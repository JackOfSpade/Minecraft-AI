package io.github.zoyluo.aibot.mining.assist;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.observe.BotProfiler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shadow POI evaluation for one bot (mining-assist design 6.2 and 6.3, phase P0: scoring and logging
 * only). Once per {@value PoiScorer#EVAL_INTERVAL_TICKS} ticks it builds a {@link PoiSignals} snapshot from
 * the sensor's memories, runs {@link PoiScorer#evaluate}, and feeds the per-candidate hysteresis. It acts on
 * nothing: the caller logs the returned {@link Result} and no task, chat line or model call follows from it.
 *
 * <p>It reads only observed data. Evidence cells come from the state's POI window, which the sweep and the
 * break peek filled from first hits. The entity scan is one {@code getEntitiesByClass} per evaluation, and an
 * entity is kept only if {@code ObservableWorldQuery.canObserveEntity} allows it and it is not invisible.
 * The only other world read is the biome id at the bot's own feet (the F3 equivalent), which goes into the
 * state for the deep-dark veto, the lush-caves classification flag and the logs; it is not a scoring term.</p>
 *
 * <p>This is a heavy operation in the design's "one heavy op per bot per tick" rule: the coordinator must
 * not run another heavy operation for the same bot in the same tick.</p>
 */
public final class PoiDetector {
    /** Profiler section of one evaluation (entity scan, assembly, scoring). Observability only. */
    public static final String SECTION_POI = "assist_poi";
    /** Most entities that are accepted as evidence per scan. */
    public static final int ENTITY_CANDIDATE_CAP = 48;
    /**
     * Most entities that get the (raycast) visibility check per scan. The scan visits wardens first and then the
     * nearest first, and only an entity that passes the check counts toward {@link #ENTITY_CANDIDATE_CAP}, so
     * evidence-type entities the bot cannot see (a modded mob farm behind a wall) cannot crowd a visible warden
     * or a nearer structure mob out of the scan.
     */
    public static final int ENTITY_EXAMINE_CAP = 96;

    private PoiDetector() {
    }

    /**
     * What one evaluation produced. {@code evaluated} is false only for {@link #NOT_RUN}.
     * {@code bandChanged} is true when {@code band} differs from the previous evaluation of this bot.
     * {@code confirmed} is the design's "hysteresis satisfied AND possible-class band" (POSSIBLE or
     * CAVERN_ONLY on at least 2 of the last 3 evaluations).
     */
    public record Result(boolean evaluated,
                         PoiScorer.Band band,
                         PoiScorer.PoiScore score,
                         String label,
                         boolean confirmed,
                         boolean bandChanged,
                         BlockPos anchor,
                         int windowCells,
                         int entitiesCounted,
                         int hits,
                         String biome,
                         boolean deepDark) {
        public static final Result NOT_RUN =
                new Result(false, PoiScorer.Band.NONE, null, "", false, false, null, 0, 0, 0, "", false);

        /** Key-value pairs for a {@code BotLog} line; only meaningful when {@link #evaluated}. */
        public Object[] logFields() {
            PoiScorer.PoiScore s = score;
            return new Object[] {
                    "band", band,
                    "t", format(s == null ? 0.0D : s.t()),
                    "s", format(s == null ? 0.0D : s.s()),
                    "c", format(s == null ? 0.0D : s.c()),
                    "e", format(s == null ? 0.0D : s.e()),
                    "cells", s == null ? 0 : s.distinctCells(),
                    "buckets", s == null ? 0 : s.distinctBuckets(),
                    "label", label,
                    "confirmed", confirmed,
                    "hits", hits,
                    "trigger", s == null ? "" : s.mandatoryTrigger(),
                    "habitation_like", s != null && s.habitationLike(),
                    "downgraded", s != null && s.downgradedByHabitation(),
                    "window", windowCells,
                    "entities", entitiesCounted,
                    "anchor", anchor == null ? "-" : anchor.getX() + "," + anchor.getY() + "," + anchor.getZ(),
                    "biome", biome.isEmpty() ? "-" : biome,
                    "deep_dark", deepDark
            };
        }

        private static String format(double value) {
            return String.format(java.util.Locale.ROOT, "%.3f", value);
        }
    }

    /**
     * P1 (mining-assist design 4.4 item 8, M18): reads the own-cell biome id and stores it, independent of
     * {@code poi.enabled}. This is the only place in the package that reads the world's biome (moved out of
     * {@link #evaluate}, which used to run this only inside the shadow POI pass); {@code task/DetourSafetyGate}
     * calls it directly when {@code MiningAssistState.staleOrNever(tick, state.biomeTick(), 20)}.
     */
    public static void refreshBiome(AIPlayerEntity bot, MiningAssistState state, ServerWorld world, int serverTick) {
        BlockPos feet = bot.getBlockPos();
        state.setBiome(world.getBiome(feet).getKey().map(key -> key.getValue().toString()).orElse(""));
        state.noteBiomeRead(serverTick);
    }

    /**
     * True when this bot's evaluation is due at {@code serverTick}. The first call arms a stagger of
     * {@code uuid.hashCode() & 15} ticks (so bots do not all evaluate on the same tick) and returns
     * false; afterwards it is due every {@value PoiScorer#EVAL_INTERVAL_TICKS} ticks.
     */
    public static boolean due(MiningAssistState state, int serverTick) {
        int next = state.nextPoiEvalTick();
        if (next == MiningAssistState.NEVER) {
            state.setNextPoiEvalTick(serverTick + (state.botId().hashCode() & 15));
            return false;
        }
        return serverTick >= next || next - serverTick > PoiScorer.EVAL_INTERVAL_TICKS + 16;
    }

    /**
     * Runs one shadow evaluation and schedules the next one. Never throws for a normal world; call only
     * while the assist gate is open and only when {@link #due} says so.
     */
    public static Result evaluate(AIPlayerEntity bot, MiningAssistState state, ServerWorld world, int serverTick) {
        long started = System.nanoTime();
        state.setNextPoiEvalTick(serverTick + PoiScorer.EVAL_INTERVAL_TICKS);
        MiningAssistConfig config = MiningAssistRuntime.config();
        double radius = SenseBudget.sweepRadius(AIBotConfig.get().perception().radius());
        String dimension = BotEdits.dimensionKey(world);
        state.enterDimension(dimension);

        BlockPos feet = bot.getBlockPos();
        refreshBiome(bot, state, world, serverTick);

        EntityEvidence entities = scanEntities(bot, world, radius);
        state.counters().entityScans++;
        state.poiWindow().expire(serverTick);

        Vec3d eye = bot.getEyePos();
        FreeRunStats.Openness openness = state.ring().openness(
                serverTick, SweepEngine.eyeCell(eye.x, eye.y, eye.z), radius);
        noteCavernGate(bot, state, config, openness, dimension, radius);

        PoiSignals signals = PoiAssembler.assemble(state.poiWindow(), bot.getX(), bot.getY(), bot.getZ(),
                entities, openness, radius, dimension, config.poi().cavernDimensions());
        PoiScorer.PoiScore score = PoiScorer.evaluate(signals);
        state.notePoiScore(serverTick, score.s());
        PoiScorer.Band band = score.band();

        BlockPos anchor = score.centroidBlock() != null ? score.centroidBlock() : feet;
        PoiCandidates.Tracked tracked = state.poiCandidates()
                .record(anchor, score, serverTick, config.poi().dedupeRadius());
        boolean confirmed = tracked.satisfied() && band.isPossibleClass();
        boolean changed = band != state.lastPoiBand();
        state.setLastPoiBand(band);

        Result result = new Result(true, band, score, labelFor(band, signals), confirmed, changed, anchor,
                state.poiWindow().size(), entities.count(), tracked.hits(),
                state.biomeId(), state.deepDark());

        long elapsed = System.nanoTime() - started;
        SenseCounters counters = state.counters();
        counters.poiEvaluations++;
        counters.poiNanos += elapsed;
        counters.maxPoiNanos = Math.max(counters.maxPoiNanos, elapsed);
        BotProfiler.INSTANCE.record(bot, SECTION_POI, elapsed);
        return result;
    }

    /** The label a log line or notice would use: the labeler's structure label, {@code cavern} or {@code warden_risk}. */
    static String labelFor(PoiScorer.Band band, PoiSignals signals) {
        return switch (band) {
            case NONE -> "";
            case CAVERN_ONLY -> "cavern";
            case MANDATORY -> "warden_risk";
            case POSSIBLE, STRUCTURE_CERTAIN -> PoiLabeler.label(signals);
        };
    }

    /**
     * The design's one-time note: the cavern channel is disabled outside {@code poi.cavernDimensions} and
     * below a perception radius of {@value PoiScorer#CAVERN_MIN_RADIUS}. Logged once per bot, and only once
     * the ring has enough entries for the channel to matter.
     */
    private static void noteCavernGate(AIPlayerEntity bot, MiningAssistState state, MiningAssistConfig config,
                                       FreeRunStats.Openness openness, String dimension, double radius) {
        if (state.cavernDisabledLogged() || !openness.valid()) {
            return;
        }
        boolean listed = config.poi().cavernEnabledIn(dimension);
        if (listed && radius >= PoiScorer.CAVERN_MIN_RADIUS) {
            return;
        }
        state.setCavernDisabledLogged(true);
        BotLog.task(bot, "assist_cavern_channel_disabled",
                "dimension", dimension,
                "radius", radius,
                "reason", listed ? "radius_below_12" : "dimension_not_listed");
    }

    /**
     * One {@code getEntitiesByClass} over the perception box. Only entities that could contribute (a score,
     * a habitation marker or a warden) are candidates, examined wardens first and then nearest first (at most
     * {@value #ENTITY_EXAMINE_CAP} examined, {@value #ENTITY_CANDIDATE_CAP} accepted); each must be
     * visible (not invisible, not a marker armor stand) and pass {@code ObservableWorldQuery.canObserveEntity}.
     */
    private static EntityEvidence scanEntities(AIPlayerEntity bot, ServerWorld world, double radius) {
        EntityEvidence evidence = new EntityEvidence();
        Box box = bot.getBoundingBox().expand(radius);
        List<Entity> candidates = world.getEntitiesByClass(Entity.class, box,
                entity -> entity != bot && entity.isAlive() && !entity.isInvisible() && isEvidenceType(entity));
        List<Entity> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.<Entity, Boolean>comparing(entity -> !isWardenType(entity))
                .thenComparingDouble(entity -> bot.squaredDistanceTo(entity)));
        int examined = 0;
        int accepted = 0;
        for (Entity entity : ordered) {
            if (examined >= ENTITY_EXAMINE_CAP || accepted >= ENTITY_CANDIDATE_CAP) {
                break;
            }
            examined++;
            if (entity instanceof ArmorStandEntity stand && stand.isMarker()) {
                continue;
            }
            if (!ObservableWorldQuery.canObserveEntity(bot, entity)) {
                continue;
            }
            Identifier id = Registries.ENTITY_TYPE.getId(entity.getType());
            evidence.add(id.getNamespace(), id.getPath());
            accepted++;
        }
        return evidence;
    }

    private static boolean isWardenType(Entity entity) {
        Identifier id = Registries.ENTITY_TYPE.getId(entity.getType());
        return PoiLexicon.isWarden(id.getNamespace(), id.getPath());
    }

    private static boolean isEvidenceType(Entity entity) {
        Identifier id = Registries.ENTITY_TYPE.getId(entity.getType());
        String namespace = id.getNamespace();
        String path = id.getPath();
        return PoiLexicon.entityScore(namespace, path) > 0.0D
                || PoiLexicon.isWarden(namespace, path)
                || PoiLexicon.habitationKey(namespace, path) != null;
    }
}

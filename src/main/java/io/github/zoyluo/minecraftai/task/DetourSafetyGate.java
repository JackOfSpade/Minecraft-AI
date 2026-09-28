package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mining.MiningEvidenceAudit;
import io.github.zoyluo.minecraftai.mining.assist.AssistGate;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistConfig;
import io.github.zoyluo.minecraftai.mining.assist.MandatoryLatch;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistRegistry;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistRuntime;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistState;
import io.github.zoyluo.minecraftai.mining.assist.PoiDetector;
import io.github.zoyluo.minecraftai.mining.assist.PoiScorer;
import io.github.zoyluo.minecraftai.mining.assist.SafeGate;
import io.github.zoyluo.minecraftai.mining.assist.SafeGateInputs;
import io.github.zoyluo.minecraftai.mining.assist.SafeReason;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * The Minecraft side of the detour SAFE gate (mining-assist design 4.4, invariant I7): reads live state and hands
 * plain values to the pure {@link SafeGate}. It lives in the {@code task} package because it needs the
 * package-private {@code DangerWatcher} helpers ({@code hasObservableHostilePressure}, {@code shelterEpisodeActive},
 * and the two additions of P1, {@code observedLavaInThreatBox} and {@code threatCooldownActive}). Every read is
 * <b>live</b>: tasks tick before the watchers, so a verdict cached by DangerWatcher is one tick old and never used.
 *
 * <h2>What it reads (all through existing, already reviewed sources)</h2>
 * <ul>
 *   <li>Item 1: {@code modeAllowsDetour} is {@code cfg.detourActive()} AND the per-bot harness rule
 *       ({@code AssistGate.denyReason(cfg.mode(), cfg.harnessOff(), MiningAssistRuntime.isForced(uuid), true, false,
 *       false) == null}, the origin, audit and TPS arguments being separate items), so a bot that is not opted in
 *       while the harness default is off never detours even though a state exists; the origin kind of
 *       {@code TaskManager.INSTANCE.activeOrigin(bot)} via {@code AssistGate.isRealOrigin} (the record is
 *       {@code io.github.zoyluo.minecraftai.runtime.TaskOrigin}); and {@code MiningEvidenceAudit.hasSession(uuid)}.</li>
 *   <li>Item 2: {@code MiningAssistRuntime.tpsDegraded(bot)} (made public by P1) and
 *       {@code MiningAssistRuntime.headroom()}: START asks {@code canStart(tps)}, the TICK stages ask
 *       {@code shouldAbort(tps)} (which disarms, so never call a TICK stage without a live detour).</li>
 *   <li>Item 3: bot health, {@code hurtTime}, fire, lava, submerged, touching water, food level,
 *       {@code MinecraftAiConfig.get().combat().retreatHp()}, {@code survival().hungerCriticalThreshold()},
 *       {@code detour.startHpMargin}.</li>
 *   <li>Item 4: {@code NavSafetyNet.INSTANCE.isWaterRescueActive}, {@code TaskManager.pausedDepth},
 *       {@code isUserPaused}, origin SAFETY.</li>
 *   <li>Items 5 and 6: {@code DangerWatcher.INSTANCE.threatCooldownActive(bot, tick)},
 *       {@code shelterEpisodeActive(bot)}, {@code DangerWatcher.hasObservableHostilePressure(bot)}.</li>
 *   <li>Item 7: {@code DangerWatcher.observedLavaInThreatBox(bot, ore)} (5x3x5, observation gated; excludes a lava
 *       cell that is a face neighbour of {@code ore} while {@code ore} currently reads air, deferring a fluid the
 *       detour's own just-completed break exposed to design 4.7's seal-or-abort instead of double-aborting it
 *       here) and the bot's {@code HazardField} ({@code anyLavaWithin}) around the bot, {@code pose} and
 *       {@code ore} with {@code detour.lavaClearRadius}.</li>
 *   <li>Item 8: the own-cell biome id is the deep dark ({@code state.deepDark()}) and {@code safety.deepDarkVeto}.
 *       The biome is refreshed here through {@code PoiDetector.refreshBiome} when
 *       {@code MiningAssistState.staleOrNever(tick, state.biomeTick(), 20)} (never a bare {@code tick - biomeTick},
 *       which overflows for the NEVER sentinel), independent of {@code poi.enabled} and {@code poi.useOwnBiome}.</li>
 *   <li>Item 9: fail closed on stale facts: {@code poiEvidenceStale} is true when {@code poi.enabled} and
 *       {@code staleOrNever(tick, state.poiScoreTick(), 60)} (the score was never computed, or is older than about
 *       three POI passes), or when there is no state; the structure score {@code state.poiStructureScore()};
 *       {@code SafeGate.poiWindowVeto(state.poiWindow())}; a pending candidate through
 *       {@code SafeGate.candidatePending(state.lastPoiBand(), anyCandidateHysteresisSatisfied,
 *       CAVERN_BLOCKS_DETOUR)} (a tracked candidate that merely exists does not count); the no-detour zone reads
 *       {@code MandatoryLatch.inNoDetourZone}.</li>
 *   <li>Item 10: {@code HazardField.anyTrapWithin(centre, 3)} around the bot, {@code pose} and {@code ore}.</li>
 * </ul>
 * A missing {@code MiningAssistState} FAILS CLOSED: {@code poiEvidenceStale} is set (the gate answers POI_EVIDENCE)
 * and no hazard fact is invented. {@code OreDigTask.tickOpportunistic} never asks the gate without a state (a live
 * detour whose state was dropped aborts {@code safety_state_lost} first), so this is a backstop.
 *
 * <p><b>Constant.</b> {@link #CAVERN_BLOCKS_DETOUR} decides whether a {@code CAVERN_ONLY} POI band counts as a
 * pending candidate (P1 contract Q2). It is true (design-literal); flipping it is the one-place change if shadow
 * logs show it starves detours in ordinary caves.</p>
 *
 * <p>Cost discipline: {@code inputs} fills only the fields of the items the stage reads
 * ({@code SafeGate.Stage.reads(item)}); the entity query of item 6 and the 75 observation rays of item 7 run
 * only for START and TICK_FULL. No block state is read here; this class touches no world block, so it is not in
 * the {@code PrivilegedBoundarySourceTest} must-contain set.</p>
 */
public final class DetourSafetyGate {
    /** P1 contract Q2: a CAVERN_ONLY POI band counts as a pending candidate (true, design-literal). */
    public static final boolean CAVERN_BLOCKS_DETOUR = true;

    private DetourSafetyGate() {
    }

    /**
     * Builds the inputs for {@code stage}. {@code pose} and {@code ore} may be null (the start check has none
     * yet); when given they extend the lava and trap radius checks to those cells.
     */
    public static SafeGateInputs inputs(AIPlayerEntity bot, SafeGate.Stage stage, BlockPos pose, BlockPos ore) {
        UUID uuid = bot.getUuid();
        MiningAssistConfig cfg = MiningAssistRuntime.config();
        MiningAssistState state = MiningAssistRegistry.getIfPresent(uuid);
        int tick = MiningAssistRuntime.serverTick(bot);
        SafeGateInputs.Builder b = SafeGateInputs.builder();

        if (stage.reads(1)) {
            boolean modeAllowsDetour = cfg.detourActive()
                    && AssistGate.denyReason(cfg.mode(), cfg.harnessOff(), MiningAssistRuntime.isForced(uuid),
                            true, false, false) == null;
            boolean originReal = TaskManager.INSTANCE.activeOrigin(bot)
                    .map(origin -> AssistGate.isRealOrigin(origin.kind().name()))
                    .orElse(false);
            b.modeAllowsDetour(modeAllowsDetour)
                    .originReal(originReal)
                    .auditSession(MiningEvidenceAudit.hasSession(uuid));
        }
        if (stage.reads(2)) {
            boolean tpsDegraded = MiningAssistRuntime.tpsDegraded(bot);
            b.tpsDegraded(tpsDegraded);
            if (stage.isStart()) {
                b.headroomStartOk(MiningAssistRuntime.headroom().canStart(tpsDegraded));
            } else {
                b.headroomAbort(MiningAssistRuntime.headroom().shouldAbort(tpsDegraded));
            }
        }
        if (stage.reads(3)) {
            MinecraftAiConfig.Combat combat = MinecraftAiConfig.get().combat();
            MinecraftAiConfig.Survival survival = MinecraftAiConfig.get().survival();
            b.health(bot.getHealth())
                    .retreatHp(combat.retreatHp())
                    .startHpMargin(cfg.detour().startHpMargin())
                    .hurtTime(bot.hurtTime)
                    .onFire(bot.isOnFire())
                    .inLava(bot.isInLava())
                    .submerged(bot.isSubmergedInWater())
                    .touchingWater(bot.isTouchingWater())
                    .foodLevel(bot.getHungerManager().getFoodLevel())
                    .hungerCritical(survival.hungerCriticalThreshold());
        }
        if (stage.reads(4)) {
            b.waterRescueActive(NavSafetyNet.INSTANCE.isWaterRescueActive(bot))
                    .pausedDepth(TaskManager.INSTANCE.pausedDepth(bot))
                    .userPaused(TaskManager.INSTANCE.isUserPaused(bot))
                    .originSafety(TaskManager.INSTANCE.activeOrigin(bot).map(TaskOrigin::safety).orElse(false));
        }
        if (stage.reads(5)) {
            b.threatCooldown(DangerWatcher.INSTANCE.threatCooldownActive(bot, tick))
                    .shelterEpisode(DangerWatcher.INSTANCE.shelterEpisodeActive(bot));
        }
        if (stage.reads(6)) {
            b.hostilePressure(DangerWatcher.hasObservableHostilePressure(bot));
        }
        if (stage.reads(7)) {
            b.lavaInThreatBox(DangerWatcher.observedLavaInThreatBox(bot, ore).isPresent());
            int radius = cfg.detour().lavaClearRadius();
            boolean hazardLavaNear = state != null && (
                    state.hazards().anyLavaWithin(bot.getBlockPos(), radius)
                    || (pose != null && state.hazards().anyLavaWithin(pose, radius))
                    || (ore != null && state.hazards().anyLavaWithin(ore, radius)));
            b.hazardLavaNear(hazardLavaNear);
        }
        if (stage.reads(8)) {
            if (state == null) {
                b.deepDark(false);
            } else {
                if (MiningAssistState.staleOrNever(tick, state.biomeTick(), 20)) {
                    ServerWorld world = bot.getEntityWorld();
                    PoiDetector.refreshBiome(bot, state, world, tick);
                }
                b.deepDark(cfg.safety().deepDarkVeto() && state.deepDark());
            }
        }
        if (stage.reads(9)) {
            if (state == null) {
                b.poiEvidenceStale(true);
            } else {
                boolean stale = cfg.poi().enabled()
                        && MiningAssistState.staleOrNever(tick, state.poiScoreTick(), 3 * PoiScorer.EVAL_INTERVAL_TICKS);
                boolean anyCandidateSatisfied = state.poiCandidates().snapshot().stream()
                        .anyMatch(candidate -> candidate.hysteresis().satisfied(tick));
                b.poiEvidenceStale(stale)
                        .poiStructureScore(state.poiStructureScore())
                        .poiWindowVeto(SafeGate.poiWindowVeto(state.poiWindow()))
                        .poiCandidatePending(SafeGate.candidatePending(state.lastPoiBand(), anyCandidateSatisfied,
                                CAVERN_BLOCKS_DETOUR))
                        .inNoDetourZone(MandatoryLatch.inNoDetourZone(uuid, state.dimensionKey(), bot.getBlockPos()));
            }
        }
        if (stage.reads(10)) {
            int radius = 3;
            boolean trapNear = state != null && (
                    state.hazards().anyTrapWithin(bot.getBlockPos(), radius)
                    || (pose != null && state.hazards().anyTrapWithin(pose, radius))
                    || (ore != null && state.hazards().anyTrapWithin(ore, radius)));
            b.trapNear(trapNear);
        }
        return b.build();
    }

    /** {@code SafeGate.evaluate(inputs(bot, stage, pose, ore), stage)}. */
    public static SafeReason evaluate(AIPlayerEntity bot, SafeGate.Stage stage, BlockPos pose, BlockPos ore) {
        return SafeGate.evaluate(inputs(bot, stage, pose, ore), stage);
    }

    /**
     * Design 6.5 {@code safeToHold}: true when {@link SafeGate.Stage#HOLD} finds no hostile pressure, health
     * above {@code combat.retreatHp()} and no hurt flash. {@code coordination.PoiCoordinator} calls this (it
     * cannot reach {@code DangerWatcher.hasObservableHostilePressure} itself, package-private in {@code task})
     * rather than the general {@link #evaluate}, since a hold is not a detour start/tick and reads none of
     * the mode/origin/headroom/pause/POI-evidence items those use.
     */
    public static boolean safeToHold(AIPlayerEntity bot) {
        return evaluate(bot, SafeGate.Stage.HOLD, null, null) == SafeReason.OK;
    }
}

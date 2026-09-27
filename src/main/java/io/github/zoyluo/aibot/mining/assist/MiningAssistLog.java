package io.github.zoyluo.aibot.mining.assist;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The shadow-mode log lines of the sensor (mining-assist design 7: {@code sense.shadowLog} logs band
 * changes and cost, nothing else). Deliberately low volume: one cost summary per bot per
 * {@value #SUMMARY_INTERVAL_TICKS} ticks while it is sensing, one line per POI band change, one line per
 * sensing session start and end, and a handful of rare-find lines per window. There is no per-ray,
 * per-tick or per-common-sighting output. Every call is a no-op while {@code sense.shadowLog} is off.
 *
 * <p>These lines exist so a played session can answer, for one mining request, whether the sensor ran, what
 * it cost (measure before acting, invariant I11) and what it believed it saw. They are logging only: nothing
 * in the bot's behaviour depends on them.</p>
 */
public final class MiningAssistLog {
    /** Ticks between two cost summaries of one bot (one minute). */
    public static final int SUMMARY_INTERVAL_TICKS = 1200;
    /** Most {@code assist_sighting} lines per bot per reporting window. */
    public static final int MAX_SIGHTING_LINES_PER_WINDOW = 6;

    private MiningAssistLog() {
    }

    private static boolean shadowLog() {
        return MiningAssistRuntime.config().sense().shadowLog();
    }

    // ---- P1 (H.1): the six detour window counters, wired from the engine's own log events -----------------

    /**
     * Classifies one of the engine's own detour log events (design 4.13, {@code docs/LOGGING.md}) into the
     * matching window counter of {@link SenseCounters} and bumps it. Pure bookkeeping, not logging: this never
     * writes a line itself (the event was already logged by the caller through {@code DetourHost.log}/
     * {@code warn}) and never touches {@code sense.shadowLog}, because the cost summary reports these counters
     * unconditionally, the same as {@code peekedBreaks} or {@code sightingsAdded}.
     *
     * <p>The six events line up one to one with the six counters: {@code ore_dig_detour_start} to
     * {@link SenseCounters#detourStarts}, {@code ore_dig_detour_break} to {@link SenseCounters#detourBreaks},
     * {@code ore_dig_detour_seal} to {@link SenseCounters#detourSeals}, {@code ore_dig_detour_drop_lost} to
     * {@link SenseCounters#detourDropsLost}, {@code ore_dig_detour_abort} to {@link SenseCounters#detourAborts}
     * (every abort reason of design 4.12, {@code ore_dig_detour_abort} is logged once per abort so this is not
     * double counted by {@code ore_dig_detour_end}), and {@code ore_dig_detour_return_rebased} to
     * {@link SenseCounters#detourRebases}. Any other event (including {@code ore_dig_detour_skip},
     * {@code _route}, {@code _end}, {@code _orphan}, {@code _cursor_drift}, {@code _lava_claimed} and
     * {@code _resume_return}, which are not window counters) is a no-op: unknown events never throw.</p>
     *
     * @param state may be null (a caller with no state, for example a race with the coordinator's own state
     *              drop, must not crash the detour over a diagnostics counter); a null state is a no-op
     */
    public static void noteDetourEvent(MiningAssistState state, String event) {
        if (state == null || event == null) {
            return;
        }
        SenseCounters counters = state.counters();
        switch (event) {
            case "ore_dig_detour_start" -> counters.detourStarts++;
            case "ore_dig_detour_break" -> counters.detourBreaks++;
            case "ore_dig_detour_seal" -> counters.detourSeals++;
            case "ore_dig_detour_drop_lost" -> counters.detourDropsLost++;
            case "ore_dig_detour_abort" -> counters.detourAborts++;
            case "ore_dig_detour_return_rebased" -> counters.detourRebases++;
            default -> { }
        }
    }

    /**
     * Writes the bot's cost summary if its reporting window is due, then starts a new window. Cheap when
     * not due (two integer compares). Category {@code PROFILE}, event {@code assist_sense_summary}.
     */
    public static void summaryIfDue(AIPlayerEntity bot, MiningAssistState state, int serverTick) {
        if (!shadowLog()) {
            return;
        }
        int windowStart = state.windowStartTick();
        SenseCounters window = state.drainWindow(serverTick, SUMMARY_INTERVAL_TICKS);
        if (window == null || window.isIdle()) {
            return;
        }
        emitSummary(bot, state, serverTick, window, windowStart, false);
    }

    /**
     * Writes the bot's last, partial window when its state is released (the bot stopped mining), so a short
     * sensing session is not lost from the cost record. Event {@code assist_sense_summary} with
     * {@code final=true}.
     */
    public static void summaryFinal(AIPlayerEntity bot, MiningAssistState state, int serverTick) {
        if (!shadowLog()) {
            return;
        }
        int windowStart = state.windowStartTick();
        SenseCounters window = state.takeWindow(serverTick);
        if (window.isIdle()) {
            return;
        }
        emitSummary(bot, state, serverTick, window, windowStart, true);
    }

    private static void emitSummary(AIPlayerEntity bot, MiningAssistState state, int serverTick,
                                    SenseCounters window, int windowStart, boolean last) {
        double radius = SenseBudget.sweepRadius(AIBotConfig.get().perception().radius());
        Vec3d eye = bot.getEyePos();
        FreeRunStats.Openness openness = state.ring().openness(
                serverTick, SweepEngine.eyeCell(eye.x, eye.y, eye.z), radius);
        List<SightingLedger.Sighting> top = state.sightings().snapshotSortedByValueDesc();
        SightingLedger.Sighting best = top.isEmpty() ? null : top.get(0);
        int elapsed = windowStart == MiningAssistState.NEVER ? 0 : Math.max(0, serverTick - windowStart);
        BotLog.profile(bot, "assist_sense_summary",
                "final", last,
                "window_ticks", elapsed,
                "steps", window.steps,
                "rays", window.rays,
                "unknown_rays", window.unknownRays,
                "throttled_out", window.raysThrottledOut,
                "decor_rays", window.decorRays,
                "decor_cells", window.decorEvidence,
                "sweeps", window.sweepsCompleted,
                "breakthroughs", window.breakthroughs,
                "breakthroughs_deferred", window.breakthroughsDeferred,
                "peeked_breaks", window.peekedBreaks,
                "breaks_unconfirmed", window.breaksUnconfirmed,
                "sightings_added", window.sightingsAdded,
                "sightings_rejected", window.sightingsRejected,
                "sightings", state.sightings().size(),
                "best_sighting", best == null ? "-" : best.blockId() + "@" + best.pos().getX() + ","
                        + best.pos().getY() + "," + best.pos().getZ(),
                "lava_new", window.lavaCells,
                "water_new", window.waterCells,
                "trap_new", window.trapCells,
                "hazards", state.hazards().count(),
                "poi_cells_new", window.poiCellsAdded,
                "poi_window", state.poiWindow().size(),
                "poi_evals", window.poiEvaluations,
                "poi_bands_withheld", window.poiBandsSuppressed,
                "step_ms_avg", ms(window.steps == 0 ? 0L : window.sweepNanos / window.steps),
                "step_ms_max", ms(window.maxStepNanos),
                "fold_ms_avg", ms(window.steps == 0 ? 0L : window.foldNanos / window.steps),
                "poi_ms_avg", ms(window.poiEvaluations == 0 ? 0L : window.poiNanos / window.poiEvaluations),
                "poi_ms_max", ms(window.maxPoiNanos),
                "open_valid", openness.valid(),
                "open_fraction", format(openness.fraction()),
                "open_up_free", format(openness.upFree()),
                "radius", radius,
                "biome", state.biomeId().isEmpty() ? "-" : state.biomeId(),
                "deep_dark", state.deepDark());
    }

    /**
     * Logs a POI evaluation when the bot's band differs from the band of its last line (category {@code TASK},
     * event {@code assist_poi_band}), at most one line per {@value PoiBandLogGate#MIN_GAP_TICKS} ticks (see
     * {@link PoiBandLogGate}). A quiet bot logs nothing: the band stays NONE. The line carries
     * {@code withheld}, the number of band changes the gate held back since the previous line; the cost summary
     * carries the window total.
     */
    public static void poiBand(AIPlayerEntity bot, MiningAssistState state, PoiDetector.Result result, int serverTick) {
        if (result == null || !result.evaluated() || !shadowLog()) {
            return;
        }
        int withheld = state.poiBandGate().consider(result.band(), result.bandChanged(), serverTick);
        if (withheld == PoiBandLogGate.NO_LINE) {
            if (result.bandChanged()) {
                state.counters().poiBandsSuppressed++;
            }
            return;
        }
        Object[] fields = result.logFields();
        Object[] line = Arrays.copyOf(fields, fields.length + 2);
        line[fields.length] = "withheld";
        line[fields.length + 1] = withheld;
        BotLog.task(bot, "assist_poi_band", line);
    }

    /** A sensing session started (category {@code TASK}, event {@code assist_sense_enabled}). */
    public static void senseEnabled(AIPlayerEntity bot, String task, String dimension, BlockPos feet, AssistMode mode) {
        if (!shadowLog()) {
            return;
        }
        BotLog.task(bot, "assist_sense_enabled",
                "task", task,
                "mode", mode,
                "dimension", dimension,
                "feet", feet.getX() + "," + feet.getY() + "," + feet.getZ());
    }

    /**
     * A sensing session ended after {@value SenseStatus#DISABLE_GRACE_TICKS} quiet ticks (category
     * {@code TASK}, event {@code assist_sense_disabled}). {@code reason} is a {@link SensePlan.Verdict}
     * reason token, {@code deny} the gate's deny reason when the gate closed ({@code -} otherwise).
     */
    public static void senseDisabled(AIPlayerEntity bot, String reason, String deny, MiningAssistState state) {
        if (!shadowLog()) {
            return;
        }
        BotLog.task(bot, "assist_sense_disabled",
                "reason", reason,
                "deny", deny == null ? "-" : deny,
                "rays_total", state == null ? 0L : state.lifetimeRays(),
                "sweeps_total", state == null ? 0L : state.lifetimeSweeps());
    }

    /** The bot's state was dropped after it stopped mining (category {@code TASK}, event {@code assist_state_released}). */
    public static void stateReleased(AIPlayerEntity bot, MiningAssistState state, int idleTicks) {
        if (!shadowLog()) {
            return;
        }
        BotLog.task(bot, "assist_state_released",
                "reason", "idle",
                "idle_ticks", idleTicks,
                "rays_total", state.lifetimeRays(),
                "sweeps_total", state.lifetimeSweeps(),
                "sightings", state.sightings().size(),
                "hazards", state.hazards().count());
    }

    /**
     * Logs the rare finds (raw value at least {@code minValue}) that were first seen at {@code serverTick},
     * at most {@value #MAX_SIGHTING_LINES_PER_WINDOW} per reporting window. Call it only when a sighting worth
     * announcing was added this pass ({@code SenseCounters#newSightingMaxValue} at least {@code minValue}),
     * so the ledger snapshot is not taken every tick and a coal or copper cave never pays for it. Category
     * {@code TASK}, event
     * {@code assist_sighting}: it records what the sensor nominated, not what the bot mined.
     *
     * @return the number of lines written
     */
    public static int sightings(AIPlayerEntity bot, MiningAssistState state, int serverTick, int minValue) {
        if (!shadowLog()) {
            return 0;
        }
        Vec3d eye = bot.getEyePos();
        int written = 0;
        for (SightingLedger.Sighting sighting : state.sightings().snapshotSortedByValueDesc()) {
            if (sighting.rawValue() < minValue) {
                break;
            }
            if (sighting.firstTick() != serverTick) {
                continue;
            }
            if (state.counters().sightingsLogged >= MAX_SIGHTING_LINES_PER_WINDOW) {
                break;
            }
            BlockPos pos = sighting.pos();
            double dx = pos.getX() + 0.5D - eye.x;
            double dy = pos.getY() + 0.5D - eye.y;
            double dz = pos.getZ() + 0.5D - eye.z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            state.counters().sightingsLogged++;
            written++;
            BotLog.task(bot, "assist_sighting",
                    "block", sighting.blockId(),
                    "pos", pos.getX() + "," + pos.getY() + "," + pos.getZ(),
                    "value", sighting.rawValue(),
                    "dist", format(distance));
        }
        return written;
    }

    private static String ms(long nanos) {
        return format(nanos / 1_000_000.0D);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}

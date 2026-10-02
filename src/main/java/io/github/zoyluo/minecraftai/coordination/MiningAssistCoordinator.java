package io.github.zoyluo.minecraftai.coordination;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mining.assist.BotEdits;
import io.github.zoyluo.minecraftai.mining.assist.BreakPeek;
import io.github.zoyluo.minecraftai.mining.assist.DetourControl;
import io.github.zoyluo.minecraftai.mining.assist.DetourLiveness;
import io.github.zoyluo.minecraftai.mining.assist.DetourPhase;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistConfig;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistLog;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistRegistry;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistRuntime;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistState;
import io.github.zoyluo.minecraftai.mining.assist.OreClaims;
import io.github.zoyluo.minecraftai.mining.assist.PoiDetector;
import io.github.zoyluo.minecraftai.mining.assist.PoiScorer;
import io.github.zoyluo.minecraftai.mining.assist.SensePlan;
import io.github.zoyluo.minecraftai.mining.assist.SenseStatus;
import io.github.zoyluo.minecraftai.mining.assist.ViewSweeper;
import io.github.zoyluo.minecraftai.task.DescendToYTask;
import io.github.zoyluo.minecraftai.task.DigDownTask;
import io.github.zoyluo.minecraftai.task.MineTask;
import io.github.zoyluo.minecraftai.task.MineValuablesTask;
import io.github.zoyluo.minecraftai.task.OreDigTask;
import io.github.zoyluo.minecraftai.task.Task;
import io.github.zoyluo.minecraftai.task.TaskManager;
import io.github.zoyluo.minecraftai.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import java.util.UUID;

/**
 * Per-bot, per-tick driver of the mining assist (mining-assist design 2.3 step 3), phase P0: it senses in
 * shadow and acts on nothing. {@code BotTickCoordinator} calls {@link #tickBot} once per bot per tick, after
 * the danger scan has said whether it {@code handled} the tick and before the goal executor runs.
 *
 * <ul>
 *   <li><b>Never consumes the tick.</b> It returns nothing and the caller's control flow does not depend on it.</li>
 *   <li><b>Never throws.</b> Everything runs inside one fence; a failure logs once per bot per minute, drops
 *       that bot's assist state and pauses its sensing for a few seconds.</li>
 *   <li><b>Cheapest checks first.</b> With the mode off (or the harness default off and no forced bot) the
 *       first statement returns after one static read. After that: a handled tick, then the task class, then
 *       the gate (cached 20 ticks per bot), then one own-cell sky read (design 2.3 3e).</li>
 *   <li><b>Senses only underground, only for mining classes.</b> OreDig, DigDown, DescendToY, MineTask and
 *       MineValuables (the retired legacy StripMineTask is not covered).
 *       DigDown is sensed in both of its phases in P0 because nothing acts on what is sensed.</li>
 *   <li><b>No behaviour change.</b> The sensor and the shadow POI scorer only fill the bot's own memories and
 *       write log lines. This class never pauses, walks, chats, calls a model or assigns work (G1, G2, I5).</li>
 *   <li><b>One heavy operation per bot per tick.</b> The only heavy operation in P0 is the POI evaluation
 *       (entity scan plus scoring), every 20 ticks staggered by {@code uuid.hashCode() & 15}; the ray sweep
 *       and the break peek are bounded (at most {@value BreakPeek#MAX_BREAKS_PER_TICK} peeked breaks, the
 *       throttled ray budget).</li>
 * </ul>
 *
 * <p>Server thread only. Profiler sections ({@code assist_sweep}, {@code assist_fold}, {@code assist_poi}) are
 * recorded by the adapters this class calls and are observability only.</p>
 */
public final class MiningAssistCoordinator {
    public static final MiningAssistCoordinator INSTANCE = new MiningAssistCoordinator();

    private MiningAssistCoordinator() {
    }

    /**
     * @param handled the danger scan handled this bot this tick; the sensor then stays out of the way
     *                (design 2.3 step 3d: if handled, stop here)
     */
    public void tickBot(MinecraftServer server, AIPlayerEntity bot, boolean handled) {
        if (!MiningAssistRuntime.senseConfigured()) {
            return;
        }
        int tick = server.getTickCount();
        try {
            run(bot, tick, handled);
        } catch (RuntimeException exception) {
            fail(bot, tick, exception);
        }
    }

    /** Design 6.8 "Multi-bot routing": true while a POI stop is open for this bot. */
    public static boolean awaitingContinue(AIPlayerEntity bot) {
        return PoiCoordinator.INSTANCE.awaitingContinue(bot);
    }

    private static void run(AIPlayerEntity bot, int tick, boolean handled) {
        // P1 (F.2, design 2.3 steps 3b/3c): a live detour must be tended even in the very ticks right after a
        // sensor fault, which is exactly when the fault cooldown below would otherwise skip this bot. Its own
        // fence keeps a still-unfinished writer's stub, or any other failure here, from touching sensing at all.
        try {
            maintainDetour(bot, tick);
        } catch (RuntimeException ignored) {
            // maintainDetour must never take down the sensor pass that follows.
        }
        // P2 (design 6.5 "Restart during a hold"): an open POI stop must be tended (resume detection, restart
        // rehydration) even while the fault cooldown below would skip this bot, and even while the bot is paused
        // and therefore has no sensed task at all.
        try {
            PoiCoordinator.INSTANCE.tick(bot, tick);
        } catch (RuntimeException ignored) {
            // must never take down the sensor pass that follows.
        }
        UUID botId = bot.getUUID();
        if (MiningAssistRuntime.failures().coolingDown(botId, tick)) {
            return;
        }
        SensePlan.Verdict verdict = SensePlan.decide(handled,
                () -> isSensedTask(TaskManager.INSTANCE.getActive(bot).orElse(null)),
                () -> MiningAssistRuntime.enabledFor(bot, tick),
                () -> !bot.level().canSeeSky(bot.blockPosition()));
        if (verdict.senses()) {
            sense(bot, tick);
        } else if (!verdict.neutral()) {
            notSensing(bot, tick, verdict);
        }
    }

    /** The five mining classes the design senses for (6.1); StripMineTask is deliberately absent. */
    private static boolean isSensedTask(Task task) {
        return task instanceof OreDigTask
                || task instanceof DigDownTask
                || task instanceof DescendToYTask
                || task instanceof MineTask
                || task instanceof MineValuablesTask;
    }

    /** Design 2.3 step 3b and 3c: live derivation of the published detour, orphan cleanup, tick-granular net. */
    private static void maintainDetour(AIPlayerEntity bot, int tick) {
        MiningAssistState state = MiningAssistRegistry.getIfPresent(bot.getUUID());
        int hurt = bot.hurtTime;
        if (state == null || state.detourOwner() == null) {
            return;                     // note: hurtTimeSeen is NOT touched here (M-review): it must stay stale
        }                               // only across an ABSENT detour, never mid-detour (see below)
        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        DetourLiveness.Verdict verdict = DetourLiveness.check(
                active != null && active == state.detourOwner(),
                active != null && active.state() == TaskState.RUNNING,
                state.detourPhase(), state.detourPublishedTick(), tick);
        if (verdict != DetourLiveness.Verdict.LIVE) {
            DetourPhase phase = state.detourPhase();
            DetourControl control = state.detourControl();
            int released = OreClaims.releaseAll(bot.getUUID());
            if (control != null) {
                control.abandoned(tick);          // settles the CACHED mission ledger entry the engine itself cannot reach any more
            }
            state.clearDetour();
            state.noteHurtTime(hurt);
            BotLog.task(bot, "ore_dig_detour_orphan", "cause", verdict.name().toLowerCase(java.util.Locale.ROOT),
                    "phase", phase, "claims_released", released);
            return;                                   // never stopAll(): a SAFETY task may own the action pack
        }
        boolean tpsDegraded = MiningAssistRuntime.tpsDegraded(bot);
        boolean headroomAbort = state.detourPhase().netAbortable()
                && MiningAssistRuntime.headroom().shouldAbort(tpsDegraded);
        String reason = DetourLiveness.netReason(state.detourPhase(), tpsDegraded, headroomAbort, hurt,
                state.hurtTimeSeen());
        state.noteHurtTime(hurt);                       // every LIVE run updates it, so the first hit's rise is always seen next run
        if (reason != null && state.detourControl() != null) {
            state.detourControl().abortNow(reason);
        }
    }

    private static void sense(AIPlayerEntity bot, int tick) {
        MiningAssistConfig config = MiningAssistRuntime.config();
        ServerLevel world = bot.level();
        MiningAssistState state = MiningAssistRegistry.getIfPresent(bot.getUUID());
        if (state == null) {
            state = MiningAssistRegistry.getOrCreate(bot);
        }
        // A state that was dropped by the fence and rebuilt is the recovery of a failed pass, not a new session:
        // the failure line already said so, and a persistent fault must not add an enabled line per cooldown.
        if (state.status().sensed(tick) == SenseStatus.Change.ENABLED
                && !MiningAssistRuntime.failures().takeReenableSuppression(bot.getUUID())) {
            MiningAssistLog.senseEnabled(bot, activeTaskName(bot), BotEdits.dimensionKey(world),
                    bot.blockPosition(), config.mode());
        }
        state.maintain(tick);

        state.counters().newSightingMaxValue = 0;
        BreakPeek.drain(bot, state, tick, BreakPeek.MAX_BREAKS_PER_TICK);
        ViewSweeper.step(bot, state, config.sense().raysPerTick(), tick);
        if (state.counters().newSightingMaxValue >= config.detour().announceMinValue()) {
            MiningAssistLog.sightings(bot, state, tick, config.detour().announceMinValue());
        }

        // The one heavy operation of the tick: shadow POI scoring, logged on band changes only. Scoring and
        // logging stay unconditional of mode (unchanged from P0/P1: SENSE/DETOUR keep shadow-only POI behaviour).
        // P2: a candidate that is MANDATORY/STRUCTURE_CERTAIN on this single evaluation, or POSSIBLE/CAVERN_ONLY
        // whose hysteresis is now satisfied, is handed to the coordinator -- but only once the mode allows POI
        // to act (poiActive()).
        if (config.poi().enabled() && PoiDetector.due(state, tick)) {
            PoiDetector.Result result = PoiDetector.evaluate(bot, state, world, tick);
            MiningAssistLog.poiBand(bot, state, result, tick);
            if (config.poiActive() && (result.band() == PoiScorer.Band.MANDATORY
                    || result.band() == PoiScorer.Band.STRUCTURE_CERTAIN
                    || result.confirmed())) {
                PoiCoordinator.INSTANCE.onCandidate(bot, state, world, result, tick);
            }
        }
        MiningAssistLog.summaryIfDue(bot, state, tick);
    }

    /** The bot is not being sensed this tick (not mining, gate closed, or on the surface). */
    private static void notSensing(AIPlayerEntity bot, int tick, SensePlan.Verdict verdict) {
        MiningAssistState state = MiningAssistRegistry.getIfPresent(bot.getUUID());
        if (state == null) {
            return;
        }
        SenseStatus status = state.status();
        if (status.notSensed(tick) == SenseStatus.Change.DISABLED) {
            String deny = verdict == SensePlan.Verdict.GATE_CLOSED
                    ? MiningAssistRuntime.lastDenyReason(bot.getUUID()) : null;
            MiningAssistLog.senseDisabled(bot, verdict.reason(), deny, state);
        }
        if (status.idleFor(tick, SenseStatus.IDLE_RELEASE_TICKS)) {
            // The bot has stopped mining: drop its observation memory (lava included, I15) and its 69 KB
            // occupancy window, after one last cost line for the partial window.
            MiningAssistLog.summaryFinal(bot, state, tick);
            MiningAssistLog.stateReleased(bot, state, tick - status.lastSensedTick());
            MiningAssistRuntime.clearBot(bot);
        }
    }

    private static String activeTaskName(AIPlayerEntity bot) {
        return TaskManager.INSTANCE.getActive(bot).map(Task::name).orElse("-");
    }

    /**
     * The exception fence. The bot's assist state is dropped (it may be half updated), the failure is logged
     * at most once per bot per {@value io.github.zoyluo.minecraftai.mining.assist.SenseFailureGate#LOG_INTERVAL_TICKS}
     * ticks, and the bot's sensing pauses for a short cooldown so a persistent fault cannot cost a failing pass
     * on every tick.
     */
    private static void fail(AIPlayerEntity bot, int tick, RuntimeException exception) {
        MiningAssistRegistry.clear(bot);
        if (MiningAssistRuntime.failures().recordFailure(bot.getUUID(), tick)) {
            BlockPos feet = bot.blockPosition();
            BotLog.error(bot, "assist_tick_failed", exception,
                    "task", activeTaskName(bot),
                    "feet", feet.getX() + "," + feet.getY() + "," + feet.getZ());
        }
    }
}

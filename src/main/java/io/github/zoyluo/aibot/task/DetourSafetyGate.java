package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.mining.assist.SafeGate;
import io.github.zoyluo.aibot.mining.assist.SafeGateInputs;
import io.github.zoyluo.aibot.mining.assist.SafeReason;
import net.minecraft.util.math.BlockPos;

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
 *       {@code io.github.zoyluo.aibot.runtime.TaskOrigin}); and {@code MiningEvidenceAudit.hasSession(uuid)}.</li>
 *   <li>Item 2: {@code MiningAssistRuntime.tpsDegraded(bot)} (made public by P1) and
 *       {@code MiningAssistRuntime.headroom()}: START asks {@code canStart(tps)}, the TICK stages ask
 *       {@code shouldAbort(tps)} (which disarms, so never call a TICK stage without a live detour).</li>
 *   <li>Item 3: bot health, {@code hurtTime}, fire, lava, submerged, touching water, food level,
 *       {@code AIBotConfig.get().combat().retreatHp()}, {@code survival().hungerCriticalThreshold()},
 *       {@code detour.startHpMargin}.</li>
 *   <li>Item 4: {@code NavSafetyNet.INSTANCE.isWaterRescueActive}, {@code TaskManager.pausedDepth},
 *       {@code isUserPaused}, origin SAFETY.</li>
 *   <li>Items 5 and 6: {@code DangerWatcher.INSTANCE.threatCooldownActive(bot, tick)},
 *       {@code shelterEpisodeActive(bot)}, {@code DangerWatcher.hasObservableHostilePressure(bot)}.</li>
 *   <li>Item 7: {@code DangerWatcher.observedLavaInThreatBox(bot)} (5x3x5, observation gated) and the bot's
 *       {@code HazardField} ({@code anyLavaWithin}) around the bot, {@code pose} and {@code ore} with
 *       {@code detour.lavaClearRadius}.</li>
 *   <li>Item 8: the own-cell biome id is the deep dark ({@code state.deepDark()}) and {@code safety.deepDarkVeto}.
 *       The biome is refreshed here through {@code PoiDetector.refreshBiome} when
 *       {@code MiningAssistState.staleOrNever(tick, state.biomeTick(), 20)} (never a bare {@code tick - biomeTick},
 *       which overflows for the NEVER sentinel), independent of {@code poi.enabled} and {@code poi.useOwnBiome}.</li>
 *   <li>Item 9: fail closed on stale facts: {@code poiEvidenceStale} is true when {@code poi.enabled} and
 *       {@code staleOrNever(tick, state.poiScoreTick(), 60)} (the score was never computed, or is older than about
 *       three POI passes), or when there is no state; the structure score {@code state.poiStructureScore()};
 *       {@code SafeGate.poiWindowVeto(state.poiWindow())}; a pending candidate through
 *       {@code SafeGate.candidatePending(state.lastPoiBand(), anyCandidateHysteresisSatisfied,
 *       CAVERN_BLOCKS_DETOUR)} (a tracked candidate that merely exists does not count); the no-detour zone is
 *       false in P1.</li>
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
        throw new UnsupportedOperationException("P1 stub: DetourSafetyGate.inputs");
    }

    /** {@code SafeGate.evaluate(inputs(bot, stage, pose, ore), stage)}. */
    public static SafeReason evaluate(AIPlayerEntity bot, SafeGate.Stage stage, BlockPos pose, BlockPos ore) {
        throw new UnsupportedOperationException("P1 stub: DetourSafetyGate.evaluate");
    }
}

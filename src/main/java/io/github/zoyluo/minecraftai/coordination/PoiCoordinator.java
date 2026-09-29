package io.github.zoyluo.minecraftai.coordination;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationGate;
import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.brain.PoiAdvisor;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.memory.BotMemory;
import io.github.zoyluo.minecraftai.memory.BotMemoryStore;
import io.github.zoyluo.minecraftai.mining.assist.MandatoryLatch;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistConfig;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistRuntime;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistState;
import io.github.zoyluo.minecraftai.mining.assist.MissionAssistLedger;
import io.github.zoyluo.minecraftai.mining.assist.PoiCache;
import io.github.zoyluo.minecraftai.mining.assist.PoiConsultBudget;
import io.github.zoyluo.minecraftai.mining.assist.PoiDecisionPolicy;
import io.github.zoyluo.minecraftai.mining.assist.PoiDetector;
import io.github.zoyluo.minecraftai.mining.assist.PoiNotice;
import io.github.zoyluo.minecraftai.mining.assist.PoiPrompt;
import io.github.zoyluo.minecraftai.mining.assist.PoiRegistry;
import io.github.zoyluo.minecraftai.mining.assist.PoiScorer;
import io.github.zoyluo.minecraftai.persist.BotPersistence;
import io.github.zoyluo.minecraftai.runtime.IntentController;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import io.github.zoyluo.minecraftai.task.DetourSafetyGate;
import io.github.zoyluo.minecraftai.task.DigDownTask;
import io.github.zoyluo.minecraftai.task.TaskManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The stateful orchestrator of the R3 point-of-interest stop/notify flow (mining-assist design 6.5). This is
 * the only new file of P2 allowed to touch {@link TaskManager}, {@link IntentController}, {@link BotMemory} or
 * player messaging: {@code mining/assist/PoiNotice} is pure text rendering, and every other new P2 class in
 * {@code mining/assist} is plain bookkeeping. Singleton, {@code INSTANCE}-style exactly like
 * {@link MiningAssistCoordinator}.
 *
 * <ul>
 *   <li><b>{@link #tick}</b> runs unconditionally, every coordinator tick, for every bot, independent of
 *       sense/mode state: it tends an open hold (resume detection) and, once no in-memory case survives a
 *       restart but the bot is still user-paused, rebuilds the case from {@link BotMemory} and resends the
 *       stop notice once, prefixed {@code "Still paused: "}.</li>
 *   <li><b>{@link #onCandidate}</b> runs only when {@code PoiDetector} produced an actionable result
 *       (MANDATORY, STRUCTURE_CERTAIN, or a confirmed POSSIBLE/CAVERN_ONLY): it is design 6.5's decision
 *       tree — mandatory always checked first (bypassing {@code PoiRegistry} entirely, since a DECLINED or
 *       STOPPED registry entry must never suppress a mandatory candidate), then the dedupe registry, then the
 *       per-mission hold/stop cap, then a deterministic structure-certain stop, then {@link #possibleFlow} for
 *       POSSIBLE/CAVERN_ONLY.</li>
 *   <li><b>{@link #possibleFlow} (P3 R4).</b> Advisor unavailable (disabled, keyless, degraded TPS, or
 *       {@link PoiConsultBudget} says no) falls through to the design 6.7 fallback matrix unchanged from P2
 *       ({@link #applyFallback}); a {@link PoiCache} hit applies that verdict synchronously; otherwise a real
 *       consult starts ({@link #startConsult}), holding the mission through {@code TaskManager.
 *       pauseUserIntent} when design 6.1's task-class policy and {@code DetourSafetyGate.safeToHold} allow
 *       it. The eventual verdict — real ({@link #onAdvisorVerdict}/{@link #onAdvisorFailure}), cached, or this
 *       coordinator's own tick-based deadline ({@link #checkConsultDeadline}) — all converge on
 *       {@link #applyDecision}, which also implements design 6.5's stale-result rule: a verdict that outlives
 *       the player having already acted during the hold never resumes and never claims "Stopped."</li>
 * </ul>
 *
 * <p>Server thread only, exactly like the state it reads and writes ({@code PoiRegistry}, {@code
 * MandatoryLatch}, {@code MissionAssistLedger}, {@code TaskManager}, {@code BotMemory}).</p>
 */
public final class PoiCoordinator {
    public static final PoiCoordinator INSTANCE = new PoiCoordinator();

    /** Where a stop's decision came from. Kept only in memory ({@code PoiRegistry.OpenCase.source()}):
     * design 2.4/6.4 both say, in bold, "No {@code BotMemory} facts are written," so a restart never reads
     * this back from persistence -- see {@link #tick}'s rehydration branch, which reconstructs it from the
     * (already-persisted, MANDATORY-only) {@value #WARDEN_RISK_LABEL} label instead. */
    enum Source { MANDATORY, CERTAIN, FALLBACK }

    private static final String HOLD_PLACE_PREFIX = "poi_hold_";
    /** {@link PoiDetector#labelFor}'s label for every MANDATORY candidate, and no other band's: a reliable,
     * already-persisted discriminator for {@link #tick}'s rehydration, so no BotMemory fact is needed. */
    private static final String WARDEN_RISK_LABEL = "warden_risk";
    /** Design 6.4: "(at most 3 more)" once the per-mission hold/stop cap is reached. */
    private static final int MAX_CERTAIN_NOTIFY_AFTER_CAP = 3;
    /** Design 6.5's "else deadline = now + 300" (no hold: DigDown descent, or a hold that could not start). */
    private static final int NO_HOLD_DEADLINE_TICKS = 300;
    /** Design 6.6: at most this many nearest same-dimension prior sites go into the LLM payload.
     * Package-private: also read by {@link PoiPayloadBuilder#priorPoisFor}. */
    static final int MAX_PRIOR_POIS_IN_PAYLOAD = 3;
    /** Design 6.6: at most this many evidence lines go into the LLM payload.
     * Package-private: also read by {@link PoiPayloadBuilder#buildPayloadInput}. */
    static final int MAX_EVIDENCE_ITEMS_IN_PAYLOAD = 8;

    // This coordinator is ONE shared instance across every bot on the server, unlike OreDigTask's
    // DetourHostImpl (a private inner instance per bot/task) whose single `lastLedgerKey` field
    // (OreDigTask.java) caches into. A literal copy of that single-field cache here would let one bot's
    // ledger key leak into another bot's onCandidate call on any tick where TaskManager.activeOrigin(bot)
    // is momentarily empty for a *different* bot. Cache per bot instead.
    private final Map<UUID, String> lastLedgerKeyByBot = new HashMap<>();

    // P3: bookkeeping for an in-flight R4 consult, one at a time per bot. Keyed by a locally-minted
    // caseId (not PoiRegistry's own state, which only tracks CONSULTING/STOPPED/DECLINED dedupe entries,
    // and not TaskManager's userPauseEpoch, which tracks pause transitions, not consult identity) so a
    // late PoiAdvisor callback -- arriving after this coordinator's own tick-based deadline already
    // resolved the case via fallback -- can recognise itself as stale (design 6.5's "verified by caseId,
    // generation, bot alive"; PoiAdvisor's own generation guard covers reconfigure/shutdown races, this one
    // covers "a newer case already replaced it") and still honour design 6.6's "late replies never pause":
    // a late stop becomes a notify-only line, a late continue only fills the cache. The PendingConsult stays
    // in pendingByCaseId (marked PendingConsult.deadlineResolved) instead of being deleted the moment the
    // deadline fires, precisely so the eventual real callback can still find its cacheKey/ownsPause/
    // pauseEpoch -- see checkConsultDeadline's and onAdvisorVerdict's own comments.
    private final Map<Long, PendingConsult> pendingByCaseId = new HashMap<>();
    private final Map<UUID, Long> caseIdByBot = new HashMap<>();
    private long nextCaseId = 1L;

    private PoiCoordinator() {
    }

    /** One POSSIBLE/CAVERN_ONLY candidate's identity, carried from {@link #onCandidate} through to whichever
     * of {@link #applyFallback}/{@link #startConsult} eventually resolves it. */
    private record Candidate(String dim, BlockPos anchor, String label, PoiScorer.Band band,
                             double structureScore, boolean habitationLike) {
    }

    /** One in-flight R4 consult (mining-assist design 6.5). {@code hold}/{@code ownsPause} are almost always
     * equal (a hold that failed to actually pause -- a race, or no active origin -- keeps {@code hold} true
     * for logging but {@code ownsPause} false, since only {@code ownsPause} gates the pause-state-affecting
     * branches). {@code pauseEpoch} is {@link TaskManager#userPauseEpoch} captured right after the pause.
     * {@code deadlineResolved} is set once {@link #checkConsultDeadline} has already applied this case's
     * fallback: the entry is kept (not removed) so the still-outstanding real callback can honour design
     * 6.6's late-reply rule instead of finding nothing. */
    private record PendingConsult(long caseId, UUID botId, Candidate candidate, String ledgerKey,
                                  boolean cavernOnly, String cacheKey, boolean hold, boolean ownsPause,
                                  int pauseEpoch, int deadlineTick, boolean deadlineResolved) {
    }

    /**
     * Called unconditionally every coordinator tick, for every bot, regardless of sense/mode state: resume
     * detection for an open case, and — when no in-memory case exists but the bot is user-paused —
     * rehydration from {@link BotMemory}. Must run even while the bot has no active sensed task, so it is
     * called from {@code MiningAssistCoordinator.run()} alongside {@code maintainDetour}, not from
     * {@code sense()}.
     */
    public void tick(AIPlayerEntity bot, int serverTick) {
        UUID id = bot.getUUID();
        if (caseIdByBot.containsKey(id)) {
            // A P3 consult is (or, after this call, was) in flight for this bot: checkConsultDeadline
            // either leaves it pending (deadline not yet reached) or resolves it itself via
            // applyFallbackForPending, which sends its own notice. Either way, this bot's pause state
            // this tick is fully owned by the consult machinery, not a restart artifact -- the
            // open-case/rehydration logic below is for a crash-recovered case with no in-memory
            // bookkeeping at all, so it must not also run on the same tick.
            checkConsultDeadline(bot, serverTick);
            return;
        }
        boolean userPaused = TaskManager.INSTANCE.isUserPaused(bot);
        PoiRegistry.OpenCase open = PoiRegistry.openCase(id);
        if (open == null) {
            if (!userPaused) {
                return;
            }
            BotMemory mem = BotMemoryStore.INSTANCE.of(id);
            Optional<Map.Entry<String, BotMemory.Place>> marker = mem.firstPlaceWithPrefix(HOLD_PLACE_PREFIX);
            if (marker.isEmpty()) {
                return;
            }
            String label = marker.get().getKey().substring(HOLD_PLACE_PREFIX.length());
            // No BotMemory fact records which Source produced the hold (design 2.4/6.4). PoiNotice.renderStop
            // only branches on MANDATORY vs not, and WARDEN_RISK_LABEL is the one label PoiDetector ever hands
            // a MANDATORY candidate (never any other band), so it alone is enough to pick the right template;
            // any non-mandatory guess is equivalent to any other for that branch.
            String source = label.equals(WARDEN_RISK_LABEL) ? Source.MANDATORY.name() : Source.FALLBACK.name();
            BotMemory.Place place = marker.get().getValue();
            open = new PoiRegistry.OpenCase(label, source, place.dimension(), place.pos());
            PoiRegistry.openCase(id, open);
            if (!PoiRegistry.restartNoticeSent(id)) {
                PoiRegistry.markRestartNoticeSent(id);
                // Best-effort only: by rehydration time (a later process, or a later tick of this one) a
                // DigDownTask's own onPause already converted DESCEND to RETURN long ago (see stopNow's
                // comment), so peekPaused can no longer recover the phase it was in at the original stop.
                // Same documented-simplification idiom as PoiRegistry/MandatoryLatch's own javadoc notes.
                boolean descending = TaskManager.INSTANCE.peekPaused(bot)
                        .map(task -> task instanceof DigDownTask digDownTask && digDownTask.isDescending())
                        .orElse(false);
                String text = PoiNotice.renderStop(descending, Source.valueOf(open.source()) == Source.MANDATORY,
                        open.label(), open.anchor(), bot.blockPosition(), null, "Still paused: ");
                sendNotice(bot, bot.level(), text);
                BotLog.task(bot, "poi_restart_rehydrated", "label", label, "source", source);
            }
            return;
        }
        if (userPaused) {
            return;
        }
        if (Source.MANDATORY.name().equals(open.source())) {
            MandatoryLatch.acknowledge(id, serverTick);
        }
        BotMemory mem = BotMemoryStore.INSTANCE.of(id);
        mem.forgetPlace(HOLD_PLACE_PREFIX + open.label());
        PoiRegistry.closeCase(id);
        BotLog.task(bot, "poi_case_closed", "label", open.label(), "source", open.source());
    }

    /**
     * Called only when {@code PoiDetector} produced an actionable result (MANDATORY, STRUCTURE_CERTAIN, or a
     * confirmed POSSIBLE/CAVERN_ONLY) — design 6.5's {@code onCandidate}.
     */
    public void onCandidate(AIPlayerEntity bot, MiningAssistState state, ServerLevel world,
                            PoiDetector.Result result, int serverTick) {
        MiningAssistConfig cfg = MiningAssistRuntime.config();
        UUID id = bot.getUUID();
        String dim = state.dimensionKey();
        BlockPos anchor = result.anchor();
        String label = result.label();
        PoiScorer.PoiScore score = result.score();

        if (result.band() == PoiScorer.Band.MANDATORY) {
            mandatoryFlow(bot, world, dim, anchor, score, serverTick);
            return;
        }

        if (PoiRegistry.suppressed(id, dim, anchor, label, score.s(), serverTick)) {
            return;
        }

        MissionAssistLedger.Entry ledger = MissionAssistLedger.get(ledgerKeyFor(bot), serverTick);
        if (ledger.poiHoldsOrStops() >= cfg.poi().maxHoldsPerMission()) {
            notifyOnlyIfCertain(bot, world, dim, anchor, label, result.band(), score.s(), ledger, serverTick);
            return;
        }

        if (result.band() == PoiScorer.Band.STRUCTURE_CERTAIN) {
            stopNow(bot, world, dim, anchor, label, Source.CERTAIN, score.s(), ledger, serverTick, null, false);
            return;
        }

        // POSSIBLE or CAVERN_ONLY: design 6.5 possibleFlow.
        Candidate candidate = new Candidate(dim, anchor, label, result.band(), score.s(), score.habitationLike());
        possibleFlow(bot, world, state, result, candidate, cfg, ledger, serverTick);
    }

    /**
     * Design 6.5 {@code possibleFlow}: unavailable goes through the existing (P2) deterministic fallback
     * matrix unchanged; a cache hit applies that cached verdict synchronously; otherwise a real R4 consult
     * starts (holding the mission when allowed).
     */
    private void possibleFlow(AIPlayerEntity bot, ServerLevel world, MiningAssistState state,
                              PoiDetector.Result result, Candidate candidate, MiningAssistConfig cfg,
                              MissionAssistLedger.Entry ledger, int serverTick) {
        boolean cavernOnly = result.band() == PoiScorer.Band.CAVERN_ONLY;
        String ledgerKey = ledgerKeyFor(bot);

        if (!advisorAvailable(bot, cfg, ledgerKey, cavernOnly, serverTick)) {
            applyFallback(bot, world, candidate, ledger, serverTick);
            return;
        }

        List<String> topIds = PoiPayloadBuilder.evidenceIdsFor(state, MAX_EVIDENCE_ITEMS_IN_PAYLOAD);
        String cacheKey = PoiCache.keyFor(candidate.dim(), candidate.anchor().getX(), candidate.anchor().getY(),
                candidate.anchor().getZ(), topIds);
        PoiCache.Entry cached = PoiCache.get(cacheKey, serverTick);
        if (cached != null) {
            PoiPrompt.Decision decision = cached.stop() ? PoiPrompt.Decision.STOP : PoiPrompt.Decision.CONTINUE;
            BotLog.task(bot, "poi_cache_hit", "label", candidate.label(), "advisor_label", cached.label(), "stop", cached.stop());
            applyDecision(bot, world, candidate, ledgerKey, decision, false, 0, serverTick, false);
            return;
        }

        startConsult(bot, world, state, result, candidate, cfg, ledgerKey, cavernOnly, cacheKey, serverTick);
    }

    /** Design 6.7's fallback matrix, unchanged from P2: the entire {@code possibleFlow} body before this
     * phase existed. Used both up front (advisor unavailable) and, via {@link #applyFallbackForPending},
     * when a started consult's own deadline is reached with no verdict yet. */
    private void applyFallback(AIPlayerEntity bot, ServerLevel world, Candidate candidate,
                               MissionAssistLedger.Entry ledger, int serverTick) {
        MiningAssistConfig cfg = MiningAssistRuntime.config();
        PoiDecisionPolicy.Decision decision = PoiDecisionPolicy.decide(candidate.band(), candidate.structureScore(),
                candidate.habitationLike(), cfg.poi().unavailablePolicy(), cfg.poi().cavernKeylessPolicy());
        if (decision == PoiDecisionPolicy.Decision.STOP) {
            stopNow(bot, world, candidate.dim(), candidate.anchor(), candidate.label(), Source.FALLBACK,
                    candidate.structureScore(), ledger, serverTick, "(auto-detected)", false);
        } else if (!ledger.poiFyiCapReached()) {
            PoiRegistry.record(bot.getUUID(), candidate.dim(), candidate.anchor(), candidate.label(),
                    PoiRegistry.State.DECLINED, candidate.structureScore(), serverTick);
            ledger.notePoiFyi();
            sendNotice(bot, world, PoiNotice.renderFyi(candidate.label(), candidate.anchor(), bot.blockPosition()));
            BotLog.task(bot, "poi_fyi", "label", candidate.label(), "pos", PoiNotice.anchorStr(candidate.anchor()));
        }
    }

    /**
     * Design 6.6: "Skipped while TPS is degraded or the key is blank," plus the enabled flag and
     * {@link PoiConsultBudget}'s own breaker/in-flight/interval/mission-cap gate. Read-only: taking the
     * budget's reservation is {@link PoiConsultBudget#reserve}, called only from {@link #startConsult} once
     * this (and a cache miss) has already been decided.
     */
    private static boolean advisorAvailable(AIPlayerEntity bot, MiningAssistConfig cfg, String ledgerKey,
                                            boolean cavernOnly, int serverTick) {
        if (!cfg.advisor().enabled()) {
            return false;
        }
        if (!PoiAdvisor.hasTestTransport()) {
            String apiKey = MinecraftAiConfig.get().llm().apiKey();
            if (apiKey == null || apiKey.isBlank()) {
                return false;
            }
        }
        if (MiningAssistRuntime.tpsDegraded(bot)) {
            return false;
        }
        return PoiConsultBudget.canConsult(ledgerKey, bot.getUUID(), cavernOnly, serverTick, cfg.advisor());
    }

    /**
     * Design 6.1: whether the active task's class allows a hold at all. Every sensed task class allows it
     * except a {@code DigDownTask} currently descending (6.1's table: "Hold: no"; its own {@code onPause}
     * converts DESCEND to a RETURN climb, which a hold must never trigger for a merely POSSIBLE candidate).
     */
    private static boolean holdAllowedFor(AIPlayerEntity bot) {
        return TaskManager.INSTANCE.getActive(bot)
                .map(task -> !(task instanceof DigDownTask digDownTask && digDownTask.isDescending()))
                .orElse(true);
    }

    /**
     * Design 6.5 {@code consult()}: reserves the budget, opens a CONSULTING dedupe entry, decides whether to
     * hold (design 6.1 task-class policy, {@link DetourSafetyGate#safeToHold}, not already user-paused, and
     * the brain not already busy -- design has no separate {@code poi.hold} toggle in section 7's config
     * table, so "cfg.hold" there is read as "holding is available whenever a real consult is," gated only by
     * these per-attempt conditions), and starts the async {@link PoiAdvisor} call either way.
     */
    private void startConsult(AIPlayerEntity bot, ServerLevel world, MiningAssistState state, PoiDetector.Result result,
                              Candidate candidate, MiningAssistConfig cfg, String ledgerKey, boolean cavernOnly,
                              String cacheKey, int serverTick) {
        UUID id = bot.getUUID();
        PoiConsultBudget.reserve(ledgerKey, id, cavernOnly, serverTick);
        PoiRegistry.record(id, candidate.dim(), candidate.anchor(), candidate.label(), PoiRegistry.State.CONSULTING,
                candidate.structureScore(), serverTick);

        boolean wantsHold = holdAllowedFor(bot) && !TaskManager.INSTANCE.isUserPaused(bot)
                && !BrainCoordinator.INSTANCE.status(bot).busy() && DetourSafetyGate.safeToHold(bot);
        boolean ownsPause = false;
        int pauseEpoch = 0;
        int deadlineTick;
        BotMemory mem = BotMemoryStore.INSTANCE.of(id);
        if (wantsHold) {
            // Marker BEFORE pausing (design 6.5), so a crash between the two still fails closed on restart.
            mem.markPlace(HOLD_PLACE_PREFIX + candidate.label(), world, candidate.anchor());
            if (TaskManager.INSTANCE.pauseUserIntent(bot, "poi_confirm")) {
                BotPersistence.INSTANCE.markDirty(world.getServer());
                pauseEpoch = TaskManager.INSTANCE.userPauseEpoch(bot);
                ownsPause = true;
                deadlineTick = serverTick + cfg.poi().holdDeadlineTicks();
                if (cfg.poi().announceHold()) {
                    BrainCoordinator.INSTANCE.sendPanelChat(bot, "system", "Checking a possible point of interest...");
                }
            } else {
                // Lost a race (already paused by something else this same tick) or no active origin: the
                // marker we just wrote would mislead a restart into "Still paused," so forget it and fall
                // back to the no-hold deadline instead.
                mem.forgetPlace(HOLD_PLACE_PREFIX + candidate.label());
                deadlineTick = serverTick + NO_HOLD_DEADLINE_TICKS;
            }
        } else {
            deadlineTick = serverTick + NO_HOLD_DEADLINE_TICKS;
        }

        long caseId = nextCaseId++;
        PendingConsult pending = new PendingConsult(caseId, id, candidate, ledgerKey, cavernOnly, cacheKey,
                wantsHold, ownsPause, pauseEpoch, deadlineTick, false);
        pendingByCaseId.put(caseId, pending);
        caseIdByBot.put(id, caseId);
        BotLog.task(bot, "poi_consult_started", "label", candidate.label(), "hold", ownsPause,
                "cavern_only", cavernOnly, "deadline_tick", deadlineTick);

        String systemPromptText = PoiPrompt.systemPrompt();
        String payload = PoiPrompt.userPayload(
                PoiPayloadBuilder.buildPayloadInput(bot, state, result, candidate.dim(), candidate.anchor(), cavernOnly));
        PoiAdvisor.INSTANCE.consult(bot, systemPromptText, payload,
                verdict -> onAdvisorVerdict(bot, caseId, verdict),
                reason -> onAdvisorFailure(bot, caseId, reason));
    }

    /** {@link PoiAdvisor}'s success callback (already on the server thread). {@code pending.deadlineResolved()}
     * true means this coordinator's own deadline already applied this case's fallback via
     * {@link #applyFallbackForPending}; the verdict is still cached (design 6.6: "a late continue only fills
     * the cache") and, for a late STOP, still worth a notify-only line ("a late stop after the fallback...
     * becomes a notify-only line"), via {@link #applyDecision}'s {@code forceLate}. A null {@code pending} is
     * defensive only (an unknown caseId should not happen; every started consult resolves exactly once). */
    private void onAdvisorVerdict(AIPlayerEntity bot, long caseId, PoiPrompt.Verdict verdict) {
        PoiConsultBudget.release();
        PoiConsultBudget.recordSuccess();
        int serverTick = MiningAssistRuntime.serverTick(bot);
        PendingConsult pending = pendingByCaseId.remove(caseId);
        if (pending == null) {
            return;
        }
        caseIdByBot.remove(pending.botId(), caseId);
        boolean stop = verdict.decision() == PoiPrompt.Decision.STOP;
        PoiCache.put(pending.cacheKey(), stop, verdict.label(), serverTick);
        BotLog.task(bot, "poi_advisor_verdict", "label", pending.candidate().label(), "advisor_label", verdict.label(),
                "stop", stop, "confidence", verdict.confidence(), "already_resolved", pending.deadlineResolved());
        if (bot.isRemoved()) {
            return;
        }
        applyDecision(bot, bot.level(), pending.candidate(), pending.ledgerKey(), verdict.decision(),
                pending.ownsPause(), pending.pauseEpoch(), serverTick, pending.deadlineResolved());
    }

    /** {@link PoiAdvisor}'s failure callback (network, parse, or the 10s wall-clock guard): counts against
     * the breaker and applies the design 6.7 fallback for this candidate, unless the coordinator's own
     * deadline already beat it to resolving the case -- a failure carries no verdict to cache or notify, so
     * a deadline-resolved one is a pure no-op past the log line (applying the fallback a second time would
     * double-resolve the same candidate). */
    private void onAdvisorFailure(AIPlayerEntity bot, long caseId, String reason) {
        PoiConsultBudget.release();
        int serverTick = MiningAssistRuntime.serverTick(bot);
        PoiConsultBudget.recordFailure(serverTick, MiningAssistRuntime.config().advisor());
        PendingConsult pending = pendingByCaseId.remove(caseId);
        if (pending == null) {
            return;
        }
        caseIdByBot.remove(pending.botId(), caseId);
        BotLog.task(bot, "poi_advisor_failed", "reason", reason, "label", pending.candidate().label(),
                "already_resolved", pending.deadlineResolved());
        if (pending.deadlineResolved() || bot.isRemoved()) {
            return;
        }
        applyFallbackForPending(bot, bot.level(), pending, serverTick);
    }

    /** Called from {@link #tick} every coordinator tick while this bot has a tracked case: resolves it via
     * {@link #applyFallbackForPending} once {@code serverTick} reaches its deadline, otherwise leaves it be. */
    private void checkConsultDeadline(AIPlayerEntity bot, int serverTick) {
        UUID id = bot.getUUID();
        Long caseId = caseIdByBot.get(id);
        if (caseId == null) {
            return;
        }
        PendingConsult pending = pendingByCaseId.get(caseId);
        if (pending == null) {
            caseIdByBot.remove(id);
            return;
        }
        if (serverTick < pending.deadlineTick()) {
            return;
        }
        // The bot is no longer "in an open consult" for tick()'s own routing, but the PendingConsult itself
        // stays (marked deadlineResolved), not removed: the real PoiAdvisor callback is still outstanding and
        // will arrive later on this same caseId, and design 6.6's late-reply rule needs its cacheKey/
        // ownsPause/pauseEpoch to honour it (onAdvisorVerdict/onAdvisorFailure do the eventual cleanup).
        caseIdByBot.remove(id, caseId);
        pendingByCaseId.put(caseId, new PendingConsult(pending.caseId(), pending.botId(), pending.candidate(),
                pending.ledgerKey(), pending.cavernOnly(), pending.cacheKey(), pending.hold(), pending.ownsPause(),
                pending.pauseEpoch(), pending.deadlineTick(), true));
        BotLog.task(bot, "poi_consult_deadline", "label", pending.candidate().label());
        applyFallbackForPending(bot, bot.level(), pending, serverTick);
    }

    /** Design 6.5 "deadline reached (any state) -> applyFallback()", made hold-aware: a STOP fallback
     * promotes an existing hold-pause in place (never a second {@code IntentController.pause}); a CONTINUE
     * fallback resumes it first. A stale hold (the player already acted) never resumes and never claims a
     * stop, matching {@link #applyDecision}'s own rule for a genuine late verdict. Always {@code
     * forceLate=false}: this is the case's primary (first) resolution, never itself a late reply. */
    private void applyFallbackForPending(AIPlayerEntity bot, ServerLevel world, PendingConsult pending, int serverTick) {
        Candidate c = pending.candidate();
        MiningAssistConfig cfg = MiningAssistRuntime.config();
        PoiDecisionPolicy.Decision decision = PoiDecisionPolicy.decide(c.band(), c.structureScore(),
                c.habitationLike(), cfg.poi().unavailablePolicy(), cfg.poi().cavernKeylessPolicy());
        applyDecision(bot, world, c, pending.ledgerKey(),
                decision == PoiDecisionPolicy.Decision.STOP ? PoiPrompt.Decision.STOP : PoiPrompt.Decision.CONTINUE,
                pending.ownsPause(), pending.pauseEpoch(), serverTick, false);
    }

    /**
     * Applies a resolved STOP/CONTINUE decision (a real or cached R4 verdict, or the design 6.7 fallback
     * matrix's own equivalent of one), shared by every path that can produce one. Design 6.6's "late replies
     * never pause" rule fires -- notify-only "Late check" line for a stop, silent registry/cache bookkeeping
     * only for a continue -- whenever either of two independent things happened first: {@code forceLate} is
     * true (this coordinator's own {@link #checkConsultDeadline} already applied this case's fallback before
     * this verdict arrived), or {@code ownsPause} is true and the pause epoch has moved on or the bot is no
     * longer user-paused at all (the player acted during the hold). Either way this decision must never
     * resume the bot and never claim "Stopped" a second/late time.
     *
     * <p>The notice, ring-slot marker and registry key all use {@code candidate.label()} (the deterministic
     * {@code PoiLabeler} label, the same one the CONSULTING placeholder was recorded under and the one every
     * other stop path in this file already uses), never the advisor's own {@code label} guess: {@link
     * PoiRegistry#record} replaces an existing entry only when {@code (dimensionKey, anchor, label)} all
     * still match, so using a different label here would leave the CONSULTING placeholder dangling forever
     * (it never expires on its own) instead of being replaced by the resolved STOPPED/DECLINED entry. The
     * advisor's own label/confidence are still logged for diagnostics.</p>
     */
    private void applyDecision(AIPlayerEntity bot, ServerLevel world, Candidate candidate, String ledgerKey,
                               PoiPrompt.Decision decision, boolean ownsPause, int pauseEpoch, int serverTick,
                               boolean forceLate) {
        UUID id = bot.getUUID();
        String label = candidate.label();
        boolean stop = decision == PoiPrompt.Decision.STOP;
        boolean stale = forceLate || (ownsPause && (TaskManager.INSTANCE.userPauseEpoch(bot) != pauseEpoch
                || !TaskManager.INSTANCE.isUserPaused(bot)));
        if (stale) {
            String trigger = forceLate ? "coordinator_deadline" : "player_action";
            if (stop) {
                PoiRegistry.record(id, candidate.dim(), candidate.anchor(), label, PoiRegistry.State.STOPPED,
                        candidate.structureScore(), serverTick);
                sendNotice(bot, world, PoiNotice.renderLateCheck(label, candidate.anchor()));
                BotLog.task(bot, "poi_late_check", "label", label, "decision", "stop", "trigger", trigger);
            } else {
                PoiRegistry.record(id, candidate.dim(), candidate.anchor(), label, PoiRegistry.State.DECLINED,
                        candidate.structureScore(), serverTick);
                BotLog.task(bot, "poi_late_check", "label", label, "decision", "continue", "trigger", trigger);
            }
            return;
        }
        if (stop) {
            MissionAssistLedger.Entry ledger = MissionAssistLedger.get(ledgerKey, serverTick);
            stopNow(bot, world, candidate.dim(), candidate.anchor(), label, Source.FALLBACK,
                    candidate.structureScore(), ledger, serverTick, null, ownsPause);
        } else {
            if (ownsPause) {
                resumeHold(bot, candidate.label(), "poi_cleared");
            }
            PoiRegistry.record(id, candidate.dim(), candidate.anchor(), candidate.label(), PoiRegistry.State.DECLINED,
                    candidate.structureScore(), serverTick);
            BotLog.task(bot, "poi_continue", "label", candidate.label());
        }
    }

    /** Design 6.5 CONTINUE-with-hold: resumes the paused mission and forgets the hold marker, the exact
     * reverse of {@link #startConsult}'s hold-start bookkeeping. */
    private static void resumeHold(AIPlayerEntity bot, String label, String why) {
        TaskManager.INSTANCE.resumeUserIntent(bot, why);
        BotMemory mem = BotMemoryStore.INSTANCE.of(bot.getUUID());
        mem.forgetPlace(HOLD_PLACE_PREFIX + label);
    }

    /** Design 6.8 "Multi-bot routing": true while a POI stop is open for this bot and the player has not yet resumed. */
    public boolean awaitingContinue(AIPlayerEntity bot) {
        return PoiRegistry.openCase(bot.getUUID()) != null && TaskManager.INSTANCE.isUserPaused(bot);
    }

    /**
     * The warden rule (I13), evaluated on its own, before and independent of the dedupe registry: a DECLINED
     * or STOPPED {@code PoiRegistry} entry must never suppress a mandatory candidate, so mandatory never
     * consults the registry at all — only {@link MandatoryLatch}.
     */
    private void mandatoryFlow(AIPlayerEntity bot, ServerLevel world, String dim, BlockPos anchor,
                               PoiScorer.PoiScore score, int serverTick) {
        UUID id = bot.getUUID();
        boolean wardenVisible = score.mandatoryTrigger().contains("warden_visible");
        if (MandatoryLatch.suppresses(id, dim, anchor, wardenVisible, serverTick)) {
            return;
        }
        MandatoryLatch.record(id, dim, anchor, serverTick);
        // Literal "warden_risk" (== WARDEN_RISK_LABEL), not the constant: PoiCoordinatorSourceContractTest
        // pins this exact call text.
        stopNow(bot, world, dim, anchor, "warden_risk", Source.MANDATORY, score.s(), null, serverTick, null, false);
    }

    /**
     * Pauses the bot's mission, records the stop in the dedupe registry, opens the case (BotMemory ring slot
     * plus the resumable {@code poi_hold_<label>} marker; no {@code BotMemory} fact -- design 2.4/6.4), and
     * sends the notice. {@code structureScore} is {@code S} at the moment of the stop (mandatory passes
     * {@code score.s()} of its own evaluation, since there is no separate "structure score" for a
     * warden-risk trigger).
     *
     * @param alreadyPaused P3: true when a R4 consult already paused this bot via {@code TaskManager.
     *                      pauseUserIntent} for a hold (design 6.5's {@code ownsPause}) and its own verdict
     *                      resolved to STOP -- the hold's pause is promoted in place, {@code
     *                      IntentController.pause} is never called a second time. Always {@code false} for a
     *                      deterministic (mandatory/certain/keyless-fallback) stop, which never held anything.
     */
    private void stopNow(AIPlayerEntity bot, ServerLevel world, String dim, BlockPos anchor, String label,
                         Source source, double structureScore, MissionAssistLedger.Entry ledger, int serverTick,
                         String autoDetectedNote, boolean alreadyPaused) {
        UUID id = bot.getUUID();
        // Design 6.1's DigDown-descend variant needs the task's phase at the MOMENT of the stop, captured
        // from the still-active task BEFORE it is paused: IntentController.pause below routes through
        // TaskManager.pauseFor -> DigDownTask.onPause, which -- as its own documented side effect (design
        // 6.1's table: "onPause converts to a RETURN climb") -- flips phase DESCEND to RETURN before the
        // call returns. Reading isDescending() any later, even correctly via peekPaused once the task is on
        // the pause stack, would always observe the post-pause RETURN phase and could never select the
        // climb-out template (real-server regression guard: OreDigPoiGameTests.digDownStopUsesDescentClimbNotice
        // / digDownMandatoryUsesDescentVariantToo).
        boolean descending = TaskManager.INSTANCE.getActive(bot)
                .map(task -> task instanceof DigDownTask digDownTask && digDownTask.isDescending())
                .orElse(false);
        if (!alreadyPaused) {
            IntentController.INSTANCE.pause(bot, IntentController.ControlOrigin.SYSTEM, "poi_stop");
        }
        PoiRegistry.record(id, dim, anchor, label, PoiRegistry.State.STOPPED, structureScore, serverTick);
        if (source != Source.MANDATORY && ledger != null) {
            ledger.notePoiHoldOrStop();
        }
        int slot = PoiRegistry.nextRingSlot(id);
        BotMemory mem = BotMemoryStore.INSTANCE.of(id);
        mem.markPlace("poi_" + slot + "_" + label, world, anchor);
        mem.markPlace(HOLD_PLACE_PREFIX + label, world, anchor);
        PoiRegistry.openCase(id, new PoiRegistry.OpenCase(label, source.name(), dim, anchor));
        String text = PoiNotice.renderStop(descending, source == Source.MANDATORY, label, anchor,
                bot.blockPosition(), autoDetectedNote, null);
        sendNotice(bot, world, text);
        BotLog.task(bot, "poi_stop", "label", label, "source", source, "pos", PoiNotice.anchorStr(anchor),
                "ledger_key", ledgerKeyFor(bot));
    }

    /**
     * Design 6.4's fallback once the per-mission hold/stop cap is reached: a structure-certain candidate still
     * gets one notify-and-continue line each, up to {@value #MAX_CERTAIN_NOTIFY_AFTER_CAP} more per mission;
     * anything else (POSSIBLE, CAVERN_ONLY, NONE) is silently dropped once the cap is reached.
     */
    private void notifyOnlyIfCertain(AIPlayerEntity bot, ServerLevel world, String dim, BlockPos anchor,
                                     String label, PoiScorer.Band band, double structureScore,
                                     MissionAssistLedger.Entry ledger, int serverTick) {
        if (band != PoiScorer.Band.STRUCTURE_CERTAIN) {
            return;
        }
        if (ledger.poiCertainNotifyAfterCapReached()) {
            return;
        }
        ledger.notePoiCertainNotifyAfterCap();
        PoiRegistry.record(bot.getUUID(), dim, anchor, label, PoiRegistry.State.DECLINED, structureScore, serverTick);
        sendNotice(bot, world, PoiNotice.renderFyi(label, anchor, bot.blockPosition()));
        BotLog.task(bot, "poi_certain_notify_after_cap", "label", label);
    }

    /** Panel chat plus, per {@code poi.noticeRecipients}, either every online player or only an authorized one. */
    private void sendNotice(AIPlayerEntity bot, ServerLevel world, String text) {
        BrainCoordinator.INSTANCE.sendPanelChat(bot, "system", text);
        MiningAssistConfig.NoticeRecipients recipients = MiningAssistRuntime.config().poi().noticeRecipients();
        for (ServerPlayer player : world.getServer().getPlayerList().getPlayers()) {
            if (recipients == MiningAssistConfig.NoticeRecipients.BROADCAST
                    || BotAuthorizationGate.INSTANCE.canCommand(player, bot)) {
                player.displayClientMessage(Component.literal("[Minecraft-AI] " + text), false);
            }
        }
    }

    /**
     * The live {@code MissionAssistLedger} key for {@code bot}, recomputed from
     * {@code TaskManager.activeOrigin(bot)} every call so the ledger always tracks the bot's current mission.
     * On the rare tick where {@code activeOrigin} is empty (between a replan and the next assignment), falls
     * back to the last key seen for this specific bot, never a single shared field (this coordinator is one
     * instance shared by every bot on the server, unlike a per-task detour host).
     */
    private String ledgerKeyFor(AIPlayerEntity bot) {
        UUID id = bot.getUUID();
        Optional<TaskOrigin> origin = TaskManager.INSTANCE.activeOrigin(bot);
        if (origin.isPresent()) {
            String key = MissionAssistLedger.keyFor(id, origin.get().missionId(), origin.get().jobId());
            lastLedgerKeyByBot.put(id, key);
            return key;
        }
        return lastLedgerKeyByBot.getOrDefault(id, MissionAssistLedger.keyFor(id, null, null));
    }

    /**
     * P3 restart safety: drops this bot's in-flight-consult bookkeeping ({@link #caseIdByBot},
     * {@link #pendingByCaseId}) and {@link #lastLedgerKeyByBot} cache entry. Called on a genuine bot
     * unload/restart ({@code MiningAssistRuntime.clearBotUnload}, wired from {@code
     * RuntimeLifecycleCoordinator.clearTransient}), never on the soft idle-release path -- same reasoning as
     * {@code MiningAssistRuntime.clearBot} vs {@code clearBotUnload}'s own split.
     *
     * <p>Without this, a bot that restarts mid-consult keeps a stale {@code caseIdByBot} entry: {@link #tick}
     * would then route it into {@link #checkConsultDeadline} instead of design 6.5's "no in-memory case ->
     * rehydrate from BotMemory, fail closed" restart path, exactly the case that path exists for. The
     * abandoned {@code pendingByCaseId} entry (if any) is dropped with it: {@link #onAdvisorVerdict}/
     * {@link #onAdvisorFailure} already treat an unknown caseId as a safe no-op, and applying a pre-restart
     * verdict to a freshly rehydrated post-restart case would be worse than dropping it.</p>
     */
    public void clearBot(UUID botId) {
        Long caseId = caseIdByBot.remove(botId);
        if (caseId != null) {
            pendingByCaseId.remove(caseId);
        }
        lastLedgerKeyByBot.remove(botId);
    }

    /** World unload ({@code MiningAssistRuntime.clearWorldRuntime}, wired from {@code
     * RuntimeLifecycleCoordinator}'s own {@code clearWorldRuntime}): drops every bot's P3 bookkeeping. */
    public void clearAll() {
        pendingByCaseId.clear();
        caseIdByBot.clear();
        lastLedgerKeyByBot.clear();
    }
}

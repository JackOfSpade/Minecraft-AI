package io.github.zoyluo.aibot.coordination;

import io.github.zoyluo.aibot.auth.BotAuthorizationGate;
import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.memory.BotMemory;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.mining.assist.MandatoryLatch;
import io.github.zoyluo.aibot.mining.assist.MiningAssistConfig;
import io.github.zoyluo.aibot.mining.assist.MiningAssistRuntime;
import io.github.zoyluo.aibot.mining.assist.MiningAssistState;
import io.github.zoyluo.aibot.mining.assist.MissionAssistLedger;
import io.github.zoyluo.aibot.mining.assist.PoiDecisionPolicy;
import io.github.zoyluo.aibot.mining.assist.PoiDetector;
import io.github.zoyluo.aibot.mining.assist.PoiNotice;
import io.github.zoyluo.aibot.mining.assist.PoiRegistry;
import io.github.zoyluo.aibot.mining.assist.PoiScorer;
import io.github.zoyluo.aibot.runtime.IntentController;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import io.github.zoyluo.aibot.task.DigDownTask;
import io.github.zoyluo.aibot.task.TaskManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
 *       per-mission hold/stop cap, then a deterministic structure-certain stop, then the design 6.7 fallback
 *       matrix for POSSIBLE/CAVERN_ONLY.</li>
 *   <li><b>P2 scope.</b> {@code possibleFlow} here always resolves through {@link PoiDecisionPolicy}'s
 *       fallback matrix: the advisor, the hold-and-wait, and consuming {@code TaskManager}'s pause-epoch
 *       counter are all P3. This class never calls {@code consult(} and never references an advisor symbol.</li>
 * </ul>
 *
 * <p>Server thread only, exactly like the state it reads and writes ({@code PoiRegistry}, {@code
 * MandatoryLatch}, {@code MissionAssistLedger}, {@code TaskManager}, {@code BotMemory}).</p>
 */
public final class PoiCoordinator {
    public static final PoiCoordinator INSTANCE = new PoiCoordinator();

    /** Where a stop's decision came from; persisted (as {@code .name()}) in the "poi_hold_source" BotMemory fact. */
    enum Source { MANDATORY, CERTAIN, FALLBACK }

    private static final String HOLD_PLACE_PREFIX = "poi_hold_";
    private static final String HOLD_SOURCE_FACT = "poi_hold_source";
    /** Design 6.4: "(at most 3 more)" once the per-mission hold/stop cap is reached. */
    private static final int MAX_CERTAIN_NOTIFY_AFTER_CAP = 3;

    // This coordinator is ONE shared instance across every bot on the server, unlike OreDigTask's
    // DetourHostImpl (a private inner instance per bot/task) whose single `lastLedgerKey` field
    // (OreDigTask.java) caches into. A literal copy of that single-field cache here would let one bot's
    // ledger key leak into another bot's onCandidate call on any tick where TaskManager.activeOrigin(bot)
    // is momentarily empty for a *different* bot. Cache per bot instead.
    private final Map<UUID, String> lastLedgerKeyByBot = new HashMap<>();

    private PoiCoordinator() {
    }

    /**
     * Called unconditionally every coordinator tick, for every bot, regardless of sense/mode state: resume
     * detection for an open case, and — when no in-memory case exists but the bot is user-paused —
     * rehydration from {@link BotMemory}. Must run even while the bot has no active sensed task, so it is
     * called from {@code MiningAssistCoordinator.run()} alongside {@code maintainDetour}, not from
     * {@code sense()}.
     */
    public void tick(AIPlayerEntity bot, int serverTick) {
        UUID id = bot.getUuid();
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
            String source = mem.recall(HOLD_SOURCE_FACT).orElse(Source.FALLBACK.name());
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
                String text = noticeText(descending, Source.valueOf(open.source()), open.label(), open.anchor(),
                        bot.getBlockPos(), null, "Still paused: ");
                sendNotice(bot, bot.getEntityWorld(), text);
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
        mem.forget(HOLD_SOURCE_FACT);
        PoiRegistry.closeCase(id);
        BotLog.task(bot, "poi_case_closed", "label", open.label(), "source", open.source());
    }

    /**
     * Called only when {@code PoiDetector} produced an actionable result (MANDATORY, STRUCTURE_CERTAIN, or a
     * confirmed POSSIBLE/CAVERN_ONLY) — design 6.5's {@code onCandidate}.
     */
    public void onCandidate(AIPlayerEntity bot, MiningAssistState state, ServerWorld world,
                            PoiDetector.Result result, int serverTick) {
        MiningAssistConfig cfg = MiningAssistRuntime.config();
        UUID id = bot.getUuid();
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
            stopNow(bot, world, dim, anchor, label, Source.CERTAIN, score.s(), ledger, serverTick, null);
            return;
        }

        // POSSIBLE or CAVERN_ONLY
        PoiDecisionPolicy.Decision decision = PoiDecisionPolicy.decide(result.band(), score.s(),
                score.habitationLike(), cfg.poi().unavailablePolicy(), cfg.poi().cavernKeylessPolicy());
        if (decision == PoiDecisionPolicy.Decision.STOP) {
            stopNow(bot, world, dim, anchor, label, Source.FALLBACK, score.s(), ledger, serverTick, "(auto-detected)");
        } else if (!ledger.poiFyiCapReached()) {
            PoiRegistry.record(id, dim, anchor, label, PoiRegistry.State.DECLINED, score.s(), serverTick);
            ledger.notePoiFyi();
            sendNotice(bot, world, PoiNotice.renderFyi(label, anchor, bot.getBlockPos()));
            BotLog.task(bot, "poi_fyi", "label", label, "pos", anchorStr(anchor));
        }
    }

    /** Design 6.8 "Multi-bot routing": true while a POI stop is open for this bot and the player has not yet resumed. */
    public boolean awaitingContinue(AIPlayerEntity bot) {
        return PoiRegistry.openCase(bot.getUuid()) != null && TaskManager.INSTANCE.isUserPaused(bot);
    }

    /**
     * The warden rule (I13), evaluated on its own, before and independent of the dedupe registry: a DECLINED
     * or STOPPED {@code PoiRegistry} entry must never suppress a mandatory candidate, so mandatory never
     * consults the registry at all — only {@link MandatoryLatch}.
     */
    private void mandatoryFlow(AIPlayerEntity bot, ServerWorld world, String dim, BlockPos anchor,
                               PoiScorer.PoiScore score, int serverTick) {
        UUID id = bot.getUuid();
        boolean wardenVisible = score.mandatoryTrigger().contains("warden_visible");
        if (MandatoryLatch.suppresses(id, dim, anchor, wardenVisible, serverTick)) {
            return;
        }
        MandatoryLatch.record(id, dim, anchor, serverTick);
        stopNow(bot, world, dim, anchor, "warden_risk", Source.MANDATORY, score.s(), null, serverTick, null);
    }

    /**
     * Pauses the bot's mission, records the stop in the dedupe registry, opens the case (BotMemory ring slot
     * plus the resumable {@code poi_hold_<label>} marker and {@code poi_hold_source} fact), and sends the
     * notice. {@code structureScore} is {@code S} at the moment of the stop (mandatory passes {@code score.s()}
     * of its own evaluation, since there is no separate "structure score" for a warden-risk trigger).
     */
    private void stopNow(AIPlayerEntity bot, ServerWorld world, String dim, BlockPos anchor, String label,
                         Source source, double structureScore, MissionAssistLedger.Entry ledger, int serverTick,
                         String autoDetectedNote) {
        UUID id = bot.getUuid();
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
        IntentController.INSTANCE.pause(bot, IntentController.ControlOrigin.SYSTEM, "poi_stop");
        PoiRegistry.record(id, dim, anchor, label, PoiRegistry.State.STOPPED, structureScore, serverTick);
        if (source != Source.MANDATORY && ledger != null) {
            ledger.notePoiHoldOrStop();
        }
        int slot = PoiRegistry.nextRingSlot(id);
        BotMemory mem = BotMemoryStore.INSTANCE.of(id);
        mem.markPlace("poi_" + slot + "_" + label, world, anchor);
        mem.markPlace(HOLD_PLACE_PREFIX + label, world, anchor);
        mem.remember(HOLD_SOURCE_FACT, source.name());
        PoiRegistry.openCase(id, new PoiRegistry.OpenCase(label, source.name(), dim, anchor));
        String text = noticeText(descending, source, label, anchor, bot.getBlockPos(), autoDetectedNote, null);
        sendNotice(bot, world, text);
        BotLog.task(bot, "poi_stop", "label", label, "source", source, "pos", anchorStr(anchor),
                "ledger_key", ledgerKeyFor(bot));
    }

    /**
     * Design 6.4's fallback once the per-mission hold/stop cap is reached: a structure-certain candidate still
     * gets one notify-and-continue line each, up to {@value #MAX_CERTAIN_NOTIFY_AFTER_CAP} more per mission;
     * anything else (POSSIBLE, CAVERN_ONLY, NONE) is silently dropped once the cap is reached.
     */
    private void notifyOnlyIfCertain(AIPlayerEntity bot, ServerWorld world, String dim, BlockPos anchor,
                                     String label, PoiScorer.Band band, double structureScore,
                                     MissionAssistLedger.Entry ledger, int serverTick) {
        if (band != PoiScorer.Band.STRUCTURE_CERTAIN) {
            return;
        }
        if (ledger.poiCertainNotifyAfterCapReached()) {
            return;
        }
        ledger.notePoiCertainNotifyAfterCap();
        PoiRegistry.record(bot.getUuid(), dim, anchor, label, PoiRegistry.State.DECLINED, structureScore, serverTick);
        sendNotice(bot, world, PoiNotice.renderFyi(label, anchor, bot.getBlockPos()));
        BotLog.task(bot, "poi_certain_notify_after_cap", "label", label);
    }

    /**
     * Design 6.1's per-task-class notice variant, pure text selection: when {@code descending} (the task's
     * DigDown DESCEND phase at whatever moment the caller captured it — see {@code stopNow}'s and
     * {@code tick}'s own comments on why that moment matters), every stop (mandatory, certain, or fallback)
     * uses the climb-out wording instead of the standard/mandatory template, regardless of source.
     */
    private static String noticeText(boolean descending, Source source, String label, BlockPos anchor,
                                     BlockPos botPos, String autoDetectedNote, String restartPrefix) {
        String base = descending
                ? PoiNotice.renderDigDownDescend(label, anchor, botPos)
                : source == Source.MANDATORY
                        ? PoiNotice.renderMandatory(anchor, botPos)
                        : PoiNotice.renderStandard(label, anchor, botPos, autoDetectedNote);
        return restartPrefix == null ? base : restartPrefix + base;
    }

    /** Panel chat plus, per {@code poi.noticeRecipients}, either every online player or only an authorized one. */
    private void sendNotice(AIPlayerEntity bot, ServerWorld world, String text) {
        BrainCoordinator.INSTANCE.sendPanelChat(bot, "system", text);
        MiningAssistConfig.NoticeRecipients recipients = MiningAssistRuntime.config().poi().noticeRecipients();
        for (ServerPlayerEntity player : world.getServer().getPlayerManager().getPlayerList()) {
            if (recipients == MiningAssistConfig.NoticeRecipients.BROADCAST
                    || BotAuthorizationGate.INSTANCE.canCommand(player, bot)) {
                player.sendMessage(Text.literal("[AIBot] " + text), false);
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
        UUID id = bot.getUuid();
        Optional<TaskOrigin> origin = TaskManager.INSTANCE.activeOrigin(bot);
        if (origin.isPresent()) {
            String key = MissionAssistLedger.keyFor(id, origin.get().missionId(), origin.get().jobId());
            lastLedgerKeyByBot.put(id, key);
            return key;
        }
        return lastLedgerKeyByBot.getOrDefault(id, MissionAssistLedger.keyFor(id, null, null));
    }

    private static String anchorStr(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}

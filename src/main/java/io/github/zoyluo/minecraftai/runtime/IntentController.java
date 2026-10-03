package io.github.zoyluo.minecraftai.runtime;

import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.coordination.IdleCoordinator;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.goal.GoalExecutor;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.memory.BotMemoryStore;
import io.github.zoyluo.minecraftai.task.StuckWatcher;
import io.github.zoyluo.minecraftai.task.TaskManager;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.Locale;

/** The only user-facing owner of Mission/Task/Action cancellation ordering. */
public final class IntentController {
    public static final IntentController INSTANCE = new IntentController();

    private IntentController() {
    }

    public IntentControlTransaction.Outcome cancelCurrent(AIPlayerEntity bot,
                                                          ControlOrigin origin,
                                                          String reason) {
        requireServerThread(bot);
        return cancel(bot, origin, reason, IntentControlTransaction.Scope.CURRENT, false);
    }

    public IntentControlTransaction.Outcome cancelAll(AIPlayerEntity bot,
                                                      ControlOrigin origin,
                                                      String reason) {
        requireServerThread(bot);
        return cancel(bot, origin, reason, IntentControlTransaction.Scope.ALL, false);
    }

    /**
     * {@link #cancelAll} for a new player chat request: everything is replaced except a running
     * SAFETY-origin task (a fight against a real threat), which keeps going until the threat is
     * handled instead of being cancelled mid-fight. An explicit stop/hold (control phrases, tools,
     * commands) still goes through {@link #cancelAll}/{@link #pause} and preempts it.
     */
    public IntentControlTransaction.Outcome cancelAllKeepingActiveSafety(AIPlayerEntity bot,
                                                                         ControlOrigin origin,
                                                                         String reason) {
        requireServerThread(bot);
        return cancel(bot, origin, reason, IntentControlTransaction.Scope.ALL, true);
    }

    public ReplaceResult replace(AIPlayerEntity bot,
                                 ControlOrigin origin,
                                 String reason,
                                 Supplier<Boolean> startReplacement) {
        requireServerThread(bot);
        Objects.requireNonNull(startReplacement, "startReplacement");
        IntentControlTransaction.Outcome cancellation = cancel(
                bot, origin, reason, IntentControlTransaction.Scope.CURRENT, false);
        boolean replacementStarted;
        try {
            replacementStarted = Boolean.TRUE.equals(startReplacement.get());
        } catch (RuntimeException replacementFailure) {
            // A replacement may fail after partially installing Goal/Task/Action state. Remove that
            // partial work, preserve the explicit queue, and rethrow so the caller reports failure.
            try {
                cancel(bot, ControlOrigin.SYSTEM, reason + ":replacement_start_failed",
                        IntentControlTransaction.Scope.CURRENT, false);
            } catch (RuntimeException cleanupFailure) {
                replacementFailure.addSuppressed(cleanupFailure);
            }
            throw replacementFailure;
        }
        // A failed replacement leaves the preserved queue idle. GoalExecutor promotes it on the
        // next tick so repeated controls in this server turn cannot consume multiple queue items.
        return new ReplaceResult(cancellation, replacementStarted);
    }

    public boolean pause(AIPlayerEntity bot, ControlOrigin origin, String reason) {
        requireServerThread(bot);
        String normalized = normalizeReason(origin, reason);
        if (TaskManager.INSTANCE.isUserPaused(bot)) {
            // An adaptive checkpoint installs an automatic safe pause. A later human "pause"
            // must still win over its in-flight model decision and remain durable as a manual hold.
            if (GoalExecutor.INSTANCE.holdStrategyCheckpoint(bot)) {
                BrainCoordinator.INSTANCE.invalidateDecision(bot,
                        "intent_pause_strategy_checkpoint:" + normalized);
                BrainCoordinator.INSTANCE.clearIntentWakeSources(bot);
                BotLog.comm(bot, "intent_paused_strategy_checkpoint", "origin", origin,
                        "reason", normalized);
                if (origin.notifiesUser()) {
                    BrainCoordinator.INSTANCE.sendPanelChat(bot, "system",
                            "The current mission is paused at its safe strategy checkpoint.");
                }
                return true;
            }
            return false;
        }
        boolean changed = BrainCoordinator.INSTANCE.invalidateDecision(bot, "intent_pause:" + normalized);
        changed |= BrainCoordinator.INSTANCE.clearIntentWakeSources(bot);
        changed |= TaskManager.INSTANCE.pauseUserIntent(bot, normalized);
        boolean hadActions = bot.getActionPack().hasActiveActions();
        bot.getActionPack().stopAll();
        changed |= hadActions;
        BotLog.comm(bot, "intent_paused", "origin", origin, "reason", normalized,
                "queue_preserved", GoalExecutor.INSTANCE.queuedGoalCount(bot),
                "stack_depth", TaskManager.INSTANCE.pausedDepth(bot));
        if (origin.notifiesUser()) {
            BrainCoordinator.INSTANCE.sendPanelChat(bot, "system", "The current mission is paused. Queued goals are preserved and safety recovery can still run.");
        }
        io.github.zoyluo.minecraftai.persist.BotPersistence.INSTANCE.markDirty(bot.level().getServer());
        return changed;
    }

    public boolean resume(AIPlayerEntity bot, ControlOrigin origin, String reason) {
        requireServerThread(bot);
        String normalized = normalizeReason(origin, reason);
        if (!TaskManager.INSTANCE.isUserPaused(bot)) {
            return false;
        }
        boolean changed = BrainCoordinator.INSTANCE.invalidateDecision(bot, "intent_resume:" + normalized);
        changed |= BrainCoordinator.INSTANCE.clearIntentWakeSources(bot);
        changed |= TaskManager.INSTANCE.resumeUserIntent(bot, normalized);
        BotLog.comm(bot, "intent_resumed", "origin", origin, "reason", normalized,
                "queue_preserved", GoalExecutor.INSTANCE.queuedGoalCount(bot),
                "stack_depth", TaskManager.INSTANCE.pausedDepth(bot));
        if (origin.notifiesUser()) {
            BrainCoordinator.INSTANCE.sendPanelChat(bot, "system", "Mission resumed from its paused point.");
        }
        io.github.zoyluo.minecraftai.persist.BotPersistence.INSTANCE.markDirty(bot.level().getServer());
        return changed;
    }

    /** Returns true when an exact player phrase was handled without sending an LLM request. */
    public boolean routePlayerControlPhrase(AIPlayerEntity bot, ControlOrigin origin, String text) {
        String normalized = text == null ? "" : text.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[.!?]+$", "");
        // Accept the literal bracketed phrase the batch-checkpoint prompt tells the player to
        // type ("[continue]" / "[stop]") in addition to its unbracketed form.
        if (normalized.length() > 1 && normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        // A consent checkpoint is intentionally narrower than a normal pause.  Handle it before
        // generic resume so an ordinary "continue" cannot bypass the goal executor's durable
        // marker, and so the existing plan is resumed rather than replaced by a fresh LLM turn.
        if (isBatchContinuationAcknowledgement(normalized)
                && GoalExecutor.INSTANCE.isAwaitingBatchContinuation(bot)) {
            // A human acknowledgement wins over an in-flight adaptive strategy reply. Invalidate
            // first so a delayed tokenless/control response cannot alter the freshly resumed work.
            BrainCoordinator.INSTANCE.invalidateDecision(bot, "manual_goal_checkpoint_continue");
            BrainCoordinator.INSTANCE.clearIntentWakeSources(bot);
            if (GoalExecutor.INSTANCE.resumeBatchCheckpoint(bot)) {
                return true;
            }
        }
        if (isBatchStopAcknowledgement(normalized)
                && GoalExecutor.INSTANCE.isAwaitingBatchContinuation(bot)) {
            cancelCurrent(bot, origin, "goal_batch_checkpoint_stop");
            return true;
        }
        if (java.util.Set.of("pause", "hold").contains(normalized)) {
            pause(bot, origin, "control_phrase");
            return true;
        }
        boolean explicitResume = java.util.Set.of("resume", "go on", "continue").contains(normalized);
        boolean pausedResume = TaskManager.INSTANCE.isUserPaused(bot)
                && (normalized.startsWith("continue") || normalized.startsWith("resume"));
        if (explicitResume || pausedResume) {
            resume(bot, origin, "control_phrase");
            return true;
        }
        return false;
    }

    /** Exact, low-ambiguity acknowledgements accepted only while a goal batch is waiting. */
    private static boolean isBatchContinuationAcknowledgement(String normalized) {
        return java.util.Set.of(
                "yes", "yes please", "yeah", "yep", "ok", "okay", "sure",
                "continue", "continue please", "please continue", "go ahead",
                "keep going", "carry on", "next batch", "resume")
                .contains(normalized);
    }

    /** Exact, low-ambiguity refusals accepted only while a goal batch is waiting. */
    private static boolean isBatchStopAcknowledgement(String normalized) {
        return java.util.Set.of(
                "no", "no thanks", "nope", "stop", "stop please", "please stop",
                "end", "end goal", "cancel", "cancel goal", "that's enough", "thats enough")
                .contains(normalized);
    }

    private IntentControlTransaction.Outcome cancel(AIPlayerEntity bot,
                                                    ControlOrigin origin,
                                                    String reason,
                                                    IntentControlTransaction.Scope scope,
                                                    boolean keepActiveSafety) {
        Objects.requireNonNull(origin, "origin");
        String normalizedReason = reason == null || reason.isBlank() ? origin.name().toLowerCase() : reason;
        IntentControlTransaction.Port port = new MinecraftPort(bot, origin, normalizedReason,
                keepActiveSafety && TaskManager.INSTANCE.isActiveSafety(bot));
        return IntentControlTransaction.cancel(port, scope, origin.invalidatesDecision());
    }

    private static String normalizeReason(ControlOrigin origin, String reason) {
        return reason == null || reason.isBlank() ? origin.name().toLowerCase(Locale.ROOT) : reason;
    }

    private static void requireServerThread(AIPlayerEntity bot) {
        Objects.requireNonNull(bot, "bot");
        if (!bot.level().getServer().isSameThread()) {
            throw new IllegalStateException("intent_control_must_run_on_server_thread");
        }
    }

    private static final class MinecraftPort implements IntentControlTransaction.Port {
        private final AIPlayerEntity bot;
        private final ControlOrigin origin;
        private final String reason;
        private final boolean keepSafety;

        private MinecraftPort(AIPlayerEntity bot, ControlOrigin origin, String reason, boolean keepSafety) {
            this.keepSafety = keepSafety;
            this.bot = bot;
            this.origin = origin;
            this.reason = reason;
        }

        @Override
        public boolean invalidateDecision() {
            return BrainCoordinator.INSTANCE.invalidateDecision(bot, "intent_control:" + reason);
        }

        @Override
        public boolean clearWakeSources() {
            return BrainCoordinator.INSTANCE.clearIntentWakeSources(bot);
        }

        @Override
        public boolean detachCurrentMission() {
            return GoalExecutor.INSTANCE.cancelCurrent(bot, reason);
        }

        @Override
        public int clearMissionQueue() {
            return GoalExecutor.INSTANCE.clearQueue(bot);
        }

        @Override
        public boolean clearLongTermGoal() {
            return BotMemoryStore.INSTANCE.of(bot.getUUID()).clearGoal();
        }

        @Override
        public boolean cancelClaimedJob() {
            return IdleCoordinator.INSTANCE.cancelClaimedJob(bot, reason);
        }

        @Override
        public boolean cancelActiveAndPausedWork() {
            return keepSafety
                    ? TaskManager.INSTANCE.cancelIntentTasksKeepingActiveSafety(bot, "cancelled:" + reason)
                    : TaskManager.INSTANCE.cancelIntentTasks(bot, "cancelled:" + reason);
        }

        @Override
        public boolean stopActions() {
            if (keepSafety) {
                return false; // the kept SAFETY task owns the live actions (e.g. a fight in progress)
            }
            boolean changed = bot.getActionPack().hasActiveActions();
            changed |= StuckWatcher.INSTANCE.reset(bot);
            bot.getActionPack().stopAll();
            return changed;
        }

        @Override
        public void publish(IntentControlTransaction.Outcome outcome) {
            int queued = GoalExecutor.INSTANCE.queuedGoalCount(bot);
            BotLog.comm(bot, "intent_cancelled",
                    "scope", outcome.scope(),
                    "origin", origin,
                    "reason", reason,
                    "queue_remaining", queued);
            if (!origin.notifiesUser()) {
                return;
            }
            String text = outcome.scope() == IntentControlTransaction.Scope.ALL
                    ? "Cancelled all tasks and queued goals."
                    : queued > 0
                            ? "Cancelled the current task; queued goals will continue."
                            : "Cancelled the current task.";
            try {
                BrainCoordinator.INSTANCE.sendPanelChat(bot, "system", text);
            } catch (RuntimeException exception) {
                BotLog.error(bot, "intent_cancel_notification_failed", exception, "reason", reason);
            }
        }
    }

    public enum ControlOrigin {
        PLAYER_PANEL(true, true),
        PLAYER_COMMAND(true, true),
        LLM_TOOL(false, true),
        SYSTEM(true, false);

        private final boolean invalidatesDecision;
        private final boolean notifiesUser;

        ControlOrigin(boolean invalidatesDecision, boolean notifiesUser) {
            this.invalidatesDecision = invalidatesDecision;
            this.notifiesUser = notifiesUser;
        }

        public boolean invalidatesDecision() {
            return invalidatesDecision;
        }

        public boolean notifiesUser() {
            return notifiesUser;
        }
    }

    public record ReplaceResult(
            IntentControlTransaction.Outcome cancellation,
            boolean replacementStarted) {
    }
}

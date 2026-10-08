package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.brain.BotReporter;
import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.goal.GoalExecutor;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.observe.BotProfiler;
import io.github.zoyluo.minecraftai.observe.TpsGuard;
import io.github.zoyluo.minecraftai.runtime.ExecutionStack;
import io.github.zoyluo.minecraftai.runtime.RuntimeLifecycleCoordinator;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

public final class TaskManager {
    public static final TaskManager INSTANCE = new TaskManager();

    private final Map<UUID, Task> active = new ConcurrentHashMap<>();
    private final Map<UUID, TaskOrigin> activeOrigins = new ConcurrentHashMap<>();
    private final Map<UUID, ExecutionStack<Task>> executionStacks = new ConcurrentHashMap<>();
    private final Set<UUID> userPaused = ConcurrentHashMap.newKeySet();
    private final Map<UUID, TaskStatus> lastStatus = new ConcurrentHashMap<>();
    private final Map<UUID, FailureRecord> lastFailure = new ConcurrentHashMap<>();
    private final Map<UUID, FailureRecord> pendingFailure = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> userPauseEpoch = new ConcurrentHashMap<>();

    private TaskManager() {
    }

    public void assign(AIPlayerEntity bot, Task task, TaskOrigin origin) {
        assign(bot, task, origin, true);
    }

    /**
     * Establishes task authority without publishing irreversible user-visible task reports.
     * Restore callers must publish the assignment only after their persisted authority has passed
     * every semantic check.
     */
    public void assignSilently(AIPlayerEntity bot, Task task, TaskOrigin origin) {
        assign(bot, task, origin, false);
    }

    private void assign(AIPlayerEntity bot, Task task, TaskOrigin origin,
                        boolean publishStatus) {
        if (isUserPaused(bot) && !origin.safety()) {
            throw new IllegalStateException("mission_user_paused");
        }
        if (isNewOwnerDirection(origin)) {
            RuntimeLifecycleCoordinator.INSTANCE.clearOwnerDeathWatch(bot, "new_owner_direction");
        }
        abort(bot, publishStatus);
        bot.getActionPack().stopAll();
        UUID uuid = bot.getUUID();
        active.put(uuid, task);
        activeOrigins.put(uuid, origin);
        try {
            task.start(bot);
        } catch (RuntimeException startFailure) {
            active.remove(uuid, task);
            activeOrigins.remove(uuid, origin);
            try {
                task.abort(bot);
                if (task instanceof AbstractTask abstractTask && task.state() == TaskState.FAILED) {
                    String message = startFailure.getMessage() == null
                            ? startFailure.getClass().getSimpleName()
                            : startFailure.getMessage();
                    abstractTask.failureReason = "start_failed:" + message;
                }
            } catch (RuntimeException cleanupFailure) {
                startFailure.addSuppressed(cleanupFailure);
            }
            try {
                bot.getActionPack().stopAll();
            } catch (RuntimeException cleanupFailure) {
                startFailure.addSuppressed(cleanupFailure);
            }
            TaskStatus failed = TaskStatus.from(task);
            lastStatus.put(uuid, failed);
            if (failed.state() == TaskState.FAILED) {
                recordFailure(bot, task.name(), failed.failureReason(), bot.level().getServer().getTickCount());
            }
            if (publishStatus) {
                BotReporter.INSTANCE.onStatus(bot.level().getServer(), bot, failed);
            }
            BotLog.error(bot, "task_start_failed", startFailure, "name", task.name());
            throw startFailure;
        }
        TaskStatus status = TaskStatus.from(task);
        lastStatus.put(uuid, status);
        if (publishStatus) {
            BotReporter.INSTANCE.onAssigned(bot, status);
        }
        BotLog.task(bot, "task_assigned", "name", task.name(), "params", task.describe(),
                "origin", origin.kind(), "origin_reason", origin.reason());
    }

    public void abort(AIPlayerEntity bot) {
        abort(bot, true);
    }

    private void abort(AIPlayerEntity bot, boolean publishStatus) {
        Task current = active.remove(bot.getUUID());
        activeOrigins.remove(bot.getUUID());
        if (current != null) {
            current.abort(bot);
            lastStatus.put(bot.getUUID(), TaskStatus.from(current));
            if (publishStatus) {
                BotReporter.INSTANCE.onStatus(bot.level().getServer(), bot, TaskStatus.from(current));
            }
        }
    }

    /** Publishes the assignment established by {@link #assignSilently} after restore commit. */
    public boolean publishCurrentAssignment(AIPlayerEntity bot) {
        Task current = active.get(bot.getUUID());
        if (current == null) {
            return false;
        }
        TaskStatus status = TaskStatus.from(current);
        if (status.state() != TaskState.RUNNING
                && status.state() != TaskState.PAUSED) {
            return false;
        }
        lastStatus.put(bot.getUUID(), status);
        BotReporter.INSTANCE.onAssigned(bot, status);
        return true;
    }

    /**
     * Fully resets the bot to a clean idle state: stops active/paused tasks, clears the failure record and
     * status cache, so status() returns idle.
     * Intended for the brain to call as cleanup in scenarios like giving up after repeated failures
     * (max_turns) — otherwise, once a task is left in FAILED state, lastStatus keeps caching FAILED
     * indefinitely (the panel/diagnostics keep showing it as stuck), and a lingering pendingFailure also
     * causes the idle-watcher to spin with nothing to do (root cause of an observed 13-minute stall).
     */
    public void resetToIdle(AIPlayerEntity bot) {
        UUID uuid = bot.getUUID();
        Task current = active.remove(uuid);
        if (current != null) {
            current.abort(bot);
        }
        ExecutionStack<Task> stack = executionStacks.remove(uuid);
        if (stack != null) {
            for (ExecutionStack.Frame<Task> frame : stack.drain()) {
                frame.work().abort(bot);
            }
        }
        activeOrigins.remove(uuid);
        userPaused.remove(uuid);
        lastFailure.remove(uuid);
        pendingFailure.remove(uuid);
        lastStatus.put(uuid, TaskStatus.idle());
        BotReporter.INSTANCE.onStatus(bot.level().getServer(), bot, TaskStatus.idle());
    }

    /** User-intent cancellation: clear active and paused work without creating a failure/replan. */
    public boolean cancelIntentTasks(AIPlayerEntity bot, String reason) {
        return cancelIntentTasks(bot, reason, false);
    }

    /**
     * Like {@link #cancelIntentTasks} but a currently active SAFETY-origin task (a fight, an
     * evade, a shelter) keeps running: a new chat request must not cancel a combat mid-fight. All
     * paused work beneath it is still cancelled (the request replaces it). Returns whether anything
     * was cancelled.
     */
    public boolean cancelIntentTasksKeepingActiveSafety(AIPlayerEntity bot, String reason) {
        return cancelIntentTasks(bot, reason, isActiveSafety(bot));
    }

    /**
     * Owner death is a battlefield transition: keep a live fight, evade or guard owner as well
     * as ordinary safety work, but clear any follow/mission waiting beneath it so it cannot later
     * resume toward the dead player.
     */
    public boolean cancelIntentTasksKeepingActiveCombatOrSafety(AIPlayerEntity bot, String reason) {
        Task current = active.get(bot.getUUID());
        boolean keepActive = isActiveSafety(bot)
                || current instanceof CombatTask
                || current instanceof EvadeTask
                || current instanceof GuardTask;
        return cancelIntentTasks(bot, reason, keepActive);
    }

    /** True when the active task exists and was assigned with SAFETY authority. */
    public boolean isActiveSafety(AIPlayerEntity bot) {
        UUID uuid = bot.getUUID();
        TaskOrigin origin = activeOrigins.get(uuid);
        return active.containsKey(uuid) && origin != null && origin.safety();
    }

    private boolean cancelIntentTasks(AIPlayerEntity bot, String reason, boolean keepActive) {
        UUID uuid = bot.getUUID();
        bumpUserPauseEpoch(uuid);
        Task current = keepActive ? null : active.remove(uuid);
        if (!keepActive) {
            activeOrigins.remove(uuid);
        }
        ExecutionStack<Task> stack = executionStacks.remove(uuid);
        java.util.List<ExecutionStack.Frame<Task>> pausedFrames = stack == null ? java.util.List.of() : stack.drain();
        userPaused.remove(uuid);
        boolean hadFailure = lastFailure.remove(uuid) != null;
        boolean hadPendingFailure = pendingFailure.remove(uuid) != null;
        if (current != null) {
            try {
                current.cancel(bot, reason);
            } catch (RuntimeException exception) {
                BotLog.error(bot, "task_cancel_cleanup_failed", exception, "name", current.name(), "reason", reason);
            }
        }
        for (ExecutionStack.Frame<Task> frame : pausedFrames) {
            Task pausedTask = frame.work();
            if (pausedTask == current) {
                continue;
            }
            try {
                pausedTask.cancel(bot, reason);
            } catch (RuntimeException exception) {
                BotLog.error(bot, "paused_task_cancel_cleanup_failed", exception, "name", pausedTask.name(), "reason", reason);
            }
        }
        Task representative = current != null ? current : pausedFrames.isEmpty() ? null : pausedFrames.get(0).work();
        if (representative != null) {
            TaskStatus cancelled = TaskStatus.from(representative);
            lastStatus.put(uuid, cancelled);
            BotReporter.INSTANCE.onStatus(bot.level().getServer(), bot, cancelled);
            BotLog.task(bot, "task_cancelled", "name", representative.name(), "reason", reason);
        } else if (hadFailure || hadPendingFailure) {
            lastStatus.put(uuid, TaskStatus.idle());
            BotReporter.INSTANCE.onStatus(bot.level().getServer(), bot, TaskStatus.idle());
        }
        return representative != null || hadFailure || hadPendingFailure;
    }

    public Optional<Task> getActive(AIPlayerEntity bot) {
        return Optional.ofNullable(active.get(bot.getUUID()));
    }

    public boolean hasPaused(AIPlayerEntity bot) {
        ExecutionStack<Task> stack = executionStacks.get(bot.getUUID());
        return stack != null && !stack.isEmpty();
    }

    /** Most recently interrupted work, used by safety routing without consuming the stack. */
    public Optional<Task> peekPaused(AIPlayerEntity bot) {
        ExecutionStack<Task> stack = executionStacks.get(bot.getUUID());
        return stack == null ? Optional.empty() : stack.peek().map(ExecutionStack.Frame::work);
    }

    /**
     * True when a task type is live or retained beneath a temporary safety interrupt. This is
     * intentionally broader than {@link #peekPaused(AIPlayerEntity)}: a player control mode must
     * not silently disappear merely because lava or a shelter pushed another safety frame on top.
     */
    public boolean hasActiveOrPausedTask(AIPlayerEntity bot, Class<? extends Task> taskType) {
        if (bot == null || taskType == null) {
            return false;
        }
        Task current = active.get(bot.getUUID());
        if (taskType.isInstance(current)) {
            return true;
        }
        ExecutionStack<Task> stack = executionStacks.get(bot.getUUID());
        return stack != null && stack.anyMatch(taskType::isInstance);
    }

    /**
     * Captures a live or safety-paused follow instruction before a lifecycle interruption clears
     * the task stack. The active task wins; otherwise the newest paused follow is the one the
     * player was most recently asking the companion to perform.
     */
    public Optional<FollowIntent> activeOrPausedFollowIntent(AIPlayerEntity bot) {
        if (bot == null) {
            return Optional.empty();
        }
        UUID uuid = bot.getUUID();
        Task current = active.get(uuid);
        if (current instanceof FollowTask follow) {
            return Optional.of(new FollowIntent(follow.requestedTargetName(), activeOrigins.get(uuid)));
        }
        ExecutionStack<Task> stack = executionStacks.get(uuid);
        return stack == null ? Optional.empty()
                : stack.newestMatch(FollowTask.class::isInstance)
                .map(frame -> new FollowIntent(((FollowTask) frame.work()).requestedTargetName(), frame.origin()));
    }

    /** A resumable player follow intent captured across a companion death. */
    public record FollowIntent(String targetName, TaskOrigin origin) {
    }

    /** Player-directed work intentionally releases the local hold installed when that player died. */
    private static boolean isNewOwnerDirection(TaskOrigin origin) {
        if (origin == null) {
            return false;
        }
        return switch (origin.kind()) {
            case PLAYER_COMMAND, PLAYER_PANEL, LLM_TOOL, MISSION, JOB -> true;
            case SAFETY, SYSTEM_BACKGROUND, VERIFY -> false;
        };
    }

    public int pausedDepth(AIPlayerEntity bot) {
        ExecutionStack<Task> stack = executionStacks.get(bot.getUUID());
        return stack == null ? 0 : stack.size();
    }

    public Optional<TaskOrigin> activeOrigin(AIPlayerEntity bot) {
        return Optional.ofNullable(activeOrigins.get(bot.getUUID()));
    }

    public boolean isUserPaused(AIPlayerEntity bot) {
        return userPaused.contains(bot.getUUID());
    }

    /** Design 6.5 "Pause epoch": additive per-bot counter bumped by pauseUserIntent, resumeUserIntent, and
     * cancelIntentTasks (which also covers bot unload, since onBotDespawn calls cancelIntentTasks(bot,
     * "bot_unload") as its own first statement). A genuine despawn must bump it exactly once:
     * {@code RuntimeLifecycleCoordinator.deleteBot} already cancels active/paused work through
     * {@code IntentController.cancelAll} (which itself reaches {@code cancelIntentTasks} via
     * {@code cancelActiveAndPausedWork}), so it calls {@link #forgetDespawnedBot} there, never
     * {@link #onBotDespawn} — the latter's own {@code cancelIntentTasks} call would double-bump the epoch
     * for the same despawn (real-server regression guard: {@code OreDigPoiGameTests
     * .userPauseEpochBumpsOnEveryTransition}). {@code RuntimeLifecycleCoordinator.unloadBot} has no such
     * prior cancellation and keeps calling {@link #onBotDespawn} for its one bump.
     * Unused by TaskManager itself in P2; P3's hold logic reads it to detect a player action during a hold. */
    public int userPauseEpoch(AIPlayerEntity bot) {
        return userPauseEpoch.getOrDefault(bot.getUUID(), 0);
    }

    private void bumpUserPauseEpoch(UUID uuid) {
        userPauseEpoch.merge(uuid, 1, Integer::sum);
    }

    public TaskStatus status(AIPlayerEntity bot) {
        Task current = active.get(bot.getUUID());
        if (current != null) {
            return TaskStatus.from(current);
        }
        ExecutionStack<Task> stack = executionStacks.get(bot.getUUID());
        if (stack != null && stack.peek().isPresent()) {
            return TaskStatus.from(stack.peek().orElseThrow().work());
        }
        return lastStatus.getOrDefault(bot.getUUID(), TaskStatus.idle());
    }

    public void pauseFor(AIPlayerEntity bot, String why) {
        UUID uuid = bot.getUUID();
        Task current = active.remove(uuid);
        TaskOrigin origin = activeOrigins.remove(uuid);
        if (current == null) {
            return;
        }
        current.pause(bot);
        TaskOrigin preservedOrigin = origin == null
                ? TaskOrigin.of(TaskOrigin.Kind.SYSTEM_BACKGROUND, "unknown_origin") : origin;
        ExecutionStack<Task> stack = executionStacks.computeIfAbsent(uuid, ignored -> new ExecutionStack<>());
        stack.push(current, preservedOrigin);
        TaskStatus status = TaskStatus.from(current);
        lastStatus.put(bot.getUUID(), status);
        BotReporter.INSTANCE.onStatus(bot.level().getServer(), bot, status);
        BotLog.task(bot, "task_paused", "name", current.name(), "why", why,
                "origin", preservedOrigin.kind(), "stack_depth", stack.size());
    }

    public void resumeFromPause(AIPlayerEntity bot) {
        UUID uuid = bot.getUUID();
        if (active.containsKey(uuid)) {
            return;
        }
        ExecutionStack<Task> stack = executionStacks.get(uuid);
        if (stack == null) {
            return;
        }
        Optional<ExecutionStack.Frame<Task>> resumable = stack.popResumable(userPaused.contains(uuid));
        if (resumable.isEmpty()) {
            return;
        }
        ExecutionStack.Frame<Task> frame = resumable.get();
        Task task = frame.work();
        active.put(uuid, task);
        activeOrigins.put(uuid, frame.origin());
        task.resume(bot);
        TaskStatus status = TaskStatus.from(task);
        lastStatus.put(bot.getUUID(), status);
        BotReporter.INSTANCE.onStatus(bot.level().getServer(), bot, status);
        if (stack.isEmpty()) {
            executionStacks.remove(uuid, stack);
        }
        BotLog.task(bot, "task_resumed", "name", task.name(), "origin", frame.origin().kind(),
                "stack_depth", stack.size(), "user_paused", userPaused.contains(uuid));
    }

    public boolean pauseUserIntent(AIPlayerEntity bot, String why) {
        UUID uuid = bot.getUUID();
        bumpUserPauseEpoch(uuid);
        boolean changed = userPaused.add(uuid);
        TaskOrigin origin = activeOrigins.get(uuid);
        if (active.containsKey(uuid) && (origin == null || !origin.safety())) {
            pauseFor(bot, "user_pause:" + why);
            changed = true;
        }
        bot.getActionPack().stopAll();
        BotLog.task(bot, "mission_user_paused", "why", why, "stack_depth", pausedDepth(bot));
        return changed;
    }

    public boolean resumeUserIntent(AIPlayerEntity bot, String why) {
        UUID uuid = bot.getUUID();
        bumpUserPauseEpoch(uuid);
        boolean changed = userPaused.remove(uuid);
        if (!active.containsKey(uuid)) {
            int before = pausedDepth(bot);
            resumeFromPause(bot);
            changed |= pausedDepth(bot) != before;
        }
        BotLog.task(bot, "mission_user_resumed", "why", why, "stack_depth", pausedDepth(bot));
        return changed;
    }

    public void tickAll(MinecraftServer server) {
        // A tower whose task has ended comes down before the bot's next task goes on (see TowerCustody).
        Set<UUID> descending = TowerCustody.INSTANCE.tickOrphans();
        for (Map.Entry<UUID, Task> entry : new ArrayList<>(active.entrySet())) {
            UUID uuid = entry.getKey();
            Task task = entry.getValue();
            Optional<AIPlayerEntity> bot = AIPlayerManager.INSTANCE.getByUuid(uuid);
            if (bot.isEmpty()) {
                active.remove(uuid);
                activeOrigins.remove(uuid);
                executionStacks.remove(uuid);
                userPaused.remove(uuid);
                continue;
            }
            AIPlayerEntity player = bot.get();
            TaskOrigin origin = activeOrigins.get(uuid);
            if (descending.contains(uuid)) {
                continue;
            }
            if ((origin == null || !origin.safety()) && !isCritical(task)
                    && !TpsGuard.INSTANCE.shouldTickNonCriticalTask(server, uuid)) {
                BotProfiler.INSTANCE.record(player, "task_tick_skipped", 0L);
                continue;
            }
            // V1 unified survival layer: circuit-breaker check before each task tick — drowning/lava/on
            // fire/near-death conditions unconditionally halt the task, and the failure reason is passed
            // through to the goal layer's replan. A task's own private circuit breaker can react earlier
            // and more intelligently, but this serves as the fallback when one isn't configured.
            String breaker = SurvivalGuard.INSTANCE.check(player, task);
            if (breaker != null && task.state() == TaskState.RUNNING) {
                if ((origin == null || !origin.safety()) && breaker.startsWith("guard_")) {
                    // Survival interruptions are temporary physical conditions, not evidence that
                    // the mission cursor is invalid. Preserve the exact task instance and let the
                    // safety layer repay the danger before resuming it.
                    pauseFor(player, "survival_guard:" + breaker);
                    BotLog.danger(player, "survival_guard_paused",
                            "task", task.name(), "why", breaker);
                    continue;
                }
                task.abort(player);
                if (task instanceof AbstractTask at) {
                    at.failureReason = breaker; // abort defaults to "aborted"; replace with a diagnosable circuit-breaker reason
                }
                BotLog.danger(player, "survival_guard_abort", "task", task.name(), "why", breaker);
            }
            long started = System.nanoTime();
            try {
                task.tick(player);
            } finally {
                BotProfiler.INSTANCE.record(player, "task_tick", System.nanoTime() - started);
            }
            TaskStatus status = TaskStatus.from(task);
            lastStatus.put(uuid, status);
            BotReporter.INSTANCE.onStatus(server, player, status);
            if (task.state() == TaskState.COMPLETED) {
                active.remove(uuid);
                activeOrigins.remove(uuid);
                lastFailure.remove(uuid);
                pendingFailure.remove(uuid);
                BotLog.task(player, "task_completed", "name", task.name(), "elapsed_ticks", task.elapsedTicks());
                if ((task instanceof GatherQuotaTask || task instanceof MineTask) && userRequested(origin)) {
                    StorageJanitor.INSTANCE.noteUserGather(player, server.getTickCount());
                }
            } else if (task.state() == TaskState.FAILED) {
                active.remove(uuid);
                activeOrigins.remove(uuid);
                recordFailure(player, task.name(), task.failureReason(), server.getTickCount());
                BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.TASK, player, "task_failed",
                        "name", task.name(), "reason", task.failureReason(), "elapsed_ticks", task.elapsedTicks());
                reportDirectTimedCollectionMiss(player, origin, task);
            } else if (task.state() == TaskState.CANCELLED) {
                active.remove(uuid);
                activeOrigins.remove(uuid);
                lastFailure.remove(uuid);
                pendingFailure.remove(uuid);
                BotLog.task(player, "task_cancelled", "name", task.name(), "reason", task.failureReason());
            }
        }
    }

    /** True for work the player asked for (directly, through a mission/job or through the brain), not background upkeep. */
    private static boolean userRequested(TaskOrigin origin) {
        return origin != null && switch (origin.kind()) {
            case PLAYER_COMMAND, PLAYER_PANEL, LLM_TOOL, MISSION, JOB -> true;
            case SAFETY, SYSTEM_BACKGROUND, VERIFY -> false;
        };
    }

    /**
     * Goal missions publish their own exact terminal result. A direct tool/command task has no
     * goal executor to do that, so make the promised "none found after ten minutes" reply
     * unconditional even when optional verbose task reporting is disabled.
     */
    private static void reportDirectTimedCollectionMiss(AIPlayerEntity bot,
                                                         TaskOrigin origin,
                                                         Task task) {
        if (task == null || !userRequested(origin)
                || GoalExecutor.INSTANCE.hasActivePlan(bot)
                || BotReporter.INSTANCE.taskReportsEnabled(bot)
                || !GatherQuotaTask.NO_RESOURCE_FOUND_BY_DEADLINE.equals(task.failureReason())) {
            return;
        }
        BrainCoordinator.INSTANCE.sendBotReply(bot,
                "I explored for ten minutes but found none of the requested resource.");
    }

    public void recordFailure(AIPlayerEntity bot, String name, String reason, int tick) {
        UUID uuid = bot.getUUID();
        FailureRecord previous = lastFailure.get(uuid);
        int count = previous != null && previous.name().equals(name) && previous.reason().equals(reason)
                ? previous.count() + 1
                : 1;
        FailureRecord record = new FailureRecord(name, reason, count, tick);
        lastFailure.put(uuid, record);
        pendingFailure.put(uuid, record);
    }

    public Optional<FailureRecord> peekFailure(AIPlayerEntity bot) {
        return Optional.ofNullable(pendingFailure.get(bot.getUUID()));
    }

    public Optional<FailureRecord> consumeFailure(AIPlayerEntity bot) {
        return Optional.ofNullable(pendingFailure.remove(bot.getUUID()));
    }

    public void onServerStopping(MinecraftServer server) {
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            cancelIntentTasks(bot, "server_unload");
        }
        TowerCustody.INSTANCE.clearAll();
        active.clear();
        activeOrigins.clear();
        executionStacks.clear();
        userPaused.clear();
        lastStatus.clear();
        lastFailure.clear();
        pendingFailure.clear();
        userPauseEpoch.clear();
        BotLog.task(null, "tasks_cleared");
    }

    public void onBotDespawn(AIPlayerEntity bot) {
        cancelIntentTasks(bot, "bot_unload");
        forgetDespawnedBot(bot);
    }

    /** The per-bot bookkeeping cleanup half of {@link #onBotDespawn}, without the {@code cancelIntentTasks}
     * call (and its {@code userPauseEpoch} bump): for a caller that already ran the equivalent cancellation
     * itself (see {@link #userPauseEpoch}'s javadoc). Safe to call after work is already cancelled — every
     * map removal here is then a no-op except the ones {@code cancelIntentTasks} never touches
     * ({@code lastStatus}, {@code BotReporter}). */
    public void forgetDespawnedBot(AIPlayerEntity bot) {
        executionStacks.remove(bot.getUUID());
        activeOrigins.remove(bot.getUUID());
        userPaused.remove(bot.getUUID());
        TowerCustody.INSTANCE.forget(bot.getUUID());
        lastStatus.remove(bot.getUUID());
        lastFailure.remove(bot.getUUID());
        pendingFailure.remove(bot.getUUID());
        BotReporter.INSTANCE.onCleared(bot);
    }

    public void clearAllRuntime() {
        active.clear();
        TowerCustody.INSTANCE.clearAll();
        activeOrigins.clear();
        executionStacks.clear();
        userPaused.clear();
        lastStatus.clear();
        lastFailure.clear();
        pendingFailure.clear();
        userPauseEpoch.clear();
    }

    public int activeCount() {
        return active.size();
    }

    private static boolean isCritical(Task task) {
        return task instanceof EvadeTask
                || task instanceof CreeperDefenseTask
                || task instanceof CombatTask
                || task instanceof EmergencyShelterTask
                || task instanceof MiningBarricadeTask
                || task instanceof EatTask
                || task instanceof ResupplyTask
                // A follower with a hostile in its face swings on ready ticks: it must tick on every one of them.
                || task instanceof FollowTask follow && follow.escortEngaged();
    }

    public record FailureRecord(String name, String reason, int count, int tick) {
    }
}

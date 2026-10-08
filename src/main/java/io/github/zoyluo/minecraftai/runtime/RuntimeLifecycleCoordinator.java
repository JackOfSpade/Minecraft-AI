package io.github.zoyluo.minecraftai.runtime;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.baritone.BaritoneRegistry;
import io.github.zoyluo.minecraftai.navigation.NavEngineSelector;
import io.github.zoyluo.minecraftai.brain.AmbientConversationCoordinator;
import io.github.zoyluo.minecraftai.brain.BotReporter;
import io.github.zoyluo.minecraftai.brain.BotRuntimeOptions;
import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.brain.ChatRecipientRouter;
import io.github.zoyluo.minecraftai.brain.PoiAdvisor;
import io.github.zoyluo.minecraftai.coordination.IdleCoordinator;
import io.github.zoyluo.minecraftai.coordination.PoiCoordinator;
import io.github.zoyluo.minecraftai.coordination.TaskBoard;
import io.github.zoyluo.minecraftai.craft.RuntimeRecipeIndex;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.goal.GoalExecutor;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.BotLogWriter;
import io.github.zoyluo.minecraftai.log.CapabilityTally;
import io.github.zoyluo.minecraftai.log.DiagnosticLogger;
import io.github.zoyluo.minecraftai.log.InventoryAudit;
import io.github.zoyluo.minecraftai.loot.RuntimeDropIndex;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.memory.BotMemoryStore;
import io.github.zoyluo.minecraftai.memory.EpisodeLog;
import io.github.zoyluo.minecraftai.memory.KnowledgeBase;
import io.github.zoyluo.minecraftai.mining.MiningEvidenceAudit;
import io.github.zoyluo.minecraftai.mining.assist.BotEdits;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistRuntime;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.network.MinecraftAiServerNetworking;
import io.github.zoyluo.minecraftai.observe.BotProfiler;
import io.github.zoyluo.minecraftai.observe.ReplayRecorder;
import io.github.zoyluo.minecraftai.observe.TpsGuard;
import io.github.zoyluo.minecraftai.pathfinding.AStarPathfinder;
import io.github.zoyluo.minecraftai.persist.BotPersistence;
import io.github.zoyluo.minecraftai.task.DangerWatcher;
import io.github.zoyluo.minecraftai.task.EmergencyShelterTask;
import io.github.zoyluo.minecraftai.task.EpisodeMemory;
import io.github.zoyluo.minecraftai.task.FollowTask;
import io.github.zoyluo.minecraftai.task.NavSafetyNet;
import io.github.zoyluo.minecraftai.task.StuckWatcher;
import io.github.zoyluo.minecraftai.task.TaskManager;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Single ordering authority for world start/stop and Bot reset/death/despawn cleanup. */
public final class RuntimeLifecycleCoordinator {
    public static final RuntimeLifecycleCoordinator INSTANCE = new RuntimeLifecycleCoordinator();
    /** A direct player follow is a recoverable companion intent, unlike arbitrary interrupted work. */
    private static final Map<java.util.UUID, TaskManager.FollowIntent> FOLLOW_AFTER_DEATH = new ConcurrentHashMap<>();
    /** Companions whose owner died stay in local defense/hold mode until the owner gives them a new direction. */
    private static final Map<java.util.UUID, java.util.UUID> OWNER_DEATH_WATCH = new ConcurrentHashMap<>();

    private RuntimeLifecycleCoordinator() {
    }

    public void onServerStarted(MinecraftServer server, MinecraftAiConfig config) {
        clearWorldRuntime();
        BotLogWriter.INSTANCE.start(config);
        BrainCoordinator.INSTANCE.configure(config);
        ChatRecipientRouter.INSTANCE.configure(config);
        AmbientConversationCoordinator.INSTANCE.configure(config);
        PoiAdvisor.INSTANCE.configure(config);
        BotPersistence.INSTANCE.resumeWrites();
        RuntimeRecipeIndex.rebuild(server);
        RuntimeDropIndex.arm(server);
        KnowledgeBase.INSTANCE.attachServer(server);
        BotEdits.loadFromDisk(BotEdits.defaultSidecarPath());
        int restored = BotPersistence.INSTANCE.loadAndRespawn(server);
        BotLog.lifecycle("server_runtime_ready", "restored_bots", restored,
                "runtime_session", TaskBoard.INSTANCE.runtimeSessionId());
    }

    public void onServerStopping(MinecraftServer server) {
        BotLog.lifecycle("server_stopping");
        BotPersistence.INSTANCE.freezeWrites();
        int persisted = BotPersistence.INSTANCE.saveAll(server);
        AIPlayerManager.INSTANCE.onServerStopping(server);
        BotEdits.flushSync(BotEdits.defaultSidecarPath());
        ChatRecipientRouter.INSTANCE.shutdown();
        AmbientConversationCoordinator.INSTANCE.shutdown();
        PoiAdvisor.INSTANCE.shutdown();
        BrainCoordinator.INSTANCE.shutdown();
        clearWorldRuntime();
        KnowledgeBase.INSTANCE.detachServer();
        RuntimeRecipeIndex.clear();
        RuntimeDropIndex.clear();
        BotLog.lifecycle("server_runtime_stopped", "persisted_bots", persisted);
        BotLogWriter.INSTANCE.shutdown(3000);
    }

    public void resetBot(AIPlayerEntity bot, IntentController.ControlOrigin origin, String reason) {
        IntentController.INSTANCE.cancelAll(bot, origin, reason);
        BrainCoordinator.INSTANCE.reset(bot);
        // An explicit brain reset is a clean slate: the conversation memory is forgotten as well (death and
        // unload keep it).
        io.github.zoyluo.minecraftai.brain.ChatMemory.forget(bot.getUUID());
        GoalExecutor.INSTANCE.unload(bot);
        NavEngineSelector.hook("baritone_reset", () -> BaritoneRegistry.INSTANCE.reset(bot, "runtime_reset"));
        TaskManager.INSTANCE.resetToIdle(bot);
        clearTransient(bot);
        BotLog.lifecycle(bot, "bot_runtime_reset", "reason", reason);
    }

    public void onBotDeath(AIPlayerEntity bot) {
        // Capture this before the generic death reset drains both the active task and its safety
        // pause stack. A Mission restores through GoalExecutor's durable checkpoint instead.
        FOLLOW_AFTER_DEATH.remove(bot.getUUID());
        if (!TaskManager.INSTANCE.isUserPaused(bot)) {
            TaskManager.INSTANCE.activeOrPausedFollowIntent(bot)
                    .filter(intent -> intent.origin() == null || intent.origin().kind() != TaskOrigin.Kind.MISSION)
                    .ifPresent(intent -> FOLLOW_AFTER_DEATH.put(bot.getUUID(), intent));
        }
        BrainCoordinator.INSTANCE.invalidateDecision(bot, "bot_death");
        BrainCoordinator.INSTANCE.clearIntentWakeSources(bot);
        // A death interrupts but does not erase a long-running Mission. GoalExecutor persists the
        // exact goal/task checkpoint while RecoverDropsTask owns the safety slot, then resumes it.
        GoalExecutor.INSTANCE.suspendForDeath(bot);
        IdleCoordinator.INSTANCE.cancelClaimedJob(bot, "bot_died");
        TaskManager.INSTANCE.cancelIntentTasks(bot, "bot_died");
        NavEngineSelector.hook("baritone_reset", () -> BaritoneRegistry.INSTANCE.reset(bot, "bot_died"));
        bot.getActionPack().stopAll();
        BrainCoordinator.INSTANCE.reset(bot);
        clearTransient(bot);
        BotLog.lifecycle(bot, "bot_runtime_death_reset");
    }

    /**
     * A companion does not try to follow its dead owner or wander into a new job. It may finish
     * a live safety fight, then DangerWatcher gives it a permanent local hold until its owner
     * explicitly directs it again.
     */
    public void onOwnerDeath(ServerPlayer owner) {
        if (owner == null || owner instanceof AIPlayerEntity) {
            return;
        }
        int companions = 0;
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.botsOf(owner.getUUID())) {
            OWNER_DEATH_WATCH.put(bot.getUUID(), owner.getUUID());
            // A fight/shelter already keeping the companion alive remains in control, but any
            // follow or mission beneath it must not resume toward a dead player afterward.
            TaskManager.INSTANCE.cancelIntentTasksKeepingActiveCombatOrSafety(bot, "owner_died");
            BotLog.lifecycle(bot, "companion_owner_death_watch",
                    "owner", owner.getGameProfile().name(),
                    "mode", "fight_then_hold");
            companions++;
        }
        if (companions > 0) {
            BotLog.lifecycle("companion_owner_death_watch_started",
                    "owner", owner.getGameProfile().name(), "companions", companions);
        }
    }

    /** True while this companion must defend locally and then wait, instead of resuming old work. */
    public boolean awaitingOwnerReturn(AIPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        return AIPlayerManager.INSTANCE.ownerOf(bot)
                .filter(owner -> owner.equals(OWNER_DEATH_WATCH.get(bot.getUUID())))
                .isPresent();
    }

    /** A new owner-issued direction is the intentional exit from the post-death local hold. */
    public void clearOwnerDeathWatch(AIPlayerEntity bot, String reason) {
        if (bot != null && OWNER_DEATH_WATCH.remove(bot.getUUID()) != null) {
            BotLog.lifecycle(bot, "companion_owner_death_watch_cleared",
                    "reason", reason == null ? "new_owner_direction" : reason);
        }
    }

    /** Restores an interrupted direct follow after the retained fake player has been revived. */
    public void onBotRespawned(AIPlayerEntity bot) {
        TaskManager.FollowIntent intent = FOLLOW_AFTER_DEATH.remove(bot.getUUID());
        if (intent == null || !bot.isAlive()) {
            return;
        }
        if (awaitingOwnerReturn(bot)) {
            BotLog.lifecycle(bot, "companion_waiting_for_owner_after_death");
            return;
        }
        TaskOrigin origin = intent.origin() == null
                ? TaskOrigin.of(TaskOrigin.Kind.LLM_TOOL, "death_resume_follow")
                : intent.origin();
        TaskManager.INSTANCE.assign(bot, new FollowTask(intent.targetName()), origin);
        BotLog.lifecycle(bot, "follow_resumed_after_death",
                "target", intent.targetName().isBlank() ? "owner" : intent.targetName(),
                "origin", origin.kind());
    }

    /** Explicit despawn is deletion: publish cancellation first, then forget every cached projection.
     * {@code IntentController.cancelAll} above already cancels active/paused work (reaching
     * {@code TaskManager.cancelIntentTasks} via {@code cancelActiveAndPausedWork}), so cleanup here uses
     * {@code TaskManager.forgetDespawnedBot}, not {@code onBotDespawn}: the latter's own
     * {@code cancelIntentTasks} call would bump {@code userPauseEpoch} a second time for one despawn. */
    public void deleteBot(AIPlayerEntity bot) {
        FOLLOW_AFTER_DEATH.remove(bot.getUUID());
        OWNER_DEATH_WATCH.remove(bot.getUUID());
        IntentController.INSTANCE.cancelAll(bot, IntentController.ControlOrigin.SYSTEM, "bot_despawn");
        BrainCoordinator.INSTANCE.reset(bot);
        IdleCoordinator.INSTANCE.onBotRemoved(bot);
        TaskManager.INSTANCE.forgetDespawnedBot(bot);
        GoalExecutor.INSTANCE.unload(bot);
        // GameTest-only (see EmergencyShelterTask#forgetCleanupDebtsOwnedBy): this bot is gone for
        // good, so its own still-pending shelter cleanup debt can no longer be a real chore for
        // another bot to inherit -- only a same-batch cross-test leak. No-op in production.
        EmergencyShelterTask.forgetCleanupDebtsOwnedBy(bot);
        forgetBot(bot);
    }

    /** Server stop is unload, not deletion; the already-captured Mission must not receive CANCELLED. */
    public void unloadBot(AIPlayerEntity bot) {
        FOLLOW_AFTER_DEATH.remove(bot.getUUID());
        OWNER_DEATH_WATCH.remove(bot.getUUID());
        BrainCoordinator.INSTANCE.invalidateDecision(bot, "server_unload");
        BrainCoordinator.INSTANCE.reset(bot);
        GoalExecutor.INSTANCE.unload(bot);
        IdleCoordinator.INSTANCE.onBotUnloaded(bot);
        TaskManager.INSTANCE.onBotDespawn(bot);
        bot.getActionPack().stopAll();
        forgetBot(bot);
    }

    private static void clearTransient(AIPlayerEntity bot) {
        StuckWatcher.INSTANCE.reset(bot);
        DangerWatcher.INSTANCE.clear(bot);
        NavSafetyNet.INSTANCE.clear(bot);
        EpisodeMemory.INSTANCE.reset(bot.getUUID());
        BotReporter.INSTANCE.onCleared(bot);
        DiagnosticLogger.INSTANCE.clear(bot);
        InventoryAudit.INSTANCE.clear(bot);
        CapabilityRuntime.clear(bot);
        CapabilityTally.INSTANCE.clear(bot.getUUID());
        MiningAssistRuntime.clearBotUnload(bot);
        // P3: the R4 in-flight-consult bookkeeping is per-bot state too, same restart-safety reasoning as
        // MiningAssistRuntime.clearBotUnload immediately above (see PoiCoordinator.clearBot's own javadoc).
        PoiCoordinator.INSTANCE.clearBot(bot.getUUID());
    }

    private static void forgetBot(AIPlayerEntity bot) {
        NavEngineSelector.hook("baritone_forget", () -> BaritoneRegistry.INSTANCE.forget(bot, "bot_forgotten"));
        MiningEvidenceAudit.clear(bot);
        MiningAssistRuntime.clearForced(bot.getUUID());
        clearTransient(bot);
        BotRuntimeOptions.INSTANCE.clear(bot);
        NavEngineSelector.clearBotEngine(bot.getUUID());
        BotMemoryStore.INSTANCE.remove(bot.getUUID());
        io.github.zoyluo.minecraftai.brain.ChatMemory.forget(bot.getUUID());
        EpisodeLog.INSTANCE.clearFor(bot.getUUID());
        KnowledgeBase.INSTANCE.forget(bot.getUUID());
        ReplayRecorder.INSTANCE.clear(bot.getUUID());
        BotProfiler.INSTANCE.clear(bot.getUUID());
        MinecraftAiServerNetworking.INSTANCE.clearBot(bot.getUUID());
    }

    private static void clearWorldRuntime() {
        FOLLOW_AFTER_DEATH.clear();
        OWNER_DEATH_WATCH.clear();
        NavEngineSelector.hook("baritone_clearAll", () -> BaritoneRegistry.INSTANCE.clearAll());
        GoalExecutor.INSTANCE.clearAllRuntime();
        TaskManager.INSTANCE.clearAllRuntime();
        IdleCoordinator.INSTANCE.clearAllRuntime();
        TaskBoard.INSTANCE.clear();
        DangerWatcher.INSTANCE.clearAll();
        NavSafetyNet.INSTANCE.clearAll();
        NavEngineSelector.clearAllBotEngines();
        StuckWatcher.INSTANCE.clearAll();
        EpisodeMemory.INSTANCE.clearAll();
        EpisodeLog.INSTANCE.clearAll();
        BotMemoryStore.INSTANCE.clear();
        io.github.zoyluo.minecraftai.brain.ChatMemory.clearAll();
        BotRuntimeOptions.INSTANCE.clearAll();
        BotReporter.INSTANCE.clearAll();
        DiagnosticLogger.INSTANCE.clearAll();
        InventoryAudit.INSTANCE.clearAll();
        ReplayRecorder.INSTANCE.clearAll();
        BotProfiler.INSTANCE.clearAll();
        MinecraftAiServerNetworking.INSTANCE.clear();
        CapabilityRuntime.clearAll();
        CapabilityTally.INSTANCE.clearAll();
        MiningEvidenceAudit.clearAll();
        MiningAssistRuntime.clearWorldRuntime();
        PoiCoordinator.INSTANCE.clearAll();
        AmbientConversationCoordinator.INSTANCE.reset();
        TpsGuard.INSTANCE.reset();
        AStarPathfinder.invalidateCache("runtime_world_boundary");
    }
}

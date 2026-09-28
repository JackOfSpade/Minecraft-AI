package io.github.zoyluo.minecraftai.runtime;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
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
import io.github.zoyluo.minecraftai.task.NavSafetyNet;
import io.github.zoyluo.minecraftai.task.StuckWatcher;
import io.github.zoyluo.minecraftai.task.TaskManager;
import net.minecraft.server.MinecraftServer;

/** Single ordering authority for world start/stop and Bot reset/death/despawn cleanup. */
public final class RuntimeLifecycleCoordinator {
    public static final RuntimeLifecycleCoordinator INSTANCE = new RuntimeLifecycleCoordinator();

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
        BotLog.lifecycle("server_runtime_stopped", "persisted_bots", persisted);
        BotLogWriter.INSTANCE.shutdown(3000);
    }

    public void resetBot(AIPlayerEntity bot, IntentController.ControlOrigin origin, String reason) {
        IntentController.INSTANCE.cancelAll(bot, origin, reason);
        BrainCoordinator.INSTANCE.reset(bot);
        GoalExecutor.INSTANCE.unload(bot);
        TaskManager.INSTANCE.resetToIdle(bot);
        clearTransient(bot);
        BotLog.lifecycle(bot, "bot_runtime_reset", "reason", reason);
    }

    public void onBotDeath(AIPlayerEntity bot) {
        BrainCoordinator.INSTANCE.invalidateDecision(bot, "bot_death");
        BrainCoordinator.INSTANCE.clearIntentWakeSources(bot);
        // A death interrupts but does not erase a long-running Mission. GoalExecutor persists the
        // exact goal/task checkpoint while RecoverDropsTask owns the safety slot, then resumes it.
        GoalExecutor.INSTANCE.suspendForDeath(bot);
        IdleCoordinator.INSTANCE.cancelClaimedJob(bot, "bot_died");
        TaskManager.INSTANCE.cancelIntentTasks(bot, "bot_died");
        bot.getActionPack().stopAll();
        BrainCoordinator.INSTANCE.reset(bot);
        clearTransient(bot);
        BotLog.lifecycle(bot, "bot_runtime_death_reset");
    }

    /** Explicit despawn is deletion: publish cancellation first, then forget every cached projection.
     * {@code IntentController.cancelAll} above already cancels active/paused work (reaching
     * {@code TaskManager.cancelIntentTasks} via {@code cancelActiveAndPausedWork}), so cleanup here uses
     * {@code TaskManager.forgetDespawnedBot}, not {@code onBotDespawn}: the latter's own
     * {@code cancelIntentTasks} call would bump {@code userPauseEpoch} a second time for one despawn. */
    public void deleteBot(AIPlayerEntity bot) {
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
        EpisodeMemory.INSTANCE.reset(bot.getUuid());
        BotReporter.INSTANCE.onCleared(bot);
        DiagnosticLogger.INSTANCE.clear(bot);
        InventoryAudit.INSTANCE.clear(bot);
        CapabilityRuntime.clear(bot);
        CapabilityTally.INSTANCE.clear(bot.getUuid());
        MiningAssistRuntime.clearBotUnload(bot);
        // P3: the R4 in-flight-consult bookkeeping is per-bot state too, same restart-safety reasoning as
        // MiningAssistRuntime.clearBotUnload immediately above (see PoiCoordinator.clearBot's own javadoc).
        PoiCoordinator.INSTANCE.clearBot(bot.getUuid());
    }

    private static void forgetBot(AIPlayerEntity bot) {
        MiningEvidenceAudit.clear(bot);
        MiningAssistRuntime.clearForced(bot.getUuid());
        clearTransient(bot);
        BotRuntimeOptions.INSTANCE.clear(bot);
        BotMemoryStore.INSTANCE.remove(bot.getUuid());
        EpisodeLog.INSTANCE.clearFor(bot.getUuid());
        KnowledgeBase.INSTANCE.forget(bot.getUuid());
        ReplayRecorder.INSTANCE.clear(bot.getUuid());
        BotProfiler.INSTANCE.clear(bot.getUuid());
        MinecraftAiServerNetworking.INSTANCE.clearBot(bot.getUuid());
    }

    private static void clearWorldRuntime() {
        GoalExecutor.INSTANCE.clearAllRuntime();
        TaskManager.INSTANCE.clearAllRuntime();
        IdleCoordinator.INSTANCE.clearAllRuntime();
        TaskBoard.INSTANCE.clear();
        DangerWatcher.INSTANCE.clearAll();
        NavSafetyNet.INSTANCE.clearAll();
        StuckWatcher.INSTANCE.clearAll();
        EpisodeMemory.INSTANCE.clearAll();
        EpisodeLog.INSTANCE.clearAll();
        BotMemoryStore.INSTANCE.clear();
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

package io.github.zoyluo.minecraftai.entity;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Locks in post-death follow recovery and the owner-only companion recall escape hatch. */
final class CompanionRecoverySourceContractTest {
    @Test
    void interruptedFollowResumesAfterTheCompanionRevives() throws IOException {
        String lifecycle = read("runtime/RuntimeLifecycleCoordinator.java");
        String manager = read("manager/AIPlayerManager.java");

        int capture = lifecycle.indexOf("TaskManager.INSTANCE.activeOrPausedFollowIntent(bot)");
        int cancellation = lifecycle.indexOf("TaskManager.INSTANCE.cancelIntentTasks(bot, \"bot_died\")");
        assertTrue(capture >= 0 && capture < cancellation);
        assertTrue(lifecycle.contains("new FollowTask(intent.targetName())"));
        assertTrue(lifecycle.contains("follow_resumed_after_death"));
        assertTrue(manager.contains("RuntimeLifecycleCoordinator.INSTANCE.onBotRespawned(bot);"));
    }

    @Test
    void ownerRecallIsExposedUnderBothCommandSpellings() throws IOException {
        String command = read("command/MinecraftAiCommand.java");
        String manager = read("manager/AIPlayerManager.java");

        assertTrue(command.contains("registerRoot(dispatcher, \"minecraftai\")"));
        assertTrue(command.contains("registerRoot(dispatcher, \"minecraft-ai\")"));
        assertTrue(command.contains("literal(\"companions\")"));
        assertTrue(command.contains("literal(\"recall\")"));
        assertTrue(command.contains("recallOwnedCompanions(owner)"));
        assertTrue(command.contains("recallOwnedCompanion(owner, name)"));
        assertTrue(manager.contains("public int recallOwnedCompanions(ServerPlayer owner)"));
        assertTrue(manager.contains("public boolean recallOwnedCompanion(ServerPlayer owner, String companionName)"));
        assertTrue(manager.contains("needsRecoveryRespawn(bot)")
                        && manager.contains("safeCompanionSpawnPosition(world, owner")
                        && manager.contains("placement\", \"owner_adjacent\""),
                "recall must revive a dead/removed companion and put it in a visible adjacent cell, not inside its owner");
        assertTrue(manager.contains("companion_recalled_by_owner"));
    }

    @Test
    void ownerDeathKeepsSafetyCombatThenHoldsUntilANewDirection() throws IOException {
        String lifecycle = read("runtime/RuntimeLifecycleCoordinator.java");
        String danger = read("task/DangerWatcher.java");
        String tasks = read("task/TaskManager.java");
        String coordinator = read("task/BotTickCoordinator.java");
        String mod = read("MinecraftAiMod.java");

        assertTrue(lifecycle.contains("cancelIntentTasksKeepingActiveCombatOrSafety(bot, \"owner_died\")"));
        assertTrue(lifecycle.contains("companion_owner_death_watch"));
        assertTrue(danger.contains("awaitingOwnerReturn(bot)"));
        assertTrue(danger.contains("new HoldTask()"));
        assertTrue(tasks.contains("clearOwnerDeathWatch(bot, \"new_owner_direction\")"));
        assertTrue(coordinator.contains("boolean runDanger = awaitingOwnerReturn ||"));
        assertTrue(mod.contains("onOwnerDeath(player)"));
    }

    private static String read(String relative) throws IOException {
        return Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai").resolve(relative));
    }
}

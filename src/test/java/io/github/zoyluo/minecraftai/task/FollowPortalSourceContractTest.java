package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Keeps portal follow narrowly scoped to an owner who was observed entering a real vanilla travel portal. */
final class FollowPortalSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    @Test
    void crossDimensionFollowNeedsARecentObservedVanillaPortalAndAnOwner() throws IOException {
        String follow = Files.readString(MAIN.resolve("task/FollowTask.java"));
        int portalAttempt = follow.indexOf("followOwnerThroughPortal(bot, target)");
        int ordinaryCrossDimensionWait = follow.indexOf("if (target == null || target.level() != bot.level())");

        assertTrue(portalAttempt >= 0 && portalAttempt < ordinaryCrossDimensionWait,
                "the portal handoff must run before the ordinary cross-dimension wait");
        assertTrue(follow.contains("rememberPortalDeparture(target, bot.level())"));
        assertTrue(follow.contains("void observePortalDeparture(AIPlayerEntity bot)")
                        && follow.contains("!isVerifiedOwner(bot, target)")
                        && follow.contains("target.level() != bot.level()"),
                "the START_WORLD_TICK portal observer records only a verified owner while both players still share the source level");

        String taskManager = Files.readString(MAIN.resolve("task/TaskManager.java"));
        assertTrue(taskManager.contains("public void observePortalDepartures(ServerLevel level)")
                        && taskManager.contains("follow::observePortalDeparture"),
                "active follow tasks must be observed before player dimension transfer, not only at END_SERVER_TICK");
        String mod = Files.readString(MAIN.resolve("MinecraftAiMod.java"));
        assertTrue(mod.contains("ServerTickEvents.START_WORLD_TICK.register(level ->")
                        && mod.contains("TaskManager.INSTANCE.observePortalDepartures(level)"),
                "the portal observer must run in Fabric's pre-player world-tick phase");
        assertTrue(follow.contains("Blocks.NETHER_PORTAL") && follow.contains("Blocks.END_PORTAL"));
        assertTrue(follow.contains("source.dimension() == Level.OVERWORLD && destination.dimension() == Level.END")
                        && follow.contains("source.dimension() == Level.END && destination.dimension() == Level.OVERWORLD"),
                "an End handoff must be a real Overworld↔End transfer, never a same-dimension gateway jump");
        assertTrue(follow.contains("!isVerifiedOwner(bot, target)")
                        && follow.contains("elapsed - departure.tick() > 200"),
                "ordinary named follows, command teleports, logouts, and stale portal observations must still wait");
        assertTrue(follow.contains("AIPlayerManager.INSTANCE.followOwnerThroughPortal(bot, target)"));

        String manager = Files.readString(MAIN.resolve("manager/AIPlayerManager.java"));
        assertTrue(manager.contains("owner.getUUID().equals(botOwners.get(bot.getUUID()))"));
        assertTrue(manager.contains("companion_followed_owner_portal"));
        assertTrue(manager.contains("teleportCompanionBesideOwner(bot, owner)"),
                "the transfer must use the existing visible safe-arrival proof");
    }
}

package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks follow's known-player routing and cancelled-shelter exit ownership boundary. */
final class FollowRecoverySourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    @Test
    void landFollowUsesAuthorizedPlayerTrackingAndDoesNotResetItsFallbackEveryTick() throws IOException {
        String follow = read("task/FollowTask.java");

        assertTrue(follow.contains(
                "BlockPos standNear = standOffsetFrom(target.getBlockPos(), bot.getBlockPos(), STOP_DISTANCE)"),
                "the walk/path destination must stand off from the player, not target their own block");
        assertTrue(follow.contains("startPathTo(standNear)"));
        assertTrue(follow.contains("boolean walkIdle = bot.getActionPack().isWalkToIdle()"));
        assertTrue(follow.contains("if (!walkIdle)"));
        assertTrue(follow.contains("waiting = pathIdle && walkIdle;"),
                "an idle navigation cooldown must be a deliberate waiting/reacquire state");
        assertFalse(follow.contains("ObservableWorldQuery"),
                "following an explicitly selected owner/player must not become an entity scan");
    }

    @Test
    void cancelledShelterHandsOffOnlyItsOwnedDoorwayToFollow() throws IOException {
        String shelter = read("task/EmergencyShelterTask.java");
        String follow = read("task/FollowTask.java");

        int onAbort = shelter.indexOf("protected void onAbort(AIPlayerEntity bot)");
        int preserve = shelter.indexOf("preserveOwnedExitDebt(bot);", onAbort);
        int stop = shelter.indexOf("bot.getActionPack().stopAll();", preserve);
        assertTrue(onAbort >= 0 && preserve > onAbort && stop > preserve,
                "cancellation must snapshot the owned exit before its action state is discarded");
        assertTrue(shelter.contains("currentOwned.containsKey(candidate) && currentOwned.containsKey(candidate.up())"));
        assertTrue(shelter.contains("boolean matchesDimension(AIPlayerEntity bot)"));
        assertTrue(shelter.contains("owned.equals(bot.getEntityWorld().getBlockState(position))"));
        assertTrue(follow.contains("EmergencyShelterTask.pendingExitDebt(bot).orElse(null)"));
        assertTrue(follow.contains("!shelterExitDebt.matchesDimension(bot)"));
        assertTrue(follow.contains("shelterExitDebt.ownsCurrentPlacement(bot, obstruction)"));
        assertTrue(follow.contains("shelterExitMiner.begin(bot, obstruction)"));
        assertTrue(follow.contains("FakePlayerMotion.stepToStandable(bot, egress, \"follow_shelter_exit\")"));
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}

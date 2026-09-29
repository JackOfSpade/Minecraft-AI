package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Argument-validation invariants for GiveItemTask's constructor (count clamping, player-name
 * normalization/defaulting to owner). GiveItemTask's constructor touches Item/Registries constants
 * the same way ToolRegistry.registerDefaults() does, which needs a Minecraft bootstrap a plain
 * JUnit test does not have (see ToolRegistryAssignTaskNullParamsTest) -- this pins the invariants
 * as a source-text contract instead, the same way MiningFoodReserveTest's whitelist check and
 * AcquireWaterSurvivalBoundaryTest do for the same reason.
 */
final class GiveItemTaskConstructionTest {
    @Test
    void countIsClampedToAtLeastOne() throws IOException {
        String source = readSource();
        assertTrue(source.contains("this.count = Math.max(1, count);"),
                "expected a non-positive count to be clamped to 1, not accepted or silently dropped to 0");
    }

    @Test
    void playerNameIsNormalizedThroughTheSameResolverFollowUses() throws IOException {
        String source = readSource();
        assertTrue(source.contains("this.requestedPlayerName = FollowTargetResolver.normalize(playerName);"),
                "expected player_name to go through FollowTargetResolver.normalize, the same "
                        + "null/blank-safe normalization FollowTask uses");
    }

    @Test
    void targetResolutionDefaultsToTheBotsOwnerViaFollowTargetResolver() throws IOException {
        String source = readSource();
        assertTrue(source.contains("FollowTargetResolver.resolve(bot, requestedPlayerName)"),
                "expected target resolution to reuse FollowTargetResolver.resolve, which falls "
                        + "back to the bot's owner when no name was given (the same default follow uses)");
    }

    @Test
    void describeReportsOwnerWhenPlayerNameIsBlank() throws IOException {
        String source = readSource();
        assertTrue(source.contains("requestedPlayerName.isBlank() ? \" to owner\""),
                "expected describe() to say \"to owner\" for a blank/defaulted player_name");
    }

    @Test
    void giveVerifiesTheExactRequestedCountLeftInventoryBeforeCompleting() throws IOException {
        String source = readSource();
        assertTrue(source.contains("before - after != count"),
                "expected give() to verify the inventory decreased by exactly the requested count "
                        + "before reporting success, never trusting dropItems' return value alone");
    }

    private static String readSource() throws IOException {
        return Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/GiveItemTask.java"));
    }
}

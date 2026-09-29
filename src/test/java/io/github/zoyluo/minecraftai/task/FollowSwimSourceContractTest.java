package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the safety-relevant structure of swim / dive / dig-out following: who owns the water when,
 * that expensive scans stay throttled, and that the dig-out stays inside the legal dig envelope.
 */
final class FollowSwimSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    @Test
    void followYieldsToTheDrowningRescueBelowItsThresholdAndNeverMovesThen() throws IOException {
        String swim = read("task/FollowSwimming.java");
        String oxygen = read("task/FollowOxygen.java");

        assertTrue(oxygen.contains("RESCUE_AIR = NavSafetyNet.AIR_SURFACE_THRESHOLD"),
                "follow's yield level must be the safety net's own threshold, not a copy");
        assertTrue(oxygen.contains("SURFACE_FLOOR_AIR = RESCUE_AIR +"),
                "follow must always start its ascent before the rescue has to");
        int yield = swim.indexOf("air <= FollowOxygen.RESCUE_AIR");
        int renew = swim.indexOf("NavSafetyNet.INSTANCE.renewFollowSwim(bot)");
        assertTrue(yield >= 0 && renew > yield,
                "the yield-without-moving check must run before the lease is renewed");
        String yieldBlock = swim.substring(yield, renew);
        assertTrue(yieldBlock.contains("clearFollowSwim(bot)") && yieldBlock.contains("stopMovement()"));
        assertFalse(yieldBlock.contains("swimStepTo") || yieldBlock.contains("stepAlongRoute")
                || yieldBlock.contains("ascendStep"),
                "the yield block must not move the bot");
    }

    @Test
    void oxygenDecisionUsesTheMeasuredLossAndEffectsRatherThanHardCodedPotions() throws IOException {
        String swim = read("task/FollowSwimming.java");
        assertTrue(swim.contains("loss.observe(bot.getAir(), bot.isSubmergedInWater()"));
        assertTrue(swim.contains("FollowOxygen.shouldSurface(air, rate, blocksToAir)"));
        assertTrue(swim.contains("FollowOxygen.mayResumeDive("));
        assertTrue(swim.contains("StatusEffects.WATER_BREATHING") && swim.contains("StatusEffects.CONDUIT_POWER"));
    }

    @Test
    void waterEdgeAndRouteScansAreThrottledAndReused() throws IOException {
        String swim = read("task/FollowSwimming.java");
        assertTrue(swim.contains("ENTRY_SCAN_COOLDOWN_TICKS = 20"));
        assertTrue(swim.contains("if (elapsed < nextEntryScanTick)"),
                "a failed water-edge scan must not be repeated every tick");
        assertTrue(swim.contains("&& BoatSupport.isWater(world, entry.water())")
                        && swim.contains("NavSafetyNet.isDryStandableCell(world, entry.shore())"),
                "the last edge is reused only while it is still valid");
        assertTrue(swim.contains("if (elapsed < nextRouteSearchTick)"),
                "water route searches are throttled too");
        assertTrue(swim.contains("BoatSupport.canObserveWater(bot,"),
                "an edge must be observable before the bot walks to it");
    }

    @Test
    void swimmerFollowNeverTouchesBoatsAndFollowTaskDelegatesToIt() throws IOException {
        String follow = read("task/FollowTask.java");
        assertTrue(follow.contains("swimming.follow(bot, target, elapsed, SWIM_STOP_DISTANCE)"));
        assertTrue(follow.contains("swimming.exitWaterForLand(bot, target, elapsed, STOP_DISTANCE)"));
        assertFalse(follow.contains("nextSwimRepathTick"), "the old launch-site swim entry is gone");
    }

    @Test
    void digOutIsTheLastRecoveryStepAndStaysInsideTheLegalDigEnvelope() throws IOException {
        String recovery = read("task/FollowStuckRecovery.java");
        String dig = read("task/FollowDigOut.java");

        int adjacent = recovery.indexOf("\"follow_recovery_step\"");
        int start = recovery.indexOf("digOut.start(bot, target)");
        assertTrue(adjacent >= 0 && start > adjacent,
                "dig-out must only be tried after every adjacent verified step failed");

        assertTrue(dig.contains("NeighborEnumerator.isMineable(world, cell)"),
                "only the pathfinder's natural dig whitelist may be broken");
        assertTrue(dig.contains("ObservableWorldQuery.canObserveBlock(bot, cell)"),
                "only currently observable blocks may be broken");
        assertTrue(dig.contains("DigNav.adjacentHazardFluid("), "never through or beside an observed fluid");
        assertTrue(dig.contains("isSolidFloor(world, stand.down())"),
                "never dig into a cell that would leave the bot over an unknown drop");
        assertTrue(dig.contains("instanceof FallingBlock"), "never under a suspended sand/gravel column");
        assertTrue(dig.contains("hasSuitableTool"), "only with a tool that can actually break the block");
        assertTrue(dig.contains("MAX_CELLS = 8"), "the tunnel is bounded");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}

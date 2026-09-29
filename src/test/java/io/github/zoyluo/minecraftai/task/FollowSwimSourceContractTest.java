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

    @Test
    void landBoundExitOnlyTakesOverForRealSwimmingAndHonoursTheAirFloor() throws IOException {
        String swim = read("task/FollowSwimming.java");
        int exit = swim.indexOf("boolean exitWaterForLand(");
        int end = swim.indexOf("// ---- entering the water");
        assertTrue(exit >= 0 && end > exit);
        String body = swim.substring(exit, end);
        assertTrue(body.contains("needsWaterExit(bot, target, standoff)"),
                "wading through shallows must stay with land follow, not run the swim exit");
        assertFalse(body.contains("if (!isSwimCell(world, bot.getBlockPos()))"),
                "merely having wet feet is not swimming");
        int gate = body.indexOf("needsWaterExit(");
        int search = body.indexOf("searchRoute(");
        assertTrue(gate >= 0 && search > gate, "no water route search before the swimming gate");
        assertTrue(body.contains("updateAscending(bot, air, lossRate(bot), true, blocksToAir)")
                        && body.contains("ascendWhileSubmerged("),
                "a submerged bot heading for land must still surface for air early");
        assertTrue(swim.contains("static boolean isSwimming(AIPlayerEntity bot)")
                        && swim.contains("bot.isSubmergedInWater()"),
                "swimming = head under water or afloat with nothing solid underfoot");
    }

    @Test
    void modeSwitchesAndPauseAbortResetLandRecoveryAndCancelTheDigOut() throws IOException {
        String follow = read("task/FollowTask.java");
        assertTrue(follow.contains("private void suspendLandRecovery(AIPlayerEntity bot)")
                && follow.contains("stuckRecovery.reset(bot, elapsed);"));
        for (String owner : new String[]{"protected void onPause(", "protected void onAbort(", "protected void onResume("}) {
            int at = follow.indexOf(owner);
            assertTrue(at >= 0, owner);
            int next = follow.indexOf("@Override", at);
            String body = follow.substring(at, next < 0 ? follow.length() : next);
            assertTrue(body.contains("suspendLandRecovery(bot)"), owner + " must reset land recovery / cancel the dig-out");
        }
        int tick = follow.indexOf("protected void onTick(");
        String onTick = follow.substring(tick, follow.indexOf("private static void faceTarget"));
        assertTrue(count(onTick, "suspendLandRecovery(bot)") >= 5,
                "offline, boat, swim, leave-boat and exit-water ticks must each suspend land recovery");
        String recovery = read("task/FollowStuckRecovery.java");
        assertTrue(recovery.contains("digOut.cancel(bot);"), "reset must cancel an active dig-out");
    }

    @Test
    void digOutNeverBreaksBuildingBlocksOrBlocksTheBotsPlaced() throws IOException {
        String dig = read("task/FollowDigOut.java");
        assertTrue(dig.contains("isBuildingBlock(state)") && dig.contains("BotEdits.wasPlaced(world, cell)"),
                "building blocks and the bots' own placements are never dug");
        assertTrue(dig.contains("state.isOf(Blocks.COBBLESTONE)") && dig.contains("BlockTags.PLANKS")
                && dig.contains("BlockTags.DOORS"));
        assertFalse(dig.contains("never doors, chests, beds, glass, planks or any other block entity / built block"),
                "the header must not claim it can tell built blocks from natural ones");
        assertTrue(dig.contains("Limits (not a guarantee)"));
        String tests = readGametest("task/FollowSwimGameTests.java");
        assertFalse(tests.contains("NeverPlayerBuiltWalls"), "the test name must not overclaim");
    }

    @Test
    void aSuppressedPhysicalSnapDoesNotEscalateToTheEmergencyTeleport() throws IOException {
        String pack = read("action/ActionPack.java");
        int suppressed = pack.indexOf("if (physicalSnapSuppressed(current, reason))");
        int physical = pack.indexOf("if (tryPhysicalSnap(world, current, reason))");
        int teleport = pack.indexOf("PrivilegedCapability.EMERGENCY_TELEPORT,\n                \"action_pack_snap:");
        assertTrue(suppressed >= 0 && physical > suppressed && teleport > physical);
        String between = pack.substring(suppressed, physical);
        assertTrue(between.contains("return false;"), "a suppressed snap must return, not fall through to the teleport");
    }

    @Test
    void followRechecksAHeldOwnCellGoalAsSoonAsThePlayerMoves() throws IOException {
        String follow = read("task/FollowTask.java");
        assertTrue(follow.contains("holdTargetPos = target.getBlockPos().toImmutable();"));
        assertTrue(follow.contains("!holdTargetPos.equals(target.getBlockPos())")
                && follow.contains("nextRepathTick = elapsed;"));
    }

    @Test
    void combatIsLoggedAtHitDeathAndTaskLevel() throws IOException {
        String entity = read("entity/AIPlayerEntity.java");
        assertTrue(entity.contains("\"damage_taken\"") && entity.contains("\"bot_death\""),
                "damage taken and death (source, attacker) must be logged");
        String combat = read("task/CombatTask.java");
        assertTrue(combat.contains("\"combat_target\"") && combat.contains("\"combat_phase\"")
                && combat.contains("\"combat_kill\""));
        String interact = read("action/InteractAction.java");
        assertTrue(interact.contains("\"target_hp\""));
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }

    private static String readGametest(String relative) throws IOException {
        return Files.readString(Path.of("src/gametest/java/io/github/zoyluo/minecraftai").resolve(relative));
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}

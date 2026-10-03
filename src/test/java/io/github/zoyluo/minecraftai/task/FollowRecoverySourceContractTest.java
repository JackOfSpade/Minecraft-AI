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
    void landFollowUsesBaritoneAndDoesNotRetryEveryTick() throws IOException {
        String follow = read("task/FollowTask.java");

        assertTrue(follow.contains("private boolean followLandBaritone(AIPlayerEntity bot, ServerPlayer target)"));
        assertTrue(follow.contains("isVerifiedOwner(bot, target)")
                        && follow.contains("pack.startOwnerFollowTo(target.getUUID(), targetPos, baritoneRadius, refresh)")
                        && follow.contains("new LandRouteAttempt(ownerRoute, false, true)"),
                "the verified owner uses the dedicated live-coordinate route before ordinary observed routing");
        assertTrue(follow.contains("pack.startApproachTo(targetPos, baritoneRadius, refresh, true)"),
                "a named/non-owner player remains inside the ordinary observed GoalNear boundary");
        assertTrue(follow.contains("pack.startDirectionalPursuitTo(standoff, DIRECTIONAL_PURSUIT_MAX_HOP, refresh, true)"),
                "an out-of-view named/non-owner player receives a bounded observed Baritone hop, not a local navigator");
        assertTrue(follow.contains("standOffsetFrom(targetPos, bot.blockPosition(), STOP_DISTANCE)"),
                "the remote pursuit heading stops at the normal personal-space offset");
        assertTrue(follow.contains("baritoneTargetPos.distSqr(targetPos) >= BARITONE_REGOAL_MOVED_SQ"),
                "re-goal comparison keeps the actual player position rather than the standoff heading");
        assertTrue(follow.contains("if (repathBackoff && elapsed < nextRepathTick)"),
                "a failed admission becomes an explicit hold/backoff rather than an every-tick retry");
        assertFalse(follow.contains("ObservableWorldQuery"),
                "following an explicitly selected owner/player must not become an entity scan");
    }

    @Test
    void landFollowNeverFightsItsOwnNavigationOrFallsBackToDirectWalking() throws IOException {
        String follow = read("task/FollowTask.java");
        String pack = read("action/ActionPack.java");

        // Yaw belongs to the steering controller: only pitch follows the player while navigating.
        assertTrue(follow.contains("LookAction.lookPitchAt(bot, target.position()"),
                "while a path/walk is steering, only the pitch may follow the player");
        int steeringBranch = follow.indexOf("if (steering)");
        int fullLook = follow.indexOf("CombatCore.lookAt(bot, target)");
        assertTrue(steeringBranch >= 0 && fullLook > steeringBranch
                        && fullLook == follow.lastIndexOf("CombatCore.lookAt(bot, target)"),
                "the full body-yaw look-at is only allowed when nothing is navigating");
        // Arrival must cancel the Baritone route, not merely release the keys.
        int arrival = follow.indexOf("bot.distanceTo(target) <= STOP_DISTANCE + STOP_ARRIVAL_SLACK");
        int arrivalStop = follow.indexOf("pack.stopNavigation();", arrival);
        assertTrue(arrival >= 0 && arrivalStop > arrival && arrivalStop < follow.indexOf("waiting = true;", arrival),
                "the arrived branch must cancel the active route before holding position");
        assertTrue(pack.contains("public void stopNavigation()")
                        && pack.contains("cancelBaritoneRoute(\"stop_navigation\")"),
                "stopNavigation must end the Baritone route");
        assertFalse(follow.contains("FollowDirectWalk.verify(") && !follow.contains("follow_direct_walk"),
                "dry-land follow has no direct-walk fallback behind Baritone");
        assertTrue(follow.contains("private static final double BOAT_SHORE_STANDOFF = 2.0D;")
                        && follow.contains("BOAT_SHORE_STANDOFF, BOAT_TURN_ONLY_ANGLE"),
                "the boat shore approach keeps its own standoff instead of the land STOP_DISTANCE");
        assertTrue(follow.contains("BoatLaunchTask.FAIL_NEED_BOAT_OR_PLANKS"),
                "the swim-vs-walk decision shares the launch task's failure-reason constant");
    }

    @Test
    void landFollowKeepsTheWiderStopDistanceAndNeverAbortsAStandingOrder() throws IOException {
        String follow = read("task/FollowTask.java");
        String tools = read("brain/ToolRegistry.java");

        assertTrue(follow.contains("private static final double STOP_DISTANCE = 3.0D;"));
        assertTrue(follow.contains("bot.distanceTo(target) <= STOP_DISTANCE + STOP_ARRIVAL_SLACK"),
                "arrival threshold must keep the one-sided slack");
        assertTrue(follow.contains("baritoneProgress.stalled(elapsed, bot.distanceTo(target), bot.getX(), bot.getZ())"),
                "follow owns a Baritone no-progress recovery instead of handing off to another navigator");
        assertTrue(follow.contains("stuckRecovery.tick(bot, target, elapsed, STOP_DISTANCE)")
                        && follow.contains("stuckRecovery.consumeForcedRepath()"),
                "a physically stopped follower retains its standing order and gets bounded recovery/replans");
        assertTrue(follow.contains("(directional || ownerFollow) && NavRouteRules.PATH_INCOMPLETE.equals(ended.reason())")
                        && follow.contains("repathBackoff = false;"),
                "a bounded directional leg or loaded-chunk owner segment immediately acquires its next route");
        assertTrue(follow.contains("private static final double SWIM_STOP_DISTANCE = 3.5D;"),
                "swim distance is unchanged by the land stop-distance change");
        assertFalse(follow.contains("startSurfacePathTo"),
                "follow must not run a second surface-first search on every repath");
        assertTrue(tools.contains("keeping roughly 3-5 blocks of distance"));
    }

    @Test
    void cancelledShelterHandsOffOnlyItsOwnedDoorwayToFollow() throws IOException {
        String shelter = read("task/EmergencyShelterTask.java");
        String follow = read("task/FollowTask.java");
        String repayer = read("task/ShelterExitDebtRepayer.java");

        int onAbort = shelter.indexOf("protected void onAbort(AIPlayerEntity bot)");
        int settle = shelter.indexOf("private void settleTerminalOwnership(AIPlayerEntity bot, String cancelReason)");
        int preserve = shelter.indexOf("preserveOwnedExitDebt(bot);", settle);
        int stop = shelter.indexOf("bot.getActionPack().stopAll();", preserve);
        assertTrue(onAbort >= 0 && settle >= 0 && preserve > settle && stop > preserve
                        && shelter.indexOf("settleTerminalOwnership(bot, \"shelter_aborted\");", onAbort) > onAbort,
                "cancellation must reuse the terminal ownership handoff before action state is discarded");
        assertTrue(shelter.contains("currentOwned.containsKey(candidate) && currentOwned.containsKey(candidate.above())"));
        assertTrue(shelter.contains("boolean matchesDimension(AIPlayerEntity bot)"));
        assertTrue(shelter.contains("owned.equals(bot.level().getBlockState(position))"));
        // FollowTask delegates its shelter-exit-debt mini state machine to a dedicated
        // collaborator (a mechanical extraction, same behaviour); the invariants below now live
        // in that collaborator rather than in FollowTask itself.
        assertTrue(follow.contains("shelterExitDebtRepayer.repay(bot, target, elapsed)"));
        assertTrue(repayer.contains("EmergencyShelterTask.pendingExitDebt(bot).orElse(null)"));
        assertTrue(repayer.contains("!shelterExitDebt.matchesDimension(bot)"));
        assertTrue(repayer.contains("shelterExitDebt.ownsCurrentPlacement(bot, obstruction)"));
        assertTrue(repayer.contains("shelterExitMiner.begin(bot, obstruction)"));
        assertTrue(repayer.contains("WalkedStep.begin(bot, egress, kind, \"follow_shelter_exit\")"),
                "the doorway is walked through with real inputs");
        assertFalse(repayer.contains("FakePlayerMotion"), "the shelter exit never teleports the bot");
        assertTrue(read("task/FollowStuckRecovery.java").contains("WalkedStep.begin(bot, best, kind, \"follow_recovery_step\")")
                        && !read("task/FollowStuckRecovery.java").contains("FakePlayerMotion"),
                "the stuck-recovery step is a walked step, not a teleport");
        assertTrue(read("task/FollowDigOut.java").contains("WalkedStep.begin(bot, ahead, kind, \"follow_dig_step\")")
                        && !read("task/FollowDigOut.java").contains("FakePlayerMotion.step"),
                "the dig-out walks into each opened cell");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}

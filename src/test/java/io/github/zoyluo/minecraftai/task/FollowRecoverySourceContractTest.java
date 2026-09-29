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
        assertTrue(follow.contains("boolean walkIdle = pack.isWalkToIdle()"));
        assertTrue(follow.contains("if (!walkIdle)"));
        assertTrue(follow.contains("waiting = pathIdle && walkIdle;"),
                "an idle navigation cooldown must be a deliberate waiting/reacquire state");
        assertFalse(follow.contains("ObservableWorldQuery"),
                "following an explicitly selected owner/player must not become an entity scan");
    }

    @Test
    void landFollowNeverFightsItsOwnNavigationOrWalksUnverifiedSegments() throws IOException {
        String follow = read("task/FollowTask.java");
        String pack = read("action/ActionPack.java");

        // Yaw belongs to the steering controller: only pitch follows the player while navigating.
        assertTrue(follow.contains("LookAction.lookPitchAt(bot, target.getEntityPos()"),
                "while a path/walk is steering, only the pitch may follow the player");
        int steeringBranch = follow.indexOf("if (steering)");
        int fullLook = follow.indexOf("CombatCore.lookAt(bot, target)");
        assertTrue(steeringBranch >= 0 && fullLook > steeringBranch
                        && fullLook == follow.lastIndexOf("CombatCore.lookAt(bot, target)"),
                "the full body-yaw look-at is only allowed when nothing is navigating");
        // Arrival must cancel the executor, not merely release the keys.
        int arrival = follow.indexOf("distance <= STOP_DISTANCE + STOP_ARRIVAL_SLACK");
        int arrivalStop = follow.indexOf("pack.stopNavigation();", arrival);
        assertTrue(arrival >= 0 && arrivalStop > arrival && arrivalStop < follow.indexOf("waiting = true;", arrival),
                "the arrived branch must cancel the path executor and the walk");
        assertTrue(pack.contains("public void stopNavigation()")
                        && pack.contains("clearActivePathExecutor();\n        this.walkTo = null;"),
                "stopNavigation must really stop the executor and the walk");
        // A throttled request is "keep the current plan", never a failed route.
        assertTrue(follow.contains("ActionPack.PATHFINDING_THROTTLED.equals(path.reason())"));
        // The straight-line fallback is verified along its whole length and logged.
        int direct = follow.indexOf("pack.startWalkTo(standNear.toCenterPos())");
        int verify = follow.indexOf("FollowDirectWalk.verify(");
        assertTrue(verify >= 0 && direct > verify && follow.contains("\"follow_direct_walk\""),
                "startWalkTo may only follow a verified segment");
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
        assertTrue(follow.contains("distance <= STOP_DISTANCE + STOP_ARRIVAL_SLACK"),
                "arrival threshold must keep the one-sided slack");
        assertTrue(follow.contains("stuckRecovery.tick(bot, target, elapsed, STOP_DISTANCE)"),
                "follow must own its stall recovery instead of letting StuckWatcher abort it");
        assertTrue(follow.contains("stuckRecovery.consumeForcedRepath()"));
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
        int preserve = shelter.indexOf("preserveOwnedExitDebt(bot);", onAbort);
        int stop = shelter.indexOf("bot.getActionPack().stopAll();", preserve);
        assertTrue(onAbort >= 0 && preserve > onAbort && stop > preserve,
                "cancellation must snapshot the owned exit before its action state is discarded");
        assertTrue(shelter.contains("currentOwned.containsKey(candidate) && currentOwned.containsKey(candidate.up())"));
        assertTrue(shelter.contains("boolean matchesDimension(AIPlayerEntity bot)"));
        assertTrue(shelter.contains("owned.equals(bot.getEntityWorld().getBlockState(position))"));
        // FollowTask delegates its shelter-exit-debt mini state machine to a dedicated
        // collaborator (a mechanical extraction, same behaviour); the invariants below now live
        // in that collaborator rather than in FollowTask itself.
        assertTrue(follow.contains("shelterExitDebtRepayer.repay(bot, target, elapsed)"));
        assertTrue(repayer.contains("EmergencyShelterTask.pendingExitDebt(bot).orElse(null)"));
        assertTrue(repayer.contains("!shelterExitDebt.matchesDimension(bot)"));
        assertTrue(repayer.contains("shelterExitDebt.ownsCurrentPlacement(bot, obstruction)"));
        assertTrue(repayer.contains("shelterExitMiner.begin(bot, obstruction)"));
        assertTrue(repayer.contains("FakePlayerMotion.stepToStandable(bot, egress, \"follow_shelter_exit\")"));
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}

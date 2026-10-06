package io.github.zoyluo.minecraftai.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the physical, non-destructive demonstration contract for a known target. */
final class ShowTargetTaskSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    @Test
    void guidanceUsesObservedNavigationWithAPlayerRendezvousAndObservedTargetLeg() throws IOException {
        String task = read("task/ShowTargetTask.java");

        assertTrue(task.contains("OWNER_RADIUS = 5")
                        && task.contains("TARGET_RADIUS = 3")
                        && task.contains("HOP_DISTANCE = 12"),
                "the demonstration must preserve the requested five-block owner and three-block target distances");
        assertTrue(task.contains("startOwnerFollowTo(owner.getUUID(), owner.blockPosition(), OWNER_RADIUS, false)"),
                "the first leg must use the verified owner's live coordinate, including out of view");
        assertTrue(task.contains("ObservableWorldQuery.canObserveCell(bot, target)")
                        && task.contains("startApproachTo(target, TARGET_RADIUS, false, false)")
                        && task.contains("startDirectionalPursuitTo(target, HOP_DISTANCE, false, false)"),
                "the target must be visibly re-proven before GoalNear(3), with only observed hops while it is out of view");
        assertTrue(task.contains("observedSurfaceWaterAtTargetColumn(bot)")
                        && task.contains("driveSurfacePresentation(bot, presentation, now)")
                        && task.contains("startSurfacePresentationStep(bot, surfaceWater)")
                        && task.contains("withinSurfacePresentation(bot, presentation)")
                        && task.contains("observed_surface_swim_no_dive")
                        && task.contains("canObserveCellThroughFluids(bot, water)")
                        && task.contains("WalkedStep.Kind.SWIM")
                        && task.contains("WATER_WITH_AIR_ABOVE")
                        && task.contains("SurfaceStepAdmission")
                        && task.contains("feet.getY() < admission.surfaceY()")
                        && task.contains("withinSurfaceStepContinuationEnvelope")
                        && task.contains("isSafeSurfaceStance")
                        && task.contains("renewObservedSurfacePresentationWater")
                        && task.contains("maintainSurfaceHold")
                        && task.contains("beginObservedSurfaceEntry"),
                "a known target below visible water must use observed, top-water strokes, never a GoalNear dive");
        assertTrue(task.contains("It's below here.") && task.contains("surfacePresentation != null"),
                "the completed surface presentation must explain that the known target is below the safe water stance");
        assertTrue(task.contains("requestRoutePace(Gait.SPRINT, PaceOwner.TASK)")
                        && task.contains("requestSprintRoute(bot);"),
                "each successful Baritone leg must request the task's sprint pace without bypassing Baritone safety");
        int reachTarget = task.indexOf("phase = Phase.REACH_TARGET;");
        int nextLeg = task.indexOf("reachTarget(bot, now);", reachTarget);
        assertTrue(reachTarget >= 0 && nextLeg > reachTarget,
                "the target leg must begin immediately after the owner rendezvous");
        assertFalse(task.contains("startWalkTo(") || task.contains("startSurfacePathTo(")
                        || task.contains("FakePlayerMotion") || task.contains("MiningAction")
                        || task.contains("InteractAction") || task.contains(".attack("),
                "showing a target must not fall back to direct walking, teleportation, mining, interacting, or attacking");
    }

    @Test
    void pointingIsFiveSeparatedAnimationOnlySwings() throws IOException {
        String task = read("task/ShowTargetTask.java");

        assertTrue(task.contains("SWING_COUNT = 5")
                        && task.contains("SWING_INTERVAL_TICKS = 4")
                        && task.contains("if (now < nextSwingTick || bot.isUsingItem())"),
                "air punches must be exactly five distinct, safely scheduled taps");
        assertTrue(task.contains("LookAction.lookAt(bot, Vec3.atCenterOf(target))")
                        && task.contains("bot.swing(InteractionHand.MAIN_HAND);")
                        && task.contains("swings++")
                        && task.contains("nextSwingTick = now + SWING_INTERVAL_TICKS")
                        && task.contains("if (swings >= SWING_COUNT)"),
                "the bot must face the target and use only five visual main-hand swings");
        assertFalse(task.contains("setAttack") || task.contains("player.attack")
                        || task.contains("MiningAction") || task.contains("InteractAction")
                        || task.contains("useItem"),
                "the gestures must never hold an attack/use input or change the target");
    }

    @Test
    void explicitKnownCoordinatesWorkIndependentlyAndFindRemainsTheSafeShowMeDefault() throws IOException {
        String discovery = read("task/DiscoveryTask.java");
        String tools = read("brain/ToolRegistry.java");
        String brain = read("brain/BrainCoordinator.java");

        int question = discovery.indexOf("Would you like me to show you where it is?");
        int completedAfterQuestion = discovery.indexOf("complete();", question);
        assertTrue(discovery.contains("LAST_FOUND_PLACE = \"last_found\"")
                        && discovery.contains("public static Optional<FoundTarget> latestFound")
                        && discovery.contains("markPlace(LAST_FOUND_PLACE, bot.level(), found)")
                        && question >= 0
                        && completedAfterQuestion > question
                        && !discovery.contains("new ShowTargetTask"),
                "a real visible discovery may become the default target, but a plain find must ask and complete without starting a demonstration");
        assertTrue(tools.contains("register(\"show_location\"")
                        && tools.contains("showLocationTask(bot, args)")
                        && tools.contains("DiscoveryTask.FoundTarget.forShowing(DiscoveryTask.latestFound(bot)")
                        && tools.contains("return new ShowTargetTask(shown.pos(), shown.label())")
                        && tools.contains("case \"show_location\" -> showLocationTask(bot, params)"),
                "the LLM needs a first-class show-location tool plus the high-level task alias");
        assertTrue(brain.contains("\"find\", \"show_location\", \"follow\"")
                        && brain.contains("For \"show me\", \"take me to it\"")
                        && brain.contains("examples, not a fixed keyword list")
                        && brain.contains("It does not depend on find")
                        && brain.contains("Never call show_location merely because find succeeded")
                        && brain.contains("A plain find is locate-only")
                        && brain.contains("automatic task-finished wake")
                        && brain.contains("wait for a new affirmative player message")
                        && brain.contains("original request is advance authorization")
                        && brain.contains("Never invent a coordinate"),
                "the action gate and planner guidance must allow any verified coordinate, not just a find result");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}

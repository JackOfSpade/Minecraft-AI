package io.github.zoyluo.minecraftai.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.zoyluo.minecraftai.entity.TeleportAudit.Frame;
import io.github.zoyluo.minecraftai.entity.TeleportAudit.Kind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The pure part of {@link TeleportAudit}: which kind a teleport is, from the stack frames alone. */
final class TeleportAuditClassifierTest {
    private static final String P = "io.github.zoyluo.minecraftai.";

    private static Frame f(String className, String method) {
        return new Frame(className, method);
    }

    /** The plumbing every teleport starts with: the entity override, the audit itself and vanilla frames above the caller. */
    private static List<Frame> stack(Frame... callerAndBelow) {
        List<Frame> frames = new ArrayList<>();
        frames.add(f(P + "entity.TeleportAudit", "record"));
        frames.add(f(P + "entity.AIPlayerEntity", "teleportTo"));
        frames.addAll(List.of(callerAndBelow));
        frames.add(f("net.minecraft.server.MinecraftServer", "tickServer"));
        frames.add(f("java.lang.Thread", "run"));
        return frames;
    }

    @Test
    void aManagerFrameIsALifecycleMove() {
        assertEquals(Kind.LIFECYCLE, TeleportAudit.classify(stack(f(P + "manager.AIPlayerManager", "spawnInternal")), false));
        assertEquals(Kind.LIFECYCLE, TeleportAudit.classify(stack(f(P + "manager.AIPlayerManager", "respawnDeadBot")), false));
    }

    @Test
    void theNetworkingFrameIsAUserMove() {
        assertEquals(Kind.USER, TeleportAudit.classify(
                stack(f(P + "network.MinecraftAiServerNetworking", "lambda$handleTeleport$3")), false));
    }

    @Test
    void noModFrameIsVanilla() {
        assertEquals(Kind.VANILLA, TeleportAudit.classify(List.of(
                f(P + "entity.TeleportAudit", "record"),
                f(P + "entity.AIPlayerEntity", "teleport"),
                f("net.minecraft.world.entity.Entity", "teleport"),
                f("net.minecraft.world.level.block.EndPortalBlock", "getPortalDestination"),
                f("java.lang.Thread", "run")), false));
        assertEquals(Kind.VANILLA, TeleportAudit.classify(List.of(), false));
    }

    @Test
    void foreignModsAndLibrariesAreNotTheCaller() {
        assertEquals(Kind.VANILLA, TeleportAudit.classify(List.of(
                f(P + "entity.AIPlayerEntity", "teleportTo"),
                f("net.fabricmc.fabric.api.Something", "run"),
                f("com.example.pvpbot.PvpBot", "respawn"),
                f("java.util.ArrayList", "forEach")), false));
    }

    @Test
    void mixinFramesAreSkipped() {
        // The mixin is plumbing: the first real caller below it decides.
        assertEquals(Kind.LIFECYCLE, TeleportAudit.classify(List.of(
                f(P + "entity.AIPlayerEntity", "teleportTo"),
                f(P + "mixin.ServerPlayerMixin", "redirect$teleport"),
                f(P + "manager.AIPlayerManager", "respawnDeadBot")), false));
        assertEquals(Kind.VANILLA, TeleportAudit.classify(List.of(
                f(P + "entity.AIPlayerEntity", "teleportTo"),
                f(P + "mixin.EntityMixin", "onTeleport"),
                f("net.minecraft.world.entity.Entity", "teleport")), false));
    }

    @Test
    void theEntityAndAuditFramesAreSkippedEvenWhenTheyRepeat() {
        assertEquals(Kind.CORRECTION, TeleportAudit.classify(List.of(
                f(P + "entity.TeleportAudit", "record"),
                f(P + "entity.AIPlayerEntity", "teleportTo"),
                f(P + "entity.AIPlayerEntity", "teleport"),
                f(P + "mode.FakePlayerMotion", "stepTo")), false));
    }

    @Test
    void thePrivilegedEmergencyRescuesAreRecognised() {
        assertEquals(Kind.PRIVILEGED, TeleportAudit.classify(stack(f(P + "task.NavSafetyNet", "escapeSuffocation")), false));
        assertEquals(Kind.PRIVILEGED, TeleportAudit.classify(stack(f(P + "task.NavSafetyNet", "emergencyTeleportToAir")), false));
        assertEquals(Kind.PRIVILEGED, TeleportAudit.classify(stack(f(P + "task.DangerWatcher", "escapeToSurface")), false));
        assertEquals(Kind.PRIVILEGED, TeleportAudit.classify(stack(f(P + "task.GatherQuotaTask", "trySurface")), false));
    }

    @Test
    void thePathStartSnapIsACorrectionNotPrivileged() {
        // The user wants no path-correction teleports in any profile, so the snap must be counted as one.
        assertEquals(Kind.CORRECTION, TeleportAudit.classify(stack(f(P + "action.ActionPack", "snapPlayerToNearestStandable")), false));
        assertEquals(Kind.CORRECTION, TeleportAudit.classify(
                stack(f(P + "action.ActionPack", "lambda$snapPlayerToNearestStandable$1")), false));
    }

    @Test
    void lambdaBodiesOfThePrivilegedMethodsCountAsThem() {
        assertEquals(Kind.PRIVILEGED, TeleportAudit.classify(
                stack(f(P + "task.DangerWatcher", "lambda$escapeToSurface$0")), false));
        assertEquals(Kind.PRIVILEGED, TeleportAudit.classify(
                stack(f(P + "task.NavSafetyNet", "lambda$escapeSuffocation$2"),
                        f(P + "mode.CapabilityRuntime", "run"),
                        f(P + "task.NavSafetyNet", "escapeSuffocation")), false));
        assertEquals(Kind.PRIVILEGED, TeleportAudit.classify(
                stack(f(P + "task.GatherQuotaTask", "lambda$trySurface$5")), false));
    }

    @Test
    void otherMethodsOfThoseClassesAreCorrections() {
        assertEquals(Kind.CORRECTION, TeleportAudit.classify(stack(f(P + "action.ActionPack", "tryPhysicalSnap")), false));
        assertEquals(Kind.CORRECTION, TeleportAudit.classify(stack(f(P + "action.ActionPack", "lambda$tryPhysicalSnap$0")), false));
        assertEquals(Kind.CORRECTION, TeleportAudit.classify(stack(f(P + "task.NavSafetyNet", "escapeSuffocationByInputs")), false));
        assertEquals(Kind.CORRECTION, TeleportAudit.classify(stack(f(P + "task.NavSafetyNet", "tick")), false));
        assertEquals(Kind.CORRECTION, TeleportAudit.classify(stack(f(P + "task.DangerWatcher", "escapeToSurfaceLater")), false));
        // A same-named method of another class is not on the list.
        assertEquals(Kind.CORRECTION, TeleportAudit.classify(stack(f(P + "task.OtherTask", "trySurface")), false));
    }

    @Test
    void theProductionPrimitivesAreCorrections() {
        assertEquals(Kind.CORRECTION, TeleportAudit.classify(stack(f(P + "mode.FakePlayerMotion", "stepToStandable")), false));
        assertEquals(Kind.CORRECTION, TeleportAudit.classify(stack(f(P + "pathfinding.PathExecutor", "tick")), false));
    }

    @Test
    void aTestScopeOrAGameTestsCallerIsATestMove() {
        assertEquals(Kind.TEST, TeleportAudit.classify(
                stack(f(P + "gametest.BotFixtureMoves", "place")), true));
        // Inside a scope even a production primitive is a fixture move ...
        assertEquals(Kind.TEST, TeleportAudit.classify(
                stack(f(P + "mode.FakePlayerMotion", "stepTo"), f(P + "gametest.BotFixtureMoves", "lambda$place$0")), true));
        // ... and a GameTests class is a test caller without one, nested classes and lambdas included.
        assertEquals(Kind.TEST, TeleportAudit.classify(
                stack(f(P + "task.CombatHardeningGameTests", "put")), false));
        assertEquals(Kind.TEST, TeleportAudit.classify(
                stack(f(P + "task.BotFallAndKnockbackGameTests$Rig", "lambda$run$3")), false));
        assertEquals(Kind.CORRECTION, TeleportAudit.classify(
                stack(f(P + "task.CombatHardeningGameTestsHelper", "put")), false));
    }

    @Test
    void lifecycleUserAndPrivilegedWinOverTheTestScope() {
        assertEquals(Kind.LIFECYCLE, TeleportAudit.classify(
                stack(f(P + "manager.AIPlayerManager", "spawnInternal"), f(P + "gametest.BotFixtureMoves", "spawn")), true));
        assertEquals(Kind.PRIVILEGED, TeleportAudit.classify(
                stack(f(P + "task.DangerWatcher", "escapeToSurface")), true));
    }

    @Test
    void theFirstCallerIsTheInnermostModFrame() {
        Frame caller = TeleportAudit.firstCaller(stack(
                f(P + "mode.FakePlayerMotion", "stepTo"), f(P + "action.ActionPack", "snapPlayerToNearestStandable")));
        assertEquals("FakePlayerMotion", TeleportAudit.simpleClassName(caller.className()));
        assertEquals("stepTo", caller.methodName());
        assertNull(TeleportAudit.firstCaller(List.of(f("java.lang.Thread", "run"))));
    }

    @Test
    void simpleNamesDropThePackageAndTheNesting() {
        assertEquals("ActionPack", TeleportAudit.simpleClassName(P + "action.ActionPack"));
        assertEquals("BotFallAndKnockbackGameTests", TeleportAudit.simpleClassName(P + "task.BotFallAndKnockbackGameTests$Rig$1"));
        assertEquals("Plain", TeleportAudit.simpleClassName("Plain"));
    }

    @Test
    void thePrivilegedListIsExactlyTheDocumentedOnes() {
        assertEquals(java.util.Set.of(
                        "NavSafetyNet#escapeSuffocation",
                        "NavSafetyNet#emergencyTeleportToAir",
                        "DangerWatcher#escapeToSurface",
                        "GatherQuotaTask#trySurface"),
                TeleportAudit.PRIVILEGED_METHODS);
        assertTrue(TeleportAudit.isPrivileged(P + "task.NavSafetyNet", "lambda$escapeSuffocation$7"));
        assertFalse(TeleportAudit.isPrivileged(P + "task.NavSafetyNet", "lambda$escapeSuffocationByInputs$7"));
        assertFalse(TeleportAudit.isPrivileged(P + "task.NavSafetyNet", null));
    }

    @Test
    void theTestScopeNestsAndClosesOnce() {
        assertFalse(TeleportAudit.inTestScope());
        try (TeleportAudit.Scope outer = TeleportAudit.testScope()) {
            assertTrue(TeleportAudit.inTestScope());
            try (TeleportAudit.Scope inner = TeleportAudit.testScope()) {
                assertTrue(TeleportAudit.inTestScope());
                inner.close();
                inner.close();
            }
            assertTrue(TeleportAudit.inTestScope(), "closing the inner scope twice must not close the outer one");
        }
        assertFalse(TeleportAudit.inTestScope());
    }
}

package io.github.zoyluo.minecraftai.action;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks strict placement to vanilla reach and exact, physically visible support-face hits. */
class BuildActionVisibilitySourceTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/action/BuildAction.java");

    @Test
    void supportCenterDistanceCannotRejectAReachableFaceInset() throws IOException {
        String source = Files.readString(SOURCE);
        int place = source.indexOf("public static ActionResult placeBlock(");
        int placeAt = source.indexOf("public static ActionResult placeBlockAt(", place);
        assertTrue(place >= 0 && placeAt > place);
        String body = source.substring(place, placeAt);

        assertTrue(body.contains("player.isWithinBlockInteractionRange(against, 0.0D)"));
        assertTrue(body.contains("exactPlacementSampleRange("));
        assertFalse(body.contains("distanceToSqr(against.getCenter())"),
                "vanilla block-box reach must not be narrowed to support-center reach");
        assertTrue(body.contains("visibleSupportFaceHit(player, against, face, sampleRange)"));
        assertTrue(body.indexOf("visibleSupportFaceHit")
                        < body.indexOf("player.isWithinBlockInteractionRange"),
                "external support interaction checks require exact perception proof first");
        assertTrue(body.indexOf("visibleSupportFaceHit") < body.indexOf("ObservableWorldQuery.canObserveCell(player, destination)"),
                "destination observation requires an exact visible support-face proof first");
        assertFalse(body.contains("getBlockState(destination)"),
                "placement must not read the live destination to infer that a block was placed");
    }

    @Test
    void placeAtDoesNotUseFaceCenterVisibilityOrDirectMutationInAnyProfile()
            throws IOException {
        String source = Files.readString(SOURCE);
        int placeAt = source.indexOf("public static ActionResult placeBlockAt(");
        int exactSampler = source.indexOf("private static BlockHitResult visibleSupportFaceHit", placeAt);
        assertTrue(placeAt >= 0 && exactSampler > placeAt);
        String body = source.substring(placeAt, exactSampler);

        assertFalse(body.contains("ObservableWorldQuery.canObserveBlock"),
                "six face-center rays must not prevent the exact edge sampler from running");
        assertFalse(body.contains("getBlockState("),
                "placeBlockAt must not inspect an unproven adjacent support");
        assertTrue(body.contains("placeBlock(player, against, direction, InteractionHand.MAIN_HAND, true)"),
                "every support still goes through the exact placeBlock proof (plain supports first)");

        assertFalse(body.contains("OperatingProfile"),
                "no profile may grant a placement path a survival player lacks");
        assertFalse(body.contains("setBlock("),
                "placeBlockAt must never write the world directly: only a real click on a real support face places");
        assertFalse(source.contains("directPlaceFallback"), "the mid-air placement fallback is gone");
        assertFalse(source.contains("setBlock("),
                "BuildAction never writes a block itself");
    }


    @Test
    void everyBlockUseRepeatsThePacketHandlersMayInteractCheckFirst() throws IOException {
        String source = Files.readString(SOURCE);
        assertTrue(source.contains("!player.level().mayInteract(player, pos)"),
                "vanilla's ServerLevel.mayInteract (spawn protection, world border) is the check");
        int from = 0;
        int uses = 0;
        while ((from = source.indexOf("player.gameMode.useItemOn(", from)) >= 0) {
            int guard = source.lastIndexOf("isProtectedArea(player,", from);
            int previousUse = source.lastIndexOf("player.gameMode.useItemOn(", from - 1);
            assertTrue(guard >= 0 && guard > previousUse,
                    "a block use at offset " + from + " has no protected-area check before it");
            uses++;
            from++;
        }
        assertEquals(3, uses, "useItemOnHit, useItemOnFace and useItemOnCell are the only block-use sites");
        assertTrue(source.contains("ActionResult.failed(PROTECTED_AREA)"));
        assertEquals("protected_area", BuildAction.PROTECTED_AREA);
        String container = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/action/ContainerAction.java"));
        int open = container.indexOf("public static Optional<Container> open(");
        assertTrue(open > 0 && container.indexOf("BuildAction.isProtectedArea(bot, pos)", open) > open,
                "opening a container is a right click on it: the same protected-area refusal applies");
    }
    @Test
    void exactPlacementSampleRangeUsesTheSmallerPhysicalBoundary() {
        assertEquals(1.0D, BuildAction.exactPlacementSampleRange(0, 4.5D));
        assertEquals(2.0D, BuildAction.exactPlacementSampleRange(2, 4.5D));
        assertEquals(4.5D, BuildAction.exactPlacementSampleRange(16, 4.5D));
    }

    @Test
    void realVanillaFailureSurvivesLaterInvisibleSupports() {
        ActionResult vanillaFailure = ActionResult.failed("interact_block_FAIL");
        ActionResult invisible = ActionResult.failed("support_face_not_visible");

        assertSame(vanillaFailure,
                BuildAction.preferPlacementFailure(vanillaFailure, invisible));
        assertSame(vanillaFailure,
                BuildAction.preferPlacementFailure(invisible, vanillaFailure));
    }

    // ---- canAcceptPlacementAt must be a side-effect-free probe: it may never turn the bot's head ----------

    @Test
    void canAcceptPlacementAtUsesTheNonRotatingProbeNeverTheHeadTurningRealPlacementHelper()
            throws IOException {
        String source = Files.readString(SOURCE);
        int hasFace = source.indexOf("private static boolean hasAcceptableSupportFace(");
        int preferFailure = source.indexOf("static ActionResult preferPlacementFailure(", hasFace);
        assertTrue(hasFace >= 0 && preferFailure > hasFace);
        String body = source.substring(hasFace, preferFailure);

        assertTrue(body.contains("probeSupportFaceHit(player, against, face, sampleRange)"),
                "the placement-candidate query must go through the non-rotating probe");
        assertFalse(body.contains("visibleSupportFaceHit("),
                "a mere candidate check must never call the real placement's head-turning helper");
    }

    @Test
    void theProbeRayNeverTurnsTheBotsHeadOrCallsVanillasLookDirectionRaycast() throws IOException {
        String source = Files.readString(SOURCE);
        int rayTo = source.indexOf("private static BlockHitResult rayTo(");
        int interactive = source.indexOf("static boolean isInteractiveSupport(", rayTo);
        assertTrue(rayTo >= 0 && interactive > rayTo);
        String body = source.substring(rayTo, interactive);

        assertFalse(body.contains("LookAction.lookAt"),
                "the pure probe ray must never rotate the player");
        assertFalse(body.contains(".setYRot"),
                "the pure probe ray must never write yaw directly either");
        assertFalse(body.contains(".setXRot"),
                "the pure probe ray must never write pitch directly either");
        assertFalse(body.contains("player.pick("),
                "the pure probe ray must build its own ClipContext, not vanilla's look-direction raycast");
        assertTrue(body.contains("new ClipContext("),
                "the probe must cast its own eye-to-target ray");
    }

    @Test
    void theRealPlacementRayStillTurnsTheHeadBeforeRaycasting() throws IOException {
        String source = Files.readString(SOURCE);
        int rotateAndRaycast = source.indexOf("private static BlockHitResult rotateAndRaycast(");
        int rayTo = source.indexOf("private static BlockHitResult rayTo(", rotateAndRaycast);
        assertTrue(rotateAndRaycast >= 0 && rayTo > rotateAndRaycast);
        String body = source.substring(rotateAndRaycast, rayTo);

        assertTrue(body.contains("LookAction.lookAt(player, target)"),
                "the real placement's own ray must keep turning the head exactly as before");
        assertTrue(body.contains("player.pick(sampleRange, 1.0F, false)"),
                "the real placement's own ray must keep using vanilla's look-direction raycast");
    }
}

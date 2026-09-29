package io.github.zoyluo.minecraftai.action;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Placing against an interactive block must place (secondary use), rank plain supports first, and aim at the shape. */
class BuildActionShiftPlacementSourceTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/action/BuildAction.java");

    @Test
    void ourPlacementCrouchesAndTheSharedClickDoesNot() throws IOException {
        String source = Files.readString(SOURCE);
        int crouching = source.indexOf("private static Use useItemOnHitCrouching(");
        int crouchEnd = source.indexOf("public record Use(", crouching);
        String wrapper = source.substring(crouching, crouchEnd);
        int shiftOn = wrapper.indexOf("player.setShiftKeyDown(true)");
        int click = wrapper.indexOf("useItemOnHit(player, hit, hand)");
        int finallyBlock = wrapper.indexOf("finally");
        int restore = wrapper.indexOf("player.setShiftKeyDown(wasShifting)");
        assertTrue(shiftOn > 0 && shiftOn < click, "shift is pressed before the vanilla placement click");
        assertTrue(finallyBlock > click && restore > finallyBlock, "the previous shift state comes back in a finally");
        assertTrue(wrapper.contains("boolean wasShifting = player.isShiftKeyDown();"));

        // The click Baritone shares with us must stay a plain click: it opens doors and gates, and Baritone asks
        // for sneak itself when it places.
        int shared = source.indexOf("public static Use useItemOnHit(");
        int sharedEnd = source.indexOf("public static String supportFaceRefusal(", shared);
        String sharedBody = source.substring(shared, sharedEnd);
        assertTrue(sharedBody.contains("player.gameMode.useItemOn("));
        assertFalse(sharedBody.contains("setShiftKeyDown"), "useItemOnHit never presses shift");

        int place = source.indexOf("private static ActionResult placeBlock(");
        int placeEnd = source.indexOf("private static Use useItemOnHitCrouching(", place);
        assertTrue(source.substring(place, placeEnd).contains("useItemOnHitCrouching(player, hit, hand)"),
                "placeBlock (and so placeBlockAt) clicks crouching");
    }

    @Test
    void placeAtTriesPlainSupportsBeforeInteractiveOnesAndDecidesOnTheRayProof() throws IOException {
        String source = Files.readString(SOURCE);
        int placeAt = source.indexOf("public static ActionResult placeBlockAt(");
        int end = source.indexOf("static double exactPlacementSampleRange", placeAt);
        String body = source.substring(placeAt, end);
        assertTrue(body.contains("InteractionHand.MAIN_HAND, true)"), "first pass defers interactive supports");
        assertTrue(body.contains("InteractionHand.MAIN_HAND, false)"),
                "deferred supports are used after the plain ones");

        int place = source.indexOf("private static ActionResult placeBlock(");
        int placeEnd = source.indexOf("public static ActionResult placeBlockAt(", place);
        String placeBody = source.substring(place, placeEnd);
        assertTrue(placeBody.indexOf("visibleSupportFaceHit(") < placeBody.indexOf("isInteractiveSupport("),
                "the interaction decision (interactive support) is only taken once the face is ray-proven visible");
        assertTrue(placeBody.indexOf("isInteractiveSupport(") < placeBody.indexOf("useItemOnHitCrouching("));
    }

    @Test
    void supportFaceSamplerAimsAtTheOutlineShapeNotTheCellFace() throws IOException {
        String source = Files.readString(SOURCE);
        int sampler = source.indexOf("private static BlockHitResult supportFaceHit(");
        int rotate = source.indexOf("private static BlockHitResult rotateAndRaycast(", sampler);
        String body = source.substring(sampler, rotate);
        assertTrue(body.contains("FaceAim.aim(") && body.contains("ClipContext.Block.OUTLINE"));
        assertTrue(body.contains("FaceAim.facePoint("));
        assertFalse(body.contains("Vec3.atCenterOf(against)"), "no full-cube face centre any more");
    }
}

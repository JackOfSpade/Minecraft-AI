package io.github.zoyluo.minecraftai.mode;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shape-aware face aim points: pure geometry plus the source contracts that keep the rays honest. */
class FaceAimTest {
    private static final double EPS = 1.0E-9D;
    // A chest at cell (10, 20, 30): inset 1/16 on the sides, 14/16 tall.
    private static final AABB CHEST = new AABB(
            10 + 1 / 16.0D, 20.0D, 30 + 1 / 16.0D, 10 + 15 / 16.0D, 20 + 14 / 16.0D, 30 + 15 / 16.0D);

    private static void assertNear(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, EPS, "x");
        assertEquals(expected.y, actual.y, EPS, "y");
        assertEquals(expected.z, actual.z, EPS, "z");
    }

    @Test
    void chestFacePointsLieJustInsideTheChestNotTheCell() {
        double depth = FaceAim.OBSERVE_DEPTH;
        assertNear(new Vec3(10.5D, 20.875D - depth, 30.5D), FaceAim.facePoint(CHEST, Direction.UP, depth, 0, 0));
        assertNear(new Vec3(10.5D, 20.0D + depth, 30.5D), FaceAim.facePoint(CHEST, Direction.DOWN, depth, 0, 0));
        assertNear(new Vec3(11.0D - 1 / 16.0D - depth, 20.4375D, 30.5D),
                FaceAim.facePoint(CHEST, Direction.EAST, depth, 0, 0));
        assertNear(new Vec3(10.0D + 1 / 16.0D + depth, 20.4375D, 30.5D),
                FaceAim.facePoint(CHEST, Direction.WEST, depth, 0, 0));
        assertNear(new Vec3(10.5D, 20.4375D, 31.0D - 1 / 16.0D - depth),
                FaceAim.facePoint(CHEST, Direction.SOUTH, depth, 0, 0));
        assertNear(new Vec3(10.5D, 20.4375D, 30.0D + 1 / 16.0D + depth),
                FaceAim.facePoint(CHEST, Direction.NORTH, depth, 0, 0));
    }

    @Test
    void insetGridScalesToTheShapeExtentSoEveryPointStaysOnTheFace() {
        double inset = ObservableWorldQuery.FACE_SAMPLE_INSET;
        for (Direction face : Direction.values()) {
            for (double a : new double[]{-inset, 0.0D, inset}) {
                for (double b : new double[]{-inset, 0.0D, inset}) {
                    Vec3 point = FaceAim.facePoint(CHEST, face, FaceAim.OBSERVE_DEPTH, a, b);
                    assertTrue(CHEST.inflate(1.0E-9D).contains(point),
                            face + " " + a + " " + b + " left the chest: " + point);
                }
            }
        }
        // The top of a 0.875 x 0.875 face: 0.375 of the extent, not 0.375 of a block.
        Vec3 corner = FaceAim.facePoint(CHEST, Direction.UP, 0.0D, inset, -inset);
        assertEquals(10.5D + inset * 0.875D, corner.x, EPS);
        assertEquals(30.5D - inset * 0.875D, corner.z, EPS);
    }

    @Test
    void aFullCellBoxReproducesTheCellFaceGrid() {
        AABB cell = new AABB(10, 20, 30, 11, 21, 31);
        assertNear(new Vec3(10.999D, 20.125D, 30.875D),
                FaceAim.facePoint(cell, Direction.EAST, 0.001D, -0.375D, 0.375D));
        assertNear(new Vec3(10.125D, 20.001D, 30.875D),
                FaceAim.facePoint(cell, Direction.DOWN, 0.001D, -0.375D, 0.375D));
        assertNear(new Vec3(10.125D, 20.875D, 30.999D),
                FaceAim.facePoint(cell, Direction.SOUTH, 0.001D, -0.375D, 0.375D));
    }

    @Test
    void bottomSlabFacePointsUseTheHalfHeight() {
        AABB slab = new AABB(10, 20, 30, 11, 20.5D, 31);
        assertNear(new Vec3(10.5D, 20.499D, 30.5D), FaceAim.facePoint(slab, Direction.UP, 0.001D, 0, 0));
        assertNear(new Vec3(10.999D, 20.25D, 30.5D), FaceAim.facePoint(slab, Direction.EAST, 0.001D, 0, 0));
    }

    @Test
    void observationAimsAtTheShapeAndKeepsTheRayOnTheColliderOrOutline() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/mode/ObservableWorldQuery.java"));
        assertEquals(2, count(source, "FaceAim.aim(bot.level()"), "both block observation predicates are shape-aware");
        assertFalse(source.contains("getStepX() * 0.499D"), "the cell-face endpoint arithmetic is gone");
        assertTrue(source.contains("aim.clipShape(), fluid"),
                "ordinary shape-aware observation must ray-cast against its derived collider or outline");
        assertTrue(source.contains("aim.clipShape(),\n                        ClipContext.Fluid.ANY"),
                "inset face observation retains its fluid-aware shape ray");
        assertTrue(source.contains("ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot"),
                "water collider observation must prove a first collider hit without reading target shape first");
    }

    @Test
    void visibilityPredicatesSeeOutlineOnlyBlocksAndColliderPredicatesDoNot() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/mode/ObservableWorldQuery.java"));
        assertTrue(source.contains("observeShapeFaces(bot, pos, range, true, "), "canObserveBlockWithin: outline fallback on");
        assertTrue(source.contains("observeShapeFaces(bot, pos, range, false, "), "canObserveColliderWithin: outline fallback off");
        assertTrue(source.contains("return observeShapeInsetFaces(bot, pos, true);"));
        assertTrue(source.contains("return observeShapeInsetFaces(bot, pos, false"));
        assertTrue(source.contains("ClipContext.Block.COLLIDER, CollisionContext.of(bot), outlineFallback)"));
    }

    @Test
    void faceAimOnlyDerivesAimPointsFromTheTargetShape() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/mode/FaceAim.java"));
        assertFalse(source.contains(".clip("), "FaceAim only derives aim points; the ray is cast by the caller");
        assertTrue(source.contains("getCollisionShape") && source.contains("getShape"));
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}

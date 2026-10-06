package io.github.zoyluo.minecraftai.mode;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservableWorldQueryInsetFaceTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/mode/ObservableWorldQuery.java");

    @Test
    void insetEndpointUsesDeterministicTangentsInsideTheRequestedFace() {
        BlockPos pos = new BlockPos(10, 20, 30);
        AABB cell = new AABB(pos);
        double depth = FaceAim.OBSERVE_DEPTH;

        assertNear(new Vec3(10.999D, 20.125D, 30.875D),
                FaceAim.facePoint(cell, Direction.EAST, depth, -0.375D, 0.375D));
        assertNear(new Vec3(10.125D, 20.001D, 30.875D),
                FaceAim.facePoint(cell, Direction.DOWN, depth, -0.375D, 0.375D));
        assertNear(new Vec3(10.125D, 20.875D, 30.999D),
                FaceAim.facePoint(cell, Direction.SOUTH, depth, -0.375D, 0.375D));
    }

    private static void assertNear(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, 1.0E-9D, "x");
        assertEquals(expected.y, actual.y, 1.0E-9D, "y");
        assertEquals(expected.z, actual.z, 1.0E-9D, "z");
    }


    @Test
    void insetObservationIsExplicitShortRangeAndFluidAware() throws IOException {
        String source = Files.readString(SOURCE);
        int ordinary = source.indexOf("public static boolean canObserveBlock(");
        int cellFace = source.indexOf("public static boolean canObserveBlockCellFace(");
        int inset = source.indexOf("public static boolean canObserveBlockWithInsetFaces(");
        int facePolicy = source.indexOf("private static boolean canObserveFaceAfterPolicy", inset);
        assertTrue(ordinary >= 0 && cellFace > ordinary && inset > cellFace && facePolicy > inset);
        assertFalse(source.contains("insetFaceEndpoint") || source.contains("FACE_ENDPOINT_DEPTH"),
                "the cell-face endpoint helper is gone; FaceAim.facePoint is the one point builder");

        int ordinaryEnd = source.indexOf("\n    }", ordinary) + "\n    }".length();
        String ordinaryBody = source.substring(ordinary, ordinaryEnd);
        assertFalse(ordinaryBody.contains("FACE_SAMPLE_OFFSETS"),
                "ordinary block observation must retain its six-ray cost");
        assertFalse(ordinaryBody.contains("canObserveBlockWithInsetFaces"));

        // The sight and the strict predicate share one implementation that differs only in the clip it casts.
        int cellFaceImpl = source.indexOf("private static boolean observeBlockCellFace(");
        assertTrue(cellFaceImpl > cellFace, "the cell-face predicates share one implementation");
        int cellFaceEnd = source.indexOf("\n    }", cellFaceImpl) + "\n    }".length();
        String cellFaceBody = source.substring(cellFaceImpl, cellFaceEnd);
        assertTrue(cellFaceBody.contains("FaceAim.facePoint(cell, face"));
        assertTrue(cellFaceBody.contains("ClipContext.Fluid.ANY"));
        assertTrue(cellFaceBody.contains("pos.equals(hit.getBlockPos())"));
        assertFalse(cellFaceBody.contains("getBlockState(") || cellFaceBody.contains("FaceAim.aim("),
                "the preliminary cell-face proof must not read target state or shape before an action earns it");

        String insetBody = source.substring(inset, facePolicy);
        assertTrue(insetBody.contains("Math.min("));
        assertTrue(insetBody.contains("botRenderDistanceBlocks(bot)"),
                "inset observation is bounded by the actual tracked render distance, then interaction reach");
        assertTrue(insetBody.contains("bot.blockInteractionRange()"));
        assertTrue(insetBody.contains("ClipContext.Fluid.ANY"));
        assertTrue(insetBody.contains("hit.getBlockPos().equals(pos)"));
        assertTrue(insetBody.contains("hit.getDirection() == direction"));
        assertTrue(insetBody.contains("FACE_SAMPLE_OFFSETS"));
    }

    @Test
    void retiredHiddenScanNeverTurnsPerCellVisibilityProofsIntoAuditedCapabilityCalls() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("private static boolean canBypassObservationWithRetiredHiddenScan"));
        assertTrue(source.contains("retired for every operating profile"));
        assertTrue(source.contains("return false;"),
                "the local fast path must fail closed even if a future config changes");
        assertFalse(source.contains("CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN"),
                "ordinary cell/face checks must not audit a permanently denied capability once per candidate");
        assertTrue(source.contains("canBypassObservationWithRetiredHiddenScan(\"observable_cell_query\")"));
        assertTrue(source.contains("canBypassObservationWithRetiredHiddenScan(\"observable_entity_query\")"));
        assertTrue(source.contains("canBypassObservationWithRetiredHiddenScan(\"observable_water_collider_query\")"));
    }
}

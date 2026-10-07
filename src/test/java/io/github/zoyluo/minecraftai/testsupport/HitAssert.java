package io.github.zoyluo.minecraftai.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Field-by-field comparison of two {@link BlockHitResult}s: a ray result is only "the same" if every observable bit is. */
public final class HitAssert {
    private HitAssert() {
    }

    public static void assertSameHit(BlockHitResult expected, BlockHitResult actual, String message) {
        assertEquals(expected.getType(), actual.getType(), message + " (type)");
        assertEquals(expected.getBlockPos(), actual.getBlockPos(), message + " (block pos)");
        assertEquals(expected.getDirection(), actual.getDirection(), message + " (face)");
        assertEquals(expected.getLocation(), actual.getLocation(), message + " (location)");
        assertEquals(expected.isInside(), actual.isInside(), message + " (inside)");
        assertEquals(expected.isWorldBorderHit(), actual.isWorldBorderHit(), message + " (border)");
    }

    /** Asserts a block hit in {@code pos} and returns it. */
    public static BlockHitResult assertHitsBlock(BlockHitResult hit, BlockPos pos, String message) {
        assertEquals(HitResult.Type.BLOCK, hit.getType(), message + ": expected a hit in " + pos.toShortString());
        assertEquals(pos, hit.getBlockPos(), message);
        return hit;
    }
}

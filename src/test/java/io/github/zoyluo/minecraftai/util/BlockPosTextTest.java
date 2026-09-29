package io.github.zoyluo.minecraftai.util;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockPosTextTest {

    // ---- compact / compactOrElse ----

    @Test
    void compactFormatsNegativeAndPositiveCoordinates() {
        assertEquals("-5,64,-20", BlockPosText.compact(new BlockPos(-5, 64, -20)));
    }

    @Test
    void compactThrowsOnNullPos() {
        assertThrows(NullPointerException.class, () -> BlockPosText.compact(null));
    }

    @Test
    void compactOrElseSubstitutesTextForNull() {
        assertEquals("missing", BlockPosText.compactOrElse(null, "missing"));
        assertEquals("0,0,0", BlockPosText.compactOrElse(BlockPos.ZERO, "missing"));
    }

    // ---- encodePos / encodePosOrEmpty / encodeOptionalPos ----

    @Test
    void encodePosMatchesCompactAndThrowsOnNull() {
        BlockPos pos = new BlockPos(1, 2, 3);
        assertEquals(BlockPosText.compact(pos), BlockPosText.encodePos(pos));
        assertThrows(NullPointerException.class, () -> BlockPosText.encodePos(null));
    }

    @Test
    void encodePosOrEmptyReturnsEmptyStringForNull() {
        assertEquals("", BlockPosText.encodePosOrEmpty(null));
        assertEquals("1,2,3", BlockPosText.encodePosOrEmpty(new BlockPos(1, 2, 3)));
    }

    @Test
    void encodeOptionalPosReturnsNoneForNull() {
        assertEquals("none", BlockPosText.encodeOptionalPos(null));
        assertEquals("1,2,3", BlockPosText.encodeOptionalPos(new BlockPos(1, 2, 3)));
    }

    // ---- decodePos: null/blank -> empty; wrong part count -> empty; catches bad numbers ----

    @Test
    void decodePosRoundTripsNegativeCoordinates() {
        BlockPos pos = new BlockPos(-100, -64, 200);
        assertEquals(Optional.of(pos), BlockPosText.decodePos(BlockPosText.encodePos(pos)));
    }

    @Test
    void decodePosRejectsNullBlankAndWhitespace() {
        assertTrue(BlockPosText.decodePos(null).isEmpty());
        assertTrue(BlockPosText.decodePos("").isEmpty());
        assertTrue(BlockPosText.decodePos("   ").isEmpty());
    }

    @Test
    void decodePosRejectsWrongPartCount() {
        assertTrue(BlockPosText.decodePos("1,2").isEmpty());
        assertTrue(BlockPosText.decodePos("1,2,3,4").isEmpty());
    }

    @Test
    void decodePosDropsATrailingCommaInsteadOfRejecting() {
        // Regular split(",") drops the trailing empty part, so this is still exactly 3 parts.
        assertEquals(Optional.of(new BlockPos(1, 2, 3)), BlockPosText.decodePos("1,2,3,"));
    }

    @Test
    void decodePosRejectsNonNumericParts() {
        assertTrue(BlockPosText.decodePos("x,2,3").isEmpty());
        assertTrue(BlockPosText.decodePos("1, 2,3").isEmpty());
    }

    @Test
    void decodePosRejectsIntegerOverflow() {
        assertTrue(BlockPosText.decodePos("99999999999,2,3").isEmpty());
    }

    // ---- decodePosStrictSplit: same as decodePos but a trailing comma is rejected ----

    @Test
    void decodePosStrictSplitRejectsATrailingComma() {
        assertTrue(BlockPosText.decodePosStrictSplit("1,2,3,").isEmpty());
        assertEquals(Optional.of(new BlockPos(1, 2, 3)), BlockPosText.decodePosStrictSplit("1,2,3"));
    }

    @Test
    void decodePosStrictSplitRejectsNullBlankAndBadNumbers() {
        assertTrue(BlockPosText.decodePosStrictSplit(null).isEmpty());
        assertTrue(BlockPosText.decodePosStrictSplit("").isEmpty());
        assertTrue(BlockPosText.decodePosStrictSplit("1,2").isEmpty());
        assertTrue(BlockPosText.decodePosStrictSplit("x,2,3").isEmpty());
        assertTrue(BlockPosText.decodePosStrictSplit("99999999999,2,3").isEmpty());
    }

    @Test
    void decodePosStrictSplitRoundTripsNegativeCoordinates() {
        BlockPos pos = new BlockPos(-1, -2, -3);
        assertEquals(Optional.of(pos), BlockPosText.decodePosStrictSplit(BlockPosText.encodePos(pos)));
    }

    // ---- decodePosOrThrow: same accept/reject shape as decodePos, but a bad number propagates ----

    @Test
    void decodePosOrThrowRejectsNullBlankAndWrongPartCount() {
        assertTrue(BlockPosText.decodePosOrThrow(null).isEmpty());
        assertTrue(BlockPosText.decodePosOrThrow("").isEmpty());
        assertTrue(BlockPosText.decodePosOrThrow("   ").isEmpty());
        assertTrue(BlockPosText.decodePosOrThrow("1,2").isEmpty());
        assertTrue(BlockPosText.decodePosOrThrow("1,2,3,4").isEmpty());
    }

    @Test
    void decodePosOrThrowPropagatesNumberFormatExceptionInsteadOfSwallowingIt() {
        assertThrows(NumberFormatException.class, () -> BlockPosText.decodePosOrThrow("x,2,3"));
        assertThrows(NumberFormatException.class, () -> BlockPosText.decodePosOrThrow("99999999999,2,3"));
    }

    @Test
    void decodePosOrThrowRoundTripsNegativeCoordinates() {
        BlockPos pos = new BlockPos(-7, 8, -9);
        assertEquals(Optional.of(pos), BlockPosText.decodePosOrThrow(BlockPosText.encodePos(pos)));
    }

    // ---- decodeOptionalPos: "none" sentinel, throws IllegalArgumentException on bad part count ----

    @Test
    void decodeOptionalPosRoundTripsThroughNoneSentinel() {
        assertNull(BlockPosText.decodeOptionalPos(BlockPosText.encodeOptionalPos(null)));
        BlockPos pos = new BlockPos(4, -5, 6);
        assertEquals(pos, BlockPosText.decodeOptionalPos(BlockPosText.encodeOptionalPos(pos)));
    }

    @Test
    void decodeOptionalPosRejectsWrongPartCountWithIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> BlockPosText.decodeOptionalPos("1,2"));
        assertThrows(IllegalArgumentException.class, () -> BlockPosText.decodeOptionalPos("1,2,3,4"));
    }

    @Test
    void decodeOptionalPosRejectsATrailingCommaUnlikeDecodePos() {
        assertThrows(IllegalArgumentException.class, () -> BlockPosText.decodeOptionalPos("1,2,3,"));
    }

    @Test
    void decodeOptionalPosPropagatesNumberFormatExceptionOnBadNumbers() {
        assertThrows(NumberFormatException.class, () -> BlockPosText.decodeOptionalPos("x,2,3"));
    }
}

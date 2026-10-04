package io.github.zoyluo.minecraftai.mining.assist;

import org.junit.jupiter.api.Test;

import java.util.List;
import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoiEvidenceWindowTest {
    private static BlockPos at(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static List<BlockPos> structuralPositions(PoiEvidenceWindow window) {
        return window.structuralEntries().stream().map(PoiEvidenceWindow.Entry::pos).toList();
    }

    @Test
    void naturalCellWithoutFlagsIsNotEvidence() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        assertFalse(window.observe(at(1, 2, 3), PoiBucket.NATURAL, 0, 10, false));
        assertTrue(window.isEmpty());
    }

    @Test
    void naturalCellWithAFlagGoesToTheFlagOnlyWindow() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        assertTrue(window.observe(at(1, 2, 3), PoiBucket.NATURAL, PoiEvidenceFlags.BLACKSTONE, 10, false));
        assertEquals(0, window.structuralSize());
        assertEquals(1, window.flagOnlySize());
        assertTrue(window.contains(at(1, 2, 3)));
    }

    @Test
    void nonNaturalCellGoesToTheStructuralWindowAndRefreshesInPlace() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        assertTrue(window.observe(at(0, 0, 0), PoiBucket.RAIL, 0, 5, false));
        assertFalse(window.observe(at(0, 0, 0), PoiBucket.RAIL, 0, 9, false));
        assertEquals(1, window.structuralSize());
        assertEquals(9, window.structuralEntries().iterator().next().lastTick());
    }

    @Test
    void aCellMovesBetweenSubWindowsWhenItsClassificationChanges() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        window.observe(at(4, 4, 4), PoiBucket.NATURAL, PoiEvidenceFlags.BLACKSTONE, 1, false);
        window.observe(at(4, 4, 4), PoiBucket.RAIL, PoiEvidenceFlags.BLACKSTONE,
                2, false);
        assertEquals(1, window.structuralSize());
        assertEquals(0, window.flagOnlySize());
        window.observe(at(4, 4, 4), PoiBucket.NATURAL, 0, 3, false);
        assertTrue(window.isEmpty(), "re-observed as harmless: forgotten");
    }

    @Test
    void expiresAfterTwoHundredFortyTicksUnseen() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        window.observe(at(0, 0, 0), PoiBucket.WOOD_BUILD, 0, 100, false);
        window.observe(at(1, 0, 0), PoiBucket.WOOD_BUILD, 0, 300, false);
        assertEquals(0, window.expire(100 + PoiEvidenceWindow.EXPIRE_TICKS));
        assertEquals(1, window.expire(100 + PoiEvidenceWindow.EXPIRE_TICKS + 1));
        assertEquals(List.of(at(1, 0, 0)), structuralPositions(window));
        assertEquals(PoiScorer.EVIDENCE_WINDOW_TICKS, PoiEvidenceWindow.EXPIRE_TICKS);
    }

    @Test
    void expiryDoesNotAssumeMonotonicTicksInInsertionOrder() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        window.observe(at(0, 0, 0), PoiBucket.WOOD_BUILD, 0, 900, false);
        window.observe(at(1, 0, 0), PoiBucket.WOOD_BUILD, 0, 100, false);
        assertEquals(1, window.expire(1000));
        assertEquals(List.of(at(0, 0, 0)), structuralPositions(window));
    }

    @Test
    void structuralWindowIsCappedAtFiveHundredTwelveDroppingTheLeastRecentlySeen() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        for (int i = 0; i < PoiEvidenceWindow.STRUCTURAL_CAP + 40; i++) {
            window.observe(at(i, 0, 0), PoiBucket.WOOD_BUILD, 0, i, false);
        }
        assertEquals(PoiEvidenceWindow.STRUCTURAL_CAP, window.structuralSize());
        assertFalse(window.contains(at(0, 0, 0)));
        assertFalse(window.contains(at(39, 0, 0)));
        assertTrue(window.contains(at(40, 0, 0)));
        assertTrue(window.contains(at(PoiEvidenceWindow.STRUCTURAL_CAP + 39, 0, 0)));
    }

    @Test
    void refreshingACellProtectsItFromEviction() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        for (int i = 0; i < PoiEvidenceWindow.STRUCTURAL_CAP; i++) {
            window.observe(at(i, 0, 0), PoiBucket.WOOD_BUILD, 0, i, false);
        }
        window.observe(at(0, 0, 0), PoiBucket.WOOD_BUILD, 0, 600, false);
        window.observe(at(9999, 0, 0), PoiBucket.WOOD_BUILD, 0, 601, false);
        assertTrue(window.contains(at(0, 0, 0)));
        assertFalse(window.contains(at(1, 0, 0)));
    }

    @Test
    void aFlagOnlyFieldCannotPushStructuralEvidenceOut() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        window.observe(at(500, 0, 0), PoiBucket.RAIL, 0, 1, false);
        for (int i = 0; i < 1000; i++) {
            window.observe(at(i, 1, 0), PoiBucket.NATURAL, PoiEvidenceFlags.BLACKSTONE, 2 + i, false);
        }
        assertEquals(PoiEvidenceWindow.FLAG_ONLY_CAP, window.flagOnlySize());
        assertTrue(window.contains(at(500, 0, 0)));
        assertEquals(1, window.structuralSize());
    }

    @Test
    void viaDecorStaysTrueOnlyWhileNoColliderRayHasSeenTheCell() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        window.observe(at(0, 0, 0), PoiBucket.RAIL, 0, 1, true);
        assertTrue(window.structuralEntries().iterator().next().viaDecor());
        window.observe(at(0, 0, 0), PoiBucket.RAIL, 0, 2, false);
        assertFalse(window.structuralEntries().iterator().next().viaDecor());
        window.observe(at(0, 0, 0), PoiBucket.RAIL, 0, 3, true);
        assertFalse(window.structuralEntries().iterator().next().viaDecor(), "collider sight is sticky");
    }

    @Test
    void removeForgetsFromBothSubWindows() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        window.observe(at(1, 1, 1), PoiBucket.RAIL, 0, 1, false);
        window.observe(at(2, 2, 2), PoiBucket.NATURAL, PoiEvidenceFlags.BLACKSTONE, 1, false);
        assertTrue(window.remove(at(1, 1, 1)));
        assertTrue(window.remove(at(2, 2, 2)));
        assertFalse(window.remove(at(3, 3, 3)));
        assertTrue(window.isEmpty());
    }

    @Test
    void storedPositionsAreImmutableCopies() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(5, 6, 7);
        window.observe(cursor, PoiBucket.RAIL, 0, 1, false);
        cursor.set(9, 9, 9);
        assertEquals(at(5, 6, 7), window.structuralEntries().iterator().next().pos());
        assertNotEquals(cursor, window.structuralEntries().iterator().next().pos());
    }

    @Test
    void clearEmptiesEverything() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        window.observe(at(1, 1, 1), PoiBucket.RAIL, 0, 1, false);
        window.observe(at(2, 2, 2), PoiBucket.NATURAL, PoiEvidenceFlags.BLACKSTONE, 1, false);
        window.clear();
        assertEquals(0, window.size());
    }
}

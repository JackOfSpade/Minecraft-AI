package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.task.SurfaceColumn.Cell;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure surface-versus-underground decision (no Minecraft bootstrap needed). */
final class SurfaceColumnTest {
    private static boolean open(Cell... column) {
        return SurfaceColumn.isOpenToSky(List.of(column).iterator());
    }

    @Test
    void anEmptyColumnAtTheWorldTopIsOpenSky() {
        assertTrue(open());
    }

    @Test
    void airAndFluidsNeverBlock() {
        assertTrue(open(Cell.OPEN, Cell.OPEN, Cell.OPEN));
    }

    @Test
    void naturalCanopyDoesNotBlock() {
        assertTrue(open(Cell.OPEN, Cell.CANOPY, Cell.CANOPY, Cell.OPEN, Cell.CANOPY),
                "logs and leaves overhead are still the surface");
    }

    @Test
    void anyRoofBlocksWhereverItIs() {
        assertFalse(open(Cell.ROOF));
        assertFalse(open(Cell.OPEN, Cell.OPEN, Cell.ROOF));
        assertFalse(open(Cell.CANOPY, Cell.ROOF, Cell.OPEN, Cell.OPEN),
                "a stone slab under a tree's leaves is still a roof");
        assertFalse(open(Cell.OPEN, Cell.CANOPY, Cell.OPEN, Cell.ROOF),
                "a roof high above the canopy still makes it indoors/underground");
    }

    @Test
    void readingStopsAtTheFirstRoof() {
        List<Cell> read = new ArrayList<>();
        java.util.Iterator<Cell> lazy = new java.util.Iterator<>() {
            private int next;

            @Override
            public boolean hasNext() {
                return next < 400;
            }

            @Override
            public Cell next() {
                Cell cell = next++ == 2 ? Cell.ROOF : Cell.OPEN;
                read.add(cell);
                return cell;
            }
        };
        assertFalse(SurfaceColumn.isOpenToSky(lazy));
        assertEquals(3, read.size(), "an underground spot must cost only the cells up to its ceiling");
    }
}

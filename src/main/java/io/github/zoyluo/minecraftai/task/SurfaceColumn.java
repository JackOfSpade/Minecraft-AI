package io.github.zoyluo.minecraftai.task;

import java.util.Iterator;

/**
 * The pure half of the "is this spot on the surface" decision: how one cell of the column above a
 * spot counts, and when a whole column means "open sky". It knows nothing about Minecraft blocks;
 * {@link SurfaceCheck} classifies real block states into these categories and feeds the column in.
 */
final class SurfaceColumn {
    private SurfaceColumn() {
    }

    /** What one cell above a spot is, for the purpose of telling surface from underground. */
    enum Cell {
        /** Air or a fluid: nothing between the spot and the sky. */
        OPEN,
        /** Natural tree or mushroom growth (logs, leaves, vines, caps...): a tree canopy is not a roof. */
        CANOPY,
        /** Any other block: stone, planks, glass, a placed slab... a real roof (underground or indoors). */
        ROOF
    }

    /**
     * True when a column, given from the cell just above the spot up to the top of the world, holds no
     * {@link Cell#ROOF}. An empty column (the spot already touches the world's top) is open sky.
     * Stops reading at the first roof, so underground spots cost a handful of cells.
     */
    static boolean isOpenToSky(Iterator<Cell> cellsFromNearestUp) {
        while (cellsFromNearestUp.hasNext()) {
            if (cellsFromNearestUp.next() == Cell.ROOF) {
                return false;
            }
        }
        return true;
    }
}

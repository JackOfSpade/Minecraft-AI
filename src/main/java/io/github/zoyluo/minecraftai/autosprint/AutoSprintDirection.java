package io.github.zoyluo.minecraftai.autosprint;

/** A cardinal, world-relative heading for an auto-sprint route. */
public enum AutoSprintDirection {
    NORTH(0, -1),
    SOUTH(0, 1),
    EAST(1, 0),
    WEST(-1, 0);

    private final int stepX;
    private final int stepZ;

    AutoSprintDirection(int stepX, int stepZ) {
        this.stepX = stepX;
        this.stepZ = stepZ;
    }

    public int stepX() {
        return stepX;
    }

    public int stepZ() {
        return stepZ;
    }
}

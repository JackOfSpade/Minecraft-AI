package dev.spawnbotswrapper.inhabitants.spawn;

/**
 * The single definition of "a bot can stand here", shared by spawn-position search, home verification and
 * patrol validation so the three can never disagree.
 * <p>
 * A position is FEET coordinates: the block the feet occupy. It is valid when the block below is a safe
 * solid floor, the feet and head blocks are free of solids and hazards (water only when submerged
 * standing is allowed), the column is inside the world border, and the whole 3-block window lies inside
 * the build height with one spare block above the head.
 */
final class Standing {

    enum Verdict {
        VALID,
        /** Definitely not a place to stand. */
        INVALID,
        /** At least one needed block could not be read; the answer is unknown, not "no". */
        UNLOADED
    }

    private Standing() {
    }

    static Verdict at(ProbeView view, int x, int y, int z, boolean allowSubmerged) {
        if (y < view.minY() + 1 || y > view.maxY() - 2 || !view.insideBorder(x, z)) {
            return Verdict.INVALID;
        }
        Cell floor = view.cell(x, y - 1, z);
        Cell feet = view.cell(x, y, z);
        Cell head = view.cell(x, y + 1, z);
        if (floor == Cell.UNLOADED || feet == Cell.UNLOADED || head == Cell.UNLOADED) {
            return Verdict.UNLOADED;
        }
        boolean valid = floor == Cell.SOLID_STANDABLE && open(feet, allowSubmerged) && open(head, allowSubmerged);
        return valid ? Verdict.VALID : Verdict.INVALID;
    }

    private static boolean open(Cell cell, boolean allowSubmerged) {
        return cell == Cell.EMPTY || (allowSubmerged && cell == Cell.WATER);
    }
}

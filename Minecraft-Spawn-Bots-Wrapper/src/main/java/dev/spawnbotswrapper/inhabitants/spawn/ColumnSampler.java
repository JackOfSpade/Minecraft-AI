package dev.spawnbotswrapper.inhabitants.spawn;

import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.util.SplitMix64;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Draws (x, z) columns from a set of boxes WITHOUT replacement.
 * <p>
 * Plain area-proportional sampling would starve small pieces (a village has a 40x40 plaza next to 1x1
 * lamp posts), so a fixed share of draws first picks a box uniformly and only the rest follow area. Never
 * repeating a column means a small structure is searched exhaustively once the attempt budget exceeds its
 * area, and no attempt is wasted re-reading a column that already failed. Each box keeps a sparse
 * Fisher-Yates permutation, so a 200x200 box costs memory only for the columns actually drawn.
 * <p>
 * Identical boxes are collapsed (overlapping village pieces are common, exact duplicates would only double
 * a box's weight). Deterministic for a given generator state: iteration order is fixed and the only
 * randomness comes from the supplied {@link SplitMix64}.
 */
final class ColumnSampler {

    /** A drawn column and the box it was drawn from (the box bounds which Y levels are legitimate). */
    record Column(IntBox box, int x, int z) {
    }

    /** Share of draws that pick a box uniformly instead of by remaining area. */
    static final double UNIFORM_BOX_SHARE = 0.25;

    private final List<BoxDeck> active = new ArrayList<>();
    private long totalRemaining;

    ColumnSampler(List<IntBox> boxes) {
        for (IntBox box : new LinkedHashSet<>(boxes)) {
            BoxDeck deck = new BoxDeck(box);
            active.add(deck);
            totalRemaining += deck.remaining;
        }
    }

    boolean hasNext() {
        return !active.isEmpty();
    }

    Column next(SplitMix64 rng) {
        if (active.isEmpty()) {
            throw new IllegalStateException("every column has been drawn");
        }
        BoxDeck deck = choose(rng);
        Column column = deck.draw(rng);
        totalRemaining--;
        if (deck.remaining == 0) {
            active.remove(deck);
        }
        return column;
    }

    private BoxDeck choose(SplitMix64 rng) {
        if (active.size() == 1) {
            return active.get(0);
        }
        if (rng.nextDouble() < UNIFORM_BOX_SHARE) {
            return active.get(rng.nextInt(active.size()));
        }
        long ticket = Math.min(totalRemaining - 1, (long) (rng.nextDouble() * totalRemaining));
        for (BoxDeck deck : active) {
            if (ticket < deck.remaining) {
                return deck;
            }
            ticket -= deck.remaining;
        }
        return active.get(active.size() - 1);
    }

    private static final class BoxDeck {
        private final IntBox box;
        private final long width;
        private long remaining;
        /** Slots of the virtual shuffled array that no longer hold their own index. */
        private final Map<Long, Long> moved = new HashMap<>();

        BoxDeck(IntBox box) {
            this.box = box;
            this.width = (long) box.maxX() - box.minX() + 1;
            this.remaining = width * ((long) box.maxZ() - box.minZ() + 1);
        }

        Column draw(SplitMix64 rng) {
            long pick = below(rng, remaining);
            long cell = moved.getOrDefault(pick, pick);
            long last = remaining - 1;
            moved.put(pick, moved.getOrDefault(last, last));
            moved.remove(last);
            remaining--;
            return new Column(box, (int) (box.minX() + cell % width), (int) (box.minZ() + cell / width));
        }

        private static long below(SplitMix64 rng, long bound) {
            return bound <= Integer.MAX_VALUE
                    ? rng.nextInt((int) bound)
                    : Long.remainderUnsigned(rng.nextLong(), bound);
        }
    }
}

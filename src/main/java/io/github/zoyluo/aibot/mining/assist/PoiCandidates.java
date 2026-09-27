package io.github.zoyluo.aibot.mining.assist;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Per-bot set of shadow POI candidates and their hysteresis (mining-assist design 6.3: "T >= 0.40 on at
 * least 2 of the last 3 evaluations, 20 ticks apart"). Each candidate owns one
 * {@link PoiScorer.Hysteresis}. Evaluations are matched to a candidate by distance to its anchor, so
 * a site the bot keeps looking at accumulates hits while an unrelated evaluation elsewhere starts its
 * own. At most {@value #MAX_CANDIDATES} candidates live at once; a candidate silent for longer than the
 * evidence window is dropped. Pure and deterministic, server thread only.
 */
public final class PoiCandidates {
    public static final int MAX_CANDIDATES = 4;

    /** One tracked site. */
    public static final class Candidate {
        private BlockPos anchor;
        private final PoiScorer.Hysteresis hysteresis = new PoiScorer.Hysteresis();
        private final int firstTick;
        private int lastTick;

        private Candidate(BlockPos anchor, int tick) {
            this.anchor = anchor.toImmutable();
            this.firstTick = tick;
            this.lastTick = tick;
        }

        public BlockPos anchor() {
            return anchor;
        }

        public PoiScorer.Hysteresis hysteresis() {
            return hysteresis;
        }

        public int firstTick() {
            return firstTick;
        }

        public int lastTick() {
            return lastTick;
        }
    }

    /** Outcome of feeding one evaluation: the candidate it landed on (null when untracked) and its hysteresis verdict. */
    public record Tracked(Candidate candidate, boolean satisfied, int hits, int evaluations) {
        public static final Tracked NONE = new Tracked(null, false, 0, 0);
    }

    private final List<Candidate> candidates = new ArrayList<>();

    /**
     * Records one evaluation. An evaluation without the possible-gate creates nothing (it can only add a
     * miss to a candidate that already exists within {@code radius} of {@code anchor}); one with the
     * gate creates a candidate when none matches, evicting the stalest when full.
     */
    public Tracked record(BlockPos anchor, PoiScorer.PoiScore score, int tick, int radius) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(score, "score");
        prune(tick);
        Candidate match = nearest(anchor, radius);
        if (match == null) {
            if (!score.possibleGate()) {
                return Tracked.NONE;
            }
            if (candidates.size() >= MAX_CANDIDATES) {
                candidates.remove(stalestIndex());
            }
            match = new Candidate(anchor, tick);
            candidates.add(match);
        }
        boolean satisfied = match.hysteresis.record(tick, score);
        match.anchor = anchor.toImmutable();
        match.lastTick = tick;
        return new Tracked(match, satisfied, match.hysteresis.hits(tick), match.hysteresis.evaluations());
    }

    /** Drops candidates that have not been evaluated within the scorer's evidence window. */
    public int prune(int nowTick) {
        int before = candidates.size();
        candidates.removeIf(c -> (long) nowTick - c.lastTick > PoiScorer.EVIDENCE_WINDOW_TICKS || nowTick < c.lastTick);
        return before - candidates.size();
    }

    private Candidate nearest(BlockPos anchor, int radius) {
        long limit = (long) Math.max(1, radius) * Math.max(1, radius);
        Candidate best = null;
        long bestSq = Long.MAX_VALUE;
        for (Candidate c : candidates) {
            long dx = (long) c.anchor.getX() - anchor.getX();
            long dy = (long) c.anchor.getY() - anchor.getY();
            long dz = (long) c.anchor.getZ() - anchor.getZ();
            long sq = dx * dx + dy * dy + dz * dz;
            if (sq <= limit && sq < bestSq) {
                best = c;
                bestSq = sq;
            }
        }
        return best;
    }

    private int stalestIndex() {
        int index = 0;
        for (int i = 1; i < candidates.size(); i++) {
            if (candidates.get(i).lastTick < candidates.get(index).lastTick) {
                index = i;
            }
        }
        return index;
    }

    public List<Candidate> snapshot() {
        return List.copyOf(candidates);
    }

    public int size() {
        return candidates.size();
    }

    public void clear() {
        candidates.clear();
    }
}

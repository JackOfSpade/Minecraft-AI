package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.SightClipContext;
import io.github.zoyluo.minecraftai.perception.SharedWorldSight;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A small, budgeted 360-degree look-around for tree gathering.
 *
 * <p>It deliberately uses only eye rays that stop at the first opaque block (and already ray-proven shared sight), rather
 * than enumerating a render-distance cube. The eye sees through leaves, so a visible trunk is a direct target even when the
 * canopy hides it from a hand; a visible leaf is a factual landmark that gather can pursue with its existing
 * observed-navigation boundary.</p>
 */
final class TreeHorizonScan {
    /**
     * At a 10-chunk server view (roughly 160 blocks), this leaves about 3.1 blocks between
     * neighbouring final-pass azimuths. That is deliberately narrower than an ordinary tree
     * canopy, rather than the old 1.875-degree / 5.2-block spacing which could thread between it.
     *
     * <p>A second sub-cell phase closes the remaining lattice seams without increasing the
     * per-tick ray budget.</p>
     */
    private static final int AZIMUTH_SAMPLES = 320;
    /** Rays are spread across ticks so a look-around never creates a server hitch. */
    private static final int RAYS_PER_STEP = 64;
    /** Number of pitch bands across the full practical tree-bearing view. */
    private static final int ELEVATION_SAMPLES = 48;
    /**
     * The range covers a lower tree in a ravine through a canopy high above the bot. The two
     * offset phases close the remaining pitch seams without treating loaded-but-occluded cells
     * as visible terrain.
     */
    private static final double ELEVATION_MIN = -1.52D;
    private static final double ELEVATION_MAX = 1.52D;
    /** A second half-cell phase closes systematic gaps between the initial first-hit rays. */
    private static final int SUBCELL_PHASES = 2;
    /** Co-prime permutation makes each tick distribute rays around the entire horizon. */
    private static final int AZIMUTH_STRIDE = 127;
    private static final int AZIMUTH_BAND_OFFSET = 73;
    /** Let ordinary safe exploration continue after a short initial look-around. */
    private static final int FALLBACK_HOLD_STEPS = 8;
    /** Fresh bot/owner visual evidence can arrive after this scan has already started. */
    private static final int SHARED_SIGHT_RECHECK_STEPS = 8;
    private static final int SHARED_SIGHT_LIMIT = 96;
    private static final int SAMPLES_PER_PHASE = AZIMUTH_SAMPLES * ELEVATION_SAMPLES;
    private static final int TOTAL_SAMPLES = SAMPLES_PER_PHASE * SUBCELL_PHASES;

    record Sighting(BlockPos pos, Kind kind, int raysCast) {
        Sighting {
            pos = pos == null ? null : pos.immutable();
        }
    }

    enum Kind {
        LOG,
        LEAF
    }

    private final Set<Block> targetBlocks;
    private final DeclinedSightings declined = new DeclinedSightings();
    /** The physical eye position this raster was sampled from; a moved bot starts a fresh pass. */
    private BlockPos origin;
    private int sampleCursor;
    private int raysCast;
    private int steps;
    private boolean complete;

    TreeHorizonScan(Set<Block> targetBlocks) {
        this.targetBlocks = targetBlocks == null ? Set.of() : Set.copyOf(targetBlocks);
    }

    boolean complete() {
        return complete;
    }

    /**
     * The caller could do nothing with this sighting from where the bot stands now. It is not
     * offered again until the bot stands in another cell, so one unusable block (a leaf straight
     * overhead, an excluded log) cannot answer every vertical and lattice ray of every step.
     */
    void decline(AIPlayerEntity bot, BlockPos sighting) {
        declined.decline(bot.blockPosition(), sighting);
    }

    /** True only for the initial brief look-around before ordinary safe exploration resumes. */
    boolean shouldHoldFallback() {
        return !complete && steps < FALLBACK_HOLD_STEPS;
    }

    /** Advances a single fair 360-degree slice and returns the first useful visible tree feature. */
    Sighting step(AIPlayerEntity bot) {
        if (complete || bot == null || targetBlocks.isEmpty()) {
            complete = true;
            return null;
        }
        resetIfMoved(bot);
        BlockPos feet = bot.blockPosition();
        if (steps % SHARED_SIGHT_RECHECK_STEPS == 0) {
            Sighting remembered = sharedSight(bot, feet);
            if (remembered != null) {
                // A shared-sight lead is a useful shortcut, not a reason to pin this cursor at
                // its initial cadence forever.  Without advancing steps here, the next call
                // re-checks the identical shared observation at step zero and a lead the caller
                // cannot use would keep the rest of the sweep from ever running.
                steps++;
                return remembered;
            }
        }
        steps++;
        int range = Math.max(1, ObservableWorldQuery.visibleRangeBlocks(bot) - 1);
        // A trunk or canopy directly overhead/underfoot has no azimuth; keep those factual
        // vertical rays outside the finite angular lattice.
        Sighting vertical = treeSighting(ObservableWorldQuery.castSightRay(bot, 0.0D, 1.0D, 0.0D,
                range, ObservableWorldQuery.ViewShape.OUTLINE, null), feet);
        if (vertical != null) {
            return vertical;
        }
        Sighting downward = treeSighting(ObservableWorldQuery.castSightRay(bot, 0.0D, -1.0D, 0.0D,
                range, ObservableWorldQuery.ViewShape.OUTLINE, null), feet);
        if (downward != null) {
            return downward;
        }
        for (int budget = 2; budget < RAYS_PER_STEP && !complete; budget++) {
            // A pitch-at-a-time sweep starves a tall canopy for many ticks. This persistent
            // 2-D permutation visits every pitch in each batch and scatters each pitch around
            // the full ring. Both phases remain first-hit eye rays; no hidden block volume is
            // inspected or inferred.
            int phase = sampleCursor / SAMPLES_PER_PHASE;
            int inPhase = sampleCursor % SAMPLES_PER_PHASE;
            int elevationSlot = inPhase % ELEVATION_SAMPLES;
            int azimuthVisit = inPhase / ELEVATION_SAMPLES;
            int elevationSample = centeredElevationSample(elevationSlot);
            int azimuthIndex = Math.floorMod(azimuthVisit * AZIMUTH_STRIDE
                    + elevationSample * AZIMUTH_BAND_OFFSET, AZIMUTH_SAMPLES);
            double subcellOffset = phase == 0 ? 0.25D : 0.75D;
            double azimuth = (Math.PI * 2.0D * (azimuthIndex + subcellOffset)) / AZIMUTH_SAMPLES;
            double elevation = elevation(elevationSample, subcellOffset);
            double horizontal = Math.cos(elevation);
            ObservableWorldQuery.ViewHit hit = ObservableWorldQuery.castSightRay(bot,
                    Math.cos(azimuth) * horizontal, Math.sin(elevation), Math.sin(azimuth) * horizontal,
                    range, ObservableWorldQuery.ViewShape.OUTLINE, null);
            advance();
            Sighting sighting = treeSighting(hit, feet);
            if (sighting != null) {
                return sighting;
            }
        }
        return null;
    }

    private Sighting sharedSight(AIPlayerEntity bot, BlockPos feet) {
        List<SharedWorldSight.Observation> observations = SharedWorldSight.knownBlocks(bot,
                state -> kindOf(state) != null, SHARED_SIGHT_LIMIT);
        for (SharedWorldSight.Observation observation : observations) {
            BlockPos pos = observation.pos();
            if (declined.isDeclined(feet, pos)) {
                continue;
            }
            // Shared memory is only a lead. Re-prove the cell before its live state is read.
            if (!ObservableWorldQuery.canObserveBlock(bot, pos)) {
                continue;
            }
            BlockState state = bot.level().getBlockState(pos);
            Kind kind = kindOf(state);
            if (kind != null) {
                return new Sighting(pos, kind, raysCast);
            }
        }
        return null;
    }

    /**
     * Converts one already cast sight ray into a tree feature without a second world read. The eye sees through foliage, so a
     * ray reports the leaves it crossed and what lies behind them: a trunk that shows, even behind the canopy, is always the
     * better answer, and the nearest leaf is the landmark only when no trunk does. A block the caller has declined from the
     * cell the bot stands in (see {@link #decline}) is passed over wherever the ray meets it, crossed or struck.
     */
    private Sighting treeSighting(ObservableWorldQuery.ViewHit hit, BlockPos feet) {
        raysCast++;
        Sighting landmark = null;
        for (SightClipContext.Crossing crossing : hit.crossed()) {
            Kind kind = kindOf(crossing.state());
            if (kind == null || declined.isDeclined(feet, crossing.pos())) {
                continue;
            }
            if (kind == Kind.LOG) {
                return new Sighting(crossing.pos(), kind, raysCast);
            }
            if (landmark == null) {
                landmark = new Sighting(crossing.pos(), kind, raysCast);
            }
        }
        if (hit.hit() && hit.pos() != null && hit.state() != null) {
            Kind kind = kindOf(hit.state());
            if (kind != null && (kind == Kind.LOG || landmark == null) && !declined.isDeclined(feet, hit.pos())) {
                return new Sighting(hit.pos(), kind, raysCast);
            }
        }
        return landmark;
    }

    private Kind kindOf(BlockState state) {
        if (targetBlocks.contains(state.getBlock())) {
            return Kind.LOG;
        }
        return state.is(BlockTags.LEAVES) ? Kind.LEAF : null;
    }

    private void advance() {
        sampleCursor++;
        if (sampleCursor >= TOTAL_SAMPLES) {
            complete = true;
        }
    }

    /** A tree-bearing view sweep cannot reuse directions sampled before a material relocation. */
    private void resetIfMoved(AIPlayerEntity bot) {
        BlockPos feet = bot.blockPosition().immutable();
        if (origin == null) {
            origin = feet;
            return;
        }
        if (feet.distSqr(origin) <= 64.0D) {
            return;
        }
        origin = feet;
        sampleCursor = 0;
        raysCast = 0;
        steps = 0;
        complete = false;
        declined.clear();
    }

    private static double elevation(int sample, double subcellOffset) {
        return ELEVATION_MIN + (ELEVATION_MAX - ELEVATION_MIN)
                * (sample + subcellOffset) / ELEVATION_SAMPLES;
    }

    /** Alternates around the horizon so near-level and steep visible canopies are both sampled early. */
    private static int centeredElevationSample(int slot) {
        int lowerMiddle = ELEVATION_SAMPLES / 2 - 1;
        return (slot & 1) == 0
                ? lowerMiddle - slot / 2
                : lowerMiddle + (slot + 1) / 2;
    }
}

package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.SightClipContext;
import io.github.zoyluo.minecraftai.perception.SharedWorldSight;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

/**
 * Budgeted, line-of-sight-only discovery for an ordinary gather or mine target.
 *
 * <p>This is intentionally a view sweep, not a render-distance block scan. Each new fact is
 * either what an eye ray reaches (the eye sees through foliage, fences, glass and water) or an
 * already ray-proven shared-sight memory entry that is
 * proved again before it is returned. Unlike {@link TreeHorizonScan}, it has no landmark class:
 * a non-tree target is useful only when the target block itself is currently visible.</p>
 */
final class VisibleTargetHorizonScan {
    /** Finer horizontal coverage helps a single exposed target block at normal render ranges. */
    private static final int AZIMUTH_SAMPLES = 480;
    /** Keep the same modest per-tick eye-ray budget as the tree look-around. */
    private static final int RAYS_PER_STEP = 64;
    /** Cover nearly the whole above/below horizon, including steep cliffs and high ceilings. */
    private static final int ELEVATION_SAMPLES = 64;
    private static final double ELEVATION_MIN = -1.52D;
    private static final double ELEVATION_MAX = 1.52D;
    /** A second half-cell phase closes systematic gaps without a hidden-volume scan. */
    private static final int SUBCELL_PHASES = 2;
    /** Co-prime permutation makes each tick span the full ring rather than one sector. */
    private static final int AZIMUTH_STRIDE = 181;
    private static final int AZIMUTH_BAND_OFFSET = 131;
    /** Do not make a no-hit raster hold ordinary exploration for its whole fine pass. */
    private static final int FALLBACK_HOLD_STEPS = 8;
    /** Fresh bot/owner rays arrive every tick; reconsider them during a long sweep. */
    private static final int SHARED_SIGHT_RECHECK_STEPS = 8;
    private static final int SHARED_SIGHT_LIMIT = 96;
    private static final int SAMPLES_PER_PHASE = AZIMUTH_SAMPLES * ELEVATION_SAMPLES;
    private static final int TOTAL_SAMPLES = SAMPLES_PER_PHASE * SUBCELL_PHASES;

    record Sighting(BlockPos pos, int raysCast) {
        Sighting {
            pos = pos == null ? null : pos.immutable();
        }
    }

    private final Set<Block> targetBlocks;
    /** The physical eye position this raster was sampled from; a moved bot starts a fresh pass. */
    private BlockPos origin;
    private int sampleCursor;
    private int raysCast;
    private int steps;
    private boolean complete;

    VisibleTargetHorizonScan(Set<Block> targetBlocks) {
        this.targetBlocks = targetBlocks == null ? Set.of() : Set.copyOf(targetBlocks);
    }

    boolean complete() {
        return complete;
    }

    /** True only for the short initial look-around that may defer ordinary exploration. */
    boolean shouldHoldFallback() {
        return !complete && steps < FALLBACK_HOLD_STEPS;
    }

    /** Advances one fair 360-degree slice and returns only a directly visible requested block. */
    Sighting step(AIPlayerEntity bot) {
        if (complete || bot == null || targetBlocks.isEmpty()) {
            complete = true;
            return null;
        }
        resetIfMoved(bot);
        if (steps % SHARED_SIGHT_RECHECK_STEPS == 0) {
            Sighting remembered = sharedSight(bot);
            if (remembered != null) {
                return remembered;
            }
        }
        steps++;
        int range = Math.max(1, ObservableWorldQuery.visibleRangeBlocks(bot) - 1);
        // Exact vertical targets have no meaningful azimuth. Probe both axes every slice so an
        // overhead/underfoot block is not left between finite angular bands.
        Sighting vertical = targetSighting(ObservableWorldQuery.castSightRay(bot, 0.0D, 1.0D, 0.0D,
                range, ObservableWorldQuery.ViewShape.OUTLINE, null));
        if (vertical != null) {
            return vertical;
        }
        Sighting downward = targetSighting(ObservableWorldQuery.castSightRay(bot, 0.0D, -1.0D, 0.0D,
                range, ObservableWorldQuery.ViewShape.OUTLINE, null));
        if (downward != null) {
            return downward;
        }
        for (int budget = 2; budget < RAYS_PER_STEP && !complete; budget++) {
            // A band-at-a-time raster starves steep targets for many ticks. This persistent
            // 2-D permutation samples every elevation band in each batch and spreads each band
            // around the full ring; the second phase shifts the sub-cell ray origin to avoid a
            // target repeatedly landing on the same lattice seam.
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
            Sighting sighting = targetSighting(hit);
            if (sighting != null) {
                return sighting;
            }
        }
        return null;
    }

    private Sighting sharedSight(AIPlayerEntity bot) {
        List<SharedWorldSight.Observation> observations = SharedWorldSight.knownBlocks(bot,
                targetBlocks, SHARED_SIGHT_LIMIT);
        for (SharedWorldSight.Observation observation : observations) {
            BlockPos pos = observation.pos();
            // Memory can nominate a cell, never authorize a state read or a route by itself.
            if (!ObservableWorldQuery.canObserveBlock(bot, pos)) {
                continue;
            }
            if (targetBlocks.contains(bot.level().getBlockState(pos).getBlock())) {
                return new Sighting(pos, raysCast);
            }
        }
        return null;
    }

    /**
     * Converts one already cast sight ray into a target sighting without any extra read. The ray passes through foliage,
     * fences, glass and water, so the target may be the block behind them or one of those cells itself (a plant, a vine,
     * a cobweb): the nearest cell of the ray that is a target block wins.
     */
    private Sighting targetSighting(ObservableWorldQuery.ViewHit hit) {
        raysCast++;
        for (SightClipContext.Crossing crossing : hit.crossed()) {
            if (targetBlocks.contains(crossing.state().getBlock())) {
                return new Sighting(crossing.pos(), raysCast);
            }
        }
        if (hit.hit() && hit.pos() != null && hit.state() != null
                && targetBlocks.contains(hit.state().getBlock())) {
            return new Sighting(hit.pos(), raysCast);
        }
        return null;
    }

    private void advance() {
        sampleCursor++;
        if (sampleCursor >= TOTAL_SAMPLES) {
            complete = true;
        }
    }

    /** A ray lattice belongs to one physical view; do not skip old directions after relocation. */
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
    }

    private static double elevation(int sample, double subcellOffset) {
        return ELEVATION_MIN + (ELEVATION_MAX - ELEVATION_MIN)
                * (sample + subcellOffset) / ELEVATION_SAMPLES;
    }

    /** Alternates around the horizon so each batch sees low, level, and high targets early. */
    private static int centeredElevationSample(int slot) {
        int lowerMiddle = ELEVATION_SAMPLES / 2 - 1;
        return (slot & 1) == 0
                ? lowerMiddle - slot / 2
                : lowerMiddle + (slot + 1) / 2;
    }
}

package io.github.zoyluo.minecraftai.mining.assist;

import net.minecraft.util.math.BlockPos;

import java.util.function.LongPredicate;

/**
 * The pure core of the view sweeper (mining-assist design 3.3). It owns everything that happens to a
 * ray after the world has answered: the free-length ring, the observed-occupancy DDA, hazard
 * reconciliation, the folds into the sighting ledger, hazard field and POI window, the OUTLINE decor
 * pass, sweep pacing and the breakthrough rate. The only world access is the {@link RayProbe}, which the
 * adapter {@code ViewSweeper} implements with {@code ObservableWorldQuery.castViewRay}, so this class
 * can be driven by a synthetic voxel world in unit tests and can never read a block by itself.
 *
 * <p>Ray order comes from the per-sweep rotation of the 2048-direction lattice ({@code SphereSchedule}),
 * so any partial sweep is spatially uniform; the sweep does not restart on movement, it only restarts
 * on a breakthrough. Server thread only.</p>
 */
public final class SweepEngine {
    /** Extra reach of the decor re-cast beyond the collider hit, design 3.3 step 5. */
    public static final double DECOR_EPSILON = 0.01D;

    private SweepEngine() {
    }

    /** The eye's block cell as a packed long: the ring stamps it and the openness query compares against it. */
    public static long eyeCell(double eyeX, double eyeY, double eyeZ) {
        return BlockPos.asLong((int) Math.floor(eyeX), (int) Math.floor(eyeY), (int) Math.floor(eyeZ));
    }

    /** The one world question the engine asks: cast a view ray from the bot's eye and report the first hit. */
    @FunctionalInterface
    public interface RayProbe {
        /**
         * @param outline false for a COLLIDER ray, true for the OUTLINE decor pass
         * @param range   the ray length in blocks, already at most the live perception radius
         */
        RayResult cast(double dx, double dy, double dz, double range, boolean outline);
    }

    /**
     * Answer to one ray. {@code unknown} means the ray was skipped (end chunk not loaded) and says
     * nothing about the world. On a miss {@code distance} is the clamped range; on a hit it is the
     * distance to the hit point. {@code fluid} is the hit state's lava or water, {@code fluidCell}
     * whether the hit state holds any fluid at all (drives the occupancy FLUID mark).
     */
    public record RayResult(boolean unknown, boolean hit, BlockPos pos, double distance,
                            BlockFacts facts, HazardField.Kind fluid, boolean fluidCell) {
        public static final RayResult UNKNOWN = new RayResult(true, false, null, -1.0D, null, null, false);

        public static RayResult miss(double range) {
            return new RayResult(false, false, null, range, null, null, false);
        }

        public static RayResult hit(BlockPos pos, double distance, BlockFacts facts,
                                    HazardField.Kind fluid, boolean fluidCell) {
            return new RayResult(false, true, pos, distance, facts, fluid, fluidCell);
        }
    }

    /**
     * Per-step inputs. {@code radius} is the LIVE perception radius (at least 1), the same value the
     * rays are clamped to and the openness is later normalised by.
     */
    public record Context(double eyeX, double eyeY, double eyeZ,
                          int feetX, int feetY, int feetZ,
                          double radius, int tick, String dimension, LongPredicate placedByBot) {
    }

    /**
     * Casts {@code rays} COLLIDER rays (plus one OUTLINE re-cast on every second one) and folds the
     * results into {@code state}.
     *
     * @return the number of COLLIDER rays cast this step, including skipped ones
     */
    public static int step(MiningAssistState state, Context ctx, RayProbe probe, int rays) {
        long stepStart = System.nanoTime();
        state.enterDimension(ctx.dimension());
        ObservedOccupancy occ = state.occupancy(ctx.feetX(), ctx.feetY(), ctx.feetZ());
        FreeRunStats ring = state.ring();
        HazardField hazards = state.hazards();
        SenseCounters counters = state.counters();
        long eyeCell = eyeCell(ctx.eyeX(), ctx.eyeY(), ctx.eyeZ());
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        long foldNanos = 0L;
        int cast = 0;

        for (int i = 0; i < rays; i++) {
            if (state.sweepComplete()) {
                state.completeSweep();
            }
            SphereSchedule.Sweep sweep = state.sweep();
            int visit = state.takeVisit();
            SphereSchedule.Dir dir = sweep.direction(visit);
            RayResult ray = probe.cast(dir.dx(), dir.dy(), dir.dz(), ctx.radius(), false);
            cast++;
            if (ray.unknown()) {
                counters.unknownRays++;
                continue;
            }

            long foldStart = System.nanoTime();
            ring.record(SphereSchedule.latticeIndex(visit), ray.distance(), dir.dy(), ctx.tick(), eyeCell);
            RayGrid.HitKind kind = !ray.hit() ? RayGrid.HitKind.MISS
                    : ray.fluidCell() ? RayGrid.HitKind.FLUID : RayGrid.HitKind.SOLID;
            RayGrid.mark(occ, ctx.eyeX(), ctx.eyeY(), ctx.eyeZ(), dir.dx(), dir.dy(), dir.dz(), ray.distance(), kind);
            if (ray.hit()) {
                if (ray.fluidCell()) {
                    occ.markFluid(ray.pos());
                } else {
                    occ.markSolid(ray.pos());
                }
            }
            if (hazards.count(HazardField.Kind.LAVA) + hazards.count(HazardField.Kind.WATER) > 0) {
                reconcileTraversedFluids(hazards, occ, cursor, ctx, dir, ray);
            }
            if (ray.hit()) {
                EvidenceFold.foldHit(state, ray.pos(), ray.facts(), ray.fluid(), ctx.placedByBot(), ctx.tick());
            }
            foldNanos += System.nanoTime() - foldStart;

            if (SenseBudget.decorRay(visit)) {
                castDecor(state, ctx, probe, dir, ray);
            }
        }

        if (hazards.count() > HazardField.CAP) {
            hazards.evictIfOver(new BlockPos(ctx.feetX(), ctx.feetY(), ctx.feetZ()));
        }
        state.maintain(ctx.tick());
        state.markSweepTick(ctx.tick());

        long elapsed = System.nanoTime() - stepStart;
        counters.steps++;
        counters.rays += cast;
        counters.foldNanos += foldNanos;
        counters.sweepNanos += elapsed;
        counters.maxStepNanos = Math.max(counters.maxStepNanos, elapsed);
        state.addLifetimeRays(cast);
        return cast;
    }

    /**
     * Lowest surface any fluid cell can have, in blocks above the cell floor. A flowing fluid of level 1
     * is one ninth of a block tall (a source is eight ninths, falling fluid a full block), and the ray
     * (FluidHandling.ANY) tests that partial-height shape, not the whole cell.
     */
    public static final double MIN_FLUID_HEIGHT = 1.0D / 9.0D;
    /** Proof height: strictly below {@link #MIN_FLUID_HEIGHT}, so rounding cannot turn a near miss into a proof. */
    static final double NO_FLUID_PROOF_HEIGHT = 0.10D;

    /**
     * True when a ray that passed through cell {@code (cx, cy, cz)} without hitting anything proves the cell
     * holds no fluid. A ray that only crosses the open top slab of a cell passes over a partial-height lava
     * or water surface without touching it, so passing through is not enough (invariant I1: unknown is not
     * absent). The proof needs the part of the ray inside the cell to reach at or below
     * {@value #NO_FLUID_PROOF_HEIGHT} above the cell floor: every fluid shape covers the whole cell
     * footprint up to at least {@link #MIN_FLUID_HEIGHT}, so such a ray would have struck it.
     *
     * @param range end of the ray in blocks (the hit distance, or the clamped range of a miss)
     */
    static boolean provesNoFluid(double ox, double oy, double oz, double dx, double dy, double dz,
                                 double range, int cx, int cy, int cz) {
        double lengthSquared = dx * dx + dy * dy + dz * dz;
        if (!(lengthSquared > 1.0E-18D) || !Double.isFinite(lengthSquared) || !(range > 0.0D)) {
            return false;
        }
        if (Math.abs(lengthSquared - 1.0D) > RayGrid.UNIT_TOLERANCE) {
            double inverse = 1.0D / Math.sqrt(lengthSquared);
            dx *= inverse;
            dy *= inverse;
            dz *= inverse;
        }
        double lo = 0.0D;
        double hi = range;
        if (dx > 0.0D) {
            lo = Math.max(lo, (cx - ox) / dx);
            hi = Math.min(hi, (cx + 1.0D - ox) / dx);
        } else if (dx < 0.0D) {
            lo = Math.max(lo, (cx + 1.0D - ox) / dx);
            hi = Math.min(hi, (cx - ox) / dx);
        } else if (ox < cx || ox > cx + 1.0D) {
            return false;
        }
        if (dy > 0.0D) {
            lo = Math.max(lo, (cy - oy) / dy);
            hi = Math.min(hi, (cy + 1.0D - oy) / dy);
        } else if (dy < 0.0D) {
            lo = Math.max(lo, (cy + 1.0D - oy) / dy);
            hi = Math.min(hi, (cy - oy) / dy);
        } else if (oy < cy || oy > cy + 1.0D) {
            return false;
        }
        if (dz > 0.0D) {
            lo = Math.max(lo, (cz - oz) / dz);
            hi = Math.min(hi, (cz + 1.0D - oz) / dz);
        } else if (dz < 0.0D) {
            lo = Math.max(lo, (cz + 1.0D - oz) / dz);
            hi = Math.min(hi, (cz - oz) / dz);
        } else if (oz < cz || oz > cz + 1.0D) {
            return false;
        }
        if (lo > hi) {
            // The DDA and this interval disagree by rounding: claim nothing.
            return false;
        }
        double lowestY = dy > 0.0D ? oy + dy * lo : dy < 0.0D ? oy + dy * hi : oy;
        return lowestY <= cy + NO_FLUID_PROOF_HEIGHT;
    }

    /**
     * Reconciles the remembered lava and water with a ray that travelled through cells before its hit (design
     * 3.3, invariant I15: lava leaves memory only by re-observation as non-fluid). A remembered fluid cell is
     * forgotten only when the ray {@linkplain #provesNoFluid proves} the cell holds no fluid. A cell the ray
     * merely grazed above a possible partial-height surface stays remembered, and its occupancy mark is put
     * back to FLUID (the mark pass has just written AIR over it). Only runs while the field holds a fluid
     * cell; the hit cell itself is left to {@link EvidenceFold#foldHit}, which read its state.
     */
    private static void reconcileTraversedFluids(HazardField hazards, ObservedOccupancy occ, BlockPos.Mutable cursor,
                                                 Context ctx, SphereSchedule.Dir dir, RayResult ray) {
        final boolean stopAtHit = ray.hit();
        final int hitX = stopAtHit ? ray.pos().getX() : 0;
        final int hitY = stopAtHit ? ray.pos().getY() : 0;
        final int hitZ = stopAtHit ? ray.pos().getZ() : 0;
        final int tick = ctx.tick();
        final double range = ray.distance();
        RayGrid.traverse(ctx.eyeX(), ctx.eyeY(), ctx.eyeZ(), dir.dx(), dir.dy(), dir.dz(), range,
                (x, y, z) -> {
                    if (stopAtHit && x == hitX && y == hitY && z == hitZ) {
                        return false;
                    }
                    cursor.set(x, y, z);
                    HazardField.Kind kind = hazards.kindAt(cursor);
                    if (kind != HazardField.Kind.LAVA && kind != HazardField.Kind.WATER) {
                        return true;
                    }
                    if (provesNoFluid(ctx.eyeX(), ctx.eyeY(), ctx.eyeZ(), dir.dx(), dir.dy(), dir.dz(),
                            range, x, y, z)) {
                        hazards.observeNotFluid(cursor, tick);
                    } else {
                        occ.markFluid(x, y, z);
                    }
                    return true;
                });
    }

    /**
     * The OUTLINE re-cast: stops just beyond the collider hit and, if it meets a different, nearer
     * block (rail, cobweb, torch, banner, sculk vein ...), records it as decor evidence.
     */
    private static void castDecor(MiningAssistState state, Context ctx, RayProbe probe,
                                  SphereSchedule.Dir dir, RayResult collider) {
        double range = collider.hit()
                ? Math.min(collider.distance() + DECOR_EPSILON, ctx.radius())
                : ctx.radius();
        RayResult outline = probe.cast(dir.dx(), dir.dy(), dir.dz(), range, true);
        state.counters().decorRays++;
        if (outline.unknown() || !outline.hit() || outline.facts() == null) {
            return;
        }
        if (collider.hit()
                && (outline.pos().equals(collider.pos()) || outline.distance() >= collider.distance())) {
            return;
        }
        EvidenceFold.foldDecor(state, outline.pos(), outline.facts(), ctx.placedByBot(), ctx.tick());
    }
}

package io.github.zoyluo.minecraftai.mining.assist;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongPredicate;
import net.minecraft.core.BlockPos;

import static io.github.zoyluo.minecraftai.mining.assist.AssistTestSupport.BOT;
import static io.github.zoyluo.minecraftai.mining.assist.AssistTestSupport.NOT_PLACED;
import static io.github.zoyluo.minecraftai.mining.assist.AssistTestSupport.OVERWORLD;
import static io.github.zoyluo.minecraftai.mining.assist.AssistTestSupport.facts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the pure sweep core with a synthetic voxel world (a fine-step ray march) so the ring, the
 * occupancy DDA, the folds, hazard reconciliation, decor pass and sweep pacing are exercised without
 * a Minecraft server.
 */
class SweepEngineTest {
    private static final double RADIUS = 16.0D;
    private static final double EYE_X = 0.5D;
    private static final double EYE_Y = 64.5D;
    private static final double EYE_Z = 0.5D;
    private static final int TICK = 1000;

    /** {@code height} is the fluid surface above the cell floor (1.0 = a full cell); rays above it pass over. */
    private record Cell(BlockFacts facts, HazardField.Kind fluid, boolean fluidCell, boolean decor, double height) {
    }

    /** A synthetic world: an optional bulk terrain predicate plus explicit special cells. */
    private static final class Voxels implements SweepEngine.RayProbe {
        interface Terrain {
            boolean solid(int x, int y, int z);
        }

        Terrain terrain = (x, y, z) -> false;
        final Map<Long, Cell> special = new HashMap<>();
        boolean unknown;
        int colliderCasts;
        int outlineCasts;

        void put(int x, int y, int z, BlockFacts facts) {
            special.put(BlockPos.asLong(x, y, z), new Cell(facts, null, false, false, 1.0D));
        }

        void putFluid(int x, int y, int z, BlockFacts facts, HazardField.Kind kind) {
            special.put(BlockPos.asLong(x, y, z), new Cell(facts, kind, true, false, 1.0D));
        }

        /** A fluid cell whose surface sits {@code height} above the cell floor, like a real source (8/9). */
        void putFluidSurface(int x, int y, int z, BlockFacts facts, HazardField.Kind kind, double height) {
            special.put(BlockPos.asLong(x, y, z), new Cell(facts, kind, true, false, height));
        }

        void putDecor(int x, int y, int z, BlockFacts facts) {
            special.put(BlockPos.asLong(x, y, z), new Cell(facts, null, false, true, 1.0D));
        }

        @Override
        public SweepEngine.RayResult cast(double dx, double dy, double dz, double range, boolean outline) {
            if (outline) {
                outlineCasts++;
            } else {
                colliderCasts++;
            }
            if (unknown) {
                return SweepEngine.RayResult.UNKNOWN;
            }
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double step = 0.02D;
            for (double t = 0.0D; t <= range; t += step) {
                double x = EYE_X + dx / length * t;
                double y = EYE_Y + dy / length * t;
                double z = EYE_Z + dz / length * t;
                int cx = (int) Math.floor(x);
                int cy = (int) Math.floor(y);
                int cz = (int) Math.floor(z);
                Cell cell = special.get(BlockPos.asLong(cx, cy, cz));
                if (cell != null) {
                    if (cell.decor() && !outline) {
                        continue;
                    }
                    if (cell.fluidCell() && y - cy > cell.height()) {
                        continue; // above the fluid surface: the ray passes over it
                    }
                    return SweepEngine.RayResult.hit(new BlockPos(cx, cy, cz), t, cell.facts(),
                            cell.fluid(), cell.fluidCell());
                }
                if (terrain.solid(cx, cy, cz)) {
                    return SweepEngine.RayResult.hit(new BlockPos(cx, cy, cz), t, facts("stone"), null, false);
                }
            }
            return SweepEngine.RayResult.miss(range);
        }
    }

    private static SweepEngine.Context context(int tick, LongPredicate placed) {
        return new SweepEngine.Context(EYE_X, EYE_Y, EYE_Z, 0, 64, 0, RADIUS, tick, OVERWORLD, placed);
    }

    private static int run(MiningAssistState state, Voxels world, int totalRays, int perStep, int tick) {
        int cast = 0;
        while (cast < totalRays) {
            cast += SweepEngine.step(state, context(tick, NOT_PLACED), world, Math.min(perStep, totalRays - cast));
        }
        return cast;
    }

    private static long eyeCell() {
        return SweepEngine.eyeCell(EYE_X, EYE_Y, EYE_Z);
    }

    // ---- pacing ----------------------------------------------------------------------------------

    @Test
    void stepCastsExactlyTheRequestedRaysAndCountsThem() {
        MiningAssistState state = new MiningAssistState(BOT);
        Voxels world = new Voxels();
        assertEquals(40, SweepEngine.step(state, context(TICK, NOT_PLACED), world, 40));
        assertEquals(40, world.colliderCasts);
        assertEquals(20, world.outlineCasts, "every second slice ray gets one OUTLINE re-cast");
        assertEquals(40, state.counters().rays);
        assertEquals(20, state.counters().decorRays);
        assertEquals(1, state.counters().steps);
        assertEquals(40, state.lifetimeRays());
        assertEquals(40, state.sweepCursor());
        assertEquals(TICK, state.lastSweepTick());
    }

    @Test
    void aFullSweepTakesTwoThousandFortyEightRaysThenTheNextRotationStarts() {
        MiningAssistState state = new MiningAssistState(BOT);
        Voxels world = new Voxels();
        run(state, world, SphereSchedule.LATTICE_SIZE, 64, TICK);
        assertEquals(SphereSchedule.LATTICE_SIZE, state.sweepCursor());
        assertTrue(state.sweepComplete());
        assertEquals(0, state.sweepIndex());
        SweepEngine.step(state, context(TICK + 1, NOT_PLACED), world, 1);
        assertEquals(1, state.sweepIndex());
        assertEquals(1, state.sweepCursor());
        assertEquals(1, state.counters().sweepsCompleted);
    }

    @Test
    void everyLatticeSlotIsWrittenExactlyOncePerSweep() {
        MiningAssistState state = new MiningAssistState(BOT);
        run(state, new Voxels(), SphereSchedule.LATTICE_SIZE, 100, TICK);
        assertEquals(SphereSchedule.LATTICE_SIZE, state.ring().validCount(TICK, eyeCell()));
    }

    // ---- openness ring ---------------------------------------------------------------------------

    @Test
    void anOpenCavernReadsAsFullyOpenOnceEnoughRaysHaveLanded() {
        MiningAssistState state = new MiningAssistState(BOT);
        run(state, new Voxels(), FreeRunStats.MIN_VALID_ENTRIES + 40, 40, TICK);
        FreeRunStats.Openness openness = state.ring().openness(TICK, eyeCell(), RADIUS);
        assertTrue(openness.valid());
        assertEquals(1.0D, openness.fraction(), 0.01D);
        assertEquals(RADIUS, openness.upFree(), 0.5D);
        assertEquals(1.0D, openness.cavernC(), 0.05D);
    }

    @Test
    void opennessIsNotValidBeforeSixHundredFortyEntries() {
        MiningAssistState state = new MiningAssistState(BOT);
        run(state, new Voxels(), FreeRunStats.MIN_VALID_ENTRIES - 1, 40, TICK);
        assertFalse(state.ring().openness(TICK, eyeCell(), RADIUS).valid());
    }

    @Test
    void aNarrowTunnelStaysFarBelowTheCavernFloor() {
        MiningAssistState state = new MiningAssistState(BOT);
        Voxels world = new Voxels();
        world.terrain = (x, y, z) -> !(y == 64 && z == 0 && x >= -100 && x <= 100);
        run(state, world, SphereSchedule.LATTICE_SIZE, 64, TICK);
        FreeRunStats.Openness openness = state.ring().openness(TICK, eyeCell(), RADIUS);
        assertTrue(openness.valid());
        assertTrue(openness.fraction() < FreeRunStats.CAVERN_FRACTION_FLOOR / 2,
                "tunnel fraction " + openness.fraction());
        assertEquals(0.0D, openness.cavernC(), 1.0e-9D);
    }

    @Test
    void aSolidBoxAroundTheEyeIsNearlyZeroVolume() {
        MiningAssistState state = new MiningAssistState(BOT);
        Voxels world = new Voxels();
        world.terrain = (x, y, z) -> !(x == 0 && y == 64 && z == 0);
        run(state, world, SphereSchedule.LATTICE_SIZE, 64, TICK);
        assertTrue(state.ring().openness(TICK, eyeCell(), RADIUS).fraction() < 0.001D);
    }

    @Test
    void theRingUsesTheClampedRangeForAMiss() {
        MiningAssistState state = new MiningAssistState(BOT);
        run(state, new Voxels(), 700, 100, TICK);
        assertEquals(RADIUS, state.ring().meanFreeLength(TICK, eyeCell(), RADIUS), 0.01D);
    }

    @Test
    void skippedRaysAreNeverRecordedAsFreeSpace() {
        MiningAssistState state = new MiningAssistState(BOT);
        Voxels world = new Voxels();
        world.unknown = true;
        run(state, world, 200, 40, TICK);
        assertEquals(0, state.ring().validCount(TICK, eyeCell()));
        assertEquals(200, state.counters().unknownRays);
        assertEquals(200, state.counters().rays);
        assertEquals(ObservedOccupancy.UNKNOWN, state.occupancyIfPresent().get(1, 64, 0));
    }

    // ---- occupancy -------------------------------------------------------------------------------

    @Test
    void raysMarkTraversedCellsAirAndTheFirstHitSolid() {
        MiningAssistState state = new MiningAssistState(BOT);
        Voxels world = new Voxels();
        world.terrain = (x, y, z) -> x >= 5;
        run(state, world, SphereSchedule.LATTICE_SIZE, 64, TICK);
        ObservedOccupancy occ = state.occupancyIfPresent();
        int solid = 0;
        int air = 0;
        for (int x = occ.minX(); x <= occ.maxX(); x++) {
            for (int y = 48; y <= 80; y++) {
                for (int z = -16; z <= 16; z++) {
                    int cell = occ.get(x, y, z);
                    if (cell == ObservedOccupancy.SOLID) {
                        solid++;
                        assertTrue(x >= 5, "a SOLID mark at x=" + x);
                    } else if (cell == ObservedOccupancy.AIR) {
                        air++;
                        assertTrue(x <= 5, "an AIR mark at x=" + x);
                    }
                }
            }
        }
        assertTrue(solid > 20, "solid cells marked: " + solid);
        assertTrue(air > 200, "air cells marked: " + air);
        assertEquals(ObservedOccupancy.AIR, occ.get(1, 64, 0));
        assertEquals(ObservedOccupancy.AIR, occ.get(3, 64, 0));
    }

    @Test
    void aFluidHitMarksTheCellFluidNotSolid() {
        MiningAssistState state = new MiningAssistState(BOT);
        Voxels world = new Voxels();
        world.putFluid(4, 64, 0, facts("water"), HazardField.Kind.WATER);
        run(state, world, SphereSchedule.LATTICE_SIZE, 64, TICK);
        assertEquals(ObservedOccupancy.FLUID, state.occupancyIfPresent().get(4, 64, 0));
    }

    // ---- valuables -------------------------------------------------------------------------------

    @Test
    void aValuableInSightEndsUpInTheSightingLedger() {
        MiningAssistState state = new MiningAssistState(BOT);
        Voxels world = new Voxels();
        for (int x = 4; x <= 6; x++) {
            for (int y = 63; y <= 65; y++) {
                for (int z = -1; z <= 1; z++) {
                    world.put(x, y, z, facts("diamond_ore"));
                }
            }
        }
        run(state, world, SphereSchedule.LATTICE_SIZE, 64, TICK);
        assertTrue(state.sightings().size() >= 9, "sightings: " + state.sightings().size());
        SightingLedger.Sighting best = state.sightings().snapshotSortedByValueDesc().get(0);
        assertEquals(100, best.rawValue());
        assertEquals("diamond_ore", best.blockId());
        assertTrue(state.counters().sightingsAdded >= 9);
    }

    @Test
    void aMinedValuableIsMarkedGoneWhenTheCellIsSeenAsSomethingElse() {
        MiningAssistState state = new MiningAssistState(BOT);
        Voxels world = new Voxels();
        for (int y = 63; y <= 65; y++) {
            for (int z = -1; z <= 1; z++) {
                world.put(4, y, z, facts("gold_ore"));
            }
        }
        run(state, world, SphereSchedule.LATTICE_SIZE, 64, TICK);
        assertFalse(state.sightings().isEmpty());
        world.special.clear();
        world.terrain = (x, y, z) -> x == 4 && y >= 63 && y <= 65 && z >= -1 && z <= 1;
        run(state, world, SphereSchedule.LATTICE_SIZE, 64, TICK + 100);
        assertTrue(state.sightings().isEmpty(), "sightings left: " + state.sightings().size());
    }

    // ---- hazards ---------------------------------------------------------------------------------

    @Test
    void lavaInSightIsRememberedThenForgottenOnlyByAReobservationOfNonFluid() {
        MiningAssistState state = new MiningAssistState(BOT);
        Voxels world = new Voxels();
        for (int y = 63; y <= 65; y++) {
            for (int z = -1; z <= 1; z++) {
                world.putFluid(3, y, z, facts("lava"), HazardField.Kind.LAVA);
            }
        }
        run(state, world, SphereSchedule.LATTICE_SIZE, 64, TICK);
        int remembered = state.hazards().count(HazardField.Kind.LAVA);
        assertTrue(remembered >= 5, "lava cells remembered: " + remembered);
        assertTrue(state.hazards().anyLavaWithin(new BlockPos(3, 64, 0), 2));

        // Much later, with nothing observing the wall: lava never ages out by time.
        state.maintain(TICK + 1_000_000);
        assertEquals(remembered, state.hazards().count(HazardField.Kind.LAVA));

        // The lava is gone (drained or cooled): rays now pass through those cells.
        world.special.clear();
        run(state, world, SphereSchedule.LATTICE_SIZE, 64, TICK + 1_000_100);
        assertEquals(0, state.hazards().count(HazardField.Kind.LAVA),
                "lava cells left: " + state.hazards().count(HazardField.Kind.LAVA));
    }

    @Test
    void lavaBeyondThePerceptionRadiusIsNotForgottenBySweepsThatNeverReachIt() {
        MiningAssistState state = new MiningAssistState(BOT);
        state.hazards().observe(new BlockPos(30, 64, 0), HazardField.Kind.LAVA, 1);
        Voxels world = new Voxels();
        run(state, world, SphereSchedule.LATTICE_SIZE, 64, TICK);
        assertTrue(state.hazards().isLava(new BlockPos(30, 64, 0)),
                "the cell is beyond the perception radius, so nothing was re-observed");
    }

    @Test
    void trapBlocksHitByAColliderRayAreRemembered() {
        MiningAssistState state = new MiningAssistState(BOT);
        Voxels world = new Voxels();
        for (int y = 63; y <= 65; y++) {
            for (int z = -1; z <= 1; z++) {
                world.put(4, y, z, facts("tnt"));
            }
        }
        run(state, world, SphereSchedule.LATTICE_SIZE, 64, TICK);
        assertTrue(state.hazards().count(HazardField.Kind.TRAP) >= 5);
    }

    // ---- POI evidence and decor ------------------------------------------------------------------

    @Test
    void nonNaturalFirstHitsBecomePoiEvidenceAndNaturalOnesDoNot() {
        MiningAssistState state = new MiningAssistState(BOT);
        Voxels world = new Voxels();
        world.terrain = (x, y, z) -> x >= 8;
        for (int y = 63; y <= 65; y++) {
            for (int z = -1; z <= 1; z++) {
                world.put(6, y, z, facts("oak_planks"));
            }
        }
        run(state, world, SphereSchedule.LATTICE_SIZE, 64, TICK);
        assertTrue(state.poiWindow().structuralSize() >= 5);
        assertTrue(state.poiWindow().structuralEntries().stream()
                .allMatch(e -> e.bucket() == PoiBucket.WOOD_BUILD && !e.viaDecor()));
        assertEquals(0, state.poiWindow().flagOnlySize());
    }

    @Test
    void outlineOnlyDecorIsSeenThroughTheDecorPassAndMarkedViaDecor() {
        MiningAssistState state = new MiningAssistState(BOT);
        Voxels world = new Voxels();
        for (int y = 63; y <= 65; y++) {
            for (int z = -1; z <= 1; z++) {
                world.putDecor(4, y, z, facts("cobweb"));
            }
        }
        run(state, world, SphereSchedule.LATTICE_SIZE, 64, TICK);
        assertTrue(state.poiWindow().structuralSize() >= 3, "decor cells: " + state.poiWindow().structuralSize());
        assertTrue(state.poiWindow().structuralEntries().stream().allMatch(PoiEvidenceWindow.Entry::viaDecor));
        assertTrue(state.counters().decorEvidence >= 3);
        assertTrue(state.hazards().isEmpty());
        assertTrue(state.sightings().isEmpty());
    }

    @Test
    void theDecorPassRunsOnHalfTheRaysOnly() {
        MiningAssistState state = new MiningAssistState(BOT);
        Voxels world = new Voxels();
        run(state, world, SphereSchedule.LATTICE_SIZE, 64, TICK);
        assertEquals(SphereSchedule.LATTICE_SIZE, world.colliderCasts);
        assertEquals(SphereSchedule.LATTICE_SIZE / 2, world.outlineCasts);
    }

    @Test
    void aDecorHitBehindTheColliderHitIsIgnored() {
        MiningAssistState state = new MiningAssistState(BOT);
        Voxels world = new Voxels();
        world.terrain = (x, y, z) -> x >= 3;
        for (int y = 63; y <= 65; y++) {
            for (int z = -1; z <= 1; z++) {
                world.putDecor(8, y, z, facts("cobweb"));
            }
        }
        run(state, world, SphereSchedule.LATTICE_SIZE, 64, TICK);
        assertTrue(state.poiWindow().isEmpty(), "the web is behind solid stone and must stay unseen");
    }

    @Test
    void botPlacedCellsAreNotRecordedAsEvidence() {
        MiningAssistState state = new MiningAssistState(BOT);
        Voxels world = new Voxels();
        for (int y = 63; y <= 65; y++) {
            for (int z = -1; z <= 1; z++) {
                world.put(5, y, z, facts("torch"));
                world.putDecor(4, y, z, facts("rail"));
            }
        }
        LongPredicate allPlaced = packed -> true;
        int cast = 0;
        while (cast < SphereSchedule.LATTICE_SIZE) {
            cast += SweepEngine.step(state, context(TICK, allPlaced), world, 64);
        }
        assertTrue(state.poiWindow().isEmpty());
    }

    // ---- dimension and determinism ---------------------------------------------------------------

    @Test
    void changingDimensionDropsWhatWasRemembered() {
        MiningAssistState state = new MiningAssistState(BOT);
        Voxels world = new Voxels();
        for (int y = 63; y <= 65; y++) {
            for (int z = -1; z <= 1; z++) {
                world.put(4, y, z, facts("diamond_ore"));
            }
        }
        run(state, world, SphereSchedule.LATTICE_SIZE, 64, TICK);
        assertFalse(state.sightings().isEmpty());
        SweepEngine.Context nether = new SweepEngine.Context(EYE_X, EYE_Y, EYE_Z, 0, 64, 0, RADIUS, TICK + 5,
                "minecraft:the_nether", NOT_PLACED);
        SweepEngine.step(state, nether, new Voxels(), 8);
        assertTrue(state.sightings().isEmpty());
        assertEquals("minecraft:the_nether", state.dimensionKey());
    }

    @Test
    void theSameBotAndWorldGiveTheSameRingEveryTime() {
        double[] volumes = new double[2];
        for (int run = 0; run < 2; run++) {
            MiningAssistState state = new MiningAssistState(BOT);
            Voxels world = new Voxels();
            world.terrain = (x, y, z) -> y >= 68 || y <= 61 || x >= 7;
            run(state, world, SphereSchedule.LATTICE_SIZE, 50, TICK);
            volumes[run] = state.ring().volume(TICK, eyeCell(), RADIUS);
        }
        assertEquals(volumes[0], volumes[1], 0.0D);
        assertTrue(volumes[0] > 0.0D);
    }

    @Test
    void theOccupancyWindowIsRecentredWhenTheBotWalksAway() {
        MiningAssistState state = new MiningAssistState(BOT);
        Voxels world = new Voxels();
        SweepEngine.step(state, context(TICK, NOT_PLACED), world, 16);
        assertEquals(0, state.occupancyIfPresent().centreX());
        SweepEngine.Context moved = new SweepEngine.Context(EYE_X + 20, EYE_Y, EYE_Z, 20, 64, 0, RADIUS, TICK + 1,
                OVERWORLD, NOT_PLACED);
        SweepEngine.step(state, moved, world, 16);
        assertEquals(20, state.occupancyIfPresent().centreX());
    }

    // ---- partial-height fluids (a ray that passes over a surface proves nothing) ---------------------

    @Test
    void aRayThatOnlyCrossesTheTopOfACellDoesNotProveItHoldsNoFluid() {
        // Horizontal ray at y=65.6 through cell (5,65): it stays 0.6 above the floor, over any fluid surface.
        assertFalse(SweepEngine.provesNoFluid(0.5D, 65.6D, 0.5D, 1.0D, 0.0D, 0.0D, 16.0D, 5, 65, 0));
        // A shallow descent that leaves the cell still 0.5 above its floor.
        double slope = Math.sqrt(1.0D - 0.05D * 0.05D);
        assertFalse(SweepEngine.provesNoFluid(0.5D, 64.5D, 0.5D, slope, -0.05D, 0.0D, 16.0D, 5, 64, 0));
        // A ray that ends (a miss) inside the cell high above its floor.
        assertFalse(SweepEngine.provesNoFluid(0.5D, 65.6D, 0.5D, 1.0D, 0.0D, 0.0D, 5.2D, 5, 65, 0));
    }

    @Test
    void aRayThatReachesTheFloorRegionOfACellProvesItHoldsNoFluid() {
        // Straight down through the cell: it reaches the floor, below every possible fluid surface (min 1/9).
        assertTrue(SweepEngine.provesNoFluid(5.5D, 68.5D, 0.5D, 0.0D, -1.0D, 0.0D, 16.0D, 5, 65, 0));
        // Steeply descending: enters at the top, leaves through the floor.
        double n = Math.sqrt(0.2D * 0.2D + 0.98D * 0.98D);
        assertTrue(SweepEngine.provesNoFluid(4.5D, 66.5D, 0.5D, 0.2D / n, -0.98D / n, 0.0D, 16.0D, 5, 64, 0));
        // Ascending: it enters a cell through the floor, so the lowest point is the floor itself.
        assertTrue(SweepEngine.provesNoFluid(5.5D, 60.5D, 0.5D, 0.0D, 1.0D, 0.0D, 16.0D, 5, 63, 0));
        // Horizontal ray whose height sits within the lowest tenth of the cell: below any fluid surface.
        assertTrue(SweepEngine.provesNoFluid(0.5D, 64.05D, 0.5D, 1.0D, 0.0D, 0.0D, 16.0D, 5, 64, 0));
    }

    @Test
    void provingNothingWhenTheDirectionIsDegenerateOrTheCellIsNotOnTheRay() {
        assertFalse(SweepEngine.provesNoFluid(0.5D, 64.5D, 0.5D, 0.0D, 0.0D, 0.0D, 16.0D, 0, 64, 0));
        assertFalse(SweepEngine.provesNoFluid(0.5D, 64.5D, 0.5D, 1.0D, 0.0D, 0.0D, 16.0D, 5, 64, 3),
                "a ray that never enters the cell proves nothing about it");
        assertFalse(SweepEngine.provesNoFluid(0.5D, 64.05D, 0.5D, 1.0D, 0.0D, 0.0D, 0.0D, 5, 64, 0));
    }

    @Test
    void grazingRaysNeverMakeAnObservedLavaLakeFlicker() {
        MiningAssistState state = new MiningAssistState(BOT);
        Voxels world = new Voxels();
        // A lava lake, one layer below the eye's cell, with real source height (8/9).
        for (int x = 3; x <= 14; x++) {
            for (int z = -8; z <= 8; z++) {
                world.putFluidSurface(x, 63, z, facts("lava"), HazardField.Kind.LAVA, 8.0D / 9.0D);
            }
        }
        java.util.Set<Long> remembered = new java.util.HashSet<>();
        int steps = 3 * (SphereSchedule.LATTICE_SIZE / 32);
        for (int i = 0; i < steps; i++) {
            SweepEngine.step(state, context(TICK, NOT_PLACED), world, 32);
            for (long packed : remembered) {
                assertTrue(state.hazards().isLava(BlockPos.of(packed)),
                        "lava cell " + BlockPos.of(packed) + " was forgotten while the lava is still there (step " + i + ")");
            }
            for (HazardField.Cell cell : state.hazards().snapshot()) {
                remembered.add(cell.pos().asLong());
            }
        }
        assertTrue(remembered.size() > 40, "the lake must have been seen: " + remembered.size());
    }

    @Test
    void aRememberedLavaCellStaysFluidInTheOccupancyWhenOnlyGrazedAbove() {
        MiningAssistState state = new MiningAssistState(BOT);
        Voxels world = new Voxels();
        for (int x = 3; x <= 14; x++) {
            for (int z = -8; z <= 8; z++) {
                world.putFluidSurface(x, 63, z, facts("lava"), HazardField.Kind.LAVA, 8.0D / 9.0D);
            }
        }
        run(state, world, 2 * SphereSchedule.LATTICE_SIZE, 64, TICK);
        ObservedOccupancy occ = state.occupancyIfPresent();
        int checked = 0;
        for (HazardField.Cell cell : state.hazards().snapshot()) {
            if (cell.kind() == HazardField.Kind.LAVA && occ.inWindow(cell.pos())) {
                checked++;
                assertEquals(ObservedOccupancy.FLUID, occ.get(cell.pos()),
                        "remembered lava at " + cell.pos() + " must not read as air");
            }
        }
        assertTrue(checked > 40, "lava cells checked: " + checked);
    }

    @Test
    void drainedLavaIsForgottenOnceRaysProveTheCellsEmpty() {
        MiningAssistState state = new MiningAssistState(BOT);
        Voxels world = new Voxels();
        for (int x = 3; x <= 8; x++) {
            for (int z = -3; z <= 3; z++) {
                world.putFluidSurface(x, 63, z, facts("lava"), HazardField.Kind.LAVA, 8.0D / 9.0D);
            }
        }
        run(state, world, SphereSchedule.LATTICE_SIZE, 64, TICK);
        assertTrue(state.hazards().count(HazardField.Kind.LAVA) > 10);

        world.special.clear(); // the lake drained
        // Rays that dive into the cells (or reach their floors) prove them empty; the shallow grazers do not.
        run(state, world, 4 * SphereSchedule.LATTICE_SIZE, 64, TICK + 1);
        assertEquals(0, state.hazards().count(HazardField.Kind.LAVA),
                "lava cells left after four sweeps over drained cells: " + state.hazards().count(HazardField.Kind.LAVA));
    }

    // ---- recall (design 10, P0 done-when: at least 90% of single-face ores within 8 blocks after 2 sweeps) ---

    @Test
    void twoSweepsFindAtLeastNinetyPercentOfSingleFaceOresWithinEightBlocks() {
        int found = 0;
        int total = 0;
        for (long seed = 1; seed <= 6; seed++) {
            java.util.Random random = new java.util.Random(seed * 7919L);
            java.util.List<BlockPos> wall = new java.util.ArrayList<>();
            for (int x = -6; x <= 6; x++) {
                for (int y = 59; y <= 69; y++) {
                    for (int z = -6; z <= 6; z++) {
                        boolean inside = Math.abs(x) <= 5 && Math.abs(z) <= 5 && y >= 60 && y <= 68;
                        boolean touchesAir = (Math.abs(x) == 6 && Math.abs(z) <= 5 && y >= 60 && y <= 68)
                                || (Math.abs(z) == 6 && Math.abs(x) <= 5 && y >= 60 && y <= 68)
                                || ((y == 59 || y == 69) && Math.abs(x) <= 5 && Math.abs(z) <= 5);
                        if (inside || !touchesAir) {
                            continue;
                        }
                        double dx = x + 0.5D - EYE_X;
                        double dy = y + 0.5D - EYE_Y;
                        double dz = z + 0.5D - EYE_Z;
                        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        if (distance >= 2.0D && distance <= 8.0D) {
                            wall.add(new BlockPos(x, y, z));
                        }
                    }
                }
            }
            java.util.Collections.shuffle(wall, random);
            java.util.List<BlockPos> ores = wall.subList(0, Math.min(40, wall.size()));

            MiningAssistState state = new MiningAssistState(BOT);
            Voxels world = new Voxels();
            world.terrain = (x, y, z) -> !(Math.abs(x) <= 5 && Math.abs(z) <= 5 && y >= 60 && y <= 68);
            for (BlockPos ore : ores) {
                world.put(ore.getX(), ore.getY(), ore.getZ(), facts("diamond_ore"));
            }
            run(state, world, 2 * SphereSchedule.LATTICE_SIZE, 64, TICK);
            java.util.Set<BlockPos> sighted = new java.util.HashSet<>();
            for (SightingLedger.Sighting sighting : state.sightings().snapshotSortedByValueDesc()) {
                sighted.add(sighting.pos());
            }
            for (BlockPos ore : ores) {
                total++;
                if (sighted.contains(ore)) {
                    found++;
                }
            }
        }
        double recall = total == 0 ? 0.0D : (double) found / total;
        assertTrue(total >= 200, "ores placed: " + total);
        assertTrue(recall >= 0.90D, "recall " + found + "/" + total + " = " + recall);
    }

    @Test
    void hazardFieldIsBroughtBackWithinItsCapOncePerStep() {
        MiningAssistState state = new MiningAssistState(BOT);
        Voxels world = new Voxels();
        int overfill = HazardField.CAP + 200;
        for (int i = 0; i < overfill; i++) {
            state.hazards().observe(new BlockPos(1000 + i % 64, (i / 64) % 64, i / 4096), HazardField.Kind.WATER, 5);
        }
        assertEquals(overfill, state.hazards().count());
        SweepEngine.step(state, context(TICK, NOT_PLACED), world, 32);
        assertTrue(state.hazards().count() <= HazardField.CAP, "hazards: " + state.hazards().count());
        assertNotNull(state.hazards());
    }
}

package io.github.zoyluo.aibot.mining.assist;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.zoyluo.aibot.mining.assist.AssistTestSupport.BOT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningAssistStateTest {
    @Test
    void newStateIsEmptyAndAllocatesNoOccupancyWindow() {
        MiningAssistState state = new MiningAssistState(BOT);
        assertEquals(BOT, state.botId());
        assertNull(state.occupancyIfPresent(), "the 69 KB window is lazy");
        assertTrue(state.hazards().isEmpty());
        assertTrue(state.sightings().isEmpty());
        assertTrue(state.poiWindow().isEmpty());
        assertTrue(state.pendingBreaks().isEmpty());
        assertEquals(0, state.sweepIndex());
        assertEquals(0, state.sweepCursor());
        assertFalse(state.breakthroughActive());
        assertEquals(MiningAssistState.NEVER, state.lastSweepTick());
        assertEquals(PoiScorer.Band.NONE, state.lastPoiBand());
        assertEquals("", state.biomeId());
    }

    @Test
    void occupancyIsCreatedCentredOnTheFeetAndRecentredLater() {
        MiningAssistState state = new MiningAssistState(BOT);
        ObservedOccupancy first = state.occupancy(100, 20, -50);
        assertEquals(100, first.centreX());
        assertEquals(20, first.centreY());
        assertEquals(-50, first.centreZ());
        first.markSolid(101, 20, -50);
        ObservedOccupancy same = state.occupancy(103, 20, -50);
        assertSame(first, same);
        assertEquals(100, same.centreX(), "within the hysteresis margin: no recentre");
        assertEquals(ObservedOccupancy.SOLID, same.get(101, 20, -50));
        state.occupancy(120, 20, -50);
        assertEquals(120, first.centreX());
    }

    @Test
    void sweepRotationIsBuiltOncePerSweepIndexFromTheBotUuid() {
        MiningAssistState state = new MiningAssistState(BOT);
        SphereSchedule.Sweep zero = state.sweep();
        assertSame(zero, state.sweep());
        assertEquals(SphereSchedule.sweep(BOT.getMostSignificantBits(), BOT.getLeastSignificantBits(), 0).seed(),
                zero.seed());
        for (int i = 0; i < SphereSchedule.LATTICE_SIZE; i++) {
            assertFalse(state.sweepComplete());
            assertEquals(i, state.takeVisit());
        }
        assertTrue(state.sweepComplete());
        state.completeSweep();
        assertEquals(1, state.sweepIndex());
        assertEquals(0, state.sweepCursor());
        assertNotSame(zero, state.sweep());
        assertEquals(SphereSchedule.sweep(BOT.getMostSignificantBits(), BOT.getLeastSignificantBits(), 1).seed(),
                state.sweep().seed());
        assertEquals(1, state.counters().sweepsCompleted);
        assertEquals(1, state.lifetimeSweeps());
    }

    @Test
    void differentBotsGetDifferentRotations() {
        long a = new MiningAssistState(BOT).sweep().seed();
        long b = new MiningAssistState(new UUID(5L, 6L)).sweep().seed();
        assertTrue(a != b);
    }

    @Test
    void breakthroughRestartsAreSpacedByTheMinimumGapAndTheRunningSweepGoesOn() {
        MiningAssistState state = new MiningAssistState(BOT);
        assertTrue(state.requestBreakthrough(1000));
        assertEquals(1, state.sweepIndex());
        for (int i = 0; i < 50; i++) {
            state.takeVisit();
        }

        assertFalse(state.requestBreakthrough(1000 + MiningAssistState.MIN_BREAKTHROUGH_GAP_TICKS - 1),
                "inside the gap the restart is deferred");
        assertEquals(1, state.sweepIndex(), "the running sweep keeps its rotation");
        assertEquals(50, state.sweepCursor(), "and its progress");
        assertTrue(state.breakthroughActive());
        assertEquals(1, state.counters().breakthroughs);
        assertEquals(1, state.counters().breakthroughsDeferred);

        assertTrue(state.requestBreakthrough(1000 + MiningAssistState.MIN_BREAKTHROUGH_GAP_TICKS));
        assertEquals(2, state.sweepIndex());
        assertEquals(0, state.sweepCursor());
        assertEquals(2, state.counters().breakthroughs);
    }

    @Test
    void aTickThatMovedBackwardsNeverBlocksABreakthroughAndAResetForgetsTheGap() {
        MiningAssistState state = new MiningAssistState(BOT);
        assertTrue(state.requestBreakthrough(50_000));
        assertTrue(state.requestBreakthrough(10), "world reload: the clock restarted");
        state.resetObservations();
        assertTrue(state.requestBreakthrough(11), "dimension change: nothing to space against");
    }

    @Test
    void breakthroughRestartsTheSweepWithAFreshRotationAndEndsWithThatSweep() {
        MiningAssistState state = new MiningAssistState(BOT);
        long before = state.sweep().seed();
        for (int i = 0; i < 100; i++) {
            state.takeVisit();
        }
        state.requestBreakthrough();
        assertTrue(state.breakthroughActive());
        assertEquals(0, state.sweepCursor());
        assertEquals(1, state.sweepIndex());
        assertTrue(before != state.sweep().seed());
        assertEquals(1, state.counters().breakthroughs);
        for (int i = 0; i < SphereSchedule.LATTICE_SIZE; i++) {
            state.takeVisit();
        }
        state.completeSweep();
        assertFalse(state.breakthroughActive(), "raised rate lasts one sweep only");
        assertEquals(2, state.sweepIndex());
    }

    @Test
    void enteringANewDimensionDropsEveryObservation() {
        MiningAssistState state = new MiningAssistState(BOT);
        assertFalse(state.enterDimension("minecraft:overworld"), "first dimension is not a change");
        state.hazards().observe(new BlockPos(1, 2, 3), HazardField.Kind.LAVA, 5);
        state.sightings().observe(new BlockPos(4, 5, 6), "diamond_ore", 100, 5);
        state.poiWindow().observe(new BlockPos(7, 8, 9), PoiBucket.RAIL, 0, 5, false);
        state.pendingBreaks().offer(42L);
        state.occupancy(0, 0, 0).markSolid(1, 1, 1);
        state.ring().record(3, 8.0D, 5, 0L);
        assertFalse(state.enterDimension("minecraft:overworld"));
        assertFalse(state.hazards().isEmpty());

        assertTrue(state.enterDimension("minecraft:the_nether"));
        assertTrue(state.hazards().isEmpty());
        assertTrue(state.sightings().isEmpty());
        assertTrue(state.poiWindow().isEmpty());
        assertTrue(state.pendingBreaks().isEmpty());
        assertEquals(ObservedOccupancy.UNKNOWN, state.occupancyIfPresent().get(1, 1, 1));
        assertEquals(0, state.ring().validCount(6, 0L));
        assertEquals("minecraft:the_nether", state.dimensionKey());
    }

    @Test
    void biomeFlagsFollowTheFeetBiome() {
        MiningAssistState state = new MiningAssistState(BOT);
        state.setBiome("minecraft:deep_dark");
        assertTrue(state.deepDark());
        assertFalse(state.lush());
        state.setBiome("minecraft:lush_caves");
        assertFalse(state.deepDark());
        assertTrue(state.lush());
        state.setBiome(null);
        assertEquals("", state.biomeId());
        assertFalse(state.lush());
    }

    @Test
    void maintenanceRunsOnceEveryHundredTicksAndExpiresOldMemories() {
        MiningAssistState state = new MiningAssistState(BOT);
        state.hazards().observe(new BlockPos(0, 0, 0), HazardField.Kind.WATER, 0);
        state.hazards().observe(new BlockPos(1, 0, 0), HazardField.Kind.LAVA, 0);
        state.sightings().observe(new BlockPos(5, 5, 5), "iron_ore", 30, 0);
        state.poiWindow().observe(new BlockPos(9, 9, 9), PoiBucket.RAIL, 0, 0, false);

        assertTrue(state.maintain(0));
        assertFalse(state.maintain(50), "not due yet");
        assertFalse(state.maintain(99));
        assertTrue(state.maintain(100));
        assertEquals(2, state.hazards().count(), "nothing is old enough at tick 100");

        assertTrue(state.maintain(MiningAssistState.SIGHTING_MAX_AGE_TICKS + 500));
        assertEquals(1, state.hazards().count(), "water aged out, lava never does");
        assertTrue(state.hazards().isLava(new BlockPos(1, 0, 0)));
        assertTrue(state.sightings().isEmpty());
        assertTrue(state.poiWindow().isEmpty());
    }

    @Test
    void maintenanceTreatsABackwardsTickAsDue() {
        MiningAssistState state = new MiningAssistState(BOT);
        assertTrue(state.maintain(5000));
        assertTrue(state.maintain(10));
    }

    @Test
    void summaryWindowStartsItsClockOnTheFirstCallThenSwapsCounters() {
        MiningAssistState state = new MiningAssistState(BOT);
        state.counters().rays += 5;
        assertNull(state.drainWindow(1000, 1200));
        state.counters().rays += 7;
        assertNull(state.drainWindow(2199, 1200));
        SenseCounters window = state.drainWindow(2200, 1200);
        assertNotNull(window);
        assertEquals(12, window.rays);
        assertEquals(0, state.counters().rays, "a fresh window starts empty");
        assertNull(state.drainWindow(2300, 1200));
    }

    @Test
    void windowStartTickFollowsTheReportingClock() {
        MiningAssistState state = new MiningAssistState(BOT);
        assertEquals(MiningAssistState.NEVER, state.windowStartTick());
        state.drainWindow(1000, 1200);
        assertEquals(1000, state.windowStartTick(), "the first call starts the clock");
        state.drainWindow(2200, 1200);
        assertEquals(2200, state.windowStartTick());
    }

    @Test
    void takeWindowEndsTheWindowUnconditionallyEvenBeforeTheClockStarted() {
        MiningAssistState state = new MiningAssistState(BOT);
        state.counters().rays += 9;
        SenseCounters window = state.takeWindow(300);
        assertEquals(9, window.rays, "a short sensing session still yields its last window");
        assertEquals(0, state.counters().rays);
        assertEquals(300, state.windowStartTick());
    }

    @Test
    void theSensingStatusBelongsToTheStateAndSurvivesObservationResets() {
        MiningAssistState state = new MiningAssistState(BOT);
        state.status().sensed(50);
        state.resetObservations();
        assertTrue(state.status().sensing(), "a dimension change is not the end of a sensing session");
        assertEquals(50, state.status().lastSensedTick());
    }

    @Test
    void resetObservationsKeepsTheDimensionAndClearsTheRest() {
        MiningAssistState state = new MiningAssistState(BOT);
        state.enterDimension("minecraft:overworld");
        state.sightings().observe(new BlockPos(1, 1, 1), "gold_ore", 45, 1);
        state.takeVisit();
        state.requestBreakthrough();
        state.resetObservations();
        assertTrue(state.sightings().isEmpty());
        assertEquals(0, state.sweepCursor());
        assertFalse(state.breakthroughActive());
        assertEquals("minecraft:overworld", state.dimensionKey());
    }
}

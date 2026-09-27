package dev.spawnbotswrapper.inhabitants.mc;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TickScheduleTest {

    @Test
    void firstCallOnlyRecordsAStartingPoint() {
        TickSchedule schedule = new TickSchedule(() -> 10);
        assertFalse(schedule.due(500));
        assertFalse(schedule.due(509));
        assertTrue(schedule.due(510));
    }

    @Test
    void firesOncePerIntervalAndRestartsTheCycle() {
        TickSchedule schedule = new TickSchedule(() -> 5);
        schedule.due(0);
        int fired = 0;
        for (int tick = 1; tick <= 50; tick++) {
            if (schedule.due(tick)) {
                fired++;
                assertEquals(0, tick % 5, "fires exactly on multiples of the interval here");
            }
        }
        assertEquals(10, fired);
    }

    @Test
    void aLateCallStillFiresOnceAndDoesNotCatchUp() {
        TickSchedule schedule = new TickSchedule(() -> 10);
        schedule.due(0);
        assertTrue(schedule.due(1000));
        assertFalse(schedule.due(1001));
        assertFalse(schedule.due(1009));
        assertTrue(schedule.due(1010));
    }

    @Test
    void theIntervalIsReadOnEveryCallSoAReloadTakesEffectImmediately() {
        AtomicInteger interval = new AtomicInteger(1000);
        TickSchedule schedule = new TickSchedule(interval::get);
        schedule.due(0);
        assertFalse(schedule.due(100));
        interval.set(50);
        assertTrue(schedule.due(100), "already 100 ticks since the start, and the new interval is 50");
    }

    @Test
    void aTickCounterThatMovesBackwardsRestartsTheCycle() {
        TickSchedule schedule = new TickSchedule(() -> 10);
        schedule.due(5000);
        assertTrue(schedule.due(5010));
        assertFalse(schedule.due(3), "new server session: counter went backwards");
        assertFalse(schedule.due(12));
        assertTrue(schedule.due(13));
    }

    @Test
    void anIntervalBelowOneMeansEveryTick() {
        TickSchedule schedule = new TickSchedule(() -> 0);
        schedule.due(0);
        assertTrue(schedule.due(1));
        assertTrue(schedule.due(2));
        TickSchedule negative = new TickSchedule(() -> -7);
        negative.due(0);
        assertTrue(negative.due(1));
    }
}

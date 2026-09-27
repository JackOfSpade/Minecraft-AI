package dev.spawnbotswrapper.inhabitants.mc;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class McClockTest {

    @Test
    void ticksFollowTheServerCounterAndMillisTheWallClock() {
        AtomicInteger ticks = new AtomicInteger(0);
        AtomicLong millis = new AtomicLong(1_700_000_000_000L);
        McClock clock = new McClock(ticks::get, millis::get);
        assertEquals(0, clock.tick());
        ticks.set(1234);
        millis.addAndGet(60_000);
        assertEquals(1234, clock.tick());
        assertEquals(1_700_000_060_000L, clock.nowMillis());
    }

    @Test
    void theTickCounterIsWidenedWithoutOverflow() {
        McClock clock = new McClock(() -> Integer.MAX_VALUE, () -> 0L);
        assertEquals(Integer.MAX_VALUE, clock.tick());
        assertTrue(clock.tick() > 0);
    }

    @Test
    void theTickClockStandsStillWhenTheServerDoes() {
        AtomicInteger ticks = new AtomicInteger(77);
        McClock clock = new McClock(ticks::get, System::currentTimeMillis);
        long first = clock.tick();
        assertEquals(first, clock.tick(), "no server ticks, no game time: delays and timeouts pause with the server");
    }
}

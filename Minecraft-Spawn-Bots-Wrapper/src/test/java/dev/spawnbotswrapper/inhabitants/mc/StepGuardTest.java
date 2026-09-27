package dev.spawnbotswrapper.inhabitants.mc;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StepGuardTest {

    private final List<String> messages = new ArrayList<>();
    private final List<Throwable> causes = new ArrayList<>();

    private StepGuard guard(int reportEvery) {
        return new StepGuard((message, cause) -> {
            messages.add(message);
            causes.add(cause);
        }, reportEvery);
    }

    @Test
    void aStepThatWorksRunsOnceAndReportsNothing() {
        StepGuard guard = guard(10);
        int[] runs = {0};
        assertTrue(guard.run("step", () -> runs[0]++));
        assertEquals(1, runs[0]);
        assertTrue(messages.isEmpty());
        assertEquals(0, guard.failures("step"));
    }

    @Test
    void aFailingStepIsContainedAndReportedWithItsCause() {
        StepGuard guard = guard(10);
        IllegalStateException boom = new IllegalStateException("boom");
        assertFalse(guard.run("engine", () -> {
            throw boom;
        }));
        assertEquals(1, guard.failures("engine"));
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("'engine'"), messages.get(0));
        assertSame(boom, causes.get(0));
    }

    @Test
    void linkageErrorsFromAMissingUpstreamMemberAreContainedToo() {
        StepGuard guard = guard(10);
        assertFalse(guard.run("adapter", () -> {
            throw new NoSuchMethodError("upstream changed");
        }));
        assertEquals(1, guard.failures("adapter"));
    }

    @Test
    void jvmErrorsStillPropagate() {
        StepGuard guard = guard(10);
        assertThrows(OutOfMemoryError.class, () -> guard.run("step", () -> {
            throw new OutOfMemoryError("simulated");
        }));
        assertThrows(AssertionError.class, () -> guard.run("step", () -> {
            throw new AssertionError("simulated");
        }));
    }

    @Test
    void repeatedFailuresAreThrottledButAlwaysCounted() {
        StepGuard guard = guard(5);
        for (int i = 0; i < 12; i++) {
            guard.run("tick", () -> {
                throw new RuntimeException("every tick");
            });
        }
        assertEquals(12, guard.failures("tick"));
        // Reported: #1, #2, then every 5th (5, 10).
        assertEquals(4, messages.size(), messages.toString());
        assertFalse(messages.get(0).contains("failure #"));
        assertTrue(messages.get(1).contains("failure #2"));
        assertTrue(messages.get(2).contains("failure #5"));
        assertTrue(messages.get(3).contains("failure #10"));
    }

    @Test
    void stepsAreCountedIndependently() {
        StepGuard guard = guard(100);
        guard.run("a", () -> {
            throw new RuntimeException();
        });
        guard.run("b", () -> {
            throw new RuntimeException();
        });
        guard.run("b", () -> {
            throw new RuntimeException();
        });
        assertEquals(1, guard.failures("a"));
        assertEquals(2, guard.failures("b"));
        assertEquals(0, guard.failures("c"));
        assertEquals(3, messages.size(), "each step gets its own first reports");
    }

    @Test
    void reportCanBeUsedDirectlyForFailuresHandledByTheCaller() {
        StepGuard guard = guard(100);
        guard.report("save", new java.io.IOException("disk full"));
        assertEquals(1, guard.failures("save"));
        assertInstanceOf(java.io.IOException.class, causes.get(0));
    }

    @Test
    void aReportIntervalBelowOneIsTreatedAsOne() {
        StepGuard guard = guard(0);
        for (int i = 0; i < 4; i++) {
            guard.run("x", () -> {
                throw new RuntimeException();
            });
        }
        assertEquals(4, messages.size());
    }
}

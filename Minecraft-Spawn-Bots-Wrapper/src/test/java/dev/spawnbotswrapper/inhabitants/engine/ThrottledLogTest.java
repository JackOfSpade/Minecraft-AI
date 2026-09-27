package dev.spawnbotswrapper.inhabitants.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ThrottledLogTest {

    /** A logger that only remembers which severities were written. */
    private static final class Recorder {
        final List<String> written = new ArrayList<>();
        final Logger logger = (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(), new Class<?>[]{Logger.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("error") || method.getName().equals("warn")) {
                        written.add(method.getName());
                    }
                    return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                });
    }

    @Test
    void theFirstFailureOfASiteIsLoggedAndRepeatsAreSuppressedUntilTheIntervalPasses() {
        FakeClock clock = new FakeClock();
        Recorder rec = new Recorder();
        ThrottledLog log = new ThrottledLog(rec.logger, clock::tick);

        log.error("poll", "bot A", new IllegalStateException("boom"));
        assertEquals(1, rec.written.size());
        for (int i = 0; i < 5000; i++) {
            clock.tick++;
            log.error("poll", "bot B", new IllegalStateException("boom"));
        }
        assertEquals(1, rec.written.size(), "a bug that throws every tick must not flood the log");
        assertEquals(5001, log.errorCount(), "but every failure is counted");

        clock.tick = ThrottledLog.REPEAT_TICKS + 1;
        log.error("poll", "bot C", new IllegalStateException("boom"));
        assertEquals(2, rec.written.size());
    }

    @Test
    void differentSitesAreThrottledIndependently() {
        FakeClock clock = new FakeClock();
        Recorder rec = new Recorder();
        ThrottledLog log = new ThrottledLog(rec.logger, clock::tick);
        log.error("a", "x", new RuntimeException());
        log.error("b", "x", new RuntimeException());
        log.warn("c", "message");
        log.warn("c", "message");
        assertEquals(3, rec.written.size());
    }

    @Test
    void clearingTheThrottleLetsAStillFailingSiteReportAgain() {
        FakeClock clock = new FakeClock();
        Recorder rec = new Recorder();
        ThrottledLog log = new ThrottledLog(rec.logger, clock::tick);
        log.error("a", "x", new RuntimeException());
        log.error("a", "x", new RuntimeException());
        assertEquals(1, rec.written.size());
        log.clear();
        log.error("a", "x", new RuntimeException());
        assertEquals(2, rec.written.size());
    }
}

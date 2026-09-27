package dev.spawnbotswrapper.inhabitants.engine;

import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Logging for failures that can repeat every tick. The engine must survive a broken port forever, but a
 * bug that throws on every tick must not also flood the log: the first occurrence of each failure site is
 * logged in full (with stack trace), later ones at most once per {@link #REPEAT_TICKS} with the number
 * that were swallowed in between.
 * <p>
 * Sites are identified by a short constant tag, never by data such as a bot name, so the map stays tiny.
 */
final class ThrottledLog {
    /** Five minutes of server time. */
    static final long REPEAT_TICKS = 6000;

    private static final class Site {
        long lastLoggedTick;
        long suppressed;
    }

    private final Logger log;
    private final LongSupplier ticks;
    private final Map<String, Site> sites = new HashMap<>();
    private long errorCount;

    ThrottledLog(Logger log, LongSupplier ticks) {
        this.log = log;
        this.ticks = ticks;
    }

    /** Number of failures contained so far, logged or not. */
    long errorCount() {
        return errorCount;
    }

    void error(String site, String detail, Throwable t) {
        errorCount++;
        if (!due(site)) {
            return;
        }
        Site s = sites.get(site);
        if (s.suppressed > 0) {
            log.error("{} ({}) - {} similar failure(s) were suppressed", site, detail, s.suppressed, t);
        } else {
            log.error("{} ({})", site, detail, t);
        }
        s.suppressed = 0;
    }

    void warn(String site, String message) {
        if (!due(site)) {
            return;
        }
        Site s = sites.get(site);
        if (s.suppressed > 0) {
            log.warn("{} ({} similar message(s) suppressed)", message, s.suppressed);
        } else {
            log.warn("{}", message);
        }
        s.suppressed = 0;
    }

    /** True (and the site's clock restarts) when this occurrence should be written; else it is counted. */
    private boolean due(String site) {
        long now = ticks.getAsLong();
        Site s = sites.get(site);
        if (s == null) {
            s = new Site();
            s.lastLoggedTick = now;
            sites.put(site, s);
            return true;
        }
        if (now - s.lastLoggedTick >= REPEAT_TICKS) {
            s.lastLoggedTick = now;
            return true;
        }
        s.suppressed++;
        return false;
    }

    void clear() {
        sites.clear();
    }
}

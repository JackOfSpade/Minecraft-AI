package dev.spawnbotswrapper.inhabitants.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * The adapter's log front end. It exists for two reasons: reflection wraps every upstream failure in
 * {@link InvocationTargetException}, which is useless in a log, and a server that ticks 20 times a second
 * turns any "log on failure" into spam. So failures are unwrapped and reported once per distinct key.
 */
final class Diagnostics {

    /** Where messages go; a seam so tests can capture them. */
    interface Sink {
        void debug(String message);

        void info(String message);

        void warn(String message);

        void error(String message);
    }

    /** Distinct once-keys remembered; beyond this new ones are dropped so a hostile upstream cannot grow the set. */
    static final int MAX_ONCE_KEYS = 256;
    private static final int MAX_MESSAGE = 240;

    private final Sink sink;
    private final Set<String> seen = new HashSet<>();

    Diagnostics(Sink sink) {
        this.sink = sink;
    }

    static Sink slf4j(String loggerName) {
        Logger logger = LoggerFactory.getLogger(loggerName);
        return new Sink() {
            @Override
            public void debug(String message) {
                logger.debug(message);
            }

            @Override
            public void info(String message) {
                logger.info(message);
            }

            @Override
            public void warn(String message) {
                logger.warn(message);
            }

            @Override
            public void error(String message) {
                logger.error(message);
            }
        };
    }

    void debug(String message) {
        sink.debug(message);
    }

    void info(String message) {
        sink.info(message);
    }

    void warn(String message) {
        sink.warn(message);
    }

    void error(String message) {
        sink.error(message);
    }

    /** Logs {@code message} at debug level the first time {@code key} is seen. Returns whether it was logged. */
    boolean debugOnce(String key, String message) {
        if (firstTime(key)) {
            sink.debug(message);
            return true;
        }
        return false;
    }

    /** Logs {@code message} at warn level the first time {@code key} is seen. Returns whether it was logged. */
    boolean warnOnce(String key, String message) {
        if (firstTime(key)) {
            sink.warn(message);
            return true;
        }
        return false;
    }

    /** A failure at an upstream/reflection boundary: unwrapped, one warning per distinct (context, cause). */
    void failure(String context, Throwable t) {
        String cause = describe(t);
        warnOnce(context + "|" + cause, "PvP BOT integration: " + context + " failed (" + cause + ")");
    }

    private synchronized boolean firstTime(String key) {
        if (seen.size() >= MAX_ONCE_KEYS && !seen.contains(key)) {
            return false;
        }
        return seen.add(key);
    }

    /** Strips reflection/future wrappers so the real upstream problem is what gets reported. */
    static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        for (int depth = 0; depth < 8 && cur != null; depth++) {
            boolean wrapper = cur instanceof InvocationTargetException
                    || cur instanceof ExecutionException
                    || cur instanceof UndeclaredThrowableException;
            if (wrapper && cur.getCause() != null) {
                cur = cur.getCause();
            } else {
                break;
            }
        }
        return cur == null ? t : cur;
    }

    /** {@code SimpleName: message}, single line, bounded. Never throws. */
    static String describe(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        try {
            Throwable root = unwrap(t);
            String message = root.getMessage();
            String text = root.getClass().getSimpleName();
            if (message != null && !message.isBlank()) {
                text += ": " + message.replace('\n', ' ').replace('\r', ' ');
            }
            return text.length() <= MAX_MESSAGE ? text : text.substring(0, MAX_MESSAGE) + "...";
        } catch (Throwable ignored) {
            return "unprintable error";
        }
    }
}

package dev.spawnbotswrapper.inhabitants.engine;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.Configurator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records what the engine writes to its logger (the one {@link EngineContext} uses) at or above a level, so a test can
 * assert what an operator would see: how often something is logged and at which severity.
 */
final class EngineLogCapture implements AutoCloseable {
    record Line(Level level, String message) {
    }

    private final org.apache.logging.log4j.core.Logger logger;
    private final AbstractAppender appender;
    private final Level previousLevel;
    private final List<Line> lines = Collections.synchronizedList(new ArrayList<>());

    EngineLogCapture(Level lowest) {
        this.logger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(PopulationEngine.class);
        this.previousLevel = logger.getLevel();
        this.appender = new AbstractAppender("engine-log-capture-" + System.nanoTime(), null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                lines.add(new Line(event.getLevel(), event.getMessage().getFormattedMessage()));
            }
        };
        appender.start();
        logger.addAppender(appender);
        Configurator.setLevel(logger.getName(), lowest);
    }

    /** Everything written since the capture started (or since {@link #clear}) whose message contains {@code text}. */
    List<Line> matching(String text) {
        synchronized (lines) {
            return lines.stream().filter(l -> l.message().contains(text)).toList();
        }
    }

    long count(Level level, String text) {
        return matching(text).stream().filter(l -> l.level() == level).count();
    }

    void clear() {
        lines.clear();
    }

    @Override
    public void close() {
        logger.removeAppender(appender);
        appender.stop();
        Configurator.setLevel(logger.getName(), previousLevel);
    }
}

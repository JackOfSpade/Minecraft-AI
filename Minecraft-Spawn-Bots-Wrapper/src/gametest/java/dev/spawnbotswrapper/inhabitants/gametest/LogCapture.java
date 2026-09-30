package dev.spawnbotswrapper.inhabitants.gametest;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Collects every message logged while it is open (the server log is the only place the wrapper's diagnostics go). */
final class LogCapture implements AutoCloseable {
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final AbstractAppender appender;
    private final LoggerContext context;

    LogCapture() {
        context = (LoggerContext) LogManager.getContext(false);
        Configuration config = context.getConfiguration();
        appender = new AbstractAppender("harness-capture-" + System.nanoTime(), null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                lines.add(event.getLoggerName() + ": " + event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        config.addAppender(appender);
        config.getRootLogger().addAppender(appender, Level.INFO, null);
        context.updateLoggers();
    }

    /** Messages so far that contain the text. */
    List<String> containing(String text) {
        return lines.stream().filter(l -> l.contains(text)).toList();
    }

    @Override
    public void close() {
        Configuration config = context.getConfiguration();
        config.getRootLogger().removeAppender(appender.getName());
        appender.stop();
        context.updateLoggers();
    }
}

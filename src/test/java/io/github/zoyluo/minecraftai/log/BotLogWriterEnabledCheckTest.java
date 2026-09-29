package io.github.zoyluo.minecraftai.log;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import java.lang.reflect.Field;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers xPerf-BOTLOG-01: {@link BotLogWriter#enabled} is the cheap pre-check {@code BotLog}
 * consults before building a call's field map, and it must agree exactly with the gate at the
 * top of {@link BotLogWriter#submit} -- a disabled category/level must report false (so the map
 * build is skipped) and an enabled one must report true (so the exact same lines are still
 * produced as before this change).
 *
 * <p>Pokes {@code BotLogWriter.INSTANCE}'s private state via reflection instead of calling
 * {@code start()}, which needs a live FabricLoader unavailable to this unit test, and restores
 * the singleton's original state afterward since it is process-wide.
 */
final class BotLogWriterEnabledCheckTest {
    private Field startedField;
    private Field configField;
    private Field thresholdsField;
    private boolean originalStarted;
    private Object originalConfig;
    private Map<LogCategory, Level> thresholds;
    private Map<LogCategory, Level> originalThresholdsSnapshot;

    @BeforeEach
    void captureOriginalState() throws ReflectiveOperationException {
        startedField = BotLogWriter.class.getDeclaredField("started");
        startedField.setAccessible(true);
        configField = BotLogWriter.class.getDeclaredField("config");
        configField.setAccessible(true);
        thresholdsField = BotLogWriter.class.getDeclaredField("thresholds");
        thresholdsField.setAccessible(true);

        originalStarted = startedField.getBoolean(BotLogWriter.INSTANCE);
        originalConfig = configField.get(BotLogWriter.INSTANCE);
        @SuppressWarnings("unchecked")
        Map<LogCategory, Level> current = (Map<LogCategory, Level>) thresholdsField.get(BotLogWriter.INSTANCE);
        thresholds = current;
        originalThresholdsSnapshot = new EnumMap<>(current);
    }

    @AfterEach
    void restoreOriginalState() throws ReflectiveOperationException {
        startedField.setBoolean(BotLogWriter.INSTANCE, originalStarted);
        configField.set(BotLogWriter.INSTANCE, originalConfig);
        thresholds.clear();
        thresholds.putAll(originalThresholdsSnapshot);
    }

    @Test
    void securityIsAlwaysEnabledRegardlessOfStartedOrThreshold() throws ReflectiveOperationException {
        startedField.setBoolean(BotLogWriter.INSTANCE, false);
        assertTrue(BotLogWriter.INSTANCE.enabled(LogCategory.SECURITY, Level.TRACE),
                "security denials must remain observable even before the writer starts");
    }

    @Test
    void configAndErrorAreBootstrapCriticalBeforeTheWriterStarts() throws ReflectiveOperationException {
        startedField.setBoolean(BotLogWriter.INSTANCE, false);
        assertTrue(BotLogWriter.INSTANCE.enabled(LogCategory.CONFIG, Level.INFO));
        assertTrue(BotLogWriter.INSTANCE.enabled(LogCategory.ERROR, Level.ERROR));
    }

    @Test
    void ordinaryCategoriesAreDisabledUntilTheWriterStarts() throws ReflectiveOperationException {
        startedField.setBoolean(BotLogWriter.INSTANCE, false);
        assertFalse(BotLogWriter.INSTANCE.enabled(LogCategory.LIFECYCLE, Level.INFO));
        assertFalse(BotLogWriter.INSTANCE.enabled(LogCategory.ACTION, Level.INFO));
    }

    @Test
    void enabledMatchesTheThresholdOnceStartedAndConfigEnabled() throws ReflectiveOperationException {
        startedField.setBoolean(BotLogWriter.INSTANCE, true);
        configField.set(BotLogWriter.INSTANCE,
                new MinecraftAiConfig.Logging(true, "logs/minecraftai", true, "daily", 50, 30, 3, 10, true, Map.of()));
        thresholds.clear();
        thresholds.put(LogCategory.PERCEPTION, Level.INFO);

        // Below the raised threshold -> filtered out, so BotLog.submit must skip toMap/LogEntry entirely.
        assertFalse(BotLogWriter.INSTANCE.enabled(LogCategory.PERCEPTION, Level.DEBUG));
        // At/above the threshold -> the call still reaches submit() exactly as before.
        assertTrue(BotLogWriter.INSTANCE.enabled(LogCategory.PERCEPTION, Level.INFO));
        assertTrue(BotLogWriter.INSTANCE.enabled(LogCategory.PERCEPTION, Level.WARN));
    }

    @Test
    void enabledIsFalseWhenLoggingIsConfiguredOff() throws ReflectiveOperationException {
        startedField.setBoolean(BotLogWriter.INSTANCE, true);
        configField.set(BotLogWriter.INSTANCE,
                new MinecraftAiConfig.Logging(false, "logs/minecraftai", true, "daily", 50, 30, 3, 10, true, Map.of()));

        assertFalse(BotLogWriter.INSTANCE.enabled(LogCategory.ACTION, Level.INFO));
    }
}

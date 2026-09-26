package io.github.zoyluo.aibot.log;

import org.slf4j.event.Level;

import java.util.Map;

record LogEntry(
        long timestamp,
        LogCategory category,
        Level level,
        String botName,
        String event,
        Map<String, String> fields,
        String humanMessage,
        Throwable throwable
) {
}

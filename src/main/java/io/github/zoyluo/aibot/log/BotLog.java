package io.github.zoyluo.aibot.log;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.task.TaskManager;
import org.slf4j.event.Level;

import java.util.LinkedHashMap;
import java.util.Map;

public final class BotLog {
    private BotLog() {
    }

    public static void lifecycle(AIPlayerEntity bot, String event, Object... kv) {
        submit(LogCategory.LIFECYCLE, Level.INFO, bot, event, null, null, kv);
    }

    public static void lifecycle(String event, Object... kv) {
        submit(LogCategory.LIFECYCLE, Level.INFO, "-", event, null, null, kv);
    }

    public static void comm(AIPlayerEntity bot, String event, Object... kv) {
        submit(LogCategory.COMM, Level.INFO, bot, event, null, null, kv);
    }

    public static void commSystem(String event, Object... kv) {
        submit(LogCategory.COMM, Level.INFO, "-", event, null, null, kv);
    }

    public static void api(AIPlayerEntity bot, String event, Object... kv) {
        submit(LogCategory.API, Level.INFO, bot, event, null, null, kv);
    }

    public static void action(AIPlayerEntity bot, String event, Object... kv) {
        submit(LogCategory.ACTION, Level.INFO, bot, event, null, null, kv);
    }

    public static void perception(AIPlayerEntity bot, String event, Object... kv) {
        submit(LogCategory.PERCEPTION, Level.DEBUG, bot, event, null, null, kv);
    }

    public static void path(AIPlayerEntity bot, String event, Object... kv) {
        submit(LogCategory.PATH, Level.DEBUG, bot, event, null, null, kv);
    }

    public static void task(AIPlayerEntity bot, String event, Object... kv) {
        submit(LogCategory.TASK, Level.INFO, bot, event, null, null, kv);
    }

    public static void danger(AIPlayerEntity bot, String event, Object... kv) {
        submit(LogCategory.DANGER, Level.INFO, bot, event, null, null, kv);
    }

    public static void profile(AIPlayerEntity bot, String event, Object... kv) {
        submit(LogCategory.PROFILE, Level.INFO, bot, event, null, null, kv);
    }

    public static void replay(AIPlayerEntity bot, String event, Object... kv) {
        submit(LogCategory.REPLAY, Level.INFO, bot, event, null, null, kv);
    }

    public static void config(String event, Object... kv) {
        submit(LogCategory.CONFIG, Level.INFO, "-", event, null, null, kv);
    }

    public static void security(String event, Object... kv) {
        submit(LogCategory.SECURITY, Level.WARN, "-", event, null, null, kv);
    }

    public static void warn(LogCategory category, AIPlayerEntity bot, String event, Object... kv) {
        submit(category, Level.WARN, bot, event, null, null, kv);
    }

    public static void error(AIPlayerEntity bot, String event, Throwable throwable, Object... kv) {
        submit(LogCategory.ERROR, Level.ERROR, bot, event, null, throwable, kv);
    }

    public static void error(String event, Throwable throwable, Object... kv) {
        submit(LogCategory.ERROR, Level.ERROR, "-", event, null, throwable, kv);
    }

    public static void raw(LogCategory category, Level level, AIPlayerEntity bot, String event, String humanMessage, Object... kv) {
        submit(category, level, bot, event, humanMessage, null, kv);
    }

    private static void submit(LogCategory category, Level level, AIPlayerEntity bot, String event, String humanMessage, Throwable throwable, Object... kv) {
        BotLogWriter.INSTANCE.submit(category, level, nameOf(bot), scopeOf(bot), event, toMap(kv), humanMessage, throwable);
    }

    private static void submit(LogCategory category, Level level, String botName, String event, String humanMessage, Throwable throwable, Object... kv) {
        BotLogWriter.INSTANCE.submit(category, level, botName, "-", event, toMap(kv), humanMessage, throwable);
    }

    private static String nameOf(AIPlayerEntity bot) {
        return bot == null ? "-" : bot.getGameProfile().getName();
    }

    /**
     * Every log call automatically inherits the bot's currently active request scope (see {@link
     * io.github.zoyluo.aibot.runtime.TaskOrigin#scopeId()}) -- no call site anywhere in the
     * codebase needs to pass it explicitly. This is what lets a played session's log be filtered
     * down to exactly one player instruction's execution (grep for its scope tag) instead of
     * wading through every bot's interleaved activity.
     */
    private static String scopeOf(AIPlayerEntity bot) {
        if (bot == null) {
            return "-";
        }
        return TaskManager.INSTANCE.activeOrigin(bot).map(origin -> {
            try {
                return origin.scopeId();
            } catch (RuntimeException ignored) {
                return "-";
            }
        }).orElse("-");
    }

    private static Map<String, String> toMap(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("kv must be even-length pairs");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int index = 0; index < kv.length; index += 2) {
            map.put(String.valueOf(kv[index]), String.valueOf(kv[index + 1]));
        }
        return map;
    }
}

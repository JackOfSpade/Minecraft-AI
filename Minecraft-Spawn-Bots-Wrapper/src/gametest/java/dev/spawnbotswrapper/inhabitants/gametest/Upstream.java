package dev.spawnbotswrapper.inhabitants.gametest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Test-side reads of PvP BOT's internal state by reflection (the harness only OBSERVES, it never writes upstream state).
 * The names are the released 0.0.15 ones; every read is fail-soft and answers "?" when it cannot be made.
 */
final class Upstream {
    private static final String PKG = "org.stepan1411.pvp_bot.bot.";

    private Upstream() {
    }

    private static Class<?> cls(String simple) throws ClassNotFoundException {
        return Class.forName(PKG + simple, true, Upstream.class.getClassLoader());
    }

    /** PvP BOT's current target of this bot, or "none"/"?" . */
    static String target(String bot) {
        try {
            Object t = cls("BotCombat").getMethod("getTarget", String.class).invoke(null, bot);
            return t == null ? "none" : t.getClass().getSimpleName() + ":" + t;
        } catch (Throwable e) {
            return "?" + e;
        }
    }

    /** PvP BOT's combat state fields: mode, isDrawingBow, bowDrawTicks. */
    static String combatState(String bot) {
        try {
            Object st = cls("BotCombat").getMethod("getState", String.class).invoke(null, bot);
            return "mode=" + field(st, "currentMode") + " drawing=" + field(st, "isDrawingBow") + "/" + field(st, "bowDrawTicks")
                    + " cd=" + field(st, "attackCooldown") + " retreat=" + field(st, "isRetreating");
        } catch (Throwable e) {
            return "state?" + e;
        }
    }

    static Object field(Object o, String name) throws ReflectiveOperationException {
        Field f = o.getClass().getField(name);
        return f.get(o);
    }

    /** Makes PvP BOT read its per-world settings file again (what its reload command does to the settings): a NEW settings object. */
    static void reloadSettings() {
        try {
            cls("BotSettings").getMethod("load").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("PvP BOT settings could not be reloaded", e);
        }
    }

    /** The settings object itself, to tell a reload (a new object) from a change of values. */
    static Object settingsObject() {
        try {
            return cls("BotSettings").getMethod("get").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A getter of the settings singleton, by name. */
    static Object setting(String getter) {
        try {
            Object settings = cls("BotSettings").getMethod("get").invoke(null);
            Method m = settings.getClass().getMethod(getter);
            return m.invoke(settings);
        } catch (Throwable e) {
            return "?" + e;
        }
    }
}

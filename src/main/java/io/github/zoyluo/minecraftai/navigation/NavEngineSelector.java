package io.github.zoyluo.minecraftai.navigation;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.log.BotLog;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Chooses the navigator for a request and makes Baritone fail soft.
 *
 * <ul>
 *   <li>{@link #configured()} is what {@code nav.engine} says; {@link #effective()} is what is actually used: the configured
 *       engine, except that a Baritone that has failed once this session is never asked again (the mod then behaves exactly
 *       as it does with the legacy engine). The {@code ...For(botId)} forms honour a per-bot override ({@link #setBotEngine}).</li>
 *   <li>{@link #attempt} is the only way callers enter Baritone. It runs the Baritone-side work of one request; if that work
 *       throws a linkage-type failure (a class that cannot load or initialise: a mixin or remap problem in some modpack, a
 *       missing library, a static initialiser that throws) it logs once, marks Baritone unavailable for the session and answers
 *       with the fallback. Any other exception is logged and answers with the fallback for that request only.</li>
 *   <li>{@link #baritoneLive()} is the cheap "has Baritone been initialised by us at all" flag. Hooks that run for every bot on
 *       every tick or on every lifecycle event ask it first, so with the legacy engine no {@code baritone.*} class is even
 *       loaded by them.</li>
 * </ul>
 *
 * <p>Nothing here names a Baritone type.</p>
 */
public final class NavEngineSelector {
    private static final AtomicBoolean FAILED = new AtomicBoolean();
    private static volatile boolean live;
    private static volatile String failure = "";
    private static final Map<UUID, NavEngine> BOT_OVERRIDES = new ConcurrentHashMap<>();

    private NavEngineSelector() {
    }

    /** The engine the config asks for. */
    public static NavEngine configured() {
        return MinecraftAiConfig.get().nav().engineChoice();
    }

    /** The engine asked for bot {@code botId}: its override if one was set ({@link #setBotEngine}), else {@link #configured()}. */
    public static NavEngine configuredFor(UUID botId) {
        NavEngine override = botId == null ? null : BOT_OVERRIDES.get(botId);
        return override != null ? override : configured();
    }

    /**
     * Per-bot override of {@code nav.engine} (null clears it). There is no user-facing switch for it: the config's global value is
     * the setting, and this is the hook for code that must run one bot on the other engine without changing the engine of the
     * others (the GameTests, and later a per-bot command). Cleared when the bot is forgotten.
     */
    public static void setBotEngine(UUID botId, NavEngine engine) {
        if (engine == null) {
            BOT_OVERRIDES.remove(botId);
        } else {
            BOT_OVERRIDES.put(botId, engine);
        }
    }

    /** Drops the override of a bot that is gone. */
    public static void clearBotEngine(UUID botId) {
        BOT_OVERRIDES.remove(botId);
    }

    /** Drops every override (server stop). */
    public static void clearAllBotEngines() {
        BOT_OVERRIDES.clear();
    }

    /** The engine to use right now: {@link #configured()} unless Baritone has failed to initialise. */
    public static NavEngine effective() {
        return effective(configured(), FAILED.get());
    }

    /** The engine to use right now for bot {@code botId}. */
    public static NavEngine effectiveFor(UUID botId) {
        return effective(configuredFor(botId), FAILED.get());
    }

    /** Pure form of {@link #effective()} (unit-testable without a config). */
    public static NavEngine effective(NavEngine configured, boolean baritoneFailed) {
        return configured == NavEngine.BARITONE && baritoneFailed ? NavEngine.LEGACY : configured;
    }

    /** True when requests are currently answered by Baritone. */
    public static boolean baritoneSelected() {
        return effective() == NavEngine.BARITONE;
    }

    /** True when requests of bot {@code botId} are currently answered by Baritone. */
    public static boolean baritoneSelectedFor(UUID botId) {
        return effectiveFor(botId) == NavEngine.BARITONE;
    }

    /** True once Baritone has failed to initialise or run; sticky until {@link #resetForTests()}. */
    public static boolean baritoneFailed() {
        return FAILED.get();
    }

    /** Why Baritone was given up on (empty while it has not been). */
    public static String failureDescription() {
        return failure;
    }

    /** Baritone state now exists (an instance was created); the lifecycle hooks have something to clean up. */
    public static void markBaritoneLive() {
        live = true;
    }

    /** Whether the mod has initialised Baritone in this JVM. Lifecycle and tick hooks skip Baritone entirely while it is false. */
    public static boolean baritoneLive() {
        return live;
    }

    /**
     * Gives up on Baritone for the rest of the session. Logs once; later calls keep the first reason.
     *
     * @return true for the call that marked it
     */
    public static boolean markBaritoneUnavailable(String where, Throwable cause) {
        if (!FAILED.compareAndSet(false, true)) {
            return false;
        }
        failure = where + ": " + cause;
        try {
            BotLog.error("nav_baritone_unavailable", cause, "where", where, "fallback", NavEngine.LEGACY.configValue());
        } catch (Throwable loggingFailed) {
            System.err.println("[minecraftai] Baritone navigation unavailable (" + failure + "); falling back to the legacy navigator");
        }
        return true;
    }

    /**
     * Runs the Baritone-side work of one request. Returns {@code fallback.get()} when the effective engine is not Baritone,
     * and when the work failed (see the class comment for what marks Baritone unavailable). The fallback of a route request is
     * "not routed, the legacy code carries on".
     */
    public static <T> T attempt(String what, Supplier<T> baritoneWork, Supplier<T> fallback) {
        return attempt(null, what, baritoneWork, fallback);
    }

    /** As {@link #attempt(String, Supplier, Supplier)} for a request of bot {@code botId} (which may have its own engine). */
    public static <T> T attempt(UUID botId, String what, Supplier<T> baritoneWork, Supplier<T> fallback) {
        if (!baritoneSelectedFor(botId)) {
            return fallback.get();
        }
        try {
            return baritoneWork.get();
        } catch (Throwable failed) {
            if (failed instanceof VirtualMachineError fatal && !(failed instanceof StackOverflowError)) {
                throw fatal;
            }
            if (isInitialisationFailure(failed)) {
                markBaritoneUnavailable(what, failed);
            } else {
                try {
                    BotLog.error("nav_baritone_request_failed", failed, "what", what);
                } catch (Throwable ignored) {
                    // logging must not make a failed request worse
                }
            }
            return fallback.get();
        }
    }

    /** A failure that means Baritone (or a class it needs) cannot be used in this JVM, as opposed to one bad request. */
    public static boolean isInitialisationFailure(Throwable failed) {
        for (Throwable t = failed; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof LinkageError || t instanceof ClassNotFoundException || t instanceof TypeNotPresentException
                    || t.getClass().getName().startsWith("org.spongepowered.asm.mixin")) {
                return true;
            }
        }
        return false;
    }

    /** Test hook: forgets a Baritone failure only (the live flag and the overrides stay). */
    public static void clearFailureForTests() {
        FAILED.set(false);
        failure = "";
    }

    /** Test hook: forgets a failure, the live flag and every per-bot override. */
    public static void resetForTests() {
        FAILED.set(false);
        failure = "";
        live = false;
        BOT_OVERRIDES.clear();
    }
}

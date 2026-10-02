package io.github.zoyluo.minecraftai.navigation;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.log.BotLog;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Owns the Baritone-only navigation availability state.
 *
 * <ul>
 *   <li>{@link #configured()} and {@link #effective()} are always {@link NavEngine#BARITONE}. A session failure makes requests
 *       fail closed; it never selects the old executor. The {@code ...For(botId)} forms remain only as source-compatible test
 *       seams and also canonicalise to Baritone.</li>
 *   <li>{@link #attempt} is the only way callers enter Baritone. It runs the Baritone-side work of one request; if that work
 *       throws a linkage-type failure (a class that cannot load or initialise: a mixin or remap problem in some modpack, a
 *       missing library, a static initialiser that throws) it logs once, marks Baritone unavailable for the session and answers
 *       with the caller's typed failure. Any other exception is logged and answers with the caller's typed failure for that request.</li>
 *   <li>{@link #baritoneActive()} is the cheap "Baritone was initialised by us and has not been given up on" flag ({@link #baritoneLive()}
 *       alone only says it was initialised). Hooks that run for every bot on every tick or on every lifecycle event go through it:
 *       {@link #hook} runs a Baritone-side action and {@link #query} asks a question, both only while it is true and both
 *       containing a failure ({@link #handleFailure}); a Baritone that failed once is never touched again.</li>
 * </ul>
 *
 * <p>Nothing here names a Baritone type.</p>
 */
public final class NavEngineSelector {
    private static final AtomicBoolean FAILED = new AtomicBoolean();
    private static volatile boolean live;
    private static volatile String failure = "";
    /** Best-effort teardown of every Baritone instance and route, registered by the Baritone glue once it exists. */
    private static volatile Runnable unavailableHook;
    private static final Map<UUID, NavEngine> BOT_OVERRIDES = new ConcurrentHashMap<>();

    private NavEngineSelector() {
    }

    /** The engine the config asks for. */
    public static NavEngine configured() {
        return NavEngine.BARITONE;
    }

    /** The engine asked for bot {@code botId}: its override if one was set ({@link #setBotEngine}), else {@link #configured()}. */
    public static NavEngine configuredFor(UUID botId) {
        return NavEngine.BARITONE;
    }

    /**
     * Compatibility seam for old tests. A legacy value is deliberately ignored: production has no
     * per-bot legacy mode and every override canonicalises to Baritone.
     */
    public static void setBotEngine(UUID botId, NavEngine engine) {
        if (botId == null) {
            return;
        }
        if (engine != NavEngine.BARITONE) {
            BOT_OVERRIDES.remove(botId);
        } else {
            BOT_OVERRIDES.put(botId, NavEngine.BARITONE);
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

    /** The only configured engine; availability is represented by {@link #baritoneSelected()}. */
    public static NavEngine effective() {
        return effective(configured(), FAILED.get());
    }

    /** The engine to use right now for bot {@code botId}. */
    public static NavEngine effectiveFor(UUID botId) {
        return effective(configuredFor(botId), FAILED.get());
    }

    /** Pure compatibility form: navigation identity stays Baritone even when it is unavailable. */
    public static NavEngine effective(NavEngine configured, boolean baritoneFailed) {
        return NavEngine.BARITONE;
    }

    /** True when requests are currently answered by Baritone. */
    public static boolean baritoneSelected() {
        return !FAILED.get();
    }

    /** True when requests of bot {@code botId} are currently answered by Baritone. */
    public static boolean baritoneSelectedFor(UUID botId) {
        return !FAILED.get();
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
     * Whether the hooks that run for every bot / every lifecycle event may call into Baritone: it was initialised and has not been
     * given up on since. A Baritone that failed once is never touched again by them.
     */
    public static boolean baritoneActive() {
        return live && !FAILED.get();
    }

    /**
     * Registers what runs (once, best effort, on the thread that marked the failure) when Baritone is given up on: it lets go of
     * every bot, cancels every route and forgets every instance. Set by the Baritone glue when it creates its first instance.
     */
    public static void setUnavailableHook(Runnable hook) {
        unavailableHook = hook;
    }

    /**
     * Runs a Baritone-side hook (a lifecycle reset, a preempt, ...) only while {@link #baritoneActive()}. Any failure is
     * handled by {@link #handleFailure}: a linkage-type one retires Baritone, the caller carries on either way.
     */
    public static void hook(String where, Runnable work) {
        if (!baritoneActive()) {
            return;
        }
        try {
            work.run();
        } catch (Throwable failed) {
            handleFailure(where, failed);
        }
    }

    /** As {@link #hook} for a question: {@code whenInactive} when Baritone is not active or the question failed. */
    public static <T> T query(String where, Supplier<T> work, T whenInactive) {
        if (!baritoneActive()) {
            return whenInactive;
        }
        try {
            return work.get();
        } catch (Throwable failed) {
            handleFailure(where, failed);
            return whenInactive;
        }
    }

    /**
     * The one place a failure of Baritone-side work that is already running is classified. A true VM error (out of memory, ...) is
     * rethrown; a linkage-type failure (a class that cannot load or initialise, a mixin failure: see
     * {@link #isInitialisationFailure}) gives up on Baritone for the session ({@link #markBaritoneUnavailable}); anything else is
     * only logged (one bad request or tick).
     *
     * @return true when the failure retired Baritone (the caller must carry on without it)
     */
    public static boolean handleFailure(String where, Throwable failed) {
        if (failed instanceof VirtualMachineError fatal && !(failed instanceof StackOverflowError)) {
            throw fatal;
        }
        if (isInitialisationFailure(failed)) {
            markBaritoneUnavailable(where, failed);
            return true;
        }
        try {
            BotLog.error("nav_baritone_request_failed", failed, "what", where);
        } catch (Throwable ignored) {
            // logging must not make a failed request worse
        }
        return false;
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
            BotLog.error("nav_baritone_unavailable", cause, "where", where, "action", "navigation_stopped");
        } catch (Throwable loggingFailed) {
            System.err.println("[minecraftai] Baritone navigation unavailable (" + failure + "); navigation stopped");
        }
        Runnable teardown = unavailableHook;
        if (teardown != null) {
            try {
                teardown.run();
            } catch (Throwable ignored) {
                // best effort: the flag is what keeps every hook away from Baritone from now on
            }
        }
        return true;
    }

    /**
     * Runs the Baritone-side work of one request. {@code whenUnavailable} must be a typed failure
     * result; it must never start an alternate navigator.
     */
    public static <T> T attempt(String what, Supplier<T> baritoneWork, Supplier<T> whenUnavailable) {
        return attempt(null, what, baritoneWork, whenUnavailable);
    }

    /** As {@link #attempt(String, Supplier, Supplier)} for a request of bot {@code botId} (which may have its own engine). */
    public static <T> T attempt(UUID botId, String what, Supplier<T> baritoneWork, Supplier<T> whenUnavailable) {
        if (!baritoneSelectedFor(botId)) {
            // A scale-one P3 row records the unavailable Baritone request rather than silently
            // turning into a legacy movement.
            NavigationMeasurement.noteBaritoneFallback(botId);
            return whenUnavailable.get();
        }
        try {
            return baritoneWork.get();
        } catch (Throwable failed) {
            handleFailure(what, failed);
            NavigationMeasurement.noteBaritoneFallback(botId);
            return whenUnavailable.get();
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
        unavailableHook = null;
        BOT_OVERRIDES.clear();
    }
}

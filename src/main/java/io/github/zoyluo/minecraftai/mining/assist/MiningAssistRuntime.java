package io.github.zoyluo.minecraftai.mining.assist;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.mining.MiningEvidenceAudit;
import io.github.zoyluo.minecraftai.observe.TpsGuard;
import io.github.zoyluo.minecraftai.task.TaskManager;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Static holder of the mining assist's process-wide runtime state (mining-assist design 2.1): the
 * current immutable {@link MiningAssistConfig}, the harness default, the test overrides, the
 * {@link TickHeadroom} and the per-bot gate. {@code MiningAssistConfig} itself is immutable, so the
 * mutable switches the design attaches to it ({@code setHarnessDefaultOff}, {@code forceEnable},
 * {@code setTestTpsDegraded}) live here.
 *
 * <p><b>Ordering.</b> The harness default is folded into the config at parse time
 * ({@code MiningAssistConfig#harnessOff}), so {@link #setHarnessDefaultOff} should run before the config
 * is loaded. To make the order harmless anyway, this class keeps the last file root and environment
 * function it was given and re-parses when the harness default changes afterwards; a call after
 * {@link #load} therefore behaves exactly like a call before it.</p>
 *
 * <p><b>Gate.</b> {@link #enabledFor} is the design's {@code enabledFor(bot)}: mode allows sensing, the
 * harness is not off (unless the bot is forced), the active origin is one of the five real mission kinds,
 * no strict audit session holds the bot, and TPS is not degraded. The verdict is cached per bot for
 * {@value GateCache#TTL_TICKS} ticks, except that a cached open verdict is re-checked against the live origin
 * and audit session on every call.</p>
 *
 * <p><b>What "off" costs.</b> While the switch is off ({@link #senseConfigured()} is false: mode {@code off},
 * or the harness default off with no forced bot) the first statement of every hook, of the coordinator, of
 * the gate and of the tick measurement ({@link #beginTick}, {@link #endTick}) is that one volatile read and a
 * return, and the sidecar is not read at start-up. What still runs is the bare call per tick per bot and
 * the once-per-tick dirty-flag check of {@code BotEdits.snapshotIfDue}.</p>
 */
public final class MiningAssistRuntime {
    /**
     * The phase's shipped default mode. Flipped SENSE -&gt; DETOUR 2026-09-27 for a user-directed live
     * single-bot playtest of P1 (the walk-only opportunistic valuables detour): the design's own gate
     * for this flip (GameTest suite fully green + a 4-bot tick-cost check + three clean unfiltered
     * runs) is not yet fully met -- two GameTest scenarios and one flaky one remain open, and the
     * 4-bot cost gate was never run. The user explicitly substituted a live one-bot playtest plus
     * post-session log review for that gate ("with one bot tho, not doing 4-bots... generate the
     * logging we need to check for any bugs after i'm done"). No POI capability exists yet (P2/P3
     * unbuilt), so DETOUR is the correct mode, not POI/ALL.
     */
    public static final AssistMode SHIPPED_DEFAULT_MODE = AssistMode.DETOUR;

    private static final Function<String, String> PROCESS_ENV = System::getenv;

    private static volatile MiningAssistConfig config =
            MiningAssistConfig.parse(null, PROCESS_ENV, SHIPPED_DEFAULT_MODE, false);
    private static volatile TickHeadroom headroom = buildHeadroom(config, false);
    private static volatile boolean senseAny = computeSenseAny(config, false);
    private static volatile long tickStartNanos;
    private static volatile boolean harnessOff;
    private static JsonObject lastFileRoot;
    private static Function<String, String> lastEnv = PROCESS_ENV;
    private static volatile Boolean testTpsDegraded;
    private static int hookFailureNotes;

    private static final Set<UUID> FORCED = ConcurrentHashMap.newKeySet();
    private static final GateCache GATE = new GateCache();
    private static final SenseFailureGate FAILURES = new SenseFailureGate();

    private MiningAssistRuntime() {
    }

    // ---------------------------------------------------------------------------------------
    // Config
    // ---------------------------------------------------------------------------------------

    public static MiningAssistConfig config() {
        return config;
    }

    /** The configured mode. */
    public static AssistMode mode() {
        return config.mode();
    }

    /**
     * The single cheap static check of every hook and of the coordinator: one volatile read. False while
     * the mode is {@code off}, and also while the harness default is off and no bot has been force-enabled,
     * so GameTests and verify scenarios run with no assist activity at all (no recording, no sidecar
     * writes) until a test opts a bot in.
     */
    public static boolean senseConfigured() {
        return senseAny;
    }

    private static boolean computeSenseAny(MiningAssistConfig cfg, boolean anyForced) {
        return cfg.mode() != AssistMode.OFF && (!cfg.harnessOff() || anyForced);
    }

    private static void refreshSenseAny() {
        senseAny = computeSenseAny(config, !FORCED.isEmpty());
    }

    /**
     * The harness (GameTest and verify lanes) asks for assist to default OFF: {@code setHarnessDefaultOff(true)}
     * is the call a harness makes; {@code false} restores the shipped default. (The design text spells the same
     * call {@code setHarnessDefault(false)}; the name here says what the flag means so a new harness entry point
     * cannot switch the assist on by mistake.) An explicit mode from the environment or from
     * {@code config/minecraftai.json} still wins (design 2.1). Callable before or after the config load, see the
     * class comment.
     */
    public static synchronized void setHarnessDefaultOff(boolean off) {
        harnessOff = off;
        reparse();
        // The assist_config line written at load time predates a harness call, so say what it resolved to.
        BotLog.config("assist_harness_default",
                "harness_default_off", off,
                "harness_off", config.harnessOff(),
                "mode", config.mode());
    }

    public static boolean harnessDefaultOff() {
        return harnessOff;
    }

    /**
     * Loads the {@code miningAssist} section of {@code config/minecraftai.json} (a separate pass, the main config
     * class ignores it), installs the result and logs every warning once. Never throws: an unreadable or
     * malformed file gives the defaults. A missing file is not an error.
     */
    public static MiningAssistConfig load(Path minecraftaiJson) {
        JsonObject root = null;
        if (minecraftaiJson != null && Files.isRegularFile(minecraftaiJson)) {
            try (Reader reader = Files.newBufferedReader(minecraftaiJson)) {
                JsonElement element = JsonParser.parseReader(reader);
                if (element != null && element.isJsonObject()) {
                    root = element.getAsJsonObject();
                }
            } catch (IOException | RuntimeException exception) {
                BotLog.warn(LogCategory.CONFIG, null, "assist_config_read_failed",
                        "path", minecraftaiJson, "error", exception.getClass().getSimpleName());
            }
        }
        return install(root, PROCESS_ENV);
    }

    /** Parses {@code fileRoot} (the whole minecraftai.json, may be null) with {@code env} and installs the result. */
    public static synchronized MiningAssistConfig install(JsonObject fileRoot, Function<String, String> env) {
        lastFileRoot = fileRoot;
        lastEnv = env;
        MiningAssistConfig parsed = MiningAssistConfig.parse(fileRoot, env, SHIPPED_DEFAULT_MODE, harnessOff);
        applyConfig(parsed);
        logConfig(parsed);
        return parsed;
    }

    /** Installs an already built config (tests). Does not remember a file root, so a later harness change keeps it. */
    public static synchronized void install(MiningAssistConfig newConfig) {
        applyConfig(Objects.requireNonNull(newConfig, "newConfig"));
    }

    private static void reparse() {
        applyConfig(MiningAssistConfig.parse(lastFileRoot, lastEnv, SHIPPED_DEFAULT_MODE, harnessOff));
    }

    private static void applyConfig(MiningAssistConfig newConfig) {
        config = newConfig;
        rebuildHeadroom();
        refreshSenseAny();
        GATE.clear();
        // P1 (F.4): the route budget's capacity tracks the live config, not just its construction-time default.
        RouteBudget.shared().reconfigure(newConfig.route().bucketMs());
    }

    private static void logConfig(MiningAssistConfig parsed) {
        for (String warning : parsed.warnings()) {
            BotLog.warn(LogCategory.CONFIG, null, "assist_config_warning", "note", warning);
        }
        BotLog.config("assist_config",
                "mode", parsed.mode(),
                "mode_source", parsed.modeSource(),
                "harness_off", parsed.harnessOff(),
                "deterministic", parsed.deterministic(false),
                "rays_per_tick", parsed.sense().raysPerTick(),
                "global_rays_per_tick", parsed.sense().globalRaysPerTick(),
                "adaptive_throttle", parsed.sense().adaptiveThrottle(),
                "shadow_log", parsed.sense().shadowLog(),
                "edits_sidecar", parsed.edits().sidecar());
    }

    // ---------------------------------------------------------------------------------------
    // Test switches
    // ---------------------------------------------------------------------------------------

    /** Enables assist for one bot regardless of the harness default (tests). It also makes the run deterministic. */
    public static void forceEnable(UUID botId) {
        if (FORCED.add(Objects.requireNonNull(botId, "botId"))) {
            rebuildHeadroom();
            refreshSenseAny();
            GATE.remove(botId);
        }
    }

    public static void clearForced(UUID botId) {
        if (FORCED.remove(botId)) {
            rebuildHeadroom();
            refreshSenseAny();
            GATE.remove(botId);
        }
    }

    public static boolean isForced(UUID botId) {
        return FORCED.contains(botId);
    }

    /** Overrides the TPS verdict (tests): true or false forces it, null uses the real {@link TpsGuard}. */
    public static void setTestTpsDegraded(Boolean degraded) {
        testTpsDegraded = degraded;
        GATE.clear();
    }

    /** Treats one bot as running on a degraded server (tests): unlike {@link #setTestTpsDegraded} it touches no other bot. */
    public static void forceTpsDegradedForTests(UUID botId, boolean degraded) {
        TpsGuard.forceDegradedForTests(botId, degraded);
        GATE.remove(botId);
    }

    public static Boolean testTpsDegraded() {
        return testTpsDegraded;
    }

    /** Whether this bot runs without time-based throttling (harness default, forced, or the env flag). */
    public static boolean deterministic(UUID botId) {
        return config.deterministic(FORCED.contains(botId));
    }

    // ---------------------------------------------------------------------------------------
    // Tick headroom
    // ---------------------------------------------------------------------------------------

    /**
     * The process-wide tick headroom. {@code MinecraftAiMod} feeds it once per server tick with the measured
     * work in milliseconds; the sensor reads {@code halveRays()}. It is rebuilt (and its history lost) when the
     * config or the forced set changes.
     */
    public static TickHeadroom headroom() {
        return headroom;
    }

    /** Server ticks after start-up whose work is not fed to the headroom: the first ticks are huge and would seed the EMA high. */
    public static final int HEADROOM_WARMUP_TICKS = 200;

    /**
     * Feeds one server tick's measured work (end nanoTime minus START_SERVER_TICK nanoTime, in ms) to the
     * headroom. {@code MinecraftAiMod} calls it exactly once per tick from the END lambda, as its last statement.
     * Ticks before {@link #HEADROOM_WARMUP_TICKS} are ignored.
     */
    public static void recordTickWork(double workMs, int serverTicks) {
        if (serverTicks >= HEADROOM_WARMUP_TICKS) {
            headroom.record(Math.min(workMs, MAX_HEADROOM_SAMPLE_MS));
        }
    }

    /**
     * Largest single tick sample the headroom sees, in milliseconds. The headroom is a smoothed average with a
     * latch, meant to answer "is the server under sustained load"; one autosave, GC pause or chunk-generation
     * spike of several hundred milliseconds would otherwise throw the ray-halving latch (and it needs about ten
     * seconds of calm to release). A tick this long is already twice the 50 ms budget, so clamping loses no
     * information a sustained overload would not repeat; several such ticks in a row still latch it.
     */
    public static final double MAX_HEADROOM_SAMPLE_MS = 100.0D;

    /** {@code START_SERVER_TICK}: remembers when this tick's work began. Nothing at all while the switch is off. */
    public static void beginTick() {
        if (!senseAny) {
            return;
        }
        tickStartNanos = System.nanoTime();
    }

    /**
     * Last statement of the {@code END_SERVER_TICK} lambda: feeds the work since {@link #beginTick} to the
     * headroom. Does nothing while the switch is off, and when no start was seen (the first tick after a class
     * load or after the switch came on).
     */
    public static void endTick(int serverTicks) {
        if (!senseAny) {
            if (tickStartNanos != 0L) {
                tickStartNanos = 0L; // the switch went off after a start was seen; never measure against it
            }
            return;
        }
        long started = tickStartNanos;
        if (started != 0L) {
            recordTickWork((System.nanoTime() - started) / 1_000_000.0D, serverTicks);
        }
    }

    private static void rebuildHeadroom() {
        headroom = buildHeadroom(config, !FORCED.isEmpty());
    }

    private static TickHeadroom buildHeadroom(MiningAssistConfig cfg, boolean anyForced) {
        return new TickHeadroom(cfg.deterministic(anyForced),
                TickHeadroom.Thresholds.sanitized(cfg.tick().startWorkMs(), cfg.tick().abortWorkMs()));
    }

    // ---------------------------------------------------------------------------------------
    // Gate
    // ---------------------------------------------------------------------------------------

    /** The gate for {@code bot} at the current server tick. Returns before it reads anything from the bot while the switch is off. */
    public static boolean enabledFor(AIPlayerEntity bot) {
        if (!senseAny) {
            return false;
        }
        return enabledFor(bot, serverTick(bot));
    }

    /**
     * The pure wiring of the gate inputs (design 2.1), separate from the live lookups so tests can drive it with
     * fakes. {@code originKind} is the {@code TaskOrigin.Kind} name of the bot's active task, empty when it has
     * none: no origin is never a real origin (fail closed). Returns the {@link AssistGate} deny reason, or null
     * when the gate is open.
     */
    static String resolveDeny(MiningAssistConfig cfg, boolean forced, Optional<String> originKind,
                              boolean auditSession, boolean tpsDegraded) {
        return AssistGate.denyReason(cfg.mode(), cfg.harnessOff(), forced, originIsReal(originKind),
                auditSession, tpsDegraded);
    }

    /** True only for one of the five real origin kinds; an empty origin is not real. */
    static boolean originIsReal(Optional<String> originKind) {
        return originKind.map(AssistGate::isRealOrigin).orElse(false);
    }

    /**
     * Whether a verdict that was cached open is still open. The two inputs that can change inside the cache
     * window without the cache knowing (an evidence-audit session that began, an origin that is no longer a
     * real mission kind) are one map lookup each, so they are re-read on every call; only the TPS part is
     * really cached.
     */
    static boolean stillOpen(Optional<String> originKind, boolean auditSession) {
        return originIsReal(originKind) && !auditSession;
    }

    private static Optional<String> originKindOf(AIPlayerEntity bot) {
        return TaskManager.INSTANCE.activeOrigin(bot).map(origin -> origin.kind().name());
    }

    /**
     * The gate for {@code bot} at {@code serverTick}, cached for {@value GateCache#TTL_TICKS} ticks. A
     * change of verdict or of the deny reason is logged once (task category, event {@code assist_gate}). A
     * cached open verdict is re-checked against the live origin and audit session on every call
     * ({@link #stillOpen}), so an audit session that begins never waits out the cache.
     */
    public static boolean enabledFor(AIPlayerEntity bot, int serverTick) {
        if (!senseAny) {
            return false;
        }
        MiningAssistConfig cfg = config;
        UUID id = bot.getUUID();
        GateCache.Verdict cached = GATE.fresh(id, serverTick);
        if (cached != null && (!cached.enabled()
                || stillOpen(originKindOf(bot), MiningEvidenceAudit.hasSession(id)))) {
            return cached.enabled();
        }
        String deny = resolveDeny(cfg, FORCED.contains(id), originKindOf(bot),
                MiningEvidenceAudit.hasSession(id), tpsDegraded(bot));
        GateCache.Verdict previous = GATE.last(id);
        GateCache.Verdict verdict = GATE.put(id, serverTick, deny == null, deny);
        if (previous == null
                || previous.enabled() != verdict.enabled()
                || !Objects.equals(previous.denyReason(), verdict.denyReason())) {
            BotLog.task(bot, "assist_gate",
                    "enabled", verdict.enabled(),
                    "deny", deny == null ? "-" : deny,
                    "mode", cfg.mode(),
                    "forced", FORCED.contains(id));
        }
        return verdict.enabled();
    }

    /** The deny reason of the last gate evaluation for the bot ({@code mode_off}, {@code origin} ...), or null when open or unknown. */
    public static String lastDenyReason(UUID botId) {
        GateCache.Verdict last = GATE.last(botId);
        return last == null ? null : last.denyReason();
    }

    /** P1 contract M3: made public so {@code task/DetourSafetyGate} (item 2) can read the live TPS verdict. */
    public static boolean tpsDegraded(AIPlayerEntity bot) {
        if (TpsGuard.isForcedDegraded(bot.getUUID())) {
            return true;
        }
        Boolean override = testTpsDegraded;
        if (override != null) {
            return override;
        }
        MinecraftServer server = bot.getServer();
        return server != null && TpsGuard.INSTANCE.snapshot(server).degraded();
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    /**
     * Bot unload ({@code clearTransient}): drops the bot's state, gate verdict, dug ring and failure
     * bookkeeping. The forced flag stays (a death or reset must not switch a test bot off).
     */
    public static void clearBot(AIPlayerEntity bot) {
        MiningAssistRegistry.clear(bot);
        GATE.remove(bot.getUUID());
        FAILURES.clear(bot.getUUID());
        // P1 (F.4, M5): a detour's soft claims must not outlive the bot's own transient state.
        OreClaims.releaseAll(bot.getUUID());
    }

    /** Same as {@link #clearBot} plus the POI dedupe registry — genuine
     * bot-unload/death/reset only. Deliberately NOT folded into {@link #clearBot}: that method is also called
     * from {@code MiningAssistCoordinator.notSensing}'s 2400-tick idle-release path, which fires while a bot
     * is paused mid an open POI hold (a paused bot is, by definition, not being sensed). Clearing PoiRegistry/
     * PoiRegistry there would silently drop the hold's dedupe state and trigger a spurious restart-rehydration
     * notice roughly every 2 minutes for as long as the hold stays open. */
    public static void clearBotUnload(AIPlayerEntity bot) {
        clearBot(bot);
        // P2: POI dedupe/registry is per-bot transient state too, but
        // only safe to drop on a genuine unload, not on the soft idle-release above.
        PoiRegistry.clear(bot.getUUID());
    }

    /** The coordinator's exception fence: per-bot failure log throttle and sensing cooldown. */
    public static SenseFailureGate failures() {
        return FAILURES;
    }

    /**
     * World unload ({@code clearWorldRuntime}): drops every bot state, gate verdict, forced flag and TPS
     * override, and rebuilds the tick headroom. The placed-cells ledger is persisted separately
     * ({@link BotEdits}) and is not touched here. The config stays.
     */
    public static void clearWorldRuntime() {
        MiningAssistRegistry.clearAll();
        GATE.clear();
        FAILURES.clearAll();
        FORCED.clear();
        testTpsDegraded = null;
        BlockFactsAdapter.clearCache();
        rebuildHeadroom();
        refreshSenseAny();
        // P1 (F.4, M5): the detour's own server-wide statics (design 2.4) are not owned by any bot's state.
        OreClaims.clearAll();
        MissionAssistLedger.clearAll();
        RouteBudget.shared().reset();
        // P2: same reasoning for the POI registry and the mandatory latch.
        PoiRegistry.clearAll();
        // P3: the advisor's cross-bot verdict cache and its mission/global consult budget.
        PoiCache.clearAll();
        PoiConsultBudget.clearAll();
    }

    /** Restores the shipped defaults and drops everything (unit tests). */
    public static synchronized void resetForTests() {
        harnessOff = false;
        lastFileRoot = null;
        lastEnv = PROCESS_ENV;
        hookFailureNotes = 0;
        tickStartNanos = 0L;
        clearWorldRuntime();
        config = MiningAssistConfig.parse(null, PROCESS_ENV, SHIPPED_DEFAULT_MODE, false);
        rebuildHeadroom();
        refreshSenseAny();
        BotEdits.resetForTests();
    }

    // ---------------------------------------------------------------------------------------
    // Small shared helpers
    // ---------------------------------------------------------------------------------------

    /** The bot's server tick, or 0 when it has no server yet. */
    public static int serverTick(AIPlayerEntity bot) {
        MinecraftServer server = bot.getServer();
        return server == null ? 0 : server.getTickCount();
    }

    /** Logs the first few failures of an exception-free hook, then stays quiet. */
    public static void noteHookFailure(String hook, RuntimeException exception) {
        if (hookFailureNotes < 3) {
            hookFailureNotes++;
            BotLog.error("assist_hook_failed", exception, "hook", hook);
        }
    }
}

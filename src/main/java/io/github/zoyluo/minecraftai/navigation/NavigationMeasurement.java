package io.github.zoyluo.minecraftai.navigation;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.pathfinding.AStarPathfinder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Opt-in, per-run navigation measurement capture.
 *
 * <p>This is deliberately not a performance policy and it never changes a route. The only built-in
 * caller is the navigation-course GameTest fixture when the explicit {@value #MODE_PROPERTY}={@value
 * #SCALE_ONE_GAMETEST} property is present. That mode records elapsed server-thread durations with the legacy
 * A* allowance restored to scale one, but remains an <em>unpaced GameTest</em>; its data must never
 * be described as production wall-clock route time.</p>
 */
public final class NavigationMeasurement {
    /** Explicit JVM property used only by the P3 evidence runner. */
    public static final String MODE_PROPERTY = "minecraftai.nav.measurement";
    /** The only supported opt-in value: unpaced GameTest, with the legacy A* budget set to one. */
    public static final String SCALE_ONE_GAMETEST = "scale1";
    /** Provenance written into raw artifacts. It intentionally does not say "production". */
    public static final String ENVIRONMENT = "gametest_unpaced";

    private static final Object LOCK = new Object();
    /** Volatile so all ordinary hot paths can return before taking {@link #LOCK} or a timestamp. */
    private static volatile Session active;
    private static long nextRunId;

    private NavigationMeasurement() {
    }

    /** Whether this JVM explicitly asked a navigation-course fixture for scale-one measurement. */
    public static boolean scaleOneGameTestRequested() {
        return SCALE_ONE_GAMETEST.equalsIgnoreCase(System.getProperty(MODE_PROPERTY, "").trim());
    }

    /**
     * Opens one scale-one GameTest capture, or returns {@code null} when the opt-in property is absent.
     * The assertion against {@link AStarPathfinder#harnessTimeScaleForDiagnostics()} prevents an
     * accidentally scaled legacy run from becoming an evidence row.
     */
    public static Run startScaleOneGameTest(String course, NavEngine engine, List<UUID> botIds) {
        if (!scaleOneGameTestRequested()) {
            return null;
        }
        if (AStarPathfinder.harnessTimeScaleForDiagnostics() != 1L) {
            throw new IllegalStateException("scale-one navigation measurement requires legacy A* scale 1");
        }
        if (course == null || course.isBlank() || engine == null || botIds == null || botIds.isEmpty()) {
            throw new IllegalArgumentException("course, engine and at least one bot are required");
        }
        Set<UUID> ids = new HashSet<>(botIds);
        if (ids.size() != botIds.size() || ids.contains(null)) {
            throw new IllegalArgumentException("measurement bot ids must be unique and non-null");
        }
        synchronized (LOCK) {
            if (active != null) {
                throw new IllegalStateException("a navigation measurement is already active");
            }
            Run run = new Run(++nextRunId);
            active = new Session(run, course, engine, ids);
            return run;
        }
    }

    /** Starts a full measured bot tick, or returns zero for a bot outside the active capture. */
    public static long beginBotTick(AIPlayerEntity bot) {
        if (bot == null) {
            return 0L;
        }
        return beginBotTick(bot.getUUID());
    }

    /** Completes a full measured bot tick. */
    public static void endBotTick(AIPlayerEntity bot, long startedNanos) {
        if (bot != null) {
            endBotTick(bot.getUUID(), startedNanos);
        }
    }

    static long beginBotTick(UUID botId) {
        Session session = active;
        if (session == null || !session.botIds.contains(botId)) {
            return 0L;
        }
        synchronized (LOCK) {
            session = active;
            if (session == null || !session.botIds.contains(botId)) {
                return 0L;
            }
            // A direct failure marker belongs to one bot tick. Clear an unconsumed marker from
            // the previous tick before this tick can record its driver evidence.
            session.directBaritoneFallbacksThisTick.remove(botId);
            return System.nanoTime();
        }
    }

    static void endBotTick(UUID botId, long startedNanos) {
        if (startedNanos <= 0L) {
            return;
        }
        recordBotTick(botId, System.nanoTime() - startedNanos);
    }

    /** Starts a full server-tick sample only while a navigation measurement is active. */
    public static long beginServerTick() {
        if (active == null) {
            return 0L;
        }
        synchronized (LOCK) {
            return active == null ? 0L : System.nanoTime();
        }
    }

    /** Completes a full server-tick sample. */
    public static void endServerTick(long startedNanos) {
        if (startedNanos <= 0L || active == null) {
            return;
        }
        synchronized (LOCK) {
            if (active != null) {
                active.serverTicks.add(System.nanoTime() - startedNanos);
            }
        }
    }

    /** Records the effective engine observed for one measured bot; a mismatch invalidates the row. */
    public static void noteEffectiveEngine(UUID botId, NavEngine effective) {
        Session session = active;
        if (session == null || !session.botIds.contains(botId)) {
            return;
        }
        synchronized (LOCK) {
            if (active != null && active.botIds.contains(botId) && active.engine != effective) {
                active.engineIsolated = false;
            }
        }
    }

    /**
     * Records what actually drove a measured bot's tick. A non-driven tick can still run the
     * legacy task scheduler to admit a Baritone route, so the legacy-update counter is evidence
     * rather than an automatic Baritone mismatch. A driven Baritone tick that then falls through
     * to {@code ActionPack.onUpdate()} is an actual fallback and invalidates a Baritone capture.
     */
    public static void noteDriver(AIPlayerEntity bot, boolean baritoneDrove, boolean legacyUpdated,
                                  NavEngine actionPackOwner) {
        if (bot != null) {
            noteDriver(bot.getUUID(), baritoneDrove, legacyUpdated, actionPackOwner, actionPackOwner);
        }
    }

    /**
     * As {@link #noteDriver(AIPlayerEntity, boolean, boolean, NavEngine)}, while preserving the
     * ActionPack owner on both sides of its update. A direct legacy walk, mining action, or step
     * can finish in one update, so checking only after it would turn a real fallback into an
     * apparently idle Baritone-labelled row.
     */
    public static void noteDriver(AIPlayerEntity bot, boolean baritoneDrove, boolean legacyUpdated,
                                  NavEngine actionPackOwnerBeforeUpdate, NavEngine actionPackOwnerAfterUpdate) {
        if (bot != null) {
            noteDriver(bot.getUUID(), baritoneDrove, legacyUpdated,
                    actionPackOwnerBeforeUpdate, actionPackOwnerAfterUpdate);
        }
    }

    static void noteDriver(UUID botId, boolean baritoneDrove, boolean legacyUpdated) {
        noteDriver(botId, baritoneDrove, legacyUpdated, null, null);
    }

    static void noteDriver(UUID botId, boolean baritoneDrove, boolean legacyUpdated, NavEngine actionPackOwner) {
        noteDriver(botId, baritoneDrove, legacyUpdated, actionPackOwner, actionPackOwner);
    }

    static void noteDriver(UUID botId, boolean baritoneDrove, boolean legacyUpdated,
                           NavEngine actionPackOwnerBeforeUpdate, NavEngine actionPackOwnerAfterUpdate) {
        Session session = active;
        if (session == null || !session.botIds.contains(botId)) {
            return;
        }
        synchronized (LOCK) {
            if (active == null || !active.botIds.contains(botId)) {
                return;
            }
            if (baritoneDrove) {
                active.baritoneDriverTicks++;
                if (active.engine == NavEngine.LEGACY) {
                    active.engineIsolated = false;
                }
            }
            if (legacyUpdated) {
                active.legacyActionPackTicks++;
            }
            // An ActionPack update can be a harmless scheduler tick that admits a new Baritone
            // route. It is a real legacy fallback only when that update owns a legacy controller
            // (path executor/direct walk/mining/step), or when a Baritone-driven tick fell
            // through to ActionPack after its post-physics half failed.
            boolean legacyOwnerObserved = actionPackOwnerBeforeUpdate == NavEngine.LEGACY
                    || actionPackOwnerAfterUpdate == NavEngine.LEGACY;
            boolean baritoneOwnerObserved = actionPackOwnerBeforeUpdate == NavEngine.BARITONE
                    || actionPackOwnerAfterUpdate == NavEngine.BARITONE;
            boolean directFallbackAlreadyCounted = active.directBaritoneFallbacksThisTick.remove(botId);
            if ((baritoneDrove && legacyUpdated)
                    || (active.engine == NavEngine.BARITONE && legacyUpdated && legacyOwnerObserved)) {
                if (!directFallbackAlreadyCounted) {
                    active.baritoneFallbacks++;
                }
                active.engineIsolated = false;
            }
            if (active.engine == NavEngine.LEGACY && baritoneOwnerObserved) {
                active.engineIsolated = false;
            }
        }
    }

    /**
     * Records a Baritone-to-legacy fallback from a path that owns the bot rather than the
     * selector's UUID-only request seam. This deliberately shares the selector fallback counter:
     * either condition means a Baritone-labelled capture is no longer engine-isolated. The
     * volatile session check keeps ordinary per-tick failure paths allocation-, lock-, and
     * clock-free when no measurement is active.
     */
    public static void noteBaritoneFallback(AIPlayerEntity bot) {
        if (bot != null) {
            noteBaritoneFallback(bot.getUUID());
        }
    }

    static void noteBaritoneFallback(UUID botId) {
        Session session = active;
        if (session == null || !session.botIds.contains(botId)) {
            return;
        }
        synchronized (LOCK) {
            if (active != null && active.botIds.contains(botId) && active.engine == NavEngine.BARITONE) {
                active.baritoneFallbacks++;
                active.directBaritoneFallbacksThisTick.add(botId);
                active.engineIsolated = false;
            }
        }
    }

    /**
     * Records one route-planning call. {@code wallNanos} is the actual caller duration; {@code reportedMillis}
     * is the engine's own reported search duration. Keeping both avoids silently treating a cached legacy
     * result or Baritone's internal search timer as the same measurement.
     */
    public static void recordPlanner(AIPlayerEntity bot, NavEngine engine, String phase, long wallNanos,
                                     long reportedMillis, int nodes, int moves, String outcome) {
        if (bot != null) {
            recordPlanner(bot.getUUID(), engine, phase, wallNanos, reportedMillis, nodes, moves, outcome);
        }
    }

    static void recordBotTick(UUID botId, long nanos) {
        if (nanos < 0L) {
            return;
        }
        Session session = active;
        if (session == null || !session.botIds.contains(botId)) {
            return;
        }
        synchronized (LOCK) {
            if (active != null && active.botIds.contains(botId)) {
                active.engineTicks.add(nanos);
            }
        }
    }

    static void recordPlanner(UUID botId, NavEngine engine, String phase, long wallNanos,
                              long reportedMillis, int nodes, int moves, String outcome) {
        if (wallNanos < 0L || reportedMillis < 0L || nodes < 0 || moves < 0) {
            return;
        }
        Session session = active;
        if (session == null || !session.botIds.contains(botId)) {
            return;
        }
        synchronized (LOCK) {
            if (active == null || !active.botIds.contains(botId)) {
                return;
            }
            if (active.engine != engine) {
                active.engineIsolated = false;
            }
            active.planners.add(new PlannerSample(
                    phase == null || phase.isBlank() ? "unspecified" : phase,
                    wallNanos / 1_000_000.0D,
                    reportedMillis,
                    nodes,
                    moves,
                    outcome == null || outcome.isBlank() ? "unspecified" : outcome));
        }
    }

    /** True only for one of the exact bots in the active opt-in capture; a lock-free hot-path guard. */
    public static boolean isCapturing(AIPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        Session session = active;
        return session != null && session.botIds.contains(bot.getUUID());
    }

    /** Finishes the active run. A wrong token is a programming error, not a silently mixed artifact. */
    public static Snapshot finish(Run run, Outcome outcome) {
        synchronized (LOCK) {
            if (run == null || active == null || active.run.id() != run.id()) {
                throw new IllegalStateException("navigation measurement token does not match the active run");
            }
            if (outcome == null) {
                throw new IllegalArgumentException("outcome");
            }
            Session finished = active;
            active = null;
            return finished.snapshot(outcome);
        }
    }

    /** Test-only cleanup for an abandoned fixture. Production never calls this. */
    static void clearForTests() {
        synchronized (LOCK) {
            active = null;
            nextRunId = 0L;
        }
    }

    /** Opaque ownership token; no mutable measurement state escapes to callers. */
    public record Run(long id) {
    }

    /** The existing course outcome duplicated into a self-contained measurement artifact. */
    public record Outcome(boolean reached, int ticks, double damage, int broken, int placed, int waterTicks, int lavaTicks,
                          String reason) {
        public Outcome {
            if (ticks < 0 || broken < 0 || placed < 0 || waterTicks < 0 || lavaTicks < 0 || !Double.isFinite(damage)) {
                throw new IllegalArgumentException("invalid route outcome");
            }
            reason = reason == null || reason.isBlank() ? "-" : reason;
        }
    }

    /** Exact source record for one legacy planner call or Baritone inline admission. */
    public record PlannerSample(String phase, double wallMs, long reportedMs, int nodes, int moves, String outcome) {
    }

    /** Count plus average/p95/max, in milliseconds. The p95 is the nearest-rank 95th percentile. */
    public record Stats(int count, double avgMs, double p95Ms, double maxMs) {
        private static Stats fromNanos(List<Long> samples) {
            if (samples.isEmpty()) {
                return new Stats(0, 0.0D, 0.0D, 0.0D);
            }
            List<Long> sorted = new ArrayList<>(samples);
            sorted.sort(Long::compareTo);
            long total = 0L;
            for (long sample : sorted) {
                total += sample;
            }
            int p95 = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.95D) - 1);
            return new Stats(
                    sorted.size(),
                    total / (double) sorted.size() / 1_000_000.0D,
                    sorted.get(p95) / 1_000_000.0D,
                    sorted.get(sorted.size() - 1) / 1_000_000.0D);
        }

        private static Stats fromPlanner(List<PlannerSample> samples) {
            List<Long> nanos = new ArrayList<>(samples.size());
            for (PlannerSample sample : samples) {
                nanos.add(Math.round(sample.wallMs() * 1_000_000.0D));
            }
            return fromNanos(nanos);
        }
    }

    /** Actual tick ownership recorded by {@code AIPlayerEntity}, not merely the configured selector value. */
    public record DriverStats(int baritoneDriverTicks, int legacyActionPackTicks, int baritoneFallbacks) {
    }

    /** Immutable, self-contained summary written beside the existing NAVCOURSE outcome row. */
    public record Snapshot(String course, NavEngine engine, long runId, String environment, long pathfinderBudgetScale,
                           boolean engineIsolated, Stats engineTick, Stats serverTick, Stats planner,
                           DriverStats driver, List<PlannerSample> planners, Outcome outcome) {
        public Snapshot {
            planners = List.copyOf(planners);
        }

        /** Whether this row has every required P3 capture, not whether either engine was faster. */
        public boolean hasRequiredEvidence() {
            return pathfinderBudgetScale == 1L && engineIsolated
                    && engineTick.count() > 0 && serverTick.count() > 0 && planner.count() > 0
                    && driverMatchesRequestedEngine();
        }

        private boolean driverMatchesRequestedEngine() {
            return switch (engine) {
                // A no-route course can legitimately have only synchronous Baritone admissions
                // (and no driven tick); planner.count()/engineIsolated already prove that it was
                // Baritone, while the driver columns make that admission-only behavior explicit.
                case BARITONE -> driver.baritoneFallbacks() == 0;
                case LEGACY -> driver.baritoneDriverTicks() == 0;
            };
        }

        /** Human-readable explanation used by the fixture when an artifact would be incomplete. */
        public String evidenceProblem() {
            List<String> problems = new ArrayList<>();
            if (pathfinderBudgetScale != 1L) {
                problems.add("legacy_budget_scale=" + pathfinderBudgetScale);
            }
            if (!engineIsolated) {
                problems.add("engine_isolation_lost");
            }
            if (engineTick.count() == 0) {
                problems.add("no_engine_tick_samples");
            }
            if (serverTick.count() == 0) {
                problems.add("no_server_tick_samples");
            }
            if (planner.count() == 0) {
                problems.add("no_planner_samples");
            }
            if (engine == NavEngine.BARITONE && driver.baritoneFallbacks() > 0) {
                problems.add("baritone_fallbacks=" + driver.baritoneFallbacks());
            }
            if (engine == NavEngine.LEGACY && driver.baritoneDriverTicks() > 0) {
                problems.add("baritone_drove_legacy_capture=" + driver.baritoneDriverTicks());
            }
            return problems.isEmpty() ? "" : String.join(",", problems);
        }
    }

    private static final class Session {
        private final Run run;
        private final String course;
        private final NavEngine engine;
        private final Set<UUID> botIds;
        /** Direct failure markers that have already incremented this bot's current tick. */
        private final Set<UUID> directBaritoneFallbacksThisTick = new HashSet<>();
        private final List<Long> engineTicks = new ArrayList<>();
        private final List<Long> serverTicks = new ArrayList<>();
        private final List<PlannerSample> planners = new ArrayList<>();
        private int baritoneDriverTicks;
        private int legacyActionPackTicks;
        private int baritoneFallbacks;
        private boolean engineIsolated = true;

        private Session(Run run, String course, NavEngine engine, Set<UUID> botIds) {
            this.run = run;
            this.course = course;
            this.engine = engine;
            this.botIds = Set.copyOf(botIds);
        }

        private Snapshot snapshot(Outcome outcome) {
            return new Snapshot(course, engine, run.id(), ENVIRONMENT, AStarPathfinder.harnessTimeScaleForDiagnostics(),
                    engineIsolated, Stats.fromNanos(engineTicks), Stats.fromNanos(serverTicks), Stats.fromPlanner(planners),
                    new DriverStats(baritoneDriverTicks, legacyActionPackTicks, baritoneFallbacks), planners, outcome);
        }
    }
}

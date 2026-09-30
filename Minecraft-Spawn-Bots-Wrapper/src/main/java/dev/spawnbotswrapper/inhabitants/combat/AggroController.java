package dev.spawnbotswrapper.inhabitants.combat;

import dev.spawnbotswrapper.inhabitants.combat.AggroWorld.Body;
import dev.spawnbotswrapper.inhabitants.combat.AggroWorld.Pos;
import dev.spawnbotswrapper.inhabitants.combat.AggroWorld.SearchSpot;
import dev.spawnbotswrapper.inhabitants.combat.AggroWorld.Watcher;
import dev.spawnbotswrapper.inhabitants.combat.TargetControl.Settings;
import dev.spawnbotswrapper.inhabitants.combat.TargetControl.Target;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The "line of sight hunter" of the hostile inhabitants: what they do about players (and their companions), decided by
 * what they can SEE and HEAR, with no block-distance rule of any kind (the only limit is the mod maximum, 128 blocks:
 * vanilla's line-of-sight cap and PvP BOT's largest targeting distance).
 * <pre>
 *   IDLE --(noticed)--&gt; CHASE --(lost)--&gt; PURSUE --(arrived or blocked)--&gt; SEARCH --(10 s)--&gt; RETURN --(home)--&gt; IDLE
 *   PURSUE / SEARCH / RETURN --(noticed again)--&gt; CHASE          IDLE / PURSUE / SEARCH / RETURN --(hit)--&gt; REACT or PURSUE
 * </pre>
 * <b>Noticing</b> is {@link Perception}: a player that stays in view (front cone, or the periphery; never from behind) for
 * the reaction time (0.25 s and more, longer for what is far, at an angle, sneaking or hard to see), or is heard close by
 * (walking 4, sprinting 8, combat noise 12; a sneaking player is silent; never through a wall). Hearing only adds
 * awareness from behind: it restricts nothing. A player standing behind a bot or sneaking up on it is not noticed until it
 * strikes.
 * <p>
 * <b>CHASE.</b> The noticed player is handed to PvP BOT as its forced target; PvP BOT fights and moves. While the target is
 * visible (occlusion only, no cone: an engaged bot faces its target) the last known position and velocity are kept. Not
 * visible for {@code loseGraceTicks} (a tree trunk does not break a chase), or beyond the mod maximum, and the target is
 * LOST. A target that is dead, gone, in another level or no longer attackable ends the hunt and sends the bot home.
 * <p>
 * <b>PURSUE.</b> PvP BOT's target is cleared (so it does not track through walls) and the bot walks to the last known
 * position along a planned route ({@link PathPlanner}); if the player was moving it continues a few blocks along the last
 * heading. No route, or no progress: search from where it stands.
 * <p>
 * <b>SEARCH</b> lasts {@code searchTicks} (10 s): look all the way round, walk to the most promising nearby spots (along the
 * last heading, corners, doorways and branches from which hidden space opens up, not yet visited: {@link SearchPlanner}),
 * look round again, repeat; a sound heard from behind cover moves the search there. Seeing the player again (reaction time
 * applies) is a new CHASE.
 * <p>
 * <b>RETURN.</b> Walks back to the HOME anchor: where the bot stood when it FIRST started aggro'ing. Home is kept through
 * every cycle until the bot is back within {@code returnArriveDistance}; then it is cleared. The bot ALWAYS returns
 * to it, never to a temporary point. If it cannot get there within {@code returnMaxTicks} it gives up where it stands.
 * Never a teleport; the bot never breaks or places blocks for any of this.
 * <p>
 * <b>Being hit</b> by a valid player makes the bot aware of the attacker at once (the last known position is where the
 * attacker stood). Visible: it turns to the pain and reacts after the reaction time (PvP BOT's instant revenge is
 * suppressed meanwhile), then CHASE. Not visible (an arrow from cover): PURSUE to that position.
 * <p>
 * <b>Mobs.</b> PvP BOT's native revenge still fights mobs (a forced target cannot name a specific mob): the fight is tracked,
 * lost sight for {@code loseGraceTicks} ends it, and the bot walks home; no pursuit and no search for mobs.
 * <b>External</b> forced targets (a {@code /pvpbot} command, another mod) are tracked for the status only and are never
 * cleared, pursued or returned from.
 * <p>
 * Pure decision logic over {@link AggroWorld}, {@link TargetControl} and {@link PathPlanner}; no Minecraft or PvP BOT
 * classes. Server thread only. {@link #tick} must run AFTER PvP BOT's own tick of the same server tick.
 */
public final class AggroController {

    /** The mod maximum: vanilla {@code hasLineOfSight}'s cap and PvP BOT's largest {@code maxTargetDistance}. */
    public static final double MOD_MAX = 128.0;

    /** Tunables; see {@code InhabitantsConfig.Aggro}. */
    public record Config(boolean enabled, boolean requireLineOfSight, int loseGraceTicks, int searchTicks,
                         double returnArriveDistance, int stuckTicks, int returnMaxTicks, int scanIntervalTicks,
                         Perception.Params perception) {
        public static Config defaults() {
            return new Config(true, true, 10, 200, 1.5, 40, 1200, 3, Perception.Params.defaults());
        }

        public Config {
            loseGraceTicks = Math.max(1, loseGraceTicks);
            searchTicks = Math.max(1, searchTicks);
            stuckTicks = Math.max(1, stuckTicks);
            returnMaxTicks = Math.max(1, returnMaxTicks);
            scanIntervalTicks = Math.max(1, scanIntervalTicks);
            perception = perception == null ? Perception.Params.defaults() : perception;
        }

        /** The same configuration with other perception rules. */
        public Config withPerception(Perception.Params p) {
            return new Config(enabled, requireLineOfSight, loseGraceTicks, searchTicks, returnArriveDistance,
                    stuckTicks, returnMaxTicks, scanIntervalTicks, p);
        }
    }

    /** Where log lines go. */
    public interface Log {
        void debug(String message);

        void info(String message);

        void warn(String message);
    }

    /** What the controller is doing right now, for the log and the diagnostics. */
    public enum Mode {
        /** Not yet run. */
        UNKNOWN("not started"),
        /** Noticing players for idle inhabitants. */
        ACTIVE("active"),
        /** Switched off by the configuration. */
        OFF("off (configuration)"),
        /** PvP BOT's auto-target is on: PvP BOT acquires by itself; only the supervision (lost, search, return) applies. */
        INERT_AUTO_TARGET("inert, PvP BOT auto-target is on"),
        /** PvP BOT's combat routine is off. */
        INERT_COMBAT_OFF("inert, PvP BOT combat is off"),
        /** PvP BOT's settings cannot be read, so nothing can be validated. */
        SETTINGS_UNREADABLE("inert, PvP BOT settings unreadable"),
        /** A required PvP BOT member is missing. */
        UPSTREAM_UNAVAILABLE("disabled, PvP BOT target control unavailable"),
        /** Too many consecutive upstream failures. */
        FAILED("disabled after repeated PvP BOT failures");

        private final String text;

        Mode(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    /** Where one inhabitant is in the hunt. */
    public enum Phase {
        IDLE,
        /** Hit by a visible attacker: turning to it, reacting; PvP BOT's instant revenge is held back. */
        REACT,
        CHASE,
        PURSUE,
        SEARCH,
        RETURN
    }

    /** How an engagement began. */
    public enum Cause {
        /** This controller noticed a player (sight or hearing) and forced them on the bot. */
        ACQUIRED,
        /** The bot was hit by the target. */
        HIT,
        /** Anything else PvP BOT holds (a mob it decided to fight, a faction enemy, somebody else's force). */
        OTHER
    }

    /** What is being tracked. */
    private enum Kind {
        /** A player this controller handed to PvP BOT (or one PvP BOT holds that is treated the same). */
        PLAYER,
        /** A mob PvP BOT fights on its own (revenge): tracked, never pursued or searched for. */
        MOB,
        /** Somebody else's forced target: tracked for the status only. */
        EXTERNAL
    }

    /** Snapshot for diagnostics. */
    public record Stats(Mode mode, int chasing, int pursuing, int searching, int returning, long acquisitions,
                        long hits, long returned, Map<String, Long> giveUps, Map<String, Long> abandoned) {
    }

    /** Consecutive upstream failures after which the controller switches itself off. */
    static final int MAX_CONSECUTIVE_FAILURES = 20;
    /** At most one INFO line per bot per this many ticks for starts and give-ups; the rest go to debug. */
    static final long INFO_GAP_TICKS = 200;
    /** Speed handed to PvP BOT's move-toward call (1.0 is what its own patrols use). */
    static final double WALK_SPEED = 1.0;
    /** Horizontal distance (blocks) from the last known position at which a pursuit has arrived. */
    static final double ARRIVE_LKP = 1.5;
    /** Horizontal distance (blocks) from a search spot at which the bot has arrived and looks round. */
    static final double ARRIVE_SPOT = 1.2;
    /** Blocks around the search focus that candidate search spots are taken from. */
    static final double SPOT_RADIUS = 12.0;
    /** Ticks a bot walks to one search spot at most before it looks round wherever it is. */
    static final int MAX_SPOT_TICKS = 100;
    /** Look round: quarter turns, and ticks spent looking along each. */
    static final int SWEEP_STEPS = 4;
    static final int SWEEP_STEP_TICKS = 6;
    /** Ticks a planned route stays unreplanned after a plan (bounds the planning cost), and after a failed one. */
    static final int PLAN_INTERVAL_TICKS = 20;
    static final int PLAN_RETRY_TICKS = 60;
    /** Route plans per server tick, all bots together (the cost bound); the rest wait a tick. */
    static final int MAX_PLANS_PER_TICK = 2;
    /** A player moving faster than this (blocks per tick, 1.2 blocks per second) has a heading worth following. */
    static final double SIGNIFICANT_SPEED = 0.06;
    /** Ticks a bot keeps scanning every tick after something was in view (the exposure runs are watched closely). */
    static final int WATCH_TICKS = 3;
    /** Times a stuck route is replanned before the walk gives up (pursuit and search). */
    static final int MAX_STUCK_REPLANS = 2;
    /** Vertical distance (blocks) within which the bot counts as being at home. */
    static final double HOME_HEIGHT = 4.0;

    /** Where a hunt began: a position and the level it is in. */
    record Origin(Pos pos, Object dimension) {
    }

    private static final class BotState {
        Phase phase = Phase.IDLE;
        /** Where the bot FIRST started aggro'ing; kept until it is back there. */
        Origin home;
        /** PvP BOT's patrol of this bot is paused while the hunt walks it (see {@link TargetControl#pausePatrol}). */
        boolean patrolPaused;

        // ---- the target
        Kind kind = Kind.PLAYER;
        Cause cause = Cause.OTHER;
        String target;
        Object entity;
        /** The target the hunt began with; a forced name equal to it is never "somebody else's". */
        String firstName;
        /** The forced name this controller set, or null. */
        String forcedByUs;
        /** How the target was noticed: sight or hearing; null for a hit or a hunt PvP BOT began itself. */
        Perception.Sense noticed;
        /** Ticks of exposure (or of reaction delay) before the hunt began. */
        long reaction;
        long startTick;
        int unseen;
        Pos lkp;
        double lastX;
        double lastZ;
        boolean haveLast;
        /** Smoothed horizontal velocity of the target while it was visible (blocks per tick). */
        double velX;
        double velZ;
        long lastSeenTick;
        /** Where the bot was as of the last tick (for the status text). */
        Pos pos;

        // ---- REACT
        long reactUntil;

        // ---- walking
        long phaseStart;
        PathFollower route;
        Pos routeGoal;
        boolean routePartial;
        long nextPlanTick;
        int stuckReplans;
        double straightBest = Double.MAX_VALUE;
        long straightBestTick;

        // ---- PURSUE
        Pos pursueGoal;
        boolean predicted;

        // ---- SEARCH
        long searchStart;
        Pos focus;
        final List<Pos> visited = new ArrayList<>();
        int points;
        Pos spot;
        long spotStart;
        boolean sweeping;
        int sweepStep;
        long sweepStepStart;
        double sweepBase;
        Pos hint;
        long hintTick = Long.MIN_VALUE;
        long hintUsed = Long.MIN_VALUE;
    }

    private enum Walk {
        WALKING, ARRIVED, BLOCKED
    }

    private final Supplier<Config> config;
    private final TargetControl up;
    private final PathPlanner planner;
    private final Log log;

    private final Map<String, BotState> states = new LinkedHashMap<>();
    /** Bots that have something in view: scanned every tick until this tick. */
    private final Map<String, Long> watching = new HashMap<>();
    private final ExposureTracker exposure = new ExposureTracker();
    private final Map<String, Long> lastInfo = new HashMap<>();
    private final Map<String, Long> giveUps = new TreeMap<>();
    private final Map<String, Long> abandoned = new TreeMap<>();
    private final Set<String> warned = new HashSet<>();
    private long acquisitions;
    private long hitEngagements;
    private long returned;
    private long lastTick = Long.MIN_VALUE;
    private long lastSettingsRead = Long.MIN_VALUE;
    private Settings settings;
    private Mode mode = Mode.UNKNOWN;
    private double modMax = MOD_MAX;
    private int failures;
    private boolean failed;
    private int plansThisTick;

    public AggroController(Supplier<Config> config, TargetControl up, PathPlanner planner, Log log) {
        this.config = config;
        this.up = up;
        this.planner = planner == null ? PathPlanner.NONE : planner;
        this.log = log;
    }

    // ================================================================ tick

    /** One server tick, after PvP BOT's own tick. Cheap when nothing is going on: O(inhabitants) reads. */
    public void tick(long now, AggroWorld world) {
        if (now < lastTick) {
            // A new server: ticks restarted at 0. Nothing timed in the old server's ticks may outlive it.
            reset();
        }
        lastTick = now;
        plansThisTick = 0;
        Config cfg = config.get();
        if (failed) {
            return;
        }
        if (!up.available()) {
            setMode(Mode.UPSTREAM_UNAVAILABLE, up.unavailableReason());
            states.clear();
            return;
        }
        if (lastSettingsRead == Long.MIN_VALUE || now - lastSettingsRead >= cfg.scanIntervalTicks()) {
            lastSettingsRead = now;
            settings = readSettings();
            modMax = settings == null ? MOD_MAX : Math.max(4.0, Math.min(MOD_MAX, settings.maxTargetDistance()));
        }
        if (!cfg.enabled()) {
            setMode(Mode.OFF, null);
            dropAll("aggro disabled");
            return;
        }
        setMode(modeFor(settings), null);
        boolean prune = !states.isEmpty() || !watching.isEmpty();
        Set<String> seen = prune ? new HashSet<>() : null;
        for (Watcher bot : world.inhabitants()) {
            if (failed) {
                return;
            }
            String name = bot.name();
            if (seen != null) {
                seen.add(name);
            }
            try {
                step(now, world, bot, cfg);
                failures = 0;
            } catch (RuntimeException e) {
                noteFailure("handling " + name, e);
            }
        }
        if (prune) {
            for (String name : new ArrayList<>(states.keySet())) {
                if (!seen.contains(name)) {
                    dropBot(name, "bot left");
                }
            }
            watching.keySet().removeIf(n -> !seen.contains(n));
        }
    }

    /** Forget everything: the server stopped or restarted. Does not touch PvP BOT. */
    public void reset() {
        states.clear();
        watching.clear();
        exposure.clear();
        lastInfo.clear();
        lastTick = Long.MIN_VALUE;
        lastSettingsRead = Long.MIN_VALUE;
        settings = null;
        modMax = MOD_MAX;
        mode = Mode.UNKNOWN;
        failures = 0;
        failed = false;
    }

    // ================================================================ per bot

    private boolean active() {
        return mode == Mode.ACTIVE;
    }

    private boolean mayStart() {
        return settings == null || settings.combatEnabled();
    }

    private void step(long now, AggroWorld world, Watcher bot, Config cfg) {
        String name = bot.name();
        BotState st = states.get(name);
        if (!bot.alive()) {
            if (st != null) {
                dropBot(name, "bot died");
            }
            watching.remove(name);
            return;
        }
        // Drained every tick, so each hit is reported once and the poller's baseline stays current.
        Body hitter = bot.newHitAttacker();
        if (st != null) {
            st.pos = bot.position();
        }
        if (st != null) {
            switch (st.phase) {
                case CHASE -> {
                    chase(now, world, bot, st, cfg);
                    return;
                }
                case REACT -> {
                    react(now, world, bot, st, cfg, hitter);
                    return;
                }
                default -> {
                }
            }
        }
        // IDLE, PURSUE, SEARCH or RETURN. PvP BOT's own target (revenge after a hit, a mob fight, somebody's force) first.
        if (mayStart()) {
            Target held = up.currentTarget(name);
            if (held != null) {
                st = startFromHeld(now, world, bot, st, held, cfg);
                if (st.phase == Phase.CHASE || st.phase == Phase.REACT || st.phase == Phase.PURSUE) {
                    return;
                }
            } else if (hitter != null && active() && validAttacker(bot, hitter)) {
                st = onHit(now, world, bot, st, hitter, cfg);
                return;
            }
        }
        if (st == null) {
            if (active()) {
                scanForChase(now, world, bot, null, cfg);
            }
            return;
        }
        switch (st.phase) {
            case PURSUE -> pursue(now, world, bot, st, cfg);
            case SEARCH -> search(now, world, bot, st, cfg);
            case RETURN -> ret(now, world, bot, st, cfg);
            default -> {
            }
        }
    }

    private BotState state(String name) {
        return states.computeIfAbsent(name, k -> new BotState());
    }

    private Origin here(Watcher bot) {
        return new Origin(bot.position(), bot.dimension());
    }

    // ---------------------------------------------------------------- starting a hunt

    /** PvP BOT holds a target this controller did not hand over: revenge, a mob fight, or somebody else's force. */
    private BotState startFromHeld(long now, AggroWorld world, Watcher bot, BotState existing, Target t,
                                   Config cfg) {
        String name = bot.name();
        String forced = up.forcedTarget(name);
        Body body = world.bodyOf(t.entity());
        boolean player = body != null && body.isPlayer();
        BotState st = existing != null ? existing : state(name);
        if (forced != null) {
            // A forced name that appears while nothing of ours is chasing is somebody else's (a command, another mod).
            beginHeld(now, bot, st, t, Kind.EXTERNAL, Cause.OTHER);
            log.debug("aggro: " + name + " fights " + t.name() + " on a forced target set by someone else; tracked only");
            return st;
        }
        if (player && active()) {
            // A hit: the bot is aware of the attacker now; PvP BOT's instant revenge is held back.
            if (body.alive() && attackableNow(body)) {
                return onHit(now, world, bot, st, body, cfg);
            }
        }
        beginHeld(now, bot, st, t, player ? Kind.PLAYER : Kind.MOB, t.revenge() ? Cause.HIT : Cause.OTHER);
        hitEngagements++;
        logInfo(now, name, "aggro: " + name + " fights " + t.name() + " (" + st.cause.name().toLowerCase(Locale.ROOT)
                + (st.kind == Kind.MOB ? ", a mob" : "") + ")");
        return st;
    }

    /** Begins tracking a fight PvP BOT started itself (or somebody forced). */
    private void beginHeld(long now, Watcher bot, BotState st, Target t, Kind kind, Cause cause) {
        st.kind = kind;
        st.cause = cause;
        st.target = t.name();
        st.firstName = t.name();
        st.entity = t.entity();
        st.forcedByUs = null;
        st.noticed = null;
        st.reaction = 0;
        st.startTick = now;
        st.unseen = 0;
        st.haveLast = false;
        st.velX = 0.0;
        st.velZ = 0.0;
        st.route = null;
        if (kind != Kind.EXTERNAL && st.home == null) {
            st.home = here(bot);
        }
        st.phase = Phase.CHASE;
        exposure.forgetObserver(bot.name());
    }

    /** True when {@code b} may be treated as an attacker to hunt: alive, a player, in this level, and not exempt. */
    private boolean validAttacker(Watcher bot, Body b) {
        Settings s = settings;
        return s != null && b.alive() && b.isPlayer() && Objects.equals(bot.dimension(), b.dimension())
                && !b.name().equalsIgnoreCase(bot.name()) && (s.attackInvincible() || attackable(b));
    }

    private boolean attackableNow(Body b) {
        Settings s = settings;
        return s != null && (s.attackInvincible() || attackable(b));
    }

    /**
     * A hit by a player. The bot is aware of the attacker at once and knows where it was. Visible: turn to it and react
     * after the reaction time (PvP BOT's instant revenge is cleared now and the target is set when the delay ends).
     * Not visible (an arrow from cover): PURSUE to where the attacker stood.
     */
    private BotState onHit(long now, AggroWorld world, Watcher bot, BotState existing, Body attacker, Config cfg) {
        String name = bot.name();
        BotState st = existing != null ? existing : state(name);
        if (st.home == null) {
            st.home = here(bot);
        }
        st.kind = Kind.PLAYER;
        st.cause = Cause.HIT;
        st.target = attacker.name();
        st.firstName = attacker.name();
        st.entity = attacker.handle();
        st.forcedByUs = null;
        st.noticed = null;
        st.reaction = reactionTicks(cfg);
        st.startTick = now;
        st.unseen = 0;
        st.lkp = attacker.position();
        st.haveLast = false;
        st.velX = 0.0;
        st.velZ = 0.0;
        st.lastSeenTick = now;
        st.route = null;
        exposure.forgetObserver(name);
        hitEngagements++;
        boolean visible = sees(bot, attacker, cfg);
        if (visible) {
            // PvP BOT's instant revenge is held back: its target is cleared now and set when the reaction is over.
            up.clearTarget(name);
            st.phase = Phase.REACT;
            st.reactUntil = now + reactionTicks(cfg);
            logInfo(now, name, "aggro: " + name + " was hit by " + attacker.name() + " and turns to it (reacts in "
                    + reactionTicks(cfg) + " ticks)");
        } else {
            logInfo(now, name, "aggro: " + name + " was hit by " + attacker.name() + " from cover and goes to where "
                    + "it came from");
            beginPursue(now, bot, st, "hit from cover");
        }
        return st;
    }

    private static int reactionTicks(Config cfg) {
        return (int) Math.max(0, Math.ceil(cfg.perception().reactionTicks()));
    }

    /** Tracks the reaction delay after a visible hit; when it is over the attacker is handed to PvP BOT. */
    private void react(long now, AggroWorld world, Watcher bot, BotState st, Config cfg, Body hitter) {
        String name = bot.name();
        Body attacker = world.bodyOf(st.entity);
        if (attacker == null || !attacker.alive() || !Objects.equals(bot.dimension(), attacker.dimension())) {
            beginReturn(now, bot, st, "attacker gone");
            return;
        }
        if (hitter != null && hitter.alive()) {
            st.lkp = hitter.position();
        }
        // PvP BOT re-arms its revenge on every hit; hold it back until the delay is over.
        if (up.currentTarget(name) != null) {
            up.clearTarget(name);
        }
        if (now < st.reactUntil) {
            up.look(bot.handle(), attacker.position());
            return;
        }
        if (!attackableNow(attacker)) {
            beginReturn(now, bot, st, "attacker no longer attackable");
            return;
        }
        if (!sees(bot, attacker, cfg)) {
            beginPursue(now, bot, st, "attacker out of sight after the reaction");
            return;
        }
        startChase(now, bot, st, attacker, null, st.reaction, Cause.HIT);
    }

    /**
     * Hands {@code target} to PvP BOT as the forced target and starts CHASE. The first hunt of a bot without a home makes
     * the bot's position its home.
     */
    private void startChase(long now, Watcher bot, BotState existing, Body target, Perception.Sense how, long reaction,
                            Cause cause) {
        String name = bot.name();
        up.setTarget(name, target.name());
        BotState st = existing != null ? existing : state(name);
        if (st.home == null) {
            st.home = here(bot);
        }
        st.phase = Phase.CHASE;
        st.kind = Kind.PLAYER;
        st.cause = cause;
        st.target = target.name();
        st.firstName = target.name();
        st.forcedByUs = target.name();
        st.entity = target.handle();
        st.noticed = how;
        st.reaction = reaction;
        st.startTick = now;
        st.unseen = 0;
        st.lkp = target.position();
        st.lastSeenTick = now;
        st.haveLast = false;
        st.velX = 0.0;
        st.velZ = 0.0;
        st.route = null;
        exposure.forgetObserver(name);
        if (cause == Cause.ACQUIRED) {
            acquisitions++;
        }
        logInfo(now, name, "aggro: " + name + (cause == Cause.HIT ? " reacted to the hit and chases "
                : " noticed " + target.name() + " by " + senseText(how) + " after " + reaction + " ticks and chases ")
                + target.name() + " (" + fmt(bot.distanceTo(target)) + " blocks away)");
    }

    // ---------------------------------------------------------------- noticing

    /**
     * Looks for a player this bot notices now (IDLE, PURSUE, SEARCH, RETURN): every scan interval (staggered per bot), and
     * every tick while something is in view. Starts a CHASE when the exposure has lasted long enough. An occluded sound
     * is only a hint for the search.
     *
     * @return true when a chase started
     */
    private boolean scanForChase(long now, AggroWorld world, Watcher bot, BotState st, Config cfg) {
        String name = bot.name();
        Settings s = settings;
        if (s == null || !mayStart()) {
            return false;
        }
        Long until = watching.get(name);
        boolean watched = until != null && until >= now;
        if (until != null && !watched) {
            watching.remove(name);
        }
        boolean due = watched || (now + stagger(name, cfg.scanIntervalTicks())) % cfg.scanIntervalTicks() == 0;
        if (!due) {
            return false;
        }
        List<? extends Body> near = world.playersWithin(bot, modMax);
        if (near.isEmpty()) {
            return false;
        }
        // Cheap validity first (most of a crowded server's players are other inhabitants, which are no targets), then the
        // few that are left nearest first.
        List<Body> ordered = new ArrayList<>(Math.min(near.size(), 8));
        for (Body candidate : near) {
            if (validTarget(bot, candidate, s)) {
                ordered.add(candidate);
            }
        }
        if (ordered.size() > 1) {
            ordered.sort(Comparator.<Body>comparingDouble(bot::distanceTo)
                    .thenComparing(b -> b.name().toLowerCase(Locale.ROOT)));
        }
        boolean anyExposure = false;
        for (Body candidate : ordered) {
            Perception.Reading reading = read(bot, candidate, cfg);
            String key = ExposureTracker.key(name, candidate.name());
            if (reading.exposed()) {
                anyExposure = true;
                long ticks = exposure.sighted(key, now);
                if (Perception.noticed(ticks, reading.requiredTicks())) {
                    startChase(now, bot, st, candidate, reading.sense(), ticks, Cause.ACQUIRED);
                    watching.remove(name);
                    return true;
                }
            } else {
                exposure.missed(key, now);
                if (reading.sense() == Perception.Sense.INVESTIGATE && st != null) {
                    st.hint = candidate.position();
                    st.hintTick = now;
                }
            }
        }
        if (anyExposure) {
            // Watched every tick from now on until a few ticks after the last exposure: an exposure run is only alive
            // for a couple of missed ticks (ExposureTracker), so a run must not be left to the sparse idle cadence.
            watching.put(name, now + WATCH_TICKS);
        }
        return false;
    }

    private static int stagger(String name, int interval) {
        return (name.hashCode() & 0x7fffffff) % Math.max(1, interval);
    }

    /**
     * What {@code bot} makes of {@code candidate} this tick: {@link Perception#read} over the two bodies' eyes, look
     * direction and stance, with occlusion (a clear view, when {@code requireLineOfSight} is on) asked last. Without
     * perception (switched off, or a view that cannot tell the eyes and the stance) it is plain line of sight:
     * in range and unobstructed, at once.
     */
    Perception.Reading read(Watcher bot, Body candidate, Config cfg) {
        Perception.Params p = cfg.perception();
        // Perception off is exactly vanilla hasLineOfSight (one eye-to-eye ray); on, the eye ray and then a body-centre ray.
        BooleanSupplier clear = () -> !cfg.requireLineOfSight()
                || (p.enabled() ? bot.canSee(candidate) : bot.plainLineOfSight(candidate));
        AggroWorld.Senses me = p.enabled() ? bot.senses() : null;
        AggroWorld.Senses them = me == null ? null : candidate.senses();
        double distance = bot.distanceTo(candidate);
        if (me == null || them == null) {
            return Perception.read(p.disabled(), modMax, 0.0, distance, Perception.Subject.player(false, false, false),
                    clear);
        }
        double theta = Perception.angleDeg(me.look().x(), me.look().y(), me.look().z(),
                them.eye().x() - me.eye().x(), them.eye().y() - me.eye().y(), them.eye().z() - me.eye().z());
        return Perception.read(p, modMax, theta, distance, them.subject(), clear);
    }

    /** True when the bot has an unobstructed view of {@code b} within the mod maximum (no cone: an engaged bot faces it). */
    private boolean sees(Watcher bot, Body b, Config cfg) {
        return Objects.equals(bot.dimension(), b.dimension()) && bot.distanceTo(b) <= modMax
                && (!cfg.requireLineOfSight() || bot.canSee(b));
    }

    private static String senseText(Perception.Sense how) {
        return how == Perception.Sense.HEARING ? "hearing" : how == Perception.Sense.SIGHT ? "sight" : "a hit";
    }

    /**
     * The same rules as PvP BOT's own target validity for a player: alive, not spectator / creative / invulnerable unless
     * {@code attackInvincible}, not a faction ally unless friendly fire, and a PvP BOT bot only when
     * {@code targetOtherBots}, any other player only when {@code targetPlayers}. There is no distance rule here.
     */
    boolean validTarget(Watcher bot, Body candidate, Settings s) {
        if (candidate.name().equalsIgnoreCase(bot.name()) || !candidate.alive() || !candidate.isPlayer()) {
            return false;
        }
        if (!Objects.equals(bot.dimension(), candidate.dimension())) {
            return false;
        }
        if (!s.attackInvincible() && !attackable(candidate)) {
            return false;
        }
        if (s.factionsEnabled() && !s.friendlyFire()) {
            try {
                if (up.areAllies(bot.name(), candidate.name())) {
                    return false;
                }
            } catch (RuntimeException e) {
                // Cannot tell whether this is an ally: not attacking is the safe answer.
                warnOnce("factions|" + e.getMessage(), "aggro: PvP BOT's faction check failed (" + e.getMessage()
                        + "); players are not targeted while it does");
                return false;
            }
        }
        return up.isPvpBotBot(candidate.name()) ? s.targetOtherBots() : s.targetPlayers();
    }

    private static boolean attackable(Body b) {
        return !b.spectator() && !b.creative() && !b.invulnerable();
    }

    // ---------------------------------------------------------------- CHASE

    private void chase(long now, AggroWorld world, Watcher bot, BotState st, Config cfg) {
        String name = bot.name();
        Target held = mayStart() ? up.currentTarget(name) : null;
        if (st.kind != Kind.EXTERNAL) {
            String forced = up.forcedTarget(name);
            if (forced != null && isSomebodyElses(forced, st, held)) {
                st.kind = Kind.EXTERNAL;
                log.debug("aggro: " + name + " now has a forced target set by someone else (" + forced + "); tracked only");
            }
        }
        switch (st.kind) {
            case EXTERNAL -> chaseExternal(now, world, bot, st, held, cfg);
            case MOB -> chaseMob(now, world, bot, st, held, cfg);
            default -> chasePlayer(now, world, bot, st, held, cfg);
        }
    }

    /**
     * Whether a forced name is somebody else's. It is ours when this controller set it, when it names the target the hunt
     * began with (PvP BOT writes the attacker's name itself in its wind-burst and elytra flow), or when it names the bot's
     * current revenge target (its last attacker).
     */
    private static boolean isSomebodyElses(String forced, BotState st, Target held) {
        return !forced.equalsIgnoreCase(st.forcedByUs) && !forced.equalsIgnoreCase(st.firstName)
                && !(held != null && held.revenge() && forced.equalsIgnoreCase(held.name()));
    }

    private void chasePlayer(long now, AggroWorld world, Watcher bot, BotState st, Target held, Config cfg) {
        if (held != null) {
            st.entity = held.entity();
            st.target = held.name();
        }
        Body target = world.bodyOf(st.entity);
        String bad = invalidTarget(bot, target);
        if (bad != null) {
            giveUps.merge(bad, 1L, Long::sum);
            up.clearTarget(bot.name());
            beginReturn(now, bot, st, bad);
            return;
        }
        // PvP BOT dropped its target (beyond the mod maximum, or invalid there): the chase is lost.
        boolean visible = held != null && sees(bot, target, cfg);
        if (visible) {
            st.unseen = 0;
            st.lastSeenTick = now;
            Pos p = target.position();
            st.lkp = p;
            if (st.haveLast) {
                st.velX = 0.5 * st.velX + 0.5 * (p.x() - st.lastX);
                st.velZ = 0.5 * st.velZ + 0.5 * (p.z() - st.lastZ);
            }
            st.lastX = p.x();
            st.lastZ = p.z();
            st.haveLast = true;
            return;
        }
        st.unseen++;
        if (st.unseen >= cfg.loseGraceTicks() || held == null) {
            beginPursue(now, bot, st, held == null ? "PvP BOT dropped the target" : "out of sight for "
                    + st.unseen + " ticks");
        }
    }

    private void chaseMob(long now, AggroWorld world, Watcher bot, BotState st, Target held, Config cfg) {
        Body mob = held == null ? null : world.bodyOf(held.entity());
        if (held == null || mob == null || !mob.alive()) {
            up.clearTarget(bot.name());
            giveUps.merge("mob fight over", 1L, Long::sum);
            beginReturn(now, bot, st, "mob fight over");
            return;
        }
        st.entity = held.entity();
        st.target = held.name();
        if (sees(bot, mob, cfg)) {
            st.unseen = 0;
            st.lkp = mob.position();
            return;
        }
        st.unseen++;
        if (st.unseen >= cfg.loseGraceTicks()) {
            up.clearTarget(bot.name());
            giveUps.merge("mob out of sight", 1L, Long::sum);
            beginReturn(now, bot, st, "mob out of sight for " + st.unseen + " ticks");
        }
    }

    private void chaseExternal(long now, AggroWorld world, Watcher bot, BotState st, Target held, Config cfg) {
        String name = bot.name();
        if (up.forcedTarget(name) == null) {
            // Not forced any more: an ordinary fight again (or over); judged as one from the next step.
            log.debug("aggro: " + name + " no longer has an external forced target");
            st.phase = Phase.IDLE;
            if (st.home != null) {
                beginReturn(now, bot, st, "external target over");
            } else {
                finish(name);
            }
            return;
        }
        if (held != null) {
            st.entity = held.entity();
            st.target = held.name();
            Body target = world.bodyOf(held.entity());
            st.unseen = target != null && !sees(bot, target, cfg) ? st.unseen + 1 : 0;
        }
    }

    /** Why the target cannot be chased any more, or null when it can. */
    private String invalidTarget(Watcher bot, Body target) {
        if (target == null || !target.alive()) {
            return "target gone";
        }
        if (!Objects.equals(bot.dimension(), target.dimension())) {
            return "target in another level";
        }
        Settings s = settings;
        if (s != null && !s.attackInvincible() && !attackable(target)) {
            return "target no longer attackable";
        }
        return null;
    }

    // ---------------------------------------------------------------- PURSUE

    /** The target is lost: PvP BOT's target is cleared (also its revenge memory) and the bot walks to the last known position. */
    private void beginPursue(long now, Watcher bot, BotState st, String why) {
        String name = bot.name();
        up.clearTarget(name);
        giveUps.merge("lost", 1L, Long::sum);
        if (!up.steeringAvailable()) {
            warnOnce("steering", "aggro: inhabitants cannot walk to where they lost a player: " + up.steeringProblem());
            st.home = null;
            finish(name);
            return;
        }
        holdPatrol(name, st);
        st.phase = Phase.PURSUE;
        st.phaseStart = now;
        st.pursueGoal = st.lkp != null ? st.lkp : bot.position();
        st.predicted = false;
        resetWalk(st, now);
        st.hint = null;
        transition(now, name, "pursues " + st.target + "'s last position, " + fmt(bot.position().horizontalTo(st.pursueGoal))
                + " away (" + why + ")");
    }

    private void pursue(long now, AggroWorld world, Watcher bot, BotState st, Config cfg) {
        if (scanForChase(now, world, bot, st, cfg)) {
            return;
        }
        if (!Objects.equals(bot.dimension(), st.home == null ? bot.dimension() : st.home.dimension())) {
            abandon(bot, st, "bot changed level");
            return;
        }
        if (now - st.phaseStart >= cfg.returnMaxTicks()) {
            beginSearch(now, bot, st, "the walk took too long");
            return;
        }
        switch (walkTo(now, bot, st, st.pursueGoal, ARRIVE_LKP, cfg, true)) {
            case ARRIVED -> {
                if (!st.predicted) {
                    st.predicted = true;
                    Pos ahead = predictedGoal(st);
                    if (ahead != null) {
                        st.pursueGoal = ahead;
                        resetWalk(st, now);
                        log.debug("aggro: " + bot.name() + " continues along the last heading of " + st.target);
                        return;
                    }
                }
                beginSearch(now, bot, st, "arrived at the last known position");
            }
            case BLOCKED -> beginSearch(now, bot, st, "no way to the last known position");
            default -> {
            }
        }
    }

    /** A few blocks further along the last heading when the target was moving, else null. */
    private static Pos predictedGoal(BotState st) {
        double speed = Math.sqrt(st.velX * st.velX + st.velZ * st.velZ);
        if (!st.haveLast || speed < SIGNIFICANT_SPEED || st.lkp == null) {
            return null;
        }
        double len = Math.max(3.0, Math.min(8.0, speed * 20.0));
        return new Pos(st.lkp.x() + st.velX / speed * len, st.lkp.y(), st.lkp.z() + st.velZ / speed * len);
    }

    // ---------------------------------------------------------------- SEARCH

    private void beginSearch(long now, Watcher bot, BotState st, String why) {
        st.phase = Phase.SEARCH;
        st.searchStart = now;
        st.focus = st.lkp != null ? st.lkp : bot.position();
        st.visited.clear();
        st.points = 0;
        st.spot = null;
        st.hint = null;
        st.hintUsed = Long.MIN_VALUE;
        resetWalk(st, now);
        startSweep(now, bot, st);
        up.halt(bot.handle());
        transition(now, bot.name(), "searches for " + st.target + " (" + why + ")");
    }

    private void search(long now, AggroWorld world, Watcher bot, BotState st, Config cfg) {
        if (scanForChase(now, world, bot, st, cfg)) {
            return;
        }
        long elapsed = now - st.searchStart;
        if (elapsed >= cfg.searchTicks()) {
            up.halt(bot.handle());
            beginReturn(now, bot, st, "the search is over");
            return;
        }
        if (st.hint != null && st.hintTick > st.hintUsed) {
            // A sound from behind cover: the search moves there, within the same window.
            st.hintUsed = st.hintTick;
            if (st.focus == null || st.hint.horizontalTo(st.focus) > 3.0) {
                st.focus = st.hint;
                st.spot = null;
                st.sweeping = false;
                resetWalk(st, now);
                log.debug("aggro: " + bot.name() + " heard something and searches there");
            }
        }
        if (st.sweeping) {
            sweep(now, bot, st);
            return;
        }
        if (st.spot != null) {
            Walk w = walkTo(now, bot, st, st.spot, ARRIVE_SPOT, cfg, true);
            if (w != Walk.WALKING || now - st.spotStart > MAX_SPOT_TICKS) {
                if (w == Walk.ARRIVED) {
                    st.points++;
                }
                st.visited.add(st.spot);
                st.spot = null;
                startSweep(now, bot, st);
                up.halt(bot.handle());
            }
            return;
        }
        double speed = Math.sqrt(st.velX * st.velX + st.velZ * st.velZ);
        double hx = speed > SIGNIFICANT_SPEED ? st.velX / speed : 0.0;
        double hz = speed > SIGNIFICANT_SPEED ? st.velZ / speed : 0.0;
        List<SearchSpot> spots = world.searchSpots(bot, st.focus, SPOT_RADIUS);
        SearchSpot pick = SearchPlanner.choose(bot.position(), st.focus, hx, hz, spots, st.visited,
                cfg.searchTicks() - elapsed);
        if (pick == null) {
            // Nowhere new worth walking to: stand and look round again.
            st.visited.add(bot.position());
            startSweep(now, bot, st);
            return;
        }
        st.spot = pick.pos();
        st.spotStart = now;
        resetWalk(st, now);
    }

    private void startSweep(long now, Watcher bot, BotState st) {
        st.sweeping = true;
        st.sweepStep = 0;
        st.sweepStepStart = now;
        Pos me = bot.position();
        Pos toward = st.focus != null && me.horizontalTo(st.focus) > 1.5 ? st.focus : null;
        st.sweepBase = toward == null ? 0.0 : Math.atan2(-(toward.x() - me.x()), toward.z() - me.z());
    }

    /** Look round in quarter turns (a 120 degree view cone covers the circle in four), standing still. */
    private void sweep(long now, Watcher bot, BotState st) {
        if (now - st.sweepStepStart >= SWEEP_STEP_TICKS) {
            st.sweepStep++;
            st.sweepStepStart = now;
        }
        if (st.sweepStep >= SWEEP_STEPS) {
            st.sweeping = false;
            return;
        }
        double yaw = st.sweepBase + st.sweepStep * (Math.PI / 2.0);
        Pos me = bot.position();
        Pos at = new Pos(me.x() - Math.sin(yaw) * 8.0, me.y() + 1.62, me.z() + Math.cos(yaw) * 8.0);
        up.look(bot.handle(), at);
        up.halt(bot.handle());
    }

    // ---------------------------------------------------------------- RETURN

    /**
     * The hunt is over: walk back to the HOME anchor (never to a temporary point). Without a home, or when walking is
     * not possible, the bot stays where it is.
     */
    private void beginReturn(long now, Watcher bot, BotState st, String why) {
        String name = bot.name();
        Origin home = st.home;
        if (home == null || !Objects.equals(bot.dimension(), home.dimension())) {
            finish(name);
            return;
        }
        if (!up.steeringAvailable()) {
            warnOnce("steering", "aggro: inhabitants cannot walk back to their home anchor: " + up.steeringProblem());
            st.home = null;
            finish(name);
            return;
        }
        holdPatrol(name, st);
        st.phase = Phase.RETURN;
        st.phaseStart = now;
        resetWalk(st, now);
        transition(now, name, "returns home, " + fmt(bot.position().horizontalTo(home.pos())) + " away (" + why + ")");
    }

    private void ret(long now, AggroWorld world, Watcher bot, BotState st, Config cfg) {
        String name = bot.name();
        if (scanForChase(now, world, bot, st, cfg)) {
            return;
        }
        Origin home = st.home;
        if (home == null || !Objects.equals(bot.dimension(), home.dimension())) {
            abandon(bot, st, "bot changed level");
            return;
        }
        Pos here = bot.position();
        if (here.horizontalTo(home.pos()) <= cfg.returnArriveDistance() && Math.abs(here.y() - home.pos().y()) <= HOME_HEIGHT) {
            up.halt(bot.handle());
            st.home = null;
            finish(name);
            returned++;
            log.debug("aggro: " + name + " is back home (" + fmt(here.horizontalTo(home.pos())) + " blocks away, "
                    + (now - st.phaseStart) + " ticks)");
            return;
        }
        if (now - st.phaseStart >= cfg.returnMaxTicks()) {
            up.halt(bot.handle());
            abandoned.merge("took too long", 1L, Long::sum);
            logInfo(now, name, "aggro: " + name + " gave up walking home after " + (now - st.phaseStart) + " ticks ("
                    + fmt(here.horizontalTo(home.pos())) + " blocks left) and stays where it is");
            st.home = null;
            finish(name);
            return;
        }
        walkTo(now, bot, st, home.pos(), cfg.returnArriveDistance(), cfg, false);
    }

    private void abandon(Watcher bot, BotState st, String reason) {
        abandoned.merge(reason, 1L, Long::sum);
        log.debug("aggro: " + bot.name() + " stops walking home: " + reason);
        up.halt(bot.handle());
        st.home = null;
        finish(bot.name());
    }

    /**
     * Pauses PvP BOT's own patrol of this bot for the length of the hunt: its patrol movement is applied every tick and
     * would pull the bot toward its next patrol point against the pursuit, search and walk home.
     */
    private void holdPatrol(String name, BotState st) {
        if (!st.patrolPaused) {
            st.patrolPaused = true;
            up.pausePatrol(name);
        }
    }

    /** The hunt of this bot is over: forget it, and let its patrol walk again if it was paused. */
    private void finish(String name) {
        BotState st = states.remove(name);
        if (st != null && st.patrolPaused) {
            up.resumePatrol(name);
        }
    }

    // ---------------------------------------------------------------- walking

    private void resetWalk(BotState st, long now) {
        st.route = null;
        st.routeGoal = null;
        st.routePartial = false;
        st.nextPlanTick = now;
        st.stuckReplans = 0;
        st.straightBest = Double.MAX_VALUE;
        st.straightBestTick = now;
    }

    /**
     * One tick of walking toward {@code goal}: plan a route when there is none (bounded: at most one plan per
     * {@value #PLAN_INTERVAL_TICKS} ticks per bot and {@value #MAX_PLANS_PER_TICK} per tick overall), follow it waypoint by
     * waypoint with PvP BOT's own look and move input, and replan when stuck. Without a route (no planner, or nothing
     * found yet) the bot steers straight at the goal so it is never idle.
     *
     * @param giveUp true: report {@link Walk#BLOCKED} when there is no way or no progress; false: keep trying (the
     *               caller has its own time limit)
     */
    private Walk walkTo(long now, Watcher bot, BotState st, Pos goal, double arrive, Config cfg, boolean giveUp) {
        Pos here = bot.position();
        double d = here.horizontalTo(goal);
        if (d <= arrive) {
            up.halt(bot.handle());
            return Walk.ARRIVED;
        }
        if (st.route != null && st.routeGoal != null && st.routeGoal.distanceTo(goal) > 1.0) {
            st.route = null;
            st.nextPlanTick = now;
        }
        if (st.route == null && now >= st.nextPlanTick && plansThisTick < MAX_PLANS_PER_TICK) {
            plansThisTick++;
            PathPlanner.Plan plan = planner.plan(bot.handle(), goal, modMax);
            if (plan.hasRoute()) {
                st.route = new PathFollower(plan.waypoints(), now);
                st.routeGoal = goal;
                st.routePartial = !plan.reachesGoal();
                st.nextPlanTick = now + PLAN_INTERVAL_TICKS;
            } else {
                st.nextPlanTick = now + PLAN_RETRY_TICKS;
                if (plan.outcome() == PathPlanner.Outcome.NONE && giveUp) {
                    return Walk.BLOCKED;
                }
            }
        }
        if (st.route != null) {
            Pos wp = st.route.current(here, now);
            if (wp == null) {
                boolean partial = st.routePartial;
                st.route = null;
                st.nextPlanTick = now + (partial ? PLAN_RETRY_TICKS : 0);
                if (partial && giveUp) {
                    return Walk.BLOCKED;
                }
            } else if (st.route.stuck(here, now, cfg.stuckTicks())) {
                st.route = null;
                st.stuckReplans++;
                st.nextPlanTick = now;
                if (st.stuckReplans > MAX_STUCK_REPLANS) {
                    if (giveUp) {
                        return Walk.BLOCKED;
                    }
                    st.stuckReplans = 0;
                    st.nextPlanTick = now + PLAN_RETRY_TICKS;
                }
            } else {
                up.steer(bot.handle(), wp, WALK_SPEED);
                return Walk.WALKING;
            }
        }
        // No route yet or right now: head straight for the goal, and notice when that gets nowhere.
        if (d < st.straightBest - 0.5) {
            st.straightBest = d;
            st.straightBestTick = now;
        } else if (giveUp && st.route == null && now - st.straightBestTick >= cfg.stuckTicks() * 2L && now >= st.nextPlanTick) {
            return Walk.BLOCKED;
        }
        up.steer(bot.handle(), goal, WALK_SPEED);
        return Walk.WALKING;
    }

    // ---------------------------------------------------------------- drop

    /** The bot died or left: forget its hunt, and release a force we set. */
    private void dropBot(String name, String reason) {
        BotState st = states.remove(name);
        exposure.forgetObserver(name);
        watching.remove(name);
        if (st == null) {
            return;
        }
        if (st.patrolPaused) {
            try {
                up.resumePatrol(name);
            } catch (RuntimeException e) {
                noteFailure("resuming the patrol of " + name, e);
            }
        }
        if (st.phase == Phase.CHASE && st.forcedByUs != null && st.kind == Kind.PLAYER) {
            releaseOurForce(name, st);
        }
        log.debug("aggro: " + name + " dropped its hunt (" + st.phase.name().toLowerCase(Locale.ROOT) + "): " + reason);
    }

    private void dropAll(String reason) {
        for (String name : new ArrayList<>(states.keySet())) {
            dropBot(name, reason);
        }
        watching.clear();
    }

    private void releaseOurForce(String name, BotState st) {
        try {
            String forced = up.forcedTarget(name);
            if (forced != null && forced.equalsIgnoreCase(st.forcedByUs)) {
                up.clearTarget(name);
            }
        } catch (RuntimeException ex) {
            noteFailure("releasing the forced target of " + name, ex);
        }
    }

    // ================================================================ modes

    private Settings readSettings() {
        try {
            return up.settings();
        } catch (RuntimeException e) {
            warnOnce("settings|" + e.getMessage(), "aggro: PvP BOT's settings could not be read: " + e.getMessage());
            return null;
        }
    }

    private Mode modeFor(Settings s) {
        if (s == null) {
            return Mode.SETTINGS_UNREADABLE;
        }
        if (!s.combatEnabled()) {
            return Mode.INERT_COMBAT_OFF;
        }
        if (s.autoTarget()) {
            return Mode.INERT_AUTO_TARGET;
        }
        return Mode.ACTIVE;
    }

    private void setMode(Mode next, String detail) {
        if (next == mode) {
            return;
        }
        Mode previous = mode;
        mode = next;
        String text = "aggro: " + next.text() + (detail == null ? "" : " (" + detail + ")");
        switch (next) {
            case ACTIVE -> {
                Config cfg = config.get();
                log.info(previous == Mode.UNKNOWN
                        ? "aggro: active, inhabitants chase players they see or hear (line of sight, up to " + fmt(modMax)
                        + " blocks, after a " + fmt(cfg.perception().reactionTicks() / 20.0) + " s reaction), search "
                        + fmt(cfg.searchTicks() / 20.0) + " s where they lost them, then walk back to where they started"
                        : text);
            }
            case INERT_AUTO_TARGET -> log.info("aggro: inert, PvP BOT's own auto-target is on and already acquires "
                    + "targets by itself, so nothing is noticed here (the lost-target search and the walk home still "
                    + "apply; turn auto-target off to use line of sight)");
            case OFF -> log.debug(text);
            case UNKNOWN -> {
            }
            default -> log.warn(text);
        }
    }

    // ================================================================ logging

    /** A transition, always at debug. */
    private void transition(long now, String bot, String what) {
        log.debug("aggro: " + bot + " " + what);
    }

    /** One INFO per bot per {@link #INFO_GAP_TICKS}; the rest go to debug. */
    private void logInfo(long now, String bot, String text) {
        Long last = lastInfo.get(bot);
        if (last == null || now < last || now - last >= INFO_GAP_TICKS) {
            lastInfo.put(bot, now);
            log.info(text);
        } else {
            log.debug(text);
        }
    }

    // ================================================================ failures

    private void noteFailure(String what, RuntimeException e) {
        failures++;
        warnOnce(what + "|" + e.getMessage(), "aggro: " + what + " failed: " + e.getMessage());
        if (failures >= MAX_CONSECUTIVE_FAILURES && !failed) {
            failed = true;
            states.clear();
            setMode(Mode.FAILED, null);
        }
    }

    private void warnOnce(String key, String message) {
        if (warned.size() < 64 && warned.add(key)) {
            log.warn(message);
        }
    }

    // ================================================================ diagnostics

    public Mode mode() {
        return mode;
    }

    public Stats stats() {
        int chasing = 0;
        int pursuing = 0;
        int searching = 0;
        int returning = 0;
        for (BotState s : states.values()) {
            switch (s.phase) {
                case CHASE, REACT -> chasing++;
                case PURSUE -> pursuing++;
                case SEARCH -> searching++;
                case RETURN -> returning++;
                default -> {
                }
            }
        }
        return new Stats(mode, chasing, pursuing, searching, returning, acquisitions, hitEngagements, returned,
                Map.copyOf(giveUps), Map.copyOf(abandoned));
    }

    /** Where {@code botName} is in the hunt ({@link Phase#IDLE} when it is not hunting). */
    public Phase phaseOf(String botName) {
        BotState s = states.get(botName);
        return s == null ? Phase.IDLE : s.phase;
    }

    /** The name of the player or mob {@code botName} is hunting, or null. */
    public String engagedWith(String botName) {
        BotState s = states.get(botName);
        return s == null || s.phase == Phase.IDLE ? null : s.target;
    }

    /** The cause of the current hunt of {@code botName}, or null. */
    public Cause causeOf(String botName) {
        BotState s = states.get(botName);
        return s == null || s.phase == Phase.IDLE ? null : s.cause;
    }

    /** True while {@code botName} has a home anchor (a hunt is running or a walk home is pending). */
    public boolean hasHome(String botName) {
        BotState s = states.get(botName);
        return s != null && s.home != null;
    }

    /** The home anchor of {@code botName}, or null. */
    public Pos homeOf(String botName) {
        BotState s = states.get(botName);
        return s == null || s.home == null ? null : s.home.pos();
    }

    /** How many search points {@code botName} has checked in the current search. */
    public int pointsChecked(String botName) {
        BotState s = states.get(botName);
        return s == null ? 0 : s.points;
    }

    /**
     * One short text about one bot for the status output, e.g. {@code chasing Jack (seen 0.4 s ago; noticed by sight after
     * 0.4 s)}, {@code pursuing Jack's last position, 6.2 to go}, {@code searching for Jack, 7.5 s left, 3 points checked},
     * {@code returning home, 18.0 to go} or {@code idle}. Distances and times are as of the last tick.
     */
    public String describe(String botName) {
        BotState s = states.get(botName);
        if (s != null && s.phase != Phase.IDLE) {
            Config cfg = config.get();
            String how = s.noticed != null ? "; noticed by " + senseText(s.noticed) + " after " + fmt(s.reaction / 20.0) + " s"
                    : s.cause == Cause.HIT ? "; hit, reacted after " + fmt(s.reaction / 20.0) + " s" : "";
            switch (s.phase) {
                case REACT:
                    return "turning to " + s.target + "'s hit (reacting)";
                case CHASE:
                    if (s.kind == Kind.EXTERNAL) {
                        return "fighting " + s.target + " (forced by someone else; not managed)";
                    }
                    return (s.kind == Kind.MOB ? "fighting " : "chasing ") + s.target + " (seen "
                            + fmt(s.unseen / 20.0) + " s ago" + (s.kind == Kind.MOB ? "" : how) + ")";
                case PURSUE:
                    return "pursuing " + s.target + "'s last position, "
                            + fmt(s.pursueGoal == null || s.pos == null ? 0.0 : s.pursueGoal.horizontalTo(s.pos)) + " to go";
                case SEARCH:
                    return "searching for " + s.target + ", "
                            + fmt(Math.max(0L, cfg.searchTicks() - (lastTick - s.searchStart)) / 20.0) + " s left, "
                            + s.points + " points checked";
                case RETURN:
                    return "returning home, " + fmt(s.home == null || s.pos == null ? 0.0
                            : s.home.pos().horizontalTo(s.pos)) + " to go";
                default:
                    break;
            }
        }
        return switch (mode) {
            case ACTIVE -> "idle";
            case OFF -> "off";
            case INERT_AUTO_TARGET -> "idle (PvP BOT auto-target on)";
            case INERT_COMBAT_OFF -> "idle (PvP BOT combat off)";
            case SETTINGS_UNREADABLE -> "idle (settings unreadable)";
            case UPSTREAM_UNAVAILABLE -> "unavailable";
            case FAILED -> "failed";
            case UNKNOWN -> "not started";
        };
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}

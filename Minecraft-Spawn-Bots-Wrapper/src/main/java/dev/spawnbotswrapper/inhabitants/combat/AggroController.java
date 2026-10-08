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
 * what they can SEE and HEAR. The one hard-coded distance rule is the ENGAGE LIMIT ({@link #ENGAGE_LIMIT}, 16 blocks):
 * sight has no block limit in the view cone, but a bot never engages a target it sees (and measures) farther away.
 * <pre>
 *   IDLE --(noticed)--&gt; CHASE --(lost)--&gt; PURSUE --(arrived or blocked)--&gt; SEARCH --(10 s)--&gt; RETURN --(home)--&gt; IDLE
 *   PURSUE / SEARCH / RETURN --(noticed again)--&gt; CHASE          IDLE / PURSUE / SEARCH / RETURN --(hit)--&gt; CHASE (confirming) or PURSUE
 * </pre>
 * <b>No magic.</b> The bot acts only on what it perceives: sight (the current position while the target is visible), the last
 * known position and last seen velocity, hearing (vanilla vibrations: the position of a sound), and the direction a blow came
 * from. It never reads the true position, distance, health or life of a target it cannot see: "too far" is decided only from
 * a SIGHTING measured beyond the engage limit; a target that logs out, changes level or dies OUT OF SIGHT is simply lost
 * (last known position, search, walk home); only a death or disappearance the bot SEES ends the engagement at once.
 * <p>
 * <b>Noticing</b> is {@link Perception}: a player that stays in view for the reaction time (a continuous formula in seconds:
 * 0.5 s up close, 2.0 s at 32 blocks, longer at an angle, sneaking or hard to see; nothing is seen behind) is noticed. Sounds
 * come from vanilla's own vibration system (radius, sneaking and wool are vanilla rules): a sound whose source is in clear
 * view counts as sight without the view cone (the bot turned to it); a sound out of sight is only a place to investigate
 * (idle: turn and look; pursue/search: the search focus moves there). A bot engages only once it SEES the target.
 * <p>
 * <b>CHASE.</b> The engagement has a CONFIRMED flag: true only after the target has been continuously visible for the full
 * reaction time. Only while confirmed does PvP BOT hold the target (so it can neither shoot nor strike earlier; a melee
 * hit or crossbow shot outside a confirmed engagement is vetoed, see {@link #mayAttackPlayer}). The FIRST unseen tick clears
 * PvP BOT's target and the bot steers to the last known position; EVERY re-sighting restarts the exposure from zero (no
 * instant resume). Unseen for {@code loseGraceTicks} the target is LOST. Seen beyond the engage limit: too far, the bot gives
 * up and goes home.
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
 * <b>Being hit.</b> A melee hit: the striker is adjacent; the bot turns to the pain and, when it SEES the attacker (occlusion
 * only), confirms the engagement after the reaction delay (PvP BOT's instant revenge is cleared, and its counter-hit vetoed
 * at damage level, until then). A projectile from a shooter the bot does not see: it knows only the DIRECTION the projectile
 * came from; it turns to look along it (sight rules and reaction time apply) and, if it sees nobody, PURSUES the point found by
 * tracing back along the incoming line to the first blocking block (or the engage limit), then searches.
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

    /**
     * The ENGAGE LIMIT, the only hard-coded distance rule: a target the bot sees and measures farther than this many blocks
     * is never engaged (not acquired, not chased, no pursuit or search for it). Sight itself has no such limit. Not a
     * tunable.
     */
    public static final double ENGAGE_LIMIT = Perception.ENGAGE_LIMIT;

    /** The longest route worth planning (blocks): a route-planning budget, not an engagement rule. */
    static final double PLAN_RANGE = 128.0;
    /** Ticks a heard sound keeps the bot's attention on the source: while the source stays in clear view it is noticed without the view cone. */
    static final int ATTENTION_TICKS = 30;
    /** A visible player within this many blocks of a heard sound is where the sound came from. */
    static final double HEARD_MATCH = 4.0;
    /** Ticks an idle bot keeps looking toward a sound it could not place. */
    static final int LOOK_TICKS = 25;
    /** How far along the direction of a melee blow the bot looks for its (unseen) striker: within reach, plus slack. */
    static final double MELEE_TRACE = 3.0;
    /** Horizontal distance (blocks) at which a bot re-acquiring its target stops walking and just looks. */
    static final double CONFIRM_STOP = 1.5;

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
        /** Engaged: confirmed (PvP BOT holds the target) or confirming (the reaction time has not been served yet). */
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

        // ---- CONFIRMING (CHASE)
        /** True only after the target was continuously visible for the full reaction time; PvP BOT holds the target only then. */
        boolean confirmed;
        long confirmedAt;
        /** The tick the current unbroken sighting run began (exposure = now - runStart, in ticks). */
        long runStart;
        /** The reaction is to a hit (pain tells where the attacker is: its sneaking does not slow the reaction). */
        boolean painLed;
        /** The required reaction seconds as of the last tick (status only). */
        double required;

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

    /**
     * What a bot heard: it turned to {@code pos}. With a {@code candidate} (a visible player near the sound) the player is
     * sighted without the view cone until {@code until}; without one it is a place to look at (idle) or to search (hint).
     */
    private record Attention(String candidate, Pos pos, long until) {
    }

    private final Supplier<Config> config;
    private final TargetControl up;
    private final PathPlanner planner;
    private final Log log;

    private final Map<String, BotState> states = new LinkedHashMap<>();
    /** Bots that have something in view: scanned every tick until this tick. */
    private final Map<String, Long> watching = new HashMap<>();
    /** What each idle or searching bot pays attention to after a sound; see {@link Attention}. */
    private final Map<String, Attention> attention = new HashMap<>();
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
            clearStatesResumingPatrols();
            return;
        }
        if (lastSettingsRead == Long.MIN_VALUE || now - lastSettingsRead >= cfg.scanIntervalTicks()) {
            lastSettingsRead = now;
            settings = readSettings();
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
            attention.keySet().removeIf(n -> !seen.contains(n));
        }
    }

    /** How many (observer, candidate) exposure runs are being tracked (for tests and diagnostics). */
    int exposureRuns() {
        return exposure.size();
    }

    /** Forget everything: the server stopped or restarted. Does not touch PvP BOT. */
    public void reset() {
        states.clear();
        watching.clear();
        attention.clear();
        exposure.clear();
        lastInfo.clear();
        lastTick = Long.MIN_VALUE;
        lastSettingsRead = Long.MIN_VALUE;
        settings = null;
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
        // Drained every tick, so each hit and sound is reported once and the poller's baseline stays current.
        AggroWorld.Hit hit = bot.newHit();
        List<AggroWorld.Sound> sounds = bot.drainSounds();
        if (st != null) {
            st.pos = bot.position();
        }
        if (st != null && st.phase == Phase.CHASE) {
            chase(now, world, bot, st, cfg);
            return;
        }
        // IDLE, PURSUE, SEARCH or RETURN. PvP BOT's own target (revenge after a hit, a mob fight, somebody's force) first.
        boolean revenge = false;
        if (mayStart()) {
            Target held = up.currentTarget(name);
            if (held != null) {
                Body heldBody = world.bodyOf(held.entity());
                revenge = held.revenge() && heldBody != null && heldBody.isPlayer() && up.forcedTarget(name) == null;
                st = startFromHeld(now, world, bot, st, held, hit, cfg);
                if (st != null && (st.phase == Phase.CHASE || st.phase == Phase.PURSUE)) {
                    return;
                }
                // A hit that startFromHeld consumed (turned into a pursuit or a confirmation) must not be handled twice.
                hit = null;
            }
            if (hit != null && active()) {
                st = onHit(now, world, bot, st, hit, revenge, cfg);
                if (st != null && (st.phase == Phase.CHASE || st.phase == Phase.PURSUE)) {
                    return;
                }
            }
        }
        if (active() && cfg.perception().enabled() && !sounds.isEmpty()) {
            hear(now, world, bot, st, sounds, cfg);
        }
        if (st == null) {
            if (active()) {
                lookAtSound(now, bot);
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
                                   AggroWorld.Hit hit, Config cfg) {
        String name = bot.name();
        String forced = up.forcedTarget(name);
        Body body = world.bodyOf(t.entity());
        boolean player = body != null && body.isPlayer();
        boolean revengeForce = forced != null && t.revenge() && forced.equalsIgnoreCase(t.name());
        if (forced != null && !revengeForce) {
            // A forced name that appears while nothing of ours is chasing is somebody else's (a command, another mod).
            // (One that names the revenge target, PvP BOT's own wind-burst flow, is not: it is handled as the revenge.)
            BotState st = existing != null ? existing : state(name);
            beginHeld(now, bot, st, t, Kind.EXTERNAL, Cause.OTHER);
            log.debug("aggro: " + name + " fights " + t.name() + " on a forced target set by someone else; tracked only");
            return st;
        }
        if (player && active() && t.revenge()) {
            // PvP BOT's revenge names the attacker whether or not the bot could know who struck it, so it is no information:
            // it is cleared at once (its instant counter-attack is held back), and the bot goes on what it felt (the hit).
            up.clearTarget(name);
            if (hit != null) {
                return onHit(now, world, bot, existing, hit, true, cfg);
            }
            log.debug("aggro: " + name + " dropped PvP BOT's revenge target (no hit was felt)");
            return existing;
        }
        BotState st = existing != null ? existing : state(name);
        beginHeld(now, bot, st, t, player ? Kind.PLAYER : Kind.MOB, t.revenge() ? Cause.HIT : Cause.OTHER);
        hitEngagements++;
        logInfo(now, name, "aggro: " + name + " fights " + t.name() + " (" + st.cause.name().toLowerCase(Locale.ROOT)
                + (st.kind == Kind.MOB ? ", a mob" : "") + ")");
        return st;
    }

    /** Begins tracking a fight PvP BOT started itself (or somebody forced): PvP BOT's own decision, nothing to confirm. */
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
        st.confirmed = true;
        st.confirmedAt = now;
        st.runStart = now;
        st.painLed = false;
        if (kind != Kind.EXTERNAL && st.home == null) {
            st.home = here(bot);
        }
        st.phase = Phase.CHASE;
        exposure.forgetObserver(bot.name());
    }

    /**
     * True when {@code b} may be treated as an attacker to hunt: the same filters as any other candidate ({@link #validTarget}:
     * alive, a player, in this level, not exempt unless {@code attackInvincible}, no faction ally, other bots only with
     * {@code targetOtherBots}, other players only with {@code targetPlayers}). A hit does not widen who may be hunted.
     */
    private boolean validAttacker(Watcher bot, Body b) {
        Settings s = settings;
        return s != null && validTarget(bot, b, s);
    }

    private boolean attackableNow(Body b) {
        Settings s = settings;
        return s != null && (s.attackInvincible() || attackable(b));
    }

    /**
     * A hit. NO MAGIC: the bot felt where the blow came from, not who struck it.
     * <ul>
     *   <li>A melee hit by a player it SEES (occlusion only: it turns to the pain, even when the striker is behind it):
     *       PvP BOT's instant revenge is cleared now, and the engagement becomes CONFIRMED only after the reaction delay
     *       (the same continuous formula, angle factor 1; sneaking does not slow a reaction to pain).</li>
     *   <li>A projectile, or a striker it cannot see: the bot turns to look along the incoming direction (the sight rules and
     *       the reaction time apply to whoever it then sees) and PURSUES the point found by tracing back along that line to
     *       the first blocking block (or the engage limit). It never learns the shooter's position or identity.</li>
     * </ul>
     *
     * @param revenge PvP BOT held a revenge target for this hit (its attacker may be hunted even when the ordinary target
     *                filters would skip it)
     */
    private BotState onHit(long now, AggroWorld world, Watcher bot, BotState existing, AggroWorld.Hit hit,
                           boolean revenge, Config cfg) {
        String name = bot.name();
        Body attacker = hit.attacker();
        if (attacker != null && (!attacker.isPlayer()
                || !(validAttacker(bot, attacker) || (revenge && attacker.alive() && attackableNow(attacker))))) {
            // A mob (PvP BOT's own revenge handles those) or somebody the bot does not hunt.
            return existing;
        }
        BotState st = existing != null ? existing : state(name);
        if (st.home == null) {
            st.home = here(bot);
        }
        // PvP BOT re-arms its revenge on every hit; its instant counter-attack is held back until the engagement is confirmed.
        up.clearTarget(name);
        st.kind = Kind.PLAYER;
        st.cause = Cause.HIT;
        st.noticed = null;
        st.forcedByUs = null;
        st.reaction = 0;
        st.startTick = now;
        st.unseen = 0;
        st.haveLast = false;
        st.velX = 0.0;
        st.velZ = 0.0;
        st.lastSeenTick = now;
        st.route = null;
        st.confirmed = false;
        st.painLed = true;
        st.runStart = now;
        exposure.forgetObserver(name);
        hitEngagements++;
        if (attacker != null && sees(bot, attacker, cfg)) {
            st.target = attacker.name();
            st.firstName = attacker.name();
            st.entity = attacker.handle();
            st.lkp = attacker.position();
            st.phase = Phase.CHASE;
            st.required = requiredFor(bot, attacker, st, cfg);
            up.look(bot.handle(), attacker.position());
            logInfo(now, name, "aggro: " + name + " was hit by " + attacker.name() + " and turns to it (confirms in about "
                    + fmt(st.required) + " s)");
            return st;
        }
        Pos from = hit.from();
        // Only the direction of the blow is known: a melee blow came from within reach, anything else from as far as the line runs.
        Pos spot = bot.traceBack(from, hit.towardX(), hit.towardY(), hit.towardZ(), attacker != null ? MELEE_TRACE : ENGAGE_LIMIT);
        up.look(bot.handle(), new Pos(from.x() + hit.towardX() * 8.0, from.y() + hit.towardY() * 8.0,
                from.z() + hit.towardZ() * 8.0));
        st.target = null;
        st.firstName = null;
        st.entity = null;
        st.lkp = spot;
        watching.put(name, now + ATTENTION_TICKS);
        logInfo(now, name, "aggro: " + name + " was hit from out of sight, looks toward the shot and goes to where the "
                + "line of the shot ends");
        beginPursue(now, bot, st, "hit from cover");
        return st;
    }

    /** {@code target} is confirmed: PvP BOT is handed it as the forced target (and only now, so it cannot strike or shoot earlier). */
    private void confirm(long now, Watcher bot, BotState st, Body target, long exposureTicks) {
        up.setTarget(bot.name(), target.name());
        st.confirmed = true;
        st.confirmedAt = now;
        st.forcedByUs = target.name();
        st.reaction = exposureTicks;
    }

    /**
     * Starts CHASE on a player noticed by sight or hearing (the exposure has already lasted the reaction time, so the
     * engagement is CONFIRMED at once and PvP BOT is handed the target). The first hunt of a bot without a home makes the
     * bot's position its home.
     */
    private void startChase(long now, Watcher bot, BotState existing, Body target, Perception.Sense how, long reaction) {
        String name = bot.name();
        BotState st = existing != null ? existing : state(name);
        if (st.home == null) {
            st.home = here(bot);
        }
        st.phase = Phase.CHASE;
        st.kind = Kind.PLAYER;
        st.cause = Cause.ACQUIRED;
        st.target = target.name();
        st.firstName = target.name();
        st.entity = target.handle();
        st.noticed = how;
        st.startTick = now;
        st.runStart = now;
        st.painLed = false;
        st.unseen = 0;
        st.lkp = target.position();
        st.lastSeenTick = now;
        st.haveLast = false;
        st.velX = 0.0;
        st.velZ = 0.0;
        st.route = null;
        exposure.forgetObserver(name);
        confirm(now, bot, st, target, reaction);
        acquisitions++;
        logInfo(now, name, "aggro: " + name + " noticed " + target.name() + " by " + senseText(how) + " after "
                + fmt(Perception.exposureSeconds(reaction)) + " s and chases " + target.name() + " ("
                + fmt(bot.distanceTo(target)) + " blocks away)");
    }

    // ---------------------------------------------------------------- noticing

    /**
     * Looks for a player this bot notices now (IDLE, PURSUE, SEARCH, RETURN): every scan interval (staggered per bot), and
     * every tick while something is in view or a sound holds the bot's attention. Only players within the engage limit are
     * candidates (farther ones are never engaged, so no ray is cast for them). Starts a CHASE when the exposure has lasted
     * the reaction time.
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
        List<Body> ordered = validNearby(world, bot, s);
        if (ordered.isEmpty()) {
            exposure.forgetObserver(name);
            return false;
        }
        // A player that disconnected, changed level, went out of range or is no valid target any more has no exposure run:
        // only the candidates that are valid this scan keep one.
        Set<String> present = new HashSet<>();
        for (Body candidate : ordered) {
            present.add(candidate.name());
        }
        exposure.retainSubjects(name, present);
        Attention att = attention.get(name);
        if (att != null && att.until() < now) {
            attention.remove(name);
            att = null;
        }
        boolean anyExposure = false;
        for (Body candidate : ordered) {
            boolean heardNear = att != null && att.candidate() != null && att.candidate().equalsIgnoreCase(candidate.name());
            Perception.Reading reading = read(bot, candidate, cfg, heardNear);
            String key = ExposureTracker.key(name, candidate.name());
            if (reading.exposed()) {
                anyExposure = true;
                long ticks = exposure.sighted(key, now);
                if (Perception.noticed(Perception.exposureSeconds(ticks), reading.requiredSeconds())) {
                    startChase(now, bot, st, candidate, heardNear ? Perception.Sense.HEARING : reading.sense(), ticks);
                    watching.remove(name);
                    attention.remove(name);
                    return true;
                }
            } else {
                exposure.missed(key, now);
            }
        }
        if (anyExposure) {
            // Watched every tick from now on until a few ticks after the last exposure: an exposure run is only alive
            // for a couple of missed ticks (ExposureTracker), so a run must not be left to the sparse idle cadence.
            watching.merge(name, now + WATCH_TICKS, Math::max);
        }
        return false;
    }

    /**
     * The players within the engage limit of {@code bot} that are valid targets, nearest first. Cheap validity first (most of
     * a crowded server's players are other inhabitants, which are no targets).
     */
    private List<Body> validNearby(AggroWorld world, Watcher bot, Settings s) {
        List<? extends Body> near = world.playersWithin(bot, ENGAGE_LIMIT);
        if (near.isEmpty()) {
            return List.of();
        }
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
        return ordered;
    }

    private static int stagger(String name, int interval) {
        return (name.hashCode() & 0x7fffffff) % Math.max(1, interval);
    }

    // ---------------------------------------------------------------- hearing

    /**
     * Vanilla vibrations reached this bot (what a sculk sensor or a Warden hears; radius, sneaking and wool are vanilla's
     * rules). NO MAGIC: only the position of a sound is known. A valid player in CLEAR view near the sound is where it came
     * from: the bot turns to it and, while the player stays in view, sights it without the view cone (angle factor 1; the
     * reaction time still applies). A sound whose source is not in view is only a place to investigate: an idle bot turns to
     * look, a pursuing or searching bot moves its search focus there. A bot engages only once it SEES the target.
     */
    private void hear(long now, AggroWorld world, Watcher bot, BotState st, List<AggroWorld.Sound> sounds, Config cfg) {
        String name = bot.name();
        Settings s = settings;
        if (s == null || !mayStart()) {
            return;
        }
        List<Body> candidates = null;
        for (AggroWorld.Sound sound : sounds) {
            if (candidates == null) {
                candidates = validNearby(world, bot, s);
            }
            Body match = null;
            double best = HEARD_MATCH;
            for (Body c : candidates) {
                double d = c.position().distanceTo(sound.pos());
                if (d <= best && sees(bot, c, cfg)) {
                    match = c;
                    best = d;
                }
            }
            if (match != null) {
                attention.put(name, new Attention(match.name(), sound.pos(), now + ATTENTION_TICKS));
                watching.merge(name, now + ATTENTION_TICKS, Math::max);
                if (up.steeringAvailable()) {
                    up.look(bot.handle(), match.position());
                }
            } else if (st != null) {
                st.hint = sound.pos();
                st.hintTick = now;
            } else {
                // Never replaces a live (unexpired, ATTENTION_TICKS) attention that carries a matched candidate.
                Attention current = attention.get(name);
                if (current == null || current.until() < now || current.candidate() == null) {
                    attention.put(name, new Attention(null, sound.pos(), now + LOOK_TICKS));
                }
            }
        }
    }

    /** An idle bot keeps its head turned toward a sound it could not place. */
    private void lookAtSound(long now, Watcher bot) {
        Attention a = attention.get(bot.name());
        if (a == null) {
            return;
        }
        if (a.until() < now) {
            attention.remove(bot.name());
        } else if (a.candidate() == null && up.steeringAvailable()) {
            up.look(bot.handle(), a.pos());
        }
    }

    /**
     * What {@code bot} makes of {@code candidate} this tick: {@link Perception#read} over the two bodies' eyes, look
     * direction and stance, with occlusion (a clear view, when {@code requireLineOfSight} is on) asked last. Without
     * perception (switched off, or a view that cannot tell the eyes and the stance) it is plain line of sight:
     * unobstructed, at once.
     *
     * @param heardNear a sound was heard at the candidate (angle factor 1: the bot turned to it)
     */
    Perception.Reading read(Watcher bot, Body candidate, Config cfg, boolean heardNear) {
        Perception.Params p = cfg.perception();
        // Perception off is exactly vanilla hasLineOfSight (one eye-to-eye ray); on, the eye ray and then a body-centre ray.
        BooleanSupplier clear = () -> !cfg.requireLineOfSight()
                || (p.enabled() ? bot.canSee(candidate) : bot.plainLineOfSight(candidate));
        AggroWorld.Senses me = p.enabled() ? bot.senses() : null;
        AggroWorld.Senses them = me == null ? null : candidate.senses();
        if (me == null || them == null) {
            return Perception.read(p.disabled(), 0.0, bot.distanceTo(candidate), Perception.Subject.player(false), false, clear);
        }
        // The perception distance is EYE TO EYE (as on the companion side and in docs/perception/vectors.json); the engage
        // limit stays the distance between the two bodies (playersWithin, chasePlayer).
        double distance = me.eye().distanceTo(them.eye());
        double theta = Perception.angleDeg(me.look().x(), me.look().y(), me.look().z(),
                them.eye().x() - me.eye().x(), them.eye().y() - me.eye().y(), them.eye().z() - me.eye().z());
        return Perception.read(p, theta, distance, them.subject(), heardNear, clear);
    }

    /**
     * True when the bot has an unobstructed view of {@code b} (no cone: an engaged bot faces it). No distance limit: sight has
     * none (the driver caps the rays at vanilla's line-of-sight range); what a sighting at a great distance MEANS is the
     * caller's business ({@link #ENGAGE_LIMIT}).
     */
    private boolean sees(Watcher bot, Body b, Config cfg) {
        return Objects.equals(bot.dimension(), b.dimension()) && (!cfg.requireLineOfSight() || bot.canSee(b));
    }

    /**
     * The reaction time (seconds) an engaged bot needs to CONFIRM {@code target} after a continuous sighting: the continuous
     * formula at the current distance with angle factor 1 (an engaged bot faces its target), the target's sneaking (unless
     * the reaction is to a hit) and visibility; 0 with perception off.
     */
    private double requiredFor(Watcher bot, Body target, BotState st, Config cfg) {
        Perception.Params p = cfg.perception();
        if (!p.enabled()) {
            return 0.0;
        }
        AggroWorld.Senses them = target.senses();
        Perception.Subject subject = them == null ? Perception.Subject.player(false) : them.subject();
        AggroWorld.Senses me = bot.senses();
        double distance = me != null && them != null ? me.eye().distanceTo(them.eye()) : bot.distanceTo(target);
        return Perception.requiredSeconds(p, 0.0, distance, subject, !st.painLed);
    }

    /** Who the hunt is about: the target, or "whoever shot" for a hit from out of sight (the bot never learned who). */
    private static String who(BotState st) {
        return st.target == null ? "whoever shot" : st.target;
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

    /**
     * One tick of an engagement with a player. Everything here is a function of what the bot SEES this tick (occlusion clear)
     * and what it saw before:
     * <ul>
     *   <li>seen beyond the engage limit: too far, the bot gives up and goes home (never engaged);</li>
     *   <li>seen: the last known position and velocity are updated; a re-sighting starts the exposure from zero; the
     *       engagement is CONFIRMED (PvP BOT is handed the target) only once the exposure reaches the reaction time;</li>
     *   <li>the FIRST unseen tick: not confirmed any more, PvP BOT's target cleared; the bot steers to the last known
     *       position; unseen for {@code loseGraceTicks}: PURSUE;</li>
     *   <li>a target that dies, logs out or changes level is judged like sight: only when the bot SAW it (in view a tick ago)
     *       does the engagement end at once; out of sight it is just lost.</li>
     * </ul>
     */
    private void chasePlayer(long now, AggroWorld world, Watcher bot, BotState st, Target held, Config cfg) {
        String name = bot.name();
        if (st.confirmed && held != null) {
            // PvP BOT may retarget by itself (its wind-burst and elytra flow name the attacker); follow it.
            st.entity = held.entity();
            st.target = held.name();
        }
        Body target = world.bodyOf(st.entity);
        String bad = invalidTarget(bot, target);
        if (bad != null && st.unseen == 0 && (target == null
                || !Objects.equals(bot.dimension(), target.dimension()) || sees(bot, target, cfg))) {
            // It was in view a tick ago AND is now (a body that still exists is judged by this tick's view, so one that dies
            // the tick it steps behind cover is lost sight, not a seen death): the bot saw it die, leave or vanish.
            giveUps.merge(bad, 1L, Long::sum);
            up.clearTarget(name);
            beginReturn(now, bot, st, bad);
            return;
        }
        // (A target that went out of sight and THEN died or left cannot be observed: it is treated as lost sight below.)
        double required = 0.0;
        boolean visible = bad == null && sees(bot, target, cfg);
        if (visible && !st.confirmed) {
            required = requiredFor(bot, target, st, cfg);
            st.required = required;
            visible = required != Perception.NEVER;
        }
        if (visible) {
            double distance = bot.distanceTo(target);
            if (distance > ENGAGE_LIMIT) {
                // Sighted and measured beyond the limit: too far to engage.
                giveUps.merge("too far", 1L, Long::sum);
                up.clearTarget(name);
                beginReturn(now, bot, st, "too far: sighted " + fmt(distance) + " blocks away, the limit is " + fmt(ENGAGE_LIMIT));
                return;
            }
            if (st.unseen > 0) {
                // Every re-sighting restarts the reaction: no instant resume behind a tree, a corner or a pillar.
                st.unseen = 0;
                st.runStart = now;
            }
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
            if (st.confirmed) {
                if (held == null && now > st.confirmedAt + 1) {
                    beginPursue(now, bot, st, "PvP BOT dropped the target");
                }
                return;
            }
            // Confirming: PvP BOT holds nothing (its revenge is re-armed by every hit: cleared again), the bot faces the
            // player and closes in, and the reaction time runs.
            if (held != null) {
                up.clearTarget(name);
            }
            long exposure = now - st.runStart;
            if (Perception.noticed(Perception.exposureSeconds(exposure), required)) {
                confirm(now, bot, st, target, exposure);
                logInfo(now, name, "aggro: " + name + " confirmed " + target.name() + " after "
                        + fmt(Perception.exposureSeconds(exposure)) + " s of unbroken sight and fights it");
            } else {
                steerToward(bot, p);
            }
            return;
        }
        st.unseen++;
        if (st.confirmed) {
            // The first unseen tick: no longer confirmed, and PvP BOT must not walk toward the true position.
            st.confirmed = false;
            st.forcedByUs = null;
            up.clearTarget(name);
        } else if (held != null) {
            up.clearTarget(name);
        }
        if (st.unseen >= cfg.loseGraceTicks()) {
            beginPursue(now, bot, st, "out of sight for " + st.unseen + " ticks");
            return;
        }
        if (st.lkp != null) {
            steerToward(bot, st.lkp);
        }
    }

    /** One tick toward {@code goal}: walk, or (close by) stand and look at it. Never a teleport. */
    private void steerToward(Watcher bot, Pos goal) {
        if (!up.steeringAvailable()) {
            return;
        }
        if (bot.position().horizontalTo(goal) <= CONFIRM_STOP) {
            up.halt(bot.handle());
            up.look(bot.handle(), goal);
        } else {
            up.steer(bot.handle(), goal, WALK_SPEED);
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
        giveUps.merge(st.target == null ? "hit from cover" : "lost", 1L, Long::sum);
        st.confirmed = false;
        st.forcedByUs = null;
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
        transition(now, name, "pursues " + (st.target == null ? "where the shot came from" : st.target + "'s last position") + ", " + fmt(bot.position().horizontalTo(st.pursueGoal))
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
                        log.debug("aggro: " + bot.name() + " continues along the last heading of " + who(st));
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
        // A sound heard while pursuing (from out of sight) is where the search starts, else the last known position.
        boolean heard = st.hint != null && st.hintTick >= st.phaseStart;
        st.focus = heard ? st.hint : st.lkp != null ? st.lkp : bot.position();
        st.visited.clear();
        st.points = 0;
        st.spot = null;
        st.hint = null;
        st.hintUsed = Long.MIN_VALUE;
        resetWalk(st, now);
        startSweep(now, bot, st);
        up.halt(bot.handle());
        transition(now, bot.name(), "searches for " + who(st) + " (" + why + ")");
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
            PathPlanner.Plan plan = planner.plan(bot.handle(), goal, PLAN_RANGE);
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
        attention.remove(name);
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

    /**
     * Forgets every hunt without a per-bot reason (PvP BOT is unavailable, or this controller switched itself off after
     * repeated failures), and lets every PvP BOT patrol this controller paused walk again. Best effort: the calls may
     * fail for the very reason the hunts are dropped, and that must never throw out of the tick.
     */
    private void clearStatesResumingPatrols() {
        for (Map.Entry<String, BotState> e : states.entrySet()) {
            if (e.getValue().patrolPaused) {
                try {
                    up.resumePatrol(e.getKey());
                } catch (RuntimeException ex) {
                    warnOnce("resume|" + e.getKey(), "aggro: could not resume the patrol of " + e.getKey() + ": " + ex.getMessage());
                }
            }
        }
        states.clear();
        watching.clear();
        attention.clear();
    }

    private void dropAll(String reason) {
        for (String name : new ArrayList<>(states.keySet())) {
            dropBot(name, reason);
        }
        watching.clear();
        attention.clear();
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
                        ? "aggro: active, inhabitants chase players they see or hear (line of sight, never farther than " + fmt(ENGAGE_LIMIT)
                        + " blocks, after a " + fmt(cfg.perception().reactionBaseSeconds()) + " to " + fmt(cfg.perception().reactionAt64Seconds())
                        + " s reaction), search "
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
            clearStatesResumingPatrols();
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
                case CHASE -> chasing++;
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

    /**
     * Whether {@code botName} may hurt the player {@code victim} right now (a melee blow, or a crossbow shot fired by the
     * addon). False only while this controller manages the bot's aggro and the bot has no CONFIRMED engagement with exactly
     * that player: the reaction time is enforced at damage level, so PvP BOT's instant revenge or a stale target can never
     * strike or shoot inside the window. True (nothing to enforce) when the controller is inactive, perception is off (no
     * reaction time), the fight is someone else's forced target, or anything is unknown (fail-open). Mob victims are not
     * asked about: PvP BOT's own revenge against mobs is left alone.
     */
    public boolean mayAttackPlayer(String botName, String victim) {
        try {
            if (mode != Mode.ACTIVE || failed || !config.get().enabled() || !config.get().perception().enabled()) {
                return true;
            }
            BotState s = states.get(botName);
            if (s == null) {
                // No engagement of ours: only somebody else's forced target may hurt a player.
                return up.forcedTarget(botName) != null;
            }
            if (s.kind == Kind.EXTERNAL) {
                return true;
            }
            return s.phase == Phase.CHASE && s.kind == Kind.PLAYER && s.confirmed && s.target != null
                    && s.target.equalsIgnoreCase(victim);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** The player {@code botName} has a CONFIRMED engagement with (the reaction time served, PvP BOT holds it), or null. */
    public String confirmedTarget(String botName) {
        BotState s = states.get(botName);
        return s != null && s.phase == Phase.CHASE && s.kind == Kind.PLAYER && s.confirmed ? s.target : null;
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

    /**
     * True while the bot is engaged with a hunt target: reacting, chasing, pursuing the last known position or searching.
     * Walking back home ({@link Phase#RETURN}) is not engaged. The population allocator never removes an engaged bot.
     */
    public boolean isEngaged(String botName) {
        Phase p = phaseOf(botName);
        return p == Phase.CHASE || p == Phase.PURSUE || p == Phase.SEARCH;
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
            String how = s.noticed != null ? "; noticed by " + senseText(s.noticed) + " after " + fmt(Perception.exposureSeconds(s.reaction)) + " s"
                    : s.cause == Cause.HIT ? "; hit, confirmed after " + fmt(Perception.exposureSeconds(s.reaction)) + " s" : "";
            switch (s.phase) {
                case CHASE:
                    if (s.kind == Kind.EXTERNAL) {
                        return "fighting " + s.target + " (forced by someone else; not managed)";
                    }
                    if (s.kind == Kind.PLAYER && !s.confirmed) {
                        return "reacting to " + s.target + " (" + (s.unseen > 0 ? "out of sight for " + fmt(s.unseen / 20.0)
                                : fmt(Perception.exposureSeconds(Math.max(0L, lastTick - s.runStart))) + " of " + fmt(s.required))
                                + " s)";
                    }
                    return (s.kind == Kind.MOB ? "fighting " : "chasing ") + s.target + " (seen "
                            + fmt(s.unseen / 20.0) + " s ago" + (s.kind == Kind.MOB ? "" : how) + ")";
                case PURSUE:
                    return "pursuing " + (s.target == null ? "the shot's line" : s.target + "'s last position") + ", "
                            + fmt(s.pursueGoal == null || s.pos == null ? 0.0 : s.pursueGoal.horizontalTo(s.pos)) + " to go";
                case SEARCH:
                    return "searching for " + who(s) + ", "
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

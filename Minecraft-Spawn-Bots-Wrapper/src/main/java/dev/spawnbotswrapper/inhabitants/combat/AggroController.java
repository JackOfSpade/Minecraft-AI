package dev.spawnbotswrapper.inhabitants.combat;

import dev.spawnbotswrapper.inhabitants.combat.AggroWorld.Body;
import dev.spawnbotswrapper.inhabitants.combat.AggroWorld.Pos;
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
import java.util.function.Supplier;

/**
 * The aggro range and the leash of the hostile inhabitants.
 * <p>
 * <b>Noticing.</b> An idle inhabitant notices a player only within {@code acquireRange} blocks (default 10) AND in
 * line of sight. It then hands that player to PvP BOT as a forced target. PvP BOT's own auto-target (a 64 block
 * box without line of sight) must be off for this; while it is on, PvP BOT acquires by itself and this class
 * only supervises (inert mode).
 * <p>
 * <b>Being hit.</b> Any target PvP BOT holds that this class did not hand over (its revenge memory after a hit
 * from any distance, or a mob fight) starts an engagement too, so a hit from far away is chased as well.
 * <p>
 * <b>Giving up.</b> Every engagement ends when the inhabitant is more than {@code leashRange} blocks (default 32)
 * from where the engagement began, or has not seen its target for {@code loseSightTicks} ticks (default 200, 10 s),
 * or the target is gone. PvP BOT's target state is then cleared (which also wipes its revenge memory) and, when
 * {@code returnToOrigin} is on, the inhabitant WALKS back to where the engagement began (PvP BOT's own look and
 * move-toward inputs, applied after PvP BOT's own tick; never a teleport). The return ends on arrival, or is
 * abandoned when it makes no progress or takes too long. While the return is pending no new target is noticed,
 * and a new hit continues the ORIGINAL engagement origin, so repeated hits cannot drag a bot ever farther from home.
 * <p>
 * <b>External targets.</b> A forced target somebody else set (a {@code /pvpbot} command, another mod) is tracked for
 * the status text only; it is never leashed, cleared or returned from.
 * <p>
 * Pure decision logic over {@link AggroWorld} and {@link TargetControl}; no Minecraft or PvP BOT classes. Server
 * thread only. {@link #tick} must run AFTER PvP BOT's own tick of the same server tick.
 */
public final class AggroController {

    /** Tunables; see {@code InhabitantsConfig.Aggro}. */
    public record Config(boolean enabled, double acquireRange, boolean requireLineOfSight, int scanIntervalTicks,
                         double leashRange, int loseSightTicks, boolean returnToOrigin, double returnArriveDistance,
                         int returnStuckTicks, int returnMaxTicks) {
        public static Config defaults() {
            return new Config(true, 10.0, true, 5, 32.0, 200, true, 1.5, 200, 1200);
        }

        public Config {
            scanIntervalTicks = Math.max(1, scanIntervalTicks);
            loseSightTicks = Math.max(1, loseSightTicks);
            returnStuckTicks = Math.max(1, returnStuckTicks);
            returnMaxTicks = Math.max(1, returnMaxTicks);
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
        /** PvP BOT's auto-target is on: PvP BOT acquires by itself; only the leash and the return apply. */
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

    /** How an engagement began. */
    public enum Cause {
        /** This controller noticed a player and forced them on the bot. */
        ACQUIRED,
        /** The target is the bot's last attacker (PvP BOT's revenge memory). */
        HIT,
        /** Anything else PvP BOT holds (a mob it decided to fight, a faction enemy, somebody else's force). */
        OTHER
    }

    /** Snapshot for diagnostics. */
    public record Stats(Mode mode, int engaged, int returning, long acquisitions, long hits, long returned,
                        Map<String, Long> giveUps, Map<String, Long> abandoned) {
    }

    /** Consecutive upstream failures after which the controller switches itself off. */
    static final int MAX_CONSECUTIVE_FAILURES = 20;
    /** At most one INFO line per bot per this many ticks for starts and give-ups; the rest go to debug. */
    static final long INFO_GAP_TICKS = 200;
    /** How far (blocks) the distance to the origin must shrink to count as progress. */
    static final double RETURN_PROGRESS = 1.0;
    /** Speed handed to PvP BOT's move-toward call (1.0 is what its own patrols use). */
    static final double RETURN_SPEED = 1.0;

    /** Where an engagement began. */
    record Origin(Pos pos, Object dimension) {
    }

    private static final class Engagement {
        Object entity;
        String name;
        final Cause cause;
        final Origin origin;
        final long startTick;
        /** Somebody else's forced target: tracked, never leashed or cleared. */
        boolean external;
        /** The forced name this controller set, or null. */
        final String forcedByUs;
        int unseen;
        double leashDistance;

        Engagement(Object entity, String name, Cause cause, Origin origin, long startTick, boolean external,
                   String forcedByUs) {
            this.entity = entity;
            this.name = name;
            this.cause = cause;
            this.origin = origin;
            this.startTick = startTick;
            this.external = external;
            this.forcedByUs = forcedByUs;
        }
    }

    private static final class Return {
        final Origin origin;
        final long startTick;
        double best;
        long bestTick;
        double distance;

        Return(Origin origin, long startTick, double distance) {
            this.origin = origin;
            this.startTick = startTick;
            this.best = distance;
            this.bestTick = startTick;
            this.distance = distance;
        }
    }

    private static final class BotState {
        Engagement engagement;
        Return ret;
    }

    private final Supplier<Config> config;
    private final TargetControl up;
    private final Log log;

    private final Map<String, BotState> states = new LinkedHashMap<>();
    private final Map<String, Long> lastInfo = new HashMap<>();
    private final Map<String, Long> giveUps = new TreeMap<>();
    private final Map<String, Long> abandoned = new TreeMap<>();
    private final Set<String> warned = new HashSet<>();
    private long acquisitions;
    private long hitEngagements;
    private long returned;
    private long lastTick = Long.MIN_VALUE;
    private long lastScan = Long.MIN_VALUE;
    private Settings settings;
    private Mode mode = Mode.UNKNOWN;
    private int failures;
    private boolean failed;
    private String warnedLeash = "";

    public AggroController(Supplier<Config> config, TargetControl up, Log log) {
        this.config = config;
        this.up = up;
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
        Config cfg = config.get();
        if (failed) {
            return;
        }
        if (!up.available()) {
            setMode(Mode.UPSTREAM_UNAVAILABLE, up.unavailableReason());
            states.clear();
            return;
        }
        boolean scan = lastScan == Long.MIN_VALUE || now - lastScan >= cfg.scanIntervalTicks();
        if (scan) {
            lastScan = now;
            settings = readSettings();
        }
        if (!cfg.enabled()) {
            setMode(Mode.OFF, null);
            dropAll("aggro disabled");
            return;
        }
        Mode wanted = modeFor(settings);
        if (scan) {
            setMode(wanted, null);
            checkLeash(cfg);
        }
        boolean mayStart = settings == null || settings.combatEnabled();
        boolean mayAcquire = scan && wanted == Mode.ACTIVE;

        boolean prune = !states.isEmpty();
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
                step(now, world, bot, cfg, mayStart, mayAcquire);
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
        }
    }

    /** Forget everything: the server stopped or restarted. Does not touch PvP BOT. */
    public void reset() {
        states.clear();
        lastInfo.clear();
        lastTick = Long.MIN_VALUE;
        lastScan = Long.MIN_VALUE;
        settings = null;
        mode = Mode.UNKNOWN;
        failures = 0;
        failed = false;
    }

    // ================================================================ per bot

    private void step(long now, AggroWorld world, Watcher bot, Config cfg, boolean mayStart, boolean mayAcquire) {
        String name = bot.name();
        BotState st = states.get(name);
        if (!bot.alive()) {
            if (st != null) {
                dropBot(name, "bot died");
            }
            return;
        }
        if (st != null && st.engagement != null) {
            maintain(now, world, bot, st, cfg);
        }
        boolean engaged = st != null && st.engagement != null;
        if (!engaged && mayStart) {
            Target t = up.currentTarget(name);
            if (t != null) {
                st = startFromTarget(now, bot, st, t);
                engaged = true;
            }
        }
        if (st != null && st.ret != null) {
            stepReturn(now, bot, st, cfg);
        }
        if (mayAcquire && !engaged && (st == null || st.ret == null)) {
            acquire(now, world, bot, cfg);
        }
        if (st != null && st.engagement == null && st.ret == null) {
            states.remove(name);
        }
    }

    private BotState state(String name) {
        return states.computeIfAbsent(name, k -> new BotState());
    }

    // ---------------------------------------------------------------- start

    /** PvP BOT holds a target this controller did not hand over: revenge, a mob fight, or somebody else's force. */
    private BotState startFromTarget(long now, Watcher bot, BotState existing, Target t) {
        String name = bot.name();
        BotState st = existing != null ? existing : state(name);
        String forced = up.forcedTarget(name);
        boolean external = forced != null;
        Cause cause = external ? Cause.OTHER : t.revenge() ? Cause.HIT : Cause.OTHER;
        // A return still pending keeps its origin: repeated hits must not drag the bot farther from home each time.
        boolean continued = st.ret != null;
        Origin origin = continued ? st.ret.origin : new Origin(bot.position(), bot.dimension());
        st.ret = null;
        st.engagement = new Engagement(t.entity(), t.name(), cause, origin, now, external, null);
        if (external) {
            log.debug("aggro: " + name + " fights " + t.name() + " on a forced target set by someone else; tracked only");
        } else {
            hitEngagements++;
            logInfo(now, name, "aggro: " + name + " engaged " + t.name() + " (" + cause.name().toLowerCase(Locale.ROOT)
                    + ")" + (continued ? ", original origin kept" : ""));
        }
        return st;
    }

    // ---------------------------------------------------------------- maintain

    private void maintain(long now, AggroWorld world, Watcher bot, BotState st, Config cfg) {
        Engagement e = st.engagement;
        String name = bot.name();
        String forced = up.forcedTarget(name);
        if (!e.external && forced != null && !forced.equalsIgnoreCase(e.forcedByUs)) {
            e.external = true;
            log.debug("aggro: " + name + " now has a forced target set by someone else (" + forced + "); tracked only");
        }
        Target t = up.currentTarget(name);
        if (e.external) {
            if (forced == null) {
                // Not forced any more: it is an ordinary fight again and is judged as one from the next step.
                st.engagement = null;
                log.debug("aggro: " + name + " no longer has an external forced target");
                return;
            }
            if (t != null) {
                e.entity = t.entity();
                e.name = t.name();
                Body target = world.bodyOf(t.entity());
                e.unseen = target != null && !bot.canSee(target) ? e.unseen + 1 : 0;
            }
            e.leashDistance = bot.position().horizontalTo(e.origin.pos());
            return;
        }
        String reason = giveUpReason(world, bot, e, t, cfg);
        if (reason != null) {
            giveUp(now, bot, st, reason, cfg);
        }
    }

    /** Why the engagement must end now, or null. Also updates the counters the status text shows. */
    private String giveUpReason(AggroWorld world, Watcher bot, Engagement e, Target t, Config cfg) {
        if (t == null) {
            return "target lost";
        }
        e.entity = t.entity();
        e.name = t.name();
        Body target = world.bodyOf(t.entity());
        if (target == null || !target.alive()) {
            return "target gone";
        }
        if (!Objects.equals(bot.dimension(), e.origin.dimension())) {
            return "bot changed level";
        }
        if (!Objects.equals(bot.dimension(), target.dimension())) {
            return "target in another level";
        }
        Settings s = settings;
        if (s != null && !s.attackInvincible() && !attackable(target)) {
            return "target no longer attackable";
        }
        e.leashDistance = bot.position().horizontalTo(e.origin.pos());
        if (e.leashDistance > cfg.leashRange()) {
            return "leash";
        }
        if (bot.canSee(target)) {
            e.unseen = 0;
        } else {
            e.unseen++;
            if (e.unseen >= cfg.loseSightTicks()) {
                return "out of sight";
            }
        }
        return null;
    }

    /**
     * Ends the engagement: clears PvP BOT's target state (also its revenge memory) and starts the walk back. If the
     * clear throws, the engagement stays and the next tick tries again.
     */
    private void giveUp(long now, Watcher bot, BotState st, String reason, Config cfg) {
        Engagement e = st.engagement;
        String name = bot.name();
        up.clearTarget(name);
        st.engagement = null;
        giveUps.merge(reason, 1L, Long::sum);
        boolean back = cfg.returnToOrigin() && canReturn(bot, e.origin);
        if (back) {
            st.ret = new Return(e.origin, now, bot.position().horizontalTo(e.origin.pos()));
        }
        logInfo(now, name, "aggro: " + name + " gives up on " + e.name + " after " + (now - e.startTick) + " ticks ("
                + reason + ", " + fmt(e.leashDistance) + " from origin, unseen " + e.unseen + "t)"
                + (back ? ", walking back" : ""));
    }

    private boolean canReturn(Watcher bot, Origin origin) {
        if (!Objects.equals(bot.dimension(), origin.dimension())) {
            return false;
        }
        if (!up.steeringAvailable()) {
            warnOnce("steering", "aggro: inhabitants cannot walk back to where a fight began: " + up.steeringProblem());
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- return

    private void stepReturn(long now, Watcher bot, BotState st, Config cfg) {
        Return r = st.ret;
        String name = bot.name();
        if (!Objects.equals(bot.dimension(), r.origin.dimension())) {
            abandon(name, st, "bot changed level");
            return;
        }
        double d = bot.position().horizontalTo(r.origin.pos());
        r.distance = d;
        if (d <= cfg.returnArriveDistance()) {
            st.ret = null;
            returned++;
            log.debug("aggro: " + name + " is back where the fight began (" + fmt(d) + " blocks away, "
                    + (now - r.startTick) + " ticks)");
            return;
        }
        if (now - r.startTick >= cfg.returnMaxTicks()) {
            abandon(name, st, "took too long, " + fmt(d) + " blocks left");
            return;
        }
        if (d < r.best - RETURN_PROGRESS) {
            r.best = d;
            r.bestTick = now;
        } else if (now - r.bestTick >= cfg.returnStuckTicks()) {
            abandon(name, st, "stuck, " + fmt(d) + " blocks left");
            return;
        }
        up.steer(bot.handle(), r.origin.pos(), RETURN_SPEED);
    }

    private void abandon(String name, BotState st, String reason) {
        st.ret = null;
        abandoned.merge(reason.contains(",") ? reason.substring(0, reason.indexOf(',')) : reason, 1L, Long::sum);
        log.debug("aggro: " + name + " stops walking back: " + reason);
    }

    // ---------------------------------------------------------------- drop

    /** The bot died or left: forget its engagement and its pending return, and release a force we set. */
    private void dropBot(String name, String reason) {
        BotState st = states.remove(name);
        if (st == null) {
            return;
        }
        Engagement e = st.engagement;
        if (e != null && e.forcedByUs != null && !e.external) {
            releaseOurForce(name, e);
        }
        log.debug("aggro: " + name + " dropped its " + (e != null ? "engagement" : "return") + ": " + reason);
    }

    private void dropAll(String reason) {
        for (String name : new ArrayList<>(states.keySet())) {
            dropBot(name, reason);
        }
    }

    private void releaseOurForce(String name, Engagement e) {
        try {
            String forced = up.forcedTarget(name);
            if (forced != null && forced.equalsIgnoreCase(e.forcedByUs)) {
                up.clearTarget(name);
            }
        } catch (RuntimeException ex) {
            noteFailure("releasing the forced target of " + name, ex);
        }
    }

    // ================================================================ acquisition

    private void acquire(long now, AggroWorld world, Watcher bot, Config cfg) {
        Settings s = settings;
        if (s == null) {
            return;
        }
        List<? extends Body> near = world.playersWithin(bot, cfg.acquireRange());
        if (near.isEmpty()) {
            return;
        }
        // The bot must be idle: no target of its own (revenge, faction enemy, anything) and no forced name.
        if (up.currentTarget(bot.name()) != null || up.forcedTarget(bot.name()) != null) {
            return;
        }
        List<Body> ordered = new ArrayList<>(near);
        ordered.sort(Comparator.<Body>comparingDouble(bot::distanceTo)
                .thenComparing(b -> b.name().toLowerCase(Locale.ROOT)));
        for (Body candidate : ordered) {
            if (!validTarget(bot, candidate, s, cfg.acquireRange())) {
                continue;
            }
            if (cfg.requireLineOfSight() && !bot.canSee(candidate)) {
                continue;
            }
            up.setTarget(bot.name(), candidate.name());
            Origin origin = new Origin(bot.position(), bot.dimension());
            state(bot.name()).engagement = new Engagement(candidate.handle(), candidate.name(), Cause.ACQUIRED,
                    origin, now, false, candidate.name());
            acquisitions++;
            logInfo(now, bot.name(), "aggro: " + bot.name() + " noticed " + candidate.name() + " at "
                    + fmt(bot.distanceTo(candidate)) + " blocks");
            return;
        }
    }

    /**
     * The same rules as PvP BOT's own target validity for a player, plus the range: alive, not spectator /
     * creative / invulnerable unless {@code attackInvincible}, not a faction ally unless friendly fire, and a
     * PvP BOT bot only when {@code targetOtherBots}, any other player only when {@code targetPlayers}.
     */
    boolean validTarget(Watcher bot, Body candidate, Settings s, double range) {
        if (candidate.name().equalsIgnoreCase(bot.name()) || !candidate.alive() || !candidate.isPlayer()) {
            return false;
        }
        if (bot.distanceTo(candidate) > range || !Objects.equals(bot.dimension(), candidate.dimension())) {
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
                        ? "aggro: active, inhabitants notice players within " + fmt(cfg.acquireRange())
                        + " blocks in line of sight and give up " + fmt(cfg.leashRange()) + " blocks from where the "
                        + "fight began or after " + cfg.loseSightTicks() + " ticks unseen"
                        + (cfg.returnToOrigin() ? ", then walk back" : "")
                        : text);
            }
            case INERT_AUTO_TARGET -> log.info("aggro: inert, PvP BOT's own auto-target is on and already acquires "
                    + "targets by itself, so nothing is noticed here (the leash and the walk back still apply; "
                    + "turn auto-target off to use the aggro range)");
            case OFF -> log.debug(text);
            case UNKNOWN -> {
            }
            default -> log.warn(text);
        }
    }

    /** WARN once when the chase can outrun PvP BOT's own limit, so PvP BOT may drop a target before the leash decides. */
    private void checkLeash(Config cfg) {
        Settings s = settings;
        if (s == null) {
            return;
        }
        double max = s.maxTargetDistance();
        if (cfg.leashRange() + cfg.acquireRange() > max) {
            String key = cfg.leashRange() + "|" + cfg.acquireRange() + "|" + max;
            if (!key.equals(warnedLeash)) {
                warnedLeash = key;
                log.warn("aggro: leashRange " + fmt(cfg.leashRange()) + " + acquireRange " + fmt(cfg.acquireRange())
                        + " is more than PvP BOT's maxTargetDistance " + fmt(max) + "; PvP BOT may drop a target "
                        + "before the leash decides (raise maxTargetDistance or lower the aggro ranges)");
            }
        }
    }

    // ================================================================ logging

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
        int engaged = 0;
        int returning = 0;
        for (BotState s : states.values()) {
            if (s.engagement != null) {
                engaged++;
            }
            if (s.ret != null) {
                returning++;
            }
        }
        return new Stats(mode, engaged, returning, acquisitions, hitEngagements, returned,
                Map.copyOf(giveUps), Map.copyOf(abandoned));
    }

    /** The name of the target of the engagement running on {@code botName}, or null. */
    public String engagedWith(String botName) {
        BotState s = states.get(botName);
        return s == null || s.engagement == null ? null : s.engagement.name;
    }

    /** The cause of the engagement running on {@code botName}, or null. */
    public Cause causeOf(String botName) {
        BotState s = states.get(botName);
        return s == null || s.engagement == null ? null : s.engagement.cause;
    }

    /** True while {@code botName} is walking back to where a fight began. */
    public boolean isReturning(String botName) {
        BotState s = states.get(botName);
        return s != null && s.ret != null;
    }

    /**
     * One short text about one bot for the status output, e.g.
     * {@code engaged Steve (acquired) 12.3 from origin, unseen 40t}, {@code returning, 18.0 to origin} or
     * {@code idle}. Distances are as of the last tick.
     */
    public String describe(String botName) {
        BotState s = states.get(botName);
        if (s != null && s.engagement != null) {
            Engagement e = s.engagement;
            return "engaged " + e.name + " (" + e.cause.name().toLowerCase(Locale.ROOT)
                    + (e.external ? ", external" : "") + ") " + fmt(e.leashDistance) + " from origin, unseen "
                    + e.unseen + "t";
        }
        if (s != null && s.ret != null) {
            return "returning, " + fmt(s.ret.distance) + " to origin";
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

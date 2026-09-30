package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities;
import dev.spawnbotswrapper.inhabitants.store.BotSnapshot;
import dev.spawnbotswrapper.inhabitants.store.PopulationStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * What the engine's parts share: the ports, the counters, failure logging, and the persistence clock.
 * <p>
 * The configuration is deliberately NOT kept: every reader asks {@link #config()} again, so a reloaded
 * config takes effect on the next tick and nothing derived from it can go stale. Only bookkeeping that
 * records what already happened (ticks, handles) lives in the engine's own state.
 */
final class EngineContext {
    private static final Logger LOG = LoggerFactory.getLogger(PopulationEngine.class);
    private static final InhabitantsConfig.Processing FALLBACK_PROCESSING = new InhabitantsConfig.Processing();
    private static final InhabitantsConfig.TpsThrottle FALLBACK_TPS_THROTTLE = new InhabitantsConfig.TpsThrottle();
    private static final InhabitantsConfig.Dormancy FALLBACK_DORMANCY = new InhabitantsConfig.Dormancy();
    private static final InhabitantsConfig.Allocation FALLBACK_ALLOCATION = new InhabitantsConfig.Allocation();
    private static final String FALLBACK_PREFIX = "Inh";

    final Supplier<InhabitantsConfig> configSource;
    final PopulationStorage store;
    final BotGateway bots;
    final WorldGateway world;
    final Clock clock;
    final ProfileFactory profiles;
    final SpawnPlanner planner;
    final EngineCounters counters = new EngineCounters();
    final ThrottledLog log;

    private long lastSaveTick;

    EngineContext(Supplier<InhabitantsConfig> configSource, PopulationStorage store, BotGateway bots,
                  WorldGateway world, Clock clock, ProfileFactory profiles, SpawnPlanner planner) {
        this.configSource = configSource;
        this.store = store;
        this.bots = bots;
        this.world = world;
        this.clock = clock;
        this.profiles = profiles;
        this.planner = planner;
        this.log = new ThrottledLog(LOG, clock::tick);
        this.lastSaveTick = clock.tick();
    }

    /** The current configuration; null only if the supplier itself misbehaves (the engine then idles). */
    InhabitantsConfig config() {
        return configSource.get();
    }

    long now() {
        return clock.tick();
    }

    // ------------------------------------------------------------------ tolerant config access

    static InhabitantsConfig.Processing processing(InhabitantsConfig cfg) {
        return cfg.processing != null ? cfg.processing : FALLBACK_PROCESSING;
    }

    static InhabitantsConfig.TpsThrottle tpsThrottle(InhabitantsConfig cfg) {
        return cfg.tpsThrottle != null ? cfg.tpsThrottle : FALLBACK_TPS_THROTTLE;
    }

    static InhabitantsConfig.Dormancy dormancy(InhabitantsConfig cfg) {
        return cfg.dormancy != null ? cfg.dormancy : FALLBACK_DORMANCY;
    }

    static InhabitantsConfig.Allocation allocation(InhabitantsConfig cfg) {
        return cfg.allocation != null ? cfg.allocation : FALLBACK_ALLOCATION;
    }

    static boolean isDeterministic(InhabitantsConfig cfg) {
        return cfg.deterministic != null && cfg.deterministic.enabled;
    }

    static String salt(InhabitantsConfig cfg) {
        return cfg.deterministic != null && cfg.deterministic.salt != null ? cfg.deterministic.salt : "";
    }

    static String namePrefix(InhabitantsConfig cfg) {
        return cfg.spawning != null && cfg.spawning.namePrefix != null ? cfg.spawning.namePrefix : FALLBACK_PREFIX;
    }

    static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ containment

    /**
     * Runs {@code r} so that nothing it throws can escape into the server tick. Only an
     * {@link OutOfMemoryError} is let through: the JVM is beyond helping at that point and hiding it
     * would only postpone the crash.
     */
    void guard(String site, Runnable r) {
        try {
            r.run();
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            log.error(site, String.valueOf(t), t);
        }
    }

    // ------------------------------------------------------------------ persistence

    /**
     * Writes changed state to disk now. Used for what must survive a crash (the roll, the write-ahead
     * before a spawn request, admin actions); everything else is written by {@link #saveIfDue}.
     *
     * @return false when the write failed; callers that must not proceed without durability check this
     */
    boolean saveNow() {
        lastSaveTick = clock.tick();
        try {
            boolean ok = store.saveIfDirty();
            if (!ok) {
                log.warn("save", "Could not write the inhabitants data; will retry (nothing new is spawned until it can be saved)");
            }
            return ok;
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            log.error("save", String.valueOf(t), t);
            return false;
        }
    }

    /**
     * Makes the current records of these structures durable now through the store's write-ahead journal (cheap: one small
     * append, not a rewrite of the whole store). What must be durable before a bot is emptied and removed, see {@link Retirer}.
     *
     * @return false when it could not be done; the caller must then not proceed
     */
    boolean journalNow(java.util.Collection<dev.spawnbotswrapper.inhabitants.structure.StructureKey> keys) {
        try {
            boolean ok = store.journal(keys);
            if (!ok) {
                log.warn("journal", "Could not write the inhabitants data; will retry (no inhabitant is removed until it can be saved)");
            }
            return ok;
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            log.error("journal", String.valueOf(t), t);
            return false;
        }
    }

    void saveIfDue(long now, InhabitantsConfig cfg) {
        long interval = Math.max(1, processing(cfg).saveIntervalTicks);
        if (now - lastSaveTick >= interval) {
            saveNow();
        }
    }

    // ------------------------------------------------------------------ port calls that must never throw

    /** A throwing gateway is an unavailable gateway: nothing is rolled or persisted on its account. */
    boolean available() {
        try {
            return bots.available();
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            log.error("available", String.valueOf(t), t);
            return false;
        }
    }

    /** A store that cannot even say whether it is usable is treated as unusable. */
    boolean storeUsable() {
        try {
            return store.usable();
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            log.error("usable", String.valueOf(t), t);
            return false;
        }
    }

    /** Whether the bot is online; null when the gateway threw (unknown: callers keep their previous belief). */
    Boolean online(String name) {
        try {
            return bots.isOnline(name);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            log.error("isOnline", String.valueOf(name), t);
            return null;
        }
    }

    /** Blocks from the bot to the nearest online real player; -1 when unknown (see {@link BotGateway#distanceToNearestPlayer}). */
    double distanceToNearestPlayer(String name) {
        try {
            return bots.distanceToNearestPlayer(name);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            log.error("distanceToNearestPlayer", String.valueOf(name), t);
            return -1;
        }
    }

    Boolean managed(String name) {
        try {
            return bots.isManaged(name);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            log.error("isManaged", String.valueOf(name), t);
            return null;
        }
    }

    /** Whether the name may be used for a new bot; null when the gateway threw. */
    Boolean nameFree(String name) {
        try {
            return bots.nameAvailable(name);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            log.error("nameAvailable", String.valueOf(name), t);
            return null;
        }
    }

    /** The live state of an online inhabitant; null when it cannot be read (or the gateway threw). */
    BotSnapshot snapshot(String name) {
        try {
            return bots.snapshot(name);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            log.error("snapshot", String.valueOf(name), t);
            return null;
        }
    }

    /** Puts an online inhabitant right (survival, vanilla attributes); what was wrong, or none when the gateway threw. */
    BotGateway.StateFixes enforceVanilla(String name) {
        try {
            BotGateway.StateFixes fixes = bots.enforceVanilla(name);
            return fixes == null ? BotGateway.StateFixes.NONE : fixes;
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            log.error("enforceVanilla", String.valueOf(name), t);
            return BotGateway.StateFixes.NONE;
        }
    }

    /** Where every real player is; empty when unknown (or when the gateway threw). */
    java.util.List<BotGateway.PlayerPos> realPlayers() {
        try {
            java.util.List<BotGateway.PlayerPos> players = bots.realPlayers();
            return players == null ? java.util.List.of() : players;
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            log.error("realPlayers", String.valueOf(t), t);
            return java.util.List.of();
        }
    }

    /** How far from a real player a structure can host bots (blocks). */
    double relevanceRadiusBlocks() {
        try {
            double r = bots.relevanceRadiusBlocks();
            return Double.isFinite(r) && r > 0 ? r : 192.0;
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            log.error("relevanceRadiusBlocks", String.valueOf(t), t);
            return 192.0;
        }
    }

    /** True when the bot is engaged with a player, or when that cannot be told (then it is never removed). */
    boolean engaged(String name) {
        try {
            return bots.isEngaged(name);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            log.error("isEngaged", String.valueOf(name), t);
            return true;
        }
    }

    /** Whether a real player sees the bot right now; false when unknown. */
    boolean seenByHuman(String name) {
        try {
            return bots.seenByHuman(name);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            log.error("seenByHuman", String.valueOf(name), t);
            return false;
        }
    }

    /** Whether the bot is inside the area a real player keeps loaded (its chunk is loaded because of a player); true when that cannot be told. */
    boolean loadedByHuman(String name) {
        try {
            return bots.loadedByHuman(name);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            log.error("loadedByHuman", String.valueOf(name), t);
            return true;
        }
    }

    /** Where a live bot stands; null when unknown. */
    BotGateway.PlayerPos position(String name) {
        try {
            return bots.position(name);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            log.error("position", String.valueOf(name), t);
            return null;
        }
    }

    /** Best effort: takes a bot the addon could not finish out of the world and releases its upstream state. */
    void discard(String name) {
        guard("discard", () -> bots.remove(name));
        guard("discard", () -> bots.forget(name));
    }

    /** Upstream's documented defaults stand in when the gateway cannot read the settings (or returns nothing). */
    GlobalCapabilities capabilities() {
        try {
            GlobalCapabilities caps = bots.capabilities();
            if (caps != null) {
                return caps;
            }
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            log.error("capabilities", String.valueOf(t), t);
        }
        return GlobalCapabilities.upstreamDefaults();
    }

    void debug(InhabitantsConfig cfg, String message, Object... args) {
        if (cfg.debug) {
            LOG.info(message, args);
        } else {
            LOG.debug(message, args);
        }
    }

    /** A rare, operator-relevant anomaly (never per tick; at most once per bot and kind): always written at WARN. */
    void warn(String message, Object... args) {
        LOG.warn(message, args);
    }

    /** A rare, operator-relevant event (never per tick or per bot): always written at INFO. */
    void info(String message, Object... args) {
        LOG.info(message, args);
    }
}

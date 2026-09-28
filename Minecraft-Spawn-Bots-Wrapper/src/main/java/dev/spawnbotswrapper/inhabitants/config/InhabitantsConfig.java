package dev.spawnbotswrapper.inhabitants.config;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The editable configuration ({@code config/pvpbot_inhabitants.json}). Plain mutable data so Gson can
 * bind it; every field has a sensible default so a partial file is fine. Nothing in the spawning
 * logic hard-codes these numbers - the values below are only the shipped defaults.
 * <p>
 * Structure identifiers everywhere in this file use the same syntax:
 * <ul>
 *   <li>{@code minecraft:pillager_outpost} - an exact registry id (a bare {@code village_plains} means {@code minecraft:village_plains})</li>
 *   <li>{@code #minecraft:village} - a structure tag</li>
 *   <li>{@code somemod:*} - every structure of one namespace (mod)</li>
 *   <li>{@code *} - everything</li>
 * </ul>
 */
public final class InhabitantsConfig {
    public static final int CURRENT_VERSION = 1;

    public int configVersion = CURRENT_VERSION;

    /** Master switch. When false the addon does nothing (no rolls are made or recorded). */
    public boolean enabled = true;

    /** Verbose logging of rolls, positions and profile generation. */
    public boolean debug = false;

    /** Register the admin/testing commands ({@code /inhabitants ...}). */
    public boolean debugCommands = true;

    /** Op level needed for the admin commands (0-4). */
    public int commandPermissionLevel = 2;

    /**
     * Turn off vanilla's Locator Bar gamerule on server start, so inhabitants (indistinguishable from
     * real players at the protocol level) don't show up as radar-like waypoints under the hunger bar.
     * Applied once per server start; the player can still turn the gamerule back on manually.
     */
    public boolean hideLocatorBar = true;

    /** Baseline occupied/abandoned probability and bot count, used unless overridden below. */
    @SerializedName("default")
    public Rule defaults = new Rule();

    /** Only structures matching one of these are eligible. Empty is treated as {@code ["*"]}. */
    public List<String> include = new ArrayList<>(List.of("*"));

    /** Structures matching any of these are never populated (they are not even rolled). Wins over include. */
    public List<String> exclude = new ArrayList<>(List.of("minecraft:buried_treasure"));

    /** Per-structure overrides: exact id or {@code namespace:*}. Fields you omit fall through to tags, then default. */
    public Map<String, RuleOverride> structures = defaultStructureOverrides();

    /** Per-tag overrides, keys like {@code #minecraft:village}. Earlier entries win over later ones. */
    public Map<String, RuleOverride> tags = defaultTagOverrides();

    /** Dimensions structures are processed in. */
    public Dimensions dimensions = new Dimensions();

    public Profiles profiles = new Profiles();
    public Deterministic deterministic = new Deterministic();
    public Processing processing = new Processing();
    public Spawning spawning = new Spawning();
    /** Despawns inhabitants when the server is struggling, farthest from the nearest real player first. */
    public TpsThrottle tpsThrottle = new TpsThrottle();
    /** Despawns inhabitants that have drifted far from every real player, and restores them later unchanged. */
    public Dormancy dormancy = new Dormancy();
    /** Holds newly-connecting players on their loading screen for a grace period after server start; see {@link Connection}. */
    public Connection connection = new Connection();
    /**
     * Console commands run, in order, once every server start (silently: nothing is echoed to chat), before
     * anything else in this file takes effect. Not specific to inhabitants -- a general convenience so choices
     * that some OTHER mod only stores per world name (PvP BOT's own combat settings, for instance, which reset
     * to its hardcoded defaults on every fresh world with no template of its own) can be reasserted automatically
     * instead of retyped by hand after every new world. Each command is independent: one failing (unknown
     * command, bad argument) is logged and does not stop the rest from running. Example:
     * {@code ["pvpbot settings auto-target true", "pvpbot settings view-distance 16"]}.
     */
    public List<String> startupCommands = new ArrayList<>();

    /**
     * Rolled once per structure: occupied or abandoned, and the minimum bot count if occupied. There is
     * deliberately no per-structure/tag maximum here any more: the ceiling for one structure INSTANCE is
     * always {@code min(EffectiveRule.MAX_BOTS_PER_STRUCTURE, ceil(totalVolume / processing.blocksPerBot))}
     * (see {@link EffectiveRule#sizeCappedMax}) -- a one-room well and a fifty-house village never share a
     * hand-picked number just because they share a tag; a bigger structure simply supports more bots, up
     * to the one global cap every structure shares.
     */
    public static final class Rule {
        /** Probability in [0,1] that a structure is occupied. */
        public double occupiedChance = 0.65;
        /** Bots an occupied structure gets at minimum (also the floor the size-scaled ceiling folds down to). */
        public int minBots = 1;

        public Rule() {
        }

        public Rule(double occupiedChance, int minBots) {
            this.occupiedChance = occupiedChance;
            this.minBots = minBots;
        }
    }

    /** A partial rule; null fields inherit. */
    public static final class RuleOverride {
        public Double occupiedChance;
        public Integer minBots;

        public RuleOverride() {
        }

        public RuleOverride(Double occupiedChance, Integer minBots) {
            this.occupiedChance = occupiedChance;
            this.minBots = minBots;
        }
    }

    public static final class Dimensions {
        public List<String> include = new ArrayList<>(List.of("*"));
        public List<String> exclude = new ArrayList<>();
    }

    public static final class Profiles {
        /**
         * Give every bot an independently randomized loadout / attribute / behaviour profile.
         * When false bots keep whatever PvP BOT gives a fresh bot (nothing).
         */
        public boolean randomize = true;
        /** How many buckets a numeric range is divided into for coverage sampling (2-64). */
        public int coverageBuckets = 8;
        /** Also vary vanilla entity attributes (movement speed, max health, ...) per bot. */
        public boolean attributeVariation = true;
        /** Allow the SCALE (body size) attribute to vary. Off by default: tiny/huge bots can break pathing through doors. */
        public boolean scaleVariation = false;
        /** Give bots a PvP BOT patrol path and/or faction where PvP BOT supports it (see README). */
        public boolean behaviorVariation = true;
        /** Re-apply the stored profile if a bot comes back (e.g. after a restart) without its items/attributes. */
        public boolean reapplyOnRestore = true;
        /**
         * Allow loadouts with end crystals + obsidian, or respawn anchors + glowstone. PvP BOT's crystal/anchor
         * PvP places blocks and causes EXPLOSIONS, which destroy the structure the bot lives in. Off by default.
         */
        public boolean allowExplosiveKits = false;
        /**
         * Allow elytra loadouts. Elytra + fireworks + a mace triggers PvP BOT's flight routine whenever the bot
         * has a target, which sends bots soaring out of their structure. Off by default.
         */
        public boolean allowElytra = false;
    }

    public static final class Deterministic {
        /**
         * Derive every roll and profile from world seed + dimension + structure id + start position
         * (+ salt), so a fresh copy of the same world produces the same inhabitants. Persisted results
         * remain authoritative once a structure has been processed.
         */
        public boolean enabled = false;
        /** Change to get a different-but-still-reproducible set of results. */
        public String salt = "";
    }

    public static final class Processing {
        /**
         * true: only structures generated while this addon is installed are processed.
         * false: any structure whose start chunk loads and has no record yet is rolled (existing worlds get inhabitants too).
         */
        public boolean onlyNewlyGenerated = false;
        /** How many newly seen structures are rolled per server tick. */
        public int maxStructuresPerTick = 2;
        /** How many bots are requested from PvP BOT per server tick (spawning a fake player is not free). */
        public int maxBotsPerTick = 1;
        /** Minimum ticks between two bot spawn requests. */
        public int spawnIntervalTicks = 20;
        /**
         * How many blocks of a structure's total volume (sum of its pieces' bounding boxes, or the
         * overall bounds when it has no piece data) justify one more bot, before
         * {@link EffectiveRule#MAX_BOTS_PER_STRUCTURE} clamps the result. A single global knob rather than
         * a per-structure one: a bigger structure earning more bots is a property of its size, not of an
         * operator having hand-picked a number for that particular structure type. Tune this once you have
         * seen real structures in play -- it is a rough starting estimate, not a calibrated constant.
         */
        public double blocksPerBot = 300.0;
        /** Ticks to wait before the first population attempt, so neighbouring chunks of the structure can load. */
        public int initialDelayTicks = 40;
        /** Ticks between retries while a structure's chunks are not loaded/valid yet. */
        public int retryIntervalTicks = 100;
        /** Population attempts (with loaded chunks) before an occupied structure is given up on permanently. */
        public int maxAttemptsPerStructure = 12;
        /** Never have more than this many living inhabitants at once; further population waits (no re-roll). 0 = unlimited. */
        public int maxLiveBots = 24;
        /** Ticks to wait for PvP BOT to make a requested bot appear before counting the attempt as failed. */
        public int appearTimeoutTicks = 200;
        /** Ticks between disk saves of changed state (also saved on server stop). */
        public int saveIntervalTicks = 600;
        /**
         * After a server start PvP BOT restores its bots one by one with no completion signal; the addon waits
         * this many ticks before it reconciles (re-applies profiles / concludes anything about missing bots).
         */
        public int restoreSettleTicks = 1200;
        /**
         * A spawned inhabitant that stays offline this many ticks (after the settle period) is considered gone
         * for good: the addon releases its upstream leftovers (patrol path etc.). It is NEVER respawned.
         */
        public int goneConfirmTicks = 6000;
    }

    public static final class Connection {
        /**
         * After a server start, PvP BOT restores its bots one by one with no completion signal, and that
         * restore burst can stall the server tick loop; a player who joins mid-stall could be placed into
         * the world before the surrounding terrain/chunks have finished loading and fall through it. This
         * addon holds any player who begins connecting during the first this-many ticks after server start
         * on their client's own native loading screen (via the vanilla configuration-phase task mechanism);
         * their player entity is never created until the hold ends, so there is no teleport-then-freeze.
         * 0 disables the hold entirely. Independent of {@code processing.restoreSettleTicks} (which only
         * gates this addon's own bot reconciliation) and never touches the population/roster engine.
         */
        public int joinHoldTicks = 1800;
    }

    /**
     * Reactive safety valve for server load, instead of a single hand-picked {@code processing.maxLiveBots}
     * guess that may be wrong at any given moment: periodically checks the real, measured tick rate and, while
     * it is degraded, (a) hard-blocks every new spawn -- both freshly-discovered structures and already-pending
     * ones -- and (b) sheds a batch of already-live inhabitants, farthest from the nearest real player first,
     * since those are both the least noticed if removed and the least likely to be who the slowdown is actually
     * about. The batch escalates the longer the server stays degraded (a lone bad check sheds one base batch, a
     * second consecutive one sheds two, and so on, resetting the moment it recovers), so a brief blip is handled
     * gently while a sustained, genuine overload converges quickly instead of nibbling forever. A despawn here
     * is permanent, exactly like a death: nothing here ever brings a bot back on its own. Unblocking new spawns
     * once the server is healthy again cannot by itself respawn anything either -- only a newly discovered
     * structure, or {@link Dormancy} restoring one it put to sleep, ever creates a new bot. See {@link Dormancy}
     * for the separate, always-on, fully reversible mechanism that keeps the population naturally close to the
     * player during ordinary play.
     */
    public static final class TpsThrottle {
        /** Master switch. */
        public boolean enabled = true;
        /** Rolling average ms/tick at or below which the server is healthy: new spawns are unblocked (fully,
         * immediately -- see the class doc on why that is safe) and the shed-escalation level resets to zero.
         * ~52.6ms/tick is ~19 TPS -- one TPS of hysteresis above {@link #degradedMillis} so a server sitting
         * right at the target does not flip between the two states every check. */
        public double healthyMillis = 52.6;
        /** Rolling average ms/tick above which the server is degraded: new spawns are hard-blocked and shedding
         * begins. ~55.6ms/tick is 18 TPS: this starts the moment the server drops below that. */
        public double degradedMillis = 55.6;
        /** Ticks between checks, so one round's effect on the tick rate is fully measured before reacting again.
         * Matches {@code TpsGateway}'s own rolling sample window (100 ticks) on purpose: a shorter interval
         * would react to a reading still diluted by ticks from before the last shed. */
        public int checkIntervalTicks = 100;
        /** Base inhabitants removed on the first consecutive degraded check, farthest from the nearest real
         * player first; doubles-by-addition for each further consecutive bad check (2x, 3x, ...), resetting to
         * this base the moment the server is healthy again. No floor: can reduce the live population to 0 if
         * the problem persists (useful for narrowing down whether the inhabitants are even the cause). */
        public int despawnBatchSize = 3;
    }

    /**
     * Without this, a spawned inhabitant is a real player-like entity (see the addon README) that keeps
     * ticking forever no matter how far the player travels -- PvP BOT gives it none of vanilla's distance-based
     * entity unloading. Left alone, the live population would only ever grow as the player explores. This
     * periodically despawns inhabitants that have stayed far from every real player for a while, and remembers
     * them exactly (name, position, profile) so they are restored unchanged -- not re-rolled -- the next time
     * their structure is near a real player again. This is what lets the population settle to an equilibrium
     * around wherever the player actually is, instead of accumulating across the whole explored world. See
     * {@link TpsThrottle} for the separate, reactive, one-way mechanism that responds to server load instead.
     */
    public static final class Dormancy {
        /** Master switch. */
        public boolean enabled = true;
        /** Blocks from the nearest online real player beyond which a live inhabitant is a dormancy candidate. */
        public double distanceBlocks = 160.0;
        /** A candidate must stay beyond that distance for this many consecutive ticks before it actually goes
         * dormant, so a brief detour or flyby does not despawn it. */
        public int delayTicks = 1200;
        /** Ticks between distance scans. */
        public int scanIntervalTicks = 100;
    }

    public static final class Spawning {
        /** AUTO (BotManager.spawnBot, falling back to /pvpbot spawn), CLASS, or COMMAND. */
        public String backend = "AUTO";
        /** Candidate positions tried per bot before giving up on that bot. */
        public int positionAttemptsPerBot = 80;
        /** Minimum distance in blocks between two bots of the same structure. */
        public double minBotSeparation = 3.0;
        /** Permit standing in water (some structures, e.g. ocean ruins, need it). Lava is never allowed. */
        public boolean allowSubmerged = false;
        /**
         * Optional reserved prefix for generated bot names (up to 8 letters/digits/underscores; names are
         * at most 16 characters). Empty by default: {@link dev.spawnbotswrapper.inhabitants.engine.NameGenerator}
         * already draws from a large two-word pool ({@code DuskRaven}, {@code IronFang7}, ...), so an
         * inhabitant reads as a real player name instead of every single one sharing an obvious system tag.
         * Set this only on an online-mode server where a generated name might otherwise collide with a
         * real Mojang account and take over its UUID/skin; a distinctive prefix (e.g. {@code "Inh"}) rules
         * that out entirely, at the cost of every inhabitant visibly sharing it.
         */
        public String namePrefix = "";
    }

    private static Map<String, RuleOverride> defaultStructureOverrides() {
        Map<String, RuleOverride> m = new LinkedHashMap<>();
        m.put("minecraft:pillager_outpost", new RuleOverride(0.85, 2));
        m.put("minecraft:mansion", new RuleOverride(0.90, 2));
        m.put("minecraft:ancient_city", new RuleOverride(0.50, 1));
        m.put("minecraft:trial_chambers", new RuleOverride(0.60, 1));
        return m;
    }

    private static Map<String, RuleOverride> defaultTagOverrides() {
        Map<String, RuleOverride> m = new LinkedHashMap<>();
        m.put("#minecraft:village", new RuleOverride(0.70, 1));
        return m;
    }
}

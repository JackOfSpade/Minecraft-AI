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

    /** Rolled once per structure: occupied or abandoned, and how many bots if occupied. */
    public static final class Rule {
        /** Probability in [0,1] that a structure is occupied. */
        public double occupiedChance = 0.65;
        /** Inclusive bot count range for an occupied structure. */
        public int minBots = 1;
        public int maxBots = 4;

        public Rule() {
        }

        public Rule(double occupiedChance, int minBots, int maxBots) {
            this.occupiedChance = occupiedChance;
            this.minBots = minBots;
            this.maxBots = maxBots;
        }
    }

    /** A partial rule; null fields inherit. */
    public static final class RuleOverride {
        public Double occupiedChance;
        public Integer minBots;
        public Integer maxBots;

        public RuleOverride() {
        }

        public RuleOverride(Double occupiedChance, Integer minBots, Integer maxBots) {
            this.occupiedChance = occupiedChance;
            this.minBots = minBots;
            this.maxBots = maxBots;
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
        public int maxStructuresPerTick = 4;
        /** How many bots are requested from PvP BOT per server tick (spawning a fake player is not free). */
        public int maxBotsPerTick = 1;
        /** Minimum ticks between two bot spawn requests. */
        public int spawnIntervalTicks = 4;
        /** Ticks to wait before the first population attempt, so neighbouring chunks of the structure can load. */
        public int initialDelayTicks = 40;
        /** Ticks between retries while a structure's chunks are not loaded/valid yet. */
        public int retryIntervalTicks = 100;
        /** Population attempts (with loaded chunks) before an occupied structure is given up on permanently. */
        public int maxAttemptsPerStructure = 12;
        /** Never have more than this many living inhabitants at once; further population waits (no re-roll). 0 = unlimited. */
        public int maxLiveBots = 256;
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
         * Reserved prefix for generated bot names (up to 8 letters/digits/underscores; names are at most 16
         * characters). A distinctive prefix keeps addon bots from colliding with real player accounts: on an
         * online-mode server a bot named like a real Mojang account would take over that account's UUID/skin.
         */
        public String namePrefix = "Inh";
    }

    private static Map<String, RuleOverride> defaultStructureOverrides() {
        Map<String, RuleOverride> m = new LinkedHashMap<>();
        m.put("minecraft:pillager_outpost", new RuleOverride(0.85, 2, 6));
        m.put("minecraft:mansion", new RuleOverride(0.90, 2, 6));
        m.put("minecraft:ancient_city", new RuleOverride(0.50, 1, 3));
        m.put("minecraft:trial_chambers", new RuleOverride(0.60, 1, 4));
        return m;
    }

    private static Map<String, RuleOverride> defaultTagOverrides() {
        Map<String, RuleOverride> m = new LinkedHashMap<>();
        m.put("#minecraft:village", new RuleOverride(0.70, 1, 5));
        return m;
    }
}

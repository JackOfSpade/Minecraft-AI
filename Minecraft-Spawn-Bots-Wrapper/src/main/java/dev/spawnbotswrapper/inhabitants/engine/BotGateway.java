package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities;
import dev.spawnbotswrapper.inhabitants.store.BotSnapshot;

import java.util.List;
import java.util.UUID;

/**
 * Everything the population engine needs from "the bot side": asking PvP BOT for a bot, watching it
 * appear, dressing it, and cleaning up. The real implementation (Minecraft glue over the single
 * {@code PvpBotAdapter}) lives outside the engine; the engine and its tests only ever see this port.
 * <p>
 * All methods are called on the server thread and must not block.
 */
public interface BotGateway {

    /** False while the PvP BOT integration is unusable; the engine then neither rolls nor spawns anything. */
    boolean available();

    /** Human-readable reason when {@link #available()} is false. */
    String unavailableReason();

    /** Current global PvP BOT capability flags (read-only view; defaults if unreadable). */
    GlobalCapabilities capabilities();

    /**
     * True when {@code name} can be used for a new bot: no online player with that name (compared
     * case-insensitively, as vanilla does) and not listed by PvP BOT.
     */
    boolean nameAvailable(String name);

    /** Asks PvP BOT to create the bot. Never blocks and never assumes success; see {@link #poll}. */
    SpawnHandle requestSpawn(SpawnRequest request);

    /** Progress of a previously requested spawn. */
    SpawnPoll poll(SpawnHandle handle);

    /**
     * Dresses and configures a bot whose entity exists: loadout, vitals/attributes, behaviour (path).
     * Best effort: partial failures are reported in the result rather than thrown.
     */
    ApplyResult applyProfile(String botName, BotProfile profile);

    /** True when an online player entity with this name exists. */
    boolean isOnline(String botName);

    /** True when the bot is online AND PvP BOT lists it as one of its bots. */
    boolean isManaged(String botName);

    /**
     * Distance in blocks from the bot's current position to the nearest online real player (this addon's own
     * inhabitants and any other bot do not count), or -1 when the bot is not online or no real player is online.
     */
    double distanceToNearestPlayer(String botName);

    /**
     * Where every real player (not a PvP BOT or addon bot) is right now: the input of the nearest-first allocation. Empty
     * when nobody is online or it cannot be told; the allocation then stays idle and population is first come, first served.
     */
    default List<PlayerPos> realPlayers() {
        return List.of();
    }

    /** How far from a real player (blocks) a structure can host bots at all: the server simulation distance plus two chunks. */
    default double relevanceRadiusBlocks() {
        return 192.0;
    }

    /**
     * True when the bot is engaged with a player (aggro engagement, or PvP BOT has a real player as its target) -- or when
     * that cannot be told: an engaged bot is never removed until it disengages.
     */
    default boolean isEngaged(String botName) {
        return false;
    }

    /**
     * True when a real player sees this bot right now: the bot's eye or body lies inside the player's view cone, nothing
     * blocks the view (the same eye-to-eye and eye-to-body rays as Perception) and it is within vanilla sight range. An
     * invisible bot is never seen. Cheap when no player is near.
     */
    default boolean seenByHuman(String botName) {
        return false;
    }

    /**
     * True when the bot is inside the area a real player keeps loaded: its chunk is sent to a player (view distance) or
     * lies within the simulation distance of one. A seen bot in such a place is never put to sleep (its chunk is loaded
     * and ticking), whatever the allocation says. The default estimates it from positions.
     */
    default boolean loadedByHuman(String botName) {
        PlayerPos where = position(botName);
        if (where == null) {
            return true;
        }
        double radius = relevanceRadiusBlocks();
        for (PlayerPos p : realPlayers()) {
            if (p.dimension().equals(where.dimension())
                    && Math.max(Math.abs(p.x() - where.x()), Math.abs(p.z() - where.z())) <= radius) {
                return true;
            }
        }
        return false;
    }

    /** Where a live bot stands (dimension, position, facing), or null when it is not online. */
    default PlayerPos position(String botName) {
        return null;
    }

    /**
     * Releases per-bot upstream state the addon created (path, follower, ...) for a bot that is gone.
     * Idempotent; safe for names that never existed.
     */
    void forget(String botName);

    /**
     * Takes an addon-owned bot out of the world WITHOUT killing it: it drops nothing (inventory, armor, offhand, ender chest
     * and experience are emptied first), no death happens (no death message, statistics, advancements or XP orbs) and PvP
     * BOT forgets it like after its own removal. Returns whether a removal was issued. This is the only way the addon
     * ends a bot it decided to remove; a real death is never caused by it.
     */
    boolean remove(String botName);

    /**
     * Re-asserts per-bot upstream state that does not survive a restart (path following, the path itself)
     * for a bot that is back online, and puts back what a restart resets on a fake player. Returns true when
     * something had to be re-applied.
     * <ul>
     *   <li>A bot the addon already dressed (marked) keeps its inventory from the player's own saved data; only its
     *       health, hunger and missing effects are restored from {@code snapshot} (HeroBot heals a fake player to full
     *       health when it is created).</li>
     *   <li>A bot that is not marked yet is restored from {@code snapshot} completely when there is one (so what it
     *       used up stays used up) and dressed from {@code profile} only when it never was snapshotted.</li>
     * </ul>
     */
    boolean restore(String botName, BotProfile profile, BotSnapshot snapshot);

    /** The same without a snapshot: what a bot of a record that was never snapshotted gets. */
    boolean restore(String botName, BotProfile profile);

    /**
     * The live state of an online inhabitant as a {@link BotSnapshot} (inventory, selected slot, health, hunger,
     * effects, experience), or null when it is not online, not a bot, dead, or cannot be read. Never throws.
     */
    default BotSnapshot snapshot(String botName) {
        return null;
    }

    /**
     * Brings a bot back from dormancy exactly as it was: its whole snapshot is written slot by slot and then its
     * vitals (nothing is dressed from the profile, so arrows, food, potions, gear condition and health stay what they
     * were), then its behaviour (path) is assigned again and the bot is marked as dressed.
     */
    default ApplyResult wake(String botName, BotProfile profile, BotSnapshot snapshot) {
        return applyProfile(botName, profile);
    }

    /**
     * Makes an online inhabitant an ordinary survival player again: survival game mode without creative-style
     * abilities, and no attribute modifier of this addon. Includes what was already fixed since the last call (a
     * fresh spawn is checked the moment it appears). Returns what was wrong; {@link StateFixes#isEmpty()} when nothing
     * was. Cheap and idempotent; never throws.
     */
    default StateFixes enforceVanilla(String botName) {
        return StateFixes.NONE;
    }

    /**
     * Removes every ender pearl from an online inhabitant's inventory and returns how many were removed (0 when
     * none, when it is not online or not a bot). Cheap and idempotent; the engine calls it after a restore and then
     * every few seconds so a pearl picked up later goes too. PvP BOT's cobweb escape loop re-selects the pearl
     * slot every tick while the bot stands in a web, which cancels crossbow charges and attacks.
     */
    int stripEnderPearls(String botName);

    /**
     * Removes every enchantment the config disables (profiles.disabledEnchantments, Piercing by default) from an online
     * inhabitant's items, keeping the items and their other enchantments, and returns one description per removal, for
     * example {@code minecraft:piercing (crossbow)} (empty when nothing was removed, when it is not online or not a bot).
     * Includes what its dressing already removed since the last call. Cheap and idempotent; the engine calls it beside
     * {@link #stripEnderPearls} so an old crossbow that still carries Piercing loses just that enchantment.
     */
    List<String> stripDisabledEnchantments(String botName);

    /**
     * The one-time sanitize pass for an inhabitant dressed before the wrapper marked the items it issues: removes every ender
     * pearl and disabled enchantment from ALL its stacks, exactly what the periodic sweep used to do. After it (the engine
     * records the bot as migrated) {@link #stripEnderPearls} and {@link #stripDisabledEnchantments} judge only stacks the wrapper
     * issued, so what a bot picks up in the world is never touched. Returns null when the bot could not be swept now (it is not
     * online, or the pass failed) and the engine must try again; never throws.
     */
    default ItemSweep migrateLegacyItems(String botName) {
        return ItemSweep.NONE;
    }

    /** What a sanitize pass removed: ender pearls (items) and one description per disabled enchantment. */
    record ItemSweep(int pearls, List<String> enchantments) {
        public static final ItemSweep NONE = new ItemSweep(0, List.of());
    }

    // ------------------------------------------------------------------ value types

    /** A position in a dimension ({@code namespace:path}, the form {@code StructureKey} uses) and where it faces. */
    record PlayerPos(String dimension, double x, double y, double z, float yaw) {
        public PlayerPos(String dimension, double x, double y, double z) {
            this(dimension, x, y, z, 0.0f);
        }
    }

    record SpawnRequest(String dimensionId, String name, double x, double y, double z, float yaw) {
    }

    record SpawnHandle(long id, String name) {
    }

    /** Result of {@link #poll}. */
    sealed interface SpawnPoll permits SpawnPoll.Pending, SpawnPoll.Ready, SpawnPoll.Failed {
        /** Not there yet; keep polling until the engine's own deadline. */
        record Pending() implements SpawnPoll {
        }

        /** The entity exists. {@code uuid} may be null if it could not be read. */
        record Ready(UUID uuid) implements SpawnPoll {
        }

        /** Definitively refused or impossible. */
        record Failed(String reason) implements SpawnPoll {
        }
    }


    /**
     * What {@link #enforceVanilla} found wrong with an inhabitant.
     *
     * @param previousGameMode the game mode it was in when it was not SURVIVAL, else null
     * @param abilities        creative-style abilities it had (instabuild, mayfly, invulnerable, flying)
     * @param modifiers        addon-added attribute modifiers that were removed, one description each
     */
    record StateFixes(String previousGameMode, List<String> abilities, List<String> modifiers) {
        public static final StateFixes NONE = new StateFixes(null, List.of(), List.of());

        public StateFixes {
            abilities = abilities == null ? List.of() : List.copyOf(abilities);
            modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
        }

        public boolean isEmpty() {
            return previousGameMode == null && abilities.isEmpty() && modifiers.isEmpty();
        }

        /** Both findings together (the earlier game mode wins: it is the one that was really found). */
        public StateFixes and(StateFixes other) {
            if (other == null || other.isEmpty()) {
                return this;
            }
            if (isEmpty()) {
                return other;
            }
            java.util.ArrayList<String> a = new java.util.ArrayList<>(abilities);
            other.abilities.stream().filter(x -> !a.contains(x)).forEach(a::add);
            java.util.ArrayList<String> m = new java.util.ArrayList<>(modifiers);
            m.addAll(other.modifiers);
            return new StateFixes(previousGameMode != null ? previousGameMode : other.previousGameMode, a, m);
        }
    }

    /** What {@link #applyProfile} managed to do. */
    record ApplyResult(boolean loadoutApplied, boolean vitalsApplied, boolean behaviorApplied, List<String> warnings) {
        public ApplyResult {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public boolean allApplied() {
            return loadoutApplied && vitalsApplied && behaviorApplied;
        }
    }
}

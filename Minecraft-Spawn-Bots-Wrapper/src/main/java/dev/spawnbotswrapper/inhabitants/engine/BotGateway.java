package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities;

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
     * Releases per-bot upstream state the addon created (path, follower, ...) for a bot that is gone.
     * Idempotent; safe for names that never existed.
     */
    void forget(String botName);

    /** Admin: remove an addon-owned bot through PvP BOT. Returns whether a removal was issued. */
    boolean remove(String botName);

    /**
     * Re-asserts per-bot upstream state that does not survive a restart (path following, the path itself)
     * for a bot that is back online. Returns true when something had to be re-applied.
     */
    boolean restore(String botName, BotProfile profile);

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

    // ------------------------------------------------------------------ value types

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

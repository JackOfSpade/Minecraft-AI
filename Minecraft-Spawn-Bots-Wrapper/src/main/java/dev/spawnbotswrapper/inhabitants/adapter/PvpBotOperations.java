package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.combat.TargetControl;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * THE ONLY seam through which the addon talks to PvP BOT. Implemented by {@link PvpBotAdapter}, which is
 * the only class in the whole addon allowed to reference PvP BOT (or HeroBot) classes, methods or command
 * names. If PvP BOT changes, only that implementation should need updating.
 * <p>
 * Contract for every method: server thread only, never blocks, never throws for upstream failures
 * (failures are reported through return values / {@link Status}).
 */
public interface PvpBotOperations {

    enum Availability {
        /** Every required upstream member was found; full functionality. */
        AVAILABLE,
        /** Usable, but a preferred/optional member is missing so a fallback is in use (see {@link Status#warnings()}). */
        DEGRADED,
        /** PvP BOT is missing or incompatible; the addon must not roll or spawn anything. */
        UNAVAILABLE
    }

    /**
     * @param pvpBotVersion  Fabric-metadata version of PvP BOT, or "not installed"
     * @param heroBotVersion Fabric-metadata version of HeroBot, or "not installed"
     * @param addonVersion   version of this addon
     * @param spawnTier      which spawn path is in use: "CLASS(pos)", "CLASS", "COMMAND" or "NONE"
     * @param summary        one line for the startup log / {@code /inhabitants info}
     * @param details        what was probed and found (multi-line diagnostics)
     * @param warnings       upstream settings/conditions that will surprise an operator (e.g. botsRelogs=false)
     */
    record Status(Availability availability, String pvpBotVersion, String heroBotVersion, String addonVersion,
                  String spawnTier, String summary, List<String> details, List<String> warnings) {
        public Status {
            details = details == null ? List.of() : List.copyOf(details);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public boolean usable() {
            return availability != Availability.UNAVAILABLE;
        }
    }

    /** Handle for one spawn request. */
    record SpawnTicket(long id, String name, long requestedAtTick) {
    }

    /** Progress of a spawn request. */
    sealed interface SpawnState permits SpawnState.Pending, SpawnState.Ready, SpawnState.Failed {
        record Pending() implements SpawnState {
        }

        record Ready(UUID uuid) implements SpawnState {
        }

        record Failed(String reason) implements SpawnState {
        }
    }

    // ---------------------------------------------------------------- availability / diagnostics

    /** Latest probe result without re-probing. */
    Status status();

    default boolean available() {
        return status().usable();
    }

    /**
     * (Re-)runs all probes against the running server and logs one clear report (versions, API
     * compatibility result, spawn tier, warnings). Call after PvP BOT has finished initialising
     * (its SERVER_STARTED work) - i.e. at least one tick after SERVER_STARTED.
     */
    Status probe(MinecraftServer server);

    /** Read-only view of the global PvP BOT settings that gate behaviours; defaults when unreadable. */
    GlobalCapabilities readCapabilities();

    /** Names of PvP BOT's settings fields (metadata only), so the setting catalog can be audited for drift. */
    Set<String> discoverUpstreamSettingNames();

    /**
     * Tells the adapter which PvP BOT settings the addon wants held at a value (null or an empty set: none). The adapter
     * applies them to PvP BOT's settings object when they differ, at the probe and again whenever PvP BOT replaces that
     * object by loading its per-world settings (its reload command), so calling this once per tick with the current
     * configuration is cheap and keeps them enforced. Writes PvP BOT's per-world settings file when something changed
     * and logs ONE line naming what changed; a missing upstream name is one warning, never an exception.
     */
    default void manageSettings(ManagedSettings wanted) {
    }

    /**
     * Tells the adapter whether the wrapper's aggro hunter is switched on. While it is, PvP BOT's own auto-target being OFF
     * is the intended state (the aggro hunter acquires targets), so the status report no longer warns about it.
     */
    default void aggroHunterEnabled(boolean enabled) {
    }

    /**
     * How far (blocks) PvP BOT looks for targets right now, as its settings hold it; empty when unreadable. Read-only.
     */
    default java.util.OptionalDouble targetRadius() {
        return java.util.OptionalDouble.empty();
    }

    /**
     * What PvP BOT currently intends for this bot: its target (null when none) and combat mode/bow state where readable.
     * Empty when the bot is not listed or the state cannot be read. Read-only.
     */
    default Optional<CombatView> combatView(String botName) {
        return Optional.empty();
    }

    /**
     * PvP BOT's per-bot combat intent.
     *
     * @param target      the entity it targets, or null
     * @param mode        its weapon mode as text ("RANGED", "MELEE", ...), or null when unreadable
     * @param drawingBow  whether it believes it is drawing a bow or crossbow, or null when unreadable
     * @param bowDrawTicks ticks of the draw it counts, or null when unreadable
     */
    record CombatView(Entity target, String mode, Boolean drawingBow, Integer bowDrawTicks) {
    }

    /**
     * PvP BOT's melee range and move speed (its own settings: blocks, and the speed factor its combat passes to its
     * move-toward). Empty when unreadable. Read-only.
     */
    default Optional<MeleeTuning> meleeTuning() {
        return Optional.empty();
    }

    /**
     * PvP BOT's melee reach and walking speed factor.
     *
     * @param meleeRange PvP BOT's melee range, blocks
     * @param moveSpeed  the speed factor its combat moves with
     */
    record MeleeTuning(double meleeRange, double moveSpeed) {
    }

    /**
     * Whether PvP BOT flags this bot as retreating (low health with food to eat: it walks away from its target), or
     * null when that cannot be read. Read-only.
     */
    default Boolean retreating(String botName) {
        return null;
    }

    // ---------------------------------------------------------------- spawning

    /**
     * True when {@code name} is free: no online player with it (case-insensitive) and PvP BOT does not
     * list it (case-insensitive).
     */
    boolean nameAvailable(MinecraftServer server, String name);

    /**
     * Asks PvP BOT to spawn a bot at the exact position in {@code world}'s dimension. Returns
     * immediately. NEVER trusts upstream's boolean: the outcome is only known through {@link #pollSpawn}.
     */
    SpawnTicket requestSpawn(MinecraftServer server, ServerLevel world, String name,
                             double x, double y, double z, float yaw);

    /** Progress: Ready once the player entity exists AND PvP BOT lists it (re-adopting an orphan if needed). */
    SpawnState pollSpawn(MinecraftServer server, SpawnTicket ticket);

    /** The live player entity of a bot, if online and actually a bot (not a real player). */
    Optional<ServerPlayer> findBotEntity(MinecraftServer server, String name);

    /** True when PvP BOT currently lists this bot. */
    boolean isManaged(String name);

    /** True when the entity is a HeroBot bot player (class-name check; no compile-time dependency). */
    boolean isBotEntity(ServerPlayer player);

    /** Removes an addon-owned bot through PvP BOT. Refuses names that are not online bots. */
    boolean removeBot(MinecraftServer server, String name);

    // ---------------------------------------------------------------- per-bot behaviour (PvP BOT paths)

    /**
     * Creates a PvP BOT path for this bot from {@code behavior.waypoints()} (using the stance and walk type;
     * the path always has attack=true, every inhabitant fights) and starts the bot following it. Returns false when the behaviour has no path,
     * the waypoints are invalid, or upstream refused. Safe against upstream's known path traps (never a
     * single-point ping-pong loop; path fully built before following starts).
     */
    boolean assignPatrol(MinecraftServer server, String botName, BotProfile.Behavior behavior);

    /** Stops following, deletes the bot's path and clears stale per-bot navigation state. Idempotent. */
    void clearPatrol(String botName);

    /** True when the bot currently follows a path created by this addon. */
    boolean isPatrolling(String botName);

    // ---------------------------------------------------------------- targeting (aggro hunter)

    /**
     * PvP BOT's target control for the aggro hunter: read its targeting settings, read a bot's current and forced
     * target, force or release a target. Never null; {@link TargetControl#available()} is false when PvP BOT is
     * missing or lacks a member it needs.
     */
    default TargetControl targetControl() {
        return TargetControl.NONE;
    }
}

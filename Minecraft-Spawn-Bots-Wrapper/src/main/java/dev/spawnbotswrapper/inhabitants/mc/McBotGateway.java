package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.engine.BotGateway;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities;
import dev.spawnbotswrapper.inhabitants.store.BotSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link BotGateway} on top of the PvP BOT adapter, the profile applier and the running server: the engine's
 * only view of "the bot side".
 * <ul>
 *   <li>Availability, capabilities, name checks, spawning, removal and patrols are delegated to the adapter
 *       (the one class that knows about PvP BOT); this class never references PvP BOT itself.</li>
 *   <li>Dressing a bot is the applier plus a patrol assignment, and is recorded with a marker on the entity so
 *       a bot that comes back after a restart can be recognised as already dressed.</li>
 *   <li>Upstream state that does not survive a restart (path following) is re-asserted by {@link #restore}.</li>
 * </ul>
 * A spawn that cannot even be asked for (unknown dimension, adapter failure) still returns a handle, whose
 * {@link #poll} answers Failed, so the engine has exactly one code path for "did it work". Server thread only.
 */
public final class McBotGateway implements BotGateway {
    private static final Logger LOG = LoggerFactory.getLogger(McBotGateway.class);
    /** Tickets older than this are forgotten; the engine gives up on a spawn long before. */
    private static final int TICKET_MAX_AGE_TICKS = 6000;
    private static final int MAX_REJECTIONS = 1024;

    private final ServerAccess access;
    private final PvpBotOperations adapter;
    private final ProfileApplication applier;
    private final Supplier<InhabitantsConfig> config;
    private final Map<Long, PvpBotOperations.SpawnTicket> tickets = new HashMap<>();
    private final Map<Long, String> rejected = new HashMap<>();
    private long localIds;
    /**
     * What a dressing already took out of a bot (ender pearls, disabled enchantments), by lower-case name, until the
     * next sweep of that bot reports it: the dressing removes it first, so without this the roster's sweep would find
     * nothing and the first removal would never be logged.
     */
    private final Map<String, Integer> pendingPearls = new HashMap<>();
    private final Map<String, List<String>> pendingEnchantments = new HashMap<>();
    /**
     * What the checks of a bot's state found wrong (game mode, abilities, addon attribute modifiers) before the roster's
     * next sweep of that bot reported it, by lower-case name: a fresh spawn is put right the moment it appears.
     */
    private final Map<String, StateFixes> pendingFixes = new HashMap<>();

    public McBotGateway(ServerAccess access, PvpBotOperations adapter, ProfileApplication applier,
                        Supplier<InhabitantsConfig> config) {
        this.access = access;
        this.adapter = adapter;
        this.applier = applier;
        this.config = config;
    }

    @Override
    public boolean available() {
        return adapter.status().usable();
    }

    @Override
    public String unavailableReason() {
        PvpBotOperations.Status status = adapter.status();
        return status.usable() ? "" : status.summary();
    }

    @Override
    public GlobalCapabilities capabilities() {
        try {
            GlobalCapabilities read = adapter.readCapabilities();
            return read != null ? read : GlobalCapabilities.upstreamDefaults();
        } catch (RuntimeException e) {
            return GlobalCapabilities.upstreamDefaults();
        }
    }

    @Override
    public boolean nameAvailable(String name) {
        try {
            return adapter.nameAvailable(access.server(), name);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public SpawnHandle requestSpawn(SpawnRequest request) {
        ServerLevel world = access.world(request.dimensionId());
        if (world == null) {
            return rejectedHandle(request.name(), "dimension " + request.dimensionId() + " is not loaded");
        }
        try {
            PvpBotOperations.SpawnTicket ticket = adapter.requestSpawn(access.server(), world, request.name(),
                    request.x(), request.y(), request.z(), request.yaw());
            if (ticket == null) {
                return rejectedHandle(request.name(), "PvP BOT adapter returned no ticket");
            }
            forgetStaleTickets();
            tickets.put(ticket.id(), ticket);
            return new SpawnHandle(ticket.id(), ticket.name());
        } catch (RuntimeException e) {
            return rejectedHandle(request.name(), "PvP BOT adapter failed: " + e);
        }
    }

    @Override
    public SpawnPoll poll(SpawnHandle handle) {
        String refusal = rejected.remove(handle.id());
        if (refusal != null) {
            return new SpawnPoll.Failed(refusal);
        }
        // A handle from before a restart has no ticket; rebuild one so the adapter can still be asked.
        PvpBotOperations.SpawnTicket ticket = tickets.getOrDefault(handle.id(),
                new PvpBotOperations.SpawnTicket(handle.id(), handle.name(), access.ticks()));
        PvpBotOperations.SpawnState state;
        try {
            state = adapter.pollSpawn(access.server(), ticket);
        } catch (RuntimeException e) {
            tickets.remove(handle.id());
            return new SpawnPoll.Failed("PvP BOT adapter failed: " + e);
        }
        if (state instanceof PvpBotOperations.SpawnState.Ready ready) {
            tickets.remove(handle.id());
            // HeroBot creates a fake player in CREATIVE unless told otherwise, and PvP BOT's own switch to survival can run
            // before the player exists: make it an ordinary survival player before anything else looks at it.
            enforce(handle.name());
            return new SpawnPoll.Ready(ready.uuid());
        }
        if (state instanceof PvpBotOperations.SpawnState.Failed failed) {
            tickets.remove(handle.id());
            return new SpawnPoll.Failed(failed.reason());
        }
        if (state == null) {
            tickets.remove(handle.id());
            return new SpawnPoll.Failed("PvP BOT adapter returned no spawn state");
        }
        return new SpawnPoll.Pending();
    }

    @Override
    public ApplyResult applyProfile(String botName, BotProfile profile) {
        Optional<ServerPlayer> entity = findBot(botName);
        if (entity.isEmpty()) {
            return new ApplyResult(false, false, false, List.of("bot " + botName + " is not online, or is not a bot"));
        }
        ServerPlayer bot = entity.get();
        List<String> warnings = new ArrayList<>();
        // Only a bot the addon has not dressed yet is wiped first; a second application overwrites in place.
        boolean fresh = !applier.isMarked(bot);
        ProfileApplication.Result applied = applier.apply(bot, profile, fresh);
        stash(botName, applied);
        warnings.addAll(applied.warnings());
        if (applied.loadoutApplied() && applied.vitalsApplied()) {
            applier.mark(bot);
        }
        boolean behavior = applyBehavior(botName, profile.behavior(), warnings);
        return new ApplyResult(applied.loadoutApplied(), applied.vitalsApplied(), behavior, warnings);
    }

    @Override
    public ApplyResult wake(String botName, BotProfile profile, BotSnapshot snapshot) {
        Optional<ServerPlayer> entity = findBot(botName);
        if (entity.isEmpty()) {
            return new ApplyResult(false, false, false, List.of("bot " + botName + " is not online, or is not a bot"));
        }
        ServerPlayer bot = entity.get();
        List<String> warnings = new ArrayList<>();
        // Nothing is dressed from the profile: the bot gets back exactly what it had, slot by slot, and how it was doing.
        ProfileApplication.Result restored = restoreOrDress(botName, bot, profile, snapshot);
        stash(botName, restored);
        warnings.addAll(restored.warnings());
        if (restored.loadoutApplied() && restored.vitalsApplied()) {
            applier.mark(bot);
        }
        stashFixes(botName, applier.enforceVanilla(bot));
        boolean behavior = applyBehavior(botName, profile.behavior(), warnings);
        return new ApplyResult(restored.loadoutApplied(), restored.vitalsApplied(), behavior, warnings);
    }

    /**
     * Puts the saved state back; when none of it can be read (the result says nothing was restored) the bot is dressed from
     * its profile instead, and the reason is logged, so a bot is never left in whatever state the spawn gave it and never
     * reported as restored when it was not.
     */
    private ProfileApplication.Result restoreOrDress(String botName, ServerPlayer bot, BotProfile profile,
                                                     BotSnapshot snapshot) {
        ProfileApplication.Result restored = applier.restore(bot, snapshot, true);
        if (restored.loadoutApplied()) {
            return restored;
        }
        LOG.warn("The saved state of inhabitant {} could not be restored ({}); dressing it from its profile instead", botName,
                restored.warnings());
        ProfileApplication.Result dressed = applier.apply(bot, profile, true);
        List<String> warnings = new ArrayList<>(restored.warnings());
        warnings.addAll(dressed.warnings());
        return new ProfileApplication.Result(dressed.loadoutApplied(), dressed.vitalsApplied(), warnings,
                dressed.pearlsRemoved(), dressed.enchantmentsRemoved());
    }

    @Override
    public BotSnapshot snapshot(String botName) {
        Optional<ServerPlayer> entity = findBot(botName);
        if (entity.isEmpty()) {
            return null;
        }
        List<String> warnings = new ArrayList<>();
        BotSnapshot snapshot = applier.capture(entity.get(), warnings);
        if (!warnings.isEmpty()) {
            LOG.warn("The state of inhabitant {} was saved with problems: {}", botName, warnings);
        }
        return snapshot;
    }

    @Override
    public StateFixes enforceVanilla(String botName) {
        Optional<ServerPlayer> entity = findBot(botName);
        StateFixes pending = pendingFixes.remove(key(botName));
        StateFixes found = entity.isEmpty() ? StateFixes.NONE : applier.enforceVanilla(entity.get());
        return pending == null ? found : pending.and(found);
    }

    @Override
    public boolean isOnline(String botName) {
        return access.isPlayerOnline(botName);
    }

    @Override
    public boolean isManaged(String botName) {
        try {
            return isOnline(botName) && adapter.isManaged(botName);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public double distanceToNearestPlayer(String botName) {
        try {
            Optional<ServerPlayer> bot = findBot(botName);
            if (bot.isEmpty()) {
                return -1;
            }
            Vec3 botPos = bot.get().position();
            double best = -1;
            for (ServerPlayer p : access.server().getPlayerList().getPlayers()) {
                if (adapter.isBotEntity(p)) {
                    continue;
                }
                double d = p.position().distanceTo(botPos);
                if (best < 0 || d < best) {
                    best = d;
                }
            }
            return best;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    @Override
    public void forget(String botName) {
        pendingPearls.remove(key(botName));
        pendingFixes.remove(key(botName));
        pendingEnchantments.remove(key(botName));
        try {
            adapter.clearPatrol(botName);
        } catch (RuntimeException e) {
            // Releasing leftovers is best effort; there is nothing more to do for a bot that is gone.
        }
    }

    @Override
    public boolean remove(String botName) {
        try {
            return adapter.removeBot(access.server(), botName);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean restore(String botName, BotProfile profile) {
        return restore(botName, profile, null);
    }

    @Override
    public boolean restore(String botName, BotProfile profile, BotSnapshot snapshot) {
        if (!isManaged(botName)) {
            return false;
        }
        boolean changed = false;
        BotProfile.Behavior behavior = profile.behavior();
        // Upstream keeps paths but not who follows them, so a restart leaves the path with no follower.
        if (behavior.usesPath() && reassignPath(botName, behavior)) {
            changed = true;
        }
        Optional<ServerPlayer> entity = findBot(botName);
        if (entity.isEmpty()) {
            return changed;
        }
        ServerPlayer bot = entity.get();
        // The entity is new after a restart: survival like any player, and none of the stats older versions gave it.
        stashFixes(botName, applier.enforceVanilla(bot));
        if (applier.isMarked(bot)) {
            // Its inventory came back with the player's own saved data; only what the fake-player spawn resets (it heals
            // the bot to full health) is put back from the last snapshot, so a wounded bot is still wounded.
            if (snapshot != null) {
                ProfileApplication.Result applied = applier.restore(bot, snapshot, false);
                stash(botName, applied);
                changed = true;
            }
        } else if (config.get().profiles.reapplyOnRestore) {
            ProfileApplication.Result applied = snapshot != null
                    ? restoreOrDress(botName, bot, profile, snapshot)
                    : applier.apply(bot, profile, true);
            stash(botName, applied);
            if (applied.loadoutApplied() && applied.vitalsApplied()) {
                applier.mark(bot);
            }
            changed = true;
        }
        return changed;
    }

    @Override
    public int stripEnderPearls(String botName) {
        Optional<ServerPlayer> bot = findBot(botName);
        if (bot.isEmpty()) {
            return 0;
        }
        Integer dressed = pendingPearls.remove(key(botName));
        return applier.stripEnderPearls(bot.get()) + (dressed == null ? 0 : dressed);
    }

    @Override
    public List<String> stripDisabledEnchantments(String botName) {
        Optional<ServerPlayer> bot = findBot(botName);
        if (bot.isEmpty()) {
            return List.of();
        }
        List<String> removed = new ArrayList<>(pendingEnchantments.getOrDefault(key(botName), List.of()));
        pendingEnchantments.remove(key(botName));
        removed.addAll(applier.stripDisabledEnchantments(bot.get()));
        return removed;
    }

    private void stash(String botName, ProfileApplication.Result applied) {
        if (applied.pearlsRemoved() > 0) {
            pendingPearls.merge(key(botName), applied.pearlsRemoved(), Integer::sum);
        }
        if (!applied.enchantmentsRemoved().isEmpty()) {
            pendingEnchantments.computeIfAbsent(key(botName), k -> new ArrayList<>()).addAll(applied.enchantmentsRemoved());
        }
    }

    /** Puts a bot right at once and remembers what was wrong until the next sweep reports it. */
    private void enforce(String botName) {
        try {
            stashFixes(botName, findBot(botName).map(applier::enforceVanilla).orElse(StateFixes.NONE));
        } catch (RuntimeException e) {
            LOG.warn("Could not check the game mode of {}: {}", botName, e.toString());
        }
    }

    private void stashFixes(String botName, StateFixes fixes) {
        if (fixes != null && !fixes.isEmpty()) {
            pendingFixes.merge(key(botName), fixes, StateFixes::and);
        }
    }

    private static String key(String botName) {
        return botName == null ? "" : botName.toLowerCase(Locale.ROOT);
    }

    /** Gives a bot back its path if it no longer follows one. True only when a path was actually assigned. */
    private boolean reassignPath(String botName, BotProfile.Behavior behavior) {
        try {
            return !adapter.isPatrolling(botName) && adapter.assignPatrol(access.server(), botName, behavior);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Optional<ServerPlayer> findBot(String botName) {
        try {
            return adapter.findBotEntity(access.server(), botName);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** A bot that stands still has no path, so there is nothing to assign and nothing that can fail. */
    private boolean applyBehavior(String botName, BotProfile.Behavior behavior, List<String> warnings) {
        if (!behavior.usesPath()) {
            return true;
        }
        try {
            if (adapter.assignPatrol(access.server(), botName, behavior)) {
                return true;
            }
            warnings.add("the " + behavior.stance() + " path could not be assigned to " + botName);
        } catch (RuntimeException e) {
            warnings.add("assigning the path to " + botName + " failed: " + e);
        }
        return false;
    }

    private SpawnHandle rejectedHandle(String name, String reason) {
        if (rejected.size() >= MAX_REJECTIONS) {
            rejected.clear();
        }
        // Local ids live at the far negative end so they can never collide with the adapter's own.
        long id = Long.MIN_VALUE + (++localIds);
        rejected.put(id, reason);
        return new SpawnHandle(id, name);
    }

    private void forgetStaleTickets() {
        long now = access.ticks();
        tickets.values().removeIf(t -> now - t.requestedAtTick() > TICKET_MAX_AGE_TICKS);
    }
}

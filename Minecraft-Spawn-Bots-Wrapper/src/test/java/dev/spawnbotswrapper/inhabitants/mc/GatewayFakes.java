package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Scriptable stand-ins for the collaborators of {@link McBotGateway}; they run inside {@link McSandbox}. */
final class GatewayFakes {
    private GatewayFakes() {
    }

    /** A programmable PvP BOT adapter that records what it was asked. */
    static class Adapter implements PvpBotOperations {
        Status status = new Status(Availability.AVAILABLE, "0.0.15", "1.4.3", "test", "CLASS(pos)", "all good", List.of(), List.of());
        GlobalCapabilities capabilities = GlobalCapabilities.upstreamDefaults();
        boolean capabilitiesNull;
        RuntimeException capabilitiesFailure;

        boolean nameFree = true;
        RuntimeException nameFailure;

        SpawnTicket ticket = new SpawnTicket(7, "Inh_Bot", 100);
        boolean nullTicket;
        RuntimeException spawnFailure;
        SpawnState state = new SpawnState.Pending();
        boolean nullState;
        RuntimeException pollFailure;
        ServerWorld lastWorld;
        String lastName;
        double[] lastPosition;
        float lastYaw;
        final List<SpawnTicket> polled = new ArrayList<>();

        Optional<ServerPlayerEntity> entity = Optional.empty();
        RuntimeException findFailure;
        boolean managed;
        boolean removeResult = true;
        RuntimeException removeFailure;

        boolean assignResult = true;
        RuntimeException assignFailure;
        final List<BotProfile.Behavior> assigned = new ArrayList<>();
        final List<String> assignedTo = new ArrayList<>();
        boolean patrolling;
        final List<String> cleared = new ArrayList<>();
        RuntimeException clearFailure;

        @Override
        public Status status() {
            return status;
        }

        @Override
        public Status probe(MinecraftServer server) {
            return status;
        }

        @Override
        public GlobalCapabilities readCapabilities() {
            if (capabilitiesFailure != null) {
                throw capabilitiesFailure;
            }
            return capabilitiesNull ? null : capabilities;
        }

        @Override
        public Set<String> discoverUpstreamSettingNames() {
            return Set.of();
        }

        @Override
        public boolean nameAvailable(MinecraftServer server, String name) {
            if (nameFailure != null) {
                throw nameFailure;
            }
            return nameFree;
        }

        @Override
        public SpawnTicket requestSpawn(MinecraftServer server, ServerWorld world, String name, double x, double y, double z, float yaw) {
            lastWorld = world;
            lastName = name;
            lastPosition = new double[]{x, y, z};
            lastYaw = yaw;
            if (spawnFailure != null) {
                throw spawnFailure;
            }
            return nullTicket ? null : ticket;
        }

        @Override
        public SpawnState pollSpawn(MinecraftServer server, SpawnTicket t) {
            polled.add(t);
            if (pollFailure != null) {
                throw pollFailure;
            }
            return nullState ? null : state;
        }

        @Override
        public Optional<ServerPlayerEntity> findBotEntity(MinecraftServer server, String name) {
            if (findFailure != null) {
                throw findFailure;
            }
            return entity;
        }

        @Override
        public boolean isManaged(String name) {
            return managed;
        }

        @Override
        public boolean isBotEntity(ServerPlayerEntity player) {
            return true;
        }

        @Override
        public boolean removeBot(MinecraftServer server, String name) {
            if (removeFailure != null) {
                throw removeFailure;
            }
            return removeResult;
        }

        @Override
        public boolean assignPatrol(MinecraftServer server, String botName, BotProfile.Behavior behavior) {
            assigned.add(behavior);
            assignedTo.add(botName);
            if (assignFailure != null) {
                throw assignFailure;
            }
            return assignResult;
        }

        @Override
        public void clearPatrol(String botName) {
            cleared.add(botName);
            if (clearFailure != null) {
                throw clearFailure;
            }
        }

        @Override
        public boolean isPatrolling(String botName) {
            return patrolling;
        }
    }

    /** Records every application, and remembers which entities were marked. */
    static final class Applier implements ProfileApplication {
        boolean loadoutApplied = true;
        boolean vitalsApplied = true;
        List<String> warnings = List.of();
        final List<Boolean> clearFlags = new ArrayList<>();
        final List<BotProfile> profiles = new ArrayList<>();
        final Map<ServerPlayerEntity, Boolean> marked = new IdentityHashMap<>();

        @Override
        public Result apply(ServerPlayerEntity bot, BotProfile profile, boolean clearInventoryFirst) {
            clearFlags.add(clearInventoryFirst);
            profiles.add(profile);
            return new Result(loadoutApplied, vitalsApplied, warnings);
        }

        @Override
        public boolean isMarked(ServerPlayerEntity bot) {
            return marked.containsKey(bot);
        }

        @Override
        public void mark(ServerPlayerEntity bot) {
            marked.put(bot, Boolean.TRUE);
        }
    }

    /** A "server" with a settable world map, online-player set and tick counter. */
    static final class Access implements ServerAccess {
        final MinecraftServer server = null;
        final Map<String, ServerWorld> worlds = new HashMap<>();
        final Set<String> online = new HashSet<>();
        long seed = 1234L;
        int ticks;

        @Override
        public MinecraftServer server() {
            return server;
        }

        @Override
        public ServerWorld world(String dimensionId) {
            return worlds.get(dimensionId);
        }

        @Override
        public long worldSeed() {
            return seed;
        }

        @Override
        public boolean isPlayerOnline(String name) {
            return online.contains(name.toLowerCase(java.util.Locale.ROOT));
        }

        @Override
        public int ticks() {
            return ticks;
        }
    }
}

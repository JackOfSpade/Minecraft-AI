package dev.spawnbotswrapper.inhabitants.command;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.engine.EngineControl;
import dev.spawnbotswrapper.inhabitants.engine.ForceMode;
import dev.spawnbotswrapper.inhabitants.engine.PopulationView;
import dev.spawnbotswrapper.inhabitants.mc.StructureLocator;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * In-memory stand-ins for every service the commands use, with call recording. None of them touches
 * Minecraft or PvP BOT, so the whole command layer runs without a server.
 */
final class FakeServices {
    final InhabitantsConfig config = new InhabitantsConfig();
    final FakePopulation population = new FakePopulation();
    final FakeEngine engine = new FakeEngine();
    final FakeAdapter adapter = new FakeAdapter();
    final FakeLocator locator = new FakeLocator();
    List<String> reloadMessages = List.of();
    int reloads;

    CommandServices services() {
        return new CommandServices(() -> config, population, engine, adapter, locator,
                () -> {
                    reloads++;
                    return reloadMessages;
                }, "1.2.3");
    }

    /** Sender resolver used by the tree tests: no world, fixed dimension and position. */
    static Function<net.minecraft.server.command.ServerCommandSource, Sender> senderAt(double x, double y, double z) {
        return source -> new Sender(null, Fixtures.OVERWORLD, x, y, z);
    }

    static Backends backends(FakeCatalog catalog, Backends.ProfileRenderer renderer,
                             Function<net.minecraft.server.command.ServerCommandSource, Sender> senders) {
        return new Backends(catalog, renderer, senders);
    }

    // ---------------------------------------------------------------- population

    static final class FakePopulation implements PopulationView {
        final Map<StructureKey, StructureRecord> records = new LinkedHashMap<>();
        /** What {@link #nearby} returns, nearest first; defaults to every record. */
        List<Map.Entry<StructureKey, StructureRecord>> nearbyResult;
        PopulationCounts counts = Fixtures.counts();
        RuntimeException failure;

        String lastDimension;
        int lastChunkX;
        int lastChunkZ;
        int lastRadius = -1;

        @Override
        public Optional<StructureRecord> find(StructureKey key) {
            fail();
            return Optional.ofNullable(records.get(key));
        }

        @Override
        public List<Map.Entry<StructureKey, StructureRecord>> nearby(String dimensionId, int chunkX, int chunkZ,
                                                                      int radiusChunks) {
            fail();
            lastDimension = dimensionId;
            lastChunkX = chunkX;
            lastChunkZ = chunkZ;
            lastRadius = radiusChunks;
            return nearbyResult != null ? nearbyResult : new ArrayList<>(records.entrySet());
        }

        @Override
        public Optional<BotLocation> findBot(String botName) {
            fail();
            for (Map.Entry<StructureKey, StructureRecord> e : records.entrySet()) {
                for (BotRecord b : e.getValue().bots) {
                    if (b.name != null && b.name.equalsIgnoreCase(botName)) {
                        return Optional.of(new BotLocation(e.getKey(), b));
                    }
                }
            }
            return Optional.empty();
        }

        @Override
        public PopulationCounts counts() {
            fail();
            return counts;
        }

        private void fail() {
            if (failure != null) {
                throw failure;
            }
        }
    }

    // ---------------------------------------------------------------- engine

    static final class FakeEngine implements EngineControl {
        record ProcessCall(StructureSnapshot snapshot, ForceMode mode) {
        }

        record ResetCall(StructureKey key, boolean removeBots) {
        }

        final List<ProcessCall> processCalls = new ArrayList<>();
        final List<ResetCall> resetCalls = new ArrayList<>();
        ProcessOutcome nextOutcome = new ProcessOutcome(ProcessOutcome.Kind.OCCUPIED_QUEUED, "3 bots planned");
        boolean resetResult = true;
        /** Simulates the engine forgetting the record (the real one removes it from the store). */
        java.util.function.Consumer<StructureKey> onReset = key -> {
        };
        EngineStats stats = Fixtures.stats();

        @Override
        public ProcessOutcome process(StructureSnapshot snapshot, ForceMode mode) {
            processCalls.add(new ProcessCall(snapshot, mode));
            return nextOutcome;
        }

        @Override
        public boolean reset(StructureKey key, boolean removeBots) {
            resetCalls.add(new ResetCall(key, removeBots));
            if (resetResult) {
                onReset.accept(key);
            }
            return resetResult;
        }

        @Override
        public EngineStats stats() {
            return stats;
        }
    }

    // ---------------------------------------------------------------- adapter

    static final class FakeAdapter implements PvpBotOperations {
        Status status = Fixtures.availableStatus();
        GlobalCapabilities capabilities = GlobalCapabilities.upstreamDefaults();
        Set<String> upstreamNames = Set.of("moveSpeed", "autoTarget");
        boolean throwOnCapabilities;
        boolean throwOnNames;
        int capabilityReads;

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
            capabilityReads++;
            if (throwOnCapabilities) {
                throw new IllegalStateException("upstream reflection failed");
            }
            return capabilities;
        }

        @Override
        public Set<String> discoverUpstreamSettingNames() {
            if (throwOnNames) {
                throw new IllegalStateException("upstream reflection failed");
            }
            return upstreamNames;
        }

        @Override
        public boolean nameAvailable(MinecraftServer server, String name) {
            return true;
        }

        @Override
        public SpawnTicket requestSpawn(MinecraftServer server, ServerWorld world, String name, double x, double y,
                                        double z, float yaw) {
            return null;
        }

        @Override
        public SpawnState pollSpawn(MinecraftServer server, SpawnTicket ticket) {
            return null;
        }

        @Override
        public Optional<ServerPlayerEntity> findBotEntity(MinecraftServer server, String name) {
            return Optional.empty();
        }

        @Override
        public boolean isManaged(String name) {
            return false;
        }

        @Override
        public boolean isBotEntity(ServerPlayerEntity player) {
            return false;
        }

        @Override
        public boolean removeBot(MinecraftServer server, String name) {
            return false;
        }

        @Override
        public boolean assignPatrol(MinecraftServer server, String botName, BotProfile.Behavior behavior) {
            return false;
        }

        @Override
        public void clearPatrol(String botName) {
        }

        @Override
        public boolean isPatrolling(String botName) {
            return false;
        }
    }

    // ---------------------------------------------------------------- locator

    static final class FakeLocator implements StructureLocator {
        List<StructureSnapshot> at = List.of();
        List<StructureSnapshot> near = List.of();
        int lastNearRadius = -1;
        BlockPos lastAtPos;
        BlockPos lastNearPos;

        @Override
        public List<StructureSnapshot> at(ServerWorld world, BlockPos pos) {
            lastAtPos = pos;
            return at;
        }

        @Override
        public List<StructureSnapshot> near(ServerWorld world, BlockPos pos, int radiusChunks) {
            lastNearPos = pos;
            lastNearRadius = radiusChunks;
            return near;
        }
    }

    // ---------------------------------------------------------------- catalog

    static class FakeCatalog implements Backends.CatalogSource {
        List<SettingCatalog.SettingSpec> specs = List.of();
        String version = "0.0.15";
        Set<String> lastAuditInput;
        SettingCatalog.AuditReport report = new SettingCatalog.AuditReport(List.of(), List.of());

        @Override
        public List<SettingCatalog.SettingSpec> all() {
            return specs;
        }

        @Override
        public String auditedVersion() {
            return version;
        }

        @Override
        public SettingCatalog.AuditReport audit(Set<String> upstreamFieldNames) {
            lastAuditInput = upstreamFieldNames;
            return report;
        }
    }

    // ---------------------------------------------------------------- reply

    /** Collects what a command answered. */
    static final class RecordingReply implements Reply {
        final List<String> lines = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        @Override
        public void lines(List<String> lines) {
            this.lines.addAll(lines);
        }

        @Override
        public void error(String message) {
            errors.add(message);
        }

        /** The visible text of all output lines (markup removed). */
        List<String> text() {
            return Markup.strip(lines);
        }

        String joined() {
            return String.join("\n", text());
        }
    }
}

package io.github.zoyluo.minecraftai.manager;

import com.mojang.authlib.GameProfile;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogFields;
import io.github.zoyluo.minecraftai.memory.BotMemoryStore;
import io.github.zoyluo.minecraftai.network.FakeClientConnection;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import io.github.zoyluo.minecraftai.persist.BotPersistence;
import io.github.zoyluo.minecraftai.persist.BotRecord;
import io.github.zoyluo.minecraftai.runtime.RuntimeLifecycleCoordinator;
import io.github.zoyluo.minecraftai.util.OfflineProfileFactory;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AIPlayerManager {
    public static final AIPlayerManager INSTANCE = new AIPlayerManager();

    private final Map<UUID, AIPlayerEntity> players = new ConcurrentHashMap<>();
    private final Map<String, UUID> nameIndex = new ConcurrentHashMap<>();
    /**
     * Every bot a player owns, in spawn order (oldest first). A player may own any number of bots;
     * {@link #botOf} picks the most recently spawned one as the implicit "my bot" default when a
     * command or chat message doesn't name one, but any of them remains addressable by name.
     */
    private final Map<UUID, java.util.LinkedHashSet<UUID>> ownerIndex = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> botOwners = new ConcurrentHashMap<>();
    /** One of {@link OfflineProfileFactory}'s 18 default-skin indices, chosen once per bot. */
    private final Map<UUID, Integer> skinIndices = new ConcurrentHashMap<>();

    private AIPlayerManager() {
    }

    /**
     * SAFE-DEAD: after a bot dies (hp<=0) it sits in place indefinitely receiving evade requests
     * and never respawns on its own (a fake player has no client to send the vanilla respawn
     * packet, and the ServerPlayerEntity isn't removed after death either). This revives it to
     * full health and teleports it to a safe surface point, clearing any leftover death state.
     * Returns true if it was revived.
     */
    public boolean respawnDeadBot(AIPlayerEntity bot) {
        ServerWorld world = bot.getEntityWorld();
        // Episodic memory: record the death event (using the death position = current position,
        // before teleporting to the surface). Distillation rule: two deaths in the same area -> danger zone.
        io.github.zoyluo.minecraftai.memory.EpisodeLog.INSTANCE.record(bot,
                io.github.zoyluo.minecraftai.memory.EpisodeLog.Type.DEATH, bot.getBlockPos(),
                bot.getRecentDamageSource() == null ? "unknown" : bot.getRecentDamageSource().getName());
        RuntimeLifecycleCoordinator.INSTANCE.onBotDeath(bot);
        boolean enhancedRespawn = CapabilityRuntime.decide(
                bot, PrivilegedCapability.EMERGENCY_TELEPORT, "death_surface_respawn").allowed();
        ServerWorld respawnWorld;
        Vec3d respawnPos;
        String respawnStrategy;
        if (enhancedRespawn) {
            BlockPos surface = world.getTopPosition(
                    Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, bot.getBlockPos());
            respawnWorld = world;
            respawnPos = Vec3d.ofBottomCenter(surface);
            respawnStrategy = "operator_death_column_surface";
        } else {
            // Fake players cannot send the vanilla respawn packet. In strict mode this adapter uses
            // the world's normal spawn area instead of teleporting to the death column's surface.
            respawnWorld = bot.getEntityWorld().getServer().getOverworld();
            respawnPos = safeSpawnPosition(
                    respawnWorld, Vec3d.ofBottomCenter(respawnWorld.getSpawnPoint().getPos()),
                    bot.getGameProfile().name());
            respawnStrategy = "strict_world_spawn";
        }
        bot.setHealth(20.0F);
        bot.deathTime = 0;
        bot.getHungerManager().setFoodLevel(20);
        bot.teleport(respawnWorld, respawnPos.x, respawnPos.y, respawnPos.z,
                Collections.emptySet(), bot.getYaw(), bot.getPitch(), true);
        bot.extinguish();
        BotLog.danger(bot, "bot_respawned_after_death",
                "pos", LogFields.pos(bot.getBlockPos()),
                "strategy", respawnStrategy);
        return true;
    }

    public Optional<AIPlayerEntity> spawn(MinecraftServer server,
                                          String name,
                                          ServerWorld world,
                                          Vec3d pos,
                                          float yaw,
                                          float pitch,
                                          GameMode gameMode) {
        return spawn(server, name, world, pos, yaw, pitch, gameMode, null);
    }

    public Optional<AIPlayerEntity> spawn(MinecraftServer server,
                                          String name,
                                          ServerWorld world,
                                          Vec3d pos,
                                          float yaw,
                                          float pitch,
                                          GameMode gameMode,
                                          UUID ownerUuid) {
        return spawnInternal(server, name, world, pos, yaw, pitch, gameMode, ownerUuid, null);
    }

    /**
     * {@code explicitSkinIndex} is null for every ordinary spawn (a fresh random default skin is
     * picked below); {@link #respawnFromRecord} is the one caller that passes a persisted index, so
     * a restored bot keeps looking like itself across a server restart instead of re-rolling.
     */
    private Optional<AIPlayerEntity> spawnInternal(MinecraftServer server,
                                                    String name,
                                                    ServerWorld world,
                                                    Vec3d pos,
                                                    float yaw,
                                                    float pitch,
                                                    GameMode gameMode,
                                                    UUID ownerUuid,
                                                    Integer explicitSkinIndex) {
        String normalizedName = normalizeName(name);
        if (nameIndex.containsKey(normalizedName) || server.getPlayerManager().getPlayer(name) != null) {
            return Optional.empty();
        }

        int skinIndex = explicitSkinIndex != null ? explicitSkinIndex : OfflineProfileFactory.randomSkinIndex();
        GameProfile profile = OfflineProfileFactory.create(name, skinIndex);
        SyncedClientOptions options = SyncedClientOptions.createDefault();
        AIPlayerEntity player = new AIPlayerEntity(server, world, profile, options);
        FakeClientConnection connection = new FakeClientConnection(NetworkSide.SERVERBOUND);
        ConnectedClientData clientData = new ConnectedClientData(profile, 0, options, false);
        Vec3d safePos = safeSpawnPosition(world, pos, name);

        server.getPlayerManager().onPlayerConnect(connection, player, clientData);
        player.teleport(world, safePos.x, safePos.y, safePos.z, Collections.emptySet(), yaw, pitch, true);
        player.setHealth(20.0F);
        player.reviveForMinecraftAiSpawn();
        EntityAttributeInstance stepHeight = player.getAttributeInstance(EntityAttributes.STEP_HEIGHT);
        if (stepHeight != null) {
            stepHeight.setBaseValue(0.6D);
        }
        // The AI assistant is locked to survival mode: in creative mode broken blocks don't
        // drop, and in adventure mode breaking/placing is disallowed, both of which would break
        // gathering/building. So the incoming gameMode is ignored (it may be the summoner's
        // creative mode, or creative restored from an old save) and SURVIVAL is always used.
        GameMode effectiveMode = GameMode.SURVIVAL;
        player.interactionManager.changeGameMode(effectiveMode);

        players.put(player.getUuid(), player);
        nameIndex.put(normalizedName, player.getUuid());
        skinIndices.put(player.getUuid(), skinIndex);
        if (ownerUuid != null) {
            ownerIndex.computeIfAbsent(ownerUuid, ignored -> new java.util.LinkedHashSet<>()).add(player.getUuid());
            botOwners.put(player.getUuid(), ownerUuid);
        }
        BotLog.lifecycle(player, "bot_spawned", "pos", LogFields.pos(player.getBlockPos()), "mode", effectiveMode.asString());
        BotPersistence.INSTANCE.markDirty(server);
        return Optional.of(player);
    }

    public Optional<AIPlayerEntity> respawnFromRecord(MinecraftServer server, BotRecord record) {
        RestoreTarget target = restoreTarget(server, record);
        GameMode gameMode = GameMode.SURVIVAL;  // The AI assistant is always survival; ignore any creative mode that may have been saved in the old record
        Optional<AIPlayerEntity> spawned = spawnInternal(
                server,
                record.name(),
                target.world(),
                target.pos(),
                record.yaw(),
                record.pitch(),
                gameMode,
                parseUuid(record.ownerUuid()),
                record.skinIndex());
        spawned.ifPresent(bot -> {
            BotPersistence.applyInventory(bot, record.inventoryNbt());
            BotPersistence.applyPlayerState(bot, record.playerStateNbt());
            BotMemoryStore.INSTANCE.loadString(bot.getUuid(), record.memoryNbt());
            bot.setHealth(Math.max(1.0F, Math.min(record.health(), bot.getMaxHealth())));
            bot.getHungerManager().setFoodLevel(Math.max(0, Math.min(20, record.hunger())));
            BotLog.lifecycle(bot, "bot_restored",
                    "pos", LogFields.pos(bot.getBlockPos()),
                    "mode", gameMode.asString(),
                    "dimension", bot.getEntityWorld().getRegistryKey().getValue(),
                    "fallback", target.fallback());
        });
        return spawned;
    }

    public boolean despawn(MinecraftServer server, String name) {
        Optional<AIPlayerEntity> player = getByName(name);
        if (player.isEmpty()) {
            return false;
        }

        AIPlayerEntity entity = player.get();
        RuntimeLifecycleCoordinator.INSTANCE.deleteBot(entity);
        players.remove(entity.getUuid());
        nameIndex.remove(normalizeName(name));
        skinIndices.remove(entity.getUuid());
        clearOwner(entity.getUuid());
        disconnect(server, entity, "MinecraftAi despawn");
        BotLog.lifecycle(entity, "bot_despawned", "reason", "command_or_shutdown");
        BotPersistence.INSTANCE.markDirty(server);
        return true;
    }

    public Optional<AIPlayerEntity> getByName(String name) {
        String normalized = normalizeName(name);
        UUID uuid = nameIndex.get(normalized);
        AIPlayerEntity indexed = uuid == null ? null : players.get(uuid);
        if (indexed != null) {
            return Optional.of(indexed);
        }
        // A restored fake player can survive a transient name-index refresh.  Recover it
        // from the authoritative live-player map and repair the derived index so that a
        // visible bot never becomes unaddressable by its owner.
        Optional<AIPlayerEntity> recovered = players.values().stream()
                .filter(player -> normalizeName(player.getGameProfile().name()).equals(normalized))
                .findFirst();
        recovered.ifPresent(player -> nameIndex.put(normalized, player.getUuid()));
        return recovered;
    }

    public Optional<AIPlayerEntity> getByUuid(UUID uuid) {
        return Optional.ofNullable(players.get(uuid));
    }

    /**
     * The implicit "my bot" default for a command or chat message that doesn't name one: the most
     * recently spawned of the owner's bots, or empty if they have none right now. Any bot an owner
     * has -- not just this one -- remains addressable by giving its name explicitly; see
     * {@link #botsOf} to enumerate all of them.
     */
    public Optional<AIPlayerEntity> botOf(UUID ownerUuid) {
        java.util.LinkedHashSet<UUID> owned = ownerIndex.get(ownerUuid);
        if (owned == null || owned.isEmpty()) {
            return Optional.empty();
        }
        UUID botUuid = null;
        for (UUID candidate : owned) {
            botUuid = candidate; // LinkedHashSet: last iterated == most recently added
        }
        AIPlayerEntity bot = players.get(botUuid);
        if (bot == null) {
            // Stale entry (should already have been cleared by despawn/clearOwner); self-heal.
            owned.remove(botUuid);
            return botOf(ownerUuid);
        }
        return Optional.of(bot);
    }

    /** Every bot {@code ownerUuid} currently owns, oldest first. Empty (never null) if none. */
    public Collection<AIPlayerEntity> botsOf(UUID ownerUuid) {
        java.util.LinkedHashSet<UUID> owned = ownerIndex.get(ownerUuid);
        if (owned == null || owned.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<AIPlayerEntity> result = new java.util.ArrayList<>(owned.size());
        for (UUID botUuid : owned) {
            AIPlayerEntity bot = players.get(botUuid);
            if (bot != null) {
                result.add(bot);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public Optional<UUID> ownerOf(AIPlayerEntity bot) {
        return Optional.ofNullable(botOwners.get(bot.getUuid()));
    }

    public Collection<AIPlayerEntity> all() {
        return Collections.unmodifiableCollection(players.values());
    }

    /** The one of {@link OfflineProfileFactory}'s 18 default-skin indices this bot was spawned with. */
    public int skinIndex(AIPlayerEntity bot) {
        return skinIndices.getOrDefault(bot.getUuid(), 0);
    }

    public void onServerStopping(MinecraftServer server) {
        int count = players.size();
        for (AIPlayerEntity player : players.values().toArray(AIPlayerEntity[]::new)) {
            RuntimeLifecycleCoordinator.INSTANCE.unloadBot(player);
            disconnect(server, player, "MinecraftAi server unload");
        }
        players.clear();
        nameIndex.clear();
        skinIndices.clear();
        ownerIndex.clear();
        botOwners.clear();
        BotLog.lifecycle("all_bots_cleared", "count", count);
    }

    private void clearOwner(UUID botUuid) {
        UUID ownerUuid = botOwners.remove(botUuid);
        if (ownerUuid == null) {
            return;
        }
        ownerIndex.computeIfPresent(ownerUuid, (ignored, owned) -> {
            owned.remove(botUuid);
            return owned.isEmpty() ? null : owned;
        });
    }

    private static void disconnect(MinecraftServer server, AIPlayerEntity entity, String reason) {
        if (entity.networkHandler != null) {
            entity.networkHandler.onDisconnected(new DisconnectionInfo(Text.literal(reason)));
        } else {
            server.getPlayerManager().remove(entity);
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static RestoreTarget restoreTarget(MinecraftServer server, BotRecord record) {
        RegistryKey<World> worldKey;
        try {
            worldKey = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(record.dimension()));
        } catch (RuntimeException exception) {
            BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.LIFECYCLE, null, "bot_restore_dimension_invalid",
                    "name", record.name(), "dimension", record.dimension());
            return overworldSpawn(server);
        }
        ServerWorld world = server.getWorld(worldKey);
        if (world == null) {
            BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.LIFECYCLE, null, "bot_restore_world_missing",
                    "name", record.name(), "dimension", record.dimension());
            return overworldSpawn(server);
        }
        return new RestoreTarget(world, new Vec3d(record.x(), record.y(), record.z()), false);
    }

    private static RestoreTarget overworldSpawn(MinecraftServer server) {
        ServerWorld overworld = server.getOverworld();
        return new RestoreTarget(overworld, Vec3d.ofBottomCenter(overworld.getSpawnPoint().getPos()), true);
    }

    private static Vec3d safeSpawnPosition(ServerWorld world, Vec3d requested, String name) {
        BlockPos requestedBlock = BlockPos.ofFloored(requested);
        Standability.clearCache();
        if (Standability.isStandable(world, requestedBlock)) {
            return requested;
        }
        Optional<BlockPos> safe = Standability.findNearestStandable(world, requestedBlock, 8, 128, 32);
        if (safe.isEmpty()) {
            BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.LIFECYCLE, null, "bot_spawn_position_unsafe",
                    "name", name, "requested", LogFields.pos(requestedBlock));
            return requested;
        }
        BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.LIFECYCLE, null, "bot_spawn_position_snapped",
                "name", name,
                "from", LogFields.pos(requestedBlock),
                "to", LogFields.pos(safe.get()));
        return Vec3d.ofBottomCenter(safe.get());
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private record RestoreTarget(ServerWorld world, Vec3d pos, boolean fallback) {
    }
}

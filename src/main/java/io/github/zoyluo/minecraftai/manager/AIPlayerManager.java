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
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
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
     * packet, and the ServerPlayer isn't removed after death either). This revives it to
     * full health and teleports it to a safe surface point, clearing any leftover death state.
     * Returns true if it was revived.
     */
    public boolean respawnDeadBot(AIPlayerEntity bot) {
        ServerLevel world = bot.level();
        // Episodic memory: record the death event (using the death position = current position,
        // before teleporting to the surface). Distillation rule: two deaths in the same area -> danger zone.
        io.github.zoyluo.minecraftai.memory.EpisodeLog.INSTANCE.record(bot,
                io.github.zoyluo.minecraftai.memory.EpisodeLog.Type.DEATH, bot.blockPosition(),
                bot.getLastDamageSource() == null ? "unknown" : bot.getLastDamageSource().getMsgId());
        RuntimeLifecycleCoordinator.INSTANCE.onBotDeath(bot);
        boolean enhancedRespawn = CapabilityRuntime.decide(
                bot, PrivilegedCapability.EMERGENCY_TELEPORT, "death_surface_respawn").allowed();
        ServerLevel respawnWorld;
        Vec3 respawnPos;
        String respawnStrategy;
        if (enhancedRespawn) {
            BlockPos surface = world.getHeightmapPos(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bot.blockPosition());
            respawnWorld = world;
            respawnPos = Vec3.atBottomCenterOf(surface);
            respawnStrategy = "operator_death_column_surface";
        } else {
            // Fake players cannot send the vanilla respawn packet. In strict mode this adapter uses
            // the world's normal spawn area instead of teleporting to the death column's surface.
            respawnWorld = bot.level().getServer().overworld();
            respawnPos = safeSpawnPosition(
                    respawnWorld, Vec3.atBottomCenterOf(respawnWorld.getRespawnData().pos()),
                    bot.getGameProfile().name());
            respawnStrategy = "strict_world_spawn";
        }
        bot.setHealth(20.0F);
        bot.deathTime = 0;
        bot.getFoodData().setFoodLevel(20);
        bot.teleportTo(respawnWorld, respawnPos.x, respawnPos.y, respawnPos.z,
                Collections.emptySet(), bot.getYRot(), bot.getXRot(), true);
        bot.clearFire();
        BotLog.danger(bot, "bot_respawned_after_death",
                "pos", LogFields.pos(bot.blockPosition()),
                "strategy", respawnStrategy);
        return true;
    }

    public Optional<AIPlayerEntity> spawn(MinecraftServer server,
                                          String name,
                                          ServerLevel world,
                                          Vec3 pos,
                                          float yaw,
                                          float pitch,
                                          GameType gameMode) {
        return spawn(server, name, world, pos, yaw, pitch, gameMode, null);
    }

    public Optional<AIPlayerEntity> spawn(MinecraftServer server,
                                          String name,
                                          ServerLevel world,
                                          Vec3 pos,
                                          float yaw,
                                          float pitch,
                                          GameType gameMode,
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
                                                    ServerLevel world,
                                                    Vec3 pos,
                                                    float yaw,
                                                    float pitch,
                                                    GameType gameMode,
                                                    UUID ownerUuid,
                                                    Integer explicitSkinIndex) {
        String normalizedName = normalizeName(name);
        if (nameIndex.containsKey(normalizedName) || server.getPlayerList().getPlayerByName(name) != null) {
            return Optional.empty();
        }

        int skinIndex = explicitSkinIndex != null ? explicitSkinIndex : OfflineProfileFactory.randomSkinIndex();
        GameProfile profile = OfflineProfileFactory.create(name, skinIndex);
        ClientInformation options = ClientInformation.createDefault();
        AIPlayerEntity player = new AIPlayerEntity(server, world, profile, options);
        FakeClientConnection connection = new FakeClientConnection(PacketFlow.SERVERBOUND);
        CommonListenerCookie clientData = new CommonListenerCookie(profile, 0, options, false);
        Vec3 safePos = safeSpawnPosition(world, pos, name);

        server.getPlayerList().placeNewPlayer(connection, player, clientData);
        player.teleportTo(world, safePos.x, safePos.y, safePos.z, Collections.emptySet(), yaw, pitch, true);
        player.setHealth(20.0F);
        player.reviveForMinecraftAiSpawn();
        AttributeInstance stepHeight = player.getAttribute(Attributes.STEP_HEIGHT);
        if (stepHeight != null) {
            stepHeight.setBaseValue(0.6D);
        }
        // The AI assistant is locked to survival mode: in creative mode broken blocks don't
        // drop, and in adventure mode breaking/placing is disallowed, both of which would break
        // gathering/building. So the incoming gameMode is ignored (it may be the summoner's
        // creative mode, or creative restored from an old save) and SURVIVAL is always used.
        GameType effectiveMode = GameType.SURVIVAL;
        player.gameMode.changeGameModeForPlayer(effectiveMode);

        players.put(player.getUUID(), player);
        nameIndex.put(normalizedName, player.getUUID());
        skinIndices.put(player.getUUID(), skinIndex);
        if (ownerUuid != null) {
            ownerIndex.computeIfAbsent(ownerUuid, ignored -> new java.util.LinkedHashSet<>()).add(player.getUUID());
            botOwners.put(player.getUUID(), ownerUuid);
        }
        BotLog.lifecycle(player, "bot_spawned", "pos", LogFields.pos(player.blockPosition()), "mode", effectiveMode.getSerializedName());
        BotPersistence.INSTANCE.markDirty(server);
        return Optional.of(player);
    }

    public Optional<AIPlayerEntity> respawnFromRecord(MinecraftServer server, BotRecord record) {
        RestoreTarget target = restoreTarget(server, record);
        GameType gameMode = GameType.SURVIVAL;  // The AI assistant is always survival; ignore any creative mode that may have been saved in the old record
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
            io.github.zoyluo.minecraftai.brain.ChatMemory.restore(bot.getUUID(), record.conversationMemoryJson(), System.currentTimeMillis());
            BotMemoryStore.INSTANCE.loadString(bot.getUUID(), record.memoryNbt());
            bot.setHealth(Math.max(1.0F, Math.min(record.health(), bot.getMaxHealth())));
            bot.getFoodData().setFoodLevel(Math.max(0, Math.min(20, record.hunger())));
            BotLog.lifecycle(bot, "bot_restored",
                    "pos", LogFields.pos(bot.blockPosition()),
                    "mode", gameMode.getSerializedName(),
                    "dimension", bot.level().dimension().identifier(),
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
        players.remove(entity.getUUID());
        nameIndex.remove(normalizeName(name));
        skinIndices.remove(entity.getUUID());
        clearOwner(entity.getUUID());
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
        recovered.ifPresent(player -> nameIndex.put(normalized, player.getUUID()));
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
        return Optional.ofNullable(botOwners.get(bot.getUUID()));
    }

    /**
     * True when {@code playerUuid} currently owns at least one Minecraft-AI bot. Used only to define the protected victims of the
     * hostile-bot ledger (a player who owns a bot, or any bot); it is never a friendliness test.
     */
    public boolean isAnyBotOwner(UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }
        java.util.LinkedHashSet<UUID> owned = ownerIndex.get(playerUuid);
        return owned != null && !owned.isEmpty();
    }

    public Collection<AIPlayerEntity> all() {
        return Collections.unmodifiableCollection(players.values());
    }

    /** The one of {@link OfflineProfileFactory}'s 18 default-skin indices this bot was spawned with. */
    public int skinIndex(AIPlayerEntity bot) {
        return skinIndices.getOrDefault(bot.getUUID(), 0);
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
        if (entity.connection != null) {
            entity.connection.onDisconnect(new DisconnectionDetails(Component.literal(reason)));
        } else {
            server.getPlayerList().remove(entity);
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
        ResourceKey<Level> worldKey;
        try {
            worldKey = ResourceKey.create(Registries.DIMENSION, Identifier.parse(record.dimension()));
        } catch (RuntimeException exception) {
            BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.LIFECYCLE, null, "bot_restore_dimension_invalid",
                    "name", record.name(), "dimension", record.dimension());
            return overworldSpawn(server);
        }
        ServerLevel world = server.getLevel(worldKey);
        if (world == null) {
            BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.LIFECYCLE, null, "bot_restore_world_missing",
                    "name", record.name(), "dimension", record.dimension());
            return overworldSpawn(server);
        }
        return new RestoreTarget(world, new Vec3(record.x(), record.y(), record.z()), false);
    }

    private static RestoreTarget overworldSpawn(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return new RestoreTarget(overworld, Vec3.atBottomCenterOf(overworld.getRespawnData().pos()), true);
    }

    private static Vec3 safeSpawnPosition(ServerLevel world, Vec3 requested, String name) {
        BlockPos requestedBlock = BlockPos.containing(requested);
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
        return Vec3.atBottomCenterOf(safe.get());
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private record RestoreTarget(ServerLevel world, Vec3 pos, boolean fallback) {
    }
}

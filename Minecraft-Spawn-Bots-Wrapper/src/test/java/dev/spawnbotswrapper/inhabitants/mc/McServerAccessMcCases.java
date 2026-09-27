package dev.spawnbotswrapper.inhabitants.mc;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.dedicated.DedicatedPlayerManager;
import net.minecraft.server.dedicated.MinecraftDedicatedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** {@link McServerAccess} against a server whose world map, player list and tick counter are set directly, run inside {@link McSandbox}. */
public final class McServerAccessMcCases {
    private McServerAccessMcCases() {
    }

    private static ServerPlayerEntity player(String name) {
        ServerPlayerEntity player = McObjects.opaque(ServerPlayerEntity.class);
        McObjects.setField(player, PlayerEntity.class, "gameProfile", new GameProfile(UUID.randomUUID(), name));
        McObjects.setField(player, Entity.class, "commandTags", new HashSet<String>());
        return player;
    }

    private static MinecraftServer server(Map<RegistryKey<World>, ServerWorld> worlds, List<ServerPlayerEntity> players, int ticks) {
        MinecraftDedicatedServer server = McObjects.opaque(MinecraftDedicatedServer.class);
        McObjects.setField(server, MinecraftServer.class, "worlds", worlds);
        DedicatedPlayerManager manager = McObjects.opaque(DedicatedPlayerManager.class);
        McObjects.setField(manager, PlayerManager.class, "players", new ArrayList<>(players));
        McObjects.setField(server, MinecraftServer.class, "playerManager", manager);
        McObjects.setInt(server, MinecraftServer.class, "ticks", ticks);
        return server;
    }

    private static RegistryKey<World> dimension(String id) {
        return RegistryKey.of(RegistryKeys.WORLD, Identifier.of(id));
    }

    public static void dimensionIdsResolveToLoadedWorldsOnly() {
        ServerWorld overworld = McObjects.opaque(ServerWorld.class);
        ServerWorld nether = McObjects.opaque(ServerWorld.class);
        ServerWorld custom = McObjects.opaque(ServerWorld.class);
        Map<RegistryKey<World>, ServerWorld> worlds = new HashMap<>();
        worlds.put(dimension("minecraft:overworld"), overworld);
        worlds.put(dimension("minecraft:the_nether"), nether);
        worlds.put(dimension("somemod:pocket"), custom);
        McServerAccess access = new McServerAccess(server(worlds, List.of(), 0));

        assertSame(overworld, access.world("minecraft:overworld"));
        assertSame(nether, access.world("minecraft:the_nether"));
        assertSame(custom, access.world("somemod:pocket"), "modded dimensions work the same way");
        assertNull(access.world("minecraft:the_end"), "known dimension but not loaded");
        assertNull(access.world("somemod:missing"));
    }

    public static void invalidDimensionIdsAreNullNotExceptions() {
        McServerAccess access = new McServerAccess(server(new HashMap<>(), List.of(), 0));
        assertNull(access.world(null));
        assertNull(access.world("Not A Valid Id!"));
        assertNull(access.world("UPPER:case"));
        assertNull(access.world(""));
    }

    public static void playerNamesAreComparedCaseInsensitively() {
        McServerAccess access = new McServerAccess(server(new HashMap<>(), List.of(player("Steve"), player("Inh_Guard7")), 0));
        assertTrue(access.isPlayerOnline("Steve"));
        assertTrue(access.isPlayerOnline("STEVE"));
        assertTrue(access.isPlayerOnline("inh_guard7"));
        assertFalse(access.isPlayerOnline("Alex"));
        assertFalse(access.isPlayerOnline("Steve2"));
        assertFalse(access.isPlayerOnline(""));
        assertFalse(access.isPlayerOnline(null));
    }

    public static void ticksFollowTheServerCounterAndTheServerIsPassedThrough() {
        MinecraftServer server = server(new HashMap<>(), List.of(), 4711);
        McServerAccess access = new McServerAccess(server);
        assertEquals(4711, access.ticks());
        assertSame(server, access.server());
        assertEquals(4711, McClock.of(server).tick());
    }
}

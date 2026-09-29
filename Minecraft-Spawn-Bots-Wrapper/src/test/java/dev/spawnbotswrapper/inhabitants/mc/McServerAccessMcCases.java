package dev.spawnbotswrapper.inhabitants.mc;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
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

    private static ServerPlayer player(String name) {
        ServerPlayer player = McObjects.opaque(ServerPlayer.class);
        McObjects.setField(player, Player.class, "gameProfile", new GameProfile(UUID.randomUUID(), name));
        McObjects.setField(player, Entity.class, "tags", new HashSet<String>());
        return player;
    }

    private static MinecraftServer server(Map<ResourceKey<Level>, ServerLevel> worlds, List<ServerPlayer> players, int ticks) {
        DedicatedServer server = McObjects.opaque(DedicatedServer.class);
        McObjects.setField(server, MinecraftServer.class, "levels", worlds);
        DedicatedPlayerList manager = McObjects.opaque(DedicatedPlayerList.class);
        McObjects.setField(manager, PlayerList.class, "players", new ArrayList<>(players));
        McObjects.setField(server, MinecraftServer.class, "playerList", manager);
        McObjects.setInt(server, MinecraftServer.class, "tickCount", ticks);
        return server;
    }

    private static ResourceKey<Level> dimension(String id) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse(id));
    }

    public static void dimensionIdsResolveToLoadedWorldsOnly() {
        ServerLevel overworld = McObjects.opaque(ServerLevel.class);
        ServerLevel nether = McObjects.opaque(ServerLevel.class);
        ServerLevel custom = McObjects.opaque(ServerLevel.class);
        Map<ResourceKey<Level>, ServerLevel> worlds = new HashMap<>();
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

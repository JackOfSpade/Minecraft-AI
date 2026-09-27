package dev.spawnbotswrapper.inhabitants.mc;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

/**
 * The few things the gateways need to ask a running server, behind an interface so the gateway logic can be
 * unit-tested without one. {@link McServerAccess} is the real implementation. Server thread only.
 */
public interface ServerAccess {

    /** The server, passed through to the PvP BOT adapter, whose interface is defined in terms of it. */
    MinecraftServer server();

    /** The loaded world of a dimension id such as {@code minecraft:the_nether}, or null when unknown/not loaded. */
    ServerWorld world(String dimensionId);

    /** The seed shared by every dimension of the save. */
    long worldSeed();

    /** True when a player entity with this name is online (names compare case-insensitively, as in vanilla). */
    boolean isPlayerOnline(String name);

    /** The server's tick counter. */
    int ticks();
}

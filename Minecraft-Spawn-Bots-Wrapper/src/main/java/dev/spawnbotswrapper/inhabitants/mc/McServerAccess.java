package dev.spawnbotswrapper.inhabitants.mc;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** {@link ServerAccess} backed by a live {@link MinecraftServer}. */
public final class McServerAccess implements ServerAccess {
    private final MinecraftServer server;

    public McServerAccess(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public MinecraftServer server() {
        return server;
    }

    @Override
    public ServerLevel world(String dimensionId) {
        Identifier id = dimensionId == null ? null : Identifier.tryParse(dimensionId);
        if (id == null) {
            return null;
        }
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    @Override
    public long worldSeed() {
        return server.overworld().getSeed();
    }

    @Override
    public boolean isPlayerOnline(String name) {
        return name != null && server.getPlayerList().getPlayerByName(name) != null;
    }

    @Override
    public int ticks() {
        return server.getTickCount();
    }
}

package dev.spawnbotswrapper.inhabitants.mc;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

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
    public ServerWorld world(String dimensionId) {
        Identifier id = dimensionId == null ? null : Identifier.tryParse(dimensionId);
        if (id == null) {
            return null;
        }
        return server.getWorld(RegistryKey.of(RegistryKeys.WORLD, id));
    }

    @Override
    public long worldSeed() {
        return server.getOverworld().getSeed();
    }

    @Override
    public boolean isPlayerOnline(String name) {
        return name != null && server.getPlayerManager().getPlayer(name) != null;
    }

    @Override
    public int ticks() {
        return server.getTicks();
    }
}

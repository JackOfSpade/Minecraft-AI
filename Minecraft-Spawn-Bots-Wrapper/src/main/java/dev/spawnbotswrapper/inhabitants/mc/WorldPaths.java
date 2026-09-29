package dev.spawnbotswrapper.inhabitants.mc;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Path;

/**
 * Where the addon keeps its per-world data. It lives INSIDE the save folder, so copying, backing up or
 * deleting a world takes the population records with it; keying them by level name instead would let two
 * worlds with the same name share (and corrupt) each other's records.
 */
public final class WorldPaths {
    public static final String DIRECTORY_NAME = "pvpbot_inhabitants";

    private WorldPaths() {
    }

    /** {@code <world>/pvpbot_inhabitants}, normalised (the save root is reported as {@code <world>/.}). */
    public static Path dataDirectory(MinecraftServer server) {
        return dataDirectory(server.getWorldPath(LevelResource.ROOT));
    }

    public static Path dataDirectory(Path worldRoot) {
        return worldRoot.resolve(DIRECTORY_NAME).normalize();
    }
}

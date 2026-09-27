package dev.spawnbotswrapper.inhabitants.adapter;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.Optional;

/** {@link Environment} backed by Fabric Loader. Every call is guarded: a missing loader is "nothing installed". */
final class FabricEnvironment implements Environment {

    @Override
    public Optional<String> modVersion(String modId) {
        try {
            return FabricLoader.getInstance().getModContainer(modId)
                    .map(container -> container.getMetadata().getVersion().getFriendlyString());
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Path> configDir() {
        try {
            return Optional.ofNullable(FabricLoader.getInstance().getConfigDir());
        } catch (Throwable t) {
            return Optional.empty();
        }
    }
}

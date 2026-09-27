package dev.spawnbotswrapper.inhabitants.adapter;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** A mod loader with exactly the mods a test installs. */
final class TestEnvironment implements Environment {

    final Map<String, String> mods = new HashMap<>();
    Path configDir;
    /** When set, every version lookup throws it (a broken loader). */
    RuntimeException failure;
    /** When set, asking for the config directory throws it. */
    RuntimeException configDirFailure;

    static TestEnvironment standard() {
        TestEnvironment e = new TestEnvironment();
        e.mods.put(UpstreamNames.MOD_PVP_BOT, "0.0.15");
        e.mods.put(UpstreamNames.MOD_HEROBOT, "1.21.11-1.4.3+v260315");
        return e;
    }

    TestEnvironment with(String modId, String version) {
        mods.put(modId, version);
        return this;
    }

    TestEnvironment without(String modId) {
        mods.remove(modId);
        return this;
    }

    @Override
    public Optional<String> modVersion(String modId) {
        if (failure != null) {
            throw failure;
        }
        return Optional.ofNullable(mods.get(modId));
    }

    @Override
    public Optional<Path> configDir() {
        if (configDirFailure != null) {
            throw configDirFailure;
        }
        return Optional.ofNullable(configDir);
    }
}

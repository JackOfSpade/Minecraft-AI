package dev.spawnbotswrapper.inhabitants.adapter;

import java.nio.file.Path;
import java.util.Optional;

/**
 * What the adapter asks the mod loader. A seam: in unit tests there is no loader, and the probe must be
 * testable with any combination of installed mods.
 */
interface Environment {

    /** Metadata version string of an installed mod, empty when that mod is not loaded. */
    Optional<String> modVersion(String modId);

    /** The game's config directory, when known (used only to READ PvP BOT's statistics opt-out). */
    Optional<Path> configDir();
}

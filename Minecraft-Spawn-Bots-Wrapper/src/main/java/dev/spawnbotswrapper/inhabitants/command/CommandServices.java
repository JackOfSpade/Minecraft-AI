package dev.spawnbotswrapper.inhabitants.command;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.engine.EngineControl;
import dev.spawnbotswrapper.inhabitants.engine.PopulationView;
import dev.spawnbotswrapper.inhabitants.mc.StructureLocator;

import java.util.List;
import java.util.function.Supplier;

/**
 * Everything the admin commands need, bundled so the command layer has no wiring logic of its own.
 * Built by the mod entrypoint once the server is running (commands are registered earlier, so they receive
 * a {@code Supplier<CommandServices>} that yields null until then).
 *
 * @param config          the CURRENT configuration (re-read on every use; replaced by {@code reloadConfig})
 * @param population      read-only queries over processed structures
 * @param engine          testing/admin operations (force-process, reset)
 * @param adapter         the PvP BOT adapter (status/version report, upstream setting names)
 * @param locator         finds structures at/near a position
 * @param reloadConfig    re-reads the config file; returns the warnings/errors produced (empty = clean)
 * @param addonVersion    version of this addon
 */
public record CommandServices(
        Supplier<InhabitantsConfig> config,
        PopulationView population,
        EngineControl engine,
        PvpBotOperations adapter,
        StructureLocator locator,
        Supplier<List<String>> reloadConfig,
        String addonVersion) {
}

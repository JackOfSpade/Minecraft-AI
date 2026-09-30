package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.adapter.ManagedSettings;
import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog;
import dev.spawnbotswrapper.inhabitants.command.CommandServices;
import dev.spawnbotswrapper.inhabitants.config.DisabledEnchantments;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.config.StartupCommands;
import dev.spawnbotswrapper.inhabitants.engine.PopulationEngine;
import dev.spawnbotswrapper.inhabitants.profile.ProfileGenerator;
import dev.spawnbotswrapper.inhabitants.spawn.DefaultSpawnPlanner;
import dev.spawnbotswrapper.inhabitants.store.PopulationStore;
import dev.spawnbotswrapper.inhabitants.util.SplitMix64;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Everything that exists for exactly one running server: the store, the engine and the command services,
 * built on the first tick after the server has started and torn down when it stops. A second world opened in
 * the same JVM (singleplayer) gets a brand-new session and therefore starts clean.
 * <p>
 * Nothing that touches PvP BOT happens before the first tick. PvP BOT finishes its own initialisation in its
 * SERVER_STARTED listener, there is no signal that says it has, and listener order between mods is not
 * something to lean on; the first END_SERVER_TICK is guaranteed to come after every SERVER_STARTED listener.
 * <p>
 * Every per-tick step runs inside a {@link StepGuard}: a bug in the addon costs that step for that tick and one
 * throttled log entry, never the server tick. Server thread only.
 */
public final class ServerSession {
    /** Chunks examined per tick for structure starts; the rest of a burst waits for the next tick. */
    public static final int DRAIN_CHUNKS_PER_TICK = 128;

    /** What every session shares with the mod entrypoint. */
    public record Shared(ConfigHolder config, PvpBotOperations adapter, StructureDetector detector,
                         StructureLocator locator, StepGuard guard, String addonVersion, Logger log,
                         McTpsGateway tps) {
    }

    private final MinecraftServer server;
    private final Shared shared;
    private final TickSchedule saveSchedule;
    private boolean initialised;
    private PopulationStore store;
    private PopulationEngine engine;
    private CommandServices services;

    public ServerSession(MinecraftServer server, Shared shared) {
        this.server = server;
        this.shared = shared;
        this.saveSchedule = new TickSchedule(() -> shared.config().get().processing.saveIntervalTicks);
    }

    public MinecraftServer server() {
        return server;
    }

    /** The command services, or null until the first tick has initialised the session (or if that failed). */
    public CommandServices services() {
        return services;
    }

    /** One server tick: initialise on the first call, then feed detected structures to the engine and tick it. */
    public void tick() {
        if (!initialised) {
            initialised = true;
            initialise();
        }
        // Cheap when nothing changed; re-applies the managed PvP BOT settings whenever PvP BOT reloads its own.
        shared.guard().run("PvP BOT settings policy", this::manageUpstreamSettings);
        if (engine == null) {
            return;
        }
        StepGuard guard = shared.guard();
        InhabitantsConfig config = shared.config().get();
        if (config.enabled && store.usable()) {
            guard.run("structure intake", () -> shared.detector().drain(server, DRAIN_CHUNKS_PER_TICK, engine::submit));
        } else {
            shared.detector().discardPending();
        }
        guard.run("population engine", engine::tick);
        if (saveSchedule.due(server.getTickCount())) {
            guard.run("saving population data", this::saveIfDirty);
        }
    }

    /** Server is stopping: write everything that has not been written yet. */
    public void shutdown() {
        if (engine != null) {
            shared.guard().run("final save", engine::shutdown);
        } else if (store != null && store.usable()) {
            shared.guard().run("final save", store::flush);
        }
    }

    private void saveIfDirty() {
        if (!store.saveIfDirty()) {
            throw new IllegalStateException("the population data could not be written to disk");
        }
    }

    private void initialise() {
        guarded("startup commands", this::runStartupCommands);
        // The backend goes in first so the probe's report is about the spawn path that will actually be used.
        applyBackend();
        // Handed over BEFORE the probe, which applies them so its report describes what the bots will run with.
        guarded("PvP BOT settings policy", this::manageUpstreamSettings);
        guarded("PvP BOT probe", this::probeUpstream);
        guarded("audit of PvP BOT settings", this::auditUpstreamSettings);
        store = openStore();
        if (store != null) {
            guarded("engine start-up", this::startEngine);
        }
        if (engine == null) {
            shared.log().error("Population is DISABLED: the engine could not be started (see the messages above).");
        }
    }

    /**
     * Runs {@code connection.startupCommands} (see its doc comment) as the server console, silently, before
     * anything else runs -- so a command that itself depends on another mod's own commands already being
     * registered (true for every mod's {@code CommandRegistrationCallback}, which always fires before the
     * first tick, well before this) still works. One command failing is logged and does not stop the rest.
     */
    private void runStartupCommands() {
        // Managed PvP BOT settings (crit-fall-ticks) first, then the operator's list; see StartupCommands.
        List<String> commands = StartupCommands.plan(shared.config().get());
        if (commands.isEmpty()) {
            return;
        }
        CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
        for (String command : commands) {
            try {
                int result = server.getCommands().getDispatcher().execute(command, source);
                shared.log().info("startup command '{}' ran (result {})", command, result);
            } catch (OutOfMemoryError e) {
                throw e;
            } catch (Throwable t) {
                shared.log().warn("startup command '{}' failed: {}", command, t.toString());
            }
        }
    }

    /** Tells the adapter which PvP BOT settings the configuration wants held (none while the addon is disabled). */
    private void manageUpstreamSettings() {
        InhabitantsConfig config = shared.config().get();
        shared.adapter().aggroHunterEnabled(config.enabled && config.aggro != null && config.aggro.enabled);
        shared.adapter().manageSettings(config.enabled ? managedSettings(config.pvpbotSettings) : ManagedSettings.NONE);
    }

    static ManagedSettings managedSettings(InhabitantsConfig.PvpbotSettings s) {
        return s == null ? ManagedSettings.NONE : new ManagedSettings(s.maxTargetDistance, s.rangedMinRange,
                s.rangedOptimalRange, s.rangedMaxRange, s.autoEquipWeapon, s.autoTargetEnabled, s.rangedRetreatOnClose,
                s.meleeRange, s.bowMinDrawTime);
    }

    private void probeUpstream() {
        PvpBotOperations.Status status = shared.adapter().probe(server);
        if (status.usable()) {
            shared.log().info("PvP BOT integration: {} ({})", status.availability(), status.summary());
        } else {
            shared.log().warn("PvP BOT integration is UNAVAILABLE, the addon will not populate anything: {}", status.summary());
        }
    }

    /** Opens and loads the per-world store; null (after logging why) when even that is not possible. */
    private PopulationStore openStore() {
        Logger log = shared.log();
        Path directory = WorldPaths.dataDirectory(server);
        try {
            PopulationStore opened = new PopulationStore(directory);
            PopulationStore.LoadReport report = opened.load();
            for (String message : report.messages()) {
                if (report.usable()) {
                    log.info("population store: {}", message);
                } else {
                    log.warn("population store: {}", message);
                }
            }
            if (!report.usable()) {
                log.error("Population is DISABLED for this world: the saved inhabitant data in {} could not be read, "
                        + "and continuing with an empty store would roll every structure again and duplicate its "
                        + "inhabitants. Fix or move that folder and restart. Reasons are listed above.", directory);
            }
            return opened;
        } catch (RuntimeException e) {
            log.error("Population is DISABLED for this world: the data folder {} could not be opened", directory, e);
            return null;
        }
    }

    private void startEngine() {
        PvpBotOperations adapter = shared.adapter();
        ServerAccess access = new McServerAccess(server);
        ProfileApplier applier = new ProfileApplier(adapter::isBotEntity,
                () -> DisabledEnchantments.parse(shared.config().get().profiles.disabledEnchantments));
        McBotGateway bots = new McBotGateway(access, adapter, applier, shared.config());
        engine = new PopulationEngine(
                shared.config(),
                store,
                bots,
                new McWorldGateway(access),
                McClock.of(server),
                new ProfileGenerator(() -> shared.config().get().profiles),
                new DefaultSpawnPlanner(() -> shared.config().get().spawning),
                shared.tps(),
                () -> SplitMix64.fromEntropy().nextLong());
        services = new CommandServices(shared.config(), engine.view(), engine, adapter,
                shared.locator(), this::reloadConfig, shared.addonVersion());
    }

    /** {@code reloadConfig} of the command services: swap the config, tell everyone, report what happened. */
    private List<String> reloadConfig() {
        ConfigHolder.Reload reload = shared.config().reload();
        List<String> messages = new ArrayList<>(reload.messages());
        if (reload.swapped()) {
            boolean applied = shared.guard().run("applying the reloaded configuration", () -> {
                applyBackend();
                shared.detector().forgetRecent();
                if (engine != null) {
                    engine.onConfigReloaded();
                }
            });
            if (!applied) {
                messages.add("the new configuration was loaded but applying it failed; see the server log");
            }
        }
        return messages;
    }

    private void applyBackend() {
        String backend = shared.config().get().spawning.backend;
        boolean applied = AdapterBackend.apply(shared.adapter(), backend);
        if (!applied && backend != null && !"AUTO".equalsIgnoreCase(backend)) {
            shared.log().warn("spawning.backend is {}, but the PvP BOT adapter offers no way to select a backend; "
                    + "it will choose automatically", backend);
        }
    }

    /** Compares PvP BOT's setting names with the catalogue and logs any drift once per session. */
    private void auditUpstreamSettings() {
        if (!shared.adapter().status().usable()) {
            return;
        }
        Set<String> names = shared.adapter().discoverUpstreamSettingNames();
        if (names.isEmpty()) {
            return;
        }
        SettingCatalog.AuditReport audit = SettingCatalog.audit(names);
        if (!audit.clean()) {
            shared.log().warn("PvP BOT settings differ from the ones this addon was audited against ({}): "
                            + "settings the addon does not know about: {}; settings it expected but did not find: {}. "
                            + "The addon keeps working, but new settings are not randomised per bot.",
                    SettingCatalog.auditedVersion(), audit.unknownToCatalog(), audit.missingUpstream());
        }
    }

    private void guarded(String step, Runnable body) {
        shared.guard().run(step, body);
    }
}

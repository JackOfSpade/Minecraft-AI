package dev.spawnbotswrapper.inhabitants;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotAdapter;
import dev.spawnbotswrapper.inhabitants.command.InhabitantsCommand;
import dev.spawnbotswrapper.inhabitants.config.ConfigIO;
import dev.spawnbotswrapper.inhabitants.mc.AggroDriver;
import dev.spawnbotswrapper.inhabitants.mc.CombatLogger;
import dev.spawnbotswrapper.inhabitants.mc.ConfigHolder;
import dev.spawnbotswrapper.inhabitants.mc.GameMessageFilter;
import dev.spawnbotswrapper.inhabitants.mc.LateTickPhase;
import dev.spawnbotswrapper.inhabitants.mc.McStructureLocator;
import dev.spawnbotswrapper.inhabitants.mc.McTpsGateway;
import dev.spawnbotswrapper.inhabitants.mc.RangedFire;
import dev.spawnbotswrapper.inhabitants.mc.ServerSession;
import dev.spawnbotswrapper.inhabitants.mc.StepGuard;
import dev.spawnbotswrapper.inhabitants.mc.StructureDetector;
import dev.spawnbotswrapper.inhabitants.mc.StructureSnapshotBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entrypoint. Wires the addon together and hooks the server lifecycle; all real work lives elsewhere.
 * <p>
 * Timeline, and why it is shaped this way:
 * <ol>
 *   <li><b>Mod initialisation.</b> Load the config, create the PvP BOT adapter (no probing yet), register the
 *       structure detector and the commands. The detector has to be listening this early: chunk events fire
 *       for the spawn area before the server reports itself started.</li>
 *   <li><b>SERVER_STARTED.</b> Only creates a {@link ServerSession}. Nothing here touches PvP BOT, whose own
 *       start-up runs in its SERVER_STARTED listener and gives no completion signal.</li>
 *   <li><b>First END_SERVER_TICK.</b> The session initialises: probe PvP BOT, open the store, build the engine.
 *       Every later tick feeds detected structures to the engine and ticks it. Tick events do not fire while a
 *       dedicated server is paused with nobody online, so nothing wall-clock based is scheduled from them.</li>
 *   <li><b>SERVER_STOPPING</b> flushes the store; <b>SERVER_STOPPED</b> drops all per-server state so the next
 *       world opened in this JVM starts clean.</li>
 * </ol>
 */
public final class InhabitantsMod implements ModInitializer {
    public static final String MOD_ID = "pvpbot_inhabitants";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final String CONFIG_FILE = MOD_ID + ".json";

    /** The running entrypoint; only read by {@link #servicesOf}. */
    private static volatile InhabitantsMod instance;

    private ServerSession.Shared shared;
    private volatile ServerSession session;
    private final McTpsGateway tps = new McTpsGateway();
    private final CombatLogger combat = new CombatLogger(() -> session, LOGGER);
    /** The aggro range (inhabitants notice players within a short range, chase far); see AggroController. */
    private final AggroDriver aggro = new AggroDriver(() -> session, LOGGER);
    private final RangedFire rangedFire = new RangedFire(() -> session, LOGGER);

    @Override
    public void onInitialize() {
        instance = this;
        ConfigHolder config = new ConfigHolder(FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE));
        logConfigLoad(config, config.loadInitial());

        String version = addonVersion();
        PvpBotAdapter adapter = new PvpBotAdapter(version);
        StepGuard guard = StepGuard.logging(LOGGER);
        StructureSnapshotBuilder snapshots = new StructureSnapshotBuilder();
        StructureDetector detector = new StructureDetector(snapshots, guard);
        shared = new ServerSession.Shared(config, adapter, detector, new McStructureLocator(snapshots), guard, version, LOGGER, tps);

        detector.register();
        registerCommands();
        GameMessageFilter.register(() -> {
            ServerSession current = session;
            return current == null ? null : current.services();
        });
        combat.register();
        combat.aggroState(aggro::describe);
        rangedFire.register();
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(this::onEndServerTick);
        // After PvP BOT's own bot tick (default phase), so the walk back is the last input written and the crossbow
        // trigger sees the state PvP BOT left this tick (its target, its mode, the loaded crossbow); see LateTickPhase.
        LateTickPhase.register(ServerTickEvents.END_SERVER_TICK, this::onLateServerTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(this::onServerStopped);
        LOGGER.info("PvP BOT Inhabitants {} loaded (config: {})", version, config.file());
    }

    /**
     * The command services of the session running on {@code server}, or null before its first tick has initialised
     * them. A seam for the real-server GameTests (they drive the population engine like an operator would); nothing
     * in the addon itself calls it.
     */
    public static dev.spawnbotswrapper.inhabitants.command.CommandServices servicesOf(MinecraftServer server) {
        InhabitantsMod mod = instance;
        ServerSession current = mod == null ? null : mod.session;
        return current != null && current.server() == server ? current.services() : null;
    }

    private static void logConfigLoad(ConfigHolder holder, ConfigIO.LoadResult result) {
        if (result.created()) {
            LOGGER.info("Wrote a default configuration to {}", holder.file());
        }
        for (String warning : result.warnings()) {
            LOGGER.warn("config: {}", warning);
        }
        if (result.fatalError() != null) {
            LOGGER.error("PvP BOT Inhabitants could not read its configuration {}: {}", holder.file(), result.fatalError());
        }
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // Always register: PermissionLevels gates every node against the LIVE config on every use (so
            // debugCommands=false still refuses everybody), and /inhabitants reload is deliberately exempt
            // from that flag so a config edit back to debugCommands=true is never a one-way trap that needs
            // a server restart to recover from. Registering only when the config happens to allow it at
            // this moment (command-tree build time, e.g. server start) would remove that escape hatch
            // entirely whenever the server starts with debugCommands already false.
            // A broken command tree must not take the whole command manager (and with it the server) down.
            try {
                InhabitantsCommand.register(dispatcher, () -> {
                    ServerSession current = session;
                    return current == null ? null : current.services();
                });
            } catch (RuntimeException e) {
                LOGGER.error("The /inhabitants commands could not be registered", e);
            }
        });
    }

    private void onServerStarted(MinecraftServer server) {
        session = new ServerSession(server, shared);
        if (shared.config().get().hideLocatorBar) {
            shared.guard().run("hide locator bar", () ->
                    server.overworld().getGameRules().set(GameRules.LOCATOR_BAR, false, server));
        }
        LOGGER.info("Server started; the PvP BOT integration is probed on the first tick");
    }

    private void onEndServerTick(MinecraftServer server) {
        // Measured first and unconditionally, so the addon's own tick cost is included in what an operator
        // would see anyway, and a throw further down never leaves a gap in the sample window.
        tps.recordTick();
        ServerSession current = session;
        if (current != null && current.server() == server) {
            shared.guard().run("server tick", current::tick);
            combat.tick(server.getTickCount());
        }
    }

    private void onLateServerTick(MinecraftServer server) {
        ServerSession current = session;
        if (current != null && current.server() == server) {
            shared.guard().run("aggro range", () -> aggro.tick(server));
            rangedFire.tick(server);
        }
    }

    private void onServerStopping(MinecraftServer server) {
        ServerSession current = session;
        if (current != null && current.server() == server) {
            combat.flush(server.getTickCount());
            aggro.reset();
            rangedFire.reset();
            current.shutdown();
        }
    }

    private void onServerStopped(MinecraftServer server) {
        ServerSession current = session;
        if (current != null && current.server() == server) {
            session = null;
        }
        shared.detector().reset();
    }

    private static String addonVersion() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}

package dev.spawnbotswrapper.inhabitants;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotAdapter;
import dev.spawnbotswrapper.inhabitants.command.InhabitantsCommand;
import dev.spawnbotswrapper.inhabitants.config.ConfigIO;
import dev.spawnbotswrapper.inhabitants.mc.AggroDriver;
import dev.spawnbotswrapper.inhabitants.mc.CombatLogger;
import dev.spawnbotswrapper.inhabitants.mc.ConfigHolder;
import dev.spawnbotswrapper.inhabitants.mc.EatGate;
import dev.spawnbotswrapper.inhabitants.mc.GameMessageFilter;
import dev.spawnbotswrapper.inhabitants.mc.HumanAimDriver;
import dev.spawnbotswrapper.inhabitants.mc.IssuedItemGuard;
import dev.spawnbotswrapper.inhabitants.mc.LateTickPhase;
import dev.spawnbotswrapper.inhabitants.mc.OffhandPolicy;
import dev.spawnbotswrapper.inhabitants.mc.OutOfAmmoGapCloser;
import dev.spawnbotswrapper.inhabitants.mc.McStructureLocator;
import dev.spawnbotswrapper.inhabitants.mc.McTpsGateway;
import dev.spawnbotswrapper.inhabitants.mc.MeleeLegality;
import dev.spawnbotswrapper.inhabitants.mc.RangedFire;
import dev.spawnbotswrapper.inhabitants.mc.ServerSession;
import dev.spawnbotswrapper.inhabitants.mc.StepGuard;
import dev.spawnbotswrapper.inhabitants.mc.StructureDetector;
import dev.spawnbotswrapper.inhabitants.mc.StructureSnapshotBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
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
    /** The line-of-sight hunter (inhabitants notice, chase, search for and walk back from players); see AggroController. */
    /** Human aim: inhabitants turn their heads at human speed and shoot only where they really point (see HumanAim). */
    private final HumanAimDriver humanAim = new HumanAimDriver(() -> session, LOGGER);
    private final AggroDriver aggro = new AggroDriver(() -> session, LOGGER, humanAim);
    /** No cheating: vetoes melee hits by inhabitants that a human client could not make (through walls, beyond reach). */
    private final MeleeLegality meleeLegality = new MeleeLegality(() -> session, LOGGER, aggro::mayAttackPlayer, humanAim);
    private final RangedFire rangedFire = new RangedFire(() -> session, LOGGER, aggro::mayAttackPlayer, humanAim);
    private final OutOfAmmoGapCloser gapCloser = new OutOfAmmoGapCloser(() -> session, LOGGER);
    /** The offhand of every inhabitant: the best shield, else a totem (see OffhandRule); PvP BOT auto-totem is managed off. */
    private final OffhandPolicy offhandPolicy = new OffhandPolicy(() -> session, LOGGER);
    /** Vanilla's rule that food is only eaten below a full food bar, which PvP BOT's own eating skips. */
    private final EatGate eatGate = new EatGate(LOGGER);

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
        IssuedItemGuard.register(LOGGER);
        combat.aggroState(aggro::describe);
        combat.meleeVetoState(meleeLegality::describe);
        meleeLegality.register();
        humanAim.register();
        // A real death (any cause) of an inhabitant spends its structure slot for good; removal by this addon is never a death.
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            ServerSession current = session;
            if (current != null) {
                shared.guard().run("inhabitant death", () -> current.onLivingDeath(entity));
            }
        });
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

    /**
     * Where an inhabitant is in the aggro hunt (IDLE, CHASE, PURSUE, SEARCH, RETURN), or IDLE when nothing runs. A seam
     * for the real-server GameTests; nothing in the addon itself calls it.
     */
    public static String aggroPhaseOf(String botName) {
        InhabitantsMod mod = instance;
        return mod == null ? "IDLE" : mod.aggro.phaseOf(botName).name();
    }

    /**
     * The aggro route planner's statistics {plans made, plans that reached their goal, detached helper mobs alive}. A seam
     * for the real-server GameTests; nothing in the addon itself calls it.
     */
    public static long[] aggroPlannerStats() {
        InhabitantsMod mod = instance;
        return mod == null ? new long[3] : mod.aggro.plannerStats();
    }

    /** What the aggro hunter is doing about an inhabitant, as the status text. A seam for the real-server GameTests. */
    public static String aggroDescribe(String botName) {
        InhabitantsMod mod = instance;
        String text = mod == null ? null : mod.aggro.describe(botName);
        return text == null ? "?" : text;
    }

    /** The unique ids of the aggro route planner's helper mobs (none may ever be found in a level). A seam for the GameTests. */
    public static java.util.List<java.util.UUID> aggroPlannerHelperIds() {
        InhabitantsMod mod = instance;
        return mod == null ? java.util.List.of() : mod.aggro.plannerHelperIds();
    }

    /** The aggro home anchor of an inhabitant as {x, y, z}, or null. A seam for the real-server GameTests. */
    public static double[] aggroHomeOf(String botName) {
        InhabitantsMod mod = instance;
        return mod == null ? null : mod.aggro.homeOf(botName);
    }

    /** The player an inhabitant has a CONFIRMED engagement with (the reaction time served), or null. A seam for the GameTests. */
    public static String aggroConfirmedTarget(String botName) {
        InhabitantsMod mod = instance;
        return mod == null ? null : mod.aggro.confirmedTarget(botName);
    }

    /** How many vanilla vibration listeners the inhabitants have registered (none may leak). A seam for the GameTests. */
    public static int hearingListeners() {
        InhabitantsMod mod = instance;
        return mod == null ? 0 : mod.aggro.hearingListeners();
    }

    /** Melee blows on players vetoed for want of a confirmed engagement, and loaded crossbows held back for the same reason: {melee, crossbow}. A seam for the GameTests. */
    public static long[] reactionHolds() {
        InhabitantsMod mod = instance;
        return mod == null ? new long[2] : new long[]{mod.meleeLegality.reactionVetoes(), mod.rangedFire.shotsHeldBack()};
    }

    /**
     * Pins the tracked aim of an inhabitant to the rotation its entity has right now (no turning): what a test does after it
     * places a bot facing a direction, so the human turn speed does not turn the fixture into a slow swing. A seam for the GameTests.
     */
    public static boolean aimSnap(String botName) {
        InhabitantsMod mod = instance;
        return mod != null && mod.humanAim.snap(botName);
    }

    /** {yaw, pitch, wantedYaw, wantedPitch, errorDeg, settledSeconds} of the tracked aim of an inhabitant, or null. A seam for the GameTests. */
    public static double[] aimOf(String botName) {
        InhabitantsMod mod = instance;
        return mod == null ? null : mod.humanAim.aimOf(botName);
    }

    /** Human aim counters {arrows re-aimed, crossbow shots held for want of aim, melee blows vetoed for want of aim}. A seam for the GameTests. */
    public static long[] aimCounters() {
        InhabitantsMod mod = instance;
        return mod == null ? new long[3] : new long[]{mod.humanAim.arrowsReaimed(), mod.rangedFire.shotsHeldOnAim(), mod.meleeLegality.aimVetoes()};
    }

    private static void logConfigLoad(ConfigHolder holder, ConfigIO.LoadResult result) {
        if (result.created()) {
            LOGGER.info("Wrote a default configuration to {}", holder.file());
        }
        for (String warning : result.warnings()) {
            LOGGER.warn("config: {}", warning);
        }
        for (String note : result.notes()) {
            LOGGER.info(note);
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
        session.aggroEngaged(aggro::engaged);
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
        }
    }

    /**
     * Everything that runs behind PvP BOT's own end-of-tick work: the aggro hunter (its steering is the last input
     * written), the crossbow trigger (PvP BOT's target, its mode) and the combat diagnostics (the selected slot and
     * item use it left).
     */
    private void onLateServerTick(MinecraftServer server) {
        ServerSession current = session;
        if (current != null && current.server() == server) {
            shared.guard().run("aggro hunter", () -> aggro.tick(server));
        }
        gapCloser.tick(server);
        offhandPolicy.tick(server);
        if (current != null && current.server() == server) {
            // Behind every writer of a look direction (PvP BOT, the hunt's steering, the gap closer): whatever they asked the head
            // to look at, it turns there at human speed. The crossbow trigger below reads where it really looks.
            humanAim.tick(server);
        }
        rangedFire.tick(server);
        if (current != null && current.server() == server) {
            shared.guard().run("eat gate", () -> eatGate.tick(server));
        }
        if (current != null && current.server() == server) {
            combat.tick(server.getTickCount());
        }
    }

    private void onServerStopping(MinecraftServer server) {
        ServerSession current = session;
        if (current != null && current.server() == server) {
            combat.flush(server.getTickCount());
            aggro.reset();
            humanAim.reset();
            rangedFire.reset();
            gapCloser.reset();
            offhandPolicy.reset();
            eatGate.reset();
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

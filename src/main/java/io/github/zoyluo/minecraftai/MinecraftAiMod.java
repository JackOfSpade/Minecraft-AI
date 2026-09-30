package io.github.zoyluo.minecraftai;

import io.github.zoyluo.minecraftai.mining.BreakVerdictCache;
import io.github.zoyluo.minecraftai.brain.AmbientConversationCoordinator;
import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.brain.ChatCaptureListener;
import io.github.zoyluo.minecraftai.brain.ChatRecipientRouter;
import io.github.zoyluo.minecraftai.brain.PoiAdvisor;
import io.github.zoyluo.minecraftai.command.MinecraftAiCommand;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.BotLogWriter;
import io.github.zoyluo.minecraftai.inventory.BotInventoryScreenHandler;
import io.github.zoyluo.minecraftai.network.MinecraftAiServerNetworking;
import io.github.zoyluo.minecraftai.network.payload.AIPayloads;
import io.github.zoyluo.minecraftai.mode.CapabilityPolicy;
import io.github.zoyluo.minecraftai.mining.assist.BotEdits;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistRuntime;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.observe.TpsGuard;
import io.github.zoyluo.minecraftai.persist.BotPersistence;
import io.github.zoyluo.minecraftai.runtime.RuntimeLifecycleCoordinator;
import io.github.zoyluo.minecraftai.task.BotTickCoordinator;
import io.github.zoyluo.minecraftai.task.TaskManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Arrays;

public class MinecraftAiMod implements ModInitializer {
    public static final String MOD_ID = "minecraftai";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        BotInventoryScreenHandler.initialize();
        // Optional: answers VeinMiner's "veinminer.use" permission (no for bots); a no-op without fabric-permissions-api.
        io.github.zoyluo.minecraftai.integration.PermissionsIntegration.registerIfPresent();
        warnIfVeinMinerCanBeUsedByBots();
        MinecraftAiConfig config = MinecraftAiConfig.load();
        BotLogWriter.INSTANCE.start(config);
        BotLog.lifecycle("mod_loaded", "version", getModVersion());
        BotLog.config("config_loaded",
                "profile", config.profile().configValue(),
                "configured_operator_capabilities", config.operatorCapabilities(),
                "effective_capabilities", Arrays.stream(PrivilegedCapability.values())
                        .filter(capability -> CapabilityPolicy.decide(
                                config.profile(), config.operatorCapabilities(), capability).allowed())
                        .map(Enum::name)
                        .toList(),
                "llm_model", config.llm().model(),
                "perception_radius", config.perception().radius(),
                "nav_lookahead", config.nav().lookahead(),
                "pickup_force_radius", config.pickup().forceRadiusH(),
                "behaviour", config.behaviourOrDefaults(),
                "logging_enabled", config.logging().enabled());
        // Mining assist: optional "miningAssist" section of the same minecraftai.json (separate parse pass; never throws).
        MiningAssistRuntime.load(FabricLoader.getInstance().getConfigDir().resolve("minecraftai.json"));
        Path assistSidecar = BotEdits.defaultSidecarPath();

        LOGGER.info("================================");
        LOGGER.info("  MinecraftAi v{} loaded", getModVersion());
        LOGGER.info("  Mode: M6 structured logs over M5 tasks");
        LOGGER.info("================================");

        BrainCoordinator.INSTANCE.configure(config);
        ChatRecipientRouter.INSTANCE.configure(config);
        AmbientConversationCoordinator.INSTANCE.configure(config);
        PoiAdvisor.INSTANCE.configure(config);
        ChatCaptureListener.register();
        AIPayloads.register();
        MinecraftAiServerNetworking.INSTANCE.register();

        // The Baritone break policy caches a verdict per block from tag membership: drop it when the server starts and when tags
        // are loaded or reloaded (BreakVerdictCache names no Baritone type, so this stays free with the legacy engine).
        ServerLifecycleEvents.SERVER_STARTING.register(server -> BreakVerdictCache.invalidate());
        CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> BreakVerdictCache.invalidate());
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            BotLog.lifecycle("server_started", "motd", server.getMotd());
            RuntimeLifecycleCoordinator.INSTANCE.onServerStarted(server, config);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(RuntimeLifecycleCoordinator.INSTANCE::onServerStopping);
        // Damage records (who hurt whom, in level game time) and the teleport counters are per server run.
        io.github.zoyluo.minecraftai.entity.RecentDamage.register();
        io.github.zoyluo.minecraftai.task.HostileBotLedger.install();
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            io.github.zoyluo.minecraftai.entity.RecentDamage.clear();
            io.github.zoyluo.minecraftai.entity.TeleportAudit.clearAll();
            io.github.zoyluo.minecraftai.task.HostileBotLedger.clearAll();
            io.github.zoyluo.minecraftai.task.SharedVision.clearAll();
            io.github.zoyluo.minecraftai.task.AggroSense.clearAll();
        });
        ServerTickEvents.START_SERVER_TICK.register(server -> MiningAssistRuntime.beginTick());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            TpsGuard.INSTANCE.tick(server);
            TaskManager.INSTANCE.tickAll(server);
            BotTickCoordinator.INSTANCE.tick(server);
            io.github.zoyluo.minecraftai.task.HostileBotIntent.tick(server);
            AmbientConversationCoordinator.INSTANCE.tick(server);
            MinecraftAiServerNetworking.INSTANCE.tick(server);
            io.github.zoyluo.minecraftai.log.DiagnosticLogger.INSTANCE.tick(server);
            io.github.zoyluo.minecraftai.log.InventoryAudit.INSTANCE.tick(server);
            io.github.zoyluo.minecraftai.mode.CapabilityRuntime.flushDue(server.getTickCount());
            if (server.getTickCount() > 0 && server.getTickCount() % 6000 == 0) {
                BotPersistence.INSTANCE.saveAllAsync(server);
            }
            BotEdits.snapshotIfDue(server.getTickCount(), assistSidecar);
            // One-shot idle-moment build a few seconds after start (lazy build stays the fallback).
            io.github.zoyluo.minecraftai.loot.RuntimeDropIndex.tickWarmup(server,
                    () -> io.github.zoyluo.minecraftai.task.TaskManager.INSTANCE.activeCount() == 0,
                    () -> !io.github.zoyluo.minecraftai.observe.TpsGuard.INSTANCE.degraded(server));
            // Keep last: feeds this tick's measured work to the mining assist's TickHeadroom.
            MiningAssistRuntime.endTick(server.getTickCount());
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                MinecraftAiCommand.register(dispatcher, registryAccess));
    }

    /** Logged once at startup: VeinMiner without permissionRestricted lets bots vein-mine. Never throws. */
    private static void warnIfVeinMinerCanBeUsedByBots() {
        try {
            FabricLoader loader = FabricLoader.getInstance();
            boolean loaded = loader.isModLoaded(io.github.zoyluo.minecraftai.integration.VeinMinerConfigCheck.MOD_ID);
            String settings = loaded
                    ? io.github.zoyluo.minecraftai.integration.VeinMinerConfigCheck.readSettings(loader.getConfigDir())
                    : null;
            String warning = io.github.zoyluo.minecraftai.integration.VeinMinerConfigCheck.warning(loaded, settings);
            if (warning != null) {
                LOGGER.warn("[Minecraft-AI] {}", warning);
            }
        } catch (RuntimeException ignored) {
            // A diagnostic must never stop the mod from loading.
        }
    }

    private static String getModVersion() {
        return FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}

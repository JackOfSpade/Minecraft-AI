package io.github.zoyluo.aibot;

import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.brain.ChatCaptureListener;
import io.github.zoyluo.aibot.brain.ChatRecipientRouter;
import io.github.zoyluo.aibot.command.AIBotCommand;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.BotLogWriter;
import io.github.zoyluo.aibot.inventory.BotInventoryScreenHandler;
import io.github.zoyluo.aibot.network.AIBotServerNetworking;
import io.github.zoyluo.aibot.network.payload.AIPayloads;
import io.github.zoyluo.aibot.mode.CapabilityPolicy;
import io.github.zoyluo.aibot.mining.assist.BotEdits;
import io.github.zoyluo.aibot.mining.assist.MiningAssistRuntime;
import io.github.zoyluo.aibot.mode.PrivilegedCapability;
import io.github.zoyluo.aibot.observe.TpsGuard;
import io.github.zoyluo.aibot.persist.BotPersistence;
import io.github.zoyluo.aibot.runtime.RuntimeLifecycleCoordinator;
import io.github.zoyluo.aibot.task.BotTickCoordinator;
import io.github.zoyluo.aibot.task.TaskManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Arrays;

public class AIBotMod implements ModInitializer {
    public static final String MOD_ID = "aibot";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        BotInventoryScreenHandler.initialize();
        AIBotConfig config = AIBotConfig.load();
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
                "deepseek_model", config.deepseek().model(),
                "perception_radius", config.perception().radius(),
                "nav_lookahead", config.nav().lookahead(),
                "pickup_force_radius", config.pickup().forceRadiusH(),
                "logging_enabled", config.logging().enabled());
        // Mining assist: optional "miningAssist" section of the same aibot.json (separate parse pass; never throws).
        MiningAssistRuntime.load(FabricLoader.getInstance().getConfigDir().resolve("aibot.json"));
        Path assistSidecar = BotEdits.defaultSidecarPath();

        LOGGER.info("================================");
        LOGGER.info("  AIBot v{} loaded", getModVersion());
        LOGGER.info("  Mode: M6 structured logs over M5 tasks");
        LOGGER.info("================================");

        BrainCoordinator.INSTANCE.configure(config);
        ChatRecipientRouter.INSTANCE.configure(config);
        ChatCaptureListener.register();
        AIPayloads.register();
        AIBotServerNetworking.INSTANCE.register();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            BotLog.lifecycle("server_started", "motd", server.getServerMotd());
            RuntimeLifecycleCoordinator.INSTANCE.onServerStarted(server, config);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(RuntimeLifecycleCoordinator.INSTANCE::onServerStopping);
        ServerTickEvents.START_SERVER_TICK.register(server -> MiningAssistRuntime.beginTick());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            TpsGuard.INSTANCE.tick(server);
            TaskManager.INSTANCE.tickAll(server);
            BotTickCoordinator.INSTANCE.tick(server);
            AIBotServerNetworking.INSTANCE.tick(server);
            io.github.zoyluo.aibot.log.DiagnosticLogger.INSTANCE.tick(server);
            if (server.getTicks() > 0 && server.getTicks() % 6000 == 0) {
                BotPersistence.INSTANCE.saveAllAsync(server);
            }
            BotEdits.snapshotIfDue(server.getTicks(), assistSidecar);
            // Keep last: feeds this tick's measured work to the mining assist's TickHeadroom.
            MiningAssistRuntime.endTick(server.getTicks());
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                AIBotCommand.register(dispatcher, registryAccess));
    }

    private static String getModVersion() {
        return FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}

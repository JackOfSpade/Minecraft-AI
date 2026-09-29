package io.github.zoyluo.minecraftai;

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
            AmbientConversationCoordinator.INSTANCE.tick(server);
            MinecraftAiServerNetworking.INSTANCE.tick(server);
            io.github.zoyluo.minecraftai.log.DiagnosticLogger.INSTANCE.tick(server);
            io.github.zoyluo.minecraftai.log.InventoryAudit.INSTANCE.tick(server);
            io.github.zoyluo.minecraftai.mode.CapabilityRuntime.flushDue(server.getTicks());
            if (server.getTicks() > 0 && server.getTicks() % 6000 == 0) {
                BotPersistence.INSTANCE.saveAllAsync(server);
            }
            BotEdits.snapshotIfDue(server.getTicks(), assistSidecar);
            // Keep last: feeds this tick's measured work to the mining assist's TickHeadroom.
            MiningAssistRuntime.endTick(server.getTicks());
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                MinecraftAiCommand.register(dispatcher, registryAccess));
    }

    private static String getModVersion() {
        return FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}

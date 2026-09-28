package io.github.zoyluo.aibot.gametest;

import io.github.zoyluo.aibot.command.AIBotTestSubcommand;
import io.github.zoyluo.aibot.command.AIBotVerifySubcommand;
import io.github.zoyluo.aibot.mining.assist.MiningAssistRuntime;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.command.CommandManager;

/** Test-only command harness. This class and both subcommands are excluded from the production jar. */
public final class AIBotHarnessTestMod implements ModInitializer {
    @Override
    public void onInitialize() {
        // GameTests and verify scenarios run with the mining assist OFF unless a test opts a bot in with
        // MiningAssistRuntime.forceEnable(uuid). An explicit env or file mode still wins. AIBotMod loads
        // the config first (this mod depends on it) and setHarnessDefaultOff re-parses either way.
        MiningAssistRuntime.setHarnessDefaultOff(true);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("aibot")
                        .then(AIBotTestSubcommand.build(registryAccess))
                        .then(AIBotVerifySubcommand.build())
                        .then(AIBotRestartHarnessCommand.build())));
        ServerTickEvents.END_SERVER_TICK.register(AIBotVerifySubcommand::tick);
    }
}

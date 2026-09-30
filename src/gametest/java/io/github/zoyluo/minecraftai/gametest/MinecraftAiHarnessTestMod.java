package io.github.zoyluo.minecraftai.gametest;

import io.github.zoyluo.minecraftai.command.MinecraftAiTestSubcommand;
import io.github.zoyluo.minecraftai.command.MinecraftAiVerifySubcommand;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistRuntime;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;

/** Test-only command harness. This class and both subcommands are excluded from the production jar. */
public final class MinecraftAiHarnessTestMod implements ModInitializer {
    @Override
    public void onInitialize() {
        // GameTests and verify scenarios run with the mining assist OFF unless a test opts a bot in with
        // MiningAssistRuntime.forceEnable(uuid). An explicit env or file mode still wins. MinecraftAiMod loads
        // the config first (this mod depends on it) and setHarnessDefaultOff re-parses either way.
        MiningAssistRuntime.setHarnessDefaultOff(true);
        // Likewise realistic perception (docs/PERCEPTION.md): the legacy fixtures spawn a hostile a few blocks away at any angle and
        // expect the very next scan to react, which is the omnidirectional line of sight (behaviour.perception.enabled=false). The tests
        // of the perception itself (CompanionPerceptionGameTests) switch it on for their own batch. MINECRAFTAI_HARNESS_PERCEPTION=on
        // runs any other suite with the realistic perception too (a lane for finding fixtures that assume an omniscient bot).
        io.github.zoyluo.minecraftai.perception.CreatureSenses.setHarnessDefaultOff(
                !"on".equalsIgnoreCase(System.getenv("MINECRAFTAI_HARNESS_PERCEPTION")));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("minecraftai")
                        .then(MinecraftAiTestSubcommand.build(registryAccess))
                        .then(MinecraftAiVerifySubcommand.build())
                        .then(MinecraftAiRestartHarnessCommand.build())));
        ServerTickEvents.END_SERVER_TICK.register(MinecraftAiVerifySubcommand::tick);
    }
}

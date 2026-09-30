package io.github.zoyluo.minecraftai.gametest;

import io.github.zoyluo.minecraftai.command.MinecraftAiTestSubcommand;
import io.github.zoyluo.minecraftai.command.MinecraftAiVerifySubcommand;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistRuntime;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRules;
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
        // Bots must not change how often they scan because a busy test server ticks slowly: a GameTest is counted in ticks, not in
        // milliseconds. A scenario that wants a degraded server forces it for its own bot (TpsGuard.forceDegradedForTests).
        io.github.zoyluo.minecraftai.observe.TpsGuard.setHarnessPinned(true);
        // Same for the A* wall-clock budget (50 ms in production): the node budget stays, the milliseconds do not decide.
        io.github.zoyluo.minecraftai.pathfinding.AStarPathfinder.setHarnessTimeScale(40L);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("minecraftai")
                        .then(MinecraftAiTestSubcommand.build(registryAccess))
                        .then(MinecraftAiVerifySubcommand.build())
                        .then(MinecraftAiRestartHarnessCommand.build())));
        ServerTickEvents.END_SERVER_TICK.register(MinecraftAiVerifySubcommand::tick);
        if (System.getProperty("fabric-api.gametest") != null) {
            // Every test runs alone (GameTestIsolation) in the one test world, which keeps one world clock, one weather and one set of
            // game rules. The suite freezes them at a known ambient (day, clear, clock and weather not advancing, no natural spawning),
            // and GameTestSweeper puts that ambient back between two tests, so a test that sets night or rain for its premise sets it
            // for itself only.
            ServerLifecycleEvents.SERVER_STARTED.register(MinecraftAiHarnessTestMod::freezeAmbient);
            ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
                GameTestEntityGate.onLoad(entity);
                GameTestSweeper.track(entity);
            });
            ServerTickEvents.END_SERVER_TICK.register(server -> GameTestEntityGate.endTick());
            ServerTickEvents.END_SERVER_TICK.register(GameTestSweeper::endTick);
            // Last: the light of every block this tick changed (tests, bots, the restorer) is published before the next tick.
            ServerTickEvents.END_SERVER_TICK.register(GameTestLightSync::endTick);
        }
    }

    private static void freezeAmbient(MinecraftServer server) {
        ServerLevel world = server.overworld();
        world.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
        world.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
        // No natural spawning: a zombie, a slime or a cow the fixture did not place is not a scene, it is noise (see GameTestEntityGate).
        world.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
        world.getGameRules().set(GameRules.SPAWN_MONSTERS, false, server);
        world.getGameRules().set(GameRules.SPAWN_PATROLS, false, server);
        world.getGameRules().set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
        world.getGameRules().set(GameRules.SPAWN_WARDENS, false, server);
        world.getGameRules().set(GameRules.SPAWN_PHANTOMS, false, server);
        world.setDayTime(GameTestSweeper.AMBIENT_DAY_TIME);
        world.setWeatherParameters(6000, 0, false, false);
        world.setRainLevel(0.0F);
        world.setThunderLevel(0.0F);
        GameTestSweeper.captureBaseline(server);
    }
}

package io.github.zoyluo.minecraftai.gametest;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.task.SensingArena.Room;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Self-tests of {@link GameTestSightDistance}: that the harness really does clamp a bot's block sight below what a bot has in play
 * (otherwise the helper is a no-op and the tests written under it prove nothing), that the helper gives the number a dedicated server
 * gives, and that no way of leaving a test, a throwing body or a leak that only the sweeper catches, changes what the next test sees.
 *
 * <p>A test cannot observe its own completion cleanup, so the restore after a test is verified by the NEXT one, as
 * {@code GameTestCleanupSelfTests} does: the two scenarios are identical, every batch runs alone and in sequence, and each checks
 * that it starts on the harness's view distance and leaves the production one in force. Whichever runs second proves what the first
 * left behind; when only one is selected the cross-check is skipped and the rest still runs.</p>
 */
public final class GameTestSightDistanceSelfTests {
    private static final int CHUNK = 16;

    @GameTest(environment = "minecraftai-gametest:game_test_sight_distance_self_tests_first_scenario_starts_on_the_harness_view_and_leaves_the_production_one_in_force", maxTicks = 10)
    public void firstScenarioStartsOnTheHarnessViewAndLeavesTheProductionOneInForce(GameTestHelper context) {
        scenario(context, "SightViewAGT");
    }

    @GameTest(environment = "minecraftai-gametest:game_test_sight_distance_self_tests_second_scenario_starts_on_the_harness_view_and_leaves_the_production_one_in_force", maxTicks = 10)
    public void secondScenarioStartsOnTheHarnessViewAndLeavesTheProductionOneInForce(GameTestHelper context) {
        scenario(context, "SightViewBGT");
    }

    private static void scenario(GameTestHelper context, String botName) {
        MinecraftServer server = context.getLevel().getServer();
        int harness = GameTestSweeper.baselineViewDistance();
        require(context, server.getPlayerList().getViewDistance() == harness,
                "the test started on view distance " + server.getPlayerList().getViewDistance() + ", not the harness's " + harness
                        + ": an earlier test left the production view in force");
        AIPlayerEntity bot = spawn(context, botName);
        int harnessSight = ObservableWorldQuery.visibleRangeBlocks(bot);

        GameTestSightDistance.useProductionView(context);

        require(context, server.getPlayerList().getViewDistance() == GameTestSightDistance.PRODUCTION_VIEW_DISTANCE_CHUNKS,
                "the production view distance is not in force: " + server.getPlayerList().getViewDistance());
        require(context, ObservableWorldQuery.visibleRangeBlocks(bot) > harnessSight,
                "the production view did not lengthen the bot's block sight: " + harnessSight + " -> "
                        + ObservableWorldQuery.visibleRangeBlocks(bot));
        // The test ends with the production view still in force on purpose: the other scenario checks it was put back.
        context.runAtTickTime(2L, () -> {
            AIPlayerManager.INSTANCE.despawn(server, botName);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:game_test_sight_distance_self_tests_the_harness_clamps_block_sight_and_the_helper_gives_what_a_bot_has_in_play", maxTicks = 10)
    public void theHarnessClampsBlockSightAndTheHelperGivesWhatABotHasInPlay(GameTestHelper context) {
        MinecraftServer server = context.getLevel().getServer();
        // A sealed stone room: nothing but the bot's own sight limit can hide the block, whatever the terrain around the structure.
        Room room = new Room(context, 150, -4, 30, -4, 4, 6);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(server, "SightRangeGT", room.world, Vec3.atBottomCenterOf(room.feet),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn SightRangeGT"));
        int asked = ClientInformation.createDefault().viewDistance();
        require(context, bot.requestedViewDistance() == asked, "a bot does not ask for the default client view distance " + asked + ": "
                + bot.requestedViewDistance());
        int inPlay = Math.min(asked, GameTestSightDistance.PRODUCTION_VIEW_DISTANCE_CHUNKS) * CHUNK;
        require(context, inPlay == GameTestSightDistance.BOT_BLOCK_SIGHT,
                "a bot on a dedicated server sees " + inPlay + " blocks, not the documented " + GameTestSightDistance.BOT_BLOCK_SIGHT);

        int harness = ObservableWorldQuery.visibleRangeBlocks(bot);
        require(context, harness < inPlay, "the harness no longer clamps block sight (" + harness + " vs " + inPlay
                + " blocks in play): retire GameTestSightDistance and the tests that use it");
        BlockPos far = room.at(24, 1, 0);
        room.set(24, 1, 0, Blocks.STONE);
        require(context, !ObservableWorldQuery.canObserveBlock(bot, far),
                "a block 24 away is in sight on the harness's " + harness + " blocks");

        try (var view = GameTestSightDistance.productionView(server)) {
            require(context, ObservableWorldQuery.visibleRangeBlocks(bot) == inPlay,
                    "the production view gives " + ObservableWorldQuery.visibleRangeBlocks(bot) + " blocks, a bot in play sees " + inPlay);
            require(context, ObservableWorldQuery.canObserveBlock(bot, far), "a block 24 away is not in sight on the production view");
        }
        require(context, ObservableWorldQuery.visibleRangeBlocks(bot) == harness, "the scope did not put the harness's view back");
        AIPlayerManager.INSTANCE.despawn(server, "SightRangeGT");
        room.clear();
        context.succeed();
    }

    @GameTest(environment = "minecraftai-gametest:game_test_sight_distance_self_tests_a_scope_restores_the_harness_view_when_its_body_throws_and_refuses_a_second_opening", maxTicks = 10)
    public void aScopeRestoresTheHarnessViewWhenItsBodyThrowsAndRefusesASecondOpening(GameTestHelper context) {
        MinecraftServer server = context.getLevel().getServer();
        int harness = server.getPlayerList().getViewDistance();
        try (var view = GameTestSightDistance.productionView(server)) {
            boolean refused = false;
            try {
                GameTestSightDistance.productionView(server);
            } catch (IllegalStateException second) {
                refused = true;
            }
            require(context, refused, "a second production view was opened over the first");
            require(context, server.getPlayerList().getViewDistance() == GameTestSightDistance.PRODUCTION_VIEW_DISTANCE_CHUNKS,
                    "the refused second opening changed the view distance");
            throw new IllegalStateException("EXPECTED by GameTestSightDistanceSelfTests (deliberate throw): the scope must still restore");
        } catch (IllegalStateException expected) {
            require(context, expected.getMessage().startsWith("EXPECTED by GameTestSightDistanceSelfTests"), "unexpected failure: " + expected);
        }
        require(context, server.getPlayerList().getViewDistance() == harness,
                "the harness's view distance " + harness + " was not put back after a throwing body: "
                        + server.getPlayerList().getViewDistance());
        context.succeed();
    }

    @GameTest(environment = "minecraftai-gametest:game_test_sight_distance_self_tests_the_sweeper_restores_a_view_that_a_test_left_in_force", maxTicks = 10)
    public void theSweeperRestoresAViewThatATestLeftInForce(GameTestHelper context) {
        MinecraftServer server = context.getLevel().getServer();
        int harness = server.getPlayerList().getViewDistance();
        require(context, !GameTestSightDistance.restoreLeaked(server), "a view was in force before this test opened one");
        GameTestSightDistance.productionView(server);
        require(context, server.getPlayerList().getViewDistance() == GameTestSightDistance.PRODUCTION_VIEW_DISTANCE_CHUNKS,
                "the view was not put in force");
        require(context, GameTestSightDistance.restoreLeaked(server), "the sweeper's guard did not see the leaked view");
        require(context, server.getPlayerList().getViewDistance() == harness, "the leaked view was not put back");
        require(context, !GameTestSightDistance.restoreLeaked(server), "the guard restored twice");
        context.succeed();
    }

    private static AIPlayerEntity spawn(GameTestHelper context, String name) {
        BlockPos start = context.absolutePos(new BlockPos(4, 4, 4));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                context.getLevel().setBlock(start.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        return AIPlayerManager.INSTANCE.spawn(context.getLevel().getServer(), name, context.getLevel(),
                        Vec3.atBottomCenterOf(start), 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
            throw new IllegalStateException(message);
        }
    }
}

package io.github.zoyluo.minecraftai.task;

import com.google.gson.JsonObject;
import io.github.zoyluo.minecraftai.brain.ChatTranscript;
import io.github.zoyluo.minecraftai.brain.ToolDefinition;
import io.github.zoyluo.minecraftai.brain.ToolRegistry;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Regression coverage for target-leg arrival at Baritone's exact {@code GoalNear(3)} boundary. */
public final class ShowTargetTaskGameTests {
    @GameTest(environment = "minecraftai-gametest:show_target_task_game_tests_exact_three_block_radius_completes_without_route",
            maxTicks = 60)
    public void showTargetCompletesAtExactThreeBlockHorizontalRadiusWithoutNoOpRoute(GameTestHelper context) {
        FollowFieldFixture fixture = new FollowFieldFixture(context, 8, 8);
        AIPlayerEntity bot = fixture.bot("ShowTargetRadiusGT", 0, 0, true);
        fixture.owner(bot, 1, 0);
        BlockPos target = fixture.cell(0, 3);
        ShowTargetTask task = new ShowTargetTask(target, "test target");
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_show_target_exact_radius"));

        context.failIfEver(() -> {
            fixture.require(task.state() != TaskState.FAILED,
                    "show target failed at GoalNear(3): " + task.failureReason());
            if (task.state() == TaskState.COMPLETED) {
                fixture.require(task.describe().contains("hops=0/24"),
                        "exactly three blocks away started a no-op target route: " + task.describe());
                fixture.finish();
                return;
            }
            fixture.require(context.getTick() < 30,
                    "show target did not point from GoalNear(3): " + task.describe());
        });
    }

    /** Ice is dry support, not water: a show route must be allowed to walk across it without breaking it. */
    @GameTest(environment = "minecraftai-gametest:show_target_task_game_tests_crosses_packed_ice_between_snow_and_chest",
            maxTicks = 240)
    public void showTargetCrossesPackedIceBetweenSnowAndChest(GameTestHelper context) {
        FollowFieldFixture fixture = new FollowFieldFixture(context, 5, 8);
        AIPlayerEntity bot = fixture.bot("ShowTargetIceGT", 0, -5, true);
        // Keep the owner close enough for the rendezvous without occupying the one-cell lane.
        fixture.owner(bot, 0, -7);
        for (int z = -8; z <= 8; z++) {
            fixture.arena.set(0, -1, z, Blocks.SNOW_BLOCK);
            fixture.arena.fill(-1, z, Blocks.BEDROCK, 0, 2);
            fixture.arena.fill(1, z, Blocks.BEDROCK, 0, 2);
        }
        BlockPos firstIce = fixture.arena.cell(0, -1, -2);
        BlockPos secondIce = fixture.arena.cell(0, -1, -1);
        fixture.level.setBlock(firstIce, Blocks.PACKED_ICE.defaultBlockState(), Block.UPDATE_ALL);
        fixture.level.setBlock(secondIce, Blocks.PACKED_ICE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos target = fixture.arena.cell(0, 0, 4);
        fixture.level.setBlock(target, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        ShowTargetTask task = new ShowTargetTask(target, "ice chest");
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_show_target_ice_bridge"));
        boolean[] steppedOnIce = {false};

        context.failIfEver(() -> {
            BlockPos support = bot.blockPosition().below();
            steppedOnIce[0] |= support.equals(firstIce) || support.equals(secondIce);
            fixture.require(!bot.isInWater() && !bot.isInLava(),
                    "ice bridge was treated as a fluid route: " + bot.blockPosition());
            fixture.require(task.state() != TaskState.FAILED,
                    "show target failed on dry ice: " + task.failureReason());
            if (task.state() == TaskState.COMPLETED) {
                int completedRelativeZ = bot.blockPosition().getZ() - fixture.arena.origin.getZ();
                fixture.require(completedRelativeZ >= 1,
                        "show route completed before crossing the ice bridge: " + bot.blockPosition());
                fixture.require(steppedOnIce[0], "show route reached the chest without walking on the packed ice");
                fixture.require(fixture.level.getBlockState(firstIce).is(Blocks.PACKED_ICE)
                                && fixture.level.getBlockState(secondIce).is(Blocks.PACKED_ICE),
                        "show route broke an ice support");
                fixture.require(fixture.level.getBlockState(target).is(Blocks.CHEST),
                        "show route altered the chest it was showing");
                fixture.finish();
                return;
            }
            fixture.require(context.getTick() < 220,
                    "show target did not cross the ice bridge: " + task.describe());
        });
    }

    /** A chest below a pool is shown from its top-water projection, never by diving to its floor cell. */
    @GameTest(environment = "minecraftai-gametest:show_target_task_game_tests_shows_deep_water_target_from_surface_without_diving",
            maxTicks = 520)
    public void showTargetSwimsAtSurfaceAboveDeepChestWithoutDiving(GameTestHelper context) {
        FollowFieldFixture fixture = new FollowFieldFixture(context, 10, 6, 0, 8);
        AIPlayerEntity bot = fixture.bot("ShowTargetSurfaceGT", -7, 0, true);
        fixture.owner(bot, -8, 0);
        // The pool seals the whole cross-section of the arena, so reaching the chest's column requires a water
        // crossing. Its surface is feet-level y=0; the chest is six cells below it.
        for (int x = -1; x <= 10; x++) {
            for (int z = -6; z <= 6; z++) {
                for (int y = -5; y <= 0; y++) {
                    fixture.level.setBlock(fixture.arena.cell(x, y, z), Blocks.WATER.defaultBlockState(),
                            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE);
                }
            }
        }
        BlockPos target = fixture.arena.cell(6, -6, 0);
        BlockPos surface = fixture.arena.cell(6, 0, 0);
        fixture.level.setBlock(target, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        ShowTargetTask task = new ShowTargetTask(target, "deep chest");
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_show_target_surface_water"));
        boolean[] enteredWater = {false};
        boolean[] crossedNearTargetOnSurface = {false};

        context.failIfEver(() -> {
            enteredWater[0] |= bot.isInWater();
            crossedNearTargetOnSurface[0] |= bot.isInWater()
                    && bot.blockPosition().getY() == surface.getY()
                    && bot.blockPosition().getX() >= surface.getX() - ShowTargetTask.TARGET_RADIUS;
            fixture.require(!bot.isInLava(), "surface presentation entered lava: " + bot.blockPosition());
            fixture.require(bot.blockPosition().getY() >= surface.getY(),
                    "surface presentation dived below the top water layer: " + bot.blockPosition());
            fixture.require(task.state() != TaskState.FAILED,
                    "show target failed instead of using the water surface: " + task.failureReason());
            if (task.state() == TaskState.COMPLETED) {
                int dx = bot.blockPosition().getX() - surface.getX();
                int dz = bot.blockPosition().getZ() - surface.getZ();
                fixture.require(enteredWater[0], "surface presentation never entered the required water crossing");
                fixture.require(crossedNearTargetOnSurface[0],
                        "surface presentation did not cross water near the chest column");
                fixture.require(dx * dx + dz * dz <= ShowTargetTask.TARGET_RADIUS * ShowTargetTask.TARGET_RADIUS,
                        "surface presentation ended away from the chest column: " + bot.blockPosition());
                fixture.require(bot.blockPosition().getY() >= surface.getY(),
                        "surface presentation completed after diving: " + bot.blockPosition());
                fixture.require(fixture.level.getBlockState(target).is(Blocks.CHEST),
                        "surface presentation altered the underwater chest");
                fixture.finish();
                return;
            }
            fixture.require(context.getTick() < 500,
                    "show target did not reach the water surface: " + task.describe());
        });
    }

    /**
     * A bonus chest is an ordinary chest that nothing the bot can observe sets apart, so finding "the bonus chest" reports a chest and says
     * it cannot tell which one it is; the label a later show-location uses stays the observed one.
     */
    @GameTest(maxTicks = 140)
    public void findBonusChestReportsAChestAndAdmitsItCannotTellWhichOne(GameTestHelper context) {
        findVisibleChest(context, "FindBonusGT", "bonus_chest", true);
    }

    @GameTest(maxTicks = 140)
    public void findContainerReportsAChestWithoutTheBonusChestCaveat(GameTestHelper context) {
        findVisibleChest(context, "FindChestGT", "container", false);
    }

    private static void findVisibleChest(GameTestHelper context, String botName, String requested, boolean bonusCaveat) {
        FollowFieldFixture fixture = new FollowFieldFixture(context, 8, 8);
        AIPlayerEntity bot = fixture.bot(botName, 0, 0, true);
        fixture.level.setBlock(fixture.cell(0, 4), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        DiscoveryTask task = DiscoveryTask.find(requested, 24);
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_find_chest"));

        context.failIfEver(() -> {
            fixture.require(task.state() != TaskState.FAILED, "find failed: " + task.failureReason());
            if (task.state() == TaskState.COMPLETED) {
                String chat = ChatTranscript.renderRecentChat(bot.getUUID());
                fixture.require(chat.contains("I found a chest or other storage container at"),
                        "the report did not name what was seen: " + chat);
                fixture.require(chat.contains("I can't tell whether it is the bonus chest.") == bonusCaveat,
                        "the bonus-chest caveat was " + (bonusCaveat ? "missing" : "present") + ": " + chat);
                DiscoveryTask.FoundTarget found = DiscoveryTask.latestFound(bot).orElse(null);
                fixture.require(found != null && found.pos().equals(fixture.cell(0, 4))
                                && "a chest or other storage container".equals(found.label()),
                        "the remembered find is not the observed chest: " + found);
                fixture.finish();
                return;
            }
            fixture.require(context.getTick() < 120, "find did not complete: " + task.describe());
        });
    }

    /**
     * The tool call the model makes after a find ("show me", with the label it believes in): the demonstration is announced under the
     * observed label, whether or not the call repeats the coordinates, never as "the bonus chest".
     */
    @GameTest(maxTicks = 200)
    public void showLocationAfterFindingABonusChestAnnouncesTheObservedChestNotTheModelsLabel(GameTestHelper context) {
        FollowFieldFixture fixture = new FollowFieldFixture(context, 8, 8);
        AIPlayerEntity bot = fixture.bot("ShowBonusGT", 0, 0, true);
        fixture.owner(bot, 1, 0);
        BlockPos chest = fixture.cell(0, 4);
        fixture.level.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        DiscoveryTask find = DiscoveryTask.find("bonus_chest", 24);
        TaskManager.INSTANCE.assign(bot, find, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_find_chest"));
        ToolDefinition show = new ToolRegistry().get("show_location").orElseThrow();
        boolean[] asked = {false};

        context.failIfEver(() -> {
            fixture.require(find.state() != TaskState.FAILED, "find failed: " + find.failureReason());
            if (find.state() != TaskState.COMPLETED) {
                fixture.require(context.getTick() < 120, "find did not complete: " + find.describe());
                return;
            }
            if (!asked[0]) {
                asked[0] = true;
                JsonObject args = new JsonObject();
                args.addProperty("label", "the bonus chest");
                ToolDefinition.ToolResult result = show.handler().invoke(bot, args);
                fixture.require(result.ok(), "show_location was refused: " + result);
                return;
            }
            String chat = ChatTranscript.renderRecentChat(bot.getUUID());
            if (chat.contains("I'll show you where")) {
                fixture.require(chat.contains("I'll show you where a chest or other storage container is."),
                        "the demonstration was not announced under the observed label: " + chat);
                fixture.require(!chat.contains("the bonus chest is"), "the model's unsupported name was repeated: " + chat);
                fixture.finish();
                return;
            }
            fixture.require(context.getTick() < 180, "the demonstration was never announced: " + chat);
        });
    }
}

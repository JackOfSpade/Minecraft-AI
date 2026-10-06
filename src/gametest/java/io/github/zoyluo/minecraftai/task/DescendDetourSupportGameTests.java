package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.gametest.BotFixtureMoves;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The sneak-bridge a descent builds to cross a cell that has no floor (a stair that broke into a cave leaves
 * every neighbour of the bot's cell like that), played on a one-block pillar over open air.
 */
public final class DescendDetourSupportGameTests {
    private static final List<Direction> SIDES =
            List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);

    /**
     * A flat step ends the moment the feet cross into the new cell, so a bot arrives at the near edge of it, and
     * the lean over the edge toward any other side was then refused as "too far" (and that side poisoned). The
     * bot is put on the south edge of its pillar: whichever side the descent tries first, it must line up on the
     * middle of the cell, lean without a refusal, place its one support there and cross onto it, and the
     * crossed support must remain the floor it was built to be.
     */
    @GameTest(environment = "minecraftai-gametest:descend_detour_support_game_tests_lean_starts_from_the_near_edge_of_the_cell", maxTicks = 1200)
    public void leanStartsFromTheNearEdgeOfTheCell(GameTestHelper context) {
        BlockPos start = pillar(context);
        String name = "DetourEdgeGT";
        AIPlayerEntity bot = spawnOnNearEdge(context, name, start);

        DescendToYTask task = DescendToYTask.forMiningExploration(start.getY() - 6);
        task.start(bot);
        Direction[] placed = {null};
        int[] settled = {0};

        DescendTickStages.run(context,
                DescendTickStages.tickUntil(context, task, bot, 600,
                        "no detour support was placed for a bot standing on the near edge of its cell",
                        () -> {
                            requireNoRefusedLean(context, bot);
                            for (Direction side : SIDES) {
                                if (hasSupport(context, start, side)) {
                                    placed[0] = side;
                                    return true;
                                }
                            }
                            return false;
                        }),
                DescendTickStages.once(() -> {
                    int supports = 0;
                    for (Direction side : SIDES) {
                        supports += hasSupport(context, start, side) ? 1 : 0;
                    }
                    require(context, supports == 1, "one detour placed " + supports + " supports");
                }),
                DescendTickStages.tickUntil(context, task, bot, 600,
                        "the bot did not cross onto the support it placed",
                        () -> {
                            requireNoRefusedLean(context, bot);
                            return bot.blockPosition().equals(start.relative(placed[0]));
                        }),
                // The crossed support is a floor now: it must stay one, not be mined back as the next stair tread.
                DescendTickStages.tickUntil(context, task, bot, 200,
                        "the support was taken back after the crossing",
                        () -> {
                            require(context, hasSupport(context, start, placed[0]),
                                    "the support under the crossed cell was mined");
                            return ++settled[0] > 60;
                        }),
                DescendTickStages.once(() -> {
                    TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
                    AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
                    context.succeed();
                }));
    }

    /**
     * The walk to the middle of the cell that comes before the lean was given up for good on its first failure, and the edge
     * with it. A mob that stands in the middle of the cell for a moment must not cost the detour that edge: once it has moved on,
     * the same edge is tried again and a support is built.
     */
    @GameTest(environment = "minecraftai-gametest:descend_detour_support_game_tests_a_mob_in_the_cell_does_not_retire_the_edge", maxTicks = 1200)
    public void aMobInTheCellDoesNotRetireTheEdge(GameTestHelper context) {
        BlockPos start = pillar(context);
        String name = "DetourEdgeMobGT";
        AIPlayerEntity bot = spawnOnNearEdge(context, name, start);
        var stand = EntityType.ARMOR_STAND.create(context.getLevel(), EntitySpawnReason.COMMAND);
        require(context, stand != null, "could not create the mob");
        stand.snapTo(start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D, 0.0F, 0.0F);
        context.getLevel().addFreshEntity(stand);

        DescendToYTask task = DescendToYTask.forMiningExploration(start.getY() - 6);
        task.start(bot);

        DescendTickStages.run(context,
                DescendTickStages.tickUntil(context, task, bot, 300,
                        "the mob in the middle of the cell never stopped the bot walking there",
                        () -> {
                            WalkedStep.Result last = bot.getActionPack().stepResult();
                            return last != null && last.failed();
                        }),
                DescendTickStages.once(stand::discard),
                DescendTickStages.tickUntil(context, task, bot, 600,
                        "no detour support was placed once the mob had moved on",
                        () -> SIDES.stream().anyMatch(side -> hasSupport(context, start, side))),
                DescendTickStages.once(() -> {
                    require(context, failedStepEdges(task).isEmpty(),
                            "the edge was retired by one refused walk to the middle of the cell: " + failedStepEdges(task));
                    TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
                    AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
                    context.succeed();
                }));
    }

    /** A one-block stone pillar in the middle of open air, and the cell on top of it. */
    private static BlockPos pillar(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(6, 12, 6));
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = -6; dy <= 3; dy++) {
                    context.getLevel().setBlock(start.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        context.getLevel().setBlock(start.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        return start;
    }

    /** A bot on the south edge of {@code start}, as a flat step that has just crossed into the cell leaves it. */
    private static AIPlayerEntity spawnOnNearEdge(GameTestHelper context, String name, BlockPos start) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getLevel().getServer(), name, context.getLevel(),
                        Vec3.atBottomCenterOf(start), 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        BotFixtureMoves.place(bot, new Vec3(start.getX() + 0.5D, start.getY(), start.getZ() + 0.95D));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 64));
        return bot;
    }

    private static Set<?> failedStepEdges(DescendToYTask task) {
        try {
            Field field = DescendToYTask.class.getDeclaredField("failedStepEdges");
            field.setAccessible(true);
            return (Set<?>) field.get(task);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("could not inspect the failed detour edges", exception);
        }
    }

    private static boolean hasSupport(GameTestHelper context, BlockPos start, Direction side) {
        return context.getLevel().getBlockState(start.relative(side).below()).is(Blocks.COBBLESTONE);
    }

    /** The result of the last walked step is read before the task launches the next one. */
    private static void requireNoRefusedLean(GameTestHelper context, AIPlayerEntity bot) {
        WalkedStep.Result last = bot.getActionPack().stepResult();
        require(context, last == null || !last.failed() || !"too_far".equals(last.reason()),
                "a step in the cell was refused as too far: " + last);
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}

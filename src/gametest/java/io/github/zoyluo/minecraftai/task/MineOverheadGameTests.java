package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.task.GatherOverheadLogGameTests.Case;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * The generic mine request meets the same overhead target as gather: a block straight above has no heading for a
 * landmark leg and the sweep must not stall on it, and a block no route reaches is climbed to before it is set aside.
 * (Dirt, because the sealed room is stone and a stone mine would simply break the floor.)
 */
public final class MineOverheadGameTests {
    @GameTest(environment = "minecraftai-gametest:mine_overhead_game_tests_overhead_block_is_climbed_and_the_tower_taken_down", maxTicks = 1500)
    public void overheadBlockIsClimbedAndTheTowerTakenDown(GameTestHelper context) {
        Case c = new Case(context, "MineOverheadOwnGT", 0, 4);
        BlockPos dirt = c.at(0, 7, 0);
        c.set(0, 7, 0, Blocks.DIRT);
        c.give(new ItemStack(Items.WOODEN_SHOVEL), new ItemStack(Items.COBBLESTONE, 8));
        MineTask task = new MineTask(Blocks.DIRT, 1);
        task.start(c.bot);

        c.run(task, () -> {
            if (task.state() != TaskState.COMPLETED) {
                return false;
            }
            List<String> lines = c.log();
            c.require(c.count(lines, "mine_pillar_start", "target='" + dirt.toShortString() + "'") == 1,
                    "the overhead block was not climbed: " + c.tail(lines, "mine_"));
            c.require(c.count(lines, "mine_target_sighting_pursuit_refused") == 0,
                    "an overhead block must not go through a refused landmark pursuit: " + c.tail(lines, "mine_"));
            c.require(c.world().getBlockState(dirt).isAir() && InventoryAction.countItem(c.bot, Items.DIRT) >= 1,
                    "the block was not mined and picked up: " + c.tail(lines, "mine_"));
            c.require(c.count(lines, "mine_tower_descended") == 1 && c.bot.blockPosition().getY() == c.feet.getY(),
                    "the task ended with the bot still up on its tower: " + c.tail(lines, "mine_"));
            return true;
        });
    }

    @GameTest(environment = "minecraftai-gametest:mine_overhead_game_tests_excluded_overhead_block_does_not_stop_the_sweep_finding_another", maxTicks = 1500)
    public void excludedOverheadBlockDoesNotStopTheSweepFindingAnother(GameTestHelper context) {
        // The excluded block straight above answers the sweep's vertical ray on every step. It must be passed over
        // (neither climbed nor offered again) so the 360-degree raster reaches the second block off to the side.
        Case c = new Case(context, "MineOverheadExcludedGT", 1, 5);
        BlockPos excluded = c.at(0, 7, 0);
        BlockPos other = c.at(-3, 7, 2);
        c.set(0, 7, 0, Blocks.DIRT);
        c.set(-3, 7, 2, Blocks.DIRT);
        c.give(new ItemStack(Items.WOODEN_SHOVEL), new ItemStack(Items.COBBLESTONE, 8));
        EpisodeMemory.INSTANCE.exclude(c.bot.getUUID(), excluded, context.getLevel().getServer().getTickCount(),
                EpisodeMemory.TTL_UNREACHABLE);
        MineTask task = new MineTask(Blocks.DIRT, 1);
        task.start(c.bot);

        c.run(task, () -> {
            if (task.state() != TaskState.COMPLETED) {
                return false;
            }
            List<String> lines = c.log();
            c.require(c.world().getBlockState(excluded).is(Blocks.DIRT) && c.world().getBlockState(other).isAir(),
                    "the sweep took the excluded block instead of the other one: " + c.tail(lines, "mine_"));
            c.require(c.count(lines, "mine_pillar_start", "target='" + excluded.toShortString() + "'") == 0,
                    "an excluded block was climbed anyway: " + c.tail(lines, "mine_"));
            int pillar = c.indexOf(lines, "mine_pillar_start", "target='" + other.toShortString() + "'");
            c.require(pillar >= 0, "the second block was never climbed to: " + c.tail(lines, "mine_"));
            long sighted = lines.subList(0, pillar).stream().filter(line -> line.contains("event=mine_target_sighted")
                    && line.contains("pos='" + excluded.toShortString() + "'")).count();
            c.require(sighted <= 1, "the excluded block answered the sweep again and again (" + sighted + " times): "
                    + c.tail(lines, "mine_"));
            return true;
        });
    }

    @GameTest(environment = "minecraftai-gametest:mine_overhead_game_tests_block_a_route_refuses_is_climbed_to_before_it_is_set_aside", maxTicks = 1500)
    public void blockARouteRefusesIsClimbedToBeforeItIsSetAside(GameTestHelper context) {
        // A floating stone ledge is an observed stance beside the block, but no route reaches it (the bot holds
        // nothing a route could place). The search used to ask for the same refused route on every tick; now the
        // block is climbed to, or set aside, once. (Coarse dirt, because the dirt on the floor is the bot's pillar supply.)
        Case c = new Case(context, "MineOverheadLedgeGT", 2, 9);
        BlockPos coarse = c.at(0, 6, 7);
        c.set(0, 6, 7, Blocks.COARSE_DIRT);
        c.set(1, 5, 7, Blocks.STONE);
        for (int[] cell : new int[][] {{-3, 0, 0}, {-3, 0, 1}, {-3, 0, -1}, {-4, 0, 0}}) {
            c.set(cell[0], cell[1], cell[2], Blocks.DIRT);
        }
        c.give(new ItemStack(Items.WOODEN_SHOVEL));
        MineTask task = new MineTask(Blocks.COARSE_DIRT, 1);
        task.start(c.bot);

        c.run(task, () -> {
            if (task.state() != TaskState.COMPLETED) {
                return false;
            }
            List<String> lines = c.log();
            c.require(c.count(lines, "mine_route_refused") <= 1,
                    "the same refused route was asked for again and again: " + c.tail(lines, "mine_"));
            c.require(c.count(lines, "mine_pillar_start", "target='" + coarse.toShortString() + "'") >= 1
                            && c.world().getBlockState(coarse).isAir(),
                    "the block no route reaches was not climbed to: " + c.tail(lines, "mine_"));
            return true;
        });
    }

    @GameTest(environment = "minecraftai-gametest:mine_overhead_game_tests_block_over_a_hill_is_climbed_from_the_hills_top", maxTicks = 1800)
    public void blockOverAHillIsClimbedFromTheHillsTop(GameTestHelper context) {
        // Two broad steps of stone rise toward the far wall and the block hangs nine up above the top one: nothing
        // at the bot's own level is a column worth building in, so it walks up the hill and builds two blocks.
        Case c = new Case(context, "MineOverheadHillGT", 3, 9);
        BlockPos dirt = c.at(0, 9, 6);
        c.set(0, 9, 6, Blocks.DIRT);
        for (int row = 0; row < 2; row++) {
            for (int dz = 2 + 4 * row; dz <= 5 + 4 * row; dz++) {
                for (int dx = -4; dx <= 4; dx++) {
                    for (int y = 0; y <= row; y++) {
                        c.set(dx, y, dz, Blocks.STONE);
                    }
                }
            }
        }
        c.give(new ItemStack(Items.WOODEN_SHOVEL), new ItemStack(Items.COBBLESTONE, 8));
        MineTask task = new MineTask(Blocks.DIRT, 1);
        task.start(c.bot);

        c.run(task, () -> {
            if (task.state() != TaskState.COMPLETED) {
                return false;
            }
            List<String> lines = c.log();
            int walk = c.indexOf(lines, "mine_pillar_base_walk", "target='" + dirt.toShortString() + "'");
            int pillar = c.indexOf(lines, "mine_pillar_start", "target='" + dirt.toShortString() + "'", "supports='2'");
            c.require(walk >= 0 && pillar > walk,
                    "expected a walk up the hill, then the two-block pillar (" + walk + "," + pillar + "): " + c.tail(lines, "mine_"));
            c.require(c.world().getBlockState(dirt).isAir() && InventoryAction.countItem(c.bot, Items.DIRT) >= 1,
                    "the block was not mined and picked up: " + c.tail(lines, "mine_"));
            return true;
        });
    }
}

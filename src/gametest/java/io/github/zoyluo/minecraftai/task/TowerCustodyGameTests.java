package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import io.github.zoyluo.minecraftai.task.GatherOverheadLogGameTests.Case;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * A task can end while the bot stands on a tower it built: stopped by the player, replaced by another job, timed
 * out. A route steps down at most the safe fall and the bot never breaks its own footing, so a tall tower with
 * no one to take it down would strand it (the height cap that used to rule this out is gone). The custody
 * ({@link TowerCustody}) takes the tower down whichever way the task ended.
 */
public final class TowerCustodyGameTests {
    /** Twelve up needs a seven-block tower, more than a route steps down from. */
    private static final int ON_THE_TOWER = 4;

    @GameTest(environment = "minecraftai-gametest:tower_custody_game_tests_task_stopped_high_on_its_tower_does_not_strand_the_bot", maxTicks = 1500)
    public void taskStoppedHighOnItsTowerDoesNotStrandTheBot(GameTestHelper context) {
        Case c = new Case(context, "TowerCustodyStopGT", 0, 9);
        BlockPos dirt = c.at(0, 12, 0);
        c.set(0, 12, 0, Blocks.DIRT);
        c.give(new ItemStack(Items.WOODEN_SHOVEL), new ItemStack(Items.WOODEN_PICKAXE), new ItemStack(Items.COBBLESTONE, 8));
        MineTask task = new MineTask(Blocks.DIRT, 1);
        TaskManager.INSTANCE.assign(c.bot, task, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_tower_custody"));

        boolean[] stopped = {false};
        c.watch(() -> {
            if (!stopped[0]) {
                if (c.bot.blockPosition().getY() >= c.feet.getY() + ON_THE_TOWER && c.bot.onGround()) {
                    stopped[0] = true;
                    TaskManager.INSTANCE.cancelIntentTasks(c.bot, "gametest_stop");
                    c.require(task.state() == TaskState.CANCELLED, "the task did not stop: " + task.state());
                }
                return false;
            }
            List<String> lines = c.log();
            if (c.bot.blockPosition().getY() != c.feet.getY() || c.count(lines, "tower_orphan_descended") == 0) {
                return false;
            }
            c.require(c.count(lines, "tower_orphan_descended") == 1 && c.count(lines, "tower_orphan_descent_failed") == 0,
                    "the tower of the stopped task was not taken down: " + c.tail(lines, ""));
            c.require(c.world().getBlockState(dirt).is(Blocks.DIRT), "the stopped task went on mining");
            for (int up = 0; up < ON_THE_TOWER; up++) {
                c.require(c.world().getBlockState(c.at(0, up, 0)).isAir(), "a block of the tower is still standing at " + up);
            }
            c.require(InventoryAction.countItem(c.bot, Items.COBBLESTONE) == 8,
                    "the tower's blocks were not picked up again: " + InventoryAction.countItem(c.bot, Items.COBBLESTONE));
            return true;
        });
    }

    @GameTest(environment = "minecraftai-gametest:tower_custody_game_tests_new_task_waits_for_the_tower_of_the_one_it_replaced", maxTicks = 1500)
    public void newTaskWaitsForTheTowerOfTheOneItReplaced(GameTestHelper context) {
        // The replacement does nothing but count its ticks and note how high the bot stood at its first one.
        Case c = new Case(context, "TowerCustodyReplaceGT", 1, 9);
        c.set(0, 12, 0, Blocks.DIRT);
        c.give(new ItemStack(Items.WOODEN_SHOVEL), new ItemStack(Items.WOODEN_PICKAXE), new ItemStack(Items.COBBLESTONE, 8));
        MineTask task = new MineTask(Blocks.DIRT, 1);
        TaskManager.INSTANCE.assign(c.bot, task, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_tower_custody"));

        Probe[] next = {null};
        c.watch(() -> {
            if (next[0] == null) {
                if (c.bot.blockPosition().getY() >= c.feet.getY() + ON_THE_TOWER && c.bot.onGround()) {
                    next[0] = new Probe();
                    TaskManager.INSTANCE.assign(c.bot, next[0], TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_replacement"));
                }
                return false;
            }
            if (next[0].state() != TaskState.COMPLETED) {
                return false;
            }
            List<String> lines = c.log();
            c.require(next[0].heightAtFirstTick == c.feet.getY(),
                    "the replacing task ran with the bot " + (next[0].heightAtFirstTick - c.feet.getY()) + " up on the old tower: "
                            + c.tail(lines, ""));
            c.require(c.count(lines, "tower_orphan_descended") == 1,
                    "the replaced task's tower was not taken down first: " + c.tail(lines, ""));
            return true;
        });
    }

    /** A task that waits five ticks and ends, and records where the bot stood when it first got to run. */
    private static final class Probe extends AbstractTask {
        int heightAtFirstTick = Integer.MIN_VALUE;

        @Override
        public String name() {
            return "tower_custody_probe";
        }

        @Override
        public String describe() {
            return "waiting for its turn";
        }

        @Override
        public double progress() {
            return 0.0D;
        }

        @Override
        protected void onStart(AIPlayerEntity bot) {
        }

        @Override
        protected void onTick(AIPlayerEntity bot) {
            if (heightAtFirstTick == Integer.MIN_VALUE) {
                heightAtFirstTick = bot.blockPosition().getY();
            }
            if (elapsed >= 5) {
                complete();
            }
        }
    }
}

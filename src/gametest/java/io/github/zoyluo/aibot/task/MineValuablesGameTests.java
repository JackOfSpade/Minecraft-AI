package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Regression coverage for {@link MineValuablesTask}'s two load-bearing guarantees: it actually
 * mines and collects a valuable it could see at start, and -- the whole point of the feature --
 * it never expands scope onto a valuable that only becomes visible after the snapshot was taken.
 */
public final class MineValuablesGameTests {
    @GameTest(environment = "aibot-gametest:mine_valuables_game_tests_visible_ore_is_mined_and_collected", maxTicks = 400)
    public void visibleOreIsMinedAndCollected(TestContext context) {
        Fixture fixture = fixture(context, "MineValuablesBasicGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos ore = fixture.start().east(2);
        bot.getServerWorld().setBlockState(ore, Blocks.COAL_ORE.getDefaultState(), Block.NOTIFY_ALL);

        MineValuablesTask task = new MineValuablesTask(3);
        task.start(bot);

        context.runAtEveryTick(() -> {
            tickOrFail(context, task, bot);
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, bot.getServerWorld().getBlockState(ore).isAir(),
                    "mine_valuables completed without actually breaking the visible coal ore");
            require(context, InventoryAction.countItem(bot, Items.COAL) >= 1,
                    "mine_valuables completed without collecting the coal it mined");
            finish(context, fixture);
        });
    }

    @GameTest(environment = "aibot-gametest:mine_valuables_game_tests_frozen_snapshot_never_mines_newly_revealed_ore", maxTicks = 500)
    public void frozenSnapshotNeverMinesNewlyRevealedOre(TestContext context) {
        Fixture fixture = fixture(context, "MineValuablesFrozenGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos visibleOre = fixture.start().east(2);
        // One layer directly beneath the ordinary floor -- fully concealed by that floor block
        // from every angle the bot can stand at, since it sits in the exact same column the bot's
        // own eye-to-face raycasts pass through to reach anything below the floor.
        BlockPos concealingFloor = fixture.start().east(2).down(1);
        BlockPos hiddenOre = fixture.start().east(2).down(2);
        bot.getServerWorld().setBlockState(visibleOre, Blocks.COAL_ORE.getDefaultState(), Block.NOTIFY_ALL);
        bot.getServerWorld().setBlockState(hiddenOre, Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        // A stone pickaxe can harvest both coal and iron ore. If the scope-freeze guarantee this
        // test exists to protect were ever broken, the bot would be fully capable of mining the
        // newly-revealed iron ore too -- so a pass here is not an accident of missing tool tier.
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));

        MineValuablesTask task = new MineValuablesTask(3);
        task.start(bot);
        AtomicBoolean revealed = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            tickOrFail(context, task, bot);
            if (!revealed.get()) {
                // Still scanning: describe() reports "Scanning valuables ..." until the frozen
                // snapshot is built. The instant it flips to the post-scan "Mining valuables N/M"
                // form, the snapshot is fixed for the rest of this run.
                if (task.describe().startsWith("Scanning")) {
                    return;
                }
                require(context, task.describe().contains("valuables 0/1 "),
                        "snapshot did not freeze to exactly the one visible ore (hidden ore leaked "
                                + "in, or the visible one was missed): " + task.describe());
                // Reveal the previously-hidden ore now, with the task still actively running and
                // many ticks left (travel + mine + pickup of the visible ore) during which a
                // scope-freeze regression would have every opportunity to notice and chase it.
                bot.getServerWorld().setBlockState(concealingFloor, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                revealed.set(true);
                return;
            }
            if (task.state() != TaskState.COMPLETED) {
                require(context, bot.getServerWorld().getBlockState(hiddenOre).isOf(Blocks.IRON_ORE),
                        "mine_valuables touched the newly-revealed ore while still running: "
                                + task.describe());
                return;
            }
            require(context, bot.getServerWorld().getBlockState(visibleOre).isAir(),
                    "mine_valuables completed without mining the originally visible ore");
            require(context, InventoryAction.countItem(bot, Items.COAL) >= 1,
                    "mine_valuables completed without collecting the originally visible ore's drop");
            require(context, bot.getServerWorld().getBlockState(hiddenOre).isOf(Blocks.IRON_ORE),
                    "mine_valuables mined the ore that only became visible after the snapshot was taken");
            require(context, InventoryAction.countItem(bot, Items.RAW_IRON) == 0,
                    "mine_valuables collected raw iron it should never have targeted");
            finish(context, fixture);
        });
    }

    private static Fixture fixture(TestContext context, String name) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(2, 2, 2));
        // Keep every mutation inside FabricGameTest.EMPTY_STRUCTURE (8x8), matching the established
        // convention in this codebase's other single-task GameTests (see GatherPickupGameTests).
        for (int dx = -2; dx <= 3; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = start.add(dx, 0, dz);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        bot.getHungerManager().setSaturationLevel(5.0F);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE, 1));
        return new Fixture(bot, start.toImmutable(), name);
    }

    private static void tickOrFail(TestContext context, MineValuablesTask task, AIPlayerEntity bot) {
        if (task.state() == TaskState.RUNNING) {
            task.tick(bot);
        }
        if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
            context.throwGameTestException(Text.of(
                    "mine_valuables ended as " + task.state() + ":" + task.failureReason()));
        }
    }

    private static void finish(TestContext context, Fixture fixture) {
        AIPlayerManager.INSTANCE.despawn(fixture.bot().getServer(), fixture.name());
        context.complete();
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(Text.of(message));
        }
    }

    private record Fixture(AIPlayerEntity bot, BlockPos start, String name) {
    }
}

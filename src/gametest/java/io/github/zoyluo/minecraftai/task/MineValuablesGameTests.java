package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Regression coverage for {@link MineValuablesTask}'s two load-bearing guarantees: it actually
 * mines and collects a valuable it could see at start, and -- the whole point of the feature --
 * it never expands scope onto a valuable that only becomes visible after the snapshot was taken.
 */
public final class MineValuablesGameTests {
    @GameTest(environment = "minecraftai-gametest:mine_valuables_game_tests_visible_ore_is_mined_and_collected", maxTicks = 400)
    public void visibleOreIsMinedAndCollected(GameTestHelper context) {
        Fixture fixture = fixture(context, "MineValuablesBasicGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos ore = fixture.start().east(2);
        bot.level().setBlock(ore, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);

        MineValuablesTask task = new MineValuablesTask(3);
        task.start(bot);

        context.failIfEver(() -> {
            tickOrFail(context, task, bot);
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, bot.level().getBlockState(ore).isAir(),
                    "mine_valuables completed without actually breaking the visible coal ore");
            require(context, InventoryAction.countItem(bot, Items.COAL) >= 1,
                    "mine_valuables completed without collecting the coal it mined");
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:mine_valuables_game_tests_frozen_snapshot_never_mines_newly_revealed_ore", maxTicks = 500)
    public void frozenSnapshotNeverMinesNewlyRevealedOre(GameTestHelper context) {
        Fixture fixture = fixture(context, "MineValuablesFrozenGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos visibleOre = fixture.start().east(2);
        // One layer directly beneath the ordinary floor -- fully concealed by that floor block
        // from every angle the bot can stand at: the floor slab is solid all around it, so every
        // eye-to-face ray toward it passes through the floor block above it. It is NOT under the
        // visible ore: revealing it opens a hole in the floor, and a hole under the visible ore
        // swallowed that ore's drop whenever the drop fell straight down (the bot does not climb
        // into a pit for a drop), which failed the mission for a reason this test is not about.
        BlockPos concealingFloor = fixture.start().west(1).south(1).below(1);
        BlockPos hiddenOre = fixture.start().west(1).south(1).below(2);
        bot.level().setBlock(visibleOre, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
        bot.level().setBlock(hiddenOre, Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        // A stone pickaxe can harvest both coal and iron ore. If the scope-freeze guarantee this
        // test exists to protect were ever broken, the bot would be fully capable of mining the
        // newly-revealed iron ore too -- so a pass here is not an accident of missing tool tier.
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));

        MineValuablesTask task = new MineValuablesTask(3);
        task.start(bot);
        AtomicBoolean revealed = new AtomicBoolean();

        context.failIfEver(() -> {
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
                bot.level().setBlock(concealingFloor, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                revealed.set(true);
                return;
            }
            if (task.state() != TaskState.COMPLETED) {
                require(context, bot.level().getBlockState(hiddenOre).is(Blocks.IRON_ORE),
                        "mine_valuables touched the newly-revealed ore while still running: "
                                + task.describe());
                return;
            }
            require(context, bot.level().getBlockState(visibleOre).isAir(),
                    "mine_valuables completed without mining the originally visible ore");
            require(context, InventoryAction.countItem(bot, Items.COAL) >= 1,
                    "mine_valuables completed without collecting the originally visible ore's drop");
            require(context, bot.level().getBlockState(hiddenOre).is(Blocks.IRON_ORE),
                    "mine_valuables mined the ore that only became visible after the snapshot was taken");
            require(context, InventoryAction.countItem(bot, Items.RAW_IRON) == 0,
                    "mine_valuables collected raw iron it should never have targeted");
            finish(context, fixture);
        });
    }

    private static Fixture fixture(GameTestHelper context, String name) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(2, 2, 2));
        // Keep every mutation inside FabricGameTest.EMPTY_STRUCTURE (8x8), matching the established
        // convention in this codebase's other single-task GameTests (see GatherPickupGameTests).
        for (int dx = -2; dx <= 3; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = start.offset(dx, 0, dz);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.getFoodData().setSaturation(5.0F);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE, 1));
        return new Fixture(bot, start.immutable(), name);
    }

    private static void tickOrFail(GameTestHelper context, MineValuablesTask task, AIPlayerEntity bot) {
        if (task.state() == TaskState.RUNNING) {
            task.tick(bot);
        }
        if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
            context.fail(Component.nullToEmpty(
                    "mine_valuables ended as " + task.state() + ":" + task.failureReason()));
        }
    }

    private static void finish(GameTestHelper context, Fixture fixture) {
        AIPlayerManager.INSTANCE.despawn(fixture.bot().level().getServer(), fixture.name());
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }

    private record Fixture(AIPlayerEntity bot, BlockPos start, String name) {
    }
}

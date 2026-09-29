package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
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

/**
 * Live bug (session 20260928-230626, bot Moss): the player asked for a stone pickaxe right after
 * a dig_down trip. The bot's surface stance had torches on three horizontal neighbours (a separate
 * torch-clustering bug) and the fourth neighbour was the open mouth of its own dig-down staircase
 * -- air, but with no floor and nothing solid around it. CraftTask.adjacentAir picked that hole
 * anyway (it only checked "is this cell air/entity-free", never whether BuildAction could actually
 * place there), so ensureTable failed instantly with place_crafting_table_failed:
 * support_face_not_visible even though open, legal placement ground existed a few blocks away --
 * and GoalExecutor kept replanning and repeating the identical failure.
 */
public final class CraftTaskTablePlacementGameTests {
    @GameTest(maxTicks = 400)
    public void tablePlacementRelocatesAroundBlockedNeighboursAndDigDownHole(TestContext context) {
        Fixture fixture = spawnWithBlockedNeighbours(context, "CraftRelocateGT", new BlockPos(8, 4, 8));
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 3));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE, 1));

        CraftTask task = new CraftTask(Items.STONE_PICKAXE, 1);
        task.start(bot);
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
                return;
            }
            require(context, task.state() == TaskState.COMPLETED,
                    "table placement did not recover from blocked neighbours/dig-down hole: "
                            + task.failureReason());
            require(context, InventoryAction.countItem(bot, Items.STONE_PICKAXE) == 1,
                    "relocated craft did not produce the stone pickaxe");
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 60)
    public void noReachablePlacementFailsWithClearReasonInsteadOfLoopingSupportFaceNotVisible(TestContext context) {
        // Same blocked-neighbour/dig-down-hole stance as above, but this time EVERY cell within
        // the bounded relocation radius is also blocked, so the bounded search must genuinely
        // exhaust and fail with a distinct, clear reason -- never an infinite/looping retry of the
        // exact support_face_not_visible failure the live bug reported.
        // Both tests of this class run in one batch, each in its own structure cell only 13 blocks apart
        // and each carving a radius-6 (13x13) fixture, so every fixture must stay inside its OWN cell
        // (relative x/z 2..14). A farther offset (this used to be x=24) spilled into the neighbouring
        // test cell, and whichever test ran second overwrote the other's stone/air: the relocate
        // fixture opened this enclosed pocket, a table got placed and this test timed out.
        Fixture fixture = spawnFullyEnclosed(context, "CraftNoPlacementGT", new BlockPos(8, 4, 8));
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 3));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE, 1));

        CraftTask task = new CraftTask(Items.STONE_PICKAXE, 1);
        task.start(bot);
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
                return;
            }
            require(context, task.state() == TaskState.FAILED,
                    "fully-enclosed placement should fail, not " + task.state());
            require(context, "place_crafting_table_failed:no_reachable_placement".equals(task.failureReason()),
                    "expected the clear bounded-search failure reason, got: " + task.failureReason());
            cleanup(context, fixture);
        });
    }

    /** Three torch-occupied neighbours + one open, floor-less dig-down-style hole; open ground a few blocks away. */
    private static Fixture spawnWithBlockedNeighbours(TestContext context, String name, BlockPos relativeFeet) {
        var world = context.getWorld();
        world.setTimeOfDay(1000L);
        BlockPos feet = context.getAbsolutePos(relativeFeet);
        int radius = 6;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos cell = feet.add(dx, 0, dz);
                world.setBlockState(cell.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        // Torches: walkable (empty collision shape) but never a legal crafting-table placement
        // target (not air) -- the torch-clustering bug another worker is fixing, kept intact here
        // as the environment this bug actually reproduces in.
        world.setBlockState(feet.north(), Blocks.TORCH.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(feet.east(), Blocks.TORCH.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(feet.south(), Blocks.TORCH.getDefaultState(), Block.NOTIFY_ALL);
        // The remaining neighbour is the mouth of a dig-down staircase: open air, but with no
        // floor and nothing solid around it at head height -- BuildAction can never find a
        // visible support face there, reproducing support_face_not_visible exactly.
        world.setBlockState(feet.west().down(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(feet.west().down().down(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        return spawnBot(context, world, name, feet);
    }

    /**
     * Solid stone in every direction except the bot's own 1x2 standing pocket: no cell anywhere
     * within (and beyond) the bounded relocation radius is ever air, so the bounded search must
     * exhaust its candidate list (empty -- nothing else is even standable) and fail cleanly.
     */
    private static Fixture spawnFullyEnclosed(TestContext context, String name, BlockPos relativeFeet) {
        var world = context.getWorld();
        world.setTimeOfDay(1000L);
        BlockPos feet = context.getAbsolutePos(relativeFeet);
        int radius = 6;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    world.setBlockState(feet.add(dx, dy, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        return spawnBot(context, world, name, feet);
    }

    private static Fixture spawnBot(
            TestContext context, net.minecraft.server.world.ServerWorld world, String name, BlockPos feet) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(feet),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        return new Fixture(name, bot, feet);
    }

    private static void cleanup(TestContext context, Fixture fixture) {
        AIPlayerManager.INSTANCE.despawn(fixture.bot().getEntityWorld().getServer(), fixture.name());
        context.complete();
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(Text.of(message));
        }
    }

    private record Fixture(String name, AIPlayerEntity bot, BlockPos feet) {
    }
}

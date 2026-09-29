package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.List;
import java.util.Set;

/**
 * Live bug (session 20260928-230626, bot Moss): the player asked the bot to make tool sets "one
 * for you that you keep and give one set for me". None of ToolRegistry's ~59 tools could hand an
 * item to a player (deposit/withdraw only move items in/out of containers, trade only works with
 * villagers), so the model stalled on repeated say(purpose=plan) calls until
 * model_call_budget_exhausted. The recipient here is another bot (a real, strict-survival
 * PlayerEntity target), per the fix's own suggestion for exercising this without a human tester.
 */
public final class GiveItemTaskGameTests {
    @GameTest(maxTicks = 300)
    public void recipientEndsUpHoldingExactlyTheGivenItemsAndGiverInventoryDecreasesByThatCount(
            TestContext context) {
        Fixture fixture = spawnGiverAndRecipient(context, new BlockPos(6, 4, 6), "HoldingGT");
        AIPlayerEntity giver = fixture.giver();
        AIPlayerEntity recipient = fixture.recipient();
        InventoryAction.giveItem(giver, new ItemStack(Items.STONE_PICKAXE, 1));
        InventoryAction.giveItem(giver, new ItemStack(Items.DIRT, 4)); // untouched control stack
        int giverBefore = InventoryAction.countItem(giver, Items.STONE_PICKAXE);

        GiveItemTask task = new GiveItemTask(Items.STONE_PICKAXE, 1, fixture.recipientName());
        task.start(giver);
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(giver);
                return;
            }
            require(context, task.state() == TaskState.COMPLETED,
                    "give_item did not complete: " + task.failureReason());
            require(context, InventoryAction.countItem(giver, Items.STONE_PICKAXE)
                            == giverBefore - 1,
                    "giver's inventory did not decrease by exactly the given count");
            require(context, InventoryAction.countItem(giver, Items.DIRT) == 4,
                    "give_item touched an unrelated stack");
            boolean recipientHolding = InventoryAction.countItem(recipient, Items.STONE_PICKAXE) >= 1;
            boolean droppedNearRecipient = !nearbyItemEntities(context, recipient, Items.STONE_PICKAXE).isEmpty();
            require(context, recipientHolding || droppedNearRecipient,
                    "recipient ended up neither holding the given item nor standing next to a "
                            + "dropped entity of it");
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 60)
    public void insufficientInventoryFailsWithAClearReasonAndTouchesNothing(TestContext context) {
        Fixture fixture = spawnGiverAndRecipient(context, new BlockPos(16, 4, 6), "InsufficientGT");
        AIPlayerEntity giver = fixture.giver();
        InventoryAction.giveItem(giver, new ItemStack(Items.STONE_PICKAXE, 1));

        GiveItemTask task = new GiveItemTask(Items.STONE_PICKAXE, 3, fixture.recipientName());
        task.start(giver);
        for (int tick = 0; tick < 5 && task.state() == TaskState.RUNNING; tick++) {
            task.tick(giver);
        }

        require(context, task.state() == TaskState.FAILED,
                "give_item with insufficient inventory should fail, not " + task.state());
        require(context, "need: minecraft:stone_pickaxe x3".equals(task.failureReason()),
                "expected a clear insufficient-inventory reason, got: " + task.failureReason());
        require(context, InventoryAction.countItem(giver, Items.STONE_PICKAXE) == 1,
                "a failed give must not touch the giver's inventory");
        cleanup(context, fixture);
    }

    private static List<ItemEntity> nearbyItemEntities(
            TestContext context, AIPlayerEntity near, net.minecraft.item.Item item) {
        Box search = near.getBoundingBox().expand(2.0D);
        return context.getWorld().getEntitiesByClass(ItemEntity.class, search,
                entity -> entity.getStack().isOf(item));
    }

    private static Fixture spawnGiverAndRecipient(
            TestContext context, BlockPos relativeFeet, String uniqueSuffix) {
        var world = context.getWorld();
        world.setTimeOfDay(1000L);
        BlockPos feet = context.getAbsolutePos(relativeFeet);
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                BlockPos cell = feet.add(dx, 0, dz);
                world.setBlockState(cell.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        // Distinct names per test method: Fabric GameTest batches can run different tests'
        // structures concurrently on the same server, and AIPlayerManager's bot-name registry is
        // server-global, so reusing a name across test methods races and fails to spawn.
        String giverName = "Give" + uniqueSuffix;
        String recipientName = "Recv" + uniqueSuffix;
        AIPlayerEntity giver = spawnBot(world, giverName, feet);
        AIPlayerEntity recipient = spawnBot(world, recipientName, feet.north(4));
        return new Fixture(giverName, recipientName, giver, recipient);
    }

    private static AIPlayerEntity spawnBot(
            net.minecraft.server.world.ServerWorld world, String name, BlockPos feet) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(feet),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        return bot;
    }

    private static void cleanup(TestContext context, Fixture fixture) {
        AIPlayerManager.INSTANCE.despawn(fixture.giver().getEntityWorld().getServer(), fixture.giverName());
        AIPlayerManager.INSTANCE.despawn(fixture.recipient().getEntityWorld().getServer(), fixture.recipientName());
        context.complete();
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(Text.of(message));
        }
    }

    private record Fixture(String giverName, String recipientName, AIPlayerEntity giver, AIPlayerEntity recipient) {
    }
}

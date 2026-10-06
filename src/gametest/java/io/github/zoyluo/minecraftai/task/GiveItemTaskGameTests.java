package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Set;

/**
 * Live bug (session 20260928-230626, bot Moss): the player asked the bot to make tool sets "one
 * for you that you keep and give one set for me". None of ToolRegistry's ~59 tools could hand an
 * item to a player (deposit/withdraw only move items in/out of containers, trade only works with
 * villagers), so the model stalled on repeated say(purpose=plan) calls until
 * model_call_budget_exhausted. The recipient here is another bot (a real, strict-survival
 * Player target), per the fix's own suggestion for exercising this without a human tester.
 */
public final class GiveItemTaskGameTests {
    @GameTest(maxTicks = 300)
    public void recipientEndsUpHoldingExactlyTheGivenItemsAndGiverInventoryDecreasesByThatCount(
            GameTestHelper context) {
        Fixture fixture = spawnGiverAndRecipient(context, new BlockPos(6, 4, 6), "HoldingGT");
        AIPlayerEntity giver = fixture.giver();
        AIPlayerEntity recipient = fixture.recipient();
        InventoryAction.giveItem(giver, new ItemStack(Items.STONE_PICKAXE, 1));
        InventoryAction.giveItem(giver, new ItemStack(Items.DIRT, 4)); // untouched control stack
        int giverBefore = InventoryAction.countItem(giver, Items.STONE_PICKAXE);

        GiveItemTask task = new GiveItemTask(Items.STONE_PICKAXE, 1, fixture.recipientName());
        task.start(giver);
        context.failIfEver(() -> {
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
    public void insufficientInventoryFailsWithAClearReasonAndTouchesNothing(GameTestHelper context) {
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

    @GameTest(maxTicks = 60)
    public void freshDeliveryGuardFailsBeforeAnyCobblestoneDropOrRouteWork(GameTestHelper context) {
        Fixture fixture = spawnGiverAndRecipient(context, new BlockPos(24, 4, 6), "FreshGuardGT");
        AIPlayerEntity giver = fixture.giver();
        InventoryAction.giveItem(giver, new ItemStack(Items.COBBLESTONE, 64));

        GiveItemTask task = new GiveItemTask(Items.COBBLESTONE, 32, fixture.recipientName(),
                () -> true, true, () -> false);
        task.start(giver);
        task.tick(giver);

        require(context, task.state() == TaskState.FAILED,
                "failed fresh conservation guard must stop the handoff before pathing/drop");
        require(context, "give_item_fresh_quota_lost".equals(task.failureReason()),
                "fresh guard failure reason was not typed: " + task.failureReason());
        require(context, InventoryAction.countItem(giver, Items.COBBLESTONE) == 64,
                "fresh guard failure must not debit protected cobblestone");
        cleanup(context, fixture);
    }

    private static List<ItemEntity> nearbyItemEntities(
            GameTestHelper context, AIPlayerEntity near, net.minecraft.world.item.Item item) {
        AABB search = near.getBoundingBox().inflate(2.0D);
        return context.getLevel().getEntitiesOfClass(ItemEntity.class, search,
                entity -> entity.getItem().is(item));
    }

    private static Fixture spawnGiverAndRecipient(
            GameTestHelper context, BlockPos relativeFeet, String uniqueSuffix) {
        var world = context.getLevel();
        world.setDayTime(1000L);
        BlockPos feet = context.absolutePos(relativeFeet);
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                world.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
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
            net.minecraft.server.level.ServerLevel world, String name, BlockPos feet) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        return bot;
    }

    private static void cleanup(GameTestHelper context, Fixture fixture) {
        AIPlayerManager.INSTANCE.despawn(fixture.giver().level().getServer(), fixture.giverName());
        AIPlayerManager.INSTANCE.despawn(fixture.recipient().level().getServer(), fixture.recipientName());
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }

    private record Fixture(String giverName, String recipientName, AIPlayerEntity giver, AIPlayerEntity recipient) {
    }
}

package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.Set;

/**
 * A collect-and-hand-over request in the real world: the new quota is what reaches the player,
 * logs the bot already carried stay with it, and a handoff that fails leaves the collected quota
 * in the inventory (so a retry can be a plain handoff instead of another collection).
 */
public final class GatherThenGiveGameTests {
    @GameTest(maxTicks = 2400)
    public void aQuotaCollectedAsTwoSpeciesIsHandedOverSpeciesBySpeciesAndOldLogsStay(GameTestHelper context) {
        Fixture fixture = fixture(context, new BlockPos(8, 4, 8), "MixedGT");
        AIPlayerEntity giver = fixture.giver();
        BlockPos feet = giver.blockPosition();
        // Three oak and three birch logs: neither species alone reaches the quota of six, which is
        // exactly the case an extra single-species refinement could not have served here.
        column(giver, feet.east(3), Blocks.OAK_LOG, 3);
        column(giver, feet.west(3), Blocks.BIRCH_LOG, 3);
        InventoryAction.giveItem(giver, new ItemStack(Items.WOODEN_AXE));
        InventoryAction.giveItem(giver, new ItemStack(Items.OAK_LOG, 4)); // carried before the request

        GatherThenGiveTask task = GatherThenGiveTask.genericLogs(6, fixture.recipientName());
        task.start(giver);
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING || task.state() == TaskState.PAUSED) {
                task.tick(giver);
                return;
            }
            require(context, task.state() == TaskState.COMPLETED,
                    "gather_then_give did not complete: " + task.failureReason());
            require(context, InventoryAction.countItem(giver, Items.OAK_LOG) == 4,
                    "the four oak logs carried before the request must stay with the bot, not be handed over");
            require(context, InventoryAction.countItem(giver, Items.BIRCH_LOG) == 0,
                    "all new birch logs belong to the player");
            require(context, heldOrDropped(context, fixture.recipient(), Items.OAK_LOG) == 3,
                    "the player must receive exactly the 3 new oak logs");
            require(context, heldOrDropped(context, fixture.recipient(), Items.BIRCH_LOG) == 3,
                    "the player must receive exactly the 3 new birch logs");
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 1800)
    public void aFailedHandoffLeavesTheCollectedQuotaWithTheBotAndNamesAHandoffFailure(GameTestHelper context) {
        Fixture fixture = fixture(context, new BlockPos(24, 4, 8), "FailedGT");
        AIPlayerEntity giver = fixture.giver();
        column(giver, giver.blockPosition().east(3), Blocks.OAK_LOG, 3);
        InventoryAction.giveItem(giver, new ItemStack(Items.WOODEN_AXE));

        GatherThenGiveTask task = new GatherThenGiveTask(Items.OAK_LOG, 3, "NobodyOnlineGT");
        task.start(giver);
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING || task.state() == TaskState.PAUSED) {
                task.tick(giver);
                return;
            }
            require(context, task.state() == TaskState.FAILED, "an absent player cannot be handed anything");
            require(context, task.failureReason().startsWith(GatherThenGiveTask.HANDOFF_FAILED_PREFIX),
                    "a failure after the collection must say so: " + task.failureReason());
            require(context, InventoryAction.countItem(giver, Items.OAK_LOG) == 3,
                    "the collected quota must still be carried after the failed handoff");
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 2400)
    public void aQuotaWithNoStatedNumberIsWhatTheWindowCollectedAndOldLogsStay(GameTestHelper context) {
        Fixture fixture = fixture(context, new BlockPos(40, 4, 8), "TimedGT");
        AIPlayerEntity giver = fixture.giver();
        BlockPos feet = giver.blockPosition();
        column(giver, feet.east(3), Blocks.OAK_LOG, 3);
        column(giver, feet.west(3), Blocks.BIRCH_LOG, 2);
        InventoryAction.giveItem(giver, new ItemStack(Items.WOODEN_AXE));
        InventoryAction.giveItem(giver, new ItemStack(Items.OAK_LOG, 4)); // carried before the request

        // "Gather some wood and give it to me": the number is whatever a short window collects.
        GatherThenGiveTask task = GatherThenGiveTask.timedLogs(fixture.recipientName(), 300);
        task.start(giver);
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING || task.state() == TaskState.PAUSED) {
                task.tick(giver);
                return;
            }
            require(context, task.state() == TaskState.COMPLETED,
                    "gather_then_give with no count did not complete: " + task.failureReason());
            require(context, InventoryAction.countItem(giver, Items.OAK_LOG) == 4,
                    "the four oak logs carried before the request must stay with the bot");
            require(context, heldOrDropped(context, fixture.recipient(), Items.OAK_LOG) == 3,
                    "the player must receive the 3 oak logs the window collected");
            require(context, heldOrDropped(context, fixture.recipient(), Items.BIRCH_LOG) == 2,
                    "and the 2 birch logs: with no number every new log of any species is handed over");
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 1800)
    public void aWindowThatCollectedNothingHandsNothingOver(GameTestHelper context) {
        Fixture fixture = fixture(context, new BlockPos(56, 4, 8), "EmptyWindowGT");
        AIPlayerEntity giver = fixture.giver();
        InventoryAction.giveItem(giver, new ItemStack(Items.OAK_LOG, 4)); // carried, and no tree in reach

        GatherThenGiveTask task = GatherThenGiveTask.timedLogs(fixture.recipientName(), 100);
        task.start(giver);
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING || task.state() == TaskState.PAUSED) {
                task.tick(giver);
                return;
            }
            require(context, task.state() == TaskState.FAILED, "a window without a log is no handoff");
            require(context, !task.failureReason().startsWith(GatherThenGiveTask.HANDOFF_FAILED_PREFIX),
                    "nothing was collected, so this is a collection failure: " + task.failureReason());
            require(context, InventoryAction.countItem(giver, Items.OAK_LOG) == 4,
                    "the carried logs must not be handed over for a collection that found none");
            require(context, heldOrDropped(context, fixture.recipient(), Items.OAK_LOG) == 0,
                    "the player receives nothing");
            cleanup(context, fixture);
        });
    }

    private static void column(AIPlayerEntity bot, BlockPos base, Block log, int height) {
        for (int dy = 0; dy < height; dy++) {
            bot.level().setBlock(base.above(dy), log.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /** Logs of one species the player is holding plus any resting on the ground next to it. */
    private static int heldOrDropped(GameTestHelper context, AIPlayerEntity player, Item item) {
        int total = InventoryAction.countItem(player, item);
        AABB around = player.getBoundingBox().inflate(6.0D);
        for (ItemEntity entity : context.getLevel().getEntitiesOfClass(ItemEntity.class, around,
                candidate -> candidate.getItem().is(item))) {
            total += entity.getItem().getCount();
        }
        return total;
    }

    private static Fixture fixture(GameTestHelper context, BlockPos relativeFeet, String uniqueSuffix) {
        var world = context.getLevel();
        world.setDayTime(1000L);
        BlockPos feet = context.absolutePos(relativeFeet);
        for (int dx = -7; dx <= 7; dx++) {
            for (int dz = -7; dz <= 7; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                world.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 5; dy++) {
                    world.setBlock(cell.above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        // Distinct names per test method: the bot-name registry is server-global.
        String giverName = "Gtg" + uniqueSuffix;
        String recipientName = "Rcv" + uniqueSuffix;
        AIPlayerEntity giver = spawnBot(world, giverName, feet);
        AIPlayerEntity recipient = spawnBot(world, recipientName, feet.north(5));
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

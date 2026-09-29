package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.Set;

/** Full-inventory proofs for restoring an active mining tool after offhand torch promotion. */
public final class MiningTorchToolRestoreGameTests {
    private static final String BATCH = "miningTorchToolRestoreStrict";

    @GameTest(environment = "minecraftai-gametest:mining_torch_tool_restore_game_tests_descend_torch_attempt_restores_active_stone_pick", maxTicks = 40)
    public void descendTorchAttemptRestoresActiveStonePick(GameTestHelper context) {
        Fixture fixture = spawn(context, "DescendTorchRestoreGT", new BlockPos(4, 4, 4));
        BlockMiner miner = beginActiveStoneClear(context, fixture, false);

        attemptOffhandTorchPlacement(context, fixture);
        DescendToYTask.restoreActiveMiningTool(fixture.bot(), context.getLevel(), miner);

        assertActivePickRestored(context, fixture, miner, "descend");
        cleanup(context, fixture, miner);
    }

    @GameTest(environment = "minecraftai-gametest:mining_torch_tool_restore_game_tests_ore_dig_torch_attempt_restores_active_channel_pick", maxTicks = 40)
    public void oreDigTorchAttemptRestoresActiveChannelPick(GameTestHelper context) {
        Fixture fixture = spawn(context, "OreDigTorchRestoreGT", new BlockPos(10, 4, 4));
        BlockMiner miner = beginActiveStoneClear(context, fixture, true);

        attemptOffhandTorchPlacement(context, fixture);
        OreDigTask.restoreActiveChannelTool(fixture.bot(), context.getLevel(), miner);

        assertActivePickRestored(context, fixture, miner, "ore_dig");
        cleanup(context, fixture, miner);
    }

    private static BlockMiner beginActiveStoneClear(GameTestHelper context,
                                                     Fixture fixture,
                                                     boolean channelPolicy) {
        AIPlayerEntity bot = fixture.bot();
        for (int slot = 0; slot < bot.getInventory().getNonEquipmentItems().size(); slot++) {
            bot.getInventory().getNonEquipmentItems().set(slot, new ItemStack(Items.DIRT));
        }
        bot.getInventory().setSelectedSlot(0);
        bot.getInventory().getNonEquipmentItems().set(0, new ItemStack(Items.STONE_PICKAXE));
        bot.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TORCH));
        bot.getInventory().setChanged();

        BlockMiner miner = new BlockMiner();
        miner.begin(bot, fixture.target(), channelPolicy);
        BlockMiner.Status status = miner.tick(bot);
        require(context, status == BlockMiner.Status.MINING
                        && fixture.target().equals(miner.target())
                        && bot.getMainHandItem().is(Items.STONE_PICKAXE),
                "fixture did not open an active stone-pick clear");
        return miner;
    }

    private static void attemptOffhandTorchPlacement(GameTestHelper context, Fixture fixture) {
        AIPlayerEntity bot = fixture.bot();
        int torchSlot = InventoryAction.findItem(bot, Items.TORCH).orElse(-1);
        require(context, torchSlot == bot.getInventory().getSelectedSlot()
                        && bot.getOffhandItem().is(Items.STONE_PICKAXE),
                "full-main torch promotion did not exchange the active pick into offhand");
        InventoryAction.equipFromSlot(bot, torchSlot);
        BuildAction.placeBlockAt(bot, fixture.torchPos());
    }

    private static void assertActivePickRestored(GameTestHelper context,
                                                 Fixture fixture,
                                                 BlockMiner miner,
                                                 String owner) {
        AIPlayerEntity bot = fixture.bot();
        require(context, fixture.target().equals(miner.target()),
                owner + " torch handoff discarded the active BlockMiner target");
        require(context, bot.getMainHandItem().is(Items.STONE_PICKAXE),
                owner + " torch handoff left the active miner holding "
                        + bot.getMainHandItem().getItem());
        require(context, InventoryAction.countItem(bot, Items.STONE_PICKAXE) == 1
                        && InventoryAction.countItem(bot, Items.DIRT) == 35,
                owner + " torch handoff lost or duplicated a full-inventory stack");
    }

    private static Fixture spawn(GameTestHelper context, String name, BlockPos relativeFeet) {
        var world = context.getLevel();
        BlockPos feet = context.absolutePos(relativeFeet);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                world.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        BlockPos target = feet.north();
        BlockPos torchPos = feet.south();
        world.setBlock(target, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        return new Fixture(name, bot, target, torchPos);
    }

    private static void cleanup(GameTestHelper context, Fixture fixture, BlockMiner miner) {
        miner.cancel(fixture.bot());
        AIPlayerManager.INSTANCE.despawn(fixture.bot().level().getServer(), fixture.name());
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }

    private record Fixture(String name,
                           AIPlayerEntity bot,
                           BlockPos target,
                           BlockPos torchPos) {
    }
}

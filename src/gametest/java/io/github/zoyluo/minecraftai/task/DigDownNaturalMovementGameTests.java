package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.baritone.BaritoneEdits;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.TeleportAudit;
import io.github.zoyluo.minecraftai.gametest.BotFixtureMoves;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.navigation.NavEngine;
import io.github.zoyluo.minecraftai.navigation.NavEngineSelector;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Compatibility coverage for the retired DigDown navigation adapter. Physical excavating
 * journeys belong to the retired task and are deliberately not exercised under strict survival.
 */
public final class DigDownNaturalMovementGameTests {
    private static final int BASE_Y = 130;

    public DigDownNaturalMovementGameTests() {
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
            throw new IllegalStateException(message);
        }
    }

    private static BlockPos buildShaftArena(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(8, BASE_Y, 14));
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -14; dz <= 1; dz++) {
                for (int dy = -14; dy <= 6; dy++) {
                    Block block = dy < 0 ? Blocks.STONE : Blocks.AIR;
                    world.setBlock(feet.offset(dx, dy, dz), block.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        return feet;
    }

    private static AIPlayerEntity spawn(GameTestHelper context, String name, BlockPos feet) {
        ServerLevel world = context.getLevel();
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet), 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        NavEngineSelector.setBotEngine(bot.getUUID(), NavEngine.BARITONE);
        BotFixtureMoves.place(bot, feet);
        bot.setOnGround(true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.getFoodData().setSaturation(20.0F);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        Standability.clearCache();
        TeleportAudit.reset(bot);
        return bot;
    }

    /** The compatibility adapter must refuse a target embedded in stone without excavating. */
    @GameTest(environment = "minecraftai-gametest:dig_down_natural_movement_game_tests_dig_nav_drops_into_the_hole_it_dug", maxTicks = 1200)
    public void hiddenDigNavTargetIsRefusedWithoutExcavation(GameTestHelper context) {
        BlockPos start = buildShaftArena(context);
        AIPlayerEntity bot = spawn(context, "DigNavDropGT", start);
        BlockPos target = start.offset(0, -3, -1);
        io.github.zoyluo.minecraftai.action.BlockMiner miner = new io.github.zoyluo.minecraftai.action.BlockMiner();
        require(context, !io.github.zoyluo.minecraftai.action.DigNav.digStep(bot, miner, target),
                "the hidden DigNav target was admitted for movement");
        require(context, !bot.getActionPack().hasBaritoneRoute(),
                "the hidden DigNav target left a Baritone route active");
        require(context, BaritoneEdits.of(bot.getUUID()).isEmpty(),
                "the hidden DigNav target excavated terrain: " + BaritoneEdits.of(bot.getUUID()));
        context.runAfterDelay(20, () -> {
            require(context, TeleportAudit.corrections(bot) == 0,
                    "the hidden DigNav target teleported the bot");
            require(context, !bot.getActionPack().hasBaritoneRoute(),
                    "the hidden DigNav target began a route later");
            require(context, BaritoneEdits.of(bot.getUUID()).isEmpty(),
                    "the hidden DigNav target later excavated terrain: " + BaritoneEdits.of(bot.getUUID()));
            bot.getActionPack().stopAll();
            NavEngineSelector.clearBotEngine(bot.getUUID());
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), bot.getGameProfile().name());
            context.succeed();
        });
    }
}

package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.baritone.BaritoneEdits;
import io.github.zoyluo.minecraftai.baritone.BaritoneRegistry;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Worst-first gear on Baritone's break tool: the mod's tool policy ({@code ToolSelector.choose}, worst-first) is both the tool a
 * driven bot breaks with and the tool Baritone's cost model prices the break with (patch 0016), so a follow through a stone wall
 * with a wooden, a stone and an iron pickaxe (and a diamond sword, never a digging tool) digs with the WOODEN pickaxe, the price
 * is that of the wooden pickaxe, and the follow still arrives inside the movement budgets of {@link BaritoneEngineToolGameTests}.
 */
public final class BaritoneEngineWorstToolGameTests {
    private static final double ARRIVED = 3.6D;

    @GameTest(environment = "minecraftai-gametest:baritone_engine_worst_tool_game_tests_baritone_digs_stone_wall_with_wooden_pick_and_prices_it", maxTicks = 1400)
    public void baritoneDigsStoneWallWithWoodenPickAndPricesIt(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 6, 14, 5);
        for (int dx = 0; dx <= 2; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                arena.fill(dx, dz, Blocks.STONE, 0, BaritoneEngineArena.CEILING - 1);
            }
        }
        AIPlayerEntity target = arena.spawnHolder("BeWorstToolTgt", arena.cell(7, 0, 0));
        AIPlayerEntity bot = arena.spawnOnBaritone("BeWorstTool", arena.cell(-7, 0, 0));
        bot.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        bot.getInventory().setItem(1, new ItemStack(Items.STONE_PICKAXE));
        bot.getInventory().setItem(2, new ItemStack(Items.WOODEN_PICKAXE));
        bot.getInventory().setItem(3, new ItemStack(Items.DIAMOND_SWORD));
        bot.getInventory().setSelectedSlot(0);

        // The price: the policy hook answers with the wooden pickaxe for stone, from the same snapshot Baritone's cost model takes.
        BaritoneRegistry.INSTANCE.get(bot); // creates the bot's Baritone, which installs the mod's tool policy
        baritone.api.utils.HostEnvironment.ToolPolicy policy = baritone.api.utils.HostEnvironment.toolPolicy();
        arena.require(policy != null, "the mod's tool policy is not installed");
        ItemStack priced = policy.toolFor(policy.snapshot(bot), Blocks.STONE.defaultBlockState());
        arena.require(priced != null && priced.is(Items.WOODEN_PICKAXE), "the cost model would price stone with " + priced);

        FollowTask follow = new FollowTask("BeWorstToolTgt");
        TaskManager.INSTANCE.assign(bot, follow, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_baritone_follow_worst_tool_wall"));
        int[] tick = {0};
        context.failIfEver(() -> {
            int now = ++tick[0];
            arena.require(follow.state() == TaskState.RUNNING, "follow ended early: " + follow.state() + " " + follow.failureReason());
            arena.require(now < 1350, "the follower never got through the wall: pos=" + bot.position()
                    + " breaks=" + BaritoneEdits.of(bot.getUUID(), BaritoneEdits.Kind.BREAK).size());
            if (follow.isWaiting() && bot.getX() > arena.origin.getX() + 2.5D && bot.distanceTo(target) <= ARRIVED) {
                List<BaritoneEdits.Edit> breaks = BaritoneEdits.of(bot.getUUID(), BaritoneEdits.Kind.BREAK);
                arena.require(breaks.size() >= 4, "too few blocks were dug through a three-thick wall: " + breaks.size());
                for (BaritoneEdits.Edit edit : breaks) {
                    arena.require(edit.block().equals("minecraft:stone"), "dug " + edit.block());
                    arena.require(edit.tool().equals("minecraft:wooden_pickaxe"),
                            "broke " + edit.block() + " with " + edit.tool() + " instead of the wooden pickaxe (the priced tool): " + breaks);
                }
                ItemStack iron = bot.getInventory().getItem(0);
                ItemStack stone = bot.getInventory().getItem(1);
                ItemStack wood = bot.getInventory().getItem(2);
                arena.require(iron.is(Items.IRON_PICKAXE) && iron.getDamageValue() == 0, "the iron pickaxe was worn: " + iron.getDamageValue());
                arena.require(stone.is(Items.STONE_PICKAXE) && stone.getDamageValue() == 0, "the stone pickaxe was worn: " + stone.getDamageValue());
                arena.require(wood.is(Items.WOODEN_PICKAXE) && wood.getDamageValue() >= 1, "the wooden pickaxe did not dig: " + wood);
                arena.require(follow.baritoneStarts() >= 1 && BaritoneRegistry.INSTANCE.find(bot.getUUID()) != null, "the route was not Baritone's");
                arena.finish(bot, target);
            }
        });
    }
}

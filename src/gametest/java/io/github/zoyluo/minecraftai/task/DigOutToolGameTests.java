package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.BreakEffort;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import static io.github.zoyluo.minecraftai.task.DigOutTaskGameTests.NOON;
import static io.github.zoyluo.minecraftai.task.DigOutTaskGameTests.cleanUp;
import static io.github.zoyluo.minecraftai.task.DigOutTaskGameTests.require;
import static io.github.zoyluo.minecraftai.task.DigOutTaskGameTests.snapshot;
import static io.github.zoyluo.minecraftai.task.DigOutTaskGameTests.spawnAt;
import static io.github.zoyluo.minecraftai.task.DigOutTaskGameTests.stoneWithRoom;

/**
 * What a bot stuck in the dark digs with. It uses the lowest pickaxe that harvests the rock (never a sword), makes a pickaxe first
 * when it has none and carries the makings of one, digs by hand when it can make nothing, and refuses before the first swing a block
 * that vanilla's own break time says the held tool cannot finish (bedrock; deepslate by hand; ancient debris with an iron pickaxe),
 * typed {@code break_refused:<reason>}.
 *
 * <p>Fixture: {@link DigOutTaskGameTests}'s room under rock, with two cells of rock over the room instead of five. The first rise
 * opens the second roof cell over its landing, letting daylight reach that landing; that is the same factual escape condition the
 * ordinary dig-out uses.</p>
 */
public final class DigOutToolGameTests {
    private static final int THIN_ROOF = 2;

    @GameTest(maxTicks = 3000)
    public void aBotWithNoPickaxeAndTheMakingsOfOneMakesItBeforeItDigsAndDigsWithIt(GameTestHelper context) {
        // A table, three planks and two sticks: the craft places the table in the room, makes the pickaxe and takes the table up again.
        digsWith(context, "DigOutCraftGT", 1180, Craft.PICKAXE_FIRST,
                new ItemStack(Items.CRAFTING_TABLE), new ItemStack(Items.OAK_PLANKS, 3), new ItemStack(Items.STICK, 2));
    }

    @GameTest(maxTicks = 3000)
    public void aBotWithOnlyPlanksMakesTheTableTheSticksAndThePickaxeBeforeItDigs(GameTestHelper context) {
        digsWith(context, "DigOutPlanksGT", 1220, Craft.PICKAXE_FIRST, new ItemStack(Items.OAK_PLANKS, 12));
    }

    @GameTest(maxTicks = 3200)
    public void aBotWithNothingToMakeAPickaxeFromDigsStoneByHandAndLeavesItsSwordAlone(GameTestHelper context) {
        // A hand takes 150 ticks over a block of stone: slow, and legal. A sword would break it no faster and lose durability doing so.
        digsWith(context, "DigOutByHandGT", 1260, Craft.NONE, new ItemStack(Items.WOODEN_SWORD));
    }

    @GameTest(maxTicks = 2400)
    public void aBotWithSeveralPickaxesDigsStoneWithTheLowestOneAndKeepsTheIronOneWhole(GameTestHelper context) {
        digsWith(context, "DigOutLowestGT", 1300, Craft.NONE,
                new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.WOODEN_PICKAXE));
    }

    private enum Craft {
        /** No pickaxe is made: the bot digs with what it carries, by hand when that is nothing that suits. */
        NONE,
        /** A pickaxe was made before the first block of rock was dug. */
        PICKAXE_FIRST
    }

    private static void digsWith(GameTestHelper context, String name, int z, Craft craft, ItemStack... carried) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(20, 5, z));
        stoneWithRoom(world, feet, Blocks.STONE, THIN_ROOF);
        world.setDayTime(NOON);
        AIPlayerEntity bot = spawnAt(context, name, feet);
        // giveItem may consume a source stack while moving it to the bot, so retain the fixture's
        // intended loadout before handing those mutable stacks over.
        boolean onlyWoodenSword = carried.length == 1 && carried[0].is(Items.WOODEN_SWORD);
        for (ItemStack stack : carried) {
            InventoryAction.giveItem(bot, stack);
        }
        Map<BlockPos, BlockState> before = snapshot(world, feet);
        DigOutTask task = new DigOutTask();
        context.runAfterDelay(10, () -> TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_out_tool")));
        int[] dugWhenThePickaxeAppeared = {-1};
        int[] ticks = {0};
        context.failIfEver(() -> {
            require(context, bot.isAlive(), "the bot died");
            require(context, task.state() == TaskState.PENDING || task.state() == TaskState.RUNNING
                    || task.state() == TaskState.COMPLETED, "the task ended as " + task.state() + ": " + task.failureReason());
            if (craft == Craft.PICKAXE_FIRST && dugWhenThePickaxeAppeared[0] < 0 && carriesPickaxe(bot)) {
                dugWhenThePickaxeAppeared[0] = dug(world, before);
            }
            if (task.state() == TaskState.COMPLETED) {
                require(context, !DangerWatcher.isDarkTrapCell(world, bot.blockPosition()),
                        "the dig-out ended in the dark at " + bot.blockPosition());
                require(context, task.risen() >= 1, "the stair never rose: " + task.risen());
                require(context, dug(world, before) >= 4, "almost nothing was dug: " + dug(world, before));
                switch (craft) {
                    case PICKAXE_FIRST -> {
                        require(context, dugWhenThePickaxeAppeared[0] >= 0, "no pickaxe was ever made");
                        require(context, dugWhenThePickaxeAppeared[0] == 0,
                                "the bot dug " + dugWhenThePickaxeAppeared[0] + " cells before the pickaxe was made");
                        require(context, wear(bot, Items.WOODEN_PICKAXE) > 0, "the pickaxe that was made was never used");
                    }
                    case NONE -> {
                        if (onlyWoodenSword) {
                            require(context, !carriesPickaxe(bot), "a pickaxe came from nowhere");
                            require(context, wear(bot, Items.WOODEN_SWORD) == 0,
                                    "the sword was worn down on rock: " + wear(bot, Items.WOODEN_SWORD));
                        } else {
                            require(context, wear(bot, Items.WOODEN_PICKAXE) > 0, "the wooden pickaxe was not the one used");
                            require(context, wear(bot, Items.IRON_PICKAXE) == 0,
                                    "the iron pickaxe was used on stone: " + wear(bot, Items.IRON_PICKAXE));
                        }
                    }
                }
                cleanUp(bot);
                context.succeed();
                return;
            }
            require(context, ++ticks[0] < 2900, "the bot never dug out of the room under the rock: " + bot.blockPosition()
                    + " state=" + task.state() + " risen=" + task.risen() + " dug=" + dug(world, before));
        });
    }

    /** A room whose rock is something the held tool cannot break: the task ends where it says why, before one swing, and nothing is dug. */
    @GameTest(maxTicks = 1300)
    public void deepslateByHandIsRefusedAsTooSlowAndNothingIsDug(GameTestHelper context) {
        refusedEverywhere(context, "DigOutDeepslateGT", 1340, Blocks.DEEPSLATE, null, "dig_out_blocked:break_refused:too_slow");
    }

    @GameTest(maxTicks = 1300)
    public void bedrockIsRefusedAsUnbreakableAndNothingIsDug(GameTestHelper context) {
        refusedEverywhere(context, "DigOutBedrockGT", 1380, Blocks.BEDROCK,
                new ItemStack(Items.STONE_PICKAXE), "dig_out_blocked:break_refused:unbreakable");
    }

    @GameTest(maxTicks = 1300)
    public void ancientDebrisIsRefusedAsTooSlowForAnIronPickaxeAndNothingIsDug(GameTestHelper context) {
        // The wrong tier: an iron pickaxe does not harvest it, and vanilla's break time with it is far over what a break can take.
        refusedEverywhere(context, "DigOutDebrisGT", 1420, Blocks.ANCIENT_DEBRIS,
                new ItemStack(Items.IRON_PICKAXE), "dig_out_blocked:break_refused:too_slow");
    }

    @GameTest(maxTicks = 1300)
    public void obsidianIsRefusedWhateverTheBotHoldsAndNothingIsDug(GameTestHelper context) {
        // Obsidian is not natural terrain for a bot to dig, by the mod-wide break rule, ahead of any question of the tool.
        refusedEverywhere(context, "DigOutObsidianGT", 1460, Blocks.OBSIDIAN,
                new ItemStack(Items.DIAMOND_PICKAXE), "dig_out_blocked:break_refused:not_natural_terrain");
    }

    private static void refusedEverywhere(GameTestHelper context, String name, int z, Block rock, ItemStack carried, String expected) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(20, 5, z));
        stoneWithRoom(world, feet, rock);
        world.setDayTime(NOON);
        AIPlayerEntity bot = spawnAt(context, name, feet);
        if (carried != null) {
            InventoryAction.giveItem(bot, carried);
        }
        Map<BlockPos, BlockState> before = snapshot(world, feet);
        DigOutTask task = new DigOutTask();
        context.runAfterDelay(10, () -> TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_out_refused")));
        int[] ticks = {0};
        context.failIfEver(() -> {
            require(context, bot.isAlive(), "the bot died");
            require(context, task.state() != TaskState.COMPLETED, "the task completed through rock it may not break");
            if (task.state() == TaskState.FAILED) {
                require(context, expected.equals(task.failureReason()),
                        "the task did not give the typed reason " + expected + ": " + task.failureReason());
                require(context, dug(world, before) == 0, "something was dug: " + dug(world, before));
                cleanUp(bot);
                context.succeed();
                return;
            }
            // Swinging at one such block would take its two hundred ticks before the miner gave up: refusing is quick.
            require(context, ++ticks[0] < 1200, "the bot neither dug nor refused within the time a refusal takes: "
                    + bot.blockPosition() + " state=" + task.state());
        });
    }

    /**
     * The numbers behind the refusals, read from the game: the verdict for what the bot holds against the block, by vanilla's own
     * rate. Hands on stone are slow and legal, on deepslate too slow; a wooden pickaxe digs both; obsidian wants a diamond pickaxe
     * and is too slow for an iron one or a hand; bedrock is never broken.
     */
    @GameTest(maxTicks = 100)
    public void breakEffortReadsVanillasRateForWhatTheBotHolds(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(20, 5, 1500));
        stoneWithRoom(world, feet, Blocks.STONE);
        AIPlayerEntity bot = spawnAt(context, "DigOutEffortGT", feet);
        BlockPos target = feet.offset(0, 0, -3);
        // The bot stands on the ground a tick after it joins.
        context.runAfterDelay(10, () -> {
            require(context, bot.onGround(), "fixture: the bot is not on the ground");
            Object[][] cases = {
                    {Blocks.STONE, null, null},
                    {Blocks.DEEPSLATE, null, BreakEffort.TOO_SLOW},
                    {Blocks.DEEPSLATE, Items.WOODEN_PICKAXE, null},
                    {Blocks.STONE, Items.WOODEN_SWORD, null},
                    {Blocks.OBSIDIAN, null, BreakEffort.TOO_SLOW},
                    {Blocks.OBSIDIAN, Items.IRON_PICKAXE, BreakEffort.TOO_SLOW},
                    {Blocks.OBSIDIAN, Items.DIAMOND_PICKAXE, null},
                    {Blocks.ANCIENT_DEBRIS, Items.IRON_PICKAXE, BreakEffort.TOO_SLOW},
                    {Blocks.BEDROCK, Items.DIAMOND_PICKAXE, BreakEffort.UNBREAKABLE},
            };
            for (Object[] row : cases) {
                Block block = (Block) row[0];
                Item tool = (Item) row[1];
                world.setBlock(target, block.defaultBlockState(), Block.UPDATE_ALL);
                bot.getInventory().clearContent();
                bot.getInventory().setSelectedSlot(0);
                if (tool != null) {
                    bot.getInventory().setItem(0, new ItemStack(tool));
                }
                String verdict = BreakEffort.refusal(bot, world, target);
                require(context, java.util.Objects.equals(row[2], verdict), block + " with "
                        + (tool == null ? "a bare hand" : tool.toString()) + ": expected " + row[2] + " but the verdict was " + verdict);
            }
            cleanUp(bot);
            context.succeed();
        });
    }

    private static boolean carriesPickaxe(AIPlayerEntity bot) {
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            if (stack.is(ItemTags.PICKAXES)) {
                return true;
            }
        }
        return bot.getItemBySlot(EquipmentSlot.OFFHAND).is(ItemTags.PICKAXES);
    }

    /** The damage of the (first) stack of {@code item} the bot carries. */
    private static int wear(AIPlayerEntity bot, Item item) {
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            if (stack.is(item)) {
                return stack.getDamageValue();
            }
        }
        ItemStack offhand = bot.getItemBySlot(EquipmentSlot.OFFHAND);
        return offhand.is(item) ? offhand.getDamageValue() : -1;
    }

    /** How many cells of the fixture's rock are open air now that were not before. */
    private static int dug(ServerLevel world, Map<BlockPos, BlockState> before) {
        int dug = 0;
        for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
            if (!entry.getValue().isAir() && world.getBlockState(entry.getKey()).isAir()) {
                dug++;
            }
        }
        return dug;
    }
}

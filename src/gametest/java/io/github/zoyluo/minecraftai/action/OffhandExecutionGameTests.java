package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import io.github.zoyluo.minecraftai.task.CraftTask;
import io.github.zoyluo.minecraftai.task.ResupplyTask;
import io.github.zoyluo.minecraftai.task.TaskManager;
import io.github.zoyluo.minecraftai.task.TaskState;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
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
import java.util.concurrent.atomic.AtomicBoolean;

/** Strict-survival proofs that executable inventory paths honor offhand resources. */
public final class OffhandExecutionGameTests {
    @GameTest(environment = "minecraftai-gametest:offhand_execution_game_tests_raw33_offhand_diamond_pick_is_selected_and_breaks_obsidian", maxTicks = 260)
    public void raw33OffhandDiamondPickIsSelectedAndBreaksObsidian(GameTestHelper context) {
        Fixture fixture = spawn(context, "OffhandObsidianPickGT", new BlockPos(4, 4, 4));
        AIPlayerEntity bot = fixture.bot();
        BlockPos target = fixture.feet().north();
        context.getLevel().setBlock(
                target, Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);

        // A completely full main inventory forces the offhand promotion to exchange with the
        // selected hotbar slot. The displaced dirt must remain in offhand, never disappear.
        for (int slot = 0; slot < bot.getInventory().getNonEquipmentItems().size(); slot++) {
            bot.getInventory().getNonEquipmentItems().set(slot, new ItemStack(Items.DIRT));
        }
        bot.getInventory().setSelectedSlot(0);
        ItemStack diamond = new ItemStack(Items.DIAMOND_PICKAXE);
        diamond.setDamageValue(diamond.getMaxDamage() - 33);
        bot.setItemSlot(EquipmentSlot.OFFHAND, diamond);
        bot.getInventory().setChanged();

        ToolSelector.Selection channel = ToolSelector.equipMiningChannelTool(
                bot, context.getLevel().getBlockState(target));
        require(context, channel.changed()
                        && bot.getMainHandItem().is(Items.DIAMOND_PICKAXE),
                "mining-channel selector did not promote the offhand raw33 pick");
        require(context, bot.getOffhandItem().is(Items.DIRT)
                        && InventoryAction.countItem(bot, Items.DIRT) == 36,
                "full-inventory channel promotion lost the selected main stack");

        // Restore the exact full-inventory boundary so the physical break exercises the ordinary
        // equipBestTool path used by CreateObsidianTask, not the already-promoted main candidate.
        ItemStack promoted = bot.getMainHandItem();
        ItemStack displaced = bot.getOffhandItem();
        bot.getInventory().getNonEquipmentItems().set(bot.getInventory().getSelectedSlot(), displaced);
        bot.setItemSlot(EquipmentSlot.OFFHAND, promoted);
        bot.getInventory().setChanged();

        BlockMiner miner = new BlockMiner();
        miner.begin(bot, target);
        context.failIfEver(() -> {
            BlockMiner.Status status = miner.tick(bot);
            if (status == BlockMiner.Status.FAILED) {
                context.fail(Component.nullToEmpty(
                        "offhand raw33 pick failed physical obsidian break: "
                                + miner.failureReason()));
                return;
            }
            if (status != BlockMiner.Status.DONE) {
                return;
            }
            require(context, context.getLevel().getBlockState(target).isAir(),
                    "BlockMiner reported DONE without physically breaking obsidian");
            require(context, bot.getMainHandItem().is(Items.DIAMOND_PICKAXE)
                            && bot.getMainHandItem().getMaxDamage()
                            - bot.getMainHandItem().getDamageValue() == 32,
                    "physical break did not consume exactly one raw durability");
            require(context, bot.getOffhandItem().is(Items.DIRT)
                            && InventoryAction.countItem(bot, Items.DIRT) == 36,
                    "ordinary tool selection lost the main stack displaced into offhand");
            cleanup(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:offhand_execution_game_tests_mining_channel_breaks_soft_obstruction_with_empty_hand", maxTicks = 80)
    public void miningChannelBreaksSoftObstructionWithEmptyHand(GameTestHelper context) {
        Fixture fixture = spawn(context, "EmptyHandSoftBlockGT", new BlockPos(7, 4, 4));
        AIPlayerEntity bot = fixture.bot();
        BlockPos target = fixture.feet().north();
        context.getLevel().setBlock(
                target, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);

        // Reproduce the strict water-return boundary: the selected hand is empty and every
        // remaining hotbar candidate is an unusable raw-1 stone pick. Dirt is still legally and
        // physically mineable by hand, so the mining-channel policy must not report a missing tool.
        bot.getInventory().setSelectedSlot(0);
        for (int slot = 1; slot < 9; slot++) {
            ItemStack exhausted = new ItemStack(Items.STONE_PICKAXE);
            exhausted.setDamageValue(exhausted.getMaxDamage() - 1);
            bot.getInventory().getNonEquipmentItems().set(slot, exhausted);
        }
        bot.getInventory().setChanged();

        BlockMiner miner = new BlockMiner();
        miner.begin(bot, target, true);
        context.failIfEver(() -> {
            BlockMiner.Status status = miner.tick(bot);
            if (status == BlockMiner.Status.FAILED) {
                context.fail(Component.nullToEmpty(
                        "empty-hand soft obstruction failed: " + miner.failureReason()));
                return;
            }
            if (status != BlockMiner.Status.DONE) {
                return;
            }
            require(context, context.getLevel().getBlockState(target).isAir(),
                    "BlockMiner reported DONE without physically breaking grass");
            require(context, bot.getMainHandItem().isEmpty(),
                    "soft obstruction consumed or equipped an exhausted mining tool");
            for (int slot = 1; slot < 9; slot++) {
                ItemStack stack = bot.getInventory().getNonEquipmentItems().get(slot);
                require(context, stack.is(Items.STONE_PICKAXE)
                                && stack.getMaxDamage() - stack.getDamageValue() == 1,
                        "soft obstruction changed raw-1 stone pick in slot " + slot);
            }
            cleanup(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:offhand_execution_game_tests_soft_block_speed_tie_preserves_wooden_sword_durability", maxTicks = 80)
    public void softBlockSpeedTiePreservesWoodenSwordDurability(GameTestHelper context) {
        Fixture fixture = spawn(context, "SoftBlockSwordPreserveGT", new BlockPos(7, 4, 7));
        AIPlayerEntity bot = fixture.bot();
        BlockPos target = fixture.feet().north();
        context.getLevel().setBlock(
                target, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);

        ItemStack sword = new ItemStack(Items.WOODEN_SWORD);
        sword.setDamageValue(9);
        bot.getInventory().setSelectedSlot(0);
        bot.getInventory().getNonEquipmentItems().set(0, sword);
        bot.getInventory().setChanged();

        BlockMiner miner = new BlockMiner();
        miner.begin(bot, target);
        context.failIfEver(() -> {
            BlockMiner.Status status = miner.tick(bot);
            if (status == BlockMiner.Status.FAILED) {
                context.fail(Component.nullToEmpty(
                        "soft-block sword preservation failed: " + miner.failureReason()));
                return;
            }
            if (status != BlockMiner.Status.DONE) {
                return;
            }
            require(context, context.getLevel().getBlockState(target).isAir(),
                    "soft-block preservation reported DONE without breaking dirt");
            require(context, bot.getMainHandItem().isEmpty(),
                    "soft-block speed tie retained a durability-bearing melee weapon");
            require(context, bot.getInventory().getNonEquipmentItems().get(0).is(Items.WOODEN_SWORD)
                            && bot.getInventory().getNonEquipmentItems().get(0).getDamageValue() == 9,
                    "soft-block mining consumed wooden-sword durability");
            cleanup(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:offhand_execution_game_tests_offhand_only_crafting_table_completes_three_by_three_recipe", maxTicks = 350)
    public void offhandOnlyCraftingTableCompletesThreeByThreeRecipe(GameTestHelper context) {
        Fixture fixture = spawn(context, "OffhandCraftingTableGT", new BlockPos(9, 4, 4));
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_INGOT, 3));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 2));
        bot.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.CRAFTING_TABLE));
        bot.getInventory().setChanged();

        CraftTask task = new CraftTask(Items.IRON_PICKAXE, 1);
        task.start(bot);
        // A crafting table has no required tool, so this bot breaks it bare-handed in
        // RECLAIMING_TABLE via the real BlockMiner/ActionPack mining loop, which only advances on
        // genuine per-tick AIPlayerEntity.tick() calls -- so this must poll across real GameTest
        // ticks (failIfEver), not a single synchronous burst of task.tick(bot) calls.
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
                return;
            }
            require(context, task.state() == TaskState.COMPLETED,
                    "offhand-only table could not complete 3x3 craft: " + task.failureReason());
            require(context, InventoryAction.countItem(bot, Items.IRON_PICKAXE) == 1,
                    "3x3 craft did not produce the iron pickaxe");
            require(context, bot.getInventory().getNonEquipmentItems().stream()
                            .anyMatch(stack -> stack.is(Items.CRAFTING_TABLE)),
                    "CraftTask did not promote the offhand-only table into executable inventory");
            require(context, InventoryAction.countItem(bot, Items.CRAFTING_TABLE) == 1
                            && InventoryAction.countItem(bot, Items.IRON_INGOT) == 0
                            && InventoryAction.countItem(bot, Items.STICK) == 0,
                    "3x3 craft duplicated or lost table/ingredients");
            cleanup(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:offhand_execution_game_tests_mixed_oak_and_birch_logs_complete_one_atomic_stick_plan", maxTicks = 40)
    public void mixedOakAndBirchLogsCompleteOneAtomicStickPlan(GameTestHelper context) {
        Fixture fixture = spawn(context, "MixedFamilyCraftGT", new BlockPos(12, 4, 4));
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.BIRCH_LOG, 6));
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_PLANKS, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 2));

        CraftTask task = new CraftTask(Items.STICK, 58);
        task.start(bot);
        for (int tick = 0; tick < 12 && task.state() == TaskState.RUNNING; tick++) {
            task.tick(bot);
        }

        require(context, task.state() == TaskState.COMPLETED,
                "mixed-family craft failed: " + task.failureReason());
        int remainingPlanks = InventoryAction.countItem(bot, Items.OAK_PLANKS)
                + InventoryAction.countItem(bot, Items.BIRCH_PLANKS);
        int remainingLogs = InventoryAction.countItem(bot, Items.OAK_LOG)
                + InventoryAction.countItem(bot, Items.BIRCH_LOG);
        require(context, InventoryAction.countItem(bot, Items.STICK) == 58
                        && remainingLogs * 4 + remainingPlanks == 6,
                "mixed-family craft duplicated or stranded inputs/intermediates");
        cleanup(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:offhand_execution_game_tests_insufficient_mixed_family_capacity_leaves_inventory_bit_exact", maxTicks = 40)
    public void insufficientMixedFamilyCapacityLeavesInventoryBitExact(GameTestHelper context) {
        Fixture fixture = spawn(context, "MixedFamilyRollbackGT", new BlockPos(15, 4, 4));
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG));
        InventorySnapshot before = inventorySnapshot(bot);

        CraftTask task = new CraftTask(Items.STICK, 16);
        task.start(bot);
        for (int tick = 0; tick < 4 && task.state() == TaskState.RUNNING; tick++) {
            task.tick(bot);
        }

        require(context, task.state() == TaskState.FAILED,
                "insufficient mixed-family craft did not fail");
        require(context, "need: minecraft:oak_planks x4".equals(task.failureReason()),
                "remaining family deficit was not reported once: " + task.failureReason());
        requireInventoryEquals(context, before, bot);
        require(context, InventoryAction.countItem(bot, Items.OAK_LOG) == 1
                        && InventoryAction.countItem(bot, Items.STICK) == 0
                        && InventoryAction.countItem(bot, Items.OAK_PLANKS) == 0,
                "failed family aggregation changed live inventory");
        cleanup(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:offhand_execution_game_tests_insufficient_craft_output_capacity_leaves_inventory_bit_exact", maxTicks = 40)
    public void insufficientCraftOutputCapacityLeavesInventoryBitExact(GameTestHelper context) {
        Fixture fixture = spawn(context, "AtomicCraftCapacityGT", new BlockPos(14, 4, 4));
        AIPlayerEntity bot = fixture.bot();

        // The recipe consumes its only main-inventory material stack and therefore creates room
        // for just one of five unstackable outputs. Sticks live in offhand to prove that freeing
        // offhand does not count as output capacity. Before the guard this lost all ingredients
        // and inserted one pick before Inventory.add reported failure for the remaining four.
        ItemStack existingPick = new ItemStack(Items.STONE_PICKAXE);
        existingPick.setDamageValue(17);
        bot.getInventory().getNonEquipmentItems().set(0, existingPick);
        bot.getInventory().getNonEquipmentItems().set(1, new ItemStack(Items.COBBLESTONE, 15));
        bot.getInventory().getNonEquipmentItems().set(2, new ItemStack(Items.CRAFTING_TABLE));
        for (int slot = 3; slot < bot.getInventory().getNonEquipmentItems().size(); slot++) {
            bot.getInventory().getNonEquipmentItems().set(slot, new ItemStack(Items.DIRT, slot + 1));
        }
        bot.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.STICK, 10));
        bot.getInventory().setChanged();

        InventorySnapshot before = inventorySnapshot(bot);
        CraftTask task = new CraftTask(Items.STONE_PICKAXE, 6);
        task.start(bot);
        for (int tick = 0; tick < 4 && task.state() == TaskState.RUNNING; tick++) {
            task.tick(bot);
        }

        require(context, task.state() == TaskState.FAILED,
                "capacity-constrained craft did not fail");
        require(context, task.failureReason().equals(
                        "craft_output_capacity:item=minecraft:stone_pickaxe:count=5:available=1"),
                "unexpected capacity failure reason: " + task.failureReason());
        requireInventoryEquals(context, before, bot);
        require(context, InventoryAction.countItem(bot, Items.STONE_PICKAXE) == 1
                        && InventoryAction.countItem(bot, Items.COBBLESTONE) == 15
                        && InventoryAction.countItem(bot, Items.STICK) == 10,
                "capacity failure changed ingredients or existing output counts");
        cleanup(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:offhand_execution_game_tests_full_inventory_tool_resupply_drops_junk_and_crafts_usable_pickaxe", maxTicks = 350)
    public void fullInventoryToolResupplyDropsJunkAndCraftsUsablePickaxe(GameTestHelper context) {
        Fixture fixture = spawn(context, "ResupplyCapacityGT", new BlockPos(17, 4, 4));
        AIPlayerEntity bot = fixture.bot();

        for (int slot = 0; slot < 5; slot++) {
            ItemStack exhausted = new ItemStack(Items.STONE_PICKAXE);
            exhausted.setDamageValue(exhausted.getMaxDamage() - 1);
            bot.getInventory().getNonEquipmentItems().set(slot, exhausted);
        }
        bot.getInventory().getNonEquipmentItems().set(5, new ItemStack(Items.COBBLESTONE, 11));
        bot.getInventory().getNonEquipmentItems().set(6, new ItemStack(Items.CRAFTING_TABLE));
        bot.getInventory().getNonEquipmentItems().set(7, new ItemStack(Items.STICK, 42));
        bot.getInventory().getNonEquipmentItems().set(8, new ItemStack(Items.DIRT, 8));
        for (int slot = 9; slot < bot.getInventory().getNonEquipmentItems().size(); slot++) {
            bot.getInventory().getNonEquipmentItems().set(slot, new ItemStack(Items.NETHERRACK, 64));
        }
        bot.getInventory().setChanged();
        require(context, bot.getInventory().getNonEquipmentItems().stream().noneMatch(ItemStack::isEmpty),
                "fixture did not start with a full main inventory");

        ResupplyTask task = ResupplyTask.tool(Items.STONE_PICKAXE);
        // Assign through TaskManager (rather than a bare task.start/tick) so DangerWatcher's own
        // background scan (BotTickCoordinator runs it for every spawned bot every real tick) sees
        // this bot as already busy with a ResupplyTask and does not race it with a second,
        // independently-assigned one -- which otherwise duplicates the crafted pickaxe once the
        // capacity-recovery retry below makes this task take long enough for that scan to fire.
        // TaskManager.tickAll() (invoked automatically once per real server tick) then drives
        // task.tick(bot) itself; this must poll across real GameTest ticks (failIfEver), not a
        // single synchronous burst of manual tick() calls, since the table-reclaim mining this
        // retry can reach only advances on genuine per-tick AIPlayerEntity.tick() calls.
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_full_inventory_tool_resupply"));
        // A dropped stack lands with real throw velocity and can roll/fall well past a few blocks
        // before settling (it is not pinned to the drop point), so watch for it continuously with a
        // generous radius instead of a single narrow check once the task finishes -- mirroring
        // SmeltFurnacePlacementGameTests' identical capacity-recovery-drop observation.
        AtomicBoolean observedDroppedDirt = new AtomicBoolean();
        context.failIfEver(() -> {
            if (!observedDroppedDirt.get()
                    && context.getLevel().getEntitiesOfClass(ItemEntity.class,
                                    new AABB(fixture.feet()).inflate(16.0D),
                                    entity -> entity.getItem().is(Items.DIRT)
                                            && entity.getItem().getCount() == 8)
                            .stream().findAny().isPresent()) {
                observedDroppedDirt.set(true);
            }
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.COMPLETED,
                    "full-inventory tool resupply failed: " + task.failureReason());
            require(context, InventoryAction.countItem(bot, Items.STONE_PICKAXE) == 6
                            && bot.getMainHandItem().is(Items.STONE_PICKAXE)
                            && bot.getMainHandItem().getDamageValue() == 0,
                    "resupply did not craft and equip one usable stone pickaxe");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 8
                            && InventoryAction.countItem(bot, Items.STICK) == 40
                            && InventoryAction.countItem(bot, Items.DIRT) == 0,
                    "capacity recovery spent or retained the wrong inventory stacks");
            require(context, observedDroppedDirt.get(),
                    "capacity recovery did not create the exact ordinary dirt ItemEntity");
            cleanup(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:offhand_execution_game_tests_offhand_food_promotion_preserves_a_full_selected_slot", maxTicks = 20)
    public void offhandFoodPromotionPreservesAFullSelectedSlot(GameTestHelper context) {
        Fixture fixture = spawn(context, "OffhandFoodGT", new BlockPos(14, 4, 4));
        AIPlayerEntity bot = fixture.bot();
        for (int slot = 0; slot < bot.getInventory().getNonEquipmentItems().size(); slot++) {
            bot.getInventory().getNonEquipmentItems().set(slot, new ItemStack(Items.DIRT));
        }
        bot.getInventory().setSelectedSlot(0);
        bot.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.BREAD));
        bot.getInventory().setChanged();

        int foodSlot = InventoryAction.findFoodSlot(bot);
        require(context, foodSlot == bot.getInventory().getSelectedSlot()
                        && bot.getInventory().getNonEquipmentItems().get(foodSlot).is(Items.BREAD),
                "offhand food was not promoted into a selectable main slot");
        require(context, bot.getOffhandItem().is(Items.DIRT)
                        && InventoryAction.countItem(bot, Items.DIRT) == 36
                        && InventoryAction.countItem(bot, Items.BREAD) == 1,
                "offhand food promotion lost or duplicated a full-inventory stack");
        cleanup(context, fixture);
    }

    private static Fixture spawn(GameTestHelper context, String name, BlockPos relativeFeet) {
        var world = context.getLevel();
        world.setDayTime(1000L);
        BlockPos feet = context.absolutePos(relativeFeet);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                world.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        return new Fixture(name, bot, feet);
    }

    private static void cleanup(GameTestHelper context, Fixture fixture) {
        AIPlayerManager.INSTANCE.despawn(fixture.bot().level().getServer(), fixture.name());
        context.succeed();
    }

    private static InventorySnapshot inventorySnapshot(AIPlayerEntity bot) {
        return new InventorySnapshot(
                bot.getInventory().getNonEquipmentItems().stream().map(ItemStack::copy).toList(),
                List.of(bot.getItemBySlot(EquipmentSlot.OFFHAND).copy()));
    }

    private static void requireInventoryEquals(
            GameTestHelper context, InventorySnapshot expected, AIPlayerEntity bot) {
        require(context, expected.main().size() == bot.getInventory().getNonEquipmentItems().size()
                        && expected.offHand().size() == 1,
                "inventory region size changed during failed craft");
        for (int slot = 0; slot < expected.main().size(); slot++) {
            require(context, ItemStack.matches(
                            expected.main().get(slot), bot.getInventory().getNonEquipmentItems().get(slot)),
                    "main inventory changed at slot " + slot);
        }
        for (int slot = 0; slot < expected.offHand().size(); slot++) {
            require(context, ItemStack.matches(
                            expected.offHand().get(slot), bot.getItemBySlot(EquipmentSlot.OFFHAND)),
                    "offhand inventory changed at slot " + slot);
        }
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }

    private record Fixture(String name, AIPlayerEntity bot, BlockPos feet) {
    }

    private record InventorySnapshot(List<ItemStack> main, List<ItemStack> offHand) {
    }
}

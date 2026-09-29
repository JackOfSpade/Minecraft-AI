package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.loot.RuntimeDropIndex;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real-server regressions for the "gather X means break a block that DROPS X" fix and the
 * optimal-tool-category policy (see GatherQuotaTask.harvestBlocksForItem/startHarvest and
 * GatherToolPolicy): a bot must never roam away from a resource sitting right in front of it just
 * because the target block's own registry name differs from the requested item, and it must never
 * mine with the bare hand or a wrong-category tool when an effective tool category actually
 * exists -- crafting the cheapest adequate one first, or stopping with a clear reason if it can't.
 */
public final class GatherToolPolicyGameTests {
    @GameTest(environment = "minecraftai-gametest:gather_tool_policy_game_tests_shovel_gathers_dirt_from_grass_without_roaming", maxTicks = 800)
    public void shovelGathersDirtFromGrassWithoutRoaming(GameTestHelper context) {
        Fixture fixture = fixture(context, "GatherToolShovelGT", new BlockPos(2, 2, 2), 5);
        AIPlayerEntity bot = fixture.bot();
        placeGrassPatch(bot, fixture.start());
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SHOVEL));

        GatherQuotaTask task = GatherQuotaTask.collectAdditional(Items.DIRT, 8);
        task.start(bot);
        AtomicBoolean sawShovelEquippedDuringHarvest = new AtomicBoolean();

        context.failIfEver(() -> {
            tickOrFail(context, task, bot);
            if (task.describe().contains("phase=HARVEST")) {
                require(context, bot.getMainHandItem().is(Items.WOODEN_SHOVEL),
                        "broke a dirt source without the shovel equipped: " + bot.getMainHandItem());
                sawShovelEquippedDuringHarvest.set(true);
            }
            require(context, !task.describe().contains("phase=ROAM") && !task.describe().contains("phase=EXPLORE"),
                    "grass right in front of the bot should never require roaming: " + task.describe());
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, sawShovelEquippedDuringHarvest.get(),
                    "task completed without ever observing the shovel equipped during harvest");
            require(context, InventoryAction.countItem(bot, Items.DIRT) >= 8,
                    "did not collect the requested dirt");
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_tool_policy_game_tests_crafts_shovel_from_planks_and_sticks_before_gathering", maxTicks = 1200)
    public void craftsShovelFromPlanksAndSticksBeforeGathering(GameTestHelper context) {
        Fixture fixture = fixture(context, "GatherToolCraftGT", new BlockPos(2, 2, 2), 5);
        AIPlayerEntity bot = fixture.bot();
        placeGrassPatch(bot, fixture.start());
        // Enough for a crafting table (4 planks) + a wooden shovel (1 plank + 2 sticks), with
        // margin -- no logs given, so the only path to a shovel is crafting from what's here.
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_PLANKS, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 4));
        require(context, InventoryAction.countItem(bot, Items.WOODEN_SHOVEL) == 0
                        && InventoryAction.countItem(bot, Items.STONE_SHOVEL) == 0,
                "fixture must not already carry a shovel");

        GatherQuotaTask task = GatherQuotaTask.collectAdditional(Items.DIRT, 4);
        task.start(bot);
        AtomicBoolean sawCraftPhase = new AtomicBoolean();
        AtomicBoolean sawShovelEquippedDuringHarvest = new AtomicBoolean();

        context.failIfEver(() -> {
            tickOrFail(context, task, bot);
            if (task.describe().contains("phase=ENSURE_TOOL")) {
                sawCraftPhase.set(true);
            }
            if (task.describe().contains("phase=HARVEST")) {
                require(context, sawCraftPhase.get(),
                        "started harvesting before ever detouring through the tool-crafting phase");
                require(context, bot.getMainHandItem().is(Items.WOODEN_SHOVEL),
                        "broke a dirt source without the crafted shovel equipped: " + bot.getMainHandItem());
                sawShovelEquippedDuringHarvest.set(true);
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, sawCraftPhase.get(), "task never entered the tool-crafting phase");
            require(context, sawShovelEquippedDuringHarvest.get(),
                    "task completed without ever observing the crafted shovel equipped during harvest");
            require(context, InventoryAction.countItem(bot, Items.WOODEN_SHOVEL) >= 1,
                    "did not keep the crafted shovel");
            require(context, InventoryAction.countItem(bot, Items.DIRT) >= 4,
                    "did not collect the requested dirt after crafting a shovel");
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_tool_policy_game_tests_missing_tool_and_materials_stops_without_roaming", maxTicks = 500)
    public void missingToolAndMaterialsStopsWithoutRoaming(GameTestHelper context) {
        Fixture fixture = fixture(context, "GatherToolStopGT", new BlockPos(2, 2, 2), 5);
        AIPlayerEntity bot = fixture.bot();
        placeGrassPatch(bot, fixture.start());
        // Deliberately no shovel and nothing to craft one from (no logs/planks/sticks/cobblestone).

        GatherQuotaTask task = GatherQuotaTask.collectAdditional(Items.DIRT, 8);
        task.start(bot);

        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                require(context, !task.describe().contains("phase=ROAM") && !task.describe().contains("phase=EXPLORE"),
                        "a missing tool must stop the task, not roam looking for another patch: " + task.describe());
                require(context, !task.describe().contains("phase=HARVEST"),
                        "must never mine with the bare hand when the optimal tool category is missing: " + task.describe());
                task.tick(bot);
                return;
            }
            require(context, task.state() == TaskState.FAILED,
                    "missing tool + no craftable materials should fail, got " + task.state());
            require(context, task.failureReason() != null && task.failureReason().startsWith("missing_tool:shovel"),
                    "failure reason did not identify the missing tool category: " + task.failureReason());
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 0,
                    "no dirt should have been collected before stopping");
            require(context, bot.level().getBlockState(fixture.start().offset(1, 0, 0)).is(Blocks.GRASS_BLOCK),
                    "grass in front of the bot was mined despite having no shovel and nothing to craft one from");
            require(context, bot.blockPosition().distSqr(fixture.start()) < 10.0D * 10.0D,
                    "bot wandered away instead of stopping in place");
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_tool_policy_game_tests_pickaxe_preferred_over_shovel_for_cobblestone", maxTicks = 800)
    public void pickaxePreferredOverShovelForCobblestone(GameTestHelper context) {
        Fixture fixture = fixture(context, "GatherToolPickaxeGT", new BlockPos(2, 2, 2), 5);
        AIPlayerEntity bot = fixture.bot();
        for (int dx = 1; dx <= 3; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                bot.level().setBlock(fixture.start().offset(dx, 0, dz),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SHOVEL));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        GatherQuotaTask task = GatherQuotaTask.collectAdditional(Items.COBBLESTONE, 4);
        task.start(bot);
        AtomicBoolean sawPickaxeEquippedDuringHarvest = new AtomicBoolean();

        context.failIfEver(() -> {
            tickOrFail(context, task, bot);
            if (task.describe().contains("phase=HARVEST")) {
                require(context, bot.getMainHandItem().is(Items.STONE_PICKAXE),
                        "broke stone with a non-pickaxe while a pickaxe was available: " + bot.getMainHandItem());
                require(context, !bot.getMainHandItem().is(Items.WOODEN_SHOVEL),
                        "used the shovel to mine cobblestone instead of the pickaxe");
                sawPickaxeEquippedDuringHarvest.set(true);
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, sawPickaxeEquippedDuringHarvest.get(),
                    "task completed without ever observing the pickaxe equipped during harvest");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) >= 4,
                    "did not collect the requested cobblestone");
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_tool_policy_game_tests_break_leaves_needs_no_tool_and_counts_any_leaf_type", maxTicks = 800)
    public void breakLeavesNeedsNoToolAndCountsAnyLeafType(GameTestHelper context) {
        // "break 32 leaves": leaves are shears-optimal and hoe-mineable but need no tool, and the
        // requested count is of physical blocks of ANY leaf type. A bare bot must therefore not stop
        // with missing_tool:shears (2 iron ingots) nor detour through crafting; it breaks exactly N.
        Fixture fixture = fixture(context, "BreakLeavesGT", new BlockPos(2, 2, 2), 5);
        AIPlayerEntity bot = fixture.bot();
        java.util.List<BlockPos> cells = new java.util.ArrayList<>();
        for (int dx = 2; dx <= 3; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos pos = fixture.start().offset(dx, 0, dz);
                var leaves = (dz == 0 ? Blocks.BIRCH_LEAVES : Blocks.OAK_LEAVES).defaultBlockState()
                        .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
                bot.level().setBlock(pos, leaves, Block.UPDATE_ALL);
                cells.add(pos);
            }
        }
        require(context, bot.getInventory().isEmpty(), "fixture must start with an empty inventory");

        GatherQuotaTask task = GatherQuotaTask.breakLeaves(4);
        task.start(bot);

        context.failIfEver(() -> {
            tickOrFail(context, task, bot);
            require(context, !task.describe().contains("phase=ENSURE_TOOL"),
                    "breaking leaves for a count must not detour through tool crafting: " + task.describe());
            require(context, !task.describe().contains("phase=ROAM") && !task.describe().contains("phase=EXPLORE"),
                    "leaves right beside the bot should never require roaming: " + task.describe());
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            int remaining = 0;
            for (BlockPos pos : cells) {
                if (bot.level().getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
                    remaining++;
                }
            }
            require(context, cells.size() - remaining == 4,
                    "break_blocks leaves must break exactly 4 leaf blocks, broke " + (cells.size() - remaining));
            require(context, InventoryAction.countItem(bot, Items.SHEARS) == 0
                            && InventoryAction.countItem(bot, Items.WOODEN_HOE) == 0,
                    "no tool should have been crafted for leaves");
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_tool_policy_game_tests_drop_index_maps_dirt_sources_and_excludes_infrastructure", maxTicks = 40)
    public void dropIndexMapsDirtSourcesAndExcludesInfrastructure(GameTestHelper context) {
        Optional<Set<Block>> deterministic = RuntimeDropIndex.deterministicSourcesFor(Items.DIRT);
        require(context, deterministic.isPresent(), "runtime drop index was not built by real server start");
        Set<Block> sources = deterministic.get();

        require(context, sources.contains(Blocks.DIRT), "dirt drop sources must include dirt itself");
        require(context, sources.contains(Blocks.GRASS_BLOCK), "dirt drop sources must include grass_block");
        require(context, sources.contains(Blocks.PODZOL), "dirt drop sources must include podzol");
        require(context, sources.contains(Blocks.MYCELIUM), "dirt drop sources must include mycelium");
        require(context, !sources.contains(Blocks.FARMLAND),
                "dirt drop sources must exclude farmland (player infrastructure)");
        require(context, !sources.contains(Blocks.DIRT_PATH),
                "dirt drop sources must exclude dirt_path (player infrastructure)");
        // Vanilla loot tables: coarse_dirt/rooted_dirt drop themselves, not plain dirt.
        require(context, !sources.contains(Blocks.COARSE_DIRT),
                "coarse_dirt does not actually drop dirt in vanilla and must not be listed as a source");
        require(context, !sources.contains(Blocks.ROOTED_DIRT),
                "rooted_dirt does not actually drop dirt in vanilla and must not be listed as a source");

        context.succeed();
    }

    @GameTest(environment = "minecraftai-gametest:gather_tool_policy_game_tests_log_bootstrap_crafts_axe_from_empty_inventory", maxTicks = 2000)
    public void logBootstrapCraftsAxeFromEmptyInventory(GameTestHelper context) {
        // The one relaxation of the strict optimal-tool rule (GatherToolPolicy.Bootstrap): logs are
        // axe-optimal but the axe itself comes from logs, so an empty-inventory bot must break the
        // MINIMUM logs by hand (crafting table + wooden axe = 3 logs), craft the axe, then finish
        // the request with it. Never roam/explore, never stop with missing_tool:axe.
        Fixture fixture = fixture(context, "GatherToolBootstrapGT", new BlockPos(2, 2, 2), 5);
        AIPlayerEntity bot = fixture.bot();
        GatherQuotaTask task = GatherQuotaTask.collectAdditional(Items.OAK_LOG, 6);
        runLogBootstrap(context, fixture, task, (hand, axe) -> {
            // The bootstrap minimum is 3 logs, but a legitimate pickup miss (a log that popped away
            // and had to be chased) can add a hand break, so the exact count is not an invariant;
            // "never by hand once an axe is carried" is (enforced every tick in runLogBootstrap).
            require(context, hand >= 1 && hand <= HAND_BREAK_SLACK,
                    "bootstrap should break some logs (at least 1, sanity bound " + HAND_BREAK_SLACK
                            + ") by hand, broke " + hand);
            require(context, axe >= 6,
                    "rest of the request should be gathered with the crafted axe, axe breaks=" + axe);
            require(context, InventoryAction.countItem(bot, Items.WOODEN_AXE) >= 1,
                    "did not keep the crafted wooden axe");
            require(context, InventoryAction.countItem(bot, Items.OAK_LOG) >= 6,
                    "bootstrap logs must not count toward the quota; expected >= 6 logs, had "
                            + InventoryAction.countItem(bot, Items.OAK_LOG));
        }, Integer.MAX_VALUE);
    }

    @GameTest(environment = "minecraftai-gametest:gather_tool_policy_game_tests_break_blocks_on_logs_bootstraps_axe_from_empty_inventory", maxTicks = 2000)
    public void breakBlocksOnLogsBootstrapsAxeFromEmptyInventory(GameTestHelper context) {
        // break_blocks counts PHYSICAL blocks broken, so the bootstrap logs broken by hand are part
        // of the requested 5 (they are not excluded like a gather quota's): at most 3 by hand, the
        // remainder with the crafted axe, and never more than 5 logs broken in total.
        Fixture fixture = fixture(context, "BreakBootstrapGT", new BlockPos(2, 2, 2), 5);
        AIPlayerEntity bot = fixture.bot();
        GatherQuotaTask task = GatherQuotaTask.breakBlocks(Blocks.OAK_LOG, 5);
        runLogBootstrap(context, fixture, task, (hand, axe) -> {
            require(context, hand >= 1 && hand <= 3,
                    "break_blocks bootstrap should break between 1 and 3 logs by hand, broke " + hand);
            require(context, hand + axe == 5,
                    "break_blocks must break exactly 5 logs in total: hand=" + hand + " axe=" + axe);
            require(context, axe >= 2, "the rest of the 5 should be broken with the axe, axe breaks=" + axe);
            require(context, InventoryAction.countItem(bot, Items.WOODEN_AXE) >= 1,
                    "did not keep the crafted wooden axe");
        }, 5);
    }

    @GameTest(environment = "minecraftai-gametest:gather_tool_policy_game_tests_break_blocks_with_carried_crafting_table_needs_fewer_hand_breaks", maxTicks = 2000)
    public void breakBlocksWithCarriedCraftingTableNeedsFewerHandBreaks(GameTestHelper context) {
        // A carried crafting table removes the table's 4 planks from the bootstrap, so only the axe
        // itself (3 planks + 2 sticks = 2 logs) has to come from bare-hand breaks: at most 2.
        Fixture fixture = fixture(context, "BreakBootstrapTableGT", new BlockPos(2, 2, 2), 5);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        GatherQuotaTask task = GatherQuotaTask.breakBlocks(Blocks.OAK_LOG, 5);
        runLogBootstrap(context, fixture, task, (hand, axe) -> {
            require(context, hand >= 1 && hand <= 2,
                    "with a carried crafting table at most 2 logs should be broken by hand, broke " + hand);
            require(context, hand + axe == 5,
                    "break_blocks must break exactly 5 logs in total: hand=" + hand + " axe=" + axe);
            require(context, InventoryAction.countItem(bot, Items.WOODEN_AXE) >= 1,
                    "did not craft and keep a wooden axe");
        }, 5);
    }

    @GameTest(environment = "minecraftai-gametest:gather_tool_policy_game_tests_axe_arriving_mid_bootstrap_does_not_under_report_a_new_items_quota", maxTicks = 2000)
    public void axeArrivingMidBootstrapDoesNotUnderReportANewItemsQuota(GameTestHelper context) {
        // The bootstrap plans to discount 3 hand-broken logs from a "gather 6 NEW logs" quota. If an
        // axe arrives by another route after only the first hand break, just that one log was
        // collected; discounting the planned 3 would under-report and make the bot break 2 logs too
        // many. Exactly 6 logs must then be broken with the axe.
        Fixture fixture = fixture(context, "BootstrapAxeArrivesGT", new BlockPos(2, 2, 2), 5);
        AIPlayerEntity bot = fixture.bot();
        GatherQuotaTask task = GatherQuotaTask.collectAdditional(Items.OAK_LOG, 6);
        boolean[] axeGiven = {false};
        runLogBootstrap(context, fixture, task, (hand, axe) -> {
            require(context, hand == 1, "the axe was handed over after the first hand break, hand breaks=" + hand);
            require(context, axe == 6,
                    "a quota of 6 new logs must be met by exactly 6 axe breaks after the one bootstrap log, axe breaks=" + axe);
            require(context, InventoryAction.countItem(bot, Items.OAK_LOG) >= 6,
                    "expected at least 6 logs in the inventory, had " + InventoryAction.countItem(bot, Items.OAK_LOG));
        }, Integer.MAX_VALUE, counts -> {
            if (!axeGiven[0] && counts[0] >= 1) {
                axeGiven[0] = true;
                InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_AXE));
            }
        }, false);
    }

    /** Sanity ceiling for hand breaks in a gather bootstrap (minimum is 3; slack for pickup misses). */
    private static final int HAND_BREAK_SLACK = 6;

    /**
     * Shared driver for the log-bootstrap scenarios: plants a 2x3x2 block of oak logs three blocks
     * east of the start, starts the task, and every tick enforces the invariant that matters --
     * once a wooden axe is in the inventory no log is broken by hand (the equipped tool must be the
     * axe) -- plus a sane ceiling on hand breaks, a ceiling on total logs broken, and "never roam".
     * On completion it hands (handBreaks, axeBreaks) to {@code onComplete}, which adds the
     * scenario-specific expectations.
     */
    private static void runLogBootstrap(GameTestHelper context, Fixture fixture, GatherQuotaTask task,
                                        java.util.function.BiConsumer<Integer, Integer> onComplete,
                                        int maxTotalBroken) {
        runLogBootstrap(context, fixture, task, onComplete, maxTotalBroken, null, true);
    }

    /** As above; {@code afterTick} (nullable) sees {handBreaks, axeBreaks} at the end of every tick. */
    private static void runLogBootstrap(GameTestHelper context, Fixture fixture, GatherQuotaTask task,
                                        java.util.function.BiConsumer<Integer, Integer> onComplete,
                                        int maxTotalBroken,
                                        java.util.function.Consumer<int[]> afterTick,
                                        boolean expectEnsureTool) {
        AIPlayerEntity bot = fixture.bot();
        java.util.List<BlockPos> treeCells = new java.util.ArrayList<>();
        for (int dx = 3; dx <= 4; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos pos = fixture.start().offset(dx, dy, dz);
                    bot.level().setBlock(pos, Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
                    treeCells.add(pos);
                }
            }
        }
        boolean carriesTable = InventoryAction.countItem(bot, Items.CRAFTING_TABLE) > 0;
        require(context, carriesTable || bot.getInventory().isEmpty(),
                "fixture must start with an empty inventory (or only the crafting table)");

        task.start(bot);
        AtomicBoolean sawEnsureTool = new AtomicBoolean();
        int[] previousRemaining = {treeCells.size()};
        int[] handBreaks = {0};
        int[] axeBreaks = {0};
        // Whether a wooden axe was already carried when the previous tick ended: a log broken this
        // tick with an axe carried since before it began must have been broken with that axe.
        boolean[] axeCarriedBefore = {false};

        context.failIfEver(() -> {
            tickOrFail(context, task, bot);
            if (task.describe().contains("phase=ENSURE_TOOL")) {
                sawEnsureTool.set(true);
            }
            int remaining = 0;
            for (BlockPos pos : treeCells) {
                if (bot.level().getBlockState(pos).is(Blocks.OAK_LOG)) {
                    remaining++;
                }
            }
            int broken = previousRemaining[0] - remaining;
            if (broken > 0) {
                if (bot.getMainHandItem().is(Items.WOODEN_AXE)) {
                    axeBreaks[0] += broken;
                } else {
                    handBreaks[0] += broken;
                    require(context, !axeCarriedBefore[0],
                            "a log was broken by hand although a wooden axe was already in the inventory (main hand "
                                    + bot.getMainHandItem() + ", hand breaks=" + handBreaks[0] + ")");
                }
            }
            previousRemaining[0] = remaining;
            axeCarriedBefore[0] = InventoryAction.countItem(bot, Items.WOODEN_AXE) > 0;
            if (afterTick != null) {
                afterTick.accept(new int[] {handBreaks[0], axeBreaks[0]});
            }
            require(context, handBreaks[0] <= HAND_BREAK_SLACK,
                    "implausibly many logs broken by hand: " + handBreaks[0]);
            require(context, handBreaks[0] + axeBreaks[0] <= maxTotalBroken,
                    "broke more logs than requested: hand=" + handBreaks[0] + " axe=" + axeBreaks[0]
                            + " max=" + maxTotalBroken);
            require(context, !task.describe().contains("phase=ROAM") && !task.describe().contains("phase=EXPLORE"),
                    "a tree right beside the bot should never require roaming: " + task.describe());
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, !expectEnsureTool || sawEnsureTool.get() || carriesTable,
                    "task never detoured through the axe-crafting phase");
            onComplete.accept(handBreaks[0], axeBreaks[0]);
            finish(context, fixture);
        });
    }

    /** A 3x3 patch of grass_block at floor level immediately in front of the bot's spawn point. */
    private static void placeGrassPatch(AIPlayerEntity bot, BlockPos start) {
        for (int dx = 1; dx <= 3; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                bot.level().setBlock(start.offset(dx, 0, dz),
                        Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    private static Fixture fixture(GameTestHelper context, String name, BlockPos relativeStart, int east) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(relativeStart);
        // Keep every mutation inside FabricGameTest.EMPTY_STRUCTURE (8x8); see GatherPickupGameTests.
        for (int dx = -2; dx <= east; dx++) {
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
        return new Fixture(bot, start, name);
    }

    private static void tickOrFail(GameTestHelper context, GatherQuotaTask task, AIPlayerEntity bot) {
        if (task.state() == TaskState.RUNNING) {
            task.tick(bot);
        }
        if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
            context.fail(Component.nullToEmpty("gather ended as " + task.state() + ":" + task.failureReason()));
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

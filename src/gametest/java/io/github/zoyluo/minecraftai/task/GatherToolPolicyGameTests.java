package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.loot.RuntimeDropIndex;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

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
    public void shovelGathersDirtFromGrassWithoutRoaming(TestContext context) {
        Fixture fixture = fixture(context, "GatherToolShovelGT", new BlockPos(2, 2, 2), 5);
        AIPlayerEntity bot = fixture.bot();
        placeGrassPatch(bot, fixture.start());
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SHOVEL));

        GatherQuotaTask task = GatherQuotaTask.collectAdditional(Items.DIRT, 8);
        task.start(bot);
        AtomicBoolean sawShovelEquippedDuringHarvest = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            tickOrFail(context, task, bot);
            if (task.describe().contains("phase=HARVEST")) {
                require(context, bot.getMainHandStack().isOf(Items.WOODEN_SHOVEL),
                        "broke a dirt source without the shovel equipped: " + bot.getMainHandStack());
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
    public void craftsShovelFromPlanksAndSticksBeforeGathering(TestContext context) {
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

        context.runAtEveryTick(() -> {
            tickOrFail(context, task, bot);
            if (task.describe().contains("phase=ENSURE_TOOL")) {
                sawCraftPhase.set(true);
            }
            if (task.describe().contains("phase=HARVEST")) {
                require(context, sawCraftPhase.get(),
                        "started harvesting before ever detouring through the tool-crafting phase");
                require(context, bot.getMainHandStack().isOf(Items.WOODEN_SHOVEL),
                        "broke a dirt source without the crafted shovel equipped: " + bot.getMainHandStack());
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
    public void missingToolAndMaterialsStopsWithoutRoaming(TestContext context) {
        Fixture fixture = fixture(context, "GatherToolStopGT", new BlockPos(2, 2, 2), 5);
        AIPlayerEntity bot = fixture.bot();
        placeGrassPatch(bot, fixture.start());
        // Deliberately no shovel and nothing to craft one from (no logs/planks/sticks/cobblestone).

        GatherQuotaTask task = GatherQuotaTask.collectAdditional(Items.DIRT, 8);
        task.start(bot);

        context.runAtEveryTick(() -> {
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
            require(context, bot.getEntityWorld().getBlockState(fixture.start().add(1, 0, 0)).isOf(Blocks.GRASS_BLOCK),
                    "grass in front of the bot was mined despite having no shovel and nothing to craft one from");
            require(context, bot.getBlockPos().getSquaredDistance(fixture.start()) < 10.0D * 10.0D,
                    "bot wandered away instead of stopping in place");
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_tool_policy_game_tests_pickaxe_preferred_over_shovel_for_cobblestone", maxTicks = 800)
    public void pickaxePreferredOverShovelForCobblestone(TestContext context) {
        Fixture fixture = fixture(context, "GatherToolPickaxeGT", new BlockPos(2, 2, 2), 5);
        AIPlayerEntity bot = fixture.bot();
        for (int dx = 1; dx <= 3; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                bot.getEntityWorld().setBlockState(fixture.start().add(dx, 0, dz),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SHOVEL));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        GatherQuotaTask task = GatherQuotaTask.collectAdditional(Items.COBBLESTONE, 4);
        task.start(bot);
        AtomicBoolean sawPickaxeEquippedDuringHarvest = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            tickOrFail(context, task, bot);
            if (task.describe().contains("phase=HARVEST")) {
                require(context, bot.getMainHandStack().isOf(Items.STONE_PICKAXE),
                        "broke stone with a non-pickaxe while a pickaxe was available: " + bot.getMainHandStack());
                require(context, !bot.getMainHandStack().isOf(Items.WOODEN_SHOVEL),
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

    @GameTest(environment = "minecraftai-gametest:gather_tool_policy_game_tests_drop_index_maps_dirt_sources_and_excludes_infrastructure", maxTicks = 40)
    public void dropIndexMapsDirtSourcesAndExcludesInfrastructure(TestContext context) {
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

        context.complete();
    }

    @GameTest(environment = "minecraftai-gametest:gather_tool_policy_game_tests_log_bootstrap_crafts_axe_from_empty_inventory", maxTicks = 2000)
    public void logBootstrapCraftsAxeFromEmptyInventory(TestContext context) {
        // The one relaxation of the strict optimal-tool rule (GatherToolPolicy.Bootstrap): logs are
        // axe-optimal but the axe itself comes from logs, so an empty-inventory bot must break the
        // MINIMUM logs by hand (crafting table + wooden axe = 3 logs), craft the axe, then finish
        // the request with it. Never roam/explore, never stop with missing_tool:axe.
        Fixture fixture = fixture(context, "GatherToolBootstrapGT", new BlockPos(2, 2, 2), 5);
        AIPlayerEntity bot = fixture.bot();
        java.util.List<BlockPos> treeCells = new java.util.ArrayList<>();
        for (int dx = 3; dx <= 4; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos pos = fixture.start().add(dx, dy, dz);
                    bot.getEntityWorld().setBlockState(pos, Blocks.OAK_LOG.getDefaultState(), Block.NOTIFY_ALL);
                    treeCells.add(pos);
                }
            }
        }
        require(context, bot.getInventory().isEmpty(), "fixture must start with an empty inventory");

        GatherQuotaTask task = GatherQuotaTask.collectAdditional(Items.OAK_LOG, 6);
        task.start(bot);
        AtomicBoolean sawEnsureTool = new AtomicBoolean();
        int[] previousRemaining = {treeCells.size()};
        int[] handBreaks = {0};
        int[] axeBreaks = {0};

        context.runAtEveryTick(() -> {
            tickOrFail(context, task, bot);
            if (task.describe().contains("phase=ENSURE_TOOL")) {
                sawEnsureTool.set(true);
            }
            int remaining = 0;
            for (BlockPos pos : treeCells) {
                if (bot.getEntityWorld().getBlockState(pos).isOf(Blocks.OAK_LOG)) {
                    remaining++;
                }
            }
            int broken = previousRemaining[0] - remaining;
            if (broken > 0) {
                if (bot.getMainHandStack().isOf(Items.WOODEN_AXE)) {
                    axeBreaks[0] += broken;
                } else {
                    handBreaks[0] += broken;
                }
            }
            previousRemaining[0] = remaining;
            require(context, handBreaks[0] <= 3,
                    "broke more than the bootstrap minimum (3 logs) by hand: " + handBreaks[0]);
            require(context, !task.describe().contains("phase=ROAM") && !task.describe().contains("phase=EXPLORE"),
                    "a tree right beside the bot should never require roaming: " + task.describe());
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, sawEnsureTool.get(), "task never detoured through the axe-crafting phase");
            require(context, handBreaks[0] >= 1 && handBreaks[0] <= 3,
                    "bootstrap should break between 1 and 3 logs by hand, broke " + handBreaks[0]);
            require(context, axeBreaks[0] >= 6,
                    "rest of the request should be gathered with the crafted axe, axe breaks=" + axeBreaks[0]);
            require(context, InventoryAction.countItem(bot, Items.WOODEN_AXE) >= 1,
                    "did not keep the crafted wooden axe");
            require(context, InventoryAction.countItem(bot, Items.OAK_LOG) >= 6,
                    "bootstrap logs must not count toward the quota; expected >= 6 logs, had "
                            + InventoryAction.countItem(bot, Items.OAK_LOG));
            finish(context, fixture);
        });
    }

    /** A 3x3 patch of grass_block at floor level immediately in front of the bot's spawn point. */
    private static void placeGrassPatch(AIPlayerEntity bot, BlockPos start) {
        for (int dx = 1; dx <= 3; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                bot.getEntityWorld().setBlockState(start.add(dx, 0, dz),
                        Blocks.GRASS_BLOCK.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
    }

    private static Fixture fixture(TestContext context, String name, BlockPos relativeStart, int east) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(relativeStart);
        // Keep every mutation inside FabricGameTest.EMPTY_STRUCTURE (8x8); see GatherPickupGameTests.
        for (int dx = -2; dx <= east; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = start.add(dx, 0, dz);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        return new Fixture(bot, start, name);
    }

    private static void tickOrFail(TestContext context, GatherQuotaTask task, AIPlayerEntity bot) {
        if (task.state() == TaskState.RUNNING) {
            task.tick(bot);
        }
        if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
            context.throwGameTestException(Text.of("gather ended as " + task.state() + ":" + task.failureReason()));
        }
    }

    private static void finish(TestContext context, Fixture fixture) {
        AIPlayerManager.INSTANCE.despawn(fixture.bot().getEntityWorld().getServer(), fixture.name());
        context.complete();
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(Text.of(message));
        }
    }

    private record Fixture(AIPlayerEntity bot, BlockPos start, String name) {
    }
}

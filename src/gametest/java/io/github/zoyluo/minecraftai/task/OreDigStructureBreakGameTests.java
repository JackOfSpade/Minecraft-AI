package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mining.BreakRule;
import io.github.zoyluo.minecraftai.pathfinding.NeighborEnumerator;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The shared natural-terrain break rule ({@link BreakRule}) as the legacy diggers see it, and the vanilla cadence of legacy breaks.
 *
 * <ul>
 *   <li>the verdict of every family of block: structure and player-build material refused (stone bricks and their cracked,
 *       mossy and chiseled forms, slabs, stairs, planks, glass, wool, infested blocks, block entities ...), the terrain the world
 *       generator makes allowed (sandstone, terracotta, deepslate, tuff, ice, dripstone, soul sand, basalt ...);</li>
 *   <li>{@link BlockMiner} in its natural-terrain-only mode (what OreDig uses) fails a structure block without touching it and
 *       breaks a natural one; {@link NeighborEnumerator#isMineable} (the path search's dig-through rule) agrees;</li>
 *   <li>OreDig itself, blind strip mining northwards: through a lane of natural blocks it digs, through a lane of structure blocks it
 *       does not break a single one;</li>
 *   <li>after a break that took several ticks the next legacy break waits five ticks (vanilla's destroyDelay); an instant break
 *       sets no delay.</li>
 * </ul>
 */
public final class OreDigStructureBreakGameTests {
    // ---------------------------------------------------------------------------------------------------------------
    // The verdict
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:ore_dig_structure_break_game_tests_break_rule_refuses_structure_blocks_and_allows_natural_terrain", maxTicks = 20)
    public void breakRuleRefusesStructureBlocksAndAllowsNaturalTerrain(GameTestHelper context) {
        Map<Block, String> expected = new LinkedHashMap<>();
        // Structure and player-build material.
        for (Block block : List.of(Blocks.STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS,
                Blocks.STONE_BRICK_SLAB, Blocks.STONE_SLAB, Blocks.OAK_SLAB, Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_WALL,
                Blocks.OAK_PLANKS, Blocks.OAK_FENCE, Blocks.OAK_DOOR, Blocks.BRICKS, Blocks.NETHER_BRICKS, Blocks.DEEPSLATE_BRICKS,
                Blocks.DEEPSLATE_TILES, Blocks.POLISHED_ANDESITE, Blocks.POLISHED_DEEPSLATE, Blocks.CUT_SANDSTONE, Blocks.CHISELED_SANDSTONE,
                Blocks.SMOOTH_STONE, Blocks.SMOOTH_SANDSTONE, Blocks.GLASS, Blocks.GLASS_PANE, Blocks.WHITE_WOOL, Blocks.WHITE_CONCRETE,
                Blocks.WHITE_CARPET, Blocks.IRON_BARS)) {
            expected.put(block, "structure_block");
        }
        for (Block block : List.of(Blocks.INFESTED_STONE, Blocks.INFESTED_COBBLESTONE, Blocks.INFESTED_STONE_BRICKS, Blocks.INFESTED_DEEPSLATE)) {
            expected.put(block, "infested_block");
        }
        for (Block block : List.of(Blocks.CHEST, Blocks.FURNACE, Blocks.SPAWNER, Blocks.WHITE_BED, Blocks.OAK_SIGN, Blocks.BARREL, Blocks.HOPPER)) {
            expected.put(block, "block_entity");
        }
        expected.put(Blocks.CRAFTING_TABLE, "protected_block");
        expected.put(Blocks.ANVIL, "protected_block");
        expected.put(Blocks.BEDROCK, "unbreakable");
        expected.put(Blocks.WATER, "fluid");
        expected.put(Blocks.LAVA, "fluid");
        expected.put(Blocks.MAGMA_BLOCK, "dangerous_block");
        expected.put(Blocks.POINTED_DRIPSTONE, "dangerous_block");
        expected.put(Blocks.COBWEB, "not_natural_terrain");
        expected.put(Blocks.OAK_LOG, "not_natural_terrain");
        expected.put(Blocks.OBSIDIAN, "not_natural_terrain");
        // Natural terrain: allowed (null).
        List<Block> natural = List.of(Blocks.STONE, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE, Blocks.TUFF, Blocks.DEEPSLATE,
                Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.SAND, Blocks.RED_SAND,
                Blocks.GRAVEL, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.CLAY, Blocks.MUD, Blocks.TERRACOTTA, Blocks.ORANGE_TERRACOTTA,
                Blocks.BROWN_TERRACOTTA, Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.SNOW_BLOCK, Blocks.DRIPSTONE_BLOCK,
                Blocks.CALCITE, Blocks.SMOOTH_BASALT, Blocks.BASALT, Blocks.BLACKSTONE, Blocks.NETHERRACK, Blocks.SOUL_SAND, Blocks.SOUL_SOIL,
                Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM, Blocks.END_STONE, Blocks.AMETHYST_BLOCK, Blocks.SCULK, Blocks.OAK_LEAVES,
                Blocks.SHORT_GRASS, Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.NETHER_GOLD_ORE,
                Blocks.ANCIENT_DEBRIS, Blocks.GLOWSTONE);
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<Block, String> entry : expected.entrySet()) {
            String verdict = BreakRule.denialOf(entry.getKey());
            if (!entry.getValue().equals(verdict)) {
                wrong.add(entry.getKey() + " -> " + verdict + " (expected " + entry.getValue() + ")");
            }
        }
        for (Block block : natural) {
            String verdict = BreakRule.denialOf(block);
            if (verdict != null) {
                wrong.add(block + " -> " + verdict + " (expected allowed)");
            }
        }
        require(context, wrong.isEmpty(), "wrong break verdicts: " + wrong);
        require(context, BreakRule.denialOf(Blocks.AIR.defaultBlockState()) == null, "air has nothing to break");
        context.succeed();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // BlockMiner (natural terrain only) and the path search's dig rule
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:ore_dig_structure_break_game_tests_block_miner_refuses_structure_blocks_without_touching_them_and_mines_natural_terrain", maxTicks = 1200)
    public void blockMinerRefusesStructureBlocksWithoutTouchingThemAndMinesNaturalTerrain(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 6, 8, 6);
        AIPlayerEntity bot = arena.spawn("StructMinerGT", arena.cell(0, 0, 0));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        BlockPos target = arena.cell(0, 0, -2);
        List<Block> structure = List.of(Blocks.STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS,
                Blocks.STONE_SLAB, Blocks.OAK_PLANKS, Blocks.INFESTED_STONE, Blocks.INFESTED_STONE_BRICKS, Blocks.BRICKS, Blocks.GLASS,
                Blocks.WHITE_WOOL, Blocks.OAK_STAIRS, Blocks.CHEST, Blocks.COBWEB);
        BlockMiner refuser = new BlockMiner();
        for (Block block : structure) {
            arena.world.setBlock(target, block.defaultBlockState(), Block.UPDATE_ALL);
            require(context, !NeighborEnumerator.isMineable(arena.world, target), "the path search may dig through " + block);
            refuser.begin(bot, target, false, true);
            BlockMiner.Status status = refuser.tick(bot);
            require(context, status == BlockMiner.Status.FAILED && refuser.failureReason().startsWith("break_refused:"),
                    block + " was not refused: " + status + " " + refuser.failureReason());
            require(context, arena.world.getBlockState(target).is(block), block + " was touched: " + arena.world.getBlockState(target));
            require(context, bot.getActionPack().isMiningIdle(), "a swing was started on " + block);
        }
        // The default (not natural-only) miner keeps its old behaviour for the other tasks: it starts on a structure block too.
        arena.world.setBlock(target, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
        BlockMiner plain = new BlockMiner();
        plain.begin(bot, target);
        require(context, plain.tick(bot) == BlockMiner.Status.MINING, "the plain miner refuses planks (other tasks harvest and reclaim them)");
        plain.cancel(bot);

        List<Block> natural = List.of(Blocks.SANDSTONE, Blocks.TERRACOTTA, Blocks.ORANGE_TERRACOTTA, Blocks.DEEPSLATE, Blocks.TUFF, Blocks.DRIPSTONE_BLOCK,
                Blocks.SOUL_SAND, Blocks.BASALT, Blocks.SMOOTH_BASALT, Blocks.PACKED_ICE, Blocks.CALCITE, Blocks.COBBLESTONE, Blocks.NETHERRACK,
                Blocks.OAK_LEAVES);
        BlockMiner miner = new BlockMiner();
        int[] index = {0};
        int[] tick = {0};
        boolean[] began = {false};
        arena.world.setBlock(target, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        context.failIfEver(() -> {
            arena.require(++tick[0] < 1150, "the natural blocks were not all mined: at " + index[0] + " " + bot.position());
            if (index[0] >= natural.size()) {
                arena.finish(bot);
                return;
            }
            Block block = natural.get(index[0]);
            if (!began[0]) {
                arena.world.setBlock(target, block.defaultBlockState(), Block.UPDATE_ALL);
                require(context, NeighborEnumerator.isMineable(arena.world, target), "the path search may not dig through " + block);
                miner.begin(bot, target, false, true);
                began[0] = true;
            }
            BlockMiner.Status status = miner.tick(bot);
            arena.require(status != BlockMiner.Status.FAILED, block + " was refused or failed: " + miner.failureReason());
            if (status == BlockMiner.Status.DONE) {
                arena.require(arena.world.getBlockState(target).isAir(), block + " reported done but the cell holds " + arena.world.getBlockState(target));
                index[0]++;
                began[0] = false;
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // OreDig itself: a lane of natural blocks is dug, a lane of structure blocks is not
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:ore_dig_structure_break_game_tests_ore_dig_strip_mines_through_natural_terrain_of_every_kind", maxTicks = 1700)
    public void oreDigStripMinesThroughNaturalTerrainOfEveryKind(GameTestHelper context) {
        List<Block> lane = List.of(Blocks.SANDSTONE, Blocks.TERRACOTTA, Blocks.DEEPSLATE, Blocks.TUFF, Blocks.DRIPSTONE_BLOCK,
                Blocks.SOUL_SAND, Blocks.BASALT, Blocks.SMOOTH_BASALT);
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 6, 8, 6);
        List<BlockPos> cells = buildLane(arena, lane);
        AIPlayerEntity bot = arena.spawn("OreDigNatGT", arena.cell(0, 0, 0));
        equip(bot);
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 40);
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_ore_dig_natural_lane"));
        int[] tick = {0};
        context.failIfEver(() -> {
            int now = ++tick[0];
            arena.require(task.state() != TaskState.FAILED && task.state() != TaskState.CANCELLED,
                    "the dig ended as " + task.state() + ": " + task.failureReason());
            int layers = clearedLayers(arena, cells);
            arena.require(now < 1600, "OreDig dug only " + layers + " of " + lane.size() + " layers of natural blocks in " + now + " ticks");
            if (layers >= 5) {
                arena.finish(bot);
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_structure_break_game_tests_ore_dig_never_breaks_astructure_lane", maxTicks = 900)
    public void oreDigNeverBreaksAStructureLane(GameTestHelper context) {
        List<Block> lane = List.of(Blocks.STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS,
                Blocks.STONE_SLAB, Blocks.OAK_PLANKS, Blocks.INFESTED_STONE_BRICKS, Blocks.GLASS);
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 6, 8, 6);
        List<BlockPos> cells = buildLane(arena, lane);
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        for (BlockPos cell : cells) {
            before.put(cell, arena.world.getBlockState(cell));
        }
        AIPlayerEntity bot = arena.spawn("OreDigStructGT", arena.cell(0, 0, 0));
        equip(bot);
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 40);
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_ore_dig_structure_lane"));
        int[] tick = {0};
        BlockPos start = bot.blockPosition();
        context.failIfEver(() -> {
            int now = ++tick[0];
            for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
                arena.require(arena.world.getBlockState(entry.getKey()).equals(entry.getValue()),
                        "OreDig broke or changed the structure block at " + entry.getKey() + ": " + entry.getValue().getBlock()
                                + " -> " + arena.world.getBlockState(entry.getKey()).getBlock() + " at tick " + now);
            }
            if (now >= 800) {
                arena.require(task.state() != TaskState.CANCELLED, "the dig was cancelled");
                // The control: the task either refused the lane (it ended saying so: with nothing else to dig it is trapped) or worked
                // elsewhere (walked off, dug natural stone beside the lane); it never touched the lane.
                boolean refused = task.state() == TaskState.FAILED && task.failureReason().contains("break_refused:structure_block");
                boolean worked = refused || bot.blockPosition().distManhattan(start) >= 2 || naturalStoneDug(arena);
                arena.require(worked, "OreDig did nothing in 800 ticks (state " + task.state() + " " + task.failureReason()
                        + "), so the untouched lane proves nothing");
                arena.finish(bot);
            }
        });
    }

    /** Eight layers of the given blocks, two cells high, straight north of the bot inside a mass of natural stone. */
    private static List<BlockPos> buildLane(BaritoneEngineArena arena, List<Block> lane) {
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -11; dz <= -1; dz++) {
                arena.fill(dx, dz, Blocks.STONE, 0, BaritoneEngineArena.CEILING - 1);
            }
        }
        List<BlockPos> cells = new ArrayList<>();
        for (int i = 0; i < lane.size(); i++) {
            for (int dy = 0; dy <= 1; dy++) {
                arena.set(0, dy, -1 - i, lane.get(i));
                cells.add(arena.cell(0, dy, -1 - i));
            }
        }
        return cells;
    }

    /** How many layers of the lane (both cells) are open now. */
    private static int clearedLayers(BaritoneEngineArena arena, List<BlockPos> cells) {
        int layers = 0;
        for (int i = 0; i + 1 < cells.size(); i += 2) {
            if (arena.world.getBlockState(cells.get(i)).isAir() && arena.world.getBlockState(cells.get(i + 1)).isAir()) {
                layers++;
            }
        }
        return layers;
    }

    private static void equip(AIPlayerEntity bot) {
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 32));
    }

    /** Whether any cell of the natural stone mass beside the lane (not the lane itself) has been opened. */
    private static boolean naturalStoneDug(BaritoneEngineArena arena) {
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -11; dz <= -1; dz++) {
                if (dx == 0) {
                    continue;
                }
                for (int dy = 0; dy <= 1; dy++) {
                    if (arena.world.getBlockState(arena.cell(dx, dy, dz)).isAir()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The vanilla destroy delay of legacy breaks
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Four stone blocks broken back to back through the legacy action pack: every break after the first starts only after the
     * five-tick delay that follows a multi-tick break (the gap between two completions is the break time plus five ticks). Two
     * blades of short grass (destroyed in the first tick) are broken with no delay in between.
     */
    @GameTest(environment = "minecraftai-gametest:ore_dig_structure_break_game_tests_consecutive_legacy_breaks_wait_five_ticks_and_instant_breaks_do_not", maxTicks = 300)
    public void consecutiveLegacyBreaksWaitFiveTicksAndInstantBreaksDoNot(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 6, 8, 6);
        AIPlayerEntity bot = arena.spawn("DestroyDelayGT", arena.cell(0, 0, 0));
        bot.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        bot.getInventory().setSelectedSlot(0);
        List<BlockPos> stones = List.of(arena.cell(0, 0, -2), arena.cell(0, 1, -2), arena.cell(1, 0, -2), arena.cell(1, 1, -2));
        for (BlockPos pos : stones) {
            arena.world.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        List<BlockPos> grass = List.of(arena.cell(-2, 0, -2), arena.cell(-3, 0, -2));
        for (BlockPos pos : grass) {
            arena.world.setBlock(pos.below(), Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
            arena.world.setBlock(pos, Blocks.SHORT_GRASS.defaultBlockState(), Block.UPDATE_ALL);
        }
        // How many ticks of work one stone takes with this pickaxe (progress accumulates per tick until it reaches 1).
        float perTick = Blocks.STONE.defaultBlockState().getDestroyProgress(bot, arena.world, stones.get(0));
        int work = 0;
        for (float progress = 0.0F; progress < 1.0F; progress += perTick) {
            work++;
        }
        arena.require(work >= 3, "the stone is not a multi-tick break with this tool: " + work + " ticks");
        int workTicks = work;
        ActionPack pack = bot.getActionPack();
        List<BlockPos> queue = new ArrayList<>(stones);
        queue.addAll(grass);
        List<Long> done = new ArrayList<>();
        int[] next = {0};
        boolean[] running = {false};
        int[] tick = {0};
        context.failIfEver(() -> {
            arena.require(++tick[0] < 280, "the breaks did not finish: done=" + done + " next=" + next[0]);
            long now = arena.world.getGameTime();
            if (running[0] && pack.isMiningIdle()) {
                arena.require(arena.world.getBlockState(queue.get(next[0])).isAir(), "the break of " + queue.get(next[0]) + " ended without breaking it");
                done.add(now);
                next[0]++;
                running[0] = false;
            }
            if (next[0] >= queue.size()) {
                // done: [s1, s2, s3, s4, g1, g2]. Stone to stone gaps carry the delay; the grass pair does not.
                for (int i = 1; i < stones.size(); i++) {
                    long gap = done.get(i) - done.get(i - 1);
                    arena.require(gap >= workTicks + 5 && gap <= workTicks + 7,
                            "stone " + (i + 1) + " came " + gap + " ticks after the previous one; " + workTicks + " ticks of work plus the 5-tick delay expected");
                }
                long grassGap = done.get(5) - done.get(4);
                arena.require(grassGap <= 2, "two instant breaks were " + grassGap + " ticks apart: an instant break must not set the delay");
                arena.finish(bot);
                return;
            }
            if (!running[0]) {
                BlockPos pos = queue.get(next[0]);
                pack.startMining(pos, Direction.SOUTH);
                running[0] = true;
            }
        });
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(net.minecraft.network.chat.Component.nullToEmpty(message));
            throw new IllegalStateException(message);
        }
    }
}

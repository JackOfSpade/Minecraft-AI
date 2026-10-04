package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
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
        expected.put(Blocks.ICE, "ice_releases_water");
        expected.put(Blocks.FROSTED_ICE, "ice_releases_water");
        // Natural terrain: allowed (null).
        List<Block> natural = List.of(Blocks.STONE, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE, Blocks.TUFF, Blocks.DEEPSLATE,
                Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.SAND, Blocks.RED_SAND,
                Blocks.GRAVEL, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.CLAY, Blocks.MUD, Blocks.TERRACOTTA, Blocks.ORANGE_TERRACOTTA,
                Blocks.BROWN_TERRACOTTA, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.SNOW_BLOCK, Blocks.DRIPSTONE_BLOCK,
                Blocks.CALCITE, Blocks.SMOOTH_BASALT, Blocks.BASALT, Blocks.BLACKSTONE, Blocks.NETHERRACK, Blocks.SOUL_SAND, Blocks.SOUL_SOIL,
                Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM, Blocks.END_STONE, Blocks.AMETHYST_BLOCK, Blocks.SCULK, Blocks.OAK_LEAVES,
                Blocks.SHORT_GRASS, Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.NETHER_GOLD_ORE,
                Blocks.ANCIENT_DEBRIS, Blocks.GLOWSTONE, Blocks.RAW_IRON_BLOCK, Blocks.RAW_COPPER_BLOCK, Blocks.DEEPSLATE_IRON_ORE,
                Blocks.DEEPSLATE_COPPER_ORE);
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
        BlockPos target = arena.cell(0, 1, -1);
        List<Block> structure = List.of(Blocks.STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS,
                Blocks.STONE_SLAB, Blocks.OAK_PLANKS, Blocks.INFESTED_STONE, Blocks.INFESTED_STONE_BRICKS, Blocks.BRICKS, Blocks.GLASS,
                Blocks.WHITE_WOOL, Blocks.OAK_STAIRS, Blocks.CHEST, Blocks.COBWEB, Blocks.ICE, Blocks.OBSIDIAN);
        BlockMiner refuser = new BlockMiner();
        for (Block block : structure) {
            arena.world.setBlock(target, block.defaultBlockState(), Block.UPDATE_ALL);
            require(context, !NeighborEnumerator.isMineable(arena.world, target), "the path search may dig through " + block);
            refuser.naturalTerrainOnly(true);
            refuser.begin(bot, target, false);
            BlockMiner.Status status = refuser.tick(bot);
            require(context, status == BlockMiner.Status.FAILED
                            && (refuser.failureReason().startsWith("break_refused:")
                            || "target_not_observed".equals(refuser.failureReason())),
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
                Blocks.SOUL_SAND, Blocks.BASALT, Blocks.SMOOTH_BASALT, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.CALCITE, Blocks.COBBLESTONE, Blocks.NETHERRACK,
                Blocks.OAK_LEAVES, Blocks.RAW_IRON_BLOCK, Blocks.RAW_COPPER_BLOCK);
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
                miner.naturalTerrainOnly(true);
                miner.begin(bot, target, false);
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
    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(net.minecraft.network.chat.Component.nullToEmpty(message));
            throw new IllegalStateException(message);
        }
    }
}

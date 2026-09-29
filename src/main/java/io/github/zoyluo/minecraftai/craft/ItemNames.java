package io.github.zoyluo.minecraftai.craft;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Item / Block → display-name lookup table. Used by the goal chain ({@code GoalStep.describe} /
 * {@code GoalExecutor} goal titles) to resolve human-readable names **server-side**, so the panel
 * and logs can show them directly without depending on client language files (in practice,
 * client-side localization does not reliably take effect).
 * Items not covered here fall back to the path portion of the English id (e.g. {@code oak_log}),
 * which is more concise than the full {@code minecraft:oak_log}.
 */
public final class ItemNames {
    private static final Map<Item, String> ITEMS = new HashMap<>();
    private static final Map<Block, String> BLOCKS = new HashMap<>();

    private ItemNames() {
    }

    public static String cn(Item item) {
        if (item == null) {
            return "?";
        }
        String name = ITEMS.get(item);
        return name != null ? name : BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    public static String cn(Block block) {
        if (block == null) {
            return "?";
        }
        String name = BLOCKS.get(block);
        return name != null ? name : BuiltInRegistries.BLOCK.getKey(block).getPath();
    }

    private static void i(Item item, String cn) {
        ITEMS.put(item, cn);
    }

    private static void b(Block block, String cn) {
        BLOCKS.put(block, cn);
    }

    static {
        // ── Wood / Basics ──
        i(Items.OAK_LOG, "Oak Log");
        i(Items.BIRCH_LOG, "Birch Log");
        i(Items.SPRUCE_LOG, "Spruce Log");
        i(Items.JUNGLE_LOG, "Jungle Log");
        i(Items.ACACIA_LOG, "Acacia Log");
        i(Items.DARK_OAK_LOG, "Dark Oak Log");
        i(Items.MANGROVE_LOG, "Mangrove Log");
        i(Items.CHERRY_LOG, "Cherry Log");
        i(Items.OAK_PLANKS, "Oak Planks");
        i(Items.BIRCH_PLANKS, "Birch Planks");
        i(Items.SPRUCE_PLANKS, "Spruce Planks");
        i(Items.JUNGLE_PLANKS, "Jungle Planks");
        i(Items.ACACIA_PLANKS, "Acacia Planks");
        i(Items.DARK_OAK_PLANKS, "Dark Oak Planks");
        i(Items.MANGROVE_PLANKS, "Mangrove Planks");
        i(Items.CHERRY_PLANKS, "Cherry Planks");
        i(Items.STICK, "Stick");
        i(Items.CRAFTING_TABLE, "Crafting Table");
        i(Items.FURNACE, "Furnace");
        i(Items.CHEST, "Chest");
        i(Items.TORCH, "Torch");
        i(Items.DIRT, "Dirt");

        // ── Tools / Weapons / Armor ──
        i(Items.WOODEN_PICKAXE, "Wooden Pickaxe");
        i(Items.STONE_PICKAXE, "Stone Pickaxe");
        i(Items.IRON_PICKAXE, "Iron Pickaxe");
        i(Items.GOLDEN_PICKAXE, "Golden Pickaxe");
        i(Items.DIAMOND_PICKAXE, "Diamond Pickaxe");
        i(Items.NETHERITE_PICKAXE, "Netherite Pickaxe");
        i(Items.WOODEN_SWORD, "Wooden Sword");
        i(Items.STONE_SWORD, "Stone Sword");
        i(Items.IRON_SWORD, "Iron Sword");
        i(Items.DIAMOND_SWORD, "Diamond Sword");
        i(Items.WOODEN_AXE, "Wooden Axe");
        i(Items.STONE_AXE, "Stone Axe");
        i(Items.IRON_AXE, "Iron Axe");
        i(Items.WOODEN_SHOVEL, "Wooden Shovel");
        i(Items.STONE_SHOVEL, "Stone Shovel");
        i(Items.IRON_SHOVEL, "Iron Shovel");
        i(Items.WOODEN_HOE, "Wooden Hoe");
        i(Items.STONE_HOE, "Stone Hoe");
        i(Items.IRON_HOE, "Iron Hoe");
        i(Items.SHIELD, "Shield");
        i(Items.IRON_HELMET, "Iron Helmet");
        i(Items.IRON_CHESTPLATE, "Iron Chestplate");
        i(Items.IRON_LEGGINGS, "Iron Leggings");
        i(Items.IRON_BOOTS, "Iron Boots");

        // ── Stone / Ores / Ingots ──
        i(Items.STONE, "Stone");
        i(Items.COBBLESTONE, "Cobblestone");
        i(Items.COBBLED_DEEPSLATE, "Cobbled Deepslate");
        i(Items.BLACKSTONE, "Blackstone");
        i(Items.COAL, "Coal");
        i(Items.CHARCOAL, "Charcoal");
        i(Items.RAW_IRON, "Raw Iron");
        i(Items.IRON_INGOT, "Iron Ingot");
        i(Items.RAW_GOLD, "Raw Gold");
        i(Items.GOLD_INGOT, "Gold Ingot");
        i(Items.RAW_COPPER, "Raw Copper");
        i(Items.COPPER_INGOT, "Copper Ingot");
        i(Items.DIAMOND, "Diamond");
        i(Items.REDSTONE, "Redstone");
        i(Items.LAPIS_LAZULI, "Lapis Lazuli");

        // ── Food ──
        i(Items.SWEET_BERRIES, "Sweet Berries");
        i(Items.GLOW_BERRIES, "Glow Berries");
        i(Items.MELON_SLICE, "Melon Slice");
        i(Items.APPLE, "Apple");
        i(Items.WHEAT, "Wheat");
        i(Items.WHEAT_SEEDS, "Wheat Seeds");
        i(Items.BREAD, "Bread");
        i(Items.HAY_BLOCK, "Hay Bale");
        i(Items.CARROT, "Carrot");
        i(Items.POTATO, "Potato");
        i(Items.BAKED_POTATO, "Baked Potato");
        i(Items.BEETROOT, "Beetroot");
        i(Items.BEEF, "Raw Beef");
        i(Items.COOKED_BEEF, "Steak");
        i(Items.PORKCHOP, "Raw Porkchop");
        i(Items.COOKED_PORKCHOP, "Cooked Porkchop");
        i(Items.MUTTON, "Raw Mutton");
        i(Items.COOKED_MUTTON, "Cooked Mutton");
        i(Items.CHICKEN, "Raw Chicken");
        i(Items.COOKED_CHICKEN, "Cooked Chicken");
        i(Items.RABBIT, "Raw Rabbit");
        i(Items.COOKED_RABBIT, "Cooked Rabbit");
        i(Items.COD, "Raw Cod");
        i(Items.COOKED_COD, "Cooked Cod");
        i(Items.SALMON, "Raw Salmon");
        i(Items.COOKED_SALMON, "Cooked Salmon");

        // ── Blocks (used by MINE / FARM steps) ──
        b(Blocks.STONE, "Stone");
        b(Blocks.DEEPSLATE, "Deepslate");
        b(Blocks.COAL_ORE, "Coal Ore");
        b(Blocks.DEEPSLATE_COAL_ORE, "Deepslate Coal Ore");
        b(Blocks.IRON_ORE, "Iron Ore");
        b(Blocks.DEEPSLATE_IRON_ORE, "Deepslate Iron Ore");
        b(Blocks.COPPER_ORE, "Copper Ore");
        b(Blocks.DEEPSLATE_COPPER_ORE, "Deepslate Copper Ore");
        b(Blocks.GOLD_ORE, "Gold Ore");
        b(Blocks.DEEPSLATE_GOLD_ORE, "Deepslate Gold Ore");
        b(Blocks.DIAMOND_ORE, "Diamond Ore");
        b(Blocks.DEEPSLATE_DIAMOND_ORE, "Deepslate Diamond Ore");
        b(Blocks.REDSTONE_ORE, "Redstone Ore");
        b(Blocks.DEEPSLATE_REDSTONE_ORE, "Deepslate Redstone Ore");
        b(Blocks.WHEAT, "Wheat");
        b(Blocks.CARROTS, "Carrots");
        b(Blocks.POTATOES, "Potatoes");
        b(Blocks.SWEET_BERRY_BUSH, "Sweet Berry Bush");
        b(Blocks.MELON, "Melon");
    }
}

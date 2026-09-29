package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.task.WorkshopLocator;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;

import java.util.Locale;
import java.util.Set;

/**
 * Optimal-tool-CATEGORY policy for player-requested gather/harvest/break actions (used by
 * GatherQuotaTask, which backs the {@code gather}/{@code break_blocks}/{@code clear_grass} LLM
 * tools). The category is derived from vanilla's own block tags -- never a hand-written per-block
 * table -- so it never picks a wrong-category tool (a shovel for cobblestone) and never silently
 * falls back to the bare hand when an effective tool category actually exists.
 *
 * <p>Mining missions (OreDig/DigDown/MiningService, obsidian creation) and incidental blocks broken
 * while pathing keep their own existing tool policy ({@link ToolSelector#equipMiningChannelTool}
 * for the pickaxe-tier channel); this class is deliberately not used there.
 */
public final class GatherToolPolicy {
    private GatherToolPolicy() {
    }

    public enum Category {
        /** No effective tool exists for this block (instant-break plants, flowers, ...): the hand is optimal. */
        NONE,
        PICKAXE,
        AXE,
        SHOVEL,
        HOE,
        SHEARS,
        SWORD
    }

    // Cobweb and vines have no vanilla "mineable" block tag (unlike wool/leaves, which are tagged);
    // shears are still their vanilla-intended optimal tool (guaranteed string / vine drop instead of
    // nothing), so they are special-cased here to match vanilla's own hardcoded tool behavior.
    private static final Set<Block> SHEARS_SPECIAL = Set.of(Blocks.COBWEB, Blocks.VINE);

    /** Derives the optimal tool category for {@code state} from vanilla's own block tags. */
    public static Category categoryFor(BlockState state) {
        Block block = state.getBlock();
        if (SHEARS_SPECIAL.contains(block) || state.isIn(BlockTags.WOOL) || state.isIn(BlockTags.LEAVES)) {
            return Category.SHEARS;
        }
        if (state.isIn(BlockTags.PICKAXE_MINEABLE)) {
            return Category.PICKAXE;
        }
        if (state.isIn(BlockTags.SHOVEL_MINEABLE)) {
            return Category.SHOVEL;
        }
        if (state.isIn(BlockTags.AXE_MINEABLE)) {
            return Category.AXE;
        }
        if (state.isIn(BlockTags.HOE_MINEABLE)) {
            return Category.HOE;
        }
        if (state.isIn(BlockTags.SWORD_EFFICIENT)) {
            return Category.SWORD;
        }
        return Category.NONE;
    }

    /** True when {@code stack} belongs to {@code category} (any tier counts -- tier selection is
     * {@link ToolSelector}'s job once a category match is known to exist). */
    public static boolean matches(ItemStack stack, Category category) {
        if (stack.isEmpty()) {
            return category == Category.NONE;
        }
        return switch (category) {
            case NONE -> true;
            case PICKAXE -> stack.isIn(ItemTags.PICKAXES);
            case AXE -> stack.isIn(ItemTags.AXES);
            case SHOVEL -> stack.isIn(ItemTags.SHOVELS);
            case HOE -> stack.isIn(ItemTags.HOES);
            case SHEARS -> stack.getItem() == Items.SHEARS;
            case SWORD -> stack.isIn(ItemTags.SWORDS);
        };
    }

    /** True when the bot already carries any item of {@code category} (main inventory slots or offhand). */
    public static boolean hasTool(AIPlayerEntity bot, Category category) {
        if (category == Category.NONE) {
            return true;
        }
        PlayerInventory inventory = bot.getInventory();
        for (ItemStack stack : inventory.getMainStacks()) {
            if (matches(stack, category)) {
                return true;
            }
        }
        return matches(bot.getEquippedStack(EquipmentSlot.OFFHAND), category);
    }

    /**
     * Cheapest-first craft candidates for a missing tool of {@code category}: wood tier, then stone
     * (shears/sword have only the one relevant tier here). Empty for {@link Category#NONE}, which
     * never needs crafting.
     */
    public static Item[] craftCandidates(Category category) {
        return switch (category) {
            case PICKAXE -> new Item[]{Items.WOODEN_PICKAXE, Items.STONE_PICKAXE};
            case AXE -> new Item[]{Items.WOODEN_AXE, Items.STONE_AXE};
            case SHOVEL -> new Item[]{Items.WOODEN_SHOVEL, Items.STONE_SHOVEL};
            case HOE -> new Item[]{Items.WOODEN_HOE, Items.STONE_HOE};
            case SWORD -> new Item[]{Items.WOODEN_SWORD, Items.STONE_SWORD};
            case SHEARS -> new Item[]{Items.SHEARS};
            case NONE -> new Item[0];
        };
    }

    // ---------------------------------------------------------------------------------------
    // Log bootstrap exception (the ONE place the strict "never break with the wrong/bare tool"
    // rule is relaxed). Logs are axe-optimal, but the axe's own materials (planks, sticks, and a
    // crafting table for the 3x3 axe recipe) come from logs: a bot with an empty inventory would
    // otherwise stop with missing_tool:axe and could never obtain wood at all, breaking every
    // fresh bot and the goal planner's from-nothing chains. So, and only when (a) the requested
    // block is a log/stem, (b) the bot has no axe, and (c) it cannot craft one from what it
    // carries, it may break by hand exactly the MINIMUM number of logs needed to craft a wooden
    // axe (plus a crafting table unless one is carried or placed within reach); the axe is then
    // crafted through the normal ENSURE_TOOL path and the rest of the request uses it. Every other
    // block keeps the strict rule (dirt without a shovel or materials -> missing_tool stop).
    // ---------------------------------------------------------------------------------------

    /** True when {@code state} is a log/stem, i.e. a block that can be hand-bootstrapped. */
    public static boolean isLogBootstrapTarget(BlockState state) {
        return state.isIn(BlockTags.LOGS);
    }

    /**
     * Logs still to be broken BY HAND so that a wooden axe becomes craftable from the inventory
     * (0 when it already is, or when a stone axe is craftable instead). Reads the bot's inventory.
     */
    public static int bootstrapLogsByHand(AIPlayerEntity bot) {
        int logs = 0;
        int planks = 0;
        int sticks = 0;
        int stoneMaterial = 0;
        boolean tableCarried = false;
        PlayerInventory inventory = bot.getInventory();
        for (ItemStack stack : inventory.getMainStacks()) {
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.isIn(ItemTags.LOGS)) {
                logs += stack.getCount();
            } else if (stack.isIn(ItemTags.PLANKS)) {
                planks += stack.getCount();
            } else if (stack.isOf(Items.STICK)) {
                sticks += stack.getCount();
            } else if (stack.isOf(Items.CRAFTING_TABLE)) {
                tableCarried = true;
            } else if (stack.isIn(ItemTags.STONE_TOOL_MATERIALS)) {
                stoneMaterial += stack.getCount();
            }
        }
        boolean tableAvailable = tableCarried || WorkshopLocator.hasNearbyCraftingTable(bot);
        return Bootstrap.logsByHand(logs, planks, sticks, stoneMaterial, tableAvailable);
    }

    /** Pure arithmetic of the bootstrap rule (unit-testable without the game bootstrapped). */
    public static final class Bootstrap {
        public static final int AXE_PLANKS = 3;
        public static final int AXE_STICKS = 2;
        public static final int TABLE_PLANKS = 4;
        /** Planks per log, and planks spent to make one batch (4) of sticks. */
        public static final int PLANKS_PER_LOG = 4;
        public static final int STICK_BATCH_PLANKS = 2;

        private Bootstrap() {
        }

        /** Total logs the axe (+ table when {@code tableAvailable} is false) needs, ignoring the inventory. */
        public static int totalLogsNeeded(int planks, int sticks, boolean tableAvailable) {
            int planksNeeded = AXE_PLANKS
                    + (tableAvailable ? 0 : TABLE_PLANKS)
                    + (sticks >= AXE_STICKS ? 0 : STICK_BATCH_PLANKS);
            int missingPlanks = Math.max(0, planksNeeded - Math.max(0, planks));
            return (missingPlanks + PLANKS_PER_LOG - 1) / PLANKS_PER_LOG;
        }

        /**
         * Logs that must still be broken by hand: the total logs needed minus logs already
         * carried; 0 when a stone axe (3 stone-tool material + 2 sticks at an available table) can
         * be crafted instead.
         */
        public static int logsByHand(int logs, int planks, int sticks, int stoneMaterial, boolean tableAvailable) {
            if (stoneMaterial >= 3 && sticks >= AXE_STICKS && tableAvailable) {
                return 0;
            }
            return Math.max(0, totalLogsNeeded(planks, sticks, tableAvailable) - Math.max(0, logs));
        }
    }

    /** Lower-case token for failure reasons/logging, e.g. {@code missing_tool:shovel}. */
    public static String token(Category category) {
        return category.name().toLowerCase(Locale.ROOT);
    }
}

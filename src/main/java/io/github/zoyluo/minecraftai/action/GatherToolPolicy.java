package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
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

    /** Lower-case token for failure reasons/logging, e.g. {@code missing_tool:shovel}. */
    public static String token(Category category) {
        return category.name().toLowerCase(Locale.ROOT);
    }
}

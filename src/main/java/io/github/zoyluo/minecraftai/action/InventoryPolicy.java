package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The keep / junk split behind the bot's storage housekeeping. KEEP is everything that is not
 * junk: tools, armor, weapons, food, ores and ingots, anything enchanted or renamed, and anything a
 * goal is collecting. JUNK is a fixed list of cheap filler blocks, and only the part beyond a small
 * configurable throwaway budget (kept for building and pillaring) counts as surplus. Stone-like
 * blocks share one pooled budget; the other junk kinds keep the budget each.
 */
public final class InventoryPolicy {
    private static final List<Item> STONE_LIKE = List.of(
            Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.BLACKSTONE);
    private static final List<Item> OTHER_JUNK = List.of(
            Items.DIRT, Items.COARSE_DIRT, Items.GRAVEL, Items.SAND, Items.NETHERRACK,
            Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF);
    private static final Set<Item> JUNK = new java.util.HashSet<>();

    static {
        JUNK.addAll(STONE_LIKE);
        JUNK.addAll(OTHER_JUNK);
    }

    private InventoryPolicy() {
    }

    /** One pending junk move: {@code count} of {@code item} may go into storage. */
    public record Stow(Item item, int count) {
    }

    public static boolean isJunkKind(Item item) {
        return JUNK.contains(item);
    }

    /** True for a stack that must never be stowed as junk even if its item kind is on the list. */
    public static boolean isProtected(ItemStack stack) {
        return stack.isEmpty()
                || stack.isDamageableItem()
                || stack.has(DataComponents.FOOD)
                || stack.isEnchanted()
                || stack.has(DataComponents.CUSTOM_NAME);
    }

    public static int freeMainSlots(AIPlayerEntity bot) {
        int free = 0;
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) {
                free++;
            }
        }
        return free;
    }

    public static boolean nearlyFull(AIPlayerEntity bot) {
        return freeMainSlots(bot) <= MinecraftAiConfig.get().storage().nearlyFullFreeSlots();
    }

    /** Junk to move, most plentiful surplus first; empty when nothing exceeds its budget. */
    public static List<Stow> surplus(AIPlayerEntity bot) {
        MinecraftAiConfig.Storage config = MinecraftAiConfig.get().storage();
        List<Stow> result = new ArrayList<>();
        int stoneTotal = 0;
        for (Item item : STONE_LIKE) {
            stoneTotal += InventoryAction.countItem(bot, item);
        }
        int stoneSurplus = Math.max(0, stoneTotal - config.junkKeepStone());
        List<Item> stoneByCount = new ArrayList<>(STONE_LIKE);
        stoneByCount.sort(Comparator.comparingInt((Item item) -> -InventoryAction.countItem(bot, item)));
        for (Item item : stoneByCount) {
            int take = Math.min(stoneSurplus, InventoryAction.countItem(bot, item));
            if (take > 0) {
                result.add(new Stow(item, take));
                stoneSurplus -= take;
            }
        }
        for (Item item : OTHER_JUNK) {
            int extra = InventoryAction.countItem(bot, item) - config.junkKeepOther();
            if (extra > 0) {
                result.add(new Stow(item, extra));
            }
        }
        result.sort(Comparator.comparingInt((Stow stow) -> -stow.count()));
        return result;
    }

    /** The next junk move that is actually possible (a real, unprotected stack exists), or empty. */
    public static Optional<Stow> nextStow(AIPlayerEntity bot) {
        for (Stow stow : surplus(bot)) {
            for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
                if (!stack.isEmpty() && stack.is(stow.item()) && !isProtected(stack)) {
                    return Optional.of(stow);
                }
            }
        }
        return Optional.empty();
    }
}

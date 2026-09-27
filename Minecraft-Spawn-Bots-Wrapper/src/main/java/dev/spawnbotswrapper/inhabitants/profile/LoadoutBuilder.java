package dev.spawnbotswrapper.inhabitants.profile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lays rolled stacks out into worn slots, offhand, hotbar and main inventory, and guarantees the result
 * fits a player inventory.
 * <p>
 * Capacity is the one thing that cannot be left to the individual facets: potions do not stack, so a
 * bot carrying several potion kinds needs a slot per bottle. Main-inventory stacks are therefore ranked by
 * how much PvP BOT needs them. Should the 27 main slots ever overflow, the least needed stacks first move
 * into the hotbar slots nothing else uses (PvP BOT looks for items in all 36 slots, so an item there is
 * just as usable), and only when the hotbar is full too are the least needed stacks dropped (buff potions
 * first, ammunition and totems last). The facet ranges are chosen so dropping essentially never happens;
 * {@link #trimmed()} reports it so a test can hold the generator to that.
 * <p>
 * Main-inventory stacks are placed with {@code index -1} ("first free slot"), never a fixed index, so the
 * applier decides the exact position. Hotbar placement is explicit because PvP BOT only selects weapons
 * that sit in hotbar slots 0-8.
 */
final class LoadoutBuilder {
    static final int HOTBAR_SIZE = 9;
    static final int MAIN_SIZE = 27;

    /** Trim priorities: the lower the number, the later a stack is dropped (or spilled) when the inventory is full. */
    static final int AMMO = 1;
    static final int TOTEM = 2;
    static final int SUSTAIN = 3;
    static final int UTILITY = 4;
    static final int LUXURY = 6;
    static final int BUFF = 7;

    /**
     * Order in which spare hotbar slots take overflow: the middle of the bar first, then slot 1 (PvP BOT's
     * shield swap slot, only in play when a shield is carried), then slot 8 (its food/potion scratch slot),
     * and slot 0 (the weapon slot) last of all.
     */
    private static final int[] SPILL_ORDER = {2, 3, 4, 5, 6, 7, 1, 8, 0};

    private record Stack(BotProfile.ItemSpec spec, int priority, int order) {
    }

    private final Map<String, BotProfile.ItemSpec> worn = new LinkedHashMap<>();
    private BotProfile.ItemSpec offhand;
    private final BotProfile.ItemSpec[] hotbar = new BotProfile.ItemSpec[HOTBAR_SIZE];
    private final List<Stack> main = new ArrayList<>();
    private int trimmed;

    void wear(String slot, BotProfile.ItemSpec spec) {
        worn.put(slot, spec);
    }

    BotProfile.ItemSpec worn(String slot) {
        return worn.get(slot);
    }

    void offhand(BotProfile.ItemSpec spec) {
        this.offhand = spec;
    }

    void hotbar(int index, BotProfile.ItemSpec spec) {
        if (index < 0 || index >= HOTBAR_SIZE) {
            throw new IllegalArgumentException("hotbar index out of range: " + index);
        }
        hotbar[index] = spec;
    }

    /** One main-inventory stack; {@code spec.count()} must already respect the item's max stack size. */
    void stock(BotProfile.ItemSpec spec, int priority) {
        main.add(new Stack(spec, priority, main.size()));
    }

    /**
     * {@code total} of an item split into stacks of at most its vanilla max stack size. Enchantments,
     * wear and potion of the template are copied onto every stack.
     */
    void stockSplit(BotProfile.ItemSpec template, int total, int priority) {
        int max = ItemIds.maxStack(template.item());
        int left = total;
        while (left > 0) {
            int n = Math.min(max, left);
            stock(new BotProfile.ItemSpec(template.item(), n, template.enchantments(),
                    template.damageFraction(), template.potion()), priority);
            left -= n;
        }
    }

    /** Stacks dropped because neither the main inventory nor the hotbar had room. */
    int trimmed() {
        return trimmed;
    }

    BotProfile.Loadout build() {
        fit();
        List<BotProfile.PlacedItem> out = new ArrayList<>();
        for (ItemIds.ArmorSlot s : ItemIds.ArmorSlot.values()) {
            BotProfile.ItemSpec spec = worn.get(s.slot);
            if (spec != null) {
                out.add(new BotProfile.PlacedItem(s.slot, 0, spec));
            }
        }
        if (offhand != null) {
            out.add(new BotProfile.PlacedItem(BotProfile.Slot.OFFHAND, 0, offhand));
        }
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (hotbar[i] != null) {
                out.add(new BotProfile.PlacedItem(BotProfile.Slot.HOTBAR, i, hotbar[i]));
            }
        }
        for (Stack s : main) {
            out.add(new BotProfile.PlacedItem(BotProfile.Slot.INVENTORY, -1, s.spec()));
        }
        return new BotProfile.Loadout(out);
    }

    /** Moves the most droppable overflow into spare hotbar slots and drops what still does not fit. */
    private void fit() {
        if (main.size() <= MAIN_SIZE) {
            return;
        }
        List<Stack> mostDroppableFirst = new ArrayList<>(main);
        mostDroppableFirst.sort(Comparator.comparingInt(Stack::priority).reversed()
                .thenComparing(Comparator.comparingInt(Stack::order).reversed()));
        List<Stack> overflow = new ArrayList<>(mostDroppableFirst.subList(0, main.size() - MAIN_SIZE));
        main.removeAll(overflow);

        // Of the overflow the least droppable stacks get the spare slots; the rest are lost.
        int next = overflow.size() - 1;
        for (int slot : SPILL_ORDER) {
            if (next < 0) {
                break;
            }
            if (hotbar[slot] == null) {
                hotbar[slot] = overflow.get(next--).spec();
            }
        }
        trimmed += next + 1;
    }
}

package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.profile.BotProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides which slot of a player inventory every item of a loadout goes into, before anything is written.
 * Pure: slots are the vanilla player-inventory indices, so the plan can be tested without Minecraft (a test
 * against the real game checks the constants below).
 * <pre>
 *   0..8    hotbar                     36  feet     39  head
 *   9..35   main inventory             37  legs     40  offhand
 *                                      38  chest
 * </pre>
 * Rules:
 * <ul>
 *   <li>An item asking for a valid explicit slot gets it; a second item asking for a taken slot does not
 *       overwrite the first, it falls back to the first free slot, with a warning.</li>
 *   <li>Items with no usable slot (unknown slot name, hotbar index out of range, {@code inventory} with
 *       index -1) take the first free slot: main inventory first, then the hotbar. With none left the item
 *       is dropped with a warning.</li>
 *   <li>Explicit requests are settled before any free-slot search, so an item without a slot never takes
 *       a slot another item asked for by name, wherever it appears in the list.</li>
 * </ul>
 */
public final class SlotPlanner {
    public static final int HOTBAR_FIRST = 0;
    public static final int HOTBAR_LAST = 8;
    public static final int MAIN_FIRST = 9;
    public static final int MAIN_LAST = 35;
    public static final int FEET = 36;
    public static final int LEGS = 37;
    public static final int CHEST = 38;
    public static final int HEAD = 39;
    public static final int OFFHAND = 40;
    /** Number of slots addressable by a plan (hotbar, main inventory, four armor slots, offhand). */
    public static final int SLOT_COUNT = 41;

    private SlotPlanner() {
    }

    /** One item and the inventory index it will be written to. */
    public record Placement(int slot, BotProfile.ItemSpec spec) {
    }

    /** The placements, ordered by slot, and everything that did not go as requested. */
    public record Plan(List<Placement> placements, List<String> warnings) {
        public Plan {
            placements = List.copyOf(placements);
            warnings = List.copyOf(warnings);
        }
    }

    public static Plan plan(List<BotProfile.PlacedItem> items) {
        List<String> warnings = new ArrayList<>();
        BotProfile.ItemSpec[] slots = new BotProfile.ItemSpec[SLOT_COUNT];
        List<BotProfile.ItemSpec> needFreeSlot = new ArrayList<>();

        for (BotProfile.PlacedItem placed : items) {
            if (placed == null || placed.spec() == null) {
                warnings.add("loadout entry without an item was ignored");
                continue;
            }
            int wanted = requestedSlot(placed, warnings);
            if (wanted < 0) {
                needFreeSlot.add(placed.spec());
            } else if (slots[wanted] != null) {
                warnings.add("slot " + describe(wanted) + " is already taken; " + placed.spec().item()
                        + " goes to the first free slot instead");
                needFreeSlot.add(placed.spec());
            } else {
                slots[wanted] = placed.spec();
            }
        }

        for (BotProfile.ItemSpec spec : needFreeSlot) {
            int free = firstFree(slots);
            if (free < 0) {
                warnings.add("no free inventory slot left for " + spec.item() + "; it was skipped");
            } else {
                slots[free] = spec;
            }
        }

        List<Placement> placements = new ArrayList<>();
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            if (slots[slot] != null) {
                placements.add(new Placement(slot, slots[slot]));
            }
        }
        return new Plan(placements, warnings);
    }

    /** The slot an item asks for, or -1 when it has to take the first free one (a warning says why if it is a mistake). */
    private static int requestedSlot(BotProfile.PlacedItem placed, List<String> warnings) {
        String name = placed.slot() == null ? "" : placed.slot().trim().toLowerCase(Locale.ROOT);
        switch (name) {
            case BotProfile.Slot.HEAD:
                return HEAD;
            case BotProfile.Slot.CHEST:
                return CHEST;
            case BotProfile.Slot.LEGS:
                return LEGS;
            case BotProfile.Slot.FEET:
                return FEET;
            case BotProfile.Slot.OFFHAND:
                return OFFHAND;
            case BotProfile.Slot.HOTBAR:
                if (placed.index() >= HOTBAR_FIRST && placed.index() <= HOTBAR_LAST) {
                    return placed.index();
                }
                warnings.add("hotbar index " + placed.index() + " is out of range for " + placed.spec().item()
                        + "; it goes to the first free slot instead");
                return -1;
            case BotProfile.Slot.INVENTORY:
                if (placed.index() >= 0 && placed.index() <= MAIN_LAST - MAIN_FIRST) {
                    return MAIN_FIRST + placed.index();
                }
                if (placed.index() != -1) {
                    warnings.add("inventory index " + placed.index() + " is out of range for " + placed.spec().item()
                            + "; it goes to the first free slot instead");
                }
                return -1;
            default:
                warnings.add("unknown slot '" + placed.slot() + "' for " + placed.spec().item()
                        + "; it goes to the first free slot instead");
                return -1;
        }
    }

    private static int firstFree(BotProfile.ItemSpec[] slots) {
        for (int slot = MAIN_FIRST; slot <= MAIN_LAST; slot++) {
            if (slots[slot] == null) {
                return slot;
            }
        }
        for (int slot = HOTBAR_FIRST; slot <= HOTBAR_LAST; slot++) {
            if (slots[slot] == null) {
                return slot;
            }
        }
        return -1;
    }

    /** Human-readable name of an inventory index, for messages. */
    public static String describe(int slot) {
        if (slot >= HOTBAR_FIRST && slot <= HOTBAR_LAST) {
            return "hotbar " + slot;
        }
        if (slot >= MAIN_FIRST && slot <= MAIN_LAST) {
            return "inventory " + (slot - MAIN_FIRST);
        }
        return switch (slot) {
            case FEET -> BotProfile.Slot.FEET;
            case LEGS -> BotProfile.Slot.LEGS;
            case CHEST -> BotProfile.Slot.CHEST;
            case HEAD -> BotProfile.Slot.HEAD;
            case OFFHAND -> BotProfile.Slot.OFFHAND;
            default -> "slot " + slot;
        };
    }
}

package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.profile.BotProfile.ItemSpec;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.PlacedItem;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.Slot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SlotPlannerTest {

    private static PlacedItem item(String slot, int index, String id) {
        return new PlacedItem(slot, index, ItemSpec.of(id));
    }

    private static SlotPlanner.Plan plan(PlacedItem... items) {
        return SlotPlanner.plan(Arrays.asList(items));
    }

    private static String slotOf(SlotPlanner.Plan plan, String id) {
        return plan.placements().stream()
                .filter(p -> p.spec().item().equals(id))
                .map(p -> String.valueOf(p.slot()))
                .collect(Collectors.joining(","));
    }

    @Test
    void namedSlotsMapToTheVanillaInventoryIndices() {
        SlotPlanner.Plan p = plan(
                item(Slot.HEAD, 0, "h"), item(Slot.CHEST, 0, "c"), item(Slot.LEGS, 0, "l"),
                item(Slot.FEET, 0, "f"), item(Slot.OFFHAND, 0, "o"), item(Slot.HOTBAR, 4, "hb"),
                item(Slot.INVENTORY, 0, "i0"), item(Slot.INVENTORY, 26, "i26"));
        assertEquals("39", slotOf(p, "h"));
        assertEquals("38", slotOf(p, "c"));
        assertEquals("37", slotOf(p, "l"));
        assertEquals("36", slotOf(p, "f"));
        assertEquals("40", slotOf(p, "o"));
        assertEquals("4", slotOf(p, "hb"));
        assertEquals("9", slotOf(p, "i0"));
        assertEquals("35", slotOf(p, "i26"));
        assertTrue(p.warnings().isEmpty(), p.warnings().toString());
    }

    @Test
    void slotNamesAreCaseAndWhitespaceTolerant() {
        SlotPlanner.Plan p = plan(item(" HEAD ", 0, "h"), item("Offhand", 0, "o"));
        assertEquals("39", slotOf(p, "h"));
        assertEquals("40", slotOf(p, "o"));
    }

    @Test
    void placementsAreOrderedBySlot() {
        SlotPlanner.Plan p = plan(item(Slot.OFFHAND, 0, "o"), item(Slot.HOTBAR, 8, "b"), item(Slot.HOTBAR, 0, "a"));
        assertEquals(List.of(0, 8, 40), p.placements().stream().map(SlotPlanner.Placement::slot).toList());
    }

    @Test
    void freeSlotItemsFillTheMainInventoryFirstInOrder() {
        SlotPlanner.Plan p = plan(item(Slot.INVENTORY, -1, "a"), item(Slot.INVENTORY, -1, "b"), item(Slot.INVENTORY, -1, "c"));
        assertEquals("9", slotOf(p, "a"));
        assertEquals("10", slotOf(p, "b"));
        assertEquals("11", slotOf(p, "c"));
        assertTrue(p.warnings().isEmpty());
    }

    @Test
    void aTakenSlotIsNeverOverwrittenTheLaterItemMovesToTheFirstFreeSlot() {
        SlotPlanner.Plan p = plan(item(Slot.HOTBAR, 0, "sword"), item(Slot.HOTBAR, 0, "axe"));
        assertEquals("0", slotOf(p, "sword"));
        assertEquals("9", slotOf(p, "axe"));
        assertEquals(1, p.warnings().size());
        assertTrue(p.warnings().get(0).contains("already taken"), p.warnings().get(0));
    }

    @Test
    void anItemWithoutASlotNeverStealsAnExplicitlyRequestedOne() {
        List<PlacedItem> items = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            items.add(item(Slot.INVENTORY, -1, "filler" + i));
        }
        // The main inventory is full, so the next free-slot item has to use the hotbar; it is listed BEFORE the
        // item that explicitly asks for hotbar slot 0 and must still not take it.
        items.add(item(Slot.INVENTORY, -1, "overflow"));
        items.add(item(Slot.HOTBAR, 0, "sword"));
        SlotPlanner.Plan p = SlotPlanner.plan(items);
        assertEquals("0", slotOf(p, "sword"));
        assertEquals("1", slotOf(p, "overflow"));
    }

    @Test
    void badRequestsFallBackToAFreeSlotWithAWarning() {
        SlotPlanner.Plan p = plan(
                item(Slot.HOTBAR, 9, "a"), item(Slot.HOTBAR, -1, "b"),
                item("belt", 0, "c"), item(null, 0, "d"), item(Slot.INVENTORY, 27, "e"));
        assertEquals("9", slotOf(p, "a"));
        assertEquals("10", slotOf(p, "b"));
        assertEquals("11", slotOf(p, "c"));
        assertEquals("12", slotOf(p, "d"));
        assertEquals("13", slotOf(p, "e"), "inventory index 27 is out of range and takes the first free slot");
        assertEquals(5, p.warnings().size(), p.warnings().toString());
        assertTrue(p.warnings().stream().anyMatch(w -> w.contains("belt")));
        assertTrue(p.warnings().stream().anyMatch(w -> w.contains("inventory index 27")));
    }

    @Test
    void nothingIsWrittenToArmorSlotsByTheFreeSlotSearchAndOverflowIsDroppedWithWarnings() {
        List<PlacedItem> items = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            items.add(item(Slot.INVENTORY, -1, "item" + i));
        }
        SlotPlanner.Plan p = SlotPlanner.plan(items);
        assertEquals(36, p.placements().size(), "27 main + 9 hotbar slots");
        assertTrue(p.placements().stream().allMatch(x -> x.slot() >= 0 && x.slot() <= 35));
        assertEquals(4, p.warnings().size());
        assertTrue(p.warnings().get(0).contains("no free inventory slot"), p.warnings().get(0));
    }

    @Test
    void emptyAndNullEntriesAreIgnoredWithoutFailing() {
        List<PlacedItem> items = new ArrayList<>();
        items.add(null);
        items.add(new PlacedItem(Slot.HEAD, 0, null));
        SlotPlanner.Plan p = SlotPlanner.plan(items);
        assertTrue(p.placements().isEmpty());
        assertEquals(2, p.warnings().size());
        assertTrue(SlotPlanner.plan(List.of()).placements().isEmpty());
    }

    @Test
    void describeNamesEverySlot() {
        assertEquals("hotbar 3", SlotPlanner.describe(3));
        assertEquals("inventory 0", SlotPlanner.describe(9));
        assertEquals("head", SlotPlanner.describe(SlotPlanner.HEAD));
        assertEquals("offhand", SlotPlanner.describe(SlotPlanner.OFFHAND));
        assertEquals("slot 99", SlotPlanner.describe(99));
    }
}

package dev.spawnbotswrapper.inhabitants.profile;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.spawnbotswrapper.inhabitants.profile.ProfileTestSupport.NS;
import static org.junit.jupiter.api.Assertions.*;

class LoadoutBuilderTest {

    private static BotProfile.ItemSpec spec(String path) {
        return BotProfile.ItemSpec.of(NS + path);
    }

    private static BotProfile.ItemSpec potion(String potion) {
        return new BotProfile.ItemSpec(NS + "splash_potion", 1, Map.of(), 0.0, NS + potion);
    }

    private static long inSlot(BotProfile.Loadout l, String slot) {
        return l.items().stream().filter(p -> p.slot().equals(slot)).count();
    }

    @Test
    void stacksAreSplitAtTheVanillaMaximumAndKeepTheirTemplate() {
        LoadoutBuilder b = new LoadoutBuilder();
        b.stockSplit(new BotProfile.ItemSpec(NS + "tipped_arrow", 1, Map.of(), 0.0, NS + "poison"), 150, LoadoutBuilder.AMMO);
        b.stockSplit(spec("ender_pearl"), 40, LoadoutBuilder.UTILITY);
        BotProfile.Loadout l = b.build();
        List<Integer> arrows = l.items().stream().filter(p -> p.spec().item().endsWith("tipped_arrow"))
                .map(p -> p.spec().count()).toList();
        assertEquals(List.of(64, 64, 22), arrows);
        assertTrue(l.items().stream().filter(p -> p.spec().item().endsWith("tipped_arrow"))
                .allMatch(p -> (NS + "poison").equals(p.spec().potion())));
        assertEquals(List.of(16, 16, 8), l.items().stream().filter(p -> p.spec().item().endsWith("ender_pearl"))
                .map(p -> p.spec().count()).toList());
        assertEquals(0, b.trimmed());
    }

    @Test
    void nonStackingItemsGetOneStackEach() {
        LoadoutBuilder b = new LoadoutBuilder();
        b.stockSplit(potion("healing"), 3, LoadoutBuilder.SUSTAIN);
        b.stockSplit(spec("totem_of_undying"), 2, LoadoutBuilder.TOTEM);
        BotProfile.Loadout l = b.build();
        assertEquals(5, l.items().size());
        assertTrue(l.items().stream().allMatch(p -> p.spec().count() == 1));
    }

    @Test
    void zeroOrNegativeTotalsPlaceNothing() {
        LoadoutBuilder b = new LoadoutBuilder();
        b.stockSplit(spec("arrow"), 0, LoadoutBuilder.AMMO);
        b.stockSplit(spec("arrow"), -5, LoadoutBuilder.AMMO);
        assertTrue(b.build().items().isEmpty());
    }

    @Test
    void buildOrdersWornThenOffhandThenHotbarThenInventory() {
        LoadoutBuilder b = new LoadoutBuilder();
        b.stock(spec("cobweb"), LoadoutBuilder.UTILITY);
        b.hotbar(3, spec("bow"));
        b.hotbar(0, spec("iron_sword"));
        b.offhand(spec("shield"));
        b.wear(BotProfile.Slot.FEET, spec("iron_boots"));
        b.wear(BotProfile.Slot.HEAD, spec("iron_helmet"));
        List<String> order = b.build().items().stream().map(p -> p.slot() + ":" + p.index()).toList();
        assertEquals(List.of("head:0", "feet:0", "offhand:0", "hotbar:0", "hotbar:3", "inventory:-1"), order);
    }

    @Test
    void hotbarIndexOutOfRangeIsRejected() {
        LoadoutBuilder b = new LoadoutBuilder();
        assertThrows(IllegalArgumentException.class, () -> b.hotbar(-1, spec("bow")));
        assertThrows(IllegalArgumentException.class, () -> b.hotbar(9, spec("bow")));
    }

    @Test
    void wearingASecondPieceInTheSameSlotReplacesTheFirst() {
        LoadoutBuilder b = new LoadoutBuilder();
        b.wear(BotProfile.Slot.CHEST, spec("iron_chestplate"));
        b.wear(BotProfile.Slot.CHEST, spec("elytra"));
        BotProfile.Loadout l = b.build();
        assertEquals(1, l.items().size());
        assertEquals(NS + "elytra", l.items().get(0).spec().item());
        assertEquals(NS + "elytra", b.worn(BotProfile.Slot.CHEST).item());
    }

    @Test
    void aFullMainInventoryFitsExactlyAndNothingIsTouched() {
        LoadoutBuilder b = new LoadoutBuilder();
        for (int i = 0; i < LoadoutBuilder.MAIN_SIZE; i++) {
            b.stock(potion("healing"), LoadoutBuilder.BUFF);
        }
        BotProfile.Loadout l = b.build();
        assertEquals(LoadoutBuilder.MAIN_SIZE, inSlot(l, BotProfile.Slot.INVENTORY));
        assertEquals(0, inSlot(l, BotProfile.Slot.HOTBAR));
        assertEquals(0, b.trimmed());
    }

    @Test
    void overflowSpillsIntoSpareHotbarSlotsBeforeAnythingIsDropped() {
        LoadoutBuilder b = new LoadoutBuilder();
        b.hotbar(0, spec("iron_sword"));
        b.hotbar(1, spec("shield"));
        b.hotbar(8, BotProfile.ItemSpec.of(NS + "bread", 20));
        for (int i = 0; i < LoadoutBuilder.MAIN_SIZE + 4; i++) {
            b.stock(potion("healing"), LoadoutBuilder.SUSTAIN);
        }
        BotProfile.Loadout l = b.build();
        assertEquals(LoadoutBuilder.MAIN_SIZE, inSlot(l, BotProfile.Slot.INVENTORY));
        assertEquals(3 + 4, inSlot(l, BotProfile.Slot.HOTBAR), "the four overflow stacks join the three weapons/food");
        assertEquals(0, b.trimmed());
        // the spill never displaces what the layout put there and starts in the middle of the bar
        assertEquals(NS + "iron_sword", l.items().stream().filter(p -> p.slot().equals("hotbar") && p.index() == 0)
                .findFirst().orElseThrow().spec().item());
        for (int index : new int[]{2, 3, 4, 5}) {
            assertTrue(l.items().stream().anyMatch(p -> p.slot().equals("hotbar") && p.index() == index), "slot " + index);
        }
    }

    @Test
    void whenEverythingIsFullTheMostDroppableStacksAreDroppedFirst() {
        LoadoutBuilder b = new LoadoutBuilder();
        // an empty hotbar (9 free slots) + full main inventory (27) = 36; ask for 40
        for (int i = 0; i < 6; i++) {
            b.stock(BotProfile.ItemSpec.of(NS + "arrow", 64), LoadoutBuilder.AMMO);
        }
        for (int i = 0; i < 14; i++) {
            b.stock(potion("healing"), LoadoutBuilder.SUSTAIN);
        }
        for (int i = 0; i < 12; i++) {
            b.stock(potion("strength"), LoadoutBuilder.BUFF);
        }
        for (int i = 0; i < 8; i++) {
            b.stock(potion("swiftness"), LoadoutBuilder.BUFF);
        }
        BotProfile.Loadout l = b.build();
        assertEquals(4, b.trimmed());
        assertEquals(36, l.items().size());
        long arrows = l.items().stream().filter(p -> p.spec().item().endsWith("arrow")).count();
        long healing = l.items().stream().filter(p -> NS.concat("healing").equals(p.spec().potion())).count();
        long swiftness = l.items().stream().filter(p -> (NS + "swiftness").equals(p.spec().potion())).count();
        assertEquals(6, arrows, "ammunition is the last to go");
        assertEquals(14, healing, "healing outranks the buffs");
        assertEquals(4, swiftness, "the four dropped stacks are the LAST buff potions added: " + swiftness);
    }

    @Test
    void spillSkipsOccupiedSlotsAndUsesTheWeaponSlotOnlyAsALastResort() {
        LoadoutBuilder b = new LoadoutBuilder();
        for (int i = 0; i < LoadoutBuilder.MAIN_SIZE + 8; i++) {
            b.stock(potion("healing"), LoadoutBuilder.SUSTAIN);
        }
        BotProfile.Loadout l = b.build();
        assertEquals(8, inSlot(l, BotProfile.Slot.HOTBAR));
        assertFalse(l.items().stream().anyMatch(p -> p.slot().equals("hotbar") && p.index() == 0),
                "slot 0 is the weapon slot: eight spills fit in 2-7, 1 and 8");
        b = new LoadoutBuilder();
        for (int i = 0; i < LoadoutBuilder.MAIN_SIZE + 9; i++) {
            b.stock(potion("healing"), LoadoutBuilder.SUSTAIN);
        }
        assertTrue(b.build().items().stream().anyMatch(p -> p.slot().equals("hotbar") && p.index() == 0));
    }
}

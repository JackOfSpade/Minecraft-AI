package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.store.BotSnapshot;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inventory half of {@link BotSnapshots}, on a bare inventory (no server): every slot, count, damage and data
 * component goes into the snapshot and comes back the same, and nothing is refilled on the way. Run inside {@link McSandbox}.
 */
public final class BotSnapshotsMcCases {
    private BotSnapshotsMcCases() {
    }

    private static Inventory newInventory() {
        return new Inventory(null, new EntityEquipment());
    }

    private static ItemStack damaged(net.minecraft.world.item.Item item, int damage) {
        ItemStack stack = new ItemStack(item);
        stack.setDamageValue(damage);
        return stack;
    }

    /** A bot that has been fighting: a worn bow, a part-used quiver, damaged armor, a named item, a shield, some food. */
    private static Inventory usedKit() {
        Inventory inventory = newInventory();
        inventory.setItem(0, damaged(Items.BOW, 31));
        inventory.setItem(1, damaged(Items.IRON_SWORD, 12));
        inventory.setItem(9, new ItemStack(Items.ARROW, 37));
        inventory.setItem(10, new ItemStack(Items.COOKED_BEEF, 5));
        ItemStack named = new ItemStack(Items.STICK, 3);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Rusty"));
        inventory.setItem(11, named);
        inventory.setItem(36, damaged(Items.IRON_BOOTS, 20));
        inventory.setItem(38, damaged(Items.IRON_CHESTPLATE, 120));
        inventory.setItem(40, damaged(Items.SHIELD, 90));
        inventory.setSelectedSlot(1);
        return inventory;
    }

    private static void assertSame(Inventory expected, Inventory actual) {
        for (int slot = 0; slot < expected.getContainerSize(); slot++) {
            ItemStack e = expected.getItem(slot);
            ItemStack a = actual.getItem(slot);
            assertEquals(e.isEmpty(), a.isEmpty(), "slot " + slot + " emptiness");
            if (!e.isEmpty()) {
                assertTrue(ItemStack.matches(e, a), "slot " + slot + ": " + e + " vs " + a);
                assertEquals(e.getCount(), a.getCount(), "slot " + slot + " count");
                assertEquals(e.getDamageValue(), a.getDamageValue(), "slot " + slot + " damage");
            }
        }
        assertEquals(expected.getSelectedSlot(), actual.getSelectedSlot(), "selected slot");
    }

    public static void everySlotComesBackExactlyIncludingArmorOffhandDamageAndComponents() {
        Inventory before = usedKit();
        BotSnapshot snapshot = new BotSnapshot();
        List<String> warnings = new ArrayList<>();
        BotSnapshots.captureInventory(before, McBootstrap.registries(), snapshot, warnings);
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertEquals(8, snapshot.stacks.size(), "only the non-empty slots are listed");
        assertEquals(1, snapshot.selectedSlot);

        Inventory after = newInventory();
        assertEquals(8, BotSnapshots.restoreInventory(after, McBootstrap.registries(), snapshot, warnings));
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertSame(before, after);
        assertEquals(37, after.getItem(9).getCount(), "arrows fired stay fired");
        assertEquals(120, after.getItem(38).getDamageValue(), "worn armor stays worn");
        assertEquals("Rusty", after.getItem(11).get(DataComponents.CUSTOM_NAME).getString());
    }

    /** What dormancy used to do: dress the bot from its profile again. A restore must leave nothing of what is on the bot. */
    public static void restoringNeverRefillsAndEmptiesSlotsThatWereEmptyInTheSnapshot() {
        Inventory used = newInventory();
        used.setItem(0, new ItemStack(Items.BOW));
        used.setItem(9, new ItemStack(Items.ARROW, 3));
        BotSnapshot snapshot = new BotSnapshot();
        BotSnapshots.captureInventory(used, McBootstrap.registries(), snapshot, new ArrayList<>());

        Inventory fresh = newInventory(); // what a new fake player carries after a profile dressing: everything full
        fresh.setItem(0, new ItemStack(Items.BOW));
        fresh.setItem(9, new ItemStack(Items.ARROW, 64));
        fresh.setItem(10, new ItemStack(Items.ARROW, 64));
        fresh.setItem(11, new ItemStack(Items.GOLDEN_APPLE, 8));
        fresh.setItem(38, new ItemStack(Items.NETHERITE_CHESTPLATE));
        fresh.setItem(40, new ItemStack(Items.TOTEM_OF_UNDYING));

        BotSnapshots.restoreInventory(fresh, McBootstrap.registries(), snapshot, new ArrayList<>());
        assertEquals(3, fresh.getItem(9).getCount());
        assertTrue(fresh.getItem(10).isEmpty());
        assertTrue(fresh.getItem(11).isEmpty());
        assertTrue(fresh.getItem(38).isEmpty());
        assertTrue(fresh.getItem(40).isEmpty());
    }

    public static void aBotThatCarriedNothingComesBackWithNothing() {
        Inventory naked = newInventory();
        BotSnapshot snapshot = new BotSnapshot();
        BotSnapshots.captureInventory(naked, McBootstrap.registries(), snapshot, new ArrayList<>());
        assertTrue(snapshot.stacks.isEmpty());

        Inventory target = usedKit();
        assertEquals(0, BotSnapshots.restoreInventory(target, McBootstrap.registries(), snapshot, new ArrayList<>()));
        for (int slot = 0; slot < target.getContainerSize(); slot++) {
            assertTrue(target.getItem(slot).isEmpty(), "slot " + slot);
        }
    }

    public static void anUnreadableStackIsSkippedWithAWarningAndTheRestStillComesBack() {
        Inventory before = usedKit();
        BotSnapshot snapshot = new BotSnapshot();
        BotSnapshots.captureInventory(before, McBootstrap.registries(), snapshot, new ArrayList<>());
        snapshot.stacks.set(3, new BotSnapshot.Entry(snapshot.stacks.get(3).slot, "{\"id\":\"minecraft:not_an_item\",\"count\":1}"));
        snapshot.stacks.add(new BotSnapshot.Entry(99, "{\"id\":\"minecraft:stick\",\"count\":1}"));
        snapshot.stacks.add(new BotSnapshot.Entry(4, "this is not json"));

        Inventory after = newInventory();
        List<String> warnings = new ArrayList<>();
        int restored = BotSnapshots.restoreInventory(after, McBootstrap.registries(), snapshot, warnings);
        assertEquals(7, restored, "one bad item, one slot that does not exist, one text that is not JSON");
        assertEquals(3, warnings.size(), warnings.toString());
        assertEquals(37, after.getItem(9).getCount());
    }

    public static void aSnapshotThatCannotBeReadAtAllLeavesTheInventoryAlone() {
        BotSnapshot snapshot = new BotSnapshot();
        snapshot.stacks.add(new BotSnapshot.Entry(0, "{\"id\":\"minecraft:not_an_item\",\"count\":1}"));
        Inventory carrying = usedKit();
        List<String> warnings = new ArrayList<>();
        assertEquals(0, BotSnapshots.restoreInventory(carrying, McBootstrap.registries(), snapshot, warnings));
        assertFalse(warnings.isEmpty());
        assertEquals(37, carrying.getItem(9).getCount(), "not wiped for a snapshot that could not be read");
    }

    public static void theSelectedSlotIsRestoredAndAnOutOfRangeOneFallsBackToTheFirst() {
        BotSnapshot snapshot = new BotSnapshot();
        snapshot.stacks.add(new BotSnapshot.Entry(0, "{\"id\":\"minecraft:stick\",\"count\":1}"));
        snapshot.selectedSlot = 4;
        Inventory inventory = newInventory();
        BotSnapshots.restoreInventory(inventory, McBootstrap.registries(), snapshot, new ArrayList<>());
        assertEquals(4, inventory.getSelectedSlot());

        snapshot.selectedSlot = 30;
        BotSnapshots.restoreInventory(inventory, McBootstrap.registries(), snapshot, new ArrayList<>());
        assertEquals(0, inventory.getSelectedSlot());
        snapshot.selectedSlot = -2;
        BotSnapshots.restoreInventory(inventory, McBootstrap.registries(), snapshot, new ArrayList<>());
        assertEquals(0, inventory.getSelectedSlot());
    }

    /** The stack text is vanilla's own item codec, so it is readable JSON with the item id and the count. */
    public static void theStoredTextIsTheVanillaItemCodecJson() {
        Inventory inventory = newInventory();
        inventory.setItem(2, new ItemStack(Items.ARROW, 37));
        BotSnapshot snapshot = new BotSnapshot();
        BotSnapshots.captureInventory(inventory, McBootstrap.registries(), snapshot, new ArrayList<>());
        String text = snapshot.stackAt(2);
        assertNotNull(text);
        assertTrue(text.contains("minecraft:arrow"), text);
        assertTrue(text.contains("37"), text);
    }

    /** Exhaustion is private in vanilla's FoodData; it is read through the food data's own save routine. */
    public static void theExhaustionAccumulatorIsReadThroughTheSaveRoutineAndCanBePutBack() {
        FoodData food = new FoodData();
        assertEquals(0.0f, BotSnapshots.exhaustionOf(food), 1e-6);
        food.addExhaustion(2.5f);
        assertEquals(2.5f, BotSnapshots.exhaustionOf(food), 1e-6);

        // What restoreVitals does: add the difference to the recorded value (a fresh fake player starts at zero).
        FoodData fresh = new FoodData();
        fresh.addExhaustion(3.25f - BotSnapshots.exhaustionOf(fresh));
        assertEquals(3.25f, BotSnapshots.exhaustionOf(fresh), 1e-6);
        fresh.addExhaustion(1.0f - BotSnapshots.exhaustionOf(fresh));
        assertEquals(1.0f, BotSnapshots.exhaustionOf(fresh), 1e-6, "also downwards");
    }

    public static void foodLevelAndSaturationSetThroughTheVanillaSetters() {
        FoodData food = new FoodData();
        food.setFoodLevel(14);
        food.setSaturation(2.5f);
        assertEquals(14, food.getFoodLevel());
        assertEquals(2.5f, food.getSaturationLevel(), 1e-6);
        assertTrue(food.needsFood(), "14 is below a full food bar");
    }
}

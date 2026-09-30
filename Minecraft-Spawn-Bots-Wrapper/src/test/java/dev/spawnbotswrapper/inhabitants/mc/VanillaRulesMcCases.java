package dev.spawnbotswrapper.inhabitants.mc;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The eating rule of {@link VanillaRules} against the real item data, run inside {@link McSandbox}. */
public final class VanillaRulesMcCases {
    private VanillaRulesMcCases() {
    }

    private static FoodProperties food(Item item) {
        return new ItemStack(item).get(DataComponents.FOOD);
    }

    /** True when this item, being eaten at a full food bar, must be stopped. */
    private static boolean stoppedAtFullFood(Item item) {
        FoodProperties food = food(item);
        return VanillaRules.mustStopEating(food != null, food != null && food.canAlwaysEat(), 20);
    }

    public static void ordinaryFoodIsStoppedAtAFullFoodBar() {
        for (Item item : new Item[]{Items.BREAD, Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.APPLE, Items.GOLDEN_CARROT,
                Items.BAKED_POTATO, Items.COOKED_CHICKEN}) {
            assertNotNull(food(item), item + " is food");
            assertFalse(food(item).canAlwaysEat(), item + " is not always edible in vanilla");
            assertTrue(stoppedAtFullFood(item), item + " cannot be eaten at full food");
        }
    }

    public static void alwaysEdibleFoodIsLeftAlone() {
        for (Item item : new Item[]{Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE, Items.CHORUS_FRUIT}) {
            assertNotNull(food(item), item + " is food");
            assertTrue(food(item).canAlwaysEat(), item + " is always edible in vanilla");
            assertFalse(stoppedAtFullFood(item), item + " may be eaten at full food");
        }
    }

    /** Potions and milk are drunk, not eaten: they have no food component, and the rule never applies to them. */
    public static void potionsAndMilkAreNotFoodAndAreNeverStopped() {
        for (Item item : new Item[]{Items.POTION, Items.MILK_BUCKET, Items.SPLASH_POTION}) {
            assertNull(food(item), item + " has no food component");
            assertFalse(stoppedAtFullFood(item), item + " is never stopped");
        }
    }

    public static void theGateOnlyStopsAtExactlyAFullFoodBar() {
        for (int level = 0; level < 20; level++) {
            assertFalse(VanillaRules.mustStopEating(true, false, level), "food level " + level + " may eat");
        }
        assertTrue(VanillaRules.mustStopEating(true, false, 20));
        assertTrue(VanillaRules.mustStopEating(true, false, 21), "never above the maximum either");
        assertFalse(VanillaRules.mustStopEating(true, true, 20), "always edible");
        assertFalse(VanillaRules.mustStopEating(false, false, 20), "not food at all");
        assertEquals(false, VanillaRules.mustStopEating(false, true, 20));
    }
}

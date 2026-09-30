package io.github.zoyluo.minecraftai.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import org.junit.jupiter.api.Test;

/** The pure decisions of the durability chat warning: threshold, eligibility, once per crossing, re-arm, message, config. */
class DurabilityWarningsTest {
    private static final DurabilityWarnings.Core.Step NONE = DurabilityWarnings.Core.Step.NONE;
    private static final DurabilityWarnings.Core.Step WARN = DurabilityWarnings.Core.Step.WARN;
    private static final DurabilityWarnings.Core.Step REARM = DurabilityWarnings.Core.Step.REARM;

    @Test
    void theThresholdIsStrictlyBelowTenPercentOfTheMaximum() {
        // Diamond pickaxe: 1561 uses, 10 percent is 156.1: 156 left is below it, 157 is not.
        assertTrue(DurabilityWarnings.Core.isLow(156, 1561, 10.0D));
        assertFalse(DurabilityWarnings.Core.isLow(157, 1561, 10.0D));
        // A shield (336): 33.6 is the line, 33 left is below, 34 is not.
        assertTrue(DurabilityWarnings.Core.isLow(33, 336, 10.0D));
        assertFalse(DurabilityWarnings.Core.isLow(34, 336, 10.0D));
        // Exactly ten percent is not below it.
        assertFalse(DurabilityWarnings.Core.isLow(10, 100, 10.0D));
        assertTrue(DurabilityWarnings.Core.isLow(9, 100, 10.0D));
        // An item that cannot break never warns; a custom threshold works.
        assertFalse(DurabilityWarnings.Core.isLow(0, 0, 10.0D));
        assertTrue(DurabilityWarnings.Core.isLow(24, 100, 25.0D));
        assertFalse(DurabilityWarnings.Core.isLow(25, 100, 25.0D));
    }

    @Test
    void theEligibleItemsAreDiamondAndNetheriteGearPlusTheItemsWithNoOreTier() {
        // What vanilla repairs with a diamond or a netherite ingot (the repairable component) is passed in as the flag.
        assertTrue(DurabilityWarnings.Core.isEligible("diamond_pickaxe", true));
        assertTrue(DurabilityWarnings.Core.isEligible("netherite_chestplate", true));
        assertFalse(DurabilityWarnings.Core.isEligible("iron_pickaxe", false));
        assertFalse(DurabilityWarnings.Core.isEligible("golden_sword", false));
        assertFalse(DurabilityWarnings.Core.isEligible("stone_axe", false));
        assertFalse(DurabilityWarnings.Core.isEligible("leather_helmet", false));
        assertFalse(DurabilityWarnings.Core.isEligible("turtle_helmet", false));
        // The items without an ore tier: shield, bow, crossbow, trident, mace, elytra, fishing rod.
        for (String path : new String[] {"shield", "bow", "crossbow", "trident", "mace", "elytra", "fishing_rod"}) {
            assertTrue(DurabilityWarnings.Core.isEligible(path, false), path);
        }
        // Items that merely share a word are not eligible (no substring matching).
        assertFalse(DurabilityWarnings.Core.isEligible("shears", false));
        assertFalse(DurabilityWarnings.Core.isEligible("carrot_on_a_stick", false));
        assertFalse(DurabilityWarnings.Core.isEligible("flint_and_steel", false));
        assertFalse(DurabilityWarnings.Core.isEligible(null, false));
    }

    @Test
    void anItemWarnsOncePerCrossingAndIsArmedAgainOnlyWhenItIsHealthyAgain() {
        // First scan below the threshold: warn (and mark). Every later scan below it: nothing.
        assertEquals(WARN, DurabilityWarnings.Core.step(true, false));
        assertEquals(NONE, DurabilityWarnings.Core.step(true, true));
        // Healthy and never warned: nothing. Healthy again after a warning (repaired): clear the marker, arm again.
        assertEquals(NONE, DurabilityWarnings.Core.step(false, false));
        assertEquals(REARM, DurabilityWarnings.Core.step(false, true));
        // The full cycle.
        boolean marked = false;
        int warnings = 0;
        boolean[] lowOverTime = {false, true, true, true, false, false, true, true};
        for (boolean low : lowOverTime) {
            DurabilityWarnings.Core.Step step = DurabilityWarnings.Core.step(low, marked);
            if (step == WARN) {
                warnings++;
                marked = true;
            } else if (step == REARM) {
                marked = false;
            }
        }
        assertEquals(2, warnings, "one line per crossing, two crossings");
    }

    @Test
    void severalItemsCrossingTogetherAreEachWarnedInTheSameScan() {
        // There is no rate limit and no queue: the state machine has no notion of time, every item is judged on its own.
        int warnings = 0;
        for (int item = 0; item < 6; item++) {
            if (DurabilityWarnings.Core.step(true, false) == WARN) {
                warnings++;
            }
        }
        assertEquals(6, warnings);
    }

    @Test
    void theMessageNamesTheItemAndTheUsesLeft() {
        assertEquals("My diamond pickaxe is about to break (14/1561 left).",
                DurabilityWarnings.Core.message("diamond_pickaxe", 14, 1561));
        assertEquals("My fishing rod is about to break (5/64 left).", DurabilityWarnings.Core.message("fishing_rod", 5, 64));
        assertEquals("My item is about to break (1/2 left).", DurabilityWarnings.Core.message(null, 1, 2));
    }

    @Test
    void theConfigDefaultsToOnAtTenPercentAndOldConfigsStillLoad() {
        MinecraftAiConfig.DurabilityWarnings defaults = MinecraftAiConfig.DurabilityWarnings.defaults();
        assertTrue(defaults.enabledOn());
        assertEquals(10.0D, defaults.thresholdOrDefault());
        // A config file from before the section (only worstFirst) reads as the defaults.
        MinecraftAiConfig.Gear old = new MinecraftAiConfig.Gear(false);
        assertFalse(old.worstFirstEnabled());
        assertTrue(old.durabilityWarningsOrDefaults().enabledOn());
        assertEquals(10.0D, old.durabilityWarningsOrDefaults().thresholdOrDefault());
        // Null members and nonsense thresholds fall back; a real value is kept; off is off.
        assertTrue(new MinecraftAiConfig.DurabilityWarnings(null, null).enabledOn());
        assertEquals(10.0D, new MinecraftAiConfig.DurabilityWarnings(true, -5.0D).thresholdOrDefault());
        assertEquals(10.0D, new MinecraftAiConfig.DurabilityWarnings(true, 0.0D).thresholdOrDefault());
        assertEquals(10.0D, new MinecraftAiConfig.DurabilityWarnings(true, Double.NaN).thresholdOrDefault());
        assertEquals(10.0D, new MinecraftAiConfig.DurabilityWarnings(true, 250.0D).thresholdOrDefault());
        assertEquals(15.0D, new MinecraftAiConfig.DurabilityWarnings(true, 15.0D).thresholdOrDefault());
        assertFalse(new MinecraftAiConfig.DurabilityWarnings(false, 10.0D).enabledOn());
    }
}

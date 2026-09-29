package io.github.zoyluo.minecraftai.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.zoyluo.minecraftai.action.FoodPolicy.Option;
import io.github.zoyluo.minecraftai.action.FoodPolicy.Tier;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The pure half of the food chooser: tiers, reserve rules, and "fit the gap without waste". */
final class FoodPolicyTest {
    private static final Option BREAD = new Option(1, 5, 6.0F, Tier.COMMON);
    private static final Option STEAK = new Option(2, 8, 12.8F, Tier.COMMON);
    private static final Option CARROT = new Option(3, 3, 3.6F, Tier.COMMON);
    private static final Option GOLDEN_CARROT = new Option(4, 6, 14.4F, Tier.VALUABLE);
    private static final Option GOLDEN_APPLE = new Option(5, 4, 9.6F, Tier.EMERGENCY_ONLY);
    private static final Option ROTTEN = new Option(6, 4, 0.8F, Tier.LAST_RESORT);
    private static final Option CHORUS = new Option(7, 4, 2.4F, Tier.NEVER);

    private static int pick(int gap, boolean emergency, Option... options) {
        Option chosen = FoodPolicy.choose(List.of(options), gap, emergency);
        return chosen == null ? -1 : chosen.id();
    }

    @Test
    void smallGapPrefersCheapFoodOverSteakAndGoldenCarrot() {
        // gap 3: carrot wastes 0, bread 2, steak 5, golden carrot 3 (and is valuable anyway).
        assertEquals(3, pick(3, false, STEAK, GOLDEN_CARROT, BREAD, CARROT));
        assertEquals(1, pick(5, false, GOLDEN_CARROT, STEAK, BREAD));
    }

    @Test
    void largeGapTakesTheBiggerFood() {
        assertEquals(2, pick(12, false, BREAD, STEAK, CARROT));
        assertEquals(2, pick(8, false, BREAD, STEAK));
    }

    @Test
    void firstFitBeatsBiggerWhenItFits() {
        // gap 7: bread does not fit, steak does (waste 1).
        assertEquals(2, pick(7, false, BREAD, STEAK));
    }

    @Test
    void valuableFoodOnlyWhenNoCommonFoodExists() {
        assertEquals(1, pick(6, false, GOLDEN_CARROT, BREAD));
        assertEquals(4, pick(6, false, GOLDEN_CARROT, ROTTEN));
    }

    @Test
    void goldenAppleOnlyInAnEmergencyAndAfterOrdinaryFood() {
        assertEquals(-1, pick(10, false, GOLDEN_APPLE));
        assertEquals(1, pick(10, false, GOLDEN_APPLE, BREAD));
        assertEquals(5, pick(10, true, GOLDEN_APPLE));
        assertEquals(1, pick(10, true, GOLDEN_APPLE, BREAD));
        assertEquals(5, pick(10, true, ROTTEN, GOLDEN_APPLE));
    }

    @Test
    void harmfulFoodIsTheLastResort() {
        assertEquals(6, pick(10, false, ROTTEN));
        assertEquals(1, pick(10, false, ROTTEN, BREAD));
    }

    @Test
    void neverFoodIsNeverChosenEvenWhenStarving() {
        assertEquals(-1, pick(20, true, CHORUS));
        assertEquals(-1, pick(20, false, CHORUS));
        assertEquals(6, pick(20, true, CHORUS, ROTTEN));
    }

    @Test
    void emptyAndTiesAreStable() {
        assertNull(FoodPolicy.choose(List.of(), 10, true));
        Option a = new Option(10, 5, 6.0F, Tier.COMMON);
        Option b = new Option(11, 5, 6.0F, Tier.COMMON);
        assertEquals(10, pick(5, false, a, b));
        assertEquals(11, pick(5, false, b, a));
    }

    @Test
    void hungerGapIsClamped() {
        assertEquals(0, FoodPolicy.hungerGap(25));
        assertEquals(20, FoodPolicy.hungerGap(0));
    }
}

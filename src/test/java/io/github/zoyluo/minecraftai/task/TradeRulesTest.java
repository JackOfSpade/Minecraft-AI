package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The pure half of TradeTask: the gate of Villager.mobInteract and whole-result inventory room. */
final class TradeRulesTest {
    @Test
    void anOrdinaryAwakeAdultVillagerWithOffersTrades() {
        assertNull(TradeRules.refusal(true, false, false, false, true));
    }

    @Test
    void aSleepingBabyBusyOrOfferlessVillagerRefuses() {
        assertEquals("villager_lost", TradeRules.refusal(false, false, false, false, true));
        assertEquals("villager_asleep", TradeRules.refusal(true, false, true, false, true));
        assertEquals("villager_busy", TradeRules.refusal(true, false, false, true, true));
        assertEquals("villager_baby", TradeRules.refusal(true, true, false, false, true));
        assertEquals("villager_has_no_offers", TradeRules.refusal(true, false, false, false, false));
    }

    @Test
    void sleepingOutranksTheOtherRefusalsAsInVanilla() {
        // mobInteract hands a sleeping villager to the default handler before it looks at age or offers.
        assertEquals("villager_asleep", TradeRules.refusal(true, true, true, true, false));
    }

    @Test
    void roomCountsEmptySlotsAndThePartlyFilledStacksOfTheSameStackType() {
        assertEquals(64 * 2 + 10 + 3, TradeRules.insertable(64, List.of(54, 61), 2));
        assertEquals(0, TradeRules.insertable(64, List.of(64, 64), 0));
        assertEquals(16 * 3, TradeRules.insertable(16, List.of(), 3));
    }

    @Test
    void aResultThatDoesNotFullyFitIsDetectedByComparingRoomToTheCount() {
        // 16 arrows into one slot holding 54 (10 free) and no empty slot: a partial insert would lose 6.
        assertEquals(10, TradeRules.insertable(64, List.of(54), 0));
    }

    @Test
    void roomIsNeverNegative() {
        assertEquals(0, TradeRules.insertable(64, List.of(70), -1));
    }
}

package dev.spawnbotswrapper.inhabitants.combat;

import dev.spawnbotswrapper.inhabitants.combat.OffhandRule.Held;
import dev.spawnbotswrapper.inhabitants.combat.OffhandRule.Move;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The offhand decision table (O1): the best shield, else a totem, else as it is; the same kind first. */
class OffhandRuleTest {
    @Test
    void anEmptyOffhandGetsTheShieldBeforeTheTotemAndTheTotemBeforeNothing() {
        assertEquals(Move.SHIELD, OffhandRule.decide(Held.EMPTY, true, true));
        assertEquals(Move.SHIELD, OffhandRule.decide(Held.EMPTY, true, false));
        assertEquals(Move.TOTEM, OffhandRule.decide(Held.EMPTY, false, true));
        assertEquals(Move.NONE, OffhandRule.decide(Held.EMPTY, false, false));
    }

    @Test
    void aHeldShieldIsNeverSwapped() {
        for (boolean shield : new boolean[] {false, true}) {
            for (boolean totem : new boolean[] {false, true}) {
                assertEquals(Move.NONE, OffhandRule.decide(Held.SHIELD, shield, totem));
            }
        }
    }

    @Test
    void aHeldTotemGivesWayToACarriedShieldOnly() {
        assertEquals(Move.SHIELD, OffhandRule.decide(Held.TOTEM, true, true));
        assertEquals(Move.SHIELD, OffhandRule.decide(Held.TOTEM, true, false));
        assertEquals(Move.NONE, OffhandRule.decide(Held.TOTEM, false, true));
        assertEquals(Move.NONE, OffhandRule.decide(Held.TOTEM, false, false));
    }

    @Test
    void anyOtherOffhandItemIsLeftAlone() {
        for (boolean shield : new boolean[] {false, true}) {
            for (boolean totem : new boolean[] {false, true}) {
                assertEquals(Move.NONE, OffhandRule.decide(Held.OTHER, shield, totem));
            }
        }
    }

    @Test
    void theLadderWithTwoShieldsAndTwoTotems() {
        assertEquals(Move.SHIELD, OffhandRule.decide(Held.EMPTY, true, true));   // shield 1
        assertEquals(Move.SHIELD, OffhandRule.decide(Held.EMPTY, true, true));   // shield 1 broke: shield 2
        assertEquals(Move.TOTEM, OffhandRule.decide(Held.EMPTY, false, true));   // shield 2 broke: totem 1
        assertEquals(Move.TOTEM, OffhandRule.decide(Held.EMPTY, false, true));   // totem 1 popped: totem 2
        assertEquals(Move.NONE, OffhandRule.decide(Held.EMPTY, false, false));   // totem 2 popped: nothing
    }

    private static int best(String slots) {
        // One character per slot: 's' a plain shield, 'e' an enchanted shield, '.' something else.
        return OffhandRule.bestShield(i -> slots.charAt(i) != '.', i -> slots.charAt(i) == 'e', slots.length());
    }

    @Test
    void theBestShieldIsTheEnchantedOneThenTheLowestSlot() {
        assertEquals(-1, best("...."));
        assertEquals(2, best("..s."), "the only shield");
        assertEquals(1, best(".ss."), "equal plain shields: the lowest slot");
        assertEquals(2, best("s.es"), "an enchanted shield beats plain ones in lower slots");
        assertEquals(1, best(".ee."), "equal enchanted shields: the lowest slot");
        assertEquals(3, best("ss.e"), "the enchanted one wins wherever it is");
        assertEquals(0, best("es.s"), "an enchanted shield in the lowest slot stays the best");
    }
}

package io.github.zoyluo.minecraftai.action;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.zoyluo.minecraftai.action.OffhandPolicy.Core.Held;
import io.github.zoyluo.minecraftai.action.OffhandPolicy.Core.Move;
import org.junit.jupiter.api.Test;

/** The offhand decision table (O1): the best shield, else a totem, else as it is; same kind first, any other offhand item untouched. */
class OffhandPolicyTest {
    private static Move decide(Held held, boolean shield, boolean totem) {
        return OffhandPolicy.Core.decide(held, shield, totem);
    }

    @Test
    void anEmptyOffhandGetsTheShieldBeforeTheTotemAndTheTotemBeforeNothing() {
        assertEquals(Move.SHIELD, decide(Held.EMPTY, true, true));
        assertEquals(Move.SHIELD, decide(Held.EMPTY, true, false));
        assertEquals(Move.TOTEM, decide(Held.EMPTY, false, true));
        assertEquals(Move.NONE, decide(Held.EMPTY, false, false));
    }

    @Test
    void aHeldShieldIsNeverSwappedForAnything() {
        for (boolean shield : new boolean[] {false, true}) {
            for (boolean totem : new boolean[] {false, true}) {
                assertEquals(Move.NONE, decide(Held.SHIELD, shield, totem));
            }
        }
    }

    @Test
    void aHeldTotemGivesWayToACarriedShieldOnly() {
        assertEquals(Move.SHIELD, decide(Held.TOTEM, true, true));
        assertEquals(Move.SHIELD, decide(Held.TOTEM, true, false));
        assertEquals(Move.NONE, decide(Held.TOTEM, false, true));
        assertEquals(Move.NONE, decide(Held.TOTEM, false, false));
    }

    @Test
    void anyOtherOffhandItemIsLeftAlone() {
        for (boolean shield : new boolean[] {false, true}) {
            for (boolean totem : new boolean[] {false, true}) {
                assertEquals(Move.NONE, decide(Held.OTHER, shield, totem));
            }
        }
    }

    @Test
    void theLadderAfterEveryBreakIsTheSameKindThenTheNextRung() {
        // Two shields and two totems carried, offhand empty after each loss.
        assertEquals(Move.SHIELD, decide(Held.EMPTY, true, true));   // shield 1
        assertEquals(Move.SHIELD, decide(Held.EMPTY, true, true));   // shield 1 broke: shield 2
        assertEquals(Move.TOTEM, decide(Held.EMPTY, false, true));   // shield 2 broke: totem 1
        assertEquals(Move.TOTEM, decide(Held.EMPTY, false, true));   // totem 1 popped: totem 2
        assertEquals(Move.NONE, decide(Held.EMPTY, false, false));   // totem 2 popped: nothing
    }
}

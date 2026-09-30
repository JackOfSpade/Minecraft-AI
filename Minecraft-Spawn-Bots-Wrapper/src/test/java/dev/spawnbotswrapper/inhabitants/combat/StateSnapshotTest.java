package dev.spawnbotswrapper.inhabitants.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The compact state string is what an operator reads in latest.log, so its exact shape is pinned. */
class StateSnapshotTest {

    @Test
    void aBotDrawingAnUnloadedCrossbowNextToAPlayer() {
        StateSnapshot s = new StateSnapshot(2, "crossbow", false, "shield", true, "crossbow", 7, 18, 12, 0,
                true, true, true, "Steve", 2.345, true, true, false, false,
                "global(combat=1,autoTarget=1,ranged=1) target=Steve mode=RANGED draw=1/12");
        assertEquals("slot=2 main=crossbow(unloaded) off=shield using=crossbow(7/25t) ammo=arrows:12,rockets:0"
                + " carries=bow,crossbow,melee nearest=Steve@2.3 los=yes ground=1 water=0 web=0"
                + " pvpbot=global(combat=1,autoTarget=1,ranged=1) target=Steve mode=RANGED draw=1/12", s.format());
    }

    @Test
    void aChargedCrossbowIsMarkedAndAnIdleBotSaysNoUse() {
        StateSnapshot s = new StateSnapshot(0, "crossbow", true, "empty", false, "none", 0, 0, 0, 3,
                false, true, false, "zombie", 5.0, false, false, true, true, null);
        assertEquals("slot=0 main=crossbow(charged) off=empty using=no ammo=arrows:0,rockets:3"
                + " carries=crossbow nearest=zombie@5.0 los=no ground=0 water=1 web=1 pvpbot=unreadable", s.format());
    }

    @Test
    void noNearbyPlayerAndNoWeaponsAreSpelledOut() {
        StateSnapshot s = new StateSnapshot(8, "stick", null, "empty", false, "none", 0, 0, 0, 0,
                false, false, false, null, -1, null, true, false, false, null);
        assertEquals("slot=8 main=stick off=empty using=no ammo=arrows:0,rockets:0 carries=none nearest=none"
                + " ground=1 water=0 web=0 pvpbot=unreadable", s.format());
    }

    @Test
    void anUnmeasuredLineOfSightAndDistanceAreMarked() {
        StateSnapshot s = new StateSnapshot(1, "bow", null, "empty", false, "none", 0, 0, 1, 0,
                true, false, false, "Steve", -1, null, true, false, false, null);
        assertTrue(s.format().contains("nearest=Steve los=?"), s.format());
    }
}

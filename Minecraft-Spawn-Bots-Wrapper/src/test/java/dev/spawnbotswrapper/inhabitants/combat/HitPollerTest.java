package dev.spawnbotswrapper.inhabitants.combat;

import dev.spawnbotswrapper.inhabitants.combat.HitPoller.Hit;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Notices hits on an inhabitant from its recorded damage source and health, without the damage event. */
class HitPollerTest {
    private static final UUID BOT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void theFirstObservationOnlySetsTheBaselineEvenWithAStaleSource() {
        HitPoller p = new HitPoller();
        assertNull(p.observe(BOT, new Object(), 20.0f), "a source seen at first sight may be old; it is not a new hit");
    }

    @Test
    void aNewSourceWithAHealthDropIsAHitWithThatDamage() {
        HitPoller p = new HitPoller();
        p.observe(BOT, null, 20.0f);
        Object source = new Object();
        Hit hit = p.observe(BOT, source, 16.0f);
        assertNotNull(hit);
        assertEquals(4.0f, hit.damage());
        assertFalse(hit.blocked());
    }

    @Test
    void theSameSourceStillRecordedTheNextTicksIsNotReportedAgain() {
        HitPoller p = new HitPoller();
        p.observe(BOT, null, 20.0f);
        Object source = new Object();
        assertNotNull(p.observe(BOT, source, 16.0f));
        assertNull(p.observe(BOT, source, 16.0f));
        assertNull(p.observe(BOT, source, 16.5f), "regeneration is not a hit");
    }

    @Test
    void aNewSourceWithoutAHealthDropIsABlockedOrAbsorbedHitWithZeroDamage() {
        HitPoller p = new HitPoller();
        p.observe(BOT, null, 20.0f);
        Hit hit = p.observe(BOT, new Object(), 20.0f);
        assertNotNull(hit);
        assertEquals(0.0f, hit.damage());
        assertTrue(hit.blocked());
    }

    @Test
    void aSharedSourceObjectHitAgainIsCaughtByTheHealthDrop() {
        // a few environmental damage types are one shared object: the second rule catches the repeat
        HitPoller p = new HitPoller();
        Object fall = new Object();
        p.observe(BOT, null, 20.0f);
        assertNotNull(p.observe(BOT, fall, 17.0f));
        assertNull(p.observe(BOT, fall, 17.0f));
        Hit again = p.observe(BOT, fall, 15.0f);
        assertNotNull(again);
        assertEquals(2.0f, again.damage());
    }

    @Test
    void aRecordedSourceThatExpiredAndReturnsIsAHitAgain() {
        HitPoller p = new HitPoller();
        Object fall = new Object();
        p.observe(BOT, null, 20.0f);
        assertNotNull(p.observe(BOT, fall, 18.0f));
        assertNull(p.observe(BOT, null, 18.0f), "the entity forgets the source after a while");
        assertNotNull(p.observe(BOT, fall, 17.0f));
    }

    @Test
    void aHealthDropWithoutAnyRecordedSourceIsNotAHit() {
        HitPoller p = new HitPoller();
        p.observe(BOT, null, 20.0f);
        assertNull(p.observe(BOT, null, 10.0f), "a command or a plugin changed the health; there is nobody to name");
    }

    @Test
    void aSourceTheEventAlreadyDeliveredIsNeverReportedByThePoll() {
        HitPoller p = new HitPoller();
        p.observe(BOT, null, 20.0f);
        Object source = new Object();
        p.delivered(BOT, source);
        assertNull(p.observe(BOT, source, 16.0f));
        Object next = new Object();
        assertNotNull(p.observe(BOT, next, 12.0f), "the next hit is a different source and still counts");
    }

    @Test
    void botsAreIndependentAndForgettingStartsOver() {
        HitPoller p = new HitPoller();
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000002");
        p.observe(BOT, null, 20.0f);
        p.observe(other, null, 20.0f);
        assertNotNull(p.observe(BOT, new Object(), 15.0f));
        assertNull(p.observe(other, null, 20.0f));
        assertEquals(2, p.tracked());
        p.retainOnly(Set.of(other));
        assertEquals(1, p.tracked());
        assertNull(p.observe(BOT, new Object(), 10.0f), "a forgotten bot is a fresh baseline again");
        p.reset();
        assertEquals(0, p.tracked());
    }

    @Test
    void twoHitsInOneTickAreOneReportWithTheSummedDamage() {
        HitPoller p = new HitPoller();
        p.observe(BOT, null, 20.0f);
        Hit hit = p.observe(BOT, new Object(), 13.0f);
        assertEquals(7.0f, hit.damage());
    }
}

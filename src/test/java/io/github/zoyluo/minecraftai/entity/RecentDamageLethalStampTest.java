package io.github.zoyluo.minecraftai.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A1: the lethal damage remembered at ALLOW_DEATH is consumed at AFTER_DEATH of the SAME tick. A death that another mod vetoed leaves
 * a stamp behind; it must never be attributed to a later, unrelated death.
 */
class RecentDamageLethalStampTest {
    @AfterEach
    void reset() {
        RecentDamage.clear();
    }

    @Test
    void aLethalHitIsTakenInTheTickItWasStamped() {
        UUID victim = UUID.randomUUID();
        RecentDamage.rememberLethal(victim, 7.5F, 1000L);
        assertEquals(7.5F, RecentDamage.takeLethal(victim, 1000L));
        assertNull(RecentDamage.takeLethal(victim, 1000L), "the stamp is consumed by the first take");
    }

    @Test
    void aStampFromAnEarlierTickIsIgnoredAndDropped() {
        UUID victim = UUID.randomUUID();
        RecentDamage.rememberLethal(victim, 20.0F, 1000L); // a vetoed death (totem, a mod): no AFTER_DEATH followed
        assertNull(RecentDamage.takeLethal(victim, 1001L), "a stale stamp was attributed to a later death");
        assertNull(RecentDamage.takeLethal(victim, 1000L), "the stale stamp was not removed");
    }

    @Test
    void aFreshStampReplacesAStaleOne() {
        UUID victim = UUID.randomUUID();
        RecentDamage.rememberLethal(victim, 20.0F, 1000L);
        RecentDamage.rememberLethal(victim, 3.0F, 1500L);
        assertEquals(3.0F, RecentDamage.takeLethal(victim, 1500L));
    }

    @Test
    void noStampMeansNoAmount() {
        assertNull(RecentDamage.takeLethal(UUID.randomUUID(), 5L));
    }
}

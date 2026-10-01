package io.github.zoyluo.minecraftai.action;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Dynamic occupancy is retryable; factual terrain and hazard refusals are not. */
final class DigNavTest {
    @Test
    void onlyLiveOccupancyMakesADescendRefusalRetryable() {
        assertTrue(DigNav.isTransientDescentRefusal("occupied"));
        assertTrue(DigNav.isTransientDescentRefusal("entity_occupied"));
        assertFalse(DigNav.isTransientDescentRefusal("no_landing"));
        assertFalse(DigNav.isTransientDescentRefusal("hazard:lava"));
        assertFalse(DigNav.isTransientDescentRefusal(null));
    }
}

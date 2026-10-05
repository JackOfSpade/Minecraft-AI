package io.github.zoyluo.minecraftai.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Regression coverage for the persisted unbounded-yaw value seen in Moss's runtime state. */
final class LookActionYawTest {
    @Test
    void canonicalYawPreservesDirectionWithoutPersistingWholeTurns() {
        float canonical = LookAction.canonicalYaw(-28809.59F);

        assertEquals(-9.59F, canonical, 0.01F);
        assertTrue(canonical >= -180.0F && canonical < 180.0F);
    }

    @Test
    void canonicalYawHandlesBothWrapDirections() {
        assertEquals(-179.0F, LookAction.canonicalYaw(181.0F));
        assertEquals(179.0F, LookAction.canonicalYaw(-181.0F));
    }
}

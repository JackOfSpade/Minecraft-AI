package io.github.zoyluo.minecraftai.util;

import com.mojang.authlib.GameProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineProfileFactoryTest {

    @Test
    void everyDefaultSkinIndexIsReachableForTheSameName() {
        for (int index = 0; index < OfflineProfileFactory.DEFAULT_SKIN_COUNT; index++) {
            int expected = index;
            GameProfile profile = OfflineProfileFactory.create("SameBot", expected);
            assertEquals(expected, Math.floorMod(profile.id().hashCode(), OfflineProfileFactory.DEFAULT_SKIN_COUNT),
                    () -> "index " + expected + " did not round-trip through the profile's UUID");
        }
    }

    @Test
    void sameNameAndIndexAlwaysProducesTheSameProfile() {
        GameProfile first = OfflineProfileFactory.create("Guard", 5);
        GameProfile second = OfflineProfileFactory.create("Guard", 5);
        assertEquals(first.id(), second.id());
    }

    @Test
    void differentIndicesForTheSameNameProduceDifferentUuids() {
        GameProfile a = OfflineProfileFactory.create("Companion", 0);
        GameProfile b = OfflineProfileFactory.create("Companion", 9);
        assertTrue(!a.id().equals(b.id()));
    }

    @Test
    void negativeOrOutOfRangeIndicesWrapIntoRange() {
        GameProfile wrapped = OfflineProfileFactory.create("Wrap", -1);
        assertEquals(OfflineProfileFactory.DEFAULT_SKIN_COUNT - 1,
                Math.floorMod(wrapped.id().hashCode(), OfflineProfileFactory.DEFAULT_SKIN_COUNT));
    }

    @Test
    void randomSkinIndexStaysInRange() {
        for (int i = 0; i < 200; i++) {
            int index = OfflineProfileFactory.randomSkinIndex();
            assertTrue(index >= 0 && index < OfflineProfileFactory.DEFAULT_SKIN_COUNT);
        }
    }
}

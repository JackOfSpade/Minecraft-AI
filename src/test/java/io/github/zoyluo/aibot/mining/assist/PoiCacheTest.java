package io.github.zoyluo.aibot.mining.assist;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Design 6.6: "(dimension, 24x16x24 coarse cell, sorted top-6 block ids), shared across bots. TTL is 12000
 * ticks for stop and 6000 for continue." Mandatory never reads or writes this cache (enforced by {@code
 * PoiCoordinator}, not this class -- see that file's {@code mandatoryFlow}, which never touches {@link PoiCache}). */
class PoiCacheTest {
    private static final String OVERWORLD = "minecraft:overworld";

    @BeforeEach
    @AfterEach
    void reset() {
        PoiCache.clearAll();
    }

    @Test
    void aFreshKeyMissesAndAPutIsReadableImmediately() {
        String key = PoiCache.keyFor(OVERWORLD, 10, 64, 10, List.of("rail", "web"));
        assertNull(PoiCache.get(key, 100));
        PoiCache.put(key, true, "mineshaft", 100);
        PoiCache.Entry entry = PoiCache.get(key, 101);
        assertTrue(entry.stop());
        assertEquals("mineshaft", entry.label());
    }

    @Test
    void keyIsOrderIndependentOverTopIds() {
        String a = PoiCache.keyFor(OVERWORLD, 10, 64, 10, List.of("rail", "web", "cobble"));
        String b = PoiCache.keyFor(OVERWORLD, 10, 64, 10, List.of("cobble", "web", "rail"));
        assertEquals(a, b, "sorted top ids make discovery order irrelevant to the key");
    }

    @Test
    void keyUsesOnlyTheTopSixIdsEvenWhenMoreAreGiven() {
        String withSeven = PoiCache.keyFor(OVERWORLD, 0, 0, 0,
                List.of("a", "b", "c", "d", "e", "f", "zzz_extra"));
        String withSix = PoiCache.keyFor(OVERWORLD, 0, 0, 0, List.of("a", "b", "c", "d", "e", "f"));
        assertEquals(withSix, withSeven, "a 7th id beyond the top 6 must not change the key");
    }

    @Test
    void differentCoarseCellsGiveDifferentKeys() {
        String here = PoiCache.keyFor(OVERWORLD, 0, 64, 0, List.of("rail"));
        String farX = PoiCache.keyFor(OVERWORLD, PoiCache.CELL_XZ, 64, 0, List.of("rail"));
        String farY = PoiCache.keyFor(OVERWORLD, 0, 64 + PoiCache.CELL_Y, 0, List.of("rail"));
        assertNotEquals(here, farX);
        assertNotEquals(here, farY);
    }

    @Test
    void withinTheSameCoarseCellTheKeyIsIdentical() {
        String a = PoiCache.keyFor(OVERWORLD, 0, 0, 0, List.of("rail"));
        String b = PoiCache.keyFor(OVERWORLD, PoiCache.CELL_XZ - 1, PoiCache.CELL_Y - 1, PoiCache.CELL_XZ - 1,
                List.of("rail"));
        assertEquals(a, b, "the same 24x16x24 cell starting at the origin must give the same key for any "
                + "point inside it, corner included");
    }

    @Test
    void differentDimensionsNeverShareAKey() {
        String over = PoiCache.keyFor(OVERWORLD, 0, 64, 0, List.of("rail"));
        String nether = PoiCache.keyFor("minecraft:the_nether", 0, 64, 0, List.of("rail"));
        assertNotEquals(over, nether);
    }

    @Test
    void aStopEntryExpiresAtTheStopTtlNotBefore() {
        String key = PoiCache.keyFor(OVERWORLD, 0, 64, 0, List.of("spawner"));
        PoiCache.put(key, true, "dungeon", 1000);
        assertTrue(PoiCache.get(key, 1000 + PoiCache.STOP_TTL_TICKS) != null,
                "still live at exactly the TTL boundary");
        assertNull(PoiCache.get(key, 1000 + PoiCache.STOP_TTL_TICKS + 1));
    }

    @Test
    void aContinueEntryUsesTheShorterContinueTtl() {
        String key = PoiCache.keyFor(OVERWORLD, 0, 64, 0, List.of("cobble"));
        PoiCache.put(key, false, "", 1000);
        assertTrue(PoiCache.CONTINUE_TTL_TICKS < PoiCache.STOP_TTL_TICKS);
        assertNull(PoiCache.get(key, 1000 + PoiCache.CONTINUE_TTL_TICKS + 1));
    }

    @Test
    void anExpiredEntryIsDroppedOnRead() {
        String key = PoiCache.keyFor(OVERWORLD, 0, 64, 0, List.of("cobble"));
        PoiCache.put(key, false, "", 1000);
        assertEquals(1, PoiCache.size());
        assertNull(PoiCache.get(key, 1000 + PoiCache.CONTINUE_TTL_TICKS + 5000));
        assertEquals(0, PoiCache.size(), "a read past expiry drops the stale entry");
    }

    @Test
    void aLaterPutOverwritesAnEarlierOneForTheSameKey() {
        String key = PoiCache.keyFor(OVERWORLD, 0, 64, 0, List.of("rail"));
        PoiCache.put(key, false, "", 100);
        PoiCache.put(key, true, "mineshaft", 200);
        PoiCache.Entry entry = PoiCache.get(key, 201);
        assertTrue(entry.stop());
        assertEquals("mineshaft", entry.label());
    }

    @Test
    void clearAllDropsEveryEntry() {
        String key = PoiCache.keyFor(OVERWORLD, 0, 64, 0, List.of("rail"));
        PoiCache.put(key, true, "mineshaft", 100);
        PoiCache.clearAll();
        assertEquals(0, PoiCache.size());
        assertNull(PoiCache.get(key, 101));
    }

    @Test
    void anEmptyOrNullTopIdsListStillProducesAUsableKey() {
        String a = PoiCache.keyFor(OVERWORLD, 0, 64, 0, List.of());
        String b = PoiCache.keyFor(OVERWORLD, 0, 64, 0, null);
        assertEquals(a, b);
        assertFalse(a.isBlank());
    }
}

package io.github.zoyluo.minecraftai.memory;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers miningcore-bug-001: once more than {@code PLACE_INJECT_LIMIT} places have been
 * recorded, {@link BotMemory#inject()}'s "Known places:" block must show the most recently
 * inserted places (mirroring how the "Remembered facts:" block already keeps the most recent
 * facts), not permanently freeze on the first ones ever recorded.
 *
 * <p>Inserts places directly into the private {@code places} map via reflection rather than
 * through {@link BotMemory#markPlace}, since that method requires a live {@code ServerWorld}
 * unavailable to this unit test; {@link BotMemory.Place} itself is a plain record with no
 * Minecraft bootstrap dependency.
 */
final class BotMemoryRecentPlacesInjectionTest {
    @Test
    void injectShowsTheMostRecentlyInsertedPlacesOnceOverTheCap() throws ReflectiveOperationException {
        BotMemory memory = new BotMemory();
        Map<String, BotMemory.Place> places = placesOf(memory);

        // PLACE_INJECT_LIMIT is 10; insert 12 in order so place_1/place_2 are the oldest and
        // must be the ones dropped, not place_11/place_12 (the bug: the loop broke after the
        // first 10 insertion-order entries instead of skipping to the last 10).
        for (int index = 1; index <= 12; index++) {
            places.put("place_" + index, new BotMemory.Place("minecraft:overworld", new BlockPos(index, 64, 0)));
        }

        String injected = memory.inject();

        assertFalse(injected.contains("place_1 ="), "oldest place must be dropped once over the cap");
        assertFalse(injected.contains("place_2 ="), "second-oldest place must be dropped once over the cap");
        for (int index = 3; index <= 12; index++) {
            assertTrue(injected.contains("place_" + index + " ="), "place_" + index + " should still be shown");
        }
    }

    @Test
    void injectShowsEveryPlaceWhenAtOrUnderTheCap() throws ReflectiveOperationException {
        BotMemory memory = new BotMemory();
        Map<String, BotMemory.Place> places = placesOf(memory);

        for (int index = 1; index <= 10; index++) {
            places.put("place_" + index, new BotMemory.Place("minecraft:overworld", new BlockPos(index, 64, 0)));
        }

        String injected = memory.inject();

        for (int index = 1; index <= 10; index++) {
            assertTrue(injected.contains("place_" + index + " ="), "place_" + index + " should be shown when at the cap");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, BotMemory.Place> placesOf(BotMemory memory) throws ReflectiveOperationException {
        Field field = BotMemory.class.getDeclaredField("places");
        field.setAccessible(true);
        return (Map<String, BotMemory.Place>) field.get(memory);
    }
}

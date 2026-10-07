package io.github.zoyluo.minecraftai.task;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The way a bot in the dark digs first is decided from what it has seen: the light of the brightest open cell in each direction
 * against the light of its own cell. With nothing brighter in sight there is no preference at all (the caller then digs the way it
 * always did), and lava in a direction is never followed.
 */
final class DigOutBearingTest {
    private static Map<Direction, DigOutBearing.Seen> seen(Object... directionLightDistance) {
        Map<Direction, DigOutBearing.Seen> seen = new EnumMap<>(Direction.class);
        for (int i = 0; i < directionLightDistance.length; i += 3) {
            seen.put((Direction) directionLightDistance[i],
                    new DigOutBearing.Seen((Integer) directionLightDistance[i + 1], (Integer) directionLightDistance[i + 2]));
        }
        return seen;
    }

    @Test
    void nothingSeenMeansNoPreference() {
        assertNull(DigOutBearing.brighter(Direction.SOUTH, 0, seen(), Set.of()));
    }

    @Test
    void aDirectionIsPreferredOnlyWhenItIsBrighterThanTheCellTheBotStandsIn() {
        Map<Direction, DigOutBearing.Seen> seen = seen(Direction.EAST, 5, 9, Direction.WEST, 3, 4);
        assertNull(DigOutBearing.brighter(Direction.SOUTH, 5, seen, Set.of()), "as bright as here is not brighter");
        assertEquals(Direction.EAST, DigOutBearing.brighter(Direction.SOUTH, 4, seen, Set.of()));
    }

    @Test
    void theBrightestDirectionWins() {
        Map<Direction, DigOutBearing.Seen> seen = seen(Direction.NORTH, 7, 4, Direction.EAST, 14, 100, Direction.WEST, 9, 1);
        assertEquals(Direction.EAST, DigOutBearing.brighter(Direction.SOUTH, 0, seen, Set.of()));
    }

    @Test
    void equalLightGoesToTheNearerCellAndThenToTheCurrentHeading() {
        assertEquals(Direction.WEST, DigOutBearing.brighter(Direction.SOUTH, 0,
                seen(Direction.EAST, 9, 25, Direction.WEST, 9, 16), Set.of()));
        assertEquals(Direction.WEST, DigOutBearing.brighter(Direction.WEST, 0,
                seen(Direction.EAST, 9, 16, Direction.WEST, 9, 16), Set.of()), "the heading already held keeps it");
        assertEquals(Direction.EAST, DigOutBearing.brighter(Direction.EAST, 0,
                seen(Direction.EAST, 9, 16, Direction.WEST, 9, 16), Set.of()));
    }

    @Test
    void aDirectionWithLavaInSightIsNeverFollowedWhateverItShows() {
        Map<Direction, DigOutBearing.Seen> seen = seen(Direction.EAST, 15, 4, Direction.WEST, 6, 9);
        Set<Direction> lava = EnumSet.of(Direction.EAST);
        assertEquals(Direction.WEST, DigOutBearing.brighter(Direction.SOUTH, 0, seen, lava));
        assertNull(DigOutBearing.brighter(Direction.SOUTH, 0, seen(Direction.EAST, 15, 4), lava),
                "the glow of lava alone is no way out");
    }
}

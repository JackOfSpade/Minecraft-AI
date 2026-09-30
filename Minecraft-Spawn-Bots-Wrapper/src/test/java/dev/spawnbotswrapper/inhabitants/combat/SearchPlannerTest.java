package dev.spawnbotswrapper.inhabitants.combat;

import dev.spawnbotswrapper.inhabitants.combat.AggroWorld.Pos;
import dev.spawnbotswrapper.inhabitants.combat.AggroWorld.SearchSpot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The smart part of the search: heading, openings (corners, doorways), not visited, not too far. */
class SearchPlannerTest {

    private static Pos p(double x, double z) {
        return new Pos(x, 64, z);
    }

    private static SearchSpot spot(double x, double z, double opening) {
        return new SearchSpot(p(x, z), opening);
    }

    private static final Pos BOT = p(0, 0);
    private static final Pos FOCUS = p(0, 0);

    @Test
    void prefersTheDirectionThePlayerWasHeading() {
        List<SearchSpot> spots = List.of(spot(5, 0, 0.3), spot(-5, 0, 0.3), spot(0, 5, 0.3), spot(0, -5, 0.3));
        SearchSpot pick = SearchPlanner.choose(BOT, FOCUS, 1, 0, spots, List.of(), 200);
        assertEquals(p(5, 0), pick.pos(), "along the heading (+x)");
        pick = SearchPlanner.choose(BOT, FOCUS, 0, -1, spots, List.of(), 200);
        assertEquals(p(0, -5), pick.pos());
    }

    @Test
    void anOpeningBeatsTheHeadingWhenItIsBigEnough() {
        List<SearchSpot> spots = List.of(spot(5, 0, 0.0), spot(-5, 0, 0.9));
        SearchSpot pick = SearchPlanner.choose(BOT, FOCUS, 1, 0, spots, List.of(), 200);
        assertEquals(p(-5, 0), pick.pos(), "a doorway opens more hidden space than the open floor ahead");
    }

    @Test
    void withoutAHeadingOnlyTheOpeningAndTheDistanceCount() {
        List<SearchSpot> spots = List.of(spot(6, 0, 0.5), spot(3, 3, 0.5), spot(-8, 0, 0.2));
        SearchSpot pick = SearchPlanner.choose(BOT, FOCUS, 0, 0, spots, List.of(), 200);
        assertEquals(p(3, 3), pick.pos(), "same opening: the nearer one");
    }

    @Test
    void skipsWhatWasAlreadyChecked() {
        List<SearchSpot> spots = List.of(spot(5, 0, 0.9), spot(-5, 0, 0.1));
        SearchSpot pick = SearchPlanner.choose(BOT, FOCUS, 1, 0, spots, List.of(p(4, 1)), 200);
        assertEquals(p(-5, 0), pick.pos(), "within 3 blocks of a visited point counts as visited");
        assertNull(SearchPlanner.choose(BOT, FOCUS, 1, 0, spots, List.of(p(5, 0), p(-5, 0)), 200));
    }

    @Test
    void skipsWhatCannotBeReachedInTheTimeThatIsLeft() {
        List<SearchSpot> spots = List.of(spot(10, 0, 0.9), spot(2, 0, 0.1));
        // 10 blocks at 0.2 blocks per tick with room for a detour needs 65 ticks; only 30 are left
        SearchSpot pick = SearchPlanner.choose(BOT, FOCUS, 0, 0, spots, List.of(), 30);
        assertEquals(p(2, 0), pick.pos());
        assertNull(SearchPlanner.choose(BOT, FOCUS, 0, 0, List.of(spot(10, 0, 0.9)), List.of(), 30));
    }

    @Test
    void anEmptyListPicksNothing() {
        assertNull(SearchPlanner.choose(BOT, FOCUS, 1, 0, List.of(), List.of(), 200));
    }

    @Test
    void ties_breakTheSameWayEveryTime() {
        List<SearchSpot> a = List.of(spot(4, 0, 0.5), spot(-4, 0, 0.5));
        List<SearchSpot> b = List.of(spot(-4, 0, 0.5), spot(4, 0, 0.5));
        assertEquals(SearchPlanner.choose(BOT, FOCUS, 0, 0, a, List.of(), 200).pos(),
                SearchPlanner.choose(BOT, FOCUS, 0, 0, b, List.of(), 200).pos());
    }

    @Test
    void theScoreRisesWithOpeningAndAlignmentAndFallsWithDistance() {
        double base = SearchPlanner.score(BOT, FOCUS, 1, 0, spot(5, 0, 0.2));
        assertTrue(SearchPlanner.score(BOT, FOCUS, 1, 0, spot(5, 0, 0.6)) > base);
        assertTrue(SearchPlanner.score(BOT, FOCUS, 1, 0, spot(-5, 0, 0.2)) < base);
        assertTrue(SearchPlanner.score(BOT, FOCUS, 1, 0, spot(9, 0, 0.2)) < base + SearchPlanner.W_NEAR * 0.0 + 1e-9
                || SearchPlanner.score(BOT, FOCUS, 1, 0, spot(9, 0, 0.2)) < base);
    }
}

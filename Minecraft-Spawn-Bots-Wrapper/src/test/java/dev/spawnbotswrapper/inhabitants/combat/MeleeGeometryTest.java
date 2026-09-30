package dev.spawnbotswrapper.inhabitants.combat;

import dev.spawnbotswrapper.inhabitants.combat.MeleeGeometry.Box;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The melee legality geometry: nearest point, ray entry, reach and sweep, with no Minecraft involved. */
class MeleeGeometryTest {
    /** A player-sized box (0.6 x 1.8) whose feet centre is at (x, 0, 0). */
    private static Box player(double x) {
        return new Box(x - 0.3, 0.0, -0.3, x + 0.3, 1.8, 0.3);
    }

    private static final double EYE_Y = 1.62;

    @Test
    void theNearestPointIsTheEyeClampedIntoTheBox() {
        assertArrayEquals(new double[] {1.7, 1.62, 0.0}, MeleeGeometry.nearestPoint(0.0, EYE_Y, 0.0, player(2.0)), 1e-9);
        // Eye above the box: the top face.
        assertArrayEquals(new double[] {2.0, 1.8, 0.0}, MeleeGeometry.nearestPoint(2.0, 3.0, 0.0, player(2.0)), 1e-9);
    }

    @Test
    void distanceToTheBoxIsZeroInsideAndCentreDistanceMinusHalfWidthBesideIt() {
        assertEquals(0.0, MeleeGeometry.distanceToBox(2.0, 1.0, 0.0, player(2.0)), 1e-9);
        assertEquals(3.5, MeleeGeometry.distanceToBox(0.0, EYE_Y, 0.0, player(3.8)), 1e-9);
        assertEquals(1.7, MeleeGeometry.distanceToBox(0.0, EYE_Y, 0.0, player(2.0)), 1e-9);
    }

    @Test
    void aRayEntryIsReachableWithinTheRangePlusTheDocumentedTolerance() {
        assertTrue(MeleeGeometry.withinReach(3.0, 3.0));
        assertTrue(MeleeGeometry.withinReach(3.2, 3.0));
        assertFalse(MeleeGeometry.withinReach(3.25, 3.0));
        // 3.8 blocks centre to centre is 3.5 to the box: beyond a 3.0 range plus the tolerance (the vanilla range test itself is covered by MeleeLegalityMcCases).
        assertFalse(MeleeGeometry.withinReach(MeleeGeometry.distanceToBox(0.0, EYE_Y, 0.0, player(3.8)),
                3.0));
        // 2.2 blocks centre to centre (the smoke test through a wall) is within reach: the wall is what stops it.
        assertTrue(MeleeGeometry.withinReach(MeleeGeometry.distanceToBox(0.0, EYE_Y, 0.0, player(2.2)),
                3.0));
        assertFalse(MeleeGeometry.withinReach(-1.0, 3.0), "no entry is never reachable");
    }

    @Test
    void anAttributeChangedReachIsHonoured() {
        assertTrue(MeleeGeometry.withinReach(4.5, 4.5));
        assertFalse(MeleeGeometry.withinReach(2.5, 2.0));
    }

    @Test
    void theRayEntersTheBoxAtItsNearFace() {
        Box b = player(2.0);
        assertEquals(1.7, MeleeGeometry.entryDistance(0.0, EYE_Y, 0.0, 2.0, EYE_Y, 0.0, b), 1e-9);
        // toward the centre of the box from below eye level: enters through the near face, a bit farther along the ray
        double d = MeleeGeometry.entryDistance(0.0, EYE_Y, 0.0, 2.0, 0.9, 0.0, b);
        assertTrue(d > 1.7 && d < 1.85, "was " + d);
    }

    @Test
    void aRayThatMissesTheBoxHasNoEntry() {
        assertEquals(-1.0, MeleeGeometry.entryDistance(0.0, EYE_Y, 0.0, 2.0, EYE_Y, 5.0, player(2.0)));
        assertEquals(-1.0, MeleeGeometry.entryDistance(0.0, EYE_Y, 0.0, -2.0, EYE_Y, 0.0, player(2.0)), "away from it");
        assertEquals(-1.0, MeleeGeometry.entryDistance(0.0, 3.0, 0.0, 2.0, 3.0, 0.0, player(2.0)), "over its head");
    }

    @Test
    void anEyeInsideTheBoxEntersItAtOnce() {
        assertEquals(0.0, MeleeGeometry.entryDistance(2.0, 1.0, 0.0, 5.0, 1.0, 0.0, player(2.0)));
    }

    @Test
    void aimPointsStartWithTheNearestPointAndStayInsideTheBox() {
        Box b = player(2.0);
        double[][] pts = MeleeGeometry.aimPoints(0.0, EYE_Y, 0.0, b);
        assertEquals(4, pts.length);
        assertArrayEquals(MeleeGeometry.nearestPoint(0.0, EYE_Y, 0.0, b), pts[0], 1e-9);
        for (double[] p : pts) {
            assertTrue(b.contains(p[0], p[1], p[2]), "outside the box: " + java.util.Arrays.toString(p));
        }
    }

    @Test
    void aSweepVictimIsWithinOneBlockOfThePrimaryAndCloserThanThreeToTheAttacker() {
        Box primary = player(2.0);
        assertTrue(MeleeGeometry.isSweepVictim(primary, new Box(2.5, 0, 0.9, 3.1, 1.8, 1.5), 8.0));
        assertFalse(MeleeGeometry.isSweepVictim(primary, new Box(2.5, 0, 0.9, 3.1, 1.8, 1.5), 9.5), "too far from the attacker");
        assertFalse(MeleeGeometry.isSweepVictim(primary, new Box(2.5, 0, 2.5, 3.1, 1.8, 3.1), 4.0), "too far from the primary");
    }

    @Test
    void theBoxTestsAreConsistent() {
        Box b = new Box(0, 0, 0, 1, 1, 1);
        assertTrue(b.intersects(new Box(0.5, 0.5, 0.5, 2, 2, 2)));
        assertFalse(b.intersects(new Box(1, 0, 0, 2, 1, 1)), "touching faces do not intersect");
        assertTrue(b.contains(1, 1, 1));
        assertFalse(b.contains(1.01, 0.5, 0.5));
    }
}

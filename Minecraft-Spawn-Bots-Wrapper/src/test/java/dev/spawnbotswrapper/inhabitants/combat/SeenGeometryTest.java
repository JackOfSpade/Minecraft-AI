package dev.spawnbotswrapper.inhabitants.combat;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Has the player actually seen the bot?": the view cone, the range, invisibility and the lazily asked occlusion. */
class SeenGeometryTest {
    private static final double[] EYE = {0, 1.62, 0};
    private static final double[] LOOK_SOUTH = {0, 0, 1};
    private static final double HALF = 70.0;

    private static double[] at(double x, double y, double z) {
        return new double[]{x, y, z};
    }

    /** A point {@code distance} away from the eye, {@code degrees} to the left of straight ahead, at eye height. */
    private static double[] atAngle(double degrees, double distance) {
        double r = Math.toRadians(degrees);
        return at(EYE[0] + Math.sin(r) * distance, EYE[1], EYE[2] + Math.cos(r) * distance);
    }

    private static boolean sees(double[] botEye, double[] botBody, boolean invisible, boolean clear, AtomicInteger rays) {
        return SeenGeometry.sees(EYE, LOOK_SOUTH, botEye, botBody, HALF, invisible, () -> {
            rays.incrementAndGet();
            return clear;
        });
    }

    @Test
    void aBotStraightAheadAndUnobstructedIsSeen() {
        AtomicInteger rays = new AtomicInteger();
        assertTrue(sees(at(0, 1.62, 10), at(0, 1.0, 10), false, true, rays));
        assertEquals(1, rays.get());
    }

    @Test
    void aBotBehindThePlayerIsNeverSeenAndNoRayIsCast() {
        AtomicInteger rays = new AtomicInteger();
        assertFalse(sees(at(0, 1.62, -10), at(0, 1.0, -10), false, true, rays));
        assertEquals(0, rays.get(), "the expensive occlusion test is only asked when range and cone say yes");
    }

    @Test
    void theConeIsSeventyDegreesEachSideOfTheLookDirection() {
        AtomicInteger rays = new AtomicInteger();
        assertTrue(SeenGeometry.inCone(EYE, LOOK_SOUTH, atAngle(69, 20), HALF));
        assertTrue(SeenGeometry.inCone(EYE, LOOK_SOUTH, atAngle(-69, 20), HALF));
        assertFalse(SeenGeometry.inCone(EYE, LOOK_SOUTH, atAngle(71, 20), HALF));
        assertFalse(SeenGeometry.inCone(EYE, LOOK_SOUTH, atAngle(-71, 20), HALF));
        assertTrue(sees(atAngle(60, 20), atAngle(60, 20), false, true, rays));
        assertFalse(sees(atAngle(90, 20), atAngle(90, 20), false, true, rays), "at the side, outside the cone");
    }

    @Test
    void theBodyCentreInsideTheConeIsEnoughEvenWhenTheEyesAreNot() {
        AtomicInteger rays = new AtomicInteger();
        // The eyes are just outside the cone, the body centre (lower) just inside: still seen, like a body peeking into view.
        double[] eyes = at(20 * Math.sin(Math.toRadians(69.9)), 1.62 + 3.0, 20 * Math.cos(Math.toRadians(69.9)));
        double[] body = at(20 * Math.sin(Math.toRadians(60)), 1.62, 20 * Math.cos(Math.toRadians(60)));
        assertFalse(SeenGeometry.inCone(EYE, LOOK_SOUTH, at(eyes[0], eyes[1] + 60, eyes[2]), HALF), "sanity: a point far above is outside");
        assertTrue(sees(eyes, body, false, true, rays));
    }

    @Test
    void anObstructedViewIsNotSeeing() {
        AtomicInteger rays = new AtomicInteger();
        assertFalse(sees(at(0, 1.62, 10), at(0, 1.0, 10), false, false, rays));
        assertEquals(1, rays.get(), "the rays were asked, and refused");
    }

    @Test
    void anInvisibleBotIsNeverSeen() {
        AtomicInteger rays = new AtomicInteger();
        assertFalse(sees(at(0, 1.62, 10), at(0, 1.0, 10), true, true, rays));
        assertEquals(0, rays.get());
    }

    @Test
    void beyondVanillaSightRangeIsNotSeen() {
        AtomicInteger rays = new AtomicInteger();
        assertTrue(sees(at(0, 1.62, 127), at(0, 1.0, 127), false, true, rays));
        assertFalse(sees(at(0, 1.62, 129), at(0, 1.0, 129), false, true, rays));
        assertEquals(1, rays.get(), "out of range: no ray");
    }

    @Test
    void lookingStraightUpOrDownWorksToo() {
        AtomicInteger rays = new AtomicInteger();
        double[] up = {0, 1, 0};
        assertTrue(SeenGeometry.sees(EYE, up, at(0, 30, 0), at(0, 29.5, 0), HALF, false, () -> {
            rays.incrementAndGet();
            return true;
        }));
        assertFalse(SeenGeometry.sees(EYE, up, at(0, -30, 0), at(0, -30.5, 0), HALF, false, () -> true));
    }

    @Test
    void standingInsideEachOtherCountsAsInTheCone() {
        assertTrue(SeenGeometry.inCone(EYE, LOOK_SOUTH, EYE.clone(), HALF));
    }
}

package io.github.zoyluo.minecraftai.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** The straight walk-over pickup only starts along a sampled, checked corridor. */
final class HarvestCoreCorridorTest {
    private static final Path SOURCE = Path.of("src/main/java/io/github/zoyluo/minecraftai/action/HarvestCore.java");

    @Test
    void samplesIncludeBothEndsAndNeverSkipMoreThanTheStep() {
        Vec3 from = new Vec3(0.5D, 64.0D, 0.5D);
        Vec3 to = new Vec3(6.5D, 64.0D, 3.5D);
        List<Vec3> samples = HarvestCore.corridorSamples(from, to, 0.5D);
        assertEquals(from, samples.get(0));
        assertEquals(to, samples.get(samples.size() - 1));
        for (int i = 1; i < samples.size(); i++) {
            assertTrue(samples.get(i - 1).distanceTo(samples.get(i)) <= 0.5D + 1.0E-9D,
                    "a cell of the corridor could be skipped between samples " + (i - 1) + " and " + i);
        }
        assertTrue(samples.size() >= 14, "a 6.7 block walk needs at least 14 half-block samples");
    }

    @Test
    void aZeroLengthWalkStillChecksTheSpotItStandsOn() {
        Vec3 spot = new Vec3(2.5D, 70.0D, 2.5D);
        List<Vec3> samples = HarvestCore.corridorSamples(spot, spot, 0.5D);
        assertFalse(samples.isEmpty());
        assertEquals(spot, samples.get(0));
    }

    @Test
    void walkOverDropsSkipsADropWhoseCorridorIsNotSafe() throws IOException {
        String source = Files.readString(SOURCE);
        int walk = source.indexOf("public static boolean walkOverDrops");
        int end = source.indexOf("private static final double CORRIDOR_STEP", walk);
        String body = source.substring(walk, end);
        assertTrue(body.contains("isSafeWalkCorridor(bot, target.position())"));
        assertTrue(body.indexOf("isSafeWalkCorridor") < body.indexOf("startWalkTo"),
                "the corridor is checked before any walk starts");
        assertTrue(body.contains("continue;"), "an unsafe drop is skipped, the next one is tried");

        int corridor = source.indexOf("public static boolean isSafeWalkCorridor");
        String check = source.substring(corridor, source.indexOf("public static void sweepPickup(", corridor));
        assertTrue(check.contains("Standability.isDangerous(state)"), "fire / lava / cactus / magma fail the corridor");
        assertTrue(check.contains("getFluidState().isEmpty()"), "water and lava cells fail the corridor");
        assertTrue(check.contains("noCollision(bot, box.expandTowards(0.0D, -CORRIDOR_MAX_FALL"),
                "a cliff (no floor within a harmless fall) fails the corridor");
        assertTrue(check.contains("int floorLimit = belowY - (int) Math.ceil(CORRIDOR_MAX_FALL) - 1;")
                        && check.indexOf("for (int y = belowY; y >= floorLimit; y--)") > check.indexOf("noCollision(bot, box.expandTowards"),
                "the fall column down to the floor is scanned for lava, fire and fluid, not only the cell under the feet");
    }
}

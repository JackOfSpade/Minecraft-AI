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
        assertTrue(check.contains("canObserveWalkCorridorBody(bot, box)")
                        && check.indexOf("canObserveWalkCorridorBody(bot, box)") < check.indexOf("world.noCollision(bot, body)"),
                "the pickup corridor must prove its body envelope before raw collision reads");
        assertTrue(check.contains("ObservableWorldQuery.canObserveCell(bot, cell)"),
                "unoccupied corridor cells must have state-free ray evidence before hazard reads");
        assertTrue(check.contains("Standability.isDangerous(state)"), "fire / lava / cactus / magma fail the corridor");
        assertTrue(check.contains("getFluidState().isEmpty()"), "water and lava cells fail the corridor");
        assertTrue(check.contains("int floorLimit = belowY - (int) Math.ceil(CORRIDOR_MAX_FALL) - 1;")
                        && check.contains("if (!canObserveWalkCorridorCell(bot, cell))")
                        && check.contains("if (!floorFound)"),
                "the fall column proves and scans each cell down to a floor, rejecting an unobserved cliff without reading below that floor");
    }
}

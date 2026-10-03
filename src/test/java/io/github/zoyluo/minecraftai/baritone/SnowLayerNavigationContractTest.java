package io.github.zoyluo.minecraftai.baritone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Locks the snow-layer coordinate convention shared by the observation fence and Baritone.
 *
 * <p>A player can physically stand partway through a snow layer. The bot's
 * integer navigation coordinate must nevertheless be the air cell above that partial support;
 * otherwise the observed fence can select a snow-top stance while Baritone starts from the
 * occupied snow cell and immediately reports an unreachable goal.</p>
 */
final class SnowLayerNavigationContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai/baritone");
    private static final Path PATCHES = Path.of("tools/baritone/patches");

    @Test
    void playerContextAndObservationFenceNormalizeSnowToTheSameAirCell() throws IOException {
        String context = Files.readString(MAIN.resolve("ServerPlayerContext.java"));
        String playerFeet = methodBody(context, "public BetterBlockPos playerFeet()");
        assertTrue(context.contains("import net.minecraft.world.level.block.SnowLayerBlock;"),
                "the server context needs Minecraft's snow-layer type");
        assertTrue(playerFeet.contains("BlockState footing = blockStateAt(feet)")
                        && playerFeet.contains("footing.getBlock() instanceof SnowLayerBlock")
                        && playerFeet.contains("return feet.above()"),
                "a bot physically occupying any observed snow layer must path from its air cell above the support");
        assertFalse(context.contains("SnowLayerBlock.LAYERS"),
                "all observed snow layers share one partial-support model; navigation must not split on layer depth");
        assertFalse(playerFeet.contains("getBlockState("),
                "player-feet normalization must keep using the immutable observation fence, not a hidden live-world read");

        String fence = Files.readString(MAIN.resolve("ObservedNavigationFence.java"));
        String navigationFeet = methodBody(fence, "private static BlockPos navigationFeet(");
        assertTrue(fence.contains("import net.minecraft.world.level.block.SnowLayerBlock;"),
                "the observation fence must understand the same partial support");
        assertTrue(navigationFeet.contains("occupied.getBlock() instanceof SnowLayerBlock")
                        && navigationFeet.contains("raw.above()"),
                "the observed graph and Baritone start coordinate must agree on a snow-top stance");
        assertFalse(fence.contains("SnowLayerBlock.LAYERS"),
                "the observed graph must not leave shallow snow in a different coordinate system");
        assertTrue(fence.contains("BlockPos feet = navigationFeet(bot)")
                        && fence.contains("observeVisibleCorridors(bot, feet, hop"),
                "directional pursuit must prove the elevation-changing leg from the normalized snow stance");
    }

    @Test
    void patchedBaritoneTreatsEveryObservedSnowLayerAsItsOwnSupport() throws IOException {
        String movementHelperPatch = patchFor("src/main/java/baritone/pathing/movement/MovementHelper.java");
        int stateSnow = movementHelperPatch.indexOf("block instanceof SnowLayerBlock");
        int yes = movementHelperPatch.indexOf("return YES", stateSnow);
        int water = movementHelperPatch.indexOf("if (isWater(state))", stateSnow);
        assertTrue(stateSnow >= 0 && yes > stateSnow && water > yes,
                "an observed snow collision surface must be walkable support before ordinary water handling");
        assertFalse(movementHelperPatch.contains("SnowLayerBlock.LAYERS"),
                "the Baritone support rule must match the fence for every observed snow layer");
        assertFalse(movementHelperPatch.substring(stateSnow, water).contains("canWalkOn(bsi, x, y - 1, z)"),
                "snow support must not infer an unseen block below an already observed collision layer");
    }

    private static String patchFor(String generatedPath) throws IOException {
        try (Stream<Path> paths = Files.list(PATCHES)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".patch"))
                    .sorted()
                    .map(SnowLayerNavigationContractTest::readUnchecked)
                    .filter(source -> source.contains("diff --git a/" + generatedPath))
                    .reduce("", (left, right) -> left + "\n" + right);
        }
    }

    private static String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read " + path, exception);
        }
    }

    private static String methodBody(String source, String signature) {
        int signatureAt = source.indexOf(signature);
        assertTrue(signatureAt >= 0, () -> "missing method signature: " + signature);
        int open = source.indexOf('{', signatureAt);
        assertTrue(open >= 0, () -> "missing method body: " + signature);
        int depth = 0;
        for (int at = open; at < source.length(); at++) {
            char current = source.charAt(at);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(open, at + 1);
            }
        }
        throw new AssertionError("unterminated method body: " + signature);
    }
}

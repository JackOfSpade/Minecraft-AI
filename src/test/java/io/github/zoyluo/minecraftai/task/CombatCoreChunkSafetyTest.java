package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class CombatCoreChunkSafetyTest {
    private static final Path CORE = Path.of("src/main/java/io/github/zoyluo/minecraftai/task/CombatCore.java");

    @Test
    void colliderRayRejectsAnUnloadedIntermediateChunkBeforeAnyClipCanReadIt() {
        Set<String> loaded = Set.of("0,0", "2,0", "3,0");

        assertFalse(CombatCore.colliderRayOnlyUsesLoadedChunks(
                new Vec3(0.5D, 64.5D, 0.5D), new Vec3(48.5D, 64.5D, 0.5D),
                (chunkX, chunkZ) -> loaded.contains(chunkX + "," + chunkZ)));
    }

    @Test
    void colliderRayChecksTheStartAdjacentChunkWhenTheEyeBeginsOnItsBoundary() {
        assertFalse(CombatCore.colliderRayOnlyUsesLoadedChunks(
                new Vec3(16.0D, 64.5D, 0.5D), new Vec3(17.0D, 64.5D, 0.5D),
                (chunkX, chunkZ) -> chunkX == 1 && chunkZ == 0),
                "the vanilla traversal begins in the cell just before a boundary-start ray");
    }

    @Test
    void colliderRayChecksTheTerminalChunkWhenTheTargetEyeLandsOnItsBoundary() {
        assertFalse(CombatCore.colliderRayOnlyUsesLoadedChunks(
                new Vec3(0.5D, 64.5D, 0.5D), new Vec3(16.0D, 64.5D, 0.5D),
                (chunkX, chunkZ) -> chunkX == 0 && chunkZ == 0),
                "a boundary endpoint belongs to the adjacent chunk even if the traversal stops before entering it");
    }

    @Test
    void colliderRayFailsClosedOutsideTheBoundedTraversalWindow() {
        AtomicInteger checks = new AtomicInteger();

        assertFalse(CombatCore.colliderRayOnlyUsesLoadedChunks(
                new Vec3(0.5D, 64.5D, 0.5D), new Vec3(128.6D, 64.5D, 0.5D),
                (chunkX, chunkZ) -> {
                    checks.incrementAndGet();
                    return true;
                }));
        assertTrue(checks.get() == 0, "an out-of-range ray must not begin a partial loaded-chunk proof");
    }

    @Test
    void colliderRayFailsClosedForMalformedCoordinates() {
        assertFalse(CombatCore.colliderRayOnlyUsesLoadedChunks(
                new Vec3(Double.POSITIVE_INFINITY, 64.5D, 0.5D), new Vec3(1.5D, 64.5D, 0.5D),
                (chunkX, chunkZ) -> true));
    }

    @Test
    void colliderRayAcceptsACompleteLoadedPath() {
        assertTrue(CombatCore.colliderRayOnlyUsesLoadedChunks(
                new Vec3(-0.5D, 64.5D, -0.5D), new Vec3(48.5D, 64.5D, 48.5D),
                (chunkX, chunkZ) -> true));
    }

    @Test
    void clipIsReachedOnlyAfterTheLoadedChunkGuard() throws IOException {
        String source = Files.readString(CORE);
        int guard = source.indexOf("if (!colliderRayOnlyUsesLoadedChunks(");
        int clip = source.indexOf("bot.level().clip(new ClipContext(", guard);
        int helperStart = source.indexOf("static boolean colliderRayOnlyUsesLoadedChunks(");
        int helperEnd = source.indexOf("\n    private static boolean isFinite", helperStart);
        String helper = source.substring(helperStart, helperEnd);

        assertTrue(guard >= 0 && clip > guard,
                "the collider clip must stay behind its complete, non-loading chunk-path guard");
        assertTrue(source.contains("mob.level() != bot.level()")
                        && helper.contains("BlockGetter.traverseBlocks(start, end, ray,")
                        && source.contains("bot.level().getChunkSource()::hasChunk"),
                "combat LOS must reject another level and check its exact vanilla block path through non-loading chunk lookups");
        assertFalse(helper.contains("RayGrid") || helper.contains("getBlockState") || helper.contains("getChunk("),
                "the guard must use only vanilla traversal plus non-loading chunk-presence checks");
    }
}

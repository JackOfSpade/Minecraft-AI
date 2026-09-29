package io.github.zoyluo.minecraftai.gametest;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Force-loading of the extra chunks a wide fixture (a lake or an island wider than one test structure) needs
 * so its entities keep ticking, without leaking them and without breaking the next test.
 *
 * <p>How the runner handles chunks (read from {@code GameTestRunner$1}, {@code GameTestInfo} and
 * {@code TestInstanceBlockEntity}): {@code setChunkForced} is a plain flag, not a reference count. Each test
 * structure force-loads its own chunks; when the last test of a batch completes the runner's listener first
 * clears EVERY force-loaded chunk of the level and then starts the next batch (which force-loads that batch's
 * structure chunks) - all before the completion listener a test body registers (see {@link GameTestCleanup}).
 * Unforcing our chunks from such a listener therefore removed the flag the next test's structure had just been
 * given whenever the two overlapped: its chunks unloaded, the test waited for them forever (its tick counter
 * never starts, so it does not even time out) and the server spun at full speed writing a huge log.
 *
 * <p>So: chunks are only ever forced here, never released by the test body, and the runner's own clear-all
 * normally leaves nothing behind. As a guard that does not depend on that, the completion listener releases
 * any chunk this fixture forced that is still forced AND lies outside the bounds of every still-running
 * test structure the runner holds, which by construction cannot be one the runner just re-forced.
 */
public final class GameTestChunkForcing {
    private static final Logger LOG = LoggerFactory.getLogger("minecraftai-gametest-chunks");
    /** Chunks asked for by fixtures that have not finished yet (server thread only). */
    private static final Map<Long, Integer> CLAIMS = new HashMap<>();

    private GameTestChunkForcing() {
    }

    /**
     * Force-loads chunks {@code minChunkX..maxChunkX} x {@code minChunkZ..maxChunkZ} (inclusive) for the test
     * behind {@code context}. Call it from the test method, before the first tick.
     */
    public static void forceForTest(GameTestHelper context,
                                    int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        ServerLevel level = context.getLevel();
        Set<Long> owned = new HashSet<>();
        Set<Long> claimed = new HashSet<>();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                claimed.add(ChunkPos.asLong(cx, cz));
                CLAIMS.merge(ChunkPos.asLong(cx, cz), 1, Integer::sum);
                // true only when the flag actually changed: chunks that were already forced (the test's own
                // structure, a neighbour's) are not ours to release.
                if (level.setChunkForced(cx, cz, true)) {
                    owned.add(ChunkPos.asLong(cx, cz));
                }
            }
        }
        GameTestCleanup.whenFinished(context, (info, runner) -> {
            for (long key : claimed) {
                CLAIMS.merge(key, -1, Integer::sum);
                CLAIMS.remove(key, 0);
            }
            releaseLeaked(level, owned, runner);
        });
    }

    private static void releaseLeaked(ServerLevel level, Set<Long> owned, GameTestRunner runner) {
        Set<Long> liveStructureChunks = new HashSet<>();
        for (GameTestInfo other : runner.getTestInfos()) {
            if (other.isDone()) {
                continue;
            }
            AABB bounds;
            try {
                bounds = other.getStructureBounds().inflate(1.0D);
            } catch (RuntimeException notPlacedYet) {
                continue; // no structure yet: the runner forces its chunks when it spawns it, later
            }
            for (int cx = (int) Math.floor(bounds.minX) >> 4; cx <= (int) Math.floor(bounds.maxX) >> 4; cx++) {
                for (int cz = (int) Math.floor(bounds.minZ) >> 4; cz <= (int) Math.floor(bounds.maxZ) >> 4; cz++) {
                    liveStructureChunks.add(ChunkPos.asLong(cx, cz));
                }
            }
        }
        int released = 0;
        for (long key : owned) {
            if (liveStructureChunks.contains(key)
                    || CLAIMS.containsKey(key) // another unfinished fixture still asked for this chunk
                    || !level.getForceLoadedChunks().contains(key)) {
                continue;
            }
            level.setChunkForced(ChunkPos.getX(key), ChunkPos.getZ(key), false);
            released++;
        }
        if (released > 0) {
            LOG.info("released {} force-loaded fixture chunks that the runner had not cleared", released);
        }
    }
}

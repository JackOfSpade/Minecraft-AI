package io.github.zoyluo.minecraftai.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;

/**
 * Force-loading of the extra chunks a wide fixture (a lake or an island wider than one test structure) needs
 * so its entities keep ticking.
 *
 * <p>Chunks are only ever forced here, never released. GameTest servers are short-lived, and the runner clears
 * its own structure chunks: {@code setChunkForced} is a plain flag, not a reference count, and when the last
 * test of a batch completes the runner's listener clears EVERY force-loaded chunk of the level and then starts
 * the next batch (which force-loads that batch's structure chunks) - all before the completion listener a test
 * body registers (see {@link GameTestCleanup}). Nothing a fixture forces therefore outlives its batch, and
 * {@code GameTestCleanupSelfTests} fails loudly if a framework change ever stops that.
 *
 * <p>Do NOT add a release step. Unforcing a fixture's chunks from a completion listener removed the flag the
 * next test's structure had just been given whenever the two overlapped: its chunks unloaded, the test waited
 * for them forever (its tick counter never starts, so it does not even time out) and the server spun at full
 * speed writing a huge log.
 */
public final class GameTestChunkForcing {
    private GameTestChunkForcing() {
    }

    /**
     * Force-loads chunks {@code minChunkX..maxChunkX} x {@code minChunkZ..maxChunkZ} (inclusive) for the test
     * behind {@code context}. Call it from the test method, before the first tick.
     */
    public static void forceForTest(GameTestHelper context,
                                    int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        ServerLevel level = context.getLevel();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
    }
}

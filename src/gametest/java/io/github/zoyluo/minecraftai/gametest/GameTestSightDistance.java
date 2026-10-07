package io.github.zoyluo.minecraftai.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;

/**
 * Opt-in block sight as long as a bot has in play, for the tests that are about targets farther than the harness lets a bot see.
 *
 * <p>A bot's block sight is its render distance in blocks: the smaller of the view distance it asks for (a bot has the default
 * client options, 2 chunks) and the one the server runs with ({@code ObservableWorldQuery.visibleRangeBlocks}). A dedicated server runs
 * with {@code view-distance=10}, so a bot in play sees {@value #BOT_BLOCK_SIGHT} blocks. The GameTest server has no such setting: its
 * player list reports a view distance of 0, which is clamped to one chunk, so every bot of the suite sees 16 blocks and a target at
 * 20 to 30 blocks, which a bot in play sees, is out of sight. Nothing in the suite can notice, because a test only ever places things
 * near the bot.</p>
 *
 * <p>The suite deliberately stays on the harness distance: about 1200 tests were written against it and none of them is about long
 * sight. A test that is about long sight calls {@link #useProductionView} first. It raises only the number the observation reads (the
 * player list's view distance, which the dedicated server's setting sets); the chunk tracking of the bots and the loaded world are the
 * harness's own. The harness value is put back when the test ends, passed, failed or timed out ({@link GameTestCleanup});
 * {@link GameTestSweeper} puts it back too, loudly, should that ever not have run, so a test that opts in cannot change what the next
 * one sees.</p>
 */
public final class GameTestSightDistance {
    /** {@code view-distance} of a dedicated server as the game ships it, and as the evidence run configures it. */
    public static final int PRODUCTION_VIEW_DISTANCE_CHUNKS = 10;
    /** What a bot in play sees: its own 2 chunks are the smaller of the two view distances. */
    public static final int BOT_BLOCK_SIGHT = 32;

    /** The view distance the player list had before the production one was put in force, or null when none is (server thread only). */
    private static Integer harnessViewDistance;

    private GameTestSightDistance() {
    }

    /**
     * Puts the production view distance in force for the test behind {@code helper} and restores the harness's when the test is over.
     * Call it from the test method, before the scenario is built; one test may call it once.
     */
    public static void useProductionView(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        // Registered first: whatever happens after this line, the test's end puts the distance back.
        GameTestCleanup.whenFinished(helper, () -> restore(server));
        apply(server);
    }

    /** The same for code that has a scope of its own: {@code try (var view = GameTestSightDistance.productionView(server)) {...}}. */
    public static Scope productionView(MinecraftServer server) {
        apply(server);
        return new Scope(server);
    }

    /** Restores the harness's view distance if a production one is still in force; false when none was. Server thread only. */
    static boolean restoreLeaked(MinecraftServer server) {
        return restore(server);
    }

    private static void apply(MinecraftServer server) {
        if (harnessViewDistance != null) {
            throw new IllegalStateException("the production view distance is already in force for another test or scope");
        }
        harnessViewDistance = server.getPlayerList().getViewDistance();
        server.getPlayerList().setViewDistance(PRODUCTION_VIEW_DISTANCE_CHUNKS);
    }

    private static boolean restore(MinecraftServer server) {
        if (harnessViewDistance == null) {
            return false;
        }
        int harness = harnessViewDistance;
        harnessViewDistance = null;
        server.getPlayerList().setViewDistance(harness);
        return true;
    }

    /** Puts the harness's view distance back on {@link #close}, however the body ended. */
    public static final class Scope implements AutoCloseable {
        private final MinecraftServer server;

        private Scope(MinecraftServer server) {
            this.server = server;
        }

        @Override
        public void close() {
            restore(server);
        }
    }
}

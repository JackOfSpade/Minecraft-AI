package io.github.zoyluo.minecraftai.gametest;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the light engine in step with the game ticks of the GameTest server.
 *
 * <p>A block change only queues a light update; the light engine computes it on its own thread and publishes it some time later.
 * On a real server, which waits out the rest of every 50 ms tick, that is before the next tick. The GameTest server does not wait: it
 * runs the next tick as soon as the last one is done, hundreds of ticks per second, so the light of a change can arrive tens of ticks
 * late, and how late depends on how much light work is queued (a large scene built or restored a moment ago) and on how busy the
 * machine is. Everything that reads light then races it: a crop the fixture plants pops off on its next neighbour update because its
 * cell still has the light of a roof that was just removed ({@code CropBlock.canSurvive} needs light 8), a roofed cell still reports
 * open sky, a torch-reserve or surface decision reads the old light. None of it happens when the test runs alone, which is exactly
 * what makes such a failure look like chance.</p>
 *
 * <p>So at the end of every tick in which blocks changed, the server thread waits until the light engine has processed the updates of
 * the chunks those changes were in: the next tick sees the light of this tick's blocks, as it would on a real server. The wait is
 * bounded (a light engine that never answers must not hang the suite): a timeout is logged, and after {@value #MAX_TIMEOUTS} of them
 * the synchronisation switches itself off, loudly.</p>
 */
public final class GameTestLightSync {
    private static final Logger LOG = LoggerFactory.getLogger("minecraftai-gametest-light-sync");
    private static final boolean ENABLED = System.getProperty("fabric-api.gametest") != null;
    private static final long MAX_WAIT_NANOS = 2_000_000_000L;
    private static final int MAX_TIMEOUTS = 5;

    /** Per level: the chunks with a block change since the end of the last tick (server thread only). */
    private static final Map<ServerLevel, LongSet> CHANGED = new IdentityHashMap<>();
    private static int timeouts;
    private static boolean disabled;

    private GameTestLightSync() {
    }

    /** Called at the head of every {@code Level.setBlock} (see {@code LevelSetBlockRecorderMixin}). */
    public static void blockChanging(Level level, BlockPos pos) {
        if (!ENABLED || disabled || !(level instanceof ServerLevel server) || !server.getServer().isSameThread()) {
            return;
        }
        CHANGED.computeIfAbsent(server, ignored -> new LongOpenHashSet()).add(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    /** {@code ServerTickEvents.END_SERVER_TICK}, registered after every harness hook that changes blocks at the end of a tick. */
    public static void endTick(MinecraftServer server) {
        if (CHANGED.isEmpty()) {
            return;
        }
        if (disabled) {
            CHANGED.clear();
            return;
        }
        for (Map.Entry<ServerLevel, LongSet> entry : CHANGED.entrySet()) {
            ThreadedLevelLightEngine engine = entry.getKey().getChunkSource().getLightEngine();
            List<CompletableFuture<?>> pending = new ArrayList<>(entry.getValue().size());
            for (long chunk : entry.getValue()) {
                pending.add(engine.waitForPendingTasks(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)));
            }
            if (!await(engine, pending)) {
                timeouts++;
                LOG.warn("the light engine did not finish the updates of {} chunks within {} ms (timeout {} of {})",
                        pending.size(), MAX_WAIT_NANOS / 1_000_000L, timeouts, MAX_TIMEOUTS);
                if (timeouts >= MAX_TIMEOUTS) {
                    disabled = true;
                    LOG.error("light synchronisation switched off: light-dependent GameTests may race the light engine from now on");
                    break;
                }
            }
        }
        CHANGED.clear();
    }

    /**
     * The updates run on the light engine's own executor once {@code tryScheduleUpdate} hands them over, which vanilla only does from
     * the server tick; the server thread is waiting here, so it hands them over itself until every future is done.
     */
    private static boolean await(ThreadedLevelLightEngine engine, List<CompletableFuture<?>> pending) {
        long deadline = System.nanoTime() + MAX_WAIT_NANOS;
        for (CompletableFuture<?> future : pending) {
            while (!future.isDone()) {
                if (System.nanoTime() > deadline) {
                    return false;
                }
                engine.tryScheduleUpdate();
                LockSupport.parkNanos(20_000L);
            }
        }
        return true;
    }
}

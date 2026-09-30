package io.github.zoyluo.minecraftai.gametest;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Puts the blocks back that a batch of GameTests changed, before the next batch starts.
 *
 * <p>Every test builds its scene in the one shared test world and nothing removes it afterwards: the runner's structures are one
 * block wide, so the walls, ore veins, ponds, lava pits, roofs and shelters a test builds (most of them far outside its own
 * structure, at offsets chosen by hand) simply stay. Which leftover ends up next to, under or above a later test depends on the order
 * the batches happen to run in, and it changes from run to run: a test that passes alone fails when a neighbour's roof takes its
 * sky, a wall puts its path on another route, a vein of someone else's ore is what its bot digs into. Nothing in a test can see that.
 *
 * <p>{@code LevelSetBlockRecorderMixin} calls {@link #beforeSetBlock} before every {@code Level.setBlock}; the first call for a
 * position in a batch remembers the state it had. When the next batch starts (see {@link GameTestSweeper}) every remembered position
 * is set back to that state, without drops, without neighbour updates and without the block's side effects, except the cells of the
 * structures the runner has just placed for the new batch. The restored state is the state before the batch, which (batch after batch)
 * is the pristine world.</p>
 *
 * <p>The chunks have to be there to be restored. A test that builds far from its own structure gets the chunk from a synchronous
 * load that no ticket holds, and the runner releases every chunk it forced in the tick the batch ends, before anything here runs: what
 * was built in such a chunk would be saved with it and come back with it. {@link #keepLoaded} therefore holds every chunk a batch
 * touched with a ticket of its own (entity-ticking, so a mob in it ticks and an item in it is picked up), released only after the
 * restore.</p>
 *
 * <p>A ticket only asks for the chunk: it becomes entity-ticking when the chunks around it are generated, on the worker threads, a
 * while later. Until then the blocks are there but an entity added to it sits in a hidden section: an entity query does not find it
 * and it does not tick. On the GameTest server, which runs its ticks back to back, "a while" is dozens of ticks on a busy machine in a
 * part of the world no test has used yet: a husk three blocks from a burning bot was invisible to its danger scan for the whole fire
 * rescue, and a cobblestone drop could not be found or picked up until a dig-down gave up. So {@link #awaitHeldChunks}, at the end of
 * every tick, waits (bounded) until every chunk first held in that tick is entity-ticking: a scene is live from the tick after it was
 * built, as it is on a real server where the chunk system keeps up with 20 ticks a second.</p>
 */
public final class GameTestWorldRestorer {
    private static final Logger LOG = LoggerFactory.getLogger("minecraftai-gametest-restorer");
    private static final int RESTORE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS
            | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS | Block.UPDATE_SKIP_ON_PLACE;
    private static final boolean ENABLED = System.getProperty("fabric-api.gametest") != null;
    /** Ticket radius 2 is the entity-ticking level; the ticket type only has to be one that never expires and loads and simulates. */
    private static final TicketType HOLD = TicketType.DRAGON;
    private static final int HOLD_RADIUS = 2;

    /** Per level: packed position to the state it had before the batch first changed it (server thread only). */
    private static final Map<Level, Long2ObjectMap<BlockState>> ORIGINAL = new IdentityHashMap<>();
    /** Per level: the chunks this batch holds a ticket for (server thread only). */
    private static final Map<Level, LongSet> HELD = new IdentityHashMap<>();
    /** Per level: the chunks first held since the end of the last tick, which {@link #awaitHeldChunks} waits for (server thread only). */
    private static final Map<ServerLevel, LongSet> NEWLY_HELD = new IdentityHashMap<>();
    private static final long MAX_WAIT_NANOS = 10_000_000_000L;
    private static final int MAX_TIMEOUTS = 5;
    private static int timeouts;
    private static boolean waitDisabled;
    private static boolean restoring;

    private GameTestWorldRestorer() {
    }

    /** Called by the mixin at the head of {@code Level.setBlock}. */
    public static void beforeSetBlock(Level level, BlockPos pos) {
        if (!ENABLED || restoring || !(level instanceof ServerLevel) || level.isOutsideBuildHeight(pos)) {
            return;
        }
        keepLoaded(level, pos.getX() >> 4, pos.getZ() >> 4);
        Long2ObjectMap<BlockState> changed = ORIGINAL.computeIfAbsent(level, ignored -> new Long2ObjectOpenHashMap<>());
        long key = pos.asLong();
        if (!changed.containsKey(key)) {
            changed.put(key, level.getBlockState(pos));
        }
    }

    /** Holds a chunk loaded and entity-ticking until the batch is restored. */
    public static void keepLoaded(Level level, int chunkX, int chunkZ) {
        if (!ENABLED || restoring || !(level instanceof ServerLevel server)) {
            return;
        }
        long chunk = ChunkPos.asLong(chunkX, chunkZ);
        if (HELD.computeIfAbsent(level, ignored -> new LongOpenHashSet()).add(chunk)) {
            server.getChunkSource().addTicketWithRadius(HOLD, new ChunkPos(chunkX, chunkZ), HOLD_RADIUS);
            NEWLY_HELD.computeIfAbsent(server, ignored -> new LongOpenHashSet()).add(chunk);
        }
    }

    /**
     * {@code ServerTickEvents.END_SERVER_TICK}: waits until every chunk first held in this tick is entity-ticking, running the chunk
     * system's main-thread work (ticket updates, the promotions of chunks the workers finished) while it waits. Bounded: a timeout is
     * logged, and after {@value #MAX_TIMEOUTS} of them the wait switches itself off, loudly, rather than slow the suite down.
     */
    public static void awaitHeldChunks(net.minecraft.server.MinecraftServer server) {
        if (NEWLY_HELD.isEmpty()) {
            return;
        }
        if (!waitDisabled) {
            long deadline = System.nanoTime() + MAX_WAIT_NANOS;
            for (Map.Entry<ServerLevel, LongSet> entry : NEWLY_HELD.entrySet()) {
                ServerLevel level = entry.getKey();
                int pending = 0;
                for (long chunk : entry.getValue()) {
                    BlockPos probe = new BlockPos(ChunkPos.getX(chunk) << 4, level.getMinY(), ChunkPos.getZ(chunk) << 4);
                    while (!level.isPositionEntityTicking(probe) && System.nanoTime() < deadline) {
                        if (!level.getChunkSource().pollTask()) {
                            java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
                        }
                    }
                    if (!level.isPositionEntityTicking(probe)) {
                        pending++;
                    }
                }
                if (pending > 0) {
                    timeouts++;
                    LOG.warn("{} held chunks were not entity-ticking after {} s (timeout {} of {})", pending,
                            MAX_WAIT_NANOS / 1_000_000_000L, timeouts, MAX_TIMEOUTS);
                    if (timeouts >= MAX_TIMEOUTS) {
                        waitDisabled = true;
                        LOG.error("the wait for held chunks is switched off: scenes may be built in chunks whose entities do not tick yet");
                    }
                    break;
                }
            }
        }
        NEWLY_HELD.clear();
    }

    /**
     * Sets every block the batch changed back except inside {@code keep} (whose entries stay recorded for the next restore), then
     * releases the chunks; returns how many blocks were put back.
     */
    static int restore(List<AABB> keep) {
        int restored = 0;
        int skipped = 0;
        restoring = true;
        try {
            for (Map.Entry<Level, Long2ObjectMap<BlockState>> entry : new ArrayList<>(ORIGINAL.entrySet())) {
                Level level = entry.getKey();
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                Iterator<Long2ObjectMap.Entry<BlockState>> changes = entry.getValue().long2ObjectEntrySet().iterator();
                while (changes.hasNext()) {
                    Long2ObjectMap.Entry<BlockState> changed = changes.next();
                    pos.set(changed.getLongKey());
                    if (inside(keep, pos)) {
                        continue;
                    }
                    if (!level.hasChunkAt(pos)) {
                        skipped++;
                    } else if (level.getBlockState(pos) != changed.getValue()) {
                        level.setBlock(pos, changed.getValue(), RESTORE_FLAGS);
                        restored++;
                    }
                    changes.remove();
                }
            }
            for (Map.Entry<Level, LongSet> held : HELD.entrySet()) {
                if (held.getKey() instanceof ServerLevel server) {
                    for (long chunk : held.getValue()) {
                        server.getChunkSource().removeTicketWithRadius(HOLD, new ChunkPos(chunk), HOLD_RADIUS);
                    }
                }
            }
            HELD.clear();
            NEWLY_HELD.clear();
        } finally {
            restoring = false;
        }
        if (skipped > 0) {
            LOG.info("{} changed blocks were in unloaded chunks and stay as the batch left them", skipped);
        }
        return restored;
    }

    private static boolean inside(List<AABB> keep, BlockPos pos) {
        for (AABB box : keep) {
            if (pos.getX() + 1 > box.minX && pos.getX() < box.maxX
                    && pos.getY() + 1 > box.minY && pos.getY() < box.maxY
                    && pos.getZ() + 1 > box.minZ && pos.getZ() < box.maxZ) {
                return true;
            }
        }
        return false;
    }
}

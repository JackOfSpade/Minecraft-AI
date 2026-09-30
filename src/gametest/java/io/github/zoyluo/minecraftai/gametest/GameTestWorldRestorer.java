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
        if (HELD.computeIfAbsent(level, ignored -> new LongOpenHashSet()).add(ChunkPos.asLong(chunkX, chunkZ))) {
            server.getChunkSource().addTicketWithRadius(HOLD, new ChunkPos(chunkX, chunkZ), HOLD_RADIUS);
        }
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

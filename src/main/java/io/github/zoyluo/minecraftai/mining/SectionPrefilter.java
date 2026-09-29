package io.github.zoyluo.minecraftai.mining;

import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Palette-level candidate filter for observable block searches. A search asks, per cell, whether the
 * cell's chunk section can hold a matching state at all ({@link LevelChunkSection#maybeHas}, a palette
 * check that costs nothing per block); only cells of such sections are worth a state read, and only cells
 * whose state matches are worth the ray cast of {@code ObservableWorldQuery.canObserveBlock}. The answer
 * is cached for the section of the last cell, so a scan in section order pays one lookup per section.
 *
 * <p>The result of a search is unchanged by this filter: a cell is still returned only when it matches AND
 * is observable. The filter merely decides the cheaper conjunct first, and never reads a state the caller
 * would not have read anyway (the state of a cell that fails the match is discarded, never acted on).</p>
 */
public final class SectionPrefilter {
    private final ServerLevel world;
    private final Predicate<BlockState> match;
    private boolean cached;
    private int cachedChunkX;
    private int cachedSectionY;
    private int cachedChunkZ;
    private LevelChunkSection cachedSection;

    public SectionPrefilter(ServerLevel world, Predicate<BlockState> match) {
        this.world = world;
        this.match = match;
    }

    /**
     * The loaded section holding the cell that may contain a matching state, else null (unloaded chunk,
     * out of range, only air, or no matching palette entry).
     */
    public LevelChunkSection candidateSection(int x, int y, int z) {
        int chunkX = x >> 4;
        int sectionY = y >> 4;
        int chunkZ = z >> 4;
        if (cached && chunkX == cachedChunkX && sectionY == cachedSectionY && chunkZ == cachedChunkZ) {
            return cachedSection;
        }
        LevelChunkSection found = null;
        ChunkAccess raw = world.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (raw instanceof LevelChunk chunk) {
            int index = world.getSectionIndexFromSectionY(sectionY);
            if (index >= 0 && index < chunk.getSections().length) {
                LevelChunkSection section = chunk.getSection(index);
                if (section != null && !section.hasOnlyAir() && section.maybeHas(match)) {
                    found = section;
                }
            }
        }
        cached = true;
        cachedChunkX = chunkX;
        cachedSectionY = sectionY;
        cachedChunkZ = chunkZ;
        cachedSection = found;
        return found;
    }

    /** State of the cell inside a section returned by {@link #candidateSection}. */
    public static BlockState stateIn(LevelChunkSection section, int x, int y, int z) {
        return section.getBlockState(x & 15, y & 15, z & 15);
    }

    /** First block z coordinate of the section after the one holding {@code z}. */
    public static int nextSectionStartZ(int z) {
        return ((z >> 4) + 1) << 4;
    }
}

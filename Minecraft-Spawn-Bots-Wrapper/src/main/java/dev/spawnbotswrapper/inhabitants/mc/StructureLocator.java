package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * On-demand lookup of structure instances from Minecraft's own structure data (chunk StructureStarts of
 * already LOADED chunks only - it never loads or generates anything). Used by the admin commands; the
 * normal flow is event driven through the detector instead.
 */
public interface StructureLocator {

    /**
     * Structures whose bounding box contains {@code pos} (there can be several, e.g. a village around
     * a pillager outpost), smallest box first.
     */
    List<StructureSnapshot> at(ServerWorld world, BlockPos pos);

    /**
     * Structures whose start chunk is within {@code radiusChunks} (Chebyshev) of the chunk containing
     * {@code pos}, nearest first, considering loaded chunks only.
     */
    List<StructureSnapshot> near(ServerWorld world, BlockPos pos, int radiusChunks);
}

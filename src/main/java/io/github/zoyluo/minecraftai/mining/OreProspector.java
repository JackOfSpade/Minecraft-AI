package io.github.zoyluo.minecraftai.mining;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Ore prospector (ported from the player-side magic mod's HelmetOreLocator): scans chunk by chunk / section
 * within a large-radius cube, returning **the nearest target block's coordinates** — shared by OreDigTask
 * for long-range ore vein location, GatherQuotaTask for long-range tree location (across elevations / off
 * the plateau), and similar callers.
 *
 * Performance key (same as the reference): uses {@link ChunkSection#hasAny} (palette-level, not per-block)
 * to quickly skip sections that don't contain the target, and only deep-scans sections that do; so even
 * 64~128 blocks doesn't lag. Only scans **already-loaded chunks** (getChunk FULL, create=false); callers
 * rate-limit calls to protect TPS.
 */
public final class OreProspector {
    private OreProspector() {
    }

    /** Within the loaded chunks of the range cube around origin, finds the nearest target ore; null if none. Runs entirely on the main thread (read-only world data). */
    public static BlockPos nearest(AIPlayerEntity bot, Set<Block> targets, int range) {
        if (targets == null || targets.isEmpty()) {
            return null;
        }
        return nearest(bot, range, state -> OreScan.isOre(state, targets));
    }

    /**
     * General-purpose version: finds the nearest "block satisfying match" — reusable for ore search via
     * OreScan.isOre, tree search via "log block set contains", and similar cases.
     * Palette-level section.hasAny(match) quickly skips sections that don't contain the target, so even a
     * large radius doesn't lag.
     */
    public static BlockPos nearest(AIPlayerEntity bot, int range, Predicate<BlockState> match) {
        return nearest(bot, range, match, null);
    }

    /**
     * Position-filtered version: positions rejected by posFilter are skipped (e.g. the caller blacklists
     * "unreachable targets" to prevent repeatedly prospecting into the same infinite loop).
     * No filtering is applied when posFilter is null.
     */
    public static BlockPos nearest(AIPlayerEntity bot, int range,
                                   Predicate<BlockState> match, Predicate<BlockPos> posFilter) {
        boolean hiddenScanAllowed = CapabilityRuntime.decide(
                bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "ore_prospector").allowed();
        ServerWorld world = bot.getEntityWorld();
        BlockPos origin = bot.getBlockPos();
        if (hiddenScanAllowed) {
            return nearestRaw(world, origin, range, match, posFilter);
        }
        return nearestObservable(bot, origin, range, match, posFilter);
    }

    /** Strict-survival search: visibility is decided before any candidate block state is read. */
    private static BlockPos nearestObservable(AIPlayerEntity bot,
                                              BlockPos origin,
                                              int requestedRange,
                                              Predicate<BlockState> match,
                                              Predicate<BlockPos> posFilter) {
        ServerWorld world = bot.getEntityWorld();
        int range = Math.min(Math.max(1, requestedRange),
                Math.max(1, io.github.zoyluo.minecraftai.MinecraftAiConfig.get().perception().radius()));
        int minY = Math.max(world.getBottomY(), origin.getY() - range);
        int maxY = Math.min(world.getBottomY() + world.getHeight() - 1, origin.getY() + range);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int x = origin.getX() - range; x <= origin.getX() + range; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = origin.getZ() - range; z <= origin.getZ() + range; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if ((posFilter != null && !posFilter.test(pos))
                            || !ObservableWorldQuery.canObserveBlock(bot, pos)) {
                        continue;
                    }
                    BlockState state = world.getBlockState(pos);
                    if (!match.test(state)) {
                        continue;
                    }
                    double distance = origin.getSquaredDistance(pos);
                    if (distance < bestDist) {
                        bestDist = distance;
                        best = pos.toImmutable();
                    }
                }
            }
        }
        return best;
    }

    private static BlockPos nearestRaw(ServerWorld world, BlockPos origin, int range,
                                       Predicate<BlockState> match, Predicate<BlockPos> posFilter) {
        int minX = origin.getX() - range;
        int maxX = origin.getX() + range;
        int minY = Math.max(world.getBottomY(), origin.getY() - range);
        int maxY = Math.min(world.getBottomY() + world.getHeight() - 1, origin.getY() + range);
        int minZ = origin.getZ() - range;
        int maxZ = origin.getZ() + range;
        int minCX = ChunkSectionPos.getSectionCoord(minX);
        int maxCX = ChunkSectionPos.getSectionCoord(maxX);
        int minCZ = ChunkSectionPos.getSectionCoord(minZ);
        int maxCZ = ChunkSectionPos.getSectionCoord(maxZ);
        int minSY = ChunkSectionPos.getSectionCoord(minY);
        int maxSY = ChunkSectionPos.getSectionCoord(maxY);

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                Chunk raw = world.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (!(raw instanceof WorldChunk chunk)) {
                    continue; // not loaded, skip
                }
                int startX = chunk.getPos().getStartX();
                int startZ = chunk.getPos().getStartZ();
                int lMinX = Math.max(minX, startX) - startX;
                int lMaxX = Math.min(maxX, startX + 15) - startX;
                int lMinZ = Math.max(minZ, startZ) - startZ;
                int lMaxZ = Math.min(maxZ, startZ + 15) - startZ;
                for (int sy = minSY; sy <= maxSY; sy++) {
                    int idx = world.sectionCoordToIndex(sy);
                    if (idx < 0 || idx >= chunk.getSectionArray().length) {
                        continue;
                    }
                    ChunkSection section = chunk.getSection(idx);
                    if (section == null || section.isEmpty()
                            || !section.hasAny(match)) {
                        continue; // palette-level fast skip of sections that don't contain the target
                    }
                    int startY = ChunkSectionPos.getBlockCoord(sy);
                    int lMinY = Math.max(minY, startY) - startY;
                    int lMaxY = Math.min(maxY, startY + 15) - startY;
                    for (int ly = lMinY; ly <= lMaxY; ly++) {
                        int y = startY + ly;
                        for (int lx = lMinX; lx <= lMaxX; lx++) {
                            int x = startX + lx;
                            for (int lz = lMinZ; lz <= lMaxZ; lz++) {
                                int z = startZ + lz;
                                BlockState state = section.getBlockState(lx, ly, lz);
                                if (!match.test(state)) {
                                    continue;
                                }
                                double d = origin.getSquaredDistance(x, y, z);
                                if (d >= bestDist) {
                                    continue;
                                }
                                BlockPos pos = new BlockPos(x, y, z);
                                if (posFilter != null && !posFilter.test(pos)) {
                                    continue; // blacklisted by the caller (e.g. a repeatedly unreachable target)
                                }
                                bestDist = d;
                                best = pos;
                            }
                        }
                    }
                }
            }
        }
        return best;
    }
}

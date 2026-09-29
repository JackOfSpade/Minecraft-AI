package io.github.zoyluo.minecraftai.mining;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Vec3d;
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
 *
 * The search itself is a resumable {@link Scan}: {@link #nearest} runs it to completion in one call (the
 * original synchronous behaviour), while a caller that scans a wide range on the server thread uses
 * {@link #begin} and advances it a few milliseconds per tick.
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
        Scan scan = begin(bot, range, match, posFilter);
        scan.step(Long.MAX_VALUE);
        return scan.result();
    }

    /**
     * Starts a resumable search with the same semantics as {@link #nearest(AIPlayerEntity, int, Predicate,
     * Predicate)} (which is just {@code begin} plus one unbounded {@link Scan#step}). The capability decision
     * is taken once here. Callers on the server thread drive it with {@link Scan#step} and a small per-tick
     * nanosecond budget so a wide scan never lands as one 200-400 ms tick.
     */
    public static Scan begin(AIPlayerEntity bot, int range,
                             Predicate<BlockState> match, Predicate<BlockPos> posFilter) {
        boolean hiddenScanAllowed = CapabilityRuntime.decide(
                bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "ore_prospector").allowed();
        return new Scan(bot, range, match, posFilter, hiddenScanAllowed);
    }

    /**
     * A search that can be advanced a few milliseconds at a time. Not thread-safe: server thread only. The
     * search cube is fixed at {@link #begin}; the observable path re-reads the bot's live eye on each step, so
     * a bot that walks meanwhile is judged by what it can see now, never by what it could have seen.
     */
    public static final class Scan {
        /** A face endpoint lies within half a block diagonal (sqrt(3)/2) of its block centre. */
        private static final double CENTER_TO_FACE_SLACK = 0.87D;
        private static final int CLOCK_CHECK_MASK = 7; // read the clock every 8 candidate positions

        private final AIPlayerEntity bot;
        private final ServerWorld world;
        private final BlockPos origin;
        private final Predicate<BlockState> match;
        private final Predicate<BlockPos> posFilter;
        private final boolean raw;
        private final int startTick;
        private BlockPos best;
        private double bestDist = Double.MAX_VALUE;
        private boolean done;
        private int steps;
        private long maxStepNanos;
        private long totalNanos;

        private int minX;
        private int maxX;
        private int minY;
        private int maxY;
        private int minZ;
        private int maxZ;

        // observable cursor (x outermost, z innermost: the original iteration order, so ties resolve identically)
        private int cursorX;
        private int cursorY;
        private int cursorZ;
        private double radiusWithSlackSq;

        // raw cursor
        private int minCX;
        private int maxCX;
        private int minCZ;
        private int maxCZ;
        private int minSY;
        private int maxSY;
        private int cursorCX;
        private int cursorCZ;
        private int cursorSY;

        private Scan(AIPlayerEntity bot, int requestedRange, Predicate<BlockState> match,
                     Predicate<BlockPos> posFilter, boolean hiddenScanAllowed) {
            this.bot = bot;
            this.world = bot.getEntityWorld();
            this.origin = bot.getBlockPos();
            this.match = match;
            this.posFilter = posFilter;
            this.raw = hiddenScanAllowed;
            this.startTick = world.getServer() == null ? 0 : world.getServer().getTicks();
            if (raw) {
                initRaw(requestedRange);
            } else {
                initObservable(requestedRange);
            }
        }

        /** Server tick at which the scan began (staleness checks by the caller). */
        public int startTick() {
            return startTick;
        }

        public BlockPos origin() {
            return origin;
        }

        public boolean isDone() {
            return done;
        }

        /** Nearest match, or null; meaningful once {@link #isDone}. */
        public BlockPos result() {
            return best;
        }

        public int steps() {
            return steps;
        }

        /** Longest single {@link #step} of this scan in nanoseconds (observability of the per-tick cost). */
        public long maxStepNanos() {
            return maxStepNanos;
        }

        public long totalNanos() {
            return totalNanos;
        }

        /**
         * Advances the scan for about {@code budgetNanos}; at least one unit of work is always done, so it
         * terminates. Returns true once finished.
         */
        public boolean step(long budgetNanos) {
            if (done) {
                return true;
            }
            long begin = System.nanoTime();
            long deadline = budgetNanos == Long.MAX_VALUE ? Long.MAX_VALUE : begin + budgetNanos;
            if (raw) {
                stepRaw(deadline);
            } else {
                stepObservable(deadline);
            }
            long spent = System.nanoTime() - begin;
            steps++;
            totalNanos += spent;
            maxStepNanos = Math.max(maxStepNanos, spent);
            return done;
        }

        private void initObservable(int requestedRange) {
            int radius = Math.max(1, io.github.zoyluo.minecraftai.MinecraftAiConfig.get().perception().radius());
            int range = Math.min(Math.max(1, requestedRange), radius);
            minY = Math.max(world.getBottomY(), origin.getY() - range);
            maxY = Math.min(world.getBottomY() + world.getHeight() - 1, origin.getY() + range);
            minX = origin.getX() - range;
            maxX = origin.getX() + range;
            minZ = origin.getZ() - range;
            maxZ = origin.getZ() + range;
            cursorX = minX;
            cursorY = minY;
            cursorZ = minZ;
            double observeRadius = radius + CENTER_TO_FACE_SLACK;
            radiusWithSlackSq = observeRadius * observeRadius;
        }

        /**
         * Strict-survival search: visibility is decided before any candidate block state is read. A position
         * whose centre is farther from the eye than the perception radius plus the face slack can never have a
         * visible face, so it is skipped without a ray or a state read (about half of the search cube's volume).
         */
        private void stepObservable(long deadline) {
            Vec3d eye = bot.getEyePos();
            BlockPos.Mutable pos = new BlockPos.Mutable();
            int counter = 0;
            while (cursorX <= maxX) {
                double dx = cursorX + 0.5D - eye.x;
                double dx2 = dx * dx;
                while (cursorY <= maxY) {
                    double dy = cursorY + 0.5D - eye.y;
                    double dxy2 = dx2 + dy * dy;
                    while (cursorZ <= maxZ) {
                        int z = cursorZ++;
                        double dz = z + 0.5D - eye.z;
                        if (dxy2 + dz * dz > radiusWithSlackSq) {
                            continue;
                        }
                        pos.set(cursorX, cursorY, z);
                        if ((posFilter == null || posFilter.test(pos))
                                && ObservableWorldQuery.canObserveBlock(bot, pos)) {
                            BlockState state = world.getBlockState(pos);
                            if (match.test(state)) {
                                double distance = origin.getSquaredDistance(pos);
                                if (distance < bestDist) {
                                    bestDist = distance;
                                    best = pos.toImmutable();
                                }
                            }
                        }
                        if ((++counter & CLOCK_CHECK_MASK) == 0 && System.nanoTime() >= deadline) {
                            return;
                        }
                    }
                    cursorZ = minZ;
                    cursorY++;
                }
                cursorY = minY;
                cursorX++;
            }
            done = true;
        }

        private void initRaw(int range) {
            minX = origin.getX() - range;
            maxX = origin.getX() + range;
            minY = Math.max(world.getBottomY(), origin.getY() - range);
            maxY = Math.min(world.getBottomY() + world.getHeight() - 1, origin.getY() + range);
            minZ = origin.getZ() - range;
            maxZ = origin.getZ() + range;
            minCX = ChunkSectionPos.getSectionCoord(minX);
            maxCX = ChunkSectionPos.getSectionCoord(maxX);
            minCZ = ChunkSectionPos.getSectionCoord(minZ);
            maxCZ = ChunkSectionPos.getSectionCoord(maxZ);
            minSY = ChunkSectionPos.getSectionCoord(minY);
            maxSY = ChunkSectionPos.getSectionCoord(maxY);
            cursorCX = minCX;
            cursorCZ = minCZ;
            cursorSY = minSY;
        }

        /** Operator-capability search: one chunk section per unit of work (palette-level skip of empty ones). */
        private void stepRaw(long deadline) {
            while (cursorCX <= maxCX) {
                while (cursorCZ <= maxCZ) {
                    Chunk rawChunk = world.getChunkManager().getChunk(cursorCX, cursorCZ, ChunkStatus.FULL, false);
                    if (!(rawChunk instanceof WorldChunk chunk)) {
                        cursorCZ++;
                        cursorSY = minSY;
                        continue; // not loaded, skip
                    }
                    while (cursorSY <= maxSY) {
                        scanRawSection(chunk, cursorSY++);
                        if (System.nanoTime() >= deadline) {
                            if (cursorSY > maxSY) {
                                cursorSY = minSY;
                                cursorCZ++;
                            }
                            return;
                        }
                    }
                    cursorSY = minSY;
                    cursorCZ++;
                }
                cursorCZ = minCZ;
                cursorCX++;
            }
            done = true;
        }

        private void scanRawSection(WorldChunk chunk, int sy) {
            int startX = chunk.getPos().getStartX();
            int startZ = chunk.getPos().getStartZ();
            int lMinX = Math.max(minX, startX) - startX;
            int lMaxX = Math.min(maxX, startX + 15) - startX;
            int lMinZ = Math.max(minZ, startZ) - startZ;
            int lMaxZ = Math.min(maxZ, startZ + 15) - startZ;
            int idx = world.sectionCoordToIndex(sy);
            if (idx < 0 || idx >= chunk.getSectionArray().length) {
                return;
            }
            ChunkSection section = chunk.getSection(idx);
            if (section == null || section.isEmpty() || !section.hasAny(match)) {
                return; // palette-level fast skip of sections that don't contain the target
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

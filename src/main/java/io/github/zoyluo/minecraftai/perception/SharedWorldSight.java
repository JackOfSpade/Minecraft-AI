package io.github.zoyluo.minecraftai.perception;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mining.assist.RayGrid;
import io.github.zoyluo.minecraftai.mode.SightClip;
import io.github.zoyluo.minecraftai.mode.SightClipContext;
import io.github.zoyluo.minecraftai.task.SharedVision;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Small, line-of-sight-only world memory shared by an AI player and its linked human owner.
 *
 * <p>This is deliberately not a chunk scan. Every remembered cell is either traversed by a
 * clip ray (which a bot's or player's eye lets pass through foliage, fences, glass and water) or is the
 * ray's first hit. A human observer uses the render-distance value it
 * actually supplied to the server, expressed in blocks ({@code chunks * 16}); an unloaded end
 * chunk suppresses the ray entirely. The memory is only route evidence -- callers still recheck
 * a remembered target before interacting with mutable terrain.</p>
 */
public final class SharedWorldSight {
    /** Vanilla stores a player's requested render distance in chunks; Minecraft chunks are 16 blocks wide. */
    public static final int BLOCKS_PER_CHUNK = 16;
    /** Retain recent visual evidence for the same lifetime as the navigation fence. */
    public static final int MEMORY_TTL_TICKS = 6_000;
    /** Keeps a player's render-distance sweep useful without creating an unbounded world cache. */
    private static final int MAX_CELLS_PER_BOT = 32_768;
    /** A route needs a compact working subset; its direct target is always appended last. */
    private static final int ROUTE_IMPORT_LIMIT = 4_096;
    /** One central ray plus a small progressive viewport sweep per observer and tick. */
    private static final int RAYS_PER_OBSERVER_TICK = 6;
    /** Do not repeat a whole lane on every four-tick navigation-fence refresh. */
    private static final int ROUTE_ENVELOPE_REFRESH_TICKS = 100;
    /** A finite viewport sample grid is revisited while the observer looks around. */
    private static final int VIEW_GRID = 17;
    private static final double VIEW_TANGENT = 0.85D;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** An immutable fact that a particular block cell was seen on a particular server tick. */
    public record Observation(long packedPos, BlockState state, int seenTick) {
        public Observation {
            state = java.util.Objects.requireNonNull(state, "state");
        }

        public BlockPos pos() {
            return BlockPos.of(packedPos);
        }
    }

    private static final class Memory {
        String dimension;
        int sweepCursor;
        int lastPruneTick = Integer.MIN_VALUE;
        long lastRouteTarget = Long.MIN_VALUE;
        int lastRouteEnvelopeTick = Integer.MIN_VALUE;
        /** Access order makes eviction O(1) and retains the most recently seen terrain. */
        final LinkedHashMap<Long, Observation> cells = new LinkedHashMap<>(256, 0.75F, true);

        Memory(String dimension) {
            this.dimension = dimension;
        }
    }

    private static final Map<UUID, Memory> MEMORIES = new java.util.HashMap<>();

    private SharedWorldSight() {
    }

    /** Converts the client render-distance setting from chunks into the corresponding block radius. */
    public static int renderDistanceBlocks(int chunks) {
        long blocks = (long) Math.max(1, chunks) * BLOCKS_PER_CHUNK;
        return (int) Math.min(Integer.MAX_VALUE, blocks);
    }

    /** The client request cannot exceed the server's active view distance. */
    private static int renderDistanceBlocks(ServerPlayer observer) {
        if (observer == null || observer.level().getServer() == null) {
            return 0;
        }
        int effectiveChunks = Math.min(observer.requestedViewDistance(),
                observer.level().getServer().getPlayerList().getViewDistance());
        return renderDistanceBlocks(effectiveChunks);
    }

    /**
     * Captures a small progressive set of genuinely visible rays from the bot and its linked
     * owner. Called once per bot per server tick, before task navigation runs.
     */
    public static void tickBot(AIPlayerEntity bot) {
        if (bot == null || bot.level().getServer() == null || !bot.isAlive()) {
            return;
        }
        int tick = bot.level().getServer().getTickCount();
        Memory memory = memoryFor(bot);
        prune(memory, tick);
        captureViewport(bot, bot, memory, tick);
        ServerPlayer owner = SharedVision.ownerOnline(bot);
        if (isEligibleOwner(bot, owner)) {
            captureViewport(bot, owner, memory, tick);
        }
    }

    /**
     * Adds a direct, render-distance-bounded owner/bot sight line to a requested route target,
     * then returns a compact newest-first-compatible evidence set for {@code ObservedNavigationFence}.
     */
    public static List<Observation> routeEvidence(AIPlayerEntity bot, BlockPos target) {
        if (bot == null || target == null || bot.level().getServer() == null) {
            return List.of();
        }
        int tick = bot.level().getServer().getTickCount();
        Memory memory = memoryFor(bot);
        prune(memory, tick);
        boolean botSawTarget = captureTowards(bot, bot, target.getCenter(), memory, tick);
        ServerPlayer owner = SharedVision.ownerOnline(bot);
        boolean ownerSawTarget = false;
        if (isEligibleOwner(bot, owner)) {
            ownerSawTarget = captureTowards(bot, owner, target.getCenter(), memory, tick);
        }
        // A route needs more than a single target cell: Baritone also asks for a factual body,
        // headroom, and floor lane.  Build that lane only from the observer that just proved the
        // target and only once per target/short refresh window.  Every individual cell below is
        // still a vanilla ray, so a wall simply leaves a gap rather than becoming guessed terrain.
        // Prefer the bot's own sight when both observers see the target: its lane originates at
        // the bot's current feet and is consequently the most useful continuous route evidence.
        ServerPlayer routeObserver = botSawTarget ? bot : ownerSawTarget ? owner : null;
        if (routeObserver != null && shouldCaptureRouteEnvelope(memory, target, tick)) {
            captureRouteEnvelope(bot, routeObserver, target, memory, tick);
            memory.lastRouteTarget = target.asLong();
            memory.lastRouteEnvelopeTick = tick;
        }
        if (memory.cells.isEmpty()) {
            return List.of();
        }
        List<Observation> all = new ArrayList<>(memory.cells.values());
        int from = Math.max(0, all.size() - ROUTE_IMPORT_LIMIT);
        return List.copyOf(all.subList(from, all.size()));
    }

    /**
     * Returns remembered target blocks in nearest-first order without reading any new terrain.
     * Callers must still re-prove the selected position before acting: this is a factual lead
     * from a prior bot/owner eye ray, not permission to treat a stale state as current.
     */
    public static List<Observation> knownBlocks(AIPlayerEntity bot, Set<Block> targetBlocks, int limit) {
        if (targetBlocks == null || targetBlocks.isEmpty()) {
            return List.of();
        }
        return knownBlocks(bot, state -> targetBlocks.contains(state.getBlock()), limit);
    }

    /**
     * Returns remembered cells selected by their already-recorded state, without inspecting new
     * terrain. This is useful for landmarks such as leaves: callers still re-prove a selected
     * cell before acting on it.
     */
    public static List<Observation> knownBlocks(AIPlayerEntity bot, Predicate<BlockState> stateFilter, int limit) {
        if (bot == null || stateFilter == null || limit <= 0 || bot.level().getServer() == null) {
            return List.of();
        }
        int tick = bot.level().getServer().getTickCount();
        Memory memory = memoryFor(bot);
        prune(memory, tick);
        List<Observation> matches = new ArrayList<>();
        for (Observation observation : memory.cells.values()) {
            if (stateFilter.test(observation.state())) {
                matches.add(observation);
            }
        }
        matches.sort(Comparator.comparingDouble(observation -> observation.pos().distSqr(bot.blockPosition())));
        if (matches.size() > limit) {
            return List.copyOf(matches.subList(0, limit));
        }
        return List.copyOf(matches);
    }

    /**
     * Adds the state at a cell that a caller has already proved with a vanilla eye ray.  This is
     * intentionally a post-proof API: it never casts a new ray or reads a block before the
     * caller's visibility predicate has succeeded.
     */
    public static void rememberConfirmed(AIPlayerEntity bot, BlockPos pos) {
        if (bot == null || pos == null || bot.level().getServer() == null) {
            return;
        }
        int tick = bot.level().getServer().getTickCount();
        Memory memory = memoryFor(bot);
        prune(memory, tick);
        remember(memory, new Observation(pos.asLong(), bot.level().getBlockState(pos), tick));
    }

    /** Drops evidence for a despawned bot. */
    public static void forget(UUID botUuid) {
        if (botUuid != null) {
            MEMORIES.remove(botUuid);
        }
    }

    /** Drops all server-lifetime visual evidence during shutdown. */
    public static void clearAll() {
        MEMORIES.clear();
    }

    private static Memory memoryFor(AIPlayerEntity bot) {
        String dimension = bot.level().dimension().identifier().toString();
        Memory memory = MEMORIES.computeIfAbsent(bot.getUUID(), ignored -> new Memory(dimension));
        if (!memory.dimension.equals(dimension)) {
            memory.dimension = dimension;
            memory.sweepCursor = 0;
            memory.lastPruneTick = Integer.MIN_VALUE;
            memory.lastRouteTarget = Long.MIN_VALUE;
            memory.lastRouteEnvelopeTick = Integer.MIN_VALUE;
            memory.cells.clear();
        }
        return memory;
    }

    private static boolean isEligibleOwner(AIPlayerEntity bot, ServerPlayer owner) {
        return owner != null && owner != bot && owner.isAlive() && !owner.isSpectator()
                && owner.level() == bot.level();
    }

    private static void captureViewport(AIPlayerEntity bot, ServerPlayer observer, Memory memory, int tick) {
        Vec3 forward = observer.getViewVector(1.0F);
        if (forward.lengthSqr() <= 1.0E-9D) {
            return;
        }
        int range = renderDistanceBlocks(observer);
        captureRay(bot, observer, forward.normalize(), range, memory, tick);
        for (int ray = 1; ray < RAYS_PER_OBSERVER_TICK; ray++) {
            captureRay(bot, observer, viewportDirection(observer, memory.sweepCursor++), range, memory, tick);
        }
    }

    private static Vec3 viewportDirection(ServerPlayer observer, int sample) {
        Vec3 forward = observer.getViewVector(1.0F).normalize();
        Vec3 right = forward.cross(new Vec3(0.0D, 1.0D, 0.0D));
        if (right.lengthSqr() <= 1.0E-9D) {
            right = new Vec3(1.0D, 0.0D, 0.0D);
        } else {
            right = right.normalize();
        }
        Vec3 up = right.cross(forward).normalize();
        int gridSize = VIEW_GRID * VIEW_GRID;
        int index = Math.floorMod(sample, gridSize);
        int x = index % VIEW_GRID;
        int y = index / VIEW_GRID;
        double horizontal = ((x + 0.5D) / VIEW_GRID * 2.0D - 1.0D) * VIEW_TANGENT;
        double vertical = ((y + 0.5D) / VIEW_GRID * 2.0D - 1.0D) * VIEW_TANGENT;
        return forward.add(right.scale(horizontal)).add(up.scale(vertical)).normalize();
    }

    private static boolean captureTowards(AIPlayerEntity bot, ServerPlayer observer, Vec3 target,
                                          Memory memory, int tick) {
        Vec3 delta = target.subtract(observer.getEyePosition());
        if (delta.lengthSqr() <= 1.0E-9D) {
            return false;
        }
        double distance = Math.sqrt(delta.lengthSqr());
        int range = renderDistanceBlocks(observer);
        // A clipped endpoint at the range boundary could legitimately see a different block on
        // the same bearing, but it says nothing about the requested target beyond that boundary.
        // Leave it to the next locally eligible request instead of giving an out-of-range target
        // accidental route evidence.
        if (distance > range) {
            return false;
        }
        captureRay(bot, observer, delta.normalize(), distance, memory, tick);
        Observation observation = memory.cells.get(BlockPos.containing(target.x, target.y, target.z).asLong());
        return observation != null && observation.seenTick() == tick;
    }

    private static boolean shouldCaptureRouteEnvelope(Memory memory, BlockPos target, int tick) {
        return memory.lastRouteTarget != target.asLong()
                || (long) tick - memory.lastRouteEnvelopeTick >= ROUTE_ENVELOPE_REFRESH_TICKS;
    }

    /**
     * Samples the thin, direct walking lane from the bot's current cell toward an already
     * sight-proven target.  This is deliberately not an A* probe or a world scan: a lane cell is
     * retained only when the observer's own vanilla ray reaches it.  Long rays are segmented at
     * the RayGrid work-window boundary, so a lane may cover the observer's actual render range;
     * a bot that is farther away than that range still needs an already-seen bridge between the
     * two observers rather than inventing terrain the owner cannot see.
     */
    private static void captureRouteEnvelope(AIPlayerEntity bot, ServerPlayer observer, BlockPos target,
                                             Memory memory, int tick) {
        BlockPos from = bot.blockPosition();
        int dx = target.getX() - from.getX();
        int dy = target.getY() - from.getY();
        int dz = target.getZ() - from.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps == 0) {
            captureTargetNeighborhood(bot, observer, target, memory, tick);
            return;
        }
        // A target proven from the bot's own eye is necessarily within this many horizontal
        // cells. An owner can be elsewhere; in that case their own render radius is still the
        // farthest continuous terrain lane they are entitled to contribute.
        int boundedSteps = Math.min(steps, renderDistanceBlocks(observer));
        for (int index = 0; index <= boundedSteps; index++) {
            double fraction = (double) index / steps;
            BlockPos stance = new BlockPos(
                    (int) Math.floor(from.getX() + dx * fraction + 0.5D),
                    (int) Math.floor(from.getY() + dy * fraction + 0.5D),
                    (int) Math.floor(from.getZ() + dz * fraction + 0.5D));
            captureStanceEnvelope(bot, observer, stance, memory, tick);
        }
        if (!target.equals(from)) {
            captureTargetNeighborhood(bot, observer, target, memory, tick);
        }
    }

    /** A mined/used target usually occupies its own cell, so expose all immediately adjacent possible stances. */
    private static void captureTargetNeighborhood(AIPlayerEntity bot, ServerPlayer observer, BlockPos target,
                                                  Memory memory, int tick) {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                captureStanceEnvelope(bot, observer, target.offset(x, 0, z), memory, tick);
            }
        }
    }

    /** Own-ray proofs for the feet, Baritone headroom, and physical floor of one possible stance. */
    private static void captureStanceEnvelope(AIPlayerEntity bot, ServerPlayer observer, BlockPos stance,
                                              Memory memory, int tick) {
        for (int height = 0; height <= 3; height++) {
            captureTowards(bot, observer, stance.above(height).getCenter(), memory, tick);
        }
        BlockPos floor = stance.below();
        captureTowards(bot, observer,
                new Vec3(floor.getX() + 0.5D, floor.getY() + 0.999D, floor.getZ() + 0.5D), memory, tick);
    }

    /**
     * Records only cells established by the ray: its first hit, and the see-through cells it passed through (leaves, fences,
     * glass, water) with the state they really hold. Everything else it crossed is empty space; it never reads a block state
     * a ray did not reach.
     */
    private static void captureRay(AIPlayerEntity bot, ServerPlayer observer, Vec3 direction, double range,
                                   Memory memory, int tick) {
        if (!(range > 0.0D) || direction.lengthSqr() <= 1.0E-9D) {
            return;
        }
        Vec3 eye = observer.getEyePosition();
        Vec3 end = eye.add(direction.scale(range));
        var world = bot.level();
        int endChunkX = (int) Math.floor(end.x) >> 4;
        int endChunkZ = (int) Math.floor(end.z) >> 4;
        if (!observer.getChunkTrackingView().contains(endChunkX, endChunkZ)
                || !world.getChunkSource().hasChunk(endChunkX, endChunkZ)) {
            return;
        }
        // OUTLINE is the player's visible block shape, not just its collision volume.  A rail,
        // vine, torch, crop, or other non-colliding block is therefore remembered as that block
        // when it is the first thing the observer can actually see; treating it as AIR would
        // violate the shared-world-memory contract and corrupt later navigation evidence.
        // The observer's eye passes through foliage, fences, glass and water, so the ray goes on to the first opaque block
        // (or lava); the cells it skipped are remembered as what they are, never as free space Baritone could walk through.
        SightClipContext sight = SightClip.context(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, observer, null, true);
        BlockHitResult hit = world.clip(sight);
        List<SightClipContext.Crossing> crossed = sight.crossed();
        double distance = hit.getType() == HitResult.Type.BLOCK
                ? eye.distanceTo(hit.getLocation()) : range;
        // RayGrid intentionally has a conservative 256-block work window.  A player's render
        // distance can legitimately be larger, so walk a proven long ray in contiguous windows
        // rather than silently turning the remainder into unknown terrain.  The single vanilla
        // clip above has already established that each preceding window is empty.
        for (double offset = 0.0D; offset < distance; offset += RayGrid.MAX_RANGE) {
            double segment = Math.min(RayGrid.MAX_RANGE, distance - offset);
            Vec3 start = eye.add(direction.scale(offset));
            RayGrid.traverse(start.x, start.y, start.z, direction.x, direction.y, direction.z, segment,
                    (x, y, z) -> {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState state = hit.getType() == HitResult.Type.BLOCK && pos.equals(hit.getBlockPos())
                                ? world.getBlockState(pos) : SightClip.crossedState(crossed, pos.asLong());
                        remember(memory, new Observation(pos.asLong(), state != null ? state : AIR, tick));
                        return true;
                    });
        }
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            remember(memory, new Observation(pos.asLong(), world.getBlockState(pos), tick));
        }
    }

    private static void remember(Memory memory, Observation observation) {
        memory.cells.put(observation.packedPos(), observation);
        while (memory.cells.size() > MAX_CELLS_PER_BOT) {
            Iterator<Long> iterator = memory.cells.keySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            iterator.next();
            iterator.remove();
        }
    }

    private static void prune(Memory memory, int tick) {
        if (memory.lastPruneTick == tick) {
            return;
        }
        memory.lastPruneTick = tick;
        Iterator<Observation> iterator = memory.cells.values().iterator();
        while (iterator.hasNext()) {
            Observation observation = iterator.next();
            if ((long) tick - observation.seenTick() > MEMORY_TTL_TICKS) {
                iterator.remove();
            }
        }
    }
}

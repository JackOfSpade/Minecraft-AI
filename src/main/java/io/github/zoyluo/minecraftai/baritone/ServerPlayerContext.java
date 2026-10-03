package io.github.zoyluo.minecraftai.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.cache.IWorldData;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.IPlayerController;
import baritone.pathing.movement.movements.MovementParkour;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.behavior.LookBehavior;
import baritone.utils.accessor.IClientChunkProvider;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mining.assist.BotEdits;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.HitResult;

/**
 * Baritone's view of one of our bots: the server-side counterpart of upstream's client {@code BaritonePlayerContext}
 * (which wraps the local player, the client level and the client game mode and is not part of this build).
 *
 * <p>The player is a {@link Supplier} because a bot's entity can be replaced (respawn, dimension change); Baritone must
 * always act on the live one.</p>
 */
public final class ServerPlayerContext implements IPlayerContext {
    /**
     * Baritone snaps every rotation to the grid of angles a mouse at this sensitivity can produce. A bot has no mouse, so
     * ask for the finest grid (about 0.01 degree), which keeps aimed rays as exact as the raw rotation.
     */
    private static final double MOUSE_SENSITIVITY = 0.0D;

    /** Dropped items farther than this from the bot are not offered to Baritone's scans. */
    private static final double ENTITY_RANGE = 64.0D;
    /** Dropped items neither appear nor vanish faster than a bot can act on them: the list is rebuilt at most this often. */
    private static final int REFRESH_TICKS = 5;

    private final IBaritone baritone;
    private final Supplier<? extends AIPlayerEntity> bot;
    private final ServerPlayerController controller;
    /** Written on the server thread, read from any thread (see {@link #entities()}). */
    private volatile List<Entity> observedEntities = List.of();
    private int observedAtTick = -1;
    /** What this bot may do to the world; read by the cost model on the server thread and on workers. */
    private volatile BaritonePolicy policy = BaritonePolicy.UNRESTRICTED;
    /**
     * The only terrain snapshot Baritone is allowed to inspect for an active route. It is immutable
     * and replaced atomically, so search workers never see a partly-filled observation map.
     */
    private volatile ObservedNavigationFence observationFence = ObservedNavigationFence.empty();
    /** Bounded same-dimension memory retained between routes; it is never exposed to Baritone while idle. */
    private volatile ObservedNavigationFence observationMemory = ObservedNavigationFence.empty();
    /**
     * Whether the active route explicitly permits water traversal. This is route-scoped rather
     * than a global Baritone preference: a dry follower must not turn a visible water surface
     * into a legal shortcut merely because another bot is swimming.
     */
    private volatile boolean waterAllowed;
    /**
     * The one intentionally coordinate-aware navigation grant: a full-chunk snapshot captured on the server thread for a verified
     * owner-follow route. It exposes exact states only from chunks that were already FULL at capture time; it never loads a chunk
     * and, unlike a blanket allow flag, cannot fall through to Baritone's cached regions.
     */
    private volatile LoadedChunkSnapshot ownerFollowSnapshot;
    /**
     * A pre-execution fence refusal emitted by the vendored executor. The driver consumes it in
     * the same PRE tick and revokes the route before another executor can take over.
     */
    private volatile String navigationPathSafetyFailure;

    public ServerPlayerContext(IBaritone baritone, Supplier<? extends AIPlayerEntity> bot) {
        this.baritone = baritone;
        this.bot = bot;
        this.controller = new ServerPlayerController(bot);
    }

    @Override
    public MinecraftServer minecraft() {
        return bot.get().getServer();
    }

    @Override
    public AIPlayerEntity player() {
        return bot.get();
    }

    @Override
    public IPlayerController playerController() {
        return controller;
    }

    @Override
    public Level world() {
        return bot.get().level();
    }

    /**
     * The bot itself, the dropped items it can observe (the mine and farm processes look for {@code ItemEntity}s) and, while mob
     * avoidance is on, the hostile mobs it has noticed (Baritone's {@code Avoidance} reads mobs from this list and nowhere else). Observability is the mod's own rule
     * ({@link ObservableWorldQuery#canObserveEntity} for items, {@link ObservableWorldQuery#canNoticeCreature} for mobs): a strict-survival bot sees what is in range and in line of sight, a
     * bot with the hidden-scan privilege sees everything in range.
     *
     * <p>Called on the server thread this rebuilds the list if it is more than a few ticks old; called from a worker (the mine/farm rescans
     * run there) it returns the list the server thread last built, because a level's entity lookup must not be walked from
     * another thread. {@link #refreshEntities()} is what the tick pump calls so that list is never more than a moment old.</p>
     */
    @Override
    public Iterable<Entity> entities() {
        if (minecraft().isSameThread()) {
            refreshEntities();
        }
        return observedEntities;
    }

    /** Rebuilds the observable entity list unless it was built in the last few ticks. Server thread only; a call off-thread does nothing. */
    public void refreshEntities() {
        MinecraftServer server = minecraft();
        if (!server.isSameThread()) {
            return;
        }
        int tick = server.getTickCount();
        if (observedAtTick >= 0 && tick - observedAtTick < REFRESH_TICKS && tick >= observedAtTick) {
            return;
        }
        observedAtTick = tick;
        AIPlayerEntity self = player();
        List<Entity> observed = new ArrayList<>();
        observed.add(self);
        for (ItemEntity item : world().getEntitiesOfClass(ItemEntity.class, self.getBoundingBox().inflate(ENTITY_RANGE))) {
            if (ObservableWorldQuery.canObserveEntity(self, item)) {
                observed.add(item);
            }
        }
        if (BaritoneAPI.getSettings().avoidance.value) {
            // Baritone's mob avoidance (Avoidance#create) reads mobs from this very list, so it can only ever steer around what the
            // bot observes: a hostile mob in range and in line of sight (canNoticeCreature). Hostile only: a cow is not a threat, and
            // this is the whole cost of the feature (one entity query and a few rays per refresh, only while the bot is driven).
            int range = Math.max(1, MinecraftAiConfig.get().perception().radius());
            for (Mob mob : world().getEntitiesOfClass(Mob.class, self.getBoundingBox().inflate(range), mob -> mob instanceof Enemy && mob.isAlive())) {
                if (ObservableWorldQuery.canNoticeCreature(self, mob)) {
                    observed.add(mob);
                }
            }
        }
        observedEntities = List.copyOf(observed);
    }

    @Override
    public IWorldData worldData() {
        return baritone.getWorldProvider().getCurrentWorld();
    }

    @Override
    public BetterBlockPos viewerPos() {
        return playerFeet();
    }

    /**
     * Same as the interface default, but never blocks. The default reads the block under the player through {@code Level#getBlockState},
     * which on a server waits for the server thread when called from any other thread; Baritone calls this from worker threads
     * (world scans, path searches), and from a parallel stream that the server thread itself is waiting on, which deadlocks.
     */
    @Override
    public BetterBlockPos playerFeet() {
        AIPlayerEntity self = player();
        BetterBlockPos feet = new BetterBlockPos(self.getX(), self.getY() + 0.1251, self.getZ());
        BlockState footing = blockStateAt(feet);
        // Baritone's grid cell is the air cell above a partial surface the bot is standing on.
        // Its upstream slab correction already follows that rule. A snow layer has the same
        // shape of problem: a real player stands partway through it, but the observed layer's
        // collision surface—not an unseen block below it—is the navigation footing.
        // The state comes only from the active observation fence, so this never becomes a
        // hidden-terrain query while a planner worker is running.
        if (footing.getBlock() instanceof SlabBlock || footing.getBlock() instanceof SnowLayerBlock) {
            return feet.above();
        }
        return feet;
    }

    private BlockState blockStateAt(BlockPos pos) {
        // This method is called by playerFeet from both server and Baritone worker threads. It
        // therefore uses the same immutable evidence boundary as the planner rather than doing a
        // special direct chunk lookup that could leak a hidden slab/floor into navigation.
        ObservedNavigationFence fence = observationFence;
        BlockState observed = fenceMatchesCurrentDimension(fence) ? fence.stateAt(pos) : null;
        return observed == null ? Blocks.BEDROCK.defaultBlockState() : observed;
    }

    /** Installs an admitted route's immutable observed terrain. Server thread only. */
    public void setObservationFence(ObservedNavigationFence fence) {
        setObservationFence(fence, false);
    }

    /**
     * Installs a route boundary. A verified owner follow gets a fresh, immutable loaded-chunk snapshot in addition to its ordinary
     * observation fence; every other route clears that grant immediately.
     */
    public void setObservationFence(ObservedNavigationFence fence, boolean ownerFollow) {
        observationFence = java.util.Objects.requireNonNull(fence, "fence");
        observationMemory = fence;
        ownerFollowSnapshot = ownerFollow && player().level() instanceof net.minecraft.server.level.ServerLevel serverLevel
                ? LoadedChunkSnapshot.capture(serverLevel) : null;
        navigationPathSafetyFailure = null;
    }

    /** Applies the active route's water rule to both planner and executor snapshot reads. */
    public void setWaterAllowed(boolean allowed) {
        waterAllowed = allowed;
    }

    /** The active fence; safe to hand to the server-thread refresh boundary. */
    public ObservedNavigationFence observationFence() {
        return observationFence;
    }

    /** The last bounded observation memory, used to admit a later remembered route. */
    public ObservedNavigationFence observationMemory() {
        return observationMemory;
    }

    /** Stops every future planner/executor lookup immediately while retaining bounded evidence for a later route. */
    public void clearObservationFence() {
        observationFence = ObservedNavigationFence.empty();
        ownerFollowSnapshot = null;
        waterAllowed = false;
        navigationPathSafetyFailure = null;
    }

    /** Lifecycle boundary (death, dimension change, bot removal): no observation crosses it. */
    public void clearObservationMemory() {
        observationFence = ObservedNavigationFence.empty();
        observationMemory = ObservedNavigationFence.empty();
        ownerFollowSnapshot = null;
        waterAllowed = false;
        navigationPathSafetyFailure = null;
    }

    /**
     * Patch 0018 calls this before consulting a loaded chunk or Baritone's cache. The check is
     * pure and O(log n): it never touches a Level from a worker thread.
     */
    @Override
    public boolean allowNavigationCell(int x, int y, int z) {
        LoadedChunkSnapshot ownerSnapshot = ownerFollowSnapshot();
        if (ownerSnapshot != null) {
            // The paired navigationCellState() below supplies the state from this exact snapshot. Returning true only here makes
            // a missing/evicted column virtual bedrock before BlockStateInterface can consult its cached-region fallback.
            BlockState state = ownerSnapshot.stateAt(x, y, z);
            return navigationStateAllowed(state);
        }
        ObservedNavigationFence fence = observationFence;
        return fenceMatchesCurrentDimension(fence) && navigationStateAllowed(fence.stateAt(x, y, z));
    }

    /** Patch 0018 consumes this immutable state instead of re-reading a past observation from the live world. */
    @Override
    public BlockState navigationCellState(int x, int y, int z) {
        LoadedChunkSnapshot ownerSnapshot = ownerFollowSnapshot();
        if (ownerSnapshot != null) {
            // Never return null while a direct snapshot is active. The snapshot can be refreshed
            // between BlockStateInterface's state read and its allowNavigationCell check (and a
            // holder can disappear between hasCell and stateAt); a null in either case would let
            // BSI fall through to its provider/cache. Virtual bedrock keeps a stale or dry-water
            // cell blocked; allowNavigationCell independently rejects the latter so it can never
            // become imaginary walking support.
            BlockState state = ownerSnapshot.stateAt(x, y, z);
            return state == null || !navigationStateAllowed(state)
                    ? Blocks.BEDROCK.defaultBlockState() : state;
        }
        ObservedNavigationFence fence = observationFence;
        if (!fenceMatchesCurrentDimension(fence)) {
            return null;
        }
        BlockState state = fence.stateAt(x, y, z);
        // BlockStateInterface asks this method before its generic allowNavigationCell fallback.
        // Return virtual bedrock for a dry water cell as well as denying it there: either lookup
        // order therefore remains fail-closed, while canWalkOn sees the paired denial and cannot
        // use the virtual block as support.
        return state == null || navigationStateAllowed(state) ? state : Blocks.BEDROCK.defaultBlockState();
    }

    /** Water permission never makes lava navigable; dry routes additionally treat water as unavailable terrain. */
    private boolean navigationStateAllowed(BlockState state) {
        return state != null
                && !state.getFluidState().is(FluidTags.LAVA)
                && (waterAllowed || !state.getFluidState().is(FluidTags.WATER));
    }

    /**
     * Patch 0018 calls this on the server PRE tick before {@code PathExecutor} updates a movement
     * or its input handler can click. The pure navigator check covers an asynchronously installed
     * path as well as the inline admission path.
     */
    @Override
    public boolean allowNavigationPath(IPath path, int firstMovement) {
        if (navigationPathSafetyFailure != null) {
            return false;
        }
        String failure = BaritoneNavigator.observedExecutionPathSafetyFailure(player(), path, firstMovement);
        if (failure == null && !allowPathFootprints(path, firstMovement)) {
            // MovementParkour can jump over a short gap without declaring the cells beneath the
            // arc as a source, destination, or block action. Keep the executor fail-closed even
            // if a planner bug proposes a route whose swept body/support cells include lava or
            // another unavailable observation-fence cell. Dry routes also reject water through
            // the same navigation-cell predicate.
            failure = "navigation_route_cell_denied";
        }
        if (failure == null) {
            return true;
        }
        navigationPathSafetyFailure = failure;
        return false;
    }

    /**
     * Checks Baritone's one hidden-span movement. Ordinary movements expose their full cells to
     * the planner and executor callbacks; {@link MovementParkour} instead records no intermediate
     * cells even though it crosses a short cardinal arc. That arc must remain inside the admitted
     * observation fence; water-capable routes may cross water, but no route may cross lava.
     */
    private boolean allowPathFootprints(IPath path, int firstMovement) {
        if (path == null) {
            return true;
        }
        List<IMovement> movements = path.movements();
        int start = Math.max(0, firstMovement);
        for (int index = start; index < movements.size(); index++) {
            IMovement movement = movements.get(index);
            if (movement == null || (movement instanceof MovementParkour
                    && !allowParkourFootprint(movement.getSrc(), movement.getDest()))) {
                return false;
            }
        }
        return true;
    }

    /** MovementParkour is cardinal; malformed geometry is denied instead of being approximated as a diagonal. */
    private boolean allowParkourFootprint(BetterBlockPos source, BetterBlockPos destination) {
        if (source == null || destination == null) {
            return false;
        }
        int sourceX = source.getX();
        int sourceY = source.getY();
        int sourceZ = source.getZ();
        int destinationX = destination.getX();
        int destinationY = destination.getY();
        int destinationZ = destination.getZ();
        int deltaX = destinationX - sourceX;
        int deltaZ = destinationZ - sourceZ;
        int spanX = Math.abs(deltaX);
        int spanZ = Math.abs(deltaZ);
        int span = spanX + spanZ;
        if ((spanX == 0) == (spanZ == 0) || span < 2 || span > 4
                || (destinationY != sourceY && destinationY != sourceY + 1)) {
            return false;
        }
        int directionX = Integer.signum(deltaX);
        int directionZ = Integer.signum(deltaZ);
        for (int distance = 0; distance <= span; distance++) {
            if (!allowParkourColumn(sourceX + directionX * distance, sourceY,
                    sourceZ + directionZ * distance)) {
                return false;
            }
        }
        return true;
    }

    /** Same support/body/head envelope as patch 0019's MovementParkour cost check. */
    private boolean allowParkourColumn(int x, int y, int z) {
        return allowNavigationCell(x, y - 1, z)
                && allowNavigationCell(x, y, z)
                && allowNavigationCell(x, y + 1, z)
                && allowNavigationCell(x, y + 2, z);
    }

    /**
     * The final gate for an actual block click. Static movement footprints are checked earlier,
     * but a ray may select a dynamic support or an in-wall block that was not part of that list.
     */
    @Override
    public boolean allowNavigationActionCell(BlockPos pos) {
        if (navigationPathSafetyFailure != null) {
            return false;
        }
        if (pos != null && allowNavigationCell(pos.getX(), pos.getY(), pos.getZ())) {
            return true;
        }
        navigationPathSafetyFailure = "navigation_action_cell_unobserved";
        return false;
    }

    /** Returns and clears the pre-execution refusal recorded by {@link #allowNavigationPath}. Server thread only. */
    String consumeNavigationPathSafetyFailure() {
        String failure = navigationPathSafetyFailure;
        navigationPathSafetyFailure = null;
        return failure;
    }

    /** A same-coordinate snapshot must never survive a dimension change or external teleport. */
    private boolean fenceMatchesCurrentDimension(ObservedNavigationFence fence) {
        return fence != null && fence.dimension().equals(BotEdits.dimensionKey(player().level()));
    }

    /** The direct snapshot must not survive a level replacement even if teardown ordering is interrupted. */
    private LoadedChunkSnapshot ownerFollowSnapshot() {
        LoadedChunkSnapshot snapshot = ownerFollowSnapshot;
        return snapshot != null && player().level() instanceof net.minecraft.server.level.ServerLevel serverLevel
                && snapshot.belongsTo(serverLevel) ? snapshot : null;
    }

    /** The bot's break/place permission. Applies to the next plan (a running path re-validates its costs every tick). */
    public void setPolicy(BaritonePolicy policy) {
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
    }

    public BaritonePolicy policy() {
        return policy;
    }

    @Override
    public boolean allowBreak() {
        return policy.allowBreak();
    }

    @Override
    public boolean allowPlace() {
        return policy.allowPlace();
    }

    /**
     * The processes that pick their targets by scanning the loaded world (mine, get-to-block, farm, explore, build) start only for
     * a bot that holds the hidden-scan privilege, which strict survival never grants (patch 0015, {@link BaritoneBreakPlacePolicy}).
     */
    @Override
    public boolean allowScanningProcess(String process) {
        return BaritoneBreakPlacePolicy.allowScanningProcess(player(), process);
    }

    /**
     * Whether the bot may plan a fall broken by a water bucket (patch 0017): the switch (Baritone's setting, which follows
     * {@code nav.baritone.waterBucketFall}) and a place where water does not evaporate. Baritone itself only excludes the Nether; the
     * bucket click would be refused anywhere else water evaporates ({@link BaritoneWaterFall}), and a fall planned on it would be a fall to damage.
     */
    @Override
    public boolean allowWaterBucketFall() {
        if (!BaritoneAPI.getSettings().allowWaterBucketFall.value) {
            return false;
        }
        AIPlayerEntity self = player();
        return !minecraft().isSameThread() || !BaritoneWaterFall.waterEvaporates(self, self.blockPosition());
    }

    @Override
    public double mouseSensitivity() {
        return MOUSE_SENSITIVITY;
    }

    @Override
    public Rotation playerRotations() {
        return ((LookBehavior) baritone.getLookBehavior()).getEffectiveRotation().orElseGet(IPlayerContext.super::playerRotations);
    }

    @Override
    public HitResult objectMouseOver() {
        return RayTraceUtils.rayTraceTowards(player(), playerRotations(), playerController().getBlockReachDistance());
    }
}

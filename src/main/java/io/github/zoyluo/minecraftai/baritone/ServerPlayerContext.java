package io.github.zoyluo.minecraftai.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.cache.IWorldData;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.IPlayerController;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.behavior.LookBehavior;
import baritone.utils.accessor.IClientChunkProvider;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
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
     * avoidance is on, the hostile mobs it can observe (Baritone's {@code Avoidance} reads mobs from this list and nowhere else). Observability is the mod's own rule
     * ({@link ObservableWorldQuery#canObserveEntity}): a strict-survival bot sees what is in range and in line of sight, a
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
            // bot observes: a hostile mob in range and in line of sight (canObserveEntity). Hostile only: a cow is not a threat, and
            // this is the whole cost of the feature (one entity query and a few rays per refresh, only while the bot is driven).
            int range = Math.max(1, MinecraftAiConfig.get().perception().radius());
            for (Mob mob : world().getEntitiesOfClass(Mob.class, self.getBoundingBox().inflate(range), mob -> mob instanceof Enemy && mob.isAlive())) {
                if (ObservableWorldQuery.canObserveEntity(self, mob)) {
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
        if (blockStateAt(feet).getBlock() instanceof SlabBlock) {
            return feet.above();
        }
        return feet;
    }

    private BlockState blockStateAt(BlockPos pos) {
        Level level = world();
        if (minecraft().isSameThread()) {
            return level.getBlockState(pos);
        }
        LevelChunk chunk = ((IClientChunkProvider) level.getChunkSource()).createThreadSafeCopy().getChunk(pos.getX() >> 4, pos.getZ() >> 4, false);
        return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(pos);
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

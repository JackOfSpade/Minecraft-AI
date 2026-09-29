package io.github.zoyluo.minecraftai.baritone;

import baritone.api.IBaritone;
import baritone.api.cache.IWorldData;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.IPlayerController;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.behavior.LookBehavior;
import baritone.utils.accessor.IClientChunkProvider;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
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

    private final IBaritone baritone;
    private final Supplier<? extends AIPlayerEntity> bot;
    private final ServerPlayerController controller;

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

    @Override
    public Iterable<Entity> entities() {
        return ((ServerLevel) world()).getAllEntities();
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

package io.github.zoyluo.minecraftai.autosprint;

import baritone.api.IBaritone;
import baritone.api.cache.IWorldData;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.IPlayerController;
import io.github.zoyluo.minecraftai.baritone.LoadedChunkSnapshot;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;

/** Server-side Baritone view for one auto-sprinting human player. */
public final class AutoSprintPlayerContext implements IPlayerContext {
    private static final double MOUSE_SENSITIVITY = 0.0D;

    private final IBaritone baritone;
    private final ServerPlayer player;
    private final AutoSprintPlayerController controller;
    /** Updated on the server thread before each Baritone pre-tick and read safely by search workers. */
    private volatile LoadedChunkSnapshot terrain = null;

    public AutoSprintPlayerContext(IBaritone baritone, ServerPlayer player) {
        this.baritone = baritone;
        this.player = player;
        this.controller = new AutoSprintPlayerController(player);
    }

    /** Refreshes the never-loading terrain snapshot Baritone is allowed to search. Server thread only. */
    void refreshTerrain() {
        terrain = player.level() instanceof ServerLevel level ? LoadedChunkSnapshot.capture(level) : null;
    }

    @Override
    public MinecraftServer minecraft() {
        return player.level().getServer();
    }

    @Override
    public ServerPlayer player() {
        return player;
    }

    @Override
    public IPlayerController playerController() {
        return controller;
    }

    @Override
    public Level world() {
        return player.level();
    }

    @Override
    public Iterable<Entity> entities() {
        // This navigation mode does not scan or target entities. Its only concern is safe terrain movement.
        return List.of(player);
    }

    @Override
    public IWorldData worldData() {
        return baritone.getWorldProvider().getCurrentWorld();
    }

    @Override
    public HitResult objectMouseOver() {
        // All click-based actions are disabled for this context, so no synthetic crosshair target is needed.
        return null;
    }

    @Override
    public BetterBlockPos viewerPos() {
        return playerFeet();
    }

    @Override
    public BetterBlockPos playerFeet() {
        // Do not read a live block here: this method is also called by Baritone's worker thread.
        return new BetterBlockPos(player.getX(), player.getY() + 0.1251D, player.getZ());
    }

    @Override
    public double mouseSensitivity() {
        return MOUSE_SENSITIVITY;
    }

    @Override
    public boolean allowBreak() {
        return false;
    }

    @Override
    public boolean allowPlace() {
        return false;
    }

    @Override
    public boolean allowWaterBucketFall() {
        return false;
    }

    @Override
    public BlockState navigationCellState(int x, int y, int z) {
        LoadedChunkSnapshot snapshot = terrain;
        return snapshot == null || !(player.level() instanceof ServerLevel level) || !snapshot.belongsTo(level)
                ? null : snapshot.stateAt(x, y, z);
    }

    @Override
    public boolean allowNavigationCell(int x, int y, int z) {
        LoadedChunkSnapshot snapshot = terrain;
        return snapshot != null && player.level() instanceof ServerLevel level && snapshot.belongsTo(level)
                && snapshot.hasCell(x, y, z);
    }

    @Override
    public BlockState navigationCellStateForNavigation(net.minecraft.core.BlockPos pos) {
        BlockState state = navigationCellState(pos.getX(), pos.getY(), pos.getZ());
        return state == null ? Blocks.BEDROCK.defaultBlockState() : state;
    }
}

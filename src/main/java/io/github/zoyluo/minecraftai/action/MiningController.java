package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogFields;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistHooks;
import io.github.zoyluo.minecraftai.pathfinding.AStarPathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class MiningController {
    private static final int MAX_TICKS = 600;

    private final BlockPos pos;
    private final Direction face;
    /** The caller aims and picks the tool itself (see {@link #driven}); this controller only runs the break. */
    private final boolean driven;
    private boolean started;
    private BlockState targetState;
    private float progress;
    private int elapsed;

    public MiningController(BlockPos pos, Direction face) {
        this(pos, face, false);
    }

    private MiningController(BlockPos pos, Direction face, boolean driven) {
        this.pos = pos;
        this.face = face;
        this.driven = driven;
    }

    /**
     * A break run for a movement driver that owns the bot's aim and its hotbar (Baritone): it has already turned the bot toward
     * the block, checked with its own ray that the block is what the bot is looking at, and equipped the tool (the mod's tool policy,
     * ToolSelector, which its cost model prices the break with too). Re-aiming at the face center here would fight the driver's rotation every tick, and re-selecting
     * by this class's own tool score could swap the tool under it, so a driven controller does neither; everything else (the
     * vanilla START/STOP/ABORT handshake, progress, reach, timeout, cache invalidation, the assist hook) is identical.
     */
    public static MiningController driven(BlockPos pos, Direction face) {
        return new MiningController(pos, face, true);
    }

    /** The cell this controller is (or was) mining. Lets a caller react once it finishes. */
    public BlockPos pos() {
        return pos;
    }

    /**
     * The block state captured when mining started, i.e. before the break. Stays set after a
     * successful break (the world cell is air by then), so a caller can log what was actually
     * destroyed. Returns null if mining never actually started (e.g. it failed out of reach).
     */
    public BlockState brokenBlockState() {
        return targetState;
    }

    /**
     * Ticks spent actively breaking this cell, including the tick that completed the break ({@code elapsed}
     * itself only counts the ticks that were still in progress). Valid once {@link #tick} has returned success.
     */
    public int elapsedTicks() {
        return elapsed + 1;
    }

    public ActionResult tick(ActionPack pack) {
        AIPlayerEntity player = pack.player();
        var world = player.level();
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            resetProgress(player);
            return ActionResult.SUCCESS;
        }
        if (targetState != null && !state.equals(targetState)) {
            resetProgress(player);
        }

        if (!driven) {
            LookAction.lookAtBlock(player, pos, face);
        }
        double reach = player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
        if (player.getEyePosition().distanceTo(pos.getCenter()) > reach + 0.5D) {
            abort(player);
            return ActionResult.failed("out_of_reach");
        }

        if (!started) {
            if (driven) {
                BotLog.action(player, "mine_start", "pos", LogFields.pos(pos), "face", face, "driver", "baritone");
            } else {
                ToolSelector.equipBestTool(player, state);
                BotLog.action(player, "mine_start", "pos", LogFields.pos(pos), "face", face);
            }
            player.gameMode.handleBlockBreakAction(
                    pos,
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    face,
                    Level.MAX_ENTITY_SPAWN_Y,
                    -1);
            state.attack(world, pos, player);
            started = true;
            targetState = state;
        }

        progress += state.getDestroyProgress(player, world, pos);
        world.destroyBlockProgress(player.getId(), pos, Math.min(9, (int) (progress * 10.0F)));
        player.swing(InteractionHand.MAIN_HAND);
        player.resetLastActionTime();

        if (progress >= 1.0F) {
            player.gameMode.handleBlockBreakAction(
                    pos,
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    face,
                    Level.MAX_ENTITY_SPAWN_Y,
                    -1);
            world.destroyBlockProgress(player.getId(), pos, -1);
            AStarPathfinder.invalidateCache("block_break");
            MiningAssistHooks.onBotBreak(player, pos);
            return ActionResult.SUCCESS;
        }

        elapsed++;
        if (elapsed > MAX_TICKS) {
            abort(player);
            return ActionResult.failed("timeout");
        }
        return ActionResult.IN_PROGRESS;
    }

    public void abort(AIPlayerEntity player) {
        if (!started) {
            return;
        }
        player.gameMode.handleBlockBreakAction(
                pos,
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                face,
                Level.MAX_ENTITY_SPAWN_Y,
                -1);
        player.level().destroyBlockProgress(player.getId(), pos, -1);
        started = false;
        targetState = null;
        progress = 0.0F;
        elapsed = 0;
    }

    private void resetProgress(AIPlayerEntity player) {
        if (started) {
            player.gameMode.handleBlockBreakAction(
                    pos,
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                    face,
                    Level.MAX_ENTITY_SPAWN_Y,
                    -1);
        }
        player.level().destroyBlockProgress(player.getId(), pos, -1);
        started = false;
        targetState = null;
        progress = 0.0F;
        elapsed = 0;
    }
}

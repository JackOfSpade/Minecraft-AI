package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogFields;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistHooks;
import io.github.zoyluo.minecraftai.pathfinding.AStarPathfinder;
import net.minecraft.block.BlockState;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

public final class MiningController {
    private static final int MAX_TICKS = 600;

    private final BlockPos pos;
    private final Direction face;
    private boolean started;
    private BlockState targetState;
    private float progress;
    private int elapsed;

    public MiningController(BlockPos pos, Direction face) {
        this.pos = pos;
        this.face = face;
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
        var world = player.getEntityWorld();
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            resetProgress(player);
            return ActionResult.SUCCESS;
        }
        if (targetState != null && !state.equals(targetState)) {
            resetProgress(player);
        }

        LookAction.lookAtBlock(player, pos, face);
        double reach = player.getAttributeValue(EntityAttributes.BLOCK_INTERACTION_RANGE);
        if (player.getEyePos().distanceTo(pos.toCenterPos()) > reach + 0.5D) {
            abort(player);
            return ActionResult.failed("out_of_reach");
        }

        if (!started) {
            ToolSelector.equipBestTool(player, state);
            BotLog.action(player, "mine_start", "pos", LogFields.pos(pos), "face", face);
            player.interactionManager.processBlockBreakingAction(
                    pos,
                    PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
                    face,
                    World.MAX_Y,
                    -1);
            state.onBlockBreakStart(world, pos, player);
            started = true;
            targetState = state;
        }

        progress += state.calcBlockBreakingDelta(player, world, pos);
        world.setBlockBreakingInfo(player.getId(), pos, Math.min(9, (int) (progress * 10.0F)));
        player.swingHand(Hand.MAIN_HAND);
        player.updateLastActionTime();

        if (progress >= 1.0F) {
            player.interactionManager.processBlockBreakingAction(
                    pos,
                    PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
                    face,
                    World.MAX_Y,
                    -1);
            world.setBlockBreakingInfo(player.getId(), pos, -1);
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
        player.interactionManager.processBlockBreakingAction(
                pos,
                PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK,
                face,
                World.MAX_Y,
                -1);
        player.getEntityWorld().setBlockBreakingInfo(player.getId(), pos, -1);
        started = false;
        targetState = null;
        progress = 0.0F;
        elapsed = 0;
    }

    private void resetProgress(AIPlayerEntity player) {
        if (started) {
            player.interactionManager.processBlockBreakingAction(
                    pos,
                    PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK,
                    face,
                    World.MAX_Y,
                    -1);
        }
        player.getEntityWorld().setBlockBreakingInfo(player.getId(), pos, -1);
        started = false;
        targetState = null;
        progress = 0.0F;
        elapsed = 0;
    }
}

package io.github.zoyluo.minecraftai.autosprint;

import baritone.api.utils.IPlayerController;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Baritone's controller facade for an auto-sprinting real player.
 *
 * <p>Auto sprint is navigation only. It must never break, place, use, or move inventory items on the player's behalf,
 * so every world-editing controller method is a no-op/refusal. The corresponding context also disables those movement
 * capabilities while planning.</p>
 */
final class AutoSprintPlayerController implements IPlayerController {
    private final ServerPlayer player;

    AutoSprintPlayerController(ServerPlayer player) {
        this.player = player;
    }

    @Override
    public void syncHeldItem() {
        // The server inventory is authoritative and auto sprint never changes it.
    }

    @Override
    public boolean hasBrokenBlock() {
        return true;
    }

    @Override
    public boolean onPlayerDamageBlock(BlockPos pos, Direction side) {
        return false;
    }

    @Override
    public void resetBlockRemoving() {
        // No break can have been started by this controller.
    }

    @Override
    public void windowClick(int windowId, int slotId, int mouseButton, ClickType type, Player player) {
        // Navigation is not allowed to touch a real player's inventory.
    }

    @Override
    public GameType getGameType() {
        return player.gameMode.getGameModeForPlayer();
    }

    @Override
    public InteractionResult processRightClickBlock(Player player, Level world, InteractionHand hand, BlockHitResult result) {
        return InteractionResult.FAIL;
    }

    @Override
    public InteractionResult processRightClick(Player player, Level world, InteractionHand hand) {
        return InteractionResult.FAIL;
    }

    @Override
    public boolean clickBlock(BlockPos loc, Direction face) {
        return false;
    }

    @Override
    public void setHittingBlock(boolean hittingBlock) {
        // No break can have been started by this controller.
    }
}

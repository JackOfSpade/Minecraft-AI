package io.github.zoyluo.minecraftai.baritone;

import baritone.api.utils.IPlayerController;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.MiningController;
import io.github.zoyluo.minecraftai.action.ToolSelector;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogFields;
import io.github.zoyluo.minecraftai.task.ShieldGuard;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Server-side counterpart of upstream's client {@code BaritonePlayerController} (which drives the local
 * {@code MultiPlayerGameMode} and is not part of this build).
 *
 * <p>Breaking goes through the mod's {@link MiningController}, the single place that performs the vanilla
 * START/STOP/ABORT_DESTROY_BLOCK handshake for a bot and keeps the path cache and the mining-assist bookkeeping current
 * (in its {@linkplain MiningController#driven driven} form: Baritone has already aimed and chosen the tool). Placing and
 * every other right click on a block goes through {@link BuildAction#useItemOnHit}, the mod's single click-placement primitive.
 * Every completed break and every placement is also written to {@link BaritoneEdits}. Baritone's {@code BlockBreakHelper} drives this class
 * exactly like it drives the client controller: {@code clickBlock} to start, {@code onPlayerDamageBlock} once per tick
 * until {@link #hasBrokenBlock()} turns true again.</p>
 *
 * <p>Nothing here acts without asking {@link BaritoneBreakPlacePolicy} first: every break ({@code clickBlock}), every click on
 * a block ({@code processRightClickBlock}), every item use without a block ({@code processRightClick}) and every inventory move
 * ({@code windowClick}) is checked, and a refusal is logged and recorded in {@link BaritoneRefusals} and reported to Baritone as
 * "did not happen". Baritone's cost model is fed the block-level part of the same rules so it plans around what would be refused.</p>
 */
public final class ServerPlayerController implements IPlayerController {
    private final Supplier<? extends AIPlayerEntity> bot;
    private MiningController mining;
    /** The state of the cell being broken when the rules were last applied to it. */
    private BlockState checkedState;
    /** Mirrors {@code MultiPlayerGameMode#isDestroying}: Baritone toggles it around each tick, see BlockBreakHelper. */
    private boolean hitting;

    ServerPlayerController(Supplier<? extends AIPlayerEntity> bot) {
        this.bot = bot;
    }

    @Override
    public void syncHeldItem() {
        // The server inventory is authoritative; there is no client copy to bring up to date.
    }

    @Override
    public boolean hasBrokenBlock() {
        return !hitting;
    }

    @Override
    public boolean onPlayerDamageBlock(BlockPos pos, Direction side) {
        if (mining == null || !mining.pos().equals(pos)) {
            return clickBlock(pos, side);
        }
        return step();
    }

    @Override
    public void resetBlockRemoving() {
        if (mining != null) {
            mining.abort(bot.get());
            mining = null;
        }
        hitting = false;
    }

    @Override
    public void windowClick(int windowId, int slotId, int mouseButton, ClickType type, Player player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu.containerId != windowId) {
            return;
        }
        if (!(player instanceof AIPlayerEntity self) || !BaritoneBreakPlacePolicy.checkWindowClick(self, windowId, slotId, mouseButton, type).allowed()) {
            return;
        }
        menu.clicked(slotId, mouseButton, type, player);
        menu.broadcastChanges();
    }

    @Override
    public GameType getGameType() {
        return bot.get().gameMode.getGameModeForPlayer();
    }

    @Override
    public InteractionResult processRightClickBlock(Player player, Level world, InteractionHand hand, BlockHitResult result) {
        AIPlayerEntity self = bot.get();
        if (ShieldGuard.usingShield(self)) {
            return InteractionResult.PASS;
        }
        if (BaritoneBreakPlacePolicy.allowsFallBucketBlockPass(self, result, hand)) {
            // A bucket does nothing on a block: its use is the item use that follows (processRightClick, checked by BaritoneWaterFall).
            // The empty other hand of the same click has nothing to place either. Neither is a refusal.
            return InteractionResult.PASS;
        }
        BaritoneBreakPlacePolicy.Decision click = BaritoneBreakPlacePolicy.checkClickBlock(self, result, hand);
        if (!click.allowed()) {
            return InteractionResult.FAIL;
        }
        String item = BuiltInRegistries.ITEM.getKey(self.getItemInHand(hand).getItem()).toString();
        BuildAction.Use use = BuildAction.useItemOnHit(self, result, hand, click.placementState());
        if (use.placed()) {
            BaritoneEdits.record(self, new BaritoneEdits.Edit(BaritoneEdits.Kind.PLACE, use.destination().immutable(), item, item, 0,
                    self.getServer().getTickCount()));
            // The checked placement supplied this exact throwaway-block result. Publish that
            // provenance fact only; a successful click never authorises a second live scan of
            // its destination or any neighbour.
            BaritoneRegistry.INSTANCE.recordObservedPlacement(self, use.destination(), use.placementState());
        }
        return use.result();
    }

    @Override
    public InteractionResult processRightClick(Player player, Level world, InteractionHand hand) {
        AIPlayerEntity self = bot.get();
        if (ShieldGuard.usingShield(self)) {
            return InteractionResult.PASS;
        }
        if (!BaritoneBreakPlacePolicy.checkUseItem(self, hand).allowed()) {
            return InteractionResult.FAIL;
        }
        BotLog.action(self, "baritone_use_item", "item", BuiltInRegistries.ITEM.getKey(self.getItemInHand(hand).getItem()));
        Item used = self.getItemInHand(hand).getItem();
        if (used == Items.WATER_BUCKET) {
            HitResult ray = self.pick(self.blockInteractionRange(), 1.0F, false);
            if (ray instanceof BlockHitResult block) {
                boolean[] wasSource = BaritoneWaterFall.observedSourceStates(self, block);
                if (wasSource == null) {
                    return InteractionResult.FAIL;
                }
                InteractionResult result = self.gameMode.useItem(self, world, self.getItemInHand(hand), hand);
                BaritoneWaterFall.afterWaterBucketUse(self, block, wasSource);
                return result;
            }
        }
        InteractionResult result = self.gameMode.useItem(self, world, self.getItemInHand(hand), hand);
        if (used == Items.BUCKET) {
            BaritoneWaterFall.afterEmptyBucketUse(self);
        }
        return result;
    }

    @Override
    public boolean clickBlock(BlockPos loc, Direction face) {
        if (mining != null) {
            mining.abort(bot.get());
            mining = null;
        }
        hitting = false;
        if (!BaritoneBreakPlacePolicy.checkBreak(bot.get(), loc).allowed()) {
            return false;
        }
        AIPlayerEntity self = bot.get();
        BlockState target = self.level().getBlockState(loc);
        // The tool is chosen here, once, when the break starts, by the mod's own policy (never per tick: a running break keeps
        // its tool, see MiningController#driven). Baritone's own auto-tool is off (BaritoneSettings: assumeExternalAutoTool).
        ToolSelector.equipBestTool(self, target, false);
        mining = MiningController.driven(loc, face);
        checkedState = target;
        return step();
    }

    @Override
    public void setHittingBlock(boolean hittingBlock) {
        this.hitting = hittingBlock;
    }

    /** One tick of the current break: true while it goes on or just finished, false when it cannot be done. */
    private boolean step() {
        AIPlayerEntity self = bot.get();
        // Re-prove this exact cell before every current-state read. A worker path may still be
        // valid while a live block is replaced, covered, or leaves the bot's view between ticks.
        if (!BaritoneBreakPlacePolicy.checkBreak(self, mining.pos()).allowed()) {
            mining.abort(self);
            mining = null;
            hitting = false;
            return false;
        }
        BlockState now = self.level().getBlockState(mining.pos());
        if (!now.equals(checkedState)) {
            // The cell changed under the break (another block, a door swinging, a flow): the rules apply to what is there now.
            checkedState = now;
        }
        ActionResult result = mining.tick(self.getActionPack());
        if (result.isSuccess()) {
            recordBreak(self, mining);
            mining = null;
            hitting = false; // hasBrokenBlock() is true again: the block is gone
            return true;
        }
        if (result == ActionResult.IN_PROGRESS) {
            hitting = true;
            return true;
        }
        mining = null;
        hitting = false;
        return false;
    }

    /** The break audit line and the ledger entry; a cell that was already empty when the controller looked is not a break of ours. */
    private static void recordBreak(AIPlayerEntity self, MiningController finished) {
        BlockState broken = finished.brokenBlockState();
        if (broken == null) {
            return;
        }
        String block = BuiltInRegistries.BLOCK.getKey(broken.getBlock()).toString();
        ItemStack held = self.getMainHandItem();
        String tool = held.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        BotLog.action(self, "mine_complete", "block", block, "pos", LogFields.pos(finished.pos()), "tool", tool,
                "ticks", finished.elapsedTicks(), "driver", "baritone");
        BaritoneEdits.recordBreak(self, finished.pos(), block, tool, finished.elapsedTicks());
    }
}

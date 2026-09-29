package io.github.zoyluo.minecraftai.baritone;

import baritone.api.utils.IPlayerController;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.MiningController;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogFields;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

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
 * <p>Deciding <em>whether</em> a block may be broken or placed (strict-survival observability, protected blocks) is not
 * done here: Baritone's own settings and cost model decide what to plan, and the layer that hands Baritone its goals
 * decides what is allowed.</p>
 */
public final class ServerPlayerController implements IPlayerController {
    private final Supplier<? extends AIPlayerEntity> bot;
    private MiningController mining;
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
        String item = BuiltInRegistries.ITEM.getKey(self.getItemInHand(hand).getItem()).toString();
        BuildAction.Use use = BuildAction.useItemOnHit(self, result, hand);
        if (use.placed()) {
            BaritoneEdits.record(self, new BaritoneEdits.Edit(BaritoneEdits.Kind.PLACE, use.destination().immutable(), item, item, 0,
                    self.getServer().getTickCount()));
        }
        return use.result();
    }

    @Override
    public InteractionResult processRightClick(Player player, Level world, InteractionHand hand) {
        AIPlayerEntity self = bot.get();
        return self.gameMode.useItem(self, world, self.getItemInHand(hand), hand);
    }

    @Override
    public boolean clickBlock(BlockPos loc, Direction face) {
        if (mining != null) {
            mining.abort(bot.get());
        }
        mining = MiningController.driven(loc, face);
        return step();
    }

    @Override
    public void setHittingBlock(boolean hittingBlock) {
        this.hitting = hittingBlock;
    }

    /** One tick of the current break: true while it goes on or just finished, false when it cannot be done. */
    private boolean step() {
        AIPlayerEntity self = bot.get();
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
        BaritoneEdits.record(self, new BaritoneEdits.Edit(BaritoneEdits.Kind.BREAK, finished.pos().immutable(), block, tool,
                finished.elapsedTicks(), self.getServer().getTickCount()));
    }
}

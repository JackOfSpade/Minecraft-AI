package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.action.MaterialPalette;
import io.github.zoyluo.minecraftai.action.PickupReach;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;

/**
 * What a bot standing on its pillar does about the item the block it has just broken gave, before it takes the
 * tower down and can no longer get to it. An item that falls into the pillar's column lands on the bot, and the
 * watch only waits for it (a block's drop cannot be collected for ten ticks, and the next block of the descent would
 * drop the bot out of range). One that comes to rest on a ledge above the pillar's head is hidden from there by the
 * very block it lies on, so the pillar is built up to the level of the block that was broken and the bot looks from
 * there. A leaf hides nothing from eyes that see through foliage: an item on a leaf is seen at once, and is let fall by
 * breaking the leaf (it can reach the leaf from the pillar even when the item is a block or two to the side, and when
 * it cannot, the bot builds up to the level of the break first, as it does for an item it cannot see); one that lies on
 * anything else is left, and picked up from below if it ever comes within reach of the floor.
 *
 * <p>Nothing is read that the bot has not seen: the item is an entity in sight, the leaf a cell it observes and can
 * reach, and the climb stays in the pillar's own column, up to the supports the bot carries beyond the tower's.</p>
 */
final class TowerDropWatch {
    /** The block a pillar route keeps in hand over what it needs to climb. */
    private static final int SUPPORT_CUSHION = 1;

    private final BlockPos broken;
    private final long brokeAt;
    private final Set<Item> items;
    private final String event;
    private int ticksLeft;
    private boolean looked;
    private boolean climbing;
    private BlockPos restedAt;

    /**
     * {@code window} is how many ticks the bot may stay up on its tower for the item (the owner's own pickup window,
     * which is not spent: the pickup that follows still has all of it). {@code event} is the prefix of the events
     * logged: {@code mine} or {@code gather}.
     */
    TowerDropWatch(BlockPos broken, long brokeAt, Set<Item> items, int window, String event) {
        this.broken = broken.immutable();
        this.brokeAt = brokeAt;
        this.items = items;
        this.ticksLeft = window;
        this.event = event;
    }

    /**
     * True while the bot should stay up on its tower: the item is falling or about to be collected, a climb or the
     * break of a leaf is under way, or the time an item takes to fall has not yet passed; false once the window is
     * over or there is nothing more to wait for or do from up here. Each call that holds counts against the window.
     */
    boolean hold(AIPlayerEntity bot) {
        if (ticksLeft-- <= 0) {
            return false;
        }
        if (climbing) {
            if (!bot.getActionPack().isPathExecutorIdle() || !bot.onGround()) {
                return true;
            }
            climbing = false;
        }
        if (!bot.getActionPack().isMiningIdle()) {
            return true;
        }
        long sinceBreak = bot.level().getGameTime() - brokeAt;
        Optional<ItemEntity> seen = HarvestCore.nearestDropAnyOf(bot, items, 8.0D,
                drop -> drop.getAge() <= sinceBreak + 3);
        if (seen.isPresent()) {
            ItemEntity drop = seen.get();
            boolean supported = HarvestCore.isDropPhysicallySupported(bot, drop);
            if (supported) {
                restedAt = drop.blockPosition().immutable();
            }
            if (!supported || HarvestCore.canCollectNow(bot, drop)) {
                return true;
            }
            BlockPos leaf = observedLeafUnder(bot, drop);
            if (leaf == null) {
                return false; // it lies on anything else (or on a leaf the bot does not see): left, as it always was
            }
            if (HarvestCore.canReach(bot, leaf)) {
                return breakLeaf(bot, leaf, event, broken);
            }
            // The eyes see the item through the foliage, but the leaf under it is farther than an arm from the head of the
            // pillar: the one climb to the level of the break (the same an unseen item gets) may bring it within reach.
            return !looked && climbToLook(bot);
        }
        if (looked) {
            return false;
        }
        double fall = Math.max(0, broken.getY() - bot.blockPosition().getY());
        if (sinceBreak <= PickupReach.settledTicks(fall)) {
            return true;
        }
        return climbToLook(bot);
    }

    /**
     * The cell the item was last seen lying in, or null when the watch never saw it at rest. The tower comes down before the
     * item is picked up, and from the floor it can be out of sight (the bottom of a hole beside the tower is seen only from
     * above): the bot remembers where it saw it come to rest.
     */
    BlockPos restedAt() {
        return restedAt;
    }

    /**
     * Lets the item fall by breaking the leaf it rests on, when the bot sees that leaf and can reach it, as a player does for an
     * item stuck in a canopy. The eyes see an item through the leaves under it, so this is asked whether the bot stands on a tower or
     * not. {@code event} is the prefix of the event logged ({@code gather} or {@code mine}), {@code broken} the cell the
     * item's block was broken in. True while the bot should stay: the leaf's break started (or waits for a guarded step).
     */
    static boolean releaseLeafUnder(AIPlayerEntity bot, ItemEntity drop, String event, BlockPos broken) {
        BlockPos leaf = observedLeafUnder(bot, drop);
        return leaf != null && HarvestCore.canReach(bot, leaf) && breakLeaf(bot, leaf, event, broken);
    }

    /** The cell the item lies on, when the bot observes that cell and sees a leaf in it; null for anything else. */
    private static BlockPos observedLeafUnder(AIPlayerEntity bot, ItemEntity drop) {
        BlockPos rest = HarvestCore.restingOn(drop);
        return ObservableWorldQuery.canObserveCell(bot, rest) && bot.level().getBlockState(rest).is(BlockTags.LEAVES)
                ? rest : null;
    }

    private static boolean breakLeaf(AIPlayerEntity bot, BlockPos rest, String event, BlockPos broken) {
        ActionResult started = HarvestCore.startMining(bot, rest);
        if (started.isFailed()) {
            return ActionPack.GUARDED_STEP_FENCE.equals(started.reason()); // an unstarted retry, not a refusal
        }
        BotLog.action(bot, event + "_drop_released", "origin", broken.toShortString(), "leaf", rest.toShortString());
        return true;
    }

    /** The one climb a watch makes, to the level of the break; it is not asked for twice, whatever came of it. */
    private boolean climbToLook(AIPlayerEntity bot) {
        looked = true;
        int spare = MaterialPalette.countPillarSupportBlocks(bot) - SUPPORT_CUSHION;
        BlockPos goal = HarvestCore.lookClimbGoal(bot, broken, spare);
        if (goal == null) {
            return false;
        }
        bot.getActionPack().stopAll();
        ActionResult route = bot.getActionPack().startPillarPathTo(goal);
        if (route.isFailed()) {
            if (ActionPack.GUARDED_STEP_FENCE.equals(route.reason())) {
                looked = false; // an unstarted retry, not a refusal of this climb
                return true;
            }
            BotLog.action(bot, event + "_drop_climb_refused", "around", broken.toShortString(),
                    "goal", goal.toShortString(), "reason", route.reason());
            return false;
        }
        climbing = true;
        BotLog.action(bot, event + "_drop_climb", "around", broken.toShortString(),
                "goal", goal.toShortString(), "levels", goal.getY() - bot.blockPosition().getY());
        return true;
    }
}

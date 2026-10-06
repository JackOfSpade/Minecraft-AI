package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

/**
 * The way back down from a pillar the bot built itself. A person on top of a tall tower looks down,
 * breaks the block under their feet, drops one block onto the next, and repeats: every drop is a
 * single block, so it is always safe, and the throwaway blocks come back as items. A route cannot do
 * this for the bot (it never breaks its own footing, and it steps down at most the safe fall), which
 * is why a pillar taller than that fall used to strand it for good.
 *
 * <p>The pillar is known because the bot placed it: its column and its floor level are the ones the
 * pillar route was admitted for, so nothing here reads a cell the bot has not seen. The break itself
 * is {@link ActionPack#startOwnSupportMining}, which is admitted only for the block directly under
 * the bot while it holds one of the pillar's throwaway blocks.</p>
 */
public final class TowerDescent {
    public enum Status {
        /** Still breaking or falling; the owner keeps calling {@link #tick}. */
        DESCENDING,
        /** The bot stands on its floor level, or is no longer on the tower. */
        DONE,
        /** A break was refused; {@link #failureReason()} says why and the bot stays where it is. */
        FAILED
    }

    /** The feet cell at the pillar's floor: the bot stands there once the whole tower is gone. */
    private final BlockPos base;
    private BlockPos breaking;
    /** The cell of the last break that went through, until the bot has dropped out of the cell above it. */
    private BlockPos gone;
    private long generation;
    private int broken;
    /** What each block taken down gave back, in order: the throwaway blocks return as items the bot picks up again. */
    private final List<Item> returned = new ArrayList<>();
    private Item breakingItem;
    /** The game time of the last break that went through; its item cannot be picked up for a moment (see {@link PickupReach}). */
    private long lastBreakAt = Long.MIN_VALUE;
    private String failure = "";

    private TowerDescent(BlockPos base) {
        this.base = base.immutable();
    }

    /** The descent from a pillar that ends with the bot's feet at {@code goal} on {@code supports} placed blocks. */
    public static TowerDescent over(BlockPos goal, int supports) {
        return new TowerDescent(goal.below(Math.max(1, supports)));
    }

    public BlockPos base() {
        return base;
    }

    public String failureReason() {
        return failure;
    }

    /** How many blocks of the tower the bot has taken down so far. */
    public int broken() {
        return broken;
    }

    /**
     * How many of the blocks taken down so far came back as one of {@code items}. A task that counts what it
     * gathered by what the inventory holds must not count its own tower's blocks among them (a stone quota built
     * up with cobblestone).
     */
    public int returnedOf(Set<Item> items) {
        return (int) returned.stream().filter(items::contains).count();
    }

    /** True while the bot stands in the pillar's column above its floor level, so the tower is under it. */
    public boolean standsOnTower(AIPlayerEntity bot) {
        BlockPos feet = bot.blockPosition();
        return feet.getX() == base.getX() && feet.getZ() == base.getZ() && feet.getY() > base.getY();
    }

    /** Advances the descent by one tick. */
    public Status tick(AIPlayerEntity bot) {
        ActionPack pack = bot.getActionPack();
        if (breaking != null) {
            if (!pack.isMiningIdle()) {
                return Status.DESCENDING;
            }
            BlockPos settled = breaking;
            breaking = null;
            if (pack.consumeSuccessfulMining(settled, generation)) {
                broken++;
                returned.add(breakingItem);
                lastBreakAt = bot.level().getGameTime();
                gone = settled;
            } else {
                String refusal = pack.consumeFailedMining(settled, generation);
                if (refusal != null) {
                    return fail(bot, refusal);
                }
            }
        }
        // After a break that went through, physics drops the bot one block onto the next support, or onto
        // the floor. Whatever comes next (the next break, or the end of the descent and the planning of the
        // next pillar from where the bot stands) waits for it to land.
        if (gone != null) {
            if (bot.blockPosition().below().equals(gone) || !bot.onGround()) {
                return Status.DESCENDING;
            }
            gone = null;
        }
        if (!standsOnTower(bot)) {
            // The last block's item lies at the bot's feet; vanilla lets it be picked up only after its delay (and a
            // tick to count it down and another to touch it), and the owner must not walk off, or count what it
            // gathered, before it is.
            boolean collecting = lastBreakAt != Long.MIN_VALUE
                    && bot.level().getGameTime() - lastBreakAt < PickupReach.PICKUP_DELAY_TICKS + 2;
            return collecting ? Status.DESCENDING : Status.DONE;
        }
        if (!bot.onGround()) {
            return Status.DESCENDING;
        }
        BlockPos support = bot.blockPosition().below();
        ActionResult started = pack.startOwnSupportMining(support);
        if (started.isFailed()) {
            // A guarded step's fence says nothing about this block: start again once it is reconciled.
            return ActionPack.GUARDED_STEP_FENCE.equals(started.reason())
                    ? Status.DESCENDING : fail(bot, started.reason());
        }
        breaking = support.immutable();
        // The bot stands on this cell, so what it is made of is no hidden read.
        breakingItem = bot.level().getBlockState(support).getBlock().asItem();
        generation = pack.miningGeneration();
        return Status.DESCENDING;
    }

    private Status fail(AIPlayerEntity bot, String reason) {
        failure = reason;
        BotLog.action(bot, "tower_descent_refused", "reason", reason,
                "at", LogFields.pos(bot.blockPosition()), "base", LogFields.pos(base));
        return Status.FAILED;
    }
}

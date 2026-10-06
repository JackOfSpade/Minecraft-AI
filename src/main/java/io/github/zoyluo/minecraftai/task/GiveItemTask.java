package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.LookAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;

/**
 * Live bug (session 20260928-230626, bot Moss): the player asked the bot to make and keep tool
 * sets for both of them, but none of the ~59 LLM tools could hand an item to a player -- deposit/
 * withdraw only move items in and out of containers, and trade only works with villagers. The
 * small model stalled on three say(purpose=plan) calls and never took an action
 * (model_call_budget_exhausted).
 *
 * <p>Survival-legal item hand-off: walks to within {@link #GIVE_RANGE} of the target player
 * (strict-survival pathing, no teleport/forced-pickup shortcut), looks at them, then drops exactly
 * the requested item/count through the same vanilla drop path a human player uses with Q ({@link
 * InventoryAction#dropItems}). Mirrors {@link TradeTask}'s FIND/MOVE/act phase shape and {@link
 * FollowTask}'s target-resolution default (an explicit name, otherwise the bot's owner).
 */
public final class GiveItemTask extends AbstractTask {
    private enum Phase {
        FIND_PLAYER,
        MOVE_TO_PLAYER,
        GIVE
    }

    private static final double GIVE_RANGE = 2.5D; // "within ~2 blocks"
    private static final int MOVE_TIMEOUT_TICKS = 600;  // 30s bound on reaching the player
    private static final int OVERALL_TIMEOUT_TICKS = 1200;
    private static final int REPATH_INTERVAL = 20;

    private final Item item;
    private final int count;
    private final String requestedPlayerName;
    /** Mission-owned receipt commit that runs only after the exact inventory debit is proved. */
    private final BooleanSupplier receiptCommitter;
    /** A fresh-handoff parent must never spend its reserved output as disposable route support. */
    private final boolean surfaceOnlyApproach;
    /**
     * Optional parent-owned conservation check.  It runs before pathing and before the physical
     * drop so a fresh delivery can replan if another action consumed its reserved output.
     */
    private final BooleanSupplier deliveryGuard;

    private Phase phase = Phase.FIND_PLAYER;
    private Player target;
    private int phaseTicks;

    public GiveItemTask(Item item, int count, String playerName) {
        this(item, count, playerName, () -> true, false, () -> true);
    }

    /** Creates an ordinary handoff that reaches the player without disposable pillar support. */
    GiveItemTask(Item item, int count, String playerName, boolean surfaceOnlyApproach) {
        this(item, count, playerName, () -> true, surfaceOnlyApproach, () -> true);
    }

    /**
     * The optional mission callback closes the restart window between a successful vanilla drop
     * and GoalExecutor observing this task as completed.  It must return false if the logical
     * receipt could not be durably accepted; in that case this task never claims success.
     */
    public GiveItemTask(Item item,
                        int count,
                        String playerName,
                        BooleanSupplier receiptCommitter) {
        this(item, count, playerName, receiptCommitter, false, () -> true);
    }

    /**
     * Creates a parent-guarded physical handoff.  The guard must be side-effect free and return
     * false before any route/debit when a higher-level conservation invariant no longer holds.
     */
    public GiveItemTask(Item item,
                        int count,
                        String playerName,
                        BooleanSupplier receiptCommitter,
                        boolean surfaceOnlyApproach,
                        BooleanSupplier deliveryGuard) {
        this.item = item;
        this.count = Math.max(1, count);
        this.requestedPlayerName = FollowTargetResolver.normalize(playerName);
        this.receiptCommitter = Objects.requireNonNull(receiptCommitter, "receiptCommitter");
        this.surfaceOnlyApproach = surfaceOnlyApproach;
        this.deliveryGuard = Objects.requireNonNull(deliveryGuard, "deliveryGuard");
    }

    @Override
    public String name() {
        return "give_item";
    }

    @Override
    public String describe() {
        return "Giving " + BuiltInRegistries.ITEM.getKey(item) + " x" + count
                + (requestedPlayerName.isBlank() ? " to owner" : " to " + requestedPlayerName)
                + " phase=" + phase;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return switch (phase) {
            case FIND_PLAYER -> 0.1D;
            case MOVE_TO_PLAYER -> 0.5D;
            case GIVE -> 0.9D;
        };
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.FIND_PLAYER;
        phaseTicks = 0;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > OVERALL_TIMEOUT_TICKS) {
            fail("give_item_timeout");
            return;
        }
        switch (phase) {
            case FIND_PLAYER -> findPlayer(bot);
            case MOVE_TO_PLAYER -> moveToPlayer(bot);
            case GIVE -> give(bot);
        }
    }

    private void findPlayer(AIPlayerEntity bot) {
        if (!guardSatisfied()) {
            return;
        }
        if (InventoryAction.countItem(bot, item) < count) {
            fail("need: " + BuiltInRegistries.ITEM.getKey(item) + " x" + count);
            return;
        }
        target = FollowTargetResolver.resolve(bot, requestedPlayerName).orElse(null);
        if (target == null || !target.isAlive()) {
            fail("give_item_player_not_found");
            return;
        }
        if (bot.distanceTo(target) <= GIVE_RANGE) {
            bot.getActionPack().stopAll();
            transition(Phase.GIVE);
            return;
        }
        approach(bot);
        transition(Phase.MOVE_TO_PLAYER);
    }

    private void moveToPlayer(AIPlayerEntity bot) {
        if (!guardSatisfied()) {
            return;
        }
        if (target == null || !target.isAlive()) {
            fail("give_item_player_not_found");
            return;
        }
        if (phaseTicks > MOVE_TIMEOUT_TICKS) {
            bot.getActionPack().stopAll();
            fail("give_item_unreachable");
            return;
        }
        LookAction.lookAt(bot, target.position().add(0.0D, target.getEyeHeight(), 0.0D));
        if (bot.distanceTo(target) <= GIVE_RANGE) {
            bot.getActionPack().stopAll();
            transition(Phase.GIVE);
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && phaseTicks % REPATH_INTERVAL == 0) {
            approach(bot);
        }
        phaseTicks++;
    }

    private void approach(AIPlayerEntity bot) {
        ActionResult result = surfaceOnlyApproach
                ? bot.getActionPack().startSurfacePathTo(target.blockPosition())
                : bot.getActionPack().startPathTo(target.blockPosition());
        if (result.isFailed()) {
            bot.getActionPack().startWalkTo(target.position());
        }
    }

    private void give(AIPlayerEntity bot) {
        if (!guardSatisfied()) {
            return;
        }
        if (target == null || !target.isAlive()) {
            fail("give_item_player_not_found");
            return;
        }
        if (bot.distanceTo(target) > GIVE_RANGE) {
            transition(Phase.MOVE_TO_PLAYER);
            return;
        }
        if (InventoryAction.countItem(bot, item) < count) {
            fail("need: " + BuiltInRegistries.ITEM.getKey(item) + " x" + count);
            return;
        }
        LookAction.lookAt(bot, target.position().add(0.0D, target.getEyeHeight(), 0.0D));
        int before = InventoryAction.countItem(bot, item);
        // Recheck immediately before the irreversible vanilla debit. A move/look tick cannot
        // normally consume inventory, but the parent guard is intentionally authoritative.
        if (!guardSatisfied()) {
            return;
        }
        if (!InventoryAction.dropItems(bot, item, count)) {
            fail("give_item_drop_failed");
            return;
        }
        int after = InventoryAction.countItem(bot, item);
        if (before - after != count) {
            // Never claim success on anything other than the exact requested count actually
            // leaving the bot's inventory.
            fail("give_item_count_mismatch");
            return;
        }
        if (!receiptCommitter.getAsBoolean()) {
            // The physical drop has already happened, so a retry could duplicate it. Surface a
            // typed terminal failure and let the mission fail closed rather than claim delivery.
            fail("give_item_receipt_commit_failed");
            return;
        }
        complete();
    }

    private boolean guardSatisfied() {
        try {
            if (deliveryGuard.getAsBoolean()) {
                return true;
            }
        } catch (RuntimeException ignored) {
            // A parent accounting failure cannot safely be treated as permission to debit items.
        }
        fail("give_item_fresh_quota_lost");
        return false;
    }

    private void transition(Phase next) {
        phase = next;
        phaseTicks = 0;
    }
}

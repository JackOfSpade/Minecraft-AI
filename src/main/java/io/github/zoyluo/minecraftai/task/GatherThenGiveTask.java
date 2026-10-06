package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.craft.RecipeRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Collects a fresh, exact item quota before handing that quota to a player.
 *
 * <p>This is deliberately a task composition rather than a plan based on current inventory:
 * {@link GatherQuotaTask#collectAdditionalExact(Item, int)} snapshots its count when this task
 * starts, so carried copies cannot turn an acquire-and-deliver request into an immediate handoff.
 * Generic "logs" first accepts any log species, then locks one species and refines that exact
 * stack if necessary. The child tasks remain private; only this parent is assigned to
 * {@link TaskManager}.</p>
 */
public final class GatherThenGiveTask extends AbstractTask {
    private enum Phase {
        GATHER,
        REFINE,
        GIVE
    }

    private final Item item;
    private final int count;
    private final String playerName;
    /** Generic "logs" accepts any tree species; an explicit id remains species-exact. */
    private final boolean genericLogs;
    /** Immutable per-species baseline used to prove a generic-log handoff is fresh. */
    private final Map<Item, Integer> logInventoryBaseline = new LinkedHashMap<>();
    private Phase phase = Phase.GATHER;
    private GatherQuotaTask gatherTask;
    private GiveItemTask giveTask;
    private Item deliveryItem;

    public GatherThenGiveTask(Item item, int count, String playerName) {
        this(item, count, playerName, false);
    }

    /** Creates the generic-tree-log form used only when the player said "logs", not a species. */
    public static GatherThenGiveTask genericLogs(int count, String playerName) {
        return new GatherThenGiveTask(Items.OAK_LOG, count, playerName, true);
    }

    private GatherThenGiveTask(Item item, int count, String playerName, boolean genericLogs) {
        this.item = item;
        this.count = Math.max(1, count);
        this.playerName = playerName == null ? "" : playerName;
        this.genericLogs = genericLogs;
    }

    @Override
    public String name() {
        return "gather_then_give";
    }

    @Override
    public String describe() {
        String requested = genericLogs ? "logs" : BuiltInRegistries.ITEM.getKey(item).toString();
        String selected = deliveryItem == null ? requested : BuiltInRegistries.ITEM.getKey(deliveryItem).toString();
        return "Gathering new " + selected + " x" + count
                + " then giving to " + (playerName.isBlank() ? "owner" : playerName)
                + " phase=" + phase;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return switch (phase) {
            case GATHER -> gatherTask == null ? 0.0D : 0.70D * gatherTask.progress();
            case REFINE -> 0.70D + (gatherTask == null ? 0.0D : 0.20D * gatherTask.progress());
            case GIVE -> 0.90D + (giveTask == null ? 0.0D : 0.10D * giveTask.progress());
        };
    }

    @Override
    public boolean isWaiting() {
        return phase != Phase.GIVE && gatherTask != null && gatherTask.isWaiting();
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.GATHER;
        giveTask = null;
        deliveryItem = null;
        logInventoryBaseline.clear();
        if (genericLogs) {
            for (Item log : RecipeRegistry.LOGS) {
                logInventoryBaseline.put(log, InventoryAction.countItem(bot, log));
            }
            gatherTask = GatherQuotaTask.collectAdditionalLogsForHandoff(count);
        } else {
            gatherTask = GatherQuotaTask.collectAdditionalExact(item, count);
        }
        gatherTask.start(bot);
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (phase == Phase.GATHER) {
            gatherTask.tick(bot);
            if (gatherTask.state() == TaskState.COMPLETED) {
                if (genericLogs) {
                    beginGenericLogsHandoff(bot);
                    return;
                }
                if (!gatherTask.hasRetainedFreshQuota(bot)) {
                    // The child normally cannot complete without this invariant. Keep this
                    // explicit fail-closed check at the transaction boundary so a future gather
                    // behavior change cannot turn carried pre-existing items into a handoff.
                    failClosed(bot, "fresh_gather_retained_quota_missing");
                    return;
                }
                beginGive(bot, item);
            } else if (gatherTask.state() != TaskState.RUNNING && gatherTask.state() != TaskState.PAUSED) {
                failClosed(bot, "fresh_gather_failed:" + gatherTask.failureReason());
            }
            return;
        }
        if (phase == Phase.REFINE) {
            gatherTask.tick(bot);
            if (gatherTask.state() == TaskState.COMPLETED) {
                if (!hasGenericRetainedDeliveryQuota(bot)) {
                    failClosed(bot, "fresh_log_handoff_retained_quota_missing");
                    return;
                }
                beginGive(bot, deliveryItem);
            } else if (gatherTask.state() != TaskState.RUNNING && gatherTask.state() != TaskState.PAUSED) {
                failClosed(bot, "fresh_log_handoff_refine_failed:" + gatherTask.failureReason());
            }
            return;
        }
        if (!(genericLogs ? hasGenericRetainedDeliveryQuota(bot) : gatherTask.hasRetainedFreshQuota(bot))) {
            // The gather child completed with baseline + count in inventory. Re-check before
            // every give tick: another system must never consume that reserve and let this task
            // hand a pre-existing stack to the player.
            failClosed(bot, "fresh_handoff_retained_quota_lost");
            return;
        }
        giveTask.tick(bot);
        if (giveTask.state() == TaskState.COMPLETED) {
            complete();
        } else if (giveTask.state() != TaskState.RUNNING && giveTask.state() != TaskState.PAUSED) {
            // The GiveItemTask proves the inventory debit before completion. Do not retry a
            // terminal failure here, because a failed receipt can follow a real vanilla drop.
            failClosed(bot, "fresh_handoff_failed:" + giveTask.failureReason());
        }
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        Task child = activeChild();
        if (child != null) {
            child.pause(bot);
        }
    }

    @Override
    protected void onResume(AIPlayerEntity bot) {
        Task child = activeChild();
        if (child != null) {
            child.resume(bot);
        }
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        Task child = activeChild();
        if (child != null) {
            child.abort(bot);
        }
        super.onAbort(bot);
    }

    /**
     * A parent failure does not invoke {@link #onAbort(AIPlayerEntity)} automatically. Stop a
     * child route explicitly so a failed fresh handoff cannot leave the bot walking or mining
     * after this task has become terminal.
     */
    private void failClosed(AIPlayerEntity bot, String reason) {
        Task child = activeChild();
        if (child != null && (child.state() == TaskState.RUNNING || child.state() == TaskState.PAUSED)) {
            child.abort(bot);
        }
        bot.getActionPack().stopAll();
        fail(reason);
    }

    private Task activeChild() {
        return phase == Phase.GIVE ? giveTask : gatherTask;
    }

    /** Locks one actually-new log species, then gathers only the shortfall for an exact drop. */
    private void beginGenericLogsHandoff(AIPlayerEntity bot) {
        deliveryItem = freshestNewLog(bot);
        if (deliveryItem == null) {
            failClosed(bot, "fresh_log_handoff_no_new_species");
            return;
        }
        int fresh = freshLogCount(bot, deliveryItem);
        if (fresh >= count) {
            beginGive(bot, deliveryItem);
            return;
        }
        // A mixed forest can produce a family total without a single full stack. Lock the
        // best observed species and make one exact retained refinement; never substitute old
        // oak (or another species) merely because it happened to be carried at the start.
        gatherTask = GatherQuotaTask.collectAdditionalExact(deliveryItem, count - fresh);
        gatherTask.start(bot);
        phase = Phase.REFINE;
    }

    private Item freshestNewLog(AIPlayerEntity bot) {
        Item best = null;
        int bestCount = 0;
        for (Item log : RecipeRegistry.LOGS) {
            int fresh = freshLogCount(bot, log);
            if (fresh > bestCount) {
                best = log;
                bestCount = fresh;
            }
        }
        return best;
    }

    private int freshLogCount(AIPlayerEntity bot, Item log) {
        return Math.max(0, InventoryAction.countItem(bot, log) - logInventoryBaseline.getOrDefault(log, 0));
    }

    /** Re-check the locked species immediately before every physical give tick. */
    private boolean hasGenericRetainedDeliveryQuota(AIPlayerEntity bot) {
        return deliveryItem != null && freshLogCount(bot, deliveryItem) >= count;
    }

    private void beginGive(AIPlayerEntity bot, Item delivery) {
        giveTask = new GiveItemTask(delivery, count, playerName, true);
        giveTask.start(bot);
        phase = Phase.GIVE;
    }
}

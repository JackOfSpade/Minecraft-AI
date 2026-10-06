package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.craft.RecipeRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Collects a fresh, exact item quota before handing that quota to a player.
 *
 * <p>This is deliberately a task composition rather than a plan based on current inventory:
 * {@link GatherQuotaTask#collectAdditionalExact(Item, int)} snapshots its count when this task
 * starts, so carried copies cannot turn an acquire-and-deliver request into an immediate handoff.
 * Generic "logs" accepts any log species while collecting and is handed over species by species,
 * each drop limited to the new logs of that species. The child tasks remain private; only this
 * parent is assigned to {@link TaskManager}.</p>
 */
public final class GatherThenGiveTask extends AbstractTask {
    /**
     * Failure-reason prefix when the quota was collected but the handoff itself failed: the new stock
     * is still carried, so the right retry is a plain handoff, not another collection.
     */
    public static final String HANDOFF_FAILED_PREFIX = "fresh_handoff_failed:";

    private enum Phase {
        GATHER,
        GIVE
    }

    /** One drop of the handoff. */
    record Delivery(Item item, int count) {
    }

    private final Item item;
    private final int count;
    private final String playerName;
    /** Generic "logs" accepts any tree species; an explicit id remains species-exact. */
    private final boolean genericLogs;
    /** Count of each handed-over species when this task started: only units above it are the new quota. */
    private final Map<Item, Integer> carriedAtStart = new LinkedHashMap<>();
    private final List<Delivery> deliveries = new ArrayList<>();
    private int deliveryIndex;
    private Phase phase = Phase.GATHER;
    private GatherQuotaTask gatherTask;
    private GiveItemTask giveTask;

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
        return "Gathering new " + requested + " x" + count
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
            case GIVE -> 0.70D + 0.30D * (deliveryIndex + (giveTask == null ? 0.0D : giveTask.progress()))
                    / Math.max(1, deliveries.size());
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
        deliveries.clear();
        deliveryIndex = 0;
        carriedAtStart.clear();
        for (Item species : handedOverItems()) {
            carriedAtStart.put(species, InventoryAction.countItem(bot, species));
        }
        gatherTask = genericLogs
                ? GatherQuotaTask.collectAdditionalLogsForHandoff(count)
                : GatherQuotaTask.collectAdditionalExact(item, count);
        gatherTask.start(bot);
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (phase == Phase.GATHER) {
            gatherTask.tick(bot);
            if (gatherTask.state() == TaskState.COMPLETED) {
                beginHandoff(bot);
            } else if (gatherTask.state() != TaskState.RUNNING && gatherTask.state() != TaskState.PAUSED) {
                failClosed(bot, "fresh_gather_failed:" + gatherTask.failureReason());
            }
            return;
        }
        Delivery current = deliveries.get(deliveryIndex);
        if (freshCount(bot, current.item()) < current.count()) {
            // The gather child completed with the new units in inventory. Re-check before every
            // give tick: another system must never consume that reserve and let this task hand a
            // pre-existing stack to the player.
            failClosed(bot, "fresh_handoff_retained_quota_lost");
            return;
        }
        giveTask.tick(bot);
        if (giveTask.state() == TaskState.COMPLETED) {
            deliveryIndex++;
            if (deliveryIndex == deliveries.size()) {
                complete();
            } else {
                startDelivery(bot);
            }
        } else if (giveTask.state() != TaskState.RUNNING && giveTask.state() != TaskState.PAUSED) {
            // The GiveItemTask proves the inventory debit before completion. Do not retry a
            // terminal failure here, because a failed receipt can follow a real vanilla drop.
            failClosed(bot, HANDOFF_FAILED_PREFIX + giveTask.failureReason());
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

    private List<Item> handedOverItems() {
        return genericLogs ? RecipeRegistry.LOGS : List.of(item);
    }

    private void beginHandoff(AIPlayerEntity bot) {
        List<Delivery> plan = planDeliveries(handedOverItems(), species -> freshCount(bot, species), count);
        if (plan == null) {
            // The child normally cannot complete without the new units in inventory. Keep this
            // explicit fail-closed check at the transaction boundary so a future gather behavior
            // change cannot turn carried pre-existing items into a handoff.
            failClosed(bot, "fresh_gather_retained_quota_missing");
            return;
        }
        deliveries.addAll(plan);
        phase = Phase.GIVE;
        startDelivery(bot);
    }

    private void startDelivery(AIPlayerEntity bot) {
        Delivery delivery = deliveries.get(deliveryIndex);
        giveTask = new GiveItemTask(delivery.item(), delivery.count(), playerName, true);
        giveTask.start(bot);
    }

    private int freshCount(AIPlayerEntity bot, Item species) {
        return Math.max(0, InventoryAction.countItem(bot, species) - carriedAtStart.getOrDefault(species, 0));
    }

    /**
     * Splits the quota over the species of the new stock: each drop is limited to the new units of
     * that species, so "32 logs" is met by any mix of 32 new logs (20 oak and 12 birch from one
     * forest) without gathering a refinement. The most plentiful species goes first, which keeps
     * the number of separate drops down. Null when the new stock does not reach the quota.
     */
    static List<Delivery> planDeliveries(List<Item> species, ToIntFunction<Item> fresh, int quota) {
        List<Item> ranked = new ArrayList<>(species);
        ranked.sort(Comparator.comparingInt((Item candidate) -> fresh.applyAsInt(candidate)).reversed());
        List<Delivery> plan = new ArrayList<>();
        int remaining = quota;
        for (Item candidate : ranked) {
            int take = Math.min(fresh.applyAsInt(candidate), remaining);
            if (take > 0) {
                plan.add(new Delivery(candidate, take));
                remaining -= take;
            }
        }
        return remaining == 0 ? plan : null;
    }
}

package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.ContainerAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.memory.BotMemoryStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class StockpileTask extends AbstractTask {
    private static final int BASE_RADIUS = 8;
    private static final double REACH_SQUARED = 20.25D;

    private enum Phase {
        FIND_BASE,
        FIND_CONTAINER,
        WALKING,
        TRANSFERRING,
        DONE
    }

    private final boolean allExceptTools;
    /** Items a parent task must keep in inventory for its own terminal handoff. */
    private final Set<Item> retainedItems;
    private Phase phase = Phase.FIND_BASE;
    private BlockPos basePos;
    private final List<BlockPos> containers = new ArrayList<>();
    private BlockPos containerPos;
    private int containerIndex;
    private int transferred;
    private final Set<BlockPos> depositedContainers = new LinkedHashSet<>();
    private String note = "";

    public StockpileTask(boolean allExceptTools) {
        this(allExceptTools, Set.of());
    }

    /**
     * Creates a stockpiling task that leaves the supplied items untouched. This is used by an
     * in-progress fresh acquire-and-handoff transaction: depositing its exact target would let a
     * later handoff consume an older carried stack instead.
     */
    StockpileTask(boolean allExceptTools, Set<Item> retainedItems) {
        this.allExceptTools = allExceptTools;
        this.retainedItems = retainedItems == null ? Set.of() : Set.copyOf(retainedItems);
    }

    @Override
    public String name() {
        return "stockpile";
    }

    @Override
    public String describe() {
        return "Stockpiling transferred=" + transferred + " phase=" + phase + (note.isBlank() ? "" : " note=" + note);
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        if (containers.isEmpty()) {
            return transferred > 0 ? 0.5D : 0.0D;
        }
        return Math.min(0.95D, (double) Math.max(0, containerIndex) / containers.size());
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.FIND_BASE;
        transferred = 0;
        containers.clear();
        containerIndex = 0;
        depositedContainers.clear();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 1600) {
            BotLog.action(bot, "stockpile_timeout_detail", "phase", phase, "transferred", transferred,
                    "note", note.isBlank() ? "none" : note);
            fail("stockpile_timeout");
            return;
        }
        switch (phase) {
            case FIND_BASE -> findBase(bot);
            case FIND_CONTAINER -> findContainer(bot);
            case WALKING -> walk(bot);
            case TRANSFERRING -> transfer(bot);
            case DONE -> complete();
        }
    }

    private void findBase(AIPlayerEntity bot) {
        basePos = BotMemoryStore.INSTANCE.of(bot.getUUID())
                .placeIn(bot.level(), "base")
                .orElse(null);
        if (basePos == null) {
            fail("no_base");
            return;
        }
        phase = Phase.FIND_CONTAINER;
    }

    private void findContainer(AIPlayerEntity bot) {
        containers.clear();
        Item preferred = nextDepositItem(bot);
        // Storage in sight plus containers this bot remembers opening here. A container already
        // known (from the ledger, i.e. from having opened it) to hold this item is preferred so
        // stacks merge; containers known to be full are skipped. Unopened contents are never read.
        containers.addAll(StorageTargets.aroundBase(bot, basePos, BASE_RADIUS, true,
                (pos, observed) -> preferred != null && StorageTargets.ledgerCount(bot, pos, observed, preferred) > 0));
        if (containers.isEmpty()) {
            fail("no_base_container");
            return;
        }
        containerIndex = 0;
        selectNextContainer(bot);
    }

    private void selectNextContainer(AIPlayerEntity bot) {
        if (nextDepositItem(bot) == null) {
            phase = Phase.DONE;
            return;
        }
        if (containerIndex >= containers.size()) {
            fail(transferred > 0 ? "partial_stockpile_container_full" : "container_full");
            return;
        }
        containerPos = containers.get(containerIndex++);
        if (ContainerAction.inReachAndSight(bot, containerPos)) {
            phase = Phase.TRANSFERRING;
            return;
        }
        BlockPos stand = ContainerSupport.adjacentStand(bot, containerPos);
        if (stand == null) {
            selectNextContainer(bot);
            return;
        }
        // A parent may reserve a fresh handoff target. Reaching storage must not spend that
        // target as disposable Baritone support before the deposit filter has a chance to keep it.
        ActionResult result = retainedItems.isEmpty()
                ? bot.getActionPack().startPathTo(stand)
                : bot.getActionPack().startSurfacePathTo(stand);
        if (result.isFailed()) {
            note = result.reason();
            selectNextContainer(bot);
            return;
        }
        phase = Phase.WALKING;
    }

    private void walk(AIPlayerEntity bot) {
        if (containerPos == null) {
            phase = Phase.FIND_CONTAINER;
            return;
        }
        if (ContainerAction.canSee(bot, containerPos)
                && !ContainerAction.isOpenableStorage(bot.level(), containerPos)) {
            // Seen gone (or its lid is now blocked): forget what we knew and move on to the next one.
            if (!(bot.level().getBlockEntity(containerPos) instanceof Container)) {
                ContainerAction.forget(bot, containerPos);
            }
            selectNextContainer(bot);
            return;
        }
        if (ContainerAction.inReachAndSight(bot, containerPos)) {
            bot.getActionPack().stopAll();
            phase = Phase.TRANSFERRING;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            selectNextContainer(bot);
        }
    }

    private void transfer(AIPlayerEntity bot) {
        if (containerPos == null || !ContainerAction.inReachAndSight(bot, containerPos)) {
            phase = Phase.FIND_CONTAINER;
            return;
        }
        Container container = ContainerAction.open(bot, containerPos, false).orElse(null);
        if (container == null) {
            selectNextContainer(bot);
            return;
        }
        ContainerAction.TransferResult result = ContainerAction.deposit(bot, containerPos, container, depositFilter(), 64);
        if (result.movedAny()) {
            transferred += result.count();
            depositedContainers.add(containerPos.immutable());
            return;
        }
        if ("nothing_to_deposit".equals(result.reason())) {
            phase = Phase.DONE;
            return;
        }
        note = result.reason();
        selectNextContainer(bot);
    }

    private Predicate<ItemStack> depositFilter() {
        return stack -> !retainedItems.contains(stack.getItem())
                && (!allExceptTools || !ContainerAction.isReservedTool(stack));
    }

    private Item nextDepositItem(AIPlayerEntity bot) {
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty() && depositFilter().test(stack)) {
                return stack.getItem();
            }
        }
        ItemStack offHandStack = bot.getItemBySlot(EquipmentSlot.OFFHAND);
        if (!offHandStack.isEmpty() && depositFilter().test(offHandStack)) {
            return offHandStack.getItem();
        }
        return null;
    }

    public Set<BlockPos> depositedContainers() {
        return Set.copyOf(depositedContainers);
    }
}

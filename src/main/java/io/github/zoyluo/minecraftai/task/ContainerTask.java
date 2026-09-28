package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.ContainerAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.memory.BotMemoryStore;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Comparator;
import java.util.Optional;
import java.util.function.Predicate;

public final class ContainerTask extends AbstractTask {
    public enum Mode {
        DEPOSIT,
        WITHDRAW
    }

    private enum Phase {
        FINDING,
        WALKING,
        TRANSFERRING,
        DONE
    }

    private static final int SEARCH_RADIUS = 8;
    private static final double REACH_SQUARED = 20.25D;

    private final Mode mode;
    private final BlockPos requestedContainerPos;
    private final Item item;
    private final int targetCount;
    private final boolean allExceptTools;
    private Phase phase = Phase.FINDING;
    private BlockPos containerPos;
    private int transferred;
    private String doneReason = "";

    public static ContainerTask deposit(BlockPos containerPos, Item item, int count, boolean allExceptTools) {
        return new ContainerTask(Mode.DEPOSIT, containerPos, item, count, allExceptTools);
    }

    public static ContainerTask withdraw(BlockPos containerPos, Item item, int count) {
        return new ContainerTask(Mode.WITHDRAW, containerPos, item, count, false);
    }

    private ContainerTask(Mode mode, BlockPos containerPos, Item item, int count, boolean allExceptTools) {
        this.mode = mode;
        this.requestedContainerPos = containerPos == null ? null : containerPos.toImmutable();
        this.item = item;
        this.targetCount = count <= 0 ? Integer.MAX_VALUE : count;
        this.allExceptTools = allExceptTools;
    }

    @Override
    public String name() {
        return mode == Mode.DEPOSIT ? "deposit" : "withdraw";
    }

    @Override
    public String describe() {
        String target = item == null ? (allExceptTools ? "all_except_tools" : "all") : Registries.ITEM.getId(item).toString();
        String count = targetCount == Integer.MAX_VALUE ? "all" : String.valueOf(targetCount);
        return "mode=" + mode + " target=" + target + " count=" + count + " transferred=" + transferred + " phase=" + phase;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        if (targetCount == Integer.MAX_VALUE) {
            return transferred > 0 ? 0.75D : 0.0D;
        }
        return Math.min(1.0D, (double) transferred / targetCount);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.FINDING;
        transferred = 0;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 1200) {
            // The bare "container_timeout" reason alone does not say which of the three phases it
            // stalled in (never found a container, stuck walking, or stuck transferring), so a
            // reader would have to re-run it under a debugger to tell why. Log the breadcrumb once,
            // right before the generic task_failed line fires.
            BotLog.warn(LogCategory.TASK, bot, "container_timeout_context", "phase", phase,
                    "container", containerPos == null ? "none" : containerPos.toShortString(),
                    "transferred", transferred);
            fail("container_timeout");
            return;
        }
        switch (phase) {
            case FINDING -> findContainer(bot);
            case WALKING -> walkToContainer(bot);
            case TRANSFERRING -> transfer(bot);
            case DONE -> complete();
        }
    }

    private void findContainer(AIPlayerEntity bot) {
        containerPos = requestedContainerPos == null
                ? nearestContainer(bot, SEARCH_RADIUS).orElseGet(() -> rememberedContainer(bot).orElse(null))
                : requestedContainerPos;
        if (containerPos == null) {
            fail("no_container");
            return;
        }
        boolean observable = io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlock(bot, containerPos);
        if (observable && ContainerAction.resolve(bot, containerPos).isEmpty()) {
            fail("no_container_at: " + shortPos(containerPos));
            return;
        }
        if (observable && bot.getEyePos().squaredDistanceTo(containerPos.toCenterPos()) <= REACH_SQUARED) {
            phase = Phase.TRANSFERRING;
            return;
        }
        BlockPos stand = adjacentStand(bot, containerPos);
        if (stand == null) {
            fail("no_stand_position_for_container");
            return;
        }
        ActionResult result = bot.getActionPack().startPathTo(stand);
        if (result.isFailed()) {
            fail(result.reason());
            return;
        }
        phase = Phase.WALKING;
    }

    private void walkToContainer(AIPlayerEntity bot) {
        if (containerPos == null) {
            phase = Phase.FINDING;
            return;
        }
        boolean observable = io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlock(bot, containerPos);
        if (observable && ContainerAction.resolve(bot, containerPos).isEmpty()) {
            phase = Phase.FINDING;
            return;
        }
        if (observable && bot.getEyePos().squaredDistanceTo(containerPos.toCenterPos()) <= REACH_SQUARED) {
            bot.getActionPack().stopAll();
            phase = Phase.TRANSFERRING;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            phase = Phase.FINDING;
        }
    }

    private void transfer(AIPlayerEntity bot) {
        if (containerPos == null
                || bot.getEyePos().squaredDistanceTo(containerPos.toCenterPos()) > REACH_SQUARED
                || !io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlock(bot, containerPos)) {
            phase = Phase.FINDING;
            return;
        }
        Inventory container = ContainerAction.resolve(bot, containerPos).orElse(null);
        if (container == null) {
            fail("container_missing");
            return;
        }
        int remaining = targetCount == Integer.MAX_VALUE ? 64 : targetCount - transferred;
        if (remaining <= 0) {
            complete();
            return;
        }
        ContainerAction.TransferResult result = mode == Mode.DEPOSIT
                ? ContainerAction.depositOne(container, bot, depositFilter(), remaining)
                : ContainerAction.withdrawOne(container, bot, item, remaining);
        if (result.movedAny()) {
            transferred += result.count();
            if (targetCount != Integer.MAX_VALUE && transferred >= targetCount) {
                complete();
            }
            return;
        }
        doneReason = result.reason();
        if (mode == Mode.WITHDRAW && transferred < targetCount) {
            fail(doneReason.isBlank() ? "missing " + Registries.ITEM.getId(item) + " x" + (targetCount - transferred) : doneReason);
            return;
        }
        if ("container_full".equals(doneReason) && transferred == 0) {
            fail(doneReason);
            return;
        }
        // This path calls complete() even when fewer items moved than were requested (e.g. the
        // container ran out of space or of the item partway through). task_completed alone reads
        // as full success with no hint that the request was only partly satisfied, so log the
        // shortfall here -- the only place that knows both the target and the stop reason.
        if (targetCount != Integer.MAX_VALUE && transferred < targetCount) {
            BotLog.action(bot, "container_partial_complete",
                    "item", item == null ? "all" : Registries.ITEM.getId(item).toString(),
                    "requested", targetCount, "transferred", transferred,
                    "reason", doneReason.isBlank() ? "no_more_available" : doneReason);
        }
        complete();
    }

    private Predicate<ItemStack> depositFilter() {
        if (item != null) {
            return stack -> stack.isOf(item);
        }
        if (allExceptTools) {
            return stack -> !ContainerAction.isReservedTool(stack);
        }
        return stack -> true;
    }

    public static Optional<BlockPos> nearestContainer(AIPlayerEntity bot, int radius) {
        return nearestContainerNear(bot, bot.getBlockPos(), radius);
    }

    public static Optional<BlockPos> nearestContainerNear(AIPlayerEntity bot, BlockPos center, int radius) {
        BlockPos origin = bot.getBlockPos();
        return BlockPos.stream(center.add(-radius, -3, -radius), center.add(radius, 4, radius))
                .filter(pos -> io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlock(bot, pos))
                .filter(pos -> ContainerAction.resolve(bot, pos).isPresent())
                .map(BlockPos::toImmutable)
                .min(Comparator.comparingDouble(pos -> pos.getSquaredDistance(origin)));
    }

    private static Optional<BlockPos> rememberedContainer(AIPlayerEntity bot) {
        return BotMemoryStore.INSTANCE.of(bot.getUuid())
                .placeIn(bot.getEntityWorld(), "depot", "home", "base", "chest")
                .flatMap(pos -> io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlock(bot, pos)
                        && ContainerAction.resolve(bot, pos).isPresent()
                        ? Optional.of(pos.toImmutable())
                        : nearestContainerNear(bot, pos, 4));
    }

    private static BlockPos adjacentStand(AIPlayerEntity bot, BlockPos pos) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = pos.offset(direction);
            if (Standability.isStandable(bot.getEntityWorld(), candidate)) {
                return candidate.toImmutable();
            }
        }
        return null;
    }

    private static String shortPos(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}

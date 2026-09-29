package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.ContainerAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.InventoryPolicy;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.memory.BotMemoryStore;
import io.github.zoyluo.minecraftai.memory.ContainerLedger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

public final class ContainerTask extends AbstractTask {
    public enum Mode {
        DEPOSIT,
        WITHDRAW,
        /** Open the container, look inside (updating the ledger) and stop. */
        INSPECT
    }

    private enum Phase {
        FINDING,
        WALKING,
        TRANSFERRING,
        PLACING,
        DONE
    }

    private static final int SEARCH_RADIUS = 8;
    private static final double REACH_SQUARED = ContainerAction.REACH_SQUARED;
    /** Most remembered (out-of-sight) containers one request will visit. */
    private static final int LEDGER_CANDIDATES = 4;

    private final Mode mode;
    private final BlockPos requestedContainerPos;
    private final Item item;
    private final int targetCount;
    private final boolean allExceptTools;
    private final boolean junkOnly;
    private Phase phase = Phase.FINDING;
    private BlockPos containerPos;
    private int transferred;
    private String doneReason = "";
    private final List<BlockPos> candidates = new ArrayList<>();
    private final Set<BlockPos> tried = new HashSet<>();
    private boolean candidatesBuilt;
    private boolean placedFallbackChest;

    public static ContainerTask deposit(BlockPos containerPos, Item item, int count, boolean allExceptTools) {
        return new ContainerTask(Mode.DEPOSIT, containerPos, item, count, allExceptTools, false);
    }

    /** Stows only the junk surplus (filler blocks beyond the configured throwaway budget); keeps everything else. */
    public static ContainerTask depositJunk(BlockPos containerPos) {
        return new ContainerTask(Mode.DEPOSIT, containerPos, null, 0, false, true);
    }

    public static ContainerTask withdraw(BlockPos containerPos, Item item, int count) {
        return new ContainerTask(Mode.WITHDRAW, containerPos, item, count, false, false);
    }

    /** Opens a container in sight (nearest, or the one at the given position), records what is inside, and stops. */
    public static ContainerTask inspect(BlockPos containerPos) {
        return new ContainerTask(Mode.INSPECT, containerPos, null, 0, false, false);
    }

    private ContainerTask(Mode mode, BlockPos containerPos, Item item, int count, boolean allExceptTools,
                          boolean junkOnly) {
        this.mode = mode;
        this.requestedContainerPos = containerPos == null ? null : containerPos.immutable();
        this.item = item;
        this.targetCount = count <= 0 ? Integer.MAX_VALUE : count;
        this.allExceptTools = allExceptTools;
        this.junkOnly = junkOnly;
    }

    @Override
    public String name() {
        return switch (mode) {
            case DEPOSIT -> "deposit";
            case WITHDRAW -> "withdraw";
            case INSPECT -> "inspect_container";
        };
    }

    @Override
    public String describe() {
        String target = item == null
                ? (junkOnly ? "junk" : allExceptTools ? "all_except_tools" : "all")
                : BuiltInRegistries.ITEM.getKey(item).toString();
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
        candidates.clear();
        tried.clear();
        candidatesBuilt = false;
        placedFallbackChest = false;
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
            case PLACING -> placeChest(bot);
            case DONE -> complete();
        }
    }

    private boolean explicitTarget() {
        return requestedContainerPos != null;
    }

    /**
     * Whether the target may be any block-entity container (a caller-supplied coordinate for an
     * ordinary transfer). A junk stow is always a task on the bot's own initiative even when it is
     * handed a remembered position, so it keeps the automatic rules: a real, openable storage block
     * (a chest that has since become a furnace or hopper is refused) with no observed spawner nearby.
     */
    private boolean anyContainerAllowed() {
        return explicitTarget() && !junkOnly;
    }

    /**
     * Candidate order. Withdraw: containers the ledger remembers holding the item (best first),
     * then storage in sight (contents unknown until opened). Deposit: storage in sight that the
     * ledger did not recently see full, then remembered containers with room (a "full" entry fades
     * after {@link ContainerLedger#FULL_TRUST_TICKS}), then the remembered base/depot places, and
     * LAST the in-sight containers the ledger recently saw full: full is a demotion, not an
     * exclusion (a player may have emptied one), and opening it re-verifies. Remembered positions
     * count only when their chunk is loaded and they are within {@link StorageTargets#LEDGER_MAX_DISTANCE}.
     * Nothing is ranked by contents the bot has not seen.
     */
    private void buildCandidates(AIPlayerEntity bot) {
        candidates.clear();
        if (explicitTarget()) {
            candidates.add(requestedContainerPos);
            if (!junkOnly) {
                return;
            }
            // A junk stow handed a remembered position keeps the automatic rules and falls back to them.
        }
        String dimension = bot.level().dimension().identifier().toString();
        long now = bot.level().getGameTime();
        ContainerLedger ledger = StorageTargets.ledger(bot);
        if (mode != Mode.DEPOSIT) {
            if (item != null && mode == Mode.WITHDRAW) {
                for (ContainerLedger.Entry entry : ledger.find(dimension, BuiltInRegistries.ITEM.getKey(item).toString(),
                        bot.blockPosition(), targetCount == Integer.MAX_VALUE ? 1 : targetCount, LEDGER_CANDIDATES,
                        entry -> StorageTargets.ledgerCandidateOk(bot, entry))) {
                    addCandidate(bot, entry.pos());
                }
            }
            for (BlockPos pos : StorageTargets.observedStorage(bot, bot.blockPosition(), SEARCH_RADIUS, false)) {
                addCandidate(bot, pos);
            }
            return;
        }
        List<BlockPos> observed = StorageTargets.observedStorage(bot, bot.blockPosition(), SEARCH_RADIUS, true);
        List<BlockPos> demoted = new ArrayList<>();
        for (BlockPos pos : observed) {
            if (StorageTargets.ledgerKnowsFull(bot, pos, true)) {
                demoted.add(pos);
            } else {
                addCandidate(bot, pos);
            }
        }
        double stowRadius = junkOnly ? MinecraftAiConfig.get().storage().stowRadius() : Double.MAX_VALUE;
        for (ContainerLedger.Entry entry : ledger.withRoom(dimension, bot.blockPosition(), now, LEDGER_CANDIDATES,
                entry -> StorageTargets.ledgerCandidateOk(bot, entry)
                        && Math.sqrt(entry.pos().distSqr(bot.blockPosition())) <= stowRadius)) {
            addCandidate(bot, entry.pos());
        }
        if (!junkOnly) {
            rememberedContainer(bot).ifPresent(pos -> {
                if (StorageTargets.ledgerKnowsFull(bot, pos, true)) {
                    demoted.add(pos);
                } else {
                    addCandidate(bot, pos);
                }
            });
        }
        for (BlockPos pos : demoted) {
            addCandidate(bot, pos);
        }
    }

    private void addCandidate(AIPlayerEntity bot, BlockPos pos) {
        BlockPos immutable = pos.immutable();
        BlockPos identity = ContainerAction.canonicalPos(bot.level(), immutable);
        for (BlockPos existing : candidates) {
            if (ContainerAction.canonicalPos(bot.level(), existing).equals(identity)) {
                return;
            }
        }
        candidates.add(immutable);
    }

    private BlockPos nextCandidate(AIPlayerEntity bot) {
        while (!candidates.isEmpty()) {
            BlockPos pos = candidates.remove(0);
            if (tried.add(ContainerAction.canonicalPos(bot.level(), pos))) {
                return pos;
            }
        }
        return null;
    }

    /** Gives up on the current container; FINDING picks the next candidate or ends the task. */
    private void advanceCandidate() {
        containerPos = null;
        phase = Phase.FINDING;
    }

    private void findContainer(AIPlayerEntity bot) {
        if (!candidatesBuilt) {
            buildCandidates(bot);
            candidatesBuilt = true;
        }
        // Loop instead of recursing: every rejected candidate is consumed, so this always ends.
        while (true) {
            containerPos = nextCandidate(bot);
            if (containerPos == null) {
                noMoreContainers(bot);
                return;
            }
            boolean observable = ContainerAction.canSee(bot, containerPos);
            if (observable && !usable(bot, containerPos)) {
                dropIfNotStorage(bot, containerPos);
                continue;
            }
            if (observable && bot.getEyePosition().distanceToSqr(containerPos.getCenter()) <= REACH_SQUARED) {
                phase = Phase.TRANSFERRING;
                return;
            }
            BlockPos stand = ContainerSupport.adjacentStand(bot, containerPos);
            if (stand == null) {
                doneReason = "no_stand_position_for_container";
                continue;
            }
            ActionResult result = bot.getActionPack().startPathTo(stand);
            if (result.isFailed()) {
                doneReason = result.reason();
                continue;
            }
            phase = Phase.WALKING;
            return;
        }
    }

    /** An observed candidate that is no longer a storage block loses its ledger entry (a blocked lid or a nearby spawner keeps it). */
    private void dropIfNotStorage(AIPlayerEntity bot, BlockPos pos) {
        if (!ContainerAction.isStorageBlock(bot.level().getBlockState(pos))) {
            ContainerAction.forget(bot, pos);
        }
    }

    private void noMoreContainers(AIPlayerEntity bot) {
        if (mode == Mode.DEPOSIT && !explicitTarget() && !placedFallbackChest
                && InventoryAction.findItem(bot, Items.CHEST).isPresent()) {
            placedFallbackChest = true;
            phase = Phase.PLACING;
            return;
        }
        if (transferred > 0) {
            complete();
            return;
        }
        if (explicitTarget()) {
            fail(doneReason.isBlank() ? "no_container_at: " + shortPos(requestedContainerPos) : doneReason);
            return;
        }
        fail(mode == Mode.WITHDRAW && !doneReason.isBlank() ? doneReason : "no_container");
    }

    private BlockPos spawnerCheckedPos;
    private boolean spawnerCheckedClose;
    private boolean spawnerCheckedResult;

    /**
     * Junk only: an observed spawner near the chest makes it structure loot the bot must not touch.
     * The scan is costly, so it runs once per container while far and once more when in reach (the
     * spawner may only come into view when close); the result is reused on every tick in between.
     */
    private boolean spawnerNear(AIPlayerEntity bot, BlockPos pos) {
        boolean close = bot.getEyePosition().distanceToSqr(pos.getCenter()) <= REACH_SQUARED;
        if (!pos.equals(spawnerCheckedPos) || (close && !spawnerCheckedClose)) {
            spawnerCheckedPos = pos.immutable();
            spawnerCheckedClose = close;
            spawnerCheckedResult = StorageTargets.nearObservedSpawner(bot, pos);
        }
        return spawnerCheckedResult;
    }

    private boolean usable(AIPlayerEntity bot, BlockPos pos) {
        if (junkOnly && spawnerNear(bot, pos)) {
            return false;
        }
        return anyContainerAllowed()
                ? ContainerAction.resolve(bot, pos).isPresent()
                : ContainerAction.isOpenableStorage(bot.level(), pos);
    }

    private void walkToContainer(AIPlayerEntity bot) {
        if (containerPos == null) {
            phase = Phase.FINDING;
            return;
        }
        boolean observable = ContainerAction.canSee(bot, containerPos);
        if (observable && !usable(bot, containerPos)) {
            dropIfNotStorage(bot, containerPos);
            advanceCandidate();
            return;
        }
        if (observable && bot.getEyePosition().distanceToSqr(containerPos.getCenter()) <= REACH_SQUARED) {
            bot.getActionPack().stopAll();
            phase = Phase.TRANSFERRING;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            // Could not get there (or the walk ended short of sight): try the next candidate.
            doneReason = "unreachable_container";
            advanceCandidate();
        }
    }

    private void transfer(AIPlayerEntity bot) {
        if (containerPos == null || !ContainerAction.inReachAndSight(bot, containerPos)) {
            phase = Phase.FINDING;
            containerPos = null;
            return;
        }
        // Opening is what teaches the bot the contents (recorded in its ledger); the transfer below
        // works through the container it just opened.
        Container container = ContainerAction.open(bot, containerPos, anyContainerAllowed()).orElse(null);
        if (container == null) {
            if (anyContainerAllowed()) {
                fail("container_missing");
                return;
            }
            advanceCandidate();
            return;
        }
        if (mode == Mode.INSPECT) {
            complete(); // open() already recorded what is inside
            return;
        }
        int remaining = targetCount == Integer.MAX_VALUE ? 64 : targetCount - transferred;
        if (remaining <= 0) {
            complete();
            return;
        }
        ContainerAction.TransferResult result = mode == Mode.DEPOSIT
                ? depositStep(bot, container, remaining)
                : ContainerAction.withdraw(bot, containerPos, container, item, remaining);
        if (result.movedAny()) {
            transferred += result.count();
            if (targetCount != Integer.MAX_VALUE && transferred >= targetCount) {
                complete();
            }
            return;
        }
        doneReason = result.reason();
        boolean moreCandidates = !candidates.isEmpty();
        if (mode == Mode.WITHDRAW && transferred < targetCount) {
            if (moreCandidates) {
                advanceCandidate(); // the remembered contents were stale: try the next container
                return;
            }
            fail(doneReason.isBlank() ? "missing " + BuiltInRegistries.ITEM.getKey(item) + " x" + (targetCount - transferred) : doneReason);
            return;
        }
        if ("container_full".equals(doneReason)) {
            if (moreCandidates || canPlaceFallbackChest(bot)) {
                advanceCandidate();
                return;
            }
            if (transferred == 0) {
                fail(doneReason);
                return;
            }
        }
        // This path calls complete() even when fewer items moved than were requested (e.g. the
        // container ran out of space or of the item partway through). task_completed alone reads
        // as full success with no hint that the request was only partly satisfied, so log the
        // shortfall here -- the only place that knows both the target and the stop reason.
        if (targetCount != Integer.MAX_VALUE && transferred < targetCount) {
            BotLog.action(bot, "container_partial_complete",
                    "item", item == null ? "all" : BuiltInRegistries.ITEM.getKey(item).toString(),
                    "requested", targetCount, "transferred", transferred,
                    "reason", doneReason.isBlank() ? "no_more_available" : doneReason);
        }
        complete();
    }

    private boolean canPlaceFallbackChest(AIPlayerEntity bot) {
        return mode == Mode.DEPOSIT && !explicitTarget() && !placedFallbackChest
                && InventoryAction.findItem(bot, Items.CHEST).isPresent();
    }

    private ContainerAction.TransferResult depositStep(AIPlayerEntity bot, Container container, int remaining) {
        if (junkOnly) {
            Optional<InventoryPolicy.Stow> stow = InventoryPolicy.nextStow(bot);
            if (stow.isEmpty()) {
                return ContainerAction.TransferResult.failed("nothing_to_deposit");
            }
            Item junk = stow.get().item();
            return ContainerAction.deposit(bot, containerPos, container,
                    stack -> stack.is(junk) && !InventoryPolicy.isProtected(stack),
                    Math.min(remaining, stow.get().count()));
        }
        return ContainerAction.deposit(bot, containerPos, container, depositFilter(), remaining);
    }

    private Predicate<ItemStack> depositFilter() {
        if (item != null) {
            return stack -> stack.is(item);
        }
        if (allExceptTools) {
            return stack -> !ContainerAction.isReservedTool(stack);
        }
        return stack -> true;
    }

    /**
     * Last resort for a deposit with no usable container: a chest the bot carries is placed next
     * to it (BuildAction proves a visible, reachable support face) and becomes the target.
     */
    private void placeChest(AIPlayerEntity bot) {
        OptionalInt slot = InventoryAction.findItem(bot, Items.CHEST);
        BlockPos cell = slot.isPresent() ? chestCell(bot) : null;
        if (cell == null) {
            failNoContainer(bot);
            return;
        }
        InventoryAction.equipFromSlot(bot, slot.getAsInt());
        ActionResult result = BuildAction.placeBlockAt(bot, cell);
        if (result.isFailed()) {
            BotLog.warn(LogCategory.TASK, bot, "container_fallback_chest_failed",
                    "cell", cell.toShortString(), "reason", result.reason());
            failNoContainer(bot);
            return;
        }
        BotLog.action(bot, "container_fallback_chest_placed", "pos", cell.toShortString());
        candidates.clear();
        candidates.add(cell.immutable());
        containerPos = null;
        phase = Phase.FINDING;
    }

    /**
     * The carried-chest fallback could not be used. Items that already went into a container are
     * a real (partial) success, so the task completes with a logged note instead of failing;
     * with nothing transferred it is the plain no_container failure.
     */
    private void failNoContainer(AIPlayerEntity bot) {
        if (transferred > 0) {
            BotLog.action(bot, "container_partial_complete",
                    "item", item == null ? "all" : BuiltInRegistries.ITEM.getKey(item).toString(),
                    "transferred", transferred, "reason", "no_container_for_the_rest");
            complete();
            return;
        }
        fail("no_container");
    }

    /** A free horizontal neighbour with open air above it (so the lid can open), a visible support and no entity in it. */
    private static BlockPos chestCell(AIPlayerEntity bot) {
        BlockPos origin = bot.blockPosition();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = origin.relative(direction);
            if (bot.level().getBlockState(candidate).isAir()
                    && bot.level().getBlockState(candidate.above()).isAir()
                    && bot.level().getEntities(bot, new AABB(candidate)).isEmpty()
                    && BuildAction.canAcceptPlacementAt(bot, candidate)) {
                return candidate.immutable();
            }
        }
        return null;
    }

    public static Optional<BlockPos> nearestContainer(AIPlayerEntity bot, int radius) {
        return nearestContainerNear(bot, bot.blockPosition(), radius);
    }

    /**
     * Nearest storage block the bot can see around {@code center}. Kind first, then the vanilla lid
     * rule, spawner-loot exclusion, then (last, because it costs six rays) line of sight.
     */
    public static Optional<BlockPos> nearestContainerNear(AIPlayerEntity bot, BlockPos center, int radius) {
        List<BlockPos> found = StorageTargets.observedStorage(bot, center, StorageTargets.clampRadius(radius), false);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private static Optional<BlockPos> rememberedContainer(AIPlayerEntity bot) {
        return BotMemoryStore.INSTANCE.of(bot.getUUID())
                .placeIn(bot.level(), "depot", "home", "base", "chest")
                .flatMap(pos -> ContainerAction.canSee(bot, pos)
                        && ContainerAction.isOpenableStorage(bot.level(), pos)
                        ? Optional.of(pos.immutable())
                        : nearestContainerNear(bot, pos, 4));
    }

    private static String shortPos(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}

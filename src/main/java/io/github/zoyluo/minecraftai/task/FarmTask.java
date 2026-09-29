package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.ContainerAction;
import io.github.zoyluo.minecraftai.action.FarmAction;
import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.memory.BotMemoryStore;
import io.github.zoyluo.minecraftai.util.BlockPosText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public final class FarmTask extends AbstractTask {
    private enum Phase {
        SURVEY,
        GOTO,
        TILL,
        PLANT,
        HARVEST,
        NEXT,
        DEPOSIT,
        DEPOSIT_GOTO,
        DEPOSIT_TRANSFER,
        DONE
    }

    private static final int DEPOSIT_RADIUS = 8;
    private static final int DEPOSIT_INTERVAL_ACTIONS = 16;
    private static final double REACH_SQUARED = 20.25D;

    private final BlockPos areaCenter;
    private final int radius;
    private final Item seed;
    private final Block crop;
    private final boolean keepTending;
    private final boolean harvestOnly;
    // P3: quantity-limited mode (used by GoalExecutor's FARM step). When produceItem != null,
    // complete() fires as soon as targetHarvest units of the produce have been collected.
    private final Item produceItem;
    private final int targetHarvest;
    private int produceBaseline;
    private final List<FarmTarget> targets = new ArrayList<>();
    private final List<BlockPos> depositContainers = new ArrayList<>();
    private Phase phase = Phase.SURVEY;
    private FarmTarget current;
    private BlockPos basePos;
    private BlockPos depositContainerPos;
    private int depositContainerIndex;
    private int completedActions;
    private int lastDepositActionCount;
    private int waitTicks;
    private boolean waitingForMaturity; // After planting, stay put waiting for crops to mature naturally (quantity-limited mode); not stuck
    private String note = "";

    public FarmTask(BlockPos areaCenter, int radius, Item seed, Block crop, boolean keepTending, boolean harvestOnly) {
        this(areaCenter, radius, seed, crop, keepTending, harvestOnly, null, 0);
    }

    /** P3: quantity-limited constructor — completes once produceItem reaches targetHarvest units (for GoalExecutor's FARM step). */
    public FarmTask(BlockPos areaCenter, int radius, Item seed, Block crop, boolean keepTending,
                    boolean harvestOnly, Item produceItem, int targetHarvest) {
        this.areaCenter = areaCenter.immutable();
        this.radius = Math.max(1, radius);
        this.seed = seed;
        this.crop = crop;
        this.keepTending = keepTending;
        this.harvestOnly = harvestOnly;
        this.produceItem = produceItem;
        this.targetHarvest = Math.max(0, targetHarvest);
    }

    @Override
    public String name() {
        return harvestOnly ? "harvest" : "farm";
    }

    @Override
    public String describe() {
        return name() + " crop=" + crop + " center=" + BlockPosText.compact(areaCenter) + " radius=" + radius
                + " done=" + completedActions + " phase=" + phase + (note.isBlank() ? "" : " note=" + note);
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        if (keepTending) {
            return Math.min(0.95D, completedActions / 16.0D);
        }
        int total = completedActions + targets.size() + (current == null ? 0 : 1);
        return total == 0 ? 0.0D : Math.min(0.95D, (double) completedActions / total);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.SURVEY;
        lastDepositActionCount = completedActions;
        waitingForMaturity = false;
        produceBaseline = produceItem == null ? 0 : InventoryAction.countItem(bot, produceItem);
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        // P3: quantity-limited mode — complete as soon as the target produce count is reached (checked before any other phase logic).
        if (produceItem != null
                && InventoryAction.countItem(bot, produceItem) - produceBaseline >= targetHarvest) {
            complete();
            return;
        }
        if (!keepTending && produceItem == null && elapsed > 2400) {
            // Quantity-limited mode (produceItem != null) needs to wait for crops to mature naturally, so it
            // uses the 12000t quota timeout below instead and is exempt from this 2400t short timeout.
            // phase/note are attached together: this timeout can fire in any of SURVEY/GOTO/TILL/PLANT/
            // HARVEST/DEPOSIT*, and "farm_timeout" alone wouldn't tell us which step it stalled on or why
            // the last action failed.
            fail("farm_timeout phase=" + phase + (note.isBlank() ? "" : " note=" + note));
            return;
        }
        // P3: quantity-limited mode has its own hard timeout (waiting for crops to mature takes time,
        // but not forever); it reuses keepTending's patrol logic.
        if (produceItem != null && elapsed > 12000) {
            fail("farm_quota_timeout collected="
                    + (InventoryAction.countItem(bot, produceItem) - produceBaseline) + "/" + targetHarvest);
            return;
        }
        switch (phase) {
            case SURVEY -> survey(bot);
            case GOTO -> goToTarget(bot);
            case TILL -> till(bot);
            case PLANT -> plant(bot);
            case HARVEST -> harvest(bot);
            case NEXT -> next(bot);
            case DEPOSIT -> prepareDeposit(bot);
            case DEPOSIT_GOTO -> goToDepositContainer(bot);
            case DEPOSIT_TRANSFER -> depositTransfer(bot);
            case DONE -> done(bot);
        }
    }

    private void survey(AIPlayerEntity bot) {
        targets.clear();
        current = null;
        ServerLevel world = bot.level();
        boolean hasSeeds = !harvestOnly && InventoryAction.countItem(bot, seed) > 0;
        BlockPos.betweenClosedStream(areaCenter.offset(-radius, -1, -radius), areaCenter.offset(radius, 1, radius))
                .map(BlockPos::immutable)
                .filter(pos -> io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlock(bot, pos)
                        || io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlock(bot, pos.above()))
                .forEach(pos -> addTargetIfUseful(world, pos, hasSeeds));
        targets.sort(Comparator.comparingDouble(pos -> pos.ground().distSqr(bot.blockPosition())));
        if (targets.isEmpty()) {
            if (!harvestOnly && InventoryAction.countItem(bot, seed) <= 0 && completedActions == 0) {
                fail("missing " + seed + " x1");
                return;
            }
            // Wait for maturity (a lifeline for real-terrain farming to produce bread): if crops are planted
            // but not yet mature, and quantity-limited mode still hasn't hit its produce target, don't go
            // DONE — stay put and let the crops mature naturally (driven by random tick); once mature, the
            // next survey will generate a HARVEST target to collect them.
            // The old logic went DONE as soon as targets emptied and never waited for maturity (real_wheat
            // testing showed 6-12 till/plant cycles but harvest=0); the lab food_farm test only passed
            // because perTick force-ripened crops. The 12000t quota timeout above is the backstop, so even
            // if crops never mature we won't wait forever.
            boolean needMore = produceItem != null
                    && InventoryAction.countItem(bot, produceItem) - produceBaseline < targetHarvest;
            if (!harvestOnly && needMore && hasImmatureCrops(bot, world)) {
                waitingForMaturity = true;
                return; // Stay in SURVEY and keep waiting for maturity next tick (don't switch to DONE); exempt from StuckWatcher while isWaiting()
            }
            waitingForMaturity = false;
            if (keepTending && hasDepositItems(bot)) {
                phase = Phase.DEPOSIT;
            } else {
                phase = Phase.DONE;
            }
            return;
        }
        waitingForMaturity = false;
        phase = Phase.NEXT;
    }

    private void addTargetIfUseful(ServerLevel world, BlockPos ground, boolean hasSeeds) {
        BlockPos cropPos = ground.above();
        if (world.getBlockState(cropPos).is(crop) && FarmAction.isMature(world, cropPos)) {
            targets.add(new FarmTarget(ground, TargetAction.HARVEST));
            return;
        }
        if (harvestOnly || world.getBlockState(cropPos).is(crop) || !world.getBlockState(cropPos).isAir()) {
            return;
        }
        if (!hasSeeds) {
            return;
        }
        if (world.getBlockState(ground).is(Blocks.FARMLAND)) {
            targets.add(new FarmTarget(ground, TargetAction.PLANT));
            return;
        }
        if (FarmAction.isTillable(world.getBlockState(ground))) {
            targets.add(new FarmTarget(ground, TargetAction.TILL_PLANT));
        }
    }

    private void next(AIPlayerEntity bot) {
        if (keepTending && completedActions - lastDepositActionCount >= DEPOSIT_INTERVAL_ACTIONS
                && hasDepositItems(bot)) {
            phase = Phase.DEPOSIT;
            return;
        }
        current = targets.isEmpty() ? null : targets.remove(0);
        if (current == null) {
            phase = Phase.SURVEY;
            return;
        }
        phase = Phase.GOTO;
        goToTarget(bot);
    }

    private void goToTarget(AIPlayerEntity bot) {
        if (current == null) {
            phase = Phase.NEXT;
            return;
        }
        BlockPos focus = current.action() == TargetAction.HARVEST ? current.ground().above() : current.ground();
        if (bot.getEyePosition().distanceTo(focus.getCenter()) <= 4.5D) {
            bot.getActionPack().stopAll();
            phase = switch (current.action()) {
                case HARVEST -> Phase.HARVEST;
                case PLANT -> Phase.PLANT;
                case TILL_PLANT -> Phase.TILL;
            };
            return;
        }
        BlockPos stand = adjacentStandPos(bot, current.ground());
        if (stand == null) {
            note = "unreachable " + BlockPosText.compact(current.ground());
            phase = Phase.NEXT;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startPathTo(stand);
        }
    }

    private void till(AIPlayerEntity bot) {
        if (InventoryAction.countItem(bot, seed) <= 0) {
            note = "plant_skipped:missing " + seed + " x1";
            phase = Phase.NEXT;
            return;
        }
        ActionResult result = FarmAction.till(bot, current.ground());
        if (result.isFailed()) {
            note = result.reason();
            phase = Phase.NEXT;
            return;
        }
        phase = Phase.PLANT;
    }

    private void plant(AIPlayerEntity bot) {
        if (InventoryAction.countItem(bot, seed) <= 0) {
            note = "plant_skipped:missing " + seed + " x1";
            phase = Phase.NEXT;
            return;
        }
        ActionResult result = FarmAction.plant(bot, current.ground(), seed, crop);
        if (result.isFailed()) {
            note = result.reason();
        } else {
            completedActions++;
        }
        phase = Phase.NEXT;
    }

    private void prepareDeposit(AIPlayerEntity bot) {
        Item item = nextDepositItem(bot);
        if (item == null) {
            finishDeposit();
            return;
        }
        basePos = BotMemoryStore.INSTANCE.of(bot.getUUID())
                .placeIn(bot.level(), "base")
                .orElse(null);
        if (basePos == null) {
            note = "deposit_skipped:no_base";
            // In the keepTending loop this would silently repeat every DEPOSIT_INTERVAL_ACTIONS actions;
            // without this log line, produce would pile up in the inventory forever with no way to tell why
            // from task_completed/the logs (the remembered base was simply never set).
            BotLog.action(bot, "farm_deposit_no_base");
            finishDeposit();
            return;
        }
        depositContainers.clear();
        BlockPos.betweenClosedStream(basePos.offset(-DEPOSIT_RADIUS, -3, -DEPOSIT_RADIUS), basePos.offset(DEPOSIT_RADIUS, 4, DEPOSIT_RADIUS))
                .map(BlockPos::immutable)
                .filter(pos -> io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlock(bot, pos))
                .filter(pos -> ContainerAction.resolve(bot, pos).isPresent())
                .forEach(depositContainers::add);
        depositContainers.sort(Comparator
                .comparing((BlockPos pos) -> !ContainerSupport.containsItem(bot, pos, item))
                .thenComparingDouble(pos -> pos.distSqr(bot.blockPosition())));
        depositContainerIndex = 0;
        selectDepositContainer(bot);
    }

    private void selectDepositContainer(AIPlayerEntity bot) {
        if (nextDepositItem(bot) == null) {
            finishDeposit();
            return;
        }
        if (depositContainerIndex >= depositContainers.size()) {
            note = "deposit_skipped:no_base_container";
            // Same as above: with no usable container, depositing is silently abandoned and the loop would
            // keep retrying the same thing with no trace left behind.
            BotLog.action(bot, "farm_deposit_no_container", "base", BlockPosText.compact(basePos));
            finishDeposit();
            return;
        }
        depositContainerPos = depositContainers.get(depositContainerIndex++);
        if (bot.getEyePosition().distanceToSqr(depositContainerPos.getCenter()) <= REACH_SQUARED) {
            phase = Phase.DEPOSIT_TRANSFER;
            return;
        }
        BlockPos stand = adjacentStandPos(bot, depositContainerPos.below());
        if (stand == null) {
            selectDepositContainer(bot);
            return;
        }
        ActionResult result = bot.getActionPack().startPathTo(stand);
        if (result.isFailed()) {
            note = result.reason();
            selectDepositContainer(bot);
            return;
        }
        phase = Phase.DEPOSIT_GOTO;
    }

    private void goToDepositContainer(AIPlayerEntity bot) {
        if (depositContainerPos == null || ContainerAction.resolve(bot, depositContainerPos).isEmpty()) {
            phase = Phase.DEPOSIT;
            return;
        }
        if (bot.getEyePosition().distanceToSqr(depositContainerPos.getCenter()) <= REACH_SQUARED) {
            bot.getActionPack().stopAll();
            phase = Phase.DEPOSIT_TRANSFER;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            selectDepositContainer(bot);
        }
    }

    private void depositTransfer(AIPlayerEntity bot) {
        if (depositContainerPos == null
                || bot.getEyePosition().distanceToSqr(depositContainerPos.getCenter()) > REACH_SQUARED
                || !io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlock(bot, depositContainerPos)) {
            phase = Phase.DEPOSIT;
            return;
        }
        Container container = ContainerAction.resolve(bot, depositContainerPos).orElse(null);
        if (container == null) {
            selectDepositContainer(bot);
            return;
        }
        Item item = nextDepositItem(bot);
        if (item == null) {
            finishDeposit();
            return;
        }
        ContainerAction.TransferResult result = ContainerAction.depositOne(container, bot, stack -> stack.is(item), maxDepositCount(bot, item));
        if (result.movedAny()) {
            note = "deposited " + item + " x" + result.count();
            return;
        }
        selectDepositContainer(bot);
    }

    private void finishDeposit() {
        lastDepositActionCount = completedActions;
        phase = Phase.DONE;
    }

    private void harvest(AIPlayerEntity bot) {
        ActionResult result = FarmAction.harvest(bot, current.ground().above());
        if (result.isFailed()) {
            note = result.reason();
            phase = Phase.NEXT;
            return;
        }
        completedActions++;
        // The harvested produce/seed drops as an on-ground ItemEntity (FarmAction.harvest breaks the block
        // with dropResources=true, which does not go straight into the inventory). The bot harvests from reach
        // distance (<= 4.5 blocks), so drops more than 1 block from its feet are out of vanilla's automatic
        // pickup range -- forced pickup is required, otherwise countItem(produce) never increases and the
        // harvest/farm goal never completes (farm_wheat_from_scratch testing timed out with 0 wheat in the
        // inventory). forcePickup bypasses the drop's 10-tick pickup delay so it enters the inventory this
        // same tick (same fix approach as DigDownTask).
        HarvestCore.forcePickupNearbyAnyOf(bot, java.util.Set.of(harvestItem(), seed), 5.0D, 4.0D);
        if (!harvestOnly && InventoryAction.countItem(bot, seed) > 0) {
            ActionResult plantResult = FarmAction.plant(bot, current.ground(), seed, crop);
            if (plantResult.isFailed()) {
                note = "replant_failed:" + plantResult.reason();
            }
        } else if (!harvestOnly) {
            note = "replant_skipped:missing " + seed + " x1";
        }
        phase = Phase.NEXT;
    }

    private void done(AIPlayerEntity bot) {
        if (keepTending) {
            waitTicks++;
            if (waitTicks >= 100) {
                waitTicks = 0;
                phase = Phase.SURVEY;
            }
            return;
        }
        complete();
    }

    @Override
    public boolean isWaiting() {
        // While waiting for maturity, the bot standing still at the field edge is normal work (waiting for
        // crops to grow), so it's exempt from being wrongly flagged by StuckWatcher; a genuine stall is
        // caught by the 12000t quota timeout as the backstop.
        return (keepTending && phase == Phase.DONE) || waitingForMaturity;
    }

    // Whether the area has any of this crop that's "planted but not yet mature" -- if so it's worth staying
    // to wait for maturity instead of leaving right after planting (fixes real_wheat harvest=0).
    private boolean hasImmatureCrops(AIPlayerEntity bot, ServerLevel world) {
        return BlockPos.betweenClosedStream(areaCenter.offset(-radius, -1, -radius), areaCenter.offset(radius, 1, radius))
                .filter(ground -> io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlock(bot, ground)
                        || io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlock(bot, ground.above()))
                .anyMatch(ground -> {
                    BlockPos cropPos = ground.above();
                    return world.getBlockState(cropPos).is(crop) && !FarmAction.isMature(world, cropPos);
                });
    }

    private boolean hasDepositItems(AIPlayerEntity bot) {
        return nextDepositItem(bot) != null;
    }

    private Item nextDepositItem(AIPlayerEntity bot) {
        Item harvest = harvestItem();
        if (harvest != seed && InventoryAction.countItem(bot, harvest) > 0) {
            return harvest;
        }
        if (InventoryAction.countItem(bot, seed) > seedReserve()) {
            return seed;
        }
        return null;
    }

    private int maxDepositCount(AIPlayerEntity bot, Item item) {
        int count = InventoryAction.countItem(bot, item);
        if (item == seed) {
            return Math.max(0, count - seedReserve());
        }
        return count;
    }

    private int seedReserve() {
        int area = (radius * 2 + 1) * (radius * 2 + 1);
        return Math.max(8, area);
    }

    private Item harvestItem() {
        if (crop == Blocks.WHEAT) {
            return Items.WHEAT;
        }
        if (crop == Blocks.CARROTS) {
            return Items.CARROT;
        }
        if (crop == Blocks.POTATOES) {
            return Items.POTATO;
        }
        return seed;
    }

    private static BlockPos adjacentStandPos(AIPlayerEntity bot, BlockPos target) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = target.relative(direction).above();
            if (io.github.zoyluo.minecraftai.pathfinding.Standability.isStandable(bot.level(), candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private enum TargetAction {
        TILL_PLANT,
        PLANT,
        HARVEST
    }

    private record FarmTarget(BlockPos ground, TargetAction action) {
    }
}

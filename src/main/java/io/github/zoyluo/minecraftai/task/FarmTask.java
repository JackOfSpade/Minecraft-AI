package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.ContainerAction;
import io.github.zoyluo.minecraftai.action.FarmAction;
import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.memory.BotMemoryStore;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.util.BlockPosText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public final class FarmTask extends AbstractTask implements CheckpointableTask {
    /** Ten real-time minutes at Minecraft's ordinary twenty ticks per second. */
    public static final int DEFAULT_COLLECTION_DURATION_TICKS = 20 * 60 * 10;
    private enum Phase {
        SURVEY,
        GOTO,
        TILL,
        PLANT,
        HARVEST,
        PICKUP,
        NEXT,
        DEPOSIT,
        DEPOSIT_GOTO,
        DEPOSIT_TRANSFER,
        /** A short navigation leg admitted from terrain currently visible to the bot. */
        EXPLORE,
        DONE
    }

    private static final int DEPOSIT_RADIUS = 8;
    private static final int DEPOSIT_INTERVAL_ACTIONS = 16;
    private static final double REACH_SQUARED = 20.25D;
    private static final int PICKUP_BUDGET_TICKS = 100;   // walking over harvest drops before moving on
    private static final int PICKUP_DROP_SETTLE_TICKS = 10; // allow a just-broken crop's vanilla drops to clear their pickup delay
    private static final double PICKUP_RADIUS = 8.0D;
    private static final int WAIT_SURVEY_INTERVAL = 10;   // while waiting for maturity, re-survey this often
    private static final int BONE_MEAL_INTERVAL = 4;      // ticks between bone meal clicks
    private static final int MAX_TILL_ATTEMPTS = 3;       // coarse dirt and rooted dirt need a second click
    private static final int BONE_CRAFT_MAX_BONES = 4;     // bones turned into bone meal per craft (3 meal each)
    static final int FAILED_CELL_TTL_TICKS = 600; // a cell whose click proof failed is skipped this long
    private static final int CROP_LIGHT_MIN = 8;          // vanilla crop rule: raw brightness of the crop cell
    private static final int EXPLORE_MAX_HOPS = 16;
    private static final int EXPLORE_MOVE_LIMIT = 300;

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
    /** Positive only for an open-ended collection window, never for a fixed harvest quota. */
    private final int collectionDurationTicks;
    /** Restored accounting only; farm targets and in-flight actions are deliberately re-observed. */
    private final TimedCollectionCheckpoint restoredTimedCollection;
    private final boolean invalidTimedCollectionCheckpoint;
    private int produceBaseline;
    /** Latest factual yield above the task-start baseline (also keeps status reporting side-effect free). */
    private int collectedProduce;
    private final List<FarmTarget> targets = new ArrayList<>();
    private final List<BlockPos> depositContainers = new ArrayList<>();
    private final BlockMiner harvestMiner = new BlockMiner();
    // grounds whose click proof failed -> task tick of the failure; retried after FAILED_CELL_TTL_TICKS so one
    // transient refusal (a mob in the way, a momentary view block) is not permanent for a long-running task
    private final Map<BlockPos, Integer> failedCells = new HashMap<>();
    private final List<BlockPos> immatureCrops = new ArrayList<>();
    private Phase phase = Phase.SURVEY;
    private FarmTarget current;
    private BlockPos basePos;
    private BlockPos depositContainerPos;
    private int depositContainerIndex;
    private int completedActions;
    private int lastDepositActionCount;
    private int waitTicks;
    private int pickupTicks;
    private int tillAttempts;
    private int lastWaitSurvey = -WAIT_SURVEY_INTERVAL;
    private int lastBoneMeal = -BONE_MEAL_INTERVAL;
    private int darkCells;
    private CraftTask boneMealCraft;
    private boolean boneMealCraftFailed;
    private boolean waitingForMaturity; // After planting, stay put waiting for crops to mature naturally (quantity-limited mode); not stuck
    /** Resource-gathering crop requests may widen their local field view only by safe, observed walk hops. */
    private final ObservedSearchHops observedCropSearch = new ObservedSearchHops(EXPLORE_MAX_HOPS);
    private BlockPos exploreTarget;
    private BlockPos exploreStart;
    private int exploreStartedTick;
    private String note = "";

    public FarmTask(BlockPos areaCenter, int radius, Item seed, Block crop, boolean keepTending, boolean harvestOnly) {
        this(areaCenter, radius, seed, crop, keepTending, harvestOnly, null, 0, 0, Map.of());
    }

    /** P3: quantity-limited constructor — completes once produceItem reaches targetHarvest units (for GoalExecutor's FARM step). */
    public FarmTask(BlockPos areaCenter, int radius, Item seed, Block crop, boolean keepTending,
                    boolean harvestOnly, Item produceItem, int targetHarvest) {
        this(areaCenter, radius, seed, crop, keepTending, harvestOnly, produceItem, targetHarvest, 0,
                Map.of());
    }

    /**
     * Tends an observed crop field for a fixed window. Existing produce becomes the baseline,
     * never a completion shortcut; the task reports the factual new yield at the deadline.
     */
    public static FarmTask collectForDuration(BlockPos areaCenter, int radius, Item seed,
                                              Block crop, Item produceItem) {
        return collectForDuration(areaCenter, radius, seed, crop, produceItem,
                DEFAULT_COLLECTION_DURATION_TICKS);
    }

    /** Testable duration variant used by the timed goal step. */
    public static FarmTask collectForDuration(BlockPos areaCenter, int radius, Item seed,
                                              Block crop, Item produceItem, int durationTicks) {
        return collectForDuration(areaCenter, radius, seed, crop, produceItem, durationTicks, Map.of());
    }

    /** Restores only the elapsed/baseline session accounting for a timed crop collection. */
    public static FarmTask collectForDuration(BlockPos areaCenter, int radius, Item seed,
                                              Block crop, Item produceItem, int durationTicks,
                                              Map<String, String> checkpoint) {
        return new FarmTask(areaCenter, radius, seed, crop, true, false, produceItem, 1,
                Math.max(1, durationTicks), checkpoint);
    }

    private FarmTask(BlockPos areaCenter, int radius, Item seed, Block crop, boolean keepTending,
                     boolean harvestOnly, Item produceItem, int targetHarvest,
                     int collectionDurationTicks, Map<String, String> checkpoint) {
        this.areaCenter = areaCenter.immutable();
        this.radius = Math.max(1, radius);
        this.seed = seed;
        this.crop = crop;
        this.keepTending = keepTending;
        this.harvestOnly = harvestOnly;
        this.produceItem = produceItem;
        this.targetHarvest = Math.max(0, targetHarvest);
        this.collectionDurationTicks = Math.max(0, collectionDurationTicks);
        Map<String, String> values = checkpoint == null ? Map.of() : checkpoint;
        this.restoredTimedCollection = isTimedCollection()
                ? TimedCollectionCheckpoint.decode(values).orElse(null) : null;
        this.invalidTimedCollectionCheckpoint = isTimedCollection() && !values.isEmpty()
                && (restoredTimedCollection == null
                || restoredTimedCollection.durationTicks() != this.collectionDurationTicks);
    }

    @Override
    public String name() {
        return harvestOnly ? "harvest" : "farm";
    }

    @Override
    public String describe() {
        return name() + " crop=" + crop + " center=" + BlockPosText.compact(areaCenter) + " radius=" + radius
                + " done=" + completedActions
                + (isTimedCollection() ? " collected=" + collectedProduce() + " elapsed=" + elapsed
                + "/" + collectionDurationTicks : "")
                + " phase=" + phase + (note.isBlank() ? "" : " note=" + note);
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        if (isTimedCollection()) {
            return Math.min(0.99D, (double) elapsed / collectionDurationTicks);
        }
        if (keepTending) {
            return Math.min(0.95D, completedActions / 16.0D);
        }
        int total = completedActions + targets.size() + (current == null ? 0 : 1);
        return total == 0 ? 0.0D : Math.min(0.95D, (double) completedActions / total);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        if (invalidTimedCollectionCheckpoint) {
            fail("farm_invalid_timed_collection_checkpoint");
            return;
        }
        phase = Phase.SURVEY;
        lastDepositActionCount = completedActions;
        failedCells.clear();
        immatureCrops.clear();
        harvestMiner.cancel(bot);
        pickupTicks = 0;
        tillAttempts = 0;
        lastWaitSurvey = -WAIT_SURVEY_INTERVAL;
        lastBoneMeal = -BONE_MEAL_INTERVAL;
        boneMealCraft = null;
        boneMealCraftFailed = false;
        waitingForMaturity = false;
        observedCropSearch.reset();
        clearExploreLeg();
        exploreStartedTick = 0;
        int inventoryNow = produceItem == null ? 0 : InventoryAction.countItem(bot, produceItem);
        produceBaseline = restoredTimedCollection == null ? inventoryNow
                : restoredTimedCollection.inventoryBaseline();
        collectedProduce = restoredTimedCollection == null ? 0 : Math.max(
                restoredTimedCollection.collected(), Math.max(0, inventoryNow - produceBaseline));
        if (restoredTimedCollection != null) {
            elapsed = restoredTimedCollection.elapsedTicks();
        }
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        collectedProduce = produceItem == null ? 0 : Math.max(collectedProduce,
                Math.max(0, InventoryAction.countItem(bot, produceItem) - produceBaseline));
        // Do not abandon a break or its pickup/replant transaction at the precise deadline. Once
        // it settles, no fresh work is admitted and the task reports its actual new yield.
        if (isTimedCollection() && elapsed >= collectionDurationTicks && !settlingHarvest()) {
            finishTimedCollection(bot);
            return;
        }
        // P3: quantity-limited mode — complete as soon as the target produce count is reached (checked before any other phase logic).
        // (Not mid-harvest: the picked-up drops and the replant of the cell just harvested finish first.)
        if (!isTimedCollection() && produceItem != null
                && phase != Phase.HARVEST && phase != Phase.PICKUP
                && collectedProduce() >= targetHarvest) {
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
        if (!isTimedCollection() && produceItem != null && elapsed > 12000) {
            fail("farm_quota_timeout collected="
                    + collectedProduce() + "/" + targetHarvest);
            return;
        }
        switch (phase) {
            case SURVEY -> survey(bot);
            case GOTO -> goToTarget(bot);
            case TILL -> till(bot);
            case PLANT -> plant(bot);
            case HARVEST -> harvest(bot);
            case PICKUP -> pickup(bot);
            case NEXT -> next(bot);
            case DEPOSIT -> prepareDeposit(bot);
            case DEPOSIT_GOTO -> goToDepositContainer(bot);
            case DEPOSIT_TRANSFER -> depositTransfer(bot);
            case EXPLORE -> explore(bot);
            case DONE -> done(bot);
        }
    }

    private boolean isTimedCollection() {
        return collectionDurationTicks > 0;
    }

    /** A high-level produce request may widen its observed search; coordinate-only farm jobs may not. */
    private boolean isResourceCollection() {
        return isTimedCollection() || produceItem != null;
    }

    private int collectedProduce() {
        return collectedProduce;
    }

    @Override
    public Map<String, String> checkpoint() {
        if (!isTimedCollection() || invalidTimedCollectionCheckpoint) {
            return Map.of();
        }
        return new TimedCollectionCheckpoint(collectionDurationTicks,
                Math.min(collectionDurationTicks, elapsed), produceBaseline, collectedProduce).encode();
    }

    private boolean settlingHarvest() {
        return phase == Phase.PICKUP || (phase == Phase.HARVEST && harvestMiner.target() != null);
    }

    private void finishTimedCollection(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
        int collected = Math.max(collectedProduce,
                Math.max(0, InventoryAction.countItem(bot, produceItem) - produceBaseline));
        BotLog.action(bot, "farm_timed_deadline", "crop", String.valueOf(crop),
                "collected", collected, "duration_ticks", collectionDurationTicks,
                "outcome", collected > 0 ? "complete" : GatherQuotaTask.NO_RESOURCE_FOUND_BY_DEADLINE);
        if (collected > 0) {
            complete();
        } else {
            fail(GatherQuotaTask.NO_RESOURCE_FOUND_BY_DEADLINE);
        }
    }

    private void survey(AIPlayerEntity bot) {
        if (waitingForMaturity && elapsed - lastWaitSurvey < WAIT_SURVEY_INTERVAL) {
            // Between waiting surveys only the bone meal clicks (which have their own rate limit) run.
            boneMealStep(bot);
            return;
        }
        lastWaitSurvey = elapsed;
        targets.clear();
        current = null;
        darkCells = 0;
        ServerLevel world = bot.level();
        boolean hasSeeds = !harvestOnly && InventoryAction.countItem(bot, seed) > 0;
        // canObserveBlock proves that a face centre of one block is struck, which is the wrong question for a field:
        // a crop is outline-only, interior farmland is 15/16 high and a seed goes into a cell that may be empty air.
        // The farm-cell query samples the top of the cell's real shape with outline rays (and accepts the empty cell
        // above a field), so farm cells are judged by what a player looking down at the field sees.
        BlockPos surveyCenter = isResourceCollection() ? bot.blockPosition() : areaCenter;
        BlockPos.betweenClosedStream(surveyCenter.offset(-radius, -1, -radius), surveyCenter.offset(radius, 1, radius))
                .map(BlockPos::immutable)
                .filter(pos -> !isFailed(pos))
                // Cheap first: the block-state verdict is a few lookups, the outline ray tests are not.
                .map(pos -> new Verdict(pos, classify(world, pos, hasSeeds)))
                .filter(verdict -> verdict.kind() != Kind.NONE)
                .filter(verdict -> ObservableWorldQuery.canObserveFarmCell(bot, verdict.ground())
                        || ObservableWorldQuery.canObserveFarmCell(bot, verdict.ground().above()))
                .forEach(this::addTarget);
        targets.sort(Comparator.comparingDouble(pos -> pos.ground().distSqr(bot.blockPosition())));
        if (targets.isEmpty()) {
            if (!harvestOnly && InventoryAction.countItem(bot, seed) <= 0 && completedActions == 0) {
                if (!isResourceCollection()) {
                    fail("missing " + seed + " x1");
                    return;
                }
                // A resource-gathering player request stays alive through its collection window rather
                // than declaring success/failure from the initial inventory. Another observed
                // crop patch can appear on a later survey (or a player can supply seed); the
                // deadline owns the definitive "none found" outcome.
                note = "waiting_for_seed_or_observed_crop";
                if (startResourceExploration(bot)) {
                    return;
                }
                phase = Phase.DONE;
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
            boolean needMore = isTimedCollection() || produceItem != null
                    && InventoryAction.countItem(bot, produceItem) - produceBaseline < targetHarvest;
            collectImmatureCrops(bot, world);
            if (!harvestOnly && needMore && !immatureCrops.isEmpty()) {
                waitingForMaturity = true;
                boneMealStep(bot);
                return; // Stay in SURVEY and keep waiting for maturity (don't switch to DONE); exempt from StuckWatcher while isWaiting()
            }
            waitingForMaturity = false;
            if (darkCells > 0 && needMore && completedActions == 0) {
                // Crops need light >= 8 at the crop cell (vanilla rule); seeds cannot be planted here, and
                // waiting would only burn the whole quota timeout.
                if (!isResourceCollection()) {
                    fail("farm_area_too_dark cells=" + darkCells);
                    return;
                }
                note = "waiting_for_observed_lit_crop_area";
                if (startResourceExploration(bot)) {
                    return;
                }
                phase = Phase.DONE;
                return;
            }
            // A resource collection's inventory delta is its player-facing receipt. Do not move
            // produce into a chest during that window or a real harvest could be erased from the
            // baseline comparison and falsely reported as "none found."
            if (!isResourceCollection() && keepTending && hasDepositItems(bot)) {
                phase = Phase.DEPOSIT;
            } else if (isResourceCollection() && startResourceExploration(bot)) {
                return;
            } else {
                phase = Phase.DONE;
            }
            return;
        }
        waitingForMaturity = false;
        phase = Phase.NEXT;
    }

    /**
     * Opens one observation-fenced local leg when a resource-gathering farm survey is empty. The compass
     * heading is never a route destination: {@link ObservedSearchHops} resolves it to a visible
     * standable goal first, and the next survey reads only terrain revealed from that position.
     */
    private boolean startResourceExploration(AIPlayerEntity bot) {
        if (!isResourceCollection()) {
            return false;
        }
        ObservedSearchHops.Attempt attempt = observedCropSearch.begin(bot, null);
        if (!attempt.started()) {
            BotLog.action(bot, "farm_explore_hop_refused",
                    "attempt", attempt.number(),
                    "reason", attempt.reason());
            if (observedCropSearch.exhausted()) {
                // A finite episode guards individual routes, not the whole ten-minute request.
                // Start another from the factual current position; no hidden terrain is read.
                observedCropSearch.reset();
                BotLog.action(bot, "farm_explore_episode_reset", "elapsed_ticks", elapsed);
            }
            return true;
        }
        exploreTarget = attempt.observedGoal();
        exploreStart = bot.blockPosition().immutable();
        exploreStartedTick = elapsed;
        phase = Phase.EXPLORE;
        note = "exploring_for_observed_crop_area";
        BotLog.action(bot, "farm_explore_hop",
                "attempt", attempt.number(),
                "heading", attempt.heading().toShortString(),
                "to", exploreTarget.toShortString(),
                "max_hop", ObservedSearchHops.hopDistance());
        return true;
    }

    private void explore(AIPlayerEntity bot) {
        if (exploreTarget == null || bot.blockPosition().equals(exploreTarget)) {
            if (exploreStart != null && !bot.blockPosition().equals(exploreStart)) {
                BotLog.action(bot, "farm_explore_arrived",
                        "at", bot.blockPosition().toShortString(),
                        "attempts", observedCropSearch.attempts());
            } else {
                observedCropSearch.retireObservedGoal(exploreTarget);
            }
            bot.getActionPack().stopAll();
            clearExploreLeg();
            phase = Phase.SURVEY;
            return;
        }
        if (elapsed - exploreStartedTick > EXPLORE_MOVE_LIMIT
                || elapsed - exploreStartedTick > 20 && bot.getActionPack().isPathExecutorIdle()) {
            observedCropSearch.retireObservedGoal(exploreTarget);
            BotLog.action(bot, "farm_explore_hop_ended",
                    "reason", elapsed - exploreStartedTick > EXPLORE_MOVE_LIMIT
                            ? "timeout" : "route_ended",
                    "to", exploreTarget.toShortString());
            bot.getActionPack().stopAll();
            clearExploreLeg();
            phase = Phase.SURVEY;
        }
    }

    private void clearExploreLeg() {
        exploreTarget = null;
        exploreStart = null;
    }

    /** What a survey would do with {@code ground} judged by block state alone (no visibility test yet). */
    private Kind classify(ServerLevel world, BlockPos ground, boolean hasSeeds) {
        BlockPos cropPos = ground.above();
        if (world.getBlockState(cropPos).is(crop) && FarmAction.isMature(world, cropPos)) {
            return Kind.HARVEST;
        }
        if (harvestOnly || world.getBlockState(cropPos).is(crop) || !world.getBlockState(cropPos).isAir()) {
            return Kind.NONE;
        }
        if (!hasSeeds) {
            return Kind.NONE;
        }
        boolean farmland = world.getBlockState(ground).is(Blocks.FARMLAND);
        if (!farmland && !FarmAction.isTillable(world.getBlockState(ground))) {
            return Kind.NONE;
        }
        if (world.getRawBrightness(cropPos, 0) < CROP_LIGHT_MIN) {
            return Kind.DARK; // a seed placed here would be refused (and could never grow)
        }
        return farmland ? Kind.PLANT : Kind.TILL_PLANT;
    }

    private void addTarget(Verdict verdict) {
        switch (verdict.kind()) {
            case HARVEST -> targets.add(new FarmTarget(verdict.ground(), TargetAction.HARVEST));
            case PLANT -> targets.add(new FarmTarget(verdict.ground(), TargetAction.PLANT));
            case TILL_PLANT -> targets.add(new FarmTarget(verdict.ground(), TargetAction.TILL_PLANT));
            case DARK -> darkCells++;
            case NONE -> {
            }
        }
    }

    private void markFailed(BlockPos ground) {
        failedCells.put(ground.immutable(), elapsed);
    }

    /** Whether {@code ground} failed recently; an entry older than the TTL is dropped and retried. */
    private boolean isFailed(BlockPos ground) {
        Integer at = failedCells.get(ground);
        if (at == null) {
            return false;
        }
        if (failureExpired(at, elapsed)) {
            failedCells.remove(ground);
            return false;
        }
        return true;
    }

    /** Whether a cell that failed at task tick {@code failedAt} may be tried again at {@code now}. */
    static boolean failureExpired(int failedAt, int now) {
        return now - failedAt >= FAILED_CELL_TTL_TICKS;
    }

    private void next(AIPlayerEntity bot) {
        if (!isResourceCollection() && keepTending && completedActions - lastDepositActionCount >= DEPOSIT_INTERVAL_ACTIONS
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
            markFailed(current.ground());
            phase = Phase.NEXT;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            ActionResult result = bot.getActionPack().startPathTo(stand);
            if (result.isFailed()) {
                note = result.reason();
                markFailed(current.ground());
                phase = Phase.NEXT;
            }
        }
    }

    private void till(AIPlayerEntity bot) {
        if (InventoryAction.countItem(bot, seed) <= 0) {
            note = "plant_skipped:missing " + seed + " x1";
            phase = Phase.NEXT;
            return;
        }
        ActionResult result = FarmAction.till(bot, current.ground());
        if (result.isInProgress()) {
            return;
        }
        if (result.isFailed()) {
            note = result.reason();
            markFailed(current.ground());
            phase = Phase.NEXT;
            return;
        }
        // A hoe turns dirt/grass into farmland in one click, but coarse and rooted dirt only become plain
        // dirt (vanilla), which needs a second click.
        if (!bot.level().getBlockState(current.ground()).is(Blocks.FARMLAND)) {
            if (++tillAttempts >= MAX_TILL_ATTEMPTS) {
                markFailed(current.ground());
                tillAttempts = 0;
                phase = Phase.NEXT;
            }
            return;
        }
        tillAttempts = 0;
        phase = Phase.PLANT;
    }

    private void plant(AIPlayerEntity bot) {
        if (InventoryAction.countItem(bot, seed) <= 0) {
            note = "plant_skipped:missing " + seed + " x1";
            phase = Phase.NEXT;
            return;
        }
        ActionResult result = FarmAction.plant(bot, current.ground(), seed, crop);
        if (result.isInProgress()) {
            return;
        }
        if (result.isFailed()) {
            note = result.reason();
            markFailed(current.ground());
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
        // Storage in sight (kind, lid and spawner rules) plus containers remembered from earlier
        // deposits; ranked by the ledger only, so the produce merges into a stack the bot itself saw.
        depositContainers.addAll(StorageTargets.aroundBase(bot, basePos, DEPOSIT_RADIUS, true,
                (pos, observed) -> StorageTargets.ledgerCount(bot, pos, observed, item) > 0));
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
        if (ContainerAction.inReachAndSight(bot, depositContainerPos)) {
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
        if (depositContainerPos == null
                || (ContainerAction.canSee(bot, depositContainerPos)
                        && !ContainerAction.isOpenableStorage(bot.level(), depositContainerPos))) {
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
                || !ContainerAction.canSee(bot, depositContainerPos)) {
            phase = Phase.DEPOSIT;
            return;
        }
        Container container = ContainerAction.open(bot, depositContainerPos, false).orElse(null);
        if (container == null) {
            selectDepositContainer(bot);
            return;
        }
        Item item = nextDepositItem(bot);
        if (item == null) {
            finishDeposit();
            return;
        }
        ContainerAction.TransferResult result = ContainerAction.deposit(bot, depositContainerPos, container, stack -> stack.is(item), maxDepositCount(bot, item));
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
        BlockPos cropPos = current.ground().above();
        if (harvestMiner.target() == null) {
            // Click-time proof (still ripe, inside reach, outline visible), then a real break: the tick-paced
            // BlockMiner drives the same mining controller every other digging task uses.
            ActionResult proof = FarmAction.harvestProof(bot, cropPos);
            if (proof.isFailed()) {
                note = proof.reason();
                markFailed(current.ground());
                phase = Phase.NEXT;
                return;
            }
            harvestMiner.begin(bot, cropPos);
        }
        BlockMiner.Status status = harvestMiner.tick(bot);
        if (status == BlockMiner.Status.MINING) {
            return;
        }
        if (status == BlockMiner.Status.FAILED) {
            note = "harvest_failed:" + harvestMiner.failureReason();
            markFailed(current.ground());
            phase = Phase.NEXT;
            return;
        }
        completedActions++;
        BotLog.action(bot, "harvest", "pos", cropPos);
        // The harvested produce/seed drops as an on-ground ItemEntity that does not go straight into the
        // inventory. The bot harvests from reach distance (<= 4.5 blocks), so the drops are usually out of
        // vanilla's automatic pickup range. There is no forced pickup for a bot, so
        // the PICKUP phase walks over the observed drops like a player would.
        pickupTicks = 0;
        phase = Phase.PICKUP;
    }

    private Set<Item> dropItems() {
        return Set.of(harvestItem(), seed);
    }

    /**
     * Walks over the observed harvest drops (produce and seeds) within a short radius until none is left or
     * the budget is spent, then replants the harvested cell if seeds are on hand (HarvestCore.walkOverDrops).
     */
    private void pickup(AIPlayerEntity bot) {
        pickupTicks++;
        if (pickupTicks <= PICKUP_DROP_SETTLE_TICKS) {
            return;
        }
        boolean dropsLeft = HarvestCore.walkOverDrops(bot, dropItems(), PICKUP_RADIUS);
        if (dropsLeft && pickupTicks <= PICKUP_BUDGET_TICKS) {
            return;
        }
        bot.getActionPack().stopMovement();
        if (dropsLeft) {
            note = "pickup_budget_spent";
        }
        if (!replantHarvestedCell(bot)) {
            return;
        }
        phase = Phase.NEXT;
    }

    /** @return false when a reactive shield temporarily owns the use key and this phase must wait. */
    private boolean replantHarvestedCell(AIPlayerEntity bot) {
        if (harvestOnly) {
            return true;
        }
        if (InventoryAction.countItem(bot, seed) <= 0) {
            note = "replant_skipped:missing " + seed + " x1";
            return true;
        }
        ActionResult plantResult = FarmAction.plant(bot, current.ground(), seed, crop);
        if (plantResult.isInProgress()) {
            return false;
        }
        if (plantResult.isFailed()) {
            note = "replant_failed:" + plantResult.reason();
        }
        return true;
    }

    /** Observed, still-growing crops of this task's crop within the area (a snapshot for the waiting loop). */
    private void collectImmatureCrops(AIPlayerEntity bot, ServerLevel world) {
        immatureCrops.clear();
        BlockPos surveyCenter = isResourceCollection() ? bot.blockPosition() : areaCenter;
        BlockPos.betweenClosedStream(surveyCenter.offset(-radius, -1, -radius), surveyCenter.offset(radius, 1, radius))
                .map(BlockPos::immutable)
                .filter(ground -> world.getBlockState(ground.above()).is(crop)
                        && !FarmAction.isMature(world, ground.above()))
                .filter(ground -> ObservableWorldQuery.canObserveFarmCell(bot, ground.above())
                        || ObservableWorldQuery.canObserveFarmCell(bot, ground))
                .forEach(ground -> immatureCrops.add(ground.above()));
    }

    /**
     * While waiting for maturity: with bone meal in the inventory, click the nearest observed growing crop
     * (vanilla item use: reach and outline proof, the item is consumed by the item itself), one click per
     * BONE_MEAL_INTERVAL ticks. Out of reach, it walks next to the crop first. Without bone meal but with
     * bones in the inventory, one bounded craft (1 bone -> 3 bone meal, the 2x2 recipe) makes some first;
     * without either the wait stays natural.
     */
    private void boneMealStep(AIPlayerEntity bot) {
        if (immatureCrops.isEmpty()) {
            return;
        }
        if (InventoryAction.countItem(bot, Items.BONE_MEAL) <= 0) {
            craftBoneMeal(bot);
            return;
        }
        if (elapsed - lastBoneMeal < BONE_MEAL_INTERVAL) {
            return;
        }
        ServerLevel world = bot.level();
        BlockPos target = null;
        double best = Double.MAX_VALUE;
        for (BlockPos cropPos : immatureCrops) {
            if (!FarmAction.isBonemealTarget(world, cropPos)) {
                continue;
            }
            double distance = bot.getEyePosition().distanceToSqr(cropPos.getCenter());
            if (distance < best) {
                best = distance;
                target = cropPos;
            }
        }
        if (target == null) {
            return;
        }
        if (!bot.isWithinBlockInteractionRange(target, 0.0D)) {
            BlockPos stand = adjacentStandPos(bot, target.below());
            if (stand == null) {
                immatureCrops.remove(target);
                return;
            }
            if (bot.getActionPack().isPathExecutorIdle()) {
                ActionResult result = bot.getActionPack().startPathTo(stand);
                if (result.isFailed()) {
                    note = result.reason();
                    immatureCrops.remove(target);
                }
            }
            return;
        }
        bot.getActionPack().stopMovement();
        ActionResult result = FarmAction.boneMeal(bot, target);
        if (result.isInProgress()) {
            return;
        }
        lastBoneMeal = elapsed;
        if (result.isFailed()) {
            note = result.reason();
            immatureCrops.remove(target); // not clickable from here: leave it to the natural wait
            return;
        }
        lastWaitSurvey = -WAIT_SURVEY_INTERVAL; // re-survey next tick: the crop may be ripe now
    }

    /**
     * Crafts bone meal from carried bones through the ordinary crafting path (a nested CraftTask, so the
     * atomic ingredient/capacity checks apply). Bounded: at most {@link #BONE_CRAFT_MAX_BONES} bones per run
     * of the wait, and one failure ends the attempts (a full inventory would otherwise retry forever).
     */
    private void craftBoneMeal(AIPlayerEntity bot) {
        if (boneMealCraftFailed) {
            return;
        }
        if (boneMealCraft == null) {
            int bones = Math.min(InventoryAction.countItem(bot, Items.BONE), BONE_CRAFT_MAX_BONES);
            if (bones <= 0) {
                return;
            }
            boneMealCraft = new CraftTask(Items.BONE_MEAL, bones * 3);
            boneMealCraft.start(bot);
            BotLog.action(bot, "farm_craft_bone_meal", "bones", bones);
        }
        boneMealCraft.tick(bot);
        if (boneMealCraft.state() == TaskState.FAILED || boneMealCraft.state() == TaskState.CANCELLED) {
            note = "bone_meal_craft_failed:" + boneMealCraft.failureReason();
            boneMealCraftFailed = true;
            boneMealCraft = null;
        } else if (boneMealCraft.state() == TaskState.COMPLETED) {
            boneMealCraft = null;
            lastBoneMeal = -BONE_MEAL_INTERVAL;
        }
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        if (boneMealCraft != null) {
            boneMealCraft.cancel(bot, "parent_aborted");
            boneMealCraft = null;
        }
        super.onAbort(bot);
    }

    private void done(AIPlayerEntity bot) {
        if (keepTending) {
            waitTicks++;
            if (waitTicks >= 100) {
                waitTicks = 0;
                failedCells.clear();
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
        // A resource collection may spend its full task window surveying and retrying short,
        // observation-fenced search legs.  Its own quota/deadline is the authoritative bound;
        // the generic stuck watcher must not cut an empty-but-active search short in SURVEY.
        return (keepTending && phase == Phase.DONE) || waitingForMaturity
                || isResourceCollection() && (phase == Phase.SURVEY || phase == Phase.EXPLORE);
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

    static BlockPos adjacentStandPos(AIPlayerEntity bot, BlockPos target) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = target.relative(direction).above();
            if (canObserveStand(bot, candidate)
                    && io.github.zoyluo.minecraftai.pathfinding.Standability.isStandable(bot.level(), candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean canObserveStand(AIPlayerEntity bot, BlockPos stand) {
        if (stand.equals(bot.blockPosition())) {
            return true;
        }
        return ObservableWorldQuery.canObserveCell(bot, stand)
                && ObservableWorldQuery.canObserveCell(bot, stand.above())
                && ObservableWorldQuery.canObserveCollider(bot, stand.below());
    }

    private enum Kind {
        NONE,
        DARK,
        HARVEST,
        PLANT,
        TILL_PLANT
    }

    private record Verdict(BlockPos ground, Kind kind) {
    }

    private enum TargetAction {
        TILL_PLANT,
        PLANT,
        HARVEST
    }

    private record FarmTarget(BlockPos ground, TargetAction action) {
    }
}

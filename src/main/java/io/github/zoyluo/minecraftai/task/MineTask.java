package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.action.MaterialPalette;
import io.github.zoyluo.minecraftai.action.PickupReach;
import io.github.zoyluo.minecraftai.action.TowerDescent;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mining.OreScan;
import io.github.zoyluo.minecraftai.mining.ToolTier;
import io.github.zoyluo.minecraftai.pathfinding.AStarPathfinder;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class MineTask extends AbstractTask {
    private static final int LOCAL_SEARCH_RADIUS = 8;
    private static final int LOCAL_SEARCH_DOWN = 4;
    private static final int LOCAL_SEARCH_UP = 6;
    /** High direct targets get a short, vertical-only recovery inside this observed local box. */
    private static final int PILLAR_SEARCH_UP = 32;
    private static final long PILLAR_SCAN_STEP_BUDGET_NANOS = 2_000_000L;
    private static final int PILLAR_SUPPORT_CUSHION = 1;
    private static final int SCAFFOLD_SUPPLY_RADIUS = 8;
    private static final int EXPLORE_MAX_HOPS = 16;
    private static final int EXPLORE_MOVE_LIMIT = 240;
    /** Short fallback when a full render-distance visible-target route cannot be admitted. */
    private static final int TARGET_SIGHTING_HOP_DISTANCE = 48;
    private static final int TARGET_SIGHTING_MOVE_LIMIT = 300;
    /** Do not cycle at a cave mouth forever; two observed cave surveys may open another safe stair. */
    private static final int MAX_CAVE_REDESCENTS = 2;

    private enum Phase {
        SEARCHING,
        EXPLORING,
        TARGET_SIGHTING,
        SCAFFOLD_SUPPLY,
        MOVING,
        MINING,
        PICKING_UP,
        /** Building the tower higher, in its own column, to an item that rests out of reach of its head. */
        DROP_CLIMB,
        /** The quota is met; the task ends once the bot is back down from a pillar it built. */
        FINISHING
    }

    private final Block targetBlock;
    private final int countNeeded;
    private final Set<Item> targetDrops;
    private final BlockMiner miner = new BlockMiner();
    private Phase phase = Phase.SEARCHING;
    private BlockPos targetPos;
    private int countSoFar;
    private int inventoryCountBeforeMining;
    private int pickupTicks;
    private boolean pickupSweepAttempted;
    /** The game time the block being picked up for was broken, which dates the item that came of it. */
    private long brokeAt;
    /** Why the tower is built higher for the item a break gave (see awaitDropOnTower). */
    private enum DropClimb {
        REACH,
        LOOK
    }

    /** Each kind of climb is tried once per broken block: one that ended short is not asked for again. */
    private final Set<DropClimb> climbsTried = EnumSet.noneOf(DropClimb.class);
    /** The inventory count of the target's drops when the tower began coming down; -1 before that. */
    private int descentCountAtStart = -1;
    private boolean directMiningTarget;
    /** Local cadence for the side-effect-free automatic-light scan at safe task boundaries. */
    private int lastTorchCheckElapsed = -10;
    /**
     * Count-mode mining searches beyond its first local view by walking only to a short destination
     * selected by the observation fence.  A compass heading is never itself a terrain or route
     * claim, so a failed admission cannot become a fabricated "no stone over there" result.
     */
    private final ObservedSearchHops observedSearchHops = new ObservedSearchHops(EXPLORE_MAX_HOPS);
    private BlockPos exploreTarget;
    private BlockPos exploreStart;
    private int exploreStartedTick;
    private int completedExploreHops;
    /** One fresh descent, plus a bounded re-descent after a cave has actually been surveyed. */
    private MiningExplorationTask miningExploration;
    private boolean miningExplorationAttempted;
    private boolean miningExplorationCaveSurveyRequired;
    private int miningExplorationCaveRedescents;
    private final Set<BlockPos> miningExplorationExcludedCavities = new LinkedHashSet<>();
    private int miningExplorationTimeoutCredit;
    // A generic mine request may be for stone, obsidian, or another ordinary target that is
    // plainly visible beyond the old 8-block survey. It has no proxy/leaf landmarks: only the
    // requested block itself may guide this render-distance observed pursuit.
    private VisibleTargetHorizonScan targetHorizonScan;
    private BlockPos targetSightingHint;
    private BlockPos targetSightingStart;
    private int targetSightingStartedTick;
    // The generic /mine path gets the same exact vertical recovery as gather. A support child is
    // deliberately local and non-recursive, so it can only acquire cheap natural filler.
    private HarvestCore.PillarApproachScan pillarApproachScan;
    /** Searches that came back empty from the stance the bot still holds (see PillarSearchMemo). */
    private final PillarSearchMemo exactPillarMemo = new PillarSearchMemo();
    private final PillarSearchMemo broadPillarMemo = new PillarSearchMemo();
    /** The target a walk onto its pillar column's floor is under way for (see stepOntoPillarBase). */
    private BlockPos pillarBaseWalk;
    /** The pillar this task built last: the bot takes it down again before it does anything else (see descendTower). */
    private TowerDescent tower;
    private HarvestCore.PillarApproach pendingPillarApproach;
    private GatherQuotaTask scaffoldSupplyTask;
    private int scaffoldSupportRequirement;
    private int scaffoldSupplyItemIndex;
    private Item scaffoldSupplyItem;
    private boolean pillarApproachActive;

    public MineTask(Block targetBlock, int countNeeded) {
        this.targetBlock = targetBlock;
        this.countNeeded = Math.max(1, countNeeded);
        this.targetDrops = HarvestCore.expectedDropsFor(targetBlock);
    }

    @Override
    public String name() {
        return "mine";
    }

    @Override
    public String describe() {
        return "Mining " + BuiltInRegistries.BLOCK.getKey(targetBlock) + " " + countSoFar + "/" + countNeeded + " phase=" + phase;
    }

    @Override
    public double progress() {
        return Math.min(1.0D, (double) countSoFar / countNeeded);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.SEARCHING;
        observedSearchHops.reset();
        exploreTarget = null;
        exploreStart = null;
        completedExploreHops = 0;
        miningExploration = null;
        miningExplorationAttempted = false;
        miningExplorationCaveSurveyRequired = false;
        miningExplorationCaveRedescents = 0;
        miningExplorationExcludedCavities.clear();
        miningExplorationTimeoutCredit = 0;
        clearTargetSighting();
        clearPillarRecovery();
        exactPillarMemo.clear();
        broadPillarMemo.clear();
        tower = null;
        descentCountAtStart = -1;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        // The child owns its own bounded descent clock.  Do not let MineTask's much shorter
        // generic search timeout kill a safe, already-started descent in mid-stair.
        if (tickMiningExploration(bot)) {
            return;
        }
        // The child owns every action while it gathers cheap support. Do not let automatic
        // lighting or ordinary mine planning contend for the same hand/path on this tick.
        if (scaffoldSupplyTask != null || phase == Phase.SCAFFOLD_SUPPLY) {
            scaffoldSupply(bot);
            return;
        }
        if (descendTower(bot)) {
            return;
        }
        // Eligible mining requests may spend their first window revealing terrain through
        // observed hops, then have a separately bounded staircase.  Keep the historical short
        // timeout for every unrelated direct mine request.
        // Explicit quantities keep searching through fresh safe observed episodes too. Give
        // gather-capable blocks the same ten-minute exploration horizon as no-count collection
        // instead of treating an initially empty local view as the end of the request.
        int timeout = MiningExplorationTask.supports(targetBlock) ? 20 * 60 * 10 : 2400;
        if (miningExploration == null && elapsed - miningExplorationTimeoutCredit > timeout) {
            BotLog.action(bot, "mine_timeout_detail", "phase", phase, "count", countSoFar + "/" + countNeeded,
                    "target", BuiltInRegistries.BLOCK.getKey(targetBlock));
            fail("mine_timeout");
            return;
        }
        if (maybePlaceAutomaticTorch(bot, false)) {
            return;
        }
        switch (phase) {
            case SEARCHING -> search(bot);
            case EXPLORING -> explore(bot);
            case TARGET_SIGHTING -> targetSightingMove(bot);
            case SCAFFOLD_SUPPLY -> scaffoldSupply(bot);
            case MOVING -> move(bot);
            case MINING -> mine(bot);
            case PICKING_UP -> pickup(bot);
            case DROP_CLIMB -> dropClimb(bot);
            case FINISHING -> complete();
        }
    }

    /**
     * Takes the pillar this task built down again before the bot does anything else: a route steps
     * down at most the safe fall and never breaks the bot's own footing, so a tall tower would strand
     * it. The bot breaks the block under its feet one at a time, as a player does (see
     * {@link TowerDescent}). A pillar still being climbed or mined from is in use and left alone, and
     * the bot stays up on it while the item the break gave is still to be collected from there (see
     * {@link #awaitDropOnTower}). True while it owns the tick.
     */
    private boolean descendTower(AIPlayerEntity bot) {
        if (tower == null || phase == Phase.MOVING || phase == Phase.MINING || phase == Phase.DROP_CLIMB) {
            return false;
        }
        if (phase == Phase.PICKING_UP && descentCountAtStart < 0) {
            // What the break gave is counted by pickup() before the tower's own blocks come back as items.
            if (collectedDrops(bot) > 0) {
                return false;
            }
            if (tower.standsOnTower(bot) && awaitDropOnTower(bot)) {
                return true;
            }
        }
        if (descentCountAtStart < 0) {
            descentCountAtStart = HarvestCore.countInventoryItems(bot, targetDrops);
        }
        TowerDescent.Status status = tower.tick(bot);
        if (status == TowerDescent.Status.DESCENDING) {
            return true;
        }
        if (status == TowerDescent.Status.FAILED) {
            BotLog.action(bot, "mine_tower_descent_failed",
                    "reason", tower.failureReason(), "at", bot.blockPosition().toShortString());
        } else if (tower.broken() > 0) {
            BotLog.action(bot, "mine_tower_descended",
                    "blocks", tower.broken(), "at", bot.blockPosition().toShortString());
        }
        // The blocks of the tower come back as items; when they are of the kind being mined they are no
        // progress (a stone quota built up with cobblestone), so the count of what this break gave starts over.
        int gained = HarvestCore.countInventoryItems(bot, targetDrops) - descentCountAtStart;
        inventoryCountBeforeMining += Math.min(tower.returnedOf(targetDrops), Math.max(0, gained));
        descentCountAtStart = -1;
        TowerCustody.INSTANCE.release(bot, tower);
        tower = null;
        return false;
    }

    /** The items of the target's kind the inventory gained since the block now being picked up for began to break. */
    private int collectedDrops(AIPlayerEntity bot) {
        return HarvestCore.countInventoryItems(bot, targetDrops) - inventoryCountBeforeMining;
    }

    /**
     * Keeps the bot on its tower for the item the break gave. While the item is in the air, or vanilla's pickup
     * box already meets it (a freshly dropped item cannot be collected for ten ticks, and the next block of the
     * descent would drop the bot out of range), it waits. An item it sees come to rest out of reach of the
     * pillar's head (on a ledge beside the target) is climbed to by building the pillar higher, in its own
     * column, until that box meets it. An item it does not see after the time it takes to fall and be collected
     * lies out of sight, usually on the very ledge that hides it from below, so the pillar is built up to the
     * level of the block that was broken and the bot looks from there; what it then sees is dealt with as above.
     * No step is taken off the tower, and the descent takes the whole tower down afterwards. An item that fell
     * below is picked up from the floor. The wait counts against the pickup window. True while the bot stays.
     */
    private boolean awaitDropOnTower(AIPlayerEntity bot) {
        if (pickupTicks <= 0) {
            return false;
        }
        long sinceBreak = bot.level().getGameTime() - brokeAt;
        Optional<ItemEntity> seen = HarvestCore.nearestDropAnyOf(bot, targetDrops, 8.0D,
                drop -> drop.getAge() <= sinceBreak + 3);
        int supportsToSpare = MaterialPalette.countPillarSupportBlocks(bot) - PILLAR_SUPPORT_CUSHION;
        pickupTicks--;
        if (seen.isPresent()) {
            ItemEntity drop = seen.get();
            if (!HarvestCore.isDropPhysicallySupported(bot, drop) || HarvestCore.canCollectNow(bot, drop)) {
                return true;
            }
            if (climbsTried.contains(DropClimb.REACH)) {
                return false;
            }
            BlockPos goal = HarvestCore.dropClimbGoal(bot, drop, supportsToSpare);
            return startDropClimb(bot, DropClimb.REACH, goal, drop.blockPosition());
        }
        // A block that is still standing (the break failed) gave nothing to look for.
        if (climbsTried.contains(DropClimb.LOOK) || targetPos == null || isCurrentVisibleTarget(bot, targetPos)) {
            return false;
        }
        double fall = Math.max(0, targetPos.getY() - bot.blockPosition().getY());
        if (sinceBreak <= PickupReach.settledTicks(fall)) {
            return true;
        }
        return startDropClimb(bot, DropClimb.LOOK, HarvestCore.lookClimbGoal(bot, targetPos, supportsToSpare), targetPos);
    }

    /** True while the climb is under way (or only waits for the pack to be free); false when there is nothing to climb. */
    private boolean startDropClimb(AIPlayerEntity bot, DropClimb why, BlockPos goal, BlockPos around) {
        if (goal == null) {
            climbsTried.add(why);
            return false;
        }
        bot.getActionPack().stopAll();
        ActionResult route = bot.getActionPack().startPillarPathTo(goal);
        if (ActionPack.GUARDED_STEP_FENCE.equals(route.reason()) && route.isFailed()) {
            return true; // an unstarted retry, not a refusal of this climb
        }
        climbsTried.add(why);
        if (route.isFailed()) {
            BotLog.action(bot, "mine_drop_climb_refused", "why", why.name().toLowerCase(java.util.Locale.ROOT), "around", around.toShortString(),
                    "goal", goal.toShortString(), "reason", route.reason());
            return false;
        }
        phase = Phase.DROP_CLIMB;
        BotLog.action(bot, "mine_drop_climb", "why", why.name().toLowerCase(java.util.Locale.ROOT), "around", around.toShortString(),
                "goal", goal.toShortString(), "levels", goal.getY() - bot.blockPosition().getY());
        return true;
    }

    /** The tower is being built higher; once the route is over the item is in reach, or in sight, or the climb ended short. */
    private void dropClimb(AIPlayerEntity bot) {
        if (bot.getActionPack().isPathExecutorIdle() && bot.onGround()) {
            phase = Phase.PICKING_UP;
        }
    }

    /**
     * Generic block mining (cobblestone, obsidian, and other non-ore requests) reaches this task
     * directly, so it needs the same underground-only safety reflex as the ore-specific tasks.
     * Keep it at an idle search boundary: it never interrupts a path, an in-flight break, or drop
     * collection, and the next mining start will factually re-equip its best tool.
     */
    /** @return true while a deferred vanilla placement still owns this tick. */
    private boolean maybePlaceAutomaticTorch(AIPlayerEntity bot, boolean beforeMining) {
        if (!AutomaticLighting.miningTorchAutomationEnabled()) {
            return false;
        }
        // A held use is an owner in its own right. Do not merely skip the lighting attempt and
        // then let this same tick start a mine with a shield, food, or drawn bow still active.
        // At the direct pre-break boundary, any ActionPack owner must settle for the same reason.
        if (bot.isUsingItem()
                || beforeMining && bot.getActionPack().hasActiveActions()) {
            return true;
        }
        if (!beforeMining && phase != Phase.SEARCHING
                || !beforeMining && elapsed - lastTorchCheckElapsed < 10
                || beforeMining && lastTorchCheckElapsed == elapsed
                || miner.target() != null
                || !bot.getActionPack().isMiningIdle()
                || !bot.getActionPack().isPathExecutorIdle()
                || !bot.getActionPack().isWalkToIdle()
                || bot.getActionPack().hasActiveActions()) {
            return false;
        }
        AutomaticLighting.Placement placement = AutomaticLighting.tryPlaceDarkestReachable(bot);
        // A temporary owner is not a completed probe. Retrying as soon as it releases avoids a
        // ten-tick dark gap after a shield/use handoff, while NONE/PLACED/FAILED stay paced.
        if (placement != AutomaticLighting.Placement.IN_PROGRESS) {
            lastTorchCheckElapsed = elapsed;
        }
        if (placement == AutomaticLighting.Placement.PLACED) {
            BotLog.action(bot, "mine_auto_torch", "pos", bot.blockPosition().toShortString());
        }
        // BuildAction may yield to a temporary use owner (for example, a raised shield).  Do not
        // begin a path or a new BlockMiner transaction until that owner reports idle again.
        return placement == AutomaticLighting.Placement.IN_PROGRESS;
    }

    private void search(AIPlayerEntity bot) {
        HarvestCore.TargetChoice choice = nearestTarget(bot);
        if (choice == null) {
            // Ore requests take the dedicated OreDig path in normal dispatch. Keep this generic
            // surface look-around for non-ore MineTask targets so it cannot change ore-vein
            // safety/selection semantics if an older caller reaches this task directly.
            if (!OreScan.isOreBlock(targetBlock)) {
                // First take the quick render-distance look-around a player would use. If it
                // finds no immediate target, a high local target may still use one fully
                // re-proved vertical pillar before walking away or descending; it uses only
                // the common nonvaluable support palette.
                if (seekVisibleTarget(bot) || tryPillarApproach(bot)) {
                    return;
                }
            }
            // A player who has already looked around the visible surface for stone or an ore
            // does not spend a long time walking across more of that same surface before trying
            // the ground beneath their feet.  MiningExplorationTask still proves the safe
            // staircase and refuses to descend once this resource's target layer is reached.
            // Non-geological requests simply return false and retain the observed-hop recovery.
            if (startMiningExploration(bot)) {
                return;
            }
            if (startObservedExploration(bot)) {
                return;
            }
            if (startMiningExploration(bot)) {
                return;
            }
            // An empty bounded hop episode is a search boundary, not proof that the resource
            // does not exist beyond the first revealed patch.  Restart from this factual
            // position and keep exploring until the task's normal time budget expires (or an
            // explicit quota is fulfilled).  This gives both "mine 3 stone" and an omitted
            // quantity a real chance to discover terrain beyond the initial view.
            restartObservedExploration(bot);
            return;
        }
        clearTargetSighting();
        clearPillarRecovery();
        targetPos = choice.pos();
        directMiningTarget = choice.direct();
        if (directMiningTarget) {
            // The search-boundary check above is cadence-limited.  A newly selected direct
            // target can be reached in that same tick, so make one final quiet lighting check
            // before beginning the physical break.
            if (maybePlaceAutomaticTorch(bot, true)) {
                return;
            }
            startMiningTarget(bot);
            return;
        }
        ActionResult route = bot.getActionPack().startPathTo(choice.stand());
        if (route.isFailed()) {
            routeRefused(bot, choice, route);
            return;
        }
        phase = Phase.MOVING;
    }

    /** The nearest requested block with a stance, leaving out one the bot has already given up on. */
    private HarvestCore.TargetChoice nearestTarget(AIPlayerEntity bot) {
        return HarvestCore.nearestReachableBlock(bot, Set.of(targetBlock),
                LOCAL_SEARCH_RADIUS, LOCAL_SEARCH_DOWN, LOCAL_SEARCH_UP, pos -> !isExcluded(bot, pos));
    }

    /**
     * Nothing walks to the stance of the chosen block. An observed pillar may still reach it; otherwise
     * it is set aside, or the next search would choose it again and ask for the same refused route on
     * every tick.
     */
    private void routeRefused(AIPlayerEntity bot, HarvestCore.TargetChoice choice, ActionResult route) {
        if (ActionPack.GUARDED_STEP_FENCE.equals(route.reason())) {
            return; // an unstarted retry, not a refusal of this block
        }
        BotLog.action(bot, "mine_route_refused",
                "target", choice.pos().toShortString(), "stand", choice.stand().toShortString(),
                "reason", route.reason());
        if (!pillarToBlock(bot, choice.pos(), true)) {
            EpisodeMemory.INSTANCE.exclude(bot.getUUID(), choice.pos(),
                    bot.level().getServer().getTickCount(), EpisodeMemory.TTL_UNREACHABLE);
        }
    }

    /**
     * Finds an ordinary requested block through current render-distance sight before MineTask
     * gives up to descent or short observed exploration. The scanner contains no world-volume
     * query; a target must be a first-hit eye ray or freshly re-proved shared visual evidence.
     */
    private boolean seekVisibleTarget(AIPlayerEntity bot) {
        if (targetHorizonScan == null) {
            targetHorizonScan = new VisibleTargetHorizonScan(Set.of(targetBlock));
        }
        if (targetSightingHint != null) {
            BlockPos retained = targetSightingHint;
            if (horizontalDistanceSquared(bot.blockPosition(), retained) <= 64.0D) {
                targetSightingHint = null;
            } else if (startTargetSightingPursuit(bot)) {
                return true;
            } else {
                // Refused from here: the sweep must not hand the same block straight back and have it refused again.
                targetHorizonScan.decline(bot, retained);
            }
        }
        VisibleTargetHorizonScan.Sighting sighting = targetHorizonScan.step(bot);
        if (sighting != null) {
            BotLog.action(bot, "mine_target_sighted",
                    "target", BuiltInRegistries.BLOCK.getKey(targetBlock),
                    "pos", sighting.pos().toShortString(),
                    "rays", sighting.raysCast());
            if (actOnTargetSighting(bot, sighting.pos())) {
                return true;
            }
            // Nothing came of it. Tell the raster, or its very next ray would answer with the same
            // block again (an overhead one answers the vertical ray of every step) and the rest of
            // the look-around would never run.
            targetHorizonScan.decline(bot, sighting.pos());
        }
        if (!targetHorizonScan.complete()) {
            // Do not park for an entire fine visual raster. A small initial look-around is
            // enough to preempt obvious targets; subsequent search/exploration remains
            // safe while this persistent scanner resumes at later search boundaries.
            return targetHorizonScan.shouldHoldFallback();
        }
        targetHorizonScan = null;
        return false;
    }

    /** True when a sighted target started an approach, pillar or landmark leg. */
    private boolean actOnTargetSighting(AIPlayerEntity bot, BlockPos seen) {
        if (isExcluded(bot, seen)) {
            return false; // already failed or unreachable: not worth another try from here
        }
        if (approachVisibleTarget(bot, seen)) {
            return true;
        }
        if (horizontalDistanceSquared(bot.blockPosition(), seen) <= 64.0D) {
            // Inside the local survey envelope there is no direction left to pursue. A target an
            // observed pillar reaches (above the bot, in its own column or beside it) is climbed to
            // now; one that is out of reach below simply has no use for this look-around.
            return pillarToBlock(bot, seen, true);
        }
        targetSightingHint = seen.immutable();
        return startTargetSightingPursuit(bot);
    }

    private static boolean isExcluded(AIPlayerEntity bot, BlockPos pos) {
        return EpisodeMemory.INSTANCE.isExcluded(bot.getUUID(), pos, bot.level().getServer().getTickCount());
    }

    /** Uses the normal direct-mining path if the currently visible target already has a stance. */
    private boolean approachVisibleTarget(AIPlayerEntity bot, BlockPos target) {
        if (!isCurrentVisibleTarget(bot, target)) {
            return false;
        }
        // A long ordinary path would outlive the target's visual proof and could acquire
        // break/place authority beyond it. Remote sightings therefore stay on the dedicated
        // walk-only, re-proved landmark path until the target is inside this task's local scan.
        if (!insideLocalTargetSurvey(bot, target)) {
            return false;
        }
        HarvestCore.TargetChoice choice = HarvestCore.targetChoice(bot, target);
        if (choice == null) {
            return false;
        }
        targetPos = choice.pos().immutable();
        directMiningTarget = choice.direct();
        if (directMiningTarget) {
            clearTargetSighting();
            if (maybePlaceAutomaticTorch(bot, true)) {
                return true;
            }
            startMiningTarget(bot);
            return true;
        }
        ActionResult route = bot.getActionPack().startPathTo(choice.stand());
        if (route.isFailed()) {
            targetPos = null;
            BotLog.action(bot, "mine_target_sighting_route_refused",
                    "target", target.toShortString(), "reason", route.reason());
            return false;
        }
        clearTargetSighting();
        phase = Phase.MOVING;
        BotLog.action(bot, "mine_target_sighting_direct_route",
                "target", target.toShortString(), "to", choice.stand().toShortString());
        return true;
    }

    /** Starts a render-range, observed-only Baritone leg toward the exact target block. */
    private boolean startTargetSightingPursuit(AIPlayerEntity bot) {
        if (targetSightingHint == null) {
            return false;
        }
        if (!isCurrentVisibleTarget(bot, targetSightingHint)) {
            BotLog.action(bot, "mine_target_sighting_lost", "hint", targetSightingHint.toShortString());
            targetSightingHint = null;
            return false;
        }
        int requestedHop = targetSightingPursuitDistance(bot);
        ActionResult route = bot.getActionPack().startVisibleLandmarkPursuitTo(targetSightingHint, requestedHop);
        if (route.isFailed() && requestedHop > TARGET_SIGHTING_HOP_DISTANCE) {
            String longRouteReason = route.reason();
            requestedHop = TARGET_SIGHTING_HOP_DISTANCE;
            route = bot.getActionPack().startVisibleLandmarkPursuitTo(targetSightingHint, requestedHop);
            if (!route.isFailed()) {
                BotLog.action(bot, "mine_target_sighting_short_leg_fallback",
                        "hint", targetSightingHint.toShortString(), "reason", longRouteReason,
                        "max_hop", requestedHop);
            }
        }
        if (route.isFailed()) {
            BotLog.action(bot, "mine_target_sighting_pursuit_refused",
                    "hint", targetSightingHint.toShortString(), "reason", route.reason());
            targetSightingHint = null;
            return false;
        }
        if (bot.getActionPack().activePathGoal() == null) {
            bot.getActionPack().stopAll();
            BotLog.action(bot, "mine_target_sighting_pursuit_refused",
                    "hint", targetSightingHint.toShortString(), "reason", "missing_observed_goal");
            targetSightingHint = null;
            return false;
        }
        targetSightingStart = bot.blockPosition().immutable();
        targetSightingStartedTick = elapsed;
        phase = Phase.TARGET_SIGHTING;
        BotLog.action(bot, "mine_target_sighting_pursuit",
                "hint", targetSightingHint.toShortString(),
                "to", bot.getActionPack().activePathGoal().toShortString(),
                "max_hop", requestedHop);
        return true;
    }

    /** Drops stale visual guidance before it can become a hidden or sideways route. */
    private void targetSightingMove(AIPlayerEntity bot) {
        if (targetSightingHint == null) {
            phase = Phase.SEARCHING;
            return;
        }
        if (!isCurrentVisibleTarget(bot, targetSightingHint)) {
            bot.getActionPack().stopAll();
            BotLog.action(bot, "mine_target_sighting_lost", "hint", targetSightingHint.toShortString());
            clearTargetSighting();
            phase = Phase.SEARCHING;
            return;
        }
        if (horizontalDistanceSquared(bot.blockPosition(), targetSightingHint) <= 64.0D) {
            bot.getActionPack().stopAll();
            clearTargetSighting();
            phase = Phase.SEARCHING;
            return;
        }
        boolean timedOut = elapsed - targetSightingStartedTick > TARGET_SIGHTING_MOVE_LIMIT;
        boolean routeEnded = elapsed - targetSightingStartedTick > 20 && bot.getActionPack().isPathExecutorIdle();
        if (!timedOut && !routeEnded) {
            return;
        }
        boolean moved = targetSightingStart != null
                && horizontalDistanceSquared(bot.blockPosition(), targetSightingHint) + 4.0D
                < horizontalDistanceSquared(targetSightingStart, targetSightingHint);
        if (timedOut) {
            bot.getActionPack().stopAll();
        }
        BotLog.action(bot, "mine_target_sighting_leg_end",
                "reason", timedOut ? "timeout" : "route_ended",
                "moved", moved,
                "hint", targetSightingHint.toShortString());
        if (!moved) {
            targetSightingHint = null;
        }
        targetSightingStart = null;
        phase = Phase.SEARCHING;
    }

    /** State reads are permitted only after a fresh render-distance line-of-sight proof. */
    private boolean isCurrentVisibleTarget(AIPlayerEntity bot, BlockPos target) {
        return target != null
                && io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlock(bot, target)
                && bot.level().getBlockState(target).is(targetBlock);
    }

    /** The block is gone: a cell the bot can see now holds nothing (its own break, or someone else's). */
    private static boolean isVisiblyAir(AIPlayerEntity bot, BlockPos pos) {
        return pos != null
                && io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveCell(bot, pos)
                && bot.level().getBlockState(pos).isAir();
    }

    private int targetSightingPursuitDistance(AIPlayerEntity bot) {
        if (targetSightingHint == null) {
            return TARGET_SIGHTING_HOP_DISTANCE;
        }
        int renderRange = Math.max(1,
                io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.visibleRangeBlocks(bot) - 2);
        int landmarkDistance = (int) Math.ceil(Math.sqrt(
                horizontalDistanceSquared(bot.blockPosition(), targetSightingHint)));
        return Math.min(renderRange, Math.max(TARGET_SIGHTING_HOP_DISTANCE, landmarkDistance));
    }

    private void clearTargetSighting() {
        targetHorizonScan = null;
        targetSightingHint = null;
        targetSightingStart = null;
        targetSightingStartedTick = 0;
    }

    /**
     * Attempts the same explicit, no-dig vertical recovery used by GatherQuotaTask. The scan is
     * time-budgeted, all candidate/column state reads remain behind live LOS proofs, and a route
     * may place only in its admitted pillar column.
     */
    private boolean tryPillarApproach(AIPlayerEntity bot) {
        int now = bot.level().getServer().getTickCount();
        long worldVersion = AStarPathfinder.cacheVersion();
        if (pillarApproachScan == null
                && broadPillarMemo.knownEmpty(bot.blockPosition(), PILLAR_SEARCH_UP, worldVersion, now)) {
            return false; // this very volume was just scanned from here and held no approach
        }
        if (pillarApproachScan == null
                || bot.blockPosition().distSqr(pillarApproachScan.origin()) > 64.0D) {
            pillarApproachScan = HarvestCore.beginNearestPillarApproachScan(bot, Set.of(targetBlock),
                    LOCAL_SEARCH_RADIUS, LOCAL_SEARCH_DOWN, PILLAR_SEARCH_UP,
                    broadPillarMemo.scanFilter(bot.getUUID(), now));
        }
        if (!pillarApproachScan.step(PILLAR_SCAN_STEP_BUDGET_NANOS)) {
            return true;
        }
        HarvestCore.PillarApproach approach = pillarApproachScan.result();
        BlockPos slope = pillarApproachScan.baseWalkTarget();
        BlockPos scanned = pillarApproachScan.origin();
        pillarApproachScan = null;
        if (approach != null) {
            // The scan found a column at the bot's own level; ground above or below may need fewer supports.
            return pillarToBlock(bot, approach.target(), true);
        }
        // Nothing is climbable from the bot's own level; a target rooted on a slope may be from the floor a step away.
        if (slope != null && pillarToBlock(bot, slope, true)) {
            return true;
        }
        broadPillarMemo.rememberEmpty(scanned, PILLAR_SEARCH_UP, worldVersion, now);
        return false;
    }

    /**
     * Admits an observed pillar to one block the bot has just seen but no route reaches (a block it
     * sighted overhead, or one the route planner refused). The exact block is re-proved here, so it
     * does not wait for the broad candidate scan to reach it. False when it is not worth a pillar.
     */
    private boolean pillarToBlock(AIPlayerEntity bot, BlockPos block, boolean mayStepOntoFloor) {
        long worldVersion = AStarPathfinder.cacheVersion();
        int now = bot.level().getServer().getTickCount();
        if (exactPillarMemo.knownEmpty(bot.blockPosition(), block.asLong(), worldVersion, now)) {
            return false;
        }
        HarvestCore.PillarApproach flat = HarvestCore.pillarApproachFor(bot, block, Set.of(targetBlock));
        // A tower is long to build and to take down again, and a target rooted on a slope may have no column at
        // the bot's own level at all: ground a walk away may need fewer supports (or any).
        List<HarvestCore.PillarApproach> elsewhere = mayStepOntoFloor
                ? HarvestCore.pillarApproachesOnOtherFloors(bot, block, Set.of(targetBlock)) : List.of();
        if (HarvestCore.otherFloorIsCheaper(flat, elsewhere)
                && stepOntoPillarBase(bot, block, elsewhere)) {
            return true;
        }
        if (flat == null) {
            exactPillarMemo.rememberEmpty(bot.blockPosition(), block.asLong(), worldVersion, now);
            return false;
        }
        if (!admitPillarApproach(bot, flat)) {
            return false;
        }
        pillarApproachScan = null;
        return true;
    }

    /**
     * Walks onto the floor of one of the pillars {@code elsewhere}, cheapest first, by an ordinary observed
     * route; {@link #move} plans the pillar from there. The best floor may be one that no observed route
     * reaches (ground behind a ridge the bot has not seen over), so the next best is asked for when the
     * route to it is refused.
     */
    private boolean stepOntoPillarBase(AIPlayerEntity bot, BlockPos block, List<HarvestCore.PillarApproach> elsewhere) {
        for (HarvestCore.PillarApproach approach : elsewhere) {
            BlockPos base = HarvestCore.pillarFloor(approach);
            ActionResult route = bot.getActionPack().startPathTo(base);
            if (route.isFailed()) {
                BotLog.action(bot, "mine_pillar_base_refused",
                        "target", block.toShortString(), "base", base.toShortString(), "reason", route.reason());
                continue;
            }
            clearTargetSighting();
            pillarApproachScan = null;
            targetPos = block.immutable();
            directMiningTarget = false;
            pillarApproachActive = false;
            pillarBaseWalk = targetPos;
            phase = Phase.MOVING;
            BotLog.action(bot, "mine_pillar_base_walk",
                    "target", targetPos.toShortString(), "base", base.toShortString());
            return true;
        }
        return false;
    }

    /** Obtains cheap nearby supports when the bot is short of them, then starts the pillar. */
    private boolean admitPillarApproach(AIPlayerEntity bot, HarvestCore.PillarApproach approach) {
        int required = approach.supports() + PILLAR_SUPPORT_CUSHION;
        int available = MaterialPalette.countPillarSupportBlocks(bot);
        if (available >= required) {
            return startPillarApproach(bot, approach, available);
        }
        pendingPillarApproach = approach;
        scaffoldSupportRequirement = required;
        scaffoldSupplyItemIndex = 0;
        scaffoldSupplyItem = null;
        phase = Phase.SCAFFOLD_SUPPLY;
        if (startNextScaffoldSupply(bot)) {
            return true;
        }
        EpisodeMemory.INSTANCE.exclude(bot.getUUID(), approach.target(),
                bot.level().getServer().getTickCount(), EpisodeMemory.TTL_UNREACHABLE);
        BotLog.action(bot, "mine_pillar_support_unavailable",
                "target", approach.target().toShortString(), "needed", required, "available", available);
        clearPillarRecovery();
        phase = Phase.SEARCHING;
        return false;
    }

    private boolean startPillarApproach(AIPlayerEntity bot, HarvestCore.PillarApproach approach, int available) {
        bot.getActionPack().stopAll();
        ActionResult route = bot.getActionPack().startPillarPathTo(approach.goal());
        if (route.isFailed()) {
            EpisodeMemory.INSTANCE.exclude(bot.getUUID(), approach.target(),
                    bot.level().getServer().getTickCount(), EpisodeMemory.TTL_UNREACHABLE);
            BotLog.action(bot, "mine_pillar_refused",
                    "target", approach.target().toShortString(), "goal", approach.goal().toShortString(),
                    "reason", route.reason());
            return false;
        }
        clearTargetSighting();
        pillarApproachScan = null;
        pendingPillarApproach = null;
        scaffoldSupportRequirement = 0;
        scaffoldSupplyItemIndex = 0;
        scaffoldSupplyItem = null;
        targetPos = approach.target().immutable();
        directMiningTarget = false;
        pillarApproachActive = true;
        tower = TowerDescent.over(approach.goal(), approach.supports());
        TowerCustody.INSTANCE.hold(bot, this, tower);
        phase = Phase.MOVING;
        BotLog.action(bot, "mine_pillar_start",
                "target", targetPos.toShortString(), "goal", approach.goal().toShortString(),
                "supports", approach.supports(), "available", available);
        return true;
    }

    /** Gives a finished local support child one fresh proof of its target and air column. */
    private HarvestCore.PillarApproach refreshPillarApproach(AIPlayerEntity bot,
                                                               HarvestCore.PillarApproach previous) {
        if (previous == null || EpisodeMemory.INSTANCE.isExcluded(bot.getUUID(), previous.target(),
                bot.level().getServer().getTickCount())) {
            return null;
        }
        return HarvestCore.pillarApproachFor(bot, previous.target(), Set.of(targetBlock));
    }

    /** Lets the bounded nearby cheap-support child own the bot until it settles. */
    private void scaffoldSupply(AIPlayerEntity bot) {
        if (scaffoldSupplyTask == null) {
            clearPillarRecovery();
            phase = Phase.SEARCHING;
            return;
        }
        scaffoldSupplyTask.tick(bot);
        if (scaffoldSupplyTask.state() == TaskState.RUNNING || scaffoldSupplyTask.state() == TaskState.PAUSED) {
            return;
        }
        HarvestCore.PillarApproach approach = pendingPillarApproach;
        int available = MaterialPalette.countPillarSupportBlocks(bot);
        String reason = scaffoldSupplyTask.failureReason();
        Item attempted = scaffoldSupplyItem;
        scaffoldSupplyTask = null;
        if (approach != null && available >= scaffoldSupportRequirement) {
            HarvestCore.PillarApproach refreshed = refreshPillarApproach(bot, approach);
            if (refreshed != null) {
                pendingPillarApproach = refreshed;
                scaffoldSupportRequirement = refreshed.supports() + PILLAR_SUPPORT_CUSHION;
                if (available >= scaffoldSupportRequirement) {
                    if (startPillarApproach(bot, refreshed, available)) {
                        return;
                    }
                    EpisodeMemory.INSTANCE.exclude(bot.getUUID(), refreshed.target(),
                            bot.level().getServer().getTickCount(), EpisodeMemory.TTL_UNREACHABLE);
                } else if (startNextScaffoldSupply(bot)) {
                    return;
                }
            } else if (approach != null) {
                EpisodeMemory.INSTANCE.exclude(bot.getUUID(), approach.target(),
                        bot.level().getServer().getTickCount(), EpisodeMemory.TTL_UNREACHABLE);
                BotLog.action(bot, "mine_pillar_resupply_target_lost",
                        "target", approach.target().toShortString());
            }
        } else if (startNextScaffoldSupply(bot)) {
            return;
        }
        BotLog.action(bot, "mine_pillar_resupply_failed",
                "target", approach == null ? "none" : approach.target().toShortString(),
                "item", attempted == null ? "none" : BuiltInRegistries.ITEM.getKey(attempted),
                "reason", reason == null || reason.isBlank() ? "insufficient_support" : reason,
                "available", available);
        if (approach != null) {
            EpisodeMemory.INSTANCE.exclude(bot.getUUID(), approach.target(),
                    bot.level().getServer().getTickCount(), EpisodeMemory.TTL_UNREACHABLE);
        }
        clearPillarRecovery();
        phase = Phase.SEARCHING;
    }

    /** Starts the next local, common, non-wood/non-ore support gather child. */
    private boolean startNextScaffoldSupply(AIPlayerEntity bot) {
        int missing = scaffoldSupportRequirement - MaterialPalette.countPillarSupportBlocks(bot);
        if (missing <= 0) {
            return false;
        }
        List<Item> candidates = MaterialPalette.nearbyPillarSupportGatherItems();
        if (scaffoldSupplyItemIndex >= candidates.size()) {
            return false;
        }
        scaffoldSupplyItem = candidates.get(scaffoldSupplyItemIndex++);
        scaffoldSupplyTask = GatherQuotaTask.collectNearbyPillarSupport(scaffoldSupplyItem, missing);
        scaffoldSupplyTask.start(bot);
        BotLog.action(bot, "mine_pillar_resupply",
                "target", pendingPillarApproach == null ? "none" : pendingPillarApproach.target().toShortString(),
                "item", BuiltInRegistries.ITEM.getKey(scaffoldSupplyItem),
                "needed", missing,
                "available", MaterialPalette.countPillarSupportBlocks(bot),
                "radius", SCAFFOLD_SUPPLY_RADIUS);
        return true;
    }

    private void clearPillarRecovery() {
        pillarApproachScan = null;
        pillarBaseWalk = null;
        pendingPillarApproach = null;
        scaffoldSupportRequirement = 0;
        scaffoldSupplyItemIndex = 0;
        scaffoldSupplyItem = null;
        pillarApproachActive = false;
    }

    private static double horizontalDistanceSquared(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private static boolean insideLocalTargetSurvey(AIPlayerEntity bot, BlockPos target) {
        if (bot == null || target == null) {
            return false;
        }
        BlockPos feet = bot.blockPosition();
        return horizontalDistanceSquared(feet, target) <= LOCAL_SEARCH_RADIUS * LOCAL_SEARCH_RADIUS
                && target.getY() >= feet.getY() - LOCAL_SEARCH_DOWN
                && target.getY() <= feet.getY() + LOCAL_SEARCH_UP;
    }

    /**
     * Starts one short exploration leg that was admitted from current line-of-sight terrain.  It
     * gives a generic {@code mine} request the same recovery behavior as gather, without making a
     * raw world scan, blind path, or mining action part of the search.
     */
    private boolean startObservedExploration(AIPlayerEntity bot) {
        ObservedSearchHops.Attempt attempt = observedSearchHops.begin(bot, null);
        if (!attempt.started()) {
            BotLog.action(bot, "mine_explore_hop_refused",
                    "attempt", attempt.number(),
                    "heading", attempt.heading() == null ? "none" : attempt.heading().toShortString(),
                    "reason", attempt.reason(),
                    "remaining", Math.max(0, EXPLORE_MAX_HOPS - observedSearchHops.attempts()));
            // A refusal is a planning fact, not movement.  Continue trying bounded alternatives
            // until the observation fence has exhausted the search budget.
            return !observedSearchHops.exhausted();
        }
        exploreTarget = attempt.observedGoal();
        exploreStart = bot.blockPosition().immutable();
        exploreStartedTick = elapsed;
        phase = Phase.EXPLORING;
        BotLog.action(bot, "mine_explore_hop",
                "attempt", attempt.number(),
                "heading", attempt.heading().toShortString(),
                "to", exploreTarget.toShortString(),
                "max_hop", ObservedSearchHops.HOP_DISTANCE);
        return true;
    }

    private void explore(AIPlayerEntity bot) {
        // Finding a visible target while walking is enough to hand control back to normal mining.
        // The next SEARCHING tick selects a verified local stance and never assumes the remote
        // compass heading contains a block.
        if (nearestTarget(bot) != null) {
            bot.getActionPack().stopAll();
            clearExploreLeg();
            phase = Phase.SEARCHING;
            return;
        }
        boolean arrived = exploreTarget == null || bot.blockPosition().distSqr(exploreTarget) <= 9.0D;
        if (arrived) {
            recordExploreArrival(bot);
            bot.getActionPack().stopAll();
            clearExploreLeg();
            phase = Phase.SEARCHING;
            return;
        }
        if (elapsed - exploreStartedTick > EXPLORE_MOVE_LIMIT
                || (elapsed - exploreStartedTick > 20 && bot.getActionPack().isPathExecutorIdle())) {
            BotLog.action(bot, "mine_explore_hop_ended",
                    "reason", elapsed - exploreStartedTick > EXPLORE_MOVE_LIMIT ? "timeout" : "route_ended",
                    "to", exploreTarget.toShortString());
            bot.getActionPack().stopAll();
            clearExploreLeg();
            phase = Phase.SEARCHING;
        }
    }

    private void recordExploreArrival(AIPlayerEntity bot) {
        if (exploreStart != null && bot.blockPosition().distSqr(exploreStart) > 9.0D) {
            completedExploreHops++;
            BotLog.action(bot, "mine_explore_arrived",
                    "hop", completedExploreHops,
                    "at", bot.blockPosition().toShortString(),
                    "attempts", observedSearchHops.attempts());
        }
    }

    private void clearExploreLeg() {
        exploreTarget = null;
        exploreStart = null;
    }

    /**
     * Starts a fresh finite observed-hop episode from the newly reached position.  It never
     * turns an unobserved block into a route target; it merely gives the next safe compass sweep
     * a new anchor after the previous one exhausted its local alternatives.
     */
    private void restartObservedExploration(AIPlayerEntity bot) {
        BotLog.action(bot, "mine_explore_episode_reset",
                "hops", completedExploreHops,
                "attempts", observedSearchHops.attempts(),
                "target", BuiltInRegistries.BLOCK.getKey(targetBlock));
        observedSearchHops.reset();
        clearExploreLeg();
        completedExploreHops = 0;
        miningExplorationAttempted = false;
        phase = Phase.SEARCHING;
    }

    /**
     * After a normal visible search finds no local source, a known ore family or common
     * geological source may open one fresh safe stair.  At or below its target layer the child
     * declines immediately, so arbitrary blocks and same-depth searches retain the ordinary
     * observed-hop result.
     */
    private boolean startMiningExploration(AIPlayerEntity bot) {
        if (!MiningExplorationTask.supports(targetBlock)) {
            return false;
        }
        if (miningExplorationCaveSurveyRequired) {
            if (!observedSearchHops.exhausted() || completedExploreHops <= 0
                    || miningExplorationCaveRedescents >= MAX_CAVE_REDESCENTS) {
                return false;
            }
            miningExplorationCaveSurveyRequired = false;
            miningExplorationAttempted = false;
            miningExplorationCaveRedescents++;
        }
        if (miningExplorationAttempted) {
            return false;
        }
        if (!ToolTier.canHarvestWithInventory(bot, targetBlock.defaultBlockState())) {
            fail("need_better_tool:" + ToolTier.requiredPickaxeItemId(targetBlock));
            return true;
        }
        miningExplorationAttempted = true;
        miningExploration = MiningExplorationTask.forBlocks(Set.of(targetBlock), miningExplorationExcludedCavities);
        miningExploration.start(bot);
        if (miningExploration.state() == TaskState.RUNNING) {
            BotLog.action(bot, "mine_exploration_handoff",
                    "target", BuiltInRegistries.BLOCK.getKey(targetBlock),
                    "from_y", bot.blockPosition().getY());
            return true;
        }
        if (miningExploration.state() == TaskState.FAILED) {
            String reason = miningExploration.failureReason();
            miningExploration = null;
            fail(reason == null || reason.isBlank() ? "mining_exploration_failed" : reason);
            return true;
        }
        // Already at/below the target layer (or no matching profile): the caller reports its
        // normal observed-search result rather than pretending a descent happened.
        miningExploration = null;
        return false;
    }

    /** Returns true while the child owns the bot, including a terminal child failure. */
    private boolean tickMiningExploration(AIPlayerEntity bot) {
        if (miningExploration == null) {
            return false;
        }
        if (miningExploration.state() == TaskState.RUNNING) {
            miningExploration.tick(bot);
        }
        if (miningExploration.state() == TaskState.RUNNING) {
            return true;
        }
        if (miningExploration.state() == TaskState.COMPLETED) {
            boolean openCavity = miningExploration.completedAtOpenCavity();
            BlockPos caveEntry = miningExploration.completedOpenCavity();
            miningExplorationTimeoutCredit += miningExploration.elapsedTicks();
            miningExploration = null;
            observedSearchHops.reset();
            clearExploreLeg();
            completedExploreHops = 0;
            miningExplorationCaveSurveyRequired = openCavity;
            if (openCavity && caveEntry != null) {
                miningExplorationExcludedCavities.add(caveEntry.immutable());
            }
            if (openCavity) {
                BotLog.action(bot, "mine_exploration_cave_survey",
                        "remaining_redescents", MAX_CAVE_REDESCENTS - miningExplorationCaveRedescents,
                        "excluded_entries", miningExplorationExcludedCavities.size());
            }
            phase = Phase.SEARCHING;
            return false;
        }
        String reason = miningExploration.failureReason();
        miningExploration = null;
        fail(reason == null || reason.isBlank() ? "mining_exploration_failed" : reason);
        return true;
    }

    private void move(AIPlayerEntity bot) {
        // The placement-enabled recovery has a stricter lifecycle than an ordinary local route:
        // as it climbs, keep the originally visible target live or stop before the pillar route
        // can turn stale sight into a break action behind new terrain.
        if (pillarApproachActive && !isCurrentVisibleTarget(bot, targetPos)) {
            cancelStalePillarApproach(bot);
            return;
        }
        if (targetPos == null || !bot.level().getBlockState(targetPos).is(targetBlock)) {
            pillarApproachActive = false;
            phase = Phase.SEARCHING;
            return;
        }
        // A pillar climbs by jumping: at the top of a jump the eye is briefly in reach of a block that the bot,
        // standing on what it has built so far, cannot yet mine. A climbing bot is judged where it lands, also
        // when its route ends mid-jump.
        boolean midJump = pillarApproachActive && !bot.onGround();
        if (HarvestCore.canReach(bot, targetPos) && !midJump) {
            // stopAll cancels a normal food/bow use (it preserves only a reactive shield), so the
            // pre-break ownership guard must run before it tears down the just-arrived route.
            // Keep the target and MOVING phase intact until the held vanilla use settles.
            if (bot.isUsingItem()) {
                return;
            }
            bot.getActionPack().stopAll();
            // Arriving from a path can put the bot in a dark pocket between SEARCHING cadence
            // checks.  Do not start a new BlockMiner transaction while a deferred torch use owns
            // the hand; MOVING remains intact and retries the same observed target next tick.
            if (maybePlaceAutomaticTorch(bot, true)) {
                return;
            }
            startMiningTarget(bot);
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            if (midJump) {
                return;
            }
            if (targetPos.equals(pillarBaseWalk)) {
                // The walk onto the column's floor is over: plan the pillar from the new level. A column
                // that still yields none is not chased on to a third floor (the bot would shuttle between
                // two), so the target is written off like any other failed pillar.
                pillarBaseWalk = null;
                if (pillarToBlock(bot, targetPos, false)) {
                    return;
                }
                EpisodeMemory.INSTANCE.exclude(bot.getUUID(), targetPos,
                        bot.level().getServer().getTickCount(), EpisodeMemory.TTL_UNREACHABLE);
                BotLog.action(bot, "mine_pillar_base_failed",
                        "target", targetPos.toShortString(), "at", bot.blockPosition().toShortString());
                targetPos = null;
            }
            phase = Phase.SEARCHING;
        }
    }

    /** Stops a placement-enabled route immediately if its exact high target left live sight. */
    private void cancelStalePillarApproach(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
        clearPillarRecovery();
        targetPos = null;
        phase = Phase.SEARCHING;
    }

    private void mine(AIPlayerEntity bot) {
        // A block this task has just broken is gone, not out of sight: it goes on to the pickup like any other.
        if (pillarApproachActive && !isCurrentVisibleTarget(bot, targetPos) && !isVisiblyAir(bot, targetPos)) {
            miner.cancel(bot);
            bot.getActionPack().stopAll();
            clearPillarRecovery();
            targetPos = null;
            phase = Phase.SEARCHING;
            return;
        }
        if (targetPos == null || !bot.level().getBlockState(targetPos).is(targetBlock)) {
            miner.cancel(bot);
            pillarApproachActive = false;
            startPickup(bot);
            return;
        }
        // P1-a: mining goes through BlockMiner (only starts when idle, never restarts and resets progress); block break/timeout moves to the pickup phase.
        BlockMiner.Status status = miner.tick(bot);
        if (status == BlockMiner.Status.DONE || status == BlockMiner.Status.FAILED) {
            startPickup(bot);
        }
    }

    private void startPickup(AIPlayerEntity bot) {
        pickupTicks = 120;
        brokeAt = bot.level().getGameTime();
        climbsTried.clear();
        phase = Phase.PICKING_UP;
    }

    private void pickup(AIPlayerEntity bot) {
        int collected = collectedDrops(bot);
        if (collected > 0) {
            // A prior tick's chaseDropAnyOf -> approachDropPhysically nudge can leave the action
            // pack mid pickup-nudge (sneaking held); nothing else clears it once this phase stops
            // being ticked, which would otherwise deadlock any paused task waiting on
            // ActionPack.hasActiveActions() to go idle before resuming.
            bot.getActionPack().stopAll();
            BotLog.action(bot, "pickup_collected", "count", collected);
            countSoFar += collected;
            phase = countSoFar >= countNeeded ? Phase.FINISHING : Phase.SEARCHING;
            return;
        }
        pickupTicks--;
        HarvestCore.chaseDropAnyOf(bot, targetDrops, 8.0D);
        if (pickupTicks <= 0) {
            if (!pickupSweepAttempted && HarvestCore.nearestDropAnyOf(bot, targetDrops, 8.0D).isPresent()) {
                pickupSweepAttempted = true;
                HarvestCore.sweepPickupAnyOf(bot, targetDrops, 8);
                pickupTicks = 60;
                return;
            }
            int partial = collectedDrops(bot);
            bot.getActionPack().stopAll();
            if (partial > 0) {
                BotLog.action(bot, "pickup_collected", "count", partial, "reason", "partial_pickup");
                countSoFar += partial;
                phase = Phase.FINISHING;
                return;
            }
            fail("pickup_timeout");
        }
    }

    private void startMiningTarget(AIPlayerEntity bot) {
        BlockState state = bot.level().getBlockState(targetPos);
        if (!ToolTier.canHarvestWithInventory(bot, state)) {
            fail("need_better_tool:" + ToolTier.requiredPickaxeItemId(targetBlock));
            return;
        }
        // GOALFIX-GF2: pre-mining hazard gate -- do not break the block when lava is adjacent to the target (breaking it would let the lava out and burn us to death); fail safely and let the caller figure out another approach.
        if (lavaAdjacent(bot, targetPos)) {
            BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.TASK, bot, "mine_hazard_skip",
                    "pos", targetPos.getX() + "," + targetPos.getY() + "," + targetPos.getZ());
            fail("mine_hazard_lava");
            return;
        }
        inventoryCountBeforeMining = HarvestCore.countInventoryItems(bot, targetDrops);
        pickupSweepAttempted = false;
        miner.begin(bot, targetPos); // P1-a: BlockMiner takes over mining; the mine() phase advances it every tick
        phase = Phase.MINING;
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        if (miningExploration != null) {
            miningExploration.abort(bot);
            miningExploration = null;
        }
        if (scaffoldSupplyTask != null) {
            scaffoldSupplyTask.abort(bot);
            scaffoldSupplyTask = null;
        }
        miner.cancel(bot);
        clearExploreLeg();
        clearTargetSighting();
        clearPillarRecovery();
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        if (scaffoldSupplyTask != null) {
            scaffoldSupplyTask.pause(bot);
            return;
        }
        if (miningExploration != null) {
            miningExploration.pause(bot);
            return;
        }
        miner.cancel(bot);
        clearExploreLeg();
        if (phase == Phase.TARGET_SIGHTING) {
            clearTargetSighting();
            phase = Phase.SEARCHING;
        }
        clearTargetSighting();
        // A cursor begins from a physical pose and must not resume after an external safety
        // preemption relocated the bot. An active pillar route will be re-proved by move().
        pillarApproachScan = null;
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onResume(AIPlayerEntity bot) {
        if (scaffoldSupplyTask != null) {
            scaffoldSupplyTask.resume(bot);
            return;
        }
        if (miningExploration != null) {
            miningExploration.resume(bot);
        }
        clearTargetSighting();
    }

    private static boolean lavaAdjacent(AIPlayerEntity bot, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (bot.level().getFluidState(pos.relative(direction)).is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }
}

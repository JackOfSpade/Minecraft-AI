package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.GatherToolPolicy;
import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.action.KnownCellPickupSweep;
import io.github.zoyluo.minecraftai.craft.RecipeRegistry;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.BotLogWriter;
import io.github.zoyluo.minecraftai.log.CapabilityTally;
import io.github.zoyluo.minecraftai.log.GatherConsistency;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.mining.OreProspector;
import io.github.zoyluo.minecraftai.mining.ToolTier;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import org.slf4j.event.Level;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public final class GatherQuotaTask extends AbstractTask {
    private static final int SEARCH_RADIUS = 16;
    /** Exact block-breaking requests stay local rather than becoming roaming gather missions. */
    private static final int EXACT_BREAK_SEARCH_RADIUS = 16;
    private static final int EXACT_BREAK_TIMEOUT = 1200;
    // A gather survey can widen only as far as the configured perception radius.  In strict
    // survival the default is 16, so a 32/48-block scan would merely enumerate cells that the
    // observation fence must reject.  An empty local view should instead enter observed
    // exploration and reveal new terrain through actual movement.
    private static final int MAX_SEARCH_RADIUS = ObservableSearchBounds.MAX_SURVEY_RADIUS;
    private static final int SEARCH_DOWN = 6;
    private static final int SEARCH_UP = 12;
    private static final int LARGE_SCAN_THROTTLE_TICKS = 10; // Throttle large-radius scans to protect TPS
    private static final int MAX_PICKUP_MISSES = 5;  // Tolerated consecutive failed-pickup count before roaming to a new patch
    private static final int MAX_ROAMS = 8;          // Stuck-step escape: max roam-to-new-patch hops (shared by tree search/patch switching, ~224 blocks total); fail only after exhausting this
    private static final int ROAM_DISTANCE = 28;     // Horizontal distance covered by each roam
    private static final int SELF_STUCK_LIMIT = 160; // A: self-stuck threshold for gathering (self-managed watchdog; see the isWaiting note)
    private static final int HARVEST_LIMIT = 240;    // Per-block atomic mining cap; the area watchdog must never interrupt HARVEST/PICKUP
    private static final int ROAM_MOVE_LIMIT = 100;  // If roaming hasn't reached its landing point after 5s (target elevated/unreachable, or stuck on a slope) → give up and return to SURVEY
    // A prospect can cover the full *visible* vertical sphere after a local survey.  It must
    // never become a hidden-world scan, and an incomplete prospect is deferred to movement after
    // a few ticks so the bot does not stand visibly idle while it enumerates that sphere.
    private static final int PROSPECT_RANGE = ObservableSearchBounds.MAX_PROSPECT_RADIUS;
    private static final int PROSPECT_INTERVAL = 40; // Throttle wide-range scans (once per 2s) to protect TPS
    private static final int PROSPECT_STATIONARY_TICK_LIMIT = 4;
    // Wide scans are spread across ticks: the strict-survival prospect used to run as one 180-420 ms server tick
    // (real session, 01:43-01:44). Each tick now advances the scan for at most this long (observability rules
    // unchanged: OreProspector.Scan still rays each candidate before reading its state).
    private static final long SCAN_STEP_BUDGET_NANOS = 2_000_000L;
    private static final int SCAN_STALE_TICKS = 300;     // a scan older than this (task paused, phase left) is dropped, not resumed
    private static final double SCAN_STALE_DISTANCE_SQ = 64.0D; // ... and so is one begun more than 8 blocks from where the bot is now
    private static final double EXPLORE_SCAN_STALE_DISTANCE_SQ = 400.0D; // the en-route scan legitimately trails a walking bot
    // EXPLORE uses short directional hops whose actual destination is selected from terrain the
    // bot can observe.  The remote compass heading is never passed to navigation as a terrain
    // goal.  Sixteen 12-block legs let a real player movement/chunk update reveal new terrain
    // without treating a denied scan or an unseen heightmap column as proof of absence.
    private static final int EXPLORE_MAX_HOPS = 16;
    /** Retry another compass heading on the next tick after an observation-fence refusal. */
    private static final int EXPLORE_REFUSED_HOP_RETRY_TICKS = 1;
    private static final int EXPLORE_MOVE_LIMIT = 300;   // If a single hop hasn't arrived after 15s → abandon the hop and return to SURVEY
    private static final int EXPLORE_SCAN_INTERVAL = 20; // Throttle light en-route scans (once per 1s, 16 blocks); stop as soon as a target is seen
    /** A cave is worth surveying first; at most this many productive cave surveys may reopen a safe stair. */
    private static final int MAX_CAVE_REDESCENTS = 2;
    private static final int KNOWN_RESOURCE_RANGE = 192; // Max distance for heading toward a knowledge-base remembered point
    private static final int GOTO_FAIL_EXCLUDE = 2;      // N consecutive GOTO failures toward the same target → blacklist it in working memory
    private static final int GOTO_STUCK_LIMIT = 80;      // R1: if the coordinate hasn't moved for this long (4s) while GOTO paths toward a tree → assume airborne/stuck and force recovery

    private static int configuredObservationRadius() {
        return Math.max(1, MinecraftAiConfig.get().perception().radius());
    }

    private enum Phase {
        SURVEY,
        GOTO,
        ENSURE_TOOL,
        HARVEST,
        PICKUP,
        BOOTSTRAP_PICKUP,
        DEPOSIT,
        ROAM,
        EXPLORE,
        DONE
    }

    private final Item targetItem;
    private final int targetCount;
    /** True when a player/tool requested an additional amount rather than an inventory total. */
    private final boolean countNewItems;
    /** Counts matching blocks actually broken instead of inventory items. */
    private final boolean countBrokenBlocks;
    /** Non-empty only for exact-breaking tasks such as clear_grass and break_blocks. */
    private final String exactBreakTaskName;
    private final String exactBreakTargetLabel;
    // Fix C: when the target is logs, accept/gather **any** tree species (a biome may not have
    // oak). Progress is counted against the total across the whole log family.
    private final Set<Item> acceptItems;
    private final Set<Block> harvestBlocks;
    private final boolean probabilisticDrop; // The break drop is probabilistic/partial (grass → seeds, berry bush → berries): dropping 0 is normal, not a "failed pickup"
    private Phase phase = Phase.SURVEY;
    private BlockPos targetPos;
    private int countSoFar;
    private int acceptedInventoryAtStart;
    private long pickedUpAtStart;
    private int countBeforeHarvest;
    private int pickupTicks;
    private int harvestStartedTick;
    private BlockPos pickupOrigin;
    private long pickupStatBeforeHarvest;
    private boolean pickupOriginApproachLogged;
    // Bounded walk around pickupOrigin when the drop is not observable (see KnownCellPickupSweep).
    private KnownCellPickupSweep pickupOriginSweep;
    private int pickupOriginSweepLogged;
    private int pickupMisses; // Count of consecutive "broke it but didn't pick up the drop" events; only ruled pickup_timeout past this limit (avoids failing the whole gather over one missed pickup)
    private boolean pickupSweepAttempted;
    private StockpileTask stockpileTask;
    // Optimal-tool-category gate (ENSURE_TOOL phase): the category still missing from inventory,
    // which craft candidate (wood, then stone) is being attempted, the nested CraftTask itself, and
    // the last attempt's failure reason (surfaced in the final missing_tool failure so the player
    // learns what to bring, e.g. "missing_tool:shovel need: oak_planks x2").
    private GatherToolPolicy.Category pendingToolCategory;
    private int toolCraftCandidateIndex;
    private Task toolCraftTask;
    private String lastToolCraftFailure;
    // Log bootstrap (GatherToolPolicy.Bootstrap): logs broken by hand only to craft the first axe are
    // earmarked for that craft and do not count toward the player's quota.
    private boolean bootstrapActive;
    private int bootstrapExcluded;
    // True from the moment a bare-hand bootstrap break of a LOG is started until it is recorded: only then is a
    // pickup wait owed (a non-log block broken while the bootstrap is still active drops nothing the craft needs).
    private boolean handLogBreakInFlight;
    // break_blocks only: the bootstrap logs are broken by hand and must still be collected to craft the axe.
    private int bootstrapPickupTicks;
    // The origin sweep dwells in each factual stand cell long enough for vanilla's pickup delay.
    // A capped log can throw its drop two cells away behind the remaining trunk, so one hundred
    // ticks can expire before that outer ring is reached.  Keep the recovery bounded, but allow
    // the complete radius-two sweep to visit its candidates before another bare-hand log is used.
    private static final int BOOTSTRAP_PICKUP_TICKS = 200;
    private int bootstrapPickupBaseline;
    // The cell of the log just broken by hand: a factual coordinate from our own break, the fail-closed
    // fallback when the drop popped out of sight behind the remaining logs (see bootstrapPickup).
    private BlockPos bootstrapPickupOrigin;
    private boolean bootstrapOriginApproachLogged;
    private KnownCellPickupSweep bootstrapOriginSweep;
    private int bootstrapOriginSweepLogged;
    private int searchRadius = SEARCH_RADIUS;
    private int lastScanTick = -100;
    private int lastProspectTick = -100; // Treeless-area fallback: tick of the last wide-range tree prospect (throttled)
    private OreProspector.Scan prospectScan;  // in-flight budgeted prospect scan (null when none)
    private int prospectScansFinished;    // test hook: scans that ran to completion (a cheap one can begin and finish in one tick)
    private long lastProspectScanMaxStepNanos; // test hook: longest single step of the last finished prospect scan
    private OreProspector.Scan exploreScan;   // in-flight budgeted en-route explore scan (null when none); only meaningful in Phase.EXPLORE
    private HarvestCore.NearestScan surveyScan; // in-flight budgeted wide (radius > SEARCH_RADIUS) survey scan; only meaningful in Phase.SURVEY
    // Elevation-difference tolerance: previous prospect target + a blacklist of unreachable
    // targets. Re-entering prospect means the previous target wasn't harvested (if it had been,
    // nearby SURVEY would have taken over and prospect wouldn't run again) → blacklist it and move
    // to the next one; this prevents a "repeatedly scanning the same unreachable target" infinite
    // loop (observed: grass in a valley at y66, bot on a cliff top at y86, the landing point got
    // pushed to the cliff top by the heightmap, and it kept rescanning the same grass tuft for
    // 395 ticks until timeout).
    private BlockPos lastProspectFound;
    // Exclusions are folded into EpisodeMemory (working memory): they survive across replans
    // (goal-level lifetime) and revive after a TTL. Semantics match the old static blacklist, but
    // this unifies the implementation with the equivalent mechanism in ore_dig/roam.
    private boolean surfaceTried; // B: fallback flag — when no tree is found underground, surface once and retry
    private int roamCount;        // Stuck-step escape: number of roam-to-new-patch hops already taken
    private BlockPos roamTarget;  // Landing point for the roam-to-new-patch move (walked to, never teleported)
    private int selfStuckTick;     // A: tick of the last time new material was actually gathered
    private int selfStuckCount;    // A: last recorded gathered count
    // EXPLORE state: an admitted directional-hop budget plus the count of legs physically reached.
    // A refusal to admit a visible local hop consumes only the search budget; it is never described
    // as travel.  The current target is always the observation-fence's resolved local goal, never
    // the remote compass/memory coordinate.
    private final ObservedSearchHops observedSearchHops = new ObservedSearchHops(EXPLORE_MAX_HOPS);
    // Keeps one physical-progress budget across the small observed legs of one exploration
    // episode. A Baritone route can remain non-idle while replanning in place, so idle alone is
    // not a sufficient completion signal.
    private final ExplorationProgressWindow explorationProgress = new ExplorationProgressWindow();
    private int exploreHops;
    private BlockPos exploreTarget;
    private BlockPos exploreStart;
    private int exploreHopStartTick;
    private BlockPos exploreHint;
    private boolean exploredSinceFind;
    private int lastExploreScanTick = -100;
    // A refused direction did not move or inspect terrain.  Keep retrying its bounded alternate
    // headings directly instead of performing another expensive 48/96-block survey first.
    private int nextExploreAdmissionTick = -1;
    /** One safe descent, plus at most two cave-survey re-descents, for a geological source. */
    private MiningExplorationTask miningExploration;
    private boolean miningExplorationAttempted;
    /** A visible cave stopped the stair; consume actual observed cave movement before digging again. */
    private boolean miningExplorationCaveSurveyRequired;
    private int miningExplorationCaveRedescents;
    /** Every cave entry this request actually surveyed; fresh re-descents rotate past these rims. */
    private final Set<BlockPos> miningExplorationExcludedCavities = new LinkedHashSet<>();
    /** Nested descent time has its own hard budget and does not consume the outer gather window. */
    private int miningExplorationTimeoutCredit;
    private BlockPos lastGotoTarget;
    private int gotoFailStreak;
    private boolean treeDigTried; // Whether the current target has already been escalated to dig-approach (tunnel down/through when a cliff-face/below-grade tree can't be reached)
    private BlockPos gotoStuckPos; // R1: last coordinate recorded by GOTO (used to detect an airborne/deadlocked bot that hasn't moved in a long time)
    private int gotoStuckTick;

    // Auditable gather logging (docs/LOGGING.md "Auditing a gather"): observation-only counters
    // and state feeding gather_unit/gather_summary, so a player's "did the bot really gather
    // that?" question can be answered from the log instead of taken on faith. None of these
    // influence phase/progress/counting semantics above.
    private final Map<Item, Integer> lastLoggedItemCounts = new HashMap<>();
    private int breaksCount;          // Blocks of the gathered family this task itself broke
    private int pickupsCount;         // Successful confirmPickup() calls (physical pickups confirmed)
    private int pickupMissesTotal;    // Cumulative count of gather_pickup_miss events (pickupMisses above resets on success)
    private int unattributedGains;    // Sum of gather_unit deltas not attributable to this task's own break
    private int gainedTotal;          // Sum of every gather_unit delta (pickup + unattributed)
    private boolean summaryLogged;    // Guards gather_summary to exactly one line per task run
    private AIPlayerEntity currentTickBot; // Set at the top of onTick; lets complete()/fail() (which take no bot) still log

    public GatherQuotaTask(Item targetItem, int targetCount) {
        this(targetItem, targetCount, false, null, "", "", false);
    }

    /**
     * Collects a new, additional amount even when matching items are already in the inventory.
     * This is the player-facing form used by explicit {@code gather count} requests.
     */
    public static GatherQuotaTask collectAdditional(Item targetItem, int targetCount) {
        return new GatherQuotaTask(targetItem, targetCount, false, null, "", "", true);
    }

    private GatherQuotaTask(Item targetItem, int targetCount, boolean countBrokenBlocks,
                            Set<Block> exactBreakBlocks, String exactBreakTaskName,
                            String exactBreakTargetLabel, boolean countNewItems) {
        this.targetItem = targetItem;
        this.targetCount = Math.max(1, targetCount);
        this.countBrokenBlocks = countBrokenBlocks;
        this.countNewItems = !countBrokenBlocks && countNewItems;
        this.exactBreakTaskName = countBrokenBlocks ? exactBreakTaskName : "";
        this.exactBreakTargetLabel = countBrokenBlocks ? exactBreakTargetLabel : "";
        this.acceptItems = acceptItemsFor(targetItem);
        this.harvestBlocks = exactBreakBlocks == null ? harvestBlocksFor(this.acceptItems) : Set.copyOf(exactBreakBlocks);
        this.probabilisticDrop = !countBrokenBlocks && (harvestBlocks.contains(Blocks.SHORT_GRASS)
                || harvestBlocks.contains(Blocks.SWEET_BERRY_BUSH));
    }

    /** Clears actual vegetation plants, independent of any random wheat-seed drops. */
    public static GatherQuotaTask clearGrass(int targetCount) {
        return new GatherQuotaTask(Items.WHEAT_SEEDS, targetCount, true,
                Set.of(Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN),
                "clear_grass", "grass plants", false);
    }

    /**
     * Breaks exactly the requested number of a specific nearby block.  Unlike gather, progress
     * is based on the physical blocks broken, so drops are irrelevant.
     */
    public static GatherQuotaTask breakBlocks(Block block, int targetCount) {
        return new GatherQuotaTask(block.asItem(), targetCount, true, Set.of(block),
                "break_blocks", BuiltInRegistries.BLOCK.getKey(block).toString(), false);
    }

    /**
     * Breaks exactly the requested number of leaf blocks of any tree type ("break 32 leaves").
     * Drops are irrelevant, so no tool is required (see GatherToolPolicy#leavesCategory).
     */
    public static GatherQuotaTask breakLeaves(int targetCount) {
        Set<Block> leaves = new java.util.HashSet<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block.defaultBlockState().is(net.minecraft.tags.BlockTags.LEAVES)) {
                leaves.add(block);
            }
        }
        return new GatherQuotaTask(Items.OAK_LEAVES, targetCount, true, leaves,
                "break_blocks", "leaves", false);
    }

    @Override
    public String name() {
        return countBrokenBlocks ? exactBreakTaskName : "gather";
    }

    @Override
    public String describe() {
        return countBrokenBlocks
                ? "Breaking " + exactBreakTargetLabel + " " + countSoFar + "/" + targetCount + " phase=" + phase
                : "Gathering " + (countNewItems ? "new " : "")
                + BuiltInRegistries.ITEM.getKey(targetItem) + " " + countSoFar + "/" + targetCount + " phase=" + phase;
    }

    @Override
    public double progress() {
        return Math.min(1.0D, (double) countSoFar / targetCount);
    }

    @Override
    public boolean isWaiting() {
        // Gathering runs its own watchdog rather than deferring to StuckWatcher's coarse
        // "pos+progress+inv unchanged within 200t → abort" monitor — on a treeless plateau it
        // would wrongly kill a gather that's still legitimately searching/roaming far away
        // (observed: stuck:gather progress=0 while the bot was still actively roaming/prospecting
        // for a tree got aborted at 200t, derailing the whole diamond-mining goal → the brain took
        // over and mined itself to death). This task's own three-layer fallback is sufficient:
        // (1) self-stuck (no new material gathered within SELF_STUCK_LIMIT → observed search);
        // (2) bounded observed hops; (3) gather_timeout (6000t).
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        acceptedInventoryAtStart = countBrokenBlocks ? 0 : countAccepted(bot);
        pickedUpAtStart = countBrokenBlocks ? 0L : pickedUpAccepted(bot);
        countSoFar = countBrokenBlocks || countNewItems ? 0 : acceptedInventoryAtStart;
        searchRadius = defaultSearchRadius();
        phase = countSoFar >= targetCount ? Phase.DONE : Phase.SURVEY;
        prospectScan = null;
        exploreScan = null;
        surveyScan = null;
        observedSearchHops.reset();
        explorationProgress.reset();
        exploreHops = 0;
        exploreTarget = null;
        exploreStart = null;
        exploreHint = null;
        nextExploreAdmissionTick = -1;
        miningExploration = null;
        miningExplorationAttempted = false;
        miningExplorationCaveSurveyRequired = false;
        miningExplorationCaveRedescents = 0;
        miningExplorationExcludedCavities.clear();
        miningExplorationTimeoutCredit = 0;
        stockpileTask = null;
        pickupOrigin = null;
        pickupStatBeforeHarvest = pickedUpAccepted(bot);
        pickupOriginApproachLogged = false;
        bootstrapPickupOrigin = null;
        bootstrapOriginApproachLogged = false;
        bootstrapOriginSweep = null;
        pickupOriginSweep = null;
        breaksCount = 0;
        pickupsCount = 0;
        pickupMissesTotal = 0;
        unattributedGains = 0;
        gainedTotal = 0;
        summaryLogged = false;
        lastLoggedItemCounts.clear();
        if (!countBrokenBlocks) {
            lastLoggedItemCounts.putAll(currentAcceptedCounts(bot));
        }
        CapabilityTally.INSTANCE.reset(bot.getUUID());
    }

    @Override
    protected void onResume(AIPlayerEntity bot) {
        if (miningExploration != null) {
            miningExploration.resume(bot);
            return;
        }
        prospectScan = null; // a scan begun before the pause reflects a stale position
        exploreScan = null;
        surveyScan = null;
        if (!countBrokenBlocks) {
            refreshCountSoFar(bot);
        }
        if (countSoFar >= targetCount) {
            clearPickupLedger();
            phase = Phase.DONE;
            return;
        }
        if (phase != Phase.HARVEST || targetPos == null || !isHarvestBlock(bot, targetPos)) {
            if (countBrokenBlocks && phase == Phase.HARVEST && targetPos != null) {
                recordBrokenBlock(bot);
            }
            return;
        }
        // pause() releases the MiningController, so a still-reachable atomic harvest must be
        // restarted immediately. Keep the original local deadline monotonic: repeated safety
        // preemption must not renew one stale target forever. The physical pickup ledger and the
        // task-wide 6000-tick budget remain monotonic for the same reason.
        if (HarvestCore.canReach(bot, targetPos)) {
            HarvestCore.startMining(bot, targetPos);
            BotLog.action(bot, "gather_harvest_resumed", "pos", targetPos.toShortString());
            return;
        }

        // Evade/shelter may finish at a different physical cell. Keeping HARVEST here would issue
        // out-of-reach mining every 200 ticks while never rebuilding a path. Re-survey from the
        // factual recovery pose; the still-live block remains eligible and can be selected again.
        BotLog.action(bot, "gather_harvest_resume_reselect",
                "pos", targetPos.toShortString(), "from", bot.blockPosition().toShortString());
        targetPos = null;
        clearPickupLedger();
        searchRadius = defaultSearchRadius();
        lastGotoTarget = null;
        gotoFailStreak = 0;
        treeDigTried = false;
        gotoStuckPos = null;
        resetSurveyWatchdog();
        phase = Phase.SURVEY;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        currentTickBot = bot; // complete()/fail() take no bot param; see the field's javadoc
        // Working memory: record the path traveled (4-block debounce); roam waypoint selection
        // avoids already-searched areas (no more blindly circling).
        EpisodeMemory.INSTANCE.recordTrail(bot.getUUID(), countBrokenBlocks ? name() : "gather", bot.blockPosition());
        if (!countBrokenBlocks) {
            refreshCountSoFar(bot);
            // Any gain not claimed by confirmPickup's own "pickup" detection (below, later this
            // same tick or a previous one) is unattributed -- e.g. a player handed the bot an item.
            logGatherUnitGains(bot, "unattributed", null);
        }
        if (countSoFar >= targetCount) {
            if (miningExploration != null) {
                miningExploration.abort(bot);
                miningExploration = null;
            }
            bot.getActionPack().stopAll();
            clearPickupLedger();
            phase = Phase.DONE;
        }
        int timeout = countBrokenBlocks ? EXACT_BREAK_TIMEOUT : 6000;
        if (miningExploration == null && elapsed - miningExplorationTimeoutCredit > timeout) {
            fail(countBrokenBlocks ? name() + "_timeout" : "gather_timeout");
            return;
        }
        // Do not re-enter SURVEY/EXPLORE every tick while swimming. That used to burn all eight
        // exploration hops in place before NavSafetyNet's low-air threshold could take control.
        if (waitForDryGround(bot)) {
            return;
        }
        if (tickMiningExploration(bot)) {
            return;
        }
        // The current session showed an empty prospect followed by one refused eastward hop,
        // then a long, visibly idle re-survey. A refusal means the observation fence did not
        // admit that one local leg; it says nothing about the other compass directions. Give
        // those directions their bounded chance on successive ticks, without spending the time
        // rediscovering the same empty local view.
        if (phase == Phase.SURVEY && nextExploreAdmissionTick >= 0
                && elapsed >= nextExploreAdmissionTick) {
            if (!startExplore(bot)) {
                if (!startMiningExploration(bot)) {
                    fail(exploreHops > 0
                            ? "no_observed_resource_after_exploration"
                            : "no_observed_resource_in_local_view");
                }
            }
            return;
        }
        // A: gather self-healing — looks only at "whether new material was gathered" (whether
        // count is growing), not position. Slow GOTO progress with no gathering for a long time
        // (can't reach the tree / can't break it) also counts as stuck → roam to a new patch
        // promptly instead of slowly burning down to gather_timeout (observed: gathered 5 logs
        // then couldn't reach the rest, and got killed by the 5-minute timeout).
        if (phase == Phase.SURVEY || phase == Phase.GOTO) {
            if (countSoFar != selfStuckCount) {
                selfStuckCount = countSoFar;
                selfStuckTick = elapsed;
                observedSearchHops.reset();
                explorationProgress.reset();
                exploreHops = 0;          // Gathering something new means this patch produces — reset the explore hop budget
                exploredSinceFind = true; // The next "found after exploring" is worth recording into memory again
            } else if (elapsed - selfStuckTick > SELF_STUCK_LIMIT) {
                if (countBrokenBlocks) {
                    fail(exactBreakNoNearbyReason());
                    return;
                }
                if (escapeBarrenArea(bot)) {
                    return; // Both already set phase=ROAM/EXPLORE internally (walking to a new patch / heading outward to explore); no more self-checks while moving
                }
            }
        }
        // A budgeted scan only means something while its phase is current. Dropping them here, whatever code
        // path changed the phase (exploreMove's exits, escapeBarrenArea, pause/resume, swimming, ...), means no
        // exit from EXPLORE (or SURVEY) can leave a stale scan behind to be resumed from an old position.
        if (phase != Phase.EXPLORE) {
            exploreScan = null;
        }
        if (phase != Phase.SURVEY) {
            surveyScan = null;
        }
        switch (phase) {
            case SURVEY -> survey(bot);
            case GOTO -> goToTarget(bot);
            case ENSURE_TOOL -> ensureTool(bot);
            case HARVEST -> harvest(bot);
            case PICKUP -> pickup(bot);
            case BOOTSTRAP_PICKUP -> bootstrapPickup(bot);
            case DEPOSIT -> deposit(bot);
            case ROAM -> roamMove(bot);
            case EXPLORE -> exploreMove(bot);
            case DONE -> complete();
        }
    }

    // B: when the bot is underground (no sky overhead) and no reachable resource can be found
    // nearby, surface to the nearest open-sky standable point directly above, then retry
    // gathering. Surfacing is an emergency teleport (which clears fallDistance), so it exists only in the
    // operator profile: the capability is decided FIRST and a denial (strict survival) returns before
    // any of the upward cell lookups, because that scan reads cells the bot cannot see. If already in the
    // open, it does nothing. This is a fallback beyond "centralized gathering" and rarely triggers.
    private boolean trySurface(AIPlayerEntity bot) {
        var world = bot.level();
        BlockPos feet = bot.blockPosition();
        if (world.canSeeSky(feet)) {
            return false;
        }
        if (!io.github.zoyluo.minecraftai.mode.CapabilityRuntime.decide(bot,
                io.github.zoyluo.minecraftai.mode.PrivilegedCapability.EMERGENCY_TELEPORT, "gather_surface").allowed()) {
            return false;
        }
        int top = world.getMinY() + world.getHeight();
        for (int dy = 1; feet.getY() + dy < top - 1 && dy <= 80; dy++) {
            BlockPos candidate = feet.above(dy);
            surfaceScanLookups++;
            if (Standability.isStandable(world, candidate) && world.canSeeSky(candidate)) {
                bot.getActionPack().stopAll();
                bot.teleportTo(world, candidate.getX() + 0.5D, candidate.getY(), candidate.getZ() + 0.5D,
                        java.util.Collections.emptySet(), bot.getYRot(), bot.getXRot(), true);
                BotLog.action(bot, "gather_surfaced",
                        "to", candidate.getX() + "," + candidate.getY() + "," + candidate.getZ());
                return true;
            }
        }
        return false;
    }

    /** Test hook: how many upward cells {@link #trySurface} has looked at (none while the capability is denied). */
    static volatile int surfaceScanLookups;

    // A visible-sphere palette scan locates the nearest target block (e.g. logs) and pathfinds
    // to that column's surface landing point.  It can catch a visible target above/below the
    // shallower local survey, but it must yield to observed exploration rather than block motion.
    // Returns false when this call finds nothing (throttle not elapsed / genuinely no such
    // resource in range), deferring to observation-fenced exploration that reveals terrain by movement.
    private boolean prospectAndApproach(AIPlayerEntity bot, int prospectRadius) {
        int now = bot.level().getServer().getTickCount();
        java.util.UUID botId = bot.getUUID();
        if (prospectScan == null) {
            if (now - lastProspectTick < PROSPECT_INTERVAL) {
                return false;
            }
            lastProspectTick = now;
            // Reaching prospect again means the previous prospect target wasn't harvested (if it had
            // been, nearby SURVEY would already have taken over) → blacklist it and move to the next
            // one, ruling out a "repeatedly scanning the same unreachable target" infinite loop.
            // Anti-bloat: the blacklist clears and starts over once it exceeds 32 entries (a resource
            // may become reachable later).
            if (lastProspectFound != null) {
                EpisodeMemory.INSTANCE.exclude(botId, lastProspectFound, now, EpisodeMemory.TTL_UNREACHABLE);
                lastProspectFound = null;
            }
            var scanServer = bot.level().getServer();
            prospectScan = OreProspector.beginObservable(bot, prospectRadius,
                    state -> harvestBlocks.contains(state.getBlock()),
                    pos -> !EpisodeMemory.INSTANCE.isExcluded(botId, pos, scanServer.getTickCount()));
        }
        if (!prospectScan.step(SCAN_STEP_BUDGET_NANOS)) {
            return true; // still scanning: hold position, survey() resumes this scan first on the next tick
        }
        OreProspector.Scan scan = prospectScan;
        prospectScan = null;
        prospectScansFinished++;
        lastProspectScanMaxStepNanos = scan.maxStepNanos();
        var world = bot.level();
        BlockPos found = scan.result();
        if (found == null) {
            // Observability: silently returning false can't distinguish "genuinely no such
            // resource within the configured visible range" from "a scanning/blacklist bug" (observed: died in 21
            // ticks with no way to diagnose).
            BotLog.action(bot, "gather_prospect_empty",
                    "item", targetItem, "range", prospectRadius,
                    "blacklisted", EpisodeMemory.INSTANCE.excludedCount(botId),
                    "scan_steps", scan.steps(),
                    "scan_max_step_us", scan.maxStepNanos() / 1000L,
                    "scan_total_ms", scan.totalNanos() / 1_000_000L);
            return false; // Does not clear the exclusion (a TTL-backed "genuinely can't get there"); defers to observed exploration
        }
        // Anchor the landing point to the target's actual position: the target may be in a
        // valley/low ground — the old approach used that column's heightmap, which pushed the
        // landing point to a cliff top dozens of blocks above the target, unreachable even after
        // walking there (observed: found=y66 to=y86 infinite loop). Let A* work out the downhill
        // route itself.
        BlockPos ground = standNearTarget(world, found);
        if (ground == null) {
            // No standable point around a cliff-face/elevation-difference tree: plain walking has
            // no solution, but dig-approach can tunnel down/through (the real cause of 67% of
            // diamond-mining log-gathering failures at cliffs — observed GOAL_UNREACHABLE×3 that
            // never triggered the fallback, because the fallback originally lived only in
            // goToTarget, while cliff failures took this prospect path instead).
            if (tryDigApproach(bot, found, "no_stand")) {
                return true;
            }
            BotLog.action(bot, "gather_prospect_unreachable",
                    "found", found.toShortString(), "why", "no_stand");
            EpisodeMemory.INSTANCE.exclude(botId, found, now, EpisodeMemory.TTL_UNREACHABLE);
            return false;
        }
        bot.getActionPack().stopAll();
        // Walking rejected → first escalate to dig-approach (tunnel to a tree below/against a
        // cliff); only blacklist and move to the next one if digging also fails.
        var pathResult = bot.getActionPack().startPathTo(ground);
        if (pathResult.isFailed()) {
            if (tryDigApproach(bot, found, pathResult.reason())) {
                return true;
            }
            BotLog.action(bot, "gather_prospect_unreachable",
                    "found", found.toShortString(), "why", pathResult.reason());
            EpisodeMemory.INSTANCE.exclude(botId, found, now, EpisodeMemory.TTL_UNREACHABLE);
            return false;
        }
        lastProspectFound = found.immutable();
        // Lock directly onto this tree and go through GOTO, letting goToTarget uniformly drive
        // "arrive → harvest" (including dig-approach for cliffs/elevation differences + R1
        // airborne-stuck recovery). No longer routes through ROAM-to-landing-point-then-rescan:
        // rescanning often loses this tree (observed: an elevated spruce at distance 4 was
        // prospected, but SURVEY couldn't rescan it back → expand → died in 43 ticks with
        // no_resource, 3x goal_failed). GOTO commits: if unreachable, dig or blacklist and switch
        // trees, instead of spinning and losing the tree.
        targetPos = found.immutable();
        lastGotoTarget = targetPos;
        treeDigTried = false;
        gotoFailStreak = 0;
        gotoStuckPos = null;          // Reset the R1 watchdog baseline
        searchRadius = SEARCH_RADIUS;
        pickupMisses = 0;
        selfStuckTick = elapsed;
        phase = Phase.GOTO;
        bot.getActionPack().startPathTo(ground);
        BotLog.action(bot, "gather_prospected",
                "found", found.getX() + "," + found.getY() + "," + found.getZ(),
                "to", ground.getX() + "," + ground.getY() + "," + ground.getZ(),
                "item", BuiltInRegistries.ITEM.getKey(targetItem).toString(),
                "dist", (int) Math.sqrt(bot.blockPosition().distSqr(found)),
                "scan_steps", scan.steps(),
                "scan_max_step_us", scan.maxStepNanos() / 1000L);
        return true;
    }

    // Find a landing point anchored to the target: prefer the target's own cell first
    // (non-colliding blocks like short grass/saplings can be stood on directly), then the four
    // neighbors at ±1 level; if none are standable (the target is buried in a solid block or
    // floating), fall back to that column's surface (for a trunk column: stand beside the tree's
    // roots).
    private BlockPos standNearTarget(net.minecraft.server.level.ServerLevel world, BlockPos found) {
        if (Standability.isStandable(world, found)) {
            return found;
        }
        // R2: search the four neighbors vertically from ±1 up to ±3. Cliff-face/below-grade trees
        // often have a 3-4 block elevation difference (observed on seed20260610: bot at y77, tree
        // at y73 — ±1 found no landing point → no_stand → prospect died quickly). ±3 covers
        // typical cliff differences and significantly improves the success rate for reaching trees
        // below/against a cliff.
        for (var dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos side = found.relative(dir);
            for (int dy : new int[]{0, -1, 1, -2, 2, -3, 3}) {
                BlockPos p = side.above(dy);
                if (Standability.isStandable(world, p)) {
                    return p;
                }
            }
        }
        return findGroundAt(world, found.getX(), found.getZ());
    }

    // The only legal escape from an empty patch is an admitted directional hop.  Older code
    // guessed a remote height-map landing point and asked navigation to walk there.  Strict
    // survival correctly rejects that unseen goal, but the task then mistook the rejection for
    // proof that the resource was absent.  ObservedSearchHops keeps the distinction explicit.
    private boolean escapeBarrenArea(AIPlayerEntity bot) {
        return startExplore(bot);
    }

    // Compatibility entry point for the pickup-recovery path.  It now uses the same bounded,
    // observation-fenced exploration as every other empty-patch escape; it never manufactures a
    // remote terrain landing point.
    private boolean roamToNewArea(AIPlayerEntity bot) {
        return startExplore(bot);
    }

    // Search column (x,z) from high to low for the first standable point (surface/forest floor).
    private BlockPos findGroundAt(net.minecraft.server.level.ServerLevel world, int x, int z) {
        // Use the heightmap to get that column's surface, which works at any elevation (the old
        // hard cap of y=110 made roaming/landing fail entirely when the bot stood on ground above
        // y=110 — the same root-cause bug as in HuntTask). Canopy penetration (same fix as
        // HuntTask.findGround): the old MOTION_BLOCKING top surface lands in the canopy in a
        // forest (tall spruce 20+ blocks — a fixed downward-search offset can't reliably win that
        // bet). The correct fix: MOTION_BLOCKING_NO_LEAVES natively skips leaves, so the top
        // surface is terrain/trunk, and we then descend to the ground.
        int surfaceY = world.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        for (int y = surfaceY; y >= surfaceY - 24 && y > world.getMinY() + 1; y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (Standability.isStandable(world, p)) {
                return p;
            }
        }
        return null;
    }

    // Legacy ROAM state retained for checkpoint/source compatibility.  New empty-patch recovery
    // enters EXPLORE instead, so this state is not used to pick unseen terrain destinations.
    private void roamMove(AIPlayerEntity bot) {
        // 20-tick startup grace period: after startPathTo, async A* computation takes a few
        // ticks, during which the executor is still idle — judging "can't move" immediately
        // would bounce straight back to SURVEY, and the prospect blacklist mechanism would then
        // wrongly kill a good target that "just hasn't set off yet" as "unreachable" (observed a
        // cascade of false kills during grass-clearing that ran all the way down to no_resource).
        boolean pathGaveUp = elapsed - selfStuckTick > 20 && bot.getActionPack().isPathExecutorIdle();
        if (roamTarget == null
                || bot.blockPosition().distSqr(roamTarget) <= 9.0D
                || pathGaveUp
                || elapsed - selfStuckTick > ROAM_MOVE_LIMIT) { // Taking too long without arriving (roam target elevated/unreachable) → give up and return to SURVEY to find a reachable resource nearby
            roamTarget = null;
            searchRadius = SEARCH_RADIUS;
            phase = Phase.SURVEY;
        }
    }

    // EXPLORE hop takeoff: memory may provide a heading, but only the observation fence chooses
    // the actual local route destination.  No block-state/height-map query occurs before this
    // navigation request, so an unseen cliff cannot turn into an invented "empty area" result.
    private boolean startExplore(AIPlayerEntity bot) {
        if (observedSearchHops.exhausted()) {
            return false;
        }
        BlockPos feet = bot.blockPosition();
        exploreHint = null;
        int now = bot.level().getServer().getTickCount();
        for (Block block : harvestBlocks) {
            var known = io.github.zoyluo.minecraftai.memory.KnowledgeBase.INSTANCE.nearestResource(
                    bot.getUUID(), BuiltInRegistries.BLOCK.getKey(block).toString(), feet, KNOWN_RESOURCE_RANGE,
                    pos -> !EpisodeMemory.INSTANCE.isExcluded(bot.getUUID(), pos, now));
            if (known.isPresent()) {
                exploreHint = known.get().pos();
                break;
            }
        }
        ObservedSearchHops.Attempt attempt = observedSearchHops.begin(bot, exploreHint);
        if (!attempt.started()) {
            BotLog.action(bot, "gather_explore_hop_refused",
                    "attempt", attempt.number(),
                    "heading", attempt.heading() == null ? "none" : attempt.heading().toShortString(),
                    "reason", attempt.reason(),
                    "remaining", Math.max(0, EXPLORE_MAX_HOPS - observedSearchHops.attempts()));
            // A refused hop means no physical search happened. Retry the next bounded compass
            // heading immediately rather than leaving the bot idle through another full survey.
            nextExploreAdmissionTick = observedSearchHops.exhausted()
                    ? -1 : elapsed + EXPLORE_REFUSED_HOP_RETRY_TICKS;
            return !observedSearchHops.exhausted();
        }
        nextExploreAdmissionTick = -1;
        exploreTarget = attempt.observedGoal();
        exploreStart = feet.immutable();
        explorationProgress.beginLeg(exploreStart);
        exploreHopStartTick = elapsed;
        exploredSinceFind = true;
        phase = Phase.EXPLORE;
        BotLog.action(bot, "gather_explore_hop",
                "attempt", attempt.number(),
                "heading", attempt.heading().toShortString(),
                "to", exploreTarget.toShortString(),
                "mode", attempt.guided() ? "known_heading" : "compass",
                "max_hop", ObservedSearchHops.HOP_DISTANCE);
        return true;
    }

    // EXPLORE advance: stride toward the hop's waypoint, with a light scan every second along the
    // way; on arrival / submersion / timeout / a broken path, all converge back to SURVEY — which
    // then decides the next step (takes over gathering if something's nearby, or runs the fail
    // chain to trigger the next startExplore hop if not). A single exit point keeps the state
    // machine from diverging.
    private void exploreMove(AIPlayerEntity bot) {
        // (1) This hop has taken too long without arriving (the waypoint is genuinely hard to
        // reach / the route is a long detour) → abandon the hop and return to SURVEY to rescan
        // (reset scan radius/throttle).
        if (elapsed - exploreHopStartTick > EXPLORE_MOVE_LIMIT) {
            observedSearchHops.retireObservedGoal(exploreTarget);
            bot.getActionPack().stopAll();
            excludeExploreHint(bot, "timeout");
            exploreTarget = null;
            exploreStart = null;
            searchRadius = SEARCH_RADIUS;
            lastScanTick = -100;
            exploreScan = null; // every exit from EXPLORE drops its en-route scan
            phase = Phase.SURVEY;
            return;
        }
        // (2) Light en-route scan (every EXPLORE_SCAN_INTERVAL ticks, 16 blocks): stop as soon as
        // a target block is spotted and hand back to SURVEY for precise gathering.
        int now = bot.level().getServer().getTickCount();
        if (exploreScan != null && scanIsStale(bot, exploreScan, EXPLORE_SCAN_STALE_DISTANCE_SQ)) {
            exploreScan = null;
        }
        if (exploreScan == null && now - lastExploreScanTick >= EXPLORE_SCAN_INTERVAL) {
            lastExploreScanTick = now;
            exploreScan = OreProspector.beginObservable(bot, 16, state -> harvestBlocks.contains(state.getBlock()), null);
        }
        if (exploreScan != null && exploreScan.step(SCAN_STEP_BUDGET_NANOS)) {
            BlockPos seen = exploreScan.result();
            exploreScan = null;
            if (seen != null) {
                bot.getActionPack().stopAll();
                exploreTarget = null;
                exploreStart = null;
                searchRadius = SEARCH_RADIUS;
                exploreScan = null; // every exit from EXPLORE drops its en-route scan
                phase = Phase.SURVEY;
                return;
            }
        }
        // (3) A directional-pursuit route ends at an exact observed GoalBlock. Treating "within
        // three blocks" as arrival used to stop Baritone just short of small hops, then reissue
        // another local route from almost the same cell. That looks like circling in the client.
        // On exact arrival, if memory-guided travel got within 16
        // blocks of the memory point without being intercepted by (2), the old intel was stale
        // (nothing there) → retire that resource point, so the next startExplore doesn't head for
        // the same stale intel again; then return to SURVEY (the fail chain keeps exploring
        // outward if there's still nothing).
        if (exploreTarget == null || bot.blockPosition().equals(exploreTarget)) {
            boolean physicallyMoved = exploreStart != null
                    && !bot.blockPosition().equals(exploreStart);
            if (physicallyMoved) {
                exploreHops++;
                BotLog.action(bot, "gather_explore_arrived",
                        "hop", exploreHops,
                        "at", bot.blockPosition().toShortString(),
                        "attempts", observedSearchHops.attempts());
            } else {
                // A fence may occasionally resolve a heading to the cell already occupied by
                // the bot. It is not a completed hop and must not be admitted again.
                observedSearchHops.retireObservedGoal(exploreTarget);
            }
            if (exploreHint != null && bot.blockPosition().distSqr(exploreHint) <= 256.0D) {
                invalidateKnownResource(bot, exploreHint);
                exploreHint = null;
            }
            bot.getActionPack().stopAll();
            exploreTarget = null;
            exploreStart = null;
            searchRadius = SEARCH_RADIUS;
            exploreScan = null; // every exit from EXPLORE drops its en-route scan
            phase = Phase.SURVEY;
            return;
        }
        // (4) Baritone can stay active while recomputing a short route without making the bot
        // cross a new block cell. Stop that spin well before the coarse 300-tick route timeout,
        // retire its exact local goal, and let SURVEY choose another observed corridor.
        if (explorationProgress.stalled(bot.blockPosition())) {
            BlockPos stalledGoal = exploreTarget;
            observedSearchHops.retireObservedGoal(stalledGoal);
            bot.getActionPack().stopAll();
            excludeExploreHint(bot, "no_progress");
            BotLog.action(bot, "gather_explore_stalled",
                    "at", bot.blockPosition().toShortString(),
                    "goal", stalledGoal == null ? "none" : stalledGoal.toShortString(),
                    "ticks_without_frontier", explorationProgress.ticksSinceFrontier(),
                    "attempts", observedSearchHops.attempts());
            exploreTarget = null;
            exploreStart = null;
            searchRadius = SEARCH_RADIUS;
            lastScanTick = -100;
            exploreScan = null; // every exit from EXPLORE drops its en-route scan
            phase = Phase.SURVEY;
            return;
        }
        // (5) A directional route that goes idle before its locally observed destination is not
        // retried against an invented terrain point.  Drop back to SURVEY; the next bounded
        // attempt chooses a fresh observed hop and preserves the fact that no leg was completed.
        if (elapsed - exploreHopStartTick > 20 && bot.getActionPack().isPathExecutorIdle()) {
            observedSearchHops.retireObservedGoal(exploreTarget);
            bot.getActionPack().stopAll();
            excludeExploreHint(bot, "route_ended");
            exploreTarget = null;
            exploreStart = null;
            searchRadius = SEARCH_RADIUS;
            exploreScan = null;
            phase = Phase.SURVEY;
        }
    }

    private void survey(AIPlayerEntity bot) {
        if (harvestBlocks.isEmpty()) {
            fail("unsupported_resource_type");
            return;
        }
        int now = bot.level().getServer().getTickCount();
        int configuredRadius = configuredObservationRadius();
        int surveyRadiusLimit = ObservableSearchBounds.surveyRadius(configuredRadius);
        int prospectRadiusLimit = ObservableSearchBounds.prospectRadius(configuredRadius);
        // A task restored from an older build can carry a 32/48-radius survey.  Do not resume it
        // when the current profile cannot observe that far; it has no possible discovery outcome.
        if (!countBrokenBlocks && searchRadius > surveyRadiusLimit) {
            searchRadius = surveyRadiusLimit;
            surveyScan = null;
        }
        // A prospect scan can find a vertically visible target the shallow local survey misses.
        // It gets a few active ticks, then keeps no one waiting: EXPLORE runs the same observable
        // scan while the bot is actually revealing new terrain.
        if (prospectScan != null) {
            int prospectAge = now - prospectScan.startTick();
            if (scanIsStale(bot, prospectScan, SCAN_STALE_DISTANCE_SQ)
                    || prospectAge >= PROSPECT_STATIONARY_TICK_LIMIT) {
                if (prospectAge >= PROSPECT_STATIONARY_TICK_LIMIT) {
                    BotLog.action(bot, "gather_prospect_deferred_to_explore",
                            "range", prospectRadiusLimit,
                            "active_ticks", prospectAge,
                            "steps", prospectScan.steps());
                }
                prospectScan = null;
            } else {
                if (!prospectAndApproach(bot, prospectRadiusLimit)) {
                    escapeBarrenAreaOrFail(bot); // scan finished with nothing usable: the same tail the synchronous flow ran
                }
                return;
            }
        }
        if (!countBrokenBlocks && HarvestCore.isInventoryFull(bot) && countSoFar < targetCount) {
            phase = Phase.DEPOSIT;
            return;
        }
        // F1: throttle large-radius scans to avoid scanning a 48-block cube every tick and
        // dragging down TPS.
        if (surveyScan != null && scanIsStale(bot, surveyScan.startTick(), surveyScan.origin(), SCAN_STALE_DISTANCE_SQ)) {
            surveyScan = null;
        }
        if (surveyScan == null && searchRadius > SEARCH_RADIUS && now - lastScanTick < LARGE_SCAN_THROTTLE_TICKS) {
            return;
        }
        // Where the unreachable blacklist takes effect: coordinates blacklisted by goToTarget
        // (repeated failures against the same target) are filtered out of the candidate
        // stream — survey no longer repeatedly relocks onto the same unreachable tree (observed
        // on real_wood: relock → GOTO fails → relock, ping-ponging all the way to the 6001t
        // timeout).
        java.util.UUID botId = bot.getUUID();
        var surveyServer = bot.level().getServer();
        HarvestCore.TargetChoice choice;
        if (searchRadius > SEARCH_RADIUS) {
            // The wide survey (radius 32/48: ~170,000 positions, 90-165 ms cold) is a resumable scan advanced a
            // couple of milliseconds per tick, like the prospect below, instead of one server tick.
            if (surveyScan == null) {
                lastScanTick = now;
                surveyScan = HarvestCore.beginNearestScan(bot, harvestBlocks, searchRadius, SEARCH_DOWN, SEARCH_UP,
                        pos -> !EpisodeMemory.INSTANCE.isExcluded(botId, pos, surveyServer.getTickCount()),
                        needsCellObservation());
            }
            if (!surveyScan.step(SCAN_STEP_BUDGET_NANOS)) {
                return; // still scanning: hold position, this scan resumes first on the next tick
            }
            choice = surveyScan.result();
            surveyScan = null;
        } else {
            surveyScan = null; // the radius was reset while a wide scan was in flight: that scan is obsolete
            lastScanTick = now;
            choice = HarvestCore.nearestReachableBlock(bot, harvestBlocks, searchRadius, SEARCH_DOWN, SEARCH_UP,
                    pos -> !EpisodeMemory.INSTANCE.isExcluded(botId, pos, now),
                    needsCellObservation());
        }
        if (choice == null) {
            if (countBrokenBlocks) {
                // An exact break request is deliberately a small, nearby gesture. Do not surface,
                // prospect, roam, or dig toward a distant patch when the player asks to break blocks.
                BotLog.action(bot, name() + "_no_nearby", "radius", searchRadius,
                        "block", exactBreakTargetLabel);
                fail(exactBreakNoNearbyReason());
                return;
            }
            // Expand only through terrain the current profile could actually observe.  Default
            // strict survival ends at 16, then allows only its short visible-vertical prospect
            // before taking a bounded observed hop instead of iterating a 32/48 invisible cube.
            if (searchRadius < surveyRadiusLimit) {
                searchRadius = Math.min(surveyRadiusLimit, searchRadius * 2);
                BotLog.action(bot, "gather_expand_search",
                        "radius", searchRadius,
                        "item", BuiltInRegistries.ITEM.getKey(targetItem).toString());
                return;
            }
            // B: still no reachable resource even at the max radius → if the bot is underground
            // (no sky overhead), surface first and try another round (a fallback; under normal
            // conditions "centralized gathering" already stocks up on logs at the surface in one
            // pass, so this path is rarely reached).
            if (!surfaceTried && trySurface(bot)) {
                surfaceTried = true;
                searchRadius = SEARCH_RADIUS;
                return;
            }
            // The prospect checks the full visible vertical sphere, unlike the shallow local
            // survey. It is bounded to a few stationary ticks above; an unfinished pass continues
            // as the matching en-route scan while the bot takes an observed hop.
            if (prospectAndApproach(bot, prospectRadiusLimit)) {
                return;
            }
            BotLog.action(bot, "gather_observed_search_exhausted",
                    "radius", surveyRadiusLimit,
                    "perception_radius", configuredRadius,
                    "item", BuiltInRegistries.ITEM.getKey(targetItem).toString());
            // No resource in the actual view → extend that view only by a finite, admitted hop.
            escapeBarrenAreaOrFail(bot);
            return;
        }
        targetPos = choice.pos();
        // A successful find via exploring: feed it into the RESOURCE_FOUND stream (distilled into
        // a knowledge-base resource point, so the next same-type need heads straight for it);
        // only record "the first find after having explored" to avoid flooding the stream on
        // every survey hit (noise beyond the 8-block dedup).
        if (exploreHops > 0 && exploredSinceFind) {
            io.github.zoyluo.minecraftai.memory.EpisodeLog.INSTANCE.record(bot,
                    io.github.zoyluo.minecraftai.memory.EpisodeLog.Type.RESOURCE_FOUND, targetPos,
                    BuiltInRegistries.BLOCK.getKey(bot.level().getBlockState(targetPos).getBlock()).toString());
            exploredSinceFind = false;
            BotLog.action(bot, "gather_explore_found",
                    "pos", targetPos.getX() + "," + targetPos.getY() + "," + targetPos.getZ(),
                    "hops", exploreHops,
                    "attempts", observedSearchHops.attempts());
        }
        if (choice.direct()) {
            startHarvest(bot);
            return;
        }
        phase = Phase.GOTO;
        bot.getActionPack().startPathTo(choice.stand());
    }

    private void escapeBarrenAreaOrFail(AIPlayerEntity bot) {
        // At this point the local survey and its observable vertical prospect both found no
        // source.  For a real mining source (stone/cobblestone or a profiled ore), a player at
        // surface height should open a safe staircase now, not spend the entire directional-hop
        // budget walking across another surface patch.  MiningExplorationTask derives only a
        // downward target from the bot's own Y/dimension and keeps the existing safe descent;
        // it never supplies a hidden block location.  If this pose is already at/below the
        // applicable target, the child completes immediately and the normal observed-hop search
        // below remains the fallback.
        if (startMiningExploration(bot)) {
            return;
        }
        if (escapeBarrenArea(bot)) {
            surfaceTried = false; // New area — allow the "surface fallback" again
            return;
        }
        // Report only what actually happened.  A denied local route is not a completed search;
        // a model must not turn it into an assertion about terrain it never saw.
        if (observedSearchHops.exhausted()) {
            fail(exploreHops > 0
                    ? "no_observed_resource_after_exploration"
                    : "no_observed_resource_in_local_view");
            return;
        }
        fail("no_observed_resource_in_local_view");
    }

    /**
     * Starts the safe mining handoff for stone/cobblestone-like sources (or a known ore source
     * family). A cave completion must first exhaust real observed cave movement; only then may a
     * bounded re-descent continue toward the resource layer. Exact {@code break_blocks} jobs
     * intentionally remain local gestures.
     */
    private boolean startMiningExploration(AIPlayerEntity bot) {
        if (countBrokenBlocks || !MiningExplorationTask.supports(harvestBlocks)) {
            return false;
        }
        if (miningExplorationCaveSurveyRequired) {
            if (!observedSearchHops.exhausted() || exploreHops <= 0
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
        Block source = miningExplorationSource();
        if (source == null) {
            return false;
        }
        // Match GatherQuotaTask's ordinary source policy: make/carry a pickaxe before opening a
        // staircase.  This prevents a no-cobblestone request from silently hand-mining stone.
        if (!GatherToolPolicy.hasTool(bot, GatherToolPolicy.Category.PICKAXE)) {
            pendingToolCategory = GatherToolPolicy.Category.PICKAXE;
            toolCraftCandidateIndex = 0;
            toolCraftTask = null;
            lastToolCraftFailure = null;
            targetPos = null;
            phase = Phase.ENSURE_TOOL;
            BotLog.action(bot, "gather_mining_exploration_tool_missing",
                    "category", GatherToolPolicy.token(pendingToolCategory),
                    "source", BuiltInRegistries.BLOCK.getKey(source));
            return true;
        }
        if (!ToolTier.canHarvestWithInventory(bot, source.defaultBlockState())) {
            fail("need_better_tool:" + ToolTier.requiredPickaxeItemId(source));
            return true;
        }
        miningExplorationAttempted = true;
        miningExploration = MiningExplorationTask.forBlocks(harvestBlocks, miningExplorationExcludedCavities);
        miningExploration.start(bot);
        if (miningExploration.state() == TaskState.RUNNING) {
            BotLog.action(bot, "gather_mining_exploration_handoff",
                    "item", BuiltInRegistries.ITEM.getKey(targetItem),
                    "from_y", bot.blockPosition().getY());
            return true;
        }
        if (miningExploration.state() == TaskState.FAILED) {
            String reason = miningExploration.failureReason();
            miningExploration = null;
            fail(reason == null || reason.isBlank() ? "mining_exploration_failed" : reason);
            return true;
        }
        // No depth is appropriate at the current Y; surface the factual observed-search outcome.
        miningExploration = null;
        return false;
    }

    private Block miningExplorationSource() {
        for (Block block : harvestBlocks) {
            if (MiningExplorationTask.supports(block)) {
                return block;
            }
        }
        return null;
    }

    /** Returns true while the child owns movement/mining, including a terminal child failure. */
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
            searchRadius = defaultSearchRadius();
            lastScanTick = -100;
            lastProspectTick = -100;
            prospectScan = null;
            surveyScan = null;
            exploreScan = null;
            observedSearchHops.reset();
            explorationProgress.reset();
            exploreHops = 0;
            exploreTarget = null;
            exploreStart = null;
            exploreHint = null;
            nextExploreAdmissionTick = -1;
            surfaceTried = false;
            selfStuckTick = elapsed;
            selfStuckCount = countSoFar;
            miningExplorationCaveSurveyRequired = openCavity;
            if (openCavity && caveEntry != null) {
                miningExplorationExcludedCavities.add(caveEntry.immutable());
            }
            if (openCavity) {
                BotLog.action(bot, "gather_mining_exploration_cave_survey",
                        "remaining_redescents", MAX_CAVE_REDESCENTS - miningExplorationCaveRedescents,
                        "excluded_entries", miningExplorationExcludedCavities.size());
            }
            phase = Phase.SURVEY;
            return false;
        }
        String reason = miningExploration.failureReason();
        miningExploration = null;
        fail(reason == null || reason.isBlank() ? "mining_exploration_failed" : reason);
        return true;
    }

    /** Test hook: true while a budgeted prospect scan is in flight (ProspectScanBudgetGameTests). */
    boolean prospectScanActive() {
        return prospectScan != null;
    }

    /** Test hook: number of prospect scans that ran to completion (ProspectScanBudgetGameTests). */
    int prospectScansFinished() {
        return prospectScansFinished;
    }

    /** Test hook: longest single step of the last finished prospect scan, in nanoseconds. */
    long lastProspectScanMaxStepNanos() {
        return lastProspectScanMaxStepNanos;
    }

    /** A budgeted scan is only meaningful while the bot stays where it began; otherwise it is dropped and redone. */
    private static boolean scanIsStale(AIPlayerEntity bot, OreProspector.Scan scan, double maxDistanceSq) {
        return scanIsStale(bot, scan.startTick(), scan.origin(), maxDistanceSq);
    }

    private static boolean scanIsStale(AIPlayerEntity bot, int startTick, BlockPos origin, double maxDistanceSq) {
        int now = bot.level().getServer().getTickCount();
        return now - startTick > SCAN_STALE_TICKS
                || bot.blockPosition().distSqr(origin) > maxDistanceSq;
    }

    /** Test hook: true while a budgeted wide survey scan is in flight (ProspectScanBudgetGameTests). */
    boolean surveyScanActive() {
        return surveyScan != null;
    }

    /** Test hook: true while a budgeted en-route explore scan is in flight (ProspectScanBudgetGameTests). */
    boolean exploreScanActive() {
        return exploreScan != null;
    }

    // Dig-approach: when a cliff-face/elevation-difference tree is GOAL_UNREACHABLE by plain
    // walking, switch to startDigPathTo to tunnel down/through (the same primitive used to reach
    // buried ore while mining). On successful start → set targetPos=the tree and switch to GOTO,
    // letting goToTarget uniformly drive "arrive → harvest"; treeDigTried=true prevents
    // goToTarget from re-issuing it immediately. If digging also fails (rare: sealed off by
    // bedrock / out of bounds) → return false, and the caller blacklists it and switches trees.
    private boolean tryDigApproach(AIPlayerEntity bot, BlockPos tree, String why) {
        if (countBrokenBlocks) {
            return false;
        }
        ActionResult dig = bot.getActionPack().startDigPathTo(tree);
        if (dig.isFailed()) {
            return false;
        }
        BotLog.action(bot, "gather_dig_approach",
                "to", tree.getX() + "," + tree.getY() + "," + tree.getZ(), "why", why);
        targetPos = tree.immutable();
        lastGotoTarget = targetPos;
        treeDigTried = true;
        gotoFailStreak = 0;
        phase = Phase.GOTO;
        return true;
    }

    private void goToTarget(AIPlayerEntity bot) {
        if (targetPos == null || !isHarvestBlock(bot, targetPos)) {
            invalidateConsumedResource(bot);
            phase = Phase.SURVEY;
            return;
        }
        // Drop the current target the instant it touches water (fixes the wheat-chain
        // grass-clearing drowning guard_drowning): grass grows densely along waterside shallows,
        // and GOTO pathing toward waterside grass can walk the bot into deep water over its
        // head — NavSafetyNet only steers movement and doesn't kill the task, producing a "safety
        // net pulls it ashore for one tick / pathing shoves it back into the water the next tick"
        // livelock, until air<100 and SurvivalGuard cuts the task. Same pattern as
        // roamMove/exploreMove: stop moving the instant water is touched + blacklist that grass
        // (prevents SURVEY from immediately relocking onto the same underwater grass and
        // ping-ponging) + return to SURVEY, letting NavSafetyNet pull it ashore before reselecting
        // on dry land (real_wood doesn't drown: its trees are on dry land).
        if (bot.isInWater()) {
            bot.getActionPack().stopAll();
            EpisodeMemory.INSTANCE.exclude(bot.getUUID(), targetPos,
                    bot.level().getServer().getTickCount(), EpisodeMemory.TTL_UNREACHABLE);
            BotLog.action(bot, "gather_goto_water_bail",
                    "pos", targetPos.getX() + "," + targetPos.getY() + "," + targetPos.getZ());
            targetPos = null;
            phase = Phase.SURVEY;
            return;
        }
        if (HarvestCore.canReach(bot, targetPos)) {
            bot.getActionPack().stopAll();
            startHarvest(bot);
            return;
        }
        // R1 airborne/stuck fallback (fixes gather_timeout on plains): while pathing toward a
        // tree, if pathExecutor gets deadlocked (it still "thinks" it's moving → not idle), the
        // whole self-healing chain below (the isPathExecutorIdle branch) becomes dead code and
        // the bot's coordinates sit frozen until the 6001t timeout (observed: GOTO with
        // on_ground=false, zero displacement over 160 seconds). Watchdog: if the coordinate
        // hasn't moved for GOTO_STUCK_LIMIT ticks in a row → force stopAll to clear the executor +
        // blacklist that tree + return to SURVEY to reselect. Normal pathing keeps the coordinate
        // changing, and dig-approach keeps making progress too, so neither triggers this (the 4s
        // threshold is far longer than a single block's mining time).
        BlockPos hereNow = bot.blockPosition();
        if (hereNow.equals(gotoStuckPos)) {
            if (elapsed - gotoStuckTick >= GOTO_STUCK_LIMIT) {
                bot.getActionPack().stopAll();
                EpisodeMemory.INSTANCE.exclude(bot.getUUID(), targetPos,
                        bot.level().getServer().getTickCount(), EpisodeMemory.TTL_UNREACHABLE);
                BotLog.action(bot, "gather_goto_unstick",
                        "pos", hereNow.toShortString(), "on_ground", bot.onGround());
                gotoStuckPos = null;
                phase = Phase.SURVEY;
                return;
            }
        } else {
            gotoStuckPos = hereNow.immutable();
            gotoStuckTick = elapsed;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            if (countBrokenBlocks) {
                // The survey chooser has already proved an ordinary walking path. If it turns
                // out to be stale, reject this block and try another nearby one; never tunnel
                // toward an exact-break target.
                bot.getActionPack().stopAll();
                EpisodeMemory.INSTANCE.exclude(bot.getUUID(), targetPos,
                        bot.level().getServer().getTickCount(), EpisodeMemory.TTL_UNREACHABLE);
                BotLog.action(bot, name() + "_target_unreachable", "pos", targetPos.toShortString(),
                        "block", exactBreakTargetLabel);
                targetPos = null;
                phase = Phase.SURVEY;
                return;
            }
            if (!targetPos.equals(lastGotoTarget)) {
                gotoFailStreak = 0; // A new target — restart the failure count
                treeDigTried = false;
                lastGotoTarget = targetPos;
            }
            // Can't reach a cliff-face/below-grade tree (plain walking gives GOAL_UNREACHABLE —
            // the #1 obstacle behind 67% of real-diamond-run failures): escalate to
            // dig-approach — startDigPathTo to tunnel down/through, just like reaching buried ore
            // while mining, rather than immediately blacklisting and switching trees (there's
            // nothing to switch to when every tree is on a cliff). Each target is escalated only
            // once; only blacklisted (and revived after its TTL) if dig-approach also can't reach
            // it. This is the key to being able to gather wood on any terrain.
            if (!treeDigTried) {
                treeDigTried = true;
                ActionResult dig = bot.getActionPack().startDigPathTo(targetPos);
                BotLog.action(bot, "gather_dig_approach",
                        "to", targetPos.getX() + "," + targetPos.getY() + "," + targetPos.getZ(),
                        "ok", !dig.isFailed());
                if (!dig.isFailed()) {
                    return; // Dig-approach has been started; stay in GOTO and wait for it to tunnel through
                }
            }
            // Neither walking nor dig-approach can reach it → blacklist and switch trees
            // (survey's posFilter won't relock onto it; fixes the ping-pong infinite loop).
            if (++gotoFailStreak >= GOTO_FAIL_EXCLUDE) {
                EpisodeMemory.INSTANCE.exclude(bot.getUUID(), targetPos, bot.level().getServer().getTickCount(), EpisodeMemory.TTL_UNREACHABLE);
                BotLog.action(bot, "gather_target_excluded",
                        "pos", targetPos.getX() + "," + targetPos.getY() + "," + targetPos.getZ(),
                        "fails", gotoFailStreak);
            }
            phase = Phase.SURVEY;
        }
    }

    private void harvest(AIPlayerEntity bot) {
        if (targetPos == null || !isHarvestBlock(bot, targetPos)) {
            if (countBrokenBlocks && targetPos != null) {
                recordBrokenBlock(bot);
                return;
            }
            breaksCount++; // Auditable gather logging: this task's own break (see gather_summary)
            invalidateConsumedResource(bot);
            bot.getActionPack().stopAll(); // Stop and stand still after felling it — don't let movement momentum carry the bot away from the drop (observed drifting away from the tree's position after chopping and then failing to pick up the drop)
            pickupTicks = probabilisticDrop ? 30 : 120; // Probabilistic-drop resources (seeds/berries) drop right at the bot's feet and are picked up quickly, so wait less
            phase = Phase.PICKUP;
            return;
        }
        if (elapsed - harvestStartedTick > HARVEST_LIMIT) {
            bot.getActionPack().stopAll();
            EpisodeMemory.INSTANCE.exclude(bot.getUUID(), targetPos,
                    bot.level().getServer().getTickCount(), EpisodeMemory.TTL_UNREACHABLE);
            BotLog.action(bot, "gather_harvest_timeout", "pos", targetPos.toShortString());
            targetPos = null;
            clearPickupLedger();
            resetSurveyWatchdog();
            phase = Phase.SURVEY;
            return;
        }
        if (bot.getActionPack().isMiningIdle() && elapsed % 200 == 0) {
            // A controller retry is part of the same atomic attempt. Calling startHarvest() here
            // used to renew harvestStartedTick every 200 ticks, permanently outrunning the
            // 240-tick deadline whenever the target had become out of reach.
            HarvestCore.startMining(bot, targetPos);
        }
    }

    private void recordBrokenBlock(AIPlayerEntity bot) {
        BlockPos cleared = targetPos;
        invalidateConsumedResource(bot);
        // A missing target is normally the bot's completed mining controller.  Do not credit a
        // block that disappeared while the bot was no longer close enough to be the breaker.
        // This keeps a nearby player's unrelated break from silently satisfying the quota.
        if (cleared == null || cleared.distSqr(bot.blockPosition()) > 36.0D) {
            BotLog.action(bot, name() + "_target_unverified",
                    "pos", cleared == null ? "unknown" : cleared.toShortString(),
                    "block", exactBreakTargetLabel);
            clearPickupLedger();
            resetSurveyWatchdog();
            phase = Phase.SURVEY;
            return;
        }
        countSoFar++;
        breaksCount++; // Auditable gather logging: exact-break mode counts breaks 1:1 with countSoFar (see gather_summary)
        BotLog.action(bot, "exact_block_broken",
                "task", name(), "block", exactBreakTargetLabel,
                "count", countSoFar + "/" + targetCount,
                "pos", cleared == null ? "unknown" : cleared.toShortString());
        clearPickupLedger();
        resetSurveyWatchdog();
        if (countSoFar >= targetCount) {
            phase = Phase.DONE;
        } else if (bootstrapActive && handLogBreakInFlight) {
            // Log bootstrap by hand: exact-break mode has no PICKUP phase (drops normally do not
            // matter), but the axe is crafted from these very logs -- without collecting them
            // GatherToolPolicy.bootstrapLogsByHand would never reach zero and every remaining log
            // would be broken with the bare hand.
            bootstrapPickupBaseline = countAccepted(bot);
            bootstrapPickupOrigin = cleared.immutable();
            bootstrapOriginSweep = new KnownCellPickupSweep(bootstrapPickupOrigin);
            bootstrapOriginSweepLogged = 0;
            bootstrapOriginApproachLogged = false;
            bootstrapPickupTicks = BOOTSTRAP_PICKUP_TICKS;
            bot.getActionPack().stopAll();
            phase = Phase.BOOTSTRAP_PICKUP;
        } else {
            phase = Phase.SURVEY;
        }
    }

    /**
     * Exact-break log bootstrap only: collects the drop of the log just broken by hand (never
     * touches {@code countSoFar}, which counts physical breaks here), then returns to SURVEY, where
     * the next {@link #startHarvest} either breaks another bootstrap log or detours to craft the axe.
     * A drop that cannot be collected within the window is simply given up on (the next break
     * tries again); the exact-break quota is bounded regardless.
     */
    private void bootstrapPickup(AIPlayerEntity bot) {
        boolean collected = countAccepted(bot) > bootstrapPickupBaseline;
        if (!collected && --bootstrapPickupTicks > 0) {
            var visibleDrop = HarvestCore.nearestDropAnyOf(bot, acceptItems, 8.0D);
            boolean chasingVisibleDrop = false;
            if (visibleDrop.isPresent()
                    && bot.getActionPack().isPathExecutorIdle() && bot.getActionPack().isWalkToIdle()
                    && bot.getActionPack().stepIdle()) {
                chasingVisibleDrop = HarvestCore.approachDropPhysically(bot, visibleDrop.get());
            }
            // The drop can pop behind the logs that are still standing (out of line of sight, so
            // "not observable" and never chased). Our own break cell is a factual coordinate, exactly as
            // in pickup(): walk (never dig or pillar) to where the drop must be instead of giving up
            // and breaking another log by hand for want of the one that was just lost.
            // The sweep starts with that walk and, when the drop is still not in reach (it came to rest a
            // cell or two away, hidden behind the standing logs), keeps walking the standable cells around
            // the break cell (see KnownCellPickupSweep).
            // It runs only while no observed drop is being approached: a supported observed drop is
            // chased by approachDropPhysically above and the sweep must never pull the bot away from it.
            if (!chasingVisibleDrop && bootstrapPickupOrigin != null && bootstrapOriginSweep != null
                    && bot.getActionPack().isPathExecutorIdle() && bot.getActionPack().isWalkToIdle()
                    && bot.getActionPack().stepIdle()) {
                KnownCellPickupSweep.Step swept = bootstrapOriginSweep.step(bot);
                if (swept == KnownCellPickupSweep.Step.MOVING && !bootstrapOriginApproachLogged) {
                    bootstrapOriginApproachLogged = true;
                    BotLog.action(bot, "gather_bootstrap_origin_approach",
                            "origin", bootstrapPickupOrigin.toShortString(),
                            "from", bot.blockPosition().toShortString());
                }
                if (bootstrapOriginSweep.cellsVisited() > bootstrapOriginSweepLogged) {
                    bootstrapOriginSweepLogged = bootstrapOriginSweep.cellsVisited();
                    BotLog.action(bot, "gather_bootstrap_origin_sweep",
                            "origin", bootstrapPickupOrigin.toShortString(),
                            "cells", bootstrapOriginSweepLogged,
                            "at", bot.blockPosition().toShortString());
                }
            }
            return;
        }
        if (!collected) {
            BotLog.action(bot, name() + "_bootstrap_pickup_miss", "block", exactBreakTargetLabel);
        }
        bootstrapPickupOrigin = null;
        bootstrapOriginSweep = null;
        bot.getActionPack().stopAll();
        resetSurveyWatchdog();
        phase = Phase.SURVEY;
    }

    private int defaultSearchRadius() {
        return countBrokenBlocks ? EXACT_BREAK_SEARCH_RADIUS : SEARCH_RADIUS;
    }

    /**
     * Exact break can target any thin/non-colliding block.  Ordinary seed/forage gathering also
     * includes grass and berry bushes, which are visible as cells but may have no collider face
     * for the generic block-ray query to hit.
     */
    private boolean needsCellObservation() {
        return countBrokenBlocks
                || harvestBlocks.contains(Blocks.SHORT_GRASS)
                || harvestBlocks.contains(Blocks.TALL_GRASS)
                || harvestBlocks.contains(Blocks.FERN)
                || harvestBlocks.contains(Blocks.LARGE_FERN)
                || harvestBlocks.contains(Blocks.SWEET_BERRY_BUSH);
    }

    private String exactBreakNoNearbyReason() {
        return "clear_grass".equals(name()) ? "no_grass_nearby" : "no_matching_block_nearby";
    }

    private void pickup(AIPlayerEntity bot) {
        refreshCountSoFar(bot);
        long pickupStatNow = pickedUpAccepted(bot);
        if (confirmPickup(bot, pickupStatNow)) {
            return;
        }
        pickupTicks--;
        var visibleDrop = HarvestCore.nearestDropAnyOf(bot, acceptItems, 8.0D);
        boolean chasingVisibleDrop = false;
        if (visibleDrop.isPresent()) {
            if (bot.getActionPack().isPathExecutorIdle() && bot.getActionPack().isWalkToIdle()
                    && bot.getActionPack().stepIdle()) {
                chasingVisibleDrop = HarvestCore.approachDropPhysically(bot, visibleDrop.get());
            }
        }
        // A freshly spawned visible ItemEntity can remain airborne, making entity-based pickup
        // navigation deliberately wait.  The break coordinate is already a factual, durable
        // ledger entry, so if the entity route did not start any movement, walk to that exact
        // cell now instead of standing four blocks away until the whole pickup window expires.
        // The sweep runs only while no observed drop is being approached (approachDropPhysically above
        // returned false or there is no visible drop): it must never pull the bot away from a chased drop.
        if (!chasingVisibleDrop && pickupOrigin != null
                && bot.getActionPack().isPathExecutorIdle()
                && bot.getActionPack().isWalkToIdle() && bot.getActionPack().stepIdle()) {
            // The sweep starts with that walk and, when the drop is still not in reach (it came to rest
            // a cell or two away, hidden behind standing blocks), keeps walking the standable cells
            // around the break cell instead of nudging in one spot until the window expires.
            if (pickupOriginSweep == null || !pickupOriginSweep.origin().equals(pickupOrigin)) {
                pickupOriginSweep = new KnownCellPickupSweep(pickupOrigin);
                pickupOriginSweepLogged = 0;
            }
            KnownCellPickupSweep.Step swept = pickupOriginSweep.step(bot);
            if (swept == KnownCellPickupSweep.Step.MOVING && !pickupOriginApproachLogged) {
                pickupOriginApproachLogged = true;
                BotLog.action(bot, "gather_pickup_origin_approach",
                        "origin", pickupOrigin.toShortString(),
                        "from", bot.blockPosition().toShortString());
            }
            if (pickupOriginSweep.cellsVisited() > pickupOriginSweepLogged) {
                pickupOriginSweepLogged = pickupOriginSweep.cellsVisited();
                BotLog.action(bot, "gather_pickup_origin_sweep",
                        "origin", pickupOrigin.toShortString(),
                        "cells", pickupOriginSweepLogged,
                        "at", bot.blockPosition().toShortString());
            }
        }
        if (pickupTicks <= 0) {
            if (!pickupSweepAttempted && visibleDrop.isPresent()) {
                pickupSweepAttempted = true;
                HarvestCore.sweepPickupAnyOf(bot, acceptItems, 8);
                pickupTicks = 60;
                return;
            }
            refreshCountSoFar(bot);
            pickupStatNow = pickedUpAccepted(bot);
            if (confirmPickup(bot, pickupStatNow)) {
                return;
            }
            // A prior tick's approachDropPhysically nudge (or the sweep attempt above) can leave
            // sneaking held; every branch below hands off to a different phase without going
            // through confirmPickup's own cleanup, so clear it once here instead of in each one.
            bot.getActionPack().stopAll();
            if (probabilisticDrop) {
                // Probabilistic drop (clearing grass for seeds / harvesting berry bushes): not
                // dropping anything this time is normal, not a "failed pickup" — return to
                // SURVEY and gather the next one; rely on survey finding no block (→ roam) and
                // on gather_timeout (6000t) as fallbacks, avoiding a false pickup_miss timeout
                // (observed: clearing grass for seeds hit pickup_timeout and failed after
                // gathering just 1).
                clearPickupLedger();
                resetSurveyWatchdog();
                phase = Phase.SURVEY;
            } else if (++pickupMisses <= MAX_PICKUP_MISSES) {
                // Failed to pick up (drop stuck high in leaves / unreachable) → don't fail,
                // return to SURVEY and chop a different one instead. Key fix: removed the old
                // "countSoFar>0" restriction — observed that in a sparse-tree area, failing to
                // pick up the very first tree's drop → countSoFar=0 → immediate fail → the brain
                // replans the same plan → a 19-minute infinite loop that got the bot killed.
                pickupMissesTotal++; // Auditable gather logging: cumulative tally for gather_summary
                BotLog.action(bot, "gather_pickup_miss",
                        "have", countSoFar + "/" + targetCount,
                        "miss", pickupMisses,
                        "origin", pickupOrigin == null ? "unknown" : pickupOrigin.toShortString(),
                        "at", bot.blockPosition().toShortString(),
                        "pickup_stat_delta", Math.max(0L, pickupStatNow - pickupStatBeforeHarvest),
                        "visible_drop", visibleDrop.isPresent());
                clearPickupLedger();
                resetSurveyWatchdog();
                phase = Phase.SURVEY;
            } else {
                clearPickupLedger();
                resetSurveyWatchdog();
                if (roamToNewArea(bot)) {
                    // Several blocks in a row in the same patch failed to be gathered → begin a
                    // bounded observed exploration hop and retry after a new view is reached.
                } else {
                    fail("pickup_timeout");
                }
            }
        }
    }

    /**
     * Confirms ordinary entity pickup independently from net inventory growth. A safety resupply
     * may consume one accepted log while this task is paused, so inventory can remain unchanged
     * even though vanilla collision collected the newly broken block.
     */
    private boolean confirmPickup(AIPlayerEntity bot, long pickupStatNow) {
        boolean inventoryGain = countAccepted(bot) > countBeforeHarvest;
        boolean vanillaPickup = pickupStatNow > pickupStatBeforeHarvest;
        if (!inventoryGain && !vanillaPickup) {
            return false;
        }
        bot.getActionPack().stopAll();
        pickupMisses = 0;
        pickupsCount++; // Auditable gather logging: one confirmed physical pickup (see gather_summary)
        logGatherUnitGains(bot, "pickup", pickupOrigin);
        clearPickupLedger();
        if (countSoFar >= targetCount) {
            phase = Phase.DONE;
        } else {
            // A vanilla pickup can coincide with accepted-item consumption, leaving no quota
            // progress. Re-enter local survey with a fresh regional deadline instead of treating
            // the atomic HARVEST/PICKUP duration as time spent stuck in this area.
            resetSurveyWatchdog();
            phase = Phase.SURVEY;
        }
        return true;
    }

    private void clearPickupLedger() {
        pickupOrigin = null;
        pickupOriginApproachLogged = false;
        pickupOriginSweep = null;
    }

    private void resetSurveyWatchdog() {
        selfStuckCount = countSoFar;
        selfStuckTick = elapsed;
    }

    private void invalidateConsumedResource(AIPlayerEntity bot) {
        if (targetPos == null) {
            return;
        }
        // RESOURCE_FOUND stores the factual block coordinate. Once that block is gone, retaining
        // it sends every later replan back to an empty point (seed 3000 repeatedly burned all eight
        // explore hops on the same harvested tree). Nearby live blocks will be rediscovered normally.
        bot.getActionPack().stopAll();
        invalidateKnownResource(bot, targetPos);
        targetPos = null;
    }

    private boolean waitForDryGround(AIPlayerEntity bot) {
        boolean active = NavSafetyNet.INSTANCE.isWaterRescueActive(bot);
        if (!bot.isInWater() && !active) {
            return false;
        }
        bot.getActionPack().stopAll();
        if (exploreHint != null) {
            excludeExploreHint(bot, "water");
        }
        if (targetPos != null) {
            EpisodeMemory.INSTANCE.exclude(bot.getUUID(), targetPos,
                    bot.level().getServer().getTickCount(), EpisodeMemory.TTL_UNREACHABLE);
        }
        if (roamTarget != null) {
            EpisodeMemory.INSTANCE.exclude(bot.getUUID(), roamTarget,
                    bot.level().getServer().getTickCount(), EpisodeMemory.TTL_UNREACHABLE);
        }
        if (exploreTarget != null) {
            EpisodeMemory.INSTANCE.exclude(bot.getUUID(), exploreTarget,
                    bot.level().getServer().getTickCount(), EpisodeMemory.TTL_UNREACHABLE);
        }
        roamTarget = null;
        exploreTarget = null;
        exploreStart = null;
        explorationProgress.reset();
        targetPos = null;
        searchRadius = SEARCH_RADIUS;
        phase = Phase.SURVEY;
        selfStuckTick = elapsed;
        NavSafetyNet.INSTANCE.requestWaterRescue(bot);
        return true;
    }

    private void invalidateKnownResource(AIPlayerEntity bot, BlockPos pos) {
        for (Block block : harvestBlocks) {
            io.github.zoyluo.minecraftai.memory.KnowledgeBase.INSTANCE.invalidateResource(
                    bot.getUUID(), BuiltInRegistries.BLOCK.getKey(block).toString(), pos);
        }
    }

    private void excludeExploreHint(AIPlayerEntity bot, String reason) {
        if (exploreHint == null) {
            return;
        }
        EpisodeMemory.INSTANCE.exclude(bot.getUUID(), exploreHint,
                bot.level().getServer().getTickCount(), EpisodeMemory.TTL_UNREACHABLE);
        BotLog.action(bot, "gather_known_hint_excluded",
                "pos", exploreHint.toShortString(), "reason", reason);
        exploreHint = null;
    }

    private void deposit(AIPlayerEntity bot) {
        if (stockpileTask == null) {
            bot.getActionPack().stopAll();
            stockpileTask = new StockpileTask(true);
            stockpileTask.start(bot);
        }
        stockpileTask.tick(bot);
        if (stockpileTask.state() == TaskState.COMPLETED) {
            stockpileTask = null;
            phase = Phase.SURVEY;
            return;
        }
        if (stockpileTask.state() == TaskState.FAILED) {
            String reason = stockpileTask.failureReason();
            stockpileTask = null;
            fail(reason == null || reason.isBlank() ? "inventory_full" : reason);
        }
    }

    // Optimal-tool policy (player-requested gather/harvest/break -- mining missions keep their own
    // pickaxe-tier channel policy and are not routed through this class): never break the target
    // with the bare hand or a wrong-category tool when an effective category actually exists. If
    // the bot doesn't already carry one, detour through ENSURE_TOOL to craft the cheapest adequate
    // tier before mining; if it can't be crafted either, the whole task stops instead of falling
    // back to a sub-optimal tool.
    private void startHarvest(AIPlayerEntity bot) {
        handLogBreakInFlight = false;
        var targetState = bot.level().getBlockState(targetPos);
        GatherToolPolicy.Category category =
                GatherToolPolicy.categoryForBreak(targetState, bot, !countBrokenBlocks);
        if (category != GatherToolPolicy.Category.NONE && !GatherToolPolicy.hasTool(bot, category)) {
            // The one relaxation of the strict rule: no axe and none craftable from inventory, so
            // break the minimum number of logs by hand (see GatherToolPolicy.Bootstrap).
            if (category == GatherToolPolicy.Category.AXE && GatherToolPolicy.isLogBootstrapTarget(targetState)) {
                int byHand = GatherToolPolicy.bootstrapLogsByHand(bot);
                if (byHand > 0) {
                    if (!bootstrapActive) {
                        bootstrapActive = true;
                        bootstrapExcluded += byHand;
                        BotLog.action(bot, "gather_tool_bootstrap",
                                "category", GatherToolPolicy.token(category),
                                "logs_needed", byHand,
                                "logs_by_hand", byHand,
                                "pos", targetPos.toShortString());
                    }
                    handLogBreakInFlight = true;
                    doStartHarvest(bot);
                    return;
                }
            }
            bot.getActionPack().stopAll();
            pendingToolCategory = category;
            toolCraftCandidateIndex = 0;
            toolCraftTask = null;
            lastToolCraftFailure = null;
            BotLog.action(bot, "gather_tool_missing",
                    "category", GatherToolPolicy.token(category),
                    "pos", targetPos.toShortString());
            phase = Phase.ENSURE_TOOL;
            return;
        }
        if (bootstrapActive) {
            // An axe (or equivalent) arrived by another route mid-bootstrap (picked up, given,
            // crafted elsewhere): the earmarked hand-broken logs are no longer reserved for a
            // craft, so stop discounting them or absolute-quota progress is under-reported.
            endBootstrap(bot);
        }
        doStartHarvest(bot);
    }

    private void doStartHarvest(AIPlayerEntity bot) {
        countBeforeHarvest = countAccepted(bot);
        pickupSweepAttempted = false;
        harvestStartedTick = elapsed;
        pickupOrigin = targetPos == null ? null : targetPos.immutable();
        pickupStatBeforeHarvest = pickedUpAccepted(bot);
        pickupOriginApproachLogged = false;
        pickupOriginSweep = null;
        HarvestCore.startMining(bot, targetPos);
        phase = Phase.HARVEST;
    }

    /**
     * Crafts the cheapest adequate tool of {@link #pendingToolCategory} (wood tier, then stone)
     * from the bot's own inventory, reusing CraftTask's existing crafting-table/sticks/planks
     * chain rather than reimplementing it. Re-checks inventory at the top of every tick so a tool
     * picked up mid-craft (or produced by the craft itself) is noticed immediately. Re-surveys
     * afterward instead of resuming GOTO/HARVEST directly: crafting may have walked the bot to a
     * table, so targetPos/position are no longer assumed valid.
     */
    private void ensureTool(AIPlayerEntity bot) {
        if (GatherToolPolicy.hasTool(bot, pendingToolCategory)) {
            endBootstrap(bot);
            pendingToolCategory = null;
            toolCraftTask = null;
            lastToolCraftFailure = null;
            searchRadius = defaultSearchRadius();
            resetSurveyWatchdog();
            phase = Phase.SURVEY;
            return;
        }
        Item[] candidates = GatherToolPolicy.craftCandidates(pendingToolCategory);
        if (toolCraftTask == null) {
            if (toolCraftCandidateIndex >= candidates.length) {
                String category = GatherToolPolicy.token(pendingToolCategory);
                String reason = "missing_tool:" + category
                        + (lastToolCraftFailure == null || lastToolCraftFailure.isBlank() ? "" : " " + lastToolCraftFailure);
                pendingToolCategory = null;
                fail(reason);
                return;
            }
            Item candidate = candidates[toolCraftCandidateIndex];
            toolCraftTask = new CraftTask(candidate, 1);
            toolCraftTask.start(bot);
            BotLog.action(bot, "gather_tool_craft_attempt",
                    "category", GatherToolPolicy.token(pendingToolCategory),
                    "item", BuiltInRegistries.ITEM.getKey(candidate).toString());
        }
        toolCraftTask.tick(bot);
        if (toolCraftTask.state() == TaskState.FAILED) {
            lastToolCraftFailure = toolCraftTask.failureReason();
            toolCraftTask = null;
            toolCraftCandidateIndex++;
        } else if (toolCraftTask.state() == TaskState.COMPLETED) {
            toolCraftTask = null;
            // Re-checked at the top of the next tick; hasTool() is now expected to be true.
        }
    }

    private int countAccepted(AIPlayerEntity bot) {
        return acceptedInventoryCount(bot, targetItem);
    }

    /**
     * Updates progress without letting pre-existing inventory satisfy an explicit player request.
     * Inventory delta covers forced pickup; the vanilla picked-up statistic covers a new item that
     * a safety action consumes before the next task tick.  Both sources are monotonic within this
     * task, so already-carried logs can never make "gather 3 logs" complete at startup.
     */
    private void refreshCountSoFar(AIPlayerEntity bot) {
        int accepted = countAccepted(bot);
        if (!countNewItems) {
            // While bootstrapping, the logs earmarked for the axe craft are not part of the quota.
            countSoFar = Math.max(0, accepted - (bootstrapActive ? bootstrapExcluded : 0));
            return;
        }
        int observedNewItems = Math.max(0, rawNewItems(bot) - bootstrapExcluded);
        countSoFar = Math.max(countSoFar, observedNewItems);
    }

    /** Accepted items this task has received so far (monotonic: max of inventory delta and picked-up stat delta). */
    private int rawNewItems(AIPlayerEntity bot) {
        int inventoryDelta = Math.max(0, countAccepted(bot) - acceptedInventoryAtStart);
        long pickupDelta = Math.max(0L, pickedUpAccepted(bot) - pickedUpAtStart);
        return Math.max(inventoryDelta, pickupDelta >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) pickupDelta);
    }

    /**
     * Ends the log bootstrap. The hand-broken logs earmarked for the axe craft stop being discounted in an
     * absolute quota (they are consumed or counted like any other log). In a new-items quota only logs
     * that really arrived while bootstrapping stay excluded: when an axe shows up mid-bootstrap (given, picked
     * up, crafted elsewhere) fewer logs than the planned minimum were collected, and subtracting the planned
     * number would under-report every later log; the crafted-axe path collected them all, so nothing changes.
     */
    private void endBootstrap(AIPlayerEntity bot) {
        if (countNewItems) {
            bootstrapExcluded = Math.min(bootstrapExcluded, rawNewItems(bot));
        } else {
            bootstrapExcluded = 0;
        }
        bootstrapActive = false;
        handLogBreakInFlight = false;
    }

    private long pickedUpAccepted(AIPlayerEntity bot) {
        long count = 0L;
        for (Item item : acceptItems) {
            count += bot.getStats().getValue(Stats.ITEM_PICKED_UP, item);
        }
        return count;
    }

    /** Family-aware inventory count used by legacy absolute-quota planning paths. */
    public static int acceptedInventoryCount(AIPlayerEntity bot, Item targetItem) {
        return HarvestCore.countInventoryItems(bot, acceptItemsFor(targetItem));
    }

    private boolean isHarvestBlock(AIPlayerEntity bot, BlockPos pos) {
        return harvestBlocks.contains(bot.level().getBlockState(pos).getBlock());
    }

    // Forage-food family: berries / melon slices (both are wild, ready-to-take, directly edible);
    // gathering either counts toward the quota (whichever is nearer).
    private static final Set<Item> FORAGE_FOODS = Set.of(Items.SWEET_BERRIES, Items.MELON_SLICE);

    private static Set<Item> acceptItemsFor(Item item) {
        // Logs: accept any tree species (downstream recipes for planks/sticks/tools all accept
        // any member of the planks family).
        if (RecipeRegistry.LOGS.contains(item)) {
            return Set.copyOf(RecipeRegistry.LOGS);
        }
        if (FORAGE_FOODS.contains(item)) {
            return FORAGE_FOODS; // Foraging: accept any wild food — whichever kind is gathered counts
        }
        return Set.of(item);
    }

    private static Set<Block> harvestBlocksFor(Set<Item> items) {
        if (items.contains(Items.WHEAT_SEEDS)) {
            // Clearing grass for wheat seeds: breaking short grass/tall grass/ferns has a chance
            // to drop wheat_seeds (an early source of seeds for farming).
            return Set.of(Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN);
        }
        if (items.contains(Items.SWEET_BERRIES) || items.contains(Items.MELON_SLICE)) {
            // Foraging: break a sweet berry bush (mature ones drop berries) / a melon block
            // (drops melon slices) to get ready-to-take wild food.
            return Set.of(Blocks.SWEET_BERRY_BUSH, Blocks.MELON);
        }
        LinkedHashSet<Block> blocks = new LinkedHashSet<>();
        for (Item item : items) {
            blocks.addAll(harvestBlocksForItem(item));
        }
        return Set.copyOf(blocks);
    }

    /**
     * "Gather X" means break a block that DROPS X -- X need not be the full name of the block at
     * rest in natural generation (dirt is dropped by dirt, grass_block, podzol and mycelium alike;
     * see RuntimeDropIndex). The runtime drop index (built from the game's own loot tables, same
     * lifecycle as RuntimeRecipeIndex) is the primary source of truth once the server has started;
     * it deliberately excludes player-infrastructure blocks (farmland/dirt_path) even though they
     * too drop dirt. Before the index is ready (unit tests / very early startup) this falls back to
     * a minimal legacy special case, then to the item's own block (its BlockItem), then finally to
     * a probabilistic drop source (e.g. gravel -> flint) so there is still somewhere to look.
     */
    private static Set<Block> harvestBlocksForItem(Item item) {
        var deterministic = io.github.zoyluo.minecraftai.loot.RuntimeDropIndex.deterministicSourcesFor(item);
        if (deterministic.isPresent()) {
            if (!deterministic.get().isEmpty()) {
                return deterministic.get();
            }
        } else {
            Block legacy = legacyHarvestBlockFor(item);
            if (legacy != null) {
                return Set.of(legacy);
            }
        }
        if (item instanceof BlockItem blockItem) {
            return Set.of(blockItem.getBlock());
        }
        var probabilistic = io.github.zoyluo.minecraftai.loot.RuntimeDropIndex.probabilisticSourcesFor(item);
        return probabilistic.orElseGet(Set::of);
    }

    /** Pre-index-ready fallback only (see {@link #harvestBlocksForItem}); the real index covers this identically once built. */
    private static Block legacyHarvestBlockFor(Item item) {
        if (item == Items.COBBLESTONE) {
            return Blocks.STONE;
        }
        return null;
    }

    // ---- Auditable gather logging (docs/LOGGING.md "Auditing a gather") ----------------------
    // Everything below is observation only: it reads state already needed for progress tracking
    // and never feeds back into phase/countSoFar/targetPos decisions.

    /**
     * Emits one gather_unit line per accepted item whose inventory count increased since the
     * last time this task looked, then rebases the baseline to the new count. {@code source} is
     * "pickup" when this call follows a confirmed physical pickup of a block this task broke
     * ({@code attributedPos} is that block's position, included in the line), or "unattributed"
     * otherwise (e.g. a player handed the bot an item mid-task). Does nothing beyond one
     * enabled() check when the ACTION log category is off, so a quiet run pays for no extra
     * inventory scanning.
     */
    private void logGatherUnitGains(AIPlayerEntity bot, String source, BlockPos attributedPos) {
        if (!BotLogWriter.INSTANCE.enabled(LogCategory.ACTION, Level.INFO)) {
            return;
        }
        boolean pickup = "pickup".equals(source);
        Map<Item, Integer> current = currentAcceptedCounts(bot);
        for (Item item : acceptItems) {
            int now = current.getOrDefault(item, 0);
            int previous = lastLoggedItemCounts.getOrDefault(item, 0);
            lastLoggedItemCounts.put(item, now);
            if (now <= previous) {
                continue;
            }
            int delta = now - previous;
            gainedTotal += delta;
            if (pickup) {
                BotLog.action(bot, "gather_unit",
                        "item", BuiltInRegistries.ITEM.getKey(item).toString(),
                        "delta", delta,
                        "total", countSoFar,
                        "target", targetCount,
                        "source", source,
                        "pos", attributedPos == null ? "unknown" : attributedPos.toShortString());
            } else {
                unattributedGains += delta;
                BotLog.action(bot, "gather_unit",
                        "item", BuiltInRegistries.ITEM.getKey(item).toString(),
                        "delta", delta,
                        "total", countSoFar,
                        "target", targetCount,
                        "source", source);
            }
        }
    }

    /** One inventory pass (main stacks + offhand), grouped by item, restricted to {@link #acceptItems}. */
    private Map<Item, Integer> currentAcceptedCounts(AIPlayerEntity bot) {
        Map<Item, Integer> counts = new HashMap<>();
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty() && acceptItems.contains(stack.getItem())) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        ItemStack offhand = bot.getItemBySlot(EquipmentSlot.OFFHAND);
        if (!offhand.isEmpty() && acceptItems.contains(offhand.getItem())) {
            counts.merge(offhand.getItem(), offhand.getCount(), Integer::sum);
        }
        return counts;
    }

    /**
     * The most this task's target family can drop from a single broken block without Fortune
     * (strict_survival never grants it): 1 for ordinary 1:1 drops (logs, seeds-from-grass,
     * cobblestone-from-stone), a conservative upper bound for melon/berry-bush multi-drops.
     */
    private int maxDropsPerBrokenBlock() {
        if (harvestBlocks.contains(Blocks.MELON) || harvestBlocks.contains(Blocks.SWEET_BERRY_BUSH)) {
            return 9;
        }
        return 1;
    }

    /**
     * Exactly one gather_summary line whenever this task ends, for any reason (complete, fail,
     * abort, cancel) -- see the {@link #complete()}/{@link #fail(String)}/{@link
     * #onAbort(AIPlayerEntity)} overrides below. {@code bot} may be null in principle (those
     * overrides run before any tick in no known path); logging is simply skipped rather than
     * risking a null-bot inventory read.
     */
    private void logGatherSummary(AIPlayerEntity bot, String outcome) {
        if (summaryLogged || bot == null) {
            return;
        }
        summaryLogged = true;
        String itemLabel = countBrokenBlocks ? exactBreakTargetLabel : BuiltInRegistries.ITEM.getKey(targetItem).toString();
        int baseline = countBrokenBlocks ? 0 : acceptedInventoryAtStart;
        int finalCount = countBrokenBlocks ? countSoFar : countAccepted(bot);
        CapabilityTally.Snapshot decisions = CapabilityTally.INSTANCE.snapshot(bot.getUUID());
        boolean consistent = GatherConsistency.isConsistent(gainedTotal, breaksCount, maxDropsPerBrokenBlock(),
                unattributedGains);
        BotLog.action(bot, "gather_summary",
                "item", itemLabel,
                "target", targetCount,
                "baseline", baseline,
                "final", finalCount,
                "gained", gainedTotal,
                "breaks", breaksCount,
                "pickups", pickupsCount,
                "pickup_misses", pickupMissesTotal,
                "unattributed_gains", unattributedGains,
                "capability_denials", decisions.denied(),
                "elapsed_ticks", elapsed,
                "outcome", outcome,
                "consistent", consistent);
    }

    @Override
    protected void complete() {
        logGatherSummary(currentTickBot, "complete");
        super.complete();
    }

    @Override
    protected void fail(String reason) {
        logGatherSummary(currentTickBot, reason);
        super.fail(reason);
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        if (miningExploration != null) {
            miningExploration.pause(bot);
            return;
        }
        super.onPause(bot);
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        // abort()/cancel() have already set state+failureReason by the time this runs: "aborted"
        // for abort(), the given reason (or "") for cancel(reason).
        if (miningExploration != null) {
            miningExploration.abort(bot);
            miningExploration = null;
        }
        logGatherSummary(bot, failureReason.isBlank() ? "cancelled" : failureReason);
        super.onAbort(bot);
    }
}

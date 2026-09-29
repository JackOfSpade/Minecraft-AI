package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.GatherToolPolicy;
import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.craft.RecipeRegistry;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.BotLogWriter;
import io.github.zoyluo.minecraftai.log.CapabilityTally;
import io.github.zoyluo.minecraftai.log.GatherConsistency;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.mining.OreProspector;
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
    // F1: when no resource is found nearby (16 blocks), automatically widen the search radius and
    // look farther (32→48), instead of failing immediately and handing back to the brain to flail.
    private static final int MAX_SEARCH_RADIUS = 48;
    private static final int SEARCH_DOWN = 6;
    private static final int SEARCH_UP = 12;
    private static final int LARGE_SCAN_THROTTLE_TICKS = 10; // Throttle large-radius scans to protect TPS
    private static final int MAX_PICKUP_MISSES = 5;  // Tolerated consecutive failed-pickup count before roaming to a new patch
    private static final int MAX_ROAMS = 8;          // Stuck-step escape: max roam-to-new-patch hops (shared by tree search/patch switching, ~224 blocks total); fail only after exhausting this
    private static final int ROAM_DISTANCE = 28;     // Horizontal distance covered by each roam
    private static final int SELF_STUCK_LIMIT = 160; // A: self-stuck threshold for gathering (self-managed watchdog; see the isWaiting note)
    private static final int HARVEST_LIMIT = 240;    // Per-block atomic mining cap; the area watchdog must never interrupt HARVEST/PICKUP
    private static final int ROAM_MOVE_LIMIT = 100;  // If roaming hasn't reached its landing point after 5s (target elevated/unreachable, or stuck on a slope) → give up and return to SURVEY
    // Treeless-area fallback: when neither the 48-block radius nor surfacing finds a resource, use
    // an OreProspector palette scan over a wide range (96 blocks) to locate the nearest target
    // block (e.g. logs), then pathfind there. Specifically fixes "treeless plateau / harsh terrain"
    // cases — roam only shifts sideways within the same patch and can't cross a plateau, while
    // prospect can lock directly onto a tree at the foot of the mountain or farther away.
    private static final int PROSPECT_RANGE = 96;
    private static final int PROSPECT_INTERVAL = 40; // Throttle wide-range scans (once per 2s) to protect TPS
    // Wide scans are spread across ticks: the strict-survival prospect used to run as one 180-420 ms server tick
    // (real session, 01:43-01:44). Each tick now advances the scan for at most this long (observability rules
    // unchanged: OreProspector.Scan still rays each candidate before reading its state).
    private static final long SCAN_STEP_BUDGET_NANOS = 2_000_000L;
    private static final int SCAN_STALE_TICKS = 300;     // a scan older than this (task paused, phase left) is dropped, not resumed
    private static final double SCAN_STALE_DISTANCE_SQ = 64.0D; // ... and so is one begun more than 8 blocks from where the bot is now
    private static final double EXPLORE_SCAN_STALE_DISTANCE_SQ = 400.0D; // the en-route scan legitimately trails a walking bot
    // EXPLORE (head out in a direction to search): roam's small 28-block steps ping-pong across 8
    // directions on real terrain with near-zero net displacement, so survey keeps circling the same
    // patch forever (real_wood seed=20260610 observed: looped in place to the 6001t timeout when no
    // tree was found or a found tree was unreachable). EXPLORE extrapolates outward in big
    // 48/40/32-block hops; it heads for a remembered resource point from the knowledge base if one
    // exists, otherwise it does a blind compass search.
    // Shared-root hardening (real_wheat seed 206232996 gather_timeout observed): 4 hops (~190
    // blocks) often can't cross a genuinely treeless area (snow plains/desert/coast/plateau) — and
    // the server's load window (view/sim-distance=10, ~160 blocks) means explore must physically
    // walk there to trigger new chunk loading (prospect only scans already-loaded chunks, so
    // widening its range is useless). 8 hops (~380 blocks) are needed to actually cross into
    // another biome and find a tree.
    private static final int EXPLORE_MAX_HOPS = 8;
    private static final double[] EXPLORE_HOP_DISTANCES = {48.0D, 40.0D, 32.0D};
    private static final int[] EXPLORE_DEFLECTIONS_DEG = {0, 45, -45, 90, -90}; // Deflection-angle sequence: straight ahead first, then fan out left/right
    private static final int EXPLORE_MOVE_LIMIT = 300;   // If a single hop hasn't arrived after 15s → abandon the hop and return to SURVEY
    private static final int EXPLORE_SCAN_INTERVAL = 20; // Throttle light en-route scans (once per 1s, 16 blocks); stop as soon as a target is seen
    private static final int EXPLORE_PATH_ATTEMPTS = 5;  // Max synchronous A* runs per waypoint selection (prevents a long single-tick stall)
    private static final int KNOWN_RESOURCE_RANGE = 192; // Max distance for heading toward a knowledge-base remembered point
    private static final int GOTO_FAIL_EXCLUDE = 2;      // N consecutive GOTO failures toward the same target → blacklist it in working memory
    private static final int GOTO_STUCK_LIMIT = 80;      // R1: if the coordinate hasn't moved for this long (4s) while GOTO paths toward a tree → assume airborne/stuck and force recovery

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
    private static final int BOOTSTRAP_PICKUP_TICKS = 100;
    private int bootstrapPickupBaseline;
    private int searchRadius = SEARCH_RADIUS;
    private int lastScanTick = -100;
    private int lastProspectTick = -100; // Treeless-area fallback: tick of the last wide-range tree prospect (throttled)
    private OreProspector.Scan prospectScan;  // in-flight budgeted prospect scan (null when none)
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
    // EXPLORE state: hop-count budget (reset whenever something new is gathered), current heading
    // (radians), current hop target, this hop's start tick, memory-point guidance (heads for a
    // knowledge-base hit and retires it if there's nothing there on arrival), "explored since the
    // last find" (gates entries into the RESOURCE_FOUND stream), and the throttle for en-route
    // light scans; the other two fields are for the unreachable blacklist: the previous GOTO
    // target plus a streak counter for repeated failures against the same target.
    private int exploreHops;
    private double exploreHeading;
    private BlockPos exploreTarget;
    private int exploreHopStartTick;
    private BlockPos exploreHint;
    private boolean exploredSinceFind;
    private int lastExploreScanTick = -100;
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
        // (1) self-stuck (no new material gathered within SELF_STUCK_LIMIT → roam to a new patch);
        // (2) roam up to MAX_ROAMS times; (3) gather_timeout (6000t).
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
        stockpileTask = null;
        pickupOrigin = null;
        pickupStatBeforeHarvest = pickedUpAccepted(bot);
        pickupOriginApproachLogged = false;
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
            bot.getActionPack().stopAll();
            clearPickupLedger();
            phase = Phase.DONE;
        }
        int timeout = countBrokenBlocks ? EXACT_BREAK_TIMEOUT : 6000;
        if (elapsed > timeout) {
            fail(countBrokenBlocks ? name() + "_timeout" : "gather_timeout");
            return;
        }
        // Do not re-enter SURVEY/EXPLORE every tick while swimming. That used to burn all eight
        // exploration hops in place before NavSafetyNet's low-air threshold could take control.
        if (waitForDryGround(bot)) {
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
    // gathering. Surfacing uses teleport (which clears fallDistance); if already in the open, it
    // does nothing. This is a fallback beyond "centralized gathering" and rarely triggers.
    private boolean trySurface(AIPlayerEntity bot) {
        var world = bot.level();
        BlockPos feet = bot.blockPosition();
        if (world.canSeeSky(feet)) {
            return false;
        }
        int top = world.getMinY() + world.getHeight();
        for (int dy = 1; feet.getY() + dy < top - 1 && dy <= 80; dy++) {
            BlockPos candidate = feet.above(dy);
            if (Standability.isStandable(world, candidate) && world.canSeeSky(candidate)) {
                boolean moved = io.github.zoyluo.minecraftai.mode.CapabilityRuntime.run(
                        bot, io.github.zoyluo.minecraftai.mode.PrivilegedCapability.EMERGENCY_TELEPORT,
                        "gather_surface", () -> {
                            bot.getActionPack().stopAll();
                            bot.teleportTo(world, candidate.getX() + 0.5D, candidate.getY(), candidate.getZ() + 0.5D,
                                    java.util.Collections.emptySet(), bot.getYRot(), bot.getXRot(), true);
                        });
                if (moved) {
                    BotLog.action(bot, "gather_surfaced",
                            "to", candidate.getX() + "," + candidate.getY() + "," + candidate.getZ());
                }
                return moved;
            }
        }
        return false;
    }

    // Treeless-area fallback: a wide-range palette scan (PROSPECT_RANGE) locates the nearest target
    // block (e.g. logs) and pathfinds to that column's surface landing point; once there, nearby
    // SURVEY (16 blocks) takes over for precise gathering. Throttled (PROSPECT_INTERVAL) to protect TPS.
    // Returns false when this call finds nothing (throttle not elapsed / genuinely no such resource
    // in range), deferring to roam's blind patch-switching fallback (which can walk beyond the scan range).
    private boolean prospectAndApproach(AIPlayerEntity bot) {
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
            prospectScan = OreProspector.begin(bot, PROSPECT_RANGE,
                    state -> harvestBlocks.contains(state.getBlock()),
                    pos -> !EpisodeMemory.INSTANCE.isExcluded(botId, pos, scanServer.getTickCount()));
        }
        if (!prospectScan.step(SCAN_STEP_BUDGET_NANOS)) {
            return true; // still scanning: hold position, survey() resumes this scan first on the next tick
        }
        OreProspector.Scan scan = prospectScan;
        prospectScan = null;
        var world = bot.level();
        BlockPos found = scan.result();
        if (found == null) {
            // Observability: silently returning false can't distinguish "genuinely no such
            // resource within 96 blocks" from "a scanning/blacklist bug" (observed: died in 21
            // ticks with no way to diagnose).
            BotLog.action(bot, "gather_prospect_empty",
                    "item", targetItem, "range", PROSPECT_RANGE,
                    "blacklisted", EpisodeMemory.INSTANCE.excludedCount(botId),
                    "scan_steps", scan.steps(),
                    "scan_max_step_us", scan.maxStepNanos() / 1000L,
                    "scan_total_ms", scan.totalNanos() / 1_000_000L);
            return false; // Does not clear the exclusion (a TTL-backed "genuinely can't get there"); defers to roam's blind patch-switching fallback
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

    // Escape a treeless area (fixes the gather_timeout infinite loop): roam is a local patch
    // switch (28-56 block small steps), which in a large treeless/harsh-terrain area can keep
    // bouncing between the same few points without escaping; explore is a directed long-range
    // escape (160 blocks). Try roam twice first (there might be something nearby, saving
    // pointless wandering); once 2 consecutive attempts haven't escaped (roamCount>=2), switch to
    // [prefer explore for a long-range escape] instead of continuing to ping-pong in place until
    // gather_timeout.
    private boolean escapeBarrenArea(AIPlayerEntity bot) {
        if (roamCount >= 2) {
            return startExplore(bot) || roamToNewArea(bot);
        }
        return roamToNewArea(bot) || startExplore(bot);
    }

    // Stuck-step escape: when several blocks in a row in the same patch can't be gathered → walk
    // to an open-sky surface landing point ROAM_DISTANCE away and retry in a new patch (walked to,
    // never teleported), instead of grinding in place until killed. Up to MAX_ROAMS times; fail to
    // the brain/player only after that.
    private boolean roamToNewArea(AIPlayerEntity bot) {
        if (++roamCount > MAX_ROAMS) {
            return false;
        }
        var world = bot.level();
        BlockPos feet = bot.blockPosition();
        int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        int start = Math.floorMod(roamCount, dirs.length);
        // Adaptive distance: if the full distance doesn't work, halve it and try again (on a
        // mountaintop/cliff edge/surrounded by water, all 8 directions at 28 blocks out may get
        // their pathing rejected — observed 8 consecutive rejections within 21 ticks leading
        // straight to a no_resource death; there's almost always a walkable point closer in, so
        // move there first and expand again next round).
        for (int dist = ROAM_DISTANCE; dist >= ROAM_DISTANCE / 4; dist /= 2) {
            // Trail-avoidance only applies at the full-distance tier: roam prefers unsearched new
            // areas first (fixes blind circling); the reduced-distance tier is a "move somewhere,
            // even if it's close" fallback and stops being picky (when every direction is near
            // the trail, it still has to pick one).
            boolean avoidTrail = dist == ROAM_DISTANCE;
            for (int i = 0; i < dirs.length; i++) {
                int[] d = dirs[(start + i) % dirs.length];
                BlockPos ground = findGroundAt(world, feet.getX() + d[0] * dist, feet.getZ() + d[1] * dist);
                if (ground == null
                        || EpisodeMemory.INSTANCE.isExcluded(
                        bot.getUUID(), ground, bot.level().getServer().getTickCount())
                        || (avoidTrail && EpisodeMemory.INSTANCE.nearTrail(
                        bot.getUUID(), "gather", ground, 10.0D))) {
                    continue;
                }
                // Human-like: walk to the new patch instead of teleport-flashing (observed
                // teleporting mid-tree-chopping looked jarringly unnatural).
                bot.getActionPack().stopAll();
                if (bot.getActionPack().startPathTo(ground).isFailed()) {
                    continue; // Pathing rejected → try a different direction (entering ROAM without checking the result would immediately bail and waste a roam attempt)
                }
                roamTarget = ground;
                searchRadius = SEARCH_RADIUS;
                pickupMisses = 0;
                selfStuckTick = elapsed;
                phase = Phase.ROAM;
                BotLog.action(bot, "gather_roam",
                        "to", ground.getX() + "," + ground.getY() + "," + ground.getZ(),
                        "n", roamCount, "dist", dist);
                return true;
            }
        }
        return false;
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

    // While roaming: walk toward the new patch's landing point; on arrival (within 3 blocks) or
    // if stuck (path executor idle) → return to SURVEY to find a tree in the new patch (SURVEY
    // also picks up any tree spotted along the way).
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

    // EXPLORE hop takeoff: pick a heading (if the knowledge base remembers a same-type resource
    // within 192 blocks → head for that memory point; otherwise do a blind compass search,
    // avoiding the trail just walked), then pick a waypoint 48/40/32 blocks out and set off.
    // Returns false if the hop budget is exhausted or no waypoint can be chosen (survey's fail
    // chain then decides the outcome).
    private boolean startExplore(AIPlayerEntity bot) {
        if (exploreHops >= EXPLORE_MAX_HOPS) {
            return false;
        }
        var world = bot.level();
        BlockPos feet = bot.blockPosition();
        exploreHint = null;
        boolean aimed = false;
        // Memory-guided: the nearest same-type resource point in the semantic knowledge base
        // (persists across sessions) → head straight for it (even if an en-route light scan
        // intercepts something first, that's still a win).
        int now = bot.level().getServer().getTickCount();
        for (Block block : harvestBlocks) {
            var known = io.github.zoyluo.minecraftai.memory.KnowledgeBase.INSTANCE.nearestResource(
                    bot.getUUID(), BuiltInRegistries.BLOCK.getKey(block).toString(), feet, KNOWN_RESOURCE_RANGE,
                    pos -> !EpisodeMemory.INSTANCE.isExcluded(bot.getUUID(), pos, now));
            if (known.isPresent()) {
                exploreHint = known.get().pos();
                exploreHeading = Math.atan2(exploreHint.getZ() + 0.5D - bot.getZ(), exploreHint.getX() + 0.5D - bot.getX());
                aimed = true;
                break;
            }
        }
        if (!aimed) {
            // Blind search: among 8 compass directions (45° steps), take the first heading with
            // "a ground landing point 44 blocks out that isn't within 16 blocks of the recent
            // trail". If none qualify, keep the current heading (default 0 / the previous hop's
            // direction) and let pickExploreWaypoint's deflection-angle fan act as the fallback.
            for (int i = 0; i < 8; i++) {
                double heading = Math.toRadians(i * 45.0D);
                int px = (int) Math.floor(bot.getX() + 44.0D * Math.cos(heading));
                int pz = (int) Math.floor(bot.getZ() + 44.0D * Math.sin(heading));
                BlockPos probe = findGroundAt(world, px, pz);
                if (probe == null || EpisodeMemory.INSTANCE.nearTrail(
                        bot.getUUID(), "gather", probe, 16.0D)) {
                    continue;
                }
                exploreHeading = heading;
                break;
            }
        }
        BlockPos picked = pickExploreWaypoint(bot);
        if (picked == null) {
            exploreHeading += Math.PI / 2.0D; // That heading's whole fan came up empty → rotate 90° and try again
            picked = pickExploreWaypoint(bot);
        }
        if (picked == null) {
            exploreHops++; // Burn one hop from the budget: prevents re-entering every tick when "no point can ever be picked"; the fail chain wraps up once the budget is exhausted
            return false;
        }
        exploreHops++;
        exploreTarget = picked;
        exploreHopStartTick = elapsed;
        exploredSinceFind = true;
        phase = Phase.EXPLORE;
        BotLog.action(bot, "gather_explore_hop",
                "hop", exploreHops,
                "to", picked.getX() + "," + picked.getY() + "," + picked.getZ(),
                "mode", exploreHint == null ? "blind" : "known");
        return true;
    }

    // Waypoint selection (mirrors MoveTask.pickWaypoint's structure): a double loop over heading ±
    // deflection {0,±45,±90} × distance tier {48,40,32}; each candidate is that column's surface
    // landing point and must be a dry column (lake-surface floating cells / shallow-water foot
    // cells are all excluded); memory-guided mode additionally requires the candidate not be
    // 10%+ farther from the memory point than the current distance (prevents the deflection fan
    // from drifting farther and farther off course). The first candidate whose startPathTo
    // doesn't fail is used (a successful pathfind means it has already set off); synchronous A*
    // runs are capped at EXPLORE_PATH_ATTEMPTS to prevent a long single-tick stall.
    private BlockPos pickExploreWaypoint(AIPlayerEntity bot) {
        var world = bot.level();
        double bx = bot.getX();
        double bz = bot.getZ();
        double maxHintDistSq = Double.MAX_VALUE;
        if (exploreHint != null) {
            double maxHintDist = Math.sqrt(exploreHint.distSqr(bot.blockPosition())) * 1.10D;
            maxHintDistSq = maxHintDist * maxHintDist;
        }
        int pathAttempts = 0;
        for (int deg : EXPLORE_DEFLECTIONS_DEG) {
            double phi = exploreHeading + Math.toRadians(deg);
            double cos = Math.cos(phi);
            double sin = Math.sin(phi);
            for (double dist : EXPLORE_HOP_DISTANCES) {
                BlockPos candidate = findGroundAt(world, (int) Math.floor(bx + dist * cos), (int) Math.floor(bz + dist * sin));
                if (candidate == null || !isDryColumn(world, candidate)
                        || EpisodeMemory.INSTANCE.isExcluded(
                        bot.getUUID(), candidate, bot.level().getServer().getTickCount())) {
                    continue;
                }
                if (exploreHint != null && candidate.distSqr(exploreHint) > maxHintDistSq) {
                    continue;
                }
                if (pathAttempts >= EXPLORE_PATH_ATTEMPTS) {
                    return null;
                }
                pathAttempts++;
                bot.getActionPack().stopAll();
                if (!bot.getActionPack().startPathTo(candidate).isFailed()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    // Dry-column check (copied from MoveTask): only counts as "dry" if the candidate foot cell
    // and the 4 cells below it are all free of fluid. On a lake, MOTION_BLOCKING_NO_LEAVES picks
    // up the cell floating above the water surface; in shallow water, the foot cell itself is
    // water — both cases must be excluded, otherwise a waypoint would lead the bot straight into
    // water and turn exploration into a death trap.
    private static boolean isDryColumn(net.minecraft.server.level.ServerLevel world, BlockPos feet) {
        for (int i = 0; i <= 4; i++) {
            if (!world.getFluidState(feet.below(i)).isEmpty()) {
                return false;
            }
        }
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
            bot.getActionPack().stopAll();
            excludeExploreHint(bot, "timeout");
            exploreTarget = null;
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
            exploreScan = OreProspector.begin(bot, 16, state -> harvestBlocks.contains(state.getBlock()), null);
        }
        if (exploreScan != null && exploreScan.step(SCAN_STEP_BUDGET_NANOS)) {
            BlockPos seen = exploreScan.result();
            exploreScan = null;
            if (seen != null) {
                bot.getActionPack().stopAll();
                exploreTarget = null;
                searchRadius = SEARCH_RADIUS;
                exploreScan = null; // every exit from EXPLORE drops its en-route scan
                phase = Phase.SURVEY;
                return;
            }
        }
        // (3) Arrived at the hop waypoint (≤3 blocks): if memory-guided travel got within 16
        // blocks of the memory point without being intercepted by (2), the old intel was stale
        // (nothing there) → retire that resource point, so the next startExplore doesn't head for
        // the same stale intel again; then return to SURVEY (the fail chain keeps exploring
        // outward if there's still nothing).
        if (exploreTarget == null || bot.blockPosition().distSqr(exploreTarget) <= 9.0D) {
            if (exploreHint != null && bot.blockPosition().distSqr(exploreHint) <= 256.0D) {
                invalidateKnownResource(bot, exploreHint);
                exploreHint = null;
            }
            bot.getActionPack().stopAll();
            exploreTarget = null;
            searchRadius = SEARCH_RADIUS;
            exploreScan = null; // every exit from EXPLORE drops its en-route scan
            phase = Phase.SURVEY;
            return;
        }
        // (4) Path broke mid-route (20-tick startup grace period — right after startPathTo the
        // executor may still be idle) → reselect a hop on the same heading; if rotating 90° still
        // can't pick one → return to SURVEY (this hop's EXPLORE_MOVE_LIMIT budget counts
        // continuously across the whole hop, including any reselection).
        if (elapsed - exploreHopStartTick > 20 && bot.getActionPack().isPathExecutorIdle()) {
            BlockPos repick = pickExploreWaypoint(bot);
            if (repick == null) {
                exploreHeading += Math.PI / 2.0D;
                repick = pickExploreWaypoint(bot);
            }
            if (repick == null) {
                bot.getActionPack().stopAll();
                excludeExploreHint(bot, "no_path");
                exploreTarget = null;
                searchRadius = SEARCH_RADIUS;
                exploreScan = null; // every exit from EXPLORE drops its en-route scan
                phase = Phase.SURVEY;
                return;
            }
            exploreTarget = repick;
        }
    }

    private void survey(AIPlayerEntity bot) {
        if (harvestBlocks.isEmpty()) {
            fail("unsupported_resource_type");
            return;
        }
        // A prospect scan spread over several ticks resumes before anything else scans: survey's own
        // throttled radius scans below are a full 120-150 ms each and must not run while one is in flight.
        if (prospectScan != null) {
            if (scanIsStale(bot, prospectScan, SCAN_STALE_DISTANCE_SQ)) {
                prospectScan = null;
            } else {
                if (!prospectAndApproach(bot)) {
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
        int now = bot.level().getServer().getTickCount();
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
            // F1: nothing nearby → automatically widen the search radius and look farther,
            // instead of failing immediately and handing back to the brain to flail / dig
            // bare-handed / ask for help.
            if (searchRadius < MAX_SEARCH_RADIUS) {
                searchRadius = Math.min(MAX_SEARCH_RADIUS, searchRadius * 2);
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
            // Treeless-area fallback: nothing found nearby (48 blocks) even after surfacing → a
            // wide-range palette scan (96 blocks) locates the nearest target block (e.g. logs)
            // and pathfinds there. Specifically fixes treeless plateaus / harsh terrain — blind
            // roam only shifts sideways within the same patch and can't cross a plateau, while
            // prospect can directly locate a tree at the foot of the mountain or farther away.
            if (prospectAndApproach(bot)) {
                return;
            }
            // No tree in this patch or in prospect's scan range (96 blocks) → escape the
            // treeless area: if roam's local patch-switch (28-56 blocks) fails to escape after 2
            // consecutive hops → prefer explore's long-range escape (160 blocks). Fixes "roam
            // repeatedly bouncing between the same points in a treeless/harsh area, explore's
            // budget running out too early, and everything burning down to gather_timeout"
            // (observed in log 160048: gather_roam×16 ping-ponging between 4 points while
            // explore only got 4 hops — not a prospect-throttling issue).
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
                    "hops", exploreHops);
        }
        if (choice.direct()) {
            startHarvest(bot);
            return;
        }
        phase = Phase.GOTO;
        bot.getActionPack().startPathTo(choice.stand());
    }

    private void escapeBarrenAreaOrFail(AIPlayerEntity bot) {
        if (escapeBarrenArea(bot)) {
            surfaceTried = false; // New area — allow the "surface fallback" again
            return;
        }
        // The explore budget is also exhausted (4 hops, ~190 blocks, found nothing) → use a
        // dedicated reason so the brain/player knows it "already went out and searched".
        fail(exploreHops >= EXPLORE_MAX_HOPS ? "no_resource_after_explore" : "no_resource_nearby");
    }

    /** Test hook: true while a budgeted prospect scan is in flight (ProspectScanBudgetGameTests). */
    boolean prospectScanActive() {
        return prospectScan != null;
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
        HarvestCore.forcePickupNearbyAnyOf(bot, acceptItems);
        boolean collected = countAccepted(bot) > bootstrapPickupBaseline;
        if (!collected && --bootstrapPickupTicks > 0) {
            var visibleDrop = HarvestCore.nearestDropAnyOf(bot, acceptItems, 8.0D);
            if (visibleDrop.isPresent()
                    && bot.getActionPack().isPathExecutorIdle() && bot.getActionPack().isWalkToIdle()) {
                HarvestCore.approachDropPhysically(bot, visibleDrop.get());
            }
            return;
        }
        if (!collected) {
            BotLog.action(bot, name() + "_bootstrap_pickup_miss", "block", exactBreakTargetLabel);
        }
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
        HarvestCore.forcePickupNearbyAnyOf(bot, acceptItems);
        refreshCountSoFar(bot);
        long pickupStatNow = pickedUpAccepted(bot);
        if (confirmPickup(bot, pickupStatNow)) {
            return;
        }
        pickupTicks--;
        var visibleDrop = HarvestCore.nearestDropAnyOf(bot, acceptItems, 8.0D);
        if (visibleDrop.isPresent()) {
            if (bot.getActionPack().isPathExecutorIdle() && bot.getActionPack().isWalkToIdle()) {
                HarvestCore.approachDropPhysically(bot, visibleDrop.get());
            }
        }
        // A freshly spawned visible ItemEntity can remain airborne, making entity-based pickup
        // navigation deliberately wait.  The break coordinate is already a factual, durable
        // ledger entry, so if the entity route did not start any movement, walk to that exact
        // cell now instead of standing four blocks away until the whole pickup window expires.
        if (pickupOrigin != null
                && bot.getActionPack().isPathExecutorIdle()
                && bot.getActionPack().isWalkToIdle()) {
            boolean started = HarvestCore.approachKnownPickupCell(bot, pickupOrigin);
            if (started && !pickupOriginApproachLogged) {
                pickupOriginApproachLogged = true;
                BotLog.action(bot, "gather_pickup_origin_approach",
                        "origin", pickupOrigin.toShortString(),
                        "from", bot.blockPosition().toShortString());
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
                    // Several blocks in a row in the same patch failed to be gathered → roam
                    // (walk) to a new patch and retry (roamToNewArea already sets phase=ROAM
                    // internally).
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
                unattributedGains, decisions.forcedPickupsAllowed());
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
                "forced_pickups", decisions.forcedPickupsAllowed(),
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
    protected void onAbort(AIPlayerEntity bot) {
        // abort()/cancel() have already set state+failureReason by the time this runs: "aborted"
        // for abort(), the given reason (or "") for cancel(reason).
        logGatherSummary(bot, failureReason.isBlank() ? "cancelled" : failureReason);
        super.onAbort(bot);
    }
}

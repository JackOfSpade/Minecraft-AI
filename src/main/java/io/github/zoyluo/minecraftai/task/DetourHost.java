package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.mining.assist.DetourPolicy;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistConfig;
import io.github.zoyluo.minecraftai.mining.assist.MissionAssistLedger;
import io.github.zoyluo.minecraftai.mining.assist.ObservedReach;
import io.github.zoyluo.minecraftai.mining.assist.SafeGate;
import io.github.zoyluo.minecraftai.mining.assist.SafeReason;
import io.github.zoyluo.minecraftai.mining.assist.SightingLedger;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Everything the detour engine ({@link OreDigDetourEngine}) and its start selector ({@link DetourStartSelector})
 * need from the world and from the hosting task (mining-assist design 4.14). The production implementation is
 * the private inner class {@code OreDigTask.DetourHostImpl}; the JUnit implementation is a scripted fake
 * (see {@code FakeDetourHost}). The engine and the selector see no Minecraft world, no bot, no registry and no
 * clock: everything time-like comes from {@link #now()} and {@link #serverTick()}, everything block-like is a
 * {@link BlockPos} plus a registry-id string, so a fake can drive every path of design 4.3 to 4.12.
 *
 * <p><b>Threading and cost.</b> Server thread only. A host call is either a plain read or one bounded piece of
 * work; the engine never calls a host method twice in a tick for the same answer unless documented.</p>
 *
 * <p><b>Honesty rules (I1 to I3) the implementation must keep.</b> Every block read is preceded by an
 * {@code OreScan.observe*} or {@code ObservableWorldQuery} proof of that exact cell; UNKNOWN is never absent and
 * never safe; no teleport, forced pickup outside {@code CapabilityRuntime}, hidden scan or structure lookup. A
 * sighting is only a nomination: {@link #observeBlockIs} is the re-proof, and {@link #mineStep} is only called
 * after it said PRESENT in the same tick (or while the miner already owns the cell).</p>
 *
 * <p><b>Units.</b> "task ticks" are {@code AbstractTask.elapsedTicks()} (they stop while the task is paused or
 * skipped under degraded TPS); "server ticks" are {@code server.getTickCount()}. Deadlines and heartbeats are in task
 * ticks; cooldowns, exclusions, claims, the route budget and the mission ledger are in server ticks.</p>
 *
 * <p><b>Names.</b> The production implementation is an inner class of {@code OreDigTask}; several methods below
 * share a simple name with an OreDig member ({@code isCurrentSupport}, {@code rebaseTargetMonitors},
 * {@code rebaseCursorHere}, {@code noteProgress}, possibly more). Java resolves an unqualified call to the
 * innermost class that has a method of that name, so a delegation with the same name would call itself or fail to
 * compile: the outer helpers are named with a {@code detour} prefix and every delegation is qualified
 * ({@code OreDigTask.this.x()} or {@code OreDigTask.x(...)}).</p>
 */
interface DetourHost {

    // ------------------------------------------------------------------------------------------------------
    // Value types
    // ------------------------------------------------------------------------------------------------------

    /** Tri-state of an observation ({@code OreScan.Observation} without the dependency): PRESENT, GONE (observed absent) or UNKNOWN (not observable now). */
    enum Seen {
        PRESENT,
        GONE,
        UNKNOWN
    }

    /** Result of {@link #startRoute}. */
    enum RouteResult {
        /** A walk-only route was started and its resolved goal equals the requested stand. */
        OK,
        /** The action pack refused with {@code pathfinding_throttled}; try again on a later tick, not an attempt. */
        THROTTLED,
        /** The search or the route contract failed (any other failed result, or a resolved goal that differs from the stand). Counts as an attempt. */
        FAILED,
        /** {@code RouteBudget} refused the start; try again on a later tick, not an attempt. */
        BUDGET
    }

    /** Result of {@link #sealOneFluidNeighbour}. */
    enum SealResult {
        SEALED,
        /** A reactive shield temporarily owns the use key; retry without consuming a seal or timeout budget. */
        WAITING,
        /** No sacrificial block above the protected reserve. */
        NO_BLOCK,
        /** The placement failed. */
        FAILED
    }

    /** Result of {@link #toolVerdict}. */
    enum ToolVerdict {
        OK,
        /** No pickaxe passes the mining-channel policy for this block ({@code missing_mining_channel_tool}): abort {@code tool}. */
        NO_TOOL,
        /** The selected tool has too little durability left: abort {@code tool_wear}. */
        WEAR
    }

    /** Result of {@link #breakGeometry}. */
    enum GeometryVerdict {
        OK,
        /** The ore is above the bot and the block above the ore is a falling block. */
        ABOVE_FALLING,
        /** The ore is above the bot and the block above the ore cannot be observed. */
        ABOVE_UNOBSERVED,
        /** A physical drop catch is required and is not already there (an opportunistic detour never spends support blocks). */
        CATCH_MISSING
    }

    /**
     * The strip cursor state the detour must give back. {@code face} is the physical work cell (the feet cell at
     * the start), the four ints are OreDig's {@code stripDirIndex}, {@code stripLegIndex}, {@code stripStepsLeft}
     * and {@code stripLegLength}. {@link #y()} is {@code face.getY()}.
     */
    record Anchor(BlockPos face, int stripDirIndex, int stripLegIndex, int stripStepsLeft, int stripLegLength) {
        public Anchor {
            face = face.immutable();
        }

        int y() {
            return face.getY();
        }
    }

    /** A stand pose for one ore. {@code zeroTransit} is true when {@code stand} is the bot's current feet cell (no route needed). */
    record Pose(BlockPos stand, boolean zeroTransit) {
        public Pose {
            stand = stand.immutable();
        }
    }

    /** Result of {@link #mineStep}: MINING (keep ticking), DONE (the cell is now air per the miner), or FAILED with a reason string. */
    record MineStep(Status status, String reason) {
        enum Status {
            MINING,
            DONE,
            FAILED
        }

        static final MineStep MINING = new MineStep(Status.MINING, "");
        static final MineStep DONE = new MineStep(Status.DONE, "");

        static MineStep failed(String reason) {
            return new MineStep(Status.FAILED, reason == null ? "" : reason);
        }

        /** True for a FAILED step whose reason starts with {@code missing_mining_channel_tool}: the engine maps it to abort {@code tool}. */
        boolean isMissingChannelTool() {
            return status == Status.FAILED && reason.startsWith("missing_mining_channel_tool");
        }
    }

    /**
     * Result of {@link #probeFluidAround}: {@code present} is the first neighbour observed as lava or water
     * (search order DOWN, UP, NORTH, SOUTH, WEST, EAST) or null; {@code unknown} is true when at least one
     * neighbour could not be observed.
     */
    record FluidProbe(BlockPos present, boolean unknown) {
        static final FluidProbe CLEAR = new FluidProbe(null, false);
    }

    /**
     * Result of {@link #observeDrop}: {@code visible} is true when at least one observable, visible item entity lies within
     * 3.5 blocks of the break cell and 8 of the bot (the one nearest the BREAK CELL counts, whatever else lies nearer to the
     * bot); {@code stand} is that item's floor cell when that cell passes
     * the strict stand tests, else null (an item in an unreachable pit is a lost drop, not a reason to dig);
     * {@code atRest} is that item's own settled state -- on the ground, slow while touching water, or slow on two
     * observations of the same item a few ticks apart (a single slow reading may be the apex of the pop and proves
     * nothing; see {@code DropRestGate}). A freshly spawned item is
     * still falling for the first few ticks, during which its floor cell is not yet meaningful, so a null
     * {@code stand} while {@code atRest} is false means "not yet known", not "unreachable" (design 4.9).
     */
    record DropView(boolean visible, BlockPos stand, boolean atRest) {
        static final DropView NONE = new DropView(false, null, true);
    }

    // ------------------------------------------------------------------------------------------------------
    // Time, identity, position, configuration
    // ------------------------------------------------------------------------------------------------------

    /** Task ticks: {@code elapsedTicks()} of the hosting task. Monotonic while the task runs. */
    int now();

    /** Server ticks: {@code server.getTickCount()}. */
    int serverTick();

    /** A stable per-bot number in 0..9 that staggers the start checks (design 4.3: "staggered by uuid"). */
    int staggerSeed();

    /** OreDig's {@code maxElapsed}, the mission hard budget in task ticks (used for the detour tick budget). */
    int taskMaxElapsedTicks();

    /** The bot's feet cell. */
    BlockPos feet();

    /** The bot's eye position (distance to a block centre is the admission's eye distance). */
    Vec3 eyePos();

    /** The lowest Y a stand pose or route may use: {@code OreDigTask.MIN_Y + 1}, that is -59. Walk-only never digs down. */
    int minStandY();

    /**
     * Whether the bot's own feet cell is a standable cell (used only to log an unsafe rebase and to pick the return
     * retry cadence). Observation-gated: the feet cell, the head cell and the floor cell must be observable before
     * {@code Standability} reads them; an unobservable cell counts as NOT standable (the safer, slower cadence).
     */
    boolean feetStandable();

    /**
     * The Y at or below which lava lakes are expected in the bot's dimension ({@code DetourPolicy.lavaBandTopY(
     * dimensionKey)}: -50 in the overworld, 32 in the Nether, {@code Integer.MIN_VALUE} in the End). The engine and
     * the selector apply it to {@code min(feet.y, stand.y, ore.y)}: at or below it a detour needs
     * {@link #sealMaterialOk()}.
     */
    int lavaBandTopY();

    /** The live {@code detour} config section. */
    MiningAssistConfig.Detour config();

    /** The deterministic flag for this bot ({@code MiningAssistRuntime.deterministic(uuid)}): no time based throttling. */
    boolean deterministic();

    // ------------------------------------------------------------------------------------------------------
    // Owners (design 4.3 "start conditions")
    // ------------------------------------------------------------------------------------------------------

    /**
     * True only when OreDig owns nothing that a detour would disturb, evaluated live: {@code !restoringFace},
     * {@code miner.target() == null}, {@code pendingPickupPos == null}, {@code activeTargetBreakPos == null},
     * {@code bonusOre == null}, {@code veinQueue.isEmpty()}, {@code blockedBodyRecoveryTarget == null},
     * {@code !hasStagedBlindFootWork(bot)}, {@code lavaReroute == null}, {@code boundaryRerouteOrigin == null},
     * {@code pendingBlindAdvance == null}, and the action pack has no path executor and no walker running.
     */
    boolean ownersIdle();

    /** NONE when {@code targetOre == null}; NEAR when a target is locked and within 3 blocks of the feet cell; else LOCKED. */
    DetourPolicy.TargetLock targetLock();

    /**
     * True when {@code blockId} is a target block of this task: it is in {@code OreScan.expandOreFamilies(targetOres)}
     * or its {@code HarvestCore.expectedDropsFor} intersects the task's target drops (design 4.1). Such blocks
     * belong to {@code nearestOre}, never to a detour.
     */
    boolean isTargetOre(String blockId);

    /**
     * True when the existing bonus scan owns {@code pos}: {@code bonusMined < BONUS_CAP}, the id ends with
     * {@code _ore}, and {@code pos} is inside the {@code scanBonusOre} box (feet plus or minus 2 in x and z, at
     * or above the feet level, up to 3 above) and within reach. After the cap, or for a non-{@code _ore} block, or
     * below the feet, the detour takes it (design 4.3, "bonus ownership").
     */
    boolean bonusOwns(BlockPos pos, String blockId);

    // ------------------------------------------------------------------------------------------------------
    // Nominations, exclusions, claims, ledgers
    // ------------------------------------------------------------------------------------------------------

    /** A snapshot of the bot's sighting ledger in retention order ({@code snapshotSortedByValueDesc()}); empty when the bot has no assist state. */
    List<SightingLedger.Sighting> sightings();

    /** Forgets a sighting that was observed gone ({@code SightingLedger.markGone}). */
    void forgetSighting(BlockPos pos);

    /**
     * The mission ledger of the bot's current mission, touched at the current server tick. The engine calls this
     * once, at {@code start()}, and keeps the returned entry in a field for the whole detour ({@code interrupt},
     * {@code finish}, {@code rebase} and {@code abandon} use only that field): {@code TaskManager} removes the
     * active origin BEFORE it calls {@code onPause} or {@code onAbort}, so a later lookup would resolve the wrong
     * (ad hoc) key. The selector calls it while the origin is present. A production host that finds no origin
     * reuses the key it resolved last and falls back to the ad hoc key only when it never resolved one.
     */
    MissionAssistLedger.Entry ledger();

    /** Excluded by the detour's private table or, read-only, by {@code EpisodeMemory}, at the current server tick. */
    boolean excluded(BlockPos pos);

    /** Excludes {@code pos} in the detour's private table for {@code ttlServerTicks} server ticks ({@code EpisodeMemory} is never written). */
    void exclude(BlockPos pos, int ttlServerTicks);

    /** {@code DetourExclusions.shouldLogSkip}: true at most once per 600 ticks per cell; the call records that it was. */
    boolean shouldLogSkip(BlockPos pos);

    /** True when another bot holds a live claim on {@code pos} ({@code OreClaims.heldByOther}). */
    boolean claimedByOther(BlockPos pos);

    /** Claims {@code pos} for this bot; false when another bot holds it. Renewing an own claim returns true. */
    boolean tryClaim(BlockPos pos);

    /** Renews every claim of this bot (the engine calls it every 40 server ticks while a detour is live). */
    void renewClaims();

    /** Releases every claim of this bot. Idempotent. */
    void releaseClaims();

    // ------------------------------------------------------------------------------------------------------
    // Observation
    // ------------------------------------------------------------------------------------------------------

    /**
     * The re-proof of design 4.6 step 1: {@code OreScan.observe(bot, pos, state -> state.is(block))} where
     * {@code block} is the registry block of {@code blockId} (a bare path means the {@code minecraft} namespace).
     * PRESENT when observed and it is that block, GONE when observed and it is something else, UNKNOWN when the
     * cell cannot be observed now. An id that is not in the registry is GONE without any observation (it must
     * never be resolved to air, for which the predicate would answer PRESENT). The state captured inside the
     * predicate is remembered per cell (a small bounded map) for {@link #toolVerdict}.
     */
    Seen observeBlockIs(BlockPos pos, String blockId);

    /**
     * The six-neighbour fluid re-observation of design 4.7 around {@code cell}: {@code OreScan.observeDangerFluid}
     * for each direction. See {@link FluidProbe}.
     */
    FluidProbe probeFluidAround(BlockPos cell);

    /** The observed-occupancy reachability precheck from {@code from} to {@code to} (design 4.3): {@code ObservedReach.search} over the bot's window; INCONCLUSIVE when the bot has no window yet. */
    ObservedReach.Result observedReach(BlockPos from, BlockPos to);

    /** {@code OreScan.veinFrom(bot, seed, OreScan.oreFamily(block), cap)}: up to {@code cap} observed same-block cells of the vein of {@code seed}, seed included; empty when the seed is not observable. Called once at the start. */
    List<BlockPos> veinAt(BlockPos seed, String blockId, int cap);

    /** The 26 neighbours of {@code around} that {@code OreScan.observe} says are PRESENT and hold {@code blockId} (design 4.7, vein discovery after each break). */
    List<BlockPos> neighbours26Same(BlockPos around, String blockId);

    // ------------------------------------------------------------------------------------------------------
    // Pose and geometry
    // ------------------------------------------------------------------------------------------------------

    /**
     * {@code DetourPose.pick(ore)} of design 4.5: the first legal stand among at most 9 candidates (current feet if
     * it is a recoverable break pose and the ore is not the current support; {@code approachGoalFor}; the four
     * same-level side stands), or null. Calls {@code Standability.clearCache()} once at the start. Every
     * pose also satisfies {@code DetourPolicy.standWithinLimits(stand, anchor.face(), minStandY(), config())},
     * is not in {@code forbiddenStands}, is not {@link #excluded}, is not in the anchor's 3x3 floor patch (nor is
     * the ore), is not within the lava or trap clearance of the {@code HazardField}, and its observation and
     * standability gates hold. The result is never a stand for which the ore is the current support.
     */
    Pose poseFor(BlockPos ore, Anchor anchor, Set<BlockPos> forbiddenStands);

    /** {@code hasRecoverableTargetBreakPose(bot, ore)}: dy in [-1, 2], Manhattan distance at most 1, within reach, from the current feet. */
    boolean inBreakEnvelope(BlockPos ore);

    /** {@code ore.equals(feet.down())}. */
    boolean isCurrentSupport(BlockPos ore);

    /** Design 4.6 step 5 from the current feet: for dy at least 1 the block above the ore must be observed and not a falling block, and when a drop catch is needed ({@code needsTargetDropSupport}) {@code hasReliableObservedDropCatch(ore.down())} must already hold. */
    GeometryVerdict breakGeometry(BlockPos ore);

    // ------------------------------------------------------------------------------------------------------
    // Resource gates
    // ------------------------------------------------------------------------------------------------------

    /**
     * Design 4.6 step 7: {@code ToolTier.canHarvestWithInventory}, then {@code ToolSelector.equipMiningChannelTool}
     * (idempotent, the same call {@code beginMine} makes; NOTE it EQUIPS the tool, so the selector calls this late,
     * for the one candidate that is about to be returned), then the durability rule of
     * {@code InventoryHeadroom.durabilityOk(remaining, maxDamage, plannedMembers)}. The block state is the one the
     * last successful {@link #observeBlockIs} of that exact cell saw (kept per cell by the host); when the host has
     * none for the cell it re-observes first. It is never read from the world here.
     */
    ToolVerdict toolVerdict(BlockPos ore, int plannedMembers);

    /** Design 4.6 step 8: {@code InventoryHeadroom.capacityOk} (or {@code unknownDropOk} for an unknown drop) with reserve {@code config().minFreeSlots()} ({@code minFreeSlotsRareBatch()} in rare expedition batches). */
    boolean capacityOk(BlockPos ore, String blockId);

    /** Design 4.7, deep band: the sacrificial palette slot's count minus {@code protectedStoneLikeReserve} is at least 2. */
    boolean sealMaterialOk();

    // ------------------------------------------------------------------------------------------------------
    // Movement
    // ------------------------------------------------------------------------------------------------------

    /** Peek at {@code RouteBudget.shared().canStart(serverTick(), deterministic())} without consuming anything. */
    boolean routeStartAllowed();

    /**
     * Starts a walk-only route to {@code stand} (design 4.5): checks {@code RouteBudget} (BUDGET when refused),
     * runs {@code bot.getActionPack().startSurfacePathTo(stand, minY, returnAnchor)} (a null anchor selects the
     * two-argument overload, which needs no return proof), maps {@code pathfinding_throttled} to THROTTLED (checked
     * BEFORE any budget charge: a throttled call ran no search and costs nothing) and any other failure to FAILED
     * (remember its text for {@link #routeFailureReason}), charges the measured {@code nanoTime} cost of a real
     * search to the budget, and after a success requires {@code activePathGoal().equals(stand)}, otherwise
     * {@code stopAll()} and FAILED. Never a dig route, never a pillar. Used for approach and chase legs; the
     * mandatory return uses {@link #startReturnRoute}.
     *
     * <p>Cost note: with a non-null anchor the path executor re-proves the walk-only return with an uncached
     * search (up to 10000 nodes or 50 ms) at every NEW cell it enters, outside this call, so the start cost measured
     * here understates the route's cost. The 4-bot cost gate measures it; a mid-route loss of the contract ends the
     * path early with {@code route_contract_lost}, which the engine sees as an idle path short of the goal.</p>
     */
    RouteResult startRoute(BlockPos stand, int minY, BlockPos returnAnchorOrNull);

    /**
     * Starts the walk-only route of the mandatory RETURN to the anchor face (two-argument overload, no return
     * proof). Unlike {@link #startRoute} it never answers BUDGET: the return is the safety path and must not depend
     * on a fairness budget (four bots each burning 100 ms on failing searches would otherwise starve it into a
     * rebase). It still charges its measured cost to {@code RouteBudget} (the balance may go negative, the floor is
     * {@code -capacity}) and takes the tick's token, so other bots' optional starts feel it. It can still answer
     * THROTTLED (the action pack's own search throttle) or FAILED, and it applies the same resolved-goal check.
     */
    RouteResult startReturnRoute(BlockPos face, int minY);

    /** The failure text of the last FAILED {@link #startRoute} (for the log), or the empty string. */
    String routeFailureReason();

    /** {@code bot.getActionPack().isPathExecutorIdle()}. */
    boolean pathIdle();

    /** {@code bot.getActionPack().isWalkToIdle()}. */
    boolean walkIdle();

    /** {@code bot.getActionPack().stopAll()}. */
    void stopAll();

    /** {@code miner.cancel(bot)}. */
    void cancelMining();

    // ------------------------------------------------------------------------------------------------------
    // Mining, sealing, drops
    // ------------------------------------------------------------------------------------------------------

    /**
     * One mining tick of {@code pos} through the existing {@code beginMine(bot, pos)} (stop movement,
     * {@code miner.begin} with the channel-tool policy, {@code miner.tick}). Never {@code fail()}, never
     * {@code failMissingMiningChannelTool}: a {@code missing_mining_channel_tool:*} reason is returned as a
     * FAILED step and the engine maps it to abort {@code tool}.
     */
    MineStep mineStep(BlockPos pos);

    /** Places one sealing block on {@code fluidCell} (pattern of design 4.7): {@code pickSacrificialBlockSlot}, {@code equipFromSlot}, {@code BuildAction.placeBlockAt}. At most one cell per call. */
    SealResult sealOneFluidNeighbour(BlockPos fluidCell);

    /** {@code HarvestCore.totalInventoryCount(bot)}: every item in the main inventory and the off hand. */
    int inventoryTotal();

    /**
     * See {@link DropView}. Queries ALL observable, visible item entities within 8 blocks of the bot
     * ({@code canObserveEntity}, invisible ones skipped), keeps those within 3.5 blocks of the break cell centre
     * and picks the one nearest the break cell (ties by position); an unrelated item nearer to the bot never hides
     * the real drop. Calls {@code Standability.clearCache()}, then tests the chosen item's floor cell in this order:
     * {@code canObserveCell(stand)}, {@code canObserveCell(stand.up())}, {@code canObserveBlock(stand.down())},
     * {@code Standability.isStandable}, {@code adjacentHazard == OBSERVED_GONE}; a failure gives {@code stand = null}.
     */
    DropView observeDrop(BlockPos breakCell);

    // ------------------------------------------------------------------------------------------------------
    // Safety and progress
    // ------------------------------------------------------------------------------------------------------

    /**
     * The live SAFE gate ({@code DetourSafetyGate.evaluate}): {@code SafeGate.evaluate(inputs, stage)}. {@code pose}
     * and {@code ore} (either may be null) extend the lava and trap radius checks to those cells. The selector asks
     * with {@code START}; a running detour asks with {@code TICK_FAST} and {@code TICK_FULL} (parity of its ticks)
     * and, before a break, a route leg and a drop chase, once more with {@code TICK_FULL} (never {@code START}: the
     * start thresholds would defeat the start/abort hysteresis of {@code TickHeadroom} and the hp margin).
     */
    SafeReason safety(SafeGate.Stage stage, BlockPos pose, BlockPos ore);

    /** OreDig's {@code noteProgress()}: the only reset of {@code NO_PROGRESS_LIMIT}. The engine calls it at the heartbeat points of design 4.10 and nowhere else. */
    void noteProgress();

    // ------------------------------------------------------------------------------------------------------
    // Anchor and cursor
    // ------------------------------------------------------------------------------------------------------

    /** Reads the current anchor: {@code face = feet} and the four strip numbers. No side effect. */
    Anchor captureAnchor();

    /** {@code clearStripMovementOwnership()}. */
    void clearStripOwnership();

    /**
     * Idempotently writes the four strip numbers of {@code anchor} back ({@code stripDirIndex}, {@code stripLegIndex},
     * {@code stripStepsLeft}, {@code stripLegLength}) and returns true when any of them differed before (a drift
     * the engine reports as {@code ore_dig_detour_cursor_drift}).
     */
    boolean restoreAnchorNumbers(Anchor anchor);

    /** {@code targetApproachTick = elapsed} and, when a target is locked, {@code lastTargetDist = Double.MAX_VALUE}. */
    void rebaseTargetMonitors();

    /**
     * The return-failure rebase (design 4.10): the current feet cell becomes face and progress,
     * {@code stripDirIndex = -1}, {@code stripLegIndex = 0}, {@code stripStepsLeft = 0},
     * {@code stripLegLength = STRIP_SEGMENT}, {@code boundaryRerouteOrigin = null}, then
     * {@code clearStripMovementOwnership()}. Keeps {@code cursorOrigin} and {@code completedBatches}.
     */
    void rebaseCursorHere();

    // ------------------------------------------------------------------------------------------------------
    // Logging and side effects
    // ------------------------------------------------------------------------------------------------------

    /** {@code BotLog.task(bot, event, kv...)}. Event names are those of design 4.13. */
    void log(String event, Object... kv);

    /** {@code BotLog.warn(LogCategory.TASK, bot, event, kv...)}: the loud lines ({@code ore_dig_detour_return_rebased}). */
    void warn(String event, Object... kv);

    /** Sends the rare-find chat line through {@code BrainCoordinator.sendBotReply} with {@code DetourPolicy.announceText(blockId)}. The engine has already applied the value and the 600-tick rate limit. */
    void announce(String blockId);

    /** Feeds the knowledge base: {@code EpisodeLog.record(bot, RESOURCE_FOUND, pos, registryId)}. Called once per detour for the seed. */
    void recordFind(BlockPos pos, String blockId);

    /**
     * Kind FRONTIER's arrival action (design 5.4): {@code MiningAssistState.requestBreakthrough(serverTick())},
     * the same "opened a wall into unobserved space" mechanism {@code BreakPeek} already triggers -- P5 adds
     * no new sensing, only this one extra trigger of the existing breakthrough sweep. A no-op return value
     * (the request can be deferred by the {@code MIN_BREAKTHROUGH_GAP_TICKS} cooldown) is not surfaced; the
     * excursion does not wait on it.
     */
    void panoramaBurst();
}

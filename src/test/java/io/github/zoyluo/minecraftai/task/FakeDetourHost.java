package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.mining.assist.DetourPolicy;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistConfig;
import io.github.zoyluo.minecraftai.mining.assist.MissionAssistLedger;
import io.github.zoyluo.minecraftai.mining.assist.ObservedReach;
import io.github.zoyluo.minecraftai.mining.assist.SafeGate;
import io.github.zoyluo.minecraftai.mining.assist.SafeReason;
import io.github.zoyluo.minecraftai.mining.assist.SightingLedger;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Scripted, deterministic {@link DetourHost} for the JUnit tests of the detour engine and its start selector
 * (mining-assist design 9: "a scripted fake DetourHost"). It has no Minecraft world. Everything is a plain public
 * field with an "everything is fine" default, so a test sets only what it wants to break; every call is recorded.
 *
 * <h2>How a test drives it</h2>
 * <pre>
 * FakeDetourHost host = new FakeDetourHost();
 * host.sightings.add(new SightingLedger.Sighting(pos, "diamond_ore", 100, 0, 0));
 * OreDigDetourEngine engine = new OreDigDetourEngine();
 * for (int i = 0; i &lt; 400; i++) { host.tickClock(); OreDigDetourEngine.Result r = engine.tick(host); ... }
 * </pre>
 * {@link #tickClock()} advances both clocks by one and runs the mini simulation (movement of an active route,
 * the pickup of a dropped item); call it once per engine tick, before the engine's {@code tick}.
 *
 * <h2>Defaults</h2>
 * Owners idle, no target lock, every observation PRESENT (a mined cell reads GONE), fluid probe clear, observed
 * reach REACHABLE at 5, pose = the cell west of the ore (zero transit when the bot already stands there),
 * geometry, tool, capacity and sealing fine, routes succeed and move the bot one block per
 * {@link #ticksPerBlock} ticks along the greedy axis path, mining takes {@link #mineTicks} calls, the drop is
 * picked up {@link #pickupDelay} ticks after the break, the SAFE gate always says OK, claims always succeed.
 *
 * <h2>Scripting</h2>
 * {@code *Script} fields are queues consulted first (one element per call); when empty the default applies.
 * {@link #safetyFn} may inspect the stage. Recording: {@link #calls} (one string per side effect), {@link #beats}
 * (task tick of every {@code noteProgress}), {@link #logs} (event names), {@link #announced}.
 *
 * <p><b>Frozen for the P1 wave.</b> The architect (reviewer round) owns this file and fixed it once: nobody edits
 * it while WRITER-4 (engine tests) and WRITER-5 (selector tests) work in parallel. A writer who needs another knob
 * subclasses it in his own test file (the fields are package-private and the overridable methods public) and
 * reports the wish; the orchestrator folds accepted wishes in after the wave. Knobs added in the review round:
 * {@link #lavaBandTopY}, {@link #routeEndsAfterBlocks}, {@link #startReturnRoute} and a drop that stays visible until
 * it is picked up (so {@code pickupEnabled = false} really produces the 60-tick {@code drop_lost}).</p>
 */
class FakeDetourHost implements DetourHost {
    // ---- clock and identity -------------------------------------------------------------------------------
    int now = 100;
    int serverTick = 5000;
    int staggerSeed;
    int maxElapsed = 24_000;

    // ---- position and configuration -----------------------------------------------------------------------
    BlockPos feet = new BlockPos(0, 40, 0);
    int minStandY = -59;
    boolean feetStandable = true;
    /** What {@link #lavaBandTopY()} answers: -50 like the overworld. */
    int lavaBandTopY = -50;
    MiningAssistConfig.Detour cfg = MiningAssistConfig.Detour.DEFAULTS;
    boolean deterministic = true;

    // ---- owners ---------------------------------------------------------------------------------------------
    boolean ownersIdle = true;
    DetourPolicy.TargetLock lock = DetourPolicy.TargetLock.NONE;
    final Set<String> targetIds = new HashSet<>();
    final Set<BlockPos> bonusOwned = new HashSet<>();

    // ---- nominations, exclusions, claims, ledger --------------------------------------------------------------
    final List<SightingLedger.Sighting> sightings = new ArrayList<>();
    final Set<BlockPos> forgotten = new HashSet<>();
    MissionAssistLedger.Entry ledger = new MissionAssistLedger.Entry();
    final Map<BlockPos, Integer> excludedUntil = new HashMap<>();
    final Set<BlockPos> claimedByOthers = new HashSet<>();
    final Set<BlockPos> myClaims = new HashSet<>();
    final Set<BlockPos> skipLogged = new HashSet<>();

    // ---- observation --------------------------------------------------------------------------------------
    final Map<BlockPos, Deque<Seen>> seenScript = new HashMap<>();
    Seen defaultSeen = Seen.PRESENT;
    final Set<BlockPos> broken = new HashSet<>();
    final Map<BlockPos, Deque<FluidProbe>> fluidScript = new HashMap<>();
    ObservedReach.Result reach = ObservedReach.Result.reachable(5);
    final Map<BlockPos, ObservedReach.Result> reachByStand = new HashMap<>();
    List<BlockPos> vein = new ArrayList<>();
    final Map<BlockPos, List<BlockPos>> neighbours = new HashMap<>();

    // ---- pose and geometry ------------------------------------------------------------------------------------
    /** Overrides the default pose; return null for "no pose". */
    Function<BlockPos, Pose> poseFn;
    Function<BlockPos, Boolean> envelopeFn;
    GeometryVerdict geometry = GeometryVerdict.OK;
    ToolVerdict tool = ToolVerdict.OK;
    boolean capacityOk = true;
    boolean sealMaterialOk = true;

    // ---- movement ---------------------------------------------------------------------------------------------
    final Deque<RouteResult> routeScript = new ArrayDeque<>();
    RouteResult defaultRoute = RouteResult.OK;
    String routeFailure = "";
    boolean routeStartAllowed = true;
    int ticksPerBlock = 5;
    /** When non-negative, an active route goes idle short of its goal after this many steps (models {@code route_contract_lost} or a path that ends early). */
    int routeEndsAfterBlocks = -1;
    private int routeSteps;
    boolean pathActive;
    BlockPos routeGoal;
    boolean walkIdle = true;
    private int moveAccumulator;

    // ---- mining, drops --------------------------------------------------------------------------------------------
    final Map<BlockPos, Deque<MineStep>> mineScript = new HashMap<>();
    int mineTicks = 8;
    final Map<BlockPos, Integer> mineCalls = new HashMap<>();
    SealResult sealResult = SealResult.SEALED;
    int inventoryTotal;
    int pickupDelay = 3;
    boolean pickupEnabled = true;
    private int pickupCountdown = -1;
    private BlockPos lastBrokenCell;
    /** When non-null it answers {@link #observeDrop}; otherwise a drop is visible at the break cell until it is picked up. */
    Function<BlockPos, DropView> dropViewFn;

    // ---- safety ---------------------------------------------------------------------------------------------------
    final Deque<SafeReason> safetyScript = new ArrayDeque<>();
    Function<SafeGate.Stage, SafeReason> safetyFn = stage -> SafeReason.OK;

    // ---- anchor and cursor ------------------------------------------------------------------------------------------
    int stripDirIndex = 1;
    int stripLegIndex = 2;
    int stripStepsLeft = 17;
    int stripLegLength = 48;
    /** When true {@link #restoreAnchorNumbers} pretends the numbers had drifted. */
    boolean reportDrift;

    // ---- recording -----------------------------------------------------------------------------------------------------
    final List<String> calls = new ArrayList<>();
    final List<Integer> beats = new ArrayList<>();
    final List<String> logs = new ArrayList<>();
    final List<Object[]> logArgs = new ArrayList<>();
    final List<String> announced = new ArrayList<>();
    final List<String> found = new ArrayList<>();
    final List<SafeGate.Stage> stagesAsked = new ArrayList<>();

    // ---- P5 cave-frontier excursion (design 5.4) -------------------------------------------------------------
    /** Times {@link #panoramaBurst()} was called; the frontier engine test asserts it fires exactly once per arrival. */
    int panoramaBursts;

    // ===========================================================================================================
    // Simulation
    // ===========================================================================================================

    /** Advances both clocks by one tick, moves an active route one step per {@link #ticksPerBlock} ticks, and delivers a pending pickup. */
    void tickClock() {
        now++;
        serverTick++;
        if (pathActive && routeGoal != null) {
            moveAccumulator++;
            if (moveAccumulator >= ticksPerBlock) {
                moveAccumulator = 0;
                feet = stepToward(feet, routeGoal);
                routeSteps++;
            }
            if (feet.equals(routeGoal)) {
                pathActive = false;
            } else if (routeEndsAfterBlocks >= 0 && routeSteps >= routeEndsAfterBlocks) {
                pathActive = false;
            }
        }
        if (pickupCountdown > 0) {
            pickupCountdown--;
            if (pickupCountdown == 0 && pickupEnabled) {
                inventoryTotal++;
                lastBrokenCell = null;
            }
        }
    }

    private static BlockPos stepToward(BlockPos from, BlockPos to) {
        int dx = Integer.compare(to.getX(), from.getX());
        int dz = Integer.compare(to.getZ(), from.getZ());
        int dy = Integer.compare(to.getY(), from.getY());
        if (dx != 0) {
            return from.add(dx, 0, 0);
        }
        if (dz != 0) {
            return from.add(0, 0, dz);
        }
        return from.add(0, dy, 0);
    }

    /** The largest distance between two consecutive beats in {@code [fromTick, toTick]}, counting the interval edges as beats. */
    int maxBeatGap(int fromTick, int toTick) {
        int previous = fromTick;
        int max = 0;
        for (int beat : beats) {
            if (beat < fromTick || beat > toTick) {
                continue;
            }
            max = Math.max(max, beat - previous);
            previous = beat;
        }
        return Math.max(max, toTick - previous);
    }

    /** True when some recorded call starts with {@code prefix}. */
    boolean called(String prefix) {
        return calls.stream().anyMatch(call -> call.startsWith(prefix));
    }

    int countCalls(String prefix) {
        return (int) calls.stream().filter(call -> call.startsWith(prefix)).count();
    }

    private static String fmt(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    // ===========================================================================================================
    // DetourHost: time, identity, position, configuration
    // ===========================================================================================================

    @Override
    public int now() {
        return now;
    }

    @Override
    public int serverTick() {
        return serverTick;
    }

    @Override
    public int staggerSeed() {
        return staggerSeed;
    }

    @Override
    public int taskMaxElapsedTicks() {
        return maxElapsed;
    }

    @Override
    public BlockPos feet() {
        return feet;
    }

    @Override
    public Vec3d eyePos() {
        return new Vec3d(feet.getX() + 0.5D, feet.getY() + 1.62D, feet.getZ() + 0.5D);
    }

    @Override
    public int minStandY() {
        return minStandY;
    }

    @Override
    public boolean feetStandable() {
        return feetStandable;
    }

    @Override
    public int lavaBandTopY() {
        return lavaBandTopY;
    }

    @Override
    public MiningAssistConfig.Detour config() {
        return cfg;
    }

    @Override
    public boolean deterministic() {
        return deterministic;
    }

    // ===========================================================================================================
    // Owners
    // ===========================================================================================================

    @Override
    public boolean ownersIdle() {
        return ownersIdle;
    }

    @Override
    public DetourPolicy.TargetLock targetLock() {
        return lock;
    }

    @Override
    public boolean isTargetOre(String blockId) {
        return targetIds.contains(blockId);
    }

    @Override
    public boolean bonusOwns(BlockPos pos, String blockId) {
        return bonusOwned.contains(pos);
    }

    // ===========================================================================================================
    // Nominations, exclusions, claims, ledger
    // ===========================================================================================================

    @Override
    public List<SightingLedger.Sighting> sightings() {
        return new ArrayList<>(sightings);
    }

    @Override
    public void forgetSighting(BlockPos pos) {
        forgotten.add(pos.toImmutable());
        sightings.removeIf(s -> s.pos().equals(pos));
        calls.add("forget:" + fmt(pos));
    }

    @Override
    public MissionAssistLedger.Entry ledger() {
        return ledger;
    }

    @Override
    public boolean excluded(BlockPos pos) {
        Integer until = excludedUntil.get(pos);
        return until != null && serverTick < until;
    }

    @Override
    public void exclude(BlockPos pos, int ttlServerTicks) {
        excludedUntil.merge(pos.toImmutable(), serverTick + ttlServerTicks, Math::max);
        calls.add("exclude:" + fmt(pos) + ":" + ttlServerTicks);
    }

    @Override
    public boolean shouldLogSkip(BlockPos pos) {
        return skipLogged.add(pos.toImmutable());
    }

    @Override
    public boolean claimedByOther(BlockPos pos) {
        return claimedByOthers.contains(pos);
    }

    @Override
    public boolean tryClaim(BlockPos pos) {
        calls.add("claim:" + fmt(pos));
        if (claimedByOthers.contains(pos)) {
            return false;
        }
        myClaims.add(pos.toImmutable());
        return true;
    }

    @Override
    public void renewClaims() {
        calls.add("renew");
    }

    @Override
    public void releaseClaims() {
        myClaims.clear();
        calls.add("release");
    }

    // ===========================================================================================================
    // Observation
    // ===========================================================================================================

    @Override
    public Seen observeBlockIs(BlockPos pos, String blockId) {
        if (broken.contains(pos)) {
            return Seen.GONE;
        }
        Deque<Seen> script = seenScript.get(pos);
        if (script != null && !script.isEmpty()) {
            return script.poll();
        }
        return defaultSeen;
    }

    @Override
    public FluidProbe probeFluidAround(BlockPos cell) {
        Deque<FluidProbe> script = fluidScript.get(cell);
        if (script != null && !script.isEmpty()) {
            return script.poll();
        }
        return FluidProbe.CLEAR;
    }

    @Override
    public ObservedReach.Result observedReach(BlockPos from, BlockPos to) {
        return reachByStand.getOrDefault(to, reach);
    }

    @Override
    public List<BlockPos> veinAt(BlockPos seed, String blockId, int cap) {
        List<BlockPos> result = new ArrayList<>();
        result.add(seed);
        for (BlockPos member : vein) {
            if (result.size() >= cap) {
                break;
            }
            if (!result.contains(member)) {
                result.add(member);
            }
        }
        return result;
    }

    @Override
    public List<BlockPos> neighbours26Same(BlockPos around, String blockId) {
        return new ArrayList<>(neighbours.getOrDefault(around, List.of()));
    }

    // ===========================================================================================================
    // Pose and geometry
    // ===========================================================================================================

    @Override
    public Pose poseFor(BlockPos ore, Anchor anchor, Set<BlockPos> forbiddenStands) {
        if (poseFn != null) {
            return poseFn.apply(ore);
        }
        BlockPos stand = ore.west();
        if (forbiddenStands != null && forbiddenStands.contains(stand)) {
            return null;
        }
        return new Pose(stand, stand.equals(feet));
    }

    @Override
    public boolean inBreakEnvelope(BlockPos ore) {
        if (envelopeFn != null) {
            return envelopeFn.apply(ore);
        }
        int dy = ore.getY() - feet.getY();
        int manhattan = Math.abs(ore.getX() - feet.getX()) + Math.abs(ore.getZ() - feet.getZ());
        return dy >= -1 && dy <= 2 && manhattan <= 1;
    }

    @Override
    public boolean isCurrentSupport(BlockPos ore) {
        return ore.equals(feet.down());
    }

    @Override
    public GeometryVerdict breakGeometry(BlockPos ore) {
        return geometry;
    }

    // ===========================================================================================================
    // Resource gates
    // ===========================================================================================================

    @Override
    public ToolVerdict toolVerdict(BlockPos ore, int plannedMembers) {
        calls.add("tool:" + fmt(ore) + ":" + plannedMembers);
        return tool;
    }

    @Override
    public boolean capacityOk(BlockPos ore, String blockId) {
        return capacityOk;
    }

    @Override
    public boolean sealMaterialOk() {
        return sealMaterialOk;
    }

    // ===========================================================================================================
    // Movement
    // ===========================================================================================================

    @Override
    public boolean routeStartAllowed() {
        return routeStartAllowed;
    }

    @Override
    public RouteResult startRoute(BlockPos stand, int minY, BlockPos returnAnchorOrNull) {
        return route(stand, returnAnchorOrNull == null ? "noanchor" : "anchor");
    }

    /**
     * The mandatory return start. Consults the same {@link #routeScript} and records the same {@code route:} line
     * (with {@code noanchor}, so a test counting {@code route:} calls sees approach, chase and return uniformly)
     * plus a {@code return} marker line. The real host never answers BUDGET here; a test may still script it to prove
     * the engine's RETURN survives every answer.
     */
    @Override
    public RouteResult startReturnRoute(BlockPos face, int minY) {
        calls.add("return:" + fmt(face));
        return route(face, "noanchor");
    }

    private RouteResult route(BlockPos stand, String anchorTag) {
        RouteResult result = routeScript.isEmpty() ? defaultRoute : routeScript.poll();
        calls.add("route:" + fmt(stand) + ":" + result + ":" + anchorTag);
        if (result == RouteResult.OK) {
            pathActive = true;
            routeGoal = stand.toImmutable();
            moveAccumulator = 0;
            routeSteps = 0;
        } else if (result == RouteResult.FAILED) {
            routeFailure = "pathfinding_failed: NO_PATH";
        }
        return result;
    }

    @Override
    public String routeFailureReason() {
        return routeFailure;
    }

    @Override
    public boolean pathIdle() {
        return !pathActive;
    }

    @Override
    public boolean walkIdle() {
        return walkIdle;
    }

    @Override
    public void stopAll() {
        pathActive = false;
        calls.add("stopAll");
    }

    @Override
    public void cancelMining() {
        calls.add("cancelMining");
    }

    // ===========================================================================================================
    // Mining, sealing, drops
    // ===========================================================================================================

    @Override
    public MineStep mineStep(BlockPos pos) {
        calls.add("mine:" + fmt(pos));
        Deque<MineStep> script = mineScript.get(pos);
        if (script != null && !script.isEmpty()) {
            MineStep step = script.poll();
            if (step.status() == MineStep.Status.DONE) {
                onBroken(pos);
            }
            return step;
        }
        int count = mineCalls.merge(pos.toImmutable(), 1, Integer::sum);
        if (count >= mineTicks) {
            mineCalls.remove(pos);
            onBroken(pos);
            return MineStep.DONE;
        }
        return MineStep.MINING;
    }

    private void onBroken(BlockPos pos) {
        broken.add(pos.toImmutable());
        lastBrokenCell = pos.toImmutable();
        pickupCountdown = pickupDelay;
    }

    @Override
    public SealResult sealOneFluidNeighbour(BlockPos fluidCell) {
        calls.add("seal:" + fmt(fluidCell) + ":" + sealResult);
        return sealResult;
    }

    @Override
    public int inventoryTotal() {
        return inventoryTotal;
    }

    @Override
    public void tryForcedPickup() {
        calls.add("forcedPickup");
    }

    @Override
    public DropView observeDrop(BlockPos breakCell) {
        if (dropViewFn != null) {
            return dropViewFn.apply(breakCell);
        }
        // The drop stays visible until it is picked up (lastBrokenCell is cleared only by the pickup), so with
        // pickupEnabled = false it stays for ever: the engine then reaches its 60-tick drop_lost timeout.
        return lastBrokenCell != null ? new DropView(true, feet) : DropView.NONE;
    }

    // ===========================================================================================================
    // Safety and progress
    // ===========================================================================================================

    @Override
    public SafeReason safety(SafeGate.Stage stage, BlockPos pose, BlockPos ore) {
        stagesAsked.add(stage);
        if (!safetyScript.isEmpty()) {
            return safetyScript.poll();
        }
        return safetyFn.apply(stage);
    }

    @Override
    public void noteProgress() {
        beats.add(now);
        calls.add("beat");
    }

    // ===========================================================================================================
    // Anchor and cursor
    // ===========================================================================================================

    @Override
    public Anchor captureAnchor() {
        return new Anchor(feet, stripDirIndex, stripLegIndex, stripStepsLeft, stripLegLength);
    }

    @Override
    public void clearStripOwnership() {
        calls.add("clearStripOwnership");
    }

    @Override
    public boolean restoreAnchorNumbers(Anchor anchor) {
        calls.add("restoreAnchorNumbers");
        stripDirIndex = anchor.stripDirIndex();
        stripLegIndex = anchor.stripLegIndex();
        stripStepsLeft = anchor.stripStepsLeft();
        stripLegLength = anchor.stripLegLength();
        return reportDrift;
    }

    @Override
    public void rebaseTargetMonitors() {
        calls.add("rebaseTargetMonitors");
    }

    @Override
    public void rebaseCursorHere() {
        calls.add("rebaseCursorHere");
        stripDirIndex = -1;
        stripLegIndex = 0;
        stripStepsLeft = 0;
        stripLegLength = 48;
    }

    // ===========================================================================================================
    // Logging and side effects
    // ===========================================================================================================

    @Override
    public void log(String event, Object... kv) {
        logs.add(event);
        logArgs.add(kv);
    }

    @Override
    public void warn(String event, Object... kv) {
        logs.add("warn:" + event);
        logArgs.add(kv);
    }

    @Override
    public void announce(String blockId) {
        announced.add(blockId);
    }

    @Override
    public void recordFind(BlockPos pos, String blockId) {
        found.add(blockId + "@" + fmt(pos));
    }

    // ===========================================================================================================
    // P5 cave-frontier excursion (design 5.4)
    // ===========================================================================================================

    /**
     * Kind FRONTIER's arrival action: increments {@link #panoramaBursts} and records the call, exactly as much
     * as a test needs to assert it fired (and fired once) without modelling {@code requestBreakthrough}'s own
     * cooldown -- that mechanism is {@code BreakPeek}'s territory, already covered elsewhere.
     */
    @Override
    public void panoramaBurst() {
        panoramaBursts++;
        calls.add("panoramaBurst");
    }
}

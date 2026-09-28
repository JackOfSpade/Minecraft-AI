package io.github.zoyluo.aibot.task;

import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.mining.assist.AssistMode;
import io.github.zoyluo.aibot.mining.assist.DetourPhase;
import io.github.zoyluo.aibot.mining.assist.MiningAssistConfig;
import io.github.zoyluo.aibot.mining.assist.MiningAssistRegistry;
import io.github.zoyluo.aibot.mining.assist.MiningAssistRuntime;
import io.github.zoyluo.aibot.mining.assist.MiningAssistState;
import io.github.zoyluo.aibot.mining.assist.ObservedOccupancy;
import io.github.zoyluo.aibot.mode.CapabilityRuntime;
import io.github.zoyluo.aibot.mode.OperatingProfile;
import io.github.zoyluo.aibot.mode.PrivilegedCapability;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Real-Minecraft GameTests for mining-assist phase P5 R2b (design 5.4, {@code MINING_ASSIST_DESIGN.md} section
 * 8.2 hook 14 and section 10's phase table): the cave-frontier excursion (kind FRONTIER in
 * {@code OreDigDetourEngine}), running through the real {@code OreDigTask} / {@code OreDigDetourEngine} /
 * {@code SafeGate} stack against a live server, never the JUnit {@code FakeDetourHost}.
 *
 * <p>Both tests follow the {@code OreDigOpportunisticGameTests} / {@code LegChooserGameTests} Harness/Room idiom
 * (a sealed stone-shelled fixture, {@link MiningAssistRuntime#forceEnable}, a DETOUR-mode config with
 * {@code harnessOff=true}, a real MISSION-origin task). What P5 additionally needs, that a P1 detour test does
 * not, is a controlled warm-up: {@code explorationTick}'s own gates (an estimated open volume of at least 1200,
 * a genuinely observed candidate stand) only pass once the sensor has actually mapped the room, so every test
 * here first freezes a real mining-class task in place (the {@code MiningAssistSenseGameTests.freeze} idiom) and
 * lets the REAL sensor build that picture through ordinary rays before ever assigning the {@code OreDigTask}
 * that reads it -- design's own honesty invariant (I3): occupancy is earned by the sensor, never poked in by
 * the test.</p>
 *
 * <p>Neither test opens {@code explore.frontier} at the shipped default: both install their own DETOUR config
 * with it forced on and {@code frontierMinUtility} lowered well below the shipped default of 1.0 (this
 * fixture's flat, single-floor cavern gives every candidate a small {@code unknownFrac} and zero sightings, so
 * its utility never approaches the tuned default -- the same caution the contract itself calls out). Every
 * other GameTest in the suite keeps running with {@code explore.frontier} at its shipped-off default, so this
 * file alone exercises the new trigger.</p>
 */
public final class OreDigFrontierGameTests {

    private static final int SHELL = 3;
    private static final BlockState STONE = Blocks.STONE.getDefaultState();
    private static final BlockState AIR = Blocks.AIR.getDefaultState();
    private static final String ENV_PREFIX = "aibot-gametest:ore_dig_frontier_game_tests_";

    // Half-extent of the main open cavern both tests spawn the bot at the centre of: big enough that the
    // estimated open volume (FreeRunStats.volume, design 5.4's "open volume >= 1200") reliably clears the
    // trigger's own gate once the sensor has mapped it, and that a candidate 12 blocks out (contract's own
    // "10-15 blocks, well inside the real hop-cap headroom") sits comfortably short of its wall.
    private static final int CAVERN_HALF = 13;
    private static final int CAVERN_HEIGHT = 7;
    // Ticks of a frozen, real MISSION-origin task the sensor is given to map the cavern before the real
    // OreDigTask (and explorationTick's own gates) ever sees it: SphereSchedule.LATTICE_SIZE (2048) rays per
    // sweep at 256 rays/tick is 8 ticks/sweep, so this is dozens of sweeps of a static, wide-open room.
    private static final int WARMUP_TICKS = 450;
    // The LATER, ordinary P1 detour (stage 6/7) fires only once the bot's own ongoing strip-mine ladder has
    // walked it away from the frontier anchor by however much real admission-radius/strip-drift timing
    // happens to cost that run (observed up to ~14 blocks); after that detour resolves, the bot resumes
    // wherever its strip-mine task naturally continues, not by teleporting back to the original anchor
    // block. A bounded Chebyshev-XZ window (comfortably above the observed drift) still proves the bot came
    // back to the same vicinity instead of ending up lost, without depending on the ambient ladder's
    // incidental direction; the tighter exact-anchor check right after the frontier excursion's own RETURN
    // phase (before any ordinary strip-mining resumes) is unaffected and still checked exactly.
    private static final int LATER_DETOUR_RETURN_TOLERANCE_XZ = 24;

    // ---------------------------------------------------------------------------------------------
    // 1. Waypoints never leave the observed cavern for a shorter, unobserved side corridor (design 5.4's own
    //    stated residual: "the executor's route between two nearby waypoints may still shortcut through
    //    unobserved cells").
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = ENV_PREFIX + "frontier_waypoints_stay_near_observed_path_with_shorter_unobserved_corridor",
            maxTicks = 1600)
    public void frontierWaypointsStayNearObservedPathWithShorterUnobservedCorridor(TestContext context) {
        Harness h = new Harness(context);
        Room cavern = h.newRoom(10, -CAVERN_HALF, CAVERN_HALF, -CAVERN_HALF, CAVERN_HALF, CAVERN_HEIGHT);
        // The "shorter unobserved corridor... branching toward a large open cavern" (design 5.4): a second,
        // independently sealed Room (its own stone shell, per the Room constructor design shares with every
        // other GameTest fixture in this suite), separated from the mapped cavern by a solid gap several
        // blocks thick. It is never opened, so ObservedOccupancy can never see any of it: this is what proves
        // "candidates/ranking use observed geometry only" end to end, not just in ObservedGraphSearchTest's
        // fake Environment. dx 18-26 is 9 cells wide (the contract's own "9-block side corridor" flavour); the
        // wide dz range is the "large open cavern" beyond it, sized to look tempting (a high unknownFrac / a
        // lot of open volume) to anything that could see into it.
        Room hidden = h.newRoom(10, 18, 26, -6, 6, 8);
        List<BlockPos> hiddenSample = List.of(
                hidden.at(18, 0, -6), hidden.at(26, 0, 6), hidden.at(22, 3, 0), hidden.at(18, 5, 6), hidden.at(26, 1, -6));

        AIPlayerEntity bot = h.spawn("OreDigFrontierWaypointsGT", cavern, 0, 0);
        h.enableFrontier(bot, 256, 0.02D);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        UUID id = bot.getUuid();
        OreDigTask[] task = new OreDigTask[1];
        int[] stage = {0};
        int[] stageStart = {0};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            int tick = ++h.tick;
            switch (stage[0]) {
                case 0 -> {
                    // A frozen, real MISSION-origin mining task: MiningAssistCoordinator senses for it exactly as
                    // it would for the real mission that follows, but it never moves, so the sensor maps a static
                    // room instead of chasing a bot that is also digging (MiningAssistSenseGameTests's idiom).
                    h.assertStrict(bot, "ore_dig_frontier_waypoints_warmup");
                    freeze(bot, TaskOrigin.Kind.MISSION, "gametest_frontier_waypoints_warmup");
                    stage[0] = 1;
                    stageStart[0] = tick;
                }
                case 1 -> {
                    if (tick - stageStart[0] < WARMUP_TICKS) {
                        return;
                    }
                    MiningAssistState warm = MiningAssistRegistry.getIfPresent(id);
                    h.require(warm != null, "the frozen warm-up task never produced assist state");
                    ObservedOccupancy occ = warm.occupancyIfPresent();
                    h.require(occ != null, "no occupancy window after " + WARMUP_TICKS + " warm-up ticks");
                    h.require(occ.get(cavern.at(12, 0, 0)) == ObservedOccupancy.AIR,
                            "the cavern's own interior was not mapped after warm-up: (12,0,0) is "
                                    + ObservedOccupancy.stateName(occ.get(cavern.at(12, 0, 0))));
                    for (BlockPos pos : hiddenSample) {
                        h.require(occ.get(pos) == ObservedOccupancy.UNKNOWN,
                                "fixture error: sealed cavern cell " + pos.toShortString()
                                        + " was already observed before the mission even started");
                    }
                    // Swap the frozen warm-up task for the real mission: TaskManager.assign aborts the frozen
                    // task first (design's own single-active-task discipline), so this is a clean hand-off, not
                    // a second task running alongside the first.
                    task[0] = new OreDigTask(Set.of(Blocks.COAL_ORE), 40);
                    TaskManager.INSTANCE.assign(bot, task[0],
                            TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_frontier_waypoints"));
                    stage[0] = 2;
                    stageStart[0] = tick;
                }
                case 2 -> {
                    requireNotFailed(h, task[0]);
                    MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
                    boolean walking = state != null && state.detourOwner() == task[0]
                            && state.detourPhase() == DetourPhase.FRONTIER_WALK;
                    if (walking) {
                        stage[0] = 3;
                        stageStart[0] = tick;
                        // fall through: this tick's own position/occupancy must satisfy the invariant too.
                    } else {
                        h.require(tick - stageStart[0] < 150,
                                "explorationTick never started a FRONTIER excursion from an already-mapped, wide"
                                        + " open cavern with no ore anywhere and frontierMinUtility forced low");
                        return;
                    }
                    checkFrontierWalkInvariant(h, state, bot, cavern, hidden);
                }
                case 3 -> {
                    requireNotFailed(h, task[0]);
                    MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
                    h.require(state != null, "assist state disappeared mid frontier excursion");
                    if (state.detourOwner() == task[0] && state.detourPhase() == DetourPhase.FRONTIER_WALK) {
                        checkFrontierWalkInvariant(h, state, bot, cavern, hidden);
                        h.require(tick - stageStart[0] < 500, "FRONTIER_WALK never left within budget");
                        return;
                    }
                    // The walk ended (arrival ran): with nothing to sight anywhere in this fixture the excursion
                    // is unproductive, so this should be RETURN, not a cleared tuple from rebaseCursorHere.
                    h.require(state.detourOwner() == task[0] && state.detourPhase() == DetourPhase.RETURN,
                            "the frontier excursion left FRONTIER_WALK for " + (state.detourOwner() == task[0]
                                    ? state.detourPhase() : "no detour") + ", expected RETURN (nothing in this"
                                    + " fixture could make it productive)");
                    stage[0] = 4;
                    stageStart[0] = tick;
                }
                case 4 -> {
                    requireNotFailed(h, task[0]);
                    // Final canary: after the whole excursion, the sealed cavern the search could never legally
                    // route through is still exactly as unobserved as it was before the mission started.
                    MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
                    if (state != null && state.detourOwner() == task[0]) {
                        h.require(tick - stageStart[0] < 400, "RETURN never finished within budget");
                        return;
                    }
                    ObservedOccupancy occ = state == null ? null : state.occupancyIfPresent();
                    if (occ != null) {
                        for (BlockPos pos : hiddenSample) {
                            h.require(occ.get(pos) == ObservedOccupancy.UNKNOWN,
                                    "the sealed cavern cell " + pos.toShortString()
                                            + " was observed at some point during the excursion");
                        }
                    }
                    h.assertStrict(bot, "ore_dig_frontier_waypoints_end");
                    h.pass();
                }
                default -> {
                }
            }
        }));
    }

    /**
     * The one invariant both the letter of design 5.4 and this file's own name are about: whatever cell the bot
     * is standing in while FRONTIER_WALK is live was actually observed (never UNKNOWN -- {@code
     * ObservedGraphSearch}'s route can only ever be built from observed-standable cells), and it is still inside
     * the mapped cavern, never the sealed room a shorter but unobserved route could tempt a buggy executor into
     * (contract's own residual risk).
     */
    private static void checkFrontierWalkInvariant(Harness h, MiningAssistState state, AIPlayerEntity bot,
            Room cavern, Room hidden) {
        BlockPos feet = bot.getBlockPos();
        h.require(cavern.contains(feet),
                "FRONTIER_WALK left the mapped cavern at " + feet.toShortString()
                        + " -- the observed corridor's own path never goes there");
        h.require(!hidden.contains(feet),
                "FRONTIER_WALK entered the sealed, never-observed room at " + feet.toShortString()
                        + ": the executor shortcut through unobserved cells");
        ObservedOccupancy occ = state.occupancyIfPresent();
        h.require(occ != null && occ.get(feet) != ObservedOccupancy.UNKNOWN,
                "FRONTIER_WALK is standing on a cell (" + feet.toShortString() + ") that was never observed");
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Design 10's own "done when" bar: walks to a frontier, mines a valuable, returns (10, 5.4's "same...
    //    return"), and never selects an unobserved or lava-adjacent target.
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = ENV_PREFIX + "frontier_excursion_mines_a_valuable_and_returns_to_anchor", maxTicks = 2400)
    public void frontierExcursionMinesAValuableAndReturnsToAnchor(TestContext context) {
        Harness h = new Harness(context);
        Room cavern = h.newRoom(60, -CAVERN_HALF, CAVERN_HALF, -CAVERN_HALF, CAVERN_HALF, CAVERN_HEIGHT);
        // A single valuable, sealed on all 6 faces inside its own little pillar (the hub()/x-ray-canary idiom
        // this whole suite uses) well off the frontier's own +X axis (z=-3, not z=0) so it neither blocks nor
        // biases ObservedGraphSearch's route to the frontier candidate. Deliberately on the NORTH (-Z) side of
        // the anchor, not the south: with explore.legChooser off, OreDigTask's own first strip leg always
        // starts along STRIP_DIRS[0] = Direction.NORTH (stripDirIndex defaults to 0), so once the mission
        // resumes stripMine after the frontier excursion's RETURN and its own 600-tick unproductive cooldown,
        // the strip actually walks TOWARD this pillar and DetourPolicy's maxRadius (measured from the bot's
        // own live position every recheck, not a fixed point) can catch it -- placing it south, opposite that
        // deterministic direction, would have the strip walk permanently away and this test would never see a
        // P1 detour at all, no matter how long it waits. It stays sealed through the whole excursion (so the
        // excursion's own sightings count never reaches design 5.4's productive threshold of 2) and is only
        // exposed once the bot is verifiably back at the anchor -- proving the diamond is picked up by the
        // ORDINARY P1 opportunistic detour on a later tick, never folded into the frontier excursion.
        BlockPos ore = cavern.at(5, 1, -3);
        cavern.set(5, 0, -3, Blocks.STONE);
        cavern.set(5, 1, -3, Blocks.DIAMOND_ORE);
        cavern.set(5, 2, -3, Blocks.STONE);
        cavern.set(4, 1, -3, Blocks.STONE); // the face opened later; the approach stand is (4,0,-3), one step further out
        cavern.set(6, 1, -3, Blocks.STONE);
        cavern.set(5, 1, -2, Blocks.STONE);
        cavern.set(5, 1, -4, Blocks.STONE);
        for (Direction direction : Direction.values()) {
            BlockState neighbour = cavern.world.getBlockState(ore.offset(direction));
            h.require(!neighbour.isAir(), "fixture error: the sealed valuable at " + ore.toShortString()
                    + " touches air on its " + direction + " face before the excursion ever runs");
        }
        // A lava hazard well off the frontier's own axis: never a candidate itself (SafeGate/frontierStandable
        // must refuse anything within the configured lava-clear radius of it), and never anywhere near the
        // accepted route or the diamond, so it only ever proves a negative.
        BlockPos lava = cavern.at(-9, -1, -9);
        cavern.world.setBlockState(lava, Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);

        AIPlayerEntity bot = h.spawn("OreDigFrontierExcursionGT", cavern, 0, 0);
        h.enableFrontier(bot, 256, 0.02D);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        UUID id = bot.getUuid();
        BlockPos anchor = bot.getBlockPos();
        OreDigTask[] task = new OreDigTask[1];
        int[] stage = {0};
        int[] stageStart = {0};
        boolean[] sawApproachOrMineAfterReturn = {false};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            int tick = ++h.tick;
            switch (stage[0]) {
                case 0 -> {
                    h.assertStrict(bot, "ore_dig_frontier_excursion_warmup");
                    freeze(bot, TaskOrigin.Kind.MISSION, "gametest_frontier_excursion_warmup");
                    stage[0] = 1;
                    stageStart[0] = tick;
                }
                case 1 -> {
                    if (tick - stageStart[0] < WARMUP_TICKS) {
                        return;
                    }
                    MiningAssistState warm = MiningAssistRegistry.getIfPresent(id);
                    h.require(warm != null, "the frozen warm-up task never produced assist state");
                    ObservedOccupancy occ = warm.occupancyIfPresent();
                    h.require(occ != null, "no occupancy window after " + WARMUP_TICKS + " warm-up ticks");
                    h.require(occ.get(cavern.at(12, 0, 0)) == ObservedOccupancy.AIR,
                            "the cavern's own interior was not mapped after warm-up");
                    h.require(!warm.sightings().contains(ore),
                            "the sealed diamond was already sighted before the mission started (fixture leak)");
                    task[0] = new OreDigTask(Set.of(Blocks.COAL_ORE), 40);
                    TaskManager.INSTANCE.assign(bot, task[0],
                            TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_frontier_excursion"));
                    stage[0] = 2;
                    stageStart[0] = tick;
                }
                case 2 -> {
                    requireNotFailed(h, task[0]);
                    MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
                    boolean walking = state != null && state.detourOwner() == task[0]
                            && state.detourPhase() == DetourPhase.FRONTIER_WALK;
                    if (!walking) {
                        h.require(tick - stageStart[0] < 150,
                                "explorationTick never started a FRONTIER excursion from an already-mapped cavern"
                                        + " with frontierMinUtility forced low");
                        return;
                    }
                    stage[0] = 3;
                    stageStart[0] = tick;
                    checkFrontierWalkSafety(h, state, bot, cavern, lava);
                }
                case 3 -> {
                    requireNotFailed(h, task[0]);
                    MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
                    h.require(state != null, "assist state disappeared mid frontier excursion");
                    if (state.detourOwner() == task[0] && state.detourPhase() == DetourPhase.FRONTIER_WALK) {
                        checkFrontierWalkSafety(h, state, bot, cavern, lava);
                        h.require(tick - stageStart[0] < 500, "FRONTIER_WALK never left within budget");
                        return;
                    }
                    // The panorama burst at arrival can see at most the sealed diamond (still 0 -- it is sealed)
                    // and nothing else in this fixture, so the excursion must be unproductive: RETURN, not a
                    // cleared tuple from rebaseCursorHere.
                    h.require(state.detourOwner() == task[0] && state.detourPhase() == DetourPhase.RETURN,
                            "the excursion left FRONTIER_WALK for " + (state.detourOwner() == task[0]
                                    ? state.detourPhase() : "no detour") + ", expected RETURN");
                    stage[0] = 4;
                    stageStart[0] = tick;
                }
                case 4 -> {
                    requireNotFailed(h, task[0]);
                    MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
                    boolean stillReturning = state != null && state.detourOwner() == task[0];
                    if (stillReturning) {
                        h.require(tick - stageStart[0] < 400, "RETURN from the frontier excursion never finished");
                        return;
                    }
                    h.require(bot.getBlockPos().equals(anchor),
                            "the bot did not return to the exact anchor after the frontier excursion: at "
                                    + bot.getBlockPos().toShortString() + ", anchor " + anchor.toShortString());
                    // The frontier excursion's own design-5.4 assertions (waypoints stayed observed, arrival ran
                    // the panorama burst, an unproductive excursion walked RETURN and landed on the exact anchor)
                    // are already proven by this point. Switch explore.frontier back off now, before revealing the
                    // diamond: once this mission's own frontierCooldownUntilServerTick lapses, a second frontier
                    // excursion would otherwise compete with the ordinary P1 opportunistic detour for hook 9/14's
                    // shared tick (hook 9 runs first, but only once MIN_TASK_AGE_TICKS-gated; hook 14 has no such
                    // gate) and could walk the bot away from the very valuable this test still needs to watch get
                    // mined. P1 itself (hook 9, unaffected by this switch) keeps running normally.
                    h.disableFrontier(256);
                    // Reveal the sealed diamond now, well after the excursion is over: the west face becomes the
                    // exposed one, with (4,0,-3) -- one open-floor step further west -- as its ordinary stand.
                    cavern.world.setBlockState(cavern.at(4, 1, -3), AIR, Block.NOTIFY_ALL);
                    stage[0] = 5;
                    stageStart[0] = tick;
                }
                case 5 -> {
                    requireNotFailed(h, task[0]);
                    MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
                    h.require(state != null, "assist state disappeared after the excursion returned");
                    if (state.sightings().contains(ore)) {
                        stage[0] = 6;
                        stageStart[0] = tick;
                        return;
                    }
                    h.require(tick - stageStart[0] < 200,
                            "the newly exposed diamond was never sighted by the ordinary running sensor");
                }
                case 6 -> {
                    requireNotFailed(h, task[0]);
                    if (cavern.world.getBlockState(ore).isOf(Blocks.DIAMOND_ORE)) {
                        MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
                        if (state != null && state.detourOwner() == task[0]
                                && (state.detourPhase() == DetourPhase.APPROACH || state.detourPhase() == DetourPhase.MINE)) {
                            sawApproachOrMineAfterReturn[0] = true;
                            checkNeverLavaAdjacent(h, bot, lava);
                        }
                        h.require(tick - stageStart[0] < 700,
                                "the now-visible diamond, well within an ordinary P1 detour's reach, was never mined"
                                        + " by a LATER opportunistic detour after the frontier excursion returned"
                                        + " [diag: sighted=" + (state != null && state.sightings().contains(ore))
                                        + " bot=" + bot.getBlockPos().toShortString()
                                        + " ore=" + ore.toShortString()
                                        + " anchor=" + anchor.toShortString()
                                        + " chebyshevXZ=" + Math.max(
                                                Math.abs(ore.getX() - bot.getBlockPos().getX()),
                                                Math.abs(ore.getZ() - bot.getBlockPos().getZ()))
                                        + " dy=" + (ore.getY() - bot.getBlockPos().getY())
                                        + " eyeDist=" + bot.getEyePos().distanceTo(
                                                net.minecraft.util.math.Vec3d.ofCenter(ore))
                                        + "]");
                        return;
                    }
                    h.require(sawApproachOrMineAfterReturn[0],
                            "the diamond was mined without this test ever observing the ORE-kind APPROACH/MINE"
                                    + " phase that design 5.4 says must come strictly after the frontier RETURN");
                    stage[0] = 7;
                    stageStart[0] = tick;
                }
                case 7 -> {
                    requireNotFailed(h, task[0]);
                    MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
                    boolean stillOnDetour = state != null && state.detourOwner() == task[0];
                    if (stillOnDetour) {
                        h.require(tick - stageStart[0] < 500, "the ORE-kind detour that mined the diamond never finished");
                        return;
                    }
                    int driftXZ = Math.max(Math.abs(bot.getBlockPos().getX() - anchor.getX()),
                            Math.abs(bot.getBlockPos().getZ() - anchor.getZ()));
                    h.require(driftXZ <= LATER_DETOUR_RETURN_TOLERANCE_XZ,
                            "the bot did not return near the anchor after mining the diamond: at "
                                    + bot.getBlockPos().toShortString() + ", anchor " + anchor.toShortString()
                                    + ", driftXZ=" + driftXZ);
                    h.assertStrict(bot, "ore_dig_frontier_excursion_end");
                    h.pass();
                }
                default -> {
                }
            }
        }));
    }

    /** During FRONTIER_WALK: never an unobserved cell, and never within the lava clearance (design 5.4, 4.4). */
    private static void checkFrontierWalkSafety(Harness h, MiningAssistState state, AIPlayerEntity bot,
            Room cavern, BlockPos lava) {
        BlockPos feet = bot.getBlockPos();
        h.require(cavern.contains(feet),
                "FRONTIER_WALK left the mapped cavern at " + feet.toShortString());
        ObservedOccupancy occ = state.occupancyIfPresent();
        h.require(occ != null && occ.get(feet) != ObservedOccupancy.UNKNOWN,
                "FRONTIER_WALK is standing on a cell (" + feet.toShortString() + ") that was never observed");
        checkNeverLavaAdjacent(h, bot, lava);
    }

    private static void checkNeverLavaAdjacent(Harness h, AIPlayerEntity bot, BlockPos lava) {
        int lavaClearRadius = MiningAssistRuntime.config().detour().lavaClearRadius();
        BlockPos feet = bot.getBlockPos();
        int chebyshev = Math.max(Math.max(Math.abs(feet.getX() - lava.getX()), Math.abs(feet.getY() - lava.getY())),
                Math.abs(feet.getZ() - lava.getZ()));
        h.require(chebyshev > lavaClearRadius,
                "the bot is within the configured lava-clear radius (" + lavaClearRadius + ") of the hazard at "
                        + lava.toShortString() + ": stand " + feet.toShortString());
    }

    // ---------------------------------------------------------------------------------------------
    // Shared fixture plumbing
    // ---------------------------------------------------------------------------------------------

    private static void requireNotFailed(Harness h, OreDigTask task) {
        h.require(task.state() != TaskState.FAILED && task.state() != TaskState.CANCELLED,
                "mission ended as " + task.state() + ":" + task.failureReason());
    }

    /** A real mining-class task that is the bot's active task but does nothing: paused in place, exactly the
     *  {@code MiningAssistSenseGameTests.freeze} idiom this file's own javadoc points to. */
    private static MineTask freeze(AIPlayerEntity bot, TaskOrigin.Kind kind, String reason) {
        MineTask task = new MineTask(Blocks.OBSIDIAN, 1);
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(kind, reason));
        task.pause(bot);
        return task;
    }

    /**
     * DETOUR mode (design M4/M38, {@code harnessOff=true}) with {@code explore.frontier} forced on and {@code
     * explore.frontierMinUtility} lowered from the shipped default of 1.0: this fixture's flat, single-floor
     * cavern gives every candidate zero sightings and a small unknownFrac, so its utility never approaches the
     * tuned default (the contract's own warning: "check the default of 1.0 against the fixture's actual
     * utility before assuming it fires"). Every other GameTest in the suite keeps {@code explore.frontier} at
     * its shipped-off default; only this file's own config ever turns it on.
     */
    private static MiningAssistConfig frontierConfig(int raysPerTick, double frontierMinUtility) {
        JsonObject sense = new JsonObject();
        sense.addProperty("raysPerTick", raysPerTick);
        JsonObject explore = new JsonObject();
        explore.addProperty("frontier", true);
        explore.addProperty("frontierMinUtility", frontierMinUtility);
        JsonObject section = new JsonObject();
        section.add("sense", sense);
        section.add("explore", explore);
        JsonObject root = new JsonObject();
        root.add(MiningAssistConfig.FILE_SECTION, section);
        return MiningAssistConfig.parse(root, key -> null, AssistMode.DETOUR, true);
    }

    /**
     * Same DETOUR/{@code harnessOff=true} base as {@link #frontierConfig}, but with {@code explore.frontier}
     * switched back off: used once the frontier excursion's own design-5.4 assertions (waypoints, arrival,
     * exact-anchor return) are already proven, to let the ordinary P1 opportunistic detour (hook 9, which always
     * runs before the frontier trigger, hook 14) have every remaining tick to itself while the test checks
     * whether it picks up a separately-revealed valuable -- without a second frontier excursion competing for
     * the same tick once this mission's own {@code frontierCooldownUntilServerTick} eventually lapses.
     */
    private static MiningAssistConfig noFrontierConfig(int raysPerTick) {
        JsonObject sense = new JsonObject();
        sense.addProperty("raysPerTick", raysPerTick);
        JsonObject explore = new JsonObject();
        explore.addProperty("frontier", false);
        JsonObject section = new JsonObject();
        section.add("sense", sense);
        section.add("explore", explore);
        JsonObject root = new JsonObject();
        root.add(MiningAssistConfig.FILE_SECTION, section);
        return MiningAssistConfig.parse(root, key -> null, AssistMode.DETOUR, true);
    }

    /** A sealed stone box with an air interior ({@code MiningAssistSenseGameTests.Room}'s pattern), plus a
     *  {@link #contains(BlockPos)} query the invariant checks in this file need and the sibling files do not. */
    private static final class Room {
        final ServerWorld world;
        final BlockPos feet;
        private final int minDx;
        private final int maxDx;
        private final int minDz;
        private final int maxDz;
        private final int height;

        Room(TestContext context, int relY, int minDx, int maxDx, int minDz, int maxDz, int height) {
            this.world = context.getWorld();
            this.feet = context.getAbsolutePos(new BlockPos(3, relY, 3)).toImmutable();
            this.minDx = minDx;
            this.maxDx = maxDx;
            this.minDz = minDz;
            this.maxDz = maxDz;
            this.height = height;
            fill(minDx - SHELL, -SHELL, minDz - SHELL, maxDx + SHELL, height - 1 + SHELL, maxDz + SHELL, STONE);
            fill(minDx, 0, minDz, maxDx, height - 1, maxDz, AIR);
            discardEntities();
        }

        void clear() {
            fill(minDx - SHELL, -SHELL, minDz - SHELL, maxDx + SHELL, height - 1 + SHELL, maxDz + SHELL, AIR);
            discardEntities();
        }

        private void discardEntities() {
            BlockPos low = at(minDx - SHELL, -SHELL, minDz - SHELL);
            BlockPos high = at(maxDx + SHELL + 1, height + SHELL, maxDz + SHELL + 1);
            Box box = new Box(low.getX(), low.getY(), low.getZ(), high.getX(), high.getY(), high.getZ());
            for (Entity entity : world.getEntitiesByClass(Entity.class, box, e -> !(e instanceof PlayerEntity))) {
                entity.discard();
            }
        }

        BlockPos at(int dx, int dy, int dz) {
            return feet.add(dx, dy, dz);
        }

        void set(int dx, int dy, int dz, Block block) {
            world.setBlockState(at(dx, dy, dz), block.getDefaultState(), Block.NOTIFY_ALL);
        }

        /** True when {@code pos} is inside this room's own carved-out interior (not its shell). */
        boolean contains(BlockPos pos) {
            int dx = pos.getX() - feet.getX();
            int dy = pos.getY() - feet.getY();
            int dz = pos.getZ() - feet.getZ();
            return dx >= minDx && dx <= maxDx && dy >= 0 && dy <= height - 1 && dz >= minDz && dz <= maxDz;
        }

        private void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        world.setBlockState(at(x, y, z), state, Block.NOTIFY_ALL);
                    }
                }
            }
        }
    }

    /** Cleanup-on-failure, strict-capability and DETOUR-mode config plumbing shared by every test. */
    private static final class Harness {
        final TestContext context;
        final List<String> bots = new ArrayList<>();
        final List<Room> rooms = new ArrayList<>();
        final List<UUID> forced = new ArrayList<>();
        MiningAssistConfig restoreConfig;
        boolean tpsOverridden;
        boolean done;
        int tick;

        Harness(TestContext context) {
            this.context = context;
        }

        Room newRoom(int relY, int minDx, int maxDx, int minDz, int maxDz, int height) {
            Room room = new Room(context, relY, minDx, maxDx, minDz, maxDz, height);
            rooms.add(room);
            return room;
        }

        AIPlayerEntity spawn(String name, Room room, int dx, int dz) {
            ServerWorld world = room.world;
            BlockPos feet = room.at(dx, 0, dz);
            bots.add(name);
            var spawned = AIPlayerManager.INSTANCE.spawn(
                    world.getServer(), name, world, Vec3d.ofBottomCenter(feet), 0.0F, 0.0F, GameMode.SURVIVAL);
            if (spawned.isEmpty()) {
                fail("failed to spawn " + name + " (a bot of that name is still alive)");
            }
            AIPlayerEntity bot = spawned.get();
            bot.teleport(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
            bot.setHealth(bot.getMaxHealth());
            bot.getHungerManager().setFoodLevel(20);
            bot.getHungerManager().setSaturationLevel(5.0F);
            return bot;
        }

        /** Installs a DETOUR-mode config with {@code explore.frontier} forced on and force-enables one bot past it. */
        void enableFrontier(AIPlayerEntity bot, int raysPerTick, double frontierMinUtility) {
            if (restoreConfig == null) {
                restoreConfig = MiningAssistRuntime.config();
            }
            MiningAssistRuntime.install(frontierConfig(raysPerTick, frontierMinUtility));
            MiningAssistRuntime.setTestTpsDegraded(Boolean.FALSE);
            tpsOverridden = true;
            MiningAssistRuntime.forceEnable(bot.getUuid());
            forced.add(bot.getUuid());
        }

        /** Switches the already-installed config's {@code explore.frontier} back off (see {@link #noFrontierConfig}). */
        void disableFrontier(int raysPerTick) {
            MiningAssistRuntime.install(noFrontierConfig(raysPerTick));
        }

        void require(boolean condition, String message) {
            if (!condition) {
                fail(message);
            }
        }

        void fail(String message) {
            cleanup();
            context.throwGameTestException(Text.of(message));
        }

        /** Runs one tick of a test body; anything thrown cleans up first so a failure never leaves a bot behind. */
        void guard(Runnable body) {
            try {
                body.run();
            } catch (RuntimeException | Error failure) {
                cleanup();
                throw failure;
            }
        }

        void cleanup() {
            done = true;
            for (String name : new ArrayList<>(bots)) {
                AIPlayerManager.INSTANCE.despawn(context.getWorld().getServer(), name);
            }
            bots.clear();
            for (Room room : rooms) {
                try {
                    room.clear();
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
            rooms.clear();
            for (UUID id : forced) {
                MiningAssistRuntime.clearForced(id);
            }
            forced.clear();
            if (restoreConfig != null) {
                MiningAssistRuntime.install(restoreConfig);
                restoreConfig = null;
            }
            if (tpsOverridden) {
                MiningAssistRuntime.setTestTpsDegraded(null);
                tpsOverridden = false;
            }
        }

        void pass() {
            cleanup();
            context.complete();
        }

        void assertStrict(AIPlayerEntity bot, String label) {
            require(io.github.zoyluo.aibot.AIBotConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                    "GameTest must run under strict_survival, got " + io.github.zoyluo.aibot.AIBotConfig.get().profile());
            for (PrivilegedCapability capability : PrivilegedCapability.values()) {
                require(!CapabilityRuntime.decide(bot, capability, label).allowed(),
                        "strict_survival unexpectedly allowed " + capability);
            }
        }
    }
}

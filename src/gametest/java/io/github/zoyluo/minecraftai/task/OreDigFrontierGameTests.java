package io.github.zoyluo.minecraftai.task;

import com.google.gson.JsonObject;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.assist.AssistMode;
import io.github.zoyluo.minecraftai.mining.assist.DetourPhase;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistConfig;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistRegistry;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistRuntime;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistState;
import io.github.zoyluo.minecraftai.mining.assist.ObservedOccupancy;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import io.github.zoyluo.minecraftai.task.SensingArena.Room;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
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
 * <p>The test follows the {@code OreDigOpportunisticGameTests} Harness/Room idiom
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

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final String ENV_PREFIX = "minecraftai-gametest:ore_dig_frontier_game_tests_";

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
    public void frontierWaypointsStayNearObservedPathWithShorterUnobservedCorridor(GameTestHelper context) {
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
        BlockPos anchor = bot.blockPosition();
        // The absent deep diamond target gives observed cavern stands a real Y-band score after
        // warm-up, so the lowered positive threshold exercises selection without relying on
        // residual UNKNOWN cells that a complete sensor sweep may legitimately eliminate.
        h.enableFrontier(bot, 256, 0.02D);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        UUID id = bot.getUUID();
        OreDigTask[] task = new OreDigTask[1];
        int[] stage = {0};
        int[] stageStart = {0};

        context.failIfEver(() -> h.guard(() -> {
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
                    task[0] = new OreDigTask(Set.of(Blocks.DEEPSLATE_DIAMOND_ORE), 40);
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
                    h.require(bot.blockPosition().equals(anchor),
                            "the unproductive frontier excursion did not return to its exact anchor: at "
                                    + bot.blockPosition().toShortString() + ", anchor " + anchor.toShortString());
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
        BlockPos feet = bot.blockPosition();
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

    /** Cleanup-on-failure, strict-capability and DETOUR-mode config plumbing shared by every test. */
    private static final class Harness {
        final GameTestHelper context;
        final List<String> bots = new ArrayList<>();
        final List<Room> rooms = new ArrayList<>();
        final List<UUID> forced = new ArrayList<>();
        MiningAssistConfig restoreConfig;
        boolean tpsOverridden;
        boolean done;
        int tick;

        Harness(GameTestHelper context) {
            this.context = context;
        }

        Room newRoom(int relY, int minDx, int maxDx, int minDz, int maxDz, int height) {
            Room room = new Room(context, relY, minDx, maxDx, minDz, maxDz, height);
            rooms.add(room);
            return room;
        }

        AIPlayerEntity spawn(String name, Room room, int dx, int dz) {
            ServerLevel world = room.world;
            BlockPos feet = room.at(dx, 0, dz);
            bots.add(name);
            var spawned = AIPlayerManager.INSTANCE.spawn(
                    world.getServer(), name, world, Vec3.atBottomCenterOf(feet), 0.0F, 0.0F, GameType.SURVIVAL);
            if (spawned.isEmpty()) {
                fail("failed to spawn " + name + " (a bot of that name is still alive)");
            }
            AIPlayerEntity bot = spawned.get();
            bot.teleportTo(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
            bot.setHealth(bot.getMaxHealth());
            bot.getFoodData().setFoodLevel(20);
            bot.getFoodData().setSaturation(5.0F);
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
            MiningAssistRuntime.forceEnable(bot.getUUID());
            forced.add(bot.getUUID());
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
            context.fail(Component.nullToEmpty(message));
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
                AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), name);
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
            context.succeed();
        }

        void assertStrict(AIPlayerEntity bot, String label) {
            require(io.github.zoyluo.minecraftai.MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                    "GameTest must run under strict_survival, got " + io.github.zoyluo.minecraftai.MinecraftAiConfig.get().profile());
            for (PrivilegedCapability capability : PrivilegedCapability.values()) {
                require(!CapabilityRuntime.decide(bot, capability, label).allowed(),
                        "strict_survival unexpectedly allowed " + capability);
            }
        }
    }
}

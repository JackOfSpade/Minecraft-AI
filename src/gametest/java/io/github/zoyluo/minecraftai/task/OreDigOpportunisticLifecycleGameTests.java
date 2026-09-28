package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLogWriter;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.assist.AssistMode;
import io.github.zoyluo.minecraftai.mining.assist.BotEdits;
import io.github.zoyluo.minecraftai.mining.assist.DetourPhase;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistConfig;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistRegistry;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistRuntime;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistState;
import io.github.zoyluo.minecraftai.mining.assist.OreClaims;
import io.github.zoyluo.minecraftai.mining.assist.SenseBudget;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real-Minecraft GameTests for the P1 walk-only opportunistic valuables detour (design {@code d4_r1_detour.md},
 * P1 contract sections C and E): the actual {@code OreDigTask}/{@code OreDigDetourEngine}/{@code
 * DetourStartSelector}/{@code DetourSafetyGate} running a real server, real pathfinding, real mining and real
 * item physics -- never {@code FakeDetourHost}. P1's unit and source-contract lane (commit {@code 05af47c})
 * already proves the engine's phase machine and deadlines against a scripted fake host; this file is the
 * missing "does it actually happen when a real bot mines a real cave" proof, in the style of the sibling files
 * in this package ({@code OreDigPickupGameTests}, {@code MiningCheckpointMissionGameTests}, {@code
 * CreateObsidianMissionRecoveryGameTests}, {@code DangerWatcherLowHealthGameTests}) and of {@code
 * mining.assist.MiningAssistSenseGameTests} (P0), whose sealed-room/forceEnable/environment-JSON fixture idiom
 * this file copies.
 *
 * <p>Every test seals a stone-shelled room (so the sensor's honesty is preserved: a valuable is only ever a
 * ledger sighting, re-proved before any action), installs a DETOUR-mode config for the duration (the shipped
 * default is still SENSE until the orchestrator flips it after this file is green), force-enables the bot past
 * the harness-off gate and pins the TPS verdict healthy, then assigns a real {@code OreDigTask} through {@code
 * TaskManager} with a real (MISSION) origin. Assertions read only what the task/mission layer exposes:
 * {@code task.checkpoint()}, {@code TaskManager.INSTANCE.getActive(bot)}, {@code task.state()}, {@code
 * OreClaims}, block state and inventory contents, and this bot's own structured log lines -- never a private
 * engine field.</p>
 */
public final class OreDigOpportunisticLifecycleGameTests {
    private static final Logger LOG = LoggerFactory.getLogger("minecraftai-detour-gametest");
    private static final int SHELL = 3;
    private static final BlockState STONE = Blocks.STONE.getDefaultState();
    private static final BlockState AIR = Blocks.AIR.getDefaultState();
    private static final Pattern EVENT = Pattern.compile("event=(ore_dig_detour_[a-z_]+)");

    // ---------------------------------------------------------------------------------------------
    // 9. Slow mine, a lost drop and a long return never trip OreDig's own NO_PROGRESS_LIMIT (I14)
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:ore_dig_opportunistic_lifecycle_slow_mine_and_drop_lost_and_long_return_never_trip_no_progress", maxTicks = 4200)
    public void slowMineAndDropLostAndLongReturnNeverTripNoProgress(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(30, -3, 18, -3, 3, 4);
        // Far enough from the anchor (spawn) that the walk-only return is a genuinely long trip, but still well
        // inside sensing range so the fixture does not spend the whole test budget just waiting to be sighted.
        // dy=1 + a solid roof (not dy=0): DetourHostImpl.poseFor's same-level cardinal stands all require
        // hasReliableObservedDropCatch(ore.down()), which can never pass for a floor-level ore (ore.down()
        // is always buried under the room's own solid floor slab). One level up, approachGoalFor's own
        // stand (ore.down().offset(dir), the room's ordinary floor one step to the side) is already open
        // and standable; the roof keeps breakGeometry's overhead check observable.
        BlockPos ore = room.at(15, 1, 0);
        room.set(15, 1, 0, Blocks.DIAMOND_ORE);
        room.set(15, 2, 0, Blocks.STONE);
        // A generous, purely-local emerald supply (dy=1 + roof), placed on the SAME side of spawn as the
        // diamond (a few blocks short of it, never adjacent) rather than the opposite side: the mission's
        // own sensor/detour sweep is a hook of OreDigTask's own tick (design 8.2 hook 9/13), so a mission
        // with nothing nearby to find would wander off via OreDig's 48-block spiral looking for emerald that
        // was never there. Placing the supply beyond MAX_EYE_DISTANCE(14) in the opposite direction would
        // only walk the bot further from the diamond before it is ever detoured to; placing it on the same
        // side keeps every step of that local supply also a step towards the diamond's own sensing range.
        for (int dz = -3; dz <= 3; dz++) {
            room.set(10, 1, dz, Blocks.EMERALD_ORE);
            room.set(10, 2, dz, Blocks.STONE);
        }
        AIPlayerEntity bot = h.spawn("DetourSlowLongGT", room, 0, 0);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        InventoryAction.giveItem(bot, new ItemStack(Items.TORCH, 16));
        h.enableDetourMode();
        h.enableAssist(bot);
        UUID id = bot.getUuid();
        Progress p = new Progress();
        OreDigTask[] task = {null};
        boolean[] fatigued = {false};
        boolean[] everActive = {false};
        boolean[] sawReturn = {false};
        int[] activeSince = {-1};
        int failuresBefore = MiningAssistRuntime.failures().size();

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    h.assertStrict(bot);
                    task[0] = new OreDigTask(Set.of(Blocks.EMERALD_ORE), 40);
                    TaskManager.INSTANCE.assign(bot, task[0], TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_detour_slow_long"));
                    p.assignedAt = p.tick;
                }
                return;
            }
            h.require(task[0].state() != TaskState.FAILED,
                    "the mission failed: " + task[0].failureReason() + " checkpoint=" + task[0].checkpoint());
            h.require(!task[0].failureReason().startsWith("ore_dig_no_progress"),
                    "OreDig's own NO_PROGRESS_LIMIT tripped during a live/returning detour: " + task[0].failureReason());
            DetourPhase phase = phaseOf(id);
            if (!fatigued[0] && phase != DetourPhase.IDLE) {
                // A deliberately slow mine: mining fatigue does not touch movement, so APPROACH/RETURN keep their
                // ordinary pace while every swing is stretched out, exercising the MINE_BEAT_TICKS heartbeat.
                // Amplifier 0 (Fatigue I, calcBlockBreakingDelta's own ~0.3x speed multiplier), not 2 (Fatigue
                // III, ~0.027x): III stretches a single iron-pick diamond_ore swing to roughly 800+ ticks, past
                // even MINE_SWING_TICKS(160), so the engine's own cap always skips the member (mine_timeout)
                // before ever proving a real break under a live heartbeat, and -- far more importantly -- the
                // same 6000-tick effect then keeps afflicting OreDigTask's ORDINARY (non-detour) channel/corridor
                // digging for the rest of the run: that ladder only calls noteProgress() when a break completes
                // (OreDigTask#tickPickupEgressClearance and its neighbours), with no per-swing beat of its own
                // (that convention belongs to OreDigDetourEngine's MINE phase only), so a single ~800-tick stone
                // break trips OreDig's own generic NO_PROGRESS_LIMIT(200) -- confirmed against the real GameTest
                // log (ore_dig_stall_dump/ore_dig_region_head at the exact failure tick showed the miner stuck
                // mid-swing on a plain corridor stone cell, target=none, well after ore_dig_detour_end{breaks=0,
                // reason=done} had already finished cleanly at ticks=216). This is a fixture bug, not an I14
                // violation: the detour's own heartbeat never gapped during its 216 ticks. Fatigue I still slows
                // an iron-pick diamond_ore swing to comfortably above MINE_BEAT_TICKS(50) so a real mid-swing beat
                // is exercised, while staying comfortably under both MINE_SWING_TICKS(160) and, for ordinary
                // stone/ore breaks afterward, NO_PROGRESS_LIMIT(200).
                bot.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 6000, 0));
                fatigued[0] = true;
            }
            if (phase != DetourPhase.IDLE) {
                everActive[0] = true;
                if (activeSince[0] < 0) {
                    activeSince[0] = p.tick;
                }
            }
            if (phase == DetourPhase.RETURN) {
                sawReturn[0] = true;
            }
            if (!everActive[0]) {
                h.require(p.tick - p.assignedAt < 1200, "the opportunistic detour never started for a nearby diamond");
                return;
            }
            if (!sawReturn[0]) {
                h.require(p.tick - activeSince[0] < 1000,
                        "the slow detour never reached RETURN (the long walk home) within its budget");
                return;
            }
            // The invariant under test: however slow the mine or long the return, the mission's own watchdog
            // (200 task ticks without noteProgress) must never see a gap, because the engine beats at least
            // every ~101 ticks (I14) the whole way. Hold this for a good stretch after RETURN was seen, then pass.
            if (p.tick - activeSince[0] < 1600) {
                return;
            }
            h.require(MiningAssistRuntime.failures().size() == failuresBefore,
                    "the coordinator's exception fence fired during the slow/long detour");
            LOG.info("[detour-gametest] slow_long final_phase={} task_state={}", phase, task[0].state());
            h.pass();
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 10. Pause mid-detour resumes to the exact anchor; the strip fields never move
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:ore_dig_opportunistic_lifecycle_pause_mid_detour_resumes_to_anchor", maxTicks = 2000)
    public void pauseMidDetourResumesToAnchor(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(31, -3, 10, -3, 3, 4);
        // dy=1 + a solid roof (not dy=0): see the pose comment on the first test in this file.
        room.set(7, 1, 0, Blocks.DIAMOND_ORE);
        room.set(7, 2, 0, Blocks.STONE);
        AIPlayerEntity bot = h.spawn("DetourPauseGT", room, 0, 0);
        // A stone pick is mandatory for OreDig's own strip/channel through ordinary rock: the channel-tool
        // policy floors every mined block (including the mission's own coal/lapis target) at STONE tier and,
        // for non-ore rock, caps it there too (OreDigTask.failMissingMiningChannelTool ~line 4489), so the
        // mission never has to spend its iron pick on plain corridor stone.
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 8));
        h.enableDetourMode();
        h.enableAssist(bot);
        UUID id = bot.getUuid();
        Progress p = new Progress();
        OreDigTask[] task = {null};
        Map<String, String>[] anchorCheckpoint = new Map[1];
        int[] stage = {0};
        int[] stageStart = {0};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    h.assertStrict(bot);
                    task[0] = new OreDigTask(Set.of(Blocks.COAL_ORE), 1);
                    TaskManager.INSTANCE.assign(bot, task[0], TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_detour_pause"));
                    p.assignedAt = p.tick;
                    stageStart[0] = p.tick;
                }
                return;
            }
            h.require(task[0].state() != TaskState.FAILED, "the mission failed: " + task[0].failureReason());
            switch (stage[0]) {
                case 0 -> {
                    if (phaseOf(id) == DetourPhase.IDLE) {
                        h.require(p.tick - stageStart[0] < 1200, "the opportunistic detour never started");
                        return;
                    }
                    // Capture the checkpoint's published face (the anchor, hook 11) and strip numbers now, mid-flight.
                    anchorCheckpoint[0] = new LinkedHashMap<>(task[0].checkpoint());
                    TaskManager.INSTANCE.pauseFor(bot, "gametest_detour_pause");
                    h.require(TaskManager.INSTANCE.getActive(bot).isEmpty(), "fixture error: pause left an active task");
                    stage[0] = 1;
                    stageStart[0] = p.tick;
                }
                case 1 -> {
                    h.require(phaseOf(id) == DetourPhase.IDLE, "the paused detour is still published as live");
                    if (p.tick - stageStart[0] < 10) {
                        return;
                    }
                    TaskManager.INSTANCE.resumeFromPause(bot);
                    h.require(TaskManager.INSTANCE.getActive(bot).orElse(null) == task[0], "resume did not restore the task");
                    stage[0] = 2;
                    stageStart[0] = p.tick;
                }
                case 2 -> {
                    BlockPos anchor = decode(anchorCheckpoint[0].get("face"));
                    if (!bot.getBlockPos().equals(anchor)) {
                        h.require(p.tick - stageStart[0] < 400, "the resumed bot never walked back to the interrupted anchor "
                                + anchor.toShortString() + " (at " + bot.getBlockPos().toShortString() + ")");
                        return;
                    }
                    Map<String, String> after = task[0].checkpoint();
                    for (String key : new String[] {"direction", "leg", "steps_left", "leg_length"}) {
                        h.require(java.util.Objects.equals(anchorCheckpoint[0].get(key), after.get(key)),
                                "the strip field '" + key + "' moved across a pause/resume: "
                                        + anchorCheckpoint[0].get(key) + " -> " + after.get(key));
                    }
                    LOG.info("[detour-gametest] pause_resume anchor={} resumed_face={}", anchor.toShortString(), after.get("face"));
                    h.pass();
                }
                default -> {
                }
            }
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 11. Restart from a mid-detour checkpoint returns to the anchor, not to the interrupted detour
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:ore_dig_opportunistic_lifecycle_restart_mid_detour_returns_to_anchor", maxTicks = 2100)
    public void restartMidDetourReturnsToAnchor(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(32, -3, 10, -3, 3, 4);
        // dy=1 + a solid roof (not dy=0): see the pose comment on the first test in this file.
        room.set(7, 1, 0, Blocks.DIAMOND_ORE);
        room.set(7, 2, 0, Blocks.STONE);
        AIPlayerEntity bot = h.spawn("DetourRestartGT", room, 0, 0);
        // A stone pick is mandatory for OreDig's own strip/channel through ordinary rock: the channel-tool
        // policy floors every mined block (including the mission's own coal/lapis target) at STONE tier and,
        // for non-ore rock, caps it there too (OreDigTask.failMissingMiningChannelTool ~line 4489), so the
        // mission never has to spend its iron pick on plain corridor stone.
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 8));
        h.enableDetourMode();
        h.enableAssist(bot);
        UUID id = bot.getUuid();
        Progress p = new Progress();
        OreDigTask[] task = {null};
        OreDigTask[] restored = {null};
        BlockPos[] anchor = new BlockPos[1];
        int[] stage = {0};
        int[] stageStart = {0};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    h.assertStrict(bot);
                    task[0] = new OreDigTask(Set.of(Blocks.COAL_ORE), 1);
                    TaskManager.INSTANCE.assign(bot, task[0], TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_detour_restart"));
                    p.assignedAt = p.tick;
                    stageStart[0] = p.tick;
                }
                return;
            }
            switch (stage[0]) {
                case 0 -> {
                    h.require(task[0].state() != TaskState.FAILED, "the mission failed: " + task[0].failureReason());
                    // Wait for the engine to have actually reached MINE (not merely a still-walking APPROACH), so
                    // the checkpoint we freeze really is "mid-detour", not "about to start one".
                    if (phaseOf(id) != DetourPhase.MINE && phaseOf(id) != DetourPhase.POSTBREAK
                            && phaseOf(id) != DetourPhase.SETTLE_DROP) {
                        h.require(p.tick - stageStart[0] < 1200, "the detour never reached MINE");
                        return;
                    }
                    Map<String, String> checkpoint = new LinkedHashMap<>(task[0].checkpoint());
                    anchor[0] = decode(checkpoint.get("face"));
                    task[0].cancel(bot, "gametest_restart");
                    restored[0] = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
                    TaskManager.INSTANCE.assign(bot, restored[0], TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_detour_restarted"));
                    stage[0] = 1;
                    stageStart[0] = p.tick;
                }
                case 1 -> {
                    h.require(restored[0].state() != TaskState.FAILED, "the restarted mission failed: " + restored[0].failureReason());
                    // A fresh instance built from a mid-detour checkpoint: no engine object survived the restart,
                    // so it must walk back to the anchor face like any other restart, never resume the detour.
                    h.require(phaseOf(id) == DetourPhase.IDLE,
                            "a freshly restarted task instance published a live detour it never started");
                    if (!bot.getBlockPos().equals(anchor[0])) {
                        h.require(p.tick - stageStart[0] < 500, "the restarted task never walked back to the anchor "
                                + anchor[0].toShortString() + " (at " + bot.getBlockPos().toShortString() + ")");
                        return;
                    }
                    LOG.info("[detour-gametest] restart anchor={} reached_ticks={}", anchor[0].toShortString(), p.tick - stageStart[0]);
                    h.pass();
                }
                default -> {
                }
            }
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 12/13. fail()/complete() bypass onAbort (I8/M29): the coordinator's orphan cleanup still releases claims
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:ore_dig_opportunistic_lifecycle_fail_mid_detour_releases_claims", maxTicks = 1100)
    public void failMidDetourReleasesClaims(TestContext context) {
        terminalExitReleasesClaims(context, "DetourFailGT", "gametest_detour_fail", true);
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_opportunistic_lifecycle_complete_mid_detour_releases_claims", maxTicks = 1100)
    public void completeMidDetourReleasesClaims(TestContext context) {
        terminalExitReleasesClaims(context, "DetourCompleteGT", "gametest_detour_complete", false);
    }

    private void terminalExitReleasesClaims(TestContext context, String name, String reason, boolean viaFail) {
        Harness h = new Harness(context);
        Room room = h.newRoom(33, -3, 10, -3, 3, 4);
        // A lone ore cell with no same-block neighbour: the vein has exactly one member (the seed itself), so
        // the one claim taken during MINE prep is unambiguously this exact cell. dy=1 + a solid roof (not
        // dy=0): see the pose comment on the first test in this file.
        BlockPos ore = room.at(6, 1, 0);
        room.set(6, 1, 0, Blocks.DIAMOND_ORE);
        room.set(6, 2, 0, Blocks.STONE);
        AIPlayerEntity bot = h.spawn(name, room, 0, 0);
        // A stone pick is mandatory for OreDig's own strip/channel through ordinary rock: the channel-tool
        // policy floors every mined block (including the mission's own coal/lapis target) at STONE tier and,
        // for non-ore rock, caps it there too (OreDigTask.failMissingMiningChannelTool ~line 4489), so the
        // mission never has to spend its iron pick on plain corridor stone.
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 8));
        h.enableDetourMode();
        h.enableAssist(bot);
        UUID id = bot.getUuid();
        String dimensionKey = BotEdits.dimensionKey(room.world);
        UUID otherBot = UUID.randomUUID();
        Progress p = new Progress();
        OreDigTask[] task = {null};
        int[] stage = {0};
        int[] stageStart = {0};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    h.assertStrict(bot);
                    task[0] = new OreDigTask(Set.of(Blocks.COAL_ORE), 1);
                    TaskManager.INSTANCE.assign(bot, task[0], TaskOrigin.of(TaskOrigin.Kind.MISSION, reason));
                    p.assignedAt = p.tick;
                    stageStart[0] = p.tick;
                }
                return;
            }
            switch (stage[0]) {
                case 0 -> {
                    h.require(task[0].state() != TaskState.FAILED, "the mission failed before the fixture reached MINE: "
                            + task[0].failureReason());
                    if (phaseOf(id) != DetourPhase.MINE) {
                        h.require(p.tick - stageStart[0] < 700, "the detour never claimed and started mining the lone ore");
                        return;
                    }
                    int tick = MiningAssistRuntime.serverTick(bot);
                    h.require(OreClaims.heldByOther(dimensionKey, otherBot, ore.asLong(), tick),
                            "fixture error: the engine reached MINE without claiming its seed cell");
                    if (viaFail) {
                        task[0].fail("gametest_forced_fail");
                    } else {
                        task[0].complete();
                    }
                    stage[0] = 1;
                    stageStart[0] = p.tick;
                }
                case 1 -> {
                    TaskState expected = viaFail ? TaskState.FAILED : TaskState.COMPLETED;
                    h.require(task[0].state() == expected, "the direct " + (viaFail ? "fail()" : "complete()")
                            + " call did not stick: " + task[0].state());
                    int tick = MiningAssistRuntime.serverTick(bot);
                    boolean stillClaimed = OreClaims.heldByOther(dimensionKey, otherBot, ore.asLong(), tick);
                    if (stillClaimed) {
                        h.require(p.tick - stageStart[0] < 60,
                                "the coordinator's orphan cleanup never released the claim after a terminal exit "
                                        + "that bypassed onAbort");
                        return;
                    }
                    MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
                    h.require(state == null || state.detourPhase() == DetourPhase.IDLE,
                            "the published detour tuple survived the orphan cleanup: " + (state == null ? "null" : state.detourPhase()));
                    LOG.info("[detour-gametest] terminal_exit via={} released_after_ticks={}",
                            viaFail ? "fail" : "complete", p.tick - stageStart[0]);
                    h.pass();
                }
                default -> {
                }
            }
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 14. The drop chase is contract-bound (walk-only, <= 2 attempts) and never terminal
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:ore_dig_opportunistic_lifecycle_drop_recovery_contract_bound_and_non_terminal", maxTicks = 3200)
    public void dropRecoveryContractBoundAndNonTerminal(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(34, -3, 12, -3, 3, 4);
        // Recoverable: floating in the open, already fully-swept interior air -- every neighbour of the break
        // cell is known, so nothing can ever be excluded from a legal chase. dy=1 + a solid roof (not dy=0):
        // see the pose comment on the first test in this file (the one buried cell this adds is the ore's
        // own roof, not a neighbour the drop could ever roll into).
        room.set(5, 1, 3, Blocks.DIAMOND_ORE);
        room.set(5, 2, 3, Blocks.STONE);
        // Unrecoverable: embedded a full shell layer deep, directly beside the diamond itself (not clear
        // across the room at the original dx=13, room maxDx=12): a cell outside the room's own carved
        // interior box is left as the shell fill's default STONE on every face automatically, so placing the
        // gold at z=4 (one past the interior's own maxDz=3, directly north of the diamond at z=3, the last
        // interior row) needs no explicit sealing loop -- its up, down, east, west and north (z=5)
        // neighbours are already solid by construction, and only the south face (z=3, the diamond's own
        // cell) is ever open. The diamond's own break exposes that face (I1/I2, never x-ray beforehand), so
        // whatever the bot re-proves while it works the diamond, one cell away, decides this sighting --
        // moved as close as the geometry allows after the first attempt (two cells away, dz=2, one
        // aisle further back) still read unknown for the whole run (see below).
        //
        // The original fixture put this at dx=13 (still one shell layer deep, same technique) but clear
        // across the room from the diamond at dx=5, trusting OreDig's own far-ranging spiral search to
        // wander there eventually. Confirmed wrong against the real GameTest log, across three different
        // "keep busy" fixture attempts (no local ore, a solid local supply, a checkerboarded one): every
        // later DetourStartSelector re-proof of the old dx=13 candidate answered unknown (never present,
        // never gone) for the whole ~2700-tick budget regardless, because the corridor's own default
        // direction (branch_leg dir=north, i.e. -z) never actually walks anywhere near dx=13 in that time --
        // this was the room geometry itself, not the busy-work fixture around it, so no amount of "keep the
        // bot nearby" tuning elsewhere could have fixed it. A first reposition (dz=2, two cells short of the
        // diamond) was still not enough: the diamond's own detour lasts only ~70 ticks (ticks=73 confirmed in
        // the real GameTest log) and returns the bot to its blind-tunnel anchor immediately after, so a
        // candidate merely "nearby" only gets the brief, one-shot chance of a stray ray during that visit,
        // not a proof. Directly adjacent (the very next cell along the same sightline the break itself must
        // re-prove) is the closest this candidate can get to the diamond while staying genuinely hidden.
        BlockPos hidden = room.at(5, 1, 4);
        room.set(5, 1, 4, Blocks.GOLD_ORE);
        BlockPos guard = room.at(5, 1, 5); // one cell further into the shell: never a legal walk target either
        AIPlayerEntity bot = h.spawn("DetourDropGT", room, 0, 0);
        // A stone pick is mandatory for OreDig's own strip/channel through ordinary rock: the channel-tool
        // policy floors every mined block (including the mission's own coal/lapis target) at STONE tier and,
        // for non-ore rock, caps it there too (OreDigTask.failMissingMiningChannelTool ~line 4489), so the
        // mission never has to spend its iron pick on plain corridor stone.
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 8));
        h.enableDetourMode(256); // a wider sensor budget, belt-and-braces now the gold sits well within reach
        h.enableAssist(bot);
        UUID id = bot.getUuid();
        Progress p = new Progress();
        OreDigTask[] task = {null};
        int[] activeTicks = {0};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    h.assertStrict(bot);
                    task[0] = new OreDigTask(Set.of(Blocks.LAPIS_ORE), 1);
                    TaskManager.INSTANCE.assign(bot, task[0], TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_detour_drop"));
                    p.assignedAt = p.tick;
                }
                return;
            }
            h.require(task[0].state() != TaskState.FAILED,
                    "an unrecovered drop must never fail the mission: " + task[0].failureReason());
            h.require(!task[0].failureReason().toLowerCase(java.util.Locale.ROOT).contains("unrecovered"),
                    "an unrecovered-drop failure reason appeared: " + task[0].failureReason());
            h.require(room.world.getBlockState(guard).isOf(Blocks.STONE),
                    "the walk-only chase dug through solid rock chasing an unreachable drop");
            if (phaseOf(id) != DetourPhase.IDLE) {
                activeTicks[0]++;
            }
            boolean bothGone = room.world.getBlockState(room.at(5, 1, 2)).isAir()
                    && room.world.getBlockState(hidden).isAir();
            if (!bothGone) {
                h.require(p.tick - p.assignedAt < 2700, "both valuables were never both broken (recoverable="
                        + !room.world.getBlockState(room.at(5, 1, 2)).isAir() + " hidden="
                        + !room.world.getBlockState(hidden).isAir() + ")");
                return;
            }
            // Give the settle logic (up to SETTLE_TOTAL_TICKS=60) and the coordinator a moment to finish quietly.
            if (p.tick - p.assignedAt < 2700 + 100) {
                return;
            }
            h.require(!task[0].failureReason().toLowerCase(java.util.Locale.ROOT).contains("unrecovered"),
                    "an unrecovered-drop failure reason appeared after settling");
            h.require(room.world.getBlockState(guard).isOf(Blocks.STONE), "the chase eventually dug through solid rock");
            LOG.info("[detour-gametest] drop_recovery active_ticks={} diamond_held={} gold_held={}",
                    activeTicks[0], InventoryAction.countItem(bot, Items.DIAMOND), InventoryAction.countItem(bot, Items.GOLD_NUGGET)
                            + InventoryAction.countItem(bot, Items.RAW_GOLD));
            h.pass();
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 15. Two bots, one vein: OreClaims lets exactly one of them actually mine it
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:ore_dig_opportunistic_lifecycle_two_bots_one_vein_exactly_one_breaker", maxTicks = 2500)
    public void twoBotsOneVeinExactlyOneBreaker(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(35, -6, 6, -3, 3, 4);
        // A lone valuable exactly between two symmetric bots: both selectors see the same single candidate, so
        // both engines pick the very same seed cell -- a genuine race for one claim, not two independent finds.
        // dy=1 + a solid roof (not dy=0): see the pose comment on the first test in this file.
        BlockPos ore = room.at(0, 1, 0);
        room.set(0, 1, 0, Blocks.DIAMOND_ORE);
        room.set(0, 2, 0, Blocks.STONE);
        AIPlayerEntity botA = h.spawn("DetourRaceAGT", room, -4, 0);
        AIPlayerEntity botB = h.spawn("DetourRaceBGT", room, 4, 0);
        for (AIPlayerEntity bot : List.of(botA, botB)) {
            // Stone pick for each bot's own channel/corridor bottleneck (see the giveItem comment on the
            // earlier tests in this file); iron pick for the shared diamond vein itself.
            InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
            InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
            InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 8));
        }
        h.enableDetourMode();
        h.enableAssist(botA);
        h.enableAssist(botB);
        UUID idA = botA.getUuid();
        UUID idB = botB.getUuid();
        Progress p = new Progress();
        OreDigTask[] taskA = {null};
        OreDigTask[] taskB = {null};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(botA, p) && h.settle(botB, p)) {
                    h.assertStrict(botA);
                    h.assertStrict(botB);
                    taskA[0] = new OreDigTask(Set.of(Blocks.COAL_ORE), 1);
                    taskB[0] = new OreDigTask(Set.of(Blocks.COAL_ORE), 1);
                    TaskManager.INSTANCE.assign(botA, taskA[0], TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_detour_race_a"));
                    TaskManager.INSTANCE.assign(botB, taskB[0], TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_detour_race_b"));
                    p.assignedAt = p.tick;
                }
                return;
            }
            h.require(taskA[0].state() != TaskState.FAILED, "bot A's mission failed: " + taskA[0].failureReason());
            h.require(taskB[0].state() != TaskState.FAILED, "bot B's mission failed: " + taskB[0].failureReason());
            boolean mined = room.world.getBlockState(ore).isAir();
            if (!mined) {
                h.require(p.tick - p.assignedAt < 1900, "neither bot ever mined the shared vein");
                return;
            }
            // Give both engines time to finish their own settle/return so the claim is released either way.
            if (p.tick - p.assignedAt < 1900 + 150) {
                return;
            }
            int tick = MiningAssistRuntime.serverTick(botA);
            String dimensionKey = BotEdits.dimensionKey(room.world);
            h.require(!OreClaims.heldByOther(dimensionKey, UUID.randomUUID(), ore.asLong(), tick),
                    "the shared cell's claim was never released after the vein was settled");
            int total = InventoryAction.countItem(botA, Items.DIAMOND) + InventoryAction.countItem(botB, Items.DIAMOND);
            LOG.info("[detour-gametest] two_bots mined_by_a={} mined_by_b={} total_diamonds={}",
                    taskA[0].checkpoint().get("delivered"), taskB[0].checkpoint().get("delivered"), total);
            h.pass();
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 16. Inventory below the reserve stops a detour from starting; freeing space allows it
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:ore_dig_opportunistic_lifecycle_inventory_reserve_stops_detour", maxTicks = 2300)
    public void inventoryReserveStopsDetour(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(36, -3, 8, -3, 3, 4);
        // dy=1 + a solid roof (not dy=0): see the pose comment on the first test in this file.
        //
        // KNOWN REMAINING ISSUE (not resolved, documented per the debugging brief rather than guessed at
        // further): this test is still flaky. No local coal at all (matching this file's other long-idle
        // fixtures, e.g. pauseMidDetourResumesToAnchor) reliably fixes the diamond's sighting for MOST of
        // the run, and is a real improvement over the original fixture's own 7-cell coal column (which
        // reliably went stale by the time inventory space freed at tick ~900, confirmed in the real
        // GameTest log: capacity -> unknown, never present again). But two things have NOT been pinned
        // down with the same confidence: (a) whether "no local ore" alone is fully sufficient across runs,
        // since one repositioning experiment (dx=2 dz=-3, beside the corridor's own default first leg
        // instead of off to the side at dx=5 dz=0) produced a WORSE, not better, "never even sighted"
        // result in an isolated single-test run -- suggesting position along the corridor's own axis is
        // not the deciding factor after all; and (b) this is the only test in the whole suite that calls
        // setInventory (every slot but a couple pre-filled to 63 cobblestone), and its own task ticks log
        // consistently high profile_slow_section costs (50-150ms, several times a normal tick budget) that
        // no sibling fixture shows, hinting the extra per-tick inventory-merge bookkeeping itself may be
        // competing for the same tick budget the sense sweep uses (design's own "adaptive_throttle"), which
        // would explain a fixture-specific flake no geometry change alone can fix. Left at the original
        // ore position with no local supply (the best-confirmed partial improvement) rather than guessing
        // further.
        BlockPos ore = room.at(5, 1, 0);
        room.set(5, 1, 0, Blocks.DIAMOND_ORE);
        room.set(5, 2, 0, Blocks.STONE);
        AIPlayerEntity bot = h.spawn("DetourInventoryGT", room, 0, 0);
        setInventory(bot, Items.IRON_PICKAXE, 2); // leaves only 2 empty slots: below the default reserve of 3
        h.enableDetourMode(256); // a wider sensor budget, belt-and-braces
        h.enableAssist(bot);
        UUID id = bot.getUuid();
        Progress p = new Progress();
        OreDigTask[] task = {null};
        int[] stage = {0};
        int[] stageStart = {0};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    h.assertStrict(bot);
                    task[0] = new OreDigTask(Set.of(Blocks.COAL_ORE), 1);
                    TaskManager.INSTANCE.assign(bot, task[0], TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_detour_inventory"));
                    p.assignedAt = p.tick;
                    stageStart[0] = p.tick;
                }
                return;
            }
            h.require(task[0].state() != TaskState.FAILED, "the mission failed: " + task[0].failureReason());
            MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
            switch (stage[0]) {
                case 0 -> {
                    boolean sighted = state != null && state.sightings().contains(ore);
                    h.require(phaseOf(id) == DetourPhase.IDLE,
                            "a detour started while the inventory was below the reserve (capacityOk should have rejected it)");
                    if (!sighted) {
                        h.require(p.tick - stageStart[0] < 500, "the nearby diamond was never even sighted");
                        return;
                    }
                    if (p.tick - stageStart[0] < 900) {
                        return;
                    }
                    // Free up space well above the reserve and confirm the same candidate can now be detoured to.
                    setInventory(bot, Items.IRON_PICKAXE, 6);
                    stage[0] = 1;
                    stageStart[0] = p.tick;
                }
                case 1 -> {
                    if (phaseOf(id) == DetourPhase.IDLE) {
                        h.require(p.tick - stageStart[0] < 600, "the detour never started once inventory space was freed");
                        return;
                    }
                    LOG.info("[detour-gametest] inventory_reserve started_after_free_ticks={}", p.tick - stageStart[0]);
                    h.pass();
                }
                default -> {
                }
            }
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 17. A walk-only return that keeps failing rebases in place and disables further detours
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:ore_dig_opportunistic_lifecycle_return_failure_rebases_in_place_and_disables_detours", maxTicks = 3800)
    public void returnFailureRebasesInPlaceAndDisablesDetours(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(37, -3, 10, -3, 3, 4);
        // dy=1 + a solid roof (not dy=0): see the pose comment on the first test in this file.
        room.set(7, 1, 0, Blocks.DIAMOND_ORE);
        room.set(7, 2, 0, Blocks.STONE);
        // A generous, purely-local coal supply near spawn (dy=1 + roof, checkerboard, same convention
        // and same reasoning as the inventory-reserve test above), well short of the diamond and of the
        // dx=3 wall this test seals later (harmless either way: wall() overwrites its whole plane solid
        // regardless of what was there): without it (the original fixture had none at all, target=1)
        // OreDig's own ordinary ladder has nothing to find and immediately branches off on its
        // far-ranging spiral search (confirmed in the real GameTest log: the very first
        // DetourStartSelector check answered unreachable_observed -- an unlucky but real race against
        // how much of the room the sensor had swept by MIN_TASK_AGE_TICKS(60) -- which excludes the
        // candidate cluster for 600 server ticks; by the time that lifts, the spiral has carried the bot
        // far enough that the diamond reads unknown instead and never becomes observable again, so the
        // detour never leaves APPROACH). dx=0 is the bot's own spawn column and every (dx,dz) cell solid
        // would entomb it / wall off the room (see the inventory-reserve test's own comment on that exact
        // regression): the checkerboard (only where dx+dz is even, dx skipping 0) avoids both. 18 cells,
        // comfortably under target_count(999, effectively never reached) below so the mission never
        // completes either, keeps the bot in the immediate area for the whole run.
        for (int dx = -2; dx <= 3; dx++) {
            if (dx == 0) {
                continue;
            }
            for (int dz = -3; dz <= 3; dz++) {
                if (((dx + dz) & 1) != 0) {
                    continue;
                }
                room.set(dx, 1, dz, Blocks.COAL_ORE);
                room.set(dx, 2, dz, Blocks.STONE);
            }
        }
        AIPlayerEntity bot = h.spawn("DetourRebaseGT", room, 0, 0);
        // A stone pick is mandatory for OreDig's own strip/channel through ordinary rock: the channel-tool
        // policy floors every mined block (including the mission's own coal/lapis target) at STONE tier and,
        // for non-ore rock, caps it there too (OreDigTask.failMissingMiningChannelTool ~line 4489), so the
        // mission never has to spend its iron pick on plain corridor stone.
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 8));
        h.enableDetourMode(256); // see enableDetourMode(int)'s own comment: this fixture's local coal supply
        h.enableAssist(bot);
        UUID id = bot.getUuid();
        Progress p = new Progress();
        OreDigTask[] task = {null};
        int[] stage = {0};
        int[] stageStart = {0};
        int[] startsBefore = {-1};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    h.assertStrict(bot);
                    task[0] = new OreDigTask(Set.of(Blocks.COAL_ORE), 999);
                    TaskManager.INSTANCE.assign(bot, task[0], TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_detour_rebase"));
                    p.assignedAt = p.tick;
                    stageStart[0] = p.tick;
                }
                return;
            }
            h.require(task[0].state() != TaskState.FAILED, "the mission failed: " + task[0].failureReason());
            switch (stage[0]) {
                case 0 -> {
                    DetourPhase phase = phaseOf(id);
                    if (phase != DetourPhase.MINE && phase != DetourPhase.POSTBREAK && phase != DetourPhase.SETTLE_DROP
                            && phase != DetourPhase.RETURN) {
                        h.require(p.tick - stageStart[0] < 1200, "the detour never left APPROACH");
                        return;
                    }
                    // The bot has already crossed dx=3 to reach the ore at dx=7: seal the corridor behind it now,
                    // between the ore and the anchor, with a full-height, full-width, unbreakable-to-a-walker wall.
                    room.wall(3);
                    stage[0] = 1;
                    stageStart[0] = p.tick;
                }
                case 1 -> {
                    // A walk-only return cannot dig through the new wall: three failed attempts must rebase the
                    // cursor in place rather than fail the mission with an unreachable-face error.
                    h.require(!task[0].failureReason().contains("restore_face_unreachable"),
                            "the sealed corridor produced a real restore-face failure instead of a detour rebase: "
                                    + task[0].failureReason());
                    Map<String, String> checkpoint = task[0].checkpoint();
                    BlockPos face = decode(checkpoint.get("face"));
                    boolean rebased = face.getX() > room.at(3, 0, 0).getX() && phaseOf(id) == DetourPhase.IDLE;
                    if (!rebased) {
                        h.require(p.tick - stageStart[0] < 1400,
                                "the blocked return never rebased the cursor on the ore's side of the new wall (face="
                                        + face.toShortString() + " phase=" + phaseOf(id) + ")");
                        return;
                    }
                    startsBefore[0] = countStarts(bot.getGameProfile().name());
                    // A second, obviously-admittable valuable, placed on the same side the bot is now stuck on.
                    // dy=1 + a solid roof (not dy=0): see the pose comment on the first test in this file --
                    // otherwise a further detour would stay silent for a geometry reason having nothing to do
                    // with the return-rebase disabling further detours, and this stage would prove nothing.
                    room.world.setBlockState(face.add(2, 1, 1), Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
                    room.world.setBlockState(face.add(2, 2, 1), STONE, Block.NOTIFY_ALL);
                    stage[0] = 2;
                    stageStart[0] = p.tick;
                }
                case 2 -> {
                    h.require(phaseOf(id) == DetourPhase.IDLE,
                            "a further detour started after a return rebase disabled the mission's detours");
                    if (p.tick - stageStart[0] < 700) {
                        return;
                    }
                    int startsAfter = countStarts(bot.getGameProfile().name());
                    h.require(startsAfter <= startsBefore[0],
                            "a new ore_dig_detour_start line appeared after the return rebase disabled further detours");
                    LOG.info("[detour-gametest] return_rebase starts_before={} starts_after={}", startsBefore[0], startsAfter);
                    h.pass();
                }
                default -> {
                }
            }
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 18. Degraded TPS aborts an in-flight detour and sends the bot back toward the anchor
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:ore_dig_opportunistic_lifecycle_degraded_tps_aborts_in_flight_detour", maxTicks = 1800)
    public void degradedTpsAbortsInFlightDetour(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(38, -3, 12, -3, 3, 4);
        // dy=1 + a solid roof (not dy=0): see the pose comment on the first test in this file.
        room.set(9, 1, 0, Blocks.DIAMOND_ORE);
        room.set(9, 2, 0, Blocks.STONE);
        AIPlayerEntity bot = h.spawn("DetourTpsGT", room, 0, 0);
        // A stone pick is mandatory for OreDig's own strip/channel through ordinary rock: the channel-tool
        // policy floors every mined block (including the mission's own coal/lapis target) at STONE tier and,
        // for non-ore rock, caps it there too (OreDigTask.failMissingMiningChannelTool ~line 4489), so the
        // mission never has to spend its iron pick on plain corridor stone.
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 8));
        h.enableDetourMode();
        h.enableAssist(bot);
        UUID id = bot.getUuid();
        Progress p = new Progress();
        OreDigTask[] task = {null};
        int[] stage = {0};
        int[] stageStart = {0};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    h.assertStrict(bot);
                    task[0] = new OreDigTask(Set.of(Blocks.COAL_ORE), 1);
                    TaskManager.INSTANCE.assign(bot, task[0], TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_detour_tps"));
                    p.assignedAt = p.tick;
                    stageStart[0] = p.tick;
                }
                return;
            }
            h.require(task[0].state() != TaskState.FAILED, "the mission failed: " + task[0].failureReason());
            switch (stage[0]) {
                case 0 -> {
                    if (phaseOf(id) == DetourPhase.IDLE) {
                        h.require(p.tick - stageStart[0] < 1200, "the detour never started");
                        return;
                    }
                    MiningAssistRuntime.setTestTpsDegraded(Boolean.TRUE);
                    stage[0] = 1;
                    stageStart[0] = p.tick;
                }
                case 1 -> {
                    DetourPhase phase = phaseOf(id);
                    if (phase != DetourPhase.RETURN && phase != DetourPhase.IDLE) {
                        h.require(p.tick - stageStart[0] < 60,
                                "a degraded TPS verdict did not abort the in-flight detour into RETURN within a couple of ticks: "
                                        + phase);
                        return;
                    }
                    MiningAssistRuntime.setTestTpsDegraded(Boolean.FALSE);
                    LOG.info("[detour-gametest] degraded_tps aborted_into={} after_ticks={}", phase, p.tick - stageStart[0]);
                    h.pass();
                }
                default -> {
                }
            }
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 19. A hurt edge aborts an APPROACH but the same condition never aborts a RETURN
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:ore_dig_opportunistic_lifecycle_hurt_edge_aborts_approach_but_not_return", maxTicks = 2200)
    public void hurtEdgeAbortsApproachButNotReturn(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(39, -3, 12, -3, 3, 4);
        // dy=1 + a solid roof (not dy=0): see the pose comment on the first test in this file.
        room.set(9, 1, 0, Blocks.DIAMOND_ORE);
        room.set(9, 2, 0, Blocks.STONE);
        AIPlayerEntity bot = h.spawn("DetourHurtGT", room, 0, 0);
        // A stone pick is mandatory for OreDig's own strip/channel through ordinary rock: the channel-tool
        // policy floors every mined block (including the mission's own coal/lapis target) at STONE tier and,
        // for non-ore rock, caps it there too (OreDigTask.failMissingMiningChannelTool ~line 4489), so the
        // mission never has to spend its iron pick on plain corridor stone.
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 8));
        h.enableDetourMode();
        h.enableAssist(bot);
        UUID id = bot.getUuid();
        Progress p = new Progress();
        OreDigTask[] task = {null};
        int[] stage = {0};
        int[] stageStart = {0};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    h.assertStrict(bot);
                    task[0] = new OreDigTask(Set.of(Blocks.COAL_ORE), 1);
                    TaskManager.INSTANCE.assign(bot, task[0], TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_detour_hurt"));
                    p.assignedAt = p.tick;
                    stageStart[0] = p.tick;
                }
                return;
            }
            h.require(task[0].state() != TaskState.FAILED, "the mission failed: " + task[0].failureReason());
            switch (stage[0]) {
                case 0 -> {
                    if (phaseOf(id) != DetourPhase.APPROACH) {
                        h.require(p.tick - stageStart[0] < 1200, "the detour never reached (or passed through) APPROACH");
                        return;
                    }
                    bot.hurtTime = 5;
                    stage[0] = 1;
                    stageStart[0] = p.tick;
                }
                case 1 -> {
                    DetourPhase phase = phaseOf(id);
                    if (phase != DetourPhase.RETURN && phase != DetourPhase.IDLE) {
                        h.require(p.tick - stageStart[0] < 60,
                                "a hurtTime rise during APPROACH did not abort the detour into RETURN promptly: " + phase);
                        return;
                    }
                    // (phase may already be IDLE here if the return finished very quickly; that is fine.)
                    stage[0] = 2;
                    stageStart[0] = p.tick;
                }
                case 2 -> {
                    // hurtTime decays on its own; wait for it to read 0 again before re-raising it as a fresh edge.
                    if (bot.hurtTime > 0) {
                        h.require(p.tick - stageStart[0] < 60, "the bot's hurtTime never decayed back to 0");
                        return;
                    }
                    if (phaseOf(id) == DetourPhase.RETURN) {
                        bot.hurtTime = 5;
                    }
                    stage[0] = 3;
                    stageStart[0] = p.tick;
                }
                case 3 -> {
                    // Design 4.4 / the P1 contract: RETURN (and HANDOFF) are exempt from the hurt-edge net. The
                    // fresh rise above must not re-abort anything (there is nothing left to abort into but RETURN
                    // itself) and the bot must keep walking home, not sit still or fail.
                    h.require(task[0].state() == TaskState.RUNNING,
                            "the mission is no longer RUNNING after a hurtTime rise seen during RETURN: " + task[0].state());
                    if (phaseOf(id) != DetourPhase.IDLE) {
                        h.require(p.tick - stageStart[0] < 500, "the detour never finished returning home");
                        return;
                    }
                    LOG.info("[detour-gametest] hurt_edge finished_returning_ticks={}", p.tick - stageStart[0]);
                    h.pass();
                }
                default -> {
                }
            }
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // Shared fixture and harness plumbing
    // ---------------------------------------------------------------------------------------------

    private static DetourPhase phaseOf(UUID id) {
        MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
        return state == null ? DetourPhase.IDLE : state.detourPhase();
    }

    /** Sets the bot's main inventory to a pickaxe in slot 0, cobblestone filling every slot but the last {@code emptySlots}. */
    /**
     * Sets the bot's main inventory to a stone pick in slot 0 (mandatory for OreDig's own channel/corridor
     * bottleneck, see the giveItem comment on the earlier tests in this file), {@code pickaxe} in slot 1,
     * cobblestone filling every slot but the last {@code emptySlots}.
     */
    private static void setInventory(AIPlayerEntity bot, net.minecraft.item.Item pickaxe, int emptySlots) {
        var main = bot.getInventory().getMainStacks();
        int size = main.size();
        int keepEmpty = Math.max(0, Math.min(emptySlots, size - 2));
        main.set(0, new ItemStack(Items.STONE_PICKAXE));
        main.set(1, new ItemStack(pickaxe));
        for (int i = 2; i < size - keepEmpty; i++) {
            // One below max: capacityOk's reserve counts strictly-empty stacks, so this does not change the
            // "below reserve" slot count, but it gives the mission's own incidental corridor cobblestone (it
            // must still eat through real rock to stay locally busy, see the hub()-style coal added below)
            // a large merge buffer instead of demanding a brand new slot the reserve test deliberately denies.
            main.set(i, new ItemStack(Items.COBBLESTONE, 63));
        }
        for (int i = size - keepEmpty; i < size; i++) {
            main.set(i, ItemStack.EMPTY);
        }
        bot.getInventory().markDirty();
    }

    private static BlockPos decode(String value) {
        String[] parts = value.split(",");
        return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    /** Count of {@code ore_dig_detour_start} lines in this bot's own structured log, or -1 if the log is unavailable. */
    private static int countStarts(String botName) {
        List<String> lines = botLog(botName);
        if (lines == null || !hasSpawnLine(lines)) {
            return -1;
        }
        return (int) detourEvents(lines).stream().filter(e -> e.equals("ore_dig_detour_start")).count();
    }

    private static List<String> botLog(String botName) {
        try {
            Path base = BotLogWriter.INSTANCE.baseDir();
            if (base == null) {
                return null;
            }
            Path file = base.resolve("by-bot").resolve(botName.replaceAll("[^a-zA-Z0-9_.-]", "_") + ".log");
            if (!Files.isRegularFile(file)) {
                return null;
            }
            String needle = " bot=" + botName + " ";
            try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
                return lines.filter(line -> line.contains(needle)).toList();
            }
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    private static boolean hasSpawnLine(List<String> lines) {
        return lines.stream().anyMatch(line -> line.contains("event=bot_spawned"));
    }

    private static List<String> detourEvents(List<String> lines) {
        List<String> events = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = EVENT.matcher(line);
            if (matcher.find()) {
                events.add(matcher.group(1));
            }
        }
        return events;
    }

    /** A sealed stone box with an air interior, matching {@code mining.assist.MiningAssistSenseGameTests.Room}. */
    private static final class Room {
        final ServerWorld world;
        final BlockPos feet;
        private final int minDx;
        private final int maxDx;
        private final int minDz;
        private final int maxDz;
        private final int height;
        private final int shellH;

        Room(TestContext context, int relY, int minDx, int maxDx, int minDz, int maxDz, int height) {
            this.world = context.getWorld();
            this.feet = context.getAbsolutePos(new BlockPos(3, relY, 3)).toImmutable();
            this.minDx = minDx;
            this.maxDx = maxDx;
            this.minDz = minDz;
            this.maxDz = maxDz;
            this.height = height;
            this.shellH = SHELL;
            fill(minDx - shellH, -SHELL, minDz - shellH, maxDx + shellH, height - 1 + SHELL, maxDz + shellH, STONE);
            fill(minDx, 0, minDz, maxDx, height - 1, maxDz, AIR);
            discardEntities();
        }

        void clear() {
            fill(minDx - shellH, -SHELL, minDz - shellH, maxDx + shellH, height - 1 + SHELL, maxDz + shellH, AIR);
            discardEntities();
        }

        private void discardEntities() {
            BlockPos low = at(minDx - shellH, -SHELL, minDz - shellH);
            BlockPos high = at(maxDx + shellH + 1, height + SHELL, maxDz + shellH + 1);
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

        /** Seals the whole cross-section (interior and shell) at this X plane: a walk-only route cannot pass it. */
        void wall(int dx) {
            for (int y = -1; y <= height; y++) {
                for (int z = minDz - shellH; z <= maxDz + shellH; z++) {
                    world.setBlockState(at(dx, y, z), STONE, Block.NOTIFY_ALL);
                }
            }
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

    private static final class Progress {
        int tick;
        int assignedAt = -1;
        int chunkWaitStart = -1;
        boolean chunksWaived;
    }

    /** Cleanup-on-failure, strict-capability and readiness plumbing shared by every test in this file. */
    private static final class Harness {
        final TestContext context;
        final List<String> bots = new ArrayList<>();
        final List<Runnable> cleanups = new ArrayList<>();
        final List<Room> rooms = new ArrayList<>();
        MiningAssistConfig restoreConfig;
        boolean tpsOverridden;
        boolean done;

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

        /** Installs a DETOUR-mode config for the duration of this test (the shipped default is still SENSE). */
        void enableDetourMode() {
            if (restoreConfig == null) {
                restoreConfig = MiningAssistRuntime.config();
            }
            MiningAssistRuntime.install(MiningAssistConfig.defaults(AssistMode.DETOUR, false));
        }

        /**
         * Same as {@link #enableDetourMode()} but with a raised {@code sense.raysPerTick} (the
         * {@code OreDigOpportunisticGameTests} sibling file's own convention, {@code h.enableDetour(bot, 256)}):
         * a test whose fixture adds many nearby "keep busy" ore cells (design comment on the tests that use
         * this) needs a wider sensor budget so the one distant candidate a test cares about is still reached
         * promptly instead of competing for the plain {@link MiningAssistConfig.Sense#DEFAULT_RAYS_PER_TICK}
         * against every one of those closer surfaces.
         */
        void enableDetourMode(int raysPerTick) {
            if (restoreConfig == null) {
                restoreConfig = MiningAssistRuntime.config();
            }
            com.google.gson.JsonObject sense = new com.google.gson.JsonObject();
            sense.addProperty("raysPerTick", raysPerTick);
            com.google.gson.JsonObject section = new com.google.gson.JsonObject();
            section.add("sense", sense);
            com.google.gson.JsonObject root = new com.google.gson.JsonObject();
            root.add(MiningAssistConfig.FILE_SECTION, section);
            MiningAssistRuntime.install(MiningAssistConfig.parse(root, key -> null, AssistMode.DETOUR, false));
        }

        /** Opts the bot in and pins the TPS verdict to "healthy" so a loaded test server cannot close the gate. */
        void enableAssist(AIPlayerEntity bot) {
            MiningAssistRuntime.setTestTpsDegraded(Boolean.FALSE);
            tpsOverridden = true;
            MiningAssistRuntime.forceEnable(bot.getUuid());
        }

        void onCleanup(Runnable cleanup) {
            cleanups.add(cleanup);
        }

        /**
         * True once the bot is underground by the world's own sky test and the chunk ring around it is loaded.
         * A chunk ring that never loads is waived after 60 ticks (matches {@code MiningAssistSenseGameTests}).
         */
        boolean settle(AIPlayerEntity bot, Progress p) {
            ServerWorld world = bot.getEntityWorld();
            BlockPos feet = bot.getBlockPos();
            if (world.isSkyVisible(feet)) {
                require(p.tick < 200, "the sealed fixture never became underground by the world's sky test");
                return false;
            }
            int ring = (int) Math.ceil(SenseBudget.sweepRadius(MinecraftAiConfig.get().perception().radius()) / 16.0D);
            boolean loaded = true;
            for (int dx = -ring; dx <= ring && loaded; dx++) {
                for (int dz = -ring; dz <= ring && loaded; dz++) {
                    loaded = world.getChunkManager().isChunkLoaded((feet.getX() >> 4) + dx, (feet.getZ() >> 4) + dz);
                }
            }
            if (loaded) {
                return true;
            }
            if (p.chunkWaitStart < 0) {
                p.chunkWaitStart = p.tick;
            }
            if (p.tick - p.chunkWaitStart < 60) {
                return false;
            }
            p.chunksWaived = true;
            return true;
        }

        void assertStrict(AIPlayerEntity bot) {
            require(MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                    "GameTest must run under strict_survival, got " + MinecraftAiConfig.get().profile());
            for (PrivilegedCapability capability : PrivilegedCapability.values()) {
                require(!CapabilityRuntime.decide(bot, capability, "ore_dig_opportunistic_lifecycle_gametest").allowed(),
                        "strict_survival unexpectedly allowed " + capability);
            }
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
            for (Runnable cleanup : cleanups) {
                try {
                    cleanup.run();
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
            cleanups.clear();
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
    }
}

package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
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
import io.github.zoyluo.minecraftai.task.SensingArena.Room;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.zoyluo.minecraftai.task.SensingArena.botLog;
import static io.github.zoyluo.minecraftai.task.SensingArena.hasSpawnLine;

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
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final Pattern EVENT = Pattern.compile("event=(ore_dig_detour_[a-z_]+)");

    // ---------------------------------------------------------------------------------------------
    // 9. Slow mine, a lost drop and a long return never trip OreDig's own NO_PROGRESS_LIMIT (I14)
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:ore_dig_opportunistic_lifecycle_slow_mine_and_drop_lost_and_long_return_never_trip_no_progress", maxTicks = 4200)
    public void slowMineAndDropLostAndLongReturnNeverTripNoProgress(GameTestHelper context) {
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
        UUID id = bot.getUUID();
        Progress p = new Progress();
        OreDigTask[] task = {null};
        boolean[] fatigued = {false};
        boolean[] everActive = {false};
        boolean[] sawReturn = {false};
        int[] activeSince = {-1};
        int failuresBefore = MiningAssistRuntime.failures().size();

        context.failIfEver(() -> h.guard(() -> {
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
                // Amplifier 0 (Fatigue I, getDestroyProgress's own ~0.3x speed multiplier), not 2 (Fatigue
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
                bot.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, 6000, 0));
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
    public void pauseMidDetourResumesToAnchor(GameTestHelper context) {
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
        UUID id = bot.getUUID();
        Progress p = new Progress();
        OreDigTask[] task = {null};
        Map<String, String>[] anchorCheckpoint = new Map[1];
        int[] stage = {0};
        int[] stageStart = {0};

        context.failIfEver(() -> h.guard(() -> {
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
                    // Wait for the engine to have actually reached MINE (not merely a still-walking APPROACH,
                    // and never the very first tick APPROACH itself is published): pausing the instant phase
                    // leaves IDLE catches the detour before the bot has walked a single block away from its
                    // pre-detour anchor, since APPROACH is published the same tick the excursion is chosen.
                    // With zero real displacement, resume finds the bot already standing on the anchor
                    // (restoringFace false, nothing to walk back for), so ordinary corridor digging resumes
                    // immediately and can move the bot off that exact cell within the very next server tick --
                    // before this polling loop (one tick behind the engine) ever observes it there -- so the
                    // "walked back to the anchor" check below never fires true. Waiting for MINE (the bot has
                    // actually reached the ore's stand pose) guarantees a genuine mid-flight interruption, the
                    // same care restartMidDetourReturnsToAnchor takes for the same reason.
                    if (phaseOf(id) != DetourPhase.MINE && phaseOf(id) != DetourPhase.POSTBREAK
                            && phaseOf(id) != DetourPhase.SETTLE_DROP) {
                        h.require(p.tick - stageStart[0] < 1200, "the detour never reached MINE");
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
                    if (!bot.blockPosition().equals(anchor)) {
                        h.require(p.tick - stageStart[0] < 400, "the resumed bot never walked back to the interrupted anchor "
                                + anchor.toShortString() + " (at " + bot.blockPosition().toShortString() + ")");
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
    public void restartMidDetourReturnsToAnchor(GameTestHelper context) {
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
        UUID id = bot.getUUID();
        Progress p = new Progress();
        OreDigTask[] task = {null};
        OreDigTask[] restored = {null};
        BlockPos[] anchor = new BlockPos[1];
        int[] stage = {0};
        int[] stageStart = {0};

        context.failIfEver(() -> h.guard(() -> {
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
                    if (!bot.blockPosition().equals(anchor[0])) {
                        h.require(p.tick - stageStart[0] < 500, "the restarted task never walked back to the anchor "
                                + anchor[0].toShortString() + " (at " + bot.blockPosition().toShortString() + ")");
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
    public void failMidDetourReleasesClaims(GameTestHelper context) {
        terminalExitReleasesClaims(context, "DetourFailGT", "gametest_detour_fail", true);
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_opportunistic_lifecycle_complete_mid_detour_releases_claims", maxTicks = 1100)
    public void completeMidDetourReleasesClaims(GameTestHelper context) {
        terminalExitReleasesClaims(context, "DetourCompleteGT", "gametest_detour_complete", false);
    }

    private void terminalExitReleasesClaims(GameTestHelper context, String name, String reason, boolean viaFail) {
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
        UUID id = bot.getUUID();
        String dimensionKey = BotEdits.dimensionKey(room.world);
        UUID otherBot = UUID.randomUUID();
        Progress p = new Progress();
        OreDigTask[] task = {null};
        int[] stage = {0};
        int[] stageStart = {0};

        context.failIfEver(() -> h.guard(() -> {
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

    @GameTest(environment = "minecraftai-gametest:ore_dig_opportunistic_lifecycle_drop_recovery_contract_bound_and_non_terminal", maxTicks = 1800)
    public void dropRecoveryContractBoundAndNonTerminal(GameTestHelper context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(34, -3, 12, -3, 3, 4);
        // FIXTURE HISTORY / ROOT CAUSE (confirmed against the real GameTest log; see the g2/r7/r8 debugging
        // sessions' own reports): the original fixture made "hidden" a DIFFERENT block (gold_ore) from the
        // diamond, so 4.8's vein-follow could never pick it up as a member -- it could only ever become an
        // ordinary BreakPeek sighting (4.7/4.8) needing its own later detour with a fresh LIVE re-proof. But
        // the diamond's own detour lasts only ~70-100 ticks and returns the bot to its blind-tunnel anchor
        // immediately after (design 4.10), and nothing in the mission's own ordinary ladder (target lapis,
        // never present) ever walks the bot back past the gold's one open face again -- every later
        // DetourStartSelector re-proof of that candidate answered "unknown" (never present, never gone) for
        // the whole ~2700-tick budget regardless of how close the gold was moved (dx=13 across the room,
        // then dz=2 two cells short, then directly adjacent at dz=4 -- all three confirmed wrong the same
        // way). This was the fixture's own geometry, not a product bug: an ordinary BreakPeek sighting is
        // *supposed* to need its own later live re-proof, and nothing here ever gave it one.
        //
        // THE FIX (task's own suggested option): make both valuables the SAME block (diamond_ore), placed as
        // immediate same-block neighbours, so the second one is discovered and mined by ordinary P1
        // vein-follow (4.7 "scan the 26 neighbours of p... add PRESENT cells to pending", 4.8 "members are
        // visited nearest-first... each member re-runs 4.6 and 4.7") within the SAME live detour as the first
        // -- no return-to-anchor, no second detour, no stale re-proof anywhere in the path.
        //
        // A second attempt this round embedded memberB a shell layer deep (matching the original "hidden"
        // gold's own trick) with only its south face -- memberA's own cell -- open. That is geometrically
        // impossible to mine here: the only cardinal stand DetourHostImpl.poseFor can ever offer for it is
        // memberA's own former cell, and that cell's roof (mandatory for memberA's own breakGeometry overhead
        // check, see the pose comment below) leaves no headroom for a player to stand there -- confirmed
        // against the real GameTest log (event=ore_dig_detour_skip reason='geometry' the instant vein-follow
        // reached it). Embedding is therefore not this fixture's route to "reachable by ordinary P1
        // behaviour"; two open, individually-roofed same-block cells are.
        //
        // Both members: floating in the open, already fully-swept interior air -- every neighbour of each
        // break cell is known, so nothing can ever be excluded from a legal chase; each one's own break
        // independently exercises the drop ledger's contract (attempt a walk-only chase, at most 2 attempts,
        // settle within SETTLE_TOTAL_TICKS=60, and treat a lost drop as non-terminal -- design 4.9). dy=1 +
        // a solid roof (not dy=0): see the pose comment on the first test in this file (the one buried cell
        // this adds per member is its own roof, not a neighbour either drop could ever roll into).
        //
        // MEMBER B'S PLACE (regression hunt, pace commit d2b583f): B used to sit at (6,1,3), a straight
        // x-neighbour of A, whose only stand is the cell one step east of A's stand. Reaching that pose
        // needs adjacentHazard(stand) == OBSERVED_GONE, and the one neighbour of that stand that matters --
        // the open cell under B -- is hidden behind B itself from the eye of a bot standing at A's stand
        // unless the bot's sub-cell arrival offset is below ~0.38 on one axis (ray from eye y+1.62 down to that
        // cell's centre crosses B's row at t~0.55). The pace policy walks the last blocks and stops inside
        // the arrival tolerance, at about (+0.42, +0.42) of the cell, so the real-server log answered
        // ore_dig_detour_skip reason=no_pose for B (UNKNOWN is never dry, design I1) in most runs and the
        // 900-tick window never saw B broken. That is the design working, not a bug; the fixture's own
        // geometry made B's pose depend on a fraction of a block. B now sits at (6,1,2): still A's diagonal
        // (26-neighbourhood) vein member, but at head height one step east of A's stand, i.e. inside the
        // break envelope of the very cell the bot already stands on (hasRecoverableTargetBreakPose). Its pose
        // is the bot's own feet, so no hazard proof of a cell hidden behind the ore is involved, whatever
        // the arrival offset.
        BlockPos memberA = room.at(5, 1, 3);
        room.set(5, 1, 3, Blocks.DIAMOND_ORE);
        room.set(5, 2, 3, Blocks.STONE);
        BlockPos memberB = room.at(6, 1, 2);
        room.set(6, 1, 2, Blocks.DIAMOND_ORE);
        room.set(6, 2, 2, Blocks.STONE);
        BlockPos guard = room.at(7, 1, 2); // sealed solid just past memberB: never a legal walk target either
        room.set(7, 1, 2, Blocks.STONE);
        AIPlayerEntity bot = h.spawn("DetourDropGT", room, 0, 0);
        // A stone pick is mandatory for OreDig's own strip/channel through ordinary rock: the channel-tool
        // policy floors every mined block (including the mission's own coal/lapis target) at STONE tier and,
        // for non-ore rock, caps it there too (OreDigTask.failMissingMiningChannelTool ~line 4489), so the
        // mission never has to spend its iron pick on plain corridor stone.
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 8));
        h.enableDetourMode(256); // a wider sensor budget, belt-and-braces
        h.enableAssist(bot);
        UUID id = bot.getUUID();
        Progress p = new Progress();
        OreDigTask[] task = {null};
        int[] activeTicks = {0};

        context.failIfEver(() -> h.guard(() -> {
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
            h.require(room.world.getBlockState(guard).is(Blocks.STONE),
                    "the walk-only chase dug through solid rock chasing an unreachable drop");
            if (phaseOf(id) != DetourPhase.IDLE) {
                activeTicks[0]++;
            }
            boolean bothGone = room.world.getBlockState(memberA).isAir()
                    && room.world.getBlockState(memberB).isAir();
            if (!bothGone) {
                h.require(p.tick - p.assignedAt < 900, "both valuables were never both broken (memberA_left="
                        + !room.world.getBlockState(memberA).isAir() + " memberB_left="
                        + !room.world.getBlockState(memberB).isAir() + ")");
                return;
            }
            // Give the settle logic (up to SETTLE_TOTAL_TICKS=60) and the coordinator a moment to finish quietly.
            if (p.tick - p.assignedAt < 900 + 150) {
                return;
            }
            h.require(!task[0].failureReason().toLowerCase(java.util.Locale.ROOT).contains("unrecovered"),
                    "an unrecovered-drop failure reason appeared after settling");
            h.require(room.world.getBlockState(guard).is(Blocks.STONE), "the chase eventually dug through solid rock");
            LOG.info("[detour-gametest] drop_recovery active_ticks={} diamonds_held={}",
                    activeTicks[0], InventoryAction.countItem(bot, Items.DIAMOND));
            h.pass();
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 15. Two bots, one vein: OreClaims lets exactly one of them actually mine it
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:ore_dig_opportunistic_lifecycle_two_bots_one_vein_exactly_one_breaker", maxTicks = 2500)
    public void twoBotsOneVeinExactlyOneBreaker(GameTestHelper context) {
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
        UUID idA = botA.getUUID();
        UUID idB = botB.getUUID();
        Progress p = new Progress();
        OreDigTask[] taskA = {null};
        OreDigTask[] taskB = {null};
        int[] minedAt = {-1};

        context.failIfEver(() -> h.guard(() -> {
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
            // Give both engines time to finish their own settle/return so the claim is released either way. The wait
            // runs from the moment the vein was mined and ends as soon as the claim is free: both missions look for
            // coal that does not exist, so each one is doomed to end "trapped" once its blind branch has eaten through
            // the finite sealed room (about 1400 ticks in), and a fixed wait counted from the assignment raced that end.
            if (minedAt[0] < 0) {
                minedAt[0] = p.tick;
            }
            if (p.tick - minedAt[0] < 150) {
                return;
            }
            int tick = MiningAssistRuntime.serverTick(botA);
            String dimensionKey = BotEdits.dimensionKey(room.world);
            if (OreClaims.heldByOther(dimensionKey, UUID.randomUUID(), ore.asLong(), tick)) {
                h.require(p.tick - minedAt[0] < 150 + 600,
                        "the shared cell's claim was never released after the vein was settled");
                return;
            }
            int total = InventoryAction.countItem(botA, Items.DIAMOND) + InventoryAction.countItem(botB, Items.DIAMOND);
            LOG.info("[detour-gametest] two_bots mined_by_a={} mined_by_b={} total_diamonds={}",
                    taskA[0].checkpoint().get("delivered"), taskB[0].checkpoint().get("delivered"), total);
            h.pass();
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 16. Inventory below the reserve stops a detour from starting; freeing space allows it
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:ore_dig_opportunistic_lifecycle_inventory_reserve_stops_detour", maxTicks = 1800)
    public void inventoryReserveStopsDetour(GameTestHelper context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(36, -3, 8, -3, 3, 4);
        // dy=1 + a solid roof (not dy=0): see the pose comment on the first test in this file.
        //
        // FIXTURE HISTORY / ROOT CAUSE (confirmed against the real GameTest log; see the g2/r7/r8 debugging
        // sessions' own reports for the full trail, two rounds). Round 1 (still true): with no local coal at
        // all, OreDig's own ordinary ladder (target=coal_ore, none present) has nothing to find near spawn and
        // commits to a single STRIP_SEGMENT(48)-block leg out of the sealed room, going stale within ~150-200
        // ticks; a FIXED 900-tick wait for the reserve to free always lost that race.
        //
        // Round 2 (this fix's own finding): reacting to the FIRST live `ore_dig_detour_skip reason='capacity'`
        // line (freeing space immediately instead of after a fixed wait) is necessary but not sufficient on
        // its own. Instrumenting DetourStartSelector directly (temporary, removed) showed the real shape of
        // the problem: that one successful re-proof is a brief, lucky alignment -- the very next selector
        // passes (every START_CHECK_INTERVAL_TICKS=10) reproved the SAME candidate as `unknown` over and over,
        // even while the bot stayed only 6-8 blocks away the whole time (confirmed by position/distance
        // logging). `observeBlockIs`'s occlusion check is a straight-line raycast from the bot's own eye
        // position, independent of which way it is facing, so this was not a facing/FOV issue: with no local
        // target, OreDig's own ladder has nothing to hold the bot inside the open interior, and once it drifts
        // toward the far side of the small room the straight line back to the diamond clips the room's own
        // solid roof/floor cells at a shallow angle. A momentary re-proof race is not a fixture bug to route
        // around at the freeing step; it needs the bot actually anchored near the diamond's own open sightline
        // for the (now much shorter, reactive) wait.
        //
        // THE FIX: give the mission a tiny, purely-local coal supply -- the same proven technique as
        // slowMineAndDropLostAndLongReturnNeverTripNoProgress's emerald column and
        // returnFailureRebasesInPlaceAndDisablesDetours's checkerboard (dy=1 + roof, open floor below, so each
        // one's own ordinary-ladder drop is reliably recoverable) -- placed WEST of spawn (dx=-1,-2) on rows
        // off the spawn-diamond sightline (dz=+-2, never dz=0, never dx=0 which is spawn's own column), so
        // mining it can never stand directly on the line to the diamond at dz=0. A high target count (999,
        // matching returnFailureRebasesInPlaceAndDisablesDetours) keeps the mission perpetually incomplete so
        // it never finishes and stops ticking before the detour ever gets its chance. This keeps the bot
        // inside the open interior, near the diamond's own line of sight, for the whole (now short) reactive
        // wait, instead of trading this failure for the round-1 occlusion/drop_unrecovered ones a long fixed
        // wait's worth of local ore used to cause.
        BlockPos ore = room.at(5, 1, 0);
        room.set(5, 1, 0, Blocks.DIAMOND_ORE);
        room.set(5, 2, 0, Blocks.STONE);
        for (int dx = -2; dx <= -1; dx++) {
            for (int dz = -2; dz <= 2; dz += 4) {
                room.set(dx, 1, dz, Blocks.COAL_ORE);
                room.set(dx, 2, dz, Blocks.STONE);
            }
        }
        AIPlayerEntity bot = h.spawn("DetourInventoryGT", room, 0, 0);
        setInventory(bot, Items.IRON_PICKAXE, 2); // leaves only 2 empty slots: below the default reserve of 3
        h.enableDetourMode(256); // a wider sensor budget, belt-and-braces
        h.enableAssist(bot);
        UUID id = bot.getUUID();
        Progress p = new Progress();
        OreDigTask[] task = {null};
        int[] stage = {0};
        int[] stageStart = {0};

        context.failIfEver(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    h.assertStrict(bot);
                    task[0] = new OreDigTask(Set.of(Blocks.COAL_ORE), 999);
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
                    if (!sawSkipReason(bot.getGameProfile().name(), "capacity")) {
                        h.require(p.tick - stageStart[0] < 700,
                                "capacityOk never rejected the sighted diamond while inventory was below the reserve");
                        return;
                    }
                    // Free up space well above the reserve immediately after the first live proof that the
                    // reserve actually blocked this exact candidate -- not a long fixed wait (see the FIXTURE
                    // HISTORY note above) -- and confirm the same candidate can now be detoured to.
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
    public void returnFailureRebasesInPlaceAndDisablesDetours(GameTestHelper context) {
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
        UUID id = bot.getUUID();
        Progress p = new Progress();
        OreDigTask[] task = {null};
        int[] stage = {0};
        int[] stageStart = {0};
        int[] startsBefore = {-1};

        context.failIfEver(() -> h.guard(() -> {
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
                    room.world.setBlock(face.offset(2, 1, 1), Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
                    room.world.setBlock(face.offset(2, 2, 1), STONE, Block.UPDATE_ALL);
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
    public void degradedTpsAbortsInFlightDetour(GameTestHelper context) {
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
        UUID id = bot.getUUID();
        Progress p = new Progress();
        OreDigTask[] task = {null};
        int[] stage = {0};
        int[] stageStart = {0};

        context.failIfEver(() -> h.guard(() -> {
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
    public void hurtEdgeAbortsApproachButNotReturn(GameTestHelper context) {
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
        UUID id = bot.getUUID();
        Progress p = new Progress();
        OreDigTask[] task = {null};
        int[] stage = {0};
        int[] stageStart = {0};

        context.failIfEver(() -> h.guard(() -> {
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
    private static void setInventory(AIPlayerEntity bot, net.minecraft.world.item.Item pickaxe, int emptySlots) {
        var main = bot.getInventory().getNonEquipmentItems();
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
        bot.getInventory().setChanged();
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

    /**
     * True once this bot's own structured log has an {@code ore_dig_detour_skip} line carrying the given
     * {@code reason} (e.g. {@code capacity}, the DetourStartSelector line {@code capacityOk} itself logs).
     * Used to trigger a fixture step on live proof of a gate firing, instead of a fixed tick count.
     */
    private static boolean sawSkipReason(String botName, String reason) {
        List<String> lines = botLog(botName);
        if (lines == null || !hasSpawnLine(lines)) {
            return false;
        }
        String reasonNeedle = "reason='" + reason + "'";
        return lines.stream().anyMatch(line -> line.contains("event=ore_dig_detour_skip") && line.contains(reasonNeedle));
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

    private static final class Progress {
        int tick;
        int assignedAt = -1;
        int chunkWaitStart = -1;
        boolean chunksWaived;
    }

    /** Cleanup-on-failure, strict-capability and readiness plumbing shared by every test in this file. */
    private static final class Harness {
        final GameTestHelper context;
        final List<String> bots = new ArrayList<>();
        final List<Runnable> cleanups = new ArrayList<>();
        final List<Room> rooms = new ArrayList<>();
        MiningAssistConfig restoreConfig;
        boolean tpsOverridden;
        boolean done;

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
            MiningAssistRuntime.forceEnable(bot.getUUID());
        }

        void onCleanup(Runnable cleanup) {
            cleanups.add(cleanup);
        }

        /**
         * True once the bot is underground by the world's own sky test and the chunk ring around it is loaded.
         * A chunk ring that never loads is waived after 60 ticks (matches {@code MiningAssistSenseGameTests}).
         */
        boolean settle(AIPlayerEntity bot, Progress p) {
            ServerLevel world = bot.level();
            BlockPos feet = bot.blockPosition();
            if (world.canSeeSky(feet)) {
                require(p.tick < 200, "the sealed fixture never became underground by the world's sky test");
                return false;
            }
            int ring = (int) Math.ceil(SenseBudget.sweepRadius(MinecraftAiConfig.get().perception().radius()) / 16.0D);
            boolean loaded = true;
            for (int dx = -ring; dx <= ring && loaded; dx++) {
                for (int dz = -ring; dz <= ring && loaded; dz++) {
                    loaded = world.getChunkSource().hasChunk((feet.getX() >> 4) + dx, (feet.getZ() >> 4) + dz);
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
            for (Runnable cleanup : cleanups) {
                try {
                    cleanup.run();
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
            cleanups.clear();
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
    }
}

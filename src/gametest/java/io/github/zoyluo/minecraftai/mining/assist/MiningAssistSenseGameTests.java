package io.github.zoyluo.minecraftai.mining.assist;

import com.google.gson.JsonObject;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.MiningEvidenceAudit;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.observe.BotProfiler;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import io.github.zoyluo.minecraftai.task.AbstractTask;
import io.github.zoyluo.minecraftai.task.DescendToYTask;
import io.github.zoyluo.minecraftai.task.DigDownTask;
import io.github.zoyluo.minecraftai.task.MineTask;
import io.github.zoyluo.minecraftai.task.MineValuablesTask;
import io.github.zoyluo.minecraftai.task.OreDigTask;
import io.github.zoyluo.minecraftai.task.SensingArena.Room;
import io.github.zoyluo.minecraftai.task.Task;
import io.github.zoyluo.minecraftai.task.TaskManager;
import io.github.zoyluo.minecraftai.task.TaskState;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.zoyluo.minecraftai.task.SensingArena.botLog;
import static io.github.zoyluo.minecraftai.task.SensingArena.hasSpawnLine;

/**
 * Real-world proof of mining assist phase P0 (sense in shadow). Until these tests the sensor had only been
 * exercised over synthetic voxel worlds; here it runs in a real Minecraft world, through the real coordinator,
 * with strict-survival capabilities, real rays, real chunks and real block states.
 *
 * <p>Every test owns a sealed, stone-shelled fixture (so nothing outside it can be seen), waits until the bot is
 * genuinely underground per the world's own sky test, opts the bot in with {@code forceEnable} and gives it a
 * task with a REAL origin. Where a test needs a stationary bot it assigns a real mining-class task and then
 * pauses that task object in place: the task stays the bot's active task (so the coordinator's task-class and
 * origin gates see exactly what they see in play) but does nothing, which makes the ray sequence
 * deterministic (a bot's rotation comes from its uuid, and an offline uuid follows from its name).</p>
 *
 * <p>Numbers observed in the real world are written to the server log with the tag {@code [assist-gametest]}.</p>
 */
public final class MiningAssistSenseGameTests {
    private static final Logger LOG = LoggerFactory.getLogger("minecraftai-assist-gametest");

    private static final int SWEEP_RAYS = SphereSchedule.LATTICE_SIZE;
    private static final Pattern EVENT = Pattern.compile("event=(assist_[a-z_]+)");

    // ---------------------------------------------------------------------------------------------
    // 1. A visible ore is sighted within two sweeps
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:assist_sense_visible_ore", maxTicks = 500)
    public void visibleOreInsideACaveIsSighted(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(20, -4, 4, -3, 3, 4);
        List<OreSpot> ores = List.of(
                new OreSpot("diamond_ore", 100, room.at(5, 0, 0)),
                new OreSpot("iron_ore", 30, room.at(-5, 1, 2)),
                new OreSpot("gold_ore", 45, room.at(-2, 0, 4)));
        room.set(5, 0, 0, Blocks.DIAMOND_ORE);
        room.set(-5, 1, 2, Blocks.IRON_ORE);
        room.set(-2, 0, 4, Blocks.GOLD_ORE);

        AIPlayerEntity bot = h.spawn("AssistSightGT", room, 0, 0);
        h.enableAssist(bot);
        UUID id = bot.getUuid();
        Progress p = new Progress();
        Map<String, Integer> firstRay = new LinkedHashMap<>();
        boolean[] evaluated = {false};
        int[] logDeadline = {0};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    h.assertStrict(bot, "assist_visible_ore");
                    freeze(bot, TaskOrigin.Kind.MISSION, "gametest_assist_visible_ore");
                    p.assignedAt = p.tick;
                }
                return;
            }
            MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
            if (state == null) {
                h.require(p.tick - p.assignedAt < 40,
                        "a forced bot with a real-origin mining task in an underground fixture never got assist state");
                return;
            }
            int rays = (int) state.lifetimeRays();
            if (!evaluated[0]) {
                for (OreSpot ore : ores) {
                    if (!firstRay.containsKey(ore.id()) && state.sightings().contains(ore.pos())) {
                        firstRay.put(ore.id(), rays);
                    }
                }
                if (rays < 2 * SWEEP_RAYS) {
                    return;
                }
                for (OreSpot ore : ores) {
                    SightingLedger.Sighting sighting = state.sightings().get(ore.pos());
                    h.require(sighting != null, ore.id() + " in plain view at " + ore.pos().toShortString()
                            + " was not in the sighting ledger after " + rays + " rays (two sweeps = "
                            + 2 * SWEEP_RAYS + "); " + describe(state));
                    h.require(sighting.blockId().equals(ore.id()) && sighting.rawValue() == ore.value(),
                            "sighting of " + ore.id() + " carried the wrong id/value: " + sighting);
                }
                h.require(state.sightings().size() == ores.size(),
                        "ledger holds phantom entries: " + state.sightings().snapshotSortedByValueDesc());
                Map<String, BotProfiler.Stat> profile = BotProfiler.INSTANCE.snapshot(id);
                h.require(profile.containsKey(ViewSweeper.SECTION_SWEEP) && profile.containsKey(ViewSweeper.SECTION_FOLD),
                        "the sweep did not record its assist_* profiler sections: " + profile.keySet());
                h.assertStrict(bot, "assist_visible_ore_end");
                LOG.info("[assist-gametest] visible_ore rays={} first_seen_at_rays={} unknown_rays={} chunks_waived={} {} sweep_ms={}",
                        rays, firstRay, state.counters().unknownRays, p.chunksWaived, describe(state),
                        stat(profile, ViewSweeper.SECTION_SWEEP));
                evaluated[0] = true;
                logDeadline[0] = p.tick + 80;
                return;
            }
            // The log lines are written by a background thread: give them a moment, then check the record a
            // reviewer would read ("did the sensor run, and what did it believe it saw").
            List<String> lines = botLog(bot.getGameProfile().name());
            if (lines == null || !hasSpawnLine(lines)) {
                LOG.info("[assist-gametest] visible_ore log check skipped (per-bot log unavailable)");
                h.pass();
                return;
            }
            List<String> events = assistEvents(lines);
            boolean complete = events.contains("assist_gate") && events.contains("assist_sense_enabled")
                    && lines.stream().anyMatch(l -> l.contains("event=assist_sighting")
                    && l.contains("block='diamond_ore'"));
            if (complete) {
                LOG.info("[assist-gametest] visible_ore log events={}", events);
                h.pass();
            } else {
                h.require(p.tick < logDeadline[0],
                        "shadow log lacks the expected lines (assist_gate, assist_sense_enabled, assist_sighting"
                                + " diamond_ore); saw " + events);
            }
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 1b. The same, while a real OreDig mission actually mines (break peek included)
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:assist_sense_real_ore_dig", maxTicks = 700)
    public void realOreDigMissionIsSensedWhileItMines(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(30, -4, 4, -3, 3, 4);
        // Eye-level ore: the broken cell is then in plain view of the bot that broke it (a floor-level hole in a wall is not).
        BlockPos coal = room.at(5, 1, 0);
        BlockPos emerald = room.at(6, 1, 0);
        room.set(5, 1, 0, Blocks.COAL_ORE);
        room.set(6, 1, 0, Blocks.EMERALD_ORE);
        room.set(-5, 1, 1, Blocks.IRON_ORE);

        AIPlayerEntity bot = h.spawn("AssistDigGT", room, 0, 0);
        h.enableAssist(bot);
        // OreDig digs with the cheapest sufficient "channel" pick and keeps the better one for the ore itself.
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        // A sacrificial block for OreDig's drop-catch support (it refuses to mine without one to spend).
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 8));
        UUID id = bot.getUuid();
        Progress p = new Progress();
        OreDigTask[] task = new OreDigTask[1];
        int[] brokeAt = {-1};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    h.assertStrict(bot, "assist_real_dig");
                    task[0] = new OreDigTask(Set.of(Blocks.COAL_ORE), 1);
                    TaskManager.INSTANCE.assign(bot, task[0],
                            TaskOrigin.of(TaskOrigin.Kind.PLAYER_COMMAND, "gametest_assist_real_dig"));
                    p.assignedAt = p.tick;
                }
                return;
            }
            MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
            if (state == null) {
                h.require(p.tick - p.assignedAt < 60, "a real OreDig mission never produced assist state");
                return;
            }
            boolean broken = h.world(room).getBlockState(coal).isAir();
            if (!broken) {
                h.require(!state.sightings().contains(emerald),
                        "the emerald hidden behind the target coal entered the ledger before the bot broke the coal: "
                                + describe(state));
                h.require(task[0].state() == TaskState.RUNNING || task[0].state() == TaskState.PENDING,
                        "OreDig ended before mining the coal: " + task[0].state() + ":" + task[0].failureReason());
                h.require(p.tick - p.assignedAt < 550, "OreDig never mined the coal: " + task[0].describe());
                return;
            }
            if (brokeAt[0] < 0) {
                brokeAt[0] = p.tick;
            }
            SightingLedger.Sighting revealed = state.sightings().get(emerald);
            if (revealed == null) {
                h.require(p.tick - brokeAt[0] < 60,
                        "the emerald exposed by the bot's own break never entered the ledger: " + describe(state));
                return;
            }
            h.require(revealed.blockId().equals("emerald_ore") && revealed.rawValue() == 90,
                    "revealed emerald carried the wrong id/value: " + revealed);
            h.require(state.counters().peekedBreaks >= 1, "the bot's real break was never peeked: " + describe(state));
            h.require(BotEdits.wasDug(bot, coal), "the bot's own break was not recorded in its dug ring: " + describe(state)
                    + " occupancy=" + (state.occupancyIfPresent() == null ? -1 : state.occupancyIfPresent().get(coal)));
            h.require(state.counters().breaksUnconfirmed == 0,
                    "an eye-level break in plain view was left unconfirmed: " + describe(state));
            h.require(state.occupancyIfPresent() != null
                            && state.occupancyIfPresent().get(coal) == ObservedOccupancy.AIR,
                    "the mined coal cell is not AIR in the occupancy window");
            h.require(!state.sightings().contains(coal),
                    "the consumed coal is still remembered as a sighting");
            h.assertStrict(bot, "assist_real_dig_end");
            LOG.info("[assist-gametest] real_ore_dig broke_at_tick={} revealed_after_ticks={} {}",
                    brokeAt[0] - p.assignedAt, p.tick - brokeAt[0], describe(state));
            h.pass();
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 2. The x-ray canary: an ore behind one stone layer is never sighted
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:assist_sense_enclosed_ore", maxTicks = 500)
    public void oreBehindOneStoneLayerIsNeverSighted(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(40, -4, 4, -3, 3, 3);
        BlockPos control = room.at(5, 0, 0);
        room.set(5, 0, 0, Blocks.IRON_ORE);
        List<BlockPos> hidden = List.of(
                room.at(6, 0, 0),
                room.at(-6, 0, 0),
                room.at(0, 4, 0),
                room.at(1, -2, 0));
        room.set(6, 0, 0, Blocks.EMERALD_ORE);
        room.set(-6, 0, 0, Blocks.DIAMOND_ORE);
        room.set(0, 4, 0, Blocks.DIAMOND_ORE);
        room.set(1, -2, 0, Blocks.DIAMOND_ORE);
        List<BlockPos> front = List.of(room.at(-5, 0, 0), room.at(0, 3, 0), room.at(1, -1, 0));
        for (BlockPos ore : hidden) {
            for (net.minecraft.util.math.Direction direction : net.minecraft.util.math.Direction.values()) {
                BlockState neighbour = room.world.getBlockState(ore.offset(direction));
                h.require(!neighbour.isAir() && !neighbour.isOf(Blocks.CAVE_AIR),
                        "fixture error: hidden ore at " + ore.toShortString() + " touches air on its "
                                + direction + " face");
            }
        }

        // The canary runs a long time on purpose: 256 rays per tick (the configured maximum) for two hundred ticks
        // is dozens of full sweeps, so a leak that needed an unlucky ray would show.
        h.replaceConfig(withRaysPerTick(256));
        AIPlayerEntity bot = h.spawn("AssistCanaryGT", room, 0, 0);
        h.enableAssist(bot);
        UUID id = bot.getUuid();
        Progress p = new Progress();

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    h.assertStrict(bot, "assist_canary");
                    freeze(bot, TaskOrigin.Kind.MISSION, "gametest_assist_canary");
                    p.assignedAt = p.tick;
                }
                return;
            }
            MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
            if (state == null) {
                h.require(p.tick - p.assignedAt < 40, "canary bot never got assist state");
                return;
            }
            for (BlockPos ore : hidden) {
                h.require(!state.sightings().contains(ore),
                        "X-RAY LEAK: enclosed ore at " + ore.toShortString() + " entered the sighting ledger after "
                                + state.lifetimeRays() + " rays");
            }
            if (state.lifetimeSweeps() < 20) {
                return;
            }
            SightingLedger.Sighting seen = state.sightings().get(control);
            h.require(seen != null && seen.blockId().equals("iron_ore") && seen.rawValue() == 30,
                    "positive control failed: the exposed iron ore was not sighted, so the canary proves nothing: "
                            + describe(state));
            h.require(state.sightings().size() == 1,
                    "ledger must hold exactly the exposed control ore: " + state.sightings().snapshotSortedByValueDesc());
            ObservedOccupancy occupancy = state.occupancyIfPresent();
            h.require(occupancy != null, "no occupancy window after " + state.lifetimeRays() + " rays");
            for (BlockPos ore : hidden) {
                h.require(occupancy.get(ore) == ObservedOccupancy.UNKNOWN,
                        "enclosed cell " + ore.toShortString() + " was observed (occupancy "
                                + occupancy.get(ore) + "); an unseen cell must stay UNKNOWN");
            }
            for (BlockPos stone : front) {
                h.require(occupancy.get(stone) == ObservedOccupancy.SOLID,
                        "the stone layer at " + stone.toShortString() + " in front of an enclosed ore was never hit by a ray");
            }
            h.assertStrict(bot, "assist_canary_end");
            h.assertNoAllowedCapabilityDecision(bot);
            LOG.info("[assist-gametest] enclosed_ore rays={} sweeps={} unknown_rays={} ledger={} {}",
                    state.lifetimeRays(), state.lifetimeSweeps(), state.counters().unknownRays,
                    state.sightings().size(), describe(state));
            h.pass();
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 3. An un-forced bot stays completely off (the harness default)
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:assist_sense_unforced_off", maxTicks = 700)
    public void unforcedBotStaysOff(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(50, -4, 4, -3, 3, 4);
        BlockPos coal = room.at(5, 1, 0);
        room.set(5, 1, 0, Blocks.COAL_ORE);
        room.set(-5, 1, 1, Blocks.IRON_ORE);

        AIPlayerEntity bot = h.spawn("AssistOffGT", room, 0, 0);
        // OreDig digs with the cheapest sufficient "channel" pick and keeps the better one for the ore itself.
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        // A sacrificial block for OreDig's drop-catch support (it refuses to mine without one to spend).
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 8));
        UUID id = bot.getUuid();
        h.require(!MiningAssistRuntime.isForced(id), "fixture error: the bot is forced");
        Progress p = new Progress();
        int[] brokeAt = {-1};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    h.assertStrict(bot, "assist_unforced");
                    OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1);
                    TaskManager.INSTANCE.assign(bot, task,
                            TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_assist_unforced"));
                    p.assignedAt = p.tick;
                }
                return;
            }
            h.require(MiningAssistRegistry.getIfPresent(id) == null,
                    "an un-forced bot got assist state under the harness default");
            h.require(!MiningAssistRuntime.enabledFor(bot), "the gate opened for an un-forced bot");
            String deny = MiningAssistRuntime.lastDenyReason(id);
            h.require(deny == null || deny.equals("harness_off"),
                    "an un-forced bot was refused for the wrong reason: " + deny);
            if (!h.world(room).getBlockState(coal).isAir()) {
                h.require(p.tick - p.assignedAt < 550, "OreDig never mined the coal with the assist off");
                return;
            }
            if (brokeAt[0] < 0) {
                brokeAt[0] = p.tick;
            }
            if (p.tick - brokeAt[0] < 25) {
                return;
            }
            // The bot mined for real, placed and broke blocks, and nothing assist-related happened at all.
            Map<String, BotProfiler.Stat> profile = BotProfiler.INSTANCE.snapshot(id);
            for (String section : profile.keySet()) {
                h.require(!section.startsWith("assist_"), "an un-forced bot recorded assist profiler section " + section);
            }
            h.require(!BotEdits.wasDug(bot, coal), "an un-forced bot's break was recorded in the assist dug ring");
            List<String> lines = botLog(bot.getGameProfile().name());
            if (lines != null && hasSpawnLine(lines)) {
                List<String> events = new ArrayList<>(assistEvents(lines));
                // With another forced bot elsewhere the gate is consulted (and logs its refusal); nothing else may appear.
                events.removeIf(event -> event.equals("assist_gate") && MiningAssistRuntime.senseConfigured());
                h.require(events.isEmpty(), "an un-forced bot produced assist log events: " + events);
            }
            LOG.info("[assist-gametest] unforced_off broke_at_tick={} sense_configured={} log_checked={}",
                    brokeAt[0] - p.assignedAt, MiningAssistRuntime.senseConfigured(), lines != null);
            h.pass();
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Non-real origins never sense (VERIFY, SAFETY, SYSTEM_BACKGROUND), a real one does
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:assist_sense_verify_origin", maxTicks = 500)
    public void verifyOriginNeverSenses(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(60, -3, 3, -3, 3, 3);
        room.set(4, 0, 0, Blocks.DIAMOND_ORE);
        AIPlayerEntity bot = h.spawn("AssistVerifyGT", room, 0, 0);
        h.enableAssist(bot);
        UUID id = bot.getUuid();
        Progress p = new Progress();
        TaskOrigin.Kind[] closed = {TaskOrigin.Kind.VERIFY, TaskOrigin.Kind.SAFETY, TaskOrigin.Kind.SYSTEM_BACKGROUND};
        int[] phase = {0};
        int[] phaseStart = {0};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    h.assertStrict(bot, "assist_verify_origin");
                    p.assignedAt = p.tick;
                    phaseStart[0] = p.tick;
                    freeze(bot, closed[0], "gametest_assist_origin_" + closed[0]);
                }
                return;
            }
            MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
            if (phase[0] < closed.length) {
                h.require(state == null, "a " + closed[phase[0]] + "-origin mining task got assist state: "
                        + (state == null ? "" : describe(state)));
                h.require(!BotProfiler.INSTANCE.snapshot(id).keySet().stream().anyMatch(s -> s.startsWith("assist_")),
                        "a " + closed[phase[0]] + "-origin task recorded assist profiler sections");
                if (p.tick - phaseStart[0] < 50) {
                    return;
                }
                h.require("origin".equals(MiningAssistRuntime.lastDenyReason(id)),
                        "the gate did not refuse " + closed[phase[0]] + " for its origin: "
                                + MiningAssistRuntime.lastDenyReason(id));
                phase[0]++;
                phaseStart[0] = p.tick;
                if (phase[0] < closed.length) {
                    freeze(bot, closed[phase[0]], "gametest_assist_origin_" + closed[phase[0]]);
                } else {
                    // Positive control: the very same bot in the very same fixture senses as soon as the origin is real.
                    freeze(bot, TaskOrigin.Kind.PLAYER_COMMAND, "gametest_assist_origin_real");
                }
                return;
            }
            if (state == null || state.lifetimeRays() < 200) {
                h.require(p.tick - phaseStart[0] < 60,
                        "the control (PLAYER_COMMAND origin) never sensed: gate deny="
                                + MiningAssistRuntime.lastDenyReason(id));
                return;
            }
            h.require(MiningAssistRuntime.lastDenyReason(id) == null, "gate still denies a real origin");
            LOG.info("[assist-gametest] verify_origin control_rays={}", state.lifetimeRays());
            h.pass();
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 4b. An open audit session, and a degraded TPS verdict, close the gate at once
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:assist_sense_audit_gate", maxTicks = 600)
    public void auditSessionAndDegradedTpsCloseTheGate(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(55, -3, 3, -3, 3, 3);
        room.set(4, 0, 0, Blocks.DIAMOND_ORE);
        AIPlayerEntity bot = h.spawn("AssistAuditGT", room, 0, 0);
        h.enableAssist(bot);
        h.onCleanup(() -> MiningEvidenceAudit.clear(bot));
        UUID id = bot.getUuid();
        Progress p = new Progress();
        int[] stage = {0};
        long[] mark = {0L};
        int[] markTick = {0};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    freeze(bot, TaskOrigin.Kind.MISSION, "gametest_assist_audit");
                    p.assignedAt = p.tick;
                }
                return;
            }
            MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
            switch (stage[0]) {
                case 0 -> {
                    if (state == null || state.lifetimeRays() < 200) {
                        h.require(p.tick - p.assignedAt < 60, "sensing never started");
                        return;
                    }
                    MiningEvidenceAudit.begin(bot, MiningEvidenceAudit.Target.DIAMOND);
                    h.require(MiningEvidenceAudit.hasSession(id), "fixture error: no audit session");
                    stage[0] = 1;
                    markTick[0] = p.tick;
                }
                case 1 -> {
                    // One tick of grace for the pass that was already running when the session began.
                    if (p.tick - markTick[0] == 2) {
                        mark[0] = state.lifetimeRays();
                    }
                    if (p.tick - markTick[0] < 30) {
                        return;
                    }
                    h.require("audit_session".equals(MiningAssistRuntime.lastDenyReason(id)),
                            "an open audit session did not close the gate: " + MiningAssistRuntime.lastDenyReason(id));
                    h.require(state.lifetimeRays() == mark[0],
                            "the sensor kept casting rays under an open audit session: " + mark[0] + " -> "
                                    + state.lifetimeRays());
                    MiningEvidenceAudit.clear(bot);
                    stage[0] = 2;
                    markTick[0] = p.tick;
                }
                case 2 -> {
                    // A cached refusal lives at most GateCache.TTL_TICKS (20) ticks.
                    if (state.lifetimeRays() > mark[0] + 100) {
                        h.require(MiningAssistRuntime.lastDenyReason(id) == null,
                                "gate reopened but still reports " + MiningAssistRuntime.lastDenyReason(id));
                        LOG.info("[assist-gametest] audit_gate reopened_after_ticks={}", p.tick - markTick[0]);
                        // Second closer: the TPS verdict (pinned by the harness; flipped here to "degraded").
                        MiningAssistRuntime.setTestTpsDegraded(Boolean.TRUE);
                        stage[0] = 3;
                        markTick[0] = p.tick;
                        return;
                    }
                    h.require(p.tick - markTick[0] < 60, "sensing did not resume after the audit session ended");
                }
                case 3 -> {
                    if (p.tick - markTick[0] == 3) {
                        mark[0] = state.lifetimeRays();
                    }
                    if (p.tick - markTick[0] < 30) {
                        return;
                    }
                    h.require("tps_degraded".equals(MiningAssistRuntime.lastDenyReason(id)),
                            "a degraded TPS verdict did not close the gate: " + MiningAssistRuntime.lastDenyReason(id));
                    h.require(state.lifetimeRays() == mark[0],
                            "the sensor kept casting rays under a degraded TPS verdict: " + mark[0] + " -> "
                                    + state.lifetimeRays());
                    MiningAssistRuntime.setTestTpsDegraded(Boolean.FALSE);
                    stage[0] = 4;
                    markTick[0] = p.tick;
                }
                case 4 -> {
                    if (state.lifetimeRays() > mark[0] + 100) {
                        h.require(MiningAssistRuntime.lastDenyReason(id) == null,
                                "gate reopened but still reports " + MiningAssistRuntime.lastDenyReason(id));
                        h.pass();
                        return;
                    }
                    h.require(p.tick - markTick[0] < 60, "sensing did not resume after the TPS verdict recovered");
                }
                default -> {
                }
            }
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 5. POI palette: a mineshaft scores as a structure, the bot's own edits do not
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:assist_sense_mineshaft_poi", maxTicks = 700)
    public void minePoiPaletteScoresAsStructureInShadow(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(70, -9, 9, -1, 1, 3);
        List<BlockPos> torches = List.of(room.at(2, 0, 1), room.at(-2, 0, -1));
        // Rails along the floor line, on either side of the bot.
        for (int x : new int[] {-8, -6, -4, -2, 2, 4, 6, 8}) {
            room.set(x, 0, 0, Blocks.RAIL);
        }
        // Four support frames: two fence posts and a plank beam each, as in a mineshaft.
        for (int x : new int[] {-7, -3, 3, 7}) {
            room.set(x, 0, -1, Blocks.OAK_FENCE);
            room.set(x, 1, -1, Blocks.OAK_FENCE);
            room.set(x, 0, 1, Blocks.OAK_FENCE);
            room.set(x, 1, 1, Blocks.OAK_FENCE);
            room.set(x, 2, -1, Blocks.OAK_PLANKS);
            room.set(x, 2, 0, Blocks.OAK_PLANKS);
            room.set(x, 2, 1, Blocks.OAK_PLANKS);
        }
        room.set(-5, 2, 0, Blocks.COBWEB);
        room.set(5, 2, 0, Blocks.COBWEB);
        room.set(-8, 1, 1, Blocks.COBWEB);
        room.set(8, 1, -1, Blocks.COBWEB);
        // Torches placed by the TEST, not by the bot: they are evidence.
        for (BlockPos torch : torches) {
            room.world.setBlockState(torch, Blocks.TORCH.getDefaultState(), Block.NOTIFY_ALL);
        }
        room.set(-4, 0, 1, Blocks.CHEST);
        var minecart = EntityType.CHEST_MINECART.create(room.world, SpawnReason.COMMAND);
        h.require(minecart != null, "could not create a chest minecart");
        BlockPos cart = room.at(4, 0, 0);
        minecart.refreshPositionAndAngles(cart.getX() + 0.5D, cart.getY() + 0.0625D, cart.getZ() + 0.5D, 0.0F, 0.0F);
        room.world.spawnEntity(minecart);

        AIPlayerEntity bot = h.spawn("AssistShaftGT", room, 0, 0);
        h.enableAssist(bot);
        h.onCleanup(minecart::discard);
        UUID id = bot.getUuid();
        Progress p = new Progress();
        int[] maxRank = {0};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    h.assertStrict(bot, "assist_mineshaft");
                    freeze(bot, TaskOrigin.Kind.MISSION, "gametest_assist_mineshaft");
                    p.assignedAt = p.tick;
                }
                return;
            }
            MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
            if (state == null) {
                h.require(p.tick - p.assignedAt < 40, "mineshaft bot never got assist state");
                return;
            }
            maxRank[0] = Math.max(maxRank[0], rank(state.lastPoiBand()));
            if (p.tick - p.assignedAt < 300) {
                return;
            }
            h.require(maxRank[0] >= 1, "the shadow POI evaluation never left NONE for a mineshaft palette: "
                    + describe(state) + " window=" + windowCounts(state));
            PoiDetector.Result result = PoiDetector.evaluate(bot, state, room.world, MiningAssistRuntime.serverTick(bot));
            PoiScorer.PoiScore score = result.score();
            Map<PoiBucket, Integer> counts = windowCounts(state);
            LOG.info("[assist-gametest] mineshaft band={} max_rank={} label={} s={} t={} e={} cells={} buckets={} weak_counted={} window={} entities={} {}",
                    result.band(), maxRank[0], result.label(), fmt(score.s()), fmt(score.t()), fmt(score.e()),
                    score.distinctCells(), score.distinctBuckets(), score.weakCounted(), counts,
                    result.entitiesCounted(), describe(state));
            h.require(result.band() == PoiScorer.Band.POSSIBLE || result.band() == PoiScorer.Band.STRUCTURE_CERTAIN,
                    "final band for the mineshaft palette was " + result.band() + " (" + counts + ")");
            h.require(score.s() >= 0.5D, "structure score too low for a mineshaft: " + score.s() + " " + counts);
            h.require(result.label().equals(PoiLabeler.MINESHAFT), "label was " + result.label());
            h.require(count(counts, PoiBucket.WOOD_BUILD) >= 4, "too few plank/fence cells seen: " + counts);
            h.require(count(counts, PoiBucket.RAIL) >= 2, "too few rail cells seen (decor pass): " + counts);
            h.require(count(counts, PoiBucket.WEB) >= 1, "no cobweb seen (decor pass): " + counts);
            h.require(score.weakCounted(), "torches placed by the test did not count as (weak) evidence: " + counts);
            h.require(score.e() >= 0.59D, "the visible chest minecart did not score as entity evidence: e=" + score.e());
            for (BlockPos torch : torches) {
                h.require(!BotEdits.wasPlaced(room.world, torch), "a test-placed torch is in the bot's placed ledger");
            }
            h.assertStrict(bot, "assist_mineshaft_end");
            h.assertNoAllowedCapabilityDecision(bot);
            h.pass();
        }));
    }

    @GameTest(environment = "minecraftai-gametest:assist_sense_own_edits_poi", maxTicks = 800)
    public void botOwnPlacementsNeverScoreAsStructure(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(85, -5, 5, -4, 4, 4);
        AIPlayerEntity bot = h.spawn("AssistEditsGT", room, 0, 0);
        h.enableAssist(bot);
        UUID id = bot.getUuid();
        Progress p = new Progress();
        // Phase 1: the palette that WOULD score (planks + cobweb, beside torches and cobblestone), placed by the
        // bot itself through BuildAction.placeBlockAt, the path every real placement takes.
        List<BlockPos> botPlanks = List.of(room.at(3, 0, 1), room.at(3, 0, 2), room.at(2, 0, 2),
                room.at(1, 0, 3), room.at(-1, 0, 3), room.at(-2, 0, 2));
        List<BlockPos> botWebs = List.of(room.at(-3, 0, 1), room.at(-3, 0, 0), room.at(-2, 0, -2));
        List<BlockPos> botTorches = List.of(room.at(2, 0, -3), room.at(-2, 0, -3));
        List<BlockPos> botCobble = List.of(room.at(3, 0, -1), room.at(3, 0, -2), room.at(-3, 0, -1), room.at(-3, 0, -2));
        // Phase 2: the same kinds of blocks placed by the TEST (not the bot) elsewhere in view.
        List<BlockPos> testPlanks = List.of(room.at(-4, 0, -3), room.at(-4, 0, -2), room.at(-4, 0, -1), room.at(-4, 0, 1),
                room.at(4, 0, -3), room.at(4, 0, -2), room.at(4, 0, 1), room.at(4, 0, 2));
        List<BlockPos> testWebs = List.of(room.at(-4, 1, -3), room.at(4, 1, -3), room.at(0, 2, -3), room.at(0, 2, 3));
        int[] phase = {0};
        int[] phaseStart = {0};
        int[] maxRank = {0};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    h.assertStrict(bot, "assist_own_edits");
                    freeze(bot, TaskOrigin.Kind.MISSION, "gametest_assist_own_edits");
                    p.assignedAt = p.tick;
                    phaseStart[0] = p.tick;
                    for (BlockPos pos : botPlanks) {
                        botPlace(h, bot, Items.OAK_PLANKS, pos);
                    }
                    for (BlockPos pos : botCobble) {
                        botPlace(h, bot, Items.COBBLESTONE, pos);
                    }
                    for (BlockPos pos : botTorches) {
                        botPlace(h, bot, Items.TORCH, pos);
                    }
                    // Cobwebs last: a web is solid to the outline ray a player aims with, so one placed early
                    // hides the floor behind it from every later placement.
                    for (BlockPos pos : botWebs) {
                        botPlace(h, bot, Items.COBWEB, pos);
                    }
                }
                return;
            }
            MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
            if (state == null) {
                h.require(p.tick - p.assignedAt < 40, "own-edits bot never got assist state");
                return;
            }
            maxRank[0] = Math.max(maxRank[0], rank(state.lastPoiBand()));
            if (phase[0] == 0) {
                h.require(maxRank[0] == 0, "the bot's own placements scored as a structure: band "
                        + state.lastPoiBand() + " " + windowCounts(state));
                if (p.tick - phaseStart[0] < 160) {
                    return;
                }
                // The rays did see the bot's planks (occupancy SOLID), and still they are not evidence.
                ObservedOccupancy occupancy = state.occupancyIfPresent();
                h.require(occupancy != null, "no occupancy window");
                int seenPlanks = 0;
                for (BlockPos pos : botPlanks) {
                    if (occupancy.get(pos) == ObservedOccupancy.SOLID) {
                        seenPlanks++;
                    }
                }
                h.require(seenPlanks >= 4, "only " + seenPlanks + " of the bot's planks were struck by rays; the control proves nothing");
                for (BlockPos pos : concat(botPlanks, botWebs, botTorches, botCobble)) {
                    h.require(!state.poiWindow().contains(pos),
                            "bot-placed block at " + pos.toShortString() + " is in the POI evidence window");
                }
                PoiDetector.Result quiet = PoiDetector.evaluate(bot, state, room.world, MiningAssistRuntime.serverTick(bot));
                h.require(quiet.band() == PoiScorer.Band.NONE && quiet.score().s() < 0.05D,
                        "control band was " + quiet.band() + " s=" + quiet.score().s());
                LOG.info("[assist-gametest] own_edits control band={} s={} seen_planks={} window={} {}",
                        quiet.band(), fmt(quiet.score().s()), seenPlanks, windowCounts(state), describe(state));
                // Twin: same kinds of blocks, placed by the test.
                for (BlockPos pos : testPlanks) {
                    room.world.setBlockState(pos, Blocks.OAK_PLANKS.getDefaultState(), Block.NOTIFY_ALL);
                }
                for (BlockPos pos : testWebs) {
                    room.world.setBlockState(pos, Blocks.COBWEB.getDefaultState(), Block.NOTIFY_ALL);
                }
                phase[0] = 1;
                phaseStart[0] = p.tick;
                return;
            }
            if (p.tick - phaseStart[0] < 200 && rank(state.lastPoiBand()) < 1) {
                return;
            }
            h.require(maxRank[0] >= 1, "the same palette placed by the test never scored either (sensor dead?): "
                    + describe(state) + " " + windowCounts(state));
            for (BlockPos pos : concat(botPlanks, botWebs, botTorches, botCobble)) {
                h.require(!state.poiWindow().contains(pos),
                        "bot-placed block at " + pos.toShortString() + " is in the POI evidence window (phase 2)");
                h.require(BotEdits.wasPlaced(room.world, pos), "BotEdits lost the bot's placement at " + pos.toShortString());
            }
            for (BlockPos pos : concat(testPlanks, testWebs)) {
                h.require(!BotEdits.wasPlaced(room.world, pos), "a test-placed block is in the bot's placed ledger");
            }
            int inWindow = 0;
            for (BlockPos pos : concat(testPlanks, testWebs)) {
                if (state.poiWindow().contains(pos)) {
                    inWindow++;
                }
            }
            h.require(inWindow >= 3, "only " + inWindow + " test-placed cells reached the evidence window");
            PoiDetector.Result twin = PoiDetector.evaluate(bot, state, room.world, MiningAssistRuntime.serverTick(bot));
            h.require(twin.band() == PoiScorer.Band.POSSIBLE || twin.band() == PoiScorer.Band.STRUCTURE_CERTAIN,
                    "twin band was " + twin.band() + " " + windowCounts(state));
            LOG.info("[assist-gametest] own_edits twin band={} s={} in_window={} window={} {}",
                    twin.band(), fmt(twin.score().s()), inWindow, windowCounts(state), describe(state));
            h.assertStrict(bot, "assist_own_edits_end");
            h.pass();
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 6. Four bots, bounded cost
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:assist_sense_four_bot_cost", maxTicks = 800)
    public void fourBotsSensingCostStaysBounded(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(25, -9, 9, -9, 9, 6);
        // A busy cave: ores in the walls, a lava pool and a water pool (so hazard bookkeeping runs), a run of
        // mineshaft decor, and two POI entities.
        int[][] wallOres = {{10, 0, -4}, {10, 2, 4}, {-10, 1, 0}, {-10, 3, -6}, {-4, 0, 10}, {4, 2, 10},
                {-4, 3, -10}, {6, 0, -10}, {10, 4, 0}, {-10, 0, 5}, {0, 1, 10}, {0, 4, -10}};
        Block[] kinds = {Blocks.DIAMOND_ORE, Blocks.IRON_ORE, Blocks.GOLD_ORE, Blocks.REDSTONE_ORE, Blocks.COAL_ORE, Blocks.COPPER_ORE};
        for (int i = 0; i < wallOres.length; i++) {
            room.set(wallOres[i][0], wallOres[i][1], wallOres[i][2], kinds[i % kinds.length]);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -9; dz <= -7; dz++) {
                room.set(dx, -1, dz, Blocks.LAVA);
            }
            for (int dz = 7; dz <= 9; dz++) {
                room.set(dx, -1, dz, Blocks.WATER);
            }
        }
        for (int x = -8; x <= 8; x += 2) {
            room.set(x, 0, 0, Blocks.RAIL);
        }
        for (int x : new int[] {-3, 3}) {
            for (int z : new int[] {-1, 1}) {
                room.set(x, 0, z, Blocks.OAK_FENCE);
                room.set(x, 1, z, Blocks.OAK_FENCE);
                room.set(x, 2, z, Blocks.OAK_PLANKS);
            }
        }
        room.set(0, 2, 0, Blocks.COBWEB);
        room.set(-6, 1, 1, Blocks.COBWEB);
        room.set(6, 0, -1, Blocks.TORCH);
        room.set(-6, 0, -1, Blocks.TORCH);
        room.set(-8, 0, 3, Blocks.CHEST);
        var stand = EntityType.ARMOR_STAND.create(room.world, SpawnReason.COMMAND);
        var cart = EntityType.CHEST_MINECART.create(room.world, SpawnReason.COMMAND);
        h.require(stand != null && cart != null, "could not create the POI entities");
        BlockPos standPos = room.at(1, 0, -4);
        stand.refreshPositionAndAngles(standPos.getX() + 0.5D, standPos.getY(), standPos.getZ() + 0.5D, 0.0F, 0.0F);
        room.world.spawnEntity(stand);
        BlockPos cartPos = room.at(4, 0, 0);
        room.set(4, 0, 0, Blocks.RAIL);
        cart.refreshPositionAndAngles(cartPos.getX() + 0.5D, cartPos.getY() + 0.0625D, cartPos.getZ() + 0.5D, 0.0F, 0.0F);
        room.world.spawnEntity(cart);
        h.onCleanup(stand::discard);
        h.onCleanup(cart::discard);

        String[] names = {"AssistCostAGT", "AssistCostBGT", "AssistCostCGT", "AssistCostDGT"};
        int[][] spots = {{-6, -3}, {6, -3}, {-6, 3}, {6, 3}};
        List<AIPlayerEntity> bots = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            AIPlayerEntity bot = h.spawn(names[i], room, spots[i][0], spots[i][1]);
            h.enableAssist(bot);
            bots.add(bot);
        }
        int failuresBefore = MiningAssistRuntime.failures().size();
        int runTicks = 330;
        int warmupTicks = 60;
        double[] coldStepMs = new double[names.length];
        double[] coldPoiMs = new double[names.length];
        boolean[] warmed = {false};
        Progress p = new Progress();

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                for (AIPlayerEntity bot : bots) {
                    if (!h.settle(bot, p)) {
                        return;
                    }
                }
                for (AIPlayerEntity bot : bots) {
                    h.assertStrict(bot, "assist_cost");
                    freeze(bot, TaskOrigin.Kind.MISSION, "gametest_assist_cost");
                }
                p.assignedAt = p.tick;
                return;
            }
            for (AIPlayerEntity bot : bots) {
                h.require(TaskManager.INSTANCE.getActive(bot).isPresent(),
                        bot.getGameProfile().name() + " lost its mining task during the cost run");
            }
            if (!warmed[0] && p.tick - p.assignedAt >= warmupTicks) {
                // The first sweep step and the first POI evaluation of a JVM that has never run them are class-loading and
                // JIT-cold (tens of milliseconds, once). Record them as such, then measure the steady state the cost gate
                // is about: the profiler is cleared and everything below is what a running server pays every tick.
                for (int i = 0; i < bots.size(); i++) {
                    MiningAssistState warm = MiningAssistRegistry.getIfPresent(bots.get(i).getUuid());
                    h.require(warm != null && warm.counters().poiEvaluations >= 2L, "bot " + i + " has not evaluated the POI twice after the warm-up");
                    coldStepMs[i] = warm.counters().maxStepNanos / 1_000_000.0D;
                    coldPoiMs[i] = warm.counters().maxPoiNanos / 1_000_000.0D;
                    h.require(coldStepMs[i] < 1000.0D && coldPoiMs[i] < 1000.0D,
                            "cold-start cost is pathological: step " + fmt(coldStepMs[i]) + " ms, poi " + fmt(coldPoiMs[i]) + " ms");
                    BotProfiler.INSTANCE.clear(bots.get(i).getUuid());
                }
                warmed[0] = true;
            }
            if (p.tick - p.assignedAt < runTicks) {
                return;
            }
            double sweepAvgSum = 0.0D;
            double worstMax = 0.0D;
            for (AIPlayerEntity bot : bots) {
                String name = bot.getGameProfile().name();
                MiningAssistState state = MiningAssistRegistry.getIfPresent(bot.getUuid());
                h.require(state != null, name + " has no assist state after the run");
                h.require(state.lifetimeRays() >= (long) (runTicks - 20) * MiningAssistConfig.Sense.DEFAULT_RAYS_PER_TICK,
                        name + " cast too few rays (" + state.lifetimeRays() + "): sensing was interrupted or dropped");
                Map<String, BotProfiler.Stat> profile = BotProfiler.INSTANCE.snapshot(bot.getUuid());
                BotProfiler.Stat sweep = profile.get(ViewSweeper.SECTION_SWEEP);
                h.require(sweep != null && sweep.count() > 0, name + " recorded no assist_sweep samples");
                h.require(profile.containsKey(PoiDetector.SECTION_POI), name + " recorded no assist_poi samples: " + profile.keySet());
                for (Map.Entry<String, BotProfiler.Stat> entry : profile.entrySet()) {
                    if (!entry.getKey().startsWith("assist_")) {
                        continue;
                    }
                    worstMax = Math.max(worstMax, entry.getValue().maxMs());
                    h.require(entry.getValue().maxMs() < 50.0D, name + " " + entry.getKey()
                            + " max " + fmt(entry.getValue().maxMs()) + " ms exceeds the 50 ms bound");
                }
                h.require(sweep.avgMs() < 10.0D, name + " assist_sweep average " + fmt(sweep.avgMs()) + " ms exceeds 10 ms");
                sweepAvgSum += sweep.avgMs();
                LOG.info("[assist-gametest] cost bot={} cold_first_step_ms={} cold_poi_ms_first_two_evals={}", name,
                        fmt(coldStepMs[bots.indexOf(bot)]), fmt(coldPoiMs[bots.indexOf(bot)]));
                LOG.info("[assist-gametest] cost bot={} rays={} unknown={} throttled_out={} sweeps={} sections={} poi_evals={} {}",
                        name, state.lifetimeRays(), state.counters().unknownRays, state.counters().raysThrottledOut,
                        state.lifetimeSweeps(), statLine(profile), state.counters().poiEvaluations, describe(state));
            }
            h.require(MiningAssistRuntime.failures().size() == failuresBefore,
                    "the coordinator's exception fence fired during the cost run");
            List<String> failures = new ArrayList<>();
            for (AIPlayerEntity bot : bots) {
                List<String> lines = botLog(bot.getGameProfile().name());
                if (lines != null && hasSpawnLine(lines)) {
                    failures.addAll(assistEvents(lines).stream().filter(e -> e.equals("assist_tick_failed")).toList());
                }
            }
            h.require(failures.isEmpty(), "assist_tick_failed was logged: " + failures);
            LOG.info("[assist-gametest] cost total: four bots, assist_sweep avg sum {} ms per tick, worst single section max {} ms, run_ticks={}",
                    fmt(sweepAvgSum), fmt(worstMax), runTicks);
            h.pass();
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 7. Design "done when": recall of ores within 8 blocks after two sweeps
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:assist_sense_recall", maxTicks = 500)
    public void recallWithinEightBlocksAfterTwoSweeps(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(35, -6, 6, -6, 6, 10);
        // Four 3x3 pillars, each carrying single-face ores in the middle of the two faces that look at the bot.
        int[][] pillars = {{4, 4}, {4, -4}, {-4, 4}, {-4, -4}};
        for (int[] pillar : pillars) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int y = 0; y < 10; y++) {
                        room.set(pillar[0] + dx, y, pillar[1] + dz, Blocks.STONE);
                    }
                }
            }
        }
        Block[] kinds = {Blocks.DIAMOND_ORE, Blocks.IRON_ORE, Blocks.GOLD_ORE, Blocks.REDSTONE_ORE, Blocks.LAPIS_ORE,
                Blocks.EMERALD_ORE, Blocks.COAL_ORE, Blocks.COPPER_ORE};
        Map<BlockPos, String> category = new LinkedHashMap<>();
        int[] next = {0};
        java.util.function.BiConsumer<BlockPos, String> put = (pos, group) -> {
            room.world.setBlockState(pos, kinds[next[0]++ % kinds.length].getDefaultState(), Block.NOTIFY_ALL);
            category.put(pos.toImmutable(), group);
        };
        // Wall ores: four walls, three lateral offsets, two heights (all within 8 blocks of the eye).
        for (int lat : new int[] {-2, 0, 2}) {
            for (int y : new int[] {0, 2}) {
                put.accept(room.at(7, y, lat), "wall");
                put.accept(room.at(-7, y, lat), "wall");
                put.accept(room.at(lat, y, 7), "wall");
                put.accept(room.at(lat, y, -7), "wall");
            }
        }
        for (int[] pillar : pillars) {
            int sx = Integer.signum(pillar[0]);
            int sz = Integer.signum(pillar[1]);
            for (int y : new int[] {1, 3}) {
                put.accept(room.at(pillar[0] - sx, y, pillar[1]), "pillar");
                put.accept(room.at(pillar[0], y, pillar[1] - sz), "pillar");
            }
        }
        // Floor ores 2 to 4.5 blocks away, exposed face up.
        int[][] floor = {{2, 1}, {-2, -1}, {1, -3}, {-1, 3}, {3, -2}, {-3, 2}, {0, -4}, {0, 4}};
        for (int[] cell : floor) {
            put.accept(room.at(cell[0], -1, cell[1]), "floor");
        }
        AIPlayerEntity bot = h.spawn("AssistRecallGT", room, 0, 0);
        h.enableAssist(bot);
        UUID id = bot.getUuid();
        Vec3d eyeAtStart = bot.getEyePos();
        for (BlockPos ore : category.keySet()) {
            double d = Math.sqrt(ore.toCenterPos().squaredDistanceTo(eyeAtStart));
            h.require(d <= 8.05D, "fixture error: ore at " + ore.toShortString() + " is " + fmt(d) + " blocks from the eye");
        }
        Progress p = new Progress();

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    h.assertStrict(bot, "assist_recall");
                    freeze(bot, TaskOrigin.Kind.MISSION, "gametest_assist_recall");
                    p.assignedAt = p.tick;
                }
                return;
            }
            MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
            if (state == null) {
                h.require(p.tick - p.assignedAt < 40, "recall bot never got assist state");
                return;
            }
            if (state.lifetimeRays() < 2 * SWEEP_RAYS) {
                return;
            }
            Map<String, int[]> groups = new LinkedHashMap<>();
            List<String> misses = new ArrayList<>();
            int found = 0;
            for (Map.Entry<BlockPos, String> entry : category.entrySet()) {
                boolean seen = state.sightings().contains(entry.getKey());
                int[] counts = groups.computeIfAbsent(entry.getValue(), k -> new int[2]);
                counts[1]++;
                if (seen) {
                    found++;
                    counts[0]++;
                } else {
                    Vec3d eye = eyeAtStart;
                    misses.add(entry.getValue() + "@" + entry.getKey().toShortString() + " d="
                            + fmt(Math.sqrt(entry.getKey().toCenterPos().squaredDistanceTo(eye))));
                }
            }
            double recall = found / (double) category.size();
            StringBuilder perGroup = new StringBuilder();
            groups.forEach((group, counts) -> perGroup.append(group).append('=').append(counts[0]).append('/')
                    .append(counts[1]).append(' '));
            LOG.info("[assist-gametest] recall rays={} found={}/{} recall={} groups={} misses={} unknown_rays={} chunks_waived={} {}",
                    state.lifetimeRays(), found, category.size(), fmt(recall), perGroup.toString().trim(), misses,
                    state.counters().unknownRays, p.chunksWaived, describe(state));
            h.require(recall >= 0.90D, "recall after two sweeps was " + fmt(recall) + " (" + found + "/"
                    + category.size() + "), design requires 0.90; missed " + misses);
            h.pass();
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 7b. Pause, abort, re-assign and despawn leave nothing behind
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:assist_sense_lifecycle", maxTicks = 500)
    public void pauseAbortAndDespawnMidSenseLeaveNoLeakedState(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(45, -3, 3, -3, 3, 3);
        room.set(4, 0, 0, Blocks.DIAMOND_ORE);
        AIPlayerEntity bot = h.spawn("AssistLifeGT", room, 0, 0);
        h.enableAssist(bot);
        UUID id = bot.getUuid();
        String name = bot.getGameProfile().name();
        Progress p = new Progress();
        MineTask[] task = new MineTask[1];
        int[] stage = {0};
        int[] stageStart = {0};
        long[] mark = {0L};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    task[0] = freeze(bot, TaskOrigin.Kind.MISSION, "gametest_assist_life");
                    p.assignedAt = p.tick;
                    stageStart[0] = p.tick;
                }
                return;
            }
            MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
            int since = p.tick - stageStart[0];
            switch (stage[0]) {
                case 0 -> {
                    if (state == null || state.lifetimeRays() < 300) {
                        h.require(since < 60, "sensing never started");
                        return;
                    }
                    // Pause mid-sense: the task leaves the active slot, so the sensor must go quiet.
                    TaskManager.INSTANCE.pauseUserIntent(bot, "gametest_assist_pause");
                    h.require(TaskManager.INSTANCE.getActive(bot).isEmpty(), "fixture error: pause left an active task");
                    stage[0] = 1;
                    stageStart[0] = p.tick;
                }
                case 1 -> {
                    if (since == 2) {
                        mark[0] = state.lifetimeRays();
                    }
                    if (since < 25) {
                        return;
                    }
                    h.require(state.lifetimeRays() == mark[0],
                            "the sensor kept sweeping while the mining task was paused: " + mark[0] + " -> "
                                    + state.lifetimeRays());
                    // Resume, and freeze again in the same call so no real task tick happens in between.
                    TaskManager.INSTANCE.resumeUserIntent(bot, "gametest_assist_resume");
                    h.require(TaskManager.INSTANCE.getActive(bot).orElse(null) == task[0], "resume did not restore the task");
                    task[0].pause(bot);
                    stage[0] = 2;
                    stageStart[0] = p.tick;
                }
                case 2 -> {
                    if (since < 25) {
                        return;
                    }
                    h.require(state.lifetimeRays() > mark[0] + 500,
                            "the sensor did not resume after the task resumed: " + mark[0] + " -> " + state.lifetimeRays());
                    // Abort mid-sense: no task at all.
                    TaskManager.INSTANCE.abort(bot);
                    stage[0] = 3;
                    stageStart[0] = p.tick;
                }
                case 3 -> {
                    if (since == 2) {
                        mark[0] = state.lifetimeRays();
                    }
                    if (since < SenseStatus.DISABLE_GRACE_TICKS + 15) {
                        return;
                    }
                    h.require(state.lifetimeRays() == mark[0], "the sensor swept with no task at all");
                    h.require(!state.status().sensing(),
                            "the sensing session was not closed after " + since + " quiet ticks");
                    freeze(bot, TaskOrigin.Kind.MISSION, "gametest_assist_life_again");
                    stage[0] = 4;
                    stageStart[0] = p.tick;
                }
                case 4 -> {
                    if (state.lifetimeRays() <= mark[0] + 300) {
                        h.require(since < 60, "the sensor did not restart on a new task");
                        return;
                    }
                    h.require(state.status().sensing(), "state does not report an open sensing session");
                    // Despawn mid-sense: every per-bot piece of assist state must be gone.
                    h.despawnNow(name);
                    h.require(MiningAssistRegistry.getIfPresent(id) == null, "assist state leaked past the bot's despawn");
                    h.require(!MiningAssistRuntime.isForced(id), "the forced flag leaked past the bot's despawn");
                    h.require(MiningAssistRuntime.lastDenyReason(id) == null, "a gate verdict leaked past the bot's despawn");
                    h.require(BotProfiler.INSTANCE.snapshot(id).isEmpty(), "profiler samples leaked past the bot's despawn");
                    stage[0] = 5;
                    stageStart[0] = p.tick;
                }
                case 5 -> {
                    if (since < 5) {
                        return;
                    }
                    h.require(MiningAssistRegistry.getIfPresent(id) == null, "assist state reappeared after the despawn");
                    List<String> lines = botLog(name);
                    if (lines != null && hasSpawnLine(lines)) {
                        List<String> events = assistEvents(lines);
                        long enabled = events.stream().filter(e -> e.equals("assist_sense_enabled")).count();
                        long disabled = events.stream().filter(e -> e.equals("assist_sense_disabled")).count();
                        LOG.info("[assist-gametest] lifecycle events enabled={} disabled={} all={}", enabled, disabled, events);
                        // Two sessions (the first, and the one after the abort); the 25-tick pause is inside the
                        // 40-tick grace and must not have logged a disable/enable pair.
                        h.require(enabled == 2 && disabled == 1,
                                "session start/end lines are wrong (enabled=" + enabled + " disabled=" + disabled + "): " + events);
                    }
                    h.pass();
                }
                default -> {
                }
            }
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 7c. Every mining class senses; other tasks do not
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:assist_sense_task_classes", maxTicks = 600)
    public void everyMiningClassSensesAndOtherTasksDoNot(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(65, -3, 3, -3, 3, 3);
        room.set(4, 0, 0, Blocks.DIAMOND_ORE);
        AIPlayerEntity bot = h.spawn("AssistClassGT", room, 0, 0);
        h.enableAssist(bot);
        UUID id = bot.getUuid();
        BlockPos feet = bot.getBlockPos();
        List<Task> sensed = List.of(
                new OreDigTask(Set.of(Blocks.COAL_ORE), 1),
                new DigDownTask(Blocks.DIAMOND_ORE, 1),
                new DescendToYTask(feet.getY() - 4),
                new MineTask(Blocks.OBSIDIAN, 1),
                new MineValuablesTask(8));
        Progress p = new Progress();
        int[] index = {0};
        int[] stageStart = {0};
        long[] mark = {0L};
        boolean[] started = {false};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    h.assertStrict(bot, "assist_classes");
                    p.assignedAt = p.tick;
                }
                return;
            }
            MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
            if (index[0] < sensed.size()) {
                Task task = sensed.get(index[0]);
                if (!started[0]) {
                    mark[0] = state == null ? 0L : state.lifetimeRays();
                    TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_assist_class"));
                    task.pause(bot);
                    started[0] = true;
                    stageStart[0] = p.tick;
                    return;
                }
                if (p.tick - stageStart[0] < 16) {
                    return;
                }
                h.require(state != null && state.lifetimeRays() - mark[0] >= 300,
                        task.name() + " was not sensed: rays " + mark[0] + " -> "
                                + (state == null ? "no state" : state.lifetimeRays()));
                LOG.info("[assist-gametest] classes {} sensed rays_gained={}", task.name(), state.lifetimeRays() - mark[0]);
                index[0]++;
                started[0] = false;
                return;
            }
            // Not a mining class: a real origin and an active task, but nothing to sense for.
            if (!started[0]) {
                mark[0] = state.lifetimeRays();
                TaskManager.INSTANCE.assign(bot, new HoldingTask(),
                        TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_assist_not_mining"));
                started[0] = true;
                stageStart[0] = p.tick;
                return;
            }
            if (p.tick - stageStart[0] < 25) {
                return;
            }
            h.require(state.lifetimeRays() - mark[0] <= MiningAssistConfig.Sense.DEFAULT_RAYS_PER_TICK,
                    "a non-mining task was sensed: rays " + mark[0] + " -> " + state.lifetimeRays());
            h.pass();
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 7c2. A real strip mine: the break peek and the bot's own torches, measured on a bot that really digs
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:assist_sense_strip_mine", maxTicks = 900)
    public void realStripMineIsPeekedAndItsOwnTorchesNeverScore(TestContext context) {
        Harness h = new Harness(context);
        // A small pocket inside a solid mass of stone, and a target ore that does not exist: OreDig strip-mines.
        Room room = h.newMass(90, -2, 2, -2, 2, 3, 22);
        AIPlayerEntity bot = h.spawn("AssistStripGT", room, 0, 0);
        h.enableAssist(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.TORCH, 16));
        UUID id = bot.getUuid();
        BlockPos start = bot.getBlockPos();
        Progress p = new Progress();
        OreDigTask[] task = new OreDigTask[1];
        int[] maxRank = {0};
        int failuresBefore = MiningAssistRuntime.failures().size();
        double[] firstMs = {-1.0D, -1.0D};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    h.assertStrict(bot, "assist_strip_mine");
                    task[0] = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1);
                    TaskManager.INSTANCE.assign(bot, task[0],
                            TaskOrigin.of(TaskOrigin.Kind.PLAYER_COMMAND, "gametest_assist_strip_mine"));
                    p.assignedAt = p.tick;
                }
                return;
            }
            MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
            if (state == null) {
                h.require(p.tick - p.assignedAt < 60, "a real strip-mining OreDig never produced assist state");
                return;
            }
            if (firstMs[0] < 0.0D && state.counters().steps >= 1L) {
                firstMs[0] = state.counters().maxStepNanos / 1_000_000.0D;
            }
            if (firstMs[1] < 0.0D && state.counters().poiEvaluations >= 1L) {
                firstMs[1] = state.counters().maxPoiNanos / 1_000_000.0D;
            }
            maxRank[0] = Math.max(maxRank[0], rank(state.lastPoiBand()));
            h.require(maxRank[0] == 0, "the bot's own strip-mine work scored as a point of interest: " + state.lastPoiBand()
                    + " " + windowCounts(state));
            h.require(task[0].state() == TaskState.RUNNING,
                    "the strip-mining task ended: " + task[0].state() + ":" + task[0].failureReason());
            if (p.tick - p.assignedAt < 450) {
                return;
            }
            SenseCounters c = state.counters();
            Map<String, BotProfiler.Stat> profile = BotProfiler.INSTANCE.snapshot(id);
            double moved = Math.sqrt(bot.getBlockPos().getSquaredDistance(start));
            int torches = 0;
            int torchesInLedger = 0;
            for (int x = -3; x <= 3; x++) {
                for (int y = -1; y <= 3; y++) {
                    for (int z = -26; z <= 3; z++) {
                        BlockPos pos = room.at(x, y, z);
                        BlockState cell = room.world.getBlockState(pos);
                        if (cell.isOf(Blocks.TORCH) || cell.isOf(Blocks.WALL_TORCH)) {
                            torches++;
                            if (BotEdits.wasPlaced(room.world, pos)) {
                                torchesInLedger++;
                            }
                        }
                    }
                }
            }
            LOG.info("[assist-gametest] strip_mine moved={} steps={} rays={} peeked_breaks={} breaks_unconfirmed={} peek_neighbours={}"
                            + " breakthroughs={} breakthroughs_deferred={} throttled_out={} torches_in_tunnel={} torches_in_ledger={}"
                            + " first_step_ms={} first_poi_ms={} max_band_rank={} sections={}",
                    fmt(moved), c.steps, state.lifetimeRays(), c.peekedBreaks, c.breaksUnconfirmed, c.peekNeighbours,
                    c.breakthroughs, c.breakthroughsDeferred, c.raysThrottledOut, torches, torchesInLedger,
                    fmt(firstMs[0]), fmt(firstMs[1]), maxRank[0],
                    statLine(profile));
            h.require(moved >= 6.0D, "the bot did not dig anywhere (moved " + fmt(moved) + " blocks): " + task[0].describe());
            h.require(c.steps >= 350L, "sensing was interrupted during the dig: steps=" + c.steps);
            h.require(c.peekedBreaks >= 10L, "too few breaks were peeked for a real dig: " + describe(state));
            h.require(torchesInLedger == torches,
                    "a torch the bot placed is missing from its placed ledger (" + torchesInLedger + "/" + torches + ")");
            BotProfiler.Stat sweep = profile.get(ViewSweeper.SECTION_SWEEP);
            h.require(sweep != null && sweep.avgMs() < 10.0D, "sweep average too high while digging: " + stat(profile, ViewSweeper.SECTION_SWEEP));
            h.require(MiningAssistRuntime.failures().size() == failuresBefore, "the coordinator's exception fence fired during the dig");
            h.assertStrict(bot, "assist_strip_mine_end");
            h.assertNoAllowedCapabilityDecision(bot);
            h.pass();
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 7d. The documented clocks: a cost summary per minute of sensing, state released after two idle minutes
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:assist_sense_clocks", maxTicks = 4600)
    public void summaryLineAndIdleReleaseFollowTheDocumentedClocks(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(75, -3, 3, -3, 3, 3);
        room.set(4, 0, 0, Blocks.DIAMOND_ORE);
        AIPlayerEntity bot = h.spawn("AssistClockGT", room, 0, 0);
        h.enableAssist(bot);
        UUID id = bot.getUuid();
        String name = bot.getGameProfile().name();
        Progress p = new Progress();
        int[] stage = {0};
        int[] stageStart = {0};
        int[] firstSense = {-1};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    freeze(bot, TaskOrigin.Kind.MISSION, "gametest_assist_clocks");
                    p.assignedAt = p.tick;
                    stageStart[0] = p.tick;
                }
                return;
            }
            MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
            int since = p.tick - stageStart[0];
            switch (stage[0]) {
                case 0 -> {
                    // A minute (1200 ticks) of sensing: the first cost summary is due.
                    if (state != null && firstSense[0] < 0) {
                        firstSense[0] = p.tick;
                    }
                    if (firstSense[0] < 0) {
                        h.require(since < 200, "sensing never started");
                        return;
                    }
                    if (p.tick - firstSense[0] < MiningAssistLog.SUMMARY_INTERVAL_TICKS + 40) {
                        return;
                    }
                    List<String> lines = botLog(name);
                    if (lines == null || !hasSpawnLine(lines)) {
                        LOG.info("[assist-gametest] clocks: per-bot log unavailable, summary check skipped");
                        stage[0] = 1;
                        stageStart[0] = p.tick;
                        TaskManager.INSTANCE.abort(bot);
                        return;
                    }
                    String summary = lines.stream()
                            .filter(l -> l.contains("event=assist_sense_summary") && l.contains("final='false'"))
                            .findFirst().orElse(null);
                    if (summary == null) {
                        h.require(p.tick - firstSense[0] < MiningAssistLog.SUMMARY_INTERVAL_TICKS + 200,
                                "no per-minute assist_sense_summary line after " + (p.tick - firstSense[0]) + " ticks of sensing");
                        return;
                    }
                    LOG.info("[assist-gametest] clocks summary line: {}", summary);
                    long rays = field(summary, "rays");
                    h.require(rays >= 40_000L && rays <= 60_000L, "summary rays out of range for one minute at 40/tick: " + rays);
                    h.require(field(summary, "steps") >= 1_100L, "summary steps too low: " + summary);
                    h.require(field(summary, "poi_evals") >= 50L, "summary poi_evals too low: " + summary);
                    TaskManager.INSTANCE.abort(bot);
                    stage[0] = 1;
                    stageStart[0] = p.tick;
                }
                case 1 -> {
                    // No task: the state must survive two idle minutes, then be released with a final cost line.
                    if (state != null) {
                        h.require(since < SenseStatus.IDLE_RELEASE_TICKS + 120,
                                "state was not released " + since + " ticks after the bot stopped mining");
                        return;
                    }
                    h.require(since >= SenseStatus.IDLE_RELEASE_TICKS - 5,
                            "state was released early, after only " + since + " idle ticks");
                    LOG.info("[assist-gametest] clocks state released after {} idle ticks", since);
                    stage[0] = 2;
                    stageStart[0] = p.tick;
                }
                case 2 -> {
                    List<String> lines = botLog(name);
                    if (lines == null || !hasSpawnLine(lines)) {
                        h.pass();
                        return;
                    }
                    boolean released = lines.stream().anyMatch(l -> l.contains("event=assist_state_released"));
                    boolean finalSummary = lines.stream()
                            .anyMatch(l -> l.contains("event=assist_sense_summary") && l.contains("final='true'"));
                    if (released && finalSummary) {
                        h.pass();
                        return;
                    }
                    h.require(since < 80, "release lines missing (assist_state_released=" + released
                            + ", final summary=" + finalSummary + "): " + assistEvents(lines));
                }
                default -> {
                }
            }
        }));
    }

    /** The integer value of {@code key='123'} in a structured log line, or -1. */
    private static long field(String line, String key) {
        Matcher matcher = Pattern.compile(" " + Pattern.quote(key) + "='(-?[0-9]+)'").matcher(line);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : -1L;
    }

    // ---------------------------------------------------------------------------------------------
    // Fixture and harness plumbing
    // ---------------------------------------------------------------------------------------------

    private record OreSpot(String id, int value, BlockPos pos) {
    }

    /** Per-test tick bookkeeping. */
    private static final class Progress {
        int tick;
        int assignedAt = -1;
        int chunkWaitStart = -1;
        boolean chunksWaived;
    }

    /** Cleanup-on-failure, strict-capability and readiness plumbing shared by every test. */
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

        ServerWorld world(Room room) {
            return room.world;
        }

        /** Builds a fixture room that this harness hands back as air when the test ends, pass or fail. */
        Room newRoom(int relY, int minDx, int maxDx, int minDz, int maxDz, int height) {
            Room room = new Room(context, relY, minDx, maxDx, minDz, maxDz, height);
            rooms.add(room);
            return room;
        }

        /** A small pocket inside a large solid mass of stone (for tests where the bot has to dig). */
        Room newMass(int relY, int minDx, int maxDx, int minDz, int maxDz, int height, int shellH) {
            Room room = new Room(context, relY, minDx, maxDx, minDz, maxDz, height, shellH);
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

        /** Opts the bot in and pins the TPS verdict to "healthy" so a loaded test server cannot close the gate. */
        void enableAssist(AIPlayerEntity bot) {
            MiningAssistRuntime.setTestTpsDegraded(Boolean.FALSE);
            tpsOverridden = true;
            MiningAssistRuntime.forceEnable(bot.getUuid());
        }

        void replaceConfig(MiningAssistConfig config) {
            if (restoreConfig == null) {
                restoreConfig = MiningAssistRuntime.config();
            }
            MiningAssistRuntime.install(config);
        }

        void onCleanup(Runnable cleanup) {
            cleanups.add(cleanup);
        }

        void despawnNow(String name) {
            AIPlayerManager.INSTANCE.despawn(context.getWorld().getServer(), name);
            bots.remove(name);
        }

        /**
         * True once the bot is underground by the world's own sky test and the chunk ring around it is loaded.
         * A chunk ring that never loads is waived after 60 ticks (and reported through {@code chunksWaived}):
         * the sensor treats such rays as unknown, which is exactly what the run should then show.
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

        void assertStrict(AIPlayerEntity bot, String label) {
            require(MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                    "GameTest must run under strict_survival, got " + MinecraftAiConfig.get().profile());
            for (PrivilegedCapability capability : PrivilegedCapability.values()) {
                require(!CapabilityRuntime.decide(bot, capability, label).allowed(),
                        "strict_survival unexpectedly allowed " + capability);
            }
        }

        /**
         * The privileged-read counter of the whole run. The audit session that normally counts allowed capability
         * decisions cannot be used on a sensing bot (an open session closes the assist gate by design), so this reads
         * the record of every capability decision the bot made from the bot's own log and requires that none was allowed.
         */
        void assertNoAllowedCapabilityDecision(AIPlayerEntity bot) {
            List<String> lines = botLog(bot.getGameProfile().name());
            if (lines == null || !hasSpawnLine(lines)) {
                // Same unavailable-log skip as OreDigOpportunisticGameTests: say so instead of passing silently.
                LOG.warn("[capability-canary skipped: no per-bot log] {}", bot.getGameProfile().name());
                return;
            }
            long allowed = lines.stream()
                    .filter(l -> l.contains("event=capability_decision") && l.contains("allowed='true'"))
                    .count();
            long denied = lines.stream().filter(l -> l.contains("event=capability_decision")).count() - allowed;
            LOG.info("[assist-gametest] capability decisions for {}: allowed={} denied_lines={}",
                    bot.getGameProfile().name(), allowed, denied);
            require(allowed == 0, "a privileged capability was ALLOWED for " + bot.getGameProfile().name() + " during the run");
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

    /** A real mining-class task that is the bot's active task but does nothing: paused in place. */
    private static MineTask freeze(AIPlayerEntity bot, TaskOrigin.Kind kind, String reason) {
        MineTask task = new MineTask(Blocks.OBSIDIAN, 1);
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(kind, reason));
        task.pause(bot);
        return task;
    }

    private static MiningAssistConfig withRaysPerTick(int rays) {
        JsonObject sense = new JsonObject();
        sense.addProperty("raysPerTick", rays);
        JsonObject section = new JsonObject();
        section.add("sense", sense);
        JsonObject root = new JsonObject();
        root.add(MiningAssistConfig.FILE_SECTION, section);
        return MiningAssistConfig.parse(root, key -> null, MiningAssistRuntime.SHIPPED_DEFAULT_MODE,
                MiningAssistRuntime.harnessDefaultOff());
    }

    /** Places one block through the bot's real placement path (BuildAction.placeBlockAt) and checks it was recorded. */
    private static void botPlace(Harness h, AIPlayerEntity bot, Item item, BlockPos destination) {
        bot.getInventory().setSelectedSlot(0);
        bot.getInventory().getMainStacks().set(0, new ItemStack(item, 8));
        bot.getInventory().markDirty();
        ActionResult result = BuildAction.placeBlockAt(bot, destination);
        BlockPos relative = destination.subtract(bot.getBlockPos());
        h.require(result.isSuccess(), "the bot could not place " + item + " at " + destination.toShortString()
                + " (" + relative.toShortString() + " from the bot, cell now "
                + bot.getEntityWorld().getBlockState(destination) + ", below "
                + bot.getEntityWorld().getBlockState(destination.down()) + "): " + result.reason());
        ServerWorld world = bot.getEntityWorld();
        h.require(!world.getBlockState(destination).isAir(), "placement of " + item + " left air at " + destination.toShortString());
        h.require(BotEdits.wasPlaced(world, destination),
                "BotEdits did not record the bot's own placement of " + item + " at " + destination.toShortString());
    }

    @SafeVarargs
    private static List<BlockPos> concat(List<BlockPos>... lists) {
        List<BlockPos> all = new ArrayList<>();
        for (List<BlockPos> list : lists) {
            all.addAll(list);
        }
        return all;
    }

    private static int rank(PoiScorer.Band band) {
        return switch (band) {
            case NONE -> 0;
            case POSSIBLE, CAVERN_ONLY -> 1;
            case STRUCTURE_CERTAIN -> 2;
            case MANDATORY -> 3;
        };
    }

    private static Map<PoiBucket, Integer> windowCounts(MiningAssistState state) {
        Map<PoiBucket, Integer> counts = new java.util.EnumMap<>(PoiBucket.class);
        for (PoiEvidenceWindow.Entry entry : state.poiWindow().structuralEntries()) {
            counts.merge(entry.bucket(), 1, Integer::sum);
        }
        for (PoiEvidenceWindow.Entry entry : state.poiWindow().flagOnlyEntries()) {
            counts.merge(entry.bucket(), 1, Integer::sum);
        }
        return counts;
    }

    private static int count(Map<PoiBucket, Integer> counts, PoiBucket bucket) {
        return counts.getOrDefault(bucket, 0);
    }

    private static String describe(MiningAssistState state) {
        SenseCounters c = state.counters();
        return "[rays=" + state.lifetimeRays() + " sweeps=" + state.lifetimeSweeps() + " steps=" + c.steps
                + " unknown=" + c.unknownRays + " decor_rays=" + c.decorRays + " decor_cells=" + c.decorEvidence
                + " sightings=" + state.sightings().size() + " peeked_breaks=" + c.peekedBreaks
                + " breaks_unconfirmed=" + c.breaksUnconfirmed + " poi_evals=" + c.poiEvaluations
                + " band=" + state.lastPoiBand() + "]";
    }

    private static String stat(Map<String, BotProfiler.Stat> profile, String section) {
        BotProfiler.Stat stat = profile.get(section);
        return stat == null ? "-" : "avg " + fmt(stat.avgMs()) + " p95 " + fmt(stat.p95Ms()) + " max " + fmt(stat.maxMs());
    }

    private static String statLine(Map<String, BotProfiler.Stat> profile) {
        StringBuilder line = new StringBuilder();
        for (Map.Entry<String, BotProfiler.Stat> entry : profile.entrySet()) {
            if (entry.getKey().startsWith("assist_")) {
                line.append(entry.getKey()).append("{n=").append(entry.getValue().count())
                        .append(" avg=").append(fmt(entry.getValue().avgMs()))
                        .append(" p95=").append(fmt(entry.getValue().p95Ms()))
                        .append(" max=").append(fmt(entry.getValue().maxMs())).append("ms} ");
            }
        }
        return line.toString().trim();
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static List<String> assistEvents(List<String> lines) {
        List<String> events = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = EVENT.matcher(line);
            if (matcher.find()) {
                events.add(matcher.group(1));
            }
        }
        return events;
    }

    /** A running task of no mining class: it ticks, does nothing, and is never sensed for. */
    private static final class HoldingTask extends AbstractTask {
        @Override
        public String name() {
            return "holding_work";
        }

        @Override
        public String describe() {
            return "Holding still";
        }

        @Override
        public double progress() {
            return 0.5D;
        }

        @Override
        protected void onStart(AIPlayerEntity bot) {
        }

        @Override
        protected void onTick(AIPlayerEntity bot) {
        }
    }
}

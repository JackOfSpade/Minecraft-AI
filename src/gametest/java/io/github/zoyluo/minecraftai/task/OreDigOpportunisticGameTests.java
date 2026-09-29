package io.github.zoyluo.minecraftai.task;

import com.google.gson.JsonObject;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.assist.AssistGate;
import io.github.zoyluo.minecraftai.mining.assist.AssistMode;
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
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static io.github.zoyluo.minecraftai.task.SensingArena.botLog;
import static io.github.zoyluo.minecraftai.task.SensingArena.hasSpawnLine;

/**
 * Real-Minecraft GameTests for mining-assist phase P1 (design {@code d4_r1_detour.md}, {@code d9_tests.md},
 * section G.6 of the P1 contract): the walk-only opportunistic valuables detour, running through the real
 * {@code OreDigTask} / {@code OreDigDetourEngine} / {@code DetourStartSelector} / {@code SafeGate} stack against a
 * live server, never the JUnit {@code FakeDetourHost}.
 *
 * <p>Every test seals its own stone-shelled room (the {@code MiningAssistSenseGameTests} pattern), force-enables
 * one bot ({@link MiningAssistRuntime#forceEnable}), installs a DETOUR-mode config for the run
 * ({@code harnessOff=true}, matching the shipped harness default; forcing never bypasses the origin/audit/TPS
 * gates, only the harness default, design M38) and gives the bot a real {@code OreDigTask} through a real origin
 * (MISSION or PLAYER_COMMAND; {@link #verifyOriginNeverDetours} is the one exception on purpose). Assertions read
 * only what the task/mission layer exposes: {@code OreDigTask.checkpoint()}, {@code TaskManager}'s active task and
 * its state, real block states, inventory contents and {@code OreClaims}; the published {@code MiningAssistState}
 * tuple ({@code detourOwner}/{@code detourPhase}) is the coordinator's own supervision channel (design M8) and is
 * read the same way {@code MiningAssistCoordinator} reads it, never the engine's private per-instance fields.</p>
 */
public final class OreDigOpportunisticGameTests {
    private static final Logger LOG = LoggerFactory.getLogger("minecraftai-detour-gametest");
    private static final BlockState STONE = Blocks.STONE.getDefaultState();
    private static final String ENV_PREFIX = "minecraftai-gametest:ore_dig_opportunistic_game_tests_";

    // ---------------------------------------------------------------------------------------------
    // 1. A visible valuable is mined, then the bot returns to the exact anchor (design 4.10, I6)
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = ENV_PREFIX + "visible_valuable_in_cave_is_mined_then_bot_returns_to_exact_anchor",
            maxTicks = 1800)
    public void visibleValuableInCaveIsMinedThenBotReturnsToExactAnchor(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(10, -3, 17, -4, 4, 4);
        hub(room);
        // dy=1, not dy=0: DetourHostImpl.poseFor's cardinal (same-level) stands all require
        // hasReliableObservedDropCatch(ore.down()), which can never pass for a floor-level ore (ore.down()
        // is always buried under the room's own solid floor slab, with no exposed face reachable without
        // breaking a stand's own required floor). One level up, approachGoalFor's own stand
        // (ore.down().offset(dir), i.e. the room's ordinary floor one step to the side) is the room's
        // normal, already-open, already-standable floor -- no drop-catch needed at all. A solid (not air)
        // roof one cell above the ore keeps breakGeometry's overhead check observable (a real cave vein
        // has rock above it; open air there can never itself be "observed as a block").
        BlockPos candidate = room.at(9, 1, 0);
        room.set(9, 1, 0, Blocks.DIAMOND_ORE);
        room.set(9, 2, 0, Blocks.STONE);

        AIPlayerEntity bot = h.spawn("OreDigDetourAnchorGT", room, 0, 0);
        h.enableDetour(bot, 256);
        // A stone pick is mandatory for OreDig's own strip/channel through ordinary rock: the channel-tool
        // policy floors every mined block (including the mission's own coal) at STONE tier and, for non-ore
        // rock, caps it there too (OreDigTask.failMissingMiningChannelTool / ToolSelector.equipMiningChannelTool),
        // so the mission never has to spend its iron pick on plain corridor stone.
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        UUID id = bot.getUuid();
        Progress p = new Progress();
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 40);
        AnchorRef anchor = new AnchorRef();
        boolean[] wasActive = {false};
        boolean[] done = {false};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                h.assertStrict(bot, "ore_dig_opportunistic_anchor");
                TaskManager.INSTANCE.assign(bot, task,
                        TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_detour_anchor"));
                p.assignedAt = p.tick;
                return;
            }
            requireNotFailed(h, task);
            if (done[0]) {
                return;
            }
            MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
            boolean activeNow = state != null && state.detourOwner() == task;
            Map<String, String> live = task.checkpoint();
            if (activeNow) {
                if (!wasActive[0]) {
                    anchor.capture(live);
                    wasActive[0] = true;
                } else {
                    anchor.requireUnchanged(h, live, p.tick);
                }
                return;
            }
            if (wasActive[0]) {
                // The detour just ended (published tuple cleared): the bot must be back at the exact anchor
                // face, and the checkpoint's strip numbers must still be the anchor's (design 4.10, I6).
                h.require(anchor.face != null, "detour was live but never captured an anchor");
                h.require(bot.getBlockPos().equals(anchor.face),
                        "bot did not return to the exact anchor: at " + bot.getBlockPos().toShortString()
                                + ", anchor " + anchor.face.toShortString());
                anchor.requireUnchanged(h, live, p.tick);
                h.require(!room.world.getBlockState(candidate).isOf(Blocks.DIAMOND_ORE),
                        "the visible diamond was never mined by the detour");
                h.assertStrict(bot, "ore_dig_opportunistic_anchor_end");
                done[0] = true;
                h.pass();
                return;
            }
            h.require(p.tick - p.assignedAt < 1700,
                    "the visible diamond was never detoured to (candidate never entered a live detour)");
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 2. The x-ray canary: a valuable sealed behind one stone layer is never detoured to (I1, I2)
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = ENV_PREFIX + "valuable_behind_one_stone_layer_is_never_detoured", maxTicks = 1200)
    public void valuableBehindOneStoneLayerIsNeverDetoured(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(20, -6, 6, -4, 4, 4);
        hub(room);
        BlockPos hidden = room.at(-8, 0, 0);
        room.world.setBlockState(hidden, Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
        assertFullyEnclosed(h, room, hidden);
        // dy=1 + a solid roof (not dy=0): see the giveItem/pose comment on test 1 in this file --
        // DetourHostImpl.poseFor's same-level cardinal stands can never pass hasReliableObservedDropCatch
        // for a floor-level ore, so the control must sit one level up for approachGoalFor's below-stand.
        BlockPos control = room.at(6, 1, 0);
        room.world.setBlockState(control, Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        room.world.setBlockState(room.at(6, 2, 0), STONE, Block.NOTIFY_ALL);

        AIPlayerEntity bot = h.spawn("OreDigDetourCanaryGT", room, 0, 0);
        h.enableDetour(bot, 256);
        // A stone pick is mandatory for OreDig's own strip/channel through ordinary rock: the channel-tool
        // policy floors every mined block (including the mission's own coal) at STONE tier and, for non-ore
        // rock, caps it there too (OreDigTask.failMissingMiningChannelTool / ToolSelector.equipMiningChannelTool),
        // so the mission never has to spend its iron pick on plain corridor stone.
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        UUID id = bot.getUuid();
        Progress p = new Progress();
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 40);

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                h.assertStrict(bot, "ore_dig_opportunistic_canary");
                TaskManager.INSTANCE.assign(bot, task,
                        TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_detour_canary"));
                p.assignedAt = p.tick;
                return;
            }
            requireNotFailed(h, task);
            h.require(room.world.getBlockState(hidden).isOf(Blocks.DIAMOND_ORE),
                    "X-RAY LEAK: the sealed diamond was mined at tick " + p.tick);
            MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
            if (state != null) {
                h.require(!state.sightings().contains(hidden),
                        "X-RAY LEAK: the sealed diamond entered the sighting ledger");
            }
            if (!room.world.getBlockState(control).isOf(Blocks.IRON_ORE)) {
                // The open, admissible control was mined: the sensing/detour pipeline is demonstrably alive,
                // so the fact the sealed diamond above was never touched actually proves something (I2).
                ObservedOccupancy occupancy = state == null ? null : state.occupancyIfPresent();
                h.require(occupancy == null || occupancy.get(hidden) == ObservedOccupancy.UNKNOWN,
                        "the enclosed cell was observed; an unseen cell must stay UNKNOWN");
                h.assertNoAllowedCapabilityDecision(bot);
                h.assertStrict(bot, "ore_dig_opportunistic_canary_end");
                h.pass();
                return;
            }
            h.require(p.tick - p.assignedAt < 1100,
                    "the open, admissible control iron was never mined; the canary proves nothing");
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 3. A vein of more than MEMBER_CAP valuables is followed up to the cap, then ends (design 4.8, 4.11)
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = ENV_PREFIX + "vein_follow_respects_cap_and_lease", maxTicks = 2200)
    public void veinFollowRespectsCapAndLease(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(30, -3, 17, -4, 4, 4);
        hub(room);
        int veinLength = 15;
        List<BlockPos> vein = new ArrayList<>();
        // dy=1 + a solid roof strip (not dy=0): see test 1's pose comment -- every member needs
        // approachGoalFor's below-stand, never poseFor's same-level cardinal one.
        for (int i = 0; i < veinLength; i++) {
            // Kept close to spawn (dx starts at 1, not farther out) so the near end of the vein is
            // reliably within ordinary perception radius of a bot still working hub()'s coal near dx=0,
            // regardless of which way OreDigTask's own strip tunnel happens to head first.
            BlockPos pos = room.at(1 + i, 1, 0);
            room.world.setBlockState(pos, Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
            room.world.setBlockState(room.at(1 + i, 2, 0), STONE, Block.NOTIFY_ALL);
            vein.add(pos);
        }

        AIPlayerEntity bot = h.spawn("OreDigDetourVeinGT", room, 0, 0);
        // maxRadius is widened so the vein-follow is bounded by MEMBER_CAP, not by the anchor radius.
        JsonObject detour = new JsonObject();
        detour.addProperty("maxRadius", 20);
        h.enableDetour(bot, 256, detour);
        // A stone pick for the mission's own channel/corridor (its coal-tunnel bottleneck is always stone
        // tier, never higher: see the giveItem comment on the earlier tests in this file); the diamond pick
        // is for the iron vein detour itself.
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        UUID id = bot.getUuid();
        Progress p = new Progress();
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 40);
        boolean[] wasActive = {false};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                h.assertStrict(bot, "ore_dig_opportunistic_vein");
                TaskManager.INSTANCE.assign(bot, task,
                        TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_detour_vein"));
                p.assignedAt = p.tick;
                return;
            }
            requireNotFailed(h, task);
            MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
            boolean activeNow = state != null && state.detourOwner() == task;
            if (activeNow) {
                wasActive[0] = true;
                int minedSoFar = minedCount(room.world, vein);
                h.require(minedSoFar <= OreDigDetourEngine.MEMBER_CAP,
                        "the vein-follow mined more than MEMBER_CAP (" + OreDigDetourEngine.MEMBER_CAP
                                + ") members while still active: " + minedSoFar);
                return;
            }
            if (!wasActive[0]) {
                h.require(p.tick - p.assignedAt < 1400, "the 15-block vein was never detoured to");
                return;
            }
            int mined = minedCount(room.world, vein);
            h.require(mined >= 1, "the vein-follow ended having mined nothing");
            h.require(mined <= OreDigDetourEngine.MEMBER_CAP,
                    "the vein-follow mined more than MEMBER_CAP (" + OreDigDetourEngine.MEMBER_CAP
                            + ") members: " + mined);
            h.require(mined < veinLength,
                    "the whole 15-block vein was consumed: MEMBER_CAP was not enforced");
            h.assertStrict(bot, "ore_dig_opportunistic_vein_end");
            h.pass();
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 4. A valuable with lava directly beside its stand is never approached (design 4.5, 4.6, SafeGate item 7)
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = ENV_PREFIX + "lava_beside_valuable_is_skipped", maxTicks = 900)
    public void lavaBesideValuableIsSkipped(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(40, -3, 9, -4, 4, 4);
        hub(room);
        // dy=1 + a solid roof (not dy=0): see test 1's pose comment -- approachGoalFor's below-stand,
        // never poseFor's same-level cardinal one. The lava stays at the ore's own level (its "up"
        // neighbour, from the east-side below-stand's point of view) so it is still beside the ore.
        BlockPos ore = room.at(6, 1, 0);
        room.set(6, 1, 0, Blocks.IRON_ORE);
        room.world.setBlockState(room.at(6, 2, 0), STONE, Block.NOTIFY_ALL);
        BlockPos lava = room.at(7, 1, 0);
        room.world.setBlockState(lava, Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);

        AIPlayerEntity bot = h.spawn("OreDigDetourLavaBesideGT", room, 0, 0);
        h.enableDetour(bot, 256);
        // A stone pick is mandatory for OreDig's own strip/channel through ordinary rock: the channel-tool
        // policy floors every mined block (including the mission's own coal) at STONE tier and, for non-ore
        // rock, caps it there too (OreDigTask.failMissingMiningChannelTool / ToolSelector.equipMiningChannelTool),
        // so the mission never has to spend its iron pick on plain corridor stone.
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        UUID id = bot.getUuid();
        Progress p = new Progress();
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 40);
        float startHealth = bot.getHealth();

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                h.assertStrict(bot, "ore_dig_opportunistic_lava_beside");
                TaskManager.INSTANCE.assign(bot, task,
                        TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_detour_lava_beside"));
                p.assignedAt = p.tick;
                return;
            }
            requireNotFailed(h, task);
            h.require(bot.isAlive() && bot.getHealth() >= startHealth - 0.01F,
                    "the bot took damage: it must never have approached the lava-adjacent ore");
            h.require(room.world.getBlockState(ore).isOf(Blocks.IRON_ORE),
                    "the lava-adjacent iron was mined: SafeGate/DetourPolicy did not reject it");
            if (p.tick - p.assignedAt > 750) {
                MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
                h.require(state != null && state.sightings().contains(ore),
                        "fixture error: the lava-adjacent ore was never even sighted, so this test proves nothing");
                h.assertStrict(bot, "ore_dig_opportunistic_lava_beside_end");
                h.pass();
            }
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Lava exposed by the break is sealed (with material) or the detour aborts cleanly (design 4.7)
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = ENV_PREFIX + "lava_exposed_by_the_break_is_sealed_or_detour_aborts", maxTicks = 1000)
    public void lavaExposedByTheBreakIsSealedOrDetourAborts(TestContext context) {
        // Sub-case A: no sacrificial material at all -> the seal fails (NO_BLOCK) and the engine aborts
        // fluid_unsealable cleanly (design C.5), never failing the mission (I8).
        Harness h = new Harness(context);
        Room room = h.newRoom(50, -3, 9, -4, 4, 4);
        hub(room);
        // dy=1 + a solid roof (not dy=0): see test 1's pose comment -- approachGoalFor's below-stand,
        // never poseFor's same-level cardinal one. The lava sits on the ore's own level, one cell east
        // of it, but -- unlike test 4's "lava_beside_valuable_is_skipped" -- it must be genuinely HIDDEN
        // until the break exposes it, not already sitting in the open room interior: sealed on every
        // face but the one touching the ore (the x-ray canary pattern of assertFullyEnclosed/tests 6-7).
        // A lava cell open to the room (as this used to be, sharing test 4's exact geometry) is raycast
        // and enters HazardField before the bot ever approaches, so DetourSafetyGate's hazardLavaNear
        // (item 7, an "anyLavaWithin(ore, lavaClearRadius)" check against that memory, never x-ray) makes
        // every approach to this candidate self-abort during APPROACH every time, exactly like test 4 --
        // so the ore is never mined and the postbreak seal/fluid_unsealable path this test exists to
        // prove is never reached (confirmed against the real GameTest log: no ore_dig_detour_skip or
        // _start line ever appears for this candidate within the whole 900-tick budget).
        BlockPos ore = room.at(6, 1, 0);
        room.set(6, 1, 0, Blocks.IRON_ORE);
        room.world.setBlockState(room.at(6, 2, 0), STONE, Block.NOTIFY_ALL);
        BlockPos lava = room.at(7, 1, 0);
        for (Direction d : Direction.values()) {
            if (d != Direction.WEST) {
                room.world.setBlockState(lava.offset(d), STONE, Block.NOTIFY_ALL);
            }
        }
        room.world.setBlockState(lava, Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);

        AIPlayerEntity bot = h.spawn("OreDigDetourLavaAbortGT", room, 0, 0);
        h.enableDetour(bot, 256);
        // No DIRT on purpose (sub-case A: no sacrificial material at all); a stone pick is still mandatory
        // for the mission's own channel/corridor bottleneck (always stone tier, never higher).
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        UUID id = bot.getUuid();
        Progress p = new Progress();
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 40);
        float startHealth = bot.getHealth();
        boolean[] sawBreak = {false};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                h.assertStrict(bot, "ore_dig_opportunistic_lava_abort");
                TaskManager.INSTANCE.assign(bot, task,
                        TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_detour_lava_abort"));
                p.assignedAt = p.tick;
                return;
            }
            requireNotFailed(h, task);
            h.require(bot.isAlive() && bot.getHealth() >= startHealth - 4.0F,
                    "the bot took heavy damage from the exposed lava");
            boolean broken = !room.world.getBlockState(ore).isOf(Blocks.IRON_ORE);
            if (broken) {
                sawBreak[0] = true;
                h.require(room.world.getBlockState(lava).isOf(Blocks.LAVA),
                        "the lava was sealed although the bot carried no sacrificial material");
            }
            if (!sawBreak[0]) {
                h.require(p.tick - p.assignedAt < 900, "the ore beside the hidden lava was never mined");
                return;
            }
            MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
            boolean stillLive = state != null && state.detourOwner() == task;
            if (!stillLive && p.tick - p.assignedAt > 250) {
                requireNotFailed(h, task);
                h.assertStrict(bot, "ore_dig_opportunistic_lava_abort_end");
                h.pass();
            }
        }));
    }

    /** Sub-case B of scenario 5: the bot has a sacrificial block, so the exposed lava is sealed and mining goes on. */
    @GameTest(environment = ENV_PREFIX + "lava_exposed_by_the_break_is_sealed_when_material_available", maxTicks = 1000)
    public void lavaExposedByTheBreakIsSealedWhenMaterialAvailable(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(60, -3, 9, -4, 4, 4);
        hub(room);
        // dy=1 + a solid roof (not dy=0): see test 1's pose comment -- approachGoalFor's below-stand,
        // never poseFor's same-level cardinal one. The lava must be genuinely HIDDEN until the break
        // exposes it (sealed on every face but the one touching the ore): see the matching comment on
        // lavaExposedByTheBreakIsSealedOrDetourAborts (sub-case A) just above for why an open, already
        // observable lava cell here (test 4's geometry) makes hazardLavaNear self-abort every approach
        // before the ore is ever mined, so the postbreak seal path never gets reached.
        BlockPos ore = room.at(6, 1, 0);
        room.set(6, 1, 0, Blocks.IRON_ORE);
        room.world.setBlockState(room.at(6, 2, 0), STONE, Block.NOTIFY_ALL);
        BlockPos lava = room.at(7, 1, 0);
        for (Direction d : Direction.values()) {
            if (d != Direction.WEST) {
                room.world.setBlockState(lava.offset(d), STONE, Block.NOTIFY_ALL);
            }
        }
        room.world.setBlockState(lava, Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);

        AIPlayerEntity bot = h.spawn("OreDigDetourLavaSealGT", room, 0, 0);
        h.enableDetour(bot, 256);
        // A stone pick is mandatory for OreDig's own strip/channel through ordinary rock: the channel-tool
        // policy floors every mined block (including the mission's own coal) at STONE tier and, for non-ore
        // rock, caps it there too (OreDigTask.failMissingMiningChannelTool / ToolSelector.equipMiningChannelTool),
        // so the mission never has to spend its iron pick on plain corridor stone.
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        Progress p = new Progress();
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 40);
        float startHealth = bot.getHealth();
        boolean[] sawBreak = {false};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                h.assertStrict(bot, "ore_dig_opportunistic_lava_seal");
                TaskManager.INSTANCE.assign(bot, task,
                        TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_detour_lava_seal"));
                p.assignedAt = p.tick;
                return;
            }
            requireNotFailed(h, task);
            h.require(bot.isAlive() && bot.getHealth() >= startHealth - 4.0F,
                    "the bot took heavy damage from the exposed lava");
            boolean broken = !room.world.getBlockState(ore).isOf(Blocks.IRON_ORE);
            if (broken) {
                sawBreak[0] = true;
            }
            if (!sawBreak[0]) {
                h.require(p.tick - p.assignedAt < 900, "the ore beside the hidden lava was never mined");
                return;
            }
            if (!room.world.getBlockState(lava).isOf(Blocks.LAVA)) {
                // Sealed: at most SEAL_CAP (3) sacrificial blocks, design C.5/C.3 tickPostbreak.
                requireNotFailed(h, task);
                h.assertStrict(bot, "ore_dig_opportunistic_lava_seal_end");
                h.pass();
                return;
            }
            h.require(p.tick - p.assignedAt < 900, "the exposed lava was never sealed although dirt was available");
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 6. A lava sighting mid-detour self-aborts to RETURN, never a DangerWatcher evade+pause (design C.3, M40)
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = ENV_PREFIX + "lava_sighting_during_detour_returns_instead_of_evade", maxTicks = 1400)
    public void lavaSightingDuringDetourReturnsInsteadOfEvade(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(70, -4, 9, -4, 4, 4);
        hub(room);
        // dy=1 + a solid roof (not dy=0): see test 1's pose comment.
        BlockPos candidate = room.at(8, 1, -3);
        room.set(8, 1, -3, Blocks.DIAMOND_ORE);
        room.world.setBlockState(room.at(8, 2, -3), STONE, Block.NOTIFY_ALL);
        // A lava cell sealed on every side but the one facing the anchor, three blocks (Chebyshev) from spawn:
        // within SafeGate's lavaClearRadius(4) of the bot's own position, but not adjacent (never the immediate,
        // hook-3 "hasImmediateLava" claim path).
        BlockPos lava = room.at(3, 0, 3);
        for (Direction d : Direction.values()) {
            if (d != Direction.WEST) {
                room.world.setBlockState(lava.offset(d), STONE, Block.NOTIFY_ALL);
            }
        }
        room.world.setBlockState(lava, Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos door = lava.offset(Direction.WEST);
        room.world.setBlockState(door, STONE, Block.NOTIFY_ALL);

        AIPlayerEntity bot = h.spawn("OreDigDetourLavaReturnGT", room, 0, 0);
        h.enableDetour(bot, 256);
        // A stone pick is mandatory for OreDig's own strip/channel through ordinary rock: the channel-tool
        // policy floors every mined block (including the mission's own coal) at STONE tier and, for non-ore
        // rock, caps it there too (OreDigTask.failMissingMiningChannelTool / ToolSelector.equipMiningChannelTool),
        // so the mission never has to spend its iron pick on plain corridor stone.
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        UUID id = bot.getUuid();
        Progress p = new Progress();
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 40);
        float startHealth = bot.getHealth();
        boolean[] revealed = {false};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                h.assertStrict(bot, "ore_dig_opportunistic_lava_return");
                TaskManager.INSTANCE.assign(bot, task,
                        TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_detour_lava_return"));
                p.assignedAt = p.tick;
                return;
            }
            // The whole run, throughout: a self-abort to RETURN never shows up as a paused task (M40's own
            // distinction between the engine's own abort and DangerWatcher's generic Evade+pause).
            h.require(task.state() != TaskState.PAUSED,
                    "the mission task was PAUSED: DangerWatcher's generic evade took over instead of a self-abort");
            requireNotFailed(h, task);
            h.require(bot.isAlive() && bot.getHealth() >= startHealth - 0.01F,
                    "the bot took damage: it must never have touched the revealed lava");
            MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
            boolean activeNow = state != null && state.detourOwner() == task;
            if (activeNow && !revealed[0]) {
                room.world.setBlockState(door, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                revealed[0] = true;
                return;
            }
            if (revealed[0] && !activeNow) {
                // The detour ended (published tuple cleared) after the reveal, having never been PAUSED and
                // never having damaged the bot: it self-aborted (design C.3's abort -> beginReturn -> finish),
                // which is the whole point (M40) -- DangerWatcher's generic Evade+pause never got a chance to.
                h.assertStrict(bot, "ore_dig_opportunistic_lava_return_end");
                h.pass();
                return;
            }
            h.require(p.tick - p.assignedAt < 1300, "the detour never reacted to the revealed lava sighting");
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 7. HazardField remembers a lava sighting after a long gap without re-observation (design 3.3, I15)
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = ENV_PREFIX + "hazard_field_remembers_lava_after_long_sweep_gap", maxTicks = 1500)
    public void hazardFieldRemembersLavaAfterLongSweepGap(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(80, -4, 9, -4, 4, 4);
        hub(room);
        // A generous, purely-local coal supply (dy=1 + roof, same as hub()): the mission's own target count
        // (40) deliberately outlives hub's 6 cells everywhere else in this file, but here the test needs the
        // mission to keep ticking (I1/M6's own sensor sweep is a hook of OreDigTask's own tick, design 8.2
        // hook 9/13, so a completed or wandered-off mission stops sensing anything at all) for the whole
        // ~950-tick sweep gap, without OreDig's 48-block spiral ever walking it out of range chasing coal
        // that was never there. Kept well clear of the lava (x=6, sealed) and the late candidate (x=6, z=3).
        for (int dx = -4; dx <= -3; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                room.set(dx, 1, dz, Blocks.COAL_ORE);
                room.set(dx, 2, dz, Blocks.STONE);
            }
        }
        BlockPos lava = room.at(6, 0, 0);
        for (Direction d : Direction.values()) {
            if (d != Direction.WEST) {
                room.world.setBlockState(lava.offset(d), STONE, Block.NOTIFY_ALL);
            }
        }
        room.world.setBlockState(lava, Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos door = lava.offset(Direction.WEST);
        // The door starts open so the initial sighting is real; a later candidate near the same cell, placed
        // well within the SafeGate's lavaClearRadius(4), must stay rejected even after the door is closed and
        // a long gap passes (I15: memory must not silently expire by time).
        BlockPos candidate = room.at(6, 0, 3);

        AIPlayerEntity bot = h.spawn("OreDigDetourHazardGT", room, 0, 0);
        h.enableDetour(bot, 256);
        // A stone pick is mandatory for OreDig's own strip/channel through ordinary rock: the channel-tool
        // policy floors every mined block (including the mission's own coal) at STONE tier and, for non-ore
        // rock, caps it there too (OreDigTask.failMissingMiningChannelTool / ToolSelector.equipMiningChannelTool),
        // so the mission never has to spend its iron pick on plain corridor stone.
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        UUID id = bot.getUuid();
        Progress p = new Progress();
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 40);
        int[] sightedAt = {-1};
        int[] closedAt = {-1};
        int[] candidatePlacedAt = {-1};

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                h.assertStrict(bot, "ore_dig_opportunistic_hazard_memory");
                TaskManager.INSTANCE.assign(bot, task,
                        TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_detour_hazard_memory"));
                p.assignedAt = p.tick;
                return;
            }
            requireNotFailed(h, task);
            MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
            if (sightedAt[0] < 0) {
                if (state != null && state.hazards().anyLavaWithin(lava, 1)) {
                    sightedAt[0] = p.tick;
                }
                h.require(p.tick - p.assignedAt < 400, "the open lava was never sensed at all");
                return;
            }
            if (closedAt[0] < 0) {
                room.world.setBlockState(door, STONE, Block.NOTIFY_ALL);
                closedAt[0] = p.tick;
                return;
            }
            if (candidatePlacedAt[0] < 0) {
                // The long sweep gap: real ticks, real sensor sweeps of everything else in the room, but never
                // another look at the now-sealed lava cell.
                if (p.tick - closedAt[0] < 500) {
                    return;
                }
                room.world.setBlockState(candidate, Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
                candidatePlacedAt[0] = p.tick;
                return;
            }
            h.require(room.world.getBlockState(candidate).isOf(Blocks.IRON_ORE),
                    "the candidate beside the remembered lava was mined: the hazard memory did not hold");
            if (p.tick - candidatePlacedAt[0] > 400) {
                h.require(state != null && state.hazards().anyLavaWithin(lava, 1),
                        "the lava hazard memory expired although Kind.LAVA never ages (design 3.3)");
                MiningAssistState finalState = state;
                h.require(finalState == null || finalState.sightings().contains(candidate),
                        "fixture error: the candidate near the remembered lava was never even sighted");
                h.assertStrict(bot, "ore_dig_opportunistic_hazard_memory_end");
                h.pass();
            }
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 8. A detour that declines or aborts for tool reasons must never fail the mission itself (I8)
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = ENV_PREFIX + "stone_pick_and_raw_gold_block_never_fails_the_mission", maxTicks = 1200)
    public void stonePickAndRawGoldBlockNeverFailsTheMission(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(90, -3, 9, -3, 3, 4);
        // dy=1 + a solid roof (not dy=0): see hub()'s own comment -- a floor-level target's own support
        // cell is never observable, so the mission's own approach would abandon it forever.
        room.set(1, 1, 1, Blocks.COAL_ORE);
        room.set(1, 2, 1, Blocks.STONE);
        room.set(-1, 1, 1, Blocks.COAL_ORE);
        room.set(-1, 2, 1, Blocks.STONE);
        // A stone pick reaches coal (an _ore block, so the channel-tool policy accepts stone-and-up:
        // ToolSelector.equipMiningChannelTool) and is also exactly the mission's own channel/corridor
        // bottleneck (always stone tier, never higher). raw_gold_block needs iron (BlockTags.NEEDS_IRON_TOOL)
        // and, unlike a bare-stone-tier non-ore block, is NOT capped back down to stone by the channel
        // policy (channelMaximumTier only caps a minimumTier of exactly stone), so a stone pick genuinely
        // cannot touch it: any detour attempt on it must decline or abort for tool reasons without touching
        // the mission. (raw_iron_block would not work for this test: it needs only stone too, the exact same
        // tier the mission's own corridor needs, so no tool tier could ever separate "reaches the mission"
        // from "reaches the candidate" the way this test means to.)
        // dy=1 + a solid roof (not dy=0): see test 1's pose comment -- otherwise the detour would skip
        // this candidate with no_pose (a geometry dead end that runs before the tool check this test
        // means to exercise) rather than genuinely reaching and failing the tool check itself.
        BlockPos rawGold = room.at(7, 1, 0);
        room.set(7, 1, 0, Blocks.RAW_GOLD_BLOCK);
        room.set(7, 2, 0, Blocks.STONE);
        room.set(7, 1, 1, Blocks.GOLD_ORE); // natural context for the raw block (design 4.2/M37 naturalContext)
        room.set(7, 2, 1, Blocks.STONE);

        AIPlayerEntity bot = h.spawn("OreDigDetourToolIronGT", room, 0, 0);
        h.enableDetour(bot, 256);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        Progress p = new Progress();
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 2);

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                h.assertStrict(bot, "ore_dig_opportunistic_tool_iron");
                TaskManager.INSTANCE.assign(bot, task,
                        TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_detour_tool_iron"));
                p.assignedAt = p.tick;
                return;
            }
            requireNotFailed(h, task);
            if (task.state() == TaskState.COMPLETED) {
                // A stone pick cannot pass the mining-channel policy for raw_gold_block (needs iron): either
                // the detour never chose this candidate, or it started and aborted "tool"/"tool_wear" before
                // ever swinging (design 4.6 step 7). Either way the raw-gold block itself must be untouched,
                // and the mission (a stone-minable coal target) is what actually finished.
                h.require(room.world.getBlockState(rawGold).isOf(Blocks.RAW_GOLD_BLOCK),
                        "the stone-pick bot mined raw_gold_block, which needs at least an iron pick");
                h.assertStrict(bot, "ore_dig_opportunistic_tool_iron_end");
                h.pass();
                return;
            }
            h.require(p.tick - p.assignedAt < 1100,
                    "the stone-pick coal mission never completed although a nearby gold/raw-gold candidate needed a"
                            + " better tool for the detour (I8: the detour declining must never fail the mission)");
        }));
    }

    @GameTest(environment = ENV_PREFIX + "coal_with_only_a_wooden_pick_never_fails_the_mission", maxTicks = 900)
    public void coalWithOnlyAWoodenPickNeverFailsTheMission(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(100, -3, 9, -3, 3, 4);
        // Mission target is iron_ore (wood cannot mine it in vanilla either, but the pick given below can):
        // a wooden pick is used only near the LOW-VALUE (below detour.minValue=25) coal candidate. dy=1 + a
        // solid roof (not dy=0): see hub()'s own comment -- a floor-level target's own support cell is never
        // observable, so the mission's own approach would abandon it forever.
        room.set(1, 1, 1, Blocks.IRON_ORE);
        room.set(1, 2, 1, Blocks.STONE);
        room.set(-1, 1, 1, Blocks.IRON_ORE);
        room.set(-1, 2, 1, Blocks.STONE);
        BlockPos coalCandidate = room.at(7, 0, 0);
        room.set(7, 0, 0, Blocks.COAL_ORE);

        AIPlayerEntity bot = h.spawn("OreDigDetourToolCoalGT", room, 0, 0);
        h.enableDetour(bot, 256);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE));
        Progress p = new Progress();
        OreDigTask task = new OreDigTask(Set.of(Blocks.IRON_ORE), 2);

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                h.assertStrict(bot, "ore_dig_opportunistic_tool_coal");
                TaskManager.INSTANCE.assign(bot, task,
                        TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_detour_tool_coal"));
                p.assignedAt = p.tick;
                return;
            }
            requireNotFailed(h, task);
            // coal_ore (raw value 12) is always below detour.minValue (25): DetourPolicy.rank drops it before
            // it is ever ranked (design G.1), so it must never be approached at all.
            h.require(room.world.getBlockState(coalCandidate).isOf(Blocks.COAL_ORE),
                    "the below-minValue coal candidate was mined by the detour");
            if (task.state() == TaskState.COMPLETED) {
                h.assertStrict(bot, "ore_dig_opportunistic_tool_coal_end");
                h.pass();
                return;
            }
            h.require(p.tick - p.assignedAt < 800,
                    "the iron mission never completed although a nearby below-minValue coal candidate was"
                            + " present (I8: a non-admissible candidate must never interfere)");
        }));
    }

    @GameTest(environment = ENV_PREFIX + "gilded_blackstone_with_a_non_stone_pick_never_fails_the_mission",
            maxTicks = 900)
    public void gildedBlackstoneWithANonStonePickNeverFailsTheMission(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(110, -3, 9, -3, 3, 4);
        // dy=1 + a solid roof (not dy=0): see hub()'s own comment -- a floor-level target's own support
        // cell is never observable, so the mission's own approach would abandon it forever.
        room.set(1, 1, 1, Blocks.COAL_ORE);
        room.set(1, 2, 1, Blocks.STONE);
        room.set(-1, 1, 1, Blocks.COAL_ORE);
        room.set(-1, 2, 1, Blocks.STONE);
        BlockPos gilded = room.at(7, 0, 0);
        room.set(7, 0, 0, Blocks.GILDED_BLACKSTONE);

        AIPlayerEntity bot = h.spawn("OreDigDetourToolGildedGT", room, 0, 0);
        h.enableDetour(bot, 256);
        // A stone pick is mandatory for the mission's own channel/corridor bottleneck (always stone tier,
        // never higher, see the giveItem comment on the earlier tests in this file); the extra iron pick is
        // the "non_stone_pick" belt-and-braces this test's name refers to -- gilded_blackstone is not an
        // _ore block, so even setting the neverDetour policy aside (design M37), the channel-tool policy
        // caps it at exactly stone tier and neither pick in this inventory could touch it.
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        Progress p = new Progress();
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 2);

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                h.assertStrict(bot, "ore_dig_opportunistic_tool_gilded");
                TaskManager.INSTANCE.assign(bot, task,
                        TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_detour_tool_gilded"));
                p.assignedAt = p.tick;
                return;
            }
            requireNotFailed(h, task);
            // gilded_blackstone is DetourPolicy.neverDetour (design M37, a bastion/build block): it must never
            // be approached at all, tool tier aside.
            h.require(room.world.getBlockState(gilded).isOf(Blocks.GILDED_BLACKSTONE),
                    "gilded_blackstone was mined by the detour although it is DetourPolicy.neverDetour");
            if (task.state() == TaskState.COMPLETED) {
                h.assertStrict(bot, "ore_dig_opportunistic_tool_gilded_end");
                h.pass();
                return;
            }
            h.require(p.tick - p.assignedAt < 800, "the coal mission never completed");
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 20. An unobserved side corridor is never a ranking input, even when it holds a richer ore (I3)
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = ENV_PREFIX + "unobserved_side_corridor_is_never_ranking_input", maxTicks = 1200)
    public void unobservedSideCorridorIsNeverRankingInput(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(120, -6, 6, -4, 4, 4);
        hub(room);
        // The richer, closer candidate is sealed behind one stone layer: never raycast, so its geometry (and its
        // higher value) must never outrank the poorer but OBSERVED candidate on the other side of the room.
        BlockPos richerHidden = room.at(-8, 0, 0);
        room.world.setBlockState(richerHidden, Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
        assertFullyEnclosed(h, room, richerHidden);
        // dy=1 + a solid roof (not dy=0): see test 1's pose comment.
        BlockPos poorerObserved = room.at(6, 1, 0);
        room.world.setBlockState(poorerObserved, Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        room.world.setBlockState(room.at(6, 2, 0), STONE, Block.NOTIFY_ALL);

        AIPlayerEntity bot = h.spawn("OreDigDetourUnobservedGT", room, 0, 0);
        h.enableDetour(bot, 256);
        // A stone pick is mandatory for OreDig's own strip/channel through ordinary rock: the channel-tool
        // policy floors every mined block (including the mission's own coal) at STONE tier and, for non-ore
        // rock, caps it there too (OreDigTask.failMissingMiningChannelTool / ToolSelector.equipMiningChannelTool),
        // so the mission never has to spend its iron pick on plain corridor stone.
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        UUID id = bot.getUuid();
        Progress p = new Progress();
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 40);

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                h.assertStrict(bot, "ore_dig_opportunistic_unobserved");
                TaskManager.INSTANCE.assign(bot, task,
                        TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_detour_unobserved"));
                p.assignedAt = p.tick;
                return;
            }
            requireNotFailed(h, task);
            h.require(room.world.getBlockState(richerHidden).isOf(Blocks.DIAMOND_ORE),
                    "the unobserved, richer diamond was mined: unobserved geometry was used as a ranking input");
            MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
            if (state != null) {
                h.require(!state.sightings().contains(richerHidden),
                        "the unobserved diamond entered the sighting ledger");
            }
            if (!room.world.getBlockState(poorerObserved).isOf(Blocks.IRON_ORE)) {
                h.require(room.world.getBlockState(richerHidden).isOf(Blocks.DIAMOND_ORE),
                        "the observed, poorer iron was mined only after the hidden diamond, or both moved together");
                h.assertStrict(bot, "ore_dig_opportunistic_unobserved_end");
                h.pass();
                return;
            }
            h.require(p.tick - p.assignedAt < 1100,
                    "the observed, poorer iron was never detoured to");
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // 21. A task with origin VERIFY never gets a detour, even forced and given a visible valuable (I4, 2.1)
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = ENV_PREFIX + "verify_origin_never_detours", maxTicks = 500)
    public void verifyOriginNeverDetours(TestContext context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(130, -4, 4, -3, 3, 4);
        BlockPos candidate = room.at(3, 0, 0);
        room.set(3, 0, 0, Blocks.DIAMOND_ORE);
        room.set(1, 0, 2, Blocks.COAL_ORE);

        AIPlayerEntity bot = h.spawn("OreDigDetourVerifyOriginGT", room, 0, 0);
        h.enableDetour(bot, 256);
        // A stone pick is mandatory for OreDig's own strip/channel through ordinary rock: the channel-tool
        // policy floors every mined block (including the mission's own coal) at STONE tier and, for non-ore
        // rock, caps it there too (OreDigTask.failMissingMiningChannelTool / ToolSelector.equipMiningChannelTool),
        // so the mission never has to spend its iron pick on plain corridor stone.
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        UUID id = bot.getUuid();
        Progress p = new Progress();
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1);

        context.runAtEveryTick(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                h.assertStrict(bot, "ore_dig_opportunistic_verify_origin");
                TaskManager.INSTANCE.assign(bot, task,
                        TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_detour_verify_origin"));
                p.assignedAt = p.tick;
                return;
            }
            h.require(room.world.getBlockState(candidate).isOf(Blocks.DIAMOND_ORE),
                    "a VERIFY-origin task's diamond was mined by a detour: the origin gate (I4) did not hold");
            MiningAssistState state = MiningAssistRegistry.getIfPresent(id);
            h.require(state == null || state.detourOwner() == null,
                    "a VERIFY-origin task published a live detour tuple");
            if (p.tick - p.assignedAt >= 300) {
                h.require(AssistGate.DENY_ORIGIN.equals(MiningAssistRuntime.lastDenyReason(id))
                                || state == null,
                        "expected the origin gate to be the deny reason for a forced, VERIFY-origin bot, saw "
                                + MiningAssistRuntime.lastDenyReason(id));
                h.assertStrict(bot, "ore_dig_opportunistic_verify_origin_end");
                h.pass();
            }
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // Shared fixture plumbing
    // ---------------------------------------------------------------------------------------------

    /** A small cluster of the mission's own target ore near spawn: keeps the mission genuinely busy and
     * healthy (real {@code noteProgress()} calls) for the whole run, independent of whatever the detour does. */
    private static void hub(Room room) {
        // dy=1 + a solid roof at dy=2 (not dy=0): a floor-level target's own support cell (ore.down(), the
        // room's solid floor slab) is never observable (buried, no exposed face -- the ore itself occludes
        // the only face that would otherwise face the room), so OreDigTask.needsTargetDropSupport /
        // hasReliableObservedDropCatch permanently abandons the approach (design's own drop-catch honesty
        // rule, working as intended against an artificial fixture, not a bug). One level up, the bot
        // approaches from the room's ordinary floor one level below, where vertical=1 never satisfies
        // needsTargetDropSupport's own (vertical==0 || vertical==-1) test in the first place.
        int[][] offsets = {{1, 1, 1}, {1, 1, -1}, {-1, 1, 1}, {-1, 1, -1}, {2, 1, 2}, {2, 1, -2}};
        for (int[] o : offsets) {
            room.set(o[0], o[1], o[2], Blocks.COAL_ORE);
            room.set(o[0], o[1] + 1, o[2], Blocks.STONE);
        }
    }

    private static void requireNotFailed(Harness h, OreDigTask task) {
        h.require(task.state() != TaskState.FAILED && task.state() != TaskState.CANCELLED,
                "mission ended as " + task.state() + ":" + task.failureReason());
    }

    /** Counts how many of {@code cells} are no longer their original ore (i.e. were mined). */
    private static int minedCount(ServerWorld world, List<BlockPos> cells) {
        int mined = 0;
        for (BlockPos pos : cells) {
            if (!world.getBlockState(pos).isOf(Blocks.IRON_ORE)) {
                mined++;
            }
        }
        return mined;
    }

    /** Every neighbour of {@code hidden} must be solid: the x-ray canary self-check (P0's own idiom). */
    private static void assertFullyEnclosed(Harness h, Room room, BlockPos hidden) {
        for (Direction direction : Direction.values()) {
            BlockState neighbour = room.world.getBlockState(hidden.offset(direction));
            h.require(!neighbour.isAir(),
                    "fixture error: hidden ore at " + hidden.toShortString() + " touches air on its "
                            + direction + " face");
        }
    }

    private static MiningAssistConfig detourConfig(int raysPerTick, JsonObject detourOverrides) {
        JsonObject sense = new JsonObject();
        sense.addProperty("raysPerTick", raysPerTick);
        JsonObject section = new JsonObject();
        section.add("sense", sense);
        if (detourOverrides != null) {
            section.add("detour", detourOverrides);
        }
        JsonObject root = new JsonObject();
        root.add(MiningAssistConfig.FILE_SECTION, section);
        // DETOUR mode, harnessOff=true (the shipped harness default, design M4/M38): every test opts a single
        // bot past it with forceEnable, which never bypasses the origin/audit/TPS gates.
        return MiningAssistConfig.parse(root, key -> null, AssistMode.DETOUR, true);
    }

    /** Parses the "x,y,z" form {@code MiningCursor.encode()} writes for {@code checkpoint().get("face")}. */
    private static BlockPos parseFace(String encoded) {
        String[] parts = encoded.split(",");
        return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    /** Per-test tick bookkeeping (mirrors {@code MiningAssistSenseGameTests.Progress}). */
    private static final class Progress {
        int tick;
        int assignedAt = -1;
    }

    /** Captures the checkpoint's anchor fields the first tick a detour is seen live, then pins them (I6). */
    private static final class AnchorRef {
        BlockPos face;
        String direction;
        String leg;
        String stepsLeft;
        String legLength;

        void capture(Map<String, String> checkpoint) {
            face = parseFace(checkpoint.get("face"));
            direction = checkpoint.get("direction");
            leg = checkpoint.get("leg");
            stepsLeft = checkpoint.get("steps_left");
            legLength = checkpoint.get("leg_length");
        }

        void requireUnchanged(Harness h, Map<String, String> live, int tick) {
            h.require(face != null && face.equals(parseFace(live.get("face"))),
                    "anchor face drifted during the detour at tick " + tick);
            h.require(Objects.equals(direction, live.get("direction")),
                    "anchor direction drifted during the detour at tick " + tick);
            h.require(Objects.equals(leg, live.get("leg")),
                    "anchor leg drifted during the detour at tick " + tick);
            h.require(Objects.equals(stepsLeft, live.get("steps_left")),
                    "anchor steps_left drifted during the detour at tick " + tick);
            h.require(Objects.equals(legLength, live.get("leg_length")),
                    "anchor leg_length drifted during the detour at tick " + tick);
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

        /** Installs a DETOUR-mode config (design G.6, harnessOff=true) and force-enables one bot past it. */
        void enableDetour(AIPlayerEntity bot, int raysPerTick, JsonObject detourOverrides) {
            if (restoreConfig == null) {
                restoreConfig = MiningAssistRuntime.config();
            }
            MiningAssistRuntime.install(detourConfig(raysPerTick, detourOverrides));
            MiningAssistRuntime.setTestTpsDegraded(Boolean.FALSE);
            tpsOverridden = true;
            MiningAssistRuntime.forceEnable(bot.getUuid());
            forced.add(bot.getUuid());
        }

        void enableDetour(AIPlayerEntity bot, int raysPerTick) {
            enableDetour(bot, raysPerTick, null);
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
            require(MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                    "GameTest must run under strict_survival, got " + MinecraftAiConfig.get().profile());
            for (PrivilegedCapability capability : PrivilegedCapability.values()) {
                require(!CapabilityRuntime.decide(bot, capability, label).allowed(),
                        "strict_survival unexpectedly allowed " + capability);
            }
        }

        /**
         * Reads the bot's own structured log (the record a reviewer would read) and requires that no
         * {@code capability_decision} line was ever ALLOWED (the x-ray canary's belt-and-braces check, design I2,
         * {@code MiningAssistSenseGameTests.assertNoAllowedCapabilityDecision}'s idiom).
         */
        void assertNoAllowedCapabilityDecision(AIPlayerEntity bot) {
            List<String> lines = botLog(bot.getGameProfile().name());
            if (lines == null || !hasSpawnLine(lines)) {
                // No in-memory equivalent exists (CapabilityTally only counts allowed FORCED_PICKUP and denied
                // decisions; an open MiningEvidenceAudit session closes the assist gate), so say so in the log
                // instead of passing without a trace. Deliberately not a failure.
                LOG.info("[capability-canary skipped: no per-bot log] {}", bot.getGameProfile().name());
                return;
            }
            long allowed = lines.stream()
                    .filter(l -> l.contains("event=capability_decision") && l.contains("allowed='true'"))
                    .count();
            require(allowed == 0,
                    "a privileged capability was ALLOWED for " + bot.getGameProfile().name() + " during the run");
        }
    }
}

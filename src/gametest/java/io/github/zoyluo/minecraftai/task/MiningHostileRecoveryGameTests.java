package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.TeleportAudit;
import io.github.zoyluo.minecraftai.gametest.BotFixtureMoves;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.MiningCursor;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Live regressions for preserving a mining mission across a hostile cave opening. */
public final class MiningHostileRecoveryGameTests {
    @GameTest(maxTicks = 40)
    public void blindStripRotatesAtUnsupportedCaveLipBeforeFalling(GameTestHelper context) {
        TunnelFixture fixture = quietTunnel(context, "MiningOpenDropGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos opening = fixture.workFace().east();
        context.getLevel().setBlock(opening, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(opening.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(opening.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(opening.below(2),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        // South is the already-controlled tunnel. Give the interrupted east leg one factual new
        // north head obstruction above a supported open foot cell. This test owns open-drop
        // rotation; two-solid-cell staged excavation is covered by OreDigPickupGameTests.
        context.getLevel().setBlock(
                fixture.workFace().north(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(
                fixture.workFace().north().above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(
                fixture.workFace().north().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        MiningCursor cursor = new MiningCursor(
                MiningCursor.CURRENT_SCHEMA,
                fixture.workFace(),
                fixture.workFace(),
                1,
                1,
                12,
                48,
                0);
        Map<String, String> checkpoint = new OreDigCheckpoint(
                4, 1, true, 0, 0, false, 40, 0, 0, cursor,
                OreDigTask.oreFingerprint(Set.of(Blocks.IRON_ORE)),
                0, 0, null, null, null, null, -1, -1, -1, null, -1).encode();
        OreDigTask mining = new OreDigTask(Set.of(Blocks.IRON_ORE), 1, checkpoint);
        TaskManager.INSTANCE.assign(bot, mining,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_open_drop_reroute"));

        mining.tick(bot);

        require(context, mining.state() == TaskState.RUNNING,
                "unsupported cave lip terminated mining: " + mining.failureReason());
        require(context, bot.blockPosition().equals(fixture.workFace()),
                "blind strip stepped or fell into the cave: " + bot.blockPosition().toShortString());
        require(context, bot.getActionPack().isPathExecutorIdle()
                        && bot.getActionPack().isWalkToIdle(),
                "blind strip left movement active toward the unsupported opening");
        Map<String, String> rerouted = mining.checkpoint();
        require(context, "12".equals(rerouted.get("steps_left"))
                        && "0".equals(rerouted.get("direction")),
                "unsupported branch did not preserve its remaining north fresh-work detour: "
                        + rerouted);
        require(context, encode(fixture.workFace()).equals(rerouted.get("face")),
                "reroute changed the factual work face: " + rerouted.get("face"));
        finish(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:mining_hostile_recovery_game_tests_unmarked_straight_leg_rejects_wrong_side_and_owns_its_front_barricade", maxTicks = 40)
    public void unmarkedStraightLegRejectsWrongSideAndOwnsItsFrontBarricade(
            GameTestHelper context) {
        TunnelFixture fixture = quietTunnel(context, "MiningUnmarkedBarricadeGT");
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 2));
        assertStrictCapabilities(context, bot);

        MiningCursor cursor = new MiningCursor(
                MiningCursor.CURRENT_SCHEMA,
                fixture.workFace(), fixture.workFace(), 0, 0, 12, 48, 0);
        Map<String, String> checkpoint = new OreDigCheckpoint(
                4, 1, true, 0, 0, false, 40, 0, 0, cursor,
                OreDigTask.oreFingerprint(Set.of(Blocks.IRON_ORE)),
                0, 0, null, null, null, null,
                -1, -1, -1, null, -1).encode();
        OreDigTask mining = new OreDigTask(Set.of(Blocks.IRON_ORE), 1, checkpoint);
        mining.start(bot);

        Map<String, String> beforeWrongSide = mining.checkpoint();
        require(context, mining.prepareHostileBarricade(
                        bot, fixture.workFace().south(2)).isEmpty()
                        && beforeWrongSide.equals(mining.checkpoint()),
                "rear-tunnel hostile moved or closed the unmarked cursor");
        require(context, mining.prepareHostileBarricade(
                        bot, fixture.workFace().east(2)).isEmpty()
                        && beforeWrongSide.equals(mining.checkpoint()),
                "side hostile moved or closed the unmarked cursor");

        Optional<MiningBarricadeTask> plan = mining.prepareHostileBarricade(
                bot, fixture.workFace().north(3));
        Map<String, String> closed = mining.checkpoint();
        require(context, plan.isPresent()
                        && plan.get().describe().contains(
                        "retreat=" + encode(fixture.retreatFeet()))
                        && plan.get().describe().contains("gate=" + encode(fixture.gateFeet()))
                        && "0".equals(closed.get("steps_left"))
                        && encode(fixture.retreatFeet()).equals(closed.get("face"))
                        && OreDigTask.inspectCheckpoint(closed).isPresent(),
                "ordinary unmarked leg lost its straight factual rear: " + closed);
        mining.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:mining_hostile_recovery_game_tests_marker_only_reroute_rejects_standable_geometric_reverse_without_mutation", maxTicks = 40)
    public void markerOnlyRerouteRejectsStandableGeometricReverseWithoutMutation(
            GameTestHelper context) {
        TunnelFixture fixture = quietTunnel(context, "MiningMarkerOnlyBarricadeGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos face = fixture.workFace();
        // Build a tempting one-wide west corridor. It is observable and standable but was never
        // crossed by this east-facing marker-only reroute, so geometry alone cannot confer ownership.
        for (int distance = 1; distance <= 4; distance++) {
            BlockPos center = face.west(distance);
            context.getLevel().setBlock(
                    center.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(
                    center, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(
                    center.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(
                    center.above(2), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            for (int dy = 0; dy <= 1; dy++) {
                context.getLevel().setBlock(
                        center.north().above(dy), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                context.getLevel().setBlock(
                        center.south().above(dy), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 2));
        assertStrictCapabilities(context, bot);

        MiningCursor cursor = new MiningCursor(
                MiningCursor.CURRENT_SCHEMA, face, face, 1, 1, 48, 48, 0);
        Map<String, String> checkpoint = new OreDigCheckpoint(
                4, 1, true, 0, 0, false, 40, 0, 0, cursor,
                OreDigTask.oreFingerprint(Set.of(Blocks.IRON_ORE)),
                7, 7, null, face, null, null,
                -1, -1, -1, null, -1).encode();
        OreDigTask mining = new OreDigTask(Set.of(Blocks.IRON_ORE), 1, checkpoint);
        mining.start(bot);
        Map<String, String> before = mining.checkpoint();

        Optional<MiningBarricadeTask> plan = mining.prepareHostileBarricade(
                bot, face.east(3));
        Map<String, String> after = mining.checkpoint();
        require(context, plan.isEmpty() && mining.state() == TaskState.RUNNING
                        && before.equals(after)
                        && bot.getActionPack().isPathExecutorIdle()
                        && bot.getActionPack().isWalkToIdle()
                        && bot.getActionPack().isMiningIdle(),
                "marker-only reroute stole an unowned west corridor: before="
                        + before + " after=" + after);
        mining.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    // This long live-entity sequence builds beyond EMPTY_STRUCTURE's tiny template.
    // Keep it out of the short sibling batch so a neighbouring context cannot complete
    // and clear one of these deliberately retained hostiles before the final assertion.
    @GameTest(environment = "minecraftai-gametest:mining_hostile_recovery_game_tests_ore_dig_retreats_and_permanently_barricades_four_hostiles", maxTicks = 500)
    public void oreDigRetreatsAndPermanentlyBarricadesFourHostiles(GameTestHelper context) {
        TunnelFixture fixture = hostileTunnel(context, "MiningBarricadeGT");
        AIPlayerEntity bot = fixture.bot();
        assertStrictCapabilities(context, bot);
        BlockPos finalNorthRear = fixture.workFace().south();
        // A fixture move (recorded as TEST, never as a correction): the miner starts in the factual old north leg.
        BotFixtureMoves.place(bot, finalNorthRear);
        TeleportAudit.reset(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 3));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));

        MiningCursor cursor = new MiningCursor(
                MiningCursor.CURRENT_SCHEMA,
                finalNorthRear,
                finalNorthRear,
                0,
                0,
                1,
                48,
                0);
        Map<String, String> checkpoint = new OreDigCheckpoint(
                4, 3, true, 0, 0, false, 40, 0, 0, cursor,
                OreDigTask.oreFingerprint(Set.of(Blocks.IRON_ORE)),
                0, 0, null, null, null, null,
                -1, -1, -1, null, -1).encode();
        OreDigTask initial = new OreDigTask(Set.of(Blocks.IRON_ORE), 3, checkpoint);
        initial.start(bot);

        // Reproduce the real corner transaction instead of forging a marker: finish the last north
        // step, then let OreDig atomically publish its east successor. The factual old corridor is
        // south, while east's geometric reverse west has never been traversed.
        initial.tick(bot);
        bot.getActionPack().stopAll();
        BotFixtureMoves.place(bot, fixture.workFace()); // the final factual north step, as a fixture move
        initial.tick(bot);
        Map<String, String> markedSuccessor = initial.checkpoint();
        require(context, "1".equals(markedSuccessor.get("direction"))
                        && "1".equals(markedSuccessor.get("leg"))
                        && "48".equals(markedSuccessor.get("steps_left"))
                        && encode(fixture.workFace()).equals(markedSuccessor.get("face"))
                        && encode(fixture.workFace()).equals(
                        markedSuccessor.get("boundary_reroute_origin"))
                        && encode(finalNorthRear).equals(
                        markedSuccessor.get("controlled_strip_rear"))
                        && "2".equals(markedSuccessor.get("budget_used"))
                        && "2".equals(markedSuccessor.get("last_progress_budget"))
                        && OreDigTask.inspectCheckpoint(markedSuccessor).isPresent(),
                "factual corner did not publish its owned south rear: " + markedSuccessor);
        Map<String, String> forgedGeometricReverse = new java.util.LinkedHashMap<>(markedSuccessor);
        forgedGeometricReverse.put(
                "controlled_strip_rear", encode(fixture.workFace().west()));
        require(context, OreDigTask.inspectCheckpoint(forgedGeometricReverse).isEmpty(),
                "checkpoint codec accepted east's untraversed west column as factual rear");
        Map<String, String> forgedPartialCorner = new java.util.LinkedHashMap<>(markedSuccessor);
        forgedPartialCorner.put("steps_left", "47");
        require(context, OreDigTask.inspectCheckpoint(forgedPartialCorner).isEmpty(),
                "checkpoint codec accepted a partial marker as a factual normal corner");

        initial.cancel(bot, "gametest_marked_successor_restart");
        OreDigTask mining = new OreDigTask(Set.of(Blocks.IRON_ORE), 3, markedSuccessor);
        TaskManager.INSTANCE.assign(bot, mining,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_mining_hostile_recovery"));
        require(context, encode(finalNorthRear).equals(
                        mining.checkpoint().get("controlled_strip_rear"))
                        && encode(fixture.workFace()).equals(
                        mining.checkpoint().get("boundary_reroute_origin")),
                "marked successor restart lost its factual old rear: " + mining.checkpoint());
        Map<String, String> beforeSideHostile = mining.checkpoint();
        require(context, mining.prepareHostileBarricade(
                        bot, fixture.workFace().east(3)).isEmpty()
                        && beforeSideHostile.equals(mining.checkpoint()),
                "marked successor retreated for a hostile beside its north-facing gate");

        require(context, EmergencyShelterTask.hasShelterBlock(bot),
                "hostile mining fixture did not expose its dirt reserve to the safety palette");

        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof MiningBarricadeTask,
                "hostile strip mine chose " + (active == null ? "idle" : active.name())
                        + " instead of a factual rear barricade");
        require(context, mining.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mining
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "barricade did not preserve the exact OreDig instance");
        Map<String, String> interrupted = mining.checkpoint();
        require(context, "0".equals(interrupted.get("steps_left")),
                "hostile-facing branch leg remained open in the checkpoint");
        require(context, interrupted.get("face").equals(encode(fixture.retreatFeet())),
                "checkpoint face did not move to the verified rear: " + interrupted.get("face"));
        require(context, !interrupted.containsKey("boundary_reroute_origin")
                        && OreDigTask.inspectCheckpoint(interrupted).isPresent(),
                "hostile close retained an old marker or published an undecodable checkpoint: "
                        + interrupted);
        require(context, !interrupted.containsKey("controlled_strip_rear"),
                "hostile close retained a consumed factual rear: " + interrupted);
        require(context, "2".equals(interrupted.get("budget_used"))
                        && "2".equals(interrupted.get("last_progress_budget")),
                "safety routing changed the persisted mining budget");

        AtomicInteger ticks = new AtomicInteger();
        context.failIfEver(() -> {
            require(context, TeleportAudit.corrections(bot) == 0,
                    "the barricade retreat or the resumed mining teleported the bot (corrections="
                            + TeleportAudit.corrections(bot) + " last=" + TeleportAudit.lastCaller(bot) + ")");
            DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
            if (mining.state() == TaskState.FAILED || mining.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("mining cursor ended during hostile recovery: "
                        + mining.state() + ":" + mining.failureReason()));
            }
            if (!isSealed(context, fixture.gateFeet())
                    || !isSealed(context, fixture.gateFeet().above())
                    || mining.state() != TaskState.RUNNING
                    || TaskManager.INSTANCE.pausedDepth(bot) != 0
                    || TaskManager.INSTANCE.getActive(bot).orElse(null) != mining) {
                if (ticks.incrementAndGet() > 420) {
                    context.fail(Component.nullToEmpty("barricade did not seal and resume: active="
                            + TaskManager.INSTANCE.status(bot)));
                }
                return;
            }
            require(context, bot.blockPosition().equals(fixture.retreatFeet()),
                    "miner did not resume from the verified rear: " + bot.blockPosition().toShortString());
            require(context, context.getLevel().getBlockState(fixture.retreatFeet()).isAir()
                            && context.getLevel().getBlockState(fixture.retreatFeet().above()).isAir(),
                    "barricade closed the known rear corridor");
            require(context, fixture.hostiles().stream().allMatch(entity -> entity.isAlive()),
                    "recovery silently converted into cave combat");
            Map<String, String> resumed = mining.checkpoint();
            require(context, "0".equals(resumed.get("steps_left")),
                    "OreDig reopened the hostile-facing leg before rotating it");
            require(context, "2".equals(resumed.get("budget_used")),
                    "paused safety interval consumed OreDig's durable budget");

            // Simulate a process boundary from the checkpoint captured immediately after hostile
            // admission. At the now-sealed factual retreat it must decode, keep the hard budget and
            // advance exactly one square-spiral successor away from the gate.
            TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_hostile_process_restart");
            require(context, TaskManager.INSTANCE.getActive(bot).isEmpty()
                            && !TaskManager.INSTANCE.hasPaused(bot)
                            && TaskManager.INSTANCE.peekFailure(bot).isEmpty(),
                    "process boundary retained a task owner or synthetic failure");
            OreDigTask restarted = new OreDigTask(Set.of(Blocks.IRON_ORE), 3, interrupted);
            restarted.start(bot);
            require(context, restarted.state() == TaskState.RUNNING
                            && "0".equals(restarted.checkpoint().get("steps_left"))
                            && encode(fixture.retreatFeet()).equals(
                            restarted.checkpoint().get("face"))
                            && "2".equals(restarted.checkpoint().get("budget_used")),
                    "process restart rejected the closed hostile checkpoint: "
                            + restarted.checkpoint());
            restarted.tick(bot);
            Map<String, String> successor = restarted.checkpoint();
            require(context, restarted.state() == TaskState.RUNNING
                            && "2".equals(successor.get("direction"))
                            && "2".equals(successor.get("leg"))
                            && "96".equals(successor.get("steps_left"))
                            && "96".equals(successor.get("leg_length"))
                            && encode(fixture.retreatFeet()).equals(successor.get("face"))
                            && !successor.containsKey("boundary_reroute_origin")
                            && !successor.containsKey("controlled_strip_rear")
                            && "3".equals(successor.get("budget_used"))
                            && "2".equals(successor.get("last_progress_budget"))
                            && bot.blockPosition().equals(fixture.retreatFeet())
                            && bot.getActionPack().isPathExecutorIdle()
                            && bot.getActionPack().isWalkToIdle()
                            && bot.getActionPack().isMiningIdle()
                            && OreDigTask.inspectCheckpoint(successor).isPresent(),
                    "restart did not advance exactly one safe post-barricade successor: "
                            + successor);
            restarted.cancel(bot, "gametest_complete");
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_hostile_recovery_game_tests_one_block_cannot_commit_a_two_cell_mining_barricade", maxTicks = 40)
    public void oneBlockCannotCommitATwoCellMiningBarricade(GameTestHelper context) {
        TunnelFixture fixture = hostileTunnel(context, "MiningBarricadeOneBlockGT");
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 3));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT));

        MiningCursor cursor = new MiningCursor(
                MiningCursor.CURRENT_SCHEMA,
                fixture.workFace(),
                fixture.workFace(),
                0,
                0,
                12,
                48,
                0);
        Map<String, String> checkpoint = new OreDigCheckpoint(
                4, 3, true, 0, 0, false, 40, 0, 0, cursor,
                OreDigTask.oreFingerprint(Set.of(Blocks.IRON_ORE)),
                40, 40, null, null, null, null, -1, -1, -1, null, -1).encode();
        OreDigTask mining = new OreDigTask(Set.of(Blocks.IRON_ORE), 3, checkpoint);
        TaskManager.INSTANCE.assign(bot, mining,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_mining_barricade_one_block"));
        Map<String, String> before = mining.checkpoint();

        require(context, EmergencyShelterTask.hasShelterBlock(bot)
                        && !MiningBarricadeTask.hasMaterialsForOpenGate(bot),
                "one-block fixture did not exercise the shelter/barricade admission boundary");
        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        Map<String, String> after = mining.checkpoint();
        require(context, !(active instanceof MiningBarricadeTask),
                "one block dispatched a two-cell mining barricade");
        require(context, mining.state() != TaskState.FAILED
                        && mining.state() != TaskState.CANCELLED,
                "insufficient barricade material destroyed the mining mission");
        for (String key : List.of(
                "face", "direction", "leg", "steps_left", "leg_length",
                "budget_used", "last_progress_budget")) {
            require(context, java.util.Objects.equals(before.get(key), after.get(key)),
                    "failed barricade admission mutated " + key
                            + ": before=" + before + " after=" + after);
        }
        require(context, InventoryAction.countItem(bot, Items.DIRT) == 1,
                "failed barricade admission consumed its only shelter block");
        finish(context, fixture);
    }

    @GameTest(maxTicks = 40)
    public void survivalGuardPausesMissionInstanceInsteadOfFailingIt(GameTestHelper context) {
        TunnelFixture fixture = quietTunnel(context, "GuardPauseGT");
        AIPlayerEntity bot = fixture.bot();
        HoldingTask mission = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, mission,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_guard_pause"));
        bot.setHealth(5.0F);
        bot.hurtTime = 5;

        TaskManager.INSTANCE.tickAll(context.getLevel().getServer());

        require(context, mission.state() == TaskState.PAUSED,
                "survival guard destroyed mission state: "
                        + mission.state() + ":" + mission.failureReason());
        require(context, TaskManager.INSTANCE.getActive(bot).isEmpty()
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission,
                "survival guard did not retain the same mission object on the pause stack");
        require(context, TaskManager.INSTANCE.peekFailure(bot).isEmpty(),
                "temporary guard pause published a terminal task failure");
        finish(context, fixture);
    }

    @GameTest(maxTicks = 40)
    public void pausedWorkOnlyResumesAfterThreatAndDamageClear(GameTestHelper context) {
        TunnelFixture fixture = quietTunnel(context, "ResumeGateGT");
        AIPlayerEntity bot = fixture.bot();
        Zombie zombie = spawnZombie(context, fixture.workFace().north(2));
        fixture.hostiles().add(zombie);

        bot.setHealth(bot.getMaxHealth());
        bot.hurtTime = 0;
        Threat hostile = new Threat(Threat.Type.HOSTILE, Threat.Severity.MEDIUM,
                zombie, zombie.blockPosition());
        require(context, !DangerWatcher.canResumePausedWork(bot, Optional.of(hostile)),
                "visible hostile authorized mission resume");

        bot.setHealth(2.0F);
        require(context, !DangerWatcher.canResumePausedWork(bot, Optional.empty()),
                "critical health authorized mission resume without recovery");

        bot.setHealth(bot.getMaxHealth());
        bot.hurtTime = 3;
        require(context, !DangerWatcher.canResumePausedWork(bot, Optional.empty()),
                "active damage window authorized mission resume");

        bot.hurtTime = 0;
        require(context, DangerWatcher.canResumePausedWork(bot, Optional.empty()),
                "safe recovered pose remained permanently paused");
        finish(context, fixture);
    }

    private static TunnelFixture hostileTunnel(GameTestHelper context, String name) {
        TunnelFixture fixture = quietTunnel(context, name);
        BlockPos face = fixture.workFace();
        // Open a chamber in front while preserving four cells of factual one-wide rear tunnel.
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -7; dz <= -1; dz++) {
                context.getLevel().setBlock(face.offset(dx, -1, dz),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 4; dy++) {
                    context.getLevel().setBlock(face.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        for (BlockPos pos : List.of(
                face.north(3), face.north(4).east(),
                face.north(4).west(), face.north(5))) {
            fixture.hostiles().add(spawnZombie(context, pos));
        }
        return fixture;
    }

    private static TunnelFixture quietTunnel(GameTestHelper context, String name) {
        var world = context.getLevel();
        world.setDayTime(1000L);
        BlockPos face = context.absolutePos(new BlockPos(12, 5, 12));
        for (int dz = 0; dz <= 8; dz++) {
            BlockPos center = face.south(dz);
            world.setBlock(center.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(center, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(center.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(center.above(2), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            for (int dy = 0; dy <= 1; dy++) {
                world.setBlock(center.east().above(dy),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(center.west().above(dy),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        // GameTest structures are placed in a void column. Add a broad factual ceiling so the
        // production underground predicate is exercised instead of relying on a one-column
        // heightmap update at the spawn tick.
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -8; dz <= 10; dz++) {
                world.setBlock(face.offset(dx, 6, dz),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(face),
                        180.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, face.getX() + 0.5D, face.getY(), face.getZ() + 0.5D,
                Set.of(), 180.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.getFoodData().setSaturation(5.0F);
        BlockPos retreat = face.south(4);
        return new TunnelFixture(name, bot, face.immutable(), retreat.immutable(),
                retreat.north().immutable(), new ArrayList<>());
    }

    private static Zombie spawnZombie(GameTestHelper context, BlockPos feet) {
        Zombie zombie = EntityType.ZOMBIE.create(context.getLevel(), EntitySpawnReason.COMMAND);
        if (zombie == null) {
            throw new IllegalStateException("failed to create zombie fixture");
        }
        zombie.setPersistenceRequired();
        zombie.snapTo(feet.getX() + 0.5D, feet.getY(),
                feet.getZ() + 0.5D, 0.0F, 0.0F);
        context.getLevel().addFreshEntity(zombie);
        return zombie;
    }

    private static boolean isSealed(GameTestHelper context, BlockPos pos) {
        var state = context.getLevel().getBlockState(pos);
        return !state.canBeReplaced()
                && !state.getCollisionShape(context.getLevel(), pos).isEmpty();
    }

    private static void assertStrictCapabilities(GameTestHelper context, AIPlayerEntity bot) {
        require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + MinecraftAiConfig.get().profile());
        for (PrivilegedCapability capability : PrivilegedCapability.values()) {
            require(context, !CapabilityRuntime.decide(
                            bot, capability, "mining_hostile_recovery_gametest").allowed(),
                    "strict_survival unexpectedly allowed " + capability);
        }
    }

    private static void finish(GameTestHelper context, TunnelFixture fixture) {
        for (Zombie hostile : fixture.hostiles()) {
            hostile.discard();
        }
        DangerWatcher.INSTANCE.clear(fixture.bot());
        TaskManager.INSTANCE.cancelIntentTasks(fixture.bot(), "gametest_complete");
        AIPlayerManager.INSTANCE.despawn(fixture.bot().level().getServer(), fixture.name());
        context.succeed();
    }

    private static String encode(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }

    private record TunnelFixture(String name,
                                 AIPlayerEntity bot,
                                 BlockPos workFace,
                                 BlockPos retreatFeet,
                                 BlockPos gateFeet,
                                 List<Zombie> hostiles) {
    }

    private static final class HoldingTask extends AbstractTask {
        @Override
        public String name() {
            return "guard_pause_holding";
        }

        @Override
        public String describe() {
            return "Holding a mission cursor across survival guard";
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

package io.github.zoyluo.minecraftai.task;

import com.mojang.logging.LogUtils;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.MaterialPalette;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.TeleportAudit;
import io.github.zoyluo.minecraftai.gametest.BotFixtureMoves;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.MiningBudget;
import io.github.zoyluo.minecraftai.mining.MiningCursor;
import io.github.zoyluo.minecraftai.mining.OreScan;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.stats.Stats;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;

/** Live strict-survival regression coverage for OreDig's physical target-drop ledger. */
public final class OreDigPickupGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_adjacent_coal_over_five_deep_shaft_is_caught_before_deep_fall", maxTicks = 500)
    public void adjacentCoalOverFiveDeepShaftIsCaughtBeforeDeepFall(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreDropCatchGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos ore = start.north();
        BlockPos support = ore.below();
        var world = bot.level();

        world.setBlock(ore, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
        for (int depth = 1; depth <= 5; depth++) {
            world.setBlock(ore.below(depth), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        world.setBlock(ore.below(6), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT));
        require(context, !ObservableWorldQuery.canObserveCell(bot, support),
                "fixture did not preserve the ore-occluded support-center ray");

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        int pickupBaseline = bot.getStats().getValue(
                Stats.ITEM_PICKED_UP.get(Items.COAL));
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_drop_catch"));
        AtomicBoolean sawCatchSupport = new AtomicBoolean();
        AtomicBoolean sawPhysicalBreak = new AtomicBoolean();
        AtomicInteger settlementCallbacks = new AtomicInteger();

        context.failIfEver(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            if (world.getBlockState(ore).isAir()) {
                sawPhysicalBreak.set(true);
                if (world.getBlockState(support).is(Blocks.DIRT)) {
                    sawCatchSupport.set(true);
                } else {
                    require(context, settlementCallbacks.incrementAndGet() <= 1,
                            "coal break remained open past its first task-settlement tick");
                }
            }
            for (ItemEntity entity : world.getEntitiesOfClass(
                    ItemEntity.class, new AABB(ore.below(5)).inflate(1.0D, 5.0D, 1.0D),
                    item -> item.getItem().is(Items.COAL))) {
                require(context, entity.getBlockY() >= ore.getY(),
                        "coal entity fell below its supported break cell: "
                                + entity.blockPosition().toShortString());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, sawCatchSupport.get() && sawPhysicalBreak.get(),
                    "drop-catch fixture did not settle the break with physical support");
            require(context, world.getBlockState(support).is(Blocks.DIRT),
                    "drop catch did not spend the first sacrificial block");
            require(context, InventoryAction.countItem(bot, Items.COAL) == 1,
                    "supported coal was not physically recovered");
            require(context, bot.getStats().getValue(
                            Stats.ITEM_PICKED_UP.get(Items.COAL)) > pickupBaseline,
                    "supported coal bypassed vanilla pickup statistics");
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_diagonal_eye_height_ore_waits_for_cardinal_work_pose", maxTicks = 500)
    public void diagonalEyeHeightOreWaitsForCardinalWorkPose(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreDiagonalBreakGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos ore = start.east().north().above();
        var world = bot.level();
        // A floor-level diagonal ore has no observable support under any cardinal work pose.
        // Give it one factual, south cardinal stance reached by an ordinary one-block climb.
        // Leaving several disconnected high stances made the selector pick its north-first pose
        // even though the fixture only supplied a route to a different side.
        world.setBlock(ore.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            world.setBlock(ore.relative(direction), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(ore.relative(direction).above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(ore.relative(direction).below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        BlockPos workPose = ore.south();
        world.setBlock(workPose.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        // Keep headroom above the break cell. The cardinal stance remains the only observed
        // break pose, while a real item launch can be recovered through ordinary collision
        // instead of becoming trapped in a one-cell cavity beyond pickup reach.
        world.setBlock(ore.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(ore, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
        require(context, ObservableWorldQuery.canObserveBlock(bot, ore),
                "diagonal eye-height coal is not strictly observable");
        require(context, Math.abs(ore.getX() - start.getX())
                        + Math.abs(ore.getZ() - start.getZ()) == 2,
                "fixture is not the diagonal break geometry");
        require(context, workPose.equals(OreDigTask.inspectApproachGoalFor(bot, world, ore)),
                "diagonal fixture did not expose its sole observed cardinal work pose");

        assertStrictCapabilities(context, bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 1));
        int deathBaseline = deathCount(bot);
        int pickupBaseline = bot.getStats().getValue(
                Stats.ITEM_PICKED_UP.get(Items.COAL));
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_ore_diagonal_break_work_pose"));
        AtomicBoolean sawCardinalWorkPose = new AtomicBoolean();

        context.failIfEver(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            if (encode(ore).equals(task.checkpoint().get("active_break_pos"))) {
                BlockPos feet = bot.blockPosition();
                int horizontalManhattan = Math.abs(ore.getX() - feet.getX())
                        + Math.abs(ore.getZ() - feet.getZ());
                require(context, horizontalManhattan <= 1,
                        "diagonal ore opened before a cardinal work pose: bot="
                                + feet.toShortString() + " ore=" + ore.toShortString());
                require(context, !feet.equals(start),
                        "diagonal ore opened from the original corner pose");
                sawCardinalWorkPose.set(true);
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, sawCardinalWorkPose.get(),
                    "fixture never exercised the cardinal work-pose boundary");
            require(context, InventoryAction.countItem(bot, Items.COAL) == 1,
                    "diagonal coal was not physically recovered");
            require(context, bot.getStats().getValue(
                            Stats.ITEM_PICKED_UP.get(Items.COAL)) > pickupBaseline,
                    "diagonal coal bypassed vanilla pickup statistics");
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_three_above_ore_requires_reachable_high_work_pose_for_natural_pickup", maxTicks = 700)
    public void threeAboveOreRequiresReachableHighWorkPoseForNaturalPickup(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupHighFaceGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        BlockPos start = fixture.start();
        BlockPos firstStep = start.south().above();
        BlockPos workPose = firstStep.east().above();
        BlockPos ore = start.east().above(3);

        // A high ore can be inside vanilla eye reach while its launched ItemEntity can still drift
        // onto an unreachable ledge. Build a real two-step, cardinal staircase from the initial
        // floor to an observed side work pose; the ore may open only after ordinary movement
        // reaches that recoverable envelope.
        world.setBlock(firstStep.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(firstStep, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(firstStep.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(workPose.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(workPose, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(workPose.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(ore, Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        require(context, !firstStep.above(2).equals(ore),
                "high-face staircase cannot put its second jump head inside the ore");
        require(context, ore.getY() - start.getY() == 3
                        && Math.abs(ore.getX() - start.getX()) == 1,
                "fixture does not reproduce the dx=1,dy=+3 break boundary");
        require(context, bot.getEyePosition().distanceToSqr(ore.getCenter()) <= 20.25D,
                "high-face fixture must begin inside the old vanilla-reach shortcut");
        require(context, ObservableWorldQuery.canObserveBlock(bot, ore),
                "high-face iron must be strictly observable from the lower floor");
        require(context, ObservableWorldQuery.canObserveCell(bot, firstStep)
                        && ObservableWorldQuery.canObserveCell(bot, firstStep.above())
                        && ObservableWorldQuery.canObserveBlock(bot, firstStep.below())
                        && ObservableWorldQuery.canObserveCell(bot, workPose)
                        && ObservableWorldQuery.canObserveCell(bot, workPose.above())
                        && ObservableWorldQuery.canObserveBlock(bot, workPose.below())
                        && Standability.isStandable(world, firstStep)
                        && Standability.isStandable(world, workPose),
                "high-face staircase must be a fully observed connected walk-only route");

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        int pickupBaseline = bot.getStats().getValue(
                Stats.ITEM_PICKED_UP.get(Items.RAW_IRON));
        OreDigTask task = new OreDigTask(Set.of(Blocks.IRON_ORE), 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_pickup_high_face"));
        AtomicBoolean sawHighWorkPose = new AtomicBoolean();
        AtomicBoolean sawPhysicalBreak = new AtomicBoolean();
        AtomicBoolean sawNaturalDrop = new AtomicBoolean();

        context.failIfEver(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            Map<String, String> live = task.checkpoint();
            if (encode(ore).equals(live.get("active_break_pos"))) {
                require(context, bot.blockPosition().equals(workPose),
                        "dy=+3 ore opened before reaching its high side work pose: bot="
                                + bot.blockPosition().toShortString()
                                + " expected=" + workPose.toShortString());
                Standability.clearCache();
                require(context, Standability.isStandable(world, workPose),
                        "dy=+3 ore opened from a non-standable high work pose");
                sawHighWorkPose.set(true);
            }
            if (world.getBlockState(ore).isAir()) {
                require(context, sawHighWorkPose.get(),
                        "dy=+3 ore broke before its high work pose was observed");
                sawPhysicalBreak.set(true);
                if (!world.getEntitiesOfClass(
                        ItemEntity.class, new AABB(ore).inflate(3.0D),
                        entity -> entity.getItem().is(Items.RAW_IRON)).isEmpty()) {
                    sawNaturalDrop.set(true);
                }
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, sawHighWorkPose.get() && sawPhysicalBreak.get(),
                    "fixture did not exercise high-face work-pose mining");
            require(context, sawNaturalDrop.get(),
                    "high-face iron entered inventory without an observed vanilla ItemEntity");
            require(context, InventoryAction.countItem(bot, Items.RAW_IRON) == 1,
                    "high-face iron drop was not physically recovered");
            require(context, bot.getStats().getValue(
                            Stats.ITEM_PICKED_UP.get(Items.RAW_IRON)) > pickupBaseline,
                    "high-face raw iron bypassed vanilla pickup statistics");
            require(context, !task.checkpoint().containsKey("pending_pickup_pos")
                            && !task.checkpoint().containsKey("pending_pickup_last_seen_pos"),
                    "completed high-face mining retained pickup debt: " + task.checkpoint());
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_lower_floor_ore_clears_swept_pickup_egress_before_breaking", maxTicks = 400)
    public void lowerFloorOreClearsSweptPickupEgressBeforeBreaking(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupLowerLedgeGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        BlockPos start = fixture.start();
        BlockPos ore = start.north().below();
        BlockPos landingSupport = ore.below();
        BlockPos upperOverhang = ore.above(2);
        world.setBlock(ore, Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(ore.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(landingSupport, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(upperOverhang, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(
                Items.COBBLESTONE, MiningBudget.EMERGENCY_STONE_LIKE + 1));
        require(context, ObservableWorldQuery.canObserveBlock(bot, ore),
                "fixture floor ore is not exposed to strict perception");
        int pickupBaseline = bot.getStats().getValue(
                Stats.ITEM_PICKED_UP.get(Items.RAW_IRON));
        int deathBaseline = deathCount(bot);
        OreDigTask task = new OreDigTask(Set.of(Blocks.IRON_ORE), 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_pickup_lower_ledge"));
        AtomicBoolean egressCleared = new AtomicBoolean();
        AtomicBoolean oreBroken = new AtomicBoolean();

        context.failIfEver(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            if (world.getBlockState(upperOverhang).isAir()) {
                egressCleared.set(true);
            }
            if (world.getBlockState(ore).isAir()) {
                require(context, egressCleared.get(),
                        "finite floor ore broke before its swept pickup egress was clear");
                oreBroken.set(true);
            }
            require(context, world.getBlockState(start.below()).is(Blocks.STONE)
                            && world.getBlockState(landingSupport).is(Blocks.STONE),
                    "pickup recovery modified either factual support block");
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, egressCleared.get() && oreBroken.get(),
                    "fixture did not exercise ordered egress clearing and target break");
            require(context, InventoryAction.countItem(bot, Items.RAW_IRON) == 1,
                    "expected exactly one physically recovered raw iron");
            require(context, bot.getStats().getValue(
                            Stats.ITEM_PICKED_UP.get(Items.RAW_IRON)) > pickupBaseline,
                    "raw iron entered inventory without vanilla pickup statistics");
            require(context, !task.checkpoint().containsKey("pending_pickup_pos"),
                    "completed lower-ledge pickup retained its durable debt");
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_lower_floor_coal_over_open_shaft_gets_physical_drop_support", maxTicks = 500)
    public void lowerFloorCoalOverOpenShaftGetsPhysicalDropSupport(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupLowerShaftGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        BlockPos start = fixture.start();
        BlockPos ore = start.north().below();
        BlockPos support = ore.below();
        BlockPos anchor = support.below();
        world.setBlock(ore, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(ore.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(ore.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // The ore hides this water cell until the physical break. A legitimate shaft can receive
        // an incoming water flow during that same settlement window; the miner must replace the
        // observed, replaceable water with its one real drop catch instead of abandoning the
        // fresh ItemEntity to the shaft.
        world.setBlock(support, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(anchor, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        InventoryAction.giveItem(bot, new ItemStack(
                Items.COBBLESTONE, MiningBudget.EMERGENCY_STONE_LIKE + 1));
        require(context, ObservableWorldQuery.canObserveBlock(bot, ore),
                "fixture lower coal is not exposed to strict perception");
        require(context, !ObservableWorldQuery.canObserveCell(bot, support),
                "fixture did not preserve the lower ore-occluded support ray");

        assertStrictCapabilities(context, bot);
        int pickupBaseline = bot.getStats().getValue(
                Stats.ITEM_PICKED_UP.get(Items.COAL));
        int fillerBaseline = InventoryAction.countItem(bot, Items.COBBLESTONE);
        int deathBaseline = deathCount(bot);
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_pickup_lower_shaft"));
        AtomicBoolean sawPhysicalBreak = new AtomicBoolean();
        AtomicBoolean sawCatchSupport = new AtomicBoolean();
        AtomicInteger settlementCallbacks = new AtomicInteger();

        context.failIfEver(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            if (world.getBlockState(ore).isAir()) {
                sawPhysicalBreak.set(true);
                if (world.getBlockState(support).is(Blocks.COBBLESTONE)) {
                    sawCatchSupport.set(true);
                } else {
                    // BlockMiner removes the ore before OreDig observes DONE on its following
                    // task tick. Permit that single callback edge, but never a second open tick in
                    // which the fresh ItemEntity could fall below the reachable pickup envelope.
                    require(context, settlementCallbacks.incrementAndGet() <= 1,
                            "lower coal break remained unsupported past its first settlement tick");
                }
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, sawPhysicalBreak.get() && sawCatchSupport.get(),
                    "lower-shaft fixture did not exercise the physical drop catch");
            require(context, world.getBlockState(support).is(Blocks.COBBLESTONE),
                    "lower-shaft drop support was not present at task completion");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE)
                            == fillerBaseline - 1,
                    "lower-shaft drop catch did not consume exactly one real support block");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE)
                            == MiningBudget.EMERGENCY_STONE_LIKE,
                    "lower-shaft drop catch consumed the protected emergency reserve");
            require(context, InventoryAction.countItem(bot, Items.COAL) == 1,
                    "supported lower coal was not physically recovered");
            require(context, bot.getStats().getValue(
                            Stats.ITEM_PICKED_UP.get(Items.COAL)) > pickupBaseline,
                    "lower coal bypassed vanilla pickup statistics");
            require(context, !task.checkpoint().containsKey("pending_pickup_pos"),
                    "completed lower-shaft pickup retained its durable debt");
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_exact_protected_reserve_rejects_open_shaft_ore_before_break_and_restart", maxTicks = 40)
    public void exactProtectedReserveRejectsOpenShaftOreBeforeBreakAndRestart(
            GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreDropCommitReserveGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos ore = start.north();
        var world = context.getLevel();
        int protectedStone = ServicePolicy.bootstrapStoneLikeTarget(32)
                + MiningBudget.OBSIDIAN_BOOTSTRAP_CHANNEL_RETRY_STONE_LIKE;

        world.setBlock(ore, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
        for (int depth = 1; depth <= 5; depth++) {
            world.setBlock(ore.below(depth), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        world.setBlock(ore.below(6), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, protectedStone));
        require(context, ObservableWorldQuery.canObserveBlock(bot, ore),
                "drop-commit fixture did not expose its finite coal");
        require(context, MaterialPalette.pickPathSupportBlockSlot(
                        bot, protectedStone).isEmpty(),
                "exact parent reserve exposed a drop-support block");

        Map<String, String> initial = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        OreDigTask task = new OreDigTask(
                Set.of(Blocks.COAL_ORE), 1, 0, protectedStone, initial);
        task.start(bot);
        task.tick(bot); // acquire the observed finite ore
        task.tick(bot); // reject the break before active/pending debt can be published
        Map<String, String> blocked = task.checkpoint();

        require(context, task.state() == TaskState.RUNNING
                        && world.getBlockState(ore).is(Blocks.COAL_ORE),
                "exact-reserve drop gate modified or ended the finite ore: "
                        + task.state() + ":" + task.failureReason());
        require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == protectedStone,
                "drop commit consumed the protected parent reserve");
        require(context, !blocked.containsKey("active_break_pos")
                        && !blocked.containsKey("pending_pickup_pos")
                        && !blocked.containsKey("pending_pickup_last_seen_pos")
                        && OreDigTask.inspectCheckpoint(blocked).isPresent(),
                "rejected drop commit published physical debt or an invalid checkpoint: " + blocked);
        require(context, bot.getActionPack().isMiningIdle(),
                "rejected drop commit left a target miner active");

        task.cancel(bot, "gametest_drop_commit_restart");
        OreDigTask restored = new OreDigTask(
                Set.of(Blocks.COAL_ORE), 1, 0, protectedStone, blocked);
        restored.start(bot);
        restored.tick(bot);
        Map<String, String> after = restored.checkpoint();
        require(context, restored.state() == TaskState.RUNNING
                        && world.getBlockState(ore).is(Blocks.COAL_ORE)
                        && InventoryAction.countItem(bot, Items.COBBLESTONE) == protectedStone
                        && !after.containsKey("active_break_pos")
                        && !after.containsKey("pending_pickup_pos")
                        && Integer.parseInt(after.get("budget_used"))
                        > Integer.parseInt(blocked.get("budget_used")),
                "checkpoint restart bypassed the exact-reserve drop gate: before="
                        + blocked + " after=" + after);
        restored.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_active_break_cancels_when_its_only_surplus_support_disappears_before_restart", maxTicks = 50)
    public void activeBreakCancelsWhenItsOnlySurplusSupportDisappearsBeforeRestart(
            GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreDropCommitRevokedGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos ore = start.north();
        var world = context.getLevel();
        int protectedStone = ServicePolicy.bootstrapStoneLikeTarget(32)
                + MiningBudget.OBSIDIAN_BOOTSTRAP_CHANNEL_RETRY_STONE_LIKE;

        world.setBlock(
                ore, Blocks.DEEPSLATE_COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
        for (int depth = 1; depth <= 5; depth++) {
            world.setBlock(ore.below(depth), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        world.setBlock(ore.below(6), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, protectedStone + 1));

        Map<String, String> initial = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.DEEPSLATE_COAL_ORE)));
        OreDigTask task = new OreDigTask(
                Set.of(Blocks.DEEPSLATE_COAL_ORE), 1, 0, protectedStone, initial);
        task.start(bot);
        task.tick(bot); // acquire
        task.tick(bot); // pass the support gate and begin the slow deepslate break
        Map<String, String> active = task.checkpoint();
        require(context, world.getBlockState(ore).is(Blocks.DEEPSLATE_COAL_ORE)
                        && encode(ore).equals(active.get("active_break_pos"))
                        && InventoryAction.countItem(bot, Items.COBBLESTONE)
                        == protectedStone + 1,
                "fixture did not open an intact, support-authorized target break: " + active);

        require(context, InventoryAction.removeItems(bot, Items.COBBLESTONE, 1),
                "fixture could not consume the sole surplus support");
        task.tick(bot);
        Map<String, String> revoked = task.checkpoint();
        require(context, task.state() == TaskState.RUNNING
                        && world.getBlockState(ore).is(Blocks.DEEPSLATE_COAL_ORE)
                        && InventoryAction.countItem(bot, Items.COBBLESTONE) == protectedStone
                        && !revoked.containsKey("active_break_pos")
                        && !revoked.containsKey("pending_pickup_pos")
                        && bot.getActionPack().isMiningIdle(),
                "revoked support authorization continued or retained the finite break: "
                        + revoked);

        task.cancel(bot, "gametest_revoked_support_restart");
        OreDigTask restored = new OreDigTask(
                Set.of(Blocks.DEEPSLATE_COAL_ORE), 1, 0, protectedStone, revoked);
        restored.start(bot);
        restored.tick(bot);
        Map<String, String> after = restored.checkpoint();
        require(context, restored.state() == TaskState.RUNNING
                        && world.getBlockState(ore).is(Blocks.DEEPSLATE_COAL_ORE)
                        && !after.containsKey("active_break_pos")
                        && !after.containsKey("pending_pickup_pos")
                        && InventoryAction.countItem(bot, Items.COBBLESTONE) == protectedStone,
                "restart recreated an active break after its support commit was revoked: " + after);
        restored.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

            @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_visible_lava_rotates_the_branch_instead_of_assigning_impossible_evade", maxTicks = 180)
    public void visibleLavaRotatesTheBranchInsteadOfAssigningImpossibleEvade(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreLavaRerouteGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        // Keep one source exposed to the south while fixture stone contains its other three sides.
        // The callback clears the one vanilla flow cell before every repeated watcher scan, making
        // this a stable observation instead of a fluid-tick race.
        BlockPos lava = fixture.start().north(2);
        world.setBlock(lava.north(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(lava.east(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(lava.west(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(lava, Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(lava.south().east(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(lava.south().west(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        // The east wall is factual new territory; the open west side is an already-controlled
        // corridor and must not be selected merely because the old square cursor rotated there.
        world.setBlock(
                fixture.start().east(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(
                fixture.start().east().above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(fixture.start(), 1, Set.of(Blocks.IRON_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "12");
        OreDigTask task = new OreDigTask(Set.of(Blocks.IRON_ORE), 1, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_lava_reroute"));

        DangerWatcher.INSTANCE.scanBot(world.getServer(), bot);
        require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == task,
                "visible branch lava replaced OreDig with an impossible underground Evade");
        require(context, "1".equals(task.checkpoint().get("direction"))
                        && "12".equals(task.checkpoint().get("steps_left")),
                "DangerWatcher did not atomically preserve the unfinished leg through the east "
                        + "fresh-work detour: " + task.checkpoint());
        AtomicBoolean rotatedEast = new AtomicBoolean();
        AtomicInteger ticks = new AtomicInteger();
        context.failIfEver(() -> {
            world.setBlock(lava, Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(lava.south(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            DangerWatcher.INSTANCE.scanBot(world.getServer(), bot);
            require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == task,
                    "repeated lava scan replaced OreDig during its bounded escape window");
            require(context, task.state() == TaskState.RUNNING,
                    "lava reroute ended OreDig as " + task.state() + ":" + task.failureReason());
            String direction = task.checkpoint().get("direction");
            if ("1".equals(direction)) {
                rotatedEast.set(true);
            }
            if (rotatedEast.get() && !"1".equals(direction)) {
                context.fail(Component.nullToEmpty(
                        "same visible lava repeatedly rotated the branch: direction=" + direction));
            }
            require(context, world.getBlockState(lava).is(Blocks.LAVA),
                    "lava reroute mutated the factual lava source");
            require(context, bot.blockPosition().getZ() >= fixture.start().getZ(),
                    "OreDig advanced toward the rejected north lava branch");
            if (bot.blockPosition().getX() > fixture.start().getX()) {
                require(context, rotatedEast.get(),
                        "OreDig left the lava radius without publishing the east cursor");
                DangerWatcher.INSTANCE.clear(bot);
                finish(context, fixture);
                return;
            }
            if (ticks.incrementAndGet() > 140) {
                context.fail(Component.nullToEmpty(
                        "OreDig did not physically leave the lava-facing origin within 140 ticks"));
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_watcher_does_not_mistake_a_visible_lava_pool_for_the_active_branch_cell", maxTicks = 20)
    public void watcherDoesNotMistakeAVisibleLavaPoolForTheActiveBranchCell(
            GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreWatcherLavaClusterGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        BlockPos start = fixture.start();
        BlockPos lavaA = start.north(2);
        BlockPos lavaB = lavaA.east();
        world.setBlock(lavaA, Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(lavaB, Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.east(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.east().above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(
                Items.COBBLESTONE, MiningBudget.EMERGENCY_STONE_LIKE + 1));
        require(context, ObservableWorldQuery.canObserveCell(bot, lavaA)
                        && ObservableWorldQuery.canObserveCell(bot, lavaB),
                "watcher lava-cluster fixture did not expose both fluid cells");

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.IRON_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "12");
        OreDigTask task = new OreDigTask(Set.of(Blocks.IRON_ORE), 1, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_ore_watcher_lava_cluster"));

        require(context, DangerWatcher.INSTANCE.scanBot(world.getServer(), bot),
                "watcher did not claim the visible lava cluster");
        Map<String, String> rerouted = task.checkpoint();
        require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == task
                        && task.state() == TaskState.RUNNING
                        && "1".equals(rerouted.get("direction"))
                        && "12".equals(rerouted.get("steps_left"))
                        && world.getBlockState(lavaA).is(Blocks.LAVA)
                        && world.getBlockState(lavaB).is(Blocks.LAVA)
                        && InventoryAction.countItem(bot, Items.COBBLESTONE)
                        == MiningBudget.EMERGENCY_STONE_LIKE + 1,
                "watcher sealed an arbitrary pool cell instead of publishing a real reroute: "
                        + rerouted);
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
        finish(context, fixture);
    }


    /**
     * Deterministic replay of the intermittent launch-RNG failure: a mined drop can land on the
     * raised 1x1 work-pose pedestal beside the shaft. The recovery loop must climb the two-step
     * ascent and physically collect it instead of idling into ore_dig_drop_unrecovered.
     *
     * <p>The two-step climb is real jump-arc physics, not an instant reposition; under heavy
     * concurrent GameTest load (the full suite runs hundreds of bots' real per-tick physics at
     * once) it can legitimately need more real ticks than an isolated single-test run does to
     * finish the same climb. 900 matches this file's own budget for its closest sibling
     * (restoredObservedHighWorkPoseRoutesWithoutDigging).</p>
     */
    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_pedestal_landed_drop_is_physically_recovered", maxTicks = 900)
    public void pedestalLandedDropIsPhysicallyRecovered(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OrePedestalDropGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        BlockPos start = fixture.start();
        BlockPos ore = start.east().above(2);
        BlockPos firstRise = start.south().above();
        // This is the legal high-ore work pose: cardinal to the ore and one cell below it.
        // The first rise reaches the same ledge by an ordinary one-block step.
        BlockPos riseStep = firstRise.east();
        BlockPos pedestal = riseStep.east().above();

        world.setBlock(ore, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(firstRise.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(firstRise, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(firstRise.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(pedestal.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(riseStep.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        for (BlockPos open : new BlockPos[]{
                pedestal, pedestal.above(), riseStep, riseStep.above()}) {
            world.setBlock(open, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        require(context, ObservableWorldQuery.canObserveBlock(bot, ore),
                "pedestal fixture coal must be strictly observable");
        // The upper cells are deliberately revealed through ordinary movement.  Requiring them to
        // be visible from the starting floor would reject the very climb this recovery fixture is
        // exercising; only the target itself has to be observed before the task starts.
        require(context, Standability.isStandable(world, firstRise)
                        && Standability.isStandable(world, riseStep),
                "pedestal fixture must provide a connected walk-only staircase");

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        // This case exercises recovery of a redirected vanilla drop, not discovery of a high
        // work pose. Seed the factual cardinal pose that the route will use; high-pose discovery
        // and checkpoint validation have their own fixtures below.
        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("remembered_high_work_poses", encode(ore) + "@" + encode(riseStep));
        require(context, OreDigTask.inspectCheckpoint(checkpoint).isPresent(),
                "pedestal fixture rejected its valid remembered high work pose: " + checkpoint);
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_pedestal_drop"));
        AtomicBoolean dropPlaced = new AtomicBoolean();

        context.failIfEver(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            if (!dropPlaced.get()) {
                var drops = world.getEntitiesOfClass(ItemEntity.class,
                        AABB.encapsulatingFullBlocks(start.offset(-6, -2, -10), start.offset(6, 4, 4)),
                        candidate -> candidate.getItem().is(Items.COAL));
                if (!drops.isEmpty()) {
                    // Replace the random launch velocity with the worst observed landing: at
                    // rest on top of the floating pedestal. This is fixture determinism, not a
                    // capability: the entity stays a vanilla ItemEntity the bot must reach.
                    ItemEntity drop = drops.get(0);
                    drop.setDeltaMovement(Vec3.ZERO);
                    drop.setPos(
                            pedestal.getX() + 0.5D, pedestal.getY(), pedestal.getZ() + 0.5D);
                    dropPlaced.set(true);
                }
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, dropPlaced.get(),
                    "fixture never intercepted the coal drop");
            require(context, InventoryAction.countItem(bot, Items.COAL) == 1,
                    "pedestal drop was not physically recovered, coal="
                            + InventoryAction.countItem(bot, Items.COAL));
            require(context, !task.checkpoint().containsKey("pending_pickup_pos"),
                    "completed pedestal recovery retained pickup debt: " + task.checkpoint());
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_restored_observed_high_work_pose_routes_without_digging", maxTicks = 900)
    public void restoredObservedHighWorkPoseRoutesWithoutDigging(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreHighPoseRestartGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        BlockPos start = fixture.start();
        BlockPos ore = start.above(3);
        BlockPos workPose = ore.below().east();
        BlockPos riseStep = workPose.south().below();

        world.setBlock(riseStep.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(riseStep, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(riseStep.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(workPose.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(workPose, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(workPose.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // The restored side pose is the route under test.  Give the broken high ore its ordinary
        // physical landing surface so the resulting vanilla ItemEntity does not fall through an
        // unrelated open shaft and turn this route recovery test into a drop-loss fixture.
        world.setBlock(ore.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(ore, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);

        require(context, bot.blockPosition().equals(start)
                        && OreDigTask.inspectApproachGoalFor(bot, world, ore) == null,
                "direct-under restart must not have a live side-pose observation");

        String encodedRememberedPose = encode(ore) + "@" + encode(workPose);
        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("remembered_high_work_poses", encodedRememberedPose);
        require(context, OreDigTask.inspectCheckpoint(checkpoint).isPresent(),
                "valid remembered high-work-pose checkpoint was rejected: " + checkpoint);

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        int pickupBaseline = bot.getStats().getValue(
                Stats.ITEM_PICKED_UP.get(Items.COAL));
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_high_pose_restart"));
        AtomicBoolean retainedRememberedPose = new AtomicBoolean();
        AtomicBoolean enteredRiseStep = new AtomicBoolean();
        AtomicBoolean enteredWorkPose = new AtomicBoolean();
        AtomicBoolean openedFromWorkPose = new AtomicBoolean();
        AtomicBoolean dropSettledOnSupport = new AtomicBoolean();

        context.failIfEver(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            if (!dropSettledOnSupport.get()) {
                var drops = world.getEntitiesOfClass(ItemEntity.class,
                        AABB.encapsulatingFullBlocks(ore.offset(-2, -2, -2), ore.offset(2, 2, 2)),
                        candidate -> candidate.getItem().is(Items.COAL));
                if (!drops.isEmpty()) {
                    // Keep the real vanilla ItemEntity on the fixture's observed landing surface.
                    // Launch velocity is unrelated to restored-pose routing and otherwise lets the
                    // drop escape into a neighbouring lower cell before recovery begins.
                    ItemEntity drop = drops.get(0);
                    drop.setDeltaMovement(Vec3.ZERO);
                    drop.setPos(ore.getX() + 0.5D, ore.getY(), ore.getZ() + 0.5D);
                    dropSettledOnSupport.set(true);
                }
            }
            Map<String, String> live = task.checkpoint();
            if (encodedRememberedPose.equals(live.get("remembered_high_work_poses"))) {
                retainedRememberedPose.set(true);
            }
            if (bot.blockPosition().equals(riseStep)) {
                enteredRiseStep.set(true);
            }
            if (bot.blockPosition().equals(workPose)) {
                enteredWorkPose.set(true);
            }
            if (encode(ore).equals(live.get("active_break_pos"))) {
                require(context, retainedRememberedPose.get() && enteredWorkPose.get(),
                        "restored high ore opened without retaining and reaching its factual pose");
                require(context, bot.blockPosition().equals(workPose),
                        "high ore opened outside its recovered side work pose: bot="
                                + bot.blockPosition().toShortString());
                openedFromWorkPose.set(true);
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, retainedRememberedPose.get() && enteredRiseStep.get()
                            && enteredWorkPose.get() && openedFromWorkPose.get(),
                    "fixture did not restore, route to, and mine from the observed high work pose");
            require(context, dropSettledOnSupport.get(),
                    "fixture never settled the vanilla coal drop on its observed support");
            require(context, InventoryAction.countItem(bot, Items.COAL) == 1,
                    "restored high-pose coal was not physically recovered");
            require(context, bot.getStats().getValue(
                            Stats.ITEM_PICKED_UP.get(Items.COAL)) > pickupBaseline,
                    "restored high-pose coal bypassed vanilla pickup statistics");
            require(context, world.getBlockState(riseStep.below()).is(Blocks.STONE)
                            && world.getBlockState(workPose.below()).is(Blocks.STONE),
                    "remembered work-pose route dug through its staircase supports");
            require(context, !task.checkpoint().containsKey("remembered_high_work_poses")
                            && !task.checkpoint().containsKey("active_break_pos")
                            && !task.checkpoint().containsKey("pending_pickup_pos"),
                    "completed restored high-pose task retained finite debt: "
                            + task.checkpoint());
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_remembered_high_work_pose_owner_lease_expires_across_successful_replans", maxTicks = 180)
    public void rememberedHighWorkPoseOwnerLeaseExpiresAcrossSuccessfulReplans(
            GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreHighPoseLeaseGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        BlockPos start = fixture.start();
        BlockPos ore = start.above(3);
        BlockPos workPose = ore.below().east();

        // Keep each exact route alive for at least the five-tick successful-path cooldown. Every
        // sixth tick the fixture cancels its still-in-flight route and asks for another from the
        // bot's physical current cell. The absolute owner lease must still expire.
        // Match the proven restored-high-pose staircase: one normal jump reaches the rise,
        // followed by one ordinary step onto the historical side pose. Two consecutive upward
        // jumps have no admitted observed surface corridor even though every individual cell is safe.
        BlockPos riseStep = workPose.south().below();
        world.setBlock(riseStep.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(riseStep, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(riseStep.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(workPose.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(workPose, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(workPose.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(ore, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();
        require(context, Standability.isStandable(world, riseStep)
                        && Standability.isStandable(world, workPose),
                "lease fixture did not build an observed physical route to its work pose");

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("remembered_high_work_poses", encode(ore) + "@" + encode(workPose));
        require(context, OreDigTask.inspectCheckpoint(checkpoint).isPresent(),
                "route-lease fixture checkpoint was rejected");
        assertStrictCapabilities(context, bot);

        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        task.start(bot);
        // This is the post-observation phase: production already recorded the exact high owner
        // and pose before a later route returned the bot to the factual start. Restore that owner
        // directly so this test exercises the finite no-dig lease, rather than asking the bounded
        // observed scanner to rediscover a remote ore.
        setBlockPosFieldForFixture(task, "targetOre", ore);
        int deathBaseline = deathCount(bot);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicInteger successfulRouteStarts = new AtomicInteger();
        AtomicBoolean acceptedRouteLive = new AtomicBoolean();

        context.failIfEver(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            int callback = callbacks.incrementAndGet();
            // Let an accepted route remain published for at least one task interval, then cancel
            // it from the bot's actual cell. startSurfacePathTo may throttle one tick after a
            // cancellation, so a replacement is counted only when ActionPack exposes its exact
            // goal rather than being assumed synchronously at the cancellation boundary.
            if (acceptedRouteLive.get()) {
                bot.getActionPack().stopAll();
                acceptedRouteLive.set(false);
            }
            task.tick(bot);

            if (!EpisodeMemory.INSTANCE.isExcluded(
                    bot.getUUID(), ore, bot.level().getServer().getTickCount())
                    && workPose.equals(bot.getActionPack().activePathGoal())) {
                acceptedRouteLive.set(true);
                successfulRouteStarts.incrementAndGet();
            }
            if (!EpisodeMemory.INSTANCE.isExcluded(
                    bot.getUUID(), ore, bot.level().getServer().getTickCount())) {
                require(context, callback <= 96,
                        "successful remembered routes renewed the finite owner lease forever");
                return;
            }

            Map<String, String> terminalCheckpoint = task.checkpoint();
            int leaseAge = Integer.parseInt(terminalCheckpoint.get("budget_used"));
            require(context, successfulRouteStarts.get() >= 3,
                    "fixture did not issue repeated successful routes before lease expiry");
            require(context, leaseAge > 80 && leaseAge <= 86,
                    "remembered owner expired outside its absolute lease: " + leaseAge);
            require(context, task.state() == TaskState.RUNNING,
                    "finite owner abandonment terminated the whole OreDig task: "
                            + task.state() + ":" + task.failureReason());
            require(context, world.getBlockState(ore).is(Blocks.COAL_ORE),
                    "no-dig remembered route modified its finite ore owner");
            require(context, !terminalCheckpoint.containsKey("remembered_high_work_poses"),
                    "expired remembered owner survived in the durable ledger");
            require(context, inspectVeinQueueForFixture(task).isEmpty(),
                    "expired remembered vein owner remained queued");
            require(context, bot.getActionPack().isPathExecutorIdle()
                            && bot.getActionPack().isWalkToIdle()
                            && bot.getActionPack().isMiningIdle(),
                    "owner lease expiry retained an action controller");
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_remembered_high_work_pose_capacity_evicts_deterministic_farthest_unpinned_owner", maxTicks = 30)
    public void rememberedHighWorkPoseCapacityEvictsDeterministicFarthestUnpinnedOwner(
            GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreHighPoseCapacityGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos face = fixture.start();
        Map<BlockPos, BlockPos> initial = new LinkedHashMap<>();
        BlockPos routeOwner = face.offset(20, 3, 19);
        BlockPos targetOwner = face.offset(19, 3, 20);
        BlockPos queueOwner = face.offset(-20, 3, 19);
        BlockPos expectedEviction = face.offset(19, 3, -20);
        BlockPos equalDistanceSurvivor = face.offset(-19, 3, 20);
        for (BlockPos owner : new BlockPos[]{
                routeOwner, targetOwner, queueOwner,
                expectedEviction, equalDistanceSurvivor}) {
            initial.put(owner.immutable(), owner.below().east().immutable());
        }
        for (int x = -4; x <= 4 && initial.size() < 64; x++) {
            for (int z = -4; z <= 4 && initial.size() < 64; z++) {
                if (x == 0 && z == 0) {
                    continue;
                }
                BlockPos owner = face.offset(x, 3, z);
                initial.put(owner.immutable(), owner.below().east().immutable());
            }
        }
        require(context, initial.size() == 64,
                "capacity fixture did not create exactly 64 remembered owners");

        StringBuilder encoded = new StringBuilder();
        initial.forEach((owner, pose) -> {
            if (!encoded.isEmpty()) {
                encoded.append(';');
            }
            encoded.append(encode(owner)).append('@').append(encode(pose));
        });
        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(face, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("remembered_high_work_poses", encoded.toString());
        require(context, OreDigTask.inspectCheckpoint(checkpoint).isPresent(),
                "64-entry remembered ledger fixture was rejected");

        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        task.start(bot);
        setBlockPosFieldForFixture(task, "rememberedHighWorkPoseRouteOwner", routeOwner);
        setBlockPosFieldForFixture(task, "targetOre", targetOwner);
        enqueueVeinForFixture(task, queueOwner);
        BlockPos candidate = face.above(3);
        BlockPos candidatePose = candidate.below().east();
        rememberHighWorkPoseForFixture(task, bot, candidate, candidatePose);

        Map<BlockPos, BlockPos> live = inspectRememberedHighWorkPosesForFixture(task);
        require(context, live.size() == 64,
                "runtime remembered ledger did not return to its exact cap: " + live.size());
        require(context, candidatePose.equals(live.get(candidate)),
                "fresh near observation was starved by a full stale ledger");
        require(context, live.containsKey(routeOwner)
                        && live.containsKey(targetOwner)
                        && live.containsKey(queueOwner),
                "capacity eviction removed a pinned finite owner");
        require(context, !live.containsKey(expectedEviction)
                        && live.containsKey(equalDistanceSurvivor),
                "equal-distance eviction did not use deterministic numeric x/y/z order");

        Map<String, String> durable = task.checkpoint();
        String durableEntries = durable.get("remembered_high_work_poses");
        require(context, durableEntries != null
                        && durableEntries.split(";", -1).length == 64
                        && OreDigTask.inspectCheckpoint(durable).isPresent(),
                "evicted runtime ledger did not publish a valid exact-cap checkpoint");
        finish(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_queued_high_ore_without_work_pose_stays_intact_and_search_continues", maxTicks = 1000)
    public void queuedHighOreWithoutWorkPoseStaysIntactAndSearchContinues(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreQueuedHighReachGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        BlockPos start = fixture.start();
        BlockPos shaft = start.east(2);
        BlockPos lowerOre = shaft.above(2);
        BlockPos upperOre = shaft.above(3);
        BlockPos higherOre = shaft.above(4);
        BlockPos highestOre = shaft.above(5);
        BlockPos alternativeOre = start.north(4).above();

        // Reproduce the seed-3000 authorization boundary: the top queued ore is exactly dy=+5 and
        // still inside vanilla reach, but no observed high side pose or staircase exists. The whole
        // high chain must remain intact after the recoverable lower ore, and mining must continue at
        // a separate finite ore instead of creating an unrecoverable ItemEntity debt.
        world.setBlock(lowerOre, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(upperOre, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(higherOre, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(highestOre, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(alternativeOre.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(alternativeOre, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
        require(context, ObservableWorldQuery.canObserveBlock(bot, lowerOre)
                        && ObservableWorldQuery.canObserveBlock(bot, upperOre)
                        && ObservableWorldQuery.canObserveBlock(bot, higherOre)
                        && ObservableWorldQuery.canObserveBlock(bot, highestOre),
                "stacked queued-ore fixture must begin strictly observable");
        require(context, bot.getEyePosition().distanceToSqr(highestOre.getCenter()) <= 20.25D,
                "dy=+5 queued ore must begin inside the former direct-break reach shortcut");
        require(context, ObservableWorldQuery.canObserveBlock(bot, alternativeOre)
                        && Standability.isStandable(world, alternativeOre.south().below()),
                "replacement coal must have a visible raised pedestal and safe side work pose");

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        int pickupBaseline = bot.getStats().getValue(
                Stats.ITEM_PICKED_UP.get(Items.COAL));
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 2);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_ore_queued_high_catch"));
        AtomicBoolean openedRecoverableLower = new AtomicBoolean();
        AtomicBoolean openedAlternative = new AtomicBoolean();

        context.failIfEver(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            Map<String, String> live = task.checkpoint();
            if (task.state() == TaskState.FAILED) {
                require(context, "no_observed_ore_after_exploration".equals(task.failureReason())
                                && world.getBlockState(upperOre).is(Blocks.COAL_ORE)
                                && world.getBlockState(higherOre).is(Blocks.COAL_ORE)
                                && world.getBlockState(highestOre).is(Blocks.COAL_ORE)
                                && InventoryAction.countItem(bot, Items.COAL) == 1
                                && bot.getStats().getValue(Stats.ITEM_PICKED_UP.get(Items.COAL))
                                >= pickupBaseline + 1
                                && !live.containsKey("active_break_pos")
                                && !live.containsKey("pending_pickup_pos"),
                        "bounded high-ore rejection left unsafe work or physical debt: "
                                + task.failureReason() + " " + live);
                finish(context, fixture);
                return;
            }
            failIfTerminalError(context, task);
            String active = live.get("active_break_pos");
            if (encode(lowerOre).equals(active)) {
                require(context, bot.blockPosition().equals(shaft),
                        "recoverable lower ore opened away from its close work pose: bot="
                                + bot.blockPosition().toShortString());
                openedRecoverableLower.set(true);
            }
            if (encode(alternativeOre).equals(active)) {
                openedAlternative.set(true);
            }
            require(context, !encode(upperOre).equals(active)
                            && !encode(higherOre).equals(active)
                            && !encode(highestOre).equals(active),
                    "high queued ore entered active break without a recoverable work pose: " + active);
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, openedRecoverableLower.get() && openedAlternative.get(),
                    "task did not continue from the rejected high chain to a safe ore");
            require(context, world.getBlockState(upperOre).is(Blocks.COAL_ORE)
                            && world.getBlockState(higherOre).is(Blocks.COAL_ORE)
                            && world.getBlockState(highestOre).is(Blocks.COAL_ORE),
                    "an unproven high-shaft ore was physically opened");
            require(context, InventoryAction.countItem(bot, Items.COAL) == 2,
                    "expected two physically recovered safe coal, got "
                            + InventoryAction.countItem(bot, Items.COAL));
            require(context, bot.getStats().getValue(
                            Stats.ITEM_PICKED_UP.get(Items.COAL)) >= pickupBaseline + 2,
                    "safe replacement coal bypassed vanilla pickup statistics");
            require(context, !live.containsKey("pending_pickup_pos")
                            && !live.containsKey("active_break_pos"),
                    "completed high-catch rejection retained finite mining debt: " + live);
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_queued_ore_beyond_vanilla_reach_is_released_without_cursor_livelock", maxTicks = 1200)
    public void queuedOreBeyondVanillaReachIsReleasedWithoutCursorLivelock(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreQueuedBeyondReachGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        BlockPos shaft = fixture.start().east(2);
        BlockPos unreachableOre = shaft.above(6);
        for (int dy = 2; dy <= 6; dy++) {
            world.setBlock(shaft.above(dy), Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
        }
        require(context, unreachableOre.getY() - shaft.getY() == 6,
                "queued release fixture must end beyond vanilla reach");

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 5);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_ore_queued_beyond_reach_release"));
        AtomicBoolean recoveredReachableLower = new AtomicBoolean();
        AtomicInteger ticksAfterLower = new AtomicInteger();
        AtomicReference<BlockPos> previous = new AtomicReference<>(bot.blockPosition().immutable());

        context.failIfEver(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            BlockPos now = bot.blockPosition();
            BlockPos before = previous.getAndSet(now.immutable());
            int movement = Math.max(
                    Math.max(Math.abs(now.getX() - before.getX()),
                            Math.abs(now.getY() - before.getY())),
                    Math.abs(now.getZ() - before.getZ()));
            require(context, movement <= 1,
                    "queued ore release caused non-adjacent movement: from="
                            + before.toShortString() + " to=" + now.toShortString());

            int coal = InventoryAction.countItem(bot, Items.COAL);
            boolean highChainIntact = true;
            for (int dy = 3; dy <= 6; dy++) {
                highChainIntact &= world.getBlockState(shaft.above(dy)).is(Blocks.COAL_ORE);
            }
            Map<String, String> live = task.checkpoint();
            if (task.state() == TaskState.FAILED) {
                require(context, "no_observed_ore_after_exploration".equals(task.failureReason())
                                && highChainIntact
                                && coal == 0
                                && !live.containsKey("active_break_pos")
                                && !live.containsKey("pending_pickup_pos"),
                        "unreachable queued ore did not end as a clean observed-frontier exhaustion: "
                                + task.failureReason() + " " + live);
                finish(context, fixture);
                return;
            }
            failIfTerminalError(context, task);
            if (coal >= 1 && highChainIntact) {
                recoveredReachableLower.set(true);
            }
            if (!recoveredReachableLower.get()) {
                return;
            }
            require(context, coal == 1,
                    "ore beyond vanilla reach entered inventory: " + coal);
            require(context, highChainIntact,
                    "queued release modified a high ore outside the recoverable break envelope");

            boolean cursorResumed = Integer.parseInt(live.get("direction")) >= 0
                    && Integer.parseInt(live.get("steps_left")) > 0;
            if (cursorResumed) {
                require(context, now.getY() == shaft.getY(),
                        "queued release abandoned the factual mining level: "
                                + now.toShortString());
                task.cancel(bot, "gametest_complete");
                finish(context, fixture);
                return;
            }
            if (ticksAfterLower.incrementAndGet() > 40) {
                context.fail(Component.nullToEmpty(
                        "queued ore beyond reach retained the vein head for over 40 ticks: "
                                + live + " bot=" + now.toShortString()));
            }
        });
    }

    private static int horizontalChebyshev(BlockPos from, BlockPos to) {
        return Math.max(Math.abs(from.getX() - to.getX()), Math.abs(from.getZ() - to.getZ()));
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_hidden_diagonal_drop_uses_exact_l_route_without_changing_corner_walls", maxTicks = 400)
    public void hiddenDiagonalDropUsesExactLRouteWithoutChangingCornerWalls(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupCornerGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        BlockPos start = fixture.start();
        BlockPos turn = start.west();
        BlockPos dropCell = turn.north();
        BlockPos blockedCorner = start.north();

        // Reproduce seed3000's final tunnel face: the remembered break cell is diagonally
        // adjacent, the direct line is blocked, and the already-open west->north L is the only
        // ordinary player route. Recovery may walk it but may not mine either corner wall.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz),
                            Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        for (BlockPos feet : new BlockPos[]{start, turn, dropCell}) {
            for (int dy = 0; dy <= 2; dy++) {
                world.setBlock(feet.above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }

        ItemEntity drop = new ItemEntity(world,
                dropCell.getX() + 0.5D, dropCell.getY() + 0.1D, dropCell.getZ() + 0.5D,
                new ItemStack(Items.COAL));
        drop.setDeltaMovement(Vec3.ZERO);
        drop.setNoGravity(true);
        drop.setOnGround(true);
        // Keep vanilla's generous nearby-player pickup check from consuming the diagonal item
        // before OreDig has started its remembered-cell route. Release it only after the bot has
        // physically entered the L turn below.
        drop.setNeverPickUp();
        require(context, world.addFreshEntity(drop), "failed to spawn hidden corner coal drop");
        require(context, !ObservableWorldQuery.canObserveEntity(bot, drop),
                "corner fixture did not hide the diagonal ItemEntity");

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("pending_pickup_pos", encode(dropCell));
        checkpoint.put("pending_pickup_inventory", "0");
        checkpoint.put("pending_pickup_started_budget", "0");

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        int pickupBaseline = bot.getStats().getValue(
                Stats.ITEM_PICKED_UP.get(Items.COAL));
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_pickup_corner"));
        AtomicBoolean sawPendingLedger = new AtomicBoolean();
        AtomicBoolean visitedTurn = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();

        context.failIfEver(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            Map<String, String> live = task.checkpoint();
            if (encode(dropCell).equals(live.get("pending_pickup_pos"))) {
                sawPendingLedger.set(true);
            }
            if (bot.blockPosition().equals(turn)) {
                visitedTurn.set(true);
                if (released.compareAndSet(false, true)) {
                    drop.setNoPickUpDelay();
                }
            }
            require(context, world.getBlockState(blockedCorner).is(Blocks.STONE)
                            && world.getBlockState(blockedCorner.above()).is(Blocks.STONE),
                    "pickup recovery modified the diagonal corner wall");
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, sawPendingLedger.get(),
                    "corner recovery never restored the durable pickup ledger");
            require(context, visitedTurn.get(),
                    "pickup did not traverse the required west->north L turn");
            require(context, released.get(),
                    "corner drop was collected before the L-route release boundary");
            require(context, InventoryAction.countItem(bot, Items.COAL) == 1,
                    "expected exactly one physically collected coal");
            require(context, bot.getStats().getValue(
                            Stats.ITEM_PICKED_UP.get(Items.COAL)) > pickupBaseline,
                    "corner coal entered inventory without vanilla pickup statistics");
            require(context, !live.containsKey("pending_pickup_pos"),
                    "completed corner pickup retained its durable debt");
            require(context, !drop.isAlive(), "picked corner coal entity remained alive");
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_diagonal_physical_pickup_clears_debt_and_continues_mining", maxTicks = 700)
    public void diagonalPhysicalPickupClearsDebtAndContinuesMining(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupDiagonalGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos pendingOre = fixture.start().east().north().above();
        BlockPos nextOre = fixture.start().north(3).above();
        var world = bot.level();
        world.setBlock(nextOre, Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.RAW_IRON));

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(fixture.start(), 2, Set.of(Blocks.IRON_ORE)));
        checkpoint.put("pending_pickup_pos", encode(pendingOre));
        checkpoint.put("pending_pickup_inventory", "1");
        checkpoint.put("pending_pickup_started_budget", "0");

        ItemEntity secondDrop = new ItemEntity(world,
                bot.getX(), bot.getY() + 0.1D, bot.getZ(),
                new ItemStack(Items.RAW_IRON));
        secondDrop.setDeltaMovement(Vec3.ZERO);
        secondDrop.setNoPickUpDelay();
        require(context, world.addFreshEntity(secondDrop),
                "failed to spawn the physical second raw-iron drop");

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        int pickupBaseline = bot.getStats().getValue(
                Stats.ITEM_PICKED_UP.get(Items.RAW_IRON));
        OreDigTask task = new OreDigTask(Set.of(Blocks.IRON_ORE), 2, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_pickup_diagonal"));
        AtomicBoolean sawDiagonalPickupPending = new AtomicBoolean();
        AtomicBoolean sawNextMiningAfterClear = new AtomicBoolean();

        context.failIfEver(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            Map<String, String> live = task.checkpoint();
            int rawIron = InventoryAction.countItem(bot, Items.RAW_IRON);
            if (rawIron >= 2 && encode(pendingOre).equals(live.get("pending_pickup_pos"))) {
                require(context, bot.blockPosition().distSqr(pendingOre) > 2.0D,
                        "fixture did not reproduce the diagonal pickup distance");
                require(context, world.getBlockState(nextOre).is(Blocks.IRON_ORE),
                        "next iron ore opened before the prior physical-drop debt cleared");
                sawDiagonalPickupPending.set(true);
            }
            if (encode(nextOre).equals(live.get("active_break_pos"))) {
                require(context, sawDiagonalPickupPending.get(),
                        "next iron mining started before the diagonal pickup was observed");
                require(context, !live.containsKey("pending_pickup_pos"),
                        "next iron mining started with stale physical-drop debt");
                sawNextMiningAfterClear.set(true);
            }
            if (task.state() == TaskState.COMPLETED) {
                require(context, sawDiagonalPickupPending.get(),
                        "second raw iron never exercised the squared-distance=3 boundary");
                require(context, sawNextMiningAfterClear.get(),
                        "task did not continue to the next ore after clearing pickup debt");
                require(context, rawIron == 3,
                        "expected baseline plus two physically collected raw iron, got " + rawIron);
                require(context, bot.getStats().getValue(
                                Stats.ITEM_PICKED_UP.get(Items.RAW_IRON)) > pickupBaseline,
                        "second raw iron did not enter through vanilla pickup statistics");
                AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), fixture.name());
                context.succeed();
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_consecutive_eye_height_diamonds_wait_for_each_physical_pickup", maxTicks = 900)
    public void consecutiveEyeHeightDiamondsWaitForEachPhysicalPickup(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupPairGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos first = fixture.start().north(4).above();
        BlockPos second = fixture.start().north(5).above();
        BlockPos firstWorkPose = first.south();
        BlockPos secondWorkPose = second.east();
        // Both eye-height ores need their own observed, supported cardinal stance.  The old
        // fixture placed floating ores and depended on the retired tunnel fallback after the
        // first physical pickup, so the second owner was never admissible.
        for (BlockPos pose : new BlockPos[]{firstWorkPose, secondWorkPose}) {
            bot.level().setBlock(pose.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            bot.level().setBlock(pose, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            bot.level().setBlock(pose.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        bot.level().setBlock(first.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        bot.level().setBlock(second.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        bot.level().setBlock(first, Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
        bot.level().setBlock(second, Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
        require(context, firstWorkPose.equals(OreDigTask.inspectApproachGoalFor(bot, bot.level(), first)),
                "first eye-height diamond lacks its observed cardinal work pose");

        assertStrictCapabilities(context, bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 2));
        int deathBaseline = deathCount(bot);
        int pickupBaseline = bot.getStats().getValue(
                Stats.ITEM_PICKED_UP.get(Items.DIAMOND));
        OreDigTask task = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 2);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_pickup_pair"));
        AtomicBoolean sawFirstPendingWithoutPickup = new AtomicBoolean();
        AtomicBoolean sawFirstPhysicalPickup = new AtomicBoolean();
        AtomicBoolean sawSecondMiningAfterFirstPickup = new AtomicBoolean();

        context.failIfEver(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            int diamonds = InventoryAction.countItem(bot, Items.DIAMOND);
            Map<String, String> checkpoint = task.checkpoint();

            if (bot.level().getBlockState(first).isAir() && diamonds == 0) {
                // ActionPack removes the block before OreDig's next task tick promotes
                // active_break_pos into pending_pickup_pos. Both are valid ledger states, but
                // neither may hand control to the second ore.
                boolean activeBreakObserved = encode(first).equals(checkpoint.get("active_break_pos"));
                boolean pendingPickupObserved = encode(first).equals(checkpoint.get("pending_pickup_pos"));
                require(context, activeBreakObserved || pendingPickupObserved,
                        "first broken ore disappeared from the target-drop ledger: " + checkpoint);
                if (pendingPickupObserved) {
                    sawFirstPendingWithoutPickup.set(true);
                }
                require(context, !bot.level().getBlockState(second).isAir(),
                        "second eye-height ore broke before the first drop entered inventory");
                require(context, !encode(second).equals(checkpoint.get("active_break_pos")),
                        "second eye-height ore started before the first drop entered inventory");
            }

            if (diamonds >= 1 && !sawFirstPhysicalPickup.get()) {
                // ItemEntity launch velocity is advanced before the GameTest callback and can move
                // farther than an arbitrary last-sampled-position radius. The vanilla PICKED_UP
                // stat is the authoritative collision-pickup receipt; strict capabilities above
                // already prove that forced pickup is unavailable.
                require(context, bot.getStats().getValue(
                                Stats.ITEM_PICKED_UP.get(Items.DIAMOND))
                                >= pickupBaseline + 1,
                        "first diamond did not enter through vanilla pickup statistics");
                sawFirstPhysicalPickup.set(true);
            }

            if (encode(second).equals(checkpoint.get("active_break_pos"))) {
                require(context, diamonds >= 1 && sawFirstPhysicalPickup.get(),
                        "second ore mining started before first physical pickup");
                sawSecondMiningAfterFirstPickup.set(true);
            }
            if (bot.level().getBlockState(second).isAir()) {
                require(context, diamonds >= 1 && sawFirstPhysicalPickup.get(),
                        "second ore broke before first physical pickup");
            }

            if (task.state() == TaskState.COMPLETED) {
                require(context, sawFirstPendingWithoutPickup.get(),
                        "fixture never exercised the pending-pickup pause between adjacent ores");
                require(context, sawFirstPhysicalPickup.get(),
                        "first diamond was not observed entering through physical recovery");
                require(context, sawSecondMiningAfterFirstPickup.get(),
                        "second ore never entered the accounted mining state");
                require(context, diamonds == 2,
                        "expected exactly two collision-picked diamonds, got " + diamonds);
                require(context, bot.getStats().getValue(
                                Stats.ITEM_PICKED_UP.get(Items.DIAMOND))
                                >= pickupBaseline + 2,
                        "second diamond did not enter through vanilla pickup statistics");
                finish(context, fixture);
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_foot_level_diamond_drop_is_recovered_by_walking_into_its_cell", maxTicks = 700)
    public void footLevelDiamondDropIsRecoveredByWalkingIntoItsCell(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupFootGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos ore = fixture.start().north(4);
        bot.level().setBlock(ore, Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
        InventoryAction.giveItem(bot, new ItemStack(
                Items.COBBLESTONE, MiningBudget.EMERGENCY_STONE_LIKE + 1));

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        OreDigTask task = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_pickup_foot"));
        AtomicBoolean sawPendingWithoutPickup = new AtomicBoolean();
        AtomicBoolean sawVanillaDrop = new AtomicBoolean();
        AtomicReference<Vec3> lastVanillaDropPosition = new AtomicReference<>();

        context.failIfEver(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            int diamonds = InventoryAction.countItem(bot, Items.DIAMOND);
            if (diamonds == 0) {
                nearestDiamondDropPosition(bot, ore).ifPresent(position -> {
                    sawVanillaDrop.set(true);
                    lastVanillaDropPosition.set(position);
                });
            }
            if (bot.level().getBlockState(ore).isAir() && diamonds == 0) {
                Map<String, String> checkpoint = task.checkpoint();
                boolean activeBreakObserved = encode(ore).equals(checkpoint.get("active_break_pos"));
                boolean pendingPickupObserved = encode(ore).equals(checkpoint.get("pending_pickup_pos"));
                require(context, activeBreakObserved || pendingPickupObserved,
                        "foot-level ore drop disappeared from the target-drop ledger: " + checkpoint);
                if (pendingPickupObserved) {
                    sawPendingWithoutPickup.set(true);
                }
            }

            if (task.state() == TaskState.COMPLETED) {
                require(context, sawPendingWithoutPickup.get(),
                        "foot-level fixture did not separate breaking from pickup");
                require(context, sawVanillaDrop.get(),
                        "foot-level diamond ItemEntity was never observable before pickup");
                require(context, diamonds == 1,
                        "expected exactly one foot-level diamond, got " + diamonds);
                Vec3 lastDrop = lastVanillaDropPosition.get();
                require(context, lastDrop != null
                                && bot.position().distanceToSqr(lastDrop) <= 4.0D,
                        "foot-level diamond entered inventory away from the last vanilla drop position");
                // Vanilla loot has launch velocity and can roll toward the miner, so requiring the
                // original block cell would reject a genuine collision pickup. The regression
                // boundary is that the bot moved and met the observed ItemEntity.
                require(context, !bot.blockPosition().equals(fixture.start()),
                        "bot did not physically leave the start cell to recover the drop");
                finish(context, fixture);
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_pending_pickup_checkpoint_resumes_before_any_new_mining", maxTicks = 800)
    public void pendingPickupCheckpointResumesBeforeAnyNewMining(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupRestartGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos ore = fixture.start().north(4).above();
        bot.level().setBlock(ore, Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        AtomicReference<OreDigTask> active = new AtomicReference<>(
                new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1));
        TaskManager.INSTANCE.assign(bot, active.get(),
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_pickup_restart"));
        AtomicBoolean restarted = new AtomicBoolean();
        AtomicBoolean restartedFromLastSeen = new AtomicBoolean();
        AtomicBoolean displacedDropAfterRestart = new AtomicBoolean();
        AtomicBoolean sawDropAfterRestart = new AtomicBoolean();
        AtomicReference<ItemEntity> displacedDrop = new AtomicReference<>();
        AtomicReference<Vec3> lastDropAfterRestart = new AtomicReference<>();

        context.failIfEver(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            OreDigTask task = active.get();
            failIfTerminalError(context, task);
            int diamonds = InventoryAction.countItem(bot, Items.DIAMOND);
            Map<String, String> checkpoint = task.checkpoint();

            if (!restarted.get()
                    && diamonds == 0
                    && encode(ore).equals(checkpoint.get("pending_pickup_pos"))) {
                require(context, bot.level().getBlockState(ore).isAir(),
                        "pickup checkpoint was written before the target ore broke");
                require(context, "0".equals(checkpoint.get("pending_pickup_inventory")),
                        "pickup checkpoint stored the wrong inventory baseline: " + checkpoint);
                int budgetBefore = Integer.parseInt(checkpoint.get("budget_used"));
                String pickupStartedBefore = checkpoint.get("pending_pickup_started_budget");

                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_pickup_restart_boundary");
                OreDigTask restored = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, checkpoint);
                TaskManager.INSTANCE.assign(bot, restored,
                        TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_pickup_restored"));
                Map<String, String> after = restored.checkpoint();
                require(context, encode(ore).equals(after.get("pending_pickup_pos"))
                                && "0".equals(after.get("pending_pickup_inventory"))
                                && pickupStartedBefore.equals(after.get("pending_pickup_started_budget"))
                                && Integer.parseInt(after.get("budget_used")) >= budgetBefore,
                        "pending pickup changed across checkpoint restore: before="
                                + checkpoint + " after=" + after);
                active.set(restored);
                restarted.set(true);
                return;
            }

            if (restarted.get() && diamonds == 0) {
                nearestDiamondDrop(bot, ore).ifPresent(drop -> {
                    if (displacedDropAfterRestart.compareAndSet(false, true)) {
                        // Make the vanilla launch-velocity edge deterministic: a restored task
                        // must follow the surviving entity after it leaves the mined block instead
                        // of waiting forever at the stale checkpoint coordinate.
                        Vec3 displaced = Vec3.atBottomCenterOf(ore.below().east(2)).add(0.0D, 0.1D, 0.0D);
                        drop.snapTo(
                                displaced.x, displaced.y, displaced.z, 0.0F, 0.0F);
                        drop.setDeltaMovement(Vec3.ZERO);
                        drop.setNeverPickUp();
                        displacedDrop.set(drop);
                    }
                    sawDropAfterRestart.set(true);
                    lastDropAfterRestart.set(drop.position());
                });
            }
            String lastSeen = checkpoint.get("pending_pickup_last_seen_pos");
            if (restarted.get() && displacedDropAfterRestart.get()
                    && !restartedFromLastSeen.get()
                    && lastSeen != null && !lastSeen.equals(encode(ore))) {
                String expectedLastSeen = encode(ore.below().east(2));
                require(context, expectedLastSeen.equals(lastSeen),
                        "moving drop published the wrong durable last-seen cell: " + checkpoint);
                TaskManager.INSTANCE.cancelIntentTasks(
                        bot, "gametest_pickup_last_seen_restart_boundary");
                OreDigTask restored = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, checkpoint);
                TaskManager.INSTANCE.assign(bot, restored,
                        TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                                "gametest_ore_pickup_last_seen_restored"));
                require(context, lastSeen.equals(
                                restored.checkpoint().get("pending_pickup_last_seen_pos")),
                        "moving drop last-seen cell changed across checkpoint restore: before="
                                + checkpoint + " after=" + restored.checkpoint());
                active.set(restored);
                restartedFromLastSeen.set(true);
                ItemEntity heldDrop = displacedDrop.get();
                require(context, heldDrop != null && heldDrop.isAlive(),
                        "moving drop vanished before its durable restart boundary");
                heldDrop.setNoPickUpDelay();
                return;
            }
            if (task.state() == TaskState.COMPLETED) {
                require(context, restarted.get(),
                        "ore completed without exercising pending-pickup restore");
                require(context, restartedFromLastSeen.get(),
                        "ore completed without restoring the moving-drop last-seen cell");
                require(context, sawDropAfterRestart.get(),
                        "restored task never pursued the surviving vanilla ItemEntity");
                require(context, displacedDropAfterRestart.get(),
                        "fixture never displaced the surviving ItemEntity from the mined block");
                require(context, diamonds == 1,
                        "restored pickup produced the wrong diamond count: " + diamonds);
                Vec3 lastDrop = lastDropAfterRestart.get();
                require(context, lastDrop != null
                                && bot.position().distanceToSqr(lastDrop) <= 4.0D,
                        "restored pickup completed away from its observed ItemEntity");
                finish(context, fixture);
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_unreachable_visible_last_seen_falls_back_to_reachable_break_cell", maxTicks = 30)
    public void unreachableVisibleLastSeenFallsBackToReachableBreakCell(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupFallbackGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        BlockPos start = fixture.start();
        BlockPos breakCell = start.north(3);
        BlockPos unreachableLedge = start.east(2).above(3);
        world.setBlock(
                unreachableLedge.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(unreachableLedge, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(unreachableLedge.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        ItemEntity drop = new ItemEntity(world,
                unreachableLedge.getX() + 0.5D,
                unreachableLedge.getY() + 0.1D,
                unreachableLedge.getZ() + 0.5D,
                new ItemStack(Items.COAL));
        drop.setDeltaMovement(Vec3.ZERO);
        drop.setNoGravity(true);
        drop.setOnGround(true);
        drop.setNeverPickUp();
        require(context, world.addFreshEntity(drop), "failed to spawn unreachable ledge drop");

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("pending_pickup_pos", encode(breakCell));
        checkpoint.put("pending_pickup_last_seen_pos", encode(unreachableLedge));
        checkpoint.put("pending_pickup_inventory", "0");
        checkpoint.put("pending_pickup_started_budget", "0");
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        task.start(bot);
        for (int i = 0; i < 5; i++) {
            task.tick(bot);
        }

        require(context, task.state() == TaskState.RUNNING,
                "fallback ledger ended unexpectedly: " + task.failureReason());
        require(context, breakCell.equals(bot.getActionPack().activePathGoal()),
                "failed high last-seen route suppressed the reachable break-cell fallback: goal="
                        + bot.getActionPack().activePathGoal());
        require(context, encode(breakCell).equals(
                        task.checkpoint().get("pending_pickup_pos"))
                        && encode(unreachableLedge).equals(
                        task.checkpoint().get("pending_pickup_last_seen_pos")),
                "fallback movement mutated its durable pickup ledger: " + task.checkpoint());
        require(context, world.getBlockState(unreachableLedge.below()).is(Blocks.STONE),
                "fallback manufactured a route to the unreachable ledge");
        task.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_elevated_drop_uses_lower_adjacent_stand_without_pillar", maxTicks = 400)
    public void elevatedDropUsesLowerAdjacentStandWithoutPillar(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupElevatedGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        BlockPos start = fixture.start();
        BlockPos dropCell = start.north(2).above();

        // The drop rests on a one-block pedestal under a low ceiling. Its own Y-level and four
        // neighbours are not valid player poses, but the lower ring is a real walkable pickup
        // surface. This is the exact geometry produced by eye-height iron on a descending stair.
        world.setBlock(dropCell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(dropCell.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        ItemEntity drop = new ItemEntity(world,
                dropCell.getX() + 0.5D, dropCell.getY() + 0.1D, dropCell.getZ() + 0.5D,
                new ItemStack(Items.DIAMOND));
        drop.setDeltaMovement(Vec3.ZERO);
        require(context, world.addFreshEntity(drop), "failed to spawn elevated diamond drop");

        Map<String, String> checkpoint = new LinkedHashMap<>(openCheckpoint(start, 1));
        checkpoint.put("pending_pickup_pos", encode(dropCell));
        checkpoint.put("pending_pickup_inventory", "0");
        checkpoint.put("pending_pickup_started_budget", "0");

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        OreDigTask task = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_pickup_elevated"));
        AtomicInteger maxY = new AtomicInteger(start.getY());

        context.failIfEver(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            maxY.accumulateAndGet(bot.getBlockY(), Math::max);
            if (task.state() == TaskState.COMPLETED) {
                require(context, InventoryAction.countItem(bot, Items.DIAMOND) == 1,
                        "elevated diamond was not physically recovered");
                require(context, maxY.get() <= start.getY() + 1,
                        "pickup climbed above the one-block vanilla route: max_y=" + maxY.get());
                require(context, bot.getBlockY() == start.getY(),
                        "pickup did not finish on the verified lower ring: "
                                + bot.blockPosition().toShortString());
                require(context, bot.blockPosition().distSqr(dropCell) <= 2.0D,
                        "pickup completed away from the elevated drop: "
                                + bot.blockPosition().toShortString());
                require(context, world.getBlockState(dropCell.below()).is(Blocks.STONE)
                                && world.getBlockState(dropCell.above()).is(Blocks.STONE),
                        "pickup modified its pedestal or ceiling to manufacture a route");
                require(context, !task.checkpoint().containsKey("pending_pickup_pos"),
                        "elevated pickup retained a stale durable ledger");
                finish(context, fixture);
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_airborne_drop_waits_for_landing_and_uses_natural_route_without_pillar", maxTicks = 600)
    public void airborneDropWaitsForLandingAndUsesNaturalRouteWithoutPillar(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupAirborneGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        BlockPos start = fixture.start();
        BlockPos landing = start.east(4).above(4);

        // Build a real ascending route.  The bot also carries filler blocks so the old generic
        // pickup path was able to pillar toward the transient airborne coordinate.
        for (int step = 1; step <= 4; step++) {
            BlockPos foot = start.east(step).above(step);
            world.setBlock(foot.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(foot, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(foot.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 8));
        int fillerBaseline = InventoryAction.countItem(bot, Items.COBBLESTONE);

        ItemEntity drop = new ItemEntity(world,
                landing.getX() + 0.5D, landing.getY() + 3.1D, landing.getZ() + 0.5D,
                new ItemStack(Items.DIAMOND));
        drop.setDeltaMovement(Vec3.ZERO);
        drop.setNoGravity(true);
        require(context, world.addFreshEntity(drop), "failed to spawn airborne diamond drop");

        Map<String, String> checkpoint = new LinkedHashMap<>(openCheckpoint(start, 1));
        checkpoint.put("pending_pickup_pos", encode(landing));
        checkpoint.put("pending_pickup_inventory", "0");
        checkpoint.put("pending_pickup_started_budget", "0");

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        OreDigTask task = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_pickup_airborne"));
        AtomicInteger ticks = new AtomicInteger();
        AtomicInteger maxY = new AtomicInteger(start.getY());
        AtomicBoolean released = new AtomicBoolean();

        context.failIfEver(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            int tick = ticks.incrementAndGet();
            maxY.accumulateAndGet(bot.getBlockY(), Math::max);
            if (tick < 30) {
                require(context, bot.blockPosition().equals(start),
                        "miner chased an unsupported airborne coordinate: "
                                + bot.blockPosition().toShortString());
                require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == fillerBaseline,
                        "airborne pickup consumed pillar material before landing");
            } else if (released.compareAndSet(false, true)) {
                // Do not reuse the synthetic no-gravity entity. Under accelerated parallel
                // GameTests it can retain stale interpolation/section state after gravity is
                // restored and remain unsupported for the whole production recovery budget.
                // Replace it with a fresh supported entity at the factual landing. Entity gravity
                // itself is not the policy under test, and accelerated GameTests do not provide a
                // stable interpolation contract. The bot must still wait for this release and then
                // traverse the complete natural staircase for vanilla collision pickup.
                drop.discard();
                ItemEntity settled = new ItemEntity(world,
                        landing.getX() + 0.5D,
                        landing.getY() + 0.1D,
                        landing.getZ() + 0.5D,
                        new ItemStack(Items.DIAMOND));
                settled.setDeltaMovement(Vec3.ZERO);
                settled.setOnGround(true);
                settled.setNoPickUpDelay();
                require(context, world.addFreshEntity(settled),
                        "failed to spawn released supported diamond drop");
            }

            if (task.state() == TaskState.COMPLETED) {
                require(context, released.get(), "pickup completed before the drop was released");
                require(context, InventoryAction.countItem(bot, Items.DIAMOND) == 1,
                        "expected one physically recovered airborne diamond");
                require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == fillerBaseline,
                        "pickup route consumed filler blocks");
                require(context, maxY.get() <= landing.getY(),
                        "pickup route climbed above the settled drop: max_y=" + maxY.get());
                require(context, !task.checkpoint().containsKey("pending_pickup_pos"),
                        "completed pickup retained its durable pending ledger");
                finish(context, fixture);
            }
        });
    }





    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_legacy_open_checkpoint_without_delivered_ledger_fails_closed", maxTicks = 20)
    public void legacyOpenCheckpointWithoutDeliveredLedgerFailsClosed(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreTorchSchemaMigrationGT");
        Map<String, String> legacyRunning = new LinkedHashMap<>(
                openCheckpoint(fixture.start(), 8));
        legacyRunning.put("task_schema", "1");
        legacyRunning.remove("delivered");
        legacyRunning.remove("torch_limit");
        legacyRunning.remove("torch_placements");
        legacyRunning.remove("resource_epoch");
        legacyRunning.remove("rare_mission_target");
        legacyRunning.remove("inventory_service_used");

        OreDigTask first = new OreDigTask(
                Set.of(Blocks.DIAMOND_ORE), 8, 8, legacyRunning);
        first.start(fixture.bot());
        require(context, first.state() == TaskState.FAILED
                        && "ore_dig_invalid_checkpoint".equals(first.failureReason())
                        && first.checkpoint().isEmpty(),
                "schema1 open batch invented delivered=0: " + first.checkpoint());

        Map<String, String> schemaTwo = new LinkedHashMap<>(openCheckpoint(
                fixture.start(), 8));
        schemaTwo.put("task_schema", "2");
        schemaTwo.remove("delivered");
        schemaTwo.remove("rare_mission_target");
        schemaTwo.remove("inventory_service_used");
        schemaTwo.put("torch_placements", "17");
        schemaTwo.put("resource_epoch", "1");
        schemaTwo.put("budget_used", "123");
        schemaTwo.put("last_progress_budget", "100");
        OreDigTask schemaTwoRestored = new OreDigTask(
                Set.of(Blocks.DIAMOND_ORE), 8, 8, schemaTwo);
        schemaTwoRestored.start(fixture.bot());
        require(context, schemaTwoRestored.state() == TaskState.FAILED
                        && "ore_dig_invalid_checkpoint".equals(
                        schemaTwoRestored.failureReason()),
                "schema2 open batch invented delivered=0");

        Map<String, String> schemaThree = new LinkedHashMap<>(openCheckpoint(
                fixture.start(), 8));
        schemaThree.put("task_schema", "3");
        schemaThree.remove("delivered");
        require(context, OreDigTask.inspectCheckpoint(schemaThree, 8).isEmpty(),
                "schema3 open batch invented delivered=0");

        Map<String, String> legacyCommitted = new LinkedHashMap<>(legacyRunning);
        legacyCommitted.put("batch_open", "false");
        legacyCommitted.put("budget_used", "0");
        legacyCommitted.put("last_progress_budget", "0");
        OreDigTask.RestoreMetadata committed = OreDigTask.inspectCheckpoint(
                        legacyCommitted, 8)
                .orElseThrow();
        require(context, committed.torchPlacements() == 0 && committed.resourceEpoch() == 0,
                "schema1 committed cursor did not migrate to a clean successor epoch");
        finish(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_remembered_high_work_pose_checkpoint_rejects_forged_entries", maxTicks = 20)
    public void rememberedHighWorkPoseCheckpointRejectsForgedEntries(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreRememberedPoseCodecGT");
        BlockPos face = fixture.start();
        BlockPos ore = face.above(3);
        BlockPos pose = face.east().above(2);
        Map<String, String> valid = new LinkedHashMap<>(
                openCheckpoint(face, 1, Set.of(Blocks.COAL_ORE)));
        valid.put("remembered_high_work_poses", encode(ore) + "@" + encode(pose));
        require(context, OreDigTask.inspectCheckpoint(valid).isPresent(),
                "exact remembered high work pose failed codec validation");

        Map<String, String> duplicate = new LinkedHashMap<>(valid);
        duplicate.put("remembered_high_work_poses",
                encode(ore) + "@" + encode(pose) + ";"
                        + encode(ore) + "@" + encode(ore.below().west()));
        require(context, OreDigTask.inspectCheckpoint(duplicate).isEmpty(),
                "checkpoint accepted two work poses for one finite ore owner");

        Map<String, String> wrongGeometry = new LinkedHashMap<>(valid);
        wrongGeometry.put("remembered_high_work_poses",
                encode(ore) + "@" + encode(ore.below()));
        require(context, OreDigTask.inspectCheckpoint(wrongGeometry).isEmpty(),
                "checkpoint accepted an under-ore pose instead of a real side pose");

        BlockPos farOre = face.east(49).above(3);
        Map<String, String> far = new LinkedHashMap<>(valid);
        far.put("remembered_high_work_poses",
                encode(farOre) + "@" + encode(farOre.below().east()));
        require(context, OreDigTask.inspectCheckpoint(far).isEmpty(),
                "checkpoint accepted a remembered pose outside its bounded work region");

        Map<String, String> closed = new LinkedHashMap<>(valid);
        closed.put("batch_open", "false");
        require(context, OreDigTask.inspectCheckpoint(closed).isEmpty(),
                "committed checkpoint retained an unfinished observed-pose ledger");

        StringBuilder oversized = new StringBuilder();
        for (int index = 0; index <= 64; index++) {
            BlockPos entryOre = face.offset(index % 9 - 4, 3, index / 9 - 4);
            if (oversized.length() > 0) {
                oversized.append(';');
            }
            oversized.append(encode(entryOre))
                    .append('@')
                    .append(encode(entryOre.below().east()));
        }
        Map<String, String> tooMany = new LinkedHashMap<>(valid);
        tooMany.put("remembered_high_work_poses", oversized.toString());
        require(context, OreDigTask.inspectCheckpoint(tooMany).isEmpty(),
                "checkpoint accepted more than VEIN_CAP remembered poses");
        finish(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_long_rare_tail_retains_mission_identity_across_restart_and_service_debits", maxTicks = 20)
    public void longRareTailRetainsMissionIdentityAcrossRestartAndServiceDebits(
            GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreLongRareTailCheckpointGT");
        InventoryAction.giveItem(fixture.bot(), new ItemStack(Items.STONE_PICKAXE));
        Map<String, String> tail = new LinkedHashMap<>(openCheckpoint(
                fixture.start(), 1, Set.of(Blocks.DIAMOND_ORE), 64));
        tail.put("direction", "2");
        tail.put("leg", "3");
        tail.put("steps_left", "17");
        tail.put("batches", "7");
        tail.put("boundary_reroute_origin", encode(fixture.start()));
        tail.put("torch_placements", "40");
        tail.put("budget_used", "123");
        tail.put("last_progress_budget", "100");
        tail.put("pending_pickup_pos", encode(fixture.start()));
        tail.put("pending_pickup_last_seen_pos", encode(fixture.start().east(2).below()));
        tail.put("pending_pickup_inventory", "5");
        tail.put("pending_pickup_started_budget", "90");
        tail.put("pickup_gain_budget", "110");
        String rememberedTailPose = encode(fixture.start().above(3))
                + "@" + encode(fixture.start().east().above(2));
        tail.put("remembered_high_work_poses", rememberedTailPose);

        OreDigTask.RestoreMetadata metadata = OreDigTask.inspectCheckpoint(tail, 64)
                .orElseThrow();
        require(context, metadata.targetCount() == 1 && metadata.rareMissionTarget() == 64,
                "long rare tail lost immutable mission identity: " + metadata);

        OreDigTask restarted = new OreDigTask(
                Set.of(Blocks.DIAMOND_ORE), 1, 64, tail);
        restarted.start(fixture.bot());
        Map<String, String> afterRestart = restarted.checkpoint();
        require(context, "1".equals(afterRestart.get("target_count"))
                        && "64".equals(afterRestart.get("rare_mission_target")),
                "ordinary restart converted a long rare tail into a small goal: " + afterRestart);
        assertCheckpointFieldsEqual(context, tail, afterRestart,
                "direction", "leg", "steps_left", "batches", "budget_used",
                "last_progress_budget", "boundary_reroute_origin",
                "pending_pickup_pos", "pending_pickup_inventory",
                "pending_pickup_last_seen_pos", "pending_pickup_started_budget",
                "pickup_gain_budget", "remembered_high_work_poses");

        Map<String, String> epochOne = OreDigTask.advanceResourceEpoch(afterRestart)
                .orElseThrow();
        require(context, "0".equals(epochOne.get("torch_placements"))
                        && "1".equals(epochOne.get("resource_epoch"))
                        && "64".equals(epochOne.get("rare_mission_target")),
                "tail resource retry did not advance exactly one durable epoch: " + epochOne);
        assertCheckpointFieldsEqual(context, afterRestart, epochOne,
                "target_count", "direction", "leg", "steps_left", "batches", "budget_used",
                "last_progress_budget", "boundary_reroute_origin",
                "pending_pickup_pos", "pending_pickup_inventory",
                "pending_pickup_last_seen_pos", "pending_pickup_started_budget",
                "pickup_gain_budget", "remembered_high_work_poses");

        Map<String, String> serviced = OreDigTask.debitInventoryService(epochOne)
                .orElseThrow();
        require(context, "true".equals(serviced.get("inventory_service_used"))
                        && OreDigTask.debitInventoryService(serviced).isEmpty(),
                "long rare tail accepted more than one inventory service debit");
        assertCheckpointFieldsEqual(context, epochOne, serviced,
                "target_count", "rare_mission_target", "torch_placements", "resource_epoch",
                "direction", "leg", "steps_left", "batches", "budget_used",
                "last_progress_budget", "boundary_reroute_origin",
                "pending_pickup_pos", "pending_pickup_inventory",
                "pending_pickup_last_seen_pos", "pending_pickup_started_budget",
                "pickup_gain_budget", "remembered_high_work_poses");

        Map<String, String> ordinaryChannel = new LinkedHashMap<>(openCheckpoint(
                fixture.start(), 1, Set.of(Blocks.COAL_ORE), 0));
        ordinaryChannel.put("direction", "1");
        ordinaryChannel.put("steps_left", "23");
        ordinaryChannel.put("boundary_reroute_origin", encode(fixture.start()));
        ordinaryChannel.put("remembered_high_work_poses", rememberedTailPose);
        Map<String, String> channelDebited = OreDigTask
                .debitChannelToolResupply(ordinaryChannel)
                .orElseThrow();
        require(context, "true".equals(channelDebited.get("inventory_service_used"))
                        && encode(fixture.start()).equals(
                        channelDebited.get("boundary_reroute_origin"))
                        && OreDigTask.inspectCheckpoint(channelDebited, 0).isPresent()
                        && OreDigTask.debitChannelToolResupply(channelDebited).isEmpty(),
                "ordinary channel debit lost its reroute marker or accepted a second debit: "
                        + channelDebited);
        assertCheckpointFieldsEqual(context, ordinaryChannel, channelDebited,
                "target_count", "rare_mission_target", "torch_placements", "resource_epoch",
                "direction", "leg", "steps_left", "batches", "budget_used",
                "last_progress_budget", "boundary_reroute_origin",
                "remembered_high_work_poses");

        Map<String, String> smallRare = openCheckpoint(
                fixture.start(), 7, Set.of(Blocks.DIAMOND_ORE), 0);
        require(context, OreDigTask.advanceResourceEpoch(smallRare).isEmpty()
                        && OreDigTask.debitInventoryService(smallRare).isEmpty(),
                "small rare goal was misclassified as a long expedition batch");
        Map<String, String> capacityDebited = OreDigTask
                .debitCapacityHandoff(smallRare).orElseThrow();
        require(context, "true".equals(capacityDebited.get("inventory_service_used"))
                        && OreDigTask.debitCapacityHandoff(capacityDebited).isEmpty()
                        && OreDigTask.debitChannelToolResupply(capacityDebited).isEmpty(),
                "small rare capacity hand-off did not seal its first auxiliary debit");
        assertCheckpointFieldsEqual(context, smallRare, capacityDebited,
                "target_count", "rare_mission_target", "torch_placements", "resource_epoch",
                "origin", "face", "direction", "leg", "steps_left", "leg_length", "batches",
                "budget_used", "last_progress_budget", "ore_fingerprint");
        Map<String, String> progressedCapacity = new LinkedHashMap<>(capacityDebited);
        progressedCapacity.put("delivered", "1");
        Map<String, String> repeatedCapacity = OreDigTask
                .debitCapacityHandoff(progressedCapacity, 0).orElseThrow();
        require(context, repeatedCapacity.equals(progressedCapacity)
                        && OreDigTask.debitCapacityHandoff(progressedCapacity, 1).isEmpty()
                        && OreDigTask.debitCapacityHandoff(progressedCapacity, 2).isEmpty(),
                "capacity retry did not require a strict delivered-watermark advance");
        Map<String, String> advancedFaceCapacity = new LinkedHashMap<>(capacityDebited);
        advancedFaceCapacity.put("face", encode(fixture.start().east()));
        Map<String, String> faceRepeatedCapacity = OreDigTask.debitCapacityHandoff(
                advancedFaceCapacity, 0, fixture.start()).orElseThrow();
        require(context, faceRepeatedCapacity.equals(advancedFaceCapacity)
                        && OreDigTask.debitCapacityHandoff(
                        capacityDebited, 0, fixture.start()).isEmpty()
                        && OreDigTask.debitCapacityHandoff(
                        advancedFaceCapacity, 0, null).isEmpty(),
                "capacity retry did not require strict delivered or work-face progress");
        Map<String, String> invalidSmallMission = new LinkedHashMap<>(smallRare);
        invalidSmallMission.put("rare_mission_target", "7");
        require(context, OreDigTask.inspectCheckpoint(invalidSmallMission).isEmpty(),
                "schema3 accepted a non-zero rare mission target below eight");

        restarted.cancel(fixture.bot(), "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_rare_full_inventory_fails_without_creating_open_rear_drops", maxTicks = 20)
    public void rareFullInventoryFailsWithoutCreatingOpenRearDrops(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreInventoryServiceRequiredGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 40));
        InventoryAction.giveItem(bot, new ItemStack(Items.TORCH, 64));
        while (!HarvestCore.isInventoryFull(bot)) {
            InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        }
        int dirtBefore = InventoryAction.countItem(bot, Items.DIRT);
        int cobbleBefore = InventoryAction.countItem(bot, Items.COBBLESTONE);
        int openDropsBefore = world.getEntitiesOfClass(
                ItemEntity.class, new AABB(bot.blockPosition()).inflate(8.0D), entity -> true).size();
        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(fixture.start(), 8));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "48");
        OreDigTask task = new OreDigTask(
                Set.of(Blocks.DIAMOND_ORE), 8, 8, checkpoint);
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.FAILED
                        && "ore_dig_inventory_service_required".equals(task.failureReason()),
                "rare full inventory did not report its exact service boundary: "
                        + task.state() + ":" + task.failureReason());
        require(context, InventoryAction.countItem(bot, Items.DIRT) == dirtBefore
                        && InventoryAction.countItem(bot, Items.COBBLESTONE) == cobbleBefore,
                "OreDig disposed inventory before the sealed service task could run");
        int openDropsAfter = world.getEntitiesOfClass(
                ItemEntity.class, new AABB(bot.blockPosition()).inflate(8.0D), entity -> true).size();
        require(context, openDropsAfter == openDropsBefore,
                "OreDig created an open ItemEntity disposal path: before="
                        + openDropsBefore + ", after=" + openDropsAfter);
        require(context, "false".equals(task.checkpoint().get("inventory_service_used")),
                "OreDig debited inventory service before GoalExecutor scheduled it");
        finish(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_ordinary_full_inventory_fails_without_mutating_inventory_or_creating_drops", maxTicks = 20)
    public void ordinaryFullInventoryFailsWithoutMutatingInventoryOrCreatingDrops(
            GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OrdinaryInventoryServiceRequiredGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 40));
        while (!HarvestCore.isInventoryFull(bot)) {
            InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        }
        int dirtBefore = InventoryAction.countItem(bot, Items.DIRT);
        int cobbleBefore = InventoryAction.countItem(bot, Items.COBBLESTONE);
        int dropsBefore = world.getEntitiesOfClass(
                ItemEntity.class, new AABB(bot.blockPosition()).inflate(8.0D), entity -> true).size();
        Map<String, String> checkpoint = new LinkedHashMap<>(openCheckpoint(
                fixture.start(), 1, Set.of(Blocks.IRON_ORE), 0));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "48");
        OreDigTask task = new OreDigTask(
                Set.of(Blocks.IRON_ORE), 1, 0, checkpoint);
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.FAILED
                        && "ore_dig_inventory_service_required".equals(task.failureReason()),
                "ordinary full inventory did not report the typed capacity boundary: "
                        + task.state() + ":" + task.failureReason());
        require(context, InventoryAction.countItem(bot, Items.DIRT) == dirtBefore
                        && InventoryAction.countItem(bot, Items.COBBLESTONE) == cobbleBefore,
                "ordinary OreDig mutated inventory before sealed service admission");
        int dropsAfter = world.getEntitiesOfClass(
                ItemEntity.class, new AABB(bot.blockPosition()).inflate(8.0D), entity -> true).size();
        require(context, dropsAfter == dropsBefore,
                "ordinary OreDig created an open ItemEntity disposal path: before="
                        + dropsBefore + ", after=" + dropsAfter);
        require(context, "false".equals(task.checkpoint().get("inventory_service_used")),
                "ordinary OreDig debited capacity service before GoalExecutor scheduled it");
        finish(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_malformed_checkpoint_fails_closed", maxTicks = 20)
    public void malformedCheckpointFailsClosed(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreMalformedCheckpointGT");
        Map<String, String> malformed = new LinkedHashMap<>(openCheckpoint(fixture.start(), 1));
        malformed.put("task_schema", "999");

        OreDigTask task = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, malformed);
        task.start(fixture.bot());

        require(context, task.state() == TaskState.FAILED,
                "malformed OreDig checkpoint restarted as fresh work");
        require(context, "ore_dig_invalid_checkpoint".equals(task.failureReason()),
                "unexpected malformed-checkpoint reason: " + task.failureReason());
        require(context, task.checkpoint().isEmpty(),
                "invalid restore published an invented successor checkpoint");

        Map<String, String> wrongStep = new LinkedHashMap<>(openCheckpoint(
                fixture.start(), 8, Set.of(Blocks.DIAMOND_ORE), 64));
        wrongStep.put("delivered", "4");
        OreDigTask wrongCount = new OreDigTask(
                Set.of(Blocks.DIAMOND_ORE), 3, 64, wrongStep);
        wrongCount.start(fixture.bot());
        require(context, wrongCount.state() == TaskState.FAILED
                        && "ore_dig_invalid_checkpoint".equals(wrongCount.failureReason()),
                "open batch accepted a step count that was neither original 8 nor remainder 4");
        finish(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_restart_cannot_reset_hard_budget", maxTicks = 20)
    public void restartCannotResetHardBudget(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreHardBudgetGT");
        Map<String, String> exhausted = new LinkedHashMap<>(openCheckpoint(fixture.start(), 1));
        exhausted.put("budget_used", "24000");
        exhausted.put("last_progress_budget", "24000");

        OreDigTask restored = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, exhausted);
        restored.start(fixture.bot());
        require(context, "24000".equals(restored.checkpoint().get("budget_used")),
                "restart reset the active OreDig hard budget");
        restored.tick(fixture.bot());

        require(context, restored.state() == TaskState.FAILED,
                "restored OreDig received a fresh hard-timeout window");
        require(context, restored.failureReason().startsWith("ore_dig_timeout"),
                "unexpected restored hard-budget failure: " + restored.failureReason());
        Map<String, String> terminal = restored.checkpoint();
        require(context, "true".equals(terminal.get("batch_open"))
                        && "24000".equals(terminal.get("budget_used")),
                "terminal hard timeout discarded its exhausted durable budget: " + terminal);
        finish(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_restart_cannot_reset_no_progress_budget", maxTicks = 20)
    public void restartCannotResetNoProgressBudget(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreNoProgressBudgetGT");
        Map<String, String> stalled = new LinkedHashMap<>(openCheckpoint(fixture.start(), 1));
        stalled.put("budget_used", "200");
        stalled.put("last_progress_budget", "0");

        OreDigTask restored = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, stalled);
        restored.start(fixture.bot());
        require(context, "200".equals(restored.checkpoint().get("budget_used")),
                "restart reset the active OreDig no-progress budget");
        restored.tick(fixture.bot());

        require(context, restored.state() == TaskState.FAILED,
                "restored OreDig received a fresh no-progress window");
        require(context, restored.failureReason().startsWith("ore_dig_no_progress"),
                "unexpected restored no-progress failure: " + restored.failureReason());
        finish(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_partial_delivery_rebases_only_the_transient_stall_window", maxTicks = 280)
    public void partialDeliveryRebasesOnlyTheTransientStallWindow(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OrePartialRetryBudgetGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        BlockPos channel = fixture.start().north();
        world.setBlock(channel, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        Map<String, String> nearlyExhausted = new LinkedHashMap<>(
                openCheckpoint(fixture.start(), 2, Set.of(Blocks.DIAMOND_ORE)));
        // The observation-bounded search exhausts after roughly 187 ticks. Start close enough
        // to the hard cap that its typed terminal outcome still leaves the saved mission within
        // the final timeout window, where the restart assertion proves only the transient stall
        // clock was rebased.
        nearlyExhausted.put("budget_used", "23810");
        nearlyExhausted.put("last_progress_budget", "23810");
        nearlyExhausted.put("direction", "0");
        nearlyExhausted.put("steps_left", "12");

        AtomicReference<OreDigTask> active = new AtomicReference<>(
                new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 2, nearlyExhausted));
        TaskManager.INSTANCE.assign(bot, active.get(),
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_partial_retry_budget"));
        // Assignment starts the task and captures its inventory baseline. This item therefore
        // represents a real delivery made by the running attempt, not pre-existing inventory.
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND));
        int deathBaseline = deathCount(bot);
        AtomicBoolean restored = new AtomicBoolean();
        AtomicInteger restoredTicks = new AtomicInteger();

        context.failIfEver(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            OreDigTask task = active.get();
            if (!restored.get()) {
                if (task.state() == TaskState.CANCELLED) {
                    context.fail(Component.nullToEmpty("partial delivery task was cancelled"));
                }
                if (task.state() != TaskState.FAILED) {
                    return;
                }
                require(context, task.failureReason().startsWith("ore_dig_no_progress")
                                || task.failureReason().startsWith(
                                "no_observed_ore_after_exploration"),
                        "fixture did not reach a bounded terminal search/stall boundary: "
                                + task.failureReason());
                Map<String, String> successor = task.checkpoint();
                int budget = Integer.parseInt(successor.get("budget_used"));
                require(context, budget > 23990 && budget < 24000,
                        "fixture did not retain the nearly exhausted hard budget: " + successor);
                require(context, successor.get("last_progress_budget").equals(String.valueOf(budget)),
                        "partial delivery did not publish a fresh transient stall boundary: " + successor);
                require(context, "true".equals(successor.get("batch_open"))
                                && "2".equals(successor.get("target_count")),
                        "partial delivery committed or resized the original batch: " + successor);

                world.setBlock(channel, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                OreDigTask retry = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, successor);
                TaskManager.INSTANCE.assign(bot, retry,
                        TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                                "gametest_ore_partial_retry_budget_restored"));
                active.set(retry);
                restored.set(true);
                return;
            }

            int ticks = restoredTicks.incrementAndGet();
            if (ticks == 1) {
                require(context, task.state() != TaskState.FAILED
                                || !task.failureReason().startsWith("ore_dig_no_progress"),
                        "successor attempt inherited an already-expired transient stall window");
            }
            if (task.state() == TaskState.FAILED) {
                require(context, task.failureReason().startsWith("ore_dig_timeout"),
                        "retry escaped the original hard budget or failed for the wrong reason: "
                                + task.failureReason());
                require(context, ticks <= 10,
                        "retry received a fresh hard-timeout budget: ticks=" + ticks);
                finish(context, fixture);
            } else if (task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("restored partial delivery task was cancelled"));
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_rare_partial_delivery_restart_only_mines_the_logical_batch_remainder", maxTicks = 280)
    public void rarePartialDeliveryRestartOnlyMinesTheLogicalBatchRemainder(
            GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreRarePartialDeliveredGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        BlockPos channel = fixture.start().north();
        world.setBlock(channel, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);

        Map<String, String> open = new LinkedHashMap<>(openCheckpoint(
                fixture.start(), 8, Set.of(Blocks.DIAMOND_ORE), 64));
        open.put("direction", "0");
        open.put("steps_left", "12");
        AtomicReference<OreDigTask> active = new AtomicReference<>(
                new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 8, 64, open));
        TaskManager.INSTANCE.assign(bot, active.get(),
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_rare_partial_delivery"));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND, 4));
        AtomicBoolean restarted = new AtomicBoolean();

        context.failIfEver(() -> {
            OreDigTask task = active.get();
            if (!restarted.get()) {
                if (task.state() == TaskState.CANCELLED) {
                    context.fail(Component.nullToEmpty("partial rare batch was cancelled"));
                }
                if (task.state() != TaskState.FAILED) {
                    return;
                }
                require(context, task.failureReason().startsWith("ore_dig_no_progress")
                                || task.failureReason().startsWith(
                                "no_observed_ore_after_exploration"),
                        "partial rare fixture failed outside the bounded search/stall outcomes: "
                                + task.failureReason());
                Map<String, String> successor = task.checkpoint();
                require(context, "4".equals(successor.get("delivered"))
                                && "8".equals(successor.get("target_count"))
                                && "true".equals(successor.get("batch_open")),
                        "partial rare failure lost its factual delivered ledger: " + successor);
                OreDigTask.RestoreMetadata metadata = OreDigTask.inspectCheckpoint(successor, 64)
                        .orElseThrow();
                require(context, metadata.remainingCount() == 4
                                && metadata.acceptsStepTarget(8)
                                && metadata.acceptsStepTarget(4),
                        "open batch did not expose replay/replan target identity: " + metadata);

                world.setBlock(channel, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                // A process restart replays the persisted GoalStep(8). OreDig must still request
                // only the four items not already named by delivered=4.
                OreDigTask replay = new OreDigTask(
                        Set.of(Blocks.DIAMOND_ORE), 8, 64, successor);
                TaskManager.INSTANCE.assign(bot, replay,
                        TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                                "gametest_rare_partial_delivery_restarted"));
                active.set(replay);
                InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND, 4));
                restarted.set(true);
                return;
            }

            if (task.state() == TaskState.COMPLETED) {
                require(context, InventoryAction.countItem(bot, Items.DIAMOND) == 8,
                        "restart mined more than the four-item remainder");
                Map<String, String> committed = task.checkpoint();
                require(context, "false".equals(committed.get("batch_open"))
                                && "0".equals(committed.get("delivered"))
                                && "1".equals(committed.get("batches")),
                        "remainder completion did not close exactly one logical batch: "
                                + committed);
                finish(context, fixture);
            } else if (task.state() == TaskState.FAILED
                    || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("rare remainder replay ended unexpectedly: "
                        + task.state() + ":" + task.failureReason()));
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_restart_at_fully_delivered_open_batch_settles_debt_without_breaking_another_ore", maxTicks = 30)
    public void restartAtFullyDeliveredOpenBatchSettlesDebtWithoutBreakingAnotherOre(
            GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreRareDeliveredGraceGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos untouched = fixture.start().north();
        bot.level().setBlock(
                untouched, Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND, 8));
        InventoryAction.removeItems(bot, Items.IRON_PICKAXE, 1);

        Map<String, String> fullyDelivered = new LinkedHashMap<>(openCheckpoint(
                fixture.start(), 8, Set.of(Blocks.DIAMOND_ORE), 64));
        fullyDelivered.put("delivered", "8");
        fullyDelivered.put("budget_used", "24000");
        fullyDelivered.put("last_progress_budget", "24000");
        fullyDelivered.put("active_break_pos", encode(untouched));
        fullyDelivered.put("active_break_inventory", "8");
        OreDigTask restored = new OreDigTask(
                Set.of(Blocks.DIAMOND_ORE), 8, 64, fullyDelivered);
        restored.start(bot);
        restored.tick(bot);

        require(context, restored.state() == TaskState.COMPLETED,
                "fully delivered restart required a tool or timed out: "
                        + restored.failureReason());
        require(context, bot.level().getBlockState(untouched).is(Blocks.DIAMOND_ORE)
                        && InventoryAction.countItem(bot, Items.DIAMOND) == 8,
                "fully delivered restart broke a ninth ore");
        Map<String, String> committed = restored.checkpoint();
        require(context, "false".equals(committed.get("batch_open"))
                        && "0".equals(committed.get("delivered"))
                        && "1".equals(committed.get("batches")),
                "fully delivered grace checkpoint did not commit exactly once: " + committed);
        finish(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_completed_batch_publishes_zero_budget_successor", maxTicks = 20)
    public void completedBatchPublishesZeroBudgetSuccessor(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreCommittedBudgetGT");
        OreDigTask task = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1);
        task.start(fixture.bot());
        InventoryAction.giveItem(fixture.bot(), new ItemStack(Items.DIAMOND));
        task.tick(fixture.bot());

        require(context, task.state() == TaskState.COMPLETED,
                "inventory-satisfied batch did not commit");
        Map<String, String> successor = task.checkpoint();
        require(context, "false".equals(successor.get("batch_open")),
                "completed batch remained open: " + successor);
        require(context, "0".equals(successor.get("budget_used"))
                        && "0".equals(successor.get("last_progress_budget")),
                "completed batch leaked its budget into the next batch: " + successor);
        require(context, "1".equals(successor.get("batches"))
                        && !successor.containsKey("pending_pickup_pos")
                        && !successor.containsKey("active_break_pos"),
                "completed successor retained active-batch debt: " + successor);
        finish(context, fixture);
    }





        @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_strip_physically_retreats_when_gravity_reoccupies_its_head", maxTicks = 100)
    public void stripPhysicallyRetreatsWhenGravityReoccupiesItsHead(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreStripGravelGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos buried = start.north();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.EMERALD_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "48");

        OreDigTask task = new OreDigTask(Set.of(Blocks.EMERALD_ORE), 1, checkpoint);
        task.start(bot);
        task.tick(bot); // publish start as the previous factual branch face
        bot.getActionPack().stopAll();
        BotFixtureMoves.place(bot, buried);
        context.getLevel().setBlock(
                buried.above(), Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();
        require(context, !context.getLevel().getBlockState(buried.above())
                        .getCollisionShape(context.getLevel(), buried.above()).isEmpty(),
                "gravity fixture did not reoccupy the bot's head");
        float healthBefore = bot.getHealth();
        TeleportAudit.reset(bot);

        task.tick(bot); // starts the walked retreat out of the occupied cell
        require(context, task.state() == TaskState.RUNNING && !bot.getActionPack().stepIdle(),
                "recoverable strip collapse did not start a walked retreat: "
                        + task.state() + ":" + task.failureReason());
        // The retreat is a walk (no teleport): the face is published when a later tick has verified the landing.
        tickUntil(context, task, bot,
                () -> encode(start).equals(task.checkpoint().get("boundary_reroute_origin")),
                60, "strip collapse retreat", () -> {
                    require(context, task.state() == TaskState.RUNNING,
                            "recoverable strip collapse ended OreDig: "
                                    + task.state() + ":" + task.failureReason());
                    require(context, bot.blockPosition().equals(start),
                            "OreDig did not physically retreat to its previous face: "
                                    + buried.toShortString() + " -> " + bot.blockPosition().toShortString());
                    // The serialized gravity-retreat measurement was zero loss. Keep one vanilla suffocation hit as the only
                    // frame-timing allowance; invulnerability frames prevent a second hit during this short physical exit.
                    require(context, bot.isAlive() && bot.getHealth() >= healthBefore - 1.0F,
                            "OreDig lost more than one suffocation hit leaving the occupied body cell: "
                                    + healthBefore + " -> " + bot.getHealth());
                    LOGGER.info("ORE_DIG_GRAVITY_HEAD_RETREAT health_loss={} health_before={} health_after={}",
                            healthBefore - bot.getHealth(), healthBefore, bot.getHealth());
                    require(context, gravityBlockPresent(context.getLevel(), buried, buried.above()),
                            "strict retreat silently removed the gravity obstruction");
                    require(context, "1".equals(task.checkpoint().get("steps_left"))
                                    && "1".equals(task.checkpoint().get("direction")),
                            "collapsed north branch did not publish its finite visible escape: "
                                    + task.checkpoint());
                    task.cancel(bot, "gametest_complete");
                    finish(context, fixture);
                });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_blocked_body_retreat_immediately_publishes_marker_free_restart", maxTicks = 100)
    public void blockedBodyRetreatImmediatelyPublishesMarkerFreeRestart(
            GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreRetreatCheckpointGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos safeRear = fixture.start();
        BlockPos blockedFace = safeRear.north();
        BotFixtureMoves.place(bot, blockedFace);
        context.getLevel().setBlock(
                blockedFace.above(), Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        Map<String, String> checkpoint = new LinkedHashMap<>(openCheckpoint(
                blockedFace, 1, Set.of(Blocks.EMERALD_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "48");
        checkpoint.put("boundary_reroute_origin", encode(blockedFace));
        require(context, OreDigTask.inspectCheckpoint(checkpoint).isPresent(),
                "fixture marker checkpoint did not decode");

        OreDigTask task = new OreDigTask(Set.of(Blocks.EMERALD_ORE), 1, checkpoint);
        task.start(bot);
        TeleportAudit.reset(bot);
        task.tick(bot); // starts the walked retreat
        require(context, task.state() == TaskState.RUNNING && !bot.getActionPack().stepIdle(),
                "blocked-body recovery did not start a walked retreat: "
                        + task.state() + ":" + task.failureReason());
        Map<String, String> inFlight = task.checkpoint();
        require(context, encode(blockedFace).equals(inFlight.get("face"))
                        && encode(blockedFace).equals(inFlight.get("boundary_reroute_origin")),
                "the checkpoint named a face the bot had not reached while the step was in flight: " + inFlight);

        // The cursor is published from the verified landing, a later tick; a restart from that checkpoint binds the escape.
        tickUntil(context, task, bot,
                () -> encode(safeRear).equals(task.checkpoint().get("face")),
                60, "blocked-body retreat", () -> {
                    require(context, task.state() == TaskState.RUNNING && bot.blockPosition().equals(safeRear),
                            "blocked-body recovery did not reach its factual rear: "
                                    + task.state() + ":" + task.failureReason());
                    Map<String, String> immediate = task.checkpoint();
                    require(context, OreDigTask.inspectCheckpoint(immediate).isPresent()
                                    && encode(safeRear).equals(immediate.get("face"))
                                    && "1".equals(immediate.get("steps_left"))
                                    && "1".equals(immediate.get("direction"))
                                    && encode(safeRear).equals(
                                    immediate.get("boundary_reroute_origin")),
                            "landing checkpoint did not bind its finite factual escape: "
                                    + immediate);

                    task.cancel(bot, "gametest_immediate_checkpoint_restart");
                    OreDigTask restored = new OreDigTask(Set.of(Blocks.EMERALD_ORE), 1, immediate);
                    restored.start(bot);
                    Map<String, String> restarted = restored.checkpoint();
                    require(context, restored.state() == TaskState.RUNNING
                                    && OreDigTask.inspectCheckpoint(restarted).isPresent()
                                    && encode(safeRear).equals(restarted.get("face"))
                                    && encode(safeRear).equals(
                                    restarted.get("boundary_reroute_origin")),
                            "retreat restart lost or changed its bounded escape marker: " + restarted);

                    restored.cancel(bot, "gametest_complete");
                    finish(context, fixture);
                });
    }



    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_pending_pickup_gravity_retreat_does_not_become_blind_branch_terminal", maxTicks = 100)
    public void pendingPickupGravityRetreatDoesNotBecomeBlindBranchTerminal(
            GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupGravityOwnerGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos safeRear = fixture.start();
        BlockPos blockedFace = safeRear.north();
        BlockPos pendingDrop = safeRear.east(3);
        var world = context.getLevel();
        Map<String, String> checkpoint = new LinkedHashMap<>(openCheckpoint(
                safeRear, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "48");
        checkpoint.put("pending_pickup_pos", encode(pendingDrop));
        checkpoint.put("pending_pickup_inventory", "0");
        checkpoint.put("pending_pickup_started_budget", "0");
        require(context, OreDigTask.inspectCheckpoint(checkpoint).isPresent(),
                "pickup-owner gravity fixture checkpoint did not decode");

        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        task.start(bot);
        task.tick(bot); // restore the pickup owner and publish the factual branch rear
        require(context, encode(pendingDrop).equals(
                        task.checkpoint().get("pending_pickup_pos")),
                "fixture lost pickup ownership before the gravity collision");
        bot.getActionPack().stopAll();
        BotFixtureMoves.place(bot, blockedFace);
        world.setBlock(
                blockedFace.above(), Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_ALL);
        for (BlockPos rejected : new BlockPos[]{
                safeRear.east(), safeRear.west(), safeRear.south()}) {
            world.setBlock(rejected, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(rejected.above(), Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
        }
        Standability.clearCache();
        float healthBefore = bot.getHealth();
        TeleportAudit.reset(bot);

        task.tick(bot); // starts the walked retreat
        require(context, task.state() == TaskState.RUNNING && !bot.getActionPack().stepIdle(),
                "pickup-owned gravity collision did not start a walked retreat: "
                        + task.state() + ":" + task.failureReason());
        // The retreat lands on a later tick; the pickup ledger and the cursor are untouched throughout.
        tickUntil(context, task, bot,
                () -> bot.blockPosition().equals(safeRear) && bot.getActionPack().stepIdle(),
                60, "pickup-owned gravity retreat", () -> {
                    task.tick(bot); // publishes the landing
                    Map<String, String> live = task.checkpoint();
                    require(context, task.state() == TaskState.RUNNING,
                            "pickup-owned retreat was misclassified as a blind branch terminal: "
                                    + task.failureReason());
                    require(context, bot.blockPosition().equals(safeRear),
                            "pickup-owned gravity collision did not reach its factual safe rear");
                    require(context, encode(pendingDrop).equals(live.get("pending_pickup_pos"))
                                    && "0".equals(live.get("pending_pickup_inventory")),
                            "gravity retreat discarded or rewrote the pending pickup ledger: " + live);
                    require(context, "0".equals(live.get("direction"))
                                    && "48".equals(live.get("steps_left"))
                                    && !live.containsKey("boundary_reroute_origin"),
                            "non-branch pickup owner mutated the strip cursor or reroute marker: " + live);
                    require(context, gravityBlockPresent(world, blockedFace, blockedFace.above()),
                            "pickup-owned retreat silently removed the gravity obstruction");
                    // The serialized gravity-retreat measurement was zero loss. One vanilla suffocation hit is the only
                    // retained frame-timing allowance while the pickup owner physically leaves the occupied cell.
                    require(context, bot.isAlive() && bot.getHealth() >= healthBefore - 1.0F,
                            "pickup-owned retreat lost more than one suffocation hit before reaching safety: "
                                    + healthBefore + " -> " + bot.getHealth());
                    LOGGER.info("ORE_DIG_PICKUP_OWNER_GRAVITY_RETREAT health_loss={} health_before={} health_after={}",
                            healthBefore - bot.getHealth(), healthBefore, bot.getHealth());

                    task.cancel(bot, "gametest_complete");
                    finish(context, fixture);
                });
    }



        @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_target_lower_step_rejects_observed_fluid_neighbour_in_strict_mode", maxTicks = 40)
    public void targetLowerStepRejectsObservedFluidNeighbourInStrictMode(
            GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreTargetLowerHazardGT");
        AIPlayerEntity bot = fixture.bot();
        assertStrictCapabilities(context, bot);
        var world = context.getLevel();
        BlockPos start = fixture.start();
        BlockPos landing = start.east().below();
        BlockPos ore = start.east(3).below();
        BlockPos sideFluid = landing.north();

        world.setBlock(landing, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(landing.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(landing.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(landing.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(sideFluid, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(ore, Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
        for (Direction direction : new Direction[]{
                Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            world.setBlock(
                    ore.below().relative(direction),
                    Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
        }
        Standability.clearCache();
        require(context, Standability.isStandable(world, landing)
                        && ObservableWorldQuery.canObserveBlock(bot, ore)
                        && (ObservableWorldQuery.canObserveCell(bot, sideFluid)
                        || ObservableWorldQuery.canObserveBlock(bot, sideFluid)),
                "fixture did not expose a strict lower landing with an observed fluid neighbour");

        Map<String, String> checkpoint = new LinkedHashMap<>(openCheckpoint(
                start, 1, Set.of(Blocks.DIAMOND_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "17");
        OreDigTask task = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, checkpoint);
        task.start(bot);
        task.tick(bot);
        task.tick(bot);

        Map<String, String> rejected = task.checkpoint();
        require(context, task.state() == TaskState.RUNNING
                        && bot.blockPosition().equals(start)
                        && "17".equals(rejected.get("steps_left"))
                        && encode(start).equals(rejected.get("face"))
                        && world.getBlockState(sideFluid).is(Blocks.WATER),
                "strict lower approach entered or mutated an adjacent-fluid landing: "
                        + task.state() + ":" + task.failureReason() + " " + rejected);

        task.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_lower_step_fluid_gate_rejects_unobservable_neighbour_in_strict_mode", maxTicks = 20)
    public void lowerStepFluidGateRejectsUnobservableNeighbourInStrictMode(
            GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreTargetHiddenLowerHazardGT");
        AIPlayerEntity bot = fixture.bot();
        assertStrictCapabilities(context, bot);
        int radius = Math.max(1, MinecraftAiConfig.get().perception().radius());
        BlockPos hiddenCenter = fixture.start().east(radius + 8);
        BlockPos hiddenNeighbour = hiddenCenter.below();

        require(context, !ObservableWorldQuery.canObserveCell(bot, hiddenNeighbour)
                        && !ObservableWorldQuery.canObserveBlock(bot, hiddenNeighbour),
                "fixture neighbour was not outside strict-survival observation");
        require(context, !OreDigTask.isObservedAdjacentFluidSafe(
                        bot, context.getLevel(), hiddenCenter),
                "lower-step fluid gate accepted an unobservable neighbour");

        finish(context, fixture);
    }



    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_aligned_rich_zone_path_does_not_spend_blind_cursor", maxTicks = 500)
    public void alignedRichZonePathDoesNotSpendBlindCursor(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreRichCursorOwnerGT");
        AIPlayerEntity bot = fixture.bot();
        assertStrictCapabilities(context, bot);
        var world = context.getLevel();
        BlockPos start = fixture.start();
        BlockPos firstForeignStep = start.north();
        BlockPos zone = start.north(20);
        for (int north = 0; north <= 22; north++) {
            for (int east = -2; east <= 2; east++) {
                BlockPos feet = start.north(north).east(east);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        io.github.zoyluo.minecraftai.memory.EpisodeLog.INSTANCE.clearFor(bot.getUUID());
        io.github.zoyluo.minecraftai.memory.KnowledgeBase.INSTANCE.resetFor(bot.getUUID());
        for (BlockPos remembered : new BlockPos[]{zone, zone.east(10), zone.west(10)}) {
            io.github.zoyluo.minecraftai.memory.EpisodeLog.INSTANCE.record(
                    bot,
                    io.github.zoyluo.minecraftai.memory.EpisodeLog.Type.RESOURCE_FOUND,
                    remembered,
                    "minecraft:emerald_ore");
        }
        require(context, io.github.zoyluo.minecraftai.memory.KnowledgeBase.INSTANCE
                        .richZoneNear(bot.getUUID(), "minecraft:emerald_ore", start, 128, 3, 24)
                        .filter(zone::equals).isPresent(),
                "fixture did not publish the exact aligned rich-zone owner");

        Map<String, String> checkpoint = new LinkedHashMap<>(openCheckpoint(
                start, 1, Set.of(Blocks.EMERALD_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "17");
        OreDigTask task = new OreDigTask(Set.of(Blocks.EMERALD_ORE), 1, checkpoint);
        task.start(bot);
        AtomicInteger ticks = new AtomicInteger();
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            require(context, task.state() == TaskState.RUNNING,
                    "rich-zone owner ended before its cursor assertion: "
                            + task.state() + ":" + task.failureReason());
            Map<String, String> live = task.checkpoint();
            require(context, "17".equals(live.get("steps_left")),
                    "foreign rich-zone path spent blind cursor budget: " + live);
            if (!bot.getActionPack().isPathExecutorIdle()
                    && bot.blockPosition().equals(firstForeignStep)) {
                require(context, encode(firstForeignStep).equals(live.get("face"))
                                && !live.containsKey("controlled_strip_rear")
                                && OreDigTask.inspectCheckpoint(live).isPresent(),
                        "aligned foreign first step acquired blind ownership: " + live);
                io.github.zoyluo.minecraftai.memory.EpisodeLog.INSTANCE.clearFor(bot.getUUID());
                io.github.zoyluo.minecraftai.memory.KnowledgeBase.INSTANCE.resetFor(bot.getUUID());
                task.cancel(bot, "gametest_complete");
                finish(context, fixture);
                return;
            }
            if (ticks.incrementAndGet() > 440) {
                context.fail(Component.nullToEmpty(
                        "rich-zone path never exposed its aligned first step: "
                                + live + " pos=" + bot.blockPosition().toShortString()));
            }
        });
    }













    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_survival_guard_pause_displacement_restores_unpublished_rear_and_cursor", maxTicks = 260)
    public void survivalGuardPauseDisplacementRestoresUnpublishedRearAndCursor(
            GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreStripPauseResumeGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos lip = start.south();
        BlockPos safetyFace = lip.east();

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.EMERALD_ORE)));
        checkpoint.put("direction", "2");
        checkpoint.put("leg", "3");
        checkpoint.put("steps_left", "84");
        checkpoint.put("leg_length", "96");
        checkpoint.put("boundary_reroute_origin", encode(start));

        OreDigTask task = new OreDigTask(Set.of(Blocks.EMERALD_ORE), 1, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_ore_branch_pause_resume"));
        TaskManager.INSTANCE.tickAll(context.getLevel().getServer());
        bot.getActionPack().stopAll();
        BotFixtureMoves.place(bot, lip);
        bot.getActionPack().startWalkTo(
                lip.getCenter(),
                io.github.zoyluo.minecraftai.action.WalkToController.PATH_NODE_ARRIVAL_THRESHOLD);

        Map<String, String> beforeGuard = task.checkpoint();
        int budgetBeforeGuard = Integer.parseInt(beforeGuard.get("budget_used"));
        bot.setHealth(5.0F);
        bot.hurtTime = 5;
        TaskManager.INSTANCE.tickAll(context.getLevel().getServer());

        Map<String, String> paused = task.checkpoint();
        require(context, task.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.getActive(bot).isEmpty()
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == task
                        && encode(start).equals(paused.get("face"))
                        && "84".equals(paused.get("steps_left"))
                        && encode(start).equals(paused.get("boundary_reroute_origin"))
                        && Integer.parseInt(paused.get("budget_used")) == budgetBeforeGuard,
                "survival guard consumed or rewrote the unpublished branch: " + paused);

        BotFixtureMoves.place(bot, safetyFace);
        bot.setHealth(bot.getMaxHealth());
        bot.hurtTime = 0;
        TaskManager.INSTANCE.resumeFromPause(bot);

        Map<String, String> resumed = task.checkpoint();
        require(context, task.state() == TaskState.RUNNING
                        && TaskManager.INSTANCE.getActive(bot).orElse(null) == task
                        && encode(start).equals(resumed.get("face"))
                        && "84".equals(resumed.get("steps_left"))
                        && encode(start).equals(resumed.get("boundary_reroute_origin")),
                "resume adopted the safety task destination as mining progress: " + resumed);

        AtomicInteger ticks = new AtomicInteger();
        context.failIfEver(() -> {
            require(context, task.state() != TaskState.FAILED
                            && task.state() != TaskState.CANCELLED,
                    "paused branch failed while restoring its factual rear: "
                            + task.state() + ":" + task.failureReason());
            Map<String, String> live = task.checkpoint();
            require(context, OreDigTask.inspectCheckpoint(live).isPresent()
                            && encode(start).equals(live.get("face"))
                            && "84".equals(live.get("steps_left"))
                            && encode(start).equals(live.get("boundary_reroute_origin"))
                            && Integer.parseInt(live.get("budget_used"))
                            >= budgetBeforeGuard,
                    "safety displacement mutated the pre-move cursor: " + live);
            if (bot.blockPosition().equals(start)
                    && bot.getActionPack().isPathExecutorIdle()
                    && !task.describe().contains("returning to saved face")) {
                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
                finish(context, fixture);
                return;
            }
            if (ticks.incrementAndGet() > 220) {
                context.fail(Component.nullToEmpty(
                        "resumed branch never restored its exact factual rear: checkpoint="
                                + live + " pos=" + bot.blockPosition().toShortString()));
            }
        });
    }





    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_hidden_lower_transition_remains_unknown_whether_blocked_or_open", maxTicks = 20)
    public void hiddenLowerTransitionRemainsUnknownWhetherBlockedOrOpen(
            GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreHiddenTransitionGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos transition = fixture.start().north(80).above();
        var world = context.getLevel();

        world.setBlock(transition, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        OreScan.Observation hiddenStone = OreDigTask.observePickupEgressClearance(
                bot, world, transition);
        world.setBlock(transition, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        OreScan.Observation hiddenAir = OreDigTask.observePickupEgressClearance(
                bot, world, transition);
        require(context, hiddenStone == OreScan.Observation.UNKNOWN
                        && hiddenAir == OreScan.Observation.UNKNOWN,
                "hidden lower transition leaked blocked/open state: stone="
                        + hiddenStone + " air=" + hiddenAir);
        finish(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_restart_keeps_unknown_active_target_without_inventing_pickup_debt", maxTicks = 40)
    public void restartKeepsUnknownActiveTargetWithoutInventingPickupDebt(
            GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreUnknownActiveRestartGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos hiddenOre = start.north(80);
        var world = context.getLevel();
        world.setBlock(hiddenOre, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(hiddenOre.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        require(context, OreScan.observeOre(bot, hiddenOre, Set.of(Blocks.COAL_ORE))
                        == OreScan.Observation.UNKNOWN,
                "restart fixture did not keep the active target outside ordinary perception");

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("active_break_pos", encode(hiddenOre));
        checkpoint.put("active_break_inventory", "0");
        OreDigTask restored = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        restored.start(bot);
        Map<String, String> before = restored.checkpoint();
        restored.tick(bot);
        Map<String, String> after = restored.checkpoint();

        require(context, restored.state() == TaskState.RUNNING
                        && encode(hiddenOre).equals(before.get("active_break_pos"))
                        && !after.containsKey("active_break_pos")
                        && !before.containsKey("pending_pickup_pos")
                        && !after.containsKey("pending_pickup_pos")
                        && world.getBlockState(hiddenOre).is(Blocks.COAL_ORE),
                "unknown restart target was not safely released without inventing pickup debt: before="
                        + before + " after=" + after);
        restored.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_restart_rejects_intact_high_active_break_but_preserves_gone_break_debt", maxTicks = 20)
    public void restartRejectsIntactHighActiveBreakButPreservesGoneBreakDebt(
            GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreHighActiveRestartGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos highOre = fixture.start().above(5);
        var world = context.getLevel();
        world.setBlock(highOre, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
        require(context, OreScan.observeOre(bot, highOre, Set.of(Blocks.COAL_ORE))
                        == OreScan.Observation.OBSERVED_PRESENT,
                "intact high restart target was not factually visible");

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(fixture.start(), 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("active_break_pos", encode(highOre));
        checkpoint.put("active_break_inventory", "0");
        OreDigTask intact = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        intact.start(bot);
        intact.tick(bot);
        Map<String, String> rejected = intact.checkpoint();
        require(context, intact.state() == TaskState.RUNNING
                        && world.getBlockState(highOre).is(Blocks.COAL_ORE)
                        && bot.getActionPack().isMiningIdle()
                        && !rejected.containsKey("active_break_pos")
                        && !rejected.containsKey("pending_pickup_pos"),
                "restart resumed an intact high-shaft break or invented pickup debt: " + rejected);
        intact.cancel(bot, "gametest_intact_high_restart_complete");

        // The same checkpoint must not erase a transaction whose block is already factually gone.
        // That case still owns a physical ItemEntity debt even though the old break pose is invalid.
        world.setBlock(highOre, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        require(context, OreScan.observeOre(bot, highOre, Set.of(Blocks.COAL_ORE))
                        == OreScan.Observation.OBSERVED_GONE,
                "gone high restart target was not factually observable");
        OreDigTask gone = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        gone.start(bot);
        Map<String, String> preserved = gone.checkpoint();
        require(context, encode(highOre).equals(preserved.get("pending_pickup_pos"))
                        && "0".equals(preserved.get("pending_pickup_inventory"))
                        && !preserved.containsKey("active_break_pos"),
                "restart erased or misclassified a factually gone high break: " + preserved);
        gone.cancel(bot, "gametest_gone_high_restart_complete");
        finish(context, fixture);
    }





    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_head_side_fluid_cannot_consume_exact_protected_reserve", maxTicks = 20)
    public void headSideFluidCannotConsumeExactProtectedReserve(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreStripHeadFluidReserveGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos forward = start.north();
        BlockPos forwardHead = forward.above();
        BlockPos headLava = forwardHead.east();
        var world = context.getLevel();
        int protectedStone = ServicePolicy.bootstrapStoneLikeTarget(32)
                + MiningBudget.OBSIDIAN_BOOTSTRAP_CHANNEL_RETRY_STONE_LIKE;

        world.setBlock(forward, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(forwardHead, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(headLava, Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
        // East is contaminated by the head source; leave one visible, solid west branch so the
        // exact-reserve result is a finite typed reroute rather than an all-directions trap.
        world.setBlock(start.west(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.west().above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, protectedStone));
        require(context, ObservableWorldQuery.canObserveBlock(bot, headLava),
                "exact-reserve fixture did not expose its head-side lava");

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "12");
        OreDigTask task = new OreDigTask(
                Set.of(Blocks.COAL_ORE), 1, 0, protectedStone, checkpoint);
        task.start(bot);
        task.tick(bot);
        Map<String, String> rerouted = task.checkpoint();
        require(context, task.state() == TaskState.RUNNING
                        && world.getBlockState(headLava).is(Blocks.LAVA)
                        && world.getBlockState(forward).is(Blocks.STONE)
                        && world.getBlockState(forwardHead).is(Blocks.STONE)
                        && InventoryAction.countItem(bot, Items.COBBLESTONE) == protectedStone
                        && "12".equals(rerouted.get("steps_left"))
                        && !rerouted.containsKey("active_break_pos")
                        && !rerouted.containsKey("pending_pickup_pos"),
                "head-fluid boundary consumed reserve or opened the body before reroute: "
                        + task.state() + ":" + task.failureReason() + " " + rerouted);
        task.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }





    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_support_ore_rejects_elevated_relocation_outside_break_envelope", maxTicks = 20)
    public void supportOreRejectsElevatedRelocationOutsideBreakEnvelope(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreSupportElevatedGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        var world = bot.level();
        BlockPos supportOre = start.below();

        world.setBlock(supportOre, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
        for (net.minecraft.core.Direction direction
                : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos side = start.relative(direction);
            world.setBlock(side, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(side.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(side.below(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        BlockPos elevatedLanding = start.north().above();
        world.setBlock(elevatedLanding.below(), Blocks.STONE.defaultBlockState(),
                Block.UPDATE_ALL);
        Standability.clearCache();
        require(context, Standability.isStandable(world, elevatedLanding),
                "fixture did not create the sole elevated relocation");
        require(context, ObservableWorldQuery.canObserveBlock(bot, supportOre),
                "support ore is not strictly observable");

        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1);
        task.start(bot);
        task.tick(bot); // acquire the support ore
        task.tick(bot); // reject the sole dy=+1 relocation instead of opening a path loop

        require(context, task.state() == TaskState.RUNNING,
                "awkward support ore ended the bounded search: "
                        + task.state() + ":" + task.failureReason());
        require(context, bot.getActionPack().isPathExecutorIdle()
                        && bot.getActionPack().isWalkToIdle(),
                "support ore started an elevated relocation outside the break envelope");
        require(context, bot.blockPosition().equals(start),
                "support ore relocation moved before a recoverable work pose existed");
        require(context, world.getBlockState(supportOre).is(Blocks.COAL_ORE),
                "rejecting the elevated relocation modified the finite ore");

        task.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_channel_tool_failure_reports_the_blocked_ore_tier", maxTicks = 20)
    public void channelToolFailureReportsTheBlockedOreTier(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreChannelTierGT");
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.removeItems(bot, Items.IRON_PICKAXE, 1);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        BlockPos gold = fixture.start().north();
        bot.level().setBlock(
                gold, Blocks.GOLD_ORE.defaultBlockState(), Block.UPDATE_ALL);

        BlockMiner miner = new BlockMiner();
        miner.begin(bot, gold, true);
        BlockMiner.Status status = miner.tick(bot);

        require(context, status == BlockMiner.Status.FAILED,
                "stone-only channel unexpectedly started mining gold");
        require(context,
                "missing_mining_channel_tool:minecraft:iron_pickaxe"
                        .equals(miner.failureReason()),
                "blocked gold reported the wrong channel tool: " + miner.failureReason());
        require(context, bot.level().getBlockState(gold).is(Blocks.GOLD_ORE),
                "typed channel failure modified the gold obstruction");
        finish(context, fixture);
    }

        @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_nearby_restart_position_cannot_replace_the_exact_saved_face", maxTicks = 20)
    public void nearbyRestartPositionCannotReplaceTheExactSavedFace(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreExactFaceRestoreGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos savedFace = fixture.start().north(2);
        Map<String, String> checkpoint = openCheckpoint(savedFace, 1);

        OreDigTask restored = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, checkpoint);
        restored.start(bot);
        restored.tick(bot);

        require(context, restored.state() == TaskState.RUNNING,
                "nearby face restore ended unexpectedly: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, bot.blockPosition().equals(fixture.start()),
                "opening the exact-face path moved outside the task tick: "
                        + bot.blockPosition().toShortString());
        require(context, encode(savedFace).equals(restored.checkpoint().get("face")),
                "nearby restart position replaced the exact saved face: "
                        + restored.checkpoint());

        restored.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_distant_committed_cursor_rebases_to_the_current_physical_branch", maxTicks = 20)
    public void distantCommittedCursorRebasesToTheCurrentPhysicalBranch(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreCommittedRebaseGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos staleFace = fixture.start().north(32);
        Map<String, String> checkpoint = new LinkedHashMap<>(openCheckpoint(
                staleFace, 1, Set.of(Blocks.IRON_ORE)));
        checkpoint.put("batch_open", "false");
        checkpoint.put("batches", "3");

        OreDigTask restored = new OreDigTask(Set.of(Blocks.IRON_ORE), 1, checkpoint);
        restored.start(bot);

        require(context, restored.state() == TaskState.RUNNING,
                "committed-cursor rebase ended unexpectedly: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, encode(fixture.start()).equals(restored.checkpoint().get("origin"))
                        && encode(fixture.start()).equals(restored.checkpoint().get("face")),
                "distant committed cursor was not rebased locally: "
                        + restored.checkpoint());
        require(context, "3".equals(restored.checkpoint().get("batches")),
                "committed-cursor rebase erased completed batch history");

        restored.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_distant_open_cursor_still_retains_the_exact_saved_face", maxTicks = 20)
    public void distantOpenCursorStillRetainsTheExactSavedFace(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreOpenFaceRestoreGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos savedFace = fixture.start().north(32);
        Map<String, String> checkpoint = openCheckpoint(
                savedFace, 1, Set.of(Blocks.IRON_ORE));

        OreDigTask restored = new OreDigTask(Set.of(Blocks.IRON_ORE), 1, checkpoint);
        restored.start(bot);

        require(context, restored.state() == TaskState.RUNNING,
                "open-cursor restore ended unexpectedly: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, encode(savedFace).equals(restored.checkpoint().get("face")),
                "open batch silently abandoned its exact saved face: "
                        + restored.checkpoint());

        restored.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_pickup_game_tests_strip_lighting_does_not_consume_tool_service_sticks", maxTicks = 20)
    public void stripLightingDoesNotConsumeToolServiceSticks(GameTestHelper context) {
        PickupFixture fixture = spawnMiner(context, "OreTorchReserveGT");
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK));
        Map<String, String> checkpoint = new LinkedHashMap<>(openCheckpoint(fixture.start(), 1));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "10");

        OreDigTask task = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, checkpoint);
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.RUNNING,
                "strip-lighting fixture ended unexpectedly: "
                        + task.state() + ":" + task.failureReason());
        require(context, InventoryAction.countItem(bot, Items.COAL) == 1,
                "strip lighting consumed incidental coal");
        require(context, InventoryAction.countItem(bot, Items.STICK) == 1,
                "strip lighting stole a tool-service stick");
        require(context, InventoryAction.countItem(bot, Items.TORCH) == 0,
                "strip lighting synthesized torches without a planned craft");

        task.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    private static PickupFixture spawnMiner(GameTestHelper context, String name) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(6, 3, 9));
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -8; dz <= 3; dz++) {
                world.setBlock(start.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 3; dy++) {
                    world.setBlock(start.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        180.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 180.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.getFoodData().setSaturation(5.0F);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        return new PickupFixture(name, bot, start.immutable());
    }

    private static BlockPos isolateCheckpointMiner(PickupFixture fixture, int dy) {
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        BlockPos isolated = fixture.start().above(dy).immutable();
        world.setBlock(
                isolated.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(isolated, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(isolated.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        bot.teleportTo(world,
                isolated.getX() + 0.5D, isolated.getY(), isolated.getZ() + 0.5D,
                Set.of(), 180.0F, 0.0F, true);
        return isolated;
    }

    private static void assertStrictCapabilities(GameTestHelper context, AIPlayerEntity bot) {
        require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + MinecraftAiConfig.get().profile());
        for (PrivilegedCapability capability : PrivilegedCapability.values()) {
            require(context, !CapabilityRuntime.decide(
                            bot, capability, "ore_dig_pickup_gametest").allowed(),
                    "strict_survival unexpectedly allowed " + capability);
        }
    }

    private static void assertAliveWithoutDeath(GameTestHelper context,
                                                AIPlayerEntity bot,
                                                int deathBaseline) {
        require(context, bot.isAlive() && bot.getHealth() > 0.0F,
                "miner died during physical pickup regression");
        require(context, deathCount(bot) == deathBaseline,
                "miner death counter changed during physical pickup regression");
    }

    private static void failIfTerminalError(GameTestHelper context, OreDigTask task) {
        if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
            context.fail(Component.nullToEmpty("OreDig pickup task ended as " + task.state()
                    + ":" + task.failureReason() + " checkpoint=" + task.checkpoint()));
        }
    }

    private static java.util.Optional<Vec3> nearestDiamondDropPosition(AIPlayerEntity bot,
                                                                        BlockPos pos) {
        return nearestDiamondDrop(bot, pos).map(ItemEntity::position);
    }

    private static java.util.Optional<ItemEntity> nearestDiamondDrop(AIPlayerEntity bot,
                                                                      BlockPos pos) {
        return bot.level().getEntitiesOfClass(
                        ItemEntity.class, new AABB(pos).inflate(4.0D),
                        entity -> entity.getItem().is(Items.DIAMOND))
                .stream()
                .min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(bot)));
    }

    private static int deathCount(AIPlayerEntity bot) {
        return bot.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS));
    }

    private static String encode(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static Map<String, String> openCheckpoint(BlockPos face, int targetCount) {
        return openCheckpoint(face, targetCount, Set.of(Blocks.DIAMOND_ORE));
    }

    private static Map<String, String> openCheckpoint(BlockPos face,
                                                       int targetCount,
                                                       Set<Block> ores) {
        int rareMissionTarget = targetCount >= 8
                && (ores.contains(Blocks.DIAMOND_ORE)
                || ores.contains(Blocks.DEEPSLATE_DIAMOND_ORE)) ? targetCount : 0;
        return openCheckpoint(face, targetCount, ores, rareMissionTarget);
    }

    private static Map<String, String> openCheckpoint(BlockPos face,
                                                       int targetCount,
                                                       Set<Block> ores,
                                                       int rareMissionTarget) {
        return new OreDigCheckpoint(
                4,
                targetCount,
                true,
                0,
                rareMissionTarget,
                false,
                40,
                0,
                0,
                MiningCursor.initial(face, 48),
                OreDigTask.oreFingerprint(ores),
                0,
                0,
                null,
                null,
                null,
                null,
                -1,
                -1,
                -1,
                null,
                -1).encode();
    }

    @SuppressWarnings("unchecked")
    private static java.util.Deque<BlockPos> inspectVeinQueueForFixture(OreDigTask task) {
        try {
            java.lang.reflect.Field field = OreDigTask.class.getDeclaredField("veinQueue");
            field.setAccessible(true);
            return (java.util.Deque<BlockPos>) field.get(task);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("could not inspect OreDig vein queue", exception);
        }
    }

    private static void enqueueVeinForFixture(OreDigTask task, BlockPos ore) {
        java.util.Deque<BlockPos> queue = inspectVeinQueueForFixture(task);
        queue.clear();
        queue.addLast(ore.immutable());
    }

    @SuppressWarnings("unchecked")
    private static Map<BlockPos, BlockPos> inspectRememberedHighWorkPosesForFixture(
            OreDigTask task) {
        try {
            java.lang.reflect.Field field = OreDigTask.class.getDeclaredField(
                    "rememberedHighWorkPoses");
            field.setAccessible(true);
            return (Map<BlockPos, BlockPos>) field.get(task);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "could not inspect OreDig remembered work poses", exception);
        }
    }

    private static void setBlockPosFieldForFixture(OreDigTask task,
                                                   String fieldName,
                                                   BlockPos value) {
        try {
            java.lang.reflect.Field field = OreDigTask.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(task, value == null ? null : value.immutable());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "could not set OreDig fixture field " + fieldName, exception);
        }
    }

    private static void rememberHighWorkPoseForFixture(OreDigTask task,
                                                       AIPlayerEntity bot,
                                                       BlockPos ore,
                                                       BlockPos pose) {
        try {
            java.lang.reflect.Method method = OreDigTask.class.getDeclaredMethod(
                    "rememberObservedHighWorkPose",
                    AIPlayerEntity.class, BlockPos.class, BlockPos.class);
            method.setAccessible(true);
            method.invoke(task, bot, ore, pose);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "could not publish OreDig remembered work pose", exception);
        }
    }

    private static void assertCheckpointFieldsEqual(GameTestHelper context,
                                                    Map<String, String> expected,
                                                    Map<String, String> actual,
                                                    String... keys) {
        for (String key : keys) {
            require(context, java.util.Objects.equals(expected.get(key), actual.get(key)),
                    "checkpoint transform changed " + key + ": before=" + expected.get(key)
                            + ", after=" + actual.get(key));
        }
    }

    /**
     * OreDig moves by walked steps (R5: no micro-teleports), so a move is over only when a later game tick has verified its landing:
     * the task is ticked once per game tick until {@code done} holds (then {@code then} runs on that tick), and every tick asserts
     * that the bot was never teleported. {@code TeleportAudit.reset(bot)} belongs right before the measured phase.
     */
    private static void tickUntil(GameTestHelper context, OreDigTask task, AIPlayerEntity bot,
                                  java.util.function.BooleanSupplier done, int maxTicks, String what, Runnable then) {
        new TickRunner(context, bot).until(task, done, maxTicks, what, then);
    }

    /**
     * The stages of a test that follow each other over game ticks. One tick callback is registered on the first stage (a game test
     * must not register callbacks from inside a callback); a stage's {@code then} may queue the next stage.
     */
    private static final class TickRunner {
        private record Stage(OreDigTask task, java.util.function.BooleanSupplier done, int maxTicks, String what, Runnable then) {
        }

        private final GameTestHelper context;
        private final AIPlayerEntity bot;
        private final java.util.ArrayDeque<Stage> stages = new java.util.ArrayDeque<>();
        private boolean registered;
        private int ticks;

        TickRunner(GameTestHelper context, AIPlayerEntity bot) {
            this.context = context;
            this.bot = bot;
        }

        void until(OreDigTask task, java.util.function.BooleanSupplier done, int maxTicks, String what, Runnable then) {
            stages.add(new Stage(task, done, maxTicks, what, then));
            if (!registered) {
                registered = true;
                context.onEachTick(this::tick);
            }
        }

        private void tick() {
            Stage stage = stages.peek();
            if (stage == null) {
                return;
            }
            require(context, TeleportAudit.corrections(bot) == 0,
                    stage.what() + ": the bot was teleported (corrections=" + TeleportAudit.corrections(bot)
                            + " last=" + TeleportAudit.lastCaller(bot) + ")");
            if (stage.done().getAsBoolean()) {
                stages.poll();
                ticks = 0;
                stage.then().run();
                return;
            }
            require(context, ++ticks <= stage.maxTicks(),
                    stage.what() + ": not done after " + stage.maxTicks() + " ticks: " + stage.task().state() + ":"
                            + stage.task().failureReason() + " at " + bot.blockPosition().toShortString() + " "
                            + stage.task().checkpoint());
            require(context, stage.task().state() == TaskState.RUNNING,
                    stage.what() + ": the task ended as " + stage.task().state() + ":" + stage.task().failureReason());
            stage.task().tick(bot);
        }
    }

    /**
     * Whether the gravel of a gravity fixture is still there: as a block in one of {@code cells}, or as a falling block on its way down
     * (the retreat is a walk, so the gravel above a vacated cell starts falling while the test still looks: a bot that mined it would
     * leave neither a block nor a falling block).
     */
    private static boolean gravityBlockPresent(net.minecraft.server.level.ServerLevel world, BlockPos... cells) {
        for (BlockPos cell : cells) {
            if (world.getBlockState(cell).is(Blocks.GRAVEL)) {
                return true;
            }
        }
        return !world.getEntitiesOfClass(net.minecraft.world.entity.item.FallingBlockEntity.class,
                new AABB(cells[0]).inflate(3.0D), entity -> entity.getBlockState().is(Blocks.GRAVEL)).isEmpty();
    }

    private static void finish(GameTestHelper context, PickupFixture fixture) {
        require(context, HarvestCore.countInventoryItems(
                        fixture.bot(), Set.of(Items.DIAMOND))
                        == InventoryAction.countItem(fixture.bot(), Items.DIAMOND),
                "diamond accounting disagrees across inventory observers");
        AIPlayerManager.INSTANCE.despawn(
                fixture.bot().level().getServer(), fixture.name());
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }

    private record PickupFixture(String name, AIPlayerEntity bot, BlockPos start) {
    }
}

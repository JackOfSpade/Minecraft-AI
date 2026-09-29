package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Strict-survival regressions for Gather's physical drop transaction. */
public final class GatherPickupGameTests {
    @GameTest(environment = "minecraftai-gametest:gather_pickup_game_tests_vanilla_pickup_stat_survives_concurrent_log_consumption", maxTicks = 500)
    public void vanillaPickupStatSurvivesConcurrentLogConsumption(GameTestHelper context) {
        Fixture fixture = fixture(context, "GatherPickupStatGT", new BlockPos(2, 2, 2), 5);
        AIPlayerEntity bot = fixture.bot();
        // GatherQuotaTask now gates the optimal tool category before harvesting (an axe for
        // logs); these fixtures are about the pickup/pause/resume state machine, not tool
        // selection, so give the bot an axe up front to keep exercising that machinery.
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_AXE));
        BlockPos first = fixture.start().east(3);
        bot.level().setBlock(first, Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG));
        int pickupBaseline = bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.OAK_LOG);

        GatherQuotaTask task = new GatherQuotaTask(Items.OAK_LOG, 2);
        task.start(bot);
        AtomicBoolean consumed = new AtomicBoolean();

        context.failIfEver(() -> {
            tickOrFail(context, task, bot);
            if (!consumed.get() && task.describe().contains("phase=PICKUP")) {
                require(context, InventoryAction.removeItems(bot, Items.OAK_LOG, 1),
                        "failed to simulate concurrent resupply consumption");
                consumed.set(true);
            }
            boolean pickupRecorded = bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.OAK_LOG)
                    > pickupBaseline;
            boolean atomicPhaseResolved = !task.describe().contains("phase=PICKUP")
                    && !task.describe().contains("phase=HARVEST");
            if (!consumed.get() || !pickupRecorded || !atomicPhaseResolved) {
                return;
            }
            require(context, task.state() == TaskState.RUNNING,
                    "net-zero pickup was mistaken for terminal gather state: " + task.describe());
            require(context, InventoryAction.countItem(bot, Items.OAK_LOG) == 1,
                    "physical pickup did not replace the concurrently consumed log");
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_pickup_game_tests_real_miss_retries_nearby_resource_before_regional_roam", maxTicks = 700)
    public void realMissRetriesNearbyResourceBeforeRegionalRoam(GameTestHelper context) {
        Fixture fixture = fixture(context, "GatherMissRetryGT", new BlockPos(2, 2, 2), 5);
        AIPlayerEntity bot = fixture.bot();
        // GatherQuotaTask now gates the optimal tool category before harvesting (an axe for
        // logs); these fixtures are about the pickup/pause/resume state machine, not tool
        // selection, so give the bot an axe up front to keep exercising that machinery.
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_AXE));
        BlockPos first = fixture.start().east(2);
        BlockPos second = fixture.start().east(5);
        bot.level().setBlock(first, Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        bot.level().setBlock(second, Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);

        GatherQuotaTask task = new GatherQuotaTask(Items.OAK_LOG, 1);
        task.start(bot);
        AtomicBoolean discarded = new AtomicBoolean();
        AtomicBoolean revisitedBreakCell = new AtomicBoolean();
        AtomicBoolean localRetryHarvestStarted = new AtomicBoolean();

        context.failIfEver(() -> {
            tickOrFail(context, task, bot);
            if (!discarded.get() && task.describe().contains("phase=PICKUP")) {
                ItemEntity drop = nearestOakDrop(bot, first, 3.0D);
                require(context, drop != null, "first harvest produced no removable test drop");
                drop.discard();
                discarded.set(true);
            }
            if (discarded.get() && bot.blockPosition().equals(first)) {
                revisitedBreakCell.set(true);
            }
            if (discarded.get()
                    && task.describe().contains("phase=HARVEST")
                    && bot.level().getBlockState(second).is(Blocks.OAK_LOG)) {
                localRetryHarvestStarted.set(true);
            }
            if (discarded.get() && !localRetryHarvestStarted.get()
                    && task.state() == TaskState.RUNNING) {
                require(context, !task.describe().contains("phase=ROAM")
                                && !task.describe().contains("phase=EXPLORE"),
                        "one real pickup miss bypassed the local retry budget: " + task.describe());
            }
            if (!localRetryHarvestStarted.get()
                    || !bot.level().getBlockState(second).isAir()) {
                return;
            }
            require(context, discarded.get(), "real-miss fixture never activated");
            require(context, revisitedBreakCell.get(),
                    "invisible drop did not fall back to its remembered break coordinate");
            require(context, bot.blockPosition().distSqr(fixture.start()) < 20.0D * 20.0D,
                    "gather escaped the local test area before retrying the nearby log");
            require(context, !task.describe().contains("phase=ROAM")
                            && !task.describe().contains("phase=EXPLORE"),
                    "local retry entered regional roaming before its second harvest settled");
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_pickup_game_tests_reachable_harvest_restarts_immediately_after_safety_pause", maxTicks = 300)
    public void reachableHarvestRestartsImmediatelyAfterSafetyPause(GameTestHelper context) {
        Fixture fixture = fixture(context, "GatherResumeHarvestGT", new BlockPos(2, 2, 2), 5);
        AIPlayerEntity bot = fixture.bot();
        // GatherQuotaTask now gates the optimal tool category before harvesting (an axe for
        // logs); these fixtures are about the pickup/pause/resume state machine, not tool
        // selection, so give the bot an axe up front to keep exercising that machinery.
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_AXE));
        BlockPos log = fixture.start().east(3);
        bot.level().setBlock(log, Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);

        GatherQuotaTask task = new GatherQuotaTask(Items.OAK_LOG, 1);
        task.start(bot);
        AtomicBoolean resumed = new AtomicBoolean();

        context.failIfEver(() -> {
            tickOrFail(context, task, bot);
            if (!resumed.get()) {
                if (!task.describe().contains("phase=HARVEST")) {
                    return;
                }
                require(context, !bot.getActionPack().isMiningIdle(),
                        "fixture entered HARVEST without a live mining controller");
                task.pause(bot);
                require(context, task.state() == TaskState.PAUSED
                                && bot.getActionPack().isMiningIdle(),
                        "pause did not release the atomic harvest");
                task.resume(bot);
                require(context, task.state() == TaskState.RUNNING
                                && task.describe().contains("phase=HARVEST"),
                        "reachable harvest did not preserve its transaction on resume");
                require(context, !bot.getActionPack().isMiningIdle(),
                        "reachable harvest waited for the 200-tick retry boundary after resume");
                resumed.set(true);
                return;
            }
            if (!bot.level().getBlockState(log).isAir()) {
                return;
            }
            require(context, task.state() == TaskState.RUNNING
                            || task.state() == TaskState.COMPLETED,
                    "resumed harvest did not settle normally: "
                            + task.state() + ":" + task.failureReason());
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_pickup_game_tests_safety_displacement_reselects_instead_of_mining_remote_target", maxTicks = 300)
    public void safetyDisplacementReselectsInsteadOfMiningRemoteTarget(GameTestHelper context) {
        Fixture fixture = fixture(context, "GatherResumeReselectGT", new BlockPos(2, 2, 2), 5);
        AIPlayerEntity bot = fixture.bot();
        // GatherQuotaTask now gates the optimal tool category before harvesting (an axe for
        // logs); these fixtures are about the pickup/pause/resume state machine, not tool
        // selection, so give the bot an axe up front to keep exercising that machinery.
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_AXE));
        BlockPos log = fixture.start().east(3);
        bot.level().setBlock(log, Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);

        GatherQuotaTask task = new GatherQuotaTask(Items.OAK_LOG, 1);
        task.start(bot);

        context.failIfEver(() -> {
            tickOrFail(context, task, bot);
            if (!task.describe().contains("phase=HARVEST")) {
                return;
            }
            task.pause(bot);
            BlockPos displaced = fixture.start().west(2);
            bot.teleportTo(bot.level(),
                    displaced.getX() + 0.5D, displaced.getY(), displaced.getZ() + 0.5D,
                    Set.of(), bot.getYRot(), bot.getXRot(), true);
            require(context, !HarvestCore.canReach(bot, log),
                    "fixture displacement left the old harvest inside interaction reach");
            task.resume(bot);

            require(context, task.state() == TaskState.RUNNING
                            && task.describe().contains("phase=SURVEY"),
                    "displaced resume retained stale HARVEST: " + task.describe());
            require(context, bot.getActionPack().isMiningIdle(),
                    "displaced resume started an out-of-reach mining controller");
            require(context, bot.level().getBlockState(log).is(Blocks.OAK_LOG),
                    "resume fixture unexpectedly consumed the remote target");
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_pickup_game_tests_out_of_reach_retry_cannot_renew_harvest_deadline", maxTicks = 400)
    public void outOfReachRetryCannotRenewHarvestDeadline(GameTestHelper context) {
        Fixture fixture = fixture(context, "GatherHarvestLeaseGT", new BlockPos(2, 2, 2), 5);
        AIPlayerEntity bot = fixture.bot();
        // GatherQuotaTask now gates the optimal tool category before harvesting (an axe for
        // logs); these fixtures are about the pickup/pause/resume state machine, not tool
        // selection, so give the bot an axe up front to keep exercising that machinery.
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_AXE));
        BlockPos log = fixture.start().east(3);
        bot.level().setBlock(log, Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);

        GatherQuotaTask task = new GatherQuotaTask(Items.OAK_LOG, 1);
        task.start(bot);
        AtomicBoolean displaced = new AtomicBoolean();
        int[] ticksAfterDisplacement = {0};

        context.failIfEver(() -> {
            tickOrFail(context, task, bot);
            if (!displaced.get()) {
                if (!task.describe().contains("phase=HARVEST")) {
                    return;
                }
                BlockPos remote = fixture.start().west(2);
                bot.teleportTo(bot.level(),
                        remote.getX() + 0.5D, remote.getY(), remote.getZ() + 0.5D,
                        Set.of(), bot.getYRot(), bot.getXRot(), true);
                require(context, !HarvestCore.canReach(bot, log),
                        "fixture displacement left the target reachable");
                displaced.set(true);
                return;
            }
            ticksAfterDisplacement[0]++;
            if (task.describe().contains("phase=HARVEST")) {
                require(context, ticksAfterDisplacement[0] <= 260,
                        "periodic retry renewed HARVEST beyond its local deadline");
                return;
            }
            require(context, task.state() == TaskState.RUNNING
                            && task.describe().contains("phase=SURVEY"),
                    "expired atomic harvest did not return to survey: " + task.describe());
            require(context, bot.level().getBlockState(log).is(Blocks.OAK_LOG),
                    "out-of-reach fixture unexpectedly broke the target");
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_pickup_game_tests_repeated_safety_resume_cannot_renew_harvest_deadline", maxTicks = 400)
    public void repeatedSafetyResumeCannotRenewHarvestDeadline(GameTestHelper context) {
        Fixture fixture = fixture(context, "GatherResumeLeaseGT", new BlockPos(2, 2, 2), 5);
        AIPlayerEntity bot = fixture.bot();
        // GatherQuotaTask now gates the optimal tool category before harvesting (an axe for
        // logs); these fixtures are about the pickup/pause/resume state machine, not tool
        // selection, so give the bot an axe up front to keep exercising that machinery.
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_AXE));
        BlockPos log = fixture.start().east(3);
        bot.level().setBlock(log, Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);

        GatherQuotaTask task = new GatherQuotaTask(Items.OAK_LOG, 1);
        task.start(bot);
        AtomicBoolean interrupting = new AtomicBoolean();
        int[] interruptions = {0};

        context.failIfEver(() -> {
            tickOrFail(context, task, bot);
            if (!interrupting.get()) {
                if (!task.describe().contains("phase=HARVEST")) {
                    return;
                }
                interrupting.set(true);
            }
            if (task.describe().contains("phase=HARVEST")) {
                task.pause(bot);
                task.resume(bot);
                bot.getActionPack().stopAll();
                interruptions[0]++;
                require(context, interruptions[0] <= 260,
                        "repeated safety resume renewed HARVEST beyond its local deadline");
                return;
            }

            require(context, interruptions[0] >= 230,
                    "fixture did not exercise the original HARVEST lease");
            require(context, task.state() == TaskState.RUNNING
                            && task.describe().contains("phase=SURVEY"),
                    "interrupted atomic harvest did not expire into survey: " + task.describe());
            require(context, bot.level().getBlockState(log).is(Blocks.OAK_LOG),
                    "interrupted fixture unexpectedly broke the target");
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_pickup_game_tests_origin_sweep_collects_drop_resting_beyond_reach_of_the_first_stand_cell", maxTicks = 400)
    public void originSweepCollectsDropRestingBeyondReachOfTheFirstStandCell(GameTestHelper context) {
        // A log broken with another log still standing above it leaves a break cell nobody can stand in,
        // and its drop can come to rest a cell further out, hidden behind the standing logs. Parking in
        // the nearest standable cell and nudging leaves that item just outside pickup reach for the whole
        // window; the sweep must walk the other standable cells around the break cell (no digging, no
        // pillaring) until the item is collected.
        Fixture fixture = fixture(context, "GatherOriginSweepGT", new BlockPos(2, 2, 2), 5);
        AIPlayerEntity bot = fixture.bot();
        BlockPos origin = fixture.start().east(2);
        BlockPos cap = origin.above();
        bot.level().setBlock(cap, Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        require(context, !io.github.zoyluo.minecraftai.pathfinding.Standability.isStandable(bot.level(), origin),
                "fixture: the break cell must not be standable");
        BlockPos resting = origin.east();
        ItemEntity drop = new ItemEntity(bot.level(), resting.getX() + 0.65D, resting.getY(),
                resting.getZ() + 0.65D, new ItemStack(Items.OAK_LOG));
        drop.setDeltaMovement(Vec3.ZERO);
        bot.level().addFreshEntity(drop);
        int before = InventoryAction.countItem(bot, Items.OAK_LOG);
        io.github.zoyluo.minecraftai.action.KnownCellPickupSweep sweep =
                new io.github.zoyluo.minecraftai.action.KnownCellPickupSweep(origin);

        context.failIfEver(() -> {
            require(context, bot.level().getBlockState(cap).is(Blocks.OAK_LOG),
                    "the sweep must never dig the standing log");
            require(context, bot.blockPosition().getY() == fixture.start().getY(),
                    "the sweep must not pillar or climb: " + bot.blockPosition());
            if (InventoryAction.countItem(bot, Items.OAK_LOG) > before) {
                finish(context, fixture);
                return;
            }
            if (bot.getActionPack().isPathExecutorIdle() && bot.getActionPack().isWalkToIdle()) {
                require(context, sweep.step(bot) != io.github.zoyluo.minecraftai.action.KnownCellPickupSweep.Step.EXHAUSTED,
                        "the sweep ran out of cells before collecting the drop");
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_pickup_game_tests_bootstrap_pickup_sweeps_for_hidden_resting_drop", maxTicks = 700)
    public void bootstrapPickupSweepsForHiddenRestingDrop(GameTestHelper context) {
        // Integration of the whole exact-break bootstrap through GatherQuotaTask (not the sweep alone): an
        // empty-inventory bot breaks a capped log by hand, and the drop of that break is replaced by one
        // resting on the far side of the log, out of the bot's line of sight. The BOOTSTRAP_PICKUP
        // window must find it by walking around the break cell (no digging) instead of giving up and
        // breaking another log by hand for want of the one that was lost.
        Fixture fixture = fixture(context, "GatherBootstrapHiddenDropGT", new BlockPos(2, 2, 2), 5);
        AIPlayerEntity bot = fixture.bot();
        BlockPos log = fixture.start().east(3);
        BlockPos cap = log.above();
        bot.level().setBlock(log, Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        bot.level().setBlock(cap, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        // A two-high stone wall beside the log hides the far corner (log.east().south()) from a bot standing
        // west of the log even once the log cell itself is empty.
        bot.level().setBlock(log.south(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        bot.level().setBlock(log.south().above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        require(context, bot.getInventory().isEmpty(), "fixture must start with an empty inventory");

        GatherQuotaTask task = GatherQuotaTask.breakBlocks(Blocks.OAK_LOG, 5);
        task.start(bot);
        AtomicBoolean planted = new AtomicBoolean();

        context.failIfEver(() -> {
            tickOrFail(context, task, bot);
            require(context, bot.level().getBlockState(cap).is(Blocks.STONE),
                    "the bootstrap pickup must never dig the cap: " + task.describe());
            boolean pickupPhase = task.describe().contains("phase=BOOTSTRAP_PICKUP");
            if (!planted.get()) {
                if (!pickupPhase) {
                    return;
                }
                require(context, bot.level().getBlockState(log).isAir(),
                        "the bootstrap pickup began but the log is still there: " + task.describe());
                // Replace the drop of that break by one resting behind the stone wall beside the (former) log cell.
                for (ItemEntity drop : bot.level().getEntitiesOfClass(ItemEntity.class,
                        new AABB(log).inflate(6.0D), entity -> true)) {
                    drop.discard();
                }
                BlockPos resting = log.east().south();
                ItemEntity hidden = new ItemEntity(bot.level(), resting.getX() + 0.5D, resting.getY(),
                        resting.getZ() + 0.5D, new ItemStack(Items.OAK_LOG));
                hidden.setDeltaMovement(Vec3.ZERO);
                hidden.setPickUpDelay(0);
                bot.level().addFreshEntity(hidden);
                require(context, !io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveEntity(bot, hidden),
                        "fixture: the planted drop must be out of the bot's sight (bot at " + bot.blockPosition() + ", log " + log + ", drop " + resting + ")");
                planted.set(true);
                return;
            }
            if (pickupPhase) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.OAK_LOG) == 1,
                    "the hidden drop was not collected before the bootstrap pickup ended: " + task.describe());
            finish(context, fixture);
        });
    }

    private static Fixture fixture(GameTestHelper context, String name, BlockPos relativeStart, int east) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(relativeStart);
        // Keep every mutation inside FabricGameTest.EMPTY_STRUCTURE (8x8). Tests from other
        // batches can overlap in wall-clock time; writing a long runway beyond the template lets
        // a later fixture erase this floor and turns a pickup assertion into a random void fall.
        for (int dx = -2; dx <= east; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = start.offset(dx, 0, dz);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        return new Fixture(bot, start, name);
    }

    private static ItemEntity nearestOakDrop(AIPlayerEntity bot, BlockPos center, double radius) {
        return bot.level().getEntitiesOfClass(
                        ItemEntity.class, new AABB(center).inflate(radius),
                        entity -> entity.getItem().is(Items.OAK_LOG))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private static void tickOrFail(GameTestHelper context, GatherQuotaTask task, AIPlayerEntity bot) {
        if (task.state() == TaskState.RUNNING) {
            task.tick(bot);
        }
        if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
            context.fail(Component.nullToEmpty("gather ended as " + task.state() + ":" + task.failureReason()));
        }
    }

    private static void finish(GameTestHelper context, Fixture fixture) {
        AIPlayerManager.INSTANCE.despawn(fixture.bot().level().getServer(), fixture.name());
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }

    private record Fixture(AIPlayerEntity bot, BlockPos start, String name) {
    }
}

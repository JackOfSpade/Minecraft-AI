package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.navigation.NavOutcome;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Live proof that surface water is reached physically, filled through vanilla, and restartable. */
public final class AcquireWaterTaskGameTests {
    @GameTest(environment = "minecraftai-gametest:acquire_water_task_game_tests_approach_descends_to_exact_reachable_stand_before_filling", maxTicks = 200)
    public void approachDescendsToExactReachableStandBeforeFilling(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos stand = context.absolutePos(new BlockPos(8, 6, 8));
        BlockPos start = stand.east().above();
        BlockPos source = stand.south(3).below(2);

        // P=start is loosely "near" W=stand (distance squared 2), but P's eye is outside the
        // bucket reach of Q=source. W is within reach. This is the seed-3000 failure geometry:
        // the old APPROACH predicate stopped its live path before completing the one-block drop.
        for (int dx = -2; dx <= 3; dx++) {
            for (int dz = -2; dz <= 5; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    world.setBlock(stand.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        world.setBlock(stand.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = 0; dz <= 5; dz++) {
                world.setBlock(source.offset(dx, -1, dz - 3),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        world.setBlock(source, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);

        String name = "WaterExactStandGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));

        require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + MinecraftAiConfig.get().profile());
        for (PrivilegedCapability capability : PrivilegedCapability.values()) {
            require(context, !CapabilityRuntime.decide(
                            bot, capability, "water_exact_stand_gametest").allowed(),
                    "strict_survival unexpectedly allowed " + capability);
        }
        double startReachSquared = bot.getEyePosition().distanceToSqr(source.getCenter());
        double exactReachSquared = Vec3.atBottomCenterOf(stand).add(0.0D, 1.62D, 0.0D)
                .distanceToSqr(source.getCenter());
        double interactionReachSquared = bot.blockInteractionRange()
                * bot.blockInteractionRange();
        require(context, start.distSqr(stand) <= 4.0D
                        && startReachSquared > interactionReachSquared
                        && exactReachSquared < interactionReachSquared,
                "fixture did not isolate loose arrival from exact interaction reach: start="
                        + startReachSquared + " exact=" + exactReachSquared
                        + " reach=" + interactionReachSquared);

        AtomicReference<AcquireWaterTask> active = new AtomicReference<>(
                new AcquireWaterTask(stand, approachCheckpoint(stand, source, stand)));
        active.get().start(bot);
        active.get().tick(bot);
        Map<String, String> beforeRestart = active.get().checkpoint();
        require(context, active.get().state() == TaskState.RUNNING
                        && "APPROACH".equals(beforeRestart.get("phase"))
                        && encode(source).equals(beforeRestart.get("water_source"))
                        && encode(stand).equals(beforeRestart.get("water_stand")),
                "loose arrival rejected the reachable source before exact travel: "
                        + beforeRestart);
        require(context, stand.equals(bot.getActionPack().activePathGoal()),
                "APPROACH did not retain the exact stand as its live path goal: "
                        + bot.getActionPack().activePathGoal());
        require(context, InventoryAction.countItem(bot, Items.BUCKET) == 1
                        && InventoryAction.countItem(bot, Items.WATER_BUCKET) == 0
                        && world.getFluidState(source).is(FluidTags.WATER),
                "loose arrival mutated the bucket or source before exact travel");

        // Restart at the vulnerable boundary: the exact target must survive and be retried from
        // the factual high cell, rather than being downgraded to a rejected SEARCH source.
        active.get().cancel(bot, "gametest_exact_stand_restart");
        AcquireWaterTask restored = new AcquireWaterTask(stand, beforeRestart);
        restored.start(bot);
        require(context, restored.state() == TaskState.RUNNING
                        && "APPROACH".equals(restored.checkpoint().get("phase")),
                "exact-stand APPROACH checkpoint did not restart: " + restored.failureReason());
        active.set(restored);

        AtomicReference<BlockPos> previous = new AtomicReference<>(start.immutable());
        AtomicBoolean reachedExactStand = new AtomicBoolean();
        context.failIfEver(() -> {
            AcquireWaterTask task = active.get();
            BlockPos now = bot.blockPosition().immutable();
            BlockPos before = previous.get();
            if (!now.equals(before)) {
                int horizontal = Math.abs(now.getX() - before.getX())
                        + Math.abs(now.getZ() - before.getZ());
                int vertical = now.getY() - before.getY();
                require(context, horizontal <= 1 && Math.abs(vertical) <= 1
                                && (horizontal > 0 || vertical != 0),
                        "water approach used non-adjacent movement: " + before + " -> " + now);
            }
            if (now.equals(stand)) {
                reachedExactStand.set(true);
            } else {
                require(context, task.state() == TaskState.RUNNING
                                && "APPROACH".equals(task.checkpoint().get("phase"))
                                && world.getFluidState(source).is(FluidTags.WATER)
                                && InventoryAction.countItem(bot, Items.WATER_BUCKET) == 0,
                        "APPROACH rejected or filled before exact stand: pos=" + now
                                + " checkpoint=" + task.checkpoint());
            }

            task.tick(bot);
            previous.set(bot.blockPosition().immutable());
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("exact-stand water approach failed: "
                        + task.failureReason() + " checkpoint=" + task.checkpoint()));
                return;
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, reachedExactStand.get() && bot.blockPosition().equals(stand),
                    "bucket filled without physically reaching the exact stand");
            require(context, bot.getEyePosition().distanceToSqr(source.getCenter())
                            <= interactionReachSquared,
                    "bucket filled outside the bot's current interaction reach");
            require(context, InventoryAction.countItem(bot, Items.BUCKET) == 0
                            && InventoryAction.countItem(bot, Items.WATER_BUCKET) == 1,
                    "exact stand did not perform one vanilla bucket exchange");
            require(context, world.getFluidState(source).isEmpty(),
                    "exact stand did not physically drain the source block");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:acquire_water_task_game_tests_shallow_water_at_feet_hands_return_to_physical_rescue_before_ascent_inspection", maxTicks = 40)
    public void shallowWaterAtFeetHandsReturnToPhysicalRescueBeforeAscentInspection(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(4, 4, 4));
        BlockPos surfaceAnchor = start.above(4);

        world.setBlock(start.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            world.setBlock(start.relative(direction),
                    Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(start.relative(direction).above(),
                    Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
        }

        String name = "WaterShallowFluidGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        AcquireWaterTask task = new AcquireWaterTask(surfaceAnchor);
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.RUNNING,
                "shallow-water return stopped before rescue: " + task.failureReason());
        require(context, !bot.isUnderWater()
                        && world.getFluidState(start).is(FluidTags.WATER),
                "fixture did not isolate feet-only water contact");
        require(context, NavSafetyNet.INSTANCE.isWaterRescueActive(bot),
                "feet-only water did not hand movement to the physical rescue controller");
        require(context, bot.getInventory().getNonEquipmentItems().stream()
                        .filter(stack -> stack.is(Items.STONE_PICKAXE))
                        .allMatch(stack -> stack.getDamageValue() == 0),
                "ascent mined a hidden direction before handing off shallow water");
        NavSafetyNet.INSTANCE.clear(bot);
        task.cancel(bot, "gametest_shallow_water_handoff_complete");
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
        context.succeed();
    }

    @GameTest(environment = "minecraftai-gametest:acquire_water_task_game_tests_dry_return_collects_visible_reachable_plain_water_without_minting_surface_proof", maxTicks = 40)
    public void dryReturnCollectsVisibleReachablePlainWaterWithoutMintingSurfaceProof(
            GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(4, 4, 4));
        BlockPos surfaceAnchor = start.above(8);
        BlockPos source = start.north().above(2);

        world.setBlock(start.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(source.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(source, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);

        String name = "WaterReturnAquiferGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        AcquireWaterTask task = new AcquireWaterTask(surfaceAnchor);
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.COMPLETED,
                "visible return aquifer did not complete: " + task.failureReason());
        require(context, bot.blockPosition().equals(start),
                "return aquifer moved before using the reachable source: " + bot.blockPosition());
        require(context, InventoryAction.countItem(bot, Items.BUCKET) == 0
                        && InventoryAction.countItem(bot, Items.WATER_BUCKET) == 1,
                "return aquifer did not perform exactly one vanilla bucket exchange");
        require(context, world.getFluidState(source).isEmpty(),
                "return aquifer source was not physically removed by the bucket interaction");
        Map<String, String> checkpoint = task.checkpoint();
        require(context, "DONE".equals(checkpoint.get("phase"))
                        && "false".equals(checkpoint.get("surface_exit"))
                        && !checkpoint.containsKey("water_source")
                        && !checkpoint.containsKey("water_stand"),
                "direct return completion minted surface/approach authority: " + checkpoint);

        AcquireWaterTask restored = new AcquireWaterTask(surfaceAnchor, checkpoint);
        restored.start(bot);
        require(context, restored.state() == TaskState.COMPLETED,
                "completed return aquifer did not restore from the physical water bucket: "
                        + restored.failureReason());
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
        context.succeed();
    }

    /**
     * A return route is live work.  Repeated RETURN_SURFACE ticks must leave that route alone so
     * Baritone can drive the bot up this fully visible, dry staircase on real server ticks.
     */
    @GameTest(environment = "minecraftai-gametest:acquire_water_task_game_tests_dry_visible_upward_return_keeps_its_baritone_route_until_physical_progress", maxTicks = 200)
    public void dryVisibleUpwardReturnKeepsItsBaritoneRouteUntilPhysicalProgress(
            GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(4, 4, 4));
        BlockPos surfaceAnchor = start.east(4).above(2);

        // This is an observable, dry one-block staircase, ending on an open surface platform.
        // It gives the return controller a factual route without using the retired local ascent.
        for (int dx = -1; dx <= 10; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 10; dy++) {
                    world.setBlock(start.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        for (int step = 0; step <= 4; step++) {
            BlockPos stand = start.east(step).above(step / 2);
            world.setBlock(stand.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(stand, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(stand.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos platform = surfaceAnchor.offset(dx, 0, dz);
                world.setBlock(platform.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(platform, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(platform.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }

        String name = "WaterReturnRoutePersistenceGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));

        require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + MinecraftAiConfig.get().profile());
        for (PrivilegedCapability capability : PrivilegedCapability.values()) {
            require(context, !CapabilityRuntime.decide(
                            bot, capability, "water_return_route_persistence_gametest").allowed(),
                    "strict_survival unexpectedly allowed " + capability);
        }
        for (int step = 0; step <= 4; step++) {
            BlockPos stand = start.east(step).above(step / 2);
            require(context, ObservableWorldQuery.canObserveCell(bot, stand),
                    "spawned bot cannot observe the factual return stair: " + stand.toShortString());
            require(context, world.getFluidState(stand).isEmpty()
                            && world.getBlockState(stand.below()).is(Blocks.STONE),
                    "fixture return stair is not a dry supported stand: " + stand.toShortString());
        }
        require(context, world.canSeeSky(surfaceAnchor),
                "fixture return platform is not an open surface: " + surfaceAnchor.toShortString());

        AcquireWaterTask task = new AcquireWaterTask(surfaceAnchor);
        task.start(bot);
        task.tick(bot);
        require(context, task.state() == TaskState.RUNNING
                        && "RETURN_SURFACE".equals(task.checkpoint().get("phase"))
                        && bot.getActionPack().hasBaritoneRoute()
                        && isNearSurfaceReturnGoal(bot.getActionPack().activePathGoal(), surfaceAnchor),
                "RETURN_SURFACE did not retain ownership of its initial visible route: "
                        + task.checkpoint() + " goal=" + bot.getActionPack().activePathGoal());

        AtomicReference<BlockPos> previous = new AtomicReference<>(bot.blockPosition().immutable());
        AtomicInteger returnTicks = new AtomicInteger(1);
        AtomicInteger physicalMoves = new AtomicInteger();
        context.failIfEver(() -> {
            BlockPos now = bot.blockPosition().immutable();
            BlockPos before = previous.get();
            if (!now.equals(before)) {
                int horizontal = Math.abs(now.getX() - before.getX())
                        + Math.abs(now.getZ() - before.getZ());
                int vertical = Math.abs(now.getY() - before.getY());
                require(context, horizontal <= 1 && vertical <= 1
                                && horizontal + vertical > 0,
                        "RETURN_SURFACE used non-physical movement: "
                                + before.toShortString() + " -> " + now.toShortString());
                physicalMoves.incrementAndGet();
            }

            task.tick(bot);
            returnTicks.incrementAndGet();
            Map<String, String> checkpoint = task.checkpoint();
            NavOutcome outcome = bot.getActionPack().lastRouteOutcome();
            require(context, task.state() == TaskState.RUNNING
                            && "RETURN_SURFACE".equals(checkpoint.get("phase"))
                            && bot.getActionPack().hasBaritoneRoute()
                            && isNearSurfaceReturnGoal(
                            bot.getActionPack().activePathGoal(), surfaceAnchor)
                            && (outcome == null || outcome.status() != NavOutcome.Status.CANCELLED),
                    "repeated RETURN_SURFACE tick cancelled or lost its route: checkpoint="
                            + checkpoint + " goal=" + bot.getActionPack().activePathGoal()
                            + " outcome=" + outcome);
            previous.set(now);

            if (returnTicks.get() < 4 || physicalMoves.get() < 2) {
                return;
            }
            task.cancel(bot, "gametest_return_route_persistence_complete");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    private static boolean isNearSurfaceReturnGoal(BlockPos goal, BlockPos anchor) {
        return goal != null && goal.distSqr(anchor) <= 4.0D;
    }

    @GameTest(environment = "minecraftai-gametest:acquire_water_task_game_tests_return_does_not_read_or_collect_an_occluded_plain_water_source", maxTicks = 40)
    public void returnDoesNotReadOrCollectAnOccludedPlainWaterSource(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(4, 4, 4));
        BlockPos surfaceAnchor = start.above(8);
        BlockPos screen = start.north().above();
        BlockPos source = start.north(2).above();

        world.setBlock(start.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(screen, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(source.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(source, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);

        String name = "WaterReturnHiddenAquiferGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        AcquireWaterTask task = new AcquireWaterTask(surfaceAnchor);
        task.start(bot);
        task.tick(bot);

        Map<String, String> checkpoint = task.checkpoint();
        require(context, task.state() == TaskState.RUNNING
                        && "RETURN_SURFACE".equals(checkpoint.get("phase"))
                        && "false".equals(checkpoint.get("surface_exit")),
                "occluded source changed the return phase: " + checkpoint);
        require(context, !checkpoint.containsKey("water_source")
                        && !checkpoint.containsKey("water_stand"),
                "occluded source minted an approach target: " + checkpoint);
        require(context, InventoryAction.countItem(bot, Items.BUCKET) == 1
                        && InventoryAction.countItem(bot, Items.WATER_BUCKET) == 0
                        && world.getFluidState(source).is(FluidTags.WATER),
                "occluded source was read or collected through its solid screen");
        task.cancel(bot, "gametest_hidden_return_aquifer_complete");
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
        context.succeed();
    }

    @GameTest(environment = "minecraftai-gametest:acquire_water_task_game_tests_displaced_search_retries_on_its_first_resume_tick", maxTicks = 80)
    public void displacedSearchRetriesOnItsFirstResumeTick(GameTestHelper context) {
        DryFixture fixture = spawnDryWaterSeeker(context, "WaterSearchPauseCooldownGT", false);
        AIPlayerEntity bot = fixture.bot();
        BlockPos waypoint = fixture.start().east(12);
        BlockPos safetyLanding = fixture.start().north();
        AcquireWaterTask task = new AcquireWaterTask(fixture.start(), searchCheckpoint(
                fixture.start(), waypoint, 380, 0, 1, 0));
        task.start(bot);
        task.tick(bot);
        require(context, bot.getActionPack().activePathGoal() != null,
                "fixture did not seed a live SEARCH path retry");
        task.pause(bot);
        require(context, task.state() == TaskState.PAUSED,
                "SEARCH path did not pause before safety displacement");
        var safetyMove = bot.getActionPack().startSurfacePathTo(safetyLanding);
        require(context, !safetyMove.isFailed(),
                "fixture safety displacement was rejected: " + safetyMove.reason());

        context.failIfEver(() -> {
            require(context, task.state() == TaskState.PAUSED,
                    "SEARCH task ran while safety movement owned the bot");
            if (!bot.blockPosition().equals(safetyLanding)) {
                return;
            }
            bot.getActionPack().stopAll();
            task.resume(bot);
            int beforeResumeTick = task.elapsedTicks();
            task.tick(bot);
            require(context, task.elapsedTicks() == beforeResumeTick + 1,
                    "SEARCH resume did not execute exactly one immediate task tick");
            require(context, bot.getActionPack().activePathGoal() != null
                            && !safetyLanding.equals(bot.getActionPack().activePathGoal()),
                    "first SEARCH tick inherited the pre-safety retry cooldown: goal="
                            + bot.getActionPack().activePathGoal());
            require(context, "0".equals(task.checkpoint().get("path_attempts")),
                    "displaced SEARCH retained position-dependent path failures: "
                            + task.checkpoint());
            task.cancel(bot, "gametest_search_pause_cooldown_complete");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), fixture.name());
            context.succeed();
        });
    }

    /** A visible exit persists as the search origin and never sends the bot back down its old stair. */
    @GameTest(environment = "minecraftai-gametest:acquire_water_task_game_tests_sky_visible_exit_rebases_search_without_reentering_mined_stair", maxTicks = 20)
    public void skyVisibleExitRebasesSearchWithoutReenteringMinedStair(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos exit = context.absolutePos(new BlockPos(32, 10, 32));
        BlockPos surfaceAnchor = exit.west(4).below(4);

        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                world.setBlock(exit.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(exit.offset(dx, 0, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(exit.offset(dx, 1, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        for (int step = 1; step <= 4; step++) {
            BlockPos stair = exit.west(step).below(step);
            world.setBlock(stair.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(stair, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(stair.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }

        String name = "WaterSurfaceLatchGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(exit),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, exit.getX() + 0.5D, exit.getY(), exit.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));

        AcquireWaterTask task = new AcquireWaterTask(surfaceAnchor);
        task.start(bot);
        task.tick(bot);
        Map<String, String> checkpoint = task.checkpoint();
        require(context, task.state() == TaskState.RUNNING
                        && "SEARCH".equals(checkpoint.get("phase"))
                        && "true".equals(checkpoint.get("surface_exit"))
                        && encode(exit).equals(checkpoint.get("search_origin"))
                        && bot.blockPosition().getY() >= exit.getY(),
                "visible exit did not rebase the surface search before re-entering the stair: " + checkpoint);

        task.cancel(bot, "gametest_surface_exit_latch_complete");
        AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
        context.succeed();
    }
    @GameTest(environment = "minecraftai-gametest:acquire_water_task_game_tests_dry_overhang_footing_uses_its_observable_sky_edge_as_surface_exit", maxTicks = 40)
    public void dryOverhangFootingUsesItsObservableSkyEdgeAsSurfaceExit(GameTestHelper context) {
        var world = context.getLevel();
        // Keep every mutated cell inside the tiny EMPTY_STRUCTURE footprint. A far local offset
        // can overlap a concurrently placed GameTest structure and make the roof nondeterministic.
        BlockPos exit = context.absolutePos(new BlockPos(1, 4, 1));
        BlockPos skyEdge = exit.east();
        BlockPos surfaceAnchor = exit.west(8).below(3);

        world.setBlock(exit.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(exit, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(exit.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // One remaining shelter/terrain block keeps the bot's own column non-sky-visible while the
        // adjacent dry edge is a factual, observable route onto the open surface.
        world.setBlock(exit.above(2), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(skyEdge.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(skyEdge, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(skyEdge.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        String name = "WaterOverhangExitGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(exit),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, exit.getX() + 0.5D, exit.getY(), exit.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        // Sky light/heightmap propagation is ticked. Assert the roof only after the world has had
        // time to publish the block update; synchronous checks are order-dependent in full suites.
        context.runAtTickTime(2, () -> {
            require(context, !world.canSeeSky(exit),
                    "fixture footing unexpectedly sees sky");
            require(context, world.canSeeSky(skyEdge),
                    "fixture outward edge does not see sky");

            Map<String, String> exhaustedAnchorRoute = new LinkedHashMap<>(
                    returnSurfaceCheckpoint(surfaceAnchor, 200, 3));
            AcquireWaterTask task = new AcquireWaterTask(surfaceAnchor, exhaustedAnchorRoute);
            task.start(bot);
            task.tick(bot);
            Map<String, String> checkpoint = task.checkpoint();
            require(context, task.state() == TaskState.RUNNING,
                    "overhang exit stopped unexpectedly: " + task.failureReason());
            require(context, "SEARCH".equals(checkpoint.get("phase"))
                            && "true".equals(checkpoint.get("surface_exit"))
                            && encode(exit).equals(checkpoint.get("search_origin"))
                            && "0".equals(checkpoint.get("path_attempts")),
                    "observable sky edge did not publish one durable surface exit: " + checkpoint);

            task.cancel(bot, "gametest_overhang_exit_restart");
            AcquireWaterTask restored = new AcquireWaterTask(surfaceAnchor, checkpoint);
            restored.start(bot);
            Map<String, String> after = restored.checkpoint();
            require(context, restored.state() == TaskState.RUNNING
                            && "SEARCH".equals(after.get("phase"))
                            && "true".equals(after.get("surface_exit"))
                            && encode(exit).equals(after.get("search_origin"))
                            && "0".equals(after.get("path_attempts")),
                    "overhang surface latch changed across restart: before=" + checkpoint
                            + " after=" + after);
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:acquire_water_task_game_tests_oscillation_cannot_reset_waypoint_budget_across_restart", maxTicks = 100)
    public void oscillationCannotResetWaypointBudgetAcrossRestart(GameTestHelper context) {
        DryFixture fixture = spawnDryWaterSeeker(context, "WaterOscillationBudgetGT", false);
        AIPlayerEntity bot = fixture.bot();
        BlockPos waypoint = fixture.start().east(12);
        AtomicReference<AcquireWaterTask> active = new AtomicReference<>(new AcquireWaterTask(
                fixture.start(), searchCheckpoint(fixture.start(), waypoint,
                380, 0, 1, 0)));
        AtomicBoolean restarted = new AtomicBoolean();
        AtomicInteger ticks = new AtomicInteger();
        active.get().start(bot);

        context.failIfEver(() -> {
            AcquireWaterTask task = active.get();
            int tick = ticks.getAndIncrement();
            BlockPos forced = (tick & 1) == 0 ? fixture.start() : fixture.start().north();
            bot.getActionPack().stopAll();
            bot.teleportTo(bot.level(),
                    forced.getX() + 0.5D, forced.getY(), forced.getZ() + 0.5D,
                    Set.of(), 0.0F, 0.0F, true);
            task.tick(bot);
            Map<String, String> checkpoint = task.checkpoint();

            if (!restarted.get()
                    && Integer.parseInt(checkpoint.get("budget_used")) >= 390
                    && checkpoint.containsKey("waypoint")) {
                require(context, "0".equals(checkpoint.get("waypoint_started_budget")),
                        "movement renewed the absolute waypoint deadline before restart: " + checkpoint);
                task.cancel(bot, "gametest_waypoint_budget_restart");
                AcquireWaterTask restored = new AcquireWaterTask(fixture.start(), checkpoint);
                restored.start(bot);
                require(context, restored.state() == TaskState.RUNNING,
                        "valid liveness checkpoint did not restart: " + restored.failureReason());
                require(context, "0".equals(restored.checkpoint().get("waypoint_started_budget")),
                        "restart renewed the absolute waypoint deadline");
                active.set(restored);
                restarted.set(true);
                return;
            }

            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("oscillation budget ended unexpectedly: "
                        + task.state() + ":" + task.failureReason() + " checkpoint=" + checkpoint));
                return;
            }
            if (encode(waypoint).equals(checkpoint.get("waypoint"))) {
                if (Integer.parseInt(checkpoint.get("budget_used")) > 400) {
                    context.fail(Component.nullToEmpty(
                            "oscillation extended the waypoint beyond its absolute budget: " + checkpoint));
                }
                return;
            }
            require(context, restarted.get(), "waypoint expired without exercising restart");
            require(context, "1".equals(checkpoint.get("issued"))
                            && "0".equals(checkpoint.get("reached")),
                    "unreached waypoint was reported as an observation: " + checkpoint);
            require(context, Integer.parseInt(checkpoint.get("budget_used")) <= 400,
                    "waypoint deadline was renewed by oscillation/restart: " + checkpoint);
            cleanupDry(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:acquire_water_task_game_tests_legacy_hundred_terminal_stays_terminal_across_schema_four_restore", maxTicks = 20)
    public void legacyHundredTerminalStaysTerminalAcrossSchemaFourRestore(GameTestHelper context) {
        DryFixture fixture = spawnDryWaterSeeker(context, "WaterLegacyHundredGT", false);
        Map<String, String> legacy = new LinkedHashMap<>(searchCheckpoint(
                fixture.start(), fixture.start(), 10_114, 10_114, 100, 81));
        legacy.put("schema", "3");
        legacy.put("direction", "3");
        legacy.put("leg_length", "10");
        legacy.put("step_in_leg", "0");
        legacy.put("repeated_legs", "1");
        legacy.put("grid_x", "-5");
        legacy.put("grid_z", "5");
        legacy.remove("waypoint");
        legacy.remove("waypoint_limit");
        legacy.remove("budget_limit");

        AcquireWaterTask first = new AcquireWaterTask(fixture.start(), legacy);
        first.start(fixture.bot());
        Map<String, String> migrated = first.checkpoint();
        require(context, first.state() == TaskState.FAILED
                        && first.failureReason().startsWith("acquire_water_search_exhausted")
                        && "4".equals(migrated.get("schema"))
                        && "100".equals(migrated.get("waypoint_limit"))
                        && "12000".equals(migrated.get("budget_limit"))
                        && "100".equals(migrated.get("issued"))
                        && !migrated.containsKey("waypoint"),
                "legacy point-100 terminal gained new search authority: "
                        + first.state() + ":" + first.failureReason()
                        + " checkpoint=" + migrated);

        AcquireWaterTask second = new AcquireWaterTask(fixture.start(), migrated);
        second.start(fixture.bot());
        require(context, second.state() == TaskState.FAILED
                        && second.failureReason().startsWith("acquire_water_search_exhausted")
                        && "100".equals(second.checkpoint().get("waypoint_limit")),
                "migrated legacy point-100 terminal restarted as a fresh expedition");
        cleanupDry(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:acquire_water_task_game_tests_current_cursor_ends_after_complete_seventh_ring", maxTicks = 20)
    public void currentCursorEndsAfterCompleteSeventhRing(GameTestHelper context) {
        DryFixture fixture = spawnDryWaterSeeker(context, "WaterSeventhRingGT", false);
        // Cursor 223 is grid=(6,-7). Rebase it onto the physical dry fixture so issuing point 224
        // asks for one ordinary 12-block eastward path instead of a synthetic long-distance jump.
        BlockPos searchOrigin = fixture.start().offset(-72, 0, 84);
        Map<String, String> cursor223 = new LinkedHashMap<>(searchCheckpoint(
                searchOrigin, fixture.start(), 200, 180, 223, 200));
        cursor223.put("direction", "0");
        cursor223.put("leg_length", "15");
        cursor223.put("step_in_leg", "13");
        cursor223.put("repeated_legs", "0");
        cursor223.put("grid_x", "6");
        cursor223.put("grid_z", "-7");
        cursor223.remove("waypoint");

        AcquireWaterTask active = new AcquireWaterTask(searchOrigin, cursor223);
        active.start(fixture.bot());
        active.tick(fixture.bot());
        Map<String, String> point224 = active.checkpoint();
        require(context, active.state() == TaskState.RUNNING
                        && "224".equals(point224.get("issued"))
                        && "7".equals(point224.get("grid_x"))
                        && "-7".equals(point224.get("grid_z"))
                        && "0".equals(point224.get("direction"))
                        && "15".equals(point224.get("leg_length"))
                        && "14".equals(point224.get("step_in_leg"))
                        && encode(fixture.start().east(12)).equals(point224.get("waypoint")),
                "point 224 did not close the complete seventh ring: " + point224);

        active.cancel(fixture.bot(), "gametest_seventh_ring_terminal");
        Map<String, String> terminal = new LinkedHashMap<>(point224);
        terminal.remove("waypoint");
        terminal.put("path_attempts", "0");
        AcquireWaterTask exhausted = new AcquireWaterTask(searchOrigin, terminal);
        exhausted.start(fixture.bot());
        require(context, exhausted.state() == TaskState.FAILED
                        && exhausted.failureReason().startsWith(
                        "acquire_water_search_exhausted")
                        && "224".equals(exhausted.checkpoint().get("issued")),
                "current cursor issued a point beyond its complete seventh ring");
        cleanupDry(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:acquire_water_task_game_tests_surface_search_moves_beyond_initial_perception_restarts_and_fills", maxTicks = 240)
    public void surfaceSearchMovesBeyondInitialPerceptionRestartsAndFills(GameTestHelper context) {
        WaterFixture fixture = spawnDistantWaterSeeker(context, "WaterSurfaceSearchGT");
        AIPlayerEntity bot = fixture.bot();
        // Issue the first spiral post at the top of the well's west rim. It is 19 blocks from
        // spawn, while the contained source remains 20 blocks away until physical movement.
        BlockPos searchOrigin = fixture.start().offset(7, 1, 0);
        BlockPos requestedWaypoint = searchOrigin.east(12);
        AtomicReference<AcquireWaterTask> active = new AtomicReference<>(new AcquireWaterTask(
                searchOrigin, searchCheckpoint(searchOrigin, requestedWaypoint, 0, 0, 1, 0)));
        AtomicBoolean restarted = new AtomicBoolean();
        AtomicBoolean leftInitialPerception = new AtomicBoolean();
        AtomicBoolean observedWaterAfterMoving = new AtomicBoolean();
        AtomicReference<String> canonicalWaypoint = new AtomicReference<>();
        require(context, !ObservableWorldQuery.canObserveCell(bot, fixture.water()),
                "surface-water fixture exposed its source before SEARCH movement");
        active.get().start(bot);

        context.failIfEver(() -> {
            AcquireWaterTask task = active.get();
            task.tick(bot);
            Map<String, String> checkpoint = task.checkpoint();
            // The first live tick canonicalizes a restored SEARCH waypoint from its spiral cursor.
            canonicalWaypoint.compareAndSet(null, checkpoint.get("waypoint"));
            leftInitialPerception.set(leftInitialPerception.get()
                    || bot.blockPosition().getX() - fixture.start().getX() >= 12);
            observedWaterAfterMoving.set(observedWaterAfterMoving.get()
                    || ObservableWorldQuery.canObserveCell(bot, fixture.water()));

            if (!restarted.get() && bot.blockPosition().getX() - fixture.start().getX() >= 4) {
                String resolvedWaypoint = canonicalWaypoint.get();
                String restoredCursorWaypoint = encode(searchOrigin.east(12));
                require(context, "SEARCH".equals(checkpoint.get("phase"))
                                && resolvedWaypoint != null
                                && resolvedWaypoint.equals(checkpoint.get("waypoint"))
                                && InventoryAction.countItem(bot, Items.WATER_BUCKET) == 0,
                        "surface search did not preserve its unreached waypoint before restart: "
                                + checkpoint);
                task.cancel(bot, "gametest_surface_search_restart");
                AcquireWaterTask restored = new AcquireWaterTask(searchOrigin, checkpoint);
                restored.start(bot);
                // The live path may resolve its temporary observation stance beside the nominal
                // cursor post. Only the cursor post itself is durable across a restart.
                require(context, restored.state() == TaskState.RUNNING
                                && "SEARCH".equals(restored.checkpoint().get("phase"))
                                && restoredCursorWaypoint.equals(restored.checkpoint().get("waypoint")),
                        "surface SEARCH checkpoint did not resume its canonical cursor waypoint: "
                                + restored.checkpoint());
                active.set(restored);
                restarted.set(true);
                return;
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("surface SEARCH failed: "
                        + task.failureReason() + " checkpoint=" + checkpoint));
                return;
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, restarted.get() && leftInitialPerception.get()
                            && observedWaterAfterMoving.get(),
                    "surface SEARCH filled before physically leaving initial perception");
            require(context, InventoryAction.countItem(bot, Items.BUCKET) == 0
                            && InventoryAction.countItem(bot, Items.WATER_BUCKET) == 1,
                    "surface SEARCH did not perform the vanilla bucket exchange");
            require(context, bot.level().getFluidState(fixture.water()).isEmpty(),
                    "surface SEARCH did not drain its water source");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), fixture.name());
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:acquire_water_task_game_tests_schema_four_cursor_hundred_issues_and_resumes_waypoint_one_oh_one", maxTicks = 80)
    public void schemaFourCursorHundredIssuesAndResumesWaypointOneOhOne(
            GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(36, 4, 36));
        for (int lateral = -2; lateral <= 2; lateral++) {
            for (int distance = -2; distance <= 28; distance++) {
                BlockPos cell = start.offset(lateral, 0, -distance);
                world.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        BlockPos water = start.north(25);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            world.setBlock(water.relative(direction), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        world.setBlock(water, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        removeForeignWater(world, start, 32, water);

        String name = "WaterCursorHundredGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));

        BlockPos searchOrigin = start.offset(60, 0, -60);
        Map<String, String> boundary = new LinkedHashMap<>(searchCheckpoint(
                searchOrigin, start, 10_114, 10_114, 100, 81));
        boundary.put("direction", "3");
        boundary.put("leg_length", "10");
        boundary.put("step_in_leg", "0");
        boundary.put("repeated_legs", "1");
        boundary.put("grid_x", "-5");
        boundary.put("grid_z", "5");
        boundary.remove("waypoint");

        AtomicReference<AcquireWaterTask> active = new AtomicReference<>(
                new AcquireWaterTask(searchOrigin, boundary));
        AtomicBoolean restarted = new AtomicBoolean();
        active.get().start(bot);

        context.failIfEver(() -> {
            AcquireWaterTask task = active.get();
            task.tick(bot);
            Map<String, String> checkpoint = task.checkpoint();
            if (!restarted.get() && "101".equals(checkpoint.get("issued"))) {
                require(context, "-5".equals(checkpoint.get("grid_x"))
                                && "4".equals(checkpoint.get("grid_z"))
                                && encode(start.north(12)).equals(checkpoint.get("waypoint")),
                        "schema-4 cursor did not issue waypoint 101: " + checkpoint);
                task.cancel(bot, "gametest_waypoint_101_restart");
                AcquireWaterTask restored = new AcquireWaterTask(searchOrigin, checkpoint);
                restored.start(bot);
                require(context, restored.state() == TaskState.RUNNING
                                && "101".equals(restored.checkpoint().get("issued"))
                                && encode(start.north(12)).equals(restored.checkpoint().get("waypoint")),
                        "schema-4 waypoint 101 did not resume after restart: "
                                + restored.checkpoint());
                restarted.set(true);
                // This is the durable cursor boundary proof. Its successor legs are deliberately
                // outside this small fixture's observed runway; the separate surface-search test
                // covers physical travel and filling beyond initial perception.
                AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
                context.succeed();
                return;
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("schema-4 cursor search failed: "
                        + task.failureReason() + " checkpoint=" + checkpoint));
                return;
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, restarted.get(), "water was filled without resuming waypoint 101");
            require(context, InventoryAction.countItem(bot, Items.BUCKET) == 0
                            && InventoryAction.countItem(bot, Items.WATER_BUCKET) == 1
                            && world.getFluidState(water).isEmpty(),
                    "schema-4 cursor search did not physically fill from its water source");
            AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:acquire_water_task_game_tests_restored_hard_budget_remains_typed_and_decoder_valid", maxTicks = 20)
    public void restoredHardBudgetRemainsTypedAndDecoderValid(GameTestHelper context) {
        DryFixture fixture = spawnDryWaterSeeker(context, "WaterHardBudgetGT", false);
        BlockPos waypoint = fixture.start().east(12);
        AcquireWaterTask exhausted = new AcquireWaterTask(fixture.start(), searchCheckpoint(
                fixture.start(), waypoint, 30_000, 29_600, 1, 0));
        exhausted.start(fixture.bot());

        require(context, exhausted.state() == TaskState.FAILED,
                "restored hard budget received a fresh running window");
        require(context, exhausted.failureReason().startsWith("acquire_water_timeout"),
                "unexpected restored hard-budget failure: " + exhausted.failureReason());
        Map<String, String> terminal = exhausted.checkpoint();
        require(context, "30000".equals(terminal.get("budget_used"))
                        && "29600".equals(terminal.get("waypoint_started_budget"))
                        && "30000".equals(terminal.get("budget_limit"))
                        && "224".equals(terminal.get("waypoint_limit")),
                "terminal budget was not clamped into its own decoder domain: " + terminal);

        AcquireWaterTask restoredAgain = new AcquireWaterTask(fixture.start(), terminal);
        restoredAgain.start(fixture.bot());
        require(context, restoredAgain.state() == TaskState.FAILED
                        && restoredAgain.failureReason().startsWith("acquire_water_timeout"),
                "terminal checkpoint became invalid/fresh on second restore: "
                        + restoredAgain.state() + ":" + restoredAgain.failureReason());
        require(context, "30000".equals(restoredAgain.checkpoint().get("budget_used")),
                "second terminal restore lost the exhausted hard budget");

        // Schema 3 owned the former 12,000-tick authority. Upgrading the binary must not grant its
        // already exhausted checkpoint the current 30,000-tick window.
        Map<String, String> previous = new LinkedHashMap<>(searchCheckpoint(
                fixture.start(), waypoint, 12_000, 11_600, 1, 0));
        previous.put("schema", "3");
        previous.remove("waypoint_limit");
        previous.remove("budget_limit");
        AcquireWaterTask previousTerminal = new AcquireWaterTask(fixture.start(), previous);
        previousTerminal.start(fixture.bot());
        Map<String, String> previousMigrated = previousTerminal.checkpoint();
        require(context, previousTerminal.state() == TaskState.FAILED
                        && previousTerminal.failureReason().startsWith("acquire_water_timeout")
                        && "4".equals(previousMigrated.get("schema"))
                        && "12000".equals(previousMigrated.get("budget_used"))
                        && "12000".equals(previousMigrated.get("budget_limit"))
                        && "100".equals(previousMigrated.get("waypoint_limit")),
                "schema-3 hard terminal gained current authority: "
                        + previousTerminal.state() + ":" + previousTerminal.failureReason()
                        + " checkpoint=" + previousMigrated);
        AcquireWaterTask previousAgain = new AcquireWaterTask(
                fixture.start(), previousMigrated);
        previousAgain.start(fixture.bot());
        require(context, previousAgain.state() == TaskState.FAILED
                        && previousAgain.failureReason().startsWith("acquire_water_timeout")
                        && "12000".equals(previousAgain.checkpoint().get("budget_limit")),
                "migrated schema-3 hard terminal restarted with fresh authority");

        // Schema 2 wrote the first exhausted task at LEGACY_MAX_ELAPSED+1. Migrate that exact
        // historical boundary once, retaining its authority inside a schema-4 checkpoint.
        Map<String, String> legacy = new LinkedHashMap<>(searchCheckpoint(
                fixture.start(), waypoint, 12_000, 12_000, 1, 0));
        legacy.put("schema", "2");
        legacy.put("budget_used", "12001");
        legacy.put("visited", legacy.remove("issued"));
        legacy.remove("reached");
        legacy.remove("waypoint_limit");
        legacy.remove("budget_limit");
        legacy.remove("waypoint_started_budget");
        legacy.remove("consecutive_unreachable");
        AcquireWaterTask migrated = new AcquireWaterTask(fixture.start(), legacy);
        migrated.start(fixture.bot());
        require(context, migrated.state() == TaskState.FAILED
                        && migrated.failureReason().startsWith("acquire_water_timeout")
                        && "4".equals(migrated.checkpoint().get("schema"))
                        && "true".equals(migrated.checkpoint().get("surface_exit"))
                        && "12000".equals(migrated.checkpoint().get("budget_used"))
                        && "12000".equals(migrated.checkpoint().get("budget_limit"))
                        && "100".equals(migrated.checkpoint().get("waypoint_limit")),
                "legacy MAX+1 terminal checkpoint did not migrate fail-closed: "
                        + migrated.state() + ":" + migrated.failureReason()
                        + " checkpoint=" + migrated.checkpoint());
        cleanupDry(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:acquire_water_task_game_tests_sealed_surface_fails_typed_without_burning_cursor", maxTicks = 240)
    public void sealedSurfaceFailsTypedWithoutBurningCursor(GameTestHelper context) {
        DryFixture fixture = spawnDryWaterSeeker(context, "WaterSealedRouteGT", true);
        BlockPos firstWaypoint = fixture.start().east(12);
        AtomicReference<AcquireWaterTask> active = new AtomicReference<>(
                new AcquireWaterTask(fixture.start(), searchCheckpoint(
                        fixture.start(), firstWaypoint, 0, 0, 1, 0)));
        AtomicBoolean restarted = new AtomicBoolean();
        active.get().start(fixture.bot());

        context.failIfEver(() -> {
            AcquireWaterTask task = active.get();
            task.tick(fixture.bot());
            Map<String, String> checkpoint = task.checkpoint();
            if (!restarted.get() && task.state() == TaskState.RUNNING
                    && "1".equals(checkpoint.get("consecutive_unreachable"))) {
                String anchor = checkpoint.get("unreachable_anchor");
                task.cancel(fixture.bot(), "gametest_unreachable_restart");
                AcquireWaterTask restored = new AcquireWaterTask(fixture.start(), checkpoint);
                restored.start(fixture.bot());
                Map<String, String> after = restored.checkpoint();
                require(context, restored.state() == TaskState.RUNNING
                                && "1".equals(after.get("consecutive_unreachable"))
                                && java.util.Objects.equals(anchor, after.get("unreachable_anchor")),
                        "restart erased the same-area unreachable ledger: before="
                                + checkpoint + " after=" + after);
                active.set(restored);
                restarted.set(true);
                return;
            }
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("sealed route ended unexpectedly: " + task.state()));
                return;
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, task.failureReason().startsWith(
                            "acquire_water_no_reachable_surface_route"),
                    "sealed route returned the wrong typed failure: " + task.failureReason());
            require(context, restarted.get(),
                    "sealed route failed without checkpoint restart coverage");
            require(context, Integer.parseInt(checkpoint.get("issued")) <= 3
                            && "0".equals(checkpoint.get("reached"))
                            && "3".equals(checkpoint.get("consecutive_unreachable")),
                    "sealed route burned cursor or invented observations: " + checkpoint);
            require(context, fixture.bot().blockPosition().equals(fixture.start()),
                    "sealed route moved out of its physical cage: " + fixture.bot().blockPosition());
            require(context, InventoryAction.countItem(fixture.bot(), Items.BUCKET) == 1,
                    "sealed-route failure consumed the empty bucket");

            AcquireWaterTask terminalRestore = new AcquireWaterTask(fixture.start(), checkpoint);
            terminalRestore.start(fixture.bot());
            require(context, terminalRestore.state() == TaskState.FAILED
                            && terminalRestore.failureReason().startsWith(
                            "acquire_water_no_reachable_surface_route"),
                    "terminal unreachable ledger restarted as fresh search");
            cleanupDry(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:acquire_water_task_game_tests_blocked_surface_sector_clears_only_local_ledger_across_restart", maxTicks = 120)
    public void blockedSurfaceSectorClearsOnlyLocalLedgerAcrossRestart(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(4, 4, 4));
        BlockPos blockedGoal = start.east(2);
        // Keep the fixture inside EMPTY_STRUCTURE's 8x8 footprint.  The bot has one factual
        // three-cell west runway, while the next
        // semantically valid spiral waypoint is a standable cell enclosed by a two-high ring.
        // This forces GOAL_UNREACHABLE without rewriting terrain across neighboring structures.
        for (int distance = 0; distance <= 3; distance++) {
            BlockPos cell = start.west(distance);
            world.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        world.setBlock(blockedGoal.below(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(blockedGoal, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(blockedGoal.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(blockedGoal.above(2),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            world.setBlock(blockedGoal.relative(direction),
                    Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(blockedGoal.relative(direction).above(),
                    Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }

        String name = "WaterBlockedSectorGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));

        // Waypoint eight in the square spiral is grid=(1,-1).  Offset the persisted origin so
        // that this real cursor state resolves to the compact enclosed goal above.
        BlockPos searchOrigin = blockedGoal.offset(-12, 0, 12);
        Map<String, String> impossible = new LinkedHashMap<>(searchCheckpoint(
                searchOrigin, blockedGoal, 100, 80, 6, 5));
        impossible.put("direction", "0");
        impossible.put("leg_length", "3");
        impossible.put("step_in_leg", "2");
        impossible.put("repeated_legs", "0");
        impossible.put("grid_x", "1");
        impossible.put("grid_z", "-1");
        impossible.put("path_attempts", "3");
        impossible.put("consecutive_unreachable", "3");
        impossible.put("unreachable_anchor", encode(start));
        AcquireWaterTask rejected = new AcquireWaterTask(searchOrigin, impossible);
        rejected.start(bot);
        require(context, rejected.state() == TaskState.FAILED
                        && "acquire_water_invalid_checkpoint".equals(rejected.failureReason())
                        && rejected.checkpoint().isEmpty(),
                "decoder accepted an impossible unreachable ledger");

        Map<String, String> terminal = new LinkedHashMap<>(impossible);
        terminal.put("issued", "8");
        terminal.put("reached", "5");
        AcquireWaterTask recoveredRestore = new AcquireWaterTask(searchOrigin, terminal);
        recoveredRestore.start(bot);
        Map<String, String> recovered = recoveredRestore.checkpoint();
        require(context, recoveredRestore.state() == TaskState.RUNNING
                        && "100".equals(recovered.get("budget_used"))
                        && "8".equals(recovered.get("issued"))
                        && "5".equals(recovered.get("reached"))
                        && "0".equals(recovered.get("direction"))
                        && "3".equals(recovered.get("leg_length"))
                        && "2".equals(recovered.get("step_in_leg"))
                        && "1".equals(recovered.get("grid_x"))
                        && "-1".equals(recovered.get("grid_z"))
                        && "0".equals(recovered.get("path_attempts"))
                        && "0".equals(recovered.get("consecutive_unreachable"))
                        && !recovered.containsKey("waypoint")
                        && !recovered.containsKey("unreachable_anchor"),
                "restored blocked sector changed durable cursor/budget authority: "
                        + recoveredRestore.state() + ":" + recoveredRestore.failureReason()
                        + " checkpoint=" + recovered);
        recoveredRestore.cancel(bot, "gametest_recovered_sector_second_restore");
        AcquireWaterTask recoveredAgain = new AcquireWaterTask(searchOrigin, recovered);
        recoveredAgain.start(bot);
        require(context, recoveredAgain.state() == TaskState.RUNNING
                        && recovered.equals(recoveredAgain.checkpoint()),
                "recovered sector flipped across a second restore: "
                        + recoveredAgain.state() + ":" + recoveredAgain.failureReason());
        recoveredAgain.cancel(bot, "gametest_recovered_sector_live_boundary");

        // A legitimate live state after waypoints six and seven both failed and the enclosed
        // eighth goal has failed A* twice.  Its third failed attempt must clear only the local
        // consecutive ledger and preserve every durable expedition bound.
        Map<String, String> cursor = new LinkedHashMap<>(searchCheckpoint(
                searchOrigin, blockedGoal, 120, 100, 8, 5));
        cursor.put("direction", "0");
        cursor.put("leg_length", "3");
        cursor.put("step_in_leg", "2");
        cursor.put("repeated_legs", "0");
        cursor.put("grid_x", "1");
        cursor.put("grid_z", "-1");
        cursor.put("path_attempts", "2");
        cursor.put("consecutive_unreachable", "2");
        cursor.put("unreachable_anchor", encode(start));

        AcquireWaterTask active = new AcquireWaterTask(searchOrigin, cursor);
        active.start(bot);
        context.failIfEver(() -> {
            active.tick(bot);
            Map<String, String> checkpoint = active.checkpoint();
            if (active.state() == TaskState.FAILED || active.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("blocked sector ended the surface search: "
                        + active.state() + ":" + active.failureReason()
                        + " checkpoint=" + checkpoint));
                return;
            }
            if (!"8".equals(checkpoint.get("issued"))
                    || !"0".equals(checkpoint.get("consecutive_unreachable"))
                    || checkpoint.containsKey("waypoint")) {
                return;
            }
            require(context, "5".equals(checkpoint.get("reached"))
                            && "0".equals(checkpoint.get("direction"))
                            && "3".equals(checkpoint.get("leg_length"))
                            && "2".equals(checkpoint.get("step_in_leg"))
                            && "1".equals(checkpoint.get("grid_x"))
                            && "-1".equals(checkpoint.get("grid_z"))
                            && Integer.parseInt(checkpoint.get("budget_used")) >= 120
                            && !checkpoint.containsKey("unreachable_anchor")
                            && bot.blockPosition().equals(start),
                    "live blocked-sector recovery changed cursor, budget, or position: "
                            + checkpoint + " pos=" + bot.blockPosition());
            int used = Integer.parseInt(checkpoint.get("budget_used"));
            active.cancel(bot, "gametest_blocked_sector_restart");
            AcquireWaterTask restored = new AcquireWaterTask(searchOrigin, checkpoint);
            restored.start(bot);
            Map<String, String> after = restored.checkpoint();
            require(context, restored.state() == TaskState.RUNNING
                            && "8".equals(after.get("issued"))
                            && "5".equals(after.get("reached"))
                            && "1".equals(after.get("grid_x"))
                            && "-1".equals(after.get("grid_z"))
                            && Integer.parseInt(after.get("budget_used")) >= used,
                    "live blocked-sector recovery did not survive restart: before="
                            + checkpoint + " after=" + after);
            restored.cancel(bot, "gametest_blocked_sector_complete");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(maxTicks = 20)
    public void deepSkyLitRavineDoesNotCountAsSurfaceExit(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 4, 8));
        BlockPos surfaceAnchor = start.above(20);
        world.setBlock(start.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            world.setBlock(start.relative(direction),
                    Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }

        String name = "WaterDeepSkylightGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        require(context, world.canSeeSky(start), "fixture is not sky visible");

        AcquireWaterTask task = new AcquireWaterTask(surfaceAnchor);
        task.start(bot);
        task.tick(bot);
        Map<String, String> checkpoint = task.checkpoint();
        require(context, task.state() == TaskState.RUNNING,
                "deep skylight task stopped unexpectedly: " + task.failureReason());
        require(context, "RETURN_SURFACE".equals(checkpoint.get("phase"))
                        && "false".equals(checkpoint.get("surface_exit")),
                "deep skylight was incorrectly latched as surface: " + checkpoint);
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void nearAnchorSkyPocketWithoutLateralEgressKeepsAscending(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(20, 8, 20));
        // Exact anchor proximity exercises the historical bypass branch: it used to enter SEARCH
        // here without proving dry footing or a reusable lateral/uphill surface edge.
        BlockPos surfaceAnchor = start;

        world.setBlock(start.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos wall = start.relative(direction);
            world.setBlock(wall.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(wall, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(wall.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }

        String name = "WaterSkyPocketGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        require(context, world.canSeeSky(start), "stair pocket is not sky visible");

        AcquireWaterTask task = new AcquireWaterTask(surfaceAnchor);
        task.start(bot);
        task.tick(bot);
        Map<String, String> checkpoint = task.checkpoint();
        require(context, task.state() == TaskState.RUNNING,
                "sky pocket stopped unexpectedly: " + task.failureReason());
        require(context, "RETURN_SURFACE".equals(checkpoint.get("phase"))
                        && "false".equals(checkpoint.get("surface_exit")),
                "one-cell sky pocket was incorrectly latched as reusable surface: " + checkpoint);
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void malformedCheckpointFailsClosed(GameTestHelper context) {
        WaterFixture fixture = spawnWaterSeeker(context, "WaterCheckpointGT");
        AcquireWaterTask task = new AcquireWaterTask(fixture.start(), Map.of(
                "schema", "999",
                "surface_anchor", encode(fixture.start())));
        task.start(fixture.bot());

        require(context, task.state() == TaskState.FAILED,
                "malformed checkpoint must fail instead of restarting a fresh search");
        require(context, "acquire_water_invalid_checkpoint".equals(task.failureReason()),
                "unexpected malformed-checkpoint reason: " + task.failureReason());
        require(context, task.checkpoint().isEmpty(),
                "invalid water restore published an invented successor checkpoint");
        require(context, InventoryAction.countItem(fixture.bot(), Items.BUCKET) == 1,
                "failed restore mutated the inventory");
        AIPlayerManager.INSTANCE.despawn(fixture.bot().level().getServer(), fixture.name());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void schemaThreeSearchWithoutSurfaceExitFailsClosed(GameTestHelper context) {
        WaterFixture fixture = spawnWaterSeeker(context, "WaterFalseSurfaceLatchGT");
        Map<String, String> forged = new LinkedHashMap<>(searchCheckpoint(
                fixture.start(), fixture.start().east(12), 20, 10, 1, 0));
        forged.put("schema", "3");
        forged.remove("waypoint_limit");
        forged.remove("budget_limit");
        forged.put("surface_exit", "false");

        AcquireWaterTask task = new AcquireWaterTask(fixture.start(), forged);
        task.start(fixture.bot());

        require(context, task.state() == TaskState.FAILED,
                "schema-3 SEARCH with a false surface latch must fail closed");
        require(context, "acquire_water_invalid_checkpoint".equals(task.failureReason()),
                "unexpected contradictory-checkpoint reason: " + task.failureReason());
        require(context, task.checkpoint().isEmpty(),
                "contradictory water restore published an invented successor checkpoint");
        require(context, InventoryAction.countItem(fixture.bot(), Items.BUCKET) == 1,
                "failed contradictory restore mutated the empty bucket");
        AIPlayerManager.INSTANCE.despawn(fixture.bot().level().getServer(), fixture.name());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void schemaTwoSearchWithoutSurfaceExitFailsClosed(GameTestHelper context) {
        WaterFixture fixture = spawnWaterSeeker(context, "WaterLegacyFalseSurfaceLatchGT");
        Map<String, String> legacy = new LinkedHashMap<>(searchCheckpoint(
                fixture.start(), fixture.start().east(12), 20, 10, 1, 0));
        legacy.put("schema", "2");
        legacy.put("surface_exit", "false");
        legacy.put("visited", legacy.remove("issued"));
        legacy.remove("reached");
        legacy.remove("waypoint_limit");
        legacy.remove("budget_limit");
        legacy.remove("waypoint_started_budget");
        legacy.remove("consecutive_unreachable");

        AcquireWaterTask task = new AcquireWaterTask(fixture.start(), legacy);
        task.start(fixture.bot());

        require(context, task.state() == TaskState.FAILED,
                "schema-2 SEARCH with a false surface latch must fail closed");
        require(context, "acquire_water_invalid_checkpoint".equals(task.failureReason()),
                "unexpected legacy contradictory-checkpoint reason: " + task.failureReason());
        require(context, task.checkpoint().isEmpty(),
                "legacy false-latch restore published an invented migrated checkpoint");
        require(context, InventoryAction.countItem(fixture.bot(), Items.BUCKET) == 1,
                "failed legacy restore mutated the empty bucket");
        AIPlayerManager.INSTANCE.despawn(fixture.bot().level().getServer(), fixture.name());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void checkpointCursorAndAuthorityForgeryFailsClosed(GameTestHelper context) {
        DryFixture fixture = spawnDryWaterSeeker(context, "WaterCursorForgeryGT", false);
        BlockPos anchor = fixture.start();
        Map<String, String> valid = searchCheckpoint(
                anchor, anchor.east(12), 100, 80, 1, 0);

        Map<String, String> minGrid = new LinkedHashMap<>(valid);
        minGrid.put("grid_x", String.valueOf(Integer.MIN_VALUE));
        requireInvalidWaterCheckpoint(context, fixture, minGrid, "minimum integer grid");

        Map<String, String> mismatchedCursor = new LinkedHashMap<>(valid);
        mismatchedCursor.put("issued", "2");
        requireInvalidWaterCheckpoint(context, fixture, mismatchedCursor,
                "issued/cursor mismatch");

        Map<String, String> zeroWithWaypoint = new LinkedHashMap<>(valid);
        zeroWithWaypoint.put("issued", "0");
        zeroWithWaypoint.put("reached", "0");
        putSearchCursor(zeroWithWaypoint, 0);
        requireInvalidWaterCheckpoint(context, fixture, zeroWithWaypoint,
                "issued-zero pending waypoint");

        Map<String, String> malformedWaypoint = new LinkedHashMap<>(valid);
        malformedWaypoint.put("waypoint", "not,a,position");
        requireInvalidWaterCheckpoint(context, fixture, malformedWaypoint,
                "malformed pending waypoint");

        Map<String, String> legacyWaypointsWithCurrentBudget = new LinkedHashMap<>(valid);
        legacyWaypointsWithCurrentBudget.put("waypoint_limit", "100");
        requireInvalidWaterCheckpoint(context, fixture, legacyWaypointsWithCurrentBudget,
                "legacy waypoints/current budget hybrid");

        Map<String, String> currentWaypointsWithLegacyBudget = new LinkedHashMap<>(valid);
        currentWaypointsWithLegacyBudget.put("budget_limit", "12000");
        requireInvalidWaterCheckpoint(context, fixture, currentWaypointsWithLegacyBudget,
                "current waypoints/legacy budget hybrid");

        // A syntactically valid process-local resolved goal is not trusted as durable authority.
        // The restore remains valid, but its pending goal must be canonicalized from the cursor.
        Map<String, String> arbitraryResolvedGoal = new LinkedHashMap<>(valid);
        arbitraryResolvedGoal.put("waypoint", encode(anchor.offset(73, 4, -51)));
        AcquireWaterTask canonical = new AcquireWaterTask(anchor, arbitraryResolvedGoal);
        canonical.start(fixture.bot());
        require(context, canonical.state() == TaskState.RUNNING
                        && encode(anchor.east(12)).equals(
                        canonical.checkpoint().get("waypoint")),
                "pending waypoint coordinates were trusted instead of rebuilt: "
                        + canonical.state() + ":" + canonical.failureReason()
                        + " checkpoint=" + canonical.checkpoint());
        canonical.cancel(fixture.bot(), "gametest_cursor_canonicalized");
        cleanupDry(context, fixture);
    }

    @GameTest(maxTicks = 20)
    public void legacyRunningAndSchemaTwoTerminalRetainOldAuthority(GameTestHelper context) {
        DryFixture fixture = spawnDryWaterSeeker(context, "WaterLegacyAuthorityGT", false);
        BlockPos anchor = fixture.start();

        Map<String, String> schemaThree = new LinkedHashMap<>(searchCheckpoint(
                anchor, anchor.offset(91, 3, -47), 100, 80, 1, 0));
        schemaThree.put("schema", "3");
        schemaThree.remove("waypoint_limit");
        schemaThree.remove("budget_limit");
        AcquireWaterTask previousRunning = new AcquireWaterTask(anchor, schemaThree);
        previousRunning.start(fixture.bot());
        Map<String, String> previousMigrated = previousRunning.checkpoint();
        require(context, previousRunning.state() == TaskState.RUNNING
                        && "4".equals(previousMigrated.get("schema"))
                        && "100".equals(previousMigrated.get("waypoint_limit"))
                        && "12000".equals(previousMigrated.get("budget_limit"))
                        && encode(anchor.east(12)).equals(previousMigrated.get("waypoint")),
                "schema-3 running checkpoint lost its old authority or canonical cursor: "
                        + previousRunning.state() + ":" + previousRunning.failureReason()
                        + " checkpoint=" + previousMigrated);
        previousRunning.cancel(fixture.bot(), "gametest_schema_three_running_checked");

        Map<String, String> schemaTwoRunning = new LinkedHashMap<>(searchCheckpoint(
                anchor, anchor.offset(-45, 2, 63), 100, 80, 1, 0));
        schemaTwoRunning.put("schema", "2");
        schemaTwoRunning.put("visited", schemaTwoRunning.remove("issued"));
        schemaTwoRunning.remove("reached");
        schemaTwoRunning.remove("waypoint_limit");
        schemaTwoRunning.remove("budget_limit");
        schemaTwoRunning.remove("waypoint_started_budget");
        schemaTwoRunning.remove("consecutive_unreachable");
        AcquireWaterTask legacyRunning = new AcquireWaterTask(anchor, schemaTwoRunning);
        legacyRunning.start(fixture.bot());
        Map<String, String> legacyMigrated = legacyRunning.checkpoint();
        require(context, legacyRunning.state() == TaskState.RUNNING
                        && "4".equals(legacyMigrated.get("schema"))
                        && "100".equals(legacyMigrated.get("waypoint_limit"))
                        && "12000".equals(legacyMigrated.get("budget_limit"))
                        && "0".equals(legacyMigrated.get("reached"))
                        && encode(anchor.east(12)).equals(legacyMigrated.get("waypoint")),
                "schema-2 running checkpoint lost its old authority or canonical cursor: "
                        + legacyRunning.state() + ":" + legacyRunning.failureReason()
                        + " checkpoint=" + legacyMigrated);
        legacyRunning.cancel(fixture.bot(), "gametest_schema_two_running_checked");

        Map<String, String> schemaTwoTerminal = new LinkedHashMap<>(searchCheckpoint(
                anchor, anchor, 500, 500, 100, 0));
        schemaTwoTerminal.put("schema", "2");
        schemaTwoTerminal.put("visited", schemaTwoTerminal.remove("issued"));
        schemaTwoTerminal.remove("reached");
        schemaTwoTerminal.remove("waypoint");
        schemaTwoTerminal.remove("waypoint_limit");
        schemaTwoTerminal.remove("budget_limit");
        schemaTwoTerminal.remove("waypoint_started_budget");
        schemaTwoTerminal.remove("consecutive_unreachable");
        AcquireWaterTask legacyTerminal = new AcquireWaterTask(anchor, schemaTwoTerminal);
        legacyTerminal.start(fixture.bot());
        Map<String, String> terminalMigrated = legacyTerminal.checkpoint();
        require(context, legacyTerminal.state() == TaskState.FAILED
                        && legacyTerminal.failureReason().startsWith(
                        "acquire_water_search_exhausted")
                        && "100".equals(terminalMigrated.get("issued"))
                        && "100".equals(terminalMigrated.get("waypoint_limit"))
                        && "12000".equals(terminalMigrated.get("budget_limit"))
                        && !terminalMigrated.containsKey("waypoint"),
                "schema-2 terminal checkpoint was revived or rejected during migration: "
                        + legacyTerminal.state() + ":" + legacyTerminal.failureReason()
                        + " checkpoint=" + terminalMigrated);

        AcquireWaterTask terminalAgain = new AcquireWaterTask(anchor, terminalMigrated);
        terminalAgain.start(fixture.bot());
        require(context, terminalAgain.state() == TaskState.FAILED
                        && terminalAgain.failureReason().startsWith(
                        "acquire_water_search_exhausted")
                        && "100".equals(terminalAgain.checkpoint().get("waypoint_limit")),
                "migrated schema-2 terminal checkpoint restarted as running");
        cleanupDry(context, fixture);
    }

    private static void requireInvalidWaterCheckpoint(GameTestHelper context,
                                                      DryFixture fixture,
                                                      Map<String, String> checkpoint,
                                                      String label) {
        AcquireWaterTask task = new AcquireWaterTask(fixture.start(), checkpoint);
        task.start(fixture.bot());
        require(context, task.state() == TaskState.FAILED
                        && "acquire_water_invalid_checkpoint".equals(task.failureReason())
                        && task.checkpoint().isEmpty(),
                label + " checkpoint did not fail closed: "
                        + task.state() + ":" + task.failureReason()
                        + " checkpoint=" + task.checkpoint());
    }

    private static Map<String, String> searchCheckpoint(BlockPos anchor,
                                                         BlockPos waypoint,
                                                         int budgetUsed,
                                                         int waypointStartedBudget,
                                                         int issued,
                                                         int reached) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("schema", "4");
        values.put("phase", "SEARCH");
        values.put("surface_anchor", encode(anchor));
        values.put("search_origin", encode(anchor));
        values.put("surface_exit", "true");
        putSearchCursor(values, issued);
        values.put("issued", String.valueOf(issued));
        values.put("reached", String.valueOf(reached));
        values.put("waypoint_limit", "224");
        values.put("budget_limit", "30000");
        values.put("path_attempts", "0");
        values.put("budget_used", String.valueOf(budgetUsed));
        values.put("phase_started", "0");
        values.put("waypoint_started_budget", String.valueOf(waypointStartedBudget));
        values.put("consecutive_unreachable", "0");
        values.put("waypoint", encode(waypoint));
        return Map.copyOf(values);
    }

    private static void putSearchCursor(Map<String, String> values, int issued) {
        int direction = 0;
        int legLength = 1;
        int stepInLeg = 0;
        int repeatedLegs = 0;
        int gridX = 0;
        int gridZ = 0;
        for (int index = 0; index < issued; index++) {
            switch (direction) {
                case 0 -> gridX++;
                case 1 -> gridZ++;
                case 2 -> gridX--;
                default -> gridZ--;
            }
            stepInLeg++;
            if (stepInLeg >= legLength) {
                stepInLeg = 0;
                direction = (direction + 1) & 3;
                repeatedLegs++;
                if (repeatedLegs >= 2) {
                    repeatedLegs = 0;
                    legLength++;
                }
            }
        }
        values.put("direction", String.valueOf(direction));
        values.put("leg_length", String.valueOf(legLength));
        values.put("step_in_leg", String.valueOf(stepInLeg));
        values.put("repeated_legs", String.valueOf(repeatedLegs));
        values.put("grid_x", String.valueOf(gridX));
        values.put("grid_z", String.valueOf(gridZ));
    }

    private static Map<String, String> approachCheckpoint(BlockPos anchor,
                                                           BlockPos source,
                                                           BlockPos stand) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("schema", "4");
        values.put("phase", "APPROACH");
        values.put("surface_anchor", encode(anchor));
        values.put("search_origin", encode(anchor));
        values.put("surface_exit", "true");
        values.put("direction", "0");
        values.put("leg_length", "1");
        values.put("step_in_leg", "0");
        values.put("repeated_legs", "0");
        values.put("grid_x", "0");
        values.put("grid_z", "0");
        values.put("issued", "0");
        values.put("reached", "0");
        values.put("waypoint_limit", "224");
        values.put("budget_limit", "30000");
        values.put("path_attempts", "0");
        values.put("budget_used", "0");
        values.put("phase_started", "0");
        values.put("waypoint_started_budget", "0");
        values.put("consecutive_unreachable", "0");
        values.put("water_source", encode(source));
        values.put("water_stand", encode(stand));
        return Map.copyOf(values);
    }

    private static Map<String, String> returnSurfaceCheckpoint(BlockPos anchor,
                                                                int budgetUsed,
                                                                int pathAttempts) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("schema", "4");
        values.put("phase", "RETURN_SURFACE");
        values.put("surface_anchor", encode(anchor));
        values.put("search_origin", encode(anchor));
        values.put("surface_exit", "false");
        values.put("direction", "0");
        values.put("leg_length", "1");
        values.put("step_in_leg", "0");
        values.put("repeated_legs", "0");
        values.put("grid_x", "0");
        values.put("grid_z", "0");
        values.put("issued", "0");
        values.put("reached", "0");
        values.put("waypoint_limit", "224");
        values.put("budget_limit", "30000");
        values.put("path_attempts", String.valueOf(pathAttempts));
        values.put("budget_used", String.valueOf(budgetUsed));
        values.put("phase_started", "0");
        values.put("waypoint_started_budget", "0");
        values.put("consecutive_unreachable", "0");
        return Map.copyOf(values);
    }

    private static DryFixture spawnDryWaterSeeker(GameTestHelper context,
                                                   String name,
                                                   boolean sealed) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(24, 4, 24));
        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                world.setBlock(start.offset(dx, -1, dz),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        if (sealed) {
            // A 3x3 interior proves that one adjacent standable cell is not enough to attest a
            // reusable surface route.  The roof removes open-sky authority and the radius-two
            // double wall keeps the whole locally walkable room physically sealed.
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
                        world.setBlock(start.offset(dx, 0, dz),
                                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                        world.setBlock(start.offset(dx, 1, dz),
                                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                    } else {
                        world.setBlock(start.offset(dx, 2, dz),
                                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        return new DryFixture(name, bot, start.immutable());
    }

    private static void cleanupDry(GameTestHelper context, DryFixture fixture) {
        AIPlayerManager.INSTANCE.despawn(fixture.bot().level().getServer(), fixture.name());
        context.succeed();
    }

    private static WaterFixture spawnWaterSeeker(GameTestHelper context, String name) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(2, 2, 2));
        for (int dx = -3; dx <= 16; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                world.setBlock(start.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(start.offset(dx, 0, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(start.offset(dx, 1, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(start.offset(dx, 2, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        // A one-cell surface well is surrounded by a solid rim, so it cannot spread into the
        // walking lane. The west rim is the first +12 waypoint: from spawn it occludes the source;
        // after the bot physically climbs onto it, the source is visible and within bucket reach.
        BlockPos water = start.offset(13, 0, 0);
        world.setBlock(water.west(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(water.east(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(water.north(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(water.south(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(water, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        -90.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), -90.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        return new WaterFixture(name, bot, start.immutable(), water.immutable());
    }

    /** A dedicated SEARCH fixture whose source begins outside the normal 16-block observation radius. */
    private static WaterFixture spawnDistantWaterSeeker(GameTestHelper context, String name) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(2, 2, 2));
        for (int dx = -3; dx <= 24; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                world.setBlock(start.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(start.offset(dx, 0, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(start.offset(dx, 1, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(start.offset(dx, 2, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        BlockPos water = start.east(20);
        // Contain the source while the bot travels to the first cursor waypoint. The top of the
        // west rim remains the visible, reachable interaction stance once SEARCH arrives.
        world.setBlock(water.west(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(water.east(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(water.north(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(water.south(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(water, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        -90.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), -90.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        return new WaterFixture(name, bot, start.immutable(), water.immutable());
    }

    /** Removes every water block within {@code radius} horizontally (and 8 vertically) of {@code center} except {@code keep}. */
    private static void removeForeignWater(net.minecraft.server.level.ServerLevel world, BlockPos center, int radius, BlockPos keep) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -8; dy <= 8; dy++) {
                    BlockPos cell = center.offset(dx, dy, dz);
                    if (!cell.equals(keep) && world.hasChunkAt(cell)
                            && world.getFluidState(cell).is(FluidTags.WATER)) {
                        world.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }

    private static String encode(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private record WaterFixture(String name, AIPlayerEntity bot, BlockPos start, BlockPos water) {
    }

    private record DryFixture(String name, AIPlayerEntity bot, BlockPos start) {
    }
}

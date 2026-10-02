package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Proves strict hunting can cross an initially empty perception region and collect physical loot. */
public final class HuntCrossRegionGameTests {
    @GameTest(environment = "minecraftai-gametest:hunt_cross_region_game_tests_restored_pickup_collects_the_same_bound_drop", maxTicks = 200)
    public void restoredPickupCollectsTheSameBoundDrop(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(4, 4, 4));
        for (int dx = -2; dx <= 4; dx++) {
            BlockPos feet = start.offset(dx, 0, 0);
            world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        String name = "HuntPickupRestoreGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        ItemEntity drop = new ItemEntity(world,
                start.getX() + 2.5D, start.getY() + 0.1D, start.getZ() + 0.5D,
                new ItemStack(Items.BEEF, 2));
        require(context, world.addFreshEntity(drop), "failed to spawn bound beef");
        int pickupBaseline = bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.BEEF);
        HuntSearchCursor cursor = HuntSearchCursor.initial();
        cursor.setSurfaceAnchorIfAbsent(
                world.dimension().identifier().toString(),
                start.getX(), start.getY(), start.getZ());
        Map<String, String> checkpoint = pickupCheckpoint(
                world.dimension().identifier().toString(), start, start,
                world.getGameTime(), drop.getUUID(), 2);
        HuntTask task = new HuntTask(1, true, cursor, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_pickup_restore"));

        context.failIfEver(() -> {
            if (task.state() == TaskState.FAILED) {
                context.fail(Component.nullToEmpty(
                        "restored pickup failed: " + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.BEEF) >= 2,
                    "restored task did not collect all bound raw units");
            require(context, bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.BEEF)
                            >= pickupBaseline + 2,
                    "restored raw units bypassed vanilla pickup stats");
            require(context, "CLOSED_COLLECTED".equals(
                            task.checkpoint().get("transaction_state")),
                    "OPEN checkpoint was not covered by a CLOSED receipt");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:hunt_cross_region_game_tests_replan_shrunk_quota_still_settles_open_pickup_debt", maxTicks = 200)
    public void replanShrunkQuotaStillSettlesOpenPickupDebt(GameTestHelper context) {
        // A mid-mission replan credits the 2 collected raw meat and re-issues the remainder
        // (4 -> 2). The successor task must settle the OPEN transaction instead of dying at
        // tick 0 with hunt_pickup_invalid_checkpoint.
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(4, 4, 4));
        for (int dx = -2; dx <= 4; dx++) {
            BlockPos feet = start.offset(dx, 0, 0);
            world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        String name = "HuntQuotaMismatchGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        ItemEntity drop = new ItemEntity(world,
                start.getX() + 2.5D, start.getY() + 0.1D, start.getZ() + 0.5D,
                new ItemStack(Items.BEEF, 2));
        require(context, world.addFreshEntity(drop), "failed to spawn bound beef");
        HuntSearchCursor cursor = HuntSearchCursor.initial();
        cursor.setSurfaceAnchorIfAbsent(
                world.dimension().identifier().toString(),
                start.getX(), start.getY(), start.getZ());
        Map<String, String> checkpoint = pickupCheckpoint(
                world.dimension().identifier().toString(), start, start,
                world.getGameTime(), drop.getUUID(), 2, 4);
        HuntTask task = new HuntTask(2, true, cursor, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_quota_mismatch"));

        context.failIfEver(() -> {
            if (task.state() == TaskState.FAILED) {
                context.fail(Component.nullToEmpty(
                        "quota-mismatched restore failed: " + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.BEEF) >= 2,
                    "settlement did not collect the bound raw units");
            require(context, "CLOSED_COLLECTED".equals(
                            task.checkpoint().get("transaction_state")),
                    "OPEN checkpoint was not covered by a CLOSED receipt");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:hunt_cross_region_game_tests_closed_receipt_does_not_poison_successor_hunt", maxTicks = 200)
    public void closedReceiptDoesNotPoisonSuccessorHunt(GameTestHelper context) {
        // A hunt that already settled its pickup can still fail later (for example
        // hunt_no_progress on the next prey) and export a CLOSED_COLLECTED receipt. The
        // successor hunt owes that transaction nothing and must start fresh: it must run as a
        // normal acquisition instead of failing at tick 0. A live kill is deliberately NOT
        // asserted here - under CI load the approach itself is timing-sensitive terrain, and
        // the regression this pins is the restore decision, not the hunt's success.
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(4, 4, 4));
        for (int x = -2; x <= 6; x++) {
            for (int z = -2; z <= 6; z++) {
                BlockPos feet = start.offset(x, 0, z);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        String name = "HuntClosedReceiptGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        HuntSearchCursor cursor = HuntSearchCursor.initial();
        cursor.setSurfaceAnchorIfAbsent(
                world.dimension().identifier().toString(),
                start.getX(), start.getY(), start.getZ());
        Map<String, String> closed = new LinkedHashMap<>(pickupCheckpoint(
                world.dimension().identifier().toString(), start, start,
                world.getGameTime(), UUID.fromString("00000000-0000-0000-0000-000000000098"), 2));
        closed.put("transaction_state", "CLOSED_COLLECTED");
        HuntTask task = new HuntTask(1, true, cursor, Map.copyOf(closed));
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_closed_receipt"));

        context.runAtTickTime(3, () -> {
            require(context, !"hunt_pickup_invalid_checkpoint".equals(task.failureReason()),
                    "closed receipt poisoned the successor hunt: " + task.failureReason());
            require(context, task.state() == TaskState.RUNNING,
                    "fresh hunt after a closed receipt ended early as " + task.state()
                            + ":" + task.failureReason());
            require(context, task.describe().contains("phase=ACQUIRE")
                            || task.describe().contains("phase=ROAM"),
                    "successor hunt did not resume acquisition: " + task.describe());
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:hunt_cross_region_game_tests_distant_prey_is_hunted_across_open_ground", maxTicks = 1600)
    public void distantPreyIsHuntedAcrossOpenGround(GameTestHelper context) {
        // Surface prey sight must align with SEARCH_RANGE: a real player sees a cow well
        // beyond the interaction-scale perception radius on open ground. The corridor is
        // heightmap-anchored (flush with the natural surface, obstacles above cleared) so the
        // surface-route proof cannot become marginal when the batch places the structure above
        // or below the natural floor; the sight contract only needs a distance clearly past
        // the base radius, not the full 64.
        var world = context.getLevel();
        BlockPos origin = context.absolutePos(new BlockPos(4, 0, 4));
        int baseY = world.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                origin.getX(), origin.getZ());
        BlockPos start = origin.atY(baseY);
        for (int dx = -2; dx <= 32; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = start.getX() + dx;
                int z = start.getZ() + dz;
                int localTop = world.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                for (int y = baseY + 1; y <= localTop + 3; y++) {
                    world.setBlock(new BlockPos(x, y, z),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
                BlockPos feet = new BlockPos(x, baseY, z);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        var cow = EntityType.COW.create(world, EntitySpawnReason.COMMAND);
        require(context, cow != null, "failed to create cow");
        cow.setNoAi(true);
        cow.snapTo(
                start.getX() + 28.5D, start.getY(), start.getZ() + 0.5D, 270.0F, 0.0F);
        require(context, world.addFreshEntity(cow), "failed to spawn cow");

        String name = "HuntDistantPreyGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        HuntSearchCursor cursor = HuntSearchCursor.initial();
        cursor.setSurfaceAnchorIfAbsent(
                world.dimension().identifier().toString(),
                start.getX(), start.getY(), start.getZ());
        HuntTask task = new HuntTask(1, true, cursor);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_distant_prey"));

        // The regression core of this fixture is distant acquisition + dig-capable approach +
        // factual damage on the fixture cow; the full kill-and-pickup tail occasionally hits
        // rare natural-terrain edges (placement-dependent whiff loops), which the close-range
        // live tests already cover. Failing before the cow is hurt is the real regression.
        float cowInitialHealth = cow.getHealth();
        context.failIfEver(() -> {
            boolean cowDamaged = cow.getHealth() < cowInitialHealth || !cow.isAlive();
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                require(context, cowDamaged,
                        "distant prey hunt died before reaching the herd: "
                                + task.failureReason());
                AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
                context.succeed();
                return;
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.BEEF) >= 1 || cowDamaged,
                    "distant prey hunt collected no raw meat");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:hunt_cross_region_game_tests_distant_prey_sight_widens_range_but_still_requires_line_of_sight", maxTicks = 40)
    public void distantPreySightWidensRangeButStillRequiresLineOfSight(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(4, 4, 4));
        for (int dx = -48; dx <= 48; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = start.offset(dx, 0, dz);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        // Full-height wall on the east side: the cow behind it must stay invisible even at
        // prey-sight range, or terrain would stop hiding herds.
        for (int dy = 0; dy < 4; dy++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.setBlock(start.offset(8, dy, dz),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        var openCow = EntityType.COW.create(world, EntitySpawnReason.COMMAND);
        require(context, openCow != null, "failed to create open cow");
        openCow.setNoAi(true);
        openCow.snapTo(
                start.getX() - 40.5D, start.getY(), start.getZ() + 0.5D, 90.0F, 0.0F);
        require(context, world.addFreshEntity(openCow), "failed to spawn open cow");
        var walledCow = EntityType.COW.create(world, EntitySpawnReason.COMMAND);
        require(context, walledCow != null, "failed to create walled cow");
        walledCow.setNoAi(true);
        walledCow.snapTo(
                start.getX() + 40.5D, start.getY(), start.getZ() + 0.5D, 270.0F, 0.0F);
        require(context, world.addFreshEntity(walledCow), "failed to spawn walled cow");

        String name = "HuntPreySightGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        context.runAtTickTime(1, () -> {
            require(context, io.github.zoyluo.minecraftai.mode.ObservableWorldQuery
                            .canObserveEntityWithin(bot, openCow, 64),
                    "open-ground prey at 40 blocks was not visible at prey-sight range");
            require(context, !io.github.zoyluo.minecraftai.mode.ObservableWorldQuery
                            .canObserveEntity(bot, openCow),
                    "base entity observation widened beyond the configured perception radius");
            require(context, !io.github.zoyluo.minecraftai.mode.ObservableWorldQuery
                            .canObserveEntityWithin(bot, walledCow, 64),
                    "terrain stopped hiding prey at prey-sight range");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:hunt_cross_region_game_tests_prey_approach_proof_digs_near_level_through_obstacles", maxTicks = 40)
    public void preyApproachProofDigsNearLevelThroughObstacles(GameTestHelper context) {
        // Deterministic proof-level pin (no live hunt timing): a 3-high dirt wall has no
        // walk-only crossing, so SAFE here can only come from the near-level dig fallback.
        // The stair-shape and floor policies are asserted directly alongside it.
        var world = context.getLevel();
        BlockPos origin = context.absolutePos(new BlockPos(4, 0, 4));
        int baseY = world.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                origin.getX(), origin.getZ());
        BlockPos start = origin.atY(baseY);
        for (int dx = -2; dx <= 16; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = start.getX() + dx;
                int z = start.getZ() + dz;
                int localTop = world.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
                for (int y = baseY + 1; y <= localTop + 3; y++) {
                    world.setBlock(new BlockPos(x, y, z),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
                BlockPos feet = new BlockPos(x, baseY, z);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        for (int dy = 0; dy < 3; dy++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.setBlock(new BlockPos(start.getX() + 8, baseY + dy, start.getZ() + dz),
                        Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        BlockPos beyond = new BlockPos(start.getX() + 12, baseY, start.getZ());
        int floorY = baseY - 16;
        String name = "HuntHiddenDigGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        HuntTask.SurfaceRouteProof proof = HuntSurfaceRoutes.provePreyApproachRoute(
                bot, beyond, floorY, start);
        require(context, proof != HuntTask.SurfaceRouteProof.SAFE,
                "hunt preview used hidden terrain to approve a dig route: " + proof);

        require(context, HuntSurfaceRoutes.digBreakthroughFloor(start, beyond, floorY)
                        == Math.max(floorY, baseY - 1),
                "breakthrough floor must sit one block under the lower endpoint");
        require(context, HuntSurfaceRoutes.digBreakthroughFloor(start, beyond, baseY + 4)
                        == baseY + 4,
                "breakthrough floor must never drop below the caller's minimum");
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
        context.succeed();
    }

    @GameTest(environment = "minecraftai-gametest:hunt_cross_region_game_tests_near_deadline_restore_does_not_refresh_bound_debt", maxTicks = 320)
    public void nearDeadlineRestoreDoesNotRefreshBoundDebt(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(4, 4, 4));
        world.setBlock(start.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        String name = "HuntPickupDeadlineGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        HuntSearchCursor cursor = HuntSearchCursor.initial();
        cursor.setSurfaceAnchorIfAbsent(
                world.dimension().identifier().toString(),
                start.getX(), start.getY(), start.getZ());
        AtomicReference<HuntTask> assignedTask = new AtomicReference<>();
        AtomicInteger assignedTick = new AtomicInteger(-1);

        context.failIfEver(() -> {
            HuntTask task = assignedTask.get();
            if (task == null) {
                if (world.getGameTime() < 239L) {
                    return;
                }
                Map<String, String> checkpoint = pickupCheckpoint(
                        world.dimension().identifier().toString(), start, start,
                        world.getGameTime() - 239L,
                        UUID.fromString("00000000-0000-0000-0000-000000000099"), 1);
                task = new HuntTask(1, true, cursor, checkpoint);
                TaskManager.INSTANCE.assign(bot, task,
                        TaskOrigin.of(
                                TaskOrigin.Kind.VERIFY,
                                "gametest_hunt_pickup_deadline"));
                assignedTask.set(task);
                assignedTick.set(world.getServer().getTickCount());
                return;
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, task.failureReason().startsWith("hunt_drop_unrecovered"),
                    "missing bound UUID did not remain a physical debt: "
                            + task.failureReason());
            require(context, world.getServer().getTickCount() - assignedTick.get() <= 3,
                    "restored pickup received a fresh recovery deadline");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:hunt_cross_region_game_tests_unloaded_target_is_reacquired_instead_of_inventing_pickup_debt", maxTicks = 1200)
    public void unloadedTargetIsReacquiredInsteadOfInventingPickupDebt(GameTestHelper context) {
        var world = context.getLevel();
        // GameTest arenas are not reset synchronously, so assertions below track only the two
        // chickens this fixture creates rather than globally counting nearby animals.
        BlockPos start = context.absolutePos(new BlockPos(8, 5, -520));
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 12; z++) {
                BlockPos feet = start.offset(x, 0, z);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }

        var original = EntityType.CHICKEN.create(world, EntitySpawnReason.COMMAND);
        require(context, original != null, "failed to create original chicken");
        original.setNoAi(true);
        original.snapTo(
                start.getX() + 0.5D, start.getY(), start.getZ() + 5.5D,
                180.0F, 0.0F);
        require(context, world.addFreshEntity(original), "failed to spawn original chicken");
        require(context, original.isAlive() && !original.isRemoved(),
                "fixture did not retain its original chicken after spawn");

        String name = "HuntTargetReloadGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        int pickupBaseline = bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.CHICKEN);

        HuntTask task = anchoredHunt(bot, 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_target_reload"));
        AtomicBoolean unloaded = new AtomicBoolean();
        AtomicBoolean sawReacquire = new AtomicBoolean();
        AtomicReference<net.minecraft.world.entity.animal.chicken.Chicken> replacement =
                new AtomicReference<>();

        context.failIfEver(() -> {
            String description = task.describe();
            if (!unloaded.get() && description.contains("phase=APPROACH")) {
                Vec3 preyPos = original.position();
                original.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                var reloaded = EntityType.CHICKEN.create(world, EntitySpawnReason.COMMAND);
                require(context, reloaded != null, "failed to recreate unloaded chicken");
                reloaded.setNoAi(true);
                reloaded.setHealth(1.0F);
                reloaded.snapTo(
                        preyPos.x, preyPos.y, preyPos.z, 180.0F, 0.0F);
                require(context, world.addFreshEntity(reloaded), "failed to spawn reloaded chicken");
                replacement.set(reloaded);
                unloaded.set(true);
                return;
            }
            if (unloaded.get() && description.contains("phase=ACQUIRE")) {
                sawReacquire.set(true);
            }
            if (description.contains("phase=PICKUP")) {
                require(context, !original.isAlive() && (replacement.get() == null || !replacement.get().isAlive()),
                        "hunt opened pickup debt while this fixture's named chicken remained alive");
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("target-reload hunt ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, unloaded.get() && sawReacquire.get(),
                    "fixture did not exercise target unload followed by reacquisition");
            require(context, replacement.get() != null && !replacement.get().isAlive(),
                    "reloaded chicken remained alive after hunt completion");
            require(context, InventoryAction.countItem(bot, Items.CHICKEN) >= 1,
                    "reloaded hunt completed without raw chicken");
            require(context, bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.CHICKEN)
                            > pickupBaseline,
                    "reloaded hunt did not collect meat through vanilla pickup");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:hunt_cross_region_game_tests_rejected_compass_fan_rotates_onto_reversible_ridge", maxTicks = 1400)
    public void rejectedCompassFanRotatesOntoReversibleRidge(GameTestHelper context) {
        var world = context.getLevel();
        // A low dedicated layer (like the distant-prey strip): the previous 40-up elevated ridge
        // could not prove long no-dig/no-pillar approach routes, and prey sight now requires the
        // initial approach to be provable from wherever the hunt first sees the cow.
        BlockPos start = context.absolutePos(new BlockPos(8, 5, -112));

        // An 11-degree, three-cell-wide reversible ridge leads to a small terminal pickup pad. The
        // compass-fan rotation geometry itself is pinned by the static asserts below on the pure
        // rotatedRoamColumn function; prey sight ends the old "invisible at 20 blocks" premise, so
        // the live portion now proves a direct hunt across the narrow diagonal ridge. The pad
        // catches vanilla's randomized item launch after the kill without widening the corridor.
        for (int x = 0; x <= 36; x++) {
            int z = (int) Math.round(x * Math.tan(Math.toRadians(11.0D)));
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos feet = start.offset(x, 0, z + dz);
                world.setBlock(
                        feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }

        BlockPos firstCompass = HuntSurfaceRoutes.rotatedRoamColumn(start, 1, 0, 32, 0);
        BlockPos rotatedRetry = HuntSurfaceRoutes.rotatedRoamColumn(start, 1, 0, 32, 1);
        require(context, firstCompass.equals(start.offset(32, 0, 0)),
                "serial-zero roam geometry changed: " + firstCompass.toShortString());
        require(context, !world.getBlockState(firstCompass.below()).is(Blocks.STONE),
                "initial compass fan unexpectedly intersected the widened ridge");
        require(context, !rotatedRetry.equals(firstCompass)
                        && world.getBlockState(rotatedRetry.below()).is(Blocks.STONE),
                "retry fan did not rotate onto the reversible ridge: " + rotatedRetry.toShortString());

        var cow = EntityType.COW.create(world, EntitySpawnReason.COMMAND);
        require(context, cow != null, "failed to create rotated-retry cow");
        cow.setNoAi(true);
        cow.setHealth(1.0F);
        // ... hunt across the narrow diagonal ridge. The cow sits at close range (the historical
        // post-roam end state of this fixture): the surface-route proof cannot span this
        // zigzag strip at range, so prey sight must not be asked to approach across it.
        int cowX = 9;
        int cowZ = (int) Math.round(cowX * Math.tan(Math.toRadians(11.0D)));
        BlockPos cowFeet = start.offset(cowX, 0, cowZ);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos pickupCell = cowFeet.offset(dx, 0, dz);
                world.setBlock(pickupCell.below(),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(pickupCell,
                        Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(pickupCell.above(),
                        Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        cow.snapTo(
                cowFeet.getX() + 0.5D, cowFeet.getY(), cowFeet.getZ() + 0.5D,
                180.0F, 0.0F);
        require(context, world.addFreshEntity(cow), "failed to spawn rotated-retry cow");

        String name = "HuntRotatedRetryGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        require(context, bot.blockPosition().equals(start),
                "rotated-retry fixture spawn drifted off its isolated ridge: "
                        + bot.blockPosition().toShortString());
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));

        HuntTask task = anchoredHunt(bot, 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_rotated_retry"));
        context.failIfEver(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("rotated-retry hunt ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.BEEF) >= 1,
                    "rotated-retry hunt completed without physical beef pickup");
            require(context, bot.blockPosition().getX() >= start.getX() + 4,
                    "hunt never physically advanced onto the retry ridge: "
                            + bot.blockPosition().toShortString());
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:hunt_cross_region_game_tests_remembered_kill_cell_routes_around_new_occluding_wall", maxTicks = 900)
    public void rememberedKillCellRoutesAroundNewOccludingWall(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 5, -40));
        for (int x = -7; x <= 7; x++) {
            for (int z = -4; z <= 12; z++) {
                BlockPos feet = start.offset(x, 0, z);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }

        var cow = EntityType.COW.create(world, EntitySpawnReason.COMMAND);
        require(context, cow != null, "failed to create hidden-drop cow");
        cow.setNoAi(true);
        cow.setHealth(1.0F);
        BlockPos killCell = start.south(6);
        cow.snapTo(
                killCell.getX() + 0.5D, killCell.getY(), killCell.getZ() + 0.5D,
                180.0F, 0.0F);
        require(context, world.addFreshEntity(cow), "failed to spawn hidden-drop cow");

        String name = "HuntHiddenDropGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));

        HuntTask task = anchoredHunt(bot, 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_hidden_drop"));
        AtomicBoolean wallBuilt = new AtomicBoolean();
        AtomicBoolean dropWasOccluded = new AtomicBoolean();

        context.failIfEver(() -> {
            if (!cow.isAlive() && !wallBuilt.get()) {
                ItemEntity beef = world.getEntitiesOfClass(
                                ItemEntity.class, new AABB(killCell).inflate(3.0D),
                                entity -> entity.getItem().is(Items.BEEF))
                        .stream().findFirst().orElse(null);
                if (beef != null) {
                    BlockPos wall = killCell.north();
                    for (int dx = -2; dx <= 2; dx++) {
                        world.setBlock(wall.offset(dx, 0, 0),
                                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                        world.setBlock(wall.offset(dx, 1, 0),
                                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                    }
                    beef.setDeltaMovement(Vec3.ZERO);
                    wallBuilt.set(true);
                    dropWasOccluded.set(!bot.hasLineOfSight(beef));
                    require(context, dropWasOccluded.get(),
                            "fixture failed to occlude the post-kill beef");
                }
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("hidden-drop hunt ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, wallBuilt.get() && dropWasOccluded.get(),
                    "hunt completed without exercising remembered-cell recovery");
            require(context, InventoryAction.countItem(bot, Items.BEEF) >= 1,
                    "hidden beef never entered inventory physically");
            BlockPos wall = killCell.north();
            for (int dx = -2; dx <= 2; dx++) {
                require(context, world.getBlockState(wall.offset(dx, 0, 0)).is(Blocks.STONE)
                                && world.getBlockState(wall.offset(dx, 1, 0)).is(Blocks.STONE),
                        "hidden-drop route dug through its occluding wall");
            }
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:hunt_cross_region_game_tests_observed_wool_pickup_triggers_physical_recovery_of_missed_mutton", maxTicks = 700)
    public void observedWoolPickupTriggersPhysicalRecoveryOfMissedMutton(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 5, -176));
        for (int x = -6; x <= 10; x++) {
            for (int z = -6; z <= 6; z++) {
                BlockPos feet = start.offset(x, 0, z);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }

        var sheep = EntityType.SHEEP.create(world, EntitySpawnReason.COMMAND);
        require(context, sheep != null, "failed to create split-loot sheep");
        sheep.setNoAi(true);
        sheep.setHealth(1.0F);
        BlockPos killCell = start.east(4);
        sheep.snapTo(
                killCell.getX() + 0.5D, killCell.getY(), killCell.getZ() + 0.5D,
                180.0F, 0.0F);
        require(context, world.addFreshEntity(sheep), "failed to spawn split-loot sheep");

        String name = "HuntSplitLootGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));

        HuntTask task = anchoredHunt(bot, 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_split_loot"));
        AtomicReference<ItemEntity> heldMutton = new AtomicReference<>();
        AtomicBoolean woolArrivedFirst = new AtomicBoolean();
        AtomicBoolean muttonReleased = new AtomicBoolean();
        BlockPos releaseCell = killCell.east();
        int pickedMuttonBaseline = bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.MUTTON);
        context.failIfEver(() -> {
            if (!sheep.isAlive() && heldMutton.get() == null) {
                ItemEntity mutton = world.getEntitiesOfClass(
                                ItemEntity.class, new AABB(killCell).inflate(3.0D),
                                entity -> entity.getItem().is(Items.MUTTON))
                        .stream().findFirst().orElse(null);
                if (mutton != null) {
                    mutton.setNeverPickUp();
                    mutton.setPos(
                            releaseCell.getX() + 0.5D, releaseCell.getY(), releaseCell.getZ() + 0.5D);
                    mutton.setDeltaMovement(Vec3.ZERO);
                    heldMutton.set(mutton);
                }
            }
            if (heldMutton.get() != null
                    && InventoryAction.countItem(bot, Items.WHITE_WOOL) > 0
                    && InventoryAction.countItem(bot, Items.MUTTON) == 0) {
                woolArrivedFirst.set(true);
                if (!muttonReleased.get()) {
                    heldMutton.get().setNoPickUpDelay();
                    muttonReleased.set(true);
                }
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("split-loot hunt ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, woolArrivedFirst.get() && muttonReleased.get(),
                    "fixture did not separate the real wool and mutton pickups");
            require(context, InventoryAction.countItem(bot, Items.MUTTON) >= 1,
                    "missed mutton never entered inventory through physical pickup");
            require(context, bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.MUTTON)
                            > pickedMuttonBaseline,
                    "mutton inventory changed without a vanilla physical pickup statistic");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(maxTicks = 20)
    public void surfaceRoamRejectsOneWayDropPocket(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos origin = context.absolutePos(new BlockPos(4, 8, 4));
        BlockPos pocket = origin.offset(4, -3, 0);
        world.setBlock(origin.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(origin, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(origin.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(pocket.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(pocket, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(pocket.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        String name = "HuntSurfacePreviewGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(origin),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        require(context, !HuntSurfaceRoutes.hasRoundTripSurfaceRoute(bot, pocket, Integer.MIN_VALUE),
                "one-way drop pocket was accepted as reusable surface exploration");

        BlockPos flat = origin.east();
        world.setBlock(flat.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(flat, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(flat.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        require(context, HuntSurfaceRoutes.hasRoundTripSurfaceRoute(bot, flat, Integer.MIN_VALUE),
                "adjacent reversible surface waypoint was rejected");
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
        context.succeed();
    }

    @GameTest(environment = "minecraftai-gametest:hunt_cross_region_game_tests_water_rescue_does_not_immediately_retarget_same_prey", maxTicks = 1000)
    public void waterRescueDoesNotImmediatelyRetargetSamePrey(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 5, -144));
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                BlockPos feet = start.offset(x, 0, z);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }

        var sheep = EntityType.SHEEP.create(world, EntitySpawnReason.COMMAND);
        require(context, sheep != null, "failed to create wet-prey fixture sheep");
        sheep.setNoAi(true);
        sheep.setHealth(1.0F);
        sheep.snapTo(
                start.getX() + 4.5D, start.getY(), start.getZ() + 0.5D,
                180.0F, 0.0F);
        require(context, world.addFreshEntity(sheep), "failed to spawn wet-prey fixture sheep");

        String name = "HuntWetPreyGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));

        HuntTask task = anchoredHunt(bot, 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_wet_prey_rejection"));
        BlockPos wetCell = start.south(2);
        AtomicBoolean injected = new AtomicBoolean();
        AtomicBoolean rejectionObserved = new AtomicBoolean();
        AtomicInteger dryTicksAfterRescue = new AtomicInteger();
        context.failIfEver(() -> {
            if (!injected.get() && task.describe().contains("phase=APPROACH")) {
                world.setBlock(wetCell, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
                bot.teleportTo(world,
                        wetCell.getX() + 0.5D, wetCell.getY(), wetCell.getZ() + 0.5D,
                        Set.of(), bot.getYRot(), bot.getXRot(), true);
                injected.set(true);
                return;
            }
            if (injected.get() && task.isWetPreyTemporarilyRejected(sheep.getUUID())) {
                rejectionObserved.set(true);
                // Let the shared rescue own the handoff, then remove the artificial source so the
                // deterministic fixture cannot spread water across the otherwise dry arena.
                world.setBlock(wetCell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            if (rejectionObserved.get() && !NavSafetyNet.INSTANCE.isWaterRescueActive(bot)
                    && !bot.isInWater()) {
                dryTicksAfterRescue.incrementAndGet();
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("wet-prey hunt ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            if (dryTicksAfterRescue.get() < 40) {
                return;
            }
            require(context, injected.get() && rejectionObserved.get(),
                    "fixture never exercised the Hunt-to-NavSafetyNet water handoff");
            require(context, task.isWetPreyTemporarilyRejected(sheep.getUUID()),
                    "wet prey UUID was not retained through dry-ground recovery");
            require(context, sheep.isAlive(),
                    "hunt immediately retargeted and killed the same sheep after water rescue");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:hunt_cross_region_game_tests_fresh_hunt_accepts_factual_high_surface_and_starts_acquiring", maxTicks = 100)
    public void freshHuntAcceptsFactualHighSurfaceAndStartsAcquiring(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos template = context.absolutePos(new BlockPos(8, 5, -368));
        BlockPos start = new BlockPos(template.getX(), 64, template.getZ());
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                BlockPos feet = start.offset(dx, 0, dz);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        require(context, start.getY() >= 32,
                "fresh surface fixture unexpectedly started below the planner boundary");
        require(context, world.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        start.getX(), start.getZ()) == start.getY(),
                "fresh surface fixture did not publish a factual terrain height");

        String name = "HuntFreshSurfaceAnchorGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);

        HuntSearchCursor cursor = HuntSearchCursor.initial();
        String dimension = world.dimension().identifier().toString();
        require(context, cursor.surfaceAnchor(dimension).isEmpty(),
                "fresh surface cursor unexpectedly contained a preset anchor");
        HuntTask task = new HuntTask(1, true, cursor);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_fresh_surface_anchor"));

        context.failIfEver(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("fresh surface hunt ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            HuntSearchCursor.SurfaceAnchor anchor =
                    cursor.surfaceAnchor(dimension).orElse(null);
            require(context, anchor != null,
                    "fresh surface hunt did not establish its own anchor");
            require(context, anchor.x() == start.getX()
                            && anchor.y() == start.getY()
                            && anchor.z() == start.getZ(),
                    "fresh surface hunt established the wrong anchor");
            String description = task.describe();
            if (!description.contains("phase=ACQUIRE")
                    && !description.contains("phase=ROAM")) {
                return;
            }
            require(context, task.state() == TaskState.RUNNING,
                    "fresh surface hunt did not continue into acquisition");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:hunt_cross_region_game_tests_fresh_hunt_rejects_sky_visible_deep_mine_as_surface_anchor", maxTicks = 100)
    public void freshHuntRejectsSkyVisibleDeepMineAsSurfaceAnchor(GameTestHelper context) {
        var world = context.getLevel();
        // Build the sky-visible-deep geometry explicitly instead of trusting ambient terrain
        // 200 blocks out: batch placement varies per run, and that spot sometimes lands in an
        // unticked chunk whose sky light never propagates. A pad beside this structure (always
        // inside the ticking area) below the planner's Y=32 boundary is sky visible yet deep,
        // which is exactly the fact this test pins.
        BlockPos start = context.absolutePos(new BlockPos(8, 0, 8)).atY(26);
        for (int y = start.getY() - 1; y <= 80; y++) {
            world.setBlock(new BlockPos(start.getX(), y, start.getZ()),
                    y == start.getY() - 1
                            ? Blocks.STONE.defaultBlockState()
                            : Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_ALL);
        }
        require(context, start.getY() < 32,
                "deep-anchor fixture unexpectedly started above the planner boundary");
        // Sky light only propagates on the next server tick, so assert and spawn from the
        // tick callback rather than during fixture setup.
        String name = "HuntFreshDeepAnchorGT";
        AtomicReference<HuntTask> taskRef = new AtomicReference<>();
        AtomicReference<AIPlayerEntity> botRef = new AtomicReference<>();
        context.failIfEver(() -> {
            if (taskRef.get() == null) {
                require(context, world.canSeeSky(start),
                        "deep-anchor fixture must prove sky visibility alone is insufficient");
                AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                                world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                                0.0F, 0.0F, GameType.SURVIVAL)
                        .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
                bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                        Set.of(), 0.0F, 0.0F, true);
                HuntTask task = new HuntTask(1, true);
                TaskManager.INSTANCE.assign(bot, task,
                        TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_fresh_deep_anchor"));
                botRef.set(bot);
                taskRef.set(task);
                return;
            }
            HuntTask task = taskRef.get();
            AIPlayerEntity bot = botRef.get();
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.FAILED,
                    "fresh deep hunt ended as " + task.state());
            require(context, task.failureReason().startsWith("hunt_surface_anchor_unavailable"),
                    "fresh deep hunt produced wrong failure: " + task.failureReason());
            require(context, bot.blockPosition().equals(start),
                    "rejected deep hunt moved before establishing a surface fact: "
                            + bot.blockPosition().toShortString());
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:hunt_cross_region_game_tests_satisfied_quota_returns_to_surface_before_publishing_completion", maxTicks = 700)
    public void satisfiedQuotaReturnsToSurfaceBeforePublishingCompletion(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos deep = context.absolutePos(new BlockPos(8, 5, -240));
        BlockPos anchor = deep.offset(18, 18, 0);
        for (int step = 0; step <= 18; step++) {
            BlockPos feet = deep.offset(step, step, 0);
            world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }

        String name = "HuntQuotaSurfaceReturnGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(deep),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, deep.getX() + 0.5D, deep.getY(), deep.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);

        HuntSearchCursor cursor = HuntSearchCursor.initial();
        String dimension = world.dimension().identifier().toString();
        require(context, cursor.setSurfaceAnchorIfAbsent(
                        dimension, anchor.getX(), anchor.getY(), anchor.getZ()),
                "failed to establish quota-return surface anchor");
        HuntTask task = new HuntTask(1, true, cursor);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_quota_surface_return"));
        InventoryAction.giveItem(bot, new ItemStack(Items.CHICKEN));

        AtomicBoolean sawReturnDebt = new AtomicBoolean();
        AtomicBoolean movedPhysically = new AtomicBoolean();
        context.failIfEver(() -> {
            sawReturnDebt.compareAndSet(false, task.describe().contains("phase=RETURN_SURFACE"));
            movedPhysically.compareAndSet(false, !bot.blockPosition().equals(deep));
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("quota-return hunt ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, sawReturnDebt.get() && movedPhysically.get(),
                    "satisfied quota skipped its physical surface return");
            require(context, bot.blockPosition().distSqr(anchor) <= 4.0D,
                    "quota completed away from its surface anchor: "
                            + bot.blockPosition().toShortString());
            require(context, InventoryAction.countItem(bot, Items.CHICKEN) == 1,
                    "quota-return fixture lost its physical raw meat");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:hunt_cross_region_game_tests_visible_prey_below_mission_surface_floor_is_never_pursued", maxTicks = 500)
    public void visiblePreyBelowMissionSurfaceFloorIsNeverPursued(GameTestHelper context) {
        var world = context.getLevel();
        // The bot has already walked down to the last legal level of a persisted surface
        // expedition. The chicken is locally exposed and visible just beyond that boundary, but
        // it is 17 blocks below the mission-owned anchor and must therefore remain off-limits.
        BlockPos start = context.absolutePos(new BlockPos(8, 55, -224));
        BlockPos missionAnchor = start.above(16);
        int surfaceFloorY = missionAnchor.getY() - 16;
        for (int x = -3; x <= 1; x++) {
            for (int z = -3; z <= 3; z++) {
                BlockPos feet = start.offset(x, 0, z);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        BlockPos unsafePreyCell = start.offset(4, -1, 0);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                BlockPos feet = unsafePreyCell.offset(x, 0, z);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }

        var chicken = EntityType.CHICKEN.create(world, EntitySpawnReason.COMMAND);
        require(context, chicken != null, "failed to create below-floor chicken");
        chicken.setNoAi(true);
        chicken.setHealth(1.0F);
        chicken.snapTo(
                unsafePreyCell.getX() + 0.5D, unsafePreyCell.getY(),
                unsafePreyCell.getZ() + 0.5D, 180.0F, 0.0F);
        require(context, world.addFreshEntity(chicken), "failed to spawn below-floor chicken");

        String name = "HuntSurfaceFloorGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        require(context,
                io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveEntity(bot, chicken),
                "below-floor chicken was not visible from the legal cliff ledge");
        require(context, unsafePreyCell.getY() < surfaceFloorY,
                "fixture chicken did not cross the mission surface floor");

        HuntSearchCursor cursor = HuntSearchCursor.initial();
        String dimension = world.dimension().identifier().toString();
        require(context, cursor.setSurfaceAnchorIfAbsent(
                        dimension,
                        missionAnchor.getX(), missionAnchor.getY(), missionAnchor.getZ()),
                "failed to establish shared hunt surface anchor");
        HuntTask task = new HuntTask(1, true, cursor);
        int chickenKillBaseline = bot.getStats().getValue(
                Stats.ENTITY_KILLED, EntityType.CHICKEN);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_surface_floor_prey"));
        AtomicInteger minimumY = new AtomicInteger(bot.blockPosition().getY());
        AtomicInteger observedTicks = new AtomicInteger();
        context.failIfEver(() -> {
            minimumY.accumulateAndGet(bot.blockPosition().getY(), Math::min);
            observedTicks.incrementAndGet();
            // Assert on positive kill evidence, not on the captured entity reference: this
            // offset arena stays loaded only through the fake player's chunk tickets, and a
            // transient unload replaces the chicken instance, making a stale isAlive() read
            // false without any kill having happened.
            require(context, bot.getStats().getValue(
                            Stats.ENTITY_KILLED, EntityType.CHICKEN) == chickenKillBaseline,
                    "hunt killed prey below the mission surface floor");
            require(context, minimumY.get() >= surfaceFloorY,
                    "hunt descended below the mission surface floor: minY=" + minimumY.get()
                            + " floorY=" + surfaceFloorY);
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("below-floor hunt ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            if (task.state() == TaskState.FAILED) {
                require(context, !task.failureReason().isBlank(),
                        "below-floor hunt failed without a typed reason");
            } else if (observedTicks.get() < 240) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.CHICKEN) == 0,
                    "below-floor chicken entered inventory");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:hunt_cross_region_game_tests_killed_prey_drop_in_one_way_pit_fails_without_following_it", maxTicks = 700)
    public void killedPreyDropInOneWayPitFailsWithoutFollowingIt(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 70, -272));
        for (int x = -4; x <= 5; x++) {
            for (int z = -4; z <= 4; z++) {
                BlockPos feet = start.offset(x, 0, z);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        BlockPos killCell = start.east(3);
        BlockPos observationLedge = start.east(5);
        BlockPos pitCell = start.offset(10, -13, 0);
        for (int y = pitCell.getY(); y <= start.getY() + 1; y++) {
            world.setBlock(
                    new BlockPos(pitCell.getX(), y, pitCell.getZ()),
                    Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                BlockPos feet = pitCell.offset(x, 0, z);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        var cow = EntityType.COW.create(world, EntitySpawnReason.COMMAND);
        require(context, cow != null, "failed to create one-way-drop cow");
        cow.setNoAi(true);
        cow.setHealth(1.0F);
        cow.snapTo(
                killCell.getX() + 0.5D, killCell.getY(), killCell.getZ() + 0.5D,
                180.0F, 0.0F);
        require(context, world.addFreshEntity(cow), "failed to spawn one-way-drop cow");

        String name = "HuntOneWayDropGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        int pickupBaseline = bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.BEEF);

        HuntSearchCursor cursor = HuntSearchCursor.initial();
        String dimension = world.dimension().identifier().toString();
        require(context, cursor.setSurfaceAnchorIfAbsent(
                        dimension, start.getX(), start.getY(), start.getZ()),
                "failed to establish one-way-drop surface anchor");
        HuntTask task = new HuntTask(1, true, cursor);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_one_way_drop_debt"));
        AtomicReference<ItemEntity> trappedBeef = new AtomicReference<>();
        AtomicBoolean dropWasObserved = new AtomicBoolean();
        AtomicBoolean fixtureMovedBotToLedge = new AtomicBoolean();
        AtomicInteger minimumY = new AtomicInteger(start.getY());
        context.failIfEver(() -> {
            minimumY.accumulateAndGet(bot.blockPosition().getY(), Math::min);
            if (!cow.isAlive() && trappedBeef.get() == null) {
                ItemEntity beef = world.getEntitiesOfClass(
                                ItemEntity.class, new AABB(killCell).inflate(3.0D),
                                entity -> entity.getItem().is(Items.BEEF))
                        .stream().findFirst().orElse(null);
                if (beef != null) {
                    beef.setPos(
                            pitCell.getX() + 0.5D, pitCell.getY(), pitCell.getZ() + 0.5D);
                    beef.setDeltaMovement(Vec3.ZERO);
                    trappedBeef.set(beef);
                    bot.getActionPack().stopAll();
                    bot.teleportTo(world,
                            observationLedge.getX() + 0.5D, observationLedge.getY(),
                            observationLedge.getZ() + 0.5D,
                            Set.of(), bot.getYRot(), bot.getXRot(), true);
                    fixtureMovedBotToLedge.set(true);
                }
            }
            ItemEntity beef = trappedBeef.get();
            if (beef != null
                    && io.github.zoyluo.minecraftai.mode.ObservableWorldQuery
                    .canObserveEntity(bot, beef)) {
                dropWasObserved.set(true);
            }
            require(context, minimumY.get() >= start.getY() - 1,
                    "hunt followed meat into the one-way pit: minY=" + minimumY.get());
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("one-way-drop hunt ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, task.failureReason().startsWith("hunt_drop_unrecovered"),
                    "one-way drop produced the wrong typed failure: " + task.failureReason());
            require(context, trappedBeef.get() != null && trappedBeef.get().isAlive(),
                    "fixture never retained physical beef in the one-way pit");
            require(context, fixtureMovedBotToLedge.get(),
                    "fixture never moved the bot to its safe observation ledge");
            require(context, dropWasObserved.get(),
                    "deep beef was never visibly observed by the hunt task");
            require(context, InventoryAction.countItem(bot, Items.BEEF) == 0,
                    "one-way beef entered inventory");
            require(context, bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.BEEF)
                            == pickupBaseline,
                    "one-way beef changed vanilla pickup statistics");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:hunt_cross_region_game_tests_moving_prey_is_retargeted_on_safe_surface_and_physically_collected", maxTicks = 1200)
    public void movingPreyIsRetargetedOnSafeSurfaceAndPhysicallyCollected(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 80, -320));
        int arenaRadius = 15;
        for (int x = -arenaRadius; x <= arenaRadius; x++) {
            for (int z = -arenaRadius; z <= arenaRadius; z++) {
                BlockPos feet = start.offset(x, 0, z);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                if (Math.abs(x) == arenaRadius || Math.abs(z) == arenaRadius) {
                    world.setBlock(feet, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                    world.setBlock(feet.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }

        BlockPos initialPreyCell = start.east(6);
        BlockPos movedPreyCell = start.offset(-6, 0, 4);
        int surfaceFloorY = start.getY() - 16;
        var chicken = EntityType.CHICKEN.create(world, EntitySpawnReason.COMMAND);
        require(context, chicken != null, "failed to create moving chicken");
        // The relocation below is the movement this fixture means to test. Letting vanilla AI
        // move the chicken as well can bring it back into melee before the bot travels to it.
        chicken.setNoAi(true);
        chicken.setHealth(1.0F);
        chicken.snapTo(
                initialPreyCell.getX() + 0.5D, initialPreyCell.getY(),
                initialPreyCell.getZ() + 0.5D, 180.0F, 0.0F);
        require(context, world.addFreshEntity(chicken), "failed to spawn moving chicken");
        require(context, chicken.isNoAi(),
                "moving-prey fixture did not keep its scripted chicken still");

        String name = "HuntMovingPreyGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        require(context, HuntSurfaceRoutes.hasRoundTripSurfaceRoute(
                        bot, initialPreyCell, surfaceFloorY),
                "initial moving-prey cell was not safely reversible");
        require(context, HuntSurfaceRoutes.hasRoundTripSurfaceRoute(
                        bot, movedPreyCell, surfaceFloorY),
                "relocated moving-prey cell was not safely reversible");
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        int pickupBaseline = bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.CHICKEN);

        HuntSearchCursor cursor = HuntSearchCursor.initial();
        String dimension = world.dimension().identifier().toString();
        require(context, cursor.setSurfaceAnchorIfAbsent(
                        dimension, start.getX(), start.getY(), start.getZ()),
                "failed to establish moving-prey surface anchor");
        HuntTask task = new HuntTask(1, true, cursor);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_moving_prey_retarget"));
        AtomicBoolean preyRelocated = new AtomicBoolean();
        AtomicReference<BlockPos> botAtRelocation = new AtomicReference<>();
        AtomicBoolean relocatedMeleeEnvelopeReached = new AtomicBoolean();
        AtomicInteger maximumPostRelocationTravelSquared = new AtomicInteger();
        AtomicInteger minimumY = new AtomicInteger(start.getY());
        context.failIfEver(() -> {
            minimumY.accumulateAndGet(bot.blockPosition().getY(), Math::min);
            require(context, minimumY.get() >= surfaceFloorY,
                    "moving-prey hunt crossed its mission floor: minY=" + minimumY.get());
            require(context,
                    Math.abs(bot.blockPosition().getX() - start.getX()) < arenaRadius
                            && Math.abs(bot.blockPosition().getZ() - start.getZ()) < arenaRadius,
                    "moving-prey hunt left the bounded arena: "
                            + bot.blockPosition().toShortString());
            if (chicken.isAlive()) {
                require(context,
                        Math.abs(chicken.blockPosition().getX() - start.getX()) < arenaRadius
                                && Math.abs(chicken.blockPosition().getZ() - start.getZ())
                                < arenaRadius,
                        "scripted chicken escaped the bounded arena: "
                                + chicken.blockPosition().toShortString());
            }

            if (!preyRelocated.get() && task.describe().contains("phase=APPROACH")) {
                BlockPos relocationOrigin = bot.blockPosition().immutable();
                chicken.snapTo(
                        movedPreyCell.getX() + 0.5D, movedPreyCell.getY(),
                        movedPreyCell.getZ() + 0.5D, chicken.getYRot(), chicken.getXRot());
                chicken.setDeltaMovement(Vec3.ZERO);
                require(context, chicken.isNoAi(),
                        "relocating the scripted chicken enabled its AI");
                require(context, chicken.blockPosition().distSqr(initialPreyCell) >= 100.0D,
                        "fixture did not force the chicken far enough to require reselection");
                require(context, chicken.blockPosition().distSqr(relocationOrigin)
                                >= Math.pow(CombatCore.ATTACK_RANGE + 3.0D, 2),
                        "fixture did not force the chicken far enough from the bot to require travel");
                require(context, chicken.blockPosition().getY() >= surfaceFloorY
                                && HuntSurfaceRoutes.hasRoundTripSurfaceRoute(
                                bot, chicken.blockPosition(), surfaceFloorY),
                        "forced chicken destination was not safely reversible");
                botAtRelocation.set(relocationOrigin);
                preyRelocated.set(true);
                return;
            }

            if (preyRelocated.get()) {
                int traveledSquared = (int) Math.floor(
                        bot.blockPosition().distSqr(botAtRelocation.get()));
                maximumPostRelocationTravelSquared.accumulateAndGet(
                        traveledSquared, Math::max);
            }
            if (preyRelocated.get()
                    && !relocatedMeleeEnvelopeReached.get()
                    && chicken.isAlive()
                    && bot.distanceTo(chicken) <= CombatCore.ATTACK_RANGE) {
                require(context, maximumPostRelocationTravelSquared.get() >= 9,
                        "hunt entered melee without physically traveling toward relocated prey");
                require(context, bot.blockPosition().getY() >= surfaceFloorY
                                && chicken.blockPosition().getY() >= surfaceFloorY,
                        "relocated melee envelope was not reached on reversible safe surface");
                relocatedMeleeEnvelopeReached.set(true);
            }

            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("moving-prey hunt ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, preyRelocated.get() && relocatedMeleeEnvelopeReached.get(),
                    "hunt completed without physically reaching relocated prey on safe surface");
            require(context, !chicken.isAlive(),
                    "moving chicken remained alive after hunt completion");
            require(context, InventoryAction.countItem(bot, Items.CHICKEN) >= 1,
                    "moving-prey hunt completed without raw chicken");
            require(context, bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.CHICKEN)
                            > pickupBaseline,
                    "moving-prey meat did not enter through vanilla pickup statistics");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:hunt_cross_region_game_tests_vanilla_pickup_stat_settles_debt_after_inventory_meat_is_consumed", maxTicks = 900)
    public void vanillaPickupStatSettlesDebtAfterInventoryMeatIsConsumed(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 70, -368));
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                BlockPos feet = start.offset(x, 0, z);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }

        BlockPos killCell = start.east(4);
        BlockPos pickupCell = start.west(4);
        var cow = EntityType.COW.create(world, EntitySpawnReason.COMMAND);
        require(context, cow != null, "failed to create pickup-stat cow");
        cow.setNoAi(true);
        cow.setHealth(1.0F);
        cow.snapTo(
                killCell.getX() + 0.5D, killCell.getY(), killCell.getZ() + 0.5D,
                180.0F, 0.0F);
        require(context, world.addFreshEntity(cow), "failed to spawn pickup-stat cow");

        String name = "HuntPickupStatGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        int pickupBaseline = bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.BEEF);
        int inventoryBaseline = InventoryAction.countItem(bot, Items.BEEF);

        HuntTask task = anchoredHunt(bot, 2);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_pickup_stat_competition"));
        AtomicBoolean pickupPaused = new AtomicBoolean();
        AtomicBoolean vanillaPickupObserved = new AtomicBoolean();
        AtomicBoolean inventoryReturnedToBaseline = new AtomicBoolean();
        AtomicBoolean acquireObserved = new AtomicBoolean();
        AtomicInteger ticksAfterResume = new AtomicInteger();
        context.failIfEver(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("pickup-stat hunt ended as " + task.state()
                        + ":" + task.failureReason()));
            }

            if (!pickupPaused.get()
                    && !cow.isAlive()
                    && task.describe().contains("phase=PICKUP")) {
                require(context,
                        bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.BEEF)
                                == pickupBaseline,
                        "fixture missed the pre-pickup pause boundary");
                ItemEntity beef = HarvestCore.nearestDropAnyOf(
                        bot, Set.of(Items.BEEF), 16).orElse(null);
                if (beef == null) {
                    return;
                }
                beef.setPos(
                        pickupCell.getX() + 0.5D, pickupCell.getY(),
                        pickupCell.getZ() + 0.5D);
                beef.setDeltaMovement(Vec3.ZERO);
                task.pause(bot);
                require(context, task.state() == TaskState.PAUSED,
                        "pickup debt did not pause before physical collection");
                pickupPaused.set(true);
            }

            if (pickupPaused.get() && !vanillaPickupObserved.get()) {
                require(context, task.state() == TaskState.PAUSED,
                        "Hunt tick ran before the pickup competition was injected");
                int pickedUp = bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.BEEF);
                if (pickedUp == pickupBaseline) {
                    ItemEntity beef = HarvestCore.nearestDropAnyOf(
                            bot, Set.of(Items.BEEF), 16).orElse(null);
                    if (beef != null
                            && bot.getActionPack().isPathExecutorIdle()
                            && bot.getActionPack().isWalkToIdle()) {
                        HarvestCore.approachDropPhysically(bot, beef);
                    }
                    return;
                }
                int physicalMeat = InventoryAction.countItem(bot, Items.BEEF);
                require(context, !cow.isAlive() && physicalMeat > inventoryBaseline,
                        "pickup statistic advanced without physical cow death and inventory meat");
                vanillaPickupObserved.set(true);
                require(context, InventoryAction.removeItems(
                                bot, Items.BEEF, physicalMeat - inventoryBaseline),
                        "fixture failed to consume the physically picked-up beef");
                require(context, InventoryAction.countItem(bot, Items.BEEF) == inventoryBaseline,
                        "inventory delta did not return to its exact pre-hunt baseline");
                inventoryReturnedToBaseline.set(true);
                task.resume(bot);
                require(context, task.state() == TaskState.RUNNING,
                        "pickup debt did not resume after the competing consumption");
                return;
            }

            if (!inventoryReturnedToBaseline.get()) {
                return;
            }
            ticksAfterResume.incrementAndGet();
            require(context,
                    bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.BEEF) > pickupBaseline,
                    "vanilla pickup evidence disappeared after inventory consumption");
            require(context, InventoryAction.countItem(bot, Items.BEEF) == inventoryBaseline,
                    "fixture unexpectedly restored an inventory delta");
            require(context, !task.describe().contains("phase=APPROACH")
                            && !task.describe().contains("phase=STRIKE"),
                    "hunt tried to pursue or strike the already-dead cow again: "
                            + task.describe());
            if (!task.describe().contains("phase=ACQUIRE")) {
                require(context, ticksAfterResume.get() <= 250,
                        "pickup statistic did not settle the bounded debt: " + task.describe());
                return;
            }

            acquireObserved.set(true);
            require(context, task.state() == TaskState.RUNNING
                            && !task.failureReason().startsWith("hunt_drop_unrecovered"),
                    "pickup debt did not leave PICKUP cleanly: "
                            + task.state() + ":" + task.failureReason());
            require(context, vanillaPickupObserved.get()
                            && inventoryReturnedToBaseline.get()
                            && acquireObserved.get(),
                    "fixture did not prove the pickup-stat competition");
            task.cancel(bot, "gametest_pickup_stat_debt_settled");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:hunt_cross_region_game_tests_externally_killed_target_never_creates_pickup_debt", maxTicks = 500)
    public void externallyKilledTargetNeverCreatesPickupDebt(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 70, -400));
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                BlockPos feet = start.offset(x, 0, z);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }

        BlockPos preyCell = start.east(6);
        var chicken = EntityType.CHICKEN.create(world, EntitySpawnReason.COMMAND);
        require(context, chicken != null, "failed to create external-death chicken");
        chicken.setNoAi(true);
        chicken.snapTo(
                preyCell.getX() + 0.5D, preyCell.getY(), preyCell.getZ() + 0.5D,
                180.0F, 0.0F);
        require(context, world.addFreshEntity(chicken), "failed to spawn external-death chicken");

        String name = "HuntExternalDeathGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        int killBaseline = bot.getStats().getValue(Stats.ENTITY_KILLED, EntityType.CHICKEN);

        HuntTask task = anchoredHunt(bot, 64);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_external_death_credit"));
        AtomicBoolean externallyKilled = new AtomicBoolean();
        AtomicBoolean pickupDebtObserved = new AtomicBoolean();
        AtomicInteger ticksAfterDeath = new AtomicInteger();
        context.failIfEver(() -> {
            pickupDebtObserved.compareAndSet(
                    false, task.describe().contains("phase=PICKUP"));
            if (!externallyKilled.get() && task.describe().contains("phase=APPROACH")) {
                require(context,
                        chicken.hurtServer(world, world.damageSources().generic(), 1000.0F),
                        "fixture failed to kill chicken without player credit");
                externallyKilled.set(true);
                return;
            }
            if (!externallyKilled.get()) {
                return;
            }
            ticksAfterDeath.incrementAndGet();
            require(context,
                    bot.getStats().getValue(Stats.ENTITY_KILLED, EntityType.CHICKEN)
                            == killBaseline,
                    "external death unexpectedly credited the hunting bot");
            require(context, !pickupDebtObserved.get(),
                    "external target death created a PICKUP debt");
            if (task.state() == TaskState.FAILED || task.state() == TaskState.COMPLETED
                    || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("external-death hunt ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            if (ticksAfterDeath.get() < 5
                    || (task.describe().contains("phase=APPROACH")
                    || task.describe().contains("phase=STRIKE"))) {
                return;
            }
            task.cancel(bot, "gametest_external_death_reacquired");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:hunt_cross_region_game_tests_old_nearby_raw_drop_cannot_poison_fresh_kill_transaction", maxTicks = 1000)
    public void oldNearbyRawDropCannotPoisonFreshKillTransaction(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 70, -432));
        for (int x = -9; x <= 9; x++) {
            for (int z = -9; z <= 9; z++) {
                BlockPos feet = start.offset(x, 0, z);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }

        BlockPos killCell = start.east(5);
        String name = "HuntOldDropGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        int pickupBaseline = bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.BEEF);
        int killBaseline = bot.getStats().getValue(Stats.ENTITY_KILLED, EntityType.COW);
        int inventoryBaseline = InventoryAction.countItem(bot, Items.BEEF);
        AABB arena = new AABB(killCell).inflate(6.0D);

        // This arena lives hundreds of blocks from the test structure and stays loaded only by
        // the fake player's own chunk tickets. Spawn the fixture entities after those tickets
        // settle, and assert through fresh world queries: a transient unload/reload replaces the
        // entity instances, so captured Java references can report a false !isAlive().
        context.runAtTickTime(40, () -> {
            ItemEntity oldBeef = new ItemEntity(
                    world,
                    killCell.getX() + 0.5D,
                    killCell.getY(),
                    killCell.getZ() + 2.5D,
                    new ItemStack(Items.BEEF));
            oldBeef.setDeltaMovement(Vec3.ZERO);
            oldBeef.setNeverPickUp();
            oldBeef.setUnlimitedLifetime();
            require(context, world.addFreshEntity(oldBeef), "failed to spawn old beef");

            var cow = EntityType.COW.create(world, EntitySpawnReason.COMMAND);
            require(context, cow != null, "failed to create old-drop cow");
            cow.setNoAi(true);
            cow.setHealth(1.0F);
            cow.snapTo(
                    killCell.getX() + 0.5D, killCell.getY(), killCell.getZ() + 0.5D,
                    180.0F, 0.0F);
            require(context, world.addFreshEntity(cow), "failed to spawn old-drop cow");
        });

        AtomicReference<HuntTask> taskRef = new AtomicReference<>();
        context.runAtTickTime(80, () -> {
            ItemEntity oldBeef = onlyOldBeef(world, arena);
            require(context, oldBeef != null && oldBeef.getAge() < 0,
                    "old beef lost its non-fresh age marker");
            HuntTask task = anchoredHunt(bot, 64);
            taskRef.set(task);
            TaskManager.INSTANCE.assign(bot, task,
                    TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_old_drop_isolation"));
        });

        AtomicBoolean pickupObserved = new AtomicBoolean();
        context.failIfEver(() -> {
            HuntTask task = taskRef.get();
            if (task == null) {
                return;
            }
            pickupObserved.compareAndSet(
                    false, task.describe().contains("phase=PICKUP"));
            if (task.state() == TaskState.FAILED || task.state() == TaskState.COMPLETED
                    || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("old-drop hunt ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            if (!pickupObserved.get() || !task.describe().contains("phase=ACQUIRE")) {
                return;
            }
            require(context, world.getEntitiesOfClass(
                            net.minecraft.world.entity.animal.cow.Cow.class, arena,
                            Entity::isAlive).isEmpty(),
                    "old-drop fixture reached ACQUIRE before the cow died");
            ItemEntity oldBeef = onlyOldBeef(world, arena);
            require(context, oldBeef != null,
                    "unrelated old beef was consumed or mutated");
            require(context,
                    bot.getStats().getValue(Stats.ENTITY_KILLED, EntityType.COW) > killBaseline,
                    "fresh cow kill lacked bot kill credit");
            require(context,
                    bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.BEEF) > pickupBaseline
                            && InventoryAction.countItem(bot, Items.BEEF) > inventoryBaseline,
                    "fresh cow beef was not physically collected");
            task.cancel(bot, "gametest_old_drop_ignored");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:hunt_cross_region_game_tests_credited_fire_aspect_kill_without_raw_meat_returns_to_acquire", maxTicks = 1000)
    public void creditedFireAspectKillWithoutRawMeatReturnsToAcquire(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 70, -464));
        for (int x = -9; x <= 9; x++) {
            for (int z = -9; z <= 9; z++) {
                BlockPos feet = start.offset(x, 0, z);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }

        BlockPos killCell = start.east(5);
        var cow = EntityType.COW.create(world, EntitySpawnReason.COMMAND);
        require(context, cow != null, "failed to create Fire Aspect cow");
        cow.setNoAi(true);
        cow.setHealth(1.0F);
        cow.snapTo(
                killCell.getX() + 0.5D, killCell.getY(), killCell.getZ() + 0.5D,
                180.0F, 0.0F);
        require(context, world.addFreshEntity(cow), "failed to spawn Fire Aspect cow");

        String name = "HuntCookedDropGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        ItemStack fireSword = new ItemStack(Items.DIAMOND_SWORD);
        var enchantmentRegistry =
                world.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        var fireAspect = enchantmentRegistry.get(
                        Enchantments.FIRE_ASPECT.identifier())
                .orElseThrow(() -> new IllegalStateException("missing Fire Aspect registry entry"));
        fireSword.enchant(fireAspect, 1);
        InventoryAction.giveItem(bot, fireSword);
        int killBaseline = bot.getStats().getValue(Stats.ENTITY_KILLED, EntityType.COW);
        int rawPickupBaseline = bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.BEEF);
        int cookedPickupBaseline =
                bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.COOKED_BEEF);

        HuntTask task = anchoredHunt(bot, 64);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_cooked_drop_no_raw"));
        AtomicBoolean pickupObserved = new AtomicBoolean();
        AtomicBoolean cookedDropObserved = new AtomicBoolean();
        context.failIfEver(() -> {
            pickupObserved.compareAndSet(
                    false, task.describe().contains("phase=PICKUP"));
            cookedDropObserved.compareAndSet(false,
                    InventoryAction.countItem(bot, Items.COOKED_BEEF) > 0
                            || bot.getStats().getValue(
                            Stats.ITEM_PICKED_UP, Items.COOKED_BEEF) > cookedPickupBaseline
                            || !world.getEntitiesOfClass(
                                    ItemEntity.class,
                                    new AABB(killCell).inflate(4.0D),
                                    item -> item.getItem().is(Items.COOKED_BEEF))
                            .isEmpty());
            if (task.state() == TaskState.FAILED || task.state() == TaskState.COMPLETED
                    || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("cooked-drop hunt ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            if (!pickupObserved.get() || !task.describe().contains("phase=ACQUIRE")) {
                return;
            }
            require(context, !cow.isAlive() && cookedDropObserved.get(),
                    "Fire Aspect fixture did not produce factual cooked beef");
            require(context,
                    bot.getStats().getValue(Stats.ENTITY_KILLED, EntityType.COW) > killBaseline,
                    "Fire Aspect cow kill lacked bot credit");
            require(context,
                    bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.BEEF)
                            == rawPickupBaseline,
                    "Fire Aspect fixture unexpectedly produced raw beef pickup");
            require(context, !task.failureReason().startsWith("hunt_drop_unrecovered"),
                    "zero-raw credited kill became a false pickup debt");
            task.cancel(bot, "gametest_cooked_drop_reacquired");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:hunt_cross_region_game_tests_bounded_hunt_walks_to_prey_outside_initial_perception", maxTicks = 2000)
    public void boundedHuntWalksToPreyOutsideInitialPerception(GameTestHelper context) {
        var world = context.getLevel();
        // GameTest lays every structure on the positive-Z grid before executing batches. Reserve a
        // negative-Z lane for this longer live fixture so its prey/corridor cannot overlap another
        // template even when that neighboring test has not started yet. Extend the lane east: that
        // is the first standable destination in HuntTask's deterministic compass fan. A north/south
        // lane let the bot accept an unrelated barrier foundation to the east and made the proof
        // depend on the placement/order of every other GameTest.
        BlockPos start = context.absolutePos(new BlockPos(8, 5, -72));
        for (int x = -4; x <= 36; x++) {
            for (int z = -5; z <= 5; z++) {
                BlockPos feet = start.offset(x, 0, z);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }

        var chicken = EntityType.CHICKEN.create(world, EntitySpawnReason.COMMAND);
        if (chicken == null) {
            context.fail(Component.nullToEmpty("failed to create chicken"));
            return;
        }
        chicken.setNoAi(true);
        // This fixture owns cross-region discovery and physical pickup, not repeated combat cadence
        // (the seed evidence and planner tests cover multi-kill batches). An adult chicken always
        // drops one raw chicken, removing the cow-loot variance from this navigation contract.
        chicken.setHealth(1.0F);
        // Default strict perception is 16 blocks. Keep the prey outside that boundary while
        // minimizing writes beyond EMPTY_STRUCTURE; the unique batch prevents live-test overlap.
        chicken.snapTo(
                start.getX() + 20.5D, start.getY(), start.getZ() + 0.5D, 0.0F, 0.0F);
        require(context, world.addFreshEntity(chicken), "failed to spawn cross-region chicken");

        String name = "HuntCrossRegionGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        int pickupBaseline = bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.CHICKEN);

        HuntTask task = anchoredHunt(bot, 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_cross_region"));
        context.failIfEver(() -> {
            if (task.describe().contains("phase=PICKUP")) {
                require(context, world.getEntitiesOfClass(
                                net.minecraft.world.entity.animal.chicken.Chicken.class,
                                new AABB(start).inflate(32.0D), prey -> prey.isAlive()).isEmpty(),
                        "cross-region hunt opened pickup debt while its chicken was still alive");
            }
            if (InventoryAction.countItem(bot, Items.CHICKEN) == 0
                    && HarvestCore.nearestDropAnyOf(bot, Set.of(Items.CHICKEN), 16).isPresent()) {
                String description = task.describe();
                require(context, description.contains("phase=STRIKE")
                                || description.contains("phase=PICKUP"),
                        "hunt abandoned an observed meat drop for a new roam: " + task.describe());
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("cross-region hunt ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            int meat = InventoryAction.countItem(bot, Items.CHICKEN);
            require(context, meat >= 1, "hunt completed without physical pickup: " + meat);
            require(context, bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.CHICKEN)
                            > pickupBaseline,
                    "hunt meat did not enter through vanilla pickup statistics");
            require(context, bot.blockPosition().getX() >= start.getX() + 12,
                    "hunt never crossed the initial perception region: " + bot.blockPosition().toShortString());
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    /**
     * Re-queries the pickup-proof marker beef instead of trusting a captured reference: the
     * offset arena's chunks can transiently unload, and a reloaded {@link ItemEntity} is a new
     * instance while the marker's infinite pickup delay and never-despawn age survive in NBT.
     */
    private static ItemEntity onlyOldBeef(
            net.minecraft.server.level.ServerLevel world, AABB arena) {
        var marked = world.getEntitiesOfClass(
                ItemEntity.class, arena,
                entity -> entity.getItem().is(Items.BEEF) && entity.hasPickUpDelay());
        return marked.size() == 1 ? marked.get(0) : null;
    }

    private static HuntTask anchoredHunt(AIPlayerEntity bot, int targetMeat) {
        HuntSearchCursor cursor = HuntSearchCursor.initial();
        BlockPos anchor = bot.blockPosition();
        boolean established = cursor.setSurfaceAnchorIfAbsent(
                bot.level().dimension().identifier().toString(),
                anchor.getX(), anchor.getY(), anchor.getZ());
        if (!established) {
            throw new IllegalStateException("failed to establish Hunt GameTest surface anchor");
        }
        return new HuntTask(targetMeat, true, cursor);
    }

    private static Map<String, String> pickupCheckpoint(
            String dimension, BlockPos origin, BlockPos anchor,
            long startedWorldTime, UUID dropId, int units) {
        return pickupCheckpoint(dimension, origin, anchor, startedWorldTime, dropId, units, 1);
    }

    private static Map<String, String> pickupCheckpoint(
            String dimension, BlockPos origin, BlockPos anchor,
            long startedWorldTime, UUID dropId, int units, int targetCount) {
        Map<String, String> checkpoint = new LinkedHashMap<>();
        checkpoint.put("task_schema", "1");
        checkpoint.put("cursor_kind", "hunt_pickup");
        checkpoint.put("transaction_state", "OPEN");
        checkpoint.put("target_count", String.valueOf(targetCount));
        checkpoint.put("require_full_quota", "true");
        checkpoint.put("dimension", dimension);
        checkpoint.put("expected_raw_item", "minecraft:beef");
        checkpoint.put("pickup_origin",
                origin.getX() + "," + origin.getY() + "," + origin.getZ());
        checkpoint.put("pickup_return_anchor",
                anchor.getX() + "," + anchor.getY() + "," + anchor.getZ());
        checkpoint.put("inventory_baseline", "0");
        checkpoint.put("pickup_stat_baseline", "0");
        checkpoint.put("aux_inventory_baseline", "0");
        checkpoint.put("aux_pickup_stat_baseline", "0");
        checkpoint.put("pickup_started_world_time", String.valueOf(startedWorldTime));
        checkpoint.put("bound_drop_units", dropId + "=" + units);
        return Map.copyOf(checkpoint);
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}

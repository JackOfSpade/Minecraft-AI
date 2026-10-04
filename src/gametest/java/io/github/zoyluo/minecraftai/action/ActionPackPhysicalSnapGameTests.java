package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.TeleportAudit;
import io.github.zoyluo.minecraftai.gametest.BotFixtureMoves;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.FakePlayerMotion;
import io.github.zoyluo.minecraftai.navigation.NavEngine;
import io.github.zoyluo.minecraftai.navigation.NavEngineSelector;
import io.github.zoyluo.minecraftai.pathfinding.AStarPathfinder;
import io.github.zoyluo.minecraftai.pathfinding.MoveType;
import io.github.zoyluo.minecraftai.pathfinding.Node;
import io.github.zoyluo.minecraftai.pathfinding.PathExecutor;
import io.github.zoyluo.minecraftai.pathfinding.PathfindingResult;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Strict-survival regression for a one-cell physical recovery from an invalid A* start. */
public final class ActionPackPhysicalSnapGameTests {
    /**
     * A start whose body overlaps a wall column (the lower corner of a standable cell) is left by walking: nothing recentres the bot by
     * a teleport. The safety net shoves it clear of the block with real inputs and the route it asked for is walked to its goal.
     */
    @GameTest(environment = "minecraftai-gametest:action_pack_physical_snap_game_tests_an_overlap_start_walks_off_without_a_teleport", maxTicks = 200)
    public void anOverlapStartWalksOffWithoutATeleport(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos anchor = context.absolutePos(new BlockPos(3, 3, 3));
        BlockPos goal = anchor.south(3);
        for (int dz = -1; dz <= 4; dz++) {
            BlockPos feet = anchor.south(dz);
            world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        // The wall column north of the anchor: the bot's 0.6-wide body reaches into it.
        world.setBlock(anchor.north(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(anchor.north().above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        String name = "OverlapWalkOffGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(anchor),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        NavEngineSelector.setBotEngine(bot.getUUID(), NavEngine.BARITONE);
        BotFixtureMoves.place(bot, new Vec3(anchor.getX() + 0.5D, anchor.getY(), anchor.getZ() + 0.15D));
        bot.setOnGround(true);
        Standability.clearCache();
        require(context, Standability.isStandable(world, anchor) && !FakePlayerMotion.isBlockCollisionFree(bot),
                "fixture: the start must be a standable cell whose body overlaps the wall");
        TeleportAudit.reset(bot);
        Vec3 start = bot.position();
        ActionResult started = bot.getActionPack().startPathTo(goal);
        require(context, !started.isFailed(), "an overlapping start was refused: " + started.reason());
        require(context, bot.position().distanceToSqr(start) < 1.0E-12D, "starting the route moved the bot");

        AtomicInteger ticks = new AtomicInteger();
        context.onEachTick(() -> {
            int tick = ticks.incrementAndGet();
            require(context, TeleportAudit.corrections(bot) == 0,
                    "the overlap was corrected by a teleport (" + TeleportAudit.lastCaller(bot) + ")");
            if (bot.getActionPack().isPathExecutorIdle() && tick > 8 && !bot.blockPosition().closerThan(goal, 1.5D)) {
                // The safety net's shove took the bot over for a moment: the owner asks again, as a task does.
                bot.getActionPack().startPathTo(goal);
            }
            if (bot.blockPosition().closerThan(goal, 1.5D) && FakePlayerMotion.isBlockCollisionFree(bot)) {
                require(context, Math.abs(bot.getX() - (anchor.getX() + 0.5D)) < 1.0D, "the bot left its column");
                bot.getActionPack().stopAll();
                NavEngineSelector.clearBotEngine(bot.getUUID());
                AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
                context.succeed();
            }
            require(context, tick < 190, "the bot did not walk off the wall to the goal: " + bot.blockPosition().toShortString());
        });
    }

    @GameTest(maxTicks = 20)
    public void standableBodySnapKeepsEveryPoseAndNeverTeleportsACornerOverlap(
            GameTestHelper context) {
        var world = context.getLevel();
        BlockPos anchor = context.absolutePos(new BlockPos(7, 3, 7));
        world.setBlock(anchor.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(anchor, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(anchor.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(anchor.north(),
                Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(anchor.north().west(),
                Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);

        String name = "BodySnapCornerGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(anchor),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));

        // A collision-free fractional pose is legitimate physical state and must not be
        // normalized merely because it is off centre.
        bot.teleportTo(world, anchor.getX() + 0.65D, anchor.getY(),
                anchor.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
        Vec3 safeOffset = bot.position();
        require(context, FakePlayerMotion.isBlockCollisionFree(bot),
                "safe-offset fixture unexpectedly collided");
        require(context, bot.getActionPack().snapPlayerToNearestStandable(
                        "gametest_safe_fractional_pose"),
                "safe fractional stand was rejected");
        require(context, bot.position().distanceToSqr(safeOffset) < 1.0E-12D,
                "safe fractional stand was unnecessarily recentered");

        // Seed-3000 equivalent: the lower corner still floors to the standable anchor column, but
        // the player's width crosses north/west into raised full blocks.
        bot.teleportTo(world, anchor.getX(), anchor.getY(), anchor.getZ(),
                Set.of(), 0.0F, 0.0F, true);
        Standability.clearCache();
        require(context, Standability.isStandable(world, anchor),
                "corner fixture logical column was not standable");
        require(context, !FakePlayerMotion.isBlockCollisionFree(bot),
                "corner fixture did not produce a real body collision");
        TeleportAudit.reset(bot);
        Vec3 corner = bot.position();
        // R5: the overlap is not repaired by moving the bot. The cell is a valid start as it is (the bot walks off it), so the
        // snap accepts it without a single teleport, however often it is asked.
        require(context, bot.getActionPack().snapPlayerToNearestStandable(
                        "gametest_corner_overlap"),
                "a standable corner overlap must be accepted as a start");
        require(context, bot.position().distanceToSqr(corner) < 1.0E-12D,
                "the corner overlap was recentred by a move");
        require(context, bot.getActionPack().startCell().equals(anchor),
                "the search must start from the bot's own cell");

        require(context, bot.getActionPack().snapPlayerToNearestStandable(
                        "gametest_corner_overlap_idempotent"),
                "the same start was rejected the second time");
        require(context, bot.position().distanceToSqr(corner) < 1.0E-12D,
                "the repeated snap moved the bot");
        require(context, TeleportAudit.corrections(bot) == 0,
                "a snap teleported the bot (" + TeleportAudit.lastCaller(bot) + ")");

        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void centreWalkRejectsLivingEntityOccupyingLanding(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos anchor = context.absolutePos(new BlockPos(12, 3, 12));
        world.setBlock(anchor.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(anchor, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(anchor.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        String name = "CenterOccupiedGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(anchor),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        BotFixtureMoves.place(bot, new Vec3(anchor.getX() + 0.95D, anchor.getY(), anchor.getZ() + 0.95D));
        Vec3 before = bot.position();

        var cow = EntityType.COW.create(world, EntitySpawnReason.COMMAND);
        require(context, cow != null, "failed to create centre-occupying cow");
        cow.snapTo(
                anchor.getX(), anchor.getY(), anchor.getZ(), 0.0F, 0.0F);
        require(context, world.addFreshEntity(cow), "failed to spawn centre-occupying cow");
        require(context, !bot.getBoundingBox().intersects(cow.getBoundingBox()),
                "fixture cow already overlapped the off-centre bot");

        // The walk back to the middle of the cell (the walked replacement of the deleted teleporting centre return) is refused
        // on its first tick, before any key is pressed, because the cow occupies the landing.
        WalkedStep recentre = WalkedStep.begin(bot, new Vec3(anchor.getX() + 0.5D, anchor.getY(), anchor.getZ() + 0.5D),
                WalkedStep.Kind.RECENTER, "gametest_occupied_center");
        WalkedStep.Result refused = recentre.tick();
        require(context, refused.failed() && "entity_occupied".equals(recentre.failure()),
                "the centre walk entered a living entity: " + refused + " / " + recentre.failure());
        require(context, bot.position().distanceToSqr(before) < 1.0E-12D,
                "rejected occupied centre walk still moved the bot");
        require(context, cow.isAlive(),
                "rejected centre return removed the occupying entity");

        cow.discard();
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
        context.succeed();
    }

    /**
     * Three one-block hops in a row, each a real jump onto the next step with the forward and jump keys (no teleport): every landing is
     * verified (block position, grounded) before the next hop starts.
     */
    @GameTest(maxTicks = 240)
    public void consecutiveWalkedHopsLandOnEachStep(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(3, 3, 3));
        BlockPos first = start.east().above();
        BlockPos second = first.east().above();
        BlockPos third = second.east().above();
        for (BlockPos feet : new BlockPos[]{start, first, second, third}) {
            world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }

        String name = "WalkedHopsGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        BotFixtureMoves.place(bot, start);
        bot.setOnGround(true);
        Standability.clearCache();
        TeleportAudit.reset(bot);

        BlockPos[] landings = {first, second, third};
        int[] hop = {0};
        int[] ticks = {0};
        bot.getActionPack().runStep(WalkedStep.begin(bot, landings[0], WalkedStep.Kind.STEP_UP, "gametest_hop_1"));
        context.onEachTick(() -> {
            ticks[0]++;
            require(context, TeleportAudit.corrections(bot) == 0,
                    "a hop teleported the bot (" + TeleportAudit.lastCaller(bot) + ")");
            if (bot.getActionPack().stepIdle()) {
                WalkedStep.Result result = bot.getActionPack().stepResult();
                require(context, result != null && result.succeeded(),
                        "hop " + (hop[0] + 1) + " did not succeed: " + (result == null ? "no result" : result.reason()));
                require(context, bot.blockPosition().equals(landings[hop[0]]) && WalkedStep.supported(bot),
                        "hop " + (hop[0] + 1) + " did not publish a verified landing: " + bot.blockPosition().toShortString());
                hop[0]++;
                if (hop[0] == landings.length) {
                    AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
                    context.succeed();
                    return;
                }
                bot.getActionPack().runStep(WalkedStep.begin(bot, landings[hop[0]], WalkedStep.Kind.STEP_UP, "gametest_hop_" + (hop[0] + 1)));
            }
            require(context, ticks[0] < 230, "timed out at hop " + (hop[0] + 1) + " " + bot.blockPosition().toShortString());
        });
    }

    /** A living entity standing on the landing stops a walked hop before it starts: the step fails, the bot does not move. */
    @GameTest(maxTicks = 40)
    public void walkedHopRejectsLivingEntityOnLanding(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(9, 3, 3));
        BlockPos landing = start.east().above();
        world.setBlock(start.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(landing.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(landing, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(landing.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        String name = "OccupiedHopGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        BotFixtureMoves.place(bot, start);
        bot.setOnGround(true);
        Standability.clearCache();
        TeleportAudit.reset(bot);
        var cow = EntityType.COW.create(world, EntitySpawnReason.COMMAND);
        require(context, cow != null, "failed to create occupied-hop cow");
        cow.setNoAi(true);
        cow.snapTo(Vec3.atBottomCenterOf(landing), 0.0F, 0.0F);
        require(context, world.addFreshEntity(cow), "failed to spawn occupied-hop cow");

        Vec3 before = bot.position();
        require(context, WalkedStep.refusal(bot, landing, WalkedStep.Kind.STEP_UP) != null,
                "the hop validator accepted a landing occupied by a living entity");
        bot.getActionPack().runStep(WalkedStep.begin(bot, landing, WalkedStep.Kind.STEP_UP, "gametest_occupied_landing"));
        AtomicInteger ticks = new AtomicInteger();
        context.onEachTick(() -> {
            ticks.incrementAndGet();
            if (bot.getActionPack().stepIdle()) {
                WalkedStep.Result result = bot.getActionPack().stepResult();
                require(context, result != null && result.failed() && result.reason().contains("entity_occupied"),
                        "the hop into a living entity was not refused as entity_occupied: "
                                + (result == null ? "no result" : result.status() + " " + result.reason()));
                require(context, bot.blockPosition().equals(start) && bot.position().distanceTo(before) < 0.05D,
                        "the rejected hop moved the bot to " + bot.position());
                require(context, world.getBlockState(landing.below()).is(Blocks.STONE),
                        "the rejected hop changed the natural support");
                require(context, TeleportAudit.corrections(bot) == 0, "a rejected hop teleported the bot");
                cow.discard();
                AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
                context.succeed();
                return;
            }
            require(context, ticks.get() < 30, "the occupied hop never ended");
        });
    }

    @GameTest(maxTicks = 20)
    public void sameLevelPickupPrefersExactDropCellOverCurrentNeighbour(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos current = context.absolutePos(new BlockPos(4, 5, 4));
        BlockPos drop = current.south();
        for (BlockPos feet : new BlockPos[]{current, drop}) {
            world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }

        String name = "PickupStandGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(current),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, current.getX() + 0.5D, current.getY(), current.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);

        require(context, HarvestCore.pickupStandPos(bot, drop).equals(drop),
                "same-level pickup did not select the exact drop cell");

        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
        context.succeed();
    }

    @GameTest(environment = "minecraftai-gametest:action_pack_physical_snap_game_tests_edge_perched_observed_drop_uses_physical_support_instead_of_on_ground_flag", maxTicks = 160)
    public void edgePerchedObservedDropUsesPhysicalSupportInsteadOfOnGroundFlag(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(3, 4, 4));
        BlockPos pickupStand = start.east();
        BlockPos unsupportedDropCell = pickupStand.east();
        for (int dx = 0; dx <= 1; dx++) {
            BlockPos feet = start.east(dx);
            world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        world.setBlock(unsupportedDropCell.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(unsupportedDropCell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(unsupportedDropCell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        String name = "PickupEdgeSupportGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        Vec3 startPose = bot.position();
        int pickupBaseline = bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.BEEF);

        // Reproduce the failed narrow-ridge loot pose: the 0.25-wide item is mostly over an
        // unsupported cell, with only 0.025 blocks of its AABB still resting over pickupStand's
        // west-side support. Vanilla has not published onGround, but ordinary collision can
        // support and collect it without entering the unsupported cell.
        ItemEntity drop = new ItemEntity(world,
                unsupportedDropCell.getX() + 0.10D,
                unsupportedDropCell.getY() + 0.24D,
                unsupportedDropCell.getZ() + 0.5D,
                new ItemStack(Items.BEEF));
        drop.setDeltaMovement(Vec3.ZERO);
        drop.setNoGravity(true);
        drop.setOnGround(false);
        drop.setNoPickUpDelay();
        require(context, world.addFreshEntity(drop), "failed to spawn edge-perched beef");
        require(context, !drop.onGround(), "fixture unexpectedly published onGround");
        require(context, HarvestCore.isDropPhysicallySupported(bot, drop),
                "collision-supported edge drop was classified as airborne");

        context.failIfEver(() -> {
            if (InventoryAction.countItem(bot, Items.BEEF) < 1) {
                HarvestCore.approachDropPhysically(bot, drop);
                return;
            }
            require(context, bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.BEEF)
                            > pickupBaseline,
                    "edge drop entered inventory without vanilla pickup credit");
            require(context, bot.position().distanceToSqr(startPose) > 0.01D,
                    "edge drop was collected without at least 0.1 blocks of physical movement");
            require(context, !bot.blockPosition().equals(unsupportedDropCell),
                    "edge pickup entered the unsupported drop cell: "
                            + bot.blockPosition().toShortString());
            require(context, world.getBlockState(unsupportedDropCell.below()).isAir(),
                    "edge pickup manufactured support beneath the drop");
            require(context, !drop.isAlive(), "picked beef entity remained in the world");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:action_pack_physical_snap_game_tests_same_cell_edge_drop_requires_physical_nudge_before_vanilla_pickup", maxTicks = 100)
    public void sameCellEdgeDropRequiresPhysicalNudgeBeforeVanillaPickup(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos stand = context.absolutePos(new BlockPos(7, 4, 4));
        world.setBlock(stand.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(stand, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(stand.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        String name = "PickupSameCellEdgeGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(stand),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        Vec3 startPose = bot.position();
        int pickupBaseline = bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.OAK_LOG);

        ItemEntity drop = new ItemEntity(world,
                stand.getX() + 0.96D,
                stand.getY() + 0.20D,
                stand.getZ() + 0.5D,
                new ItemStack(Items.OAK_LOG));
        drop.setDeltaMovement(Vec3.ZERO);
        drop.setNoGravity(true);
        drop.setOnGround(true);
        drop.setNeverPickUp();
        require(context, world.addFreshEntity(drop), "failed to spawn same-cell edge drop");
        require(context, bot.blockPosition().equals(drop.blockPosition()),
                "fixture did not place player and drop in the same block cell");
        require(context, !bot.getBoundingBox().intersects(drop.getBoundingBox()),
                "fixture edge drop already intersected the player");

        AtomicBoolean nudgeObserved = new AtomicBoolean();
        context.failIfEver(() -> {
            if (InventoryAction.countItem(bot, Items.OAK_LOG) < 1) {
                HarvestCore.approachDropPhysically(bot, drop);
                if (!nudgeObserved.get()
                        && bot.position().distanceToSqr(startPose) > 0.01D) {
                    nudgeObserved.set(true);
                    drop.setNoPickUpDelay();
                }
                return;
            }
            require(context, bot.blockPosition().equals(stand),
                    "same-cell pickup left its verified support cell");
            require(context, nudgeObserved.get(),
                    "same-cell edge drop was collected without a physical nudge");
            require(context, bot.getStats().getValue(Stats.ITEM_PICKED_UP, Items.OAK_LOG)
                            > pickupBaseline,
                    "same-cell edge pickup did not publish vanilla pickup credit");
            require(context, !drop.isAlive(), "picked same-cell edge drop remained alive");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:action_pack_physical_snap_game_tests_surface_path_replan_cannot_escalate_into_dig_or_pillar", maxTicks = 400)
    public void surfacePathReplanCannotEscalateIntoDigOrPillar(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(3, 4, 4));
        BlockPos blocked = start.east();
        BlockPos goal = blocked.east();
        for (BlockPos feet : new BlockPos[]{start, blocked, goal}) {
            world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            for (int dy = 0; dy <= 4; dy++) {
                world.setBlock(feet.above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }

        String name = "SurfaceReplanPolicyGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        270.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 270.0F, 0.0F, true);
        bot.setOnGround(true);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 4));

        PathfindingResult initial = new AStarPathfinder(
                world, start, goal, 3_000, 30L, false, false).findPath();
        require(context, initial.success(),
                "initial walking-only path was rejected: " + initial.reason());
        require(context, goal.equals(initial.resolvedGoal()),
                "initial surface endpoint was not exact");
        PathExecutor executor = new PathExecutor(initial.path(), goal, false, false);

        // Invalidate the already planned corridor after startup. A permissive internal replan can
        // escape this two-high stone wall by mining it or by spending dirt pillars; a walking-only
        // replan must instead fail closed and leave both the world and mission inventory untouched.
        world.setBlock(blocked, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(blocked.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        AStarPathfinder.invalidateCache("gametest_surface_replan_dynamic_wall");
        AtomicInteger ticks = new AtomicInteger();

        context.failIfEver(() -> {
            require(context, world.getBlockState(blocked).is(Blocks.STONE)
                            && world.getBlockState(blocked.above()).is(Blocks.STONE),
                    "surface replan broke the dynamic wall");
            require(context, world.getBlockState(start).isAir()
                            && world.getBlockState(start.above()).isAir(),
                    "surface replan manufactured a pillar at the route origin");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 4,
                    "surface replan consumed disposable support material");
            require(context, !bot.blockPosition().equals(goal),
                    "walking-only replan crossed an impassable dynamic wall");

            int elapsed = ticks.incrementAndGet();
            ActionResult result = executor.tick(bot.getActionPack());
            if (result.isInProgress()) {
                return;
            }
            require(context, result.isFailed(),
                    "walking-only replan unexpectedly completed through the wall");
            require(context, result.reason().contains("replan_failed"),
                    "fixture did not reach the bounded internal replan: " + result.reason());
            require(context, elapsed > 2,
                    "fixture never exercised the active route before failing");
            require(context, bot.getActionPack().isMiningIdle(),
                    "failed-closed surface replan left a mining action active");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:action_pack_physical_snap_game_tests_constrained_invalid_start_fails_without_cross_cell_snap", maxTicks = 20)
    public void constrainedInvalidStartFailsWithoutCrossCellSnap(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos invalid = context.absolutePos(new BlockPos(5, 7, 5));
        BlockPos lowerLanding = invalid.offset(1, -1, 0);
        BlockPos goal = invalid.east(3);
        world.setBlock(invalid.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(invalid, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(invalid.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        for (BlockPos feet : new BlockPos[]{lowerLanding, goal}) {
            world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }

        String name = "ConstrainedNoSnapGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(invalid),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world,
                invalid.getX() + 0.5D, invalid.getY(), invalid.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, false);
        BlockPos before = bot.blockPosition().immutable();

        ActionResult result =
                bot.getActionPack().startSurfacePathTo(goal, invalid.getY());

        require(context, result.isFailed(),
                "constrained invalid start unexpectedly installed a path");
        require(context, result.reason().startsWith("navigation_goal_"),
                "constrained invalid start produced wrong reason: " + result.reason());
        require(context, bot.blockPosition().equals(before),
                "constrained admission moved before contract proof: from="
                        + before.toShortString() + " to="
                        + bot.blockPosition().toShortString());
        require(context, bot.blockPosition().getY() >= invalid.getY(),
                "constrained admission crossed its minimumY");
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
        context.succeed();
    }

    @GameTest(environment = "minecraftai-gametest:action_pack_physical_snap_game_tests_constrained_empty_and_singleton_executors_require_exact_terminal", maxTicks = 20)
    public void constrainedEmptyAndSingletonExecutorsRequireExactTerminal(
            GameTestHelper context) {
        var world = context.getLevel();
        BlockPos current = context.absolutePos(new BlockPos(4, 4, 4));
        BlockPos goal = current.east();
        for (BlockPos feet : new BlockPos[]{current, goal}) {
            world.setBlock(
                    feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        String name = "ConstrainedExactTerminalGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(current),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        PathExecutor.RouteContract contract =
                PathExecutor.RouteContract.constrainedSurface(current.getY(), null);
        PathExecutor empty = new PathExecutor(
                List.of(), goal, false, false, 0, contract);
        ActionResult emptyResult = empty.tick(bot.getActionPack());
        require(context, emptyResult.isFailed()
                        && emptyResult.reason().contains("terminal_goal_not_exact"),
                "empty constrained path accepted adjacent terminal: "
                        + emptyResult.reason());

        Node singleton = new Node(
                goal, 0.0D, 0.0D, MoveType.WALK, null);
        PathExecutor oneNode = new PathExecutor(
                List.of(singleton), goal, false, false, 0, contract);
        ActionResult singletonResult = oneNode.tick(bot.getActionPack());
        require(context, singletonResult.isFailed()
                        && singletonResult.reason().contains("terminal_goal_not_exact"),
                "singleton constrained path accepted adjacent terminal: "
                        + singletonResult.reason());
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
        context.succeed();
    }

    @GameTest(environment = "minecraftai-gametest:action_pack_physical_snap_game_tests_minimum_y_search_finds_long_safe_route_instead_of_short_descent", maxTicks = 20)
    public void minimumYSearchFindsLongSafeRouteInsteadOfShortDescent(
            GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(4, 7, 4));
        BlockPos goal = start.east(4);
        for (int dx = -1; dx <= 5; dx++) {
            for (int dz = -1; dz <= 3; dz++) {
                BlockPos column = start.offset(dx, 0, dz);
                for (int dy = -2; dy <= 2; dy++) {
                    world.setBlock(
                            column.offset(0, dy, 0),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        // Short route: one block below the contract floor.
        for (int dx = 1; dx <= 3; dx++) {
            BlockPos lowerFeet = start.offset(dx, -1, 0);
            world.setBlock(
                    lowerFeet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        // Longer route: a supported U-shaped detour that remains on the start/goal Y.
        for (int dz = 0; dz <= 2; dz++) {
            for (int dx : new int[]{0, 4}) {
                BlockPos feet = start.offset(dx, 0, dz);
                world.setBlock(
                        feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        for (int dx = 0; dx <= 4; dx++) {
            BlockPos feet = start.offset(dx, 0, 2);
            world.setBlock(
                    feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }

        PathfindingResult ordinary = new AStarPathfinder(
                world, start, goal, 10_000, 50L, false, false)
                .findPathUncached();
        require(context, ordinary.success(), "fixture ordinary route failed");
        require(context, ordinary.path().stream()
                        .anyMatch(node -> node.pos().getY() < start.getY()),
                "fixture ordinary shortest route did not use its short descent");

        PathfindingResult constrained = new AStarPathfinder(
                world, start, goal, 10_000, 50L, false, false)
                .findPathUncachedAtOrAbove(start.getY());
        require(context, constrained.success(),
                "minimumY search rejected an available longer safe route: "
                        + constrained.reason());
        require(context, goal.equals(constrained.resolvedGoal()),
                "minimumY route did not resolve the exact goal");
        require(context, constrained.path().stream()
                        .allMatch(node -> node.pos().getY() >= start.getY()),
                "minimumY search expanded into the forbidden descent");
        require(context, constrained.path().size() > ordinary.path().size(),
                "minimumY route did not take the longer safe detour");
        context.succeed();
    }

    @GameTest(environment = "minecraftai-gametest:action_pack_physical_snap_game_tests_dynamic_rear_closure_fails_before_constrained_terminal_success", maxTicks = 300)
    public void dynamicRearClosureFailsBeforeConstrainedTerminalSuccess(
            GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(4, 4, 4));
        BlockPos goal = start.east(7);
        for (int dx = -1; dx <= 8; dx++) {
            BlockPos corridor = start.offset(dx, 0, 0);
            world.setBlock(
                    corridor.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(
                    corridor, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(
                    corridor.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            for (int dz : new int[]{-1, 1}) {
                BlockPos wall = corridor.offset(0, 0, dz);
                world.setBlock(
                        wall, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(
                        wall.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        world.setBlock(
                goal.east(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(
                goal.east().above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        String name = "ConstrainedReturnLeaseGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        270.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world,
                start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 270.0F, 0.0F, true);
        PathfindingResult outbound = new AStarPathfinder(
                world, start, goal, 10_000, 50L, false, false)
                .findPathUncached();
        require(context, outbound.success(), "fixture outbound route failed");
        PathfindingResult cachedReturn = new AStarPathfinder(
                world, goal, start, 10_000, 50L, false, false).findPath();
        require(context, cachedReturn.success(),
                "fixture could not warm the ordinary TTL return cache");
        PathfindingResult returnProof = new AStarPathfinder(
                world, goal, start, 10_000, 50L, false, false)
                .findPathUncached();
        PathExecutor.RouteContract contract =
                PathExecutor.RouteContract.constrainedSurface(start.getY(), start);
        require(context, PathExecutor.validateRouteContract(
                        outbound, goal, contract, returnProof).accepted(),
                "fixture contract was not initially reversible");
        PathExecutor executor = new PathExecutor(
                outbound.path(), goal, false, false, 0, contract);
        AtomicBoolean sealed = new AtomicBoolean();
        AtomicReference<ActionResult> terminal = new AtomicReference<>();

        context.failIfEver(() -> {
            ActionResult result = executor.tick(bot.getActionPack());
            if (!result.isInProgress()) {
                terminal.compareAndSet(null, result);
            }
            if (!sealed.get() && bot.blockPosition().equals(goal)
                    && result.isInProgress()) {
                BlockPos rear = goal.west();
                world.setBlock(
                        rear, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(
                        rear.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                sealed.set(true);
                return;
            }
            ActionResult finished = terminal.get();
            if (finished == null) {
                return;
            }
            require(context, sealed.get(), "executor ended before rear closure");
            require(context, finished.isFailed()
                            && finished.reason().startsWith("route_contract_lost:"),
                    "rear closure did not produce typed contract loss: "
                            + finished.reason());
            require(context, bot.blockPosition().equals(goal),
                    "terminal return lease was not exercised at the exact goal");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    /**
     * A route from a cell that is not standable begins with a walked step onto the adjacent standable cell (the bot first settles in
     * the layer below, then steps east onto the landing); no snap, teleport or privileged relocation exists on the way.
     */
    @GameTest(maxTicks = 220)
    public void invalidStartUsesAdjacentWalkedLandingWithoutAnySnap(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos invalid = context.absolutePos(new BlockPos(5, 6, 5));
        BlockPos landing = invalid.offset(1, -1, 0);
        BlockPos goal = landing.offset(2, 0, 0);

        for (int x = landing.getX(); x <= goal.getX(); x++) {
            BlockPos feet = new BlockPos(x, landing.getY(), landing.getZ());
            world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        world.setBlock(invalid.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(invalid, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(invalid.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // Floor under the hole so the bot settles one layer down instead of falling out of the world.
        world.setBlock(invalid.below().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        String name = "PhysicalSnapGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(invalid),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        NavEngineSelector.setBotEngine(bot.getUUID(), NavEngine.BARITONE);
        BotFixtureMoves.place(bot, invalid);
        Standability.clearCache();

        require(context, !Standability.isStandable(world, invalid),
                "fixture start unexpectedly standable");
        TeleportAudit.reset(bot);
        Vec3 before = bot.position();
        ActionResult result = bot.getActionPack().startPathTo(goal);
        require(context, !result.isFailed(), "strict physical start recovery failed: " + result.reason());
        require(context, bot.position().distanceToSqr(before) < 1.0E-12D,
                "starting the route moved the bot to " + bot.blockPosition().toShortString());
        AtomicInteger ticks = new AtomicInteger();
        AtomicBoolean visitedLanding = new AtomicBoolean();
        context.onEachTick(() -> {
            int tick = ticks.incrementAndGet();
            require(context, TeleportAudit.corrections(bot) == 0 && TeleportAudit.count(bot, TeleportAudit.Kind.PRIVILEGED) == 0,
                    "the start was corrected by a teleport (" + TeleportAudit.lastCaller(bot) + ")");
            if (bot.blockPosition().equals(landing)) {
                visitedLanding.set(true);
            }
            if (bot.getActionPack().isPathExecutorIdle() && tick > 3) {
                require(context, visitedLanding.get(), "the bot never stood on the adjacent landing cell");
                require(context, bot.blockPosition().closerThan(goal, 1.5D),
                        "the walked route ended away from the goal: " + bot.blockPosition().toShortString());
                require(context, Standability.isStandable(world, bot.blockPosition()),
                        "the walked recovery ended on a non-standable cell");
                NavEngineSelector.clearBotEngine(bot.getUUID());
                AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
                context.succeed();
            }
            require(context, tick < 210, "timed out at " + bot.blockPosition().toShortString());
        });
    }

    /**
     * Session log 01:51:50-01:53:02: 42 path_start_physical_snap events while the bot yo-yoed
     * between two cells -- a straight-line walk kept carrying it back into the invalid start cell
     * and every failed search snapped it out again.  Once the first planned physical step is
     * accepted by a controller, a second start out of the same cell inside the stall window must
     * be refused (strict survival then reports NO_START) instead of repeating. Planning alone
     * deliberately does not consume the retry window, because a failed search never moved the bot.
     */
    @GameTest(maxTicks = 20)
    public void secondPhysicalSnapOutOfTheSameCellIsRefusedInsteadOfYoYoing(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos invalid = context.absolutePos(new BlockPos(5, 6, 5));
        BlockPos landing = invalid.offset(1, -1, 0);
        BlockPos goal = landing.offset(2, 0, 0);

        for (int x = landing.getX(); x <= goal.getX(); x++) {
            BlockPos feet = new BlockPos(x, landing.getY(), landing.getZ());
            world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        world.setBlock(invalid.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(invalid, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(invalid.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        String name = "SnapYoYoGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(invalid),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        BotFixtureMoves.place(bot, invalid);
        Standability.clearCache();
        TeleportAudit.reset(bot);
        var pack = bot.getActionPack();
        require(context, pack.snapPlayerToNearestStandable("gametest_first_snap")
                        && pack.startCell().equals(landing)
                        && bot.blockPosition().equals(invalid),
                "the first start out of the invalid cell did not plan the adjacent cell without moving the bot: "
                        + pack.startCell().toShortString() + " / " + bot.blockPosition().toShortString());

        // The guarded-admission boundary records the repeat guard only after a controller has
        // accepted this exact prefix. A plan abandoned by a failed A* search must remain retryable.
        WalkedStep firstStep = pack.takeStartStep();
        require(context, firstStep != null && pack.runStep(firstStep) != null,
                "the first physical start step was not admitted");
        pack.cancelStep();

        // Something (the old straight-line walk fallback) carries the bot back into the same cell
        // after that accepted start step was cancelled/replaced.
        BotFixtureMoves.place(bot, invalid);
        Standability.clearCache();
        require(context, !pack.snapPlayerToNearestStandable("gametest_second_snap"),
                "a second start out of the same cell inside the stall window was not refused");
        require(context, bot.blockPosition().equals(invalid),
                "the refused start still moved the bot to " + bot.blockPosition().toShortString());
        require(context, TeleportAudit.corrections(bot) == 0, "a start teleported the bot (" + TeleportAudit.lastCaller(bot) + ")");

        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}

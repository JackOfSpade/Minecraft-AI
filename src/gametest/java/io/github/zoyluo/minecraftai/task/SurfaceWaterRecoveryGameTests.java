package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.TeleportAudit;
import io.github.zoyluo.minecraftai.gametest.BotFixtureMoves;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.phys.Vec3;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Live proof that a clientless fake player leaves shallow water through adjacent physical motion. */
public final class SurfaceWaterRecoveryGameTests {
    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_proactive_rescue_steps_onto_dry_ground", maxTicks = 80)
    public void proactiveRescueStepsOntoDryGround(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 5, -32));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = start.offset(dx, 0, dz);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        world.setBlock(start, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        if (Standability.isStandable(world, start)) {
            context.fail(Component.nullToEmpty("water cell must not be an ordinary A* stand position"));
        }

        String name = "SurfaceWaterRecoveryGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        NavSafetyNet.INSTANCE.requestWaterRescue(bot);

        context.failIfEver(() -> {
            if (bot.blockPosition().equals(start)
                    || bot.isInWater()
                    || !Standability.isStandable(world, bot.blockPosition())
                    || NavSafetyNet.INSTANCE.isWaterRescueActive(bot)) {
                return;
            }
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_connected_shore_beats_an_unneeded_vertical_air_stroke", maxTicks = 160)
    public void connectedShoreBeatsAnUnneededVerticalAirStroke(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 8, -106));

        // Seal a compact vertical shaft. A real dry landing is connected through the water cell
        // below the bot, while the water column above remains open. Connected-shore BFS must keep
        // priority over the low-air fallback; otherwise a harmless full-air rescue abandons a
        // proved route and starts climbing without making progress toward dry footing.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -3; dy <= 4; dy++) {
                    world.setBlock(start.offset(dx, dy, dz),
                            Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        // The bot now needs real time to swim to the landing. Water beside an open air cell flows into it, so the landing would stop being
        // dry within a few ticks: these placements schedule no fluid tick, update no neighbour and no shape, keeping the shaft as built.
        int still = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;
        for (int dy = -1; dy <= 2; dy++) {
            world.setBlock(start.above(dy), Blocks.WATER.defaultBlockState(), still);
        }
        BlockPos lowerShore = start.below().east();
        world.setBlock(lowerShore, Blocks.AIR.defaultBlockState(), still);
        world.setBlock(lowerShore.above(), Blocks.AIR.defaultBlockState(), still);
        world.setBlock(lowerShore.below(), Blocks.STONE.defaultBlockState(), still);
        Standability.clearCache();

        String name = "WaterMonotonicAscentGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY() + 0.125D,
                start.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, false);
        bot.setAirSupply(300);
        TeleportAudit.reset(bot);
        NavSafetyNet.INSTANCE.requestWaterRescue(bot);

        // The rescue swims and walks by real inputs (a few ticks per cell, no teleport): with the connected lower shore proved it goes
        // down and out, and never takes the vertical air stroke first (the feet stay in the lower cells of the shaft).
        context.failIfEver(() -> {
            require(context, bot.getY() < start.getY() + 1.0D,
                    "water rescue took the vertical air stroke before the connected lower shore: y=" + bot.getY());
            require(context, bot.isAlive() && bot.getHealth() == bot.getMaxHealth(),
                    "monotonic ascent lost health");
            if (!bot.blockPosition().equals(lowerShore) || NavSafetyNet.INSTANCE.isWaterRescueActive(bot)) {
                return;
            }
            require(context, TeleportAudit.corrections(bot) == 0,
                    "water rescue teleported the bot: " + TeleportAudit.lastCaller(bot));
            NavSafetyNet.INSTANCE.clear(bot);
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_emergency_vertical_step_requires_low_air", maxTicks = 120)
    public void emergencyVerticalStepRequiresLowAir(GameTestHelper context) {
        WaterShaftFixture fixture = sealedWaterShaftFixture(context, -126);
        AIPlayerEntity bot = fixture.bot();
        bot.setAirSupply(300);
        NavSafetyNet.INSTANCE.requestWaterRescue(bot);

        TeleportAudit.reset(bot);

        require(context, NavSafetyNet.INSTANCE.tickBot(context.getLevel().getServer(), bot),
                "full-air rescue did not take control");
        require(context, bot.getActionPack().stepIdle() && bot.blockPosition().equals(fixture.lower()),
                "full-air rescue used the emergency vertical step: "
                        + bot.blockPosition().toShortString());
        bot.setAirSupply(100);

        require(context, NavSafetyNet.INSTANCE.tickBot(context.getLevel().getServer(), bot),
                "low-air rescue did not take control");
        require(context, !bot.getActionPack().stepIdle(),
                "low-air rescue failed to start the physical upward water step: "
                        + bot.blockPosition().toShortString());

        // The upward step is a real swim (jump key) up the shaft: no teleport.
        context.failIfEver(() -> {
            if (!bot.blockPosition().equals(fixture.lower().above())) {
                return;
            }
            require(context, TeleportAudit.corrections(bot) == 0,
                    "low-air rescue teleported the bot: " + TeleportAudit.lastCaller(bot));
            NavSafetyNet.INSTANCE.clear(bot);
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), fixture.name());
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_rescue_routes_around_a_wall_even_when_the_first_step_moves_away_from_shore", maxTicks = 320)
    public void rescueRoutesAroundAWallEvenWhenTheFirstStepMovesAwayFromShore(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 5, -38));
        // Seal the local volume, then carve a U-shaped two-block-deep water route. The only dry
        // landing is geometrically close behind NORTH, but NORTH itself is a wall. Reaching it
        // requires EAST as the first step, which the old greedy distance check permanently
        // rejected.
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                world.setBlock(start.offset(dx, -1, dz),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(start.offset(dx, 0, dz),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(start.offset(dx, 1, dz),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(start.offset(dx, 2, dz),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        List<BlockPos> waterRoute = List.of(
                start, start.east(), start.east().north(), start.east().north(2));
        for (BlockPos cell : waterRoute) {
            world.setBlock(cell, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        // A real hop out of the water needs headroom (a jump lifts the head 1.25 blocks; the old teleport needed none): open the
        // cells above the ceiling over every route cell and over the landing.
        for (BlockPos cell : waterRoute) {
            world.setBlock(cell.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        world.setBlock(start.north(2).above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.north(2).above(3), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // Keep only the initial eye cell submerged. A waterlogged plant supplies real water
        // without turning the whole upper route into spreading sources that would flood the dry
        // endpoint before the rescue reaches it.
        world.setBlock(start.above(), Blocks.SEAGRASS.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos shore = start.north(2).above();
        world.setBlock(shore, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(shore.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        String name = "WaterWallDetourGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setAirSupply(260);
        TeleportAudit.reset(bot);
        AtomicReference<BlockPos> previous = new AtomicReference<>(start.immutable());
        AtomicBoolean sawRequiredDetour = new AtomicBoolean();

        context.failIfEver(() -> {
            BlockPos now = bot.blockPosition();
            BlockPos before = previous.getAndSet(now.immutable());
            if (!now.equals(before)) {
                int dx = Math.abs(now.getX() - before.getX());
                int dy = Math.abs(now.getY() - before.getY());
                int dz = Math.abs(now.getZ() - before.getZ());
                int changed = (dx == 0 ? 0 : 1) + (dy == 0 ? 0 : 1) + (dz == 0 ? 0 : 1);
                require(context, dx <= 1 && dy <= 1 && dz <= 1 && changed <= 2,
                        "water rescue used a non-adjacent movement: " + before + " -> " + now);
            }
            if (now.equals(start.east())) {
                sawRequiredDetour.set(true);
            }
            if (!now.equals(shore) || bot.isInWater()
                    || NavSafetyNet.INSTANCE.isWaterRescueActive(bot)) {
                return;
            }
            require(context, sawRequiredDetour.get(),
                    "rescue reached the blocked shore without taking the physical EAST detour");
            require(context, bot.isAlive() && bot.getHealth() == bot.getMaxHealth(),
                    "rescue lost health before reaching the dry landing");
            require(context, TeleportAudit.corrections(bot) == 0,
                    "rescue teleported the bot: " + TeleportAudit.lastCaller(bot));
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_descend_seals_ingress_and_hands_off_a_dry_ore_layer", maxTicks = 160)
    public void descendSealsIngressAndHandsOffADryOreLayer(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 5, -44));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = start.offset(dx, 0, dz);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        // A side source feeds a flowing-water work cell after the staircase has opened it. The
        // target Y is already reached: Descend must not report success while the next OreDig would
        // still start submerged.
        world.setBlock(start.east(), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start,
                Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1), Block.UPDATE_ALL);
        Standability.clearCache();

        String name = "DescendWaterSealGT";
        BlockPos drySpawn = start.west(2);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(drySpawn),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 8));
        DescendToYTask task = new DescendToYTask(start.getY());
        // Drive the first task tick synchronously, before the global safety net can relocate the
        // bot. This proves Descend itself seals the newly opened ingress instead of merely
        // completing wet and relying on a later rescue tick to hide the bad handoff.
        task.start(bot);
        task.tick(bot);
        if (task.state() != TaskState.RUNNING) {
            context.fail(Component.nullToEmpty("Descend completed before sealing its wet target layer"
                    + " bot=" + bot.blockPosition().toShortString()
                    + " expected=" + start.toShortString()
                    + " feet=" + world.getBlockState(bot.blockPosition()).getBlock()
                    + " east=" + world.getBlockState(start.east()).getBlock()
                    + " east_fluid=" + world.getFluidState(start.east())));
        }
        if (!world.getBlockState(start.east()).is(Blocks.COBBLESTONE)) {
            context.fail(Component.nullToEmpty("side ingress was not physically sealed"));
        }

        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("water handoff ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            if (bot.isUnderWater() || bot.isInWater()) {
                context.fail(Component.nullToEmpty("Descend completed on a wet ore-layer handoff"));
            }
            if (!Standability.isStandable(world, bot.blockPosition())) {
                context.fail(Component.nullToEmpty("Descend completed without dry footing at "
                        + bot.blockPosition().toShortString()));
            }
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_descend_does_not_retry_safety_rejected_landing", maxTicks = 220)
    public void descendDoesNotRetrySafetyRejectedLanding(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 6, -56));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        world.setBlock(start.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos rejected = start.north().below();
        BlockPos alternate = start.east().below();
        world.setBlock(rejected.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(alternate.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        String name = "DescendRejectedLandingGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        DescendToYTask task = new DescendToYTask(start.getY() - 1);
        task.start(bot);
        String rejectedText = rejected.getX() + "," + rejected.getY() + "," + rejected.getZ();
        // The two stair steps are walked (11 game ticks each plus the tick that settles them).
        DescendTickStages.run(context,
                DescendTickStages.tickUntil(context, task, bot, 60, "fixture did not exercise the initial north landing",
                        () -> rejectedText.equals(task.checkpoint().get("pending_landing_target"))),
                () -> {
                    require(context, bot.blockPosition().equals(rejected),
                            "fixture did not exercise the initial north landing");

                    // Reproduce the production ordering explicitly: a dynamic footing change makes the just
                    // accepted landing unsafe, SafetyNet returns the bot to its origin, then TaskManager gets
                    // the next tick before SafetyNet can release rescue ownership.
                    world.setBlock(rejected.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    Standability.clearCache();
                    NavSafetyNet.INSTANCE.requestWaterRescue(bot);
                    BotFixtureMoves.place(bot, start);
                    task.tick(bot);
                    require(context, bot.blockPosition().equals(start),
                            "Descend immediately retried the SafetyNet-rejected landing");
                    require(context, NavSafetyNet.INSTANCE.isWaterRescueActive(bot),
                            "task incorrectly cleared SafetyNet ownership");

                    require(context, !NavSafetyNet.INSTANCE.tickBot(world.getServer(), bot),
                            "dry origin should release rescue without another movement");
                    require(context, !NavSafetyNet.INSTANCE.isWaterRescueActive(bot),
                            "SafetyNet did not release rescue at the dry origin");
                    return true;
                },
                DescendTickStages.tickUntil(context, task, bot, 60, "Descend did not rotate to the safe alternate landing",
                        () -> bot.blockPosition().equals(alternate)),
                DescendTickStages.tickUntil(context, task, bot, 30, "Descend did not complete from the alternate dry landing",
                        () -> task.state() == TaskState.COMPLETED),
                () -> {
                    require(context, Standability.isStandable(world, alternate),
                            "alternate handoff is not physically standable");

                    AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
                    context.succeed();
                    return true;
                });
    }

    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_descend_relocates_from_a_shoreline_dead_star_before_mutating", maxTicks = 200)
    public void descendRelocatesFromAShorelineDeadStarBeforeMutating(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 6, -50));
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    world.setBlock(start.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        world.setBlock(start.below(), Blocks.SAND.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.below(2), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        // Reproduce the seed-3000 shoreline topology: NORTH/WEST have no lower support while
        // EAST/SOUTH are water. The cardinal same-level fallback is therefore also invalid.
        world.setBlock(start.east().below(), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.south().below(), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);

        BlockPos staging = start.south().west();
        world.setBlock(staging.below(), Blocks.SAND.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(staging.below(2), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos landing = staging.west().below();
        world.setBlock(landing.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        String name = "DescendFreshEntryRelocationGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.TORCH));
        int torchesBefore = InventoryAction.countItem(bot, Items.TORCH);

        DescendToYTask task = new DescendToYTask(start.getY() - 1);
        task.start(bot);
        DescendToYTask[] restoredTask = new DescendToYTask[1];
        int[] restoredTicks = {0};
        // The diagonal staging step (6 game ticks) and the WEST stair step after the restart (11 game ticks) are walked, each with the
        // tick that settles it.
        DescendTickStages.run(context,
                DescendTickStages.tickUntil(context, task, bot, 60, "Descend did not take the safe diagonal staging step",
                        () -> bot.blockPosition().equals(staging) && bot.getActionPack().stepIdle()
                                && "3".equals(task.checkpoint().get("stair_direction"))),
                () -> {
                    require(context, task.state() == TaskState.RUNNING,
                            "fresh-entry relocation ended Descend: "
                                    + task.state() + ":" + task.failureReason());
                    require(context, bot.blockPosition().equals(staging),
                            "Descend did not take the safe diagonal staging step: "
                                    + bot.blockPosition().toShortString());
                    require(context, InventoryAction.countItem(bot, Items.TORCH) == torchesBefore,
                            "Descend mutated inventory before completing fresh-entry relocation");
                    require(context, world.getBlockState(start).isAir()
                                    && world.getBlockState(staging).isAir()
                                    && world.getBlockState(landing).isAir()
                                    && world.getBlockState(start.below()).is(Blocks.SAND)
                                    && world.getBlockState(staging.below()).is(Blocks.SAND)
                                    && world.getBlockState(landing.below()).is(Blocks.STONE),
                            "Descend mutated shoreline blocks before relocation");

                    Map<String, String> checkpoint = task.checkpoint();
                    require(context, DescendToYTask.inspectCheckpoint(checkpoint).isPresent(),
                            "relocated Descend did not publish a valid checkpoint");
                    task.cancel(bot, "gametest_restart_after_entry_relocation");
                    DescendToYTask restored = new DescendToYTask(start.getY() - 1, checkpoint);
                    restored.start(bot);
                    restoredTask[0] = restored;
                    return true;
                },
                DescendTickStages.tickUntil(context, () -> restoredTask[0], bot, 80,
                        "restarted Descend did not complete from the relocated staging",
                        () -> {
                            restoredTicks[0]++;
                            return restoredTask[0].state() == TaskState.COMPLETED;
                        }),
                () -> {
                    require(context, restoredTicks[0] >= 2,
                            "restart proof did not cross two physical server ticks");
                    require(context, bot.blockPosition().equals(landing),
                            "restarted Descend did not use the checkpointed WEST stair: "
                                    + bot.blockPosition().toShortString());
                    require(context, world.getBlockState(start.below()).is(Blocks.SAND)
                                    && world.getBlockState(start.below(2)).is(Blocks.STONE)
                                    && world.getBlockState(staging.below()).is(Blocks.SAND)
                                    && world.getBlockState(staging.below(2)).is(Blocks.STONE)
                                    && world.getBlockState(landing.below()).is(Blocks.STONE),
                            "restarted Descend damaged verified supports");

                    AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
                    context.succeed();
                    return true;
                });
    }

    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_descend_fresh_entry_relocation_cannot_cut_a_diagonal_corner", maxTicks = 40)
    public void descendFreshEntryRelocationCannotCutADiagonalCorner(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 6, -62));
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    world.setBlock(start.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        world.setBlock(start.below(), Blocks.SAND.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.below(2), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.east().below(), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.south().below(), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);

        BlockPos staging = start.south().west();
        world.setBlock(staging.below(), Blocks.SAND.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(staging.below(2), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos landing = staging.west().below();
        world.setBlock(landing.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos blockedCorner = start.west();
        // MAGMA is a full collision corner and also prevents the ordinary upper-detour fallback
        // from treating the wall top as a safe support. The first tick therefore isolates the
        // diagonal corner rule instead of exercising an unrelated one-block retreat.
        world.setBlock(blockedCorner, Blocks.MAGMA_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        String name = "DescendFreshEntryCornerGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        DescendToYTask task = new DescendToYTask(start.getY() - 1);
        task.start(bot);
        task.tick(bot);
        require(context, task.state() == TaskState.FAILED
                        && task.failureReason().startsWith("descend_no_safe_landing"),
                "blocked diagonal corner did not retain fail-closed descent: "
                        + task.state() + ":" + task.failureReason());
        require(context, bot.blockPosition().equals(start),
                "Descend cut across an occupied diagonal corner: "
                        + bot.blockPosition().toShortString());
        require(context, world.getBlockState(blockedCorner).is(Blocks.MAGMA_BLOCK)
                        && world.getBlockState(staging.below()).is(Blocks.SAND)
                        && world.getBlockState(landing.below()).is(Blocks.STONE),
                "failed diagonal preflight mutated the corner fixture");

        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
        context.succeed();
    }

    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_descend_never_mines_the_water_seal_it_just_placed", maxTicks = 200)
    public void descendNeverMinesTheWaterSealItJustPlaced(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 6, -68));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -2; dy <= 3; dy++) {
                    world.setBlock(start.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        world.setBlock(start.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos northLanding = start.north().below();
        BlockPos alternate = start.east().below();
        world.setBlock(northLanding.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(alternate.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos ingress = start.north().above();
        // Give vanilla placement a real adjacent face, matching an aquifer source embedded in a
        // stone wall.  A source floating in the all-air fixture cannot be sealed by player use.
        world.setBlock(ingress.north(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(ingress, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        String name = "DescendSealOwnershipGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 4));

        DescendToYTask task = new DescendToYTask(start.getY() - 1);
        task.start(bot);
        task.tick(bot);
        require(context, world.getBlockState(ingress).is(Blocks.COBBLESTONE),
                "Descend did not physically seal the lateral water source");
        int blocksAfterSeal = InventoryAction.countItem(bot, Items.COBBLESTONE);

        // The old loop selected NORTH again, mined this cobblestone, let water refill it and
        // repeated until all portable blocks were gone.  The sealed direction must instead stay
        // rejected while Descend rotates to EAST.
        // The stair step onto the alternate is walked (11 game ticks plus the tick that settles it).
        int[] ticks = {0};
        DescendTickStages.run(context,
                DescendTickStages.tickUntil(context, task, bot, 60, "Descend did not finish through the alternate dry stair",
                        () -> {
                            require(context, world.getBlockState(ingress).is(Blocks.COBBLESTONE),
                                    "Descend mined its own water seal on tick " + ticks[0]);
                            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == blocksAfterSeal,
                                    "Descend consumed another block after sealing one ingress");
                            ticks[0]++;
                            return task.state() == TaskState.COMPLETED;
                        }),
                () -> {
                    require(context, bot.blockPosition().equals(alternate),
                            "Descend failed to rotate away from the sealed north stair");

                    AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
                    context.succeed();
                    return true;
                });
    }

    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_descend_horizontal_fallback_never_mines_its_owned_water_seal", maxTicks = 60)
    public void descendHorizontalFallbackNeverMinesItsOwnedWaterSeal(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 6, -76));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    world.setBlock(start.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        world.setBlock(start.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos ingress = start.north().above();
        // No lower landing has support, so after sealing NORTH the task must exhaust its stair
        // choices and enter horizontal fallback.  The old fallback then selected this exact
        // cobblestone as its first obstruction and reopened the water forever.
        world.setBlock(ingress.north(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(ingress, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        String name = "DescendHorizontalSealGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 4));

        DescendToYTask task = new DescendToYTask(start.getY() - 1);
        task.start(bot);
        task.tick(bot);
        require(context, world.getBlockState(ingress).is(Blocks.COBBLESTONE),
                "fixture did not establish the Descend-owned water seal");
        int blocksAfterSeal = InventoryAction.countItem(bot, Items.COBBLESTONE);
        Map<String, String> checkpoint = task.checkpoint();
        require(context, DescendToYTask.inspectCheckpoint(checkpoint).isPresent(),
                "Descend did not publish a valid owned-seal checkpoint");
        task.cancel(bot, "gametest_restart");
        task = new DescendToYTask(start.getY() - 1, checkpoint);
        task.start(bot);

        for (int i = 0; i < 20 && task.state() == TaskState.RUNNING; i++) {
            task.tick(bot);
            require(context, world.getBlockState(ingress).is(Blocks.COBBLESTONE),
                    "Descend horizontal fallback mined its owned water seal on tick " + i);
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == blocksAfterSeal,
                    "Descend consumed another block after its fallback reopened the seal");
        }
        require(context, task.state() == TaskState.FAILED
                        && task.failureReason().startsWith("descend_no_safe_landing"),
                "sealed unsupported descent did not fail closed: "
                        + task.state() + ":" + task.failureReason());
        require(context, bot.blockPosition().equals(start),
                "Descend left its supported origin while every landing was unsupported");

        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
        context.succeed();
    }

    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_dig_down_preserves_its_water_seal_and_protected_workstation", maxTicks = 80)
    public void digDownPreservesItsWaterSealAndProtectedWorkstation(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 6, -80));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    world.setBlock(start.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        world.setBlock(start.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos eastLanding = start.east().below();
        world.setBlock(eastLanding.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos ingress = start.north().above();
        world.setBlock(ingress.north(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(ingress, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        String name = "DigDownSealOwnershipGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        // The protected workstation deliberately occupies the first inventory slot. Emergency
        // sealing must choose the disposable cobblestone instead of the first arbitrary BlockItem.
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 4));
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE));

        DigDownTask task = new DigDownTask(Blocks.STONE, 3);
        task.start(bot);
        task.tick(bot);
        require(context, world.getBlockState(ingress).is(Blocks.COBBLESTONE),
                "DigDown did not physically seal the lateral water source");
        require(context, InventoryAction.countItem(bot, Items.CRAFTING_TABLE) == 1,
                "DigDown consumed the protected crafting table as a water seal");
        int blocksAfterSeal = InventoryAction.countItem(bot, Items.COBBLESTONE);

        // The descent onto the dry east stair is a walked step now (several game ticks), so the task is driven tick by tick and the
        // seal invariants are held on every tick until the bot stands on the east landing.
        int[] ticks = {0};
        context.failIfEver(() -> {
            task.tick(bot);
            ticks[0]++;
            require(context, world.getBlockState(ingress).is(Blocks.COBBLESTONE),
                    "DigDown mined its own water seal on tick " + ticks[0]);
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == blocksAfterSeal,
                    "DigDown consumed another emergency block after sealing one ingress");
            require(context, InventoryAction.countItem(bot, Items.CRAFTING_TABLE) == 1,
                    "DigDown lost the protected crafting table after sealing");
            require(context, ticks[0] < 70, "DigDown did not rotate onto the dry east stair: "
                    + bot.blockPosition().toShortString());
            if (bot.blockPosition().equals(eastLanding)) {
                task.cancel(bot, "gametest_complete");
                AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
                context.succeed();
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_horizontal_fallback_never_mines_the_owned_water_seal", maxTicks = 40)
    public void horizontalFallbackNeverMinesTheOwnedWaterSeal(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 6, -94));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    world.setBlock(start.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        world.setBlock(start.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos ingress = start.north();
        // NORTH can support a same-level horizontal move, but has no lower stair support. The
        // other three directions are unsupported. Once NORTH is sealed/rejected, the horizontal
        // fallback must fail closed without treating its own cobblestone wall as mineable stone.
        world.setBlock(ingress.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(ingress, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        String name = "DigDownHorizontalSealGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 4));
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE));

        DigDownTask[] active = {new DigDownTask(Blocks.STONE, 3)};
        active[0].start(bot);
        active[0].tick(bot);
        require(context, world.getBlockState(ingress).is(Blocks.COBBLESTONE),
                "fixture did not establish an owned water seal");
        int blocksAfterSeal = InventoryAction.countItem(bot, Items.COBBLESTONE);
        boolean[] restartedSettleDebt = {false};

        for (int settleTick = 0; settleTick < 40
                && active[0].state() == TaskState.RUNNING; settleTick++) {
            active[0].tick(bot);
            Map<String, String> saved = active[0].checkpoint();
            DigDownTask.DigDownCheckpoint live =
                    DigDownTask.DigDownCheckpoint.decode(saved).orElse(null);
            if (!restartedSettleDebt[0] && live != null
                    && live.phase() == DigDownTask.Phase.DESCEND
                    && live.pickupGrace() > 0) {
                require(context, !live.horizontalMode(),
                        "fixture unexpectedly latched horizontal mode before stair fallback");
                active[0].abort(bot);
                active[0] = new DigDownTask(Blocks.STONE, 3, saved);
                active[0].start(bot);
                restartedSettleDebt[0] = true;
            }
        }
        require(context, restartedSettleDebt[0],
                "fixture never checkpointed the armed stair-to-horizontal settle debt");
        require(context, active[0].state() == TaskState.FAILED
                        && active[0].failureReason().startsWith("dig_down_walled"),
                "fully rejected origin did not fail closed: "
                        + active[0].state() + ":" + active[0].failureReason());
        require(context, EpisodeMemory.INSTANCE.isExcluded(
                        bot.getUUID(), start, bot.level().getServer().getTickCount()),
                "observed walled entry was not retained for the next physical relocation");
        require(context, world.getBlockState(ingress).is(Blocks.COBBLESTONE),
                "horizontal fallback mined its owned water seal");
        require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == blocksAfterSeal,
                "horizontal fallback consumed another emergency block");
        require(context, InventoryAction.countItem(bot, Items.CRAFTING_TABLE) == 1,
                "horizontal fallback consumed the protected workstation");

        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
        context.succeed();
    }

    private static WaterShaftFixture sealedWaterShaftFixture(GameTestHelper context, int z) {
        var world = context.getLevel();
        BlockPos lower = context.absolutePos(new BlockPos(8, 24, z));

        // Fill the complete local rescue window, then carve only a two-cell water shaft. There is
        // deliberately no dry standable target for either connected-shore BFS or the legacy shore
        // search. The first tick therefore isolates the full-air gate; changing only the oxygen
        // level on the second tick proves that the adjacent emergency ascent remains available.
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                for (int dy = -16; dy <= 16; dy++) {
                    world.setBlock(lower.offset(dx, dy, dz),
                            Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        world.setBlock(lower, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(lower.above(), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(lower.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        String name = "WaterShaftGT" + Math.abs(z);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(lower),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, lower.getX() + 0.5D, lower.getY() + 0.125D,
                lower.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, false);
        return new WaterShaftFixture(name, bot, lower.immutable());
    }

    private record WaterShaftFixture(String name, AIPlayerEntity bot, BlockPos lower) {
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}

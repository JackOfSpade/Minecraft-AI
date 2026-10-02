package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.TeleportAudit;
import io.github.zoyluo.minecraftai.gametest.BotFixtureMoves;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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

    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_deep_water_surfaces_before_the_fixed_air_floor", maxTicks = 160)
    public void deepWaterSurfacesBeforeTheFixedAirFloor(GameTestHelper context) {
        // Twelve vertical water cells need more than the historic 120-air floor to surface by
        // real swim input. At 140 air the dynamic threshold must install an upward WalkedStep;
        // the old fixed check merely held jump in place and could leave this deep bot underwater.
        WaterShaftFixture fixture = sealedWaterShaftFixture(context, -134, 12);
        AIPlayerEntity bot = fixture.bot();
        bot.setAirSupply(140);
        NavSafetyNet.INSTANCE.requestWaterRescue(bot);
        TeleportAudit.reset(bot);

        require(context, NavSafetyNet.INSTANCE.tickBot(context.getLevel().getServer(), bot),
                "deep-water rescue did not take control");
        require(context, !bot.getActionPack().stepIdle(),
                "deep-water rescue did not start the physical upward step above the fixed air floor");
        context.failIfEver(() -> {
            if (!bot.blockPosition().equals(fixture.lower().above())) {
                return;
            }
            require(context, TeleportAudit.corrections(bot) == 0,
                    "deep-water ascent teleported the bot: " + TeleportAudit.lastCaller(bot));
            NavSafetyNet.INSTANCE.clear(bot);
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), fixture.name());
            context.succeed();
        });
    }

    /**
     * Water itself is the medium through which an underwater player looks. A strict bot in this
     * multi-cell clear shaft must therefore prove the nearby water column and begin a real upward
     * rescue stroke, rather than treating its own transparent water as an UNKNOWN frontier.
     */
    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_strict_clear_water_shaft_starts_physical_oxygen_ascent", maxTicks = 30)
    public void strictClearWaterShaftStartsPhysicalOxygenAscent(GameTestHelper context) {
        WaterShaftFixture fixture = sealedWaterShaftFixture(context, -142, 5);
        AIPlayerEntity bot = fixture.bot();
        BlockPos next = fixture.lower().above();
        bot.setAirSupply(100);
        MinecraftAiConfig original = MinecraftAiConfig.get();
        try {
            installConfig(withProfile(original, OperatingProfile.STRICT_SURVIVAL));
            require(context, !CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                            "strict_clear_water_shaft").allowed(),
                    "clear-water shaft fixture unexpectedly enabled hidden-world scanning");
            require(context, ObservableWorldQuery.canObserveCellThroughFluids(bot, next)
                            && ObservableWorldQuery.canObserveCellThroughFluids(bot, next.above()),
                    "clear water in the nearby vertical shaft was not observable under the scoped underwater policy");
            TeleportAudit.reset(bot);
            NavSafetyNet.INSTANCE.requestWaterRescue(bot);
            require(context, NavSafetyNet.INSTANCE.tickBot(context.getLevel().getServer(), bot),
                    "strict clear-water rescue did not take control");
            boolean physicalAscent = bot.getActionPack().stepInFlightFor(
                    "navsafe_water_rescue", next, WalkedStep.Kind.SWIM)
                    || bot.getActionPack().stepInFlightFor(
                    "navsafe_water_surface", next, WalkedStep.Kind.SWIM);
            require(context, physicalAscent,
                    "strict clear-water shaft returned UNKNOWN/idle instead of starting its physical upward rescue stroke");
            require(context, bot.blockPosition().equals(fixture.lower()) && TeleportAudit.corrections(bot) == 0,
                    "clear-water rescue moved without its physical walked step or used a correction teleport");
        } finally {
            NavSafetyNet.INSTANCE.clear(bot);
            bot.getActionPack().cancelStep();
            installConfig(original);
            AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), fixture.name());
        }
        context.succeed();
    }

    /**
     * The strict SWIM settling allowance is only for vertical bobbing around a horizontal stroke.
     * An external move to the next cell beyond an admitted vertical ascent must not retain the
     * old rescue lease merely because that displaced feet cell is one block above its destination.
     */
    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_strict_vertical_rescue_guard_rejects_external_bob_outside_stroke", maxTicks = 30)
    public void strictVerticalRescueGuardRejectsExternalBobOutsideStroke(GameTestHelper context) {
        WaterShaftFixture fixture = sealedWaterShaftFixture(context, -143, 4);
        var world = context.getLevel();
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.lower();
        BlockPos next = start.above();
        BlockPos displaced = next.above();
        bot.setAirSupply(100);
        MinecraftAiConfig original = MinecraftAiConfig.get();
        try {
            installConfig(withProfile(original, OperatingProfile.STRICT_SURVIVAL));
            require(context, !CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                            "strict_vertical_rescue_continuation").allowed(),
                    "vertical-rescue fixture unexpectedly enabled hidden-world scanning");
            require(context, ObservableWorldQuery.canObserveCellThroughFluids(bot, next)
                            && ObservableWorldQuery.canObserveCellThroughFluids(bot, next.above()),
                    "vertical-rescue fixture did not start with a visible strict water stroke");
            NavSafetyNet.INSTANCE.requestWaterRescue(bot);
            require(context, NavSafetyNet.INSTANCE.tickBot(world.getServer(), bot),
                    "strict vertical rescue did not take control");
            boolean admittedStroke = bot.getActionPack().stepInFlightFor(
                    "navsafe_water_rescue", next, WalkedStep.Kind.SWIM)
                    || bot.getActionPack().stepInFlightFor(
                    "navsafe_water_surface", next, WalkedStep.Kind.SWIM);
            require(context, admittedStroke,
                    "strict vertical rescue did not start its visibly admitted upward stroke");

            // This is deliberately one water cell beyond the exact start-to-next vertical
            // corridor. It remains observable, so only provenance—not a changed terrain fact—
            // may reject the retained action before WalkedStep reads its raw validator inputs.
            poseWaterObserver(bot, displaced);
            require(context, bot.blockPosition().equals(displaced)
                            && ObservableWorldQuery.canObserveCellThroughFluids(bot, next),
                    "fixture did not establish the visible externally displaced vertical pose");
            TeleportAudit.reset(bot);
            Vec3 before = bot.position();
            bot.getActionPack().onUpdate();
            WalkedStep.Result preOwner = bot.getActionPack().stepResult();
            require(context, bot.getActionPack().stepIdle()
                            && preOwner != null && preOwner.failed()
                            && "continuation_guard".equals(preOwner.reason()),
                    "strict vertical rescue retained an externally displaced swim step: "
                            + (preOwner == null ? "no result" : preOwner.status() + " " + preOwner.reason()));
            require(context, bot.blockPosition().equals(displaced)
                            && bot.position().distanceToSqr(before) < 1.0E-12D
                            && TeleportAudit.corrections(bot) == 0,
                    "the vertical continuation guard advanced or corrected the displaced bot before rejection");
        } finally {
            NavSafetyNet.INSTANCE.clear(bot);
            bot.getActionPack().cancelStep();
            installConfig(original);
            AIPlayerManager.INSTANCE.despawn(world.getServer(), fixture.name());
        }
        context.succeed();
    }

    /**
     * A retained strict UNKNOWN may earn one fresh proof slice, but cannot permanently suppress
     * the only visible water retreat. This uses real A-to-B and B-to-A swim strokes: the forward
     * diagonal stays water behind two solid side columns, so it remains UNKNOWN at B rather than
     * becoming a visible-invalid cell or an invented route.
     */
    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_strict_unknown_frontier_allows_bounded_visible_backtrack", maxTicks = 120)
    public void strictUnknownFrontierAllowsBoundedVisibleBacktrack(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 6, -158));
        BlockPos forward = start.east();
        BlockPos hiddenDiagonal = forward.east().north();
        for (int dx = -3; dx <= 5; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -2; dy <= 3; dy++) {
                    world.setBlock(start.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        // The complete surrounding fill leaves both forward side columns solid. They keep the
        // diagonal water cell hidden from B while A is a directly observable same-level stroke.
        for (BlockPos cell : List.of(start, forward, hiddenDiagonal)) {
            world.setBlock(cell, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        Standability.clearCache();

        String name = "StrictUnknownBacktrackGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        Runnable cleanup = () -> {
            NavSafetyNet.INSTANCE.clear(bot);
            bot.getActionPack().cancelStep();
            AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
        };
        try {
            poseWaterObserver(bot, start);
            bot.setAirSupply(300);
            require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                    "GameTest must run under strict_survival, got " + MinecraftAiConfig.get().profile());
            require(context, !CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                            "strict_unknown_backtrack").allowed(),
                    "unknown-backtrack fixture unexpectedly enabled hidden-world scanning");
            require(context, ObservableWorldQuery.canObserveCellThroughFluids(bot, forward)
                            && ObservableWorldQuery.canObserveCellThroughFluids(bot, forward.above()),
                    "unknown-backtrack fixture did not expose the initial A-to-B water stroke");
            TeleportAudit.reset(bot);
            NavSafetyNet.INSTANCE.requestWaterRescue(bot);
            require(context, NavSafetyNet.INSTANCE.tickBot(world.getServer(), bot),
                    "strict unknown-backtrack rescue did not take control");
            require(context, bot.getActionPack().stepInFlightFor(
                            "navsafe_water_rescue", forward, WalkedStep.Kind.SWIM)
                            && bot.blockPosition().equals(start),
                    "strict unknown-backtrack rescue did not begin the physical A-to-B stroke");
        } catch (RuntimeException | Error failure) {
            cleanup.run();
            throw failure;
        }

        boolean[] reachedForward = {false};
        boolean[] backtrackAdmitted = {false};
        boolean[] settled = {false};
        int[] ticksAtForward = {0};
        int[] ticksAfterBacktrackAdmission = {0};
        int[] totalTicks = {0};
        context.onEachTick(() -> {
            if (settled[0]) {
                return;
            }
            try {
                totalTicks[0]++;
                BlockPos feet = bot.blockPosition();
                require(context, feet.equals(start) || feet.equals(forward),
                        "strict unknown-backtrack rescue left its sealed physical corridor: " + feet);
                require(context, TeleportAudit.corrections(bot) == 0,
                        "strict unknown-backtrack rescue teleported the bot: " + TeleportAudit.lastCaller(bot));
                if (!reachedForward[0] && feet.equals(forward)) {
                    reachedForward[0] = true;
                    require(context, navWaterProbeIsUnknown(bot, world, hiddenDiagonal),
                            "the forward diagonal was not a retained strict UNKNOWN at the real B landing");
                    require(context, ObservableWorldQuery.canObserveCellThroughFluids(bot, start)
                                    && ObservableWorldQuery.canObserveCellThroughFluids(bot, start.above()),
                            "the real B landing lost sight of its only visible water retreat");
                    for (BlockPos neighbor : NavSafetyNet.waterEscapeNeighbors(forward)) {
                        if (neighbor.equals(start) || neighbor.equals(hiddenDiagonal)) {
                            continue;
                        }
                        require(context, navWaterProbeIsObservedInvalid(bot, world, neighbor)
                                        || navWaterProbeIsUnknown(bot, world, neighbor),
                                "fixture exposed an unaccounted legal fallback at B: " + neighbor);
                    }
                }
                if (!reachedForward[0]) {
                    require(context, totalTicks[0] < 60,
                            "strict unknown-backtrack rescue never physically landed its admitted A-to-B stroke");
                    return;
                }
                ticksAtForward[0]++;
                if (!backtrackAdmitted[0]) {
                    backtrackAdmitted[0] = bot.getActionPack().stepInFlightFor(
                            "navsafe_water_rescue", start, WalkedStep.Kind.SWIM);
                    require(context, backtrackAdmitted[0] || ticksAtForward[0] < 12,
                            "a retained UNKNOWN permanently suppressed the only visible B-to-A retreat");
                    return;
                }
                if (feet.equals(start)) {
                    settled[0] = true;
                    cleanup.run();
                    context.succeed();
                    return;
                }
                require(context, ++ticksAfterBacktrackAdmission[0] < 40,
                        "the admitted B-to-A rescue stroke never physically landed");
            } catch (RuntimeException | Error failure) {
                settled[0] = true;
                cleanup.run();
                throw failure;
            }
        });
    }

    /**
     * Fluid-transparent observation is deliberately not wall-transparent. A solid in the shaft
     * must hide water beyond it and prevent the strict rescue from admitting an invented ascent.
     */
    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_strict_solid_shaft_occluder_blocks_unseen_oxygen_ascent", maxTicks = 30)
    public void strictSolidShaftOccluderBlocksUnseenOxygenAscent(GameTestHelper context) {
        WaterShaftFixture fixture = sealedWaterShaftFixture(context, -146, 5);
        var world = context.getLevel();
        AIPlayerEntity bot = fixture.bot();
        BlockPos blocker = fixture.lower().above(2);
        BlockPos hiddenBeyondBlocker = blocker.above();
        world.setBlock(blocker, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();
        bot.setAirSupply(100);
        MinecraftAiConfig original = MinecraftAiConfig.get();
        try {
            installConfig(withProfile(original, OperatingProfile.STRICT_SURVIVAL));
            require(context, !CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                            "strict_solid_shaft_occluder").allowed(),
                    "solid-occluder fixture unexpectedly enabled hidden-world scanning");
            require(context, !ObservableWorldQuery.canObserveCellThroughFluids(bot, hiddenBeyondBlocker),
                    "the scoped underwater observer looked through a solid shaft occluder");
            TeleportAudit.reset(bot);
            NavSafetyNet.INSTANCE.requestWaterRescue(bot);
            require(context, NavSafetyNet.INSTANCE.tickBot(world.getServer(), bot),
                    "strict solid-occluder rescue did not retain control");
            boolean onlyExposedAdjacentStroke = bot.getActionPack().stepInFlightFor(
                    "navsafe_water_rescue", fixture.lower().above(), WalkedStep.Kind.SWIM)
                    || bot.getActionPack().stepInFlightFor(
                    "navsafe_water_surface", fixture.lower().above(), WalkedStep.Kind.SWIM);
            require(context, (bot.getActionPack().stepIdle() || onlyExposedAdjacentStroke)
                            && bot.blockPosition().equals(fixture.lower())
                            && TeleportAudit.corrections(bot) == 0,
                    "solid-occluded strict rescue skipped its exposed adjacent cell, admitted an unseen target beyond the blocker, or used a correction teleport");
        } finally {
            NavSafetyNet.INSTANCE.clear(bot);
            bot.getActionPack().cancelStep();
            installConfig(original);
            AIPlayerManager.INSTANCE.despawn(world.getServer(), fixture.name());
        }
        context.succeed();
    }

    /**
     * Seeing an empty support cell is knowledge that a dry landing is physically impossible, not
     * missing world knowledge. Both strict planners must complete that candidate as invalid rather
     * than retaining it as UNKNOWN/PENDING for a future viewpoint that cannot create a floor.
     */
    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_strict_visible_empty_support_is_known_invalid", maxTicks = 30)
    public void strictVisibleEmptySupportIsKnownInvalid(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 6, -148));
        for (int dx = -1; dx <= 2; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        BlockPos unsupported = start.east();
        world.setBlock(start, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(unsupported.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(unsupported, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(unsupported.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(unsupported.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        String name = "StrictVisibleEmptySupportGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        poseWaterObserver(bot, start);
        MinecraftAiConfig original = MinecraftAiConfig.get();
        try {
            installConfig(withProfile(original, OperatingProfile.STRICT_SURVIVAL));
            require(context, ObservableWorldQuery.canObserveCellThroughFluids(bot, unsupported)
                            && ObservableWorldQuery.canObserveCellThroughFluids(bot, unsupported.above())
                            && ObservableWorldQuery.canObserveCellThroughFluids(bot, unsupported.below()),
                    "empty-support fixture did not leave the body and absent floor visibly provable");
            SwimRoute.CellProbe routeProbe = SwimRoute.probeCell(bot, world, unsupported, false);
            require(context, routeProbe.observed() && routeProbe.cell() == null,
                    "strict SwimRoute retained a visibly empty support as UNKNOWN instead of known-invalid");
            require(context, navWaterProbeIsObservedInvalid(bot, world, unsupported),
                    "strict Nav rescue retained a visibly empty support as UNKNOWN instead of known-invalid");
        } finally {
            NavSafetyNet.INSTANCE.clear(bot);
            bot.getActionPack().cancelStep();
            installConfig(original);
            AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
        }
        context.succeed();
    }

    /**
     * A physical operator rescue may have an expired route deadline when capability changes at
     * the same feet/shore/cell-count context. Strict survival cannot inherit that privileged
     * timing state, and returning to operator must receive a fresh deadline rather than teleport
     * immediately from the old one.
     */
    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_operator_deadline_does_not_survive_strict_context_round_trip", maxTicks = 30)
    public void operatorDeadlineDoesNotSurviveStrictContextRoundTrip(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 6, -152));
        for (int dx = -1; dx <= 4; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        BlockPos next = start.east();
        BlockPos shore = next.east();
        for (BlockPos cell : List.of(start, next)) {
            world.setBlock(cell, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        world.setBlock(shore, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(shore.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(shore.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        String name = "DeadlineProfileContextGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        poseWaterObserver(bot, start);
        bot.setAirSupply(260);
        MinecraftAiConfig original = MinecraftAiConfig.get();
        try {
            installConfig(withProfile(original, OperatingProfile.OPERATOR));
            NavSafetyNet.INSTANCE.requestWaterRescue(bot);
            require(context, NavSafetyNet.INSTANCE.tickBot(world.getServer(), bot)
                            && bot.getActionPack().stepInFlightFor(
                            "navsafe_water_rescue", next, WalkedStep.Kind.SWIM),
                    "operator rescue did not accrue its physical route deadline");
            DeadlineSnapshot oldOperatorDeadline = rescueDeadlineForTest(bot);
            require(context, oldOperatorDeadline.feet().equals(start)
                            && oldOperatorDeadline.shore().equals(shore)
                            && oldOperatorDeadline.hiddenWorldScan(),
                    "operator deadline did not retain the proved route context");

            // Freeze the already-admitted physical step while retaining its deadline, then make
            // that old operator deadline expired. This deterministically represents the interval
            // before an interrupted rescue is revisited without burning a long live-server wait.
            releaseRescueStepForTest(bot, true);
            replaceRescueDeadlineForTest(bot, oldOperatorDeadline.withDeadline(
                    world.getServer().getTickCount() - 1));

            installConfig(withProfile(original, OperatingProfile.STRICT_SURVIVAL));
            require(context, NavSafetyNet.INSTANCE.tickBot(world.getServer(), bot),
                    "strict mode did not reconcile the interrupted operator water route");
            DeadlineSnapshot strictDeadline = rescueDeadlineForTest(bot);
            require(context, !strictDeadline.hiddenWorldScan()
                            && strictDeadline.deadlineTick() > world.getServer().getTickCount(),
                    "strict rescue inherited an expired operator deadline at unchanged route coordinates");
            releaseRescueStepForTest(bot, true);

            installConfig(withProfile(original, OperatingProfile.OPERATOR));
            TeleportAudit.reset(bot);
            require(context, NavSafetyNet.INSTANCE.tickBot(world.getServer(), bot),
                    "operator mode did not re-open the physical rescue after strict interruption");
            DeadlineSnapshot freshOperatorDeadline = rescueDeadlineForTest(bot);
            require(context, freshOperatorDeadline.hiddenWorldScan()
                            && freshOperatorDeadline.deadlineTick() > world.getServer().getTickCount()
                            && bot.blockPosition().equals(start)
                            && TeleportAudit.corrections(bot) == 0
                            && bot.getActionPack().stepInFlightFor(
                            "navsafe_water_rescue", next, WalkedStep.Kind.SWIM),
                    "returning to operator reused an expired strict/operator deadline and teleported instead of beginning a fresh physical rescue");
        } finally {
            NavSafetyNet.INSTANCE.clear(bot);
            bot.getActionPack().cancelStep();
            installConfig(original);
            AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
        }
        context.succeed();
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

    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_strict_rescue_explores_visible_water_before_a_hidden_route", maxTicks = 80)
    public void strictRescueExploresVisibleWaterBeforeAHiddenRoute(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 5, -42));
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }

        // Fixed, non-overlapping cardinals keep this differential independent of the complete
        // 26-cell rescue-envelope order (whose first member is deliberately diagonal).
        // The dead end is visible; the north route bends to a dry shore that strict cannot see.
        BlockPos visibleDeadEnd = start.west();
        BlockPos hiddenRouteFirst = start.north();
        require(context, NavSafetyNet.waterEscapeNeighbors(start).containsAll(
                        List.of(visibleDeadEnd, hiddenRouteFirst)),
                "water rescue neighbourhood lost required cardinal cells");
        int routeDx = hiddenRouteFirst.getX() - start.getX();
        int routeDz = hiddenRouteFirst.getZ() - start.getZ();
        // Walk two cells forward before turning: diagonal strokes are valid physical water moves,
        // so a one-cell bend would make a later route cell directly adjacent to the origin and
        // keep the hidden shore outside one direct observed step from the origin.
        BlockPos forwardTwo = hiddenRouteFirst.offset(routeDx, 0, routeDz);
        // Turn clockwise from the route's first horizontal edge, leaving a solid corner that
        // blocks a direct line from the bot's eye to the eventual shore.
        int turnDx = -routeDz;
        int turnDz = routeDx;
        BlockPos bendOne = forwardTwo.offset(turnDx, 0, turnDz);
        BlockPos bendTwo = bendOne.offset(turnDx, 0, turnDz);
        BlockPos shore = bendTwo.offset(turnDx, 0, turnDz);
        List<BlockPos> waterRoute = List.of(start, visibleDeadEnd, hiddenRouteFirst, forwardTwo, bendOne, bendTwo);
        require(context, waterRoute.stream().distinct().count() == waterRoute.size(),
                "hidden-route differential accidentally reuses a water cell: " + waterRoute);
        for (BlockPos cell : waterRoute) {
            world.setBlock(cell, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        world.setBlock(shore, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(shore.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(shore.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // Leave the eye above the source water: the local visible step must be proved by a real
        // fluid-aware ray rather than by a waterlogged decoration in the observer's own cell.
        world.setBlock(start.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        String name = "StrictWaterObservationGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        poseWaterObserver(bot, start);
        bot.setAirSupply(260);
        MinecraftAiConfig original = MinecraftAiConfig.get();
        try {
            // Neither profile may take the raw hidden route. Exercise operator first so an old
            // configuration spelling cannot reintroduce it, then prove strict takes the same
            // visible local exploration step.
            installConfig(withProfile(original, OperatingProfile.OPERATOR));
            require(context, !CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                            "operator_water_observation_gametest").allowed(),
                    "operator unexpectedly enabled hidden water scans");
            TeleportAudit.reset(bot);
            NavSafetyNet.INSTANCE.requestWaterRescue(bot);
            require(context, NavSafetyNet.INSTANCE.tickBot(world.getServer(), bot),
                    "operator water rescue did not take control");
            require(context, bot.getActionPack().stepInFlightFor(
                            "navsafe_water_rescue", visibleDeadEnd, WalkedStep.Kind.SWIM),
                    "operator rescue did not choose its visible local exploration step");
            bot.getActionPack().cancelStep();
            NavSafetyNet.INSTANCE.clear(bot);

            installConfig(withProfile(original, OperatingProfile.STRICT_SURVIVAL));
            require(context, !CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                            "strict_water_observation_gametest").allowed(),
                    "strict_survival unexpectedly allowed hidden water scans");
            require(context, ObservableWorldQuery.canObserveCellThroughFluids(bot, visibleDeadEnd)
                            && ObservableWorldQuery.canObserveCellThroughFluids(bot, visibleDeadEnd.above()),
                    "fixture must offer a visible local water step");
            require(context, !ObservableWorldQuery.canObserveCellThroughFluids(bot, shore),
                    "fixture must keep the dry shore behind the opaque turn");
            NavSafetyNet.INSTANCE.requestWaterRescue(bot);
            require(context, NavSafetyNet.INSTANCE.tickBot(world.getServer(), bot),
                    "strict water rescue did not take control");
            require(context, bot.getActionPack().stepInFlightFor(
                            "navsafe_water_rescue", visibleDeadEnd, WalkedStep.Kind.SWIM),
                    "strict rescue did not choose its visible local exploration step");
        } finally {
            // The launched step was admitted under strict survival. Restoring the original default
            // before later server ticks therefore cannot leak an operator step across the test.
            installConfig(original);
        }

        context.failIfEver(() -> {
            require(context, !bot.blockPosition().equals(hiddenRouteFirst),
                    "strict rescue committed directly to a route whose shore was hidden behind a wall");
            if (!bot.blockPosition().equals(visibleDeadEnd)) {
                return;
            }
            require(context, TeleportAudit.corrections(bot) == 0,
                    "strict water rescue teleported the bot: " + TeleportAudit.lastCaller(bot));
            NavSafetyNet.INSTANCE.clear(bot);
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    /**
     * A strict rescue may reuse a short-lived route memo only after re-proving its first step and
     * shore. Cancel the initial physical action without moving the bot, make that cached next cell
     * opaque/unsafe, then require the following cache hit to refuse it rather than steering from
     * yesterday's observation.
     */
    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_strict_cached_route_reproof_rejects_new_occluder", maxTicks = 30)
    public void strictCachedRouteReproofRejectsNewOccluder(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 6, -40));
        for (int dx = -2; dx <= 4; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        // The first water edge is diagonal; an unsupported east sight gap plus a head-height
        // north slit expose that water and its later shore without adding a cardinal dry rescue
        // alternative. A straight two-water tube would correctly occlude its far shore under
        // Fluid.ANY ray policy.
        BlockPos next = start.east().north();
        BlockPos shore = start.east(2);
        BlockPos sightGap = start.east();
        for (BlockPos cell : List.of(start, next)) {
            world.setBlock(cell, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        world.setBlock(sightGap.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(sightGap, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(sightGap.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(sightGap.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.north().above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(shore, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(shore.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(shore.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        String name = "StrictWaterCacheReproofGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        poseWaterObserver(bot, start);
        bot.setAirSupply(260);
        MinecraftAiConfig original = MinecraftAiConfig.get();
        try {
            installConfig(withProfile(original, OperatingProfile.STRICT_SURVIVAL));
            require(context, !CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                            "strict_water_cache_reproof").allowed(),
                    "cache fixture unexpectedly enabled a hidden scan");
            require(context, ObservableWorldQuery.canObserveCellThroughFluids(bot, next)
                            && ObservableWorldQuery.canObserveCellThroughFluids(bot, next.above())
                            && ObservableWorldQuery.canObserveCellThroughFluids(bot, shore)
                            && ObservableWorldQuery.canObserveCellThroughFluids(bot, shore.above()),
                    "fixture did not establish a fully visible cacheable route");
            TeleportAudit.reset(bot);
            NavSafetyNet.INSTANCE.requestWaterRescue(bot);
            require(context, NavSafetyNet.INSTANCE.tickBot(world.getServer(), bot),
                    "strict rescue did not build its visible route cache");
            require(context, bot.getActionPack().stepInFlightFor(
                            "navsafe_water_rescue", next, WalkedStep.Kind.SWIM),
                    "strict rescue did not start the route's visible first action");
            Vec3 before = bot.position();
            bot.getActionPack().cancelStep();
            require(context, bot.position().distanceToSqr(before) < 1.0E-12D
                            && bot.blockPosition().equals(start),
                    "cancelling the first cached-route action moved the bot before reproof");
            // Generic cancellation deliberately does not release the strict owner's opaque
            // admission. An unrelated controller must not use the brief owner-reconciliation
            // window to replace it with an unguarded lookalike before ActionPack.onUpdate().
            ActionPack.StepLease foreignLease = bot.getActionPack().runStep(
                    WalkedStep.begin(bot, next, WalkedStep.Kind.SWIM, "navsafe_water_rescue"));
            require(context, foreignLease == null && bot.getActionPack().stepIdle(),
                    "generic cancellation let an unguarded same-cell/kind rescue replacement bypass its lease");
            bot.getActionPack().onUpdate();
            require(context, bot.getActionPack().stepIdle()
                            && bot.position().distanceToSqr(before) < 1.0E-12D,
                    "a blocked generic-cancel replacement raw-continued before NavSafetyNet reconciled it");

            // This head-height opaque block removes the cached first cell's physical/visible
            // admission while preserving the bot's feet and the cache's normal age/position key.
            // A second tick must not consume the old cache as a steering instruction.
            world.setBlock(next.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            require(context, NavSafetyNet.INSTANCE.tickBot(world.getServer(), bot),
                    "strict rescue stopped owning the cache-reproof tick");
            require(context, bot.getActionPack().stepIdle(),
                    "strict cache consumption reused a now-occluded cached next step");
            require(context, bot.blockPosition().equals(start)
                            && bot.position().distanceToSqr(before) < 1.0E-12D,
                    "strict cache reproof advanced the bot toward its stale route");
            require(context, TeleportAudit.corrections(bot) == 0,
                    "strict cache reproof used a correction teleport: " + TeleportAudit.lastCaller(bot));
        } finally {
            NavSafetyNet.INSTANCE.clear(bot);
            bot.getActionPack().cancelStep();
            installConfig(original);
            AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
        }
        context.succeed();
    }

    /**
     * A generic cancellation intentionally leaves a guarded lease fenced so an unrelated task
     * cannot steal the narrow owner-reconciliation window. An actual lava escape is the explicit
     * high-priority exception: it must preempt both that stale ordinary fence and a live physical
     * suffocation successor, then emit real escape input.
     */
    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_guarded_lease_blocks_ordinary_input_but_lava_emergency_preempts", maxTicks = 40)
    public void guardedLeaseBlocksOrdinaryInputButLavaEmergencyPreempts(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 6, -168));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = start.offset(dx, 0, dz);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        BlockPos safeSide = start.east();
        world.setBlock(start, Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(safeSide, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(safeSide.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        String name = "GuardedLeaseLavaPreemptGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        // The manager's initial lifecycle placement can snap a newly spawned test bot to the
        // environment floor before this fixture has its intended feet cell. Put it explicitly
        // in the constructed lava/ground cell before exercising NavSafetyNet, as the shared
        // GameTest spawn helper does.
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.fallDistance = 0.0F;
        MinecraftAiConfig original = MinecraftAiConfig.get();
        try {
            installConfig(withProfile(original, OperatingProfile.STRICT_SURVIVAL));
            ActionPack pack = bot.getActionPack();
            ActionPack.StepLease guardedLease = pack.runStep(
                    WalkedStep.begin(bot, safeSide, WalkedStep.Kind.FLAT, "guarded_lease_lava_fixture"),
                    (guardBot, step) -> true);
            require(context, guardedLease != null && pack.stepInFlightFor(guardedLease),
                    "fixture did not create a continuation-guarded physical step");
            pack.cancelStep();
            require(context, pack.stepIdle(), "generic cancellation did not stop the guarded fixture step");

            ActionPack.StepLease foreignLease = pack.runStep(
                    WalkedStep.begin(bot, safeSide, WalkedStep.Kind.FLAT, "ordinary_foreign_replacement"));
            pack.setForward(1.0F);
            pack.setJumping(true);
            pack.onUpdate();
            require(context, foreignLease == null && pack.stepIdle() && bot.zza == 0.0F && !bot.isJumping(),
                    "generic-cancelled guarded lease allowed ordinary replacement or raw movement input before owner reconciliation");

            TeleportAudit.reset(bot);
            require(context, NavSafetyNet.INSTANCE.tickBot(world.getServer(), bot),
                    "lava emergency did not take priority over the retained guarded lease");
            pack.onUpdate();
            require(context, pack.stepIdle() && pack.hasActiveActions() && bot.zza > 0.0F && bot.isJumping()
                            && TeleportAudit.corrections(bot) == 0,
                    "lava emergency failed to preempt the fence and issue real strict-survival escape input");

            // Build a real NavSafetyNet-owned physical successor on ordinary ground. Then bury
            // the bot into a solid feet cell while lava appears beneath it: the buried branch
            // must let this higher-priority lava emergency preempt the live special lease rather
            // than treating the successor as an untouchable generic guarded step.
            pack.stopAll();
            NavSafetyNet.INSTANCE.clear(bot);
            world.setBlock(start, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(start.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            Standability.clearCache();
            require(context, NavSafetyNet.INSTANCE.escapeSuffocationByInputs(bot, world, start)
                            && !pack.stepIdle() && pack.baritoneControlBlocked(),
                    "fixture did not create a live Nav physical suffocation successor");
            pack.onUpdate();
            require(context, !pack.stepIdle() && bot.zza > 0.0F,
                    "the live Nav suffocation successor did not issue physical movement before lava arrived");

            world.setBlock(start, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(start.below(), Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
            Standability.clearCache();
            TeleportAudit.reset(bot);
            require(context, NavSafetyNet.INSTANCE.tickBot(world.getServer(), bot),
                    "buried lava did not take priority over a live Nav suffocation successor");
            pack.onUpdate();
            require(context, pack.stepIdle() && !pack.baritoneControlBlocked()
                            && pack.hasActiveActions() && bot.zza > 0.0F && bot.isJumping()
                            && TeleportAudit.corrections(bot) == 0,
                    "lava did not preempt the live Nav emergency lease and issue its own physical escape input");
        } finally {
            NavSafetyNet.INSTANCE.clear(bot);
            bot.getActionPack().stopAll();
            installConfig(original);
            AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
        }
        context.succeed();
    }

    /**
     * The cache check above covers a cancelled action. This companion keeps the strict action in
     * flight beyond its first physical tick, changes its visible head cell, and drives the actual
     * ActionPack pre-owner tick. The continuation proof must fail before WalkedStep re-reads the
     * newly invalid terrain; late strict-SWIM proof retention is only for a genuine visibility miss.
     */
    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_strict_in_flight_rescue_guard_rejects_new_occluder", maxTicks = 30)
    public void strictInFlightRescueGuardRejectsNewOccluder(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 6, -42));
        for (int dx = -2; dx <= 4; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        BlockPos next = start.east();
        BlockPos shore = next.east();
        for (BlockPos cell : List.of(start, next)) {
            world.setBlock(cell, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        world.setBlock(shore, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(shore.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(shore.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        String name = "StrictInFlightOccluderGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        poseWaterObserver(bot, start);
        bot.setAirSupply(260);
        MinecraftAiConfig original = MinecraftAiConfig.get();
        try {
            installConfig(withProfile(original, OperatingProfile.STRICT_SURVIVAL));
            require(context, ObservableWorldQuery.canObserveCellThroughFluids(bot, next)
                            && ObservableWorldQuery.canObserveCellThroughFluids(bot, next.above()),
                    "fixture did not begin with a visible strict rescue destination");
            TeleportAudit.reset(bot);
            NavSafetyNet.INSTANCE.requestWaterRescue(bot);
            require(context, NavSafetyNet.INSTANCE.tickBot(world.getServer(), bot),
                    "strict rescue did not start an in-flight visible water step");
            require(context, bot.getActionPack().stepInFlightFor(
                            "navsafe_water_rescue", next, WalkedStep.Kind.SWIM),
                    "strict rescue did not start its visibly admitted step");
            // Advance the guarded step past tick one without moving its body. This exercises the
            // late strict-SWIM continuation path rather than the first-tick admission proof.
            bot.getActionPack().onUpdate();
            bot.getActionPack().onUpdate();
            require(context, bot.getActionPack().stepInFlightFor(
                            "navsafe_water_rescue", next, WalkedStep.Kind.SWIM),
                    "strict rescue did not remain in flight before the late occluder");
            Vec3 before = bot.position();

            // Remove the head-cell proof without changing the profile or feet.  Calling
            // ActionPack directly exercises the real pre-owner point in AIPlayerEntity.tick.
            world.setBlock(next.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            bot.getActionPack().onUpdate();
            WalkedStep.Result preOwner = bot.getActionPack().stepResult();
            require(context, bot.getActionPack().stepIdle()
                            && preOwner != null && preOwner.failed()
                            && "continuation_guard".equals(preOwner.reason()),
                    "strict pre-owner guard did not reject the newly occluded rescue step: "
                            + (preOwner == null ? "no result" : preOwner.status() + " " + preOwner.reason()));
            require(context, bot.blockPosition().equals(start)
                            && bot.position().distanceToSqr(before) < 1.0E-12D,
                    "new occlusion advanced the strict rescue before its continuation proof");
            require(context, TeleportAudit.corrections(bot) == 0,
                    "strict continuation guard used a correction teleport: " + TeleportAudit.lastCaller(bot));

            // Let the owner observe the failed step afterwards; it must not revive the old
            // route/cache entry whose destination is no longer observable.
            require(context, NavSafetyNet.INSTANCE.tickBot(world.getServer(), bot),
                    "NavSafetyNet did not retain ownership after the guard refusal");
            require(context, bot.getActionPack().stepIdle(),
                    "NavSafetyNet revived the newly occluded strict in-flight step");
        } finally {
            NavSafetyNet.INSTANCE.clear(bot);
            bot.getActionPack().cancelStep();
            installConfig(original);
            AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
        }
        context.succeed();
    }

    /**
     * Visibility alone is not enough for a retained strict dry step: if its target floods after
     * admission, the step kind's raw landing validator would read a different terrain contract.
     * The ActionPack guard must reject that transition before the validator begins.
     */
    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_strict_in_flight_dry_step_rejects_flooded_target", maxTicks = 30)
    public void strictInFlightDryStepRejectsFloodedTarget(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 6, -46));
        for (int dx = -2; dx <= 4; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        BlockPos dryShore = start.east();
        world.setBlock(start, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(dryShore, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(dryShore.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(dryShore.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        String name = "StrictFloodedDryStepGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        poseWaterObserver(bot, start);
        bot.setAirSupply(260);
        MinecraftAiConfig original = MinecraftAiConfig.get();
        try {
            installConfig(withProfile(original, OperatingProfile.STRICT_SURVIVAL));
            require(context, ObservableWorldQuery.canObserveCellThroughFluids(bot, dryShore)
                            && ObservableWorldQuery.canObserveCellThroughFluids(bot, dryShore.above()),
                    "strict dry shore was not visibly provable before admission");
            TeleportAudit.reset(bot);
            NavSafetyNet.INSTANCE.requestWaterRescue(bot);
            require(context, NavSafetyNet.INSTANCE.tickBot(world.getServer(), bot),
                    "strict rescue did not take control of its visible dry shore");
            require(context, bot.getActionPack().stepInFlightFor(
                            "navsafe_water_rescue", dryShore, WalkedStep.Kind.FLAT),
                    "strict rescue did not admit the expected dry walked step");
            Vec3 before = bot.position();

            // The target remains visible and physically open, but it now belongs to the SWIM
            // validator rather than the FLAT validator. A continuation cannot cross that kind
            // transition just because the old destination is still in sight.
            world.setBlock(dryShore, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            require(context, ObservableWorldQuery.canObserveCellThroughFluids(bot, dryShore)
                            && ObservableWorldQuery.canObserveCellThroughFluids(bot, dryShore.above()),
                    "flooded target ceased to be visible; this must test a kind change, not occlusion");
            bot.getActionPack().onUpdate();
            WalkedStep.Result preOwner = bot.getActionPack().stepResult();
            require(context, bot.getActionPack().stepIdle()
                            && preOwner != null && preOwner.failed()
                            && "continuation_guard".equals(preOwner.reason()),
                    "strict dry-step guard did not reject the visible water transition: "
                            + (preOwner == null ? "no result" : preOwner.status() + " " + preOwner.reason()));
            require(context, bot.blockPosition().equals(start)
                            && bot.position().distanceToSqr(before) < 1.0E-12D
                            && TeleportAudit.corrections(bot) == 0,
                    "flooded dry target advanced or corrected the bot before continuation rejection");
        } finally {
            NavSafetyNet.INSTANCE.clear(bot);
            bot.getActionPack().cancelStep();
            installConfig(original);
            AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
        }
        context.succeed();
    }

    /**
     * Water strokes are legal over the full adjacent 3x3x3 envelope. With every cardinal water
     * exit sealed, strict rescue must still use the one visible diagonal stroke rather than
     * treating a normal player move as an invented cardinal-only restriction.
     */
    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_strict_rescue_uses_visible_diagonal_water_step", maxTicks = 30)
    public void strictRescueUsesVisibleDiagonalWaterStep(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 6, -44));
        for (int dx = -2; dx <= 4; dx++) {
            for (int dz = -3; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        BlockPos diagonalWater = start.east().north();
        // An unsupported open cardinal column plus a head-height north slit are sight gaps, not
        // legal rescue cells. They expose the diagonal water to the bot's eye without adding a
        // cardinal water/dry alternative.
        BlockPos sightGap = start.east();
        world.setBlock(sightGap.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(sightGap, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(sightGap.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(sightGap.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.north().above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        for (BlockPos cell : List.of(start, diagonalWater)) {
            world.setBlock(cell, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        Standability.clearCache();

        String name = "StrictDiagonalWaterRescueGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        poseWaterObserver(bot, start);
        bot.setAirSupply(260);
        MinecraftAiConfig original = MinecraftAiConfig.get();
        try {
            installConfig(withProfile(original, OperatingProfile.STRICT_SURVIVAL));
            require(context, !CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                            "strict_diagonal_water_rescue").allowed(),
                    "diagonal rescue fixture unexpectedly enabled a hidden scan");
            require(context, ObservableWorldQuery.canObserveCellThroughFluids(bot, diagonalWater)
                            && ObservableWorldQuery.canObserveCellThroughFluids(bot, diagonalWater.above()),
                    "diagonal rescue stroke was not visibly provable");
            require(context, !navHasVisibleSolidDiagonalWaterCorner(bot, start, diagonalWater),
                    "positive diagonal fixture accidentally made the north side a full solid body column");
            TeleportAudit.reset(bot);
            NavSafetyNet.INSTANCE.requestWaterRescue(bot);
            require(context, NavSafetyNet.INSTANCE.tickBot(world.getServer(), bot),
                    "strict diagonal rescue did not take control");
            require(context, bot.getActionPack().stepInFlightFor(
                            "navsafe_water_rescue", diagonalWater, WalkedStep.Kind.SWIM),
                    "strict rescue did not start its only visible diagonal water stroke");
            require(context, bot.blockPosition().equals(start) && TeleportAudit.corrections(bot) == 0,
                    "diagonal rescue moved without a physical step or used a correction teleport");
        } finally {
            NavSafetyNet.INSTANCE.clear(bot);
            bot.getActionPack().cancelStep();
            installConfig(original);
            AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
        }
        context.succeed();
    }

    /**
     * A visible diagonal water target is not enough to cut through a full two-block side wall.
     * The east column remains an open, unsupported sight gap, so this specifically distinguishes
     * a solid orthogonal body column from the one-block north ledge retained by the positive test.
     */
    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_strict_diagonal_rescue_rejects_visible_solid_corner", maxTicks = 30)
    public void strictDiagonalRescueRejectsVisibleSolidCorner(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 6, -44));
        for (int dx = -2; dx <= 4; dx++) {
            for (int dz = -3; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        BlockPos diagonalWater = start.east().north();
        BlockPos sightGap = start.east();
        // The east gap is not a landing or a water alternative. It only gives the observer a
        // genuine line through to the diagonal target; NORTH stays a two-block solid wall.
        world.setBlock(sightGap.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(sightGap, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(sightGap.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(sightGap.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        for (BlockPos cell : List.of(start, diagonalWater)) {
            world.setBlock(cell, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        Standability.clearCache();

        String name = "StrictDiagonalSolidCornerGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        poseWaterObserver(bot, start);
        // Bias both target rays through the open east gap rather than along the shared corner.
        bot.teleportTo(bot.level(), start.getX() + 0.625D, start.getY() + 0.125D,
                start.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.setAirSupply(260);
        MinecraftAiConfig original = MinecraftAiConfig.get();
        try {
            installConfig(withProfile(original, OperatingProfile.STRICT_SURVIVAL));
            require(context, !CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                            "strict_diagonal_solid_corner").allowed(),
                    "solid-corner fixture unexpectedly enabled a hidden scan");
            require(context, ObservableWorldQuery.canObserveCellThroughFluids(bot, diagonalWater)
                            && ObservableWorldQuery.canObserveCellThroughFluids(bot, diagonalWater.above()),
                    "solid-corner fixture did not leave the diagonal target visibly provable through east");
            require(context, navHasVisibleSolidDiagonalWaterCorner(bot, start, diagonalWater),
                    "solid-corner fixture did not satisfy the exact strict diagonal edge predicate");
            Vec3 before = bot.position();
            TeleportAudit.reset(bot);
            NavSafetyNet.INSTANCE.requestWaterRescue(bot);
            require(context, NavSafetyNet.INSTANCE.tickBot(world.getServer(), bot),
                    "strict solid-corner rescue did not take control");
            require(context, !bot.getActionPack().stepInFlightFor(
                            "navsafe_water_rescue", diagonalWater, WalkedStep.Kind.SWIM),
                    "strict rescue cut through a visibly solid diagonal side column");
            require(context, bot.blockPosition().equals(start)
                            && bot.position().distanceToSqr(before) < 1.0E-12D
                            && TeleportAudit.corrections(bot) == 0,
                    "solid-corner rejection moved or corrected the bot: " + TeleportAudit.lastCaller(bot));
        } finally {
            NavSafetyNet.INSTANCE.clear(bot);
            bot.getActionPack().cancelStep();
            installConfig(original);
            AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
        }
        context.succeed();
    }

    /**
     * The strict in-flight guard must not retain an admitted SWIM proof merely because a wall
     * placed after tick one turns the unchanged diagonal target into an UNKNOWN ray miss.  The
     * two-block wall is in the formerly open cardinal sight gap, not in the target water/head
     * cells, so this specifically covers late interposing occlusion rather than visible-invalid
     * terrain at the target.
     */
    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_strict_in_flight_rescue_guard_rejects_late_interposing_wall", maxTicks = 30)
    public void strictInFlightRescueGuardRejectsLateInterposingWall(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 6, -44));
        for (int dx = -2; dx <= 4; dx++) {
            for (int dz = -3; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        BlockPos diagonalWater = start.east().north();
        BlockPos sightGap = start.east();
        world.setBlock(sightGap.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(sightGap, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(sightGap.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(sightGap.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.north().above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        for (BlockPos cell : List.of(start, diagonalWater)) {
            world.setBlock(cell, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        Standability.clearCache();

        String name = "StrictInFlightInterposingWallGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        poseWaterObserver(bot, start);
        // Keep the observer in the source cell, but move it off the diagonal's shared corner so
        // the later east wall genuinely intersects both current center-ray proofs.
        bot.teleportTo(bot.level(), start.getX() + 0.625D, start.getY() + 0.125D,
                start.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.setAirSupply(260);
        MinecraftAiConfig original = MinecraftAiConfig.get();
        try {
            installConfig(withProfile(original, OperatingProfile.STRICT_SURVIVAL));
            require(context, ObservableWorldQuery.canObserveCellThroughFluids(bot, diagonalWater)
                            && ObservableWorldQuery.canObserveCellThroughFluids(bot, diagonalWater.above()),
                    "interposing-wall fixture did not begin with a visible diagonal target");
            TeleportAudit.reset(bot);
            NavSafetyNet.INSTANCE.requestWaterRescue(bot);
            require(context, NavSafetyNet.INSTANCE.tickBot(world.getServer(), bot),
                    "strict rescue did not begin the diagonal in-flight step");
            require(context, bot.getActionPack().stepInFlightFor(
                            "navsafe_water_rescue", diagonalWater, WalkedStep.Kind.SWIM),
                    "strict rescue did not choose the only legal diagonal water step");

            // Put the real guarded action beyond admission tick one before changing the current
            // world. The target stays WATER with clear headroom throughout this regression.
            bot.getActionPack().onUpdate();
            bot.getActionPack().onUpdate();
            require(context, bot.getActionPack().stepInFlightFor(
                            "navsafe_water_rescue", diagonalWater, WalkedStep.Kind.SWIM),
                    "strict diagonal step did not remain live through tick two");
            Vec3 before = bot.position();

            // This is an interposing cardinal wall, not a target edit. It converts the target
            // observation to UNKNOWN, exactly the late continuation exception that must fail
            // closed rather than treating a new obstruction as self-occlusion.
            world.setBlock(sightGap, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(sightGap.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            Standability.clearCache();
            require(context, world.getFluidState(diagonalWater).is(net.minecraft.tags.FluidTags.WATER)
                            && world.getBlockState(diagonalWater.above()).is(Blocks.AIR),
                    "interposing-wall fixture changed the diagonal target instead of only its sight line");
            require(context, !ObservableWorldQuery.canObserveCellThroughFluids(bot, diagonalWater)
                            && !ObservableWorldQuery.canObserveCellThroughFluids(bot, diagonalWater.above()),
                    "interposing cardinal wall did not block the current strict center-ray proofs");
            require(context, navWaterProbeIsUnknown(bot, world, diagonalWater),
                    "interposing cardinal wall did not make the unchanged strict target UNKNOWN");

            bot.getActionPack().onUpdate();
            WalkedStep.Result preOwner = bot.getActionPack().stepResult();
            require(context, bot.getActionPack().stepIdle()
                            && preOwner != null && preOwner.failed()
                            && "continuation_guard".equals(preOwner.reason()),
                    "strict guard retained a late interposing-wall target: "
                            + (preOwner == null ? "no result" : preOwner.status() + " " + preOwner.reason()));
            require(context, bot.blockPosition().equals(start)
                            && bot.position().distanceToSqr(before) < 1.0E-12D
                            && TeleportAudit.corrections(bot) == 0,
                    "late interposing wall advanced or corrected the strict rescue before rejection");
        } finally {
            NavSafetyNet.INSTANCE.clear(bot);
            bot.getActionPack().cancelStep();
            installConfig(original);
            AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
        }
        context.succeed();
    }

    /**
     * NavSafetyNet owns its own in-flight observed steps. A rescue step made invalid before the
     * next strict tick must be cancelled before the action pack can advance it; no profile can
     * make the hidden-world capability valid.
     */
    @GameTest(environment = "minecraftai-gametest:surface_water_recovery_game_tests_operator_rescue_step_is_cancelled_before_strict_reproof", maxTicks = 30)
    public void operatorRescueStepIsCancelledBeforeStrictReproof(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 6, -48));
        for (int dx = -1; dx <= 3; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        BlockPos next = start.east();
        BlockPos shore = next.east();
        for (BlockPos cell : List.of(start, next)) {
            world.setBlock(cell, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        world.setBlock(shore, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(shore.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(shore.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        String name = "NavRescueProvenanceGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setAirSupply(260);
        MinecraftAiConfig original = MinecraftAiConfig.get();
        try {
            installConfig(withProfile(original, OperatingProfile.OPERATOR));
            require(context, !CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                            "operator_rescue_step_provenance").allowed(),
                    "operator fixture unexpectedly enabled hidden-world scanning");
            TeleportAudit.reset(bot);
            NavSafetyNet.INSTANCE.requestWaterRescue(bot);
            require(context, NavSafetyNet.INSTANCE.tickBot(world.getServer(), bot),
                    "operator-profile rescue did not start its observation-bearing route step");
            require(context, bot.getActionPack().stepInFlightFor(
                            "navsafe_water_rescue", next, WalkedStep.Kind.SWIM),
                    "operator rescue did not begin its physical next step");
            Vec3 before = bot.position();

            world.setBlock(next, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(next.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            Standability.clearCache();
            installConfig(withProfile(original, OperatingProfile.STRICT_SURVIVAL));
            require(context, !CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                            "strict_rescue_step_provenance").allowed(),
                    "strict fixture unexpectedly retained hidden-world capability");
            // AIPlayerEntity.tick drives ActionPack before NavSafetyNet's END_SERVER_TICK owner
            // reconciliation. Exercise that exact boundary before invoking the owner manually.
            bot.getActionPack().onUpdate();
            WalkedStep.Result preOwner = bot.getActionPack().stepResult();
            require(context, bot.getActionPack().stepIdle()
                            && preOwner != null && preOwner.failed()
                            && "continuation_guard".equals(preOwner.reason()),
                    "the ActionPack pre-owner tick did not reject the invalidated observed rescue step: "
                            + (preOwner == null ? "no result" : preOwner.status() + " " + preOwner.reason()));
            require(context, bot.position().distanceToSqr(before) < 1.0E-12D,
                    "the pre-owner rescue guard advanced the bot: " + before + " -> " + bot.position());

            require(context, NavSafetyNet.INSTANCE.tickBot(world.getServer(), bot),
                    "strict rescue did not retain ownership for the reproof tick");
            require(context, bot.getActionPack().stepIdle(),
                    "strict rescue let an invalidated observed step continue after reproof failed");
            require(context, bot.position().distanceToSqr(before) < 1.0E-12D,
                    "rescue profile transition advanced the bot before strict reproof: "
                            + before + " -> " + bot.position());
            require(context, TeleportAudit.corrections(bot) == 0,
                    "rescue profile transition used a correction teleport: " + TeleportAudit.lastCaller(bot));
        } finally {
            NavSafetyNet.INSTANCE.clear(bot);
            bot.getActionPack().cancelStep();
            installConfig(original);
            AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
        }
        context.succeed();
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
        return sealedWaterShaftFixture(context, z, 2);
    }

    private static WaterShaftFixture sealedWaterShaftFixture(GameTestHelper context, int z, int depth) {
        var world = context.getLevel();
        BlockPos lower = context.absolutePos(new BlockPos(8, 24, z));

        // Fill the complete local rescue window, then carve only the requested water shaft. There is
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
        for (int dy = 0; dy < depth; dy++) {
            world.setBlock(lower.above(dy), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        }
        world.setBlock(lower.above(depth), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
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

    /**
     * Leave a waterborne fixture bot's eye just above its source block.  Its physical feet still
     * belong to {@code feet}, while the scoped Fluid.NONE underwater observer can prove clear
     * water without treating the source column itself as a hidden-world occluder.
     */
    private static void poseWaterObserver(AIPlayerEntity bot, BlockPos feet) {
        bot.teleportTo(bot.level(), feet.getX() + 0.5D, feet.getY() + 0.125D, feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.fallDistance = 0.0F;
    }

    /** Test-only immutable-config replacement; every caller restores it before yielding another tick. */
    private static MinecraftAiConfig withProfile(MinecraftAiConfig config, OperatingProfile profile) {
        return new MinecraftAiConfig(profile, config.operatorCapabilities(), config.llm(), config.perception(),
                config.brain(), config.watchdog(), config.logging(), config.survival(), config.combat(), config.night(),
                config.mining(), config.goal(), config.nav(), config.pickup(), config.conversation(), config.storage(),
                config.behaviour());
    }

    private static void installConfig(MinecraftAiConfig config) {
        try {
            Field instance = MinecraftAiConfig.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, config);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("failed to install GameTest config", exception);
        }
    }

    /** Reads NavSafetyNet's private strict diagonal-edge predicate without exposing a production-only test seam. */
    private static boolean navHasVisibleSolidDiagonalWaterCorner(AIPlayerEntity bot, BlockPos from, BlockPos to) {
        try {
            Method corner = NavSafetyNet.class.getDeclaredMethod("hasVisibleSolidDiagonalWaterCorner",
                    AIPlayerEntity.class, BlockPos.class, BlockPos.class);
            corner.setAccessible(true);
            return Boolean.TRUE.equals(corner.invoke(null, bot, from, to));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("failed to inspect strict Nav diagonal water edge", exception);
        }
    }

    /** Reads NavSafetyNet's private strict classifier without exposing a production-only test seam. */
    private static boolean navWaterProbeIsObservedInvalid(AIPlayerEntity bot, ServerLevel world, BlockPos candidate) {
        try {
            Method probeMethod = NavSafetyNet.class.getDeclaredMethod("probeWaterEscapeCell",
                    AIPlayerEntity.class, ServerLevel.class, BlockPos.class, boolean.class);
            probeMethod.setAccessible(true);
            Object probe = probeMethod.invoke(null, bot, world, candidate, false);
            Method observed = probe.getClass().getDeclaredMethod("observed");
            Method cell = probe.getClass().getDeclaredMethod("cell");
            observed.setAccessible(true);
            cell.setAccessible(true);
            return Boolean.TRUE.equals(observed.invoke(probe)) && cell.invoke(probe) == null;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("failed to inspect strict Nav water-cell classification", exception);
        }
    }

    /** Confirms a late obstruction hid the target rather than changing its visible terrain class. */
    private static boolean navWaterProbeIsUnknown(AIPlayerEntity bot, ServerLevel world, BlockPos candidate) {
        try {
            Method probeMethod = NavSafetyNet.class.getDeclaredMethod("probeWaterEscapeCell",
                    AIPlayerEntity.class, ServerLevel.class, BlockPos.class, boolean.class);
            probeMethod.setAccessible(true);
            Object probe = probeMethod.invoke(null, bot, world, candidate, false);
            Method unknown = probe.getClass().getDeclaredMethod("unknown");
            unknown.setAccessible(true);
            return Boolean.TRUE.equals(unknown.invoke(probe));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("failed to inspect strict Nav water-cell visibility", exception);
        }
    }

    private record DeadlineSnapshot(BlockPos feet, BlockPos shore, int routeCells,
                                    boolean hiddenWorldScan, int deadlineTick) {
        private DeadlineSnapshot withDeadline(int nextDeadlineTick) {
            return new DeadlineSnapshot(feet, shore, routeCells, hiddenWorldScan, nextDeadlineTick);
        }
    }

    private static DeadlineSnapshot rescueDeadlineForTest(AIPlayerEntity bot) {
        try {
            Object deadline = waterRescueDeadlinesForTest().get(bot.getUUID());
            if (deadline == null) {
                throw new IllegalStateException("water rescue did not install a deadline");
            }
            return new DeadlineSnapshot(
                    (BlockPos) deadlineComponent(deadline, "feet"),
                    (BlockPos) deadlineComponent(deadline, "shore"),
                    ((Number) deadlineComponent(deadline, "routeCells")).intValue(),
                    (Boolean) deadlineComponent(deadline, "hiddenWorldScan"),
                    ((Number) deadlineComponent(deadline, "deadlineTick")).intValue());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("failed to inspect water-rescue deadline", exception);
        }
    }

    private static void replaceRescueDeadlineForTest(AIPlayerEntity bot, DeadlineSnapshot snapshot) {
        try {
            Map<Object, Object> deadlines = waterRescueDeadlinesForTest();
            Object current = deadlines.get(bot.getUUID());
            if (current == null) {
                throw new IllegalStateException("water rescue did not retain a deadline to replace");
            }
            Constructor<?> constructor = current.getClass().getDeclaredConstructor(
                    BlockPos.class, BlockPos.class, int.class, boolean.class, int.class);
            constructor.setAccessible(true);
            deadlines.put(bot.getUUID(), constructor.newInstance(snapshot.feet(), snapshot.shore(),
                    snapshot.routeCells(), snapshot.hiddenWorldScan(), snapshot.deadlineTick()));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("failed to replace water-rescue deadline", exception);
        }
    }

    private static Object deadlineComponent(Object deadline, String name) throws ReflectiveOperationException {
        Method accessor = deadline.getClass().getDeclaredMethod(name);
        accessor.setAccessible(true);
        return accessor.invoke(deadline);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> waterRescueDeadlinesForTest() throws ReflectiveOperationException {
        Field field = NavSafetyNet.class.getDeclaredField("waterRescueDeadlines");
        field.setAccessible(true);
        return (Map<Object, Object>) field.get(NavSafetyNet.INSTANCE);
    }

    private static void releaseRescueStepForTest(AIPlayerEntity bot, boolean cancel) {
        try {
            Method release = NavSafetyNet.class.getDeclaredMethod("releaseRescueStep", AIPlayerEntity.class, boolean.class);
            release.setAccessible(true);
            release.invoke(NavSafetyNet.INSTANCE, bot, cancel);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("failed to interrupt the owned rescue step", exception);
        }
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}

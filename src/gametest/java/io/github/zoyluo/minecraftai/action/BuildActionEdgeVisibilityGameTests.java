package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Field;
import java.util.Set;

/** Physical strict-survival regressions for support faces exposed only at a reachable edge. */
public final class BuildActionEdgeVisibilityGameTests {
    @GameTest(environment = "minecraftai-gametest:build_action_edge_visibility_game_tests_support_center_and_face_center_may_be_beyond_reach_when_inset_is_legal", maxTicks = 40)
    public void supportCenterAndFaceCenterMayBeBeyondReachWhenInsetIsLegal(
            GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(3, 4, 4));
        clear(context, feet);
        context.getLevel().setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos support = feet.east(5);
        BlockPos destination = support.west();
        context.getLevel().setBlock(support, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        AIPlayerEntity bot = spawn(context, "BuildReachInset", feet,
                new Vec3(feet.getX() + 0.57D, feet.getY(), feet.getZ() + 0.5D));
        equipTwoCobblestone(bot);
        double reach = bot.blockInteractionRange();
        double reachSquared = reach * reach;
        Vec3 faceCenter = Vec3.atCenterOf(support).add(-0.5D, 0.0D, 0.0D);
        Vec3 reachableInset = faceCenter.add(0.0D, 0.375D, 0.0D);

        require(context, bot.getEyePosition().distanceToSqr(support.getCenter()) > reachSquared,
                "fixture left the support center inside ordinary reach");
        require(context, bot.getEyePosition().distanceToSqr(faceCenter) > reachSquared,
                "fixture left the requested face center inside ordinary reach");
        require(context, bot.getEyePosition().distanceToSqr(reachableInset) < reachSquared,
                "fixture did not expose a reachable inset point");
        require(context, bot.isWithinBlockInteractionRange(support, 0.0D),
                "vanilla block-box reach rejected the inset fixture");

        ActionResult result = BuildAction.placeBlock(
                bot, support, Direction.WEST, InteractionHand.MAIN_HAND);
        require(context, result.isSuccess(),
                "reachable face inset was rejected: " + result.reason());
        require(context, context.getLevel().getBlockState(destination).is(Blocks.COBBLESTONE),
                "successful inset click did not place the real destination block");
        require(context, bot.getMainHandItem().getCount() == 1,
                "inset placement did not consume exactly one physical block");
        cleanup(context, bot, "BuildReachInset");
    }

    @GameTest(environment = "minecraftai-gametest:build_action_edge_visibility_game_tests_place_at_uses_exact_inset_when_all_target_face_centers_are_hidden", maxTicks = 40)
    public void placeAtUsesExactInsetWhenAllTargetFaceCentersAreHidden(GameTestHelper context) {
        BlockPos base = context.absolutePos(new BlockPos(3, 4, 4));
        clear(context, base);
        BlockPos botFeet = base.south();
        context.getLevel().setBlock(
                botFeet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos support = base.east(4).above();
        BlockPos destination = support.west();
        BlockPos occluder = base.east().above();
        context.getLevel().setBlock(support, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(occluder, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        // Hide every non-clicked face center without creating another block adjacent to the
        // destination. The WEST face retains only its south-side inset around the occluder.
        for (Direction direction : new Direction[]{
                Direction.UP, Direction.DOWN, Direction.NORTH,
                Direction.SOUTH, Direction.EAST}) {
            context.getLevel().setBlock(
                    support.relative(direction), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }

        AIPlayerEntity bot = spawn(context, "BuildHiddenEdge", botFeet,
                new Vec3(base.getX() + 0.5D, base.getY(), base.getZ() + 1.3D));
        equipTwoCobblestone(bot);
        Vec3 faceCenter = Vec3.atCenterOf(support).add(-0.5D, 0.0D, 0.0D);
        LookAction.lookAt(bot, faceCenter);
        var centerRay = bot.pick(bot.blockInteractionRange(), 1.0F, false);

        require(context, !(centerRay instanceof BlockHitResult hit
                        && hit.getBlockPos().equals(support)
                        && hit.getDirection() == Direction.WEST),
                "fixture left the requested face center clickable");
        require(context, !ObservableWorldQuery.canObserveBlock(bot, support),
                "fixture exposed one of the legacy six face-center rays");
        require(context, ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, support),
                "fixture did not expose an exact inset observation ray");
        require(context, hasVisibleWestEdgePoint(bot, support),
                "fixture did not expose a direct ray to the requested west support face");

        ActionResult result = BuildAction.placeBlockAt(bot, destination);
        require(context, result.isSuccess(),
                "placeBlockAt did not reach the exact inset sampler: " + result.reason());
        require(context, context.getLevel().getBlockState(destination).is(Blocks.COBBLESTONE),
                "edge sampler reported success without a physical placement");
        require(context, bot.getMainHandItem().getCount() == 1,
                "edge sampler did not consume exactly one physical block");
        cleanup(context, bot, "BuildHiddenEdge");
    }

    /** The fixture's independently chosen south-west edge point is the one left clear around the occluder. */
    private static boolean hasVisibleWestEdgePoint(AIPlayerEntity bot, BlockPos support) {
        Vec3 eye = bot.getEyePosition();
        Vec3 target = new Vec3(support.getX() + 0.001D, support.getY() + 0.5D,
                support.getZ() + 0.875D);
        BlockHitResult hit = bot.level().clip(new ClipContext(
                eye, target, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, bot));
        return hit.getType() == HitResult.Type.BLOCK
                && support.equals(hit.getBlockPos())
                && hit.getDirection() == Direction.WEST;
    }

    @GameTest(environment = "minecraftai-gametest:build_action_edge_visibility_game_tests_water_source_may_be_observable_only_through_an_inset_ray", maxTicks = 40)
    public void waterSourceMayBeObservableOnlyThroughAnInsetRay(GameTestHelper context) {
        assertFluidOnlyInsetObservable(context, "BuildInsetWater", Blocks.WATER);
    }

    @GameTest(environment = "minecraftai-gametest:build_action_edge_visibility_game_tests_lava_source_may_be_observable_only_through_an_inset_ray", maxTicks = 40)
    public void lavaSourceMayBeObservableOnlyThroughAnInsetRay(GameTestHelper context) {
        assertFluidOnlyInsetObservable(context, "BuildInsetLava", Blocks.LAVA);
    }

    @GameTest(environment = "minecraftai-gametest:build_action_edge_visibility_game_tests_low_perception_radius_rejects_otherwise_reachable_inset_placement", maxTicks = 40)
    public void lowPerceptionRadiusRejectsOtherwiseReachableInsetPlacement(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(3, 4, 4));
        clear(context, feet);
        context.getLevel().setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos support = feet.east(3).above();
        BlockPos destination = support.west();
        context.getLevel().setBlock(support, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        AIPlayerEntity bot = spawn(context, "BuildLowPerception", feet, Vec3.atBottomCenterOf(feet));
        equipTwoCobblestone(bot);
        MinecraftAiConfig original = MinecraftAiConfig.get();

        try {
            require(context, bot.isWithinBlockInteractionRange(support, 0.0D),
                    "fixture support is outside vanilla interaction reach");
            setConfig(withPerceptionRadius(original, 1));
            require(context, MinecraftAiConfig.get().perception().radius() == 1,
                    "fixture failed to lower the configured perception radius");
            // Block sight is bounded by the bot's render distance and, for this inset query, its interaction reach, not by the
            // perception radius: the support is seen. What the low radius still limits is the placement's own face proof below.
            require(context, ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, support),
                    "the perception radius limited block sight, which follows the render distance");

            ActionResult result = BuildAction.placeBlock(
                    bot, support, Direction.WEST, InteractionHand.MAIN_HAND);
            require(context, result.isFailed()
                            && "support_face_not_visible".equals(result.reason()),
                    "low-radius support reached vanilla interaction: " + result.reason());
            require(context, context.getLevel().getBlockState(destination).isAir(),
                    "low-radius placement mutated the external destination");
            require(context, bot.getMainHandItem().getCount() == 2,
                    "low-radius placement consumed a physical block");
        } finally {
            setConfig(original);
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "BuildLowPerception");
        }
        context.succeed();
    }

    @GameTest(environment = "minecraftai-gametest:build_action_edge_visibility_game_tests_strict_mode_never_uses_direct_hidden_placement_fallback", maxTicks = 40)
    public void strictModeNeverUsesDirectHiddenPlacementFallback(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        clear(context, feet);
        context.getLevel().setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos unsupported = feet.north(2).above();
        AIPlayerEntity bot = spawn(context, "BuildNoFallback", feet, Vec3.atBottomCenterOf(feet));
        equipTwoCobblestone(bot);

        require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "fixture is not running under strict_survival");
        require(context, ObservableWorldQuery.canObserveCell(bot, unsupported),
                "unsupported destination is not visible enough to detect direct fallback");
        ActionResult result = BuildAction.placeBlockAt(bot, unsupported);

        require(context, result.isFailed(),
                "strict unsupported placement unexpectedly succeeded");
        require(context, context.getLevel().getBlockState(unsupported).isAir(),
                "strict placement used a direct world-mutation fallback");
        require(context, bot.getMainHandItem().getCount() == 2,
                "failed strict placement consumed a block");
        cleanup(context, bot, "BuildNoFallback");
    }

    @GameTest(environment = "minecraftai-gametest:build_action_edge_visibility_game_tests_mid_air_placement_without_a_support_face_fails_in_both_profiles", maxTicks = 40)
    public void midAirPlacementWithoutASupportFaceFailsInBothProfiles(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        clear(context, feet);
        context.getLevel().setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        // A cell in reach and in plain view with all six neighbours empty: a player has nothing to click.
        BlockPos unsupported = feet.north(2).above();
        AIPlayerEntity bot = spawn(context, "BuildMidAir", feet, Vec3.atBottomCenterOf(feet));
        equipTwoCobblestone(bot);
        require(context, ObservableWorldQuery.canObserveCell(bot, unsupported),
                "the mid-air cell is not visible enough to expose a direct-placement fallback");
        for (Direction direction : Direction.values()) {
            require(context, context.getLevel().getBlockState(unsupported.relative(direction)).isAir(),
                    "fixture: the mid-air cell has a neighbour " + direction);
        }

        MinecraftAiConfig original = MinecraftAiConfig.get();
        try {
            for (OperatingProfile profile : OperatingProfile.values()) {
                setConfig(withProfile(original, profile));
                require(context, MinecraftAiConfig.get().profile() == profile,
                        "fixture failed to switch to profile " + profile);
                ActionResult result = BuildAction.placeBlockAt(bot, unsupported);
                require(context, result.isFailed(),
                        "mid-air placement succeeded under profile " + profile);
                require(context, context.getLevel().getBlockState(unsupported).isAir(),
                        "profile " + profile + " wrote a block into mid-air");
                require(context, bot.getMainHandItem().getCount() == 2,
                        "failed placement under profile " + profile + " consumed a block");
            }
        } finally {
            setConfig(original);
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "BuildMidAir");
        }
        context.succeed();
    }

    @GameTest(environment = "minecraftai-gametest:build_action_edge_visibility_game_tests_ordinary_supported_placement_still_uses_vanilla_interaction", maxTicks = 40)
    public void ordinarySupportedPlacementStillUsesVanillaInteraction(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        clear(context, feet);
        context.getLevel().setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos destination = feet.north();
        context.getLevel().setBlock(
                destination.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        AIPlayerEntity bot = spawn(context, "BuildOrdinary", feet, Vec3.atBottomCenterOf(feet));
        equipTwoCobblestone(bot);

        ActionResult result = BuildAction.placeBlockAt(bot, destination);
        require(context, result.isSuccess(),
                "ordinary supported placement regressed: " + result.reason());
        require(context, context.getLevel().getBlockState(destination).is(Blocks.COBBLESTONE),
                "ordinary placement did not mutate through vanilla interaction");
        require(context, bot.getMainHandItem().getCount() == 1,
                "ordinary placement did not consume exactly one physical block");
        cleanup(context, bot, "BuildOrdinary");
    }

    @GameTest(environment = "minecraftai-gametest:build_action_edge_visibility_game_tests_can_accept_placement_at_never_turns_the_bots_head", maxTicks = 40)
    public void canAcceptPlacementAtNeverTurnsTheBotsHead(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        clear(context, feet);
        context.getLevel().setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos supported = feet.north();
        context.getLevel().setBlock(
                supported.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        // Far cell with no support face at all: the probe must reject it without turning either.
        BlockPos unsupported = feet.east(3).above(2);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    context.getLevel().setBlock(unsupported.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        AIPlayerEntity bot = spawn(context, "BuildProbeNoTurn", feet, Vec3.atBottomCenterOf(feet));
        equipTwoCobblestone(bot);
        // Deliberately not facing either candidate, so any look-at would show up as a change.
        bot.setYRot(133.0F);
        bot.setXRot(-27.0F);
        bot.setYHeadRot(133.0F);
        bot.setYBodyRot(133.0F);
        float yaw = bot.getYRot();
        float pitch = bot.getXRot();
        float headYaw = bot.getYHeadRot();
        float bodyYaw = bot.getVisualRotationYInDegrees();

        boolean acceptsSupported = BuildAction.canAcceptPlacementAt(bot, supported);
        boolean acceptsUnsupported = BuildAction.canAcceptPlacementAt(bot, unsupported);

        require(context, acceptsSupported, "the probe should accept an ordinary supported cell");
        require(context, !acceptsUnsupported, "the probe should reject a cell with no support face");
        require(context, bot.getYRot() == yaw && bot.getXRot() == pitch,
                "canAcceptPlacementAt turned the bot's head: yaw " + yaw + "->" + bot.getYRot()
                        + " pitch " + pitch + "->" + bot.getXRot());
        require(context, bot.getYHeadRot() == headYaw && bot.getVisualRotationYInDegrees() == bodyYaw,
                "canAcceptPlacementAt turned the bot's head/body yaw");
        require(context, context.getLevel().getBlockState(supported).isAir()
                        && context.getLevel().getBlockState(unsupported).isAir(),
                "canAcceptPlacementAt must not mutate the world");
        cleanup(context, bot, "BuildProbeNoTurn");
    }


    private static void assertFluidOnlyInsetObservable(GameTestHelper context,
                                                       String name,
                                                       Block fluid) {
        BlockPos base = context.absolutePos(new BlockPos(3, 4, 4));
        clear(context, base);
        BlockPos botFeet = base.south();
        context.getLevel().setBlock(
                botFeet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos source = base.east(4).above();
        BlockPos occluder = base.east().above();
        context.getLevel().setBlock(source, fluid.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(occluder, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        for (Direction direction : new Direction[]{
                Direction.UP, Direction.DOWN, Direction.NORTH,
                Direction.SOUTH, Direction.EAST}) {
            context.getLevel().setBlock(
                    source.relative(direction), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }

        AIPlayerEntity bot = spawn(context, name, botFeet,
                new Vec3(base.getX() + 0.5D, base.getY(), base.getZ() + 1.3D));
        Vec3 faceCenter = Vec3.atCenterOf(source).add(-0.499D, 0.0D, 0.0D);
        BlockHitResult centerHit = context.getLevel().clip(new ClipContext(
                bot.getEyePosition(), faceCenter,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.ANY,
                bot));

        require(context, centerHit.getType() != HitResult.Type.BLOCK
                        || !centerHit.getBlockPos().equals(source)
                        || centerHit.getDirection() != Direction.WEST,
                "fixture left the fluid face center visible");
        require(context, !ObservableWorldQuery.canObserveBlock(bot, source),
                "fixture exposed a legacy fluid face-center ray");
        require(context, ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, source),
                "fluid source was not observable through its exposed inset");
        cleanup(context, bot, name);
    }

    private static MinecraftAiConfig withProfile(MinecraftAiConfig config, OperatingProfile profile) {
        return new MinecraftAiConfig(
                profile,
                config.operatorCapabilities(),
                config.llm(),
                config.perception(),
                config.brain(),
                config.watchdog(),
                config.logging(),
                config.survival(),
                config.combat(),
                config.night(),
                config.mining(),
                config.goal(),
                config.nav(),
                config.pickup(),
                config.conversation());
    }

    private static MinecraftAiConfig withPerceptionRadius(MinecraftAiConfig config, int radius) {
        MinecraftAiConfig.Perception perception = config.perception();
        return new MinecraftAiConfig(
                config.profile(),
                config.operatorCapabilities(),
                config.llm(),
                new MinecraftAiConfig.Perception(
                        radius,
                        perception.maxBlocks(),
                        perception.maxEntities(),
                        perception.maxItems(),
                        perception.includeRawLists()),
                config.brain(),
                config.watchdog(),
                config.logging(),
                config.survival(),
                config.combat(),
                config.night(),
                config.mining(),
                config.goal(),
                config.nav(),
                config.pickup(),
                config.conversation());
    }

    private static void setConfig(MinecraftAiConfig config) {
        try {
            Field instance = MinecraftAiConfig.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, config);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("failed to install GameTest config", exception);
        }
    }

    private static AIPlayerEntity spawn(GameTestHelper context,
                                        String name,
                                        BlockPos feet,
                                        Vec3 pose) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getLevel().getServer(), name, context.getLevel(), pose,
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(context.getLevel(), pose.x, pose.y, pose.z,
                Set.of(), 0.0F, 0.0F, true);
        bot.setOnGround(true);
        require(context, bot.blockPosition().equals(feet),
                "fixture spawned in the wrong feet cell: " + bot.blockPosition().toShortString());
        return bot;
    }

    private static void equipTwoCobblestone(AIPlayerEntity bot) {
        bot.getInventory().setSelectedSlot(0);
        bot.getInventory().getNonEquipmentItems().set(0, new ItemStack(Items.COBBLESTONE, 2));
        bot.getInventory().setChanged();
    }

    private static void clear(GameTestHelper context, BlockPos origin) {
        for (int dx = -2; dx <= 7; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    context.getLevel().setBlock(
                            origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    private static void cleanup(GameTestHelper context, AIPlayerEntity bot, String name) {
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}

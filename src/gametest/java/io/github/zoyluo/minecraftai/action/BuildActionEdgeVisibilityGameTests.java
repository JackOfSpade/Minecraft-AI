package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.RaycastContext;

import java.lang.reflect.Field;
import java.util.Set;
import net.minecraft.text.Text;

/** Physical strict-survival regressions for support faces exposed only at a reachable edge. */
public final class BuildActionEdgeVisibilityGameTests {
    @GameTest(environment = "minecraftai-gametest:build_action_edge_visibility_game_tests_support_center_and_face_center_may_be_beyond_reach_when_inset_is_legal", maxTicks = 40)
    public void supportCenterAndFaceCenterMayBeBeyondReachWhenInsetIsLegal(
            TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(3, 4, 4));
        clear(context, feet);
        context.getWorld().setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos support = feet.east(5);
        BlockPos destination = support.west();
        context.getWorld().setBlockState(support, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);

        AIPlayerEntity bot = spawn(context, "BuildReachInset", feet,
                new Vec3d(feet.getX() + 0.57D, feet.getY(), feet.getZ() + 0.5D));
        equipTwoCobblestone(bot);
        double reach = bot.getBlockInteractionRange();
        double reachSquared = reach * reach;
        Vec3d faceCenter = Vec3d.ofCenter(support).add(-0.5D, 0.0D, 0.0D);
        Vec3d reachableInset = faceCenter.add(0.0D, 0.375D, 0.0D);

        require(context, bot.getEyePos().squaredDistanceTo(support.toCenterPos()) > reachSquared,
                "fixture left the support center inside ordinary reach");
        require(context, bot.getEyePos().squaredDistanceTo(faceCenter) > reachSquared,
                "fixture left the requested face center inside ordinary reach");
        require(context, bot.getEyePos().squaredDistanceTo(reachableInset) < reachSquared,
                "fixture did not expose a reachable inset point");
        require(context, bot.canInteractWithBlockAt(support, 0.0D),
                "vanilla block-box reach rejected the inset fixture");

        ActionResult result = BuildAction.placeBlock(
                bot, support, Direction.WEST, Hand.MAIN_HAND);
        require(context, result.isSuccess(),
                "reachable face inset was rejected: " + result.reason());
        require(context, context.getWorld().getBlockState(destination).isOf(Blocks.COBBLESTONE),
                "successful inset click did not place the real destination block");
        require(context, bot.getMainHandStack().getCount() == 1,
                "inset placement did not consume exactly one physical block");
        cleanup(context, bot, "BuildReachInset");
    }

    @GameTest(environment = "minecraftai-gametest:build_action_edge_visibility_game_tests_place_at_uses_exact_inset_when_all_target_face_centers_are_hidden", maxTicks = 40)
    public void placeAtUsesExactInsetWhenAllTargetFaceCentersAreHidden(TestContext context) {
        BlockPos base = context.getAbsolutePos(new BlockPos(3, 4, 4));
        clear(context, base);
        BlockPos botFeet = base.south();
        context.getWorld().setBlockState(
                botFeet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos support = base.east(4).up();
        BlockPos destination = support.west();
        BlockPos occluder = base.east().up();
        context.getWorld().setBlockState(support, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(occluder, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        // Hide every non-clicked face center without creating another block adjacent to the
        // destination. The WEST face retains only its south-side inset around the occluder.
        for (Direction direction : new Direction[]{
                Direction.UP, Direction.DOWN, Direction.NORTH,
                Direction.SOUTH, Direction.EAST}) {
            context.getWorld().setBlockState(
                    support.offset(direction), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }

        AIPlayerEntity bot = spawn(context, "BuildHiddenEdge", botFeet,
                new Vec3d(base.getX() + 0.5D, base.getY(), base.getZ() + 1.3D));
        equipTwoCobblestone(bot);
        Vec3d faceCenter = Vec3d.ofCenter(support).add(-0.5D, 0.0D, 0.0D);
        LookAction.lookAt(bot, faceCenter);
        var centerRay = bot.raycast(bot.getBlockInteractionRange(), 1.0F, false);

        require(context, !(centerRay instanceof BlockHitResult hit
                        && hit.getBlockPos().equals(support)
                        && hit.getSide() == Direction.WEST),
                "fixture left the requested face center clickable");
        require(context, !ObservableWorldQuery.canObserveBlock(bot, support),
                "fixture exposed one of the legacy six face-center rays");
        require(context, ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, support),
                "fixture did not expose an exact inset observation ray");

        ActionResult result = BuildAction.placeBlockAt(bot, destination);
        require(context, result.isSuccess(),
                "placeBlockAt did not reach the exact inset sampler: " + result.reason());
        require(context, context.getWorld().getBlockState(destination).isOf(Blocks.COBBLESTONE),
                "edge sampler reported success without a physical placement");
        require(context, bot.getMainHandStack().getCount() == 1,
                "edge sampler did not consume exactly one physical block");
        cleanup(context, bot, "BuildHiddenEdge");
    }

    @GameTest(environment = "minecraftai-gametest:build_action_edge_visibility_game_tests_water_source_may_be_observable_only_through_an_inset_ray", maxTicks = 40)
    public void waterSourceMayBeObservableOnlyThroughAnInsetRay(TestContext context) {
        assertFluidOnlyInsetObservable(context, "BuildInsetWater", Blocks.WATER);
    }

    @GameTest(environment = "minecraftai-gametest:build_action_edge_visibility_game_tests_lava_source_may_be_observable_only_through_an_inset_ray", maxTicks = 40)
    public void lavaSourceMayBeObservableOnlyThroughAnInsetRay(TestContext context) {
        assertFluidOnlyInsetObservable(context, "BuildInsetLava", Blocks.LAVA);
    }

    @GameTest(environment = "minecraftai-gametest:build_action_edge_visibility_game_tests_low_perception_radius_rejects_otherwise_reachable_inset_placement", maxTicks = 40)
    public void lowPerceptionRadiusRejectsOtherwiseReachableInsetPlacement(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(3, 4, 4));
        clear(context, feet);
        context.getWorld().setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos support = feet.east(3).up();
        BlockPos destination = support.west();
        context.getWorld().setBlockState(support, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        AIPlayerEntity bot = spawn(context, "BuildLowPerception", feet, Vec3d.ofBottomCenter(feet));
        equipTwoCobblestone(bot);
        MinecraftAiConfig original = MinecraftAiConfig.get();

        try {
            require(context, bot.canInteractWithBlockAt(support, 0.0D),
                    "fixture support is outside vanilla interaction reach");
            setConfig(withPerceptionRadius(original, 1));
            require(context, MinecraftAiConfig.get().perception().radius() == 1,
                    "fixture failed to lower the configured perception radius");
            require(context, !ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, support),
                    "inset observation escaped the one-block perception radius");

            ActionResult result = BuildAction.placeBlock(
                    bot, support, Direction.WEST, Hand.MAIN_HAND);
            require(context, result.isFailed()
                            && "support_face_not_visible".equals(result.reason()),
                    "low-radius support reached vanilla interaction: " + result.reason());
            require(context, context.getWorld().getBlockState(destination).isAir(),
                    "low-radius placement mutated the external destination");
            require(context, bot.getMainHandStack().getCount() == 2,
                    "low-radius placement consumed a physical block");
        } finally {
            setConfig(original);
            AIPlayerManager.INSTANCE.despawn(bot.getEntityWorld().getServer(), "BuildLowPerception");
        }
        context.complete();
    }

    @GameTest(environment = "minecraftai-gametest:build_action_edge_visibility_game_tests_strict_mode_never_uses_direct_hidden_placement_fallback", maxTicks = 40)
    public void strictModeNeverUsesDirectHiddenPlacementFallback(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        clear(context, feet);
        context.getWorld().setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos unsupported = feet.north(2).up();
        AIPlayerEntity bot = spawn(context, "BuildNoFallback", feet, Vec3d.ofBottomCenter(feet));
        equipTwoCobblestone(bot);

        require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "fixture is not running under strict_survival");
        require(context, ObservableWorldQuery.canObserveCell(bot, unsupported),
                "unsupported destination is not visible enough to detect direct fallback");
        ActionResult result = BuildAction.placeBlockAt(bot, unsupported);

        require(context, result.isFailed(),
                "strict unsupported placement unexpectedly succeeded");
        require(context, context.getWorld().getBlockState(unsupported).isAir(),
                "strict placement used a direct world-mutation fallback");
        require(context, bot.getMainHandStack().getCount() == 2,
                "failed strict placement consumed a block");
        cleanup(context, bot, "BuildNoFallback");
    }

    @GameTest(environment = "minecraftai-gametest:build_action_edge_visibility_game_tests_ordinary_supported_placement_still_uses_vanilla_interaction", maxTicks = 40)
    public void ordinarySupportedPlacementStillUsesVanillaInteraction(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        clear(context, feet);
        context.getWorld().setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos destination = feet.north();
        context.getWorld().setBlockState(
                destination.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        AIPlayerEntity bot = spawn(context, "BuildOrdinary", feet, Vec3d.ofBottomCenter(feet));
        equipTwoCobblestone(bot);

        ActionResult result = BuildAction.placeBlockAt(bot, destination);
        require(context, result.isSuccess(),
                "ordinary supported placement regressed: " + result.reason());
        require(context, context.getWorld().getBlockState(destination).isOf(Blocks.COBBLESTONE),
                "ordinary placement did not mutate through vanilla interaction");
        require(context, bot.getMainHandStack().getCount() == 1,
                "ordinary placement did not consume exactly one physical block");
        cleanup(context, bot, "BuildOrdinary");
    }

    @GameTest(environment = "minecraftai-gametest:build_action_edge_visibility_game_tests_can_accept_placement_at_never_turns_the_bots_head", maxTicks = 40)
    public void canAcceptPlacementAtNeverTurnsTheBotsHead(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        clear(context, feet);
        context.getWorld().setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos supported = feet.north();
        context.getWorld().setBlockState(
                supported.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        // Far cell with no support face at all: the probe must reject it without turning either.
        BlockPos unsupported = feet.east(3).up(2);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    context.getWorld().setBlockState(unsupported.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        AIPlayerEntity bot = spawn(context, "BuildProbeNoTurn", feet, Vec3d.ofBottomCenter(feet));
        equipTwoCobblestone(bot);
        // Deliberately not facing either candidate, so any look-at would show up as a change.
        bot.setYaw(133.0F);
        bot.setPitch(-27.0F);
        bot.setHeadYaw(133.0F);
        bot.setBodyYaw(133.0F);
        float yaw = bot.getYaw();
        float pitch = bot.getPitch();
        float headYaw = bot.getHeadYaw();
        float bodyYaw = bot.getBodyYaw();

        boolean acceptsSupported = BuildAction.canAcceptPlacementAt(bot, supported);
        boolean acceptsUnsupported = BuildAction.canAcceptPlacementAt(bot, unsupported);

        require(context, acceptsSupported, "the probe should accept an ordinary supported cell");
        StringBuilder dbg = new StringBuilder(); for (Direction d : Direction.values()) { dbg.append(d).append("=").append(context.getWorld().getBlockState(unsupported.offset(d)).getBlock()).append(" "); }
        require(context, !acceptsUnsupported, "the probe should reject a cell with no support face: pos=" + unsupported.toShortString() + " eye=" + bot.getEyePos() + " " + dbg);
        require(context, bot.getYaw() == yaw && bot.getPitch() == pitch,
                "canAcceptPlacementAt turned the bot's head: yaw " + yaw + "->" + bot.getYaw()
                        + " pitch " + pitch + "->" + bot.getPitch());
        require(context, bot.getHeadYaw() == headYaw && bot.getBodyYaw() == bodyYaw,
                "canAcceptPlacementAt turned the bot's head/body yaw");
        require(context, context.getWorld().getBlockState(supported).isAir()
                        && context.getWorld().getBlockState(unsupported).isAir(),
                "canAcceptPlacementAt must not mutate the world");
        cleanup(context, bot, "BuildProbeNoTurn");
    }


    private static void assertFluidOnlyInsetObservable(TestContext context,
                                                       String name,
                                                       Block fluid) {
        BlockPos base = context.getAbsolutePos(new BlockPos(3, 4, 4));
        clear(context, base);
        BlockPos botFeet = base.south();
        context.getWorld().setBlockState(
                botFeet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos source = base.east(4).up();
        BlockPos occluder = base.east().up();
        context.getWorld().setBlockState(source, fluid.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(occluder, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        for (Direction direction : new Direction[]{
                Direction.UP, Direction.DOWN, Direction.NORTH,
                Direction.SOUTH, Direction.EAST}) {
            context.getWorld().setBlockState(
                    source.offset(direction), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }

        AIPlayerEntity bot = spawn(context, name, botFeet,
                new Vec3d(base.getX() + 0.5D, base.getY(), base.getZ() + 1.3D));
        Vec3d faceCenter = Vec3d.ofCenter(source).add(-0.499D, 0.0D, 0.0D);
        BlockHitResult centerHit = context.getWorld().raycast(new RaycastContext(
                bot.getEyePos(), faceCenter,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.ANY,
                bot));

        require(context, centerHit.getType() != HitResult.Type.BLOCK
                        || !centerHit.getBlockPos().equals(source)
                        || centerHit.getSide() != Direction.WEST,
                "fixture left the fluid face center visible");
        require(context, !ObservableWorldQuery.canObserveBlock(bot, source),
                "fixture exposed a legacy fluid face-center ray");
        require(context, ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, source),
                "fluid source was not observable through its exposed inset");
        cleanup(context, bot, name);
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

    private static AIPlayerEntity spawn(TestContext context,
                                        String name,
                                        BlockPos feet,
                                        Vec3d pose) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getWorld().getServer(), name, context.getWorld(), pose,
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(context.getWorld(), pose.x, pose.y, pose.z,
                Set.of(), 0.0F, 0.0F, true);
        bot.setOnGround(true);
        require(context, bot.getBlockPos().equals(feet),
                "fixture spawned in the wrong feet cell: " + bot.getBlockPos().toShortString());
        return bot;
    }

    private static void equipTwoCobblestone(AIPlayerEntity bot) {
        bot.getInventory().setSelectedSlot(0);
        bot.getInventory().getMainStacks().set(0, new ItemStack(Items.COBBLESTONE, 2));
        bot.getInventory().markDirty();
    }

    private static void clear(TestContext context, BlockPos origin) {
        for (int dx = -2; dx <= 7; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    context.getWorld().setBlockState(
                            origin.add(dx, dy, dz), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
    }

    private static void cleanup(TestContext context, AIPlayerEntity bot, String name) {
        AIPlayerManager.INSTANCE.despawn(bot.getEntityWorld().getServer(), name);
        context.complete();
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(Text.of(message));
        }
    }
}

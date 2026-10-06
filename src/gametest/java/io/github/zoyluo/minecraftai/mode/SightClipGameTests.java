package io.github.zoyluo.minecraftai.mode;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The sight primitive in a real server: real tags from the loaded data, a real bot as the observer and vanilla's own
 * {@code LivingEntity.hasLineOfSight} as the reference. Nothing here changes what a bot does yet; it proves that
 * {@link SightClip} is vanilla's test with eyes that see through foliage, fences, glass and water, and that lava and every solid
 * building block still blind it.
 */
public final class SightClipGameTests {
    /** The wall stands two blocks east of the bot and three blocks high, which covers the whole slope of the ray to the pig. */
    private static void raiseWall(GameTestHelper context, BlockPos feet, BlockState wall) {
        for (int dy = 0; dy <= 2; dy++) {
            BlockState state = wall;
            if (wall.is(Blocks.OAK_DOOR)) {
                state = dy == 0 ? wall : dy == 1 ? wall.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER) : Blocks.STONE.defaultBlockState();
            }
            context.getLevel().setBlock(feet.east(2).above(dy), state, Block.UPDATE_ALL);
        }
    }

    private static void clearWall(GameTestHelper context, BlockPos feet) {
        for (int dy = 0; dy <= 2; dy++) {
            context.getLevel().setBlock(feet.east(2).above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static void prepare(GameTestHelper context, BlockPos feet) {
        for (int dx = -1; dx <= 8; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = 0; dy <= 5; dy++) {
                    context.getLevel().setBlock(feet.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
                context.getLevel().setBlock(feet.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    private static AIPlayerEntity spawn(GameTestHelper context, String name, BlockPos feet) {
        Vec3 pose = Vec3.atBottomCenterOf(feet);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getLevel().getServer(), name, context.getLevel(), pose, 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(context.getLevel(), pose.x, pose.y, pose.z, Set.of(), 0.0F, 0.0F, true);
        bot.setOnGround(true);
        return bot;
    }

    /** Where the two eyes are and what a plain vanilla ray between them meets, for a failure message. */
    private static String describe(AIPlayerEntity bot, Pig pig) {
        Vec3 from = new Vec3(bot.getX(), bot.getEyeY(), bot.getZ());
        Vec3 to = new Vec3(pig.getX(), pig.getEyeY(), pig.getZ());
        BlockHitResult plain = bot.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
        return " [from " + from + " to " + to + " plain clip " + plain.getType() + " " + plain.getBlockPos().toShortString() + " "
                + bot.level().getBlockState(plain.getBlockPos()) + ", same level " + (bot.level() == pig.level()) + "]";
    }

    private static Pig spawnPig(GameTestHelper context, BlockPos feet) {
        Pig pig = EntityType.PIG.create(context.getLevel(), EntitySpawnReason.COMMAND);
        if (pig == null) {
            throw new IllegalStateException("no pig");
        }
        pig.setNoAi(true);
        pig.snapTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
        context.getLevel().addFreshEntity(pig);
        return pig;
    }

    private static void finish(GameTestHelper context, List<String> failures) {
        if (failures.isEmpty()) {
            context.succeed();
        } else {
            context.fail(Component.nullToEmpty(String.join("; ", failures)));
        }
    }

    @GameTest(environment = "minecraftai-gametest:sight_clip_game_tests_a_bot_sees_a_mob_through_foliage_fences_glass_and_water", maxTicks = 60)
    public void aBotSeesAMobThroughFoliageFencesGlassAndWater(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(2, 4, 4));
        prepare(context, feet);
        AIPlayerEntity bot = spawn(context, "SightThrough", feet);
        Pig pig = spawnPig(context, feet.east(5));
        List<String> failures = new ArrayList<>();
        BlockState[] seeThrough = {
                Blocks.OAK_LEAVES.defaultBlockState(), Blocks.OAK_FENCE.defaultBlockState(), Blocks.OAK_FENCE_GATE.defaultBlockState(),
                Blocks.NETHER_BRICK_FENCE.defaultBlockState(), Blocks.GLASS.defaultBlockState(), Blocks.BLUE_STAINED_GLASS.defaultBlockState(),
                Blocks.GLASS_PANE.defaultBlockState(), Blocks.IRON_BARS.defaultBlockState(), Blocks.ICE.defaultBlockState(),
                Blocks.WATER.defaultBlockState(),
        };
        for (BlockState wall : seeThrough) {
            raiseWall(context, feet, wall);
            String name = wall.getBlock().getDescriptionId();
            if (!SightClip.hasLineOfSight(bot, pig)) {
                failures.add("a bot cannot see the pig through " + name + describe(bot, pig));
            }
            boolean vanilla = bot.hasLineOfSight(pig);
            if (vanilla != wall.is(Blocks.WATER)) {
                failures.add("vanilla hasLineOfSight through " + name + " is " + vanilla + ", the contrast the test relies on is gone");
            }
            clearWall(context, feet);
        }
        pig.discard();
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "SightThrough");
        finish(context, failures);
    }

    @GameTest(environment = "minecraftai-gametest:sight_clip_game_tests_solid_building_blocks_and_lava_still_blind_a_bot", maxTicks = 60)
    public void solidBuildingBlocksAndLavaStillBlindABot(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(2, 4, 4));
        prepare(context, feet);
        AIPlayerEntity bot = spawn(context, "SightBlind", feet);
        Pig pig = spawnPig(context, feet.east(5));
        List<String> failures = new ArrayList<>();
        BlockState[] opaque = {
                Blocks.STONE.defaultBlockState(), Blocks.COBBLESTONE_WALL.defaultBlockState(), Blocks.BLUE_ICE.defaultBlockState(),
                Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST),
                Blocks.LAVA.defaultBlockState(),
        };
        for (BlockState wall : opaque) {
            raiseWall(context, feet, wall);
            String name = wall.getBlock().getDescriptionId();
            if (SightClip.hasLineOfSight(bot, pig)) {
                failures.add("a bot sees the pig through " + name + describe(bot, pig));
            }
            if (wall.is(Blocks.LAVA) == !bot.hasLineOfSight(pig)) {
                failures.add("vanilla hasLineOfSight through " + name + " is not the contrast the test relies on");
            }
            clearWall(context, feet);
        }
        pig.discard();
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "SightBlind");
        finish(context, failures);
    }

    @GameTest(environment = "minecraftai-gametest:sight_clip_game_tests_the_line_of_sight_test_matches_vanilla_in_the_open_and_at_range", maxTicks = 60)
    public void theLineOfSightTestMatchesVanillaInTheOpenAndAtRange(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(2, 4, 4));
        prepare(context, feet);
        AIPlayerEntity bot = spawn(context, "SightRange", feet);
        Pig pig = spawnPig(context, feet.east(5));
        List<String> failures = new ArrayList<>();
        if (!SightClip.hasLineOfSight(bot, pig) || !bot.hasLineOfSight(pig)) {
            failures.add("open air: sight " + SightClip.hasLineOfSight(bot, pig) + " vanilla " + bot.hasLineOfSight(pig) + describe(bot, pig));
        }
        // The limit is on the distance only, so the pig may be moved past it without a chunk to stand in.
        pig.setPos(bot.getX() + SightClip.MAX_LINE_OF_SIGHT + 1.0D, bot.getEyeY(), bot.getZ());
        if (SightClip.hasLineOfSight(bot, pig) || bot.hasLineOfSight(pig)) {
            failures.add("beyond 128 blocks: sight " + SightClip.hasLineOfSight(bot, pig) + " vanilla " + bot.hasLineOfSight(pig));
        }
        pig.discard();
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "SightRange");
        finish(context, failures);
    }

    @GameTest(environment = "minecraftai-gametest:sight_clip_game_tests_a_block_behind_leaves_is_seen_and_the_leaf_is_the_obstruction", maxTicks = 60)
    public void aBlockBehindLeavesIsSeenAndTheLeafIsTheObstruction(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(2, 4, 4));
        prepare(context, feet);
        AIPlayerEntity bot = spawn(context, "SightLeaf", feet);
        BlockPos leaf = feet.east(2).above();
        BlockPos log = feet.east(4).above();
        context.getLevel().setBlock(leaf, Blocks.OAK_LEAVES.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(log, Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        List<String> failures = new ArrayList<>();
        Vec3 eye = new Vec3(feet.getX() + 0.5D, feet.getY() + 1.5D, feet.getZ() + 0.5D);
        Vec3 into = new Vec3(log.getX() + 0.001D, eye.y, eye.z);
        SightClipContext sight = SightClip.context(eye, into, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, bot, log, true)
                .trackObstructions();
        BlockHitResult hit = bot.level().clip(sight);
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(log) || hit.getDirection() != Direction.WEST) {
            failures.add("the log behind the leaf was not seen: " + hit.getType() + " " + hit.getBlockPos().toShortString());
        }
        if (!sight.reachObstructed() || sight.obstructions().size() != 1 || !sight.obstructions().get(0).pos().equals(leaf)) {
            failures.add("the leaf is the one obstruction: " + sight.obstructions());
        }
        BlockHitResult vanilla = bot.level().clip(new ClipContext(eye, into, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, bot));
        if (!vanilla.getBlockPos().equals(leaf)) {
            failures.add("a vanilla pick ray hits the leaf first: " + vanilla.getBlockPos().toShortString());
        }
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "SightLeaf");
        finish(context, failures);
    }

    @GameTest(environment = "minecraftai-gametest:sight_clip_game_tests_the_census_holds_with_the_tags_the_server_loaded", maxTicks = 20)
    public void theCensusHoldsWithTheTagsTheServerLoaded(GameTestHelper context) {
        // 248 of the 1166 vanilla blocks (SeeThroughGoldenTest names them); the data pack tags decide leaves, fences and gates.
        int seeThrough = 0;
        int total = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            total++;
            if (SeeThrough.block(block)) {
                seeThrough++;
            }
        }
        List<String> failures = new ArrayList<>();
        if (total != 1166 || seeThrough != 248) {
            failures.add("registry of " + total + " blocks, " + seeThrough + " see-through (expected 1166 and 248)");
        }
        if (!SeeThrough.block(Blocks.OAK_LEAVES) || !SeeThrough.block(Blocks.OAK_FENCE) || !SeeThrough.block(Blocks.SPRUCE_FENCE_GATE)
                || SeeThrough.block(Blocks.COBBLESTONE_WALL) || SeeThrough.cell(Blocks.LAVA.defaultBlockState())) {
            failures.add("leaves, fences and gates see-through, walls and lava opaque");
        }
        finish(context, failures);
    }
}

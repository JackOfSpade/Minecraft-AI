package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.navigation.NavEngine;
import io.github.zoyluo.minecraftai.navigation.NavEngineSelector;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

/**
 * A sealed obstacle course for the GameTests that run bots on the Baritone engine ({@code nav.engine=baritone}): a stone floor
 * four blocks thick inside a bedrock ring, so the only ways to a goal are the ones the test builds. Every course has its own
 * world slab (its own {@code layer}), and the bots that should navigate with Baritone are switched over with the per-bot engine
 * override, so no other test (or bot) is affected by them.
 */
final class BaritoneEngineArena {
    private static final int BASE_Y = 60;
    private static final int LAYER_STEP = 12;
    static final int CEILING = 6;

    final GameTestHelper context;
    final ServerLevel world;
    /** The cell at floor level in the middle of the course (feet position of a bot standing on the floor). */
    final BlockPos origin;
    final int halfX;
    final int halfZ;

    private BaritoneEngineArena(GameTestHelper context, BlockPos origin, int halfX, int halfZ) {
        this.context = context;
        this.world = context.getLevel();
        this.origin = origin;
        this.halfX = halfX;
        this.halfZ = halfZ;
    }

    /** Builds (and clears) the course: floor at {@code origin.y - 1}, open air above it, bedrock ring around it. */
    static BaritoneEngineArena build(GameTestHelper context, int layer, int halfX, int halfZ) {
        return build(context, layer, halfX, halfZ, 4);
    }

    /** As above with a floor {@code floorDepth} blocks thick (deep enough for water to be dug into it). */
    static BaritoneEngineArena build(GameTestHelper context, int layer, int halfX, int halfZ, int floorDepth) {
        ServerLevel world = context.getLevel();
        BlockPos origin = context.absolutePos(new BlockPos(8, BASE_Y + LAYER_STEP * layer, 8));
        for (int dx = -halfX - 1; dx <= halfX + 1; dx++) {
            for (int dz = -halfZ - 1; dz <= halfZ + 1; dz++) {
                boolean ring = Math.abs(dx) > halfX || Math.abs(dz) > halfZ;
                for (int dy = -floorDepth - 1; dy <= CEILING; dy++) {
                    BlockState state;
                    if (ring && dy >= -floorDepth) {
                        state = Blocks.BEDROCK.defaultBlockState();
                    } else if (dy >= -floorDepth && dy <= -1) {
                        state = Blocks.STONE.defaultBlockState();
                    } else {
                        state = Blocks.AIR.defaultBlockState();
                    }
                    world.setBlock(origin.offset(dx, dy, dz), state, Block.UPDATE_CLIENTS);
                }
            }
        }
        // Light everywhere: bots attract natural spawns, and a hostile in view makes the danger watcher pause the task under test
        // (a skeleton did that to a follow test). Invisible light blocks in a grid keep every cell of the course at light >= 8.
        for (int dx = -halfX; dx <= halfX; dx += 4) {
            for (int dz = -halfZ; dz <= halfZ; dz += 4) {
                world.setBlock(origin.offset(dx, 3, dz), Blocks.LIGHT.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        return new BaritoneEngineArena(context, origin, halfX, halfZ);
    }

    void set(int dx, int dy, int dz, Block block) {
        world.setBlock(origin.offset(dx, dy, dz), block.defaultBlockState(), Block.UPDATE_ALL);
    }

    /** {@code block} in the column at ({@code dx}, {@code dz}) for heights {@code fromDy..toDy}. */
    void fill(int dx, int dz, Block block, int fromDy, int toDy) {
        for (int dy = fromDy; dy <= toDy; dy++) {
            set(dx, dy, dz, block);
        }
    }

    /** A full-height (floor to ceiling) bedrock wall along {@code z} at column {@code dx}, from {@code fromZ} to {@code toZ}. */
    void bedrockWall(int dx, int fromZ, int toZ) {
        for (int dz = fromZ; dz <= toZ; dz++) {
            fill(dx, dz, Blocks.BEDROCK, 0, CEILING);
        }
    }

    /** A closed wooden door at the given floor-level cell (both halves), facing along x. */
    void closedDoor(int dx, int dz) {
        BlockPos lower = origin.offset(dx, 0, dz);
        BlockState base = Blocks.OAK_DOOR.defaultBlockState();
        world.setBlock(lower, base.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
        world.setBlock(lower.above(), base.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
    }

    BlockPos cell(int dx, int dy, int dz) {
        return origin.offset(dx, dy, dz);
    }

    /** Spawns a bot at {@code feet} and points it at the Baritone engine (per-bot override; the global engine stays legacy). */
    AIPlayerEntity spawnOnBaritone(String name, BlockPos feet) {
        AIPlayerEntity bot = spawn(name, feet);
        NavEngineSelector.setBotEngine(bot.getUUID(), NavEngine.BARITONE);
        return bot;
    }

    /** Spawns a bot that follows the ordinary (legacy) engine: what the tests use for the followed player. */
    AIPlayerEntity spawn(String name, BlockPos feet) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet), 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        teleportTo(bot, feet);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        return bot;
    }

    /** A followed player stand-in that stays where it is put. */
    AIPlayerEntity spawnHolder(String name, BlockPos feet) {
        AIPlayerEntity holder = spawn(name, feet);
        TaskManager.INSTANCE.assign(holder, new HoldTask(), TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hold_still"));
        return holder;
    }

    void teleportTo(AIPlayerEntity bot, BlockPos pos) {
        bot.teleportTo(world, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.fallDistance = 0.0F;
        bot.setOnGround(true);
    }

    /** Removes the bots (their tasks first) and ends the test successfully. */
    void finish(AIPlayerEntity... bots) {
        for (AIPlayerEntity bot : bots) {
            TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
            AIPlayerManager.INSTANCE.despawn(world.getServer(), bot.getGameProfile().name());
        }
        context.succeed();
    }

    void require(boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
            throw new IllegalStateException(message);
        }
    }
}

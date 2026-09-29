package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.gametest.GameTestCleanup;
import io.github.zoyluo.minecraftai.gametest.GameTestTimeLock;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Fixture helpers shared by the emergency-shelter and underground-safety GameTests
 * ({@link EmergencyShelterAtomicRecoveryGameTests}, {@link EmergencyShelterMaterialSchedulingGameTests},
 * {@link EmergencyShelterRescueGameTests} and {@link UndergroundSafetyGameTests}): these classes
 * carried byte-identical copies of the shelter-shell geometry, the platform/seal checks and the
 * {@link GameTestTimeLock} acquire/release idiom. Only helpers that were identical across their
 * callers were moved here; helpers whose bodies diverged (for example each class's own {@code spawn})
 * were left local to keep every test's behaviour unchanged.
 */
final class ShelterGameTestFixtures {
    private ShelterGameTestFixtures() {
    }

    /** The nine shell positions (top plus the four horizontal walls, two cells tall) around {@code feet}. */
    static List<BlockPos> shelterShell(BlockPos feet) {
        List<BlockPos> shell = new ArrayList<>(9);
        shell.add(feet.above(2));
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            shell.add(feet.relative(direction));
            shell.add(feet.above().relative(direction));
        }
        return List.copyOf(shell);
    }

    static boolean isSealed(GameTestHelper context, BlockPos pos) {
        var state = context.getLevel().getBlockState(pos);
        return !state.canBeReplaced()
                && !state.getCollisionShape(context.getLevel(), pos).isEmpty();
    }

    static void assertPhysicalExit(GameTestHelper context,
                                   AIPlayerEntity bot,
                                   BlockPos shelterFeet) {
        BlockPos actual = bot.blockPosition();
        int horizontal = Math.abs(actual.getX() - shelterFeet.getX())
                + Math.abs(actual.getZ() - shelterFeet.getZ());
        require(context, actual.getY() == shelterFeet.getY() && horizontal == 1,
                "terminal pose was not one adjacent exit step: "
                        + shelterFeet.toShortString() + " -> " + actual.toShortString());
        require(context, Standability.isStandable(context.getLevel(), actual),
                "terminal exit was not standable: " + actual.toShortString());
        require(context, context.getLevel().getBlockState(actual).isAir()
                        && context.getLevel().getBlockState(actual.above()).isAir(),
                "terminal exit did not leave a two-block opening");
    }

    static void preparePlatform(GameTestHelper context, BlockPos feet, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                context.getLevel().setBlock(cell.below(),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                context.getLevel().setBlock(cell,
                        Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                context.getLevel().setBlock(cell.above(),
                        Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                context.getLevel().setBlock(cell.above(2),
                        Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    static void finish(GameTestHelper context, AIPlayerEntity bot, String name) {
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
        DangerWatcher.INSTANCE.clear(bot);
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
        context.succeed();
    }

    static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }

    /**
     * Registers {@code perTick} as the test's single top-level {@code failIfEver} callback,
     * gated on {@link GameTestTimeLock}: the callback polls {@link GameTestTimeLock#tryAcquire()}
     * every tick until it holds the lock, then runs {@code perTick} from that same callback on
     * every subsequent tick. See {@link GameTestTimeLock}'s class docs for why this must stay the
     * test's only {@code failIfEver} registration.
     *
     * <p>The test completes only through {@code perTick}'s own assertions ({@link #finish} after
     * the checks) or fails through them. The lock is released by {@link GameTestCleanup#whenFinished}
     * when the test really ends (pass, fail or timeout). It must NOT be released from
     * {@code GameTestHelper.succeedIf}: that runs its task on the first tick and then passes the test,
     * which used to complete every test built on this helper at tick 1, before any assertion ran.
     */
    static void runLocked(GameTestHelper context, Runnable perTick) {
        boolean[] timeLockAcquired = {false};
        boolean[] over = {false};
        GameTestCleanup.whenFinished(context, () -> {
            over[0] = true;
            if (timeLockAcquired[0]) {
                timeLockAcquired[0] = false;
                GameTestTimeLock.release();
            }
        });
        context.failIfEver(() -> {
            if (over[0]) {
                return;
            }
            if (!timeLockAcquired[0]) {
                if (!GameTestTimeLock.tryAcquire()) {
                    return;
                }
                timeLockAcquired[0] = true;
            }
            perTick.run();
        });
    }
}

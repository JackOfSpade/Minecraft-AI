package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.gametest.GameTestTimeLock;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

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
        shell.add(feet.up(2));
        for (Direction direction : Direction.Type.HORIZONTAL) {
            shell.add(feet.offset(direction));
            shell.add(feet.up().offset(direction));
        }
        return List.copyOf(shell);
    }

    static boolean isSealed(TestContext context, BlockPos pos) {
        var state = context.getWorld().getBlockState(pos);
        return !state.isReplaceable()
                && !state.getCollisionShape(context.getWorld(), pos).isEmpty();
    }

    static void assertPhysicalExit(TestContext context,
                                   AIPlayerEntity bot,
                                   BlockPos shelterFeet) {
        BlockPos actual = bot.getBlockPos();
        int horizontal = Math.abs(actual.getX() - shelterFeet.getX())
                + Math.abs(actual.getZ() - shelterFeet.getZ());
        require(context, actual.getY() == shelterFeet.getY() && horizontal == 1,
                "terminal pose was not one adjacent exit step: "
                        + shelterFeet.toShortString() + " -> " + actual.toShortString());
        require(context, Standability.isStandable(context.getWorld(), actual),
                "terminal exit was not standable: " + actual.toShortString());
        require(context, context.getWorld().getBlockState(actual).isAir()
                        && context.getWorld().getBlockState(actual.up()).isAir(),
                "terminal exit did not leave a two-block opening");
    }

    static void preparePlatform(TestContext context, BlockPos feet, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos cell = feet.add(dx, 0, dz);
                context.getWorld().setBlockState(cell.down(),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                context.getWorld().setBlockState(cell,
                        Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                context.getWorld().setBlockState(cell.up(),
                        Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                context.getWorld().setBlockState(cell.up(2),
                        Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
    }

    static void finish(TestContext context, AIPlayerEntity bot, String name) {
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
        DangerWatcher.INSTANCE.clear(bot);
        AIPlayerManager.INSTANCE.despawn(bot.getEntityWorld().getServer(), name);
        context.complete();
    }

    static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(Text.of(message));
        }
    }

    /**
     * Registers {@code perTick} as the test's single top-level {@code runAtEveryTick} callback,
     * gated on {@link GameTestTimeLock}: the callback polls {@link GameTestTimeLock#tryAcquire()}
     * every tick until it holds the lock, then runs {@code perTick} from that same callback on
     * every subsequent tick, and releases the lock in {@code context.addFinalTask} if it was ever
     * acquired. See {@link GameTestTimeLock}'s class docs for why this must stay the test's only
     * {@code runAtEveryTick} registration.
     */
    static void runLocked(TestContext context, Runnable perTick) {
        boolean[] timeLockAcquired = {false};
        context.addFinalTask(() -> {
            if (timeLockAcquired[0]) {
                GameTestTimeLock.release();
            }
        });
        context.runAtEveryTick(() -> {
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

package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.TeleportAudit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

/**
 * Drives a scenario over real game ticks. DescendToYTask moves by walked steps (R5: no micro-teleports), and a walked step advances
 * only when the server ticks the bot, so a fixture that called {@code task.tick(bot)} in a plain loop and looked at the bot's block
 * position afterwards would never see it move. A scenario is a list of stages evaluated in order, one evaluation per game tick; a
 * stage answers true when it is done (the next stage then runs in the same tick), so an assertion stage simply asserts and answers true.
 * Every tick of a ticking stage also asserts that the bot was not teleported (no path-correction teleports).
 */
final class DescendTickStages {
    private DescendTickStages() {
    }

    /** Runs the stages in order over the game ticks of the test (the last one usually ends the test). */
    static void run(GameTestHelper context, BooleanSupplier... stages) {
        int[] index = {0};
        context.onEachTick(() -> {
            while (index[0] < stages.length) {
                if (!stages[index[0]].getAsBoolean()) {
                    return;
                }
                index[0]++;
            }
        });
    }

    /**
     * A stage that ticks {@code task} once per game tick until {@code done} holds (it is evaluated before each tick, so it sees the
     * world exactly as the previous task tick and the physics of that game tick left it). Fails the test with {@code what} when it has
     * not held after {@code maxTicks} task ticks or the task ended without it holding.
     */
    static BooleanSupplier tickUntil(GameTestHelper context, AbstractTask task, AIPlayerEntity bot, int maxTicks, String what,
                                     BooleanSupplier done) {
        return tickUntil(context, () -> task, bot, maxTicks, what, done);
    }

    /** {@link #tickUntil(GameTestHelper, AbstractTask, AIPlayerEntity, int, String, BooleanSupplier)} for a task that an earlier stage creates. */
    static BooleanSupplier tickUntil(GameTestHelper context, Supplier<? extends AbstractTask> current, AIPlayerEntity bot,
                                     int maxTicks, String what, BooleanSupplier done) {
        int[] used = {0};
        return () -> {
            if (done.getAsBoolean()) {
                return true;
            }
            AbstractTask task = current.get();
            if (TeleportAudit.corrections(bot) != 0) {
                context.fail(Component.nullToEmpty(what + " (the bot was teleported: corrections=" + TeleportAudit.corrections(bot)
                        + " last=" + TeleportAudit.lastCaller(bot) + ")"));
            }
            if (task.state() != TaskState.RUNNING) {
                context.fail(Component.nullToEmpty(what + " (the task ended as " + task.state() + ": " + task.failureReason()
                        + " at " + bot.blockPosition().toShortString() + ")"));
            }
            if (++used[0] > maxTicks) {
                context.fail(Component.nullToEmpty(what + " (not within " + maxTicks + " ticks, at " + bot.blockPosition().toShortString()
                        + ")"));
            }
            task.tick(bot);
            return false;
        };
    }

    /** A stage that waits {@code ticks} game ticks without touching the task. */
    static BooleanSupplier idle(int ticks) {
        int[] used = {0};
        return () -> ++used[0] > ticks;
    }

    /** A stage that runs {@code action} once (in the tick its turn comes) and is done. */
    static BooleanSupplier once(Runnable action) {
        return () -> {
            action.run();
            return true;
        };
    }
}

package io.github.zoyluo.minecraftai.gametest;

import io.github.zoyluo.minecraftai.action.LookAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.perception.CreaturePerception;
import io.github.zoyluo.minecraftai.perception.CreatureSenses;
import java.util.List;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * How a fixture makes a companion bot NOTICE a creature honestly, now that the whole GameTest suite runs with the realistic perception
 * ON (docs/PERCEPTION.md): the fixture puts the creature where the premise says and lets the bot's own senses do the rest, never a
 * shortcut past the model.
 *
 * <ul>
 *   <li><b>Face it</b>: {@link #faceToward} turns the bot's head to the creature (inside the full-attention cone) or, for a premise
 *       that needs the bot looking elsewhere, {@link #faceAway}.</li>
 *   <li><b>Wait the reaction time</b> from the shared formula ({@link CreaturePerception#requiredSeconds}, from the real distance and
 *       angle) plus the scan cadence, never a larger arbitrary budget: {@link #reactionTicks}, or {@link #afterNoticed}, which runs
 *       the rest of the scenario the first tick the bot has noticed every creature and fails the test if that has not happened by the
 *       formula's deadline.</li>
 *   <li>A premise of "the bot was attacked" is a REAL blow ({@code hurtServer} with the creature as the attacker): pain tells the
 *       victim where the striker is.</li>
 * </ul>
 */
public final class PerceptionFixtures {
    /**
     * Ticks the scan may add to the formula's reaction time: a creature that is not being watched yet is read every second tick, and
     * the notice lands on the tick after the last read of the run.
     */
    public static final int SCAN_SLACK_TICKS = 3;

    /**
     * The longest wait, in ticks, a fixture's scenario budget ({@code maxTicks}) has to add for the bot to notice its threats: the
     * shared formula for a creature up to 24 blocks away at the edge of the view field (100 degrees, twice the reaction time) is
     * (0.5 + 1.5 * 24 / 64) s * 2 = 43 ticks, plus {@link #SCAN_SLACK_TICKS}. {@link #afterNoticed} fails at the formula's own
     * deadline of the actual geometry, never at this bound: it only sizes the annotation budget.
     */
    public static final int MAX_WAIT_TICKS = 50;

    /** The scenario that runs once the bot has noticed its threats; {@code sinceNoticed} counts the ticks since then. */
    @FunctionalInterface
    public interface Scenario {
        void run(java.util.function.LongSupplier sinceNoticed);
    }

    private PerceptionFixtures() {
    }

    // The GameTest runner keeps its per-tick callbacks in one hash map that it iterates while it runs them: a callback that registers
    // another ({@code failIfEver}, {@code onEachTick}, {@code runAtTickTime}) corrupts that iteration and crashes the server. A
    // scenario that starts from inside a callback (after the notice) therefore registers its per-tick checks here instead: one master
    // callback per test, registered up front by afterNoticed, runs them in order every tick.
    private static final java.util.Map<GameTestHelper, java.util.List<Runnable>> HOOKS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static java.util.List<Runnable> hooksFor(GameTestHelper context) {
        return HOOKS.computeIfAbsent(context, c -> {
            java.util.List<Runnable> hooks = new java.util.ArrayList<>();
            c.onEachTick(() -> {
                for (int i = 0; i < hooks.size(); i++) {
                    hooks.get(i).run();
                }
            });
            return hooks;
        });
    }

    /**
     * {@code context.onEachTick(check)} for a helper that a test may call either at its top or from a scenario started by
     * {@link #afterNoticed}: registered with the fixture's own hook list when the test has one (a callback registering a callback
     * would crash the runner), with the runner otherwise.
     */
    public static void scheduleEachTick(GameTestHelper context, Runnable check) {
        java.util.List<Runnable> hooks = HOOKS.get(context);
        if (hooks != null) {
            hooks.add(check);
        } else {
            context.onEachTick(check);
        }
    }

    /**
     * For a test that starts {@link #afterNoticed} from inside one of the runner's own callbacks (a {@code runAtTickTime} step): call
     * it once at the top of the test, where registering a callback is safe.
     */
    public static void prepare(GameTestHelper context) {
        hooksFor(context);
    }

    /**
     * {@code context.failIfEver(check)} / {@code context.onEachTick(check)} for a scenario that starts inside {@link #afterNoticed}
     * (from inside a tick callback, where the runner's own registration would crash it): {@code check} runs every tick from now on.
     */
    public static void everyTick(GameTestHelper context, Runnable check) {
        java.util.List<Runnable> hooks = HOOKS.get(context);
        if (hooks == null) {
            throw new IllegalStateException("everyTick is for scenarios started by afterNoticed");
        }
        hooks.add(check);
    }

    /** Turns {@code bot}'s head straight to {@code target}'s eyes. */
    public static void faceToward(AIPlayerEntity bot, Entity target) {
        LookAction.lookAt(bot, target.getEyePosition());
    }

    /** Turns {@code bot}'s head straight to {@code point} (a fixture that wants a whole group of creatures inside the view). */
    public static void facePoint(AIPlayerEntity bot, Vec3 point) {
        LookAction.lookAt(bot, point);
    }

    /**
     * A REAL melee blow of {@code attacker} on {@code bot} (the premise "the bot was attacked"): the join invulnerability of a
     * freshly spawned bot is ended first (the imaginary client has loaded) and the hurt cooldown is cleared, so the blow lands and
     * the bot feels it; pain tells it where the striker is (docs/PERCEPTION.md, "Blows"). True when the blow was applied.
     */
    public static boolean struckBy(GameTestHelper context, AIPlayerEntity bot, LivingEntity attacker, float amount) {
        if (!bot.connection.hasClientLoaded()) {
            bot.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        }
        bot.invulnerableTime = 0;
        var sources = context.getLevel().damageSources();
        return bot.hurtServer(context.getLevel(), attacker instanceof net.minecraft.world.entity.player.Player player
                ? sources.playerAttack(player) : sources.mobAttack(attacker), amount);
    }

    /** Turns {@code bot}'s head to the point opposite {@code target} (the creature ends up directly behind it). */
    public static void faceAway(AIPlayerEntity bot, Entity target) {
        Vec3 eye = bot.getEyePosition();
        Vec3 opposite = eye.add(eye.subtract(target.getEyePosition()));
        LookAction.lookAt(bot, opposite);
    }

    /** The angle in degrees between the bot's look direction and the direction to {@code creature}. */
    public static double angleTo(AIPlayerEntity bot, LivingEntity creature) {
        Vec3 look = bot.getViewVector(1.0F);
        Vec3 toward = creature.getEyePosition().subtract(bot.getEyePosition());
        return CreaturePerception.angleDeg(look.x, look.y, look.z, toward.x, toward.y, toward.z);
    }

    /**
     * The ticks the shared formula asks of the bot to notice {@code creature} as things stand now (distance, angle, sneaking), 0
     * when it is at once, -1 when it can never be sighted from here (behind, invisible). Without the scan slack.
     */
    public static int formulaTicks(AIPlayerEntity bot, LivingEntity creature) {
        double required = CreaturePerception.requiredSeconds(CreaturePerception.Params.defaults(), angleTo(bot, creature),
                bot.getEyePosition().distanceTo(creature.getEyePosition()),
                new CreaturePerception.Subject(creature.isDiscrete() || creature.isCrouching(), 1.0D), true);
        return (int) CreaturePerception.noticeTick(required);
    }

    /** {@link #formulaTicks} plus the scan slack: the most a fixture ever needs to wait, derived from the formula and nothing else. */
    public static int reactionTicks(AIPlayerEntity bot, LivingEntity creature) {
        int ticks = formulaTicks(bot, creature);
        if (ticks < 0) {
            throw new IllegalStateException("the creature cannot be sighted from where the bot looks: " + creature.getType());
        }
        return ticks + SCAN_SLACK_TICKS;
    }

    /** The longest {@link #reactionTicks} of several creatures: a wait that covers all of them. */
    public static int reactionTicks(AIPlayerEntity bot, List<? extends LivingEntity> creatures) {
        int most = 0;
        for (LivingEntity creature : creatures) {
            // A creature that cannot be sighted from where the bot looks (behind it, a passive animal answered by plain sight) adds
            // no formula wait: it is not noticed by sight, or it is noticed at once.
            if (formulaTicks(bot, creature) >= 0) {
                most = Math.max(most, reactionTicks(bot, creature));
            }
        }
        return most;
    }

    /** True when {@code bot} has noticed every one of {@code creatures}. */
    public static boolean noticedAll(AIPlayerEntity bot, List<? extends LivingEntity> creatures) {
        for (LivingEntity creature : creatures) {
            if (!CreatureSenses.INSTANCE.noticed(bot, creature)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The scenario after the bot has noticed the creatures: waits (one tick at a time, from the tick it is called) until the bot has
     * noticed all of them, then runs {@code then} once, in that tick. The wait is bounded by the formula's reaction time from the
     * geometry at the call, plus the scan slack; a bot that has not noticed them by then fails the test (a product or fixture problem,
     * not something a longer wait would fix). Call it after the creatures are placed and the bot faces them; the scenario's own tick
     * budget ({@code maxTicks}) must include {@link #reactionTicks}.
     */
    public static void afterNoticed(GameTestHelper context, AIPlayerEntity bot, List<? extends LivingEntity> creatures, Runnable then) {
        afterNoticed(context, bot, creatures, since -> then.run());
    }

    /**
     * {@link #afterNoticed(GameTestHelper, AIPlayerEntity, List, Scenario)} for a scenario that begins at the bot's FIRST reaction
     * to a threat it has just noticed (a fixture that drives a task or the watcher by hand, tick by tick): the watcher scans in the
     * very tick the creature is noticed and has already taken up the bot's reaction by the time the scenario starts, so the
     * scenario begins from a clean slate, {@link #resetReaction}. The noticing itself, the thing under test, is untouched.
     */
    public static void afterNoticedFresh(GameTestHelper context, AIPlayerEntity bot, List<? extends LivingEntity> creatures, Scenario then) {
        // Several creatures are rarely noticed in the same tick (each has its own distance and the scan reads alternate creatures on
        // alternate ticks). The watcher reacts to the first ones at once and would turn the bot away from the rest before it has had
        // the time to register them; that partial reaction is undone as it happens (the bot keeps looking where the fixture turned it)
        // until it knows every one of them. The noticing itself is untouched.
        hooksFor(context);
        float yaw = bot.getYRot();
        float pitch = bot.getXRot();
        io.github.zoyluo.minecraftai.task.Task before = io.github.zoyluo.minecraftai.task.TaskManager.INSTANCE.getActive(bot).orElse(null);
        boolean[] over = {false};
        everyTick(context, () -> {
            if (over[0] || noticedAll(bot, creatures)) {
                over[0] = true;
                return;
            }
            if (io.github.zoyluo.minecraftai.task.TaskManager.INSTANCE.getActive(bot).orElse(null) != before
                    && io.github.zoyluo.minecraftai.task.TaskManager.INSTANCE.isActiveSafety(bot)) {
                resetReaction(bot);
                LookAction.setYawPitch(bot, yaw, pitch);
            }
        });
        afterNoticed(context, bot, creatures, since -> {
            resetReaction(bot);
            then.run(since);
        });
    }

    /**
     * Undoes the bot's reaction to what it noticed, not the noticing: a SAFETY task the watcher took up is aborted (the interrupted
     * mission resumes), the walk it started stops and the watcher forgets its cooldowns. The bot still knows the creatures; the
     * next scan reacts to them again, exactly as it would to a threat noticed in that tick.
     */
    public static void resetReaction(AIPlayerEntity bot) {
        if (io.github.zoyluo.minecraftai.task.TaskManager.INSTANCE.isActiveSafety(bot)) {
            io.github.zoyluo.minecraftai.task.TaskManager.INSTANCE.abort(bot);
            io.github.zoyluo.minecraftai.task.TaskManager.INSTANCE.resumeFromPause(bot);
        }
        bot.getActionPack().stopAll();
        bot.getActionPack().forgetPathThrottleForTests(); // the reaction's route request must not throttle the scenario's own
        io.github.zoyluo.minecraftai.task.DangerWatcher.INSTANCE.clear(bot);
    }

    /**
     * {@link #afterNoticed(GameTestHelper, AIPlayerEntity, List, Scenario)} for a bot that is busy with something of its own and will
     * turn its head while it waits (a task looking at its own source): the deadline is the formula's worst case for a creature at the
     * edge of the view field ({@code worstAngleDeg}, at most 100), not the angle of the moment.
     */
    public static void afterNoticedWithin(GameTestHelper context, AIPlayerEntity bot, List<? extends LivingEntity> creatures,
                                          double worstAngleDeg, Scenario then) {
        hooksFor(context);
        int budget = 0;
        for (LivingEntity creature : creatures) {
            double required = CreaturePerception.requiredSeconds(CreaturePerception.Params.defaults(), worstAngleDeg,
                    bot.getEyePosition().distanceTo(creature.getEyePosition()),
                    new CreaturePerception.Subject(creature.isDiscrete() || creature.isCrouching(), 1.0D), true);
            budget = Math.max(budget, (int) CreaturePerception.noticeTick(required) + SCAN_SLACK_TICKS);
        }
        int deadline = (int) context.getTick() + budget;
        int shown = budget;
        boolean[] done = {false};
        everyTick(context, () -> {
            if (done[0]) {
                return;
            }
            if (noticedAll(bot, creatures)) {
                done[0] = true;
                long at = context.getTick();
                then.run(() -> context.getTick() - at);
            } else if (context.getTick() > deadline) {
                done[0] = true;
                context.fail(Component.literal("the bot did not notice its threats within the formula's worst-case reaction time ("
                        + shown + " ticks incl. slack): "
                        + creatures.stream().map(c -> c.getType() + "@" + Math.round(bot.distanceTo(c) * 10.0D) / 10.0D
                        + "m/" + Math.round(angleTo(bot, c)) + "deg noticed=" + CreatureSenses.INSTANCE.noticed(bot, c)).toList()));
            }
        });
    }

    /** {@link #afterNoticed(GameTestHelper, AIPlayerEntity, List, Runnable)} for a scenario with its own tick budgets: it counts from the notice. */
    public static void afterNoticed(GameTestHelper context, AIPlayerEntity bot, List<? extends LivingEntity> creatures, Scenario then) {
        hooksFor(context); // registered now, outside any callback
        int budget = reactionTicks(bot, creatures);
        int deadline = (int) context.getTick() + budget;
        boolean[] done = {false};
        everyTick(context, () -> {
            if (done[0]) {
                return;
            }
            if (noticedAll(bot, creatures)) {
                done[0] = true;
                long at = context.getTick();
                then.run(() -> context.getTick() - at);
            } else if (context.getTick() > deadline) {
                done[0] = true;
                context.fail(Component.literal("the bot did not notice its threats within the formula's reaction time ("
                        + budget + " ticks incl. slack): "
                        + creatures.stream().map(c -> c.getType() + "@" + Math.round(bot.distanceTo(c) * 10.0D) / 10.0D
                        + "m/" + Math.round(angleTo(bot, c)) + "deg noticed=" + CreatureSenses.INSTANCE.noticed(bot, c)).toList()));
            }
        });
    }
}

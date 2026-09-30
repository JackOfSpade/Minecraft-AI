package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.observe.TpsGuard;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.Vec3;

/**
 * The bodies of the follow pace GameTests. The legacy engine ({@code FollowPaceGameTests}) and Baritone
 * ({@code FollowPaceBaritoneGameTests}) run the same scenario and must show the same behaviour: the followed player is a survival mock
 * that the test moves along +x (a mock is not ticked, so its sneak and sprint flags stay as set), and what is measured is what an
 * observer sees of the follower: its sprint and sneak flags on the ticks it moves.
 */
final class FollowPaceScenarios {
    /** Horizontal blocks per tick below which the bot counts as standing still. */
    private static final double MOVING = 0.03D;

    private FollowPaceScenarios() {
    }

    /** What the follower did, sampled at the end of every tick. */
    private static final class Watch {
        final AIPlayerEntity bot;
        Vec3 last;
        boolean moving;

        Watch(AIPlayerEntity bot) {
            this.bot = bot;
            this.last = bot.position();
        }

        void sample() {
            Vec3 now = bot.position();
            moving = Math.hypot(now.x - last.x, now.z - last.z) > MOVING;
            last = now;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** A player 15 blocks away: the follower sprints; once it is within about six blocks it walks; it does not flap. */
    static void sprintsWhenFarAndWalksWhenClose(GameTestHelper context, boolean baritone, String prefix) {
        FollowFieldFixture f = new FollowFieldFixture(context, 24, 8);
        AIPlayerEntity bot = f.bot(prefix + "Far", -13, 0, baritone);
        ServerPlayer target = f.target(2, 0);
        FollowTask follow = f.follow(bot, target.getGameProfile().name(), "gametest_follow_pace_far");
        Watch watch = new Watch(bot);
        int[] tick = {0};
        int[] counts = new int[6]; // 0 farMoving, 1 farSprint, 2 closeSprint, 3 changes, 4 closeMoving, 5 last sprint flag (-1 none)
        counts[5] = -1;
        context.failIfEver(() -> {
            int now = ++tick[0];
            f.require(follow.state() == TaskState.RUNNING, "follow ended: " + follow.state() + " " + follow.failureReason());
            watch.sample();
            double gap = bot.distanceTo(target);
            if (watch.moving) {
                boolean sprint = bot.isSprinting();
                if (gap > 11.0D) {
                    counts[0]++;
                    if (sprint) {
                        counts[1]++;
                    }
                } else if (gap <= 5.0D) {
                    counts[4]++;
                    if (sprint) {
                        counts[2]++;
                    }
                }
                if (counts[5] >= 0 && counts[5] != (sprint ? 1 : 0)) {
                    counts[3]++;
                }
                counts[5] = sprint ? 1 : 0;
            }
            if (now > 20 && follow.isWaiting() && gap <= 3.6D) {
                f.require(counts[0] >= 10, "the bot hardly moved while the player was far: " + counts[0] + " ticks");
                f.require(counts[1] * 10 >= counts[0] * 6, "the bot did not sprint while far: " + counts[1] + " of " + counts[0] + " moving ticks");
                f.require(counts[2] <= 2, "the bot sprinted while within five blocks: " + counts[2] + " of " + counts[4] + " moving ticks");
                f.require(counts[3] <= 4, "the gait flapped: " + counts[3] + " sprint changes");
                f.finish();
            }
            f.require(now < 290, "the follower never arrived (gap " + gap + ")");
        });
    }

    /** The player sneaks: the follower creeps too, on every tick it moves, and never sprints. */
    static void matchesSneakingPlayer(GameTestHelper context, boolean baritone, String prefix, boolean tpsThrottled) {
        FollowFieldFixture f = new FollowFieldFixture(context, 24, 8);
        AIPlayerEntity bot = f.bot(prefix + "Sneak", -6, 0, baritone);
        ServerPlayer target = f.target(1, 0);
        target.setShiftKeyDown(true);
        FollowTask follow = f.follow(bot, target.getGameProfile().name(), "gametest_follow_pace_sneak");
        if (tpsThrottled) {
            // Only this follower runs on the "degraded server": every other scenario keeps the normal scan rates.
            TpsGuard.forceDegradedForTests(bot.getUUID(), true);
            f.onFinish(() -> TpsGuard.forceDegradedForTests(bot.getUUID(), false));
        }
        Watch watch = new Watch(bot);
        int[] tick = {0};
        int[] stats = new int[3]; // moving ticks, ticks moving without shift, sprint ticks
        StringBuilder misses = new StringBuilder();
        context.failIfEver(() -> {
            int now = ++tick[0];
            f.require(follow.state() == TaskState.RUNNING, "follow ended: " + follow.state() + " " + follow.failureReason());
            // The player creeps away along +x at 1 block per second.
            f.place(target, 1.0D + now * 0.05D, 0.0D);
            target.setShiftKeyDown(true);
            watch.sample();
            // Only ticks a controller drives: at the end of a short leg the bot slides a tick or two with its keys already released
            // (and, like a player who lets go of the sneak key, no longer sneaking) before the next leg starts.
            boolean driven = !bot.getActionPack().isPathExecutorIdle() || !bot.getActionPack().isWalkToIdle();
            if (now > 40 && watch.moving && driven) {
                stats[0]++;
                if (!bot.isShiftKeyDown()) {
                    stats[1]++;
                    if (misses.length() < 400) {
                        misses.append(" [t").append(now).append(" gait=").append(follow.paceGait()).append(" gap=")
                                .append(Math.round(bot.distanceTo(target) * 10.0D) / 10.0D).append(" waiting=").append(follow.isWaiting())
                                .append(" pathIdle=").append(bot.getActionPack().isPathExecutorIdle()).append(" lease=")
                                .append(bot.getActionPack().leasedGait()).append(" last=").append(bot.getActionPack().lastPaceGait()).append("]");
                    }
                }
                if (bot.isSprinting()) {
                    stats[2]++;
                }
            }
            if (now >= 200) {
                f.require(stats[0] >= 40, "the follower hardly moved: " + stats[0] + " ticks");
                f.require(stats[1] == 0, "the follower moved without sneaking on " + stats[1] + " of " + stats[0] + " ticks" + misses);
                f.require(stats[2] == 0, "the follower sprinted after a sneaking player: " + stats[2] + " ticks");
                f.require(bot.distanceTo(target) <= 8.0D, "the follower fell behind: " + bot.distanceTo(target));
                f.finish();
            }
        });
    }

    /** The player sprints away: the follower keeps up (the gap stays under twelve blocks) at a sprint. */
    static void matchesSprintingPlayer(GameTestHelper context, boolean baritone, String prefix) {
        FollowFieldFixture f = new FollowFieldFixture(context, 44, 8);
        AIPlayerEntity bot = f.bot(prefix + "Run", -30, 0, baritone);
        ServerPlayer target = f.target(-24, 0);
        target.setSprinting(true);
        FollowTask follow = f.follow(bot, target.getGameProfile().name(), "gametest_follow_pace_sprint");
        Watch watch = new Watch(bot);
        int[] tick = {0};
        double[] maxGap = {0.0D};
        int[] stats = new int[2]; // moving ticks, sprint ticks
        context.failIfEver(() -> {
            int now = ++tick[0];
            f.require(follow.state() == TaskState.RUNNING, "follow ended: " + follow.state() + " " + follow.failureReason());
            // A sprinting player covers 5.6 blocks per second.
            f.place(target, -24.0D + now * 0.28D, 0.0D);
            target.setSprinting(true);
            watch.sample();
            double gap = bot.distanceTo(target);
            if (now > 30) {
                maxGap[0] = Math.max(maxGap[0], gap);
                if (watch.moving) {
                    stats[0]++;
                    if (bot.isSprinting()) {
                        stats[1]++;
                    }
                }
            }
            if (now >= 150) {
                f.require(maxGap[0] < 12.0D, "the follower fell behind a sprinting player: max gap " + maxGap[0]);
                f.require(stats[1] * 10 >= stats[0] * 6, "the follower did not sprint after a sprinting player: " + stats[1] + " of " + stats[0]);
                f.finish();
            }
        });
    }

    /** The player walks: the follower walks with them (it does not sprint unless they get well ahead). */
    static void walksWithWalkingPlayer(GameTestHelper context, boolean baritone, String prefix) {
        FollowFieldFixture f = new FollowFieldFixture(context, 44, 8);
        AIPlayerEntity bot = f.bot(prefix + "Walk", -30, 0, baritone);
        ServerPlayer target = f.target(-25, 0);
        FollowTask follow = f.follow(bot, target.getGameProfile().name(), "gametest_follow_pace_walk");
        Watch watch = new Watch(bot);
        int[] tick = {0};
        double[] maxGap = {0.0D};
        int[] stats = new int[2]; // moving ticks, sprint ticks
        context.failIfEver(() -> {
            int now = ++tick[0];
            f.require(follow.state() == TaskState.RUNNING, "follow ended: " + follow.state() + " " + follow.failureReason());
            // A walking player covers 4.3 blocks per second.
            f.place(target, -25.0D + now * 0.2D, 0.0D);
            watch.sample();
            double gap = bot.distanceTo(target);
            if (now > 30) {
                maxGap[0] = Math.max(maxGap[0], gap);
                if (watch.moving) {
                    stats[0]++;
                    if (bot.isSprinting()) {
                        stats[1]++;
                    }
                }
            }
            if (now >= 190) {
                f.require(stats[0] >= 60, "the follower hardly moved: " + stats[0] + " ticks");
                f.require(stats[1] * 100 <= stats[0] * 25, "the follower sprinted after a walking player: " + stats[1] + " of " + stats[0] + " ticks");
                f.require(maxGap[0] < 12.0D, "the follower fell behind a walking player: max gap " + maxGap[0]);
                f.finish();
            }
        });
    }

    /** A zombie hurts the owner: the follower runs while it moves, even close to the owner. */
    static void sprintsWhenZombieAggroOnOwner(GameTestHelper context, boolean baritone, String prefix) {
        FollowFieldFixture f = new FollowFieldFixture(context, 40, 12);
        AIPlayerEntity bot = f.bot(prefix + "Aggro", -6, 0, baritone);
        ServerPlayer owner = f.owner(bot, -1, 0);
        Zombie zombie = f.zombie(-3.0D, 10.0D, true);
        FollowTask follow = f.follow(bot, "", "gametest_follow_pace_aggro");
        Watch watch = new Watch(bot);
        int[] tick = {0};
        int[] before = new int[2]; // moving, sprint
        int[] after = new int[2];
        context.failIfEver(() -> {
            int now = ++tick[0];
            f.require(follow.state() == TaskState.RUNNING, "follow ended: " + follow.state() + " " + follow.failureReason());
            // The owner walks away along +x at 4.7 blocks per second: a brisk walk (under the sprint speed, so the follower has no
            // other reason to run) that keeps the follower on the move instead of arriving after every short leg.
            f.place(owner, -1.0D + now * 0.235D, 0.0D);
            watch.sample();
            if (now == 40) {
                boolean hit = owner.hurtServer(f.level, f.level.damageSources().mobAttack(zombie), 1.0F);
                f.require(hit, "the zombie's hit on the owner was not applied");
            }
            if (watch.moving) {
                if (now > 15 && now < 40) {
                    before[0]++;
                    if (bot.isSprinting()) {
                        before[1]++;
                    }
                } else if (now > 45 && now < 100) {
                    after[0]++;
                    if (bot.isSprinting()) {
                        after[1]++;
                    }
                }
            }
            if (now >= 110) {
                f.require(after[0] >= 20, "the follower hardly moved after the hit: " + after[0] + " ticks");
                f.require(after[1] * 10 >= after[0] * 7, "the follower did not sprint while a zombie was after the owner: "
                        + after[1] + " of " + after[0] + " moving ticks");
                f.require(before[0] == 0 || before[1] * 100 <= before[0] * 40, "control: the follower sprinted before any aggro: "
                        + before[1] + " of " + before[0] + " moving ticks");
                f.require(!(TaskManager.INSTANCE.getActive(bot).orElse(null) instanceof EvadeTask), "the follower evaded instead of following");
                f.finish();
            }
        });
    }

    /** A calm warden 14 blocks away in the dark: no evade, the follower creeps (shift down whenever it moves). */
    static void sneaksNearCalmWarden(GameTestHelper context, boolean baritone, String prefix) {
        FollowFieldFixture f = new FollowFieldFixture(context, 24, 18);
        AIPlayerEntity bot = f.bot(prefix + "Warden", -6, 0, baritone);
        ServerPlayer target = f.target(1, 0);
        Warden warden = f.warden(-6.0D, 14.0D);
        bot.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 4000, 0, false, false));
        // Perception: the bot knows the warden is there because it has seen it: it looks at it and notices it (the reaction time of the
        // shared formula) before the follow starts. Once noticed it stays known while the line to it is clear, also behind the bot.
        io.github.zoyluo.minecraftai.gametest.PerceptionFixtures.faceToward(bot, warden);
        io.github.zoyluo.minecraftai.gametest.PerceptionFixtures.afterNoticed(context, bot, java.util.List.of(warden), () -> {
        FollowTask follow = f.follow(bot, target.getGameProfile().name(), "gametest_follow_pace_warden");
        Watch watch = new Watch(bot);
        int[] tick = {0};
        int[] stats = new int[3]; // moving ticks, moving without shift, sprint ticks
        io.github.zoyluo.minecraftai.gametest.PerceptionFixtures.everyTick(context, () -> {
            int now = ++tick[0];
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            f.require(!(active instanceof EvadeTask), "the follower evaded a calm warden 14 blocks away");
            f.require(active == follow && follow.state() == TaskState.RUNNING, "follow is not the active task: "
                    + (active == null ? "none" : active.name()) + " " + follow.state());
            f.require(warden.getPose() != net.minecraft.world.entity.Pose.ROARING, "fixture: the warden started roaring");
            bot.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 4000, 0, false, false));
            f.place(target, 1.0D + now * 0.05D, 0.0D);
            watch.sample();
            if (now > 30 && watch.moving) {
                stats[0]++;
                if (!bot.isShiftKeyDown()) {
                    stats[1]++;
                }
                if (bot.isSprinting()) {
                    stats[2]++;
                }
            }
            if (now >= 180) {
                f.require(stats[0] >= 30, "the follower hardly moved: " + stats[0] + " ticks");
                f.require(stats[1] <= 2, "the follower moved without sneaking near a calm warden on " + stats[1] + " of " + stats[0] + " ticks");
                f.require(stats[2] == 0, "the follower sprinted near a calm warden: " + stats[2] + " ticks");
                f.finish();
            }
        });
        });
    }

    /** The TPS guard's degraded state makes the task tick one tick in five; the creeping must not stutter. */
    static void sneakPersistsUnderTpsThrottle(GameTestHelper context, boolean baritone, String prefix) {
        matchesSneakingPlayer(context, baritone, prefix, true);
    }
}

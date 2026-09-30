package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * R4: a following bot only knocks back what gets into its melee reach and keeps following, never leaves the player to fight, still
 * runs from what it cannot fight (low health, a creeper, a warden), and is silent next to a calm warden. The followed player is a
 * survival mock (see {@link FollowFieldFixture}); every test has its own environment.
 */
public final class FollowEscortGameTests {
    private static final String ENV = "minecraftai-gametest:follow_escort_game_tests_";
    private static final double MOVING = 0.03D;

    @GameTest(environment = ENV + "follower_knocks_away_zombie_in_melee_range_and_keeps_following", maxTicks = 220)
    public void followerKnocksAwayZombieInMeleeRangeAndKeepsFollowing(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 40, 10);
        AIPlayerEntity bot = f.bot("FeKnock", -6, 0, false);
        f.give(bot, new ItemStack(Items.WOODEN_SWORD));
        ServerPlayer target = f.target(2, 0);
        Zombie zombie = f.zombie(-4.0D, 0.0D, false);
        Vec3 zombieStart = zombie.position();
        FollowTask follow = f.follow(bot, target.getGameProfile().name(), "gametest_escort_knock");
        int retreatHp = MinecraftAiConfig.get().combat().retreatHp();
        int[] tick = {0};
        boolean[] hurtBelowRetreat = {false};
        double[] zombieMoved = {0.0D};
        context.failIfEver(() -> {
            int now = ++tick[0];
            f.place(target, 2.0D + Math.min(now, 100) * 0.2D, 0.0D); // a brisk walk along +x for 100 ticks, then it stands
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (bot.getHealth() > retreatHp && !hurtBelowRetreat[0]) {
                f.require(active == follow, "the follower left the follow for " + (active == null ? "nothing" : active.name()) + " at tick " + now);
                f.require(follow.state() == TaskState.RUNNING, "the follow was " + follow.state());
            } else {
                hurtBelowRetreat[0] = true;
            }
            zombieMoved[0] = Math.max(zombieMoved[0], zombie.position().distanceTo(zombieStart));
            if (now >= 170) {
                f.require(follow.escortStrikes() >= 1, "the follower never swung at the zombie in its reach");
                f.require(zombie.isRemoved() || zombie.getHealth() < zombie.getMaxHealth(), "the zombie took no damage");
                f.require(zombieMoved[0] > 0.5D, "the zombie was never displaced: " + zombieMoved[0]);
                f.require(bot.distanceTo(target) <= 8.0D, "the follower fell behind: " + bot.distanceTo(target));
                f.finish();
            }
        });
    }

    @GameTest(environment = ENV + "escort_does_not_steer_toward_mob_between_strikes", maxTicks = 200)
    public void escortDoesNotSteerTowardMobBetweenStrikes(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 40, 10);
        AIPlayerEntity bot = f.bot("FeSteer", -14, 0, false);
        f.give(bot, new ItemStack(Items.WOODEN_SWORD));
        ServerPlayer target = f.target(24, 0);
        // Beside the path, inside reach while the bot passes.
        Zombie zombie = f.zombie(-2.0D, 2.0D, true);
        FollowTask follow = f.follow(bot, target.getGameProfile().name(), "gametest_escort_steer");
        int[] tick = {0};
        Vec3[] last = {bot.position()};
        int[] strikesSeen = {0};
        int[] excludeUntil = {0};
        int[] checked = {0};
        context.failIfEver(() -> {
            int now = ++tick[0];
            f.require(follow.state() == TaskState.RUNNING, "follow ended: " + follow.state());
            Vec3 here = bot.position();
            boolean moving = Math.hypot(here.x - last[0].x, here.z - last[0].z) > MOVING;
            last[0] = here;
            if (follow.escortStrikes() > strikesSeen[0]) {
                strikesSeen[0] = follow.escortStrikes();
                excludeUntil[0] = now + 2; // the swing turns the bot toward its target on that tick, the walker re-steers the next
            }
            // Human aim: a swing that is ready but still turning toward its target (a few ticks at 540 degrees per second) also
            // holds the follower's facing on the mob; that is the swing itself, not the walker steering toward the mob.
            long lastAim = follow.escortLastAimTick();
            boolean aiming = lastAim != Long.MIN_VALUE && bot.level().getGameTime() - lastAim <= 2L;
            if (now > 8 && moving && now > excludeUntil[0] && !aiming) {
                checked[0]++;
                float offPath = Math.abs(Mth.wrapDegrees(bot.getYRot() + 90.0F)); // the path runs along +x: yaw -90
                f.require(offPath <= 45.0F, "on tick " + now + " (no swing) the follower faced " + offPath + " degrees off its path");
            }
            if (now >= 150) {
                f.require(strikesSeen[0] >= 1, "fixture: the follower never swung at the zombie beside its path");
                f.require(checked[0] >= 20, "fixture: only " + checked[0] + " moving ticks were checked");
                f.finish();
            }
        });
    }

    @GameTest(environment = ENV + "follower_ignores_hostile_outside_melee_range", maxTicks = 200)
    public void followerIgnoresHostileOutsideMeleeRange(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 40, 12);
        AIPlayerEntity bot = f.bot("FeIgnore", -14, 0, false);
        f.give(bot, new ItemStack(Items.WOODEN_SWORD));
        ServerPlayer target = f.target(24, 0);
        Zombie zombie = f.zombie(-2.0D, 7.0D, true);
        FollowTask follow = f.follow(bot, target.getGameProfile().name(), "gametest_escort_ignore");
        int[] tick = {0};
        double[] maxAbsZ = {0.0D};
        context.failIfEver(() -> {
            int now = ++tick[0];
            f.require(follow.state() == TaskState.RUNNING, "follow ended: " + follow.state());
            f.require(TaskManager.INSTANCE.getActive(bot).orElse(null) == follow, "the follower left the follow");
            maxAbsZ[0] = Math.max(maxAbsZ[0], Math.abs(bot.getZ() - f.z(0.0D)));
            f.require(follow.escortStrikes() == 0, "the follower swung at a hostile out of its reach");
            f.require(zombie.getHealth() >= zombie.getMaxHealth(), "the zombie outside reach was hurt");
            if (now >= 150) {
                f.require(maxAbsZ[0] < 2.0D, "the follower went toward the zombie: " + maxAbsZ[0] + " blocks off its path");
                f.require(bot.getX() > f.x(0.0D) + 5.0D, "the follower did not get past the zombie: x " + (bot.getX() - f.x(0.0D)));
                f.finish();
            }
        });
    }

    @GameTest(environment = ENV + "aggressor_bot_in_melee_range_is_struck_while_following", maxTicks = 220)
    public void aggressorBotInMeleeRangeIsStruckWhileFollowing(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 40, 10);
        AIPlayerEntity bot = f.bot("FeAggressor", -14, 0, false);
        f.give(bot, new ItemStack(Items.WOODEN_SWORD));
        ServerPlayer owner = f.owner(bot, 24, 0);
        ServerPlayer foreign = f.foreign(-2, 2);
        foreign.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        FollowTask follow = f.follow(bot, "", "gametest_escort_aggressor");
        int[] tick = {0};
        context.failIfEver(() -> {
            int now = ++tick[0];
            f.require(follow.state() == TaskState.RUNNING, "follow ended: " + follow.state());
            if (now == 1) {
                // A real hit on the owner: the foreign bot becomes a MARKED aggressor for everyone who sees it.
                boolean hit = owner.hurtServer(f.level, f.level.damageSources().playerAttack(foreign), 1.0F);
                f.require(hit, "the foreign bot's hit on the owner was not applied");
                f.require(HostileBotLedger.isMarked(foreign.getUUID(), f.level.getGameTime()), "fixture: the foreign bot is not marked");
            }
            f.require(TaskManager.INSTANCE.getActive(bot).orElse(null) == follow || bot.getHealth() <= 10.0F,
                    "the follower left the follow for a marked bot");
            if (foreign.getHealth() < foreign.getMaxHealth()) {
                f.require(follow.escortStrikes() >= 1, "the aggressor lost health but the escort counted no swing");
                f.finish();
            }
            f.require(now < 200, "the follower never struck the marked aggressor in its reach (strikes " + follow.escortStrikes() + ")");
        });
    }

    @GameTest(environment = ENV + "suspect_bot_is_not_struck", maxTicks = 200)
    public void suspectBotIsNotStruck(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 40, 10);
        AIPlayerEntity bot = f.bot("FeSuspect", -6, 0, false);
        f.give(bot, new ItemStack(Items.WOODEN_SWORD));
        ServerPlayer owner = f.owner(bot, -1, 0);
        ServerPlayer foreign = f.foreign(4, 2);
        foreign.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        FollowTask follow = f.follow(bot, "", "gametest_escort_suspect");
        int[] tick = {0};
        Vec3[] last = {bot.position()};
        int[] stats = new int[2]; // moving ticks, sprint ticks
        context.failIfEver(() -> {
            int now = ++tick[0];
            f.require(follow.state() == TaskState.RUNNING, "follow ended: " + follow.state());
            f.place(owner, -1.0D + now * 0.235D, 0.0D); // a brisk walk: the follower stays on the move
            long game = f.level.getGameTime();
            if (now % 10 == 1) {
                HostileBotLedger.suspectPlayer(foreign, owner, game, "gametest_charge");
            }
            f.require(HostileBotLedger.isSuspect(foreign.getUUID(), game) && !HostileBotLedger.isMarked(foreign.getUUID(), game),
                    "fixture: the foreign bot is not a mere suspect at tick " + now);
            Vec3 here = bot.position();
            boolean moving = Math.hypot(here.x - last[0].x, here.z - last[0].z) > MOVING;
            last[0] = here;
            if (now > 15 && moving) {
                stats[0]++;
                if (bot.isSprinting()) {
                    stats[1]++;
                }
            }
            f.require(follow.escortStrikes() == 0, "the escort swung at a mere suspect");
            f.require(foreign.getHealth() >= foreign.getMaxHealth(), "the suspect was hurt");
            if (now >= 110) {
                f.require(stats[0] >= 20, "the follower hardly moved: " + stats[0]);
                f.require(stats[1] * 10 >= stats[0] * 6, "the follower did not run from a suspect near its owner: " + stats[1] + " of " + stats[0]);
                f.finish();
            }
        });
    }

    @GameTest(environment = ENV + "no_escort_strike_next_to_calm_warden", maxTicks = 200)
    public void noEscortStrikeNextToCalmWarden(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 40, 16);
        AIPlayerEntity bot = f.bot("FeQuiet", -14, 0, false);
        f.give(bot, new ItemStack(Items.WOODEN_SWORD));
        ServerPlayer target = f.target(24, 0);
        Zombie zombie = f.zombie(-2.0D, 2.0D, true);
        Warden warden = f.warden(-2.0D, -12.0D);
        FollowTask follow = f.follow(bot, target.getGameProfile().name(), "gametest_escort_quiet");
        int[] tick = {0};
        context.failIfEver(() -> {
            int now = ++tick[0];
            f.require(warden.getPose() != Pose.ROARING, "fixture: the warden started roaring");
            f.require(follow.state() == TaskState.RUNNING, "follow ended: " + follow.state());
            f.require(!(TaskManager.INSTANCE.getActive(bot).orElse(null) instanceof EvadeTask), "the follower evaded a calm warden 12 blocks away");
            f.require(follow.escortStrikes() == 0, "the escort swung next to a calm warden");
            f.require(zombie.getHealth() >= zombie.getMaxHealth(), "the zombie was hurt next to a calm warden");
            f.require(bot.getMainHandItem().is(Items.WOODEN_SWORD) || bot.getMainHandItem().isEmpty(),
                    "the escort swapped its weapon next to a calm warden: " + bot.getMainHandItem());
            if (now >= 150) {
                f.require(bot.getX() > f.x(0.0D) + 2.0D, "the follower did not get past the zombie: x " + (bot.getX() - f.x(0.0D)));
                f.finish();
            }
        });
    }

    @GameTest(environment = ENV + "low_health_follower_still_evades", maxTicks = 160)
    public void lowHealthFollowerStillEvades(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 40, 10);
        AIPlayerEntity bot = f.bot("FeLowHp", -6, 0, false);
        f.give(bot, new ItemStack(Items.WOODEN_SWORD));
        ServerPlayer target = f.target(16, 0);
        f.zombie(0.0D, 4.0D, true);
        bot.setHealth(8.0F);
        f.follow(bot, target.getGameProfile().name(), "gametest_escort_lowhp");
        int[] tick = {0};
        context.failIfEver(() -> {
            int now = ++tick[0];
            bot.setHealth(8.0F);
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (active instanceof EvadeTask) {
                f.finish();
            }
            f.require(now < 120, "the wounded follower never evaded, active: " + (active == null ? "none" : active.name()));
        });
    }

    @GameTest(environment = ENV + "creeper_still_triggers_creeper_defense_while_following", maxTicks = 160)
    public void creeperStillTriggersCreeperDefenseWhileFollowing(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 40, 10);
        AIPlayerEntity bot = f.bot("FeCreeper", -6, 0, false);
        ServerPlayer target = f.target(16, 0);
        Creeper creeper = f.creeper(0.0D, 5.0D);
        f.follow(bot, target.getGameProfile().name(), "gametest_escort_creeper");
        int[] tick = {0};
        context.failIfEver(() -> {
            int now = ++tick[0];
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (active instanceof CreeperDefenseTask) {
                f.finish();
            }
            f.require(now < 120, "a creeper 5 blocks away did not start CreeperDefense, active: " + (active == null ? "none" : active.name())
                    + " creeper alive " + creeper.isAlive());
        });
    }

    @GameTest(environment = ENV + "low_health_with_warden_evades_instead_of_shelter", maxTicks = 160)
    public void lowHealthWithWardenEvadesInsteadOfShelter(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 40, 12);
        AIPlayerEntity bot = f.bot("FeWardenLow", -6, 0, false);
        f.give(bot, new ItemStack(Items.COBBLESTONE, 64));
        ServerPlayer target = f.target(16, 0);
        f.warden(-6.0D, 6.0D);
        bot.setHealth(8.0F);
        f.follow(bot, target.getGameProfile().name(), "gametest_escort_warden_low");
        int[] tick = {0};
        context.failIfEver(() -> {
            int now = ++tick[0];
            bot.setHealth(8.0F);
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            f.require(!(active instanceof EmergencyShelterTask), "the bot built a shelter next to a warden");
            if (active instanceof EvadeTask) {
                f.finish();
            }
            f.require(now < 120, "the wounded bot next to a warden never evaded, active: " + (active == null ? "none" : active.name()));
        });
    }

    @GameTest(environment = ENV + "dark_trap_surface_does_not_scan_in_strict", maxTicks = 60)
    public void darkTrapSurfaceDoesNotScanInStrict(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 8, 8);
        // A sealed dark pit: stone all around a two-block-high cell, with a shaft of air above it to open sky.
        BlockPos feet = f.cell(0, 0);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 6; dy++) {
                    boolean shaft = dx == 0 && dz == 0;
                    f.level.setBlock(feet.offset(dx, dy, dz), (shaft && dy <= 5 ? Blocks.AIR : Blocks.STONE).defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        AIPlayerEntity bot = f.bot("FeDarkTrap", 0, 0, false);
        Vec3 before = bot.position();
        int scansBefore = DangerWatcher.INSTANCE.surfaceScanCount();
        f.require(MinecraftAiConfig.get().profile() == io.github.zoyluo.minecraftai.mode.OperatingProfile.STRICT_SURVIVAL,
                "fixture: the GameTest profile is not strict survival");
        boolean escaped = DangerWatcher.INSTANCE.escapeToSurface(bot);
        f.require(!escaped, "the strict-survival bot escaped to the surface by teleport");
        f.require(DangerWatcher.INSTANCE.surfaceScanCount() == scansBefore, "the strict-survival bot scanned the columns above it before asking for the teleport");
        f.require(bot.position().distanceTo(before) < 0.01D, "the bot moved: " + bot.position());
        f.finish();
    }
}

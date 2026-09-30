package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * R6: a bot eats when its food drops to 7, so it never falls to 6, where a player can no longer sprint. It does not wait for a walk
 * to end (a follower on a long route would otherwise run out of sprint first), it does not eat in the middle of a fight or next to a
 * calm warden (it eats as soon as they allow), and the normal food rules hold (no reserve food, no poison).
 */
public final class AutoEatSprintLimitGameTests {
    private static final String ENV = "minecraftai-gametest:auto_eat_sprint_limit_game_tests_";

    @GameTest(environment = ENV + "eats_at_seven_while_following_on_a_long_walk", maxTicks = 320)
    public void eatsAtSevenWhileFollowingOnALongWalk(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 44, 8);
        AIPlayerEntity bot = f.bot("AeWalk", -30, 0, false);
        ServerPlayer target = f.target(30, 0);
        bot.getFoodData().setFoodLevel(7);
        bot.getFoodData().setSaturation(0.0F);
        f.give(bot, new ItemStack(Items.BREAD, 4));
        FollowTask follow = f.follow(bot, target.getGameProfile().name(), "gametest_auto_eat_walk");
        int[] tick = {0};
        boolean[] ateWhileWalking = {false};
        boolean[] started = {false};
        context.failIfEver(() -> {
            int now = ++tick[0];
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (active instanceof EatTask) {
                if (!started[0]) {
                    started[0] = true;
                    f.require(bot.getFoodData().getFoodLevel() >= 7, "the bot's food fell below 7 before it ate: " + bot.getFoodData().getFoodLevel());
                    ateWhileWalking[0] = bot.distanceTo(target) > 10.0D;
                }
                return;
            }
            if (!started[0]) {
                f.require(bot.getFoodData().getFoodLevel() >= 7, "the bot's food fell to " + bot.getFoodData().getFoodLevel() + " without eating");
                f.require(now < 150, "the bot never ate at food 7 while following (active: " + (active == null ? "none" : active.name()) + ")");
                return;
            }
            if (active == follow && bot.getFoodData().getFoodLevel() == 20) {
                f.require(ateWhileWalking[0], "the bot ate only after the walk was over");
                f.require(InventoryAction.countItem(bot, Items.BREAD) < 4, "the food bar is full but no bread was eaten");
                f.require(!TaskManager.INSTANCE.hasPaused(bot), "a stale pause frame remained after eating");
                f.finish();
            }
            f.require(now < 300, "the follow did not resume after eating, active: " + (active == null ? "none" : active.name())
                    + " food " + bot.getFoodData().getFoodLevel());
        });
    }

    @GameTest(environment = ENV + "does_not_eat_mid_fight_then_eats_as_soon_as_it_ends", maxTicks = 320)
    public void doesNotEatMidFightThenEatsAsSoonAsItEnds(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 20, 10);
        AIPlayerEntity bot = f.bot("AeFight", -2, 0, false);
        ServerPlayer target = f.target(1, 0);
        bot.getFoodData().setFoodLevel(7);
        bot.getFoodData().setSaturation(0.0F);
        f.give(bot, new ItemStack(Items.BREAD, 4));
        f.give(bot, new ItemStack(Items.WOODEN_SWORD));
        // A zombie next to the bot that stays and takes a long time to kill: the fight lasts as long as the test wants.
        Zombie zombie = f.zombie(-4.0D, 0.0D, true);
        zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(400.0D);
        zombie.setHealth(400.0F);
        FollowTask follow = f.follow(bot, target.getGameProfile().name(), "gametest_auto_eat_fight");
        int[] tick = {0};
        int[] zombieGoneAt = {-1};
        boolean[] ateSeen = {false};
        context.failIfEver(() -> {
            int now = ++tick[0];
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (zombieGoneAt[0] < 0) {
                f.require(!(active instanceof EatTask), "the bot started eating in the middle of a fight at tick " + now);
                f.require(bot.getFoodData().getFoodLevel() == 7, "the bot's food changed during the fight: " + bot.getFoodData().getFoodLevel());
                if (now == 70) {
                    f.require(zombie.isAlive() && follow.escortStrikes() >= 1, "fixture: the fight was not on (strikes " + follow.escortStrikes() + ")");
                    zombie.discard();
                    zombieGoneAt[0] = now;
                }
                return;
            }
            if (active instanceof EatTask && !ateSeen[0]) {
                ateSeen[0] = true;
                f.require(now - zombieGoneAt[0] <= 60, "the bot ate only " + (now - zombieGoneAt[0]) + " ticks after the fight ended");
            }
            if (active == follow && bot.getFoodData().getFoodLevel() == 20) {
                f.finish();
            }
            f.require(now < 300, "the bot did not eat to full after the fight, active: " + (active == null ? "none" : active.name())
                    + " food " + bot.getFoodData().getFoodLevel());
        });
    }

    @GameTest(environment = ENV + "reserve_and_poison_food_are_not_eaten_at_seven", maxTicks = 260)
    public void reserveAndPoisonFoodAreNotEatenAtSeven(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 20, 10);
        AIPlayerEntity bot = f.bot("AeRules", -2, 0, false);
        ServerPlayer target = f.target(1, 0);
        bot.getFoodData().setFoodLevel(7);
        bot.getFoodData().setSaturation(0.0F);
        f.give(bot, new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 2));
        f.give(bot, new ItemStack(Items.SPIDER_EYE, 3));
        FollowTask follow = f.follow(bot, target.getGameProfile().name(), "gametest_auto_eat_rules");
        int[] tick = {0};
        context.failIfEver(() -> {
            int now = ++tick[0];
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (now <= 80) {
                f.require(!(active instanceof EatTask), "the bot ate reserve or poison food at food 7 at tick " + now);
                f.require(InventoryAction.countItem(bot, Items.ENCHANTED_GOLDEN_APPLE) == 2 && InventoryAction.countItem(bot, Items.SPIDER_EYE) == 3,
                        "reserve or poison food was consumed");
                if (now == 80) {
                    // Control: ordinary food is eaten (and not instead of it the reserve food).
                    f.give(bot, new ItemStack(Items.BREAD, 4));
                }
                return;
            }
            if (active == follow && bot.getFoodData().getFoodLevel() == 20) {
                f.require(InventoryAction.countItem(bot, Items.BREAD) < 4, "no bread was eaten");
                f.require(InventoryAction.countItem(bot, Items.ENCHANTED_GOLDEN_APPLE) == 2 && InventoryAction.countItem(bot, Items.SPIDER_EYE) == 3,
                        "reserve or poison food was eaten after the bread ran out of use");
                f.finish();
            }
            f.require(now < 240, "the bot did not eat the bread, active: " + (active == null ? "none" : active.name())
                    + " food " + bot.getFoodData().getFoodLevel());
        });
    }

    @GameTest(environment = ENV + "defers_eating_next_to_a_calm_warden", maxTicks = 300)
    public void defersEatingNextToACalmWarden(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 20, 18);
        AIPlayerEntity bot = f.bot("AeWarden", -2, 0, false);
        ServerPlayer target = f.target(1, 0);
        bot.getFoodData().setFoodLevel(7);
        bot.getFoodData().setSaturation(0.0F);
        f.give(bot, new ItemStack(Items.BREAD, 4));
        Warden warden = f.warden(-2.0D, 12.0D);
        FollowTask follow = f.follow(bot, target.getGameProfile().name(), "gametest_auto_eat_warden");
        int[] tick = {0};
        int[] wardenGoneAt = {-1};
        context.failIfEver(() -> {
            int now = ++tick[0];
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (wardenGoneAt[0] < 0) {
                f.require(!(active instanceof EatTask), "the bot chewed next to a calm warden at tick " + now);
                f.require(active == follow, "the bot left the follow next to a calm warden 12 blocks away: " + (active == null ? "none" : active.name()));
                if (now == 80) {
                    warden.discard();
                    wardenGoneAt[0] = now;
                }
                return;
            }
            if (active == follow && bot.getFoodData().getFoodLevel() == 20) {
                f.finish();
            }
            f.require(now < 280, "the bot did not eat once the warden was gone, active: " + (active == null ? "none" : active.name())
                    + " food " + bot.getFoodData().getFoodLevel());
        });
    }
}

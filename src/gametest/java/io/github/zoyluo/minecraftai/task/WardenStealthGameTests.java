package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.gametest.PerceptionFixtures;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.minecraftai.brain.ToolDefinition;
import io.github.zoyluo.minecraftai.brain.ToolRegistry;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.List;

/**
 * R3: a warden is never fought. The bot creeps away from a CALM warden (sneaking, silent), sprints while one hunts it, creeps again
 * once it has stopped, still gets down a drop while creeping, on either engine; a commanded attack on a warden is refused; no arrow is
 * ever shot at one. A NoAI warden never changes its synced anger level, so a "hunting" warden is made with {@code Pose.ROARING}.
 * Every arena holds no mob but the ones the test puts in it (a stray aggro'd mob would raise pace pressure), and is wide enough for all five
 * directions of the escape fan (with a single candidate one legacy A* wall-clock timeout on a loaded machine fails the whole admission).
 */
public final class WardenStealthGameTests {
    private static final String ENV = "minecraftai-gametest:warden_stealth_game_tests_";
    /** Horizontal blocks per tick above which the bot counts as moving. */
    private static final double MOVING = 0.03D;
    /** Moving ticks at the start of a flight that are not judged (the route starts, the gait lease is renewed the next tick). */
    private static final int WARM_UP_MOVING_TICKS = 4;

    /** Samples the ticks on which the bot moves over flat ground and what its gait flags said then. */
    private static final class Gaits {
        private Vec3 last;
        int moving;
        int judged;
        int sneaking;
        int sprinting;
        int walking;
        /** When set, only ticks it accepts are judged (the others still count as moving). */
        java.util.function.Predicate<AIPlayerEntity> judgeOnly;

        /** @return true when this tick was a judged (post warm-up, flat, moving) tick */
        boolean sample(AIPlayerEntity bot) {
            Vec3 here = bot.position();
            boolean flat = last != null && bot.onGround() && Math.abs(here.y - last.y) < 0.05D
                    && Math.hypot(here.x - last.x, here.z - last.z) > MOVING;
            last = here;
            if (!flat) {
                return false;
            }
            moving++;
            if (moving <= WARM_UP_MOVING_TICKS) {
                return false;
            }
            if (judgeOnly != null && !judgeOnly.test(bot)) {
                return false;
            }
            judged++;
            if (bot.isShiftKeyDown()) {
                sneaking++;
            }
            if (bot.isSprinting()) {
                sprinting++;
            }
            if (!bot.isShiftKeyDown() && !bot.isSprinting()) {
                walking++;
            }
            return true;
        }

        String summary() {
            return "moving=" + moving + " judged=" + judged + " sneaking=" + sneaking + " sprinting=" + sprinting + " walking=" + walking;
        }
    }

    private static EvadeTask assignEvade(FollowFieldFixture f, AIPlayerEntity bot) {
        DangerWatcher.INSTANCE.scanBot(f.level.getServer(), bot);
        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        f.require(active instanceof EvadeTask, "the warden was routed to " + (active == null ? "idle" : active.name()));
        return (EvadeTask) active;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Calm: sneak away
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(environment = ENV + "calm_warden_evade_sneaks_away", maxTicks = 1700 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void calmWardenEvadeSneaksAway(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 44, 24);
        AIPlayerEntity bot = f.bot("WsCalm", 0, 0, false);
        Warden warden = f.warden(-10.0D, 0.0D);
        PerceptionFixtures.faceToward(bot, warden);
        PerceptionFixtures.afterNoticedFresh(context, bot, List.of(warden), since -> {
        float health = bot.getHealth();
        assignEvade(f, bot);
        Gaits gaits = new Gaits();
        int[] tick = {0};
        PerceptionFixtures.everyTick(context, () -> {
            int now = ++tick[0];
            gaits.sample(bot);
            f.require(warden.getPose() != Pose.ROARING, "fixture: the warden started roaring");
            f.require(bot.getHealth() >= health, "the bot was hurt: " + bot.getHealth());
            f.require(gaits.sprinting == 0, "the bot sprinted away from a calm warden (" + gaits.summary() + ") at tick " + now);
            if (bot.distanceTo(warden) >= 20.0D && gaits.judged >= 20) {
                f.require(gaits.sneaking >= gaits.judged - 1, "the bot did not sneak on every flat moving tick: " + gaits.summary());
                f.finish();
            }
            f.require(now < 1650, "the bot never got 20 blocks from the calm warden in " + now + " ticks: "
                    + bot.distanceTo(warden) + " " + gaits.summary());
        });
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Hunting: sprint away
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(environment = ENV + "hunting_warden_evade_sprints", maxTicks = 500 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void huntingWardenEvadeSprints(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 44, 24);
        AIPlayerEntity bot = f.bot("WsHunt", 0, 0, false);
        Warden warden = f.warden(-10.0D, 0.0D);
        warden.setPose(Pose.ROARING);
        PerceptionFixtures.faceToward(bot, warden);
        PerceptionFixtures.afterNoticedFresh(context, bot, List.of(warden), since -> {
        assignEvade(f, bot);
        Gaits gaits = new Gaits();
        int[] tick = {0};
        PerceptionFixtures.everyTick(context, () -> {
            int now = ++tick[0];
            gaits.sample(bot);
            warden.setPose(Pose.ROARING); // a NoAI warden keeps the pose it is given; make sure it stays "hunting"
            f.require(gaits.sneaking == 0, "the bot crept away from a hunting warden (" + gaits.summary() + ") at tick " + now);
            if (bot.distanceTo(warden) >= 20.0D && gaits.judged >= 20) {
                f.require(gaits.sprinting >= gaits.judged - 2, "the bot did not sprint on its moving ticks: " + gaits.summary());
                f.finish();
            }
            f.require(now < 450, "the bot never got 20 blocks from the hunting warden: " + bot.distanceTo(warden) + " " + gaits.summary());
        });
        });
    }

    @GameTest(environment = ENV + "calm_warden_turning_angry_mid_evade_switches_to_sprint", maxTicks = 900 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void calmWardenTurningAngryMidEvadeSwitchesToSprint(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 44, 24);
        AIPlayerEntity bot = f.bot("WsTurn", 0, 0, false);
        Warden warden = f.warden(-10.0D, 0.0D);
        PerceptionFixtures.faceToward(bot, warden);
        PerceptionFixtures.afterNoticedFresh(context, bot, List.of(warden), since -> {
        assignEvade(f, bot);
        int turnTick = 60;
        int[] tick = {0};
        int[] sprintFrom = {-1};
        int[] sneakAfter = {0};
        Gaits after = new Gaits();
        Gaits before = new Gaits();
        PerceptionFixtures.everyTick(context, () -> {
            int now = ++tick[0];
            if (now < turnTick) {
                before.sample(bot);
            } else if (now == turnTick) {
                f.require(before.judged >= 20 && before.sneaking >= before.judged - 1,
                        "fixture: the bot was not creeping before the warden turned: " + before.summary());
                f.require(TaskManager.INSTANCE.getActive(bot).orElse(null) instanceof EvadeTask, "the evade ended before the warden turned");
                warden.setPose(Pose.ROARING);
            } else {
                warden.setPose(Pose.ROARING);
                if (sprintFrom[0] < 0 && bot.isSprinting()) {
                    sprintFrom[0] = now;
                }
                if (now > turnTick + 2 && after.sample(bot) && bot.isShiftKeyDown()) {
                    sneakAfter[0]++;
                }
                if (now > turnTick + 40) {
                    f.require(sprintFrom[0] > 0 && sprintFrom[0] <= turnTick + 2,
                            "the bot did not sprint within 2 ticks of the warden turning hunting: first sprint tick "
                                    + (sprintFrom[0] < 0 ? "never" : String.valueOf(sprintFrom[0] - turnTick)));
                    f.require(after.judged >= 10 && sneakAfter[0] == 0 && after.sprinting >= after.judged - 1,
                            "the bot did not keep sprinting after the warden turned: " + after.summary() + " sneakAfter=" + sneakAfter[0]);
                    f.finish();
                }
            }
            f.require(now < 800, "timeout");
        });
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Creeping down a drop
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(environment = ENV + "sneaking_evade_still_descends_a_drop", maxTicks = 1700 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void sneakingEvadeStillDescendsADrop(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 44, 24);
        // The way east drops two blocks from x = 6 on: a sneaking bot must still get down it (the enforcer lifts the sneak on the drop).
        for (int dx = 6; dx <= 44; dx++) {
            for (int dz = -24; dz <= 24; dz++) {
                f.arena.set(dx, -1, dz, Blocks.AIR);
                f.arena.set(dx, -2, dz, Blocks.AIR);
            }
        }
        AIPlayerEntity bot = f.bot("WsDrop", 0, 0, false);
        Warden warden = f.warden(-10.0D, 0.0D);
        PerceptionFixtures.faceToward(bot, warden);
        PerceptionFixtures.afterNoticedFresh(context, bot, List.of(warden), since -> {
        double startY = bot.getY();
        double startX = bot.getX();
        EvadeTask evade = assignEvade(f, bot);
        Gaits gaits = new Gaits();
        // The sneak is lifted on the drop and the descent (an edge is not walked off while sneaking): judge the flat ground before the
        // edge zone (the drop is 6 blocks east) and the lower level after it.
        gaits.judgeOnly = b -> b.getX() < startX + 3.0D || b.getY() <= startY - 1.9D && b.getX() > startX + 8.0D;
        int[] tick = {0};
        PerceptionFixtures.everyTick(context, () -> {
            int now = ++tick[0];
            gaits.sample(bot);
            f.require(evade.state() != TaskState.FAILED, "the evade failed: " + evade.failureReason());
            f.require(gaits.sprinting == 0, "the bot sprinted away from a calm warden (" + gaits.summary() + ")");
            if (bot.getY() <= startY - 1.9D && bot.distanceTo(warden) >= 20.0D) {
                f.require(gaits.judged >= 10 && gaits.sneaking >= gaits.judged - 1, "the bot did not creep on flat ground: " + gaits.summary());
                f.finish();
            }
            f.require(now < 1650, "the creeping bot never got down the drop and 20 blocks away: y=" + bot.getY() + " start " + startY
                    + " dist " + bot.distanceTo(warden) + " evade " + evade.state() + " " + gaits.summary());
        });
        });
    }

    @GameTest(environment = ENV + "sneaking_evade_on_baritone", maxTicks = 1700 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void sneakingEvadeOnBaritone(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 44, 24);
        AIPlayerEntity bot = f.bot("WsBari", 0, 0, true);
        Warden warden = f.warden(-10.0D, 0.0D);
        PerceptionFixtures.faceToward(bot, warden);
        PerceptionFixtures.afterNoticedFresh(context, bot, List.of(warden), since -> {
        float health = bot.getHealth();
        EvadeTask evade = assignEvade(f, bot);
        f.require(bot.getActionPack().hasBaritoneRoute(), "the flight did not start a Baritone route");
        Gaits gaits = new Gaits();
        int[] tick = {0};
        PerceptionFixtures.everyTick(context, () -> {
            int now = ++tick[0];
            gaits.sample(bot);
            var outcome = bot.getActionPack().lastRouteOutcome();
            f.require(bot.getHealth() >= health, "the bot was hurt: " + bot.getHealth());
            f.require(gaits.sprinting == 0, "the bot sprinted away from a calm warden (" + gaits.summary() + ") at tick " + now);
            f.require(evade.state() != TaskState.FAILED, "the evade failed: " + evade.failureReason());
            if (bot.distanceTo(warden) >= 20.0D && gaits.judged >= 20) {
                f.require(gaits.sneaking >= gaits.judged - 1, "the bot did not sneak on every flat moving tick: " + gaits.summary());
                f.require(evade.usesRunAway() && bot.getActionPack().hasBaritoneRoute(),
                        "the flight was not Baritone's run-away goal: last route " + outcome);
                f.finish();
            }
            f.require(now < 1650, "the bot never got 20 blocks from the calm warden: " + bot.distanceTo(warden) + " " + gaits.summary()
                    + " last route " + outcome);
        });
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The owner's side
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(environment = ENV + "evade_bias_toward_owner", maxTicks = 100)
    public void evadeBiasTowardOwner(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 30, 30);
        AIPlayerEntity bot = f.bot("WsBias", 0, 0, false);
        Zombie zombie = f.zombie(0.0D, -8.0D, true); // north of the bot: the way away is south, both sides are open
        boolean[] done = {false};
        context.failIfEver(() -> {
            if (done[0]) {
                return;
            }
            done[0] = true;
            // Both rankings are taken in this one tick, so the bot has not moved in between (the danger watcher also sees the zombie).
            BlockPos here = bot.blockPosition();
            List<BlockPos> withoutOwner = EvadeTask.chooseGoals(bot, zombie, zombie.blockPosition(), 12);
            f.require(!withoutOwner.isEmpty(), "no escape goal without an owner");
            BlockPos straight = withoutOwner.get(0);
            f.require(straight.getX() == here.getX() && straight.getZ() > here.getZ(),
                    "fixture: without an owner the first choice was not straight away (south): " + straight + " from " + here);
            ServerPlayer owner = f.owner(bot, 10, 0); // east of the bot
            f.require(owner.level() == bot.level(), "fixture: the owner is elsewhere");
            List<BlockPos> withOwner = EvadeTask.chooseGoals(bot, zombie, zombie.blockPosition(), 12);
            f.require(!withOwner.isEmpty(), "no escape goal with an owner");
            BlockPos chosen = withOwner.get(0);
            f.require(chosen.getX() > here.getX() && chosen.getZ() > here.getZ(),
                    "the first escape goal did not lean toward the owner (east) while still leading away (south): " + chosen
                            + " from " + here + " all " + withOwner);
            f.require(withOwner.stream().allMatch(goal -> goal.getZ() > here.getZ() || goal.getX() != here.getX()),
                    "an escape goal does not move the bot: " + withOwner);
            f.finish();
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Never fight a warden
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(environment = ENV + "attack_warden_tool_is_refused", maxTicks = 40)
    public void attackWardenToolIsRefused(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 12, 12);
        AIPlayerEntity bot = f.bot("WsRefuse", 0, 0, false);
        ToolRegistry registry = new ToolRegistry();
        ToolDefinition attack = registry.get("attack").orElse(null);
        ToolDefinition assignTask = registry.get("assign_task").orElse(null);
        f.require(attack != null && assignTask != null, "the attack or assign_task tool is not registered");

        JsonObject args = JsonParser.parseString("{\"entity_type\":\"minecraft:warden\",\"count\":1}").getAsJsonObject();
        ToolDefinition.ToolResult result = attack.handler().invoke(bot, args);
        f.require(result != null && !result.ok() && WardenRefusal.MESSAGE.equals(result.message()),
                "the attack tool did not refuse a warden: " + (result == null ? "null" : result.ok() + " " + result.message()));
        f.require(!(TaskManager.INSTANCE.getActive(bot).orElse(null) instanceof CombatTask),
                "a combat task was assigned against a warden by the attack tool");

        JsonObject task = JsonParser.parseString("{\"task_type\":\"attack\",\"params\":{\"entity_type\":\"minecraft:warden\"}}").getAsJsonObject();
        ToolDefinition.ToolResult viaTask = assignTask.handler().invoke(bot, task);
        f.require(viaTask != null && !viaTask.ok() && WardenRefusal.MESSAGE.equals(viaTask.message()),
                "assign_task attack did not refuse a warden: " + (viaTask == null ? "null" : viaTask.ok() + " " + viaTask.message()));
        f.require(!(TaskManager.INSTANCE.getActive(bot).orElse(null) instanceof CombatTask),
                "a combat task was assigned against a warden by assign_task");

        // The refusal is about wardens only: any other mob is still a valid commanded target.
        JsonObject zombieArgs = JsonParser.parseString("{\"entity_type\":\"minecraft:zombie\",\"count\":1}").getAsJsonObject();
        ToolDefinition.ToolResult ordinary = attack.handler().invoke(bot, zombieArgs);
        f.require(ordinary != null && ordinary.ok(), "the attack tool refused an ordinary mob: " + (ordinary == null ? "null" : ordinary.message()));
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
        f.finish();
    }

    @GameTest(environment = ENV + "no_arrow_is_shot_at_a_warden", maxTicks = 260)
    public void noArrowIsShotAtAWarden(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 30, 12);
        AIPlayerEntity bot = f.bot("WsBow", 0, 0, false);
        f.give(bot, new ItemStack(Items.BOW));
        f.give(bot, new ItemStack(Items.ARROW, 32));
        Warden warden = f.warden(12.0D, 0.0D);
        float health = warden.getHealth();
        BlockPos anchor = bot.blockPosition().immutable();
        // The combat task is handed the warden directly (the commanded entry points refuse it): a bow must still stay in the quiver.
        TaskManager.INSTANCE.assign(bot, CombatTask.defensive(warden, 6.0F, anchor),
                io.github.zoyluo.minecraftai.runtime.TaskOrigin.of(io.github.zoyluo.minecraftai.runtime.TaskOrigin.Kind.VERIFY, "gametest_no_arrow_warden"));
        int[] tick = {0};
        context.failIfEver(() -> {
            int now = ++tick[0];
            var arrows = f.level.getEntitiesOfClass(AbstractArrow.class, bot.getBoundingBox().inflate(60.0D),
                    arrow -> arrow.getOwner() == bot);
            f.require(arrows.isEmpty(), "the bot shot an arrow at a warden at tick " + now);
            f.require(warden.getHealth() == health, "the warden was hurt");
            if (now >= 200) {
                f.require(bot.getInventory().countItem(Items.ARROW) == 32, "arrows were spent: " + bot.getInventory().countItem(Items.ARROW));
                f.finish();
            }
        });
    }
}

package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import io.github.zoyluo.minecraftai.task.FollowSwimGameTests.Pond;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.zoyluo.minecraftai.task.FollowSwimGameTests.LANE_Z;
import static io.github.zoyluo.minecraftai.task.FollowSwimGameTests.Pond;
import static io.github.zoyluo.minecraftai.task.FollowSwimGameTests.buildPond;
import static io.github.zoyluo.minecraftai.task.FollowSwimGameTests.finish;
import static io.github.zoyluo.minecraftai.task.FollowSwimGameTests.holdStill;
import static io.github.zoyluo.minecraftai.task.FollowSwimGameTests.noBoats;
import static io.github.zoyluo.minecraftai.task.FollowSwimGameTests.require;
import static io.github.zoyluo.minecraftai.task.FollowSwimGameTests.requireRunning;
import static io.github.zoyluo.minecraftai.task.FollowSwimGameTests.spawnBot;

/**
 * Goal snap over water for a player who is NOT swimming: the stand-off cell 3 blocks from the
 * player toward the bot lies over the pond, so the pathfinder's goal resolution has to pick dry
 * ground (never the pond floor) and the bot has to walk around the water rather than through it.
 *
 * <p>Needs the follow look fix (the bot must steer by its path, not re-face the player every tick,
 * which made every leg a straight-line walk straight into the pond); it lives in its own class so
 * it can be merged after that fix.</p>
 */
public final class FollowAroundPondGameTests {
    /**
     * A land target across a pond: the goal snap must resolve the stand-off cell over the water to
     * dry ground (never the pond floor) and the bot must walk around the pond, never swim.
     */
    @GameTest(environment = "minecraftai-gametest:follow_around_pond_game_tests_land_target_across_pond_is_followed_around_the_water_not_through_it", maxTicks = 900)
    public void landTargetAcrossPondIsFollowedAroundTheWaterNotThroughIt(GameTestHelper context) {
        Pond pond = buildPond(context, 8, 19, 3, 26);
        ServerLevel world = context.getLevel();
        AIPlayerEntity bot = spawnBot(world, "AroundBot", pond.feet().offset(3, 0, LANE_Z));
        AIPlayerEntity target = spawnBot(world, "AroundTgt", pond.feet().offset(22, 0, LANE_Z));
        holdStill(target);
        FollowTask follow = new FollowTask("AroundTgt");
        TaskManager.INSTANCE.assign(bot, follow,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_around_pond"));
        AtomicInteger tick = new AtomicInteger();
        context.failIfEver(() -> {
            int now = tick.incrementAndGet();
            requireRunning(context, follow, bot);
            require(context, !bot.isInWater() && bot.getY() >= pond.feet().getY() - 0.5D,
                    "the bot went into (or under) the pond for a target on land at tick " + now
                            + " pos=" + bot.position());
            require(context, bot.getVehicle() == null && noBoats(world, pond), "follow used a boat for a land target");
            if (bot.getX() >= pond.feet().getX() + 19.5D && bot.distanceTo(target) <= 4.5D && follow.isWaiting()) {
                finish(context, pond, bot, target);
            }
        });
    }

}

package io.github.zoyluo.minecraftai.task;

import java.util.List;
import io.github.zoyluo.minecraftai.gametest.PerceptionFixtures;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * R4: a retreating bot uses the follower's escort rule. While it moves away it only knocks back what is in its melee reach (a swing on
 * a ready tick, never turning the flight toward the mob) and leaves everything out of reach alone.
 */
public final class EvadeEscortGameTests {
    private static final String ENV = "minecraftai-gametest:evade_escort_game_tests_";

    @GameTest(environment = ENV + "retreat_swings_only_at_what_is_in_melee_range", maxTicks = 260 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void retreatSwingsOnlyAtWhatIsInMeleeRange(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 40, 12);
        AIPlayerEntity bot = f.bot("EeEscort", -6, 0, false);
        f.give(bot, new ItemStack(Items.WOODEN_SWORD));
        // The threat the bot flees, behind it (west); it never moves and is never in reach.
        Zombie source = f.zombie(-14.0D, 0.0D, true);
        // Just off the eastbound route, inside reach with a deterministic forward hit window;
        // and one far off to the side, which must be left alone.
        Zombie beside = f.zombie(-2.0D, 0.4D, true);
        Zombie far = f.zombie(-2.0D, 9.0D, true);
        PerceptionFixtures.faceToward(bot, beside);
        PerceptionFixtures.afterNoticedFresh(context, bot, List.of(beside), since -> {
        EvadeTask evade = new EvadeTask(new Threat(Threat.Type.HOSTILE, Threat.Severity.HIGH, source, source.blockPosition()));
        TaskManager.INSTANCE.assign(bot, evade, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_evade_escort"));
        int[] tick = {0};
        int[] strikesSeen = {0};
        double startX = bot.getX();
        PerceptionFixtures.everyTick(context, () -> {
            int now = ++tick[0];
            f.require(evade.state() != TaskState.FAILED, "the evade failed: " + evade.failureReason());
            // The physical displacement below is the route-steering assertion. Yaw is transient
            // input state owned by Baritone and human-speed combat aim during the same movement tick.
            if (evade.escortStrikes() > strikesSeen[0]) {
                strikesSeen[0] = evade.escortStrikes();
            }
            f.require(far.getHealth() >= far.getMaxHealth(), "the retreating bot hurt a mob far out of its reach");
            f.require(source.getHealth() >= source.getMaxHealth(), "the retreating bot hurt the mob it flees");
            // The flight ends by itself (12 blocks from the source); the danger watcher then deals with the idle zombies as it always does.
            if (evade.state() == TaskState.COMPLETED || now >= 150) {
                f.require(evade.state() == TaskState.COMPLETED, "the evade did not complete: " + evade.state());
                f.require(bot.getX() > startX + 8.0D, "the retreating bot did not keep moving away: x " + (bot.getX() - startX));
                f.require(evade.escortStrikes() >= 1, "the retreating bot never swung at the zombie in its reach");
                f.require(beside.isRemoved() || beside.getHealth() < beside.getMaxHealth(), "the zombie in reach took no damage");
                f.finish();
            }
        });
        });
    }

}

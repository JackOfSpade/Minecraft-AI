package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * R4: a retreating bot uses the follower's escort rule. While it moves away it only knocks back what is in its melee reach (a swing on
 * a ready tick, never turning the flight toward the mob) and leaves everything out of reach alone.
 */
public final class EvadeEscortGameTests {
    private static final String ENV = "minecraftai-gametest:evade_escort_game_tests_";
    private static final double MOVING = 0.03D;

    @GameTest(environment = ENV + "retreat_swings_only_at_what_is_in_melee_range", maxTicks = 260)
    public void retreatSwingsOnlyAtWhatIsInMeleeRange(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 40, 12);
        AIPlayerEntity bot = f.bot("EeEscort", -6, 0, false);
        f.give(bot, new ItemStack(Items.WOODEN_SWORD));
        // The threat the bot flees, behind it (west); it never moves and is never in reach.
        Zombie source = f.zombie(-14.0D, 0.0D, true);
        // Beside the way east, inside reach while the bot passes; and one far off to the side, which must be left alone.
        Zombie beside = f.zombie(-2.0D, 2.0D, true);
        Zombie far = f.zombie(-2.0D, 9.0D, true);
        EvadeTask evade = new EvadeTask(new Threat(Threat.Type.HOSTILE, Threat.Severity.HIGH, source, source.blockPosition()));
        TaskManager.INSTANCE.assign(bot, evade, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_evade_escort"));
        int[] tick = {0};
        Vec3[] last = {bot.position()};
        int[] strikesSeen = {0};
        int[] excludeUntil = {0};
        int[] checked = {0};
        double startX = bot.getX();
        context.failIfEver(() -> {
            int now = ++tick[0];
            f.require(evade.state() != TaskState.FAILED, "the evade failed: " + evade.failureReason());
            Vec3 here = bot.position();
            boolean moving = Math.hypot(here.x - last[0].x, here.z - last[0].z) > MOVING;
            last[0] = here;
            if (evade.escortStrikes() > strikesSeen[0]) {
                strikesSeen[0] = evade.escortStrikes();
                excludeUntil[0] = now + 2; // the swing turns the bot toward its target on that tick, the walker re-steers the next
            }
            if (evade.state() == TaskState.RUNNING && now > 8 && moving && now > excludeUntil[0]) {
                checked[0]++;
                float offPath = Math.abs(Mth.wrapDegrees(bot.getYRot() + 90.0F)); // the flight runs along +x: yaw -90
                f.require(offPath <= 60.0F, "on tick " + now + " (no swing) the retreating bot faced " + offPath + " degrees off its way");
            }
            f.require(far.getHealth() >= far.getMaxHealth(), "the retreating bot hurt a mob far out of its reach");
            f.require(source.getHealth() >= source.getMaxHealth(), "the retreating bot hurt the mob it flees");
            // The flight ends by itself (12 blocks from the source); the danger watcher then deals with the idle zombies as it always does.
            if (evade.state() == TaskState.COMPLETED || now >= 150) {
                f.require(evade.state() == TaskState.COMPLETED, "the evade did not complete: " + evade.state());
                f.require(bot.getX() > startX + 8.0D, "the retreating bot did not keep moving away: x " + (bot.getX() - startX));
                f.require(evade.escortStrikes() >= 1, "the retreating bot never swung at the zombie in its reach");
                f.require(beside.isRemoved() || beside.getHealth() < beside.getMaxHealth(), "the zombie in reach took no damage");
                f.require(checked[0] >= 15, "fixture: only " + checked[0] + " moving ticks were checked");
                f.finish();
            }
        });
    }

    @GameTest(environment = ENV + "retreat_next_to_a_calm_warden_stays_silent", maxTicks = 200)
    public void retreatNextToACalmWardenStaysSilent(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 40, 16);
        AIPlayerEntity bot = f.bot("EeQuiet", -6, 0, false);
        f.give(bot, new ItemStack(Items.WOODEN_SWORD));
        Zombie source = f.zombie(-14.0D, 0.0D, true);
        Zombie beside = f.zombie(-2.0D, 2.0D, true);
        f.warden(-2.0D, -12.0D);
        EvadeTask evade = new EvadeTask(new Threat(Threat.Type.HOSTILE, Threat.Severity.HIGH, source, source.blockPosition()));
        TaskManager.INSTANCE.assign(bot, evade, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_evade_quiet"));
        int[] tick = {0};
        context.failIfEver(() -> {
            int now = ++tick[0];
            f.require(evade.escortStrikes() == 0, "the retreating bot swung next to a calm warden");
            f.require(beside.getHealth() >= beside.getMaxHealth(), "a zombie was hurt next to a calm warden");
            if (now >= 120) {
                f.finish();
            }
        });
    }
}

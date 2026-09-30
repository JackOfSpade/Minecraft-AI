package io.github.zoyluo.minecraftai.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The numbers behind human aim ({@link HumanAim.Core}), its config default and the source-level wiring of the aim. */
class HumanAimCoreTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    @Test
    void settleJitterStartsAtThreeDegreesAndFadesToItsFloor() {
        assertEquals(0.3D + 2.5D, HumanAim.Core.settleSigma(0.0D), 1.0E-9D);
        assertEquals(0.3D + 2.5D * Math.exp(-1.0D), HumanAim.Core.settleSigma(0.25D), 1.0E-9D);
        assertEquals(0.3D, HumanAim.Core.settleSigma(60.0D), 1.0E-6D);
        double previous = Double.MAX_VALUE;
        for (double t = 0.0D; t < 3.0D; t += 0.05D) {
            double sigma = HumanAim.Core.settleSigma(t);
            assertTrue(sigma < previous, "the jitter must shrink as the aim settles");
            previous = sigma;
        }
    }

    @Test
    void aTurnNeverMovesMoreThanItsBudgetAndTakesTheShortWayRound() {
        double[] turned = HumanAim.Core.turn(170.0D, 0.0D, -170.0D, 0.0D, 27.0D);
        assertEquals(190.0D, turned[0], 1.0E-9D, "170 -> -170 is a 20 degree turn through 180, not 340 back");
        assertEquals(0.0D, turned[2], "reached within the budget");

        double[] flick = HumanAim.Core.turn(0.0D, 0.0D, 180.0D, 0.0D, 27.0D);
        assertEquals(27.0D, Math.abs(flick[0]), 1.0E-9D);
        assertEquals(1.0D, flick[2], "a spent budget is a flick");

        double[] diagonal = HumanAim.Core.turn(0.0D, 0.0D, 90.0D, 60.0D, 27.0D);
        assertEquals(27.0D, Math.hypot(diagonal[0], diagonal[1]), 1.0E-9D, "yaw and pitch share one budget");
        assertEquals(90.0D / 60.0D, diagonal[0] / diagonal[1], 1.0E-9D, "the turn heads straight for the target");
    }

    @Test
    void aSeriesOfTurnsReachesTheTargetInTheNumberOfTicksTheRateGives() {
        double yaw = 0.0D;
        double pitch = 0.0D;
        int ticks = 0;
        while (Math.hypot(HumanAim.Core.wrap(180.0D - yaw), 0.0D - pitch) > 1.0E-9D) {
            double[] turned = HumanAim.Core.turn(yaw, pitch, 180.0D, 0.0D, 540.0D / 20.0D);
            assertTrue(Math.abs(turned[0] - yaw) <= 27.0D + 1.0E-9D);
            yaw = turned[0];
            pitch = turned[1];
            ticks++;
            assertTrue(ticks < 100);
        }
        assertEquals(7, ticks, "a half turn takes 180 / 27 = 6.7 ticks at 540 degrees per second");
    }

    @Test
    void theAngleBetweenTwoLooksIsTheTrueAngle() {
        assertEquals(0.0D, HumanAim.Core.angleBetween(30.0D, 10.0D, 30.0D, 10.0D), 1.0E-6D);
        assertEquals(90.0D, HumanAim.Core.angleBetween(0.0D, 0.0D, 90.0D, 0.0D), 1.0E-6D);
        assertEquals(180.0D, HumanAim.Core.angleBetween(0.0D, 0.0D, 180.0D, 0.0D), 1.0E-6D);
        // Near the pole a yaw difference is a small angle: the tolerance measures directions, not raw degrees.
        assertTrue(HumanAim.Core.angleBetween(0.0D, 89.0D, 90.0D, 89.0D) < 2.0D);
        assertEquals(10.0D, HumanAim.Core.angleBetween(0.0D, 0.0D, 0.0D, 10.0D), 1.0E-6D);
    }

    @Test
    void theShippedAimIsAHumanFlickAndABadValueFallsBack() {
        assertEquals(540.0D, MinecraftAiConfig.Aim.defaults().maxTurnDegPerSec());
        assertEquals(540.0D, MinecraftAiConfig.CombatBehaviour.defaults().aimOrDefaults().maxTurnDegPerSec());
        assertEquals(540.0D, MinecraftAiConfig.Behaviour.defaults().combatOrDefaults().aimOrDefaults().maxTurnDegPerSec());
        assertEquals(540.0D, new MinecraftAiConfig.CombatBehaviour(null).aimOrDefaults().maxTurnDegPerSec());
    }

    @Test
    void everyCombatLookAndTheStrikeGoThroughTheHumanAim() throws IOException {
        String core = read("task/CombatCore.java");
        assertTrue(core.contains("HumanAim.lookToward(bot, targetCenter)"), "CombatCore.lookAt turns at human speed");
        assertTrue(core.contains("HumanAim.turnToward(bot, yaw, pitch)") && core.contains("HumanAim.isOnTarget(bot, yaw, pitch)"),
                "the shot aim turns at human speed and reports whether it is on target");
        String interact = read("action/InteractAction.java");
        int attack = interact.indexOf("public static ActionResult attackEntity(");
        String body = interact.substring(attack, interact.indexOf("public static ActionResult useItemOnEntity("));
        assertTrue(body.indexOf("HumanAim.isUnderCrosshair(player, target)") > body.indexOf("HumanAim.lookToward(player, targetCenter)")
                        && body.indexOf("player.attack(target)") > body.indexOf("HumanAim.isUnderCrosshair(player, target)"),
                "a strike turns at human speed and lands only on what is under the crosshair");
        String aim = read("action/HumanAim.java");
        assertTrue(aim.contains("ProjectileUtil.getHitEntitiesAlong(") && aim.contains("bot.entityAttackRange()"),
                "the crosshair is vanilla's own pick within the weapon's vanilla attack range");
        String ranged = read("task/CombatTask.java");
        assertTrue(ranged.contains("aimed && RangedWeapon.shoot(bot)"), "a weapon fires only with the aim on target");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}

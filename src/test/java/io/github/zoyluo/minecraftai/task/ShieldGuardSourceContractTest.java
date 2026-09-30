package io.github.zoyluo.minecraftai.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the wiring of the one reactive shield owner ({@link ShieldGuard}) to its intended shape: where it runs, what yields to it,
 * and the vanilla use path it goes through. The behaviour itself is proven by ShieldBlockingGameTests and the pure rules by
 * ShieldRulesTest and ShieldBlockabilityTest.
 */
class ShieldGuardSourceContractTest {
    private static String read(String relative) throws IOException {
        return Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/" + relative));
    }

    @Test
    void theSafetyNetsRescueStandsTheGuardDown() throws IOException {
        String coordinator = read("task/BotTickCoordinator.java");
        int net = coordinator.indexOf("NavSafetyNet.INSTANCE.tickBot(server, bot)");
        int stand = coordinator.indexOf("ShieldGuard.INSTANCE.standDown(bot, \"safety_net\");", net);
        assertTrue(net > 0 && stand > net && stand < coordinator.indexOf("continue;", net),
                "a reactive shield comes down when the safety net takes the bot over");
    }

    @Test
    void theGuardRunsOncePerBotTickBetweenTheDangerScanAndTheTasks() throws IOException {
        String coordinator = read("task/BotTickCoordinator.java");
        int scan = coordinator.indexOf("DangerWatcher.INSTANCE.scanBot(server, bot)");
        int guard = coordinator.indexOf("ShieldGuard.INSTANCE.tickBot(server, bot);");
        int goals = coordinator.indexOf("GoalExecutor.INSTANCE.tickBot(server, bot)");
        assertTrue(scan > 0 && guard > scan && goals > guard,
                "the guard decides after the danger scan and before the tasks of the tick read its state");
        assertTrue(coordinator.indexOf("ShieldGuard.INSTANCE.tickBot(", guard + 1) < 0, "exactly one call site");
    }

    @Test
    void theUseGoesThroughTheVanillaPathMainHandFirstThenOffhand() throws IOException {
        String guard = read("task/ShieldGuard.java");
        int use = guard.indexOf("private static Raise tryVanillaUse(");
        String body = guard.substring(use, guard.indexOf("/** Lowers the shield if the bot is using it", use));
        assertTrue(body.contains("for (InteractionHand hand : InteractionHand.values())")
                        && body.contains("InteractAction.useItemInAir(bot, hand)"),
                "MAIN_HAND then OFF_HAND, exactly as the client tries them");
        int raise = guard.indexOf("public static Raise raise(");
        String raiseBody = guard.substring(raise, guard.indexOf("private static Raise tryVanillaUse(", raise));
        assertTrue(raiseBody.indexOf("isOnCooldown(shieldStack(bot))") > 0
                        && raiseBody.indexOf("isOnCooldown(shieldStack(bot))") < raiseBody.indexOf("tryVanillaUse(bot, owner)"),
                "a shield on its item cooldown is never tried (no re-raise spam)");
        assertTrue(raiseBody.contains("mainHandConsumesUse(main)") && raiseBody.contains("switchMainHandAway(bot)"),
                "a main-hand item that takes the use forces a hotbar change first");
    }

    @Test
    void whileTheGuardHoldsTheShieldTasksCannotMineAttackOrDropIt() throws IOException {
        String pack = read("action/ActionPack.java");
        int stopAll = pack.indexOf("public void stopAll(");
        assertTrue(pack.substring(stopAll, pack.indexOf("public boolean hasActiveActions()", stopAll))
                        .contains("ShieldGuard.holdsShield(player)"),
                "stopAll spares the shield the reactive owner holds");
        int breakTick = pack.indexOf("public ActionResult tickBreak(");
        assertTrue(pack.substring(breakTick, pack.indexOf("controller.tick(this)", breakTick)).contains("ShieldGuard.usingShield(player)"),
                "no block is broken while the shield is up");
        String interact = read("action/InteractAction.java");
        int attack = interact.indexOf("public static ActionResult attackEntity(");
        assertTrue(interact.substring(attack, interact.indexOf("StrikeLegality.strikeRefusal", attack))
                        .contains("if (player.isUsingItem())"),
                "no attack is made while an item is in use (a raised shield included)");
        String core = read("task/CombatCore.java");
        int strike = core.indexOf("public static boolean strikeIfReady(");
        String strikeBody = core.substring(strike, core.indexOf("public static float safeStrafeInput", strike));
        int aim = strikeBody.indexOf("HumanAim.isUnderCrosshair(bot, target)");
        int lower = strikeBody.indexOf("ShieldGuard.lower(bot);");
        assertTrue(aim > 0 && lower > aim, "the shield comes down only for a swing that can land (the aim is on the target first)");
        assertTrue(strikeBody.substring(lower).startsWith("ShieldGuard.lower(bot);\n            return false;"),
                "the swing is the next tick's: never lowered and struck in the same tick");
    }

    @Test
    void theMeleeRhythmSwingsOnlyReadyOnTargetAndOnTheTickAfterTheLowering() throws IOException {
        String combat = read("task/CombatTask.java");
        int block = combat.indexOf("private void block(AIPlayerEntity bot)");
        String body = combat.substring(block, combat.indexOf("private void reposition(AIPlayerEntity bot)", block));
        assertTrue(body.contains("ShieldRules.meleeStep(ready, onTarget, true, ShieldGuard.shieldUsable(bot)"),
                "the rhythm is the pure state machine");
        assertTrue(body.contains("HumanAim.isUnderCrosshair(bot, target)"), "a swing needs the target under the crosshair");
        int swing = body.indexOf("case SWING ->");
        String swingCase = body.substring(swing, body.indexOf("case RAISE ->", swing));
        assertTrue(swingCase.contains("ShieldGuard.lower(bot);") && swingCase.contains("phase = Phase.STRIKE;")
                        && !swingCase.contains("strike(bot)"),
                "SWING lowers the shield and leaves the strike to the next tick");
        int reposition = combat.indexOf("private void reposition(AIPlayerEntity bot)");
        assertTrue(combat.substring(reposition, combat.indexOf("private void retreat(", reposition))
                        .contains("PaceRules.inputScale(false, bot.isUsingItem())"),
                "the raw strafe keys carry the vanilla use-item slowdown");
    }

    @Test
    void aProjectileFromAShooterNobodyNoticedWaitsTheReactionTime() throws IOException {
        String guard = read("task/ShieldGuard.java");
        assertTrue(guard.contains("ShieldRules.reacted(") && guard.contains("CreaturePerception.requiredSeconds(params"),
                "a first sighting waits the shared reaction formula");
        assertTrue(guard.contains("ObservableWorldQuery.canNoticeCreature(bot, living)"),
                "a projectile is anticipated only when its shooter is a creature the bot is tracking");
        assertFalse(guard.contains("SHOOTER_HOLD_MAX_TICKS"), "the pre-emptive raise is held until the shot lands or the draw stops");
        assertTrue(guard.contains("OffhandPolicy.apply(bot)") && !guard.contains("EquipAction.equipShieldOffhand"),
                "the offhand content is the equipment rule's (an empty offhand or a totem takes the best shield, nothing else moves)");
    }

    @Test
    void theHandPolicyFinishesEatingAndDrawsUnlessTheHitIsLethalAndTheRangedExchangeKeepsTheHands() throws IOException {
        String guard = read("task/ShieldGuard.java");
        assertTrue(guard.contains("ShieldRules.mayInterrupt(use, lethal)"), "the hand policy is the pure rule");
        assertTrue(guard.contains("combat.isRangedExchange()") && guard.contains("task instanceof CreeperDefenseTask")
                        && guard.contains("task instanceof EmergencyShelterTask"),
                "the tasks that own the hands are left alone");
        assertFalse(guard.contains("LookAction.lookAt("), "the head never turns instantly");
    }
}

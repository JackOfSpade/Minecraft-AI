package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.action.ShieldRules.MainHandKind;
import io.github.zoyluo.minecraftai.action.ShieldRules.MeleeStep;
import io.github.zoyluo.minecraftai.action.ShieldRules.UseKind;
import io.github.zoyluo.minecraftai.perception.CreaturePerception;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure shield decisions: the front arc, the reaction/facing/block-delay timing, the hand policy, the main-hand order, the melee rhythm. */
class ShieldRulesTest {
    // ------------------------------------------------------------------ the front arc

    @Test
    void theOffsetIsMeasuredFromTheHeadDirectionFlattened() {
        // Yaw 0 looks along +z (south): a source straight ahead is 0 degrees, to the side 90, behind 180.
        assertEquals(0.0D, ShieldRules.offsetDeg(0.0D, 0.0D, 5.0D), 1.0E-6D);
        assertEquals(90.0D, ShieldRules.offsetDeg(0.0D, 5.0D, 0.0D), 1.0E-6D);
        assertEquals(90.0D, ShieldRules.offsetDeg(0.0D, -5.0D, 0.0D), 1.0E-6D);
        assertEquals(180.0D, ShieldRules.offsetDeg(0.0D, 0.0D, -5.0D), 1.0E-6D);
        // Yaw 90 looks along -x (west).
        assertEquals(0.0D, ShieldRules.offsetDeg(90.0D, -5.0D, 0.0D), 1.0E-6D);
        assertEquals(45.0D, ShieldRules.offsetDeg(90.0D, -5.0D, 5.0D), 1.0E-6D);
        // A source on the bot itself has no direction: never an offset.
        assertEquals(0.0D, ShieldRules.offsetDeg(33.0D, 0.0D, 0.0D), 1.0E-9D);
    }

    @Test
    void theTurnBringsTheSourceInsideTheArcWithItsMargin() {
        // Inside the margin (70 degrees): no turn at all, a follower keeps looking where it walks.
        assertEquals(0.0D, ShieldRules.degreesToTurn(0.0D, 90.0D), 1.0E-9D);
        assertEquals(0.0D, ShieldRules.degreesToTurn(70.0D, 90.0D), 1.0E-9D);
        // Behind (180): 110 degrees to reach the 70 degree line.
        assertEquals(110.0D, ShieldRules.degreesToTurn(180.0D, 90.0D), 1.0E-9D);
        assertEquals(10.0D, ShieldRules.degreesToTurn(80.0D, 90.0D), 1.0E-9D);
    }

    @Test
    void theTurnTakesTheHumanRateNeverAnInstantSpin() {
        // The default human flick: 540 degrees per second = 27 per tick.
        assertEquals(0, ShieldRules.turnTicks(0.0D, 27.0D));
        assertEquals(1, ShieldRules.turnTicks(27.0D, 27.0D));
        assertEquals(2, ShieldRules.turnTicks(27.5D, 27.0D));
        assertEquals(5, ShieldRules.turnTicks(110.0D, 27.0D));
        assertEquals(Integer.MAX_VALUE, ShieldRules.turnTicks(10.0D, 0.0D));
    }

    // ------------------------------------------------------------------ the reaction

    @Test
    void aProjectileFromATrackedShooterIsAnticipatedAnythingElseWaitsTheReactionTime() {
        // Perception off: today's behaviour, no reaction time.
        assertTrue(ShieldRules.reacted(false, false, 0.0D, 5.0D));
        // A shooter the bot is tracking: it watched the release.
        assertTrue(ShieldRules.reacted(true, true, 0.0D, 5.0D));
        // A first sighting: continuous exposure must reach the required seconds.
        assertFalse(ShieldRules.reacted(true, false, 0.80D, 0.85D));
        assertTrue(ShieldRules.reacted(true, false, 0.85D, 0.85D));
        // Behind (never sighted) is never reacted to.
        assertFalse(ShieldRules.reacted(true, false, 99.0D, CreaturePerception.NEVER));
    }

    @Test
    void anArrowFromAShooterNobodyNoticedJustHitsASlowFireballFromFarAwayCanStillBeBlocked() {
        CreaturePerception.Params params = CreaturePerception.Params.defaults();
        // A skeleton arrow seen at 15 blocks straight ahead: 0.5 + 1.5 * 15 / 64 = 0.85 s (17 ticks) of reaction, but at 1.6 blocks per
        // tick it is on the bot in about 10 ticks: it lands before the reaction ends, like it would on a player caught off guard.
        double arrowRequired = CreaturePerception.requiredSeconds(params, 0.0D, 15.0D, CreaturePerception.Subject.of(false), false);
        assertEquals(0.8515625D, arrowRequired, 1.0E-9D);
        long reactTick = CreaturePerception.noticeTick(arrowRequired);
        assertEquals(18L, reactTick);
        double flightTicks = 15.0D / 1.6D;
        assertTrue(reactTick > flightTicks, "the reaction outlasts the flight");
        // The same arrow from a shooter the bot is watching: blocked if the block can be active in time (no turn, 5 delay ticks).
        assertTrue(ShieldRules.reacted(true, true, 0.0D, arrowRequired)
                && ShieldRules.canBeActiveInTime(flightTicks, 0, 5, 0));
        // Seen at 60 degrees the reaction is longer still (angle factor 1 + 30/70).
        double side = CreaturePerception.requiredSeconds(params, 60.0D, 15.0D, CreaturePerception.Subject.of(false), false);
        assertEquals(arrowRequired * (1.0D + 30.0D / 70.0D), side, 1.0E-9D);
        // A ghast fireball first seen at 40 blocks needs 1.4375 s (29 ticks). It leaves the ghast at 0.1 blocks per tick and speeds up
        // (AbstractHurtingProjectile: position += v, then v = (v + 0.1 * direction) * 0.95), so it takes far longer than an arrow:
        // after the reaction the block delay still fits before it arrives, so it is blocked.
        double fireballRequired = CreaturePerception.requiredSeconds(params, 0.0D, 40.0D, CreaturePerception.Subject.of(false), false);
        assertEquals(1.4375D, fireballRequired, 1.0E-9D);
        long fireballReact = CreaturePerception.noticeTick(fireballRequired);
        double travelled = 0.0D;
        double speed = 0.1D;
        int fireballFlight = 0;
        while (travelled < 40.0D) {
            travelled += speed;
            speed = (speed + 0.1D) * 0.95D;
            fireballFlight++;
        }
        assertTrue(fireballFlight > fireballReact + 5 + ShieldRules.HIT_SLACK_TICKS,
                "flight " + fireballFlight + " ticks, reaction " + fireballReact);
        assertTrue(ShieldRules.canBeActiveInTime(fireballFlight - fireballReact, 0, 5, 0));
    }

    // ------------------------------------------------------------------ the time a block needs

    @Test
    void aRaiseThatCannotBeActiveInTimeIsNotStarted() {
        // In front (no turn), shield down: the five block-delay ticks plus the slack must fit before the hit.
        assertTrue(ShieldRules.canBeActiveInTime(6.0D, 0, 5, 0));
        assertFalse(ShieldRules.canBeActiveInTime(5.9D, 0, 5, 0));
        // The turn and the delay run together: the longer of the two counts, not their sum.
        assertTrue(ShieldRules.canBeActiveInTime(6.0D, 5, 5, 0));
        assertFalse(ShieldRules.canBeActiveInTime(6.0D, 6, 5, 0));
        // A hotbar change comes first and adds its tick.
        assertFalse(ShieldRules.canBeActiveInTime(6.0D, 0, 5, ShieldRules.HOTBAR_SWITCH_TICKS));
        assertTrue(ShieldRules.canBeActiveInTime(7.0D, 0, 5, ShieldRules.HOTBAR_SWITCH_TICKS));
        // Already warming up: only what is left of the delay counts.
        assertTrue(ShieldRules.canBeActiveInTime(3.0D, 0, 2, 0));
        // A bot that cannot turn (a walker steers its head) never makes a turn-dependent block.
        assertFalse(ShieldRules.canBeActiveInTime(30.0D, Integer.MAX_VALUE, 0, 0));
    }

    // ------------------------------------------------------------------ the hand policy

    @Test
    void eatingADrawnBowAndOtherUsesAreOnlyInterruptedForALethalHit() {
        assertTrue(ShieldRules.mayInterrupt(UseKind.NONE, false));
        assertTrue(ShieldRules.mayInterrupt(UseKind.SHIELD, false));
        for (UseKind busy : new UseKind[]{UseKind.CONSUMING, UseKind.RANGED_DRAW, UseKind.OTHER}) {
            assertFalse(ShieldRules.mayInterrupt(busy, false), busy + " is finished for a hit that only hurts");
            assertTrue(ShieldRules.mayInterrupt(busy, true), busy + " is cancelled for a lethal hit");
        }
    }

    @Test
    void aHitIsLethalWhenItReachesTheHealthAndAbsorption() {
        assertTrue(ShieldRules.lethal(6.0F, 6.0F));
        assertTrue(ShieldRules.lethal(8.0F, 6.0F));
        assertFalse(ShieldRules.lethal(5.0F, 6.0F));
    }

    // ------------------------------------------------------------------ the main-hand use order

    @Test
    void aMainHandItemThatTakesTheUseForcesAHotbarChange() {
        for (MainHandKind takes : new MainHandKind[]{MainHandKind.SHIELD, MainHandKind.RANGED_READY, MainHandKind.CONSUMABLE_READY,
                MainHandKind.EQUIPPABLE_SWAP, MainHandKind.OTHER_USE}) {
            assertTrue(ShieldRules.mainHandConsumesUse(takes), takes + " takes the use before the offhand");
        }
        for (MainHandKind passes : new MainHandKind[]{MainHandKind.EMPTY, MainHandKind.PLAIN, MainHandKind.RANGED_UNUSABLE,
                MainHandKind.CONSUMABLE_REFUSED}) {
            assertFalse(ShieldRules.mainHandConsumesUse(passes), passes + " passes the use to the offhand");
        }
    }

    // ------------------------------------------------------------------ the melee rhythm

    @Test
    void theSwingComesFirstAndNeverWithTheShieldUp() {
        // Ready and on the crosshair: swing (the caller lowers the shield first), whatever else holds.
        assertEquals(MeleeStep.SWING, ShieldRules.meleeStep(true, true, true, true, true, 0.0D, 5));
        assertEquals(MeleeStep.SWING, ShieldRules.meleeStep(true, true, false, false, false, 0.0D, 5));
        // Ready but the aim is still settling: the shield stays up until the swing can land.
        assertEquals(MeleeStep.HOLD, ShieldRules.meleeStep(true, false, true, true, true, 0.0D, 5));
    }

    @Test
    void theShieldGoesUpBetweenSwingsAndIsHeldThroughTheCooldown() {
        // Just swung, 12 ticks to go, attacker in reach: raise. Then hold until the swing is ready again.
        assertEquals(MeleeStep.RAISE, ShieldRules.meleeStep(false, false, true, true, false, 12.0D, 5));
        assertEquals(MeleeStep.HOLD, ShieldRules.meleeStep(false, false, true, true, true, 7.0D, 5));
    }

    @Test
    void aRaiseThatCouldNotBeActiveBeforeTheNextSwingIsSkipped() {
        assertEquals(MeleeStep.RAISE, ShieldRules.meleeStep(false, false, true, true, false, 6.0D, 5));
        assertEquals(MeleeStep.IDLE, ShieldRules.meleeStep(false, false, true, true, false, 5.0D, 5));
    }

    @Test
    void aFullRhythmSwingRaiseHoldLowerSwingAndNoRaiseOnADisabledShield() {
        // A stone sword: 12.5 ticks between full swings (1.6 attacks per second), the shield's 5-tick block delay.
        double interval = 20.0D / 1.6D;
        StringBuilder trace = new StringBuilder();
        boolean shieldUp = false;
        int raises = 0;
        int swings = 0;
        for (int tick = 0; tick < 40; tick++) {
            double sinceSwing = tick % 13; // a swing lands every 13 ticks (the first tick at which the strength is back)
            double cooldownLeft = Math.max(0.0D, interval - sinceSwing);
            boolean ready = cooldownLeft <= 0.625D; // vanilla 0.95 of the strength scale
            MeleeStep step = ShieldRules.meleeStep(ready, true, true, true, shieldUp, cooldownLeft, 5);
            switch (step) {
                case SWING -> {
                    assertFalse(shieldUp && trace.toString().endsWith("W"), "never two swings with the shield up in between");
                    shieldUp = false; // lowered first, the swing follows
                    swings++;
                    trace.append('W');
                }
                case RAISE -> {
                    shieldUp = true;
                    raises++;
                    trace.append('R');
                }
                case HOLD -> {
                    assertTrue(shieldUp);
                    trace.append('H');
                }
                case IDLE -> trace.append('.');
            }
        }
        assertTrue(swings >= 3 && raises >= 3, trace.toString());
        assertTrue(trace.toString().contains("WRHHHH"), "raised right after a swing and held through the cooldown: " + trace);
        // The axe hit: the shield on its item cooldown is never raised, the swings go on.
        for (int tick = 0; tick < 13; tick++) {
            double cooldownLeft = Math.max(0.0D, interval - tick);
            MeleeStep step = ShieldRules.meleeStep(cooldownLeft <= 0.625D, true, true, false, false, cooldownLeft, 5);
            assertTrue(step == MeleeStep.IDLE || step == MeleeStep.SWING, "a disabled shield is left alone: " + step);
        }
    }

    @Test
    void noAttackerInReachOrNoUsableShieldMeansNoShieldWork() {
        assertEquals(MeleeStep.IDLE, ShieldRules.meleeStep(false, false, false, true, false, 12.0D, 5));
        // A shield disabled by an axe (the vanilla item cooldown) is never raised: no re-raise attempts.
        assertEquals(MeleeStep.IDLE, ShieldRules.meleeStep(false, false, true, false, false, 12.0D, 5));
        assertEquals(MeleeStep.IDLE, ShieldRules.meleeStep(false, false, true, false, true, 12.0D, 5));
    }
}

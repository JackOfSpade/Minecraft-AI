package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure decision-helper coverage for {@link DangerWatcher#decideEatInterrupt}: whether low
 * (non-critical) hunger may pause the active task to start eating, given task kind and urgency.
 */
final class EatInterruptPolicyTest {

    @Test
    void noActiveTaskAlwaysStartsWithoutPausing() {
        DangerWatcher.EatInterruptDecision decision =
                DangerWatcher.decideEatInterrupt(false, false, false, false);

        assertTrue(decision.startEating());
        assertFalse(decision.pauseActive());
    }

    @Test
    void lowHungerPausesOrdinaryInterruptibleWork() {
        // e.g. follow/hold/guard/gather -- not combat/evade, not a protected transaction.
        DangerWatcher.EatInterruptDecision decision =
                DangerWatcher.decideEatInterrupt(true, false, false, false);

        assertTrue(decision.startEating());
        assertTrue(decision.pauseActive());
    }

    @Test
    void lowHungerNeverPreemptsCombatOrEvadeEvenIfNotMarkedProtected() {
        DangerWatcher.EatInterruptDecision decision =
                DangerWatcher.decideEatInterrupt(true, false, true, false);

        assertFalse(decision.startEating());
        assertFalse(decision.pauseActive());
    }

    @Test
    void urgentHungerStillNeverPreemptsCombatOrEvade() {
        DangerWatcher.EatInterruptDecision decision =
                DangerWatcher.decideEatInterrupt(true, true, true, false);

        assertFalse(decision.startEating());
        assertFalse(decision.pauseActive());
    }

    @Test
    void lowHungerDefersAProtectedAtomicTransaction() {
        // e.g. a mining-pick transaction mid-break, or a craft/smelt/container transaction.
        DangerWatcher.EatInterruptDecision decision =
                DangerWatcher.decideEatInterrupt(true, false, false, true);

        assertFalse(decision.startEating());
        assertFalse(decision.pauseActive());
    }

    @Test
    void urgentHungerStillInterruptsAProtectedAtomicTransaction() {
        // Critical starvation / low-health heal / shelter-cleanup recovery preserve the
        // pre-existing urgent-path behaviour: they may preempt anything except combat/evade.
        DangerWatcher.EatInterruptDecision decision =
                DangerWatcher.decideEatInterrupt(true, true, false, true);

        assertTrue(decision.startEating());
        assertTrue(decision.pauseActive());
    }
}

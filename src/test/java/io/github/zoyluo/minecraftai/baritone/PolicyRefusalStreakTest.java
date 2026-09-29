package io.github.zoyluo.minecraftai.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.zoyluo.minecraftai.navigation.NavOutcome;
import io.github.zoyluo.minecraftai.navigation.NavRoute;
import io.github.zoyluo.minecraftai.navigation.NavRouteRules;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The refusal cap: a route that the strict-survival rules keep vetoing ends as a typed failure instead of looping. */
class PolicyRefusalStreakTest {
    @AfterEach
    void clean() {
        PolicyRefusalStreak.clearAll();
    }

    @Test
    void theCapIsReachedAfterEnoughRefusalsInARowAndNotBefore() {
        UUID bot = UUID.randomUUID();
        assertEquals(0, PolicyRefusalStreak.of(bot));
        for (int i = 1; i < PolicyRefusalStreak.CAP; i++) {
            PolicyRefusalStreak.refused(bot);
            assertFalse(PolicyRefusalStreak.capReached(bot), "refusal " + i + " is below the cap");
        }
        PolicyRefusalStreak.refused(bot);
        assertTrue(PolicyRefusalStreak.capReached(bot));
        assertEquals(PolicyRefusalStreak.CAP, PolicyRefusalStreak.of(bot));
    }

    @Test
    void anAllowedEditOrANewRouteEndsTheStreakAndBotsAreIndependent() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        for (int i = 0; i < PolicyRefusalStreak.CAP; i++) {
            PolicyRefusalStreak.refused(a);
        }
        PolicyRefusalStreak.refused(b);
        assertTrue(PolicyRefusalStreak.capReached(a));
        assertFalse(PolicyRefusalStreak.capReached(b));
        PolicyRefusalStreak.reset(a);
        assertEquals(0, PolicyRefusalStreak.of(a));
        assertEquals(1, PolicyRefusalStreak.of(b));
    }

    @Test
    void aVetoedRouteEndsFailedWithTheTypedReason() {
        NavRouteRules.Verdict verdict = NavRouteRules.verdict(NavRoute.Progress.POLICY_REFUSED, false, false, false);
        assertTrue(verdict.ended());
        assertEquals(NavOutcome.Status.FAILED, verdict.status());
        assertEquals("policy_refused", verdict.reason());
        assertEquals(NavRouteRules.POLICY_REFUSED, verdict.reason());
        // arriving still wins over everything, and a route that is merely running is untouched
        assertEquals(NavOutcome.Status.SUCCESS, NavRouteRules.verdict(NavRoute.Progress.ARRIVED, false, false, false).status());
        assertFalse(NavRouteRules.verdict(NavRoute.Progress.RUNNING, false, false, false).ended());
    }

    @Test
    void theLedgersFeedAndResetTheStreak() throws Exception {
        String refusals = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/baritone/BaritoneRefusals.java"));
        assertTrue(refusals.contains("if (op == Op.BREAK || op == Op.PLACE) {\n            PolicyRefusalStreak.refused(bot.getUUID());"),
                "breaks and placements that were refused count towards the cap");
        String edits = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/baritone/BaritoneEdits.java"));
        assertTrue(edits.contains("static void record(AIPlayerEntity bot, Edit edit) {\n        PolicyRefusalStreak.reset(bot.getUUID());"),
                "a break or placement that went through ends the streak");
        String navigator = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/baritone/BaritoneNavigator.java"));
        assertTrue(navigator.contains("PolicyRefusalStreak.capReached(bot.getUUID()) ? NavRoute.Progress.POLICY_REFUSED"),
                "the navigator reports a vetoed route");
        assertTrue(navigator.contains("PolicyRefusalStreak.reset(bot.getUUID());"), "a new route starts with a clean streak");
    }
}

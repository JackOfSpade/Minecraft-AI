package io.github.zoyluo.minecraftai.action;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The slow-pace credit of a route deadline is capped per route, and Baritone taking over keeps the route lease. */
class DeadlineCreditTest {
    @Test
    void sprintEarnsNothing() {
        DeadlineCredit credit = new DeadlineCredit(1000);
        for (int i = 0; i < 100; i++) {
            assertEquals(0, credit.note(Gait.SPRINT));
        }
        assertEquals(0, credit.granted());
    }

    @Test
    void sneakingEarnsAboutThreePointFourTicksPerTickUntilTheCap() {
        DeadlineCredit credit = new DeadlineCredit(10_000);
        int total = 0;
        for (int i = 0; i < 100; i++) {
            total += credit.note(Gait.SNEAK);
        }
        // 1 - 1/4.4 = 0.7727 per tick (the deadline moves out 0.77 ticks per sneaking tick, i.e. only 1/4.4 of a tick counts).
        assertEquals(77, total, 1);
        assertEquals(total, credit.granted());
    }

    @Test
    void aLongSneakLeaseStillReachesItsDeadline() {
        int budget = 600 + 20 * 30;
        DeadlineCredit credit = DeadlineCredit.forBudget(budget);
        int deadline = budget;
        int tick = 0;
        while (tick < deadline && tick < 100_000) {
            tick++;
            deadline += credit.note(Gait.SNEAK);
        }
        assertTrue(tick < 100_000, "the deadline of a route sneaked all the way was never reached");
        assertTrue(deadline <= budget * 2, "the total credit exceeded the cap: deadline " + deadline + " for a budget of " + budget);
        assertEquals(budget, credit.granted());
    }

    @Test
    void theCapIsExactAndNothingIsGrantedAfterIt() {
        DeadlineCredit credit = new DeadlineCredit(5);
        int total = 0;
        for (int i = 0; i < 50; i++) {
            total += credit.note(Gait.WALK) + credit.note(Gait.SNEAK);
        }
        assertEquals(5, total);
        assertEquals(0, credit.note(Gait.SNEAK));
    }

    @Test
    void noBudgetMeansNoCredit() {
        DeadlineCredit credit = new DeadlineCredit(0);
        assertEquals(0, credit.note(Gait.SNEAK));
        assertEquals(0, DeadlineCredit.forBudget(-5).note(Gait.SNEAK));
    }

    /** Baritone taking over on its first driven tick must not drop the route lease that was requested after the route started. */
    @Test
    void yieldToBaritoneKeepsTheRouteLease() throws Exception {
        String pack = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/action/ActionPack.java"));
        int start = pack.indexOf("public void yieldToBaritone()");
        int end = pack.indexOf("\n    }\n", start);
        String body = pack.substring(start, end);
        assertTrue(body.contains("dropPathExecutor();"), "yieldToBaritone must drop the executor without the lease");
        assertTrue(!body.contains("clearActivePathExecutor()") && !body.contains("clearRouteLease()"),
                "yieldToBaritone must not end the route lease");
        int wrapper = pack.indexOf("private void clearActivePathExecutor()");
        String wrapperBody = pack.substring(wrapper, pack.indexOf("\n    }\n", wrapper));
        assertTrue(wrapperBody.contains("dropPathExecutor();") && wrapperBody.contains("clearRouteLease();"));
    }
}

package io.github.zoyluo.aibot.brain;

import io.github.zoyluo.aibot.AIBotConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class AmbientConversationCoordinatorTest {

    private static AIBotConfig.Conversation conversation(double readingWpm, double thinkingSecondsPerWord,
                                                          double minDelay, double maxDelay) {
        return new AIBotConfig.Conversation(true, 12000, 200, 0.03D, 1, 4,
                readingWpm, thinkingSecondsPerWord, minDelay, maxDelay, 100);
    }

    @Test
    void checksOnlyOnTheConfiguredInterval() {
        assertTrue(AmbientConversationCoordinator.shouldCheckThisTick(200, 200));
        assertTrue(AmbientConversationCoordinator.shouldCheckThisTick(0, 200));
        assertFalse(AmbientConversationCoordinator.shouldCheckThisTick(199, 200));
        assertFalse(AmbientConversationCoordinator.shouldCheckThisTick(200, 0));
        assertFalse(AmbientConversationCoordinator.shouldCheckThisTick(200, -1));
    }

    @Test
    void eligibilityExcludesSafetyTasksAndBusyBrains() {
        assertTrue(AmbientConversationCoordinator.isEligible(false, false));
        assertFalse(AmbientConversationCoordinator.isEligible(true, false));
        assertFalse(AmbientConversationCoordinator.isEligible(false, true));
        assertFalse(AmbientConversationCoordinator.isEligible(true, true));
    }

    @Test
    void participantCountStaysWithinConfiguredAndAvailableBounds() {
        SplittableRandom random = new SplittableRandom(1);
        for (int trial = 0; trial < 200; trial++) {
            int eligible = 1 + random.nextInt(10);
            int min = 1 + random.nextInt(4);
            int max = min + random.nextInt(4);
            int count = AmbientConversationCoordinator.pickParticipantCount(eligible, min, max, random);
            assertTrue(count >= 1, "count must be at least 1");
            assertTrue(count <= eligible, "count must not exceed how many are actually eligible");
            assertTrue(count <= Math.max(min, max), "count must not exceed the configured maximum");
        }
    }

    @Test
    void participantCountNeverExceedsASingleEligibleBot() {
        SplittableRandom random = new SplittableRandom(2);
        assertEquals(1, AmbientConversationCoordinator.pickParticipantCount(1, 1, 4, random));
    }

    @Test
    void shuffleAndTakePicksDistinctItemsFromTheOriginalList() {
        SplittableRandom random = new SplittableRandom(3);
        List<String> bots = List.of("Moss", "Iron", "Dusk", "Fern", "Ash");
        for (int trial = 0; trial < 50; trial++) {
            List<String> chosen = AmbientConversationCoordinator.shuffleAndTake(bots, 3, random);
            assertEquals(3, chosen.size());
            assertEquals(3, Set.copyOf(chosen).size(), "no duplicates");
            assertTrue(bots.containsAll(chosen), "every chosen bot came from the original list");
        }
    }

    @Test
    void shuffleAndTakeClampsToTheListSize() {
        SplittableRandom random = new SplittableRandom(4);
        List<String> bots = List.of("Moss", "Iron");
        assertEquals(2, AmbientConversationCoordinator.shuffleAndTake(bots, 5, random).size());
    }

    @Test
    void endsWithQuestionIgnoresTrailingWhitespace() {
        assertTrue(AmbientConversationCoordinator.endsWithQuestion("Have you seen the new outpost?"));
        assertTrue(AmbientConversationCoordinator.endsWithQuestion("Have you seen the new outpost?  \n"));
        assertFalse(AmbientConversationCoordinator.endsWithQuestion("It's a nice day out."));
        assertFalse(AmbientConversationCoordinator.endsWithQuestion(""));
        assertFalse(AmbientConversationCoordinator.endsWithQuestion(null));
    }

    @Test
    void firstLineHasNoDelayBecauseThereIsNothingToReadYet() {
        assertEquals(0, AmbientConversationCoordinator.computeDelayTicks(null, conversation(200, 0.15, 2, 25)));
        assertEquals(0, AmbientConversationCoordinator.computeDelayTicks("  ", conversation(200, 0.15, 2, 25)));
    }

    @Test
    void delayGrowsWithMessageLengthWithinTheConfiguredBounds() {
        AIBotConfig.Conversation cfg = conversation(200, 0.15, 2, 25);
        int shortDelay = AmbientConversationCoordinator.computeDelayTicks("Nice weather today.", cfg);
        int longDelay = AmbientConversationCoordinator.computeDelayTicks(
                "I was thinking we should head north past the ravine and check out that abandoned "
                        + "mineshaft before it gets dark, since the light level there was really low last time.",
                cfg);
        assertTrue(shortDelay >= (int) (cfg.minReplyDelaySeconds() * 20), "never below the configured floor");
        assertTrue(longDelay > shortDelay, "a longer message must take longer to react to");
        assertTrue(longDelay <= (int) (cfg.maxReplyDelaySeconds() * 20), "never above the configured ceiling");
    }

    @Test
    void delayNeverExceedsTheConfiguredCeilingEvenForAVeryLongMessage() {
        AIBotConfig.Conversation cfg = conversation(200, 0.15, 2, 10);
        String longMessage = "word ".repeat(200);
        int delay = AmbientConversationCoordinator.computeDelayTicks(longMessage, cfg);
        assertEquals((int) Math.round(cfg.maxReplyDelaySeconds() * 20), delay);
    }
}

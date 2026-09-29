package io.github.zoyluo.minecraftai.mining.assist;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Design 6.4: dedupe radius and dimension keying, the DECLINED re-ask rule, the same-label 96-block
 * suppression, the 32-entry cap with stalest eviction, STOPPED/DECLINED TTL expiry, the BotMemory ring slot,
 * and the "open case"/restart-notice bookkeeping {@code PoiCoordinator} needs. */
class PoiRegistryTest {
    private static final Function<String, String> NO_ENV = key -> null;
    private static final UUID BOT = new UUID(1L, 2L);
    private static final UUID OTHER = new UUID(3L, 4L);
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @BeforeEach
    @AfterEach
    void reset() {
        MiningAssistRuntime.resetForTests();
        MiningAssistRuntime.install(null, NO_ENV);
        PoiRegistry.clearAll();
    }

    private static BlockPos at(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    // ---- dedupe radius + dimension keying -----------------------------------------------------------

    @Test
    void aStoppedEntrySuppressesWithinTheConfiguredDedupeRadiusOnly() {
        int radius = MiningAssistRuntime.config().poi().dedupeRadius();
        PoiRegistry.record(BOT, OVERWORLD, at(0, 64, 0), "mineshaft", PoiRegistry.State.STOPPED, 0.9D, 100);
        assertTrue(PoiRegistry.suppressed(BOT, OVERWORLD, at(radius, 64, 0), "other_label", 0.9D, 200),
                "within the dedupe radius, any label is suppressed by a STOPPED entry");
        assertFalse(PoiRegistry.suppressed(BOT, OVERWORLD, at(radius + 1, 64, 0), "other_label", 0.9D, 200),
                "just outside the dedupe radius, a different label is not suppressed");
    }

    @Test
    void suppressionNeverCrossesDimensionsOrBots() {
        PoiRegistry.record(BOT, OVERWORLD, at(0, 64, 0), "mineshaft", PoiRegistry.State.STOPPED, 0.9D, 100);
        assertFalse(PoiRegistry.suppressed(BOT, NETHER, at(0, 64, 0), "mineshaft", 0.9D, 200),
                "the same coordinates in a different dimension are unrelated");
        assertFalse(PoiRegistry.suppressed(OTHER, OVERWORLD, at(0, 64, 0), "mineshaft", 0.9D, 200),
                "another bot's registry is entirely separate");
    }

    @Test
    void aConsultingEntryAlsoSuppressesWithinTheDedupeRadius() {
        PoiRegistry.record(BOT, OVERWORLD, at(5, 64, 5), "structure", PoiRegistry.State.CONSULTING, 0.5D, 50);
        assertTrue(PoiRegistry.suppressed(BOT, OVERWORLD, at(5, 64, 5), "structure", 0.5D, 60));
    }

    // ---- DECLINED re-ask rule -------------------------------------------------------------------------

    @Test
    void aDeclinedEntrySuppressesUntilBothTheAgeAndScoreGrowthConditionsAreMet() {
        PoiRegistry.record(BOT, OVERWORLD, at(0, 64, 0), "possible_structure", PoiRegistry.State.DECLINED, 0.40D, 1000);

        // Neither condition met yet.
        assertTrue(PoiRegistry.suppressed(BOT, OVERWORLD, at(0, 64, 0), "possible_structure", 0.40D, 1500));
        // Old enough, but the score has not grown.
        assertTrue(PoiRegistry.suppressed(BOT, OVERWORLD, at(0, 64, 0), "possible_structure", 0.40D,
                1000 + PoiRegistry.RE_ASK_MIN_TICKS));
        // Score grown enough, but not old enough.
        assertTrue(PoiRegistry.suppressed(BOT, OVERWORLD, at(0, 64, 0), "possible_structure",
                0.40D + PoiRegistry.RE_ASK_SCORE_GROWTH, 1500));
        // Both conditions met: no longer suppressed.
        assertFalse(PoiRegistry.suppressed(BOT, OVERWORLD, at(0, 64, 0), "possible_structure",
                0.40D + PoiRegistry.RE_ASK_SCORE_GROWTH, 1000 + PoiRegistry.RE_ASK_MIN_TICKS));
    }

    // ---- same-label 96-block suppression ---------------------------------------------------------------

    @Test
    void aStoppedEntryWithTheSameLabelSuppressesWithinTheSameLabelRadiusEvenBeyondTheDedupeRadius() {
        int dedupeRadius = MiningAssistRuntime.config().poi().dedupeRadius();
        assertTrue(dedupeRadius < PoiRegistry.SAME_LABEL_RADIUS_BLOCKS, "the fixture must exercise the wider radius");
        PoiRegistry.record(BOT, OVERWORLD, at(0, 64, 0), "mineshaft", PoiRegistry.State.STOPPED, 0.9D, 100);

        int beyondDedupe = dedupeRadius + 5;
        assertTrue(PoiRegistry.suppressed(BOT, OVERWORLD, at(beyondDedupe, 64, 0), "mineshaft", 0.9D, 200),
                "same label, within 96 blocks but outside the plain dedupe radius, is still suppressed");
        assertFalse(PoiRegistry.suppressed(BOT, OVERWORLD, at(beyondDedupe, 64, 0), "player_base", 0.9D, 200),
                "a different label at the same distance is not covered by the same-label rule");
        assertFalse(PoiRegistry.suppressed(BOT, OVERWORLD,
                at(PoiRegistry.SAME_LABEL_RADIUS_BLOCKS + 1, 64, 0), "mineshaft", 0.9D, 200),
                "beyond the same-label radius entirely, nothing suppresses");
    }

    // ---- cap + stalest eviction ------------------------------------------------------------------------

    @Test
    void recordingBeyondTheCapEvictsTheStalestEntryFirst() {
        for (int i = 0; i < PoiRegistry.MAX_ENTRIES_PER_BOT; i++) {
            PoiRegistry.record(BOT, OVERWORLD, at(i * 1000, 64, 0), "label_" + i, PoiRegistry.State.DECLINED, 0.1D, i);
        }
        List<PoiRegistry.Entry> before = PoiRegistry.snapshot(BOT);
        assertEquals(PoiRegistry.MAX_ENTRIES_PER_BOT, before.size());
        assertTrue(before.stream().anyMatch(e -> e.label().equals("label_0")), "the stalest entry is still present before the cap is exceeded");

        // One more record, far from everything so it cannot refresh an existing entry: the stalest (tick 0) is evicted.
        PoiRegistry.record(BOT, OVERWORLD, at(999_000, 64, 0), "label_new", PoiRegistry.State.DECLINED, 0.1D,
                PoiRegistry.MAX_ENTRIES_PER_BOT);
        List<PoiRegistry.Entry> after = PoiRegistry.snapshot(BOT);
        assertEquals(PoiRegistry.MAX_ENTRIES_PER_BOT, after.size(), "the cap is never exceeded");
        assertFalse(after.stream().anyMatch(e -> e.label().equals("label_0")), "the stalest (tick 0) entry was evicted");
        assertTrue(after.stream().anyMatch(e -> e.label().equals("label_new")));
    }

    @Test
    void recordingTheSameSiteAgainRefreshesInPlaceRatherThanDuplicating() {
        PoiRegistry.record(BOT, OVERWORLD, at(0, 64, 0), "mineshaft", PoiRegistry.State.DECLINED, 0.3D, 10);
        PoiRegistry.record(BOT, OVERWORLD, at(0, 64, 0), "mineshaft", PoiRegistry.State.STOPPED, 0.9D, 20);
        List<PoiRegistry.Entry> entries = PoiRegistry.snapshot(BOT);
        assertEquals(1, entries.size(), "the same (dimension, anchor, label) refreshes rather than duplicating");
        assertEquals(PoiRegistry.State.STOPPED, entries.get(0).state());
        assertEquals(0.9D, entries.get(0).score(), 1.0e-9D);
    }

    // ---- TTL expiry -------------------------------------------------------------------------------------

    @Test
    void aStoppedEntryExpiresAfterItsTtlAndNoLongerSuppresses() {
        PoiRegistry.record(BOT, OVERWORLD, at(0, 64, 0), "mineshaft", PoiRegistry.State.STOPPED, 0.9D, 0);
        assertTrue(PoiRegistry.suppressed(BOT, OVERWORLD, at(0, 64, 0), "mineshaft", 0.9D, PoiRegistry.STOPPED_TTL_TICKS));
        assertFalse(PoiRegistry.suppressed(BOT, OVERWORLD, at(0, 64, 0), "mineshaft", 0.9D, PoiRegistry.STOPPED_TTL_TICKS + 1));
    }

    @Test
    void aDeclinedEntryExpiresAfterItsShorterTtlAndNoLongerSuppresses() {
        PoiRegistry.record(BOT, OVERWORLD, at(0, 64, 0), "possible_structure", PoiRegistry.State.DECLINED, 0.3D, 0);
        assertTrue(PoiRegistry.suppressed(BOT, OVERWORLD, at(0, 64, 0), "possible_structure", 0.3D, PoiRegistry.DECLINED_TTL_TICKS));
        assertFalse(PoiRegistry.suppressed(BOT, OVERWORLD, at(0, 64, 0), "possible_structure", 0.3D, PoiRegistry.DECLINED_TTL_TICKS + 1));
    }

    // ---- ring slot --------------------------------------------------------------------------------------

    @Test
    void theRingSlotWrapsOneTwoThreeOne() {
        assertEquals(1, PoiRegistry.nextRingSlot(BOT));
        assertEquals(2, PoiRegistry.nextRingSlot(BOT));
        assertEquals(3, PoiRegistry.nextRingSlot(BOT));
        assertEquals(1, PoiRegistry.nextRingSlot(BOT));
        assertEquals(1, PoiRegistry.nextRingSlot(OTHER), "each bot has its own ring");
    }

    // ---- open case / restart notice --------------------------------------------------------------------

    @Test
    void openCaseSetGetAndCloseRoundTrip() {
        assertNull(PoiRegistry.openCase(BOT));
        PoiRegistry.OpenCase openCase = new PoiRegistry.OpenCase("mineshaft", "CERTAIN", OVERWORLD, at(1, 2, 3));
        PoiRegistry.openCase(BOT, openCase);
        assertEquals(openCase, PoiRegistry.openCase(BOT));
        PoiRegistry.closeCase(BOT);
        assertNull(PoiRegistry.openCase(BOT));
    }

    @Test
    void theRestartNoticeFlagIsPerBotAndClearedByCloseCase() {
        assertFalse(PoiRegistry.restartNoticeSent(BOT));
        PoiRegistry.markRestartNoticeSent(BOT);
        assertTrue(PoiRegistry.restartNoticeSent(BOT));
        assertFalse(PoiRegistry.restartNoticeSent(OTHER));
        PoiRegistry.closeCase(BOT);
        assertFalse(PoiRegistry.restartNoticeSent(BOT), "closing a case resets the flag for the next one");
    }

    // ---- clear / clearAll --------------------------------------------------------------------------------

    @Test
    void clearDropsOneBotsStateOnlyAndClearAllDropsEveryone() {
        PoiRegistry.record(BOT, OVERWORLD, at(0, 64, 0), "mineshaft", PoiRegistry.State.STOPPED, 0.9D, 0);
        PoiRegistry.record(OTHER, OVERWORLD, at(0, 64, 0), "mineshaft", PoiRegistry.State.STOPPED, 0.9D, 0);
        PoiRegistry.openCase(BOT, new PoiRegistry.OpenCase("mineshaft", "CERTAIN", OVERWORLD, at(0, 64, 0)));
        PoiRegistry.nextRingSlot(BOT);
        PoiRegistry.markRestartNoticeSent(BOT);

        PoiRegistry.clear(BOT);
        assertTrue(PoiRegistry.snapshot(BOT).isEmpty());
        assertNull(PoiRegistry.openCase(BOT));
        assertFalse(PoiRegistry.restartNoticeSent(BOT));
        assertEquals(1, PoiRegistry.nextRingSlot(BOT), "the ring resets too");
        assertFalse(PoiRegistry.snapshot(OTHER).isEmpty(), "clear(botId) never touches another bot");

        PoiRegistry.clearAll();
        assertTrue(PoiRegistry.snapshot(OTHER).isEmpty(), "clearAll drops every bot");
    }
}

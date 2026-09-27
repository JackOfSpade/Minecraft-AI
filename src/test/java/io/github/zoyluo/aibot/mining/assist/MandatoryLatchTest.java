package io.github.zoyluo.aibot.mining.assist;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Design 6.4: the warden mandatory-repeat suppression latch, entirely separate from {@link PoiRegistry}, and
 * the {@code inNoDetourZone} it backs for {@code DetourSafetyGate}. */
class MandatoryLatchTest {
    private static final UUID BOT = new UUID(5L, 6L);
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @BeforeEach
    @AfterEach
    void reset() {
        MandatoryLatch.clearAll();
    }

    private static BlockPos at(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    @Test
    void noPriorSiteIsNeverSuppressed() {
        assertFalse(MandatoryLatch.suppresses(BOT, OVERWORLD, at(0, 64, 0), false, 100));
        assertFalse(MandatoryLatch.suppresses(BOT, OVERWORLD, at(0, 64, 0), true, 100),
                "the first mandatory site always stops even with a warden visible");
    }

    @Test
    void allFourConditionsTogetherSuppressARepeat() {
        MandatoryLatch.record(BOT, OVERWORLD, at(0, 64, 0), 0);
        MandatoryLatch.acknowledge(BOT, 10);
        assertTrue(MandatoryLatch.suppresses(BOT, OVERWORLD, at(10, 64, 0), false, 500),
                "acknowledged, no warden, within radius, within the ack TTL");
    }

    @Test
    void notAcknowledgedBreaksSuppression() {
        MandatoryLatch.record(BOT, OVERWORLD, at(0, 64, 0), 0);
        // never acknowledged
        assertFalse(MandatoryLatch.suppresses(BOT, OVERWORLD, at(10, 64, 0), false, 500));
    }

    @Test
    void wardenVisibleBreaksTheOrdinarySuppressionRegardlessOfAcknowledgement() {
        MandatoryLatch.record(BOT, OVERWORLD, at(0, 64, 0), 0);
        MandatoryLatch.acknowledge(BOT, 10);
        assertFalse(MandatoryLatch.suppresses(BOT, OVERWORLD, at(10, 64, 0), true, 500),
                "a visible warden always re-evaluates the ordinary suppression, even acknowledged");
    }

    @Test
    void beyondTheSameSiteRadiusBreaksSuppression() {
        MandatoryLatch.record(BOT, OVERWORLD, at(0, 64, 0), 0);
        MandatoryLatch.acknowledge(BOT, 10);
        assertFalse(MandatoryLatch.suppresses(BOT, OVERWORLD,
                at(MandatoryLatch.SAME_SITE_RADIUS_BLOCKS + 1, 64, 0), false, 500),
                "new evidence more than the same-site radius away always stops");
        assertTrue(MandatoryLatch.suppresses(BOT, OVERWORLD,
                at(MandatoryLatch.SAME_SITE_RADIUS_BLOCKS, 64, 0), false, 500),
                "exactly at the radius is still the same site");
    }

    @Test
    void atOrAfterTheAckTtlBreaksSuppression() {
        MandatoryLatch.record(BOT, OVERWORLD, at(0, 64, 0), 0);
        MandatoryLatch.acknowledge(BOT, 10);
        assertTrue(MandatoryLatch.suppresses(BOT, OVERWORLD, at(0, 64, 0), false, MandatoryLatch.ACK_TTL_TICKS - 1));
        assertFalse(MandatoryLatch.suppresses(BOT, OVERWORLD, at(0, 64, 0), false, MandatoryLatch.ACK_TTL_TICKS));
    }

    @Test
    void wardenVisibleRateLimitAppliesOnlyWithinTheWindowSinceTheLastRecord() {
        MandatoryLatch.record(BOT, OVERWORLD, at(0, 64, 0), 1000);
        assertTrue(MandatoryLatch.suppresses(BOT, OVERWORLD, at(0, 64, 0), true,
                1000 + MandatoryLatch.WARDEN_NOTICE_RATE_LIMIT_TICKS - 1),
                "within the rate limit window since the last stop, a still-visible warden does not re-notify");
        assertFalse(MandatoryLatch.suppresses(BOT, OVERWORLD, at(0, 64, 0), true,
                1000 + MandatoryLatch.WARDEN_NOTICE_RATE_LIMIT_TICKS),
                "once the window elapses, the warden may stop the bot again");
    }

    @Test
    void differentDimensionNeverSuppresses() {
        MandatoryLatch.record(BOT, OVERWORLD, at(0, 64, 0), 0);
        MandatoryLatch.acknowledge(BOT, 10);
        assertFalse(MandatoryLatch.suppresses(BOT, NETHER, at(0, 64, 0), false, 500));
    }

    @Test
    void acknowledgeIsANoOpWhenThereIsNoTrackedSite() {
        MandatoryLatch.acknowledge(BOT, 10);
        assertFalse(MandatoryLatch.suppresses(BOT, OVERWORLD, at(0, 64, 0), false, 500));
    }

    // ---- inNoDetourZone -----------------------------------------------------------------------------

    @Test
    void inNoDetourZoneIsFalseWhenThereIsNoTrackedSite() {
        assertFalse(MandatoryLatch.inNoDetourZone(BOT, OVERWORLD, at(0, 64, 0)));
    }

    @Test
    void inNoDetourZoneTracksRadiusAndDimensionOfTheLatestSite() {
        MandatoryLatch.record(BOT, OVERWORLD, at(100, 64, 100), 0);
        assertTrue(MandatoryLatch.inNoDetourZone(BOT, OVERWORLD,
                at(100 + MandatoryLatch.NO_DETOUR_ZONE_RADIUS_BLOCKS, 64, 100)));
        assertFalse(MandatoryLatch.inNoDetourZone(BOT, OVERWORLD,
                at(100 + MandatoryLatch.NO_DETOUR_ZONE_RADIUS_BLOCKS + 1, 64, 100)));
        assertFalse(MandatoryLatch.inNoDetourZone(BOT, NETHER, at(100, 64, 100)),
                "a different dimension is never in the zone");
    }

    @Test
    void clearDropsOneBotsSiteOnlyAndClearAllDropsEveryone() {
        UUID other = new UUID(7L, 8L);
        MandatoryLatch.record(BOT, OVERWORLD, at(0, 64, 0), 0);
        MandatoryLatch.record(other, OVERWORLD, at(0, 64, 0), 0);

        MandatoryLatch.clear(BOT);
        assertFalse(MandatoryLatch.inNoDetourZone(BOT, OVERWORLD, at(0, 64, 0)));
        assertTrue(MandatoryLatch.inNoDetourZone(other, OVERWORLD, at(0, 64, 0)), "clear(botId) never touches another bot");

        MandatoryLatch.clearAll();
        assertFalse(MandatoryLatch.inNoDetourZone(other, OVERWORLD, at(0, 64, 0)));
    }
}

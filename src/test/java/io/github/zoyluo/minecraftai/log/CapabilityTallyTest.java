package io.github.zoyluo.minecraftai.log;

import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers {@code CapabilityTally}, the per-bot accumulator behind {@code gather_summary}'s
 * {@code forced_pickups}/{@code capability_denials} fields (see docs/LOGGING.md "Auditing a
 * gather"). {@code CapabilityTally.INSTANCE} is a process-wide singleton, so each test uses its
 * own bot id and clears it afterward to avoid leaking state between tests.
 */
final class CapabilityTallyTest {
    private final UUID botId = UUID.randomUUID();

    @AfterEach
    void clear() {
        CapabilityTally.INSTANCE.clear(botId);
    }

    @Test
    void freshBotStartsAtZero() {
        CapabilityTally.Snapshot snapshot = CapabilityTally.INSTANCE.snapshot(botId);
        assertEquals(0, snapshot.forcedPickupsAllowed());
        assertEquals(0, snapshot.denied());
    }

    @Test
    void allowedForcedPickupIncrementsForcedPickupsAllowed() {
        CapabilityTally.INSTANCE.record(botId, PrivilegedCapability.FORCED_PICKUP, true);
        CapabilityTally.INSTANCE.record(botId, PrivilegedCapability.FORCED_PICKUP, true);

        CapabilityTally.Snapshot snapshot = CapabilityTally.INSTANCE.snapshot(botId);
        assertEquals(2, snapshot.forcedPickupsAllowed());
        assertEquals(0, snapshot.denied());
    }

    @Test
    void anyDeniedDecisionIncrementsDeniedRegardlessOfCapability() {
        CapabilityTally.INSTANCE.record(botId, PrivilegedCapability.FORCED_PICKUP, false);
        CapabilityTally.INSTANCE.record(botId, PrivilegedCapability.HIDDEN_BLOCK_SCAN, false);

        CapabilityTally.Snapshot snapshot = CapabilityTally.INSTANCE.snapshot(botId);
        assertEquals(0, snapshot.forcedPickupsAllowed());
        assertEquals(2, snapshot.denied());
    }

    @Test
    void allowedHiddenBlockScanCountsTowardNeitherCounter() {
        // Only FORCED_PICKUP allowances are tracked as "forced_pickups"; an allowed scan is
        // normal, unprivileged observation once granted, so it must not inflate either count.
        CapabilityTally.INSTANCE.record(botId, PrivilegedCapability.HIDDEN_BLOCK_SCAN, true);

        CapabilityTally.Snapshot snapshot = CapabilityTally.INSTANCE.snapshot(botId);
        assertEquals(0, snapshot.forcedPickupsAllowed());
        assertEquals(0, snapshot.denied());
    }

    @Test
    void resetZeroesCountsForANewTaskRun() {
        CapabilityTally.INSTANCE.record(botId, PrivilegedCapability.FORCED_PICKUP, true);
        CapabilityTally.INSTANCE.record(botId, PrivilegedCapability.HIDDEN_BLOCK_SCAN, false);

        CapabilityTally.INSTANCE.reset(botId);

        CapabilityTally.Snapshot snapshot = CapabilityTally.INSTANCE.snapshot(botId);
        assertEquals(0, snapshot.forcedPickupsAllowed());
        assertEquals(0, snapshot.denied());
    }

    @Test
    void snapshotDoesNotResetCounts() {
        CapabilityTally.INSTANCE.record(botId, PrivilegedCapability.FORCED_PICKUP, true);

        CapabilityTally.INSTANCE.snapshot(botId);
        CapabilityTally.Snapshot second = CapabilityTally.INSTANCE.snapshot(botId);

        assertEquals(1, second.forcedPickupsAllowed());
    }

    @Test
    void differentBotsAreTrackedIndependently() {
        UUID otherBot = UUID.randomUUID();
        try {
            CapabilityTally.INSTANCE.record(botId, PrivilegedCapability.FORCED_PICKUP, true);

            assertEquals(1, CapabilityTally.INSTANCE.snapshot(botId).forcedPickupsAllowed());
            assertEquals(0, CapabilityTally.INSTANCE.snapshot(otherBot).forcedPickupsAllowed());
        } finally {
            CapabilityTally.INSTANCE.clear(otherBot);
        }
    }
}

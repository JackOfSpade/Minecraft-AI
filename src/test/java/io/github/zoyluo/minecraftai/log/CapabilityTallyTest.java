package io.github.zoyluo.minecraftai.log;

import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers {@code CapabilityTally}, the per-bot accumulator behind {@code gather_summary}'s
 * {@code capability_denials} field (see docs/LOGGING.md "Auditing a gather").
 * {@code CapabilityTally.INSTANCE} is a process-wide singleton, so each test uses its
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
        assertEquals(0, CapabilityTally.INSTANCE.snapshot(botId).denied());
    }

    @Test
    void anyDeniedDecisionIncrementsDeniedRegardlessOfCapability() {
        CapabilityTally.INSTANCE.record(botId, PrivilegedCapability.EMERGENCY_TELEPORT, false);
        CapabilityTally.INSTANCE.record(botId, PrivilegedCapability.HIDDEN_BLOCK_SCAN, false);

        assertEquals(2, CapabilityTally.INSTANCE.snapshot(botId).denied());
    }

    @Test
    void anAllowedDecisionIsNotADenial() {
        // An allowed scan is normal, unprivileged observation once granted, so it must not inflate the count.
        CapabilityTally.INSTANCE.record(botId, PrivilegedCapability.HIDDEN_BLOCK_SCAN, true);

        assertEquals(0, CapabilityTally.INSTANCE.snapshot(botId).denied());
    }

    @Test
    void resetZeroesCountsForANewTaskRun() {
        CapabilityTally.INSTANCE.record(botId, PrivilegedCapability.HIDDEN_BLOCK_SCAN, false);

        CapabilityTally.INSTANCE.reset(botId);

        assertEquals(0, CapabilityTally.INSTANCE.snapshot(botId).denied());
    }

    @Test
    void snapshotDoesNotResetCounts() {
        CapabilityTally.INSTANCE.record(botId, PrivilegedCapability.HIDDEN_BLOCK_SCAN, false);

        CapabilityTally.INSTANCE.snapshot(botId);
        CapabilityTally.Snapshot second = CapabilityTally.INSTANCE.snapshot(botId);

        assertEquals(1, second.denied());
    }

    @Test
    void differentBotsAreTrackedIndependently() {
        UUID otherBot = UUID.randomUUID();
        try {
            CapabilityTally.INSTANCE.record(botId, PrivilegedCapability.HIDDEN_BLOCK_SCAN, false);

            assertEquals(1, CapabilityTally.INSTANCE.snapshot(botId).denied());
            assertEquals(0, CapabilityTally.INSTANCE.snapshot(otherBot).denied());
        } finally {
            CapabilityTally.INSTANCE.clear(otherBot);
        }
    }
}

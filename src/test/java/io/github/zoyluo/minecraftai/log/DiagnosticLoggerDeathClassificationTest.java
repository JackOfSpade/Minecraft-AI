package io.github.zoyluo.minecraftai.log;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.entity.Entity;

/**
 * Entity.isAlive() goes false both for a real death and for a plain removal (chunk unload,
 * despawn, the player quitting the world). DiagnosticLogger.isRealDeath is the pure classifier
 * that tells those apart so only a real death is logged as diag_bot_died -- see the real-log case
 * this fixes: Moss was removed ~30ms before server_stopping when the user quit the world, at
 * 20/20 hp, and that used to be misreported as diag_bot_died.
 */
final class DiagnosticLoggerDeathClassificationTest {
    @Test
    void zeroHealthIsARealDeath() {
        assertTrue(DiagnosticLogger.isRealDeath(0.0F, false, null));
    }

    @Test
    void negativeHealthIsARealDeath() {
        assertTrue(DiagnosticLogger.isRealDeath(-1.0F, true, Entity.RemovalReason.DISCARDED));
    }

    @Test
    void killedRemovalWithPositiveHealthIsStillARealDeath() {
        // Health can already read stale/positive by the time this is sampled; an explicit KILLED
        // removal reason is itself sufficient (mirrors DangerWatcher.scanBot's own death check).
        assertTrue(DiagnosticLogger.isRealDeath(20.0F, true, Entity.RemovalReason.KILLED));
    }

    @Test
    void fullHealthQuitRemovalIsNotADeath() {
        // The real bug: bot quit the world at full health, removalReason was not KILLED.
        assertFalse(DiagnosticLogger.isRealDeath(20.0F, true, Entity.RemovalReason.DISCARDED));
    }

    @Test
    void fullHealthChunkUnloadIsNotADeath() {
        assertFalse(DiagnosticLogger.isRealDeath(20.0F, true, Entity.RemovalReason.UNLOADED_TO_CHUNK));
    }

    @Test
    void fullHealthRemovalWithUnknownReasonIsNotADeath() {
        assertFalse(DiagnosticLogger.isRealDeath(20.0F, true, null));
    }
}

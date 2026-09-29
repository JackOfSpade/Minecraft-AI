package io.github.zoyluo.minecraftai.log;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DiagnosticSnapshotGateTest {
    private static final UUID BOT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final int CADENCE = 40;

    private static DiagnosticSnapshotGate.Reading reading(int x, int y, int z, String state, String loadout) {
        return new DiagnosticSnapshotGate.Reading(x, y, z, state, loadout);
    }

    private static final DiagnosticSnapshotGate.Reading IDLE = reading(10, 64, 10, "idle", "20.0|20|empty|empty");

    @Test
    void firstSnapshotIsAlwaysWritten() {
        DiagnosticSnapshotGate gate = new DiagnosticSnapshotGate();
        DiagnosticSnapshotGate.Decision d = gate.decide(BOT, IDLE, 40);
        assertTrue(d.emit());
        assertEquals("first", d.reason());
    }

    @Test
    void anIdleBotWritesOnlyHeartbeatsEveryThirtySeconds() {
        DiagnosticSnapshotGate gate = new DiagnosticSnapshotGate();
        int written = 0;
        int total = 0;
        int lastHeartbeatTick = 0;
        for (int tick = 40; tick <= 40 + 72_000; tick += CADENCE) { // one hour
            total++;
            DiagnosticSnapshotGate.Decision d = gate.decide(BOT, IDLE, tick);
            if (d.emit()) {
                written++;
                if (written > 1) {
                    assertEquals("heartbeat", d.reason());
                    assertEquals(DiagnosticSnapshotGate.HEARTBEAT_TICKS, tick - lastHeartbeatTick);
                    assertEquals(DiagnosticSnapshotGate.HEARTBEAT_TICKS / CADENCE - 1, d.suppressedSinceLast());
                }
                lastHeartbeatTick = tick;
            }
        }
        assertEquals(1 + 72_000 / DiagnosticSnapshotGate.HEARTBEAT_TICKS, written);
        assertTrue(written * 10 < total, "idle output must drop by an order of magnitude: " + written + "/" + total);
    }

    @Test
    void aTaskOrPhaseChangeIsWrittenAtTheNextCadenceTick() {
        DiagnosticSnapshotGate gate = new DiagnosticSnapshotGate();
        gate.decide(BOT, IDLE, 40);
        DiagnosticSnapshotGate.Decision d = gate.decide(BOT,
                reading(10, 64, 10, "gather|RUNNING|SURVEY", IDLE.loadout()), 80);
        assertTrue(d.emit());
        assertEquals("state", d.reason());
    }

    @Test
    void movingAtLeastThreeBlocksIsWrittenAfterTheSoftInterval() {
        DiagnosticSnapshotGate gate = new DiagnosticSnapshotGate();
        gate.decide(BOT, IDLE, 40);
        DiagnosticSnapshotGate.Reading moved = reading(13, 64, 10, IDLE.state(), IDLE.loadout());
        assertFalse(gate.decide(BOT, moved, 80).emit(), "soft changes wait for the soft interval");
        assertFalse(gate.decide(BOT, moved, 120).emit());
        DiagnosticSnapshotGate.Decision d = gate.decide(BOT, moved, 40 + DiagnosticSnapshotGate.MIN_SOFT_TICKS);
        assertTrue(d.emit());
        assertEquals("moved", d.reason());
        assertEquals(2, d.suppressedSinceLast());
    }

    @Test
    void smallJitterAndUnchangedStateStayQuiet() {
        DiagnosticSnapshotGate gate = new DiagnosticSnapshotGate();
        gate.decide(BOT, IDLE, 40);
        for (int tick = 80; tick < 40 + DiagnosticSnapshotGate.HEARTBEAT_TICKS; tick += CADENCE) {
            assertFalse(gate.decide(BOT, reading(11, 64, 11, IDLE.state(), IDLE.loadout()), tick).emit());
        }
    }

    @Test
    void loadoutChangesAreWrittenButRateLimited() {
        DiagnosticSnapshotGate gate = new DiagnosticSnapshotGate();
        gate.decide(BOT, IDLE, 40);
        int written = 0;
        for (int i = 1; i <= 30; i++) { // inventory changes on every check for 60 s
            int tick = 40 + i * CADENCE;
            DiagnosticSnapshotGate.Decision d = gate.decide(BOT,
                    reading(10, 64, 10, IDLE.state(), "20.0|20|empty|minecraft:dirtx" + i), tick);
            if (d.emit()) {
                written++;
                assertEquals("loadout", d.reason());
            }
        }
        assertTrue(written >= 10 && written <= 13, "expected roughly one per 5 s over 60 s, got " + written);
    }

    @Test
    void clearForgetsTheBotAndCounterResetsAreHandled() {
        DiagnosticSnapshotGate gate = new DiagnosticSnapshotGate();
        gate.decide(BOT, IDLE, 4000);
        gate.clear(BOT);
        assertEquals("first", gate.decide(BOT, IDLE, 4040).reason());
        // The tick counter going backwards (world reload) must not stall the bot's snapshots for good.
        assertEquals("first", gate.decide(BOT, IDLE, 40).reason());
    }
}

package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The allocator's cost on the server thread: about 500 known structures and 64 live bots with a player riding through them
 * (so the order is recomputed on nearly every pass, the worst case). The target is under 0.2 ms per server tick on average; the
 * assertion leaves room for a loaded build machine, the measured value is printed.
 */
class AllocatorCostTest {

    @Test
    void theAllocatorCostsLessThanAFractionOfAMillisecondPerTickWithFiveHundredStructuresAnd64LiveBots() {
        Rig rig = new Rig(new InMemoryStorage(false), 5);
        rig.cfg.processing.maxLiveBots = 64;
        rig.cfg.processing.maxBotsPerTick = 8;
        rig.cfg.dormancy.distanceBlocks = 2000.0;
        rig.bots.relevanceRadius = 400.0; // 25 blocks of margin on top of a 16-chunk simulation distance are covered by the extra chunks
        rig.bots.players.add(new BotGateway.PlayerPos(Rig.OVERWORLD, 800, 70, 640));

        // 25 x 20 = 500 structures on a 64 block grid: the player rides through the middle of them.
        rig.feed(500, i -> Rig.structure("minecraft:pillager_outpost", (i % 25) * 4, (i / 25) * 4));
        rig.run(600);
        assertEquals(64, rig.engine.stats().liveBots(), "the budget is used up");

        int candidatesInReach = 0;
        for (int i = 0; i < 500; i++) {
            double dx = Math.max(0, Math.max((i % 25) * 64 - 800, 800 - ((i % 25) * 64 + 47)));
            double dz = Math.max(0, Math.max((i / 25) * 64 - 640, 640 - ((i / 25) * 64 + 47)));
            if (Math.sqrt(dx * dx + dz * dz) <= 432) {
                candidatesInReach++;
            }
        }
        assertTrue(candidatesInReach > 100, "sanity: many structures are in reach at any time: " + candidatesInReach);

        long nanosBefore = rig.engine.allocationNanos();
        long computationsBefore = rig.engine.allocationComputations();
        long passesBefore = rig.engine.allocationPasses();
        int ticks = 4000;
        double x = 800;
        for (int t = 0; t < ticks; t++) {
            x += 0.6 * Math.sin(t / 400.0 * Math.PI) + (t < 2000 ? 0.4 : -0.4); // a wandering ride of about 8 blocks per second
            rig.bots.players.set(0, new BotGateway.PlayerPos(Rig.OVERWORLD, x, 70, 640 + 30 * Math.sin(t / 300.0)));
            rig.run(1);
        }
        long nanos = rig.engine.allocationNanos() - nanosBefore;
        long computations = rig.engine.allocationComputations() - computationsBefore;
        long passes = rig.engine.allocationPasses() - passesBefore;
        double perTickMicros = nanos / 1000.0 / ticks;
        System.out.printf("allocator cost: %.1f microseconds per server tick on average over %d ticks (%d passes, %d recomputations of the order, %d structures known, %d live bots)%n",
                perTickMicros, ticks, passes, computations, rig.store.recordCount(), rig.engine.stats().liveBots());
        assertTrue(passes > 100 && computations > 50, "the ride really changed the order: " + computations + " recomputations");
        assertTrue(perTickMicros < 1000.0, "the allocator must stay well below a millisecond per tick, measured " + perTickMicros + " us");
        // and the population followed the rider without ever going over the budget or creating a farm
        for (BotRecord b : rig.store.allBots()) {
            assertTrue(b.state != BotState.DEAD);
        }
        assertTrue(rig.engine.stats().liveBots() <= 64);
    }
}

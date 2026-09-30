package dev.spawnbotswrapper.inhabitants.store;

import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static dev.spawnbotswrapper.inhabitants.store.StoreFixtures.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How much the store grows with many bots, before and after "only seen bots are kept while they are away": 2000 structures
 * with 3 bots each, every bot carrying a full profile and a full saved state. Before, every bot that went dormant was kept
 * with its state; after, only the ones a player saw (a tenth here) are, the others are deleted without a record. The numbers
 * are printed; the assertion only pins the direction.
 */
class StoreSizeReportTest {

    private static BotSnapshot fullSnapshot(int seed) {
        BotSnapshot s = new BotSnapshot();
        s.health = 14.5f;
        s.foodLevel = 17;
        s.saturation = 3.5f;
        s.xpLevel = seed % 30;
        for (int slot : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 36, 37, 38, 39, 40}) {
            s.stacks.add(new BotSnapshot.Entry(slot, "{\"id\":\"minecraft:diamond_sword\",\"count\":1,\"components\":"
                    + "{\"minecraft:damage\":" + (seed % 100) + ",\"minecraft:enchantments\":{\"minecraft:sharpness\":4,"
                    + "\"minecraft:unbreaking\":3}}}"));
        }
        s.effects.add("{\"id\":\"minecraft:speed\",\"amplifier\":1,\"duration\":1200}");
        return s;
    }

    private static Map<StructureKey, StructureRecord> world(int structures, int seenEveryNth, boolean keepUnseen) {
        Map<StructureKey, StructureRecord> records = new LinkedHashMap<>();
        int bot = 0;
        for (int i = 0; i < structures; i++) {
            StructureRecord r = populated("Bot_" + (bot++), "Bot_" + (bot++), "Bot_" + (bot++));
            r.plannedBots = 3;
            for (BotRecord b : r.bots) {
                b.state = BotState.DORMANT;
                b.snapshot = fullSnapshot(bot);
                b.seen = (bot % seenEveryNth) == 0;
            }
            if (!keepUnseen) {
                r.bots.removeIf(b -> !b.seen);
            }
            records.put(key(VILLAGE, i, i * 2), r);
        }
        return records;
    }

    private static double writeMillis(Path file, Map<StructureKey, StructureRecord> records) throws IOException {
        for (int i = 0; i < 3; i++) {
            PopulationFile.write(file, StructureRecord.CURRENT_DATA_VERSION, records, Map.of()); // warm up
        }
        long t0 = System.nanoTime();
        int runs = 8;
        for (int i = 0; i < runs; i++) {
            PopulationFile.write(file, StructureRecord.CURRENT_DATA_VERSION, records, Map.of());
        }
        return (System.nanoTime() - t0) / 1e6 / runs;
    }

    @Test
    void onlyKeepingSeenBotsShrinksTheStoreAndItsSaveTime(@TempDir Path dir) throws IOException {
        Map<StructureKey, StructureRecord> before = world(2000, 10, true);
        Map<StructureKey, StructureRecord> after = world(2000, 10, false);
        Path beforeFile = dir.resolve("before.json");
        Path afterFile = dir.resolve("after.json");
        double beforeMs = writeMillis(beforeFile, before);
        double afterMs = writeMillis(afterFile, after);
        long beforeBytes = Files.size(beforeFile);
        long afterBytes = Files.size(afterFile);
        int beforeBots = before.values().stream().mapToInt(r -> r.bots.size()).sum();
        int afterBots = after.values().stream().mapToInt(r -> r.bots.size()).sum();
        System.out.printf("store size: %d bots kept before -> %d after; %.1f MB -> %.2f MB; one save %.0f ms -> %.0f ms "
                        + "(%.0f us per stored bot)%n",
                beforeBots, afterBots, beforeBytes / 1e6, afterBytes / 1e6, beforeMs, afterMs, beforeMs * 1000 / beforeBots);
        assertTrue(afterBytes * 5 < beforeBytes, "only seen bots are stored: " + beforeBytes + " -> " + afterBytes);
        assertTrue(afterBots * 5 < beforeBots);
    }
}

package io.github.zoyluo.minecraftai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

final class ContainerLedgerTest {
    private static final String OVERWORLD = "minecraft:overworld";

    private static ContainerLedger.Entry entry(String dimension, int x, Map<String, Integer> items, int free, long time) {
        return new ContainerLedger.Entry(dimension, new BlockPos(x, 64, 0), "minecraft:chest", items, free, 27, time);
    }

    @Test
    void findRanksEnoughFirstThenNearestAndIgnoresOtherItemsAndDimensions() {
        ContainerLedger ledger = new ContainerLedger();
        ledger.record(entry(OVERWORLD, 2, Map.of("minecraft:oak_log", 3), 20, 10));
        ledger.record(entry(OVERWORLD, 30, Map.of("minecraft:oak_log", 40), 20, 10));
        ledger.record(entry(OVERWORLD, 4, Map.of("minecraft:coal", 9), 20, 10));
        ledger.record(entry("minecraft:the_nether", 1, Map.of("minecraft:oak_log", 64), 0, 10));

        List<ContainerLedger.Entry> hits = ledger.find(OVERWORLD, "minecraft:oak_log", BlockPos.ZERO, 16, 3);

        assertEquals(2, hits.size());
        assertEquals(30, hits.get(0).pos().getX(), "the chest with enough beats a nearer one with too little");
        assertEquals(2, hits.get(1).pos().getX());
    }

    @Test
    void reRecordingReplacesTheEntryAndForgetDropsIt() {
        ContainerLedger ledger = new ContainerLedger();
        ledger.record(entry(OVERWORLD, 2, Map.of("minecraft:oak_log", 3), 20, 10));
        ledger.record(entry(OVERWORLD, 2, Map.of("minecraft:coal", 1), 21, 50));

        assertEquals(1, ledger.size());
        assertEquals(0, ledger.get(OVERWORLD, new BlockPos(2, 64, 0)).orElseThrow().count("minecraft:oak_log"));
        assertTrue(ledger.forget(OVERWORLD, new BlockPos(2, 64, 0)));
        assertTrue(ledger.isEmpty());
        assertFalse(ledger.forget(OVERWORLD, new BlockPos(2, 64, 0)));
    }

    @Test
    void withRoomSkipsFullContainersAndTheCapEvictsTheStalestEntry() {
        ContainerLedger ledger = new ContainerLedger();
        ledger.record(entry(OVERWORLD, 1, Map.of(), 0, 5));
        ledger.record(entry(OVERWORLD, 2, Map.of(), 3, 6));
        assertEquals(List.of(new BlockPos(2, 64, 0)),
                ledger.withRoom(OVERWORLD, BlockPos.ZERO, 5).stream().map(ContainerLedger.Entry::pos).toList());

        for (int i = 0; i < ContainerLedger.MAX_ENTRIES; i++) {
            ledger.record(entry(OVERWORLD, 100 + i, Map.of(), 3, 1000 + i));
        }
        assertEquals(ContainerLedger.MAX_ENTRIES, ledger.size());
        assertTrue(ledger.get(OVERWORLD, new BlockPos(1, 64, 0)).isEmpty(), "the oldest entry was evicted");
    }

    @Test
    void survivesAnNbtRoundTripThroughBotMemory() {
        BotMemory memory = new BotMemory();
        memory.containers().record(entry(OVERWORLD, 7, Map.of("minecraft:iron_ingot", 12), 9, 1234));

        BotMemory restored = new BotMemory();
        restored.load(memory.toNbt());

        var loaded = restored.containers().get(OVERWORLD, new BlockPos(7, 64, 0)).orElseThrow();
        assertEquals(12, loaded.count("minecraft:iron_ingot"));
        assertEquals(9, loaded.freeSlots());
        assertEquals(27, loaded.totalSlots());
        assertEquals(1234L, loaded.lastVerified());
    }

    @Test
    void anEmptyLedgerAddsNothingToTheMemoryNbtAndNearestLinesIsNull() {
        BotMemory memory = new BotMemory();
        CompoundTag root = memory.toNbt();
        assertFalse(root.contains("containerLedger"));
        assertNull(memory.containers().nearestLines(OVERWORLD, BlockPos.ZERO, 0, 3));
    }

    @Test
    void describeIsShortAndCarriesCountsFreeSlotsAndAge() {
        String text = ContainerLedger.describe(
                entry(OVERWORLD, 2, Map.of("minecraft:oak_log", 40, "minecraft:coal", 3), 20, 0), 20L * 120);
        assertTrue(text.contains("oak_log x40"), text);
        assertTrue(text.contains("20/27 slots free"), text);
        assertTrue(text.contains("2min ago"), text);
    }
}

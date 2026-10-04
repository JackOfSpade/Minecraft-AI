package io.github.zoyluo.minecraftai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
    void withRoomSkipsRecentlyFullContainersAndTheCapEvictsTheStalestEntry() {
        ContainerLedger ledger = new ContainerLedger();
        ledger.record(entry(OVERWORLD, 1, Map.of(), 0, 5));
        ledger.record(entry(OVERWORLD, 2, Map.of(), 3, 6));
        assertEquals(List.of(new BlockPos(2, 64, 0)),
                ledger.withRoom(OVERWORLD, BlockPos.ZERO, 100L, 5, e -> true).stream().map(ContainerLedger.Entry::pos).toList());

        for (int i = 0; i < ContainerLedger.MAX_ENTRIES; i++) {
            ledger.record(entry(OVERWORLD, 100 + i, Map.of(), 3, 1000 + i));
        }
        assertEquals(ContainerLedger.MAX_ENTRIES, ledger.size());
        assertTrue(ledger.get(OVERWORLD, new BlockPos(1, 64, 0)).isEmpty(), "the oldest entry was evicted");
    }

    @Test
    void aFullFlagFadesSoAPlayerEmptiedChestIsTriedAgain() {
        ContainerLedger ledger = new ContainerLedger();
        ContainerLedger.Entry full = entry(OVERWORLD, 1, Map.of("minecraft:cobblestone", 1728), 0, 1000);
        ledger.record(full);

        assertTrue(full.full());
        assertTrue(full.knownFull(1000L + ContainerLedger.FULL_TRUST_TICKS), "still believed at the edge of the window");
        assertFalse(full.knownFull(1001L + ContainerLedger.FULL_TRUST_TICKS), "the flag fades after the trust window");
        assertTrue(ledger.withRoom(OVERWORLD, BlockPos.ZERO, 1000L + 10, 5, e -> true).isEmpty());
        assertEquals(1, ledger.withRoom(OVERWORLD, BlockPos.ZERO, 1001L + ContainerLedger.FULL_TRUST_TICKS, 5, e -> true).size(),
                "an old full observation no longer excludes the container");
        assertFalse(entry(OVERWORLD, 2, Map.of(), 3, 1000).knownFull(1000L), "a container with room is never full");
    }

    @Test
    void findAndWithRoomApplyTheAcceptFilterBeforeTheLimit() {
        ContainerLedger ledger = new ContainerLedger();
        ledger.record(entry(OVERWORLD, 1, Map.of("minecraft:coal", 4), 3, 10));
        ledger.record(entry(OVERWORLD, 500, Map.of("minecraft:coal", 4), 3, 10));
        java.util.function.Predicate<ContainerLedger.Entry> near = e -> e.pos().getX() < 100;

        assertEquals(List.of(new BlockPos(1, 64, 0)), ledger.find(OVERWORLD, "minecraft:coal", BlockPos.ZERO, 1, 1, near)
                .stream().map(ContainerLedger.Entry::pos).toList());
        assertEquals(List.of(new BlockPos(1, 64, 0)), ledger.withRoom(OVERWORLD, BlockPos.ZERO, 50L, 1, near)
                .stream().map(ContainerLedger.Entry::pos).toList());
    }

    @Test
    void roundTripsThroughTheRealSaveAndLoadStringPath() {
        java.util.UUID bot = java.util.UUID.fromString("00000000-0000-0000-0000-00000000c0de");
        BotMemoryStore.INSTANCE.remove(bot);
        try {
            ContainerLedger source = BotMemoryStore.INSTANCE.of(bot).containers();
            source.record(entry(OVERWORLD, 7, Map.of("minecraft:iron_ingot", 12, "minecraft:oak_log", 3), 9, 1234));
            source.record(new ContainerLedger.Entry("minecraft:the_nether", new BlockPos(-5, 70, 9), "minecraft:barrel",
                    Map.of(), 27, 27, 99));
            String snbt = BotMemoryStore.INSTANCE.saveString(bot);
            assertTrue(snbt.contains("containerLedger"), snbt);

            BotMemoryStore.INSTANCE.remove(bot);
            assertTrue(BotMemoryStore.INSTANCE.of(bot).containers().isEmpty());
            BotMemoryStore.INSTANCE.loadString(bot, snbt);

            ContainerLedger loaded = BotMemoryStore.INSTANCE.of(bot).containers();
            assertEquals(2, loaded.size());
            ContainerLedger.Entry chest = loaded.get(OVERWORLD, new BlockPos(7, 64, 0)).orElseThrow();
            assertEquals(12, chest.count("minecraft:iron_ingot"));
            assertEquals(3, chest.count("minecraft:oak_log"));
            assertEquals("minecraft:chest", chest.block());
            assertEquals(9, chest.freeSlots());
            assertEquals(27, chest.totalSlots());
            assertEquals(1234L, chest.lastVerified());
            ContainerLedger.Entry barrel = loaded.get("minecraft:the_nether", new BlockPos(-5, 70, 9)).orElseThrow();
            assertEquals("minecraft:barrel", barrel.block());
            assertTrue(barrel.items().isEmpty());
            assertEquals(27, barrel.freeSlots());
        } finally {
            BotMemoryStore.INSTANCE.remove(bot);
        }
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
    void loadEnforcesTheSameEntryAndItemLimitsAsLiveRecording() {
        ListTag saved = new ListTag();
        for (int index = 0; index < ContainerLedger.MAX_ENTRIES + 4; index++) {
            CompoundTag container = new CompoundTag();
            container.putString("dimension", OVERWORLD);
            container.putInt("x", index);
            container.putInt("y", 64);
            container.putInt("z", 0);
            container.putString("block", "minecraft:chest");
            container.putInt("free", 1);
            container.putInt("slots", 27);
            container.putLong("verified", index);
            CompoundTag items = new CompoundTag();
            for (int item = 0; item < 70; item++) {
                items.putInt("minecraft:item_" + item, 1);
            }
            container.put("items", items);
            saved.add(container);
        }

        ContainerLedger ledger = new ContainerLedger();
        ledger.load(saved);

        assertEquals(ContainerLedger.MAX_ENTRIES, ledger.size());
        assertTrue(ledger.get(OVERWORLD, new BlockPos(3, 64, 0)).isEmpty(),
                "an oversized persisted list keeps the recent tail without parsing its stale prefix");
        assertTrue(ledger.get(OVERWORLD, new BlockPos(4, 64, 0)).isPresent(),
                "the first record in the retained recent tail is available");
        assertEquals(64, ledger.get(OVERWORLD,
                new BlockPos(ContainerLedger.MAX_ENTRIES + 3, 64, 0)).orElseThrow().items().size(),
                "loading must not retain more item kinds than the persisted representation writes");
    }

    @Test
    void loadSaturatesMalformedPositiveItemCountsAtIntMax() {
        CompoundTag container = new CompoundTag();
        container.putString("dimension", OVERWORLD);
        container.putInt("x", 4);
        container.putInt("y", 64);
        container.putInt("z", 0);
        container.putString("block", "minecraft:chest");
        container.putInt("free", 2);
        container.putInt("slots", 27);
        CompoundTag items = new CompoundTag();
        items.putInt("minecraft:diamond", Integer.MAX_VALUE);
        items.putInt("minecraft:emerald", 1);
        container.put("items", items);
        ListTag saved = new ListTag();
        saved.add(container);

        ContainerLedger ledger = new ContainerLedger();
        ledger.load(saved);

        assertEquals(Integer.MAX_VALUE,
                ledger.get(OVERWORLD, new BlockPos(4, 64, 0)).orElseThrow().totalItems());
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

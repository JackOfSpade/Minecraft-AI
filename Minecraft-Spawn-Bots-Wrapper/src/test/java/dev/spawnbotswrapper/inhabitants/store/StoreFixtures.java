package dev.spawnbotswrapper.inhabitants.store;

import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.AttributeMod;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.Behavior;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.ItemSpec;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.Loadout;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.Op;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.PlacedItem;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.Slot;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.Stance;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.Vitals;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.Waypoint;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.WalkType;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Builders for realistic records and field-by-field comparison, so round-trip tests cannot pass by accident. */
final class StoreFixtures {
    static final String OVERWORLD = "minecraft:overworld";
    static final String NETHER = "minecraft:the_nether";
    static final String VILLAGE = "minecraft:village_plains";
    static final String OUTPOST = "minecraft:pillager_outpost";

    private StoreFixtures() {
    }

    static StructureKey key(String structureId, int chunkX, int chunkZ) {
        return new StructureKey(OVERWORLD, structureId, chunkX, chunkZ);
    }

    static StructureKey key(int chunkX, int chunkZ) {
        return key(VILLAGE, chunkX, chunkZ);
    }

    /** A profile that exercises every part: all slot kinds, enchantments, potion, damage, attributes, waypoints. */
    static BotProfile fullProfile(long seed) {
        List<PlacedItem> items = List.of(
                new PlacedItem(Slot.HEAD, 0, new ItemSpec("minecraft:diamond_helmet", 1,
                        Map.of("minecraft:protection", 4, "minecraft:unbreaking", 3), 0.25, null)),
                new PlacedItem(Slot.CHEST, 0, ItemSpec.of("minecraft:iron_chestplate")),
                new PlacedItem(Slot.HOTBAR, 0, new ItemSpec("minecraft:netherite_sword", 1,
                        Map.of("minecraft:sharpness", 5), 0.5, null)),
                new PlacedItem(Slot.HOTBAR, 1, new ItemSpec("minecraft:splash_potion", 3, Map.of(), 0.0,
                        "minecraft:strong_healing")),
                new PlacedItem(Slot.OFFHAND, 0, ItemSpec.of("minecraft:shield")),
                new PlacedItem(Slot.INVENTORY, -1, ItemSpec.of("minecraft:golden_apple", 12)));
        Vitals vitals = new Vitals(0.6, 17, Map.of(
                "minecraft:max_health", new AttributeMod(Op.ADD_VALUE, 10.0),
                "minecraft:knockback_resistance", new AttributeMod(Op.ADD_MULTIPLIED_BASE, 0.35)));
        Behavior behavior = new Behavior(Stance.PATROL_PINGPONG, false, WalkType.SPRINT, 12.5, 3,
                List.of(new Waypoint(1.5, 64.0, -3.25), new Waypoint(-10.125, 70.0, 0.1), new Waypoint(4.0, 64.5, 8.0)));
        return new BotProfile(BotProfile.CURRENT_VERSION, seed, "duelist", new Loadout(items), vitals, behavior);
    }

    static BotRecord plannedBot(int index, String name, long seed) {
        return new BotRecord(index, name, seed);
    }

    static BotRecord spawnedBot(int index, String name, long seed) {
        BotRecord b = new BotRecord(index, name, seed);
        b.uuid = "123e4567-e89b-12d3-a456-4266141740" + String.format("%02d", index);
        b.state = BotState.SPAWNED;
        b.x = 100.5 + index;
        b.y = 64.0;
        b.z = -20.25;
        b.yaw = 90.5f;
        b.spawnAttempts = 2;
        b.spawnedAtMillis = 1_700_000_000_000L + index;
        b.profile = fullProfile(seed);
        b.profileVersion = BotProfile.CURRENT_VERSION;
        b.profileApplied = true;
        return b;
    }

    static StructureRecord pending(String... botNames) {
        StructureRecord r = new StructureRecord();
        r.status = StructureStatus.OCCUPIED_PENDING;
        r.source = "DETERMINISTIC";
        r.occupiedChance = 0.65;
        r.roll = 0.123456789;
        r.structureSeed = 0x1234_5678_9ABC_DEF0L;
        r.plannedBots = botNames.length;
        r.attempts = 1;
        r.rolledAtMillis = 1_700_000_000_123L;
        r.bounds = new int[]{-10, 60, 20, 30, 90, 70};
        for (int i = 0; i < botNames.length; i++) {
            r.bots.add(plannedBot(i, botNames[i], 1000L + i));
        }
        return r;
    }

    static StructureRecord populated(String... botNames) {
        StructureRecord r = pending(botNames);
        r.status = StructureStatus.POPULATED;
        r.note = "all bots placed";
        r.bots.clear();
        for (int i = 0; i < botNames.length; i++) {
            r.bots.add(spawnedBot(i, botNames[i], 5000L + i));
        }
        return r;
    }

    static StructureRecord gaveUp() {
        StructureRecord r = pending("Nobody_1");
        r.status = StructureStatus.GAVE_UP;
        r.note = "no valid position";
        r.bots.get(0).state = BotState.FAILED;
        r.bots.get(0).failure = "no position";
        return r;
    }

    static void assertRecordEquals(StructureRecord expected, StructureRecord actual) {
        assertEquals(expected.dataVersion, actual.dataVersion, "dataVersion");
        assertEquals(expected.status, actual.status, "status");
        assertEquals(expected.source, actual.source, "source");
        assertEquals(expected.occupiedChance, actual.occupiedChance, "occupiedChance");
        assertEquals(expected.roll, actual.roll, "roll");
        assertEquals(expected.structureSeed, actual.structureSeed, "structureSeed");
        assertEquals(expected.plannedBots, actual.plannedBots, "plannedBots");
        assertEquals(expected.attempts, actual.attempts, "attempts");
        assertEquals(expected.rolledAtMillis, actual.rolledAtMillis, "rolledAtMillis");
        assertEquals(expected.note, actual.note, "note");
        assertArrayEquals(expected.bounds, actual.bounds, "bounds");
        assertEquals(expected.bots.size(), actual.bots.size(), "bot count");
        for (int i = 0; i < expected.bots.size(); i++) {
            assertBotEquals(expected.bots.get(i), actual.bots.get(i));
        }
    }

    static void assertBotEquals(BotRecord e, BotRecord a) {
        String who = "bot " + e.name;
        assertEquals(e.index, a.index, who + " index");
        assertEquals(e.name, a.name, who + " name");
        assertEquals(e.uuid, a.uuid, who + " uuid");
        assertEquals(e.seed, a.seed, who + " seed");
        assertEquals(e.state, a.state, who + " state");
        assertEquals(e.x, a.x, who + " x");
        assertEquals(e.y, a.y, who + " y");
        assertEquals(e.z, a.z, who + " z");
        assertEquals(e.yaw, a.yaw, who + " yaw");
        assertEquals(e.spawnAttempts, a.spawnAttempts, who + " spawnAttempts");
        assertEquals(e.spawnedAtMillis, a.spawnedAtMillis, who + " spawnedAtMillis");
        assertEquals(e.failure, a.failure, who + " failure");
        assertEquals(e.profile, a.profile, who + " profile");
        assertEquals(e.profileVersion, a.profileVersion, who + " profileVersion");
        assertEquals(e.profileApplied, a.profileApplied, who + " profileApplied");
    }

    static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    static List<String> lines(Path file) throws IOException {
        List<String> out = new ArrayList<>(Arrays.asList(read(file).split("\n", -1)));
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    static PopulationStore loaded(Path dir) {
        PopulationStore store = new PopulationStore(dir);
        PopulationStore.LoadReport report = store.load();
        if (!report.usable()) {
            throw new AssertionError("store unexpectedly unusable: " + report.messages());
        }
        return store;
    }
}

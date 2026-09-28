package dev.spawnbotswrapper.inhabitants.command;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.engine.EngineControl.EngineStats;
import dev.spawnbotswrapper.inhabitants.engine.PopulationView.PopulationCounts;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.store.StructureStatus;
import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plain-data builders shared by the command tests. */
final class Fixtures {
    static final String OVERWORLD = "minecraft:overworld";
    static final String UUID_TEXT = "123e4567-e89b-12d3-a456-426614174000";

    private Fixtures() {
    }

    static StructureKey key(String id, int chunkX, int chunkZ) {
        return new StructureKey(OVERWORLD, id, chunkX, chunkZ);
    }

    /** A 64x31x64 structure whose start chunk is (chunkX, chunkZ), made of one piece. */
    static StructureSnapshot snapshot(String id, int chunkX, int chunkZ, boolean newlyGenerated, String... tags) {
        IntBox box = new IntBox(chunkX * 16, 60, chunkZ * 16, chunkX * 16 + 63, 90, chunkZ * 16 + 63);
        return new StructureSnapshot(key(id, chunkX, chunkZ), Set.of(tags), box, List.of(box), newlyGenerated);
    }

    static BotRecord bot(int index, String name, BotState state) {
        BotRecord b = new BotRecord(index, name, 1000L + index);
        b.state = state;
        if (state == BotState.REQUESTED || state == BotState.SPAWNED) {
            b.x = 10.5;
            b.y = 64;
            b.z = -3.25;
            b.yaw = 90f;
            b.uuid = UUID_TEXT;
        }
        return b;
    }

    /** A rolled-occupied record (chance 65%, roll 0.31) with the given bots. */
    static StructureRecord occupied(StructureStatus status, int planned, BotRecord... bots) {
        StructureRecord r = new StructureRecord();
        r.status = status;
        r.source = "RANDOM";
        r.occupiedChance = 0.65;
        r.roll = 0.31;
        r.rollDetailsKept = true;
        r.plannedBots = planned;
        r.bots = new ArrayList<>(List.of(bots));
        return r;
    }

    static StructureRecord populated(BotRecord... bots) {
        return occupied(StructureStatus.POPULATED, bots.length, bots);
    }

    /** A record synthesized from the compact abandoned-key log: no roll details survive. */
    static StructureRecord synthesizedAbandoned() {
        return StructureRecord.abandoned();
    }

    static StructureRecord abandonedRolled(double chance, double roll) {
        StructureRecord r = StructureRecord.abandoned();
        r.occupiedChance = chance;
        r.roll = roll;
        r.rollDetailsKept = true;
        return r;
    }

    static Map.Entry<StructureKey, StructureRecord> entry(StructureKey k, StructureRecord r) {
        return Map.entry(k, r);
    }

    /** A config with no per-structure overrides, so a test sees only the rule it sets. */
    static InhabitantsConfig bareConfig() {
        InhabitantsConfig c = new InhabitantsConfig();
        c.structures = new LinkedHashMap<>();
        c.tags = new LinkedHashMap<>();
        c.exclude = new ArrayList<>();
        return c;
    }

    static PvpBotOperations.Status status(PvpBotOperations.Availability availability, List<String> details,
                                          List<String> warnings) {
        return new PvpBotOperations.Status(availability, "0.0.15", "1.4.0", "0.1.0", "CLASS(pos)",
                "PvP BOT 0.0.15 ok", details, warnings);
    }

    static PvpBotOperations.Status availableStatus() {
        return status(PvpBotOperations.Availability.AVAILABLE, List.of("found BotManager", "found BotSettings"),
                List.of());
    }

    static PvpBotOperations.Status unavailableStatus() {
        return new PvpBotOperations.Status(PvpBotOperations.Availability.UNAVAILABLE, "not installed",
                "not installed", "0.1.0", "NONE", "PvP BOT is not installed", List.of("mod pvp_bot not found"),
                List.of());
    }

    static EngineStats stats() {
        return new EngineStats(120, 118, 40, 38, 2, 3, 1, 37);
    }

    static PopulationCounts counts() {
        return new PopulationCounts(70, 3, 42, 3, 38, 2);
    }

    static BotProfile profile() {
        return new BotProfile(BotProfile.CURRENT_VERSION, 42L, "guard", null, null, null);
    }

    /** True as soon as some line contains {@code fragment}. */
    static boolean contains(List<String> lines, String fragment) {
        return lines.stream().anyMatch(l -> l.contains(fragment));
    }

    /** As {@link #contains}, but strips markup first: for output a test never ran through {@link Markup#strip}. */
    static boolean containsStripped(List<String> lines, String fragment) {
        return contains(Markup.strip(lines), fragment);
    }
}

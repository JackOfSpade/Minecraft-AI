package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.sample.PersistentDeckStore;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.PopulationStorage;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.store.StructureStatus;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Test double for {@link PopulationStorage} that keeps MEMORY and DISK apart, like the real store does: what
 * the engine mutates lives in memory, and only {@link #saveIfDirty()} / {@link #flush()} copy it to "disk".
 * That makes crash behaviour testable: {@link #crashCopy()} yields a storage holding only what had been
 * persisted, {@link #persisted(StructureKey)} shows what a crash at this very moment would preserve.
 */
final class InMemoryStorage implements PopulationStorage {

    private final boolean trackDisk;
    private final Map<StructureKey, StructureRecord> live = new LinkedHashMap<>();
    private Map<StructureKey, StructureRecord> disk = new LinkedHashMap<>();
    private Map<String, PersistentDeckStore.Snapshot> diskDecks = new LinkedHashMap<>();
    private final PersistentDeckStore decks = new PersistentDeckStore();
    private final Map<String, BotLocation> nameIndex = new HashMap<>();
    private boolean indexStale;
    private boolean dirty;

    boolean usable = true;
    boolean failSaves;
    boolean throwOnFind;
    boolean throwOnPut;
    boolean throwOnFindBot;
    boolean throwOnSave;
    boolean throwOnNonAbandoned;

    /** Calls to {@link #saveIfDirty()}, whether or not anything was written. */
    int saveCalls;
    /** Times state was actually copied to disk. */
    int writes;
    int flushCalls;
    int puts;
    /** Called every time state is copied to "disk" (after the copy), to observe what is durable at that moment. */
    Runnable onWrite = () -> {
    };

    InMemoryStorage() {
        this(true);
    }

    /** @param trackDisk false skips the deep copies (big statistical tests do not need the disk view) */
    InMemoryStorage(boolean trackDisk) {
        this.trackDisk = trackDisk;
    }

    // ------------------------------------------------------------------ test helpers

    /** A storage holding exactly what has been persisted so far (as after a crash + restart). */
    InMemoryStorage crashCopy() {
        InMemoryStorage s = new InMemoryStorage(trackDisk);
        for (Map.Entry<StructureKey, StructureRecord> e : disk.entrySet()) {
            s.live.put(e.getKey(), copy(e.getValue()));
            s.disk.put(e.getKey(), copy(e.getValue()));
        }
        s.decks.importSnapshots(diskDecks);
        s.diskDecks = new LinkedHashMap<>(diskDecks);
        s.indexStale = true;
        return s;
    }

    /** As after a clean shutdown and restart: everything is flushed first. */
    InMemoryStorage cleanRestartCopy() {
        flush();
        return crashCopy();
    }

    /** The record as persisted (not as currently in memory); empty if never saved. */
    Optional<StructureRecord> persisted(StructureKey key) {
        StructureRecord r = disk.get(key);
        return r == null ? Optional.empty() : Optional.of(copy(r));
    }

    int recordCount() {
        return live.size();
    }

    Map<StructureKey, StructureRecord> all() {
        return live;
    }

    List<BotRecord> allBots() {
        List<BotRecord> out = new ArrayList<>();
        for (StructureRecord r : live.values()) {
            out.addAll(r.bots);
        }
        return out;
    }

    static StructureRecord copy(StructureRecord r) {
        StructureRecord c = new StructureRecord();
        c.dataVersion = r.dataVersion;
        c.status = r.status;
        c.source = r.source;
        c.occupiedChance = r.occupiedChance;
        c.roll = r.roll;
        c.structureSeed = r.structureSeed;
        c.plannedBots = r.plannedBots;
        c.attempts = r.attempts;
        c.rolledAtMillis = r.rolledAtMillis;
        c.note = r.note;
        c.bounds = r.bounds == null ? null : r.bounds.clone();
        c.nextBotIndex = r.nextBotIndex;
        for (BotRecord b : r.bots) {
            BotRecord n = new BotRecord(b.index, b.name, b.seed);
            n.uuid = b.uuid;
            n.state = b.state;
            n.x = b.x;
            n.y = b.y;
            n.z = b.z;
            n.yaw = b.yaw;
            n.spawnAttempts = b.spawnAttempts;
            n.spawnedAtMillis = b.spawnedAtMillis;
            n.failure = b.failure;
            n.profile = b.profile;
            n.profileVersion = b.profileVersion;
            n.profileApplied = b.profileApplied;
            n.snapshot = b.snapshot;
            n.seen = b.seen;
            n.firstSeenMillis = b.firstSeenMillis;
            n.lastSeenMillis = b.lastSeenMillis;
            n.dimension = b.dimension;
            n.removing = b.removing;
            c.bots.add(n);
        }
        return c;
    }

    // ------------------------------------------------------------------ PopulationStorage

    @Override
    public boolean usable() {
        return usable;
    }

    @Override
    public void put(StructureKey key, StructureRecord record) {
        if (throwOnPut) {
            throw new IllegalStateException("injected put failure");
        }
        puts++;
        StructureRecord previous = live.put(key, record);
        if (previous != null) {
            indexStale = true;
        } else if (!indexStale) {
            for (BotRecord b : record.bots) {
                if (b.name != null) {
                    nameIndex.put(b.name.toLowerCase(Locale.ROOT), new BotLocation(key, b));
                }
            }
        }
        dirty = true;
    }

    @Override
    public boolean remove(StructureKey key) {
        boolean had = live.remove(key) != null;
        indexStale = true;
        dirty = true;
        return had;
    }

    @Override
    public void markDirty() {
        // Matches the real PopulationStore: markDirty() alone does NOT re-read bot names. A caller that
        // renames/adds/drops a bot in place must also call reindex(key), exactly as production code must.
        dirty = true;
    }

    @Override
    public void reindex(StructureKey key) {
        StructureRecord record = live.get(key);
        if (record == null) {
            return;
        }
        nameIndex.values().removeIf(loc -> loc.structure().equals(key));
        for (BotRecord b : record.bots) {
            if (b.name != null) {
                nameIndex.put(b.name.toLowerCase(Locale.ROOT), new BotLocation(key, b));
            }
        }
    }

    @Override
    public List<Map.Entry<StructureKey, StructureRecord>> nonAbandoned() {
        if (throwOnNonAbandoned) {
            throw new IllegalStateException("injected nonAbandoned failure");
        }
        List<Map.Entry<StructureKey, StructureRecord>> out = new ArrayList<>();
        for (Map.Entry<StructureKey, StructureRecord> e : live.entrySet()) {
            if (e.getValue().status != StructureStatus.ABANDONED) {
                out.add(Map.entry(e.getKey(), e.getValue()));
            }
        }
        return out;
    }

    @Override
    public PersistentDeckStore decks() {
        return decks;
    }

    @Override
    public boolean saveIfDirty() {
        saveCalls++;
        if (throwOnSave) {
            throw new IllegalStateException("injected save failure");
        }
        if (failSaves) {
            return false;
        }
        if (dirty || decks.isDirty()) {
            writeToDisk();
        }
        return true;
    }

    @Override
    public void flush() {
        flushCalls++;
        writeToDisk();
    }

    private void writeToDisk() {
        writes++;
        dirty = false;
        decks.clearDirty();
        if (!trackDisk) {
            return;
        }
        Map<StructureKey, StructureRecord> snapshot = new LinkedHashMap<>();
        for (Map.Entry<StructureKey, StructureRecord> e : live.entrySet()) {
            snapshot.put(e.getKey(), copy(e.getValue()));
        }
        disk = snapshot;
        diskDecks = decks.exportSnapshots();
        onWrite.run();
    }

    // ------------------------------------------------------------------ PopulationView

    @Override
    public Optional<StructureRecord> find(StructureKey key) {
        if (throwOnFind) {
            throw new IllegalStateException("injected find failure");
        }
        return Optional.ofNullable(live.get(key));
    }

    @Override
    public List<Map.Entry<StructureKey, StructureRecord>> nearby(String dimensionId, int chunkX, int chunkZ, int radiusChunks) {
        List<Map.Entry<StructureKey, StructureRecord>> out = new ArrayList<>();
        for (Map.Entry<StructureKey, StructureRecord> e : live.entrySet()) {
            StructureKey k = e.getKey();
            if (k.dimension().equals(dimensionId)
                    && Math.max(Math.abs(k.chunkX() - chunkX), Math.abs(k.chunkZ() - chunkZ)) <= radiusChunks) {
                out.add(Map.entry(k, e.getValue()));
            }
        }
        out.sort((a, b) -> Integer.compare(
                Math.max(Math.abs(a.getKey().chunkX() - chunkX), Math.abs(a.getKey().chunkZ() - chunkZ)),
                Math.max(Math.abs(b.getKey().chunkX() - chunkX), Math.abs(b.getKey().chunkZ() - chunkZ))));
        return out;
    }

    @Override
    public Optional<BotLocation> findBot(String botName) {
        if (throwOnFindBot) {
            throw new IllegalStateException("injected findBot failure");
        }
        if (botName == null) {
            return Optional.empty();
        }
        if (indexStale) {
            nameIndex.clear();
            for (Map.Entry<StructureKey, StructureRecord> e : live.entrySet()) {
                for (BotRecord b : e.getValue().bots) {
                    if (b.name != null) {
                        nameIndex.put(b.name.toLowerCase(Locale.ROOT), new BotLocation(e.getKey(), b));
                    }
                }
            }
            indexStale = false;
        }
        return Optional.ofNullable(nameIndex.get(botName.toLowerCase(Locale.ROOT)));
    }

    @Override
    public PopulationCounts counts() {
        int abandoned = 0;
        int pending = 0;
        int populated = 0;
        int gaveUp = 0;
        int spawned = 0;
        int failed = 0;
        for (StructureRecord r : live.values()) {
            switch (r.status) {
                case ABANDONED -> abandoned++;
                case OCCUPIED_PENDING -> pending++;
                case POPULATED -> populated++;
                case GAVE_UP -> gaveUp++;
            }
            for (BotRecord b : r.bots) {
                switch (b.state) {
                    case SPAWNED -> spawned++;
                    case FAILED -> failed++;
                    default -> {
                    }
                }
            }
        }
        return new PopulationCounts(abandoned, pending, populated, gaveUp, spawned, failed);
    }
}

package dev.spawnbotswrapper.inhabitants.store;

import dev.spawnbotswrapper.inhabitants.sample.PersistentDeckStore;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Persistence of every processed structure, in a directory inside the world save.
 *
 * <pre>
 *   populations.json       { dataVersion, structures: {key: record}, decks: {name: deck} }
 *                          ONLY non-abandoned records; rewritten atomically on save
 *   populations.json.bak   the previous good populations.json
 *   abandoned.keys         append-only, one structure key per line: every abandoned structure
 * </pre>
 * Abandoned structures live in their own compact file because a well-explored world has hundreds of
 * thousands of them: appending a line is cheap enough to do (and fsync) the moment a structure is rolled
 * empty, so that decision survives a crash immediately, while rewriting a huge JSON for each one would
 * not be. Because only the fact is stored, {@link #find} synthesises {@link StructureRecord#abandoned()}
 * for such keys; roll details, notes and bounds of an abandoned structure are not kept, before or after a
 * restart.
 *
 * <h2>Why unreadable data makes the store unusable</h2>
 * The whole point of this store is that a structure is rolled exactly once. If persisted data exists but
 * cannot be read (both populations.json and its backup are damaged, abandoned.keys cannot be read, or the
 * data is from a newer version of the addon), starting over with an empty store would re-roll every
 * structure and duplicate populations that already exist. {@link #usable()} then reports false and the
 * addon must not process structures; nothing is written, so the damaged files are left for a human to
 * fix. A missing directory or missing files simply mean a fresh world.
 *
 * <h2>Crash safety</h2>
 * populations.json is written to a temp file and fsynced, the current file becomes populations.json.bak,
 * and the temp file is moved into place; every step leaves a loadable file behind. Loading tries the main
 * file, then (only when the main file is missing) a complete leftover temp file from an interrupted
 * save, then the backup. A damaged main file that was replaced by the backup is kept as
 * populations.json.corrupt instead of being overwritten. A truncated final line of abandoned.keys (a crash
 * mid-append) is ignored and cut off, never trusted.
 *
 * <h2>What is durable when</h2>
 * Only an abandoned decision is durable on its own (one forced append per {@code put}, so a burst of
 * rolls costs one fsync each). A new occupied record, and every later in-place change, is durable only
 * after {@link #saveIfDirty()}/{@link #flush()}: the engine should save after rolling an occupied structure
 * and before it asks PvP BOT to spawn its bots, otherwise a crash in between leaves live bots that the
 * restarted addon has no record of. A save rewrites the whole populations.json (roughly 60 microseconds
 * per stored bot), which is why it belongs behind a debounce rather than in every tick.
 *
 * <h2>Threading</h2>
 * Server thread only. The public methods are synchronized so a stray second thread cannot corrupt the
 * indexes, but the records handed out are live objects: the engine mutates them in place, then calls
 * {@link #markDirty()} (and {@link #reindex(StructureKey)} if it renamed, added or dropped a bot).
 */
public final class PopulationStore implements PopulationStorage {
    private static final Logger LOG = LoggerFactory.getLogger("pvpbot_inhabitants");

    public static final String POPULATIONS_FILE = "populations.json";
    public static final String BACKUP_FILE = "populations.json.bak";
    public static final String TEMP_FILE = "populations.json.tmp";
    public static final String CORRUPT_FILE = "populations.json.corrupt";
    public static final String ABANDONED_FILE = "abandoned.keys";
    private static final String ABANDONED_TEMP_FILE = "abandoned.keys.tmp";

    /** The populations.json layout version this build writes and the newest it can read. */
    public static final int DATA_VERSION = StructureRecord.CURRENT_DATA_VERSION;

    /** @param usable false when persisted data exists but could not be read; the addon must then not process structures */
    public record LoadReport(boolean usable, List<String> messages) {
    }

    /** Which file the in-memory state came from. */
    public enum LoadSource {
        /** Nothing persisted (or only abandoned.keys): a new world. */
        FRESH,
        MAIN,
        /** populations.json was damaged; populations.json.bak was used. Changes since that backup are lost. */
        BACKUP,
        /** populations.json was missing but a complete temp file from an interrupted save was found. */
        INTERRUPTED_SAVE
    }

    private record Hit(StructureKey key, double distanceSquared, StructureRecord record) {
    }

    private static final Comparator<Hit> NEAREST_FIRST =
            Comparator.comparingDouble(Hit::distanceSquared).thenComparing(h -> h.key().asString());

    private final Path directory;
    private final Path mainFile;
    private final Path backupFile;
    private final Path tempFile;
    private final Path corruptFile;
    private final Path abandonedFile;
    private final Path abandonedTempFile;

    /** Records that are not ABANDONED, in the order they were first stored. */
    private final Map<StructureKey, StructureRecord> active = new LinkedHashMap<>();
    private final ChunkGrid activeGrid = new ChunkGrid();
    private final ChunkGrid abandonedGrid = new ChunkGrid();
    /**
     * Keys that became non-abandoned but are still listed in abandoned.keys. They may only leave the file
     * after populations.json holding their new record is durable; otherwise a crash in between would
     * forget the structure entirely and re-roll it.
     */
    private final Set<StructureKey> deferredAbandonedRemovals = new HashSet<>();
    private final NameIndex names = new NameIndex();
    private final PersistentDeckStore decks = new PersistentDeckStore();

    private boolean loaded;
    private boolean usable;
    private boolean dirty;
    /** True while the file on disk is known to be a good populations.json we loaded or wrote. */
    private boolean mainGood;
    /** abandoned.keys must be rewritten (an append failed, or it holds a tail/conflicts) before appending again. */
    private boolean abandonedRewriteNeeded;
    private LoadSource loadSource = LoadSource.FRESH;
    private LoadReport lastReport = new LoadReport(false, List.of("not loaded yet"));
    private String lastError;
    private boolean refusalLogged;

    /** @param directory e.g. {@code <world>/pvpbot_inhabitants}; created if missing */
    public PopulationStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.mainFile = directory.resolve(POPULATIONS_FILE);
        this.backupFile = directory.resolve(BACKUP_FILE);
        this.tempFile = directory.resolve(TEMP_FILE);
        this.corruptFile = directory.resolve(CORRUPT_FILE);
        this.abandonedFile = directory.resolve(ABANDONED_FILE);
        this.abandonedTempFile = directory.resolve(ABANDONED_TEMP_FILE);
    }

    public Path directory() {
        return directory;
    }

    // ------------------------------------------------------------------ loading

    /**
     * Loads everything from disk, replacing whatever is in memory (unsaved changes included). Never
     * throws; problems are reported in the result. Called implicitly by the first use if the caller
     * did not, so an un-loaded store can never be mistaken for an empty world.
     */
    public synchronized LoadReport load() {
        LoadReport report;
        try {
            report = doLoad();
        } catch (RuntimeException e) {
            // Whatever it was, an unreadable store must read as "refuse to run", never as an empty world.
            report = unusable(new ArrayList<>(), "loading failed unexpectedly (" + e + ")");
        }
        lastReport = report;
        loaded = true;
        return report;
    }

    public synchronized LoadReport lastLoadReport() {
        ensureLoaded();
        return lastReport;
    }

    public synchronized LoadSource loadSource() {
        ensureLoaded();
        return loadSource;
    }

    /** The most recent I/O problem that has not been followed by a fully successful write, if any. */
    public synchronized Optional<String> lastError() {
        return Optional.ofNullable(lastError);
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        LoadReport report = load();
        for (String message : report.messages()) {
            if (report.usable()) {
                LOG.info("{}", message);
            } else {
                LOG.error("{}", message);
            }
        }
    }

    private void reset() {
        active.clear();
        activeGrid.clear();
        abandonedGrid.clear();
        deferredAbandonedRemovals.clear();
        names.clear();
        decks.importSnapshots(null);
        usable = false;
        dirty = false;
        mainGood = false;
        abandonedRewriteNeeded = false;
        loadSource = LoadSource.FRESH;
        lastError = null;
        refusalLogged = false;
    }

    private LoadReport doLoad() {
        reset();
        List<String> messages = new ArrayList<>();
        try {
            Files.createDirectories(directory);
        } catch (IOException | RuntimeException e) {
            return unusable(messages, "the data directory " + directory + " cannot be created or opened (" + e + ")");
        }

        LoadSource source = LoadSource.FRESH;
        PopulationFile.Parsed parsed = null;
        boolean unreadableFound = false;

        PopulationFile.ReadResult main = PopulationFile.read(mainFile);
        switch (main.outcome()) {
            case OK -> {
                parsed = main.parsed();
                source = LoadSource.MAIN;
            }
            case TOO_NEW -> {
                return unusable(messages, newerVersion(POPULATIONS_FILE, main.detail()));
            }
            case INVALID -> {
                unreadableFound = true;
                messages.add(POPULATIONS_FILE + " is unreadable (" + main.detail() + ")");
            }
            case MISSING -> {
            }
        }

        if (parsed == null && main.outcome() == PopulationFile.Outcome.MISSING) {
            // A save moves the old file to .bak before moving the new one in; a crash in that instant
            // leaves no main file but a complete, fsynced temp file that is newer than the backup.
            PopulationFile.ReadResult temp = PopulationFile.read(tempFile);
            switch (temp.outcome()) {
                case OK -> {
                    parsed = temp.parsed();
                    source = LoadSource.INTERRUPTED_SAVE;
                    messages.add(POPULATIONS_FILE + " is missing; using the complete " + TEMP_FILE
                            + " left by an interrupted save");
                }
                case TOO_NEW -> {
                    return unusable(messages, newerVersion(TEMP_FILE, temp.detail()));
                }
                case INVALID -> messages.add("ignored an incomplete " + TEMP_FILE + " left by an interrupted save ("
                        + temp.detail() + ")");
                case MISSING -> {
                }
            }
        } else if (parsed != null && Files.exists(tempFile)) {
            messages.add("ignored a leftover " + TEMP_FILE + " (an interrupted save; " + POPULATIONS_FILE + " is intact)");
        }

        if (parsed == null) {
            PopulationFile.ReadResult backup = PopulationFile.read(backupFile);
            switch (backup.outcome()) {
                case OK -> {
                    parsed = backup.parsed();
                    source = LoadSource.BACKUP;
                    messages.add("recovered from " + BACKUP_FILE + " (written " + modified(backupFile)
                            + "); population changes made after that backup are lost");
                }
                case TOO_NEW -> {
                    return unusable(messages, newerVersion(BACKUP_FILE, backup.detail()));
                }
                case INVALID -> {
                    unreadableFound = true;
                    messages.add(BACKUP_FILE + " is unreadable (" + backup.detail() + ")");
                }
                case MISSING -> {
                }
            }
        }

        if (parsed == null && unreadableFound) {
            return unusable(messages, "persisted population data exists in " + directory + " but neither "
                    + POPULATIONS_FILE + " nor " + BACKUP_FILE + " can be read");
        }

        boolean handEditedAbandoned = false;
        if (parsed != null) {
            for (Map.Entry<StructureKey, StructureRecord> e : parsed.structures().entrySet()) {
                if (e.getValue().status == StructureStatus.ABANDONED) {
                    abandonedGrid.add(e.getKey());
                    handEditedAbandoned = true;
                } else {
                    active.put(e.getKey(), e.getValue());
                    activeGrid.add(e.getKey());
                }
            }
            decks.importSnapshots(parsed.decks());
            messages.addAll(parsed.warnings());
            messages.add("loaded " + active.size() + " structure record(s) and " + parsed.decks().size()
                    + " deck(s) from " + sourceName(source));
        } else {
            messages.add("no " + POPULATIONS_FILE + " found in " + directory + "; starting a new store");
        }

        int[] conflicts = new int[1];
        try {
            AbandonedLog.Loaded log = AbandonedLog.read(abandonedFile, key -> {
                if (active.containsKey(key)) {
                    conflicts[0]++;
                } else {
                    abandonedGrid.add(key);
                }
            });
            messages.add(ABANDONED_FILE + ": " + abandonedGrid.size() + " abandoned structure key(s)");
            if (log.malformed() > 0) {
                messages.add(ABANDONED_FILE + ": skipped " + log.malformed() + " malformed line(s)");
            }
            if (log.truncatedTail() > 0) {
                if (AbandonedLog.truncate(abandonedFile, log.validLength())) {
                    messages.add(ABANDONED_FILE + ": removed an unterminated final line (" + log.truncatedTail()
                            + " byte(s)) left by a crash mid-append; it was not used");
                } else {
                    abandonedRewriteNeeded = true;
                    messages.add(ABANDONED_FILE + ": ignored an unterminated final line (" + log.truncatedTail()
                            + " byte(s)) left by a crash mid-append; it will be dropped at the next rewrite");
                }
            }
        } catch (NoSuchFileException e) {
            if (source != LoadSource.FRESH) {
                messages.add("warning: " + ABANDONED_FILE + " is missing although " + POPULATIONS_FILE
                        + " exists; structures that were abandoned may be rolled again");
            }
        } catch (IOException | RuntimeException e) {
            return unusable(messages, ABANDONED_FILE + " in " + directory + " cannot be read (" + e + ")");
        }
        if (conflicts[0] > 0) {
            abandonedRewriteNeeded = true;
            messages.add(conflicts[0] + " structure(s) are both in " + ABANDONED_FILE + " and have a record; the record was kept");
        }
        if (handEditedAbandoned) {
            abandonedRewriteNeeded = true;
            dirty = true;
            messages.add(POPULATIONS_FILE + " contained ABANDONED records; they were moved to " + ABANDONED_FILE);
        }

        names.rebuild(active);
        if (names.clashes() > 0) {
            messages.add("warning: " + names.clashes() + " duplicate bot name(s), e.g. " + names.clashExamples());
        }

        loadSource = source;
        mainGood = source == LoadSource.MAIN;
        if (source == LoadSource.BACKUP || source == LoadSource.INTERRUPTED_SAVE) {
            dirty = true; // rewrite the main file from the recovered state at the next save
        }
        usable = true;
        return new LoadReport(true, List.copyOf(messages));
    }

    private LoadReport unusable(List<String> messages, String why) {
        reset();
        messages.add("UNUSABLE: " + why + ". The addon will not populate structures until this is resolved, because "
                + "starting over with an empty store would re-roll them and duplicate existing populations. "
                + "Nothing was modified; restore the files or, to knowingly start over, delete them.");
        return new LoadReport(false, List.copyOf(messages));
    }

    private static String newerVersion(String file, String detail) {
        return file + " was written by a newer version of this addon (" + detail
                + "); an older build must not read it and overwrite newer data";
    }

    private static String sourceName(LoadSource source) {
        return switch (source) {
            case MAIN -> POPULATIONS_FILE;
            case BACKUP -> BACKUP_FILE;
            case INTERRUPTED_SAVE -> TEMP_FILE;
            case FRESH -> "nowhere";
        };
    }

    private static String modified(Path file) {
        try {
            return Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis()).toString();
        } catch (IOException | RuntimeException e) {
            return "at an unknown time";
        }
    }

    @Override
    public synchronized boolean usable() {
        ensureLoaded();
        return usable;
    }

    // ------------------------------------------------------------------ mutation

    /**
     * Stores or replaces a record and marks the store dirty. ABANDONED records are appended to the key
     * log immediately (and forced to disk); only the fact "abandoned" is kept.
     * <p>
     * A non-abandoned record replaces an abandoned entry for the same key; the key leaves abandoned.keys
     * once populations.json holding the new record has been written. Storing the same record instance
     * again re-derives its bot names, so it is also a way to announce an in-place rename.
     * <p>
     * Ignored (with a logged warning) while the store is unusable.
     *
     * @throws IllegalArgumentException if the key cannot be persisted unambiguously (contains '|', a
     *                                  line break, or malformed UTF-16), which no real identifier does
     */
    @Override
    public synchronized void put(StructureKey key, StructureRecord record) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(record, "record");
        requirePersistable(key);
        ensureLoaded();
        if (!usable) {
            refuse("put " + key);
            return;
        }
        if (record.status == StructureStatus.ABANDONED) {
            putAbandoned(key);
        } else {
            putActive(key, record);
        }
        dirty = true;
    }

    private void putAbandoned(StructureKey key) {
        if (active.remove(key) != null) {
            activeGrid.remove(key);
            names.unindex(key);
        }
        if (abandonedGrid.add(key)) {
            if (!deferredAbandonedRemovals.remove(key)) {
                recordAbandoned(key);
            } // else its line never left abandoned.keys
        }
    }

    private void putActive(StructureKey key, StructureRecord record) {
        if (abandonedGrid.remove(key)) {
            deferredAbandonedRemovals.add(key);
        }
        if (active.put(key, record) == null) {
            activeGrid.add(key);
        } else {
            names.unindex(key);
        }
        names.index(key, record);
    }

    /** Removes the record (also from the abandoned key log, which is rewritten atomically). Returns whether one existed. */
    @Override
    public synchronized boolean remove(StructureKey key) {
        Objects.requireNonNull(key, "key");
        ensureLoaded();
        if (!usable) {
            refuse("remove " + key);
            return false;
        }
        if (active.remove(key) != null) {
            activeGrid.remove(key);
            names.unindex(key);
            if (deferredAbandonedRemovals.remove(key)) {
                abandonedRewriteNeeded = true; // its stale line must go as well
            }
            dirty = true;
            return true;
        }
        if (abandonedGrid.remove(key)) {
            dirty = true;
            rewriteAbandoned();
            return true;
        }
        return false;
    }

    /**
     * Marks a record as changed after the engine mutated it in place. Bot names are not re-read by this
     * call; use {@link #reindex(StructureKey)} when a bot was renamed, added or removed.
     */
    @Override
    public synchronized void markDirty() {
        ensureLoaded();
        if (usable) {
            dirty = true;
        }
    }

    /**
     * Re-reads the bot names of one stored record. The engine calls this after it renames, adds or drops a
     * bot of a record that is already stored, so {@link #findBot} keeps answering for the new names. (put and
     * remove keep the index exact on their own; only in-place edits need this.)
     */
    public synchronized void reindex(StructureKey key) {
        Objects.requireNonNull(key, "key");
        ensureLoaded();
        StructureRecord record = active.get(key);
        if (usable && record != null) {
            names.reindex(key, record);
        }
    }

    /** Rebuilds the whole name index from the stored records (diagnostics / after bulk in-place edits). */
    public synchronized void reindexAll() {
        ensureLoaded();
        if (usable) {
            names.rebuild(active);
        }
    }

    private void refuse(String operation) {
        if (!refusalLogged) {
            refusalLogged = true;
            LOG.error("population store is unusable, ignoring {} (further refusals are not logged)", operation);
        }
    }

    // ------------------------------------------------------------------ queries

    /** All records that are not ABANDONED (pending and populated ones), as a copy that stays valid while the store changes. */
    @Override
    public synchronized List<Map.Entry<StructureKey, StructureRecord>> nonAbandoned() {
        ensureLoaded();
        List<Map.Entry<StructureKey, StructureRecord>> out = new ArrayList<>(active.size());
        for (Map.Entry<StructureKey, StructureRecord> e : active.entrySet()) {
            if (e.getValue().status != StructureStatus.ABANDONED) {
                out.add(Map.entry(e.getKey(), e.getValue()));
            }
        }
        return out;
    }

    /** World-wide coverage decks; persisted together with the records. */
    @Override
    public synchronized PersistentDeckStore decks() {
        ensureLoaded();
        return decks;
    }

    @Override
    public synchronized Optional<StructureRecord> find(StructureKey key) {
        ensureLoaded();
        if (key == null) {
            return Optional.empty();
        }
        StructureRecord record = active.get(key);
        if (record != null) {
            return Optional.of(record);
        }
        return abandonedGrid.contains(key) ? Optional.of(StructureRecord.abandoned()) : Optional.empty();
    }

    /**
     * Processed structures whose start chunk lies within {@code radiusChunks} of the chunk on both axes
     * (the square, as {@link dev.spawnbotswrapper.inhabitants.engine.PopulationView} specifies), ordered
     * by Euclidean distance in chunks, nearest first, ties by key text. Abandoned structures are included
     * with a freshly synthesised record each call.
     */
    @Override
    public synchronized List<Map.Entry<StructureKey, StructureRecord>> nearby(String dimensionId, int chunkX, int chunkZ,
                                                                             int radiusChunks) {
        ensureLoaded();
        List<Hit> hits = new ArrayList<>();
        if (dimensionId != null && radiusChunks >= 0) {
            abandonedGrid.forEachWithin(dimensionId, chunkX, chunkZ, radiusChunks, (dim, id, x, z) ->
                    hits.add(new Hit(new StructureKey(dim, id, x, z), distanceSquared(x, z, chunkX, chunkZ), null)));
            activeGrid.forEachWithin(dimensionId, chunkX, chunkZ, radiusChunks, (dim, id, x, z) -> {
                StructureKey key = new StructureKey(dim, id, x, z);
                StructureRecord record = active.get(key);
                if (record != null) {
                    hits.add(new Hit(key, distanceSquared(x, z, chunkX, chunkZ), record));
                }
            });
        }
        hits.sort(NEAREST_FIRST);
        List<Map.Entry<StructureKey, StructureRecord>> out = new ArrayList<>(hits.size());
        for (Hit hit : hits) {
            out.add(Map.entry(hit.key(), hit.record() != null ? hit.record() : StructureRecord.abandoned()));
        }
        return out;
    }

    private static double distanceSquared(int x, int z, int centerX, int centerZ) {
        double dx = (double) x - centerX;
        double dz = (double) z - centerZ;
        return dx * dx + dz * dz;
    }

    @Override
    public synchronized Optional<BotLocation> findBot(String botName) {
        ensureLoaded();
        return Optional.ofNullable(names.find(botName, active));
    }

    @Override
    public synchronized PopulationCounts counts() {
        ensureLoaded();
        int abandoned = abandonedGrid.size();
        int pending = 0;
        int populated = 0;
        int gaveUp = 0;
        int spawned = 0;
        int failed = 0;
        for (StructureRecord record : active.values()) {
            StructureStatus status = record.status == null ? StructureStatus.OCCUPIED_PENDING : record.status;
            switch (status) {
                case ABANDONED -> abandoned++;
                case OCCUPIED_PENDING -> pending++;
                case POPULATED -> populated++;
                case GAVE_UP -> gaveUp++;
            }
            for (BotRecord bot : record.bots) {
                if (bot == null) {
                    continue;
                }
                if (bot.state == BotState.SPAWNED) {
                    spawned++;
                } else if (bot.state == BotState.FAILED) {
                    failed++;
                }
            }
        }
        return new PopulationCounts(abandoned, pending, populated, gaveUp, spawned, failed);
    }

    // ------------------------------------------------------------------ writing

    /** Writes to disk if anything changed since the last write. Returns false only on I/O failure (or an unusable store). */
    @Override
    public synchronized boolean saveIfDirty() {
        ensureLoaded();
        if (!usable) {
            refuse("saveIfDirty");
            return false;
        }
        boolean mainDue = dirty || decks.isDirty() || !deferredAbandonedRemovals.isEmpty();
        if (!mainDue && !abandonedRewriteNeeded) {
            return true;
        }
        return persist(mainDue);
    }

    /** Unconditional write (server stop). Never throws; a failure is logged and kept in {@link #lastError()}. */
    @Override
    public synchronized void flush() {
        ensureLoaded();
        if (!usable) {
            refuse("flush");
            return;
        }
        persist(true);
    }

    private boolean persist(boolean writeMain) {
        boolean ok = !writeMain || writeMain();
        if (ok && (abandonedRewriteNeeded || !deferredAbandonedRemovals.isEmpty())) {
            // The main file is durable now, so keys that became records can leave abandoned.keys.
            deferredAbandonedRemovals.clear();
            ok = rewriteAbandoned();
        }
        if (ok) {
            lastError = null;
        }
        return ok;
    }

    private boolean writeMain() {
        try {
            Files.createDirectories(directory);
            demoteInPlaceAbandoned();
            if (!Files.exists(abandonedFile)) {
                // Always keep the pair together, so a missing key file next to a population file is a real anomaly.
                AbandonedLog.rewrite(abandonedFile, abandonedTempFile, this::forEachAbandonedKey);
            }
            PopulationFile.write(tempFile, DATA_VERSION, active, decks.exportSnapshots());
        } catch (IOException | RuntimeException e) {
            DurableFiles.deleteQuietly(tempFile);
            fail("could not write " + POPULATIONS_FILE, e);
            return false;
        }
        boolean movedAside = false;
        try {
            if (Files.exists(mainFile)) {
                Path aside = mainGood ? backupFile : corruptFile;
                try {
                    DurableFiles.replace(mainFile, aside);
                    movedAside = true;
                } catch (IOException | RuntimeException e) {
                    // The backup is a safety net, not a precondition: replacing the main file below is atomic anyway.
                    fail("could not keep " + mainFile.getFileName() + " as " + aside.getFileName(), e);
                }
            }
            DurableFiles.replace(tempFile, mainFile);
            DurableFiles.syncDirectory(directory);
        } catch (IOException | RuntimeException e) {
            if (movedAside) {
                mainGood = false; // the old file is now only the backup; the complete temp file is the newest state
            }
            fail("could not move " + TEMP_FILE + " into place", e);
            return false;
        }
        mainGood = true;
        dirty = false;
        decks.clearDirty();
        return true;
    }

    /** A record whose status was flipped to ABANDONED in place belongs in the key log, not in populations.json. */
    private void demoteInPlaceAbandoned() {
        Iterator<Map.Entry<StructureKey, StructureRecord>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<StructureKey, StructureRecord> e = it.next();
            if (e.getValue().status != StructureStatus.ABANDONED) {
                continue;
            }
            StructureKey key = e.getKey();
            it.remove();
            activeGrid.remove(key);
            names.unindex(key);
            if (abandonedGrid.add(key) && !deferredAbandonedRemovals.remove(key)) {
                recordAbandoned(key);
            }
        }
    }

    private void recordAbandoned(StructureKey key) {
        if (abandonedRewriteNeeded) {
            rewriteAbandoned(); // the rewrite includes this key
            return;
        }
        try {
            AbandonedLog.append(abandonedFile, key);
        } catch (IOException | RuntimeException e) {
            abandonedRewriteNeeded = true; // retried at the next save; until then the decision lives in memory only
            fail("could not append to " + ABANDONED_FILE, e);
        }
    }

    private boolean rewriteAbandoned() {
        try {
            Files.createDirectories(directory);
            AbandonedLog.rewrite(abandonedFile, abandonedTempFile, this::forEachAbandonedKey);
            abandonedRewriteNeeded = false;
            return true;
        } catch (IOException | RuntimeException e) {
            abandonedRewriteNeeded = true;
            fail("could not rewrite " + ABANDONED_FILE, e);
            return false;
        }
    }

    private void forEachAbandonedKey(Consumer<StructureKey> sink) {
        abandonedGrid.forEach((dim, id, x, z) -> sink.accept(new StructureKey(dim, id, x, z)));
        for (StructureKey key : deferredAbandonedRemovals) {
            sink.accept(key);
        }
    }

    private void fail(String what, Throwable cause) {
        String message = what + ": " + cause;
        if (!message.equals(lastError)) {
            LOG.warn("population store: {}", message);
        }
        lastError = message;
    }

    /**
     * Keys are persisted as one text line and split on '|'; a key that does not survive that (or UTF-8)
     * would silently come back as a different key after a restart, so it is refused up front.
     */
    private static void requirePersistable(StructureKey key) {
        String text = key.asString();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                throw new IllegalArgumentException("structure key contains a line break: " + printable(text));
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    throw new IllegalArgumentException("structure key contains malformed UTF-16: " + printable(text));
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("structure key contains malformed UTF-16: " + printable(text));
            }
        }
        if (!key.equals(StructureKey.parse(text))) {
            throw new IllegalArgumentException("structure key does not survive its text form (contains '|'?): " + printable(text));
        }
    }

    private static String printable(String s) {
        return s.replace("\n", "\\n").replace("\r", "\\r");
    }
}

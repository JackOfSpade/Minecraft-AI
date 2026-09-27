package dev.spawnbotswrapper.inhabitants.store;

import dev.spawnbotswrapper.inhabitants.engine.PopulationView;
import dev.spawnbotswrapper.inhabitants.sample.PersistentDeckStore;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;

import java.util.List;
import java.util.Map;

/**
 * What the population engine needs from persistence. {@link PopulationStore} is the file-backed
 * implementation; tests use an in-memory one. Server thread only.
 */
public interface PopulationStorage extends PopulationView {

    /** False when persisted data exists but could not be read: the engine must then not process anything. */
    boolean usable();

    /** Stores or replaces a record and marks the store dirty. ABANDONED records must survive a crash immediately. */
    void put(StructureKey key, StructureRecord record);

    /** Removes the record. Returns whether one existed. */
    boolean remove(StructureKey key);

    /** Marks changed after the engine mutated a stored record in place. */
    void markDirty();

    /**
     * Tells the store that the record at {@code key} had a bot renamed, added or removed in place, so any
     * name-based lookup index must be rebuilt for it. Call this (in addition to {@link #markDirty()})
     * whenever {@code BotRecord.name} changes on an already-stored record - otherwise {@link
     * PopulationView#findBot} keeps answering with the old name and a name-uniqueness check against it can
     * falsely report a stale name as free. The default implementation does nothing, which is only correct
     * for a storage that has no such index.
     */
    default void reindex(StructureKey key) {
    }

    /** All records that are not ABANDONED (pending and populated ones). */
    List<Map.Entry<StructureKey, StructureRecord>> nonAbandoned();

    /** World-wide coverage decks, persisted with the records. */
    PersistentDeckStore decks();

    /** Writes to disk if anything changed since the last write. Returns false only on I/O failure. */
    boolean saveIfDirty();

    /** Unconditional write (server stop). */
    void flush();
}

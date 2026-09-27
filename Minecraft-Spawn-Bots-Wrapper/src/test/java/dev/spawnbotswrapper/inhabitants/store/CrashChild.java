package dev.spawnbotswrapper.inhabitants.store;

import java.nio.file.Path;

import static dev.spawnbotswrapper.inhabitants.store.StoreFixtures.key;
import static dev.spawnbotswrapper.inhabitants.store.StoreFixtures.pending;

/**
 * Run in a separate JVM by {@link PopulationStoreCrashTest}: does some saved and some unsaved work and then
 * dies with {@link Runtime#halt(int)}, which skips shutdown hooks and finalisers exactly like a kill -9
 * does from the process's point of view (no flush, no close, no cleanup).
 */
public final class CrashChild {
    public static final int SAVED_ABANDONED = 20;
    public static final int UNSAVED_ABANDONED = 60;

    private CrashChild() {
    }

    public static void main(String[] args) {
        PopulationStore store = new PopulationStore(Path.of(args[0]));
        if (!store.load().usable()) {
            System.exit(2);
        }
        store.put(key(1, 1), pending("Saved_1"));
        for (int i = 0; i < SAVED_ABANDONED; i++) {
            store.put(key(1000 + i, 0), StructureRecord.abandoned());
        }
        if (!store.saveIfDirty()) {
            System.exit(3);
        }
        store.put(key(2, 2), pending("Unsaved_2")); // never saved: allowed to be lost
        for (int i = 0; i < UNSAVED_ABANDONED; i++) {
            store.put(key(2000 + i, 0), StructureRecord.abandoned()); // never saved, but each one is durable
        }
        Runtime.getRuntime().halt(137);
    }
}

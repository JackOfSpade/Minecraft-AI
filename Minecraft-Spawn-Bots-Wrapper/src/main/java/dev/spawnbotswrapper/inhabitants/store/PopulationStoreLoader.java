package dev.spawnbotswrapper.inhabitants.store;

import dev.spawnbotswrapper.inhabitants.sample.PersistentDeckStore;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * wrapperB-6: the file-recovery / load-report cluster mechanically extracted out of {@link PopulationStore}.
 * Reads {@code populations.json} (falling back to a leftover temp file, then the backup) and {@code
 * abandoned.keys}, and reports what it found; it touches no store state itself -- {@link PopulationStore#doLoad()}
 * applies the {@link Result} to its own indexes ({@code active}, the chunk grids, the name index, the deck
 * store) exactly as it used to inline. See {@link PopulationStore}'s own class doc ("Why unreadable data makes
 * the store unusable", "Crash safety") for the invariants this preserves.
 */
final class PopulationStoreLoader {
    private PopulationStoreLoader() {
    }

    /**
     * @param unusableWhy   non-null only when persisted data exists but could not be trusted; the caller must
     *                       then report {@link PopulationStore.LoadReport#usable()} false and apply nothing else
     * @param source         where the returned state came from ({@link PopulationStore.LoadSource#FRESH} when
     *                       {@code unusableWhy} is set, or when nothing was persisted at all)
     * @param active         non-abandoned records, keyed exactly as {@code populations.json}/its fallbacks had them
     * @param abandonedKeys  every abandoned structure key found, from a hand-edited ABANDONED record in the main
     *                       file and/or from {@code abandoned.keys}
     * @param dirtyOnLoad    true when the in-memory state already differs from {@code populations.json} the
     *                       instant it is loaded (recovered from the backup/temp file, or a hand-edited ABANDONED
     *                       record was found and must be moved out) and so needs rewriting at the next save
     */
    record Result(String unusableWhy, List<String> messages, PopulationStore.LoadSource source,
                  Map<StructureKey, StructureRecord> active, Set<StructureKey> abandonedKeys,
                  Map<String, PersistentDeckStore.Snapshot> decks, boolean abandonedRewriteNeeded,
                  boolean dirtyOnLoad) {

        boolean usable() {
            return unusableWhy == null;
        }
    }

    private static Result unusable(List<String> messages, String why) {
        return new Result(why, List.copyOf(messages), PopulationStore.LoadSource.FRESH,
                Map.of(), Set.of(), Map.of(), false, false);
    }

    static Result load(Path directory, Path mainFile, Path backupFile, Path tempFile, Path abandonedFile) {
        List<String> messages = new ArrayList<>();
        PopulationStore.LoadSource source = PopulationStore.LoadSource.FRESH;
        PopulationFile.Parsed parsed = null;
        boolean unreadableFound = false;

        PopulationFile.ReadResult main = PopulationFile.read(mainFile);
        switch (main.outcome()) {
            case OK -> {
                parsed = main.parsed();
                source = PopulationStore.LoadSource.MAIN;
            }
            case TOO_NEW -> {
                return unusable(messages, newerVersion(PopulationStore.POPULATIONS_FILE, main.detail()));
            }
            case INVALID -> {
                unreadableFound = true;
                messages.add(PopulationStore.POPULATIONS_FILE + " is unreadable (" + main.detail() + ")");
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
                    source = PopulationStore.LoadSource.INTERRUPTED_SAVE;
                    messages.add(PopulationStore.POPULATIONS_FILE + " is missing; using the complete "
                            + PopulationStore.TEMP_FILE + " left by an interrupted save");
                }
                case TOO_NEW -> {
                    return unusable(messages, newerVersion(PopulationStore.TEMP_FILE, temp.detail()));
                }
                case INVALID -> messages.add("ignored an incomplete " + PopulationStore.TEMP_FILE
                        + " left by an interrupted save (" + temp.detail() + ")");
                case MISSING -> {
                }
            }
        } else if (parsed != null && Files.exists(tempFile)) {
            messages.add("ignored a leftover " + PopulationStore.TEMP_FILE + " (an interrupted save; "
                    + PopulationStore.POPULATIONS_FILE + " is intact)");
        }

        if (parsed == null) {
            PopulationFile.ReadResult backup = PopulationFile.read(backupFile);
            switch (backup.outcome()) {
                case OK -> {
                    parsed = backup.parsed();
                    source = PopulationStore.LoadSource.BACKUP;
                    messages.add("recovered from " + PopulationStore.BACKUP_FILE + " (written " + modified(backupFile)
                            + "); population changes made after that backup are lost");
                }
                case TOO_NEW -> {
                    return unusable(messages, newerVersion(PopulationStore.BACKUP_FILE, backup.detail()));
                }
                case INVALID -> {
                    unreadableFound = true;
                    messages.add(PopulationStore.BACKUP_FILE + " is unreadable (" + backup.detail() + ")");
                }
                case MISSING -> {
                }
            }
        }

        if (parsed == null && unreadableFound) {
            return unusable(messages, "persisted population data exists in " + directory + " but neither "
                    + PopulationStore.POPULATIONS_FILE + " nor " + PopulationStore.BACKUP_FILE + " can be read");
        }

        Map<StructureKey, StructureRecord> active = new LinkedHashMap<>();
        Set<StructureKey> abandonedKeys = new LinkedHashSet<>();
        boolean handEditedAbandoned = false;
        Map<String, PersistentDeckStore.Snapshot> decks = Map.of();
        if (parsed != null) {
            for (Map.Entry<StructureKey, StructureRecord> e : parsed.structures().entrySet()) {
                if (e.getValue().status == StructureStatus.ABANDONED) {
                    abandonedKeys.add(e.getKey());
                    handEditedAbandoned = true;
                } else {
                    active.put(e.getKey(), e.getValue());
                }
            }
            decks = parsed.decks();
            messages.addAll(parsed.warnings());
            messages.add("loaded " + active.size() + " structure record(s) and " + decks.size()
                    + " deck(s) from " + sourceName(source));
        } else {
            messages.add("no " + PopulationStore.POPULATIONS_FILE + " found in " + directory + "; starting a new store");
        }

        boolean abandonedRewriteNeeded = false;
        int[] conflicts = new int[1];
        try {
            AbandonedLog.Loaded log = AbandonedLog.read(abandonedFile, key -> {
                if (active.containsKey(key)) {
                    conflicts[0]++;
                } else {
                    abandonedKeys.add(key);
                }
            });
            messages.add(PopulationStore.ABANDONED_FILE + ": " + abandonedKeys.size() + " abandoned structure key(s)");
            if (log.malformed() > 0) {
                messages.add(PopulationStore.ABANDONED_FILE + ": skipped " + log.malformed() + " malformed line(s)");
            }
            if (log.truncatedTail() > 0) {
                if (AbandonedLog.truncate(abandonedFile, log.validLength())) {
                    messages.add(PopulationStore.ABANDONED_FILE + ": removed an unterminated final line ("
                            + log.truncatedTail() + " byte(s)) left by a crash mid-append; it was not used");
                } else {
                    abandonedRewriteNeeded = true;
                    messages.add(PopulationStore.ABANDONED_FILE + ": ignored an unterminated final line ("
                            + log.truncatedTail() + " byte(s)) left by a crash mid-append; it will be dropped at "
                            + "the next rewrite");
                }
            }
        } catch (NoSuchFileException e) {
            if (source != PopulationStore.LoadSource.FRESH) {
                messages.add("warning: " + PopulationStore.ABANDONED_FILE + " is missing although "
                        + PopulationStore.POPULATIONS_FILE + " exists; structures that were abandoned may be "
                        + "rolled again");
            }
        } catch (IOException | RuntimeException e) {
            return unusable(messages, PopulationStore.ABANDONED_FILE + " in " + directory + " cannot be read (" + e + ")");
        }
        if (conflicts[0] > 0) {
            abandonedRewriteNeeded = true;
            messages.add(conflicts[0] + " structure(s) are both in " + PopulationStore.ABANDONED_FILE
                    + " and have a record; the record was kept");
        }
        if (handEditedAbandoned) {
            abandonedRewriteNeeded = true;
            messages.add(PopulationStore.POPULATIONS_FILE + " contained ABANDONED records; they were moved to "
                    + PopulationStore.ABANDONED_FILE);
        }

        boolean dirtyOnLoad = handEditedAbandoned || source == PopulationStore.LoadSource.BACKUP
                || source == PopulationStore.LoadSource.INTERRUPTED_SAVE;
        return new Result(null, List.copyOf(messages), source, active, abandonedKeys, decks,
                abandonedRewriteNeeded, dirtyOnLoad);
    }

    private static String newerVersion(String file, String detail) {
        return file + " was written by a newer version of this addon (" + detail
                + "); an older build must not read it and overwrite newer data";
    }

    private static String sourceName(PopulationStore.LoadSource source) {
        return switch (source) {
            case MAIN -> PopulationStore.POPULATIONS_FILE;
            case BACKUP -> PopulationStore.BACKUP_FILE;
            case INTERRUPTED_SAVE -> PopulationStore.TEMP_FILE;
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
}

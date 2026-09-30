package dev.spawnbotswrapper.inhabitants.store;

import dev.spawnbotswrapper.inhabitants.structure.StructureKey;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The write-ahead journal of {@code populations.json}: a small append-only file that makes the RECORDS of a few
 * structures durable without rewriting the whole store.
 * <p>
 * Why it exists: taking a bot out of the world (it sleeps, or it is deleted) must be durable BEFORE the bot is emptied
 * (see {@code Retirer}), otherwise a crash could leave a restorable copy next to a live inventory, or a bot that is gone
 * while its record still says it is alive (which would read as a death). A full save rewrites the whole store (about 90
 * microseconds per stored bot, plus an fsync of the whole file); doing that on the server thread for every batch of a
 * shedding round, in the middle of lag, is exactly what must not happen. One journal append is a few kilobytes and one
 * fsync of that small file.
 * <p>
 * Each line is {@code seq TAB structureKey TAB recordJson}: the record of that structure AS IT IS at that moment. Loading
 * replays the lines newer than the {@code journalSeq} stored in populations.json, each replacing the structure's record
 * wholesale, in order; every full save stores the current sequence number in its header and then deletes the journal,
 * so an old journal that survives a crash between the two steps is recognised as already folded in and ignored. An
 * unterminated final line (a crash mid-append) is never trusted.
 */
final class RecordJournal {
    static final String FILE = "populations.journal";

    private RecordJournal() {
    }

    /** One replayable line. */
    record Entry(long seq, StructureKey key, StructureRecord record) {
    }

    /**
     * @param entries       the valid lines, in file order
     * @param maxSeq        the highest sequence number found in a valid line (0 when none)
     * @param malformed     complete lines that could not be used
     * @param validLength   byte offset just after the last complete line
     * @param truncatedTail bytes after {@code validLength}: an unterminated final line, ignored
     */
    record Loaded(List<Entry> entries, long maxSeq, int malformed, long validLength, long truncatedTail) {
    }

    /** Appends the given records (already numbered) and forces them to disk before returning. */
    static void append(Path file, long firstSeq, Map<StructureKey, StructureRecord> records) throws IOException {
        StringBuilder text = new StringBuilder();
        long seq = firstSeq;
        for (Map.Entry<StructureKey, StructureRecord> e : records.entrySet()) {
            text.append(seq++).append('\t').append(e.getKey().asString()).append('\t')
                    .append(PopulationFile.encodeRecord(e.getValue())).append('\n');
        }
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        try {
            appendBytes(file, bytes);
        } catch (NoSuchFileException e) {
            Path parent = file.toAbsolutePath().getParent();
            if (parent == null) {
                throw e;
            }
            Files.createDirectories(parent);
            appendBytes(file, bytes);
        }
    }

    private static void appendBytes(Path file, byte[] bytes) throws IOException {
        boolean created = !Files.exists(file);
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(false);
        }
        if (created) {
            DurableFiles.syncDirectory(file.toAbsolutePath().getParent());
        }
    }

    /** Reads every complete, valid line newer than {@code afterSeq}. A missing file is an empty journal. */
    static Loaded read(Path file, long afterSeq) throws IOException {
        byte[] all;
        try {
            all = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return new Loaded(List.of(), 0, 0, 0, 0);
        }
        List<Entry> entries = new ArrayList<>();
        long maxSeq = 0;
        int malformed = 0;
        int lineStart = 0;
        long validLength = 0;
        for (int i = 0; i < all.length; i++) {
            if (all[i] != '\n') {
                continue;
            }
            String line = new String(all, lineStart, i - lineStart, StandardCharsets.UTF_8);
            lineStart = i + 1;
            validLength = lineStart;
            Entry entry = parse(line);
            if (entry == null) {
                malformed++;
                continue;
            }
            maxSeq = Math.max(maxSeq, entry.seq());
            if (entry.seq() > afterSeq) {
                entries.add(entry);
            }
        }
        return new Loaded(entries, maxSeq, malformed, validLength, all.length - validLength);
    }

    private static Entry parse(String line) {
        String[] parts = line.split("\t", 3);
        if (parts.length != 3) {
            return null;
        }
        try {
            long seq = Long.parseLong(parts[0]);
            StructureKey key = StructureKey.parse(parts[1]);
            StructureRecord record = key == null ? null : PopulationFile.decodeRecord(key, parts[2]);
            return record == null ? null : new Entry(seq, key, record);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static void delete(Path file) {
        DurableFiles.deleteQuietly(file);
    }
}

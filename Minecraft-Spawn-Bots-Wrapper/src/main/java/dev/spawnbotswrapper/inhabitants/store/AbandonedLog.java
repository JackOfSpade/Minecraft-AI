package dev.spawnbotswrapper.inhabitants.store;

import dev.spawnbotswrapper.inhabitants.structure.StructureKey;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * The append-only list of abandoned structure keys: UTF-8, one {@link StructureKey#asString()} per line.
 * <p>
 * It is a separate file (not part of populations.json) because a well-explored world holds hundreds of
 * thousands of abandoned structures, and because an abandoned decision must be durable the moment it is
 * made: appending one line and forcing it to disk costs microseconds of I/O, rewriting a multi-megabyte
 * JSON for every roll would not.
 * <p>
 * A crash mid-append can leave a final line without its newline. Such a tail is NEVER trusted, even when
 * it happens to parse: cutting {@code ...|1,234} after "1,23" still yields a valid key, just a wrong one.
 */
final class AbandonedLog {
    /** Real keys are a few dozen bytes; anything longer than this is garbage, not a key. */
    private static final int MAX_LINE_BYTES = 4096;
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private AbandonedLog() {
    }

    /**
     * @param keys          lines that parsed into a key (duplicates included)
     * @param malformed     complete lines that were skipped (not UTF-8, not a key, or absurdly long)
     * @param validLength   byte offset just after the last newline; everything up to here is complete lines
     * @param truncatedTail bytes after {@code validLength}: an unterminated final line, ignored
     */
    record Loaded(int keys, int malformed, long validLength, long truncatedTail) {
    }

    /** Streams the file, handing each valid key to {@code sink}. Throws when the file cannot be read at all. */
    static Loaded read(Path file, Consumer<StructureKey> sink) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        byte[] chunk = new byte[1 << 16];
        byte[] line = new byte[256];
        int lineLength = 0;
        boolean overflow = false;
        boolean firstLine = true;
        long offset = 0;
        long validLength = 0;
        int keys = 0;
        int malformed = 0;

        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(chunk)) > 0) {
                for (int i = 0; i < n; i++) {
                    byte b = chunk[i];
                    offset++;
                    if (b != '\n') {
                        if (!overflow) {
                            if (lineLength == line.length) {
                                if (line.length >= MAX_LINE_BYTES) {
                                    overflow = true;
                                } else {
                                    line = Arrays.copyOf(line, line.length * 2);
                                }
                            }
                            if (!overflow) {
                                line[lineLength++] = b;
                            }
                        }
                        continue;
                    }
                    validLength = offset;
                    int start = 0;
                    if (firstLine && !overflow && startsWithBom(line, lineLength)) {
                        start = BOM.length;
                    }
                    firstLine = false;
                    int end = lineLength;
                    if (end > start && line[end - 1] == '\r') {
                        end--;
                    }
                    if (overflow) {
                        malformed++;
                    } else if (end > start) {
                        StructureKey key = decode(decoder, line, start, end - start);
                        if (key == null) {
                            malformed++;
                        } else {
                            keys++;
                            sink.accept(key);
                        }
                    }
                    lineLength = 0;
                    overflow = false;
                }
            }
        }
        return new Loaded(keys, malformed, validLength, offset - validLength);
    }

    /** Appends one key and forces it to disk before returning, so the decision survives a crash. */
    static void append(Path file, StructureKey key) throws IOException {
        byte[] bytes = encode(key);
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

    interface KeySource {
        void forEach(Consumer<StructureKey> sink);
    }

    /** Atomically replaces the whole file with {@code keys}: readers see the old file or the new, never a mix. */
    static void rewrite(Path file, Path temp, KeySource keys) throws IOException {
        try {
            DurableFiles.writeSynced(temp, out -> {
                try {
                    keys.forEach(key -> {
                        try {
                            out.write(encode(key));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                } catch (UncheckedIOException e) {
                    throw e.getCause();
                }
            });
            DurableFiles.replace(temp, file);
        } catch (IOException | RuntimeException e) {
            DurableFiles.deleteQuietly(temp);
            throw e;
        }
        DurableFiles.syncDirectory(file.toAbsolutePath().getParent());
    }

    /** Cuts the file back to {@code length} bytes (the end of its last complete line). Returns success. */
    static boolean truncate(Path file, long length) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(length);
            channel.force(true);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static StructureKey decode(CharsetDecoder decoder, byte[] line, int offset, int length) {
        try {
            decoder.reset();
            return StructureKey.parse(decoder.decode(ByteBuffer.wrap(line, offset, length)).toString());
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static byte[] encode(StructureKey key) {
        return (key.asString() + '\n').getBytes(StandardCharsets.UTF_8);
    }

    private static boolean startsWithBom(byte[] line, int length) {
        return length >= BOM.length && line[0] == BOM[0] && line[1] == BOM[1] && line[2] == BOM[2];
    }
}

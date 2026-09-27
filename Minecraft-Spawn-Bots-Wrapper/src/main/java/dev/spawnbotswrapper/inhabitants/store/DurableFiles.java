package dev.spawnbotswrapper.inhabitants.store;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * The few file primitives the store's crash safety rests on: "written" means forced to the storage
 * device, "moved" means atomically replaced, so a crash leaves either the old or the new file, never a mix.
 */
final class DurableFiles {

    private DurableFiles() {
    }

    @FunctionalInterface
    interface Body {
        void write(OutputStream out) throws IOException;
    }

    /** Creates or truncates {@code file}, lets {@code body} fill it, and fsyncs it before returning. */
    static void writeSynced(Path file, Body body) throws IOException {
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            // Deliberately not closed: closing would close the channel before it is forced.
            OutputStream out = new BufferedOutputStream(Channels.newOutputStream(channel), 1 << 16);
            body.write(out);
            out.flush();
            channel.force(true);
        }
    }

    /** Atomic replace; falls back to a plain replace on file systems without atomic moves. */
    static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Persists a directory entry (a rename or a newly created file). Best effort: some platforms
     * (Windows) cannot open a directory for this and make the rename durable themselves.
     */
    static void syncDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException ignored) {
            // see above
        }
    }

    static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException ignored) {
            // a stale temp file is ignored by the loader and overwritten by the next save
        }
    }
}

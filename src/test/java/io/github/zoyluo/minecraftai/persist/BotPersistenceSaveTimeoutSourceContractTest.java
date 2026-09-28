package io.github.zoyluo.minecraftai.persist;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source contract of {@code persist/BotPersistence.java}'s {@code saveAll}. It runs synchronously
 * on the calling thread (the server thread, for the manual save command, server shutdown and the
 * startup legacy-migration path) and needs a real {@code MinecraftServer}, so it cannot be
 * exercised without a full Minecraft bootstrap; this reads the production source as text instead,
 * like the other source-contract tests in this repo.
 */
class BotPersistenceSaveTimeoutSourceContractTest {
    private static final Path FILE = Path.of("src/main/java/io/github/zoyluo/minecraftai/persist/BotPersistence.java");

    private static String source() throws IOException {
        return Files.readString(FILE);
    }

    private static String method(String src, String signature) {
        int start = src.indexOf(signature);
        assertTrue(start >= 0, signature + " must exist");
        int end = src.indexOf("\n    }\n", start);
        assertTrue(end > start, signature + " must end with a method-level closing brace");
        return src.substring(start, end);
    }

    @Test
    void theFileExists() {
        assertTrue(Files.exists(FILE));
    }

    @Test
    void saveAllBoundsTheWaitAndHandlesTimeout() throws IOException {
        String body = method(source(), "public int saveAll(MinecraftServer server) {");

        assertTrue(body.contains("future.get(SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)"),
                "the calling thread's wait must be bounded, not an unbounded future.get()");
        assertTrue(body.contains("} catch (TimeoutException exception) {"),
                "a timeout must be handled explicitly, mirroring the InterruptedException/ExecutionException branches");
        assertTrue(body.contains("BotLog.error(\"runtime_persist_sync_timeout\", exception, \"path\", runtimeFile(server))"));
        assertTrue(body.contains("lastSaveSucceeded = false;"));
    }

    @Test
    void theTimeoutHandlerReturnsZeroLikeTheOtherFailureBranches() throws IOException {
        String body = method(source(), "public int saveAll(MinecraftServer server) {");
        int timeoutCatch = body.indexOf("} catch (TimeoutException exception) {");
        String timeoutBranch = body.substring(timeoutCatch);

        assertTrue(timeoutBranch.contains("return 0;"), "a timed-out save must report 0, like the other failure branches");
    }

    @Test
    void theWriterFutureIsNeverCancelledOnTimeout() throws IOException {
        String body = method(source(), "public int saveAll(MinecraftServer server) {");

        // The write must be left to finish in the background so the on-disk state stays consistent;
        // only the caller's wait is bounded.
        assertFalse(body.contains("future.cancel("));
    }

    @Test
    void theTimeoutConstantIsPositiveAndFinite() throws IOException {
        String source = source();
        assertTrue(source.contains("private static final long SAVE_TIMEOUT_SECONDS = 30;"));
    }
}

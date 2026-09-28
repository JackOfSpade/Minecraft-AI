package io.github.zoyluo.minecraftai.entity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source contract of {@code entity/AIPlayerEntity.java}. {@code tick()} overrides
 * {@code ServerPlayerEntity#tick()} and cannot be exercised without a full Minecraft server
 * bootstrap, so this reads the production source as text instead, like the other source-contract
 * tests in this repo.
 */
class AIPlayerEntityTickCatchSourceContractTest {
    private static final Path FILE = Path.of("src/main/java/io/github/zoyluo/minecraftai/entity/AIPlayerEntity.java");

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
    void tickCatchesAnyRuntimeExceptionNotJustNullPointerException() throws IOException {
        String body = method(source(), "public void tick() {");

        // Was catch (NullPointerException exception); widened so any other RuntimeException from the
        // same call graph (super.tick()/playerTick()/actionPack.onUpdate()) gets the same graceful
        // per-tick handling instead of crashing the server as a "Ticking player" failure.
        assertTrue(body.contains("} catch (RuntimeException exception) {"));
        assertFalse(body.contains("catch (NullPointerException exception)"),
                "the catch must be widened, not left NPE-only");
        assertTrue(body.contains("BotLog.error(this, \"tick_npe_swallowed\", exception);"),
                "the existing diagnostic log line must be kept unchanged");
    }

    @Test
    void tickStillDoesNotResetActionPackStateOnCatch() throws IOException {
        String body = method(source(), "public void tick() {");
        int catchIndex = body.indexOf("} catch (RuntimeException exception) {");
        String catchBody = body.substring(catchIndex);

        // The bug fix widens the exception type caught; it must not also change what happens when a
        // (possibly recurring, benign) exception is caught, e.g. by force-clearing ActionPack state -
        // a stuck bot still recovers only through StuckWatcher's own timeout.
        assertFalse(catchBody.contains("stopAll()"));
        assertFalse(catchBody.contains("TaskManager"));
    }
}

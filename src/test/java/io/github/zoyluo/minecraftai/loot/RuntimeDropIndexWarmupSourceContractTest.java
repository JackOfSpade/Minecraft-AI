package io.github.zoyluo.minecraftai.loot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The drop index build (200-650 ms) must not land inside the player's first gather request: it is
 * armed at server start, built by an idle-time warm-up tick a few seconds later, and the lazy
 * first-use build stays as the fallback.
 */
final class RuntimeDropIndexWarmupSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    @Test
    void theWarmupIsScheduledFromTheServerTickAndTheLazyPathRemainsAsFallback() throws IOException {
        String index = Files.readString(MAIN.resolve("loot/RuntimeDropIndex.java"));
        String mod = Files.readString(MAIN.resolve("MinecraftAiMod.java"));

        assertTrue(index.contains("public static void tickWarmup(MinecraftServer server)"));
        assertTrue(index.contains("warmupTicksLeft = WARMUP_DELAY_TICKS;"), "arm() must schedule the warm-up");
        assertTrue(index.contains("warmupTicksLeft = 0;"), "clear() must cancel a pending warm-up");
        assertTrue(index.contains("private static void ensureBuilt()"), "the lazy first-use build stays as fallback");
        assertTrue(mod.contains("RuntimeDropIndex.tickWarmup(server);"),
                "the server tick hook must drive the warm-up");
        int hook = mod.indexOf("ServerTickEvents.END_SERVER_TICK.register(server -> {");
        assertTrue(mod.indexOf("RuntimeDropIndex.tickWarmup(server);") > hook,
                "the warm-up runs on the server thread inside the tick hook");
    }

    @Test
    void theWarmupDelayIsAFewSecondsNotTheStartCriticalPath() {
        assertTrue(RuntimeDropIndex.WARMUP_DELAY_TICKS >= 40 && RuntimeDropIndex.WARMUP_DELAY_TICKS <= 400,
                "a few seconds after start (20 tps)");
    }
}

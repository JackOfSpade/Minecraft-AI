package io.github.zoyluo.minecraftai.pathfinding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Locks retirement of the raw-world legacy planner's server-start scan. */
class RuntimePathfinderWarmupTest {
    @Test
    void productionStartupDoesNotRunTheRetiredRawWorldPlanner() throws IOException {
        Path root = Path.of("src/main/java/io/github/zoyluo/minecraftai");
        String mod = Files.readString(root.resolve("MinecraftAiMod.java"));
        assertFalse(mod.contains("RuntimePathfinderWarmup"),
                "server startup must not perform a raw-world A* warm-up after navigation retirement");
        assertFalse(Files.exists(root.resolve("pathfinding/RuntimePathfinderWarmup.java")),
                "the retired production warm-up must not remain available for accidental re-wiring");
    }
}

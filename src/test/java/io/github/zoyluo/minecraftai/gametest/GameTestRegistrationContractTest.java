package io.github.zoyluo.minecraftai.gametest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Fabric only runs the GameTest classes the test mod's {@code fabric-gametest} entrypoint lists: a class that is written and never
 * listed compiles, looks like coverage, and is never run (MiningSafetyGameTests, the break-footing proof of MiningSafety, was one).
 * There is deliberately no allowlist: a class that cannot pass is fixed or deleted, not parked (LegChooserGameTests was deleted
 * because the strip-mining path it exercised is retired in every operating profile).
 */
class GameTestRegistrationContractTest {
    private static final Path SOURCES = Path.of("src/gametest/java");
    private static final Path MOD_JSON = Path.of("src/gametest/resources/fabric.mod.json");
    private static final Pattern GAME_TEST = Pattern.compile("(?m)^\\s*@GameTest\\b");

    private static boolean registered(String modJson, String className) {
        return modJson.contains("\"" + className + "\"");
    }

    @Test
    void everyClassWithAGameTestIsListedInTheFabricGameTestEntrypoint() throws IOException {
        String modJson = Files.readString(MOD_JSON);
        List<String> unregistered = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (!GAME_TEST.matcher(Files.readString(file)).find()) {
                    continue;
                }
                String relative = SOURCES.relativize(file).toString().replace('\\', '/');
                String className = relative.substring(0, relative.length() - ".java".length()).replace('/', '.');
                if (!registered(modJson, className)) {
                    unregistered.add(className);
                }
            }
        }
        assertTrue(unregistered.isEmpty(),
                "these classes declare @GameTest and are not in the fabric-gametest entrypoint of " + MOD_JSON + ": " + unregistered);
    }
}

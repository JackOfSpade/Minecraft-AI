package io.github.zoyluo.minecraftai.gametest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Fabric only runs the GameTest classes the test mod's {@code fabric-gametest} entrypoint lists: a class that is written and never
 * listed compiles, looks like coverage, and is never run (MiningSafetyGameTests, the break-footing proof of MiningSafety, was one).
 */
class GameTestRegistrationContractTest {
    private static final Path SOURCES = Path.of("src/gametest/java");
    private static final Path MOD_JSON = Path.of("src/gametest/resources/fabric.mod.json");
    private static final Pattern GAME_TEST = Pattern.compile("(?m)^\\s*@GameTest\\b");

    /**
     * Classes that are known not to be registered, and why. Registering one is a one-line change once it passes; an entry here is a
     * debt, not a decision, and it is dropped from this set the day the class is registered (the test below insists on that).
     *
     * <ul>
     *   <li>{@code LegChooserGameTests} fails when run: its premise is that an OreDig mission in a sealed stone room strip-mines on its
     *       first empty scan, and the shipped strict-survival policy no longer does that (it explores by observed hops and a staircase
     *       instead, see {@code RetiredNavigationTask.legacyExcavationDisabled()}), so {@code publishStripSuccessor} never runs.
     *       It needs a rewrite for the policy that ships, or a decision to delete it with the retired strip path.</li>
     * </ul>
     */
    private static final Set<String> KNOWN_UNREGISTERED = Set.of("io.github.zoyluo.minecraftai.task.LegChooserGameTests");

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
                if (!registered(modJson, className) && !KNOWN_UNREGISTERED.contains(className)) {
                    unregistered.add(className);
                }
            }
        }
        assertTrue(unregistered.isEmpty(),
                "these classes declare @GameTest and are not in the fabric-gametest entrypoint of " + MOD_JSON + ": " + unregistered);
    }

    @Test
    void aClassThatIsRegisteredIsNoLongerListedAsKnownUnregistered() throws IOException {
        String modJson = Files.readString(MOD_JSON);
        for (String className : KNOWN_UNREGISTERED) {
            Path source = SOURCES.resolve(className.replace('.', '/') + ".java");
            assertTrue(Files.exists(source), className + " no longer exists: drop it from KNOWN_UNREGISTERED");
            assertTrue(!registered(modJson, className), className + " is registered now: drop it from KNOWN_UNREGISTERED");
        }
    }
}

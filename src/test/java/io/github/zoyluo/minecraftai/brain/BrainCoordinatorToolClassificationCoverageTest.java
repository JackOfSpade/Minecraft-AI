package io.github.zoyluo.minecraftai.brain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BrainCoordinator's READ_ONLY_TOOLS/GENUINE_ACTION_TOOLS/WORK_START_TOOLS/CONTROL_ONLY_TOOLS
 * sets are hand-maintained shadow metadata for the tools ToolRegistry actually registers
 * (brain-r3). Constructing either class needs a Minecraft bootstrap this test environment
 * does not have (see ToolRegistryAssignTaskNullParamsTest), so this pins the invariant as a
 * source-text check instead: every tool name any classification set mentions must be a name
 * ToolRegistry.registerDefaults() actually registers, so a typo or a renamed/removed tool is
 * caught instead of silently being treated as "not a genuine action tool".
 */
final class BrainCoordinatorToolClassificationCoverageTest {
    private static final List<String> CLASSIFICATION_SETS = List.of(
            "READ_ONLY_TOOLS", "GENUINE_ACTION_TOOLS", "WORK_START_TOOLS", "CONTROL_ONLY_TOOLS");

    @Test
    void everyClassifiedToolNameIsActuallyRegistered() throws IOException {
        String coordinator = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/brain/BrainCoordinator.java"));
        String registry = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/brain/ToolRegistry.java"));

        Set<String> registeredTools = toolNames(registry, "register\\(\"([a-zA-Z0-9_]+)\"");
        assertTrue(registeredTools.size() >= 60,
                "sanity check: expected ToolRegistry to register dozens of tools, found " + registeredTools.size());

        for (String setName : CLASSIFICATION_SETS) {
            Matcher setMatcher = Pattern.compile(
                    setName + "\\s*=\\s*Set\\.of\\(([^;]*)\\);", Pattern.DOTALL).matcher(coordinator);
            assertTrue(setMatcher.find(), "expected to find " + setName + " in BrainCoordinator.java");
            Set<String> classified = toolNames(setMatcher.group(1), "\"([a-zA-Z0-9_]+)\"");
            assertTrue(!classified.isEmpty(), setName + " should not be empty");
            for (String tool : classified) {
                assertTrue(registeredTools.contains(tool),
                        setName + " references \"" + tool + "\", which ToolRegistry does not register");
            }
        }
    }

    private static Set<String> toolNames(String source, String pattern) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile(pattern).matcher(source);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}

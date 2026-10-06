package io.github.zoyluo.minecraftai.brain;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Pins the generic-log sentinel so an unspecified "logs" request never defaults to oak. */
final class ToolRegistryGatherThenGiveRegistrationTest {
    @Test
    void genericLogsSentinelUsesTheFamilySafeHandoffTask() throws IOException {
        String registry = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/brain/ToolRegistry.java"));

        assertTrue(registry.contains("literal logs for any tree-log species")
                        && registry.contains("isGenericLogHandoff(requestedItem)")
                        && registry.contains("GatherThenGiveTask.genericLogs("),
                "gather_then_give must accept the logs sentinel before exact registry-item parsing");
        assertTrue(registry.contains("\"logs\".equalsIgnoreCase(item.trim())")
                        && registry.contains("\"minecraft:logs\".equalsIgnoreCase(item.trim())"),
                "only the intentional generic-log spellings may bypass exact item-id parsing");
    }
}

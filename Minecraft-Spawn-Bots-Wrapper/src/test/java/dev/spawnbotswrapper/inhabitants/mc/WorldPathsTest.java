package dev.spawnbotswrapper.inhabitants.mc;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldPathsTest {

    @Test
    void theDataDirectoryLivesInsideTheSaveFolder() {
        assertEquals(Path.of("saves", "My World", "pvpbot_inhabitants"),
                WorldPaths.dataDirectory(Path.of("saves", "My World")));
    }

    @Test
    void theDotThatMinecraftReportsAsTheSaveRootIsNormalisedAway() {
        assertEquals(Path.of("world", "pvpbot_inhabitants"), WorldPaths.dataDirectory(Path.of("world", ".")));
        assertEquals(Path.of("srv", "world", "pvpbot_inhabitants"),
                WorldPaths.dataDirectory(Path.of("srv", "world", ".")));
    }

    @Test
    void absolutePathsStayAbsolute() {
        Path root = Path.of("").toAbsolutePath().resolve("some world");
        Path dir = WorldPaths.dataDirectory(root);
        assertTrue(dir.isAbsolute());
        assertEquals(root.resolve("pvpbot_inhabitants"), dir);
    }

    @Test
    void twoWorldsWithTheSameNameInDifferentFoldersNeverShareData() {
        assertNotEquals(WorldPaths.dataDirectory(Path.of("a", "world")), WorldPaths.dataDirectory(Path.of("b", "world")));
    }

    @Test
    void theDirectoryNameIsTheAddonId() {
        assertEquals("pvpbot_inhabitants", WorldPaths.DIRECTORY_NAME);
    }
}

package io.github.zoyluo.minecraftai.mode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * HIDDEN_BLOCK_SCAN is permanently retired. Hot, ordinary observation entry points must not create
 * misleading denied-capability audits for work that is already constrained to visible terrain.
 */
class RetiredHiddenScanEntryPointSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    @Test
    void ordinaryMiningAndSnapshotEntryPointsStayObservableOnly() throws IOException {
        String prospector = read("mining/OreProspector.java");
        String harvest = read("action/HarvestCore.java");
        String perception = read("perception/PerceptionCollector.java");
        String diagnostic = read("log/DiagnosticLogger.java");

        assertFalse(prospector.contains("CapabilityRuntime.decide("));
        assertTrue(prospector.contains("new Scan(bot, range, match, posFilter, false, false)"));
        assertTrue(prospector.contains("new Scan(bot, range, match, posFilter, false, true)"));

        assertFalse(harvest.contains("CapabilityRuntime.decide("));
        assertFalse(harvest.contains("CapabilityTally.INSTANCE.record("));
        assertTrue(harvest.contains("allowObservableCellFallback, false"));

        assertFalse(perception.contains("CapabilityRuntime.decide("));
        assertFalse(diagnostic.contains("CapabilityRuntime.decide("));
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}

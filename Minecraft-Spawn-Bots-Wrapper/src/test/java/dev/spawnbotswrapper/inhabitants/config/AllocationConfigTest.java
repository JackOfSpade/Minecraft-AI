package dev.spawnbotswrapper.inhabitants.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code allocation} block: sane defaults, bounds on every value, and old configs that do not have it keep loading. */
class AllocationConfigTest {

    @Test
    void theDefaultsAreTheDocumentedOnes() {
        InhabitantsConfig.Allocation a = new InhabitantsConfig().allocation;
        assertTrue(a.enabled);
        assertEquals(20, a.intervalTicks);
        assertEquals(4.0, a.moveThresholdBlocks);
        assertEquals(8.0, a.hysteresisBlocks);
        assertEquals(400, a.dwellTicks, "20 s");
        assertEquals(32.0, a.dwellOverrideBlocks);
        assertEquals(200, a.graceTicks, "10 s");
        assertEquals(2, a.relevanceExtraChunks);
        assertEquals(10, a.seenCheckTicks);
        assertEquals(70.0, a.seenHalfAngleDeg);
    }

    @Test
    void everyValueIsClampedToItsBoundsWithAWarning() {
        InhabitantsConfig c = new InhabitantsConfig();
        c.allocation.intervalTicks = 0;
        c.allocation.moveThresholdBlocks = -3;
        c.allocation.hysteresisBlocks = 1000;
        c.allocation.dwellTicks = -1;
        c.allocation.dwellOverrideBlocks = 100000;
        c.allocation.graceTicks = -50;
        c.allocation.relevanceExtraChunks = 99;
        c.allocation.seenCheckTicks = 0;
        c.allocation.seenHalfAngleDeg = 400;
        c.dormancy.distanceBlocks = 0;
        List<String> warnings = ConfigValidator.validate(c);
        assertEquals(1, c.allocation.intervalTicks);
        assertEquals(0.0, c.allocation.moveThresholdBlocks);
        assertEquals(64.0, c.allocation.hysteresisBlocks);
        assertEquals(0, c.allocation.dwellTicks);
        assertEquals(512.0, c.allocation.dwellOverrideBlocks);
        assertEquals(0, c.allocation.graceTicks);
        assertEquals(16, c.allocation.relevanceExtraChunks);
        assertEquals(1, c.allocation.seenCheckTicks);
        assertEquals(180.0, c.allocation.seenHalfAngleDeg);
        assertEquals(16.0, c.dormancy.distanceBlocks, "the relevance area is never smaller than one chunk");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("allocation.intervalTicks")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("allocation.seenHalfAngleDeg")));
    }

    @Test
    void aMissingOrNullBlockFallsBackToTheDefaults() {
        InhabitantsConfig c = new InhabitantsConfig();
        c.allocation = null;
        c.dormancy = null;
        ConfigValidator.validate(c);
        assertEquals(20, c.allocation.intervalTicks);
        assertEquals(160.0, c.dormancy.distanceBlocks);
    }

    @Test
    void aConfigFromBeforeTheAllocationLoadsWithItsDefaultsAndItsDormancyKept(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("cfg.json");
        Files.writeString(f, """
                {
                  "processing": { "maxLiveBots": 64, "blocksPerBot": 300.0 },
                  "dormancy": { "enabled": true, "distanceBlocks": 200.0, "delayTicks": 600, "scanIntervalTicks": 50 }
                }
                """, StandardCharsets.UTF_8);
        ConfigIO.LoadResult r = ConfigIO.load(f);
        assertNull(r.fatalError());
        assertEquals(64, r.config().processing.maxLiveBots);
        assertTrue(r.config().allocation.enabled);
        assertEquals(20, r.config().allocation.intervalTicks);
        assertEquals(200.0, r.config().dormancy.distanceBlocks);
        assertEquals(600, r.config().dormancy.delayTicks);
    }

    @Test
    void theBlockRoundTripsThroughTheDefaultFile(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("cfg.json");
        ConfigIO.load(f);
        String text = Files.readString(f, StandardCharsets.UTF_8);
        assertTrue(text.contains("\"allocation\""), "the default file documents the block");
        assertTrue(text.contains("hysteresisBlocks"));
        ConfigIO.LoadResult again = ConfigIO.load(f);
        assertEquals(8.0, again.config().allocation.hysteresisBlocks);
    }
}

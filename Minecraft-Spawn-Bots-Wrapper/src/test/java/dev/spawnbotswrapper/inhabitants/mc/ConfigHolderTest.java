package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.config.ConfigIO;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class ConfigHolderTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("pvpbot_inhabitants.json");
    }

    private void write(String json) throws IOException {
        Files.writeString(file(), json, StandardCharsets.UTF_8);
    }

    @Test
    void theFirstLoadCreatesADefaultFileAndInstallsDefaults() {
        ConfigHolder holder = new ConfigHolder(file());
        ConfigIO.LoadResult result = holder.loadInitial();
        assertTrue(result.created());
        assertNull(result.fatalError());
        assertTrue(Files.exists(file()));
        assertTrue(holder.get().enabled);
        assertSame(result.config(), holder.get());
    }

    @Test
    void beforeAnythingIsLoadedThereAreStillUsableDefaults() {
        ConfigHolder holder = new ConfigHolder(file());
        assertNotNull(holder.get());
        assertTrue(holder.get().enabled);
    }

    @Test
    void anUnreadableFileAtStartupYieldsDefaultsAndTheErrorAndIsNotOverwritten() throws IOException {
        write("{ this is not json");
        ConfigHolder holder = new ConfigHolder(file());
        ConfigIO.LoadResult result = holder.loadInitial();
        assertNotNull(result.fatalError());
        assertTrue(holder.get().enabled, "defaults are in use");
        assertEquals("{ this is not json", Files.readString(file()), "the operator file is left alone");
    }

    @Test
    void reloadReplacesTheWholeObjectAndLeavesTheOldSnapshotUntouched() throws IOException {
        ConfigHolder holder = new ConfigHolder(file());
        holder.loadInitial();
        InhabitantsConfig before = holder.get();
        assertTrue(before.enabled);

        write("{ \"enabled\": false, \"debug\": true }");
        ConfigHolder.Reload reload = holder.reload();

        assertTrue(reload.swapped());
        assertNotSame(before, holder.get());
        assertFalse(holder.get().enabled);
        assertTrue(holder.get().debug);
        assertTrue(before.enabled, "code that captured the old config keeps a consistent snapshot");
    }

    @Test
    void consumersHoldingTheSupplierSeeTheReloadWithoutRewiring() throws IOException {
        ConfigHolder holder = new ConfigHolder(file());
        holder.loadInitial();
        Supplier<InhabitantsConfig> consumer = holder;
        write("{ \"processing\": { \"maxBotsPerTick\": 3 } }");
        holder.reload();
        assertEquals(3, consumer.get().processing.maxBotsPerTick);
    }

    @Test
    void aBrokenFileOnReloadKeepsTheConfigurationThatWasWorking() throws IOException {
        ConfigHolder holder = new ConfigHolder(file());
        write("{ \"enabled\": true, \"default\": { \"occupiedChance\": 0.25 } }");
        holder.loadInitial();
        InhabitantsConfig working = holder.get();
        assertEquals(0.25, working.defaults.occupiedChance);

        write("{ \"enabled\": false, oops");
        ConfigHolder.Reload reload = holder.reload();

        assertFalse(reload.swapped());
        assertSame(working, holder.get(), "a typo must not silently reset every setting to defaults");
        assertFalse(reload.messages().isEmpty());
        String first = reload.messages().get(0);
        assertTrue(first.contains("previous configuration stays active"), first);
        assertFalse(first.contains("defaults are in use"), "the message must not claim defaults are in use: " + first);
    }

    @Test
    void repairsMadeWhileLoadingAreReportedOnReload() throws IOException {
        ConfigHolder holder = new ConfigHolder(file());
        holder.loadInitial();
        write("{ \"commandPermissionLevel\": 9 }");
        ConfigHolder.Reload reload = holder.reload();
        assertTrue(reload.swapped());
        assertEquals(4, holder.get().commandPermissionLevel, "clamped");
        assertFalse(reload.messages().isEmpty(), "the clamp is reported");
        assertTrue(reload.messages().stream().anyMatch(m -> m.contains("commandPermissionLevel")), reload.messages().toString());
    }

    @Test
    void aCleanReloadReportsNothing() {
        ConfigHolder holder = new ConfigHolder(file());
        holder.loadInitial();
        ConfigHolder.Reload reload = holder.reload();
        assertTrue(reload.swapped());
        assertEquals(List.of(), reload.messages());
    }

    @Test
    void reloadingARemovedFileRecreatesTheDefaults() throws IOException {
        ConfigHolder holder = new ConfigHolder(file());
        write("{ \"enabled\": false }");
        holder.loadInitial();
        assertFalse(holder.get().enabled);
        Files.delete(file());
        ConfigHolder.Reload reload = holder.reload();
        assertTrue(reload.swapped());
        assertTrue(holder.get().enabled);
        assertTrue(Files.exists(file()));
    }

    @Test
    void reloadMessagesAreImmutable() {
        ConfigHolder holder = new ConfigHolder(file());
        holder.loadInitial();
        ConfigHolder.Reload reload = holder.reload();
        assertThrows(UnsupportedOperationException.class, () -> reload.messages().add("x"));
    }
}

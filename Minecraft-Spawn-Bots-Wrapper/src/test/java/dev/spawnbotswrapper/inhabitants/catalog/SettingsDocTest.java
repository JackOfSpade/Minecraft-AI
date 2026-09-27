package dev.spawnbotswrapper.inhabitants.catalog;

import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.Category;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Keeps {@code docs/SETTINGS.md} honest: the table and the per-category counts in it are generated from
 * {@link SettingCatalog}, so the document can never claim a classification the code does not make.
 * With the environment variable {@code REGENERATE_SETTINGS_DOC=1} the two generated blocks are rewritten
 * in place first (the hand-written text around them is never touched).
 */
class SettingsDocTest {
    private static final Path DOC = Path.of("docs", "SETTINGS.md");
    private static final String REGENERATE_ENV = "REGENERATE_SETTINGS_DOC";
    private static final String TABLE_BEGIN = "<!-- SETTINGS-TABLE:BEGIN -->";
    private static final String TABLE_END = "<!-- SETTINGS-TABLE:END -->";
    private static final String COUNTS_BEGIN = "<!-- SETTINGS-COUNTS:BEGIN -->";
    private static final String COUNTS_END = "<!-- SETTINGS-COUNTS:END -->";
    private static final String HOW_TO_REGENERATE = "Regenerate the generated blocks with "
            + REGENERATE_ENV + "=1 ./gradlew test --tests '*SettingsDocTest*' "
            + "(PowerShell: $env:" + REGENERATE_ENV + "='1'; .\\gradlew test --tests '*SettingsDocTest*') "
            + "and commit docs/SETTINGS.md. Gradle does not track the file as a test input; "
            + "add --rerun-tasks if the run reports UP-TO-DATE.";

    @Test
    void theTableInTheDocumentIsExactlyTheGeneratedTable() throws IOException {
        String doc = currentDoc();

        assertEquals(SettingCatalog.markdownTable(), block(doc, TABLE_BEGIN, TABLE_END),
                "docs/SETTINGS.md table is stale. " + HOW_TO_REGENERATE);
    }

    @Test
    void theCountsInTheDocumentMatchTheCatalog() throws IOException {
        String doc = currentDoc();

        assertEquals(expectedCounts(), block(doc, COUNTS_BEGIN, COUNTS_END),
                "docs/SETTINGS.md counts are stale. " + HOW_TO_REGENERATE);
    }

    @Test
    void theHandWrittenIntroExplainsTheCategoriesAndTheSingleton() throws IOException {
        String doc = currentDoc();
        String intro = doc.substring(0, doc.indexOf(TABLE_BEGIN));

        assertTrue(intro.contains("singleton"), "the intro must state that PvP BOT has one global settings object");
        assertTrue(intro.contains(SettingCatalog.auditedVersion()), "the intro must name the audited version");
        for (Category category : Category.values()) {
            assertTrue(intro.contains(category.name()), "the intro must define " + category);
        }
    }

    @Test
    void theRationaleAfterTheTableCoversEveryNonRandomizedCategory() throws IOException {
        String doc = currentDoc();
        String outro = doc.substring(doc.indexOf(TABLE_END));

        assertTrue(outro.contains("GLOBAL_ONLY"));
        assertTrue(outro.contains("ADMIN_OPERATIONAL"));
        assertTrue(outro.contains("UNSUPPORTED"));
    }

    // ------------------------------------------------------------------ the mechanics of the check itself

    @Test
    void aMissingDocumentFailsWithTheInstructionToRegenerate(@TempDir Path dir) {
        AssertionFailedError error = assertThrows(AssertionFailedError.class,
                () -> read(dir.resolve("SETTINGS.md"), false));

        assertTrue(error.getMessage().contains("is missing"), error.getMessage());
        assertTrue(error.getMessage().contains(REGENERATE_ENV), error.getMessage());
    }

    @Test
    void aStaleTableIsDetected(@TempDir Path dir) throws IOException {
        Path doc = dir.resolve("SETTINGS.md");
        Files.writeString(doc, skeleton(SettingCatalog.markdownTable().replace("LOADOUT", "PATH"), expectedCounts()),
                StandardCharsets.UTF_8);

        String current = read(doc, false);

        assertNotEquals(SettingCatalog.markdownTable(), block(current, TABLE_BEGIN, TABLE_END));
    }

    @Test
    void regenerationRewritesOnlyTheGeneratedBlocksAndIsIdempotent(@TempDir Path dir) throws IOException {
        Path doc = dir.resolve("SETTINGS.md");
        String prose = skeleton("stale row", "- old: 1");
        Files.writeString(doc, prose.replace("\n", "\r\n"), StandardCharsets.UTF_8);

        String once = read(doc, true);

        assertEquals(skeleton(SettingCatalog.markdownTable(), expectedCounts()), once);
        assertEquals(once, Files.readString(doc, StandardCharsets.UTF_8), "the file on disk was rewritten");
        assertEquals(once, read(doc, true), "regenerating twice changes nothing");
        assertEquals(once, read(doc, false));
    }

    @Test
    void windowsLineEndingsInTheDocumentDoNotBreakTheComparison(@TempDir Path dir) throws IOException {
        Path doc = dir.resolve("SETTINGS.md");
        String text = skeleton(SettingCatalog.markdownTable(), expectedCounts());
        Files.writeString(doc, text.replace("\n", "\r\n"), StandardCharsets.UTF_8);

        String current = read(doc, false);

        assertEquals(SettingCatalog.markdownTable(), block(current, TABLE_BEGIN, TABLE_END));
        assertEquals(expectedCounts(), block(current, COUNTS_BEGIN, COUNTS_END));
    }

    @Test
    void aMissingMarkerIsReportedByName(@TempDir Path dir) throws IOException {
        Path doc = dir.resolve("SETTINGS.md");
        Files.writeString(doc, "# no markers here\n", StandardCharsets.UTF_8);
        String current = read(doc, false);

        AssertionFailedError error = assertThrows(AssertionFailedError.class,
                () -> block(current, TABLE_BEGIN, TABLE_END));

        assertTrue(error.getMessage().contains(TABLE_BEGIN), error.getMessage());
    }

    // ------------------------------------------------------------------ plumbing

    private static String skeleton(String table, String counts) {
        return "# Title\n\nintro\n\n" + TABLE_BEGIN + "\n" + table + "\n" + TABLE_END
                + "\n\nmiddle\n\n" + COUNTS_BEGIN + "\n" + counts + "\n" + COUNTS_END + "\n\noutro\n";
    }

    private static String currentDoc() throws IOException {
        return read(DOC, regenerationRequested());
    }

    private static String read(Path doc, boolean regenerate) throws IOException {
        if (!Files.isRegularFile(doc)) {
            fail(doc + " is missing (looked for " + doc.toAbsolutePath() + "). Restore it from version control, then "
                    + HOW_TO_REGENERATE);
        }
        String text = Files.readString(doc, StandardCharsets.UTF_8).replace("\r\n", "\n");
        if (regenerate) {
            text = replaceBlock(text, TABLE_BEGIN, TABLE_END, SettingCatalog.markdownTable());
            text = replaceBlock(text, COUNTS_BEGIN, COUNTS_END, expectedCounts());
            Files.writeString(doc, text, StandardCharsets.UTF_8);
        }
        return text;
    }

    private static boolean regenerationRequested() {
        String value = System.getenv(REGENERATE_ENV);
        return value != null && !value.isBlank() && !value.equals("0") && !value.equalsIgnoreCase("false");
    }

    private static String expectedCounts() {
        List<String> lines = new ArrayList<>();
        for (Category category : Category.values()) {
            lines.add("- " + category.name() + ": " + SettingCatalog.byCategory(category).size());
        }
        lines.add("- Total: " + SettingCatalog.all().size());
        return String.join("\n", lines);
    }

    private static int[] bounds(String doc, String begin, String end) {
        int b = doc.indexOf(begin);
        int e = doc.indexOf(end);
        assertTrue(b >= 0, "marker missing from the document: " + begin);
        assertTrue(e > b, "marker missing or out of order in the document: " + end);
        assertEquals(b, doc.lastIndexOf(begin), "marker must appear exactly once: " + begin);
        assertEquals(e, doc.lastIndexOf(end), "marker must appear exactly once: " + end);
        assertTrue(b == 0 || doc.charAt(b - 1) == '\n', begin + " must start its own line");
        int after = e + end.length();
        assertTrue(after == doc.length() || doc.charAt(after) == '\n', end + " must end its own line");
        return new int[] {b + begin.length(), e};
    }

    private static String block(String doc, String begin, String end) {
        int[] bounds = bounds(doc, begin, end);
        String inner = doc.substring(bounds[0], bounds[1]);
        if (inner.equals("\n")) {
            return "";
        }
        assertTrue(inner.startsWith("\n") && inner.endsWith("\n"),
                "the block between the markers must be on its own lines");
        return inner.substring(1, inner.length() - 1);
    }

    private static String replaceBlock(String doc, String begin, String end, String body) {
        int[] bounds = bounds(doc, begin, end);
        return doc.substring(0, bounds[0]) + "\n" + body + "\n" + doc.substring(bounds[1]);
    }
}

package dev.spawnbotswrapper.inhabitants.catalog;

import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.ValueType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The drift-test baseline: the settings of the audited PvP BOT release exactly as captured from its
 * {@code BotSettings} source, independent of the catalog under test. Updating it is the deliberate act
 * of auditing a new upstream version.
 */
final class UpstreamBaseline {
    static final String RESOURCE = "upstream-settings-" + SettingCatalog.auditedVersion() + ".csv";
    static final String HEADER = "field,type,min,max,default,commandKey";

    private UpstreamBaseline() {
    }

    /**
     * @param min NaN for booleans
     * @param max NaN for booleans
     */
    record Row(String field, ValueType type, double min, double max, String defaultValue, String commandKey) {
    }

    static List<Row> load() {
        try (InputStream in = UpstreamBaseline.class.getResourceAsStream("/" + RESOURCE)) {
            assertNotNull(in, "missing test resource " + RESOURCE);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            assertEquals(HEADER, reader.readLine(), "unexpected CSV header in " + RESOURCE);
            List<Row> rows = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    rows.add(parse(line));
                }
            }
            return List.copyOf(rows);
        } catch (IOException e) {
            throw new AssertionError("cannot read " + RESOURCE, e);
        }
    }

    static List<String> fields() {
        return load().stream().map(Row::field).toList();
    }

    private static Row parse(String line) {
        String[] cells = line.split(",", -1);
        assertEquals(6, cells.length, "expected 6 CSV cells: " + line);
        return new Row(cells[0], ValueType.valueOf(cells[1].toUpperCase(Locale.ROOT)),
                number(cells[2]), number(cells[3]), cells[4], cells[5]);
    }

    private static double number(String cell) {
        return cell.isEmpty() ? Double.NaN : Double.parseDouble(cell);
    }
}

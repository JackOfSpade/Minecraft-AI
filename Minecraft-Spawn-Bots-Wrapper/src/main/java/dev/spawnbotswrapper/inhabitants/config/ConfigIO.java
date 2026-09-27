package dev.spawnbotswrapper.inhabitants.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes {@code pvpbot_inhabitants.json}. Never throws on bad input: a file that cannot
 * be parsed yields the built-in defaults plus a {@link LoadResult#fatalError()} message and is left
 * untouched on disk, so a typo never destroys someone's hand-edited configuration.
 */
public final class ConfigIO {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ConfigIO() {
    }

    /**
     * @param config     always non-null and validated
     * @param warnings   repairs that were applied
     * @param fatalError non-null when the file existed but could not be parsed (defaults are in use)
     * @param created    true when a default file was just written
     */
    public record LoadResult(InhabitantsConfig config, List<String> warnings, String fatalError, boolean created) {
    }

    public static LoadResult load(Path file) {
        if (!Files.exists(file)) {
            InhabitantsConfig defaults = new InhabitantsConfig();
            List<String> warnings = ConfigValidator.validate(defaults);
            boolean written = false;
            try {
                save(file, defaults);
                written = true;
            } catch (IOException e) {
                warnings.add("could not write default config " + file + ": " + e.getMessage());
            }
            return new LoadResult(defaults, warnings, null, written);
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonReader reader = new JsonReader(new StringReader(RelaxedJson.relax(text)));
            reader.setLenient(true); // tolerate other hand-editing leniencies (unquoted names, single quotes)
            InhabitantsConfig parsed = GSON.fromJson(reader, InhabitantsConfig.class);
            if (parsed == null) {
                return fatal("the file is empty");
            }
            List<String> warnings = new ArrayList<>(ConfigValidator.validate(parsed));
            return new LoadResult(parsed, warnings, null, false);
        } catch (Exception e) {
            return fatal(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static LoadResult fatal(String why) {
        InhabitantsConfig defaults = new InhabitantsConfig();
        List<String> warnings = ConfigValidator.validate(defaults);
        return new LoadResult(defaults, warnings, "config could not be read (" + why
                + "); built-in defaults are in use and the file was NOT overwritten", false);
    }

    /** Atomic write (temp file + move) so a crash cannot leave a half-written config. */
    public static void save(Path file, InhabitantsConfig config) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(config, out);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Pretty JSON of a config, for tests and diagnostics. */
    public static String toJson(InhabitantsConfig config) {
        return GSON.toJson(config);
    }
}

package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.config.ConfigIO;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The live configuration: one file, one current {@link InhabitantsConfig}, swapped as a whole.
 * <p>
 * Everything that reads the configuration holds this as a {@code Supplier} and calls {@link #get()} each
 * time, so a reload takes effect everywhere without anyone re-wiring. A reload replaces the object (it never
 * edits the old one in place), so code that captured the previous config for the duration of one operation
 * sees a consistent snapshot.
 * <p>
 * A file that cannot be parsed is treated differently at startup and on reload. At startup the built-in
 * defaults are the only option and are used, loudly. On reload the configuration that WAS working is kept:
 * a typo made while editing must not silently reset every setting (including {@code enabled} and the exclude
 * list) to defaults on a running server.
 */
public final class ConfigHolder implements Supplier<InhabitantsConfig> {
    private static final String DEFAULTS_SENTENCE = "; built-in defaults are in use and the file was NOT overwritten";

    private final Path file;
    private final AtomicReference<InhabitantsConfig> current = new AtomicReference<>(new InhabitantsConfig());

    /**
     * @param swapped  true when the configuration object was replaced
     * @param messages warnings from the repairs applied to the file, plus the error when it could not be read
     */
    public record Reload(boolean swapped, List<String> messages) {
        public Reload {
            messages = List.copyOf(messages);
        }
    }

    public ConfigHolder(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    @Override
    public InhabitantsConfig get() {
        return current.get();
    }

    /** First load: reads (or creates) the file and installs the result, defaults included when it is broken. */
    public ConfigIO.LoadResult loadInitial() {
        ConfigIO.LoadResult result = ConfigIO.load(file);
        current.set(result.config());
        return result;
    }

    /** Re-reads the file; installs the new configuration unless the file could not be parsed. */
    public Reload reload() {
        ConfigIO.LoadResult result = ConfigIO.load(file);
        List<String> messages = new ArrayList<>(result.warnings());
        messages.addAll(result.notes());
        if (result.fatalError() != null) {
            String why = result.fatalError().replace(DEFAULTS_SENTENCE, "; the file was NOT overwritten");
            messages.add(0, "reload failed, the previous configuration stays active: " + why);
            return new Reload(false, messages);
        }
        current.set(result.config());
        return new Reload(true, messages);
    }
}

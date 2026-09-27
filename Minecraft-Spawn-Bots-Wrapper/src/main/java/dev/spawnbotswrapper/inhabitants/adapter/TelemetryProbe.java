package dev.spawnbotswrapper.inhabitants.adapter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * READ-ONLY inspection of PvP BOT's statistics opt-out ({@code <config>/pvpbot/stats_config.json}). PvP BOT
 * uploads the names of the bots it spawns, so an addon that creates hundreds of bots should tell the
 * operator. This class never writes that file: silently editing another mod's privacy setting is not the
 * addon's decision to make.
 * <p>
 * The interpretation mirrors upstream exactly, including its fail-open behaviour: statistics are ON unless
 * the file parses and the value stringifies to exactly {@code false}. (Upstream also treats an EMPTY file as
 * "off"; that quirk is reproduced so the warning matches what PvP BOT will really do.)
 */
final class TelemetryProbe {

    static final String RELATIVE_PATH = "pvpbot/stats_config.json";
    static final String KEY = "send_anonymous_statistics";

    private TelemetryProbe() {
    }

    enum Telemetry {
        /** The file explicitly says statistics are on. */
        ENABLED,
        /** File missing, unreadable, unparsable or without the key: PvP BOT then sends statistics. */
        ENABLED_BY_DEFAULT,
        DISABLED,
        /** The config directory is not known, so nothing can be said. */
        UNKNOWN;

        boolean sends() {
            return this == ENABLED || this == ENABLED_BY_DEFAULT;
        }
    }

    static Telemetry read(Path configDir) {
        if (configDir == null) {
            return Telemetry.UNKNOWN;
        }
        Path file = configDir.resolve(RELATIVE_PATH);
        try {
            if (!Files.isRegularFile(file)) {
                return Telemetry.ENABLED_BY_DEFAULT;
            }
            return interpret(Files.readString(file));
        } catch (IOException | RuntimeException e) {
            return Telemetry.ENABLED_BY_DEFAULT;
        }
    }

    /** Pure part of {@link #read}: the file's text to the effective setting. */
    static Telemetry interpret(String text) {
        JsonElement root;
        try {
            root = JsonParser.parseString(text == null ? "" : text);
        } catch (RuntimeException e) {
            return Telemetry.ENABLED_BY_DEFAULT;
        }
        if (root == null || root.isJsonNull()) {
            return Telemetry.DISABLED;
        }
        if (!root.isJsonObject()) {
            return Telemetry.ENABLED_BY_DEFAULT;
        }
        JsonObject object = root.getAsJsonObject();
        if (!object.has(KEY)) {
            return Telemetry.ENABLED_BY_DEFAULT;
        }
        String value = stringify(object.get(KEY));
        if ("false".equals(value)) {
            return Telemetry.DISABLED;
        }
        return "true".equals(value) ? Telemetry.ENABLED : Telemetry.ENABLED_BY_DEFAULT;
    }

    private static String stringify(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return "null";
        }
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            return p.isBoolean() ? String.valueOf(p.getAsBoolean()) : p.getAsString();
        }
        return e.toString();
    }
}

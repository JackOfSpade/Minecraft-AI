package io.github.zoyluo.minecraftai.integration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Startup sanity check for the VeinMiner integration. Our permission answer ({@link VeinMinerPermissionPolicy})
 * only takes effect when VeinMiner asks for it, i.e. with {@code "permissionRestricted": true} in
 * {@code <config dir>/Veinminer/settings.json}. Without that flag VeinMiner never consults the permission
 * API and bots vein-mine (the extra blocks bypass the bot's mining controller, drop tracking and safety
 * checks). This only reports the misconfiguration; it never changes anything and never throws.
 */
public final class VeinMinerConfigCheck {
    /** The Fabric mod id of VeinMiner v2. */
    public static final String MOD_ID = "veinminer";

    private VeinMinerConfigCheck() {
    }

    /**
     * Lenient parse of VeinMiner's settings.json: true only when the document is a JSON object whose
     * {@code permissionRestricted} member is the boolean {@code true} (or the string "true"). Anything
     * else (null, empty, malformed, wrong shape, missing key) is false.
     */
    public static boolean permissionRestricted(String settingsJson) {
        if (settingsJson == null || settingsJson.isBlank()) {
            return false;
        }
        try {
            JsonElement root = JsonParser.parseString(settingsJson);
            if (!root.isJsonObject()) {
                return false;
            }
            JsonObject object = root.getAsJsonObject();
            JsonElement value = object.get("permissionRestricted");
            if (value == null || !value.isJsonPrimitive()) {
                return false;
            }
            if (value.getAsJsonPrimitive().isBoolean()) {
                return value.getAsBoolean();
            }
            return value.getAsJsonPrimitive().isString() && "true".equalsIgnoreCase(value.getAsString().trim());
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    /**
     * @param veinMinerLoaded  whether the {@value #MOD_ID} mod is loaded
     * @param settingsJson     the contents of settings.json, or null when it is missing or unreadable
     * @return the WARN text to log, or null when nothing is wrong (VeinMiner absent, or restricted)
     */
    public static String warning(boolean veinMinerLoaded, String settingsJson) {
        if (!veinMinerLoaded || permissionRestricted(settingsJson)) {
            return null;
        }
        return "VeinMiner is loaded but its settings.json does not contain \"permissionRestricted\": true"
                + (settingsJson == null ? " (file missing or unreadable)" : "")
                + ": Minecraft-AI bots will vein-mine. Set permissionRestricted to true in config/Veinminer/settings.json.";
    }

    /** Reads {@code <configDir>/Veinminer/settings.json}; null when absent or unreadable. */
    public static String readSettings(Path configDir) {
        try {
            Path file = configDir.resolve("Veinminer").resolve("settings.json");
            return Files.isRegularFile(file) ? Files.readString(file) : null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }
}

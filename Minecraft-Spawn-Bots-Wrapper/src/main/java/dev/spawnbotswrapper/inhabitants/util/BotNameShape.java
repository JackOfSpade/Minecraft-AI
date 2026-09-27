package dev.spawnbotswrapper.inhabitants.util;

/**
 * The one shape every layer between the addon and vanilla agrees a bot name (or a name prefix) may take:
 * {@code [A-Za-z0-9_]}, 3-16 characters for a full name, at most 8 for a prefix. Every place that validates
 * or sanitizes a name or a prefix - {@code config.ConfigValidator}, {@code engine.NameGenerator},
 * {@code adapter.NameRules} - calls into this class instead of restating the character class and the
 * length bounds itself, so the four cannot silently drift apart the way they once did (a prefix accepted by
 * the config validator was, until this class existed, sometimes further trimmed by the name generator,
 * leaving the two disagreeing about what "the prefix" actually was).
 * <p>
 * Lives in {@code util} (which nothing else in the addon depends on) rather than in {@code config} or
 * {@code engine}, because both of those already depend on the other in one direction and adding this here
 * avoids creating a cycle between them.
 */
public final class BotNameShape {
    public static final int MIN_NAME_LENGTH = 3;
    public static final int MAX_NAME_LENGTH = 16;
    public static final int MAX_PREFIX_LENGTH = 8;

    private BotNameShape() {
    }

    public static boolean isAllowedChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }

    /** Whether {@code name} has the shape every layer accepts: charset and {@link #MIN_NAME_LENGTH}..{@link #MAX_NAME_LENGTH}. */
    public static boolean isValidName(String name) {
        if (name == null || name.length() < MIN_NAME_LENGTH || name.length() > MAX_NAME_LENGTH) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            if (!isAllowedChar(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Strips disallowed characters, truncates to {@link #MAX_PREFIX_LENGTH}, and drops a trailing underscore
     * left dangling by that truncation (a prefix must not itself end in one, since the generator always adds
     * its own separating underscore after it). The single canonical definition of "a valid prefix" - every
     * caller must use this instead of its own filter, so a config value and the prefix a name is actually
     * generated with can never differ.
     */
    public static String sanitizePrefix(String prefix) {
        if (prefix == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < prefix.length() && sb.length() < MAX_PREFIX_LENGTH; i++) {
            char c = prefix.charAt(i);
            if (isAllowedChar(c)) {
                sb.append(c);
            }
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == '_') {
            end--;
        }
        return sb.substring(0, end);
    }
}

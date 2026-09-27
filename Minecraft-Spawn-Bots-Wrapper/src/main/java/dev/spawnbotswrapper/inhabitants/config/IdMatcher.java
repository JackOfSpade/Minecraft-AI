package dev.spawnbotswrapper.inhabitants.config;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/**
 * Matches a registry id (and the tags it belongs to) against one config entry.
 * Entry syntax: {@code *} | {@code namespace:*} | {@code #namespace:tag} | {@code namespace:path}.
 * A bare {@code path} or {@code #tag} means the {@code minecraft} namespace. Case-insensitive.
 */
public final class IdMatcher {
    public static final String ANY = "*";

    private IdMatcher() {
    }

    /** Canonical form of a config entry: trimmed, lower-case, namespaced. Returns null for blank input. */
    public static String normalize(String entry) {
        if (entry == null) {
            return null;
        }
        String e = entry.trim().toLowerCase(Locale.ROOT);
        if (e.isEmpty()) {
            return null;
        }
        if (e.equals(ANY)) {
            return ANY;
        }
        boolean tag = e.startsWith("#");
        if (tag) {
            e = e.substring(1);
        }
        if (!e.contains(":")) {
            e = "minecraft:" + e;
        }
        return tag ? "#" + e : e;
    }

    public static boolean isTag(String normalizedEntry) {
        return normalizedEntry != null && normalizedEntry.startsWith("#");
    }

    public static boolean isNamespaceWildcard(String normalizedEntry) {
        return normalizedEntry != null && !isTag(normalizedEntry) && normalizedEntry.endsWith(":*")
                && !normalizedEntry.equals(ANY);
    }

    /** Namespace of a wildcard entry ({@code somemod:*} -> {@code somemod}), else null. */
    public static String wildcardNamespace(String normalizedEntry) {
        return isNamespaceWildcard(normalizedEntry)
                ? normalizedEntry.substring(0, normalizedEntry.length() - 2)
                : null;
    }

    public static String namespaceOf(String id) {
        int i = id.indexOf(':');
        return i < 0 ? "minecraft" : id.substring(0, i);
    }

    /**
     * @param entry   a config entry (any casing; normalised here)
     * @param id      the structure/dimension id, {@code namespace:path}
     * @param tagIds  ids (without '#') of every tag the id belongs to; may be empty
     */
    public static boolean matches(String entry, String id, Collection<String> tagIds) {
        String n = normalize(entry);
        if (n == null) {
            return false;
        }
        if (n.equals(ANY)) {
            return true;
        }
        if (isTag(n)) {
            String tag = n.substring(1);
            return tagIds != null && tagIds.contains(tag);
        }
        if (isNamespaceWildcard(n)) {
            return namespaceOf(id).equals(wildcardNamespace(n));
        }
        return n.equals(id);
    }

    /** True when any entry matches. An empty/null include list is the caller's business, not handled here. */
    public static boolean matchesAny(Collection<String> entries, String id, Set<String> tagIds) {
        if (entries == null) {
            return false;
        }
        for (String entry : entries) {
            if (matches(entry, id, tagIds)) {
                return true;
            }
        }
        return false;
    }
}

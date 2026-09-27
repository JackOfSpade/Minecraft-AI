package dev.spawnbotswrapper.inhabitants.command;

import dev.spawnbotswrapper.inhabitants.structure.IntBox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import static dev.spawnbotswrapper.inhabitants.command.Markup.label;

/** Small value formatters shared by the command output classes. Locale-independent on purpose. */
final class Fmt {
    private static final String[] LEVEL_NAMES = {"everyone", "moderators", "gamemasters", "admins", "owners"};

    private Fmt() {
    }

    /** {@code 0.65 -> "65%"}, {@code 0.005 -> "0.5%"}: one decimal only when it matters. */
    static String percent(double fraction) {
        if (Double.isNaN(fraction)) {
            return "?";
        }
        String s = String.format(Locale.ROOT, "%.1f", fraction * 100.0);
        return (s.endsWith(".0") ? s.substring(0, s.length() - 2) : s) + "%";
    }

    static String decimal(double value, int places) {
        return String.format(Locale.ROOT, "%." + places + "f", value);
    }

    /** Integer with thousands separators ({@code 123456 -> "123,456"}); the world can hold many structures. */
    static String num(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    static String plural(long n, String singular) {
        return num(n) + " " + singular + (n == 1 ? "" : "s");
    }

    static String chunk(int x, int z) {
        return x + "," + z;
    }

    static String xyz(double x, double y, double z) {
        return decimal(x, 1) + ", " + decimal(y, 1) + ", " + decimal(z, 1);
    }

    static String box(IntBox b) {
        return "(" + b.minX() + "," + b.minY() + "," + b.minZ() + ") to ("
                + b.maxX() + "," + b.maxY() + "," + b.maxZ() + ")  "
                + b.sizeX() + "x" + b.sizeY() + "x" + b.sizeZ();
    }

    /** Rounded horizontal distance in blocks between two points. */
    static long blocks(double x1, double z1, double x2, double z2) {
        return Math.round(Math.hypot(x1 - x2, z1 - z2));
    }

    static String orDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    /** Vanilla op level with its name, e.g. {@code "2 (gamemasters)"}; out-of-range values are clamped. */
    static String permissionLevel(int level) {
        int l = Math.max(0, Math.min(LEVEL_NAMES.length - 1, level));
        return l + " (" + LEVEL_NAMES[l] + ")";
    }

    /** Flattens multi-line strings into single lines and drops blank ones. */
    static List<String> lines(Collection<String> multiline) {
        List<String> out = new ArrayList<>();
        if (multiline == null) {
            return out;
        }
        for (String block : multiline) {
            if (block == null) {
                continue;
            }
            for (String line : block.split("\\R")) {
                if (!line.isBlank()) {
                    out.add(line);
                }
            }
        }
        return out;
    }

    /**
     * Appends at most {@code max} of {@code items} (already formatted) to {@code out}, then one hint line
     * saying how many were left out, so a huge listing can never flood the chat.
     *
     * @param moreHint what to add after "... and N more", e.g. {@code "narrow the radius"}; may be empty
     */
    static void addCapped(List<String> out, List<String> items, int max, String moreHint) {
        int shown = Math.min(max, items.size());
        for (int i = 0; i < shown; i++) {
            out.add(items.get(i));
        }
        int rest = items.size() - shown;
        if (rest > 0) {
            String hint = moreHint == null || moreHint.isEmpty() ? "" : " (" + moreHint + ")";
            out.add(label("... and " + num(rest) + " more" + hint));
        }
    }
}

package dev.spawnbotswrapper.inhabitants.command;

import java.util.ArrayList;
import java.util.List;

/**
 * Inline colour markup for command output, so the formatters can stay pure {@code List<String>} builders
 * with per-token colour and no Minecraft dependency. A colour is the classic section sign plus one code
 * character ({@code §6}); {@code §r} resets. Only this restrained palette is ever produced:
 * gold titles, gray labels and hints, aqua identifiers, and green / yellow / red for status. Plain values
 * carry no code and therefore render in the default colour.
 * <p>
 * The thin Brigadier layer converts a marked-up line into a styled {@code Component} ({@link ChatText}); on a
 * console the styling simply disappears. Everything dynamic (bot names, ids and messages that come from
 * other mods or from PvP BOT) passes through {@link #esc} so it can never inject a colour of its own.
 */
final class Markup {
    static final char PREFIX = '§';

    private Markup() {
    }

    static String title(String s) {
        return wrap('6', s);
    }

    /** Labels, separators and hints. */
    static String label(String s) {
        return wrap('7', s);
    }

    /** Registry ids, bot names, anything the reader may want to copy. */
    static String id(String s) {
        return wrap('b', s);
    }

    static String good(String s) {
        return wrap('a', s);
    }

    static String warn(String s) {
        return wrap('e', s);
    }

    static String bad(String s) {
        return wrap('c', s);
    }

    /** A value in the default colour (only sanitised). */
    static String plain(String s) {
        return esc(s);
    }

    /** Removes the markup prefix from untrusted text so it cannot start a colour sequence. */
    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.indexOf(PREFIX) < 0 ? s : s.replace(String.valueOf(PREFIX), "");
    }

    /** The visible text of a marked-up line (what a console or a test sees). */
    static String strip(String line) {
        if (line == null) {
            return "";
        }
        if (line.indexOf(PREFIX) < 0) {
            return line;
        }
        StringBuilder sb = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == PREFIX) {
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static List<String> strip(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        for (String l : lines) {
            out.add(strip(l));
        }
        return out;
    }

    /** A run of visible text with one colour; {@code color} is a lower-case hex digit, or 0 for "default". */
    record Span(String text, char color) {
    }

    /**
     * Splits a marked-up line into coloured runs. Unknown codes are dropped together with their prefix, a
     * dangling prefix at the end of the line is ignored, and {@code §r} returns to the default colour.
     */
    static List<Span> spans(String line) {
        List<Span> out = new ArrayList<>();
        if (line == null || line.isEmpty()) {
            return out;
        }
        StringBuilder run = new StringBuilder();
        char color = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c != PREFIX) {
                run.append(c);
                continue;
            }
            if (i + 1 >= line.length()) {
                break;
            }
            char code = Character.toLowerCase(line.charAt(++i));
            char next = isColorCode(code) ? code : code == 'r' ? 0 : color;
            if (next != color) {
                flush(out, run, color);
                color = next;
            }
        }
        flush(out, run, color);
        return out;
    }

    private static void flush(List<Span> out, StringBuilder run, char color) {
        if (run.length() > 0) {
            out.add(new Span(run.toString(), color));
            run.setLength(0);
        }
    }

    private static boolean isColorCode(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }

    private static String wrap(char code, String s) {
        String text = esc(s);
        return text.isEmpty() ? "" : "" + PREFIX + code + text + PREFIX + 'r';
    }
}

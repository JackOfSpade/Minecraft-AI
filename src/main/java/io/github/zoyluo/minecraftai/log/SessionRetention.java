package io.github.zoyluo.minecraftai.log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides which previous play-session log directories to delete. A plain "keep the newest N sessions" rule
 * lets a few bot-less game starts (menu, a world without bots, a crash on load) evict every session in which the
 * bots actually did something, leaving nothing to review after the next restart. So the two kinds of session are
 * counted separately: the newest {@code keepBotSessions} sessions that contain bot activity survive, and at most
 * {@code keepBotlessSessions} bot-less ones. The rule is pure (names and a flag in, names out) so it is unit
 * tested without touching the file system; {@link #hasBotActivity} is the only file-system probe.
 */
public final class SessionRetention {
    /** One previous session directory: its name (a timestamp-sortable id) and whether any bot logged in it. */
    public record Session(String name, boolean hasBotActivity) {
    }

    private SessionRetention() {
    }

    /**
     * Names of the sessions to delete. {@code previousSessions} excludes the session being started (it is always
     * kept and is not counted). Order of the input does not matter; "newest" is by name, which is a UTC
     * timestamp id. Negative limits are treated as zero.
     */
    public static List<String> sessionsToDelete(List<Session> previousSessions, int keepBotSessions, int keepBotlessSessions) {
        List<Session> newestFirst = new ArrayList<>(previousSessions);
        newestFirst.sort((a, b) -> b.name().compareTo(a.name()));
        int botLeft = Math.max(0, keepBotSessions);
        int botlessLeft = Math.max(0, keepBotlessSessions);
        List<String> delete = new ArrayList<>();
        for (Session session : newestFirst) {
            if (session.hasBotActivity()) {
                if (botLeft > 0) {
                    botLeft--;
                } else {
                    delete.add(session.name());
                }
            } else if (botlessLeft > 0) {
                botlessLeft--;
            } else {
                delete.add(session.name());
            }
        }
        return delete;
    }

    /**
     * True when the session directory holds a per-bot log (live in {@code by-bot/} or rotated into
     * {@code archive/}) for any bot other than the {@code _system} pseudo-bot that collects entries with no bot.
     */
    public static boolean hasBotActivity(Path sessionDir) {
        return containsBotLog(sessionDir.resolve("by-bot"), false) || containsBotLog(sessionDir.resolve("archive"), true);
    }

    private static boolean containsBotLog(Path dir, boolean archive) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (var stream = Files.list(dir)) {
            return stream.anyMatch(path -> {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".log") || name.startsWith("_system")) {
                    return false;
                }
                // Rotated archives also hold the session-wide all-<date>-<reason>.log, which is not a bot.
                return !(archive && name.startsWith("all-"));
            });
        } catch (IOException exception) {
            // Unreadable: treat as having activity so an I/O hiccup can never delete a real session.
            return true;
        }
    }
}

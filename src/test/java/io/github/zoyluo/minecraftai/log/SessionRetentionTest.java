package io.github.zoyluo.minecraftai.log;

import io.github.zoyluo.minecraftai.log.SessionRetention.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SessionRetentionTest {
    private static Session bots(String name) {
        return new Session(name, true);
    }

    private static Session botless(String name) {
        return new Session(name, false);
    }

    @Test
    void botlessRestartsNeverEvictSessionsWithBotActivity() {
        // The regression: one real play session followed by many bot-less game starts (the old rule kept the
        // newest three sessions, so three restarts deleted the only sessions worth reviewing).
        List<Session> sessions = new ArrayList<>();
        sessions.add(bots("20260101-100000"));
        sessions.add(bots("20260102-100000"));
        for (int i = 1; i <= 8; i++) {
            sessions.add(botless("20260103-1" + i + "0000"));
        }
        List<String> delete = SessionRetention.sessionsToDelete(sessions, 10, 2);
        assertFalse(delete.contains("20260101-100000"));
        assertFalse(delete.contains("20260102-100000"));
        assertEquals(6, delete.size());
        // The two newest bot-less ones survive, the older ones go.
        assertFalse(delete.contains("20260103-180000"));
        assertFalse(delete.contains("20260103-170000"));
        assertTrue(delete.contains("20260103-160000"));
    }

    @Test
    void botSessionsAreBoundedAndOldestGoFirst() {
        List<Session> sessions = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            sessions.add(bots("2026010" + i + "-000000"));
        }
        assertEquals(Set.of("20260101-000000", "20260102-000000"),
                new HashSet<>(SessionRetention.sessionsToDelete(sessions, 3, 0)));
    }

    @Test
    void inputOrderDoesNotMatter() {
        List<Session> shuffled = List.of(
                botless("20260105-000000"), bots("20260101-000000"), bots("20260103-000000"),
                botless("20260102-000000"), bots("20260104-000000"));
        assertEquals(Set.of("20260101-000000", "20260102-000000"),
                new HashSet<>(SessionRetention.sessionsToDelete(shuffled, 2, 1)));
    }

    @Test
    void nothingToDeleteWhenWithinLimitsAndNegativeLimitsMeanZero() {
        List<Session> sessions = List.of(bots("a"), botless("b"));
        assertTrue(SessionRetention.sessionsToDelete(sessions, 5, 5).isEmpty());
        assertTrue(SessionRetention.sessionsToDelete(List.of(), 5, 5).isEmpty());
        assertEquals(Set.of("a", "b"), new HashSet<>(SessionRetention.sessionsToDelete(sessions, -1, -3)));
    }

    @Test
    void botActivityIsAPerBotLogButNotTheSystemOrSessionWideLog(@TempDir Path dir) throws IOException {
        Path onlySystem = Files.createDirectories(dir.resolve("s1"));
        Files.createDirectories(onlySystem.resolve("by-bot"));
        Files.writeString(onlySystem.resolve("all.log"), "x");
        Files.writeString(onlySystem.resolve("by-bot").resolve("_system.log"), "x");
        assertFalse(SessionRetention.hasBotActivity(onlySystem));

        Path withBot = Files.createDirectories(dir.resolve("s2").resolve("by-bot"));
        Files.writeString(withBot.resolve("_system.log"), "x");
        Files.writeString(withBot.resolve("Moss.log"), "x");
        assertTrue(SessionRetention.hasBotActivity(dir.resolve("s2")));

        // A session that rotated its logs into archive/ still counts when a bot's log was archived.
        Path rotated = Files.createDirectories(dir.resolve("s3").resolve("archive"));
        Files.writeString(rotated.resolve("all-2026-01-01-size.log"), "x");
        Files.writeString(rotated.resolve("_system-2026-01-01-size.log"), "x");
        assertFalse(SessionRetention.hasBotActivity(dir.resolve("s3")));
        Files.writeString(rotated.resolve("Moss-2026-01-01-size.log"), "x");
        assertTrue(SessionRetention.hasBotActivity(dir.resolve("s3")));

        assertFalse(SessionRetention.hasBotActivity(dir.resolve("does-not-exist")));
    }
}

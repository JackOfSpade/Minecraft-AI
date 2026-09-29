package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The no-progress window of a Baritone land-follow route. */
class FollowProgressWindowTest {
    @Test
    void aBotThatStandsStillIsStalledAfterTheWindowAndNotBefore() {
        FollowProgressWindow window = new FollowProgressWindow();
        assertFalse(window.stalled(0, 10.0, 0, 0), "arming tick");
        assertFalse(window.stalled(FollowProgressWindow.WINDOW_TICKS - 1, 10.0, 0.2, 0.1));
        assertTrue(window.stalled(FollowProgressWindow.WINDOW_TICKS, 10.0, 0.2, 0.1));
    }

    @Test
    void gettingCloserOrMovingAwayFromTheAnchorIsProgress() {
        FollowProgressWindow window = new FollowProgressWindow();
        window.stalled(0, 10.0, 0, 0);
        // closer by more than a block re-arms the window
        assertFalse(window.stalled(90, 8.5, 0.5, 0));
        assertFalse(window.stalled(150, 8.5, 0.5, 0), "the window restarted at tick 90");
        assertTrue(window.stalled(190, 8.5, 0.5, 0));
        // a detour that gets no closer but moves (around a wall) is progress too
        FollowProgressWindow detour = new FollowProgressWindow();
        detour.stalled(0, 10.0, 0, 0);
        assertFalse(detour.stalled(99, 12.0, 5, 0), "moved five blocks: re-armed");
        assertFalse(detour.stalled(150, 12.0, 5, 0));
        assertTrue(detour.stalled(199, 12.0, 5, 0));
    }

    @Test
    void movingReArmsTheWindowButDoesNotForgetTheBestDistance() {
        FollowProgressWindow window = new FollowProgressWindow();
        window.stalled(0, 10.0, 0, 0);
        assertFalse(window.stalled(50, 12.0, 5, 0), "moved five blocks: re-armed, the best distance stays 10");
        // 10.5 is nearer than the 12 of the re-arm but nowhere near the 10 the bot had before: not an approach, the window runs on
        assertFalse(window.stalled(60, 10.5, 4, 0));
        assertTrue(window.stalled(150, 10.5, 4, 0), "no real closing progress for the window since the re-arm at tick 50");
        // a real approach (a block nearer than the best distance) still resets it
        FollowProgressWindow approach = new FollowProgressWindow();
        approach.stalled(0, 10.0, 0, 0);
        approach.stalled(50, 12.0, 5, 0);
        assertFalse(approach.stalled(60, 8.9, 4, 0), "closer than 10 by more than a block");
        assertFalse(approach.stalled(150, 8.9, 4, 0));
        assertTrue(approach.stalled(160, 8.9, 4, 0));
    }

    @Test
    void clearingRearmsAndAnUnarmedWindowNeverReportsStalled() {
        FollowProgressWindow window = new FollowProgressWindow();
        window.stalled(0, 10.0, 0, 0);
        assertTrue(window.stalled(500, 10.0, 0, 0));
        window.clear();
        assertFalse(window.stalled(600, 10.0, 0, 0), "a new route starts a new window");
        assertFalse(window.stalled(650, 10.0, 0, 0));
        assertTrue(window.stalled(700, 10.0, 0, 0));
    }

    @Test
    void followTreatsATimeoutLikeAFailureAndRoutesThroughTheWindow() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/FollowTask.java"));
        assertTrue(source.contains("ended.status() == NavOutcome.Status.FAILED || ended.status() == NavOutcome.Status.TIMEOUT"),
                "a timed-out Baritone route backs off like a failed one, it is not restarted at once");
        int running = source.indexOf("if (!pack.isPathExecutorIdle()) {");
        int window = source.indexOf("baritoneProgress.stalled(", running);
        int abandon = source.indexOf("pack.cancelBaritoneRoute(\"follow_no_progress\")", window);
        int backoff = source.indexOf("repathBackoff = true;", abandon);
        assertTrue(running > 0 && window > running && abandon > window && backoff > abandon,
                "a route without progress is cancelled and followed by a back-off");
    }
}

package io.github.zoyluo.minecraftai.brain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The blocked-tool result must not promise a restart that only a deferred (player-instruction) call gets. */
final class SafetyTaskActiveExceptionTest {
    @Test
    void deferredResultPromisesAnAutomaticStart() {
        String text = new SafetyTaskActiveException("fight").resultText(true);
        assertTrue(text.startsWith("safety_task_active: fight"), text);
        assertTrue(text.contains("kept and will be started automatically"), text);
        assertTrue(text.contains("Tell the player"), text);
    }

    @Test
    void notDeferredResultDoesNotPromiseARestartOrAskForAnAnnouncement() {
        String text = new SafetyTaskActiveException("fight").resultText(false);
        assertTrue(text.startsWith("safety_task_active: fight"), text);
        assertFalse(text.contains("kept and will be started"), text);
        assertFalse(text.contains("Tell the player"), text);
        assertTrue(text.contains("not kept"), text);
        assertTrue(text.contains("do not start other tasks now"), text);
    }
}

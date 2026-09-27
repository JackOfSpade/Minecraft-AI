package dev.spawnbotswrapper.inhabitants.adapter;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsTest {

    @Test
    void reflectionWrappersAreUnwrappedToTheRealCause() {
        IllegalStateException root = new IllegalStateException("real problem");
        assertSame(root, Diagnostics.unwrap(new InvocationTargetException(root)));
        assertSame(root, Diagnostics.unwrap(new ExecutionException(new InvocationTargetException(root))));
        assertSame(root, Diagnostics.unwrap(root));
    }

    @Test
    void aWrapperWithoutACauseIsLeftAlone() {
        InvocationTargetException bare = new InvocationTargetException(null);
        assertSame(bare, Diagnostics.unwrap(bare));
    }

    @Test
    void describeIsSingleLineAndNamesTheRealCause() {
        String text = Diagnostics.describe(new InvocationTargetException(new IllegalStateException("bad\nthing")));
        assertEquals("IllegalStateException: bad thing", text);
        assertFalse(text.contains("\n"));
        assertEquals("NullPointerException", Diagnostics.describe(new NullPointerException()));
        assertEquals("unknown error", Diagnostics.describe(null));
    }

    @Test
    void describeIsBounded() {
        String text = Diagnostics.describe(new RuntimeException("x".repeat(5000)));
        assertTrue(text.length() < 300);
        assertTrue(text.endsWith("..."));
    }

    @Test
    void describeSurvivesAThrowableWhoseMessageThrows() {
        Throwable nasty = new RuntimeException() {
            @Override
            public String getMessage() {
                throw new IllegalStateException("no message for you");
            }
        };
        assertEquals("unprintable error", Diagnostics.describe(nasty));
    }

    @Test
    void aFailureIsWarnedOncePerDistinctContextAndCause() {
        RecordingSink sink = new RecordingSink();
        Diagnostics log = new Diagnostics(sink);
        for (int i = 0; i < 100; i++) {
            log.failure("reading the bot list", new IllegalStateException("boom"));
        }
        assertEquals(1, sink.warn.size());
        log.failure("reading the bot list", new IllegalStateException("a different boom"));
        log.failure("polling a spawn", new IllegalStateException("boom"));
        assertEquals(3, sink.warn.size());
        assertTrue(sink.warn.get(0).contains("reading the bot list") && sink.warn.get(0).contains("boom"));
    }

    @Test
    void onceKeysAreBoundedSoAHostileUpstreamCannotGrowTheSet() {
        RecordingSink sink = new RecordingSink();
        Diagnostics log = new Diagnostics(sink);
        for (int i = 0; i < Diagnostics.MAX_ONCE_KEYS + 100; i++) {
            log.warnOnce("key" + i, "message " + i);
        }
        assertEquals(Diagnostics.MAX_ONCE_KEYS, sink.warn.size());
        assertFalse(log.warnOnce("brand-new-key", "late"), "beyond the bound new keys are dropped");
        assertFalse(log.warnOnce("key0", "again"), "known keys stay suppressed");
    }

    @Test
    void debugOnceLogsAtDebugLevelOnlyAndOnlyOnce() {
        RecordingSink sink = new RecordingSink();
        Diagnostics log = new Diagnostics(sink);
        assertTrue(log.debugOnce("k", "d"));
        assertFalse(log.debugOnce("k", "d"));
        assertEquals(1, sink.debug.size());
        assertTrue(sink.warn.isEmpty());
    }
}

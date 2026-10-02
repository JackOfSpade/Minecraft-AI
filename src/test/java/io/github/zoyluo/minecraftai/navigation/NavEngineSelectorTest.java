package io.github.zoyluo.minecraftai.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Baritone-only selection and its fail-closed bootstrap. A failure that means this JVM cannot
 * use Baritone is logged once and stops navigation for the session; an ordinary request failure
 * returns only that request's typed stop result.
 */
final class NavEngineSelectorTest {
    private MinecraftAiConfig original;

    @BeforeEach
    void remember() {
        original = MinecraftAiConfig.get();
        NavEngineSelector.resetForTests();
    }

    @AfterEach
    void restore() throws Exception {
        setConfig(original);
        NavEngineSelector.resetForTests();
    }

    private static void selectEngine(NavEngine engine) throws Exception {
        MinecraftAiConfig config = MinecraftAiConfig.defaults();
        MinecraftAiConfig.Nav nav = config.nav();
        MinecraftAiConfig.Nav chosen = new MinecraftAiConfig.Nav(nav.jumpReach(), nav.sidleAfter(), nav.sidleLimit(), nav.hardLimit(),
                nav.lookahead(), nav.nodeRetry(), nav.sprintMinDist(), nav.maxSafeFall(), engine.configValue());
        setConfig(new MinecraftAiConfig(config.profile(), config.operatorCapabilities(), config.llm(), config.perception(),
                config.brain(), config.watchdog(), config.logging(), config.survival(), config.combat(), config.night(),
                config.mining(), config.goal(), chosen, config.pickup(), config.conversation()));
    }

    private static void setConfig(MinecraftAiConfig config) throws Exception {
        Field instance = MinecraftAiConfig.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, config);
    }

    @Test
    void parsingAndThePureSelectionRule() {
        assertEquals(NavEngine.BARITONE, NavEngine.parse(null));
        assertEquals(NavEngine.BARITONE, NavEngine.parse("legacy"));
        assertEquals(NavEngine.BARITONE, NavEngine.parse("Baritone"));
        assertEquals(NavEngine.BARITONE, NavEngine.parse("something else"));
        assertTrue(NavEngine.isKnown("baritone") && NavEngine.isKnown("legacy")
                && !NavEngine.isKnown("x") && !NavEngine.isKnown(null));
        assertEquals(NavEngine.BARITONE, NavEngineSelector.effective(NavEngine.BARITONE, false));
        assertEquals(NavEngine.BARITONE, NavEngineSelector.effective(NavEngine.BARITONE, true),
                "identity remains Baritone even when requests are stopped");
        assertEquals(NavEngine.BARITONE, NavEngineSelector.effective(NavEngine.LEGACY, true));
        assertEquals(NavEngine.BARITONE, NavEngineSelector.effective(NavEngine.LEGACY, false));
    }

    @Test
    void theDefaultConfigEntersBaritone() {
        AtomicInteger runs = new AtomicInteger();
        String answer = NavEngineSelector.attempt("test", () -> {
            runs.incrementAndGet();
            return "baritone";
        }, () -> "stopped");
        assertEquals("baritone", answer);
        assertEquals(1, runs.get(), "the shipped config always asks Baritone");
        assertTrue(NavEngineSelector.baritoneSelected());
        assertFalse(NavEngineSelector.baritoneLive(), "nothing marks Baritone live until an instance exists");
    }

    @Test
    void theBaritoneEngineRunsTheBaritoneWork() throws Exception {
        selectEngine(NavEngine.BARITONE);
        assertTrue(NavEngineSelector.baritoneSelected());
        assertEquals("baritone", NavEngineSelector.attempt("test", () -> "baritone", () -> "stopped"));
        assertFalse(NavEngineSelector.baritoneFailed());
    }

    @Test
    void aLinkageFailureMarksBaritoneUnavailableOnceAndStopsForTheSession() throws Exception {
        selectEngine(NavEngine.BARITONE);
        AtomicInteger runs = new AtomicInteger();
        String first = NavEngineSelector.attempt("path_to", () -> {
            runs.incrementAndGet();
            throw new NoClassDefFoundError("baritone/api/BaritoneAPI");
        }, () -> "stopped");
        assertEquals("stopped", first, "the failing request is answered by the typed stop result");
        assertEquals(1, runs.get());
        assertTrue(NavEngineSelector.baritoneFailed());
        assertTrue(NavEngineSelector.failureDescription().contains("path_to")
                && NavEngineSelector.failureDescription().contains("BaritoneAPI"), NavEngineSelector.failureDescription());
        // From now on the configured/effective identity is still Baritone, but work is not even attempted.
        assertEquals(NavEngine.BARITONE, NavEngineSelector.configured());
        assertEquals(NavEngine.BARITONE, NavEngineSelector.effective());
        assertFalse(NavEngineSelector.baritoneSelected());
        String second = NavEngineSelector.attempt("path_to", () -> {
            runs.incrementAndGet();
            return "baritone";
        }, () -> "stopped");
        assertEquals("stopped", second);
        assertEquals(1, runs.get(), "Baritone is not asked again after it failed to initialise");
        // Marking is idempotent and keeps the first reason ("log once").
        assertFalse(NavEngineSelector.markBaritoneUnavailable("later", new LinkageError("later")));
        assertTrue(NavEngineSelector.failureDescription().contains("BaritoneAPI"));
    }

    @Test
    void everyKindOfInitialisationFailureCounts() throws Exception {
        Throwable[] failures = {
                new ExceptionInInitializerError(new IllegalStateException("PalettedContainer has no Data field.")),
                new UnsatisfiedLinkError("nether-pathfinder"),
                new NoSuchMethodError("remapped"),
                new RuntimeException("wrapped", new NoClassDefFoundError("x")),
                new IllegalStateException("outer", new RuntimeException("mid", new IncompatibleClassChangeError()))
        };
        for (Throwable failure : failures) {
            NavEngineSelector.resetForTests();
            selectEngine(NavEngine.BARITONE);
            assertTrue(NavEngineSelector.isInitialisationFailure(failure), failure.toString());
            assertEquals("stopped", NavEngineSelector.attempt("t", () -> {
                NavEngineSelectorTest.<RuntimeException>sneakyThrow(failure);
                return "baritone";
            }, () -> "stopped"));
            assertTrue(NavEngineSelector.baritoneFailed(), failure.toString());
        }
    }

    @Test
    void anOrdinaryFailureStopsOnlyThatRequest() throws Exception {
        selectEngine(NavEngine.BARITONE);
        assertFalse(NavEngineSelector.isInitialisationFailure(new IllegalStateException("bad request")));
        assertFalse(NavEngineSelector.isInitialisationFailure(new IllegalArgumentException("x", new NullPointerException())));
        String answer = NavEngineSelector.attempt("t", () -> {
            throw new IllegalStateException("one bad request");
        }, () -> "stopped");
        assertEquals("stopped", answer);
        assertFalse(NavEngineSelector.baritoneFailed(), "an ordinary exception does not retire Baritone");
        assertEquals("baritone", NavEngineSelector.attempt("t", () -> "baritone", () -> "stopped"), "the next request tries Baritone again");
    }

    @Test
    void aVirtualMachineErrorIsNotSwallowed() throws Exception {
        selectEngine(NavEngine.BARITONE);
        assertThrows(OutOfMemoryError.class, () -> NavEngineSelector.attempt("t", () -> {
            throw new OutOfMemoryError("test");
        }, () -> "stopped"));
        assertFalse(NavEngineSelector.baritoneFailed());
    }

    @Test
    void aBotCannotOptOutOfBaritone() throws Exception {
        java.util.UUID special = java.util.UUID.randomUUID();
        java.util.UUID ordinary = java.util.UUID.randomUUID();
        NavEngineSelector.setBotEngine(special, NavEngine.BARITONE);
        assertTrue(NavEngineSelector.baritoneSelectedFor(special));
        assertTrue(NavEngineSelector.baritoneSelectedFor(ordinary), "the global engine is Baritone");
        assertEquals("baritone", NavEngineSelector.attempt(special, "t", () -> "baritone", () -> "stopped"));
        assertEquals("baritone", NavEngineSelector.attempt(ordinary, "t", () -> "baritone", () -> "stopped"));
        // A failed Baritone retires the engine for the override too.
        NavEngineSelector.markBaritoneUnavailable("test", new LinkageError("x"));
        assertFalse(NavEngineSelector.baritoneSelectedFor(special));
        assertEquals(NavEngine.BARITONE, NavEngineSelector.configuredFor(special), "still what was asked for");
        // The global engine, and clearing.
        NavEngineSelector.resetForTests();
        selectEngine(NavEngine.BARITONE);
        assertTrue(NavEngineSelector.baritoneSelectedFor(ordinary));
        NavEngineSelector.setBotEngine(ordinary, NavEngine.LEGACY);
        assertTrue(NavEngineSelector.baritoneSelectedFor(ordinary), "a legacy request cannot opt a bot out");
        NavEngineSelector.clearBotEngine(ordinary);
        assertTrue(NavEngineSelector.baritoneSelectedFor(ordinary));
        NavEngineSelector.setBotEngine(special, NavEngine.LEGACY);
        NavEngineSelector.setBotEngine(special, null);
        assertEquals(NavEngine.BARITONE, NavEngineSelector.configuredFor(special));
    }

    @Test
    void theLiveFlagIsWhatKeepsTheHooksAwayFromBaritone() {
        assertFalse(NavEngineSelector.baritoneLive());
        NavEngineSelector.markBaritoneLive();
        assertTrue(NavEngineSelector.baritoneLive());
        NavEngineSelector.resetForTests();
        assertFalse(NavEngineSelector.baritoneLive());
    }

    @Test
    void hooksAreGatedOnLiveAndNotFailedAndAFailureAfterLiveRetiresBaritoneOnce() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger teardowns = new AtomicInteger();
        NavEngineSelector.setUnavailableHook(teardowns::incrementAndGet);
        NavEngineSelector.hook("t", runs::incrementAndGet);
        assertEquals(0, runs.get(), "never live: the hook does not run");
        assertFalse(NavEngineSelector.baritoneActive());
        assertEquals("idle", NavEngineSelector.query("t", () -> "busy", "idle"));
        NavEngineSelector.markBaritoneLive();
        assertTrue(NavEngineSelector.baritoneActive());
        NavEngineSelector.hook("t", runs::incrementAndGet);
        assertEquals(1, runs.get());
        assertEquals("busy", NavEngineSelector.query("t", () -> "busy", "idle"));
        // A class of Baritone that cannot load on a tick after it was live: hook retires it, the caller carries on.
        NavEngineSelector.hook("tick", () -> {
            throw new NoClassDefFoundError("baritone/pathing/movement/MovementHelper");
        });
        assertTrue(NavEngineSelector.baritoneFailed());
        assertEquals(1, teardowns.get(), "every instance and route is torn down once, best effort");
        assertTrue(NavEngineSelector.baritoneLive(), "the flag that a Baritone existed stays");
        assertFalse(NavEngineSelector.baritoneActive(), "but nothing may call into it any more");
        NavEngineSelector.hook("t", runs::incrementAndGet);
        assertEquals(1, runs.get(), "after the failure no hook touches Baritone");
        assertEquals("idle", NavEngineSelector.query("t", () -> {
            runs.incrementAndGet();
            return "busy";
        }, "idle"));
        assertEquals(1, runs.get());
        // Idempotent: the teardown does not run again.
        assertFalse(NavEngineSelector.markBaritoneUnavailable("later", new LinkageError("again")));
        assertEquals(1, teardowns.get());
    }

    @Test
    void aFailingTeardownDoesNotStopTheFallback() {
        NavEngineSelector.setUnavailableHook(() -> {
            throw new NoClassDefFoundError("the very class that failed");
        });
        NavEngineSelector.markBaritoneLive();
        assertTrue(NavEngineSelector.markBaritoneUnavailable("x", new LinkageError("x")));
        assertFalse(NavEngineSelector.baritoneActive());
    }

    @Test
    void handleFailureClassifiesLikeAttempt() {
        NavEngineSelector.markBaritoneLive();
        assertFalse(NavEngineSelector.handleFailure("tick", new IllegalStateException("one bad tick")), "an ordinary failure is only logged");
        assertTrue(NavEngineSelector.baritoneActive());
        assertThrows(OutOfMemoryError.class, () -> NavEngineSelector.handleFailure("tick", new OutOfMemoryError()));
        assertTrue(NavEngineSelector.baritoneActive(), "a true VM error is rethrown and does not retire Baritone");
        assertFalse(NavEngineSelector.handleFailure("tick", new StackOverflowError()), "a stack overflow is contained like an ordinary failure");
        NavEngineSelector.clearFailureForTests();
        assertTrue(NavEngineSelector.handleFailure("tick", new RuntimeException("mixin", new NoClassDefFoundError("x"))));
        assertTrue(NavEngineSelector.baritoneFailed());
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }
}

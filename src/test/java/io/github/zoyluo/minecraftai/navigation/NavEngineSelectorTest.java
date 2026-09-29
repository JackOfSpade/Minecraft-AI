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
 * Engine selection and the fail-soft bootstrap: with the legacy engine Baritone work is never run; with the Baritone engine a
 * failure that means "this JVM cannot use Baritone" (a class that does not link or initialise: a mixin or remap problem in some
 * modpack) is logged once, marks Baritone unavailable for the session and answers with the legacy fallback from then on, while an
 * ordinary failure of one request only falls back for that request.
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
        assertEquals(NavEngine.LEGACY, NavEngine.parse(null));
        assertEquals(NavEngine.LEGACY, NavEngine.parse("legacy"));
        assertEquals(NavEngine.BARITONE, NavEngine.parse("Baritone"));
        assertEquals(NavEngine.LEGACY, NavEngine.parse("something else"));
        assertTrue(NavEngine.isKnown("baritone") && !NavEngine.isKnown("x") && !NavEngine.isKnown(null));
        assertEquals(NavEngine.BARITONE, NavEngineSelector.effective(NavEngine.BARITONE, false));
        assertEquals(NavEngine.LEGACY, NavEngineSelector.effective(NavEngine.BARITONE, true), "a failed Baritone is not selected");
        assertEquals(NavEngine.LEGACY, NavEngineSelector.effective(NavEngine.LEGACY, true));
        assertEquals(NavEngine.LEGACY, NavEngineSelector.effective(NavEngine.LEGACY, false));
    }

    @Test
    void theDefaultConfigNeverEntersBaritone() {
        AtomicInteger runs = new AtomicInteger();
        String answer = NavEngineSelector.attempt("test", () -> {
            runs.incrementAndGet();
            return "baritone";
        }, () -> "legacy");
        assertEquals("legacy", answer);
        assertEquals(0, runs.get(), "with nav.engine=legacy no Baritone work may run");
        assertFalse(NavEngineSelector.baritoneSelected());
        assertFalse(NavEngineSelector.baritoneLive(), "nothing marks Baritone live until an instance exists");
    }

    @Test
    void theBaritoneEngineRunsTheBaritoneWork() throws Exception {
        selectEngine(NavEngine.BARITONE);
        assertTrue(NavEngineSelector.baritoneSelected());
        assertEquals("baritone", NavEngineSelector.attempt("test", () -> "baritone", () -> "legacy"));
        assertFalse(NavEngineSelector.baritoneFailed());
    }

    @Test
    void aLinkageFailureMarksBaritoneUnavailableOnceAndFallsBackForTheSession() throws Exception {
        selectEngine(NavEngine.BARITONE);
        AtomicInteger runs = new AtomicInteger();
        String first = NavEngineSelector.attempt("path_to", () -> {
            runs.incrementAndGet();
            throw new NoClassDefFoundError("baritone/api/BaritoneAPI");
        }, () -> "legacy");
        assertEquals("legacy", first, "the failing request is answered by the fallback");
        assertEquals(1, runs.get());
        assertTrue(NavEngineSelector.baritoneFailed());
        assertTrue(NavEngineSelector.failureDescription().contains("path_to")
                && NavEngineSelector.failureDescription().contains("BaritoneAPI"), NavEngineSelector.failureDescription());
        // From now on the configured engine is still baritone, the effective one is not, and the work is not even attempted.
        assertEquals(NavEngine.BARITONE, NavEngineSelector.configured());
        assertEquals(NavEngine.LEGACY, NavEngineSelector.effective());
        assertFalse(NavEngineSelector.baritoneSelected());
        String second = NavEngineSelector.attempt("path_to", () -> {
            runs.incrementAndGet();
            return "baritone";
        }, () -> "legacy");
        assertEquals("legacy", second);
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
            assertEquals("legacy", NavEngineSelector.attempt("t", () -> {
                NavEngineSelectorTest.<RuntimeException>sneakyThrow(failure);
                return "baritone";
            }, () -> "legacy"));
            assertTrue(NavEngineSelector.baritoneFailed(), failure.toString());
        }
    }

    @Test
    void anOrdinaryFailureOnlyFallsBackForThatRequest() throws Exception {
        selectEngine(NavEngine.BARITONE);
        assertFalse(NavEngineSelector.isInitialisationFailure(new IllegalStateException("bad request")));
        assertFalse(NavEngineSelector.isInitialisationFailure(new IllegalArgumentException("x", new NullPointerException())));
        String answer = NavEngineSelector.attempt("t", () -> {
            throw new IllegalStateException("one bad request");
        }, () -> "legacy");
        assertEquals("legacy", answer);
        assertFalse(NavEngineSelector.baritoneFailed(), "an ordinary exception does not retire Baritone");
        assertEquals("baritone", NavEngineSelector.attempt("t", () -> "baritone", () -> "legacy"), "the next request tries Baritone again");
    }

    @Test
    void aVirtualMachineErrorIsNotSwallowed() throws Exception {
        selectEngine(NavEngine.BARITONE);
        assertThrows(OutOfMemoryError.class, () -> NavEngineSelector.attempt("t", () -> {
            throw new OutOfMemoryError("test");
        }, () -> "legacy"));
        assertFalse(NavEngineSelector.baritoneFailed());
    }

    @Test
    void aBotCanRunOnTheOtherEngineWithoutChangingTheOthers() throws Exception {
        java.util.UUID special = java.util.UUID.randomUUID();
        java.util.UUID ordinary = java.util.UUID.randomUUID();
        NavEngineSelector.setBotEngine(special, NavEngine.BARITONE);
        assertTrue(NavEngineSelector.baritoneSelectedFor(special));
        assertFalse(NavEngineSelector.baritoneSelectedFor(ordinary), "the global engine is legacy");
        assertEquals("baritone", NavEngineSelector.attempt(special, "t", () -> "baritone", () -> "legacy"));
        assertEquals("legacy", NavEngineSelector.attempt(ordinary, "t", () -> "baritone", () -> "legacy"));
        // A failed Baritone retires the engine for the override too.
        NavEngineSelector.markBaritoneUnavailable("test", new LinkageError("x"));
        assertFalse(NavEngineSelector.baritoneSelectedFor(special));
        assertEquals(NavEngine.BARITONE, NavEngineSelector.configuredFor(special), "still what was asked for");
        // The global engine, and clearing.
        NavEngineSelector.resetForTests();
        selectEngine(NavEngine.BARITONE);
        assertTrue(NavEngineSelector.baritoneSelectedFor(ordinary));
        NavEngineSelector.setBotEngine(ordinary, NavEngine.LEGACY);
        assertFalse(NavEngineSelector.baritoneSelectedFor(ordinary), "a bot can also opt out of a global baritone engine");
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

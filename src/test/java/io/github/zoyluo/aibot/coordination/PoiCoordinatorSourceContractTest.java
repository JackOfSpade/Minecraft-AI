package io.github.zoyluo.aibot.coordination;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source contract of {@code coordination/PoiCoordinator.java} (mining-assist design 6.5, P2 R3): the stop path
 * pauses through {@code IntentController}, never through {@code TaskManager.pauseUserIntent} (that call is
 * reserved for P3's hold), mandatory is evaluated before the dedupe registry is ever consulted, {@code
 * onCandidate} never references the LLM advisor at all (P2 has no advisor), and the DigDown descending-task
 * lookup uses the right source at each of its two call sites: {@code stopNow} reads {@code getActive}
 * <em>before</em> calling {@code IntentController.pause} (real-server regression guard, correction #10b:
 * {@code DigDownTask.onPause} converts phase DESCEND to RETURN as its own side effect, so reading it any later
 * -- even correctly via {@code peekPaused} once the task is on the pause stack, per the original correction
 * #10 below -- would always observe the post-pause RETURN phase and could never select the climb-out notice);
 * {@code tick}'s restart-rehydration branch reads {@code peekPaused}, since by then the task was already
 * paused by an earlier stop and {@code getActive} is unconditionally empty (correction #10 of the P2 contract:
 * {@code IntentController.pause} routes through {@code pauseFor}, which removes the task from {@code active}
 * before pushing it onto the pause stack). Reads the production source as text, like the other source-contract
 * tests.
 */
class PoiCoordinatorSourceContractTest {
    private static final Path FILE = Path.of(
            "src/main/java/io/github/zoyluo/aibot/coordination/PoiCoordinator.java");

    private static String source() throws IOException {
        return Files.readString(FILE);
    }

    /** Source without comments, so a Javadoc mentioning a future phase's concept (e.g. "P3's advisor") is not
     * mistaken for a real reference to it. */
    private static String code() throws IOException {
        return source().replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    /** The text of one method, from its signature to its closing brace at method indentation. */
    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature + " must exist");
        int end = source.indexOf("\n    }\n", start);
        assertTrue(end > start, signature + " must end with a method-level closing brace");
        return source.substring(start, end);
    }

    @Test
    void theFileExists() {
        assertTrue(Files.exists(FILE));
    }

    @Test
    void stopNowAndMandatoryFlowPauseThroughIntentControllerOnly() throws IOException {
        String source = source();
        String stopNow = method(source, "private void stopNow(");
        String mandatoryFlow = method(source, "private void mandatoryFlow(");
        assertTrue(stopNow.contains("IntentController.INSTANCE.pause("), "stopNow must pause through IntentController");
        assertTrue(mandatoryFlow.contains("stopNow("), "mandatoryFlow routes its stop through stopNow");
        assertFalse(source.contains("TaskManager.INSTANCE.pauseUserIntent("),
                "the hold-and-wait pause is P3 infrastructure; P2 never calls it");
    }

    @Test
    void mandatoryIsEvaluatedBeforeTheDedupeRegistryIsEverConsulted() throws IOException {
        String onCandidate = method(source(), "public void onCandidate(");
        int mandatoryCheck = onCandidate.indexOf("result.band() == PoiScorer.Band.MANDATORY");
        int suppressedCheck = onCandidate.indexOf("PoiRegistry.suppressed(");
        assertTrue(mandatoryCheck >= 0 && suppressedCheck > mandatoryCheck,
                "a mandatory candidate must branch out (bypassing the registry) before suppressed() is ever called");
    }

    @Test
    void onCandidateNeverReferencesTheLlmAdvisorAtAll() throws IOException {
        String code = code();
        for (String token : new String[] {"consult(", "PoiAdvisor", "Advisor."}) {
            assertFalse(code.contains(token), "P2's fallback matrix never touches the P3 advisor, found " + token);
        }
    }

    @Test
    void stopNowCapturesTheDescendingTaskFromGetActiveBeforePausingIt() throws IOException {
        String source = source();
        String stopNow = method(source, "private void stopNow(");
        int getActiveCall = stopNow.indexOf("TaskManager.INSTANCE.getActive(bot)");
        int pauseCall = stopNow.indexOf("IntentController.INSTANCE.pause(");
        assertTrue(getActiveCall >= 0 && pauseCall > getActiveCall,
                "stopNow must read whether the about-to-be-paused task is a descending DigDownTask from "
                        + "getActive BEFORE calling IntentController.pause: DigDownTask.onPause converts "
                        + "DESCEND to RETURN as its own side effect (design 6.1), so reading it any later -- "
                        + "even via peekPaused once the task is on the pause stack -- would always observe "
                        + "the post-pause RETURN phase and could never select the climb-out notice");
        assertFalse(stopNow.contains("TaskManager.INSTANCE.peekPaused("),
                "stopNow must not read peekPaused for the descending lookup: the task is not paused yet at "
                        + "the point stopNow needs to know its phase");
    }

    @Test
    void ticksRestartRehydrationDescendingLookupUsesPeekPausedSinceTheTaskIsAlreadyPaused() throws IOException {
        String tick = method(source(), "public void tick(");
        assertTrue(tick.contains("TaskManager.INSTANCE.peekPaused(bot)"),
                "at rehydration time the task was already paused by an earlier stop -- IntentController.pause "
                        + "already moved it onto the pause stack -- so peekPaused (not getActive, which is "
                        + "unconditionally empty for a paused task) is the only live task available to read");
        assertFalse(tick.contains("TaskManager.INSTANCE.getActive("),
                "getActive is unconditionally empty for an already-paused task at rehydration time");
    }

    @Test
    void poiCoordinatorIsGuardedByTheAssistObservationBannedTokenScan() throws IOException {
        String contract = Files.readString(Path.of(
                "src/test/java/io/github/zoyluo/aibot/mining/assist/AssistObservationSourceContractTest.java"));
        assertTrue(contract.contains("\"coordination/PoiCoordinator.java\""),
                "the banned-token scan must cover the new coordinator too");
    }
}

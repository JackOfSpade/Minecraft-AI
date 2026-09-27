package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.adapter.SpawnPolicy.Decision;
import dev.spawnbotswrapper.inhabitants.adapter.SpawnPolicy.PollInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnPolicyTest {

    // ---------------------------------------------------------------- tier order

    @Test
    void autoPrefersThePositionOverloadThenPlainThenTheCommand() {
        assertEquals(List.of(SpawnTier.CLASS_POS, SpawnTier.CLASS, SpawnTier.COMMAND),
                SpawnPolicy.tierOrder(SpawnBackend.AUTO, true, true, true));
    }

    @Test
    void autoSkipsWhatDoesNotExist() {
        assertEquals(List.of(SpawnTier.CLASS, SpawnTier.COMMAND),
                SpawnPolicy.tierOrder(SpawnBackend.AUTO, false, true, true));
        assertEquals(List.of(SpawnTier.CLASS_POS, SpawnTier.COMMAND),
                SpawnPolicy.tierOrder(SpawnBackend.AUTO, true, false, true));
        assertEquals(List.of(SpawnTier.COMMAND),
                SpawnPolicy.tierOrder(SpawnBackend.AUTO, false, false, true));
        assertEquals(List.of(SpawnTier.CLASS_POS),
                SpawnPolicy.tierOrder(SpawnBackend.AUTO, true, false, false));
        assertEquals(List.of(), SpawnPolicy.tierOrder(SpawnBackend.AUTO, false, false, false));
    }

    @Test
    void classNeverFallsBackToTheCommand() {
        assertEquals(List.of(SpawnTier.CLASS_POS, SpawnTier.CLASS),
                SpawnPolicy.tierOrder(SpawnBackend.CLASS, true, true, true));
        assertEquals(List.of(), SpawnPolicy.tierOrder(SpawnBackend.CLASS, false, false, true));
    }

    @Test
    void commandNeverUsesTheClassApi() {
        assertEquals(List.of(SpawnTier.COMMAND), SpawnPolicy.tierOrder(SpawnBackend.COMMAND, true, true, true));
        assertEquals(List.of(), SpawnPolicy.tierOrder(SpawnBackend.COMMAND, true, true, false));
    }

    @Test
    void aMissingBackendMeansAuto() {
        assertEquals(SpawnPolicy.tierOrder(SpawnBackend.AUTO, true, true, true),
                SpawnPolicy.tierOrder(null, true, true, true));
    }

    @Test
    void primaryTierIsTheFirstOrNone() {
        assertEquals(SpawnTier.CLASS_POS, SpawnPolicy.primary(List.of(SpawnTier.CLASS_POS, SpawnTier.COMMAND)));
        assertEquals(SpawnTier.NONE, SpawnPolicy.primary(List.of()));
    }

    @Test
    void tierLabelsAreTheOnesTheStatusContractNames() {
        assertEquals("CLASS(pos)", SpawnTier.CLASS_POS.label());
        assertEquals("CLASS", SpawnTier.CLASS.label());
        assertEquals("COMMAND", SpawnTier.COMMAND.label());
        assertEquals("NONE", SpawnTier.NONE.label());
    }

    @Test
    void backendParsingIsLenient() {
        assertEquals(SpawnBackend.AUTO, SpawnBackend.parse(null));
        assertEquals(SpawnBackend.AUTO, SpawnBackend.parse(""));
        assertEquals(SpawnBackend.AUTO, SpawnBackend.parse("nonsense"));
        assertEquals(SpawnBackend.CLASS, SpawnBackend.parse(" class "));
        assertEquals(SpawnBackend.COMMAND, SpawnBackend.parse("Command"));
    }

    // ---------------------------------------------------------------- poll decisions

    private static PollInput input(String failure, boolean present, boolean bot, boolean listed, long now,
                                   long seen, boolean relisted, long relistTick, boolean canRelist) {
        return new PollInput("Inh_Foo", failure, present, bot, listed, now, seen, relisted, relistTick, canRelist);
    }

    @Test
    void aRecordedFailureIsFinal() {
        Decision d = SpawnPolicy.decide(input("name taken", true, true, true, 10, -1, false, -1, true));
        assertEquals("name taken", assertInstanceOf(Decision.Failed.class, d).reason());
    }

    @Test
    void noEntityYetIsPendingNeverFailedTheEngineOwnsTheDeadline() {
        assertInstanceOf(Decision.Pending.class, SpawnPolicy.decide(input(null, false, false, false, 10, -1, false, -1, true)));
        assertInstanceOf(Decision.Pending.class, SpawnPolicy.decide(input(null, false, false, true, 10, -1, false, -1, true)),
                "listed but no entity: PvP BOT lists the name before the entity exists");
    }

    @Test
    void anOnlinePlayerThatIsNotABotMeansTheNameWasTaken() {
        Decision d = SpawnPolicy.decide(input(null, true, false, false, 10, -1, false, -1, true));
        assertTrue(assertInstanceOf(Decision.Failed.class, d).reason().contains("real player"));
        Decision listed = SpawnPolicy.decide(input(null, true, false, true, 10, -1, false, -1, true));
        assertInstanceOf(Decision.Failed.class, listed);
    }

    @Test
    void aBotEntityThatPvpBotListsIsReady() {
        assertInstanceOf(Decision.Ready.class, SpawnPolicy.decide(input(null, true, true, true, 10, -1, false, -1, true)));
    }

    @Test
    void anUnlistedBotIsGivenAGraceBeforeItIsRelisted() {
        for (long elapsed = 0; elapsed < SpawnPolicy.RELIST_GRACE_TICKS; elapsed++) {
            Decision d = SpawnPolicy.decide(input(null, true, true, false, 100 + elapsed, 100, false, -1, true));
            assertInstanceOf(Decision.Pending.class, d, "elapsed " + elapsed);
        }
        assertInstanceOf(Decision.Pending.class, SpawnPolicy.decide(input(null, true, true, false, 100, -1, false, -1, true)),
                "first sighting (seen tick not recorded yet) starts the grace");
        assertInstanceOf(Decision.Relist.class,
                SpawnPolicy.decide(input(null, true, true, false, 100 + SpawnPolicy.RELIST_GRACE_TICKS, 100, false, -1, true)));
    }

    @Test
    void relistIsRequestedOnlyOnceThenVerified() {
        long relistAt = 200;
        for (long elapsed = 0; elapsed < SpawnPolicy.RELIST_VERIFY_TICKS; elapsed++) {
            Decision d = SpawnPolicy.decide(input(null, true, true, false, relistAt + elapsed, 100, true, relistAt, true));
            assertInstanceOf(Decision.Pending.class, d, "verifying, elapsed " + elapsed);
        }
        Decision d = SpawnPolicy.decide(input(null, true, true, false,
                relistAt + SpawnPolicy.RELIST_VERIFY_TICKS, 100, true, relistAt, true));
        assertTrue(assertInstanceOf(Decision.Failed.class, d).reason().contains("still does not list"));
    }

    @Test
    void aRelistedBotThatBecomesListedIsReady() {
        assertInstanceOf(Decision.Ready.class, SpawnPolicy.decide(input(null, true, true, true, 205, 100, true, 200, true)));
    }

    @Test
    void withoutAnAdoptPathAnUnlistedBotIsAFailureNotAnEndlessWait() {
        Decision d = SpawnPolicy.decide(input(null, true, true, false, 100 + SpawnPolicy.RELIST_GRACE_TICKS, 100, false, -1, false));
        String reason = assertInstanceOf(Decision.Failed.class, d).reason();
        assertTrue(reason.contains("COMMAND") && reason.contains("Inh_Foo"), reason);
    }

    @Test
    void theRecordedFailureBeatsAnEntityThatHappensToExist() {
        Decision d = SpawnPolicy.decide(input("PvP BOT integration is not available", true, true, true, 10, -1, false, -1, true));
        assertInstanceOf(Decision.Failed.class, d);
    }
}

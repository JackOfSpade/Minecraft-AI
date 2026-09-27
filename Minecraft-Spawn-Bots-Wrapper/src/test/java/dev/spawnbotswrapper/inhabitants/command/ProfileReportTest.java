package dev.spawnbotswrapper.inhabitants.command;

import dev.spawnbotswrapper.inhabitants.engine.PopulationView.BotLocation;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileReportTest {

    private static BotLocation location(BotRecord bot) {
        return new BotLocation(Fixtures.key("minecraft:pillager_outpost", 4, -7), bot);
    }

    private static BotRecord spawnedWithProfile() {
        BotRecord b = Fixtures.bot(2, "Inh_Steve", BotState.SPAWNED);
        b.profile = Fixtures.profile();
        b.profileApplied = true;
        b.profileVersion = 1;
        b.spawnAttempts = 1;
        return b;
    }

    private static boolean has(List<String> lines, String fragment) {
        return Markup.strip(lines).stream().anyMatch(l -> l.contains(fragment));
    }

    @Test
    void aSpawnedBotShowsIdentityStateUuidPositionAndTheRenderedProfile() {
        List<String> out = ProfileReport.format(location(spawnedWithProfile()),
                () -> List.of("archetype: guard", "  loadout: iron sword"));
        List<String> t = Markup.strip(out);

        assertEquals("Inhabitant Inh_Steve", t.get(0));
        assertEquals("  structure: minecraft:pillager_outpost at chunk 4,-7 in minecraft:overworld", t.get(1));
        assertEquals("  state: SPAWNED  |  index 2  |  spawn attempts 1", t.get(2));
        assertEquals("  uuid: " + Fixtures.UUID_TEXT, t.get(3));
        assertEquals("  position: 10.5, 64.0, -3.3, yaw 90", t.get(4));
        assertEquals("  profile: applied to the live bot (format v1)", t.get(5));
        assertEquals("archetype: guard", t.get(6));
        assertEquals("  loadout: iron sword", t.get(7));
        assertEquals(8, t.size());
    }

    @Test
    void aBotWithoutAProfileYetSaysSoAndDoesNotCallTheRenderer() {
        BotRecord b = Fixtures.bot(0, "Inh_Wait", BotState.PLANNED);
        AtomicInteger calls = new AtomicInteger();
        List<String> out = ProfileReport.format(location(b), () -> {
            calls.incrementAndGet();
            return List.of("should not appear");
        });

        assertTrue(has(out, "No profile yet: it is generated when the bot spawns (state PLANNED)."),
                Markup.strip(out).toString());
        assertTrue(has(out, "position: not chosen yet"), Markup.strip(out).toString());
        assertTrue(has(out, "uuid: not seen yet"), Markup.strip(out).toString());
        assertEquals(0, calls.get());
    }

    @Test
    void aFailedBotShowsItsFailureReason() {
        BotRecord b = Fixtures.bot(0, "Inh_Bad", BotState.FAILED);
        b.spawnAttempts = 3;
        b.failure = "no valid position in the structure";
        List<String> out = ProfileReport.format(location(b), () -> List.of());
        assertTrue(has(out, "state: FAILED  |  index 0  |  spawn attempts 3"), Markup.strip(out).toString());
        assertTrue(has(out, "last failure: no valid position in the structure"), Markup.strip(out).toString());
        assertTrue(Markup.strip(out).stream().noneMatch(l -> l.contains("position: 0")), "no bogus position for a failed bot");
    }

    @Test
    void aProfileNotYetAppliedIsFlagged() {
        BotRecord b = spawnedWithProfile();
        b.profileApplied = false;
        b.profileVersion = 3;
        assertTrue(has(ProfileReport.format(location(b), () -> List.of()),
                "profile: generated, not applied yet (format v3)"));
    }

    @Test
    void aFailingRendererDegradesToOneLineInsteadOfLosingTheAnswer() {
        List<String> out = ProfileReport.format(location(spawnedWithProfile()), () -> {
            throw new UnsupportedOperationException("skeleton");
        });
        List<String> t = Markup.strip(out);
        assertEquals("Inhabitant Inh_Steve", t.get(0));
        assertTrue(has(out, "The profile could not be rendered: UnsupportedOperationException: skeleton (see the server log)"),
                t.toString());
    }

    @Test
    void aLinkageErrorInTheRendererIsAlsoContained() {
        List<String> out = ProfileReport.format(location(spawnedWithProfile()), () -> {
            throw new NoClassDefFoundError("gone");
        });
        assertTrue(has(out, "The profile could not be rendered: NoClassDefFoundError: gone"), Markup.strip(out).toString());
    }

    @Test
    void aNullRenderedBodyIsTreatedAsEmpty() {
        List<String> out = ProfileReport.format(location(spawnedWithProfile()), () -> null);
        assertEquals(6, out.size());
    }

    @Test
    void aHugeProfileBodyIsCapped() {
        List<String> body = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            body.add("line " + i);
        }
        List<String> t = Markup.strip(ProfileReport.format(location(spawnedWithProfile()), () -> body));
        assertTrue(t.contains("line 80") && !t.contains("line 81"), t.toString());
        assertEquals("... and 120 more", t.get(t.size() - 1));
    }

    @Test
    void profileTextCannotInjectColour() {
        BotRecord b = spawnedWithProfile();
        b.failure = "boom §4red";
        List<String> out = ProfileReport.format(location(b), () -> List.of("kit §aextra"));
        assertFalse(out.stream().anyMatch(l -> l.contains("§4red") || l.contains("§aextra")), out.toString());
    }

    @Test
    void theUnknownBotMessageQuotesTheNameSafely() {
        String msg = ProfileReport.unknownBot("Nobody§c");
        assertTrue(msg.startsWith("No inhabitant named 'Nobodyc'."), msg);
        assertFalse(msg.contains("§"), msg);
    }
}

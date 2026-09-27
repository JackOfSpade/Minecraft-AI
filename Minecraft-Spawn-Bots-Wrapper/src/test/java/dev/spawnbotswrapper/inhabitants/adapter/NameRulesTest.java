package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.adapter.NameRules.Check;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameRulesTest {

    @Test
    void acceptsThePortableNameSubsetOnly() {
        assertTrue(NameRules.isValid("Inh_Foo"));
        assertTrue(NameRules.isValid("abc"));
        assertTrue(NameRules.isValid("A1_b2_C3_d4_E5_f"), "exactly 16 characters");
        assertTrue(NameRules.isValid("0123456789abcdef"));

        assertFalse(NameRules.isValid(null));
        assertFalse(NameRules.isValid(""));
        assertFalse(NameRules.isValid("ab"), "shorter than 3");
        assertFalse(NameRules.isValid("0123456789abcdefg"), "longer than 16");
        assertFalse(NameRules.isValid("bad name"));
        assertFalse(NameRules.isValid("bad-name"), "Brigadier accepts it, vanilla profile rules and HeroBot do not need it");
        assertFalse(NameRules.isValid("bad.name"));
        assertFalse(NameRules.isValid("naïve"));
        assertFalse(NameRules.isValid("inh\n"), "a trailing newline must not slip through a regex anchor");
        assertFalse(NameRules.isValid("x; op me"), "a name can never carry a second command");
    }

    @Test
    void keyIsCaseInsensitiveAndTolerantOfNull() {
        assertEquals("inh_foo", NameRules.key("Inh_FOO"));
        assertEquals("", NameRules.key(null));
    }

    @Test
    void aNameNobodyKnowsIsFree() {
        assertEquals(Check.FREE, NameRules.check("Inh_Foo", false, Set.of()));
        assertEquals(Check.FREE, NameRules.check("Inh_Foo", false, Set.of("someone_else")));
    }

    @Test
    void anOnlinePlayerBlocksTheName() {
        assertEquals(Check.ONLINE_PLAYER, NameRules.check("Inh_Foo", true, Set.of()));
    }

    @Test
    void pvpBotsListBlocksTheNameWhateverTheCase() {
        // Vanilla and PvP BOT disagree about case; the addon must be stricter than either.
        assertEquals(Check.LISTED, NameRules.check("Inh_Foo", false, Set.of("inh_foo")));
        assertEquals(Check.LISTED, NameRules.check("INH_FOO", false, Set.of("inh_foo")));
        assertEquals(Check.LISTED, NameRules.check("inh_foo", false, Set.of("inh_foo")));
    }

    @Test
    void anUnreadableListMeansUnknownNeverFree() {
        assertEquals(Check.LISTING_UNKNOWN, NameRules.check("Inh_Foo", false, null));
    }

    @Test
    void anOnlinePlayerWinsOverAnUnreadableList() {
        assertEquals(Check.ONLINE_PLAYER, NameRules.check("Inh_Foo", true, null));
    }

    @Test
    void invalidFormatWinsOverEverythingElse() {
        assertEquals(Check.INVALID_FORMAT, NameRules.check("no", true, Set.of("no")));
        assertEquals(Check.INVALID_FORMAT, NameRules.check(null, false, Set.of()));
    }

    @Test
    void refusalTextsSayWhichNameAndWhy() {
        assertTrue(NameRules.refusal(Check.ONLINE_PLAYER, "Inh_Foo").contains("Inh_Foo"));
        assertTrue(NameRules.refusal(Check.ONLINE_PLAYER, "Inh_Foo").contains("online player"));
        assertTrue(NameRules.refusal(Check.LISTED, "Inh_Foo").contains("listed by PvP BOT"));
        assertTrue(NameRules.refusal(Check.INVALID_FORMAT, "x").contains("3-16"));
        assertTrue(NameRules.refusal(Check.LISTING_UNKNOWN, "Inh_Foo").contains("cannot be confirmed"));
    }
}

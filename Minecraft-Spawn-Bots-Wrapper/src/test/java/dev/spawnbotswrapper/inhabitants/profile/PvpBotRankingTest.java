package dev.spawnbotswrapper.inhabitants.profile;

import org.junit.jupiter.api.Test;

import static dev.spawnbotswrapper.inhabitants.profile.ProfileTestSupport.NS;
import static org.junit.jupiter.api.Assertions.*;

/** The ranking facts the loadout layout relies on, checked against the analysed PvP BOT tables. */
class PvpBotRankingTest {

    @Test
    void armorScoresFollowPvpBotsTierOrder() {
        assertEquals(100, PvpBotRanking.armorScore(NS + "netherite_helmet"));
        assertEquals(100, PvpBotRanking.armorScore(NS + "netherite_boots"));
        assertEquals(80, PvpBotRanking.armorScore(NS + "diamond_chestplate"));
        assertEquals(60, PvpBotRanking.armorScore(NS + "iron_leggings"));
        assertEquals(55, PvpBotRanking.armorScore(NS + "turtle_helmet"));
        assertEquals(50, PvpBotRanking.armorScore(NS + "chainmail_boots"));
        assertEquals(40, PvpBotRanking.armorScore(NS + "golden_helmet"));
        assertEquals(20, PvpBotRanking.armorScore(NS + "leather_chestplate"));
        assertEquals(10, PvpBotRanking.armorScore(NS + "elytra"));
        assertEquals(0, PvpBotRanking.armorScore(NS + "copper_helmet"), "copper armor is invisible to PvP BOT");
        assertEquals(0, PvpBotRanking.armorScore(NS + "diamond_sword"));
        assertEquals(0, PvpBotRanking.armorScore(null));
    }

    @Test
    void turtleHelmetRanksBetweenIronAndChainmail() {
        assertTrue(PvpBotRanking.armorScore(NS + "iron_helmet") > PvpBotRanking.armorScore(NS + "turtle_helmet"));
        assertTrue(PvpBotRanking.armorScore(NS + "turtle_helmet") > PvpBotRanking.armorScore(NS + "chainmail_helmet"));
    }

    @Test
    void weaponScoresMatchTheDocumentedOrderingWithPreferSwordOn() {
        // preferSword on: netherite sword 13 > diamond sword 12 > iron sword 11 > stone sword 10 = netherite axe 10
        // > golden/wooden sword 9 = diamond/iron/stone axe 9 = trident 9 > golden/wooden axe 7 > mace 6
        assertEquals(13, PvpBotRanking.weaponScore(NS + "netherite_sword", true));
        assertEquals(12, PvpBotRanking.weaponScore(NS + "diamond_sword", true));
        assertEquals(11, PvpBotRanking.weaponScore(NS + "iron_sword", true));
        assertEquals(10, PvpBotRanking.weaponScore(NS + "stone_sword", true));
        assertEquals(10, PvpBotRanking.weaponScore(NS + "netherite_axe", true));
        assertEquals(9, PvpBotRanking.weaponScore(NS + "golden_sword", true));
        assertEquals(9, PvpBotRanking.weaponScore(NS + "wooden_sword", true));
        assertEquals(9, PvpBotRanking.weaponScore(NS + "diamond_axe", true));
        assertEquals(9, PvpBotRanking.weaponScore(NS + "iron_axe", true));
        assertEquals(9, PvpBotRanking.weaponScore(NS + "stone_axe", true));
        assertEquals(9, PvpBotRanking.weaponScore(NS + "trident", true));
        assertEquals(7, PvpBotRanking.weaponScore(NS + "golden_axe", true));
        assertEquals(7, PvpBotRanking.weaponScore(NS + "wooden_axe", true));
        assertEquals(6, PvpBotRanking.weaponScore(NS + "mace", true));
    }

    @Test
    void withPreferSwordOffSwordsLoseTheirBonusAndAxesCanOutrankThem() {
        // Same base damage table, no +5 bonus: an iron sword (6) now scores BELOW an iron axe (9), the
        // opposite tie-break from preferSword=true. A generator that always assumed preferSword=true would
        // lay out the hotbar wrong on a server where an operator turned this setting off.
        assertEquals(8, PvpBotRanking.weaponScore(NS + "netherite_sword", false));
        assertEquals(7, PvpBotRanking.weaponScore(NS + "diamond_sword", false));
        assertEquals(6, PvpBotRanking.weaponScore(NS + "iron_sword", false));
        assertEquals(10, PvpBotRanking.weaponScore(NS + "netherite_axe", false));
        assertEquals(9, PvpBotRanking.weaponScore(NS + "iron_axe", false));
        assertTrue(PvpBotRanking.weaponScore(NS + "iron_axe", false) > PvpBotRanking.weaponScore(NS + "iron_sword", false),
                "with the bonus off, the axe's own higher base damage decides the tie");
    }

    @Test
    void spearsAndUnknownItemsScoreZeroSoTheyAreNeverReselected() {
        assertEquals(0, PvpBotRanking.weaponScore(NS + "netherite_spear", true));
        assertEquals(0, PvpBotRanking.weaponScore(NS + "copper_sword", true));
        assertEquals(0, PvpBotRanking.weaponScore(NS + "bow", true));
        assertEquals(0, PvpBotRanking.weaponScore(null, true));
    }

    @Test
    void namespaceIsIgnoredSoBareIdsScoreTheSame() {
        assertEquals(PvpBotRanking.weaponScore(NS + "iron_sword", true), PvpBotRanking.weaponScore("iron_sword", true));
    }
}

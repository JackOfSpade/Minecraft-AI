package dev.spawnbotswrapper.inhabitants.profile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArchetypesTest {

    private static final BotProfile.Behavior STAND = BotProfile.Behavior.standing();
    private static final BotProfile.Behavior GUARD = new BotProfile.Behavior(BotProfile.Stance.GUARD_POST, true,
            BotProfile.WalkType.SPRINT, 0, 1, List.of());
    private static final BotProfile.Behavior PATROL = new BotProfile.Behavior(BotProfile.Stance.PATROL_CYCLE, true,
            BotProfile.WalkType.WALK, 10, 3, List.of());
    private static final BotProfile.Behavior PEACEFUL = new BotProfile.Behavior(BotProfile.Stance.PATROL_PINGPONG, false,
            BotProfile.WalkType.WALK, 10, 3, List.of());

    /** Facts with everything blank except what the test sets. */
    private static Facts facts(Facts.MeleeKind melee, Facts.RangedKind ranged, int arrows, boolean shield, int totems,
                               Facts.ExplosiveKit explosive, boolean elytra, int rockets, int armorScore) {
        return new Facts(Facts.ArmorMode.MIXED, melee, ranged, arrows, shield, totems, explosive, elytra, rockets,
                armorScore, 0);
    }

    private static Facts melee(Facts.MeleeKind kind) {
        return facts(kind, Facts.RangedKind.NONE, 0, false, 0, Facts.ExplosiveKit.NONE, false, 0, 0);
    }

    @Test
    void thereIsNoPacifistLabelAndALegacyNonCombatantFlagChangesNothing() {
        Facts armed = facts(Facts.MeleeKind.MACE, Facts.RangedKind.BOW, 100, true, 3, Facts.ExplosiveKit.CRYSTAL,
                true, 20, 100);
        assertEquals(Archetypes.DEMOLITIONIST, Archetypes.label(armed, PEACEFUL),
                "the label follows the loadout even for a profile that still carries the retired flag");
        assertFalse(Archetypes.ALL.contains("Pacifist"));
    }

    @Test
    void aStandingBotWithTheLegacyFlagOffIsLabelledByItsLoadout() {
        BotProfile.Behavior standingNonCombatant = new BotProfile.Behavior(BotProfile.Stance.STAND, false,
                BotProfile.WalkType.BHOP, 0, 0, List.of());
        assertEquals(Archetypes.DUELIST, Archetypes.label(melee(Facts.MeleeKind.SWORD), standingNonCombatant));
    }

    @Test
    void explosiveKitsDominateEveryFight() {
        for (Facts.ExplosiveKit kit : new Facts.ExplosiveKit[]{Facts.ExplosiveKit.CRYSTAL, Facts.ExplosiveKit.ANCHOR}) {
            Facts f = facts(Facts.MeleeKind.SPEAR, Facts.RangedKind.BOW, 50, false, 0, kit, false, 0, 0);
            assertEquals(Archetypes.DEMOLITIONIST, Archetypes.label(f, STAND));
        }
    }

    @Test
    void maceBotsAreSmashersOrSkyfarers() {
        assertEquals(Archetypes.SMASHER, Archetypes.label(melee(Facts.MeleeKind.MACE), STAND));
        Facts flying = facts(Facts.MeleeKind.MACE, Facts.RangedKind.NONE, 0, false, 0, Facts.ExplosiveKit.NONE, true, 10, 0);
        assertEquals(Archetypes.SKYFARER, Archetypes.label(flying, STAND));
        Facts noRockets = facts(Facts.MeleeKind.MACE, Facts.RangedKind.NONE, 0, false, 0, Facts.ExplosiveKit.NONE, true, 0, 0);
        assertEquals(Archetypes.SMASHER, Archetypes.label(noRockets, STAND));
    }

    @Test
    void spearAndTridentBots() {
        assertEquals(Archetypes.LANCER, Archetypes.label(melee(Facts.MeleeKind.SPEAR), STAND));
        assertEquals(Archetypes.HARPOONER, Archetypes.label(melee(Facts.MeleeKind.TRIDENT), STAND));
    }

    @Test
    void rangedBotsNeedArrowsToCountAsArchers() {
        Facts archer = facts(Facts.MeleeKind.NONE, Facts.RangedKind.BOW, 40, false, 0, Facts.ExplosiveKit.NONE, false, 0, 0);
        assertEquals(Archetypes.ARCHER, Archetypes.label(archer, STAND));
        Facts skirmisher = facts(Facts.MeleeKind.SWORD, Facts.RangedKind.CROSSBOW, 40, false, 0, Facts.ExplosiveKit.NONE, false, 0, 0);
        assertEquals(Archetypes.SKIRMISHER, Archetypes.label(skirmisher, STAND));
        Facts dry = facts(Facts.MeleeKind.NONE, Facts.RangedKind.BOW, 0, false, 0, Facts.ExplosiveKit.NONE, false, 0, 0);
        assertEquals(Archetypes.BRAWLER, Archetypes.label(dry, STAND), "a bow without arrows makes no archer");
    }

    @Test
    void tanksNeedIronOrBetterArmorAndADefence() {
        Facts shielded = facts(Facts.MeleeKind.SWORD, Facts.RangedKind.NONE, 0, true, 0, Facts.ExplosiveKit.NONE, false, 0, 80);
        assertEquals(Archetypes.TANK, Archetypes.label(shielded, STAND));
        Facts totemmed = facts(Facts.MeleeKind.AXE, Facts.RangedKind.NONE, 0, false, 2, Facts.ExplosiveKit.NONE, false, 0, 60);
        assertEquals(Archetypes.TANK, Archetypes.label(totemmed, STAND));
        Facts weakArmor = facts(Facts.MeleeKind.SWORD, Facts.RangedKind.NONE, 0, true, 2, Facts.ExplosiveKit.NONE, false, 0, 59);
        assertEquals(Archetypes.DUELIST, Archetypes.label(weakArmor, STAND));
        Facts undefended = facts(Facts.MeleeKind.SWORD, Facts.RangedKind.NONE, 0, false, 0, Facts.ExplosiveKit.NONE, false, 0, 100);
        assertEquals(Archetypes.DUELIST, Archetypes.label(undefended, STAND));
    }

    @Test
    void axeBotsAreBerserkers() {
        assertEquals(Archetypes.BERSERKER, Archetypes.label(melee(Facts.MeleeKind.AXE), STAND));
        assertEquals(Archetypes.BERSERKER, Archetypes.label(melee(Facts.MeleeKind.SWORD_AND_AXE), STAND));
    }

    @Test
    void stanceDecidesWhenTheLoadoutIsUnremarkable() {
        assertEquals(Archetypes.GUARD, Archetypes.label(melee(Facts.MeleeKind.SWORD), GUARD));
        assertEquals(Archetypes.SCOUT, Archetypes.label(melee(Facts.MeleeKind.SWORD), PATROL));
        assertEquals(Archetypes.DUELIST, Archetypes.label(melee(Facts.MeleeKind.SWORD), STAND));
        assertEquals(Archetypes.BRAWLER, Archetypes.label(melee(Facts.MeleeKind.NONE), STAND));
        assertEquals(Archetypes.GUARD, Archetypes.label(melee(Facts.MeleeKind.NONE), GUARD));
    }

    @Test
    void everyLabelIsListedAndDistinct() {
        assertEquals(Archetypes.ALL.size(), Archetypes.ALL.stream().distinct().count());
        for (String label : List.of(Archetypes.DEMOLITIONIST, Archetypes.SKYFARER, Archetypes.SMASHER,
                Archetypes.LANCER, Archetypes.HARPOONER, Archetypes.ARCHER, Archetypes.SKIRMISHER, Archetypes.TANK,
                Archetypes.BERSERKER, Archetypes.GUARD, Archetypes.SCOUT, Archetypes.DUELIST, Archetypes.BRAWLER,
                Archetypes.UNEQUIPPED)) {
            assertTrue(Archetypes.ALL.contains(label), label);
            assertFalse(label.isBlank());
        }
    }
}

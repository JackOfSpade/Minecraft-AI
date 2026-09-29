package dev.spawnbotswrapper.inhabitants.profile;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every inhabitant is a fighter: the migration helpers used by the store, and the generator's guarantee. */
class BotProfileFighterTest {

    private static BotProfile profile(String archetype, boolean combatant) {
        BotProfile.Behavior behavior = new BotProfile.Behavior(BotProfile.Stance.PATROL_CYCLE, combatant,
                BotProfile.WalkType.SPRINT, 8.5, 2, List.of(new BotProfile.Waypoint(1, 64, 2), new BotProfile.Waypoint(5, 64, 6)));
        BotProfile.Loadout loadout = new BotProfile.Loadout(List.of(
                new BotProfile.PlacedItem(BotProfile.Slot.HOTBAR, 0, BotProfile.ItemSpec.of("minecraft:iron_sword"))));
        return new BotProfile(1, 99L, archetype, loadout, new BotProfile.Vitals(0.5, 12, Map.of()), behavior);
    }

    @Test
    void aPacifistProfileBecomesAFighterAndKeepsEverythingElse() {
        BotProfile old = profile("Pacifist", false);
        assertTrue(old.isLegacyPacifist());
        BotProfile now = old.asFighter();
        assertTrue(now.behavior().combatant());
        assertEquals(BotProfile.MIGRATED_ARCHETYPE, now.archetype());
        assertFalse(now.isLegacyPacifist());
        assertEquals(old.loadout(), now.loadout());
        assertEquals(old.vitals(), now.vitals());
        assertEquals(old.seed(), now.seed());
        assertEquals(old.behavior().waypoints(), now.behavior().waypoints());
        assertEquals(old.behavior().stance(), now.behavior().stance());
        assertEquals(old.behavior().walkType(), now.behavior().walkType());
        assertEquals(old.behavior().patrolRadius(), now.behavior().patrolRadius());
    }

    @Test
    void aFighterIsReturnedUnchanged() {
        BotProfile fighter = profile("Duelist", true);
        assertFalse(fighter.isLegacyPacifist());
        assertSame(fighter, fighter.asFighter());
    }

    @Test
    void aStaleLabelAloneIsCorrectedToo() {
        BotProfile p = profile("Pacifist", true);
        assertTrue(p.isLegacyPacifist());
        assertEquals(BotProfile.MIGRATED_ARCHETYPE, p.asFighter().archetype());
    }

    @Test
    void aFlagOnlyProfileKeepsItsOwnLabel() {
        BotProfile p = profile("Guard", false);
        assertEquals("Guard", p.asFighter().archetype());
        assertTrue(p.asFighter().behavior().combatant());
    }

    @Test
    void theGeneratorNeverMakesAPacifist() {
        for (ProfileGenerator.Generation g : ProfileTestSupport.generate(2000, ProfileTestSupport.allOn(),
                ProfileTestSupport.everythingOptions(), 5)) {
            assertTrue(g.profile().behavior().combatant());
            assertFalse(g.profile().isLegacyPacifist());
            assertFalse(g.profile().archetype().equals("Pacifist"));
        }
    }
}

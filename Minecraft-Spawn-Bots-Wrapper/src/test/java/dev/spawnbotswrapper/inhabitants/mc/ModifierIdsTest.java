package dev.spawnbotswrapper.inhabitants.mc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModifierIdsTest {

    @Test
    void vanillaAttributesGetAShortPath() {
        assertEquals("profile/max_health", ModifierIds.pathFor("minecraft", "max_health"));
        assertEquals("profile/entity_interaction_range", ModifierIds.pathFor("minecraft", "entity_interaction_range"));
    }

    @Test
    void moddedAttributesKeepTheirNamespaceSoTheyStayReadable() {
        assertEquals("profile/somemod/luck_of_the_bot", ModifierIds.pathFor("somemod", "luck_of_the_bot"));
    }

    @Test
    void theIdIsStableAcrossCalls() {
        assertEquals(ModifierIds.pathFor("minecraft", "attack_speed"), ModifierIds.pathFor("minecraft", "attack_speed"));
    }

    @Test
    void differentAttributesNeverShareAnId() {
        assertNotEquals(ModifierIds.pathFor("minecraft", "max_health"), ModifierIds.pathFor("minecraft", "attack_speed"));
        assertNotEquals(ModifierIds.pathFor("minecraft", "luck"), ModifierIds.pathFor("somemod", "luck"));
    }

    @Test
    void theNamespaceIsTheAddonId() {
        assertEquals("pvpbot_inhabitants", ModifierIds.NAMESPACE);
    }
}

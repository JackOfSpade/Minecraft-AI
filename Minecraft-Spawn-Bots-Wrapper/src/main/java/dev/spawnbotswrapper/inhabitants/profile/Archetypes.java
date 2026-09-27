package dev.spawnbotswrapper.inhabitants.profile;

import java.util.List;

/**
 * A short human label for what a rolled bot amounts to, so an admin reading {@code /inhabitants profile}
 * sees "Archer" instead of a list of ids. Purely informational: nothing reads it back, and it is derived
 * from the facets rather than rolled, so it can never disagree with the loadout.
 * <p>
 * The first matching rule wins, ordered from the most distinctive trait (a pacifist ignores its whole
 * loadout; an explosive kit dominates every fight) down to the plain fallback.
 */
final class Archetypes {
    private Archetypes() {
    }

    static final String PACIFIST = "Pacifist";
    static final String DEMOLITIONIST = "Demolitionist";
    static final String SKYFARER = "Skyfarer";
    static final String SMASHER = "Smasher";
    static final String LANCER = "Lancer";
    static final String HARPOONER = "Harpooner";
    static final String ARCHER = "Archer";
    static final String SKIRMISHER = "Skirmisher";
    static final String TANK = "Tank";
    static final String BERSERKER = "Berserker";
    static final String GUARD = "Guard";
    static final String SCOUT = "Scout";
    static final String DUELIST = "Duelist";
    static final String BRAWLER = "Brawler";

    /** A bot that carries nothing at all (profile randomisation switched off). */
    static final String UNEQUIPPED = "Unequipped";

    static final List<String> ALL = List.of(PACIFIST, DEMOLITIONIST, SKYFARER, SMASHER, LANCER, HARPOONER,
            ARCHER, SKIRMISHER, TANK, BERSERKER, GUARD, SCOUT, DUELIST, BRAWLER, UNEQUIPPED);

    /** Average PvP BOT armor score from which a fully dressed bot with defences counts as a tank (iron and up). */
    private static final int TANK_ARMOR_SCORE = 60;

    static String label(Facts f, BotProfile.Behavior behavior) {
        if (behavior.usesPath() && !behavior.combatant()) {
            return PACIFIST;
        }
        if (f.explosive() != Facts.ExplosiveKit.NONE) {
            return DEMOLITIONIST;
        }
        if (f.meleeKind() == Facts.MeleeKind.MACE) {
            return f.elytra() && f.rockets() > 0 ? SKYFARER : SMASHER;
        }
        if (f.meleeKind() == Facts.MeleeKind.SPEAR) {
            return LANCER;
        }
        if (f.meleeKind() == Facts.MeleeKind.TRIDENT) {
            return HARPOONER;
        }
        if (f.hasRanged() && f.arrows() > 0) {
            return f.hasMeleeWeapon() ? SKIRMISHER : ARCHER;
        }
        if (f.armorScore() >= TANK_ARMOR_SCORE && (f.shield() || f.totems() > 0)) {
            return TANK;
        }
        if (f.meleeKind() == Facts.MeleeKind.AXE || f.meleeKind() == Facts.MeleeKind.SWORD_AND_AXE) {
            return BERSERKER;
        }
        if (BotProfile.Stance.GUARD_POST.equals(behavior.stance())) {
            return GUARD;
        }
        if (behavior.usesPath()) {
            return SCOUT;
        }
        return f.meleeKind() == Facts.MeleeKind.SWORD ? DUELIST : BRAWLER;
    }
}

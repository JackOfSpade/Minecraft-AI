package dev.spawnbotswrapper.inhabitants.profile;

/**
 * The categorical outcome of each loadout facet, kept next to the finished profile. The archetype label
 * is derived from these and the tests read them to verify that every option of every facet is reachable,
 * without having to reverse-engineer categories from item lists.
 *
 * @param armorMode      how much armor the bot wears and whether the pieces match
 * @param meleeKind      which melee weapon class it carries
 * @param rangedKind     which ranged weapons it carries
 * @param arrows         total arrows across all arrow kinds (0 when it carries no ranged weapon)
 * @param shield         carries a shield
 * @param totems         totems of undying carried (offhand plus inventory)
 * @param explosive      end-crystal or respawn-anchor kit, if any
 * @param elytra         wears an elytra instead of a chestplate
 * @param rockets        firework rockets carried
 * @param armorScore     average PvP BOT armor score of the four slots (an empty slot counts 0), 0..100
 * @param trimmedStacks  stacks dropped because the inventory was full (a safety net, expected to be 0)
 */
record Facts(
        ArmorMode armorMode,
        MeleeKind meleeKind,
        RangedKind rangedKind,
        int arrows,
        boolean shield,
        int totems,
        ExplosiveKit explosive,
        boolean elytra,
        int rockets,
        int armorScore,
        int trimmedStacks) {

    enum ArmorMode { NONE, PARTIAL, MIXED, MATCHING_SET }

    enum MeleeKind { NONE, SWORD, AXE, SWORD_AND_AXE, MACE, SPEAR, TRIDENT }

    enum RangedKind { NONE, BOW, CROSSBOW, BOTH }

    enum ExplosiveKit { NONE, CRYSTAL, ANCHOR }

    boolean hasMeleeWeapon() {
        return meleeKind != MeleeKind.NONE;
    }

    boolean hasRanged() {
        return rangedKind != RangedKind.NONE;
    }

    /** Blank facts, for a profile that carries nothing. */
    static Facts empty() {
        return new Facts(ArmorMode.NONE, MeleeKind.NONE, RangedKind.NONE, 0, false, 0,
                ExplosiveKit.NONE, false, 0, 0, 0);
    }
}

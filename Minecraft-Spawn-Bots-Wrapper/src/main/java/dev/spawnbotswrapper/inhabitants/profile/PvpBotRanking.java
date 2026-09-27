package dev.spawnbotswrapper.inhabitants.profile;

import java.util.Map;

/**
 * How PvP BOT's own auto-equip ranks items, as observed from its behaviour (v0.0.15). The generator
 * needs this so the loadout it hands out is already in the order PvP BOT would arrange it into: the best
 * weapon in hotbar slot 0 and worn armor that no spare piece outranks. Otherwise PvP BOT rearranges the
 * hotbar a moment after spawn and the loadout the profile describes is not what the bot ends up holding.
 * <p>
 * Only the ranking facts are reproduced here (they are inputs to a loadout, not upstream logic).
 */
final class PvpBotRanking {
    private PvpBotRanking() {
    }

    /** Extra score PvP BOT adds to swords when its (default) preferSword setting is on. */
    private static final int SWORD_PREFERENCE = 5;

    private static final Map<String, Integer> WEAPON_DAMAGE = Map.ofEntries(
            Map.entry("netherite_sword", 8), Map.entry("netherite_axe", 10),
            Map.entry("diamond_sword", 7), Map.entry("diamond_axe", 9),
            Map.entry("iron_sword", 6), Map.entry("iron_axe", 9),
            Map.entry("stone_sword", 5), Map.entry("stone_axe", 9),
            Map.entry("golden_sword", 4), Map.entry("golden_axe", 7),
            Map.entry("wooden_sword", 4), Map.entry("wooden_axe", 7),
            Map.entry("trident", 9), Map.entry("mace", 6));

    /**
     * Armor score: netherite 100, diamond 80, iron 60, turtle helmet 55, chainmail 50, golden 40,
     * leather 20, elytra 10; anything else 0. Tier only - enchantments and wear are ignored upstream.
     */
    static int armorScore(String item) {
        String path = path(item);
        if (path.equals("turtle_helmet")) {
            return 55;
        }
        if (path.equals("elytra")) {
            return 10;
        }
        if (!ItemIds.isArmor(item)) {
            return 0;
        }
        if (path.startsWith("netherite_")) {
            return 100;
        }
        if (path.startsWith("diamond_")) {
            return 80;
        }
        if (path.startsWith("iron_")) {
            return 60;
        }
        if (path.startsWith("chainmail_")) {
            return 50;
        }
        if (path.startsWith("golden_")) {
            return 40;
        }
        if (path.startsWith("leather_")) {
            return 20;
        }
        return 0;
    }

    /**
     * Melee weapon score: base damage from its table, plus {@link #SWORD_PREFERENCE} for swords when the
     * server's {@code preferSword} setting is on (the actual live value, not an assumed default - a bot
     * placed on a {@code preferSword=false} server must be laid out for the axe-preferring tie-break PvP
     * BOT will actually apply). Spears and unknown items score 0, meaning PvP BOT never re-selects them.
     */
    static int weaponScore(String item, boolean preferSword) {
        String path = path(item);
        Integer damage = WEAPON_DAMAGE.get(path);
        if (damage == null) {
            return 0;
        }
        return preferSword && path.endsWith("_sword") ? damage + SWORD_PREFERENCE : damage;
    }

    private static String path(String item) {
        if (item == null) {
            return "";
        }
        int colon = item.indexOf(':');
        return colon < 0 ? item : item.substring(colon + 1);
    }
}

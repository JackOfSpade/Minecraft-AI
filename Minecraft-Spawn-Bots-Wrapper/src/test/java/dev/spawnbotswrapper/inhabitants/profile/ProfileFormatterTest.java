package dev.spawnbotswrapper.inhabitants.profile;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.spawnbotswrapper.inhabitants.profile.ProfileTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class ProfileFormatterTest {

    private static BotProfile.PlacedItem hotbar(int index, BotProfile.ItemSpec spec) {
        return new BotProfile.PlacedItem(BotProfile.Slot.HOTBAR, index, spec);
    }

    private static BotProfile.PlacedItem stock(BotProfile.ItemSpec spec) {
        return new BotProfile.PlacedItem(BotProfile.Slot.INVENTORY, -1, spec);
    }

    private static BotProfile.PlacedItem offhand(BotProfile.ItemSpec spec) {
        return new BotProfile.PlacedItem(BotProfile.Slot.OFFHAND, 0, spec);
    }

    private static BotProfile.PlacedItem worn(String slot, BotProfile.ItemSpec spec) {
        return new BotProfile.PlacedItem(slot, 0, spec);
    }

    private static BotProfile profile(List<BotProfile.PlacedItem> items) {
        return new BotProfile(1, 42L, "Archer", new BotProfile.Loadout(items),
                new BotProfile.Vitals(1.0, 20, Map.of()), BotProfile.Behavior.standing());
    }

    private static BotProfile profile(List<BotProfile.PlacedItem> items, BotProfile.Vitals vitals, BotProfile.Behavior b) {
        return new BotProfile(1, 42L, "Archer", new BotProfile.Loadout(items), vitals, b);
    }

    private static BotProfile.ItemSpec spec(String path) {
        return BotProfile.ItemSpec.of(NS + path);
    }

    private static BotProfile.ItemSpec spec(String path, int count) {
        return BotProfile.ItemSpec.of(NS + path, count);
    }

    private static String text(BotProfile p, GlobalCapabilities caps) {
        return String.join("\n", ProfileFormatter.format(p, caps));
    }

    private static List<String> notes(BotProfile p, GlobalCapabilities caps) {
        List<String> lines = ProfileFormatter.format(p, caps);
        int start = lines.indexOf("Global PvP BOT settings that affect this bot:");
        assertTrue(start >= 0, "notes heading missing");
        return lines.subList(start + 1, lines.size());
    }

    private static boolean anyNoteContains(List<String> notes, String fragment) {
        return notes.stream().anyMatch(n -> n.contains(fragment));
    }

    @Test
    void everyGeneratedProfileRendersEveryFacet() {
        for (BotProfile p : profiles(generate(500, GlobalCapabilities.upstreamDefaults(), everythingOptions(), 3))) {
            List<String> lines = ProfileFormatter.format(p, GlobalCapabilities.upstreamDefaults());
            String all = String.join("\n", lines);

            for (String heading : List.of("Archetype: " + p.archetype(), "seed " + p.seed(), "Armor:", "Melee:",
                    "Ranged:", "Defence:", "Sustain:", "Explosive kit:", "Flight:", "Vitals:", "Behaviour:",
                    "Global PvP BOT settings that affect this bot:")) {
                assertTrue(all.contains(heading), "missing '" + heading + "' in\n" + all);
            }
            for (String line : lines) {
                assertFalse(line.isBlank(), "blank line");
                assertFalse(line.contains("\n") || line.contains("\r"), "embedded newline");
                assertFalse(line.contains("null"), "leaked null: " + line);
            }
            for (BotProfile.PlacedItem placed : p.loadout().items()) {
                BotProfile.ItemSpec s = placed.spec();
                assertTrue(all.contains(s.item().substring(NS.length())), s.item() + " missing in\n" + all);
                for (String enchant : s.enchantments().keySet()) {
                    assertTrue(all.contains(enchant.substring(NS.length())), enchant + " missing in\n" + all);
                }
                if (s.potion() != null) {
                    assertTrue(all.contains(s.potion().substring(NS.length())), s.potion() + " missing in\n" + all);
                }
                if (s.damageFraction() > 0) {
                    assertTrue(all.contains("wear " + Math.round(s.damageFraction() * 100) + "%"), "wear of " + s);
                }
            }
            assertTrue(all.contains("food level: " + p.vitals().foodLevel() + "/20"));
            assertTrue(all.contains("stance: " + p.behavior().stance()));
            assertTrue(all.contains("walk type: " + p.behavior().walkType()));
            assertTrue(all.contains("combatant: " + (p.behavior().combatant() ? "yes" : "no")));
            assertTrue(all.contains("waypoints: " + p.behavior().waypointCount() + " planned"));
            for (var attribute : p.vitals().attributes().entrySet()) {
                String name = switch (attribute.getKey()) {
                    case NS + "max_health" -> "max health";
                    case NS + "entity_interaction_range" -> "interaction reach";
                    case NS + "attack_speed" -> "attack speed";
                    case NS + "knockback_resistance" -> "knockback resistance";
                    default -> "scale";
                };
                assertTrue(all.contains(name), name + " missing in\n" + all);
            }
        }
    }

    @Test
    void outputIsDeterministicAndIndependentOfMapOrder() {
        Map<String, Integer> a = new LinkedHashMap<>();
        a.put(NS + "sharpness", 5);
        a.put(NS + "unbreaking", 3);
        a.put(NS + "fire_aspect", 2);
        Map<String, Integer> b = new LinkedHashMap<>();
        b.put(NS + "fire_aspect", 2);
        b.put(NS + "sharpness", 5);
        b.put(NS + "unbreaking", 3);
        BotProfile pa = profile(List.of(hotbar(0, new BotProfile.ItemSpec(NS + "diamond_sword", 1, a, 0.0, null))));
        BotProfile pb = profile(List.of(hotbar(0, new BotProfile.ItemSpec(NS + "diamond_sword", 1, b, 0.0, null))));
        assertEquals(ProfileFormatter.format(pa, allOn()), ProfileFormatter.format(pb, allOn()));
        assertEquals(ProfileFormatter.format(pa, allOn()), ProfileFormatter.format(pa, allOn()));
        assertTrue(text(pa, allOn()).contains("diamond_sword - fire_aspect II, sharpness V, unbreaking III"),
                text(pa, allOn()));
    }

    @Test
    void attributesShowTheirResultingValues() {
        Map<String, BotProfile.AttributeMod> attributes = new LinkedHashMap<>();
        attributes.put(NS + "max_health", new BotProfile.AttributeMod(BotProfile.Op.ADD_VALUE, 7));
        attributes.put(NS + "entity_interaction_range", new BotProfile.AttributeMod(BotProfile.Op.ADD_VALUE, 0.9));
        attributes.put(NS + "attack_speed", new BotProfile.AttributeMod(BotProfile.Op.ADD_MULTIPLIED_BASE, -0.35));
        attributes.put(NS + "knockback_resistance", new BotProfile.AttributeMod(BotProfile.Op.ADD_VALUE, 0.4));
        attributes.put(NS + "scale", new BotProfile.AttributeMod(BotProfile.Op.ADD_VALUE, -0.25));
        String out = text(profile(List.of(), new BotProfile.Vitals(0.5, 14, attributes), BotProfile.Behavior.standing()),
                allOn());
        assertTrue(out.contains("max health 27 (20 base + 7)"), out);
        assertTrue(out.contains("interaction reach 3.9 (3 base + 0.9)"), out);
        assertTrue(out.contains("attack speed 2.6 (4 base -35%)"), out);
        assertTrue(out.contains("knockback resistance 0.4 (0 base + 0.4)"), out);
        assertTrue(out.contains("scale 0.75 (1 base - 0.25)"), out);
        assertTrue(out.contains("health: starts at 50% of max (13.5 of 27 HP)"), out);
        assertTrue(out.contains("food level: 14/20"), out);
    }

    @Test
    void attributesAreListedMostRelevantFirstAndUnknownOnesLast() {
        Map<String, BotProfile.AttributeMod> attributes = new LinkedHashMap<>();
        attributes.put("modded:luck_boost", new BotProfile.AttributeMod(BotProfile.Op.ADD_VALUE, 2));
        attributes.put(NS + "scale", new BotProfile.AttributeMod(BotProfile.Op.ADD_VALUE, 0.1));
        attributes.put(NS + "knockback_resistance", new BotProfile.AttributeMod(BotProfile.Op.ADD_VALUE, 0.5));
        attributes.put(NS + "attack_speed", new BotProfile.AttributeMod(BotProfile.Op.ADD_MULTIPLIED_BASE, -0.1));
        attributes.put(NS + "entity_interaction_range", new BotProfile.AttributeMod(BotProfile.Op.ADD_VALUE, 1));
        attributes.put(NS + "max_health", new BotProfile.AttributeMod(BotProfile.Op.ADD_VALUE, 4));
        List<String> lines = ProfileFormatter.format(
                profile(List.of(), new BotProfile.Vitals(1, 20, attributes), BotProfile.Behavior.standing()), allOn());
        List<String> order = new ArrayList<>();
        for (String line : lines) {
            for (String name : List.of("max health", "interaction reach", "attack speed", "knockback resistance",
                    "scale", "modded:luck_boost")) {
                if (line.startsWith("    " + name)) {
                    order.add(name);
                }
            }
        }
        assertEquals(List.of("max health", "interaction reach", "attack speed", "knockback resistance", "scale",
                "modded:luck_boost"), order);
    }

    @Test
    void aReducedMaxHealthIsShownWithAMinus() {
        Map<String, BotProfile.AttributeMod> attributes = Map.of(NS + "max_health",
                new BotProfile.AttributeMod(BotProfile.Op.ADD_VALUE, -7));
        String out = text(profile(List.of(), new BotProfile.Vitals(1.0, 20, attributes), BotProfile.Behavior.standing()),
                allOn());
        assertTrue(out.contains("max health 13 (20 base - 7)"), out);
    }

    @Test
    void noAttributesIsStatedExplicitly() {
        assertTrue(text(profile(List.of()), allOn()).contains("attributes: none (vanilla values)"));
    }

    @Test
    void identicalStacksAreMergedWithTheirTotals() {
        List<BotProfile.PlacedItem> items = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            items.add(stock(new BotProfile.ItemSpec(NS + "splash_potion", 1, Map.of(), 0.0, NS + "strong_healing")));
        }
        items.add(stock(spec("arrow", 64)));
        items.add(stock(spec("arrow", 22)));
        items.add(hotbar(0, spec("bow")));
        items.add(offhand(spec("totem_of_undying")));
        items.add(stock(spec("totem_of_undying")));
        List<String> lines = ProfileFormatter.format(profile(items), allOn());
        assertTrue(lines.contains("  splash_potion (strong_healing) x8  [inventory]"), lines.toString());
        assertTrue(lines.contains("  arrow x86  [inventory]"), lines.toString());
        assertTrue(lines.contains("  arrows in total: 86"), lines.toString());
        assertTrue(lines.contains("  totem_of_undying x2  [offhand, inventory]"), lines.toString());
        assertTrue(lines.contains("  totems in total: 2"), lines.toString());
    }

    @Test
    void armorIsListedPerSlotWithEnchantmentsAndWear() {
        BotProfile.ItemSpec helmet = new BotProfile.ItemSpec(NS + "iron_helmet", 1,
                Map.of(NS + "protection", 3, NS + "mending", 1), 0.35, null);
        String out = text(profile(List.of(worn("head", helmet), worn("legs", spec("leather_leggings")))), allOn());
        assertTrue(out.contains("head:  iron_helmet - mending, protection III - wear 35%"), out);
        assertTrue(out.contains("chest: (empty)"), out);
        assertTrue(out.contains("legs:  leather_leggings"), out);
        assertTrue(out.contains("feet:  (empty)"), out);
    }

    @Test
    void behaviourLinesDescribeStanceRadiusAndWaypoints() {
        BotProfile.Behavior b = new BotProfile.Behavior(BotProfile.Stance.PATROL_PINGPONG, false, BotProfile.WalkType.WALK,
                12.5, 3, List.of(new BotProfile.Waypoint(10.5, 64, -3.5), new BotProfile.Waypoint(12, 64, -9)));
        String out = text(profile(List.of(), new BotProfile.Vitals(1, 20, Map.of()), b), allOn());
        assertTrue(out.contains("stance: PATROL_PINGPONG (walks its waypoints back and forth)"), out);
        assertTrue(out.contains("walk type: walk"), out);
        assertTrue(out.contains("combatant: no (pacifist path follower)"), out);
        assertTrue(out.contains("patrol radius: 12.5 blocks"), out);
        assertTrue(out.contains("waypoints: 3 planned, 2 placed"), out);
        assertTrue(out.contains("#1 (10.5, 64, -3.5)"), out);
        assertTrue(out.contains("#2 (12, 64, -9)"), out);
    }

    @Test
    void autoTargetOffExplainsThePassivity() {
        List<String> n = notes(profile(List.of()), GlobalCapabilities.upstreamDefaults());
        assertTrue(anyNoteContains(n, "Auto-target is off"), n.toString());
        assertTrue(anyNoteContains(n, "passive until it is attacked"), n.toString());
        assertFalse(anyNoteContains(notes(profile(List.of()), allOn()), "Auto-target is off"));
    }

    @Test
    void combatOffExplainsThatNoWeaponIsUsed() {
        List<String> n = notes(profile(List.of(hotbar(0, spec("diamond_sword")))), allOnExcept("combatEnabled", "autoTargetEnabled"));
        assertTrue(anyNoteContains(n, "Combat is disabled globally"), n.toString());
        assertFalse(anyNoteContains(n, "Auto-target is off"), "auto-target is moot when combat is off");
    }

    @Test
    void rangedGearNotesCoverDisabledMissingArrowsAndPreferences() {
        BotProfile bowOnly = profile(List.of(hotbar(0, spec("bow")), stock(spec("arrow", 30))));
        assertTrue(anyNoteContains(notes(bowOnly, allOnExcept("rangedEnabled")), "ranged combat is off globally"));
        assertFalse(anyNoteContains(notes(bowOnly, allOn()), "ranged combat is off globally"));

        BotProfile dry = profile(List.of(hotbar(0, spec("crossbow"))));
        assertTrue(anyNoteContains(notes(dry, allOn()), "no arrows"));
        assertTrue(anyNoteContains(notes(dry, allOn()), "useless"));

        BotProfile arrowsOnly = profile(List.of(stock(spec("arrow", 30))));
        assertTrue(anyNoteContains(notes(arrowsOnly, allOn()), "arrows but no bow"));

        BotProfile both = profile(List.of(hotbar(0, spec("crossbow")), hotbar(1, spec("bow")), stock(spec("arrow", 5))));
        assertTrue(anyNoteContains(notes(both, allOn()), "prefers the crossbow"));

        BotProfile hybrid = profile(List.of(hotbar(0, spec("iron_sword")), hotbar(1, spec("bow")), stock(spec("arrow", 5))));
        assertTrue(anyNoteContains(notes(hybrid, allOn()), "melee weapon is rarely used"));
    }

    @Test
    void meleeGearNotes() {
        BotProfile mace = profile(List.of(hotbar(0, spec("mace"))));
        assertTrue(anyNoteContains(notes(mace, allOnExcept("maceEnabled")), "mace setting is off"));
        assertFalse(anyNoteContains(notes(mace, allOn()), "mace setting is off"));

        BotProfile spear = profile(List.of(hotbar(0, spec("iron_spear"))));
        assertTrue(anyNoteContains(notes(spear, GlobalCapabilities.upstreamDefaults()), "spear setting is off"));
        assertFalse(anyNoteContains(notes(spear, allOn()), "spear setting is off"));

        BotProfile axe = profile(List.of(hotbar(0, spec("iron_axe"))));
        assertTrue(anyNoteContains(notes(axe, allOnExcept("shieldBreakEnabled")), "Shield-breaking is off"));
    }

    @Test
    void elytraRocketMaceComboIsFlagged() {
        BotProfile combo = profile(List.of(worn("chest", spec("elytra")), hotbar(0, spec("mace")),
                stock(spec("firework_rocket", 20))));
        assertTrue(anyNoteContains(notes(combo, allOn()), "rocket-dive"));
        BotProfile plain = profile(List.of(worn("chest", spec("elytra")), stock(spec("firework_rocket", 20))));
        assertFalse(anyNoteContains(notes(plain, allOn()), "rocket-dive"));
    }

    @Test
    void explosiveKitNotes() {
        BotProfile crystals = profile(List.of(stock(spec("end_crystal", 4)), stock(spec("obsidian", 20))));
        assertTrue(anyNoteContains(notes(crystals, allOn()), "destroy blocks"));
        assertTrue(anyNoteContains(notes(crystals, allOnExcept("crystalPvpEnabled")), "crystal PvP is off globally"));

        BotProfile half = profile(List.of(stock(spec("end_crystal", 4))));
        assertTrue(anyNoteContains(notes(half, allOn()), "only one half of a crystal kit"));

        BotProfile anchors = profile(List.of(stock(spec("respawn_anchor", 2)), stock(spec("glowstone", 20))));
        assertTrue(anyNoteContains(notes(anchors, allOn()), "does nothing in the Nether"));
        assertTrue(anyNoteContains(notes(anchors, allOnExcept("anchorPvpEnabled")), "anchor PvP is off globally"));
        assertTrue(anyNoteContains(notes(profile(List.of(stock(spec("glowstone", 3)))), allOn()), "one half of an anchor kit"));
    }

    @Test
    void defenceAndSustainNotes() {
        BotProfile shield = profile(List.of(offhand(spec("shield"))));
        assertTrue(anyNoteContains(notes(shield, allOnExcept("autoShieldEnabled")), "auto-shield is off"));

        BotProfile totem = profile(List.of(offhand(spec("totem_of_undying"))));
        assertTrue(anyNoteContains(notes(totem, allOnExcept("autoTotemEnabled")), "auto-totem is off"));
        assertTrue(anyNoteContains(notes(totem, allOnExcept("autoTotemEnabled")), "vanilla only pops"));

        BotProfile both = profile(List.of(offhand(spec("totem_of_undying")), hotbar(1, spec("shield"))));
        assertTrue(anyNoteContains(notes(both, allOn()), "shield is used from the main hand"));

        BotProfile food = profile(List.of(hotbar(8, spec("bread", 10))));
        assertTrue(anyNoteContains(notes(food, allOnExcept("autoEatEnabled")), "auto-eat is off"));

        BotProfile potions = profile(List.of(stock(new BotProfile.ItemSpec(NS + "splash_potion", 1, Map.of(), 0, NS + "healing"))));
        assertTrue(anyNoteContains(notes(potions, allOnExcept("autoPotionEnabled")), "auto-potion is off"));

        BotProfile xp = profile(List.of(stock(spec("experience_bottle", 12))));
        assertTrue(anyNoteContains(notes(xp, allOnExcept("autoMendEnabled")), "auto-mend is off"));

        BotProfile web = profile(List.of(stock(spec("cobweb", 8))));
        assertTrue(anyNoteContains(notes(web, allOnExcept("cobwebEnabled")), "cobweb setting is off"));
    }

    @Test
    void noFoodMeansNoRetreatOnlyWhenRetreatIsPossible() {
        BotProfile hungry = profile(List.of(hotbar(0, spec("iron_sword"))));
        assertTrue(anyNoteContains(notes(hungry, allOn()), "standard retreat"));
        assertFalse(anyNoteContains(notes(hungry, allOnExcept("retreatEnabled")), "standard retreat"));
        assertFalse(anyNoteContains(notes(profile(List.of(hotbar(8, spec("bread", 3)))), allOn()), "standard retreat"));
    }

    @Test
    void pacifistPathFollowersAreExplained() {
        BotProfile.Behavior pacifist = new BotProfile.Behavior(BotProfile.Stance.GUARD_POST, false,
                BotProfile.WalkType.WALK, 0, 1, List.of());
        assertTrue(anyNoteContains(notes(profile(List.of(), new BotProfile.Vitals(1, 20, Map.of()), pacifist), allOn()),
                "pacifist path follower"));
        BotProfile.Behavior fighter = new BotProfile.Behavior(BotProfile.Stance.GUARD_POST, true,
                BotProfile.WalkType.WALK, 0, 1, List.of());
        assertFalse(anyNoteContains(notes(profile(List.of(), new BotProfile.Vitals(1, 20, Map.of()), fighter), allOn()),
                "pacifist path follower"));
    }

    @Test
    void attributeNotesExplainTheirLimits() {
        Map<String, BotProfile.AttributeMod> attributes = Map.of(
                NS + "entity_interaction_range", new BotProfile.AttributeMod(BotProfile.Op.ADD_VALUE, 2.0),
                NS + "attack_speed", new BotProfile.AttributeMod(BotProfile.Op.ADD_MULTIPLIED_BASE, -0.2));
        List<String> n = notes(profile(List.of(), new BotProfile.Vitals(1, 20, attributes), BotProfile.Behavior.standing()),
                allOn());
        assertTrue(anyNoteContains(n, "lower of it and PvP BOT's global melee range"), n.toString());
        assertTrue(anyNoteContains(n, "can only slow attacks down"), n.toString());
    }

    @Test
    void anUnrestrictedBotSaysSoInsteadOfPrintingAnEmptySection() {
        List<String> n = notes(profile(List.of(hotbar(8, spec("bread", 5)))), allOn());
        assertEquals(1, n.size(), n.toString());
        assertTrue(n.get(0).contains("none: every behaviour"), n.toString());
    }

    @Test
    void notesReflectTheSettingsAsTheyAreNowNotAsTheyWereAtGeneration() {
        // A bot generated while the mace was allowed, inspected after an admin switched it off.
        BotProfile generated = null;
        for (ProfileGenerator.Generation g : generate(300, allOn(), everythingOptions(), 8)) {
            if (g.facts().meleeKind() == Facts.MeleeKind.MACE) {
                generated = g.profile();
                break;
            }
        }
        assertNotNull(generated);
        assertTrue(anyNoteContains(notes(generated, allOnExcept("maceEnabled")), "mace setting is off"));
        assertFalse(anyNoteContains(notes(generated, allOn()), "mace setting is off"));
    }

    @Test
    void nullsAndEmptyProfilesNeverThrow() {
        assertEquals(List.of("(no profile)"), ProfileFormatter.format(null, allOn()));
        assertFalse(ProfileFormatter.format(new BotProfile(0, 0, null, null, null, null), null).isEmpty());
        assertFalse(ProfileFormatter.format(profile(List.of()), null).isEmpty());
        List<String> empty = ProfileFormatter.format(new BotProfile(1, 0, "", null, null, null), allOff());
        assertTrue(empty.contains("Armor:"));
        assertTrue(empty.stream().anyMatch(l -> l.startsWith("Archetype: -")));
    }

    @Test
    void oddContentIsRenderedDefensivelyWithoutThrowing() {
        List<BotProfile.PlacedItem> items = new ArrayList<>();
        items.add(new BotProfile.PlacedItem(null, 0, spec("bow")));
        items.add(new BotProfile.PlacedItem("bogus_slot", 99, spec("stick", 3)));
        items.add(new BotProfile.PlacedItem(BotProfile.Slot.HOTBAR, -5, null));
        items.add(new BotProfile.PlacedItem(BotProfile.Slot.INVENTORY, -1,
                new BotProfile.ItemSpec("modded:crazy_blade", 5, Map.of("modded:vampirism", 999, NS + "sharpness", 12), 0.5, "modded:brew")));
        items.add(new BotProfile.PlacedItem(BotProfile.Slot.HEAD, 0, new BotProfile.ItemSpec(null, 1, null, 0.0, null)));
        items.add(new BotProfile.PlacedItem(BotProfile.Slot.INVENTORY, -1, spec("diamond_helmet")));
        Map<String, BotProfile.AttributeMod> attributes = new LinkedHashMap<>();
        attributes.put("modded:luck_boost", new BotProfile.AttributeMod("teleport", Double.NaN));
        attributes.put(NS + "max_health", new BotProfile.AttributeMod(null, Double.POSITIVE_INFINITY));
        attributes.put(NS + "scale", new BotProfile.AttributeMod("add_value", -1e300));
        BotProfile.Behavior behavior = new BotProfile.Behavior("HOVER", true, "teleport", Double.NaN, -3,
                List.of(new BotProfile.Waypoint(Double.NaN, 1e18, -0.0)));
        BotProfile odd = new BotProfile(-7, Long.MIN_VALUE, null, new BotProfile.Loadout(items),
                new BotProfile.Vitals(Double.NaN, -5, attributes), behavior);

        List<String> out = assertDoesNotThrow(() -> ProfileFormatter.format(odd, allOff()));
        String all = String.join("\n", out);
        assertTrue(all.contains("crazy_blade") || all.contains("modded:crazy_blade"), all);
        assertTrue(all.contains("Other items:"), all);
        assertTrue(all.contains("stance: HOVER"), all);
        assertTrue(all.contains("diamond_helmet"), "armor outside a worn slot is still shown: " + all);
    }

    @Test
    void hugeAndTinyNumbersFormatWithoutLocaleOrScientificNotationSurprises() {
        assertEquals("27", ProfileFormatter.num(27.0));
        assertEquals("0.35", ProfileFormatter.num(0.35));
        assertEquals("12.5", ProfileFormatter.num(12.5));
        assertEquals("0", ProfileFormatter.num(-0.0));
        assertEquals("0", ProfileFormatter.num(0.001));
        assertEquals("-3.9", ProfileFormatter.num(-3.9));
        assertEquals("NaN", ProfileFormatter.num(Double.NaN));
        assertEquals("Infinity", ProfileFormatter.num(Double.POSITIVE_INFINITY));
        assertEquals("minecraft_like", ProfileFormatter.path("minecraft:minecraft_like"));
        assertEquals("mod:thing", ProfileFormatter.path("mod:thing"));
        assertEquals("?", ProfileFormatter.path(null));
    }

    @Test
    void formatterOutputIsImmutable() {
        List<String> lines = ProfileFormatter.format(profile(List.of()), allOn());
        assertThrows(UnsupportedOperationException.class, () -> lines.add("x"));
    }
}

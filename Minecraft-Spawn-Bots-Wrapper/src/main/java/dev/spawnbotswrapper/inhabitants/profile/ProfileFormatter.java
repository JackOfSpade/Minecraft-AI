package dev.spawnbotswrapper.inhabitants.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Human-readable rendering of a profile for {@code /inhabitants profile <bot>}.
 * <p>
 * The output answers two questions an admin actually has: "what does this bot have?" and "why is it not
 * doing X?". The second half matters because a profile is generated once and stored, while the PvP BOT
 * settings can change afterwards: a bot may carry a mace long after {@code mace} was switched off, or a
 * bow no one gave arrows for. The notes at the end are therefore computed from the stored loadout against
 * the capabilities as they are NOW, not as they were at generation. Likewise the rendered loadout is the stored one:
 * it may list an enchantment (Piercing, Mending) that {@code profiles.disabledEnchantments} strips when the profile is
 * applied, so a bot of an older profile can carry less than this shows.
 * <p>
 * Lines are plain text (the command layer may colour them). The formatter never throws: every field of an
 * odd or hand-edited profile is rendered defensively.
 */
public final class ProfileFormatter {
    private ProfileFormatter() {
    }

    private static final String INDENT = "  ";

    /**
     * Plain-text lines (no colour codes; the command layer may colour them) describing everything in the
     * profile, followed by notes on which behaviours are currently switched off GLOBALLY in PvP BOT
     * ({@code caps}) and therefore will not show even though the bot carries the items.
     */
    public static List<String> format(BotProfile profile, GlobalCapabilities caps) {
        if (profile == null) {
            return List.of("(no profile)");
        }
        GlobalCapabilities c = caps == null ? GlobalCapabilities.upstreamDefaults() : caps;

        List<String> out = new ArrayList<>();
        out.add("Archetype: " + orDash(profile.archetype()) + "  (seed " + profile.seed()
                + ", profile v" + profile.version() + ")");

        List<BotProfile.PlacedItem> items = profile.loadout().items();
        armor(out, items);
        section(out, "Melee", items, List.of(ItemIds.Category.MELEE), "fists only (no melee weapon)");
        ranged(out, items);
        defence(out, items);
        sustain(out, items);
        section(out, "Explosive kit", items, List.of(ItemIds.Category.EXPLOSIVE), "none");
        section(out, "Flight", items, List.of(ItemIds.Category.FLIGHT), "none");
        other(out, items);
        vitals(out, profile.vitals());
        behavior(out, profile.behavior());
        out.add("Global PvP BOT settings that affect this bot:");
        for (String note : GlobalNotes.notes(profile, c)) {
            out.add(INDENT + "- " + note);
        }
        return List.copyOf(out);
    }

    private static void armor(List<String> out, List<BotProfile.PlacedItem> items) {
        out.add("Armor:");
        for (ItemIds.ArmorSlot slot : ItemIds.ArmorSlot.values()) {
            List<BotProfile.PlacedItem> here = new ArrayList<>();
            for (BotProfile.PlacedItem p : items) {
                if (slot.slot.equals(p.slot())) {
                    here.add(p);
                }
            }
            String label = slot.slot + ":";
            if (here.isEmpty()) {
                out.add(INDENT + pad(label) + "(empty)");
            }
            for (BotProfile.PlacedItem p : here) {
                out.add(INDENT + pad(label) + describe(p.spec()));
            }
        }
    }

    private static void ranged(List<String> out, List<BotProfile.PlacedItem> items) {
        section(out, "Ranged", items, List.of(ItemIds.Category.RANGED, ItemIds.Category.AMMO), "none");
        int weapons = 0;
        int arrows = 0;
        for (BotProfile.PlacedItem p : items) {
            if (p.spec() == null) {
                continue;
            }
            ItemIds.Category cat = ItemIds.category(p.spec().item());
            if (cat == ItemIds.Category.RANGED) {
                weapons++;
            } else if (cat == ItemIds.Category.AMMO) {
                arrows += p.spec().count();
            }
        }
        if (weapons > 0 || arrows > 0) {
            out.add(INDENT + "arrows in total: " + arrows);
        }
    }

    private static void defence(List<String> out, List<BotProfile.PlacedItem> items) {
        section(out, "Defence", items, List.of(ItemIds.Category.DEFENCE), "none");
        int totems = 0;
        for (BotProfile.PlacedItem p : items) {
            if (p.spec() != null && ItemIds.TOTEM.equals(p.spec().item())) {
                totems += p.spec().count();
            }
        }
        if (totems > 0) {
            out.add(INDENT + "totems in total: " + totems);
        }
    }

    private static void sustain(List<String> out, List<BotProfile.PlacedItem> items) {
        section(out, "Sustain", items, List.of(ItemIds.Category.FOOD, ItemIds.Category.POTION, ItemIds.Category.UTILITY),
                "none");
    }

    private static void other(List<String> out, List<BotProfile.PlacedItem> items) {
        List<BotProfile.PlacedItem> odd = new ArrayList<>();
        for (BotProfile.PlacedItem p : items) {
            if (p.spec() == null) {
                odd.add(p);
            } else if (ItemIds.category(p.spec().item()) == ItemIds.Category.OTHER
                    || (ItemIds.isArmor(p.spec().item()) && !isWornSlot(p.slot()))) {
                odd.add(p);
            }
        }
        if (odd.isEmpty()) {
            return;
        }
        out.add("Other items:");
        for (String line : aggregate(odd)) {
            out.add(INDENT + line);
        }
    }

    private static boolean isWornSlot(String slot) {
        for (ItemIds.ArmorSlot s : ItemIds.ArmorSlot.values()) {
            if (s.slot.equals(slot)) {
                return true;
            }
        }
        return false;
    }

    /** One heading with the aggregated items of the given categories. */
    private static void section(List<String> out, String heading, List<BotProfile.PlacedItem> items,
                                List<ItemIds.Category> categories, String whenEmpty) {
        List<BotProfile.PlacedItem> mine = new ArrayList<>();
        for (BotProfile.PlacedItem p : items) {
            if (p.spec() != null && categories.contains(ItemIds.category(p.spec().item()))) {
                mine.add(p);
            }
        }
        out.add(heading + ":");
        if (mine.isEmpty()) {
            out.add(INDENT + whenEmpty);
            return;
        }
        for (String line : aggregate(mine)) {
            out.add(INDENT + line);
        }
    }

    /**
     * Identical stacks (same item, potion, enchantments and wear) are merged into one line with the total
     * count, so eight single-stack healing potions read as "x8", and the places they sit in are listed.
     */
    private static List<String> aggregate(List<BotProfile.PlacedItem> placed) {
        Map<String, Aggregate> byKey = new LinkedHashMap<>();
        for (BotProfile.PlacedItem p : placed) {
            BotProfile.ItemSpec s = p.spec();
            if (s == null) {
                byKey.computeIfAbsent("?" + p.slot(), k -> new Aggregate(null)).places.add(place(p));
                continue;
            }
            String key = s.item() + "|" + s.potion() + "|" + new TreeMap<>(s.enchantments()) + "|" + s.damageFraction();
            Aggregate agg = byKey.computeIfAbsent(key, k -> new Aggregate(s));
            agg.count += s.count();
            agg.places.add(place(p));
        }
        List<String> lines = new ArrayList<>();
        for (Aggregate agg : byKey.values()) {
            String where = "[" + String.join(", ", agg.places) + "]";
            lines.add(agg.spec == null ? "(missing item) " + where : describe(agg.spec, agg.count) + "  " + where);
        }
        return lines;
    }

    private static final class Aggregate {
        final BotProfile.ItemSpec spec;
        int count;
        final Set<String> places = new LinkedHashSet<>();

        Aggregate(BotProfile.ItemSpec spec) {
            this.spec = spec;
        }
    }

    private static String place(BotProfile.PlacedItem p) {
        String slot = p.slot() == null ? "?" : p.slot();
        if (BotProfile.Slot.HOTBAR.equals(slot)) {
            return "hotbar " + p.index();
        }
        return slot;
    }

    private static String describe(BotProfile.ItemSpec s) {
        return s == null ? "(missing item)" : describe(s, s.count());
    }

    private static String describe(BotProfile.ItemSpec s, int count) {
        StringBuilder b = new StringBuilder(path(s.item()));
        if (s.potion() != null) {
            b.append(" (").append(path(s.potion())).append(')');
        }
        if (count != 1) {
            b.append(" x").append(count);
        }
        if (!s.enchantments().isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> e : new TreeMap<>(s.enchantments()).entrySet()) {
                parts.add(enchantment(e.getKey(), e.getValue()));
            }
            b.append(" - ").append(String.join(", ", parts));
        }
        if (s.damageFraction() > 0) {
            b.append(" - wear ").append(Math.round(s.damageFraction() * 100)).append('%');
        }
        return b.toString();
    }

    private static String enchantment(String id, int level) {
        // A level-1-only enchantment (mending, infinity) reads better without a numeral.
        return ItemIds.maxEnchantmentLevel(id) == 1 ? path(id) : path(id) + " " + roman(level);
    }

    private static String roman(int level) {
        String[] numerals = {"0", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return level >= 0 && level < numerals.length ? numerals[level] : Integer.toString(level);
    }

    private static void vitals(List<String> out, BotProfile.Vitals v) {
        out.add("Vitals:");
        double maxHealth = ItemIds.ATTRIBUTE_BASE.getOrDefault(ItemIds.MAX_HEALTH, 20.0);
        out.add(INDENT + "health: starts at " + Math.round(v.healthFraction() * 100) + "% of max ("
                + num(v.healthFraction() * maxHealth) + " of " + num(maxHealth) + " HP)");
        out.add(INDENT + "food level: " + v.foodLevel() + "/20");
        if (v.attributes().isEmpty()) {
            out.add(INDENT + "attributes: none (vanilla values)");
        } else {
            out.add(INDENT + "attributes: none applied (vanilla values); " + v.attributes().size()
                    + " stored by an older version are ignored");
        }
    }

    private static void behavior(List<String> out, BotProfile.Behavior b) {
        out.add("Behaviour:");
        out.add(INDENT + "stance: " + b.stance() + stanceMeaning(b.stance()));
        out.add(INDENT + "walk type: " + b.walkType() + (b.usesPath() ? "" : " (only used while following a path)"));
        out.add(INDENT + "combatant: yes (every inhabitant fights)");
        out.add(INDENT + "patrol radius: " + num(b.patrolRadius()) + " blocks");
        out.add(INDENT + "waypoints: " + b.waypointCount() + " planned, " + b.waypoints().size() + " placed");
        int n = 1;
        for (BotProfile.Waypoint w : b.waypoints()) {
            out.add(INDENT + INDENT + "#" + n++ + " (" + num(w.x()) + ", " + num(w.y()) + ", " + num(w.z()) + ")");
        }
    }

    private static String stanceMeaning(String stance) {
        return switch (String.valueOf(stance)) {
            case BotProfile.Stance.STAND -> " (stays where it spawned)";
            case BotProfile.Stance.GUARD_POST -> " (stands at its post and returns to it after a fight)";
            case BotProfile.Stance.PATROL_PINGPONG -> " (walks its waypoints back and forth)";
            case BotProfile.Stance.PATROL_CYCLE -> " (walks its waypoints as a ring)";
            default -> "";
        };
    }

    /** Registry path without the {@code minecraft:} namespace ({@code diamond_sword}); other namespaces stay whole. */
    static String path(String id) {
        if (id == null || id.isEmpty()) {
            return "?";
        }
        return id.startsWith(ItemIds.NS) ? id.substring(ItemIds.NS.length()) : id;
    }

    /** Whole numbers without a decimal point, otherwise at most two decimals, never locale dependent. */
    static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return Double.toString(v);
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        String s = String.format(Locale.ROOT, "%.2f", v);
        s = s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
        return s.equals("-0") ? "0" : s;
    }

    private static String orDash(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    private static String pad(String label) {
        StringBuilder b = new StringBuilder(label);
        while (b.length() < 7) {
            b.append(' ');
        }
        return b.toString();
    }
}

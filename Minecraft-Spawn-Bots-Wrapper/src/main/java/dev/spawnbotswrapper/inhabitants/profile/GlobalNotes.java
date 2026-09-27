package dev.spawnbotswrapper.inhabitants.profile;

import java.util.ArrayList;
import java.util.List;

/**
 * Explains, for a stored profile and the PvP BOT settings as they are now, why something the bot carries
 * may never show up in a fight. Kept apart from {@link ProfileFormatter} because the rules are the
 * interesting part: each note is a fact about PvP BOT v0.0.15 (which item gates which behaviour, which
 * global switch overrides it), and the list has to stay easy to extend.
 * <p>
 * The checks read the loadout, not the generator's facts, so they stay correct for profiles generated
 * under other settings or by an older version.
 */
final class GlobalNotes {
    private GlobalNotes() {
    }

    static List<String> notes(BotProfile profile, GlobalCapabilities caps) {
        Inventory inv = new Inventory(profile.loadout());
        List<String> out = new ArrayList<>();

        combat(out, profile, caps);
        ranged(out, inv, caps);
        melee(out, inv, caps);
        kits(out, inv, caps);
        defence(out, inv, caps);
        sustain(out, inv, caps);
        attributes(out, profile.vitals());

        if (out.isEmpty()) {
            out.add("none: every behaviour this bot's loadout can use is enabled globally");
        }
        return out;
    }

    private static void combat(List<String> out, BotProfile profile, GlobalCapabilities caps) {
        if (!caps.combatEnabled()) {
            out.add("Combat is disabled globally: this bot never attacks or defends itself, so its weapons, "
                    + "ranged gear, cobwebs and crystal/anchor kits are unused.");
        } else if (!caps.autoTargetEnabled()) {
            out.add("Auto-target is off: this bot stays passive until it is attacked (revenge), given a forced "
                    + "target or placed in a hostile faction. It is a global PvP BOT setting, not a per-bot choice.");
        }
        BotProfile.Behavior b = profile.behavior();
        if (b.usesPath() && !b.combatant()) {
            out.add("This bot is a pacifist path follower: PvP BOT skips its combat AI entirely while it follows "
                    + "its path (no retaliation); only eating, totems, potions, mending and shield automation still run.");
        }
    }

    private static void ranged(List<String> out, Inventory inv, GlobalCapabilities caps) {
        boolean weapon = inv.has(ItemIds.BOW) || inv.has(ItemIds.CROSSBOW);
        int arrows = inv.arrows();
        if (weapon && !caps.rangedEnabled()) {
            out.add("Carries a bow/crossbow but ranged combat is off globally (ranged setting): it is never used.");
        } else if (weapon && arrows == 0) {
            out.add("Carries a bow/crossbow but no arrows: PvP BOT needs an arrow in its inventory to use it, "
                    + "so the weapon is useless.");
        }
        if (!weapon && arrows > 0) {
            out.add("Carries arrows but no bow or crossbow: the arrows do nothing.");
        }
        if (inv.has(ItemIds.BOW) && inv.has(ItemIds.CROSSBOW)) {
            out.add("Carries both a bow and a crossbow: PvP BOT prefers the crossbow.");
        }
        if (weapon && caps.rangedEnabled() && arrows > 0 && inv.hasMelee()) {
            out.add("With a ranged weapon and arrows PvP BOT normally stays in ranged mode even at close "
                    + "range, so its melee weapon is rarely used until the arrows run out.");
        }
    }

    private static void melee(List<String> out, Inventory inv, GlobalCapabilities caps) {
        if (inv.has(ItemIds.MACE) && !caps.maceEnabled()) {
            out.add("Carries a mace but the mace setting is off globally: PvP BOT never uses mace attacks.");
        }
        if (inv.hasSpear() && !caps.spearEnabled()) {
            out.add("Carries a spear but the spear setting is off globally (its default): PvP BOT ignores "
                    + "spears and never selects one as its weapon.");
        }
        if (inv.hasAxe() && !caps.shieldBreakEnabled()) {
            out.add("Shield-breaking is off globally: its axe is only ever used as an ordinary weapon.");
        }
        if (inv.elytraWorn() && inv.count(ItemIds.FIREWORK_ROCKET) > 0 && inv.has(ItemIds.MACE)) {
            out.add("Elytra + firework rockets + a mace start PvP BOT's rocket-dive routine (needs a forced "
                    + "target or a recent hit by a player and ignores the mace setting); it can carry the bot "
                    + "out of its structure.");
        }
    }

    private static void kits(List<String> out, Inventory inv, GlobalCapabilities caps) {
        boolean crystals = inv.has(ItemIds.END_CRYSTAL);
        boolean obsidian = inv.has(ItemIds.OBSIDIAN);
        if (crystals && obsidian) {
            if (!caps.crystalPvpEnabled()) {
                out.add("Carries end crystals and obsidian but crystal PvP is off globally: they stay in the inventory.");
            } else {
                out.add("Crystal kit: when fighting PvP BOT places obsidian and end crystals next to its target and "
                        + "detonates them, which can destroy blocks of the structure.");
            }
        } else if (crystals || obsidian) {
            out.add("Carries only one half of a crystal kit: PvP BOT needs both end crystals and obsidian.");
        }
        boolean anchors = inv.has(ItemIds.RESPAWN_ANCHOR);
        boolean glowstone = inv.has(ItemIds.GLOWSTONE);
        if (anchors && glowstone) {
            if (!caps.anchorPvpEnabled()) {
                out.add("Carries respawn anchors and glowstone but anchor PvP is off globally: they stay in the inventory.");
            } else {
                out.add("Anchor kit: when fighting PvP BOT places, charges and detonates respawn anchors next to its "
                        + "target, which can destroy blocks of the structure. It does nothing in the Nether.");
            }
        } else if (anchors || glowstone) {
            out.add("Carries only one half of an anchor kit: PvP BOT needs both a respawn anchor and glowstone.");
        }
    }

    private static void defence(List<String> out, Inventory inv, GlobalCapabilities caps) {
        if (inv.has(ItemIds.SHIELD) && !caps.autoShieldEnabled()) {
            out.add("Carries a shield but auto-shield is off globally: PvP BOT never raises it.");
        }
        if (inv.has(ItemIds.TOTEM) && !caps.autoTotemEnabled()) {
            out.add("Carries totems but auto-totem is off globally: PvP BOT will not move one to the offhand "
                    + "(vanilla only pops a totem that is held in a hand).");
        }
        if (inv.has(ItemIds.SHIELD) && inv.offhandHolds(ItemIds.TOTEM) && caps.autoShieldEnabled()) {
            out.add(caps.totemPriority()
                    ? "The offhand holds a totem, so the shield is used from the main hand (hotbar slot 1)."
                    : "totem-priority is off globally: raising this shield will overwrite and destroy the "
                            + "offhand totem instead of using the main hand. This profile should not have been "
                            + "given both; report it if it was generated after this note was written.");
        }
    }

    private static void sustain(List<String> out, Inventory inv, GlobalCapabilities caps) {
        boolean food = inv.hasFood();
        if (food && !caps.autoEatEnabled()) {
            out.add("Carries food but auto-eat is off globally: this bot never eats.");
        }
        if (!food && caps.retreatEnabled() && caps.combatEnabled()) {
            out.add("Carries no food, so PvP BOT will not use its standard retreat when hurt (that retreat needs food).");
        }
        if (inv.hasPotion() && !caps.autoPotionEnabled()) {
            out.add("Carries potions but auto-potion is off globally: PvP BOT never drinks or throws them.");
        }
        if (inv.has(ItemIds.EXPERIENCE_BOTTLE) && !caps.autoMendEnabled()) {
            out.add("Carries experience bottles but auto-mend is off globally: worn armor is never repaired.");
        }
        if (inv.has(ItemIds.COBWEB) && !caps.cobwebEnabled()) {
            out.add("Carries cobwebs but the cobweb setting is off globally: it never places them on its target "
                    + "(a water bucket or ender pearl only helps it escape a web it stands in).");
        }
    }

    private static void attributes(List<String> out, BotProfile.Vitals vitals) {
        if (vitals.attributes().containsKey(ItemIds.ENTITY_INTERACTION_RANGE)) {
            out.add("Its interaction-range modifier only changes how far its attack commands reach; the effective "
                    + "melee reach is the lower of it and PvP BOT's global melee range.");
        }
        if (vitals.attributes().containsKey(ItemIds.ATTACK_SPEED)) {
            out.add("Its attack-speed modifier can only slow attacks down: PvP BOT waits for the longer of its own "
                    + "attack cooldown and the vanilla weapon cooldown.");
        }
    }

    /** Read-only queries over a loadout, tolerant of missing specs and odd slots. */
    private static final class Inventory {
        private final List<BotProfile.PlacedItem> items;

        Inventory(BotProfile.Loadout loadout) {
            this.items = loadout.items();
        }

        int count(String item) {
            int n = 0;
            for (BotProfile.PlacedItem p : items) {
                if (p.spec() != null && item.equals(p.spec().item())) {
                    n += p.spec().count();
                }
            }
            return n;
        }

        boolean has(String item) {
            return count(item) > 0;
        }

        int arrows() {
            int n = 0;
            for (BotProfile.PlacedItem p : items) {
                if (p.spec() != null && ItemIds.isArrow(p.spec().item())) {
                    n += p.spec().count();
                }
            }
            return n;
        }

        boolean hasMelee() {
            for (BotProfile.PlacedItem p : items) {
                if (p.spec() != null && ItemIds.isMelee(p.spec().item())) {
                    return true;
                }
            }
            return false;
        }

        boolean hasSpear() {
            for (BotProfile.PlacedItem p : items) {
                if (p.spec() != null && ItemIds.isSpear(p.spec().item())) {
                    return true;
                }
            }
            return false;
        }

        boolean hasAxe() {
            for (BotProfile.PlacedItem p : items) {
                if (p.spec() != null && ItemIds.isAxe(p.spec().item())) {
                    return true;
                }
            }
            return false;
        }

        boolean hasFood() {
            for (BotProfile.PlacedItem p : items) {
                if (p.spec() != null && ItemIds.isFood(p.spec().item())) {
                    return true;
                }
            }
            return false;
        }

        boolean hasPotion() {
            for (BotProfile.PlacedItem p : items) {
                if (p.spec() != null && ItemIds.isPotionItem(p.spec().item())) {
                    return true;
                }
            }
            return false;
        }

        boolean elytraWorn() {
            for (BotProfile.PlacedItem p : items) {
                if (BotProfile.Slot.CHEST.equals(p.slot()) && p.spec() != null
                        && ItemIds.ELYTRA.equals(p.spec().item())) {
                    return true;
                }
            }
            return false;
        }

        boolean offhandHolds(String item) {
            for (BotProfile.PlacedItem p : items) {
                if (BotProfile.Slot.OFFHAND.equals(p.slot()) && p.spec() != null && item.equals(p.spec().item())) {
                    return true;
                }
            }
            return false;
        }
    }
}

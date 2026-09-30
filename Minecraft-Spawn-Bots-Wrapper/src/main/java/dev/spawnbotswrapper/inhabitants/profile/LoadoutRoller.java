package dev.spawnbotswrapper.inhabitants.profile;

import dev.spawnbotswrapper.inhabitants.config.DisabledEnchantments;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.profile.ItemIds.ArmorSlot;
import dev.spawnbotswrapper.inhabitants.profile.ItemIds.ArmorTier;
import dev.spawnbotswrapper.inhabitants.profile.ItemIds.Buff;
import dev.spawnbotswrapper.inhabitants.profile.ItemIds.ToolTier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Rolls everything a bot wears and carries. One instance rolls one loadout.
 * <p>
 * Facets are independent (armor, melee, ranged, defence, sustain, explosive kit, elytra) so a bot can
 * be an archer with a shield or a naked mace fighter; what is deliberately NOT independent is anything
 * PvP BOT would otherwise fight the loadout over:
 * <ul>
 *   <li>Only items whose behaviour the global capabilities leave switched on are rolled. A mace with
 *       {@code mace=false}, a bow with {@code ranged=false} or a totem with {@code auto-totem=false} does
 *       nothing, so it is not handed out.</li>
 *   <li>The best weapon (by PvP BOT's own ranking) goes in hotbar slot 0 and ranged weapons sit in the
 *       hotbar, the only place PvP BOT selects them from. Hotbar slot 1 is where PvP BOT swaps a shield
 *       in when a totem occupies the offhand, and slot 8 is its food/potion scratch slot.</li>
 *   <li>A totem takes the offhand (PvP BOT would move it there next tick anyway, displacing a shield);
 *       a shield only gets the offhand when there is no totem.</li>
 *   <li>Armor is worn directly, never carried as a spare, so auto-equip has nothing to swap.</li>
 * </ul>
 * All randomness goes through the {@link Roller} with a stable key per decision; see {@link Roller}.
 */
final class LoadoutRoller {

    /** The rolled loadout with the categorical outcomes it was built from. */
    record Rolled(BotProfile.Loadout loadout, Facts facts) {
    }

    private static final List<ArmorTier> ARMOR_TIERS = List.of(ArmorTier.values());
    private static final List<ToolTier> TOOL_TIERS = List.of(ToolTier.values());
    private static final List<Facts.ArmorMode> ARMOR_MODES = List.of(Facts.ArmorMode.values());
    private static final List<Facts.RangedKind> RANGED_KINDS = List.of(Facts.RangedKind.values());
    private static final List<String> HEAD_PIECES = headPieces();

    /**
     * A PARTIAL roll deals one of the 14 "some but not all" subsets of the four armor slots (bitmasks
     * 1..14), so every slot is worn in exactly half of them.
     */
    private static final int PARTIAL_SETS = 14;

    /** Damage range for worn gear: pristine to one hit from breaking (BotProfile clamps at 0.95). */
    private static final double MAX_WEAR = 0.9;

    private enum SpecialArrows { NONE, SPECTRAL, TIPPED }

    /** Half of the ranged bots keep plain arrows; the rest carry a stack of spectral or tipped ones. */
    private static final List<SpecialArrows> SPECIAL_ARROWS = List.of(
            SpecialArrows.NONE, SpecialArrows.NONE, SpecialArrows.SPECTRAL, SpecialArrows.TIPPED);

    /** Two thirds of healing potions are thrown (splash), one third is drunk. */
    private static final List<String> HEALING_DELIVERY = List.of(
            ItemIds.SPLASH_POTION, ItemIds.SPLASH_POTION, ItemIds.POTION);

    private final Roller r;
    private final GlobalCapabilities caps;
    private final InhabitantsConfig.Profiles options;
    /** Canonical ids of profiles.disabledEnchantments: rolled as usual (same draws) but never put on an item. */
    private final Set<String> disabled;
    private final LoadoutBuilder out = new LoadoutBuilder();

    private Facts.ArmorMode armorMode = Facts.ArmorMode.NONE;
    private Facts.MeleeKind meleeKind = Facts.MeleeKind.NONE;
    private Facts.RangedKind rangedKind = Facts.RangedKind.NONE;
    private final List<BotProfile.ItemSpec> melee = new ArrayList<>();
    private final List<BotProfile.ItemSpec> ranged = new ArrayList<>();
    private BotProfile.ItemSpec shield;
    private boolean shieldInHotbar;
    private int arrows;
    private int totems;
    private Facts.ExplosiveKit explosive = Facts.ExplosiveKit.NONE;
    private boolean elytra;
    private int rockets;

    private LoadoutRoller(Roller r, GlobalCapabilities caps, InhabitantsConfig.Profiles options) {
        this.r = r;
        this.caps = caps;
        this.options = options;
        this.disabled = DisabledEnchantments.parse(options == null ? null : options.disabledEnchantments);
    }

    static Rolled roll(Roller r, GlobalCapabilities caps, InhabitantsConfig.Profiles options) {
        return new LoadoutRoller(r, caps, options).roll();
    }

    private Rolled roll() {
        armor();
        elytra();
        melee();
        ranged();
        defence();
        sustain();
        explosive();
        layout();

        BotProfile.Loadout loadout = out.build();
        int armorTotal = 0;
        for (ArmorSlot s : ArmorSlot.values()) {
            BotProfile.ItemSpec piece = out.worn(s.slot);
            armorTotal += piece == null ? 0 : PvpBotRanking.armorScore(piece.item());
        }
        Facts facts = new Facts(armorMode, meleeKind, rangedKind, arrows, shield != null, totems, explosive,
                elytra, rockets, armorTotal / ArmorSlot.values().length, out.trimmed());
        return new Rolled(loadout, facts);
    }

    private void armor() {
        armorMode = r.pick("profile.armor.mode", ARMOR_MODES);
        switch (armorMode) {
            case NONE -> {
            }
            case PARTIAL -> {
                int mask = 1 + r.index("profile.armor.partialSlots", PARTIAL_SETS);
                for (ArmorSlot s : ArmorSlot.values()) {
                    if ((mask & (1 << s.ordinal())) != 0) {
                        wearArmor(s, pieceFor(s));
                    }
                }
            }
            case MIXED -> {
                for (ArmorSlot s : ArmorSlot.values()) {
                    wearArmor(s, pieceFor(s));
                }
            }
            case MATCHING_SET -> {
                ArmorTier tier = r.pick("profile.armor.set.tier", ARMOR_TIERS);
                for (ArmorSlot s : ArmorSlot.values()) {
                    wearArmor(s, ItemIds.armor(tier, s));
                }
            }
        }
    }

    /** The helmet slot also draws the turtle helmet, which PvP BOT ranks between iron and chainmail. */
    private String pieceFor(ArmorSlot slot) {
        if (slot == ArmorSlot.HEAD) {
            return r.pick("profile.armor.head.tier", HEAD_PIECES);
        }
        return ItemIds.armor(r.pick("profile.armor." + slot.slot + ".tier", ARMOR_TIERS), slot);
    }

    private void wearArmor(ArmorSlot slot, String item) {
        String key = "profile.armor." + slot.slot;
        Map<String, Integer> enchants = new TreeMap<>();
        int protection = r.level(key + ".protection", ItemIds.maxEnchantmentLevel(ItemIds.PROTECTION));
        if (protection > 0) {
            put(enchants, r.pick(key + ".protectionType", ItemIds.PROTECTION_TYPES), protection);
        }
        enchant(enchants, ItemIds.UNBREAKING, key + ".unbreaking");
        if (caps.autoMendEnabled() && r.flag(key + ".mending")) {
            put(enchants, ItemIds.MENDING, 1);
        }
        if (r.oneIn(key + ".thorns", 8)) {
            put(enchants, ItemIds.THORNS, r.count(key + ".thornsLevel", 1, ItemIds.maxEnchantmentLevel(ItemIds.THORNS)));
        }
        double wear = r.fraction(key + ".damage", 0.0, MAX_WEAR, 2);
        out.wear(slot.slot, new BotProfile.ItemSpec(item, 1, enchants, wear, null));
    }

    private static List<String> headPieces() {
        List<String> out = new ArrayList<>();
        for (ArmorTier t : ArmorTier.values()) {
            out.add(ItemIds.armor(t, ArmorSlot.HEAD));
        }
        out.add(ItemIds.TURTLE_HELMET);
        return List.copyOf(out);
    }

    /**
     * Elytra replace the chestplate. On their own they are harmless; together with firework rockets and a
     * mace PvP BOT starts a rocket-dive routine that carries the bot out of its structure, which is why the
     * whole facet sits behind {@code allowElytra}.
     */
    private void elytra() {
        if (!options.allowElytra || !r.oneIn("profile.elytra.equip", 4)) {
            return;
        }
        double wear = r.fraction("profile.elytra.damage", 0.0, MAX_WEAR, 2);
        out.wear(BotProfile.Slot.CHEST, new BotProfile.ItemSpec(ItemIds.ELYTRA, 1, Map.of(), wear, null));
        elytra = true;
        rockets = r.count("profile.elytra.rockets", 0, 64);
        out.stockSplit(BotProfile.ItemSpec.of(ItemIds.FIREWORK_ROCKET), rockets, LoadoutBuilder.LUXURY);
    }

    private void melee() {
        boolean combat = caps.combatEnabled();
        List<Facts.MeleeKind> kinds = new ArrayList<>(List.of(
                Facts.MeleeKind.NONE, Facts.MeleeKind.SWORD, Facts.MeleeKind.AXE, Facts.MeleeKind.SWORD_AND_AXE));
        if (combat && caps.maceEnabled()) {
            kinds.add(Facts.MeleeKind.MACE);
        }
        if (combat && caps.spearEnabled()) {
            kinds.add(Facts.MeleeKind.SPEAR);
        }
        kinds.add(Facts.MeleeKind.TRIDENT);
        meleeKind = r.pickVarying("profile.melee.kind", kinds);

        switch (meleeKind) {
            case SWORD -> melee.add(sword());
            case AXE -> melee.add(axe());
            case SWORD_AND_AXE -> {
                melee.add(sword());
                melee.add(axe());
            }
            case MACE -> {
                melee.add(mace());
                out.stockSplit(BotProfile.ItemSpec.of(ItemIds.WIND_CHARGE),
                        r.count("profile.melee.windCharges", 0, 32), LoadoutBuilder.UTILITY);
            }
            case SPEAR -> melee.add(spear());
            case TRIDENT -> melee.add(trident());
            case NONE -> {
            }
        }
    }

    private BotProfile.ItemSpec sword() {
        Map<String, Integer> e = new TreeMap<>();
        enchant(e, ItemIds.SHARPNESS, "profile.melee.sword.sharpness");
        enchant(e, ItemIds.FIRE_ASPECT, "profile.melee.sword.fireAspect");
        enchant(e, ItemIds.KNOCKBACK, "profile.melee.sword.knockback");
        enchant(e, ItemIds.SWEEPING_EDGE, "profile.melee.sword.sweeping");
        enchant(e, ItemIds.UNBREAKING, "profile.melee.sword.unbreaking");
        return weapon(ItemIds.sword(r.pick("profile.melee.sword.tier", TOOL_TIERS)), e);
    }

    /** Vanilla axes take sharpness but neither fire aspect, knockback nor sweeping. */
    private BotProfile.ItemSpec axe() {
        Map<String, Integer> e = new TreeMap<>();
        enchant(e, ItemIds.SHARPNESS, "profile.melee.axe.sharpness");
        enchant(e, ItemIds.UNBREAKING, "profile.melee.axe.unbreaking");
        return weapon(ItemIds.axe(r.pick("profile.melee.axe.tier", TOOL_TIERS)), e);
    }

    private BotProfile.ItemSpec spear() {
        Map<String, Integer> e = new TreeMap<>();
        enchant(e, ItemIds.SHARPNESS, "profile.melee.spear.sharpness");
        enchant(e, ItemIds.FIRE_ASPECT, "profile.melee.spear.fireAspect");
        enchant(e, ItemIds.KNOCKBACK, "profile.melee.spear.knockback");
        enchant(e, ItemIds.LUNGE, "profile.melee.spear.lunge");
        enchant(e, ItemIds.UNBREAKING, "profile.melee.spear.unbreaking");
        return weapon(ItemIds.spear(r.pick("profile.melee.spear.tier", TOOL_TIERS)), e);
    }

    /** Density and breach are mutually exclusive in vanilla, so a mace gets at most one of them. */
    private BotProfile.ItemSpec mace() {
        Map<String, Integer> e = new TreeMap<>();
        if (r.flag("profile.melee.mace.breachInsteadOfDensity")) {
            enchant(e, ItemIds.BREACH, "profile.melee.mace.breach");
        } else {
            enchant(e, ItemIds.DENSITY, "profile.melee.mace.density");
        }
        enchant(e, ItemIds.WIND_BURST, "profile.melee.mace.windBurst");
        enchant(e, ItemIds.FIRE_ASPECT, "profile.melee.mace.fireAspect");
        enchant(e, ItemIds.UNBREAKING, "profile.melee.mace.unbreaking");
        return weapon(ItemIds.MACE, e);
    }

    private BotProfile.ItemSpec trident() {
        Map<String, Integer> e = new TreeMap<>();
        enchant(e, ItemIds.IMPALING, "profile.melee.trident.impaling");
        enchant(e, ItemIds.UNBREAKING, "profile.melee.trident.unbreaking");
        return weapon(ItemIds.TRIDENT, e);
    }

    private BotProfile.ItemSpec weapon(String item, Map<String, Integer> enchants) {
        return new BotProfile.ItemSpec(item, 1, enchants, r.fraction("profile.melee.wear", 0.0, MAX_WEAR, 2), null);
    }

    /**
     * A ranged weapon is only useful with an arrow somewhere in the inventory, but arrow counts start at
     * 0 on purpose: an archer that ran out of ammunition is a real (and PvP BOT-faithful) outcome.
     */
    private void ranged() {
        if (!caps.rangedEnabled() || !caps.combatEnabled()) {
            return;
        }
        rangedKind = r.pick("profile.ranged.kind", RANGED_KINDS);
        if (rangedKind == Facts.RangedKind.NONE) {
            return;
        }
        boolean crossbow = rangedKind == Facts.RangedKind.CROSSBOW || rangedKind == Facts.RangedKind.BOTH;
        boolean bow = rangedKind == Facts.RangedKind.BOW || rangedKind == Facts.RangedKind.BOTH;
        if (crossbow) {
            ranged.add(crossbow());
        }
        if (bow) {
            ranged.add(bow());
        }
        arrows = r.count("profile.ranged.arrows", 0, 256);
        stockArrows(arrows);
    }

    private BotProfile.ItemSpec bow() {
        Map<String, Integer> e = new TreeMap<>();
        enchant(e, ItemIds.POWER, "profile.ranged.bow.power");
        enchant(e, ItemIds.PUNCH, "profile.ranged.bow.punch");
        if (r.flag("profile.ranged.bow.infinity")) {
            put(e, ItemIds.INFINITY, 1);
        }
        enchant(e, ItemIds.UNBREAKING, "profile.ranged.bow.unbreaking");
        return new BotProfile.ItemSpec(ItemIds.BOW, 1, e, 0.0, null);
    }

    /**
     * Piercing and multishot are exclusive in vanilla; only piercing is rolled, and by default (profiles.disabledEnchantments)
     * it is rolled but never applied: piercing bolts ignore shields, so a hostile inhabitant's crossbow could not be blocked.
     * The draw is still consumed, so nothing else about a seeded loadout changes and multishot is NOT substituted.
     */
    private BotProfile.ItemSpec crossbow() {
        Map<String, Integer> e = new TreeMap<>();
        enchant(e, ItemIds.QUICK_CHARGE, "profile.ranged.crossbow.quickCharge");
        enchant(e, ItemIds.PIERCING, "profile.ranged.crossbow.piercing");
        enchant(e, ItemIds.UNBREAKING, "profile.ranged.crossbow.unbreaking");
        return new BotProfile.ItemSpec(ItemIds.CROSSBOW, 1, e, 0.0, null);
    }

    private void stockArrows(int total) {
        if (total <= 0) {
            return;
        }
        int special = 0;
        SpecialArrows kind = SpecialArrows.NONE;
        if (total >= 2) {
            kind = r.pick("profile.ranged.special", SPECIAL_ARROWS);
            if (kind != SpecialArrows.NONE) {
                special = Math.min(total - 1, r.count("profile.ranged.specialCount", 1, 32));
            }
        }
        out.stockSplit(BotProfile.ItemSpec.of(ItemIds.ARROW), total - special, LoadoutBuilder.AMMO);
        if (kind == SpecialArrows.SPECTRAL) {
            out.stockSplit(BotProfile.ItemSpec.of(ItemIds.SPECTRAL_ARROW), special, LoadoutBuilder.AMMO);
        } else if (kind == SpecialArrows.TIPPED) {
            String potion = r.pick("profile.ranged.tippedPotion", ItemIds.TIPPED_ARROW_POTIONS);
            out.stockSplit(new BotProfile.ItemSpec(ItemIds.TIPPED_ARROW, 1, Map.of(), 0.0, potion),
                    special, LoadoutBuilder.AMMO);
        }
    }

    private void defence() {
        boolean wantsShield = caps.autoShieldEnabled() && r.flag("profile.defence.shield");
        totems = caps.autoTotemEnabled() ? r.count("profile.defence.totems", 0, 4) : 0;

        if (totems >= 1) {
            out.offhand(BotProfile.ItemSpec.of(ItemIds.TOTEM));
            for (int i = 1; i < totems; i++) {
                out.stock(BotProfile.ItemSpec.of(ItemIds.TOTEM), LoadoutBuilder.TOTEM);
            }
        }
        // With totemPriority off, PvP BOT's shield equip overwrites the offhand outright (BotCombat destroys
        // whatever was there, including a totem) instead of routing through hotbar slot 1: giving this bot
        // BOTH would mean the shield silently destroys its emergency totem the first time it blocks. Keep
        // the totem and skip the shield rather than hand out a self-defeating loadout.
        if (wantsShield && (totems == 0 || caps.totemPriority())) {
            shield = BotProfile.ItemSpec.of(ItemIds.SHIELD);
            if (totems >= 1) {
                shieldInHotbar = true;
            } else {
                out.offhand(shield);
            }
        }
    }

    private void sustain() {
        if (caps.autoEatEnabled()) {
            String food = r.pick("profile.sustain.food.kind", ItemIds.FOODS);
            int foodCount = r.count("profile.sustain.food.count", 0, 64);
            if (foodCount > 0) {
                out.hotbar(LoadoutBuilder.HOTBAR_SIZE - 1, BotProfile.ItemSpec.of(food, foodCount));
            }
            out.stockSplit(BotProfile.ItemSpec.of(ItemIds.GOLDEN_APPLE),
                    r.count("profile.sustain.goldenApples", 0, 8), LoadoutBuilder.SUSTAIN);
            if (r.oneIn("profile.sustain.enchantedApple", 8)) {
                out.stockSplit(BotProfile.ItemSpec.of(ItemIds.ENCHANTED_GOLDEN_APPLE),
                        r.count("profile.sustain.enchantedApple.count", 1, 2), LoadoutBuilder.LUXURY);
            }
        }
        if (caps.autoPotionEnabled()) {
            potions();
        }
        if (caps.autoMendEnabled()) {
            out.stockSplit(BotProfile.ItemSpec.of(ItemIds.EXPERIENCE_BOTTLE),
                    r.count("profile.sustain.xpBottles", 0, 64), LoadoutBuilder.UTILITY);
        }
        if (caps.cobwebEnabled() && caps.combatEnabled()) {
            out.stockSplit(BotProfile.ItemSpec.of(ItemIds.COBWEB),
                    r.count("profile.sustain.cobwebs", 0, 16), LoadoutBuilder.UTILITY);
        }
        // PvP BOT's cobweb escape routine is not tied to any setting. Its water-bucket branch is a bounded ten-tick
        // routine, so a bucket is always fair game. Ender pearls are deliberately NEVER stocked: with a pearl and
        // no bucket the bot selects the pearl slot, throws it horizontally and clears its in-web flag every single
        // tick while it stands in a cobweb. In cramped places (mineshafts, tunnels) the pearl lands back in or
        // next to the web and the pearl cooldown turns most retries into no-ops, but every retry still switches
        // the selected hotbar slot, which cancels a crossbow charge, a bow draw and every attack until the pearls
        // run out. Without pearls (and without a bucket) it simply fights from the web. Nothing else in PvP BOT
        // uses pearls, so leaving them out costs no behaviour. Inhabitants that already carry some have them
        // removed by the inventory sanitize step (ProfileApplier.removeEnderPearls).
        // Every combat-capable inhabitant carries ONE bucket: many of them end up in a cobweb (mineshafts, cave spiders,
        // the player's own webs) and without a bucket or a pearl PvP BOT prints "[COBWEB] No water bucket or ender pearl
        // found!" every tick and simply fights from the web. With a bucket it runs its bounded ten-tick escape: water is
        // placed at its feet (which removes the web), picked up again on tick 5, and the bot walks back for it if that
        // failed; PvP BOT never refills anything, so the bucket is not topped up later. The coin flip is still DRAWN so
        // every later roll (and a deterministic world's next bots) is unchanged; it only decides for a bot whose
        // world has combat switched off, where the bucket stays optional.
        boolean rolledBucket = r.flag("profile.sustain.waterBucket");
        if (rolledBucket || caps.combatEnabled()) {
            out.stock(BotProfile.ItemSpec.of(ItemIds.WATER_BUCKET), LoadoutBuilder.UTILITY);
        }
        // The draw that used to pick the pearl count is still consumed, so every later roll (vitals, behaviour,
        // the next bots of a deterministic world) is unchanged and only the pearls themselves disappear.
        r.count("profile.sustain.enderPearls", 0, 8);
    }

    /**
     * Potions do not stack, so each bottle is its own stack. Healing potions are common; each buff kind is
     * carried by one bot in four, which keeps a full potion belt from filling the whole inventory while
     * still covering the 0..8 range for every kind.
     */
    private void potions() {
        int healing = r.count("profile.sustain.potion.healing", 0, 8);
        if (healing > 0) {
            String potion = r.pick("profile.sustain.potion.healing.kind", ItemIds.HEALING_POTIONS);
            String item = r.pick("profile.sustain.potion.healing.delivery", HEALING_DELIVERY);
            for (int i = 0; i < healing; i++) {
                out.stock(new BotProfile.ItemSpec(item, 1, Map.of(), 0.0, potion), LoadoutBuilder.SUSTAIN);
            }
        }
        for (Buff buff : Buff.values()) {
            String key = "profile.sustain.potion." + buff.key;
            if (!r.oneIn(key + ".carried", 4)) {
                continue;
            }
            int count = r.count(key, 1, 8);
            String potion = r.pick(key + ".kind", buff.potions);
            for (int i = 0; i < count; i++) {
                out.stock(new BotProfile.ItemSpec(ItemIds.SPLASH_POTION, 1, Map.of(), 0.0, potion), LoadoutBuilder.BUFF);
            }
        }
    }

    /**
     * End crystals with obsidian, or respawn anchors with glowstone. PvP BOT places blocks and detonates
     * them next to its target, which destroys the structure it lives in, so this needs the explicit
     * {@code allowExplosiveKits} opt-in AND the matching PvP BOT switch. Three blank cards go into the deck so
     * an opted-in server still gets mostly ordinary inhabitants (each kit is one card in five, or four).
     */
    private void explosive() {
        if (!options.allowExplosiveKits || !caps.combatEnabled()) {
            return;
        }
        List<Facts.ExplosiveKit> kinds = new ArrayList<>(List.of(
                Facts.ExplosiveKit.NONE, Facts.ExplosiveKit.NONE, Facts.ExplosiveKit.NONE));
        if (caps.crystalPvpEnabled()) {
            kinds.add(Facts.ExplosiveKit.CRYSTAL);
        }
        if (caps.anchorPvpEnabled()) {
            kinds.add(Facts.ExplosiveKit.ANCHOR);
        }
        if (kinds.size() == 3) {
            return;
        }
        explosive = r.pickVarying("profile.explosive.kind", kinds);
        if (explosive == Facts.ExplosiveKit.CRYSTAL) {
            out.stockSplit(BotProfile.ItemSpec.of(ItemIds.END_CRYSTAL),
                    r.count("profile.explosive.crystals", 1, 16), LoadoutBuilder.UTILITY);
            out.stockSplit(BotProfile.ItemSpec.of(ItemIds.OBSIDIAN),
                    r.count("profile.explosive.obsidian", 4, 64), LoadoutBuilder.UTILITY);
        } else if (explosive == Facts.ExplosiveKit.ANCHOR) {
            out.stockSplit(BotProfile.ItemSpec.of(ItemIds.RESPAWN_ANCHOR),
                    r.count("profile.explosive.anchors", 1, 8), LoadoutBuilder.UTILITY);
            out.stockSplit(BotProfile.ItemSpec.of(ItemIds.GLOWSTONE),
                    r.count("profile.explosive.glowstone", 4, 64), LoadoutBuilder.UTILITY);
        }
    }

    /**
     * Best melee weapon first (PvP BOT would move it to slot 0 anyway), then crossbow before bow (PvP BOT
     * prefers the crossbow), skipping slot 1 when the shield has to live there.
     */
    private void layout() {
        List<BotProfile.ItemSpec> hotbar = new ArrayList<>(melee);
        hotbar.sort(Comparator.comparingInt(
                (BotProfile.ItemSpec s) -> PvpBotRanking.weaponScore(s.item(), caps.preferSword())).reversed());
        hotbar.addAll(ranged);
        int index = 0;
        for (BotProfile.ItemSpec spec : hotbar) {
            if (index == 1 && shieldInHotbar) {
                index++;
            }
            out.hotbar(index++, spec);
        }
        if (shieldInHotbar) {
            out.hotbar(1, shield);
        }
    }

    private void enchant(Map<String, Integer> into, String id, String key) {
        int level = r.level(key, ItemIds.maxEnchantmentLevel(id));
        if (level > 0) {
            put(into, id, level);
        }
    }

    /** The only way an enchantment gets onto a rolled item, so a disabled one can never slip in. */
    private void put(Map<String, Integer> into, String id, int level) {
        if (!disabled.contains(id)) {
            into.put(id, level);
        }
    }
}

package dev.spawnbotswrapper.inhabitants.profile;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static dev.spawnbotswrapper.inhabitants.profile.ProfileTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What must hold for EVERY generated profile, whatever the capabilities and options: only vocabulary ids,
 * vanilla stack and enchantment limits, a loadout that fits a player inventory, the slot conventions PvP
 * BOT depends on, and capability gating.
 */
class ProfileInvariantsTest {

    private record Config(String name, GlobalCapabilities caps, InhabitantsConfig.Profiles options) {
    }

    private static List<Config> configs() {
        List<Config> out = new ArrayList<>();
        out.add(new Config("all on, every opt-in", allOn(), everythingOptions()));
        out.add(new Config("upstream defaults", GlobalCapabilities.upstreamDefaults(), defaultOptions()));
        out.add(new Config("all off, every opt-in", allOff(), everythingOptions()));
        for (String flag : capabilityNames()) {
            out.add(new Config("only " + flag + " off", allOnExcept(flag), everythingOptions()));
        }
        InhabitantsConfig.Profiles bare = everythingOptions();
        bare.attributeVariation = false;
        bare.behaviorVariation = false;
        out.add(new Config("no attributes, no behaviour", allOn(), bare));
        return out;
    }

    @Test
    void everyProfileIsWellFormedUnderEveryConfiguration() {
        for (Config c : configs()) {
            for (BotProfile p : profiles(generate(500, c.caps(), c.options(), c.name().hashCode()))) {
                assertWellFormed(p, c);
            }
        }
    }

    private static void assertWellFormed(BotProfile p, Config c) {
        String tag = c.name() + ": ";
        Set<String> items = Set.copyOf(ProfileVocabulary.items());
        Set<String> enchants = Set.copyOf(ProfileVocabulary.enchantments());
        Set<String> potions = Set.copyOf(ProfileVocabulary.potions());
        Set<String> attributes = Set.copyOf(ProfileVocabulary.attributes());

        Set<String> wornSlots = new HashSet<>();
        Set<Integer> hotbarSlots = new HashSet<>();
        int offhand = 0;
        int inventory = 0;
        for (BotProfile.PlacedItem placed : p.loadout().items()) {
            BotProfile.ItemSpec s = placed.spec();
            String where = tag + placed.slot() + "/" + placed.index() + " " + s;

            assertTrue(items.contains(s.item()), where + ": id outside the vocabulary");
            assertTrue(s.count() >= 1 && s.count() <= vanillaMaxStack(s.item()), where + ": bad stack size");
            assertTrue(s.damageFraction() >= 0.0 && s.damageFraction() <= 0.9 + 1e-9, where + ": bad wear");
            if (s.damageFraction() > 0) {
                assertTrue(damageable(s.item()), where + ": wear on an item without durability");
            }

            boolean potionItem = s.item().equals(NS + "splash_potion") || s.item().equals(NS + "potion")
                    || s.item().equals(NS + "tipped_arrow");
            assertEquals(potionItem, s.potion() != null, where + ": potion id iff a potion item");
            if (s.potion() != null) {
                assertTrue(potions.contains(s.potion()), where + ": unknown potion");
            }

            Set<String> allowed = vanillaAllowedEnchantments(s.item());
            int protectionTypes = 0;
            int damageSet = 0;
            for (var e : s.enchantments().entrySet()) {
                assertTrue(enchants.contains(e.getKey()), where + ": unknown enchantment " + e.getKey());
                assertTrue(allowed.contains(e.getKey()), where + ": " + e.getKey() + " does not fit this item");
                int max = VANILLA_MAX_LEVEL.get(e.getKey());
                assertTrue(e.getValue() >= 1 && e.getValue() <= max, where + ": " + e.getKey() + " level " + e.getValue());
                if (ItemIds.PROTECTION_TYPES.contains(e.getKey())) {
                    protectionTypes++;
                }
                if (Set.of(ItemIds.SHARPNESS, ItemIds.IMPALING, ItemIds.DENSITY, ItemIds.BREACH).contains(e.getKey())) {
                    damageSet++;
                }
            }
            assertTrue(protectionTypes <= 1, where + ": protection types are mutually exclusive");
            assertTrue(damageSet <= 1, where + ": sharpness/impaling/density/breach are mutually exclusive");
            assertFalse(s.enchantments().containsKey(ItemIds.INFINITY) && s.enchantments().containsKey(ItemIds.MENDING),
                    where + ": infinity and mending are mutually exclusive");

            switch (placed.slot()) {
                case BotProfile.Slot.HEAD -> {
                    assertTrue(wornSlots.add("head"), where + ": two items in one slot");
                    assertTrue(s.item().endsWith("_helmet"), where);
                }
                case BotProfile.Slot.CHEST -> {
                    assertTrue(wornSlots.add("chest"), where + ": two items in one slot");
                    assertTrue(s.item().endsWith("_chestplate") || s.item().equals(ItemIds.ELYTRA), where);
                }
                case BotProfile.Slot.LEGS -> {
                    assertTrue(wornSlots.add("legs"), where + ": two items in one slot");
                    assertTrue(s.item().endsWith("_leggings"), where);
                }
                case BotProfile.Slot.FEET -> {
                    assertTrue(wornSlots.add("feet"), where + ": two items in one slot");
                    assertTrue(s.item().endsWith("_boots"), where);
                }
                case BotProfile.Slot.OFFHAND -> {
                    offhand++;
                    assertTrue(s.item().equals(ItemIds.TOTEM) || s.item().equals(ItemIds.SHIELD), where);
                }
                case BotProfile.Slot.HOTBAR -> {
                    assertTrue(placed.index() >= 0 && placed.index() <= 8, where);
                    assertTrue(hotbarSlots.add(placed.index()), where + ": hotbar slot used twice");
                }
                case BotProfile.Slot.INVENTORY -> {
                    inventory++;
                    assertEquals(-1, placed.index(), where);
                }
                default -> fail(where + ": unknown slot");
            }
            if (isArmorPiece(s.item().substring(NS.length()))) {
                assertTrue(placed.slot().equals("head") || placed.slot().equals("chest")
                        || placed.slot().equals("legs") || placed.slot().equals("feet"), where + ": spare armor");
            }
        }
        assertTrue(offhand <= 1, tag + "two offhand items");
        assertTrue(hotbarSlots.size() <= 9, tag + "hotbar overflow");
        assertTrue(inventory <= LoadoutBuilder.MAIN_SIZE, tag + "main inventory overflow: " + inventory);

        BotProfile.Vitals v = p.vitals();
        assertTrue(v.healthFraction() >= 0.35 - 1e-9 && v.healthFraction() <= 1.0, tag + v);
        if (c.caps().autoEatEnabled()) {
            assertTrue(v.foodLevel() >= 6 && v.foodLevel() <= 20, tag + v);
        } else {
            assertEquals(20, v.foodLevel(), tag + "no eating logic to exercise: start fed");
        }
        for (var e : v.attributes().entrySet()) {
            assertTrue(attributes.contains(e.getKey()), tag + e.getKey());
            assertTrue(Set.of(BotProfile.Op.ADD_VALUE, BotProfile.Op.ADD_MULTIPLIED_BASE).contains(e.getValue().operation()));
        }
    }

    @Test
    void theBestMeleeWeaponIsInHotbarSlotZero() {
        int checked = 0;
        for (BotProfile p : profiles(generate(SAMPLES, allOn(), everythingOptions(), 5))) {
            List<BotProfile.ItemSpec> melee = new ArrayList<>();
            for (BotProfile.ItemSpec s : specs(p)) {
                String path = s.item().substring(NS.length());
                if (path.endsWith("_sword") || path.endsWith("_axe") || path.endsWith("_spear")
                        || path.equals("mace") || path.equals("trident")) {
                    melee.add(s);
                }
            }
            if (melee.isEmpty()) {
                continue;
            }
            BotProfile.ItemSpec slotZero = hotbar(p, 0);
            assertNotNull(slotZero, "a melee weapon must be selectable from hotbar slot 0");
            int best = melee.stream().mapToInt(s -> oracleWeaponScore(s.item())).max().orElseThrow();
            assertEquals(best, oracleWeaponScore(slotZero.item()), "slot 0 must hold the weapon PvP BOT ranks best: " + melee);
            assertTrue(melee.contains(slotZero));
            checked++;
        }
        assertTrue(checked > SAMPLES / 2);
    }

    @Test
    void rangedWeaponsAndMeleeWeaponsSitInTheHotbar() {
        for (BotProfile p : profiles(generate(SAMPLES, allOn(), everythingOptions(), 6))) {
            for (BotProfile.PlacedItem placed : p.loadout().items()) {
                String path = placed.spec().item().substring(NS.length());
                boolean weapon = path.equals("bow") || path.equals("crossbow") || path.equals("mace")
                        || path.equals("trident") || path.endsWith("_sword") || path.endsWith("_axe")
                        || path.endsWith("_spear");
                if (weapon) {
                    assertEquals(BotProfile.Slot.HOTBAR, placed.slot(), path + " is only selected from the hotbar");
                }
            }
        }
    }

    @Test
    void rangedOnlyBotsHoldTheRangedWeaponInSlotZero() {
        int checked = 0;
        for (BotProfile p : profiles(generate(SAMPLES, allOn(), everythingOptions(), 7))) {
            boolean melee = has(p, i -> i.endsWith("_sword") || i.endsWith("_axe") || i.endsWith("_spear")
                    || i.equals(NS + "mace") || i.equals(NS + "trident"));
            boolean ranged = has(p, i -> i.equals(NS + "bow") || i.equals(NS + "crossbow"));
            if (!melee && ranged) {
                assertNotNull(hotbar(p, 0));
                assertTrue(hotbar(p, 0).item().equals(NS + "bow") || hotbar(p, 0).item().equals(NS + "crossbow"));
                checked++;
            }
        }
        assertTrue(checked > 100);
    }

    @Test
    void totemsOwnTheOffhandAndTheShieldMovesToHotbarSlotOne() {
        int totemAndShield = 0;
        int shieldOffhand = 0;
        for (BotProfile p : profiles(generate(SAMPLES, allOn(), everythingOptions(), 8))) {
            int totems = countOf(p, ItemIds.TOTEM);
            BotProfile.ItemSpec offhand = null;
            for (BotProfile.PlacedItem placed : p.loadout().items()) {
                if (BotProfile.Slot.OFFHAND.equals(placed.slot())) {
                    offhand = placed.spec();
                }
            }
            boolean shield = has(p, i -> i.equals(NS + "shield"));
            if (totems >= 1) {
                assertNotNull(offhand);
                assertEquals(ItemIds.TOTEM, offhand.item(), "a totem always takes the offhand");
                if (shield) {
                    assertNotNull(hotbar(p, 1));
                    assertEquals(ItemIds.SHIELD, hotbar(p, 1).item(), "with a totem in the offhand the shield lives in hotbar 1");
                    totemAndShield++;
                }
            } else if (shield) {
                assertNotNull(offhand);
                assertEquals(ItemIds.SHIELD, offhand.item());
                shieldOffhand++;
            } else {
                assertNull(offhand);
            }
        }
        assertTrue(totemAndShield > 100 && shieldOffhand > 100);
    }

    @Test
    void foodSitsInPvpBotsScratchSlot() {
        int checked = 0;
        for (BotProfile p : profiles(generate(SAMPLES, allOn(), everythingOptions(), 9))) {
            int food = countOf(p, FOOD_IDS::contains);
            if (food > 0) {
                assertNotNull(hotbar(p, 8));
                assertTrue(FOOD_IDS.contains(hotbar(p, 8).item()));
                assertEquals(food, hotbar(p, 8).count());
                checked++;
            } else if (hotbar(p, 8) != null) {
                String path = hotbar(p, 8).item().substring(NS.length());
                assertFalse(path.endsWith("_sword") || path.endsWith("_axe") || path.equals("bow")
                        || path.equals("crossbow") || path.equals("shield"), "only spill-over may use slot 8: " + path);
            }
        }
        assertTrue(checked > SAMPLES / 2);
    }

    @Test
    void hotbarHoldsOnlyPlannedItemsUnlessTheMainInventoryIsFull() {
        // Planned hotbar contents: weapons, the ranged weapons, the shield in slot 1 (behind a totem) and the
        // food stack in slot 8. Anything else in the hotbar is overflow, which may only exist when the 27 main
        // slots are already taken.
        int overflowBots = 0;
        for (BotProfile p : profiles(generate(6000, allOn(), everythingOptions(), 25))) {
            int overflow = 0;
            int main = 0;
            for (BotProfile.PlacedItem placed : p.loadout().items()) {
                String path = placed.spec().item().substring(NS.length());
                if (BotProfile.Slot.INVENTORY.equals(placed.slot())) {
                    main++;
                } else if (BotProfile.Slot.HOTBAR.equals(placed.slot())) {
                    boolean planned = path.endsWith("_sword") || path.endsWith("_axe") || path.endsWith("_spear")
                            || path.equals("mace") || path.equals("trident") || path.equals("bow")
                            || path.equals("crossbow") || (path.equals("shield") && placed.index() == 1)
                            || (placed.index() == 8 && FOOD_IDS.contains(placed.spec().item()));
                    if (!planned) {
                        overflow++;
                    }
                }
            }
            if (overflow > 0) {
                assertEquals(LoadoutBuilder.MAIN_SIZE, main, "overflow into the hotbar before the main inventory is full");
                overflowBots++;
            }
        }
        assertTrue(overflowBots > 0, "the spill path should be exercised by heavy loadouts");
    }

    @Test
    void armorIsWornNeverCarriedAsSpare() {
        // PvP BOT swaps in any better piece it finds in the inventory; spares would make it fight the loadout.
        for (BotProfile p : profiles(generate(SAMPLES, allOn(), everythingOptions(), 10))) {
            for (BotProfile.PlacedItem placed : p.loadout().items()) {
                String path = placed.spec().item().substring(NS.length());
                if (isArmorPiece(path) || path.equals("elytra")) {
                    assertTrue(Set.of("head", "chest", "legs", "feet").contains(placed.slot()), path + " in " + placed.slot());
                }
            }
        }
    }

    @Test
    void weaponClassesAreNotMixedInWaysPvpBotWouldFightOver() {
        for (ProfileGenerator.Generation g : generate(SAMPLES, allOn(), everythingOptions(), 11)) {
            BotProfile p = g.profile();
            int melee = 0;
            for (BotProfile.ItemSpec s : specs(p)) {
                String path = s.item().substring(NS.length());
                if (path.endsWith("_sword") || path.endsWith("_axe") || path.endsWith("_spear")
                        || path.equals("mace") || path.equals("trident")) {
                    melee++;
                }
            }
            switch (g.facts().meleeKind()) {
                case NONE -> assertEquals(0, melee);
                case SWORD_AND_AXE -> assertEquals(2, melee);
                default -> assertEquals(1, melee, g.facts().meleeKind().toString());
            }
            if (g.facts().meleeKind() != Facts.MeleeKind.MACE) {
                assertEquals(0, countOf(p, ItemIds.WIND_CHARGE), "wind charges only accompany a mace");
            }
        }
    }

    @Test
    void arrowCountsMatchTheFactsAndNeverAppearWithoutABow() {
        for (ProfileGenerator.Generation g : generate(SAMPLES, allOn(), everythingOptions(), 12)) {
            int arrows = countOf(g.profile(), ProfileTestSupport::isArrowItem);
            assertEquals(g.facts().arrows(), arrows);
            if (!g.facts().hasRanged()) {
                assertEquals(0, arrows, "arrows without a bow are dead weight");
            }
            assertTrue(arrows <= 32);
        }
    }

    @Test
    void inventoryOverflowIsAnExtremeRarity() {
        int trimmedBots = 0;
        int total = 0;
        for (ProfileGenerator.Generation g : generate(6000, allOn(), everythingOptions(), 13)) {
            total++;
            if (g.facts().trimmedStacks() > 0) {
                trimmedBots++;
            }
        }
        assertTrue(trimmedBots <= total * 0.005, trimmedBots + " of " + total + " bots lost stacks to a full inventory");
    }

    private static boolean isRanged(String i) {
        return i.equals(NS + "bow") || i.equals(NS + "crossbow") || isArrowItem(i);
    }

    private static boolean isSpear(String i) {
        return i.endsWith("_spear");
    }

    private static boolean isFoodOrApple(String i) {
        return FOOD_IDS.contains(i) || i.equals(NS + "golden_apple") || i.equals(NS + "enchanted_golden_apple");
    }

    private static boolean isPotion(String i) {
        return i.equals(NS + "splash_potion") || i.equals(NS + "potion");
    }

    /** With {@code flag} off none of the items appear; with everything on they do (so the check is not vacuous). */
    private static void assertGatedBy(String flag, Predicate<String> items) {
        List<BotProfile> without = profiles(generate(1500, allOnExcept(flag), everythingOptions(), flag.hashCode()));
        for (BotProfile p : without) {
            Set<String> present = new HashSet<>();
            specs(p).forEach(s -> {
                if (items.test(s.item())) {
                    present.add(s.item());
                }
            });
            assertTrue(present.isEmpty(), flag + "=false but the bot carries " + present);
        }
        List<BotProfile> with = profiles(generate(1500, allOn(), everythingOptions(), flag.hashCode()));
        assertTrue(with.stream().anyMatch(p -> has(p, items)), flag + "=true never produced its items: check is vacuous");
    }

    @Test
    void rangedGearNeedsRangedEnabled() {
        assertGatedBy("rangedEnabled", ProfileInvariantsTest::isRanged);
    }

    @Test
    void maceAndWindChargesNeedMaceEnabled() {
        assertGatedBy("maceEnabled", i -> i.equals(NS + "mace") || i.equals(NS + "wind_charge"));
    }

    @Test
    void spearsNeedSpearEnabled() {
        assertGatedBy("spearEnabled", ProfileInvariantsTest::isSpear);
    }

    @Test
    void crystalKitNeedsCrystalPvpEnabled() {
        assertGatedBy("crystalPvpEnabled", i -> i.equals(NS + "end_crystal") || i.equals(NS + "obsidian"));
    }

    @Test
    void anchorKitNeedsAnchorPvpEnabled() {
        assertGatedBy("anchorPvpEnabled", i -> i.equals(NS + "respawn_anchor") || i.equals(NS + "glowstone"));
    }

    @Test
    void cobwebsNeedCobwebEnabled() {
        assertGatedBy("cobwebEnabled", i -> i.equals(NS + "cobweb"));
    }

    @Test
    void totemsNeedAutoTotemEnabled() {
        assertGatedBy("autoTotemEnabled", i -> i.equals(NS + "totem_of_undying"));
    }

    @Test
    void shieldsNeedAutoShieldEnabled() {
        assertGatedBy("autoShieldEnabled", i -> i.equals(NS + "shield"));
    }

    @Test
    void foodNeedsAutoEatEnabled() {
        assertGatedBy("autoEatEnabled", ProfileInvariantsTest::isFoodOrApple);
        boolean hungryBots = false;
        for (BotProfile p : profiles(generate(500, allOn(), everythingOptions()))) {
            hungryBots |= p.vitals().foodLevel() < 20;
        }
        assertTrue(hungryBots, "with eating on, some bots start hungry so the eat logic is exercised");
    }

    @Test
    void potionsNeedAutoPotionEnabled() {
        assertGatedBy("autoPotionEnabled", ProfileInvariantsTest::isPotion);
    }

    @Test
    void xpBottlesAndMendingNeedAutoMendEnabled() {
        assertGatedBy("autoMendEnabled", i -> i.equals(NS + "experience_bottle"));
        for (BotProfile p : profiles(generate(1500, allOnExcept("autoMendEnabled"), everythingOptions()))) {
            for (BotProfile.ItemSpec s : specs(p)) {
                assertFalse(s.enchantments().containsKey(ItemIds.MENDING), "mending only matters with auto-mend");
            }
        }
        boolean mending = false;
        for (BotProfile p : profiles(generate(500, allOn(), everythingOptions()))) {
            mending |= specs(p).stream().anyMatch(s -> s.enchantments().containsKey(ItemIds.MENDING));
        }
        assertTrue(mending);
    }

    @Test
    void aShieldNeverAccompaniesAnOffhandTotemWhenTotemPriorityIsOff() {
        // Verified upstream behaviour: with totemPriority off, equipping the shield overwrites the offhand
        // outright instead of routing through hotbar slot 1, destroying whatever was there - including an
        // emergency totem. A profile that hands out both would defeat the very reason autoTotemEnabled
        // exists the first time the bot raises its shield.
        for (BotProfile p : profiles(generate(2000, allOnExcept("totemPriority"), everythingOptions()))) {
            boolean offhandTotem = p.loadout().items().stream()
                    .anyMatch(i -> BotProfile.Slot.OFFHAND.equals(i.slot()) && i.spec() != null
                            && ItemIds.TOTEM.equals(i.spec().item()));
            boolean hasShield = specs(p).stream().anyMatch(s -> ItemIds.SHIELD.equals(s.item()));
            assertFalse(offhandTotem && hasShield,
                    "profile combines an offhand totem with a shield although totemPriority is off: " + p);
        }
        // the combination must still be reachable once totemPriority is back on, so this is really gated on
        // the capability and not accidentally disabled altogether
        boolean sawBoth = false;
        for (BotProfile p : profiles(generate(2000, allOn(), everythingOptions()))) {
            boolean offhandTotem = p.loadout().items().stream()
                    .anyMatch(i -> BotProfile.Slot.OFFHAND.equals(i.slot()) && i.spec() != null
                            && ItemIds.TOTEM.equals(i.spec().item()));
            sawBoth |= offhandTotem && specs(p).stream().anyMatch(s -> ItemIds.SHIELD.equals(s.item()));
        }
        assertTrue(sawBoth, "with totemPriority on, some bot should still carry both");
    }

    /**
     * The cobweb escape needs a water bucket (PvP BOT never refills one, and pearls are never stocked), so every
     * combat-capable inhabitant carries exactly one; with combat switched off globally nobody fights, and the old coin
     * flip decides. The flip is still drawn, so switching combat off changes nothing else a profile rolls.
     */
    @Test
    void everyCombatCapableInhabitantCarriesExactlyOneWaterBucket() {
        for (BotProfile p : profiles(generate(600, allOn(), everythingOptions(), 21))) {
            long buckets = specs(p).stream().filter(s -> (NS + "water_bucket").equals(s.item())).count();
            assertEquals(1, buckets, "exactly one bucket");
        }
        long without = profiles(generate(600, allOnExcept("combatEnabled"), everythingOptions(), 21)).stream()
                .filter(p -> specs(p).stream().noneMatch(s -> (NS + "water_bucket").equals(s.item()))).count();
        assertTrue(without > 150 && without < 450, "with combat off the bucket is a coin flip again: " + without + " of 600 have none");
    }

    @Test
    void everyCombatGearNeedsCombatEnabled() {
        assertGatedBy("combatEnabled", i -> isRanged(i) || i.equals(NS + "mace") || i.equals(NS + "wind_charge")
                || isSpear(i) || i.equals(NS + "end_crystal") || i.equals(NS + "obsidian")
                || i.equals(NS + "respawn_anchor") || i.equals(NS + "glowstone") || i.equals(NS + "cobweb"));
    }

    @Test
    void flagsWithoutAnyItemFootprintDoNotChangeWhatIsGenerated() {
        // These switches change how PvP BOT uses a loadout, not which items make sense.
        for (String flag : List.of("autoEquipArmor", "autoEquipWeapon", "shieldBreakEnabled", "retreatEnabled",
                "botsRelogs", "botLeaveOnDeath", "clearOnRemove", "autoTargetEnabled")) {
            List<BotProfile> on = profiles(generate(200, allOn(), everythingOptions(), 1));
            List<BotProfile> off = profiles(generate(200, allOnExcept(flag), everythingOptions(), 1));
            assertEquals(on, off, flag);
        }
    }

    @Test
    void whenEverythingIsSwitchedOffArmorAndMeleeRemainAndNothingBreaks() {
        List<BotProfile> all = profiles(generate(800, allOff(), everythingOptions(), 14));
        Set<String> items = allItemIds(all);
        assertTrue(items.stream().anyMatch(i -> i.endsWith("_chestplate")), "armor needs no capability");
        assertTrue(items.stream().anyMatch(i -> i.endsWith("_sword")), "plain melee weapons need no capability");
        assertTrue(items.contains(NS + "water_bucket"), "the cobweb escape kit is ungated");
        assertFalse(items.contains(NS + "ender_pearl"), "ender pearls are never stocked (they make the cobweb escape loop cancel attacks)");
        for (String forbidden : List.of("bow", "crossbow", "arrow", "mace", "wind_charge", "shield", "totem_of_undying",
                "splash_potion", "potion", "experience_bottle", "cobweb", "end_crystal", "respawn_anchor",
                "golden_apple", "cooked_beef")) {
            assertFalse(items.contains(NS + forbidden), forbidden);
        }
        assertFalse(items.stream().anyMatch(ProfileInvariantsTest::isSpear));
    }

    private static final Set<String> EXPLOSIVE = Set.of(NS + "end_crystal", NS + "obsidian", NS + "respawn_anchor",
            NS + "glowstone");

    @Test
    void explosiveKitsAndElytraNeverAppearWithDefaultOptions() {
        for (GlobalCapabilities caps : List.of(allOn(), GlobalCapabilities.upstreamDefaults())) {
            for (BotProfile p : profiles(generate(2500, caps, defaultOptions(), 15))) {
                for (BotProfile.ItemSpec s : specs(p)) {
                    assertFalse(EXPLOSIVE.contains(s.item()), s.item());
                    assertNotEquals(ItemIds.ELYTRA, s.item());
                    assertNotEquals(ItemIds.FIREWORK_ROCKET, s.item());
                }
            }
        }
    }

    @Test
    void explosiveKitsAppearWhenAllowed() {
        InhabitantsConfig.Profiles o = defaultOptions();
        o.allowExplosiveKits = true;
        Set<String> seen = allItemIds(profiles(generate(2000, allOn(), o, 16)));
        assertTrue(seen.containsAll(EXPLOSIVE), seen.toString());
        assertFalse(seen.contains(ItemIds.ELYTRA), "elytra is a separate opt-in");
    }

    @Test
    void elytraAppearsWhenAllowed() {
        InhabitantsConfig.Profiles o = defaultOptions();
        o.allowElytra = true;
        Set<String> seen = allItemIds(profiles(generate(1000, allOn(), o, 17)));
        assertTrue(seen.contains(ItemIds.ELYTRA));
        assertTrue(seen.contains(ItemIds.FIREWORK_ROCKET));
        assertTrue(seen.stream().noneMatch(EXPLOSIVE::contains), "explosive kits are a separate opt-in");
    }

    @Test
    void anExplosiveKitNeedsItsOwnPvpBotSwitch() {
        InhabitantsConfig.Profiles o = defaultOptions();
        o.allowExplosiveKits = true;

        Set<String> noCrystals = allItemIds(profiles(generate(1500, allOnExcept("crystalPvpEnabled"), o, 18)));
        assertFalse(noCrystals.contains(NS + "end_crystal") || noCrystals.contains(NS + "obsidian"));
        assertTrue(noCrystals.contains(NS + "respawn_anchor"));

        Set<String> noAnchors = allItemIds(profiles(generate(1500, allOnExcept("anchorPvpEnabled"), o, 19)));
        assertFalse(noAnchors.contains(NS + "respawn_anchor") || noAnchors.contains(NS + "glowstone"));
        assertTrue(noAnchors.contains(NS + "end_crystal"));

        Set<String> neither = allItemIds(profiles(generate(1500, allOnExcept("crystalPvpEnabled", "anchorPvpEnabled"), o, 20)));
        assertTrue(neither.stream().noneMatch(EXPLOSIVE::contains));
    }

    @Test
    void explosiveKitsAreCompleteOrAbsent() {
        InhabitantsConfig.Profiles o = everythingOptions();
        for (BotProfile p : profiles(generate(2500, allOn(), o, 21))) {
            boolean crystals = has(p, i -> i.equals(NS + "end_crystal"));
            boolean obsidian = has(p, i -> i.equals(NS + "obsidian"));
            boolean anchors = has(p, i -> i.equals(NS + "respawn_anchor"));
            boolean glowstone = has(p, i -> i.equals(NS + "glowstone"));
            assertEquals(crystals, obsidian, "PvP BOT needs BOTH obsidian and crystals");
            assertEquals(anchors, glowstone, "PvP BOT needs BOTH an anchor and glowstone");
            assertFalse(crystals && anchors, "one explosive kit per bot");
        }
    }

    @Test
    void elytraReplacesTheChestplate() {
        int checked = 0;
        for (BotProfile p : profiles(generate(1500, allOn(), everythingOptions(), 22))) {
            BotProfile.ItemSpec chest = worn(p, BotProfile.Slot.CHEST);
            if (chest != null && chest.item().equals(ItemIds.ELYTRA)) {
                assertTrue(chest.enchantments().isEmpty());
                assertEquals(0, countOf(p, i -> i.endsWith("_chestplate")));
                checked++;
            }
        }
        assertTrue(checked > 100);
    }

    @Test
    void newLoadoutsNeverContainEnderPearls() {
        // PvP BOT's cobweb escape throws pearls in a per-tick loop that cancels crossbow charges and attacks.
        for (GlobalCapabilities caps : List.of(allOn(), allOff(), GlobalCapabilities.upstreamDefaults())) {
            for (BotProfile p : profiles(generate(SAMPLES, caps, everythingOptions(), 31))) {
                for (BotProfile.PlacedItem item : p.loadout().items()) {
                    assertNotEquals(NS + "ender_pearl", item.spec().item(), p.archetype());
                }
            }
        }
    }

    @Test
    void archetypeLabelsAreFromTheKnownSetAndVaried() {
        Set<String> seen = new HashSet<>();
        for (ProfileGenerator.Generation g : generate(SAMPLES, allOn(), everythingOptions(), 23)) {
            assertTrue(Archetypes.ALL.contains(g.profile().archetype()), g.profile().archetype());
            seen.add(g.profile().archetype());
        }
        assertTrue(seen.size() >= 12, seen.toString());
    }

    @Test
    void archetypeFollowsTheFacets() {
        for (ProfileGenerator.Generation g : generate(SAMPLES, allOn(), everythingOptions(), 24)) {
            BotProfile p = g.profile();
            Facts f = g.facts();
            String label = p.archetype();
            if (f.explosive() != Facts.ExplosiveKit.NONE) {
                assertEquals(Archetypes.DEMOLITIONIST, label);
            } else if (f.meleeKind() == Facts.MeleeKind.MACE) {
                assertTrue(label.equals(Archetypes.SMASHER) || label.equals(Archetypes.SKYFARER), label);
            } else if (f.hasRanged() && f.arrows() > 0) {
                assertTrue(label.equals(Archetypes.ARCHER) || label.equals(Archetypes.SKIRMISHER)
                        || label.equals(Archetypes.LANCER) || label.equals(Archetypes.HARPOONER), label);
            }
        }
    }
}

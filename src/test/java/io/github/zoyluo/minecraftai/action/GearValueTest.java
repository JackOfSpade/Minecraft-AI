package io.github.zoyluo.minecraftai.action;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The numbers behind worst-first gear ({@link GearValue.Core}) and the source-level wiring of the worst-first policy. */
class GearValueTest {

    private static double tool(String path, int maxDurability, double... enchants) {
        double sum = 0.0D;
        for (double enchant : enchants) {
            sum += enchant;
        }
        return GearValue.Core.materialValue(path, maxDurability) + GearValue.Core.capTotal(sum);
    }

    @Test
    void materialOrderIsGoldWoodStoneCopperIronDiamondNetherite() {
        double gold = GearValue.Core.materialValue("golden_pickaxe", 32);
        double wood = GearValue.Core.materialValue("wooden_pickaxe", 59);
        double stone = GearValue.Core.materialValue("stone_pickaxe", 131);
        double copper = GearValue.Core.materialValue("copper_pickaxe", 190);
        double iron = GearValue.Core.materialValue("iron_pickaxe", 250);
        double diamond = GearValue.Core.materialValue("diamond_pickaxe", 1561);
        double netherite = GearValue.Core.materialValue("netherite_pickaxe", 2031);
        assertTrue(gold < wood && wood < stone && stone < copper && copper < iron && iron < diamond && diamond < netherite);
        assertEquals(1.0D, gold);
        assertEquals(1.1D, wood);
        assertEquals(2.0D, stone);
        assertEquals(2.2D, copper);
        assertEquals(3.0D, iron);
        assertEquals(4.0D, diamond);
        assertEquals(5.0D, netherite);
    }

    @Test
    void anUnknownMaterialIsBucketedByMaximumDurability() {
        assertTrue(GearValue.Core.materialValue("modded_pickaxe", 60) < GearValue.Core.materialValue("modded_pickaxe", 250));
        assertTrue(GearValue.Core.materialValue("modded_pickaxe", 250) < GearValue.Core.materialValue("modded_pickaxe", 900));
        assertTrue(GearValue.Core.materialValue("modded_pickaxe", 900) < GearValue.Core.materialValue("modded_pickaxe", 3000));
        assertEquals(GearValue.Core.materialValue("shears", 238), GearValue.Core.materialValue("modded_axe", 250));
    }

    @Test
    void oneEnchantmentIsCappedSoSharpnessVOnStoneStaysBelowPlainIron() {
        double sharpnessFiveStone = tool("stone_sword", 131, GearValue.Core.enchantValue("sharpness", 5));
        double plainIron = tool("iron_sword", 250);
        assertTrue(sharpnessFiveStone < plainIron, sharpnessFiveStone + " must stay below " + plainIron);
        // A huge level never adds more than the single-enchantment cap.
        assertEquals(GearValue.Core.ENCHANT_SINGLE_CAP, GearValue.Core.enchantValue("efficiency", 50));
        assertEquals(0.12D * 5, GearValue.Core.enchantValue("efficiency", 5), 1.0E-9D);
        assertEquals(0.4D, GearValue.Core.enchantValue("mending", 1));
        assertEquals(0.0D, GearValue.Core.enchantValue("binding_curse", 1));
    }

    @Test
    void efficiencyVOnAWoodenPickStillCostsLessThanAPlainStoneOne() {
        double efficient = tool("wooden_pickaxe", 59, GearValue.Core.enchantValue("efficiency", 5));
        assertTrue(efficient < tool("stone_pickaxe", 131));
    }

    @Test
    void theTotalOfAllEnchantmentsIsCapped() {
        double all = GearValue.Core.enchantValue("efficiency", 5) + GearValue.Core.enchantValue("unbreaking", 3)
                + GearValue.Core.enchantValue("mending", 1) + GearValue.Core.enchantValue("fortune", 3);
        assertTrue(all > GearValue.Core.ENCHANT_TOTAL_CAP);
        assertEquals(GearValue.Core.ENCHANT_TOTAL_CAP, GearValue.Core.capTotal(all));
        assertEquals(0.0D, GearValue.Core.capTotal(-1.0D));
    }

    @Test
    void armorPointsAddToughnessKnockbackResistanceAndProtectionLevels() {
        assertEquals(8.0D + 0.25D * 2.0D, GearValue.Core.armorPoints(8.0D, 2.0D, 0.0D, 0), 1.0E-9D);
        assertEquals(8.0D + 0.25D * 3.0D + 2.0D * 0.1D, GearValue.Core.armorPoints(8.0D, 3.0D, 0.1D, 0), 1.0E-9D);
        // Leather chestplate (3) with Protection IV is worth more than an iron one (6): enchantments add value.
        assertTrue(GearValue.Core.armorPoints(3.0D, 0.0D, 0.0D, 4) > GearValue.Core.armorPoints(6.0D, 0.0D, 0.0D, 0));
        assertTrue(GearValue.Core.isProtection("blast_protection") && !GearValue.Core.isProtection("thorns"));
    }

    @Test
    void theMoreWornItemGoesFirstAtEqualValueAndTheLowerValueAlwaysWins() {
        assertTrue(GearValue.Core.compare(2.0D, 50, 2.0D, 100) < 0);
        assertTrue(GearValue.Core.compare(2.0D, 100, 2.0D, 50) > 0);
        assertEquals(0, GearValue.Core.compare(2.0D, 100, 2.0D, 100));
        assertTrue(GearValue.Core.compare(1.1D, 59, 2.0D, 1) < 0, "value beats wear");
    }

    @Test
    void nonToolsGoBestFirstAndTheMoreWornOfTwoEqualOnesGoesFirst() {
        // Best-first: the higher value goes first, however worn the lower one is.
        assertTrue(GearValue.Core.compareBestFirst(4.0D, 1, 2.0D, 131) < 0, "a diamond piece at its last use still goes before a fresh stone one");
        assertTrue(GearValue.Core.compareBestFirst(2.0D, 131, 4.0D, 1) > 0);
        assertEquals(0, GearValue.Core.compareBestFirst(3.0D, 100, 3.0D, 100));
        // Equal value: the more worn first (used up, never set aside for a fresh one).
        assertTrue(GearValue.Core.compareBestFirst(3.0D, 10, 3.0D, 200) < 0);
        assertTrue(GearValue.Core.compareBestFirst(3.0D, 200, 3.0D, 10) > 0);
    }


    // ------------------------------------------------------------------------------------------------------------------------
    // Source contracts of the worst-first wiring (the behaviour itself is proven by GearWorstFirstGameTests).
    // ------------------------------------------------------------------------------------------------------------------------

    private static String read(String relative) throws IOException {
        return Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai").resolve(relative));
    }

    @Test
    void combatAndTheBackgroundTickUseTheAutoEquipEntryPointsWhileExplicitCommandsStayBest() throws IOException {
        String combat = read("task/CombatCore.java");
        assertTrue(combat.contains("EquipAction.autoEquipArmor(bot);\n        ensureMeleeWeapon(bot);"));
        assertTrue(combat.contains("EquipAction.equipWeaponForContext(bot);"));
        assertFalse(combat.contains("equipBestArmor") || combat.contains("equipBestWeapon"));
        assertTrue(read("task/BotTickCoordinator.java").contains("EquipAction.autoEquipArmor(bot);"));
        assertTrue(combat.contains("OffhandPolicy.apply(bot);") && read("task/BotTickCoordinator.java").contains("OffhandPolicy.apply(bot);"),
                "the offhand policy (best shield, else a totem) runs next to the armor pass in both places");
        assertTrue(read("task/DescendToYTask.java").contains("EquipAction.equipBestArmor(bot);"),
                "the armor-up before a descent is an explicit command and stays best-first");
        assertTrue(read("brain/ToolRegistry.java").contains("EquipAction.equipBestArmor(bot)"),
                "the equip_armor tool stays best-first");
    }

    @Test
    void thereIsNoEscalationAndNoHumanArmorProvenance() throws IOException {
        String equip = read("action/EquipAction.java");
        String gear = read("action/GearValue.java");
        for (String source : new String[] {equip, gear, read("action/ToolSelector.java")}) {
            assertFalse(source.contains("GearPosture"), "no gear posture / escalation latch");
            assertFalse(source.contains("ArmorProvenance"));
            assertFalse(source.contains("worstFirstInMissions") || source.contains("escalateInDangerousPlaces"));
        }
        assertFalse(Files.exists(Path.of("src/main/java/io/github/zoyluo/minecraftai/action/GearPosture.java")));
        assertFalse(Files.exists(Path.of("src/main/java/io/github/zoyluo/minecraftai/action/ArmorProvenance.java")));
    }

    @Test
    void theMiningChannelHasNoStoneFloorWhenWorstFirst() throws IOException {
        String selector = read("action/ToolSelector.java");
        assertTrue(selector.contains("int minimumTier = worstFirst ? requiredTier : channelMinimumTier(requiredTier);"));
        // The request for a missing tool (requiredMiningChannelTool) is unchanged: a bot that owns no pick is still sent for a stone one.
        assertTrue(selector.contains("int tier = channelMinimumTier(ToolTier.requiredPickaxeTier(state.getBlock()));"));
    }

    @Test
    void nonToolsAreBestFirstAndToolsStayWorstFirst() throws IOException {
        String equip = read("action/EquipAction.java");
        // Weapons, armor, shields, bows and crossbows never read the worst-first switch: it is for tools only.
        // The one read of the switch left is the arrow (ammunition) preference: plain arrows before tipped ones.
        assertEquals(equip.indexOf("worstFirstEnabled()"), equip.lastIndexOf("worstFirstEnabled()"), "EquipAction (non-tools) must not depend on behaviour.gear.worstFirst");
        assertTrue(equip.contains("boolean plainOnly = GearValue.worstFirstEnabled() && carriesPlainArrow(bot);"));
        assertTrue(equip.contains("GearValue.Core.compareBestFirst("), "armor and shields are ordered best-first");
        assertTrue(equip.contains("betterRangedBefore(") && !equip.contains("cheaperRangedBefore("), "ranged weapons are ordered best-first");
        assertTrue(equip.contains("public static OptionalInt equipWeaponForContext(AIPlayerEntity bot) {\n        return equipBestWeapon(bot);"),
                "the melee entry point is the best weapon");
        // The worst-first weapon machinery and the adequacy rule are gone.
        assertFalse(equip.contains("adequateWeaponSlot") || equip.contains("ADEQUATE_") || equip.contains("WEAPON_LATCH")
                || equip.contains("cheapest("), "no worst-first weapon machinery any more");
        // Tools keep the worst-first order.
        assertTrue(read("action/ToolSelector.java").contains("GearValue.worstFirstEnabled()"));
    }

    @Test
    void aWornWeaponArmorOrBowIsUsedUntilItBreaks() throws IOException {
        String equip = read("action/EquipAction.java");
        // A two-use sword is a qualified weapon against a zombie: only a broken one (zero uses left) is out.
        assertTrue(equip.contains("private static final int MIN_MELEE_RAW_DURABILITY = 1;"));
        assertFalse(equip.contains("ADEQUATE_SPARE_USES"), "no spare-uses rule: a two-use sword is used against a zombie");
        // A worn bow, crossbow, shield or armor piece is never ordered behind a fresh one.
        assertFalse(equip.contains("remaining(a) <= 1") || equip.contains("armorNearlyBroken") || equip.contains("GearValue.remaining(a) <= 1"),
                "no durability skip for a bow, crossbow, shield or armor piece");
        assertFalse(read("action/GearValue.java").contains("armorNearlyBroken"));
        assertFalse(read("action/ToolSelector.java").contains("return 0.001F; // About to break"),
                "a worn tool is scored like a fresh one");
    }

    @Test
    void theDangerWatcherResupplyNeedsASingleUseLeftNotAWornTool() throws IOException {
        String watcher = read("task/DangerWatcher.java");
        assertFalse(watcher.contains("max * 0.10D"), "no ten-percent wear rule in the danger layer");
        assertTrue(watcher.contains("ItemStackUtil.isNearlyBroken(held)"));
    }

    @Test
    void theBotTickRunsTheDurabilityWarningsBeforeAnyTaskLayerCanConsumeTheTick() throws IOException {
        String tick = read("task/BotTickCoordinator.java");
        int warn = tick.indexOf("DurabilityWarnings.tickBot(bot);");
        assertTrue(warn > 0, "the warning pass is wired");
        assertTrue(warn < tick.indexOf("NavSafetyNet.INSTANCE.tickBot(server, bot)"), "it runs before the safety net can `continue` the tick");
        int equip = tick.indexOf("EquipAction.autoEquipArmor(bot);");
        assertTrue(equip > warn && equip < tick.indexOf("NavSafetyNet.INSTANCE.tickBot(server, bot)"),
                "the armor and offhand pass runs next to the warnings, before the safety net, so a lava or drowning rescue does not skip it");
        String warnings = read("action/DurabilityWarnings.java");
        assertTrue(warnings.contains("BrainCoordinator.INSTANCE.sendBotReply("), "the warning uses the bot's ordinary chat path");
        for (String forbidden : new String[] {"TaskManager", "pauseFor", "equipFromSlot", "submit(", "AsyncDecisionExecutor"}) {
            assertFalse(warnings.contains(forbidden), "the warning is a chat line only: " + forbidden);
        }
    }
}

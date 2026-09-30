package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.task.AggroSense;
import io.github.zoyluo.minecraftai.task.CombatCore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The gear rule on a real server. TOOLS are used worst-first (wooden pickaxe on stone, stone pickaxe on iron ore, iron pickaxe on
 * diamond ore) until they break, then the next worst takes over. NON-TOOLS (melee weapons, bows, crossbows, shields, armor, elytra)
 * are used best-first until they break, then the next best is equipped automatically (next best helmet, shield, bow, sword). Wear
 * never sets an item aside, danger never changes the choice, and the player controls it by taking items out of the bot's inventory.
 */
public final class GearWorstFirstGameTests {
    private static final String ENV = "minecraftai-gametest:gear_worst_first_game_tests_";
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    // ---------------------------------------------------------------------------------------------------------- tools

    /** Measure-first: before worst-first the stone pickaxe beat the wooden one on stone (ToolSelector's preservation rank). */
    @GameTest(environment = ENV + "worst_capable_pickaxe_breaks_stone_first", maxTicks = 40)
    public void worstCapablePickaxeBreaksStoneFirst(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearStoneFirstGT");
        fill(bot, new ItemStack(Items.DIAMOND_PICKAXE), new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.STONE_PICKAXE),
                new ItemStack(Items.WOODEN_PICKAXE));
        ToolSelector.equipBestTool(bot, STONE);
        require(context, bot.getMainHandItem().is(Items.WOODEN_PICKAXE),
                "stone was not broken with the wooden pickaxe first: " + bot.getMainHandItem().getItem());
        // The mining-channel policy of the long missions is worst-first too: no stone floor.
        bot.getInventory().setSelectedSlot(8);
        ToolSelector.equipMiningChannelTool(bot, STONE);
        require(context, bot.getMainHandItem().is(Items.WOODEN_PICKAXE),
                "the mission channel kept its stone floor: " + bot.getMainHandItem().getItem());
        // A worn wooden pickaxe is used until it breaks: wear never sends the bot to the next tier (also at its last use)...
        ItemStack worn = bot.getInventory().getItem(3);
        worn.setDamageValue(worn.getMaxDamage() - 3);
        bot.getInventory().setSelectedSlot(8);
        ToolSelector.equipBestTool(bot, STONE);
        require(context, bot.getMainHandItem().is(Items.WOODEN_PICKAXE),
                "a worn wooden pickaxe was skipped: " + bot.getMainHandItem().getItem());
        worn.setDamageValue(worn.getMaxDamage() - 1);
        bot.getInventory().setSelectedSlot(8);
        ToolSelector.equipBestTool(bot, STONE);
        require(context, bot.getMainHandItem().is(Items.WOODEN_PICKAXE),
                "a wooden pickaxe at its last use was skipped: " + bot.getMainHandItem().getItem());
        // ...except in the mission channel, whose exact break/pickup/return transactions keep the final use in reserve.
        bot.getInventory().setSelectedSlot(8);
        ToolSelector.equipMiningChannelTool(bot, STONE);
        require(context, bot.getMainHandItem().is(Items.STONE_PICKAXE),
                "the mission channel used the wooden pickaxe at its last use: " + bot.getMainHandItem().getItem());
        // The earlier best-first policy is one switch away, and still prefers the stone pick.
        List<ItemStack> main = new ArrayList<>(bot.getInventory().getNonEquipmentItems());
        main.set(3, new ItemStack(Items.WOODEN_PICKAXE));
        require(context, main.get(ToolSelector.choose(main, 8, ItemStack.EMPTY, STONE, false, true).slot()).is(Items.WOODEN_PICKAXE),
                "worstFirst=true did not choose the wooden pickaxe");
        require(context, main.get(ToolSelector.choose(main, 8, ItemStack.EMPTY, STONE, false, false).slot()).is(Items.STONE_PICKAXE),
                "worstFirst=false (best-first, earlier behaviour) did not keep preferring the stone pickaxe");
        finish(context, bot);
    }

    @GameTest(environment = ENV + "efficiency_five_diamond_pick_does_not_change_worst_choice", maxTicks = 40)
    public void efficiencyFiveDiamondPickDoesNotChangeWorstChoice(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearEfficiencyGT");
        fill(bot, new ItemStack(Items.WOODEN_PICKAXE), new ItemStack(Items.STONE_PICKAXE),
                enchanted(context, Items.DIAMOND_PICKAXE, Enchantments.EFFICIENCY, 5));
        ToolSelector.equipBestTool(bot, STONE);
        require(context, bot.getMainHandItem().is(Items.WOODEN_PICKAXE),
                "an Efficiency V diamond pickaxe changed the choice: " + bot.getMainHandItem().getItem());
        // Enchantments add value: an Efficiency V wooden pickaxe still goes before a plain stone one (1.1 + 0.6 < 2.0)...
        fill(bot, enchanted(context, Items.WOODEN_PICKAXE, Enchantments.EFFICIENCY, 5), new ItemStack(Items.STONE_PICKAXE));
        ToolSelector.equipBestTool(bot, STONE);
        require(context, bot.getMainHandItem().is(Items.WOODEN_PICKAXE) && bot.getMainHandItem().isEnchanted(),
                "the Efficiency V wooden pickaxe was passed over: " + bot.getMainHandItem().getItem());
        // ...but Unbreaking III + Mending + Efficiency V (capped at +1.5 in all) on wood, 2.6, goes after the plain stone one.
        ItemStack loaded = enchanted(context, Items.WOODEN_PICKAXE, Enchantments.EFFICIENCY, 5);
        loaded.enchant(enchantment(context, Enchantments.UNBREAKING), 3);
        loaded.enchant(enchantment(context, Enchantments.MENDING), 1);
        fill(bot, loaded, new ItemStack(Items.STONE_PICKAXE));
        ToolSelector.equipBestTool(bot, STONE);
        require(context, bot.getMainHandItem().is(Items.STONE_PICKAXE) && !bot.getMainHandItem().isEnchanted(),
                "a heavily enchanted wooden pickaxe was used before a plain stone one: " + bot.getMainHandItem().getItem());
        finish(context, bot);
    }

    @GameTest(environment = ENV + "iron_ore_uses_stone_pick_not_wood", maxTicks = 40)
    public void ironOreUsesStonePickNotWood(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearIronOreGT");
        BlockState ironOre = Blocks.IRON_ORE.defaultBlockState();
        fill(bot, new ItemStack(Items.WOODEN_PICKAXE), new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.IRON_PICKAXE),
                new ItemStack(Items.DIAMOND_PICKAXE));
        ToolSelector.equipBestTool(bot, ironOre);
        require(context, bot.getMainHandItem().is(Items.STONE_PICKAXE),
                "iron ore was not broken with the stone pickaxe: " + bot.getMainHandItem().getItem());
        bot.getInventory().setSelectedSlot(8);
        ToolSelector.equipMiningChannelTool(bot, ironOre);
        require(context, bot.getMainHandItem().is(Items.STONE_PICKAXE),
                "the mission channel did not use the stone pickaxe on iron ore: " + bot.getMainHandItem().getItem());
        // A copper pickaxe harvests what stone does and is worth more than stone: stone first, copper before iron.
        fill(bot, new ItemStack(Items.WOODEN_PICKAXE), new ItemStack(Items.COPPER_PICKAXE), new ItemStack(Items.IRON_PICKAXE));
        ToolSelector.equipBestTool(bot, ironOre);
        require(context, bot.getMainHandItem().is(Items.COPPER_PICKAXE),
                "iron ore was not broken with the copper pickaxe before the iron one: " + bot.getMainHandItem().getItem());
        bot.getInventory().setSelectedSlot(8);
        ToolSelector.equipMiningChannelTool(bot, ironOre);
        require(context, bot.getMainHandItem().is(Items.COPPER_PICKAXE),
                "the mission channel did not know the copper pickaxe: " + bot.getMainHandItem().getItem());
        finish(context, bot);
    }

    @GameTest(environment = ENV + "diamond_ore_uses_iron_pick", maxTicks = 40)
    public void diamondOreUsesIronPick(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearDiamondOreGT");
        BlockState diamondOre = Blocks.DIAMOND_ORE.defaultBlockState();
        fill(bot, new ItemStack(Items.DIAMOND_PICKAXE), new ItemStack(Items.WOODEN_PICKAXE), new ItemStack(Items.STONE_PICKAXE),
                new ItemStack(Items.IRON_PICKAXE));
        ToolSelector.equipBestTool(bot, diamondOre);
        require(context, bot.getMainHandItem().is(Items.IRON_PICKAXE),
                "diamond ore was not broken with the iron pickaxe: " + bot.getMainHandItem().getItem());
        bot.getInventory().setSelectedSlot(8);
        ToolSelector.equipMiningChannelTool(bot, diamondOre);
        require(context, bot.getMainHandItem().is(Items.IRON_PICKAXE),
                "the mission channel did not use the iron pickaxe on diamond ore: " + bot.getMainHandItem().getItem());
        // A wrong-tier pick never beats a capable one, and when nothing can harvest the block the channel offers nothing.
        fill(bot, new ItemStack(Items.WOODEN_PICKAXE), new ItemStack(Items.STONE_PICKAXE));
        ToolSelector.Selection none = ToolSelector.equipMiningChannelTool(bot, diamondOre);
        require(context, none.slot() < 0, "the mission channel offered a pick that cannot harvest diamond ore: " + none.describe());
        finish(context, bot);
    }

    @GameTest(environment = ENV + "silk_touch_pick_is_last_resort", maxTicks = 40)
    public void silkTouchPickIsLastResort(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearSilkGT");
        BlockState diamondOre = Blocks.DIAMOND_ORE.defaultBlockState();
        fill(bot, enchanted(context, Items.IRON_PICKAXE, Enchantments.SILK_TOUCH, 1), new ItemStack(Items.DIAMOND_PICKAXE));
        ToolSelector.equipBestTool(bot, diamondOre);
        require(context, bot.getMainHandItem().is(Items.DIAMOND_PICKAXE),
                "the Silk Touch iron pickaxe was used while a plain diamond one was carried: " + bot.getMainHandItem().getItem());
        bot.getInventory().setSelectedSlot(8);
        ToolSelector.equipMiningChannelTool(bot, diamondOre);
        require(context, bot.getMainHandItem().is(Items.DIAMOND_PICKAXE),
                "the mission channel used the Silk Touch pickaxe first: " + bot.getMainHandItem().getItem());
        // Alone, it is used.
        fill(bot, enchanted(context, Items.IRON_PICKAXE, Enchantments.SILK_TOUCH, 1));
        ToolSelector.equipBestTool(bot, diamondOre);
        require(context, bot.getMainHandItem().is(Items.IRON_PICKAXE), "the lone Silk Touch pickaxe was not used");
        finish(context, bot);
    }

    @GameTest(environment = ENV + "plain_iron_pick_before_fortune_iron_pick", maxTicks = 40)
    public void plainIronPickBeforeFortuneIronPick(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearFortuneGT");
        BlockState diamondOre = Blocks.DIAMOND_ORE.defaultBlockState();
        fill(bot, enchanted(context, Items.IRON_PICKAXE, Enchantments.FORTUNE, 3), new ItemStack(Items.IRON_PICKAXE));
        ToolSelector.equipBestTool(bot, diamondOre);
        require(context, bot.getMainHandItem().is(Items.IRON_PICKAXE) && !bot.getMainHandItem().isEnchanted(),
                "the Fortune III iron pickaxe was used before the plain one: " + bot.getMainHandItem());
        // At equal value the more worn pickaxe goes first.
        ItemStack fresh = new ItemStack(Items.IRON_PICKAXE);
        ItemStack worn = new ItemStack(Items.IRON_PICKAXE);
        worn.setDamageValue(100);
        fill(bot, fresh, worn);
        ToolSelector.equipBestTool(bot, diamondOre);
        require(context, bot.getMainHandItem().getDamageValue() == 100,
                "the fresh iron pickaxe was used before the worn one: " + bot.getMainHandItem().getDamageValue());
        finish(context, bot);
    }

    @GameTest(environment = ENV + "soft_block_uses_worst_shovel", maxTicks = 40)
    public void softBlockUsesWorstShovel(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearShovelGT");
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        fill(bot, new ItemStack(Items.DIAMOND_SHOVEL), new ItemStack(Items.IRON_SHOVEL), new ItemStack(Items.WOODEN_SHOVEL),
                new ItemStack(Items.STONE_SHOVEL));
        ToolSelector.equipBestTool(bot, dirt);
        require(context, bot.getMainHandItem().is(Items.WOODEN_SHOVEL),
                "dirt was not dug with the wooden shovel: " + bot.getMainHandItem().getItem());
        // A tool that is no faster than the hand on this block (a pickaxe on dirt) is never picked for it.
        fill(bot, new ItemStack(Items.WOODEN_PICKAXE), new ItemStack(Items.IRON_SHOVEL), new ItemStack(Items.DIAMOND_SHOVEL));
        ToolSelector.equipBestTool(bot, dirt);
        require(context, bot.getMainHandItem().is(Items.IRON_SHOVEL),
                "the worst shovel was not chosen among shovels: " + bot.getMainHandItem().getItem());
        finish(context, bot);
    }

    /** Tools are used worst-first until they break, then the next worst takes over (wooden, then stone, then iron). */
    @GameTest(environment = ENV + "tool_breaks_then_the_next_worst_takes_over", maxTicks = 40)
    public void toolBreaksThenTheNextWorstTakesOver(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearToolBreakGT");
        ItemStack lastUse = new ItemStack(Items.WOODEN_PICKAXE);
        lastUse.setDamageValue(lastUse.getMaxDamage() - 1);
        fill(bot, new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.STONE_PICKAXE), lastUse);
        ToolSelector.equipBestTool(bot, STONE);
        require(context, bot.getMainHandItem().is(Items.WOODEN_PICKAXE) && bot.getMainHandItem().getDamageValue() == lastUse.getDamageValue(),
                "the wooden pickaxe at its last use was not the chosen tool: " + bot.getMainHandItem());
        breakHeld(bot, EquipmentSlot.MAINHAND);
        require(context, bot.getMainHandItem().isEmpty(), "the wooden pickaxe did not break");
        ToolSelector.equipBestTool(bot, STONE);
        require(context, bot.getMainHandItem().is(Items.STONE_PICKAXE),
                "after the wooden pickaxe broke the next worst (stone) did not take over: " + bot.getMainHandItem().getItem());
        // The same again for the stone pickaxe: iron is next.
        bot.getMainHandItem().setDamageValue(bot.getMainHandItem().getMaxDamage() - 1);
        breakHeld(bot, EquipmentSlot.MAINHAND);
        ToolSelector.equipBestTool(bot, STONE);
        require(context, bot.getMainHandItem().is(Items.IRON_PICKAXE),
                "after the stone pickaxe broke the iron one did not take over: " + bot.getMainHandItem().getItem());
        finish(context, bot);
    }

    // ---------------------------------------------------------------------------------------------------------- armor

    @GameTest(environment = ENV + "armor_fills_empty_slot_with_best_piece", maxTicks = 40)
    public void armorFillsEmptySlotWithBestPiece(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearArmorFillGT");
        clearGear(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.LEATHER_HELMET));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_HELMET));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_HELMET));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_CHESTPLATE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_CHESTPLATE));
        int changed = EquipAction.autoEquipArmor(bot);
        require(context, changed == 2, "expected two slots to be filled, got " + changed);
        require(context, bot.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET),
                "the head slot was not filled with the diamond helmet: " + bot.getItemBySlot(EquipmentSlot.HEAD).getItem());
        require(context, bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE),
                "the chest slot was not filled with the diamond chestplate (best of iron and diamond): "
                        + bot.getItemBySlot(EquipmentSlot.CHEST).getItem());
        require(context, InventoryAction.countItem(bot, Items.LEATHER_HELMET) == 1 && InventoryAction.countItem(bot, Items.IRON_HELMET) == 1
                        && InventoryAction.countItem(bot, Items.IRON_CHESTPLATE) == 1 && InventoryAction.countItem(bot, Items.DIAMOND_HELMET) == 0,
                "the armor pieces were lost or duplicated");
        require(context, EquipAction.autoEquipArmor(bot) == 0, "a second pass changed something (the choice is not stable)");
        // Enchantments add value: a Protection IV leather chestplate (3 + 4 = 7) is worth more than a plain iron one (6).
        clearGear(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_CHESTPLATE));
        InventoryAction.giveItem(bot, enchanted(context, Items.LEATHER_CHESTPLATE, Enchantments.PROTECTION, 4));
        EquipAction.autoEquipArmor(bot);
        require(context, bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.LEATHER_CHESTPLATE) && bot.getItemBySlot(EquipmentSlot.CHEST).isEnchanted(),
                "the plain iron chestplate was worn before the Protection IV leather one: " + bot.getItemBySlot(EquipmentSlot.CHEST));
        finish(context, bot);
    }

    @GameTest(environment = ENV + "worn_leather_is_swapped_up_to_diamond", maxTicks = 40)
    public void wornLeatherIsSwappedUpToDiamond(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearSwapUpGT");
        clearGear(bot);
        bot.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_CHESTPLATE));
        int changed = EquipAction.autoEquipArmor(bot);
        require(context, changed == 1 && bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE),
                "the worn leather chestplate was not swapped up to diamond: " + bot.getItemBySlot(EquipmentSlot.CHEST).getItem());
        require(context, InventoryAction.countItem(bot, Items.LEATHER_CHESTPLATE) == 1, "the leather chestplate was lost in the swap");
        finish(context, bot);
    }

    /** No provenance and no exceptions: a worse piece put in keeps the worn one on; the player takes pieces out to control it. */
    @GameTest(environment = ENV + "player_controls_armor_by_taking_items_out", maxTicks = 40)
    public void playerControlsArmorByTakingItemsOut(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearPlayerControlGT");
        clearGear(bot);
        bot.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        InventoryAction.giveItem(bot, new ItemStack(Items.LEATHER_CHESTPLATE));
        require(context, EquipAction.autoEquipArmor(bot) == 0 && bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE),
                "a carried worse piece replaced the worn iron chestplate");
        // A better piece put into the inventory is worn at once.
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_CHESTPLATE));
        EquipAction.autoEquipArmor(bot);
        require(context, bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE),
                "the carried diamond chestplate was not worn: " + bot.getItemBySlot(EquipmentSlot.CHEST).getItem());
        // The player takes the diamond one off the bot (the chest slot is empty): the best that is left goes on.
        bot.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        EquipAction.autoEquipArmor(bot);
        require(context, bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE),
                "an empty chest slot was not refilled with the best carried piece: " + bot.getItemBySlot(EquipmentSlot.CHEST));
        finish(context, bot);
    }

    /** A worn piece is worn until it breaks; the moment it does, the next best piece for that slot is put on (helmet, then elytra). */
    @GameTest(environment = ENV + "armor_is_worn_until_it_breaks_then_the_next_best_is_worn", maxTicks = 40)
    public void armorIsWornUntilItBreaksThenTheNextBestIsWorn(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearArmorBreakGT");
        clearGear(bot);
        ItemStack tired = new ItemStack(Items.DIAMOND_HELMET);
        tired.setDamageValue(tired.getMaxDamage() - 2);
        bot.setItemSlot(EquipmentSlot.HEAD, tired);
        InventoryAction.giveItem(bot, new ItemStack(Items.LEATHER_HELMET));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_HELMET));
        require(context, EquipAction.autoEquipArmor(bot) == 0 && bot.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET),
                "a nearly broken diamond helmet was replaced before it broke: " + bot.getItemBySlot(EquipmentSlot.HEAD).getItem());
        breakHeld(bot, EquipmentSlot.HEAD);
        breakHeld(bot, EquipmentSlot.HEAD);
        require(context, bot.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "the diamond helmet did not break");
        require(context, EquipAction.autoEquipArmor(bot) == 1 && bot.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET),
                "after the diamond helmet broke the next best (iron) helmet was not worn: " + bot.getItemBySlot(EquipmentSlot.HEAD).getItem());
        // Through the combat entry point too (it runs the same pass at every boundary).
        ItemStack lastUse = bot.getItemBySlot(EquipmentSlot.HEAD);
        lastUse.setDamageValue(lastUse.getMaxDamage() - 1);
        breakHeld(bot, EquipmentSlot.HEAD);
        CombatCore.equipMelee(bot);
        require(context, bot.getItemBySlot(EquipmentSlot.HEAD).is(Items.LEATHER_HELMET),
                "after the iron helmet broke the leather one was not worn: " + bot.getItemBySlot(EquipmentSlot.HEAD).getItem());
        // A worn elytra is never swapped for a carried chestplate; when it breaks the best chestplate goes on.
        bot.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_CHESTPLATE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_CHESTPLATE));
        require(context, EquipAction.autoEquipArmor(bot) == 0 && bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA),
                "a worn elytra was swapped for a chestplate");
        bot.getItemBySlot(EquipmentSlot.CHEST).setDamageValue(bot.getItemBySlot(EquipmentSlot.CHEST).getMaxDamage() - 1);
        breakHeld(bot, EquipmentSlot.CHEST);
        require(context, bot.getItemBySlot(EquipmentSlot.CHEST).isEmpty(), "the elytra did not break");
        EquipAction.autoEquipArmor(bot);
        require(context, bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE),
                "after the elytra broke the best chestplate was not worn: " + bot.getItemBySlot(EquipmentSlot.CHEST).getItem());
        finish(context, bot);
    }

    @GameTest(environment = ENV + "worn_piece_edge_cases", maxTicks = 40)
    public void wornPieceEdgeCases(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearArmorEdgeGT");
        clearGear(bot);
        // A nearly broken best piece stays on (worn until it breaks); a nearly broken piece in the inventory is still put on.
        ItemStack tired = new ItemStack(Items.DIAMOND_CHESTPLATE);
        tired.setDamageValue(tired.getMaxDamage() - 2);
        bot.setItemSlot(EquipmentSlot.CHEST, tired);
        InventoryAction.giveItem(bot, new ItemStack(Items.LEATHER_CHESTPLATE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_CHESTPLATE));
        EquipAction.autoEquipArmor(bot);
        require(context, bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE),
                "a nearly broken diamond chestplate was replaced by a carried one: " + bot.getItemBySlot(EquipmentSlot.CHEST).getItem());
        clearGear(bot);
        ItemStack tiredIron = new ItemStack(Items.IRON_CHESTPLATE);
        tiredIron.setDamageValue(tiredIron.getMaxDamage() - 1);
        InventoryAction.giveItem(bot, tiredIron);
        require(context, EquipAction.autoEquipArmor(bot) == 1 && bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE),
                "a nearly broken chestplate was not put on");
        // Of two equal pieces the worn one stays (no churn), whichever is more worn.
        clearGear(bot);
        bot.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        ItemStack wornIron = new ItemStack(Items.IRON_CHESTPLATE);
        wornIron.setDamageValue(50);
        InventoryAction.giveItem(bot, wornIron);
        require(context, EquipAction.autoEquipArmor(bot) == 0, "an equal piece replaced the worn one");
        // A worn Binding Curse piece cannot be taken off.
        clearGear(bot);
        bot.setItemSlot(EquipmentSlot.CHEST, enchanted(context, Items.LEATHER_CHESTPLATE, Enchantments.BINDING_CURSE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_CHESTPLATE));
        require(context, EquipAction.autoEquipArmor(bot) == 0 && bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.LEATHER_CHESTPLATE),
                "a Binding Curse piece was swapped out");
        // A Binding Curse piece in the inventory is never put on.
        clearGear(bot);
        InventoryAction.giveItem(bot, enchanted(context, Items.LEATHER_CHESTPLATE, Enchantments.BINDING_CURSE, 1));
        require(context, EquipAction.autoEquipArmor(bot) == 0 && bot.getItemBySlot(EquipmentSlot.CHEST).isEmpty(),
                "a Binding Curse chestplate was put on automatically");
        // Elytra and a carved pumpkin are no armor.
        clearGear(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.ELYTRA));
        InventoryAction.giveItem(bot, new ItemStack(Items.CARVED_PUMPKIN));
        require(context, EquipAction.autoEquipArmor(bot) == 0 && bot.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                        && bot.getItemBySlot(EquipmentSlot.HEAD).isEmpty(),
                "an elytra or a carved pumpkin was worn as armor");
        // The explicit command is best-first too, in the same order.
        clearGear(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_CHESTPLATE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_CHESTPLATE));
        EquipAction.equipBestArmor(bot);
        require(context, bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE),
                "equipBestArmor (the equip_armor command) is no longer best-first");
        finish(context, bot);
    }

    // ---------------------------------------------------------------------------------------------------------- weapons

    /** Melee weapons are best-first: the diamond sword is drawn, used until it breaks (however worn), then the iron one, then stone. */
    @GameTest(environment = ENV + "weapon_is_best_sword_and_next_best_on_break", maxTicks = 40)
    public void weaponIsBestSwordAndNextBestOnBreak(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearZombieGT");
        Zombie zombie = spawnZombie(context, bot, 2);
        ItemStack diamond = new ItemStack(Items.DIAMOND_SWORD);
        diamond.setDamageValue(diamond.getMaxDamage() - 2);
        fill(bot, new ItemStack(Items.WOODEN_SWORD), new ItemStack(Items.STONE_SWORD), diamond, new ItemStack(Items.IRON_SWORD));
        CombatCore.ensureMeleeWeapon(bot, zombie);
        require(context, bot.getMainHandItem().is(Items.DIAMOND_SWORD),
                "the best sword against a zombie is the diamond one (a two-use sword is not set aside): " + bot.getMainHandItem().getItem());
        // Through the context entry point (no target known) it is the same.
        CombatCore.equipMelee(bot);
        require(context, bot.getMainHandItem().is(Items.DIAMOND_SWORD), "held " + bot.getMainHandItem().getItem());
        // A hit spends a use: still the diamond sword at its last use...
        bot.getMainHandItem().hurtAndBreak(1, bot, EquipmentSlot.MAINHAND);
        CombatCore.ensureMeleeWeapon(bot, zombie);
        require(context, bot.getMainHandItem().is(Items.DIAMOND_SWORD) && bot.getMainHandItem().getDamageValue() == diamond.getMaxDamage() - 1,
                "the diamond sword at its last use was set aside: " + bot.getMainHandItem());
        // ...and the attack boundary after it broke draws the next best (iron), then stone, then wood.
        breakHeld(bot, EquipmentSlot.MAINHAND);
        require(context, bot.getMainHandItem().isEmpty(), "the diamond sword did not break");
        CombatCore.ensureMeleeWeapon(bot, zombie);
        require(context, bot.getMainHandItem().is(Items.IRON_SWORD),
                "after the diamond sword broke the next best (iron) was not drawn: " + bot.getMainHandItem().getItem());
        bot.getMainHandItem().setDamageValue(bot.getMainHandItem().getMaxDamage() - 1);
        breakHeld(bot, EquipmentSlot.MAINHAND);
        CombatCore.ensureMeleeWeapon(bot, zombie);
        require(context, bot.getMainHandItem().is(Items.STONE_SWORD),
                "after the iron sword broke the stone sword was not drawn: " + bot.getMainHandItem().getItem());
        bot.getMainHandItem().setDamageValue(bot.getMainHandItem().getMaxDamage() - 1);
        breakHeld(bot, EquipmentSlot.MAINHAND);
        CombatCore.ensureMeleeWeapon(bot, zombie);
        require(context, bot.getMainHandItem().is(Items.WOODEN_SWORD),
                "after the stone sword broke the wooden sword was not drawn: " + bot.getMainHandItem().getItem());
        zombie.discard();
        finish(context, bot);
    }

    /** Danger never changes the choice: hurt, at 1.5 hearts and outnumbered, the bot uses its best sword and armor (no escalation either way). */
    @GameTest(environment = ENV + "danger_still_uses_best_gear", maxTicks = 40)
    public void dangerStillUsesBestGear(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearDangerGT");
        clearGear(bot);
        Zombie first = spawnZombie(context, bot, 2);
        Zombie second = spawnZombie(context, bot, 3);
        bot.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_CHESTPLATE));
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_SWORD));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_SWORD));
        // Both zombies really strike the bot (perception: a blow makes its striker known, the bot needs no look round first).
        require(context, io.github.zoyluo.minecraftai.gametest.PerceptionFixtures.struckBy(context, bot, first, 2.0F)
                        && io.github.zoyluo.minecraftai.gametest.PerceptionFixtures.struckBy(context, bot, second, 2.0F),
                "the zombies' blows on the bot were not real");
        bot.setHealth(3.0F);
        AggroSense.Snapshot snapshot = AggroSense.snapshot(bot);
        require(context, snapshot.pressure() && snapshot.aggressorCount() >= 1,
                "the zombies did not count as aggressors, so this fixture would prove nothing: " + snapshot);
        CombatCore.equipMelee(bot);
        require(context, bot.getMainHandItem().is(Items.DIAMOND_SWORD),
                "in danger the bot did not use its best sword: " + bot.getMainHandItem().getItem());
        require(context, bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE),
                "in danger the bot did not wear its best chestplate: " + bot.getItemBySlot(EquipmentSlot.CHEST).getItem());
        first.discard();
        second.discard();
        finish(context, bot);
    }

    @GameTest(environment = ENV + "best_weapon_against_ravager", maxTicks = 40)
    public void bestWeaponAgainstRavager(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearRavagerGT");
        Ravager ravager = EntityType.RAVAGER.create(context.getLevel(), EntitySpawnReason.COMMAND);
        require(context, ravager != null, "no ravager");
        ravager.setNoAi(true);
        ravager.setPersistenceRequired();
        BlockPos at = bot.blockPosition().south(3);
        ravager.snapTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, 0.0F, 0.0F);
        context.getLevel().addFreshEntity(ravager);
        fill(bot, new ItemStack(Items.WOODEN_SWORD), new ItemStack(Items.STONE_SWORD), new ItemStack(Items.DIAMOND_SWORD),
                new ItemStack(Items.IRON_SWORD));
        OptionalInt slot = EquipAction.equipWeaponForContext(bot, ravager);
        require(context, slot.isPresent() && bot.getMainHandItem().is(Items.DIAMOND_SWORD),
                "the best sword (diamond) against a 100 health ravager was expected, got " + bot.getMainHandItem().getItem());
        ravager.discard();
        finish(context, bot);
    }

    /** G1: the background pass never takes off a worn elytra, carved pumpkin or mob head to put a carried chestplate or helmet on. */
    @GameTest(environment = ENV + "worn_non_armor_is_never_swapped_by_the_background_pass", maxTicks = 40)
    public void wornNonArmorIsNeverSwappedByTheBackgroundPass(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearWornKeepGT");
        clearGear(bot);
        bot.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        bot.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_CHESTPLATE));
        InventoryAction.giveItem(bot, new ItemStack(Items.LEATHER_HELMET));
        require(context, EquipAction.autoEquipArmor(bot) == 0
                        && bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)
                        && bot.getItemBySlot(EquipmentSlot.HEAD).is(Items.CARVED_PUMPKIN),
                "the worn elytra or carved pumpkin was swapped: " + bot.getItemBySlot(EquipmentSlot.CHEST).getItem() + " / "
                        + bot.getItemBySlot(EquipmentSlot.HEAD).getItem());
        require(context, InventoryAction.countItem(bot, Items.IRON_CHESTPLATE) == 1 && InventoryAction.countItem(bot, Items.LEATHER_HELMET) == 1,
                "the carried armor pieces were lost");
        // A worn mob head is kept the same way, and the combat entry point (which runs the same pass) keeps it too.
        bot.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.ZOMBIE_HEAD));
        CombatCore.equipMelee(bot);
        require(context, bot.getItemBySlot(EquipmentSlot.HEAD).is(Items.ZOMBIE_HEAD) && bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA),
                "a worn mob head or elytra was swapped by the combat armor pass: " + bot.getItemBySlot(EquipmentSlot.HEAD).getItem());
        // Control: a slot with nothing worn still gets the best piece.
        bot.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        EquipAction.autoEquipArmor(bot);
        require(context, bot.getItemBySlot(EquipmentSlot.HEAD).is(Items.LEATHER_HELMET), "an empty head slot was not filled");
        finish(context, bot);
    }

    /** A target the bot was told to attack, not yet flagged aggressive, gets the best weapon like any other target. */
    @GameTest(environment = ENV + "explicit_attack_target_gets_the_best_weapon", maxTicks = 40)
    public void explicitAttackTargetGetsTheBestWeapon(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearExplicitTargetGT");
        Ravager ravager = EntityType.RAVAGER.create(context.getLevel(), EntitySpawnReason.COMMAND);
        require(context, ravager != null, "no ravager");
        ravager.setNoAi(true);
        ravager.setPersistenceRequired();
        BlockPos at = bot.blockPosition().south(3);
        ravager.snapTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, 0.0F, 0.0F);
        context.getLevel().addFreshEntity(ravager);
        require(context, AggroSense.snapshot(bot).aggressorCount() == 0, "the ravager already counts as an aggressor: the fixture proves nothing");
        fill(bot, new ItemStack(Items.WOODEN_SWORD), new ItemStack(Items.STONE_SWORD), new ItemStack(Items.DIAMOND_SWORD),
                new ItemStack(Items.IRON_SWORD));
        CombatCore.ensureMeleeWeapon(bot, ravager);
        require(context, bot.getMainHandItem().is(Items.DIAMOND_SWORD),
                "against an unflagged ravager the bot picked " + bot.getMainHandItem().getItem() + " instead of the diamond sword");
        ravager.discard();
        // An explicit zombie target gets the best sword as well (no per-target downgrade any more).
        Zombie zombie = spawnZombie(context, bot, 2);
        zombie.setAggressive(false);
        fill(bot, new ItemStack(Items.IRON_SWORD), new ItemStack(Items.WOODEN_SWORD), new ItemStack(Items.DIAMOND_SWORD),
                new ItemStack(Items.STONE_SWORD));
        CombatCore.ensureMeleeWeapon(bot, zombie);
        require(context, bot.getMainHandItem().is(Items.DIAMOND_SWORD),
                "against an unflagged zombie the bot picked " + bot.getMainHandItem().getItem() + " instead of the diamond sword");
        zombie.discard();
        finish(context, bot);
    }

    @GameTest(environment = ENV + "best_weapon_follows_inventory_changes", maxTicks = 40)
    public void bestWeaponFollowsInventoryChanges(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearFollowGT");
        fill(bot, new ItemStack(Items.WOODEN_SWORD), new ItemStack(Items.IRON_SWORD));
        EquipAction.equipWeaponForContext(bot);
        require(context, bot.getMainHandItem().is(Items.IRON_SWORD), "held " + bot.getMainHandItem().getItem());
        // A better sword arrives: the choice follows at once.
        bot.getInventory().setItem(4, new ItemStack(Items.DIAMOND_SWORD));
        EquipAction.equipWeaponForContext(bot);
        require(context, bot.getMainHandItem().is(Items.DIAMOND_SWORD),
                "the diamond sword that arrived was not drawn: " + bot.getMainHandItem().getItem());
        // The player takes it out again: back to the next best.
        bot.getInventory().setItem(4, ItemStack.EMPTY);
        EquipAction.equipWeaponForContext(bot);
        require(context, bot.getMainHandItem().is(Items.IRON_SWORD),
                "after the diamond sword was taken out the next best was not drawn: " + bot.getMainHandItem().getItem());
        finish(context, bot);
    }

    // ---------------------------------------------------------------------------------------------------------- shield

    /** Shields are best-first; the enchanted one is raised, and when it breaks the plain one takes its place in the offhand. */
    @GameTest(environment = ENV + "shield_is_best_first_and_the_next_best_on_break", maxTicks = 40)
    public void shieldIsBestFirstAndTheNextBestOnBreak(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearShieldGT");
        clearGear(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.SHIELD));
        InventoryAction.giveItem(bot, enchanted(context, Items.SHIELD, Enchantments.UNBREAKING, 3));
        require(context, EquipAction.equipShieldOffhand(bot) && bot.getOffhandItem().is(Items.SHIELD) && bot.getOffhandItem().isEnchanted(),
                "the Unbreaking III shield was not the one raised: " + bot.getOffhandItem());
        // A worn shield at its last use is still the one held (no swap away because of wear)...
        bot.getOffhandItem().setDamageValue(bot.getOffhandItem().getMaxDamage() - 1);
        require(context, EquipAction.equipShieldOffhand(bot) && bot.getOffhandItem().isEnchanted(),
                "a shield at its last use was swapped away: " + bot.getOffhandItem());
        // ...and once it broke the plain one replaces it in the offhand.
        breakHeldAll(bot);
        require(context, bot.getOffhandItem().isEmpty(), "the shield did not break");
        require(context, EquipAction.equipShieldOffhand(bot) && bot.getOffhandItem().is(Items.SHIELD) && !bot.getOffhandItem().isEnchanted(),
                "after the shield broke the next best shield was not raised: " + bot.getOffhandItem());
        // A better shield that turns up does not replace the held one: it is used until it breaks.
        InventoryAction.giveItem(bot, enchanted(context, Items.SHIELD, Enchantments.UNBREAKING, 2));
        EquipAction.equipShieldOffhand(bot);
        require(context, !bot.getOffhandItem().isEnchanted(), "a held shield was swapped for a carried one: " + bot.getOffhandItem());
        finish(context, bot);
    }

    // ---------------------------------------------------------------------------------------------------------- offhand policy (O1)

    /**
     * The offhand ladder: the best shield, else a totem, else nothing, and a broken or used-up item is replaced by the best of the
     * same kind first (2 shields then 2 totems).
     */
    @GameTest(environment = ENV + "offhand_ladder_shield_then_totem_then_next_totem", maxTicks = 40)
    public void offhandLadderShieldThenTotemThenNextTotem(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearOffhandLadderGT");
        clearGear(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.TOTEM_OF_UNDYING));
        InventoryAction.giveItem(bot, new ItemStack(Items.SHIELD));
        InventoryAction.giveItem(bot, new ItemStack(Items.TOTEM_OF_UNDYING));
        InventoryAction.giveItem(bot, enchanted(context, Items.SHIELD, Enchantments.UNBREAKING, 3));
        require(context, OffhandPolicy.apply(bot) && bot.getOffhandItem().is(Items.SHIELD) && bot.getOffhandItem().isEnchanted(),
                "the best shield was not put in the offhand (a shield outranks a totem): " + bot.getOffhandItem());
        require(context, !OffhandPolicy.apply(bot), "a second pass changed the offhand (the choice is not stable)");
        require(context, InventoryAction.countItem(bot, Items.SHIELD) == 2 && InventoryAction.countItem(bot, Items.TOTEM_OF_UNDYING) == 2,
                "an item was lost or duplicated");
        // The shield breaks: the next shield, not a totem.
        bot.getOffhandItem().setDamageValue(bot.getOffhandItem().getMaxDamage() - 1);
        require(context, !OffhandPolicy.apply(bot) && bot.getOffhandItem().isEnchanted(), "a shield at its last use was swapped away");
        breakHeldAll(bot);
        require(context, bot.getOffhandItem().isEmpty(), "the shield did not break");
        require(context, OffhandPolicy.apply(bot) && bot.getOffhandItem().is(Items.SHIELD) && !bot.getOffhandItem().isEnchanted(),
                "after the shield broke the next shield was not equipped: " + bot.getOffhandItem());
        // The last shield breaks: a totem.
        breakHeldAll(bot);
        require(context, OffhandPolicy.apply(bot) && bot.getOffhandItem().is(Items.TOTEM_OF_UNDYING),
                "after the last shield broke a totem was not equipped: " + bot.getOffhandItem());
        // The totem pops for real (a lethal hit, vanilla uses the stack up): the next totem.
        popTotem(context, bot);
        require(context, bot.getOffhandItem().isEmpty(), "the totem did not pop: " + bot.getOffhandItem());
        require(context, OffhandPolicy.apply(bot) && bot.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
                        && InventoryAction.countItem(bot, Items.TOTEM_OF_UNDYING) == 1,
                "after the first totem popped the next totem was not equipped: " + bot.getOffhandItem());
        // The last totem pops for real: nothing.
        popTotem(context, bot);
        require(context, bot.getOffhandItem().isEmpty() && InventoryAction.countItem(bot, Items.TOTEM_OF_UNDYING) == 0,
                "the last totem did not pop: " + bot.getOffhandItem());
        require(context, !OffhandPolicy.apply(bot) && bot.getOffhandItem().isEmpty(), "an item came from nowhere: " + bot.getOffhandItem());
        finish(context, bot);
    }

    /** A totem held only for lack of a shield gives way to a shield that turns up; any other offhand item is left alone. */
    @GameTest(environment = ENV + "offhand_totem_gives_way_to_a_shield_and_other_items_stay", maxTicks = 40)
    public void offhandTotemGivesWayToAShieldAndOtherItemsStay(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearOffhandTotemGT");
        clearGear(bot);
        bot.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        require(context, !OffhandPolicy.apply(bot) && bot.getOffhandItem().is(Items.TOTEM_OF_UNDYING), "a lone totem was moved");
        InventoryAction.giveItem(bot, new ItemStack(Items.SHIELD));
        require(context, OffhandPolicy.apply(bot) && bot.getOffhandItem().is(Items.SHIELD)
                        && InventoryAction.countItem(bot, Items.TOTEM_OF_UNDYING) == 1,
                "the totem did not give way to the shield (or was lost): " + bot.getOffhandItem());
        // A torch in the offhand is left alone, shield or not.
        bot.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TORCH, 8));
        require(context, !OffhandPolicy.apply(bot) && bot.getOffhandItem().is(Items.TORCH), "an unmanaged offhand item was replaced");
        finish(context, bot);
    }

    /**
     * The armor and offhand pass runs on every tick before the safety net, so it is not skipped while a rescue owns the tick. A bot
     * under water (NavSafetyNet takes over as soon as it notices) that has no offhand item is handed a totem once the rescue is seen
     * running: the totem must be in the offhand within a few ticks while the rescue is STILL active, not after it ends.
     */
    @GameTest(environment = ENV + "equipment_pass_runs_during_a_water_rescue", maxTicks = 80)
    public void equipmentPassRunsDuringAWaterRescue(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearRescueGT");
        clearGear(bot);
        BlockPos feet = bot.blockPosition().immutable();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    context.getLevel().setBlock(feet.offset(dx, dy, dz), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        bot.teleportTo(context.getLevel(), feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
        bot.setDeltaMovement(Vec3.ZERO);
        require(context, bot.getOffhandItem().isEmpty() && InventoryAction.countItem(bot, Items.TOTEM_OF_UNDYING) == 0,
                "the fixture's bot already had a totem");
        int[] tick = {0};
        int[] givenAt = {-1};
        context.onEachTick(() -> {
            tick[0]++;
            boolean rescueActive = io.github.zoyluo.minecraftai.task.NavSafetyNet.INSTANCE.isWaterRescueActive(bot);
            if (givenAt[0] < 0) {
                if (rescueActive) {
                    InventoryAction.giveItem(bot, new ItemStack(Items.TOTEM_OF_UNDYING));
                    givenAt[0] = tick[0];
                } else if (tick[0] > 30) {
                    context.fail(Component.nullToEmpty("the water rescue never started (underwater=" + bot.isUnderWater() + ")"));
                }
                return;
            }
            if (bot.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
                require(context, rescueActive, "the totem was equipped only after the water rescue ended (tick " + tick[0] + ")");
                io.github.zoyluo.minecraftai.task.NavSafetyNet.INSTANCE.clear(bot);
                finish(context, bot);
            } else if (tick[0] > givenAt[0] + 5) {
                context.fail(Component.nullToEmpty("no totem in the offhand " + (tick[0] - givenAt[0]) + " ticks after it was given in the middle of a water rescue (rescue active="
                        + rescueActive + ", underwater=" + bot.isUnderWater() + ")"));
            }
        });
    }

    /** Through the real tick: a bot that carries a shield and a totem wears the shield in the offhand without being asked, and the totem after it. */
    @GameTest(environment = ENV + "offhand_is_filled_by_the_background_pass", maxTicks = 60)
    public void offhandIsFilledByTheBackgroundPass(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearOffhandTickGT");
        clearGear(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.TOTEM_OF_UNDYING));
        InventoryAction.giveItem(bot, new ItemStack(Items.SHIELD));
        context.runAfterDelay(10, () -> {
            try {
                require(context, bot.getOffhandItem().is(Items.SHIELD), "the background pass did not equip the shield: " + bot.getOffhandItem());
                breakHeldAll(bot);
            } catch (RuntimeException e) {
                finish(context, bot);
                throw e;
            }
            context.runAfterDelay(10, () -> {
                require(context, bot.getOffhandItem().is(Items.TOTEM_OF_UNDYING),
                        "after the shield broke the background pass did not equip the totem: " + bot.getOffhandItem());
                finish(context, bot);
            });
        });
    }

    /**
     * A lethal hit on a bot that holds a totem in a hand: vanilla pops it (the stack is used up, health and effects are restored).
     * A fake connection protects a player for 60 ticks until its client "loaded", so the load is accepted first.
     */
    private static void popTotem(GameTestHelper context, AIPlayerEntity bot) {
        if (!bot.connection.hasClientLoaded()) {
            bot.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        }
        bot.invulnerableTime = 0; // a fresh hit, not one swallowed by the invulnerability frames of an earlier one
        bot.hurtServer(context.getLevel(), context.getLevel().damageSources().generic(), 1000.0F);
        require(context, bot.isAlive(), "the lethal hit killed the bot: the totem did not protect it");
        bot.setHealth(bot.getMaxHealth());
    }

    private static void breakHeldAll(AIPlayerEntity bot) {
        while (!bot.getOffhandItem().isEmpty()) {
            breakHeld(bot, EquipmentSlot.OFFHAND);
        }
    }

    // ---------------------------------------------------------------------------------------------------------- ranged: next best on break

    /**
     * The best ranged weapon is held until it breaks, however worn, and the next best one is chosen the moment it is gone: a Power V
     * bow at its last use, then a Quick Charge III crossbow that is ALSO at its last use (a worn crossbow is still used until it
     * breaks, ahead of a fresh plain bow), then the plain bow. Each step names the specific weapon expected.
     */
    @GameTest(environment = ENV + "ranged_weapon_breaks_then_the_next_best_is_chosen", maxTicks = 40)
    public void rangedWeaponBreaksThenTheNextBestIsChosen(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearRangedBreakGT");
        clearGear(bot);
        ItemStack power = enchanted(context, Items.BOW, Enchantments.POWER, 5);
        power.setDamageValue(power.getMaxDamage() - 1);
        ItemStack quickCharge = enchanted(context, Items.CROSSBOW, Enchantments.QUICK_CHARGE, 3);
        quickCharge.setDamageValue(quickCharge.getMaxDamage() - 1);
        InventoryAction.giveItem(bot, power);
        InventoryAction.giveItem(bot, quickCharge);
        InventoryAction.giveItem(bot, new ItemStack(Items.BOW));
        InventoryAction.giveItem(bot, new ItemStack(Items.ARROW, 8));

        Optional<EquipAction.RangedLoadout> first = EquipAction.equipBestRangedLoadout(bot, null);
        require(context, first.isPresent() && bot.getMainHandItem().is(Items.BOW)
                        && GearValue.enchantmentLevel(bot.getMainHandItem(), "power") == 5
                        && GearValue.remaining(bot.getMainHandItem()) == 1,
                "the Power V bow at its last use was not the chosen ranged weapon: " + bot.getMainHandItem());
        breakHeld(bot, EquipmentSlot.MAINHAND);
        require(context, bot.getMainHandItem().isEmpty(), "the bow did not break");
        first.ifPresent(lease -> lease.restore(bot));

        Optional<EquipAction.RangedLoadout> second = EquipAction.equipBestRangedLoadout(bot, null);
        require(context, second.isPresent() && bot.getMainHandItem().is(Items.CROSSBOW)
                        && GearValue.enchantmentLevel(bot.getMainHandItem(), "quick_charge") == 3
                        && GearValue.remaining(bot.getMainHandItem()) == 1,
                "after the Power V bow broke the next best was not the Quick Charge III crossbow (at its last use): " + bot.getMainHandItem());
        breakHeld(bot, EquipmentSlot.MAINHAND);
        require(context, bot.getMainHandItem().isEmpty(), "the crossbow did not break");
        second.ifPresent(lease -> lease.restore(bot));

        Optional<EquipAction.RangedLoadout> third = EquipAction.equipBestRangedLoadout(bot, null);
        require(context, third.isPresent() && bot.getMainHandItem().is(Items.BOW) && !bot.getMainHandItem().isEnchanted(),
                "after both enchanted weapons broke the plain bow was not chosen: " + bot.getMainHandItem());
        finish(context, bot);
    }

    // ---------------------------------------------------------------------------------------------------------- ranged

    @GameTest(environment = ENV + "plain_arrows_before_tipped", maxTicks = 40)
    public void plainArrowsBeforeTipped(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearArrowGT");
        bot.getInventory().clearContent();
        bot.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        bot.getInventory().setItem(0, new ItemStack(Items.BOW));
        ItemStack tipped = PotionContents.createItemStack(Items.TIPPED_ARROW, Potions.HARMING);
        tipped.setCount(8);
        bot.getInventory().setItem(1, tipped);
        bot.getInventory().setItem(2, new ItemStack(Items.SPECTRAL_ARROW, 8));
        bot.getInventory().setItem(3, new ItemStack(Items.ARROW, 8));
        Optional<EquipAction.RangedLoadout> loadout = EquipAction.equipBestRangedLoadout(bot, null);
        require(context, loadout.isPresent() && bot.getOffhandItem().is(Items.ARROW),
                "the arrow in the off hand is not the plain one: " + bot.getOffhandItem().getItem());
        // Without a plain arrow the tipped one is used.
        bot.getInventory().setItem(3, ItemStack.EMPTY);
        bot.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        loadout = EquipAction.equipBestRangedLoadout(bot, null);
        require(context, loadout.isPresent() && bot.getOffhandItem().is(Items.TIPPED_ARROW),
                "with no plain arrow the harming arrow was not used: " + bot.getOffhandItem().getItem());
        finish(context, bot);
    }

    // ---------------------------------------------------------------------------------------------------------- helpers

    private static void fill(AIPlayerEntity bot, ItemStack... stacks) {
        bot.getInventory().clearContent();
        for (int i = 0; i < stacks.length; i++) {
            bot.getInventory().setItem(i, stacks[i]);
        }
        bot.getInventory().setItem(8, new ItemStack(Items.DIRT, 4));
        bot.getInventory().setSelectedSlot(8);
    }

    /** One use of the item in {@code slot}, as a hit or a block break spends it: at its last use it breaks (its stack becomes empty). */
    private static void breakHeld(AIPlayerEntity bot, EquipmentSlot slot) {
        bot.getItemBySlot(slot).hurtAndBreak(1, bot, slot);
    }

    private static void clearGear(AIPlayerEntity bot) {
        bot.getInventory().clearContent();
        for (EquipmentSlot slot : ARMOR) {
            bot.setItemSlot(slot, ItemStack.EMPTY);
        }
        bot.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
    }

    private static net.minecraft.core.Holder<Enchantment> enchantment(GameTestHelper context, ResourceKey<Enchantment> key) {
        return context.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(key.identifier()).orElseThrow();
    }

    private static ItemStack enchanted(GameTestHelper context, Item item, ResourceKey<Enchantment> key, int level) {
        ItemStack stack = new ItemStack(item);
        stack.enchant(enchantment(context, key), level);
        return stack;
    }

    private static Zombie spawnZombie(GameTestHelper context, AIPlayerEntity bot, int distance) {
        Zombie zombie = EntityType.ZOMBIE.create(context.getLevel(), EntitySpawnReason.COMMAND);
        if (zombie == null) {
            context.fail(Component.nullToEmpty("failed to create the zombie fixture"));
            throw new IllegalStateException("no zombie");
        }
        zombie.setNoAi(true);
        zombie.setAggressive(true); // the synced flag an observer sees: this zombie counts as an aggressor of the bot
        zombie.setPersistenceRequired();
        BlockPos at = bot.blockPosition().south(distance);
        zombie.snapTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, 0.0F, 0.0F);
        context.getLevel().addFreshEntity(zombie);
        return zombie;
    }

    private static AIPlayerEntity spawnPlatform(GameTestHelper context, String name) {
        var world = context.getLevel();
        world.setDayTime(1000L);
        BlockPos feet = context.absolutePos(new BlockPos(3, 2, 3));
        for (int dx = -3; dx <= 4; dx++) {
            for (int dz = -3; dz <= 5; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                world.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 3; dy++) {
                    world.setBlock(cell.above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet), 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        return bot;
    }

    private static void finish(GameTestHelper context, AIPlayerEntity bot) {
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), bot.getGameProfile().name());
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}

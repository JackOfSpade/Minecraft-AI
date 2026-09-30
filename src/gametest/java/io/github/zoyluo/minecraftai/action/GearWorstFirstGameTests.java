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
 * Worst-first gear on a real server: a bot always uses the cheapest tool, weapon and armor that can still do the job (wooden pickaxe
 * on stone, stone pickaxe on iron ore, iron pickaxe on diamond ore, leather armor over diamond armor), enchantments add value, and
 * nothing escalates: a bot in danger keeps its worst adequate sword and armor. The player controls it by taking items out of the
 * bot's inventory.
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
        // Nearly broken wood is skipped: stone is next.
        ItemStack worn = bot.getInventory().getItem(3);
        worn.setDamageValue(worn.getMaxDamage() - 1);
        bot.getInventory().setSelectedSlot(8);
        ToolSelector.equipBestTool(bot, STONE);
        require(context, bot.getMainHandItem().is(Items.STONE_PICKAXE),
                "a nearly broken wooden pickaxe was not skipped: " + bot.getMainHandItem().getItem());
        bot.getInventory().setSelectedSlot(8);
        ToolSelector.equipMiningChannelTool(bot, STONE);
        require(context, bot.getMainHandItem().is(Items.STONE_PICKAXE),
                "the mission channel used the nearly broken wooden pickaxe: " + bot.getMainHandItem().getItem());
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

    // ---------------------------------------------------------------------------------------------------------- armor

    @GameTest(environment = ENV + "armor_fills_empty_slot_with_worst_piece", maxTicks = 40)
    public void armorFillsEmptySlotWithWorstPiece(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearArmorFillGT");
        clearGear(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_HELMET));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_HELMET));
        InventoryAction.giveItem(bot, new ItemStack(Items.LEATHER_HELMET));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_CHESTPLATE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_CHESTPLATE));
        int changed = EquipAction.autoEquipArmor(bot);
        require(context, changed == 2, "expected two slots to be filled, got " + changed);
        require(context, bot.getItemBySlot(EquipmentSlot.HEAD).is(Items.LEATHER_HELMET),
                "the head slot was not filled with the leather cap: " + bot.getItemBySlot(EquipmentSlot.HEAD).getItem());
        require(context, bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE),
                "the chest slot was not filled with the iron chestplate (worst of iron and diamond): "
                        + bot.getItemBySlot(EquipmentSlot.CHEST).getItem());
        require(context, InventoryAction.countItem(bot, Items.DIAMOND_HELMET) == 1 && InventoryAction.countItem(bot, Items.IRON_HELMET) == 1
                        && InventoryAction.countItem(bot, Items.DIAMOND_CHESTPLATE) == 1 && InventoryAction.countItem(bot, Items.LEATHER_HELMET) == 0,
                "the armor pieces were lost or duplicated");
        require(context, EquipAction.autoEquipArmor(bot) == 0, "a second pass changed something (the choice is not stable)");
        // Enchantments add value: a Protection IV leather chestplate (3 + 4) is worth more than a plain iron one (6).
        clearGear(bot);
        InventoryAction.giveItem(bot, enchanted(context, Items.LEATHER_CHESTPLATE, Enchantments.PROTECTION, 4));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_CHESTPLATE));
        EquipAction.autoEquipArmor(bot);
        require(context, bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE),
                "the Protection IV leather chestplate was worn before the plain iron one: " + bot.getItemBySlot(EquipmentSlot.CHEST));
        finish(context, bot);
    }

    @GameTest(environment = ENV + "auto_worn_diamond_is_swapped_down_to_leather", maxTicks = 40)
    public void autoWornDiamondIsSwappedDownToLeather(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearSwapDownGT");
        clearGear(bot);
        bot.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        InventoryAction.giveItem(bot, new ItemStack(Items.LEATHER_CHESTPLATE));
        int changed = EquipAction.autoEquipArmor(bot);
        require(context, changed == 1 && bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.LEATHER_CHESTPLATE),
                "the worn diamond chestplate was not swapped down to leather: " + bot.getItemBySlot(EquipmentSlot.CHEST).getItem());
        require(context, InventoryAction.countItem(bot, Items.DIAMOND_CHESTPLATE) == 1,
                "the diamond chestplate was lost in the swap");
        finish(context, bot);
    }

    /** No provenance and no exceptions: taking the worse pieces out of the bot's inventory keeps the diamond on; putting one in swaps down. */
    @GameTest(environment = ENV + "player_controls_armor_by_taking_items_out", maxTicks = 40)
    public void playerControlsArmorByTakingItemsOut(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearPlayerControlGT");
        clearGear(bot);
        bot.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        bot.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        require(context, EquipAction.autoEquipArmor(bot) == 0 && bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE)
                        && bot.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET),
                "with nothing worse carried, the worn diamond pieces were taken off");
        // Even a diamond chestplate put on by hand is swapped down as soon as something worse is carried.
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_CHESTPLATE));
        EquipAction.autoEquipArmor(bot);
        require(context, bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE)
                        && bot.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET),
                "the carried iron chestplate was not worn: " + bot.getItemBySlot(EquipmentSlot.CHEST).getItem());
        finish(context, bot);
    }

    @GameTest(environment = ENV + "worn_piece_edge_cases", maxTicks = 40)
    public void wornPieceEdgeCases(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearArmorEdgeGT");
        clearGear(bot);
        // A nearly broken worn piece is replaced by the next worst, not by the best.
        ItemStack tired = new ItemStack(Items.LEATHER_CHESTPLATE);
        tired.setDamageValue(tired.getMaxDamage() - 2);
        bot.setItemSlot(EquipmentSlot.CHEST, tired);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_CHESTPLATE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_CHESTPLATE));
        EquipAction.autoEquipArmor(bot);
        require(context, bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE),
                "a nearly broken leather chestplate was not replaced by the next worst piece: "
                        + bot.getItemBySlot(EquipmentSlot.CHEST).getItem());
        // A nearly broken piece is never put on.
        clearGear(bot);
        ItemStack tiredIron = new ItemStack(Items.IRON_CHESTPLATE);
        tiredIron.setDamageValue(tiredIron.getMaxDamage() - 1);
        InventoryAction.giveItem(bot, tiredIron);
        require(context, EquipAction.autoEquipArmor(bot) == 0 && bot.getItemBySlot(EquipmentSlot.CHEST).isEmpty(),
                "a nearly broken chestplate was put on");
        // A worn Binding Curse piece cannot be taken off.
        clearGear(bot);
        bot.setItemSlot(EquipmentSlot.CHEST, enchanted(context, Items.DIAMOND_CHESTPLATE, Enchantments.BINDING_CURSE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.LEATHER_CHESTPLATE));
        require(context, EquipAction.autoEquipArmor(bot) == 0 && bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE),
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
        // The explicit command stays best-first.
        clearGear(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_CHESTPLATE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_CHESTPLATE));
        EquipAction.equipBestArmor(bot);
        require(context, bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE),
                "equipBestArmor (the equip_armor command) is no longer best-first");
        finish(context, bot);
    }

    // ---------------------------------------------------------------------------------------------------------- weapons

    @GameTest(environment = ENV + "weapon_is_wooden_sword_against_zombie", maxTicks = 40)
    public void weaponIsWoodenSwordAgainstZombie(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearZombieGT");
        Zombie zombie = spawnZombie(context, bot, 2);
        fill(bot, new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.IRON_SWORD), new ItemStack(Items.STONE_SWORD),
                new ItemStack(Items.WOODEN_SWORD));
        OptionalInt slot = EquipAction.adequateWeaponSlot(bot, zombie);
        require(context, slot.isPresent() && bot.getInventory().getItem(slot.getAsInt()).is(Items.WOODEN_SWORD),
                "the worst adequate sword against a zombie is the wooden one: " + slot);
        // Through the context entry point (no aggressor known: the cheapest weapon).
        CombatCore.equipMelee(bot);
        require(context, bot.getMainHandItem().is(Items.WOODEN_SWORD), "held " + bot.getMainHandItem().getItem());
        // A wooden sword that is nearly used up is not adequate: the stone one goes before the diamond one.
        ItemStack tired = new ItemStack(Items.WOODEN_SWORD);
        tired.setDamageValue(tired.getMaxDamage() - 3);
        bot.getInventory().setItem(3, tired);
        slot = EquipAction.adequateWeaponSlot(bot, zombie);
        require(context, slot.isPresent() && bot.getInventory().getItem(slot.getAsInt()).is(Items.STONE_SWORD),
                "a wooden sword with 3 uses left was still adequate against a zombie: " + slot);
        zombie.discard();
        finish(context, bot);
    }

    /** The opposite of escalation: hurt, at 1.5 hearts and outnumbered, the bot keeps its worst adequate sword and armor. */
    @GameTest(environment = ENV + "danger_still_uses_worst_adequate_gear", maxTicks = 40)
    public void dangerStillUsesWorstAdequateGear(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearDangerGT");
        clearGear(bot);
        Zombie first = spawnZombie(context, bot, 2);
        Zombie second = spawnZombie(context, bot, 3);
        bot.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        InventoryAction.giveItem(bot, new ItemStack(Items.LEATHER_CHESTPLATE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_SWORD));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_SWORD));
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        var world = context.getLevel();
        bot.hurtServer(world, world.damageSources().mobAttack(first), 2.0F);
        bot.invulnerableTime = 0;
        bot.hurtServer(world, world.damageSources().mobAttack(second), 2.0F);
        bot.setHealth(3.0F);
        AggroSense.Snapshot snapshot = AggroSense.snapshot(bot);
        require(context, snapshot.pressure() && snapshot.aggressorCount() >= 1,
                "the zombies did not count as aggressors, so this fixture would prove nothing: " + snapshot);
        CombatCore.equipMelee(bot);
        require(context, bot.getMainHandItem().is(Items.WOODEN_SWORD),
                "in danger the bot escalated to " + bot.getMainHandItem().getItem());
        require(context, bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.LEATHER_CHESTPLATE),
                "in danger the bot kept or wore " + bot.getItemBySlot(EquipmentSlot.CHEST).getItem());
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
        OptionalInt slot = EquipAction.adequateWeaponSlot(bot, ravager);
        require(context, slot.isPresent() && bot.getInventory().getItem(slot.getAsInt()).is(Items.DIAMOND_SWORD),
                "no sword is adequate against a 100 health ravager: the best one (diamond) was expected, got " + slot);
        ravager.discard();
        finish(context, bot);
    }

    @GameTest(environment = ENV + "weapon_latch_invalidated_by_inventory_swap", maxTicks = 40)
    public void weaponLatchInvalidatedByInventorySwap(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GearLatchGT");
        fill(bot, new ItemStack(Items.WOODEN_SWORD), new ItemStack(Items.IRON_SWORD));
        EquipAction.equipWeaponForContext(bot);
        require(context, bot.getMainHandItem().is(Items.WOODEN_SWORD), "held " + bot.getMainHandItem().getItem());
        // The stack in the latched slot changes (same tick): the choice must follow at once.
        bot.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        EquipAction.equipWeaponForContext(bot);
        require(context, bot.getMainHandItem().is(Items.IRON_SWORD),
                "a stale latch kept " + bot.getMainHandItem().getItem() + " after the inventory changed");
        // And again when the wooden sword comes back in another slot.
        bot.getInventory().setItem(4, new ItemStack(Items.WOODEN_SWORD));
        EquipAction.equipWeaponForContext(bot);
        require(context, bot.getMainHandItem().is(Items.WOODEN_SWORD),
                "a stale latch kept " + bot.getMainHandItem().getItem() + " after a cheaper sword arrived");
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

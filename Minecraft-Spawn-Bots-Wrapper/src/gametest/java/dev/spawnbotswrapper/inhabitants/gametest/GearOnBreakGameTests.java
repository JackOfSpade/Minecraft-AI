package dev.spawnbotswrapper.inhabitants.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * "Use it until it breaks" for the inhabitants, on a real server with PvP BOT running. PvP BOT ranks armor and melee weapons by
 * item kind only (no durability term anywhere in its selection), so a worn piece or weapon is kept while it is the best one, and
 * when it breaks the next best carried one takes its place:
 * <ul>
 *   <li>armor: {@code BotEquipment.equipBestForSlot} refills the empty slot with the best carried piece of that slot at its next
 *       equipment check (every {@code checkInterval} ticks);</li>
 *   <li>melee: the melee routine re-selects the best hotbar weapon every tick (an empty hand scores zero), and the wrapper keeps every
 *       melee weapon of a loadout in the hotbar.</li>
 * </ul>
 * The wrapper adds nothing of its own for either: these tests prove PvP BOT's own behaviour is the required one, with the wrapper's
 * managed settings (autoEquipWeapon off) in force.
 */
public final class GearOnBreakGameTests {
    private static final String ENV = "pvpbot-inhabitants-gametest:";
    /** PvP BOT's armor check runs every checkInterval (20) ticks; two intervals and a margin. */
    private static final int ARMOR_CHECK_WINDOW = 50;

    private static void require(Rig rig, boolean condition, String message) {
        if (!condition) {
            rig.fail(message);
        }
    }

    /** Uses a piece of equipment up the way play does: hurtAndBreak until the stack is gone. */
    private static void breakSlot(Rig rig, EquipmentSlot slot) {
        for (int i = 0; i < 4000 && !rig.bot.getItemBySlot(slot).isEmpty(); i++) {
            rig.bot.getItemBySlot(slot).hurtAndBreak(1, rig.bot, slot);
        }
    }

    private static ItemStack withOneUseLeft(Item item) {
        ItemStack stack = new ItemStack(item);
        stack.setDamageValue(stack.getMaxDamage() - 1);
        return stack;
    }

    private static boolean carries(Rig rig, Item item) {
        Inventory inv = rig.bot.getInventory();
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).is(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A nearly broken diamond chestplate is worn while the carried iron and chainmail ones are fresh: nothing swaps it for a fresher
     * piece (use it until it breaks). When it breaks the iron chestplate, the best one left, is put on at the next equipment check,
     * and when that breaks the chainmail one.
     */
    @GameTest(environment = ENV + "armor_break_next_best", maxTicks = 600)
    public void armorBreaksThenTheNextBestIsWorn(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        int[] phase = {0};
        long[] since = {0};
        context.onEachTick(() -> {
            if (!rig.requestInhabitant()) {
                return;
            }
            if (phase[0] == 0) {
                if (!rig.inhabitantReady()) {
                    if (context.getTick() > 200) {
                        rig.fail("the inhabitant never appeared");
                    }
                    return;
                }
                require(rig, Boolean.TRUE.equals(Upstream.setting("isAutoEquipArmor")),
                        "PvP BOT's armor auto-equip is not on: " + Upstream.setting("isAutoEquipArmor"));
                Inventory inv = rig.bot.getInventory();
                inv.clearContent();
                inv.setItem(0, new ItemStack(Items.IRON_SWORD));
                inv.setSelectedSlot(0);
                rig.bot.setItemSlot(EquipmentSlot.CHEST, withOneUseLeft(Items.DIAMOND_CHESTPLATE));
                inv.setItem(9, new ItemStack(Items.IRON_CHESTPLATE));
                inv.setItem(10, new ItemStack(Items.CHAINMAIL_CHESTPLATE));
                rig.bot.setHealth(rig.bot.getMaxHealth());
                rig.bot.getFoodData().setFoodLevel(20);
                phase[0] = 1;
                since[0] = context.getTick();
                return;
            }
            long waited = context.getTick() - since[0];
            switch (phase[0]) {
                case 1 -> {
                    // Three equipment checks pass: the nearly broken diamond piece stays on, the fresh iron one stays in the inventory.
                    require(rig, rig.bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE)
                                    && carries(rig, Items.IRON_CHESTPLATE) && carries(rig, Items.CHAINMAIL_CHESTPLATE),
                            "the nearly broken diamond chestplate was swapped away before it broke: worn="
                                    + rig.bot.getItemBySlot(EquipmentSlot.CHEST));
                    if (waited >= 70) {
                        breakSlot(rig, EquipmentSlot.CHEST);
                        phase[0] = 2;
                        since[0] = context.getTick();
                    }
                }
                case 2 -> {
                    if (rig.bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE)) {
                        require(rig, carries(rig, Items.CHAINMAIL_CHESTPLATE) && !carries(rig, Items.IRON_CHESTPLATE),
                                "the iron chestplate was not moved cleanly out of the inventory");
                        breakSlot(rig, EquipmentSlot.CHEST);
                        phase[0] = 3;
                        since[0] = context.getTick();
                    } else if (waited > ARMOR_CHECK_WINDOW) {
                        rig.fail("the diamond chestplate broke but the chest slot is " + rig.bot.getItemBySlot(EquipmentSlot.CHEST)
                                + ", not the iron one, " + waited + " ticks later");
                    }
                }
                case 3 -> {
                    if (rig.bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.CHAINMAIL_CHESTPLATE)) {
                        rig.succeed();
                    } else if (waited > ARMOR_CHECK_WINDOW) {
                        rig.fail("the iron chestplate broke but the chest slot is " + rig.bot.getItemBySlot(EquipmentSlot.CHEST)
                                + ", not the chainmail one, " + waited + " ticks later");
                    }
                }
                default -> rig.fail("unexpected phase " + phase[0]);
            }
        });
    }

    /**
     * A bot fights a player with three swords in its hotbar: a diamond and an iron one with a single use left each, and a fresh stone
     * one. The best sword is held and used until a hit breaks it (it is never set aside for a fresher one), then the next best one is
     * in hand and keeps fighting, and when that breaks too the stone one.
     */
    @GameTest(environment = ENV + "weapon_break_next_best", maxTicks = 900)
    public void meleeWeaponBreaksThenTheNextBestIsHeld(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(2.0);
        int[] phase = {0};
        long[] since = {0};
        boolean[] heldDiamond = {false};
        context.onEachTick(() -> {
            if (!rig.requestInhabitant()) {
                return;
            }
            if (phase[0] == 0) {
                if (!rig.inhabitantReady()) {
                    if (context.getTick() > 200) {
                        rig.fail("the inhabitant never appeared");
                    }
                    return;
                }
                require(rig, Boolean.FALSE.equals(Upstream.setting("isAutoEquipWeapon")),
                        "PvP BOT's weapon auto-equip is not managed off: " + Upstream.setting("isAutoEquipWeapon"));
                Inventory inv = rig.bot.getInventory();
                inv.clearContent();
                inv.setItem(0, withOneUseLeft(Items.DIAMOND_SWORD));
                inv.setItem(1, withOneUseLeft(Items.IRON_SWORD));
                inv.setItem(2, new ItemStack(Items.STONE_SWORD));
                inv.setSelectedSlot(0);
                rig.bot.setHealth(rig.bot.getMaxHealth());
                rig.bot.getFoodData().setFoodLevel(20);
                rig.faceTarget();
                phase[0] = 1;
                since[0] = context.getTick();
                return;
            }
            rig.keepTargetAlive();
            if (Upstream.target(rig.botName).equals("none")) {
                rig.forceTarget();
            }
            long waited = context.getTick() - since[0];
            ItemStack held = rig.bot.getMainHandItem();
            switch (phase[0]) {
                case 1 -> {
                    if (carries(rig, Items.DIAMOND_SWORD)) {
                        require(rig, held.is(Items.DIAMOND_SWORD),
                                "the nearly broken diamond sword was put aside before it broke: held=" + held);
                        heldDiamond[0] = true;
                    } else {
                        require(rig, heldDiamond[0], "the diamond sword vanished without ever being held");
                        phase[0] = 2; // a hit used its last use up
                        since[0] = context.getTick();
                    }
                    if (waited > 400) {
                        rig.fail("the diamond sword never broke: held=" + held + " " + Upstream.combatState(rig.botName));
                    }
                }
                case 2 -> {
                    if (held.is(Items.IRON_SWORD)) {
                        phase[0] = 3;
                        since[0] = context.getTick();
                    } else if (waited > 40) {
                        rig.fail("the diamond sword broke but the hand holds " + held + ", not the iron sword, " + waited
                                + " ticks later");
                    }
                }
                case 3 -> {
                    if (carries(rig, Items.IRON_SWORD)) {
                        require(rig, held.is(Items.IRON_SWORD),
                                "the nearly broken iron sword was put aside for the fresh stone one before it broke: held=" + held);
                    } else {
                        phase[0] = 4; // a hit used its last use up
                        since[0] = context.getTick();
                    }
                    if (waited > 400) {
                        rig.fail("the iron sword never broke: held=" + held + " " + Upstream.combatState(rig.botName));
                    }
                }
                case 4 -> {
                    if (held.is(Items.STONE_SWORD)) {
                        rig.succeed();
                    } else if (waited > 40) {
                        rig.fail("the iron sword broke but the hand holds " + held + ", not the stone sword, " + waited
                                + " ticks later");
                    }
                }
                default -> rig.fail("unexpected phase " + phase[0]);
            }
        });
    }
}

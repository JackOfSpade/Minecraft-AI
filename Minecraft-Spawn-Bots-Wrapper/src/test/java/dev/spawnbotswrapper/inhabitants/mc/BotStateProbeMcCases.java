package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.combat.StateSnapshot;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The item-level parts of {@link BotStateProbe} against real items and components: ammo counts across the
 * inventory, weapon detection, item names and the crossbow charged state. (A whole player entity needs a running
 * server; the entity getters are one-line calls covered by the real-server run.)
 */
public final class BotStateProbeMcCases {
    private BotStateProbeMcCases() {
    }

    public static void talliesAmmoAndWeaponsAcrossTheInventory() {
        McBootstrap.ensure();
        BotStateProbe.Tally t = BotStateProbe.tally(List.of(
                new ItemStack(Items.ARROW, 20), new ItemStack(Items.SPECTRAL_ARROW, 5),
                new ItemStack(Items.TIPPED_ARROW, 3), new ItemStack(Items.FIREWORK_ROCKET, 7),
                new ItemStack(Items.BOW), new ItemStack(Items.CROSSBOW), new ItemStack(Items.IRON_SWORD),
                ItemStack.EMPTY));
        assertEquals(28, t.arrows());
        assertEquals(7, t.rockets());
        assertTrue(t.hasBow());
        assertTrue(t.hasCrossbow());
        assertTrue(t.hasMelee());
    }

    public static void anInventoryWithoutWeaponsSaysSo() {
        McBootstrap.ensure();
        BotStateProbe.Tally t = BotStateProbe.tally(List.of(new ItemStack(Items.BREAD, 4), ItemStack.EMPTY));
        assertEquals(0, t.arrows());
        assertEquals(0, t.rockets());
        assertFalse(t.hasBow());
        assertFalse(t.hasCrossbow());
        assertFalse(t.hasMelee());
    }

    public static void everyMeleeFamilyCounts() {
        McBootstrap.ensure();
        for (var item : List.of(Items.WOODEN_SWORD, Items.NETHERITE_SWORD, Items.STONE_AXE, Items.IRON_SPEAR,
                Items.MACE, Items.TRIDENT)) {
            assertTrue(BotStateProbe.isMelee(new ItemStack(item)), item.toString());
        }
        assertFalse(BotStateProbe.isMelee(new ItemStack(Items.BOW)));
        assertFalse(BotStateProbe.isMelee(new ItemStack(Items.SHIELD)));
    }

    public static void itemNamesAreRegistryPaths() {
        McBootstrap.ensure();
        assertEquals("crossbow", BotStateProbe.itemName(new ItemStack(Items.CROSSBOW)));
        assertEquals("empty", BotStateProbe.itemName(ItemStack.EMPTY));
        assertEquals("empty", BotStateProbe.itemName(null));
    }

    public static void aCrossbowIsChargedOnlyWithLoadedProjectiles() {
        McBootstrap.ensure();
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        assertFalse(net.minecraft.world.item.CrossbowItem.isCharged(crossbow));
        crossbow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(new ItemStack(Items.ARROW)));
        assertTrue(net.minecraft.world.item.CrossbowItem.isCharged(crossbow));
    }

    public static void upstreamTextNamesTheGlobalSwitchesOnlyTheTargetIsReadPerBot() {
        McBootstrap.ensure();
        assertNull(BotStateProbe.upstreamText(null));
        String text = BotStateProbe.upstreamText(
                dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities.upstreamDefaults());
        assertNotNull(text);
        assertTrue(text.contains("combat=") && text.contains("autoTarget=") && text.contains("ranged="), text);
        assertFalse(text.contains("target"), text);
        assertEquals("target=unreadable", BotStateProbe.intentText(java.util.Optional.empty()));
        assertEquals("target=none mode=MELEE draw=0", BotStateProbe.intentText(java.util.Optional.of(
                new dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations.CombatView(null, "MELEE", false, 0))));
        // the snapshot type accepts it and prints it
        StateSnapshot s = new StateSnapshot(0, "empty", null, "empty", false, "none", 0, 0, 0, 0, false, false,
                false, null, -1, null, true, false, false, text);
        assertTrue(s.format().endsWith("pvpbot=" + text));
    }
}

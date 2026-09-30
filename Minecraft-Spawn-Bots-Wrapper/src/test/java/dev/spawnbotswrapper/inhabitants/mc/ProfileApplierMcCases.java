package dev.spawnbotswrapper.inhabitants.mc;

import com.mojang.authlib.GameProfile;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.ItemSpec;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.PlacedItem;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.Slot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.*;

/** The vanilla-facing halves of {@link ProfileApplier}, run inside {@link McSandbox}. */
public final class ProfileApplierMcCases {
    private ProfileApplierMcCases() {
    }

    private static Inventory newInventory() {
        return new Inventory(null, new EntityEquipment());
    }

    private static BotProfile.Loadout kit() {
        return new BotProfile.Loadout(List.of(
                new PlacedItem(Slot.HEAD, 0, ItemSpec.of("minecraft:diamond_helmet")),
                new PlacedItem(Slot.CHEST, 0, ItemSpec.of("minecraft:diamond_chestplate")),
                new PlacedItem(Slot.LEGS, 0, ItemSpec.of("minecraft:diamond_leggings")),
                new PlacedItem(Slot.FEET, 0, ItemSpec.of("minecraft:diamond_boots")),
                new PlacedItem(Slot.OFFHAND, 0, ItemSpec.of("minecraft:shield")),
                new PlacedItem(Slot.HOTBAR, 0, ItemSpec.of("minecraft:diamond_sword")),
                new PlacedItem(Slot.HOTBAR, 1, ItemSpec.of("minecraft:golden_apple", 8)),
                new PlacedItem(Slot.INVENTORY, -1, ItemSpec.of("minecraft:cooked_beef", 32))));
    }

    /** The slot numbers the planner uses are the ones vanilla's player inventory really has. */
    public static void slotConstantsMatchTheGame() {
        assertEquals(EquipmentSlot.FEET.getIndex(36), SlotPlanner.FEET);
        assertEquals(EquipmentSlot.LEGS.getIndex(36), SlotPlanner.LEGS);
        assertEquals(EquipmentSlot.CHEST.getIndex(36), SlotPlanner.CHEST);
        assertEquals(EquipmentSlot.HEAD.getIndex(36), SlotPlanner.HEAD);
        assertEquals(Inventory.SLOT_OFFHAND, SlotPlanner.OFFHAND);
        assertEquals(Inventory.INVENTORY_SIZE, SlotPlanner.MAIN_LAST + 1);
        assertEquals(Inventory.getSelectionSize(), SlotPlanner.HOTBAR_LAST + 1);
        Inventory inventory = newInventory();
        for (int slot = 0; slot < SlotPlanner.SLOT_COUNT; slot++) {
            inventory.setItem(slot, new ItemStack(Items.STONE));
            assertTrue(inventory.getItem(slot).is(Items.STONE), "slot " + slot + " must be writable");
        }
    }

    /** Every pearl goes from every kind of slot; everything else, including other throwables, stays exactly as it was. */
    public static void removeEnderPearlsTakesOnlyPearlsFromEverySlotAndCountsThem() {
        Inventory inventory = newInventory();
        ProfileApplier.fill(inventory, McBootstrap.registries(), kit(), true, new ArrayList<>());
        inventory.setItem(2, new ItemStack(Items.ENDER_PEARL, 16));          // hotbar
        inventory.setItem(20, new ItemStack(Items.ENDER_PEARL, 3));          // main inventory
        inventory.setItem(21, new ItemStack(Items.ENDER_EYE, 4));            // look-alike, must stay
        inventory.setItem(22, new ItemStack(Items.SNOWBALL, 16));            // other throwable, must stay
        inventory.setItem(SlotPlanner.OFFHAND, new ItemStack(Items.ENDER_PEARL, 1)); // offhand replaces the shield
        List<ItemStack> before = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            before.add(inventory.getItem(slot).copy());
        }

        assertEquals(20, ProfileApplier.removeEnderPearls(inventory));

        assertTrue(inventory.getItem(2).isEmpty());
        assertTrue(inventory.getItem(20).isEmpty());
        assertTrue(inventory.getItem(SlotPlanner.OFFHAND).isEmpty());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (before.get(slot).is(Items.ENDER_PEARL)) {
                continue;
            }
            assertTrue(ItemStack.matches(before.get(slot), inventory.getItem(slot)), "slot " + slot + " must be untouched");
        }
        assertTrue(inventory.getItem(21).is(Items.ENDER_EYE));
        assertEquals(4, inventory.getItem(21).getCount());
        assertTrue(inventory.getItem(22).is(Items.SNOWBALL));
        assertEquals(0, ProfileApplier.removeEnderPearls(inventory), "idempotent: a second pass finds nothing");
    }

    public static void aBotWithoutPearlsIsLeftAlone() {
        Inventory inventory = newInventory();
        ProfileApplier.fill(inventory, McBootstrap.registries(), kit(), true, new ArrayList<>());
        assertEquals(0, ProfileApplier.removeEnderPearls(inventory));
        assertTrue(inventory.getItem(0).is(Items.DIAMOND_SWORD));
        assertTrue(inventory.getItem(SlotPlanner.OFFHAND).is(Items.SHIELD));
    }

    /** Old stored profiles (and unwiped inventories) carry pearls; a dressing never leaves any and says so. */
    public static void dressingNeverLeavesPearlsFromAStoredProfileOrTheOldInventory() {
        BotProfile.Loadout old = new BotProfile.Loadout(List.of(
                new PlacedItem(Slot.HOTBAR, 0, ItemSpec.of("minecraft:diamond_sword")),
                new PlacedItem(Slot.INVENTORY, -1, ItemSpec.of("minecraft:ender_pearl", 6))));
        Inventory inventory = newInventory();
        inventory.setItem(30, new ItemStack(Items.ENDER_PEARL, 2)); // not a planned slot, survives a non-clearing fill
        List<String> warnings = new ArrayList<>();
        ProfileApplier.fill(inventory, McBootstrap.registries(), old, false, warnings);
        assertEquals(0, ProfileApplier.removeEnderPearls(inventory), "nothing left to remove");
        assertTrue(inventory.getItem(0).is(Items.DIAMOND_SWORD));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("8 ender pearls"), warnings.get(0));
    }

    public static void aLoadoutLandsInTheRightVanillaSlots() {
        Inventory inventory = newInventory();
        List<String> warnings = new ArrayList<>();
        ProfileApplier.fill(inventory, McBootstrap.registries(), kit(), true, warnings);

        assertTrue(inventory.getItem(SlotPlanner.HEAD).is(Items.DIAMOND_HELMET));
        assertTrue(inventory.getItem(SlotPlanner.CHEST).is(Items.DIAMOND_CHESTPLATE));
        assertTrue(inventory.getItem(SlotPlanner.LEGS).is(Items.DIAMOND_LEGGINGS));
        assertTrue(inventory.getItem(SlotPlanner.FEET).is(Items.DIAMOND_BOOTS));
        assertTrue(inventory.getItem(SlotPlanner.OFFHAND).is(Items.SHIELD));
        assertTrue(inventory.getItem(0).is(Items.DIAMOND_SWORD));
        assertTrue(inventory.getItem(1).is(Items.GOLDEN_APPLE));
        assertEquals(8, inventory.getItem(1).getCount());
        assertTrue(inventory.getItem(9).is(Items.COOKED_BEEF));
        assertEquals(32, inventory.getItem(9).getCount());
        assertEquals(0, inventory.getSelectedSlot());
        assertTrue(inventory.getSelectedItem().is(Items.DIAMOND_SWORD), "the bot holds the first hotbar item");
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    public static void theSelectedSlotIsResetToTheFirstHotbarSlot() {
        Inventory inventory = newInventory();
        inventory.setSelectedSlot(6);
        ProfileApplier.fill(inventory, McBootstrap.registries(), kit(), true, new ArrayList<>());
        assertEquals(0, inventory.getSelectedSlot());
    }

    public static void clearingWipesEverythingElseButNotClearingOnlyOverwritesPlannedSlots() {
        Inventory withJunk = newInventory();
        withJunk.setItem(20, new ItemStack(Items.DIRT, 5));
        withJunk.setItem(SlotPlanner.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
        withJunk.setItem(SlotPlanner.OFFHAND, new ItemStack(Items.TORCH, 3));
        ProfileApplier.fill(withJunk, McBootstrap.registries(), kit(), true, new ArrayList<>());
        assertTrue(withJunk.getItem(20).isEmpty(), "cleared");
        assertTrue(withJunk.getItem(SlotPlanner.CHEST).is(Items.DIAMOND_CHESTPLATE));

        Inventory kept = newInventory();
        kept.setItem(20, new ItemStack(Items.DIRT, 5));
        kept.setItem(SlotPlanner.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
        ProfileApplier.fill(kept, McBootstrap.registries(), kit(), false, new ArrayList<>());
        assertTrue(kept.getItem(20).is(Items.DIRT), "an unrelated slot is left alone without clearing");
        assertTrue(kept.getItem(SlotPlanner.CHEST).is(Items.DIAMOND_CHESTPLATE), "a planned slot is overwritten");
    }

    public static void applyingTwiceWithoutClearingIsIdempotent() {
        Inventory once = newInventory();
        ProfileApplier.fill(once, McBootstrap.registries(), kit(), true, new ArrayList<>());
        Inventory twice = newInventory();
        ProfileApplier.fill(twice, McBootstrap.registries(), kit(), true, new ArrayList<>());
        ProfileApplier.fill(twice, McBootstrap.registries(), kit(), false, new ArrayList<>());
        for (int slot = 0; slot < SlotPlanner.SLOT_COUNT; slot++) {
            assertTrue(ItemStack.matches(once.getItem(slot), twice.getItem(slot)), "slot " + slot);
        }
    }

    public static void badItemsLeaveTheirSlotEmptyWithWarningsAndTheRestIsStillApplied() {
        Inventory inventory = newInventory();
        List<String> warnings = new ArrayList<>();
        BotProfile.Loadout loadout = new BotProfile.Loadout(List.of(
                new PlacedItem(Slot.HEAD, 0, ItemSpec.of("somemod:crown")),
                new PlacedItem(Slot.HOTBAR, 0, ItemSpec.of("minecraft:iron_sword")),
                new PlacedItem("belt", 0, ItemSpec.of("minecraft:apple"))));
        ProfileApplier.fill(inventory, McBootstrap.registries(), loadout, true, warnings);
        assertTrue(inventory.getItem(SlotPlanner.HEAD).isEmpty());
        assertTrue(inventory.getItem(0).is(Items.IRON_SWORD));
        assertTrue(inventory.getItem(9).is(Items.APPLE), "an unknown slot name falls back to a free slot");
        assertEquals(2, warnings.size(), warnings.toString());
    }

    public static void anEmptyLoadoutClearsAFreshBotAndSelectsSlotZero() {
        Inventory inventory = newInventory();
        inventory.setItem(3, new ItemStack(Items.DIRT));
        ProfileApplier.fill(inventory, McBootstrap.registries(), new BotProfile.Loadout(List.of()), true, new ArrayList<>());
        assertTrue(inventory.getItem(3).isEmpty());
        assertEquals(0, inventory.getSelectedSlot());
    }

    // ------------------------------------------------------------------------------- attribute modifiers

    private static AttributeInstance maxHealth() {
        return new AttributeInstance(Attributes.MAX_HEALTH, instance -> {
        });
    }

    private static Identifier modifierId(String path) {
        return Identifier.fromNamespaceAndPath("pvpbot_inhabitants", path);
    }

    public static void aModifierIsInstalledUnderTheFixedAddonId() {
        AttributeInstance health = maxHealth();
        List<String> warnings = new ArrayList<>();
        assertTrue(ProfileApplier.install(health, Identifier.parse("minecraft:max_health"),
                new BotProfile.AttributeMod(BotProfile.Op.ADD_VALUE, 10.0), warnings));
        assertEquals(30.0, health.getValue(), 1e-9);
        assertEquals(20.0, health.getBaseValue(), 1e-9, "the base value is never touched");
        assertTrue(health.hasModifier(modifierId("profile/max_health")));
        assertEquals(1, health.getPermanentModifiers().size(), "persistent, so it is saved with the player");
        assertTrue(warnings.isEmpty());
    }

    public static void applyingAgainReplacesTheModifierInsteadOfStackingOrThrowing() {
        AttributeInstance health = maxHealth();
        Identifier id = Identifier.parse("minecraft:max_health");
        ProfileApplier.install(health, id, new BotProfile.AttributeMod(BotProfile.Op.ADD_VALUE, 10.0), new ArrayList<>());
        ProfileApplier.install(health, id, new BotProfile.AttributeMod(BotProfile.Op.ADD_VALUE, 10.0), new ArrayList<>());
        assertEquals(30.0, health.getValue(), 1e-9, "same profile twice = same result");
        ProfileApplier.install(health, id, new BotProfile.AttributeMod(BotProfile.Op.ADD_VALUE, -4.0), new ArrayList<>());
        assertEquals(16.0, health.getValue(), 1e-9, "a different value replaces the old one");
        assertEquals(1, health.getModifiers().size());
    }

    public static void allThreeOperationsAreSupported() {
        AttributeInstance base = maxHealth();
        ProfileApplier.install(base, Identifier.parse("minecraft:max_health"),
                new BotProfile.AttributeMod(BotProfile.Op.ADD_MULTIPLIED_BASE, 0.5), new ArrayList<>());
        assertEquals(30.0, base.getValue(), 1e-9);

        AttributeInstance total = maxHealth();
        ProfileApplier.install(total, Identifier.parse("minecraft:max_health"),
                new BotProfile.AttributeMod(BotProfile.Op.ADD_MULTIPLIED_TOTAL, -0.5), new ArrayList<>());
        assertEquals(10.0, total.getValue(), 1e-9);

        assertEquals(AttributeModifier.Operation.ADD_VALUE, ProfileApplier.operationOf("add_value"));
        assertEquals(AttributeModifier.Operation.ADD_MULTIPLIED_BASE, ProfileApplier.operationOf("add_multiplied_base"));
        assertEquals(AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, ProfileApplier.operationOf("add_multiplied_total"));
    }

    public static void moddedAttributesGetTheirNamespaceInTheModifierId() {
        AttributeInstance health = maxHealth();
        ProfileApplier.install(health, Identifier.parse("somemod:vigor"),
                new BotProfile.AttributeMod(BotProfile.Op.ADD_VALUE, 1.0), new ArrayList<>());
        assertTrue(health.hasModifier(modifierId("profile/somemod/vigor")));
    }

    public static void badModifiersAreRejectedWithoutChangingAnything() {
        AttributeInstance health = maxHealth();
        List<String> warnings = new ArrayList<>();
        Identifier id = Identifier.parse("minecraft:max_health");
        assertFalse(ProfileApplier.install(health, id, new BotProfile.AttributeMod("multiply", 2.0), warnings));
        assertFalse(ProfileApplier.install(health, id, new BotProfile.AttributeMod(null, 2.0), warnings));
        assertFalse(ProfileApplier.install(health, id, new BotProfile.AttributeMod(BotProfile.Op.ADD_VALUE, Double.NaN), warnings));
        assertFalse(ProfileApplier.install(health, id, new BotProfile.AttributeMod(BotProfile.Op.ADD_VALUE, Double.POSITIVE_INFINITY), warnings));
        assertEquals(3 + 1, warnings.size(), warnings.toString());
        assertEquals(20.0, health.getValue(), 1e-9);
        assertTrue(health.getModifiers().isEmpty());
        assertNull(ProfileApplier.operationOf("nonsense"));
        assertNull(ProfileApplier.operationOf(null));
    }

    public static void startingHealthIsAFractionOfMaxButNeverBelowHalfAHeart() {
        assertEquals(10.0f, ProfileApplier.initialHealth(20.0f, 0.5), 1e-6);
        assertEquals(20.0f, ProfileApplier.initialHealth(20.0f, 1.0), 1e-6);
        assertEquals(1.0f, ProfileApplier.initialHealth(20.0f, 0.05), 1e-6, "0.05 * 20 = 1");
        assertEquals(1.0f, ProfileApplier.initialHealth(20.0f, 0.0), 1e-6, "never born dying");
        assertEquals(1.0f, ProfileApplier.initialHealth(2.0f, 0.05), 1e-6);
        assertEquals(0.5f, ProfileApplier.initialHealth(0.5f, 1.0), 1e-6, "never above the maximum");
        assertEquals(15.0f, ProfileApplier.initialHealth(30.0f, 0.5), 1e-6);
    }

    // ------------------------------------------------------------------------------- guards on the entity

    private static ServerPlayer opaquePlayer(String name) {
        ServerPlayer player = McObjects.opaque(ServerPlayer.class);
        McObjects.setField(player, Player.class, "gameProfile", new GameProfile(UUID.randomUUID(), name));
        McObjects.setField(player, Entity.class, "tags", new HashSet<String>());
        return player;
    }

    /**
     * The applier is the only thing that ever writes to an entity, and it refuses anything that is not a bot.
     * The entity here has no inventory, no attributes and no world: touching any of them would throw, so a
     * clean refusal proves nothing else was called.
     */
    public static void aRealPlayerIsNeverTouched() {
        AtomicInteger asked = new AtomicInteger();
        ProfileApplier applier = new ProfileApplier(player -> {
            asked.incrementAndGet();
            return false;
        });
        ServerPlayer player = opaquePlayer("SomeRealPerson");
        ProfileApplication.Result result = applier.apply(player, new BotProfile(1, 1L, "x", null, null, null), true);
        assertFalse(result.loadoutApplied());
        assertFalse(result.vitalsApplied());
        assertEquals(1, asked.get());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("SomeRealPerson"), result.warnings().get(0));
        assertTrue(result.warnings().get(0).contains("not a bot"), result.warnings().get(0));
    }

    public static void applyNeverThrowsEvenWhenTheEntityIsUnusable() {
        // The bot check passes but the entity has no inventory, attributes or world: every section fails inside.
        ProfileApplier broken = new ProfileApplier(p -> true);
        ProfileApplication.Result result = broken.apply(opaquePlayer("Inh_Broken"), new BotProfile(1, 1L, "x", null, null, null), true);
        assertFalse(result.loadoutApplied());
        assertFalse(result.vitalsApplied());
        assertFalse(result.warnings().isEmpty());

        // A bot check that itself throws is contained as well.
        ProfileApplier throwing = new ProfileApplier(p -> {
            throw new IllegalStateException("adapter broke");
        });
        ProfileApplication.Result refused = throwing.apply(opaquePlayer("Inh_Other"), new BotProfile(1, 1L, "x", null, null, null), true);
        assertFalse(refused.loadoutApplied());
        assertTrue(refused.warnings().get(0).contains("adapter broke"), refused.warnings().toString());
    }

    public static void theMarkerRoundTripsThroughTheEntitysCommandTags() {
        ProfileApplier applier = new ProfileApplier(p -> true);
        ServerPlayer bot = opaquePlayer("InhTest");
        assertFalse(applier.isMarked(bot));
        applier.mark(bot);
        assertTrue(applier.isMarked(bot));
        assertTrue(bot.getTags().contains("pvpbot_inhabitants"));
        applier.mark(bot);
        assertEquals(1, bot.getTags().size(), "marking twice adds nothing");
        assertEquals("pvpbot_inhabitants", ProfileApplier.MARKER_TAG);
    }

    public static void aProfileWithNoContentStillHasItemsMapAndAttributeMapSafeToIterate() {
        BotProfile profile = new BotProfile(1, 7L, "", null, null, null);
        assertTrue(profile.loadout().items().isEmpty());
        assertTrue(profile.vitals().attributes().isEmpty());
        assertEquals(Map.of(), profile.vitals().attributes());
    }
}

package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.HumanAim;
import io.github.zoyluo.minecraftai.action.InteractAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import java.util.OptionalInt;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.phys.Vec3;

/**
 * Uses one carried potion of healing through the ordinary player-use path.
 *
 * <p>It intentionally accepts only drinkable and splash healing potions. Lingering potions are
 * not a reliable self-heal in a one-cell shelter, and no arbitrary beneficial potion should make
 * the shelter claim that the bot has a way to restore hit points.</p>
 */
final class HealingPotionTask extends AbstractTask {
    private static final int USE_TIMEOUT_TICKS = 80;
    /** Do not let a low configured aim speed postpone a sealed-shelter self-heal indefinitely. */
    private static final int SPLASH_AIM_TIMEOUT_TICKS = 20;

    private enum Phase {
        FINDING,
        AIMING_SPLASH,
        USING,
        WAITING
    }

    private Phase phase = Phase.FINDING;
    /** Combat may use only the immediate thrown form; drinking is reserved for a sealed shelter. */
    private final boolean splashOnly;
    private final String startLogEvent;
    private boolean splash;
    private Item usedItem;
    private int waitTicks;
    private int aimTicks;

    HealingPotionTask() {
        this(false, "shelter_healing_potion_started");
    }

    private HealingPotionTask(boolean splashOnly, String startLogEvent) {
        this.splashOnly = splashOnly;
        this.startLogEvent = startLogEvent;
    }

    /** A combat recovery may throw an instant-health bottle, but must never stop to drink one. */
    static HealingPotionTask splashOnlyForCombat() {
        return new HealingPotionTask(true, "combat_splash_healing_started");
    }

    @Override
    public String name() {
        return "healing_potion";
    }

    @Override
    public String describe() {
        return "Using a " + (splash ? "splash" : "drinkable") + " healing potion; phase=" + phase;
    }

    @Override
    public double progress() {
        return switch (phase) {
            case FINDING -> 0.0D;
            case AIMING_SPLASH -> 0.2D;
            case USING -> 0.4D;
            case WAITING -> Math.min(0.95D, 0.4D + waitTicks / (double) USE_TIMEOUT_TICKS * 0.55D);
        };
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.FINDING;
        splash = false;
        usedItem = null;
        waitTicks = 0;
        aimTicks = 0;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        switch (phase) {
            case FINDING -> findPotion(bot);
            case AIMING_SPLASH -> aimAndUseSplash(bot);
            case USING -> usePotion(bot);
            case WAITING -> waitForUse(bot);
        }
    }

    private void findPotion(AIPlayerEntity bot) {
        int slot = findHealingPotionSlot(bot, splashOnly);
        if (slot < 0) {
            fail("no_healing_potion");
            return;
        }
        int hotbar = InventoryAction.equipFromSlot(bot, slot);
        if (hotbar < 0) {
            fail("equip_healing_potion_failed");
            return;
        }
        ItemStack stack = bot.getItemInHand(InteractionHand.MAIN_HAND);
        if (!isEligibleHealingPotion(stack, splashOnly)) {
            fail("healing_potion_slot_changed");
            return;
        }
        splash = stack.is(Items.SPLASH_POTION);
        usedItem = stack.getItem();
        phase = splash ? Phase.AIMING_SPLASH : Phase.USING;
    }

    /** Aim splash bottles into the shelter floor, keeping their entire effect inside the safe cell. */
    private void aimAndUseSplash(AIPlayerEntity bot) {
        Vec3 feetCenter = Vec3.atCenterOf(bot.blockPosition()).add(0.0D, 0.05D, 0.0D);
        aimTicks++;
        // Ten degrees still lands well inside a one-cell shelter. The timeout makes recovery
        // progress even when a very slow aim configuration or its human-settle jitter prevents a
        // perfect downward crosshair.
        if (HumanAim.lookToward(bot, feetCenter) > 10.0D && aimTicks < SPLASH_AIM_TIMEOUT_TICKS) {
            return;
        }
        phase = Phase.USING;
    }

    private void usePotion(AIPlayerEntity bot) {
        ActionResult result = InteractAction.useItemInAir(bot, InteractionHand.MAIN_HAND);
        if (result.isFailed()) {
            fail(result.reason());
            return;
        }
        waitTicks = 0;
        phase = Phase.WAITING;
        BotLog.action(bot, startLogEvent, "splash", splash);
    }

    private void waitForUse(AIPlayerEntity bot) {
        waitTicks++;
        ItemStack hand = bot.getItemInHand(InteractionHand.MAIN_HAND);
        // A splash bottle is consumed by the throw; a drinkable bottle is consumed only after its
        // normal use duration. Comparing the item (rather than stack count) also handles the
        // glass bottle that replaces a one-count potion stack.
        if (usedItem != null && !hand.is(usedItem)) {
            complete();
            return;
        }
        if (!splash && !bot.isUsingItem() && waitTicks > 5) {
            fail("healing_potion_not_consumed");
            return;
        }
        if (waitTicks > USE_TIMEOUT_TICKS) {
            bot.stopUsingItem();
            fail("healing_potion_timeout");
        }
    }

    /** Side-effect-free check used by the shelter before it asks the player for a rescue. */
    static boolean hasHealingPotion(AIPlayerEntity bot) {
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            if (isHealingPotion(stack)) {
                return true;
            }
        }
        return isHealingPotion(bot.getItemBySlot(EquipmentSlot.OFFHAND));
    }

    /** Side-effect-free check used by CombatTask before it begins an immediate self-throw. */
    static boolean hasSplashHealingPotion(AIPlayerEntity bot) {
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            if (isSplashHealingPotion(stack)) {
                return true;
            }
        }
        return isSplashHealingPotion(bot.getItemBySlot(EquipmentSlot.OFFHAND));
    }

    private static int findHealingPotionSlot(AIPlayerEntity bot, boolean splashOnly) {
        Inventory inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
            if (isEligibleHealingPotion(inventory.getNonEquipmentItems().get(slot), splashOnly)) {
                return slot;
            }
        }
        ItemStack offhand = bot.getItemBySlot(EquipmentSlot.OFFHAND);
        if (!isEligibleHealingPotion(offhand, splashOnly)) {
            return -1;
        }
        OptionalInt promoted = InventoryAction.promoteOffhandSlot(bot, 0);
        return promoted.orElse(-1);
    }

    static boolean isHealingPotion(ItemStack stack) {
        if (stack.isEmpty() || (!stack.is(Items.POTION) && !stack.is(Items.SPLASH_POTION))) {
            return false;
        }
        PotionContents contents = stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        for (MobEffectInstance effect : contents.getAllEffects()) {
            if (effect.getEffect().is(MobEffects.INSTANT_HEALTH)) {
                return true;
            }
        }
        return false;
    }

    static boolean isSplashHealingPotion(ItemStack stack) {
        return stack.is(Items.SPLASH_POTION) && isHealingPotion(stack);
    }

    private static boolean isEligibleHealingPotion(ItemStack stack, boolean splashOnly) {
        return splashOnly ? isSplashHealingPotion(stack) : isHealingPotion(stack);
    }
}

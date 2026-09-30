package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;

/**
 * The one ranged-weapon layer every shooting caller goes through: a bow and a crossbow are two strategies of the same cycle,
 * <b>begin, tick, ready, release, cancel</b>, and every step is the vanilla item-use path (no invented rate, no invented ammo).
 *
 * <table>
 *   <caption>The two strategies</caption>
 *   <tr><th></th><th>bow</th><th>crossbow</th></tr>
 *   <tr><td>begin</td><td>{@code gameMode.useItem} draws</td><td>{@code gameMode.useItem} draws (loads)</td></tr>
 *   <tr><td>ready</td><td>vanilla {@link BowItem#getPowerForTime} reaches a full draw (20 ticks)</td>
 *       <td>the draw reached {@link CrossbowItem#getChargeDuration} (Quick Charge aware); vanilla then loads the crossbow on
 *       release and {@link CrossbowItem#isCharged} holds</td></tr>
 *   <tr><td>release</td><td>{@code releaseUsingItem} fires the arrow</td>
 *       <td>{@code releaseUsingItem} ends the draw and loads it (the arrow is consumed here); a second
 *       {@code gameMode.useItem} fires the loaded crossbow</td></tr>
 *   <tr><td>cancel</td><td>{@code stopUsingItem}: never fires</td>
 *       <td>{@code stopUsingItem}: never fires; a loaded crossbow stays loaded, like a player's</td></tr>
 * </table>
 *
 * <p>Ammunition, Infinity, Multishot, Piercing, Quick Charge, Power, Punch and Flame are all resolved by the items themselves.
 * Firework rockets are never loaded here (the loadout only ever puts an arrow in the offhand, and vanilla's own search of the
 * inventory only finds arrows), and a crossbow that already holds a rocket is not fired ({@link #canShoot}): a rocket bursts
 * close to its shooter. A trident throw is a different item-use cycle (no ammunition, no draw hold) and is not part of this layer.
 */
public final class RangedWeapon {
    /** Launch speed of an arrow from a fully drawn bow, blocks per tick (vanilla). */
    public static final float BOW_ARROW_SPEED = 3.0F;
    /** Launch speed of an arrow from a loaded crossbow, blocks per tick (vanilla {@code CrossbowItem} shooting power). */
    public static final float CROSSBOW_ARROW_SPEED = 3.15F;
    /** Yaw between the arrows of a Multishot volley, degrees (vanilla). */
    public static final double MULTISHOT_SPREAD_DEG = 10.0D;
    /** A bow is fully drawn after this many ticks: {@link BowItem#getPowerForTime} is 1.0 from here. */
    public static final int BOW_FULL_DRAW_TICKS = 20;

    /** Where a weapon is in its cycle after one {@link #prepare} tick. */
    public enum Readiness {
        /** Nothing can be drawn (no ammunition, or the use was refused). */
        FAILED,
        /** Drawing, or just loaded this tick: not yet ready to shoot. */
        CHARGING,
        /** A bow fully drawn, or a crossbow loaded: {@link #shoot} fires it. */
        READY
    }

    /** What a shot flies like: {@code spreadDeg} is the yaw of the side arrows (Multishot), {@code piercing} whether they pass through. */
    public record Shape(double spreadDeg, boolean piercing) {
        public static final Shape PLAIN = new Shape(0.0D, false);
    }

    private RangedWeapon() {
    }

    /** True for a bow or a crossbow (anything of the vanilla {@code ProjectileWeaponItem} family this layer drives). */
    public static boolean isRanged(ItemStack stack) {
        return stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem;
    }

    public static boolean isCrossbow(ItemStack stack) {
        return stack.getItem() instanceof CrossbowItem;
    }

    /** True for a crossbow that holds a loaded shot. */
    public static boolean isLoaded(ItemStack stack) {
        return isCrossbow(stack) && CrossbowItem.isCharged(stack);
    }

    private static boolean isLoadedWithFirework(ItemStack stack) {
        ChargedProjectiles charged = stack.get(DataComponents.CHARGED_PROJECTILES);
        return charged != null && charged.contains(Items.FIREWORK_ROCKET);
    }

    /**
     * True when {@link #shoot} may fire the weapon: a crossbow holding a rocket is never fired (a rocket bursts next to its
     * shooter); every other ranged weapon may.
     */
    public static boolean canShoot(ItemStack stack) {
        return isRanged(stack) && !isLoadedWithFirework(stack);
    }

    /** The launch speed of the weapon's arrow, for the ballistic aim. */
    public static float projectileSpeed(ItemStack stack) {
        return isCrossbow(stack) ? CROSSBOW_ARROW_SPEED : BOW_ARROW_SPEED;
    }

    /** What a shot of this weapon flies like, for the friendly-fire guard. */
    public static Shape shapeOf(ItemStack stack) {
        boolean multishot = isCrossbow(stack) && GearValue.enchantmentLevel(stack, "multishot") > 0;
        boolean piercing = GearValue.enchantmentLevel(stack, "piercing") > 0;
        return new Shape(multishot ? MULTISHOT_SPREAD_DEG : 0.0D, piercing);
    }

    /**
     * How many ticks the draw must run before the weapon is ready: a bow's full draw, or a crossbow's own charge duration
     * (vanilla, 25 ticks, minus 5 per Quick Charge level).
     */
    public static int drawTicks(AIPlayerEntity bot, ItemStack stack) {
        return isCrossbow(stack) ? CrossbowItem.getChargeDuration(stack, bot) : BOW_FULL_DRAW_TICKS;
    }

    /** The expected ticks from one shot to the next, for planning: the draw, plus the tick that fires (and, for a crossbow, reloads). */
    public static int expectedCycleTicks(AIPlayerEntity bot, ItemStack stack) {
        return drawTicks(bot, stack) + (isCrossbow(stack) ? 2 : 1);
    }

    /**
     * One tick of getting the main-hand weapon ready: starts the draw through the vanilla item use when none runs, keeps it
     * going, and for a crossbow releases the draw the moment it is charged, so vanilla loads it. A crossbow that is already
     * loaded is {@link Readiness#READY} at once; the tick that loads one still reports {@link Readiness#CHARGING}, so
     * the shot follows on the next tick (about {@code charge + 2} ticks per shot, e.g. 12 with Quick Charge III).
     */
    public static Readiness prepare(AIPlayerEntity bot) {
        ItemStack weapon = bot.getMainHandItem();
        if (!isRanged(weapon)) {
            return Readiness.FAILED;
        }
        if (isLoaded(weapon)) {
            return Readiness.READY;
        }
        if (!bot.isUsingItem() || bot.getUsedItemHand() != InteractionHand.MAIN_HAND) {
            ActionResult started = InteractAction.useItemInAir(bot, InteractionHand.MAIN_HAND);
            return started.isFailed() ? Readiness.FAILED : Readiness.CHARGING;
        }
        int ticks = bot.getTicksUsingItem();
        if (isCrossbow(weapon)) {
            if (ticks >= drawTicks(bot, weapon)) {
                // Vanilla loads the crossbow on release once the charge time is complete (and consumes its ammunition there).
                bot.releaseUsingItem();
            }
            return Readiness.CHARGING;
        }
        return BowItem.getPowerForTime(ticks) >= 1.0F ? Readiness.READY : Readiness.CHARGING;
    }

    /** True when a bow is fully drawn right now (the draw {@link #shoot} releases). */
    public static boolean isFullyDrawn(AIPlayerEntity bot) {
        ItemStack weapon = bot.getMainHandItem();
        return weapon.getItem() instanceof BowItem
                && bot.isUsingItem()
                && bot.getUsedItemHand() == InteractionHand.MAIN_HAND
                && BowItem.getPowerForTime(bot.getTicksUsingItem()) >= 1.0F;
    }

    /** True when {@link #shoot} would fire right now: a fully drawn bow or a loaded crossbow. */
    public static boolean isReadyToShoot(AIPlayerEntity bot) {
        ItemStack weapon = bot.getMainHandItem();
        return canShoot(weapon) && (isCrossbow(weapon) ? isLoaded(weapon) : isFullyDrawn(bot));
    }

    /**
     * Fires the ready weapon through the vanilla path: a bow's {@code releaseUsingItem} (the arrow leaves at full power), a
     * loaded crossbow's {@code gameMode.useItem}. Never fires a bow before its full draw, never a crossbow that is not
     * loaded. Returns whether a shot left. The caller owns the legality (aim, line of sight, friendly fire).
     */
    public static boolean shoot(AIPlayerEntity bot) {
        if (!isReadyToShoot(bot)) {
            return false;
        }
        ItemStack weapon = bot.getMainHandItem();
        if (isCrossbow(weapon)) {
            ActionResult fired = InteractAction.useItemInAir(bot, InteractionHand.MAIN_HAND);
            return fired.isSuccess() && !isLoaded(bot.getMainHandItem());
        }
        bot.releaseUsingItem();
        return true;
    }

    /** Ends any draw in progress without shooting ({@code stopUsingItem}: releasing a drawn bow would fire it). */
    public static void cancel(AIPlayerEntity bot) {
        bot.stopUsingItem();
    }
}

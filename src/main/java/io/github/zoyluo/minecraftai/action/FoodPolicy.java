package io.github.zoyluo.minecraftai.action;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.consume_effects.ConsumeEffect;

/**
 * Which food the bot may eat on its own, and which of the eligible foods it should pick.
 *
 * <p>Two halves. {@link #reserveTier(ItemStack)} classifies an item from its own components and a short
 * list of valuables (needs the item registry). {@link #choose(List, int, boolean)} is a pure decision
 * over already-classified options, unit-tested without Minecraft.</p>
 *
 * <p>Design: a player's golden apples are a reserve, not lunch, and chorus fruit (random teleport) or
 * suspicious stew (random effects) must never be eaten unattended. Among the rest the bot picks the
 * cheapest food that fits the hunger gap without waste, and only when nothing fits it takes the biggest
 * one (fewest bites). This deliberately does NOT copy a "most saturation first" scorer, which would eat
 * the most valuable food first.</p>
 */
public final class FoodPolicy {
    private FoodPolicy() {
    }

    /** Preference tiers, best first. {@link #NEVER} foods are never eaten automatically. */
    public enum Tier {
        /** Ordinary cheap food. */
        COMMON,
        /** Edible but worth keeping for potions/trading (golden carrot): only when no common food exists. */
        VALUABLE,
        /** Golden apple: only in a low-health emergency. */
        EMERGENCY_ONLY,
        /** Hunger-only foods (rotten flesh, raw chicken): a starvation last resort. */
        LAST_RESORT,
        /** Enchanted golden apple, chorus fruit, suspicious stew, teleport/unknown consume effects. */
        NEVER
    }

    /** One candidate stack: {@code id} is opaque to the chooser (a slot number chosen by the caller). */
    public record Option(int id, int nutrition, float saturation, Tier tier) {
    }

    /** Hunger points still missing from a full bar. */
    public static int hungerGap(int foodLevel) {
        return Math.max(0, 20 - foodLevel);
    }

    /**
     * Tier of a food stack ignoring the hunger-only list (the caller owns that list): {@link Tier#NEVER},
     * {@link Tier#EMERGENCY_ONLY}, {@link Tier#VALUABLE} or {@link Tier#COMMON}.
     */
    public static Tier reserveTier(ItemStack stack) {
        if (stack.isEmpty()) {
            return Tier.NEVER;
        }
        if (stack.is(Items.ENCHANTED_GOLDEN_APPLE) || stack.is(Items.CHORUS_FRUIT)
                || stack.is(Items.SUSPICIOUS_STEW) || hasTeleportOrUnknownConsumeEffect(stack)) {
            return Tier.NEVER;
        }
        if (stack.is(Items.GOLDEN_APPLE)) {
            return Tier.EMERGENCY_ONLY;
        }
        if (stack.is(Items.GOLDEN_CARROT)) {
            return Tier.VALUABLE;
        }
        return Tier.COMMON;
    }

    /**
     * Whether the item's own consume effects teleport or are of a kind this bot does not understand
     * (anything besides applying/removing/clearing status effects and playing a sound).
     */
    static boolean hasTeleportOrUnknownConsumeEffect(ItemStack stack) {
        net.minecraft.world.item.component.Consumable consumable = stack.get(DataComponents.CONSUMABLE);
        if (consumable == null) {
            return false;
        }
        for (ConsumeEffect effect : consumable.onConsumeEffects()) {
            ConsumeEffect.Type<?> type = effect.getType();
            boolean known = type == ConsumeEffect.Type.APPLY_EFFECTS
                    || type == ConsumeEffect.Type.REMOVE_EFFECTS
                    || type == ConsumeEffect.Type.CLEAR_ALL_EFFECTS
                    || type == ConsumeEffect.Type.PLAY_SOUND;
            if (!known) {
                return true;
            }
        }
        return false;
    }

    /**
     * Picks the food to eat, or {@code null} when nothing may be eaten. {@link Tier#NEVER} options are
     * never chosen and {@link Tier#EMERGENCY_ONLY} ones only when {@code emergency}. The best non-empty
     * tier wins ({@code COMMON, VALUABLE, EMERGENCY_ONLY, LAST_RESORT}); inside it, the option that fits
     * the hunger gap ({@code nutrition >= gap}) with the least waste wins, and when nothing fits the
     * biggest nutrition wins (fewest bites), higher saturation breaking a tie. Remaining ties keep the
     * caller's order, so slot order stays the last tiebreak.
     */
    public static Option choose(List<Option> options, int hungerGap, boolean emergency) {
        for (Tier tier : new Tier[] {Tier.COMMON, Tier.VALUABLE, Tier.EMERGENCY_ONLY, Tier.LAST_RESORT}) {
            if (tier == Tier.EMERGENCY_ONLY && !emergency) {
                continue;
            }
            Option best = null;
            for (Option option : options) {
                if (option.tier() != tier) {
                    continue;
                }
                if (best == null || better(option, best, hungerGap)) {
                    best = option;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private static boolean better(Option candidate, Option best, int gap) {
        boolean candidateFits = candidate.nutrition() >= gap;
        boolean bestFits = best.nutrition() >= gap;
        if (candidateFits != bestFits) {
            return candidateFits;
        }
        if (candidateFits) {
            int candidateWaste = candidate.nutrition() - gap;
            int bestWaste = best.nutrition() - gap;
            if (candidateWaste != bestWaste) {
                return candidateWaste < bestWaste;
            }
            return false;
        }
        if (candidate.nutrition() != best.nutrition()) {
            return candidate.nutrition() > best.nutrition();
        }
        return candidate.saturation() > best.saturation();
    }
}

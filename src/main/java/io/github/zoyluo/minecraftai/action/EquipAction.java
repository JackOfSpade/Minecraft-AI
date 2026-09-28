package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ArrowItem;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.EntityTypeTags;
import net.minecraft.registry.tag.ItemTags;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

public final class EquipAction {
    private static final int MIN_MELEE_RAW_DURABILITY = 2;
    // Vanilla ArrowEntity starts at two base damage for normal, spectral, and tipped arrows.
    // Potion damage is added as a selection score only; vanilla remains the authority on impact.
    private static final int VANILLA_ARROW_DAMAGE_SCORE = 2;
    private static final int MAX_EFFECT_AMPLIFIER_FOR_SCORE = 8;
    private static final int MAX_EFFECT_DURATION_FOR_SCORE = 12_000;
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    };

    private EquipAction() {
    }

    public static int equipBestArmor(AIPlayerEntity bot) {
        PlayerInventory inventory = bot.getInventory();
        Map<EquipmentSlot, Candidate> best = new EnumMap<>(EquipmentSlot.class);
        for (int slot = 0; slot < inventory.getMainStacks().size(); slot++) {
            ItemStack stack = inventory.getMainStacks().get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            EquipmentSlot equipmentSlot = bot.getPreferredEquipmentSlot(stack);
            if (!isArmorSlot(equipmentSlot)) {
                continue;
            }
            double score = armorScore(stack, equipmentSlot);
            if (score <= equippedArmorScore(bot, equipmentSlot)) {
                continue;
            }
            Candidate current = best.get(equipmentSlot);
            if (current == null || score > current.score()) {
                best.put(equipmentSlot, new Candidate(slot, stack.copy(), score));
            }
        }
        int equipped = 0;
        for (Map.Entry<EquipmentSlot, Candidate> entry : best.entrySet()) {
            EquipmentSlot slot = entry.getKey();
            Candidate candidate = entry.getValue();
            ItemStack old = bot.getEquippedStack(slot).copy();
            inventory.getMainStacks().set(candidate.sourceSlot(), old);
            bot.equipStack(slot, candidate.stack());
            inventory.markDirty();
            equipped++;
            BotLog.action(bot, "equip_armor", "slot", slot.asString(), "item", candidate.stack().getItem(), "score", candidate.score());
        }
        return equipped;
    }

    public static OptionalInt equipBestWeapon(AIPlayerEntity bot) {
        OptionalInt slot = bestWeaponSlot(bot);
        slot.ifPresent(value -> InventoryAction.equipFromSlot(bot, value));
        return slot;
    }

    public static OptionalInt bestWeaponSlot(AIPlayerEntity bot) {
        PlayerInventory inventory = bot.getInventory();
        int bestSlot = -1;
        double bestDamage = 1.0D;
        int bestSwordPriority = -1;
        int bestDurability = -1;
        for (int slot = 0; slot < inventory.getMainStacks().size(); slot++) {
            ItemStack stack = inventory.getMainStacks().get(slot);
            if (!isQualifiedMeleeWeapon(stack)) {
                continue;
            }
            double damage = attackDamage(stack);
            int swordPriority = swordPriority(stack);
            int durability = remainingDurability(stack);
            if (damage > bestDamage
                    || Double.compare(damage, bestDamage) == 0
                    && damage > 1.0D
                    && (swordPriority > bestSwordPriority
                    || swordPriority == bestSwordPriority
                    && durability > bestDurability)) {
                bestDamage = damage;
                bestSwordPriority = swordPriority;
                bestDurability = durability;
                bestSlot = slot;
            }
        }
        return bestSlot < 0 ? OptionalInt.empty() : OptionalInt.of(bestSlot);
    }

    /** Only purpose-built melee tools may authorize a defensive Combat transaction. */
    public static boolean isQualifiedMeleeWeapon(ItemStack stack) {
        return !stack.isEmpty()
                && (stack.isIn(ItemTags.SWORDS) || stack.getItem() instanceof AxeItem)
                && remainingDurability(stack) >= MIN_MELEE_RAW_DURABILITY;
    }

    private static int swordPriority(ItemStack stack) {
        return stack.isIn(ItemTags.SWORDS) ? 1 : 0;
    }

    private static int remainingDurability(ItemStack stack) {
        return stack.isDamageable()
                ? Math.max(0, stack.getMaxDamage() - stack.getDamage())
                : Integer.MAX_VALUE;
    }

    /**
     * Selects a bow only when a physical vanilla-compatible arrow exists.  In particular, this
     * deliberately does not let an Infinity bow invent ammunition: vanilla consumes tipped and
     * spectral arrows, so a real stack must still be available before a ranged action is begun.
     */
    public static OptionalInt bestRangedSlot(AIPlayerEntity bot, LivingEntity target) {
        if (bestArrowChoice(bot, target).isEmpty()) {
            return OptionalInt.empty();
        }
        PlayerInventory inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getMainStacks().size(); slot++) {
            if (inventory.getMainStacks().get(slot).isOf(Items.BOW)) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    /** Kept for callers which have no target-specific potion-effect context. */
    public static OptionalInt bestRangedSlot(AIPlayerEntity bot) {
        return bestRangedSlot(bot, null);
    }

    /**
     * Equips a bow and places the deterministic best arrow in offhand.  RangedWeaponItem resolves
     * a held projectile before inventory ammunition, so this makes the score observable by the
     * actual shot instead of relying on inventory iteration order.  The previous offhand stack is
     * atomically swapped into the arrow's source slot and can later be restored by the returned
     * lease without dropping or overwriting either stack.
     */
    public static Optional<RangedLoadout> equipBestRangedLoadout(AIPlayerEntity bot,
                                                                   LivingEntity target) {
        OptionalInt bowSlot = bestRangedSlot(bot, target);
        if (bowSlot.isEmpty() || InventoryAction.equipFromSlot(bot, bowSlot.getAsInt()) < 0) {
            return Optional.empty();
        }

        ArrowChoice choice = bestArrowChoice(bot, target).orElse(null);
        if (choice == null) {
            return Optional.empty();
        }
        if (choice.isOffhand()) {
            return Optional.of(RangedLoadout.alreadyHeld(bot.getOffHandStack()));
        }

        PlayerInventory inventory = bot.getInventory();
        ItemStack ammunition = inventory.getMainStacks().get(choice.mainSlot());
        if (!isCompatibleBowArrow(ammunition)) {
            return Optional.empty();
        }
        ItemStack displacedOffhand = bot.getOffHandStack().copy();
        bot.equipStack(EquipmentSlot.OFFHAND, ammunition.copy());
        inventory.getMainStacks().set(choice.mainSlot(), displacedOffhand);
        inventory.markDirty();
        BotLog.action(bot, "equip_ranked_arrow_offhand",
                "source_slot", choice.mainSlot(),
                "item", ammunition.getItem(),
                "damage_score", choice.damageScore(),
                "effect_score", choice.enemyEffectScore());
        return Optional.of(new RangedLoadout(choice.mainSlot(), displacedOffhand, ammunition));
    }

    private static Optional<ArrowChoice> bestArrowChoice(AIPlayerEntity bot, LivingEntity target) {
        PlayerInventory inventory = bot.getInventory();
        ArrowChoice best = null;
        for (int slot = 0; slot < inventory.getMainStacks().size(); slot++) {
            ItemStack stack = inventory.getMainStacks().get(slot);
            if (!isCompatibleBowArrow(stack)) {
                continue;
            }
            ArrowChoice candidate = scoreArrow(slot, false, stack, target);
            if (isBetterArrow(candidate, best)) {
                best = candidate;
            }
        }
        ItemStack offhand = bot.getOffHandStack();
        if (isCompatibleBowArrow(offhand)) {
            ArrowChoice candidate = scoreArrow(-1, true, offhand, target);
            if (isBetterArrow(candidate, best)) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean isCompatibleBowArrow(ItemStack stack) {
        // ArrowItem covers vanilla normal, spectral, and tipped arrows.  Do not accept arbitrary
        // projectile-like items here: BowItem only guarantees this family as a valid bow payload.
        return !stack.isEmpty() && stack.getItem() instanceof ArrowItem;
    }

    private static ArrowChoice scoreArrow(int mainSlot,
                                          boolean offhand,
                                          ItemStack stack,
                                          LivingEntity target) {
        boolean targetIsUndead = target != null && target.getType().isIn(EntityTypeTags.UNDEAD);
        int damageScore = VANILLA_ARROW_DAMAGE_SCORE;
        int enemyEffectScore = 0;
        PotionContentsComponent contents = stack.getOrDefault(
                DataComponentTypes.POTION_CONTENTS, PotionContentsComponent.DEFAULT);
        for (StatusEffectInstance effect : contents.getEffects()) {
            boolean instantHarming = effect.getEffectType().matches(
                    net.minecraft.entity.effect.StatusEffects.INSTANT_DAMAGE);
            boolean instantHealingHurtsUndead = targetIsUndead && effect.getEffectType().matches(
                    net.minecraft.entity.effect.StatusEffects.INSTANT_HEALTH);
            if (instantHarming || instantHealingHurtsUndead) {
                damageScore += instantDamageScore(effect.getAmplifier());
            }
            enemyEffectScore += enemyEffectScore(effect, instantHealingHurtsUndead);
        }
        return new ArrowChoice(mainSlot, offhand, damageScore, enemyEffectScore);
    }

    private static int instantDamageScore(int amplifier) {
        int capped = Math.max(0, Math.min(MAX_EFFECT_AMPLIFIER_FOR_SCORE, amplifier));
        return 4 << capped;
    }

    private static int enemyEffectScore(StatusEffectInstance effect,
                                        boolean instantHealingHurtsUndead) {
        int potency = Math.max(1, Math.min(MAX_EFFECT_AMPLIFIER_FOR_SCORE + 1,
                effect.getAmplifier() + 1));
        int duration = Math.max(0, Math.min(MAX_EFFECT_DURATION_FOR_SCORE, effect.getDuration()));
        int magnitude = potency * 1_000 + duration;
        StatusEffectCategory category = effect.getEffectType().value().getCategory();
        if (category == StatusEffectCategory.HARMFUL) {
            return magnitude;
        }
        // A healing arrow is extra damage only to undead.  Against ordinary living targets it is
        // a benefit, so keep it below an otherwise equal normal arrow rather than healing a foe.
        if (category == StatusEffectCategory.BENEFICIAL && !instantHealingHurtsUndead) {
            return -magnitude;
        }
        return 0;
    }

    private static boolean isBetterArrow(ArrowChoice candidate, ArrowChoice current) {
        if (current == null) {
            return true;
        }
        if (candidate.damageScore() != current.damageScore()) {
            return candidate.damageScore() > current.damageScore();
        }
        if (candidate.enemyEffectScore() != current.enemyEffectScore()) {
            return candidate.enemyEffectScore() > current.enemyEffectScore();
        }
        // Keeping an equally good arrow already held avoids needless shield churn.  Otherwise,
        // ascending main-slot order is a deterministic tie-breaker.
        if (candidate.isOffhand() != current.isOffhand()) {
            return candidate.isOffhand();
        }
        return candidate.mainSlot() < current.mainSlot();
    }

    public static boolean equipShieldOffhand(AIPlayerEntity bot) {
        if (bot.getOffHandStack().isOf(Items.SHIELD)) {
            return true;
        }
        PlayerInventory inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getMainStacks().size(); slot++) {
            ItemStack stack = inventory.getMainStacks().get(slot);
            if (!stack.isOf(Items.SHIELD)) {
                continue;
            }
            ItemStack oldOffhand = bot.getOffHandStack().copy();
            bot.equipStack(EquipmentSlot.OFFHAND, stack.copy());
            inventory.getMainStacks().set(slot, oldOffhand);
            inventory.markDirty();
            BotLog.action(bot, "equip_shield_offhand", "source_slot", slot);
            return true;
        }
        return false;
    }

    /**
     * A reversible bow-ammunition swap.  Restoration is conservative: if another owner touched
     * either stack, it leaves both stacks in place instead of risking an overwrite or item loss.
     */
    public static final class RangedLoadout {
        private final int restoreSlot;
        private final ItemStack storedOffhand;
        private final ItemStack ammunition;

        private RangedLoadout(int restoreSlot, ItemStack storedOffhand, ItemStack ammunition) {
            this.restoreSlot = restoreSlot;
            this.storedOffhand = storedOffhand.copy();
            this.ammunition = ammunition.copy();
        }

        private static RangedLoadout alreadyHeld(ItemStack ammunition) {
            return new RangedLoadout(-1, ItemStack.EMPTY, ammunition);
        }

        public boolean restore(AIPlayerEntity bot) {
            if (restoreSlot < 0) {
                return true;
            }
            PlayerInventory inventory = bot.getInventory();
            if (restoreSlot >= inventory.getMainStacks().size()) {
                return false;
            }
            ItemStack currentOffhand = bot.getOffHandStack();
            boolean expectedAmmo = currentOffhand.isEmpty()
                    || ItemStack.areItemsAndComponentsEqual(currentOffhand, ammunition);
            if (!expectedAmmo || !ItemStack.areEqual(inventory.getMainStacks().get(restoreSlot), storedOffhand)) {
                return false;
            }
            bot.equipStack(EquipmentSlot.OFFHAND, inventory.getMainStacks().get(restoreSlot).copy());
            inventory.getMainStacks().set(restoreSlot, currentOffhand.copy());
            inventory.markDirty();
            BotLog.action(bot, "restore_ranged_offhand", "source_slot", restoreSlot);
            return true;
        }
    }

    public static double attackDamage(ItemStack stack) {
        return attributeValue(stack, EquipmentSlot.MAINHAND, EntityAttributes.ATTACK_DAMAGE);
    }

    private static double equippedArmorScore(AIPlayerEntity bot, EquipmentSlot slot) {
        return armorScore(bot.getEquippedStack(slot), slot);
    }

    private static double armorScore(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) {
            return 0.0D;
        }
        double armor = attributeValue(stack, slot, EntityAttributes.ARMOR);
        double toughness = attributeValue(stack, slot, EntityAttributes.ARMOR_TOUGHNESS);
        return armor + toughness * 0.25D;
    }

    private static double attributeValue(ItemStack stack,
                                         EquipmentSlot slot,
                                         RegistryEntry<EntityAttribute> attribute) {
        double[] value = {0.0D};
        stack.applyAttributeModifiers(slot, (entry, modifier) -> {
            if (entry.equals(attribute) && modifier.operation() == EntityAttributeModifier.Operation.ADD_VALUE) {
                value[0] += modifier.value();
            }
        });
        return value[0];
    }

    private static boolean isArmorSlot(EquipmentSlot slot) {
        for (EquipmentSlot armorSlot : ARMOR_SLOTS) {
            if (slot == armorSlot) {
                return true;
            }
        }
        return false;
    }

    private record Candidate(int sourceSlot, ItemStack stack, double score) {
    }

    private record ArrowChoice(int mainSlot,
                               boolean isOffhand,
                               int damageScore,
                               int enemyEffectScore) {
    }
}

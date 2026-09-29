package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

public final class EquipAction {
    private static final int MIN_MELEE_RAW_DURABILITY = 2;
    // Vanilla Arrow starts at two base damage for normal, spectral, and tipped arrows.
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
        Inventory inventory = bot.getInventory();
        Map<EquipmentSlot, Candidate> best = new EnumMap<>(EquipmentSlot.class);
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
            ItemStack stack = inventory.getNonEquipmentItems().get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            EquipmentSlot equipmentSlot = bot.getEquipmentSlotForItem(stack);
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
            ItemStack old = bot.getItemBySlot(slot).copy();
            inventory.getNonEquipmentItems().set(candidate.sourceSlot(), old);
            bot.setItemSlot(slot, candidate.stack());
            inventory.setChanged();
            equipped++;
            BotLog.action(bot, "equip_armor", "slot", slot.getSerializedName(), "item", candidate.stack().getItem(), "score", candidate.score());
        }
        return equipped;
    }

    public static OptionalInt equipBestWeapon(AIPlayerEntity bot) {
        OptionalInt slot = bestWeaponSlot(bot);
        slot.ifPresent(value -> InventoryAction.equipFromSlot(bot, value));
        return slot;
    }

    public static OptionalInt bestWeaponSlot(AIPlayerEntity bot) {
        Inventory inventory = bot.getInventory();
        int bestSlot = -1;
        double bestDamage = 1.0D;
        int bestSwordPriority = -1;
        int bestDurability = -1;
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
            ItemStack stack = inventory.getNonEquipmentItems().get(slot);
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
                && (stack.is(ItemTags.SWORDS) || stack.getItem() instanceof AxeItem)
                && remainingDurability(stack) >= MIN_MELEE_RAW_DURABILITY;
    }

    private static int swordPriority(ItemStack stack) {
        return stack.is(ItemTags.SWORDS) ? 1 : 0;
    }

    private static int remainingDurability(ItemStack stack) {
        return stack.isDamageableItem()
                ? Math.max(0, stack.getMaxDamage() - stack.getDamageValue())
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
        Inventory inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
            if (inventory.getNonEquipmentItems().get(slot).is(Items.BOW)) {
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
     * Equips a bow and places the deterministic best arrow in offhand.  ProjectileWeaponItem resolves
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
            return Optional.of(RangedLoadout.alreadyHeld(bot.getOffhandItem()));
        }

        Inventory inventory = bot.getInventory();
        ItemStack ammunition = inventory.getNonEquipmentItems().get(choice.mainSlot());
        if (!isCompatibleBowArrow(ammunition)) {
            return Optional.empty();
        }
        ItemStack displacedOffhand = bot.getOffhandItem().copy();
        bot.setItemSlot(EquipmentSlot.OFFHAND, ammunition.copy());
        inventory.getNonEquipmentItems().set(choice.mainSlot(), displacedOffhand);
        inventory.setChanged();
        BotLog.action(bot, "equip_ranked_arrow_offhand",
                "source_slot", choice.mainSlot(),
                "item", ammunition.getItem(),
                "damage_score", choice.damageScore(),
                "effect_score", choice.enemyEffectScore());
        return Optional.of(new RangedLoadout(choice.mainSlot(), displacedOffhand, ammunition));
    }

    private static Optional<ArrowChoice> bestArrowChoice(AIPlayerEntity bot, LivingEntity target) {
        Inventory inventory = bot.getInventory();
        ArrowChoice best = null;
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
            ItemStack stack = inventory.getNonEquipmentItems().get(slot);
            if (!isCompatibleBowArrow(stack)) {
                continue;
            }
            ArrowChoice candidate = scoreArrow(slot, false, stack, target);
            if (isBetterArrow(candidate, best)) {
                best = candidate;
            }
        }
        ItemStack offhand = bot.getOffhandItem();
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
        boolean targetIsUndead = target != null && target.getType().is(EntityTypeTags.UNDEAD);
        int damageScore = VANILLA_ARROW_DAMAGE_SCORE;
        int enemyEffectScore = 0;
        PotionContents contents = stack.getOrDefault(
                DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        for (MobEffectInstance effect : contents.getAllEffects()) {
            boolean instantHarming = effect.getEffect().is(
                    net.minecraft.world.effect.MobEffects.INSTANT_DAMAGE);
            boolean instantHealingHurtsUndead = targetIsUndead && effect.getEffect().is(
                    net.minecraft.world.effect.MobEffects.INSTANT_HEALTH);
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

    private static int enemyEffectScore(MobEffectInstance effect,
                                        boolean instantHealingHurtsUndead) {
        int potency = Math.max(1, Math.min(MAX_EFFECT_AMPLIFIER_FOR_SCORE + 1,
                effect.getAmplifier() + 1));
        int duration = Math.max(0, Math.min(MAX_EFFECT_DURATION_FOR_SCORE, effect.getDuration()));
        int magnitude = potency * 1_000 + duration;
        MobEffectCategory category = effect.getEffect().value().getCategory();
        if (category == MobEffectCategory.HARMFUL) {
            return magnitude;
        }
        // A healing arrow is extra damage only to undead.  Against ordinary living targets it is
        // a benefit, so keep it below an otherwise equal normal arrow rather than healing a foe.
        if (category == MobEffectCategory.BENEFICIAL && !instantHealingHurtsUndead) {
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
        if (bot.getOffhandItem().is(Items.SHIELD)) {
            return true;
        }
        Inventory inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
            ItemStack stack = inventory.getNonEquipmentItems().get(slot);
            if (!stack.is(Items.SHIELD)) {
                continue;
            }
            ItemStack oldOffhand = bot.getOffhandItem().copy();
            bot.setItemSlot(EquipmentSlot.OFFHAND, stack.copy());
            inventory.getNonEquipmentItems().set(slot, oldOffhand);
            inventory.setChanged();
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
            Inventory inventory = bot.getInventory();
            if (restoreSlot >= inventory.getNonEquipmentItems().size()) {
                return false;
            }
            ItemStack currentOffhand = bot.getOffhandItem();
            boolean expectedAmmo = currentOffhand.isEmpty()
                    || ItemStack.isSameItemSameComponents(currentOffhand, ammunition);
            if (!expectedAmmo || !ItemStack.matches(inventory.getNonEquipmentItems().get(restoreSlot), storedOffhand)) {
                return false;
            }
            bot.setItemSlot(EquipmentSlot.OFFHAND, inventory.getNonEquipmentItems().get(restoreSlot).copy());
            inventory.getNonEquipmentItems().set(restoreSlot, currentOffhand.copy());
            inventory.setChanged();
            BotLog.action(bot, "restore_ranged_offhand", "source_slot", restoreSlot);
            return true;
        }
    }

    public static double attackDamage(ItemStack stack) {
        return attributeValue(stack, EquipmentSlot.MAINHAND, Attributes.ATTACK_DAMAGE);
    }

    private static double equippedArmorScore(AIPlayerEntity bot, EquipmentSlot slot) {
        return armorScore(bot.getItemBySlot(slot), slot);
    }

    private static double armorScore(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) {
            return 0.0D;
        }
        double armor = attributeValue(stack, slot, Attributes.ARMOR);
        double toughness = attributeValue(stack, slot, Attributes.ARMOR_TOUGHNESS);
        return armor + toughness * 0.25D;
    }

    private static double attributeValue(ItemStack stack,
                                         EquipmentSlot slot,
                                         Holder<Attribute> attribute) {
        double[] value = {0.0D};
        stack.forEachModifier(slot, (entry, modifier) -> {
            if (entry.equals(attribute) && modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
                value[0] += modifier.amount();
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

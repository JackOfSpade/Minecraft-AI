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
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

public final class EquipAction {
    private static final int MIN_MELEE_RAW_DURABILITY = 2;
    private static final double SCORE_EPSILON = 1.0E-6D;
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
            logArmorEquip(bot, slot, candidate);
        }
        return equipped;
    }

    /** Repeat window for an identical armor-equip line (slot + item) of one bot: 1200 ticks = 1 minute. */
    private static final int ARMOR_LOG_REPEAT_TICKS = 1200;
    private static final Map<java.util.UUID, Map<EquipmentSlot, ArmorLogged>> LAST_ARMOR_LOGGED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Drops the per-bot armor-log dedup state (called when the bot entity is removed) so the map cannot leak. */
    public static void forgetArmorLog(java.util.UUID botId) {
        LAST_ARMOR_LOGGED.remove(botId);
    }

    private record ArmorLogged(Item item, int tick) {
    }

    /**
     * Logs an armor equip only when it is news: the same item re-equipped into the same slot within a
     * minute is not logged again (the same four pieces were logged every 1-2 s while a player had the
     * bot's inventory open and its equipment was being shuffled).
     */
    private static void logArmorEquip(AIPlayerEntity bot, EquipmentSlot slot, Candidate candidate) {
        Item item = candidate.stack().getItem();
        int now = bot.level().getServer() == null ? 0 : bot.level().getServer().getTickCount();
        Map<EquipmentSlot, ArmorLogged> perBot = LAST_ARMOR_LOGGED.computeIfAbsent(
                bot.getUUID(), id -> new EnumMap<>(EquipmentSlot.class));
        ArmorLogged previous = perBot.get(slot);
        if (previous != null && previous.item() == item && now - previous.tick() < ARMOR_LOG_REPEAT_TICKS
                && now >= previous.tick()) {
            return;
        }
        perBot.put(slot, new ArmorLogged(item, now));
        BotLog.action(bot, "equip_armor", "slot", slot.getSerializedName(), "item", item, "score", candidate.score());
    }

    public static OptionalInt equipBestWeapon(AIPlayerEntity bot) {
        OptionalInt slot = bestWeaponSlot(bot);
        slot.ifPresent(value -> InventoryAction.equipFromSlot(bot, value));
        return slot;
    }

    /**
     * Picks the best automatic melee weapon by sustained damage per second, not by per-hit damage.
     *
     * <p>Vanilla attack cooldown makes a slow heavy weapon worse than its damage suggests: a stone
     * axe hits for 9 every 1.25 s while a stone sword hits for 5 every 0.625 s, so by the vanilla
     * numbers a sword out-damages the same-tier axe at every tier (and knocks the mob back twice as
     * often). The score is {@code (1 + ATTACK_DAMAGE) * (4 + ATTACK_SPEED)}, read from the stack's
     * own attribute modifiers so modded weapons with unusual numbers are ranked correctly, plus a
     * small Sharpness bonus. Ties go to swords, then to remaining durability.
     *
     * <p>Spears (piercing/kinetic weapons with lunge and charge attacks) and the mace (fall-smash
     * damage) are excluded from automatic melee choice on purpose, see
     * {@link #isQualifiedMeleeWeapon}: their normal click attack is not what their damage numbers
     * describe, so ranking them here would mis-rank them.
     */
    public static OptionalInt bestWeaponSlot(AIPlayerEntity bot) {
        Inventory inventory = bot.getInventory();
        int bestSlot = -1;
        double bestScore = 0.0D;
        int bestSwordPriority = -1;
        int bestDurability = -1;
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
            ItemStack stack = inventory.getNonEquipmentItems().get(slot);
            if (!isQualifiedMeleeWeapon(stack)) {
                continue;
            }
            double score = meleeScore(stack);
            int swordPriority = swordPriority(stack);
            int durability = remainingDurability(stack);
            boolean better = bestSlot < 0
                    || score > bestScore + SCORE_EPSILON
                    || Math.abs(score - bestScore) <= SCORE_EPSILON
                    && (swordPriority > bestSwordPriority
                    || swordPriority == bestSwordPriority && durability > bestDurability);
            if (better) {
                bestScore = score;
                bestSwordPriority = swordPriority;
                bestDurability = durability;
                bestSlot = slot;
            }
        }
        return bestSlot < 0 ? OptionalInt.empty() : OptionalInt.of(bestSlot);
    }

    /**
     * Sustained melee damage per second of one swing-per-cooldown rotation, in units of
     * (damage x cooldown-rate); only the ordering matters. Attack damage and speed are the player's
     * base value (1.0 and 4.0) plus the stack's own additive modifiers.
     */
    static double meleeScore(ItemStack stack) {
        double damage = 1.0D + attackDamage(stack) + sharpnessBonus(stack);
        double speed = Math.max(0.1D, 4.0D + attackSpeedModifier(stack));
        return damage * speed;
    }

    private static double sharpnessBonus(ItemStack stack) {
        ItemEnchantments enchantments = stack.getOrDefault(
                DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (Holder<Enchantment> enchantment : enchantments.keySet()) {
            if (enchantment.is(Enchantments.SHARPNESS)) {
                int level = enchantments.getLevel(enchantment);
                return level > 0 ? 0.5D + 0.5D * level : 0.0D;
            }
        }
        return 0.0D;
    }

    /**
     * Only purpose-built melee tools may authorize a defensive Combat transaction: swords and axes.
     * Spears (any item tagged as a spear, or carrying the piercing/kinetic weapon components) and
     * the mace are deliberately not qualified: a spear's damage comes from its jab/charge attack
     * and lunge rather than a plain click, and the mace's from a falling smash, so neither is
     * modelled by the per-swing score and both stay out of automatic melee choice.
     */
    public static boolean isQualifiedMeleeWeapon(ItemStack stack) {
        return !stack.isEmpty()
                && (stack.is(ItemTags.SWORDS) || stack.getItem() instanceof AxeItem)
                && !stack.is(ItemTags.SPEARS)
                && !stack.is(Items.MACE)
                && !stack.has(DataComponents.PIERCING_WEAPON)
                && !stack.has(DataComponents.KINETIC_WEAPON)
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

    /** True when the bot carries a shield, raised or not: in the offhand or anywhere in the inventory. */
    public static boolean hasShield(AIPlayerEntity bot) {
        if (bot.getOffhandItem().is(Items.SHIELD)) {
            return true;
        }
        Inventory inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
            if (inventory.getNonEquipmentItems().get(slot).is(Items.SHIELD)) {
                return true;
            }
        }
        return false;
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

    private static double attackSpeedModifier(ItemStack stack) {
        return attributeValue(stack, EquipmentSlot.MAINHAND, Attributes.ATTACK_SPEED);
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

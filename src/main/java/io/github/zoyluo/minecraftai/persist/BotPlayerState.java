package io.github.zoyluo.minecraftai.persist;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Everything a real player's vanilla save keeps (beyond the 36 main inventory slots that
 * {@link BotPersistence#encodeInventory} writes) and that matters for a bot, serialized as one SNBT
 * compound stored in {@link BotRecord#playerStateNbt()}.
 *
 * <p>Since 1.21.5 the armor slots and the offhand live in the entity's {@code EntityEquipment}, not in
 * the 36-slot main list, so {@code PlayerInventory.writeData} silently drops them: before this class
 * existed every restart stripped the armor and offhand item from every bot.
 *
 * <p>Decision table (vanilla player data vs. what a bot persists):
 * <pre>
 * Vanilla field                          Decision   Where / why
 * -------------------------------------  ---------  --------------------------------------------------
 * Inventory (36 main slots)              persisted  BotRecord.inventoryNbt (unchanged, old saves load)
 * equipment: head/chest/legs/feet        persisted  "Equipment" (per-slot ItemStack codec, all components)
 * equipment: offhand, body, saddle       persisted  "Equipment" (every non-main-hand PlayerInventory slot)
 * SelectedItemSlot (hotbar)              persisted  "SelectedSlot"
 * EnderItems                             persisted  "EnderItems" (vanilla StackWithSlot list)
 * XpLevel / XpP / XpTotal                persisted  "XpLevel" / "XpProgress" / "XpTotal"
 * foodLevel/foodSaturationLevel/         persisted  "Hunger" (vanilla HungerManager.writeData; the food
 *   foodExhaustionLevel/foodTickTimer                 level is also kept in BotRecord.hunger)
 * active_effects (status effects)        persisted  "ActiveEffects" (vanilla StatusEffectInstance codec)
 * Air, Fire                              persisted  "Air", "Fire" (cheap; fire only when burning)
 * AbsorptionAmount                       persisted  "Absorption"
 * Health                                 persisted  BotRecord.health (clamped to at least 1 on restore)
 * Pos / Rotation / Dimension             persisted  BotRecord (with the safe-spawn fallback on restore)
 * playerGameType                         skipped    bots are always survival, see AIPlayerManager
 * XpSeed, Score                          skipped    no bot behaviour depends on them
 * abilities, recipeBook, seenCredits,    skipped    bots do not use them; recipes/stats are re-derived
 *   advancements, stats
 * SpawnX/Y/Z/Dimension, LastDeathLocation skipped   bots use their own respawn / death-recovery logic
 * Motion, FallDistance, OnGround,        skipped    transient physics state, meaningless after a reload
 *   Invulnerable, PortalCooldown
 * Sleeping / SleepTimer                  skipped    transient; the night logic re-decides
 * RootVehicle, ShoulderEntities          skipped    bots never ride/carry entities across a restart
 * Brain (memory), attributes             skipped    vanilla-generated on load; the mod keeps its own memory
 * </pre>
 *
 * <p>Compatibility: {@code playerStateNbt} is a new nullable field. A record written before it existed
 * decodes to {@code null} (Gson leaves absent record components null) which means "nothing to restore";
 * the snapshot schema therefore stays at {@link RuntimeSnapshot#CURRENT_SCHEMA}. Every section is
 * encoded and restored independently: a failing section is logged as {@code bot_state_*_failed} with
 * its field name and never aborts the rest of the bot restore. Restoration writes each equipment slot
 * exactly once through {@link Inventory#setItem}, which replaces (never adds to) the slot, so
 * nothing can be duplicated.
 */
public final class BotPlayerState {
    static final String EQUIPMENT = "Equipment";
    static final String SELECTED_SLOT = "SelectedSlot";
    static final String ENDER_ITEMS = "EnderItems";
    static final String XP_LEVEL = "XpLevel";
    static final String XP_PROGRESS = "XpProgress";
    static final String XP_TOTAL = "XpTotal";
    static final String HUNGER = "Hunger";
    static final String ACTIVE_EFFECTS = "ActiveEffects";
    static final String AIR = "Air";
    static final String FIRE = "Fire";
    static final String ABSORPTION = "Absorption";

    private BotPlayerState() {
    }

    /** Serializes the bot's persistent player state; never throws (a failed section is just omitted). */
    public static String encode(ServerPlayer player) {
        TagValueOutput view = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
        Inventory inventory = player.getInventory();
        section(player, EQUIPMENT, true, () -> {
            ValueOutput equipment = view.child(EQUIPMENT);
            for (var entry : Inventory.EQUIPMENT_SLOT_MAPPING.int2ObjectEntrySet()) {
                ItemStack stack = inventory.getItem(entry.getIntKey());
                if (!stack.isEmpty()) {
                    equipment.store(entry.getValue().getSerializedName(), ItemStack.CODEC, stack);
                }
            }
        });
        section(player, SELECTED_SLOT, true, () -> view.putInt(SELECTED_SLOT, inventory.getSelectedSlot()));
        section(player, ENDER_ITEMS, true, () ->
                player.getEnderChestInventory().storeAsSlots(view.list(ENDER_ITEMS, ItemStackWithSlot.CODEC)));
        section(player, XP_LEVEL, true, () -> {
            view.putInt(XP_LEVEL, player.experienceLevel);
            view.putFloat(XP_PROGRESS, player.experienceProgress);
            view.putInt(XP_TOTAL, player.totalExperience);
        });
        section(player, HUNGER, true, () -> player.getFoodData().addAdditionalSaveData(view.child(HUNGER)));
        section(player, ACTIVE_EFFECTS, true, () -> {
            var appender = view.list(ACTIVE_EFFECTS, MobEffectInstance.CODEC);
            for (MobEffectInstance effect : player.getActiveEffects()) {
                appender.add(effect);
            }
        });
        section(player, AIR, true, () -> view.putInt(AIR, player.getAirSupply()));
        section(player, FIRE, true, () -> {
            if (player.getRemainingFireTicks() > 0) {
                view.putInt(FIRE, player.getRemainingFireTicks());
            }
        });
        section(player, ABSORPTION, true, () -> view.putFloat(ABSORPTION, player.getAbsorptionAmount()));
        return view.buildResult().toString();
    }

    /**
     * Restores the state written by {@link #encode}. A null/blank string (a record from before this
     * feature existed) restores nothing. Returns the names of the sections that failed to restore.
     */
    public static List<String> apply(ServerPlayer player, String snbt) {
        List<String> failed = new ArrayList<>();
        if (snbt == null || snbt.isBlank()) {
            return failed;
        }
        CompoundTag root;
        try {
            root = TagParser.parseCompoundFully(snbt);
        } catch (Exception exception) {
            BotLog.error(asBot(player), "bot_state_restore_failed", exception, "field", "*", "reason", "unparseable");
            failed.add("*");
            return failed;
        }
        ValueInput view = TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), root);
        Inventory inventory = player.getInventory();

        restore(player, failed, EQUIPMENT, root, () -> {
            ValueInput equipment = view.childOrEmpty(EQUIPMENT);
            CompoundTag saved = root.getCompoundOrEmpty(EQUIPMENT);
            for (var entry : Inventory.EQUIPMENT_SLOT_MAPPING.int2ObjectEntrySet()) {
                EquipmentSlot slot = entry.getValue();
                if (!saved.contains(slot.getSerializedName())) {
                    continue;
                }
                ItemStack stack = equipment.read(slot.getSerializedName(), ItemStack.CODEC).orElse(null);
                if (stack == null) {
                    failed.add(EQUIPMENT + "." + slot.getSerializedName());
                    BotLog.error(asBot(player), "bot_state_restore_failed", null,
                            "field", EQUIPMENT + "." + slot.getSerializedName(), "reason", "undecodable_stack");
                    continue;
                }
                // PlayerInventory.setStack replaces the slot: applying twice can never duplicate an item.
                inventory.setItem(entry.getIntKey(), stack);
            }
        });
        restore(player, failed, SELECTED_SLOT, root, () ->
                inventory.setSelectedSlot(Math.max(0, Math.min(8, view.getIntOr(SELECTED_SLOT, 0)))));
        restore(player, failed, ENDER_ITEMS, root, () ->
                view.list(ENDER_ITEMS, ItemStackWithSlot.CODEC)
                        .ifPresent(list -> player.getEnderChestInventory().fromSlots(list)));
        restore(player, failed, XP_LEVEL, root, () -> {
            player.setExperienceLevels(Math.max(0, view.getIntOr(XP_LEVEL, 0)));
            player.experienceProgress = Math.max(0.0F, Math.min(1.0F, view.getFloatOr(XP_PROGRESS, 0.0F)));
            player.totalExperience = Math.max(0, view.getIntOr(XP_TOTAL, 0));
        });
        restore(player, failed, HUNGER, root, () -> player.getFoodData().readAdditionalSaveData(view.childOrEmpty(HUNGER)));
        restore(player, failed, ACTIVE_EFFECTS, root, () ->
                view.list(ACTIVE_EFFECTS, MobEffectInstance.CODEC)
                        .ifPresent(list -> {
                            for (MobEffectInstance effect : list) {
                                player.addEffect(new MobEffectInstance(effect));
                            }
                        }));
        restore(player, failed, AIR, root, () ->
                player.setAirSupply(Math.min(player.getMaxAirSupply(), view.getIntOr(AIR, player.getMaxAirSupply()))));
        restore(player, failed, FIRE, root, () -> player.setRemainingFireTicks(Math.max(0, view.getIntOr(FIRE, 0))));
        restore(player, failed, ABSORPTION, root, () ->
                player.setAbsorptionAmount(Math.max(0.0F, view.getFloatOr(ABSORPTION, 0.0F))));
        inventory.setChanged();
        return failed;
    }

    private static void restore(ServerPlayer player, List<String> failed, String field, CompoundTag root,
                                Runnable action) {
        if (!root.contains(field)) {
            return; // absent section == nothing to restore
        }
        if (!section(player, field, false, action)) {
            failed.add(field);
        }
    }

    private static boolean section(ServerPlayer player, String field, boolean encoding, Runnable action) {
        try {
            action.run();
            return true;
        } catch (Exception exception) {
            BotLog.error(asBot(player), encoding ? "bot_state_encode_failed" : "bot_state_restore_failed",
                    exception, "field", field);
            return false;
        }
    }

    private static AIPlayerEntity asBot(ServerPlayer player) {
        return player instanceof AIPlayerEntity bot ? bot : null;
    }
}

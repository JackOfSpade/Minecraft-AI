package io.github.zoyluo.minecraftai.persist;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.StackWithSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.ErrorReporter;

import java.util.ArrayList;
import java.util.List;

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
 * exactly once through {@link PlayerInventory#setStack}, which replaces (never adds to) the slot, so
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
    public static String encode(ServerPlayerEntity player) {
        NbtWriteView view = NbtWriteView.create(ErrorReporter.EMPTY, player.getRegistryManager());
        PlayerInventory inventory = player.getInventory();
        section(player, EQUIPMENT, true, () -> {
            WriteView equipment = view.get(EQUIPMENT);
            for (var entry : PlayerInventory.EQUIPMENT_SLOTS.int2ObjectEntrySet()) {
                ItemStack stack = inventory.getStack(entry.getIntKey());
                if (!stack.isEmpty()) {
                    equipment.put(entry.getValue().asString(), ItemStack.CODEC, stack);
                }
            }
        });
        section(player, SELECTED_SLOT, true, () -> view.putInt(SELECTED_SLOT, inventory.getSelectedSlot()));
        section(player, ENDER_ITEMS, true, () ->
                player.getEnderChestInventory().writeData(view.getListAppender(ENDER_ITEMS, StackWithSlot.CODEC)));
        section(player, XP_LEVEL, true, () -> {
            view.putInt(XP_LEVEL, player.experienceLevel);
            view.putFloat(XP_PROGRESS, player.experienceProgress);
            view.putInt(XP_TOTAL, player.totalExperience);
        });
        section(player, HUNGER, true, () -> player.getHungerManager().writeData(view.get(HUNGER)));
        section(player, ACTIVE_EFFECTS, true, () -> {
            var appender = view.getListAppender(ACTIVE_EFFECTS, StatusEffectInstance.CODEC);
            for (StatusEffectInstance effect : player.getStatusEffects()) {
                appender.add(effect);
            }
        });
        section(player, AIR, true, () -> view.putInt(AIR, player.getAir()));
        section(player, FIRE, true, () -> {
            if (player.getFireTicks() > 0) {
                view.putInt(FIRE, player.getFireTicks());
            }
        });
        section(player, ABSORPTION, true, () -> view.putFloat(ABSORPTION, player.getAbsorptionAmount()));
        return view.getNbt().toString();
    }

    /**
     * Restores the state written by {@link #encode}. A null/blank string (a record from before this
     * feature existed) restores nothing. Returns the names of the sections that failed to restore.
     */
    public static List<String> apply(ServerPlayerEntity player, String snbt) {
        List<String> failed = new ArrayList<>();
        if (snbt == null || snbt.isBlank()) {
            return failed;
        }
        NbtCompound root;
        try {
            root = StringNbtReader.readCompound(snbt);
        } catch (Exception exception) {
            BotLog.error(asBot(player), "bot_state_restore_failed", exception, "field", "*", "reason", "unparseable");
            failed.add("*");
            return failed;
        }
        ReadView view = NbtReadView.create(ErrorReporter.EMPTY, player.getRegistryManager(), root);
        PlayerInventory inventory = player.getInventory();

        restore(player, failed, EQUIPMENT, root, () -> {
            ReadView equipment = view.getReadView(EQUIPMENT);
            NbtCompound saved = root.getCompoundOrEmpty(EQUIPMENT);
            for (var entry : PlayerInventory.EQUIPMENT_SLOTS.int2ObjectEntrySet()) {
                EquipmentSlot slot = entry.getValue();
                if (!saved.contains(slot.asString())) {
                    continue;
                }
                ItemStack stack = equipment.read(slot.asString(), ItemStack.CODEC).orElse(null);
                if (stack == null) {
                    failed.add(EQUIPMENT + "." + slot.asString());
                    BotLog.error(asBot(player), "bot_state_restore_failed", null,
                            "field", EQUIPMENT + "." + slot.asString(), "reason", "undecodable_stack");
                    continue;
                }
                // PlayerInventory.setStack replaces the slot: applying twice can never duplicate an item.
                inventory.setStack(entry.getIntKey(), stack);
            }
        });
        restore(player, failed, SELECTED_SLOT, root, () ->
                inventory.setSelectedSlot(Math.max(0, Math.min(8, view.getInt(SELECTED_SLOT, 0)))));
        restore(player, failed, ENDER_ITEMS, root, () ->
                view.getOptionalTypedListView(ENDER_ITEMS, StackWithSlot.CODEC)
                        .ifPresent(list -> player.getEnderChestInventory().readData(list)));
        restore(player, failed, XP_LEVEL, root, () -> {
            player.setExperienceLevel(Math.max(0, view.getInt(XP_LEVEL, 0)));
            player.experienceProgress = Math.max(0.0F, Math.min(1.0F, view.getFloat(XP_PROGRESS, 0.0F)));
            player.totalExperience = Math.max(0, view.getInt(XP_TOTAL, 0));
        });
        restore(player, failed, HUNGER, root, () -> player.getHungerManager().readData(view.getReadView(HUNGER)));
        restore(player, failed, ACTIVE_EFFECTS, root, () ->
                view.getOptionalTypedListView(ACTIVE_EFFECTS, StatusEffectInstance.CODEC)
                        .ifPresent(list -> {
                            for (StatusEffectInstance effect : list) {
                                player.addStatusEffect(new StatusEffectInstance(effect));
                            }
                        }));
        restore(player, failed, AIR, root, () ->
                player.setAir(Math.min(player.getMaxAir(), view.getInt(AIR, player.getMaxAir()))));
        restore(player, failed, FIRE, root, () -> player.setFireTicks(Math.max(0, view.getInt(FIRE, 0))));
        restore(player, failed, ABSORPTION, root, () ->
                player.setAbsorptionAmount(Math.max(0.0F, view.getFloat(ABSORPTION, 0.0F))));
        inventory.markDirty();
        return failed;
    }

    private static void restore(ServerPlayerEntity player, List<String> failed, String field, NbtCompound root,
                                Runnable action) {
        if (!root.contains(field)) {
            return; // absent section == nothing to restore
        }
        if (!section(player, field, false, action)) {
            failed.add(field);
        }
    }

    private static boolean section(ServerPlayerEntity player, String field, boolean encoding, Runnable action) {
        try {
            action.run();
            return true;
        } catch (Exception exception) {
            BotLog.error(asBot(player), encoding ? "bot_state_encode_failed" : "bot_state_restore_failed",
                    exception, "field", field);
            return false;
        }
    }

    private static AIPlayerEntity asBot(ServerPlayerEntity player) {
        return player instanceof AIPlayerEntity bot ? bot : null;
    }
}

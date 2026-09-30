package dev.spawnbotswrapper.inhabitants.mc;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import dev.spawnbotswrapper.inhabitants.store.BotSnapshot;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Captures a live bot into a {@link BotSnapshot} and writes one back, with vanilla's own codecs and setters only
 * (the same ones a saved player goes through), so an item keeps its count, its damage and every data component and
 * nothing is refilled or repaired on the way.
 * <ul>
 *   <li>{@link #captureInventory}/{@link #restoreInventory} work on a bare {@link Inventory}, so they can be tested
 *       without a server.</li>
 *   <li>{@link #restoreVitals} with {@code full=false} is what a bot gets after a server restart: the inventory, XP and
 *       effects come back from the player's own saved data, and only what the fake-player spawn resets (health, hunger)
 *       is put back. {@code full=true} is a bot that was removed and comes back from nothing (dormancy): everything.</li>
 * </ul>
 * Nothing here throws for a bad entry; a slot or effect that cannot be read is skipped with a warning.
 */
final class BotSnapshots {
    private static final String EXHAUSTION_KEY = "foodExhaustionLevel";

    private BotSnapshots() {
    }

    // ------------------------------------------------------------------ capture

    /** The live state of {@code bot}, or null when it is dead or dying (a corpse is not a state to come back to). */
    static BotSnapshot capture(ServerPlayer bot, List<String> warnings) {
        if (bot.isDeadOrDying() || bot.getHealth() <= 0.0f) {
            return null;
        }
        BotSnapshot snapshot = new BotSnapshot();
        HolderLookup.Provider registries = bot.registryAccess();
        captureInventory(bot.getInventory(), registries, snapshot, warnings);
        snapshot.health = bot.getHealth();
        FoodData food = bot.getFoodData();
        snapshot.foodLevel = food.getFoodLevel();
        snapshot.saturation = food.getSaturationLevel();
        snapshot.exhaustion = exhaustionOf(food);
        DynamicOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        for (MobEffectInstance effect : bot.getActiveEffects()) {
            String json = encode(MobEffectInstance.CODEC, ops, effect);
            if (json != null) {
                snapshot.effects.add(json);
            } else {
                warnings.add("an active effect could not be saved: " + effect.getEffect().getRegisteredName());
            }
        }
        snapshot.xpLevel = bot.experienceLevel;
        snapshot.xpProgress = bot.experienceProgress;
        snapshot.xpTotal = bot.totalExperience;
        snapshot.fireTicks = bot.getRemainingFireTicks();
        snapshot.airSupply = bot.getAirSupply();
        return snapshot;
    }

    /** Every non-empty slot of the inventory (hotbar, main, armor, offhand) and the selected slot. */
    static void captureInventory(Inventory inventory, HolderLookup.Provider registries, BotSnapshot into,
                                 List<String> warnings) {
        DynamicOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        into.stacks = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String json = encode(ItemStack.CODEC, ops, stack);
            if (json == null) {
                warnings.add("slot " + slot + " (" + stack.getItem() + ") could not be saved");
                continue;
            }
            into.stacks.add(new BotSnapshot.Entry(slot, json));
        }
        into.selectedSlot = inventory.getSelectedSlot();
    }

    // ------------------------------------------------------------------ restore

    /**
     * Empties the inventory and writes the snapshot's stacks slot by slot (no equip sound, no game event: the same
     * silent path the profile dressing uses). Returns how many stacks were written.
     */
    static int restoreInventory(Inventory inventory, HolderLookup.Provider registries, BotSnapshot from,
                                List<String> warnings) {
        DynamicOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        // Decode first, so a snapshot that is unreadable as a whole does not leave the bot naked.
        List<Integer> slots = new ArrayList<>();
        List<ItemStack> stacks = new ArrayList<>();
        for (BotSnapshot.Entry entry : from.stacks) {
            if (entry.slot < 0 || entry.slot >= inventory.getContainerSize()) {
                warnings.add("slot " + entry.slot + " does not exist; skipped");
                continue;
            }
            ItemStack stack = decode(ItemStack.CODEC, ops, entry.stack);
            if (stack == null || stack.isEmpty()) {
                warnings.add("the stack of slot " + entry.slot + " could not be read; skipped");
                continue;
            }
            slots.add(entry.slot);
            stacks.add(stack);
        }
        if (slots.isEmpty() && !from.stacks.isEmpty()) {
            // Nothing in it could be read (a registry that changed under it): what the bot carries now is left alone.
            warnings.add("none of the " + from.stacks.size() + " saved stacks could be read; the inventory is unchanged");
            return 0;
        }
        inventory.clearContent();
        for (int i = 0; i < slots.size(); i++) {
            inventory.setItem(slots.get(i), stacks.get(i));
        }
        int selected = from.selectedSlot;
        inventory.setSelectedSlot(selected >= 0 && selected < Inventory.getSelectionSize() ? selected : 0);
        return slots.size();
    }

    /**
     * Puts the recorded health, hunger and (with {@code full}) effects, experience, fire and air back.
     * Health is clamped to the bot's CURRENT maximum and never set to zero or below: a snapshot cannot kill or heal
     * beyond what the bot can be.
     */
    static void restoreVitals(ServerPlayer bot, BotSnapshot from, boolean full, List<String> warnings) {
        if (from.health > 0.0f) {
            bot.setHealth(Math.min(bot.getMaxHealth(), from.health));
        }
        FoodData food = bot.getFoodData();
        food.setFoodLevel(Math.max(0, Math.min(20, from.foodLevel)));
        food.setSaturation(Math.max(0.0f, from.saturation));
        food.addExhaustion(Math.max(0.0f, from.exhaustion) - exhaustionOf(food));
        DynamicOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, bot.registryAccess());
        if (full) {
            bot.removeAllEffects();
        }
        for (String json : from.effects) {
            MobEffectInstance effect = decode(MobEffectInstance.CODEC, ops, json);
            if (effect == null) {
                warnings.add("an active effect could not be read; skipped");
            } else if (full || !bot.hasEffect(effect.getEffect())) {
                // After a restart the player's own data has already brought the effects back; only one that is missing is added.
                bot.addEffect(effect);
            }
        }
        if (full) {
            bot.experienceLevel = Math.max(0, from.xpLevel);
            bot.experienceProgress = Math.max(0.0f, Math.min(1.0f, from.xpProgress));
            bot.totalExperience = Math.max(0, from.xpTotal);
            bot.setRemainingFireTicks(Math.max(0, from.fireTicks));
            bot.setAirSupply(from.airSupply > 0 ? Math.min(from.airSupply, bot.getMaxAirSupply()) : bot.getMaxAirSupply());
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The exhaustion accumulator of a {@link FoodData} (private in vanilla), read through its own save routine. */
    static float exhaustionOf(FoodData food) {
        TagValueOutput out = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        food.addAdditionalSaveData(out);
        return out.buildResult().getFloatOr(EXHAUSTION_KEY, 0.0f);
    }

    private static <T> String encode(Codec<T> codec, DynamicOps<JsonElement> ops, T value) {
        try {
            Optional<JsonElement> json = codec.encodeStart(ops, value).result();
            return json.map(JsonElement::toString).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static <T> T decode(Codec<T> codec, DynamicOps<JsonElement> ops, String text) {
        try {
            return codec.parse(ops, JsonParser.parseString(text)).result().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}

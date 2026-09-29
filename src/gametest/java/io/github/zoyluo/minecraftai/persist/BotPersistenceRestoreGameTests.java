package io.github.zoyluo.minecraftai.persist;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Restart persistence of a bot's player state: capture through {@link BotPersistence#capture} (exactly
 * what the save path does), a JSON round trip through {@link RuntimeSnapshotCodec} (exactly what
 * runtime.json goes through), despawn, then {@link AIPlayerManager#respawnFromRecord} (the per-bot call
 * of the server-start restore). Regression: armor and offhand were silently dropped on every restart
 * because PlayerInventory.writeData only serializes the 36 main slots since 1.21.5.
 */
public final class BotPersistenceRestoreGameTests {
    private static final String ENV = "minecraftai-gametest:bot_persistence_restore_game_tests_";

    @GameTest(environment = ENV + "full_player_state_survives_restart_round_trip", maxTicks = 200)
    public void fullPlayerStateSurvivesRestartRoundTrip(TestContext context) {
        String name = "RestartStateGT";
        AIPlayerEntity bot = spawn(context, name);
        var enchantments = context.getWorld().getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);

        ItemStack helmet = new ItemStack(Items.DIAMOND_HELMET);
        helmet.addEnchantment(enchantments.getEntry(Enchantments.PROTECTION.getValue()).orElseThrow(), 3);
        helmet.setDamage(12);
        helmet.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Guardian Helm"));
        ItemStack chest = new ItemStack(Items.IRON_CHESTPLATE);
        chest.setDamage(20);
        ItemStack legs = new ItemStack(Items.GOLDEN_LEGGINGS);
        legs.addEnchantment(enchantments.getEntry(Enchantments.UNBREAKING.getValue()).orElseThrow(), 2);
        ItemStack boots = new ItemStack(Items.NETHERITE_BOOTS);
        boots.addEnchantment(enchantments.getEntry(Enchantments.FEATHER_FALLING.getValue()).orElseThrow(), 4);
        ItemStack shield = new ItemStack(Items.SHIELD);
        shield.setDamage(7);
        PlayerInventory inventory = bot.getInventory();
        bot.equipStack(EquipmentSlot.HEAD, helmet.copy());
        bot.equipStack(EquipmentSlot.CHEST, chest.copy());
        bot.equipStack(EquipmentSlot.LEGS, legs.copy());
        bot.equipStack(EquipmentSlot.FEET, boots.copy());
        bot.equipStack(EquipmentSlot.OFFHAND, shield.copy());

        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
        pick.setDamage(31);
        pick.addEnchantment(enchantments.getEntry(Enchantments.EFFICIENCY.getValue()).orElseThrow(), 2);
        inventory.setStack(0, new ItemStack(Items.BREAD, 9));
        inventory.setStack(4, pick.copy());
        inventory.setStack(8, new ItemStack(Items.TORCH, 33));
        inventory.setStack(20, new ItemStack(Items.COBBLESTONE, 64));
        inventory.setSelectedSlot(4);
        ItemStack enderGem = new ItemStack(Items.DIAMOND, 5);
        enderGem.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Vault Gem"));
        bot.getEnderChestInventory().setStack(0, enderGem.copy());
        bot.getEnderChestInventory().setStack(5, new ItemStack(Items.GOLDEN_APPLE, 3));

        bot.setExperienceLevel(7);
        bot.experienceProgress = 0.25F;
        bot.totalExperience = 77;
        bot.getHungerManager().setFoodLevel(17);
        bot.getHungerManager().setSaturationLevel(3.5F);
        bot.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 6000, 1));
        bot.setAir(150);
        Map<String, Integer> before = census(bot);

        BotRecord record = roundTrip(capture(bot, context));
        require(context, record.playerStateNbt() != null && !record.playerStateNbt().isBlank(),
                "capture did not record any player state");
        require(context, AIPlayerManager.INSTANCE.despawn(context.getWorld().getServer(), name),
                "despawn failed");

        context.waitAndRun(3, () -> {
            AIPlayerEntity restored = AIPlayerManager.INSTANCE
                    .respawnFromRecord(context.getWorld().getServer(), record)
                    .orElseThrow(() -> new IllegalStateException("restore failed"));
            try {
                same(context, "head", helmet, restored.getEquippedStack(EquipmentSlot.HEAD));
                same(context, "chest", chest, restored.getEquippedStack(EquipmentSlot.CHEST));
                same(context, "legs", legs, restored.getEquippedStack(EquipmentSlot.LEGS));
                same(context, "feet", boots, restored.getEquippedStack(EquipmentSlot.FEET));
                same(context, "offhand", shield, restored.getEquippedStack(EquipmentSlot.OFFHAND));
                PlayerInventory after = restored.getInventory();
                same(context, "slot0", new ItemStack(Items.BREAD, 9), after.getStack(0));
                same(context, "slot4", pick, after.getStack(4));
                same(context, "slot8", new ItemStack(Items.TORCH, 33), after.getStack(8));
                same(context, "slot20", new ItemStack(Items.COBBLESTONE, 64), after.getStack(20));
                require(context, after.getSelectedSlot() == 4, "selected hotbar slot " + after.getSelectedSlot());
                same(context, "ender0", enderGem, restored.getEnderChestInventory().getStack(0));
                same(context, "ender5", new ItemStack(Items.GOLDEN_APPLE, 3),
                        restored.getEnderChestInventory().getStack(5));
                require(context, restored.experienceLevel == 7 && restored.experienceProgress == 0.25F
                                && restored.totalExperience == 77,
                        "xp " + restored.experienceLevel + "/" + restored.experienceProgress
                                + "/" + restored.totalExperience);
                require(context, restored.getHungerManager().getFoodLevel() == 17
                                && restored.getHungerManager().getSaturationLevel() == 3.5F,
                        "hunger " + restored.getHungerManager().getFoodLevel() + "/"
                                + restored.getHungerManager().getSaturationLevel());
                StatusEffectInstance effect = restored.getStatusEffect(StatusEffects.RESISTANCE);
                require(context, effect != null && effect.getAmplifier() == 1
                                && effect.getDuration() > 5000 && effect.getDuration() <= 6000,
                        "status effect " + effect);
                require(context, restored.getAir() == 150, "air " + restored.getAir());
                require(context, census(restored).equals(before),
                        "item census changed across restore: " + before + " -> " + census(restored));

                // Restoring the same record a second time must be idempotent (nothing duplicated).
                BotPersistence.applyInventory(restored, record.inventoryNbt());
                BotPersistence.applyPlayerState(restored, record.playerStateNbt());
                require(context, census(restored).equals(before),
                        "second restore duplicated or lost items: " + census(restored));
            } finally {
                AIPlayerManager.INSTANCE.despawn(context.getWorld().getServer(), name);
            }
            context.complete();
        });
    }

    @GameTest(environment = ENV + "legacy_record_without_player_state_still_restores_inventory", maxTicks = 200)
    public void legacyRecordWithoutPlayerStateStillRestoresInventory(TestContext context) {
        String name = "RestartLegacyGT";
        AIPlayerEntity bot = spawn(context, name);
        bot.getInventory().setStack(3, new ItemStack(Items.OAK_LOG, 12));
        bot.getInventory().setStack(5, new ItemStack(Items.IRON_SWORD));
        bot.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));

        // Build a runtime.json exactly as an older build wrote it: no playerStateNbt key at all.
        RuntimeSnapshot snapshot = new RuntimeSnapshot(RuntimeSnapshot.CURRENT_SCHEMA, "t", "old", "s",
                List.of(new PersistedBot(capture(bot, context), MissionRuntimeRecord.empty())), List.of());
        JsonObject root = JsonParser.parseString(RuntimeSnapshotCodec.encode(snapshot)).getAsJsonObject();
        JsonArray bots = root.getAsJsonArray("bots");
        JsonObject botJson = bots.get(0).getAsJsonObject().getAsJsonObject("bot");
        require(context, botJson.remove("playerStateNbt") != null, "new format did not write playerStateNbt");
        RuntimeSnapshotCodec.DecodeResult decoded = RuntimeSnapshotCodec.decode(new StringReader(root.toString()));
        require(context, decoded.status() == RuntimeSnapshotCodec.Status.OK, "old format rejected: " + decoded.status());
        BotRecord record = decoded.snapshot().bots().getFirst().bot();
        require(context, record.playerStateNbt() == null, "old record decoded a non-null playerStateNbt");

        require(context, AIPlayerManager.INSTANCE.despawn(context.getWorld().getServer(), name), "despawn failed");
        context.waitAndRun(3, () -> {
            AIPlayerEntity restored = AIPlayerManager.INSTANCE
                    .respawnFromRecord(context.getWorld().getServer(), record)
                    .orElseThrow(() -> new IllegalStateException("restore failed"));
            try {
                same(context, "slot3", new ItemStack(Items.OAK_LOG, 12), restored.getInventory().getStack(3));
                same(context, "slot5", new ItemStack(Items.IRON_SWORD), restored.getInventory().getStack(5));
                require(context, restored.getEquippedStack(EquipmentSlot.CHEST).isEmpty(),
                        "old record must restore no equipment (nothing to restore)");
                require(context, restored.getEnderChestInventory().isEmpty(), "old record grew ender items");
            } finally {
                AIPlayerManager.INSTANCE.despawn(context.getWorld().getServer(), name);
            }
            context.complete();
        });
    }

    @GameTest(environment = ENV + "offhand_torch_and_partial_armor_restore_without_touching_empty_slots", maxTicks = 200)
    public void offhandTorchAndPartialArmorRestoreWithoutTouchingEmptySlots(TestContext context) {
        String name = "RestartTorchGT";
        AIPlayerEntity bot = spawn(context, name);
        bot.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.TORCH, 13));
        bot.equipStack(EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));
        bot.getInventory().setStack(1, new ItemStack(Items.IRON_CHESTPLATE)); // spare armor in main inventory
        Map<String, Integer> before = census(bot);

        BotRecord record = roundTrip(capture(bot, context));
        require(context, AIPlayerManager.INSTANCE.despawn(context.getWorld().getServer(), name), "despawn failed");
        context.waitAndRun(3, () -> {
            AIPlayerEntity restored = AIPlayerManager.INSTANCE
                    .respawnFromRecord(context.getWorld().getServer(), record)
                    .orElseThrow(() -> new IllegalStateException("restore failed"));
            try {
                same(context, "offhand", new ItemStack(Items.TORCH, 13), restored.getEquippedStack(EquipmentSlot.OFFHAND));
                same(context, "feet", new ItemStack(Items.LEATHER_BOOTS), restored.getEquippedStack(EquipmentSlot.FEET));
                require(context, restored.getEquippedStack(EquipmentSlot.HEAD).isEmpty()
                                && restored.getEquippedStack(EquipmentSlot.CHEST).isEmpty()
                                && restored.getEquippedStack(EquipmentSlot.LEGS).isEmpty(),
                        "empty armor slots were filled on restore (main-inventory chestplate auto-equipped?)");
                same(context, "spare chestplate", new ItemStack(Items.IRON_CHESTPLATE), restored.getInventory().getStack(1));
                require(context, census(restored).equals(before), "census " + census(restored) + " vs " + before);
            } finally {
                AIPlayerManager.INSTANCE.despawn(context.getWorld().getServer(), name);
            }
            context.complete();
        });
    }

    @GameTest(environment = ENV + "one_bad_field_does_not_abort_the_rest_of_the_restore", maxTicks = 200)
    public void oneBadFieldDoesNotAbortTheRestOfTheRestore(TestContext context) {
        String name = "RestartBadGT";
        AIPlayerEntity bot = spawn(context, name);
        try {
            String snbt = "{Equipment:{head:{id:\"minecraft:no_such_item\",count:1},"
                    + "chest:{id:\"minecraft:iron_chestplate\",count:1}},"
                    + "XpLevel:7,XpProgress:0.5f,XpTotal:91,SelectedSlot:3}";
            List<String> failed = BotPlayerState.apply(bot, snbt);
            require(context, failed.equals(List.of("Equipment.head")), "failed fields: " + failed);
            require(context, bot.getEquippedStack(EquipmentSlot.HEAD).isEmpty(), "bad head stack was equipped");
            same(context, "chest", new ItemStack(Items.IRON_CHESTPLATE), bot.getEquippedStack(EquipmentSlot.CHEST));
            require(context, bot.experienceLevel == 7 && bot.totalExperience == 91, "xp not restored after bad head");
            require(context, bot.getInventory().getSelectedSlot() == 3, "selected slot not restored after bad head");
            require(context, BotPlayerState.apply(bot, "this is not snbt{").equals(List.of("*")),
                    "unparseable state must be reported, not thrown");
            require(context, BotPlayerState.apply(bot, null).isEmpty() && BotPlayerState.apply(bot, "  ").isEmpty(),
                    "null/blank state must restore nothing");
        } finally {
            AIPlayerManager.INSTANCE.despawn(context.getWorld().getServer(), name);
        }
        context.complete();
    }

    private static BotRecord capture(AIPlayerEntity bot, TestContext context) {
        BotRecord record = BotPersistence.capture(bot);
        require(context, record != null, "capture returned null");
        return record;
    }

    /** The exact serialization runtime.json goes through. */
    private static BotRecord roundTrip(BotRecord record) {
        RuntimeSnapshot snapshot = new RuntimeSnapshot(RuntimeSnapshot.CURRENT_SCHEMA, "t", "test", "s",
                List.of(new PersistedBot(record, MissionRuntimeRecord.empty())), List.of());
        RuntimeSnapshotCodec.DecodeResult decoded =
                RuntimeSnapshotCodec.decode(new StringReader(RuntimeSnapshotCodec.encode(snapshot)));
        if (decoded.status() != RuntimeSnapshotCodec.Status.OK) {
            throw new IllegalStateException("round trip failed: " + decoded.status());
        }
        return decoded.snapshot().bots().getFirst().bot();
    }

    /** Item -> total count over main + equipment slots and the ender chest. */
    private static Map<String, Integer> census(AIPlayerEntity bot) {
        Map<String, Integer> totals = new HashMap<>();
        for (int slot = 0; slot < bot.getInventory().size(); slot++) {
            add(totals, "inv:", bot.getInventory().getStack(slot));
        }
        for (int slot = 0; slot < bot.getEnderChestInventory().size(); slot++) {
            add(totals, "ender:", bot.getEnderChestInventory().getStack(slot));
        }
        return totals;
    }

    private static void add(Map<String, Integer> totals, String prefix, ItemStack stack) {
        if (!stack.isEmpty()) {
            Item item = stack.getItem();
            totals.merge(prefix + item, stack.getCount(), Integer::sum);
        }
    }

    private static AIPlayerEntity spawn(TestContext context, String name) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(3, 2, 3));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = start.add(dx, 0, dz);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        return bot;
    }

    private static void same(TestContext context, String label, ItemStack expected, ItemStack actual) {
        require(context, ItemStack.areEqual(expected, actual),
                label + " differs: expected " + expected + " " + expected.getComponentChanges()
                        + " got " + actual + " " + actual.getComponentChanges());
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(Text.of(message));
        }
    }
}

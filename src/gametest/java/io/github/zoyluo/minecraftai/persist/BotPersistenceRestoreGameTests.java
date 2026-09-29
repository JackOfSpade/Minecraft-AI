package io.github.zoyluo.minecraftai.persist;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
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
    public void fullPlayerStateSurvivesRestartRoundTrip(GameTestHelper context) {
        String name = "RestartStateGT";
        AIPlayerEntity bot = spawn(context, name);
        var enchantments = context.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);

        ItemStack helmet = new ItemStack(Items.DIAMOND_HELMET);
        helmet.enchant(enchantments.get(Enchantments.PROTECTION.identifier()).orElseThrow(), 3);
        helmet.setDamageValue(12);
        helmet.set(DataComponents.CUSTOM_NAME, Component.literal("Guardian Helm"));
        ItemStack chest = new ItemStack(Items.IRON_CHESTPLATE);
        chest.setDamageValue(20);
        ItemStack legs = new ItemStack(Items.GOLDEN_LEGGINGS);
        legs.enchant(enchantments.get(Enchantments.UNBREAKING.identifier()).orElseThrow(), 2);
        ItemStack boots = new ItemStack(Items.NETHERITE_BOOTS);
        boots.enchant(enchantments.get(Enchantments.FEATHER_FALLING.identifier()).orElseThrow(), 4);
        ItemStack shield = new ItemStack(Items.SHIELD);
        shield.setDamageValue(7);
        Inventory inventory = bot.getInventory();
        bot.setItemSlot(EquipmentSlot.HEAD, helmet.copy());
        bot.setItemSlot(EquipmentSlot.CHEST, chest.copy());
        bot.setItemSlot(EquipmentSlot.LEGS, legs.copy());
        bot.setItemSlot(EquipmentSlot.FEET, boots.copy());
        bot.setItemSlot(EquipmentSlot.OFFHAND, shield.copy());

        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
        pick.setDamageValue(31);
        pick.enchant(enchantments.get(Enchantments.EFFICIENCY.identifier()).orElseThrow(), 2);
        inventory.setItem(0, new ItemStack(Items.BREAD, 9));
        inventory.setItem(4, pick.copy());
        inventory.setItem(8, new ItemStack(Items.TORCH, 33));
        inventory.setItem(20, new ItemStack(Items.COBBLESTONE, 64));
        inventory.setSelectedSlot(4);
        ItemStack enderGem = new ItemStack(Items.DIAMOND, 5);
        enderGem.set(DataComponents.CUSTOM_NAME, Component.literal("Vault Gem"));
        bot.getEnderChestInventory().setItem(0, enderGem.copy());
        bot.getEnderChestInventory().setItem(5, new ItemStack(Items.GOLDEN_APPLE, 3));

        bot.setExperienceLevels(7);
        bot.experienceProgress = 0.25F;
        bot.totalExperience = 77;
        bot.getFoodData().setFoodLevel(17);
        bot.getFoodData().setSaturation(3.5F);
        bot.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 6000, 1));
        bot.setAirSupply(150);
        Map<String, Integer> before = census(bot);

        BotRecord record = roundTrip(capture(bot, context));
        require(context, record.playerStateNbt() != null && !record.playerStateNbt().isBlank(),
                "capture did not record any player state");
        require(context, AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), name),
                "despawn failed");

        context.runAfterDelay(3, () -> {
            AIPlayerEntity restored = AIPlayerManager.INSTANCE
                    .respawnFromRecord(context.getLevel().getServer(), record)
                    .orElseThrow(() -> new IllegalStateException("restore failed"));
            try {
                same(context, "head", helmet, restored.getItemBySlot(EquipmentSlot.HEAD));
                same(context, "chest", chest, restored.getItemBySlot(EquipmentSlot.CHEST));
                same(context, "legs", legs, restored.getItemBySlot(EquipmentSlot.LEGS));
                same(context, "feet", boots, restored.getItemBySlot(EquipmentSlot.FEET));
                same(context, "offhand", shield, restored.getItemBySlot(EquipmentSlot.OFFHAND));
                Inventory after = restored.getInventory();
                same(context, "slot0", new ItemStack(Items.BREAD, 9), after.getItem(0));
                same(context, "slot4", pick, after.getItem(4));
                same(context, "slot8", new ItemStack(Items.TORCH, 33), after.getItem(8));
                same(context, "slot20", new ItemStack(Items.COBBLESTONE, 64), after.getItem(20));
                require(context, after.getSelectedSlot() == 4, "selected hotbar slot " + after.getSelectedSlot());
                same(context, "ender0", enderGem, restored.getEnderChestInventory().getItem(0));
                same(context, "ender5", new ItemStack(Items.GOLDEN_APPLE, 3),
                        restored.getEnderChestInventory().getItem(5));
                require(context, restored.experienceLevel == 7 && restored.experienceProgress == 0.25F
                                && restored.totalExperience == 77,
                        "xp " + restored.experienceLevel + "/" + restored.experienceProgress
                                + "/" + restored.totalExperience);
                require(context, restored.getFoodData().getFoodLevel() == 17
                                && restored.getFoodData().getSaturationLevel() == 3.5F,
                        "hunger " + restored.getFoodData().getFoodLevel() + "/"
                                + restored.getFoodData().getSaturationLevel());
                MobEffectInstance effect = restored.getEffect(MobEffects.RESISTANCE);
                require(context, effect != null && effect.getAmplifier() == 1
                                && effect.getDuration() > 5000 && effect.getDuration() <= 6000,
                        "status effect " + effect);
                require(context, restored.getAirSupply() == 150, "air " + restored.getAirSupply());
                require(context, census(restored).equals(before),
                        "item census changed across restore: " + before + " -> " + census(restored));

                // Restoring the same record a second time must be idempotent (nothing duplicated).
                BotPersistence.applyInventory(restored, record.inventoryNbt());
                BotPersistence.applyPlayerState(restored, record.playerStateNbt());
                require(context, census(restored).equals(before),
                        "second restore duplicated or lost items: " + census(restored));
            } finally {
                AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), name);
            }
            context.succeed();
        });
    }

    @GameTest(environment = ENV + "legacy_record_without_player_state_still_restores_inventory", maxTicks = 200)
    public void legacyRecordWithoutPlayerStateStillRestoresInventory(GameTestHelper context) {
        String name = "RestartLegacyGT";
        AIPlayerEntity bot = spawn(context, name);
        bot.getInventory().setItem(3, new ItemStack(Items.OAK_LOG, 12));
        bot.getInventory().setItem(5, new ItemStack(Items.IRON_SWORD));
        bot.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));

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

        require(context, AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), name), "despawn failed");
        context.runAfterDelay(3, () -> {
            AIPlayerEntity restored = AIPlayerManager.INSTANCE
                    .respawnFromRecord(context.getLevel().getServer(), record)
                    .orElseThrow(() -> new IllegalStateException("restore failed"));
            try {
                same(context, "slot3", new ItemStack(Items.OAK_LOG, 12), restored.getInventory().getItem(3));
                same(context, "slot5", new ItemStack(Items.IRON_SWORD), restored.getInventory().getItem(5));
                require(context, restored.getItemBySlot(EquipmentSlot.CHEST).isEmpty(),
                        "old record must restore no equipment (nothing to restore)");
                require(context, restored.getEnderChestInventory().isEmpty(), "old record grew ender items");
            } finally {
                AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), name);
            }
            context.succeed();
        });
    }

    @GameTest(environment = ENV + "offhand_torch_and_partial_armor_restore_without_touching_empty_slots", maxTicks = 200)
    public void offhandTorchAndPartialArmorRestoreWithoutTouchingEmptySlots(GameTestHelper context) {
        String name = "RestartTorchGT";
        AIPlayerEntity bot = spawn(context, name);
        bot.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TORCH, 13));
        bot.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));
        bot.getInventory().setItem(1, new ItemStack(Items.IRON_CHESTPLATE)); // spare armor in main inventory
        Map<String, Integer> before = census(bot);

        BotRecord record = roundTrip(capture(bot, context));
        require(context, AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), name), "despawn failed");
        context.runAfterDelay(3, () -> {
            AIPlayerEntity restored = AIPlayerManager.INSTANCE
                    .respawnFromRecord(context.getLevel().getServer(), record)
                    .orElseThrow(() -> new IllegalStateException("restore failed"));
            try {
                same(context, "offhand", new ItemStack(Items.TORCH, 13), restored.getItemBySlot(EquipmentSlot.OFFHAND));
                same(context, "feet", new ItemStack(Items.LEATHER_BOOTS), restored.getItemBySlot(EquipmentSlot.FEET));
                require(context, restored.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
                                && restored.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                                && restored.getItemBySlot(EquipmentSlot.LEGS).isEmpty(),
                        "empty armor slots were filled on restore (main-inventory chestplate auto-equipped?)");
                same(context, "spare chestplate", new ItemStack(Items.IRON_CHESTPLATE), restored.getInventory().getItem(1));
                require(context, census(restored).equals(before), "census " + census(restored) + " vs " + before);
            } finally {
                AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), name);
            }
            context.succeed();
        });
    }

    @GameTest(environment = ENV + "one_bad_field_does_not_abort_the_rest_of_the_restore", maxTicks = 200)
    public void oneBadFieldDoesNotAbortTheRestOfTheRestore(GameTestHelper context) {
        String name = "RestartBadGT";
        AIPlayerEntity bot = spawn(context, name);
        try {
            String snbt = "{Equipment:{head:{id:\"minecraft:no_such_item\",count:1},"
                    + "chest:{id:\"minecraft:iron_chestplate\",count:1}},"
                    + "XpLevel:7,XpProgress:0.5f,XpTotal:91,SelectedSlot:3}";
            List<String> failed = BotPlayerState.apply(bot, snbt);
            require(context, failed.equals(List.of("Equipment.head")), "failed fields: " + failed);
            require(context, bot.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "bad head stack was equipped");
            same(context, "chest", new ItemStack(Items.IRON_CHESTPLATE), bot.getItemBySlot(EquipmentSlot.CHEST));
            require(context, bot.experienceLevel == 7 && bot.totalExperience == 91, "xp not restored after bad head");
            require(context, bot.getInventory().getSelectedSlot() == 3, "selected slot not restored after bad head");
            require(context, BotPlayerState.apply(bot, "this is not snbt{").equals(List.of("*")),
                    "unparseable state must be reported, not thrown");
            require(context, BotPlayerState.apply(bot, null).isEmpty() && BotPlayerState.apply(bot, "  ").isEmpty(),
                    "null/blank state must restore nothing");
        } finally {
            AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), name);
        }
        context.succeed();
    }

    private static BotRecord capture(AIPlayerEntity bot, GameTestHelper context) {
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
        for (int slot = 0; slot < bot.getInventory().getContainerSize(); slot++) {
            add(totals, "inv:", bot.getInventory().getItem(slot));
        }
        for (int slot = 0; slot < bot.getEnderChestInventory().getContainerSize(); slot++) {
            add(totals, "ender:", bot.getEnderChestInventory().getItem(slot));
        }
        return totals;
    }

    private static void add(Map<String, Integer> totals, String prefix, ItemStack stack) {
        if (!stack.isEmpty()) {
            Item item = stack.getItem();
            totals.merge(prefix + item, stack.getCount(), Integer::sum);
        }
    }

    private static AIPlayerEntity spawn(GameTestHelper context, String name) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(3, 2, 3));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = start.offset(dx, 0, dz);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        return bot;
    }

    private static void same(GameTestHelper context, String label, ItemStack expected, ItemStack actual) {
        require(context, ItemStack.matches(expected, actual),
                label + " differs: expected " + expected + " " + expected.getComponentsPatch()
                        + " got " + actual + " " + actual.getComponentsPatch());
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}

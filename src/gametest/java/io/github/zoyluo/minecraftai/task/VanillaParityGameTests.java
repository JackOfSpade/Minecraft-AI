package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.craft.CraftingHelper;
import io.github.zoyluo.minecraftai.craft.RecipeRegistry;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * A bot smelts, trades and crafts under the rules a survival player is bound by: no item is deleted, kept for free or
 * invented (fuel above the stack maximum, a partly inserted furnace output, a trade whose result does not fit, a crafting
 * remainder), the vanilla bookkeeping happens (recipe experience, trade uses and villager experience) and the real stacks
 * with their components are what moves.
 */
public final class VanillaParityGameTests {
    private static final String ENV = "minecraftai-gametest:vanilla_parity_game_tests_";

    @GameTest(environment = ENV + "furnace_fuel_above_the_slot_maximum_stays_in_the_inventory", maxTicks = 60)
    public void furnaceFuelAboveTheSlotMaximumStaysInTheInventory(GameTestHelper context) {
        Fixture f = fixture(context, "ParityFuelGT");
        AIPlayerEntity bot = f.bot();
        bot.getInventory().setItem(0, new ItemStack(Items.STICK, 64));
        bot.getInventory().setItem(1, new ItemStack(Items.STICK, 36));
        AbstractFurnaceBlockEntity furnace = f.furnace();

        int moved = SmeltTask.moveIntoFurnaceSlot(bot, furnace, 1, Items.STICK, 100);
        require(context, moved == 64, "the fuel slot takes exactly its stack maximum, moved " + moved);
        require(context, furnace.getItem(1).is(Items.STICK) && furnace.getItem(1).getCount() == 64,
                "fuel slot holds " + furnace.getItem(1));
        int left = 0;
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            if (stack.is(Items.STICK)) {
                left += stack.getCount();
            }
        }
        require(context, left == 36, "the 36 sticks that did not fit vanished or were kept twice: " + left + " left");

        // A named input keeps its name when it moves, and a stack that differs from what the slot holds is not merged into it.
        ItemStack named = new ItemStack(Items.RAW_IRON, 5);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Vein Find"));
        bot.getInventory().setItem(2, named);
        require(context, SmeltTask.moveIntoFurnaceSlot(bot, furnace, 0, Items.RAW_IRON, 5) == 5, "input not moved");
        require(context, furnace.getItem(0).get(DataComponents.CUSTOM_NAME) != null,
                "the real stack was rebuilt from the item: its components are gone");
        bot.getInventory().setItem(3, new ItemStack(Items.RAW_IRON, 4));
        require(context, SmeltTask.moveIntoFurnaceSlot(bot, furnace, 0, Items.RAW_IRON, 4) == 0,
                "a plain stack was merged into a named one");
        cleanup(context, f);
    }

    @GameTest(environment = ENV + "furnace_output_is_removed_only_as_far_as_it_was_inserted_and_pays_experience", maxTicks = 60)
    public void furnaceOutputIsRemovedOnlyAsFarAsItWasInsertedAndPaysExperience(GameTestHelper context) {
        Fixture f = fixture(context, "ParityOutputGT");
        AIPlayerEntity bot = f.bot();
        ServerLevel world = context.getLevel();
        AbstractFurnaceBlockEntity furnace = f.furnace();

        // The bot has room for exactly three more ingots of the furnace's (named) kind.
        ItemStack ingot = new ItemStack(Items.IRON_INGOT, 10);
        ingot.set(DataComponents.CUSTOM_NAME, Component.literal("Forge Batch"));
        for (int slot = 0; slot < 35; slot++) {
            bot.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        bot.getInventory().setItem(35, ingot.copyWithCount(61));
        furnace.setItem(2, ingot.copy());
        var recipe = world.getServer().getRecipeManager()
                .getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(new ItemStack(Items.RAW_IRON)), world)
                .orElseThrow(() -> new IllegalStateException("no smelting recipe for raw iron"));
        for (int i = 0; i < 10; i++) {
            furnace.setRecipeUsed(recipe);
        }

        int taken = SmeltTask.takeOutput(bot, furnace, 10);
        require(context, taken == 3, "only 3 of the 10 fit, but " + taken + " were reported taken");
        require(context, furnace.getItem(2).getCount() == 7,
                "the furnace lost items that never reached the inventory: " + furnace.getItem(2).getCount() + " left of 7");
        require(context, furnace.getItem(2).get(DataComponents.CUSTOM_NAME) != null,
                "the rest of the output lost its components");
        require(context, bot.getInventory().getItem(35).getCount() == 64, "inventory count " + bot.getInventory().getItem(35));
        int xp = 0;
        for (ExperienceOrb orb : world.getEntitiesOfClass(ExperienceOrb.class, bot.getBoundingBox().inflate(4.0D), o -> true)) {
            xp += orb.getValue();
        }
        require(context, xp >= 6, "taking the output paid no recipe experience: " + xp);
        cleanup(context, f);
    }

    @GameTest(environment = ENV + "trade_needs_the_whole_result_to_fit_and_runs_the_villagers_bookkeeping", maxTicks = 80)
    public void tradeNeedsTheWholeResultToFitAndRunsTheVillagersBookkeeping(GameTestHelper context) {
        Fixture f = fixture(context, "ParityTradeGT");
        AIPlayerEntity bot = f.bot();
        Villager villager = villager(context, bot, 2.0D);
        MerchantOffer offer = new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.ARROW, 16), 12, 7, 0.05F);
        // overrideOffers is a no-op on the server side; the live offer list is what the villager sells from.
        villager.getOffers().add(offer);
        int xpBefore = villager.getVillagerXp();

        // Room for only 10 of the 16 arrows: the trade must be refused without taking the emerald.
        for (int slot = 0; slot < 34; slot++) {
            bot.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        bot.getInventory().setItem(34, new ItemStack(Items.ARROW, 54));
        bot.getInventory().setItem(35, new ItemStack(Items.EMERALD, 2));
        TradeTask refused = new TradeTask(Items.ARROW, 16);
        refused.start(bot);
        for (int i = 0; i < 6 && refused.state() == TaskState.RUNNING; i++) {
            refused.tick(bot);
        }
        require(context, refused.state() == TaskState.FAILED && refused.failureReason().contains("inventory_full"),
                "a result that does not fully fit must be refused, got " + refused.state() + " " + refused.failureReason());
        require(context, bot.getInventory().getItem(35).is(Items.EMERALD) && bot.getInventory().getItem(35).getCount() == 2,
                "the emerald was paid although the trade was refused");
        require(context, bot.getInventory().getItem(34).getCount() == 54, "arrows were partly inserted");
        require(context, offer.getUses() == 0 && villager.getVillagerXp() == xpBefore,
                "a refused trade was counted: uses " + offer.getUses());
        require(context, bot.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(
                        net.minecraft.stats.Stats.TALKED_TO_VILLAGER)) == 1,
                "opening the refused trade did not award the talked-to-villager statistic");

        // With room, the trade completes and the villager does its bookkeeping.
        bot.getInventory().setItem(0, ItemStack.EMPTY);
        bot.getInventory().setItem(1, ItemStack.EMPTY);
        TradeTask task = new TradeTask(Items.ARROW, 16);
        task.start(bot);
        for (int i = 0; i < 6 && task.state() == TaskState.RUNNING; i++) {
            task.tick(bot);
        }
        require(context, task.state() == TaskState.COMPLETED, "trade did not complete: " + task.failureReason());
        int arrows = 0;
        int emeralds = 0;
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            if (stack.is(Items.ARROW)) {
                arrows += stack.getCount();
            }
            if (stack.is(Items.EMERALD)) {
                emeralds += stack.getCount();
            }
        }
        require(context, arrows == 54 + 16 && emeralds == 1, "arrows " + arrows + " emeralds " + emeralds);
        require(context, offer.getUses() == 1, "the offer's uses were not counted: " + offer.getUses());
        require(context, villager.getVillagerXp() > xpBefore, "the villager got no trade experience");
        require(context, bot.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.TRADED_WITH_VILLAGER)) == 1,
                "the traded-with-villager statistic was not awarded");
        require(context, bot.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(
                        net.minecraft.stats.Stats.TALKED_TO_VILLAGER)) == 2,
                "each opened trade screen must award talked-to-villager");
        require(context, villager.getTradingPlayer() == null, "the bot was left as the villager's trading player");
        cleanup(context, f);
    }

    @GameTest(environment = ENV + "sleeping_villager_does_not_trade", maxTicks = 60)
    public void sleepingVillagerDoesNotTrade(GameTestHelper context) {
        Fixture f = fixture(context, "ParityAsleepGT");
        AIPlayerEntity bot = f.bot();
        Villager villager = villager(context, bot, 2.0D);
        MerchantOffer offer = new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.ARROW, 16), 12, 7, 0.05F);
        // overrideOffers is a no-op on the server side; the live offer list is what the villager sells from.
        villager.getOffers().add(offer);
        villager.startSleeping(villager.blockPosition());
        require(context, villager.isSleeping(), "fixture: the villager did not fall asleep");
        bot.getInventory().setItem(0, new ItemStack(Items.EMERALD, 3));

        TradeTask task = new TradeTask(Items.ARROW, 16);
        task.start(bot);
        for (int i = 0; i < 6 && task.state() == TaskState.RUNNING; i++) {
            task.tick(bot);
        }
        require(context, task.state() == TaskState.FAILED, "a sleeping villager was traded with: " + task.state());
        require(context, bot.getInventory().getItem(0).getCount() == 3 && offer.getUses() == 0,
                "a sleeping villager took payment or counted a use");
        cleanup(context, f);
    }

    @GameTest(environment = ENV + "cake_crafting_hands_back_the_empty_buckets", maxTicks = 60)
    public void cakeCraftingHandsBackTheEmptyBuckets(GameTestHelper context) {
        Fixture f = fixture(context, "ParityCakeGT");
        AIPlayerEntity bot = f.bot();
        bot.getInventory().setItem(0, new ItemStack(Items.MILK_BUCKET, 1));
        bot.getInventory().setItem(1, new ItemStack(Items.MILK_BUCKET, 1));
        bot.getInventory().setItem(2, new ItemStack(Items.MILK_BUCKET, 1));
        bot.getInventory().setItem(3, new ItemStack(Items.SUGAR, 2));
        bot.getInventory().setItem(4, new ItemStack(Items.EGG, 1));
        bot.getInventory().setItem(5, new ItemStack(Items.WHEAT, 3));
        RecipeRegistry.Recipe recipe = RecipeRegistry.find(Items.CAKE).orElseThrow();
        CraftTask.PreparedCraft prepared = CraftTask.prepareCraft(bot, new CraftingHelper.CraftStep(recipe, 1));
        require(context, prepared.missingIngredient() == null && prepared.remainderOverflow() == null,
                "the cake craft should be possible: " + prepared);
        require(context, count(prepared.main(), Items.BUCKET) == 3 && count(prepared.main(), Items.CAKE) == 1
                        && count(prepared.main(), Items.MILK_BUCKET) == 0,
                "the preview lacks the three empty buckets: buckets " + count(prepared.main(), Items.BUCKET));
        CraftTask.commitPreparedCraft(bot, prepared);
        List<ItemStack> live = bot.getInventory().getNonEquipmentItems();
        require(context, count(live, Items.BUCKET) == 3 && count(live, Items.CAKE) == 1 && count(live, Items.MILK_BUCKET) == 0,
                "the committed inventory lacks the empty buckets");

        cleanup(context, f);
    }

    @GameTest(environment = ENV + "ingredients_use_plain_stacks_first_and_results_keep_their_components", maxTicks = 60)
    public void ingredientsUsePlainStacksFirstAndResultsKeepTheirComponents(GameTestHelper context) {
        Fixture f = fixture(context, "ParityIngredientGT");
        AIPlayerEntity bot = f.bot();
        ItemStack named = new ItemStack(Items.OAK_PLANKS, 4);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Keepsake"));
        bot.getInventory().setItem(0, named);
        bot.getInventory().setItem(1, new ItemStack(Items.OAK_PLANKS, 4));
        RecipeRegistry.Recipe table = RecipeRegistry.find(Items.CRAFTING_TABLE).orElseThrow();
        CraftTask.PreparedCraft prepared = CraftTask.prepareCraft(bot, new CraftingHelper.CraftStep(table, 1));
        require(context, prepared.missingIngredient() == null, "the table craft should be possible");
        require(context, prepared.main().get(0).getCount() == 4 && prepared.main().get(0).get(DataComponents.CUSTOM_NAME) != null,
                "the renamed planks were used before the plain ones: " + prepared.main().get(0));
        require(context, !prepared.main().get(1).is(Items.OAK_PLANKS),
                "the plain planks were not used: " + prepared.main().get(1));

        // A recipe result carries the components the vanilla recipe puts on it.
        DataComponentPatch patch = DataComponentPatch.builder()
                .set(DataComponents.CUSTOM_NAME, Component.literal("Recipe Made")).build();
        RecipeRegistry.Recipe custom = new RecipeRegistry.Recipe(Items.STICK, 4,
                List.of(new RecipeRegistry.Ingredient(List.of(Items.OAK_PLANKS), 2)), false, patch);
        CraftTask.PreparedCraft made = CraftTask.prepareCraft(bot, new CraftingHelper.CraftStep(custom, 1));
        boolean carried = false;
        for (ItemStack stack : made.main()) {
            if (stack.is(Items.STICK) && stack.get(DataComponents.CUSTOM_NAME) != null) {
                carried = true;
            }
        }
        require(context, carried, "the crafted stack lost the recipe result's components");

        // An ingredient may accept several wood types. Plain preference applies to the entire
        // accepted set, not just the first item type: a named oak keepsake must survive when an
        // ordinary spruce plank can satisfy the same ingredient.
        bot.getInventory().clearContent();
        ItemStack namedOakAlternative = new ItemStack(Items.OAK_PLANKS, 1);
        namedOakAlternative.set(DataComponents.CUSTOM_NAME, Component.literal("Alternative Keepsake"));
        bot.getInventory().setItem(0, namedOakAlternative);
        bot.getInventory().setItem(1, new ItemStack(Items.SPRUCE_PLANKS, 1));
        RecipeRegistry.Recipe alternatives = new RecipeRegistry.Recipe(Items.STICK, 1,
                List.of(new RecipeRegistry.Ingredient(List.of(Items.OAK_PLANKS, Items.SPRUCE_PLANKS), 1)), false,
                DataComponentPatch.builder().build());
        CraftTask.PreparedCraft alternativePrepared = CraftTask.prepareCraft(bot,
                new CraftingHelper.CraftStep(alternatives, 1));
        require(context, alternativePrepared.missingIngredient() == null,
                "the mixed-plank ingredient should be craftable");
        require(context, alternativePrepared.main().get(0).is(Items.OAK_PLANKS)
                        && alternativePrepared.main().get(0).get(DataComponents.CUSTOM_NAME) != null,
                "a named first alternative was consumed before a plain accepted alternative");
        require(context, !alternativePrepared.main().get(1).is(Items.SPRUCE_PLANKS),
                "the plain alternative was not consumed first");

        // Vanilla's oak fence is made of oak planks only.
        RecipeRegistry.Recipe fence = RecipeRegistry.find(Items.OAK_FENCE).orElseThrow();
        require(context, fence.ingredients().stream().anyMatch(i -> i.anyOf().equals(List.of(Items.OAK_PLANKS)) && i.count() == 4),
                "the oak fence recipe is not 4 oak planks + 2 sticks: " + fence.ingredients());
        cleanup(context, f);
    }

    private static int count(List<ItemStack> stacks, net.minecraft.world.item.Item item) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private record Fixture(AIPlayerEntity bot, String name, BlockPos furnacePos, GameTestHelper context) {
        AbstractFurnaceBlockEntity furnace() {
            return (AbstractFurnaceBlockEntity) context.getLevel().getBlockEntity(furnacePos);
        }
    }

    private static Fixture fixture(GameTestHelper context, String name) {
        ServerLevel world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(3, 2, 3));
        for (int dx = -2; dx <= 3; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = start.offset(dx, 0, dz);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        BlockPos furnacePos = start.offset(2, 0, 0);
        world.setBlock(furnacePos, Blocks.FURNACE.defaultBlockState(), Block.UPDATE_ALL);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start), 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
        bot.getInventory().clearContent();
        return new Fixture(bot, name, furnacePos, context);
    }

    private static Villager villager(GameTestHelper context, AIPlayerEntity bot, double distance) {
        ServerLevel world = context.getLevel();
        Villager villager = EntityType.VILLAGER.create(world, EntitySpawnReason.COMMAND);
        villager.setPos(bot.getX(), bot.getY(), bot.getZ() - distance);
        world.addFreshEntity(villager);
        return villager;
    }

    private static void cleanup(GameTestHelper context, Fixture f) {
        AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), f.name());
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}

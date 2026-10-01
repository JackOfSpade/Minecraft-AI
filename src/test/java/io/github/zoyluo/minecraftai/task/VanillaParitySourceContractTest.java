package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Pins the parts of smelting, trading and crafting that make a bot obey the rules of a survival player (no item deleted
 * or invented, vanilla bookkeeping runs, real stacks move). Behaviour is proven by VanillaParityGameTests; the ordinary
 * JUnit VM cannot boot the registries of Minecraft, so the shapes that must not regress are pinned here.
 */
final class VanillaParitySourceContractTest {
    private static String read(String relative) throws IOException {
        return Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/" + relative));
    }

    @Test
    void smeltingMovesRealStacksAndNeverDeletesWhatDoesNotFit() throws IOException {
        String smelt = read("task/SmeltTask.java");
        assertFalse(smelt.contains("new ItemStack(input,"), "the furnace input must be the real stack, not rebuilt from the item");
        assertFalse(smelt.contains("new ItemStack(fuel.item()"), "the furnace fuel must be the real stack, not rebuilt from the item");
        assertFalse(smelt.contains("InventoryAction.removeItems(bot, input, inputToLoad)"),
                "loading the input must move what fits, not delete a count and set a clamped stack");
        assertFalse(smelt.contains("InventoryAction.removeItems(bot, fuel.item(), fuel.count())"),
                "fuel above the slot maximum must stay in the inventory");
        assertTrue(smelt.contains("moveIntoFurnaceSlot(bot, furnace, 1, fuel.item(), fuel.count())"));
        assertTrue(smelt.contains("Math.min(furnace.getMaxStackSize(), stack.getMaxStackSize())"),
                "a furnace slot takes no more than the stack maximum");

        int take = smelt.indexOf("static int takeOutput(");
        assertTrue(take > 0);
        String takeBody = smelt.substring(take, smelt.indexOf("static int moveIntoFurnaceSlot(", take));
        assertTrue(takeBody.contains("outputSlot.shrink(inserted)"), "shrink by what the inventory really took");
        assertFalse(takeBody.contains("outputSlot.shrink(take)"));
        assertTrue(takeBody.contains("copyWithCount(take)"), "the output leaves as a copy of the real stack, components included");
        assertTrue(takeBody.contains("furnace.awardUsedRecipesAndPopExperience(bot)"),
                "taking the output pays out the stored recipe experience");
    }

    @Test
    void tradingChecksTheWholeResultBeforePayingAndUsesTheVillagersOwnBookkeeping() throws IOException {
        String trade = read("task/TradeTask.java");
        assertFalse(trade.contains("canFit("), "one free slot is not room for the whole result");
        assertFalse(trade.contains("InventoryAction.removeItems"), "payment must take real stacks matching the cost of the offer");
        assertFalse(trade.contains("afterUsing"), "notifyTrade of the villager replaces the hand-rolled uses/xp bookkeeping");
        assertTrue(trade.contains("villager.notifyTrade(offer)"));
        assertTrue(trade.contains("insertable(bot, sell) < sell.getCount()"));
        assertTrue(trade.indexOf("takePayment(bot") < trade.indexOf("insertable(bot, sell)"),
                "room is judged after the payment frees its slots, and the payment is refunded when it does not fit");
        assertTrue(trade.contains("refund(bot, paid)"));
        assertTrue(trade.contains("TradeRules.refusal("), "the gate of Villager.mobInteract");
        assertTrue(trade.contains("!entity.isSleeping()"), "a sleeping villager is not looked for");
        assertTrue(trade.contains("villager.setTradingPlayer(null)"), "the trade window is closed again");
        assertTrue(trade.indexOf("minecraftai$invokeUpdateSpecialPrices(bot)") < trade.indexOf("completeTrade(bot)"),
                "prices are made for this player (reputation, hero of the village) before any offer is chosen");
        int opened = trade.indexOf("villager.setTradingPlayer(bot)");
        int talked = trade.indexOf("bot.awardStat(Stats.TALKED_TO_VILLAGER)");
        int completed = trade.indexOf("completeTrade(bot)");
        assertTrue(opened >= 0 && talked > opened && completed > talked,
                "opening the screen must award TALKED_TO_VILLAGER before a sale is attempted");
    }

    @Test
    void craftingReturnsRemaindersKeepsResultComponentsAndUsesPlainStacksFirst() throws IOException {
        String craft = read("task/CraftTask.java");
        assertTrue(craft.contains("getCraftingRemainder()"), "a consumed milk bucket leaves an empty bucket");
        assertTrue(craft.contains("insertEntireStack(main, remainder)"), "remainders are part of the previewed inventory");
        assertTrue(craft.contains("step.recipe().result(step.outputCount())"),
                "the crafted stack carries the components of the recipe result");
        assertFalse(craft.contains("new ItemStack(step.recipe().output(), step.outputCount())"));
        assertTrue(craft.contains("ingredient.matches(stack)"),
                "ingredients are matched with the vanilla ingredient test, not item identity per candidate");
        assertTrue(craft.contains("isPlain(stack)"));
        assertTrue(craft.contains("craft_remainder_capacity"));
        int removeIngredient = craft.indexOf("static boolean removeIngredient(");
        int removeIngredientEnd = craft.indexOf("/** A stack with nothing", removeIngredient);
        String removal = craft.substring(removeIngredient, removeIngredientEnd);
        assertTrue(removal.indexOf("for (boolean plainOnly") < removal.indexOf("for (Item item : ingredient.anyOf())"),
                "plain preference must span all accepted ingredient alternatives before item order breaks ties");

        String registry = read("craft/RecipeRegistry.java");
        assertTrue(registry.contains("new Ingredient(List.of(Items.OAK_PLANKS), 4)"), "the oak fence of vanilla is made of oak planks");

        String index = read("craft/RuntimeRecipeIndex.java");
        assertTrue(index.contains("result.getComponentsPatch()"), "the runtime index keeps the components of the vanilla result");
    }

    @Test
    void theVillagerPriceInvokerIsRegistered() throws IOException {
        String mixins = Files.readString(Path.of("src/main/resources/minecraftai.mixins.json"));
        assertTrue(mixins.contains("\"VillagerInvokerMixin\""));
        assertFalse(mixins.contains("MerchantEntityInvokerMixin"),
                "the unused AbstractVillager rewardTradeXp invoker must not remain registered");
        String mixin = read("mixin/VillagerInvokerMixin.java");
        assertTrue(mixin.contains("@Invoker(\"updateSpecialPrices\")"));
    }
}

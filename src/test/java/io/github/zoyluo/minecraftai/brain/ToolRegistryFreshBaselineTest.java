package io.github.zoyluo.minecraftai.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.zoyluo.minecraftai.goal.Goal;
import java.util.List;
import java.util.Map;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The inventory a fulfill_items request must grow beyond: everything handed to a player and every
 * raw resource is new, but gear the bot keeps for itself is satisfied by what it already carries.
 */
final class ToolRegistryFreshBaselineTest {
    @BeforeAll
    static void bootstrap() {
        RegistryBootstrap.ensure();
    }

    private static Map<Item, Integer> baselines(Map<Item, Integer> carried, Goal.Allocation... allocations) {
        return ToolRegistry.freshBaselines(List.of(allocations), item -> carried.getOrDefault(item, 0));
    }

    private static Goal.Allocation keep(Item item, int count) {
        return new Goal.Allocation(item, count, "");
    }

    private static Goal.Allocation give(Item item, int count) {
        return new Goal.Allocation(item, count, "Steve");
    }

    @Test
    void aToolTheBotKeepsIsSatisfiedByOneItAlreadyCarries() {
        Map<Item, Integer> carried = Map.of(Items.IRON_PICKAXE, 1, Items.IRON_CHESTPLATE, 1);

        Map<Item, Integer> baselines = baselines(carried,
                keep(Items.IRON_PICKAXE, 1), keep(Items.IRON_CHESTPLATE, 1));

        assertEquals(0, baselines.get(Items.IRON_PICKAXE),
                "make yourself a pickaxe must not craft a second one");
        assertEquals(0, baselines.get(Items.IRON_CHESTPLATE),
                "worn or carried armor already counts");
    }

    @Test
    void theKeyIsKeptEvenAtZeroSoTheRequestStaysAFreshOne() {
        Goal.Fulfill goal = new Goal.Fulfill(List.of(keep(Items.IRON_PICKAXE, 1)),
                baselines(Map.of(), keep(Items.IRON_PICKAXE, 1)));

        assertTrue(goal.isFreshInventoryRequest());
        assertEquals(Map.of(Items.IRON_PICKAXE, 0), goal.initialItemCounts());
    }

    @Test
    void whatIsHandedToAPlayerIsAlwaysNewProduction() {
        Map<Item, Integer> baselines = baselines(Map.of(Items.IRON_PICKAXE, 1),
                give(Items.IRON_PICKAXE, 1));

        assertEquals(1, baselines.get(Items.IRON_PICKAXE),
                "make me a pickaxe must not hand over the bot's own");
    }

    @Test
    void aKeptAndADeliveredCopyOfTheSameToolOnlyNeedTheMissingOnes() {
        Goal.Allocation mine = keep(Items.IRON_PICKAXE, 1);
        Goal.Allocation yours = give(Items.IRON_PICKAXE, 1);

        assertEquals(0, baselines(Map.of(), mine, yours).get(Items.IRON_PICKAXE));
        assertEquals(0, baselines(Map.of(Items.IRON_PICKAXE, 1), mine, yours).get(Items.IRON_PICKAXE),
                "the carried pickaxe is the one the bot keeps; only the delivered one is new");
        assertEquals(2, baselines(Map.of(Items.IRON_PICKAXE, 3), mine, yours).get(Items.IRON_PICKAXE),
                "three carried, one kept: two spare ones are not the player's");
    }

    @Test
    void aRawResourceIsAlwaysAQuotaOfNewUnitsKeptOrDelivered() {
        Map<Item, Integer> carried = Map.of(Items.COBBLESTONE, 64, Items.OAK_LOG, 10);

        Map<Item, Integer> baselines = baselines(carried,
                keep(Items.COBBLESTONE, 64), give(Items.OAK_LOG, 32), keep(Items.OAK_LOG, 8));

        assertEquals(64, baselines.get(Items.COBBLESTONE),
                "get 64 cobblestone for yourself must collect 64 more, whatever is carried");
        assertEquals(10, baselines.get(Items.OAK_LOG));
    }

    @Test
    void aCompoundRequestMixesBothRules() {
        Map<Item, Integer> carried = Map.of(Items.OAK_LOG, 5, Items.CRAFTING_TABLE, 1, Items.IRON_PICKAXE, 1);

        Map<Item, Integer> baselines = baselines(carried,
                keep(Items.OAK_LOG, 32), give(Items.CRAFTING_TABLE, 1), keep(Items.IRON_PICKAXE, 1));

        assertEquals(Map.of(Items.OAK_LOG, 5, Items.CRAFTING_TABLE, 1, Items.IRON_PICKAXE, 0), baselines);
    }
}

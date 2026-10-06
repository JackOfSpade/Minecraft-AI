package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.zoyluo.minecraftai.craft.RecipeRegistry;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** How a fresh quota that came in as several log species is handed over. */
final class GatherThenGiveTaskDeliveryPlanTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static List<GatherThenGiveTask.Delivery> plan(Map<Item, Integer> fresh, int quota) {
        return GatherThenGiveTask.planDeliveries(RecipeRegistry.LOGS, item -> fresh.getOrDefault(item, 0), quota);
    }

    @Test
    void aMixedForestQuotaIsDeliveredSpeciesBySpeciesWithoutAnyRefinement() {
        List<GatherThenGiveTask.Delivery> plan = plan(Map.of(Items.OAK_LOG, 20, Items.BIRCH_LOG, 12), 32);

        assertEquals(List.of(new GatherThenGiveTask.Delivery(Items.OAK_LOG, 20),
                new GatherThenGiveTask.Delivery(Items.BIRCH_LOG, 12)), plan);
    }

    @Test
    void oneSpeciesThatCoversTheQuotaIsDeliveredInOneDrop() {
        assertEquals(List.of(new GatherThenGiveTask.Delivery(Items.OAK_LOG, 32)),
                plan(Map.of(Items.OAK_LOG, 40, Items.BIRCH_LOG, 5), 32),
                "the surplus stays with the bot and the small species is not dropped at all");
    }

    @Test
    void theMostPlentifulSpeciesGoesFirstAndTheLastDropIsOnlyTheRemainder() {
        assertEquals(List.of(new GatherThenGiveTask.Delivery(Items.SPRUCE_LOG, 20),
                        new GatherThenGiveTask.Delivery(Items.OAK_LOG, 4)),
                plan(Map.of(Items.OAK_LOG, 9, Items.SPRUCE_LOG, 20), 24));
    }

    @Test
    void equalSpeciesKeepRegistryOrder() {
        assertEquals(List.of(new GatherThenGiveTask.Delivery(Items.OAK_LOG, 16),
                        new GatherThenGiveTask.Delivery(Items.BIRCH_LOG, 4)),
                plan(Map.of(Items.OAK_LOG, 16, Items.BIRCH_LOG, 16), 20));
    }

    @Test
    void aShortfallIsNotDeliveredAsAnythingBecauseOldStockMustNeverMakeItUp() {
        assertNull(plan(Map.of(Items.OAK_LOG, 20, Items.BIRCH_LOG, 5), 32));
        assertNull(plan(Map.of(), 1));
    }

    @Test
    void anExactItemQuotaIsOneDropOfThatItem() {
        List<Item> exact = List.of(Items.COBBLESTONE);

        assertEquals(List.of(new GatherThenGiveTask.Delivery(Items.COBBLESTONE, 32)),
                GatherThenGiveTask.planDeliveries(exact, item -> 32, 32));
        assertNull(GatherThenGiveTask.planDeliveries(exact, item -> 31, 32));
    }

    @Test
    void eachDropIsLimitedToTheNewUnitsOfItsSpecies() {
        Map<Item, Integer> fresh = Map.of(Items.OAK_LOG, 3, Items.BIRCH_LOG, 3);

        for (GatherThenGiveTask.Delivery delivery : plan(fresh, 6)) {
            assertEquals(3, delivery.count(), "an old oak stack of any size must not enlarge the oak drop");
        }
    }
}

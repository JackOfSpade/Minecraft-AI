package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * What the gather child of a fresh handoff must provide. The wrapper's own delivery logic is
 * covered behaviourally in {@link GatherThenGiveTaskDeliveryPlanTest}.
 */
final class GatherThenGiveTaskSourceContractTest {
    @Test
    void exactHandoffGatherKeepsItsReserveThroughCapacityRecovery() throws IOException {
        String gather = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/GatherQuotaTask.java"));
        String stockpile = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/StockpileTask.java"));

        assertTrue(gather.contains("collectAdditionalExact(Item targetItem, int targetCount)")
                        && gather.contains("Set.of(targetItem)")
                        && gather.contains("return HarvestCore.countInventoryItems(bot, acceptItems);")
                        && gather.contains("if (retainAcceptedItemsDuringDeposit)"),
                "an exact later handoff must use the exact acceptance set for both the fresh baseline and progress");
        assertTrue(gather.contains("new StockpileTask(true, protectedItemsDuringDeposit)")
                        && gather.contains("inventory_full_reserved_handoff")
                        && gather.contains("!retainAcceptedItemsDuringDeposit || !hasAcceptedStackRoom(bot)")
                        && gather.contains("if (acceptItems.size() != 1)")
                        && stockpile.contains("!retainedItems.contains(stack.getItem())")
                        && stockpile.contains("startSurfacePathTo(stand)"),
                "capacity recovery must retain the promised item, reserve family handoffs conservatively, and never spend it while walking to storage");
    }

    @Test
    void genericLogHandoffGatherRetainsTheWholeLogFamily() throws IOException {
        String gather = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/GatherQuotaTask.java"));

        assertTrue(gather.contains("collectAdditionalLogsForHandoff(int targetCount)")
                        && gather.contains("0, 0, 0, Set.copyOf(RecipeRegistry.LOGS), true);"),
                "the first generic-log stage must retain the whole accepted log family through capacity recovery");
    }

    @Test
    void verticalSupportChildInheritsTheHandoffReservation() throws IOException {
        String gather = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/GatherQuotaTask.java"));

        assertTrue(gather.contains("collectNearbyPillarSupport(scaffoldSupplyItem, missing, protectedItemsDuringDeposit)")
                        && gather.contains("protectedItems.add(item)")
                        && gather.contains("new StockpileTask(true, protectedItemsDuringDeposit)")
                        && gather.contains("!protectedItemsDuringDeposit.isEmpty()"),
                "a support-resupply child must preserve both its parent handoff reserve and its own gathered supports");
    }
}

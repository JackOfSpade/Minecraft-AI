package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Regression contract for a fresh acquisition followed by an exact vanilla handoff. */
final class GatherThenGiveTaskSourceContractTest {
    @Test
    void handoffCannotBeginUntilAnExactFreshGatherQuotaCompletes() throws IOException {
        String task = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/GatherThenGiveTask.java"));
        String gather = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/GatherQuotaTask.java"));
        String stockpile = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/StockpileTask.java"));

        int freshGather = task.indexOf("GatherQuotaTask.collectAdditionalExact(item, count)");
        int give = task.indexOf("beginGive(bot, item)");
        int gatherComplete = task.indexOf("gatherTask.state() == TaskState.COMPLETED");
        assertTrue(freshGather >= 0 && gatherComplete > freshGather && give > gatherComplete,
                "the exact give task must be created only after the fresh gather child completes");
        assertTrue(task.contains("new GiveItemTask(delivery, count, playerName, true)"),
                "the final give must receive the species whose retained fresh quota was proved");
        assertTrue(task.contains("gatherTask.hasRetainedFreshQuota(bot)")
                        && task.contains("fresh_gather_failed:") && task.contains("fresh_handoff_failed:")
                        && task.contains("fresh_handoff_retained_quota_lost"),
                "the wrapper must require retained fresh inventory and fail closed rather than fall through to delivery");
        assertTrue(task.contains("child.pause(bot)") && task.contains("child.resume(bot)")
                        && task.contains("child.abort(bot)") && task.contains("super.onAbort(bot)")
                        && task.contains("failClosed(bot,") && task.contains("bot.getActionPack().stopAll()"),
                "parent lifecycle transitions must be propagated to its private child task");
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
    void genericLogsLockAnActuallyFreshSpeciesBeforeTheExactHandoff() throws IOException {
        String task = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/GatherThenGiveTask.java"));
        String gather = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/GatherQuotaTask.java"));

        assertTrue(task.contains("GatherQuotaTask.collectAdditionalLogsForHandoff(count)")
                        && task.contains("logInventoryBaseline.put(log, InventoryAction.countItem(bot, log))")
                        && task.contains("freshLogCount(bot, deliveryItem)")
                        && task.contains("hasGenericRetainedDeliveryQuota(bot)"),
                "generic logs must use immutable per-species baselines, not the total family stack");
        assertTrue(task.contains("GatherQuotaTask.collectAdditionalExact(deliveryItem, count - fresh)")
                        && task.contains("phase = Phase.REFINE"),
                "a mixed first-stage log collection must lock one new species and refine only its shortfall");
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

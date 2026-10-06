package io.github.zoyluo.minecraftai.brain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryIncrementalGatherBoundaryTest {
    private static final Path REGISTRY = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/brain/ToolRegistry.java");

    @Test
    void publicCropHarvestCapturesProduceBeforeAddingTheRequestedQuota() throws IOException {
        String registry = Files.readString(REGISTRY);

        assertTrue(registry.contains(
                "int heldProduce = HarvestCore.countInventoryItems(bot, Set.of(produce));"));
        assertTrue(registry.contains(
                "new Goal.HarvestCrop(spec.crop(), spec.seed(), produce,"));
        assertTrue(registry.contains(
                "Goal.HarvestCrop.timedCollection(spec.crop(), spec.seed(), produce, heldProduce)"));
        assertTrue(registry.contains("NEW produce items"));
    }

    @Test
    void publicForageUsesTheExistingAdditionalItemTask() throws IOException {
        String registry = Files.readString(REGISTRY);

        assertTrue(registry.contains("? GatherQuotaTask.collectAdditional(net.minecraft.world.item.Items.SWEET_BERRIES,"));
        assertTrue(registry.contains("GatherQuotaTask.collectForDuration(net.minecraft.world.item.Items.SWEET_BERRIES)"));
        assertTrue(registry.contains("existing copies never satisfy it"));
        assertFalse(registry.contains(
                "new Goal.HaveItem(net.minecraft.world.item.Items.SWEET_BERRIES"),
                "forage must not turn a requested new berry count into an absolute inventory goal");
    }

    @Test
    void omittedGenericGatherAndPublicAliasesUseTimedCollection() throws IOException {
        String registry = Files.readString(REGISTRY);

        assertTrue(registry.contains("Task task = args.has(\"count\")"));
        assertTrue(registry.contains(": GatherQuotaTask.collectForDuration(item);"));
        assertTrue(registry.contains("case \"gather\" -> params.has(\"count\")"));
        assertTrue(registry.contains("case \"forage\" -> params.has(\"count\")"));
        assertTrue(registry.contains("params.has(\"count\") ? new MineTask(block, count) : timedResourceMineTask(block)"));
        assertTrue(registry.contains("HarvestCore.expectedDropsFor(block).stream().findFirst()"),
                "a no-count block mine gathers its actual drops (stone -> cobblestone), not Block.asItem()");
        assertTrue(registry.contains("if (\"harvest_crop\".equals(taskType))"));
        assertTrue(registry.contains("actively explore through short safe observed hops"));
    }
}

package io.github.zoyluo.minecraftai.goal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalExecutorSkippedTargetReceiptTest {
    private static final Path EXECUTOR = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/goal/GoalExecutor.java");
    private static final Path CODEC = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/goal/GoalCheckpointCodec.java");

    @Test
    void foodHuntReceiptRemovesHuntButKeepsUnrelatedFreshSteps() {
        GoalStep skipped = GoalStep.hunt(1).asBestEffort();
        List<GoalStep> fresh = List.of(
                GoalStep.hunt(12).asBestEffort(),
                GoalStep.cookFood(4).asBestEffort());

        List<GoalStep> restored = GoalExecutor.applySkippedTargetReceipts(
                fresh, List.of(receipt(skipped)));

        assertEquals(List.of(GoalStep.cookFood(4).asBestEffort()), restored);
    }

    @Test
    void duplicateReceiptsConsumeDuplicateTargetsOneForOne() {
        GoalStep hunt = GoalStep.hunt(1).asBestEffort();
        List<GoalStep> restored = GoalExecutor.applySkippedTargetReceipts(
                List.of(
                        GoalStep.hunt(2).asBestEffort(),
                        GoalStep.hunt(3).asBestEffort(),
                        GoalStep.hunt(4).asBestEffort()),
                List.of(receipt(hunt), receipt(hunt)));

        assertEquals(List.of(GoalStep.hunt(4).asBestEffort()), restored);
    }

    @Test
    void restoredReceiptsMustRemainAuthorizedByTheGoalSkipPolicy() {
        Goal mining = new Goal.MineOre(Set.of(), 1);
        Goal food = new Goal.Food(4);

        assertTrue(!GoalExecutor.skippedTargetReceiptsAuthorized(
                mining,
                List.of(receipt(GoalStep.mineOre(Set.of(), 1)))));
        assertTrue(GoalExecutor.skippedTargetReceiptsAuthorized(
                food,
                List.of(receipt(GoalStep.hunt(1)))));
        assertTrue(!GoalExecutor.skippedTargetReceiptsAuthorized(
                food,
                List.of(new GoalExecutor.SkippedTargetReceipt(
                        GoalStep.hunt(1),
                        "hunt_surface_return_timeout anchor=1,64,1"))));
        assertTrue(!GoalExecutor.skippedTargetReceiptsAuthorized(
                food,
                List.of(receipt(GoalStep.cookFood(4)))));
        assertTrue(GoalExecutor.skippedTargetReceiptsAuthorized(
                mining,
                List.of(receipt(GoalStep.stockpile(null)))));
    }

    @Test
    void unmatchedReceiptNeverRemovesAWeakerOrDifferentIdentity() {
        GoalStep tagged = new GoalStep(
                GoalStep.Kind.HUNT, null, 2, null, Set.of(),
                null, null, new BlockPos(1, 2, 3), "batch:a", true);
        List<GoalStep> fresh = List.of(
                new GoalStep(
                        GoalStep.Kind.HUNT, null, 9, null, Set.of(),
                        null, null, new BlockPos(1, 2, 3), "batch:b", true),
                new GoalStep(
                        GoalStep.Kind.HUNT, null, 9, null, Set.of(),
                        null, null, new BlockPos(1, 2, 4), "batch:a", true),
                new GoalStep(
                        GoalStep.Kind.HUNT, null, 9, null, Set.of(),
                        null, null, new BlockPos(1, 2, 3), "batch:a", false));

        assertEquals(fresh, GoalExecutor.applySkippedTargetReceipts(
                fresh, List.of(receipt(tagged))));
    }

    @Test
    void canonicalCodecRoundTripsStructuredTargetIdentity() {
        GoalStep detailed = new GoalStep(
                GoalStep.Kind.HUNT,
                null,
                7,
                null,
                Set.of(),
                null,
                null,
                new BlockPos(-4, 12, 8),
                "identity:complete",
                true);
        List<GoalExecutor.SkippedTargetReceipt> receipts =
                List.of(new GoalExecutor.SkippedTargetReceipt(
                        detailed, "best-effort failure"));

        Map<String, String> encoded =
                GoalCheckpointCodec.encodeSkippedTargetReceipts(receipts);
        var decoded = GoalCheckpointCodec.decodeSkippedTargetReceipts(encoded);

        assertTrue(decoded.isPresent());
        assertEquals(receipts, decoded.orElseThrow());
    }

    @Test
    void missingIsLegacyButPartialUnknownCapAndNonCanonicalFailClosed() {
        assertEquals(List.of(),
                GoalCheckpointCodec.decodeSkippedTargetReceipts(Map.of()).orElseThrow());

        Map<String, String> partial = new LinkedHashMap<>();
        partial.put("skipped_target.schema", "1");
        assertTrue(GoalCheckpointCodec.decodeSkippedTargetReceipts(partial).isEmpty());

        Map<String, String> unknown = new LinkedHashMap<>(
                GoalCheckpointCodec.encodeSkippedTargetReceipts(
                        List.of(receipt(GoalStep.hunt(1)))));
        unknown.put("skipped_target.00.unknown", "x");
        assertTrue(GoalCheckpointCodec.decodeSkippedTargetReceipts(unknown).isEmpty());

        Map<String, String> overCap = new LinkedHashMap<>();
        overCap.put("skipped_target.schema", "1");
        overCap.put("skipped_target.count", "33");
        assertTrue(GoalCheckpointCodec.decodeSkippedTargetReceipts(overCap).isEmpty());

        Map<String, String> nonCanonical = new LinkedHashMap<>(
                GoalCheckpointCodec.encodeSkippedTargetReceipts(
                        List.of(receipt(GoalStep.hunt(1)))));
        nonCanonical.put("skipped_target.count", "01");
        assertTrue(GoalCheckpointCodec.decodeSkippedTargetReceipts(nonCanonical).isEmpty());

        Map<String, String> nonCanonicalEntry = new LinkedHashMap<>(
                GoalCheckpointCodec.encodeSkippedTargetReceipts(
                        List.of(receipt(GoalStep.hunt(1)))));
        nonCanonicalEntry.put("skipped_target.00.count", "+1");
        assertTrue(GoalCheckpointCodec.decodeSkippedTargetReceipts(nonCanonicalEntry).isEmpty());
    }

    @Test
    void collectionCapIsNonEvictingAndRestoreFailureIsTyped() {
        List<GoalExecutor.SkippedTargetReceipt> receipts = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            receipts.add(receipt(new GoalStep(
                    GoalStep.Kind.HUNT, null, index + 1, null, Set.of(),
                    null, null, null, "batch:" + index, true)));
        }
        assertEquals(32, GoalCheckpointCodec.decodeSkippedTargetReceipts(
                GoalCheckpointCodec.encodeSkippedTargetReceipts(receipts))
                .orElseThrow().size());

        Map<String, String> damaged = new LinkedHashMap<>(
                GoalCheckpointCodec.encodeSkippedTargetReceipts(List.of(
                        receipt(GoalStep.hunt(1)))));
        damaged.remove("skipped_target.00.reason");
        assertEquals("mission_restore_invalid_skipped_target_receipts",
                GoalExecutor.restoreCheckpointValidationFailure(damaged)
                        .orElseThrow());
    }

    @Test
    void completedDeliveryReceiptsRemainBoundToTheFulfillmentManifest() throws IOException {
        // Item-backed allocations require a fully bootstrapped Minecraft registry, so this
        // non-bootstrapped JUnit test pins the recovery contract while the GameTest covers it
        // against real item instances.
        String executor = Files.readString(EXECUTOR);
        String codec = Files.readString(CODEC);

        assertTrue(codec.contains("encodeCompletedDeliveries("));
        assertTrue(codec.contains("decodeCompletedDeliveries("));
        assertTrue(executor.contains("goal instanceof Goal.Fulfill fulfill"));
        assertTrue(executor.contains("fulfill.deliveries().containsAll(receipts)"));
        assertTrue(executor.contains("mission_restore_invalid_completed_deliveries"));
    }

    private static GoalExecutor.SkippedTargetReceipt receipt(GoalStep step) {
        return new GoalExecutor.SkippedTargetReceipt(step, "ordinary miss");
    }
}

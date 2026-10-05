package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.mining.MiningBudget;
import io.github.zoyluo.minecraftai.mining.MiningCursor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure codec checks for the bounded factual open-seam restart ledger. */
class OreDigCheckpointOpenedVeinBreaksTest {
    @Test
    void oversizedBranchedSeamIsBoundedDeterministicallyAndKeepsRemoteAnchor() {
        Set<BlockPos> facts = new LinkedHashSet<>();
        for (int x = -40; x <= 40; x++) {
            for (int z = -40; z <= 40; z++) {
                facts.add(new BlockPos(x, 0, z));
            }
        }
        BlockPos remoteBranchAnchor = new BlockPos(80, 0, 0);
        facts.add(remoteBranchAnchor);

        OreDigCheckpoint checkpoint = checkpoint(facts);
        assertTrue(facts.size() > 4096, "fixture must exercise the former self-invalidating size");
        assertTrue(checkpoint.openedVeinBreaks().size() <= 4096,
                "the live checkpoint must never encode more anchors than its decoder accepts");
        assertTrue(checkpoint.openedVeinBreaks().contains(remoteBranchAnchor),
                "adaptive spatial coalescing must retain a branch beyond the old 48-block window");
        String encoded = checkpoint.encode().get("opened_vein_breaks");
        assertTrue(encoded.split(";", -1).length <= 4096,
                "the bounded wire ledger must remain decodable");

        List<BlockPos> reversed = new ArrayList<>(facts);
        Collections.reverse(reversed);
        OreDigCheckpoint reordered = checkpoint(new LinkedHashSet<>(reversed));
        assertEquals(checkpoint.openedVeinBreaks(), reordered.openedVeinBreaks(),
                "anchor selection must not depend on the source set iteration order");
        assertEquals(encoded, reordered.encode().get("opened_vein_breaks"),
                "the persisted recovery hint must be deterministic across equivalent ledgers");
    }

    @Test
    void longButBoundedBranchAnchorSurvivesWhileOutOfEnvelopeFactDoesNotPoisonCheckpoint() {
        BlockPos longBranchAnchor = new BlockPos(80, 0, 0);
        OreDigCheckpoint checkpoint = checkpoint(Set.of(
                longBranchAnchor,
                new BlockPos(4097, 0, 0)));

        assertTrue(checkpoint.openedVeinBreaks().contains(longBranchAnchor),
                "factual anchors outside ordinary scan range must survive a restart");
        assertFalse(checkpoint.openedVeinBreaks().contains(new BlockPos(4097, 0, 0)),
                "a corrupt remote anchor must be discarded instead of self-invalidating the checkpoint");
    }

    @Test
    void observedQueuedForkHintsEncodeSeparatelyFromBrokenAnchors() {
        BlockPos broken = new BlockPos(0, 0, 0);
        BlockPos queuedFork = new BlockPos(80, 0, 0);
        OreDigCheckpoint checkpoint = new OreDigCheckpoint(
                OreDigCheckpoint.CHECKPOINT_SCHEMA, 1, true, 0, 0, false,
                MiningBudget.RARE_BATCH_TORCH_LIMIT, 0, 0,
                MiningCursor.initial(BlockPos.ZERO, OreDigTask.STRIP_SEGMENT),
                "registry-free-fixture", 0, 0,
                null, null, null, null, -1, -1, -1, null, -1, false,
                Map.of(), Set.of(broken), Set.of(queuedFork));

        Map<String, String> encoded = checkpoint.encode();
        assertEquals(OreDigCheckpoint.encodeCheckpointPos(queuedFork),
                encoded.get("queued_vein_hints"),
                "the unbroken observed fork must have its own durable hint key");
        assertEquals(Set.of(broken), checkpoint.openedVeinBreaks());
        assertEquals(Set.of(queuedFork), checkpoint.queuedVeinHints(),
                "the constructor must not turn an observed-but-unbroken fork into a break fact");
    }

    private static OreDigCheckpoint checkpoint(Set<BlockPos> openedVeinBreaks) {
        return new OreDigCheckpoint(
                OreDigCheckpoint.CHECKPOINT_SCHEMA, 1, true, 0, 0, false,
                MiningBudget.RARE_BATCH_TORCH_LIMIT, 0, 0,
                MiningCursor.initial(BlockPos.ZERO, OreDigTask.STRIP_SEGMENT),
                "registry-free-fixture", 0, 0, null, null, null, null, -1, -1, -1,
                null, -1, false, Map.of(), openedVeinBreaks);
    }
}

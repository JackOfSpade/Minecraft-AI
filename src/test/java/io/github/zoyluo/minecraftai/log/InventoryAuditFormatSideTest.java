package io.github.zoyluo.minecraftai.log;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers {@code InventoryAudit.formatSide}, the pure formatter behind {@code inventory_delta}'s
 * {@code gained}/{@code lost} fields (see docs/LOGGING.md "Auditing a gather"). Deltas keep their
 * sign so the same formatter serves both fields: positive for gains, negative for losses.
 */
final class InventoryAuditFormatSideTest {
    @Test
    void emptyListFormatsAsADash() {
        assertEquals("-", InventoryAudit.formatSide(List.of()));
    }

    @Test
    void singleGainIsFormattedWithAPlusSign() {
        assertEquals("minecraft:spruce_log+1",
                InventoryAudit.formatSide(List.of(Map.entry("minecraft:spruce_log", 1))));
    }

    @Test
    void singleLossIsFormattedWithTheNegativeSignAlreadyOnTheDelta() {
        assertEquals("minecraft:torch-1",
                InventoryAudit.formatSide(List.of(Map.entry("minecraft:torch", -1))));
    }

    @Test
    void multipleEntriesAreCommaJoinedInGivenOrder() {
        assertEquals("minecraft:oak_log+2,minecraft:spruce_log+1",
                InventoryAudit.formatSide(List.of(
                        Map.entry("minecraft:oak_log", 2),
                        Map.entry("minecraft:spruce_log", 1))));
    }

    @Test
    void moreThanTheBoundIsTruncatedWithACountOfTheRest() {
        List<Map.Entry<String, Integer>> nine = List.of(
                Map.entry("minecraft:item0", 1), Map.entry("minecraft:item1", 1),
                Map.entry("minecraft:item2", 1), Map.entry("minecraft:item3", 1),
                Map.entry("minecraft:item4", 1), Map.entry("minecraft:item5", 1),
                Map.entry("minecraft:item6", 1), Map.entry("minecraft:item7", 1),
                Map.entry("minecraft:item8", 1));
        String formatted = InventoryAudit.formatSide(nine);
        assertEquals("minecraft:item0+1,minecraft:item1+1,minecraft:item2+1,minecraft:item3+1,"
                + "minecraft:item4+1,minecraft:item5+1,minecraft:item6+1,minecraft:item7+1,+1more", formatted);
    }
}

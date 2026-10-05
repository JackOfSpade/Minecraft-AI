package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.mining.MiningBudget;
import io.github.zoyluo.minecraftai.mining.MiningCursor;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Guards schema-aware re-encoding for checkpoints written before the newer durable ledgers. */
class OreDigCheckpointLegacySchemaRoundTripTest {
    @Test
    void closedSchemasOneThroughThreeEncodeOnlyTheirOwnWireFields() {
        for (int schema = 1; schema <= 3; schema++) {
            OreDigCheckpoint checkpoint = new OreDigCheckpoint(
                    schema, 1, false, 0, 0, false,
                    MiningBudget.RARE_BATCH_TORCH_LIMIT, 0, 0,
                    MiningCursor.initial(BlockPos.ZERO, OreDigTask.STRIP_SEGMENT),
                    "registry-free-fixture", 0, 0,
                    null, null, null, null, -1, -1, -1, null, -1);

            Map<String, String> encoded = checkpoint.encode();
            assertEquals(schema >= 2, encoded.containsKey("torch_limit"));
            assertEquals(schema >= 3, encoded.containsKey("rare_mission_target"));
            assertEquals(false, encoded.containsKey("delivered"));
            assertEquals("registry-free-fixture", encoded.get("ore_fingerprint"),
                    "schema " + schema + " must preserve its opaque ore-family fingerprint");
        }
    }
}

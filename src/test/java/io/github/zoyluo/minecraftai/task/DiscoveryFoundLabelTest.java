package io.github.zoyluo.minecraftai.task;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A show-location demonstration names a found target by what the bot observed, not by what the model called it. */
final class DiscoveryFoundLabelTest {
    private static final String OBSERVED = "a chest or other storage container";

    @Test
    void theLatestFindKeepsItsObservedLabelWhateverTheModelCalledIt() {
        BlockPos chest = new BlockPos(6, 107, 9);
        Optional<DiscoveryTask.FoundTarget> latest = Optional.of(new DiscoveryTask.FoundTarget(chest, OBSERVED));

        assertEquals(OBSERVED, DiscoveryTask.FoundTarget.labelFor(latest, new BlockPos(6, 107, 9), "bonus chest"));
        assertEquals(OBSERVED, DiscoveryTask.FoundTarget.labelFor(latest, chest, ""));
    }

    @Test
    void anyOtherCoordinateKeepsTheRequestedLabel() {
        Optional<DiscoveryTask.FoundTarget> latest = Optional.of(
                new DiscoveryTask.FoundTarget(new BlockPos(6, 107, 9), OBSERVED));

        assertEquals("my base", DiscoveryTask.FoundTarget.labelFor(latest, new BlockPos(100, 64, -20), "my base"));
    }

    @Test
    void withoutAnyFindTheRequestedLabelIsKept() {
        assertEquals("the lake", DiscoveryTask.FoundTarget.labelFor(Optional.empty(), new BlockPos(1, 2, 3), "the lake"));
    }
}

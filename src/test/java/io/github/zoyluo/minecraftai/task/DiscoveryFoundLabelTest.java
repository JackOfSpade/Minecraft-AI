package io.github.zoyluo.minecraftai.task;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A show-location demonstration names a found target by what the bot observed, not by what the model called it. */
final class DiscoveryFoundLabelTest {
    private static final String OBSERVED = "a chest or other storage container";
    private static final BlockPos CHEST = new BlockPos(6, 107, 9);

    private static Optional<DiscoveryTask.FoundTarget> latest() {
        return Optional.of(new DiscoveryTask.FoundTarget(CHEST, OBSERVED));
    }

    @Test
    void withoutCoordinatesTheLatestFindKeepsItsObservedLabelWhateverTheModelCalledIt() {
        DiscoveryTask.FoundTarget shown = DiscoveryTask.FoundTarget.forShowing(latest(), null, "the bonus chest").orElseThrow();

        assertEquals(CHEST, shown.pos());
        assertEquals(OBSERVED, shown.label());
        assertEquals(OBSERVED, DiscoveryTask.FoundTarget.forShowing(latest(), null, "").orElseThrow().label());
    }

    @Test
    void coordinatesOfTheLatestFindKeepItsObservedLabelToo() {
        DiscoveryTask.FoundTarget shown = DiscoveryTask.FoundTarget.forShowing(latest(), new BlockPos(6, 107, 9), "bonus chest")
                .orElseThrow();

        assertEquals(CHEST, shown.pos());
        assertEquals(OBSERVED, shown.label());
    }

    @Test
    void anyOtherCoordinateKeepsTheRequestedLabel() {
        DiscoveryTask.FoundTarget shown = DiscoveryTask.FoundTarget.forShowing(latest(), new BlockPos(100, 64, -20), "my base")
                .orElseThrow();

        assertEquals(new BlockPos(100, 64, -20), shown.pos());
        assertEquals("my base", shown.label());
    }

    @Test
    void coordinatesNeedNoFindAtAll() {
        DiscoveryTask.FoundTarget shown = DiscoveryTask.FoundTarget.forShowing(Optional.empty(), new BlockPos(1, 2, 3), "the lake")
                .orElseThrow();

        assertEquals(new BlockPos(1, 2, 3), shown.pos());
        assertEquals("the lake", shown.label());
    }

    @Test
    void withNeitherCoordinatesNorAFindThereIsNothingToShow() {
        assertTrue(DiscoveryTask.FoundTarget.forShowing(Optional.empty(), null, "the lake").isEmpty());
    }
}

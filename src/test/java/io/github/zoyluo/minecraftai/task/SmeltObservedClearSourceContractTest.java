package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Pins the no-hidden-terrain boundary for making room to place a carried furnace. */
final class SmeltObservedClearSourceContractTest {
    @Test
    void furnacePlacementAndClearingProveAnAdjacentCellBeforeLiveTerrainReads() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/SmeltTask.java"));

        int place = source.indexOf("private void placeFurnace(");
        int placementProof = source.indexOf("canObserveFurnaceClearCell(bot, candidate)", place);
        int placementState = source.indexOf("bot.level().getBlockState(candidate)", place);
        assertTrue(place >= 0 && placementProof > place && placementState > placementProof,
                "a carried furnace must prove an adjacent placement cell before reading whether it is air");

        int activeClear = source.indexOf("BlockPos activeClearTarget = clearMiner.target()", place);
        int activeProof = source.indexOf("canObserveFurnaceClearCell(bot, activeClearTarget)", activeClear);
        int activeTick = source.indexOf("clearMiner.tick(bot)", activeClear);
        assertTrue(activeClear > place && activeProof > activeClear && activeTick > activeProof,
                "an in-progress local clear must re-prove visibility before BlockMiner reads or mines its target");

        int clear = source.indexOf("private boolean clearSpaceForFurnace(");
        int clearProof = source.indexOf("canObserveFurnaceClearCell(bot, candidate)", clear);
        int clearState = source.indexOf("world.getBlockState(candidate)", clear);
        assertTrue(clear >= 0 && clearProof > clear && clearState > clearProof,
                "clear-space selection must use a state-free eye-ray proof before any target-state query");

        int begin = source.indexOf("private BlockMiner.Status beginClear(");
        int beginProof = source.indexOf("canObserveFurnaceClearCell(bot, pos)", begin);
        int minerBegin = source.indexOf("clearMiner.begin(bot, pos)", begin);
        int minerTick = source.indexOf("clearMiner.tick(bot)", begin);
        assertTrue(begin >= 0 && beginProof > begin && minerBegin > beginProof && minerTick > minerBegin,
                "the final mining boundary must retain its own visibility recheck");

        assertTrue(source.contains("ObservableWorldQuery.canObserveBlockCellFace(bot, pos)")
                        && source.contains("ObservableWorldQuery.canObserveCell(bot, pos)"),
                "clear targets need a state-free proof both before break and after the observed AIR transition");
        assertTrue(source.contains("smelt_clear_observation_refused")
                        && source.contains("clear_space_requires_observed_target"),
                "hidden adjacent terrain must produce an auditable typed refusal instead of a blind clear");
    }
}

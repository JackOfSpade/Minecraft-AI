package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Pins the generic render-distance target look-around without requiring a live world. */
final class VisibleTargetHorizonScanSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    @Test
    void genericScannerUsesOnlyDirectRenderSightAndHasNoTreeLeafShortcut() throws IOException {
        String scan = read("task/VisibleTargetHorizonScan.java");

        assertTrue(scan.contains("ObservableWorldQuery.castSightRay(")
                        && scan.contains("ObservableWorldQuery.visibleRangeBlocks(bot) - 1")
                        && scan.contains("ObservableWorldQuery.ViewShape.OUTLINE")
                        && scan.contains("SharedWorldSight.knownBlocks("),
                "ordinary targets must be discovered through render-distance sight rays or re-proved shared sight");
        assertTrue(scan.contains("hit.crossed()"),
                "a target that is itself see-through (a plant, a vine, a cobweb) is found among the cells a ray crossed");
        assertTrue(scan.contains("ObservableWorldQuery.canObserveBlock(bot, pos)")
                        && scan.contains("targetBlocks.contains(bot.level().getBlockState(pos).getBlock())"),
                "a remembered generic target must be visible again before its live state is read");
        assertTrue(scan.contains("SHARED_SIGHT_RECHECK_STEPS")
                        && scan.contains("shouldHoldFallback()")
                        && scan.contains("int elevationSlot = inPhase % ELEVATION_SAMPLES")
                        && scan.contains("AZIMUTH_STRIDE"),
                "the long visual raster must revisit fresh shared sight and distribute rays across elevation bands rather than stalling on one horizon band");
        assertFalse(scan.contains("BlockTags.LEAVES") || scan.contains("Kind.LEAF")
                        || scan.contains("betweenClosedStream") || scan.contains("getChunk("),
                "generic discovery must not scan a hidden volume or turn leaves into non-tree landmarks");
    }

    @Test
    void gatherUsesGenericSightBeforeExactFailureOrBlindExploration() throws IOException {
        String gather = read("task/GatherQuotaTask.java");
        String survey = methodBody(gather, "private void survey(");
        String seek = methodBody(gather, "private boolean seekVisibleTarget(");
        String pursuit = methodBody(gather, "private boolean startTargetSightingPursuit(");
        String move = methodBody(gather, "private void targetSightingMove(");

        int genericLook = survey.indexOf("seekVisibleTarget(bot)");
        int exactBoundary = survey.indexOf("if (countBrokenBlocks)", genericLook);
        int exploreFallback = survey.indexOf("escapeBarrenAreaOrFail(bot)", genericLook);
        assertTrue(genericLook >= 0 && genericLook < exactBoundary && genericLook < exploreFallback
                        && survey.contains("!isLocalScaffoldSupply() && seekVisibleTarget(bot)"),
                "every outer non-tree gather target, including exact break, must get direct visual discovery first");
        assertTrue(survey.contains("hasPendingExactVisibleSearch()")
                        && gather.contains("private boolean hasPendingExactVisibleSearch()"),
                "an exact visual-only request must finish its bounded sight raster rather than failing after the initial glance");
        assertTrue(seek.contains("new VisibleTargetHorizonScan(harvestBlocks)")
                        && seek.contains("approachVisibleTarget(bot, sighting.pos())")
                        && gather.contains("case TARGET_SIGHTING -> targetSightingMove(bot)"),
                "a directly sighted gather block should either use its local stance or a dedicated observed pursuit");
        assertTrue(pursuit.contains("isCurrentVisibleTargetLandmark(bot, targetSightingHint)")
                        && pursuit.contains("startVisibleLandmarkPursuitTo(")
                        && move.contains("isCurrentVisibleTargetLandmark(bot, targetSightingHint)"),
                "generic target pursuit must re-prove current LOS before and during Baritone movement");
    }

    @Test
    void mineUsesGenericSightBeforeDescentAndKeepsOreDigOnItsDedicatedPath() throws IOException {
        String mine = read("task/MineTask.java");
        String search = methodBody(mine, "private void search(");
        String seek = methodBody(mine, "private boolean seekVisibleTarget(");
        String pursuit = methodBody(mine, "private boolean startTargetSightingPursuit(");

        int visible = search.indexOf("seekVisibleTarget(bot)");
        int descent = search.indexOf("startMiningExploration(bot)");
        assertTrue(visible >= 0 && descent > visible
                        && search.contains("if (!OreScan.isOreBlock(targetBlock))")
                        && search.contains("seekVisibleTarget(bot) || tryPillarApproach(bot)"),
                "ordinary MineTask targets should check render-distance sight before beginning a descent, without changing ore dispatch");
        assertTrue(seek.contains("new VisibleTargetHorizonScan(Set.of(targetBlock))")
                        && seek.contains("approachVisibleTarget(bot, sighting.pos())")
                        && pursuit.contains("startVisibleLandmarkPursuitTo(")
                        && pursuit.contains("isCurrentVisibleTarget(bot, targetSightingHint)"),
                "MineTask must pursue only a re-proved direct target through the observed landmark route");
        assertTrue(mine.contains("HarvestCore.beginNearestPillarApproachScan(")
                        && mine.contains("GatherQuotaTask.collectNearbyPillarSupport(")
                        && mine.contains("startPillarPathTo(approach.goal())"),
                "generic MineTask targets must receive the same safe common-block pillar/resupply recovery as gathering");
    }

    @Test
    void oreDigUsesSightOnlyAsAWalkLandmarkBeforeItsExistingLocalOreLogic() throws IOException {
        String oreDig = read("task/OreDigTask.java");
        String seek = methodBody(oreDig, "private boolean seekVisibleOre(");
        String pursuit = methodBody(oreDig, "private boolean startVisibleOreSightingPursuit(");
        String tick = methodBody(oreDig, "private boolean tickVisibleOreSighting(");
        String interrupt = methodBody(oreDig, "private boolean interruptObservedOreSearchWithVisibleOre(");
        String next = methodBody(oreDig, "private VisibleTargetHorizonScan.Sighting nextVisibleOreSighting(");
        String vertical = methodBody(oreDig, "private boolean startVisibleOreVerticalRecovery(");

        int interruptAt = oreDig.indexOf("interruptObservedOreSearchWithVisibleOre(bot)");
        int ordinaryHop = oreDig.indexOf("if (tickObservedOreSearch(bot, world))");
        assertTrue(interruptAt >= 0 && ordinaryHop > interruptAt,
                "a visible remote ore should get a chance to replace an ordinary observed compass hop");
        assertTrue(next.contains("new VisibleTargetHorizonScan(targetOres)")
                        && seek.contains("insideLocalOreScanEnvelope(bot, sighting.pos())")
                        && pursuit.contains("startVisibleLandmarkPursuitTo(")
                        && pursuit.contains("isCurrentVisibleTargetOre(bot, visibleOreSightingHint)"),
                "a remote ore must be re-proved and pursued only through the render-aware walk-only route");
        assertTrue(tick.contains("isCurrentVisibleTargetOre(bot, visibleOreSightingHint)")
                        && tick.contains("yieldVisibleOreToLocalScan()")
                        && interrupt.contains("clearObservedOreSearchLeg()"),
                "LOS must be rechecked during pursuit and entry into the local envelope must hand off cleanly");
        assertTrue(interrupt.indexOf("clearObservedOreSearchLeg()")
                        < interrupt.indexOf("startVisibleOreSightingPursuit(bot)"),
                "replacing an observed hop must release its owner before starting the new landmark route");
        assertFalse(seek.contains("return visibleOreHorizonScan != null;"),
                "an incomplete remote scan must not stall OreDig's established local scan or safe-hop fallback");
        assertFalse(oreDig.contains("targetOre = visibleOreSightingHint")
                        || oreDig.contains("targetOre=visibleOreSightingHint"),
                "a remote sighting must never bypass local ore evidence by becoming a digging target directly");
        assertTrue(pursuit.contains("hasNoHorizontalHeading(bot, visibleOreSightingHint)")
                        && vertical.contains("highTargetStairSearch.begin(bot, null)")
                        && vertical.contains("visibleOreSightingHint = null")
                        && !vertical.contains("targetOre ="),
                "a vertically aligned remote ore must use finite observed stair/ledge alternatives, never a fabricated directional goal or direct mine lock");
    }

    private static String methodBody(String source, String signature) {
        int signatureAt = source.indexOf(signature);
        assertTrue(signatureAt >= 0, () -> "missing method signature: " + signature);
        int open = source.indexOf('{', signatureAt);
        assertTrue(open >= 0, () -> "missing method body: " + signature);
        int depth = 0;
        for (int at = open; at < source.length(); at++) {
            char current = source.charAt(at);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(open, at + 1);
            }
        }
        throw new AssertionError("unterminated method body: " + signature);
    }
}

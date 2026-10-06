package io.github.zoyluo.minecraftai.baritone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Source contracts for the live-action boundary: a Baritone click may consult current terrain
 * only after the dimension-aware action fence and a physical observation proof have admitted the
 * exact cell. These checks deliberately pin ordering rather than duplicate the GameTests.
 */
class BaritoneObservedActionProvenanceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing " + signature);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int at = open; at < source.length(); at++) {
            char current = source.charAt(at);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(start, at + 1);
            }
        }
        throw new AssertionError("unclosed " + signature);
    }

    @Test
    void breakAndClickUseTheContextsDimensionAwareFenceBeforeLiveTerrain() throws IOException {
        String policy = read("baritone/BaritoneBreakPlacePolicy.java");
        String breakCheck = method(policy, "public static Decision checkBreak(");
        int actionGate = breakCheck.indexOf("currentObservedNavigationCell(bot, pos)");
        int state = breakCheck.indexOf("bot.level().getBlockState(pos)");
        int entity = breakCheck.indexOf("bot.level().getBlockEntity(pos)");
        assertTrue(actionGate >= 0 && state > actionGate && entity > state,
                "break state/entity reads need the current observation + dimension gate first");

        String current = method(policy, "private static boolean currentObservedNavigationCell(");
        assertTrue(current.contains("BaritoneRegistry.INSTANCE.allowNavigationActionCell(bot, pos)")
                        && current.contains("ObservableWorldQuery.canObserveCellStrict(bot, pos)"),
                "the live break proof must use the context fence, not a dimensionless raw snapshot");

        String click = method(policy, "public static Decision checkClickBlock(");
        int supportGate = click.indexOf("allowNavigationActionCell(bot, against)");
        int rayProof = click.indexOf("BuildAction.supportFaceRefusal(bot, hit)");
        int supportState = click.indexOf("bot.level().getBlockState(against)");
        int destinationGate = click.indexOf("allowNavigationActionCell(bot, destination)");
        int snapshot = click.indexOf("fence.stateAt(destination)");
        assertTrue(supportGate >= 0 && rayProof > supportGate && supportState > rayProof,
                "support state is read only after its fenced exact-face proof");
        assertTrue(destinationGate > supportState && snapshot > destinationGate,
                "destination snapshot must be dimension-gated before it is consulted");
        assertFalse(click.contains("bot.level().getBlockState(destination)"),
                "a Baritone placement policy must not scan its destination live");

        String goals = read("baritone/BaritoneGoals.java");
        String mine = method(goals, "public static Outcome mineAt(");
        int cellProof = mine.indexOf("ObservableWorldQuery.canObserveBlockCellFaceStrict(bot, target)");
        int cellRay = mine.indexOf("ObservableWorldQuery.canObserveCellStrict(bot, target)");
        int shapeProof = mine.indexOf("ObservableWorldQuery.canObserveBlockStrict(bot, target)");
        int liveRead = mine.indexOf("bot.level().getBlockState(target)");
        assertTrue(cellProof >= 0 && cellRay > cellProof && shapeProof > cellRay && liveRead > shapeProof,
                "the public direct-mine seam needs a state-free cell proof before shape or state reads");
    }

    @Test
    void controllerRechecksBreaksAndPublishesOnlyTheCheckedActionResult() throws IOException {
        String controller = read("baritone/ServerPlayerController.java");
        String click = method(controller, "public InteractionResult processRightClickBlock(");
        assertTrue(click.contains("BuildAction.useItemOnHit(self, result, hand, click.placementState())"));
        assertTrue(click.contains("recordObservedPlacement(self, use.destination(), use.placementState())"));
        assertFalse(click.contains("getBlockState(use.destination())"),
                "a successful placement must not authorize a destination reread");

        String step = method(controller, "private boolean step()");
        int recheck = step.indexOf("BaritoneBreakPlacePolicy.checkBreak(self, mining.pos())");
        int current = step.indexOf("self.level().getBlockState(mining.pos())");
        int tick = step.indexOf("mining.tick(self.getActionPack())");
        assertTrue(recheck >= 0 && current > recheck && tick > current,
                "each mining tick must freshly prove the cell before state or driven mining reads it");
    }

    @Test
    void waterBucketSourceReadsNeedCurrentCellsAndOwnWaterNeedsFreshSameDimensionProof() throws IOException {
        String controller = read("baritone/ServerPlayerController.java");
        String use = method(controller, "public InteractionResult processRightClick(");
        assertTrue(use.contains("BaritoneWaterFall.observedSourceStates(self, block)"));
        assertFalse(use.contains("world.getFluidState("),
                "the controller must not inspect either bucket destination without the water proof helper");

        String fall = read("baritone/BaritoneWaterFall.java");
        String fallRule = method(fall, "static String refusalOf(");
        assertTrue(fallRule.contains("currentFallActionCell(bot, target)")
                        && fallRule.contains("currentFallActionCell(bot,\n                target.relative"),
                "the water bucket target and destination need fresh action-cell proof before use");
        String sources = method(fall, "static boolean[] observedSourceStates(");
        int proof = sources.indexOf("currentFallActionCell(bot, first)");
        int read = sources.indexOf("bot.level().getFluidState(first)");
        assertTrue(proof >= 0 && read > proof && sources.contains("currentFallActionCell(bot, second)"),
                "both possible bucket cells need current action-cell proofs before fluid reads");
        String actionCell = method(fall, "private static boolean currentFallActionCell(");
        assertTrue(actionCell.contains("BaritoneRegistry.INSTANCE.allowNavigationActionCell(bot, pos)")
                        && actionCell.contains("ObservableWorldQuery.canObserveCellThroughFluids(bot, pos)"),
                "a placed water source stays fence-bound and is re-proven through the player's water-transparent view");
        String afterWaterUse = method(fall, "static void afterWaterBucketUse(");
        int result = afterWaterUse.indexOf("boolean[] now = observedSourceStates(bot, ray)");
        int placement = afterWaterUse.indexOf("recordObservedPlacement(bot, placed, bot.level().getBlockState(placed))");
        assertTrue(result >= 0 && placement > result,
                "only a freshly confirmed bucket result may replace the admitted AIR cell in the active fence");
        String recovery = method(fall, "static void recover(");
        int ownProof = recovery.indexOf("knownPlacedWaterIsVisible(bot, entry, water)");
        int fluid = recovery.indexOf("bot.level().getFluidState(water)");
        assertTrue(ownProof >= 0 && fluid > ownProof,
                "recovery may inspect only a same-dimension, freshly visible own placement");
        String ownWater = method(fall, "private static boolean knownPlacedWaterIsVisible(");
        assertTrue(ownWater.contains("ObservableWorldQuery.canObserveCellThroughFluids(bot, water)"),
                "recovery must not lose sight of a source merely because the bot is standing in its own visible water");
        String pickup = method(fall, "static void afterEmptyBucketUse(");
        int pickupProof = pickup.indexOf("knownPlacedWaterIsVisible(bot, entry, entry.placedWater)");
        int pickupFluid = pickup.indexOf("bot.level().getFluidState(entry.placedWater)");
        int pickupState = pickup.indexOf("recordObservedPlacement(bot, entry.placedWater,");
        assertTrue(pickupProof >= 0 && pickupFluid > pickupProof && pickupState > pickupFluid,
                "only a freshly visible own-water pickup may replace the prior water state in the active fence");
    }

    @Test
    void supportShapeAndTrustedPlacementNeverUseAnUnprovenLiveCell() throws IOException {
        String build = read("action/BuildAction.java");
        String support = method(build, "private static BlockHitResult supportFaceHit(");
        int unshaped = support.indexOf("unshapedSupportFaceHit(");
        int state = support.indexOf("player.level().getBlockState(against)");
        int shape = support.indexOf("FaceAim.aim(");
        assertTrue(unshaped >= 0 && state > unshaped && shape > state,
                "a support's shape state is read only after the state-free exact face proof");

        String use = method(build, "public static Use useItemOnHit(AIPlayerEntity player, BlockHitResult hit, InteractionHand hand,");
        assertTrue(use.contains("supportFaceRefusal(player, hit)") && use.contains("confirmedPlacementState("));
        assertTrue(use.contains("BlockState destinationBefore") && use.contains("BlockState destinationAfter"),
                "the already-admitted destination must be compared before and after the vanilla click");
        String confirmed = method(build, "private static BlockState confirmedPlacementState(");
        assertTrue(confirmed.contains("expected == null") && confirmed.contains("heldItemState == null")
                        && confirmed.contains("result.consumesAction()") && confirmed.contains("expected.equals(heldItemState)")
                        && confirmed.contains("destinationBefore.equals(destinationAfter)")
                        && confirmed.contains("destinationAfter.is(expected.getBlock())"),
                "only a changed, already-admitted destination matching the pre-checked block item may publish placement state");
    }
}

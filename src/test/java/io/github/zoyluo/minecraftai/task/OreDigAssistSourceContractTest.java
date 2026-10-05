package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 contract section G.5 (INTEGRATOR-2): source-contract pins for the mining-assist R1 opportunistic detour's
 * integration into {@code OreDigTask.java} (hooks 1-13, {@code DetourHostImpl}, the literal blacklist of design
 * 8.2 / P1 contract E.4). This file never boots a Minecraft registry: every check reads the production source
 * as text, like its neighbours in this package.
 */
class OreDigAssistSourceContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/task/OreDigTask.java");
    private static final Path CHECKPOINT_SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/task/OreDigCheckpoint.java");

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    private static int count(String text, String needle) {
        int found = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + needle.length())) {
            found++;
        }
        return found;
    }

    // ---- hook 9: drain a finite vein before opportunistic work; zero-quota recovery owns its own drain --------

    @Test
    void finiteVeinDrainPrecedesOpportunisticDispatchAndZeroQuotaRecoveryHasItsOwnDrain() throws IOException {
        String source = read(SOURCE);
        int tick = source.indexOf("protected void onTick");
        int retryAtRear = source.indexOf("PendingBlindAdvancePhase.RETRY_AT_REAR", tick);
        int onTickAdvanceVein = source.indexOf("advanceVein(bot, world)", retryAtRear);
        int dispatch = source.indexOf("if (tickOpportunistic(bot, world)) {", retryAtRear);
        int zeroQuotaFinish = source.indexOf("private void finishAlreadyDeliveredBatch", dispatch);
        int zeroQuotaAdvanceVein = source.indexOf("advanceVein(bot, world)", zeroQuotaFinish);
        assertTrue(tick >= 0 && retryAtRear > tick && onTickAdvanceVein > retryAtRear
                        && dispatch > onTickAdvanceVein && zeroQuotaFinish > dispatch
                        && zeroQuotaAdvanceVein > zeroQuotaFinish,
                "ordinary onTick draining must retain priority over opportunistic work, while "
                        + "zero-quota seam recovery drains through its own helper");
    }

    // ---- the literal blacklist of design 8.2 / P1 contract E.4: unchanged counts, no new banned text ----------

    @Test
    void literalBlacklistCountsAreUnchangedByTheDetourInserts() throws IOException {
        String source = read(SOURCE);
        String checkpointSource = read(CHECKPOINT_SOURCE);
        assertEquals(2, count(source, "advanceVein(bot, world)"));
        assertEquals(1, count(source, "if (activeBonus)"));
        assertEquals(1, count(source, "if (miningTarget)"));
        assertEquals(1, count(source, "if (miningVein)"));
        assertEquals(1, count(source, "BlockPos found = nearestOre(bot, world)"));
        assertEquals(1, count(source, "miner.begin(bot, pos, true)"));
        assertEquals(10, count(source, "failMissingMiningChannelTool(bot)"));
        // Finite target owners now route only through surface paths or one-cell guarded digging;
        // the remaining generic path calls are non-target strip/face recovery work.
        assertEquals(2, count(source, "startPathTo("));
        assertEquals(1, count(source, "startDigPathTo("));
        assertEquals(10, count(source, "digTowardStep"));
        assertEquals(2, count(source, "if (restoringFace)"), "no new if (restoringFace) may be added");
        // Current checkpoints carry factual break anchors and separately observed queue hints;
        // old constructors still default both ledgers rather than inventing authority.
        assertTrue(checkpointSource.contains("openedVeinBreaks, queuedVeinHints")
                        && checkpointSource.contains("queued_vein_hints"),
                "the durable seam queue must remain distinct from factual broken anchors");
        assertEquals(1, count(source, "|| !veinQueue.isEmpty() || bonusOre != null"),
                "the pinned boolean-or literal must stay on one line, unduplicated");
        assertEquals(3, count(source, "NO_PROGRESS_LIMIT"), "the NO_PROGRESS_LIMIT condition must not be touched");
        assertEquals(0, count(source, ".teleportTo("), "no detour text may ever write a teleport call");
    }

    // ---- host uses beginMine(, never a fresh miner.begin/fail/failMissingMiningChannelTool -------------------

    @Test
    void hostMineStepUsesTheExistingBeginMineHelper() throws IOException {
        String source = read(SOURCE);
        int mineStep = source.indexOf("public MineStep mineStep(BlockPos pos) {");
        assertTrue(mineStep >= 0, "DetourHostImpl.mineStep must exist");
        int beginMineCall = source.indexOf("beginMine(bot, pos)", mineStep);
        int methodEnd = source.indexOf("}", beginMineCall);
        assertTrue(beginMineCall > mineStep && methodEnd > beginMineCall,
                "mineStep must dispatch through the existing beginMine(bot, pos) helper");
    }

    // ---- checkpoint(): the substitution is additive, the pendingBlindAdvance boundary is untouched -------------

    @Test
    void checkpointSubstitutesTheAnchorFaceWithoutTouchingItsGuardedBody() throws IOException {
        String source = read(SOURCE);
        int checkpoint = source.indexOf("public Map<String, String> checkpoint()");
        int substitution = source.indexOf("BlockPos face = detourPublishedFace(lastFace == null ? origin : lastFace);", checkpoint);
        int checkpointEnd = source.indexOf(
                "/** Keeps only the structurally exact branch-owned rear identity", checkpoint);
        assertTrue(checkpoint >= 0 && substitution > checkpoint && checkpointEnd > substitution,
                "checkpoint() must read the anchor face through detourPublishedFace(...)");
        String body = source.substring(checkpoint, checkpointEnd);
        assertFalse(body.contains("pendingBlindAdvance"),
                "the vanished ActionPack walker must still never become durable checkpoint authorization");
        assertTrue(body.contains("detourPublishedFace("));
    }

    // ---- hook 12: checkpointControlledStripRear gains one continuation line, directly after the first if -------

    @Test
    void checkpointControlledStripRearKeepsAssistOutOfTheDurableRearWhileLive() throws IOException {
        String source = read(SOURCE);
        int method = source.indexOf("private BlockPos checkpointControlledStripRear(BlockPos face) {");
        int firstIf = source.indexOf("if (controlledStripRear == null || face == null", method);
        int continuation = source.indexOf("|| assistDetourActive()", firstIf);
        int nextLine = source.indexOf("|| stripDirIndex < 0", continuation);
        assertTrue(method >= 0 && firstIf > method && continuation > firstIf && nextLine > continuation,
                "assistDetourActive() must be the first continuation line after the guard's first if");
    }

    // ---- hooks 5-8: onPause / onAbort / onResume / onTick's restoringFace branch -------------------------------

    @Test
    void onPauseCallsDetourInterruptedFirst() throws IOException {
        String source = read(SOURCE);
        int pause = source.indexOf("protected void onPause");
        int abort = source.indexOf("protected void onAbort");
        assertTrue(pause >= 0 && abort > pause);
        String body = source.substring(pause, abort);
        int detourInterrupted = body.indexOf("detourInterrupted(bot);");
        int publish = body.indexOf("publishInterruptionCursor(bot, true)");
        int clear = body.indexOf("clearPendingBlindAdvance()");
        int stop = body.indexOf("bot.getActionPack().stopAll()");
        assertTrue(detourInterrupted >= 0 && detourInterrupted < publish
                        && publish < clear && clear < stop,
                "onPause must ask detourInterrupted(bot) before the pre-existing pause bookkeeping");
    }

    @Test
    void onAbortWrapsTheExistingBodyWithDetourInterruptAndReapply() throws IOException {
        String source = read(SOURCE);
        int abort = source.indexOf("protected void onAbort");
        int onTick = source.indexOf("protected void onTick");
        assertTrue(abort >= 0 && onTick > abort);
        String body = source.substring(abort, onTick);
        int detourWasLive = body.indexOf("boolean detourWasLive = detourInterrupted(bot);");
        int publish = body.indexOf("publishInterruptionCursor(bot, false)");
        int clear = body.indexOf("clearPendingBlindAdvance()");
        int markFace = body.indexOf("markMineFace(bot)");
        int cancel = body.indexOf("miner.cancel(bot)");
        int stop = body.indexOf("bot.getActionPack().stopAll()");
        int reapply = body.indexOf("reapplyAnchor(bot, detourWasLive);");
        assertTrue(detourWasLive >= 0 && detourWasLive < publish
                        && publish < clear && clear < markFace
                        && markFace < cancel && cancel < stop && stop < reapply,
                "onAbort must capture detourWasLive first and reapply the anchor last, "
                        + "around the unchanged pre-existing statements");
    }

    @Test
    void detourResumedIsCalledOnceRightAfterTheRestoringFacePairInOnResume() throws IOException {
        String source = read(SOURCE);
        int resume = source.indexOf("protected void onResume");
        int abort = source.indexOf("protected void onAbort");
        assertTrue(resume >= 0 && abort > resume);
        String body = source.substring(resume, abort);
        int restoringFaceAssign = body.indexOf("restoringFace = !bot.blockPosition().equals(lastFace);");
        int restoreFaceStarted = body.indexOf("restoreFaceStarted = elapsed;", restoringFaceAssign);
        int detourResumedCall = body.indexOf("detourResumed(restoringFace);", restoreFaceStarted);
        assertTrue(restoringFaceAssign >= 0 && restoreFaceStarted > restoringFaceAssign
                        && detourResumedCall > restoreFaceStarted,
                "detourResumed(restoringFace) must follow the restoringFace/restoreFaceStarted pair");
        assertEquals(1, count(body, "detourResumed("),
                "onResume must call detourResumed( exactly once");
    }

    @Test
    void detourResumeReturnPrecedesReturnToSavedFaceInsideTheFirstRestoringFaceBranch() throws IOException {
        String source = read(SOURCE);
        int tick = source.indexOf("protected void onTick");
        int restoringFaceBranch = source.indexOf("if (restoringFace) {", tick);
        int resumeReturn = source.indexOf("if (detourResumeReturn(bot)) {", restoringFaceBranch);
        int savedFace = source.indexOf("returnToSavedFace(bot);", resumeReturn);
        assertTrue(tick >= 0 && restoringFaceBranch > tick && resumeReturn > restoringFaceBranch
                        && savedFace > resumeReturn,
                "the walk-only resume must be tried before the existing returnToSavedFace machinery");
    }

    // ---- oreExcluded is untouched (pinned as a byte-identical string) ------------------------------------------

    @Test
    void oreExcludedBodyIsByteIdentical() throws IOException {
        String source = read(SOURCE);
        String expected = "private boolean oreExcluded(AIPlayerEntity bot, BlockPos pos) {\n"
                + "        return EpisodeMemory.INSTANCE.isExcluded(bot.getUUID(), pos, bot.level().getServer().getTickCount());\n"
                + "    }";
        assertTrue(source.contains(expected), "oreExcluded's body text must not change");
    }

    // ---- hook 2: a separate statement after the oreExcluded/withinReach guard, inside scanBonusOre only --------

    @Test
    void hookTwoIsASeparateStatementInsideScanBonusOreOnly() throws IOException {
        String source = read(SOURCE);
        int scanBonusOre = source.indexOf("private BlockPos scanBonusOre(AIPlayerEntity bot, ServerLevel world) {");
        int scanBonusOreEnd = source.indexOf("private OreDigTask.DetourHostImpl detourHost;", scanBonusOre);
        assertTrue(scanBonusOre >= 0 && scanBonusOreEnd > scanBonusOre);
        String body = source.substring(scanBonusOre, scanBonusOreEnd);
        int guard = body.indexOf("if (oreExcluded(bot, pos) || !withinReach(bot, pos)) {");
        int guardEnd = body.indexOf("}", guard);
        int claim = body.indexOf("if (OreClaims.heldByOther(bot, pos)) {", guardEnd);
        assertTrue(guard >= 0 && guardEnd > guard && claim > guardEnd,
                "hook 2 must be its own if-statement right after the exclusion/reach guard");
        assertEquals(1, count(body, "OreClaims.heldByOther(bot, pos)"),
                "scanBonusOre must claim-check each candidate exactly once");
    }

    // ---- hook 3: the immediate-lava guard is never dropped for an active detour --------------------------------

    @Test
    void avoidObservedLavaNeverDropsTheImmediateDangerGuard() throws IOException {
        String source = read(SOURCE);
        int method = source.indexOf("boolean avoidObservedLava(AIPlayerEntity bot, BlockPos lavaPos) {");
        int detourGuard = source.indexOf("if (assistDetourActive()) {", method);
        int claim = source.indexOf("return detourClaimLava(bot, lavaPos);", detourGuard);
        assertTrue(method >= 0 && detourGuard > method && claim > detourGuard,
                "avoidObservedLava must route an active detour to detourClaimLava");
        int nextMethod = source.indexOf("Optional<MiningBarricadeTask> prepareHostileBarricade");
        String body = source.substring(method, nextMethod);
        assertTrue(body.contains("if (assistDetourActive()) {")
                        && body.contains("return detourClaimLava(bot, lavaPos);"),
                "the assist guard must be avoidObservedLava's own first statement");
        int claimLava = source.indexOf("private boolean detourClaimLava(AIPlayerEntity bot, BlockPos lavaPos) {");
        assertTrue(claimLava > 0);
        int claimLavaGuard = source.indexOf("hasImmediateLava(bot)", claimLava);
        int claimLavaEnd = source.indexOf("if (detour.phase() == DetourPhase.RETURN)", claimLava);
        assertTrue(claimLavaGuard > claimLava && claimLavaEnd > claimLavaGuard,
                "detourClaimLava must test hasImmediateLava(bot) before it can claim the sighting");
    }

    // ---- hooks 4 and 10: prepareHostileBarricade / ownsActiveBlindBranchCollision start with the assist guard --

    @Test
    void prepareHostileBarricadeAndBlindBranchCollisionYieldToALiveDetour() throws IOException {
        String source = read(SOURCE);
        int barricade = source.indexOf(
                "Optional<MiningBarricadeTask> prepareHostileBarricade(AIPlayerEntity bot, BlockPos hostilePos) {");
        int barricadeGuard = source.indexOf("if (assistDetourActive()) {", barricade);
        int barricadeEmpty = source.indexOf("return Optional.empty();", barricadeGuard);
        assertTrue(barricade >= 0 && barricadeGuard > barricade && barricadeEmpty > barricadeGuard);

        int collision = source.indexOf(
                "private boolean ownsActiveBlindBranchCollision(AIPlayerEntity bot,");
        int collisionGuard = source.indexOf("if (assistDetourActive()) {", collision);
        int collisionFalse = source.indexOf("return false;", collisionGuard);
        assertTrue(collision >= 0 && collisionGuard > collision && collisionFalse > collisionGuard);
    }

    // ---- inner class shape, and every same-simple-name delegation is qualified ---------------------------------

    @Test
    void innerHostClassIsAPrivateFinalMemberImplementingBothInterfaces() throws IOException {
        String source = read(SOURCE);
        assertTrue(source.contains("private final class DetourHostImpl implements DetourHost, DetourControl {"));
    }

    @Test
    void everySameSimpleNameDelegationIsExplicitlyQualified() throws IOException {
        String source = read(SOURCE);
        assertTrue(source.contains("OreDigTask.isCurrentSupport(bot, ore)"));
        assertTrue(source.contains("OreDigTask.this.detourRebaseTargetMonitors();"));
        assertTrue(source.contains("OreDigTask.this.detourRebaseCursor(bot.blockPosition());"));
        assertTrue(source.contains("OreDigTask.this.noteProgress();"));
    }

    @Test
    void theDesignsBareHelperNamesOnlyOccurAsTheInterfaceMethodsThemselves() throws IOException {
        String source = read(SOURCE);
        // The outer helpers are named with a "detour" prefix so no interface method and outer
        // helper ever share a simple name (JLS 15.12.1: an unqualified same-name delegation would
        // recurse into itself instead of failing to compile).
        assertTrue(source.contains("private void detourRebaseTargetMonitors() {"));
        assertTrue(source.contains("private void detourRebaseCursor(BlockPos here) {"));
        assertEquals(1, count(source, "void rebaseTargetMonitors("),
                "rebaseTargetMonitors must be declared exactly once (the interface/impl method)");
        assertEquals(1, count(source, "void rebaseCursorHere("),
                "rebaseCursorHere must be declared exactly once (the interface/impl method)");
    }

    // ---- minStandY() agrees with DetourPolicy.MIN_STAND_Y ------------------------------------------------------

    @Test
    void minStandYAgreesWithDetourPolicyMinStandY() throws IOException {
        String source = read(SOURCE);
        assertTrue(source.contains("private static final int MIN_Y = -60;"));
        int minStandY = source.indexOf("public int minStandY() {");
        int body = source.indexOf("return MIN_Y + 1;", minStandY);
        assertTrue(minStandY >= 0 && body > minStandY);
        String policy = read(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/mining/assist/DetourPolicy.java"));
        assertTrue(policy.contains("public static final int MIN_STAND_Y = -59;"),
                "DetourPolicy.MIN_STAND_Y must equal OreDig's MIN_Y + 1 (-60 + 1 = -59)");
    }
}

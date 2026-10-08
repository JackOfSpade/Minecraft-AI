package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Pins the visible-tree and high-target recovery boundary without requiring a loaded game world. */
final class GatherTreeRecoverySourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    @Test
    void treeLookAroundUsesRenderBoundedFirstHitRaysAndSharedPlayerSight() throws IOException {
        String scan = read("task/TreeHorizonScan.java");
        String gather = read("task/GatherQuotaTask.java");
        String pack = read("action/ActionPack.java");

        assertTrue(scan.contains("ObservableWorldQuery.castSightRay(")
                        && scan.contains("ObservableWorldQuery.visibleRangeBlocks(bot) - 1")
                        && scan.contains("ObservableWorldQuery.ViewShape.OUTLINE"),
                "tree discovery must sweep the actual tracked render range with sight rays that see through the canopy to the first opaque block");
        assertTrue(scan.contains("hit.crossed()") && scan.contains("Kind.LEAF"),
                "a leaf a ray crossed is still a landmark when no trunk shows behind it");
        assertTrue(scan.contains("SharedWorldSight.knownBlocks(")
                        && scan.contains("state.is(BlockTags.LEAVES)"),
                "a player-visible canopy must be a usable, re-proved tree landmark");
        assertTrue(scan.contains("SHARED_SIGHT_RECHECK_STEPS")
                        && scan.contains("shouldHoldFallback()")
                        && scan.contains("int elevationSlot = inPhase % ELEVATION_SAMPLES")
                        && scan.contains("AZIMUTH_STRIDE"),
                "tree look-around must re-check new shared sight and interleave vertical/horizon rays without parking on one pitch band");
        assertFalse(scan.contains("getChunk(") || scan.contains("betweenClosedStream"),
                "the horizon sweep must not turn tree discovery into a loaded-chunk volume scan");
        String survey = methodBody(gather, "private void survey(");
        int sweep = survey.indexOf("seekVisibleTree(bot)");
        int fallback = survey.indexOf("escapeBarrenAreaOrFail(bot)", sweep);
        assertTrue(sweep >= 0 && fallback > sweep,
                "visible-tree look-around must happen before legacy empty-area exploration");
        String pursuit = methodBody(gather, "private boolean startTreeSightingPursuit(");
        String pursuitDistance = methodBody(gather, "private int treeSightingPursuitDistance(");
        assertTrue(pursuit.contains("startVisibleLandmarkPursuitTo(")
                        && gather.contains("TREE_SIGHTING_HOP_DISTANCE = 48"),
                "a leaf landmark must use its render-aware, admitted local pursuit rather than a blind remote route");
        assertTrue(pursuitDistance.contains("ObservableWorldQuery.visibleRangeBlocks(bot)")
                        && pursuit.contains("gather_tree_sighting_short_leg_fallback"),
                "an unobstructed landmark should receive a render-distance leg, with a shorter ray-proven fallback for occluded ground");
        assertTrue(pursuit.contains("isCurrentVisibleTreeLandmark(bot, treeSightingHint)")
                        && gather.contains("gather_tree_sighting_lost"),
                "a remembered leaf landmark must be re-proved before and during pursuit");
        assertFalse(pursuit.contains("startDirectionalPursuitTo("),
                "a visible landmark must not silently fall back to the short generic compass pursuit");
        String landmarkPursuit = methodBody(pack, "public ActionResult startVisibleLandmarkPursuitTo(");
        assertTrue(landmarkPursuit.contains("NavRoute.Options.WALK_ONLY")
                        && landmarkPursuit.contains("\"visible_landmark_pursuit\"")
                        && landmarkPursuit.contains("ObservableWorldQuery.canObserveBlock(player, landmark)")
                        && landmarkPursuit.contains("visible_landmark_no_horizontal_heading"),
                "the long landmark leg must remain an observed walk-only route with a live LOS proof and a safe vertical handoff");
    }

    @Test
    void highTargetRecoveryUsesOnlySafeSupportAndNoDigPillarRoute() throws IOException {
        String gather = read("task/GatherQuotaTask.java");
        String harvest = read("action/HarvestCore.java");
        String pack = read("action/ActionPack.java");
        String palette = read("action/MaterialPalette.java");
        String resupply = methodBody(gather, "private boolean startNextScaffoldSupply(");
        String localResupply = methodBody(gather,
                "static GatherQuotaTask collectNearbyPillarSupport(Item item, int count, Set<Item> inheritedProtectedItems)");
        String pillarStart = methodBody(gather, "private boolean startPillarApproach(");
        String gotoTarget = methodBody(gather, "private void goToTarget(");
        String survey = methodBody(gather, "private void survey(");
        String pillarPlanning = methodBody(harvest, "private static List<PillarApproach> columnApproaches(")
                + methodBody(harvest, "private static PillarApproach columnApproach(");
        String supports = listBody(palette, "PILLAR_SUPPORT_BLOCKS");
        String nearbySupports = listBody(palette, "NEARBY_PILLAR_SUPPORT_GATHER_ITEMS");

        assertTrue(gather.contains("HarvestCore.beginNearestPillarApproachScan(")
                        && gather.contains("SEARCH_RADIUS, SEARCH_DOWN, PILLAR_SEARCH_UP")
                        && gather.contains("MaterialPalette.countPillarSupportBlocks(bot)")
                        && resupply.contains("MaterialPalette.nearbyPillarSupportGatherItems()")
                        && resupply.contains("collectNearbyPillarSupport(")
                        && localResupply.contains("SCAFFOLD_SUPPLY_RADIUS")
                        && localResupply.contains("SCAFFOLD_SUPPLY_TIMEOUT"),
                "a high visible target should use carried safe supports or gather nearby support material");
        assertTrue(gather.contains("pillarApproachScan.step(SCAN_STEP_BUDGET_NANOS)")
                        && harvest.contains("public static final class PillarApproachScan")
                        && harvest.contains("canObserveHarvestTarget(bot, cursor, false)"),
                "high-target recovery must time-slice its visible candidate scan instead of stalling a server tick");
        int pillarAttempt = survey.indexOf("tryPillarApproach(bot, botId, now)");
        int exactBreakBoundary = survey.indexOf("if (countBrokenBlocks)");
        assertTrue(Pattern.compile("if\\s*\\(\\s*!isLocalScaffoldSupply\\(\\)\\s*&&\\s*mayUsePillarRecovery\\(\\)\\s*&&\\s*tryPillarApproach\\(bot, botId, now\\)\\s*\\)")
                        .matcher(survey).find()
                        && pillarAttempt >= 0
                        && exactBreakBoundary > pillarAttempt
                        && gather.contains("private boolean mayUsePillarRecovery()")
                        && !survey.contains("gathersTreeLogs() && tryPillarApproach"),
                "every eligible outer GatherQuotaTask target, including exact-break targets, should try an observed pillar before giving up; a retained handoff target may not be spent as scaffold");
        assertTrue(palette.contains("nearbyPillarSupportGatherItems()"),
                "the local scaffold child must draw from the dedicated naturally gatherable support palette");
        assertFalse(Pattern.compile("Items\\.[A-Z_]*(?:LOG|PLANK|WOOD|STEM|HYPHAE|ORE|RAW_[A-Z_]*)")
                        .matcher(nearbySupports).find(),
                "emergency scaffold resupply must never target wood or ore materials");
        assertTrue(gather.contains("gather_scaffold_resupply") && gather.contains("gather_pillar_start"),
                "the recovery decision must be auditable in session logs");
        assertTrue(gather.contains("refreshPillarApproach(bot, approach)")
                        && harvest.contains("public static PillarApproach pillarApproachFor("),
                "a post-refill pillar must re-prove its exact target and clear column from the new pose");
        assertTrue(harvest.contains("canObserveHarvestTarget(bot, cursor, false)")
                        && harvest.contains("withinRenderObservationReach")
                        && harvest.contains("ObservableWorldQuery.visibleRangeBlocks(bot)")
                        && harvest.contains("isObservedClearPillarColumn")
                        && harvest.contains("state.isAir()"),
                "a pillar must be proposed only beside an observed high target through a visible air column");
        String pillar = methodBody(pack, "public ActionResult startPillarPathTo(");
        assertTrue(pillar.contains("MaterialPalette.pickPillarSupportBlockSlot(player)")
                        && pillar.contains("InventoryAction.equipFromSlot")
                        && pillar.contains("true, false, 0"),
                "the explicit pillar path must equip an approved filler, allow placement, and prohibit digging");
        assertFalse(pillar.contains("startDigPathTo("),
                "a pillar route must not hide a digging fallback behind its placement request");
        assertTrue(pillarStart.contains("startPillarPathTo(") && pillarStart.contains("pillarApproachActive = true"),
                "a successful pillar approach must be marked so its later route failure keeps the no-dig contract");
        int digFallback = gotoTarget.indexOf("startTunnelPathTo(targetPos)");
        assertTrue(digFallback > 0
                        && gotoTarget.substring(Math.max(0, digFallback - 700), digFallback)
                                .contains("!pillarApproachActive"),
                "a failed pillar leg must return to survey rather than tunneling through the canopy");
        assertTrue(Pattern.compile("PILLAR_MAX_HORIZONTAL_OFFSET\\s*=\\s*[2-9][0-9]*")
                        .matcher(harvest).find()
                        && pillarPlanning.contains("for (int dx = -PILLAR_MAX_HORIZONTAL_OFFSET;")
                        && pillarPlanning.contains("for (int dz = -PILLAR_MAX_HORIZONTAL_OFFSET;")
                        && pillarPlanning.contains("canReachFromPillarGoal(bot, target, goal)"),
                "leaf-blocked adjacent trunk cells require a reach-valid observed outer-ring pillar option");
        assertTrue(palette.contains("countPillarSupportBlocks")
                        && !supports.contains("Items.OAK_LOG"),
                "the support count must use the dedicated non-wood common-block palette");
    }

    @Test
    void everyTowerTheGatherBuildsIsOneItTracksAndTakesDown() throws IOException {
        String gather = read("task/GatherQuotaTask.java");
        String pack = read("action/ActionPack.java");

        assertFalse(gather.contains("startDigPathTo("),
                "a dig route that may place builds a stair or tower that nobody takes down, and the bot stays up on it"
                        + " (a log's pillar refused from one stance was followed by a Baritone-built tower of seven dirt"
                        + " and a bot that never came down); the gather's dig approach only breaks");
        assertTrue(gather.contains("startTunnelPathTo(target)") && gather.contains("startTunnelPathTo(targetPos)"),
                "both dig approaches of the gather use the route that places nothing");
        String tunnel = methodBody(pack, "public ActionResult startTunnelPathTo(");
        assertTrue(tunnel.contains("routeOnBaritone(\"dig_path_to\", goal, false, true, 0, RouteConstraints.unrestricted())")
                        && tunnel.contains("controllerStartBlocked()"),
                "the tunnel route may break (dig fallback) and may not pillar, behind the same guarded-step fence");
    }

    @Test
    void aPillarRefusedForWantOfSightIsNotWrittenOffAsLongAsAnUnreachableOne() throws IOException {
        String gather = read("task/GatherQuotaTask.java");
        String start = methodBody(gather, "private boolean startPillarApproach(");
        int refused = start.indexOf("if (route.isFailed())");
        int ttl = start.indexOf("EpisodeMemory.ttlAfterRouteRefusal(route.reason())", refused);
        int exclude = start.indexOf("EpisodeMemory.INSTANCE.exclude(", ttl);
        assertTrue(refused >= 0 && ttl > refused && exclude > ttl && !start.contains("TTL_UNREACHABLE"),
                "the exclusion after a refused pillar follows the reason of the refusal");
        assertTrue(start.contains("\"from\", bot.blockPosition().toShortString()") && start.contains("\"excluded_ticks\""),
                "the refusal logs where the bot stood and for how long the log is out");
    }

    @Test
    void aPillarRefusedForWantOfSightFromAFarFootIsWalkedToBeforeItIsSetAside() throws IOException {
        String gather = read("task/GatherQuotaTask.java");
        String start = methodBody(gather, "private boolean startPillarApproach(");
        int refused = start.indexOf("if (route.isFailed())");
        int walk = start.indexOf("stepOntoPillarBase(bot, approach.target(), List.of(approach))", refused);
        int setAside = start.indexOf("EpisodeMemory.INSTANCE.exclude(", refused);
        assertTrue(refused >= 0 && walk > refused && walk < setAside
                        && start.contains("mayWalkToFoot && NavRouteRules.isObservationRefusal(route.reason())")
                        && start.contains("> PILLAR_FOOT_WALK_DISTANCE_SQ"),
                "a log whose pillar route is refused for want of sight, with the column's foot more than three blocks away,"
                        + " is walked to first (the foot's own lanes are proved like any walk) rather than set aside");
        assertTrue(methodBody(gather, "private boolean pillarToBlock(").contains("admitPillarApproach(bot, flat, mayStepOntoFloor)"),
                "the planning after a walk onto a floor (which passes false) never walks again: no shuttling between two refusals");
        assertTrue(methodBody(gather, "private void goToTarget(").contains("pillarToBlock(bot, targetPos, false)"),
                "the pillar is planned from the foot with no further walk");
        assertTrue(methodBody(gather, "private boolean stepOntoPillarBase(").contains("scaffoldSupplyItem = null;"),
                "a walk to the foot ends any pending resupply of the pillar it replaces, as a started pillar does");
    }

    @Test
    void theItemsRestingPlaceSeenFromThePillarSurvivesTheTowerComingDown() throws IOException {
        String watch = read("task/TowerDropWatch.java");
        String gather = read("task/GatherQuotaTask.java");

        String hold = methodBody(watch, "boolean hold(AIPlayerEntity bot)");
        assertTrue(Pattern.compile("boolean supported = HarvestCore\\.isDropPhysicallySupported\\(bot, drop\\);\\s*"
                                + "if \\(supported\\) \\{\\s*restedAt = drop\\.blockPosition\\(\\)\\.immutable\\(\\);")
                        .matcher(hold).find(),
                "only an item seen at rest (supported) has a resting place: one that is still falling lies nowhere yet");
        String note = methodBody(gather, "private void noteRestingPlace(");
        assertTrue(note.contains("HarvestCore.isDropPhysicallySupported(bot, visibleDrop)")
                        && note.contains("dropRestedAt = dropWatch.restedAt()"),
                "the pickup learns the resting place from the floor when it sees the item, else from the watch on the tower");
        assertTrue(methodBody(gather, "private void pickup(").contains("noteRestingPlace(bot, visibleDrop.orElse(null));"),
                "every tick of the pickup window refreshes it");
        assertTrue(methodBody(gather, "private void clearPickupLedger()").contains("dropRestedAt = null;")
                        && methodBody(gather, "private void doStartHarvest(").contains("dropRestedAt = null;"),
                "a resting place belongs to one felled log and is forgotten with its ledger");
    }

    private static String listBody(String source, String name) {
        int declaration = source.indexOf("private static final List<Item> " + name + " = List.of(");
        int end = source.indexOf(");", declaration);
        assertTrue(declaration >= 0 && end > declaration, () -> "missing support palette " + name);
        return source.substring(declaration, end);
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

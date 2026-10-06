package io.github.zoyluo.minecraftai.mode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A bot's eyes pass through leaves, fences, glass and water; its hand does not. Sight is the see-through
 * {@link SightClip}, reach is a plain vanilla clip, and a break, an open or a use packet carries no pick ray of its own, so each
 * actuator re-proves with the strict predicate: seeing a log behind a leaf must never let the bot mine it through the leaf.
 * These read production sources as text, like the other source-contract tests; the behaviour is in the GameTests.
 */
class SightVersusReachSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    /** The see-through sight predicates; a reach proof must use the {@code Strict} twin of the one it needs. */
    private static final Pattern SIGHT_PREDICATE = Pattern.compile(
            "canObserve(?:Block|BlockCellFace|BlockWithInsetFaces|Cell|FarmCell|Collider\\w*)\\(");

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    private static String body(String source, String signature) {
        int signatureAt = source.indexOf(signature);
        assertTrue(signatureAt >= 0, () -> "missing method signature: " + signature);
        int open = source.indexOf('{', signatureAt);
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

    private static void assertStrictOnly(String what, String code, String... strictCalls) {
        assertFalse(SIGHT_PREDICATE.matcher(code).find(),
                what + " is a reach proof: it must ask the strict (vanilla clip) predicates, never the see-through sight ones");
        for (String call : strictCalls) {
            assertTrue(code.contains(call), what + " must call " + call);
        }
    }

    @Test
    void everyActuatorThatSendsNoPickRayReprovesWithTheStrictPredicate() throws IOException {
        String controller = read("action/MiningController.java");
        assertStrictOnly("the break gate", body(controller, "static boolean currentObservedTarget("),
                "canObserveBlockCellFaceStrict(", "canObserveCellStrict(", "canObserveBlockStrict(",
                "canObserveBlockWithInsetFacesStrict(");
        assertStrictOnly("the crop break gate", body(controller, "private static boolean currentObservedCropTarget("),
                "canObserveFarmCellStrict(");
        assertTrue(body(controller, "static boolean visiblyAir(").contains("ObservableWorldQuery.canObserveCell(player, pos)"),
                "settling a break that is already done may use sight: it sends no packet and breaks nothing");

        assertStrictOnly("opening a container", body(read("action/ContainerAction.java"), "public static boolean canSee("),
                "canObserveCellStrict(");
        assertStrictOnly("harvesting a crop", body(read("action/FarmAction.java"), "public static ActionResult harvestProof("),
                "canObserveFarmCellStrict(");
        // The seam asks the miner's own admission, which is the strict break gate or a way the miner clears (MiningObstruction).
        assertStrictOnly("the direct mining seam", body(read("baritone/BaritoneGoals.java"), "public static Outcome mineAt("),
                "MiningController.admissionRefusal(bot, target)");
        assertStrictOnly("Baritone's break authority",
                body(read("baritone/BaritoneBreakPlacePolicy.java"), "private static boolean currentObservedNavigationCell("),
                "canObserveCellStrict(");
    }

    @Test
    void containersFurnacesAndDepotsThatTheBotReachesIntoAreFoundAndUsedWithTheStrictProof() throws IOException {
        String smelt = read("task/SmeltTask.java");
        assertFalse(smelt.contains("canObserveBlock(bot, furnacePos)"),
                "a furnace is loaded and emptied without a click ray: every gate on it is the strict proof");
        assertEquals(3, count(smelt, "canObserveBlockStrict(bot, furnacePos)"));
        assertStrictOnly("the strip miner's depot deposit", body(read("task/StripMineTask.java"), "private void deposit("),
                "canObserveBlockStrict(bot, activeDepotChest)");
        String service = read("task/MiningServiceTask.java");
        assertStrictOnly("reaching into the mining depot", body(service, "private static boolean canInteractWithDepot("),
                "canObserveCellStrict(");
        assertStrictOnly("resuming with the mission depot", body(service, "public static boolean ownedMissionDepot("),
                "canObserveCellStrict(", "canObserveBlockStrict(");
        assertStrictOnly("finding a station", body(read("task/WorkshopLocator.java"), "private static java.util.List<BlockPos> observableMatches("),
                "canObserveBlockStrict(");
        assertStrictOnly("the chest already standing near", body(read("task/PlaceStationsTask.java"),
                "private static boolean stationAlreadyNearby("), "canObserveBlockStrict(");

        String interact = body(read("action/InteractAction.java"), "public static ActionResult useItemOnEntity(");
        assertTrue(interact.indexOf("StrikeLegality.hasStrikeLineOfSight(player, target)") >= 0
                        && interact.indexOf("StrikeLegality.hasStrikeLineOfSight(player, target)") < interact.indexOf("target.interact("),
                "Entity.interact has no pick ray and the server checks no line of sight: the click proves the vanilla collider line first");
    }

    @Test
    void theDigDecisionsAndTheMiningSweeperKeepTheStrictFirstHitRay() throws IOException {
        String safety = read("task/NavSafetyNet.java");
        String escape = body(safety, "private static BlockPos escapeBreakTarget(");
        assertTrue(escape.contains("ObservableWorldQuery.castViewRay(") && !escape.contains("castSightRay"),
                "the suffocation escape picks what to dig first: the first block a hand meets, not the one behind it");
        String sweeper = read("mining/assist/ViewSweeper.java");
        assertTrue(sweeper.contains("ObservableWorldQuery.castViewRay(") && !sweeper.contains("castSightRay"),
                "the sweeper's occupancy grid and hazard field need the strict ray");
    }

    @Test
    void sightSitesCastTheSeeThroughRayAndRecordWhatItCrossed() throws IOException {
        String fence = read("baritone/ObservedNavigationFence.java");
        assertFalse(fence.contains("castViewRay"), "the navigation fence is built from sight rays");
        assertEquals(5, count(fence, "castSightRay("), "the fan, the outline, two floor tops and the inset corner");
        assertTrue(body(fence, "private static void scanRay(").contains("view.seenState(pos)"),
                "a cell the fan crossed is stored as the foliage or water it is, never as air");

        String shared = read("perception/SharedWorldSight.java");
        assertFalse(shared.contains("new ClipContext("), "the shared memory is built from the observer's eyes");
        assertTrue(body(shared, "private static void captureRay(").contains("SightClip.crossedState(crossed, pos.asLong())"));

        String tree = read("task/TreeHorizonScan.java");
        assertEquals(3, count(tree, "castSightRay("));
        assertFalse(tree.contains("castViewRay"));
        assertTrue(body(tree, "private Sighting treeSighting(").contains("hit.crossed()"),
                "a leaf the ray crossed is still a landmark when no trunk shows behind it");
        String targets = read("task/VisibleTargetHorizonScan.java");
        assertEquals(3, count(targets, "castSightRay("));
        assertFalse(targets.contains("castViewRay"));

        assertTrue(read("task/SharedVision.java").contains("SightClip.hasLineOfSight(owner, entity)"));
        String safety = read("task/NavSafetyNet.java");
        for (String rescue : new String[] {"private static boolean canObserveWaterRescueSideCollider(",
                "private static boolean canObserveAdjacentWaterRescueCell("}) {
            String code = body(safety, rescue);
            assertTrue(code.contains("SightClip.clip(") && !code.contains("new ClipContext("), rescue);
        }
        for (String[] site : new String[][] {{"task/BoatSupport.java", "static boolean canObserveWater("},
                {"task/FireExtinguishTask.java", "static boolean canSeeWaterSurface("}}) {
            String code = body(read(site[0]), site[1]);
            assertTrue(code.contains("SightClip.clip(") && !code.contains("ClipContext("), site[0]);
        }
    }

    @Test
    void theQueriesCastAVanillaClipForTheStrictFormAndTheSightClipForTheSightForm() throws IOException {
        String query = read("mode/ObservableWorldQuery.java");
        String eye = body(query, "private static BlockHitResult eyeClip(");
        assertTrue(eye.contains("SightClip.clip(") && eye.contains("new ClipContext("),
                "one switch: the see-through clip, or the plain vanilla one");
        String core = body(query, "private static ViewHit castViewRay(");
        assertTrue(core.contains("SightClip.context(") && core.contains("new ClipContext("));
        assertFalse(query.contains("castViewRayThroughFluids"),
                "water is see-through to every sight ray now; the water-only view ray is gone");
    }

    @Test
    void theClickAndStrikeRaysStayPlainVanillaClips() throws IOException {
        for (String[] reach : new String[][] {
                {"action/StrikeLegality.java", "public static boolean hasStrikeLineOfSight("},
                {"action/BucketAction.java", "private static HitResult raycastWaterSource("},
                {"task/AcquireWaterTask.java", "private static boolean hasVisibleWaterFace("},
                {"action/BuildAction.java", "private static BlockHitResult rayTo("},
                {"action/BuildAction.java", "private static boolean supportHitProvesAdjacentDestinationVisible("}}) {
            String source = read(reach[0]);
            String code = body(source, reach[1]);
            assertTrue(code.contains("new ClipContext(") || code.contains("rayTo("), reach[0] + " " + reach[1]);
            assertFalse(source.contains("SightClip"), reach[0] + " reaches with a hand, so it casts no see-through ray");
        }
    }

    /** The only code that may cast a see-through ray: the queries, the recorders, the scans and the reviewed water sites. */
    @Test
    void onlyReviewedSightSitesCastASeeThroughRay() throws IOException {
        Set<String> sight = new TreeSet<>();
        Set<String> sightRay = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String relative = MAIN.relativize(file).toString().replace('\\', '/');
                if (relative.startsWith("mode/Sight") || relative.equals("mode/SeeThrough.java")) {
                    continue;
                }
                String source = Files.readString(file);
                if (source.contains("SightClip.") || source.contains("SightClipContext")) {
                    sight.add(relative);
                }
                if (source.contains("castSightRay(")) {
                    sightRay.add(relative);
                }
            }
        }
        assertEquals(Set.of("mode/ObservableWorldQuery.java",
                        "perception/SharedWorldSight.java", "task/BoatSupport.java", "task/FireExtinguishTask.java",
                        "task/NavSafetyNet.java", "task/SharedVision.java", "task/TreeHorizonScan.java",
                        "task/VisibleTargetHorizonScan.java"),
                sight, "a new see-through ray belongs in a reviewed sight site, never in code that reaches with a hand");
        assertEquals(Set.of("baritone/ObservedNavigationFence.java", "mode/ObservableWorldQuery.java",
                        "task/TreeHorizonScan.java", "task/VisibleTargetHorizonScan.java"),
                sightRay);
    }

    private static int count(String source, String needle) {
        int count = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}

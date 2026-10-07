package io.github.zoyluo.minecraftai.mining.assist;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source contract of the mining assist sensor (mining-assist design 9): the sensor may only look at the
 * world through the one honest first-hit ray, and none of the assist code may reach for a privileged
 * primitive. These read production sources as text, like the other source-contract tests.
 *
 * <p>The world and entity checks are an allow-list, not a blacklist: every member call on a world receiver in
 * the assist package (and in the coordinator) has to be one of four named calls, each in one named file and
 * each exactly once, and no world-typed variable may carry any name but {@code world} (so an alias cannot
 * hide a call from the scan). A new world read fails this test until it is reviewed and listed here.</p>
 */
class AssistObservationSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");
    private static final Path ASSIST = MAIN.resolve("mining/assist");
    private static final Path COORDINATOR = MAIN.resolve("coordination/MiningAssistCoordinator.java");

    /** Every path that must stay free of privileged primitives; entries that do not exist yet are skipped. */
    private static final List<String> GUARDED_FILES = List.of(
            "coordination/MiningAssistCoordinator.java",
            "coordination/PoiCoordinator.java",
            "task/OreDigDetourEngine.java",
            "task/DetourSafetyGate.java");

    private static final List<String> BANNED_TOKENS = List.of(
            "StructureManager",
            "structureManager",
            "getAllStarts", "startsForStructure",
            "findNearestMapStructure", "getStructureGeneratingAt",
            "maybeHas(",
            "getBlockEntity(",
            "teleportTo(",
            "teleport(",
            "setPos(",
            "snapTo(",
            "getEntities(",
            "getMaxLocalRawBrightness(",
            "TaskManager.INSTANCE.assign(");

    /** member name of a world call, the one file it may appear in, and how many times it appears there. */
    private record Allowed(String file, int count) {
    }

    private static final Map<String, Allowed> WORLD_CALLS = new LinkedHashMap<>();

    static {
        // the dimension id: the placed-cells ledger key and poi.cavernDimensions
        WORLD_CALLS.put("dimension", new Allowed("BotEdits.java", 1));
        // the one own-cell read of the POI pass: the biome id at the bot's feet
        WORLD_CALLS.put("getBiome", new Allowed("PoiDetector.java", 1));
        // one entity query per POI evaluation, every result still goes through canObserveEntity
        WORLD_CALLS.put("getEntitiesOfClass", new Allowed("PoiDetector.java", 1));
        // the other own-cell read: the underground test at the bot's feet (design 2.3 3e)
        WORLD_CALLS.put("canSeeSky", new Allowed("MiningAssistCoordinator.java", 1));
    }

    private static final Pattern WORLD_MEMBER = Pattern.compile(
            "(?:\\bworld|\\bgetServerLevel\\(\\)|\\blevel\\(\\)|\\bgetLevel\\(\\))\\s*\\.\\s*(\\w+)\\s*\\(");
    private static final Pattern WORLD_TYPED_NAME = Pattern.compile(
            "\\b(?:ServerLevel|Level|ClientLevel|LevelReader|BlockGetter|LevelAccessor|WorldGenLevel)\\s+(\\w+)");
    private static final Pattern VAR_ALIAS = Pattern.compile(
            "\\bvar\\s+\\w+\\s*=\\s*[^;]*\\b(?:getServerLevel|level|getLevel)\\s*\\(");

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    /** Source without comments or text-block/string content, so a Javadoc that names a call, or prose inside
     * a {@code """..."""} text block (e.g. P3's LLM system prompt, which legitimately contains the plain
     * English word "World" as prose, not a type), is never mistaken for real code by the regex scans below. */
    private static String code(Path path) throws IOException {
        return read(path).replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ")
                .replaceAll("(?s)\"\"\".*?\"\"\"", " ");
    }

    private static List<Path> assistSources() throws IOException {
        try (var stream = Files.walk(ASSIST)) {
            return stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static List<Path> assistAndCoordinator() throws IOException {
        List<Path> files = new ArrayList<>(assistSources());
        files.add(COORDINATOR);
        return files;
    }

    private static String castViewRayBody() throws IOException {
        String source = read(MAIN.resolve("mode/ObservableWorldQuery.java"));
        int start = source.indexOf("private static ViewHit castViewRay(AIPlayerEntity bot, double dx, double dy, double dz,\n"
                + "                                        double range, ViewShape shape, boolean seeThrough, BlockPos target) {");
        assertTrue(start >= 0, "the shared castViewRay implementation must exist");
        int end = source.indexOf("\n    }\n", start);
        assertTrue(end > start, "the shared castViewRay implementation must end with a method-level closing brace");
        return source.substring(start, end);
    }

    /** One method of a comment-free source: from its signature to its closing brace at method indentation. */
    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature + " must exist");
        int end = source.indexOf("\n    }\n", start);
        assertTrue(end > start, signature + " must end with a method-level closing brace");
        return source.substring(start, end);
    }

    // ---- the sweeper --------------------------------------------------------------------------

    @Test
    void viewSweeperCastsOnlyThroughCastViewRayAndNeverReadsABlockState() throws IOException {
        String sweeper = read(ASSIST.resolve("ViewSweeper.java"));
        assertTrue(sweeper.contains("ObservableWorldQuery.castViewRay("));
        assertFalse(sweeper.contains("getBlockState("),
                "the sweeper must never read a block itself; the first-hit state comes from castViewRay");
        assertFalse(sweeper.contains("CapabilityRuntime"), "the sweeper asks no capability question");
        assertFalse(sweeper.contains("clip("), "no direct world raycast in the sweeper");
    }

    @Test
    void theSweeperUsesTheAnswerOfCastViewRayAndTreatsASkippedRayAsUnknown() throws IOException {
        String sweeper = code(ASSIST.resolve("ViewSweeper.java"));
        assertEquals(1, count(sweeper, "ObservableWorldQuery.castViewRay("));
        assertFalse(sweeper.contains("castSightRay"),
                "the sweeper keeps the strict first-hit ray: its occupancy grid writes air for every traversed cell and its "
                        + "hazard field takes a water surface for a fluid hit, so a ray through foliage or water would erase both");
        assertTrue(Pattern.compile("ObservableWorldQuery\\.ViewHit\\s+view\\s*=\\s*ObservableWorldQuery\\.castViewRay\\(")
                .matcher(sweeper).find(), "the answer is kept, not thrown away");
        int unknown = sweeper.indexOf("if (view.isUnknown()) {");
        int miss = sweeper.indexOf("if (!view.hit()) {");
        int state = sweeper.indexOf("view.state()");
        assertTrue(unknown > 0 && miss > unknown && state > miss,
                "unknown, then miss, and only then the state of a real hit");
        assertTrue(sweeper.contains("SweepEngine.RayResult.UNKNOWN"), "a skipped ray is never recorded as free space");
    }

    @Test
    void castViewRayClampsToTrackedRenderDistanceAndReadsStateOnlyAfterTheBlockTypeCheck() throws IOException {
        String source = read(MAIN.resolve("mode/ObservableWorldQuery.java"));
        String body = castViewRayBody();
        assertTrue(body.contains("botRenderDistanceBlocks(bot)"),
                "range must be clamped to the bot's current tracked render distance");
        assertTrue(body.contains("Math.min(range, botRenderDistanceBlocks(bot))"),
                "range is min(range, tracked render distance in blocks)");
        assertFalse(body.contains("CapabilityRuntime.decide"), "no privileged read exists here");
        assertFalse(body.contains("CapabilityRuntime"), "not even a mention");
        assertTrue(body.contains("bot.getEyePosition()"), "the ray starts at the bot's own eye");
        assertTrue(source.contains("return castViewRay(bot, dx, dy, dz, range, shape, false, null);"),
                "the ordinary view ray stays the strict vanilla clip, opaque to foliage, fences, glass and water, for the sweeper");
        assertTrue(source.contains("return castViewRay(bot, dx, dy, dz, range, shape, true, target);"),
                "the see-through sight ray may share only the same bounded first-hit implementation");

        int typeCheck = body.indexOf("HitResult.Type.BLOCK");
        int stateRead = body.indexOf("getBlockState(");
        assertTrue(typeCheck > 0 && stateRead > typeCheck, "the state may only be read after the BLOCK type check");
        assertEquals(1, count(body, "getBlockState("), "exactly one read: the first-hit cell");
        assertTrue(body.indexOf("hasChunk(") > 0 && body.indexOf("hasChunk(") < body.indexOf("clip("),
                "an end chunk that is not loaded skips the ray before it is cast");
        assertTrue(body.contains("ViewHit.unknown()"));
        assertFalse(body.contains("FACE_SAMPLE_OFFSETS"));
        assertFalse(body.contains("canObserveBlockWithInsetFaces"));
    }

    @Test
    void castViewRayHasNoOriginParameterSoRaysCanOnlyStartAtTheEye() throws IOException {
        String source = read(MAIN.resolve("mode/ObservableWorldQuery.java")).replaceAll("\\s+", " ");
        assertTrue(source.contains(
                "public static ViewHit castViewRay(AIPlayerEntity bot, double dx, double dy, double dz, "
                        + "double range, ViewShape shape)"));
        assertTrue(source.contains("public enum ViewShape {"));
        assertTrue(source.contains(
                "public record ViewHit(boolean hit, BlockPos pos, Direction side, double distance, BlockState state, "
                        + "List<SightClipContext.Crossing> crossed)"));
    }

    // ---- no privilege anywhere in the assist ---------------------------------------------------

    @Test
    void noBannedTokenAppearsInTheAssistPackageOrItsCallers() throws IOException {
        List<String> violations = new ArrayList<>();
        List<Path> files = new ArrayList<>(assistSources());
        for (String relative : GUARDED_FILES) {
            Path path = MAIN.resolve(relative);
            if (Files.exists(path)) {
                files.add(path);
            }
        }
        assertFalse(files.isEmpty());
        for (Path file : files) {
            String source = code(file);
            for (String token : BANNED_TOKENS) {
                if (source.contains(token)) {
                    violations.add(MAIN.relativize(file).toString().replace('\\', '/') + " contains " + token);
                }
            }
        }
        assertTrue(violations.isEmpty(), violations.toString());
    }

    @Test
    void noAssistFileReadsABlockOrFluidFromTheWorldOrAsksAPrivilegeQuestion() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : assistSources()) {
            String source = code(file);
            String name = file.getFileName().toString();
            for (String token : List.of("getBlockState(", "world.getFluidState(", "CapabilityRuntime",
                    "PrivilegedCapability", "getChunk(", "getChunkAt(", "getChunkNow(")) {
                if (source.contains(token)) {
                    violations.add(name + " contains " + token);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "raw world reads belong only to the first-hit read inside castViewRay: " + violations);
    }

    @Test
    void everyWorldMemberCallIsOnTheAllowListInItsOneFileExactlyTheAllowedNumberOfTimes() throws IOException {
        Map<String, Integer> seen = new LinkedHashMap<>();
        List<String> violations = new ArrayList<>();
        for (Path file : assistAndCoordinator()) {
            String name = file.getFileName().toString();
            Matcher matcher = WORLD_MEMBER.matcher(code(file));
            while (matcher.find()) {
                String member = matcher.group(1);
                Allowed allowed = WORLD_CALLS.get(member);
                if (allowed == null || !allowed.file().equals(name)) {
                    violations.add(name + " calls world." + member + "(");
                } else {
                    seen.merge(member, 1, Integer::sum);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "a world read that is not on the reviewed allow-list: " + violations);
        for (Map.Entry<String, Allowed> entry : WORLD_CALLS.entrySet()) {
            assertEquals(entry.getValue().count(), seen.getOrDefault(entry.getKey(), 0),
                    entry.getKey() + " must appear exactly " + entry.getValue().count() + " time(s), in "
                            + entry.getValue().file());
        }
    }

    @Test
    void aWorldTypedVariableCannotCarryAnAliasThatHidesACallFromTheScan() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : assistAndCoordinator()) {
            String source = code(file);
            String name = file.getFileName().toString();
            Matcher typed = WORLD_TYPED_NAME.matcher(source);
            while (typed.find()) {
                String variable = typed.group(1);
                if (!"world".equals(variable)) {
                    violations.add(name + " declares a world under the name '" + variable + "'");
                }
            }
            if (VAR_ALIAS.matcher(source).find()) {
                violations.add(name + " aliases the world through var");
            }
        }
        assertTrue(violations.isEmpty(), violations.toString());
    }

    @Test
    void theOwnCellReadsAreTheFeetBiomeAndTheSkyFlagAndNothingElse() throws IOException {
        // The design's I2 names the biome id at the feet as the one extra own-cell read; the underground test
        // (design 2.3 step 3e) is a second one, the same call MineValuablesTask and DangerWatcher already make.
        // Both are pinned here to one call each in one file, and the sky read stays out of the assist package.
        int biome = 0;
        int sky = 0;
        for (Path file : assistAndCoordinator()) {
            String source = code(file);
            String name = file.getFileName().toString();
            int foundBiome = count(source, ".getBiome(");
            int foundSky = count(source, "canSeeSky(");
            if (foundBiome > 0) {
                assertEquals("PoiDetector.java", name);
            }
            if (foundSky > 0) {
                assertEquals("MiningAssistCoordinator.java", name);
            }
            biome += foundBiome;
            sky += foundSky;
        }
        assertEquals(1, biome, "one biome read per POI evaluation, at the bot's own feet");
        assertEquals(1, sky, "one sky read per sensing decision, at the bot's own feet");
        String detector = code(ASSIST.resolve("PoiDetector.java"));
        assertTrue(detector.contains("world.getBiome(feet)"));
        assertTrue(detector.contains("BlockPos feet = bot.blockPosition();"));
        assertTrue(code(COORDINATOR).contains("!bot.level().canSeeSky(bot.blockPosition())"));
    }

    @Test
    void entityEvidenceGoesThroughTheObservableBoundaryAndUsesItsAnswer() throws IOException {
        String detector = code(ASSIST.resolve("PoiDetector.java"));
        assertTrue(detector.contains("ObservableWorldQuery.canObserveEntity(bot, entity)"));
        assertTrue(detector.contains("!entity.isInvisible()"));
        assertEquals(1, count(detector, "getEntitiesOfClass("), "one entity query per evaluation");
        assertEquals(1, count(detector, "ObservableWorldQuery.canObserveEntity("));
        Matcher gate = Pattern.compile(
                "if\\s*\\(\\s*!\\s*ObservableWorldQuery\\.canObserveEntity\\(bot,\\s*entity\\)\\s*\\)\\s*\\{\\s*continue;\\s*\\}")
                .matcher(detector);
        assertTrue(gate.find(), "an entity the bot cannot observe is skipped, the answer is not ignored");
        assertTrue(detector.indexOf("evidence.add(", gate.end()) > gate.end(),
                "evidence is added only after the observation check");
        assertEquals(1, count(detector, "evidence.add("));
    }

    @Test
    void theEntityScanOrdersEvidenceCandidatesByDistanceAndCountsOnlyObservedEntitiesTowardItsCap() throws IOException {
        String detector = code(ASSIST.resolve("PoiDetector.java"));
        String scan = method(detector, "private static EntityEvidence scanEntities(");
        int sort = scan.indexOf("ordered.sort(");
        int loop = scan.indexOf("for (Entity entity : ordered) {");
        int observe = scan.indexOf("ObservableWorldQuery.canObserveEntity(bot, entity)");
        int accept = scan.indexOf("accepted++;");
        assertTrue(sort > 0 && loop > sort && observe > loop && accept > observe,
                "sorted, then visited in that order, then counted only when observed");
        assertTrue(scan.contains("Comparator.comparingDouble(entity -> bot.distanceToSqr(entity))"),
                "candidates are ordered nearest-first without a creature-specific priority");
        assertFalse(scan.contains("isWardenType("));
        assertTrue(scan.contains("bot.distanceToSqr(entity)"), "then the nearest first");
        assertTrue(scan.contains("examined >= ENTITY_EXAMINE_CAP || accepted >= ENTITY_CANDIDATE_CAP"));
        assertFalse(scan.contains("examined++ >= ENTITY_CANDIDATE_CAP"),
                "an unobservable entity must not use up the evidence cap");
    }

    @Test
    void assistCodeOnlyReadsTheTaskManagerAndOnlyTheGateDoesSo() throws IOException {
        Pattern use = Pattern.compile("TaskManager\\.INSTANCE\\.(\\w+)");
        for (Path file : assistSources()) {
            String name = file.getFileName().toString();
            Matcher matcher = use.matcher(code(file));
            while (matcher.find()) {
                assertEquals("MiningAssistRuntime.java", name, "only the gate reads the task manager");
                assertEquals("activeOrigin", matcher.group(1), "and only the active origin");
            }
        }
    }

    @Test
    void assistCodeNeverAssignsTasksTouchesTheActionPackOrChats() throws IOException {
        for (Path file : assistSources()) {
            String source = code(file);
            String name = file.getFileName().toString();
            assertFalse(source.contains("TaskManager.INSTANCE.assign"), name);
            assertFalse(source.contains("IntentController"), name);
            assertFalse(source.contains("getActionPack"), name + " must not drive the bot");
            assertFalse(source.contains("displayClientMessage("), name + " must not chat (P0 is shadow only)");
            assertFalse(source.contains("gameMode"), name);
            assertFalse(Pattern.compile("\\.(pauseFor|abort|resumeFromPause|resumeUserIntent|pauseUserIntent)\\(")
                    .matcher(source).find(), name + " must not pause, abort or resume a task");
        }
    }

    // ---- the break peek ------------------------------------------------------------------------------

    @Test
    void breakPeekProvesEveryCellThroughOreScanBeforeLookingAtItAndUsesTheAnswer() throws IOException {
        String peek = code(ASSIST.resolve("BreakPeek.java"));
        assertTrue(peek.contains("OreScan.observe(bot, neighbour"));
        assertTrue(peek.contains("OreScan.observe(bot, broken"));
        assertFalse(peek.contains("getBlockState("));
        assertFalse(peek.contains("canObserveBlock"), "observation policy lives in OreScan, not here");
        assertEquals(2, count(peek, "OreScan.observe("), "the broken cell and the six neighbours, nothing else");
        assertEquals(2, count(peek, "OreScan.Observation observation = OreScan.observe("), "the answer is kept");
        assertEquals(2, count(peek, "observation != OreScan.Observation.OBSERVED_PRESENT"),
                "and checked before the state is used, both times");

        String one = method(peek, "private static void peekOne(");
        assertTrue(one.indexOf("observation != OreScan.Observation.OBSERVED_PRESENT")
                < one.indexOf("EvidenceFold.foldHit(state, neighbour"), "the neighbour is folded only when observed");
        String broken = method(peek, "private static void peekBrokenCell(");
        assertTrue(broken.indexOf("observation != OreScan.Observation.OBSERVED_PRESENT")
                < broken.indexOf("EvidenceFold.foldHit(state, broken"), "so is the broken cell");
    }

    @Test
    void theBreakHookIsNotProofOfTheBreakSoOnlyAnObservedOpenCellBecomesAirAndDug() throws IOException {
        String peek = code(ASSIST.resolve("BreakPeek.java"));
        String broken = method(peek, "private static void peekBrokenCell(");
        int observe = broken.indexOf("OreScan.observe(bot, broken");
        int fold = broken.indexOf("EvidenceFold.foldHit(state, broken");
        int mark = broken.indexOf("markOccupancy(occupancy, broken, observed);");
        int open = broken.indexOf("if (isOpenSpace(observed)) {");
        int dug = broken.indexOf("BotEdits.noteDug(bot, broken);");
        assertTrue(observe >= 0 && fold > observe && mark > fold && open > mark && dug > open,
                "observe, fold what is really there, mark it, and only then note a dug cell");
        assertFalse(peek.contains("occupancy.markAir(broken)"), "the cell is never assumed to be air");
        assertFalse(peek.contains("sightings().markGone(broken)"), "and no ledger entry is dropped on trust");
        assertEquals(1, count(peek, "BotEdits.noteDug("), "one place decides that a cell was dug");
        assertTrue(broken.contains("breaksUnconfirmed"), "a break that could not be confirmed is counted");

        String hooks = code(ASSIST.resolve("MiningAssistHooks.java"));
        assertFalse(hooks.contains("noteDug"), "the break hook only queues the cell for the peek");
        assertTrue(hooks.contains("state.pendingBreaks().offer(pos.asLong())"));
        assertTrue(hooks.contains("if (state != null) {"), "a bot without assist state stores nothing");
    }

    @Test
    void aBreakthroughNeedsOpenSpaceTheSweepHasNotAlreadySeenAndRestartsAreSpaced() throws IOException {
        String peek = code(ASSIST.resolve("BreakPeek.java"));
        String one = method(peek, "private static void peekOne(");
        int before = one.indexOf("int before = occupancy.get(neighbour);");
        int mark = one.indexOf("markOccupancy(occupancy, neighbour, observed);");
        int test = one.indexOf("if (open && !alreadySeenOpen(before) && !BotEdits.wasDug(bot, neighbour)) {");
        assertTrue(before > 0 && mark > before && test > mark,
                "the occupancy is read before the peek overwrites it");
        assertTrue(one.contains("state.requestBreakthrough(tick);"));
        assertFalse(one.contains("state.requestBreakthrough();"), "the unspaced restart is not used");
        assertTrue(code(ASSIST.resolve("MiningAssistState.java")).contains("MIN_BREAKTHROUGH_GAP_TICKS"));
    }

    // ---- the boundary set ---------------------------------------------------------------------------

    @Test
    void theWorldTouchingAdaptersUseTheObservableBoundaryForRealNotJustByImport() throws IOException {
        String boundary = read(Path.of(
                "src/test/java/io/github/zoyluo/minecraftai/mode/PrivilegedBoundarySourceTest.java"));
        for (String adapter : List.of("mining/assist/ViewSweeper.java", "mining/assist/PoiDetector.java")) {
            assertTrue(boundary.contains("\"" + adapter + "\""), adapter + " must be in the must-contain set");
        }
        // The boundary test only asks for the word ObservableWorldQuery, which an import satisfies; these are the calls.
        assertTrue(code(ASSIST.resolve("ViewSweeper.java")).contains("ObservableWorldQuery.castViewRay("));
        assertTrue(code(ASSIST.resolve("PoiDetector.java")).contains("ObservableWorldQuery.canObserveEntity("));
    }

    // ---- the hook sites -----------------------------------------------------------------------------

    @Test
    void miningControllerReportsABreakRightAfterTheCacheInvalidation() throws IOException {
        String source = read(MAIN.resolve("action/MiningController.java"));
        int invalidate = source.indexOf("AStarPathfinder.invalidateCache(\"block_break\");");
        int hook = source.indexOf("MiningAssistHooks.onBotBreak(player, pos);");
        assertTrue(invalidate > 0 && hook > invalidate);
        assertEquals(1, count(source, "MiningAssistHooks."));
        String between = source.substring(invalidate, hook);
        assertEquals(1, count(between, "\n"), "the hook is the very next statement");
    }

    @Test
    void buildActionReportsBothPlacementsAndKeepsItsObservationRule() throws IOException {
        String source = read(MAIN.resolve("action/BuildAction.java"));
        int place = source.indexOf("AStarPathfinder.invalidateCache(\"block_place\");");
        int placeNote = source.indexOf("BotEdits.notePlaced(player, destination);");
        assertTrue(place > 0 && placeNote > place);
        assertFalse(source.contains("block_place_fallback"), "the mid-air fallback placement is gone");
        assertEquals(1, count(source, "BotEdits.notePlaced("));
        assertFalse(source.contains("ObservableWorldQuery.canObserveBlock"),
                "face-center observation must not pre-empt the exact inset click sampler");
    }

    @Test
    void hooksStartWithTheStaticModeCheckAndCannotThrow() throws IOException {
        String hooks = read(ASSIST.resolve("MiningAssistHooks.java"));
        int method = hooks.indexOf("public static void onBotBreak(");
        assertTrue(method > 0);
        String body = hooks.substring(method);
        assertTrue(body.indexOf("MiningAssistRuntime.senseConfigured()") > 0);
        assertTrue(body.indexOf("MiningAssistRuntime.senseConfigured()") < body.indexOf("instanceof AIPlayerEntity"));
        assertTrue(body.contains("catch (RuntimeException"));

        String edits = read(ASSIST.resolve("BotEdits.java"));
        int placed = edits.indexOf("public static void notePlaced(ServerLevel world, BlockPos pos)");
        assertTrue(placed > 0);
        String placedBody = edits.substring(placed, edits.indexOf("public static void noteDug(", placed));
        assertTrue(placedBody.indexOf("senseConfigured()") > 0 && placedBody.contains("catch (RuntimeException"));
    }

    @Test
    void sidecarFileIoNeverRunsOnTheTickPath() throws IOException {
        String edits = read(ASSIST.resolve("BotEdits.java"));
        int snapshot = edits.indexOf("public static boolean snapshotIfDue(");
        int flush = edits.indexOf("public static void flushSync(", snapshot);
        assertTrue(snapshot > 0 && flush > snapshot);
        String body = edits.substring(snapshot, flush);
        assertTrue(body.contains("writerThread().execute("), "the write is handed to the daemon writer");
        assertFalse(body.contains("AtomicSnapshotFile.write(file"), "not written inline on the server thread");
        assertTrue(edits.contains("thread.setDaemon(true)"));
        assertTrue(edits.contains("AtomicSnapshotFile.write("));
    }

    // ---- the gate glue (I4) -----------------------------------------------------------------------------

    @Test
    void theGateReadsTheLiveOriginAuditSessionAndTpsAndFailsClosed() throws IOException {
        String runtime = code(ASSIST.resolve("MiningAssistRuntime.java")).replaceAll("\\s+", " ");
        assertTrue(runtime.contains(
                "String deny = resolveDeny(cfg, FORCED.contains(id), originKindOf(bot), "
                        + "MiningEvidenceAudit.hasSession(id), tpsDegraded(bot));"),
                "origin, audit session and TPS all reach the resolver from live lookups");
        assertTrue(runtime.contains(
                "return TaskManager.INSTANCE.activeOrigin(bot).map(origin -> origin.kind().name());"));
        assertTrue(runtime.contains("return originKind.map(AssistGate::isRealOrigin).orElse(false);"),
                "no origin is not a real origin");
        assertFalse(runtime.contains(".orElse(true)"));
        assertTrue(runtime.contains(
                "return AssistGate.denyReason(cfg.mode(), cfg.harnessOff(), forced, originIsReal(originKind), "
                        + "auditSession, tpsDegraded);"));
        assertEquals(1, count(runtime, "AssistGate.denyReason("), "the resolver is the only path to a verdict");
        assertTrue(runtime.contains("return originIsReal(originKind) && !auditSession;"));
        assertTrue(runtime.contains(
                "stillOpen(originKindOf(bot), MiningEvidenceAudit.hasSession(id))"),
                "a cached open verdict is re-checked against the live origin and audit session");
        assertTrue(runtime.contains("TpsGuard.INSTANCE.snapshot(server).degraded()"));
        assertEquals(2, count(runtime, "MiningEvidenceAudit.hasSession(id)"));
    }

    // ---- P1 (INTEGRATOR-1): the two new guarded files must stay under this scan ----------------------

    @Test
    void theP1DetourFilesAreInTheGuardedSetAndExistOnDisk() {
        for (String relative : List.of("task/OreDigDetourEngine.java", "task/DetourSafetyGate.java")) {
            assertTrue(GUARDED_FILES.contains(relative), relative + " must be a guarded file");
            assertTrue(Files.exists(MAIN.resolve(relative)), relative + " must exist");
        }
    }

    @Test
    void theGateReturnsFirstWhileTheSwitchIsOffInEveryEntryPoint() throws IOException {
        String runtime = code(ASSIST.resolve("MiningAssistRuntime.java"));
        for (String signature : List.of(
                "public static boolean enabledFor(AIPlayerEntity bot) {",
                "public static boolean enabledFor(AIPlayerEntity bot, int serverTick) {",
                "public static void beginTick() {",
                "public static void endTick(int serverTicks) {")) {
            String body = method(runtime, signature);
            int check = body.indexOf("if (!senseAny) {");
            assertTrue(check > 0 && check <= signature.length() + 20,
                    signature + " must check the switch as its first statement");
        }
    }

    private static int count(String text, String needle) {
        Matcher matcher = Pattern.compile(Pattern.quote(needle)).matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}

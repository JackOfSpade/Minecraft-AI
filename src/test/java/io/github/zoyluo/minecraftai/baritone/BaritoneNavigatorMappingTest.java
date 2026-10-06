package io.github.zoyluo.minecraftai.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import baritone.api.utils.PathCalculationResult;
import io.github.zoyluo.minecraftai.navigation.NavRoute;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The pure mappings of the Baritone navigator: request permissions to the per-bot policy, search result to admission answer. */
final class BaritoneNavigatorMappingTest {
    @Test
    void routePermissionsMapOntoTheExistingPolicyPresets() {
        assertEquals(BaritonePolicy.UNRESTRICTED, BaritoneNavigator.policyOf(new NavRoute.Options(true, true, false)));
        assertEquals(BaritonePolicy.NO_PLACING, BaritoneNavigator.policyOf(new NavRoute.Options(true, false, false)));
        assertEquals(BaritonePolicy.NO_BREAKING, BaritoneNavigator.policyOf(new NavRoute.Options(false, true, false)));
        assertEquals(BaritonePolicy.WALK_ONLY, BaritoneNavigator.policyOf(NavRoute.Options.WALK_ONLY));
        assertEquals(BaritonePolicy.WALK_ONLY, BaritoneNavigator.policyOf(NavRoute.Options.SWIM), "swimming is not breaking or placing");
    }

    @Test
    void waterTraversalAndExactWaterGoalsAreSeparateRoutePermissions() {
        NavRoute.Options ordinaryWetRoute = new NavRoute.Options(false, false, true);
        assertTrue(ordinaryWetRoute.allowWater());
        assertFalse(ordinaryWetRoute.exactWaterGoal(), "water traversal alone must not change a BLOCK goal into a water goal");
        assertTrue(NavRoute.Options.SWIM.allowWater() && !NavRoute.Options.SWIM.exactWaterGoal(),
                "a normal swim route carries traversal permission without changing its destination semantics");
        assertTrue(NavRoute.Options.EXACT_SWIM.allowWater() && NavRoute.Options.EXACT_SWIM.exactWaterGoal(),
                "the dedicated exact-water preset carries both permissions");
    }

    @Test
    void explicitPillarColumnPermitsOnlyTheInitiallyProvenVerticalRange() {
        NavRoute.PillarPlacementColumn column = new NavRoute.PillarPlacementColumn(
                new net.minecraft.core.BlockPos(4, 64, -2), 67);
        assertTrue(column.allows(new net.minecraft.core.BlockPos(4, 64, -2)));
        assertTrue(column.allows(new net.minecraft.core.BlockPos(4, 67, -2)));
        assertFalse(column.allows(new net.minecraft.core.BlockPos(4, 63, -2)),
                "a support below the original proven base is never available to the pillar");
        assertFalse(column.allows(new net.minecraft.core.BlockPos(4, 68, -2)),
                "the feet goal and its headroom remain empty");
        assertFalse(column.allows(new net.minecraft.core.BlockPos(5, 65, -2)),
                "a pillar route cannot turn into a side bridge");
    }

    @Test
    void ordinarySwimRoutesKeepDryGoalResolutionAndExactWaterRemainsExplicit() throws IOException {
        String actionPack = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/action/ActionPack.java"));
        String swim = method(actionPack, "public ActionResult startSwimRouteTo(BlockPos goal) {");
        assertTrue(swim.contains("NavRoute.Options.SWIM"), "the swim entry must select water traversal");
        assertFalse(swim.contains("NavRoute.Options.EXACT_SWIM"),
                "a route across water to land must keep dry-goal resolution");

        String walk = method(actionPack, "public ActionResult startWalkTo(Vec3 target, double arrivalThreshold) {");
        String approach = method(actionPack, "public ActionResult startApproachTo(BlockPos target, int radius, boolean refresh, boolean allowBreak) {");
        assertFalse(walk.contains("NavRoute.Options.SWIM") || approach.contains("NavRoute.Options.SWIM"),
                "ordinary wet walk/approach requests only receive traversal permission");

        String navigator = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/baritone/BaritoneNavigator.java"));
        String goalOf = method(navigator, "static Goal goalOf(AIPlayerEntity bot, NavRoute route) {");
        assertTrue(goalOf.contains("route.options().exactWaterGoal()"));
        assertFalse(goalOf.contains("route.options().allowWater()"),
                "goal resolution must not turn every water-capable route into an exact water goal");

        String fence = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/baritone/ObservedNavigationFence.java"));
        String admit = method(fence, "public static Capture admit(");
        assertTrue(admit.contains("boolean exactWaterGoal = route.options().exactWaterGoal()")
                        && admit.contains("if (exactWaterGoal)"),
                "only dedicated exact-water goals may use water-cell admission instead of dry-stance admission");

        String context = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/baritone/ServerPlayerContext.java"));
        String allowed = method(context, "private boolean navigationStateAllowed(BlockState state) {");
        assertTrue(allowed.contains("!state.getFluidState().is(FluidTags.LAVA)")
                        && allowed.contains("waterAllowed || !state.getFluidState().is(FluidTags.WATER)"),
                "water permission must never make a lava cell navigable");

    }

    @Test
    void rememberedTargetRevalidationProvesLineOfSightBeforeComparingTheWholeState() throws IOException {
        String fence = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/baritone/ObservedNavigationFence.java"));
        String revalidate = method(fence, "public static boolean revalidateRememberedTarget(");
        int liveProof = revalidate.indexOf("ObservableWorldQuery.canObserveCell(bot, route.target())");
        int liveRead = revalidate.indexOf("bot.level().getBlockState(route.target())");
        assertTrue(liveProof >= 0 && liveRead > liveProof, "the LOS proof must precede the one allowed live target read");
        assertTrue(revalidate.contains("remembered.equals(bot.level().getBlockState(route.target()))"),
                "doors, fluid levels, and other state changes must invalidate remembered navigation evidence");
        assertFalse(revalidate.contains(".getBlock() == remembered.getBlock()"),
                "block identity alone is not enough to revalidate a navigation state");
    }

    @Test
    void nearAdmissionRequiresACurrentlyVisibleTargetButRunAwayRemainsDirectionOnly() throws IOException {
        String fence = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/baritone/ObservedNavigationFence.java"));
        String admit = method(fence, "public static Capture admit(");
        int near = admit.indexOf("if (route.shape() == NavRoute.Shape.NEAR)");
        int liveTargetGate = admit.indexOf("if (!liveTarget)", near);
        int pursuit = admit.indexOf("else if (route.shape() == NavRoute.Shape.DIRECTIONAL_PURSUIT)", near);
        int runAway = admit.indexOf("else if (route.shape() == NavRoute.Shape.RUN_AWAY)", near);
        assertTrue(near >= 0 && liveTargetGate > near && pursuit > liveTargetGate && runAway > pursuit,
                "NEAR must reject an unobserved coordinate before its corridor is admitted");
        String pursuitBranch = admit.substring(pursuit, runAway);
        assertTrue(pursuitBranch.contains("pursuitObservationPoint")
                        && pursuitBranch.contains("nearestDirectionalPursuitStance")
                        && pursuitBranch.contains("route.setResolvedGoal(hop)"),
                "directional pursuit must resolve its remote heading to one observed local stance");
        assertFalse(pursuitBranch.contains("!liveTarget"),
                "directional pursuit deliberately does not require seeing the remote owner cell");
        assertFalse(admit.substring(runAway).contains("!liveTarget"),
                "RUN_AWAY must remain a direction-only admission");
    }

    @Test
    void visibleStandingEnvelopesProveSupportByItsTopSurfaceRatherThanReadingPastAFlatFloor() throws IOException {
        String fence = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/baritone/ObservedNavigationFence.java"));
        String envelope = method(fence, "private static void observeStandingEnvelope(");
        assertTrue(envelope.contains("if (y == -1)") && envelope.contains("observeFloorTopIfVisible(bot, pos, throughFluids, observed, tick)"),
                "a visible distant stance must prove its support with a first-hit top-surface ray");
        assertFalse(envelope.contains("bot.level().getBlockState(pos)"),
                "standing-envelope admission must not fill support cells from raw world reads");

        String floorProof = method(fence, "private static void observeFloorTopIfVisible(");
        assertTrue(floorProof.contains("!throughFluids && !view.isUnknown() && !view.hit()")
                        && floorProof.contains("mayReplaceFloorEvidenceWithAir")
                        && floorProof.contains("observeRouteCellIfVisible(bot, floor, throughFluids, observed, tick)")
                        && floorProof.contains("put(observed, floor.asLong(), AIR, tick)"),
                "a visibly empty gap floor may be modeled as AIR only after its exact dry eye ray reaches the cell and cannot prove a partial support, without overwriting a prior non-colliding traversal proof");
        String replacementRule = method(fence, "static boolean mayReplaceFloorEvidenceWithAir(");
        assertTrue(replacementRule.contains("prior == null || prior.isAir()"),
                "a collider miss may replace only absent/AIR evidence; a proven vine or rail must remain known");
        assertFalse(floorProof.contains("bot.level().getBlockState(floor)"),
                "a bridge destination must remain a ray-proven fact, never a raw floor read");
    }

    @Test
    void waterTraversalUsesOnlyFluidTransparentPlayerViewsForItsVisibleShoreStance() throws IOException {
        String fence = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/baritone/ObservedNavigationFence.java"));
        String admit = method(fence, "public static Capture admit(");
        assertTrue(admit.contains("observeStandingEnvelope(bot, route.target(), waterTraversal, observed, tick)"),
                "a route allowed to swim must prove a visible dry shore through water, rather than treating water as an opaque wall");

        String floorProof = method(fence, "private static void observeFloorTopIfVisible(");
        assertTrue(floorProof.contains("ObservableWorldQuery.castViewRayThroughFluids")
                        && floorProof.contains("ObservableWorldQuery.castViewRay("),
                "the floor proof must choose Fluid.NONE only for an explicitly water-capable route");
        assertFalse(floorProof.contains("bot.level().getBlockState(floor)"),
                "a shore support state must come from the first ray hit, never a raw floor read");

        String waterEnvelope = method(fence, "private static void observeWaterEnvelope(");
        assertTrue(waterEnvelope.contains("NAVIGATION_HEADROOM") && waterEnvelope.contains("observeRouteCellIfVisible(bot"),
                "a swimmer's visible corridor must include the individually proven headroom Baritone validates");
    }

    @Test
    void visibleVerticalMovementCapturesOnlyRayProvenHeadroomAndColumns() throws IOException {
        String fence = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/baritone/ObservedNavigationFence.java"));

        String dryStance = method(fence, "private static void observeVisibleStance(");
        assertTrue(dryStance.contains("for (int y = 0; y <= NAVIGATION_HEADROOM; y++)")
                        && dryStance.contains("observeCellIfVisible(bot, feet.above(y), observed, tick)"),
                "visible dry parkour/climb stances must prove every headroom cell Baritone validates");
        assertFalse(dryStance.contains("bot.level().getBlockState"),
                "dry headroom must be sight-proven before any state read");

        String corridors = method(fence, "private static void observeVisibleCorridors(");
        assertTrue(corridors.contains("if (from.getY() != to.getY())")
                        && corridors.contains("observeVisibleElevationColumns(bot, from, to, throughFluids, observed, tick)")
                        && corridors.contains("if (to.getY() < from.getY())")
                        && corridors.contains("observeVisibleDescentColumn(bot, from, to, throughFluids, observed, tick)"),
                "elevation and descent evidence must be requested only for the matching observed route shape");
        assertTrue(corridors.contains("int halfWidth = corridorHalfWidth()")
                        && corridors.contains("strip <= halfWidth"),
                "the ray-proven corridor must use the current capability-scoped lateral envelope");
        String corridorWidth = method(fence, "private static int corridorHalfWidth()");
        assertTrue(corridorWidth.contains("mobAvoidanceEnabled()")
                        && corridorWidth.contains("BaritoneSettings.MOB_AVOIDANCE_RADIUS")
                        && corridorWidth.contains("BASE_CORRIDOR_HALF_WIDTH"),
                "a seen hostile receives a full avoidance-radius detour only while the config switch is on");

        String elevation = method(fence, "private static void observeVisibleElevationColumns(");
        assertTrue(elevation.contains("from.getX(), from.getZ()")
                        && elevation.contains("to.getX(), to.getZ()")
                        && elevation.contains("NAVIGATION_HEADROOM")
                        && elevation.contains("observeVisibleColumn(bot"),
                "climbing must prove both visible endpoint columns rather than interpolating hidden cells");

        String admission = method(fence, "public static Capture admit(");
        assertTrue(admission.contains("boolean climbableGoal = isObservedClimbable(candidate, route.target())")
                        && admission.contains("climbableGoal ? route.target().immutable()")
                        && admission.contains("observeVisibleCorridors(bot, feet, stance"),
                "a ray-proven vine/ladder goal must remain its own Baritone endpoint while the route corridor is still proven");
        String endpoint = method(fence, "public BlockPos nearestObservedStance(");
        assertTrue(endpoint.contains("isObservedClimbable(this, target)") && endpoint.contains("target.immutable()"),
                "goal resolution must retain an observed climbable endpoint instead of snapping it to a dry neighbouring floor");

        String descent = method(fence, "private static void observeVisibleDescentColumn(");
        assertTrue(descent.contains("Integer.signum(dx)")
                        && descent.contains("Integer.signum(dz)")
                        && descent.contains("throughFluids, observed, tick")
                        && descent.contains("new BlockPos(drop.getX(), to.getY(), drop.getZ())")
                        && descent.contains("observeVisibleCorridors(bot"),
                "a descent must prove the first directional drop column and its visible landing corridor through perception-approved rays");

        String column = method(fence, "private static void observeVisibleColumn(");
        assertTrue(column.contains("feetY - radius") && column.contains("feetY + radius")
                        && column.contains("observeRouteCellIfVisible(bot, new BlockPos(x, y, z), throughFluids, observed, tick)"),
                "vertical evidence must stay perception-bounded and pass each cell through the observation gate");
        assertFalse(column.contains("bot.level().getBlockState"),
                "vertical evidence must never bulk-read a world column");

        String routeCell = method(fence, "private static boolean observeRouteCellIfVisible(");
        assertTrue(routeCell.contains("ObservableWorldQuery.canObserveCellThroughFluids")
                        && routeCell.contains("ObservableWorldQuery.canObserveCell(bot, pos)")
                        && routeCell.contains("observeRouteOutlineIfVisible(bot, pos, throughFluids, observed, tick)"),
                "a non-colliding climbing block must have either a cell proof or an exact outline proof");
        String outline = method(fence, "private static boolean observeRouteOutlineIfVisible(");
        assertTrue(outline.contains("ObservableWorldQuery.ViewShape.OUTLINE")
                        && outline.contains("!pos.equals(view.pos())")
                        && outline.contains("view.state()"),
                "a visible vine or rail must be admitted only when its own outline is the first ray hit");
        assertFalse(outline.contains("bot.level().getBlockState"),
                "outline-backed route evidence must use the first-hit state rather than a raw target read");
    }

    @Test
    void aPartialPathThatEndedEarlyTowardsALoadedGoalIsUnreachable() {
        PathCalculationResult.Type partial = PathCalculationResult.Type.SUCCESS_SEGMENT;
        assertEquals(true, BaritoneNavigator.exhaustedPartial(partial, 12L, 40L, true), "ran out of places to look long before the budget");
        assertEquals(false, BaritoneNavigator.exhaustedPartial(partial, 40L, 40L, true), "cut off by the budget: keep going");
        assertEquals(false, BaritoneNavigator.exhaustedPartial(partial, 36L, 40L, true), "within the timer slack: keep going");
        assertEquals(false, BaritoneNavigator.exhaustedPartial(partial, 5L, 40L, false), "the goal is in an unloaded column: a partial path is all there can be");
        assertEquals(false, BaritoneNavigator.exhaustedPartial(PathCalculationResult.Type.SUCCESS_TO_GOAL, 5L, 40L, true));
        assertEquals(false, BaritoneNavigator.exhaustedPartial(PathCalculationResult.Type.FAILURE, 5L, 40L, true), "failures are refused by their own rule");
    }

    @Test
    void dryNoPlaceRoutesRefusePartialAdmissionBeforeAnyInputCanReachAGap() {
        NavRoute noPlace = new NavRoute(NavRoute.Shape.NEAR, new net.minecraft.core.BlockPos(10, 64, 0), 1,
                new NavRoute.Options(true, false, false), "no_place", 0);
        BaritonePlanner.Plan partial = new BaritonePlanner.Plan(
                new PathCalculationResult(PathCalculationResult.Type.SUCCESS_SEGMENT), 1L, 0L, java.util.List.of());
        assertEquals("navigation_observed_corridor_unavailable",
                BaritoneNavigator.observedAdmissionSafetyFailure(noPlace, partial),
                "NO_PLACING may use an observed break in a complete path, never an edge-only partial segment");

        NavRoute placement = new NavRoute(NavRoute.Shape.NEAR, new net.minecraft.core.BlockPos(10, 64, 0), 1,
                new NavRoute.Options(false, true, false), "placement", 0);
        assertNull(BaritoneNavigator.observedAdmissionSafetyFailure(placement, partial),
                "a construction route has separately proven observed placement cells and must not be folded into the no-place guard");
    }

    @Test
    void directionalPursuitPermitsOnlyABoundedObservedPartialAndRequiresALocalGoal() throws IOException {
        NavRoute pursuit = new NavRoute(NavRoute.Shape.DIRECTIONAL_PURSUIT,
                new net.minecraft.core.BlockPos(40, 64, 0), 12, NavRoute.Options.WALK_ONLY, "directional_pursuit", 0);
        BaritonePlanner.Plan partial = new BaritonePlanner.Plan(
                new PathCalculationResult(PathCalculationResult.Type.SUCCESS_SEGMENT), 1L, 0L, java.util.List.of());
        assertNull(BaritoneNavigator.observedAdmissionSafetyFailure(pursuit, partial),
                "a local directional hop may use a partial Baritone segment inside its immutable observed fence");

        String navigator = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/baritone/BaritoneNavigator.java"));
        String goalOf = method(navigator, "static Goal goalOf(AIPlayerEntity bot, NavRoute route) {");
        assertTrue(goalOf.contains("case DIRECTIONAL_PURSUIT")
                        && goalOf.contains("BlockPos hop = route.resolvedGoal()")
                        && goalOf.contains("new GoalBlock(hop)")
                        && goalOf.contains("directional pursuit has no observed hop"),
                "the remote heading must never become a Baritone goal when admission omitted a local stance");
        String completion = method(navigator, "private static boolean requiresCompleteObservedGoal(");
        assertTrue(completion.contains("route.shape() != NavRoute.Shape.DIRECTIONAL_PURSUIT"),
                "the exception is shape-specific; ordinary NEAR/BLOCK routes retain their complete-corridor rule");

        String fence = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/baritone/ObservedNavigationFence.java"));
        String stance = method(fence, "private static BlockPos nearestDirectionalPursuitStance(");
        assertTrue(stance.contains("distanceSq > maxHopSq") && stance.contains("forward > targetDistance"),
                "a hop stays within its local bound and cannot pass the requested standoff point");
        assertTrue(stance.contains("ObservedGraphSearch.search(origin, new SnapshotEnvironment(fence))")
                        && stance.contains("allowBreakFallback"),
                "a walk-only pursuit hop must be graph-reachable; a breakable route may retain a secondary observed fallback");
        assertFalse(stance.contains("bot.level()") || stance.contains("getChunk"),
                "choosing a pursuit stance must use only the frozen observation fence, never load or inspect remote terrain");
    }

    @Test
    void ownerFollowIsOwnerBoundWalkOnlyAndCannotFallThroughToBaritoneCache() throws IOException {
        NavRoute ownerRoute = NavRoute.ownerFollow(java.util.UUID.randomUUID(), new net.minecraft.core.BlockPos(40, 64, 0),
                3, "owner_follow", 0);
        assertEquals(NavRoute.Shape.OWNER_FOLLOW, ownerRoute.shape());
        assertEquals(NavRoute.Options.WALK_ONLY, ownerRoute.options(),
                "the only direct-coordinate shape is dry walking with no breaking or placing");
        assertTrue(ownerRoute.ownerUuid() != null);

        String pack = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/action/ActionPack.java"));
        String ownerStart = method(pack, "public ActionResult startOwnerFollowTo(UUID ownerUuid, BlockPos target, int radius, boolean refresh) {");
        assertTrue(ownerStart.contains("AIPlayerManager.INSTANCE.ownerOf(player).filter(ownerUuid::equals).isEmpty()")
                        && ownerStart.contains("NavRoute.ownerFollow(ownerUuid, target")
                        && ownerStart.contains("NavEngineSelector.attempt(player.getUUID(), \"owner_follow\""),
                "the public route entry verifies ownership and delegates only to the Baritone seam");

        String navigator = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/baritone/BaritoneNavigator.java"));
        String start = method(navigator, "public static Admission start(AIPlayerEntity bot, NavRoute route, boolean admit) {");
        assertTrue(start.contains("route.shape() == NavRoute.Shape.OWNER_FOLLOW && !isAuthorizedOwnerFollow(bot, route)"),
                "the navigator repeats the owner check at the mutation boundary");
        String guard = method(navigator, "private static boolean isAuthorizedOwnerFollow(");
        assertTrue(guard.contains("route.ownerUuid() == null")
                        && guard.contains("route.options().allowBreak() || route.options().allowPlace()")
                        && guard.contains("route.options().allowWater()")
                        && guard.contains("owner.blockPosition().equals(route.target())"),
                "a direct goal must be this bot's current owner's current cell and remain walk-only");
        String principal = method(navigator, "private static boolean isCurrentOwnerFollow(");
        assertTrue(principal.contains("currentOwnerFollowTarget(bot, route) != null"),
                "a running owner route must retain its owner/dimension authority without treating ordinary movement as revocation");
        String refresh = method(navigator, "public static boolean refreshObservationFence(AIPlayerEntity bot) {");
        assertTrue(refresh.contains("route.shape() == NavRoute.Shape.OWNER_FOLLOW && !isCurrentOwnerFollow(bot, route)")
                        && refresh.contains("registry.revokeObservation(bot, \"owner_follow_authority_lost\", false)"),
                "an ownership, disconnect, or dimension change revokes the direct snapshot before the next Baritone PRE tick");
        String goalOf = method(navigator, "static Goal goalOf(AIPlayerEntity bot, NavRoute route) {");
        assertTrue(goalOf.contains("case OWNER_FOLLOW -> new GoalNear(route.target(), route.radius())"),
                "the approved owner coordinate maps directly to GoalNear");
        String completion = method(navigator, "private static boolean requiresCompleteObservedGoal(");
        assertTrue(completion.contains("route.shape() != NavRoute.Shape.OWNER_FOLLOW"),
                "only the dedicated owner shape may continue through a loaded-chunk segment");

        String context = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/baritone/ServerPlayerContext.java"));
        String allow = method(context, "public boolean allowNavigationCell(int x, int y, int z) {");
        String state = method(context, "public BlockState navigationCellState(int x, int y, int z) {");
        assertTrue(allow.contains("BlockState state = ownerSnapshot.stateAt(x, y, z);")
                        && allow.contains("return navigationStateAllowed(state);")
                        && state.contains("BlockState state = ownerSnapshot.stateAt(x, y, z);")
                        && state.contains("state == null || !navigationStateAllowed(state)"),
                "an allowed direct cell is paired with a snapshot state; a dry water or race/miss is virtual bedrock, never cache fallback");
        assertTrue(context.contains("ownerFollowSnapshot = null;"),
                "the direct snapshot is cleared at both route and lifecycle boundaries");

        String snapshot = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/baritone/LoadedChunkSnapshot.java"));
        String full = method(snapshot, "public boolean hasFullChunk(int chunkX, int chunkZ) {");
        String stateAt = method(snapshot, "public BlockState stateAt(int x, int y, int z) {");
        assertTrue(full.contains("instanceof LevelChunk chunk && !chunk.isEmpty()")
                        && stateAt.contains("if (!hasCell(x, y, z))")
                        && stateAt.contains("if (!(access instanceof LevelChunk chunk) || chunk.isEmpty())"),
                "the loaded-cell predicate and state lookup agree: an empty/missing chunk cannot reopen a provider read");
    }

    @Test
    void seenHostileAdmissionRetriesOnlyTheFrozenStrictPartialRoute() {
        NavRoute dry = new NavRoute(NavRoute.Shape.NEAR, new net.minecraft.core.BlockPos(10, 64, 0), 1,
                NavRoute.Options.WALK_ONLY, "seen_hostile", 0);
        assertTrue(BaritoneNavigator.needsObservedHostileAdmissionRetry(
                        dry, PathCalculationResult.Type.SUCCESS_SEGMENT, 1, 40L),
                "a visible hostile gets one larger search over the existing evidence fence");
        assertFalse(BaritoneNavigator.needsObservedHostileAdmissionRetry(
                        dry, PathCalculationResult.Type.SUCCESS_SEGMENT, 0, 40L),
                "without a perception-filtered hostile the normal short admission budget remains in force");
        assertFalse(BaritoneNavigator.needsObservedHostileAdmissionRetry(
                        dry, PathCalculationResult.Type.SUCCESS_TO_GOAL, 1, 40L),
                "a complete first search never retries");
        assertFalse(BaritoneNavigator.needsObservedHostileAdmissionRetry(
                        dry, PathCalculationResult.Type.SUCCESS_SEGMENT, 1, 12L),
                "a genuinely exhausted short partial must refuse without spending the retry budget");
        NavRoute swim = new NavRoute(NavRoute.Shape.NEAR, new net.minecraft.core.BlockPos(10, 64, 0), 1,
                NavRoute.Options.SWIM, "swim", 0);
        NavRoute placing = new NavRoute(NavRoute.Shape.NEAR, new net.minecraft.core.BlockPos(10, 64, 0), 1,
                new NavRoute.Options(false, true, false), "placing", 0);
        assertFalse(BaritoneNavigator.needsObservedHostileAdmissionRetry(
                        swim, PathCalculationResult.Type.SUCCESS_SEGMENT, 1, 40L));
        assertFalse(BaritoneNavigator.needsObservedHostileAdmissionRetry(
                        placing, PathCalculationResult.Type.SUCCESS_SEGMENT, 1, 40L));
    }

    @Test
    void observationFenceUsesCollisionSupportHazardRejectionAndTrustedOwnPlacementOnly() throws IOException {
        String fence = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/baritone/ObservedNavigationFence.java"));
        String environment = method(fence, "private static final class SnapshotEnvironment");
        assertTrue(environment.contains("getCollisionShape(EmptyBlockGetter.INSTANCE, pos)")
                        && environment.contains("shape.max(Direction.Axis.Y) > 0.0D"),
                "a non-air decoration is not a valid navigation floor without collision support");
        assertTrue(environment.contains("ObservedGraphSearch.LAVA_CLEARANCE") && environment.contains("FluidTags.LAVA")
                        && environment.contains("Standability.isDangerous"),
                "known lava and trap cells must be excluded from the dry observed graph");

        String actionResult = method(fence, "ObservedNavigationFence withTrustedActionResult(");
        assertTrue(actionResult.contains("!allows(pos.getX(), pos.getY(), pos.getZ())"),
                "a successful placement may update an already observed cell but must never expand the fence");
        String registry = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/baritone/BaritoneRegistry.java"));
        assertTrue(registry.contains("recordObservedPlacement") && registry.contains("withTrustedActionResult"));
        String controller = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/baritone/ServerPlayerController.java"));
        assertTrue(controller.contains("if (use.placed())") && controller.contains("recordObservedPlacement"),
                "only a confirmed own placement may publish an action-result terrain fact");
    }

    @Test
    void visiblePillarAndAsyncReplansKeepTheirNarrowSafetyProofs() throws IOException {
        String fence = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/baritone/ObservedNavigationFence.java"));
        String admit = method(fence, "public static Capture admit(");
        assertTrue(admit.contains("route.options().allowPlace() && liveTarget && route.returnAnchor() == null")
                        && admit.contains("observePillarColumn") && admit.contains("isObservedPillarColumn"),
                "a pillar goal needs a live target, no return contract, and an explicitly proven vertical column");
        String column = method(fence, "private static boolean isObservedPillarColumn(");
        assertTrue(column.contains("state == null || !state.isAir()") && column.contains("SnapshotEnvironment.isStandable"),
                "the pillar column must be observed air over a real collision-bearing base");

        String navigator = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/baritone/BaritoneNavigator.java"));
        String goalOf = method(navigator, "static Goal goalOf(AIPlayerEntity bot, NavRoute route) {");
        assertTrue(goalOf.contains("route.observedPillarGoal()"),
                "only an admitted visible column may map an unsupported BLOCK target to a pillar goal");
        String start = method(navigator, "public static Admission start(AIPlayerEntity bot, NavRoute route, boolean admit) {");
        assertTrue(start.contains("route.setPillarPlacementColumn(observed.pillarBase())"),
                "the placement gate must receive the exact base that admission proved, not a later bot position");
        String route = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/navigation/NavRoute.java"));
        String placementColumn = method(route, "public record PillarPlacementColumn(");
        assertTrue(placementColumn.contains("destination.getY() >= base.getY()")
                        && placementColumn.contains("destination.getY() <= lastPlacementY"),
                "an explicit pillar may fill only from its proven base through the cell below its goal");
        String policy = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/baritone/BaritoneBreakPlacePolicy.java"));
        String click = method(policy, "public static Decision checkClickBlock(");
        assertTrue(click.contains("allowsPillarPlacementDestination(bot, destination)")
                        && click.contains("pillar_destination_outside_column"),
                "the placement interceptor must reject bridge/side placements outside an explicit pillar column");
        String safety = method(navigator, "static String observedPathSafetyFailure(");
        assertTrue(safety.contains("movement.getSrc().getY() - movement.getDest().getY() > safeFall")
                        && safety.contains("navigation_observed_corridor_unavailable"),
                "dry movement lists must obey maxSafeFall and no-place completion before execution");
        String observedHostiles = method(navigator, "private static int observedHostileCount(");
        assertTrue(observedHostiles.contains("baritone.getPlayerContext().entities()")
                        && observedHostiles.contains("entity instanceof Mob mob && mob instanceof Enemy && mob.isAlive()")
                        && observedHostiles.contains("mobAvoidanceEnabled()"),
                "the bounded retry may consider only the already perception-filtered hostile list while avoidance is enabled");
        assertFalse(observedHostiles.contains("getEntitiesOfClass") || observedHostiles.contains("level().get"),
                "retry selection must not scan the live world for hostiles");
        String driver = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/baritone/BaritoneDriver.java"));
        assertTrue(driver.contains("activeObservedPathSafetyFailure(bot, baritone)")
                        && driver.contains("baritone_observed_path_refused"),
                "a worker replan is checked after PRE and before the input bridge");
    }

    @Test
    void theInlineSearchResultBecomesTheLegacyAnswer() {
        assertNull(BaritoneNavigator.admissionFailure(PathCalculationResult.Type.SUCCESS_TO_GOAL, 3L, 100L));
        assertNull(BaritoneNavigator.admissionFailure(PathCalculationResult.Type.SUCCESS_SEGMENT, 3L, 100L),
                "a partial path gets the bot moving: Baritone's answer to the legacy straight-line fallback");
        assertEquals("pathfinding_failed: GOAL_UNREACHABLE", BaritoneNavigator.admissionFailure(PathCalculationResult.Type.FAILURE, 12L, 100L));
        assertNull(BaritoneNavigator.admissionFailure(PathCalculationResult.Type.FAILURE, 100L, 100L),
                "a search that used its whole budget for nothing proves nothing (cold start, busy server): the async search decides");
        assertEquals("pathfinding_failed: GOAL_UNREACHABLE", BaritoneNavigator.admissionFailure(PathCalculationResult.Type.CANCELLATION, 1L, 100L));
        assertEquals("pathfinding_failed: baritone_exception", BaritoneNavigator.admissionFailure(PathCalculationResult.Type.EXCEPTION, 1L, 100L));
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
        throw new AssertionError(signature + " must close");
    }
}

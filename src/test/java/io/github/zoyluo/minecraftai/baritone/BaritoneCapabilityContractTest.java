package io.github.zoyluo.minecraftai.baritone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Pins the rules around the Baritone capability switches ({@code nav.baritone.*}; the behavior is proven by
 * {@code BaritoneCapabilityGameTests}): the switches come from the config at every plan and never from a hand-edited settings
 * file, the only item use without a block is the bucket of a water-bucket fall inside that movement, chunk caching stays off, and
 * mob avoidance can only see what the bot observes.
 */
class BaritoneCapabilityContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");
    private static final Path BARITONE_PATCHES = Path.of("tools/baritone/patches");

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    private static String readPatch(String filename) throws IOException {
        return Files.readString(BARITONE_PATCHES.resolve(filename));
    }

    @Test
    void theSwitchesAreDerivedFromTheConfigAtEveryPlanAndNotFixed() throws IOException {
        String settings = read("baritone/BaritoneSettings.java");
        int fixed = settings.indexOf("public static void applyFixed()");
        int limits = settings.indexOf("public static void applyNavLimits()");
        assertTrue(fixed >= 0 && limits > fixed);
        String fixedPart = settings.substring(fixed, limits);
        String limitsPart = settings.substring(limits);
        for (String setting : new String[] {"allowParkour", "allowParkourPlace", "allowParkourAscend", "allowWaterBucketFall", "allowVines", "avoidance"}) {
            assertFalse(fixedPart.contains("settings." + setting + ".value"), setting + " must not be fixed: it follows nav.baritone");
            assertTrue(limitsPart.contains("settings." + setting + ".value ="), setting + " follows the config at every plan");
        }
        assertTrue(fixedPart.contains("settings.chunkCaching.value = false;"), "the world cache stays off (it would remember terrain the bot never saw)");
        assertTrue(fixedPart.contains("settings.mobSpawnerAvoidanceCoefficient.value = 1.0D;"), "spawner avoidance needs the world cache");
        assertTrue(limitsPart.contains("int configuredSafeFall = Math.max(1, nav.maxSafeFall());")
                        && limitsPart.contains("Math.min(configuredSafeFall, FallSafety.maxNonLethalFall(bot))"),
                "the configured conservative dry-fall ceiling must cap a full-health bot's non-lethal body limit");
        assertTrue(limitsPart.contains("caps.waterBucketFallEnabled() ? Math.max(maxSafeFall, caps.maxBucketFall()) : maxSafeFall"),
                "without the switch the bucket limit is the safe fall");
    }

    @Test
    void routeCapabilitiesAreCandidateScopedThenCommittedWithoutLeakingAcrossWorkers() throws IOException {
        String context = read("baritone/ServerPlayerContext.java");
        String driver = read("baritone/BaritoneDriver.java");
        String settings = read("baritone/BaritoneSettings.java");
        String navigator = read("baritone/BaritoneNavigator.java");
        String planner = read("baritone/BaritonePlanner.java");
        String fence = read("baritone/ObservedNavigationFence.java");
        String patch = readPatch("0021-route-scoped-navigation-capabilities.patch");
        assertTrue(context.contains("private volatile NavigationLimits navigationLimits")
                        && context.contains("private final ThreadLocal<NavigationLimits> planningNavigationLimits")
                        && context.contains("private final ThreadLocal<CandidateRouteAuthority> planningRouteAuthority")
                        && context.contains("<T> T withCandidateNavigationLimits(MinecraftAiConfig.Nav nav, Supplier<T> calculation)")
                        && context.contains("<T> T withCandidateRouteAuthority(ObservedNavigationFence fence, NavRoute route,")
                        && context.contains("CandidateRouteAuthorityScope openCandidateRouteAuthority(")
                        && context.contains("IPlayerContext.NavigationSnapshot captureNavigationSnapshot()")
                        && context.contains("MinecraftAiConfig.BaritoneCaps committedNavigationCaps()")
                        && context.contains("int navigationMaxSafeFall()"),
                "an uncommitted candidate must be server-thread-local while workers receive a frozen authority snapshot");
        assertFalse(context.contains("BaritoneAPI.getSettings().allowWaterBucketFall.value"),
                "a per-bot water-bucket decision must not read Baritone's mutable JVM-wide switch");
        assertTrue(settings.contains("static ServerPlayerContext.NavigationLimits commitNavCapabilities(AIPlayerEntity bot, MinecraftAiConfig.Nav nav)")
                        && settings.contains("static void restoreNavCapabilities(AIPlayerEntity bot, ServerPlayerContext.NavigationLimits previous)")
                        && settings.contains("static <T> T withCandidateNavCapabilities(AIPlayerEntity bot, MinecraftAiConfig.Nav nav, Supplier<T> calculation)")
                        && settings.contains("entry.context.withCandidateNavigationLimits")
                        && settings.contains("entry.context.committedNavigationCaps()")
                        && settings.contains("static boolean waterBucketFallEnabled(AIPlayerEntity bot)")
                        && settings.contains("static boolean mobAvoidanceEnabled(AIPlayerEntity bot)"),
                "candidate planning and accepted-route publication must use distinct bot-bound capability APIs");
        assertTrue(context.contains("if (BaritoneSettings.mobAvoidanceEnabled(self))")
                        && !context.contains("BaritoneAPI.getSettings().avoidance.value"),
                "entity refresh must use this bot's admitted avoidance choice, not the previous bot's global setting");
        assertTrue(fence.contains("corridorHalfWidth(candidateCaps)")
                        && fence.contains("corridorHalfWidth(bot)")
                        && fence.contains("BaritoneSettings.mobAvoidanceEnabled(bot)")
                        && fence.contains("candidate route configuration"),
                "admission must use its uncommitted candidate caps while active-route refresh uses its own avoidance snapshot");
        assertTrue(navigator.contains("observedHostileCount(baritone, candidateNav.baritoneCaps().mobAvoidanceEnabled())")
                        && navigator.contains("BaritoneSettings.withCandidateNavCapabilities(bot, candidateNav,"),
                "admission retry accounting and its fall proof must not borrow the predecessor's committed caps");
        int start = navigator.indexOf("public static Admission start(AIPlayerEntity bot, NavRoute route, boolean admit)");
        int rejectedObservation = navigator.indexOf("return Admission.refused(observed.failure());", start);
        int candidateNav = navigator.indexOf("MinecraftAiConfig.Nav candidateNav = MinecraftAiConfig.get().nav();", start);
        int candidateFence = navigator.indexOf("observationCaps);", candidateNav);
        int candidateAuthority = navigator.indexOf("entry.context.withCandidateRouteAuthority(", candidateFence);
        int admission = navigator.indexOf("BaritonePlanner.planNow(", candidateFence);
        int replacementCancel = navigator.indexOf("registry.cancelForReplacement(bot, \"route_replaced\");", admission);
        int publishedFence = navigator.indexOf("registry.setObservationFence(bot, observed.fence(), route);", replacementCancel);
        int handoff = navigator.indexOf("baritone.getCustomGoalProcess().setGoalAndPath(goal);", admission);
        int commit = navigator.indexOf("BaritoneSettings.commitNavCapabilities(bot, candidateNav);", handoff);
        assertTrue(start >= 0 && candidateNav > start && candidateFence > candidateNav
                        && rejectedObservation > candidateFence && candidateAuthority > rejectedObservation && admission > candidateAuthority
                        && replacementCancel > admission && publishedFence > replacementCancel
                        && handoff > publishedFence && commit > handoff,
                "a candidate uses private admission authority, cancels the old executor, then publishes and commits only after hand-off");
        int rollback = navigator.indexOf("private static void abandonStart(");
        int replacementPreempted = navigator.indexOf("if (replacementPreempted || goalHandedOff)", rollback);
        int reset = navigator.indexOf("BaritoneRegistry.INSTANCE.reset(bot, \"start_abandoned\");", replacementPreempted);
        assertTrue(rollback >= 0 && replacementPreempted > rollback && reset > replacementPreempted,
                "a post-cancel or post-handoff exception must unconditionally halt the candidate before its fence is cleared");
        int plan = planner.indexOf("public static CompletableFuture<Plan> plan(");
        int candidateScope = planner.indexOf("BaritoneSettings.withCandidateNavCapabilities(bot, candidateNav,", plan);
        int calculationContext = planner.indexOf("new CalculationContext(baritone, true)", candidateScope);
        assertTrue(plan >= 0 && candidateScope > plan && calculationContext > candidateScope
                        && !planner.contains("captureNavCapabilities"),
                "standalone plans must build their CalculationContext inside an uncommitted candidate scope");
        assertTrue(patch.contains("default boolean allowNavigationParkour()")
                        && patch.contains("default boolean allowNavigationVines()")
                        && patch.contains("default int navigationMaxFallHeightNoWater()")
                        && patch.contains("new PrecomputedData(playerContext.allowNavigationVines())")
                        && patch.contains("MovementHelper.canWalkOnBlockState(state, allowVines)")
                        && patch.contains("bsi.allowNavigationVines()")
                        && patch.contains("ctx.allowNavigationMobAvoidance()")
                        && patch.contains("interface NavigationSnapshot")
                        && patch.contains("captureNavigationSnapshot()")
                        && patch.contains("copyLoadedChunks ? ctx.captureNavigationSnapshot() : null")
                        && patch.contains("snapshot == null ? ctx.navigationCellState(x, y, z)")
                        && patch.contains("snapshot == null ? ctx.allowNavigationCell(x, y, z)")
                        && patch.contains("snapshot.navigationCellState(x, y, z)")
                        && patch.contains("snapshot.allowNavigationCell(x, y, z)"),
                "the vendored planner must snapshot every route-sensitive switch and copied-chunk cell authority, including worker-lazy vines");
        int refresh = driver.indexOf("entry.context.refreshEntities()");
        int limits = driver.indexOf("BaritoneSettings.applyNavLimits(bot);", refresh);
        int tick = driver.indexOf("onTick(nextTick(EventState.PRE))", limits);
        assertTrue(refresh >= 0 && limits > refresh && tick > limits,
                "the global compatibility mirror is replayed from the accepted bot snapshot before Baritone executes it");
    }

    @Test
    void acceptedReplacementPreemptsAnOldBreakRouteBeforeWalkOnlyOrBoundaryAuthorityCanPublish() throws IOException {
        String navigator = read("baritone/BaritoneNavigator.java");
        String registry = read("baritone/BaritoneRegistry.java");
        int start = navigator.indexOf("public static Admission start(AIPlayerEntity bot, NavRoute route, boolean admit)");
        int candidate = navigator.indexOf("entry.context.withCandidateRouteAuthority(", start);
        int irreversible = navigator.indexOf("replacementPreempted = true;", candidate);
        int cancel = navigator.indexOf("registry.cancelForReplacement(bot, \"route_replaced\");", irreversible);
        int fence = navigator.indexOf("registry.setObservationFence(bot, observed.fence(), route);", cancel);
        int policy = navigator.indexOf("registry.setPolicy(bot, routePolicy);", fence);
        int handoff = navigator.indexOf("baritone.getCustomGoalProcess().setGoalAndPath(goal);", policy);
        assertTrue(candidate > start && irreversible > candidate && cancel > irreversible
                        && fence > cancel && policy > fence && handoff > policy,
                "an accepted replacement must force-cancel the old executor before a new boundary or WALK_ONLY policy can reach it");

        String cancellation = methodBody(registry, "void cancelForReplacement(AIPlayerEntity bot, String why)");
        assertTrue(cancellation.contains("halt(entry, bot, retainNaturalFollowHandoff);"),
                "replacement cancellation must always halt the old executor, retaining only the bounded natural-follow receipt");
        int haltMethod = registry.indexOf("private static void halt(Entry entry, AIPlayerEntity bot)");
        int forceCancel = registry.indexOf("entry.baritone.getPathingBehavior().forceCancel();", haltMethod);
        int clearKeys = registry.indexOf("entry.baritone.getInputOverrideHandler().clearAllKeys();", forceCancel);
        assertTrue(forceCancel > haltMethod && clearKeys > forceCancel,
                "preemption removes old BREAK input and its executor before the replacement policy is installed");

        int rollback = navigator.indexOf("if (replacementPreempted || goalHandedOff)");
        int destructiveReset = navigator.indexOf("BaritoneRegistry.INSTANCE.reset(bot, \"start_abandoned\");", rollback);
        int destructiveReturn = navigator.indexOf("return;", destructiveReset);
        String destructive = navigator.substring(rollback, destructiveReturn);
        assertFalse(destructive.contains("restoreNavigationLimits"),
                "after replacement preemption, failure must clear rather than resurrect predecessor route metadata");
    }

    @Test
    void theOnlyItemUseWithoutABlockIsTheBucketOfAFallMovement() throws IOException {
        String policy = read("baritone/BaritoneBreakPlacePolicy.java");
        assertTrue(policy.contains("USE_ITEM_ALLOWLIST = Set.of();"), "the general allow-list stays empty");
        assertTrue(policy.contains("BaritoneWaterFall.refusalOf(bot, item)"), "checkUseItem asks the water-fall rule");
        String fall = read("baritone/BaritoneWaterFall.java");
        int refusal = fall.indexOf("static String refusalOf(");
        assertTrue(refusal >= 0);
        String rule = fall.substring(refusal, fall.indexOf("/** Called after the bot used the water bucket", refusal));
        assertTrue(rule.contains("isFallBucket(item)") && rule.contains("BaritoneSettings.waterBucketFallEnabled(bot)") && rule.contains("runningFall(bot)")
                && rule.contains("waterEvaporates(bot, dest)") && rule.contains("target_outside_landing_column"),
                "only the two buckets, with the switch on, inside a fall movement, where water does not evaporate, aimed at the landing column");
        assertFalse(fall.contains("MinecraftAiConfig.get().nav().baritoneCaps()"),
                "the fall movement and its paired pickup must retain the admitted route's bucket switch");
        assertTrue(fall.contains("baritone_water_left"), "a pickup that fails is logged, never silent");
    }

    @Test
    void mobAvoidanceReadsOnlyTheObservedEntities() throws IOException {
        String context = read("baritone/ServerPlayerContext.java");
        assertTrue(context.contains("ObservableWorldQuery.canNoticeCreature(self, mob)"), "a mob enters the list only when the bot has noticed it (realistic perception)");
        assertTrue(context.contains("mob instanceof Enemy"), "hostile mobs only");
        String driver = read("baritone/BaritoneDriver.java");
        assertTrue(driver.contains("BaritoneWaterFall.recover(bot, entry)"), "left-behind water is taken back by the driver");
    }

    private static String methodBody(String source, String signature) {
        int signatureAt = source.indexOf(signature);
        if (signatureAt < 0) {
            throw new AssertionError("missing method signature: " + signature);
        }
        int openBrace = source.indexOf('{', signatureAt);
        if (openBrace < 0) {
            throw new AssertionError("missing method body: " + signature);
        }
        int depth = 0;
        for (int index = openBrace; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(openBrace, index + 1);
            }
        }
        throw new AssertionError("unterminated method body: " + signature);
    }
}

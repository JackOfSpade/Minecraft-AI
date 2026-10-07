package io.github.zoyluo.minecraftai.perception;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Source-level guards for the shared, line-of-sight-only terrain memory.  These deliberately
 * pin the evidence boundary rather than a particular ray-sampling cadence: a linked player can
 * contribute only terrain the server is actually tracking and that their own eye can clip to.
 */
final class SharedWorldSightSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    @Test
    void sharedSightConvertsTheEffectiveChunkDistanceToBlocksAndNeverAssumesLoadedTerrain() throws IOException {
        String sight = read("perception/SharedWorldSight.java");
        String viewport = methodBody(sight, "private static void captureViewport(");
        String lane = methodBody(sight, "private static void captureRouteEnvelope(");

        assertTrue(sight.contains("BLOCKS_PER_CHUNK = 16"),
                "Minecraft render distance is supplied in 16-block chunks");
        assertTrue(sight.contains("(long) Math.max(1, chunks) * BLOCKS_PER_CHUNK"),
                "chunk distance must be converted without an integer-overflow shortcut");
        assertTrue(sight.contains("Math.min(observer.requestedViewDistance(),\n"
                        + "                observer.level().getServer().getPlayerList().getViewDistance())"),
                "the client request is capped by the server's real tracking distance");
        assertTrue(sight.contains("observer.getChunkTrackingView().contains(endChunkX, endChunkZ)")
                        && sight.contains("world.getChunkSource().hasChunk(endChunkX, endChunkZ)"),
                "a ray may not turn an untracked or unloaded end chunk into knowledge");
        assertTrue(viewport.contains("captureRay(bot, observer, forward.normalize(), range, memory, tick)")
                        && viewport.contains("viewportDirection(observer, memory.sweepCursor++), range, memory, tick"),
                "passive sight reaches the effective render range, not a smaller implementation-only cap");
        assertTrue(lane.contains("Math.min(steps, renderDistanceBlocks(observer))"),
                "a route lane may extend through the observer's actual render-distance block budget");
        assertFalse(sight.contains(".getChunk("),
                "the sight memory must not materialize chunks while it is checking visibility");
    }

    @Test
    void memoryIsMadeFromRaysAndARequestedRouteGetsAnExactFreshSightLine() throws IOException {
        String sight = read("perception/SharedWorldSight.java");
        String evidence = methodBody(sight, "public static List<Observation> routeEvidence(");
        String ray = methodBody(sight, "private static void captureRay(");

        assertTrue(evidence.contains("captureTowards(bot, bot, target.getCenter(), memory, tick)")
                        && evidence.contains("captureTowards(bot, owner, target.getCenter(), memory, tick)"),
                "a route request must test the exact target from both legitimate observers");
        assertTrue(evidence.contains("isEligibleOwner(bot, owner)"),
                "the second observer is only the linked, same-level living non-spectator owner");
        assertTrue(ray.contains("SightClip.context(") && ray.contains("world.clip(sight)") && ray.contains("RayGrid.traverse("),
                "remembered terrain must be traversed by a real clip ray (the observer's eyes: vanilla's own traversal with foliage, fences, glass and water skipped), never a volume scan");
        assertTrue(ray.contains("for (double offset = 0.0D; offset < distance; offset += RayGrid.MAX_RANGE)"),
                "a render-distance ray longer than RayGrid's work window must be retained in contiguous proven segments");
        assertTrue(ray.contains("ClipContext.Block.OUTLINE"),
                "the memory must retain a plainly visible non-colliding block (rail, vine, crop, torch) as that block, not AIR");
        assertTrue(ray.contains("hit.getType() == HitResult.Type.BLOCK")
                        && ray.contains("pos.equals(hit.getBlockPos())")
                        && ray.contains("SightClip.crossedState(crossed, pos.asLong())")
                        && ray.contains("state != null ? state : AIR"),
                "a non-air state is recorded only at the first ray-hit cell and at the see-through cells (leaf, fence, glass, water) the ray crossed, which keep their real state: foliage and water are never remembered as free air");
        assertFalse(ray.contains(".getChunks("),
                "route evidence must not enumerate chunks to populate its memory");
    }

    @Test
    void ownerSightCanStillProveACellOutsideTheBotsShortPerceptionRadius() throws IOException {
        String query = read("mode/ObservableWorldQuery.java");
        String cell = methodBody(query,
                "private static boolean canObserveCellWithinAfterPolicy(AIPlayerEntity bot, BlockPos pos, int range,");
        String shapes = methodBody(query, "private static boolean observeShapeFaces(");

        int botRangeCheck = cell.indexOf("bot.getEyePosition().distanceToSqr(pos.getCenter())");
        int ownerFallback = cell.indexOf("ownerCanObserveCell(bot, pos, fluid, seeThrough)");
        assertTrue(botRangeCheck >= 0 && ownerFallback > botRangeCheck,
                "the bot's short-range clip remains the first inexpensive proof");
        assertTrue(cell.contains("return rememberIfVisible(bot, pos, ownerCanObserveCell(bot, pos, fluid, seeThrough));"),
                "when a cell is beyond the bot radius, owner render-distance LOS must be attempted instead of returning false");
        assertTrue(shapes.contains("ownerCanObserveShape(bot, pos, outlineFallback, fluid, seeThrough)"),
                "shape-aware block observation likewise accepts a linked owner's legitimate LOS");
    }

    @Test
    void ownerObservationIsBoundedByTrackingViewRenderDistanceAndAnOwnEyeRay() throws IOException {
        String query = read("mode/ObservableWorldQuery.java");
        String owner = methodBody(query, "private static ServerPlayer sharedOwner(");
        String range = methodBody(query, "private static int ownerRenderDistanceBlocks(");
        String tracking = methodBody(query, "private static boolean ownerTracks(");
        String cell = methodBody(query, "private static boolean ownerCanObserveCell(");

        assertTrue(owner.contains("!owner.isAlive()") && owner.contains("owner.isSpectator()")
                        && owner.contains("owner.level() != bot.level()"),
                "a dead, spectator, cross-dimension, or unlinked player cannot donate terrain knowledge");
        assertTrue(range.contains("Math.min(owner.requestedViewDistance(),")
                        && range.contains("getPlayerList().getViewDistance()") && range.contains("* 16L"),
                "the owner's client render distance is constrained by the server cap and converted to blocks");
        assertTrue(tracking.contains("owner.getChunkTrackingView().contains(chunkX, chunkZ)")
                        && tracking.contains("owner.level().getChunkSource().hasChunk(chunkX, chunkZ)"),
                "an active client chunk and an actually loaded server chunk are both required");
        assertTrue(cell.contains("owner.getEyePosition()") && cell.contains("eyeClip(owner, eye, target,"),
                "owner terrain knowledge comes from the owner's own clip ray");
    }

    @Test
    void navigationImportsOnlyRememberedRayEvidenceAndKeepsItBoundedAndLifecycleManaged() throws IOException {
        String fence = read("baritone/ObservedNavigationFence.java");
        String merge = methodBody(fence, "private static void mergeSharedWorldSight(");
        String coordinator = read("task/BotTickCoordinator.java");
        String manager = read("manager/AIPlayerManager.java");
        String mod = read("MinecraftAiMod.java");

        assertTrue(merge.contains("SharedWorldSight.routeEvidence(bot, target)")
                        && merge.contains("observation.state(), observation.seenTick()")
                        && merge.contains("MEMORY_TTL_TICKS"),
                "Baritone receives the exact remembered state/tick, only while the normal navigation TTL permits it");
        assertFalse(merge.contains("getBlockState("),
                "importing sight must never reread hidden terrain on the navigation boundary");
        int senses = coordinator.indexOf("CreatureSenses.INSTANCE.tickBot(server, bot);");
        int sight = coordinator.indexOf("SharedWorldSight.tickBot(bot);");
        int executor = coordinator.indexOf("GoalExecutor.INSTANCE.tickBot(server, bot)");
        assertTrue(senses >= 0 && sight > senses && executor > sight,
                "sight is collected before a task can request a path, without replacing ordinary perception");
        assertTrue(manager.contains("SharedWorldSight.forget(entity.getUUID())")
                        && mod.contains("SharedWorldSight.clearAll()"),
                "per-bot and server-lifetime terrain memories are released on despawn and shutdown");
    }

    @Test
    void successfulLiveBlockAndCellProofsAreRememberedAndCanSeedAVisibleHarvestRoute() throws IOException {
        String query = read("mode/ObservableWorldQuery.java");
        String harvest = read("action/HarvestCore.java");
        String remembered = methodBody(query, "private static boolean rememberIfVisible(");
        String knownTarget = methodBody(harvest, "private static TargetChoice knownVisibleTarget(");

        assertTrue(remembered.contains("SharedWorldSight.rememberConfirmed(bot, pos)"),
                "every successful current observation must update the shared block memory");
        assertTrue(query.contains("return rememberIfVisible(bot, pos, true);")
                        && query.contains("ownerCanObserveCell(bot, pos, fluid, seeThrough)"),
                "both direct block/cell rays and owner-side rays must feed that memory");
        assertTrue(harvest.contains("SharedWorldSight.knownBlocks(")
                        && harvest.contains("knownVisibleTarget(bot, targetBlocks, posFilter, allowObservableCellFallback)"),
                "a remembered block outside the local survey cube must be considered before a wide scan");
        assertTrue(knownTarget.indexOf("canObserveHarvestTarget(bot, pos, allowObservableCellFallback)")
                        < knownTarget.indexOf("bot.level().getBlockState(pos)"),
                "memory must be re-proven by current line of sight before its state is reread or routed to");
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

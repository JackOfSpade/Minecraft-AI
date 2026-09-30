package io.github.zoyluo.minecraftai.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Source-contract pins for the lazy, fail-soft Baritone bootstrap and the routing table of the navigator seam: with the legacy
 * engine no hook that runs for every bot or every lifecycle event may reach a Baritone class, only the engine seam may enter
 * Baritone (through {@code NavEngineSelector.attempt}), and the legacy executor is dropped before Baritone moves the bot.
 */
final class NavEngineLazyBootstrapContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    /** Every occurrence of {@code needle} in {@code source} has {@code guard} within the {@code window} characters before it. */
    private static void assertGuarded(String source, String needle, String guard, int window, String what) {
        int count = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + 1)) {
            count++;
            String before = source.substring(Math.max(0, at - window), at);
            assertTrue(before.contains(guard), what + ": " + needle + " at offset " + at + " is not behind " + guard);
        }
        assertTrue(count > 0, what + ": expected at least one " + needle);
    }

    /** As {@link #assertGuarded} with several accepted guards. */
    private static void assertGuardedByAny(String source, String needle, int window, String what, String... guards) {
        int count = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + 1)) {
            count++;
            String before = source.substring(Math.max(0, at - window), at);
            assertTrue(java.util.Arrays.stream(guards).anyMatch(before::contains), what + ": " + needle + " at offset " + at + " is not behind " + String.join(" or ", guards));
        }
        assertTrue(count > 0, what + ": expected at least one " + needle);
    }

    @Test
    void theNavigationPackageNamesNoBaritoneType() throws IOException {
        try (Stream<Path> files = Files.list(MAIN.resolve("navigation"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                assertFalse(source.contains("import baritone."), file.getFileName() + " must not import Baritone");
                assertFalse(source.contains("io.github.zoyluo.minecraftai.baritone"), file.getFileName() + " must not import the Baritone glue");
            }
        }
    }

    @Test
    void perBotAndLifecycleHooksAreBehindTheLiveFlag() throws IOException {
        String entity = read("entity/AIPlayerEntity.java");
        assertTrue(entity.contains("boolean baritoneDrives = baritoneBeforePhysics();")
                        && entity.contains("if (!NavEngineSelector.baritoneActive()) {\n            return false;\n        }\n        try {\n            return BaritoneDriver.beforePhysics(this);"),
                "the per-tick driver hook is only reached while Baritone is live and has not been given up on");
        assertTrue(entity.contains("if (baritoneDrives) {\n                baritoneAfterPhysics();"),
                "afterPhysics only runs for a bot beforePhysics reported as driven");
        String lifecycle = read("runtime/RuntimeLifecycleCoordinator.java");
        assertGuarded(lifecycle, "BaritoneRegistry.INSTANCE.", "NavEngineSelector.hook(", 60, "RuntimeLifecycleCoordinator");
        String pack = read("action/ActionPack.java");
        assertGuardedByAny(pack, "BaritoneRegistry.INSTANCE.", 60, "ActionPack", "NavEngineSelector.hook(", "NavEngineSelector.query(");
    }

    @Test
    void onlyTheEngineSeamEntersBaritoneAndItGoesThroughAttempt() throws IOException {
        String pack = read("action/ActionPack.java");
        // Every caller of the seam's entry method wraps it in NavEngineSelector.attempt (fallback = not routed).
        int calls = 0;
        for (int at = pack.indexOf("startBaritoneRoute("); at >= 0; at = pack.indexOf("startBaritoneRoute(", at + 1)) {
            if (pack.startsWith("startBaritoneRoute(NavRoute request", at)) {
                continue; // the definition
            }
            calls++;
            assertTrue(pack.substring(Math.max(0, at - 120), at).contains("NavEngineSelector.attempt("),
                    "startBaritoneRoute call at offset " + at + " is not inside NavEngineSelector.attempt");
        }
        assertEquals(4, calls, "path_to, approach, swim_route and run_away are the entries of the Baritone engine");
        // Nothing else in the mod starts Baritone routes.
        try (Stream<Path> files = Files.walk(MAIN)) {
            List<Path> users = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains("BaritoneNavigator.start(");
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }).toList();
            assertEquals(1, users.size(), "BaritoneNavigator.start is called from ActionPack only: " + users);
            assertTrue(users.get(0).endsWith(Path.of("action", "ActionPack.java")));
        }
    }

    @Test
    void anInstanceIsOnlyCreatedByTheRegistryWhichMarksBaritoneLive() throws IOException {
        String registry = read("baritone/BaritoneRegistry.java");
        int create = registry.indexOf("BaritoneHost.create(");
        int put = registry.indexOf("entries.put(bot.getUUID(), entry);", create);
        int live = registry.indexOf("NavEngineSelector.markBaritoneLive();", put);
        assertTrue(create > 0 && put > create && live > put, "the flag is raised once the instance is registered");
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("BaritoneRegistry.java") || file.getFileName().toString().equals("BaritoneHost.java")) {
                    continue;
                }
                assertFalse(Files.readString(file).contains("BaritoneHost.create("), file + " must not create instances itself");
            }
        }
    }

    @Test
    void theRoutingTableKeepsContractRoutesDigApproachesAndStraightWalksLegacy() throws IOException {
        String pack = read("action/ActionPack.java");
        int route = pack.indexOf("private ActionResult routeOnBaritone(");
        int constrained = pack.indexOf("if (routeContract.constrained()) {", route);
        int attempt = pack.indexOf("NavEngineSelector.attempt(player.getUUID(), kind", constrained);
        assertTrue(route > 0 && constrained > route && attempt > constrained, "a contract route is answered (as not routed) before Baritone is asked");
        int dig = pack.indexOf("public ActionResult startDigPathTo(BlockPos goal, int protectedStoneLikeReserve) {");
        int digEnd = pack.indexOf("public ActionResult startPathTo(BlockPos goal) {", dig);
        assertFalse(pack.substring(dig, digEnd).contains("routeOnBaritone("), "dig approaches stay legacy in P1");
        int walk = pack.indexOf("public ActionResult startWalkTo(Vec3 target, double arrivalThreshold) {");
        int walkEnd = pack.indexOf("// Unified entry point", walk);
        assertFalse(pack.substring(walk, walkEnd).contains("routeOnBaritone("), "straight-line walks stay legacy in P1");
    }

    @Test
    void theLegacyExecutorIsDroppedBeforeBaritoneMovesTheBot() throws IOException {
        String pack = read("action/ActionPack.java");
        int start = pack.indexOf("private ActionResult startBaritoneRoute(");
        int yield = pack.indexOf("yieldToBaritone();", start);
        int admit = pack.indexOf("BaritoneNavigator.start(player, request, admit)", start);
        assertTrue(start > 0 && yield > start && admit > yield, "single writer: legacy state is dropped first");
        // A legacy order cancels the recorded Baritone route through the shared claim.
        assertTrue(pack.contains("private void claim(String why) {\n        releaseBaritone(why);"));
        assertTrue(pack.contains("cancelBaritoneRoute(\"stop_navigation\")"), "stopNavigation ends a Baritone route too");
    }

    @Test
    void theSwimLeaseIsRenewedOnlyWhileBaritoneDrivesAndEndsWithTheDrive() throws IOException {
        String driver = read("baritone/BaritoneDriver.java");
        assertTrue(driver.contains("if (entry.waterAllowed) {") && driver.contains("NavSafetyNet.INSTANCE.renewBaritoneWater(bot)"));
        int released = driver.indexOf("BotLog.lifecycle(bot, \"baritone_released\"");
        int cleared = driver.lastIndexOf("NavSafetyNet.INSTANCE.clearBaritoneWater(bot)", released);
        assertTrue(cleared > 0, "the lease ends on the tick the drive ends");
        String registry = read("baritone/BaritoneRegistry.java");
        int halt = registry.indexOf("private static void halt(");
        assertTrue(registry.indexOf("NavSafetyNet.INSTANCE.clearBaritoneWater(bot)", halt) > halt, "cancel, takeover and removal end the lease");
        String net = read("task/NavSafetyNet.java");
        int tick = net.indexOf("public boolean tickBot(");
        int lease = net.indexOf("hasBaritoneWaterLease(bot, server.getTickCount())", tick);
        int crisis = net.indexOf("boolean inCrisis", tick);
        assertTrue(lease > tick && crisis > lease, "the lease is checked before the water crisis machine");
    }
}

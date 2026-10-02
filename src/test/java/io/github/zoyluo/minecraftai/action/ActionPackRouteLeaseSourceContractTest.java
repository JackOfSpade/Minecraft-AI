package io.github.zoyluo.minecraftai.action;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code startBaritoneRoute} hands the bot over from local physical controllers before the Baritone admission
 * ({@code yieldToBaritone}, which keeps the route lease for the route that is about to start). A request that is refused or throws with
 * no Baritone route behind it must take that lease back, or a stale clockless ROUTE lease stays in force
 * for an idle bot. The behaviour is pinned by {@code NaturalMovementGameTests.aRefusedBaritoneRequestDropsTheStaleRouteLease}; this pins
 * the structure.
 */
class ActionPackRouteLeaseSourceContractTest {
    private static final Path SOURCE = Path.of("src/main/java/io/github/zoyluo/minecraftai/action/ActionPack.java");

    @Test
    void aRefusedOrFailedBaritoneStartWithNoRouteBehindItDropsTheStaleLease() throws IOException {
        String source = Files.readString(SOURCE);
        int start = source.indexOf("private ActionResult startBaritoneRoute(");
        int end = source.indexOf("/** How the last Baritone route of this pack ended", start);
        assertTrue(start >= 0 && end > start);
        String body = source.substring(start, end);
        int yield = body.indexOf("yieldToBaritone();");
        int thrown = body.indexOf("} catch (Throwable failure) {");
        int refused = body.indexOf("if (!admission.accepted()) {");
        assertTrue(yield >= 0 && thrown > yield && refused > thrown);
        String thrownBranch = body.substring(thrown, refused);
        String refusedBranch = body.substring(refused, body.indexOf("double dx = request.target()", refused));
        assertTrue(thrownBranch.contains("cancelBaritoneRoute(\"start_failed\")") && thrownBranch.contains("dropStaleRouteLease()"),
                "a start that throws with no Baritone route behind it must drop the old route's lease");
        assertTrue(refusedBranch.contains("cancelBaritoneRoute(\"rejected_request\")") && refusedBranch.contains("dropStaleRouteLease()"),
                "a refused request with no Baritone route behind it must drop the old route's lease");
        // A route that DOES start still owns the lease left for it (yieldToBaritone keeps it).
        int keep = source.indexOf("public void yieldToBaritone()");
        String yieldBody = source.substring(keep, source.indexOf("public void setForward", keep));
        assertTrue(yieldBody.contains("cancelStep();") && yieldBody.contains("stopMining();")
                        && yieldBody.contains("this.walkTo = null;") && !yieldBody.contains("clearRouteLease()"),
                "the local-controller hand-over keeps the lease for the route that is about to start");
        int drop = source.indexOf("private void dropStaleRouteLease()");
        assertTrue(drop >= 0);
        String dropBody = source.substring(drop, source.indexOf("/** The route lease ends with the route it was requested for. */", drop));
        assertTrue(dropBody.contains("clearRouteLease()"));
        assertFalse(dropBody.contains("tickLeases"), "a task's tick lease is not this pack's route lease");
    }
}

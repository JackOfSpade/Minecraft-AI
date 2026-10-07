package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class GatherAtomicPhaseSourceContractTest {
    @Test
    void regionalWatchdogCannotInterruptHarvestOrPickup() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/GatherQuotaTask.java"));
        assertTrue(source.contains("phase == Phase.SURVEY || phase == Phase.GOTO"),
                "regional self-stuck watchdog must be limited to discovery/navigation phases");
        assertTrue(source.contains("elapsed - harvestStartedTick > HARVEST_LIMIT"),
                "HARVEST needs its own explicit bounded watchdog");
        int confirmation = source.indexOf("private boolean confirmPickup");
        int reset = source.indexOf("pickupMisses = 0", confirmation);
        int transition = source.indexOf("phase = Phase.DONE", confirmation);
        assertTrue(confirmation > 0 && reset > confirmation && reset < transition,
                "physical pickup confirmation must reset the consecutive-miss ledger");
        assertTrue(source.contains("private BlockPos pickupOrigin"),
                "gather must retain the factual break coordinate until pickup resolves");
        assertTrue(source.contains("BlockPos lookAround = dropRestedAt != null ? dropRestedAt : pickupOrigin;")
                        && source.contains("new KnownCellPickupSweep(lookAround)")
                        && source.contains("pickupOriginSweep.step(bot)"),
                "an occluded drop must fall back to where it was last seen at rest, else to the remembered break coordinate");
        assertTrue(source.contains("Stats.ITEM_PICKED_UP"),
                "vanilla pickup stats must distinguish collection from concurrent inventory consumption");
        int miss = source.indexOf("gather_pickup_miss");
        int watchdogReset = source.indexOf("resetSurveyWatchdog()", miss);
        int survey = source.indexOf("phase = Phase.SURVEY", miss);
        assertTrue(watchdogReset > miss && watchdogReset < survey,
                "a real pickup miss must receive a fresh local-survey watchdog window");
    }

    @Test
    void bootstrapAndPickupSweepAroundTheBreakCellWithoutDiggingOrPillaring() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/GatherQuotaTask.java"));
        assertTrue(source.contains("new KnownCellPickupSweep(bootstrapPickupOrigin)")
                        && source.contains("bootstrapOriginSweep.step(bot)"),
                "a hand-broken bootstrap log's hidden drop must be searched around its break cell");
        String sweep = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/action/KnownCellPickupSweep.java"));
        assertTrue(sweep.contains("HarvestCore.startExactPickupPath(bot, target)")
                        && sweep.contains("HarvestCore.approachKnownPickupCell(bot, origin)"),
                "the sweep must move through exact surface routes and the plain remembered-cell approach");
        for (String forbidden : new String[] {"placeBlock", "startMining", "breakBlock", "descendInto", "pillarUp"}) {
            assertTrue(!sweep.contains(forbidden),
                    "the sweep must never dig or pillar (found " + forbidden + ")");
        }
    }

    @Test
    void confirmedPickupClaimsItsInventoryDeltaBeforeGenericAuditLogging() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/GatherQuotaTask.java"));
        int tick = source.indexOf("protected void onTick(");
        int deadline = source.indexOf("private void finishTimedCollection(", tick);
        String onTick = source.substring(tick, deadline);
        int pickupPhase = onTick.indexOf("if (phase == Phase.PICKUP)");
        int confirmation = onTick.indexOf("confirmPickup(bot, pickedUpAccepted(bot))", pickupPhase);
        int genericAudit = onTick.indexOf("logGatherUnitGains(bot, \"unattributed\", null)");

        assertTrue(pickupPhase >= 0 && confirmation > pickupPhase && genericAudit > confirmation,
                "a physical pickup must consume its audit delta before generic inventory logging labels it unattributed");
        assertTrue(source.contains("logGatherUnitGains(bot, \"pickup\", pickupOrigin)"),
                "the confirmed pickup path must remain the sole attributed gather-unit logger");
    }
}

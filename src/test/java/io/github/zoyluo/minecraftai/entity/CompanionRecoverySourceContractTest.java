package io.github.zoyluo.minecraftai.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Locks in post-death follow recovery and the owner-only companion recall escape hatch. */
final class CompanionRecoverySourceContractTest {
    @Test
    void interruptedFollowResumesAfterTheCompanionRevives() throws IOException {
        String lifecycle = read("runtime/RuntimeLifecycleCoordinator.java");
        String manager = read("manager/AIPlayerManager.java");

        int capture = lifecycle.indexOf("TaskManager.INSTANCE.activeOrPausedFollowIntent(bot)");
        int cancellation = lifecycle.indexOf("TaskManager.INSTANCE.cancelIntentTasks(bot, \"bot_died\")");
        assertTrue(capture >= 0 && capture < cancellation);
        assertTrue(lifecycle.contains("new FollowTask(targetName)"));
        assertTrue(lifecycle.contains("follow_resumed_after_death"));
        assertTrue(lifecycle.contains("death_owner_follow")
                        && lifecycle.contains("fallbackOwnerFollow"),
                "an owned companion whose safety task replaced its follow must still return to its owner after respawn");
        assertTrue(manager.contains("RuntimeLifecycleCoordinator.INSTANCE.onBotRespawned(bot);"));
    }

    @Test
    void idleCompanionReturnsToOwnerAfterThreatBeforeMaintenanceCanClaimTheSlot() throws IOException {
        String danger = read("task/DangerWatcher.java");

        assertTrue(danger.contains("ownerFollowAfterThreat"));
        assertTrue(danger.contains("post_combat_owner_follow"));
        int restore = danger.indexOf("maybeRestoreOwnerFollowAfterThreat(bot, active, threat)");
        int lighting = danger.indexOf("maybeLightDarkArea(server, bot, active)");
        assertTrue(restore >= 0 && lighting > restore,
                "post-combat follow must restore before background lighting can take an idle companion");
    }

    @Test
    void aCombatInterruptionOfFollowIsExplainedOnceAndItsRecoveryIsAnnounced() throws IOException {
        String danger = read("task/DangerWatcher.java");

        assertTrue(danger.contains("followCombatInterruptions"));
        assertTrue(danger.contains("I'm pausing follow to fight a hostile. I'll resume following you when it's safe."));
        assertTrue(danger.contains("follow_paused_for_combat"));
        assertTrue(danger.contains("The danger is clear. I'm following you again."));
        assertTrue(danger.contains("follow_resumed_after_combat"));
        int announce = danger.indexOf("private void announceFollowCombatInterruption");
        int resume = danger.indexOf("private void announceFollowCombatResumed");
        int restore = danger.indexOf("private boolean maybeRestoreOwnerFollowAfterThreat");
        assertTrue(announce >= 0 && resume > announce && restore > resume,
                "the one-per-episode combat notice must exist before the post-combat follow restore path");
        assertTrue(danger.indexOf("announceFollowCombatInterruption(bot, active);") < restore,
                "a follow interruption is announced when danger takes control, not after it is already over");
        assertTrue(danger.indexOf("announceFollowCombatResumed(bot);", restore) > restore,
                "the matching recovery notice is sent only when retained follow work is restored");
    }

    @Test
    void ownerRecallIsExposedUnderBothCommandSpellings() throws IOException {
        String command = read("command/MinecraftAiCommand.java");
        String manager = read("manager/AIPlayerManager.java");

        assertTrue(command.contains("registerRoot(dispatcher, \"minecraftai\")"));
        assertTrue(command.contains("registerRoot(dispatcher, \"minecraft-ai\")"));
        assertTrue(command.contains("literal(\"companions\")"));
        assertTrue(command.contains("literal(\"recall\")"));
        assertTrue(command.contains("recallOwnedCompanions(owner)"));
        assertTrue(command.contains("recallOwnedCompanion(owner, name)"));
        assertTrue(manager.contains("public int recallOwnedCompanions(ServerPlayer owner)"));
        assertTrue(manager.contains("public boolean recallOwnedCompanion(ServerPlayer owner, String companionName)"));
        int recall = manager.indexOf("private boolean recallOwnedCompanion(ServerPlayer owner, AIPlayerEntity bot)");
        int nextMethod = manager.indexOf("\n    /**", recall + 1);
        String recallBody = manager.substring(recall, nextMethod);
        assertTrue(recallBody.contains("needsRecoveryRespawn(bot)")
                        && recallBody.contains("teleportCompanionBesideOwner(bot, owner)")
                        && recallBody.contains("placement.get().kind()"),
                "recall must report a verified visible owner-adjacent placement, not just a successful teleport call");
        assertFalse(recallBody.contains("safeSpawnPosition"),
                "recall must never silently fall back to a remote vertically-nearest spawn position");
        assertTrue(manager.contains("hasClearOwnerSpawnLine")
                        && manager.contains("owner.hasLineOfSight(bot)")
                        && manager.contains("OWNER_VISIBLE_ADJACENCY_SQR"),
                "the recalled companion must have both an unobstructed physical owner line and a bounded adjacent distance");
        assertTrue(manager.contains("companion_recall_visible_placement_failed"),
                "when no visible adjacent cell exists, recall must fail truthfully instead of claiming success");
        assertTrue(manager.contains("companion_recalled_by_owner"));
        assertTrue(manager.contains("bot.ensureMinecraftAiEntityTracked()"),
                "recall must repair the fake player's missing vanilla entity tracker before testing owner pairing");
    }

    @Test
    void ownerRecallRebuildsTheNativeClientEntityPairing() throws IOException {
        String manager = read("manager/AIPlayerManager.java");
        String mixins = Files.readString(Path.of("src/main/resources/minecraftai.mixins.json"));

        assertTrue(manager.contains("rebuildOwnerClientPairing(bot, owner)"),
                "server-side proximity alone cannot claim a successful recall when the owner client lost the entity");
        assertTrue(manager.contains("ClientboundPlayerInfoUpdatePacket.createPlayerInitializing"),
                "the client needs the companion player profile before replaying its add-entity packet");
        assertTrue(manager.contains("minecraftai$removePlayer(owner)")
                        && manager.contains("minecraftai$updatePlayer(owner)"),
                "recall must replay the vanilla tracker pairing rather than inventing a partial spawn packet");
        assertTrue(manager.contains("minecraftai$getServerEntity().addPairing(owner)")
                        && manager.contains("visible_adjacency_fallback"),
                "a stale owner chunk view must still receive the native pairing after a physical adjacent-visibility proof");
        assertTrue(manager.contains("companion_owner_client_pairing_rebuilt"));
        assertTrue(mixins.contains("ChunkMapEntityTrackersAccessorMixin")
                        && mixins.contains("ChunkMapEntityTrackerInvokerMixin")
                        && mixins.contains("ChunkMapTrackedEntityPairingInvokerMixin"));
        String entity = read("entity/AIPlayerEntity.java");
        assertTrue(entity.contains("minecraftai$addEntity(this)")
                        && entity.contains("ensureMinecraftAiEntityTracked"),
                "a retained fake player must be re-added to ChunkMap's entity tracker after death");
    }

    @Test
    void unverifiedOwnerPlacementRollsBackItsSpeculativeTeleport() throws IOException {
        String manager = read("manager/AIPlayerManager.java");
        int placement = manager.indexOf("private static Optional<OwnerPlacement> teleportCompanionBesideOwner");
        assertTrue(placement >= 0, "owner placement must remain a dedicated transactional operation");
        int rollback = manager.indexOf("private static boolean restoreCompanionLocation", placement);
        assertTrue(rollback > placement, "owner placement must have a rollback helper");
        String placementBody = manager.substring(placement, rollback);
        assertTrue(placementBody.contains("CompanionLocation original = CompanionLocation.capture(bot);")
                        && placementBody.contains("boolean ownerClientPairingBefore = ownerClientPairingPresent(bot, owner);")
                        && placementBody.contains("boolean committed = false;"),
                "owner placement must retain its complete pre-attempt location and owner-pairing state until it commits");
        assertTrue(placementBody.contains("if (!committed) {")
                        && placementBody.contains("restoreCompanionLocation(bot, original) && ownerClientPairingBefore")
                        && placementBody.contains("restoreOwnerClientPairingAfterRollback(bot, owner);"),
                "a failed candidate/pairing attempt must restore both the companion and its prior owner client pairing");
        assertTrue(manager.contains("bot.teleportTo(original.world(),")
                        && manager.contains("bot.setYRot(original.yaw());")
                        && manager.contains("bot.setXRot(original.pitch());")
                        && manager.contains("bot.setYHeadRot(original.headYaw());")
                        && manager.contains("bot.setYBodyRot(original.bodyYaw());"),
                "rollback must restore the original world, coordinates, and every body/look rotation");
        assertTrue(manager.contains("companion_owner_placement_rollback_failed"),
                "a rare vanilla rollback failure must be visible rather than silently claiming a failed recall");
        int pairingRollback = manager.indexOf("private static void restoreOwnerClientPairingAfterRollback");
        int pairingTracker = manager.indexOf("private static ChunkMapTrackedEntityPairingInvokerMixin ownerClientPairingTracker",
                pairingRollback);
        assertTrue(pairingRollback >= 0 && pairingTracker > pairingRollback,
                "rollback must retain a dedicated owner-pairing restoration path");
        String pairingRollbackBody = manager.substring(pairingRollback, pairingTracker);
        assertTrue(pairingRollbackBody.contains("pairing.minecraftai$getSeenBy().contains(owner.connection)")
                        && pairingRollbackBody.contains("pairing.minecraftai$getSeenBy().add(owner.connection)")
                        && pairingRollbackBody.contains("pairing.minecraftai$getServerEntity().addPairing(owner)"),
                "a pre-existing owner pairing must be replayed with the native tracker after rollback");
        assertTrue(pairingRollbackBody.contains("companion_owner_pairing_rollback_failed"),
                "an unrecoverable pairing rollback must be observable");
    }

    @Test
    void ownerDeathKeepsSafetyCombatThenHoldsUntilANewDirection() throws IOException {
        String lifecycle = read("runtime/RuntimeLifecycleCoordinator.java");
        String danger = read("task/DangerWatcher.java");
        String tasks = read("task/TaskManager.java");
        String coordinator = read("task/BotTickCoordinator.java");
        String mod = read("MinecraftAiMod.java");

        assertTrue(lifecycle.contains("cancelIntentTasksKeepingActiveCombatOrSafety(bot, \"owner_died\")"));
        assertTrue(lifecycle.contains("companion_owner_death_watch"));
        assertTrue(danger.contains("awaitingOwnerReturn(bot)"));
        assertTrue(danger.contains("new HoldTask()"));
        assertTrue(tasks.contains("clearOwnerDeathWatch(bot, \"new_owner_direction\")"));
        assertTrue(coordinator.contains("boolean runDanger = awaitingOwnerReturn ||"));
        assertTrue(mod.contains("onOwnerDeath(player)"));
    }

    private static String read(String relative) throws IOException {
        return Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai").resolve(relative));
    }
}

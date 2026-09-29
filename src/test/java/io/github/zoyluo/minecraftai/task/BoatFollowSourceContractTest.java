package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the survival-facing boat path to vanilla interactions and the three follow modes. */
final class BoatFollowSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    @Test
    void boatCraftAndLaunchUseRecipesAndVanillaInteractions() throws IOException {
        String recipes = read("craft/RecipeRegistry.java");
        String action = read("action/BoatAction.java");
        String launch = read("task/BoatLaunchTask.java");

        assertTrue(recipes.contains("boat(Items.OAK_BOAT, Items.OAK_PLANKS)"));
        assertTrue(recipes.contains("boat(Items.CHERRY_BOAT, Items.CHERRY_PLANKS)"));
        assertTrue(action.contains("InteractAction.useItemInAir(player, Hand.MAIN_HAND)"),
                "a boat must be placed through the ordinary held-item use path");
        assertTrue(action.contains("InteractAction.useItemOnEntity(player, boat, Hand.MAIN_HAND)"),
                "boarding must use vanilla entity interaction");
        assertFalse(action.contains("spawnEntity("), "the task must not fabricate a boat entity");
        assertFalse(action.contains("startRiding("), "the task must not bypass vanilla boarding");
        assertTrue(launch.contains("new CraftTask(candidate, 1)"));
        assertTrue(launch.contains("BoatAction.placeBoatInWater"));
    }

    @Test
    void ordinaryFollowSelectsBoatOrSwimmingWithoutConflatingThem() throws IOException {
        String follow = read("task/FollowTask.java");
        String safety = read("task/NavSafetyNet.java");

        assertTrue(follow.contains("boolean targetInBoat"));
        assertTrue(follow.contains("boolean targetSwimming = !targetInBoat"));
        assertTrue(follow.contains("BoatFollowTask.automatic(targetName, abandonedBoats)"));
        assertTrue(follow.contains("followSwimming(bot, target)"));
        assertTrue(follow.contains("FakePlayerMotion.swimStepTo(bot, candidate, \"follow_swim\")"));
        int swimming = follow.indexOf("private void followSwimming");
        int land = follow.indexOf("private void followLand", swimming);
        String swimmingBody = follow.substring(swimming, land);
        assertFalse(swimmingBody.contains("BoatLaunchTask"),
                "a swimming player must never make ordinary follow launch a boat");
        assertTrue(follow.contains("BoatSupport.leaveBoatForLand("),
                "boat-to-land follow must go through the shared dismount-or-steer helper");
        String boatSupport = read("task/BoatSupport.java");
        assertTrue(boatSupport.contains("nearbySafeDismountShore"),
                "boat-to-land follow must wait for a real dry shore before dismounting");
        assertTrue(safety.contains("FOLLOW_SWIM_LEASE_TICKS = 6"));
        assertTrue(safety.contains("void renewFollowSwim(AIPlayerEntity bot)"));
        assertTrue(safety.contains("void clearFollowSwim(AIPlayerEntity bot)"));
        assertTrue(safety.contains("bot.getAir() <= AIR_SURFACE_THRESHOLD"),
                "the narrow swim allowance must expire before drowning safety is weakened");
    }

    /**
     * Root cause of "the boat never moves" (live log 20260928-232208): vanilla treats a boat with a
     * player passenger as client-authoritative, and our fake player has no client to simulate or
     * report it.  The two mixins must make the server authoritative ONLY for our own AI player, and
     * must not touch boats driven by anyone (or anything) else.
     */
    @Test
    void aiPlayerBoatIsSimulatedServerSideOnlyForAiPlayers() throws IOException {
        String logical = read("mixin/AIPlayerControlledBoatLogicalSideMixin.java");
        String paddle = read("mixin/AIPlayerControlledBoatPaddleMixin.java");
        String config = Files.readString(Path.of("src/main/resources/minecraftai.mixins.json"));

        assertTrue(logical.contains("isLogicalSideForUpdatingMovement"));
        assertTrue(logical.contains("boat.getEntityWorld().isClient()"),
                "the client side must keep vanilla behaviour");
        assertTrue(logical.contains("getControllingPassenger() instanceof AIPlayerEntity"),
                "only a boat controlled by our own AI player may become server-authoritative");
        assertTrue(paddle.contains("updatePaddles"));
        assertTrue(paddle.contains("getControllingPassenger() instanceof AIPlayerEntity"),
                "server-side paddling must be limited to boats our AI player controls");
        assertTrue(config.contains("AIPlayerControlledBoatLogicalSideMixin")
                && config.contains("AIPlayerControlledBoatPaddleMixin"),
                "both boat mixins must be registered");
    }

    /** A follower must retry boat acquisition on a cooldown and give up on wedged boats. */
    @Test
    void boatAcquisitionIsThrottledAndStuckBoatsAreNeverReboarded() throws IOException {
        String follow = read("task/FollowTask.java");
        String boatFollow = read("task/BoatFollowTask.java");
        assertTrue(follow.contains("nextBoatAttemptTick = elapsed + BOAT_ACQUIRE_COOLDOWN_TICKS"));
        assertTrue(follow.contains("if (elapsed < nextBoatAttemptTick)"),
                "a failed acquisition must not be retried every tick");
        assertTrue(boatFollow.contains("new BoardBoatTask(abandonedBoats)"));
        assertTrue(boatFollow.contains("boat_follow_stuck_recovered"));
        assertTrue(boatFollow.contains("STUCK_WINDOW_TICKS"));
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}

package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BoatAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;
import java.util.UUID;

/** Boards a nearby empty boat through the normal entity-interaction path. */
public final class BoardBoatTask extends AbstractTask {
    private static final int MAX_TICKS = 500;
    private static final int MAX_BOARD_ATTEMPTS = 3;
    private static final int REPATH_TICKS = 40;

    private enum Phase {
        FIND_BOAT,
        APPROACH,
        BOARD
    }

    private final UUID requestedBoatId;
    private Phase phase = Phase.FIND_BOAT;
    private UUID boatId;
    private BlockPos shore;
    private int nextRepathTick;
    private int boardAttempts;

    /** Boards the nearest nearby empty boat. */
    public BoardBoatTask() {
        this(null);
    }

    /** Boards a particular boat created by a preceding launch task. */
    public BoardBoatTask(UUID requestedBoatId) {
        this.requestedBoatId = requestedBoatId;
    }

    @Override
    public String name() {
        return "board_boat";
    }

    @Override
    public String describe() {
        return "Boarding boat phase=" + phase;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return switch (phase) {
            case FIND_BOAT -> 0.20D;
            case APPROACH -> 0.55D;
            case BOARD -> 0.85D;
        };
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.FIND_BOAT;
        boatId = null;
        shore = null;
        nextRepathTick = 0;
        boardAttempts = 0;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (BoatSupport.mountedBoat(bot).isPresent()) {
            complete();
            return;
        }
        if (elapsed > MAX_TICKS) {
            fail("boat_board_timeout:" + phase.name().toLowerCase());
            return;
        }
        switch (phase) {
            case FIND_BOAT -> findBoat(bot);
            case APPROACH -> approach(bot);
            case BOARD -> board(bot);
        }
    }

    private void findBoat(AIPlayerEntity bot) {
        AbstractBoatEntity boat = requestedBoatId == null
                ? BoatSupport.nearbyEmptyBoat(bot).orElse(null)
                : BoatSupport.boatById(bot, requestedBoatId).orElse(null);
        if (boat == null) {
            fail(requestedBoatId == null ? "no_nearby_empty_boat" : "requested_boat_unavailable");
            return;
        }
        boatId = boat.getUuid();
        shore = BoatSupport.nearestBoardingShore(bot, boat).orElse(null);
        if (bot.squaredDistanceTo(boat) <= BoatSupport.BOARD_REACH * BoatSupport.BOARD_REACH) {
            phase = Phase.BOARD;
            return;
        }
        if (shore == null) {
            fail("no_reachable_boat_shore");
            return;
        }
        phase = Phase.APPROACH;
    }

    private void approach(AIPlayerEntity bot) {
        AbstractBoatEntity boat = BoatSupport.boatById(bot, boatId).orElse(null);
        if (boat == null) {
            fail("boat_unavailable");
            return;
        }
        if (bot.squaredDistanceTo(boat) <= BoatSupport.BOARD_REACH * BoatSupport.BOARD_REACH) {
            bot.getActionPack().stopAll();
            phase = Phase.BOARD;
            return;
        }
        if (shore == null) {
            phase = Phase.FIND_BOAT;
            return;
        }
        if (elapsed < nextRepathTick) {
            return;
        }
        ActionResult path = bot.getActionPack().startPathTo(shore);
        if (path.isFailed()) {
            ActionResult walk = bot.getActionPack().startWalkTo(shore.toCenterPos(), 1.0D);
            if (walk.isFailed()) {
                phase = Phase.FIND_BOAT;
            }
        }
        nextRepathTick = elapsed + REPATH_TICKS;
    }

    private void board(AIPlayerEntity bot) {
        AbstractBoatEntity boat = BoatSupport.boatById(bot, boatId).orElse(null);
        if (boat == null) {
            fail("boat_unavailable");
            return;
        }
        ActionResult result = BoatAction.boardBoat(bot, boat);
        boardAttempts++;
        if (result.isFailed() && boardAttempts >= MAX_BOARD_ATTEMPTS) {
            fail("boat_board_failed:" + result.reason());
            return;
        }
        if (!result.isFailed() && boardAttempts >= MAX_BOARD_ATTEMPTS
                && bot.getVehicle() != boat) {
            fail("boat_board_not_confirmed");
        }
    }
}

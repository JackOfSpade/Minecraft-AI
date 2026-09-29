package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BoatAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

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
    // Boats that must never be picked by the "nearest empty boat" search (e.g. one a follower just
    // gave up on because it was beached); ignored when a specific boat was requested.
    private final Set<UUID> excludedBoats;
    private Phase phase = Phase.FIND_BOAT;
    private UUID boatId;
    private BlockPos shore;
    private int nextRepathTick;
    private int boardAttempts;

    /** Boards the nearest nearby empty boat. */
    public BoardBoatTask() {
        this((UUID) null);
    }

    /** Boards the nearest nearby empty boat that is not in {@code excludedBoats}. */
    public BoardBoatTask(Set<UUID> excludedBoats) {
        this.requestedBoatId = null;
        this.excludedBoats = excludedBoats;
    }

    /** Boards a particular boat created by a preceding launch task. */
    public BoardBoatTask(UUID requestedBoatId) {
        this.requestedBoatId = requestedBoatId;
        this.excludedBoats = Set.of();
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
        AbstractBoat boat = requestedBoatId == null
                ? BoatSupport.nearbyEmptyBoat(bot, excludedBoats).orElse(null)
                : BoatSupport.boatById(bot, requestedBoatId).orElse(null);
        if (boat == null) {
            fail(requestedBoatId == null ? "no_nearby_empty_boat" : "requested_boat_unavailable");
            return;
        }
        boatId = boat.getUUID();
        shore = BoatSupport.nearestBoardingShore(bot, boat).orElse(null);
        if (bot.distanceToSqr(boat) <= BoatSupport.BOARD_REACH * BoatSupport.BOARD_REACH) {
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
        AbstractBoat boat = BoatSupport.boatById(bot, boatId).orElse(null);
        if (boat == null) {
            // "boat_unavailable" is the same reason from both approach() and board(); record the
            // phase it was lost in, since that is otherwise the only difference between them.
            BotLog.warn(LogCategory.TASK, bot, "board_boat_target_lost", "phase", "approach");
            fail("boat_unavailable");
            return;
        }
        if (bot.distanceToSqr(boat) <= BoatSupport.BOARD_REACH * BoatSupport.BOARD_REACH) {
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
            ActionResult walk = bot.getActionPack().startWalkTo(shore.getCenter(), 1.0D);
            if (walk.isFailed()) {
                phase = Phase.FIND_BOAT;
            }
        }
        nextRepathTick = elapsed + REPATH_TICKS;
    }

    private void board(AIPlayerEntity bot) {
        AbstractBoat boat = BoatSupport.boatById(bot, boatId).orElse(null);
        if (boat == null) {
            BotLog.warn(LogCategory.TASK, bot, "board_boat_target_lost", "phase", "board");
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
            // The board interaction itself reported success, yet the bot never actually ended up
            // in the boat -- worth the actual vehicle state here, since "not_confirmed" alone
            // does not say what happened instead.
            BotLog.warn(LogCategory.TASK, bot, "board_boat_vehicle_mismatch",
                    "vehicle", bot.getVehicle() == null ? "none" : bot.getVehicle().getType());
            fail("boat_board_not_confirmed");
        }
    }
}

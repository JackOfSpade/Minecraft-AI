package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BoatAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Crafts (when possible), launches, and optionally boards a boat using ordinary player actions.
 * The task stays local: it will not tunnel or roam looking for a river/ocean.
 */
public final class BoatLaunchTask extends AbstractTask {
    private static final int MAX_TICKS = 1_200;
    private static final int MAX_LAUNCH_ATTEMPTS = 3;
    private static final int MAX_BOARD_ATTEMPTS = 3;
    private static final int REPATH_TICKS = 40;
    // Water that is visible but not yet beside a usable shore (a lake seen from a few blocks up the
    // bank): walk toward it and look for a launch site again every few ticks.
    private static final int WATER_APPROACH_RESCAN_TICKS = 15;
    private static final int MAX_WATER_APPROACHES = 6;

    private enum Phase {
        ENSURE_BOAT,
        FIND_WATER,
        APPROACH_WATER,
        APPROACH_SHORE,
        LAUNCH,
        BOARD
    }

    private final boolean boardAfterLaunch;
    private Phase phase = Phase.ENSURE_BOAT;
    private CraftTask craftTask;
    private BoatSupport.LaunchSite launchSite;
    private UUID launchedBoatId;
    private int nextRepathTick;
    private int nextRescanTick;
    private BlockPos waterApproach;
    private int waterApproaches;
    private int launchAttempts;
    private int boardAttempts;
    private String lastProblem = "";

    /** Creates a launch-only task. */
    public BoatLaunchTask() {
        this(false);
    }

    /** @param boardAfterLaunch whether the bot should board its newly launched boat. */
    public BoatLaunchTask(boolean boardAfterLaunch) {
        this.boardAfterLaunch = boardAfterLaunch;
    }

    @Override
    public String name() {
        return "launch_boat";
    }

    @Override
    public String describe() {
        return "Launching boat phase=" + phase
                + (lastProblem.isBlank() ? "" : " note=" + lastProblem);
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return switch (phase) {
            case ENSURE_BOAT -> 0.10D;
            case FIND_WATER -> 0.25D;
            case APPROACH_WATER -> 0.35D;
            case APPROACH_SHORE -> 0.50D;
            case LAUNCH -> 0.75D;
            case BOARD -> 0.90D;
        };
    }

    public Optional<UUID> launchedBoatId() {
        return Optional.ofNullable(launchedBoatId);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.ENSURE_BOAT;
        craftTask = null;
        launchSite = null;
        launchedBoatId = null;
        nextRepathTick = 0;
        nextRescanTick = 0;
        waterApproach = null;
        waterApproaches = 0;
        launchAttempts = 0;
        boardAttempts = 0;
        lastProblem = "";
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > MAX_TICKS) {
            fail("boat_launch_timeout:" + phase.name().toLowerCase());
            return;
        }
        switch (phase) {
            case ENSURE_BOAT -> ensureBoat(bot);
            case FIND_WATER -> findWater(bot);
            case APPROACH_WATER -> approachWater(bot);
            case APPROACH_SHORE -> approachShore(bot);
            case LAUNCH -> launch(bot);
            case BOARD -> board(bot);
        }
    }

    private void ensureBoat(AIPlayerEntity bot) {
        if (BoatSupport.boatSlot(bot).isPresent()) {
            phase = Phase.FIND_WATER;
            return;
        }
        if (craftTask == null) {
            Item candidate = BoatSupport.craftableBoat(bot).orElse(null);
            if (candidate == null) {
                fail("need_boat_or_five_matching_planks");
                return;
            }
            craftTask = new CraftTask(candidate, 1);
            craftTask.start(bot);
            BotLog.action(bot, "boat_craft_started", "item", candidate);
        }
        craftTask.tick(bot);
        if (craftTask.state() == TaskState.COMPLETED) {
            craftTask = null;
            if (BoatSupport.boatSlot(bot).isEmpty()) {
                fail("boat_craft_completed_without_boat");
                return;
            }
            phase = Phase.FIND_WATER;
        } else if (craftTask.state() == TaskState.FAILED) {
            fail("boat_craft_failed:" + craftTask.failureReason());
        }
    }

    private void findWater(AIPlayerEntity bot) {
        launchSite = BoatSupport.findLaunchSite(bot).orElse(null);
        if (launchSite == null) {
            // Water that is visible but not yet beside a shore: walk up to it first (near water is
            // only visible from close by, so it is found again once the bot is on the bank).
            waterApproach = waterApproaches >= MAX_WATER_APPROACHES
                    ? null : BoatSupport.findWaterApproach(bot).orElse(null);
            if (waterApproach == null) {
                fail("no_nearby_water_shore");
                return;
            }
            waterApproaches++;
            BotLog.action(bot, "boat_water_approach", "cell", waterApproach.toShortString());
            nextRepathTick = 0;
            nextRescanTick = elapsed + WATER_APPROACH_RESCAN_TICKS;
            phase = Phase.APPROACH_WATER;
            return;
        }
        nextRepathTick = 0;
        phase = Phase.APPROACH_SHORE;
    }

    private void approachWater(AIPlayerEntity bot) {
        if (elapsed >= nextRescanTick) {
            nextRescanTick = elapsed + WATER_APPROACH_RESCAN_TICKS;
            launchSite = BoatSupport.findLaunchSite(bot).orElse(null);
            if (launchSite != null) {
                bot.getActionPack().stopAll();
                nextRepathTick = 0;
                phase = Phase.APPROACH_SHORE;
                return;
            }
        }
        boolean arrived = bot.getEntityPos().squaredDistanceTo(waterApproach.toCenterPos()) <= 2.25D;
        if (arrived) {
            bot.getActionPack().stopAll();
            phase = Phase.FIND_WATER;
            return;
        }
        if (elapsed < nextRepathTick) {
            return;
        }
        ActionResult path = bot.getActionPack().startPathTo(waterApproach);
        if (path.isFailed()) {
            ActionResult walk = bot.getActionPack().startWalkTo(waterApproach.toCenterPos(), 1.0D);
            if (walk.isFailed()) {
                lastProblem = "water_unreachable:" + path.reason();
                phase = Phase.FIND_WATER;
            }
        }
        nextRepathTick = elapsed + REPATH_TICKS;
    }

    private void approachShore(AIPlayerEntity bot) {
        if (launchSite == null || !BoatSupport.isWater(bot.getEntityWorld(), launchSite.water())) {
            phase = Phase.FIND_WATER;
            return;
        }
        double distanceSquared = bot.getEntityPos().squaredDistanceTo(launchSite.shore().toCenterPos());
        if (distanceSquared <= 2.25D) {
            bot.getActionPack().stopAll();
            phase = Phase.LAUNCH;
            return;
        }
        if (elapsed < nextRepathTick) {
            return;
        }
        ActionResult path = bot.getActionPack().startPathTo(launchSite.shore());
        if (path.isFailed()) {
            ActionResult walk = bot.getActionPack().startWalkTo(launchSite.shore().toCenterPos(), 1.0D);
            if (walk.isFailed()) {
                lastProblem = "shore_unreachable:" + path.reason();
                // Give up on this shore and go back to FIND_WATER to try another one: if it
                // eventually times out in APPROACH_SHORE, "boat_launch_timeout:approach_shore"
                // alone can't tell whether it's stuck retrying the same shore repeatedly or
                // tried several shores and none were reachable -- this log leaves a trace
                // every time it gives up.
                BotLog.action(bot, "boat_shore_abandoned", "shore", launchSite.shore().toShortString(),
                        "reason", path.reason());
                launchSite = null;
                phase = Phase.FIND_WATER;
            }
        }
        nextRepathTick = elapsed + REPATH_TICKS;
    }

    private void launch(AIPlayerEntity bot) {
        if (launchSite == null || !BoatSupport.isWater(bot.getEntityWorld(), launchSite.water())) {
            phase = Phase.FIND_WATER;
            return;
        }
        OptionalInt slot = BoatSupport.boatSlot(bot);
        if (slot.isEmpty() || InventoryAction.equipFromSlot(bot, slot.getAsInt()) < 0) {
            fail("boat_missing_before_launch");
            return;
        }
        BoatAction.Placement placement = BoatAction.placeBoatInWater(bot, launchSite.water());
        if (placement.success()) {
            AbstractBoatEntity boat = placement.boat().orElseThrow();
            launchedBoatId = boat.getUuid();
            BotLog.action(bot, "boat_launch_complete", "boat_id", launchedBoatId,
                    "water", launchSite.water().toShortString());
            if (boardAfterLaunch) {
                phase = Phase.BOARD;
            } else {
                complete();
            }
            return;
        }
        launchAttempts++;
        lastProblem = placement.reason();
        if (launchAttempts >= MAX_LAUNCH_ATTEMPTS) {
            fail("boat_launch_failed:" + placement.reason());
            return;
        }
        launchSite = null;
        phase = Phase.FIND_WATER;
    }

    private void board(AIPlayerEntity bot) {
        if (BoatSupport.mountedBoat(bot)
                .map(boat -> boat.getUuid().equals(launchedBoatId))
                .orElse(false)) {
            complete();
            return;
        }
        AbstractBoatEntity boat = BoatSupport.boatById(bot, launchedBoatId).orElse(null);
        if (boat == null) {
            fail("launched_boat_unavailable");
            return;
        }
        ActionResult result = BoatAction.boardBoat(bot, boat);
        boardAttempts++;
        if (result.isFailed()) {
            lastProblem = result.reason();
            if (boardAttempts >= MAX_BOARD_ATTEMPTS) {
                fail("boat_board_failed:" + result.reason());
            }
            return;
        }
        if (bot.getVehicle() == boat) {
            // BoatAction.boardBoat() mounts the bot synchronously on a successful interaction, so
            // a non-failed result usually means the bot is already riding by now -- complete
            // immediately instead of waiting for next tick's top-of-method mountedBoat() check.
            complete();
            return;
        }
        if (boardAttempts >= MAX_BOARD_ATTEMPTS) {
            fail("boat_board_not_confirmed");
        }
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        if (craftTask != null && craftTask.state() == TaskState.RUNNING) {
            craftTask.abort(bot);
        }
        super.onAbort(bot);
    }
}

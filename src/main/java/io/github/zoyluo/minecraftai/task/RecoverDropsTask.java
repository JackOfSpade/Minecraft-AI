package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import net.minecraft.core.BlockPos;

/**
 * Corpse-run recovery: after respawning, rush back to the death point and pick up the
 * dropped gear/items. The natural first instinct after a real player dies - failing to
 * recover means gear is wiped and has to be remade from scratch, and dying once deep in
 * a mine is an efficiency cliff.
 *
 * Design notes:
 *  - Dropped items despawn after 6000t (5 minutes): the total budget is counted from the
 *    moment of death; if we can't make it in time, don't bother going at all;
 *  - Auto-recovery only accepts short, shallow routes that DangerWatcher has vetted. A
 *    pathing failure gets a bounded retry and then a typed fail - we never let a naked bot
 *    blind-dig from the spawn point toward the deep mine;
 *  - Completion semantics are lenient: once the pickup window at the site ends, we complete()
 *    regardless (even with zero pickups - drops already burned up in lava is the normal case).
 *    We just report the result and move on without dwelling on it; failing to recover the
 *    drops isn't the task's fault, so don't let the goal layer replan-loop over it.
 */
public final class RecoverDropsTask extends AbstractTask {
    private static final double ARRIVE_SQUARED = 9.0D;   // within 3 blocks counts as arrived, start picking up
    private static final int MAX_ELAPSED = 3600;          // total travel time cutoff: 3 minutes
    private static final int PICKUP_WINDOW = 100;         // 5s pickup window after arrival (drops are scattered, needs a few sweep passes)
    private static final long DESPAWN_BUDGET = 5600L;     // drops vanish at 6000t; leave a 400t margin
    private static final int ROUTE_RETRY_TICKS = 20;
    private static final int ROUTE_FAILURE_LIMIT = 3;
    private static final int ROUTE_NO_PROGRESS_LIMIT = 100;

    private final BlockPos deathPos;
    private final long deathTick;
    private int arrivedTick = -1;
    private int repathCooldown;
    private int routeFailures;
    private int lastRouteProgressTick;
    private double initialDistance;
    private double bestDistance;

    public RecoverDropsTask(BlockPos deathPos, long deathTick) {
        this.deathPos = deathPos.immutable();
        this.deathTick = deathTick;
    }

    @Override
    public String name() {
        return "recover_drops";
    }

    @Override
    public String describe() {
        return "Recovering drops at " + deathPos.getX() + "," + deathPos.getY() + "," + deathPos.getZ();
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        if (arrivedTick >= 0) {
            return 0.8D;
        }
        if (initialDistance <= 0.0D) {
            return 0.0D;
        }
        return Math.min(0.7D,
                0.7D * (1.0D - Math.min(1.0D, bestDistance / initialDistance)));
    }

    @Override
    public boolean isWaiting() {
        return arrivedTick >= 0; // standing still during the pickup window on-site; don't let StuckWatcher misfire on this
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        if (bot.level().getServer().getTickCount() - deathTick > DESPAWN_BUDGET) {
            fail("drops_expired");
            return;
        }
        initialDistance = distance(bot);
        bestDistance = initialDistance;
        lastRouteProgressTick = 0;
        routeFailures = 0;
        startApproach(bot);
    }

    @Override
    protected void onResume(AIPlayerEntity bot) {
        startApproach(bot);
    }

    private void startApproach(AIPlayerEntity bot) {
        ActionResult walk = bot.getActionPack().startPathTo(deathPos);
        if (walk.isFailed()) {
            routeFailures++;
            BotLog.action(bot, "recover_route_retry",
                    "attempt", routeFailures, "reason", walk.reason(),
                    "from", bot.blockPosition().toShortString(), "to", deathPos.toShortString());
            if (routeFailures >= ROUTE_FAILURE_LIMIT) {
                fail("recover_route_unreachable:" + walk.reason());
            }
        }
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        // (Drowning fuse handling has been folded into the unified SurvivalGuard layer - it now
        //  covers the death loop where a corpse-run to an underwater death point drowns the bot
        //  again; the knowledge base flags the area as dangerous after the second death, and the
        //  reflex gate talks it out of a third attempt.)
        // Pick up along the way: drops may have been scattered onto the path by water flow or an explosion
        HarvestCore.forcePickupNearbyAnyOf(bot, null, 4.0D, 2.0D);

        if (arrivedTick >= 0) {
            HarvestCore.sweepPickupAnyOf(bot, null, 10.0D, 6);
            if (elapsed - arrivedTick >= PICKUP_WINDOW) {
                // Drop radar: how many are still left uncollected when the window ends (=0 means truly clean; >0 means something blocked pickup)
                CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "recover_drops_report");
                var leftovers = bot.level().getEntitiesOfClass(
                        net.minecraft.world.entity.item.ItemEntity.class,
                        bot.getBoundingBox().inflate(32.0D, 16.0D, 32.0D),
                        e -> ObservableWorldQuery.canObserveEntity(bot, e));
                BotLog.action(bot, "recover_drops_done", "at", deathPos.toShortString(),
                        "leftover", leftovers.size(),
                        "nearest", leftovers.isEmpty() ? "-" : leftovers.get(0).blockPosition().toShortString());
                complete();
            }
            return;
        }

        if (bot.blockPosition().distSqr(deathPos) <= ARRIVE_SQUARED) {
            arrivedTick = elapsed;
            bot.getActionPack().stopMovement();
            BotLog.action(bot, "recover_drops_arrived", "at", deathPos.toShortString(),
                    "elapsed", elapsed);
            return;
        }

        double remaining = distance(bot);
        if (remaining + 0.5D < bestDistance) {
            bestDistance = remaining;
            lastRouteProgressTick = elapsed;
            routeFailures = 0;
        }

        if (bot.level().getServer().getTickCount() - deathTick > DESPAWN_BUDGET) {
            fail("drops_expired_enroute"); // not going to make it in time, cut losses now
            return;
        }
        if (elapsed > MAX_ELAPSED) {
            fail("recover_timeout");
            return;
        }
        // Pathing broke off (executor idle and not yet arrived) -> bounded re-issue. Progress only
        // counts real distance improvement; elapsed time must not be allowed to pass itself off as
        // corpse-run progress and drag things out to the 3-minute total cutoff.
        if (bot.getActionPack().isPathExecutorIdle()
                && elapsed - lastRouteProgressTick > ROUTE_NO_PROGRESS_LIMIT) {
            fail("recover_route_unreachable:no_progress");
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()
                && elapsed - repathCooldown > ROUTE_RETRY_TICKS) {
            repathCooldown = elapsed;
            startApproach(bot);
        }
    }

    private double distance(AIPlayerEntity bot) {
        return Math.sqrt(bot.blockPosition().distSqr(deathPos));
    }
}

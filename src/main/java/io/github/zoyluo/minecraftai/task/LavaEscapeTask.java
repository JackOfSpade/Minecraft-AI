package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.LookAction;
import io.github.zoyluo.minecraftai.action.MaterialPalette;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Lava self-rescue (P0): dispatched by DangerWatcher when the bot is stuck in lava,
 * to pull it out of the lava.
 *
 * Historical defect: SurvivalGuard detecting in_lava only aborted the working task, with a
 * comment saying "yield to DangerWatcher for escape/extinguishing" -- but the escape logic
 * was never implemented. The bot kept burning to death while soaking in lava (observed in
 * real_diamond: diving to Y-58 breached a lava pocket, hp went 20->16->...->death, failing
 * at step 14/15). This task fills that gap.
 *
 * Strategy (human-like lava escape): (1) hold jump every tick = slowly rise in lava, keeping
 * the head above the lava surface to take less burn damage and make it easier to climb onto
 * the bank; (2) walk toward the nearest non-lava foothold (the bank); (3) when no bank is
 * reachable, place a block at the feet as a stepping platform and jump onto it to escape the
 * lava. Escaping the lava completes the task. SurvivalGuard exempts this task (otherwise
 * guard_in_lava would interrupt the self-rescue itself).
 */
public final class LavaEscapeTask extends AbstractTask {
    private static final int ESCAPE_RADIUS = 5;   // horizontal radius for finding the bank
    private static final int MAX_ELAPSED = 200;   // still not out of lava after 10s -> fail (DangerWatcher redispatches next tick, retry continues)
    private static final int PLACE_INTERVAL = 4;  // rate limit for placing platform blocks

    private BlockPos target;
    private int lastPlaceTick = -100;

    @Override
    public String name() {
        return "lava_escape";
    }

    @Override
    public String describe() {
        return "Lava escape -> " + (target == null ? "(scan)" : target.toShortString());
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.9D, elapsed / 40.0D);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        bot.getActionPack().stopAll(); // drop the current job's path/mining, focus fully on the lava escape
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        // MAX_ELAPSED is a bound on the whole task, not just the in-lava phase: check it first
        // so a bot that leaves lava but never grounds (e.g. floating in adjacent water, or
        // isOnGround() staying false on clipped terrain) still fails closed instead of waiting
        // "one more tick" forever with only the generic, position-based StuckWatcher as backstop.
        if (elapsed > MAX_ELAPSED) {
            fail("lava_escape_timeout");
            return;
        }

        // Escaping lava means success: complete once out of lava and standing stably (if out
        // of lava but not yet grounded, wait one more tick, to avoid stepping wrong at the
        // bank edge and falling back in).
        // Still burning after the exit is handed to DangerWatcher's FireExtinguishTask on its next scan
        // (water bucket, observed water, fire block) instead of being left to burn out for ~15 s.
        if (!bot.isInLava()) {
            bot.getActionPack().setJumping(false);
            bot.getActionPack().setForward(0.0F);
            if (bot.onGround()) {
                bot.getActionPack().stopAll();
                BotLog.action(bot, "lava_escape_done", "pos", bot.blockPosition().toShortString());
                complete();
            }
            return;
        }

        var world = bot.level();
        // Keep rising: hold jump in lava to slowly ascend.
        bot.getActionPack().setJumping(true);

        // Find/reuse a player-eye-proven dry foothold. Loaded terrain is not an escape route: the
        // actual route is still admitted by the observed Baritone boundary below.
        if (target == null
                || bot.blockPosition().closerThan(target, 1.6D)
                || !observedStandable(bot, world, target)) {
            Optional<BlockPos> bank = nearestObservedStandable(bot, world, bot.blockPosition());
            target = bank.orElse(null);
        }

        if (target != null) {
            // A named bank is only a visible destination candidate. Baritone re-proves every
            // route/action cell, so this cannot turn the rescue into a straight-line traversal
            // through unobserved lava or stone.
            if (bot.getActionPack().isPathExecutorIdle()) {
                ActionResult route = bot.getActionPack().startPathTo(target);
                if (route.isSuccess() || route.isInProgress()) {
                    return;
                }
                BotLog.action(bot, "lava_escape_baritone_refused",
                        "target", target.toShortString(), "reason", route.reason());
                target = null;
            }
            if (target != null) {
                return;
            }
        }

        // No bank within 5 blocks around -> self-rescue by placing a block: place one block
        // horizontally at the feet as a stepping platform (replacing flowing lava), then jump
        // onto it to escape the lava.
        if (elapsed - lastPlaceTick >= PLACE_INTERVAL) {
            OptionalInt slot = MaterialPalette.pickSacrificialBlockSlot(bot);
            if (slot.isPresent()) {
                InventoryAction.equipFromSlot(bot, slot.getAsInt());
                BlockPos feet = bot.blockPosition();
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos side = feet.relative(dir);
                    ActionResult placed = BuildAction.placeBlockAt(bot, side);
                    if (placed.isInProgress()) {
                        return;
                    }
                    if (placed.isSuccess()) {
                        target = side; // platform built -> climb toward it
                        BotLog.action(bot, "lava_escape_platform", "at", side.toShortString());
                        break;
                    }
                }
            }
            lastPlaceTick = elapsed;
        }
    }

    private static boolean observedStandable(AIPlayerEntity bot, net.minecraft.server.level.ServerLevel world,
                                             BlockPos candidate) {
        return ObservableWorldQuery.canObserveCell(bot, candidate)
                && ObservableWorldQuery.canObserveCell(bot, candidate.above())
                && ObservableWorldQuery.canObserveBlockCellFace(bot, candidate.below())
                && Standability.isStandableFresh(world, candidate);
    }

    private static Optional<BlockPos> nearestObservedStandable(AIPlayerEntity bot,
                                                                 net.minecraft.server.level.ServerLevel world,
                                                                 BlockPos origin) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dy = -2; dy <= 3; dy++) {
            for (int dx = -ESCAPE_RADIUS; dx <= ESCAPE_RADIUS; dx++) {
                for (int dz = -ESCAPE_RADIUS; dz <= ESCAPE_RADIUS; dz++) {
                    BlockPos candidate = origin.offset(dx, dy, dz);
                    double distance = candidate.distSqr(origin);
                    if (distance >= bestDistance || !observedStandable(bot, world, candidate)) {
                        continue;
                    }
                    best = candidate.immutable();
                    bestDistance = distance;
                }
            }
        }
        return Optional.ofNullable(best);
    }
}

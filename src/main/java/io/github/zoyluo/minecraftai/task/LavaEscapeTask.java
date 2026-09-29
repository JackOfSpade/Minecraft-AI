package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.LookAction;
import io.github.zoyluo.minecraftai.action.MaterialPalette;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
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

        // Find/reuse the nearest non-lava foothold (rescan on arrival or when it becomes invalid).
        if (target == null
                || bot.blockPosition().closerThan(target, 1.6D)
                || !Standability.isStandable(world, target)) {
            Optional<BlockPos> bank = Standability.findNearestStandable(world, bot.blockPosition(), ESCAPE_RADIUS, 2, 3);
            target = bank.orElse(null);
        }

        if (target != null) {
            // Rush toward the bank edge: look at it + walk straight there (use walk for
            // short-distance lava escape, not pathfinding -- A* through lava would fail).
            LookAction.lookAt(bot, Vec3.atCenterOf(target));
            if (bot.getActionPack().isWalkToIdle()) {
                bot.getActionPack().startWalkTo(Vec3.atCenterOf(target));
            }
            return;
        }

        // No bank within 5 blocks around -> self-rescue by placing a block: place one block
        // horizontally at the feet as a stepping platform (replacing flowing lava), then jump
        // onto it to escape the lava.
        if (elapsed - lastPlaceTick >= PLACE_INTERVAL) {
            lastPlaceTick = elapsed;
            OptionalInt slot = MaterialPalette.pickSacrificialBlockSlot(bot);
            if (slot.isPresent()) {
                InventoryAction.equipFromSlot(bot, slot.getAsInt());
                BlockPos feet = bot.blockPosition();
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos side = feet.relative(dir);
                    if (!BuildAction.placeBlockAt(bot, side).isFailed()) {
                        target = side; // platform built -> climb toward it
                        BotLog.action(bot, "lava_escape_platform", "at", side.toShortString());
                        break;
                    }
                }
            }
        }
    }
}

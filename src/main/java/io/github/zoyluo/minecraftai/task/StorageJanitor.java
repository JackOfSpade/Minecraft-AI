package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ContainerAction;
import io.github.zoyluo.minecraftai.action.InventoryPolicy;
import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.goal.GoalExecutor;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;

/**
 * Keeps a pack-mule bot from filling up with filler blocks. When the main inventory is nearly full
 * and the junk surplus (see {@link InventoryPolicy}) is non-empty:
 * <ul>
 *   <li>while following, and a usable storage block of the player's base is already within reach
 *       and sight, it stows the surplus into it on the spot without leaving the follow;</li>
 *   <li>while idle, and such a container is remembered or in sight within the configured radius,
 *       it walks over and stows the surplus (a normal {@link ContainerTask});</li>
 *   <li>otherwise nothing changes: the existing junk-dropping paths (crafting/smelting capacity
 *       recovery) keep working exactly as before.</li>
 * </ul>
 * Only the surplus beyond the throwaway budget moves, and only into a container the bot has itself
 * opened before or that stands at its remembered base.
 */
public final class StorageJanitor {
    public static final StorageJanitor INSTANCE = new StorageJanitor();
    private static final int CHECK_INTERVAL_TICKS = 100;
    private static final int NOTHING_TO_DO_BACKOFF_TICKS = 1200;
    private static final int IN_PLACE_RADIUS = 6;
    private static final int MAX_STACKS_PER_CALL = 8;
    /**
     * After a player-requested gather or mine task completes, the junk janitor stays out of the way
     * for this long (two game minutes): what the bot just collected (dirt, cobblestone) is what the
     * player asked for and is reserved, not surplus to stow at once. See {@link InventoryPolicy}.
     */
    static final int USER_GATHER_GRACE_TICKS = 2400;

    private final Map<UUID, Integer> nextCheck = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> gatherGraceUntil = new ConcurrentHashMap<>();

    private StorageJanitor() {
    }

    /** A user-origin gather/mine task just completed: reserve what it collected for a grace period. */
    public void noteUserGather(AIPlayerEntity bot, int tick) {
        gatherGraceUntil.put(bot.getUUID(), tick + USER_GATHER_GRACE_TICKS);
    }

    /** Returns true when it started a task (the caller must not also run idle assignment this tick). */
    public boolean tickBot(AIPlayerEntity bot) {
        MinecraftAiConfig.Storage config = MinecraftAiConfig.get().storage();
        if (!config.autoStowJunkEnabled()
                || io.github.zoyluo.minecraftai.inventory.BotInventoryScreenHandler.isScreenOpen(bot)) {
            return false;
        }
        int tick = bot.level().getServer().getTickCount();
        Integer grace = gatherGraceUntil.get(bot.getUUID());
        if (grace != null) {
            if (tick < grace) {
                return false; // the player just asked for these items: not junk yet
            }
            gatherGraceUntil.remove(bot.getUUID(), grace);
        }
        Integer due = nextCheck.get(bot.getUUID());
        if (due != null && tick < due) {
            return false;
        }
        nextCheck.put(bot.getUUID(), tick + CHECK_INTERVAL_TICKS);
        if (!InventoryPolicy.nearlyFull(bot) || InventoryPolicy.nextStow(bot).isEmpty()) {
            return false;
        }
        Optional<Task> active = TaskManager.INSTANCE.getActive(bot);
        if (active.isPresent()) {
            if (active.get() instanceof FollowTask) {
                stowInPlace(bot);
            }
            return false;
        }
        if (TaskManager.INSTANCE.isUserPaused(bot)
                || TaskManager.INSTANCE.hasPaused(bot)
                || GoalExecutor.INSTANCE.hasActivePlan(bot)
                || bot.getActionPack().hasActiveActions()
                || BrainCoordinator.INSTANCE.status(bot).busy()) {
            return false;
        }
        Optional<BlockPos> target = StorageTargets.junkStowTarget(bot, config.stowRadius(), false);
        if (target.isEmpty()) {
            nextCheck.put(bot.getUUID(), tick + NOTHING_TO_DO_BACKOFF_TICKS);
            return false;
        }
        BotLog.action(bot, "junk_stow_start", "target", target.get().toShortString(),
                "free_slots", InventoryPolicy.freeMainSlots(bot));
        TaskManager.INSTANCE.assign(bot, ContainerTask.depositJunkTrusted(target.get()),
                TaskOrigin.of(TaskOrigin.Kind.SYSTEM_BACKGROUND, "junk_stow"));
        return true;
    }

    /** Follow case: no walking, only a container already in reach and sight. */
    private void stowInPlace(AIPlayerEntity bot) {
        Optional<BlockPos> target = StorageTargets.junkStowTarget(bot, IN_PLACE_RADIUS, true);
        if (target.isEmpty()) {
            return;
        }
        BlockPos pos = target.get();
        Container container = ContainerAction.open(bot, pos, false).orElse(null);
        if (container == null) {
            return;
        }
        int stowed = 0;
        for (int stacks = 0; stacks < MAX_STACKS_PER_CALL; stacks++) {
            Optional<InventoryPolicy.Stow> stow = InventoryPolicy.nextStow(bot);
            if (stow.isEmpty()) {
                break;
            }
            var item = stow.get().item();
            ContainerAction.TransferResult result = ContainerAction.deposit(bot, pos, container,
                    stack -> stack.is(item) && !InventoryPolicy.isProtected(stack), stow.get().count());
            if (!result.movedAny()) {
                break;
            }
            stowed += result.count();
        }
        if (stowed > 0) {
            BotLog.action(bot, "junk_stow_in_place", "pos", pos.toShortString(), "count", stowed);
        }
    }

    public void forget(AIPlayerEntity bot) {
        nextCheck.remove(bot.getUUID());
        gatherGraceUntil.remove(bot.getUUID());
    }
}

package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.TowerDescent;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogFields;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who answers for the pillar a bot stands on. The task that builds one takes it down itself while it runs
 * ({@link TowerDescent}); but a task can end first, stopped by the player, replaced, timed out or failed, with the
 * bot still up on its tower. A route steps down at most the safe fall and never breaks the bot's own footing, so
 * a tall tower with no one to take it down would strand the bot for good. The custody holds the tower of every bot
 * that has one, and when its owner has ended it takes the tower down before the bot's next task goes on, the way a
 * player who is told to stop climbs down first.
 *
 * <p>An owner that is still running, or only paused (a fight, a user pause), keeps its tower: it resumes the
 * descent itself. A safety task that is active keeps the bot's attention too, and the tower waits for it.</p>
 */
public final class TowerCustody {
    public static final TowerCustody INSTANCE = new TowerCustody();

    private record Held(Task owner, TowerDescent tower) {
    }

    private final Map<UUID, Held> held = new ConcurrentHashMap<>();
    /** The bots whose orphaned tower was being taken down on the last tick. */
    private volatile Set<UUID> descending = Set.of();

    private TowerCustody() {
    }

    /** {@code owner} has built the pillar {@code tower} describes and answers for it until it releases it. */
    public void hold(AIPlayerEntity bot, Task owner, TowerDescent tower) {
        held.put(bot.getUUID(), new Held(owner, tower));
    }

    /** The owner took {@code tower} down (or gave it up): nothing is left to answer for. */
    public void release(AIPlayerEntity bot, TowerDescent tower) {
        held.computeIfPresent(bot.getUUID(), (uuid, current) -> current.tower() == tower ? null : current);
    }

    /**
     * Advances the descent of every orphaned tower by a tick. Returns the bots that are busy taking one down:
     * their active task waits, because it would walk off or build on a tower that is still standing.
     */
    Set<UUID> tickOrphans() {
        Set<UUID> busy = new HashSet<>();
        for (Map.Entry<UUID, Held> entry : held.entrySet()) {
            Optional<AIPlayerEntity> found = AIPlayerManager.INSTANCE.getByUuid(entry.getKey());
            Held current = entry.getValue();
            if (found.isEmpty() || !found.get().isAlive()) {
                held.remove(entry.getKey(), current);
                continue;
            }
            AIPlayerEntity bot = found.get();
            if (!ended(current.owner())
                    || TaskManager.INSTANCE.isActiveSafety(bot)
                    || NavSafetyNet.INSTANCE.isWaterRescueActive(bot)) {
                continue;
            }
            TowerDescent.Status status = current.tower().tick(bot);
            if (status == TowerDescent.Status.DESCENDING) {
                busy.add(entry.getKey());
                continue;
            }
            held.remove(entry.getKey(), current);
            if (status == TowerDescent.Status.FAILED) {
                BotLog.action(bot, "tower_orphan_descent_failed", "owner", current.owner().name(),
                        "reason", current.tower().failureReason(), "at", LogFields.pos(bot.blockPosition()));
            } else if (current.tower().broken() > 0) {
                BotLog.action(bot, "tower_orphan_descended", "owner", current.owner().name(),
                        "blocks", current.tower().broken(), "at", LogFields.pos(bot.blockPosition()));
            }
        }
        descending = Set.copyOf(busy);
        return busy;
    }

    /** True while the bot's task is held back for an orphaned tower: it is not a task standing still for no reason. */
    public boolean isDescending(AIPlayerEntity bot) {
        return descending.contains(bot.getUUID());
    }

    /** Forgets a bot that is gone, so a stale tower cannot be held for a new bot of the same id. */
    public void forget(UUID uuid) {
        held.remove(uuid);
    }

    public void clearAll() {
        held.clear();
    }

    private static boolean ended(Task owner) {
        TaskState state = owner.state();
        return state == TaskState.COMPLETED || state == TaskState.FAILED || state == TaskState.CANCELLED;
    }
}

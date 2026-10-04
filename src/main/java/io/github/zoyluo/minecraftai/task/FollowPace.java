package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.Gait;
import io.github.zoyluo.minecraftai.action.QuietZone;

/**
 * The pace of a bot that follows a player, decided from what an observer can see of the player and of the surroundings: how far away
 * they are, whether they sneak, walk or sprint, and whether hostiles are after somebody. Pure (no Minecraft
 * types beyond the {@link Gait} and {@link QuietZone.Level} enums), so every rule is unit-tested; {@link FollowTask} gathers the
 * {@link Input} and publishes the answer as a FOLLOW tick lease ({@code ActionPack.requestPace}).
 *
 * <p>Rules, first that applies decides:</p>
 * <ol>
 *   <li>Aggro pressure on the bot, the player or a Minecraft-AI bot: SPRINT.</li>
 *   <li>Within {@value #CLOSE_GAP} blocks: SNEAK when the player has been sneaking for {@value #SNEAK_TICKS} ticks, else WALK.</li>
 *   <li>The player sneaks: SNEAK up to {@value #SNEAK_MIRROR_GAP} blocks; further out WALK, and SPRINT only from
 *       {@value #SNEAK_CATCH_UP_GAP} blocks and outside every quiet zone.</li>
 *   <li>The player sprints (synced flag, or {@value #SPRINT_SPEED} blocks per second over the last ten ticks): SPRINT.</li>
 *   <li>A SILENT quiet zone: SNEAK up to {@value #SNEAK_MIRROR_GAP} blocks, else WALK. CAUTION: SPRINT only from
 *       {@value #CAUTION_SPRINT_GAP} blocks, else WALK.</li>
 *   <li>The player walks (between {@value #WALK_SPEED} and {@value #SPRINT_SPEED} blocks per second): WALK unless the gap is at
 *       least {@code sprintGap}.</li>
 *   <li>Otherwise SPRINT from {@code sprintGap}, WALK up to {@code walkGap}, and in between the gait it already had.</li>
 * </ol>
 * An upgrade is immediate; a downgrade needs {@value #DWELL_TICKS} ticks at the current gait (the lease skips the pace policy's own
 * dwell, so the follower owns it).
 */
public final class FollowPace {
    /** At or below this gap the follower is "with" the player: it walks, or sneaks along with a sneaking one. */
    public static final double CLOSE_GAP = 4.5D;
    /** A player that sneaks (this many consecutive ticks) is mirrored up to this gap. */
    public static final double SNEAK_MIRROR_GAP = 8.0D;
    /** A sneaking player this far ahead is caught up with at a sprint (outside quiet zones). */
    public static final double SNEAK_CATCH_UP_GAP = 14.0D;
    /** In CAUTION the follower sprints only this far behind. */
    public static final double CAUTION_SPRINT_GAP = 16.0D;
    /** Consecutive ticks the player must have sneaked before the follower mirrors it. */
    public static final int SNEAK_TICKS = 4;
    /** Blocks per second above which a player is walking (below: standing). */
    public static final double WALK_SPEED = 1.5D;
    /** Blocks per second from which a player counts as sprinting even without the synced flag. */
    public static final double SPRINT_SPEED = 5.0D;
    /** Ticks a gait must have lasted before it may be lowered. */
    public static final int DWELL_TICKS = 10;

    private FollowPace() {
    }

    /**
     * Everything one decision looks at.
     *
     * @param gap                blocks between the follower and the player
     * @param targetSpeedBps     horizontal speed of the player over the last ten ticks, blocks per second
     * @param targetSprinting    the synced sprint flag of the player
     * @param targetSneakingTicks consecutive ticks the player has been sneaking (0 = not sneaking)
     * @param quiet              the quiet-zone level around the follower
     * @param pressure           hostiles are after a protected player or bot (AggroSense)
     * @param previous           the gait of the previous decision
     * @param ticksInGait        ticks the follower has been at {@code previous}
     * @param walkGap            the follower walks at or below this gap
     * @param sprintGap          the follower sprints from this gap
     */
    public record Input(double gap, double targetSpeedBps, boolean targetSprinting, int targetSneakingTicks,
                        QuietZone.Level quiet, boolean pressure,
                        Gait previous, int ticksInGait, double walkGap, double sprintGap) {
    }

    /** The gait for this tick. */
    public static Gait decide(Input in) {
        Gait wanted = wanted(in);
        if (wanted.compareTo(in.previous()) < 0 && in.ticksInGait() < DWELL_TICKS) {
            return in.previous();
        }
        return wanted;
    }

    /** The gait the rules ask for, before the downgrade dwell. */
    static Gait wanted(Input in) {
        boolean sneaking = in.targetSneakingTicks() >= SNEAK_TICKS;
        // 1. Under pressure: run.
        if (in.pressure()) {
            return Gait.SPRINT;
        }
        // 2. With the player.
        if (in.gap() <= CLOSE_GAP) {
            return sneaking ? Gait.SNEAK : Gait.WALK;
        }
        // 3. The player sneaks: the follower creeps too while it is near; from far away it walks or catches up.
        if (sneaking) {
            if (in.gap() <= SNEAK_MIRROR_GAP) {
                return Gait.SNEAK;
            }
            if (in.quiet() == QuietZone.Level.NONE && in.gap() >= SNEAK_CATCH_UP_GAP) {
                return Gait.SPRINT;
            }
            return Gait.WALK;
        }
        // 4. The player sprints.
        if (in.targetSprinting() || in.targetSpeedBps() >= SPRINT_SPEED) {
            return Gait.SPRINT;
        }
        // 5. Quiet zones.
        if (in.quiet() == QuietZone.Level.SILENT) {
            return in.gap() <= SNEAK_MIRROR_GAP ? Gait.SNEAK : Gait.WALK;
        }
        if (in.quiet() == QuietZone.Level.CAUTION) {
            return in.gap() >= CAUTION_SPRINT_GAP ? Gait.SPRINT : Gait.WALK;
        }
        // 6. The player walks: walk with them unless they have got well ahead.
        if (in.targetSpeedBps() > WALK_SPEED) {
            return in.gap() >= in.sprintGap() ? Gait.SPRINT : Gait.WALK;
        }
        // 7. The player stands (or is slow): sprint when far, walk when near, in between keep what we had.
        if (in.gap() >= in.sprintGap()) {
            return Gait.SPRINT;
        }
        if (in.gap() <= in.walkGap()) {
            return Gait.WALK;
        }
        // A follower that crept along with a sneaking player does not keep creeping once the player has stopped sneaking.
        return Gait.max(in.previous(), Gait.WALK);
    }
}

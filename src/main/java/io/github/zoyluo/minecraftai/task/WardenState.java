package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.RecentDamage;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.warden.AngerLevel;
import net.minecraft.world.entity.monster.warden.Warden;

import java.util.Collection;

/**
 * Whether a warden is hunting, judged only from what an observer could see: the synced anger level, the visible pose and the damage
 * records of this mod. It never reads {@code getTarget()}, {@code getEntityAngryAt()} or the brain memories (hidden server state).
 *
 * <p>A warden is HUNTING when any of these holds:</p>
 * <ul>
 *   <li>its synced (client) anger level is at least {@link AngerLevel#ANGRY} ({@code Warden.getClientAngerLevel()}, the value a
 *       client sees for the heartbeat and the sniffing);</li>
 *   <li>its pose is {@link Pose#ROARING} (it has just noticed something and roars at it);</li>
 *   <li>it hit one of the given victims (bots, their owner, the followed player) within {@link #HIT_WINDOW_TICKS} game ticks,
 *       according to {@link RecentDamage}.</li>
 * </ul>
 * A warden that is none of those is CALM (emerging and digging wardens included: they are not chasing anything).
 *
 * <p>Test note: {@code Warden.syncClientAngerLevel()} runs only inside {@code customServerAiStep}, so the client anger level of a
 * {@code setNoAi(true)} warden never changes. A GameTest makes such a warden "hunting" with {@code setPose(Pose.ROARING)} or with a
 * recorded hit ({@link RecentDamage}), not by raising its anger.</p>
 */
public final class WardenState {
    /** A hit on a victim this recent (game ticks) counts as hunting. */
    public static final int HIT_WINDOW_TICKS = 100;
    private WardenState() {
    }

    /** True when the warden is hunting (see the class comment); {@code gameTime} is the level game time. */
    public static boolean isHunting(Warden warden, Collection<? extends LivingEntity> victims, long gameTime) {
        if (warden == null) {
            return false;
        }
        // AngerLevel.ANGRY carries its own threshold (getMinimumAnger() is 80 in 1.21.11), so no constant is copied here.
        if (warden.getClientAngerLevel() >= AngerLevel.ANGRY.getMinimumAnger()) {
            return true;
        }
        if (warden.getPose() == Pose.ROARING) {
            return true;
        }
        if (victims != null) {
            for (LivingEntity victim : victims) {
                if (victim != null
                        && RecentDamage.lastHitBy(victim.getUUID(), warden.getUUID(), gameTime, HIT_WINDOW_TICKS).isPresent()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The negation of {@link #isHunting}. */
    public static boolean isCalm(Warden warden, Collection<? extends LivingEntity> victims, long gameTime) {
        return !isHunting(warden, victims, gameTime);
    }
}

package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.network.PlayerKind;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Survival-legal preconditions of one melee strike or one bow shot, shared by every attacker.
 *
 * <p>{@code ServerPlayer.attack} performs no reach or occlusion check of its own: vanilla checks
 * reach only in the network packet handler, which the bot's direct call skips. These predicates put
 * that missing legality back, using exactly what a human's crosshair needs: the target's bounding
 * box within the player's entity interaction range, and no colliding block in between.
 *
 * <p>Line of sight here uses collision shapes ({@link ClipContext.Block#COLLIDER}), the same bar
 * vanilla mobs use to melee, so non-colliding plants (grass, flowers) never occlude a strike; an
 * outline-based ray would stall melee in a meadow.
 *
 * <p>Residual difference to a human's crosshair: vanilla picks the entity under the crosshair with
 * an outline-based block ray, so a plant standing in the line of sight (tall grass, a flower) can take
 * the crosshair (the human targets the plant, not the mob), while this collider test lets the bot strike
 * through it. That is kept on purpose (it never makes the bot able to hit through anything solid,
 * and the outline rule would stall melee in a meadow); it is a known, documented gap, not a claim of
 * crosshair equivalence.
 */
public final class StrikeLegality {
    /** Widening applied to a friendly player's box when testing a bow line of fire (arc and spread). */
    private static final double LINE_OF_FIRE_MARGIN = 0.6D;

    private StrikeLegality() {
    }

    /** Owner or another bot (our own or a fake-player mod's): never a target for this bot. */
    public static boolean isFriendly(AIPlayerEntity bot, Entity entity) {
        if (entity == bot) {
            return true;
        }
        if (!(entity instanceof ServerPlayer player)) {
            return false;
        }
        if (PlayerKind.isBot(player)) {
            return true;
        }
        Optional<java.util.UUID> owner = AIPlayerManager.INSTANCE.ownerOf(bot);
        return owner.isPresent() && owner.get().equals(player.getUUID());
    }

    /** True when the target's bounding box is inside the bot's vanilla entity interaction range. */
    public static boolean isWithinReach(AIPlayerEntity bot, Entity target) {
        return bot.isWithinEntityInteractionRange(target, 0.0D);
    }

    /**
     * True when no colliding block lies between the bot's eyes and the point where a ray aimed at
     * the target's centre enters its bounding box. A bot whose eyes are already inside the box is
     * trivially clear.
     */
    public static boolean hasStrikeLineOfSight(AIPlayerEntity bot, Entity target) {
        Vec3 eye = bot.getEyePosition();
        AABB box = target.getBoundingBox();
        if (box.contains(eye)) {
            return true;
        }
        Vec3 aim = box.getCenter();
        Vec3 entry = box.clip(eye, aim).orElse(aim);
        HitResult hit = bot.level().clip(new ClipContext(
                eye, entry, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
        return hit.getType() == HitResult.Type.MISS;
    }

    /** The reason a strike on {@code target} is illegal right now, or {@code null} when it is legal. */
    public static String strikeRefusal(AIPlayerEntity bot, Entity target) {
        if (isFriendly(bot, target)) {
            return "friendly_target";
        }
        if (!isWithinReach(bot, target)) {
            return "out_of_reach";
        }
        if (!hasStrikeLineOfSight(bot, target)) {
            return "no_line_of_sight";
        }
        return null;
    }

    /**
     * True when the owner or another bot stands close to the straight line from the bot's eyes to
     * {@code target}'s centre, i.e. an arrow released now could hit a friend instead.
     */
    public static boolean friendlyOnLineOfFire(AIPlayerEntity bot, Entity target) {
        Vec3 eye = bot.getEyePosition();
        Vec3 aim = target.getBoundingBox().getCenter();
        double targetDistanceSquared = eye.distanceToSqr(aim);
        for (ServerPlayer other : bot.level().players()) {
            if (other == bot || !other.isAlive() || other == target || !isFriendly(bot, other)) {
                continue;
            }
            AABB box = other.getBoundingBox().inflate(LINE_OF_FIRE_MARGIN);
            Optional<Vec3> hit = box.clip(eye, aim);
            if (hit.isPresent() && eye.distanceToSqr(hit.get()) < targetDistanceSquared) {
                return true;
            }
            if (box.contains(eye)) {
                return true;
            }
        }
        return false;
    }
}

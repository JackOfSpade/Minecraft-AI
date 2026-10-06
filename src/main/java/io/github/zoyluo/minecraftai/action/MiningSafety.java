package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.phys.AABB;

/**
 * Live safety checks shared by every direct and route-driven block-break controller.
 *
 * <p>A support block is not an expendable mining target while any real player or bot's live foot
 * collision overlaps it. This is deliberately based on current player positions rather than a
 * remembered placement: a player may stand on natural stone just as easily as on a bot-built
 * bridge, including across a slab or block seam.</p>
 */
public final class MiningSafety {
    /** Typed refusal when the acting bot must first leave the target's support footprint. */
    public static final String SELF_SUPPORT = "self_support";
    /** Typed refusal when another live player occupies the target's support footprint. */
    public static final String PLAYER_SUPPORT = "player_support";

    /** Which live actor, if any, is physically supported by a prospective break target. */
    public enum SupportOccupancy {
        NONE,
        SELF,
        PLAYER
    }

    private MiningSafety() {
    }

    /**
     * Returns the live support occupancy for {@code target}. Another player wins over the bot's
     * own occupancy: stepping aside cannot make it safe to break a floor that still holds a
     * human, so callers must leave that terrain alone and choose another route.
     */
    public static SupportOccupancy supportOccupancy(AIPlayerEntity actor, BlockPos target) {
        if (actor == null || target == null) {
            return SupportOccupancy.NONE;
        }
        boolean self = isSupporting(actor, target);
        for (ServerPlayer player : actor.level().players()) {
            if (player == actor || !player.isAlive() || player.isSpectator()) {
                continue;
            }
            if (isSupporting(player, target)) {
                return SupportOccupancy.PLAYER;
            }
        }
        return self ? SupportOccupancy.SELF : SupportOccupancy.NONE;
    }

    /** True when {@code target} is supporting the acting bot or another live player. */
    public static boolean isOccupiedSupport(AIPlayerEntity actor, BlockPos target) {
        return supportOccupancy(actor, target) != SupportOccupancy.NONE;
    }

    /** Stable public failure/log reason for a support occupancy. */
    public static String refusalReason(SupportOccupancy occupancy) {
        return switch (occupancy) {
            case SELF -> SELF_SUPPORT;
            case PLAYER -> PLAYER_SUPPORT;
            case NONE -> "no_support";
        };
    }

    /**
     * Includes thin floors such as slabs and every block under a body straddling a seam.
     *
     * <p>{@link net.minecraft.world.level.CollisionGetter#findSupportingBlock} deliberately
     * returns only one nearest collision block. That makes it useful for normal movement, but
     * unsafe as a mining veto: a player whose feet overlap two blocks can be supported by either
     * one, while the nearest-block tie break exposes the other to a break. Ask the same
     * collision iterator for <em>all</em> shapes intersecting the player's infinitesimal foot
     * slice instead. This is local live body geometry, not an unguarded target-state lookup.</p>
     */
    private static boolean isSupporting(ServerPlayer player, BlockPos target) {
        AABB body = player.getBoundingBox();
        AABB footProbe = new AABB(body.minX, body.minY - 1.0E-6D, body.minZ,
                body.maxX, body.minY, body.maxZ);
        BlockCollisions<Boolean> supports = new BlockCollisions<>(
                player.level(), player, footProbe, false,
                (position, ignoredShape) -> target.equals(position));
        while (supports.hasNext()) {
            if (supports.next()) {
                return true;
            }
        }
        return false;
    }
}

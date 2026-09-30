package io.github.zoyluo.minecraftai.mode;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mixin.ServerEntityManagerCacheAccessorMixin;
import io.github.zoyluo.minecraftai.mixin.ServerWorldEntityManagerAccessorMixin;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/**
 * Read-only physical checks a bot's movement code shares: whether its body overlaps a block, and which entity occupies a landing box.
 * This class never moves a bot: every correction teleport primitive (step, swim step, jump, edge shift, centre return, nudge) was
 * removed with the R5 no-micro-teleport rule, so a move is a {@code WalkedStep} of real inputs. The privileged emergency rescues
 * live in their own capability-gated call sites (see {@code TeleportAudit.PRIVILEGED_METHODS}).
 */
public final class FakePlayerMotion {
    private static final double BODY_COLLISION_EPSILON = 1.0E-7D;

    private FakePlayerMotion() {
    }

    /**
     * Returns whether the player's current body is free of block collision.
     *
     * <p>The tiny contraction ignores an exact support-face touch while preserving meaningful
     * overlap with a neighbouring raised block. Cell-level standability alone cannot prove this:
     * an off-centre player can occupy an otherwise standable air column while its 0.6-wide body
     * intersects terrain in the next column.</p>
     */
    public static boolean isBlockCollisionFree(AIPlayerEntity bot) {
        AABB interior = bot.getBoundingBox().deflate(BODY_COLLISION_EPSILON);
        return bot.level().noBlockCollision(bot, interior);
    }

    /**
     * The first entity that blocks a landing box, or null. The bot itself, the vehicle it rides and
     * anything riding it can never block its own step (a rider stepping off a boat overlaps the hull
     * by construction), so they are excluded; the occupant is reported so a rejected step is
     * diagnosable from the log.
     */
    public static net.minecraft.world.entity.Entity landingOccupant(AIPlayerEntity bot, AABB landingBox) {
        var world = bot.level();
        var manager = ((ServerWorldEntityManagerAccessorMixin) (Object) world)
                .minecraftai$getEntityManager();
        var cache = ((ServerEntityManagerCacheAccessorMixin) (Object) manager)
                .minecraftai$getSectionCache();
        int minSectionX = SectionPos.posToSectionCoord(landingBox.minX - 2.0D);
        int minSectionY = SectionPos.posToSectionCoord(landingBox.minY - 4.0D);
        int minSectionZ = SectionPos.posToSectionCoord(landingBox.minZ - 2.0D);
        int maxSectionX = SectionPos.posToSectionCoord(landingBox.maxX + 2.0D);
        int maxSectionY = SectionPos.posToSectionCoord(landingBox.maxY);
        int maxSectionZ = SectionPos.posToSectionCoord(landingBox.maxZ + 2.0D);
        for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
            for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
                    var section = cache.getSection(
                            SectionPos.asLong(sectionX, sectionY, sectionZ));
                    if (section == null || section.isEmpty()) {
                        continue;
                    }
                    net.minecraft.world.entity.Entity occupant = section.getEntities().filter(entity ->
                            entity != bot
                                    && entity != bot.getVehicle()
                                    && !bot.hasPassenger(entity)
                                    && !entity.isRemoved()
                                    && landingBox.intersects(entity.getBoundingBox())
                                    && (entity instanceof LivingEntity living && living.isAlive()
                                    || EntitySelector.CAN_BE_COLLIDED_WITH.test(entity))).findFirst().orElse(null);
                    if (occupant != null) {
                        return occupant;
                    }
                }
            }
        }
        return null;
    }
}

package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.network.PlayerKind;
import io.github.zoyluo.minecraftai.task.HostileBotLedger;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Survival-legal preconditions of one melee strike or one shot of a bow or crossbow, shared by every attacker.
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
 * crosshair equivalence. The strike itself must also be under the real crosshair ({@link HumanAim#crosshairEntity},
 * vanilla's pick along the bot's look vector, again by collision shapes): legality here says the strike is permitted,
 * {@link InteractAction#attackEntity} then aims at human speed and lands it only on what is under the crosshair.
 */
public final class StrikeLegality {
    private StrikeLegality() {
    }

    /**
     * Owner or another bot (our own or a fake-player mod's): never a target for this bot, EXCEPT a foreign bot (a fake player that is
     * not ours, such as a PvP BOT inhabitant) that has acted against the Minecraft-AI side and that this bot or its owner can see
     * ({@link HostileBotLedger#isVisibleAggressor}).
     *
     * <p>Order: the bot itself, any Minecraft-AI bot (any owner), a non-player, the bot's own owner (before the bot test: a GameTest
     * owner is a mock on an EmbeddedChannel), any real human player, then a foreign bot (friendly unless a visible aggressor).
     */
    public static boolean isFriendly(AIPlayerEntity bot, Entity entity) {
        if (entity == bot || entity instanceof AIPlayerEntity) {
            return true;
        }
        if (!(entity instanceof ServerPlayer player)) {
            return false;
        }
        Optional<java.util.UUID> owner = AIPlayerManager.INSTANCE.ownerOf(bot);
        if (owner.isPresent() && owner.get().equals(player.getUUID())) {
            return true;
        }
        if (!PlayerKind.isBot(player)) {
            return true;
        }
        if (PlayerKind.isBot(player) && !AIPlayerManager.INSTANCE.isAnyBotOwner(player.getUUID())) {
            return !HostileBotLedger.isVisibleAggressor(bot, player);
        }
        return false;
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
     * The reason a shot at {@code target} is illegal right now, or {@code null} when it is legal.
     * Friendly humans/companions are deliberately not a refusal: their projectile collision hooks
     * ignore this shot so it can continue to the observed target.
     */
    public static String shotRefusal(AIPlayerEntity bot, Entity target, RangedWeapon.Shape shape) {
        if (!hasStrikeLineOfSight(bot, target)) {
            return "no_line_of_sight";
        }
        return null;
    }
}

package io.github.zoyluo.minecraftai.entity;

import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.network.PlayerKind;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Shared ally policy for human players and Minecraft-AI companions. */
public final class CompanionAllegiance {
    private CompanionAllegiance() {
    }

    /**
     * Minecraft-AI companions never harm each other or a real player. A fake player from another
     * bot mod remains an enemy candidate: only Minecraft-AI's own companions are the protected
     * party, so PvP BOT aggressors still work exactly as combat targets.
     */
    public static boolean areAllies(Entity first, Entity second) {
        if (first == null || second == null || first == second) {
            return false;
        }
        if (first instanceof AIPlayerEntity && second instanceof AIPlayerEntity) {
            return true;
        }
        AIPlayerEntity companion = first instanceof AIPlayerEntity bot ? bot
                : second instanceof AIPlayerEntity bot ? bot : null;
        if (companion == null) {
            return false;
        }
        Entity other = companion == first ? second : first;
        if (!(other instanceof ServerPlayer player)) {
            return false;
        }
        // An owner is always an ally, including GameTest/mock owners which may look like a bot.
        if (AIPlayerManager.INSTANCE.ownerOf(companion).filter(player.getUUID()::equals).isPresent()) {
            return true;
        }
        // Real human players are allies; a foreign fake-player bot is deliberately not.
        return !PlayerKind.isBot(player);
    }

    /** True when a damage source represents an allied player/companion attack. */
    public static boolean blocksDamage(LivingEntity victim, DamageSource source) {
        if (victim == null || source == null) {
            return false;
        }
        Entity attacker = source.getEntity();
        if (areAllies(attacker, victim)) {
            return true;
        }
        Entity direct = source.getDirectEntity();
        return direct instanceof Projectile projectile && areAllies(projectile.getOwner(), victim);
    }

    /**
     * Replaces an allied player/companion selected by a melee click with the first non-ally on
     * the same legal attack ray. If no enemy is actually behind the ally, the original target is
     * retained and the damage gate simply makes that swing harmless.
     */
    public static Entity passThroughMeleeAlly(Player attacker, Entity selected) {
        if (attacker == null || selected == null || !areAllies(attacker, selected)) {
            return selected;
        }
        Vec3 eye = attacker.getEyePosition();
        Vec3 end = eye.add(attacker.getLookAngle().scale(attacker.entityInteractionRange()));
        AABB search = attacker.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1.0D);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(attacker.level(), attacker, eye, end, search,
                candidate -> candidate != attacker
                        && candidate.isAlive()
                        && candidate.isPickable()
                        && !areAllies(attacker, candidate),
                0.0F);
        if (hit == null || !attacker.isWithinEntityInteractionRange(hit.getEntity(), 0.0D)) {
            return selected;
        }
        HitResult obstruction = attacker.level().clip(new ClipContext(
                eye, hit.getLocation(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, attacker));
        return obstruction.getType() == HitResult.Type.MISS ? hit.getEntity() : selected;
    }
}

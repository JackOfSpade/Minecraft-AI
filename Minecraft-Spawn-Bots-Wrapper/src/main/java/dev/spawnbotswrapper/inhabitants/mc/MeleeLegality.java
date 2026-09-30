package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.combat.MeleeGeometry;
import dev.spawnbotswrapper.inhabitants.combat.MeleeGeometry.Box;
import dev.spawnbotswrapper.inhabitants.combat.MeleeVetoLog;
import dev.spawnbotswrapper.inhabitants.command.CommandServices;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * "No cheating": a melee hit an inhabitant lands must be one a human client could have made. PvP BOT's melee routine
 * has no line-of-sight check, so a chasing inhabitant next to a one-block wall killed the player behind it: a human's
 * crosshair ray stops at the block, the attack never targets the entity. This vetoes such a hit through
 * {@link ServerLivingEntityEvents#ALLOW_DAMAGE} (no mixins, no PvP BOT or HeroBot class touched, and PvP BOT's own state
 * is left alone: it simply keeps failing to land hits until it has a real line).
 * <p>
 * What is checked: a hit whose damage source has the attacking inhabitant as BOTH the causing and the direct entity and
 * is a player or mob attack (projectiles, thorns, explosions and the like are never touched). The hit is legal iff a
 * human could have targeted the victim (the rule is vanilla, nothing is configurable but the switch
 * {@code combat.meleeLegality.enabled}):
 * <ul>
 *   <li>the entity interaction range of the attacker ({@code ENTITY_INTERACTION_RANGE}, vanilla 3.0 in survival) reaches
 *       the victim's bounding box from the eye ({@link MeleeGeometry#TOLERANCE} of lag tolerance is added), and</li>
 *   <li>a ray from the eye to a point a human could aim at (nearest point of the box, its centre, head or feet) enters the
 *       box within that range without passing a block that has a collision shape ({@link ClipContext.Block#COLLIDER},
 *       fluids ignored).</li>
 * </ul>
 * A vanilla sweeping-edge victim next to a legally hit primary victim of the same swing (same tick) is legal too, as in
 * vanilla. Illegal hits return false: no damage, no knockback, no effect. Each veto is a debug line and counted
 * ({@link MeleeVetoLog}); one INFO line per bot per minute reports the count.
 * <p>
 * Limit: the event fires for normal players and mobs (whom inhabitants hit). A HeroBot fake player re-implements the whole
 * hurt routine and never fires it, so an inhabitant hitting ANOTHER inhabitant is not covered; that is rare and left alone.
 * <p>
 * Every step swallows its own failure: a bug here must never break the damage path, it then lets the hit through.
 */
public final class MeleeLegality {
    private final Supplier<ServerSession> session;
    private final Logger log;
    private final MeleeVetoLog vetoes = new MeleeVetoLog();
    /** The last legally hit primary victim per attacker, to let the sweep of the same swing through. */
    private final Map<UUID, Primary> primaries = new HashMap<>();
    private boolean broken;

    private record Primary(long tick, UUID victim, Box box) {
    }

    public MeleeLegality(Supplier<ServerSession> session, Logger log) {
        this.session = session;
        this.log = log;
    }

    /** Registers the damage veto; call once from the mod entrypoint. */
    public void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(this::allow);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> reset());
    }

    /** Server stopped: nothing timed in the old server's ticks may outlive it. */
    public void reset() {
        primaries.clear();
        vetoes.reset();
    }

    /** Hits vetoed so far for the bot (all reasons). */
    public long vetoed(String bot) {
        return vetoes.total(bot);
    }

    /** Hits vetoed so far for the bot for one reason. */
    public long vetoed(String bot, MeleeVetoLog.Reason reason) {
        return vetoes.total(bot, reason);
    }

    /** A short diagnostic text about one bot's vetoed hits, or null when there were none. */
    public String describe(String bot) {
        return vetoes.describe(bot);
    }

    private boolean allow(LivingEntity victim, DamageSource source, float amount) {
        // Cheapest tests first: this runs for every damage event on the server.
        Entity attackerEntity = source.getEntity();
        if (!(attackerEntity instanceof ServerPlayer attacker) || source.getDirectEntity() != attackerEntity
                || attacker == victim || broken || !isMeleeType(source)) {
            return true;
        }
        try {
            ServerSession current = session.get();
            CommandServices services = current == null ? null : current.services();
            if (services == null || services.population() == null || services.config() == null) {
                return true;
            }
            InhabitantsConfig cfg = services.config().get();
            if (cfg == null || cfg.combat == null || cfg.combat.meleeLegality == null
                    || !cfg.combat.meleeLegality.enabled) {
                return true;
            }
            String name = attacker.getName().getString();
            if (services.population().findBot(name).isEmpty() || !(attacker.level() instanceof ServerLevel level)
                    || victim.level() != level) {
                return true;
            }
            return decide(level, attacker, name, victim);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            broken = true;
            log.warn("melee legality failed and is switched off until restart: {}", t.toString());
            return true;
        }
    }

    private static boolean isMeleeType(DamageSource source) {
        return source.is(DamageTypes.PLAYER_ATTACK) || source.is(DamageTypes.MOB_ATTACK)
                || source.is(DamageTypes.MOB_ATTACK_NO_AGGRO);
    }

    private boolean decide(ServerLevel level, ServerPlayer attacker, String name, LivingEntity victim) {
        long now = level.getGameTime();
        AABB bb = victim.getBoundingBox();
        Box box = new Box(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ);
        Primary primary = primaries.get(attacker.getUUID());
        if (primary != null && primary.tick == now && !primary.victim.equals(victim.getUUID())
                && MeleeGeometry.isSweepVictim(primary.box, box, attacker.distanceToSqr(victim))) {
            return true;
        }
        double reach = attacker.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        Vec3 eye = attacker.getEyePosition();
        double distance = MeleeGeometry.distanceToBox(eye.x, eye.y, eye.z, box);
        MeleeVetoLog.Reason reason = clearLine(level, attacker, eye, box, reach, distance);
        if (reason == null) {
            primaries.put(attacker.getUUID(), new Primary(now, victim.getUUID(), box));
            return true;
        }
        String victimName = victim instanceof ServerPlayer p ? p.getName().getString() : victim.getType().toShortString();
        log.debug("melee legality: vetoed {} on {} ({}, {} blocks to its box, reach {})", name, victimName, reason,
                String.format(Locale.ROOT, "%.2f", distance), String.format(Locale.ROOT, "%.2f", reach));
        String line = vetoes.veto(now, name, reason, victimName, distance, reach);
        if (line != null) {
            log.info(line);
        }
        return false;
    }

    /** Null when a human could have targeted the box from the eye, else why not. */
    private static MeleeVetoLog.Reason clearLine(ServerLevel level, ServerPlayer attacker, Vec3 eye, Box box,
                                                 double reach, double distance) {
        if (!MeleeGeometry.withinReach(distance, reach)) {
            return MeleeVetoLog.Reason.OUT_OF_REACH;
        }
        for (double[] aim : MeleeGeometry.aimPoints(eye.x, eye.y, eye.z, box)) {
            double entry = MeleeGeometry.entryDistance(eye.x, eye.y, eye.z, aim[0], aim[1], aim[2], box);
            if (entry < 0.0 || !MeleeGeometry.withinReach(entry, reach)) {
                continue;
            }
            if (entry <= MeleeGeometry.SURFACE_EPSILON) {
                return null;
            }
            double dx = aim[0] - eye.x;
            double dy = aim[1] - eye.y;
            double dz = aim[2] - eye.z;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double stop = entry - MeleeGeometry.SURFACE_EPSILON;
            Vec3 end = new Vec3(eye.x + dx / len * stop, eye.y + dy / len * stop, eye.z + dz / len * stop);
            HitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, attacker));
            if (hit.getType() == HitResult.Type.MISS) {
                return null;
            }
        }
        return MeleeVetoLog.Reason.BLOCKED;
    }
}

package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.SightClip;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * What the bot or its owner can see, for the one case where a bot's own eyes are not enough: a foreign bot that attacks the
 * Minecraft-AI side ("appears in line of sight of either").
 *
 * <p>The owner's sight only NOMINATES a target (hostility, threat pressure, the lost-sight timer of a fight). Strikes still need the
 * bot's own reach and collider line of sight ({@code StrikeLegality.strikeRefusal} is unchanged), so a bot never hits through a wall
 * because its owner looks at the attacker. Owner vision is used ONLY for foreign bots (players), never for mobs.</p>
 *
 * <p>The owner sees through foliage, fences, glass and water as the bot does ({@link #ownerSees}), but a fight is about what can be
 * hit: its bookkeeping asks {@link #ownerSeesStrict}, the owner's plain collider line, so an owner staring at a foreign bot through
 * a window cannot keep the bot in a fight it can never strike in.</p>
 */
public final class SharedVision {
    private SharedVision() {
    }

    /** The owner looked up once per bot per server tick. */
    private record OwnerLookup(int serverTick, UUID ownerId, ServerPlayer owner) {
    }

    private static final Map<UUID, OwnerLookup> OWNER_CACHE = new ConcurrentHashMap<>();

    /** The bot's own noticing (realistic perception, see docs/PERCEPTION.md), or the owner's view (foreign bots only). */
    public static boolean seenByBotOrOwner(AIPlayerEntity bot, Entity entity) {
        return ObservableWorldQuery.canNoticeCreature(bot, entity) || ownerSees(bot, entity);
    }

    /**
     * True when the bot's owner is online in the bot's level, within {@code targeting.ownerVisionRange}, has a clear line of sight to
     * {@code entity} (its eyes see through leaves, fences, glass and water) and is looking toward it (inside the view cone
     * {@code targeting.ownerViewConeDot}). Only for a player
     * {@code entity}; a mob is never owner-nominated. False when owner vision is switched off or the bot has no owner.
     */
    public static boolean ownerSees(AIPlayerEntity bot, Entity entity) {
        ServerPlayer owner = ownerLookingAt(bot, entity);
        // The owner's eyes see through foliage, fences, glass and water like the bot's; it only nominates, a strike still
        // needs StrikeLegality's own collider line.
        return owner != null && SightClip.hasLineOfSight(owner, entity);
    }

    /**
     * {@link #ownerSees} along the plain vanilla collider line: what the owner's look nominates for the bookkeeping of a fight
     * (is a foreign bot still worth a fight, a pressure, a threat). An owner who looks at it through a window or a hedge sees it,
     * but nothing can hit across that, so it must not keep the bot in a fight it can never strike in.
     */
    public static boolean ownerSeesStrict(AIPlayerEntity bot, Entity entity) {
        ServerPlayer owner = ownerLookingAt(bot, entity);
        return owner != null && owner.hasLineOfSight(entity);
    }

    /** The owner when it is online in the bot's level, in range of {@code entity} (a player) and looking toward it; else null. */
    private static ServerPlayer ownerLookingAt(AIPlayerEntity bot, Entity entity) {
        if (!(entity instanceof ServerPlayer) || entity == bot) {
            return null;
        }
        MinecraftAiConfig.Targeting targeting = MinecraftAiConfig.get().behaviour().targeting();
        if (!targeting.ownerVisionEnabled()) {
            return null;
        }
        ServerPlayer owner = ownerOnline(bot);
        if (owner == null || owner == entity || owner.level() != entity.level() || !owner.isAlive()) {
            return null;
        }
        double range = targeting.ownerVisionRange();
        if (owner.distanceToSqr(entity) > range * range) {
            return null;
        }
        Vec3 toEntity = entity.getBoundingBox().getCenter().subtract(owner.getEyePosition());
        double length = toEntity.length();
        if (length > 1.0E-6D && owner.getViewVector(1.0F).dot(toEntity.scale(1.0D / length)) < targeting.ownerViewConeDot()) {
            return null;
        }
        return owner;
    }

    /** The bot's owner if online (looked up once per bot per server tick), else null. */
    public static ServerPlayer ownerOnline(AIPlayerEntity bot) {
        MinecraftServer server = bot.level().getServer();
        if (server == null) {
            return null;
        }
        int tick = server.getTickCount();
        Optional<UUID> ownerId = AIPlayerManager.INSTANCE.ownerOf(bot);
        if (ownerId.isEmpty()) {
            return null;
        }
        OwnerLookup cached = OWNER_CACHE.get(bot.getUUID());
        if (cached != null && cached.serverTick() == tick && cached.ownerId().equals(ownerId.get())) {
            return cached.owner();
        }
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerId.get());
        OWNER_CACHE.put(bot.getUUID(), new OwnerLookup(tick, ownerId.get(), owner));
        return owner;
    }

    /** Drops the cached owner lookup (it holds a ServerPlayer reference) of a bot that has despawned. */
    public static void forget(UUID botUuid) {
        if (botUuid != null) {
            OWNER_CACHE.remove(botUuid);
        }
    }

    /** Forgets the per-tick caches (server stop). */
    public static void clearAll() {
        OWNER_CACHE.clear();
    }
}

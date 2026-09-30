package dev.spawnbotswrapper.inhabitants.gametest;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test-only entrypoint: records every projectile a player-like shooter launches, with the level game time, so a
 * GameTest can say when (and how often) an inhabitant fired. Vanilla events only; nothing here touches PvP BOT.
 */
public final class HarnessMod implements ModInitializer {
    /** One launched projectile; {@code speed} is its launch speed in blocks per tick (a full-power bow arrow: 3.0). */
    public record Shot(long tick, String type, double speed) {
    }

    private static final Map<UUID, List<Shot>> SHOTS = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Projectile projectile) {
                Entity owner = projectile.getOwner();
                if (owner != null) {
                    List<Shot> list = SHOTS.computeIfAbsent(owner.getUUID(), id -> new ArrayList<>());
                    synchronized (list) {
                        list.add(new Shot(level.getGameTime(), projectile.getType().toShortString(),
                                projectile.getDeltaMovement().length()));
                    }
                }
            }
        });
    }

    /** The projectiles launched by this shooter so far, in order (a copy). */
    public static List<Shot> shotsBy(UUID shooter) {
        List<Shot> list = SHOTS.get(shooter);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return List.copyOf(list);
        }
    }
}

package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations;
import dev.spawnbotswrapper.inhabitants.combat.CrossbowPacer;
import dev.spawnbotswrapper.inhabitants.command.CommandServices;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Fires an inhabitant's loaded crossbow and paces its shots (config {@code rangedPacing}; see {@link CrossbowPacer} for
 * the rules and for why this exists: PvP BOT itself can never fire a loaded crossbow on this Minecraft version).
 * <p>
 * Two pieces, both vanilla APIs only, no mixins and nothing that touches PvP BOT or HeroBot classes:
 * <ul>
 *   <li>{@link #tick}, once per server tick and AFTER PvP BOT's own tick (the caller registers it in a phase behind
 *       the default one; PvP BOT ticks its bots at the end of the same server tick): decides per inhabitant with a
 *       crossbow and fires through the vanilla right-click path {@code ServerPlayerGameMode.useItem}, exactly what a
 *       player's click does. No projectile is created here and no damage, accuracy or speed is touched.</li>
 *   <li>{@link #register}, a Fabric entity-load hook: every crossbow projectile an inhabitant launches (ours, or one
 *       fired by a held "use" action of the bot fake player, which right-clicks a loaded crossbow every tick and made
 *       inhabitants shoot about twice a second) puts the crossbow on the vanilla item cooldown for the shot interval.
 *       Vanilla refuses a use of an item on cooldown, so the interval binds every shooter.</li>
 * </ul>
 * The PvP BOT state it needs (its target, its mode, its targeting radius) is read through the adapter. Everything is
 * fail-soft: one failure disables the tick with one warning (the cooldown hook keeps working), never a crash.
 */
public final class RangedFire {
    private final Supplier<ServerSession> session;
    private final Logger log;
    private final CrossbowPacer pacer = new CrossbowPacer();
    /** Set after the tick threw once (logged once): a bug must not repeat 20 times a second. */
    private boolean broken;
    private long shotsFired;
    private long lastPrune;

    public RangedFire(Supplier<ServerSession> session, Logger log) {
        this.session = session;
        this.log = log;
    }

    /** Registers the projectile hook; call once from the mod entrypoint. */
    public void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Projectile projectile) {
                try {
                    onProjectile(projectile);
                } catch (OutOfMemoryError e) {
                    throw e;
                } catch (Throwable t) {
                    log.warn("crossbow pacing (projectile) failed: {}", t.toString());
                }
            }
        });
    }

    /** Crossbow shots this addon has fired itself since start (for tests and diagnostics). */
    public long shotsFired() {
        return shotsFired;
    }

    /** Server stopped: game time restarts with the next world, so nothing timed may survive. */
    public void reset() {
        pacer.reset();
        broken = false;
        lastPrune = 0;
    }

    // ------------------------------------------------------------------ the cooldown hook

    private void onProjectile(Projectile projectile) {
        // A freshly launched projectile has not ticked; one loaded back from disk has, and must not count as a shot.
        if (projectile.tickCount != 0 || !(projectile.getOwner() instanceof ServerPlayer shooter)
                || !crossbowShot(projectile, shooter)) {
            return;
        }
        Settings settings = settings(shooter.level().getServer());
        if (settings == null || !settings.pacing().enabled() || !isInhabitant(settings.services(), shooter)) {
            return;
        }
        long now = shooter.level().getGameTime();
        pacer.shot(shooter.getName().getString(), now);
        shooter.getCooldowns().addCooldown(new ItemStack(Items.CROSSBOW), CrossbowPacer.cooldownAfterShot(settings.pacing()));
    }

    /** True for the projectile kinds a crossbow launches, fired by a shooter that holds a crossbow. */
    private static boolean crossbowShot(Projectile projectile, ServerPlayer shooter) {
        if (!shooter.getMainHandItem().is(Items.CROSSBOW) && !shooter.getOffhandItem().is(Items.CROSSBOW)) {
            return false;
        }
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType()).getPath();
        return type.equals("arrow") || type.equals("spectral_arrow") || type.equals("firework_rocket");
    }

    // ------------------------------------------------------------------ the tick

    /** Once per server tick, after PvP BOT's tick. */
    public void tick(MinecraftServer server) {
        if (broken) {
            return;
        }
        try {
            run(server);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            broken = true;
            log.warn("crossbow trigger failed and is switched off until restart (the shot pacing keeps working): {}",
                    t.toString());
        }
    }

    private record Settings(CommandServices services, CrossbowPacer.Settings pacing, InhabitantsConfig config) {
    }

    private Settings settings(MinecraftServer server) {
        ServerSession current = session.get();
        if (current == null || current.server() != server) {
            return null;
        }
        CommandServices services = current.services();
        InhabitantsConfig cfg = services == null ? null : services.config().get();
        if (cfg == null || !cfg.enabled || cfg.rangedPacing == null || services.population() == null) {
            return null;
        }
        InhabitantsConfig.RangedPacing p = cfg.rangedPacing;
        return new Settings(services, new CrossbowPacer.Settings(p.enabled, p.aimSettleTicks, p.crossbowMinShotIntervalTicks), cfg);
    }

    private void run(MinecraftServer server) {
        Settings settings = settings(server);
        if (settings == null || !settings.pacing().enabled()) {
            if (!pacer.isEmpty()) {
                pacer.reset();
            }
            return;
        }
        CommandServices services = settings.services();
        PvpBotOperations adapter = services.adapter();
        double radius = Double.NaN;
        for (ServerPlayer bot : server.getPlayerList().getPlayers()) {
            ItemStack main = bot.getMainHandItem();
            String name = bot.getName().getString();
            boolean crossbow = main.is(Items.CROSSBOW);
            if (!crossbow && !pacer.isTracked(name)) {
                continue;
            }
            if (!isInhabitant(services, bot)) {
                continue;
            }
            boolean loaded = crossbow && CrossbowItem.isCharged(main);
            boolean using = bot.isUsingItem();
            boolean reachable = false;
            Boolean ranged = null;
            if (loaded && !using && adapter != null) {
                if (Double.isNaN(radius)) {
                    radius = targetRadius(adapter, settings.config());
                }
                Optional<PvpBotOperations.CombatView> view = adapter.combatView(name);
                if (view.isPresent()) {
                    ranged = view.get().mode() == null ? null : view.get().mode().equals("RANGED");
                    reachable = reachable(bot, view.get().target(), radius);
                }
            }
            long now = bot.level().getGameTime();
            CrossbowPacer.Verdict verdict = pacer.evaluate(name, now, settings.pacing(), new CrossbowPacer.Look(crossbow,
                    loaded, using, crossbow && bot.getCooldowns().isOnCooldown(main), reachable, ranged));
            if (verdict == CrossbowPacer.Verdict.FIRE) {
                bot.gameMode.useItem(bot, bot.level(), main, InteractionHand.MAIN_HAND);
                shotsFired++;
            }
        }
        long serverTick = server.getTickCount();
        if (serverTick - lastPrune >= 100 || serverTick < lastPrune) {
            lastPrune = serverTick;
            Set<String> online = new HashSet<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                online.add(p.getName().getString());
            }
            for (String name : pacer.tracked()) {
                if (!online.contains(name)) {
                    pacer.forget(name);
                }
            }
        }
    }

    /** PvP BOT's own targeting radius when readable, else the configured one, else PvP BOT's default of 64. */
    private static double targetRadius(PvpBotOperations adapter, InhabitantsConfig cfg) {
        java.util.OptionalDouble live = adapter.targetRadius();
        if (live.isPresent()) {
            return live.getAsDouble();
        }
        if (cfg.pvpbotSettings != null && cfg.pvpbotSettings.maxTargetDistance != null) {
            return cfg.pvpbotSettings.maxTargetDistance;
        }
        return 64.0;
    }

    /** The target is alive, in the same level, within the radius and in line of sight. */
    private static boolean reachable(ServerPlayer bot, Entity target, double radius) {
        return target != null && target.isAlive() && target.level() == bot.level() && bot.distanceTo(target) <= radius
                && bot.hasLineOfSight(target);
    }

    /** An inhabitant is a player of this addon's own roster (the same name index the combat log uses). */
    private static boolean isInhabitant(CommandServices services, ServerPlayer player) {
        return services != null && services.population() != null
                && services.population().findBot(player.getName().getString()).isPresent();
    }
}

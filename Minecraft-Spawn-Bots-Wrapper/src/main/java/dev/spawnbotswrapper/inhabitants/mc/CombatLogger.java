package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.combat.CombatLedger;
import dev.spawnbotswrapper.inhabitants.combat.DamageTakenLog;
import dev.spawnbotswrapper.inhabitants.combat.RangedCycleDetector;
import dev.spawnbotswrapper.inhabitants.combat.StateSnapshot;
import dev.spawnbotswrapper.inhabitants.combat.CombatLedger.Actor;
import dev.spawnbotswrapper.inhabitants.combat.CombatLedger.Kind;
import dev.spawnbotswrapper.inhabitants.command.CommandServices;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Feeds fights that involve an inhabitant into the {@link CombatLedger}, which turns them into a few readable,
 * rate-limited log lines (see {@code InhabitantsConfig.CombatLog} and docs/SETTINGS.md, "Combat log"). Nothing
 * logged anywhere else showed what the bots actually did, so "the bots do not attack me" could not be diagnosed.
 * <p>
 * Uses Fabric's {@link ServerLivingEntityEvents#AFTER_DAMAGE} and {@link ServerLivingEntityEvents#AFTER_DEATH}
 * (no mixins, nothing that can conflict with PvP BOT or HeroBot). An inhabitant is recognised ONLY by this addon's
 * own roster ({@code PopulationView.findBot}, the same name index {@link GameMessageFilter} uses); no upstream
 * class is consulted. Any other player is a "player", any non-player entity a "mob".
 * <p>
 * Target acquisition ("bot X targets player Y") is deliberately NOT logged: PvP BOT exposes the current target
 * only through internal state that the adapter does not read, and reading it per bot per tick is not free.
 * The first hit line of a fight shows who engaged whom instead.
 * <p>
 * Also writes two diagnostics (see the README, "Diagnostic lines"): one "Combat taken:" line per hit an inhabitant
 * takes, with a {@link StateSnapshot}, and a "ranged loop:" WARN when a bot repeatedly starts and abandons a bow or
 * crossbow draw ({@link RangedCycleDetector}). Both only read state; no bot behaviour changes.
 * <p>
 * Every handler swallows its own failures: a logging bug must never reach the damage code path.
 */
public final class CombatLogger {
    private final Supplier<ServerSession> session;
    private final Logger log;
    private CombatLedger ledger;
    /** Diagnostics: hits inhabitants TAKE (with a state snapshot) and the bow/crossbow abort loop. Server thread only. */
    private final DamageTakenLog taken = new DamageTakenLog();
    private final RangedCycleDetector rangedLoop = new RangedCycleDetector();
    /** Server tick at which a projectile owned by this player last spawned. */
    private final Map<UUID, Long> lastShot = new HashMap<>();
    private long upstreamTick = Long.MIN_VALUE;
    private String upstreamText;
    /** Set after the ranged-loop watcher threw once (logged once): a broken diagnostic must not spam every tick. */
    private boolean rangedWatchBroken;

    /** Only inhabitants within this many blocks of a real player are watched for the ranged loop. */
    static final double RANGED_WATCH_RADIUS = 32.0;

    public CombatLogger(Supplier<ServerSession> session, Logger log) {
        this.session = session;
        this.log = log;
    }

    /** Registers the two Fabric events; call once from the mod entrypoint. */
    public void register() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damage, blocked) ->
                guarded("damage", () -> onDamage(entity, source, baseDamage, damage, blocked)));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) ->
                guarded("death", () -> onDeath(entity, source)));
        // Remembers when a player-like shooter last spawned a projectile: a bow/crossbow draw that ends with a
        // fresh projectile was a shot, one that does not was aborted (see RangedCycleDetector).
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Projectile projectile) {
                guarded("projectile", () -> {
                    if (projectile.getOwner() instanceof ServerPlayer shooter) {
                        lastShot.put(shooter.getUUID(), (long) level.getServer().getTickCount());
                    }
                });
            }
        });
    }

    /** Once per server tick (from the entrypoint's END_SERVER_TICK): emits summaries whose window has elapsed. */
    public void tick(long serverTicks) {
        CombatLedger current = ledger;
        if (current != null) {
            guarded("tick", () -> current.tick(serverTicks));
        }
        if (!rangedWatchBroken) {
            try {
                watchRangedLoops(serverTicks);
            } catch (OutOfMemoryError e) {
                throw e;
            } catch (Throwable t) {
                rangedWatchBroken = true;
                log.warn("ranged loop diagnostic failed and is switched off until restart: {}", t.toString());
            }
        }
    }

    /**
     * Server stopping: write what is still pending, then drop the ledger. Server ticks restart at 0 on the next
     * server (quit to title and reopen, integrated restarts, GameTests), so nothing timed in the old server's
     * ticks may outlive it; the next event lazily creates a fresh ledger.
     */
    public void flush(long serverTicks) {
        CombatLedger current = ledger;
        ledger = null;
        taken.reset();
        rangedLoop.reset();
        lastShot.clear();
        upstreamTick = Long.MIN_VALUE;
        if (current != null) {
            guarded("flush", () -> {
                current.flushAll(serverTicks);
                current.reset();
            });
        }
    }

    private void guarded(String what, Runnable body) {
        try {
            body.run();
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            log.warn("combat log ({}) failed: {}", what, t.toString());
        }
    }

    // ------------------------------------------------------------------ events

    private void onDamage(LivingEntity victim, DamageSource source, float baseDamage, float damage, boolean blocked) {
        Context c = context(victim, source);
        if (c == null) {
            return;
        }
        // The damage-taken line first, in its own guard: it is the diagnostic that must survive a ledger failure.
        if (c.victim.kind() == Kind.INHABITANT && victim instanceof ServerPlayer bot) {
            guarded("damage taken", () -> logTaken(bot, source, damage, baseDamage, blocked, c));
        }
        double distance = c.attacker == null ? -1 : victim.distanceTo(source.getEntity());
        c.ledger.hit(c.now, new CombatLedger.Hit(c.attacker, c.victim, damage, baseDamage, blocked,
                source.getMsgId(), weapon(source), distance, victim.getHealth()), c.detail);
    }

    /**
     * One INFO line for every hit an inhabitant takes (throttled per attacker, first hit always), with the bot's
     * state snapshot. Unlike the coalesced ledger this shows a PLAYER hitting an inhabitant immediately.
     */
    private void logTaken(ServerPlayer bot, DamageSource source, float damage, float baseDamage, boolean blocked,
                          Context c) {
        String attackerKey = c.attacker == null ? "environment:" + source.getMsgId()
                : c.attacker.kind() + ":" + c.attacker.name().toLowerCase(Locale.ROOT);
        int folded = taken.admit(c.now, c.victim.name().toLowerCase(Locale.ROOT), attackerKey);
        if (folded < 0) {
            return;
        }
        ServerSession current = session.get();
        CommandServices services = current == null ? null : current.services();
        Entity attackerEntity = source.getEntity();
        Entity direct = source.getDirectEntity();
        String directCause = direct == null || direct == attackerEntity ? null
                : BuiltInRegistries.ENTITY_TYPE.getKey(direct.getType()).getPath();
        String population = null;
        if (services != null && services.population() != null) {
            population = services.population().findBot(c.victim.name())
                    .map(loc -> loc.structure().asString()).orElse(null);
        }
        Entity other = attackerEntity != null ? attackerEntity : nearestRealPlayer(bot, services, Double.MAX_VALUE);
        String snapshot = BotStateProbe.snapshot(bot, other, upstream(services, c.now)).format();
        log.info(DamageTakenLog.line(new DamageTakenLog.Taken(c.victim.name(), population,
                c.attacker == null ? null : c.attacker.name(),
                c.attacker == null ? null : c.attacker.kind().name().toLowerCase(Locale.ROOT), directCause,
                source.getMsgId(), weapon(source), damage, baseDamage, blocked, bot.getHealth(),
                bot.getX(), bot.getY(), bot.getZ(), bot.level().dimension().identifier().toString(), snapshot),
                folded));
    }

    /** PvP BOT's global combat switches as text, cached for a few seconds; null when the adapter cannot read them. */
    private String upstream(CommandServices services, long now) {
        if (services == null || services.adapter() == null) {
            return null;
        }
        if (upstreamTick == Long.MIN_VALUE || now < upstreamTick || now - upstreamTick >= 100) {
            upstreamTick = now;
            try {
                upstreamText = BotStateProbe.upstreamText(services.adapter().readCapabilities());
            } catch (RuntimeException e) {
                upstreamText = null;
            }
        }
        return upstreamText;
    }

    /** The closest real (non-inhabitant) player in the same level within {@code maxDistance}, or null. */
    private static ServerPlayer nearestRealPlayer(ServerPlayer bot, CommandServices services, double maxDistance) {
        MinecraftServer server = bot.level().getServer();
        if (server == null) {
            return null;
        }
        ServerPlayer best = null;
        double bestSq = maxDistance == Double.MAX_VALUE ? Double.MAX_VALUE : maxDistance * maxDistance;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p == bot || p.level() != bot.level() || isInhabitant(p, services)) {
                continue;
            }
            double d = bot.distanceToSqr(p);
            if (d <= bestSq) {
                bestSq = d;
                best = p;
            }
        }
        return best;
    }

    private static boolean isInhabitant(ServerPlayer p, CommandServices services) {
        return services != null && services.population() != null
                && services.population().findBot(p.getName().getString()).isPresent();
    }

    // ------------------------------------------------------------------ ranged loop

    /**
     * Every server tick: for inhabitants within {@value #RANGED_WATCH_RADIUS} blocks of a real player that are
     * using (or were just using) a bow or crossbow, feed the detector; log one WARN when it reports a loop. Idle
     * bots cost one boolean check.
     */
    private void watchRangedLoops(long now) {
        ServerSession current = session.get();
        CommandServices services = current == null ? null : current.services();
        if (services == null || services.population() == null) {
            return;
        }
        InhabitantsConfig cfg = services.config().get();
        if (cfg == null || cfg.combatLog == null || !cfg.combatLog.enabled) {
            return;
        }
        List<ServerPlayer> players = current.server().getPlayerList().getPlayers();
        if (players.size() < 2) {
            if (rangedLoop.trackedBots() > 0) {
                rangedLoop.reset();
            }
            return;
        }
        Set<String> seen = rangedLoop.trackedBots() == 0 ? null : new HashSet<>();
        for (ServerPlayer p : players) {
            String name = p.getName().getString();
            String using = BotStateProbe.usedRanged(p);
            if (using == null && !rangedLoop.tracking(name)) {
                continue;
            }
            if (!isInhabitant(p, services)) {
                continue;
            }
            ServerPlayer near = nearestRealPlayer(p, services, RANGED_WATCH_RADIUS);
            if (near == null) {
                rangedLoop.forget(name);
                continue;
            }
            if (seen != null) {
                seen.add(name);
            }
            RangedCycleDetector.Alert alert = rangedLoop.observe(name, new RangedCycleDetector.Sample(now,
                    using != null, using == null ? "none" : using, using == null ? 0 : p.getTicksUsingItem(),
                    BotStateProbe.anyCrossbowCharged(p), p.getInventory().getSelectedSlot(),
                    BotStateProbe.itemName(p.getMainHandItem()), lastShot.getOrDefault(p.getUUID(), -1L),
                    p.distanceTo(near), p.hasLineOfSight(near)));
            if (alert != null) {
                log.warn(RangedCycleDetector.line(alert,
                        BotStateProbe.snapshot(p, near, upstream(services, now)).format()));
            }
        }
        if (seen != null) {
            rangedLoop.retainOnly(seen);
        }
    }

    private void onDeath(LivingEntity victim, DamageSource source) {
        Context c = context(victim, source);
        if (c == null) {
            return;
        }
        double distance = c.attacker == null ? -1 : victim.distanceTo(source.getEntity());
        c.ledger.death(c.now, new CombatLedger.Death(c.victim, c.attacker, source.getMsgId(), weapon(source), distance));
    }

    /** Everything one event needs, or null when the event is not about an inhabitant (the common, cheap case). */
    private Context context(LivingEntity victim, DamageSource source) {
        Entity attackerEntity = source.getEntity();
        // Inhabitants are player entities; anything that involves no player at all is skipped without a lookup.
        if (!(victim instanceof ServerPlayer) && !(attackerEntity instanceof ServerPlayer)) {
            return null;
        }
        ServerSession current = session.get();
        if (current == null) {
            return null;
        }
        CommandServices services = current.services();
        if (services == null) {
            return null;
        }
        InhabitantsConfig cfg = services.config().get();
        if (cfg == null || cfg.combatLog == null || !cfg.combatLog.enabled) {
            return null;
        }
        Actor v = actor(victim, services);
        Actor a = attackerEntity == null ? null : actor(attackerEntity, services);
        if (v.kind() != Kind.INHABITANT && (a == null || a.kind() != Kind.INHABITANT)) {
            return null;
        }
        if (ledger == null) {
            ledger = new CombatLedger(log::info, () -> settings().coalesceTicks, () -> settings().maxLinesPerMinute);
        }
        return new Context(ledger, current.server().getTickCount(), v, a, cfg.debug);
    }

    private InhabitantsConfig.CombatLog settings() {
        ServerSession current = session.get();
        CommandServices services = current == null ? null : current.services();
        InhabitantsConfig cfg = services == null ? null : services.config().get();
        return cfg == null || cfg.combatLog == null ? new InhabitantsConfig.CombatLog() : cfg.combatLog;
    }

    private record Context(CombatLedger ledger, long now, Actor victim, Actor attacker, boolean detail) {
    }

    private static Actor actor(Entity entity, CommandServices services) {
        if (entity instanceof ServerPlayer player) {
            String name = player.getName().getString();
            boolean inhabitant = services.population() != null && services.population().findBot(name).isPresent();
            return new Actor(name, inhabitant ? Kind.INHABITANT : Kind.PLAYER);
        }
        return new Actor(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath(), Kind.MOB);
    }

    /** The item that did the damage: the weapon the damage source records, else what the attacker holds. */
    private static String weapon(DamageSource source) {
        ItemStack stack = source.getWeaponItem();
        if ((stack == null || stack.isEmpty()) && source.getEntity() instanceof LivingEntity living) {
            stack = living.getMainHandItem();
        }
        if (stack == null || stack.isEmpty()) {
            return "none";
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }
}

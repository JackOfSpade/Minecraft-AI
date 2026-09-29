package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.combat.CombatLedger;
import dev.spawnbotswrapper.inhabitants.combat.CombatLedger.Actor;
import dev.spawnbotswrapper.inhabitants.combat.CombatLedger.Kind;
import dev.spawnbotswrapper.inhabitants.command.CommandServices;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

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
 * Every handler swallows its own failures: a logging bug must never reach the damage code path.
 */
public final class CombatLogger {
    private final Supplier<ServerSession> session;
    private final Logger log;
    private CombatLedger ledger;

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
    }

    /** Once per server tick (from the entrypoint's END_SERVER_TICK): emits summaries whose window has elapsed. */
    public void tick(long serverTicks) {
        CombatLedger current = ledger;
        if (current != null) {
            guarded("tick", () -> current.tick(serverTicks));
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
        double distance = c.attacker == null ? -1 : victim.distanceTo(source.getEntity());
        c.ledger.hit(c.now, new CombatLedger.Hit(c.attacker, c.victim, damage, baseDamage, blocked,
                source.getMsgId(), weapon(source), distance, victim.getHealth()), c.detail);
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

package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.combat.HumanAim;
import dev.spawnbotswrapper.inhabitants.command.CommandServices;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Human aim for the inhabitants (see {@link HumanAim} for the model and the reasons): the glue that reads and writes the
 * rotation of the real entities.
 * <p>
 * PvP BOT sets an inhabitant's yaw, pitch and head yaw straight onto its target inside its own tick. Once per server tick,
 * AFTER that tick and after the aggro hunt's own steering (the caller runs {@link #tick} in the late phase behind them), this
 * reads the rotation the entity has then (the direction PvP BOT wants), turns a TRACKED aim toward it at the limited human
 * speed, and writes the tracked aim back, so the bot visibly turns at human speed and PvP BOT's next tick starts from where the
 * bot really looks. Everything that depends on where the bot looks then uses the tracked aim:
 * <ul>
 *   <li>the view cone and sight of the aggro hunt ({@link #lookOf}: a bot that is still turning cannot see behind itself);</li>
 *   <li>a crossbow shot ({@link #decideShot}: only once the aim is within tolerance of the wanted direction, fired along the
 *       aim plus the hand's jitter; vanilla's shot vector is the shooter's view vector, {@code CrossbowItem.shootProjectile}
 *       with no target, so setting the rotation before {@code useItem} is all it takes);</li>
 *   <li>a bow arrow PvP BOT releases inside its own tick along the direction it had just snapped to: the arrow is re-aimed at
 *       the moment it enters the world ({@code ENTITY_LOAD}, tick 0) along the tracked aim plus jitter, by vanilla's own
 *       {@code Projectile.shootFromRotation} with the speed the bow gave it; nothing else about it (damage, crit, piercing,
 *       enchantments) is touched, and it is never removed;</li>
 *   <li>a melee blow ({@code MeleeLegality}: it lands only when the victim is under the crosshair of the tracked aim).</li>
 * </ul>
 * No mixin and no PvP BOT class is involved. Fail-soft: one failure switches the whole thing off with one warning (the bot then
 * aims as PvP BOT does).
 */
public final class HumanAimDriver {
    private final Supplier<ServerSession> session;
    private final Logger log;
    private final Map<UUID, HumanAim.State> states = new HashMap<>();
    /** The rules of the last tick; null while the feature is off. */
    private volatile HumanAim.Params params;
    private boolean broken;
    /** True while the wrapper itself launches a projectile (the crossbow trigger already aimed it). */
    private boolean ownLaunch;
    private long arrowsReaimed;
    private long shotsHeld;

    public HumanAimDriver(Supplier<ServerSession> session, Logger log) {
        this.session = session;
        this.log = log;
    }

    /** Registers the arrow hook; call once from the mod entrypoint. */
    public void register() {
        ServerEntityEvents.ENTITY_LOAD.register(this::onEntityLoad);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> reset());
    }

    /** Server stopped: nothing timed survives. */
    public void reset() {
        states.clear();
        params = null;
        broken = false;
        ownLaunch = false;
    }

    /** True while human aim is on and running. */
    public boolean active() {
        return params != null && !broken;
    }

    /** Arrows re-aimed along the tracked aim since start (tests and diagnostics). */
    public long arrowsReaimed() {
        return arrowsReaimed;
    }

    /** Crossbow shots held back because the aim was not on target yet (tests and diagnostics). */
    public long shotsHeld() {
        return shotsHeld;
    }

    // ------------------------------------------------------------------ the per-tick turn

    /** Once per server tick, after PvP BOT's tick and the hunt's steering. */
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
            params = null;
            states.clear();
            log.warn("human aim failed and is switched off until restart: {}", t.toString());
        }
    }

    private void run(MinecraftServer server) {
        ServerSession current = session.get();
        if (current == null || current.server() != server) {
            return;
        }
        CommandServices services = current.services();
        InhabitantsConfig cfg = services == null ? null : services.config().get();
        if (cfg == null || services.population() == null) {
            return;
        }
        HumanAim.Params p = paramsOf(cfg);
        params = cfg.enabled && p.enabled() ? p : null;
        if (params == null) {
            states.clear();
            return;
        }
        long now = server.getTickCount();
        Set<UUID> seen = new HashSet<>();
        for (ServerPlayer bot : server.getPlayerList().getPlayers()) {
            if (!isInhabitant(services, bot)) {
                continue;
            }
            seen.add(bot.getUUID());
            if (bot.isRemoved() || !bot.isAlive()) {
                continue;
            }
            HumanAim.State st = states.computeIfAbsent(bot.getUUID(), k -> new HumanAim.State());
            HumanAim.Angles aim = st.advance(p, now, bot.getYRot(), bot.getXRot(), p.fireToleranceDeg());
            write(bot, aim.yaw(), aim.pitch());
        }
        states.keySet().retainAll(seen);
    }

    private static void write(ServerPlayer bot, double yaw, double pitch) {
        float y = (float) HumanAim.wrapDegrees(yaw);
        bot.setYRot(y);
        bot.setXRot((float) pitch);
        bot.setYHeadRot(y);
    }

    static HumanAim.Params paramsOf(InhabitantsConfig cfg) {
        InhabitantsConfig.AggroAim a = cfg.aggro == null || cfg.aggro.aim == null ? new InhabitantsConfig.AggroAim() : cfg.aggro.aim;
        return new HumanAim.Params(a.enabled, a.maxTurnDegPerSec, a.fireToleranceDeg, a.fireTargetRadius, a.jitterBaseDeg,
                a.jitterSettleDeg, a.jitterSettleSeconds);
    }

    // ------------------------------------------------------------------ what the rest of the addon asks

    /** The tracked look direction of an inhabitant, or null when human aim is off or the bot is not tracked yet. */
    public Vec3 lookOf(ServerPlayer bot) {
        HumanAim.State st = stateOf(bot);
        if (st == null) {
            return null;
        }
        double[] d = HumanAim.direction(st.yaw(), st.pitch());
        return new Vec3(d[0], d[1], d[2]);
    }

    private HumanAim.State stateOf(ServerPlayer bot) {
        if (params == null || broken) {
            return null;
        }
        HumanAim.State st = states.get(bot.getUUID());
        return st != null && st.started() ? st : null;
    }

    /**
     * What a crossbow shot at {@code target} looks like: {@code allowed} false while the aim is not on target yet; else the
     * rotation to shoot along (the tracked aim plus the hand's jitter). With human aim off, allowed and no rotation.
     *
     * @param aimed true when a rotation is given; false when human aim is not in force (the caller shoots as it is)
     */
    public record Shot(boolean allowed, boolean aimed, float yaw, float pitch, double errorDeg, double toleranceDeg,
                       double sigmaDeg) {
    }

    /** Decides a crossbow shot; see {@link Shot}. */
    public Shot decideShot(ServerPlayer bot, Entity target) {
        HumanAim.Params p = params;
        if (p == null || broken) {
            return new Shot(true, false, 0.0F, 0.0F, 0.0, 0.0, 0.0);
        }
        HumanAim.State st = states.get(bot.getUUID());
        long now = bot.level().getServer().getTickCount();
        double distance = target == null ? 0.0 : bot.getEyePosition().distanceTo(target.getEyePosition());
        double tolerance = HumanAim.toleranceDeg(p, distance);
        if (st == null || !st.started() || st.lastTick() != now || !st.onTarget(tolerance)) {
            shotsHeld++;
            return new Shot(false, true, 0.0F, 0.0F, st == null ? 180.0 : st.errorDeg(), tolerance, 0.0);
        }
        double sigma = HumanAim.jitterSigmaDeg(p, st.settledSeconds(now));
        HumanAim.Angles shot = HumanAim.jittered(st.yaw(), st.pitch(), sigma, bot.getRandom().nextGaussian(),
                bot.getRandom().nextGaussian());
        return new Shot(true, true, (float) HumanAim.wrapDegrees(shot.yaw()), (float) shot.pitch(), st.errorDeg(), tolerance, sigma);
    }

    /**
     * Runs {@code launch} (a vanilla launch of a projectile by the wrapper itself, e.g. {@code useItem} on a crossbow) with
     * the bot's rotation set to {@code shot}, then puts the tracked aim back. The projectile that enters the world meanwhile
     * is not re-aimed a second time.
     */
    public void launchAlong(ServerPlayer bot, Shot shot, Runnable launch) {
        if (!shot.aimed()) {
            launch.run();
            return;
        }
        float y = bot.getYRot();
        float x = bot.getXRot();
        float head = bot.getYHeadRot();
        boolean was = ownLaunch;
        ownLaunch = true;
        try {
            bot.setYRot(shot.yaw());
            bot.setXRot(shot.pitch());
            bot.setYHeadRot(shot.yaw());
            launch.run();
        } finally {
            ownLaunch = was;
            bot.setYRot(y);
            bot.setXRot(x);
            bot.setYHeadRot(head);
        }
    }

    /** Pins the tracked aim of {@code botName} to the rotation its entity has now (a spawn, a fixture, a test). */
    public boolean snap(String botName) {
        ServerSession current = session.get();
        if (current == null) {
            return false;
        }
        ServerPlayer bot = current.server().getPlayerList().getPlayerByName(botName);
        if (bot == null) {
            return false;
        }
        HumanAim.State st = states.computeIfAbsent(bot.getUUID(), k -> new HumanAim.State());
        st.snapTo(HumanAim.wrapDegrees(bot.getYRot()), bot.getXRot(), current.server().getTickCount());
        return true;
    }

    /** {yaw, pitch, wantedYaw, wantedPitch, errorDeg, settledSeconds} of the tracked aim of {@code botName}, or null. A seam for the GameTests. */
    public double[] aimOf(String botName) {
        ServerSession current = session.get();
        ServerPlayer bot = current == null ? null : current.server().getPlayerList().getPlayerByName(botName);
        HumanAim.State st = bot == null ? null : stateOf(bot);
        return st == null ? null : new double[]{st.yaw(), st.pitch(), st.wantedYaw(), st.wantedPitch(), st.errorDeg(),
                st.settledSeconds(current.server().getTickCount())};
    }

    // ------------------------------------------------------------------ arrows PvP BOT releases itself

    private void onEntityLoad(Entity entity, ServerLevel level) {
        if (!(entity instanceof AbstractArrow arrow) || ownLaunch || arrow.tickCount != 0 || params == null || broken) {
            return;
        }
        if (!(arrow.getOwner() instanceof ServerPlayer bot)) {
            return;
        }
        try {
            reaim(arrow, bot, level);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            broken = true;
            params = null;
            states.clear();
            log.warn("human aim failed re-aiming an arrow and is switched off until restart: {}", t.toString());
        }
    }

    /**
     * An arrow that just left an inhabitant's bow along PvP BOT's snapped rotation goes along the tracked aim plus jitter
     * instead. {@code shootFromRotation} is vanilla's: it sets the direction, adds the vanilla inaccuracy and the shooter's own
     * movement exactly as {@code BowItem} does; the launch speed is kept.
     */
    private void reaim(AbstractArrow arrow, ServerPlayer bot, ServerLevel level) {
        HumanAim.Params p = params;
        HumanAim.State st = states.get(bot.getUUID());
        if (p == null || st == null || !st.started()) {
            return;
        }
        // A freshly shot arrow starts at its shooter's eye and flies; one that merely loaded with its chunk does not.
        Vec3 velocity = arrow.getDeltaMovement();
        if (velocity.lengthSqr() < 1.0e-4 || arrow.position().distanceToSqr(bot.getEyePosition()) > 4.0) {
            return;
        }
        ServerSession current = session.get();
        CommandServices services = current == null ? null : current.services();
        if (services == null || services.population() == null || !isInhabitant(services, bot)) {
            return;
        }
        Vec3 own = bot.getKnownMovement();
        double speed = HumanAim.launchSpeed(velocity.x, velocity.y, velocity.z, own.x, bot.onGround() ? 0.0 : own.y, own.z);
        long now = level.getServer().getTickCount();
        double sigma = HumanAim.jitterSigmaDeg(p, st.settledSeconds(now));
        HumanAim.Angles along = HumanAim.jittered(st.yaw(), st.pitch(), sigma, bot.getRandom().nextGaussian(),
                bot.getRandom().nextGaussian());
        arrow.shootFromRotation(bot, (float) along.pitch(), (float) HumanAim.wrapDegrees(along.yaw()), 0.0F, (float) speed, 1.0F);
        arrowsReaimed++;
    }

    private static boolean isInhabitant(CommandServices services, ServerPlayer player) {
        return services.population().findBot(player.getName().getString()).isPresent();
    }
}

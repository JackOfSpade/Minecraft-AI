package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.combat.AggroController;
import dev.spawnbotswrapper.inhabitants.combat.AggroWorld;
import dev.spawnbotswrapper.inhabitants.combat.Perception;
import dev.spawnbotswrapper.inhabitants.command.CommandServices;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Runs the {@link AggroController} once per server tick over the real players, and answers "what is the aggro
 * state of this bot" for the diagnostics. All decisions live in the controller; this class only turns
 * {@link ServerPlayer}s into {@link AggroWorld} views and feeds the configuration in.
 * <p>
 * An inhabitant is recognised by this addon's own roster (the same lookup {@link CombatLogger} uses); nothing
 * about PvP BOT is consulted here, everything upstream goes through the adapter's target control.
 */
public final class AggroDriver {
    private final Supplier<ServerSession> session;
    private final Logger log;
    private AggroController controller;
    private ServerSession controllerSession;

    public AggroDriver(Supplier<ServerSession> session, Logger log) {
        this.session = session;
        this.log = log;
    }

    /** Once per server tick, after the session's own tick. */
    public void tick(MinecraftServer server) {
        ServerSession current = session.get();
        CommandServices services = current == null ? null : current.services();
        if (services == null || services.population() == null || services.adapter() == null) {
            return;
        }
        InhabitantsConfig cfg = services.config().get();
        if (cfg == null) {
            return;
        }
        if (controller == null || controllerSession != current) {
            controllerSession = current;
            controller = new AggroController(() -> configOf(services), services.adapter().targetControl(),
                    new AggroController.Log() {
                        @Override
                        public void debug(String message) {
                            log.debug(message);
                        }

                        @Override
                        public void info(String message) {
                            log.info(message);
                        }

                        @Override
                        public void warn(String message) {
                            log.warn(message);
                        }
                    });
        }
        controller.tick(server.getTickCount(), new World(server, services));
    }

    /** Server stopping: drop every timed record; nothing outlives the server it was made for. */
    public void reset() {
        AggroController c = controller;
        controller = null;
        controllerSession = null;
        if (c != null) {
            c.reset();
        }
    }

    /** A short diagnostic text about one bot's aggro state, or null when the controller has not started. */
    public String describe(String botName) {
        AggroController c = controller;
        return c == null ? null : c.describe(botName);
    }

    private static AggroController.Config configOf(CommandServices services) {
        InhabitantsConfig cfg = services.config().get();
        InhabitantsConfig.Aggro a = cfg == null || cfg.aggro == null ? new InhabitantsConfig.Aggro() : cfg.aggro;
        boolean on = cfg != null && cfg.enabled && a.enabled;
        return new AggroController.Config(on, a.acquireRange, a.requireLineOfSight, a.scanIntervalTicks,
                a.leashRange, a.loseSightTicks, a.returnToOrigin, a.returnArriveDistance, a.returnStuckTicks,
                a.returnMaxTicks, perceptionOf(a));
    }

    /** The shared perception rules from the {@code aggro.perception} block; the awareness is the chase's own 10 s rule. */
    static Perception.Params perceptionOf(InhabitantsConfig.Aggro a) {
        Perception.Params d = Perception.Params.defaults();
        InhabitantsConfig.AggroPerception p = a.perception == null ? new InhabitantsConfig.AggroPerception() : a.perception;
        return new Perception.Params(p.enabled, p.frontHalfAngleDeg, p.peripheralHalfAngleDeg, p.peripheralFactor,
                p.sneakFactor, p.hearWalk, p.hearSprint, p.hearCombat, d.hearNoisyMob(), d.hearPrimedCreeper(),
                d.hearWarden(), d.hearAnimal(), p.combatNoiseTicks, a.loseSightTicks);
    }

    /** Horizontal speed (blocks per tick) above which a body counts as moving, so it makes footstep noise. */
    private static final double MOVING_SPEED_SQ = 0.02 * 0.02;

    /**
     * Ticks since the entity last made combat noise: swinging an arm (attacks, and breaking or placing blocks),
     * getting hurt, or using an item that is heard (eating, drinking, drawing a bow or crossbow, a thrown trident;
     * raising a shield is silent); {@link Perception#NO_NOISE} when none.
     */
    private static int combatNoiseAge(LivingEntity e) {
        int age = Perception.NO_NOISE;
        if (e.swinging) {
            age = Math.min(age, e.swingTime);
        }
        if (e.hurtTime > 0) {
            age = Math.min(age, Math.max(0, e.hurtDuration - e.hurtTime));
        }
        if (e.isUsingItem()) {
            ItemUseAnimation anim = e.getUseItem().getUseAnimation();
            if (anim == ItemUseAnimation.EAT || anim == ItemUseAnimation.DRINK || anim == ItemUseAnimation.BOW
                    || anim == ItemUseAnimation.CROSSBOW || anim == ItemUseAnimation.SPEAR) {
                age = 0;
            }
        }
        return age;
    }

    /** Eye, look and stance of a living entity for {@link Perception}; null for anything else. */
    static AggroWorld.Senses sensesOf(Entity entity) {
        if (!(entity instanceof LivingEntity e)) {
            return null;
        }
        Vec3 eye = e.getEyePosition();
        Vec3 look = e.getViewVector(1.0F);
        double dx = e.getX() - e.xo;
        double dz = e.getZ() - e.zo;
        boolean moving = dx * dx + dz * dz > MOVING_SPEED_SQ;
        // Vanilla's visibility (invisibility with armor cover, worn mob heads) with its own sneak factor divided out:
        // sneaking is the perception model's factor, not counted twice.
        double visibility = e.getVisibilityPercent(null);
        if (e.isDiscrete()) {
            visibility /= 0.8;
        }
        Perception.Subject subject = new Perception.Subject(e.isDiscrete() || e.isCrouching(), moving,
                e.isSprinting(), combatNoiseAge(e), Perception.MobKind.NONE, visibility);
        return new AggroWorld.Senses(new AggroWorld.Pos(eye.x, eye.y, eye.z),
                new AggroWorld.Pos(look.x, look.y, look.z), subject);
    }

    /**
     * A clear view from {@code from}'s eye to {@code to}: a ray to its eye and, when that is blocked, a second to its
     * body centre. Vanilla's clip rules (collider shapes, fluids ignored): the blocks a vanilla mob cannot see through.
     */
    static boolean hasClearView(Entity from, Entity to) {
        Level level = from.level();
        if (to.level() != level) {
            return false;
        }
        Vec3 start = from.getEyePosition();
        Vec3 eye = to.getEyePosition();
        if (start.distanceTo(eye) > 128.0) {
            return false;
        }
        return clear(level, from, start, eye) || clear(level, from, start, to.position().add(0.0, to.getBbHeight() * 0.5, 0.0));
    }

    private static boolean clear(Level level, Entity from, Vec3 start, Vec3 end) {
        return level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, from))
                .getType() == HitResult.Type.MISS;
    }

    // ------------------------------------------------------------------ world view

    /** A target: a player, a bot or a mob. */
    private static class EntityBody implements AggroWorld.Body {
        final Entity entity;

        EntityBody(Entity entity) {
            this.entity = entity;
        }

        @Override
        public String name() {
            return entity.getName().getString();
        }

        @Override
        public boolean alive() {
            return entity.isAlive() && !entity.isRemoved();
        }

        @Override
        public boolean spectator() {
            return entity.isSpectator();
        }

        @Override
        public boolean creative() {
            return entity instanceof Player p && p.isCreative();
        }

        @Override
        public boolean invulnerable() {
            return entity.isInvulnerable();
        }

        @Override
        public boolean isPlayer() {
            return entity instanceof Player;
        }

        @Override
        public Object dimension() {
            return entity.level().dimension();
        }

        @Override
        public AggroWorld.Pos position() {
            return new AggroWorld.Pos(entity.getX(), entity.getY(), entity.getZ());
        }

        @Override
        public double distanceTo(AggroWorld.Body other) {
            return other instanceof EntityBody b ? entity.distanceTo(b.entity) : Double.MAX_VALUE;
        }

        @Override
        public Object handle() {
            return entity;
        }

        @Override
        public AggroWorld.Senses senses() {
            return sensesOf(entity);
        }
    }

    private static final class PlayerBody extends EntityBody implements AggroWorld.Watcher {
        private final ServerPlayer player;

        PlayerBody(ServerPlayer player) {
            super(player);
            this.player = player;
        }

        @Override
        public boolean canSee(AggroWorld.Body other) {
            return other instanceof EntityBody b && hasClearView(player, b.entity);
        }
    }

    private static final class World implements AggroWorld {
        private final MinecraftServer server;
        private final CommandServices services;

        World(MinecraftServer server, CommandServices services) {
            this.server = server;
            this.services = services;
        }

        /** The inhabitants online, found in ONE pass over the player list per tick (this view lives for one tick). */
        private List<PlayerBody> inhabitants;
        private Map<String, PlayerBody> byName;

        private void scan() {
            if (inhabitants != null) {
                return;
            }
            List<ServerPlayer> online = server.getPlayerList().getPlayers();
            inhabitants = new ArrayList<>(Math.min(online.size(), 16));
            byName = new HashMap<>();
            for (ServerPlayer p : online) {
                String name = p.getName().getString();
                if (services.population().findBot(name).isPresent()) {
                    PlayerBody body = new PlayerBody(p);
                    inhabitants.add(body);
                    byName.put(name.toLowerCase(Locale.ROOT), body);
                }
            }
        }

        @Override
        public List<? extends Watcher> inhabitants() {
            scan();
            return inhabitants;
        }

        @Override
        public Watcher inhabitant(String name) {
            scan();
            return byName.get(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public AggroWorld.Body bodyOf(Object entity) {
            return entity instanceof Entity e ? new EntityBody(e) : null;
        }

        @Override
        public List<? extends AggroWorld.Body> playersWithin(Watcher bot, double range) {
            if (!(bot instanceof PlayerBody b) || !(b.entity.level() instanceof ServerLevel level)) {
                return List.of();
            }
            double rangeSq = range * range;
            List<EntityBody> out = null;
            for (ServerPlayer p : level.players()) {
                if (p != b.entity && b.entity.distanceToSqr(p) <= rangeSq) {
                    if (out == null) {
                        out = new ArrayList<>(4);
                    }
                    out.add(new EntityBody(p));
                }
            }
            return out == null ? List.of() : out;
        }
    }
}

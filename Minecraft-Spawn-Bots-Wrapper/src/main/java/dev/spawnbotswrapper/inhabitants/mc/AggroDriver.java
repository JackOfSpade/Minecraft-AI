package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.combat.AggroController;
import dev.spawnbotswrapper.inhabitants.combat.AggroWorld;
import dev.spawnbotswrapper.inhabitants.combat.HitPoller;
import dev.spawnbotswrapper.inhabitants.combat.Perception;
import dev.spawnbotswrapper.inhabitants.command.CommandServices;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
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
 * {@link ServerPlayer}s into {@link AggroWorld} views (eyes, look, stance, occlusion rays, hits taken, candidate search
 * cells) and feeds the configuration in. Routes come from {@link VanillaPathPlanner}.
 * <p>
 * An inhabitant is recognised by this addon's own roster (the same lookup {@link CombatLogger} uses); nothing
 * about PvP BOT is consulted here, everything upstream goes through the adapter's target control.
 */
public final class AggroDriver {
    private final Supplier<ServerSession> session;
    private final Logger log;
    private AggroController controller;
    private ServerSession controllerSession;
    private final VanillaPathPlanner planner = new VanillaPathPlanner();
    /** Notices hits on inhabitants, also the ones the damage event never delivers; see {@link HitPoller}. */
    private final HitPoller hits = new HitPoller();
    private long lastHitPrune;
    /** Vanilla vibration listeners of the inhabitants (their hearing); see {@link InhabitantEars}. */
    private final InhabitantEars ears;

    public AggroDriver(Supplier<ServerSession> session, Logger log) {
        this.session = session;
        this.log = log;
        this.ears = new InhabitantEars(message -> log.warn("aggro: {}", message));
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
            controller = new AggroController(() -> configOf(services), services.adapter().targetControl(), planner,
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
        InhabitantsConfig.Aggro aggro = cfg.aggro == null ? new InhabitantsConfig.Aggro() : cfg.aggro;
        boolean hearing = cfg.enabled && aggro.enabled && (aggro.perception == null || aggro.perception.enabled);
        World world = new World(server, services, hits, ears);
        List<ServerPlayer> bodies = new ArrayList<>();
        for (AggroWorld.Watcher w : world.inhabitants()) {
            bodies.add(((PlayerBody) w).player);
        }
        ears.sync(bodies, hearing, aggro.hearing == null ? 16 : aggro.hearing.listenerRadius);
        controller.tick(server.getTickCount(), world);
        long now = server.getTickCount();
        if (now - lastHitPrune >= 100 || now < lastHitPrune) {
            lastHitPrune = now;
            java.util.Set<java.util.UUID> online = new java.util.HashSet<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                online.add(p.getUUID());
            }
            hits.retainOnly(online);
        }
    }

    /** Server stopping: drop every timed record; nothing outlives the server it was made for. */
    public void reset() {
        AggroController c = controller;
        controller = null;
        controllerSession = null;
        planner.reset();
        hits.reset();
        ears.closeAll();
        if (c != null) {
            c.reset();
        }
    }

    /** A short diagnostic text about one bot's aggro state, or null when the controller has not started. */
    public String describe(String botName) {
        AggroController c = controller;
        return c == null ? null : c.describe(botName);
    }

    /** Where {@code botName} is in the hunt; IDLE when it is not hunting or the controller has not started. */
    public AggroController.Phase phaseOf(String botName) {
        AggroController c = controller;
        return c == null ? AggroController.Phase.IDLE : c.phaseOf(botName);
    }

    /** Route planner statistics {plans made, plans that reached their goal, helper mobs alive}; a seam for the GameTests. */
    public long[] plannerStats() {
        return new long[]{planner.plans(), planner.reached(), planner.helperCount()};
    }

    /** The unique ids of the route planner's detached helper mobs; a seam for the GameTests. */
    public java.util.List<java.util.UUID> plannerHelperIds() {
        return planner.helperIds();
    }

    /** The home anchor of {@code botName} as {x, y, z}, or null. */
    public double[] homeOf(String botName) {
        AggroController c = controller;
        AggroWorld.Pos p = c == null ? null : c.homeOf(botName);
        return p == null ? null : new double[]{p.x(), p.y(), p.z()};
    }

    /** True while the aggro controller has this bot in an engagement with a player. */
    public boolean engaged(String botName) {
        AggroController c = controller;
        return c != null && c.isEngaged(botName);
    }

    /** The player {@code botName} has a CONFIRMED engagement with (the reaction time served), or null. A seam for the GameTests. */
    public String confirmedTarget(String botName) {
        AggroController c = controller;
        return c == null ? null : c.confirmedTarget(botName);
    }

    /**
     * Whether {@code botName} may hurt the player {@code victim} now: false only while the aggro controller manages the bot
     * and it has no CONFIRMED engagement with exactly that player (see {@link AggroController#mayAttackPlayer}). The damage
     * veto of {@link MeleeLegality} and the crossbow gate of {@link RangedFire} ask this.
     */
    public boolean mayAttackPlayer(String botName, String victim) {
        AggroController c = controller;
        return c == null || c.mayAttackPlayer(botName, victim);
    }

    /** How many vanilla vibration listeners the inhabitants have registered right now. A seam for the leak tests. */
    public int hearingListeners() {
        return ears.listenerCount();
    }

    private static AggroController.Config configOf(CommandServices services) {
        InhabitantsConfig cfg = services.config().get();
        InhabitantsConfig.Aggro a = cfg == null || cfg.aggro == null ? new InhabitantsConfig.Aggro() : cfg.aggro;
        boolean on = cfg != null && cfg.enabled && a.enabled;
        return new AggroController.Config(on, a.requireLineOfSight, a.loseGraceTicks, a.searchTicks,
                a.returnArriveDistance, a.stuckTicks, a.returnMaxTicks, a.scanIntervalTicks, perceptionOf(a));
    }

    /** The shared perception rules from the {@code aggro.perception} block. */
    static Perception.Params perceptionOf(InhabitantsConfig.Aggro a) {
        InhabitantsConfig.AggroPerception p = a.perception == null ? new InhabitantsConfig.AggroPerception() : a.perception;
        return new Perception.Params(p.enabled, p.reactionBaseSeconds, p.reactionAt64Seconds, p.fullAttentionHalfAngleDeg,
                p.peripheralHalfAngleDeg, p.peripheralMultiplier, p.sneakMultiplier);
    }

    /** Eye, look and stance of a living entity for {@link Perception}; null for anything else. */
    static AggroWorld.Senses sensesOf(Entity entity) {
        if (!(entity instanceof LivingEntity e)) {
            return null;
        }
        Vec3 eye = e.getEyePosition();
        Vec3 look = e.getViewVector(1.0F);
        // Vanilla's visibility (invisibility with armor cover, worn mob heads) with its own sneak factor divided out:
        // sneaking is the perception model's factor, not counted twice.
        double visibility = e.getVisibilityPercent(null);
        if (e.isDiscrete()) {
            visibility /= 0.8;
        }
        Perception.Subject subject = new Perception.Subject(e.isDiscrete() || e.isCrouching(), visibility);
        return new AggroWorld.Senses(new AggroWorld.Pos(eye.x, eye.y, eye.z),
                new AggroWorld.Pos(look.x, look.y, look.z), subject);
    }

    /**
     * The longest eye-to-eye ray cast for a sighting: vanilla {@code hasLineOfSight}'s own range. It only bounds the cost of
     * a ray; it is not an engagement rule (that is {@link AggroController#ENGAGE_LIMIT}).
     */
    static final double SIGHT_RAY_CAP = 128.0;

    /**
     * How far a blow from a non-projectile source can be felt as coming from its attacker: melee reach plus slack. A source
     * farther than this (a remote effect) tells the victim nothing about where it came from.
     */
    private static final double FELT_MELEE_RANGE = 8.0;

    /**
     * What the bot felt of the hit it just took (see {@link AggroWorld.Hit}): NO MAGIC, only the direction a blow came from.
     * A projectile: the reverse of its velocity at impact, and no attacker. Anything else with an attacker close by (melee):
     * the adjacent attacker. Everything else (fall, fire, a remote effect): nothing to go on.
     */
    static AggroWorld.Hit hitOf(ServerPlayer victim, DamageSource source) {
        Entity attacker = source.getEntity();
        Entity direct = source.getDirectEntity();
        Vec3 at = victim.position().add(0.0, victim.getBbHeight() * 0.5, 0.0);
        AggroWorld.Pos from = new AggroWorld.Pos(at.x, at.y, at.z);
        if (direct instanceof Projectile && direct != attacker) {
            Vec3 velocity = direct.getDeltaMovement();
            if (velocity.lengthSqr() < 1.0e-6) {
                return null;
            }
            Vec3 toward = velocity.scale(-1.0).normalize();
            return new AggroWorld.Hit(null, from, toward.x, toward.y, toward.z);
        }
        if (attacker != null && attacker != victim && attacker.distanceTo(victim) <= FELT_MELEE_RANGE) {
            Vec3 toward = attacker.position().add(0.0, attacker.getBbHeight() * 0.5, 0.0).subtract(at);
            Vec3 unit = toward.lengthSqr() < 1.0e-6 ? new Vec3(0.0, 0.0, 1.0) : toward.normalize();
            return new AggroWorld.Hit(new EntityBody(attacker), from, unit.x, unit.y, unit.z);
        }
        return null;
    }

    /**
     * The last free point on the line from {@code from} along {@code toward} (a unit vector), up to {@code limit} blocks: the
     * first blocking block ends it (vanilla collider clip, fluids ignored), else it runs the whole way.
     */
    static AggroWorld.Pos traceBack(ServerPlayer bot, AggroWorld.Pos from, double dx, double dy, double dz, double limit) {
        Vec3 start = new Vec3(from.x(), from.y(), from.z());
        Vec3 end = start.add(dx * limit, dy * limit, dz * limit);
        HitResult hit = bot.level().clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
        Vec3 stop = hit.getType() == HitResult.Type.MISS ? end : hit.getLocation().subtract(dx * 0.4, dy * 0.4, dz * 0.4);
        return new AggroWorld.Pos(stop.x, stop.y, stop.z);
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
        if (start.distanceTo(eye) > SIGHT_RAY_CAP) {
            return false;
        }
        return clear(level, from, start, eye) || clear(level, from, start, to.position().add(0.0, to.getBbHeight() * 0.5, 0.0));
    }

    private static boolean clear(Level level, Entity from, Vec3 start, Vec3 end) {
        return level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, from))
                .getType() == HitResult.Type.MISS;
    }

    // ------------------------------------------------------------------ search cells

    /** Candidate cells on a grid of this many blocks around the search focus. */
    private static final int SPOT_STEP = 2;
    /** At most this many candidate cells are scored (bounds the rays per search point). */
    private static final int MAX_SPOTS = 24;
    /** Sample points that stand for "space around the focus"; the ones hidden from the bot are what a spot may open up. */
    private static final int[] SAMPLE_RADII = {4, 9};
    private static final int SAMPLES_PER_RING = 8;

    /**
     * Standable cells around {@code focus} with their opening value: the share of the sample points that are hidden from
     * the bot's eye where now that become visible from the cell. Corners, doorways and corridor branches score high, open
     * floor scores low. About {@code MAX_SPOTS * 16} rays once per search point, not per tick.
     */
    static List<AggroWorld.SearchSpot> searchSpots(ServerPlayer bot, AggroWorld.Pos focus, double radius) {
        Level level = bot.level();
        BlockPos centre = BlockPos.containing(focus.x(), focus.y(), focus.z());
        List<BlockPos> cells = new ArrayList<>();
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx += SPOT_STEP) {
            for (int dz = -r; dz <= r; dz += SPOT_STEP) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                BlockPos stand = standable(level, centre.getX() + dx, centre.getY(), centre.getZ() + dz);
                if (stand != null) {
                    cells.add(stand);
                }
            }
        }
        if (cells.isEmpty()) {
            return List.of();
        }
        if (cells.size() > MAX_SPOTS) {
            List<BlockPos> thinned = new ArrayList<>(MAX_SPOTS);
            double stride = (double) cells.size() / MAX_SPOTS;
            for (int i = 0; i < MAX_SPOTS; i++) {
                thinned.add(cells.get((int) (i * stride)));
            }
            cells = thinned;
        }
        // The space around the focus, as sample points at eye height; those inside solid blocks are dropped.
        List<Vec3> hidden = new ArrayList<>();
        Vec3 botEye = bot.getEyePosition();
        for (int radiusRing : SAMPLE_RADII) {
            for (int i = 0; i < SAMPLES_PER_RING; i++) {
                double a = i * (2.0 * Math.PI / SAMPLES_PER_RING);
                Vec3 sample = new Vec3(focus.x() + Math.cos(a) * radiusRing, focus.y() + 1.62, focus.z() + Math.sin(a) * radiusRing);
                if (!level.getBlockState(BlockPos.containing(sample)).getCollisionShape(level, BlockPos.containing(sample)).isEmpty()) {
                    continue;
                }
                if (!clear(level, bot, botEye, sample)) {
                    hidden.add(sample);
                }
            }
        }
        List<AggroWorld.SearchSpot> out = new ArrayList<>(cells.size());
        for (BlockPos c : cells) {
            Vec3 eye = new Vec3(c.getX() + 0.5, c.getY() + 1.62, c.getZ() + 0.5);
            int opened = 0;
            for (Vec3 h : hidden) {
                if (clear(level, bot, eye, h)) {
                    opened++;
                }
            }
            double opening = hidden.isEmpty() ? 0.0 : (double) opened / hidden.size();
            out.add(new AggroWorld.SearchSpot(new AggroWorld.Pos(c.getX() + 0.5, c.getY(), c.getZ() + 0.5), opening));
        }
        return out;
    }

    /** The feet cell of a standable spot in this column near {@code y} (solid floor, two free cells, no liquid), or null. */
    private static BlockPos standable(Level level, int x, int y, int z) {
        for (int dy = 2; dy >= -3; dy--) {
            BlockPos feet = new BlockPos(x, y + dy, z);
            BlockPos floor = feet.below();
            BlockState floorState = level.getBlockState(floor);
            if (floorState.isFaceSturdy(level, floor, Direction.UP)
                    && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                    && level.getFluidState(feet).isEmpty() && level.getFluidState(floor).isEmpty()) {
                return feet;
            }
        }
        return null;
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
        private final HitPoller hits;
        private final InhabitantEars ears;

        PlayerBody(ServerPlayer player, HitPoller hits, InhabitantEars ears) {
            super(player);
            this.player = player;
            this.hits = hits;
            this.ears = ears;
        }

        @Override
        public boolean canSee(AggroWorld.Body other) {
            return other instanceof EntityBody b && hasClearView(player, b.entity);
        }

        @Override
        public boolean plainLineOfSight(AggroWorld.Body other) {
            return other instanceof EntityBody b && player.hasLineOfSight(b.entity);
        }

        @Override
        public AggroWorld.Hit newHit() {
            DamageSource source = player.getLastDamageSource();
            HitPoller.Hit hit = hits.observe(player.getUUID(), source, player.getHealth() + player.getAbsorptionAmount());
            if (hit == null || source == null) {
                return null;
            }
            return hitOf(player, source);
        }

        @Override
        public List<AggroWorld.Sound> drainSounds() {
            return ears.drain(player);
        }

        @Override
        public AggroWorld.Pos traceBack(AggroWorld.Pos from, double dx, double dy, double dz, double limit) {
            return AggroDriver.traceBack(player, from, dx, dy, dz, limit);
        }
    }

    private static final class World implements AggroWorld {
        private final MinecraftServer server;
        private final CommandServices services;
        private final HitPoller hits;
        private final InhabitantEars ears;

        World(MinecraftServer server, CommandServices services, HitPoller hits, InhabitantEars ears) {
            this.server = server;
            this.services = services;
            this.hits = hits;
            this.ears = ears;
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
                    PlayerBody body = new PlayerBody(p, hits, ears);
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

        @Override
        public List<AggroWorld.SearchSpot> searchSpots(Watcher bot, AggroWorld.Pos focus, double radius) {
            return bot instanceof PlayerBody b ? AggroDriver.searchSpots(b.player, focus, radius) : List.of();
        }
    }
}

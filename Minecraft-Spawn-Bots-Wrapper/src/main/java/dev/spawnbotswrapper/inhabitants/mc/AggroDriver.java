package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.combat.AggroController;
import dev.spawnbotswrapper.inhabitants.combat.AggroWorld;
import dev.spawnbotswrapper.inhabitants.command.CommandServices;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
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
                a.returnMaxTicks);
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
    }

    private static final class PlayerBody extends EntityBody implements AggroWorld.Watcher {
        private final ServerPlayer player;

        PlayerBody(ServerPlayer player) {
            super(player);
            this.player = player;
        }

        @Override
        public boolean canSee(AggroWorld.Body other) {
            return other instanceof EntityBody b && player.hasLineOfSight(b.entity);
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

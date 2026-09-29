package dev.spawnbotswrapper.inhabitants.adapter;

import com.mojang.brigadier.CommandDispatcher;
import dev.spawnbotswrapper.inhabitants.adapter.SettingsHygiene.SettingsSnapshot;
import dev.spawnbotswrapper.inhabitants.adapter.StatusAssembler.Assembly;
import dev.spawnbotswrapper.inhabitants.adapter.StatusAssembler.ProbeInput;
import dev.spawnbotswrapper.inhabitants.adapter.StatusAssembler.Verdict;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * THE ONLY class of the addon that may know PvP BOT (or HeroBot) exists. Everything upstream is reached
 * by reflection on class NAMES (never initialised while probing) and by public commands; there is no
 * compile-time dependency and no mixin. If PvP BOT changes, this package is what needs updating.
 * <p>
 * What the integration is built on, all learned from upstream and none of it trustable:
 * <ul>
 *   <li>PvP BOT has no API. Its spawn method answers TRUE even when nothing spawned, so its answer is never
 *       used as evidence; a spawn is only ever confirmed by observing the player entity AND PvP BOT's list
 *       ({@link #pollSpawn}).</li>
 *   <li>The entity appears asynchronously, and PvP BOT forgets a listed name whose entity takes longer than
 *       about 2.5 seconds. Such a bot exists but is no longer managed; re-calling spawn for an ONLINE name is
 *       upstream's way of listing it again, and is done here only for names a spawn was requested for.</li>
 *   <li>Identity is the NAME. PvP BOT's list is case-sensitive, vanilla's lookups are not; every comparison
 *       here is case-insensitive and PvP BOT is always handed the spelling it listed.</li>
 *   <li>PvP BOT's removal wipes the inventory of whatever player the name resolves to, so it is only ever
 *       called for an online bot entity; real players are refused.</li>
 *   <li>PvP BOT's settings are ONE global singleton: they are read, never written.</li>
 * </ul>
 * Everything runs on the server thread and never throws for upstream failures: they become return values,
 * {@link Status}, and at most one log line per distinct problem.
 * <p>
 * What only a running server can prove (a unit test cannot create a {@code MinecraftServer}, and faking one
 * would prove nothing): that the console-derived command source has the right world and position, that the
 * Brigadier dispatch of the fallback tier reaches PvP BOT, that the fake player really appears and is
 * recognised by its class name, and that an orphaned bot is really adopted again. Everything around those
 * steps (probe, classification, tier order, poll protocol, path building, cleanup) is covered by tests.
 */
public final class PvpBotAdapter implements PvpBotOperations {

    static final String LOGGER_NAME = "pvpbot_inhabitants";

    /** Everything a probe established, replaced atomically by the next probe. */
    private record Probed(ProbeInput input, UpstreamCalls calls, Verdict verdict) {
    }

    private final String addonVersion;
    private final Environment environment;
    private final ClassLocator locator;
    private final Diagnostics log;

    private volatile SpawnBackend backend;
    private volatile Status status;
    private volatile Probed probed;
    private volatile Set<String> settingNames;

    private final ListedNamesCache listedCache = new ListedNamesCache();
    private final SpawnTracker spawns;
    private final PatrolManager patrols;
    private WeakReference<MinecraftServer> lastServer = new WeakReference<>(null);
    private boolean observedAServer;

    /** @param addonVersion version string of this addon, for reports */
    public PvpBotAdapter(String addonVersion) {
        this(addonVersion, SpawnBackend.AUTO);
    }

    /**
     * @param addonVersion version string of this addon, for reports
     * @param backend      the {@code spawning.backend} config value: AUTO, CLASS or COMMAND (anything else is AUTO)
     */
    public PvpBotAdapter(String addonVersion, String backend) {
        this(addonVersion, SpawnBackend.parse(backend));
    }

    private PvpBotAdapter(String addonVersion, SpawnBackend backend) {
        this(addonVersion, new FabricEnvironment(), ClassLocator.forLoader(PvpBotAdapter.class.getClassLoader()),
                Diagnostics.slf4j(LOGGER_NAME), backend);
    }

    /** Test seam: every external dependency (mod loader, class lookup, log) replaced. */
    PvpBotAdapter(String addonVersion, Environment environment, ClassLocator locator, Diagnostics.Sink sink,
                  SpawnBackend backend) {
        this.addonVersion = addonVersion == null ? "unknown" : addonVersion;
        this.environment = environment;
        this.locator = locator;
        this.log = new Diagnostics(sink);
        this.backend = backend == null ? SpawnBackend.AUTO : backend;
        this.spawns = new SpawnTracker(log);
        this.patrols = new PatrolManager(log);
        this.status = notProbed();
    }

    private Status notProbed() {
        return new Status(Availability.UNAVAILABLE, "unknown", "unknown", addonVersion, SpawnTier.NONE.label(),
                "PvP BOT integration has not been probed yet", List.of(), List.of());
    }

    // ================================================================ configuration

    /** Applies {@code spawning.backend}; takes effect immediately, the status is re-derived without re-probing. */
    public void setSpawnBackend(String value) {
        setSpawnBackend(SpawnBackend.parse(value));
    }

    public void setSpawnBackend(SpawnBackend value) {
        this.backend = value == null ? SpawnBackend.AUTO : value;
        Probed p = probed;
        if (p != null) {
            Assembly a = StatusAssembler.assemble(p.input(), this.backend);
            probed = new Probed(p.input(), p.calls(), a.verdict());
            status = a.status();
        }
    }

    public SpawnBackend spawnBackend() {
        return backend;
    }

    // ================================================================ availability / diagnostics

    @Override
    public Status status() {
        return status;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Timing matters for one reason: the probe reads PvP BOT's settings through its singleton, and that
     * singleton loads (and creates) its per-world file on first access. Probed before PvP BOT's own start-up
     * has run, it would bind to a default world folder for the rest of the session. So this must not be
     * called before PvP BOT's server-started work has finished (one tick after SERVER_STARTED).
     */
    @Override
    public Status probe(MinecraftServer server) {
        try {
            observe(server);
            return probeWith(commandTreeOf(server));
        } catch (Throwable t) {
            log.failure("probing PvP BOT", t);
            return status;
        }
    }

    /**
     * The probe proper, with the command tree supplied: reads versions from the mod loader, resolves the
     * reflection contract, inspects PvP BOT's read-only settings and its statistics opt-out, classifies, and
     * logs ONE report. Never throws.
     */
    Status probeWith(CommandTree tree) {
        try {
            Optional<String> pvp = versionOf(UpstreamNames.MOD_PVP_BOT);
            Optional<String> hero = versionOf(UpstreamNames.MOD_HEROBOT);
            boolean carpet = versionOf(UpstreamNames.MOD_CARPET).isPresent();

            UpstreamContract contract = pvp.isPresent() ? new UpstreamContract(locator) : null;
            SettingsSnapshot settings = null;
            TelemetryProbe.Telemetry telemetry = TelemetryProbe.Telemetry.UNKNOWN;
            UpstreamCalls calls = null;
            if (contract != null) {
                calls = new UpstreamCalls(contract);
                settings = contract.settingsGet.ok() ? snapshotOf(calls) : null;
                telemetry = TelemetryProbe.read(environment.configDir().orElse(null));
            }

            ProbeInput input = new ProbeInput(addonVersion, pvp.orElse(null), hero.orElse(null), carpet, contract,
                    tree, settings, telemetry);
            Assembly assembly = StatusAssembler.assemble(input, backend);
            probed = new Probed(input, calls, assembly.verdict());
            status = assembly.status();
            listedCache.invalidate();
            report(assembly);
            return assembly.status();
        } catch (Throwable t) {
            log.failure("probing PvP BOT", t);
            Status failed = new Status(Availability.UNAVAILABLE, "unknown", "unknown", addonVersion,
                    SpawnTier.NONE.label(), "PvP BOT integration unavailable: the probe itself failed ("
                    + Diagnostics.describe(t) + ")", List.of(), List.of());
            probed = null;
            status = failed;
            return failed;
        }
    }

    private void report(Assembly assembly) {
        String text = ReportFormatter.text(assembly.status());
        switch (assembly.level()) {
            case ERROR -> log.error(text);
            case WARN -> log.warn(text);
            default -> log.info(text);
        }
    }

    private Optional<String> versionOf(String modId) {
        try {
            Optional<String> v = environment.modVersion(modId);
            return v == null ? Optional.empty() : v;
        } catch (Throwable t) {
            log.failure("asking the mod loader for " + modId, t);
            return Optional.empty();
        }
    }

    private SettingsSnapshot snapshotOf(UpstreamCalls calls) {
        try {
            Object settings = calls.settingsInstance();
            return new SettingsSnapshot(
                    calls.readBoolean(settings, "isBotsRelogs"),
                    calls.readBoolean(settings, "isBotLeaveOnDeath"),
                    calls.readInt(settings, "getCheckInterval"),
                    calls.readBoolean(settings, "isAutoTargetEnabled"));
        } catch (Throwable t) {
            log.failure("reading PvP BOT's settings", t);
            return SettingsSnapshot.unreadable();
        }
    }

    private CommandTree commandTreeOf(MinecraftServer server) {
        if (server == null) {
            return CommandTree.UNKNOWN;
        }
        try {
            return CommandTree.scan(server.getCommands().getDispatcher().getRoot());
        } catch (Throwable t) {
            log.failure("inspecting the command tree", t);
            return CommandTree.UNKNOWN;
        }
    }

    @Override
    public GlobalCapabilities readCapabilities() {
        GlobalCapabilities defaults = GlobalCapabilities.upstreamDefaults();
        Probed p = probed;
        if (p == null || p.calls() == null || !p.calls().contract().settingsGet.ok()) {
            return defaults;
        }
        try {
            return CapabilityReader.read(p.calls(), defaults, log);
        } catch (Throwable t) {
            log.failure("reading PvP BOT's settings", t);
            return defaults;
        }
    }

    @Override
    public Set<String> discoverUpstreamSettingNames() {
        Set<String> cached = settingNames;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> type = locator.load(UpstreamNames.CLASS_BOT_SETTINGS);
            Set<String> names = new TreeSet<>();
            for (Field f : type.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
                    names.add(f.getName());
                }
            }
            Set<String> result = Collections.unmodifiableSet(names);
            settingNames = result;
            return result;
        } catch (Throwable t) {
            log.debugOnce("settings-discovery|" + Diagnostics.describe(t),
                    "PvP BOT's settings fields cannot be listed (" + Diagnostics.describe(t) + ")");
            return Set.of();
        }
    }

    // ================================================================ spawning

    @Override
    public boolean nameAvailable(MinecraftServer server, String name) {
        try {
            observe(server);
            Map<String, String> listed = listedOrNull(server);
            return NameRules.check(name, onlinePlayerExists(server, name),
                    listed == null ? null : listed.keySet()) == NameRules.Check.FREE;
        } catch (Throwable t) {
            log.failure("checking whether a bot name is free", t);
            return false;
        }
    }

    @Override
    public SpawnTicket requestSpawn(MinecraftServer server, ServerLevel world, String name,
                                    double x, double y, double z, float yaw) {
        observeQuietly(server);
        SpawnTicket ticket = spawns.open(name, tickOf(server));
        try {
            String refusal = spawnRefusal(server, world, name, x, y, z);
            if (refusal != null) {
                spawns.fail(ticket, refusal);
                log.debugOnce("spawn-refused|" + refusal, "spawn of '" + name + "' not requested: " + refusal);
            } else {
                issueSpawn(server, world, ticket, x, y, z, yaw);
            }
        } catch (Throwable t) {
            spawns.fail(ticket, "unexpected failure while requesting the spawn (" + Diagnostics.describe(t) + ")");
            log.failure("requesting a spawn", t);
        }
        return ticket;
    }

    /** Why a spawn may not even be attempted, or null. Nothing here calls PvP BOT's spawn. */
    private String spawnRefusal(MinecraftServer server, ServerLevel world, String name,
                                double x, double y, double z) {
        Probed p = probed;
        if (p == null || !p.verdict().usable() || p.calls() == null) {
            return "PvP BOT integration is not available: " + status.summary();
        }
        if (server == null || world == null) {
            return "no server or world given";
        }
        if (!server.isSameThread()) {
            return "the spawn was requested off the server thread";
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return "the spawn position is not finite";
        }
        Map<String, String> listed = listedOrNull(server);
        NameRules.Check check = NameRules.check(name, onlinePlayerExists(server, name),
                listed == null ? null : listed.keySet());
        return check == NameRules.Check.FREE ? null : NameRules.refusal(check, name);
    }

    private void issueSpawn(MinecraftServer server, ServerLevel world, SpawnTicket ticket,
                            double x, double y, double z, float yaw) {
        Probed p = probed;
        Vec3 position = new Vec3(x, y, z);
        // A console-derived source: PvP BOT's spawn dispatches HeroBot's playerspawn command with it, and that
        // command refuses non-operator players. Silenced so command feedback does not spam operators. The world
        // comes FIRST because withLevel rescales the position by the dimensions' coordinate scale, and the
        // position LAST so nothing can alter it. PvP BOT 0.0.15 always spawns facing 0/0 itself; the rotation
        // is carried for the day it stops doing that.
        CommandSourceStack source = server.createCommandSourceStack()
                .withSuppressedOutput()
                .withRotation(new Vec2(0.0F, Float.isFinite(yaw) ? yaw : 0.0F))
                .withLevel(world)
                .withPosition(position);
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        UpstreamCalls.SpawnAttempt attempt = p.calls().spawn(p.verdict().tierOrder(), server, ticket.name(), source,
                position, command -> dispatcher.execute(command, source));
        listedCache.invalidate();
        if (!attempt.issued()) {
            String reason = "no spawn tier could issue the request (" + String.join("; ", attempt.failures()) + ")";
            spawns.fail(ticket, reason);
            log.failure("requesting a spawn", new IllegalStateException(reason));
            return;
        }
        for (String failure : attempt.failures()) {
            log.warnOnce("spawn-fallback|" + failure, "PvP BOT integration: a spawn tier failed (" + failure
                    + "); fell back to " + attempt.tierUsed().label());
        }
        if (Boolean.FALSE.equals(attempt.upstreamResult())) {
            log.debugOnce("spawn-false|" + attempt.tierUsed(), "PvP BOT answered false to the "
                    + attempt.tierUsed().label() + " spawn of '" + ticket.name() + "' (an online player of that "
                    + "name already existed, or upstream refused); the poll decides what actually happened");
        }
    }

    @Override
    public SpawnState pollSpawn(MinecraftServer server, SpawnTicket ticket) {
        try {
            observe(server);
            return spawns.poll(ticket, new LiveWorld(server));
        } catch (Throwable t) {
            // The engine owns the deadline; an internal hiccup must not fail a spawn that may be fine.
            log.failure("polling a spawn", t);
            return new SpawnState.Pending();
        }
    }

    /** What a poll sees of the real server: its player list, PvP BOT's list, and the way to re-list a bot. */
    private final class LiveWorld implements SpawnTracker.World {
        private final MinecraftServer server;

        LiveWorld(MinecraftServer server) {
            this.server = server;
        }

        @Override
        public long tick() {
            return tickOf(server);
        }

        @Override
        public SpawnTracker.Player player(String name) {
            ServerPlayer entity = playerNamed(server, name);
            return entity == null ? null : new SpawnTracker.Player(isBotEntity(entity), entity.getUUID(), entity);
        }

        @Override
        public boolean listed(String name) {
            Map<String, String> listed = listedOrNull(server);
            return listed != null && listed.containsKey(NameRules.key(name));
        }

        @Override
        public boolean canRelist() {
            Probed p = probed;
            return p != null && p.calls() != null && p.verdict().usable() && p.verdict().canAdopt();
        }

        @Override
        public void relist(String name, SpawnTracker.Player player) throws Throwable {
            ServerPlayer entity = (ServerPlayer) player.handle();
            // The source describes where the bot really is, so upstream's own record of it matches reality.
            CommandSourceStack source = server.createCommandSourceStack()
                    .withSuppressedOutput()
                    .withLevel(entity.level())
                    .withPosition(entity.position());
            probed.calls().adopt(server, name, source, entity.position());
            listedCache.invalidate();
        }
    }

    @Override
    public Optional<ServerPlayer> findBotEntity(MinecraftServer server, String name) {
        try {
            ServerPlayer player = playerNamed(server, name);
            return player != null && isBotEntity(player) ? Optional.of(player) : Optional.empty();
        } catch (Throwable t) {
            log.failure("looking up a bot entity", t);
            return Optional.empty();
        }
    }

    @Override
    public boolean isManaged(String name) {
        try {
            if (name == null) {
                return false;
            }
            Map<String, String> listed = listedOrNull(lastServer.get());
            return listed != null && listed.containsKey(NameRules.key(name));
        } catch (Throwable t) {
            log.failure("checking PvP BOT's bot list", t);
            return false;
        }
    }

    @Override
    public boolean isBotEntity(ServerPlayer player) {
        try {
            return player != null && UpstreamNames.isBotClassName(player.getClass().getName());
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean removeBot(MinecraftServer server, String name) {
        try {
            observe(server);
            Probed p = probed;
            if (p == null || !p.verdict().usable() || p.calls() == null) {
                return false;
            }
            if (server == null || !NameRules.isValid(name) || !server.isSameThread()) {
                return false;
            }
            ServerPlayer entity = server.getPlayerList().getPlayerByName(name);
            if (entity == null || !isBotEntity(entity)) {
                log.debugOnce("remove-refused|" + NameRules.key(name), "not removing '" + name
                        + "': there is no online bot entity with that name (real players are never removed)");
                return false;
            }
            String exact = botNameFor(server, entity.getScoreboardName());
            CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
            CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
            // PvP BOT's own removal internally runs "clear <name>" with a FRESH, non-silent source of its own
            // (ignoring the silent one we pass in, which only covers the follow-up kill sub-command) -- that
            // broadcasts vanilla's "[Server: Removed N item(s) from player X]" clear feedback to every player.
            // It goes through CommandSourceStack#sendSuccess's ops-broadcast path, which Fabric API has no
            // event for (confirmed by decompilation: it never calls PlayerList#broadcastSystemMessage, the
            // method GameMessageFilter hooks) -- so the only lever available is the gamerule that broadcast
            // itself is gated on, toggled off for just this one synchronous call and restored immediately after.
            GameRules gameRules = server.overworld().getGameRules();
            boolean feedbackWasEnabled = gameRules.get(GameRules.SEND_COMMAND_FEEDBACK);
            UpstreamCalls.RemoveAttempt attempt;
            try {
                gameRules.set(GameRules.SEND_COMMAND_FEEDBACK, false, server);
                attempt = p.calls().remove(p.verdict().removeCommandRegistered(), server,
                        exact, source, command -> dispatcher.execute(command, source));
            } finally {
                gameRules.set(GameRules.SEND_COMMAND_FEEDBACK, feedbackWasEnabled, server);
            }
            listedCache.invalidate();
            patrols.clear(patrolCalls(), name);
            if (!attempt.issued()) {
                log.failure("removing a bot", new IllegalStateException(String.join("; ", attempt.failures())));
                return false;
            }
            return true;
        } catch (Throwable t) {
            log.failure("removing a bot", t);
            return false;
        }
    }

    // ================================================================ per-bot behaviour (PvP BOT paths)

    @Override
    public boolean assignPatrol(MinecraftServer server, String botName, BotProfile.Behavior behavior) {
        try {
            observe(server);
            UpstreamCalls calls = patrolCalls();
            if (calls == null) {
                log.debugOnce("patrol-unavailable", "no patrol assigned: PvP BOT's path API is not available");
                return false;
            }
            if (server != null && !server.isSameThread()) {
                return false;
            }
            if (!NameRules.isValid(botName)) {
                log.debugOnce("patrol-name|" + botName, "no patrol assigned: '" + botName + "' is not a valid bot name");
                return false;
            }
            UpstreamPathPlanner.Outcome outcome = UpstreamPathPlanner.plan(botName, behavior);
            if (outcome.plan() == null) {
                log.debugOnce("patrol-plan|" + outcome.rejection(), "no patrol assigned to " + botName + ": "
                        + outcome.rejection());
                return false;
            }
            return patrols.assign(calls, botNameFor(server, botName), outcome.plan());
        } catch (Throwable t) {
            log.failure("assigning a patrol", t);
            return false;
        }
    }

    @Override
    public void clearPatrol(String botName) {
        try {
            patrols.clear(patrolCalls(), botName);
        } catch (Throwable t) {
            log.failure("clearing a patrol", t);
        }
    }

    @Override
    public boolean isPatrolling(String botName) {
        try {
            return patrols.isPatrolling(patrolCalls(), botName);
        } catch (Throwable t) {
            log.failure("checking a patrol", t);
            return false;
        }
    }

    /**
     * Releases every path this addon created and forgets the tracking. For SERVER_STOPPING: PvP BOT keeps
     * its path table in static memory for the life of the JVM, so a singleplayer session that opens another
     * world would otherwise carry this world's paths into it. Idempotent; returns how many were tracked.
     */
    public int releaseAllPatrols() {
        try {
            return patrols.releaseAll(patrolCalls());
        } catch (Throwable t) {
            log.failure("releasing patrols", t);
            return 0;
        }
    }

    /** The call layer for paths, or null when the probe did not find a complete, usable path API. */
    private UpstreamCalls patrolCalls() {
        Probed p = probed;
        return p != null && p.calls() != null && p.verdict().usable() && p.verdict().patrolCapable()
                ? p.calls() : null;
    }

    // ================================================================ helpers

    private void observeQuietly(MinecraftServer server) {
        try {
            observe(server);
        } catch (Throwable t) {
            log.failure("tracking the server instance", t);
        }
    }

    /** Tracks the server instance; a different one (singleplayer world switch) invalidates everything tied to the old. */
    private void observe(MinecraftServer server) {
        if (server == null || lastServer.get() == server) {
            return;
        }
        boolean hadOne = observedAServer;
        lastServer = new WeakReference<>(server);
        observedAServer = true;
        listedCache.invalidate();
        spawns.clear();
        if (hadOne) {
            // PvP BOT's statics outlive the old world: take our paths out before they leak into the new one.
            releaseAllPatrols();
        }
    }

    private static long tickOf(MinecraftServer server) {
        try {
            return server == null ? -1L : server.getTickCount();
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static ServerPlayer playerNamed(MinecraftServer server, String name) {
        if (server == null || name == null || name.isEmpty()) {
            return null;
        }
        return server.getPlayerList().getPlayerByName(name);
    }

    private static boolean onlinePlayerExists(MinecraftServer server, String name) {
        return playerNamed(server, name) != null;
    }

    /** PvP BOT's listed bots (key to exact spelling), read once per tick; null when unreadable. */
    private Map<String, String> listedOrNull(MinecraftServer server) {
        Probed p = probed;
        if (p == null || p.calls() == null || !p.calls().contract().getAllBots.ok()) {
            return null;
        }
        try {
            return listedCache.get(tickOf(server), p.calls()::listedBots);
        } catch (Throwable t) {
            log.failure("reading PvP BOT's bot list", t);
            return null;
        }
    }

    /** The spelling PvP BOT lists this bot under (its bookkeeping is case-sensitive), else the given one. */
    private String botNameFor(MinecraftServer server, String name) {
        Map<String, String> listed = listedOrNull(server);
        String exact = listed == null ? null : listed.get(NameRules.key(name));
        return exact != null ? exact : name;
    }
}

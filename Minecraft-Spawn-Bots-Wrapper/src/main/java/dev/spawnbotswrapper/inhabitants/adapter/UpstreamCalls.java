package dev.spawnbotswrapper.inhabitants.adapter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The only place that INVOKES PvP BOT methods, on the handles a successful {@link UpstreamContract} probe
 * resolved. Every method here may throw whatever upstream throws (already unwrapped from
 * {@link InvocationTargetException}); the adapter is the layer that decides how to survive it.
 * <p>
 * Arguments of Minecraft types are declared as {@link Object} so the spawn/remove orchestration can be
 * exercised in tests with plain fakes; reflection checks the real types on invocation.
 */
final class UpstreamCalls {

    /** Runs one command line as the console-derived source the caller prepared. Returns the command's result. */
    @FunctionalInterface
    interface CommandRunner {
        int run(String command) throws Exception;
    }

    /**
     * What a spawn attempt achieved. {@code tierUsed} is null when no tier could even issue the call
     * ({@code failures} then says why). {@code upstreamResult} is upstream's own boolean, kept for the logs
     * only: it is NOT evidence of success (upstream returns true when nothing spawned).
     */
    record SpawnAttempt(SpawnTier tierUsed, Boolean upstreamResult, List<String> failures) {
        boolean issued() {
            return tierUsed != null;
        }
    }

    record RemoveAttempt(String route, Boolean upstreamResult, List<String> failures) {
        boolean issued() {
            return route != null;
        }
    }

    private final UpstreamContract contract;

    UpstreamCalls(UpstreamContract contract) {
        this.contract = contract;
    }

    UpstreamContract contract() {
        return contract;
    }

    static Object invoke(Method method, Object target, Object... args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause != null ? cause : e;
        }
    }

    private static boolean truth(Object result) {
        return result instanceof Boolean b && b;
    }

    // ---------------------------------------------------------------- bot list

    /**
     * PvP BOT's listed bot names as a map from {@link NameRules#key} to the exact listed spelling. Upstream
     * returns a fresh copy on every call, so callers should cache within a tick.
     */
    Map<String, String> listedBots() throws Throwable {
        Object result = invoke(contract.getAllBots.method(), null);
        if (!(result instanceof Collection<?> names)) {
            throw new IllegalStateException("getAllBots returned "
                    + (result == null ? "null" : result.getClass().getName()));
        }
        Map<String, String> out = new HashMap<>(Math.max(16, names.size() * 2));
        for (Object o : names) {
            if (o instanceof String s) {
                out.putIfAbsent(NameRules.key(s), s);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- spawn / remove

    boolean spawnWithPosition(Object server, String name, Object source, Object position) throws Throwable {
        return truth(invoke(contract.spawn4.method(), null, server, name, source, position));
    }

    boolean spawn(Object server, String name, Object source) throws Throwable {
        return truth(invoke(contract.spawn3.method(), null, server, name, source));
    }

    boolean removeBot(Object server, String name, Object source) throws Throwable {
        return truth(invoke(contract.removeBot.method(), null, server, name, source));
    }

    /**
     * Re-lists a bot that is ONLINE but no longer in PvP BOT's list. Upstream's spawn for a name whose
     * player is alive dispatches nothing: it records that player as a bot, which is exactly the repair, and
     * answers false. The plain overload is the documented one; the position overload does the same.
     */
    boolean adopt(Object server, String name, Object source, Object position) throws Throwable {
        return contract.spawn3.ok() ? spawn(server, name, source) : spawnWithPosition(server, name, source, position);
    }

    /**
     * Tries the tiers in order until one can issue the spawn without throwing. Falling through after a
     * throw is safe: HeroBot silently ignores a second spawn for a name that is already being spawned, and
     * upstream swallows its own exceptions, so a throw here is a linkage or access problem, not a half spawn.
     */
    SpawnAttempt spawn(List<SpawnTier> order, Object server, String name, Object source, Object position,
                       CommandRunner command) {
        List<String> failures = new ArrayList<>();
        for (SpawnTier tier : order) {
            try {
                switch (tier) {
                    case CLASS_POS -> {
                        return new SpawnAttempt(tier, spawnWithPosition(server, name, source, position), failures);
                    }
                    case CLASS -> {
                        return new SpawnAttempt(tier, spawn(server, name, source), failures);
                    }
                    case COMMAND -> {
                        if (command == null) {
                            throw new IllegalStateException("no command dispatcher available");
                        }
                        // The command answers 1 exactly when spawnBot returned true.
                        return new SpawnAttempt(tier, command.run(UpstreamNames.spawnCommand(name)) > 0, failures);
                    }
                    default -> {
                    }
                }
            } catch (Throwable t) {
                failures.add(tier.label() + ": " + Diagnostics.describe(t));
            }
        }
        return new SpawnAttempt(null, null, failures);
    }

    /**
     * Removal through {@code BotManager.removeBot} when it exists, else (or when it throws) through the
     * {@code /pvpbot remove} command when that is registered. The caller has already established that
     * {@code name} is an online bot entity: upstream would happily run its inventory wipe on a real player.
     */
    RemoveAttempt remove(boolean commandRegistered, Object server, String name, Object source,
                         CommandRunner command) {
        List<String> failures = new ArrayList<>();
        if (contract.removeBot.ok()) {
            try {
                return new RemoveAttempt("class", removeBot(server, name, source), failures);
            } catch (Throwable t) {
                failures.add("class: " + Diagnostics.describe(t));
            }
        }
        if (commandRegistered && command != null) {
            try {
                return new RemoveAttempt("command", command.run(UpstreamNames.removeCommand(name)) > 0, failures);
            } catch (Throwable t) {
                failures.add("command: " + Diagnostics.describe(t));
            }
        }
        return new RemoveAttempt(null, null, failures);
    }

    // ---------------------------------------------------------------- settings (read-only)

    /** Upstream's settings singleton. READ here; the few managed settings are written by UpstreamSettingsWriter only. */
    Object settingsInstance() throws Throwable {
        return invoke(contract.settingsGet.method(), null);
    }

    /** A boolean getter's value, or null when the getter is missing or returns something else. */
    Boolean readBoolean(Object settings, String getter) throws Throwable {
        UpstreamContract.Member m = contract.getters.get(getter);
        if (settings == null || m == null || !m.ok()) {
            return null;
        }
        return invoke(m.method(), settings) instanceof Boolean b ? b : null;
    }

    /** An int getter's value, or null when the getter is missing or returns something else. */
    Integer readInt(Object settings, String getter) throws Throwable {
        UpstreamContract.Member m = contract.getters.get(getter);
        if (settings == null || m == null || !m.ok()) {
            return null;
        }
        return invoke(m.method(), settings) instanceof Integer i ? i : null;
    }

    /** A boolean getter that only the aggro range reads, or null when it is missing or returns something else. */
    Boolean readCombatBoolean(Object settings, String getter) throws Throwable {
        UpstreamContract.Member m = contract.combatGetters.get(getter);
        if (settings == null || m == null || !m.ok()) {
            return null;
        }
        return invoke(m.method(), settings) instanceof Boolean b ? b : null;
    }

    /** A double getter's value from the managed-range getters, or null when the getter is missing or returns something else. */
    Double readDouble(Object settings, String getter) throws Throwable {
        UpstreamContract.Member m = contract.doubleGetters.get(getter);
        if (settings == null || m == null || !m.ok()) {
            return null;
        }
        return invoke(m.method(), settings) instanceof Double d ? d : null;
    }

    /** {@code getMaxTargetDistance}, or null when it is missing or returns something else. */
    Double readMaxTargetDistance(Object settings) throws Throwable {
        return readDouble(settings, UpstreamContract.MAX_TARGET_DISTANCE_GETTER);
    }

    // ---------------------------------------------------------------- combat targets (aggro range)

    /**
     * The name forced on {@code bot} through {@code BotCombat.setTarget}, or null. Reads the public
     * {@code forcedTargetName} field of the bot's combat state; upstream creates that state on first access,
     * as its own combat tick does for every bot.
     */
    String forcedTarget(String bot) throws Throwable {
        Object state = invoke(contract.combatGetState.method(), null, bot);
        return state == null ? null : (String) contract.forcedTargetField.get(state);
    }

    /** The entity {@code bot} targets right now (upstream's per-tick result), or null. */
    Entity currentTarget(String bot) throws Throwable {
        return invoke(contract.combatGetTarget.method(), null, bot) instanceof Entity e ? e : null;
    }

    /** True when {@code target} is the bot's last attacker (upstream's revenge memory); false when unknown. */
    boolean isLastAttacker(String bot, Entity target) throws Throwable {
        if (contract.lastAttackerField == null || target == null) {
            return false;
        }
        Object state = invoke(contract.combatGetState.method(), null, bot);
        return state != null && contract.lastAttackerField.get(state) == target;
    }

    /** One tick of walking toward a point with upstream's own look and move-toward input (its patrols use the same). */
    void steer(ServerPlayer bot, Vec3 to, double speed) throws Throwable {
        invoke(contract.navLookAt.method(), null, bot, to);
        invoke(contract.navMoveToward.method(), null, bot, to, speed);
    }

    void setTarget(String bot, String target) throws Throwable {
        invoke(contract.combatSetTarget.method(), null, bot, target);
    }

    void clearTarget(String bot) throws Throwable {
        invoke(contract.combatClearTarget.method(), null, bot);
    }

    /** Whether the two names share a faction. Initialises upstream's faction registry: only call while factions are on. */
    boolean areAllies(String a, String b) throws Throwable {
        if (!contract.factionAreAllies.ok()) {
            throw new IllegalStateException(contract.factionAreAllies.failure());
        }
        return truth(invoke(contract.factionAreAllies.method(), null, a, b));
    }

    /** The current values of the managed settings as PvP BOT has them; a component is null when it cannot be read. */
    SettingsPolicy.Current currentManaged(Object settings) throws Throwable {
        return new SettingsPolicy.Current(readDouble(settings, UpstreamContract.MAX_TARGET_DISTANCE_GETTER),
                readDouble(settings, "getRangedMinRange"), readDouble(settings, "getRangedOptimalRange"),
                readDouble(settings, "getRangedMaxRange"), readBoolean(settings, "isAutoEquipWeapon"),
                readBoolean(settings, "isAutoTargetEnabled"), readBoolean(settings, "isRangedRetreatOnClose"),
                readDouble(settings, "getMeleeRange"));
    }

    /**
     * Writes the planned changes into the settings object and then saves the per-world file once. A change whose field
     * is not writable is skipped and named in the returned list (nothing throws for a missing name).
     */
    List<String> applyManaged(Object settings, SettingsPolicy.Plan plan) throws Throwable {
        List<String> skipped = new ArrayList<>();
        boolean wrote = false;
        for (SettingsPolicy.Change change : plan.changes()) {
            if (!contract.managed.canWrite(change.name())) {
                skipped.add(change.name());
                continue;
            }
            UpstreamSettingsWriter.write(contract.managed, settings, change.name(), change.to());
            wrote = true;
        }
        if (wrote) {
            UpstreamSettingsWriter.save(contract.managed);
        }
        return skipped;
    }

    // ---------------------------------------------------------------- combat state (read-only)

    /** What PvP BOT currently intends for one bot; {@code target} may be null, the other parts null when unreadable. */
    record CombatRead(Object target, String mode, Boolean drawingBow, Integer bowDrawTicks) {
    }

    /** Reads the bot's target and combat state. Only for bots PvP BOT lists: upstream creates a state for an unknown name. */
    CombatRead readCombat(String bot) throws Throwable {
        Object target = contract.combatGetTarget.ok() ? invoke(contract.combatGetTarget.method(), null, bot) : null;
        String mode = null;
        Boolean drawing = null;
        Integer drawTicks = null;
        if (contract.combatGetState.ok() && !contract.stateFields.isEmpty()) {
            Object state = invoke(contract.combatGetState.method(), null, bot);
            if (state != null) {
                java.lang.reflect.Field fMode = contract.stateFields.get(UpstreamContract.FIELD_MODE);
                java.lang.reflect.Field fDraw = contract.stateFields.get(UpstreamContract.FIELD_DRAWING);
                java.lang.reflect.Field fTicks = contract.stateFields.get(UpstreamContract.FIELD_DRAW_TICKS);
                Object m = fMode == null ? null : fMode.get(state);
                mode = m == null ? null : m.toString();
                Object d = fDraw == null ? null : fDraw.get(state);
                drawing = d instanceof Boolean b ? b : null;
                Object t = fTicks == null ? null : fTicks.get(state);
                drawTicks = t instanceof Integer i ? i : null;
            }
        }
        return new CombatRead(target, mode, drawing, drawTicks);
    }

    /** The bot's {@code isRetreating} flag from upstream's combat state, or null when it cannot be read. */
    Boolean readRetreating(String bot) throws Throwable {
        java.lang.reflect.Field f = contract.stateFields.get(UpstreamContract.FIELD_RETREATING);
        if (f == null || !contract.combatGetState.ok()) {
            return null;
        }
        Object state = invoke(contract.combatGetState.method(), null, bot);
        return state != null && f.get(state) instanceof Boolean b ? b : null;
    }

    // ---------------------------------------------------------------- paths

    boolean createPath(String path) throws Throwable {
        return truth(invoke(contract.createPath.method(), null, path));
    }

    boolean deletePath(String path) throws Throwable {
        return truth(invoke(contract.deletePath.method(), null, path));
    }

    boolean addPoint(String path, Vec3 point) throws Throwable {
        return truth(invoke(contract.addPoint.method(), null, path, point));
    }

    boolean setLoop(String path, boolean loop) throws Throwable {
        return truth(invoke(contract.setLoop.method(), null, path, loop));
    }

    boolean setAttack(String path, boolean attack) throws Throwable {
        return truth(invoke(contract.setAttack.method(), null, path, attack));
    }

    boolean setWalkType(String path, String walkType) throws Throwable {
        return truth(invoke(contract.setWalkType.method(), null, path, walkType));
    }

    boolean startFollowing(String bot, String path) throws Throwable {
        return truth(invoke(contract.startFollowing.method(), null, bot, path));
    }

    boolean stopFollowing(String bot) throws Throwable {
        return truth(invoke(contract.stopFollowing.method(), null, bot));
    }

    boolean pathExists(String path) throws Throwable {
        return invoke(contract.getPath.method(), null, path) != null;
    }

    /** Whether the bot follows exactly that path; null when upstream offers no way to ask. */
    Boolean isFollowing(String bot, String path) throws Throwable {
        if (!contract.isFollowing.ok()) {
            return null;
        }
        return truth(invoke(contract.isFollowing.method(), null, bot, path));
    }

    /** Drops upstream's per-bot navigation anchor; a no-op when upstream has no such method. */
    void removeNavigationState(String bot) throws Throwable {
        if (contract.removeState.ok()) {
            invoke(contract.removeState.method(), null, bot);
        }
    }
}

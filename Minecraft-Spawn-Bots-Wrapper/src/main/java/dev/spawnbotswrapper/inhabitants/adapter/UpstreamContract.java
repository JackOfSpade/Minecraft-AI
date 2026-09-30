package dev.spawnbotswrapper.inhabitants.adapter;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The reflection contract with PvP BOT: every member the adapter may ever call, resolved once with
 * {@code getMethod} and checked for public / static / return type. Minecraft types in the signatures are
 * CLASS LITERALS, which the build remaps to the runtime's own names, so a match here means the parameter
 * types really are the ones PvP BOT was compiled against.
 * <p>
 * Nothing is invoked and no upstream class is initialised while probing (classes are loaded without static
 * initialisers): upstream classes read config files in their initialisers and some bind to world state
 * that does not exist yet. A member that cannot be used carries the reason as text, so a report can say
 * exactly what is wrong instead of just "incompatible".
 * <p>
 * Ids R1..R13 follow the analysis document's contract table.
 */
final class UpstreamContract {

    /** One contract member and what the probe found; {@code method} is null exactly when {@code problem} is set. */
    record Member(String id, String description, Method method, String problem) {

        boolean ok() {
            return method != null;
        }

        String label() {
            return id.isEmpty() ? description : id + " " + description;
        }

        /** Label plus why it is unusable; the text that goes into reports. */
        String failure() {
            return label() + " - " + problem;
        }
    }

    /** Names of the BotSettings getters read for {@link dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities}. */
    static final List<String> CAPABILITY_GETTERS = List.of(
            "isAutoEquipArmor", "isAutoEquipWeapon", "isCombatEnabled", "isAutoTargetEnabled", "isRangedEnabled",
            "isMaceEnabled", "isSpearEnabled", "isCrystalPvpEnabled", "isAnchorPvpEnabled", "isCobwebEnabled",
            "isAutoTotemEnabled", "isAutoShieldEnabled", "isAutoEatEnabled", "isAutoPotionEnabled",
            "isAutoMendEnabled", "isShieldBreakEnabled", "isRetreatEnabled", "isBotsRelogs", "isBotLeaveOnDeath",
            "isClearOnRemove", "isTotemPriority", "isPreferSword", "isRangedRetreatOnClose");
    /** Contract ids R11 (boolean) and R12 (int) beyond the capability getters. */
    private static final List<String> EXTRA_BOOLEAN_GETTERS = List.of("isProfileLagFix", "isSafeSpawn", "isUseSpecialNames");
    private static final List<String> INT_GETTERS = List.of("getMaxMassSpawn", "getCheckInterval");
    static final String MAX_TARGET_DISTANCE_GETTER = "getMaxTargetDistance";
    /** Double getters read for the managed ranges (kept apart from {@link #getters}: they are not capability inputs). */
    static final List<String> DOUBLE_GETTERS = List.of(MAX_TARGET_DISTANCE_GETTER, "getRangedMinRange",
            "getRangedOptimalRange", "getRangedMaxRange");
    /** Public fields of BotCombat.CombatState the addon reads (never written). */
    static final String FIELD_MODE = "currentMode";
    static final String FIELD_DRAWING = "isDrawingBow";
    static final String FIELD_DRAW_TICKS = "bowDrawTicks";

    // ---- BotManager
    final Member spawn3;
    final Member spawn4;
    final Member getAllBots;
    final Member getBotCount;
    final Member removeBot;
    final Member getBot;
    final Member removeAllBots;
    final Member saveBots;
    final Member updateBotData;

    // ---- BotSettings (read; the few managed fields are written only through UpstreamSettingsWriter)
    final Member settingsGet;
    /** The settings class, or null when it cannot be loaded. Metadata only: never initialised by the probe. */
    final Class<?> settingsClass;
    /** Getter name to member, for every name in {@link #CAPABILITY_GETTERS} plus the extra R11/R12 getters. */
    final Map<String, Member> getters;
    /** The managed-range getters, by name (see {@link #DOUBLE_GETTERS}); optional. */
    final Map<String, Member> doubleGetters;
    /** Field and save handles of the settings the addon may write; optional, see {@link UpstreamSettingsWriter}. */
    final UpstreamSettingsWriter.Handles managed;

    // ---- BotPath / BotNavigation (patrols)
    final Member createPath;
    final Member deletePath;
    final Member addPoint;
    final Member setLoop;
    final Member setAttack;
    final Member setWalkType;
    final Member startFollowing;
    final Member stopFollowing;
    final Member getPath;
    /** Optional: lets the adapter verify WHICH path a bot follows before it touches the follower. */
    final Member isFollowing;
    /** Optional: clears the per-bot navigation anchor upstream never clears itself. */
    final Member removeState;

    // ---- BotCombat / BotFaction (aggro range). All optional: none of them affects availability.
    final Member combatSetTarget;
    final Member combatGetTarget;
    final Member combatClearTarget;
    /** {@code BotCombat.getState(String)}: the per-bot combat state; its fields are read, never written. */
    final Member combatGetState;
    /** Public fields of the combat state by name ({@link #FIELD_MODE} ...); a missing one is simply absent. */
    final Map<String, Field> stateFields;
    /** The public {@code forcedTargetName} field of the per-bot combat state; null when it cannot be used. */
    final Field forcedTargetField;
    /** Why {@link #forcedTargetField} is null, else null. */
    final String forcedTargetProblem;
    /** The public {@code lastAttacker} entity field of the combat state (PvP BOT's revenge memory); null when unusable. */
    final Field lastAttackerField;
    /** {@code BotNavigation.lookAtPosition(ServerPlayer, Vec3)} and {@code moveTowardPosition(ServerPlayer, Vec3, double)}: the walk back. */
    final Member navLookAt;
    final Member navMoveToward;
    /** Getter name to member for the settings only the aggro range reads (target filters, factions, chase limit). */
    final Map<String, Member> combatGetters;
    /** {@code BotFaction.areAllies(String, String)}; only invoked while PvP BOT's factions setting is on. */
    final Member factionAreAllies;

    /** R13: null when the main class exposes MOD_ID = "pvp_bot", else what is off (detail only). */
    final String modIdNote;

    UpstreamContract(ClassLocator locator) {
        Owner manager = load(locator, UpstreamNames.CLASS_BOT_MANAGER);
        Owner settings = load(locator, UpstreamNames.CLASS_BOT_SETTINGS);
        Owner path = load(locator, UpstreamNames.CLASS_BOT_PATH);
        Owner nav = load(locator, UpstreamNames.CLASS_BOT_NAVIGATION);
        Owner main = load(locator, UpstreamNames.CLASS_MAIN);

        spawn3 = staticMethod(manager, "R1", "spawnBot", boolean.class,
                MinecraftServer.class, String.class, CommandSourceStack.class);
        spawn4 = staticMethod(manager, "R2", "spawnBot", boolean.class,
                MinecraftServer.class, String.class, CommandSourceStack.class, Vec3.class);
        getAllBots = staticMethod(manager, "R3", "getAllBots", Set.class);
        getBotCount = staticMethod(manager, "R4", "getBotCount", int.class);
        removeBot = staticMethod(manager, "R5", "removeBot", boolean.class,
                MinecraftServer.class, String.class, CommandSourceStack.class);
        getBot = staticMethod(manager, "R6", "getBot", ServerPlayer.class, MinecraftServer.class, String.class);
        // R7 is probed only: removing every listed bot would also remove other mods' bots, so it is never called.
        removeAllBots = staticMethod(manager, "R7", "removeAllBots", void.class,
                MinecraftServer.class, CommandSourceStack.class);
        saveBots = staticMethod(manager, "R8", "saveBots", void.class);
        updateBotData = staticMethod(manager, "R9", "updateBotData", void.class, MinecraftServer.class);

        settingsClass = settings.type;
        settingsGet = staticMethod(settings, "R10", "get", settings.type == null ? Object.class : settings.type);
        Map<String, Member> g = new LinkedHashMap<>();
        for (String name : CAPABILITY_GETTERS) {
            g.put(name, instanceMethod(settings, capabilityGetterId(name), name, boolean.class));
        }
        for (String name : EXTRA_BOOLEAN_GETTERS) {
            g.put(name, instanceMethod(settings, "R11", name, boolean.class));
        }
        for (String name : INT_GETTERS) {
            g.put(name, instanceMethod(settings, "R12", name, int.class));
        }
        getters = Map.copyOf(g);
        Map<String, Member> dg = new LinkedHashMap<>();
        for (String name : DOUBLE_GETTERS) {
            dg.put(name, instanceMethod(settings, "", name, double.class));
        }
        doubleGetters = Map.copyOf(dg);
        managed = UpstreamSettingsWriter.resolve(settings.type);

        createPath = staticMethod(path, "", "createPath", boolean.class, String.class);
        deletePath = staticMethod(path, "", "deletePath", boolean.class, String.class);
        addPoint = staticMethod(path, "", "addPoint", boolean.class, String.class, Vec3.class);
        setLoop = staticMethod(path, "", "setLoop", boolean.class, String.class, boolean.class);
        setAttack = staticMethod(path, "", "setAttack", boolean.class, String.class, boolean.class);
        setWalkType = staticMethod(path, "", "setWalkType", boolean.class, String.class, String.class);
        startFollowing = staticMethod(path, "", "startFollowing", boolean.class, String.class, String.class);
        stopFollowing = staticMethod(path, "", "stopFollowing", boolean.class, String.class);
        getPath = staticMethod(path, "", "getPath", Object.class, String.class);
        isFollowing = staticMethod(path, "", "isFollowing", boolean.class, String.class, String.class);
        removeState = staticMethod(nav, "", "removeState", void.class, String.class);

        Owner combat = load(locator, UpstreamNames.CLASS_BOT_COMBAT);
        combatSetTarget = staticMethod(combat, "", "setTarget", void.class, String.class, String.class);
        combatGetTarget = staticMethod(combat, "", "getTarget", Entity.class, String.class);
        combatClearTarget = staticMethod(combat, "", "clearTarget", void.class, String.class);
        combatGetState = staticMethod(combat, "", "getState", Object.class, String.class);
        stateFields = stateFields(load(locator, UpstreamNames.CLASS_COMBAT_STATE).type);
        String[] fieldProblem = new String[1];
        forcedTargetField = forcedTargetField(combatGetState, fieldProblem);
        forcedTargetProblem = forcedTargetField == null ? fieldProblem[0] : null;
        lastAttackerField = lastAttackerField(combatGetState);
        navLookAt = staticMethod(nav, "", "lookAtPosition", void.class, ServerPlayer.class, Vec3.class);
        navMoveToward = staticMethod(nav, "", "moveTowardPosition", void.class, ServerPlayer.class, Vec3.class,
                double.class);
        Map<String, Member> cg = new LinkedHashMap<>();
        for (String name : COMBAT_BOOLEAN_GETTERS) {
            cg.put(name, instanceMethod(settings, "", name, boolean.class));
        }
        combatGetters = Map.copyOf(cg);
        Owner faction = load(locator, UpstreamNames.CLASS_BOT_FACTION);
        factionAreAllies = staticMethod(faction, "", "areAllies", boolean.class, String.class, String.class);

        modIdNote = checkModId(main);
    }

    /** BotSettings getters read only by the aggro range (isCombatEnabled / isAutoTargetEnabled are capability getters). */
    static final List<String> COMBAT_BOOLEAN_GETTERS = List.of("isTargetPlayers", "isTargetOtherBots",
            "isAttackInvincible", "isFactionsEnabled", "isFriendlyFireEnabled");
    private static final String FORCED_TARGET_FIELD = "forcedTargetName";
    private static final String LAST_ATTACKER_FIELD = "lastAttacker";

    /** The revenge-memory field, or null when missing (the cause of an engagement is then inferred as "other"). */
    private static Field lastAttackerField(Member getState) {
        if (!getState.ok()) {
            return null;
        }
        Class<?> stateType = getState.method().getReturnType();
        try {
            Field f = stateType.getField(LAST_ATTACKER_FIELD);
            return !Modifier.isStatic(f.getModifiers()) && Entity.class.isAssignableFrom(f.getType())
                    && Modifier.isPublic(stateType.getModifiers()) ? f : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field forcedTargetField(Member getState, String[] problem) {
        if (!getState.ok()) {
            problem[0] = getState.failure();
            return null;
        }
        Class<?> stateType = getState.method().getReturnType();
        try {
            Field f = stateType.getField(FORCED_TARGET_FIELD);
            if (Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) {
                problem[0] = stateType.getSimpleName() + "." + FORCED_TARGET_FIELD + " is not an instance String";
                return null;
            }
            if (!Modifier.isPublic(stateType.getModifiers())) {
                problem[0] = "the combat state class " + stateType.getName() + " is not public";
                return null;
            }
            return f;
        } catch (NoSuchFieldException e) {
            problem[0] = stateType.getSimpleName() + "." + FORCED_TARGET_FIELD + " not found";
            return null;
        } catch (Throwable t) {
            problem[0] = stateType.getSimpleName() + "." + FORCED_TARGET_FIELD + " could not be inspected ("
                    + Diagnostics.describe(t) + ")";
            return null;
        }
    }

    /**
     * Why the aggro range cannot control targets, or null when it can: it needs the three target calls, the
     * forced-name field and readable settings. The individual setting getters are handled one by one at read time.
     */
    String combatControlProblem() {
        List<String> missing = new ArrayList<>();
        for (Member m : List.of(combatSetTarget, combatGetTarget, combatClearTarget)) {
            if (!m.ok()) {
                missing.add(m.failure());
            }
        }
        if (forcedTargetField == null) {
            missing.add(forcedTargetProblem);
        }
        if (!settingsGet.ok()) {
            missing.add(settingsGet.failure());
        }
        return missing.isEmpty() ? null : String.join("; ", missing);
    }

    /** Why the walk back cannot be done, or null when it can. */
    String steeringProblem() {
        List<String> missing = new ArrayList<>();
        for (Member m : List.of(navLookAt, navMoveToward)) {
            if (!m.ok()) {
                missing.add(m.failure());
            }
        }
        return missing.isEmpty() ? null : String.join("; ", missing);
    }

    private static Map<String, Field> stateFields(Class<?> state) {
        Map<String, Field> out = new LinkedHashMap<>();
        if (state == null || !Modifier.isPublic(state.getModifiers())) {
            return out;
        }
        for (String name : List.of(FIELD_MODE, FIELD_DRAWING, FIELD_DRAW_TICKS)) {
            try {
                Field f = state.getField(name);
                if (!Modifier.isStatic(f.getModifiers())) {
                    out.put(name, f);
                }
            } catch (Throwable t) {
                // absent: the field is optional, the reads then simply omit it
            }
        }
        return out;
    }

    private static String capabilityGetterId(String name) {
        return switch (name) {
            case "isBotsRelogs", "isBotLeaveOnDeath", "isClearOnRemove" -> "R11";
            default -> "";
        };
    }

    // ---------------------------------------------------------------- derived views

    /** The mandatory members of the class-based spawn/list/remove surface, in report order. */
    List<Member> managerMembers() {
        return List.of(spawn3, spawn4, getAllBots, getBotCount, removeBot, getBot, removeAllBots, saveBots,
                updateBotData);
    }

    /** Every path member a patrol needs. Verification and navigation cleanup are optional extras. */
    List<Member> pathRequired() {
        return List.of(createPath, deletePath, addPoint, setLoop, setAttack, setWalkType, startFollowing,
                stopFollowing, getPath);
    }

    boolean patrolCapable() {
        for (Member m : pathRequired()) {
            if (!m.ok()) {
                return false;
            }
        }
        return true;
    }

    /** Names of the getters that could not be resolved, sorted. */
    List<String> missingGetterNames() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Member> e : getters.entrySet()) {
            if (!e.getValue().ok()) {
                out.add(e.getKey());
            }
        }
        Collections.sort(out);
        return out;
    }

    // ---------------------------------------------------------------- resolution

    /** A class the probe tried to load: either {@code type} or the reason it is unusable. */
    private record Owner(String fqn, Class<?> type, String problem) {
        String simple() {
            return UpstreamNames.simpleName(fqn);
        }
    }

    private static Owner load(ClassLocator locator, String fqn) {
        try {
            Class<?> type = locator.load(fqn);
            if (type == null) {
                return new Owner(fqn, null, "class " + fqn + " not found");
            }
            return new Owner(fqn, type, null);
        } catch (ClassNotFoundException e) {
            return new Owner(fqn, null, "class " + fqn + " not found");
        } catch (Throwable t) {
            return new Owner(fqn, null, "class " + fqn + " could not be loaded (" + Diagnostics.describe(t) + ")");
        }
    }

    private static Member staticMethod(Owner owner, String id, String name, Class<?> returns, Class<?>... params) {
        return resolve(owner, id, true, name, returns, params);
    }

    private static Member instanceMethod(Owner owner, String id, String name, Class<?> returns, Class<?>... params) {
        return resolve(owner, id, false, name, returns, params);
    }

    private static Member resolve(Owner owner, String id, boolean wantStatic, String name, Class<?> returns,
                                  Class<?>... params) {
        String description = owner.simple() + "." + name + "(" + simpleNames(params) + ")";
        if (owner.type == null) {
            return new Member(id, description, null, owner.problem);
        }
        try {
            Method m = owner.type.getMethod(name, params);
            String problem = check(m, wantStatic, returns);
            return problem == null
                    ? new Member(id, description, m, null)
                    : new Member(id, description, null, problem);
        } catch (NoSuchMethodException e) {
            String why = existsButNotPublic(owner.type, name, params) ? "exists but is not public" : "not found";
            return new Member(id, description, null, why);
        } catch (Throwable t) {
            return new Member(id, description, null, "could not be inspected (" + Diagnostics.describe(t) + ")");
        }
    }

    private static String check(Method m, boolean wantStatic, Class<?> returns) {
        if (Modifier.isStatic(m.getModifiers()) != wantStatic) {
            return wantStatic ? "is not static" : "is static, an instance method was expected";
        }
        if (!Modifier.isPublic(m.getDeclaringClass().getModifiers())) {
            return "declaring class " + m.getDeclaringClass().getName() + " is not public";
        }
        // isAssignableFrom is exact for primitives and void, and accepts subtypes for references.
        if (!returns.isAssignableFrom(m.getReturnType())) {
            return "returns " + m.getReturnType().getSimpleName() + ", expected " + returns.getSimpleName();
        }
        return null;
    }

    private static boolean existsButNotPublic(Class<?> type, String name, Class<?>[] params) {
        try {
            type.getDeclaredMethod(name, params);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String simpleNames(Class<?>[] params) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(params[i].getSimpleName());
        }
        return sb.toString();
    }

    private static String checkModId(Owner main) {
        if (main.type == null) {
            return main.problem;
        }
        try {
            Field f = main.type.getField(UpstreamNames.FIELD_MOD_ID);
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) {
                return "R13 " + main.simple() + "." + UpstreamNames.FIELD_MOD_ID + " is not a static String";
            }
            Object value = f.get(null);
            if (!UpstreamNames.MOD_PVP_BOT.equals(value)) {
                return "R13 " + main.simple() + "." + UpstreamNames.FIELD_MOD_ID + " is '" + value + "', expected '"
                        + UpstreamNames.MOD_PVP_BOT + "'";
            }
            return null;
        } catch (NoSuchFieldException e) {
            return "R13 " + main.simple() + "." + UpstreamNames.FIELD_MOD_ID + " not found";
        } catch (Throwable t) {
            return "R13 " + main.simple() + "." + UpstreamNames.FIELD_MOD_ID + " could not be read ("
                    + Diagnostics.describe(t) + ")";
        }
    }
}

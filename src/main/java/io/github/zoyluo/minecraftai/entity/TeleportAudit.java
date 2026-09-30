package io.github.zoyluo.minecraftai.entity;

import io.github.zoyluo.minecraftai.log.BotLog;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Classifies and counts every teleport of a bot, so a path-correction "micro teleport" can be told apart from the moves that are
 * meant to exist, and so tests can assert that a scenario produced none. This records and logs only; it never changes a teleport.
 *
 * <p>{@link AIPlayerEntity} overrides every teleport shape ({@code teleportTo} in both forms and {@code teleport(TeleportTransition)})
 * and calls {@link #record} before {@code super}. The kind is decided by the first stack frame that belongs to this mod and is not
 * plumbing:</p>
 * <ul>
 *   <li>frames of {@link AIPlayerEntity}, of this class, of anything outside {@code io.github.zoyluo.minecraftai.} (vanilla, Fabric,
 *       the JDK) and of {@code io.github.zoyluo.minecraftai.mixin.*} are skipped;</li>
 *   <li>no frame left: {@link Kind#VANILLA} (portals, ender pearls, vanilla respawn);</li>
 *   <li>{@code manager.AIPlayerManager}: {@link Kind#LIFECYCLE} (spawn, respawn after death);</li>
 *   <li>{@code network.MinecraftAiServerNetworking}: {@link Kind#USER} (the panel's recall / to-bot buttons);</li>
 *   <li>a method of {@link #PRIVILEGED_METHODS}: {@link Kind#PRIVILEGED} (the operator emergency rescues, which are capability
 *       gated and refused in strict survival);</li>
 *   <li>a {@link #testScope() test scope} is open, or the class name ends in {@code GameTests}: {@link Kind#TEST} (a fixture move
 *       of a GameTest);</li>
 *   <li>anything else: {@link Kind#CORRECTION}, a code path that moves a bot to fix its position instead of walking it there.</li>
 * </ul>
 *
 * <p>Counters are per bot (by UUID). A bot re-created with the same name has the same UUID, so a test that measures counts calls
 * {@link #reset} first or compares before/after values.</p>
 */
public final class TeleportAudit {
    /** Why a bot was moved. */
    public enum Kind {
        LIFECYCLE, USER, VANILLA, PRIVILEGED, TEST, CORRECTION
    }

    /** One stack frame as the classifier sees it (kept as plain strings so the classification is unit-testable). */
    public record Frame(String className, String methodName) {
    }

    /** The mod's package: a frame outside it is never "the caller". */
    static final String MOD_PACKAGE = "io.github.zoyluo.minecraftai.";
    private static final String MIXIN_PACKAGE = MOD_PACKAGE + "mixin.";
    private static final String ENTITY_CLASS = MOD_PACKAGE + "entity.AIPlayerEntity";
    private static final String AUDIT_CLASS = MOD_PACKAGE + "entity.TeleportAudit";
    private static final String LIFECYCLE_CLASS = MOD_PACKAGE + "manager.AIPlayerManager";
    private static final String USER_CLASS = MOD_PACKAGE + "network.MinecraftAiServerNetworking";
    private static final int MAX_FRAMES = 64;

    /**
     * The privileged emergency teleports, as {@code SimpleClassName#method}. A frame matches when the method is exactly that or a
     * lambda body of it ({@code lambda$<method>$N}, e.g. the runnable handed to {@code CapabilityRuntime.run}). Each of them decides
     * its capability (EMERGENCY_TELEPORT: operator profile only, denied in strict survival) before it moves the bot:
     * <ul>
     *   <li>{@code NavSafetyNet#escapeSuffocation} and {@code #emergencyTeleportToAir}: the suffocation and drowning rescues;</li>
     *   <li>{@code DangerWatcher#escapeToSurface}: the dark-trap surfacing;</li>
     *   <li>{@code GatherQuotaTask#trySurface}: the gather surfacing.</li>
     * </ul>
     * The path-start snap ({@code ActionPack#snapPlayerToNearestStandable}) is deliberately NOT listed: the user wants no
     * path-correction teleports in any profile, so every snap teleport must show up as a {@link Kind#CORRECTION}.
     */
    static final Set<String> PRIVILEGED_METHODS = Set.of(
            "NavSafetyNet#escapeSuffocation",
            "NavSafetyNet#emergencyTeleportToAir",
            "DangerWatcher#escapeToSurface",
            "GatherQuotaTask#trySurface");

    private static final ThreadLocal<int[]> TEST_SCOPE = ThreadLocal.withInitial(() -> new int[1]);
    private static final Map<UUID, Stats> STATS = new ConcurrentHashMap<>();

    private TeleportAudit() {
    }

    private static final class Stats {
        final EnumMap<Kind, Integer> counts = new EnumMap<>(Kind.class);
        String lastCaller = "-";
    }

    /** An open test scope; close it when the fixture move is done. Nesting is allowed. */
    public static final class Scope implements AutoCloseable {
        private boolean closed;

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                TEST_SCOPE.get()[0]--;
            }
        }
    }

    /**
     * Opens a test scope on this thread: every teleport recorded while it is open is {@link Kind#TEST}, whatever code performs it.
     * The GameTest fixture helper ({@code BotFixtureMoves}) uses it, so a harness move is never counted as a correction.
     */
    public static Scope testScope() {
        TEST_SCOPE.get()[0]++;
        return new Scope();
    }

    /** True while a {@link #testScope()} is open on the calling thread. */
    public static boolean inTestScope() {
        return TEST_SCOPE.get()[0] > 0;
    }

    /** Records (classifies, counts and logs) a teleport of {@code bot}; called by {@link AIPlayerEntity} before the move. */
    public static void record(AIPlayerEntity bot, Vec3 from, Vec3 to) {
        if (bot == null) {
            return;
        }
        List<Frame> frames = StackWalker.getInstance().walk(stream -> stream
                .limit(MAX_FRAMES)
                .map(frame -> new Frame(frame.getClassName(), frame.getMethodName()))
                .toList());
        Frame caller = firstCaller(frames);
        Kind kind = classify(frames, inTestScope());
        String callerText = caller == null ? "-" : simpleClassName(caller.className()) + "#" + caller.methodName();
        double distance = from == null || to == null ? 0.0D : from.distanceTo(to);
        Stats stats = STATS.computeIfAbsent(bot.getUUID(), ignored -> new Stats());
        synchronized (stats) {
            stats.counts.merge(kind, 1, Integer::sum);
            stats.lastCaller = callerText;
        }
        BotLog.action(bot, "bot_teleport",
                "kind", kind,
                "caller", callerText,
                "dist", String.format(java.util.Locale.ROOT, "%.2f", distance));
    }

    /**
     * The pure classification: {@code frames} are the raw stack frames, innermost first (the plumbing frames are skipped here),
     * {@code testScope} whether a {@link #testScope()} is open.
     */
    public static Kind classify(List<Frame> frames, boolean testScope) {
        Frame caller = firstCaller(frames);
        if (caller == null) {
            return Kind.VANILLA;
        }
        String className = caller.className();
        if (LIFECYCLE_CLASS.equals(className)) {
            return Kind.LIFECYCLE;
        }
        if (USER_CLASS.equals(className)) {
            return Kind.USER;
        }
        if (isPrivileged(className, caller.methodName())) {
            return Kind.PRIVILEGED;
        }
        if (testScope || simpleClassName(className).endsWith("GameTests")) {
            return Kind.TEST;
        }
        return Kind.CORRECTION;
    }

    /** The first frame that is neither plumbing nor foreign code, or null when there is none. */
    static Frame firstCaller(List<Frame> frames) {
        for (Frame frame : frames) {
            String name = frame.className();
            if (name == null
                    || !name.startsWith(MOD_PACKAGE)
                    || name.startsWith(MIXIN_PACKAGE)
                    || name.equals(ENTITY_CLASS)
                    || name.equals(AUDIT_CLASS)) {
                continue;
            }
            return frame;
        }
        return null;
    }

    static boolean isPrivileged(String className, String methodName) {
        String base = simpleClassName(className);
        for (String entry : PRIVILEGED_METHODS) {
            int split = entry.indexOf('#');
            if (!entry.substring(0, split).equals(base)) {
                continue;
            }
            String method = entry.substring(split + 1);
            if (method.equals(methodName) || (methodName != null && methodName.startsWith("lambda$" + method + "$"))) {
                return true;
            }
        }
        return false;
    }

    /** The outermost simple class name: {@code a.b.Outer$Inner} is {@code Outer}. */
    static String simpleClassName(String className) {
        String simple = className.substring(className.lastIndexOf('.') + 1);
        int nested = simple.indexOf('$');
        return nested < 0 ? simple : simple.substring(0, nested);
    }

    /** How many teleports of {@code kind} this bot has had since the last {@link #reset}. */
    public static int count(AIPlayerEntity bot, Kind kind) {
        return bot == null ? 0 : count(bot.getUUID(), kind);
    }

    public static int count(UUID bot, Kind kind) {
        Stats stats = STATS.get(bot);
        if (stats == null) {
            return 0;
        }
        synchronized (stats) {
            return stats.counts.getOrDefault(kind, 0);
        }
    }

    /** Teleports that moved this bot to fix its position: the number every "no micro-teleport" assertion is about. */
    public static int corrections(AIPlayerEntity bot) {
        return count(bot, Kind.CORRECTION);
    }

    /** The caller of the last recorded teleport as {@code Class#method}, or {@code -}. */
    public static String lastCaller(AIPlayerEntity bot) {
        Stats stats = bot == null ? null : STATS.get(bot.getUUID());
        if (stats == null) {
            return "-";
        }
        synchronized (stats) {
            return stats.lastCaller;
        }
    }

    /** Forgets the counters of this bot. */
    public static void reset(AIPlayerEntity bot) {
        if (bot != null) {
            STATS.remove(bot.getUUID());
        }
    }

    /** Forgets every counter (server stop). */
    public static void clearAll() {
        STATS.clear();
    }
}

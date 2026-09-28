package io.github.zoyluo.minecraftai.mining.assist;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable mining-assist settings (design section 7) read from the optional top-level
 * {@code "miningAssist"} object of {@code config/minecraftai.json}, plus the resolved {@link AssistMode}.
 *
 * <p>Parsing never throws and never rejects the file: unknown keys are ignored, wrong-typed values
 * fall back to the default, out-of-range numbers are clamped to sane bounds, and every such event is
 * recorded in {@link #warnings()} for the caller to log. This class holds no static mutable state;
 * the per-bot {@code forceEnable}, the harness switch and the TPS test override live in the runtime
 * holder that owns the current instance.</p>
 *
 * <p><b>Mode precedence.</b> A non-blank {@code MINECRAFTAI_MINING_ASSIST} env value beats the file key
 * {@code miningAssist.mode}, which beats the shipped phase default. An explicit value that is not a
 * known mode resolves to {@link AssistMode#OFF} (fail closed) with a warning. The harness
 * default-off ({@link #harnessOff()}) is in effect only when neither env nor file gave a mode.</p>
 */
public final class MiningAssistConfig {
    /** Env var that overrides {@code miningAssist.mode}. */
    public static final String ENV_MODE = "MINECRAFTAI_MINING_ASSIST";
    /** Env var ({@code 1}) that forces the deterministic flag on. */
    public static final String ENV_DETERMINISTIC = "MINECRAFTAI_MINING_ASSIST_DETERMINISTIC";
    /** Optional top-level key of the whole minecraftai.json. */
    public static final String FILE_SECTION = "miningAssist";
    /** Upper bound on {@code poi.cavernDimensions} entries kept. */
    public static final int MAX_CAVERN_DIMENSIONS = 16;

    private static final int MAX_MODEL_LENGTH = 128;
    private static final int MAX_DROPPED_ENTRY_NOTES = 8;
    private static final int MAX_WARNING_LENGTH = 240;
    private static final Pattern DIMENSION_KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    /** The design table's notation for a range, {@code 0.175..0.554}. */
    private static final Pattern FRACTION_RANGE =
            Pattern.compile("\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\.\\.\\s*(-?\\d+(?:\\.\\d+)?)\\s*");

    /** Where the effective mode came from. */
    public enum ModeSource { ENV, FILE, DEFAULT }

    /** {@code poi.noticeRecipients}. */
    public enum NoticeRecipients {
        AUTHORIZED, BROADCAST;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<NoticeRecipients> parse(String text) {
            return parseEnum(values(), text);
        }
    }

    /** {@code poi.unavailablePolicy}: what to do about a POSSIBLE candidate when the advisor is unavailable. */
    public enum UnavailablePolicy {
        STOP_IF_STRUCTURE, NOTIFY_ONLY, STOP_IF_POSSIBLE;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<UnavailablePolicy> parse(String text) {
            return parseEnum(values(), text);
        }
    }

    /** {@code poi.cavernKeylessPolicy}: keyless behaviour for a cavern-only candidate. */
    public enum CavernKeylessPolicy {
        NOTIFY_ONLY, STOP_IF_POSSIBLE;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<CavernKeylessPolicy> parse(String text) {
            return parseEnum(values(), text);
        }
    }

    /**
     * Sensor budget. {@code shadowLog} defaults to true whenever the mode senses at all ({@link
     * AssistMode#allowsSense()}: every mode but {@link AssistMode#OFF}), not only in the original
     * {@link AssistMode#SENSE} phase -- DETOUR/POI/ALL still run the sensor (design: "the declaration
     * order is the rollout order"), and a later phase needs the same shadow log for the same reason
     * the SENSE-only rollout did (design 7: verify what the sensor believed it saw, at no behavioural
     * cost). A later rollout flip must not silently turn the shadow log off.
     */
    public record Sense(int raysPerTick, int globalRaysPerTick, boolean adaptiveThrottle, boolean shadowLog) {
        public static final int DEFAULT_RAYS_PER_TICK = 40;
        public static final int DEFAULT_GLOBAL_RAYS_PER_TICK = 640;
    }

    /**
     * {@code TickHeadroom} thresholds in milliseconds of measured tick work. The abort threshold is
     * never below {@link #MIN_ABORT_WORK_MS}, the fixed re-arm level of the hysteresis (design 2.1):
     * a lower abort would re-arm while still "over budget" and oscillate, and
     * {@code TickHeadroom.Thresholds} rejects such a pair.
     */
    public record Tick(double startWorkMs, double abortWorkMs) {
        /** The compile-time re-arm threshold (ms); {@code abortWorkMs} is clamped to at least this. */
        public static final double MIN_ABORT_WORK_MS = 40.0D;

        public static final Tick DEFAULTS = new Tick(38.0D, 48.0D);
    }

    /** {@code RouteBudget} bucket capacity in milliseconds (refill is a compile-time 5 ms per tick). */
    public record Route(int bucketMs) {
        public static final Route DEFAULTS = new Route(100);
    }

    /** R1 opportunistic valuables detour. */
    public record Detour(
            boolean enabled,
            int minValue,
            double minScore,
            int maxRadius,
            int maxUp,
            int maxDown,
            int leaseTicks,
            int minIntervalTicks,
            int maxPerMission,
            int minFreeSlots,
            int startHpMargin,
            int lavaClearRadius,
            int announceMinValue) {
        /** Extra lease granted per broken block. */
        public static final int LEASE_PER_BREAK_TICKS = 60;
        /** Hard ceiling of a detour lease. */
        public static final int LEASE_CAP_TICKS = 600;
        /** Hard ceiling of the failure-doubled gap between detours. */
        public static final int MIN_INTERVAL_CAP_TICKS = 3200;

        public static final Detour DEFAULTS = new Detour(true, 25, 1.2D, 12, 6, 2, 300, 200, 24, 3, 4, 4, 90);

        /** The inventory reserve used for rare expedition batches: one slot more than {@link #minFreeSlots()}. */
        public int minFreeSlotsRareBatch() {
            return minFreeSlots + 1;
        }
    }

    /** Safety vetoes. */
    public record Safety(boolean deepDarkVeto) {
        public static final Safety DEFAULTS = new Safety(true);
    }

    /** R3 point-of-interest detection. */
    public record Poi(
            boolean enabled,
            double possibleScore,
            double structureCertainScore,
            double cavernOpenFractionLow,
            double cavernOpenFractionHigh,
            boolean habitationDowngrade,
            boolean useOwnBiome,
            int dedupeRadius,
            int maxHoldsPerMission,
            int holdDeadlineTicks,
            boolean announceHold,
            NoticeRecipients noticeRecipients,
            UnavailablePolicy unavailablePolicy,
            CavernKeylessPolicy cavernKeylessPolicy,
            List<String> cavernDimensions) {
        public static final double DEFAULT_CAVERN_OPEN_FRACTION_LOW = 0.175D;
        public static final double DEFAULT_CAVERN_OPEN_FRACTION_HIGH = 0.554D;

        public static final Poi DEFAULTS = new Poi(
                true, 0.40D, 0.80D,
                DEFAULT_CAVERN_OPEN_FRACTION_LOW, DEFAULT_CAVERN_OPEN_FRACTION_HIGH,
                true, true, 40, 3, 160, true,
                NoticeRecipients.AUTHORIZED, UnavailablePolicy.STOP_IF_STRUCTURE, CavernKeylessPolicy.NOTIFY_ONLY,
                List.of("minecraft:overworld"));

        public Poi {
            cavernDimensions = cavernDimensions == null ? List.of() : List.copyOf(cavernDimensions);
            noticeRecipients = noticeRecipients == null ? NoticeRecipients.AUTHORIZED : noticeRecipients;
            unavailablePolicy = unavailablePolicy == null ? UnavailablePolicy.STOP_IF_STRUCTURE : unavailablePolicy;
            cavernKeylessPolicy = cavernKeylessPolicy == null ? CavernKeylessPolicy.NOTIFY_ONLY : cavernKeylessPolicy;
        }

        /** Width of the open-volume fraction range that maps to C in [0, 1] (0.379 by default). */
        public double cavernOpenFractionSpan() {
            return cavernOpenFractionHigh - cavernOpenFractionLow;
        }

        /** True if the cavern channel is configured for that dimension key (still needs R at least 12). */
        public boolean cavernEnabledIn(String dimensionKey) {
            return dimensionKey != null && cavernDimensions.contains(dimensionKey);
        }
    }

    /** R4 LLM advisor. */
    public record Advisor(
            boolean enabled,
            String model,
            int timeoutSeconds,
            int maxTokens,
            int maxConsultsPerMission,
            int minIntervalTicks,
            int breakerFailures,
            int breakerOpenTicks) {
        public static final Advisor DEFAULTS = new Advisor(true, "", 8, 512, 6, 400, 3, 6000);

        public Advisor {
            model = model == null ? "" : model;
        }
    }

    /** Sidecar persistence of the placed ledger. */
    public record Edits(boolean sidecar) {
        public static final Edits DEFAULTS = new Edits(true);
    }

    /** R2 exploration layers (both ship off). */
    public record Explore(boolean legChooser, boolean frontier, int frontierMaxRadius, double frontierMinUtility) {
        public static final Explore DEFAULTS = new Explore(false, false, 28, 1.0D);
    }

    private final AssistMode mode;
    private final ModeSource modeSource;
    private final boolean harnessOff;
    private final boolean envDeterministic;
    private final Sense sense;
    private final Tick tick;
    private final Route route;
    private final Detour detour;
    private final Safety safety;
    private final Poi poi;
    private final Advisor advisor;
    private final Edits edits;
    private final Explore explore;
    private final List<String> warnings;

    private MiningAssistConfig(
            AssistMode mode,
            ModeSource modeSource,
            boolean harnessOff,
            boolean envDeterministic,
            Sense sense,
            Tick tick,
            Route route,
            Detour detour,
            Safety safety,
            Poi poi,
            Advisor advisor,
            Edits edits,
            Explore explore,
            List<String> warnings) {
        this.mode = mode;
        this.modeSource = modeSource;
        this.harnessOff = harnessOff;
        this.envDeterministic = envDeterministic;
        this.sense = sense;
        this.tick = tick;
        this.route = route;
        this.detour = detour;
        this.safety = safety;
        this.poi = poi;
        this.advisor = advisor;
        this.edits = edits;
        this.explore = explore;
        this.warnings = truncated(warnings);
    }

    /**
     * Shortens over-long notes (they can quote user text). The note count is already bounded by the
     * schema: at most one per key, and {@link #MAX_DROPPED_ENTRY_NOTES} plus a summary for array entries.
     */
    private static List<String> truncated(List<String> raw) {
        List<String> bounded = new ArrayList<>(raw.size());
        for (String note : raw) {
            bounded.add(note.length() > MAX_WARNING_LENGTH ? note.substring(0, MAX_WARNING_LENGTH) + "..." : note);
        }
        return List.copyOf(bounded);
    }

    /** Every default, no file and no env. */
    public static MiningAssistConfig defaults(AssistMode shippedDefault, boolean harnessDefaultOff) {
        return parse(null, key -> null, shippedDefault, harnessDefaultOff);
    }

    /**
     * Builds the effective configuration.
     *
     * @param fileRoot          the whole parsed {@code minecraftai.json} (its top-level {@code "miningAssist"}
     *                          object is read); may be null
     * @param env               environment lookup, injected so tests need no real environment; may be null
     * @param shippedDefault    the mode of the current shipping phase, used when env and file give none
     *                          (null means {@link AssistMode#OFF})
     * @param harnessDefaultOff true when the harness asked for assist to be off by default
     */
    public static MiningAssistConfig parse(
            JsonObject fileRoot,
            Function<String, String> env,
            AssistMode shippedDefault,
            boolean harnessDefaultOff) {
        List<String> warnings = new ArrayList<>();
        Section root = new Section(fileRoot, "", warnings).child(FILE_SECTION);

        AssistMode mode;
        ModeSource source;
        String envMode = readEnv(env, ENV_MODE, warnings);
        boolean envDeterministic = isDeterministicFlag(readEnv(env, ENV_DETERMINISTIC, warnings));
        if (envMode != null && !envMode.isBlank()) {
            Optional<AssistMode> parsed = parseMode(envMode);
            mode = parsed.orElse(AssistMode.OFF);
            source = ModeSource.ENV;
            if (parsed.isEmpty()) {
                warnings.add("env " + ENV_MODE + "='" + envMode.trim() + "' is not a mode, assist off");
            }
        } else {
            String fileMode = root.modeText();
            if (fileMode != null) {
                Optional<AssistMode> parsed = parseMode(fileMode);
                mode = parsed.orElse(AssistMode.OFF);
                source = ModeSource.FILE;
                if (parsed.isEmpty()) {
                    warnings.add(FILE_SECTION + ".mode='" + fileMode.trim() + "' is not a mode, assist off");
                }
            } else {
                mode = shippedDefault == null ? AssistMode.OFF : shippedDefault;
                source = ModeSource.DEFAULT;
            }
        }
        boolean harnessOff = harnessDefaultOff && source == ModeSource.DEFAULT;

        Sense sense = parseSense(root.child("sense"), mode);
        Tick tick = parseTick(root.child("tick"));
        Route route = parseRoute(root.child("route"));
        Detour detour = parseDetour(root.child("detour"));
        Safety safety = parseSafety(root.child("safety"));
        Poi poi = parsePoi(root.child("poi"));
        Advisor advisor = parseAdvisor(root.child("advisor"));
        Edits edits = parseEdits(root.child("edits"));
        Explore explore = parseExplore(root.child("explore"));

        return new MiningAssistConfig(
                mode, source, harnessOff, envDeterministic,
                sense, tick, route, detour, safety, poi, advisor, edits, explore, warnings);
    }

    private static Sense parseSense(Section section, AssistMode mode) {
        return new Sense(
                section.integer("raysPerTick", Sense.DEFAULT_RAYS_PER_TICK, 1, 256),
                section.integer("globalRaysPerTick", Sense.DEFAULT_GLOBAL_RAYS_PER_TICK, 1, 4096),
                section.bool("adaptiveThrottle", true),
                section.bool("shadowLog", mode.allowsSense()));
    }

    private static Tick parseTick(Section section) {
        double startWorkMs = section.decimal("startWorkMs", Tick.DEFAULTS.startWorkMs(), 1.0D, 100.0D);
        double abortWorkMs = section.decimal(
                "abortWorkMs", Tick.DEFAULTS.abortWorkMs(), Tick.MIN_ABORT_WORK_MS, 100.0D);
        if (abortWorkMs < startWorkMs) {
            section.warn("tick.abortWorkMs raised to startWorkMs " + startWorkMs);
            abortWorkMs = startWorkMs;
        }
        return new Tick(startWorkMs, abortWorkMs);
    }

    private static Route parseRoute(Section section) {
        // A start needs at least 30 ms in the bucket, so a smaller capacity would block every start.
        return new Route(section.integer("bucketMs", Route.DEFAULTS.bucketMs(), 30, 1000));
    }

    private static Detour parseDetour(Section section) {
        Detour defaults = Detour.DEFAULTS;
        return new Detour(
                section.bool("enabled", defaults.enabled()),
                section.integer("minValue", defaults.minValue(), 1, 1000),
                section.decimal("minScore", defaults.minScore(), 0.1D, 100.0D),
                section.integer("maxRadius", defaults.maxRadius(), 1, 32),
                section.integer("maxUp", defaults.maxUp(), 0, 16),
                section.integer("maxDown", defaults.maxDown(), 0, 16),
                section.integer("leaseTicks", defaults.leaseTicks(), 60, Detour.LEASE_CAP_TICKS),
                section.integer("minIntervalTicks", defaults.minIntervalTicks(), 20, Detour.MIN_INTERVAL_CAP_TICKS),
                section.integer("maxPerMission", defaults.maxPerMission(), 0, 200),
                section.integer("minFreeSlots", defaults.minFreeSlots(), 1, 32),
                section.integer("startHpMargin", defaults.startHpMargin(), 0, 20),
                section.integer("lavaClearRadius", defaults.lavaClearRadius(), 1, 16),
                section.integer("announceMinValue", defaults.announceMinValue(), 0, 1000));
    }

    private static Safety parseSafety(Section section) {
        return new Safety(section.bool("deepDarkVeto", Safety.DEFAULTS.deepDarkVeto()));
    }

    private static Advisor parseAdvisor(Section section) {
        Advisor defaults = Advisor.DEFAULTS;
        return new Advisor(
                section.bool("enabled", defaults.enabled()),
                section.text("model", defaults.model(), MAX_MODEL_LENGTH),
                section.integer("timeoutSeconds", defaults.timeoutSeconds(), 1, 10),
                section.integer("maxTokens", defaults.maxTokens(), 64, 16384),
                section.integer("maxConsultsPerMission", defaults.maxConsultsPerMission(), 0, 64),
                section.integer("minIntervalTicks", defaults.minIntervalTicks(), 0, 12000),
                section.integer("breakerFailures", defaults.breakerFailures(), 1, 20),
                section.integer("breakerOpenTicks", defaults.breakerOpenTicks(), 200, 72000));
    }

    private static Edits parseEdits(Section section) {
        return new Edits(section.bool("sidecar", Edits.DEFAULTS.sidecar()));
    }

    private static Explore parseExplore(Section section) {
        return new Explore(
                section.bool("legChooser", Explore.DEFAULTS.legChooser()),
                section.bool("frontier", Explore.DEFAULTS.frontier()),
                section.integer("frontierMaxRadius", Explore.DEFAULTS.frontierMaxRadius(), 8, 64),
                section.decimal("frontierMinUtility", Explore.DEFAULTS.frontierMinUtility(), 0.0D, 5.0D));
    }

    private static Poi parsePoi(Section section) {
        Poi defaults = Poi.DEFAULTS;
        double possible = section.decimal("possibleScore", defaults.possibleScore(), 0.05D, 1.0D);
        double certain = section.decimal("structureCertainScore", defaults.structureCertainScore(), 0.05D, 1.0D);
        if (certain < possible) {
            section.warn("poi.structureCertainScore raised to possibleScore " + possible);
            certain = possible;
        }
        double[] fraction = openFraction(section);
        return new Poi(
                section.bool("enabled", defaults.enabled()),
                possible,
                certain,
                fraction[0],
                fraction[1],
                section.bool("habitationDowngrade", defaults.habitationDowngrade()),
                section.bool("useOwnBiome", defaults.useOwnBiome()),
                section.integer("dedupeRadius", defaults.dedupeRadius(), 1, 256),
                section.integer("maxHoldsPerMission", defaults.maxHoldsPerMission(), 0, 32),
                section.integer("holdDeadlineTicks", defaults.holdDeadlineTicks(), 20, 1200),
                section.bool("announceHold", defaults.announceHold()),
                section.choice("noticeRecipients", defaults.noticeRecipients(), NoticeRecipients::parse),
                section.choice("unavailablePolicy", defaults.unavailablePolicy(), UnavailablePolicy::parse),
                section.choice("cavernKeylessPolicy", defaults.cavernKeylessPolicy(), CavernKeylessPolicy::parse),
                cavernDimensions(section, defaults.cavernDimensions()));
    }

    /**
     * {@code poi.cavernOpenFraction}: a two-number array {@code [low, high]} or the design table's
     * string form {@code "0.175..0.554"}, with 0 <= low < high <= 1 after each end is clamped into
     * [0, 1] (clamping is reported). Anything else keeps the default pair.
     */
    private static double[] openFraction(Section section) {
        double[] fallback = {Poi.DEFAULT_CAVERN_OPEN_FRACTION_LOW, Poi.DEFAULT_CAVERN_OPEN_FRACTION_HIGH};
        JsonElement element = section.raw("cavernOpenFraction");
        if (element == null) {
            return fallback;
        }
        Double low = null;
        Double high = null;
        if (element.isJsonArray() && element.getAsJsonArray().size() == 2) {
            JsonArray pair = element.getAsJsonArray();
            low = numberOrNull(pair.get(0));
            high = numberOrNull(pair.get(1));
        } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            Matcher range = FRACTION_RANGE.matcher(element.getAsString());
            if (range.matches()) {
                low = Double.valueOf(range.group(1));
                high = Double.valueOf(range.group(2));
            }
        }
        if (low != null && high != null) {
            double clampedLow = Math.max(0.0D, Math.min(1.0D, low));
            double clampedHigh = Math.max(0.0D, Math.min(1.0D, high));
            if (clampedLow < clampedHigh) {
                if (clampedLow != low || clampedHigh != high) {
                    section.warn("clamped miningAssist.poi.cavernOpenFraction [" + low + ", " + high + "] to ["
                            + clampedLow + ", " + clampedHigh + "]");
                }
                return new double[] {clampedLow, clampedHigh};
            }
        }
        section.warn("ignored poi.cavernOpenFraction: expected [low, high] or \"low..high\" with 0 <= low < high <= 1");
        return fallback;
    }

    /**
     * {@code poi.cavernDimensions}: an array of dimension keys. Entries are trimmed and lower-cased,
     * a missing namespace becomes {@code minecraft:}, malformed entries are dropped. An explicitly
     * empty array disables the cavern channel everywhere; an array with only malformed entries is
     * ignored in favour of the default.
     */
    private static List<String> cavernDimensions(Section section, List<String> fallback) {
        JsonElement element = section.raw("cavernDimensions");
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonArray()) {
            section.warn("ignored poi.cavernDimensions: expected an array of strings");
            return fallback;
        }
        JsonArray array = element.getAsJsonArray();
        List<String> kept = new ArrayList<>();
        int dropped = 0;
        boolean overCap = false;
        for (JsonElement item : array) {
            String normalized = item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()
                    ? normalizeDimension(item.getAsString())
                    : null;
            if (normalized == null) {
                dropped++;
                if (dropped <= MAX_DROPPED_ENTRY_NOTES) {
                    section.warn("dropped malformed poi.cavernDimensions entry " + item);
                }
            } else if (!kept.contains(normalized)) {
                if (kept.size() < MAX_CAVERN_DIMENSIONS) {
                    kept.add(normalized);
                } else {
                    overCap = true;
                }
            }
        }
        if (dropped > MAX_DROPPED_ENTRY_NOTES) {
            section.warn("dropped " + (dropped - MAX_DROPPED_ENTRY_NOTES) + " more malformed poi.cavernDimensions entries");
        }
        if (overCap) {
            section.warn("poi.cavernDimensions keeps only the first " + MAX_CAVERN_DIMENSIONS + " entries");
        }
        if (kept.isEmpty() && !array.isEmpty()) {
            section.warn("ignored poi.cavernDimensions: no valid entries");
            return fallback;
        }
        return kept;
    }

    private static String normalizeDimension(String raw) {
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return null;
        }
        if (key.indexOf(':') < 0) {
            key = "minecraft:" + key;
        }
        return DIMENSION_KEY.matcher(key).matches() ? key : null;
    }

    private static Optional<AssistMode> parseMode(String text) {
        return AssistMode.parse(text);
    }

    private static <E extends Enum<E>> Optional<E> parseEnum(E[] values, String text) {
        if (text == null) {
            return Optional.empty();
        }
        String trimmed = text.trim();
        for (E candidate : values) {
            if (candidate.name().equalsIgnoreCase(trimmed)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * A JSON number as a double, or null for anything else. Literals beyond the double range come
     * back as +/-Infinity (so {@code 1e400} clamps to the upper bound like any other too-large
     * value); NaN, which no JSON text can spell, is treated as not a number.
     */
    private static Double numberOrNull(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            double value = element.getAsDouble();
            return Double.isNaN(value) ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String describe(double value) {
        return Double.isInfinite(value) ? Double.toString(value) : Long.toString((long) value);
    }

    // ---- deterministic flag --------------------------------------------------------------------

    /**
     * The deterministic flag as a pure function: true when the harness default-off is active, when
     * the bot is force-enabled, or when env {@code MINECRAFTAI_MINING_ASSIST_DETERMINISTIC} is exactly
     * {@code 1} (surrounding whitespace ignored). When true there is no time-based throttling, no
     * millisecond route budget, and the {@code TickHeadroom} start gate always passes.
     */
    public static boolean deterministic(boolean harnessOffActive, boolean forced, Function<String, String> env) {
        return harnessOffActive || forced || isDeterministicFlag(readEnv(env, ENV_DETERMINISTIC, null));
    }

    private static boolean isDeterministicFlag(String value) {
        return value != null && value.trim().equals("1");
    }

    /** One env lookup that never throws: a null function or a failing lookup reads as "unset". */
    private static String readEnv(Function<String, String> env, String key, List<String> warnings) {
        if (env == null) {
            return null;
        }
        try {
            return env.apply(key);
        } catch (RuntimeException failure) {
            if (warnings != null) {
                warnings.add("env lookup of " + key + " failed (" + failure.getClass().getSimpleName()
                        + "), treated as unset");
            }
            return null;
        }
    }

    /** {@link #deterministic(boolean, boolean, Function)} using this config's harness state and the env captured at parse time. */
    public boolean deterministic(boolean forced) {
        return harnessOff || forced || envDeterministic;
    }

    /** {@code sense.adaptiveThrottle}, which is off whenever the deterministic flag is on. */
    public boolean adaptiveThrottleActive(boolean forced) {
        return sense.adaptiveThrottle() && !deterministic(forced);
    }

    // ---- accessors -----------------------------------------------------------------------------

    /** The resolved mode (env, then file, then shipped default). */
    public AssistMode mode() {
        return mode;
    }

    public ModeSource modeSource() {
        return modeSource;
    }

    /**
     * True when the harness default-off is in effect: the harness asked for it and neither env nor
     * file gave a mode. Bots are then refused unless force-enabled.
     */
    public boolean harnessOff() {
        return harnessOff;
    }

    public Sense sense() {
        return sense;
    }

    public Tick tick() {
        return tick;
    }

    public Route route() {
        return route;
    }

    public Detour detour() {
        return detour;
    }

    public Safety safety() {
        return safety;
    }

    public Poi poi() {
        return poi;
    }

    public Advisor advisor() {
        return advisor;
    }

    public Edits edits() {
        return edits;
    }

    public Explore explore() {
        return explore;
    }

    /** Human-readable notes about ignored, clamped or adjusted input, for the caller to log once. */
    public List<String> warnings() {
        return warnings;
    }

    /** Mode is at least SENSE (sensor and shadow scoring may run). */
    public boolean senseActive() {
        return mode.allowsSense();
    }

    /** R1 is on: mode DETOUR or ALL and {@code detour.enabled}. */
    public boolean detourActive() {
        return mode.allowsDetour() && detour.enabled();
    }

    /** R3 is on: mode POI or ALL and {@code poi.enabled}. */
    public boolean poiActive() {
        return mode.allowsPoi() && poi.enabled();
    }

    /** R4 is on: R3 is on, {@code advisor.enabled}, and the caller holds a non-blank API key. */
    public boolean advisorActive(boolean apiKeyPresent) {
        return poiActive() && advisor.enabled() && apiKeyPresent;
    }

    /** The {@code enabledFor} rule for this config's mode and harness state; see {@link AssistGate}. */
    public boolean enabledFor(
            boolean forced, boolean originIsRealMissionKind, boolean auditSessionActive, boolean tpsDegraded) {
        return AssistGate.enabled(mode, harnessOff, forced, originIsRealMissionKind, auditSessionActive, tpsDegraded);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MiningAssistConfig that)) {
            return false;
        }
        return mode == that.mode
                && modeSource == that.modeSource
                && harnessOff == that.harnessOff
                && envDeterministic == that.envDeterministic
                && sense.equals(that.sense)
                && tick.equals(that.tick)
                && route.equals(that.route)
                && detour.equals(that.detour)
                && safety.equals(that.safety)
                && poi.equals(that.poi)
                && advisor.equals(that.advisor)
                && edits.equals(that.edits)
                && explore.equals(that.explore)
                && warnings.equals(that.warnings);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, modeSource, harnessOff, envDeterministic, sense, tick, route, detour, safety,
                poi, advisor, edits, explore, warnings);
    }

    @Override
    public String toString() {
        return "MiningAssistConfig[mode=" + mode + " (" + modeSource + "), harnessOff=" + harnessOff
                + ", " + sense + ", " + tick + ", " + route + ", " + detour + ", " + safety + ", " + poi
                + ", " + advisor + ", " + edits + ", " + explore + "]";
    }

    /** Typed, tolerant reader over one JSON object; records warnings instead of throwing. */
    private static final class Section {
        private final JsonObject json;
        private final String path;
        private final List<String> warnings;

        Section(JsonObject json, String path, List<String> warnings) {
            this.json = json;
            this.path = path;
            this.warnings = warnings;
        }

        Section child(String name) {
            JsonElement element = raw(name);
            String childPath = path.isEmpty() ? name : path + "." + name;
            if (element == null) {
                return new Section(null, childPath, warnings);
            }
            if (!element.isJsonObject()) {
                warnings.add("ignored " + childPath + ": expected an object");
                return new Section(null, childPath, warnings);
            }
            return new Section(element.getAsJsonObject(), childPath, warnings);
        }

        void warn(String message) {
            warnings.add(message);
        }

        /** The value, or null when absent, JSON null, or this section is itself absent. */
        JsonElement raw(String key) {
            if (json == null) {
                return null;
            }
            JsonElement element = json.get(key);
            return element == null || element.isJsonNull() ? null : element;
        }

        /** {@code mode} as a non-blank string, or null when absent, blank or not a string (the latter warns). */
        String modeText() {
            JsonElement element = raw("mode");
            if (element == null) {
                return null;
            }
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                wrongType("mode", "a string");
                return null;
            }
            String text = element.getAsString();
            return text.isBlank() ? null : text;
        }

        boolean bool(String key, boolean fallback) {
            JsonElement element = raw(key);
            if (element == null) {
                return fallback;
            }
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
                return element.getAsBoolean();
            }
            wrongType(key, "a boolean");
            return fallback;
        }

        int integer(String key, int fallback, int min, int max) {
            JsonElement element = raw(key);
            if (element == null) {
                return fallback;
            }
            Double number = numberOrNull(element);
            if (number == null || number != Math.rint(number)) {
                wrongType(key, "an integer");
                return fallback;
            }
            if (number < min || number > max) {
                int clamped = number < min ? min : max;
                warnings.add("clamped " + full(key) + " " + describe(number) + " to " + clamped);
                return clamped;
            }
            return number.intValue();
        }

        double decimal(String key, double fallback, double min, double max) {
            JsonElement element = raw(key);
            if (element == null) {
                return fallback;
            }
            Double number = numberOrNull(element);
            if (number == null) {
                wrongType(key, "a number");
                return fallback;
            }
            if (number < min || number > max) {
                double clamped = number < min ? min : max;
                warnings.add("clamped " + full(key) + " " + number + " to " + clamped);
                return clamped;
            }
            return number;
        }

        String text(String key, String fallback, int maxLength) {
            JsonElement element = raw(key);
            if (element == null) {
                return fallback;
            }
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                wrongType(key, "a string");
                return fallback;
            }
            String value = element.getAsString().trim();
            if (value.length() > maxLength) {
                warnings.add("truncated " + full(key) + " to " + maxLength + " characters");
                return value.substring(0, maxLength);
            }
            return value;
        }

        <E> E choice(String key, E fallback, Function<String, Optional<E>> parser) {
            JsonElement element = raw(key);
            if (element == null) {
                return fallback;
            }
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                Optional<E> parsed = parser.apply(element.getAsString());
                if (parsed.isPresent()) {
                    return parsed.get();
                }
                warnings.add("ignored " + full(key) + ": unknown value '" + element.getAsString() + "'");
                return fallback;
            }
            wrongType(key, "a string");
            return fallback;
        }

        private String full(String key) {
            return path.isEmpty() ? key : path + "." + key;
        }

        private void wrongType(String key, String expected) {
            warnings.add("ignored " + full(key) + ": expected " + expected);
        }
    }
}

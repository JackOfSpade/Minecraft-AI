package io.github.zoyluo.aibot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogCategory;
import io.github.zoyluo.aibot.mode.OperatingProfile;
import io.github.zoyluo.aibot.mode.OperatorCapabilities;
import io.github.zoyluo.aibot.mode.CapabilityPolicy;
import io.github.zoyluo.aibot.mode.PrivilegedCapability;
import io.github.zoyluo.aibot.mode.ProfileResolver;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

public record AIBotConfig(
        OperatingProfile profile,
        OperatorCapabilities operatorCapabilities,
        // The JSON section is "llm". "deepseek" is the pre-rename name and is still accepted so an
        // existing aibot.json keeps working.
        @SerializedName(value = "llm", alternate = {"deepseek"}) Llm llm,
        Perception perception,
        Brain brain,
        Watchdog watchdog,
        Logging logging,
        Survival survival,
        Combat combat,
        Night night,
        Mining mining,
        Goal goal,
        Nav nav,
        Pickup pickup
) {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Environment variable that overrides the API key from the config file (any provider). */
    public static final String ENV_API_KEY = "AIBOT_LLM_API_KEY";
    /** Pre-rename environment variable; still honoured, but {@link #ENV_API_KEY} wins. */
    public static final String LEGACY_ENV_API_KEY = "DEEPSEEK_API_KEY";

    private static AIBotConfig instance = defaults();

    public static AIBotConfig get() {
        return instance;
    }

    /**
     * Parses an already-read {@code aibot.json} root and fills every missing value from the
     * defaults. Both the {@code llm} section and the legacy {@code deepseek} section are read.
     * Returns null when the JSON maps to nothing.
     */
    static AIBotConfig parse(JsonObject root, OperatingProfile profile) {
        if (root.has("llm") && root.has("deepseek")) {
            // Both spellings present: the new name wins deterministically instead of depending on
            // which key Gson happens to read last.
            root = root.deepCopy();
            root.remove("deepseek");
        }
        AIBotConfig parsed = GSON.fromJson(root, AIBotConfig.class);
        return parsed == null ? null : parsed.withProfile(profile).withDefaults();
    }

    /** API-key override from the environment: {@link #ENV_API_KEY} first, then the legacy name. */
    static String apiKeyFromEnv(Function<String, String> env) {
        for (String name : List.of(ENV_API_KEY, LEGACY_ENV_API_KEY)) {
            String value = env.apply(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public static AIBotConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("aibot.json");
        AIBotConfig loaded = defaults();
        ProfileResolver.Resolution profileResolution;
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                JsonElement element = JsonParser.parseReader(reader);
                if (element == null || !element.isJsonObject()) {
                    throw new IllegalArgumentException("config_root_must_be_object");
                }
                JsonObject root = element.getAsJsonObject();
                profileResolution = ProfileResolver.resolve(
                        true, root, System.getenv(ProfileResolver.ENVIRONMENT_KEY));
                if (root.has("deepseek") && !root.has("llm")) {
                    BotLog.warn(LogCategory.CONFIG, null, "config_legacy_key",
                            "key", "deepseek", "use", "llm");
                }
                AIBotConfig parsed = parse(root, profileResolution.profile());
                if (parsed != null) {
                    loaded = parsed;
                }
            } catch (IOException | RuntimeException exception) {
                BotLog.error("config_read_failed", exception, "path", path);
                profileResolution = ProfileResolver.resolve(
                        false, null, System.getenv(ProfileResolver.ENVIRONMENT_KEY));
                loaded = loaded.withProfile(profileResolution.profile());
            }
        } else {
            try {
                Files.createDirectories(path.getParent());
                try (Writer writer = Files.newBufferedWriter(path)) {
                    GSON.toJson(loaded, writer);
                }
                BotLog.config("config_template_written", "path", path);
            } catch (IOException exception) {
                BotLog.error("config_write_failed", exception, "path", path);
            }
            profileResolution = ProfileResolver.resolve(
                    false, null, System.getenv(ProfileResolver.ENVIRONMENT_KEY));
            loaded = loaded.withProfile(profileResolution.profile());
        }

        logProfileResolution(profileResolution, path, loaded.operatorCapabilities());

        String envKey = apiKeyFromEnv(System::getenv);
        if (envKey != null) {
            loaded = loaded.withLlm(loaded.llm().withApiKey(envKey));
        }
        if (loaded.llm().apiKey().isBlank()) {
            BotLog.warn(LogCategory.CONFIG, null, "llm_key_missing");
        }
        instance = loaded;
        return loaded;
    }

    public AIBotConfig withLlm(Llm llm) {
        return new AIBotConfig(profile(), operatorCapabilities(), llm, perception(), brain(), watchdog(), logging(), survival(), combat(), night(), mining(), goal(), nav(), pickup());
    }

    private AIBotConfig withProfile(OperatingProfile profile) {
        return new AIBotConfig(profile, operatorCapabilities(), llm(), perception(), brain(), watchdog(), logging(), survival(), combat(), night(), mining(), goal(), nav(), pickup());
    }

    private AIBotConfig withDefaults() {
        AIBotConfig defaults = defaults();
        return new AIBotConfig(
                profile == null ? defaults.profile : profile,
                operatorCapabilities == null
                        ? defaults.operatorCapabilities
                        : operatorCapabilities.withDefaults(defaults.operatorCapabilities),
                llm == null ? defaults.llm : llm.withDefaults(defaults.llm),
                perception == null ? defaults.perception : perception.withDefaults(defaults.perception),
                brain == null ? defaults.brain : brain.withDefaults(defaults.brain),
                watchdog == null ? defaults.watchdog : watchdog.withDefaults(defaults.watchdog),
                logging == null ? defaults.logging : logging.withDefaults(defaults.logging),
                survival == null ? defaults.survival : survival.withDefaults(defaults.survival),
                combat == null ? defaults.combat : combat.withDefaults(defaults.combat),
                night == null ? defaults.night : night.withDefaults(defaults.night),
                mining == null ? defaults.mining : mining.withDefaults(defaults.mining),
                goal == null ? defaults.goal : goal.withDefaults(defaults.goal),
                nav == null ? defaults.nav : nav.withDefaults(defaults.nav),
                pickup == null ? defaults.pickup : pickup.withDefaults(defaults.pickup));
    }

    public static AIBotConfig defaults() {
        return new AIBotConfig(
                OperatingProfile.STRICT_SURVIVAL,
                OperatorCapabilities.defaults(),
                // V4 的 reasoning 与正文共享 max_tokens,故显式降低 effort 并放宽预算,
                // 避免思考过程吃光额度、让本该发出的 tool_call 被截断。
                // The shipped defaults point at DeepSeek's public API; set llm.baseUrl / llm.model /
                // llm.apiKey to use any other OpenAI-compatible or Gemini endpoint.
                new Llm("", "https://api.deepseek.com", "deepseek-v4-flash", 8192, 0.3D, 60, 3, 500,
                        Boolean.TRUE, "low"),
                new Perception(16, 20, 10, 10, false),
                new Brain(36, 6, 3, false, true, false, 3, true),
                new Watchdog(200),
                new Logging(true, "logs/aibot", true, "daily", 50, 30, 3, true, Map.of(
                        "LIFECYCLE", "INFO",
                        "COMM", "INFO",
                        "API", "INFO",
                        "ACTION", "INFO",
                        "PERCEPTION", "DEBUG",
                        "PATH", "DEBUG",
                        "TASK", "INFO",
                        "DANGER", "INFO",
                        "ERROR", "ERROR",
                        "CONFIG", "INFO")),
                new Survival(14, 6),
                new Combat(10, 2),
                new Night(true, 8),
                new Mining(2, 0.10D, true),
                new Goal(24, true, true), // S7:配方补全后链更深(熟食/盾/钻装备等),16→24 留余量
                new Nav(1.0D, 12, 60, 30, 4, 2, 3.0D, 3),
                new Pickup(2.75D, 2.5D, 8.0D)); // 实测 1.5/1.0 太小:砍树掉落物垂直差>1 就吸不到→countSoFar=0 死循环
    }

    private static void logProfileResolution(ProfileResolver.Resolution resolution,
                                             Path path,
                                             OperatorCapabilities capabilities) {
        for (ProfileResolver.Warning warning : resolution.warnings()) {
            switch (warning) {
                case LEGACY_PROFILE_MISSING -> BotLog.warn(LogCategory.CONFIG, null,
                        "operating_profile_legacy_compatibility",
                        "path", path,
                        "effective_profile", resolution.profile().configValue(),
                        "migration", "set_top_level_profile_explicitly");
                case INVALID_FILE_PROFILE -> BotLog.warn(LogCategory.CONFIG, null,
                        "operating_profile_invalid",
                        "path", path,
                        "effective_profile", OperatingProfile.STRICT_SURVIVAL.configValue());
                case INVALID_ENVIRONMENT_PROFILE -> BotLog.warn(LogCategory.CONFIG, null,
                        "operating_profile_environment_invalid",
                        "environment", ProfileResolver.ENVIRONMENT_KEY,
                        "effective_profile", OperatingProfile.STRICT_SURVIVAL.configValue());
            }
        }
        OperatorCapabilities configured = capabilities == null ? OperatorCapabilities.none() : capabilities;
        List<String> effectiveCapabilities = Arrays.stream(PrivilegedCapability.values())
                .filter(capability -> CapabilityPolicy.decide(
                        resolution.profile(), configured, capability).allowed())
                .map(Enum::name)
                .toList();
        BotLog.config("operating_profile_resolved",
                "profile", resolution.profile().configValue(),
                "source", resolution.source(),
                "configured_hidden_block_scan", configured.hiddenBlockScan(),
                "configured_emergency_teleport", configured.emergencyTeleport(),
                "configured_forced_pickup", configured.forcedPickup(),
                "configured_manual_teleport", configured.manualTeleport(),
                "effective_capabilities", effectiveCapabilities);
    }

    /**
     * LLM API settings (the {@code "llm"} section of {@code aibot.json}; the legacy name
     * {@code "deepseek"} is still read). Any OpenAI-compatible chat-completions endpoint works, and
     * a {@code baseUrl} on Google's generativelanguage host selects the Gemini Interactions client.
     *
     * <p>{@code thinking} and {@code reasoningEffort} are sent explicitly rather than left to the
     * server default. DeepSeek V4 enables thinking at {@code high} effort by default, and reasoning
     * output shares the {@code maxTokens} budget — an implicit default would let reasoning starve
     * the tool call the bot actually needs.</p>
     */
    public record Llm(
            String apiKey,
            String baseUrl,
            String model,
            int maxTokens,
            double temperature,
            int timeoutSeconds,
            int retryCount,
            int retryBackoffMs,
            Boolean thinking,
            String reasoningEffort
    ) {
        public static final List<String> REASONING_EFFORTS = List.of("low", "high", "max");

        Llm withApiKey(String apiKey) {
            return new Llm(apiKey, baseUrl, model, maxTokens, temperature, timeoutSeconds,
                    retryCount, retryBackoffMs, thinking, reasoningEffort);
        }

        Llm withDefaults(Llm defaults) {
            return new Llm(
                    apiKey == null ? defaults.apiKey : apiKey,
                    blankToDefault(baseUrl, defaults.baseUrl),
                    blankToDefault(model, defaults.model),
                    positiveOrDefault(maxTokens, defaults.maxTokens),
                    temperature,
                    positiveOrDefault(timeoutSeconds, defaults.timeoutSeconds),
                    Math.max(0, retryCount),
                    positiveOrDefault(retryBackoffMs, defaults.retryBackoffMs),
                    boolOrDefault(thinking, defaults.thinking),
                    reasoningEffort != null && REASONING_EFFORTS.contains(reasoningEffort)
                            ? reasoningEffort : defaults.reasoningEffort);
        }
    }

    public record Perception(int radius, int maxBlocks, int maxEntities, int maxItems, boolean includeRawLists) {
        Perception withDefaults(Perception defaults) {
            return new Perception(
                    positiveOrDefault(radius, defaults.radius),
                    positiveOrDefault(maxBlocks, defaults.maxBlocks),
                    positiveOrDefault(maxEntities, defaults.maxEntities),
                    positiveOrDefault(maxItems, defaults.maxItems),
                    includeRawLists);
        }
    }

    public record Brain(
            int maxHistoryMessages,
            int maxToolCallsPerTurn,
            int maxTurnsPerRequest,
            Boolean exposeLowLevelTools,
            Boolean enableMemoryTools,
            Boolean enableCoordinationTools,
            int maxTaskRetries,
            Boolean verboseReports
    ) {
        Brain withDefaults(Brain defaults) {
            return new Brain(
                    positiveOrDefault(maxHistoryMessages, defaults.maxHistoryMessages),
                    positiveOrDefault(maxToolCallsPerTurn, defaults.maxToolCallsPerTurn),
                    positiveOrDefault(maxTurnsPerRequest, defaults.maxTurnsPerRequest),
                    boolOrDefault(exposeLowLevelTools, defaults.exposeLowLevelTools),
                    boolOrDefault(enableMemoryTools, defaults.enableMemoryTools),
                    boolOrDefault(enableCoordinationTools, defaults.enableCoordinationTools),
                    positiveOrDefault(maxTaskRetries, defaults.maxTaskRetries),
                    boolOrDefault(verboseReports, defaults.verboseReports));
        }

        public boolean exposesLowLevelTools() {
            return Boolean.TRUE.equals(exposeLowLevelTools);
        }

        public boolean memoryToolsEnabled() {
            return Boolean.TRUE.equals(enableMemoryTools);
        }

        public boolean coordinationToolsEnabled() {
            return Boolean.TRUE.equals(enableCoordinationTools);
        }

        public boolean verboseReportsEnabled() {
            return Boolean.TRUE.equals(verboseReports);
        }
    }

    public record Survival(int hungerEatThreshold, int hungerCriticalThreshold) {
        Survival withDefaults(Survival defaults) {
            return new Survival(
                    positiveOrDefault(hungerEatThreshold, defaults.hungerEatThreshold),
                    positiveOrDefault(hungerCriticalThreshold, defaults.hungerCriticalThreshold));
        }
    }

    public record Combat(int retreatHp, int maxEnemiesToFight) {
        Combat withDefaults(Combat defaults) {
            return new Combat(
                    positiveOrDefault(retreatHp, defaults.retreatHp),
                    positiveOrDefault(maxEnemiesToFight, defaults.maxEnemiesToFight));
        }
    }

    public record Night(boolean autoSleep, int torchLightThreshold) {
        Night withDefaults(Night defaults) {
            return new Night(autoSleep, positiveOrDefault(torchLightThreshold, defaults.torchLightThreshold));
        }
    }

    public record Mining(int returnWhenFreeSlots, double toolDurabilityFloor, boolean placeTorches) {
        Mining withDefaults(Mining defaults) {
            return new Mining(
                    positiveOrDefault(returnWhenFreeSlots, defaults.returnWhenFreeSlots),
                    toolDurabilityFloor > 0.0D ? toolDurabilityFloor : defaults.toolDurabilityFloor,
                    placeTorches);
        }
    }

    public record Goal(int maxPlanDepth, Boolean replanOnFailure, Boolean autoToolFill) {
        Goal withDefaults(Goal defaults) {
            return new Goal(
                    positiveOrDefault(maxPlanDepth, defaults.maxPlanDepth),
                    boolOrDefault(replanOnFailure, defaults.replanOnFailure),
                    boolOrDefault(autoToolFill, defaults.autoToolFill));
        }

        public boolean replanOnFailureEnabled() {
            return Boolean.TRUE.equals(replanOnFailure);
        }

        public boolean autoToolFillEnabled() {
            return Boolean.TRUE.equals(autoToolFill);
        }
    }

    public record Nav(double jumpReach,
                      int sidleAfter,
                      int sidleLimit,
                      int hardLimit,
                      int lookahead,
                      int nodeRetry,
                      double sprintMinDist,
                      int maxSafeFall) {
        Nav withDefaults(Nav defaults) {
            return new Nav(
                    positiveDoubleOrDefault(jumpReach, defaults.jumpReach),
                    positiveOrDefault(sidleAfter, defaults.sidleAfter),
                    positiveOrDefault(sidleLimit, defaults.sidleLimit),
                    positiveOrDefault(hardLimit, defaults.hardLimit),
                    positiveOrDefault(lookahead, defaults.lookahead),
                    positiveOrDefault(nodeRetry, defaults.nodeRetry),
                    positiveDoubleOrDefault(sprintMinDist, defaults.sprintMinDist),
                    positiveOrDefault(maxSafeFall, defaults.maxSafeFall));
        }
    }

    public record Pickup(double forceRadiusH, double forceRadiusV, double sweepRadius) {
        Pickup withDefaults(Pickup defaults) {
            return new Pickup(
                    positiveDoubleOrDefault(forceRadiusH, defaults.forceRadiusH),
                    positiveDoubleOrDefault(forceRadiusV, defaults.forceRadiusV),
                    positiveDoubleOrDefault(sweepRadius, defaults.sweepRadius));
        }
    }

    public record Watchdog(int stuckWindowTicks) {
        Watchdog withDefaults(Watchdog defaults) {
            return new Watchdog(positiveOrDefault(stuckWindowTicks, defaults.stuckWindowTicks));
        }
    }

    public record Logging(
            boolean enabled,
            String directory,
            boolean perBotFile,
            String rotation,
            int maxFileSizeMb,
            int maxBackups,
            /** How many play sessions' (one per server start) logs to keep under
             *  {@code <directory>/sessions/}; older sessions are deleted outright on the next
             *  start regardless of size, so history never grows past this many sessions. */
            int maxSessions,
            boolean mirrorToSlf4j,
            Map<String, String> categories
    ) {
        Logging withDefaults(Logging defaults) {
            return new Logging(
                    enabled,
                    blankToDefault(directory, defaults.directory),
                    perBotFile,
                    blankToDefault(rotation, defaults.rotation),
                    positiveOrDefault(maxFileSizeMb, defaults.maxFileSizeMb),
                    positiveOrDefault(maxBackups, defaults.maxBackups),
                    positiveOrDefault(maxSessions, defaults.maxSessions),
                    mirrorToSlf4j,
                    categories == null || categories.isEmpty() ? defaults.categories : categories);
        }
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int positiveOrDefault(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    private static double positiveDoubleOrDefault(double value, double defaultValue) {
        return value > 0.0D ? value : defaultValue;
    }

    private static Boolean boolOrDefault(Boolean value, Boolean defaultValue) {
        return value == null ? defaultValue : value;
    }
}

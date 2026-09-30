package io.github.zoyluo.minecraftai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.navigation.NavEngine;
import io.github.zoyluo.minecraftai.mode.OperatorCapabilities;
import io.github.zoyluo.minecraftai.mode.CapabilityPolicy;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.mode.ProfileResolver;
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

public record MinecraftAiConfig(
        OperatingProfile profile,
        OperatorCapabilities operatorCapabilities,
        // The JSON section is "llm". "deepseek" is the pre-rename name and is still accepted so an
        // existing minecraftai.json keeps working.
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
        Pickup pickup,
        Conversation conversation,
        Storage storage,
        // Companion behaviour switches (pace, hostile-bot targeting, gear choice, follow, warden). See docs/OPERATING_PROFILES.md.
        Behaviour behaviour
) {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Environment variable that overrides the API key from the config file (any provider). */
    public static final String ENV_API_KEY = "MINECRAFTAI_LLM_API_KEY";
    /** Pre-rename environment variable; still honoured, but {@link #ENV_API_KEY} wins. */
    public static final String LEGACY_ENV_API_KEY = "DEEPSEEK_API_KEY";

    private static MinecraftAiConfig instance = defaults();

    public static MinecraftAiConfig get() {
        return instance;
    }

    /**
     * Parses an already-read {@code minecraftai.json} root and fills every missing value from the
     * defaults. Both the {@code llm} section and the legacy {@code deepseek} section are read.
     * Returns null when the JSON maps to nothing.
     */
    static MinecraftAiConfig parse(JsonObject root, OperatingProfile profile) {
        if (root.has("llm") && root.has("deepseek")) {
            // Both spellings present: the new name wins deterministically instead of depending on
            // which key Gson happens to read last.
            root = root.deepCopy();
            root.remove("deepseek");
        }
        JsonElement night = root.get("night");
        if (night != null && night.isJsonObject()
                && night.getAsJsonObject().has("autoLight") && night.getAsJsonObject().has("autoSleep")) {
            // Same rule for the pre-rename night.autoSleep key: the new name wins.
            root = root.deepCopy();
            root.getAsJsonObject("night").remove("autoSleep");
        }
        MinecraftAiConfig parsed = GSON.fromJson(root, MinecraftAiConfig.class);
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

    public static MinecraftAiConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("minecraftai.json");
        MinecraftAiConfig loaded = defaults();
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
                MinecraftAiConfig parsed = parse(root, profileResolution.profile());
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

    public MinecraftAiConfig withLlm(Llm llm) {
        return new MinecraftAiConfig(profile(), operatorCapabilities(), llm, perception(), brain(), watchdog(), logging(), survival(), combat(), night(), mining(), goal(), nav(), pickup(), conversation(), storage(), behaviourOrDefaults());
    }

    /** A copy with another navigation section (tests swap the Baritone capability switches with it). */
    public MinecraftAiConfig withNav(Nav nav) {
        return new MinecraftAiConfig(profile(), operatorCapabilities(), llm(), perception(), brain(), watchdog(), logging(), survival(), combat(), night(), mining(), goal(), nav, pickup(), conversation(), storage(), behaviourOrDefaults());
    }

    private MinecraftAiConfig withProfile(OperatingProfile profile) {
        return new MinecraftAiConfig(profile, operatorCapabilities(), llm(), perception(), brain(), watchdog(), logging(), survival(), combat(), night(), mining(), goal(), nav(), pickup(), conversation(), storage(), behaviourOrDefaults());
    }

    private MinecraftAiConfig withDefaults() {
        MinecraftAiConfig defaults = defaults();
        return new MinecraftAiConfig(
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
                pickup == null ? defaults.pickup : pickup.withDefaults(defaults.pickup),
                conversation == null ? defaults.conversation : conversation.withDefaults(defaults.conversation),
                storage == null ? defaults.storage : storage.withDefaults(defaults.storage),
                behaviour == null ? defaults.behaviour : behaviour.withDefaults(defaults.behaviour));
    }

    public static MinecraftAiConfig defaults() {
        return new MinecraftAiConfig(
                OperatingProfile.STRICT_SURVIVAL,
                OperatorCapabilities.defaults(),
                // V4's reasoning shares max_tokens with the main response, so explicitly lower the
                // effort and widen the budget, to keep the thinking process from eating the whole
                // budget and truncating the tool_call that should have been emitted.
                // The shipped defaults point at DeepSeek's public API; set llm.baseUrl / llm.model /
                // llm.apiKey to use any other OpenAI-compatible or Gemini endpoint.
                new Llm("", "https://api.deepseek.com", "deepseek-v4-flash", 8192, 0.3D, 60, 3, 500,
                        Boolean.TRUE, "low"),
                new Perception(16, 20, 10, 10, false),
                // verboseReports off by default: the LLM's own say(purpose=plan/status) already narrates
                // starting/finishing a task in natural language, so the templated "Starting X 0/4." /
                // "Completed: X N/M." progress lines this flag adds are redundant, debug-phrased chat spam
                // unless a player explicitly opts back in for blow-by-blow task telemetry.
                new Brain(36, 6, 3, false, true, false, 3, false, ConversationMemory.defaults()),
                new Watchdog(200),
                new Logging(true, "logs/minecraftai", true, "daily", 50, 30, 3, 10, true, Map.of(
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
                new Goal(24, true, true), // S7: recipe auto-fill made chains deeper (cooked food/shield/diamond gear, etc.), raised 16→24 for headroom
                new Nav(1.0D, 12, 60, 30, 4, 2, 3.0D, 3, NavEngine.LEGACY.configValue(), BaritoneCaps.defaults()),
                new Pickup(2.75D, 2.5D, 8.0D), // measured 1.5/1.0 as too small: tree-drop items with a vertical gap >1 don't get pulled in → countSoFar=0 infinite loop
                new Conversation(true, 12000, 200, 0.03D, 1, 4, 200.0D, 0.15D, 2.0D, 25.0D, 100),
                new Storage(64, 16, 3, 24, true),
                Behaviour.defaults());
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
     * LLM API settings (the {@code "llm"} section of {@code minecraftai.json}; the legacy name
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
            /** Boxed so an explicit {@code 0.0} (a legitimate, fully-deterministic setting) can be told
             *  apart from a key omitted from the user's JSON, which falls back to the shipped default. */
            Double temperature,
            int timeoutSeconds,
            /** Boxed so an explicit {@code 0} (no retries) can be told apart from a key omitted from the
             *  user's JSON, which falls back to the shipped default. A negative value is still clamped
             *  up to 0. */
            Integer retryCount,
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
                    doubleOrDefault(temperature, defaults.temperature),
                    positiveOrDefault(timeoutSeconds, defaults.timeoutSeconds),
                    Math.max(0, intOrDefault(retryCount, defaults.retryCount)),
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
            Boolean verboseReports,
            /** Conversation memory (summary of older chat that survives restarts); null = defaults. */
            ConversationMemory memory
    ) {
        /** Pre-{@code memory} shape, kept so older callers and tests still compile. */
        public Brain(int maxHistoryMessages, int maxToolCallsPerTurn, int maxTurnsPerRequest,
                     Boolean exposeLowLevelTools, Boolean enableMemoryTools, Boolean enableCoordinationTools,
                     int maxTaskRetries, Boolean verboseReports) {
            this(maxHistoryMessages, maxToolCallsPerTurn, maxTurnsPerRequest, exposeLowLevelTools,
                    enableMemoryTools, enableCoordinationTools, maxTaskRetries, verboseReports, null);
        }

        /** The memory settings, never null. */
        public ConversationMemory memorySettings() {
            return memory == null ? ConversationMemory.defaults() : memory;
        }

        Brain withDefaults(Brain defaults) {
            return new Brain(
                    positiveOrDefault(maxHistoryMessages, defaults.maxHistoryMessages),
                    positiveOrDefault(maxToolCallsPerTurn, defaults.maxToolCallsPerTurn),
                    positiveOrDefault(maxTurnsPerRequest, defaults.maxTurnsPerRequest),
                    boolOrDefault(exposeLowLevelTools, defaults.exposeLowLevelTools),
                    boolOrDefault(enableMemoryTools, defaults.enableMemoryTools),
                    boolOrDefault(enableCoordinationTools, defaults.enableCoordinationTools),
                    positiveOrDefault(maxTaskRetries, defaults.maxTaskRetries),
                    boolOrDefault(verboseReports, defaults.verboseReports),
                    memory == null ? defaults.memory : memory.withDefaults(defaults.memory));
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

    /**
     * Per-bot conversation memory. Chat lines that fall out of the short recent-chat window are folded, with ONE
     * extra model call at a time on a background thread, into a compact summary that is kept in the bot's saved
     * data and shown to the model as background notes. Every call fails soft: on any error the old lines are
     * simply dropped (plain trimming), never blocking the server thread.
     */
    public record ConversationMemory(
            Boolean enabled,
            /** Hard cap on the stored summary length in characters. */
            int maxSummaryChars,
            /** Summarise once this many older lines are waiting; fewer stay visible verbatim until then. */
            int summarizeAfterLines,
            /** Cap on older lines waiting for a summary; the oldest beyond this are dropped. */
            int maxPendingLines,
            /** Most recent chat lines saved verbatim with the bot (restored on load). */
            int persistTailLines,
            /** Wall-clock limit of the summary call, in seconds. */
            int timeoutSeconds,
            /** Minimum seconds between two summary calls for the same bot (call budget). */
            int minIntervalSeconds,
            /** Output token limit of the summary call. */
            int maxTokens
    ) {
        public static ConversationMemory defaults() {
            return new ConversationMemory(true, 500, 8, 40, 12, 20, 60, 400);
        }

        public ConversationMemory withDefaults(ConversationMemory defaults) {
            return new ConversationMemory(
                    boolOrDefault(enabled, defaults.enabled),
                    positiveOrDefault(maxSummaryChars, defaults.maxSummaryChars),
                    positiveOrDefault(summarizeAfterLines, defaults.summarizeAfterLines),
                    positiveOrDefault(maxPendingLines, defaults.maxPendingLines),
                    positiveOrDefault(persistTailLines, defaults.persistTailLines),
                    positiveOrDefault(timeoutSeconds, defaults.timeoutSeconds),
                    positiveOrDefault(minIntervalSeconds, defaults.minIntervalSeconds),
                    positiveOrDefault(maxTokens, defaults.maxTokens));
        }

        public boolean isEnabled() {
            return Boolean.TRUE.equals(enabled);
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

    /**
     * {@code autoLight} gates both automatic torch-lighting reflexes of the danger watcher (the night
     * top-up and the dark-spot reflex; neither ever lights the open surface). Its pre-rename JSON
     * name was {@code autoSleep} (it never gated sleeping); that key is still read, and the new name
     * wins when both are present.
     */
    public record Night(@SerializedName(value = "autoLight", alternate = {"autoSleep"}) Boolean autoLight,
                        int torchLightThreshold) {
        Night withDefaults(Night defaults) {
            return new Night(
                    boolOrDefault(autoLight, defaults.autoLight),
                    positiveOrDefault(torchLightThreshold, defaults.torchLightThreshold));
        }
    }

    /**
     * Pack-mule policy. {@code junkKeepStone} / {@code junkKeepOther} are the throwaway budgets kept
     * for building and pillaring (stone-like blocks vs. dirt/gravel/sand/netherrack...); only the
     * surplus is ever stowed. {@code nearlyFullFreeSlots}: at or below this many free main slots the
     * bot stows junk on its own when it is idle or follows a player past a storage block within reach.
     * {@code stowRadius}: how far an idle bot will walk to a remembered/observed storage block for it.
     */
    public record Storage(int junkKeepStone, int junkKeepOther, int nearlyFullFreeSlots, int stowRadius,
                          Boolean autoStowJunk) {
        Storage withDefaults(Storage defaults) {
            return new Storage(
                    positiveOrDefault(junkKeepStone, defaults.junkKeepStone),
                    positiveOrDefault(junkKeepOther, defaults.junkKeepOther),
                    positiveOrDefault(nearlyFullFreeSlots, defaults.nearlyFullFreeSlots),
                    positiveOrDefault(stowRadius, defaults.stowRadius),
                    boolOrDefault(autoStowJunk, defaults.autoStowJunk));
        }

        public boolean autoStowJunkEnabled() {
            return Boolean.TRUE.equals(autoStowJunk);
        }
    }

    /** Source-compatible constructor for callers that predate the behaviour section: it carries the behaviour defaults. */
    public MinecraftAiConfig(OperatingProfile profile, OperatorCapabilities operatorCapabilities, Llm llm,
                             Perception perception, Brain brain, Watchdog watchdog, Logging logging,
                             Survival survival, Combat combat, Night night, Mining mining, Goal goal, Nav nav,
                             Pickup pickup, Conversation conversation, Storage storage) {
        this(profile, operatorCapabilities, llm, perception, brain, watchdog, logging, survival, combat, night,
                mining, goal, nav, pickup, conversation, storage, Behaviour.defaults());
    }

    /** A copy with another behaviour section (tests swap the pace, targeting, gear, follow and warden switches with it). */
    public MinecraftAiConfig withBehaviour(Behaviour behaviour) {
        return new MinecraftAiConfig(profile(), operatorCapabilities(), llm(), perception(), brain(), watchdog(), logging(), survival(), combat(), night(), mining(), goal(), nav(), pickup(), conversation(), storage(), behaviour);
    }

    /** The behaviour switches; never null (a config built without the section carries the defaults). */
    public Behaviour behaviourOrDefaults() {
        return behaviour == null ? Behaviour.defaults() : behaviour;
    }

    /** Same as {@link #behaviourOrDefaults()}: the accessor never returns null. */
    
    public Behaviour behaviour() {
        return behaviourOrDefaults();
    }

    /** Source-compatible constructor for callers that predate the storage section. */
    public MinecraftAiConfig(OperatingProfile profile, OperatorCapabilities operatorCapabilities, Llm llm,
                             Perception perception, Brain brain, Watchdog watchdog, Logging logging,
                             Survival survival, Combat combat, Night night, Mining mining, Goal goal, Nav nav,
                             Pickup pickup, Conversation conversation) {
        this(profile, operatorCapabilities, llm, perception, brain, watchdog, logging, survival, combat, night,
                mining, goal, nav, pickup, conversation, new Storage(64, 16, 3, 24, true));
    }

    public record Mining(int returnWhenFreeSlots, double toolDurabilityFloor, Boolean placeTorches) {
        Mining withDefaults(Mining defaults) {
            return new Mining(
                    positiveOrDefault(returnWhenFreeSlots, defaults.returnWhenFreeSlots),
                    toolDurabilityFloor > 0.0D ? toolDurabilityFloor : defaults.toolDurabilityFloor,
                    boolOrDefault(placeTorches, defaults.placeTorches));
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
                      int maxSafeFall,
                      // "legacy" (default) or "baritone": which navigator answers ordinary walk requests. See docs/NAVIGATION_ENGINE.md.
                      String engine,
                      // The Baritone movement capabilities (parkour, water-bucket falls, vines, mob avoidance). See docs/NAVIGATION_ENGINE.md.
                      BaritoneCaps baritone) {
        /** Source-compatible constructor for callers that predate the Baritone capability section. */
        public Nav(double jumpReach, int sidleAfter, int sidleLimit, int hardLimit, int lookahead, int nodeRetry,
                   double sprintMinDist, int maxSafeFall, String engine) {
            this(jumpReach, sidleAfter, sidleLimit, hardLimit, lookahead, nodeRetry, sprintMinDist, maxSafeFall, engine,
                    BaritoneCaps.defaults());
        }

        /** The configured engine; a missing or unknown value is {@link NavEngine#LEGACY}. */
        public NavEngine engineChoice() {
            return NavEngine.parse(engine);
        }

        /** The Baritone capabilities; never null. */
        public BaritoneCaps baritoneCaps() {
            return baritone == null ? BaritoneCaps.defaults() : baritone;
        }

        Nav withDefaults(Nav defaults) {
            return new Nav(
                    positiveDoubleOrDefault(jumpReach, defaults.jumpReach),
                    positiveOrDefault(sidleAfter, defaults.sidleAfter),
                    positiveOrDefault(sidleLimit, defaults.sidleLimit),
                    positiveOrDefault(hardLimit, defaults.hardLimit),
                    positiveOrDefault(lookahead, defaults.lookahead),
                    positiveOrDefault(nodeRetry, defaults.nodeRetry),
                    positiveDoubleOrDefault(sprintMinDist, defaults.sprintMinDist),
                    positiveOrDefault(maxSafeFall, defaults.maxSafeFall),
                    NavEngine.parse(engine).configValue(),
                    baritone == null ? defaults.baritoneCaps() : baritone.withDefaults(defaults.baritoneCaps()));
        }
    }

    /**
     * Moves the Baritone navigation engine may use ({@code nav.baritone}); each is a legitimate move of a player and stays inside
     * the survival rules (see tools/baritone/README.md). They are applied to Baritone's global settings at every plan request
     * ({@code BaritoneSettings.applyNavLimits}), so a reload takes effect at the next plan.
     *
     * <ul>
     *   <li>{@code parkour}: jumps over gaps of 2 and 3 blocks (a sprint jump; a wider gap is never planned).</li>
     *   <li>{@code parkourPlace}: a block placed in mid-air jump to land on (needs throwaway blocks and permission to place).</li>
     *   <li>{@code parkourAscend}: a jump over a gap that lands one block higher.</li>
     *   <li>{@code waterBucketFall}: a fall too high to survive is broken by placing water from a carried water bucket, which is
     *       picked up again; never in the Nether. {@code maxBucketFall} is the highest fall planned that way.</li>
     *   <li>{@code vines}: vines and other climbable blocks may be stood on while climbing (Baritone's {@code allowVines}).</li>
     *   <li>{@code mobAvoidance}: routes keep away from hostile mobs the bot can see (never from ones it cannot observe).</li>
     * </ul>
     */
    public record BaritoneCaps(Boolean parkour,
                               Boolean parkourPlace,
                               Boolean parkourAscend,
                               Boolean waterBucketFall,
                               int maxBucketFall,
                               Boolean vines,
                               Boolean mobAvoidance) {
        public static BaritoneCaps defaults() {
            return new BaritoneCaps(true, true, true, true, 12, true, true);
        }

        /** Every capability switched off (the behaviour of the engine before these switches existed). */
        public static BaritoneCaps allOff() {
            return new BaritoneCaps(false, false, false, false, 12, false, false);
        }

        BaritoneCaps withDefaults(BaritoneCaps defaults) {
            return new BaritoneCaps(
                    boolOrDefault(parkour, defaults.parkour),
                    boolOrDefault(parkourPlace, defaults.parkourPlace),
                    boolOrDefault(parkourAscend, defaults.parkourAscend),
                    boolOrDefault(waterBucketFall, defaults.waterBucketFall),
                    positiveOrDefault(maxBucketFall, defaults.maxBucketFall),
                    boolOrDefault(vines, defaults.vines),
                    boolOrDefault(mobAvoidance, defaults.mobAvoidance));
        }

        public boolean parkourEnabled() {
            return Boolean.TRUE.equals(parkour);
        }

        public boolean parkourPlaceEnabled() {
            return Boolean.TRUE.equals(parkourPlace);
        }

        public boolean parkourAscendEnabled() {
            return Boolean.TRUE.equals(parkourAscend);
        }

        public boolean waterBucketFallEnabled() {
            return Boolean.TRUE.equals(waterBucketFall);
        }

        public boolean vinesEnabled() {
            return Boolean.TRUE.equals(vines);
        }

        public boolean mobAvoidanceEnabled() {
            return Boolean.TRUE.equals(mobAvoidance);
        }
    }

    /**
     * Companion behaviour switches (the {@code "behaviour"} section of {@code minecraftai.json}); both operating profiles use the
     * same defaults. Every section is null-tolerant: a missing section, key or Boolean is the default, a number that is missing,
     * zero, negative or NaN is the default. See docs/OPERATING_PROFILES.md ("Behaviour switches").
     */
    public record Behaviour(Pace pace, Targeting targeting, Gear gear, Follow follow, Warden warden) {
        public static Behaviour defaults() {
            return new Behaviour(Pace.defaults(), Targeting.defaults(), Gear.defaults(), Follow.defaults(), Warden.defaults());
        }

        Behaviour withDefaults(Behaviour defaults) {
            return new Behaviour(
                    pace == null ? defaults.paceOrDefaults() : pace.withDefaults(defaults.paceOrDefaults()),
                    targeting == null ? defaults.targetingOrDefaults() : targeting.withDefaults(defaults.targetingOrDefaults()),
                    gear == null ? defaults.gearOrDefaults() : gear.withDefaults(defaults.gearOrDefaults()),
                    follow == null ? defaults.followOrDefaults() : follow.withDefaults(defaults.followOrDefaults()),
                    warden == null ? defaults.wardenOrDefaults() : warden.withDefaults(defaults.wardenOrDefaults()));
        }

        /** The pace section; never null. */
        public Pace paceOrDefaults() {
            return pace == null ? Pace.defaults() : pace;
        }

        /** The targeting section; never null. */
        public Targeting targetingOrDefaults() {
            return targeting == null ? Targeting.defaults() : targeting;
        }

        /** The gear section; never null. */
        public Gear gearOrDefaults() {
            return gear == null ? Gear.defaults() : gear;
        }

        /** The follow section; never null. */
        public Follow followOrDefaults() {
            return follow == null ? Follow.defaults() : follow;
        }

        /** The warden section; never null. */
        public Warden wardenOrDefaults() {
            return warden == null ? Warden.defaults() : warden;
        }
    }

    /**
     * Walking pace of controller-driven travel. {@code enabled}: the natural sprint/walk/sneak policy at all;
     * {@code itemUseSlowdown}: the 0.2 movement scale while using an item; {@code movementExhaustion}: hunger from moving;
     * {@code routeSprintDistance} / {@code routeWalkDistance}: a route sprints from that far to its goal and walks below the
     * second one; {@code quietZoneCaution}: cap the pace in sculk/warden zones.
     */
    public record Pace(Boolean enabled,
                       Boolean itemUseSlowdown,
                       Boolean movementExhaustion,
                       double routeSprintDistance,
                       double routeWalkDistance,
                       Boolean quietZoneCaution) {
        public static Pace defaults() {
            return new Pace(true, true, true, 8.0D, 4.5D, true);
        }

        Pace withDefaults(Pace defaults) {
            double sprint = positiveFiniteOrDefault(routeSprintDistance, defaults.routeSprintDistance);
            double walk = positiveFiniteOrDefault(routeWalkDistance, defaults.routeWalkDistance);
            if (walk >= sprint) {
                // An inverted (or equal) pair would make the pace flap: both fall back together.
                sprint = defaults.routeSprintDistance;
                walk = defaults.routeWalkDistance;
            }
            return new Pace(
                    boolOrDefault(enabled, defaults.enabled),
                    boolOrDefault(itemUseSlowdown, defaults.itemUseSlowdown),
                    boolOrDefault(movementExhaustion, defaults.movementExhaustion),
                    sprint,
                    walk,
                    boolOrDefault(quietZoneCaution, defaults.quietZoneCaution));
        }

        public boolean paceEnabled() {
            return boolOrTrue(enabled, defaults().enabled);
        }

        public boolean itemUseSlowdownEnabled() {
            return boolOrTrue(itemUseSlowdown, defaults().itemUseSlowdown);
        }

        public boolean movementExhaustionEnabled() {
            return boolOrTrue(movementExhaustion, defaults().movementExhaustion);
        }

        public boolean quietZoneCautionEnabled() {
            return boolOrTrue(quietZoneCaution, defaults().quietZoneCaution);
        }
    }

    /**
     * Who counts as an enemy. {@code hostileBots}: a foreign bot (a fake player that is not one of ours) that attacks the owner or a
     * Minecraft-AI bot is a target; {@code ownerVision}: what the owner can see may nominate such a target (within
     * {@code ownerVisionRange} blocks and inside the owner's view cone, {@code ownerViewConeDot} = the minimum dot product of the
     * look vector and the direction, in (0, 1]); {@code aggressorMemoryTicks}: how long a mark lasts after the last hostile act.
     */
    public record Targeting(Boolean hostileBots,
                            Boolean ownerVision,
                            int aggressorMemoryTicks,
                            int ownerVisionRange,
                            double ownerViewConeDot) {
        public static Targeting defaults() {
            return new Targeting(true, true, 600, 48, 0.5D);
        }

        Targeting withDefaults(Targeting defaults) {
            return new Targeting(
                    boolOrDefault(hostileBots, defaults.hostileBots),
                    boolOrDefault(ownerVision, defaults.ownerVision),
                    positiveOrDefault(aggressorMemoryTicks, defaults.aggressorMemoryTicks),
                    positiveOrDefault(ownerVisionRange, defaults.ownerVisionRange),
                    // A dot product lives in [-1, 1]; 0 and below is "unset" (a missing key reads as 0), above 1 is impossible.
                    ownerViewConeDot > 0.0D && ownerViewConeDot <= 1.0D ? ownerViewConeDot : defaults.ownerViewConeDot);
        }

        public boolean hostileBotsEnabled() {
            return boolOrTrue(hostileBots, defaults().hostileBots);
        }

        public boolean ownerVisionEnabled() {
            return boolOrTrue(ownerVision, defaults().ownerVision);
        }
    }

    /**
     * Gear choice. {@code worstFirst}: tools, weapons and armour are always the cheapest item that can still do the job
     * (enchantments add value); the player controls it by taking items out of the bot's inventory. There is no escalation.
     */
    public record Gear(Boolean worstFirst) {
        public static Gear defaults() {
            return new Gear(true);
        }

        Gear withDefaults(Gear defaults) {
            return new Gear(boolOrDefault(worstFirst, defaults.worstFirst));
        }

        public boolean worstFirstEnabled() {
            return boolOrTrue(worstFirst, defaults().worstFirst);
        }
    }

    /**
     * Following a player. {@code escortOnly}: a following bot only knocks back what is in melee range and keeps following;
     * {@code walkGap} / {@code sprintGap}: the follower walks at or below the first gap and sprints from the second one.
     */
    public record Follow(Boolean escortOnly, double walkGap, double sprintGap) {
        public static Follow defaults() {
            return new Follow(true, 6.0D, 10.0D);
        }

        Follow withDefaults(Follow defaults) {
            double walk = positiveFiniteOrDefault(walkGap, defaults.walkGap);
            double sprint = positiveFiniteOrDefault(sprintGap, defaults.sprintGap);
            if (walk >= sprint) {
                walk = defaults.walkGap;
                sprint = defaults.sprintGap;
            }
            return new Follow(boolOrDefault(escortOnly, defaults.escortOnly), walk, sprint);
        }

        public boolean escortOnlyEnabled() {
            return boolOrTrue(escortOnly, defaults().escortOnly);
        }
    }

    /** Wardens are never fought. {@code sneakAway}: creep away from a calm one (a hunting one is outrun at a sprint). */
    public record Warden(Boolean sneakAway) {
        public static Warden defaults() {
            return new Warden(true);
        }

        Warden withDefaults(Warden defaults) {
            return new Warden(boolOrDefault(sneakAway, defaults.sneakAway));
        }

        public boolean sneakAwayEnabled() {
            return boolOrTrue(sneakAway, defaults().sneakAway);
        }
    }

    public record Pickup(double forceRadiusH,double forceRadiusV, double sweepRadius) {
        Pickup withDefaults(Pickup defaults) {
            return new Pickup(
                    positiveDoubleOrDefault(forceRadiusH, defaults.forceRadiusH),
                    positiveDoubleOrDefault(forceRadiusV, defaults.forceRadiusV),
                    positiveDoubleOrDefault(sweepRadius, defaults.sweepRadius));
        }
    }

    /**
     * Ambient bot-to-bot conversations: occasionally, when nobody is instructing them, 1+ eligible
     * companions have a short in-character back-and-forth, each line an independent LLM call (see
     * {@link io.github.zoyluo.minecraftai.brain.AmbientConversationCoordinator}). Never routes through the
     * per-bot planner/tool-loop, so it never disrupts whatever a bot is doing.
     */
    public record Conversation(
            Boolean enabled,
            /** Minimum ticks between the END of one conversation and the next being allowed to start. */
            int cooldownTicks,
            /** How often, in ticks, eligibility is re-rolled once the cooldown has elapsed. */
            int checkIntervalTicks,
            /** Probability [0,1] of starting a conversation on each eligible check. */
            double startChancePerCheck,
            int minParticipants,
            int maxParticipants,
            /** Reading-speed estimate used to size the pause before the next bot replies. */
            double readingWordsPerMinute,
            /** Added per word of the previous line, on top of reading time, to simulate "thinking". */
            double thinkingSecondsPerWord,
            double minReplyDelaySeconds,
            double maxReplyDelaySeconds,
            int maxTokens
    ) {
        Conversation withDefaults(Conversation defaults) {
            return new Conversation(
                    boolOrDefault(enabled, defaults.enabled),
                    positiveOrDefault(cooldownTicks, defaults.cooldownTicks),
                    positiveOrDefault(checkIntervalTicks, defaults.checkIntervalTicks),
                    startChancePerCheck > 0.0D ? Math.min(1.0D, startChancePerCheck) : defaults.startChancePerCheck,
                    positiveOrDefault(minParticipants, defaults.minParticipants),
                    positiveOrDefault(maxParticipants, defaults.maxParticipants),
                    positiveDoubleOrDefault(readingWordsPerMinute, defaults.readingWordsPerMinute),
                    thinkingSecondsPerWord > 0.0D ? thinkingSecondsPerWord : defaults.thinkingSecondsPerWord,
                    positiveDoubleOrDefault(minReplyDelaySeconds, defaults.minReplyDelaySeconds),
                    positiveDoubleOrDefault(maxReplyDelaySeconds, defaults.maxReplyDelaySeconds),
                    positiveOrDefault(maxTokens, defaults.maxTokens));
        }
    }

    public record Watchdog(int stuckWindowTicks) {
        Watchdog withDefaults(Watchdog defaults) {
            return new Watchdog(positiveOrDefault(stuckWindowTicks, defaults.stuckWindowTicks));
        }
    }

    public record Logging(
            Boolean enabled,
            String directory,
            Boolean perBotFile,
            String rotation,
            int maxFileSizeMb,
            /** Archived logs older than this many days are deleted outright on rotation cleanup,
             *  regardless of how many archive files exist; it is a day-based age cutoff, not a count
             *  of archive files to retain. See {@code BotLogWriter.cleanupArchives()}. */
            int maxBackups,
            /** How many bot-less play sessions (one per server start, no bot ever logged) to keep under
             *  {@code <directory>/sessions/}, counting the session just started. Sessions with bot activity are
             *  bounded separately by {@code maxBotSessions}, so bot-less restarts never evict them. */
            int maxSessions,
            /** How many of the newest sessions that contain bot activity to keep. */
            int maxBotSessions,
            Boolean mirrorToSlf4j,
            Map<String, String> categories
    ) {
        Logging withDefaults(Logging defaults) {
            return new Logging(
                    boolOrDefault(enabled, defaults.enabled),
                    blankToDefault(directory, defaults.directory),
                    boolOrDefault(perBotFile, defaults.perBotFile),
                    blankToDefault(rotation, defaults.rotation),
                    positiveOrDefault(maxFileSizeMb, defaults.maxFileSizeMb),
                    positiveOrDefault(maxBackups, defaults.maxBackups),
                    positiveOrDefault(maxSessions, defaults.maxSessions),
                    positiveOrDefault(maxBotSessions, defaults.maxBotSessions),
                    boolOrDefault(mirrorToSlf4j, defaults.mirrorToSlf4j),
                    categories == null || categories.isEmpty() ? defaults.categories : categories);
        }
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int positiveOrDefault(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    private static double positiveFiniteOrDefault(double value, double defaultValue) {
        return value > 0.0D && Double.isFinite(value) ? value : defaultValue;
    }

    private static boolean boolOrTrue(Boolean value, Boolean defaultValue) {
        return value == null ? Boolean.TRUE.equals(defaultValue) : value;
    }

    private static double positiveDoubleOrDefault(double value, double defaultValue) {
        return value > 0.0D ? value : defaultValue;
    }

    private static Boolean boolOrDefault(Boolean value, Boolean defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static Double doubleOrDefault(Double value, Double defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static Integer intOrDefault(Integer value, Integer defaultValue) {
        return value == null ? defaultValue : value;
    }
}

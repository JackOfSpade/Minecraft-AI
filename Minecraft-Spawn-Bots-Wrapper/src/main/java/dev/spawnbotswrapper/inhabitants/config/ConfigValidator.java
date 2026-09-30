package dev.spawnbotswrapper.inhabitants.config;

import dev.spawnbotswrapper.inhabitants.util.BotNameShape;
import dev.spawnbotswrapper.inhabitants.util.RangedDistances;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Repairs a loaded config in place (clamp, fill in missing sections, fix misplaced keys) and reports
 * what it changed. A hand-edited file must never be able to crash world population later, so
 * everything odd is fixed here at load time with a warning instead.
 */
public final class ConfigValidator {
    private ConfigValidator() {
    }

    public static List<String> validate(InhabitantsConfig c) {
        List<String> w = new ArrayList<>();

        if (c.configVersion > InhabitantsConfig.CURRENT_VERSION) {
            w.add("configVersion " + c.configVersion + " is newer than this addon understands ("
                    + InhabitantsConfig.CURRENT_VERSION + "); unknown options are ignored");
        }
        c.commandPermissionLevel = clamp(w, "commandPermissionLevel", c.commandPermissionLevel, 0, 4);

        if (c.defaults == null) {
            c.defaults = new InhabitantsConfig.Rule();
            w.add("'default' section missing; using built-in defaults");
        }
        c.defaults.occupiedChance = clamp(w, "default.occupiedChance", c.defaults.occupiedChance, 0.0, 1.0);
        c.defaults.minBots = clamp(w, "default.minBots", c.defaults.minBots, 1, EffectiveRule.MAX_BOTS_PER_STRUCTURE);

        if (c.include == null) {
            c.include = new ArrayList<>(List.of("*"));
            w.add("'include' missing; using [\"*\"]");
        }
        if (c.exclude == null) {
            c.exclude = new ArrayList<>();
        }
        if (c.structures == null) {
            c.structures = new LinkedHashMap<>();
        }
        if (c.tags == null) {
            c.tags = new LinkedHashMap<>();
        }
        moveMisplacedKeys(c, w);
        validateOverrides(c.structures, "structures", w);
        validateOverrides(c.tags, "tags", w);

        if (c.dimensions == null) {
            c.dimensions = new InhabitantsConfig.Dimensions();
        }
        if (c.dimensions.include == null) {
            c.dimensions.include = new ArrayList<>(List.of("*"));
        }
        if (c.dimensions.exclude == null) {
            c.dimensions.exclude = new ArrayList<>();
        }

        if (c.profiles == null) {
            c.profiles = new InhabitantsConfig.Profiles();
        }
        c.profiles.coverageBuckets = clamp(w, "profiles.coverageBuckets", c.profiles.coverageBuckets, 2, 64);
        if (c.profiles.disabledEnchantments == null) {
            c.profiles.disabledEnchantments = new ArrayList<>(DisabledEnchantments.DEFAULT);
            w.add("profiles.disabledEnchantments is null; using " + DisabledEnchantments.DEFAULT);
        } else {
            c.profiles.disabledEnchantments = DisabledEnchantments.clean(
                    c.profiles.disabledEnchantments, "profiles.disabledEnchantments", w);
        }

        if (c.deterministic == null) {
            c.deterministic = new InhabitantsConfig.Deterministic();
        }
        if (c.deterministic.salt == null) {
            c.deterministic.salt = "";
        }

        if (c.processing == null) {
            c.processing = new InhabitantsConfig.Processing();
        }
        InhabitantsConfig.Processing p = c.processing;
        p.maxStructuresPerTick = clamp(w, "processing.maxStructuresPerTick", p.maxStructuresPerTick, 1, 64);
        p.maxBotsPerTick = clamp(w, "processing.maxBotsPerTick", p.maxBotsPerTick, 1, 16);
        p.spawnIntervalTicks = clamp(w, "processing.spawnIntervalTicks", p.spawnIntervalTicks, 0, 200);
        p.blocksPerBot = clamp(w, "processing.blocksPerBot", p.blocksPerBot, 1.0, 1_000_000.0);
        p.initialDelayTicks = clamp(w, "processing.initialDelayTicks", p.initialDelayTicks, 0, 1200);
        p.retryIntervalTicks = clamp(w, "processing.retryIntervalTicks", p.retryIntervalTicks, 10, 2400);
        p.maxAttemptsPerStructure = clamp(w, "processing.maxAttemptsPerStructure", p.maxAttemptsPerStructure, 1, 1000);
        p.maxLiveBots = clamp(w, "processing.maxLiveBots", p.maxLiveBots, 0, 10000);
        p.appearTimeoutTicks = clamp(w, "processing.appearTimeoutTicks", p.appearTimeoutTicks, 20, 2400);
        p.saveIntervalTicks = clamp(w, "processing.saveIntervalTicks", p.saveIntervalTicks, 100, 72000);
        p.restoreSettleTicks = clamp(w, "processing.restoreSettleTicks", p.restoreSettleTicks, 0, 72000);
        p.goneConfirmTicks = clamp(w, "processing.goneConfirmTicks", p.goneConfirmTicks, 200, 1728000);

        if (c.connection == null) {
            c.connection = new InhabitantsConfig.Connection();
            w.add("'connection' section missing; using built-in defaults");
        }

        c.criticalFallTicks = clamp(w, "criticalFallTicks", c.criticalFallTicks, 0, 10);
        if (c.startupCommands == null) {
            c.startupCommands = new ArrayList<>();
        }
        validateTpsThrottle(c, w);
        validateCombatLog(c, w);
        validateAggro(c, w);
        validatePvpbotSettings(c, w);
        validateRangedPacing(c, w);

        if (c.spawning == null) {
            c.spawning = new InhabitantsConfig.Spawning();
        }
        InhabitantsConfig.Spawning s = c.spawning;
        String backend = s.backend == null ? "AUTO" : s.backend.trim().toUpperCase(Locale.ROOT);
        if (!backend.equals("AUTO") && !backend.equals("CLASS") && !backend.equals("COMMAND")) {
            w.add("spawning.backend '" + s.backend + "' is not AUTO/CLASS/COMMAND; using AUTO");
            backend = "AUTO";
        }
        s.backend = backend;
        s.positionAttemptsPerBot = clamp(w, "spawning.positionAttemptsPerBot", s.positionAttemptsPerBot, 1, 1000);
        s.minBotSeparation = clamp(w, "spawning.minBotSeparation", s.minBotSeparation, 0.0, 64.0);
        s.namePrefix = sanitizePrefix(s.namePrefix, w);
        return w;
    }

    private static void validateTpsThrottle(InhabitantsConfig c, List<String> w) {
        if (c.tpsThrottle == null) {
            c.tpsThrottle = new InhabitantsConfig.TpsThrottle();
        }
        InhabitantsConfig.TpsThrottle t = c.tpsThrottle;
        if (t.healthyMillis != null || t.degradedMillis != null) {
            w.add("tpsThrottle.healthyMillis / tpsThrottle.degradedMillis are no longer used (the fixed 52.6 / 55.6 ms "
                    + "levels were below this kind of server's normal tick time and shed bots while it was merely busy); "
                    + "the governor now uses degradedFloorMillis/degradedFactor and recoveredFloorMillis/recoveredFactor "
                    + "around the server's own measured baseline. Remove the old keys.");
            t.healthyMillis = null;
            t.degradedMillis = null;
        }
        t.degradedFloorMillis = clamp(w, "tpsThrottle.degradedFloorMillis", t.degradedFloorMillis, 50.0, 1000.0);
        t.degradedFactor = clamp(w, "tpsThrottle.degradedFactor", t.degradedFactor, 1.0, 10.0);
        t.recoveredFloorMillis = clamp(w, "tpsThrottle.recoveredFloorMillis", t.recoveredFloorMillis, 50.0, 1000.0);
        t.recoveredFactor = clamp(w, "tpsThrottle.recoveredFactor", t.recoveredFactor, 1.0, 10.0);
        t.baselineMaxMillis = clamp(w, "tpsThrottle.baselineMaxMillis", t.baselineMaxMillis, 50.0, 1000.0);
        t.baselineWindowTicks = clamp(w, "tpsThrottle.baselineWindowTicks", t.baselineWindowTicks, 100, 1728000);
        t.sustainTicks = clamp(w, "tpsThrottle.sustainTicks", t.sustainTicks, 0, 72000);
        t.minDwellTicks = clamp(w, "tpsThrottle.minDwellTicks", t.minDwellTicks, 0, 1728000);
        t.checkIntervalTicks = clamp(w, "tpsThrottle.checkIntervalTicks", t.checkIntervalTicks, 1, 72000);
        t.despawnBatchSize = clamp(w, "tpsThrottle.despawnBatchSize", t.despawnBatchSize, 1, 1000);
        if (t.recoveredFloorMillis >= t.degradedFloorMillis) {
            double fixed = Math.max(50.0, t.degradedFloorMillis - 2.0);
            w.add("tpsThrottle.recoveredFloorMillis (" + t.recoveredFloorMillis + ") must be below degradedFloorMillis ("
                    + t.degradedFloorMillis + ") so the state has hysteresis; using " + fixed);
            t.recoveredFloorMillis = fixed;
        }
    }

    /** Smallest and largest {@code pvpbotSettings.maxTargetDistance} (blocks); 128 is PvP BOT's catalog maximum and vanilla's line-of-sight cap. */
    public static final double MIN_TARGET_DISTANCE = 4.0;
    public static final double MAX_TARGET_DISTANCE = 128.0;

    private static void validatePvpbotSettings(InhabitantsConfig c, List<String> w) {
        if (c.pvpbotSettings == null) {
            c.pvpbotSettings = new InhabitantsConfig.PvpbotSettings();
            w.add("'pvpbotSettings' is null; no PvP BOT setting is managed");
        }
        InhabitantsConfig.PvpbotSettings s = c.pvpbotSettings;
        s.maxTargetDistance = finiteOrNull(w, "pvpbotSettings.maxTargetDistance", s.maxTargetDistance);
        s.rangedMinRange = finiteOrNull(w, "pvpbotSettings.rangedMinRange", s.rangedMinRange);
        s.rangedOptimalRange = finiteOrNull(w, "pvpbotSettings.rangedOptimalRange", s.rangedOptimalRange);
        s.rangedMaxRange = finiteOrNull(w, "pvpbotSettings.rangedMaxRange", s.rangedMaxRange);
        if (s.maxTargetDistance != null) {
            s.maxTargetDistance = clamp(w, "pvpbotSettings.maxTargetDistance", s.maxTargetDistance,
                    MIN_TARGET_DISTANCE, MAX_TARGET_DISTANCE);
        }
        // The ordering can only be judged here when every value involved is configured; a partly configured set is
        // judged again when it is applied, against the values PvP BOT itself has for the missing ones.
        String problem = RangedDistances.problem(s.rangedMinRange, s.rangedOptimalRange, s.rangedMaxRange, s.maxTargetDistance);
        if (problem != null && s.rangedMinRange != null && s.rangedOptimalRange != null && s.rangedMaxRange != null) {
            w.add("pvpbotSettings: " + problem + "; the three ranged ranges are not managed");
            s.rangedMinRange = null;
            s.rangedOptimalRange = null;
            s.rangedMaxRange = null;
        }
    }

    private static Double finiteOrNull(List<String> w, String name, Double v) {
        if (v != null && (v.isNaN() || v.isInfinite())) {
            w.add(name + " is not a finite number; not managed");
            return null;
        }
        return v;
    }

    private static void validateRangedPacing(InhabitantsConfig c, List<String> w) {
        if (c.rangedPacing == null) {
            c.rangedPacing = new InhabitantsConfig.RangedPacing();
            w.add("'rangedPacing' is null; using the defaults");
        }
        c.rangedPacing.aimSettleTicks = clamp(w, "rangedPacing.aimSettleTicks", c.rangedPacing.aimSettleTicks, 0, 40);
        c.rangedPacing.crossbowMinShotIntervalTicks = clamp(w, "rangedPacing.crossbowMinShotIntervalTicks",
                c.rangedPacing.crossbowMinShotIntervalTicks, 1, 200);
    }

    private static void validateCombatLog(InhabitantsConfig c, List<String> w) {
        if (c.combatLog == null) {
            c.combatLog = new InhabitantsConfig.CombatLog();
        }
        c.combatLog.coalesceTicks = clamp(w, "combatLog.coalesceTicks", c.combatLog.coalesceTicks, 1, 72000);
        c.combatLog.maxLinesPerMinute = clamp(w, "combatLog.maxLinesPerMinute", c.combatLog.maxLinesPerMinute, 1, 100000);
    }

    private static void validateAggro(InhabitantsConfig c, List<String> w) {
        if (c.aggro == null) {
            c.aggro = new InhabitantsConfig.Aggro();
        }
        InhabitantsConfig.Aggro a = c.aggro;
        a.acquireRange = clamp(w, "aggro.acquireRange", a.acquireRange, 2.0, 64.0);
        a.scanIntervalTicks = clamp(w, "aggro.scanIntervalTicks", a.scanIntervalTicks, 1, 40);
        a.leashRange = clamp(w, "aggro.leashRange", a.leashRange, a.acquireRange, 128.0);
        a.loseSightTicks = clamp(w, "aggro.loseSightTicks", a.loseSightTicks, 1, 72000);
        a.returnArriveDistance = clamp(w, "aggro.returnArriveDistance", a.returnArriveDistance, 0.5, 16.0);
        a.returnStuckTicks = clamp(w, "aggro.returnStuckTicks", a.returnStuckTicks, 1, 72000);
        a.returnMaxTicks = clamp(w, "aggro.returnMaxTicks", a.returnMaxTicks, 1, 72000);
    }

    /** A '#tag' under 'structures' belongs in 'tags'; a bare id under 'tags' gets its '#'. Fix rather than silently ignore. */
    private static void moveMisplacedKeys(InhabitantsConfig c, List<String> w) {
        Map<String, InhabitantsConfig.RuleOverride> fixedStructures = new LinkedHashMap<>();
        Map<String, InhabitantsConfig.RuleOverride> fixedTags = new LinkedHashMap<>();
        for (Map.Entry<String, InhabitantsConfig.RuleOverride> e : c.structures.entrySet()) {
            String key = IdMatcher.normalize(e.getKey());
            if (key == null) {
                w.add("structures: blank key ignored");
            } else if (IdMatcher.isTag(key)) {
                w.add("structures: '" + e.getKey() + "' is a tag; moved to 'tags'");
                fixedTags.putIfAbsent(key, e.getValue());
            } else {
                fixedStructures.put(key, e.getValue());
            }
        }
        for (Map.Entry<String, InhabitantsConfig.RuleOverride> e : c.tags.entrySet()) {
            String key = IdMatcher.normalize(e.getKey());
            if (key == null) {
                w.add("tags: blank key ignored");
                continue;
            }
            if (!IdMatcher.isTag(key)) {
                w.add("tags: '" + e.getKey() + "' should start with '#'; treated as '#" + key + "'");
                key = "#" + key;
            }
            fixedTags.put(key, e.getValue());
        }
        c.structures = fixedStructures;
        c.tags = fixedTags;
    }

    private static void validateOverrides(Map<String, InhabitantsConfig.RuleOverride> map, String section, List<String> w) {
        for (Map.Entry<String, InhabitantsConfig.RuleOverride> e : map.entrySet()) {
            InhabitantsConfig.RuleOverride o = e.getValue();
            if (o == null) {
                e.setValue(new InhabitantsConfig.RuleOverride());
                continue;
            }
            String where = section + "." + e.getKey();
            if (o.occupiedChance != null) {
                o.occupiedChance = clamp(w, where + ".occupiedChance", o.occupiedChance, 0.0, 1.0);
            }
            if (o.minBots != null) {
                o.minBots = clamp(w, where + ".minBots", o.minBots, 1, EffectiveRule.MAX_BOTS_PER_STRUCTURE);
            }
        }
    }

    private static String sanitizePrefix(String prefix, List<String> w) {
        // Delegates to the one canonical definition (util.BotNameShape) instead of restating the charset,
        // length and trailing-underscore rules here: this is exactly what used to drift from
        // engine.NameGenerator's own copy, so a prefix this method called "clean" was sometimes trimmed
        // further before actually being used.
        String cleaned = BotNameShape.sanitizePrefix(prefix);
        if (!cleaned.equals(prefix)) {
            w.add("spawning.namePrefix must be up to " + BotNameShape.MAX_PREFIX_LENGTH
                    + " letters/digits/underscores with no trailing underscore; using '" + cleaned + "'");
        }
        return cleaned;
    }

    private static int clamp(List<String> w, String name, int v, int lo, int hi) {
        if (v < lo || v > hi) {
            int fixed = Math.max(lo, Math.min(hi, v));
            w.add(name + " = " + v + " is outside " + lo + ".." + hi + "; using " + fixed);
            return fixed;
        }
        return v;
    }

    private static double clamp(List<String> w, String name, double v, double lo, double hi) {
        if (Double.isNaN(v) || v < lo || v > hi) {
            double fixed = Double.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
            w.add(name + " = " + v + " is outside " + lo + ".." + hi + "; using " + fixed);
            return fixed;
        }
        return v;
    }
}

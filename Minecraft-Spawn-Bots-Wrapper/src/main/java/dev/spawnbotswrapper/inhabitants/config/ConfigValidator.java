package dev.spawnbotswrapper.inhabitants.config;

import dev.spawnbotswrapper.inhabitants.util.BotNameShape;

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
        c.defaults.maxBots = clamp(w, "default.maxBots", c.defaults.maxBots, 1, EffectiveRule.MAX_BOTS_PER_STRUCTURE);
        if (c.defaults.maxBots < c.defaults.minBots) {
            w.add("default.maxBots (" + c.defaults.maxBots + ") < minBots (" + c.defaults.minBots + "); raised maxBots");
            c.defaults.maxBots = c.defaults.minBots;
        }

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
        p.initialDelayTicks = clamp(w, "processing.initialDelayTicks", p.initialDelayTicks, 0, 1200);
        p.retryIntervalTicks = clamp(w, "processing.retryIntervalTicks", p.retryIntervalTicks, 10, 2400);
        p.maxAttemptsPerStructure = clamp(w, "processing.maxAttemptsPerStructure", p.maxAttemptsPerStructure, 1, 1000);
        p.maxLiveBots = clamp(w, "processing.maxLiveBots", p.maxLiveBots, 0, 10000);
        p.appearTimeoutTicks = clamp(w, "processing.appearTimeoutTicks", p.appearTimeoutTicks, 20, 2400);
        p.saveIntervalTicks = clamp(w, "processing.saveIntervalTicks", p.saveIntervalTicks, 100, 72000);
        p.restoreSettleTicks = clamp(w, "processing.restoreSettleTicks", p.restoreSettleTicks, 0, 72000);
        p.goneConfirmTicks = clamp(w, "processing.goneConfirmTicks", p.goneConfirmTicks, 200, 1728000);

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
            if (o.maxBots != null) {
                o.maxBots = clamp(w, where + ".maxBots", o.maxBots, 1, EffectiveRule.MAX_BOTS_PER_STRUCTURE);
            }
            if (o.minBots != null && o.maxBots != null && o.maxBots < o.minBots) {
                w.add(where + ": maxBots < minBots; raised maxBots");
                o.maxBots = o.minBots;
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

package dev.spawnbotswrapper.inhabitants.config;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Turns the config into the {@link EffectiveRule} for one structure, and decides eligibility.
 * <p>
 * Precedence, applied to each of {@code occupiedChance} and {@code minBots} independently (so an
 * override may set only {@code occupiedChance} and inherit the minimum bot count):
 * <ol>
 *   <li>exact id in {@code structures}</li>
 *   <li>the first matching {@code tags} entry, in file order, that sets the value</li>
 *   <li>a {@code namespace:*} entry in {@code structures}</li>
 *   <li>{@code default}</li>
 * </ol>
 * The result is clamped to sane bounds (chance 0..1, at least one bot, at most {@link
 * EffectiveRule#MAX_BOTS_PER_STRUCTURE}). There is no {@code maxBots} to resolve here: see {@link EffectiveRule}.
 */
public final class RuleResolver {
    private RuleResolver() {
    }

    /** Whether this structure may be populated at all (include/exclude filtering). */
    public static boolean isEligible(InhabitantsConfig cfg, String structureId, Set<String> tagIds) {
        if (IdMatcher.matchesAny(cfg.exclude, structureId, tagIds)) {
            return false;
        }
        if (cfg.include == null || cfg.include.isEmpty()) {
            return true;
        }
        return IdMatcher.matchesAny(cfg.include, structureId, tagIds);
    }

    /** Whether structures are processed in this dimension. */
    public static boolean isDimensionEligible(InhabitantsConfig cfg, String dimensionId) {
        InhabitantsConfig.Dimensions d = cfg.dimensions;
        if (d == null) {
            return true;
        }
        if (IdMatcher.matchesAny(d.exclude, dimensionId, Set.of())) {
            return false;
        }
        return d.include == null || d.include.isEmpty() || IdMatcher.matchesAny(d.include, dimensionId, Set.of());
    }

    public static EffectiveRule resolve(InhabitantsConfig cfg, String structureId, Set<String> tagIds) {
        InhabitantsConfig.Rule base = cfg.defaults != null ? cfg.defaults : new InhabitantsConfig.Rule();

        Resolved<Double> chance = pick(cfg, structureId, tagIds, o -> o.occupiedChance, base.occupiedChance);
        Resolved<Integer> min = pick(cfg, structureId, tagIds, o -> o.minBots, base.minBots);

        double p = chance.value;
        if (Double.isNaN(p)) {
            p = 0;
        }
        p = Math.max(0.0, Math.min(1.0, p));
        int lo = Math.max(1, Math.min(EffectiveRule.MAX_BOTS_PER_STRUCTURE, min.value));
        return new EffectiveRule(p, lo, chance.from, min.from);
    }

    private record Resolved<T>(T value, String from) {
    }

    private static <T> Resolved<T> pick(InhabitantsConfig cfg, String structureId, Set<String> tagIds,
                                        Function<InhabitantsConfig.RuleOverride, T> field, T fallback) {
        Map<String, InhabitantsConfig.RuleOverride> structures = cfg.structures;
        Map<String, InhabitantsConfig.RuleOverride> tags = cfg.tags;

        // 1. exact id
        if (structures != null) {
            for (Map.Entry<String, InhabitantsConfig.RuleOverride> e : structures.entrySet()) {
                String key = IdMatcher.normalize(e.getKey());
                if (key != null && !IdMatcher.isTag(key) && !IdMatcher.isNamespaceWildcard(key)
                        && !key.equals(IdMatcher.ANY) && key.equals(structureId)) {
                    T v = e.getValue() == null ? null : field.apply(e.getValue());
                    if (v != null) {
                        return new Resolved<>(v, "structure " + key);
                    }
                }
            }
        }
        // 2. tags, file order
        if (tags != null && tagIds != null && !tagIds.isEmpty()) {
            for (Map.Entry<String, InhabitantsConfig.RuleOverride> e : tags.entrySet()) {
                String key = IdMatcher.normalize(e.getKey());
                if (key != null && IdMatcher.isTag(key) && tagIds.contains(key.substring(1))) {
                    T v = e.getValue() == null ? null : field.apply(e.getValue());
                    if (v != null) {
                        return new Resolved<>(v, "tag " + key);
                    }
                }
            }
        }
        // 3. namespace wildcard
        if (structures != null) {
            String ns = IdMatcher.namespaceOf(structureId);
            for (Map.Entry<String, InhabitantsConfig.RuleOverride> e : structures.entrySet()) {
                String key = IdMatcher.normalize(e.getKey());
                if (key != null && IdMatcher.isNamespaceWildcard(key) && ns.equals(IdMatcher.wildcardNamespace(key))) {
                    T v = e.getValue() == null ? null : field.apply(e.getValue());
                    if (v != null) {
                        return new Resolved<>(v, "namespace " + key);
                    }
                }
            }
        }
        return new Resolved<>(fallback, "default");
    }
}

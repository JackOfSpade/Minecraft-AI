package io.github.zoyluo.minecraftai.mining.assist;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Accumulates the entity part of POI evidence (mining-assist design 6.2) from already-observable
 * entity type ids. Pure: the adapter decides which entities the bot may perceive, this class only
 * classifies their ids through {@link PoiLexicon}.
 *
 * <p>Scoring follows the design: per-entity vanilla scores add up, any non-{@code minecraft}
 * namespace adds {@link PoiLexicon#ENTITY_SCORE_MODDED} once for the whole group, and the scorer applies
 * the group cap ({@link PoiLexicon#ENTITY_GROUP_CAP}), so {@link #rawScore()} is deliberately uncapped.
 * Item frames and armor stands also feed the habitation set.</p>
 */
public final class EntityEvidence {
    private double vanillaSum;
    private boolean modded;
    private int counted;
    private final Set<PoiSignals.Habitation> habitation = EnumSet.noneOf(PoiSignals.Habitation.class);

    /** Adds one perceivable entity. Returns true when it contributed score or habitation evidence. */
    public boolean add(String namespace, String path) {
        boolean contributed = false;
        String key = PoiLexicon.habitationKey(namespace, path);
        if (key != null) {
            try {
                habitation.add(PoiSignals.Habitation.valueOf(key.toUpperCase(Locale.ROOT)));
                contributed = true;
            } catch (IllegalArgumentException ignored) {
                // Not a habitation constant of the scorer; the score below still applies.
            }
        }
        double score = PoiLexicon.entityScore(namespace, path);
        if (score > 0.0D) {
            if (AssistRules.isVanilla(namespace)) {
                vanillaSum += score;
            } else {
                modded = true;
            }
            contributed = true;
        }
        if (contributed) {
            counted++;
        }
        return contributed;
    }

    /** Vanilla scores summed plus one modded bonus, not yet capped (the scorer caps the group). */
    public double rawScore() {
        return vanillaSum + (modded ? PoiLexicon.ENTITY_SCORE_MODDED : 0.0D);
    }

    public Set<PoiSignals.Habitation> habitation() {
        return Collections.unmodifiableSet(habitation);
    }

    /** Number of entities that contributed. */
    public int count() {
        return counted;
    }

    public boolean isEmpty() {
        return counted == 0;
    }
}

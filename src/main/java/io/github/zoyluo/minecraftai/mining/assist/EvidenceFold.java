package io.github.zoyluo.minecraftai.mining.assist;

import java.util.function.LongPredicate;
import net.minecraft.core.BlockPos;

/**
 * Folds one observed block into the per-bot memories (mining-assist design 3.3 steps 2 to 4):
 * valuables into the {@link SightingLedger}, lava, water and traps into the {@link HazardField}, and
 * non-natural cells into the {@link PoiEvidenceWindow}. Pure: the caller has already proved the cell was
 * observed and supplies the block facts, so this class reads no world and asks no capability.
 *
 * <p>A cell that is re-observed as something harmless also clears the old memory of it: a hazard cell
 * that is now a plain block is removed, a valuable that is now something else is marked gone, a POI cell
 * that is now natural is forgotten. Bot-placed cells never become POI evidence (the placed ledger is
 * consulted through {@code placedByBot}, and only for cells that would otherwise be evidence).</p>
 */
public final class EvidenceFold {
    /** Bit set in the result of {@link #foldHit}: a valuable position new to the ledger. */
    public static final int NEW_SIGHTING = 1;
    /** Bit set in the result of {@link #foldHit}: a hazard cell new to the field, or of a new kind. */
    public static final int NEW_HAZARD = 1 << 1;
    /** Bit set in the result of {@link #foldHit}: a POI cell new to the window. */
    public static final int NEW_POI = 1 << 2;

    private EvidenceFold() {
    }

    /**
     * Folds a first hit that read the cell's real state (a COLLIDER ray hit or a break-peek neighbour).
     *
     * @param fluid the hit state's hazardous fluid (LAVA or WATER), or null when it holds none
     * @param placedByBot answers "did the bot place the block at this packed position?"
     * @return a bitmask of {@link #NEW_SIGHTING}, {@link #NEW_HAZARD}, {@link #NEW_POI}
     */
    public static int foldHit(MiningAssistState state, BlockPos pos, BlockFacts facts,
                              HazardField.Kind fluid, LongPredicate placedByBot, int tick) {
        int result = 0;
        SenseCounters counters = state.counters();

        HazardField hazards = state.hazards();
        HazardField.Kind hazard = fluid != null ? fluid : facts.trap() ? HazardField.Kind.TRAP : null;
        if (hazard != null) {
            if (hazards.observe(pos, hazard, tick)) {
                result |= NEW_HAZARD;
                switch (hazard) {
                    case LAVA -> counters.lavaCells++;
                    case WATER -> counters.waterCells++;
                    case TRAP -> counters.trapCells++;
                }
            }
        } else if (!hazards.isEmpty()) {
            hazards.observeClear(pos, tick);
        }

        SightingLedger sightings = state.sightings();
        if (facts.valuable()) {
            switch (sightings.observe(pos, facts.ledgerId(), facts.rawValue(), tick)) {
                case ADDED -> {
                    counters.sightingsAdded++;
                    counters.newSightingMaxValue = Math.max(counters.newSightingMaxValue, facts.rawValue());
                    result |= NEW_SIGHTING;
                }
                case UPDATED -> counters.sightingsUpdated++;
                case REJECTED -> counters.sightingsRejected++;
            }
        } else if (!sightings.isEmpty()) {
            sightings.markGone(pos);
        }

        PoiEvidenceWindow window = state.poiWindow();
        if (facts.evidence()) {
            if (placedByBot.test(pos.asLong())) {
                window.remove(pos);
            } else if (window.observe(pos, facts.bucket(), facts.poiFlags(), tick, false)) {
                counters.poiCellsAdded++;
                result |= NEW_POI;
            }
        } else if (!window.isEmpty()) {
            window.remove(pos);
        }
        return result;
    }

    /**
     * Folds the first hit of an OUTLINE (decor) re-cast, which feeds POI evidence only (design 3.3
     * step 5): rails, cobweb, torches, banners and sculk veins that a collider ray passes through. The
     * hit is never a reason to clear anything, since the outline pass does not read what is behind it.
     *
     * @return true when the cell was new to the POI window
     */
    public static boolean foldDecor(MiningAssistState state, BlockPos pos, BlockFacts facts,
                                    LongPredicate placedByBot, int tick) {
        if (!facts.evidence()) {
            return false;
        }
        if (placedByBot.test(pos.asLong())) {
            state.poiWindow().remove(pos);
            return false;
        }
        boolean added = state.poiWindow().observe(pos, facts.bucket(), facts.poiFlags(), tick, true);
        if (added) {
            state.counters().poiCellsAdded++;
            state.counters().decorEvidence++;
        }
        return added;
    }
}

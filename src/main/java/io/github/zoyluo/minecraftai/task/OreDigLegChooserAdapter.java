package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mining.assist.CoverageGrid;
import io.github.zoyluo.minecraftai.mining.assist.HazardField;
import io.github.zoyluo.minecraftai.mining.assist.LegChooser;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistRegistry;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistState;
import io.github.zoyluo.minecraftai.mining.assist.PoiRegistry;
import io.github.zoyluo.minecraftai.mining.assist.SightingLedger;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

// ---------------------------------------------------------------------------------------------------------
// Mining assist P4 (design 5.3): L1 LegChooser adapter. Off by default (explore.legChooser); every method
// below only ever runs from OreDigTask.publishStripSuccessor's legChooserActive branch. LegChooser itself
// is a pure kernel (mining/assist/LegChooser.java) -- everything here just measures the world into its four
// signals.
// ---------------------------------------------------------------------------------------------------------
final class OreDigLegChooserAdapter {

    private static final int LEG_CHOOSER_Y_BAND = 2;
    private static final int LEG_CHOOSER_HALF_WIDTH = 6;
    private static final int LEG_CHOOSER_OPEN_BIAS_RAYS = 9;
    private static final double LEG_CHOOSER_OPEN_BIAS_MIN_LENGTH = 8.0D;
    private static final int LEG_CHOOSER_HAZARD_RADIUS = 4;
    private static final int LEG_CHOOSER_HAZARD_SAMPLE_STEP = 12;
    private static final double LEG_CHOOSER_ZONE_MAX_DIST = 128.0D;
    private static final int LEG_CHOOSER_ZONE_MIN_POINTS = 3;
    private static final int LEG_CHOOSER_ZONE_RADIUS = 24;

    /** The chosen absolute {@code STRIP_DIRS} index and the leg length to publish with it. */
    record StripTurnChoice(int dirIndex, int legLength) {
    }

    private final Set<Block> targetOres;
    private final CoverageGrid stripCoverage;

    OreDigLegChooserAdapter(Set<Block> targetOres, CoverageGrid stripCoverage) {
        this.targetOres = targetOres;
        this.stripCoverage = stripCoverage;
    }

    /** Design 5.3 "Initial direction": scores the 4 cardinals for a mission's very first leg. */
    int chooseInitialDirection(AIPlayerEntity bot) {
        LegChooser.DirectionSignal north = stripDirectionSignal(bot, 0, OreDigTask.STRIP_SEGMENT);
        LegChooser.DirectionSignal east = stripDirectionSignal(bot, 1, OreDigTask.STRIP_SEGMENT);
        LegChooser.DirectionSignal south = stripDirectionSignal(bot, 2, OreDigTask.STRIP_SEGMENT);
        LegChooser.DirectionSignal west = stripDirectionSignal(bot, 3, OreDigTask.STRIP_SEGMENT);
        return LegChooser.chooseInitialDirection(north, east, south, west);
    }

    /**
     * Design 5.3's turn choice: clockwise (today's default) versus the one counter-clockwise
     * alternative, both measured at {@code defaultLegLength} (the length the unmodified schedule
     * would use this leg). An override also recomputes the length from the chosen direction's own
     * freshness; an unoverridden turn keeps {@code defaultLegLength} untouched.
     */
    StripTurnChoice chooseTurn(AIPlayerEntity bot, int currentDirIndex, int defaultLegLength) {
        int clockwiseDir = (currentDirIndex + 1) % OreDigTask.STRIP_DIRS.length;
        int counterClockwiseDir = (currentDirIndex + OreDigTask.STRIP_DIRS.length - 1) % OreDigTask.STRIP_DIRS.length;
        LegChooser.DirectionSignal clockwise = stripDirectionSignal(bot, clockwiseDir, defaultLegLength);
        LegChooser.DirectionSignal counterClockwise = stripDirectionSignal(bot, counterClockwiseDir, defaultLegLength);
        LegChooser.TurnChoice turn = LegChooser.chooseTurn(clockwise, counterClockwise);
        if (!turn.overridden()) {
            return new StripTurnChoice(clockwiseDir, defaultLegLength);
        }
        double chosenFresh = turn.dirIndex() == clockwiseDir ? clockwise.freshFraction() : counterClockwise.freshFraction();
        return new StripTurnChoice(turn.dirIndex(), LegChooser.lengthForFreshFraction(chosenFresh));
    }

    /** Measures the four design-5.3 signals for one candidate absolute {@code STRIP_DIRS} index. */
    private LegChooser.DirectionSignal stripDirectionSignal(AIPlayerEntity bot, int dirIndex, int corridorLength) {
        Direction direction = OreDigTask.STRIP_DIRS[dirIndex];
        BlockPos origin = bot.blockPosition();
        double freshFraction = stripCoverage.freshFraction(
                origin, direction, corridorLength, LEG_CHOOSER_Y_BAND, LEG_CHOOSER_HALF_WIDTH);
        double openBias = stripOpenBias(bot, direction);
        double zoneAttraction = stripZoneAttraction(bot, origin, direction);
        double hazardProximity = stripHazardProximity(bot, origin, direction, corridorLength);
        return new LegChooser.DirectionSignal(dirIndex, freshFraction, openBias, zoneAttraction, hazardProximity);
    }

    /**
     * "The fraction of ring rays in the candidate's &plusmn;45&deg; sector with free length at least
     * 8" (design 5.3): {@link #LEG_CHOOSER_OPEN_BIAS_RAYS} evenly spaced horizontal rays across that
     * sector, cast fresh from the bot's own eye (the same honest view {@code ObservableWorldQuery}
     * gives every other sensor). A ray whose end chunk is not loaded proves nothing and is skipped
     * from both the numerator and the denominator, never counted as open or as blocked.
     */
    private double stripOpenBias(AIPlayerEntity bot, Direction direction) {
        double baseAngle = Math.atan2(direction.getStepX(), direction.getStepZ());
        int sampled = 0;
        int free = 0;
        for (int i = 0; i < LEG_CHOOSER_OPEN_BIAS_RAYS; i++) {
            double offsetDeg = -45.0D + (90.0D * i) / (LEG_CHOOSER_OPEN_BIAS_RAYS - 1);
            double angle = baseAngle + Math.toRadians(offsetDeg);
            double dx = Math.sin(angle);
            double dz = Math.cos(angle);
            ObservableWorldQuery.ViewHit hit = ObservableWorldQuery.castViewRay(bot, dx, 0.0D, dz,
                    LEG_CHOOSER_OPEN_BIAS_MIN_LENGTH * 2.0D, ObservableWorldQuery.ViewShape.COLLIDER);
            if (hit.isUnknown()) {
                continue;
            }
            sampled++;
            if (!hit.hit() || hit.distance() >= LEG_CHOOSER_OPEN_BIAS_MIN_LENGTH) {
                free++;
            }
        }
        return sampled == 0 ? 0.0D : (double) free / sampled;
    }

    /**
     * "Bearing to a KnowledgeBase rich zone or sighting, weighted by 1/distance" (design 5.3): the
     * nearer of this mission's known rich zones (one per target ore, same call the barren-scan
     * branch above already makes) and this bot's nearest live sighting, scored by how well {@code
     * direction} points toward it and discounted by 1/distance. Zero with no known zone or sighting,
     * or when it sits exactly on top of the bot (no bearing to measure).
     */
    private double stripZoneAttraction(AIPlayerEntity bot, BlockPos origin, Direction direction) {
        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (Block oreBlock : targetOres) {
            String oreId = BuiltInRegistries.BLOCK.getKey(oreBlock).toString();
            var zone = io.github.zoyluo.minecraftai.memory.KnowledgeBase.INSTANCE.richZoneNear(
                    bot.getUUID(), oreId, origin, LEG_CHOOSER_ZONE_MAX_DIST,
                    LEG_CHOOSER_ZONE_MIN_POINTS, LEG_CHOOSER_ZONE_RADIUS);
            if (zone.isPresent()) {
                double distSq = origin.distSqr(zone.get());
                if (distSq < nearestDistSq) {
                    nearestDistSq = distSq;
                    nearest = zone.get();
                }
            }
        }
        MiningAssistState state = MiningAssistRegistry.getIfPresent(bot.getUUID());
        if (state != null) {
            for (SightingLedger.Sighting sighting : state.sightings().nearestTo(origin, 1)) {
                double distSq = origin.distSqr(sighting.pos());
                if (distSq < nearestDistSq) {
                    nearestDistSq = distSq;
                    nearest = sighting.pos();
                }
            }
        }
        if (nearest == null) {
            return 0.0D;
        }
        double dx = nearest.getX() - origin.getX();
        double dz = nearest.getZ() - origin.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDist < 1.0e-6D) {
            return 0.0D;
        }
        double alignment = (dx * direction.getStepX() + dz * direction.getStepZ()) / horizontalDist;
        if (alignment <= 0.0D) {
            return 0.0D;
        }
        double distance = Math.max(1.0D, Math.sqrt(nearestDistSq));
        return alignment / distance;
    }

    /**
     * "HazardField cells or acknowledged POI regions within 4 of the corridor" (design 5.3): samples
     * every {@link #LEG_CHOOSER_HAZARD_SAMPLE_STEP} blocks along the candidate corridor and reports
     * the fraction of samples within {@link #LEG_CHOOSER_HAZARD_RADIUS} of a remembered lava, water
     * or trap cell, or of a POI this mission already recorded (open, held or resolved -- any entry
     * this bot has already acknowledged, so L1 steers around it the same way a live hold would).
     */
    private double stripHazardProximity(AIPlayerEntity bot, BlockPos origin, Direction direction, int corridorLength) {
        MiningAssistState state = MiningAssistRegistry.getIfPresent(bot.getUUID());
        HazardField hazards = state == null ? null : state.hazards();
        List<PoiRegistry.Entry> pois = PoiRegistry.snapshot(bot.getUUID());
        int step = Math.max(1, LEG_CHOOSER_HAZARD_SAMPLE_STEP);
        int sampled = 0;
        int near = 0;
        for (int along = 0; along <= corridorLength; along += step) {
            BlockPos sample = origin.relative(direction, along);
            sampled++;
            boolean hazardous = hazards != null
                    && (hazards.anyWithin(HazardField.Kind.LAVA, sample, LEG_CHOOSER_HAZARD_RADIUS)
                    || hazards.anyWithin(HazardField.Kind.WATER, sample, LEG_CHOOSER_HAZARD_RADIUS)
                    || hazards.anyWithin(HazardField.Kind.TRAP, sample, LEG_CHOOSER_HAZARD_RADIUS));
            if (!hazardous) {
                for (PoiRegistry.Entry entry : pois) {
                    if (entry.anchor().closerThan(sample, LEG_CHOOSER_HAZARD_RADIUS)) {
                        hazardous = true;
                        break;
                    }
                }
            }
            if (hazardous) {
                near++;
            }
        }
        return sampled == 0 ? 0.0D : (double) near / sampled;
    }
}

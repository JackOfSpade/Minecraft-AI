package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.util.RangedDistances;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which of the managed PvP BOT settings have to change, given what PvP BOT currently has. Pure: no upstream
 * access, so every rule is unit tested. The writing is {@link UpstreamSettingsWriter}'s job.
 * <p>
 * Rules: {@code maxTargetDistance} must be within {@value #MIN_TARGET_DISTANCE}..{@value #MAX_TARGET_DISTANCE}; the
 * ranged distances (judged with the values PvP BOT has for the ones that are not managed) must satisfy
 * {@link RangedDistances#problem}, otherwise none of the ranged keys is applied and a warning says why (the other
 * settings are unaffected); {@code meleeRange} must be within {@value #MIN_MELEE_RANGE}..{@value #MAX_MELEE_RANGE}
 * (PvP BOT's own clamp).
 */
final class SettingsPolicy {
    static final double MIN_TARGET_DISTANCE = 4.0;
    static final double MAX_TARGET_DISTANCE = 128.0;
    /** PvP BOT's own setter clamp for the melee range. */
    static final double MIN_MELEE_RANGE = 2.0;
    static final double MAX_MELEE_RANGE = 6.0;
    private static final double EPSILON = 1e-9;

    private SettingsPolicy() {
    }

    /** What PvP BOT has right now; a null component could not be read. */
    record Current(Double maxTargetDistance, Double rangedMinRange, Double rangedOptimalRange, Double rangedMaxRange,
                   Boolean autoEquipWeapon, Boolean autoTargetEnabled, Boolean rangedRetreatOnClose,
                   Double meleeRange, Integer bowMinDrawTime) {
        Current(Double maxTargetDistance, Double rangedMinRange, Double rangedOptimalRange, Double rangedMaxRange,
                Boolean autoEquipWeapon, Boolean autoTargetEnabled) {
            this(maxTargetDistance, rangedMinRange, rangedOptimalRange, rangedMaxRange, autoEquipWeapon,
                    autoTargetEnabled, null, null, null);
        }

        Current(Double maxTargetDistance, Double rangedMinRange, Double rangedOptimalRange, Double rangedMaxRange,
                Boolean autoEquipWeapon, Boolean autoTargetEnabled, Boolean rangedRetreatOnClose) {
            this(maxTargetDistance, rangedMinRange, rangedOptimalRange, rangedMaxRange, autoEquipWeapon,
                    autoTargetEnabled, rangedRetreatOnClose, null, null);
        }

        Current(Double maxTargetDistance, Double rangedMinRange, Double rangedOptimalRange, Double rangedMaxRange,
                Boolean autoEquipWeapon, Boolean autoTargetEnabled, Boolean rangedRetreatOnClose, Double meleeRange) {
            this(maxTargetDistance, rangedMinRange, rangedOptimalRange, rangedMaxRange, autoEquipWeapon,
                    autoTargetEnabled, rangedRetreatOnClose, meleeRange, null);
        }
    }

    /** One setting to change; {@code from} is null when the current value could not be read. */
    record Change(String name, Object from, Object to) {
        String text() {
            return name + " " + (from == null ? "?" : show(from)) + " -> " + show(to);
        }
    }

    record Plan(List<Change> changes, List<String> warnings) {
        Plan {
            changes = List.copyOf(changes);
            warnings = List.copyOf(warnings);
        }

        /** "maxTargetDistance 16 -> 10, autoEquipWeapon true -> false", or null when nothing changes. */
        String summary() {
            if (changes.isEmpty()) {
                return null;
            }
            List<String> parts = new ArrayList<>();
            for (Change c : changes) {
                parts.add(c.text());
            }
            return String.join(", ", parts);
        }
    }

    static Plan plan(ManagedSettings wanted, Current current) {
        List<Change> changes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (wanted == null || wanted.isEmpty()) {
            return new Plan(changes, warnings);
        }
        Double targetDistance = wanted.maxTargetDistance();
        if (targetDistance != null && !(targetDistance >= MIN_TARGET_DISTANCE && targetDistance <= MAX_TARGET_DISTANCE)) {
            warnings.add("maxTargetDistance " + RangedDistances.text(targetDistance) + " is outside "
                    + RangedDistances.text(MIN_TARGET_DISTANCE) + ".." + RangedDistances.text(MAX_TARGET_DISTANCE)
                    + "; it is not applied");
            targetDistance = null;
        }
        Double effectiveTarget = targetDistance != null ? targetDistance : current.maxTargetDistance();

        boolean rangedManaged = wanted.rangedMinRange() != null || wanted.rangedOptimalRange() != null
                || wanted.rangedMaxRange() != null;
        Double min = wanted.rangedMinRange() != null ? wanted.rangedMinRange() : current.rangedMinRange();
        Double optimal = wanted.rangedOptimalRange() != null ? wanted.rangedOptimalRange() : current.rangedOptimalRange();
        Double max = wanted.rangedMaxRange() != null ? wanted.rangedMaxRange() : current.rangedMaxRange();
        if (rangedManaged) {
            String problem = RangedDistances.problem(min, optimal, max, effectiveTarget);
            if (problem != null) {
                warnings.add("the ranged distances are not applied: " + problem + " (with PvP BOT's own values for the "
                        + "ones this addon does not manage)");
                rangedManaged = false;
            }
        }

        addDouble(changes, "maxTargetDistance", current.maxTargetDistance(), targetDistance);
        if (rangedManaged) {
            addDouble(changes, "rangedMinRange", current.rangedMinRange(), wanted.rangedMinRange());
            addDouble(changes, "rangedOptimalRange", current.rangedOptimalRange(), wanted.rangedOptimalRange());
            addDouble(changes, "rangedMaxRange", current.rangedMaxRange(), wanted.rangedMaxRange());
        }
        if (wanted.autoEquipWeapon() != null && !wanted.autoEquipWeapon().equals(current.autoEquipWeapon())) {
            changes.add(new Change("autoEquipWeapon", current.autoEquipWeapon(), wanted.autoEquipWeapon()));
        }
        if (wanted.autoTargetEnabled() != null && !wanted.autoTargetEnabled().equals(current.autoTargetEnabled())) {
            changes.add(new Change("autoTargetEnabled", current.autoTargetEnabled(), wanted.autoTargetEnabled()));
        }
        if (wanted.rangedRetreatOnClose() != null && !wanted.rangedRetreatOnClose().equals(current.rangedRetreatOnClose())) {
            changes.add(new Change("rangedRetreatOnClose", current.rangedRetreatOnClose(), wanted.rangedRetreatOnClose()));
        }
        Double melee = wanted.meleeRange();
        if (melee != null && !(melee >= MIN_MELEE_RANGE && melee <= MAX_MELEE_RANGE)) {
            warnings.add("meleeRange " + RangedDistances.text(melee) + " is outside " + RangedDistances.text(MIN_MELEE_RANGE)
                    + ".." + RangedDistances.text(MAX_MELEE_RANGE) + "; it is not applied");
            melee = null;
        }
        addDouble(changes, "meleeRange", current.meleeRange(), melee);
        if (wanted.bowMinDrawTime() != null && !wanted.bowMinDrawTime().equals(current.bowMinDrawTime())) {
            changes.add(new Change("bowMinDrawTime", current.bowMinDrawTime(), wanted.bowMinDrawTime()));
        }
        return new Plan(changes, warnings);
    }

    private static void addDouble(List<Change> changes, String name, Double current, Double wanted) {
        if (wanted != null && (current == null || Math.abs(current - wanted) > EPSILON)) {
            changes.add(new Change(name, current, wanted));
        }
    }

    private static String show(Object value) {
        return value instanceof Double d ? RangedDistances.text(d) : String.valueOf(value);
    }
}

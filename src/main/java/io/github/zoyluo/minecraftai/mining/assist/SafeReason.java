package io.github.zoyluo.minecraftai.mining.assist;

import java.util.Locale;

/**
 * Why the detour SAFE gate said no (mining-assist design 4.4). {@link SafeGate#evaluate} returns the
 * <b>first</b> failing reason in the declaration order of this enum, so the order below is the contract: the
 * numbers are the design's item numbers, cheap to expensive.
 *
 * <ol>
 *   <li>mode, origin, audit session</li>
 *   <li>TPS and tick headroom</li>
 *   <li>hp, hurt, fire, lava, water, food</li>
 *   <li>water rescue, paused stack, user pause, safety origin</li>
 *   <li>threat cooldown, shelter episode</li>
 *   <li>observable hostile pressure</li>
 *   <li>lava in the threat box, remembered lava near the bot, the stand pose or the valuable</li>
 *   <li>deep dark biome</li>
 *   <li>POI evidence</li>
 *   <li>remembered trap near the bot, the stand pose or the valuable</li>
 * </ol>
 *
 * <p>{@link #abortReason()} maps a reason to the abort reason string of the failure matrix (design 4.12), which
 * is what the engine logs and passes to its abort.</p>
 */
public enum SafeReason {
    /** The gate is open. Not a failure. */
    OK(0),
    MODE(1),
    ORIGIN(1),
    AUDIT(1),
    TPS(2),
    HEADROOM(2),
    HP(3),
    HURT(3),
    ON_FIRE(3),
    IN_LAVA(3),
    SUBMERGED(3),
    TOUCHING_WATER(3),
    FOOD(3),
    WATER_RESCUE(4),
    PAUSED(4),
    USER_PAUSED(4),
    ORIGIN_SAFETY(4),
    THREAT_COOLDOWN(5),
    SHELTER_EPISODE(5),
    HOSTILE_PRESSURE(6),
    LAVA_THREAT_BOX(7),
    HAZARD_LAVA(7),
    POI_EVIDENCE(9),
    TRAP_SPOT(10);

    private final int item;

    SafeReason(int item) {
        this.item = item;
    }

    /** The design item number 1..10 this reason belongs to; 0 for {@link #OK}. */
    public int item() {
        return item;
    }

    public boolean ok() {
        return this == OK;
    }

    /**
     * The abort reason string of design 4.12: {@code degraded_tps} for TPS and HEADROOM, {@code paused} for
     * PAUSED and USER_PAUSED, {@code deep_dark_biome}, {@code poi_evidence} and {@code trap_spot} as named,
     * and {@code safety_<lower case name>} for everything else. {@link #OK} maps to the empty string.
     */
    public String abortReason() {
        return switch (this) {
            case OK -> "";
            case TPS, HEADROOM -> "degraded_tps";
            case PAUSED, USER_PAUSED -> "paused";
            case POI_EVIDENCE -> "poi_evidence";
            case TRAP_SPOT -> "trap_spot";
            default -> "safety_" + name().toLowerCase(Locale.ROOT);
        };
    }
}

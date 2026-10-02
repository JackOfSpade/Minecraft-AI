package io.github.zoyluo.minecraftai.mode;

/** Fine-grained operator-mode switches. Boxed fields preserve explicit false through Gson defaults. */
public record OperatorCapabilities(
        Boolean hiddenBlockScan,
        Boolean emergencyTeleport,
        Boolean manualTeleport
) {
    public static OperatorCapabilities defaults() {
        // Keep hiddenBlockScan in the record only so older minecraftai.json files parse without
        // disruption.  The no-cheat capability was retired, so it defaults off and policy denies
        // it even if an old configuration still spells it true.
        return new OperatorCapabilities(false, true, true);
    }

    public static OperatorCapabilities none() {
        return new OperatorCapabilities(false, false, false);
    }

    public OperatorCapabilities withDefaults(OperatorCapabilities defaults) {
        OperatorCapabilities fallback = defaults == null ? defaults() : defaults;
        return new OperatorCapabilities(
                booleanOrDefault(hiddenBlockScan, fallback.hiddenBlockScan),
                booleanOrDefault(emergencyTeleport, fallback.emergencyTeleport),
                booleanOrDefault(manualTeleport, fallback.manualTeleport));
    }

    public boolean enabled(PrivilegedCapability capability) {
        return switch (capability) {
            case HIDDEN_BLOCK_SCAN -> false;
            case EMERGENCY_TELEPORT -> Boolean.TRUE.equals(emergencyTeleport);
            case MANUAL_TELEPORT -> Boolean.TRUE.equals(manualTeleport);
        };
    }

    private static Boolean booleanOrDefault(Boolean value, Boolean fallback) {
        return value == null ? fallback : value;
    }
}

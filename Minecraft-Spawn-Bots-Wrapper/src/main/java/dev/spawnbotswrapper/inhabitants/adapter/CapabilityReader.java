package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities;

import java.util.List;

/**
 * Builds a {@link GlobalCapabilities} snapshot from PvP BOT's settings singleton by calling its GETTERS.
 * READ-ONLY by construction: the contract probe never resolves a setter, {@code load} or {@code save}, so
 * there is nothing here that could write to the operator's per-world settings file.
 * <p>
 * Each getter is optional. One that is missing (upstream renamed or removed the setting), throws, or
 * returns something unexpected falls back to PvP BOT's shipped default for that one switch, so a single
 * upstream change never blinds the addon to the rest.
 */
final class CapabilityReader {

    private CapabilityReader() {
    }

    /** The canonical order of {@link GlobalCapabilities}' components. */
    private static final List<String> ORDER = UpstreamContract.CAPABILITY_GETTERS;

    static GlobalCapabilities read(UpstreamCalls calls, GlobalCapabilities defaults, Diagnostics log)
            throws Throwable {
        Object settings = calls.settingsInstance();
        if (settings == null) {
            return defaults;
        }
        boolean[] fallback = {
                defaults.autoEquipArmor(), defaults.autoEquipWeapon(), defaults.combatEnabled(),
                defaults.autoTargetEnabled(), defaults.rangedEnabled(), defaults.maceEnabled(),
                defaults.spearEnabled(), defaults.crystalPvpEnabled(), defaults.anchorPvpEnabled(),
                defaults.cobwebEnabled(), defaults.autoTotemEnabled(), defaults.autoShieldEnabled(),
                defaults.autoEatEnabled(), defaults.autoPotionEnabled(), defaults.autoMendEnabled(),
                defaults.shieldBreakEnabled(), defaults.retreatEnabled(), defaults.botsRelogs(),
                defaults.botLeaveOnDeath(), defaults.clearOnRemove(), defaults.totemPriority(),
                defaults.preferSword(), defaults.rangedRetreatOnClose()};
        boolean[] v = new boolean[ORDER.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = readOne(calls, settings, ORDER.get(i), fallback[i], log);
        }
        return new GlobalCapabilities(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11],
                v[12], v[13], v[14], v[15], v[16], v[17], v[18], v[19], v[20], v[21], v[22]);
    }

    private static boolean readOne(UpstreamCalls calls, Object settings, String getter, boolean fallback,
                                   Diagnostics log) {
        try {
            Boolean value = calls.readBoolean(settings, getter);
            return value != null ? value : fallback;
        } catch (Throwable t) {
            log.failure("reading the PvP BOT setting " + getter, t);
            return fallback;
        }
    }
}

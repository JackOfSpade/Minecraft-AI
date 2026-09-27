package dev.spawnbotswrapper.inhabitants.profile;

/**
 * Read-only snapshot of the PvP BOT GLOBAL settings that decide whether a behaviour a loadout could
 * enable is switched on at all. The profile generator consults it so it does not hand out items whose
 * behaviour the server has globally disabled (a mace with {@code mace=false} is just dead weight), and
 * so the debug output can explain why a bot does not use something it carries.
 * <p>
 * The addon NEVER writes these. Values that cannot be read fall back to PvP BOT's shipped defaults
 * ({@link #upstreamDefaults()}).
 */
public record GlobalCapabilities(
        boolean autoEquipArmor,
        boolean autoEquipWeapon,
        boolean combatEnabled,
        boolean autoTargetEnabled,
        boolean rangedEnabled,
        boolean maceEnabled,
        boolean spearEnabled,
        boolean crystalPvpEnabled,
        boolean anchorPvpEnabled,
        boolean cobwebEnabled,
        boolean autoTotemEnabled,
        boolean autoShieldEnabled,
        boolean autoEatEnabled,
        boolean autoPotionEnabled,
        boolean autoMendEnabled,
        boolean shieldBreakEnabled,
        boolean retreatEnabled,
        boolean botsRelogs,
        boolean botLeaveOnDeath,
        boolean clearOnRemove,
        boolean totemPriority,
        boolean preferSword,
        boolean rangedRetreatOnClose) {

    /** PvP BOT v0.0.15 factory defaults (BotSettings field initialisers). */
    public static GlobalCapabilities upstreamDefaults() {
        return new GlobalCapabilities(
                true,   // autoEquipArmor
                true,   // autoEquipWeapon
                true,   // combatEnabled
                false,  // autoTargetEnabled  (bots are passive until attacked!)
                true,   // rangedEnabled
                true,   // maceEnabled
                false,  // spearEnabled
                true,   // crystalPvpEnabled
                true,   // anchorPvpEnabled
                true,   // cobwebEnabled
                true,   // autoTotemEnabled
                true,   // autoShieldEnabled
                true,   // autoEatEnabled
                true,   // autoPotionEnabled
                true,   // autoMendEnabled
                true,   // shieldBreakEnabled
                true,   // retreatEnabled
                true,   // botsRelogs
                true,   // botLeaveOnDeath
                true,   // clearOnRemove
                true,   // totemPriority
                true,   // preferSword
                true);  // rangedRetreatOnClose
    }

    /** Everything on: used by tests that want to see the full item space. */
    public static GlobalCapabilities allEnabled() {
        return new GlobalCapabilities(true, true, true, true, true, true, true, true, true, true, true,
                true, true, true, true, true, true, true, true, true, true, true, true);
    }
}

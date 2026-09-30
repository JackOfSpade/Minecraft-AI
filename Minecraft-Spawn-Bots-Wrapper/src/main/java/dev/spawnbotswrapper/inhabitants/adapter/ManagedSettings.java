package dev.spawnbotswrapper.inhabitants.adapter;

/**
 * The PvP BOT settings the addon wants held at a value (config {@code pvpbotSettings}). A null component means "leave
 * PvP BOT's own value alone". Plain data: the adapter turns it into field writes ({@link SettingsPolicy} decides what,
 * {@link UpstreamSettingsWriter} does it).
 *
 * @param maxTargetDistance  how far PvP BOT looks for targets, blocks (4..64)
 * @param rangedMinRange     archers back off below this distance
 * @param rangedOptimalRange archer distance while retreating
 * @param rangedMaxRange     archers walk toward a target beyond this distance
 * @param autoEquipWeapon    PvP BOT's housekeeping that keeps selecting the best MELEE weapon (it ends every bow and
 *                           crossbow draw of a bot that also carries a melee weapon)
 */
public record ManagedSettings(Double maxTargetDistance, Double rangedMinRange, Double rangedOptimalRange,
                              Double rangedMaxRange, Boolean autoEquipWeapon) {

    /** Nothing managed. */
    public static final ManagedSettings NONE = new ManagedSettings(null, null, null, null, null);

    public boolean isEmpty() {
        return maxTargetDistance == null && rangedMinRange == null && rangedOptimalRange == null
                && rangedMaxRange == null && autoEquipWeapon == null;
    }
}

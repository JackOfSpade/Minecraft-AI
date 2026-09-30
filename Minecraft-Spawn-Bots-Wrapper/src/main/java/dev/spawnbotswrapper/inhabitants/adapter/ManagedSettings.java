package dev.spawnbotswrapper.inhabitants.adapter;

/**
 * The PvP BOT settings this addon manages. A null component means "leave PvP BOT's value alone". Immutable.
 *
 * @param maxTargetDistance  PvP BOT's targeting radius (blocks, within {@code 4..128})
 * @param rangedMinRange     archers back away / stop shooting closer than this (blocks)
 * @param rangedOptimalRange the distance archers try to keep (blocks)
 * @param rangedMaxRange     archers walk toward a target beyond this instead of shooting (blocks)
 * @param autoEquipWeapon    PvP BOT's housekeeping that keeps selecting the best MELEE weapon (it ends every bow and
 *                           crossbow draw of a bot that also carries a melee weapon)
 * @param autoTargetEnabled   PvP BOT's own target acquisition: false leaves acquisition to the wrapper's aggro hunter
 * @param rangedRetreatOnClose PvP BOT's "archers keep shooting and back away when a target is close": false lets a bot that
 *                           carries a melee weapon switch to it when the target is within twice its melee range
 * @param meleeRange         PvP BOT's melee range: a bot switches to its melee weapon within twice this (2.5 gives 5 blocks)
 *                           and attacks within it (within PvP BOT's clamp {@code 2..6})
 * @param bowMinDrawTime     ticks PvP BOT holds a bow draw before it releases (5..100; 20 = vanilla full power)
 */
public record ManagedSettings(Double maxTargetDistance, Double rangedMinRange, Double rangedOptimalRange,
                              Double rangedMaxRange, Boolean autoEquipWeapon, Boolean autoTargetEnabled,
                              Boolean rangedRetreatOnClose, Double meleeRange, Integer bowMinDrawTime) {

    /** The settings without the archer close-range behaviour, the melee range and the draw time (those are left alone). */
    public ManagedSettings(Double maxTargetDistance, Double rangedMinRange, Double rangedOptimalRange,
                           Double rangedMaxRange, Boolean autoEquipWeapon, Boolean autoTargetEnabled) {
        this(maxTargetDistance, rangedMinRange, rangedOptimalRange, rangedMaxRange, autoEquipWeapon, autoTargetEnabled,
                null, null, null);
    }

    /** The settings without the melee range and the draw time (those are then left alone). */
    public ManagedSettings(Double maxTargetDistance, Double rangedMinRange, Double rangedOptimalRange,
                           Double rangedMaxRange, Boolean autoEquipWeapon, Boolean autoTargetEnabled,
                           Boolean rangedRetreatOnClose) {
        this(maxTargetDistance, rangedMinRange, rangedOptimalRange, rangedMaxRange, autoEquipWeapon, autoTargetEnabled,
                rangedRetreatOnClose, null, null);
    }

    /** The settings without the bow draw time (that one is then left alone). */
    public ManagedSettings(Double maxTargetDistance, Double rangedMinRange, Double rangedOptimalRange,
                           Double rangedMaxRange, Boolean autoEquipWeapon, Boolean autoTargetEnabled,
                           Boolean rangedRetreatOnClose, Double meleeRange) {
        this(maxTargetDistance, rangedMinRange, rangedOptimalRange, rangedMaxRange, autoEquipWeapon, autoTargetEnabled,
                rangedRetreatOnClose, meleeRange, null);
    }

    /** Nothing managed. */
    public static final ManagedSettings NONE =
            new ManagedSettings(null, null, null, null, null, null, null, null, null);

    public boolean isEmpty() {
        return maxTargetDistance == null && rangedMinRange == null && rangedOptimalRange == null
                && rangedMaxRange == null && autoEquipWeapon == null
                && autoTargetEnabled == null && rangedRetreatOnClose == null && meleeRange == null
                && bowMinDrawTime == null;
    }
}

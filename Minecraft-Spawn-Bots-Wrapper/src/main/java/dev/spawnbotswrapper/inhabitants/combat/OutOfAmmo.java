package dev.spawnbotswrapper.inhabitants.combat;

import java.util.List;
import java.util.Locale;

/**
 * The rules of the out-of-ammo gap closer, as pure functions (see {@code mc.OutOfAmmoGapCloser} for the code that reads
 * the game and acts).
 * <p>
 * The problem: PvP BOT picks its ranged mode whenever a bot carries a bow or crossbow anywhere in slots 0-35, without
 * looking at ammo. With no arrow left it then selects the ranged weapon, notices it cannot shoot, flips to melee mode and
 * returns without attacking or moving; the next tick it picks ranged mode again. A bot that carries a sword too holds its
 * empty bow forever. The fix has two halves: the managed setting {@code rangedRetreatOnClose=false} makes PvP BOT's own
 * melee mode win when the target is within twice its melee range, and this class decides when the addon has to close the
 * remaining gap itself: select the melee weapon and walk toward the target until PvP BOT's melee mode takes over. If the
 * loadout has no melee weapon at all, it instead holds an empty hand and makes ordinary vanilla punches.
 * <p>
 * Melee scoring copies PvP BOT's own ({@code BotCombat.getMeleeScore}): swords, axes and the trident by base damage, a
 * sword +5 while its {@code preferSword} setting is on. A mace or a spear only counts when nothing scores.
 */
public final class OutOfAmmo {
    private OutOfAmmo() {
    }

    /** PvP BOT's base melee damage by item id; 0 for anything it does not score. */
    static double meleeDamage(String id) {
        return switch (id == null ? "" : id) {
            case "minecraft:netherite_sword" -> 8.0;
            case "minecraft:netherite_axe" -> 10.0;
            case "minecraft:diamond_sword" -> 7.0;
            case "minecraft:diamond_axe" -> 9.0;
            case "minecraft:iron_sword" -> 6.0;
            case "minecraft:iron_axe" -> 9.0;
            case "minecraft:stone_sword" -> 5.0;
            case "minecraft:stone_axe" -> 9.0;
            case "minecraft:golden_sword" -> 4.0;
            case "minecraft:golden_axe" -> 7.0;
            case "minecraft:wooden_sword" -> 4.0;
            case "minecraft:wooden_axe" -> 7.0;
            case "minecraft:trident" -> 9.0;
            default -> 0.0;
        };
    }

    static boolean isSword(String id) {
        return id != null && id.startsWith("minecraft:") && id.endsWith("_sword");
    }

    /** PvP BOT's melee score of one item id: its base damage, a sword +5 when {@code preferSword}. */
    public static double meleeScore(String id, boolean preferSword) {
        double base = meleeDamage(id);
        return base == 0.0 ? 0.0 : (preferSword && isSword(id) ? base + 5.0 : base);
    }

    private static boolean isMace(String id) {
        return "minecraft:mace".equals(id);
    }

    private static boolean isSpear(String id) {
        return id != null && id.toLowerCase(Locale.ROOT).contains("spear");
    }

    /**
     * The slot (index into {@code ids}, meant to be inventory slots 0-35; null or empty entries are empty slots) of the
     * melee weapon the bot should hold: the best-scoring sword, axe or trident (first one wins a tie, as in PvP BOT), else
     * a mace, else a spear; -1 when it carries none.
     */
    public static int bestMeleeSlot(List<String> ids, boolean preferSword) {
        int best = -1;
        double bestScore = 0.0;
        for (int i = 0; i < ids.size(); i++) {
            double score = meleeScore(ids.get(i), preferSword);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best >= 0) {
            return best;
        }
        for (int i = 0; i < ids.size(); i++) {
            if (isMace(ids.get(i))) {
                return i;
            }
        }
        for (int i = 0; i < ids.size(); i++) {
            if (isSpear(ids.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** What the addon does for one bot this tick. */
    public enum Verdict {
        /** Nothing: PvP BOT's own logic (or the crossbow trigger) is in charge. */
        IDLE,
        /** Hold the melee weapon (moving it into the hotbar first when it sits in the main inventory), do not walk. */
        SELECT,
        /** Hold the melee weapon and walk toward the target. */
        SELECT_AND_CLOSE,
        /** Hold an empty hand, walk into reach, and let the caller deliver ordinary vanilla punches. */
        PUNCH_AND_CLOSE
    }

    /**
     * Everything the decision needs.
     *
     * @param hasTarget         PvP BOT has a live target for this bot (in the same level)
     * @param retreating        PvP BOT's retreat flag; null when unreadable (then nothing is done)
     * @param busy              the bot is eating or drinking
     * @param rangedEnabled     PvP BOT's global ranged switch
     * @param carriesRanged     a bow or crossbow anywhere in slots 0-35 (the reason PvP BOT picks its ranged mode)
     * @param hasAmmo           an arrow of any kind anywhere in slots 0-35
     * @param loadedCrossbow    a charged crossbow the bot can fire at this target now (RangedFire's job)
     * @param meleeSlot         {@link #bestMeleeSlot}, -1 when none
     * @param mode              PvP BOT's weapon mode after its tick ("RANGED", "MELEE", ...), null when unreadable
     * @param distance          bot to target, blocks
     * @param meleeRange        PvP BOT's melee range
     */
    public record Facts(boolean hasTarget, Boolean retreating, boolean busy, boolean rangedEnabled,
                        boolean carriesRanged, boolean hasAmmo, boolean loadedCrossbow, int meleeSlot, String mode,
                        double distance, double meleeRange) {
    }

    /**
     * Decides one bot. Only a bot that PvP BOT would leave holding an empty ranged weapon is touched: it has a target, is
     * not retreating or eating, carries a bow or crossbow with ranged mode enabled, has no arrow and no bolt it can
     * fire. When it carries a melee weapon, the normal weapon-selection path applies. When it has only a ranged weapon,
     * the empty-hand path closes distance instead so it can still punch.
     * <ul>
     *   <li>Within twice the melee range PvP BOT's own melee mode runs (the managed {@code rangedRetreatOnClose=false});
     *       it can only select a weapon that is in the hotbar, so a weapon in the main inventory is still brought up
     *       ({@link Verdict#SELECT}).</li>
     *   <li>Farther away: the bot holds the melee weapon, and walks only when PvP BOT itself does not: in ranged mode PvP
     *       BOT walks toward a target beyond its ranged reach by itself (it never reaches its ranged handler), so a second
     *       walking input would double the push; in melee mode (the state its ranged handler leaves an empty bow in) it
     *       stands still.</li>
     * </ul>
     */
    public static Verdict judge(Facts f) {
        if (!f.hasTarget() || !Boolean.FALSE.equals(f.retreating()) || f.busy() || !f.rangedEnabled()
                || !f.carriesRanged() || f.hasAmmo() || f.loadedCrossbow() || f.mode() == null) {
            return Verdict.IDLE;
        }
        if (f.meleeSlot() < 0) {
            return switch (f.mode()) {
                case "RANGED", "MELEE" -> Verdict.PUNCH_AND_CLOSE;
                default -> Verdict.IDLE;
            };
        }
        if (f.distance() <= f.meleeRange() * 2.0) {
            return f.meleeSlot() >= 9 ? Verdict.SELECT : Verdict.IDLE;
        }
        return switch (f.mode()) {
            case "RANGED" -> Verdict.SELECT;
            case "MELEE" -> Verdict.SELECT_AND_CLOSE;
            default -> Verdict.IDLE;
        };
    }
}

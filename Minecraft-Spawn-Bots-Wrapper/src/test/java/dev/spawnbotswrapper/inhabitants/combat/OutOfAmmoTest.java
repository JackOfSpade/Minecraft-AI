package dev.spawnbotswrapper.inhabitants.combat;

import dev.spawnbotswrapper.inhabitants.combat.OutOfAmmo.Facts;
import dev.spawnbotswrapper.inhabitants.combat.OutOfAmmo.Verdict;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The rules of the out-of-ammo gap closer: which weapon a bot holds and when it walks up to its target. */
class OutOfAmmoTest {
    /** The shipped melee range: the sword is out within twice this, 5 blocks. */
    private static final double MELEE = 2.5;

    private static List<String> inventory(String... slots) {
        List<String> ids = new ArrayList<>(Arrays.asList(slots));
        while (ids.size() < 36) {
            ids.add("");
        }
        return ids;
    }

    /** An out-of-ammo archer with a sword in hotbar slot 0, PvP BOT holding the empty weapon (mode MELEE after its tick). */
    private static Facts facts(double distance, String mode) {
        return new Facts(true, false, false, true, true, false, false, 0, mode, distance, MELEE);
    }

    private static Facts with(Facts f, java.util.function.UnaryOperator<Facts> change) {
        return change.apply(f);
    }

    @Test
    void meleeScoresCopyPvpBotsOwn() {
        assertEquals(8.0, OutOfAmmo.meleeScore("minecraft:netherite_sword", false));
        assertEquals(13.0, OutOfAmmo.meleeScore("minecraft:netherite_sword", true), "a sword gets +5 when preferSword is on");
        assertEquals(10.0, OutOfAmmo.meleeScore("minecraft:netherite_axe", true), "an axe never does");
        assertEquals(9.0, OutOfAmmo.meleeScore("minecraft:trident", true));
        assertEquals(0.0, OutOfAmmo.meleeScore("minecraft:crossbow", true));
        assertEquals(0.0, OutOfAmmo.meleeScore("minecraft:mace", true), "a mace only counts when nothing else scores");
        assertEquals(0.0, OutOfAmmo.meleeScore(null, true));
    }

    @Test
    void theBestMeleeSlotIsTheHighestScoreAndTheFirstOfATie() {
        assertEquals(2, OutOfAmmo.bestMeleeSlot(inventory("minecraft:crossbow", "minecraft:iron_sword", "minecraft:diamond_sword"), true));
        assertEquals(3, OutOfAmmo.bestMeleeSlot(inventory("", "minecraft:iron_sword", "minecraft:crossbow", "minecraft:diamond_axe"), false),
                "without preferSword the axe (9) beats the iron sword (6)");
        assertEquals(1, OutOfAmmo.bestMeleeSlot(inventory("", "minecraft:iron_sword", "", "minecraft:iron_sword"), true), "first wins a tie");
        assertEquals(1, OutOfAmmo.bestMeleeSlot(inventory("minecraft:bow", "minecraft:mace"), true), "a mace when nothing scores");
        assertEquals(1, OutOfAmmo.bestMeleeSlot(inventory("minecraft:bow", "minecraft:netherite_spear"), true), "then a spear");
        assertEquals(-1, OutOfAmmo.bestMeleeSlot(inventory("minecraft:bow", "minecraft:arrow"), true));
        assertEquals(-1, OutOfAmmo.bestMeleeSlot(List.of(), true));
    }

    @Test
    void beyondTheSwitchDistanceAnEmptyArcherHoldsTheSwordAndWalksWherePvpBotStandsStill() {
        assertEquals(Verdict.SELECT_AND_CLOSE, OutOfAmmo.judge(facts(12.0, "MELEE")));
        assertEquals(Verdict.SELECT_AND_CLOSE, OutOfAmmo.judge(facts(5.01, "MELEE")));
        assertEquals(Verdict.SELECT, OutOfAmmo.judge(facts(30.0, "RANGED")), "in ranged mode PvP BOT walks by itself");
    }

    @Test
    void withinTwiceTheMeleeRangePvpBotsOwnMeleeModeRunsSoOnlyAWeaponInTheMainInventoryIsBroughtUp() {
        assertEquals(Verdict.IDLE, OutOfAmmo.judge(facts(5.0, "MELEE")), "5.0 = twice the shipped melee range");
        assertEquals(Verdict.IDLE, OutOfAmmo.judge(facts(4.0, "MELEE")));
        assertEquals(Verdict.SELECT, OutOfAmmo.judge(new Facts(true, false, false, true, true, false, false, 12, "MELEE", 4.0, MELEE)));
        assertEquals(Verdict.SELECT_AND_CLOSE, OutOfAmmo.judge(new Facts(true, false, false, true, true, false, false, 12, "MELEE", 6.0, MELEE)));
    }

    @Test
    void theSwitchDistanceFollowsTheMeleeRange() {
        assertEquals(Verdict.SELECT_AND_CLOSE, OutOfAmmo.judge(new Facts(true, false, false, true, true, false, false, 0, "MELEE", 6.0, 2.5)));
        assertEquals(Verdict.IDLE, OutOfAmmo.judge(new Facts(true, false, false, true, true, false, false, 0, "MELEE", 6.0, 3.5)),
                "PvP BOT's own default melee range of 3.5 switches at 7 blocks");
    }

    @Test
    void aBotThatCanStillShootOrHasNothingToFightWithIsLeftAlone() {
        Facts base = facts(12.0, "MELEE");
        assertEquals(Verdict.IDLE, OutOfAmmo.judge(with(base, f -> new Facts(false, f.retreating(), f.busy(), f.rangedEnabled(),
                f.carriesRanged(), f.hasAmmo(), f.loadedCrossbow(), f.meleeSlot(), f.mode(), f.distance(), f.meleeRange()))),
                "no target");
        assertEquals(Verdict.IDLE, OutOfAmmo.judge(new Facts(true, null, false, true, true, false, false, 0, "MELEE", 12.0, MELEE)),
                "an unreadable retreat flag");
        assertEquals(Verdict.IDLE, OutOfAmmo.judge(new Facts(true, true, false, true, true, false, false, 0, "MELEE", 12.0, MELEE)),
                "retreating");
        assertEquals(Verdict.IDLE, OutOfAmmo.judge(new Facts(true, false, true, true, true, false, false, 0, "MELEE", 12.0, MELEE)),
                "eating");
        assertEquals(Verdict.IDLE, OutOfAmmo.judge(new Facts(true, false, false, false, true, false, false, 0, "MELEE", 12.0, MELEE)),
                "ranged combat is off: PvP BOT is in melee mode anyway");
        assertEquals(Verdict.IDLE, OutOfAmmo.judge(new Facts(true, false, false, true, false, false, false, 0, "MELEE", 12.0, MELEE)),
                "no bow or crossbow");
        assertEquals(Verdict.IDLE, OutOfAmmo.judge(new Facts(true, false, false, true, true, true, false, 0, "MELEE", 12.0, MELEE)),
                "it has an arrow: PvP BOT shoots");
        assertEquals(Verdict.IDLE, OutOfAmmo.judge(new Facts(true, false, false, true, true, false, true, 0, "MELEE", 12.0, MELEE)),
                "a loaded crossbow is fired first");
        assertEquals(Verdict.IDLE, OutOfAmmo.judge(new Facts(true, false, false, true, true, false, false, -1, "MELEE", 12.0, MELEE)),
                "no melee weapon: left exactly as PvP BOT has it");
        assertEquals(Verdict.IDLE, OutOfAmmo.judge(new Facts(true, false, false, true, true, false, false, 0, null, 12.0, MELEE)),
                "an unreadable mode");
        assertEquals(Verdict.IDLE, OutOfAmmo.judge(facts(12.0, "MACE")), "a mace or spear or crystal mode is not ours");
    }
}

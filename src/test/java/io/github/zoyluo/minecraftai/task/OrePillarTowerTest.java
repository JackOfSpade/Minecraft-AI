package io.github.zoyluo.minecraftai.task;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The ground a pillar's height is counted from: the tower's own ground while the bot stands on the tower, otherwise where it stands. */
class OrePillarTowerTest {
    private static final int MAX = 3;

    @Test
    void beforeAnyPillarTheGroundIsWhereTheBotStands() {
        OrePillarTower tower = new OrePillarTower();
        assertEquals(64, tower.groundUnder(new BlockPos(0, 64, 0), MAX));
    }

    @Test
    void aSecondPillarOnTopOfTheFirstCountsFromTheGroundTheFirstBeganOn() {
        OrePillarTower tower = new OrePillarTower();
        tower.groundUnder(new BlockPos(0, 64, 0), MAX);
        tower.rises(new BlockPos(0, 67, 0));
        assertEquals(64, tower.groundUnder(new BlockPos(0, 66, 0), MAX), "partway up the tower");
        assertEquals(64, tower.groundUnder(new BlockPos(0, 67, 0), MAX), "on top of the tower");
    }

    @Test
    void aPillarBegunFromHigherGroundCountsFromThatGround() {
        OrePillarTower tower = new OrePillarTower();
        tower.groundUnder(new BlockPos(0, 64, 0), MAX);
        tower.rises(new BlockPos(0, 67, 0));
        // The bot walked up a hillside: its own ground is at 70 in another column, and 64 must not cap what it builds there.
        assertEquals(70, tower.groundUnder(new BlockPos(9, 70, 4), MAX));
    }

    @Test
    void theColumnOfTheTowerIsNotTheTowerOnceTheBotStandsHigherThanATowerCouldBe() {
        OrePillarTower tower = new OrePillarTower();
        tower.groundUnder(new BlockPos(0, 64, 0), MAX);
        tower.rises(new BlockPos(0, 67, 0));
        assertEquals(71, tower.groundUnder(new BlockPos(0, 71, 0), MAX), "a cliff in that column is ground");
    }

    @Test
    void lowerGroundIsTheNewGround() {
        OrePillarTower tower = new OrePillarTower();
        tower.groundUnder(new BlockPos(0, 64, 0), MAX);
        tower.rises(new BlockPos(0, 67, 0));
        assertEquals(60, tower.groundUnder(new BlockPos(0, 60, 0), MAX), "below the ground the tower began on, in its own column");
        assertEquals(61, tower.groundUnder(new BlockPos(5, 61, 5), MAX));
    }

    @Test
    void theLatestPillarIsTheTowerTheBotStandsOn() {
        OrePillarTower tower = new OrePillarTower();
        tower.groundUnder(new BlockPos(0, 64, 0), MAX);
        tower.rises(new BlockPos(0, 67, 0));
        // A second pillar from a ledge beside the first, at its top height, in another column.
        assertEquals(67, tower.groundUnder(new BlockPos(3, 67, 0), MAX));
        tower.rises(new BlockPos(3, 70, 0));
        assertEquals(67, tower.groundUnder(new BlockPos(3, 69, 0), MAX), "the second tower rests on the ledge");
        assertEquals(67, tower.groundUnder(new BlockPos(3, 70, 0), MAX));
    }
}

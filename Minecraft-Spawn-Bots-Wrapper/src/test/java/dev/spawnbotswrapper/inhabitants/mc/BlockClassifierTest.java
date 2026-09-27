package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.mc.BlockFacts.Fluid;
import dev.spawnbotswrapper.inhabitants.spawn.Cell;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlockClassifierTest {

    private static Cell cell(Fluid fluid, boolean hazard, boolean collides, double top, boolean topFull) {
        return BlockClassifier.classify(new BlockFacts(fluid, hazard, collides, top, topFull));
    }

    private static Cell solid(double top, boolean topFull) {
        return cell(Fluid.NONE, false, true, top, topFull);
    }

    @Test
    void airAndPassableDecorationAreEmpty() {
        assertEquals(Cell.EMPTY, BlockClassifier.classify(BlockFacts.NOTHING));
        assertEquals(Cell.EMPTY, cell(Fluid.NONE, false, false, 0.0, false));
    }

    @Test
    void fullBlockWithCompleteTopIsTheOnlyStandableFloor() {
        assertEquals(Cell.SOLID_STANDABLE, solid(1.0, true));
        // An upper slab reaches the top of the block with a complete top face, so it is a floor at block height;
        // floating-point noise in the shape's top must not change that.
        assertEquals(Cell.SOLID_STANDABLE, solid(1.0 - 1.0E-7, true));
        assertEquals(Cell.SOLID_OTHER, solid(0.99, true));
    }

    @Test
    void partialHeightShapesAreNotFloors() {
        assertEquals(Cell.SOLID_OTHER, solid(0.5, false), "bottom slab: feet would sit half a block off the grid");
        assertEquals(Cell.SOLID_OTHER, solid(0.5, true), "even a complete top face at half height is not block height");
        assertEquals(Cell.SOLID_OTHER, solid(1.0, false), "stairs / cauldron: full height but the top has a step or a hole");
        assertEquals(Cell.SOLID_OTHER, solid(0.875, false), "chest");
        assertEquals(Cell.SOLID_OTHER, solid(0.5625, false), "bed");
    }

    @Test
    void tallCollisionsBlockMovementButAreNotFloors() {
        assertEquals(Cell.SOLID_OTHER, solid(1.5, false), "fence, wall, closed gate");
        assertEquals(Cell.SOLID_OTHER, solid(1.5, true), "a tall shape is never a floor even if it has a full square at 1.0");
    }

    @Test
    void thinCoversAreWalkedOver() {
        assertEquals(Cell.EMPTY, solid(0.0625, true), "carpet");
        assertEquals(Cell.EMPTY, solid(0.125, true), "two snow layers");
        assertEquals(Cell.SOLID_OTHER, solid(0.25, true), "three snow layers is already an obstacle");
        assertEquals(Cell.SOLID_OTHER, solid(0.1875, false), "closed trapdoor");
    }

    @Test
    void waterIncludingWaterloggedBlocks() {
        assertEquals(Cell.WATER, cell(Fluid.WATER, false, false, 0.0, false), "open water");
        assertEquals(Cell.WATER, cell(Fluid.WATER, false, true, 0.125, false), "waterlogged carpet: thin cover only");
        // A slab or stairs still fills part of the column even though its top is at or below 1.0: a bot
        // placed here would be pushed out of the solid half by vanilla collision, not rest where planned.
        assertEquals(Cell.SOLID_OTHER, cell(Fluid.WATER, false, true, 0.5, false), "waterlogged bottom slab");
        assertEquals(Cell.SOLID_OTHER, cell(Fluid.WATER, false, true, 1.0, false), "waterlogged stairs/top slab");
        assertEquals(Cell.SOLID_OTHER, cell(Fluid.WATER, false, true, 1.5, false), "waterlogged fence still blocks");
    }

    @Test
    void lavaAndUnknownFluidsAreHarmful() {
        assertEquals(Cell.HAZARD, cell(Fluid.LAVA, false, false, 0.0, false));
        assertEquals(Cell.HAZARD, cell(Fluid.OTHER, false, false, 0.0, false), "a modded fluid is harmful until proven otherwise");
        assertEquals(Cell.SOLID_HAZARD, cell(Fluid.LAVA, false, true, 1.0, true));
        assertEquals(Cell.SOLID_HAZARD, cell(Fluid.OTHER, false, true, 0.5, false));
    }

    @Test
    void hazardsAreSplitByWhetherTheyBlockMovement() {
        assertEquals(Cell.HAZARD, cell(Fluid.NONE, true, false, 0.0, false), "fire, cobweb, berry bush, portal");
        assertEquals(Cell.HAZARD, cell(Fluid.NONE, true, true, 0.05, true), "a hazardous covering thinner than a step");
        assertEquals(Cell.SOLID_HAZARD, cell(Fluid.NONE, true, true, 0.9375, false), "cactus");
        assertEquals(Cell.SOLID_HAZARD, cell(Fluid.NONE, true, true, 0.4375, false), "campfire");
        assertEquals(Cell.SOLID_HAZARD, cell(Fluid.NONE, true, true, 1.0, true), "magma block");
    }

    @Test
    void hazardWinsOverWater() {
        assertEquals(Cell.HAZARD, cell(Fluid.WATER, true, false, 0.0, false), "bubble column carries water and pushes");
        assertEquals(Cell.SOLID_HAZARD, cell(Fluid.WATER, true, true, 0.4375, false), "waterlogged campfire");
    }

    @Test
    void shapeInformationIsDroppedWhenThereIsNoCollision() {
        BlockFacts facts = new BlockFacts(Fluid.NONE, false, false, 3.0, true);
        assertEquals(0.0, facts.collisionTop());
        assertFalse(facts.topFaceFull());
        assertEquals(Cell.EMPTY, BlockClassifier.classify(facts));
    }

    @Test
    void factsRequireAFluid() {
        assertThrows(IllegalArgumentException.class, () -> new BlockFacts(null, false, false, 0, false));
    }

    @Test
    void neverStandableUnlessFullHeightWithCompleteTopAndNothingHarmful() {
        double[] tops = {0.0, 0.0625, 0.125, 0.13, 0.5, 0.9375, 0.9999, 1.0, 1.0000001, 1.5, 2.0};
        for (Fluid fluid : Fluid.values()) {
            for (boolean hazard : new boolean[]{false, true}) {
                for (boolean collides : new boolean[]{false, true}) {
                    for (double top : tops) {
                        for (boolean topFull : new boolean[]{false, true}) {
                            BlockFacts f = new BlockFacts(fluid, hazard, collides, top, topFull);
                            Cell c = assertDoesNotThrow(() -> BlockClassifier.classify(f), f.toString());
                            String what = f.toString() + " -> " + c;

                            boolean harmful = hazard || fluid == Fluid.LAVA || fluid == Fluid.OTHER;
                            if (harmful) {
                                assertTrue(c == Cell.HAZARD || c == Cell.SOLID_HAZARD, what);
                            } else {
                                assertNotEquals(Cell.HAZARD, c, what);
                                assertNotEquals(Cell.SOLID_HAZARD, c, what);
                            }
                            if (c == Cell.SOLID_STANDABLE) {
                                assertTrue(f.collides() && f.topFaceFull() && Math.abs(f.collisionTop() - 1.0) < 1e-5, what);
                                assertFalse(harmful, what);
                                assertEquals(Fluid.NONE, f.fluid(), what);
                            }
                            if (c == Cell.WATER) {
                                assertEquals(Fluid.WATER, f.fluid(), what);
                            }
                            assertNotEquals(Cell.UNLOADED, c, "the classifier never says unloaded, only the probe does");
                        }
                    }
                }
            }
        }
    }
}

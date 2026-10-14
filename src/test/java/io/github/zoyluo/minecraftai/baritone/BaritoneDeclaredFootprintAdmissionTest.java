package io.github.zoyluo.minecraftai.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import io.github.zoyluo.minecraftai.navigation.NavRoute;
import io.github.zoyluo.minecraftai.navigation.NavRouteRules;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Admission-level regression for the pure action/body footprint API supplied by vendor patch 0022. */
final class BaritoneDeclaredFootprintAdmissionTest {
    @Test
    void rejectsDiagonalActionCornerBeforeTheExecutorCanLatchAnObservationFailure() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        NavRoute.MovementBoundary boundary = new NavRoute.MovementBoundary(anchor, 8, 62);
        BetterBlockPos source = new BetterBlockPos(8, 64, 0);
        BetterBlockPos destination = new BetterBlockPos(7, 64, 1);
        BetterBlockPos outsideCorner = new BetterBlockPos(8, 64, 1);
        NavRoute route = new NavRoute(NavRoute.Shape.RUN_AWAY, destination, 0,
                NavRoute.Options.WALK_ONLY, "bounded_diagonal", 0,
                Integer.MIN_VALUE, null, boundary);
        IPath path = new OneMovementPath(new DeclaredMovement(source, destination,
                List.of(source, destination), List.of(outsideCorner)));

        assertEquals(NavRouteRules.MOVEMENT_BOUNDARY_REFUSED,
                BaritoneNavigator.observedPathSafetyFailure(null, route, path, 0));
    }

    private record OneMovementPath(IMovement movement) implements IPath {
        @Override
        public List<IMovement> movements() {
            return List.of(movement);
        }

        @Override
        public List<BetterBlockPos> positions() {
            return List.of(movement.getSrc(), movement.getDest());
        }

        @Override
        public baritone.api.pathing.goals.Goal getGoal() {
            return null;
        }

        @Override
        public int getNumNodesConsidered() {
            return 0;
        }
    }

    private record DeclaredMovement(BetterBlockPos source, BetterBlockPos destination,
                                    List<BetterBlockPos> bodyCells,
                                    List<BetterBlockPos> actionCells) implements IMovement {
        @Override
        public double getCost() {
            return 0.0D;
        }

        @Override
        public MovementStatus update() {
            return MovementStatus.SUCCESS;
        }

        @Override
        public void reset() {
        }

        @Override
        public void resetBlockCache() {
        }

        @Override
        public boolean safeToCancel() {
            return true;
        }

        @Override
        public boolean calculatedWhileLoaded() {
            return true;
        }

        @Override
        public BetterBlockPos getSrc() {
            return source;
        }

        @Override
        public BetterBlockPos getDest() {
            return destination;
        }

        @Override
        public List<BetterBlockPos> getDeclaredMovementCells() {
            return bodyCells;
        }

        @Override
        public List<BetterBlockPos> getDeclaredActionCells() {
            return actionCells;
        }

        @Override
        public BlockPos getDirection() {
            return destination.subtract(source);
        }
    }
}

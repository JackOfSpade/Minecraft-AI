package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Last-resort straight-line walk check for {@link FollowTask}. A direct walk has no planning of its
 * own: it just presses forward toward a point, so it happily walks a bot into a lake, off a ledge or
 * into a cactus (the two lake incidents of the 2026-09-29 session log). It is therefore only ever
 * used along a segment that has been verified cell by cell first: every cell the straight line
 * crosses must be a real dry footing ({@link Standability#isStandableFresh}: no water, lava, fire,
 * cactus, magma... in feet/head/floor), reachable from the previous one by a level step, a one
 * block step up (with headroom) or a one block drop, and a diagonal step must have both of its
 * corner columns open so the 0.6-wide body does not clip a wall. Anything else -- a gap, a fall,
 * water, a wall -- refuses the segment and the follower waits/replans instead.
 */
final class FollowDirectWalk {
    /** Sampling step along the line; finer than a block so no column of the line is skipped. */
    private static final double SAMPLE_STEP = 0.25D;

    private FollowDirectWalk() {
    }

    record Verdict(boolean safe, String reason) {
        static final Verdict SAFE = new Verdict(true, "verified_safe");

        static Verdict unsafe(String reason) {
            return new Verdict(false, reason);
        }
    }

    static Verdict verify(ServerLevel world, BlockPos start, BlockPos goal) {
        if (!Standability.isStandableFresh(world, start)) {
            return Verdict.unsafe("start_not_standable");
        }
        double sx = start.getX() + 0.5D;
        double sz = start.getZ() + 0.5D;
        double dx = goal.getX() + 0.5D - sx;
        double dz = goal.getZ() + 0.5D - sz;
        int steps = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dz * dz) / SAMPLE_STEP));
        int cx = start.getX();
        int cy = start.getY();
        int cz = start.getZ();
        for (int i = 1; i <= steps; i++) {
            int nx = (int) Math.floor(sx + dx * i / steps);
            int nz = (int) Math.floor(sz + dz * i / steps);
            if (nx == cx && nz == cz) {
                continue;
            }
            int stepX = nx - cx;
            int stepZ = nz - cz;
            if (stepX != 0 && stepZ != 0
                    && (!isOpen(world, new BlockPos(cx + stepX, cy, cz))
                    || !isOpen(world, new BlockPos(cx, cy, cz + stepZ)))) {
                return Verdict.unsafe("diagonal_corner_blocked");
            }
            int ny;
            if (Standability.isStandableFresh(world, new BlockPos(nx, cy, nz))) {
                ny = cy;
            } else if (Standability.isStandableFresh(world, new BlockPos(nx, cy + 1, nz))
                    && isOpen(world, new BlockPos(cx, cy + 2, cz))) {
                ny = cy + 1;
            } else if (Standability.isStandableFresh(world, new BlockPos(nx, cy - 1, nz))
                    && isOpen(world, new BlockPos(nx, cy + 1, nz))) {
                ny = cy - 1;
            } else {
                return Verdict.unsafe("cell_not_walkable");
            }
            if (hazardBeside(world, new BlockPos(nx, ny, nz))) {
                return Verdict.unsafe("hazard_beside_route");
            }
            cx = nx;
            cy = ny;
            cz = nz;
        }
        if (Math.abs(cx - goal.getX()) > 1 || Math.abs(cz - goal.getZ()) > 1) {
            return Verdict.unsafe("goal_not_reached");
        }
        return Verdict.SAFE;
    }

    /** Passable for a body: no collision, no fluid, nothing that hurts. */
    private static boolean isOpen(ServerLevel world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getCollisionShape(world, pos).isEmpty()
                && state.getFluidState().isEmpty()
                && !Standability.isDangerous(state);
    }

    /** Cactus, fire, berry bushes... in a side cell still hurt a body brushing past them. */
    private static boolean hazardBeside(ServerLevel world, BlockPos pos) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = pos.relative(direction);
            if (Standability.isDangerous(world.getBlockState(side))
                    || Standability.isDangerous(world.getBlockState(side.above()))) {
                return true;
            }
        }
        return false;
    }
}

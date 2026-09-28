package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.log.LogFields;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public final class WalkToController {
    private static final double ARRIVAL_THRESHOLD = 0.6D;
    public static final double PATH_NODE_ARRIVAL_THRESHOLD = 0.35D;
    private static final double MIN_ARRIVAL_THRESHOLD = 0.1D;
    // Comfortably above the largest value any current caller passes (BoardBoatTask/BoatLaunchTask/FollowTask
    // request 1.0D for a looser shoreline stopping distance) -- guards against pathological inputs without
    // capping legitimate caller-requested tolerances at the class's own default.
    private static final double MAX_ARRIVAL_THRESHOLD = 1.5D;
    private static final double PROGRESS_EPSILON = 0.04D;
    private static final double HARD_PROGRESS_EPSILON = 0.005D;
    private static final int MAX_TICKS = 160;
    private static final int SIDLE_STEP_TICKS = 8;

    private final Vec3d target;
    private final double arrivalThreshold;
    private Vec3d lastPos;
    private int noProgressTicks;
    private int hardStuckTicks;
    private int sidleTicks;
    private int elapsed;

    public WalkToController(Vec3d target) {
        this(target, ARRIVAL_THRESHOLD);
    }

    public WalkToController(Vec3d target, double arrivalThreshold) {
        this.target = target;
        this.arrivalThreshold = Math.max(MIN_ARRIVAL_THRESHOLD, Math.min(MAX_ARRIVAL_THRESHOLD, arrivalThreshold));
    }

    /** Package-visible for tests: the effective (clamped) arrival tolerance in use. */
    double arrivalThreshold() {
        return arrivalThreshold;
    }

    public ActionResult tick(ActionPack pack) {
        elapsed++;
        if (elapsed > MAX_TICKS) {
            pack.stopMovement();
            return ActionResult.failed("timeout");
        }

        var player = pack.player();
        ServerWorld world = player.getEntityWorld();
        MinecraftAiConfig.Nav nav = MinecraftAiConfig.get().nav();
        Vec3d current = player.getEntityPos();
        double dx = target.x - current.x;
        double dz = target.z - current.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance <= arrivalThreshold) {
            pack.stopMovement();
            return ActionResult.SUCCESS;
        }

        Vec3d move = new Vec3d(dx / horizontalDistance, 0.0D, dz / horizontalDistance);
        SidleCommand sidle = sidleCommand(move, nav);
        LookAction.lookHorizontallyAt(player, current.add(sidle.lookVector.multiply(4.0D)));
        pack.setForward(1.0F);
        pack.setStrafing(sidle.strafing);

        JumpDecision jump = shouldJump(current, move, world, nav);
        // Humanize: only tap jump once (a single jump) when "already grounded + there is actually a step/gap ahead", and never hold the jump key down.
        // The old implementation, setJumping(jump.jump), kept holding jump for every tick the obstacle persisted -- causing the bot to bunny-hop
        // continuously the instant it landed, which not only looks unlike a real player but also lowers horizontal speed while jumping (observed in
        // testing as "jumping while chopping trees slows movement"). The on-ground gate ensures only one jump per step.
        if (jump.jump && player.isOnGround()) {
            pack.jumpOnce();
        }
        pack.setJumping(false);
        pack.setSprinting(shouldSprint(horizontalDistance, jump, current, move, world, nav));

        if (lastPos != null && current.distanceTo(lastPos) < PROGRESS_EPSILON) {
            noProgressTicks++;
        } else {
            noProgressTicks = 0;
            sidleTicks = 0;
        }
        if (lastPos != null && current.distanceTo(lastPos) < HARD_PROGRESS_EPSILON) {
            hardStuckTicks++;
        } else {
            hardStuckTicks = 0;
        }
        lastPos = current;

        boolean sidling = noProgressTicks >= nav.sidleAfter();
        if (hardStuckTicks > nav.hardLimit() && !sidling) {
            pack.stopMovement();
            logStuck(pack, "hard", current, move, world);
            return ActionResult.failed("stuck_hard");
        }
        if (sidling) {
            sidleTicks++;
        }
        if (sidleTicks > nav.sidleLimit()) {
            pack.stopMovement();
            logStuck(pack, "blocked", current, move, world);
            return ActionResult.failed("stuck_blocked");
        }
        return ActionResult.IN_PROGRESS;
    }

    private SidleCommand sidleCommand(Vec3d move, MinecraftAiConfig.Nav nav) {
        if (noProgressTicks < nav.sidleAfter()) {
            return new SidleCommand(move, 0.0F);
        }
        int step = Math.floorMod(sidleTicks / SIDLE_STEP_TICKS, 4);
        return switch (step) {
            case 0 -> new SidleCommand(rotate(move, 35.0D), 1.0F);
            case 1 -> new SidleCommand(rotate(move, -35.0D), -1.0F);
            case 2 -> new SidleCommand(rotate(move, 60.0D), 0.7F);
            default -> new SidleCommand(rotate(move, -60.0D), -0.7F);
        };
    }

    private static JumpDecision shouldJump(Vec3d current, Vec3d move, ServerWorld world, MinecraftAiConfig.Nav nav) {
        BlockPos front = footPos(current, move, nav.jumpReach());
        BlockState frontState = world.getBlockState(front);
        BlockState aboveFront = world.getBlockState(front.up());
        BlockPos playerPos = BlockPos.ofFloored(current);
        BlockState abovePlayer = world.getBlockState(playerPos.up());
        boolean headClear = isClear(world, front.up()) && isClear(world, playerPos.up());

        if (hasCollision(frontState, world, front)) {
            double top = collisionTop(frontState, world, front);
            if (top <= 1.0D && headClear) {
                return new JumpDecision(true, false, false);
            }
            return new JumpDecision(false, true, false);
        }

        if (isGapAhead(current, move, world) && isClear(world, abovePlayer, playerPos.up())) {
            return new JumpDecision(true, false, true);
        }
        return new JumpDecision(false, false, false);
    }

    private static boolean isGapAhead(Vec3d current, Vec3d move, ServerWorld world) {
        BlockPos near = footPos(current, move, 1.35D);
        if (!isClear(world, near) || !isClear(world, near.up()) || !isClear(world, near.down())) {
            return false;
        }
        BlockPos landing = footPos(current, move, 2.1D);
        return isClear(world, landing)
                && isClear(world, landing.up())
                && hasCollision(world.getBlockState(landing.down()), world, landing.down());
    }

    private static boolean shouldSprint(double horizontalDistance, JumpDecision jump, Vec3d current, Vec3d move, ServerWorld world, MinecraftAiConfig.Nav nav) {
        if (horizontalDistance < nav.sprintMinDist()) {
            return false;
        }
        if (jump.blocked || (jump.jump && !jump.gap)) {
            return false;
        }
        return clearAhead(current, move, world, 1.0D) && clearAhead(current, move, world, 2.0D);
    }

    private static boolean clearAhead(Vec3d current, Vec3d move, ServerWorld world, double distance) {
        BlockPos pos = footPos(current, move, distance);
        return isClear(world, pos) && isClear(world, pos.up());
    }

    private static boolean isClear(ServerWorld world, BlockPos pos) {
        return isClear(world, world.getBlockState(pos), pos);
    }

    private static boolean isClear(ServerWorld world, BlockState state, BlockPos pos) {
        return state.getCollisionShape(world, pos).isEmpty();
    }

    private static boolean hasCollision(BlockState state, ServerWorld world, BlockPos pos) {
        return !state.getCollisionShape(world, pos).isEmpty();
    }

    private static double collisionTop(BlockState state, ServerWorld world, BlockPos pos) {
        if (!hasCollision(state, world, pos)) {
            return 0.0D;
        }
        return state.getCollisionShape(world, pos).getMax(Direction.Axis.Y);
    }

    private static BlockPos footPos(Vec3d current, Vec3d move, double distance) {
        return BlockPos.ofFloored(current.x + move.x * distance, current.y, current.z + move.z * distance);
    }

    private static Vec3d rotate(Vec3d move, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec3d(move.x * cos - move.z * sin, 0.0D, move.x * sin + move.z * cos);
    }

    private static void logStuck(ActionPack pack, String reason, Vec3d current, Vec3d move, ServerWorld world) {
        BlockPos front = footPos(current, move, 1.0D);
        BlockState state = world.getBlockState(front);
        BotLog.warn(LogCategory.PATH, pack.player(), "walk_stuck",
                "reason", reason,
                "front", LogFields.pos(front),
                "front_block", Registries.BLOCK.getId(state.getBlock()),
                "yaw", Math.round(pack.player().getYaw()),
                "target", String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", current.x + move.x, current.y, current.z + move.z));
    }

    private record SidleCommand(Vec3d lookVector, float strafing) {
    }

    private record JumpDecision(boolean jump, boolean blocked, boolean gap) {
    }
}

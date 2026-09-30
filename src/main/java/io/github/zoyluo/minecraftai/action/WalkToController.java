package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.log.LogFields;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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
    /** Vanilla's player step height: a rise up to this is walked up without a jump. */
    private static final double STEP_HEIGHT = 0.6D;
    /** How high a standing jump lifts a player's feet (jump velocity 0.42, gravity 0.08, drag 0.98: about 1.25 blocks). */
    private static final double JUMP_HEIGHT = 1.25D;

    private final Vec3 target;
    private final double arrivalThreshold;
    private Vec3 lastPos;
    private int noProgressTicks;
    private int hardStuckTicks;
    private int sidleTicks;
    // Ticks of this walk against its time limit: a tick at a deliberately slow pace counts for less (ActionPack#paceClockWeight).
    private double elapsed;
    // The last tick's answer about the ground ahead (jump, blocked, clear) for the pace enforcer; true until the first tick says otherwise.
    private boolean geometryAllowsSprint = true;

    public WalkToController(Vec3 target) {
        this(target, ARRIVAL_THRESHOLD);
    }

    public WalkToController(Vec3 target, double arrivalThreshold) {
        this.target = target;
        this.arrivalThreshold = Math.max(MIN_ARRIVAL_THRESHOLD, Math.min(MAX_ARRIVAL_THRESHOLD, arrivalThreshold));
    }

    /** The point this walk is heading for. */
    public Vec3 target() {
        return target;
    }

    /**
     * Whether the ground ahead lets the bot sprint: no jump or blocked step to take, the two cells ahead clear. The distance to
     * this walk's own target is NOT part of it (how fast to go is the pace policy's business, see {@link PacePolicy}).
     */
    public boolean geometryAllowsSprint() {
        return geometryAllowsSprint;
    }

    /** Package-visible for tests: the effective (clamped) arrival tolerance in use. */
    double arrivalThreshold() {
        return arrivalThreshold;
    }

    public ActionResult tick(ActionPack pack) {
        elapsed += Math.min(1.0D, pack.paceClockWeight());
        if (elapsed > MAX_TICKS) {
            pack.stopMovement();
            return ActionResult.failed("timeout");
        }

        var player = pack.player();
        ServerLevel world = player.level();
        MinecraftAiConfig.Nav nav = MinecraftAiConfig.get().nav();
        Vec3 current = player.position();
        double dx = target.x - current.x;
        double dz = target.z - current.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance <= arrivalThreshold) {
            pack.stopMovement();
            return ActionResult.SUCCESS;
        }

        Vec3 move = new Vec3(dx / horizontalDistance, 0.0D, dz / horizontalDistance);
        SidleCommand sidle = sidleCommand(move, nav);
        LookAction.lookHorizontallyAt(player, current.add(sidle.lookVector.scale(4.0D)));
        pack.setForward(1.0F);
        pack.setStrafing(sidle.strafing);

        JumpDecision jump = shouldJump(current, move, world, nav);
        // Humanize: only tap jump once (a single jump) when "already grounded + there is actually a step/gap ahead", and never hold the jump key down.
        // The old implementation, setJumping(jump.jump), kept holding jump for every tick the obstacle persisted -- causing the bot to bunny-hop
        // continuously the instant it landed, which not only looks unlike a real player but also lowers horizontal speed while jumping (observed in
        // testing as "jumping while chopping trees slows movement"). The on-ground gate ensures only one jump per step.
        if (jump.jump && player.onGround()) {
            pack.jumpOnce();
        }
        pack.setJumping(false);
        geometryAllowsSprint = sprintGeometryClear(jump, current, move, world);
        if (!MinecraftAiConfig.get().behaviour().paceOrDefaults().paceEnabled()) {
            // pace.enabled=false: the sprint rule this controller always had (far from its own target and a clear way), written directly.
            pack.setSprinting(horizontalDistance >= nav.sprintMinDist() && geometryAllowsSprint);
        }

        // A bot that sneaks or holds an item up moves at a fraction of a stride per tick: the limits scale with its input.
        double scale = Math.max(0.05D, Math.min(1.0D, pack.lastInputScale()));
        if (lastPos != null && current.distanceTo(lastPos) < PROGRESS_EPSILON * scale) {
            noProgressTicks++;
        } else {
            noProgressTicks = 0;
            sidleTicks = 0;
        }
        if (lastPos != null && current.distanceTo(lastPos) < HARD_PROGRESS_EPSILON * scale) {
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

    private SidleCommand sidleCommand(Vec3 move, MinecraftAiConfig.Nav nav) {
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

    /**
     * The obstacle ahead is judged by how far its top rises above the bot's feet: up to {@link #STEP_HEIGHT} vanilla walks the bot
     * up it, up to {@link #JUMP_HEIGHT} it takes a jump, anything higher blocks the walk. The cells are sampled at the feet plus the
     * step height (see {@link #footPos}), so the ground the bot stands in is never mistaken for a step: feet on farmland, a dirt path
     * or soul sand are inside that block's cell, and the next cell of the same ground ahead is only 1/16 higher.
     */
    private static JumpDecision shouldJump(Vec3 current, Vec3 move, ServerLevel world, MinecraftAiConfig.Nav nav) {
        BlockPos front = footPos(current, move, nav.jumpReach());
        BlockState frontState = world.getBlockState(front);
        BlockPos playerPos = feetCell(current);
        BlockState abovePlayer = world.getBlockState(playerPos.above());
        boolean headClear = isClear(world, front.above()) && isClear(world, playerPos.above());

        if (hasCollision(frontState, world, front)) {
            double rise = front.getY() + collisionTop(frontState, world, front, move) - current.y;
            if (rise <= STEP_HEIGHT) {
                return new JumpDecision(false, false, false);
            }
            if (rise <= JUMP_HEIGHT && headClear) {
                return new JumpDecision(true, false, false);
            }
            return new JumpDecision(false, true, false);
        }

        if (isGapAhead(current, move, world) && isClear(world, abovePlayer, playerPos.above())) {
            return new JumpDecision(true, false, true);
        }
        return new JumpDecision(false, false, false);
    }

    /**
     * A gap is a cell ahead with nothing to stand on and ground again beyond it: the bot jumps across. One exception: a one-deep water
     * cell (an irrigation channel or the water hole of a farm) with farmland beyond it. The jump would land on the farmland from above
     * half a block and vanilla would trample it most of the time, while wading through the shallow water and stepping out on the far
     * side is safe and tramples nothing.
     */
    private static boolean isGapAhead(Vec3 current, Vec3 move, ServerLevel world) {
        BlockPos near = footPos(current, move, 1.35D);
        if (!isClear(world, near) || !isClear(world, near.above()) || !isClear(world, near.below())) {
            return false;
        }
        BlockPos landing = footPos(current, move, 2.1D);
        boolean gap = isClear(world, landing)
                && isClear(world, landing.above())
                && hasCollision(world.getBlockState(landing.below()), world, landing.below());
        return gap && !(isShallowWater(world, near.below()) && world.getBlockState(landing.below()).is(Blocks.FARMLAND));
    }

    /** A water cell with solid ground right under it: wading through it is a step down and a step out, never a swim. */
    private static boolean isShallowWater(ServerLevel world, BlockPos pos) {
        return world.getFluidState(pos).is(FluidTags.WATER) && hasCollision(world.getBlockState(pos.below()), world, pos.below());
    }

    private static boolean sprintGeometryClear(JumpDecision jump, Vec3 current, Vec3 move, ServerLevel world) {
        if (jump.blocked || (jump.jump && !jump.gap)) {
            return false;
        }
        return clearAhead(current, move, world, 1.0D) && clearAhead(current, move, world, 2.0D);
    }

    private static boolean clearAhead(Vec3 current, Vec3 move, ServerLevel world, double distance) {
        BlockPos pos = footPos(current, move, distance);
        return isClear(world, pos) && isClear(world, pos.above());
    }

    private static boolean isClear(ServerLevel world, BlockPos pos) {
        return isClear(world, world.getBlockState(pos), pos);
    }

    private static boolean isClear(ServerLevel world, BlockState state, BlockPos pos) {
        return state.getCollisionShape(world, pos).isEmpty();
    }

    private static boolean hasCollision(BlockState state, ServerLevel world, BlockPos pos) {
        return !state.getCollisionShape(world, pos).isEmpty();
    }

    private static double collisionTop(BlockState state, ServerLevel world, BlockPos pos, Vec3 move) {
        if (!hasCollision(state, world, pos)) {
            return 0.0D;
        }
        return entryTop(state.getCollisionShape(world, pos).toAabbs(), move.x, move.z);
    }

    /**
     * The top of a block's collision boxes (block-local, 0..1) in the half of the block the bot walks into first, along the dominant
     * axis of {@code (moveX, moveZ)}; the whole block's top when that half is empty. A stair seen from its low side is a half-block
     * step there (it walks or jumps onto the low step, then steps up to the high one), not the full block its highest box makes it:
     * from a bottom slab a stair one level up rises 1.0, a jump, where the highest box would make it 1.5 and block the walk.
     */
    static double entryTop(List<AABB> boxes, double moveX, double moveZ) {
        boolean alongX = Math.abs(moveX) >= Math.abs(moveZ);
        boolean positive = (alongX ? moveX : moveZ) >= 0.0D;
        double top = 0.0D;
        double highest = 0.0D;
        boolean entered = false;
        for (AABB box : boxes) {
            highest = Math.max(highest, box.maxY);
            double low = alongX ? box.minX : box.minZ;
            double high = alongX ? box.maxX : box.maxZ;
            if (positive ? low < 0.5D : high > 0.5D) {
                top = Math.max(top, box.maxY);
                entered = true;
            }
        }
        return entered ? top : highest;
    }

    /**
     * The cell at the bot's feet level {@code distance} ahead. It is sampled a step height above the feet: on a full block that is
     * the feet cell itself, and on ground lower than a full block (farmland, a dirt path, soul sand, a slab) it is the cell above
     * that ground, not the ground block the feet are inside.
     */
    private static BlockPos footPos(Vec3 current, Vec3 move, double distance) {
        return BlockPos.containing(current.x + move.x * distance, current.y + STEP_HEIGHT, current.z + move.z * distance);
    }

    /** The cell the bot's feet stand in, by the same rule as {@link #footPos}. */
    private static BlockPos feetCell(Vec3 current) {
        return BlockPos.containing(current.x, current.y + STEP_HEIGHT, current.z);
    }

    private static Vec3 rotate(Vec3 move, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec3(move.x * cos - move.z * sin, 0.0D, move.x * sin + move.z * cos);
    }

    private static void logStuck(ActionPack pack, String reason, Vec3 current, Vec3 move, ServerLevel world) {
        BlockPos front = footPos(current, move, 1.0D);
        BlockState state = world.getBlockState(front);
        BotLog.warn(LogCategory.PATH, pack.player(), "walk_stuck",
                "reason", reason,
                "front", LogFields.pos(front),
                "front_block", BuiltInRegistries.BLOCK.getKey(state.getBlock()),
                "yaw", Math.round(pack.player().getYRot()),
                "target", String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", current.x + move.x, current.y, current.z + move.z));
    }

    private record SidleCommand(Vec3 lookVector, float strafing) {
    }

    private record JumpDecision(boolean jump, boolean blocked, boolean gap) {
    }
}

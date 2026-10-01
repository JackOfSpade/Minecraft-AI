package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.FakePlayerMotion;
import io.github.zoyluo.minecraftai.pathfinding.DangerCheck;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * One in-flight, input-driven step of a bot: what a player does with the movement keys where the old correction code teleported
 * (walk onto the next cell, hop a ledge, drop off an edge, swim up a cell, recentre in the cell, sneak a little over an edge, get shoved
 * out of a block). It never moves the bot itself: it writes the forward key, the jump key and sneak, aims at the target and lets vanilla
 * physics do the rest, at the speed a player has (the pace enforcer applies the vanilla rules to the keys, see
 * {@link ActionPack#markControllerInput}). {@link ActionPack#runStep} ticks one as a controller of the pack; {@code PathExecutor} ticks
 * one as the pre-step of a route.
 *
 * <p>Lifecycle: {@link #begin} builds it (no key is touched), {@link #tick} advances it once per game tick and answers
 * {@code IN_PROGRESS}, {@code SUCCESS} or {@code FAILED(reason)}; keys are released on the two last answers, and by {@link #cancel} for
 * an owner that abandons a step in flight. The first tick validates the step against the world (adjacency of the kind, a dry
 * hazard-free landing, no block or entity in the way) and every later tick re-proves the landing, so a step never walks into something
 * that appeared under it. A step keeps no state outside itself; a pause, a restart or a hazard abort just cancels it and the owner
 * re-derives its state from {@code bot.blockPosition()}.</p>
 */
public final class WalkedStep {
    public enum Kind {
        /** Walk to the adjacent cell at the same height. */
        FLAT,
        /** Forward plus jump onto the adjacent cell one block higher. */
        STEP_UP,
        /** Walk off the edge onto the adjacent cell one to three blocks lower (gravity lands it). */
        STEP_DOWN,
        /** Fall straight down into the cell one to three blocks below (a hole the bot has just dug): no key is needed, gravity lands it. */
        DROP,
        /** Forward, and jump while the head is under water or the target is higher, to the adjacent water cell. */
        SWIM,
        /** Sneak and walk to a point a little over the edge of the support (sneaking will not fall off it): inside the bot's cell, or up to {@value WalkedStepRules#IN_CELL_MAX_OFFSET} block from the point it stands at, so a little into the next cell. */
        SNEAK_SHIFT,
        /** Walk back to a point inside the current cell, at most {@value WalkedStepRules#IN_CELL_MAX_OFFSET} block away. */
        RECENTER,
        /** A vanilla-client style shove (at most 0.1 block per tick) toward the nearest free side while the body overlaps a block. */
        PUSH_OUT
    }

    public enum Status {
        IN_PROGRESS, SUCCESS, FAILED
    }

    /** What a tick answers. */
    public record Result(Status status, String reason) {
        static final Result RUNNING = new Result(Status.IN_PROGRESS, "");
        static final Result DONE = new Result(Status.SUCCESS, "");

        static Result failed(String reason) {
            return new Result(Status.FAILED, reason);
        }

        public boolean inProgress() {
            return status == Status.IN_PROGRESS;
        }

        public boolean succeeded() {
            return status == Status.SUCCESS;
        }

        public boolean failed() {
            return status == Status.FAILED;
        }
    }

    private static final double PUSH_SEARCH_STEP = 0.05D;
    private static final double BODY_EPSILON = 1.0E-7D;
    private static final int[][] PUSH_DIRECTIONS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};

    private final AIPlayerEntity bot;
    private Kind kind;
    private final BlockPos cell;
    private final Vec3 point;
    private final String reason;
    private int ticks;
    private double spent;
    private double budget;
    private Vec3 lastPosition;
    private boolean ended;
    private String failure;
    private Result finalResult;
    private int pushDirection = -1;
    private boolean startedWet;

    private boolean anchored;

    private WalkedStep(AIPlayerEntity bot, BlockPos cell, Vec3 point, Kind kind, String reason) {
        this.bot = bot;
        this.kind = kind;
        this.cell = cell.immutable();
        this.point = point;
        this.reason = reason == null ? "" : reason;
    }

    /** A step to the bottom centre of {@code cell}: an adjacent cell for FLAT, STEP_UP, STEP_DOWN and SWIM; the bot's own cell for the rest. */
    public static WalkedStep begin(AIPlayerEntity bot, BlockPos cell, Kind kind, String reason) {
        return new WalkedStep(bot, cell, Vec3.atBottomCenterOf(cell), kind, reason);
    }

    /** A step to {@code point} inside the bot's cell (RECENTER, SNEAK_SHIFT) or, for PUSH_OUT, out of the block it overlaps. */
    public static WalkedStep begin(AIPlayerEntity bot, Vec3 point, Kind kind, String reason) {
        return new WalkedStep(bot, BlockPos.containing(point), point, kind, reason);
    }

    /**
     * A step to {@code point} (a RECENTER or SNEAK_SHIFT) whose owning cell is {@code anchor} and not the cell that contains the point:
     * the sneak shift over the edge of the support (the point lies a little in the next cell, over the void a sneaking body still
     * stands at) and the recentre that walks back from it. The bot has to stand in the anchor cell or one of its neighbours.
     */
    public static WalkedStep beginAnchored(AIPlayerEntity bot, BlockPos anchor, Vec3 point, Kind kind, String reason) {
        WalkedStep step = new WalkedStep(bot, anchor, point, kind, reason);
        step.anchored = true;
        return step;
    }

    public Kind kind() {
        return kind;
    }

    /** The cell the step ends in (for a point-step the cell that contains the point). */
    public BlockPos cell() {
        return cell;
    }

    public String reason() {
        return reason;
    }

    public int ticks() {
        return ticks;
    }

    /** Why the step failed, or {@code null} while it has not. */
    public String failure() {
        return failure;
    }

    /** Whether the step walks off an edge: a sneak must not be applied to it (a sneaking player does not walk off). */
    public boolean descends() {
        return kind == Kind.STEP_DOWN || kind == Kind.DROP;
    }

    /**
     * Why a step of {@code kind} from the bot's current cell to {@code target} is not legal right now, or {@code null} when it is:
     * the offset fits the kind, the landing is a dry hazard-free standable cell, the cells the body sweeps through (a corner, the
     * headroom of a hop, the column of a drop) are open, and no block or entity occupies the landing. The one place the path pre-step
     * chooser, the suffocation escape and the step itself take their validity from.
     */
    public static String refusal(AIPlayerEntity bot, BlockPos target, Kind kind) {
        BlockPos here = bot.blockPosition();
        int dx = target.getX() - here.getX();
        int dy = target.getY() - here.getY();
        int dz = target.getZ() - here.getZ();
        if (!WalkedStepRules.offsetAllowed(kind, dx, dy, dz)) {
            return "not_adjacent";
        }
        ServerLevel level = bot.level();
        if (kind == Kind.SWIM) {
            String hazard = swimHazard(level, target, startsWet(bot));
            if (hazard != null) {
                return hazard;
            }
        } else {
            String hazard = landingHazard(level, target);
            if (hazard != null) {
                return hazard;
            }
            int sweepY = Math.max(here.getY(), target.getY());
            if (dx != 0 && dz != 0
                    && (!passable(level, new BlockPos(here.getX() + dx, sweepY, here.getZ()))
                    || !passable(level, new BlockPos(here.getX(), sweepY, here.getZ() + dz)))) {
                return "corner_blocked";
            }
            if (kind == Kind.STEP_UP && !passable(level, here.above())) {
                return "no_headroom";
            }
            if (kind == Kind.STEP_DOWN || kind == Kind.DROP) {
                for (int y = target.getY() + 1; y <= here.getY() + 1; y++) {
                    BlockPos column = new BlockPos(target.getX(), y, target.getZ());
                    if (!level.getBlockState(column).getCollisionShape(level, column).isEmpty()) {
                        return "blocked_drop";
                    }
                }
            }
        }
        AABB landing = bot.getBoundingBox().move(
                target.getX() + 0.5D - bot.getX(), target.getY() - bot.getY(), target.getZ() + 0.5D - bot.getZ());
        if (!level.noCollision(bot, landing)) {
            return "occupied";
        }
        if (FakePlayerMotion.landingOccupant(bot, landing) != null) {
            return "entity_occupied";
        }
        return null;
    }

    /** A dry, supported, hazard-free landing (re-proven on every tick of a cell-step). */
    private static String landingHazard(ServerLevel level, BlockPos landing) {
        String danger = DangerCheck.scan(level, landing);
        if (danger != null) {
            return "hazard:" + danger;
        }
        return Standability.isStandableFresh(level, landing) ? null : "no_landing";
    }

    /** Whether the bot is in water right now (a stroke that leaves the water for the air above it is still a swim). */
    private static boolean startsWet(AIPlayerEntity bot) {
        return bot.isInWater() || bot.level().getFluidState(bot.blockPosition()).is(FluidTags.WATER);
    }

    private static String swimHazard(ServerLevel level, BlockPos target, boolean startedWet) {
        if (target.getY() < level.getMinY() + 1) {
            return "hazard:void";
        }
        if (level.getFluidState(target).is(FluidTags.LAVA) || level.getFluidState(target.above()).is(FluidTags.LAVA)) {
            return "hazard:lava";
        }
        if (!startedWet && !level.getFluidState(target).is(FluidTags.WATER)
                && !level.getFluidState(target.above()).is(FluidTags.WATER)) {
            return "not_water";
        }
        return null;
    }

    private static boolean passable(ServerLevel level, BlockPos feet) {
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && !Standability.isDangerous(level.getBlockState(feet))
                && !Standability.isDangerous(level.getBlockState(feet.above()));
    }

    /** Whether the bot's body is resting on something: vanilla's grounded bit, or the block a one-microblock support probe finds below. */
    public static boolean supported(AIPlayerEntity bot) {
        if (bot.onGround()) {
            return true;
        }
        // A clientless player can report onGround false on the tick after a landing; the probe is vanilla's own support test.
        AABB box = bot.getBoundingBox();
        AABB probe = new AABB(box.minX, box.minY - 1.0E-6D, box.minZ, box.maxX, box.minY, box.maxZ);
        return bot.level().findSupportingBlock(bot, probe).isPresent();
    }

    /** Advances the step by one game tick. */
    public Result tick() {
        if (ended) {
            return finalResult == null ? Result.failed("cancelled") : finalResult;
        }
        ticks++;
        Vec3 position = bot.position();
        double speed = lastPosition == null ? 0.0D : Math.hypot(position.x - lastPosition.x, position.z - lastPosition.z);
        lastPosition = position;
        if (ticks == 1) {
            startedWet = startsWet(bot);
            String refused = firstTickRefusal();
            if (refused != null) {
                return fail(refused);
            }
            budget = WalkedStepRules.timeoutBudget(kind, Math.hypot(point.x - position.x, point.z - position.z)
                    + Math.abs(cell.getY() - bot.blockPosition().getY()));
            BotLog.action(bot, "walked_step_begin", "kind", kind, "reason", reason,
                    "from", bot.blockPosition().toShortString(), "to", point);
        } else if (WalkedStepRules.endsInCell(kind)) {
            String lost = kind == Kind.SWIM ? swimHazard(bot.level(), cell, startedWet) : landingHazard(bot.level(), cell);
            if (lost != null) {
                return fail(lost);
            }
            if (kind != Kind.SWIM && FakePlayerMotion.landingOccupant(bot, landingBox()) != null) {
                return fail("entity_occupied");
            }
        }
        ActionPack pack = bot.getActionPack();
        pack.markControllerInput();
        pack.capPace(kind == Kind.SWIM ? Gait.SPRINT : Gait.WALK, "walked_step");

        if (kind == Kind.PUSH_OUT) {
            return tickPushOut(pack);
        }
        double dx = point.x - position.x;
        double dz = point.z - position.z;
        double distance = Math.hypot(dx, dz);
        if (arrived(distance)) {
            return finish();
        }
        spent += WalkedStepRules.tickCost(pack.paceClockWeight());
        if (spent > budget) {
            return fail("timeout");
        }
        if (distance > 3.0D || bot.getY() < cell.getY() - 3.6D) {
            return fail("left_course");
        }
        if (kind == Kind.DROP && distance <= WalkedStepRules.DROP_CENTRED) {
            // Over its hole: nothing to press, gravity does the step.
            pack.setForward(0.0F);
            pack.setStrafing(0.0F);
            pack.setJumping(false);
            return Result.RUNNING;
        }
        if (WalkedStepRules.brakes(kind) && WalkedStepRules.shouldBrake(distance, speed)) {
            pack.setForward(0.0F);
            pack.setStrafing(0.0F);
            return Result.RUNNING;
        }
        if (distance > 0.05D) {
            LookAction.lookHorizontallyAt(bot, new Vec3(point.x, bot.getY(), point.z));
        }
        // A swim straight up or down (the target is over the bot) has nothing to walk toward: the forward key would only drift it off its column.
        boolean overhead = kind == Kind.SWIM && distance < WalkedStepRules.SWIM_OVERHEAD_DISTANCE;
        pack.setForward(overhead ? 0.0F : WalkedStepRules.brakes(kind) ? (float) Math.min(1.0D, 0.4D + distance * 2.0D) : 1.0F);
        pack.setStrafing(0.0F);
        if (kind == Kind.SNEAK_SHIFT) {
            pack.setSneaking(true);
        } else if (kind != Kind.SWIM) {
            pack.setSprinting(false);
        }
        if (kind == Kind.SWIM && cell.getY() < bot.blockPosition().getY() && bot.isInWater()) {
            // A player dives by holding shift: the client adds a downward push of 0.04 per tick (LocalPlayer.aiStep -> goDownInWater); a
            // clientless bot has no such client code, so the swim step applies the same push. Without it a sinking body only falls at
            // about half a block per second.
            Vec3 velocity = bot.getDeltaMovement();
            bot.setDeltaMovement(velocity.x, velocity.y - WalkedStepRules.SWIM_DIVE_PUSH, velocity.z);
        }
        boolean grounded = supported(bot);
        boolean jump = WalkedStepRules.jumpNow(kind, grounded, bot.getY(), cell.getY(), bot.isInWater());
        if (kind == Kind.STEP_UP) {
            if (jump) {
                pack.jumpOnce();
            }
        } else {
            pack.setJumping(jump);
        }
        return Result.RUNNING;
    }

    private boolean arrived(double distance) {
        BlockPos here = bot.blockPosition();
        if (kind == Kind.SWIM) {
            // A stroke up out of the water ends in the air cell above the surface: feet in the cell is all there is to reach.
            boolean dry = !bot.level().getFluidState(cell).is(FluidTags.WATER)
                    && !bot.level().getFluidState(cell.above()).is(FluidTags.WATER);
            return here.equals(cell) && (bot.isInWater() || supported(bot) || dry);
        }
        if (WalkedStepRules.endsInCell(kind)) {
            return here.equals(cell) && supported(bot);
        }
        return distance <= (kind == Kind.SNEAK_SHIFT ? WalkedStepRules.SHIFT_TOLERANCE : WalkedStepRules.POINT_TOLERANCE)
                && supported(bot);
    }

    private AABB landingBox() {
        return bot.getBoundingBox().move(point.x - bot.getX(), cell.getY() - bot.getY(), point.z - bot.getZ());
    }

    private String firstTickRefusal() {
        ServerLevel level = bot.level();
        BlockPos here = bot.blockPosition();
        switch (kind) {
            case FLAT, STEP_UP, STEP_DOWN -> {
                // A bot that is falling or settling when the step starts stands one cell higher or lower than the caller saw: the
                // kind follows the height difference that is really there (a walk to the cell, up or down).
                Kind actual = WalkedStepRules.walkKindFor(cell.getY() - here.getY());
                if (actual != null) {
                    kind = actual;
                }
                return refusal(bot, cell, kind);
            }
            case SWIM, DROP -> {
                return refusal(bot, cell, kind);
            }
            case RECENTER, SNEAK_SHIFT -> {
                if (!WalkedStepRules.inCellEnvelope(
                        here.getX() - cell.getX(), here.getY() - cell.getY(), here.getZ() - cell.getZ())) {
                    // The point may lie a little over the edge of the bot's cell (a sneak shift to see the side face of its support) or in the
                    // cell it stands next to (the walk back from there): the bot's cell and the point's cell are the same or neighbours.
                    // This holds for every in-cell step (an anchored one names the cell that owns it, the same test applies); what keeps a
                    // step from wandering is the offset bound below (IN_CELL_MAX_OFFSET), not the cell test.
                    return "not_in_cell";
                }
                if (Math.hypot(point.x - bot.getX(), point.z - bot.getZ()) > WalkedStepRules.IN_CELL_MAX_OFFSET) {
                    return "too_far";
                }
                if (!supported(bot)) {
                    return "not_supported";
                }
                if (kind == Kind.SNEAK_SHIFT) {
                    // The floor the body stands on: the owning cell's for an anchored step (its bot may already overhang the next cell), the
                    // bot's own otherwise (its point, and so its cell, lies over the edge).
                    BlockPos support = (anchored ? cell : here).below();
                    if (level.getBlockState(support).getCollisionShape(level, support).isEmpty()
                            || Standability.isDangerous(level.getBlockState(support))) {
                        return "unsafe_support";
                    }
                }
                AABB target = bot.getBoundingBox().move(point.x - bot.getX(), 0.0D, point.z - bot.getZ());
                if (!level.noCollision(bot, target)) {
                    return "occupied";
                }
                return FakePlayerMotion.landingOccupant(bot, target) != null ? "entity_occupied" : null;
            }
            case PUSH_OUT -> {
                if (FakePlayerMotion.isBlockCollisionFree(bot)) {
                    return "not_overlapping";
                }
                pushDirection = choosePushDirection(bot);
                return pushDirection < 0 ? "no_push_direction" : null;
            }
        }
        return null;
    }

    /** Whether a push out has somewhere to go: the body overlaps a block and a free side is within a block. */
    public static boolean canPushOut(AIPlayerEntity bot) {
        return !FakePlayerMotion.isBlockCollisionFree(bot) && choosePushDirection(bot) >= 0;
    }

    /** The horizontal direction (index into {@code PUSH_DIRECTIONS}) with the least shift that frees the body, or -1. */
    private static int choosePushDirection(AIPlayerEntity bot) {
        double[] shifts = new double[PUSH_DIRECTIONS.length];
        for (int i = 0; i < shifts.length; i++) {
            shifts[i] = pushDistanceToFree(bot, i);
        }
        return WalkedStepRules.bestPushDirection(shifts);
    }

    /** The nearest clearance distance in one push direction, or infinity when this local nudge cannot prove a free side. */
    private static double pushDistanceToFree(AIPlayerEntity bot, int direction) {
        for (double shift = PUSH_SEARCH_STEP;
             shift <= WalkedStepRules.PUSH_OUT_MAX_SHIFT + 1.0E-9D;
             shift += PUSH_SEARCH_STEP) {
            AABB moved = bot.getBoundingBox().move(
                    PUSH_DIRECTIONS[direction][0] * shift, 0.0D, PUSH_DIRECTIONS[direction][1] * shift)
                    .deflate(BODY_EPSILON);
            if (bot.level().noBlockCollision(bot, moved)) {
                return shift;
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    private Result tickPushOut(ActionPack pack) {
        if (FakePlayerMotion.isBlockCollisionFree(bot)) {
            return finish();
        }
        if (ticks > WalkedStepRules.PUSH_OUT_MAX_TICKS) {
            return fail("push_timeout");
        }
        // The vanilla client nudges a body that overlaps a block toward the nearest free side (LocalPlayer.moveTowardsClosestSpace):
        // a small horizontal velocity, nothing else. The bot is free to leave the block it is in: collision only stops movement into one.
        int[] direction = PUSH_DIRECTIONS[pushDirection];
        Vec3 velocity = bot.getDeltaMovement();
        double speed = WalkedStepRules.pushSpeed(pushDistanceToFree(bot, pushDirection));
        bot.setDeltaMovement(direction[0] * speed, velocity.y, direction[1] * speed);
        return Result.RUNNING;
    }

    private Result finish() {
        end(kind == Kind.SNEAK_SHIFT);
        BotLog.action(bot, "walked_step_done", "kind", kind, "reason", reason, "ticks", ticks);
        finalResult = Result.DONE;
        return Result.DONE;
    }

    private Result fail(String why) {
        failure = why;
        end(false);
        BotLog.action(bot, "walked_step_failed", "kind", kind, "reason", reason, "why", why, "ticks", ticks);
        finalResult = Result.failed(why);
        return finalResult;
    }

    private void end(boolean keepSneak) {
        ended = true;
        ActionPack pack = bot.getActionPack();
        if (keepSneak) {
            pack.setForward(0.0F);
            pack.setStrafing(0.0F);
            pack.setJumping(false);
        } else {
            pack.stopMovement();
        }
    }

    /** Whether the step has ended (succeeded, failed or was cancelled): its own state, whatever step the pack runs now. */
    public boolean ended() {
        return ended;
    }

    /** How the step ended, or {@code null} while it is in flight or when it was cancelled. */
    public Result outcome() {
        return finalResult;
    }

    /** Lets go of every key of a step the owner abandons while it is in flight (a no-op after it has ended). */
    public void cancel() {
        if (!ended) {
            end(false);
        }
    }
}

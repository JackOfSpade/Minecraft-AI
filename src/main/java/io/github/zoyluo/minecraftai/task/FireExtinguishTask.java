package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.BucketAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.LookAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.SightClip;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.phys.AABB;

/**
 * Fire self-rescue: dispatched by DangerWatcher while the bot is burning. A burning player takes about a
 * heart per second for as long as the fire lasts (fifteen seconds after lava), so a human puts it out at
 * once. In the order a person reaches for it:
 * <ol>
 *   <li>a fire block inside the bot's own cells keeps re-igniting it: punch it out (a real, timed
 *       break through {@link BlockMiner});</li>
 *   <li>with a water bucket: place the water at the feet through {@link BucketAction} (a real item use)
 *       and, once the fire is out, pick that same source back up;</li>
 *   <li>otherwise walk into observed water (or a spot the rain reaches) within a few blocks.</li>
 * </ol>
 * Nothing here reads unobserved blocks for a decision: every water/rain cell must pass the same
 * line-of-sight gate as the rest of the bot's perception. Water is useless where it evaporates
 * ({@code WATER_EVAPORATES}: the Nether) and Fire Resistance makes the whole reflex unnecessary.
 * SurvivalGuard exempts this task, because it is itself the fix for the conditions the guard reacts to.
 */
public final class FireExtinguishTask extends AbstractTask {
    /** Horizontal search radius for observed water / rain. */
    static final int WATER_RADIUS = 8;
    private static final int MAX_ELAPSED = 160;
    private static final int PICKUP_RETRY_LIMIT = 40;
    /** A bot walks about one block per 5 ticks; do not start a walk the fire will outlast. */
    private static final int TICKS_PER_BLOCK = 6;
    /** Below this the fire is about to go out by itself; not worth pausing work for. */
    private static final int MIN_FIRE_TICKS = 30;
    /** Tight: stopping at the pool rim (0.6 default) leaves the bot on dry ground beside the water. */
    private static final double ARRIVAL_TOLERANCE = 0.2D;

    private enum Phase { EXTINGUISH, PICKUP }

    private Phase phase = Phase.EXTINGUISH;
    private final BlockMiner miner = new BlockMiner();
    private BlockPos walkTarget;
    private BlockPos placedSource;
    private int pickupTries;
    private int placeFailures;
    private int lastScanTick = -100;
    private int placedTick = -100;

    @Override
    public String name() {
        return "fire_extinguish";
    }

    @Override
    public String describe() {
        return "Fire extinguish phase=" + phase
                + (walkTarget == null ? "" : " -> " + walkTarget.toShortString());
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.9D, elapsed / 60.0D);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
        // Aborted (preempted, cancelled, the bot died or is being despawned): the placed source is still there.
        recoverPlacedWater(bot, "aborted");
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > MAX_ELAPSED) {
            miner.cancel(bot);
            recoverPlacedWater(bot, "timeout");
            fail("fire_extinguish_timeout");
            return;
        }
        if (bot.isInLava()) {
            // LavaEscapeTask owns lava contact; hand back rather than fight it for the controls.
            recoverPlacedWater(bot, "in_lava");
            fail("fire_extinguish_in_lava");
            return;
        }
        if (phase == Phase.PICKUP) {
            pickUpWater(bot);
            return;
        }
        if (!bot.isOnFire()) {
            miner.cancel(bot);
            bot.getActionPack().stopAll();
            if (placedSource != null) {
                phase = Phase.PICKUP;
                pickUpWater(bot);
                return;
            }
            BotLog.danger(bot, "fire_extinguished", "pos", bot.blockPosition().toShortString(),
                    "hp", (int) bot.getHealth(), "how", walkTarget == null ? "other" : "water_walk");
            complete();
            return;
        }
        // 1. A fire block in the bot's own cells re-ignites it every tick: punch it out first.
        BlockPos fire = fireBlockTouching(bot);
        if (fire != null) {
            bot.getActionPack().stopMovement();
            if (miner.target() == null || !miner.target().equals(fire)) {
                miner.begin(bot, fire);
            }
            BlockMiner.Status status = miner.tick(bot);
            if (status == BlockMiner.Status.FAILED) {
                BotLog.danger(bot, "fire_punch_failed", "at", fire.toShortString(), "why", miner.failureReason());
            }
            return;
        }
        // 2. Water bucket: real bucket use at the feet.
        if (placedSource == null && placeFailures < 8 && InventoryAction.countItem(bot, Items.WATER_BUCKET) > 0
                && !waterEvaporates(bot)) {
            bot.getActionPack().stopMovement();
            BlockPos feet = bot.blockPosition();
            ActionResult result = BucketAction.placeWater(bot, feet.below(), Direction.UP);
            if (result.isSuccess()) {
                placedSource = feet.immutable();
                placedTick = elapsed;
                BotLog.danger(bot, "fire_extinguish_water_placed", "at", feet.toShortString(),
                        "hp", (int) bot.getHealth());
                return;
            }
            placeFailures++;
            BotLog.danger(bot, "fire_extinguish_place_failed", "why", result.reason(), "tries", placeFailures);
            return;
        }
        // The placed water needs a few ticks to reach the bot's fire state; only when it evidently did not
        // (the bot is not in it) fall through to walking.
        if (placedSource != null && elapsed - placedTick <= 12) {
            return;
        }
        // 3. Observed water or rain nearby.
        if (walkTarget == null || bot.getActionPack().isWalkToIdle()) {
            if (elapsed - lastScanTick >= 10) {
                lastScanTick = elapsed;
                Optional<BlockPos> target = findWaterOrRain(bot);
                walkTarget = target.orElse(null);
                if (walkTarget != null) {
                    LookAction.lookAt(bot, walkTarget.getCenter());
                    bot.getActionPack().startWalkTo(walkTarget.getCenter(), ARRIVAL_TOLERANCE);
                }
            }
        }
        if (walkTarget == null && elapsed > 20) {
            recoverPlacedWater(bot, "no_means");
            fail("fire_extinguish_no_means");
        }
    }

    /** Fire is out and the bot stands in its own placed water: take the source back with the empty bucket. */
    private void pickUpWater(AIPlayerEntity bot) {
        if (placedSource == null || pickupTries++ >= PICKUP_RETRY_LIMIT) {
            BotLog.danger(bot, "fire_extinguish_pickup_abandoned", "tries", pickupTries);
            recoverPlacedWater(bot, "pickup_abandoned");
            complete();
            return;
        }
        if (bot.isOnFire()) {
            // Re-ignited (e.g. stepped out of the water): keep the water for the next attempt.
            phase = Phase.EXTINGUISH;
            return;
        }
        var fluid = bot.level().getFluidState(placedSource);
        if (!fluid.is(FluidTags.WATER)) {
            BotLog.danger(bot, "fire_extinguished", "pos", bot.blockPosition().toShortString(),
                    "hp", (int) bot.getHealth(), "how", "bucket_water_gone");
            complete();
            return;
        }
        ActionResult result = BucketAction.fillWaterSource(bot, placedSource);
        if (result.isSuccess()) {
            BotLog.danger(bot, "fire_extinguished", "pos", bot.blockPosition().toShortString(),
                    "hp", (int) bot.getHealth(), "how", "bucket_water_recovered");
            complete();
        }
    }

    /**
     * One best-effort attempt to take the water this task placed at the feet back, on every exit that is not the
     * normal PICKUP path (timeout, abort, death, despawn, lava, no means). A dead or removed bot cannot use a
     * bucket, and a source that is out of reach or no longer visible cannot be filled: in those cases the water
     * is left and that is logged ({@code fire_extinguish_water_left}) rather than silently forgotten. Idempotent:
     * the placed cell is forgotten after the attempt.
     */
    private void recoverPlacedWater(AIPlayerEntity bot, String why) {
        BlockPos source = placedSource;
        if (source == null) {
            return;
        }
        placedSource = null;
        String outcome;
        if (!bot.isAlive() || bot.isRemoved()) {
            outcome = "bot_gone";
        } else if (!bot.level().getFluidState(source).is(FluidTags.WATER)) {
            return; // the water is already gone (flowed away, or someone took it)
        } else {
            ActionResult result = BucketAction.fillWaterSource(bot, source);
            if (result.isSuccess()) {
                BotLog.danger(bot, "fire_extinguish_water_recovered", "at", source.toShortString(), "why", why);
                return;
            }
            outcome = result.reason();
        }
        BotLog.danger(bot, "fire_extinguish_water_left", "at", source.toShortString(), "why", why, "reason", outcome);
    }

    // ---- observation-gated scans (shared with the DangerWatcher trigger) ----

    /**
     * Whether the fire reflex applies at all: burning, not immune, water not evaporating here, and not in
     * lava (the lava reflex owns that). Cheap; checked on every scan.
     */
    static boolean isBurningWithoutImmunity(AIPlayerEntity bot) {
        return bot.isOnFire()
                && bot.getRemainingFireTicks() >= MIN_FIRE_TICKS
                && !bot.isInLava()
                && !bot.fireImmune()
                && !bot.hasEffect(MobEffects.FIRE_RESISTANCE);
    }

    static boolean waterEvaporates(AIPlayerEntity bot) {
        return Boolean.TRUE.equals(bot.level().environmentAttributes()
                .getValue(EnvironmentAttributes.WATER_EVAPORATES, bot.blockPosition()));
    }

    /**
     * Whether this bot has any way to put itself out right now: a water bucket, a fire block to punch, or
     * observed water / rain close enough to reach before the fire burns out.
     */
    static boolean hasMeans(AIPlayerEntity bot) {
        if (fireBlockTouching(bot) != null) {
            return true;
        }
        if (waterEvaporates(bot)) {
            return false;
        }
        if (InventoryAction.countItem(bot, Items.WATER_BUCKET) > 0) {
            return true;
        }
        return findWaterOrRain(bot).isPresent();
    }

    /** A fire block inside the bot's bounding box (cells that actually ignite it), or null. */
    static BlockPos fireBlockTouching(AIPlayerEntity bot) {
        AABB box = bot.getBoundingBox().deflate(0.001D);
        for (BlockPos pos : BlockPos.betweenClosed(
                BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            BlockState state = bot.level().getBlockState(pos);
            if (state.getBlock() instanceof BaseFireBlock) {
                return pos.immutable();
            }
        }
        return null;
    }

    /**
     * Whether a real view ray from the bot's eye reaches the water surface of {@code cell}. A flush pool is
     * invisible through its cell centre (the rim hides it) and its surface sits below the top face the block
     * observation rays aim at, so aim at points just under the surface itself. Same line-of-sight fairness as
     * the other observation gates: a ray that a wall stops sees nothing.
     */
    static boolean canSeeWaterSurface(AIPlayerEntity bot, BlockPos cell) {
        var world = bot.level();
        var fluid = world.getFluidState(cell);
        double radius = Math.max(1, io.github.zoyluo.minecraftai.MinecraftAiConfig.get().perception().radius());
        double y = cell.getY() + Math.max(0.1D, fluid.getHeight(world, cell) - 0.02D);
        double[][] offsets = {{0.0D, 0.0D}, {0.3D, 0.0D}, {-0.3D, 0.0D}, {0.0D, 0.3D}, {0.0D, -0.3D}};
        for (double[] offset : offsets) {
            net.minecraft.world.phys.Vec3 end = new net.minecraft.world.phys.Vec3(
                    cell.getX() + 0.5D + offset[0], y, cell.getZ() + 0.5D + offset[1]);
            if (bot.getEyePosition().distanceToSqr(end) > radius * radius) {
                continue;
            }
            // The pool cell is the ray's target, so it is never skipped; water, foliage or a fence in front of it are.
            var hit = SightClip.clip(world, bot.getEyePosition(), end,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.ANY, bot, cell);
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().equals(cell)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The nearest observed water cell (or standable cell under open rain) the bot can reach before the
     * fire would burn out on its own. Candidates are filtered by the cheap fluid/rain test first, but only
     * a cell that also passes the line-of-sight gate is ever returned.
     */
    static Optional<BlockPos> findWaterOrRain(AIPlayerEntity bot) {
        var world = bot.level();
        BlockPos origin = bot.blockPosition();
        int fireTicks = Math.max(0, bot.getRemainingFireTicks());
        int reachable = Math.min(WATER_RADIUS, fireTicks / TICKS_PER_BLOCK);
        if (reachable <= 0) {
            return Optional.empty();
        }
        List<BlockPos> candidates = new ArrayList<>();
        boolean raining = world.isRaining();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-reachable, -3, -reachable),
                origin.offset(reachable, 2, reachable))) {
            if ((world.getFluidState(pos).is(FluidTags.WATER) && world.getFluidState(pos.above()).isEmpty())
                    || (raining && world.isRainingAt(pos.above()) && Standability.isStandable(world, pos))) {
                candidates.add(pos.immutable());
            }
        }
        candidates.sort(Comparator.comparingDouble(pos -> pos.distSqr(origin)));
        int checked = 0;
        for (BlockPos candidate : candidates) {
            if (candidate.distSqr(origin) > (double) reachable * reachable) {
                break;
            }
            if (++checked > 40) {
                break;
            }
            boolean water = world.getFluidState(candidate).is(FluidTags.WATER);
            if (water ? canSeeWaterSurface(bot, candidate) : ObservableWorldQuery.canObserveCell(bot, candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}

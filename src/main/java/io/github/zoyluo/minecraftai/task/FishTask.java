package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.action.InteractAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.LookAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class FishTask extends AbstractTask {
    private enum Phase {
        FIND_WATER,
        MOVE_TO_WATER,
        CAST,
        WAIT_BITE,
        REEL,
        COLLECT
    }

    private static final int SEARCH_RADIUS = 12;
    private static final int WAIT_BITE_FALLBACK_TICKS = 600;
    private static final int COLLECT_TICKS = 80;
    private static final int DEFAULT_MAX_TICKS = 6000;

    private final int maxCatches;
    private final int maxTicks;

    private Phase phase = Phase.FIND_WATER;
    private BlockPos waterPos;
    private BlockPos standPos;
    private int phaseTicks;
    private int catches;
    private int inventoryBeforeReel;
    private boolean collectSweepAttempted;

    public FishTask(int maxCatches, int maxTicks) {
        this.maxCatches = Math.max(1, maxCatches);
        this.maxTicks = Math.max(200, maxTicks);
    }

    public FishTask(int maxCatches) {
        this(maxCatches, DEFAULT_MAX_TICKS);
    }

    @Override
    public String name() {
        return "fish";
    }

    @Override
    public String describe() {
        return "Fishing " + catches + "/" + maxCatches;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        double catchProgress = Math.min(0.95D, catches / (double) maxCatches);
        double timeProgress = Math.min(0.90D, elapsed / (double) maxTicks);
        return Math.max(catchProgress, timeProgress * 0.25D);
    }

    @Override
    public boolean isWaiting() {
        return phase == Phase.WAIT_BITE;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        if (!equipRod(bot)) {
            fail("need_fishing_rod");
            return;
        }
        phase = Phase.FIND_WATER;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > maxTicks) {
            if (catches > 0) {
                complete();
            } else {
                fail("fish_timeout");
            }
            return;
        }
        switch (phase) {
            case FIND_WATER -> findWater(bot);
            case MOVE_TO_WATER -> moveToWater(bot);
            case CAST -> cast(bot);
            case WAIT_BITE -> waitBite(bot);
            case REEL -> reel(bot);
            case COLLECT -> collect(bot);
        }
    }

    private void findWater(AIPlayerEntity bot) {
        if (!equipRod(bot)) {
            fail("need_fishing_rod");
            return;
        }
        WaterChoice choice = nearestWater(bot).orElse(null);
        if (choice == null) {
            fail("no_water_nearby");
            return;
        }
        waterPos = choice.water();
        standPos = choice.stand();
        if (bot.blockPosition().distSqr(standPos) <= 2.25D) {
            bot.getActionPack().stopMovement();
            transition(Phase.CAST);
            return;
        }
        ActionResult result = bot.getActionPack().startPathTo(standPos);
        if (result.isFailed()) {
            bot.getActionPack().startWalkTo(Vec3.atCenterOf(standPos));
        }
        transition(Phase.MOVE_TO_WATER);
    }

    private void moveToWater(AIPlayerEntity bot) {
        if (bot.blockPosition().distSqr(standPos) <= 2.25D) {
            bot.getActionPack().stopAll();
            transition(Phase.CAST);
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && phaseTicks > 20) {
            ActionResult result = bot.getActionPack().startPathTo(standPos);
            if (result.isFailed()) {
                bot.getActionPack().startWalkTo(Vec3.atCenterOf(standPos));
            }
        }
        phaseTicks++;
    }

    private void cast(AIPlayerEntity bot) {
        if (!equipRod(bot)) {
            fail("need_fishing_rod");
            return;
        }
        bot.getActionPack().stopMovement();
        LookAction.lookAt(bot, waterPos.getCenter().add(0.0D, 0.15D, 0.0D));
        if (currentHook(bot).isPresent()) {
            transition(Phase.WAIT_BITE);
            return;
        }
        ActionResult result = InteractAction.useItemInAir(bot, InteractionHand.MAIN_HAND);
        if (result.isFailed()) {
            fail("cast_failed:" + result.reason());
            return;
        }
        transition(Phase.WAIT_BITE);
    }

    private void waitBite(AIPlayerEntity bot) {
        phaseTicks++;
        currentHook(bot).ifPresentOrElse(
                hook -> LookAction.lookAt(bot, hook.position()),
                () -> LookAction.lookAt(bot, waterPos.getCenter().add(0.0D, 0.15D, 0.0D)));
        Optional<FishingHook> hook = currentHook(bot);
        if (hook.isEmpty()) {
            if (phaseTicks > 30) {
                transition(Phase.CAST);
            }
            return;
        }
        if (hasBite(bot, hook.get()) || phaseTicks >= WAIT_BITE_FALLBACK_TICKS) {
            transition(Phase.REEL);
        }
    }

    private void reel(AIPlayerEntity bot) {
        if (!equipRod(bot)) {
            fail("need_fishing_rod");
            return;
        }
        currentHook(bot).ifPresent(hook -> LookAction.lookAt(bot, hook.position()));
        inventoryBeforeReel = HarvestCore.totalInventoryCount(bot);
        collectSweepAttempted = false;
        ActionResult result = InteractAction.useItemInAir(bot, InteractionHand.MAIN_HAND);
        if (result.isFailed()) {
            fail("reel_failed:" + result.reason());
            return;
        }
        transition(Phase.COLLECT);
    }

    private void collect(AIPlayerEntity bot) {
        phaseTicks++;
        HarvestCore.forcePickupNearby(bot, null);
        HarvestCore.chaseDrop(bot, null, 8.0D);
        if (phaseTicks < COLLECT_TICKS) {
            return;
        }
        if (HarvestCore.totalInventoryCount(bot) > inventoryBeforeReel) {
            catches++;
        } else if (!collectSweepAttempted && HarvestCore.nearestDrop(bot, null, 8.0D).isPresent()) {
            collectSweepAttempted = true;
            HarvestCore.sweepPickup(bot, null, 8);
            phaseTicks = 0;
            return;
        }
        if (catches >= maxCatches) {
            bot.getActionPack().stopAll();
            complete();
            return;
        }
        // chaseDrop() above can leave the bot mid pickup-nudge (sneaking held true) on the very
        // tick this phase ends; nothing else clears it once collect() stops being ticked, which
        // would otherwise deadlock any paused task waiting on ActionPack.hasActiveActions().
        bot.getActionPack().stopAll();
        transition(Phase.FIND_WATER);
    }

    private boolean equipRod(AIPlayerEntity bot) {
        return InventoryAction.findItem(bot, Items.FISHING_ROD)
                .stream()
                .anyMatch(slot -> InventoryAction.equipFromSlot(bot, slot) >= 0);
    }

    private Optional<WaterChoice> nearestWater(AIPlayerEntity bot) {
        BlockPos origin = bot.blockPosition();
        return BlockPos.betweenClosedStream(
                        origin.offset(-SEARCH_RADIUS, -2, -SEARCH_RADIUS),
                        origin.offset(SEARCH_RADIUS, 3, SEARCH_RADIUS))
                .filter(pos -> io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlock(bot, pos))
                .filter(pos -> bot.level().getFluidState(pos).is(FluidTags.WATER))
                .map(BlockPos::immutable)
                .map(pos -> waterChoice(bot, pos))
                .filter(choice -> choice != null)
                .min(Comparator.comparingDouble(choice -> choice.water().distSqr(origin)));
    }

    private WaterChoice waterChoice(AIPlayerEntity bot, BlockPos water) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos stand = water.relative(direction);
            if (Standability.isStandable(bot.level(), stand)) {
                return new WaterChoice(water, stand);
            }
        }
        return null;
    }

    private Optional<FishingHook> currentHook(AIPlayerEntity bot) {
        if (bot.fishing != null && bot.fishing.isAlive()) {
            return Optional.of(bot.fishing);
        }
        AABB box = bot.getBoundingBox().inflate(32.0D);
        return bot.level()
                .getEntitiesOfClass(FishingHook.class, box,
                        hook -> hook.isAlive() && hook.getPlayerOwner() == bot)
                .stream()
                .min(Comparator.comparingDouble(bot::distanceTo));
    }

    // Fields whose reflective lookup has already failed under both mapped names during this JVM
    // run -- guards the one-time warning below so a mapping break is logged once, not every tick.
    private static final Set<String> UNRESOLVED_FISH_BOBBER_FIELDS = ConcurrentHashMap.newKeySet();

    private boolean hasBite(AIPlayerEntity bot, FishingHook hook) {
        if (hook.getHookedIn() != null) {
            return true;
        }
        return booleanField(bot, hook, "caughtFish", "field_23232")
                || intField(bot, hook, "hookCountdown", "field_7173") > 0;
    }

    private static boolean booleanField(AIPlayerEntity bot, Object target, String named, String intermediary) {
        Object value = fieldValue(bot, target, named, intermediary);
        return value instanceof Boolean bool && bool;
    }

    private static int intField(AIPlayerEntity bot, Object target, String named, String intermediary) {
        Object value = fieldValue(bot, target, named, intermediary);
        return value instanceof Integer integer ? integer : 0;
    }

    private static Object fieldValue(AIPlayerEntity bot, Object target, String named, String intermediary) {
        Class<?> type = target.getClass();
        for (String fieldName : new String[]{named, intermediary}) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException ignored) {
                // Try the next runtime name. Yarn dev runs use named fields, remapped jars use intermediary names.
            }
        }
        // Both the yarn and intermediary names failed to resolve -- without this, hasBite()
        // silently falls back to always-false with no diagnostic anywhere if a future mapping
        // change renames the field again. Log once per field so the break is visible.
        if (UNRESOLVED_FISH_BOBBER_FIELDS.add(named)) {
            BotLog.warn(LogCategory.TASK, bot, "fish_bobber_field_unresolved", "field", named);
        }
        return null;
    }

    private void transition(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    private record WaterChoice(BlockPos water, BlockPos stand) {
    }
}

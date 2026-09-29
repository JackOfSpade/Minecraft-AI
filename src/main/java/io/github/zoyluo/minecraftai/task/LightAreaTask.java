package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import java.util.ArrayList;
import java.util.List;

public final class LightAreaTask extends AbstractTask {
    private enum Phase {
        SCAN,
        WALK,
        PLACE,
        DONE
    }

    private final int radius;
    private final int maxTorches;
    private final boolean skipSurfaceCells;
    // The fixed pool of observable, dark, placeable floor cells within radius. Computed exactly
    // once (in scan()), never rescanned mid-task: this is what keeps each decision cheap even
    // though it drives every subsequent torch choice via TorchPlacementPlanner's prediction.
    private final Set<BlockPos> cells = new LinkedHashSet<>();
    private final Map<BlockPos, Integer> worldBlockLight = new HashMap<>();
    // Torches this task itself has placed, used to PREDICT light at other cells instead of
    // trusting a live world read that hasn't propagated yet (see TorchPlacementPlanner).
    private final List<BlockPos> placedTorches = new ArrayList<>();
    // Cells that turned out unreachable or unplaceable; excluded from future selection so the
    // planner doesn't loop back onto them.
    private final Set<BlockPos> excluded = new HashSet<>();
    private Phase phase = Phase.SCAN;
    private BlockPos target;
    private BlockPos standPos;
    private int placed;
    private int threshold;

    public LightAreaTask(int radius, int maxTorches) {
        this(radius, maxTorches, false);
    }

    private LightAreaTask(int radius, int maxTorches, boolean skipSurfaceCells) {
        this.radius = Math.max(2, radius);
        this.maxTorches = Math.max(1, maxTorches);
        this.skipSurfaceCells = skipSurfaceCells;
    }

    /**
     * The lighting the danger watcher starts on its own (night top-up, dark-spot reflex): it never
     * places a torch on, or counts as dark, a cell that is on the surface (see {@link SurfaceCheck}).
     * Explicit requests (the tools, the command, task-board jobs) use the public constructor and light
     * wherever they are asked to.
     */
    static LightAreaTask automatic(int radius, int maxTorches) {
        return new LightAreaTask(radius, maxTorches, true);
    }

    @Override
    public String name() {
        return "light_area";
    }

    @Override
    public String describe() {
        return "Lighting radius=" + radius + " placed=" + placed + "/" + maxTorches + " phase=" + phase;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return Math.min(0.95D, (double) placed / maxTorches);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.SCAN;
        cells.clear();
        worldBlockLight.clear();
        placedTorches.clear();
        excluded.clear();
        target = null;
        standPos = null;
        placed = 0;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 1800) {
            // Include phase+placed together: the timeout can fire in any of the SCAN/WALK/PLACE phases,
            // and just looking at "light_area_timeout" alone can't tell whether we failed to find a target,
            // failed to walk to a torch spot, or placement kept failing.
            fail("light_area_timeout phase=" + phase + " placed=" + placed + "/" + maxTorches);
            return;
        }
        if (InventoryAction.countItem(bot, Items.TORCH) <= 0) {
            if (placed > 0) {
                complete();
            } else {
                fail("missing minecraft:torch x1");
            }
            return;
        }
        switch (phase) {
            case SCAN -> scan(bot);
            case WALK -> walk(bot);
            case PLACE -> place(bot);
            case DONE -> complete();
        }
    }

    private void scan(AIPlayerEntity bot) {
        BlockPos origin = bot.blockPosition();
        threshold = MinecraftAiConfig.get().night().torchLightThreshold();
        var world = bot.level();
        BlockPos.betweenClosedStream(origin.offset(-radius, -2, -radius), origin.offset(radius, 3, radius))
                .map(BlockPos::immutable)
                .filter(pos -> !pos.equals(origin) && !pos.equals(origin.above()))
                .filter(pos -> io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveCollider(bot, pos.below()))
                .filter(pos -> isDarkFloorCell(world, pos, threshold))
                .forEach(pos -> {
                    cells.add(pos);
                    worldBlockLight.put(pos, world.getBrightness(LightLayer.BLOCK, pos));
                });
        if (skipSurfaceCells) {
            int before = cells.size();
            // One memo per scan: each candidate used to read its own column to the top of the world.
            SurfaceColumnMemo memo = new SurfaceColumnMemo();
            Set<BlockPos> underRoof = TorchPlacementPlanner.withoutSurfaceCells(
                    cells, cell -> memo.isOnSurface(cell.getX(), cell.getY(), cell.getZ(),
                            () -> SurfaceCheck.isOnSurface(world, cell)));
            cells.retainAll(underRoof);
            worldBlockLight.keySet().retainAll(underRoof);
            if (cells.size() < before) {
                BotLog.action(bot, "light_area_surface_cells_skipped", "skipped", before - cells.size(), "kept", cells.size());
            }
        }
        pickNextTarget(bot);
    }

    private void pickNextTarget(AIPlayerEntity bot) {
        Set<BlockPos> available = new LinkedHashSet<>(cells);
        available.removeAll(excluded);
        target = TorchPlacementPlanner.chooseNext(
                available, worldBlockLight, placedTorches, bot.blockPosition(), threshold);
        standPos = null;
        phase = target == null ? Phase.DONE : Phase.WALK;
    }

    private void walk(AIPlayerEntity bot) {
        if (target == null) {
            pickNextTarget(bot);
            return;
        }
        if (bot.getEyePosition().distanceTo(target.getCenter()) <= 4.0D) {
            bot.getActionPack().stopAll();
            phase = Phase.PLACE;
            return;
        }
        if (standPos == null) {
            standPos = adjacentStandPos(bot, target);
        }
        if (standPos == null) {
            // Give up on this torch spot and pick a fresh one on the next tick: if it eventually
            // times out in WALK, just looking at "light_area_timeout phase=WALK" alone can't tell
            // whether we kept failing to reach the same spot, or skipped several unreachable spots
            // in a row.
            BotLog.action(bot, "light_area_target_unreachable", "pos", target.toShortString());
            excluded.add(target);
            target = null;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startPathTo(standPos);
        }
    }

    private void place(AIPlayerEntity bot) {
        int slot = InventoryAction.findItem(bot, Items.TORCH).orElse(-1);
        if (slot < 0) {
            fail("missing minecraft:torch x1");
            return;
        }
        if (InventoryAction.equipFromSlot(bot, slot) < 0) {
            fail("cannot_equip_torch");
            return;
        }
        ActionResult result = BuildAction.placeBlockAt(bot, target);
        if (result.isSuccess()) {
            placed++;
            placedTorches.add(target);
            cells.remove(target);
        } else {
            excluded.add(target);
        }
        target = null;
        if (placed >= maxTorches) {
            complete();
        } else {
            pickNextTarget(bot);
        }
    }

    /**
     * An observable air cell with a solid floor below it, currently below the light threshold --
     * the same floor/air test the old scan() used, now also the definition of a "dark spawnable
     * cell" that {@link TorchPlacementPlanner} tries to bring up to the threshold.
     */
    private static boolean isDarkFloorCell(Level world, BlockPos pos, int threshold) {
        return world.getBlockState(pos).isAir()
                && !world.getBlockState(pos.below()).isAir()
                && world.getBrightness(LightLayer.BLOCK, pos) < threshold;
    }

    private static BlockPos adjacentStandPos(AIPlayerEntity bot, BlockPos target) {
        if (io.github.zoyluo.minecraftai.pathfinding.Standability.isStandable(bot.level(), target)) {
            return target;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = target.relative(direction);
            if (io.github.zoyluo.minecraftai.pathfinding.Standability.isStandable(bot.level(), candidate)) {
                return candidate;
            }
        }
        return null;
    }
}

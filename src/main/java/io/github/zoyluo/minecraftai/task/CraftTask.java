package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.craft.CraftingHelper;
import io.github.zoyluo.minecraftai.craft.RecipeRegistry;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.mode.FakePlayerMotion;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class CraftTask extends AbstractTask {
    private enum Phase {
        PLANNING,
        ENSURING_TABLE,
        CRAFTING,
        RECLAIMING_TABLE
    }

    private final Item target;
    private final int targetCount;
    private Phase phase = Phase.PLANNING;
    private CraftingHelper.CraftPlan plan;
    private int nextStep;
    private int craftedCount;
    // Set only when THIS task itself placed a carried crafting table to satisfy a 3x3 recipe --
    // never for a pre-existing/borrowed table -- so it knows to mine it back afterward instead of
    // permanently donating it to the world.
    private BlockPos selfPlacedTablePos;
    private final BlockMiner tableReclaimMiner = new BlockMiner();
    private int reclaimTicks;

    // Placement relocation: when no acceptable placement cell exists around the bot's current
    // stance (e.g. every horizontal neighbour is a torch/solid block and the one open cell has no
    // visible support face -- observed live: a bot standing at its own dig-down staircase entrance
    // gave up instantly with place_crafting_table_failed: support_face_not_visible even though open,
    // legal placement space existed a few blocks away), walk to a nearby reachable stance instead of
    // failing immediately. Mirrors DigDownTask's own entry-relocation search (bounded candidate list,
    // walk-only startSurfacePathTo, no pillaring/digging).
    private static final int TABLE_PLACEMENT_RELOCATION_RADIUS = 6;
    private static final int TABLE_PLACEMENT_RELOCATION_LIMIT = 200; // 10s: generous for a <=6-block local search
    private List<BlockPos> tablePlacementRelocationCandidates;
    private int tablePlacementRelocationIndex;
    private BlockPos tablePlacementRelocationTarget;
    private int tablePlacementRelocationStartElapsed;
    private int tablePlacementRelocationAttempts;
    private String tablePlacementRelocationLastFailure = "";

    /**
     * Bounds RECLAIMING_TABLE. Must clear BlockMiner's own 200-tick mining ceiling with room to
     * spare for the pickup delay and walk-over that follow, so the miner's own cap always has a
     * chance to resolve (DONE or FAILED) before this outer best-effort guard does.
     */
    private static final int RECLAIM_TIMEOUT_TICKS = 550;

    public CraftTask(Item target, int targetCount) {
        this.target = target;
        this.targetCount = Math.max(1, targetCount);
    }

    @Override
    public String name() {
        return "craft";
    }

    @Override
    public String describe() {
        return "Crafting " + BuiltInRegistries.ITEM.getKey(target) + " x" + targetCount + " phase=" + phase;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        if (plan == null || plan.steps().isEmpty()) {
            return 0.0D;
        }
        return Math.min(0.95D, (double) nextStep / plan.steps().size());
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.PLANNING;
        selfPlacedTablePos = null;
        reclaimTicks = 0;
        resetTablePlacementRelocation();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 900) {
            // "craft_timeout" alone does not say which phase/step it stalled in; the generic
            // task_failed line has no other field for that, so record it here before failing.
            BotLog.warn(LogCategory.TASK, bot, "craft_stalled", "phase", phase,
                    "step", nextStep, "total_steps", plan == null ? 0 : plan.steps().size());
            fail("craft_timeout");
            return;
        }
        switch (phase) {
            case PLANNING -> plan(bot);
            case ENSURING_TABLE -> ensureTable(bot);
            case CRAFTING -> craftNext(bot);
            case RECLAIMING_TABLE -> reclaimTable(bot);
        }
    }

    private void plan(AIPlayerEntity bot) {
        // Idempotent short-circuit: for functional-block targets like a crafting table/furnace, if
        // one is already available nearby (within reach) or already in inventory, complete
        // immediately instead of wasting materials crafting a duplicate.
        if (utilityAlreadyAvailable(bot)) {
            BotLog.action(bot, "craft_skipped_already_available", "item", BuiltInRegistries.ITEM.getKey(target).toString());
            complete();
            return;
        }
        boolean tableAvailable = WorkshopLocator.hasNearbyCraftingTable(bot)
                || InventoryAction.findItem(bot, Items.CRAFTING_TABLE).isPresent();
        plan = CraftingHelper.plan(bot, target, targetCount, tableAvailable);
        if (!plan.success()) {
            fail("need: " + plan.missingDescription());
            return;
        }
        if (plan.steps().isEmpty()) {
            complete();
            return;
        }
        phase = Phase.CRAFTING;
    }

    private void ensureTable(AIPlayerEntity bot) {
        if (WorkshopLocator.hasNearbyCraftingTable(bot)) {
            phase = Phase.CRAFTING;
            resetTablePlacementRelocation();
            return;
        }
        OptionalInt tableSlot = InventoryAction.findItem(bot, Items.CRAFTING_TABLE);
        if (tableSlot.isEmpty()) {
            fail("need: minecraft:crafting_table x1");
            return;
        }
        if (tablePlacementRelocationTarget != null) {
            tickTablePlacementRelocation(bot, tableSlot.getAsInt());
            return;
        }
        BlockPos placePos = adjacentAir(bot);
        if (placePos == null) {
            // No acceptable cell around the current stance (e.g. every horizontal neighbour is
            // blocked and the one open cell has no visible support face) -- try walking to a
            // nearby reachable stance instead of giving up immediately.
            if (!startNextTablePlacementRelocation(bot)) {
                fail("place_crafting_table_failed:no_reachable_placement");
            }
            return;
        }
        placeTableAt(bot, tableSlot.getAsInt(), placePos);
    }

    private void placeTableAt(AIPlayerEntity bot, int tableSlot, BlockPos placePos) {
        InventoryAction.equipFromSlot(bot, tableSlot);
        // A real jump-arc/fall landing can leave the server-side onGround bit stale for one tick
        // even though the bot's current cell is a genuine, collision-verified stand (the same
        // clientless fake-player quirk AcquireWaterTask's ascent placements already account for).
        // Publish that fact before this precise placement; never do this for a genuinely
        // unsupported pose.
        Standability.clearCache();
        if (!bot.onGround() && Standability.isStandable(bot.level(), bot.blockPosition())) {
            bot.setOnGround(true);
        }
        ActionResult result = BuildAction.placeBlockAt(bot, placePos);
        if (result.isFailed()) {
            fail("place_crafting_table_failed: " + result.reason());
            return;
        }
        // This table was borrowed from inventory, not an existing station -- reclaim it once the
        // whole plan finishes instead of permanently donating it to the world.
        selfPlacedTablePos = placePos;
        phase = Phase.CRAFTING;
        resetTablePlacementRelocation();
    }

    private void resetTablePlacementRelocation() {
        tablePlacementRelocationCandidates = null;
        tablePlacementRelocationIndex = 0;
        tablePlacementRelocationTarget = null;
        tablePlacementRelocationAttempts = 0;
        tablePlacementRelocationLastFailure = "";
    }

    /**
     * Walk-only (no digging, no pillaring), bounded local search for a stance from which an
     * acceptable placement cell exists. Candidates are every standable cell within {@link
     * #TABLE_PLACEMENT_RELOCATION_RADIUS} blocks of the bot's current position, nearest first.
     * Mirrors DigDownTask's own entry-relocation search.
     */
    private boolean startNextTablePlacementRelocation(AIPlayerEntity bot) {
        if (tablePlacementRelocationCandidates == null) {
            tablePlacementRelocationCandidates = nearbyStandableStances(bot);
            tablePlacementRelocationIndex = 0;
        }
        while (tablePlacementRelocationIndex < tablePlacementRelocationCandidates.size()) {
            BlockPos candidate = tablePlacementRelocationCandidates.get(tablePlacementRelocationIndex++);
            tablePlacementRelocationAttempts++;
            if (!Standability.isStandable(bot.level(), candidate)) {
                tablePlacementRelocationLastFailure = "candidate_became_unavailable";
                continue;
            }
            ActionResult result = bot.getActionPack().startSurfacePathTo(candidate);
            if (result.isFailed()) {
                tablePlacementRelocationLastFailure = result.reason();
                continue;
            }
            BlockPos resolved = bot.getActionPack().activePathGoal();
            if (resolved == null || !resolved.equals(candidate)) {
                bot.getActionPack().stopAll();
                tablePlacementRelocationLastFailure = "endpoint_not_exact";
                continue;
            }
            tablePlacementRelocationTarget = candidate;
            tablePlacementRelocationStartElapsed = elapsed;
            BotLog.action(bot, "craft_table_placement_relocation_path",
                    "to", candidate.toShortString(), "attempt", tablePlacementRelocationAttempts);
            return true;
        }
        bot.getActionPack().stopAll();
        BotLog.warn(LogCategory.TASK, bot, "craft_table_placement_relocation_exhausted",
                "attempted", tablePlacementRelocationAttempts, "last", tablePlacementRelocationLastFailure);
        return false;
    }

    private void tickTablePlacementRelocation(AIPlayerEntity bot, int tableSlot) {
        if (elapsed - tablePlacementRelocationStartElapsed > TABLE_PLACEMENT_RELOCATION_LIMIT) {
            bot.getActionPack().stopAll();
            fail("place_crafting_table_failed:no_reachable_placement");
            return;
        }
        BlockPos current = bot.blockPosition();
        if (current.equals(tablePlacementRelocationTarget)) {
            bot.getActionPack().stopAll();
            tablePlacementRelocationTarget = null;
            BlockPos placePos = adjacentAir(bot);
            if (placePos != null) {
                placeTableAt(bot, tableSlot, placePos);
                return;
            }
            // Arrived, but this stance's own candidate placement cell turned out unacceptable
            // (e.g. the geometry changed) -- keep searching the remaining candidates.
            if (!startNextTablePlacementRelocation(bot)) {
                fail("place_crafting_table_failed:no_reachable_placement");
            }
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            tablePlacementRelocationLastFailure = "path_ended_before_target";
            tablePlacementRelocationTarget = null;
            if (!startNextTablePlacementRelocation(bot)) {
                fail("place_crafting_table_failed:no_reachable_placement");
            }
        }
    }

    /** Every standable cell within {@link #TABLE_PLACEMENT_RELOCATION_RADIUS} blocks, nearest first. */
    private static List<BlockPos> nearbyStandableStances(AIPlayerEntity bot) {
        BlockPos origin = bot.blockPosition();
        int radius = TABLE_PLACEMENT_RELOCATION_RADIUS;
        long radiusSquared = (long) radius * radius;
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    long distSq = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (distSq > radiusSquared) {
                        continue;
                    }
                    BlockPos candidate = origin.offset(dx, dy, dz).immutable();
                    if (Standability.isStandable(bot.level(), candidate)) {
                        candidates.add(candidate);
                    }
                }
            }
        }
        candidates.sort(java.util.Comparator.comparingDouble(pos -> pos.distSqr(origin)));
        return candidates;
    }

    private void craftNext(AIPlayerEntity bot) {
        if (nextStep >= plan.steps().size()) {
            if (selfPlacedTablePos != null) {
                phase = Phase.RECLAIMING_TABLE;
                reclaimTicks = 0;
                return;
            }
            complete();
            return;
        }
        CraftingHelper.CraftStep step = plan.steps().get(nextStep);
        RecipeRegistry.Recipe recipe = step.recipe();
        // Preflight against the inventory as it stands right now (table still carried, if any) so
        // a craft that can never fit is caught before placing/consuming a carried table only to
        // silently free just enough room to mask a genuinely full inventory from the caller.
        PreparedCraft prepared = prepareCraft(bot, step);
        if (prepared.missingIngredient() != null) {
            fail("need: " + describeIngredient(
                    prepared.missingIngredient(), prepared.missingCount()));
            return;
        }
        if (prepared.remainderOverflow() != null) {
            fail("craft_remainder_capacity:item=" + prepared.remainderOverflow());
            return;
        }
        if (prepared.availableOutput() < step.outputCount()) {
            fail("craft_output_capacity:item=" + BuiltInRegistries.ITEM.getKey(recipe.output())
                    + ":count=" + step.outputCount()
                    + ":available=" + prepared.availableOutput());
            return;
        }
        // A 3x3 recipe needs a real local table.  A carried table is intentionally not enough:
        // ENSURING_TABLE reuses an existing table first, otherwise places the carried one.
        if (recipe.needsCraftingTable()
                && !WorkshopLocator.hasNearbyCraftingTable(bot)) {
            phase = Phase.ENSURING_TABLE;
            return;
        }
        commitPreparedCraft(bot, prepared);
        BotLog.action(bot, "craft_atomic",
                "item", BuiltInRegistries.ITEM.getKey(recipe.output()).toString(),
                "count", step.outputCount());
        if (recipe.output() == target) {
            craftedCount += step.outputCount();
        }
        nextStep++;
    }

    /**
     * Mines back down and re-collects a crafting table this task itself placed, through the same
     * real, tool-and-hardness-paced survival mining as any other block -- no instant/creative-style
     * shortcut, matching this mod's strict_survival design throughout. Best-effort: a stuck reclaim
     * must not turn an already-successful craft into a failure.
     */
    private void reclaimTable(AIPlayerEntity bot) {
        if (selfPlacedTablePos == null || InventoryAction.countItem(bot, Items.CRAFTING_TABLE) > 0) {
            selfPlacedTablePos = null;
            finishReclaim(bot);
            return;
        }
        if (++reclaimTicks > RECLAIM_TIMEOUT_TICKS) {
            // Best-effort reclaim gives up here silently otherwise: the craft still completes, so
            // nothing else would ever record that a carried crafting table was permanently lost.
            BotLog.warn(LogCategory.TASK, bot, "craft_table_reclaim_abandoned",
                    "reason", "timeout", "pos", selfPlacedTablePos.toShortString());
            selfPlacedTablePos = null;
            finishReclaim(bot);
            return;
        }
        BlockState state = bot.level().getBlockState(selfPlacedTablePos);
        if (state.is(Blocks.CRAFTING_TABLE)) {
            if (tableReclaimMiner.target() == null) {
                // The table only has somewhere to land if a slot is free; the craft that just
                // finished can leave zero free slots (its output filled the slot the table
                // vacated).
                InventoryAction.dropJunkUntilFreeSlots(bot, 1, 16);
                tableReclaimMiner.begin(bot, selfPlacedTablePos);
            }
            if (tableReclaimMiner.tick(bot) == BlockMiner.Status.FAILED) {
                BotLog.warn(LogCategory.TASK, bot, "craft_table_reclaim_abandoned",
                        "reason", "mine_failed", "pos", selfPlacedTablePos.toShortString());
                selfPlacedTablePos = null;
                finishReclaim(bot);
            }
            return;
        }
        if (!state.isAir()) {
            // Something else already claimed/replaced our placed block -- never chase a block
            // this task no longer owns.
            BotLog.warn(LogCategory.TASK, bot, "craft_table_reclaim_abandoned",
                    "reason", "position_claimed", "pos", selfPlacedTablePos.toShortString());
            selfPlacedTablePos = null;
            finishReclaim(bot);
            return;
        }
        // The table is down as a natural drop. strict_survival denies HarvestCore's forced
        // pickup, and completing the instant the block breaks can race the item's vanilla pickup
        // delay before the caller moves the bot elsewhere -- walk over it like any ordinary item
        // so vanilla's own proximity pickup collects it, then finish once it lands in inventory.
        HarvestCore.chaseDropAnyOf(bot, Set.of(Items.CRAFTING_TABLE), 4.0D);
    }

    /**
     * Completes RECLAIMING_TABLE from every exit path. The final approach tick before a successful
     * pickup can leave the bot mid pickup-nudge -- {@code FakePlayerMotion.nudgeWithinBlockToward}
     * sets sneaking true to hold it on the ledge for that nudge -- and nothing else clears it once
     * this task stops calling {@code HarvestCore.chaseDropAnyOf}. A dangling sneak (or any other
     * leftover action-pack state) makes {@code ActionPack.hasActiveActions()} report true forever,
     * which permanently blocks DangerWatcher's paused-task resume (it requires actions to be idle)
     * and deadlocks the bot with its mission step stuck PAUSED. Stop the action pack before handing
     * control back so a fresh task, or the resume path, always starts from a clean slate.
     *
     * <p>That same chase can also leave the bot's real sub-block stance nudged toward the reclaimed
     * cell (or otherwise off-center, e.g. from the approach the reclaim mining itself needed) without
     * ever moving it to a different block -- {@code bot.getBlockPos()} still reads the original
     * cell. Vanilla's own placement check ({@code World.canPlace}) does not exclude the placer from
     * the destination's entity-collision test the way this task's own candidate scan does, so an
     * off-center stance can let the bot's own hitbox clip the neighbouring cell just enough to fail
     * a later {@code BuildAction.placeBlockAt} at that exact cell -- e.g. the very next repair cycle
     * placing another carried table back at this same reclaimed spot. Recenter before completing so
     * every exit path (success, timeout, mine failure, claimed position) hands back a stance as
     * neutral as the one this task started from.
     */
    private void finishReclaim(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
        FakePlayerMotion.returnToBlockCenter(bot, bot.blockPosition(), "craft_table_reclaim_settle");
        complete();
    }

    /**
     * Builds the complete post-craft inventory on deep copies. The live inventory is not touched
     * until every ingredient has been consumed and the entire output has been proven insertable.
     */
    static PreparedCraft prepareCraft(
            AIPlayerEntity bot, CraftingHelper.CraftStep step) {
        List<ItemStack> main = copyStacks(bot.getInventory().getNonEquipmentItems());
        List<ItemStack> offHand = copyStacks(List.of(bot.getItemBySlot(EquipmentSlot.OFFHAND)));
        // What a crafting grid hands back besides the result (an empty bucket for every milk bucket of a cake, ...).
        List<ItemStack> remainders = new ArrayList<>();
        for (RecipeRegistry.Ingredient ingredient : step.recipe().ingredients()) {
            int required = ingredient.count() * step.crafts();
            if (!removeIngredient(main, offHand, ingredient, required, remainders)) {
                return new PreparedCraft(main, offHand, ingredient, required, 0, null);
            }
        }
        for (ItemStack remainder : remainders) {
            if (outputCapacity(main, remainder) < remainder.getCount()) {
                return new PreparedCraft(main, offHand, null, 0, 0,
                        BuiltInRegistries.ITEM.getKey(remainder.getItem()).toString());
            }
            insertEntireStack(main, remainder);
        }

        // The stack the vanilla recipe makes: its result components included, not a bare item.
        ItemStack output = step.recipe().result(step.outputCount());
        int available = outputCapacity(main, output);
        if (available >= output.getCount()) {
            insertEntireStack(main, output);
        }
        return new PreparedCraft(main, offHand, null, 0, available, null);
    }

    private static List<ItemStack> copyStacks(List<ItemStack> source) {
        return source.stream().map(ItemStack::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /**
     * Takes {@code count} units of {@code ingredient} out of the (copied) inventory, like a crafting grid does: a stack is
     * accepted when vanilla's ingredient test accepts it (the ingredient's items are tried in their preference order), and
     * the plain stacks of an item are used up before a renamed, damaged or enchanted one (a careful player does not put an
     * enchanted item in the grid while plain ones lie next to it). Every consumed unit whose item leaves a container
     * behind adds that remainder to {@code remainders}.
     */
    static boolean removeIngredient(
            List<ItemStack> main,
            List<ItemStack> offHand,
            RecipeRegistry.Ingredient ingredient,
            int count,
            List<ItemStack> remainders) {
        if (countMatching(main, offHand, ingredient) < count) {
            return false;
        }
        int remaining = count;
        for (Item item : ingredient.anyOf()) {
            for (boolean plainOnly : new boolean[]{true, false}) {
                for (List<ItemStack> region : List.of(main, offHand)) {
                    for (ItemStack stack : region) {
                        if (remaining <= 0) {
                            return true;
                        }
                        if (!stack.is(item) || !ingredient.matches(stack) || (plainOnly && !isPlain(stack))) {
                            continue;
                        }
                        int take = Math.min(remaining, stack.getCount());
                        ItemStack remainder = stack.getItem().getCraftingRemainder();
                        if (!remainder.isEmpty()) {
                            remainders.add(remainder.copyWithCount(remainder.getCount() * take));
                        }
                        stack.shrink(take);
                        remaining -= take;
                    }
                }
            }
        }
        return remaining == 0;
    }

    /** A stack with nothing but its item's default components: not renamed, damaged, enchanted or otherwise customised. */
    private static boolean isPlain(ItemStack stack) {
        return stack.getComponentsPatch().isEmpty();
    }

    private static int countMatching(
            List<ItemStack> main, List<ItemStack> offHand, RecipeRegistry.Ingredient ingredient) {
        int count = 0;
        for (List<ItemStack> region : List.of(main, offHand)) {
            for (ItemStack stack : region) {
                if (ingredient.matches(stack)) {
                    count += stack.getCount();
                }
            }
        }
        return count;
    }

    private static int outputCapacity(List<ItemStack> main, ItemStack output) {
        long available = 0L;
        for (ItemStack stack : main) {
            if (stack.isEmpty()) {
                available += output.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(stack, output)) {
                available += Math.max(0, stack.getMaxStackSize() - stack.getCount());
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, available);
    }

    private static void insertEntireStack(List<ItemStack> main, ItemStack output) {
        for (ItemStack stack : main) {
            if (output.isEmpty()) {
                return;
            }
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, output)) {
                int moved = Math.min(output.getCount(), stack.getMaxStackSize() - stack.getCount());
                if (moved > 0) {
                    stack.grow(moved);
                    output.shrink(moved);
                }
            }
        }
        for (int slot = 0; slot < main.size() && !output.isEmpty(); slot++) {
            if (!main.get(slot).isEmpty()) {
                continue;
            }
            int moved = Math.min(output.getCount(), output.getMaxStackSize());
            main.set(slot, output.copyWithCount(moved));
            output.shrink(moved);
        }
        if (!output.isEmpty()) {
            throw new IllegalStateException("craft output preflight capacity mismatch");
        }
    }

    static void commitPreparedCraft(AIPlayerEntity bot, PreparedCraft prepared) {
        var inventory = bot.getInventory();
        List<ItemStack> mainStacks = inventory.getNonEquipmentItems();
        for (int slot = 0; slot < mainStacks.size(); slot++) {
            mainStacks.set(slot, prepared.main().get(slot));
        }
        bot.setItemSlot(EquipmentSlot.OFFHAND, prepared.offHand().get(0));
        inventory.setChanged();
    }

    /** {@code remainderOverflow}: the item id of a container the craft hands back that does not fit the inventory, or null. */
    record PreparedCraft(
            List<ItemStack> main,
            List<ItemStack> offHand,
            RecipeRegistry.Ingredient missingIngredient,
            int missingCount,
            int availableOutput,
            String remainderOverflow) {
    }

    private static BlockPos adjacentAir(AIPlayerEntity bot) {
        BlockPos origin = bot.blockPosition();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = origin.relative(direction);
            if (isOpenPlacementCell(bot, candidate)) {
                return candidate.immutable();
            }
        }
        // origin.up() is the bot's own head cell -- always intersects its own hitbox, so vanilla
        // placement there is never actually admissible (World/CollisionGetter#canPlace rejects any
        // destination whose collision shape intersects a live entity, the placer included, with
        // no self-exemption). The one block above that is genuinely external, open headroom.
        BlockPos above = origin.above().above();
        return isOpenPlacementCell(bot, above) ? above.immutable() : null;
    }

    /**
     * A cell is only a genuinely open placement target if it is air AND free of any entity
     * (including an ordinary item drop). A leftover, uncollected drop from an earlier reclaim
     * attempt has a real bounding box, and vanilla 1.21.5 rejects any placement whose collision
     * shape intersects a live entity -- so without this check, a stale drop at this exact cell
     * would keep failing every future placement attempt here, not just the reclaim that left it.
     *
     * <p>{@code noCollision(bot, box)} deliberately excludes the bot itself so a candidate this
     * close never reads as blocked by the placer's own presence -- but vanilla's own placement
     * check has no such self-exemption (see the same reasoning just above for why {@code
     * origin.up()} is skipped entirely). A bot whose real sub-block stance has drifted off-center
     * -- e.g. from the walk/nudge a same-cell item pickup during an earlier reclaim can leave
     * behind -- can have its own hitbox clip a horizontal neighbour without ever changing {@code
     * bot.getBlockPos()}, so this candidate scan must reject exactly that overlap itself instead of
     * confidently choosing a cell vanilla's {@code World.canPlace} will then fail.
     *
     * <p>Finally, the cell must also have a support face {@link BuildAction#placeBlockAt} would
     * actually accept -- the SAME visible/in-reach support-face predicate placeBlockAt itself uses
     * (see {@link BuildAction#canAcceptPlacementAt}). Without this, a geometrically "open" cell
     * with no visible support anywhere around it (e.g. the single open neighbour is the hole above
     * a dig-down staircase entrance, with no floor below it and every horizontal neighbour blocked)
     * would still be picked here only to fail placement with support_face_not_visible every time --
     * exactly the live bug this guards against.
     */
    private static boolean isOpenPlacementCell(AIPlayerEntity bot, BlockPos candidate) {
        var candidateBox = new net.minecraft.world.phys.AABB(candidate);
        return ObservableWorldQuery.canObserveCell(bot, candidate)
                && bot.level().getBlockState(candidate).isAir()
                && bot.level().noCollision(bot, candidateBox)
                && !bot.getBoundingBox().intersects(candidateBox)
                && BuildAction.canAcceptPlacementAt(bot, candidate);
    }

    private static String describeIngredient(RecipeRegistry.Ingredient ingredient, int count) {
        List<String> ids = ingredient.anyOf().stream()
                .map(item -> BuiltInRegistries.ITEM.getKey(item).toString())
                .toList();
        return String.join("|", ids) + " x" + count;
    }

    /** Functional workstation targets can reuse an already-present local station. */
    private boolean utilityAlreadyAvailable(AIPlayerEntity bot) {
        if (target == Items.CRAFTING_TABLE) {
            return WorkshopLocator.hasNearbyCraftingTable(bot)
                    || InventoryAction.findItem(bot, Items.CRAFTING_TABLE).isPresent();
        }
        if (target == Items.FURNACE) {
            return WorkshopLocator.hasNearbyFurnace(bot)
                    || InventoryAction.findItem(bot, Items.FURNACE).isPresent();
        }
        return false;
    }
}

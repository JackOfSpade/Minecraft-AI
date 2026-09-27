package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BlockMiner;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.craft.CraftingHelper;
import io.github.zoyluo.aibot.craft.RecipeRegistry;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogCategory;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

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
        return "Crafting " + Registries.ITEM.getId(target) + " x" + targetCount + " phase=" + phase;
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
        // 幂等短路:工作台/熔炉这类功能方块,若附近已有(够得着)或背包已有,直接完成,不浪费材料重复制造。
        if (utilityAlreadyAvailable(bot)) {
            BotLog.action(bot, "craft_skipped_already_available", "item", Registries.ITEM.getId(target).toString());
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
            return;
        }
        OptionalInt tableSlot = InventoryAction.findItem(bot, Items.CRAFTING_TABLE);
        if (tableSlot.isEmpty()) {
            fail("need: minecraft:crafting_table x1");
            return;
        }
        BlockPos placePos = adjacentAir(bot);
        if (placePos == null) {
            fail("no_place_for_crafting_table");
            return;
        }
        InventoryAction.equipFromSlot(bot, tableSlot.getAsInt());
        // A real jump-arc/fall landing can leave the server-side onGround bit stale for one tick
        // even though the bot's current cell is a genuine, collision-verified stand (the same
        // clientless fake-player quirk AcquireWaterTask's ascent placements already account for).
        // Publish that fact before this precise placement; never do this for a genuinely
        // unsupported pose.
        Standability.clearCache();
        if (!bot.isOnGround() && Standability.isStandable(bot.getEntityWorld(), bot.getBlockPos())) {
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
        if (prepared.availableOutput() < step.outputCount()) {
            fail("craft_output_capacity:item=" + Registries.ITEM.getId(recipe.output())
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
                "item", Registries.ITEM.getId(recipe.output()).toString(),
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
        BlockState state = bot.getEntityWorld().getBlockState(selfPlacedTablePos);
        if (state.isOf(Blocks.CRAFTING_TABLE)) {
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
     */
    private void finishReclaim(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
        complete();
    }

    /**
     * Builds the complete post-craft inventory on deep copies. The live inventory is not touched
     * until every ingredient has been consumed and the entire output has been proven insertable.
     */
    private static PreparedCraft prepareCraft(
            AIPlayerEntity bot, CraftingHelper.CraftStep step) {
        List<ItemStack> main = copyStacks(bot.getInventory().getMainStacks());
        List<ItemStack> offHand = copyStacks(List.of(bot.getEquippedStack(EquipmentSlot.OFFHAND)));
        for (RecipeRegistry.Ingredient ingredient : step.recipe().ingredients()) {
            int required = ingredient.count() * step.crafts();
            if (!removeIngredient(main, offHand, ingredient, required)) {
                return new PreparedCraft(main, offHand, ingredient, required, 0);
            }
        }

        ItemStack output = new ItemStack(step.recipe().output(), step.outputCount());
        int available = outputCapacity(main, output);
        if (available >= output.getCount()) {
            insertEntireStack(main, output);
        }
        return new PreparedCraft(main, offHand, null, 0, available);
    }

    private static List<ItemStack> copyStacks(List<ItemStack> source) {
        return source.stream().map(ItemStack::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static boolean removeIngredient(
            List<ItemStack> main,
            List<ItemStack> offHand,
            RecipeRegistry.Ingredient ingredient,
            int count) {
        int total = 0;
        for (Item item : ingredient.anyOf()) {
            total += countItem(main, offHand, item);
        }
        if (total < count) {
            return false;
        }
        int remaining = count;
        for (Item item : ingredient.anyOf()) {
            if (remaining <= 0) {
                return true;
            }
            for (List<ItemStack> region : List.of(main, offHand)) {
                for (ItemStack stack : region) {
                    if (remaining <= 0) {
                        return true;
                    }
                    if (!stack.isOf(item)) {
                        continue;
                    }
                    int take = Math.min(remaining, stack.getCount());
                    stack.decrement(take);
                    remaining -= take;
                }
            }
        }
        return remaining == 0;
    }

    private static int countItem(List<ItemStack> main, List<ItemStack> offHand, Item item) {
        int count = 0;
        for (List<ItemStack> region : List.of(main, offHand)) {
            for (ItemStack stack : region) {
                if (stack.isOf(item)) {
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
                available += output.getMaxCount();
            } else if (ItemStack.areItemsAndComponentsEqual(stack, output)) {
                available += Math.max(0, stack.getMaxCount() - stack.getCount());
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, available);
    }

    private static void insertEntireStack(List<ItemStack> main, ItemStack output) {
        for (ItemStack stack : main) {
            if (output.isEmpty()) {
                return;
            }
            if (!stack.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, output)) {
                int moved = Math.min(output.getCount(), stack.getMaxCount() - stack.getCount());
                if (moved > 0) {
                    stack.increment(moved);
                    output.decrement(moved);
                }
            }
        }
        for (int slot = 0; slot < main.size() && !output.isEmpty(); slot++) {
            if (!main.get(slot).isEmpty()) {
                continue;
            }
            int moved = Math.min(output.getCount(), output.getMaxCount());
            main.set(slot, output.copyWithCount(moved));
            output.decrement(moved);
        }
        if (!output.isEmpty()) {
            throw new IllegalStateException("craft output preflight capacity mismatch");
        }
    }

    private static void commitPreparedCraft(AIPlayerEntity bot, PreparedCraft prepared) {
        var inventory = bot.getInventory();
        List<ItemStack> mainStacks = inventory.getMainStacks();
        for (int slot = 0; slot < mainStacks.size(); slot++) {
            mainStacks.set(slot, prepared.main().get(slot));
        }
        bot.equipStack(EquipmentSlot.OFFHAND, prepared.offHand().get(0));
        inventory.markDirty();
    }

    private record PreparedCraft(
            List<ItemStack> main,
            List<ItemStack> offHand,
            RecipeRegistry.Ingredient missingIngredient,
            int missingCount,
            int availableOutput) {
    }

    private static BlockPos adjacentAir(AIPlayerEntity bot) {
        BlockPos origin = bot.getBlockPos();
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = origin.offset(direction);
            if (isOpenPlacementCell(bot, candidate)) {
                return candidate.toImmutable();
            }
        }
        // origin.up() is the bot's own head cell -- always intersects its own hitbox, so vanilla
        // placement there is never actually admissible (World/CollisionView#canPlace rejects any
        // destination whose collision shape intersects a live entity, the placer included, with
        // no self-exemption). The one block above that is genuinely external, open headroom.
        BlockPos above = origin.up().up();
        return isOpenPlacementCell(bot, above) ? above.toImmutable() : null;
    }

    /**
     * A cell is only a genuinely open placement target if it is air AND free of any entity
     * (including an ordinary item drop). A leftover, uncollected drop from an earlier reclaim
     * attempt has a real bounding box, and vanilla 1.21.5 rejects any placement whose collision
     * shape intersects a live entity -- so without this check, a stale drop at this exact cell
     * would keep failing every future placement attempt here, not just the reclaim that left it.
     */
    private static boolean isOpenPlacementCell(AIPlayerEntity bot, BlockPos candidate) {
        return ObservableWorldQuery.canObserveCell(bot, candidate)
                && bot.getEntityWorld().getBlockState(candidate).isAir()
                && bot.getEntityWorld().isSpaceEmpty(bot, new net.minecraft.util.math.Box(candidate));
    }

    private static String describeIngredient(RecipeRegistry.Ingredient ingredient, int count) {
        List<String> ids = ingredient.anyOf().stream()
                .map(item -> Registries.ITEM.getId(item).toString())
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

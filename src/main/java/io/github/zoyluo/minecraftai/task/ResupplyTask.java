package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.ContainerAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.memory.BotMemoryStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class ResupplyTask extends AbstractTask {
    private static final int BASE_RADIUS = 8;
    private static final double REACH_SQUARED = 20.25D;
    private static final double LOW_DURABILITY_FRACTION = 0.10D;

    public enum Need {
        TOOL,
        FOOD
    }

    private enum Phase {
        FIND_BASE,
        FIND_CONTAINER,
        GOTO_BASE,
        WALKING,
        WITHDRAWING,
        CRAFTING,
        EATING,
        DONE
    }

    private final Need need;
    private final Item requestedItem;
    private final boolean localOnly;
    private final List<BlockPos> containers = new ArrayList<>();
    private Phase phase = Phase.FIND_BASE;
    private BlockPos basePos;
    private BlockPos containerPos;
    private int containerIndex;
    private CraftTask craftTask;
    private EatTask eatTask;
    private String note = "";

    public static ResupplyTask tool(Item item) {
        return new ResupplyTask(Need.TOOL, item, false);
    }

    /** Tool service that may craft from carried materials but must never path away. */
    public static ResupplyTask toolInPlace(Item item) {
        return new ResupplyTask(Need.TOOL, item, true);
    }

    public static ResupplyTask food() {
        return new ResupplyTask(Need.FOOD, null, false);
    }

    boolean localOnly() {
        return localOnly;
    }

    private ResupplyTask(Need need, Item requestedItem, boolean localOnly) {
        this.need = need;
        this.requestedItem = requestedItem;
        this.localOnly = localOnly;
    }

    @Override
    public String name() {
        return "resupply";
    }

    @Override
    public String describe() {
        String target = requestedItem == null ? need.name().toLowerCase(java.util.Locale.ROOT) : BuiltInRegistries.ITEM.getKey(requestedItem).toString();
        return "Resupplying " + target + " phase=" + phase + (note.isBlank() ? "" : " note=" + note);
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return switch (phase) {
            case FIND_BASE -> 0.0D;
            case FIND_CONTAINER -> 0.15D;
            case GOTO_BASE -> 0.25D;
            case WALKING -> 0.35D;
            case WITHDRAWING -> 0.55D;
            case CRAFTING -> 0.75D;
            case EATING -> 0.9D;
            case DONE -> 1.0D;
        };
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.FIND_BASE;
        containers.clear();
        containerIndex = 0;
        craftTask = null;
        eatTask = null;
        note = "";
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 1800) {
            fail("resupply_timeout");
            return;
        }
        switch (phase) {
            case FIND_BASE -> findBase(bot);
            case FIND_CONTAINER -> findContainer(bot);
            case GOTO_BASE -> goToBase(bot);
            case WALKING -> walk(bot);
            case WITHDRAWING -> withdraw(bot);
            case CRAFTING -> craft(bot);
            case EATING -> eat(bot);
            case DONE -> complete();
        }
    }

    private void findBase(AIPlayerEntity bot) {
        if (alreadySatisfied(bot)) {
            phase = afterSupplyPhase(bot);
            return;
        }
        if (localOnly) {
            // A paused mining owner may owe an exact physical return from a safety-displaced pose.
            // Looking up a remembered base here would create a second displacement and corrupt
            // recovery geometry. Carried recipes remain available, but this mode never starts a
            // path or searches a remote container.
            note = "local_only";
            startCrafting(bot);
            return;
        }
        basePos = BotMemoryStore.INSTANCE.of(bot.getUUID())
                .placeIn(bot.level(), "base")
                .orElse(null);
        if (basePos == null) {
            // No base (deep-underground mining / wilderness expedition): don't stall out on no_base -- craft
            // in place directly from carried materials instead (stone_pickaxe = cobblestone + stick + carried
            // crafting table; iron_pickaxe = spare iron ingot + stick). When a pickaxe wears out deep
            // underground the inventory usually has plenty of cobblestone/materials on hand; if crafting still
            // can't produce it (materials missing), CraftTask will honestly fail with no_supply on its own.
            // Fixes real_armor: mining 26 iron with a stone pickaxe wears it out -> resupply -> FIND_BASE ->
            // no_base deadlock (bot has 78 cobblestone + a crafting table but goes off looking for a base instead).
            // No remembered base at all is a real decision point: it silently reroutes to crafting
            // from carried materials instead, many ticks before any eventual craft success/failure,
            // and would otherwise leave no trace if that craft later fails generically.
            BotLog.action(bot, "resupply_no_base_craft_in_place", "need", need);
            startCrafting(bot);
            return;
        }
        phase = Phase.FIND_CONTAINER;
    }

    private void findContainer(AIPlayerEntity bot) {
        containers.clear();
        // Storage in sight plus containers this bot remembers opening here. Ranking uses only the
        // ledger (what the bot saw when it last opened each one); the contents of an unopened
        // container are never read from a distance.
        containers.addAll(StorageTargets.aroundBase(bot, basePos, BASE_RADIUS, false,
                (pos, observed) -> ledgerHasSupply(bot, pos, observed)));
        containerIndex = 0;
        if (containers.isEmpty()) {
            walkToBaseOrCraft(bot);
            return;
        }
        selectNextContainer(bot);
    }

    private void selectNextContainer(AIPlayerEntity bot) {
        if (alreadySatisfied(bot)) {
            phase = afterSupplyPhase(bot);
            return;
        }
        if (containerIndex >= containers.size()) {
            walkToBaseOrCraft(bot);
            return;
        }
        containerPos = containers.get(containerIndex++);
        if (ContainerAction.inReachAndSight(bot, containerPos)) {
            phase = Phase.WITHDRAWING;
            return;
        }
        BlockPos stand = ContainerSupport.adjacentStand(bot, containerPos);
        if (stand == null) {
            selectNextContainer(bot);
            return;
        }
        ActionResult result = bot.getActionPack().startPathTo(stand);
        if (result.isFailed()) {
            note = result.reason();
            selectNextContainer(bot);
            return;
        }
        phase = Phase.WALKING;
    }

    private void walkToBaseOrCraft(AIPlayerEntity bot) {
        if (bot.getEyePosition().distanceToSqr(basePos.getCenter()) <= REACH_SQUARED) {
            startCrafting(bot);
            return;
        }
        BlockPos stand = ContainerSupport.adjacentStand(bot, basePos);
        if (stand == null) {
            stand = basePos;
        }
        ActionResult result = bot.getActionPack().startPathTo(stand);
        if (result.isFailed()) {
            note = result.reason();
            startCrafting(bot);
            return;
        }
        phase = Phase.GOTO_BASE;
    }

    private void goToBase(AIPlayerEntity bot) {
        if (bot.getEyePosition().distanceToSqr(basePos.getCenter()) <= REACH_SQUARED) {
            bot.getActionPack().stopAll();
            startCrafting(bot);
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            startCrafting(bot);
        }
    }

    private void walk(AIPlayerEntity bot) {
        if (containerPos == null) {
            phase = Phase.FIND_CONTAINER;
            return;
        }
        if (ContainerAction.canSee(bot, containerPos)
                && !ContainerAction.isOpenableStorage(bot.level(), containerPos)) {
            // Seen gone (or its lid is now blocked): forget what we knew and move on to the next one.
            if (!(bot.level().getBlockEntity(containerPos) instanceof Container)) {
                ContainerAction.forget(bot, containerPos);
            }
            selectNextContainer(bot);
            return;
        }
        if (ContainerAction.inReachAndSight(bot, containerPos)) {
            bot.getActionPack().stopAll();
            phase = Phase.WITHDRAWING;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            selectNextContainer(bot);
        }
    }

    private void withdraw(AIPlayerEntity bot) {
        if (containerPos == null || !ContainerAction.inReachAndSight(bot, containerPos)) {
            phase = Phase.FIND_CONTAINER;
            return;
        }
        // Opening the container is what shows the bot its contents (and updates its ledger).
        Container container = ContainerAction.open(bot, containerPos, false).orElse(null);
        if (container == null) {
            selectNextContainer(bot);
            return;
        }
        boolean moved = switch (need) {
            case TOOL -> withdrawTool(container, bot);
            case FOOD -> withdrawFood(container, bot);
        };
        if (moved) {
            phase = afterSupplyPhase(bot);
            return;
        }
        selectNextContainer(bot);
    }

    private boolean withdrawTool(Container container, AIPlayerEntity bot) {
        if (requestedItem == null) {
            return false;
        }
        ContainerAction.TransferResult result = ContainerAction.withdraw(bot, containerPos, container, requestedItem, 1);
        if (!result.movedAny()) {
            note = result.reason();
            return false;
        }
        return equipUsableTool(bot);
    }

    private boolean withdrawFood(Container container, AIPlayerEntity bot) {
        Item food = firstFood(container);
        if (food == null) {
            return false;
        }
        ContainerAction.TransferResult result = ContainerAction.withdraw(bot, containerPos, container, food, 16);
        if (!result.movedAny()) {
            note = result.reason();
            return false;
        }
        return InventoryAction.findFoodSlot(bot) >= 0;
    }

    private void startCrafting(AIPlayerEntity bot) {
        Item craftTarget = need == Need.FOOD ? Items.BREAD : requestedItem;
        if (craftTarget == null) {
            fail("no_supply");
            return;
        }
        int desiredCount = need == Need.TOOL ? InventoryAction.countItem(bot, craftTarget) + 1 : 1;
        craftTask = new CraftTask(craftTarget, desiredCount);
        craftTask.start(bot);
        phase = Phase.CRAFTING;
    }

    private void craft(AIPlayerEntity bot) {
        if (craftTask == null) {
            startCrafting(bot);
            return;
        }
        craftTask.tick(bot);
        if (craftTask.state() == TaskState.COMPLETED) {
            craftTask = null;
            if (need == Need.TOOL && !equipUsableTool(bot)) {
                // "no_supply" is reused by several distinct causes in this task (missing craft
                // target, no food slot, this one); the craft just reported success, so without this
                // line a reader could not tell this specific case apart from the others.
                BotLog.warn(LogCategory.TASK, bot, "resupply_crafted_tool_unusable",
                        "item", BuiltInRegistries.ITEM.getKey(requestedItem).toString());
                fail("no_supply");
                return;
            }
            phase = afterSupplyPhase(bot);
            return;
        }
        if (craftTask.state() == TaskState.FAILED) {
            String reason = craftTask.failureReason();
            if (reason != null
                    && reason.startsWith("craft_output_capacity:")
                    && InventoryAction.dropJunkUntilFreeSlots(bot, 1, 16) > 0) {
                craftTask = null;
                BotLog.action(bot, "resupply_craft_capacity_recovered",
                        "item", BuiltInRegistries.ITEM.getKey(
                                need == Need.FOOD ? Items.BREAD : requestedItem).toString(),
                        "reason", reason);
                startCrafting(bot);
                return;
            }
            fail(reason == null || reason.isBlank() ? "no_supply" : "no_supply: " + reason);
        }
    }

    private void eat(AIPlayerEntity bot) {
        if (bot.getFoodData().getFoodLevel() >= 20) {
            phase = Phase.DONE;
            return;
        }
        if (eatTask == null) {
            if (InventoryAction.findFoodSlot(bot) < 0) {
                BotLog.warn(LogCategory.TASK, bot, "resupply_no_food_to_eat");
                fail("no_supply");
                return;
            }
            eatTask = new EatTask();
            eatTask.start(bot);
        }
        eatTask.tick(bot);
        if (eatTask.state() == TaskState.COMPLETED) {
            phase = Phase.DONE;
            eatTask = null;
        } else if (eatTask.state() == TaskState.FAILED) {
            String reason = eatTask.failureReason();
            fail(reason == null || reason.isBlank() ? "no_supply" : reason);
        }
    }

    private Phase afterSupplyPhase(AIPlayerEntity bot) {
        if (need == Need.FOOD && bot.getFoodData().getFoodLevel() < 20) {
            return Phase.EATING;
        }
        return Phase.DONE;
    }

    private boolean alreadySatisfied(AIPlayerEntity bot) {
        return switch (need) {
            case TOOL -> equipUsableTool(bot);
            case FOOD -> InventoryAction.findFoodSlot(bot) >= 0;
        };
    }

    private boolean equipUsableTool(AIPlayerEntity bot) {
        if (requestedItem == null) {
            return false;
        }
        int bestSlot = -1;
        int bestRemaining = -1;
        for (int slot = 0; slot < bot.getInventory().getNonEquipmentItems().size(); slot++) {
            ItemStack stack = bot.getInventory().getNonEquipmentItems().get(slot);
            if (!stack.is(requestedItem) || !isUsable(stack)) {
                continue;
            }
            int remaining = stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
            if (remaining > bestRemaining) {
                bestRemaining = remaining;
                bestSlot = slot;
            }
        }
        if (bestSlot < 0) {
            return false;
        }
        InventoryAction.equipFromSlot(bot, bestSlot);
        return true;
    }

    /** Whether the ledger remembers supply in the container at {@code pos}; never reads the container itself. */
    private boolean ledgerHasSupply(AIPlayerEntity bot, BlockPos pos, boolean observed) {
        var entry = StorageTargets.entry(bot, pos, observed).orElse(null);
        if (entry == null) {
            return false;
        }
        return switch (need) {
            case TOOL -> requestedItem != null
                    && entry.count(BuiltInRegistries.ITEM.getKey(requestedItem).toString()) > 0;
            case FOOD -> entry.items().keySet().stream().anyMatch(id -> {
                Item candidate = BuiltInRegistries.ITEM
                        .getOptional(net.minecraft.resources.Identifier.parse(id)).orElse(null);
                return candidate != null && InventoryAction.isEatableFood(new ItemStack(candidate));
            });
        };
    }

    private static Item firstFood(Container inventory) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (InventoryAction.isStockableFood(stack)) {
                return stack.getItem();
            }
        }
        return null;
    }

    private static boolean isUsable(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (!stack.isDamageableItem()) {
            return true;
        }
        int max = stack.getMaxDamage();
        if (max <= 0) {
            return true;
        }
        return stack.getMaxDamage() - stack.getDamageValue() > max * LOW_DURABILITY_FRACTION;
    }

}

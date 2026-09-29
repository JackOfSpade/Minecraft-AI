package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.ContainerAction;
import io.github.zoyluo.minecraftai.action.DigNav;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.craft.CraftingHelper;
import io.github.zoyluo.minecraftai.craft.SmeltChain;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.memory.BotMemoryStore;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

public final class SmeltTask extends AbstractTask {
    private enum Phase {
        FINDING_FURNACE,
        WALKING_TO_FURNACE,
        CRAFTING_FURNACE,
        PLACING_FURNACE,
        LOADING,
        SMELTING,
        COLLECTING
    }

    private static final Map<Item, Integer> FUEL_TICKS = new LinkedHashMap<>();
    private static final int BASE_FUEL_RADIUS = 8;
    private static final double REACH_SQUARED = 20.25D;
    /**
     * A remembered furnace is useful only while reaching it is cheaper than carrying eight
     * cobblestone.  In a mining expedition the old surface furnace can be fifty blocks above the
     * bot; walking/digging toward it consumes the whole smelt watchdog before the first item cooks.
     */
    private static final double LOCAL_FURNACE_DISTANCE_SQUARED = 24.0D * 24.0D;

    static {
        FUEL_TICKS.put(Items.COAL, 1600);
        FUEL_TICKS.put(Items.CHARCOAL, 1600);
        FUEL_TICKS.put(Items.OAK_LOG, 300);
        FUEL_TICKS.put(Items.SPRUCE_LOG, 300);
        FUEL_TICKS.put(Items.BIRCH_LOG, 300);
        FUEL_TICKS.put(Items.JUNGLE_LOG, 300);
        FUEL_TICKS.put(Items.ACACIA_LOG, 300);
        FUEL_TICKS.put(Items.DARK_OAK_LOG, 300);
        FUEL_TICKS.put(Items.MANGROVE_LOG, 300);
        FUEL_TICKS.put(Items.CHERRY_LOG, 300);
        FUEL_TICKS.put(Items.OAK_PLANKS, 300);
        FUEL_TICKS.put(Items.SPRUCE_PLANKS, 300);
        FUEL_TICKS.put(Items.BIRCH_PLANKS, 300);
        FUEL_TICKS.put(Items.JUNGLE_PLANKS, 300);
        FUEL_TICKS.put(Items.ACACIA_PLANKS, 300);
        FUEL_TICKS.put(Items.DARK_OAK_PLANKS, 300);
        FUEL_TICKS.put(Items.MANGROVE_PLANKS, 300);
        FUEL_TICKS.put(Items.CHERRY_PLANKS, 300);
        FUEL_TICKS.put(Items.STICK, 100);
    }

    private Item input;             // In cookAll mode this is reselected at runtime (cooks each raw food in the inventory in turn)
    private Item output;
    private final boolean cookAll;  // true = cook every cookable raw food in the inventory until targetCount cooked items are gathered
    private final boolean requireCookedQuota;
    private final int targetCount;
    private Phase phase = Phase.FINDING_FURNACE;
    private BlockPos furnacePos;
    private double walkBestDist2 = Double.MAX_VALUE; // WALKING proximity monitor: best (smallest) distance² seen so far (fixes stuck: smelt active-but-stuck)
    private int walkStallSince;                       // elapsed time at the last approach toward the furnace; too long without approaching = path stuck -> escalate
    private int collected;
    private final BlockMiner clearMiner = new BlockMiner(); // when boxed in with nowhere to place the furnace, mine one adjacent block to clear space
    private boolean walkDigging; // when pure pathfinding can't reach the existing furnace, fall back to digging toward the furnace (reuses clearMiner)
    private CraftTask furnaceCraftSub; // when stuck heading to the furnace with no backup furnace, craft a new one in place (reuses CraftTask, no duplicate material-deduction logic)
    private boolean furnaceCraftRequired; // when no usable furnace exists at all, a failed sub-craft must terminate the task instead of spinning between FINDING and CRAFTING
    private boolean furnaceCraftFailed;   // one failure disables repeat sub-crafting for this task; falling back to a remote furnace can still succeed
    /** Stations rejected as blocked/incompatible for this task; prevents retrying one forever. */
    private final Set<BlockPos> rejectedFurnaces = new HashSet<>();

    public SmeltTask(Item input, Item output, int targetCount) {
        this.input = input;
        this.output = output;
        this.cookAll = false;
        this.requireCookedQuota = true;
        this.targetCount = Math.max(1, targetCount);
    }

    /** cookAll mode: cooks every cookable raw food in the inventory until targetCount cooked items are gathered (used by the COOK_FOOD step -- cooking meat after a hunt). */
    public SmeltTask(int targetCount) {
        this(targetCount, false);
    }

    public SmeltTask(int targetCount, boolean requireCookedQuota) {
        this.input = null;
        this.output = null;
        this.cookAll = true;
        this.requireCookedQuota = requireCookedQuota;
        this.targetCount = Math.max(1, targetCount);
    }

    @Override
    public String name() {
        return "smelt";
    }

    @Override
    public String describe() {
        return "Smelting " + BuiltInRegistries.ITEM.getKey(input) + " -> " + BuiltInRegistries.ITEM.getKey(output)
                + " " + collected + "/" + targetCount + " phase=" + phase;
    }

    @Override
    public double progress() {
        return Math.min(1.0D, (double) collected / targetCount);
    }

    @Override
    public boolean isWaiting() {
        // While dig-navigating toward the furnace the bot stands and mines with position roughly unchanged -> treat as waiting to avoid a StuckWatcher false positive (this task's own overall timeout is the backstop).
        // Clearing a horizontal placement space for the furnace inside an enclosed mine tunnel likewise mines continuously in place. That progress
        // is managed by BlockMiner's own 200-tick per-block timeout and should not be cut short early by StuckWatcher, which only observes displacement.
        return phase == Phase.SMELTING
                || (phase == Phase.WALKING_TO_FURNACE && walkDigging)
                || (phase == Phase.PLACING_FURNACE && clearMiner.target() != null);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.FINDING_FURNACE;
        furnaceCraftRequired = false;
        furnaceCraftFailed = false;
        rejectedFurnaces.clear();
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        clearMiner.cancel(bot);
        if (furnaceCraftSub != null) { // symmetric cleanup of the in-place craft subtask: prevents a leftover RUNNING instance from being reused after abort
            furnaceCraftSub.abort(bot);
            furnaceCraftSub = null;
        }
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 400 + targetCount * 260) {
            fail("smelt_timeout");
            return;
        }
        if (cookAll && !ensureCurrentRawFood(bot)) {
            return; // no cookable raw food left (already completed/failed)
        }
        switch (phase) {
            case FINDING_FURNACE -> findFurnace(bot);
            case WALKING_TO_FURNACE -> walkToFurnace(bot);
            case CRAFTING_FURNACE -> craftFurnace(bot);
            case PLACING_FURNACE -> placeFurnace(bot);
            case LOADING -> loadFurnace(bot);
            case SMELTING -> waitForOutput(bot);
            case COLLECTING -> collectOutput(bot);
        }
    }

    // cookAll: ensures the "current cook type" is a raw food actually in the inventory; once the current type is used up in both inventory and furnace -> switch to the next type; once all types are done -> finish.
    // Returns false to mean this tick should stop (already completed/failed).
    private boolean ensureCurrentRawFood(AIPlayerEntity bot) {
        if (input != null && InventoryAction.countItem(bot, input) > 0) {
            return true; // inventory still has the current type, keep cooking it
        }
        if (input != null && hasPendingInFurnace(bot)) {
            return true; // inventory is out of the current type, but the furnace still has its material/output -> finish collecting before switching types (avoids an input-slot occupancy conflict)
        }
        Item next = null;
        for (Item raw : SmeltChain.RAW_FOODS) {
            if (InventoryAction.countItem(bot, raw) > 0) {
                next = raw;
                break;
            }
        }
        if (next == null) {
            if (collected >= targetCount || (collected > 0 && !requireCookedQuota)) {
                complete();
            } else {
                fail((collected > 0 ? "insufficient_cooked_food" : "no_raw_food")
                        + " collected=" + collected + "/" + targetCount);
            }
            return false;
        }
        input = next;
        output = SmeltChain.smeltOf(next);
        phase = Phase.FINDING_FURNACE; // the furnace location is usually already known, so this returns to LOADING quickly
        return true;
    }

    private boolean hasPendingInFurnace(AIPlayerEntity bot) {
        AbstractFurnaceBlockEntity f = furnace(bot);
        return f != null && (!f.getItem(0).isEmpty() || !f.getItem(2).isEmpty());
    }

    private void findFurnace(AIPlayerEntity bot) {
        if (!InventoryAction.hasItems(bot, input, 1)) {
            fail("missing " + BuiltInRegistries.ITEM.getKey(input) + " x1");
            return;
        }
        walkDigging = false;
        furnacePos = nearestFurnace(bot, input, output,
                Math.max(1, targetCount - collected), rejectedFurnaces).orElse(null);
        if (furnacePos == null) {
            // local scan found nothing -> check memory: where did I place a furnace before (only valid if same dimension and the block is still a furnace; torn down = invalidated)
            var remembered = io.github.zoyluo.minecraftai.memory.BotMemoryStore.INSTANCE
                    .of(bot.getUUID()).placeIn(bot.level(), "furnace");
            if (remembered.isPresent()
                    && !rejectedFurnaces.contains(remembered.get())
                    && remembered.get().closerThan(bot.blockPosition(), 96.0D)) {
                furnacePos = remembered.get();
            }
        }
        if (furnacePos == null) {
            if (InventoryAction.findItem(bot, Items.FURNACE).isEmpty()) {
                // Even with no local or remembered furnace at all, this should still reuse the same
                // in-inventory crafting path. The old logic only crafted one in place when it
                // "remembered a remote furnace"; once a surface furnace fell outside the 96-block
                // memory radius it went straight to missing furnace instead, even when a crafting
                // table and hundreds of stone were already deep in the inventory. This only consumes
                // the current inventory and does not trigger any gathering task.
                if (canCraftFurnaceFromInventory(bot)) {
                    beginFurnaceCraft(bot, true, "smelt_missing_furnace_craft");
                    return;
                }
                fail("missing minecraft:furnace");
                return;
            }
            phase = Phase.PLACING_FURNACE;
            return;
        }
        double furnaceDistanceSquared = bot.getEyePosition().distanceToSqr(furnacePos.getCenter());
        if (furnaceDistanceSquared > LOCAL_FURNACE_DISTANCE_SQUARED) {
            boolean hasPortableFurnace = InventoryAction.findItem(bot, Items.FURNACE).isPresent();
            boolean canCraftPortableFurnace = canCraftFurnaceFromInventory(bot);
            if (hasPortableFurnace || canCraftPortableFurnace) {
                BotLog.action(bot, "smelt_far_furnace_replace",
                        "old", furnacePos.toShortString(),
                        "distance", String.format(java.util.Locale.ROOT, "%.1f",
                                Math.sqrt(furnaceDistanceSquared)),
                        "source", hasPortableFurnace ? "inventory" : "craft");
                furnacePos = null;
                bot.getActionPack().stopAll();
                if (hasPortableFurnace) {
                    phase = Phase.PLACING_FURNACE;
                } else {
                    beginFurnaceCraft(bot, false, null);
                }
                return;
            }
        }
        if (furnaceDistanceSquared <= REACH_SQUARED
                && io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlock(bot, furnacePos)) {
            phase = Phase.LOADING;
            return;
        }
        BlockPos stand = ContainerSupport.adjacentStand(bot, furnacePos);
        if (stand == null) {
            // Try the next ranked local station before consuming a carried/crafted furnace.
            rejectCurrentFurnace(bot, "no_stand_position");
            phase = Phase.FINDING_FURNACE;
            return;
        }
        ActionResult result = bot.getActionPack().startPathTo(stand);
        // Pure pathfinding can't reach the existing furnace (boxed in underground / self-dug tunnel too
        // complex; observed in testing that GOAL_UNREACHABLE makes the whole goal replan back to
        // surface wood-chopping, leaving the bot stuck deep underground unable to get back) -> don't
        // fail, fall back to digging toward the furnace instead.
        walkDigging = result.isFailed();
        walkBestDist2 = Double.MAX_VALUE; // entering WALKING: reset the proximity monitor
        walkStallSince = elapsed;
        phase = Phase.WALKING_TO_FURNACE;
    }

    private void walkToFurnace(AIPlayerEntity bot) {
        if (furnacePos == null) {
            phase = Phase.FINDING_FURNACE;
            return;
        }
        boolean observable = io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlock(bot, furnacePos);
        if (observable && !WorkshopLocator.isCompatibleFurnace(bot, furnacePos, input, output)) {
            rejectCurrentFurnace(bot, "station_changed_or_occupied");
            phase = Phase.FINDING_FURNACE;
            return;
        }
        double dist2 = bot.getEyePosition().distanceToSqr(furnacePos.getCenter());
        if (observable && dist2 <= REACH_SQUARED) {
            clearMiner.cancel(bot);
            bot.getActionPack().stopAll();
            phase = Phase.LOADING;
            return;
        }
        // Proximity monitor (fixes stuck: smelt WALKING_TO_FURNACE: observed in testing on 9/18 the bot
        // froze on a cliff edge with on_ground=false, pure pathfinding active-but-stuck, isPathExecutorIdle
        // permanently false so it went undetected, until the external 200-tick watchdog failed and
        // triggered a replan, burning the budget). Too long without approaching -> escalate pure
        // pathfinding to dig-navigation; if dig-navigation also can't get closer -> abandon this furnace,
        // go back to FINDING to reselect/craft a replacement. The displacement threshold is very low, so
        // normal movement easily keeps it fed.
        if (dist2 < walkBestDist2 - 0.5D) {
            walkBestDist2 = dist2;
            walkStallSince = elapsed;
        } else if (elapsed - walkStallSince > 40) {
            walkStallSince = elapsed;
            if (!walkDigging) {
                bot.getActionPack().stopAll();
                walkDigging = true;
                BotLog.action(bot, "smelt_walk_stall_dig", "furnace", furnacePos.toShortString());
            } else {
                rejectCurrentFurnace(bot, "path_unreachable");
                walkBestDist2 = Double.MAX_VALUE;
                // A failed fast station must not prevent a reachable normal furnace from being
                // selected. FINDING falls back to a portable normal furnace only after all local
                // compatible candidates have been excluded.
                phase = Phase.FINDING_FURNACE;
                BotLog.action(bot, "smelt_walk_stall_refind", "dist2", String.format("%.0f", dist2));
            }
            return;
        }
        if (walkDigging) {
            // Dig toward the furnace (reaches it even when boxed in underground); if the target block is blocked by adjacent lava -> go back to FINDING and pick another (the LOADING check stops within 4.5 blocks, so it never digs into the furnace itself)
            if (!DigNav.digStep(bot, clearMiner, furnacePos)) {
                rejectCurrentFurnace(bot, "dig_navigation_failed");
                phase = Phase.FINDING_FURNACE;
            }
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            // Pure pathfinding can't get there -> fall back to dig-navigation, instead of repeatedly re-searching until it eventually fails with smelt_timeout and triggers a replan
            walkDigging = true;
        }
    }

    private void rejectCurrentFurnace(AIPlayerEntity bot, String reason) {
        if (furnacePos != null) {
            rejectedFurnaces.add(furnacePos.immutable());
            BotLog.action(bot, "smelt_station_rejected", "station", furnacePos.toShortString(),
                    "reason", reason);
        }
        clearMiner.cancel(bot);
        bot.getActionPack().stopAll();
        furnacePos = null;
        walkDigging = false;
    }

    private void craftFurnace(AIPlayerEntity bot) {
        if (furnaceCraftSub == null) {
            furnaceCraftSub = new CraftTask(Items.FURNACE, 1);
            furnaceCraftSub.start(bot);
        }
        furnaceCraftSub.tick(bot);
        TaskState st = furnaceCraftSub.state();
        if (st == TaskState.COMPLETED) {
            furnaceCraftSub = null;
            furnaceCraftRequired = false;
            furnaceCraftFailed = false;
            // The craft may have short-circuited via CraftTask.utilityAlreadyAvailable (a furnace already
            // within 8 blocks) without actually producing an item -- only go place one if we actually
            // have it; otherwise go back to FINDING and let nearestFurnace take over nearby, and never
            // enter PLACING empty-handed to hard-fail.
            phase = InventoryAction.findItem(bot, Items.FURNACE).isPresent()
                    ? Phase.PLACING_FURNACE : Phase.FINDING_FURNACE;
        } else if (st == TaskState.FAILED) {
            String subFailure = furnaceCraftSub.failureReason();
            furnaceCraftSub = null;
            // A full mining inventory can make a perfectly valid furnace recipe fail only when
            // its output has nowhere to go.  This happened with nine cobblestone: consuming eight
            // left one in the source slot, so the crafted furnace still needed a new slot.  Keep
            // the ordinary survival semantics by dropping one whole low-value stack as a world
            // ItemEntity, then retry the same local craft instead of permanently chasing a remote
            // remembered furnace until the smelt watchdog expires.
            if (subFailure != null
                    && subFailure.startsWith("craft_output_capacity:")
                    && InventoryAction.dropJunkUntilFreeSlots(bot, 1, 16) > 0) {
                BotLog.action(bot, "smelt_furnace_craft_capacity_recovered",
                        "reason", subFailure);
                return;
            }
            boolean required = furnaceCraftRequired;
            furnaceCraftRequired = false;
            furnaceCraftFailed = true;
            if (required) {
                // There is no old furnace to fall back to here. After one failure, report a typed
                // failure directly and let the planner above handle the current material shortfall;
                // repeatedly rebuilding the same CraftTask would only burn through SmeltTask's entire budget.
                fail("missing minecraft:furnace:local_craft_failed:" + subFailure);
            } else {
                phase = Phase.FINDING_FURNACE; // remote furnace replacement failed -> just fall back to the old furnace; furnaceCraftFailed prevents another sub-craft
            }
        }
        // else RUNNING: continue ticking (crafting a furnace is only 1-2 steps, completes within a few ticks, well under CraftTask's 400-tick timeout)
    }

    private boolean canCraftFurnaceFromInventory(AIPlayerEntity bot) {
        boolean tableAvailable = WorkshopLocator.hasNearbyCraftingTable(bot)
                || InventoryAction.findItem(bot, Items.CRAFTING_TABLE).isPresent();
        return !furnaceCraftFailed
                && CraftingHelper.plan(bot, Items.FURNACE, 1, tableAvailable).success();
    }

    private void beginFurnaceCraft(AIPlayerEntity bot, boolean required, String event) {
        furnaceCraftRequired = required;
        phase = Phase.CRAFTING_FURNACE;
        bot.getActionPack().stopAll();
        if (event != null) {
            BotLog.action(bot, event, "source", "inventory_craft");
        }
    }

    private void placeFurnace(AIPlayerEntity bot) {
        // Once clearing the placement space has started, let the mining atomic action run to
        // completion first -- don't query or re-equip the furnace on every task tick. findItem could
        // promote an offhand furnace into a full inventory and swap it with the selected pick;
        // MiningController computes break speed from the currently held item, so switching back to
        // the furnace mid-clear would degrade a stone-pickaxe iron-ore clear to bare-hand speed and
        // it would never finish within the watchdog window.
        if (clearMiner.target() != null) {
            BlockMiner.Status status = clearMiner.tick(bot);
            if (status == BlockMiner.Status.FAILED) {
                fail("no_place_for_furnace:clear_failed:" + clearMiner.failureReason());
                return;
            }
            if (status != BlockMiner.Status.DONE) {
                return;
            }
        }

        OptionalInt furnaceSlot = InventoryAction.findItem(bot, Items.FURNACE);
        if (furnaceSlot.isEmpty()) {
            fail("missing minecraft:furnace");
            return;
        }

        // When DigDown returns to the surface the bot is often standing right next to the stairwell
        // opening. Always taking the first air block would pick the floating space above the stairs
        // first, which has no clickable support at all, so under strict survival rules placeBlockAt
        // legitimately returns no_adjacent_block; but the other directions are usually usable ground.
        // Try every horizontal empty space in turn so one bad candidate doesn't prematurely fail the
        // whole food/mining chain.
        boolean foundAir = false;
        boolean furnaceEquipped = false;
        ActionResult lastFailure = ActionResult.failed("no_adjacent_block");
        BlockPos origin = bot.blockPosition();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = origin.relative(direction);
            if (!bot.level().getBlockState(candidate).isAir()) {
                continue;
            }
            foundAir = true;
            if (!furnaceEquipped) {
                InventoryAction.equipFromSlot(bot, furnaceSlot.getAsInt());
                furnaceEquipped = true;
            }
            ActionResult result = BuildAction.placeBlockAt(bot, candidate);
            if (result.isFailed()) {
                lastFailure = result;
                continue;
            }
            furnacePos = candidate.immutable();
            // R2 fix: remember the furnace position -- after mining far away, nearestFurnace (a local
            // scan) can't find the furnace it placed, and missing furnace kills the whole chain
            // (observed in real_diamond testing: after using up the first furnace, mining a second
            // batch of iron and coming back, the furnace had "vanished").
            io.github.zoyluo.minecraftai.memory.BotMemoryStore.INSTANCE.of(bot.getUUID())
                    .markPlace("furnace", bot.level(), furnacePos);
            phase = Phase.LOADING;
            return;
        }

        // Boxed in on all sides, or none of the existing empty spaces have valid visible support: mine an adjacent breakable block and retry.
        if (!clearSpaceForFurnace(bot)) {
            fail(foundAir
                    ? "place_furnace_failed: " + lastFailure.reason()
                    : "no_place_for_furnace");
        }
    }

    private void loadFurnace(AIPlayerEntity bot) {
        AbstractFurnaceBlockEntity furnace = furnace(bot);
        if (furnace == null) {
            phase = Phase.FINDING_FURNACE;
            return;
        }
        ItemStack inputSlot = furnace.getItem(0);
        if (!inputSlot.isEmpty() && !inputSlot.is(input)) {
            fail("furnace_input_occupied: " + BuiltInRegistries.ITEM.getKey(inputSlot.getItem()));
            return;
        }
        ItemStack outputSlot = furnace.getItem(2);
        if (!outputSlot.isEmpty() && !outputSlot.is(output)) {
            fail("unexpected_output: " + BuiltInRegistries.ITEM.getKey(outputSlot.getItem()));
            return;
        }
        int outputQueued = outputSlot.is(output) ? outputSlot.getCount() : 0;
        int inputQueued = inputSlot.is(input) ? inputSlot.getCount() : 0;
        int remainingToQueue = targetCount - collected - outputQueued - inputQueued;
        int inputRoom = inputSlot.isEmpty() ? 64 : 64 - inputSlot.getCount();
        int inventoryInput = InventoryAction.countItem(bot, input);
        int inputToLoad = Math.min(Math.min(remainingToQueue, inputRoom), inventoryInput);
        if (remainingToQueue > 0 && inputToLoad <= 0 && inputQueued == 0) {
            fail("missing " + BuiltInRegistries.ITEM.getKey(input) + " x" + remainingToQueue);
            return;
        }
        ItemStack fuelSlot = furnace.getItem(1);
        FuelChoice fuel = null;
        // Once a vanilla furnace starts burning it immediately consumes one unit of fuel; the fuel slot
        // can be empty while burnTime is still enough to keep smelting.
        // LIT is a player-visible block state; an empty slot alone must not trigger repeated refueling or a false out_of_fuel report.
        if (fuelSlot.isEmpty() && !isBurning(bot)) {
            int smeltsNeedingFuel = Math.max(1, inputQueued + Math.max(inputToLoad, 0));
            fuel = chooseFuel(bot, smeltsNeedingFuel);
            if (fuel == null) {
                fetchFuelFromBase(bot, smeltsNeedingFuel);
                fuel = chooseFuel(bot, smeltsNeedingFuel);
            }
            if (fuel == null) {
                fail("out_of_fuel");
                return;
            }
        }
        if (inputToLoad > 0) {
            if (!InventoryAction.removeItems(bot, input, inputToLoad)) {
                fail("missing " + BuiltInRegistries.ITEM.getKey(input) + " x" + inputToLoad);
                return;
            }
            furnace.setItem(0, new ItemStack(input, inputSlot.getCount() + inputToLoad));
        }

        if (fuel != null) {
            if (!InventoryAction.removeItems(bot, fuel.item(), fuel.count())) {
                fail("out_of_fuel: " + BuiltInRegistries.ITEM.getKey(fuel.item()));
                return;
            }
            furnace.setItem(1, new ItemStack(fuel.item(), fuel.count()));
        }
        furnace.setChanged();
        phase = Phase.SMELTING;
    }

    private void waitForOutput(AIPlayerEntity bot) {
        AbstractFurnaceBlockEntity furnace = furnace(bot);
        if (furnace == null) {
            fail("furnace_missing");
            return;
        }
        ItemStack outputSlot = furnace.getItem(2);
        if (!outputSlot.isEmpty() && !outputSlot.is(output)) {
            fail("unexpected_output: " + BuiltInRegistries.ITEM.getKey(outputSlot.getItem()));
            return;
        }
        if (!outputSlot.isEmpty()) {
            phase = Phase.COLLECTING;
            return;
        }
        ItemStack inputSlot = furnace.getItem(0);
        ItemStack fuelSlot = furnace.getItem(1);
        if (collected < targetCount
                && (inputSlot.isEmpty() || (fuelSlot.isEmpty() && !isBurning(bot)))) {
            phase = Phase.LOADING;
        }
    }

    private void collectOutput(AIPlayerEntity bot) {
        AbstractFurnaceBlockEntity furnace = furnace(bot);
        if (furnace == null) {
            fail("furnace_missing");
            return;
        }
        ItemStack outputSlot = furnace.getItem(2);
        if (outputSlot.isEmpty()) {
            phase = Phase.SMELTING;
            return;
        }
        if (!outputSlot.is(output)) {
            fail("unexpected_output: " + BuiltInRegistries.ITEM.getKey(outputSlot.getItem()));
            return;
        }
        int take = Math.min(targetCount - collected, outputSlot.getCount());
        ActionResult result = InventoryAction.giveItem(bot, new ItemStack(output, take));
        if (result.isFailed()) {
            fail(result.reason());
            return;
        }
        outputSlot.shrink(take);
        furnace.setChanged();
        collected += take;
        if (collected >= targetCount) {
            complete();
        } else {
            phase = Phase.LOADING;
        }
    }

    private AbstractFurnaceBlockEntity furnace(AIPlayerEntity bot) {
        if (furnacePos == null
                || bot.getEyePosition().distanceToSqr(furnacePos.getCenter()) > REACH_SQUARED
                || !io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlock(bot, furnacePos)
                || !WorkshopLocator.isCompatibleFurnace(bot, furnacePos, input, output)) {
            return null;
        }
        return bot.level().getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity furnace ? furnace : null;
    }

    private boolean isBurning(AIPlayerEntity bot) {
        if (furnacePos == null) {
            return false;
        }
        var state = bot.level().getBlockState(furnacePos);
        return state.hasProperty(AbstractFurnaceBlock.LIT) && state.getValue(AbstractFurnaceBlock.LIT);
    }

    private static Optional<BlockPos> nearestFurnace(
            AIPlayerEntity bot,
            Item input,
            Item output,
            int requestedItems,
            Set<BlockPos> excluded) {
        return WorkshopLocator.nearestCompatibleFurnace(bot, input, output, requestedItems, excluded);
    }

    // When boxed in: mine one horizontally adjacent breakable block to clear a space for the furnace. Returns false = no breakable block on any side (e.g. bedrock/fluid).
    private boolean clearSpaceForFurnace(AIPlayerEntity bot) {
        var world = bot.level();
        BlockPos origin = bot.blockPosition();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = origin.relative(direction);
            var s = world.getBlockState(candidate);
            if (s.isAir() || !s.getFluidState().isEmpty() || s.getDestroySpeed(world, candidate) < 0.0F
                    || world.getBlockEntity(candidate) != null) {
                continue;
            }
            BlockMiner.Status st = clearMiner.target() != null && clearMiner.target().equals(candidate)
                    ? clearMiner.tick(bot)
                    : beginClear(bot, candidate);
            return st != BlockMiner.Status.FAILED;
        }
        return false;
    }

    private BlockMiner.Status beginClear(AIPlayerEntity bot, BlockPos pos) {
        clearMiner.begin(bot, pos);
        return clearMiner.tick(bot);
    }

    private static FuelChoice chooseFuel(AIPlayerEntity bot, int smeltCount) {
        int ticksNeeded = smeltCount * 200;
        FuelChoice partial = null; // the "partial load" candidate for when no single type is enough on its own (pick the one that burns longest)
        for (Map.Entry<Item, Integer> entry : FUEL_TICKS.entrySet()) {
            int available = InventoryAction.countItem(bot, entry.getKey());
            if (available <= 0) {
                continue;
            }
            int needed = divideRoundUp(ticksNeeded, entry.getValue());
            if (available >= needed) {
                return new FuelChoice(entry.getKey(), needed); // one single type is enough for all of it -> just use it
            }
            // Root-cause fix (real_armor out_of_fuel): smelting 26 iron needs 18 logs of one wood type,
            // but fuel is often split like oak13+birch6, where no single type is enough -> the old logic
            // returned null and wrongly reported no fuel. In fact, once the furnace finishes burning it
            // re-enters LOADING and loads the next type, so "load however much is available" works --
            // pick the type with the highest total burn value to fill first, and top up the remainder on
            // later reloads. Prefer whichever maximizes available*ticks (burns longest, fewest reloads).
            if (partial == null
                    || (long) available * entry.getValue()
                       > (long) partial.count() * FUEL_TICKS.get(partial.item())) {
                partial = new FuelChoice(entry.getKey(), available);
            }
        }
        return partial; // load in whatever partial fuel is available; only null when there is truly no fuel at all
    }

    private static void fetchFuelFromBase(AIPlayerEntity bot, int smeltCount) {
        BlockPos base = BotMemoryStore.INSTANCE.of(bot.getUUID())
                .placeIn(bot.level(), "base")
                .orElse(null);
        if (base == null) {
            return;
        }
        for (Map.Entry<Item, Integer> entry : FUEL_TICKS.entrySet()) {
            Item fuel = entry.getKey();
            int needed = divideRoundUp(smeltCount * 200, entry.getValue());
            if (InventoryAction.countItem(bot, fuel) >= needed) {
                return;
            }
            for (BlockPos pos : fuelContainers(bot, base, fuel)) {
                // Opening it is what shows the bot the contents; ranking above used the ledger only.
                Container container = ContainerAction.open(bot, pos, false).orElse(null);
                if (container == null) {
                    continue;
                }
                int missing = needed - InventoryAction.countItem(bot, fuel);
                if (missing <= 0) {
                    return;
                }
                ContainerAction.TransferResult result = ContainerAction.withdraw(bot, pos, container, fuel, missing);
                if (result.movedAny() && InventoryAction.countItem(bot, fuel) >= needed) {
                    return;
                }
            }
        }
    }

    /** Storage in reach and sight of the bot near the base, ledger-known holders of this fuel first, then nearest. */
    private static java.util.List<BlockPos> fuelContainers(AIPlayerEntity bot, BlockPos base, Item fuel) {
        return StorageTargets.observedStorage(bot, base, BASE_FUEL_RADIUS, false).stream()
                .filter(pos -> ContainerAction.inReachAndSight(bot, pos))
                .sorted(Comparator
                        .comparing((BlockPos pos) -> StorageTargets.ledgerCount(bot, pos, true, fuel) <= 0)
                        .thenComparingDouble(pos -> pos.distSqr(bot.blockPosition())))
                .toList();
    }

    private static int divideRoundUp(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private record FuelChoice(Item item, int count) {
    }
}

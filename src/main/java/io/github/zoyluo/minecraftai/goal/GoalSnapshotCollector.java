package io.github.zoyluo.minecraftai.goal;

import io.github.zoyluo.minecraftai.action.ContainerAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mining.MiningFoodReserve;
import io.github.zoyluo.minecraftai.mining.ToolTier;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.task.BlueprintSchema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public final class GoalSnapshotCollector {
    private static final int STATION_RADIUS = 8;
    private static final int CONTAINER_RADIUS = 16;
    private static final int CONTAINER_HEIGHT = 6;
    /** A ledger entry older than this (five game minutes) no longer counts toward a stored-items goal: the player may have emptied it since. */
    static final long LEDGER_FRESH_TICKS = 6000L;

    private GoalSnapshotCollector() {
    }

    public record Context(
            BlockPos origin,
            Set<BlockPos> boundContainers,
            BlueprintSchema blueprint,
            BlockPos buildAnchor,
            int buildPlaced,
            int buildSkipped,
            /** Exact successful handoffs already committed by a compound fulfillment mission. */
            Set<Goal.Allocation> completedDeliveries
    ) {
        /** Backward-compatible context shape for ordinary goals with no handoff receipts. */
        public Context(BlockPos origin,
                       Set<BlockPos> boundContainers,
                       BlueprintSchema blueprint,
                       BlockPos buildAnchor,
                       int buildPlaced,
                       int buildSkipped) {
            this(origin, boundContainers, blueprint, buildAnchor, buildPlaced, buildSkipped, Set.of());
        }

        public Context {
            origin = origin == null ? BlockPos.ZERO : origin.immutable();
            boundContainers = boundContainers == null ? Set.of() : boundContainers.stream()
                    .map(BlockPos::immutable).collect(java.util.stream.Collectors.toUnmodifiableSet());
            buildAnchor = buildAnchor == null ? null : buildAnchor.immutable();
            completedDeliveries = completedDeliveries == null ? Set.of() : Set.copyOf(completedDeliveries);
        }

        public static Context at(BlockPos origin) {
            return new Context(origin, Set.of(), null, null, 0, 0);
        }
    }

    public static GoalSnapshot collect(AIPlayerEntity bot, Goal goal, Context context) {
        Context resolved = context == null ? Context.at(bot.blockPosition()) : context;
        Map<String, Integer> inventory = inventoryCounts(bot);
        Set<String> capabilities = armorCapabilities(bot);
        if (goal instanceof Goal.Workstation || goal instanceof Goal.Stockpile) {
            CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "goal_postcondition");
        }
        Map<String, Integer> nearbyBlocks = goal instanceof Goal.Workstation
                ? stationCounts(bot, resolved.origin()) : Map.of();
        Map<String, Integer> containerItems = goal instanceof Goal.Stockpile
                ? containerCounts(bot, resolved) : Map.of();
        int foodUnits = goal instanceof Goal.Food
                ? MiningFoodReserve.units(bot.getInventory()) : 0;
        Optional<StructureReport> structure = Optional.empty();
        if (goal instanceof Goal.Build && resolved.blueprint() != null && resolved.buildAnchor() != null) {
            structure = Optional.of(StructureVerifier.verify(bot.level(), resolved.blueprint(),
                    resolved.buildAnchor(), resolved.buildPlaced(), resolved.buildSkipped()));
        }
        return new GoalSnapshot(inventory, ToolTier.bestPickaxeTier(bot), capabilities,
                nearbyBlocks, containerItems, foodUnits, structure);
    }

    /**
     * Uses the same usable-inventory domain as goal predicates and planning: normal inventory,
     * offhand, and equipment, excluding one-use-left damaged stacks.  Fresh goal baselines must
     * not mix this domain with a handoff-only counter or an equipped preexisting item could be
     * mistaken for newly produced inventory.
     */
    public static int inventoryCount(AIPlayerEntity bot, Item item) {
        if (bot == null || item == null) {
            return 0;
        }
        String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
        return inventoryCounts(bot).getOrDefault(itemId, 0);
    }

    private static Map<String, Integer> inventoryCounts(AIPlayerEntity bot) {
        Map<String, Integer> counts = new HashMap<>();
        for (ItemStack stack : allStacks(bot)) {
            if (!stack.isEmpty() && !nearlyBroken(stack)) {
                counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    private static Set<String> armorCapabilities(AIPlayerEntity bot) {
        Set<String> capabilities = new HashSet<>();
        for (ItemStack stack : allStacks(bot)) {
            if (stack.isEmpty() || nearlyBroken(stack)) {
                continue;
            }
            Item item = stack.getItem();
            if (item == Items.IRON_HELMET || item == Items.DIAMOND_HELMET || item == Items.NETHERITE_HELMET) {
                capabilities.add("helmet");
            } else if (item == Items.IRON_CHESTPLATE || item == Items.DIAMOND_CHESTPLATE || item == Items.NETHERITE_CHESTPLATE) {
                capabilities.add("chestplate");
            } else if (item == Items.IRON_LEGGINGS || item == Items.DIAMOND_LEGGINGS || item == Items.NETHERITE_LEGGINGS) {
                capabilities.add("leggings");
            } else if (item == Items.IRON_BOOTS || item == Items.DIAMOND_BOOTS || item == Items.NETHERITE_BOOTS) {
                capabilities.add("boots");
            } else if (item == Items.IRON_SWORD || item == Items.DIAMOND_SWORD || item == Items.NETHERITE_SWORD) {
                capabilities.add("sword");
            }
        }
        return capabilities;
    }

    private static List<ItemStack> allStacks(AIPlayerEntity bot) {
        List<ItemStack> stacks = new ArrayList<>();
        stacks.addAll(bot.getInventory().getNonEquipmentItems());
        stacks.add(bot.getItemBySlot(EquipmentSlot.OFFHAND));
        stacks.add(bot.getItemBySlot(EquipmentSlot.HEAD));
        stacks.add(bot.getItemBySlot(EquipmentSlot.CHEST));
        stacks.add(bot.getItemBySlot(EquipmentSlot.LEGS));
        stacks.add(bot.getItemBySlot(EquipmentSlot.FEET));
        return stacks;
    }

    private static Map<String, Integer> stationCounts(AIPlayerEntity bot, BlockPos origin) {
        Map<String, Integer> counts = new HashMap<>();
        for (BlockPos pos : BlockPos.withinManhattan(origin, STATION_RADIUS, 4, STATION_RADIUS)) {
            if (!ObservableWorldQuery.canObserveBlock(bot, pos)) {
                continue;
            }
            var state = bot.level().getBlockState(pos);
            if (state.is(Blocks.CRAFTING_TABLE)) {
                counts.merge("minecraft:crafting_table", 1, Integer::sum);
            } else if (state.is(Blocks.FURNACE)) {
                counts.merge("minecraft:furnace", 1, Integer::sum);
            } else if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST)) {
                counts.merge("minecraft:chest", 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Item totals in storage as the bot itself last saw them: its container ledger, which is written
     * only when the bot opened a container (deposit, withdraw or inspect). Nothing is read out of a
     * closed container, so a goal cannot be satisfied by peeking into chests from a distance. Only entries verified within
     * the last {@link #LEDGER_FRESH_TICKS} count, so a chest the player emptied long ago cannot keep
     * a stockpile goal satisfied (the goal is then replanned and opening the containers again re-verifies them).
     */
    private static Map<String, Integer> containerCounts(AIPlayerEntity bot, Context context) {
        String dimension = bot.level().dimension().identifier().toString();
        var ledger = io.github.zoyluo.minecraftai.memory.BotMemoryStore.INSTANCE.of(bot.getUUID()).containers();
        long now = bot.level().getGameTime();
        Map<String, Integer> counts = new HashMap<>();
        Set<BlockPos> seen = new HashSet<>();
        if (context.boundContainers().isEmpty()) {
            for (var entry : ledger.inDimension(dimension)) {
                if (withinContainerBox(entry.pos(), context.origin()) && fresh(entry, now)) {
                    addLedgerEntry(counts, entry);
                }
            }
            return counts;
        }
        for (BlockPos bound : context.boundContainers()) {
            BlockPos canonical = ContainerAction.canonicalPos(bot.level(), bound);
            if (!seen.add(canonical)) {
                continue;
            }
            ledger.get(dimension, canonical).or(() -> ledger.get(dimension, bound))
                    .filter(entry -> fresh(entry, now))
                    .ifPresent(entry -> addLedgerEntry(counts, entry));
        }
        return counts;
    }

    /** The box the old direct scan used: |dx| and |dz| within the container radius and |dy| within +/-6 blocks. */
    private static boolean withinContainerBox(BlockPos pos, BlockPos origin) {
        return Math.abs(pos.getX() - origin.getX()) <= CONTAINER_RADIUS
                && Math.abs(pos.getY() - origin.getY()) <= CONTAINER_HEIGHT
                && Math.abs(pos.getZ() - origin.getZ()) <= CONTAINER_RADIUS;
    }

    private static boolean fresh(io.github.zoyluo.minecraftai.memory.ContainerLedger.Entry entry, long now) {
        return now - entry.lastVerified() <= LEDGER_FRESH_TICKS;
    }

    private static void addLedgerEntry(Map<String, Integer> counts,
                                       io.github.zoyluo.minecraftai.memory.ContainerLedger.Entry entry) {
        entry.items().forEach((id, count) -> counts.merge(id, count, Integer::sum));
    }

    private static boolean nearlyBroken(ItemStack stack) {
        return stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1;
    }
}

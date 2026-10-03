package io.github.zoyluo.minecraftai.goal;

import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public record GoalStep(Kind kind,
                       Item item,
                       int count,
                       Block block,
                       Set<Block> ores,
                       Item input,
                       Item output,
                       BlockPos pos,
                       String tag,
                       boolean bestEffort) {
    public enum Kind {
        GATHER,
        MINE,
        MINE_ORE,
        MINING_SERVICE,
        CRAFT,
        SMELT,
        MOVE,
        FARM,
        HUNT,
        COOK_FOOD,
        MILK_COW,
        PLACE_STATIONS,
        STOCKPILE,
        DESCEND_TO_Y,
        ACQUIRE_WATER,
        MAKE_OBSIDIAN,
        BUILD,
        /** A survival-legal handoff of a planned item allocation to a named player. */
        GIVE_ITEM
    }

    public GoalStep {
        count = kind == Kind.MINING_SERVICE && tag != null
                && tag.startsWith("rare_ore_batch:")
                ? Math.max(0, count) : Math.max(1, count);
        ores = ores == null ? Set.of() : Set.copyOf(ores);
        pos = pos == null ? null : pos.immutable();
        if (kind == Kind.GIVE_ITEM
                && (item == null || tag == null || tag.isBlank() || !validRecipient(tag))) {
            throw new IllegalArgumentException("invalid_give_item_step");
        }
    }

    public static GoalStep gather(Item item, int count) {
        return new GoalStep(Kind.GATHER, item, count, null, Set.of(), null, null, null, null, false);
    }

    public static GoalStep mine(Block block, int count) {
        return new GoalStep(Kind.MINE, null, count, block, Set.of(), null, null, null, null, false);
    }

    public static GoalStep mineOre(Set<Block> ores, int count) {
        return new GoalStep(Kind.MINE_ORE, null, count, null, ores, null, null, null, null, false);
    }

    /** Long-running mining checkpoint between bounded ore batches. */
    public static GoalStep miningService(Set<Block> ores, int completedTarget) {
        return miningService(ores, completedTarget, false);
    }

    public static GoalStep miningService(Set<Block> ores,
                                         int completedTarget,
                                         boolean maintainTunnelingTools) {
        return new GoalStep(Kind.MINING_SERVICE, null, Math.max(1, completedTarget),
                null, ores, null, null, null,
                maintainTunnelingTools ? "after_batch:channel_tools" : "after_batch", false);
    }

    /** Capacity-only mining hand-off. Unlike an inter-batch boundary it does not rebuild the full
     * tunnelling pool; it physically retires junk and proves four delivery/crafting slots before
     * the same batch retries or its parent expedition starts the next underground dependency. */
    public static GoalStep miningHandoffService(Set<Block> ores, int completedTarget) {
        return miningHandoffService(ores, completedTarget,
                io.github.zoyluo.minecraftai.mining.MiningBudget.EMERGENCY_STONE_LIKE);
    }

    public static GoalStep miningHandoffService(Set<Block> ores,
                                                int completedTarget,
                                                int protectedStoneLike) {
        return new GoalStep(Kind.MINING_SERVICE, null, Math.max(1, completedTarget),
                null, ores, null, null, null,
                "after_final_handoff:stone=" + Math.max(
                        io.github.zoyluo.minecraftai.mining.MiningBudget.EMERGENCY_STONE_LIKE,
                        protectedStoneLike), false);
    }

    /** Exact service boundary inside one original-target long rare-ore mission. */
    public static GoalStep rareOreService(Set<Block> ores,
                                          int completedTarget,
                                          int missionTarget) {
        if (missionTarget < 8 || completedTarget < 0 || completedTarget >= missionTarget) {
            throw new IllegalArgumentException("invalid_rare_ore_service_step:target="
                    + missionTarget + ":boundary=" + completedTarget);
        }
        return new GoalStep(Kind.MINING_SERVICE, null, completedTarget,
                null, ores, null, null, null,
                "rare_ore_batch:channel_tools:target=" + missionTarget, false);
    }

    /** One-time mission-owned inventory seal immediately before a 64-diamond final descent. */
    public static GoalStep rareDescentKitService(Set<Block> ores, int missionTarget) {
        if (missionTarget != 64) {
            throw new IllegalArgumentException(
                    "invalid_rare_descent_kit_target:" + missionTarget);
        }
        return new GoalStep(Kind.MINING_SERVICE, null, 1,
                null, ores, null, null, null,
                "rare_descent_kit:channel_tools:target=" + missionTarget, false);
    }

    /** Eight-block service boundary inside one original-target obsidian transaction. */
    public static GoalStep obsidianService(int completedTarget) {
        return obsidianService(completedTarget, 32);
    }

    public static GoalStep obsidianService(int completedTarget, int transactionTarget) {
        if (completedTarget <= 0 || completedTarget % 8 != 0
                || transactionTarget <= completedTarget) {
            throw new IllegalArgumentException("invalid_obsidian_service_step:target="
                    + transactionTarget + ":boundary=" + completedTarget);
        }
        return new GoalStep(Kind.MINING_SERVICE, null, completedTarget,
                null, Set.of(Blocks.OBSIDIAN), null, null, null,
                "obsidian_8:channel_tools:target="
                        + transactionTarget, false);
    }

    /** One-time readiness gate before the first obsidian search transaction opens. */
    public static GoalStep obsidianPreflight() {
        return obsidianPreflight(32);
    }

    public static GoalStep obsidianPreflight(int transactionTarget) {
        if (transactionTarget <= 0) {
            throw new IllegalArgumentException(
                    "invalid_obsidian_preflight_step:" + transactionTarget);
        }
        return new GoalStep(Kind.MINING_SERVICE, null, 1,
                null, Set.of(Blocks.OBSIDIAN), null, null, null,
                "obsidian_preflight:channel_tools:target="
                        + transactionTarget, false);
    }

    public static GoalStep craft(Item item, int count) {
        return new GoalStep(Kind.CRAFT, item, count, null, Set.of(), null, null, null, null, false);
    }

    public static GoalStep smelt(Item input, Item output, int count) {
        return new GoalStep(Kind.SMELT, null, count, null, Set.of(), input, output, null, null, false);
    }

    public static GoalStep move(BlockPos pos) {
        return new GoalStep(Kind.MOVE, null, 1, null, Set.of(), null, null, pos, null, false);
    }

    /** P3: FARM step -- block=crop block, input=seed, item=produce item, count=amount to harvest. */
    public static GoalStep farm(Block crop, Item seed, Item produce, int count) {
        return new GoalStep(Kind.FARM, produce, count, crop, Set.of(), seed, null, null, null, false);
    }

    /** Layer 4: HUNT step -- kill animals to obtain count raw meat (best-effort: skipped when no animals are nearby, does not block the mining goal). */
    public static GoalStep hunt(int count) {
        return new GoalStep(Kind.HUNT, null, count, null, Set.of(), null, null, null, null, false);
    }

    /** Expedition hunt batch. The tag prevents adjacent bounded batches from being merged. */
    public static GoalStep huntBatch(int count, int batchIndex) {
        return new GoalStep(Kind.HUNT, null, count, null, Set.of(), null, null, null,
                "food_batch:" + Math.max(1, batchIndex), false);
    }

    /** P0 food loop: COOK_FOOD step -- cook all raw food in the inventory into count cooked food (best-effort, skipped if there's no furnace/fuel). */
    public static GoalStep cookFood(int count) {
        return new GoalStep(Kind.COOK_FOOD, null, count, null, Set.of(), null, null, null, null, false);
    }

    /** Cake chain: MILK_COW step -- milk count buckets of milk using an empty bucket (requires an empty bucket in the inventory + a nearby cow; fails as best-effort if either is missing). */
    public static GoalStep milkCow(int count) {
        return new GoalStep(Kind.MILK_COW, null, count, null, Set.of(), null, null, null, null, false);
    }

    /** Phase 2: place the crafting table/furnace/chest trio (fixed blocks, no parameters). */
    public static GoalStep placeStations() {
        return new GoalStep(Kind.PLACE_STATIONS, null, 1, null, Set.of(), null, null, null, null, false);
    }

    /** Phase 3: store inventory resources into a nearby chest (best-effort; item is only a semantic marker). */
    public static GoalStep stockpile(Item item) {
        return new GoalStep(Kind.STOCKPILE, item, 1, null, Set.of(), null, null, null, null, false);
    }

    /** Deep mining: DESCEND_TO_Y step -- dig down to the specified Y (carried via pos.y, negative values allowed; x/z are ignored). */
    public static GoalStep descendToY(int y) {
        return new GoalStep(Kind.DESCEND_TO_Y, null, 1, null, Set.of(), null, null,
                new BlockPos(0, y, 0), null, false);
    }

    /** Obsidian expedition water fetch: carry an empty bucket back to the mission's surface anchor, physically search for a visible water source, and fill the bucket using vanilla interaction. */
    public static GoalStep acquireWater() {
        return new GoalStep(Kind.ACQUIRE_WATER, null, 1, null, Set.of(), null, null,
                null, "strict_survival", false);
    }

    /** Make obsidian: MAKE_OBSIDIAN step -- pour water on lava to create count blocks on the spot (requires a bucket + diamond pickaxe in the inventory). */
    public static GoalStep makeObsidian(int count) {
        return new GoalStep(Kind.MAKE_OBSIDIAN, null, count, null, Set.of(), null, null, null, null, false);
    }

    /** Queue an exact item handoff after the fulfillment planner has produced every allocation. */
    public static GoalStep give(Item item, int count, String recipient) {
        return new GoalStep(Kind.GIVE_ITEM, item, count, null, Set.of(), null, null, null,
                recipient == null ? "" : recipient.trim(), false);
    }

    /** Build a house: BUILD step -- tag=blueprint name (e.g. small_hut/hut_5x5), materials already back-calculated and prepared during the planning phase. */
    public static GoalStep build(String blueprintName) {
        return new GoalStep(Kind.BUILD, null, 1, null, Set.of(), null, null, null, blueprintName, false);
    }

    public GoalStep withCount(int newCount) {
        return new GoalStep(kind, item, newCount, block, ores, input, output, pos, tag, bestEffort);
    }

    /** Marks a provisioning step as optional without changing its task payload. */
    public GoalStep asBestEffort() {
        return bestEffort ? this
                : new GoalStep(kind, item, count, block, ores, input, output, pos, tag, true);
    }

    public boolean maintainsTunnelingTools() {
        return kind == Kind.MINING_SERVICE && tag != null && tag.contains("channel_tools");
    }

    public boolean isMiningHandoffService() {
        return kind == Kind.MINING_SERVICE && tag != null
                && tag.startsWith("after_final_handoff:stone=");
    }

    public int miningHandoffStoneLikeReserve() {
        if (!isMiningHandoffService()) {
            return io.github.zoyluo.minecraftai.mining.MiningBudget.EMERGENCY_STONE_LIKE;
        }
        for (String component : tag.split(":")) {
            if (component.startsWith("stone=")) {
                int reserve = Integer.parseInt(component.substring("stone=".length()));
                if (reserve < io.github.zoyluo.minecraftai.mining.MiningBudget.EMERGENCY_STONE_LIKE) {
                    throw new IllegalStateException("invalid_mining_handoff_stone_reserve");
                }
                return reserve;
            }
        }
        throw new IllegalStateException("missing_mining_handoff_stone_reserve");
    }

    public boolean isObsidianService() {
        return kind == Kind.MINING_SERVICE && tag != null
                && tag.startsWith("obsidian_8:");
    }

    public boolean isObsidianPreflight() {
        return kind == Kind.MINING_SERVICE
                && tag != null && tag.startsWith("obsidian_preflight:");
    }

    public boolean isRareOreService() {
        return kind == Kind.MINING_SERVICE && tag != null
                && tag.startsWith("rare_ore_batch:");
    }

    public boolean isRareDescentKitService() {
        return kind == Kind.MINING_SERVICE && tag != null
                && tag.startsWith("rare_descent_kit:");
    }

    /** The recipient attached to a {@link Kind#GIVE_ITEM} step. */
    public String giveRecipient() {
        if (kind != Kind.GIVE_ITEM || tag == null || !validRecipient(tag)) {
            throw new IllegalStateException("invalid_give_item_recipient");
        }
        return tag;
    }

    public int rareOreMissionTarget() {
        if (!isRareOreService()) {
            return 0;
        }
        return taggedServiceTarget("rare_ore");
    }

    public int rareDescentKitMissionTarget() {
        if (!isRareDescentKitService()) {
            return 0;
        }
        return taggedServiceTarget("rare_descent_kit");
    }

    public int obsidianTransactionTarget() {
        if (!isObsidianService() && !isObsidianPreflight() || tag == null) {
            return 0;
        }
        return taggedServiceTarget("obsidian_transaction");
    }

    private int taggedServiceTarget(String identity) {
        for (String component : tag.split(":")) {
            if (component.startsWith("target=")) {
                int target = Integer.parseInt(component.substring("target=".length()));
                if (target <= 0) {
                    throw new IllegalStateException("invalid_" + identity + "_target");
                }
                return target;
            }
        }
        throw new IllegalStateException("missing_" + identity + "_target");
    }

    public boolean sameTarget(GoalStep other) {
        return other != null
                && kind == other.kind
                && item == other.item
                && block == other.block
                && input == other.input
                && output == other.output
                && ores.equals(other.ores)
                && java.util.Objects.equals(pos, other.pos)
                && java.util.Objects.equals(tag, other.tag)
                && bestEffort == other.bestEffort;
    }

    /** English step labels are used in player-facing goal progress and the companion panel. */
    public String describe() {
        return switch (kind) {
            case GATHER -> "Gather " + itemName(item) + " x" + count;
            case MINE -> "Mine " + blockName(block) + " x" + count;
            case MINE_ORE -> "Mine ore " + ores.stream()
                    .map(GoalStep::blockName)
                    .sorted()
                    .collect(Collectors.joining("/")) + " x" + count;
            case MINING_SERVICE -> isObsidianPreflight()
                    ? "Obsidian first-batch supply check"
                    : isObsidianService()
                    ? "Obsidian supply checkpoint (total x" + count + ")"
                    : isRareDescentKitService()
                    ? "Diamond expedition descent supply check"
                    : isRareOreService()
                    ? "Rare-ore supply checkpoint (total x" + count + ")"
                    : isMiningHandoffService()
                    ? "Mining capacity handoff (total x" + count + ")"
                    : "Mining supply checkpoint (total x" + count + ")";
            case CRAFT -> "Craft " + itemName(item) + " x" + count;
            case SMELT -> "Smelt " + itemName(input) + " into " + itemName(output) + " x" + count;
            case MOVE -> "Move to " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
            case FARM -> "Farm " + blockName(block) + " x" + count;
            case HUNT -> "Hunt for food x" + count;
            case COOK_FOOD -> "Cook food x" + count;
            case MILK_COW -> "Milk cows x" + count;
            case PLACE_STATIONS -> "Place a crafting table, furnace, and chest";
            case STOCKPILE -> "Store " + itemName(item) + " in a chest";
            case DESCEND_TO_Y -> "Descend to Y=" + pos.getY();
            case ACQUIRE_WATER -> "Find a water source and fill a bucket";
            case MAKE_OBSIDIAN -> "Make obsidian x" + count;
            case BUILD -> "Build " + tag;
            case GIVE_ITEM -> "Give " + itemName(item) + " x" + count + " to " + tag;
        };
    }

    /** Minecraft profile names are ASCII identifiers of at most sixteen characters. */
    private static boolean validRecipient(String recipient) {
        return recipient != null && recipient.length() <= 16
                && recipient.matches("[A-Za-z0-9_]+");
    }

    private static String itemName(Item item) {
        Identifier id = item == null ? null : BuiltInRegistries.ITEM.getKey(item);
        return id == null ? "unknown item" : id.getPath().replace('_', ' ');
    }

    private static String blockName(Block block) {
        Identifier id = block == null ? null : BuiltInRegistries.BLOCK.getKey(block);
        return id == null ? "unknown block" : id.getPath().replace('_', ' ');
    }
}

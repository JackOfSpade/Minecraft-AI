package io.github.zoyluo.minecraftai.goal;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.FarmAction;
import io.github.zoyluo.minecraftai.action.MaterialPalette;
import io.github.zoyluo.minecraftai.craft.AcquisitionHints;
import io.github.zoyluo.minecraftai.craft.SmeltChain;
import io.github.zoyluo.minecraftai.craft.RecipeRegistry;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.mining.MiningChain;
import io.github.zoyluo.minecraftai.mining.MiningBudget;
import io.github.zoyluo.minecraftai.mining.MiningFoodReserve;
import io.github.zoyluo.minecraftai.mining.OreProspector;
import io.github.zoyluo.minecraftai.mining.OreScan;
import io.github.zoyluo.minecraftai.mining.ToolTier;
import io.github.zoyluo.minecraftai.task.BlueprintLoader;
import io.github.zoyluo.minecraftai.task.BlueprintSchema;
import io.github.zoyluo.minecraftai.task.EmergencyShelterTask;
import io.github.zoyluo.minecraftai.task.HuntTask;
import io.github.zoyluo.minecraftai.task.MiningServiceTask;
import io.github.zoyluo.minecraftai.task.ServicePolicy;
import io.github.zoyluo.minecraftai.task.WorkshopLocator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

public final class GoalPlanner {

    private GoalPlanner() {
    }

    // Tier 3 armor prerequisite: full iron armor set (4 pieces) + matching equipment slots.
    private static final List<Item> IRON_ARMOR = List.of(
            Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS);
    // Hazard hardening: torch count reserved before deep mining (used by DangerWatcher to light up
    // dark underground areas and prevent mob spawns).
    private static final int TORCH_TARGET = 8;
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    // Tier 4 food provisioning: the safe-reserve accounting is unified in MiningFoodReserve; raw
    // meat is only an intermediate item awaiting processing.
    private static final List<Item> RAW_MEAT_ITEMS = List.of(
            Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN, Items.RABBIT);
    /** Matches DescendToYTask's dark-shaft placement cadence. */
    private static final int DESCEND_TORCH_EVERY = 6;
    // Obsidian expedition provisioning is a hard gate before entering the water-acquisition/diamond
    // chain. Food scales with the mission quantity; for the formula and historical basis (the old
    // 24-item gate's 5,284-tick startup, 8-unit floor) see MiningBudget.obsidianExpeditionFoodTarget.
    private static final int OBSIDIAN_EXPEDITION_STONE_PICKS = 4;
    private static final int DESCEND_THRESHOLD = 8; // If the bot is above the ore layer by more than this many blocks, descend a shaft to the ore layer before mining.
    private static final int SPARE_IRON_INGOTS = 3; // Before a deep dive, reserve enough material for one extra iron pickaxe (3 ingots), so a worn-out pick can be recrafted directly from inventory while deep underground.
    private static final int FOOD_GRASS_SCAN = 32;  // Goal.Food source selection: scan this radius for grass (the seed source for the bread-farming chain).

    public record GoalPlan(Goal goal, List<GoalStep> steps, List<String> unresolved) {
        public boolean success() {
            return unresolved.isEmpty();
        }

        public String describeSteps() {
            List<String> parts = new ArrayList<>();
            for (GoalStep step : steps) {
                parts.add(step.describe());
            }
            return parts.toString();
        }
    }

    public static GoalPlan plan(AIPlayerEntity bot, Goal goal) {
        return plan(bot, goal, null, null);
    }

    public static GoalPlan plan(AIPlayerEntity bot, Goal goal, GoalSnapshotCollector.Context resumeContext) {
        return plan(bot, goal, resumeContext, null);
    }

    /**
     * Mission-aware live planning. The mission id is deliberately absent from the synthetic
     * planFromState entry points: an inventory-shaped unit fixture cannot attest ownership of a
     * physical mission depot and therefore must always retain the descent-kit service step.
     */
    static GoalPlan plan(AIPlayerEntity bot,
                         Goal goal,
                         GoalSnapshotCollector.Context resumeContext,
                         String missionId) {
        // Goal.Food perception-driven source selection: at planning time, scan what is actually
        // nearby and choose hunting or farming accordingly (see ensureFoodTo), no longer hardcoded
        // to hunting (forcing a hunt on animal-less terrain would just flail blindly). No other
        // goal is affected by these two flags.
        boolean hasPrey = HuntTask.hasPreyNearby(bot);
        boolean hasGrass = OreProspector.nearest(bot,
                FOOD_GRASS_SCAN, GoalPlanner::isGrassForSeeds) != null;
        // Barren-biome fallback source: biomes like taiga have sparse animals (measured: 10 hunt-roam
        // attempts over 1092 ticks still yielded 0 prey) but often have sweet berry bushes nearby --
        // when there is no prey to hunt, berries are the last resort for "something to eat right now."
        boolean hasBerries = OreProspector.nearest(bot,
                FOOD_GRASS_SCAN, state -> state.is(Blocks.SWEET_BERRY_BUSH)) != null;
        // Nearby-ore perception: at planning time, check whether the target ore is already nearby
        // (within 48 blocks). If so -> mine directly without descending to the ore layer
        // (digging a 70-block shaft down to Y16 while standing next to iron ore would be silly; also
        // a shaft through natural cave/water/gravel terrain is very prone to descend_blocked --
        // measured runs show a burst of descend-type failures once the world became surface-heavy;
        // the old Y6 spawn's botY<mineY simply never triggered this path, which is why it stayed
        // hidden for so long).
        java.util.function.Predicate<Set<Block>> oreNearby = ores -> {
            if (OreProspector.nearest(bot, 48,
                    state -> ores.contains(state.getBlock())) != null) {
                return true;
            }
            // Knowledge-base second opinion (semantic-memory consumption point): a live scan within
            // 48 blocks found nothing, but this ore was seen within 96 blocks before -> also skip the
            // descent; OreDigTask's prospect(64) + horizontal tunneling can reach it --
            // "remembering where it was" covers more ground than "seeing it right now."
            for (Block ore : ores) {
                String id = BuiltInRegistries.BLOCK.getKey(ore).toString();
                if (io.github.zoyluo.minecraftai.memory.KnowledgeBase.INSTANCE
                        .nearestResource(bot.getUUID(), id, bot.blockPosition(), 96).isPresent()) {
                    return true;
                }
            }
            return false;
        };
        return planFromState(bot, goal, inventoryCounts(bot), toolUsableDurability(bot),
                Math.max(1, MinecraftAiConfig.get().goal().maxPlanDepth()), bot.blockPosition().getY(),
                hasPrey, hasGrass, hasBerries, canAcquireSurfaceResources(bot),
                oreNearby, resumeContext, missionId);
    }

    /**
     * Surface-only work (trees, animals and crops) may be planned only while the bot is actually
     * near the terrain surface. Y alone is not sufficient: mountains and deep ravines make a fixed
     * threshold lie in both directions. The no-leaves heightmap gives a cheap, deterministic fact
     * and tolerates a small shelter/overhang without classifying a Y=16 mine as surface.
     */
    public static boolean canAcquireSurfaceResources(AIPlayerEntity bot) {
        net.minecraft.core.BlockPos origin = bot.blockPosition();
        // Even an open ravine or isolated test canvas at deepslate height has sky visibility but
        // no trees, animals or crops at the work face. Keep the heightmap test, with a conservative
        // lower bound that only rules out unequivocal deep-mine positions.
        if (origin.getY() < 32) {
            return false;
        }
        for (int dx = -8; dx <= 8; dx += 4) {
            for (int dz = -8; dz <= 8; dz += 4) {
                int topY = bot.level().getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        origin.getX() + dx,
                        origin.getZ() + dz);
                if (Math.abs(topY - origin.getY()) <= 8) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Planning entry point once world perception has been reduced to facts. Keeping this boundary separate makes
     * non-build dependency-chain regressions testable without constructing a live server player.
     */
    static GoalPlan planFromState(AIPlayerEntity bot,
                                  Goal goal,
                                  Map<Item, Integer> inventory,
                                  int maxDepth,
                                  int botY,
                                  boolean hasPrey,
                                  boolean hasGrass,
                                  boolean hasBerries,
                                  java.util.function.Predicate<Set<Block>> oreNearby,
                                  GoalSnapshotCollector.Context resumeContext) {
        return planFromState(bot, goal, inventory, maxDepth, botY,
                hasPrey, hasGrass, hasBerries, botY >= 48, oreNearby, resumeContext);
    }

    static GoalPlan planFromState(AIPlayerEntity bot,
                                  Goal goal,
                                  Map<Item, Integer> inventory,
                                  int maxDepth,
                                  int botY,
                                  boolean hasPrey,
                                  boolean hasGrass,
                                  boolean hasBerries,
                                  boolean surfaceAcquisitionAllowed,
                                  java.util.function.Predicate<Set<Block>> oreNearby,
                                  GoalSnapshotCollector.Context resumeContext) {
        return planFromState(bot, goal, inventory,
                assumedFreshToolUsableDurability(inventory), maxDepth, botY,
                hasPrey, hasGrass, hasBerries, surfaceAcquisitionAllowed,
                oreNearby, resumeContext);
    }

    static GoalPlan planFromState(AIPlayerEntity bot,
                                  Goal goal,
                                  Map<Item, Integer> inventory,
                                  Map<Item, Integer> toolUsableDurability,
                                  int maxDepth,
                                  int botY,
                                  boolean hasPrey,
                                  boolean hasGrass,
                                  boolean hasBerries,
                                  boolean surfaceAcquisitionAllowed,
                                  java.util.function.Predicate<Set<Block>> oreNearby,
                                  GoalSnapshotCollector.Context resumeContext) {
        return planFromState(bot, goal, inventory, toolUsableDurability,
                maxDepth, botY, hasPrey, hasGrass, hasBerries,
                surfaceAcquisitionAllowed, oreNearby, resumeContext, null);
    }

    private static GoalPlan planFromState(AIPlayerEntity bot,
                                          Goal goal,
                                          Map<Item, Integer> inventory,
                                          Map<Item, Integer> toolUsableDurability,
                                          int maxDepth,
                                          int botY,
                                          boolean hasPrey,
                                          boolean hasGrass,
                                          boolean hasBerries,
                                          boolean surfaceAcquisitionAllowed,
                                          java.util.function.Predicate<Set<Block>> oreNearby,
                                          GoalSnapshotCollector.Context resumeContext,
                                          String missionId) {
        boolean restrictSurfaceAcquisition = !surfaceAcquisitionAllowed
                && (goal instanceof Goal.MineOre
                || goal instanceof Goal.HaveItem haveItem
                && (haveItem.item() == Items.DIAMOND || haveItem.item() == Items.OBSIDIAN));
        Planner planner = new Planner(bot, new HashMap<>(inventory), Math.max(1, maxDepth), botY,
                hasPrey, hasGrass, hasBerries, surfaceAcquisitionAllowed,
                restrictSurfaceAcquisition, oreNearby, resumeContext,
                toolUsableDurability, missionId);
        planner.ensureGoal(goal, 0, new HashSet<>());
        return new GoalPlan(goal, List.copyOf(mergeGathers(planner.steps)), List.copyOf(planner.unresolved));
    }

    // Grass types cut for seeds (the starting point of the bread-farming chain): only treat
    // "farming" as a food source when there are no animals if grass is actually present nearby.
    private static boolean isGrassForSeeds(BlockState state) {
        return state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN);
    }

    // Tier A consolidated gathering (root-cause fix for diamond-mining failures): when there is no
    // surface-local task, merge every same-item GATHER requirement and move it to the front of the
    // plan. HUNT is the exception: animals move and disappear, so a from-zero chain must first gather
    // the minimal wood to craft a sword and complete the first bounded hunting batch in place, only
    // then batch-gather the remaining logs; otherwise chopping dozens of fuel logs first would lead
    // the bot away from the spawn's sheep herd (measured on seed 3000). But it must not wait for the
    // very last HUNT batch either: the second hunting round can range hundreds of blocks and leave
    // the bot in a treeless plain before it asks for 18 fuel logs. Consolidate GATHER immediately
    // after the first HUNT batch; keep the remaining HUNT/COOK steps in their original order.
    // Best mining Y level for deep valuable ores (1.18+ terrain); returns MAX_VALUE for non-deep
    // ores (so it never triggers "descend to the ore layer first").
    private static int bestMiningY(Set<Block> ores) {
        return MiningChain.bestY(ores); // S2: the recommended mining Y level converges on MiningChain as the single data source (a mixed-ore set takes the deepest layer).
    }

    private static List<GoalStep> mergeGathers(List<GoalStep> steps) {
        int huntIndex = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).kind() == GoalStep.Kind.HUNT) {
                huntIndex = i;
                break;
            }
        }
        if (huntIndex < 0) {
            return mergeGatherSegment(steps, 0);
        }

        // Preserve the exact prerequisite prefix through the first bounded HUNT batch
        // (minimal logs → table/sticks/sword → one local hunt). Every later GATHER remains
        // dependency-free, so batch that suffix before any second hunt can leave the forest.
        List<GoalStep> result = new ArrayList<>(steps.subList(0, huntIndex + 1));
        result.addAll(mergeGatherSegment(steps.subList(huntIndex + 1, steps.size()), 0));
        return result;
    }

    private static List<GoalStep> mergeGatherSegment(List<GoalStep> steps, int insertAt) {
        // GATHER: has no prerequisites -> merge within the segment and move it forward. MINE is not
        // merged across steps: it may straddle the tool-upgrade boundary of
        // "wooden pick mines the first 3 stone -> craft stone pick -> stone pick mines the bulk stone."
        record GatherKey(Item item, boolean bestEffort) {}
        Map<GatherKey, Integer> gatherTotals = new LinkedHashMap<>();
        for (GoalStep step : steps) {
            if (step.kind() == GoalStep.Kind.GATHER) {
                gatherTotals.merge(new GatherKey(step.item(), step.bestEffort()), step.count(), Integer::sum);
            }
        }
        if (gatherTotals.isEmpty()) {
            return new ArrayList<>(steps);
        }
        List<GoalStep> result = new ArrayList<>();
        result.addAll(steps.subList(0, Math.min(insertAt, steps.size())));
        for (Map.Entry<GatherKey, Integer> entry : gatherTotals.entrySet()) {
            GoalStep gathered = GoalStep.gather(entry.getKey().item(), entry.getValue());
            result.add(entry.getKey().bestEffort() ? gathered.asBestEffort() : gathered);
        }
        for (int i = Math.min(insertAt, steps.size()); i < steps.size(); i++) {
            GoalStep step = steps.get(i);
            if (step.kind() == GoalStep.Kind.GATHER) {
                continue; // already moved to the front
            }
            result.add(step);
        }
        return result;
    }

    private static Map<Item, Integer> inventoryCounts(AIPlayerEntity bot) {
        Map<Item, Integer> counts = new HashMap<>();
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            add(counts, stack);
        }
        add(counts, bot.getItemBySlot(EquipmentSlot.OFFHAND));
        // Tier 3: also count already-equipped slots, so ensureArmor doesn't treat "already wearing iron armor" as missing and craft it again.
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            add(counts, bot.getItemBySlot(slot));
        }
        return counts;
    }

    private static void add(Map<Item, Integer> counts, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        // A tool/piece of equipment with a single use left (damage>=max-1, the boundary ToolTier and the OreDig channel use) is not
        // counted toward inventory FOR PLANNING: mining's need_better_tool (ToolTier reports such a pick as none) and "already have a
        // pick" must agree, otherwise the planner never crafts the replacement (measured in real_armor: mining 26 iron wears the stone
        // pick down to its last use; counted as "have a stone pick" no replacement was crafted and mine_ore repeatedly failed with
        // need_better_tool:stone_pickaxe). Not counting it -> ensurePickaxeTier crafts a fresh stone pick ahead of the break and the
        // chain continues. This is planning only: the item itself is never set aside, ToolSelector still uses it until it breaks.
        if (stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1) {
            return;
        }
        counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
    }

    private static Map<Item, Integer> toolUsableDurability(AIPlayerEntity bot) {
        Map<Item, Integer> durability = new HashMap<>();
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            addToolUsableDurability(durability, stack);
        }
        addToolUsableDurability(durability, bot.getItemBySlot(EquipmentSlot.OFFHAND));
        return durability;
    }

    private static void addToolUsableDurability(Map<Item, Integer> durability, ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageableItem()) {
            return;
        }
        durability.merge(stack.getItem(), MiningServiceTask.usableDurability(stack),
                GoalPlanner::saturatedAdd);
    }

    private static Map<Item, Integer> assumedFreshToolUsableDurability(
            Map<Item, Integer> inventory) {
        Map<Item, Integer> durability = new HashMap<>();
        inventory.forEach((item, count) -> {
            int fresh = freshUsableDurability(item);
            if (fresh > 0 && count > 0) {
                durability.put(item, saturatedMultiply(fresh, count));
            }
        });
        return durability;
    }

    private static int freshUsableDurability(Item item) {
        ItemStack stack = new ItemStack(item);
        return stack.isDamageableItem() ? Math.max(0, stack.getMaxDamage() - 1) : 0;
    }

    private static int saturatedAdd(int left, int right) {
        return (int) Math.min(Integer.MAX_VALUE, (long) left + Math.max(0, right));
    }

    private static int saturatedMultiply(int left, int right) {
        return (int) Math.min(Integer.MAX_VALUE,
                (long) Math.max(0, left) * Math.max(0, right));
    }

    private static final class Planner {
        private final AIPlayerEntity bot;
        private final Map<Item, Integer> counts;
        private final Map<Item, Integer> initialCounts;
        private final Map<Item, Integer> initialToolUsableDurability;
        private final int maxDepth;
        // Planning changes the bot's physical layer. In particular ACQUIRE_WATER returns a deep
        // worker to the mission origin before the next dependency is executed, so a fixed initial
        // Y can incorrectly schedule a Y16 iron mine directly after the surface return.
        private int plannedY;
        private final int waterReturnY;
        private boolean initialOrePerceptionValid = true;
        private final boolean hasPreyNearby;  // huntable animals nearby (food source selection: if present -> hunt)
        private final boolean hasGrassNearby; // grass nearby (food source selection: no animals but grass present -> farm bread)
        private final boolean hasBerriesNearby; // sweet berry bushes nearby (food source selection: no animals and no ready food -> fall back to picking berries)
        private final boolean surfaceAcquisitionAllowed;
        private final boolean restrictSurfaceAcquisition;
        /**
         * Snapshot local stations once at plan creation.  A physical station is a capability, not
         * inventory: do not manufacture a second one merely so the symbolic item count says one.
         * The task still rechecks the world immediately before it uses the station.
         */
        private final boolean nearbyCraftingTable;
        private final boolean nearbyFurnace;
        private final java.util.function.Predicate<Set<Block>> oreNearby; // whether the target ore is already nearby (48 blocks) -> skip the descent
        private final GoalSnapshotCollector.Context resumeContext;
        private final String missionId;
        private final List<GoalStep> steps = new ArrayList<>();
        private final List<String> unresolved = new ArrayList<>();
        private int bestEffortDepth;
        private int suppressOrdinaryTorchProvisionDepth;
        /** -1 means not opened; otherwise the still-unprovisioned portion of the 14-log seal. */
        private int surfaceEmergencyShelterWoodPending = -1;

        private Planner(AIPlayerEntity bot, Map<Item, Integer> counts, int maxDepth, int botY,
                        boolean hasPreyNearby, boolean hasGrassNearby, boolean hasBerriesNearby,
                        boolean surfaceAcquisitionAllowed,
                        boolean restrictSurfaceAcquisition,
                        java.util.function.Predicate<Set<Block>> oreNearby,
                        GoalSnapshotCollector.Context resumeContext,
                        Map<Item, Integer> toolUsableDurability,
                        String missionId) {
            this.bot = bot;
            this.counts = counts;
            this.initialCounts = Map.copyOf(counts);
            this.initialToolUsableDurability = toolUsableDurability == null
                    ? Map.of() : Map.copyOf(toolUsableDurability);
            this.maxDepth = maxDepth;
            this.plannedY = botY;
            this.waterReturnY = resumeContext == null ? botY : resumeContext.origin().getY();
            this.hasPreyNearby = hasPreyNearby;
            this.hasGrassNearby = hasGrassNearby;
            this.hasBerriesNearby = hasBerriesNearby;
            this.surfaceAcquisitionAllowed = surfaceAcquisitionAllowed;
            this.restrictSurfaceAcquisition = restrictSurfaceAcquisition;
            this.nearbyCraftingTable = bot != null && WorkshopLocator.hasNearbyCraftingTable(bot);
            // Goal.Workstation deliberately requires a normal furnace (its postcondition names
            // minecraft:furnace), whereas recipe-specific smelting below can use a compatible
            // smoker or blast furnace as well.
            this.nearbyFurnace = bot != null && WorkshopLocator.hasNearbyFurnace(bot);
            this.oreNearby = oreNearby;
            this.resumeContext = resumeContext;
            this.missionId = missionId == null ? "" : missionId;
        }

        private boolean ensureGoal(Goal goal, int depth, Set<String> visiting) {
            if (depth > maxDepth) {
                unresolved.add("max_depth:" + goal);
                return false;
            }
            return switch (goal) {
                case Goal.HaveItem haveItem -> ensureItem(haveItem.item(), haveItem.count(), depth, visiting);
                case Goal.HavePickaxeTier havePickaxeTier -> ensurePickaxeTier(havePickaxeTier.tier(), depth, visiting);
                case Goal.MineOre mineOre -> ensureMineOre(mineOre.ores(), mineOre.count(), depth, visiting);
                case Goal.HarvestCrop harvestCrop -> ensureHarvestCrop(harvestCrop, depth, visiting);
                case Goal.Armor ignored -> ensureArmor(true, depth, visiting);
                case Goal.Workstation ignored -> ensureWorkstation(depth, visiting);
                case Goal.Stockpile stockpile -> ensureStockpile(stockpile, depth, visiting);
                case Goal.Food food -> ensureFoodTo(food.cookedCount(), depth, visiting);
                case Goal.Build build -> ensureBuild(build, depth, visiting);
            };
        }

        // P3: harvest a crop -- no-op if already holding enough produce; otherwise back-derive a
        // hoe (any tier works, here we use wooden_hoe) + seeds, then emit the FARM step.
        private boolean ensureHarvestCrop(Goal.HarvestCrop g, int depth, Set<String> visiting) {
            int owned = counts.getOrDefault(g.produce(), 0);
            int remaining = Math.max(0, g.count() - owned);
            if (remaining <= 0) {
                return true;
            }
            // Hoe: if inventory has no hoe at all, back-derive a wooden hoe (FarmAction accepts any HoeItem, so a wooden one is enough).
            if (!hasAnyHoe() && !ensureItem(Items.WOODEN_HOE, 1, depth + 1, visiting)) {
                return false;
            }
            ensureSeeds(g.seed(), g.produce(), remaining, depth, visiting); // ensure seeds before farming
            addStep(GoalStep.farm(g.crop(), g.seed(), g.produce(), remaining));
            counts.merge(g.produce(), remaining, Integer::sum);
            return true;
        }

        // Ensure seeds before farming: when the seed differs from the produce (e.g. wheat seeds !=
        // wheat) and is insufficient -> back-derive it (wheat seeds come from cutting grass); when
        // the seed equals the produce itself (carrot/potato), do not back-derive it (otherwise
        // produce -> farm -> seed -> produce loops forever) -- leave it to FarmTask to find on the
        // spot at runtime.
        private void ensureSeeds(Item seed, Item produce, int count, int depth, Set<String> visiting) {
            if (seed == null || seed == produce) {
                return;
            }
            int have = counts.getOrDefault(seed, 0);
            if (have < count) {
                ensureItem(seed, count - have, depth + 1, visiting);
            }
        }

        private boolean hasAnyHoe() {
            return counts.getOrDefault(Items.WOODEN_HOE, 0) > 0
                    || counts.getOrDefault(Items.STONE_HOE, 0) > 0
                    || counts.getOrDefault(Items.IRON_HOE, 0) > 0
                    || counts.getOrDefault(Items.DIAMOND_HOE, 0) > 0
                    || counts.getOrDefault(Items.GOLDEN_HOE, 0) > 0
                    || counts.getOrDefault(Items.NETHERITE_HOE, 0) > 0;
        }

        // Hunting requires a weapon (bare-handed attack is only 1 damage -- inefficient and may not even catch up with the animal). Any sword already in inventory or the plan is enough.
        private boolean hasAnySword() {
            return counts.getOrDefault(Items.WOODEN_SWORD, 0) > 0
                    || counts.getOrDefault(Items.STONE_SWORD, 0) > 0
                    || counts.getOrDefault(Items.IRON_SWORD, 0) > 0
                    || counts.getOrDefault(Items.DIAMOND_SWORD, 0) > 0
                    || counts.getOrDefault(Items.GOLDEN_SWORD, 0) > 0
                    || counts.getOrDefault(Items.NETHERITE_SWORD, 0) > 0;
        }

        private boolean ensurePickaxeTier(int tier, int depth, Set<String> visiting) {
            if (bestPickaxeTier() >= tier) {
                return true;
            }
            Item pickaxe = pickaxeForTier(tier);
            if (pickaxe == Items.AIR) {
                return true;
            }
            return ensureItem(pickaxe, 1, depth + 1, visiting);
        }

        private boolean ensureMineOre(Set<Block> ores, int count, int depth, Set<String> visiting) {
            Set<Block> expanded = ores == null || ores.isEmpty() ? OreScan.COMMON_ORES : OreScan.expandOreFamilies(ores);
            Set<Item> drops = io.github.zoyluo.minecraftai.action.HarvestCore.expectedDropsFor(expanded);
            int owned = countAny(drops);
            int remaining = Math.max(0, count - owned);
            if (remaining <= 0) {
                return true;
            }
            int tier = ToolTier.requiredPickaxeTier(expanded);
            boolean rareOre = expanded.contains(Blocks.DIAMOND_ORE)
                    || expanded.contains(Blocks.DEEPSLATE_DIAMOND_ORE)
                    || expanded.contains(Blocks.EMERALD_ORE)
                    || expanded.contains(Blocks.DEEPSLATE_EMERALD_ORE);
            boolean coalOre = expanded.contains(Blocks.COAL_ORE)
                    || expanded.contains(Blocks.DEEPSLATE_COAL_ORE);
            MiningBudget budget = MiningBudget.forQuota(remaining, rareOre, tier);
            // Mission identity is the requested total, not the current deficit. A 64-diamond
            // expedition with 63 already collected must retain its service/tool contract instead
            // of silently degrading into a one-off local mine after resume.
            MiningBudget missionBudget = MiningBudget.forQuota(count, rareOre, tier);
            boolean expedition = count >= MiningBudget.EXPEDITION_THRESHOLD;
            boolean longRareExpedition = expedition && rareOre;
            int mineY = bestMiningY(expanded);
            // A long rare-ore expedition owns a stable optimal layer. One exposed ore must not keep
            // a 64-item mission branch-mining at the surface; small requests retain the local shortcut.
            // oreNearby is a fact about the position at which this plan was created. Once an
            // earlier planned step has changed layers, reusing it can suppress a required descent.
            boolean knownOreNearby = initialOrePerceptionValid && oreNearby.test(expanded);
            boolean willDescend = plannedY - mineY > DESCEND_THRESHOLD
                    && (longRareExpedition || !knownOreNearby);
            boolean ordinaryChannelMission = !rareOre
                    && budget.ordinaryChannelPickaxes() > 0;
            // An ordinary expedition owns the same four-pick service horizon after a mine-layer
            // replan. Tying this flag to willDescend made a resumed coal/iron batch silently lose
            // channel maintenance as soon as it was already standing at the target Y.
            boolean maintainTunnelingTools = longRareExpedition || ordinaryChannelMission;
            // A live descent-kit attestation is valid only if no dependency is appended after this
            // snapshot. Coal/iron/torch provisioning can consume slots or reserved materials even
            // when the inventory happened to satisfy the kit at the beginning of this plan.
            int rareBootstrapStart = longRareExpedition ? steps.size() : -1;

            if (longRareExpedition) {
                // Surface readiness is a hard gate before descent. Once the sealed descent kit
                // hands off to the mine, its protected stone ledger supersedes this phase-scoped
                // wood reserve; an underground resume must never emit a wood top-up.
                boolean reserveSurfaceShelter = count >= 64 && surfaceAcquisitionAllowed;
                if (reserveSurfaceShelter) {
                    beginSurfaceEmergencyShelterWoodReserve();
                }
                int unresolvedBefore = unresolved.size();
                if (!ensureMiningFoodReserveTo(
                        missionBudget.cookedFoodTarget(),
                        MiningBudget.RARE_SERVICE_FOOD_FLOOR,
                        depth + 1, visiting)
                        || unresolved.size() > unresolvedBefore) {
                    return false;
                }
                // Keep the first bounded local hunt ahead of bulk wood collection; after food has
                // established that ordering, seal the emergency reserve before tool/fuel/service
                // dependencies are allowed to borrow it.
                if (reserveSurfaceShelter
                        && !reserveSurfaceEmergencyShelterWood(depth + 1, visiting)) {
                    return false;
                }
            }
            if (longRareExpedition && tier == ToolTier.IRON) {
                // Acquire the complete iron contract once. The old sequential chain first made one
                // pick (3 ingots), then two more (6), then the six-ingot spare. Those three small
                // OreDig missions each fell below the ordinary-channel threshold and reused one
                // increasingly damaged stone pick. One >=15 from-zero acquisition owns one finite
                // ordinary pool/service horizon and one smelt transaction.
                int missingTargetPicks = Math.max(0,
                        missionBudget.initialPickaxes()
                                - counts.getOrDefault(Items.IRON_PICKAXE, 0));
                int aggregatedIronTarget = saturatedAdd(
                        saturatedMultiply(missingTargetPicks, 3),
                        missionBudget.spareToolIngots());
                boolean ironReady;
                suppressOrdinaryTorchProvisionDepth++;
                try {
                    ironReady = ensureItem(Items.IRON_INGOT,
                            aggregatedIronTarget, depth + 1, visiting);
                } finally {
                    suppressOrdinaryTorchProvisionDepth--;
                }
                if (!ironReady) {
                    return false;
                }
            }
            if (!ensurePickaxeTier(tier, depth + 1, visiting)) {
                return false;
            }
            if (expedition) {
                // The first leg provisions multiple pickaxes per quota; inter-batch service handles
                // returning to storage or on-site resupply, avoiding cramming the whole expedition's
                // consumption into the bootstrap in one shot. A diamond goal needs an iron pickaxe,
                // and does not consume the target diamonds themselves.
                Item expeditionPickaxe = pickaxeForTier(tier);
                if (expeditionPickaxe != Items.AIR
                        && !ensureItem(expeditionPickaxe, missionBudget.initialPickaxes(), depth + 1, visiting)) {
                    return false;
                }
                if (missionBudget.spareToolIngots() > 0
                        && !ensureItem(Items.IRON_INGOT, missionBudget.spareToolIngots(), depth + 1, visiting)) {
                    return false;
                }
                // Torches and plug blocks are topped up only during the surface bootstrap. Replaying
                // these two goals on an underground resume would translate already-consumed reserves
                // into DigDown/tree-chopping prerequisites, which would block the next batch instead;
                // any underground shortfall is either resupplied from the depot at a service
                // checkpoint or explicitly fails closed.
                if (surfaceAcquisitionAllowed || willDescend) {
                    if (longRareExpedition) {
                        int descentTorchReserve = willDescend
                                ? descendTorchBudget(plannedY, mineY) : 0;
                        int requiredTorches = roundUpToTorchRecipe(
                                saturatedAdd(missionBudget.torchTarget(), descentTorchReserve));
                        if (missionBudget.targetCount() >= 64) {
                            requiredTorches = Math.max(requiredTorches,
                                    MiningBudget.DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES);
                        }
                        if (!ensureTorchesTo(requiredTorches, depth + 1, visiting)) {
                            return false;
                        }
                    } else {
                        // Coal is the torch ingredient. Asking a coal expedition to provision its
                        // own normal torch target recursively emitted coal8 -> coal12 -> coal96,
                        // each with another independent unstackable channel pool. Mine the bounded
                        // target coal once; the parent torch craft immediately follows it.
                        if (!coalOre && suppressOrdinaryTorchProvisionDepth == 0) {
                            bestEffortProvision(() -> ensureTorchesTo(
                                    missionBudget.torchTarget(), depth + 1, visiting));
                        }
                        bestEffortProvision(() -> ensureItem(
                                Items.COBBLESTONE, missionBudget.emergencyBlocks(), depth + 1, visiting));
                    }
                }
            }
            // Provision enough pickaxes for a large mining batch (root-cause fix for real_armor:
            // mining 26 iron timed out at 9 remaining): a stone pick has ~131 durability, and mining
            // 24+ iron including tunneling wears out multiple picks. If it wears out mid-batch,
            // resupply-and-craft-in-place would interrupt a large single mine_ore request and lose
            // mining progress -> ore_dig_timeout. Provision by quantity (roughly 1 pick per 12 blocks
            // including tunneling), so one worn-out pick swaps for a spare without interruption and
            // the batch finishes in one pass. Only provisioned at the STONE tier (mining iron/copper,
            // where cobblestone is cheap and unlimited); the IRON tier (diamond) is already covered
            // by the spare iron ingot reserve.
            if (tier <= ToolTier.STONE && remaining >= 12) {
                // Subtract already-owned higher-tier picks (fix for real_iron_bulk: pre-loaded with 5
                // iron picks, roughly equivalent to 9 stone picks; naively provisioning
                // 1+100/12=9 stone picks regardless -> back-derives chopping wood -> dies quickly on
                // treeless terrain with need_oak_planks). Only provision the actual gap; starting from
                // zero (no higher-tier picks) behaves as before and does not break the real_armor
                // 26-iron scenario.
                int needPicks = 1 + remaining / 12;
                int nonStoneEquiv = counts.getOrDefault(Items.IRON_PICKAXE, 0) * 250 / 131
                        + counts.getOrDefault(Items.DIAMOND_PICKAXE, 0) * 1561 / 131
                        + counts.getOrDefault(Items.NETHERITE_PICKAXE, 0) * 2031 / 131;
                int stoneNeeded = needPicks - nonStoneEquiv;
                if (stoneNeeded > 0) {
                    ensureItem(pickaxeForTier(tier), stoneNeeded, depth + 1, visiting);
                }
            }
            // A small-quota deep mine still follows the lightweight fast chain and only tops up
            // torches; a long rare-ore expedition already went through the hard food-readiness path
            // above, but iron armor/a shield is still not mandatory, so the pre-mining bootstrap does
            // not balloon into another heavyweight equipment task.
            if (tier >= ToolTier.IRON && remaining < MiningBudget.EXPEDITION_THRESHOLD) {
                ensureTorches(depth + 1, visiting); // a small quota follows the lightweight fast chain
            }
            // Deep-ore mining rework P1: if the bot is far above the ore layer -> descend a shaft to
            // the ore layer first, then mine. Otherwise, at the wrong height (measured at Y=48), it
            // repeatedly "locks onto an out-of-reach ore diagonally below -> tunnels horizontally ->
            // distance gets stuck -> no_progress," stalling for 11 minutes.
            // Deep-dive durability fallback (root-cause fix for a real_diamond death observed by
            // hand): once an iron pick wears out deep underground it cannot be resupplied in place --
            // there are no trees down there for a furnace/fuel, so resupply's back-derivation of
            // "gather oak -> craft furnace -> smelt iron ingot" is guaranteed to fail below Y<0
            // (no trees within 96 blocks) -> repeated replans get stuck and the bot dies to mobs.
            // Fix: before a deep dive, reserve 3 spare iron ingots (mined/smelted all at once at the
            // surface). When the pick wears out deep down, craft a new one directly from the reserve
            // + a portable crafting table + sticks (only needs a craft, no tree/furnace/smelting), so
            // it is never trapped down there. Only reserved for deep dives (mining near the surface
            // can resupply normally if the pick breaks).
            if (tier >= ToolTier.IRON && willDescend
                    && remaining < MiningBudget.EXPEDITION_THRESHOLD) {
                ensureItem(Items.IRON_INGOT, SPARE_IRON_INGOTS, depth + 1, visiting);
                // [Experiment reverted] Bringing along an iron-armor buff before diving for diamonds
                // measured as a net drag (real_diamond 0/6 vs. a leaner baseline's 3/6): provisioning
                // a helmet+chestplate (13 iron) before the dive stretched the chain too long -- the
                // bot spent extra time within the 36000-tick budget mining 13 more iron, smelting and
                // crafting armor, and timed out (5/6 timeout) before it even got to mining diamonds;
                // and the armor was never once worn (the run failed in the earlier stage and never
                // reached the dive). Survival benefit = 0, cost = the chain doubled. So the armor
                // reservation was reverted; deep-dive survival now relies on reactive measures
                // (self-rescue into lava/mud, burying near death, placing torches -- only pay the cost
                // when actually in danger). Equipping armor on descent
                // (DescendToYTask.onStart equipBestArmor) is kept: zero cost, and the background armor pass is best-first too.
            }
            if (ordinaryChannelMission
                    && !ensureFreshOrdinaryChannelKit(budget, depth + 1, visiting)) {
                return false;
            }
            // Dependency planning above can itself move the simulated worker. Coal is the concrete
            // case: provisioning torches for a larger coal batch recursively mines an initial coal
            // batch and already descends to Y=48. Reusing the pre-dependency willDescend decision
            // then emitted a second Y=48 hand-off; a vein ending at Y=47 made that redundant task
            // fail as an overshoot even though the worker was already in the correct layer band.
            // Keep DescendToYTask fail-closed for real pose drift and only suppress this stale step.
            if (willDescend && plannedY - mineY > DESCEND_THRESHOLD) {
                if (longRareExpedition) {
                    boolean exactLiveKit = count == 64
                            && bot != null
                            && !missionId.isBlank()
                            && steps.size() == rareBootstrapStart
                            && MiningServiceTask.rareDescentKitReady(bot)
                            && MiningServiceTask.ownedMissionDepot(bot, missionId);
                    if (!exactLiveKit) {
                        boolean kitReady = count == 64
                                ? ensureRareDescentKit(expanded, count,
                                missionBudget, depth + 1, visiting)
                                : ensureDirectRareDescentKit(missionBudget,
                                depth + 1, visiting);
                        if (!kitReady) {
                            return false;
                        }
                    }
                }
                // DescendToY may place one torch at the dark starting face and then every six
                // vertical levels. Debit that worst-case use now so a later dependency cannot treat
                // already-promised torches as its inventory baseline.
                int descentTorches = descendTorchBudget(plannedY, mineY);
                addStep(GoalStep.descendToY(mineY));
                consumeItem(Items.TORCH, descentTorches);
                plannedY = mineY;
                initialOrePerceptionValid = false;
            }
            int rareBatchOffset = longRareExpedition
                    ? Math.floorMod(owned, budget.batchSize()) : 0;
            if (longRareExpedition && rareBatchOffset == 0) {
                // The first rare batch always owns a boundary service after the final descent.
                // From-zero missions use boundary 0 and a synthetic cursor supplied by Executor;
                // completed-batch resumes use their exact eight-item boundary. A partial open
                // batch must resume its existing cursor/resource ledgers before the next boundary;
                // servicing at owned=4 would silently split one logical batch into two.
                steps.add(GoalStep.rareOreService(
                        expanded, owned, count));
            }
            if (longRareExpedition) {
                int cumulative = 0;
                int firstBatchTarget = rareBatchOffset == 0
                        ? budget.batchSize() : budget.batchSize() - rareBatchOffset;
                while (cumulative < remaining) {
                    int batchTarget = Math.min(
                            cumulative == 0 ? firstBatchTarget : budget.batchSize(),
                            remaining - cumulative);
                    // Direct append is intentional: addStep would merge adjacent ore steps and
                    // erase the durable eight-item service boundaries.
                    steps.add(GoalStep.mineOre(expanded, batchTarget));
                    cumulative += batchTarget;
                    if (cumulative < remaining) {
                        steps.add(GoalStep.rareOreService(
                                expanded, owned + cumulative, count));
                    }
                }
            } else if (remaining >= MiningBudget.EXPEDITION_THRESHOLD) {
                int cumulative = 0;
                for (int batchIndex = 0; batchIndex < budget.batchCount(); batchIndex++) {
                    int batchTarget = budget.batchTarget(batchIndex);
                    if (batchTarget <= 0) {
                        continue;
                    }
                    // Append directly, not via addStep: adjacent same-ore steps would get merged back into one giant 64-quota Task.
                    steps.add(GoalStep.mineOre(expanded, batchTarget));
                    cumulative += batchTarget;
                    if (batchIndex + 1 < budget.batchCount()) {
                        steps.add(GoalStep.miningService(
                                expanded, cumulative, maintainTunnelingTools));
                    }
                }
            } else {
                addStep(GoalStep.mineOre(expanded, remaining));
            }
            for (Item drop : drops) {
                counts.merge(drop, remaining, Integer::sum);
                break;
            }
            if (ordinaryChannelMission) {
                // These items remain physically carried for this ordinary mission, but they are no
                // longer available to later coal/iron/rare dependencies in the symbolic plan. This
                // is the ownership boundary that prevents the final rare kit from borrowing an
                // earlier ordinary service horizon.
                consumeItem(Items.STONE_PICKAXE, budget.ordinaryChannelPickaxes());
                consumeItem(Items.STICK, budget.ordinaryChannelRepairSticks());
                consumeItem(Items.COBBLESTONE, budget.ordinaryChannelRepairStoneLike());
                // Inter-batch service protects the next OreDig batch, but the final ordinary
                // batch hands control back to a parent craft/smelt/descent. Its unpredictable
                // spoil mix can occupy every main slot even when the target drop itself stacks.
                // Seal that runtime boundary explicitly without charging another four-pick
                // rebuild. The stone reserve is the symbolic inventory after this ordinary
                // mission's exact debit; preserving it keeps the downstream obsidian/rare plan
                // truthful while surplus mining spoil remains disposable.
                steps.add(GoalStep.miningHandoffService(
                        expanded, owned + remaining, plannedStoneLikeCount()));
            }
            return true;
        }

        private int plannedStoneLikeCount() {
            return saturatedAdd(counts.getOrDefault(Items.COBBLESTONE, 0),
                    saturatedAdd(counts.getOrDefault(Items.COBBLED_DEEPSLATE, 0),
                            counts.getOrDefault(Items.BLACKSTONE, 0)));
        }

        /**
         * Provisions one finite ordinary channel mission. Four fresh picks cover the initial
         * descent/working face. The remaining raw materials fund the exact bounded contract from
         * {@link MiningBudget}: one one-pick physical resupply per open batch and one four-pick
         * rebuild at every inter-batch service. The incremental craft is deliberate; item count
         * alone cannot prove that five carried picks have usable durability.
         */
        private boolean ensureFreshOrdinaryChannelKit(MiningBudget budget,
                                                      int depth,
                                                      Set<String> visiting) {
            int freshPickaxes = budget.ordinaryChannelPickaxes();
            if (freshPickaxes <= 0) {
                return true;
            }
            int requiredUsable = saturatedMultiply(
                    freshPickaxes, MiningBudget.STONE_PICKAXE_USABLE_DURABILITY);
            boolean currentPoolAttested = counts.getOrDefault(Items.STONE_PICKAXE, 0)
                    >= freshPickaxes
                    && initialToolUsableDurability.getOrDefault(Items.STONE_PICKAXE, 0)
                    >= requiredUsable;
            int craftCount = currentPoolAttested ? 0 : freshPickaxes;
            int craftSticks = saturatedMultiply(
                    craftCount, MiningBudget.STONE_PICKAXE_STICK_COST);
            int craftStone = saturatedMultiply(
                    craftCount, MiningBudget.STONE_PICKAXE_HEAD_COST);
            int requiredSticks = saturatedAdd(
                    budget.ordinaryChannelRepairSticks(), craftSticks);
            int requiredStone = saturatedAdd(
                    budget.ordinaryChannelRepairStoneLike(), craftStone);
            if (!ensureCraftingTableAvailable(depth + 1, visiting)
                    // Mine every stone-like dependency before sealing the handle reserve. Stone
                    // acquisition can itself open a bounded channel repair and spend sticks; doing
                    // it afterwards would let that nested mission borrow the final handle pool.
                    || !ensureItem(Items.COBBLESTONE, requiredStone, depth + 1, visiting)
                    || !ensureItem(Items.STICK, requiredSticks, depth + 1, visiting)) {
                return false;
            }
            if (craftCount > 0) {
                appendFreshStonePickaxeCraft(craftCount, craftSticks, craftStone);
            }
            return true;
        }

        /**
         * Final sealed hand-off for a target64 rare expedition. All coal, iron, torch and
         * target-tool dependencies have already emitted their work. Provisioning 238 sticks, 77
         * stone-like and a mission chest lets runtime atomically retire old tools, craft five fresh
         * picks, and leave the exact 228/60 reserve immediately before final descent.
         */
        private boolean ensureRareDescentKit(Set<Block> ores,
                                             int missionTarget,
                                             MiningBudget budget,
                                             int depth,
                                             Set<String> visiting) {
            if (missionTarget != 64) {
                unresolved.add("rare_descent_kit_requires_target64:" + missionTarget);
                return false;
            }
            int freshPickaxes = budget.tunnelingPickaxes();
            int craftSticks = saturatedMultiply(
                    freshPickaxes, MiningBudget.STONE_PICKAXE_STICK_COST);
            int craftStone = saturatedMultiply(
                    freshPickaxes, MiningBudget.STONE_PICKAXE_HEAD_COST);
            int requiredSticks = saturatedAdd(budget.spareToolSticks(), craftSticks);
            int requiredStone = saturatedAdd(
                    saturatedAdd(budget.emergencyBlocks(), craftStone), 2);
            boolean ownedDepotReady = bot != null && !missionId.isBlank()
                    && MiningServiceTask.ownedMissionDepot(bot, missionId);
            if (freshPickaxes <= 0
                    || (!ownedDepotReady
                    && !ensureItem(Items.CHEST, 1, depth + 1, visiting))
                    || !ensureCraftingTableAvailable(depth + 1, visiting)
                    // The rare handle pool is the final sealed resource. Any stone acquisition
                    // (including its own ordinary channel service) must finish before this top-up.
                    || !ensureItem(Items.COBBLESTONE, requiredStone, depth + 1, visiting)
                    || !ensureItem(Items.STICK, requiredSticks, depth + 1, visiting)) {
                return false;
            }
            steps.add(GoalStep.rareDescentKitService(ores, missionTarget));
            // Runtime service atomically consumes one chest, five pick heads/handles and at most
            // two blocks for the sealed retirement pocket. Mirror that promise so downstream
            // symbolic planning cannot borrow resources that no longer remain in inventory.
            if (!ownedDepotReady) {
                consumeItem(Items.CHEST, 1);
            }
            consumeItem(Items.COBBLESTONE, saturatedAdd(craftStone, 2));
            consumeItem(Items.STICK, craftSticks);
            counts.merge(Items.STONE_PICKAXE, freshPickaxes, Integer::sum);
            return true;
        }

        /**
         * Targets below one full stack retain the established direct hand-off: provision the
         * bounded reserve and craft five fresh channel picks immediately before descent. They do
         * not own the target64 mission-local depot/schema contract.
         */
        private boolean ensureDirectRareDescentKit(MiningBudget budget,
                                                   int depth,
                                                   Set<String> visiting) {
            int freshPickaxes = budget.tunnelingPickaxes();
            int craftSticks = saturatedMultiply(
                    freshPickaxes, MiningBudget.STONE_PICKAXE_STICK_COST);
            int craftStone = saturatedMultiply(
                    freshPickaxes, MiningBudget.STONE_PICKAXE_HEAD_COST);
            int requiredSticks = saturatedAdd(budget.spareToolSticks(), craftSticks);
            int requiredStone = saturatedAdd(budget.emergencyBlocks(), craftStone);
            if (freshPickaxes <= 0
                    || !ensureCraftingTableAvailable(depth + 1, visiting)
                    || !ensureItem(Items.COBBLESTONE, requiredStone, depth + 1, visiting)
                    || !ensureItem(Items.STICK, requiredSticks, depth + 1, visiting)) {
                return false;
            }
            appendFreshStonePickaxeCraft(freshPickaxes, craftSticks, craftStone);
            return true;
        }

        /** Adds a runtime-incremental craft instead of an absolute item-count ensure. */
        private void appendFreshStonePickaxeCraft(int pickaxes, int sticks, int stone) {
            steps.add(GoalStep.craft(Items.STONE_PICKAXE, pickaxes));
            consumeItem(Items.STICK, sticks);
            consumeItem(Items.COBBLESTONE, stone);
            counts.merge(Items.STONE_PICKAXE, pickaxes, Integer::sum);
        }

        // Armor prerequisite: counts both inventory and already-worn pieces (inventoryCounts already
        // includes equipment slots).
        // full=true (an explicit achieve_armor goal): the full four-piece set + an iron sword.
        // full=false (mining prerequisite, the "compromise" option): only helmet + chestplate --
        // blocks most damage while keeping the plan half as long and with far fewer failure points.
        private boolean ensureArmor(boolean full, int depth, Set<String> visiting) {
            List<Item> pieces = full ? IRON_ARMOR : List.of(Items.IRON_HELMET, Items.IRON_CHESTPLATE);
            // Mine enough in one trip (root-cause fix for a real_armor measurement of
            // no_stand_position_for_furnace): reserve all the iron ingots needed for every missing
            // armor piece/sword [in one shot], merged into a single mining pass + a single smelting
            // pass. Otherwise mining piece by piece and returning to the furnace after each one --
            // after mining deep underground, failing to find a stand position back at that distant
            // surface furnace would stall the run (measured: after finishing the helmet, smelting
            // iron for the chestplate hit no_stand_position_for_furnace). A real player would also
            // mine a full load first and smelt it all together.
            int totalIron = 0;
            for (Item piece : pieces) {
                if (counts.getOrDefault(piece, 0) <= 0) {
                    totalIron += ironIngotCost(piece);
                }
            }
            if (full && counts.getOrDefault(Items.IRON_SWORD, 0) <= 0) {
                totalIron += ironIngotCost(Items.IRON_SWORD);
            }
            if (totalIron > 0) {
                ensureItem(Items.IRON_INGOT, totalIron, depth + 1, visiting); // mine and smelt enough in one pass; subsequent armor/sword crafts consume straight from inventory without returning to the furnace piecemeal
            }
            for (Item piece : pieces) {
                if (counts.getOrDefault(piece, 0) <= 0 && !ensureItem(piece, 1, depth + 1, visiting)) {
                    return false;
                }
            }
            if (full && counts.getOrDefault(Items.IRON_SWORD, 0) <= 0
                    && !ensureItem(Items.IRON_SWORD, 1, depth + 1, visiting)) {
                return false;
            }
            return true;
        }

        // Computes how many iron ingots a finished item's (armor/sword) recipe needs (via
        // RecipeRegistry, not hardcoded -- an armor piece's recipe is a single iron_ingot ingredient,
        // sword = iron_ingot x2 + a stick). Used by ensureArmor to pool the total iron needed,
        // implementing "mine a full 26 iron in one trip and smelt it all together."
        private int ironIngotCost(Item item) {
            return RecipeRegistry.find(item)
                    .map(r -> r.ingredients().stream()
                            .filter(ing -> ing.anyOf().contains(Items.IRON_INGOT))
                            .mapToInt(RecipeRegistry.Ingredient::count)
                            .sum())
                    .orElse(0);
        }

        // Hazard hardening: before mining deep (dangerous), reserve a batch of torches for
        // DangerWatcher to light up dark underground areas and prevent mob spawns.
        // best-effort: if torches can be back-derived (mine coal + sticks), add them to the plan;
        // this does not block the mining goal (having an iron pick makes mining coal nearly certain).
        private void ensureTorches(int depth, Set<String> visiting) {
            ensureTorchesTo(TORCH_TARGET, depth, visiting);
        }

        private boolean ensureTorchesTo(int target, int depth, Set<String> visiting) {
            if (target <= 0 || counts.getOrDefault(Items.TORCH, 0) >= target) {
                return true;
            }
            return ensureItem(Items.TORCH, target, depth + 1, visiting);
        }

        /**
         * Roll back an optional provisioning branch if planning cannot resolve it. If planning
         * succeeds, mark every emitted step so a world-time miss cannot fail the parent mining Goal.
         */
        private void bestEffortProvision(Runnable provision) {
            int stepsBefore = steps.size();
            int unresolvedBefore = unresolved.size();
            Map<Item, Integer> countsBefore = new HashMap<>(counts);
            bestEffortDepth++;
            try {
                provision.run();
            } finally {
                bestEffortDepth--;
            }
            if (unresolved.size() <= unresolvedBefore) {
                for (int i = stepsBefore; i < steps.size(); i++) {
                    steps.set(i, steps.get(i).asBestEffort());
                }
                return;
            }
            rollbackSteps(stepsBefore);
            while (unresolved.size() > unresolvedBefore) {
                unresolved.remove(unresolved.size() - 1);
            }
            counts.clear();
            counts.putAll(countsBefore);
        }

        /**
         * A nearby table satisfies a crafting dependency without inventing a spare table in the
         * inventory plan.  If the table vanishes before the craft task starts, that task reports
         * the factual miss and normal postcondition repair replans from the live world.
         */
        private boolean ensureCraftingTableAvailable(int depth, Set<String> visiting) {
            return nearbyCraftingTable
                    || counts.getOrDefault(Items.CRAFTING_TABLE, 0) > 0
                    || ensureItem(Items.CRAFTING_TABLE, 1, depth, visiting);
        }

        /** A normal furnace is required for the explicit base/workstation goal. */
        private boolean ensureNormalFurnaceAvailable(int depth, Set<String> visiting) {
            return nearbyFurnace
                    || counts.getOrDefault(Items.FURNACE, 0) > 0
                    || ensureItem(Items.FURNACE, 1, depth, visiting);
        }

        /**
         * Smelting dependencies may reuse the actual station that accepts this exact input.  This
         * allows a smoker for food and a blast furnace for raw metals, but never mistakes one for
         * a usable workstation when its recipe or occupied inventory is incompatible.
         */
        private boolean ensureFurnaceFor(Item input,
                                         Item output,
                                         int depth,
                                         Set<String> visiting) {
            if (counts.getOrDefault(Items.FURNACE, 0) > 0) {
                return true;
            }
            if (bot != null && WorkshopLocator.hasNearbyCompatibleFurnace(bot, input, output)) {
                return true;
            }
            return ensureItem(Items.FURNACE, 1, depth, visiting);
        }

        /**
         * Food has no fixed species before the hunt completes.  Any nearby empty/compatible food
         * furnace is enough; otherwise keep the old deterministic normal-furnace fallback.
         */
        private boolean hasCookingFurnaceAvailable() {
            if (counts.getOrDefault(Items.FURNACE, 0) > 0 || bot == null) {
                return counts.getOrDefault(Items.FURNACE, 0) > 0;
            }
            return SmeltChain.RAW_FOODS.stream().anyMatch(input -> {
                Item output = SmeltChain.smeltOf(input);
                return output != null
                        && WorkshopLocator.hasNearbyCompatibleFurnace(bot, input, output);
            });
        }

        private boolean ensureCookingFurnace(int depth, Set<String> visiting) {
            return hasCookingFurnaceAvailable()
                    || ensureItem(Items.FURNACE, 1, depth, visiting);
        }

        // Phase 2: infrastructure -- provision one each of crafting table/furnace/chest, then emit the placement step (PlaceStationsTask arranges them around the bot).
        private boolean ensureWorkstation(int depth, Set<String> visiting) {
            if (!ensureCraftingTableAvailable(depth + 1, visiting)) {
                return false;
            }
            if (!ensureNormalFurnaceAvailable(depth + 1, visiting)) {
                return false;
            }
            if (counts.getOrDefault(Items.CHEST, 0) <= 0
                    && !ensureItem(Items.CHEST, 1, depth + 1, visiting)) {
                return false;
            }
            addStep(GoalStep.placeStations());
            return true;
        }

        // Phase 3: stockpiling -- first acquire enough of the item to reach count, then emit the STOCKPILE step to store the resource in a nearby chest (best-effort).
        private boolean ensureStockpile(Goal.Stockpile g, int depth, Set<String> visiting) {
            net.minecraft.core.BlockPos base = resumeContext == null
                    ? io.github.zoyluo.minecraftai.memory.BotMemoryStore.INSTANCE
                            .of(bot.getUUID()).placeIn(bot.level(), "base").orElse(bot.blockPosition())
                    : resumeContext.origin();
            GoalSnapshotCollector.Context stockpileContext = resumeContext == null
                    ? GoalSnapshotCollector.Context.at(base)
                    : resumeContext;
            GoalSnapshot snapshot = GoalSnapshotCollector.collect(
                    bot, g, stockpileContext);
            int alreadyDelivered = new GoalPredicate.Stockpile(
                    BuiltInRegistries.ITEM.getKey(g.item()).toString(), g.count()).evaluate(snapshot).matched();
            int missing = Math.max(0, g.count() - alreadyDelivered);
            if (missing == 0) {
                return true;
            }
            if (!ensureItem(g.item(), missing, depth + 1, visiting)) {
                return false;
            }
            addStep(GoalStep.stockpile(g.item()));
            return true;
        }

        // Full build chain: the single phrase "build a house" = material provisioning (automatically
        // chopping wood/mining stone/smelting glass, reusing ensureItem's back-derivation) + a
        // construction chain.
        // Material-counting convention: BlueprintLoader.load has already expanded every op into
        // per-block placements -- hollow_box = shell block count, layer/box/fill = every block count
        // within the region, with same-coordinate placements deduplicated (an explicit placement
        // overrides an op's block, e.g. small_hut's two door-opening air blocks override the wall),
        // so counting placement-by-placement matches exactly what BuildTask actually places.
        private boolean ensureBuild(Goal.Build g, int depth, Set<String> visiting) {
            BlueprintSchema schema;
            try {
                schema = BlueprintLoader.load(g.blueprint());
            } catch (IOException e) {
                unresolved.add("blueprint_missing:" + g.blueprint());
                return false;
            }
            // Defensive: in case an unexpanded schema is somehow received (load already guarantees expansion; this is just a safety net), expand it again using the same geometry.
            if (schema.ops() != null && !schema.ops().isEmpty()) {
                try {
                    schema = BlueprintLoader.expand(schema);
                } catch (IOException e) {
                    unresolved.add("blueprint_bad_ops:" + g.blueprint());
                    return false;
                }
            }
            // Provisioning: palette placeholders are totaled by family; exact blocks are counted
            // individually.
            // [Fix for wandering off mid-build over a false material shortage]: a palette placeholder
            // (e.g. small_hut's "planks") is, at execution time, accepted by BuildTask/MaterialPalette
            // as any family member (any wood species' planks), but the old logic recorded the entire
            // group's requirement against a single species via preferredPlanks; on replan, seeing
            // "oak 79 < needed 96" would insert an oak-gathering step while ignoring the 896 blocks
            // of other plank species already in inventory -> the bot would abandon the build site and
            // chase logs into the dark until the budget ran out (root cause of the real_build 54/116
            // timeout measurement). Fix: palette material sufficiency is now judged by the family's
            // total owned count; if that is enough, no gathering step is inserted.
            Map<String, Integer> paletteNeeds = new LinkedHashMap<>();
            Map<Item, Integer> exactNeeds = new LinkedHashMap<>();
            for (BlueprintSchema.BlockPlacement placement : schema.placements()) {
                if (resumeContext != null && resumeContext.buildAnchor() != null
                        && StructureVerifier.matches(bot.level(), resumeContext.buildAnchor(), placement)) {
                    continue;
                }
                if ("minecraft:air".equals(placement.blockId())) {
                    continue;
                }
                if (placement.palette() != null && !placement.palette().isBlank()
                        && MaterialPalette.isKnown(placement.palette())) {
                    paletteNeeds.merge(placement.palette(), 1, Integer::sum);
                } else {
                    Item material = buildMaterialFor(placement);
                    if (material != null) {
                        exactNeeds.merge(material, 1, Integer::sum);
                    }
                }
            }
            // Material provisioning is best-effort: if back-deriving one material fails (recorded in
            // unresolved), it does not block the others -- whichever block is missing at build
            // execution time fails on its own; but if every material's back-derivation fails, the
            // whole thing is judged a failure (there is simply nothing to build with).
            // Note: this passes depth, not depth+1: blueprint materials are Build's "top-level
            // deliverable" -- BuildTask only takes finished goods from inventory, unlike CraftTask,
            // which at runtime automatically expands logs into planks. craftItem's Fix C suppresses
            // the intermediate planks CRAFT step when depth>0 (leaving the expansion to the
            // downstream CraftTask); that is wrong for BUILD. Build only ever enters at depth=0 (the
            // top of ensureGoal), so passing depth lets planks keep their CRAFT step as a top-level
            // product -- otherwise only logs would be stockpiled and the build would start short of
            // material.
            boolean anyResolved = paletteNeeds.isEmpty() && exactNeeds.isEmpty();
            for (Map.Entry<String, Integer> entry : paletteNeeds.entrySet()) {
                int need = entry.getValue();
                int ownedInFamily = 0;
                List<Item> family = MaterialPalette.GROUPS.get(entry.getKey());
                if (family != null) {
                    for (Item member : family) {
                        ownedInFamily += counts.getOrDefault(member, 0);
                    }
                }
                if (ownedInFamily >= need) {
                    anyResolved = true; // family total is already sufficient, no gathering step inserted
                    continue;
                }
                Item species = paletteDefaultItem(entry.getKey());
                if (species == null) {
                    continue;
                }
                // Only make up the family's shortfall: desiredCount = this species' current amount + the whole family's deficit; ensureItem's internal subtraction means only the deficit portion gets gathered.
                int desired = counts.getOrDefault(species, 0) + (need - ownedInFamily);
                if (ensureItem(species, desired, depth, visiting)) {
                    anyResolved = true;
                }
            }
            for (Map.Entry<Item, Integer> entry : exactNeeds.entrySet()) {
                if (ensureItem(entry.getKey(), entry.getValue(), depth, visiting)) {
                    anyResolved = true;
                }
            }
            if (!anyResolved) {
                return false;
            }
            addStep(GoalStep.build(g.blueprint()));
            return true;
        }

        // Blueprint block -> planning-time material item: skip air; a palette placeholder is
        // provisioned with that family's default material (planning reserves the default material,
        // while at execution time BuildTask/MaterialPalette accepts any family member); everything
        // else goes via blockId -> block -> corresponding item (a placement with a hardcoded blockId
        // is also placed by BuildTask as that exact block, so it is provisioned literally);
        // when there is no corresponding item (asItem()==AIR, e.g. a technical block or unknown id),
        // skip it and log one warning.
        private Item buildMaterialFor(BlueprintSchema.BlockPlacement placement) {
            if ("minecraft:air".equals(placement.blockId())) {
                return null;
            }
            if (placement.palette() != null && !placement.palette().isBlank()) {
                Item byPalette = paletteDefaultItem(placement.palette());
                if (byPalette != null) {
                    return byPalette;
                }
            }
            Identifier blockKey = placement.blockId() == null ? null : Identifier.tryParse(placement.blockId());
            Block block = blockKey == null ? null : BuiltInRegistries.BLOCK.getOptional(blockKey).orElse(null);
            Item item = block == null ? Items.AIR : block.asItem();
            if (item == Items.AIR) {
                BotLog.warn(LogCategory.TASK, null, "blueprint_material_skipped",
                        "block", String.valueOf(placement.blockId()));
                return null;
            }
            return item;
        }

        // palette -> default material item (uses the same convention as BlueprintLoader.fallbackBlock); planks adapt to whichever wood species is available.
        private Item paletteDefaultItem(String palette) {
            return switch (palette) {
                case "planks" -> preferredPlanks();
                case "logs" -> Items.OAK_LOG;
                case "stone_like" -> Items.COBBLESTONE;
                case "dirt_like" -> Items.DIRT;
                case "glass" -> Items.GLASS;
                default -> null;
            };
        }

        // Choosing the wood species for build planks (borrows the idea from preferredFuelLog): prefer
        // a plank species already in inventory, then the plank species matching a log already in
        // inventory (RecipeRegistry.LOGS/PLANKS are index-aligned), defaulting to oak planks only if
        // neither is present. Rationale: GATHER accepts any wood species for logs at runtime, but a
        // plank recipe is species-specific (oak_planks <- oak_log) -- gathering birch logs in a birch
        // forest and then crafting oak_planks would fail; the replan triggered by that failure reads
        // the birch logs in inventory and automatically switches to provisioning birch planks; palette
        // construction accepts any plank species, so the chain self-heals.
        private Item preferredPlanks() {
            for (Item planks : RecipeRegistry.PLANKS) {
                if (counts.getOrDefault(planks, 0) > 0) {
                    return planks;
                }
            }
            for (int i = 0; i < RecipeRegistry.LOGS.size(); i++) {
                if (counts.getOrDefault(RecipeRegistry.LOGS.get(i), 0) > 0) {
                    return RecipeRegistry.PLANKS.get(i);
                }
            }
            return Items.OAK_PLANKS;
        }

        private boolean ensureMiningFoodReserveTo(int surfaceTarget,
                                                  int depth,
                                                  Set<String> visiting) {
            return ensureMiningFoodReserveTo(surfaceTarget,
                    MiningFoodReserve.MIN_DEEP_MINE_UNITS, depth, visiting, false);
        }

        private boolean ensureMiningFoodReserveTo(int surfaceTarget,
                                                  int depth,
                                                  Set<String> visiting,
                                                  boolean bootstrapStonePickBeforeFurnace) {
            return ensureMiningFoodReserveTo(surfaceTarget,
                    MiningFoodReserve.MIN_DEEP_MINE_UNITS, depth, visiting,
                    bootstrapStonePickBeforeFurnace);
        }

        private boolean ensureMiningFoodReserveTo(int surfaceTarget,
                                                  int deepMineFloor,
                                                  int depth,
                                                  Set<String> visiting) {
            return ensureMiningFoodReserveTo(surfaceTarget, deepMineFloor,
                    depth, visiting, false);
        }

        private boolean ensureMiningFoodReserveTo(int surfaceTarget,
                                                  int deepMineFloor,
                                                  int depth,
                                                  Set<String> visiting,
                                                  boolean bootstrapStonePickBeforeFurnace) {
            int have = MiningFoodReserve.units(counts);
            if (!surfaceAcquisitionAllowed) {
                int required = Math.max(MiningFoodReserve.MIN_DEEP_MINE_UNITS,
                        deepMineFloor);
                if (have < required) {
                    unresolved.add("deep_mining_food_reserve_depleted:have=" + have
                            + ":required=" + required);
                    return false;
                }
                return true;
            }
            return ensureFoodTo(surfaceTarget, depth, visiting,
                    bootstrapStonePickBeforeFurnace);
        }

        // Hunt-to-cook loop: gather target cooked food/bread items (high saturation, safe). Mining
        // provisioning uses per-mission targets derived from MiningBudget; the casual "go hunting /
        // go get something to eat" entry point (Goal.Food) uses a specified amount.
        // When there are no animals/no furnace/no fuel, GoalExecutor skips the corresponding
        // best-effort step (see handleStepFailure) without blocking the main goal.
        /** Cooked units this plan already provisioned via hunt+cook (species unknown until the kill). */
        private int provisionedFoodUnits;

        private int plannedFoodUnits() {
            return MiningFoodReserve.units(counts) + provisionedFoodUnits;
        }

        private boolean ensureFoodTo(int target, int depth, Set<String> visiting) {
            return ensureFoodTo(target, depth, visiting, false);
        }

        private boolean ensureFoodTo(int target,
                                     int depth,
                                     Set<String> visiting,
                                     boolean bootstrapStonePickBeforeFurnace) {
            int cooked = plannedFoodUnits();
            if (cooked >= target) {
                return true;
            }
            if (restrictSurfaceAcquisition) {
                unresolved.add("surface_food_acquisition_unavailable:have=" + cooked
                        + ":required=" + target);
                return false;
            }
            int needCooked = target - cooked;
            // Perception-driven source selection: no animals but grass present -> farm bread, but
            // **only when a fast-path material is already available** (enough wheat and only crafting
            // remains / enough seeds and only planting-and-harvesting remains). Starting from zero
            // (cutting grass and waiting for natural growth) takes 15-20 minutes, which would
            // certainly time out a Food goal aimed at "eating as soon as possible" (measured in
            // real_food's natural-world runs: grass-cutting can fail to yield seeds, and even when it
            // succeeds the crop isn't ready in time -- a cascade of FAILs). If there is no fast path,
            // fall back to hunting: HuntTask has its own roam expedition; 64 blocks is only the
            // planning perception radius, not a cap on hunting range -- if there are no animals
            // nearby it will actively travel further to find some. Foraged berries have low
            // saturation, so they are not currently used as a provisioning source.
            boolean breadFastPath = counts.getOrDefault(Items.WHEAT, 0) >= needCooked * 3
                    || counts.getOrDefault(Items.WHEAT_SEEDS, 0) >= needCooked * 3;
            if (!hasPreyNearby && hasGrassNearby && breadFastPath) {
                ensureItem(Items.BREAD, needCooked, depth + 1, visiting);
                return true;
            }
            // Barren fallback: no animals to hunt, and no bread fast path either, but sweet berry
            // bushes are nearby (common in taiga) -> pick berries and eat them directly. Saturation is
            // low (2 points per berry), converted at a 2:1 ratio; needs no furnace/fuel, and is the
            // last resort for "something to eat right now"
            // (measured in a taiga world: 10 hunt-roam attempts over 1092 ticks still yielded 0 prey,
            // wasting the entire hunt+cook chain).
            if (!hasPreyNearby && hasBerriesNearby) {
                ensureItem(Items.SWEET_BERRIES, needCooked * 2, depth + 1, visiting);
                return true;
            }
            int raw = 0;
            for (Item m : RAW_MEAT_ITEMS) {
                raw += counts.getOrDefault(m, 0);
            }
            int huntNeed = Math.max(0, needCooked - raw);
            // Raw food is a surface acquisition. Provision the cheap wooden weapon and hunt before
            // any furnace bootstrap can dig a stone staircase; otherwise the next HUNT starts at
            // the mine bottom and strict_survival correctly refuses the old teleport-to-surface
            // escape hatch.
            if (huntNeed > 0) {
                if (!hasAnySword() && !ensureItem(Items.WOODEN_SWORD, 1, depth + 1, visiting)) {
                    return false;
                }
                int remainingHunt = huntNeed;
                int batch = 1;
                while (remainingHunt > 0) {
                    int batchTarget = Math.min(4, remainingHunt);
                    addStep(GoalStep.huntBatch(batchTarget, batch++));
                    remainingHunt -= batchTarget;
                }
            }
            // A from-zero obsidian expedition used to open the furnace's eight-cobblestone shaft
            // with its only wooden pick, let background resupply consume another handle, then open
            // the four-pick readiness shaft with wood and consume a third.  Preserve the established
            // hunt-first surface ordering, but cross the normal three-cobblestone upgrade boundary
            // before any furnace or bulk-stone work.  The later four-pick target can count this
            // physical stone pick; all remaining stone acquisition then uses the renewable tier.
            if (bootstrapStonePickBeforeFurnace
                    && !hasCookingFurnaceAvailable()
                    && bestPickaxeTier() < ToolTier.STONE
                    && !ensurePickaxeTier(ToolTier.STONE, depth + 1, visiting)) {
                return false;
            }
            // Cooking meat requires a furnace: if there is none, deterministically back-derive one
            // (8 cobblestone -> mine stone -> needs a pick -> planks/sticks -> logs -> chop wood),
            // letting "chop wood + make basic tools" expand automatically as a base capability in the
            // correct order, rather than being skipped as best-effort and left for the LLM to
            // improvise (measured: the LLM would gather cobblestone directly without first making a
            // pick, and be unable to mine it). The whole Food chain is best-effort as a fallback, so a
            // material-poor environment degrades gracefully instead of getting stuck.
            if (!hasCookingFurnaceAvailable()) {
                ensureCookingFurnace(depth + 1, visiting);
            }
            // Cooking needCooked units of food requires fuel -- this step was previously missing, and
            // cooking a large amount of meat would hit SmeltTask's out_of_fuel, wasting the entire
            // food chain. Consistent with ore smelting's smeltItem: coal/charcoal already in inventory
            // each cook roughly 8 units; if insufficient, top up with logs (1 log cooks roughly
            // 1.5 units), preferring an already-owned wood species, best-effort chopping wood (if
            // there are no trees, it goes to unresolved; at execution time COOK_FOOD degrades further
            // on a fuel shortage rather than getting stuck).
            int coalLike = counts.getOrDefault(Items.COAL, 0) + counts.getOrDefault(Items.CHARCOAL, 0);
            // SmeltTask.remainingToQueue strictly caps at targetCount, so fuel only needs to be
            // provisioned for the actual cooked-food target; the old 2x budget would inflate a
            // 24-unit readiness requirement into 32 fuel logs -- measured on seed 3000, this just
            // meant excessive tree-chopping before work even started.
            int fuelDeficit = needCooked - coalLike * 8;
            if (fuelDeficit > 0) {
                Item fuelLog = preferredFuelLog();
                int logsForFuel = Math.max(1, (int) Math.ceil(fuelDeficit / 1.5));
                int availableLogs = countItems(RecipeRegistry.LOGS);
                int missingLogs = Math.max(0, logsForFuel - availableLogs);
                if (missingLogs > 0 && !ensureItem(fuelLog,
                        counts.getOrDefault(fuelLog, 0) + missingLogs, depth + 1, visiting)) {
                    return false;
                }
                // Fuel is a real future consumption. Reserve it across every usable log species so
                // a later replan neither asks for 32 new oak logs beside a stack of birch nor spends
                // the same logs again on underground tool handles.
                consumeItems(RecipeRegistry.LOGS, logsForFuel);
            }
            addStep(GoalStep.cookFood(needCooked)); // cook into cooked meat (any raw meat already in inventory is cooked along with it)
            provisionedFoodUnits += needCooked;
            return true;
        }

        /**
         * Seals one maximum-size surface shelter budget away from every later symbolic consumer.
         *
         * <p>Raw logs are the hard unit because every later physical recipe consumes them one for
         * one. Planks remain ordinary recipe stock: reserving them only symbolically would not stop
         * CraftTask from spending four carried planks while leaving one newly gathered log, which
         * loses three physical shelter blocks. The logs remain physically carried and are spent
         * only if DangerWatcher opens an emergency shelter transaction. This ownership ends at the
         * descent hand-off, where the mine's protected stone/service ledger becomes the emergency
         * enclosure budget; underground replans may therefore use carried logs as tool material.</p>
         */
        private void beginSurfaceEmergencyShelterWoodReserve() {
            if (surfaceEmergencyShelterWoodPending >= 0) {
                return;
            }
            int target = EmergencyShelterTask.MAX_PLACEMENT_BLOCKS;
            int logs = Math.min(target, countItems(RecipeRegistry.LOGS));
            consumeItems(RecipeRegistry.LOGS, logs);
            surfaceEmergencyShelterWoodPending = target - logs;
        }

        private boolean reserveSurfaceEmergencyShelterWood(int depth,
                                                           Set<String> visiting) {
            beginSurfaceEmergencyShelterWoodReserve();
            if (surfaceEmergencyShelterWoodPending == 0) {
                return true;
            }
            // Food planning may have produced a harmless raw-log remainder. Claim it before
            // gathering the outstanding reserve, but never return the logs sealed at begin() to
            // symbolic stock.
            int available = Math.min(
                    surfaceEmergencyShelterWoodPending,
                    countItems(RecipeRegistry.LOGS));
            consumeItems(RecipeRegistry.LOGS, available);
            surfaceEmergencyShelterWoodPending -= available;
            if (surfaceEmergencyShelterWoodPending > 0) {
                Item log = preferredFuelLog();
                int desired = saturatedAdd(
                        counts.getOrDefault(log, 0), surfaceEmergencyShelterWoodPending);
                if (!ensureItem(log, desired, depth + 1, visiting)) {
                    return false;
                }
                consumeItems(RecipeRegistry.LOGS, surfaceEmergencyShelterWoodPending);
                surfaceEmergencyShelterWoodPending = 0;
            }
            return true;
        }

        private boolean ensureItem(Item item, int desiredCount, int depth, Set<String> visiting) {
            if (depth > maxDepth) {
                unresolved.add("max_depth:" + id(item));
                return false;
            }
            int available = counts.getOrDefault(item, 0);
            if (available >= desiredCount) {
                return true;
            }
            String key = id(item) + ":" + desiredCount;
            if (!visiting.add(key)) {
                unresolved.add("cycle:" + id(item));
                return false;
            }
            int missing = desiredCount - available;
            Optional<RecipeRegistry.Recipe> recipe = RecipeRegistry.find(item);
            boolean resolved = recipe.isPresent()
                    ? craftItem(item, missing, recipe.get(), depth, visiting)
                    : acquireBaseItem(item, missing, depth, visiting);
            visiting.remove(key);
            return resolved;
        }

        // S7: roll back steps to a given size -- when a recipe fails partway through craftItem, clear the intermediate steps it already emitted, avoiding a half-finished leftover polluting the plan.
        private void rollbackSteps(int to) {
            while (steps.size() > to) {
                steps.remove(steps.size() - 1);
            }
        }

        private boolean craftItem(Item item, int missing, RecipeRegistry.Recipe recipe, int depth, Set<String> visiting) {
            int crafts = divideRoundUp(missing, recipe.outputCount());
            int stepsBefore = steps.size(); // S7: on this recipe's failure, roll back the intermediate steps already emitted
            Map<Item, Integer> countsBefore = new HashMap<>(counts);
            if (recipe.needsCraftingTable() && item != Items.CRAFTING_TABLE) {
                if (!ensureCraftingTableAvailable(depth + 1, visiting)) {
                    counts.clear();
                    counts.putAll(countsBefore);
                    rollbackSteps(stepsBefore);
                    return false;
                }
            }
            for (RecipeRegistry.Ingredient ingredient : recipe.ingredients()) {
                int need = ingredient.count() * crafts;
                if (!ensureIngredient(ingredient, need, depth + 1, visiting)) {
                    unresolved.add("missing:" + ingredient.anyOf() + " x" + need + " for " + id(item));
                    counts.clear();
                    counts.putAll(countsBefore);
                    rollbackSteps(stepsBefore);
                    return false;
                }
                consume(ingredient, need);
            }
            counts.merge(item, recipe.outputCount() * crafts, Integer::sum);
            // Fix C: an intermediate planks item does not get its own CRAFT step -- a planks recipe
            // is species-specific (oak_planks <- oak_log), but downstream recipes (stick,
            // crafting_table, tools) all accept any planks family member, and their CraftTask will
            // automatically expand planks from whatever log species actually ended up in inventory
            // (which might be birch, spruce, ...). If a "CRAFT oak_planks" step were still emitted, it
            // would fail in a biome with only birch. This is kept only when planks is itself the
            // top-level goal (depth==0, e.g. achieve_goal planks), otherwise that goal would end up
            // with no output step at all. The GATHER step for logs is still emitted as usual (in
            // acquireBaseItem).
            if (!(depth > 0 && RecipeRegistry.PLANKS.contains(item))) {
                addStep(GoalStep.craft(item, recipe.outputCount() * crafts));
            }
            return true;
        }

        private boolean acquireBaseItem(Item item, int missing, int depth, Set<String> visiting) {
            if (restrictSurfaceAcquisition && isSurfaceOnlyResource(item)) {
                unresolved.add("underground_surface_resource_unavailable:" + id(item));
                return false;
            }
            if (RecipeRegistry.LOGS.contains(item)) {
                addStep(GoalStep.gather(item, missing));
                counts.merge(item, missing, Integer::sum);
                return true;
            }
            if (item == Items.WHEAT_SEEDS) {
                // Wheat seeds -> obtained by cutting grass (GatherQuotaTask maps seeds to short grass/tall grass/ferns, which have a chance to drop seeds when broken).
                addStep(GoalStep.gather(item, missing));
                counts.merge(item, missing, Integer::sum);
                return true;
            }
            if (item == Items.SWEET_BERRIES || item == Items.MELON_SLICE) {
                // Wild food -> foraging (GatherQuotaTask maps wild food to sweet berry bushes/melons, harvesting whichever is nearest).
                addStep(GoalStep.gather(item, missing));
                counts.merge(item, missing, Integer::sum);
                return true;
            }
            if (item == Items.SUGAR_CANE) {
                // Sugar cane -> cut sugar cane (GatherQuotaTask breaks sugar_cane blocks to drop sugar cane; the sugar source for the cake chain).
                addStep(GoalStep.gather(item, missing));
                counts.merge(item, missing, Integer::sum);
                return true;
            }
            if (item == Items.MILK_BUCKET) {
                // Milk bucket -> first ensure an equal number of empty buckets (an empty bucket can be back-derived from 3 iron, or already be in inventory), then emit the milking step (requires a cow nearby, best-effort at execution time).
                if (!ensureItem(Items.BUCKET, missing, depth + 1, visiting)) {
                    return false;
                }
                addStep(GoalStep.milkCow(missing));
                counts.merge(Items.MILK_BUCKET, missing, Integer::sum);
                return true;
            }
            if (item == Items.COBBLESTONE) {
                if (!ensurePickaxeTier(ToolTier.WOOD, depth + 1, visiting)) {
                    return false;
                }
                addStep(GoalStep.mine(Blocks.STONE, missing));
                counts.merge(Items.COBBLESTONE, missing, Integer::sum);
                return true;
            }
            if (item == Items.OBSIDIAN) {
                // The obsidian expedition ordering is a hard contract: first provision initial
                // rations, cheap tunneling picks, plug blocks and spare sticks at the surface; then
                // separately obtain 3 iron for the bucket and physically return to the surface to find
                // a visible water source; once the water bucket is in hand, top up the complete food
                // quota (see the staged formula in
                // MiningBudget.obsidianExpeditionInitialFoodTarget); only then enter the
                // diamond-pick deep-dive chain.
                // The bucket recipe consumes its own 3 iron, so the subsequent iron pick/spare iron is
                // back-derived independently and must not borrow the bucket's iron.
                int missionTarget = saturatedAdd(
                        counts.getOrDefault(Items.OBSIDIAN, 0), missing);
                ObsidianToolProvision toolProvision = obsidianToolProvision(missing);
                ObsidianTorchProvision torchProvision =
                        obsidianTorchProvision(toolProvision);
                if (!ensureObsidianExpeditionReadiness(
                        missing, missionTarget, toolProvision, torchProvision,
                        depth + 1, visiting)) {
                    return false;
                }
                if (counts.getOrDefault(Items.WATER_BUCKET, 0) <= 0) {
                    if (counts.getOrDefault(Items.BUCKET, 0) <= 0
                            && !ensureItem(Items.BUCKET, 1, depth + 1, visiting)) {
                        return false;
                    }
                    addStep(GoalStep.acquireWater());
                    plannedY = waterReturnY;
                    initialOrePerceptionValid = false;
                    consumeItem(Items.BUCKET, 1);
                    counts.merge(Items.WATER_BUCKET, 1, Integer::sum);
                }
                // Second stage of staged provisioning: the water-fetching expedition has already
                // brought the bot to another surface herd, so this stage tops up the complete
                // expedition quota before entering the iron/diamond deep-dive chain. The runtime
                // preflight food gate is unchanged and still requires the full amount.
                if (!ensureMiningFoodReserveTo(
                        MiningBudget.obsidianExpeditionFoodTarget(missionTarget),
                        depth + 1, visiting, true)) {
                    return false;
                }
                // Provision the ore-acquisition tool first. A raw-remaining=2 diamond/netherite
                // pick is a valid tier but cannot mine the three diamonds needed for its own
                // replacement. The immutable provision computed before readiness binds both this
                // dependency and the exact stick reserve to the same resource calculation.
                if (!ensureObsidianAcquisitionTool(toolProvision, depth + 1, visiting)
                        || !ensureObsidianTargetToolDurability(
                        toolProvision, depth + 1, visiting)) {
                    return false;
                }
                steps.add(GoalStep.obsidianPreflight(missing));
                addStep(GoalStep.makeObsidian(missing));
                counts.merge(Items.OBSIDIAN, missing, Integer::sum);
                return true;
            }
            // P2: an ore drop item -> its corresponding ore block (via a unified mapping table). The
            // pickaxe tier needed to mine it is decided by ToolTier; ensureMineOre internally calls
            // ensurePickaxeTier first to automatically fill in the pickaxe chain (e.g. diamond needs
            // an iron pick -> back-derive the iron pick first).
            Block oreOf = oreBlockFor(item);
            if (oreOf != null) {
                // ensureItem has already converted desiredCount into missing; ensureMineOre will
                // internally subtract the current drop-item inventory once more, so the total target
                // passed here must be "current + deficit." Passing missing alone would, after a
                // 64-diamond mission finishes its first batch of 8, end up with count=8/owned=8,
                // incorrectly planning an empty step and finishing as PARTIAL.
                int desiredTotal = counts.getOrDefault(item, 0) + missing;
                return ensureMineOre(Set.of(oreOf), desiredTotal, depth + 1, visiting);
            }
            SmeltRecipe smelt = smeltRecipeFor(item);
            if (smelt != null) {
                return smeltItem(smelt, missing, depth, visiting);
            }
            if ("smelt".equals(AcquisitionHints.source(item))) {
                unresolved.add("missing_smelt_recipe:" + id(item));
                return false;
            }
            if ("mine".equals(AcquisitionHints.source(item)) && item instanceof net.minecraft.world.item.BlockItem blockItem) {
                addStep(GoalStep.mine(blockItem.getBlock(), missing));
                counts.merge(item, missing, Integer::sum);
                return true;
            }
            // S4: raw meat -> hunting (best-effort, unspecific hunting; HuntTask hunts cow/pig/sheep/
            // chicken/rabbit. Counting it optimistically lets the food chain be back-derived; which
            // meat is actually obtained at runtime is not fixed, and module B's food consumption
            // treats it as "generic food").
            if (item == Items.BEEF || item == Items.PORKCHOP || item == Items.MUTTON
                    || item == Items.CHICKEN || item == Items.RABBIT) {
                addStep(GoalStep.hunt(missing));
                counts.merge(item, missing, Integer::sum);
                return true;
            }
            // S4: crop produce -> farm it in place (till/plant/wait to ripen/harvest).
            FarmAction.CropSpec crop = cropSpecForProduce(item);
            if (crop != null) {
                // Farming needs a hoe (FarmAction.till with no hoe -> missing_hoe). This branch is
                // the entry point for Goal.Food -> bread -> wheat; it previously only called
                // ensureSeeds and was missing the hoe back-derivation (ensureHarvestCrop has it, this
                // path did not) -> the FARM step's till would fail for nothing, breaking the bread
                // chain.
                // best-effort provisions a wooden hoe (consistent with ensureHarvestCrop; in a
                // wood-poor environment the hoe going unresolved does not block the whole Food chain
                // -- the FARM step is still emitted, and till degrades further on a missing hoe at
                // execution time, following the food chain's "degrade on missing material instead of
                // getting stuck" philosophy).
                if (!hasAnyHoe()) {
                    ensureItem(Items.WOODEN_HOE, 1, depth + 1, visiting);
                }
                ensureSeeds(crop.seed(), item, missing, depth, visiting); // ensure seeds before farming (wheat seeds are obtained by cutting grass)
                addStep(GoalStep.farm(crop.crop(), crop.seed(), item, missing));
                counts.merge(item, missing, Integer::sum);
                return true;
            }
            unresolved.add("unresolved:" + id(item) + " source=" + AcquisitionHints.source(item));
            return false;
        }

        private boolean isSurfaceOnlyResource(Item item) {
            return RecipeRegistry.LOGS.contains(item)
                    || RAW_MEAT_ITEMS.contains(item)
                    || item == Items.WHEAT_SEEDS
                    || item == Items.SWEET_BERRIES
                    || item == Items.MELON_SLICE
                    || item == Items.SUGAR_CANE
                    || item == Items.MILK_BUCKET
                    || cropSpecForProduce(item) != null;
        }

        private boolean ensureObsidianExpeditionReadiness(int targetCount,
                                                          int missionTarget,
                                                          ObsidianToolProvision toolProvision,
                                                          ObsidianTorchProvision torchProvision,
                                                          int depth,
                                                          Set<String> visiting) {
            boolean reserveSurfaceShelter = missionTarget >= 32
                    && surfaceAcquisitionAllowed;
            if (reserveSurfaceShelter) {
                beginSurfaceEmergencyShelterWoodReserve();
            }
            int unresolvedBefore = unresolved.size();
            // 8 cooked-food units is only enough for a short "prepared" leg; a 64-block obsidian
            // mission spans 8 service segments, and deep underground the bot can neither hunt nor
            // draw from a pre-placed depot, so running out of food means an unrecoverable
            // mining_service_food_reserve_depleted. The full quota (formula and unit tests live in
            // MiningBudget) is supplied in stages: this point only provisions the floor+buffer initial
            // rations, with the water-fetching expedition bringing the bot to a second surface herd
            // before topping up the rest -- cramming all 9 hunts onto the spawn herd exhausted all 5
            // public test seeds and died to replan_same_step:hunt_no_progress. A small goal's full
            // requirement is already <= the initial amount, so it keeps the single-gate form.
            if (!ensureMiningFoodReserveTo(
                    MiningBudget.obsidianExpeditionInitialFoodTarget(missionTarget),
                    depth + 1, visiting, true)
                    || unresolved.size() > unresolvedBefore) {
                return false;
            }
            if (reserveSurfaceShelter
                    && !reserveSurfaceEmergencyShelterWood(depth + 1, visiting)) {
                return false;
            }
            // Keep the tool-upgrade boundary explicit: four stone picks are cheap tunnel tools;
            // diamond/iron durability remains reserved for target blocks and later replacement.
            int stoneLikeTarget = ServicePolicy
                    .bootstrapStoneLikeTarget(targetCount)
                    + MiningBudget.OBSIDIAN_BOOTSTRAP_CHANNEL_RETRY_STONE_LIKE;
            int serviceStickTarget = ServicePolicy
                    .bootstrapStickTarget(targetCount)
                    + MiningBudget.OBSIDIAN_BOOTSTRAP_CHANNEL_RETRY_STICKS;
            boolean needsStoneSword = counts.getOrDefault(Items.STONE_SWORD, 0) <= 0;
            int postReadinessStickTarget = serviceStickTarget
                    + toolProvision.postReadinessSticks()
                    + torchProvision.recipeSticks();
            if (!ensureCraftingTableAvailable(depth + 1, visiting)
                    || !ensureItem(Items.STONE_PICKAXE, OBSIDIAN_EXPEDITION_STONE_PICKS,
                    depth + 1, visiting)
                    // The weapon is a safety prerequisite for the long stone reserve shaft, not a
                    // reward after it. Craft its independent +2 stone/+1 stick margin first, then
                    // replenish the untouched service ledgers below.
                    || (needsStoneSword && !ensureItem(Items.STONE_SWORD, 1,
                    depth + 1, visiting))
                    || !ensureItem(Items.COBBLESTONE,
                    stoneLikeTarget,
                    depth + 1, visiting)
                    // Readiness runs before the acquisition + target-pick chain. Keep its exact
                    // handle/torch cost separate from all preflight + 8/16/24 repair windows.
                    || !ensureItem(Items.STICK,
                    postReadinessStickTarget,
                    depth + 1, visiting)
                    // Torch recipes consume their coal before the bucket/tool/spare-iron smelts.
                    // Provision both ledgers together so an underground replan cannot arrive at
                    // the first furnace with every carried fuel item already converted to light.
                    || !ensureItem(Items.COAL, torchProvision.recipeSticks()
                            + torchProvision.futureSmeltFuelItems(),
                    depth + 1, visiting)
                    || !ensureItem(Items.TORCH, torchProvision.targetCount(),
                    depth + 1, visiting)) {
                return false;
            }
            return unresolved.size() == unresolvedBefore;
        }

        /**
         * Computes one immutable post-readiness tool contract. Existing iron durability is spent
         * first because the mining-channel selector chooses the lowest sufficient tier. Target-tier
         * durability may safely acquire replacement diamonds only when doing so still leaves the
         * final obsidian break budget intact; otherwise a renewable iron pick is provisioned.
         */
        private ObsidianToolProvision obsidianToolProvision(int targetCount) {
            int targetUsable = plannedObsidianToolUsableDurability();
            int missingTargetDurability = Math.max(0, targetCount - targetUsable);
            int freshDiamondDurability = freshUsableDurability(Items.DIAMOND_PICKAXE);
            int replacementPicks = missingTargetDurability == 0 ? 0
                    : divideRoundUpSafe(missingTargetDurability, freshDiamondDurability);
            int replacementDiamonds = saturatedMultiply(replacementPicks, 3);
            int diamondsToMine = Math.max(0,
                    replacementDiamonds - counts.getOrDefault(Items.DIAMOND, 0));

            int ironUsable = plannedToolUsableDurability(Items.IRON_PICKAXE);
            int targetToolSpend = Math.max(0, diamondsToMine - ironUsable);
            boolean acquisitionCapacitySufficient = saturatedAdd(ironUsable, targetUsable)
                    >= diamondsToMine;
            long finalTargetUsableWithoutIron = (long) targetUsable
                    - Math.min(targetUsable, targetToolSpend)
                    + (long) replacementPicks * freshDiamondDurability;
            boolean acquisitionIronRequired = diamondsToMine > 0
                    && (!acquisitionCapacitySufficient
                    || finalTargetUsableWithoutIron < targetCount);
            int missingIronUsable = acquisitionIronRequired
                    ? Math.max(0, diamondsToMine - ironUsable) : 0;
            int freshIronDurability = freshUsableDurability(Items.IRON_PICKAXE);
            int acquisitionIronPicks = missingIronUsable == 0 ? 0
                    : divideRoundUpSafe(missingIronUsable, freshIronDurability);

            int required = saturatedMultiply(replacementPicks, 2);
            required = saturatedAdd(required, saturatedMultiply(acquisitionIronPicks, 2));
            return new ObsidianToolProvision(
                    counts.getOrDefault(Items.DIAMOND_PICKAXE, 0),
                    counts.getOrDefault(Items.IRON_PICKAXE, 0),
                    replacementPicks, acquisitionIronPicks,
                    diamondsToMine, required);
        }

        /**
         * Forecasts every dependency descent before the first obsidian pool. Runtime planning later
         * debits the same per-descent budget from symbolic torch inventory; the additional eight are
         * therefore still present when branch search starts.
         */
        private ObsidianTorchProvision obsidianTorchProvision(
                ObsidianToolProvision toolProvision) {
            int simulatedY = plannedY;
            boolean perceptionValid = initialOrePerceptionValid;
            int expectedUse = 0;
            int futureSmeltFuelItems = 0;
            int ironIngots = counts.getOrDefault(Items.IRON_INGOT, 0);
            int rawIron = counts.getOrDefault(Items.RAW_IRON, 0);

            boolean needsWater = counts.getOrDefault(Items.WATER_BUCKET, 0) <= 0;
            if (needsWater && counts.getOrDefault(Items.BUCKET, 0) <= 0) {
                if (ironIngots < 3) {
                    futureSmeltFuelItems++;
                }
                IronMaterialForecast bucketIron = consumeForecastIron(
                        ironIngots, rawIron, 3, simulatedY, perceptionValid);
                ironIngots = bucketIron.ironIngots();
                rawIron = bucketIron.rawIron();
                simulatedY = bucketIron.plannedY();
                perceptionValid = bucketIron.perceptionValid();
                expectedUse = saturatedAdd(expectedUse, bucketIron.torches());
            }
            if (needsWater) {
                simulatedY = waterReturnY;
                perceptionValid = false;
            }

            int acquisitionIron = saturatedMultiply(
                    toolProvision.acquisitionIronPickaxes(), 3);
            if (acquisitionIron > 0) {
                if (ironIngots < acquisitionIron) {
                    futureSmeltFuelItems++;
                }
                IronMaterialForecast toolIron = consumeForecastIron(
                        ironIngots, rawIron, acquisitionIron,
                        simulatedY, perceptionValid);
                simulatedY = toolIron.plannedY();
                perceptionValid = toolIron.perceptionValid();
                expectedUse = saturatedAdd(expectedUse, toolIron.torches());
            }

            if (toolProvision.diamondsToMine() > 0) {
                DescentForecast diamondDescent = forecastOreDescent(
                        simulatedY, perceptionValid,
                        OreScan.expandOreFamilies(Set.of(Blocks.DIAMOND_ORE)),
                        toolProvision.diamondsToMine(), true);
                // A small deep-diamond dependency reserves three spare iron ingots before its
                // descent. Forecast that nested acquisition in the same order as ensureMineOre:
                // it may add an iron descent and always opens one physical smelt when ingots are
                // missing, even if carried raw iron already covers the ore requirement.
                if (diamondDescent.descends()
                        && toolProvision.diamondsToMine()
                        < MiningBudget.EXPEDITION_THRESHOLD) {
                    int spareMissing = Math.max(0, SPARE_IRON_INGOTS - ironIngots);
                    if (spareMissing > 0) {
                        futureSmeltFuelItems++;
                        int rawUsed = Math.min(rawIron, spareMissing);
                        rawIron -= rawUsed;
                        int spareToMine = spareMissing - rawUsed;
                        if (spareToMine > 0) {
                            DescentForecast spareIron = forecastOreDescent(
                                    simulatedY, perceptionValid,
                                    OreScan.expandOreFamilies(Set.of(Blocks.IRON_ORE)),
                                    spareToMine, false);
                            simulatedY = spareIron.plannedY();
                            perceptionValid = spareIron.perceptionValid();
                            expectedUse = saturatedAdd(expectedUse, spareIron.torches());
                        }
                        diamondDescent = forecastOreDescent(
                                simulatedY, perceptionValid,
                                OreScan.expandOreFamilies(Set.of(Blocks.DIAMOND_ORE)),
                                toolProvision.diamondsToMine(), true);
                    }
                }
                expectedUse = saturatedAdd(expectedUse, diamondDescent.torches());
            }

            int target = saturatedAdd(expectedUse, TORCH_TARGET);
            int missing = Math.max(0,
                    target - counts.getOrDefault(Items.TORCH, 0));
            int recipeSticks = divideRoundUpSafe(missing, 4);
            return new ObsidianTorchProvision(
                    target, recipeSticks, futureSmeltFuelItems);
        }

        private IronMaterialForecast consumeForecastIron(int ironIngots,
                                                         int rawIron,
                                                         int required,
                                                         int fromY,
                                                         boolean perceptionValid) {
            int remaining = Math.max(0, required);
            int usedIngots = Math.min(Math.max(0, ironIngots), remaining);
            ironIngots -= usedIngots;
            remaining -= usedIngots;
            int usedRaw = Math.min(Math.max(0, rawIron), remaining);
            rawIron -= usedRaw;
            remaining -= usedRaw;
            if (remaining == 0) {
                return new IronMaterialForecast(
                        ironIngots, rawIron, fromY, perceptionValid, 0);
            }
            DescentForecast descent = forecastOreDescent(
                    fromY, perceptionValid,
                    OreScan.expandOreFamilies(Set.of(Blocks.IRON_ORE)),
                    remaining, false);
            return new IronMaterialForecast(
                    ironIngots, rawIron,
                    descent.plannedY(), descent.perceptionValid(), descent.torches());
        }

        private DescentForecast forecastOreDescent(int fromY,
                                                   boolean perceptionValid,
                                                   Set<Block> ores,
                                                   int targetCount,
                                                   boolean rareOre) {
            int mineY = bestMiningY(ores);
            boolean longRareExpedition = rareOre
                    && targetCount >= MiningBudget.EXPEDITION_THRESHOLD;
            boolean knownOreNearby = perceptionValid && oreNearby.test(ores);
            boolean descends = fromY - mineY > DESCEND_THRESHOLD
                    && (longRareExpedition || !knownOreNearby);
            if (!descends) {
                return new DescentForecast(fromY, perceptionValid, 0, false);
            }
            return new DescentForecast(
                    mineY, false, descendTorchBudget(fromY, mineY), true);
        }

        private static int descendTorchBudget(int fromY, int targetY) {
            int depth = Math.max(0, fromY - targetY);
            return depth == 0 ? 0 : divideRoundUpSafe(depth, DESCEND_TORCH_EVERY);
        }

        private static int roundUpToTorchRecipe(int target) {
            int batches = divideRoundUpSafe(Math.max(0, target), 4);
            return saturatedMultiply(batches, 4);
        }

        private boolean ensureObsidianAcquisitionTool(ObsidianToolProvision provision,
                                                      int depth,
                                                      Set<String> visiting) {
            if (provision.acquisitionIronPickaxes() == 0) {
                return true;
            }
            int desiredIronPicks = saturatedAdd(provision.baselineIronPickaxes(),
                    provision.acquisitionIronPickaxes());
            return ensureItem(Items.IRON_PICKAXE, desiredIronPicks,
                    depth + 1, visiting);
        }

        private boolean ensureObsidianTargetToolDurability(ObsidianToolProvision provision,
                                                           int depth,
                                                           Set<String> visiting) {
            if (provision.replacementDiamondPickaxes() == 0) {
                return true;
            }
            int desiredDiamondPicks = saturatedAdd(provision.baselineDiamondPickaxes(),
                    provision.replacementDiamondPickaxes());
            return ensureItem(Items.DIAMOND_PICKAXE, desiredDiamondPicks,
                    depth + 1, visiting);
        }

        private record ObsidianToolProvision(int baselineDiamondPickaxes,
                                             int baselineIronPickaxes,
                                             int replacementDiamondPickaxes,
                                             int acquisitionIronPickaxes,
                                             int diamondsToMine,
                                             int postReadinessSticks) {
        }

        private record ObsidianTorchProvision(int targetCount,
                                              int recipeSticks,
                                              int futureSmeltFuelItems) {
        }

        private record DescentForecast(int plannedY,
                                       boolean perceptionValid,
                                       int torches,
                                       boolean descends) {
        }

        private record IronMaterialForecast(int ironIngots,
                                            int rawIron,
                                            int plannedY,
                                            boolean perceptionValid,
                                            int torches) {
        }

        private int plannedObsidianToolUsableDurability() {
            return saturatedAdd(plannedToolUsableDurability(Items.DIAMOND_PICKAXE),
                    plannedToolUsableDurability(Items.NETHERITE_PICKAXE));
        }

        private int plannedToolUsableDurability(Item item) {
            int initialCount = initialCounts.getOrDefault(item, 0);
            int plannedCount = counts.getOrDefault(item, 0);
            int newTools = Math.max(0, plannedCount - initialCount);
            int plannedFresh = saturatedMultiply(freshUsableDurability(item), newTools);
            return saturatedAdd(
                    initialToolUsableDurability.getOrDefault(item, 0), plannedFresh);
        }

        private boolean smeltItem(SmeltRecipe recipe, int missing, int depth, Set<String> visiting) {
            if (!ensureFurnaceFor(recipe.input(), recipe.output(), depth + 1, visiting)) {
                return false;
            }
            // GOALFIX-GF2: needs missing units of input to smelt missing units of output; prefer
            // what is already in inventory and only make up the deficit (ensureItem internally
            // computes missing = desired - available) -- do not mine an extra batch on top of what's
            // already owned.
            if (!ensureItem(recipe.input(), missing, depth + 1, visiting)) {
                return false;
            }
            // Fuel: prefer coal/charcoal already in inventory (1 unit smelts 8), and only chop logs
            // to cover the shortfall when there isn't enough (1 log smelts roughly 1.5).
            // (Previously it chopped logs unconditionally without using coal already in inventory --
            // even given coal it would still go chop trees, and with no trees hit no_resource;
            // measured to be where iron/gold ingot runs got stuck.)
            int coalLike = counts.getOrDefault(Items.COAL, 0) + counts.getOrDefault(Items.CHARCOAL, 0);
            int fuelDeficit = missing - coalLike * 8;
            int fuelLogs = 0;
            if (fuelDeficit > 0) {
                // +1 slack: the execution layer and the symbolic ledger naturally drift apart --
                // craft converts logs to planks in whole-log units, while smelt's chooseFuel greedily
                // loads a single fuel species at full amount; the two greedy computations together
                // commonly differ by 1-2 planks, and at the tail of the chain, "craft a stick" then
                // needs to re-gather planks; if all the trees are already chopped down by then, it
                // hits no_resource outright (measured in an iron_pickaxe test run). Chopping one extra
                // log absorbs this drift.
                // The extra surface log absorbs recipe/accounting drift during initial bootstrap.
                // Underground replans must consume the already-carried reserve exactly: adding one
                // per separated smelt stage made a viable eight-log kit request trees at Y=16.
                fuelLogs = Math.max(1, (int) Math.ceil(fuelDeficit / 1.5))
                        + (surfaceAcquisitionAllowed ? 1 : 0);
                int availableLogs = countItems(RecipeRegistry.LOGS);
                int missingLogs = Math.max(0, fuelLogs - availableLogs);
                if (missingLogs > 0) {
                    if (restrictSurfaceAcquisition) {
                        unresolved.add("underground_fuel_reserve_depleted:have="
                                + availableLogs + ":required=" + fuelLogs);
                        return false;
                    }
                    Item fuel = preferredFuelLog();
                    int desiredFuel = counts.getOrDefault(fuel, 0) + missingLogs;
                    if (!ensureItem(fuel, desiredFuel, depth + 1, visiting)) {
                        return false;
                    }
                }
            }
            consumeItem(recipe.input(), missing);
            if (fuelLogs > 0) {
                // SmeltTask can reload different vanilla log fuels. Reserve the same family here
                // instead of binding three separated underground smelts to whichever tree species
                // happened to appear first in RecipeRegistry.LOGS.
                consumeItems(RecipeRegistry.LOGS, fuelLogs);
            }
            counts.merge(recipe.output(), missing, Integer::sum);
            addStep(GoalStep.smelt(recipe.input(), recipe.output(), missing));
            return true;
        }

        /**
         * Treats an any-of ingredient as one inventory family. Each candidate first contributes
         * only what its currently carried, recipe-compatible inputs can produce (oak logs to oak
         * planks, birch logs to birch planks, and so on). If the aggregate is still short, exactly
         * one candidate plans the remaining family deficit instead of independently requesting the
         * full requirement again.
         */
        private boolean ensureIngredient(RecipeRegistry.Ingredient ingredient,
                                         int need,
                                         int depth,
                                         Set<String> visiting) {
            if (countItems(ingredient.anyOf()) >= need) {
                return true;
            }
            for (Item candidate : ingredient.anyOf()) {
                int remaining = need - countItems(ingredient.anyOf());
                if (remaining <= 0) {
                    return true;
                }
                int existing = Math.max(0, counts.getOrDefault(candidate, 0));
                int carriedCapacity = directCraftCapacity(candidate);
                int producible = Math.max(0, carriedCapacity - existing);
                if (producible <= 0) {
                    continue;
                }
                int contribution = Math.min(remaining, producible);
                if (!ensureItem(candidate, saturatedAdd(existing, contribution), depth,
                        visiting)) {
                    return false;
                }
            }
            int remaining = need - countItems(ingredient.anyOf());
            if (remaining <= 0) {
                return true;
            }
            Item candidate = chooseIngredient(ingredient, remaining);
            return candidate != null
                    && ensureItem(candidate, saturatedAdd(
                    counts.getOrDefault(candidate, 0), remaining), depth, visiting)
                    && countItems(ingredient.anyOf()) >= need;
        }

        private Item chooseIngredient(RecipeRegistry.Ingredient ingredient, int need) {
            Item bestFinished = null;
            int bestFinishedCount = -1;
            Item bestSufficient = null;
            int bestSufficientCapacity = -1;
            Item bestAvailable = null;
            int bestAvailableCapacity = -1;
            for (Item item : ingredient.anyOf()) {
                int finished = counts.getOrDefault(item, 0);
                int capacity = directCraftCapacity(item);
                if (finished >= need && finished > bestFinishedCount) {
                    bestFinished = item;
                    bestFinishedCount = finished;
                }
                if (capacity >= need && capacity > bestSufficientCapacity) {
                    bestSufficient = item;
                    bestSufficientCapacity = capacity;
                }
                if (capacity > bestAvailableCapacity) {
                    bestAvailable = item;
                    bestAvailableCapacity = capacity;
                }
            }
            // Spend a fully-carried alternative first. Otherwise choose the family whose matching
            // direct ingredients can actually satisfy the whole multi-craft requirement. This keeps
            // one stray oak plank from binding an underground stick reserve to an unavailable oak
            // log when the carried birch logs can make all required birch planks.
            if (bestFinished != null) {
                return bestFinished;
            }
            if (bestSufficient != null) {
                return bestSufficient;
            }
            if (bestAvailable != null && bestAvailableCapacity > 0) {
                return bestAvailable;
            }
            for (Item item : ingredient.anyOf()) {
                if (RecipeRegistry.find(item).isPresent()) {
                    return item;
                }
            }
            return ingredient.anyOf().isEmpty() ? null : ingredient.anyOf().get(0);
        }

        /** Existing output plus the amount craftable from the recipe's currently carried inputs. */
        private int directCraftCapacity(Item item) {
            int existing = Math.max(0, counts.getOrDefault(item, 0));
            Optional<RecipeRegistry.Recipe> candidateRecipe = RecipeRegistry.find(item);
            if (candidateRecipe.isEmpty() || candidateRecipe.get().ingredients().isEmpty()) {
                return existing;
            }
            int crafts = Integer.MAX_VALUE;
            for (RecipeRegistry.Ingredient input : candidateRecipe.get().ingredients()) {
                int perCraft = Math.max(1, input.count());
                crafts = Math.min(crafts, countItems(input.anyOf()) / perCraft);
            }
            if (crafts <= 0 || crafts == Integer.MAX_VALUE) {
                return existing;
            }
            long capacity = (long) existing + (long) crafts * candidateRecipe.get().outputCount();
            return capacity >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) capacity;
        }

        private void consume(RecipeRegistry.Ingredient ingredient, int count) {
            ingredient.consumeFrom(counts, count);
        }

        private void consumeItem(Item item, int count) {
            counts.put(item, Math.max(0, counts.getOrDefault(item, 0) - count));
        }

        private int countItems(Iterable<Item> items) {
            int total = 0;
            for (Item item : items) {
                total += counts.getOrDefault(item, 0);
            }
            return total;
        }

        private void consumeItems(Iterable<Item> items, int count) {
            int remaining = Math.max(0, count);
            for (Item item : items) {
                if (remaining <= 0) {
                    return;
                }
                int available = counts.getOrDefault(item, 0);
                int take = Math.min(available, remaining);
                if (take > 0) {
                    counts.put(item, available - take);
                    remaining -= take;
                }
            }
        }

        // GOALFIX-GF3: choosing smelting fuel -- prefer any log species already in inventory (spruce/birch/...), defaulting to oak if none is present.
        private Item preferredFuelLog() {
            for (Item log : RecipeRegistry.LOGS) {
                if (counts.getOrDefault(log, 0) > 0) {
                    return log;
                }
            }
            return Items.OAK_LOG;
        }

        private int countAny(Set<Item> items) {
            int count = 0;
            for (Item item : items) {
                count += counts.getOrDefault(item, 0);
            }
            return count;
        }

        private int bestPickaxeTier() {
            int best = ToolTier.NONE;
            best = Math.max(best, tierIfPresent(Items.WOODEN_PICKAXE, ToolTier.WOOD));
            best = Math.max(best, tierIfPresent(Items.GOLDEN_PICKAXE, ToolTier.WOOD));
            best = Math.max(best, tierIfPresent(Items.STONE_PICKAXE, ToolTier.STONE));
            best = Math.max(best, tierIfPresent(Items.IRON_PICKAXE, ToolTier.IRON));
            best = Math.max(best, tierIfPresent(Items.DIAMOND_PICKAXE, ToolTier.DIAMOND));
            best = Math.max(best, tierIfPresent(Items.NETHERITE_PICKAXE, ToolTier.NETHERITE));
            return best;
        }

        private int tierIfPresent(Item item, int tier) {
            return counts.getOrDefault(item, 0) > 0 ? tier : ToolTier.NONE;
        }

        private void addStep(GoalStep step) {
            if (bestEffortDepth > 0) {
                step = step.asBestEffort();
            }
            if (!steps.isEmpty()) {
                GoalStep previous = steps.get(steps.size() - 1);
                if (previous.sameTarget(step)) {
                    steps.set(steps.size() - 1, previous.withCount(previous.count() + step.count()));
                    return;
                }
            }
            steps.add(step);
        }

        private static Item pickaxeForTier(int tier) {
            if (tier >= ToolTier.NETHERITE) {
                return Items.NETHERITE_PICKAXE;
            }
            if (tier >= ToolTier.DIAMOND) {
                return Items.DIAMOND_PICKAXE;
            }
            if (tier >= ToolTier.IRON) {
                return Items.IRON_PICKAXE;
            }
            if (tier >= ToolTier.STONE) {
                return Items.STONE_PICKAXE;
            }
            if (tier >= ToolTier.WOOD) {
                return Items.WOODEN_PICKAXE;
            }
            return Items.AIR;
        }

        // P2: an ore drop item -> its corresponding ore block. Covers all common ores (deepslate variants are handled by OreDigTask/expandOreFamilies).
        private static Block oreBlockFor(Item item) {
            if (item == Items.RAW_IRON) {
                return Blocks.IRON_ORE;
            }
            if (item == Items.RAW_COPPER) {
                return Blocks.COPPER_ORE;
            }
            if (item == Items.RAW_GOLD) {
                return Blocks.GOLD_ORE;
            }
            if (item == Items.COAL) {
                return Blocks.COAL_ORE;
            }
            if (item == Items.REDSTONE) {
                return Blocks.REDSTONE_ORE;
            }
            if (item == Items.LAPIS_LAZULI) {
                return Blocks.LAPIS_ORE;
            }
            if (item == Items.DIAMOND) {
                return Blocks.DIAMOND_ORE;
            }
            if (item == Items.EMERALD) {
                return Blocks.EMERALD_ORE;
            }
            return null;
        }

        private static SmeltRecipe smeltRecipeFor(Item output) {
            // S5: the smelting mapping converges on SmeltChain as a single source (ore->ingot, stone, charcoal + cooked meat, glass, baked potato).
            Item raw = SmeltChain.rawFor(output);
            return raw == null ? null : new SmeltRecipe(raw, output);
        }

        // S4: crop produce -> crop spec (used for FARM routing); returns null for an unsupported crop.
        private static FarmAction.CropSpec cropSpecForProduce(Item produce) {
            if (produce == Items.WHEAT) {
                return FarmAction.cropSpec("wheat");
            }
            if (produce == Items.CARROT) {
                return FarmAction.cropSpec("carrot");
            }
            if (produce == Items.POTATO) {
                return FarmAction.cropSpec("potato");
            }
            return null;
        }

        private static int divideRoundUp(int value, int divisor) {
            return (value + divisor - 1) / divisor;
        }

        private static int divideRoundUpSafe(int value, int divisor) {
            if (value <= 0) {
                return 0;
            }
            if (divisor <= 0) {
                throw new IllegalArgumentException("invalid_divisor:" + divisor);
            }
            return (int) Math.min(Integer.MAX_VALUE,
                    ((long) value + divisor - 1L) / divisor);
        }

        private static String id(Item item) {
            return BuiltInRegistries.ITEM.getKey(item).toString();
        }
    }

    private record SmeltRecipe(Item input, Item output) {
    }
}

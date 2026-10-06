package io.github.zoyluo.minecraftai.brain;

import com.google.gson.JsonObject;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.EquipAction;
import io.github.zoyluo.minecraftai.action.FarmAction;
import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.action.InteractAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.LookAction;
import io.github.zoyluo.minecraftai.action.MiningAction;
import io.github.zoyluo.minecraftai.action.MovementAction;
import io.github.zoyluo.minecraftai.action.StrikeLegality;
import io.github.zoyluo.minecraftai.action.ToolSelector;
import io.github.zoyluo.minecraftai.coordination.Job;
import io.github.zoyluo.minecraftai.coordination.TaskBoard;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationGate;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationPolicy;
import io.github.zoyluo.minecraftai.craft.AcquisitionHints;
import io.github.zoyluo.minecraftai.craft.CraftingHelper;
import io.github.zoyluo.minecraftai.goal.Goal;
import io.github.zoyluo.minecraftai.goal.GoalExecutor;
import io.github.zoyluo.minecraftai.goal.GoalSnapshotCollector;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.memory.BotMemory;
import io.github.zoyluo.minecraftai.memory.BotMemoryStore;
import io.github.zoyluo.minecraftai.mining.OreScan;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.runtime.IntentController;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import io.github.zoyluo.minecraftai.runtime.IntentControlTransaction;
import io.github.zoyluo.minecraftai.task.AttackEntityTask;
import io.github.zoyluo.minecraftai.task.BlueprintLoader;
import io.github.zoyluo.minecraftai.task.BoardBoatTask;
import io.github.zoyluo.minecraftai.task.BoatFollowTask;
import io.github.zoyluo.minecraftai.task.BoatLaunchTask;
import io.github.zoyluo.minecraftai.task.BreedTask;
import io.github.zoyluo.minecraftai.task.BuildTask;
import io.github.zoyluo.minecraftai.task.CombatTask;
import io.github.zoyluo.minecraftai.task.ContainerTask;
import io.github.zoyluo.minecraftai.task.CraftTask;
import io.github.zoyluo.minecraftai.task.DismountBoatTask;
import io.github.zoyluo.minecraftai.task.DiscoveryTask;
import io.github.zoyluo.minecraftai.task.EatTask;
import io.github.zoyluo.minecraftai.task.FishTask;
import io.github.zoyluo.minecraftai.task.FarmTask;
import io.github.zoyluo.minecraftai.task.GatherQuotaTask;
import io.github.zoyluo.minecraftai.task.GatherThenGiveTask;
import io.github.zoyluo.minecraftai.task.FollowTask;
import io.github.zoyluo.minecraftai.task.GiveItemTask;
import io.github.zoyluo.minecraftai.task.GuardTask;
import io.github.zoyluo.minecraftai.task.HoldTask;
import io.github.zoyluo.minecraftai.task.LightAreaTask;
import io.github.zoyluo.minecraftai.task.MineTask;
import io.github.zoyluo.minecraftai.task.MineValuablesTask;
import io.github.zoyluo.minecraftai.task.MoveTask;
import io.github.zoyluo.minecraftai.task.SmeltTask;
import io.github.zoyluo.minecraftai.task.ShowTargetTask;
import io.github.zoyluo.minecraftai.task.StockpileTask;
import io.github.zoyluo.minecraftai.task.OreDigTask;
import io.github.zoyluo.minecraftai.task.Task;
import io.github.zoyluo.minecraftai.task.TaskManager;
import io.github.zoyluo.minecraftai.task.TaskStatus;
import io.github.zoyluo.minecraftai.task.BossRefusal;
import io.github.zoyluo.minecraftai.task.TradeTask;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

public final class ToolRegistry {
    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    public ToolRegistry() {
        registerDefaults();
    }

    public Optional<ToolDefinition> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<ToolDefinition> tools(MinecraftAiConfig.Brain config) {
        return tools(config, config.exposesLowLevelTools());
    }

    public List<ToolDefinition> tools(MinecraftAiConfig.Brain config, boolean exposeLowLevelTools) {
        return tools(config, exposeLowLevelTools, config.memoryToolsEnabled(), config.coordinationToolsEnabled());
    }

    public List<ToolDefinition> tools(MinecraftAiConfig.Brain config,
                                      boolean exposeLowLevelTools,
                                      boolean memoryToolsEnabled,
                                      boolean coordinationToolsEnabled) {
        return tools.values().stream()
                .filter(tool -> switch (tool.group()) {
                    case CORE -> true;
                    case MEMORY -> memoryToolsEnabled;
                    case COORDINATION -> coordinationToolsEnabled;
                    case LOW_LEVEL -> exposeLowLevelTools;
                })
                .toList();
    }

    private void registerDefaults() {
        registerMovementAndCraftingTools();
        registerGoalTools();
        registerContainerAndCombatTools();
        registerControlTools();
        registerCoordinationTools();
        registerMemoryAndGoalManagementTools();
        registerTaskLifecycleTools();
    }

    /** Movement and low-level actions plus crafting: say, look/move/mine/place, hotbar, inventory, tool equip, craft/eat/smelt. */
    private void registerMovementAndCraftingTools() {
        register("say", "Reply to the human in concise English. The reply is shown in ordinary Minecraft chat and in the MinecraftAi panel. purpose=answer is only for a question that needs no in-world work; purpose=plan must precede an applicable action or goal tool, in the same response when the provider supports multiple calls or in the runtime-forced next call when it does not; purpose=status is for progress or completion after work has started. For a requested specialized mechanic that has no supplied tool, use report_unsupported instead of promising it in say.", objectSchema()
                .property("message", stringSchema("the text to say"))
                .property("purpose", enumStringSchema("answer for a pure question, plan before starting work, or status after work", "answer", "plan", "status"))
                .required("message")
                .required("purpose")
                .build(), (bot, args) -> {
            String message = requiredString(args, "message");
            String purpose = requiredString(args, "purpose");
            if (!"answer".equals(purpose) && !"plan".equals(purpose) && !"status".equals(purpose)) {
                throw new IllegalArgumentException("bad_say_purpose: " + purpose);
            }
            BrainCoordinator.INSTANCE.sendBotReply(bot, message);
            return ok("said");
        });

        register("report_unsupported", "End a request truthfully when its requested specialized Minecraft mechanic has no supplied tool. Use only after checking the declared tools. Give the missing building block (for example controlled elytra flight with firework rockets); this tells the player what needs development. Do NOT use it for missing materials, a temporary route/visibility issue, or a normal gather/craft/mine/build task: those must use the applicable high-level tool.", objectSchema()
                .property("capability", stringSchema("specific missing building block, not a promise or a request for player help"))
                .required("capability")
                .build(), (bot, args) -> {
            String capability = requiredString(args, "capability")
                    .replace('\r', ' ').replace('\n', ' ').trim();
            if (capability.length() < 3 || capability.length() > 160) {
                throw new IllegalArgumentException("unsupported_capability_description_must_be_3_to_160_characters");
            }
            BrainCoordinator.INSTANCE.sendBotReply(bot, "I can't do that yet: I don't have a tool for "
                    + capability + ". I did not start the action; that building block needs to be added.");
            return ok("unsupported_capability_reported");
        });

        register("look_at", "Turn the bot's head toward a coordinate", xyzSchema(), ToolDefinition.Group.LOW_LEVEL, (bot, args) -> {
            LookAction.lookAt(bot, new Vec3(requiredInt(args, "x"), requiredInt(args, "y"), requiredInt(args, "z")));
            return ok("looked");
        });

        register("move_to", "Navigate to a coordinate through Baritone. If the full path request cannot be admitted, retries the same observed goal through Baritone's walk-goal entry point.", xyzSchema(), ToolDefinition.Group.LOW_LEVEL, (bot, args) -> {
            BlockPos goal = blockPos(args);
            io.github.zoyluo.minecraftai.action.ActionResult pathResult = MovementAction.startPathTo(bot, goal);
            if (pathResult.isInProgress() || pathResult.isSuccess()) {
                return ok("baritone_path_started");
            }
            io.github.zoyluo.minecraftai.action.ActionResult walkGoal = MovementAction.startWalkTo(bot, Vec3.atCenterOf(goal));
            if (walkGoal.isInProgress() || walkGoal.isSuccess()) {
                return ok("baritone_walk_goal_started: " + pathResult.reason());
            }
            return fail("baritone_navigation_failed: " + pathResult.reason());
        });

        register("mine_block", "Low-level single-block break at given coords. The block must be visible and within reach; hidden coordinates are refused. For gathering materials or mining counts, prefer assign_task with task_type mine.", xyzSchema(), ToolDefinition.Group.LOW_LEVEL, (bot, args) -> {
            BlockPos pos = blockPos(args);
            return result(MiningAction.startMining(bot, pos,
                    Direction.getApproximateNearest(bot.getEyePosition().subtract(pos.getCenter()))));
        });

        register("place_block", "Low-level manual placement of the currently held block at given coords. For crafting table placement during recipes, prefer craft because it can place a held crafting table automatically.", xyzSchema(), ToolDefinition.Group.LOW_LEVEL, (bot, args) -> {
            return result(BuildAction.placeBlockAt(bot, blockPos(args)));
        });

        register("select_hotbar", "Select hotbar slot 0..8", objectSchema()
                .property("slot", integerSchema("hotbar slot", 0, 8))
                .required("slot")
                .build(), ToolDefinition.Group.LOW_LEVEL, (bot, args) -> result(InventoryAction.selectHotbar(bot, requiredInt(args, "slot"))));

        register("inventory", "Get the bot's current inventory", objectSchema().build(), (bot, args) ->
                ok(InventoryAction.summarize(bot).toString()));

        register("equip_best_tool", "Equip the least valuable tool that can properly mine a block type and keep its drops (tools are worst-first and used until they break: a wooden pickaxe before a stone one for stone, then the next worst takes over)", objectSchema()
                .property("block", stringSchema("block id, for example minecraft:stone"))
                .required("block")
                .build(), (bot, args) -> {
            Block block = requiredBlock(args, "block");
            ToolSelector.Selection selection = ToolSelector.equipBestTool(bot, block.defaultBlockState());
            return ok(selection.describe());
        });

        register("lookup_recipe", "Read-only fact check against the running game (Minecraft 1.21.11): does this item or enchantment exist, and how is the item crafted (ingredients, crafting table or 2x2, or smelting). Use it BEFORE claiming an item, tool, recipe or enchantment does not exist or cannot be made -- your memory of the game is out of date. Accepts a full id (minecraft:copper_pickaxe) or a plain name (copper pickaxe, lunge); if nothing matches it lists similar ids.", objectSchema()
                .property("name", stringSchema("item or enchantment id or plain name, for example minecraft:copper_pickaxe or lunge"))
                .required("name")
                .build(), (bot, args) -> ok(io.github.zoyluo.minecraftai.craft.ItemLookup.describe(
                        requiredString(args, "name"), bot.level().registryAccess())));

        register("plan_craft", "Read-only preflight for crafting. Returns feasible, deterministic craft steps, missing materials, and each missing material's acquisition source.", objectSchema()
                .property("item", stringSchema("target item id, for example minecraft:stone_pickaxe"))
                .property("count", integerSchema("desired count"))
                .required("item")
                .build(), (bot, args) -> ok(craftPlanJson(CraftingHelper.plan(bot, requiredItem(args, "item"), optionalInt(args, "count", 1)))));

        register("craft", "Craft an item using known survival recipes. It resolves planks and sticks recursively, so prefer crafting the target tool/item directly instead of crafting planks or sticks as separate steps. It does not gather, smelt, or open GUIs. For 3x3 recipes, craft minecraft:crafting_table first; after that this task can use a nearby table or place a held table automatically. Do not use select_hotbar/place_block for the crafting table unless the human asks. Fails with need: <item> xN when base materials are missing.", objectSchema()
                .property("item", stringSchema("item id, for example minecraft:stone_pickaxe"))
                .property("count", integerSchema("desired count"))
                .required("item")
                .build(), (bot, args) -> {
            Task task = new CraftTask(requiredItem(args, "item"), optionalInt(args, "count", 1));
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("eat", "Eat available food from inventory", objectSchema().build(), (bot, args) -> {
            Task task = new EatTask();
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("smelt", "Smelt input items in a nearby or held furnace using available fuel. It does not craft a furnace; call craft first if needed.", objectSchema()
                .property("input_item", stringSchema("input item id, for example minecraft:raw_iron"))
                .property("output_item", stringSchema("expected output item id, for example minecraft:iron_ingot"))
                .property("count", integerSchema("output count"))
                .required("input_item")
                .required("output_item")
                .build(), (bot, args) -> {
            Task task = new SmeltTask(
                    requiredItem(args, "input_item"),
                    requiredItem(args, "output_item"),
                    optionalInt(args, "count", 1));
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("gather", "Gather a specific resource. When count is supplied, it means that many NEW/additional items: collect 3 logs means collect 3 more even when copies are already carried. Existing copies in inventory never satisfy count. A count always means NEW/additional inventory items. When count is omitted, collect as much newly gathered material as possible for up to ten minutes; actively explore through short safe observed hops and rescan newly revealed terrain rather than stopping at the first view. It reports none found only after that window. It loops survey, move, harvest, and pickup without assigning child tasks. It uses the right tool for the block, crafts one from inventory if needed, and otherwise reports missing_tool. For a request to break a precise number of blocks where drops do not matter, use break_blocks; for grass/tall grass use clear_grass.", objectSchema()
                .property("item", stringSchema("target item id, for example minecraft:cobblestone"))
                .property("count", integerSchema("number of new items to collect; omit to collect for up to ten minutes"))
                .required("item")
                .build(), (bot, args) -> {
            Item item = requiredItem(args, "item");
            Task task = args.has("count")
                    ? GatherQuotaTask.collectAdditional(item, optionalInt(args, "count", 1))
                    : GatherQuotaTask.collectForDuration(item);
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("gather_then_give", "Collect a NEW quota of one gatherable resource, then hand that exact count to a nearby player. Existing inventory never satisfies the collection phase. For generic tree logs, pass item=logs so the task uses a species it actually gathers (a mix of species is delivered one drop per species); use an exact minecraft:<species>_log id only when that species was named. Use this for one-resource requests that explicitly combine gathering/collecting with giving/handing the result to a player. It collects only what the bot can gather with the tools it carries: for ore or stone that needs a pickaxe use fulfill_items with the player as recipient. It hands over the raw resource itself, so for a crafted result (gather logs, craft a table, give it) call fulfill_items with the raw resource as its own allocation without a recipient next to the crafted item for the player, or gather first and craft/give afterwards. It also hands over everything it collects, so for only part of it (gather 32, give 16) gather first and give_item the part. When the player stated no number, omit count: it then collects for up to ten minutes and hands over everything it collected. Use give_item only for an existing-inventory handoff.", objectSchema()
                .property("item", stringSchema("exact item id, or the literal logs for any tree-log species"))
                .property("count", integerSchema("positive number of new items to collect before the handoff, exactly as the player stated it; omit when the player stated none", 1, Integer.MAX_VALUE))
                .property("player", stringSchema("optional recipient player name; defaults to owner"))
                .required("item")
                .build(), (bot, args) -> {
            String requestedItem = requiredString(args, "item");
            String player = optionalString(args, "player", "");
            boolean counted = args.has("count");
            Task task;
            if (isGenericLogHandoff(requestedItem)) {
                task = counted ? GatherThenGiveTask.genericLogs(requiredPositiveInt(args, "count"), player)
                        : GatherThenGiveTask.timedLogs(player);
            } else {
                task = counted ? new GatherThenGiveTask(requiredItem(args, "item"), requiredPositiveInt(args, "count"), player)
                        : GatherThenGiveTask.timed(requiredItem(args, "item"), player);
            }
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("clear_grass", "Break exactly the requested number of nearby grass plants. This counts short grass, tall grass, ferns, and large ferns that are actually broken; it does not count wheat seeds in inventory.", objectSchema()
                .property("count", integerSchema("number of grass plants to break"))
                .required("count")
                .build(), (bot, args) -> {
            Task task = GatherQuotaTask.clearGrass(requiredPositiveInt(args, "count"));
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("break_blocks", "Break exactly the requested number of nearby matching blocks. Progress counts blocks actually broken, not inventory drops. Use this for explicit requests such as remove 3 minecraft:oak_log; use gather when the player wants items in inventory. For leaves ('break 32 leaves', 'clear the leaves') pass block=leaves: it counts leaf blocks of ANY tree type, needs no particular tool (shears or a hoe are used if carried, otherwise bare hands) and never crafts shears. It stays nearby and will not roam or tunnel to find blocks. It uses the right tool for the block, crafts one from inventory if needed, and otherwise reports missing_tool.", objectSchema()
                .property("block", stringSchema("exact block id, for example minecraft:oak_log; or the word leaves for leaf blocks of any tree type"))
                .property("count", integerSchema("positive number of matching blocks to break"))
                .required("block")
                .required("count")
                .build(), (bot, args) -> {
            Task task = isAnyLeavesRequest(args, "block")
                    ? GatherQuotaTask.breakLeaves(requiredPositiveInt(args, "count"))
                    : GatherQuotaTask.breakBlocks(
                            requiredBreakableBlock(bot, args, "block"), requiredPositiveInt(args, "count"));
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("fish", "Fish at nearby water with a fishing rod. Casts, waits for bite, reels, collects loot, and loops until max_catches or max_ticks.", objectSchema()
                .property("max_catches", integerSchema("number of successful catches"))
                .property("max_ticks", integerSchema("maximum task duration in ticks"))
                .build(), (bot, args) -> {
            Task task = new FishTask(optionalInt(args, "max_catches", 1), optionalInt(args, "max_ticks", 6000));
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("trade", "Trade directly with a nearby villager without opening a merchant screen. Supports simple one-input offers, optionally targeting a sell item.", objectSchema()
                .property("target_item", stringSchema("optional item id to buy, for example minecraft:bread"))
                .property("max_distance", integerSchema("search radius"))
                .build(), (bot, args) -> {
            Task task = new TradeTask(optionalItem(args, "target_item"), optionalInt(args, "max_distance", 16));
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });
    }

    /** Goal-driven high-level actions: gather/break/fish/trade plus every deterministic-goal task. */
    private void registerGoalTools() {
        register("fulfill_items", "Newly produce and fulfill an arbitrary compound item request with deterministic dependency planning and exact player handoffs. Use when the player asks for multiple different final items, a kit, a bundle, a split between players, or a new quantity of a raw resource that needs a tool you may lack (stone, ore). Put every final allocation in items; recipient omitted means keep it on this bot, while a named recipient is handed that newly produced item after all production is complete. Everything handed to a player and every raw resource kept is additional to matching inventory held when this call starts; it cannot hand over a pre-existing matching stack. A crafted item kept on the bot (your own tool or armor) is satisfied by one already carried. When the request also collects NEW raw resources beside a crafted result (gather 32 logs, craft a table, give it to me), call this once: the raw resource is its own allocation without a recipient (it stays with you and is always collected above what you carry) and the crafted item is an allocation with the player as recipient; leaving the raw resource out lets carried stock stand in for it. It is for production only: to hand over what the bot already carries, call give_item once per item. The model chooses the manifest from the player's request; this tool does not assume a fixed kit or recipe.", objectSchema()
                .property("items", allocationArraySchema("complete final item allocations for this request"))
                .required("items")
                .build(), (bot, args) -> {
            List<Goal.Allocation> allocations = requiredAllocations(args, "items");
            boolean started = GoalExecutor.INSTANCE.submitAdaptive(bot, freshFulfillGoal(bot, allocations));
            return started ? ok("goal_assigned: fulfill_items allocations=" + allocations.size())
                    : fail("goal_plan_failed");
        });

        register("continue_goal_step", "Authorize exactly the next server-generated safe stage of an adaptive goal. This is only valid at a mission strategy checkpoint. Pass the mission_id and revision supplied in that checkpoint; it cannot target arbitrary coordinates, recipes, or hidden resources.", objectSchema()
                .property("mission_id", stringSchema("exact mission id from the strategy checkpoint"))
                .property("revision", integerSchema("exact non-negative strategy revision from the checkpoint", 0, Integer.MAX_VALUE))
                .required("mission_id")
                .required("revision")
                .build(), (bot, args) -> {
            boolean continued = GoalExecutor.INSTANCE.continueStrategyCheckpoint(
                    bot, requiredString(args, "mission_id"), requiredInt(args, "revision"));
            return continued ? ok("goal_stage_authorized") : fail("stale_or_invalid_goal_strategy_checkpoint");
        });

        register("replan_goal_from_current_state", "At an adaptive mission checkpoint, rebuild only the remaining server-verified dependency plan from the latest observed state. Use this when the proposed next stage no longer fits current facts. Pass the exact mission_id and revision from the checkpoint; this never discards an in-flight physical transaction.", objectSchema()
                .property("mission_id", stringSchema("exact mission id from the strategy checkpoint"))
                .property("revision", integerSchema("exact non-negative strategy revision from the checkpoint", 0, Integer.MAX_VALUE))
                .required("mission_id")
                .required("revision")
                .build(), (bot, args) -> {
            boolean replanned = GoalExecutor.INSTANCE.replanStrategyCheckpoint(
                    bot, requiredString(args, "mission_id"), requiredInt(args, "revision"));
            return replanned ? ok("goal_plan_rebuilt_from_current_state")
                    : fail("stale_or_unreplannable_goal_strategy_checkpoint");
        });

        register("stop_goal_mission", "Cancel exactly the adaptive root goal at the supplied safe strategy checkpoint. This is revision-bound, so a delayed model response cannot stop a newer mission. It stops only this root goal; separately queued player goals remain eligible.", objectSchema()
                .property("mission_id", stringSchema("exact mission id from the strategy checkpoint"))
                .property("revision", integerSchema("exact non-negative strategy revision from the checkpoint", 0, Integer.MAX_VALUE))
                .required("mission_id")
                .required("revision")
                .build(), (bot, args) -> {
            boolean stopped = GoalExecutor.INSTANCE.stopStrategyCheckpoint(
                    bot, requiredString(args, "mission_id"), requiredInt(args, "revision"));
            return stopped ? ok("adaptive_goal_stopped") : fail("stale_or_invalid_goal_strategy_checkpoint");
        });

        register("set_base", "Remember the bot's current position as the base for stockpiling and resupply tasks.", objectSchema().build(), ToolDefinition.Group.MEMORY, (bot, args) -> {
            BotMemoryStore.INSTANCE.of(bot.getUUID()).markPlace("base", bot.level(), bot.blockPosition());
            return ok("marked_base: " + bot.blockPosition().toShortString());
        });

        register("deposit_all", "Deposit carried items into containers near the remembered base. Same items prefer containers that already contain them; all_except_tools defaults true.", objectSchema()
                .property("all_except_tools", booleanSchema("deposit all non-damageable items and keep tools/equipment"))
                .build(), (bot, args) -> {
            Task task = new StockpileTask(optionalBoolean(args, "all_except_tools", true));
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("mine_ore", "PREFERRED way to obtain ores (e.g. minecraft:iron_ore or raw item minecraft:raw_iron). Starts a deterministic goal plan: prepare the required pickaxe first, then mine only observed ore. For non-ore inventory items such as minecraft:obsidian, use achieve_goal instead. Do not manually break this into gather/craft/mine steps. Two modes: mode=count (default) with count mines at least that many NEW ore drops, even when the bot already carries that material; without count it collects as many NEW drops as it can for up to ten minutes. In both cases it always drains the connected observed vein it opens, confirms each physical drop, actively follows repeated short safe observed walk-only hops when the local view is empty, rescans newly revealed terrain, and resumes if ore becomes visible. It never tunnels, strip-mines, or paths toward unseen ore; it reports none found only when the no-count window ends. mode=vein is for stopping after one specific 'whole/entire vein', 'this vein', or 'until the vein is gone': it mines exactly the connected, observed vein of the nearest ore of that type the bot can see (or of the ore at x/y/z), then STOPS and reports how many were mined -- no branch mining, no digging down, no other veins. count is ignored in vein mode. Vein mode needs a suitable pickaxe already in the inventory and a visible ore.", objectSchema()
                .property("ore", stringSchema("ore block id or raw item, e.g. minecraft:iron_ore or minecraft:raw_iron"))
                .property("count", integerSchema("how many new ore drops to mine (mode=count only); omit to collect for up to ten minutes"))
                .property("mode", enumStringSchema("count (default): with count, finish connected observed veins until `count` new drops are collected; without count, collect for up to ten minutes. Both safely widen the view through repeated bounded observed hops and rescans. vein: mine one connected visible vein and stop when it is exhausted", MINE_ORE_MODES.toArray(new String[0])))
                .property("x", integerSchema("vein mode only: x of an ore in the vein the player means (optional; default nearest visible ore)"))
                .property("y", integerSchema("vein mode only: y of an ore in the vein the player means (give x, y and z together)"))
                .property("z", integerSchema("vein mode only: z of an ore in the vein the player means"))
                .required("ore")
                .build(), (bot, args) -> {
            String mineOreMode = optionalString(args, "mode", "count").trim().toLowerCase(java.util.Locale.ROOT);
            if (!MINE_ORE_MODES.contains(mineOreMode)) {
                return fail("invalid_mode: mine_ore mode must be one of " + String.join(", ", MINE_ORE_MODES)
                        + " (got '" + optionalString(args, "mode", "count") + "'); no ore was mined");
            }
            if ("vein".equals(mineOreMode)) {
                Set<Block> veinOres = oreTargetsFrom(requiredString(args, "ore"));
                if (veinOres.stream().noneMatch(ore -> io.github.zoyluo.minecraftai.mining.ToolTier
                        .canHarvestWithInventory(bot, ore.defaultBlockState()))) {
                    return fail("need_better_tool: vein mode needs "
                            + io.github.zoyluo.minecraftai.mining.ToolTier.requiredPickaxeItemId(veinOres)
                            + " in the inventory; use achieve_goal for that pickaxe first, then retry mine_ore mode=vein");
                }
                BlockPos veinHint = hasBlockPos(args, "x", "y", "z") ? blockPos(args) : null;
                Task task = OreDigTask.untilVeinExhausted(veinOres, veinHint);
                assignLlm(bot, task);
                return ok("assigned: mine_ore vein (stops when the vein is exhausted)");
            }
            Set<Block> ores = oreTargetsFrom(requiredString(args, "ore"));
            boolean hasCount = args.has("count");
            int count = optionalInt(args, "count", 1);
            if (!MinecraftAiConfig.get().goal().autoToolFillEnabled()) {
                Task task = hasCount ? new OreDigTask(ores, count) : OreDigTask.collectForDuration(ores);
                assignLlm(bot, task);
                return ok("assigned: " + task.name());
            }
            boolean started = GoalExecutor.INSTANCE.submitAdaptive(bot,
                    hasCount ? additionalOreGoal(bot, ores, count) : timedOreGoal(bot, ores));
            return started ? ok("goal_assigned: mine_ore") : fail("goal_plan_failed");
        });

        register("mine_valuables_in_radius", "Mine every valuable block (any common ore, ancient debris, nether gold ore, gilded blackstone, raw metal blocks, amethyst clusters) that is actually visible from the bot's current position right now, within radius blocks. Takes exactly one honest snapshot of what is visible at the moment this is called and only ever works that fixed list -- it never chases anything that only becomes visible later while walking. Pillars/bridges to reach a target when it is carrying a placeable block, and automatically lights the darkest safely reachable underground cell when native hostile-spawn light is too low. Use this for 'mine everything valuable nearby' style requests; for one specific ore type prefer mine_ore instead.", objectSchema()
                .property("radius", integerSchema("scan radius in blocks, default " + MineValuablesTask.DEFAULT_RADIUS + ", max " + MineValuablesTask.MAX_RADIUS, 1, MineValuablesTask.MAX_RADIUS))
                .build(), (bot, args) -> {
            Task task = new MineValuablesTask(optionalInt(args, "radius", MineValuablesTask.DEFAULT_RADIUS));
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("achieve_goal", "Achieve an item/tool inventory goal with deterministic planning. Use this for requests like make an iron pickaxe or obtain 10 iron ingots; do not manually decompose the steps. The goal is met by a matching stack the bot already carries, so it is not offered for a request to newly gather/mine/chop raw resources: use gather, mine_ore, harvest_crop or fulfill_items there.", objectSchema()
                .property("item", stringSchema("target item/tool id, for example minecraft:iron_pickaxe or minecraft:iron_ingot"))
                .property("count", integerSchema("desired inventory count"))
                .required("item")
                .build(), (bot, args) -> {
            boolean started = GoalExecutor.INSTANCE.submitAdaptive(bot,
                    new Goal.HaveItem(requiredItem(args, "item"), optionalInt(args, "count", 1)));
            return started ? ok("goal_assigned: achieve_goal") : fail("goal_plan_failed");
        });

        register("harvest_crop", "Grow and harvest a crop with deterministic planning. When count is supplied, it means that many NEW produce items; existing wheat, carrots, or potatoes do not satisfy it. When count is omitted, tend, explore, and harvest as much new produce as possible for up to ten minutes, reporting none found only after that window. Use for requests like plant wheat/collect some wheat/get wheat. Crop is wheat, carrot, or potato. The system auto-prepares a hoe, tills, plants, waits for growth, and harvests; do not decompose manually.", objectSchema()
                .property("crop", stringSchema("crop: wheat, carrot, or potato"))
                .property("count", integerSchema("how many new produce items to harvest; omit to collect for up to ten minutes"))
                .required("crop")
                .build(), (bot, args) -> {
            FarmAction.CropSpec spec = FarmAction.cropSpec(requiredString(args, "crop"));
            net.minecraft.world.item.Item produce = spec.crop() == net.minecraft.world.level.block.Blocks.WHEAT
                    ? net.minecraft.world.item.Items.WHEAT
                    : spec.seed(); // carrot/potato: the produce item is the same as the seed item
            int heldProduce = HarvestCore.countInventoryItems(bot, Set.of(produce));
            boolean started = GoalExecutor.INSTANCE.submitAdaptive(bot,
                    args.has("count")
                            ? new Goal.HarvestCrop(spec.crop(), spec.seed(), produce,
                                    optionalInt(args, "count", 1), heldProduce)
                            : Goal.HarvestCrop.timedCollection(spec.crop(), spec.seed(), produce, heldProduce));
            return started ? ok("goal_assigned: harvest_crop") : fail("goal_plan_failed");
        });

        register("provision_food", "Stock food end-to-end; AUTO-PICKS hunting or farming by scanning what's actually around (perception-driven). "
                + "This is the DEFAULT for ANY general 'get food' request: find food/find some food/go find food/go find some food already/go find food/find something to eat/find something to eat/go get something to eat/get something to eat/get some meat/hunt some meat to eat/go hunting/I'm hungry/hungry/stock up on food/get some food/restock food/get some food/go find food/make food/go hunt. "
                + "Auto-plans (hunt->cook meat OR farm->bread) based on surroundings; do NOT decompose manually. count = how many food items (default 4).", objectSchema()
                .property("count", integerSchema("how many cooked food items to stock (default 4)"))
                .build(), (bot, args) -> {
            boolean started = GoalExecutor.INSTANCE.submitAdaptive(bot,
                    new Goal.Food(optionalInt(args, "count", 4)));
            return started ? ok("goal_assigned: provision_food") : fail("goal_plan_failed");
        });

        register("forage", "Forage SPECIFIC wild berries/melon nearby. When count is supplied, it means that many NEW items and existing copies never satisfy it. When count is omitted, collect as much as possible for up to ten minutes by safely exploring and rescanning newly revealed terrain; report none found only after the window. ONLY when the user EXPLICITLY asks for berries/wild fruit, NOT for general food. "
                + "Use for pick some wild fruit/pick some berries/pick berries/pick sweet berries/pick watermelon/want to eat berries; needs berry bushes or melons around. "
                + "For ANY general find food/get some food request use provision_food instead (it auto-picks hunt or farm).", objectSchema()
                .property("count", integerSchema("how many new wild food items to gather; omit to collect for up to ten minutes"))
                .build(), (bot, args) -> {
            Task task = args.has("count")
                    ? GatherQuotaTask.collectAdditional(net.minecraft.world.item.Items.SWEET_BERRIES,
                            optionalInt(args, "count", 1))
                    : GatherQuotaTask.collectForDuration(net.minecraft.world.item.Items.SWEET_BERRIES);
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("achieve_armor", "Make and equip a full set of iron armor plus an iron sword with deterministic planning. Use for arm yourself up/make a full set of gear/put armor on me/gear up. Auto-plans mining, smelting and crafting; do not decompose manually.", objectSchema()
                .build(), (bot, args) -> {
            boolean started = GoalExecutor.INSTANCE.submitAdaptive(bot, new Goal.Armor());
            return started ? ok("goal_assigned: achieve_armor") : fail("goal_plan_failed");
        });

        register("achieve_workstation", "Set up a base: craft and place a crafting table, furnace and chest nearby. Use for build a home/set up a crafting table/set up a crafting table, furnace and chest/set up a base. Auto-plans gathering and crafting; do not decompose manually.", objectSchema()
                .build(), (bot, args) -> {
            boolean started = GoalExecutor.INSTANCE.submitAdaptive(bot, new Goal.Workstation());
            return started ? ok("goal_assigned: achieve_workstation") : fail("goal_plan_failed");
        });

        register("build_house", "Build a house/shelter. Use for build a house/build a home/build a house/build a small hut/build a house. The goal system auto-gathers ALL missing materials (wood/stone/glass) then builds — call once then STOP. Either pass blueprint (small_hut default, hut_5x5), OR pass width/depth/height/material for a custom house (e.g. build a stone house 7 blocks wide -> width=7, material=stone_like). material: planks (wood, default) / stone_like / glass.", objectSchema()
                .property("blueprint", stringSchema("preset blueprint name: small_hut (default) or hut_5x5; ignored when width/depth/height given"))
                .property("width", integerSchema("custom house outer width in blocks (3..16)", 3, 16))
                .property("depth", integerSchema("custom house outer depth in blocks (3..16)", 3, 16))
                .property("height", integerSchema("custom house wall height in blocks (2..8)", 2, 8))
                .property("material", stringSchema("wall material palette: planks (default) / stone_like / glass"))
                .build(), (bot, args) -> {
            // P3 parameterization: if any size parameter is given, use the custom:WxDxH:material spec (default side lengths 5/5/3); otherwise use a preset blueprint.
            boolean custom = args != null && (args.has("width") || args.has("depth") || args.has("height") || args.has("material"));
            String bp;
            if (custom) {
                int w = optionalInt(args, "width", 5);
                int d = optionalInt(args, "depth", 5);
                int h = optionalInt(args, "height", 3);
                String material = optionalString(args, "material", "planks");
                bp = "custom:" + w + "x" + d + "x" + h + ":" + material;
            } else {
                bp = optionalString(args, "blueprint", "small_hut");
            }
            boolean started = GoalExecutor.INSTANCE.submitAdaptive(bot, new Goal.Build(bp));
            return started ? ok("goal_assigned: build " + bp) : fail("goal_plan_failed");
        });

        register("stockpile", "Obtain N of an item then store everything into a nearby chest. Use for stockpile goods/stock up a bit/store it away/stockpile N cobblestone. Auto-plans obtaining and depositing; do not decompose manually.", objectSchema()
                .property("item", stringSchema("item id to stockpile, e.g. minecraft:cobblestone"))
                .property("count", integerSchema("how many to obtain"))
                .required("item")
                .build(), (bot, args) -> {
            boolean started = GoalExecutor.INSTANCE.submitAdaptive(bot,
                    new Goal.Stockpile(requiredItem(args, "item"), optionalInt(args, "count", 1)));
            return started ? ok("goal_assigned: stockpile") : fail("goal_plan_failed");
        });

    }

    /** Container/deposit work plus combat and the remaining action tools (boats, farming, guard). */
    private void registerContainerAndCombatTools() {
        register("find_container", "Find the nearest storage container (chest, barrel, shulker box) that is in sight and can be opened; radius is clamped to 1..16. Says nothing about its contents.", objectSchema()
                .property("radius", integerSchema("search radius"))
                .build(), (bot, args) -> ContainerTask.nearestContainer(bot, optionalInt(args, "radius", 8))
                .map(pos -> ok("{\"x\":" + pos.getX() + ",\"y\":" + pos.getY() + ",\"z\":" + pos.getZ() + "}"))
                .orElseGet(() -> fail("no_container")));

        register("find", "Start a persistent, bounded locate-only search for any registered non-air block (for example furnace, crafting_table, oak_sapling, water, or modid:block), or for sheep / a storage container. It surveys only targets the bot can actually see, then explores with short observed hops (never force-loading chunks, mining, harvesting, attacking, or opening storage). It reports either a real coordinate or that it could not find the target within the requested radius.", objectSchema()
                .property("target", stringSchema("registered block name/id (e.g. furnace, oak_sapling, minecraft:chest, modid:block), or sheep/container (a chest or other storage container; bonus_chest is the same search, since no chest can be told apart as the bonus chest)"))
                .property("radius", integerSchema("search radius in blocks (24..64)", 24, 64))
                .required("target")
                .build(), (bot, args) -> {
            Task task = DiscoveryTask.find(requiredString(args, "target"), optionalInt(args, "radius", 64));
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("show_location", "Physically show this bot's owner where any known, verified location or target is. The bot first tells the owner it will show them, uses Baritone to sprint within 5 blocks of that owner (even if out of view), then uses only safe observed Baritone navigation to within 3 blocks of the target, faces it, and makes five harmless air swings. Pass all x/y/z when another tool, visible context, memory, or the player supplied a verified coordinate; omit them only to use the latest successful find in this dimension. It never mines, attacks, opens, or interacts with the target.", objectSchema()
                .property("x", integerSchema("optional verified target x; omit x/y/z only to use the latest find"))
                .property("y", integerSchema("optional verified target y; omit x/y/z only to use the latest find"))
                .property("z", integerSchema("optional verified target z; omit x/y/z only to use the latest find"))
                .property("label", stringSchema("optional player-facing name for the target"))
                .build(), (bot, args) -> {
            Task task = showLocationTask(bot, args);
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("find_item_in_storage", "Answer where an item is stored, from what this bot itself saw the last time it opened storage containers (chests, barrels, shulker boxes). Returns the few best remembered containers with count, free slots and age; it never looks inside a container from a distance, so a result can be stale. Use before withdraw when you are unsure which chest holds the item.", objectSchema()
                .property("item", stringSchema("item id, for example minecraft:cobblestone"))
                .required("item")
                .build(), (bot, args) -> {
            net.minecraft.world.item.Item wanted = requiredItem(args, "item");
            String id = BuiltInRegistries.ITEM.getKey(wanted).toString();
            var hits = io.github.zoyluo.minecraftai.memory.BotMemoryStore.INSTANCE.of(bot.getUUID()).containers()
                    .find(bot.level().dimension().identifier().toString(), id, bot.blockPosition(), 1, 3);
            if (hits.isEmpty()) {
                return fail("not_in_known_storage: " + id);
            }
            long now = bot.level().getGameTime();
            com.google.gson.JsonArray array = new com.google.gson.JsonArray();
            for (var entry : hits) {
                JsonObject row = new JsonObject();
                row.addProperty("x", entry.pos().getX());
                row.addProperty("y", entry.pos().getY());
                row.addProperty("z", entry.pos().getZ());
                row.addProperty("block", entry.block());
                row.addProperty("count", entry.count(id));
                row.addProperty("free_slots", entry.freeSlots());
                row.addProperty("seen_seconds_ago", Math.max(0L, now - entry.lastVerified()) / 20L);
                array.add(row);
            }
            return ok(array.toString());
        });

        register("inspect_container", "Walk to a storage container in sight (or the one at chest_x/chest_y/chest_z), open it and look inside. What it holds is then remembered for find_item_in_storage. Use to learn what a chest contains before withdrawing.", objectSchema()
                .property("chest_x", integerSchema("optional container x"))
                .property("chest_y", integerSchema("optional container y"))
                .property("chest_z", integerSchema("optional container z"))
                .build(), (bot, args) -> {
            Task task = ContainerTask.inspect(optionalBlockPos(args, "chest_x", "chest_y", "chest_z"));
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("deposit", "Deposit items into a nearby or specified container. Use all_except_tools=true to store surplus materials while keeping damageable tools/equipment, or junk=true to stow only surplus filler blocks (dirt, cobblestone, gravel, netherrack, ...) beyond the throwaway budget the bot keeps for building.", objectSchema()
                .property("item", stringSchema("optional item id to deposit, for example minecraft:cobblestone"))
                .property("count", integerSchema("optional item count; omit or <=0 means all matching items"))
                .property("all_except_tools", booleanSchema("deposit all non-damageable items"))
                .property("junk", booleanSchema("stow only surplus junk blocks and keep everything else"))
                .property("chest_x", integerSchema("optional container x"))
                .property("chest_y", integerSchema("optional container y"))
                .property("chest_z", integerSchema("optional container z"))
                .build(), (bot, args) -> {
            Task task = optionalBoolean(args, "junk", false)
                    ? ContainerTask.depositJunk(optionalBlockPos(args, "chest_x", "chest_y", "chest_z"))
                    : ContainerTask.deposit(
                    optionalBlockPos(args, "chest_x", "chest_y", "chest_z"),
                    optionalItem(args, "item"),
                    optionalInt(args, "count", 0),
                    optionalBoolean(args, "all_except_tools", false));
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("withdraw", "Withdraw a specific item count from a nearby or specified container", objectSchema()
                .property("item", stringSchema("item id to withdraw, for example minecraft:cobblestone"))
                .property("count", integerSchema("count to withdraw"))
                .property("chest_x", integerSchema("optional container x"))
                .property("chest_y", integerSchema("optional container y"))
                .property("chest_z", integerSchema("optional container z"))
                .required("item")
                .build(), (bot, args) -> {
            Task task = ContainerTask.withdraw(
                    optionalBlockPos(args, "chest_x", "chest_y", "chest_z"),
                    requiredItem(args, "item"),
                    optionalInt(args, "count", 1));
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("give_item", "Hand real items to a nearby player: walks within reach, then drops exactly the requested item/count toward them using the same vanilla drop path a human player uses with Q (no teleport or forced pickup). Use this whenever the player asks to be given/handed an item the bot already carries ('give me 32 logs', 'give me your logs'; one call per item for a bundle), as opposed to deposit (containers) or trade (villagers). It never collects anything: a request to get/gather/mine new resources is served by the collection tools (gather_then_give, fulfill_items, gather, mine_ore) and give_item only after that finishes. Omit player to give to this bot's owner.", objectSchema()
                .property("item", stringSchema("item id to give, for example minecraft:stone_pickaxe"))
                .property("count", integerSchema("item count to give"))
                .property("player", stringSchema("optional recipient player name; defaults to owner"))
                .required("item")
                .build(), (bot, args) -> {
            Task task = new GiveItemTask(
                    requiredItem(args, "item"),
                    optionalInt(args, "count", 1),
                    optionalString(args, "player", ""));
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("equip_armor", "Equip the best armor pieces from inventory and select the best weapon. The automatic choice for armor, weapons, shields and bows is best-first too (used until they break, then the next best is equipped), so this simply does it now; only tools are chosen worst-first", objectSchema().build(), (bot, args) -> {
            int equipped = EquipAction.equipBestArmor(bot);
            EquipAction.equipBestWeapon(bot);
            return ok("equipped_armor_pieces: " + equipped);
        });

        register("attack", "Start a deterministic combat task against nearby entities of a type. The bot equips armor and weapon, attacks on cooldown, and retreats at low health. It refuses bosses.", objectSchema()
                .property("entity_type", stringSchema("entity type, for example minecraft:zombie"))
                .property("count", integerSchema("number of kills"))
                .required("entity_type")
                .build(), (bot, args) -> {
            EntityType<?> attackType = requiredEntityType(args, "entity_type");
            if (BossRefusal.refuses(attackType)) {
                BossRefusal.logRefused(bot, "tool");
                return fail(BossRefusal.MESSAGE);
            }
            Task task = new CombatTask(
                    attackType,
                    optionalInt(args, "count", 1),
                    io.github.zoyluo.minecraftai.MinecraftAiConfig.get().combat().retreatHp());
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("light_area", "Place torches around the bot where block light is below the configured threshold", objectSchema()
                .property("radius", integerSchema("scan radius"))
                .property("max_torches", integerSchema("maximum torches to place"))
                .build(), (bot, args) -> {
            Task task = new LightAreaTask(optionalInt(args, "radius", 8), optionalInt(args, "max_torches", 8));
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("follow", "Follow me / come with me / stick with me / stay close: follow a player continuously, keeping roughly 3-5 blocks of distance and re-pathing as they keep moving. Call this every time the player asks to be followed, even if the bot is already standing right next to them right now -- being nearby this instant does not mean the bot is actively tracking their movement; only a running follow task does that, and it is not running unless this was just called. It automatically mirrors travel: if the player rides a boat, use a nearby empty boat first, otherwise craft, launch, and board one, then swim if that is impossible; if the player swims, swim without launching a boat; if the player reaches land while the bot is boating, get to a dry shore, exit, and continue on foot. Omit player_name to follow this bot's owner.", objectSchema()
                .property("player_name", stringSchema("optional player name; defaults to owner"))
                .build(), (bot, args) -> {
            Task task = new FollowTask(optionalString(args, "player_name", ""));
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("launch_boat", "Find nearby safe water and launch a boat. If no boat is available, craft one from available planks first. Set board=true to enter the launched boat.", objectSchema()
                .property("board", booleanSchema("whether to board after launching the boat"))
                .build(), (bot, args) -> {
            Task task = new BoatLaunchTask(optionalBoolean(args, "board", false));
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("board_boat", "Board the nearest nearby empty boat.", objectSchema().build(), (bot, args) -> {
            Task task = new BoardBoatTask();
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("boat_follow", "Follow a player using a boat. It prefers a nearby empty boat, otherwise crafts, launches, and boards one; if that cannot work it swims. When the player leaves the boat, it reaches a dry shore, exits, and continues ordinary follow. Omit player_name to follow this bot's owner.", objectSchema()
                .property("player_name", stringSchema("optional player name; defaults to owner"))
                .build(), (bot, args) -> {
            Task task = new BoatFollowTask(optionalString(args, "player_name", ""));
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("exit_boat", "Safely stop riding the current boat and get out.", objectSchema().build(), (bot, args) -> {
            Task task = new DismountBoatTask();
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("hold", "Stay here / stay put / wait here / hold position / don't move: stop moving and stay at the current spot until told otherwise. Hold the current position until another task is assigned. DangerWatcher can still interrupt for survival threats.", objectSchema().build(), (bot, args) -> {
            Task task = new HoldTask();
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("guard", "Guard the current point, a coordinate, or a named player. Hostiles near the guard point are fought inline, then the bot returns.", objectSchema()
                .property("player_name", stringSchema("optional player name to guard"))
                .property("x", integerSchema("optional guard x"))
                .property("y", integerSchema("optional guard y"))
                .property("z", integerSchema("optional guard z"))
                .build(), (bot, args) -> {
            String playerName = optionalString(args, "player_name", "");
            BlockPos point = optionalBlockPos(args, "x", "y", "z");
            Task task = playerName.isBlank()
                    ? GuardTask.point(point == null ? bot.blockPosition() : point)
                    : GuardTask.player(playerName);
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("farm", "Till soil, plant crops, harvest mature crops, and optionally keep tending the area. Supported crops: wheat, carrot, potato.", objectSchema()
                .property("x", integerSchema("area center x"))
                .property("y", integerSchema("area center y"))
                .property("z", integerSchema("area center z"))
                .property("radius", integerSchema("area radius"))
                .property("crop", stringSchema("wheat, carrot, or potato"))
                .property("keep_tending", booleanSchema("keep surveying instead of completing after one pass"))
                .required("x")
                .required("y")
                .required("z")
                .required("crop")
                .build(), (bot, args) -> {
            FarmAction.CropSpec spec = FarmAction.cropSpec(requiredString(args, "crop"));
            Task task = new FarmTask(blockPos(args), optionalInt(args, "radius", 3), spec.seed(), spec.crop(),
                    optionalBoolean(args, "keep_tending", false), false);
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("harvest", "Harvest mature crops in an area without tilling or planting new empty soil. Supported crops: wheat, carrot, potato.", objectSchema()
                .property("x", integerSchema("area center x"))
                .property("y", integerSchema("area center y"))
                .property("z", integerSchema("area center z"))
                .property("radius", integerSchema("area radius"))
                .property("crop", stringSchema("wheat, carrot, or potato"))
                .required("x")
                .required("y")
                .required("z")
                .required("crop")
                .build(), (bot, args) -> {
            FarmAction.CropSpec spec = FarmAction.cropSpec(requiredString(args, "crop"));
            Task task = new FarmTask(blockPos(args), optionalInt(args, "radius", 3), spec.seed(), spec.crop(), false, true);
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("breed", "Feed two nearby adult animals of the requested type to breed them. Supported examples: minecraft:cow, minecraft:sheep, minecraft:pig, minecraft:chicken.", objectSchema()
                .property("entity_type", stringSchema("entity type, for example minecraft:cow"))
                .property("pairs", integerSchema("number of pairs to breed"))
                .required("entity_type")
                .build(), (bot, args) -> {
            Task task = new BreedTask(requiredEntityType(args, "entity_type"), optionalInt(args, "pairs", 1));
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("attack_entity", "Attack a nearby entity by type. Only creatures the bot has NOTICED (seen, heard, or that struck it) are candidates. "
                + "When the bot is busy with another task and would first have to turn to its target, the call is refused with 'busy' "
                + "instead of replacing that task: stop it first, or wait until it is done.", objectSchema()
                .property("entity_type", stringSchema("entity type, for example minecraft:cow"))
                .required("entity_type")
                .build(), ToolDefinition.Group.LOW_LEVEL, (bot, args) -> {
            String entityType = requiredString(args, "entity_type");
            Identifier id = Identifier.parse(entityType);
            CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "tool_attack_entity");
            // Never the owner or another bot, and only a target the bot could legally hit right now
            // (its box within vanilla's entity interaction range and no colliding block between): the
            // old 4.5-block scan let this tool land hits from five to seven blocks and through walls.
            // A creature must also have been NOTICED (docs/PERCEPTION.md: seen for the reaction time, heard, or a blow), so the
            // reply never reveals a mob the bot has not seen; animals and villagers keep omnidirectional observation.
            java.util.List<Entity> candidates = bot.level()
                    .getEntities(bot, bot.getBoundingBox().inflate(4.5D),
                            entity -> BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).equals(id)
                                    && !StrikeLegality.isFriendly(bot, entity)
                                    && ObservableWorldQuery.canNoticeCreature(bot, entity));
            Optional<Entity> target = candidates.stream()
                    .filter(entity -> StrikeLegality.strikeRefusal(bot, entity) == null)
                    .min(Comparator.comparingDouble(bot::distanceTo));
            if (target.isEmpty()) {
                if (candidates.isEmpty()) {
                    return fail("no_nearby_entity: " + entityType);
                }
                Entity nearest = candidates.stream()
                        .min(Comparator.comparingDouble(bot::distanceTo)).orElseThrow();
                return fail("target_not_strikable: " + StrikeLegality.strikeRefusal(bot, nearest));
            }
            // Human aim: a strike lands only on what is under the bot's crosshair, and the first call only starts the turn. A one-shot
            // tool call must still work with the bot facing away, so when the ONLY thing missing is the turn the tool starts a short
            // bounded attack (AttackEntityTask: turn at human speed, strike when under the crosshair, give up after
            // AttackEntityTask.MAX_TICKS) and says so: the tool result is "started", never a claimed hit; how the attack ended is
            // the task's own result.
            // A busy bot is asked BEFORE anything touches its head: InteractAction.attackEntity starts the turn toward the target, and
            // a refused call must leave the bot exactly as it was. Starting the bounded attack would replace whatever the bot is doing
            // (assign aborts the running task), so a busy bot only strikes what is already under its crosshair (no turn); otherwise it
            // says so: a safety task (a fight, an evade, a shelter) is never interrupted and the request is not kept; a mission the
            // player paused stays paused; any other running task is left alone ("stop" first, or wait). Only an idle bot, or one that
            // is already in an attack of its own, turns and takes the new target.
            String busy = attackBusyReason(bot);
            if (busy != null && !io.github.zoyluo.minecraftai.action.HumanAim.isUnderCrosshair(bot, target.get())) {
                return fail("busy: " + busy + "; the attack was not started");
            }
            ActionResult first = InteractAction.attackEntity(bot, target.get());
            if (first.isFailed() && InteractAction.NOT_UNDER_CROSSHAIR.equals(first.reason())) {
                if (busy != null) {
                    return fail("busy: " + busy + "; the attack was not started");
                }
                Task attack = new AttackEntityTask(target.get());
                assignLlm(bot, attack);
                return ok("attack_started: turning to face " + entityType
                        + "; the strike lands once it is under the crosshair (within " + AttackEntityTask.MAX_TICKS + " ticks)");
            }
            return result(first);
        });
    }

    /** Why {@code attack_entity} cannot take the bot's hands right now, or null when it can (idle, or already attacking). */
    private static String attackBusyReason(AIPlayerEntity bot) {
        if (TaskManager.INSTANCE.isActiveSafety(bot)) {
            return "the safety task " + TaskManager.INSTANCE.getActive(bot).map(Task::name).orElse("safety")
                    + " is handling a threat right now (this request is not kept)";
        }
        if (TaskManager.INSTANCE.isUserPaused(bot)) {
            return "the player paused the mission";
        }
        return TaskManager.INSTANCE.getActive(bot)
                .filter(task -> !(task instanceof AttackEntityTask))
                .map(task -> "the task " + task.name() + " is running; call stop first to fight instead")
                .orElse(null);
    }

    /** Terminal/mission-control commands: stop, pause, resume, cancel_all. */
    private void registerControlTools() {
        register("stop", "Stop / stop that / cancel it / never mind: cancel the current mission/task but preserve explicitly queued missions. Use immediately before a replacement goal.", objectSchema().build(), (bot, args) -> {
            IntentControlTransaction.Outcome outcome = IntentController.INSTANCE.cancelCurrent(
                    bot, IntentController.ControlOrigin.LLM_TOOL, "tool_stop");
            return ok(outcome.changed() ? "cancelled_current" : "already_idle");
        });

        register("pause", "Pause / hold on / wait a sec / hang on: pause the current mission without deleting it or its queue; safety actions may still run.",
                objectSchema().build(), (bot, args) -> {
            boolean changed = IntentController.INSTANCE.pause(
                    bot, IntentController.ControlOrigin.LLM_TOOL, "tool_pause");
            return ok(changed ? "mission_paused" : "already_paused");
        });

        register("resume", "Resume a mission previously paused by the user.", objectSchema().build(), (bot, args) -> {
            boolean changed = IntentController.INSTANCE.resume(
                    bot, IntentController.ControlOrigin.LLM_TOOL, "tool_resume");
            return ok(changed ? "mission_resumed" : "not_paused");
        });

        register("cancel_all", "Cancel the current mission and every queued mission", objectSchema().build(), (bot, args) -> {
            IntentControlTransaction.Outcome outcome = IntentController.INSTANCE.cancelAll(
                    bot, IntentController.ControlOrigin.LLM_TOOL, "tool_cancel_all");
            return ok(outcome.changed() ? "cancelled_all" : "already_idle");
        });
    }

    /** Multi-bot task board and inter-bot messaging. */
    private void registerCoordinationTools() {
        register("post_job", "Post a shared job to the multi-bot task board. Any idle bot can claim and execute it.", objectSchema()
                .property("kind", stringSchema("job kind, for example mine, build, craft, smelt, move, eat, or light_area"))
                .property("params", objectSchema().build())
                .required("kind")
                .required("params")
                .build(), ToolDefinition.Group.COORDINATION, (bot, args) -> {
            Optional<UUID> ownerUuid = AIPlayerManager.INSTANCE.ownerOf(bot);
            if (ownerUuid.isEmpty()) {
                return fail("coordination_requires_owned_bot");
            }
            UUID id = TaskBoard.INSTANCE.postForOwner(ownerUuid.get(), requiredString(args, "kind"),
                    paramsObject(args, "params"));
            io.github.zoyluo.minecraftai.persist.BotPersistence.INSTANCE.markDirty(bot.level().getServer());
            return ok("job_posted: " + id);
        });

        register("list_jobs", "List shared jobs on the multi-bot task board", objectSchema().build(), ToolDefinition.Group.COORDINATION, (bot, args) -> {
            Optional<UUID> ownerUuid = AIPlayerManager.INSTANCE.ownerOf(bot);
            if (ownerUuid.isEmpty()) {
                return fail("coordination_requires_owned_bot");
            }
            List<Job> jobs = TaskBoard.INSTANCE.snapshotForOwner(ownerUuid.get());
            if (jobs.isEmpty()) {
                return ok("[]");
            }
            StringBuilder builder = new StringBuilder("[");
            for (int index = 0; index < jobs.size(); index++) {
                Job job = jobs.get(index);
                if (index > 0) {
                    builder.append(", ");
                }
                builder.append("{id=").append(job.id())
                        .append(", kind=").append(job.kind())
                        .append(", status=").append(job.status())
                        .append(", reason=").append(job.failureReason())
                        .append("}");
            }
            builder.append("]");
            return ok(builder.toString());
        });

        register("tell_bot", "Send a message from this bot to another bot's brain, reusing the normal @bot chat pathway.", objectSchema()
                .property("target", stringSchema("target bot name"))
                .property("message", stringSchema("message text"))
                .required("target")
                .required("message")
                .build(), ToolDefinition.Group.COORDINATION, (bot, args) -> {
            String targetName = requiredString(args, "target");
            var target = AIPlayerManager.INSTANCE.getByName(targetName);
            if (target.isEmpty()) {
                return fail("target_unavailable");
            }
            if (!BotAuthorizationGate.INSTANCE.authorizeBot(
                    bot, target.get(), BotAuthorizationPolicy.Operation.COMMAND, "tool:tell_bot")) {
                return fail("target_unavailable");
            }
            boolean queued = BrainCoordinator.INSTANCE.handleMessage(target.get(), bot.getGameProfile().name(), requiredString(args, "message"));
            return queued ? ok("message_sent") : fail("target_busy");
        });
    }

    /** Persistent per-bot facts/places and the long-term goal tools. */
    private void registerMemoryAndGoalManagementTools() {
        register("remember", "Store a persistent per-bot fact by key. Use for user preferences, named facts, or long-lived notes.", objectSchema()
                .property("key", stringSchema("memory key"))
                .property("value", stringSchema("memory value"))
                .required("key")
                .required("value")
                .build(), ToolDefinition.Group.MEMORY, (bot, args) -> {
            BotMemoryStore.INSTANCE.of(bot.getUUID()).remember(requiredString(args, "key"), requiredString(args, "value"));
            return ok("remembered");
        });

        register("recall", "Recall a persistent fact by key", objectSchema()
                .property("key", stringSchema("memory key"))
                .required("key")
                .build(), ToolDefinition.Group.MEMORY, (bot, args) -> BotMemoryStore.INSTANCE.of(bot.getUUID())
                .recall(requiredString(args, "key"))
                .map(ToolRegistry::ok)
                .orElseGet(() -> fail("missing_memory: " + requiredString(args, "key"))));

        register("forget", "Delete a persistent fact by key", objectSchema()
                .property("key", stringSchema("memory key"))
                .required("key")
                .build(), ToolDefinition.Group.MEMORY, (bot, args) -> {
            boolean removed = BotMemoryStore.INSTANCE.of(bot.getUUID()).forget(requiredString(args, "key"));
            return ok("forgotten: " + removed);
        });

        register("mark_place", "Remember the bot's current block position as a named place", objectSchema()
                .property("name", stringSchema("place name, for example home"))
                .required("name")
                .build(), ToolDefinition.Group.MEMORY, (bot, args) -> {
            BotMemoryStore.INSTANCE.of(bot.getUUID()).markPlace(requiredString(args, "name"), bot.level(), bot.blockPosition());
            return ok("marked_place: " + requiredString(args, "name") + " at " + bot.blockPosition().toShortString());
        });

        register("goto_place", "Assign a move task to a remembered named place in the current dimension", objectSchema()
                .property("name", stringSchema("place name"))
                .required("name")
                .build(), ToolDefinition.Group.MEMORY, (bot, args) -> {
            Optional<BotMemory.Place> place = BotMemoryStore.INSTANCE.of(bot.getUUID()).place(requiredString(args, "name"));
            if (place.isEmpty()) {
                return fail("unknown_place: " + requiredString(args, "name"));
            }
            if (!bot.level().dimension().identifier().toString().equals(place.get().dimension())) {
                return fail("place_in_other_dimension: " + place.get().dimension());
            }
            Task task = new MoveTask(bot, place.get().pos());
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("resume_mining", "Continue mining where the last mining session left off: walks back to the remembered mine face and mines the same ore kinds. With count it mines that many NEW drops; without count it keeps actively exploring, rescanning, and collecting for up to ten minutes, then reports whether it found any. Use when the player says things like 'continue mining'/'keep digging'.", objectSchema()
                .property("count", integerSchema("how many more NEW ore drops to mine; omit to collect for up to ten minutes"))
                .build(), (bot, args) -> {
            var mem = BotMemoryStore.INSTANCE.of(bot.getUUID());
            var face = mem.place("mine_face");
            if (face.isEmpty()) {
                return fail("no_mine_face: no recorded mining face from the previous task");
            }
            if (!bot.level().dimension().identifier().toString().equals(face.get().dimension())) {
                return fail("mine_face_in_other_dimension");
            }
            java.util.Set<net.minecraft.world.level.block.Block> ores = new java.util.HashSet<>();
            mem.recall("mine_face_ores").ifPresent(csv -> {
                for (String id : csv.split(",")) {
                    var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getValue(net.minecraft.resources.Identifier.parse(id.trim()));
                    if (block != net.minecraft.world.level.block.Blocks.AIR) {
                        ores.add(block);
                    }
                }
            });
            // Queue relay: walk back to the work face first, then resume mining the same ore type (the goal queue chains automatically, and it can resume even if interrupted midway).
            Task back = new MoveTask(bot, face.get().pos());
            assignLlm(bot, back);
            Set<Block> resumeOres = ores.isEmpty()
                    ? java.util.Set.of(net.minecraft.world.level.block.Blocks.IRON_ORE)
                    : ores;
            boolean started = GoalExecutor.INSTANCE.submitAdaptive(bot, args.has("count")
                    ? additionalOreGoal(bot, resumeOres, optionalInt(args, "count", 1))
                    : timedOreGoal(bot, resumeOres));
            return started ? ok("resuming at " + face.get().pos().toShortString()) : fail("goal_plan_failed");
        });

        register("mine_and_stockpile", "Mine a specified number of new ore drops, then deposit the yield into a chest near the remembered base. Use when the player wants mined goods stored, not carried. A count is required because this combined mining-and-storage workflow has a precise delivery target; use mine_ore without count for the ten-minute collect-as-much-as-possible workflow.", objectSchema()
                .property("ore", stringSchema("ore block id or raw item, e.g. minecraft:iron_ore"))
                .property("count", integerSchema("how many new ore drops to mine", 1, Integer.MAX_VALUE))
                .required("ore")
                .required("count")
                .build(), (bot, args) -> {
            var ores = oreTargetsFrom(requiredString(args, "ore"));
            int count = requiredPositiveInt(args, "count");
            boolean started = GoalExecutor.INSTANCE.submitAdaptive(bot, additionalOreGoal(bot, ores, count));
            if (!started) {
                return fail("goal_plan_failed");
            }
            // Return-to-storage relay: the goal queue chains automatically (once mining finishes it goes to deposit at the base chest; if there is no base, Stockpile reports no_base on its own)
            Item yield = io.github.zoyluo.minecraftai.action.HarvestCore.expectedDropsFor(ores)
                    .stream().findFirst().orElse(null);
            if (yield != null) {
                GoalExecutor.INSTANCE.submitAdaptive(bot, new Goal.Stockpile(yield, count));
            }
            return ok("goal_assigned: mine_ore + stockpile queued");
        });

        register("recover_drops", "Run back to the most recent death location and pick up dropped items before they despawn (5 min)", objectSchema()
                .build(), ToolDefinition.Group.MEMORY, (bot, args) -> {
            var deaths = io.github.zoyluo.minecraftai.memory.EpisodeLog.INSTANCE
                    .recentOfType(bot.getUUID(), io.github.zoyluo.minecraftai.memory.EpisodeLog.Type.DEATH, 1);
            if (deaths.isEmpty()) {
                return fail("no_recent_death");
            }
            var death = deaths.get(0);
            Task task = new io.github.zoyluo.minecraftai.task.RecoverDropsTask(death.pos(), death.gameTick());
            assignLlm(bot, task);
            return ok("assigned: recover_drops -> " + death.pos().toShortString());
        });

        register("set_goal", "Set a persistent long-term goal with ordered steps. Steps should be an array of short strings.", objectSchema()
                .property("title", stringSchema("goal title"))
                .property("steps", arrayOfStringsSchema("ordered goal steps"))
                .required("title")
                .required("steps")
                .build(), ToolDefinition.Group.MEMORY, (bot, args) -> {
            List<String> steps = stringArray(args, "steps");
            BotMemoryStore.INSTANCE.of(bot.getUUID()).setGoal(requiredString(args, "title"), steps);
            return ok(BotMemoryStore.INSTANCE.of(bot.getUUID()).goalStatus(""));
        });

        register("advance_goal", "Advance the current persistent long-term goal by one step", objectSchema()
                .property("result", stringSchema("short result of the completed step"))
                .build(), ToolDefinition.Group.MEMORY, (bot, args) -> ok(BotMemoryStore.INSTANCE.of(bot.getUUID()).advanceGoal(optionalString(args, "result", ""))));

        register("goal_status", "Get the current persistent long-term goal status", objectSchema().build(), ToolDefinition.Group.MEMORY, (bot, args) ->
                ok(BotMemoryStore.INSTANCE.of(bot.getUUID()).goalStatus("")));
    }

    /** assign_task and the task lifecycle tools it shares status/cancellation with. */
    private void registerTaskLifecycleTools() {
        register("assign_task", "Start a high-level deterministic task for the bot. Prefer this for movement, foraging, mining, combat, building, lighting, farming, fishing, trading, breeding, water travel, container work, or showing any known verified location. Use the dedicated gather tool to collect a specific item (it is strongly typed and will not silently drop the item argument the way this tool's generic params can), and use dedicated craft, eat, and smelt tools for those actions. task_type=gather remains available here only as a fallback after a goal failure; with count it means NEW/additional inventory items, never the total already carried, and without count it gathers for up to ten minutes while safely exploring and rescanning. Use task_type=clear_grass or task_type=break_blocks for an exact nearby physical block-breaking count when drops do not matter. For exposed surface blocks use task_type=mine: with a count it keeps the physical mining quota, while an omitted count gathers that block's normal drops for up to ten minutes. To obtain ores (iron/coal/copper/gold/diamond, *_ore, or raw_*), use the dedicated mine_ore tool; both explicit-quota and no-count collection safely explore through repeated bounded observed hops and rescans, never by tunneling toward unseen ore. Supersedes any current task. Build params: blueprint plus optional anchor_x/anchor_y/anchor_z, auto_site, and flatten. x/y/z aliases are accepted; omit anchor when auto_site=true.", objectSchema()
                .property("task_type", stringSchema("move, find, show_location, gather, clear_grass, break_blocks, forage, irrigate, milk_cow, raid_crops, attack, mine, mine_ore, mine_valuables, harvest_crop, build, light_area, farm, harvest, fish, trade, breed, follow, launch_boat, board_boat, boat_follow, exit_boat, hold, guard, deposit, stockpile, or withdraw"))
                .property("params", objectSchema().build())
                .required("task_type")
                .required("params")
                .build(), (bot, args) -> {
            String taskType = requiredString(args, "task_type");
            JsonObject params = args.getAsJsonObject("params");
            if (params == null) {
                return fail("missing_or_bad_arg: params");
            }
            if ("mine_ore".equals(taskType)) {
                Set<Block> ores = oreTargetsFrom(requiredString(params, "ore"));
                boolean hasCount = params.has("count");
                int count = optionalInt(params, "count", 1);
                if (!MinecraftAiConfig.get().goal().autoToolFillEnabled()) {
                    Task task = hasCount ? new OreDigTask(ores, count) : OreDigTask.collectForDuration(ores);
                    assignLlm(bot, task);
                    return ok("assigned: " + task.name());
                }
                boolean started = GoalExecutor.INSTANCE.submitAdaptive(bot,
                        hasCount ? additionalOreGoal(bot, ores, count) : timedOreGoal(bot, ores));
                return started ? ok("goal_assigned: mine_ore") : fail("goal_plan_failed");
            }
            if ("mine".equals(taskType)) {
                Block block = blockWithAlias(params, "block", "block_type");
                if (OreScan.isOreBlock(block)) {
                    boolean hasCount = params.has("count");
                    int count = optionalInt(params, "count", 1);
                    if (!MinecraftAiConfig.get().goal().autoToolFillEnabled()) {
                        Task task = hasCount ? new OreDigTask(OreScan.oreFamily(block), count)
                                : OreDigTask.collectForDuration(OreScan.oreFamily(block));
                        assignLlm(bot, task);
                        return ok("assigned: " + task.name());
                    }
                    boolean started = GoalExecutor.INSTANCE.submitAdaptive(bot,
                            hasCount ? additionalOreGoal(bot, OreScan.oreFamily(block), count)
                                    : timedOreGoal(bot, OreScan.oreFamily(block)));
                    return started ? ok("goal_assigned: mine_ore") : fail("goal_plan_failed");
                }
            }
            if ("harvest_crop".equals(taskType)) {
                FarmAction.CropSpec spec = FarmAction.cropSpec(requiredString(params, "crop"));
                Item produce = spec.crop() == net.minecraft.world.level.block.Blocks.WHEAT
                        ? Items.WHEAT : spec.seed();
                int heldProduce = HarvestCore.countInventoryItems(bot, Set.of(produce));
                boolean started = GoalExecutor.INSTANCE.submitAdaptive(bot, params.has("count")
                        ? new Goal.HarvestCrop(spec.crop(), spec.seed(), produce,
                                optionalInt(params, "count", 1), heldProduce)
                        : Goal.HarvestCrop.timedCollection(spec.crop(), spec.seed(), produce, heldProduce));
                return started ? ok("goal_assigned: harvest_crop") : fail("goal_plan_failed");
            }
            if ("attack".equals(taskType) && BossRefusal.refuses(requiredEntityType(params, "entity_type"))) {
                BossRefusal.logRefused(bot, "task_type");
                return fail(BossRefusal.MESSAGE);
            }
            Task task = createTask(bot, taskType, params);
            assignLlm(bot, task);
            return ok("assigned: " + task.name());
        });

        register("get_task_status", "Get the current task status", objectSchema().build(), (bot, args) -> {
            // Optimization 3: while a deterministic goal is running, don't feed it detailed status -- this breaks the positive-feedback loop of the brain repeatedly polling (measured get_task_status x19 exhausting the turn budget);
            // the goal proactively wakes the brain on completion/failure, so no polling is needed in between.
            if (io.github.zoyluo.minecraftai.goal.GoalExecutor.INSTANCE.hasActivePlan(bot)) {
                return ok("{\"state\":\"goal_running\",\"note\":\"The goal is running and will report when it completes or fails; do not poll repeatedly.\"}");
            }
            TaskStatus status = TaskManager.INSTANCE.status(bot);
            return ok("{\"name\":\"" + escape(status.name())
                    + "\",\"state\":\"" + status.state()
                    + "\",\"progress\":" + status.progress()
                    + ",\"description\":\"" + escape(status.description()) + "\"}");
        });

        register("abort_task", "Legacy alias for cancelling the current mission/task while preserving queued missions", objectSchema().build(), (bot, args) -> {
            IntentControlTransaction.Outcome outcome = IntentController.INSTANCE.cancelCurrent(
                    bot, IntentController.ControlOrigin.LLM_TOOL, "tool_abort_task");
            return ok(outcome.changed() ? "cancelled_current" : "already_idle");
        });
    }

    private static Task createTask(io.github.zoyluo.minecraftai.entity.AIPlayerEntity bot, String taskType, JsonObject params) {
        if (params == null) {
            throw new IllegalArgumentException("missing_or_bad_arg: params");
        }
        return switch (taskType) {
            case "move" -> new MoveTask(bot, new BlockPos(requiredInt(params, "x"), requiredInt(params, "y"), requiredInt(params, "z")));
            case "find" -> DiscoveryTask.find(requiredString(params, "target"), optionalInt(params, "radius", 64));
            case "show_location" -> showLocationTask(bot, params);
            // The session log showed the model attempting assign_task(find_container) even
            // though the old one-shot inspection tool was not a task type. Retain that spelling
            // as a safe persistent locate-only alias instead of leaving the bot idle.
            case "find_container" -> DiscoveryTask.find(optionalString(params, "target", "bonus_chest"),
                    optionalInt(params, "radius", 64));
            case "forage" -> params.has("count")
                    ? GatherQuotaTask.collectAdditional(Items.SWEET_BERRIES, optionalInt(params, "count", 1))
                    : GatherQuotaTask.collectForDuration(Items.SWEET_BERRIES);
            case "attack" -> new CombatTask(
                    requiredEntityType(params, "entity_type"),
                    optionalInt(params, "count", 1),
                    io.github.zoyluo.minecraftai.MinecraftAiConfig.get().combat().retreatHp());
            case "mine" -> {
                Block block = blockWithAlias(params, "block", "block_type");
                int count = optionalInt(params, "count", 1);
                yield OreScan.isOreBlock(block)
                        ? (params.has("count") ? new OreDigTask(OreScan.oreFamily(block), count)
                                : OreDigTask.collectForDuration(OreScan.oreFamily(block)))
                        : (params.has("count") ? new MineTask(block, count) : timedResourceMineTask(block));
            }
            case "mine_ore" -> {
                Set<Block> ores = oreTargetsFrom(requiredString(params, "ore"));
                yield params.has("count") ? new OreDigTask(ores, optionalInt(params, "count", 1))
                        : OreDigTask.collectForDuration(ores);
            }
            case "mine_valuables" -> new MineValuablesTask(optionalInt(params, "radius", MineValuablesTask.DEFAULT_RADIUS));
            case "gather" -> params.has("count")
                    ? GatherQuotaTask.collectAdditional(requiredItem(params, "item"), optionalInt(params, "count", 1))
                    : GatherQuotaTask.collectForDuration(requiredItem(params, "item"));
            case "clear_grass" -> GatherQuotaTask.clearGrass(requiredPositiveInt(params, "count"));
            case "break_blocks" -> isAnyLeavesRequest(params, "block")
                    ? GatherQuotaTask.breakLeaves(requiredPositiveInt(params, "count"))
                    : GatherQuotaTask.breakBlocks(
                            requiredBreakableBlock(bot, params, "block"), requiredPositiveInt(params, "count"));
            case "irrigate" -> new io.github.zoyluo.minecraftai.task.IrrigateTask(
                    bot.blockPosition().relative(bot.getDirection(), 2).below()); // dig a 2x2 infinite water source in the floor layer, 2 blocks in front of the bot
            case "milk_cow" -> new io.github.zoyluo.minecraftai.task.MilkCowTask(optionalInt(params, "count", 1)); // milk `count` buckets of milk (requires empty buckets)
            case "raid_crops" -> new io.github.zoyluo.minecraftai.task.RaidCropsTask(optionalInt(params, "count", 8)); // harvest nearby mature crops (village or wild)
            case "fish" -> new FishTask(optionalInt(params, "max_catches", 1), optionalInt(params, "max_ticks", 6000));
            case "trade" -> new TradeTask(optionalItem(params, "target_item"), optionalInt(params, "max_distance", 16));
            case "stockpile" -> new StockpileTask(optionalBoolean(params, "all_except_tools", true));
            case "light_area" -> new LightAreaTask(optionalInt(params, "radius", 8), optionalInt(params, "max_torches", 8));
            case "follow" -> new FollowTask(optionalString(params, "player_name", ""));
            case "launch_boat" -> new BoatLaunchTask(optionalBoolean(params, "board", false));
            case "board_boat" -> new BoardBoatTask();
            case "boat_follow" -> new BoatFollowTask(optionalString(params, "player_name", ""));
            case "exit_boat" -> new DismountBoatTask();
            case "hold" -> new HoldTask();
            case "guard" -> {
                String playerName = optionalString(params, "player_name", "");
                BlockPos point = optionalBlockPos(params, "x", "y", "z");
                yield playerName.isBlank() ? GuardTask.point(point) : GuardTask.player(playerName);
            }
            case "farm" -> {
                FarmAction.CropSpec spec = FarmAction.cropSpec(requiredString(params, "crop"));
                yield new FarmTask(blockPos(params), optionalInt(params, "radius", 3), spec.seed(), spec.crop(),
                        optionalBoolean(params, "keep_tending", false), false);
            }
            case "harvest" -> {
                FarmAction.CropSpec spec = FarmAction.cropSpec(requiredString(params, "crop"));
                yield new FarmTask(blockPos(params), optionalInt(params, "radius", 3), spec.seed(), spec.crop(), false, true);
            }
            case "breed" -> new BreedTask(requiredEntityType(params, "entity_type"), optionalInt(params, "pairs", 1));
            case "deposit" -> optionalBoolean(params, "junk", false)
                    ? ContainerTask.depositJunk(optionalBlockPos(params, "chest_x", "chest_y", "chest_z"))
                    : ContainerTask.deposit(
                    optionalBlockPos(params, "chest_x", "chest_y", "chest_z"),
                    optionalItem(params, "item"),
                    optionalInt(params, "count", 0),
                    optionalBoolean(params, "all_except_tools", false));
            case "withdraw" -> ContainerTask.withdraw(
                    optionalBlockPos(params, "chest_x", "chest_y", "chest_z"),
                    requiredItem(params, "item"),
                    optionalInt(params, "count", 1));
            case "build" -> {
                try {
                    boolean autoSite = optionalBoolean(params, "auto_site", false);
                    boolean flatten = optionalBoolean(params, "flatten", false);
                    BlockPos anchor = autoSite && !hasBlockPos(params, "anchor_x", "anchor_y", "anchor_z") && !hasBlockPos(params, "x", "y", "z")
                            ? null
                            : new BlockPos(
                                    intWithAlias(params, "anchor_x", "x"),
                                    intWithAlias(params, "anchor_y", "y"),
                                    intWithAlias(params, "anchor_z", "z"));
                    yield new BuildTask(
                            BlueprintLoader.load(requiredString(params, "blueprint")),
                            anchor,
                            autoSite,
                            flatten);
                } catch (java.io.IOException exception) {
                    throw new IllegalArgumentException(exception.getMessage(), exception);
                }
            }
            default -> throw new IllegalArgumentException("unknown_task_type: " + taskType);
        };
    }

    /** Explicit verified coordinates work independently; omitting them makes “show me” use the latest real discovery. */
    private static Task showLocationTask(AIPlayerEntity bot, JsonObject args) {
        DiscoveryTask.FoundTarget shown = DiscoveryTask.FoundTarget.forShowing(DiscoveryTask.latestFound(bot),
                        optionalBlockPos(args, "x", "y", "z"), optionalString(args, "label", ""))
                .orElseThrow(() -> new IllegalArgumentException("no_recent_find_in_this_dimension"));
        return new ShowTargetTask(shown.pos(), shown.label());
    }

    private void register(String name, String description, JsonObject schema, ToolDefinition.Handler handler) {
        tools.put(name, new ToolDefinition(name, description, schema, handler));
    }

    private void register(String name, String description, JsonObject schema, ToolDefinition.Group group, ToolDefinition.Handler handler) {
        tools.put(name, new ToolDefinition(name, description, schema, handler, group));
    }

    private static String craftPlanJson(CraftingHelper.CraftPlan plan) {
        JsonObject root = new JsonObject();
        root.addProperty("feasible", plan.success());
        root.addProperty("target", BuiltInRegistries.ITEM.getKey(plan.target()).toString());
        root.addProperty("count", plan.targetCount());
        root.addProperty("needs_crafting_table", plan.needsCraftingTable());

        com.google.gson.JsonArray steps = new com.google.gson.JsonArray();
        for (CraftingHelper.CraftStep step : plan.steps()) {
            JsonObject json = new JsonObject();
            json.addProperty("output", BuiltInRegistries.ITEM.getKey(step.recipe().output()).toString());
            json.addProperty("crafts", step.crafts());
            json.addProperty("output_count", step.outputCount());
            json.addProperty("needs_crafting_table", step.recipe().needsCraftingTable());
            com.google.gson.JsonArray ingredients = new com.google.gson.JsonArray();
            for (io.github.zoyluo.minecraftai.craft.RecipeRegistry.Ingredient ingredient : step.recipe().ingredients()) {
                JsonObject ingredientJson = new JsonObject();
                ingredientJson.addProperty("count", ingredient.count() * step.crafts());
                com.google.gson.JsonArray anyOf = new com.google.gson.JsonArray();
                for (Item item : ingredient.anyOf()) {
                    anyOf.add(BuiltInRegistries.ITEM.getKey(item).toString());
                }
                ingredientJson.add("any_of", anyOf);
                ingredients.add(ingredientJson);
            }
            json.add("ingredients", ingredients);
            steps.add(json);
        }
        root.add("steps", steps);

        com.google.gson.JsonArray missing = new com.google.gson.JsonArray();
        for (CraftingHelper.Missing item : plan.missing()) {
            JsonObject json = new JsonObject();
            json.addProperty("item", BuiltInRegistries.ITEM.getKey(item.item()).toString());
            json.addProperty("count", item.count());
            json.addProperty("source", AcquisitionHints.source(item.item()));
            missing.add(json);
        }
        root.add("missing", missing);
        return root.toString();
    }

    private static ToolDefinition.ToolResult result(io.github.zoyluo.minecraftai.action.ActionResult actionResult) {
        if (actionResult.isSuccess() || actionResult.isInProgress()) {
            return ok(actionResult.status().name().toLowerCase());
        }
        return fail(actionResult.reason());
    }

    private static ToolDefinition.ToolResult ok(String message) {
        return new ToolDefinition.ToolResult(true, message);
    }

    private static ToolDefinition.ToolResult fail(String message) {
        return new ToolDefinition.ToolResult(false, message);
    }

    private static void assignLlm(AIPlayerEntity bot, Task task) {
        if (TaskManager.INSTANCE.isActiveSafety(bot)) {
            throw new SafetyTaskActiveException(
                    TaskManager.INSTANCE.getActive(bot).map(Task::name).orElse("safety"));
        }
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.LLM_TOOL, "llm_tool"));
    }

    private static BlockPos blockPos(JsonObject args) {
        return new BlockPos(requiredInt(args, "x"), requiredInt(args, "y"), requiredInt(args, "z"));
    }

    private static int requiredInt(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive()) {
            throw new IllegalArgumentException("missing_or_bad_arg: " + name);
        }
        return args.get(name).getAsInt();
    }

    private static int requiredPositiveInt(JsonObject args, String name) {
        int value = requiredInt(args, name);
        if (value < 1) {
            throw new IllegalArgumentException("must_be_positive: " + name);
        }
        return value;
    }

    private static int intWithAlias(JsonObject args, String primary, String alias) {
        if (args.has(primary) && args.get(primary).isJsonPrimitive()) {
            return args.get(primary).getAsInt();
        }
        if (args.has(alias) && args.get(alias).isJsonPrimitive()) {
            return args.get(alias).getAsInt();
        }
        throw new IllegalArgumentException("missing_or_bad_arg: " + primary);
    }

    private static String requiredString(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive()) {
            throw new IllegalArgumentException("missing_or_bad_arg: " + name);
        }
        return args.get(name).getAsString();
    }

    /** The only non-registry item spelling accepted by gather_then_give: generic tree logs. */
    static boolean isGenericLogHandoff(String item) {
        return item != null && ("logs".equalsIgnoreCase(item.trim())
                || "minecraft:logs".equalsIgnoreCase(item.trim()));
    }

    private static int optionalInt(JsonObject args, String name, int defaultValue) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive()) {
            return defaultValue;
        }
        return args.get(name).getAsInt();
    }

    /**
     * Public mining tools promise a newly mined quota.  Keep the inventory snapshot with the
     * goal so its completion target and mission-sized planning stay distinct.
     */
    private static Goal.MineOre additionalOreGoal(AIPlayerEntity bot, Set<Block> ores, int requestedDrops) {
        int heldDrops = HarvestCore.countInventoryItems(bot, HarvestCore.expectedDropsFor(ores));
        return new Goal.MineOre(ores, requestedDrops, heldDrops);
    }

    /**
     * Public fulfillment is an acquisition request, not permission to hand over an existing
     * stack.  Capture one immutable, planner-compatible inventory baseline for every requested
     * output so a goal/replan/restart has to produce the requested quantity above it.
     */
    private static Goal.Fulfill freshFulfillGoal(AIPlayerEntity bot, List<Goal.Allocation> allocations) {
        Goal.Fulfill canonical = new Goal.Fulfill(allocations);
        return new Goal.Fulfill(canonical.allocations(), freshBaselines(canonical.allocations(),
                item -> GoalSnapshotCollector.inventoryCount(bot, item)));
    }

    /**
     * The count of each requested item that the request must be additional to. Everything handed to a
     * player, and every raw resource, is a quota of new units above what is carried. A crafted item the
     * bot keeps for itself is different: "make yourself a pickaxe and make me one" is already half done
     * when the bot carries a pickaxe, so a kept tool or armor piece is satisfied by what is held (baseline
     * zero), and next to a handoff of the same item only the units beyond the kept ones are new. The
     * key is present for every item so the request stays a fresh one even when the baseline is zero.
     */
    static Map<Item, Integer> freshBaselines(List<Goal.Allocation> allocations, ToIntFunction<Item> carried) {
        Map<Item, Integer> kept = new LinkedHashMap<>();
        Set<Item> delivered = new HashSet<>();
        for (Goal.Allocation allocation : allocations) {
            if (allocation.delivery()) {
                delivered.add(allocation.item());
                kept.putIfAbsent(allocation.item(), 0);
            } else {
                kept.merge(allocation.item(), allocation.count(), Integer::sum);
            }
        }
        Map<Item, Integer> baselines = new LinkedHashMap<>();
        kept.forEach((item, keptCount) -> {
            int held = carried.applyAsInt(item);
            if (!ItemNouns.isCraftedOutput(item)) {
                baselines.put(item, held);
            } else {
                baselines.put(item, delivered.contains(item) ? Math.max(0, held - keptCount) : 0);
            }
        });
        return baselines;
    }

    /**
     * A no-count player request is not satisfied by stock already carried.  Keep that snapshot
     * solely as an accounting baseline for the timed mission's eventual result.
     */
    private static Goal.MineOre timedOreGoal(AIPlayerEntity bot, Set<Block> ores) {
        int heldDrops = HarvestCore.countInventoryItems(bot, HarvestCore.expectedDropsFor(ores));
        return Goal.MineOre.timedCollection(ores, heldDrops);
    }

    /**
     * Generic {@code assign_task mine} names a block, while resource collection is measured in
     * drops.  Preserve stone -> cobblestone and the other actual drop mappings instead of using
     * {@link Block#asItem()}, which would ask a timed gather task for an unobtainable stone item.
     */
    private static Task timedResourceMineTask(Block block) {
        Item drop = HarvestCore.expectedDropsFor(block).stream().findFirst().orElse(null);
        return drop == null ? new MineTask(block, 1) : GatherQuotaTask.collectForDuration(drop);
    }

    private static boolean optionalBoolean(JsonObject args, String name, boolean defaultValue) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive()) {
            return defaultValue;
        }
        return args.get(name).getAsBoolean();
    }

    /** The values of mine_ore's {@code mode} parameter (also the schema enum): anything else is rejected, never guessed. */
    static final List<String> MINE_ORE_MODES = List.of("count", "vein");

    private static String optionalString(JsonObject args, String name, String defaultValue) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive()) {
            return defaultValue;
        }
        String value = args.get(name).getAsString();
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static List<String> stringArray(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonArray()) {
            throw new IllegalArgumentException("missing_or_bad_arg: " + name);
        }
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (com.google.gson.JsonElement element : args.getAsJsonArray(name)) {
            if (!element.isJsonPrimitive()) {
                throw new IllegalArgumentException("missing_or_bad_arg: " + name);
            }
            String value = element.getAsString();
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("missing_or_bad_arg: " + name);
        }
        return values;
    }

    /** Parses the typed output manifest accepted by {@code fulfill_items}. */
    private static List<Goal.Allocation> requiredAllocations(JsonObject args, String name) {
        if (args == null || !args.has(name) || !args.get(name).isJsonArray()) {
            throw new IllegalArgumentException("missing_or_bad_arg: " + name);
        }
        List<Goal.Allocation> allocations = new java.util.ArrayList<>();
        for (com.google.gson.JsonElement element : args.getAsJsonArray(name)) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("missing_or_bad_arg: " + name);
            }
            JsonObject allocation = element.getAsJsonObject();
            int count = optionalInt(allocation, "count", 1);
            if (count <= 0) {
                throw new IllegalArgumentException("must_be_positive: items.count");
            }
            allocations.add(new Goal.Allocation(
                    requiredItem(allocation, "item"), count,
                    optionalString(allocation, "recipient", "")));
        }
        if (allocations.isEmpty()) {
            throw new IllegalArgumentException("missing_or_bad_arg: " + name);
        }
        return List.copyOf(allocations);
    }

    private static Map<String, String> paramsObject(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonObject()) {
            return Map.of();
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> entry : args.getAsJsonObject(name).entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                params.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return params;
    }

    private static BlockPos optionalBlockPos(JsonObject args, String xName, String yName, String zName) {
        if (!args.has(xName) && !args.has(yName) && !args.has(zName)) {
            return null;
        }
        return new BlockPos(requiredInt(args, xName), requiredInt(args, yName), requiredInt(args, zName));
    }

    private static boolean hasBlockPos(JsonObject args, String xName, String yName, String zName) {
        return args.has(xName) && args.has(yName) && args.has(zName);
    }

    private static Block requiredBlock(JsonObject args, String name) {
        Identifier id = Identifier.parse(requiredString(args, name));
        return BuiltInRegistries.BLOCK.getOptional(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown_block: " + id));
    }

    /** True when the player's block argument is the generic "leaves" (any leaf block) rather than one exact id. */
    static boolean isAnyLeavesRequest(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive()) {
            return false;
        }
        String value = args.get(name).getAsString().trim().toLowerCase(java.util.Locale.ROOT);
        return value.equals("leaves") || value.equals("minecraft:leaves") || value.equals("any_leaves");
    }

    private static Block requiredBreakableBlock(AIPlayerEntity bot, JsonObject args, String name) {
        Block block = requiredBlock(args, name);
        var state = block.defaultBlockState();
        if (state.isAir() || !state.getFluidState().isEmpty() || block.asItem() == Items.AIR
                || state.getDestroySpeed(bot.level(), bot.blockPosition()) < 0.0F) {
            throw new IllegalArgumentException("not_a_breakable_block: " + BuiltInRegistries.BLOCK.getKey(block));
        }
        return block;
    }

    private static Block blockWithAlias(JsonObject args, String primary, String alias) {
        if (args.has(primary) && args.get(primary).isJsonPrimitive()) {
            return requiredBlock(args, primary);
        }
        if (args.has(alias) && args.get(alias).isJsonPrimitive()) {
            return requiredBlock(args, alias);
        }
        throw new IllegalArgumentException("missing_or_bad_arg: " + primary);
    }

    private static Item requiredItem(JsonObject args, String name) {
        Identifier id = Identifier.parse(requiredString(args, name));
        return BuiltInRegistries.ITEM.getOptional(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown_item: " + id));
    }

    private static Item optionalItem(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive() || args.get(name).getAsString().isBlank()) {
            return null;
        }
        return requiredItem(args, name);
    }

    private static EntityType<?> requiredEntityType(JsonObject args, String name) {
        Identifier id = Identifier.parse(requiredString(args, name));
        return BuiltInRegistries.ENTITY_TYPE.getOptional(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown_entity_type: " + id));
    }

    private static Direction optionalDirection(JsonObject args, String name, Direction defaultValue) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive() || args.get(name).getAsString().isBlank()) {
            return defaultValue;
        }
        return switch (args.get(name).getAsString().toLowerCase(java.util.Locale.ROOT)) {
            case "north", "n" -> Direction.NORTH;
            case "south", "s" -> Direction.SOUTH;
            case "east", "e" -> Direction.EAST;
            case "west", "w" -> Direction.WEST;
            default -> throw new IllegalArgumentException("unknown_direction: " + args.get(name).getAsString());
        };
    }

    private static Set<Block> optionalBlocksCsv(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive() || args.get(name).getAsString().isBlank()) {
            return Set.of();
        }
        Set<Block> blocks = new HashSet<>();
        for (String token : args.get(name).getAsString().split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Identifier id = Identifier.parse(trimmed);
            blocks.add(BuiltInRegistries.BLOCK.getOptional(id)
                    .orElseThrow(() -> new IllegalArgumentException("unknown_block: " + id)));
        }
        return blocks;
    }

    // Resolve an "ore block id" or "raw ore item (raw_iron/iron_ore, etc.)" into the target ore family (including deepslate variants).
    static java.util.Set<Block> oreTargetsFrom(String oreOrItem) {
        Identifier id = Identifier.parse(oreOrItem.trim());
        Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (block != null && OreScan.isOreBlock(block)) {
            return OreScan.oreFamily(block);
        }
        String path = id.getPath().replace("raw_", "");
        for (String cand : new String[]{"minecraft:" + path + "_ore", "minecraft:" + path}) {
            Block b = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(cand)).orElse(null);
            if (b != null && OreScan.isOreBlock(b)) {
                return OreScan.oreFamily(b);
            }
        }
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        String correction = item == null ? "" : "; use achieve_goal with item=" + id;
        throw new IllegalArgumentException("unsupported_mine_ore_target: " + id + correction);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static JsonObject xyzSchema() {
        return objectSchema()
                .property("x", integerSchema("block x"))
                .property("y", integerSchema("block y"))
                .property("z", integerSchema("block z"))
                .required("x")
                .required("y")
                .required("z")
                .build();
    }

    private static JsonObject stringSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        schema.addProperty("description", description);
        return schema;
    }

    private static JsonObject enumStringSchema(String description, String... values) {
        JsonObject schema = stringSchema(description);
        com.google.gson.JsonArray allowed = new com.google.gson.JsonArray();
        for (String value : values) {
            allowed.add(value);
        }
        schema.add("enum", allowed);
        return schema;
    }

    private static JsonObject arrayOfStringsSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "array");
        schema.addProperty("description", description);
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        schema.add("items", items);
        return schema;
    }

    private static JsonObject allocationArraySchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "array");
        schema.addProperty("description", description);
        JsonObject allocation = objectSchema()
                .property("item", stringSchema("final vanilla or modded item id"))
                .property("count", integerSchema("how many of this item (default 1)", 1, Integer.MAX_VALUE))
                .property("recipient", stringSchema("player name to receive it; omit to retain on this bot"))
                .required("item")
                .build();
        schema.add("items", allocation);
        return schema;
    }

    private static JsonObject integerSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "integer");
        schema.addProperty("description", description);
        return schema;
    }

    private static JsonObject integerSchema(String description, int min, int max) {
        JsonObject schema = integerSchema(description);
        schema.addProperty("minimum", min);
        schema.addProperty("maximum", max);
        return schema;
    }

    private static JsonObject booleanSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "boolean");
        schema.addProperty("description", description);
        return schema;
    }

    private static ObjectSchemaBuilder objectSchema() {
        return new ObjectSchemaBuilder();
    }

    private static final class ObjectSchemaBuilder {
        private final JsonObject root = new JsonObject();
        private final JsonObject properties = new JsonObject();
        private final com.google.gson.JsonArray required = new com.google.gson.JsonArray();

        private ObjectSchemaBuilder() {
            root.addProperty("type", "object");
            root.add("properties", properties);
            root.add("required", required);
        }

        private ObjectSchemaBuilder property(String name, JsonObject schema) {
            properties.add(name, schema);
            return this;
        }

        private ObjectSchemaBuilder required(String name) {
            required.add(name);
            return this;
        }

        private JsonObject build() {
            return root;
        }
    }
}

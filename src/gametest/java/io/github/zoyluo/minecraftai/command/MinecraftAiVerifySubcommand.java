package io.github.zoyluo.minecraftai.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationGate;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.action.EatAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.brain.ActionDispatcher;
import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.brain.ChatToolCall;
import io.github.zoyluo.minecraftai.brain.ToolRegistry;
import io.github.zoyluo.minecraftai.coordination.TaskBoard;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.goal.Goal;
import io.github.zoyluo.minecraftai.goal.GoalEvaluation;
import io.github.zoyluo.minecraftai.goal.GoalExecutor;
import io.github.zoyluo.minecraftai.goal.GoalPlanner;
import io.github.zoyluo.minecraftai.goal.GoalPredicate;
import io.github.zoyluo.minecraftai.goal.GoalPredicates;
import io.github.zoyluo.minecraftai.goal.GoalSnapshot;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.memory.BotMemoryStore;
import io.github.zoyluo.minecraftai.mining.MiningBudget;
import io.github.zoyluo.minecraftai.mining.MiningFoodReserve;
import io.github.zoyluo.minecraftai.mining.MiningMissionBudget;
import io.github.zoyluo.minecraftai.mining.MiningEvidenceAudit;
import io.github.zoyluo.minecraftai.mining.ToolTier;
import io.github.zoyluo.minecraftai.mode.CapabilityPolicy;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.persist.BotPersistence;
import io.github.zoyluo.minecraftai.persist.MissionSpec;
import io.github.zoyluo.minecraftai.runtime.IntentController;
import io.github.zoyluo.minecraftai.task.BlueprintLoader;
import io.github.zoyluo.minecraftai.task.AbstractTask;
import io.github.zoyluo.minecraftai.task.BuildTask;
import io.github.zoyluo.minecraftai.task.CombatTask;
import io.github.zoyluo.minecraftai.task.DescendToYTask;
import io.github.zoyluo.minecraftai.task.DigDownTask;
import io.github.zoyluo.minecraftai.task.OreDigTask;
import io.github.zoyluo.minecraftai.task.ContainerTask;
import io.github.zoyluo.minecraftai.task.CraftTask;
import io.github.zoyluo.minecraftai.task.FarmTask;
import io.github.zoyluo.minecraftai.task.HoldTask;
import io.github.zoyluo.minecraftai.task.IrrigateTask;
import io.github.zoyluo.minecraftai.task.MineTask;
import io.github.zoyluo.minecraftai.task.MoveTask;
import io.github.zoyluo.minecraftai.task.RaidCropsTask;
import io.github.zoyluo.minecraftai.task.StripMineTask;
import io.github.zoyluo.minecraftai.task.Task;
import io.github.zoyluo.minecraftai.task.TaskManager;
import io.github.zoyluo.minecraftai.task.TaskState;
import io.github.zoyluo.minecraftai.task.TaskStatus;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class MinecraftAiVerifySubcommand {
    private static final int DIAMOND_STACK_TARGET = 64;
    private static final int OBSIDIAN_HALF_STACK_TARGET = 32;
    // User-facing commitment baseline: a full stack of 64 obsidian. The 32 contract stays as-is (frozen); 64 is its superset scenario.
    private static final int OBSIDIAN_STACK_TARGET =
            MiningEvidenceAudit.OBSIDIAN_STACK_TARGET;
    // from-zero 64 = the 32 version's fixed timeout + an incremental 32 blocks × CreateObsidian's per-block amortized budget (2,400 ticks).
    private static final int OBSIDIAN_STACK_64_FROM_ZERO_TIMEOUT =
            240_000 + 32 * 2_400;
    // prepared isolated pipeline: the 32 version's 24,000-tick contract, doubled at the same per-block rate.
    private static final int OBSIDIAN_STACK_64_PREPARED_TIMEOUT = 48_000;
    static final String STRICT_STRIP_MINE_REJECTION_FEATURE = "strip_mine_strict_rejection";

    private static final List<String> ALL_FEATURES = List.of(
            "capability_profile",
            "diamond_stack_64_controlled",
            "obsidian_half_stack_32_controlled",
            "obsidian_stack_64_controlled",
            "persist",
            "container",
            "combat",
            "farm",
            "strip_mine",
            "build",
            "memory",
            "job",
            "craft_chain",
            "drowning",
            "nav_obstacle",
            "nav_gap",
            "mine_to_iron",
            "mine_iron_from_scratch",
            "mine_buried_iron",
            "dig_down",
            "mine_exposed",
            "ore_dig_buried",
            "mine_iron_pocket",
            "mine_with_mob",
            "achieve_iron_ingot",
            "achieve_gold_ingot",
            "achieve_obsidian",
            "achieve_iron_pickaxe",
            "achieve_diamond",
            "iron_extreme",
            "diamond_extreme",
            "food_extreme",
            "achieve_armor",
            "achieve_workstation",
            "stockpile",
            "descend_to_ore",
            "move_dig_through",
            "farm_wheat_from_scratch",
            "nav_descend",
            "food",
            "food_full",
            "food_farm",
            "forage",
            "farm_irrigate",
            "cake",
            "village_harvest",
            "real_wood",
            "real_food",
            "real_wheat",
            "real_iron",
            "real_iron_bulk",
            "real_gold",
            "real_redstone",
            "real_diamond",
            "real_armor",
            "real_build",
            "real_obsidian",
            "real_nav_far",
            "nav_pillar_out",
            "nav_buried_escape",
            "nav_unreachable",
            "goal_queue",
            "goal_build_auto",
            "goal_build_custom",
            "msg_keep_goal",
            "cancel_no_resurrection",
            "cancel_current_queue",
            "replace_queued_goal",
            "replace_action_only",
            "replace_start_failure",
            "tool_dispatch",
            "knowledge_smoke",
            "craft_runtime",
            "geo_vertical",
            "geo_slope",
            "geo_overhang",
            "geo_wall",
            "geo_pocket",
            "geo_deep",
            "geo_lava",
            "geo_gravel",
            "geo_fullinv",
            "geo_rich", "geo_water", "geo_recover", "geo_bonus", "geo_stockpile", "geo_resume", "geo_shaft", "geo_cave", "geo_diamond_lava", "geo_obsidian_make", "geo_cliff_tree", "geo_night_swarm", "geo_replay_ore",
            "geo_flow", "geo_lake", "geo_guard", "explore_wood");

    // Mining First's fast capability contract: only verifies the 63/64 and 31/32 postcondition boundaries and the MissionSpec round trip;
    // it does not pre-place ore, does not run a long expedition, and never presents a PASS as real mining capability. PR CI can finish reliably in seconds.
    private static final List<String> MINING_CONTRACT_SUITE = List.of(
            "diamond_stack_64_controlled",
            "obsidian_half_stack_32_controlled");

    // The two tiers below are both explicit opt-in and never enter verify all / mining / PR CI. "prepared" isolates the tool chain from natural resource variance,
    // while "from_zero" is the final user-commitment baseline bound to the capability manifest. Either tier may honestly FAIL while the capability is still incomplete.
    private static final List<String> MINING_ACCEPTANCE_PREPARED_SUITE = List.of(
            "diamond_stack_64_prepared",
            "obsidian_half_stack_32_prepared",
            "obsidian_stack_64_prepared");
    private static final List<String> MINING_ACCEPTANCE_FROM_ZERO_SUITE = List.of(
            "diamond_stack_64_from_zero",
            "obsidian_half_stack_32_from_zero",
            "obsidian_stack_64_from_zero");
    private static final List<String> OPT_IN_LONG_MINING_FEATURES = List.of(
            "diamond_stack_64_prepared",
            "obsidian_half_stack_32_prepared",
            "obsidian_stack_64_prepared",
            "diamond_stack_64_from_zero",
            "obsidian_half_stack_32_from_zero",
            "obsidian_stack_64_from_zero");

    // Mining regression suite: one command, /minecraftai verify mining, runs every mining-related scenario.
    private static final List<String> MINING_SUITE = List.of(
            "diamond_stack_64_controlled",
            "obsidian_half_stack_32_controlled",
            "dig_down",
            "mine_exposed",
            "ore_dig_buried",
            "mine_to_iron",
            "mine_buried_iron",
            "mine_iron_pocket",
            "mine_with_mob",
            "mine_iron_from_scratch",
            "achieve_iron_ingot",
            "achieve_gold_ingot",
            "achieve_obsidian",
            "achieve_iron_pickaxe",
            "achieve_diamond",
            "geo_recover",
            "geo_stockpile",
            "geo_resume",
            "geo_guard");

    // Food regression suite: one command, /minecraftai verify food_suite, runs every food/farming-related scenario.
    // Covers five food-acquisition paths: hunting + cooking (food/food_full), farming for bread (food_farm), foraging (forage),
    // infinite-water-source irrigation (farm_irrigate), crafting cake (cake), and village crop raiding (village_harvest), plus the farming primitive (farm/farm_wheat).
    private static final List<String> FOOD_SUITE = List.of(
            "food",
            "food_full",
            "farm",
            "farm_wheat_from_scratch",
            "food_farm",
            "forage",
            "farm_irrigate",
            "cake",
            "village_harvest");

    // Mineral material regression suite: one command, /minecraftai verify material_suite, runs all four target minerals: iron ingot/gold ingot/diamond/obsidian.
    private static final List<String> MATERIAL_SUITE = List.of(
            "achieve_iron_ingot",
            "achieve_gold_ingot",
            "achieve_diamond",
            "achieve_obsidian");

    // Extreme-environment regression suite: mineral/food goals must still be completed under "mob siege + deep darkness". /minecraftai verify extreme_suite
    private static final List<String> EXTREME_SUITE = List.of(
            "iron_extreme",
            "diamond_extreme",
            "food_extreme");

    // Terrain matrix suite (②): the same mining task × six geometries, a unified proving ground for the approach primitive. /minecraftai verify geo_suite
    private static final List<String> GEO_SUITE = List.of(
            "geo_vertical", "geo_slope", "geo_overhang", "geo_wall", "geo_pocket", "geo_deep",
            "geo_lava", "geo_gravel", "geo_fullinv", "geo_rich", "geo_water", "geo_bonus",
            "geo_flow", "geo_lake");

    // Close-to-real-play suite: natural world, empty inventory, nothing granted — complete the goal entirely from scratch. /minecraftai verify real_suite
    // A failure here = a real gap between automation and actual play, to be fixed one by one; real_obsidian is expected to FAIL (the pour-water-to-make-obsidian capability isn't implemented yet).
    private static final List<String> REAL_SUITE = List.of(
            "real_wood",
            "real_food",
            "real_wheat",
            "real_iron",
            "real_iron_bulk",
            "real_gold",
            "real_redstone",
            "real_diamond",
            "real_armor",
            "real_obsidian");

    // Pathfinding fault-tolerance suite: /minecraftai verify nav_suite. Its four cases each pin down a high-frequency real-play failure mode:
    // long-distance detouring over natural terrain (real_nav_far), pillaring up and over a wall when trapped (nav_pillar_out), escaping suffocation when buried alive (nav_buried_escape),
    // and quickly giving up on an unreachable target (nav_unreachable). The first three test "can it save itself"; the last tests "can it admit defeat" —
    // spinning without ever erroring is worse than a clean failure: in real play the bot looks like it's working while actually just spinning in place, wasting the whole run.
    private static final List<String> NAV_SUITE = List.of(
            "real_nav_far",
            "nav_pillar_out",
            "nav_buried_escape",
            "nav_unreachable");

    // R2 LLM full-chain-layer suite: colloquial-language instructions go through the real LLM brain (intent parsing → tool selection → parameterization → execution),
    // the exact same code path as a player chatting @bot (BrainCoordinator.handleMessage). It burns real API money:
    // deliberately excluded from ALL_FEATURES (verify all should never bill silently), so it must be named explicitly, /minecraftai verify llm_suite (or a single case),
    // and run with WITH_LLM=1 (the test script unsets MINECRAFTAI_LLM_API_KEY by default to isolate the brain).
    private static final List<String> LLM_SUITE = List.of(
            "llm_move",
            "llm_food",
            "llm_iron",
            "llm_diamond");

    // Conversational assistant-layer suite: /minecraftai verify assistant_suite. Verifies four new foundations of the assistant layer (previously only compiled, never run-verified):
    // P0 goal queue (consecutive instructions auto-queue and chain), P1 Goal.Build auto-provisioning (given only raw logs, it computes materials and crafts on its own),
    // P3 parameterized blueprints (custom:WxDxH:material), and P2 player messages that don't clear the in-progress goal (interrupt-preserving semantics).
    // All deterministic lab scenarios; none go through the LLM or burn API calls (the brain-driven full chain is covered separately by llm_suite).
    private static final List<String> ASSISTANT_SUITE = List.of(
            "goal_queue",
            "goal_build_auto",
            "goal_build_custom",
            "msg_keep_goal");

    // Runtime control suite: cancellation must clear every resurrection source; cancel-current preserves and promotes the queue;
    // an LLM batch of stop + replacement must start the replacement exactly once.
    private static final List<String> RUNTIME_CONTROL_SUITE = List.of(
            "cancel_no_resurrection",
            "cancel_current_queue",
            "replace_queued_goal",
            "replace_action_only",
            "replace_start_failure",
            "pause_resume_safety_stack");
    private static final Map<UUID, VerifyRun> RUNS = new ConcurrentHashMap<>();
    // Scenario spatial-isolation counter: each scenario rotates to a new plot along the x axis, preventing cross-contamination between scenarios in a suite (see the prepareArea comment for details).
    private static int scenarioSlot = 0;

    private MinecraftAiVerifySubcommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return literal("verify")
                .executes(context -> start(context.getSource(), List.of("all")))
                .then(literal("all")
                        .executes(context -> start(context.getSource(), List.of("all"))))
                .then(argument("feature", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            ALL_FEATURES.forEach(builder::suggest);
                            builder.suggest("all");
                            builder.suggest("mining");
                            builder.suggest("food_suite");
                            builder.suggest("real_suite");
                            builder.suggest("nav_suite");
                            builder.suggest("llm_suite");
                            builder.suggest("assistant_suite");
                            builder.suggest("runtime_control_suite");
                            builder.suggest("mining_contract_suite");
                            builder.suggest("mining_acceptance_prepared_suite");
                            builder.suggest("mining_acceptance_from_zero_suite");
                            builder.suggest("mining_acceptance_suite");
                            OPT_IN_LONG_MINING_FEATURES.forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            String feature = StringArgumentType.getString(context, "feature");
                            // "all"/"mining" group aliases are expanded in start→expandFeatures; a single case just passes its name through.
                            return start(context.getSource(), List.of(feature));
                        }));
    }

    public static void tick(MinecraftServer server) {
        for (VerifyRun run : new ArrayList<>(RUNS.values())) {
            if (run.tick(server)) {
                RUNS.remove(run.botId());
            }
        }
    }

    private static int start(CommandSourceStack source, List<String> requested) {
        if (!BotAuthorizationGate.INSTANCE.requireGlobalAdmin(source, "command:verify")) {
            return 0;
        }
        Optional<AIPlayerEntity> bot = selectBot(source);
        if (bot.isEmpty()) {
            source.sendFailure(Component.literal("[MinecraftAi Verify] FAIL no_bot: spawn a bot first with /minecraftai spawn <name>"));
            return 0;
        }
        List<String> features = expandFeatures(requested);
        if (features.isEmpty()) {
            source.sendFailure(Component.literal("[MinecraftAi Verify] unknown feature. Available: " + String.join(", ", ALL_FEATURES)));
            return 0;
        }
        UUID botId = bot.get().getUUID();
        if (RUNS.containsKey(botId)) {
            source.sendFailure(Component.literal("[MinecraftAi Verify] already running for " + bot.get().getGameProfile().name()));
            return 0;
        }
        VerifyRun run = new VerifyRun(source, botId, features);
        RUNS.put(botId, run);
        source.sendSuccess(() -> Component.literal("[MinecraftAi Verify] started for "
                + bot.get().getGameProfile().name()
                + ": "
                + String.join(", ", features)), false);
        return 1;
    }

    // Package-private deterministic hooks for the verifier's own GameTest. This command lives only
    // in the isolated gametest source set, so exercising the real VerifyRun state machine is both
    // cheaper and stronger than duplicating its polling semantics in a unit-test facade.
    static boolean startForGameTest(CommandSourceStack source, AIPlayerEntity bot, String feature) {
        boolean supportedFeature = MINING_ACCEPTANCE_FROM_ZERO_SUITE.contains(feature)
                || STRICT_STRIP_MINE_REJECTION_FEATURE.equals(feature);
        if (!supportedFeature || RUNS.containsKey(bot.getUUID())) {
            return false;
        }
        RUNS.put(bot.getUUID(), new VerifyRun(source, bot.getUUID(), List.of(feature)));
        return true;
    }

    static Optional<String> resultDetailForGameTest(UUID botId, String feature) {
        VerifyRun run = RUNS.get(botId);
        if (run == null) {
            return Optional.empty();
        }
        return run.results.stream()
                .filter(result -> result.feature().equals(feature))
                .map(Result::detail)
                .findFirst();
    }

    static boolean hasRunForGameTest(UUID botId) {
        return RUNS.containsKey(botId);
    }

    static void discardRunForGameTest(UUID botId) {
        RUNS.remove(botId);
        MiningEvidenceAudit.clear(botId);
    }

    private static Optional<AIPlayerEntity> selectBot(CommandSourceStack source) {
        return Optional.ofNullable(source.getPlayer())
                .flatMap(player -> AIPlayerManager.INSTANCE.botOf(player.getUUID()))
                .or(() -> AIPlayerManager.INSTANCE.all().stream().findFirst());
    }

    private static List<String> expandFeatures(List<String> requested) {
        return expandFeatures(requested, MinecraftAiConfig.get().profile());
    }

    static List<String> expandFeaturesForGameTest(List<String> requested, OperatingProfile profile) {
        return expandFeatures(requested, profile);
    }

    private static List<String> expandFeatures(List<String> requested, OperatingProfile profile) {
        OperatingProfile effectiveProfile = profile == OperatingProfile.OPERATOR
                ? OperatingProfile.OPERATOR
                : OperatingProfile.STRICT_SURVIVAL;
        List<String> features = new ArrayList<>();
        for (String raw : requested) {
            String feature = raw.toLowerCase(java.util.Locale.ROOT);
            if (feature.contains("+")) {
                // Plus-sign combos: chain any scenarios/suites into one run (most often used to diagnose the order-pollution pattern of 'passes alone, fails in a suite';
                // Brigadier's word() character set excludes commas, hence the + separator).
                features.addAll(expandFeatures(
                        java.util.Arrays.asList(feature.split("\\+")), effectiveProfile));
            } else if ("all".equals(feature)) {
                addFeaturesForProfile(features, ALL_FEATURES, effectiveProfile);
            } else if ("mining".equals(feature)) {
                features.addAll(MINING_SUITE); // mining regression suite alias
            } else if ("food_suite".equals(feature)) {
                features.addAll(FOOD_SUITE); // food regression suite alias
            } else if ("material_suite".equals(feature)) {
                features.addAll(MATERIAL_SUITE); // mineral material regression suite alias
            } else if ("extreme_suite".equals(feature)) {
                features.addAll(EXTREME_SUITE); // extreme-environment regression suite alias
            } else if ("real_suite".equals(feature)) {
                features.addAll(REAL_SUITE); // close-to-real-play suite alias
            } else if ("geo_suite".equals(feature)) {
                features.addAll(GEO_SUITE); // terrain matrix suite alias
            } else if ("nav_suite".equals(feature)) {
                features.addAll(NAV_SUITE); // pathfinding fault-tolerance suite alias
            } else if ("llm_suite".equals(feature)) {
                features.addAll(LLM_SUITE); // R2 LLM full-chain-layer suite alias (real LLM, billed, requires WITH_LLM=1)
            } else if ("assistant_suite".equals(feature)) {
                features.addAll(ASSISTANT_SUITE); // conversational assistant-layer suite alias (P0 queue / P1 auto-provisioning / P3 parameterization / P2 interrupt-preserving)
            } else if ("runtime_control_suite".equals(feature)) {
                features.addAll(RUNTIME_CONTROL_SUITE); // P0 atomic cancel/replace; deterministic and never calls the LLM
            } else if ("mining_contract_suite".equals(feature)) {
                features.addAll(MINING_CONTRACT_SUITE); // second-scale quantity/persistence/postcondition contract; does not represent real mining
            } else if ("mining_acceptance_prepared_suite".equals(feature)) {
                features.addAll(MINING_ACCEPTANCE_PREPARED_SUITE); // explicit opt-in long run: pre-equips non-target gear
            } else if ("mining_acceptance_from_zero_suite".equals(feature)) {
                features.addAll(MINING_ACCEPTANCE_FROM_ZERO_SUITE); // explicit opt-in final capability baseline
            } else if ("mining_acceptance_suite".equals(feature)) {
                features.addAll(MINING_CONTRACT_SUITE);
                features.addAll(MINING_ACCEPTANCE_PREPARED_SUITE);
                features.addAll(MINING_ACCEPTANCE_FROM_ZERO_SUITE);
            } else if (ALL_FEATURES.contains(feature) || LLM_SUITE.contains(feature)
                       || OPT_IN_LONG_MINING_FEATURES.contains(feature)
                       || "real_diamond3".equals(feature)) {
                // llm_* and long-running mining are deliberately excluded from ALL_FEATURES; they must be named explicitly, so a plain verify all never bills silently or runs for hours.
                addFeatureForProfile(features, feature, effectiveProfile);
            }
        }
        return List.copyOf(new java.util.LinkedHashSet<>(features));
    }

    private static void addFeaturesForProfile(List<String> destination,
                                              List<String> candidates,
                                              OperatingProfile profile) {
        candidates.forEach(feature -> addFeatureForProfile(destination, feature, profile));
    }

    private static void addFeatureForProfile(List<String> destination,
                                             String feature,
                                             OperatingProfile profile) {
        if ("strip_mine".equals(feature) && profile == OperatingProfile.STRICT_SURVIVAL) {
            destination.add(STRICT_STRIP_MINE_REJECTION_FEATURE);
            return;
        }
        destination.add(feature);
    }

    private static Result startScenario(CommandSourceStack source, AIPlayerEntity bot, String feature) throws IOException {
        // Uniformly clear execution state before each scenario starts: when the previous scenario's assertion is satisfied and it's judged PASS, the goal may still have steps left running
        // (runningGoal's assertion ≠ goal completion); an active plan would reject this scenario's submit (observed as forage's
        // goal_submit_failed) or leak a leftover task into it. Every scenario starts from a clean execution state — fixing this in one place stops leakage between all scenarios.
        IntentController.INSTANCE.cancelAll(bot, IntentController.ControlOrigin.SYSTEM, "verify_scenario_reset");
        return switch (feature) {
            case "capability_profile" -> verifyCapabilityProfile(bot);
            case "diamond_stack_64_controlled" -> verifyMiningCountContract(
                    "diamond_stack_64_controlled", Items.DIAMOND, DIAMOND_STACK_TARGET);
            case "obsidian_half_stack_32_controlled" -> verifyMiningCountContract(
                    "obsidian_half_stack_32_controlled", Items.OBSIDIAN, OBSIDIAN_HALF_STACK_TARGET);
            case "obsidian_stack_64_controlled" -> verifyMiningCountContract(
                    "obsidian_stack_64_controlled", Items.OBSIDIAN, OBSIDIAN_STACK_TARGET);
            case "persist" -> verifyPersist(source);
            case "memory" -> verifyMemory(bot);
            case "job" -> verifyJob();
            case "container" -> assignContainer(bot);
            case "combat" -> assignCombat(bot);
            case "farm" -> assignFarm(bot);
            case "strip_mine" -> assignStripMine(bot);
            case STRICT_STRIP_MINE_REJECTION_FEATURE -> assignStripMineStrictRejection(bot);
            case "build" -> assignBuild(bot);
            case "craft_chain" -> assignCraftChain(bot);
            case "drowning" -> verifyDrowning(bot);
            case "nav_obstacle" -> assignNavObstacle(bot);
            case "nav_gap" -> assignNavGap(bot);
            case "mine_to_iron" -> assignMineToIron(bot);
            case "mine_iron_from_scratch" -> assignMineIronFromScratch(bot);
            case "mine_buried_iron" -> assignMineBuriedIron(bot);
            case "dig_down" -> assignDigDown(bot);
            case "mine_exposed" -> assignMineExposed(bot);
            case "ore_dig_buried" -> assignOreDigBuried(bot);
            case "mine_iron_pocket" -> assignMineIronPocket(bot);
            case "mine_with_mob" -> assignMineWithMob(bot);
            case "achieve_iron_ingot" -> assignAchieveIronIngot(bot);
            case "achieve_gold_ingot" -> assignAchieveGoldIngot(bot);
            case "achieve_obsidian" -> assignAchieveObsidian(bot);
            case "iron_extreme" -> assignIronExtreme(bot);
            case "diamond_extreme" -> assignDiamondExtreme(bot);
            case "food_extreme" -> assignFoodExtreme(bot);
            case "achieve_iron_pickaxe" -> assignAchieveIronPickaxe(bot);
            case "achieve_diamond" -> assignAchieveDiamond(bot);
            case "achieve_armor" -> assignAchieveArmor(bot);
            case "achieve_workstation" -> assignAchieveWorkstation(bot);
            case "stockpile" -> assignStockpile(bot);
            case "descend_to_ore" -> assignDescendToOre(bot);
            case "move_dig_through" -> assignMoveDigThrough(bot);
            case "farm_wheat_from_scratch" -> assignFarmWheatFromScratch(bot);
            case "nav_descend" -> assignNavDescend(bot);
            case "food" -> assignAchieveFood(bot);
            case "food_full" -> assignAchieveFoodFull(bot);
            case "food_farm" -> assignAchieveFoodFarm(bot);
            case "forage" -> assignForage(bot);
            case "farm_irrigate" -> assignFarmIrrigate(bot);
            case "cake" -> assignCake(bot);
            case "village_harvest" -> assignVillageHarvest(bot);
            case "real_wood" -> assignRealWood(bot);
            case "real_food" -> assignRealFood(bot);
            case "real_wheat" -> assignRealWheat(bot);
            case "real_iron" -> assignRealIron(bot);
            case "real_iron_bulk" -> assignRealIronBulk(bot);
            case "real_gold" -> assignRealGold(bot);
            case "real_redstone" -> assignRealRedstone(bot);
            case "real_diamond" -> assignRealDiamond(bot);
            case "real_diamond3" -> assignRealDiamond3(bot);
            case "diamond_stack_64_prepared" -> assignDiamondStack64Prepared(bot);
            case "diamond_stack_64_from_zero" -> assignDiamondStack64FromZero(bot);
            case "real_armor" -> assignRealArmor(bot);
            case "real_build" -> assignRealBuild(bot);
            case "real_obsidian" -> assignRealObsidian(bot);
            case "obsidian_half_stack_32_prepared" -> assignObsidianHalfStack32Prepared(bot);
            case "obsidian_stack_64_prepared" -> assignObsidianStack64Prepared(bot);
            case "obsidian_stack_64_from_zero" -> assignObsidianStack64FromZero(bot);
            case "obsidian_half_stack_32_from_zero" -> assignObsidianHalfStack32FromZero(bot);
            case "llm_move" -> assignLlmMove(bot);
            case "llm_food" -> assignLlmFood(bot);
            case "llm_iron" -> assignLlmIron(bot);
            case "llm_diamond" -> assignLlmDiamond(bot);
            case "real_nav_far" -> assignRealNavFar(bot);
            case "nav_pillar_out" -> assignNavPillarOut(bot);
            case "nav_buried_escape" -> assignNavBuriedEscape(bot);
            case "nav_unreachable" -> assignNavUnreachable(bot);
            case "goal_queue" -> assignGoalQueue(bot);
            case "goal_build_auto" -> assignGoalBuildAuto(bot);
            case "goal_build_custom" -> assignGoalBuildCustom(bot);
            case "msg_keep_goal" -> assignMsgKeepGoal(bot);
            case "cancel_no_resurrection" -> assignCancelNoResurrection(bot);
            case "cancel_current_queue" -> verifyCancelCurrentQueue(bot);
            case "replace_queued_goal" -> verifyReplaceQueuedGoal(bot);
            case "replace_action_only" -> verifyReplaceActionOnly(bot);
            case "replace_start_failure" -> verifyReplaceStartFailure(bot);
            case "pause_resume_safety_stack" -> verifyPauseResumeSafetyStack(bot);
            case "tool_dispatch" -> assignToolDispatch(bot);
            case "knowledge_smoke" -> assignKnowledgeSmoke(bot);
            case "craft_runtime" -> assignCraftRuntime(bot);
            case "geo_vertical" -> assignMineGeo(bot, "vertical");
            case "geo_slope" -> assignMineGeo(bot, "slope");
            case "geo_overhang" -> assignMineGeo(bot, "overhang");
            case "geo_wall" -> assignMineGeo(bot, "wall");
            case "geo_pocket" -> assignMineGeo(bot, "pocket");
            case "geo_deep" -> assignMineGeo(bot, "deep");
            case "geo_lava" -> assignMineGeo(bot, "lava");
            case "geo_gravel" -> assignMineGeo(bot, "gravel");
            case "geo_fullinv" -> assignMineGeo(bot, "fullinv");
            case "geo_rich" -> assignGeoRich(bot);
            case "geo_water" -> assignMineGeo(bot, "water");
            case "geo_shaft" -> assignMineGeo(bot, "shaft");
            case "geo_cave" -> assignMineGeo(bot, "cave");
            case "geo_diamond_lava" -> assignGeoDiamondLava(bot);
            case "geo_replay_ore" -> assignGeoReplayOre(bot);
            case "geo_obsidian_make" -> assignGeoObsidianMake(bot);
            case "geo_cliff_tree" -> assignGeoCliffTree(bot);
            case "geo_night_swarm" -> assignGeoNightSwarm(bot);
            case "geo_flow" -> assignMineGeo(bot, "flow");
            case "geo_lake" -> assignMineGeo(bot, "lake");
            case "geo_recover" -> assignGeoRecover(bot);
            case "geo_bonus" -> assignGeoBonus(bot);
            case "geo_stockpile" -> assignGeoStockpile(bot);
            case "geo_resume" -> assignGeoResume(bot);
            case "geo_guard" -> assignGeoGuard(bot);
            case "explore_wood" -> assignExploreWood(bot);
            default -> Result.fail(feature, "unknown_feature");
        };
    }

    private static Result verifyCapabilityProfile(AIPlayerEntity bot) {
        MinecraftAiConfig config = MinecraftAiConfig.get();
        AtomicInteger sideEffects = new AtomicInteger();
        int expectedExecutions = 0;
        boolean matched = true;
        for (PrivilegedCapability capability : PrivilegedCapability.values()) {
            boolean expectedAllowed = CapabilityPolicy.decide(
                    config.profile(), config.operatorCapabilities(), capability).allowed();
            boolean executed = CapabilityRuntime.run(
                    bot, capability, "verify_capability_profile", sideEffects::incrementAndGet);
            matched &= executed == expectedAllowed;
            if (expectedAllowed) {
                expectedExecutions++;
            }
        }
        // Ordinary navigation from an already valid start is not an emergency teleport. This
        // guards against accidentally putting the capability gate before the standability check.
        var ordinaryPath = bot.getActionPack().startPathTo(bot.blockPosition());
        boolean ordinaryPathAllowed = !ordinaryPath.isFailed();
        bot.getActionPack().stopAll();
        boolean pass = matched && sideEffects.get() == expectedExecutions && ordinaryPathAllowed;
        String detail = "profile=" + config.profile().configValue()
                + " executed=" + sideEffects.get()
                + " expected=" + expectedExecutions
                + " ordinary_path=" + (ordinaryPathAllowed ? "allowed" : ordinaryPath.reason());
        return pass ? Result.pass("capability_profile", detail)
                : Result.fail("capability_profile", detail);
    }

    /**
     * M0 Mining First contract only. This intentionally does not mine or grant target items: it
     * proves that the exact public quantity survives MissionSpec persistence and that the typed
     * postcondition rejects target-1 while accepting target. Long-running prepared/from-zero
     * scenarios are separate opt-in tests, so a fast contract PASS can never be read as gameplay
     * certification.
     */
    private static Result verifyMiningCountContract(String feature, Item item, int target) {
        Goal goal = new Goal.HaveItem(item, target);
        Optional<Goal> restored = MissionSpec.fromGoal(goal).toGoal();
        String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
        GoalPredicate predicate = GoalPredicates.forGoal(goal);
        GoalSnapshot below = new GoalSnapshot(
                Map.of(itemId, target - 1), 0, java.util.Set.of(), Map.of(), Map.of(), 0, Optional.empty());
        GoalSnapshot exact = new GoalSnapshot(
                Map.of(itemId, target), 0, java.util.Set.of(), Map.of(), Map.of(), 0, Optional.empty());
        GoalEvaluation belowEvaluation = predicate.evaluate(below);
        GoalEvaluation exactEvaluation = predicate.evaluate(exact);
        boolean pass = restored.isPresent()
                && restored.get().equals(goal)
                && belowEvaluation.state() == GoalEvaluation.State.UNSATISFIED
                && belowEvaluation.matched() == target - 1
                && belowEvaluation.required() == target
                && exactEvaluation.state() == GoalEvaluation.State.SATISFIED
                && exactEvaluation.matched() == target
                && exactEvaluation.required() == target;
        String detail = "contract_only item=" + itemId
                + " target=" + target
                + " below=" + belowEvaluation.matched() + "/" + belowEvaluation.required()
                + " exact=" + exactEvaluation.matched() + "/" + exactEvaluation.required()
                + " mission_roundtrip=" + (restored.isPresent() && restored.get().equals(goal))
                + " execution=not_tested";
        return pass ? Result.pass(feature, detail) : Result.fail(feature, detail);
    }

    private static Result verifyPersist(CommandSourceStack source) {
        int saved = BotPersistence.INSTANCE.saveAll(source.getServer());
        return Result.pass("persist", "saveAll ok, bots=" + saved);
    }

    private static Result verifyMemory(AIPlayerEntity bot) {
        String key = "verify_" + bot.getUUID();
        BotMemoryStore.INSTANCE.of(bot.getUUID()).remember(key, "ok");
        boolean found = BotMemoryStore.INSTANCE.of(bot.getUUID()).recall(key).filter("ok"::equals).isPresent();
        BotMemoryStore.INSTANCE.of(bot.getUUID()).forget(key);
        return found ? Result.pass("memory", "remember/recall/forget ok") : Result.fail("memory", "recall_mismatch");
    }

    private static Result verifyJob() {
        UUID id = TaskBoard.INSTANCE.postGlobal("verify", Map.of("feature", "job"));
        boolean found = TaskBoard.INSTANCE.snapshot().stream().anyMatch(job -> job.id().equals(id));
        if (found) {
            TaskBoard.INSTANCE.markDone(id);
            return Result.pass("job", "post/snapshot/markDone ok");
        }
        return Result.fail("job", "posted_job_missing");
    }

    private static Result assignContainer(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        BlockPos chest = bot.blockPosition().relative(Direction.NORTH);
        bot.level().setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 3));
        Task task = ContainerTask.deposit(chest, Items.COBBLESTONE, 3, false);
        return assignTask(bot, "container", task, 200, ignored -> countContainer(bot, chest, Items.COBBLESTONE) >= 3);
    }

    private static Result assignCombat(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_SWORD, 1));
        ServerLevel world = bot.level();
        Zombie zombie = EntityType.ZOMBIE.create(world, EntitySpawnReason.COMMAND);
        if (zombie == null) {
            return Result.fail("combat", "zombie_create_failed");
        }
        zombie.snapTo(bot.getX() + 2.0D, bot.getY(), bot.getZ(), 0.0F, 0.0F);
        world.addFreshEntity(zombie);
        // Perception: the bot looks at the zombie, so it notices it after the reaction time instead of only when it is struck.
        io.github.zoyluo.minecraftai.action.LookAction.lookAt(bot, zombie.getEyePosition());
        return assignTask(bot, "combat", new CombatTask(EntityType.ZOMBIE, 1, MinecraftAiConfig.get().combat().retreatHp()),
                600,
                ignored -> !zombie.isAlive());
    }

    private static Result assignFarm(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        BlockPos farm = bot.blockPosition().relative(Direction.EAST);
        bot.level().setBlock(farm, Blocks.FARMLAND.defaultBlockState(), Block.UPDATE_ALL);
        bot.level().setBlock(farm.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.WHEAT_SEEDS, 4));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_HOE, 1));
        return assignTask(bot, "farm", new FarmTask(farm, 1, Items.WHEAT_SEEDS, Blocks.WHEAT, false, false),
                300,
                ignored -> bot.level().getBlockState(farm.above()).is(Blocks.WHEAT));
    }

    private static Result assignStripMine(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_PICKAXE, 1));
        Direction direction = Direction.NORTH;
        for (int distance = 1; distance <= 2; distance++) {
            bot.level().setBlock(bot.blockPosition().relative(direction, distance), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            bot.level().setBlock(bot.blockPosition().relative(direction, distance).above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        return assignTask(bot, "strip_mine", new StripMineTask(direction, 2, 0, null, java.util.Set.of()),
                800,
                status -> status.progress() >= 1.0D);
    }

    /**
     * Strict-survival still verifies the legacy boundary instead of silently dropping it from
     * {@code verify all}: the real task must fail immediately with the exact typed reason. The
     * operator profile never expands to this scenario and continues to run {@link #assignStripMine}.
     */
    private static Result assignStripMineStrictRejection(AIPlayerEntity bot) {
        OperatingProfile profile = MinecraftAiConfig.get().profile();
        Optional<String> declaredRejection = StripMineTask.profileRejectionReason(profile);
        if (!declaredRejection.filter(StripMineTask.STRICT_SURVIVAL_REJECTION::equals).isPresent()) {
            return Result.fail(STRICT_STRIP_MINE_REJECTION_FEATURE,
                    "profile_contract_mismatch profile=" + profile
                            + " expected=" + StripMineTask.STRICT_SURVIVAL_REJECTION
                            + " actual=" + declaredRejection.orElse("allowed"));
        }
        TaskManager.INSTANCE.assign(bot,
                new StripMineTask(Direction.NORTH, 2, 0, null, java.util.Set.of()),
                io.github.zoyluo.minecraftai.runtime.TaskOrigin.of(
                        io.github.zoyluo.minecraftai.runtime.TaskOrigin.Kind.VERIFY,
                        "strict_strip_mine_rejection"));
        return Result.runningExpectTypedFail(
                STRICT_STRIP_MINE_REJECTION_FEATURE,
                20,
                StripMineTask.STRICT_SURVIVAL_REJECTION);
    }

    private static Result assignBuild(AIPlayerEntity bot) throws IOException {
        prepareArea(bot);
        clearInventory(bot);
        // small_hut measured needs 114 planks (floor 25 + walls 66 - door 2 + roof 25); the original 64 planks ran out of material halfway through, missing_material
        // (BuildTask only consumes finished materials, it doesn't craft them; auto-provisioning is the Goal.Build chain's job — this scenario tests pure building).
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_PLANKS, 128));
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 32));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 32));
        return assignTask(bot, "build", new BuildTask(BlueprintLoader.load("small_hut"), null, true, false),
                2400,
                status -> status.progress() >= 1.0D);
    }

    private static Result assignCraftChain(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_INGOT, 3));
        return assignTask(bot, "craft_chain", new CraftTask(Items.IRON_PICKAXE, 1),
                500,
                ignored -> InventoryAction.countItem(bot, Items.IRON_PICKAXE) >= 1);
    }

    private static Result verifyDrowning(AIPlayerEntity bot) {
        Task task = new io.github.zoyluo.minecraftai.task.EvadeTask(new io.github.zoyluo.minecraftai.task.Threat(
                io.github.zoyluo.minecraftai.task.Threat.Type.DROWNING,
                io.github.zoyluo.minecraftai.task.Threat.Severity.MEDIUM,
                null,
                bot.blockPosition()));
        return assignTask(bot, "drowning", task, 300, status -> status.state() == TaskState.COMPLETED);
    }

    private static Result assignNavObstacle(AIPlayerEntity bot) {
        prepareArea(bot);
        BlockPos origin = bot.blockPosition();
        BlockPos obstacle = origin.relative(Direction.NORTH);
        BlockPos goal = origin.relative(Direction.NORTH, 3);
        bot.level().setBlock(obstacle, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
        return assignTask(bot, "nav_obstacle", new MoveTask(bot, goal), 400,
                ignored -> bot.blockPosition().distSqr(goal) <= 4.0D);
    }

    private static Result assignNavGap(AIPlayerEntity bot) {
        prepareArea(bot);
        BlockPos origin = bot.blockPosition();
        BlockPos gap = origin.relative(Direction.NORTH);
        BlockPos goal = origin.relative(Direction.NORTH, 3);
        bot.level().setBlock(gap.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        return assignTask(bot, "nav_gap", new MoveTask(bot, goal), 400,
                ignored -> bot.blockPosition().distSqr(goal) <= 4.0D);
    }

    /**
     * REGRESSION(P1-a): MineTask goes through BlockMiner to mine a designated **exposed** block.
     * Give a stone pickaxe, place one exposed iron ore directly in front, and assert raw_iron is obtained — verifies MineTask's "find nearest exposed block → mine" still works correctly under the new primitive.
     */
    private static Result assignMineExposed(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        BlockPos ore = bot.blockPosition().relative(Direction.NORTH, 2);
        bot.level().setBlock(ore, Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        return assignTask(bot, "mine_exposed", new MineTask(Blocks.IRON_ORE, 1), 800,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.RAW_IRON) >= 1);
    }

    private static Result assignMineToIron(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_PICKAXE, 1));
        BlockPos ore = bot.blockPosition().relative(Direction.NORTH, 2);
        bot.level().setBlock(ore, Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        return assignTask(bot, "mine_to_iron", new OreDigTask(java.util.Set.of(Blocks.IRON_ORE), 1),
                1200,
                ignored -> InventoryAction.countItem(bot, Items.RAW_IRON) >= 1);
    }

    private static Result assignMineIronFromScratch(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin); // in the from-scratch chain the bot has no gear, and the y6 mob sea would swarm it (observed aborted = killed by a zombie)
        // GOALFIX-GF3: the from-scratch-to-iron chain (wood pickaxe → mine stone → stone pickaxe → mine iron) needs roughly 3 raw logs + 3 cobblestone; give a comfortable margin (6/6) to avoid boundary failures.
        for (int dy = 0; dy < 6; dy++) {
            world.setBlock(origin.relative(Direction.WEST, 2).above(dy), Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        }
        for (int i = 0; i < 6; i++) {
            world.setBlock(origin.relative(Direction.EAST, 2 + i), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        world.setBlock(origin.relative(Direction.NORTH, 3), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.MineOre(java.util.Set.of(Blocks.IRON_ORE), 1));
        if (!started) {
            return Result.fail("mine_iron_from_scratch", "goal_submit_failed");
        }
        // GOALFIX-GF3: the full from-scratch chain takes a long time in real ticks; timeout raised from 3600 to 12000 (10 minutes).
        return Result.runningGoal("mine_iron_from_scratch", 12000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.RAW_IRON) >= 1);
    }

    /**
     * REGRESSION: isolates the directional-corridor logic for "approaching buried ore" (bbf8364 stepped descent / fall protection). Give a diamond pickaxe to rule out the tool/crafting variable,
     * seal the iron ore behind a 3-block stone wall (unreachable on foot — a corridor must be dug), and assert raw_iron is obtained without dying.
     * This case specifically tests OreSeek's APPROACH→digCorridorStep→MINE_ORE; it doesn't go through the LLM and is deterministically reproducible.
     */
    private static Result assignMineBuriedIron(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_PICKAXE, 1));
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        // Build a solid stone wall (2 high) + floor spanning north +3..+6; once the bot reaches +2 it must dig through 3 blocks of stone to reach the iron ore at +6.
        for (int d = 3; d <= 6; d++) {
            BlockPos col = origin.relative(Direction.NORTH, d);
            world.setBlock(col, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(col.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(col.below(), Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        BlockPos ore = origin.relative(Direction.NORTH, 6);
        world.setBlock(ore, Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.MineOre(java.util.Set.of(Blocks.IRON_ORE), 1));
        if (!started) {
            return Result.fail("mine_buried_iron", "goal_submit_failed");
        }
        return Result.runningGoal("mine_buried_iron", 2400,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.RAW_IRON) >= 1);
    }

    /**
     * REGRESSION(observed #9): DigDownTask digs a vertical shaft in place to get cobblestone. Reproduces the scenario of "a surface bot with topsoil underfoot and no exposed stone adjacent" —
     * the old implementation instantly reported no_reachable, or repeatedly re-issued startMining and stalled with progress reset to zero.
     * Give a wooden pickaxe; lay 2 layers of dirt underfoot with stone below that, and the bot must dig through the dirt into the stone layer and gather 3 cobblestone without getting stuck.
     */
    private static Result assignDigDown(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE, 1));
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        // Underfoot: y-1, y-2 laid with dirt (topsoil), stone pillar starting at y-3 going down; simulates "digging from grass down into a stone layer".
        world.setBlock(origin.below(), Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(origin.below(2), Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        for (int dy = 3; dy <= 10; dy++) {
            world.setBlock(origin.below(dy), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        Task task = new DigDownTask(Blocks.STONE, 3);
        return assignTask(bot, "dig_down", task, 1200,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.COBBLESTONE) >= 3);
    }

    /**
     * REGRESSION(observed #10): MINE_ORE goes through OreDigTask. Give a stone pickaxe and bury the iron ore in stone (unreachable on foot — a tunnel must be dug to approach it),
     * assert raw_iron is obtained without stalling. Specifically tests OreDigTask's scan → straight tunnel dig → vein mining, bypassing OreSeek's A* approach stall.
     */
    private static Result assignOreDigBuried(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        // Underfoot y-1..-4 is solid stone; the iron ore is buried slightly offset directly below at y-3: the bot must dig straight down through stone to reach it.
        for (int dy = 1; dy <= 5; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    world.setBlock(origin.offset(dx, -dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        world.setBlock(origin.below(3), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(origin.below(4), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL); // a small vein, tests flood-fill
        Task task = new OreDigTask(java.util.Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE), 2);
        return assignTask(bot, "ore_dig_buried", task, 2400,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.RAW_IRON) >= 2);
    }

    /**
     * REGRESSION(observed #8/#10): the full "mine iron ore" chain starting with an empty inventory in a narrow spawn pit. Enclose the bot in a small 5x5 stone-walled pit (simulating a real cramped-terrain predicament);
     * put one small tree (logs) in the pit, a stone layer underfoot, and iron ore deeper down; run the full backward chain through GoalExecutor and assert raw_iron is obtained in the end, without stalling or dying.
     * This is an end-to-end smoke test: chop tree → wood pickaxe → mine stone → stone pickaxe → mine iron, all through the BlockMiner primitive.
     */
    private static Result assignMineIronPocket(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        // A 5x5 stone wall (4 high) encloses a narrow pit, forcing out the "terrain-constrained" variable.
        for (int dy = 0; dy <= 3; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
                        world.setBlock(origin.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }
        clearNearbyMobs(world, origin); // the y6 mob sea would kill an unequipped bot (respawn clears the inventory → the task is doomed to fail)
        // One small tree in the pit (4 log segments — the from-scratch chain needs crafting-table 4 + wood pickaxe 3 + stick 2 = 9 planks; 2 log segments yield only 8 planks, 1 short — observed need:oak_planks x1).
        for (int dy = 0; dy < 4; dy++) {
            world.setBlock(origin.relative(Direction.EAST).above(dy), Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        }
        // Stone layer underfoot + iron ore deeper down.
        for (int dy = 1; dy <= 8; dy++) {
            world.setBlock(origin.below(dy), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        world.setBlock(origin.below(5), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.MineOre(java.util.Set.of(Blocks.IRON_ORE), 1));
        if (!started) {
            return Result.fail("mine_iron_pocket", "goal_submit_failed");
        }
        return Result.runningGoal("mine_iron_pocket", 12000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.RAW_IRON) >= 1);
    }

    /**
     * REGRESSION(observed #7): a mob spawns mid-mining. Give a stone pickaxe, bury iron ore in the stone layer underfoot, and spawn one zombie right after submitting the mine-iron goal.
     * Assert the goal **survives to completion** (raw_iron is obtained) — verifies that DangerWatcher pauses rather than abandons the goal, and resumes mining after the fight.
     */
    private static Result assignMineWithMob(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_SWORD, 1));
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        // Clear the y6 ambient mob sea (it would swarm-kill the bot; respawn clears the inventory → the tool gate reports a missing pickaxe), leaving only the 1 controlled spawn below —
        // this scenario tests combat preemption/recovery while "mining with 1 mob around", not surviving the mob sea. Wearing armor improves determinism.
        clearNearbyMobs(world, origin);
        giveDeepMineKit(bot);
        io.github.zoyluo.minecraftai.action.EquipAction.equipBestArmor(bot);
        fillStoneCube(world, origin, 4, 8);
        world.setBlock(origin.below(3), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        Zombie zombie = EntityType.ZOMBIE.create(world, EntitySpawnReason.COMMAND);
        if (zombie != null) {
            zombie.setPersistenceRequired();
            zombie.snapTo(bot.getX() + 2.0D, bot.getY(), bot.getZ() + 2.0D, 0.0F, 0.0F);
            world.addFreshEntity(zombie);
        }
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.MineOre(java.util.Set.of(Blocks.IRON_ORE), 1));
        if (!started) {
            return Result.fail("mine_with_mob", "goal_submit_failed");
        }
        return Result.runningGoal("mine_with_mob", 4800,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.RAW_IRON) >= 1);
    }

    /**
     * REGRESSION(P2): achieve_goal iron ingot — empty-handed → backward planning → chop tree → wood pickaxe → mine stone → stone pickaxe → mine iron → smelt → iron ingot.
     * All materials ready to hand (tree/stone/iron ore) + a furnace + ample fuel are nearby; assert iron_ingot eventually appears in the inventory. Tests the smelting chain.
     */
    // Iron ingot: iron ore buried in a solid-stone area, given a stone pickaxe + furnace + coal → mine iron ore → smelt → iron ingot (focuses on ore+smelting; the tool/furnace chain is tested by other scenarios).
    private static Result assignAchieveIronIngot(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin);
        fillStoneCube(world, origin, 4, 10);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.FURNACE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL, 4));
        world.setBlock(origin.below(3), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.IRON_INGOT, 1));
        if (!started) {
            return Result.fail("achieve_iron_ingot", "goal_submit_failed");
        }
        return Result.runningGoal("achieve_iron_ingot", 8000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.IRON_INGOT) >= 1);
    }

    // Gold ingot (deep ore, needs an iron pickaxe): teleport to the gold-ore layer (-16), bury gold ore underfoot, given an iron pickaxe + furnace + deep-mining safety gear + supplies → mine gold ore → smelt → gold ingot.
    private static Result assignAchieveGoldIngot(AIPlayerEntity bot) {
        clearInventory(bot);
        BlockPos origin = prepareDeepArea(bot, -16);
        ServerLevel world = bot.level();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.FURNACE, 1));
        giveDeepMineKit(bot);
        giveDeepMineSupplies(bot);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.setBlock(origin.offset(dx, -3, dz), Blocks.GOLD_ORE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.GOLD_INGOT, 1));
        if (!started) {
            return Result.fail("achieve_gold_ingot", "goal_submit_failed");
        }
        return Result.runningGoal("achieve_gold_ingot", 8000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.GOLD_INGOT) >= 1);
    }

    // Obsidian: a layer of obsidian buried in a solid-stone area, given a diamond pickaxe → DigDownTask digs downward, hits the obsidian layer, and mines 1 block (obsidian is slow to mine).
    private static Result assignAchieveObsidian(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin);
        fillStoneCube(world, origin, 4, 10);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_PICKAXE, 1));
        // Lay a 5×5 layer of obsidian at down(2..3), guaranteeing the downward stepped dig hits it regardless of direction (only 1 block needs to be mined to pass).
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.setBlock(origin.offset(dx, -2, dz), Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(origin.offset(dx, -3, dz), Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.OBSIDIAN, 1));
        if (!started) {
            return Result.fail("achieve_obsidian", "goal_submit_failed");
        }
        return Result.runningGoal("achieve_obsidian", 8000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.OBSIDIAN) >= 1);
    }

    // Extreme environment ①: iron ingot + mob siege. Armored, with 2 zombies; the bot must fight while mining iron → smelting. Verifies combat pauseFor/resume never drops the task.
    private static Result assignIronExtreme(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin);
        fillStoneCube(world, origin, 4, 10);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.FURNACE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL, 4));
        giveDeepMineKit(bot);
        io.github.zoyluo.minecraftai.action.EquipAction.equipBestArmor(bot);
        world.setBlock(origin.below(3), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        spawnHostiles(world, origin, 2);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.IRON_INGOT, 1));
        if (!started) {
            return Result.fail("iron_extreme", "goal_submit_failed");
        }
        return Result.runningGoal("iron_extreme", 12000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.IRON_INGOT) >= 1);
    }

    // Extreme environment ②: diamond (deep at -59, dark) + mob siege. Depth + darkness + 2 zombies, a triple extreme; the bot must fight while mining diamond.
    private static Result assignDiamondExtreme(AIPlayerEntity bot) {
        clearInventory(bot);
        BlockPos origin = prepareDeepArea(bot, -59);
        ServerLevel world = bot.level();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 1));
        giveDeepMineKit(bot);
        giveDeepMineSupplies(bot);
        io.github.zoyluo.minecraftai.action.EquipAction.equipBestArmor(bot);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.setBlock(origin.offset(dx, -2, dz), Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        spawnHostiles(world, origin, 2);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.DIAMOND, 1));
        if (!started) {
            return Result.fail("diamond_extreme", "goal_submit_failed");
        }
        return Result.runningGoal("diamond_extreme", 12000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.DIAMOND) >= 1);
    }

    // Extreme environment ③: gathering food (hunting + cooking) + mob siege. Armored, with 2 zombies; the bot must fight while hunting and cooking to reach 4 cooked food.
    private static Result assignFoodExtreme(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin);
        InventoryAction.giveItem(bot, new ItemStack(Items.FURNACE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL, 8));
        giveDeepMineKit(bot); // includes an iron sword (dual-use for hunting and fighting mobs) + armor
        io.github.zoyluo.minecraftai.action.EquipAction.equipBestArmor(bot);
        for (int i = 0; i < 6; i++) {
            var cow = EntityType.COW.create(world, EntitySpawnReason.COMMAND);
            if (cow != null) {
                cow.snapTo(origin.getX() + 2.0D, origin.getY(), origin.getZ() + (i - 3), 0.0F, 0.0F);
                world.addFreshEntity(cow);
            }
        }
        spawnHostiles(world, origin, 2);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Food(4));
        if (!started) {
            return Result.fail("food_extreme", "goal_submit_failed");
        }
        return Result.runningGoal("food_extreme", 12000,
                ignored -> bot.isAlive() && safeFoodUnits(bot) >= 4);
    }

    /**
     * REGRESSION(P2): achieve_goal iron pickaxe — empty-handed → the full backward chain including smelting 3 iron ingots → crafting an iron pickaxe. The deepest tool chain.
     */
    private static Result assignAchieveIronPickaxe(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin); // early in the full chain the bot has no gear; clear the y6 mob sea
        // Two columns of 24 logs: the from_scratch chain includes smelting, and execution drift from burning raw logs as fuel + crafting whole logs into planks can eat into the resupply margin;
        // with a single column of 12 logs, once the first pass chops it bare the replan resupply hits no_resource (observed in suite runs). Give enough trees to absorb all the drift.
        for (int dy = 0; dy < 12; dy++) {
            world.setBlock(origin.relative(Direction.WEST, 2).above(dy), Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(origin.relative(Direction.WEST, 2).relative(Direction.NORTH, 2).above(dy), Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        }
        // A solid-stone area replaces a single stone pillar: the mine-stone/mine-iron task digs a diagonal stepped shaft, and with a single pillar the first step already walks off it into a leftover pit (the culprit behind no_resource).
        fillStoneCube(world, origin, 4, 10);
        // 3 iron ore blocks (an iron pickaxe needs 3 iron ingots).
        world.setBlock(origin.below(4), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(origin.below(5), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(origin.below(6), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.IRON_PICKAXE, 1));
        if (!started) {
            return Result.fail("achieve_iron_pickaxe", "goal_submit_failed");
        }
        return Result.runningGoal("achieve_iron_pickaxe", 16000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.IRON_PICKAXE) >= 1);
    }

    /**
     * REGRESSION(food chain): empty-handed → Goal.Food end to end. Place a tree (tools + fuel) + stone underfoot (furnace) + 5 cows (prey);
     * source-perception should choose hunting → chop tree for tools → mine stone for a furnace → hunt → cook meat, reaching 4 portions of cooked food. Verifies the full source-perception/hunting/cooking chain.
     */
    private static Result assignAchieveFood(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        // Focuses on the "source-perception → hunting → cooking" food core: give the prerequisites ready-made (furnace + fuel + sword) so Goal.Food never has to backward-plan mining stone for a furnace
        // (dig_down digging a deep shaft would trap the bot at the bottom, unable to chase surface cows — that's a mining-scenario bug, fixed separately).
        InventoryAction.giveItem(bot, new ItemStack(Items.FURNACE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD, 1));
        // 5 cows placed right next to the bot (within the flattened area), to avoid a long chase getting stuck on obstacles
        for (int i = 0; i < 5; i++) {
            var cow = EntityType.COW.create(world, EntitySpawnReason.COMMAND);
            if (cow != null) {
                cow.snapTo(origin.getX() + 1.5D, origin.getY(), origin.getZ() + (i - 2), 0.0F, 0.0F);
                world.addFreshEntity(cow);
            }
        }
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Food(4));
        if (!started) {
            return Result.fail("food", "goal_submit_failed");
        }
        return Result.runningGoal("food", 8000,
                ignored -> bot.isAlive() && safeFoodUnits(bot) >= 4);
    }

    // Full food chain (given ready-made stone/fuel/sword): craft a furnace (craft furnace) → hunt → cook. Compared to food (which gives a ready-made furnace), this covers one more layer: "craft furnace".
    // Does not include mining stone: in the dev test world the bot spawns underground in darkness at y6 (spawn snap 0,6,0), where a stepped stone dig would get stuck on bedrock and be swarmed by spiders —
    // that's an underground-mining geometry/navigation problem, not food-chain logic, and is tracked separately (see the progress notes). Give 8 cobblestone → craft furnace directly.
    private static Result assignAchieveFoodFull(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD, 1));
        for (int i = 0; i < 6; i++) {
            var cow = EntityType.COW.create(world, EntitySpawnReason.COMMAND);
            if (cow != null) {
                cow.snapTo(origin.getX() + 2.0D, origin.getY(), origin.getZ() + (i - 3), 0.0F, 0.0F);
                world.addFreshEntity(cow);
            }
        }
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Food(4));
        if (!started) {
            return Result.fail("food_full", "goal_submit_failed");
        }
        return Result.runningGoal("food_full", 16000,
                ignored -> bot.isAlive() && safeFoodUnits(bot) >= 4);
    }

    private static int safeFoodUnits(AIPlayerEntity bot) {
        return MiningFoodReserve.units(bot.getInventory());
    }

    // End-to-end test of the food chain's "farm for bread" branch: no animals + grass present → Goal.Food should take ensureFoodTo's planting chain
    // (backward-plan a hoe → cut grass/get seeds → till → plant → wait to ripen → harvest → craft bread), reaching 2 loaves of bread in the end.
    // Complements food/food_full (hunt → cook), covering the previously-untested path of "self-sufficient farming on animal-less terrain".
    // Deliberately withholds a hoe (gives planks + a crafting table so the bot crafts one itself), verifying GoalPlanner backward-plans a hoe on the Food→bread→wheat branch (Fix B).
    private static Result assignAchieveFoodFarm(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        // 1) Clear nearby animals + hostile mobs: planting source-selection needs "no animals" (otherwise it misjudges prey present → hunts instead), and this avoids a skeleton preempting and aborting the farming.
        clearNearbyMobs(world, origin);
        // 2) Lay tillable dirt on the floor (y-1) within radius 4 of the bot (the FARM step's FarmTask is centered on the bot, till/plant happens within radius 4 here).
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                world.setBlock(origin.offset(dx, -1, dz), Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        // 3) Place a few tufts of short grass as the "grass present" signal (short grass within FOOD_GRASS_SCAN=32 triggers planting source-selection); placed on the edge dirt, not filling the whole farmland.
        for (int dz = -1; dz <= 1; dz++) {
            world.setBlock(origin.offset(4, 0, dz), Blocks.SHORT_GRASS.defaultBlockState(), Block.UPDATE_ALL);
        }
        // 4) Give seeds + planks + a crafting table, but withhold a hoe (a hoe = a tool that needs a crafting table; bread/sticks don't). Verifies the backward-planned hoe (Fix B).
        InventoryAction.giveItem(bot, new ItemStack(Items.WHEAT_SEEDS, 16));
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_PLANKS, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE, 1));
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Food(1));
        if (!started) {
            return Result.fail("food_farm", "goal_submit_failed");
        }
        // perTick forcibly ripens the wheat around the bot on every server tick — a headless test can't wait on natural random-tick growth (would take minutes and always time out);
        // the bot's wheat ripens as soon as it's planted, so this tests whether the full "till → plant → harvest → pick up (Fix A) → craft bread" logic chain can reach 2 loaves of bread.
        // (The ripening must live in perTick: the assertion is only invoked once a task completes, and while FarmTask is waiting for it to ripen no task ever completes → putting it in the assertion would deadlock.)
        return Result.runningGoal("food_farm", 12000,
                tickBot -> forceGrowCrops(world, origin, 6, Blocks.WHEAT),
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.BREAD) >= 1);
    }

    // Forcibly ripens unripe instances of the given crop within center±radius to maxAge (lets headless tests skip waiting on natural growth).
    private static void forceGrowCrops(ServerLevel world, BlockPos center, int radius, Block crop) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -1, -radius), center.offset(radius, 2, radius))) {
            net.minecraft.world.level.block.state.BlockState st = world.getBlockState(pos);
            if (st.is(crop) && st.getBlock() instanceof net.minecraft.world.level.block.CropBlock cb && !cb.isMaxAge(st)) {
                world.setBlock(pos, cb.getStateForAge(cb.getMaxAge()), Block.UPDATE_CLIENTS);
            }
        }
    }

    // End-to-end foraging (wild berries) test: ripe sweet-berry bushes laid out nearby → Goal.HaveItem(SWEET_BERRIES) should take the gather path to pick the berries.
    // Covers the "supplement food with wild berries" path (the forage tool actually maps to Goal.HaveItem(SWEET_BERRIES)).
    private static Result assignForage(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin);
        // Lay a patch of ripe (age3) sweet-berry bushes to the north, with dirt underneath to prevent a "no support" block update from knocking them out. Berries drop probabilistically, so 15 bushes are laid — far more than the target of 4.
        net.minecraft.world.level.block.state.BlockState ripeBush = Blocks.SWEET_BERRY_BUSH.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_3, 3);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = 1; dz <= 3; dz++) {
                BlockPos ground = origin.offset(dx, -1, -dz);
                world.setBlock(ground, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(ground.above(), ripeBush, Block.UPDATE_ALL);
            }
        }
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.SWEET_BERRIES, 4));
        if (!started) {
            return Result.fail("forage", "goal_submit_failed");
        }
        return Result.runningGoal("forage", 4000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.SWEET_BERRIES) >= 4);
    }

    // End-to-end infinite-water-source/irrigation test: give 2 water buckets + a solid dirt floor → IrrigateTask digs a 2×2 pit and places 2 buckets of water diagonally.
    // Assertion: all four cells of the 2×2 pit become water source blocks after SETTLE (only 2 buckets were poured; the other 2 cells become sources automatically from the flow) — proving that
    // an infinite, scoopable/irrigable 2×2 water source was formed. At the same time the inventory should end up with 2 empty buckets.
    private static Result assignFarmIrrigate(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin);
        // A patch of solid dirt is laid on the floor layer (y-1), serving as the ground to dig the pit into plus the retaining walls around the 2×2 pit.
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                world.setBlock(origin.offset(dx, -1, dz), Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.WATER_BUCKET, 2));
        BlockPos waterCenter = origin.offset(2, -1, 0); // dig a 2×2 pool in the floor layer (next to the bot)
        return assignTask(bot, "farm_irrigate", new IrrigateTask(waterCenter), 2400,
                ignored -> bot.isAlive()
                        && countWaterSources(world, waterCenter) >= 4
                        && InventoryAction.countItem(bot, Items.BUCKET) >= 2);
    }

    // End-to-end cake-crafting-chain test: give 3 empty buckets + 1 egg + 4 sugar cane + 3 wheat + a crafting table, and spawn 3 cows nearby.
    // Goal.HaveItem(CAKE) should: milk the cows (MilkCowTask: empty bucket → milk bucket ×3) + sugar cane → sugar ×2 + eggs/wheat already on hand → craft the cake.
    // Eggs are a passive byproduct (chickens lay them slowly); they aren't auto-produced here and are simply given directly (real play would need the bot to raise chickens and collect eggs, see the commit notes).
    private static Result assignCake(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin); // clear first (including cows left over from earlier contamination), then spawn 3 clean ones
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET, 3));
        InventoryAction.giveItem(bot, new ItemStack(Items.EGG, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.SUGAR_CANE, 4));
        InventoryAction.giveItem(bot, new ItemStack(Items.WHEAT, 3));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE, 1));
        for (int i = 0; i < 3; i++) {
            var cow = EntityType.COW.create(world, EntitySpawnReason.COMMAND);
            if (cow != null) {
                cow.snapTo(origin.getX() + 1.5D, origin.getY(), origin.getZ() + (i - 1), 0.0F, 0.0F);
                world.addFreshEntity(cow);
            }
        }
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.CAKE, 1));
        if (!started) {
            return Result.fail("cake", "goal_submit_failed");
        }
        return Result.runningGoal("cake", 8000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.CAKE) >= 1);
    }

    // End-to-end village-crop-raiding test: carve a walkable corridor, with a patch of ripe crop field at the end (simulating a village farm, ~12 blocks from the bot).
    // RaidCropsTask should: wide-scan to find the crop field → walk over → harvest → pick up, reaching 4 units of produce. Covers "find an existing crop field and raid it"
    // (complementing FarmTask's plant-and-harvest-your-own). The corridor is required: the dev world is all stone underground at y6, and without a path cleared the bot can't reach the distant field.
    private static Result assignVillageHarvest(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin);
        // Corridor: dirt laid on the floor, 3 blocks of headroom cleared above, running from the bot all the way to the field.
        for (int x = 0; x <= 16; x++) {
            for (int z = -3; z <= 3; z++) {
                world.setBlock(origin.offset(x, -1, z), Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
                for (int y = 0; y <= 2; y++) {
                    world.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        // Ripe crop field (wheat/carrot/potato mixed, all CropBlock): x 10..14 × z -1..1 = 15 plants, far more than the target of 4.
        net.minecraft.world.level.block.state.BlockState[] crops = {
                Blocks.WHEAT.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_7, 7),
                Blocks.CARROTS.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_7, 7),
                Blocks.POTATOES.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_7, 7)};
        int i = 0;
        for (int x = 10; x <= 14; x++) {
            for (int z = -1; z <= 1; z++) {
                world.setBlock(origin.offset(x, -1, z), Blocks.FARMLAND.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(origin.offset(x, 0, z), crops[i++ % crops.length], Block.UPDATE_ALL);
            }
        }
        return assignTask(bot, "village_harvest", new RaidCropsTask(4), 4000,
                ignored -> bot.isAlive()
                        && InventoryAction.countItem(bot, Items.WHEAT)
                        + InventoryAction.countItem(bot, Items.CARROT)
                        + InventoryAction.countItem(bot, Items.POTATO) >= 4);
    }

    // ==================== Close-to-real-play layer (realistic) ====================
    // The opposite of artificial idealized scenarios: a naturally generated world (fixed seed), empty inventory, no mob clearing, no gear given, no blocks placed, no teleporting —
    // tests "completing the goal from scratch under real conditions". The failure list at this layer = the gap list between automation and real play, fixed one item at a time.
    // Note: an assertion only means "the result was obtained", not that the process wasn't dumb (detours/stutter still need manual confirmation).

    private static BlockPos prepareRealistic(AIPlayerEntity bot) {
        ServerLevel world = bot.level();
        world.setDayTime(1000L); // same as prepareArea: start in daytime, isolating flakiness from nighttime reflexes
        bot.getActionPack().stopAll();
        clearInventory(bot); // real-play start = empty inventory; leave everything else untouched (no mob clearing/no laying blocks/no giving items)
        // real_wheat adjusts randomTickSpeed; reset it uniformly here to avoid leaking between scenarios
        world.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED, 3, world.getServer());
        surfaceTeleport(bot);
        return bot.blockPosition();
    }

    // Mining First's final baseline is stricter than the legacy real_* one: it keeps the seed's genuine spawn position and does no surfaceTeleport.
    // The dedicated evidence server only ever runs one from_zero scenario at a time, so there's no need to teleport away to clean up positional contamination from a previous scenario.
    private static BlockPos prepareMiningFromZero(AIPlayerEntity bot) {
        ServerLevel world = bot.level();
        world.setDayTime(1000L);
        bot.getActionPack().stopAll();
        clearInventory(bot);
        world.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED, 3, world.getServer());
        return bot.blockPosition();
    }

    // If the spawn point is in a cave/underground, lift it to the natural surface (real players operate on the surface); if already on the surface, leave it in place.
    // Wall-enclosure/buried-alive scenarios must be surfaced first: building a wall in the y6 underground darkness would trigger DangerWatcher's "trapped in a death pit" life-saving teleport
    // (dark_trap_escape), which would directly override the real escape being tested (pillaring/digging through walls) (observed as nav_pillar_out aborted).
    private static void surfaceTeleport(AIPlayerEntity bot) {
        ServerLevel world = bot.level();
        BlockPos at = bot.blockPosition();
        if (world.canSeeSky(at)) {
            return;
        }
        int topY = world.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ());
        bot.teleportTo(world, at.getX() + 0.5D, topY, at.getZ() + 0.5D,
                java.util.Collections.emptySet(), bot.getYRot(), bot.getXRot(), true);
        bot.getActionPack().stopAll();
    }

    // Reads the bot's cumulative death statistic (ServerStatsCounter accumulates across respawns and is never reset by one). Used as the baseline for real_*'s zero-death assertions:
    // in real play, dying and respawning is a major incident — dropped gear/lost position/wasted progress — that can't be waved off even if the goal is completed after respawn.
    // Checking isAlive() alone can't catch "died and came back"; the death count must be compared instead.
    private static int deathCount(AIPlayerEntity bot) {
        return bot.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.DEATHS));
    }

    private static Function<AIPlayerEntity, Optional<String>> zeroDeathFailFast(int deathBase) {
        return candidate -> deathCount(candidate) > deathBase
                ? Optional.of("zero_death_violation")
                : Optional.empty();
    }

    private static Function<AIPlayerEntity, Optional<String>> miningFromZeroFailFast(int deathBase) {
        return candidate -> MiningEvidenceAudit.failFastReason(candidate)
                .or(() -> zeroDeathFailFast(deathBase).apply(candidate));
    }

    // Owns a given piece of gear: either in the inventory or already worn in any armor slot. Used for the armor assertion — avoids a 1-tick race in "craft armor → auto-equip it"
    // (runningGoal checks the assertion the instant the goal completes, and equipping happens one tick later in the same burst; otherwise it would misjudge 'chestplate not worn' and FAIL).
    private static boolean hasGear(AIPlayerEntity bot, Item item) {
        if (InventoryAction.countItem(bot, item) >= 1) {
            return true;
        }
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            if (bot.getItemBySlot(slot).is(item)) {
                return true;
            }
        }
        return false;
    }

    // Real play: chop 8 logs (find a tree naturally; any wood type accepted).
    private static Result assignRealWood(AIPlayerEntity bot) {
        prepareRealistic(bot);
        final int deathBase = deathCount(bot); // zero-death red line: dying even once during the scenario is an instant FAIL (see the deathCount comment)
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.OAK_LOG, 8));
        if (!started) {
            return Result.fail("real_wood", "goal_submit_failed");
        }
        java.util.Set<Item> logs = java.util.Set.copyOf(io.github.zoyluo.minecraftai.craft.RecipeRegistry.LOGS);
        return Result.runningGoal("real_wood", 8000,
                ignored -> bot.isAlive()
                        && io.github.zoyluo.minecraftai.action.HarvestCore.countInventoryItems(bot, logs) >= 8
                        && deathCount(bot) == deathBase);
    }

    // Real play: get 4 cooked food from scratch (perceive and choose a source on its own — hunting/planting; craft its own furnace and gather fuel).
    private static Result assignRealFood(AIPlayerEntity bot) {
        prepareRealistic(bot);
        final int deathBase = deathCount(bot); // zero-death red line: dying and respawning is also judged FAIL (in real play, dying once is a major incident)
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Food(4));
        if (!started) {
            return Result.fail("real_food", "goal_submit_failed");
        }
        return Result.runningGoal("real_food", 16000,
                ignored -> bot.isAlive() && safeFoodUnits(bot) >= 4
                        && deathCount(bot) == deathBase);
    }

    // Real play: farm wheat from scratch to make 2 loaves of bread (cut grass for seeds → craft a hoe → till → plant → wait to ripen → harvest → craft).
    // The one concession: randomTickSpeed raised from 3 to 40 (~13x faster growth). The growth path is genuinely walked through, only sped up in time —
    // without the speedup, natural ripening takes 20+ minutes and the suite couldn't run; this is a different tier from perTick's magic ripening (food_farm).
    private static Result assignRealWheat(AIPlayerEntity bot) {
        prepareRealistic(bot);
        ServerLevel world = bot.level();
        world.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED, 40, world.getServer());
        final int deathBase = deathCount(bot); // zero-death red line: dying and respawning is also judged FAIL (in real play, dying once is a major incident)
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.BREAD, 2));
        if (!started) {
            return Result.fail("real_wheat", "goal_submit_failed");
        }
        return Result.runningGoal("real_wheat", 16000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.BREAD) >= 2
                        && deathCount(bot) == deathBase);
    }

    // Real play: one iron ingot from scratch (chop tree → wood pickaxe → mine stone → stone pickaxe → find iron ore → mine → craft a furnace → smelt). A major test on natural terrain.
    private static Result assignRealIron(AIPlayerEntity bot) {
        prepareRealistic(bot);
        final int deathBase = deathCount(bot); // zero-death red line: dying and respawning is also judged FAIL (in real play, dying once is a major incident)
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.IRON_INGOT, 1));
        if (!started) {
            return Result.fail("real_iron", "goal_submit_failed");
        }
        return Result.runningGoal("real_iron", 24000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.IRON_INGOT) >= 1
                        && deathCount(bot) == deathBase);
    }

    // Sustained-mining measurement: given a pickaxe + armor + torches to skip bootstrap/survival/lighting, mine 100 blocks of iron ore (raw_iron) on real terrain.
    // Isolates "OreDigTask's sustained long-run reliability" — ore-finding/approach stalling/strip-mine expansion/durability/return-to-base — to see where the ceiling is.
    private static Result assignRealIronBulk(AIPlayerEntity bot) {
        prepareRealistic(bot);
        clearInventory(bot);
        for (int i = 0; i < 5; i++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 1)); // 5 iron pickaxes: durability shouldn't be the first bottleneck
        }
        giveDeepMineKit(bot);                                          // armor + sword + shield: survival shouldn't be the first bottleneck
        InventoryAction.giveItem(bot, new ItemStack(Items.TORCH, 64)); // torches: lighting shouldn't be the first bottleneck
        final int deathBase = deathCount(bot);
        java.util.Set<Block> ironOres = java.util.Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.MineOre(ironOres, 100));
        if (!started) {
            return Result.fail("real_iron_bulk", "goal_submit_failed");
        }
        return Result.runningGoal("real_iron_bulk", 48000,
                ignored -> bot.isAlive() && deathCount(bot) == deathBase
                        && InventoryAction.countItem(bot, Items.RAW_IRON) >= 100);
    }

    // Real play: one gold ingot from scratch (tool chain → iron pickaxe → dig down to Y-16 to find gold ore → mine → smelt). Gold is shallower than diamond, but still needs an iron pickaxe + a deep dive.
    private static Result assignRealGold(AIPlayerEntity bot) {
        prepareRealistic(bot);
        final int deathBase = deathCount(bot);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.GOLD_INGOT, 1));
        if (!started) {
            return Result.fail("real_gold", "goal_submit_failed");
        }
        return Result.runningGoal("real_gold", 28000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.GOLD_INGOT) >= 1
                        && deathCount(bot) == deathBase);
    }

    // Real play: get redstone from scratch (tool chain → iron pickaxe → dig down to Y-59 to find redstone ore → mine). Y-59 is the same depth as diamond, sharing diamond's tough deep-layer edge.
    // One block of redstone ore drops 4-5 redstone and needs no smelting; assert ≥4 (about one ore block's worth).
    private static Result assignRealRedstone(AIPlayerEntity bot) {
        prepareRealistic(bot);
        final int deathBase = deathCount(bot);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.REDSTONE, 4));
        if (!started) {
            return Result.fail("real_redstone", "goal_submit_failed");
        }
        return Result.runningGoal("real_redstone", 32000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.REDSTONE) >= 4
                        && deathCount(bot) == deathBase);
    }

    // Real play: one diamond from scratch (the full tool chain + a genuine dig down to -59 to find ore, encountering caves/lava/darkness along the way).
    private static Result assignRealDiamond(AIPlayerEntity bot) {
        prepareRealistic(bot);
        final int deathBase = deathCount(bot); // zero-death red line: dying and respawning is also judged FAIL (in real play, dying once is a major incident)
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.DIAMOND, 1));
        if (!started) {
            return Result.fail("real_diamond", "goal_submit_failed");
        }
        return Result.runningGoal("real_diamond", 36000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.DIAMOND) >= 1
                        && deathCount(bot) == deathBase);
    }

    // The ultimate real-play application (diamond ≥3): mine 3 diamonds from scratch on real terrain — the regression embodiment of the user's actual goal. Requiring 3 forces "locating multiple ore veins in a row
    // and diving repeatedly"; timeout is 36000t to give the full chain room, with the zero-death red line. reliability.sh runs it to compare success rates before/after a fix.
    // Only runnable by explicit name or script (excluded from ALL_FEATURES: 36000t would sink verify all).
    private static Result assignRealDiamond3(AIPlayerEntity bot) {
        prepareRealistic(bot);
        final int deathBase = deathCount(bot);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.DIAMOND, 3));
        if (!started) {
            return Result.fail("real_diamond3", "goal_submit_failed");
        }
        return Result.runningGoal("real_diamond3", 36000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.DIAMOND) >= 3
                        && deathCount(bot) == deathBase);
    }

    // Mining First M1-prepared: lays 32×2=64 blocks of eye-level exposed diamond ore along both sides of a deterministic deep corridor, pre-granting only non-target deep-mining gear.
    // This layer isolates continuous ore-finding/block-breaking/pickup/tool-swapping — it is not a from-scratch capability; explicit opt-in, excluded from verify all.
    private static Result assignDiamondStack64Prepared(AIPlayerEntity bot) {
        clearInventory(bot);
        BlockPos origin = prepareDeepArea(bot, -59);
        ServerLevel world = bot.level();
        MiningBudget budget = MiningBudget.forQuota(
                DIAMOND_STACK_TARGET, true, ToolTier.IRON);
        // The prepared isolation layer shares the same expedition-budget source as from-zero, so hand-written fixture values never drift out of sync with
        // the boundary-zero service's tool, torch, stone, and future-stick contract.
        giveItemToAtLeast(bot, Items.IRON_PICKAXE, budget.initialPickaxes());
        giveItemToAtLeast(bot, Items.STONE_PICKAXE, budget.tunnelingPickaxes());
        giveItemToAtLeast(bot, Items.IRON_INGOT, budget.spareToolIngots());
        giveItemToAtLeast(bot, Items.COBBLESTONE, budget.emergencyBlocks());
        giveItemToAtLeast(bot, Items.STICK, budget.spareToolSticks());
        giveItemToAtLeast(bot, Items.TORCH, Math.max(
                budget.torchTarget(), MiningBudget.DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES));
        giveItemToAtLeast(bot, Items.COOKED_BEEF, budget.cookedFoodTarget());
        giveItemToAtLeast(bot, Items.CRAFTING_TABLE, 1);
        giveDeepMineKit(bot);
        // The corridor extends in OreDig's default first-leg NORTH direction; the middle 3 blocks are walkable, and space is left both at the ore wall and outside it,
        // so strict mode can actually walk over and pick up drops by collision. The old under-foot ore layer would mine away its own support; a closed loop or checkerboard
        // layout would hide remaining ore behind the bot after batch recovery, testing a geometric dead angle instead of an 8-batch expedition.
        for (int dz = -34; dz <= 0; dz++) {
            for (int dx = -3; dx <= 3; dx++) {
                world.setBlock(origin.offset(dx, -1, dz),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlock(origin.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
                // Stable roof keeps naturally generated gravel above the prepared canvas from
                // falling through the newly carved corridor and burying the bot on the first
                // neighbouring block update. This is environmental isolation, not a target grant.
                world.setBlock(origin.offset(dx, 3, dz),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
            for (int dy = 0; dy <= 3; dy++) {
                world.setBlock(origin.offset(-4, dy, dz),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(origin.offset(4, dy, dz),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        for (int dz = -1; dz >= -32; dz--) {
            for (int dx : new int[]{-2, 2}) {
                // Uses only a single eye-level ore wall: every block is genuinely visible from the central corridor, mined from the side, with drops landing on
                // the stone floor. prepared's job is to isolate continuous batches/tools/physical pickup, without mixing in
                // the separate perception variable of "an underfoot ore layer being occluded by the floor".
                world.setBlock(origin.offset(dx, 1, dz),
                        Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        final int deathBase = deathCount(bot);
        boolean started = GoalExecutor.INSTANCE.submit(
                bot, new Goal.HaveItem(Items.DIAMOND, DIAMOND_STACK_TARGET));
        if (!started) {
            return Result.fail("diamond_stack_64_prepared", "goal_submit_failed");
        }
        return Result.runningGoal("diamond_stack_64_prepared", 60000,
                ignored -> bot.isAlive()
                        && InventoryAction.countItem(bot, Items.DIAMOND) >= DIAMOND_STACK_TARGET
                        && deathCount(bot) == deathBase);
    }

    // Mining First M1-final: natural terrain, empty inventory, nothing granted toward the goal — obtain a full stack of diamonds entirely from scratch.
    // This is the only scenario the capability manifest may pin; while it isn't met it must honestly FAIL/MISSING.
    private static Result assignDiamondStack64FromZero(AIPlayerEntity bot) {
        prepareMiningFromZero(bot);
        MiningEvidenceAudit.begin(bot, MiningEvidenceAudit.Target.DIAMOND);
        final int deathBase = deathCount(bot);
        Goal goal = new Goal.HaveItem(Items.DIAMOND, DIAMOND_STACK_TARGET);
        GoalPlanner.GoalPlan nominalPlan = GoalPlanner.plan(bot, goal);
        int timeoutTicks = MiningMissionBudget
                .diamondStack64FromZero(nominalPlan).timeoutTicks();
        boolean started = GoalExecutor.INSTANCE.submit(bot, goal);
        if (!started) {
            return Result.fail("diamond_stack_64_from_zero", "goal_submit_failed");
        }
        return Result.runningGoalFailFast("diamond_stack_64_from_zero",
                timeoutTicks,
                miningFromZeroFailFast(deathBase),
                ignored -> bot.isAlive()
                        && InventoryAction.countItem(bot, Items.DIAMOND) >= DIAMOND_STACK_TARGET
                        && deathCount(bot) == deathBase
                        && MiningEvidenceAudit.snapshot(bot)
                        .filter(MiningEvidenceAudit.Snapshot::passes).isPresent());
    }

    // Craft a full suit of iron armor entirely from scratch (real terrain, no materials pre-placed — unlike achieve_armor, which pre-gives 30 iron and only tests assembling the armor).
    // Chain: wood → tools → mine iron ×24+ → smelt → craft 4 armor pieces + a sword → equip. Shallower than diamond (iron is at Y16-48, a stone pickaxe suffices, no Y-59 lava), but a large quantity.
    // Assertion: the full four-piece iron armor set is worn + the bot survives + zero deaths (dying once = a major incident, same red line as real_diamond).
    private static Result assignRealArmor(AIPlayerEntity bot) {
        prepareRealistic(bot);
        final int deathBase = deathCount(bot);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Armor());
        if (!started) {
            return Result.fail("real_armor", "goal_submit_failed");
        }
        return Result.runningGoal("real_armor", 36000,
                ignored -> bot.isAlive() && deathCount(bot) == deathBase
                        && hasGear(bot, Items.IRON_HELMET)
                        && hasGear(bot, Items.IRON_CHESTPLATE)
                        && hasGear(bot, Items.IRON_LEGGINGS)
                        && hasGear(bot, Items.IRON_BOOTS)
                        && hasGear(bot, Items.IRON_SWORD)); // iron set + iron sword: the full four-piece armor set plus the sword are both obtained (Goal.Armor full already includes a sword)
    }

    // Building, deepened · building on real terrain: given ample planks (isolating the "building" step itself — site selection autoSite + ground leveling + block-by-block placement), does not test provisioning.
    // Real terrain (slopes/rolling ground/water's edge) is something the flat lab canvas can never test: whether autoSite's site selection + flatten's ground leveling can cleanly finish a house.
    // Assertion: within origin±24, plank-family blocks ≥80 (small_hut is 112 blocks total, leaving margin) + survives + zero deaths. First surfaces the shortcomings of building on real terrain.
    private static Result assignRealBuild(AIPlayerEntity bot) {
        prepareRealistic(bot);
        clearInventory(bot);
        // Give ample planks of multiple wood types (the blueprint adapts to the local wood type; giving only oak would trigger need birch/spruce_log) + ground-leveling fill (dirt/cobble),
        // to genuinely isolate the "building" step itself (site selection + leveling + placement), without testing provisioning/logging.
        for (Item p : io.github.zoyluo.minecraftai.craft.RecipeRegistry.PLANKS) {
            InventoryAction.giveItem(bot, new ItemStack(p, 128));
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 256));        // fill material for FLATTEN's dig-high-fill-low leveling
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 128));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE, 1));
        ServerLevel world = bot.level();
        final int deathBase = deathCount(bot);
        java.util.Set<Block> plankBlocks = new java.util.HashSet<>();
        for (Item planks : io.github.zoyluo.minecraftai.craft.RecipeRegistry.PLANKS) {
            Block block = Block.byItem(planks);
            if (block != Blocks.AIR) {
                plankBlocks.add(block);
            }
        }
        if (!GoalExecutor.INSTANCE.submit(bot, new Goal.Build("small_hut"))) {
            return Result.fail("real_build", "goal_submit_failed");
        }
        // The count anchor targets the actual house-building spot: the assertion is evaluated when BuildTask completes (see pollActive), at which point the bot is right at the finished hut;
        // SiteFinder's automatic site selection may end up far from or below the spawn point (origin), so the scan is symmetric around the bot's current position instead (±10 horizontal, -6..8 vertical),
        // rather than an origin-anchored "only above" — this fixes the assertion-anchor limitation where a house finished 116/116 with zero deaths was still missed by an origin-centered count.
        return Result.runningGoal("real_build", 20000,
                ignored -> bot.isAlive() && deathCount(bot) == deathBase
                        && countNearbyBlocks(world, bot.blockPosition(), 10, -6, 8, plankBlocks) >= 80);
    }

    // snapshot placement helper: places one block in its default state at a relative coordinate (pairs with the setRel lines exported by /minecraftai snapshot).
    static void setRel(ServerLevel world, BlockPos origin, int dx, int dy, int dz, String id) {
        net.minecraft.world.level.block.Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getOptional(net.minecraft.resources.Identifier.parse(id)).orElse(null);
        if (block == null) {
            io.github.zoyluo.minecraftai.log.BotLog.config("snapshot_unknown_block", "id", id);
            return;
        }
        world.setBlock(origin.offset(dx, dy, dz), block.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    // Real play: one block of obsidian from scratch. In the natural world, obsidian requires "find a lava lake + pour water" — the bot currently lacks this capability,
    // so this scenario is expected to FAIL, kept as evidence of the missing capability and a fix target (turns green once it's fixed).
    private static Result assignRealObsidian(AIPlayerEntity bot) {
        prepareRealistic(bot);
        final int deathBase = deathCount(bot); // zero-death red line: dying and respawning is also judged FAIL (in real play, dying once is a major incident)
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.OBSIDIAN, 1));
        if (!started) {
            return Result.fail("real_obsidian", "goal_submit_failed");
        }
        return Result.runningGoal("real_obsidian", 12000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.OBSIDIAN) >= 1
                        && deathCount(bot) == deathBase);
    }

    // Mining First M1-prepared: the deterministic canvas provides a 35-cell sunken lava pool, a diamond pickaxe, and a single water bucket, but does not pre-place or grant obsidian.
    // It only verifies the long-quota planner/task wiring; only an implementation that actually pours water and relies on vanilla fluid reactions can graduate to final acceptance.
    private static Result assignObsidianHalfStack32Prepared(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin);
        // A 7×5 sunken source pool (35 blocks) + a stone rim: real water flow sweeps across the pool surface converting it in bulk, then it's mined block by block after the water is recovered.
        // This is also the safe collection method players commonly use, avoiding an isolated same-plane source spreading endlessly across the whole canvas.
        for (int dx = 4; dx <= 10; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.setBlock(origin.offset(dx, -1, dz), Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        world.setBlock(origin.offset(3, 0, 0), Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
        bot.teleportTo(world, origin.getX() + 3.5D, origin.getY() + 1.0D, origin.getZ() + 0.5D,
                java.util.Collections.emptySet(), bot.getYRot(), bot.getXRot(), true);
        // A refillable 2x2 water source; the target implementation should genuinely pour/recover water rather than writing obsidian blocks directly.
        for (int dx = -3; dx <= -2; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                world.setBlock(origin.offset(dx, 0, dz), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_PICKAXE, 1));
        // Consistent with the from-zero contract: only one bucket exists for the whole run, so any single failure to recover it immediately shows up as a task failure.
        InventoryAction.giveItem(bot, new ItemStack(Items.WATER_BUCKET, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 4));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 32));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        final int deathBase = deathCount(bot);
        boolean started = GoalExecutor.INSTANCE.submit(
                bot, new Goal.HaveItem(Items.OBSIDIAN, OBSIDIAN_HALF_STACK_TARGET));
        if (!started) {
            return Result.fail("obsidian_half_stack_32_prepared", "goal_submit_failed");
        }
        return Result.runningGoal("obsidian_half_stack_32_prepared", 24000,
                ignored -> {
                    // Prepared isolates the long quota/fluid/pickup pipeline. Keep its 12-minute
                    // laboratory run in daylight; hostile survival remains a from-zero gate.
                    world.setDayTime(1000L);
                    return bot.isAlive()
                            && InventoryAction.countItem(bot, Items.OBSIDIAN) >= OBSIDIAN_HALF_STACK_TARGET
                            && deathCount(bot) == deathBase;
                });
    }

    // The user-commitment-baseline prepared layer: structurally identical to the 32 version, with the pool expanded to 10×7 (70 sources ≥ 64); supplies scaled
    // to the 64-target contract (food follows MiningBudget.obsidianExpeditionFoodTarget), and the timeout doubled at the same per-block rate.
    private static Result assignObsidianStack64Prepared(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin);
        for (int dx = 4; dx <= 13; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                world.setBlock(origin.offset(dx, -1, dz),
                        Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        world.setBlock(origin.offset(3, 0, 0),
                Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
        bot.teleportTo(world, origin.getX() + 3.5D, origin.getY() + 1.0D, origin.getZ() + 0.5D,
                java.util.Collections.emptySet(), bot.getYRot(), bot.getXRot(), true);
        for (int dx = -3; dx <= -2; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                world.setBlock(origin.offset(dx, 0, dz),
                        Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_PICKAXE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.WATER_BUCKET, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF,
                MiningBudget.obsidianExpeditionFoodTarget(OBSIDIAN_STACK_TARGET)));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 4));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        final int deathBase = deathCount(bot);
        boolean started = GoalExecutor.INSTANCE.submit(
                bot, new Goal.HaveItem(Items.OBSIDIAN, OBSIDIAN_STACK_TARGET));
        if (!started) {
            return Result.fail("obsidian_stack_64_prepared", "goal_submit_failed");
        }
        return Result.runningGoal("obsidian_stack_64_prepared",
                OBSIDIAN_STACK_64_PREPARED_TIMEOUT,
                ignored -> {
                    world.setDayTime(1000L);
                    return bot.isAlive()
                            && InventoryAction.countItem(bot, Items.OBSIDIAN)
                            >= OBSIDIAN_STACK_TARGET
                            && deathCount(bot) == deathBase;
                });
    }

    // The user-commitment-baseline from-zero layer: structurally identical to the 32 version, with both the quota and the audit threshold at 64, and the timeout derived incrementally.
    // Just like the 32 contract, while unmet it must honestly FAIL/MISSING; the certification long-run gate is calibrated separately.
    private static Result assignObsidianStack64FromZero(AIPlayerEntity bot) {
        prepareMiningFromZero(bot);
        MiningEvidenceAudit.begin(bot, MiningEvidenceAudit.Target.OBSIDIAN,
                OBSIDIAN_STACK_TARGET);
        final int deathBase = deathCount(bot);
        boolean started = GoalExecutor.INSTANCE.submit(
                bot, new Goal.HaveItem(Items.OBSIDIAN, OBSIDIAN_STACK_TARGET));
        if (!started) {
            return Result.fail("obsidian_stack_64_from_zero", "goal_submit_failed");
        }
        return Result.runningGoalFailFast("obsidian_stack_64_from_zero",
                OBSIDIAN_STACK_64_FROM_ZERO_TIMEOUT,
                miningFromZeroFailFast(deathBase),
                ignored -> bot.isAlive()
                        && InventoryAction.countItem(bot, Items.OBSIDIAN)
                        >= OBSIDIAN_STACK_TARGET
                        && deathCount(bot) == deathBase
                        && MiningEvidenceAudit.snapshot(bot)
                        .filter(MiningEvidenceAudit.Snapshot::passes).isPresent());
    }

    // Mining First M1-final: natural terrain, empty inventory — obtain a diamond pickaxe/bucket on its own, find lava, and obtain half a stack of obsidian.
    // This is the only scenario the capability manifest may pin; while it isn't met it must honestly FAIL/MISSING.
    private static Result assignObsidianHalfStack32FromZero(AIPlayerEntity bot) {
        prepareMiningFromZero(bot);
        MiningEvidenceAudit.begin(bot, MiningEvidenceAudit.Target.OBSIDIAN);
        final int deathBase = deathCount(bot);
        boolean started = GoalExecutor.INSTANCE.submit(
                bot, new Goal.HaveItem(Items.OBSIDIAN, OBSIDIAN_HALF_STACK_TARGET));
        if (!started) {
            return Result.fail("obsidian_half_stack_32_from_zero", "goal_submit_failed");
        }
        return Result.runningGoalFailFast("obsidian_half_stack_32_from_zero", 240000,
                miningFromZeroFailFast(deathBase),
                ignored -> bot.isAlive()
                        && InventoryAction.countItem(bot, Items.OBSIDIAN) >= OBSIDIAN_HALF_STACK_TARGET
                        && deathCount(bot) == deathBase
                        && MiningEvidenceAudit.snapshot(bot)
                        .filter(MiningEvidenceAudit.Snapshot::passes).isPresent());
    }

    // Real play: long-distance navigation on natural terrain — target = a natural surface point 120 blocks east (there may be lakes/cliffs/dense forest along the way, testing detouring and fault tolerance).
    // Doesn't verify whether the path is pretty, only that it can "get there": within ≤3 blocks of the target passes. This is the shared prerequisite capability behind every real_* "walk over and do it",
    // pulled out and tested on its own so that if navigation breaks, it's immediately clear it's a "walking" problem, not a gathering/crafting one.
    private static Result assignRealNavFar(AIPlayerEntity bot) {
        BlockPos start = prepareRealistic(bot);
        ServerLevel world = bot.level();
        int gx = start.getX() + 120;
        int gz = start.getZ();
        // Uses the MOTION_BLOCKING heightmap to get a natural surface footing y (including leaves/water surfaces), consistent with "a player eyeballing a surface spot"
        int gy = world.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, gx, gz);
        BlockPos goal = new BlockPos(gx, gy, gz);
        return assignTask(bot, "real_nav_far", new MoveTask(bot, goal), 6000,
                ignored -> bot.isAlive() && bot.blockPosition().distSqr(goal) <= 9.0D);
    }

    // ==================== R2 LLM full-chain layer (llm_*) ====================
    // This layer tests the full real-play chain of "colloquial-language instruction → LLM intent parsing → tool selection → parameterization → execution":
    // the entry point is exactly the same as a player chatting @bot (BrainCoordinator.handleMessage, entering on the server thread,
    // calling the LLM asynchronously, then the response returns to the server thread to execute the tool). Must be run with WITH_LLM=1 — the test script
    // unsets MINECRAFTAI_LLM_API_KEY by default to isolate the brain, preventing the deterministic suites from billing silently; this is also why llm_* is excluded from ALL_FEATURES.
    // Judging always uses patient mode (see pollActive): the brain is conversation-driven and will dispatch multiple tasks in a row, retry with a different approach on failure,
    // and idle-think between tasks — a single task's COMPLETED/FAILED is never the scenario's final word; only "the world-state assertion is satisfied" or a timeout counts.
    // The starting standard matches real_*: prepareRealistic's natural world with nothing granted + deathBase's zero-death red line.

    /**
     * llm_* common startup: reset the brain session/goal plan/leftover tasks → prepareRealistic (natural world, nothing granted) →
     * the instruction is handed to the brain via handleMessage (the same entry point as a player chatting @bot).
     * Reason for the reset: when the previous llm scenario's assertion is satisfied, the brain is often still busy thinking ahead (busy); while busy, handleMessage
     * rejects new messages and returns false, so skipping the reset would cause cross-talk misjudgments between scenarios in a suite; resetToIdle also clears any leftover failure record,
     * preventing a new session from starting out already contaminated with the previous scenario's "last task failed".
     * Checks the key up front: when the key is missing, handleMessage still returns true (only the async request later reports llm_api_key_missing),
     * so without this check the test would just have to wait out the full timeout before FAILing. A null return means the instruction was submitted; a non-null return is a FAIL to record immediately.
     */
    private static Result startLlmScenario(AIPlayerEntity bot, String feature, String instruction) {
        BrainCoordinator.INSTANCE.reset(bot);
        GoalExecutor.INSTANCE.clear(bot);
        TaskManager.INSTANCE.resetToIdle(bot);
        prepareRealistic(bot);
        if (MinecraftAiConfig.get().llm().apiKey().isBlank()
                || !BrainCoordinator.INSTANCE.handleMessage(bot, "Tester", instruction)) {
            return Result.fail(feature, "brain_rejected_or_not_configured (run WITH_LLM=1)");
        }
        return null;
    }

    // Real play (LLM): a colloquial movement instruction → the brain should parse out the intent of "go near (120, z=0)" and pick a movement tool (move_to, etc.).
    // The assertion looks only at the world result: the bot's horizontal distance to (120, 0) is ≤ 8 blocks (ignoring y, whose footing height is decided by natural terrain), plus zero deaths.
    private static Result assignLlmMove(AIPlayerEntity bot) {
        Result rejected = startLlmScenario(bot, "llm_move", "Go over near coordinates x=120 z=0");
        if (rejected != null) {
            return rejected;
        }
        final int deathBase = deathCount(bot); // zero-death red line: dying and respawning is also judged FAIL (copied from the real_* standard)
        return Result.runningPatient("llm_move", 6000,
                ignored -> {
                    double dx = bot.getX() - 120.0D;
                    double dz = bot.getZ();
                    return bot.isAlive() && dx * dx + dz * dz <= 64.0D && deathCount(bot) == deathBase;
                });
    }

    // Real play (LLM): a colloquial food instruction → the brain should parse the intent and quantity parameter of "at least 4 cooked food", choosing a source on its own
    // (hunt + cook / farm for bread / forage), the same world standard as real_food but driven by the real brain instead of directly submitting a Goal.
    private static Result assignLlmFood(AIPlayerEntity bot) {
        Result rejected = startLlmScenario(bot, "llm_food", "Go get something to eat, get at least 4 cooked food");
        if (rejected != null) {
            return rejected;
        }
        final int deathBase = deathCount(bot); // zero-death red line: dying and respawning is also judged FAIL (copied from the real_* standard)
        return Result.runningPatient("llm_food", 16000,
                ignored -> bot.isAlive() && safeFoodUnits(bot) >= 4 && deathCount(bot) == deathBase);
    }

    // Real play (LLM): a colloquial mineral instruction → the brain should map "mine an iron ingot" onto the full achieve_goal/mine_ore chain
    // (chop tree → wood pickaxe → mine stone → stone pickaxe → find iron → mine → craft a furnace → smelt), the same world standard as real_iron.
    private static Result assignLlmIron(AIPlayerEntity bot) {
        Result rejected = startLlmScenario(bot, "llm_iron", "Help me mine an iron ingot");
        if (rejected != null) {
            return rejected;
        }
        final int deathBase = deathCount(bot); // zero-death red line: dying and respawning is also judged FAIL (copied from the real_* standard)
        return Result.runningPatient("llm_iron", 24000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.IRON_INGOT) >= 1
                        && deathCount(bot) == deathBase);
    }

    // Real play (LLM) · flagship: a colloquial diamond instruction → the brain should run the entire deep chain end to end (iron-pickaxe prerequisite → dive deep → underground ore prospecting → mine diamond).
    // This is the deepest gap on real terrain (observed in campaigns: real_diamond hit no_resource on two seeds): surface foraging has already been
    // solved by EXPLORE, but directed underground prospecting is still missing. Verified through the real conversational layer, so whatever it snags on is the next capability to fix (data-driven, not guesswork).
    // Timeout given generously (finding diamond underground is inherently slow); the zero-death red line still applies — a single slip into deep lava/a fall/a mob is judged FAIL, forcing genuine robustness.
    private static Result assignLlmDiamond(AIPlayerEntity bot) {
        Result rejected = startLlmScenario(bot, "llm_diamond", "Help me mine a diamond");
        if (rejected != null) {
            return rejected;
        }
        final int deathBase = deathCount(bot);
        return Result.runningPatient("llm_diamond", 48000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.DIAMOND) >= 1
                        && deathCount(bot) == deathBase);
    }

    // ==================== Conversational assistant layer (assistant_suite) ====================
    // Verifies four new foundations of the assistant layer (previously only compiled, never run-verified): P0 goal queue / P1 Goal.Build auto-provisioning /
    // P3 parameterized blueprints / P2 player messages preserving the in-progress goal. All deterministic lab scenarios (prepareArea's artificial platform);
    // none go through the LLM or burn API calls — this tests the assistant layer's execution foundation, while the brain-driven full chain is covered separately by llm_suite.

    /**
     * P0 goal queue end to end: submit two goals back to back — the first (sticks×4) starts immediately; the second (crafting table×1), while
     * an active goal exists, should go through GoalExecutor.goalQueue and **enqueue**, returning true (returning false = a queue regression, instant FAIL);
     * once the first completes, advanceQueue automatically dequeues and starts executing the second. 4 raw logs are enough for both chains: the stick chain consumes 1 log (→4 planks, 2 planks make 4 sticks),
     * the table chain consumes another log (the remaining 2 planks plus 4 more → a crafting table). Assert both goals' products **arrive together** (sticks≥4 and crafting table≥1)
     * and zero deaths — the assertion can only hold if the second goal was genuinely picked up and executed.
     */
    private static Result assignGoalQueue(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 4));
        final int deathBase = deathCount(bot); // zero-death red line (copied from the real_* standard)
        if (!GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.STICK, 4))) {
            return Result.fail("goal_queue", "goal_submit_failed");
        }
        if (!GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.CRAFTING_TABLE, 1))) {
            return Result.fail("goal_queue", "second_submit_rejected"); // P0 regression: the second goal should enqueue and return true
        }
        return Result.runningGoal("goal_queue", 4800,
                ignored -> bot.isAlive()
                        && InventoryAction.countItem(bot, Items.STICK) >= 4
                        && InventoryAction.countItem(bot, Items.CRAFTING_TABLE) >= 1
                        && deathCount(bot) == deathBase);
    }

    /**
     * P0-02a no resurrection after cancellation: simultaneously establish a Goal, an active Task, and a BotMemory long-term goal, then go through production
     * IntentController.cancelAll. Wait 200 ticks and confirm the old Goal, queue, Task, paused Task, memory goal, and
     * Brain decision all stay silent. This waiting window specifically guards against "the Task was stopped, but the Goal/memory dispatches it right back on the next tick".
     */
    private static Result assignCancelNoResurrection(AIPlayerEntity bot) {
        prepareRuntimeControlArea(bot);
        Goal current = new Goal.HaveItem(Items.STICK, 4);
        Goal queued = new Goal.HaveItem(Items.CRAFTING_TABLE, 1);
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 4));
        if (!GoalExecutor.INSTANCE.submit(bot, current)
                || !GoalExecutor.INSTANCE.submit(bot, queued)
                || TaskManager.INSTANCE.getActive(bot).isEmpty()
                || GoalExecutor.INSTANCE.queuedGoalCount(bot) != 1) {
            return Result.fail("cancel_no_resurrection", "goal_or_task_not_started");
        }
        Task pausedTask = TaskManager.INSTANCE.getActive(bot).orElseThrow();
        TaskManager.INSTANCE.pauseFor(bot, "verify_cancel_paused");
        HoldTask activeTask = new HoldTask();
        TaskManager.INSTANCE.assign(bot, activeTask,
                io.github.zoyluo.minecraftai.runtime.TaskOrigin.of(io.github.zoyluo.minecraftai.runtime.TaskOrigin.Kind.VERIFY, "cancel_setup"));
        if (!TaskManager.INSTANCE.hasPaused(bot)
                || pausedTask.state() != TaskState.PAUSED
                || TaskManager.INSTANCE.getActive(bot).orElse(null) != activeTask) {
            IntentController.INSTANCE.cancelAll(bot, IntentController.ControlOrigin.SYSTEM, "verify_setup_cleanup");
            return Result.fail("cancel_no_resurrection", "active_paused_setup_failed");
        }
        BotMemoryStore.INSTANCE.of(bot.getUUID()).setGoal("verify_cancel", List.of("craft sticks"));
        TaskManager.INSTANCE.recordFailure(bot, "verify_old_task", "verify_old_failure", bot.level().getServer().getTickCount());
        bot.getFoodData().setFoodLevel(10);
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 1));
        if (EatAction.startEating(bot).isFailed() || !bot.isUsingItem()) {
            IntentController.INSTANCE.cancelAll(bot, IntentController.ControlOrigin.SYSTEM, "verify_setup_cleanup");
            return Result.fail("cancel_no_resurrection", "using_item_setup_failed");
        }
        // Immediately restore hunger right after establishing the isUsingItem action, to prevent DangerWatcher from legitimately dispatching a new EatTask after cancellation;
        // this scenario only checks whether the old Mission/Task/memory resurrects, and must not mistake new work from the safety layer for the old intent.
        bot.getFoodData().setFoodLevel(20);
        bot.getFoodData().setSaturation(5.0F);
        var outcome = IntentController.INSTANCE.cancelAll(
                bot, IntentController.ControlOrigin.SYSTEM, "verify_cancel_no_resurrection");
        if (!outcome.currentMissionDetached()
                || outcome.queuedMissionsCleared() != 1
                || !outcome.workCancelled()
                || !outcome.longTermGoalCleared()
                || activeTask.state() != TaskState.CANCELLED
                || pausedTask.state() != TaskState.CANCELLED) {
            return Result.fail("cancel_no_resurrection", "incomplete_cancel_outcome=" + outcome);
        }
        int quietUntilTick = bot.level().getServer().getTickCount() + 200;
        boolean[] violated = {false};
        return Result.runningPatient("cancel_no_resurrection", 260,
                ignored -> {
                    boolean cleanNow = !GoalExecutor.INSTANCE.hasActivePlan(bot)
                        && GoalExecutor.INSTANCE.queuedGoalCount(bot) == 0
                        && TaskManager.INSTANCE.getActive(bot).isEmpty()
                        && !TaskManager.INSTANCE.hasPaused(bot)
                        && TaskManager.INSTANCE.peekFailure(bot).isEmpty()
                        && TaskManager.INSTANCE.status(bot).state() == TaskState.CANCELLED
                        && activeTask.state() == TaskState.CANCELLED
                        && pausedTask.state() == TaskState.CANCELLED
                        && !BotMemoryStore.INSTANCE.of(bot.getUUID()).hasActiveGoal()
                        && !BrainCoordinator.INSTANCE.status(bot).busy()
                        && !bot.getActionPack().hasActiveActions()
                        && !bot.isUsingItem()
                        && InventoryAction.countItem(bot, Items.STICK) == 0
                        && InventoryAction.countItem(bot, Items.CRAFTING_TABLE) == 0
                        && InventoryAction.countItem(bot, Items.BREAD) == 1;
                    violated[0] |= !cleanNow;
                    return bot.level().getServer().getTickCount() >= quietUntilTick && !violated[0];
                });
    }

    /** cancel-current retried within the same tick must be idempotent; the head of the queue is only promoted on the next tick, and afterward must actually complete. */
    private static Result verifyCancelCurrentQueue(AIPlayerEntity bot) {
        prepareRuntimeControlArea(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 4));
        Goal current = new Goal.HaveItem(Items.STICK, 4);
        Goal queued = new Goal.HaveItem(Items.CRAFTING_TABLE, 1);
        if (!GoalExecutor.INSTANCE.submit(bot, current)
                || !GoalExecutor.INSTANCE.submit(bot, queued)) {
            return Result.fail("cancel_current_queue", "goal_setup_failed");
        }

        var outcome = IntentController.INSTANCE.cancelCurrent(
                bot, IntentController.ControlOrigin.SYSTEM, "verify_cancel_current_queue");
        var repeated = IntentController.INSTANCE.cancelCurrent(
                bot, IntentController.ControlOrigin.SYSTEM, "verify_cancel_current_queue_repeat");
        boolean deferredCleanly = outcome.currentMissionDetached()
                && outcome.workCancelled()
                && !repeated.changed()
                && !GoalExecutor.INSTANCE.hasActivePlan(bot)
                && GoalExecutor.INSTANCE.queuedGoalCount(bot) == 1
                && TaskManager.INSTANCE.getActive(bot).isEmpty();
        if (!deferredCleanly) {
            String detail = "outcome=" + outcome
                    + " repeated=" + repeated
                    + " active=" + GoalExecutor.INSTANCE.describeActiveGoal(bot)
                    + " queued=" + GoalExecutor.INSTANCE.queuedGoalCount(bot);
            IntentController.INSTANCE.cancelAll(bot, IntentController.ControlOrigin.SYSTEM, "verify_cleanup");
            return Result.fail("cancel_current_queue", detail);
        }
        return Result.runningGoal("cancel_current_queue", 1200,
                ignored -> InventoryAction.countItem(bot, Items.CRAFTING_TABLE) >= 1
                        && InventoryAction.countItem(bot, Items.STICK) == 0
                        && GoalExecutor.INSTANCE.queuedGoalCount(bot) == 0);
    }

    /**
     * Simulates a stop + achieve_goal(B) within a single LLM response: stop preserves the current APPLYING lease without prematurely promoting B;
     * the second Tool call installs B immediately and removes the same Goal from the old queue, preventing it from running a second time after B completes.
     */
    private static Result verifyReplaceQueuedGoal(AIPlayerEntity bot) {
        prepareRuntimeControlArea(bot);
        if (MinecraftAiConfig.get().brain().maxToolCallsPerTurn() < 2) {
            return Result.fail("replace_queued_goal", "bad_config_max_tool_calls");
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 4));
        Goal current = new Goal.HaveItem(Items.STICK, 4);
        Goal replacement = new Goal.HaveItem(Items.CRAFTING_TABLE, 1);
        if (!GoalExecutor.INSTANCE.submit(bot, current)
                || !GoalExecutor.INSTANCE.submit(bot, replacement)) {
            return Result.fail("replace_queued_goal", "goal_setup_failed");
        }

        ActionDispatcher.DispatchBatch batch = new ActionDispatcher(new ToolRegistry()).dispatchBatch(
                bot,
                List.of(
                        new ChatToolCall("verify-stop", "stop", "{}"),
                        new ChatToolCall("verify-replacement", "achieve_goal",
                                "{\"item\":\"minecraft:crafting_table\",\"count\":1}")),
                () -> true);
        boolean installedOnce = batch.messages().size() == 2
                && batch.controlEffect() == ActionDispatcher.ControlEffect.CANCEL_CURRENT
                && GoalExecutor.INSTANCE.isActiveGoal(bot, replacement)
                && GoalExecutor.INSTANCE.queuedGoalCount(bot) == 0
                && TaskManager.INSTANCE.getActive(bot).isPresent();
        if (!installedOnce) {
            String detail = "messages=" + batch.messages().size()
                    + " effect=" + batch.controlEffect()
                    + " active=" + GoalExecutor.INSTANCE.describeActiveGoal(bot)
                    + " queued=" + GoalExecutor.INSTANCE.queuedGoalCount(bot);
            IntentController.INSTANCE.cancelAll(bot, IntentController.ControlOrigin.SYSTEM, "verify_cleanup");
            return Result.fail("replace_queued_goal", detail);
        }
        return Result.runningGoal("replace_queued_goal", 1200,
                ignored -> InventoryAction.countItem(bot, Items.CRAFTING_TABLE) >= 1
                        && InventoryAction.countItem(bot, Items.STICK) == 0
                        && GoalExecutor.INSTANCE.queuedGoalCount(bot) == 0);
    }

    /**
     * stop + move_to has no Task/Goal, so continuation must be maintained by ActionPack alone. The explicit queue must wait for the action to finish
     * before it can start — GoalExecutor must not preempt the move on the next tick — and the old Goal must not resurrect either.
     */
    private static Result verifyReplaceActionOnly(AIPlayerEntity bot) {
        prepareRuntimeControlArea(bot);
        if (MinecraftAiConfig.get().brain().maxToolCallsPerTurn() < 2) {
            return Result.fail("replace_action_only", "bad_config_max_tool_calls");
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 4));
        Goal oldGoal = new Goal.HaveItem(Items.STICK, 4);
        Goal queuedGoal = new Goal.HaveItem(Items.CRAFTING_TABLE, 1);
        if (!GoalExecutor.INSTANCE.submit(bot, oldGoal)
                || !GoalExecutor.INSTANCE.submit(bot, queuedGoal)) {
            return Result.fail("replace_action_only", "goal_setup_failed");
        }
        BlockPos target = bot.blockPosition().offset(6, 0, 0);
        String moveArgs = "{\"x\":" + target.getX()
                + ",\"y\":" + target.getY()
                + ",\"z\":" + target.getZ() + "}";
        ActionDispatcher.DispatchBatch batch = new ActionDispatcher(new ToolRegistry()).dispatchBatch(
                bot,
                List.of(
                        new ChatToolCall("verify-action-stop", "stop", "{}"),
                        new ChatToolCall("verify-action-move", "move_to", moveArgs)),
                () -> true);
        boolean actionInstalled = batch.messages().size() == 2
                && batch.controlEffect() == ActionDispatcher.ControlEffect.CANCEL_CURRENT
                && !GoalExecutor.INSTANCE.hasActivePlan(bot)
                && GoalExecutor.INSTANCE.queuedGoalCount(bot) == 1
                && TaskManager.INSTANCE.getActive(bot).isEmpty()
                && (!bot.getActionPack().isPathExecutorIdle() || !bot.getActionPack().isWalkToIdle());
        if (!actionInstalled) {
            String detail = "messages=" + batch.messages().size()
                    + " effect=" + batch.controlEffect()
                    + " active_goal=" + GoalExecutor.INSTANCE.describeActiveGoal(bot)
                    + " task=" + TaskManager.INSTANCE.status(bot).name()
                    + " action=" + bot.getActionPack().hasActiveActions();
            IntentController.INSTANCE.cancelAll(bot, IntentController.ControlOrigin.SYSTEM, "verify_cleanup");
            return Result.fail("replace_action_only", detail);
        }
        bot.getFoodData().setFoodLevel(10); // not critical, but reaches the auto-eat threshold: must not preempt the move action
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 1));
        String[] violation = {null};
        boolean[] violationLogged = {false};
        return Result.runningPatient("replace_action_only", 1200,
                ignored -> {
                    boolean reachedTarget = bot.blockPosition().distSqr(target) <= 4.0D;
                    boolean moveActive = !bot.getActionPack().isPathExecutorIdle()
                            || !bot.getActionPack().isWalkToIdle();
                    if (violation[0] == null && moveActive
                            && GoalExecutor.INSTANCE.hasActivePlan(bot)) {
                        violation[0] = "goal_started_while_move_active";
                    }
                    if (violation[0] == null && moveActive
                            && GoalExecutor.INSTANCE.queuedGoalCount(bot) != 1) {
                        violation[0] = "queue_changed_while_move_active";
                    }
                    if (violation[0] == null && moveActive
                            && TaskManager.INSTANCE.getActive(bot).isPresent()) {
                        violation[0] = "task_started_while_move_active";
                    }
                    if (violation[0] == null && moveActive
                            && (InventoryAction.countItem(bot, Items.CRAFTING_TABLE) > 0
                            || InventoryAction.countItem(bot, Items.STICK) > 0)) {
                        violation[0] = "old_or_queued_product_while_move_active";
                    }
                    if (violation[0] == null && !reachedTarget && GoalExecutor.INSTANCE.hasActivePlan(bot)) {
                        violation[0] = "goal_started_before_target";
                    }
                    if (violation[0] == null && !reachedTarget && TaskManager.INSTANCE.getActive(bot).isPresent()) {
                        violation[0] = "task_started_before_target";
                    }
                    if (violation[0] == null && !reachedTarget
                            && InventoryAction.countItem(bot, Items.CRAFTING_TABLE) > 0) {
                        violation[0] = "queued_product_before_target";
                    }
                    if (violation[0] == null && !reachedTarget
                            && GoalExecutor.INSTANCE.queuedGoalCount(bot) != 1) {
                        violation[0] = "queue_changed_before_target";
                    }
                    if (violation[0] == null && GoalExecutor.INSTANCE.hasActivePlan(bot)
                            && !GoalExecutor.INSTANCE.isActiveGoal(bot, queuedGoal)) {
                        violation[0] = "unexpected_goal_identity";
                    }
                    if (violation[0] != null && !violationLogged[0]) {
                        violationLogged[0] = true;
                        io.github.zoyluo.minecraftai.log.BotLog.warn(
                                io.github.zoyluo.minecraftai.log.LogCategory.TASK,
                                bot,
                                "verify_action_replacement_violation",
                                "reason", violation[0]);
                    }
                    return violation[0] == null
                            && reachedTarget
                            && !bot.getActionPack().hasActiveActions()
                            && InventoryAction.countItem(bot, Items.CRAFTING_TABLE) >= 1
                            && InventoryAction.countItem(bot, Items.STICK) == 0
                            && GoalExecutor.INSTANCE.queuedGoalCount(bot) == 0;
                });
    }

    /** when replacement's Task.onStart throws, partial active state must be rolled back, and the preserved queue restored on the next tick. */
    private static Result verifyReplaceStartFailure(AIPlayerEntity bot) {
        prepareRuntimeControlArea(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 4));
        Goal oldGoal = new Goal.HaveItem(Items.STICK, 4);
        Goal queuedGoal = new Goal.HaveItem(Items.CRAFTING_TABLE, 1);
        if (!GoalExecutor.INSTANCE.submit(bot, oldGoal)
                || !GoalExecutor.INSTANCE.submit(bot, queuedGoal)) {
            return Result.fail("replace_start_failure", "goal_setup_failed");
        }
        Task broken = new AbstractTask() {
            @Override
            public String name() {
                return "verify_broken_start";
            }

            @Override
            public String describe() {
                return "throws from onStart";
            }

            @Override
            public double progress() {
                return 0.0D;
            }

            @Override
            protected void onStart(AIPlayerEntity ignored) {
                throw new IllegalStateException("verify_start_failure");
            }

            @Override
            protected void onTick(AIPlayerEntity ignored) {
            }
        };
        boolean expectedFailure = false;
        try {
            IntentController.INSTANCE.replace(
                    bot,
                    IntentController.ControlOrigin.PLAYER_COMMAND,
                    "verify_replace_start_failure",
                    () -> {
                        TaskManager.INSTANCE.assign(bot, broken,
                                io.github.zoyluo.minecraftai.runtime.TaskOrigin.of(io.github.zoyluo.minecraftai.runtime.TaskOrigin.Kind.VERIFY, "replace_failure"));
                        return true;
                    });
        } catch (IllegalStateException exception) {
            expectedFailure = "verify_start_failure".equals(exception.getMessage());
        }
        boolean rolledBack = expectedFailure
                && broken.state() == TaskState.FAILED
                && TaskManager.INSTANCE.getActive(bot).isEmpty()
                && !TaskManager.INSTANCE.hasPaused(bot)
                && TaskManager.INSTANCE.peekFailure(bot).isEmpty()
                && !GoalExecutor.INSTANCE.hasActivePlan(bot)
                && GoalExecutor.INSTANCE.queuedGoalCount(bot) == 1
                && !bot.getActionPack().hasActiveActions();
        if (!rolledBack) {
            String detail = "expected_failure=" + expectedFailure
                    + " broken=" + broken.state()
                    + " task=" + TaskManager.INSTANCE.status(bot).name()
                    + " pending_failure=" + TaskManager.INSTANCE.peekFailure(bot).isPresent()
                    + " active_goal=" + GoalExecutor.INSTANCE.describeActiveGoal(bot)
                    + " queued=" + GoalExecutor.INSTANCE.queuedGoalCount(bot);
            IntentController.INSTANCE.cancelAll(bot, IntentController.ControlOrigin.SYSTEM, "verify_cleanup");
            return Result.fail("replace_start_failure", detail);
        }
        return Result.runningGoal("replace_start_failure", 1200,
                ignored -> InventoryAction.countItem(bot, Items.CRAFTING_TABLE) >= 1
                        && InventoryAction.countItem(bot, Items.STICK) == 0
                        && GoalExecutor.INSTANCE.queuedGoalCount(bot) == 0);
    }

    private static Result verifyPauseResumeSafetyStack(AIPlayerEntity bot) {
        IntentController.INSTANCE.cancelAll(bot, IntentController.ControlOrigin.SYSTEM, "verify_pause_setup");
        Task mission = new io.github.zoyluo.minecraftai.task.HoldTask();
        Task safetyOne = new io.github.zoyluo.minecraftai.task.HoldTask();
        Task safetyTwo = new io.github.zoyluo.minecraftai.task.HoldTask();
        try {
            TaskManager.INSTANCE.assign(bot, mission,
                    io.github.zoyluo.minecraftai.runtime.TaskOrigin.mission(java.util.UUID.randomUUID(), "verify_mission"));
            TaskManager.INSTANCE.pauseUserIntent(bot, "verify_user_pause");
            if (!TaskManager.INSTANCE.isUserPaused(bot) || TaskManager.INSTANCE.pausedDepth(bot) != 1
                    || mission.state() != TaskState.PAUSED) {
                return Result.fail("pause_resume_safety_stack", "mission_pause_contract_failed");
            }

            TaskManager.INSTANCE.assign(bot, safetyOne,
                    io.github.zoyluo.minecraftai.runtime.TaskOrigin.safety("verify_combat"));
            TaskManager.INSTANCE.pauseFor(bot, "verify_nested_safety");
            TaskManager.INSTANCE.assign(bot, safetyTwo,
                    io.github.zoyluo.minecraftai.runtime.TaskOrigin.safety("verify_lava"));
            if (TaskManager.INSTANCE.pausedDepth(bot) != 2) {
                return Result.fail("pause_resume_safety_stack", "nested_stack_depth=" + TaskManager.INSTANCE.pausedDepth(bot));
            }

            TaskManager.INSTANCE.abort(bot);
            TaskManager.INSTANCE.resumeFromPause(bot);
            if (TaskManager.INSTANCE.getActive(bot).orElse(null) != safetyOne
                    || TaskManager.INSTANCE.pausedDepth(bot) != 1) {
                return Result.fail("pause_resume_safety_stack", "safety_lifo_resume_failed");
            }
            TaskManager.INSTANCE.abort(bot);
            TaskManager.INSTANCE.resumeFromPause(bot);
            if (TaskManager.INSTANCE.getActive(bot).isPresent()
                    || TaskManager.INSTANCE.pausedDepth(bot) != 1
                    || mission.state() != TaskState.PAUSED) {
                return Result.fail("pause_resume_safety_stack", "user_pause_resumed_mission_early");
            }

            TaskManager.INSTANCE.resumeUserIntent(bot, "verify_user_resume");
            if (TaskManager.INSTANCE.getActive(bot).orElse(null) != mission
                    || mission.state() != TaskState.RUNNING
                    || TaskManager.INSTANCE.pausedDepth(bot) != 0
                    || TaskManager.INSTANCE.isUserPaused(bot)) {
                return Result.fail("pause_resume_safety_stack", "mission_resume_contract_failed");
            }
            return Result.pass("pause_resume_safety_stack", "nested safety unwound without resuming user-paused mission");
        } finally {
            IntentController.INSTANCE.cancelAll(bot, IntentController.ControlOrigin.SYSTEM, "verify_pause_cleanup");
        }
    }

    private static void prepareRuntimeControlArea(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        clearNearbyMobs(bot.level(), bot.blockPosition());
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.getFoodData().setSaturation(5.0F);
    }

    /**
     * P1 Goal.Build auto-provisioning end to end: give only 32 raw logs, zero planks — ensureBuild must tally, block by block from the blueprint, that
     * 114 planks are needed (small_hut's measured baseline, see the assignBuild comment), backward-plan a "logs→planks" CRAFT step and craft all of it,
     * then let the BUILD step finish the house (pure building is already covered by the build scenario; this scenario pins down the provisioning chain).
     * Assertion: within origin ±14, y∈[origin.y, origin.y+8], plank-family blocks ≥80 (the full house is 112 blocks, leaving margin) and zero deaths.
     * See the countNearbyBlocksAbove comment for the "above" baseline rationale (planks aren't in the same family as the stone floor, but a unified baseline between the two house-building scenarios is more robust).
     */
    private static Result assignGoalBuildAuto(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 32)); // raw logs only: the 114 planks must be figured out and crafted on its own
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        final int deathBase = deathCount(bot);
        java.util.Set<Block> plankBlocks = new java.util.HashSet<>();
        for (Item planks : io.github.zoyluo.minecraftai.craft.RecipeRegistry.PLANKS) {
            Block block = Block.byItem(planks);
            if (block != Blocks.AIR) {
                plankBlocks.add(block);
            }
        }
        if (!GoalExecutor.INSTANCE.submit(bot, new Goal.Build("small_hut"))) {
            return Result.fail("goal_build_auto", "goal_submit_failed");
        }
        return Result.runningGoal("goal_build_auto", 9600,
                ignored -> bot.isAlive()
                        && countNearbyBlocksAbove(world, origin, 14, plankBlocks) >= 80
                        && deathCount(bot) == deathBase);
    }

    /**
     * P3 parameterized blueprint end to end: Goal.Build("custom:5x4x3:stone_like") reads no blueprint file — it's generated on the fly by
     * BlueprintSchema.parametricHouse from the spec (outer footprint 5×4, net wall height 3: floor 20 + walls 42 - door 2 + roof 20 = 80 cells,
     * palette=stone_like). Given ample cobblestone, 128 (the provisioning chain judges materials "already satisfied" with zero collection, focusing on parametric geometry + palette building).
     * Assertion: within ±14, y∈[origin.y, origin.y+8], stone_like building material (cobblestone/stone/stone bricks) ≥40 (half the house passes, tolerating
     * a few missing cells) and zero deaths. Must use the "above" baseline: the lab platform's floor (y-1 cobblestone, 16 solid-stone layers below) shares the same family as the building material,
     * and counting it would misjudge "no house built" as a PASS — the house's floor layer lands exactly on origin.y (SiteFinder anchors to a standable foothold), so there's zero loss.
     */
    private static Result assignGoalBuildCustom(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 128));
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        final int deathBase = deathCount(bot);
        java.util.Set<Block> stoneLike = java.util.Set.of(Blocks.COBBLESTONE, Blocks.STONE, Blocks.STONE_BRICKS);
        if (!GoalExecutor.INSTANCE.submit(bot, new Goal.Build("custom:5x4x3:stone_like"))) {
            return Result.fail("goal_build_custom", "goal_submit_failed");
        }
        return Result.runningGoal("goal_build_custom", 7200,
                ignored -> bot.isAlive()
                        && countNearbyBlocksAbove(world, origin, 14, stoneLike) >= 40
                        && deathCount(bot) == deathBase);
    }

    /**
     * P2 interrupt-preserving goal (mechanism layer, no LLM decision involved): submit a mine-cobblestone goal (the platform below is all artificial stone, so DigDownTask needs
     * several hundred ticks to dig), and while it's executing, use BrainCoordinator.handleMessage to simulate a player's idle chat — the exact same entry point as a player chatting @bot.
     * P2 semantics: while an active plan exists, a new message must **not clear the goal**, only resolve the busy state (the old behavior of "new message = redirect, clear the goal" would
     * outright kill the goal that's currently mining). Right after the message, assert hasActivePlan is still true (false = a P2 regression); then finish by asserting "the goal completes as normal"
     * (cobblestone≥6 and zero deaths) — preserving semantics isn't just about not clearing it, the goal must actually finish. With no LLM key, handleMessage only
     * reports the missing key on the async path; the synchronous path proceeds as normal and this verification is unaffected.
     */
    // ==================== Terrain matrix (②): the same mining task × multiple geometries ====================
    // Let the "combinatorial explosion of terrain" happen in headless tests rather than a player's save file — the real-play case of "digging into a mountainside for ore but missing the hole"
    // should be covered by the matrix's second row (slope). Every case asserts the same thing: the unified approach primitive (mining-aware pathfinding) can walk/dig to the ore and mine 1 raw_iron.
    private static Result assignMineGeo(AIPlayerEntity bot, String geo) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        switch (geo) {
            // Vertically buried ore: 3 blocks underfoot (the old lab baseline)
            case "vertical" -> world.setBlock(origin.below(3), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
            // Mountainside: a 6-high stone slope is piled up 8 blocks away, with ore embedded in the slope face (a recreation of three real-play failures in a row)
            case "slope" -> {
                for (int dx = 0; dx <= 6; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        for (int dy = 0; dy <= dx; dy++) {
                            world.setBlock(origin.offset(8 + dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                        }
                    }
                }
                world.setBlock(origin.offset(11, 3, 0), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
            }
            // Suspended overhead: ore in the underside of a stone slab 5 blocks overhead (requires pillaring up / stepping up if it can't be reached — tests vertical approach)
            case "overhang" -> {
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        world.setBlock(origin.offset(dx, 5, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
                world.setBlock(origin.offset(0, 5, 0), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
                InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 16)); // footing material
            }
            // Wall between: a 3-thick stone wall between the bot and the ore (must dig through the wall)
            case "wall" -> {
                for (int dx = 3; dx <= 5; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        for (int dy = 0; dy <= 3; dy++) {
                            world.setBlock(origin.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                        }
                    }
                }
                world.setBlock(origin.offset(7, 1, 0), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
            }
            // Fully enclosed pocket: ore fully embedded at the center of a solid stone cube 6 blocks away (a direct test of the endpoint exemption)
            case "pocket" -> {
                for (int dx = 4; dx <= 9; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        for (int dy = -1; dy <= 4; dy++) {
                            world.setBlock(origin.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                        }
                    }
                }
                world.setBlock(origin.offset(7, 1, 0), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
            }
            // Deep and diagonally below: ore diagonally 6 blocks down (deep deepslate iron ore, testing diagonal downward digging). Torches given generously: fixed-interval strip-corridor lighting
            // (ore_dig_torch) is incidentally verified in this row — a torch should appear every 10 blocks in the dark corridor.
            case "deep" -> {
                world.setBlock(origin.offset(4, -6, 4), Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
                InventoryAction.giveItem(bot, new ItemStack(Items.TORCH, 16));
            }
            // P0 verification · lava adjacency: one lava-source block adjoins the east face of the fully enclosed ore — the precheck should route A* in from a safe face (north/south/top, etc.);
            // digging the wrong face = lava floods in and burns the bot to death (caught by the zero-death assertion).
            case "lava" -> {
                for (int dx = 4; dx <= 9; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        for (int dy = -1; dy <= 4; dy++) {
                            world.setBlock(origin.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                        }
                    }
                }
                world.setBlock(origin.offset(7, 1, 0), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(origin.offset(8, 1, 0), Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL); // lava adjoining the ore's east face
                InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 8)); // sealing material (a real player always has cobblestone on hand)
            }
            // R9 flowing water adjoining ore (waterfall base): a water source hangs 2 blocks above the cell adjacent to the ore and naturally flows down over the ore's east neighbor — flowing water (not a source)
            // also has fluidState.is(WATER), so the sealing/side-approach path should behave the same way; sealing off the flowed-through cell is enough to dig safely.
            case "flow" -> {
                for (int dx = 4; dx <= 9; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        for (int dy = -1; dy <= 4; dy++) {
                            world.setBlock(origin.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                        }
                    }
                }
                world.setBlock(origin.offset(7, 1, 0), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(origin.offset(8, 1, 0), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(origin.offset(8, 2, 0), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(origin.offset(8, 3, 0), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL); // a high source, flowing down to the ore's neighbor
                InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 8));
            }
            // R9 hidden-lake wall breach: a water tank hides behind the straight z=0/1 wall leading to the ore (digging through floods it), with a dry path left on the negative-z side —
            // the precheck (digEnterable rejecting a fluid-adjacent cell) should make A* automatically detour along the dry path; the lake is meant to be routed around, not sealed off.
            case "lake" -> {
                // (the unified canvas naturally has zero ore and zero lakes, so the artificial-tank wall-breach flood test is no longer hijacked by a natural lake.)
                for (int dx = 3; dx <= 9; dx++) {
                    for (int dz = -4; dz <= 3; dz++) {
                        for (int dy = -1; dy <= 4; dy++) {
                            world.setBlock(origin.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                        }
                    }
                }
                // Water tank: x=5..6, z=2..3 against the wall to the side (the surrounding stone walls are already in place). The test = a hidden lake sits beside the main path;
                // digging past it must not be lured in, and sealing must not trigger spuriously — the z=0 straight line can still reach the ore. The original z=0..1 pressed right against the main path:
                // the "no fluid adjacent" precheck would reject the whole z=-1 column next to the tank too, forcing A* to detour via z=-2, and the 80t approach budget
                // often couldn't finish (observed as a silent skip in round 3b), distorting the test into a "detour race".
                for (int dx = 5; dx <= 6; dx++) {
                    for (int dz = 2; dz <= 3; dz++) {
                        for (int dy = 1; dy <= 2; dy++) {
                            world.setBlock(origin.offset(dx, dy, dz), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
                        }
                    }
                }
                world.setBlock(origin.offset(9, 1, 0), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
                InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 8));
            }
            // Water-adjacent ore: structurally identical to the lava row, with the fluid swapped for a water source — the instant it's dug open, the inrushing water could push the bot away or flood the corridor and drown the drops;
            // the unified hazardous-fluid sealing logic (ore_dig_fluid_seal) should seal the water first and only then dig.
            case "water" -> {
                for (int dx = 4; dx <= 9; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        for (int dy = -1; dy <= 4; dy++) {
                            world.setBlock(origin.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                        }
                    }
                }
                world.setBlock(origin.offset(7, 1, 0), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(origin.offset(8, 1, 0), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL); // water source adjoining the ore's east face
                InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 8));
            }
            // P0 verification · gravel overhead: a 3-block gravel column hangs overhead on the mandatory wall-crossing segment (the z=0 straight line); the precheck should route through a safe z±1 column instead —
            // going straight through = a cave-in crushes and suffocates the bot (caught by the zero-death assertion).
            case "gravel" -> {
                for (int dx = 3; dx <= 5; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        for (int dy = 0; dy <= 3; dy++) {
                            world.setBlock(origin.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                        }
                    }
                }
                for (int dx = 3; dx <= 5; dx++) {
                    for (int dy = 4; dy <= 6; dy++) {
                        world.setBlock(origin.offset(dx, dy, 0), Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
                world.setBlock(origin.offset(7, 1, 0), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
            }
            // P0 verification · full inventory: the work face must not just dropJunk directly; OreDig should pause and wait for the sealed
            // ORE_BATCH capacity service to free up four slots, then retry from the same cursor and pick up the target ore.
            case "fullinv" -> {
                InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 8));
                for (int i = 0; i < 36; i++) {
                    InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 64));
                }
                world.setBlock(origin.below(3), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
            }
            // Sunken ore next to a shaft (deterministic reproduction of real_iron seed777): the ore sits in solid stone 5 down/10 across,
            // with an open shaft left 2 blocks away going all the way down (simulating a leftover shaft from the bot having just dug dig_down). Observed in real play:
            // the bot breaks 14 blocks entirely at the starting Y level, tunneling horizontally without ever sinking to the ore's Y → no_progress. geo_deep (pure solid diagonal-down)
            // still PASSes, and the difference is exactly this open shaft mixed into the terrain. Once the approacher's descent is fixed, this scenario and real_iron should turn green together.
            case "shaft" -> {
                world.setBlock(origin.offset(10, -5, 0), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy >= -8; dy--) {
                    world.setBlock(origin.offset(2, dy, 0), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
            // Sunken ore across a cavity (real_iron seed777, hypothesis #2): a 5x4x5 cave lies between the bot and the sunken ore.
            // Hypothesis: the approacher's A* sees the cavity and WALKs into the cave, lands at the bottom facing the ore from the wrong angle, and stalls tunneling horizontally.
            // If geo_shaft (pure solid) PASSes while this scenario fails → the air gap is the real cause.
            case "cave" -> {
                world.setBlock(origin.offset(10, -5, 0), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dx = 3; dx <= 7; dx++) {
                    for (int dy = -1; dy >= -4; dy--) {
                        for (int dz = -2; dz <= 2; dz++) {
                            world.setBlock(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        }
                    }
                }
            }
            default -> {
                return Result.fail("geo_" + geo, "unknown_geometry");
            }
        }
        final int deathBase = deathCount(bot);
        boolean started = GoalExecutor.INSTANCE.submit(bot,
                new Goal.MineOre(java.util.Set.of(Blocks.IRON_ORE), 1));
        if (!started) {
            return Result.fail("geo_" + geo, "goal_submit_failed");
        }
        return Result.runningGoal("geo_" + geo, 3600,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.RAW_IRON) >= 1
                        && deathCount(bot) == deathBase);
    }

    // P1 rich-ore-area guidance: no ore nearby (within 64 blocks), but the knowledge base remembers a rich-ore cluster 80 blocks away (3 resource points pre-warmed) —
    // prospect's fallback should head straight for the rich area instead of digging blindly. Assertion: obtain the real ore buried 80 blocks away.
    private static Result assignGeoRich(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        // (the high-altitude premise is already satisfied by the unified canvas: prospect's 64-block sphere naturally has zero ore, so the rich-area-guidance test is no longer hijacked.)
        // Rich area: 80 blocks away, 3 remembered resource points (clustered within 20 blocks) + one real ore block; a stone corridor is laid along the way to guarantee passage (the barren band, no ore within 64 blocks)
        BlockPos rich = origin.offset(80, 0, 0);
        for (int dx = 0; dx <= 82; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.setBlock(origin.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlock(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        world.setBlock(rich, Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        // The platform is fully laid before the bot is placed (lay first, teleport after, to prevent a falling window)
        bot.teleportTo(world, origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D,
                java.util.Collections.emptySet(), bot.getYRot(), bot.getXRot(), true);
        bot.fallDistance = 0.0F;
        io.github.zoyluo.minecraftai.memory.EpisodeLog log = io.github.zoyluo.minecraftai.memory.EpisodeLog.INSTANCE;
        log.record(bot, io.github.zoyluo.minecraftai.memory.EpisodeLog.Type.RESOURCE_FOUND, rich.offset(0, 0, 10), "minecraft:iron_ore");
        log.record(bot, io.github.zoyluo.minecraftai.memory.EpisodeLog.Type.RESOURCE_FOUND, rich.offset(10, 0, 0), "minecraft:iron_ore");
        log.record(bot, io.github.zoyluo.minecraftai.memory.EpisodeLog.Type.RESOURCE_FOUND, rich.offset(0, 0, -10), "minecraft:iron_ore");
        final int deathBase = deathCount(bot);
        boolean started = GoalExecutor.INSTANCE.submit(bot,
                new Goal.MineOre(java.util.Set.of(Blocks.IRON_ORE), 1));
        if (!started) {
            return Result.fail("geo_rich", "goal_submit_failed");
        }
        return Result.runningGoal("geo_rich", 4800,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.RAW_IRON) >= 1
                        && deathCount(bot) == deathBase);
    }

    // Ore along the way (R3): the target iron ore is 6 blocks away, and 2 blocks of coal ore are embedded in the tunnel wall on the way there (within ±1 reach) — assert the iron is still mined,
    // and the coal is picked up for free along the way (ore_dig_bonus); changing course to chase the vein or detour counts as a loss (guarded by the budget and reach constraints).
    private static Result assignGeoBonus(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin);
        fillStoneCube(world, origin, 6, 8);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        // Target iron ore: 6 blocks east, same level
        world.setBlock(origin.offset(6, 1, 0), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        // Coal along the way: on both tunnel walls (enters the ±2 scan window as the tunnel is dug through)
        world.setBlock(origin.offset(2, 1, 1), Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(origin.offset(4, 0, -1), Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
        final int deathBase = deathCount(bot);
        boolean started = GoalExecutor.INSTANCE.submit(bot,
                new Goal.MineOre(java.util.Set.of(Blocks.IRON_ORE), 1));
        if (!started) {
            return Result.fail("geo_bonus", "goal_submit_failed");
        }
        return Result.runningGoal("geo_bonus", 3600,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.RAW_IRON) >= 1
                        && InventoryAction.countItem(bot, Items.COAL) >= 1
                        && deathCount(bot) == deathBase);
    }

    // Mine-then-stock (R4): a chest is placed next to the base, goal = mine 1 iron and put it in storage. Two chained goals (MineOre→queue Stockpile),
    // assert the chest holds RAW_IRON≥1 (not the inventory — holding it in hand doesn't count as stocked).
    private static Result assignGeoStockpile(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin);
        fillStoneCube(world, origin, 4, 8);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        world.setBlock(origin.offset(5, 1, 0), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        // Base: a marker at the bot's feet + a chest
        io.github.zoyluo.minecraftai.memory.BotMemoryStore.INSTANCE.of(bot.getUUID())
                .markPlace("base", world, origin);
        BlockPos chest = origin.offset(-2, 0, 0);
        world.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        boolean started = GoalExecutor.INSTANCE.submit(bot,
                new Goal.MineOre(java.util.Set.of(Blocks.IRON_ORE), 1));
        if (!started) {
            return Result.fail("geo_stockpile", "goal_submit_failed");
        }
        GoalExecutor.INSTANCE.submit(bot, new Goal.Stockpile(Items.RAW_IRON, 1));
        return Result.running("geo_stockpile", 4800, ignored -> {
            if (!bot.isAlive()) {
                return false;
            }
            var inv = io.github.zoyluo.minecraftai.action.ContainerAction.resolve(bot, chest).orElse(null);
            if (inv == null) {
                return false;
            }
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (inv.getItem(i).is(Items.RAW_IRON)) {
                    return true;
                }
            }
            return false;
        });
    }

    // Resume mining (R6/R7): a mine_face landmark + ore-type memory pre-placed 35 blocks away (the ore is buried right beside the work face), recreating resume_mining
    // in body (MoveTask back to the face + MineOre queued), assert the bot walks back and mines the ore — verifies landmark memory and task/goal hand-off semantics.
    private static Result assignGeoResume(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        // Work face: 35 blocks east, corridor kept clear, 2 iron ore embedded beside the face
        BlockPos face = origin.offset(35, 0, 0);
        for (int dx = 0; dx <= 37; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.setBlock(origin.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlock(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        // Canvas rule: ore must be at least y+1 — placing it at ground level (y+0) makes the approach goal (ore.down) land in the void below the single stone-slab layer,
        // so the support check rejects it every time → a cascade of TIMEOUT skips (observed in round 4b). A single stone block is placed as the ore's base.
        world.setBlock(face.offset(1, 0, 1), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(face.offset(1, 0, -1), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(face.offset(1, 1, 1), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(face.offset(1, 1, -1), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        var mem = io.github.zoyluo.minecraftai.memory.BotMemoryStore.INSTANCE.of(bot.getUUID());
        mem.markPlace("mine_face", world, face);
        mem.remember("mine_face_ores", "minecraft:iron_ore");
        // Recreates resume_mining in body
        TaskManager.INSTANCE.assign(bot, new io.github.zoyluo.minecraftai.task.MoveTask(bot, face),
                io.github.zoyluo.minecraftai.runtime.TaskOrigin.of(io.github.zoyluo.minecraftai.runtime.TaskOrigin.Kind.VERIFY, "geo_resume"));
        GoalExecutor.INSTANCE.submit(bot,
                new Goal.MineOre(java.util.Set.of(Blocks.IRON_ORE), 2));
        final int deathBase = deathCount(bot);
        return Result.running("geo_resume", 4800,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.RAW_IRON) >= 2
                        && deathCount(bot) == deathBase);
    }

    // Unified survival layer (phase 2, V1): standing planted underwater (HoldTask has no private circuit breaker of its own — the strictest test of the fallback safety semantics),
    // as air runs down toward the threshold SurvivalGuard should cut the task, NavSafetyNet surfaces the bot, and the bot survives. Assertion: the task
    // terminates with guard_drowning + the bot survives — "no task may ever do worse than the unified layer".
    private static Result assignGeoGuard(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition(); // the unified canvas is already high in the air, with zero interference from natural water bodies
        // Stone-walled water well: 1x1 interior, 4 deep, the bot sinks to the bottom with 3 blocks of water overhead (only truly drowning if it can't surface)
        for (int dy = -1; dy <= 4; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    world.setBlock(origin.offset(dx, dy, dz),
                            (dx == 0 && dz == 0 && dy >= 0 && dy <= 3)
                                    ? Blocks.WATER.defaultBlockState()
                                    : Blocks.STONE.defaultBlockState(),
                            Block.UPDATE_ALL);
                }
            }
        }
        bot.teleportTo(world, origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D,
                java.util.Collections.emptySet(), bot.getYRot(), bot.getXRot(), true);
        bot.fallDistance = 0.0F;
        bot.setAirSupply(120); // compresses the wait: dropping from 120, ~1 second to the threshold (a full 300 would mean wasting 10 seconds waiting)
        TaskManager.INSTANCE.assign(bot, new io.github.zoyluo.minecraftai.task.HoldTask(),
                io.github.zoyluo.minecraftai.runtime.TaskOrigin.of(io.github.zoyluo.minecraftai.runtime.TaskOrigin.Kind.VERIFY, "hold"));
        // Inverted scenario: the guard cutting the task = a clean FAILED counts as a PASS (the detail carries the failure reason so it can be checked as guard_drowning);
        // the task blindly running to a timeout (= the unified layer failed to catch it) is the actual FAIL.
        return Result.runningExpectCleanFail("geo_guard", 1200);
    }

    // Resource exploration (EXPLORE): zero trees nearby (within survey's 48 blocks + prospect's 96 blocks); the only tree cluster is at the end of a corridor 120 blocks away —
    // roam's 28-block back-and-forth range can't reach it, so it must be found by EXPLORE striding out in a directed way. The canvas is naturally tree-free
    // (prepareArea's unified canvas), so no clearing is needed. Assertion: the bot survives and the log-family count (birch/oak) is ≥4.
    private static Result assignExploreWood(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin);
        // A stone-slab corridor runs 120 blocks out (dy-1 laid with stone, dy0..2 cleared, structurally identical to geo_rich's corridor): guarantees the tree cluster is physically reachable.
        for (int dx = 0; dx <= 124; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.setBlock(origin.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlock(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        // Tree cluster: 3 birch pillars 2 blocks tall (6 logs total, leaving margin over the 4-log target; the corridor is already cleared so the pillars behind it aren't buried).
        for (BlockPos base : new BlockPos[]{origin.offset(120, 0, 0), origin.offset(121, 0, 1), origin.offset(121, 0, -1)}) {
            world.setBlock(base, Blocks.BIRCH_LOG.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(base.above(), Blocks.BIRCH_LOG.defaultBlockState(), Block.UPDATE_ALL);
        }
        // HaveItem(OAK_LOG) is lenient about the log family: GatherQuotaTask.acceptItemsFor returns the whole family for the LOGS tag
        // (any wood type counts), so birch logs still advance progress — meaning the goal can complete even though BIRCH is gathered while the target says OAK.
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.OAK_LOG, 4));
        if (!started) {
            return Result.fail("explore_wood", "goal_submit_failed");
        }
        return Result.runningGoal("explore_wood", 6000,
                ignored -> bot.isAlive()
                        && InventoryAction.countItem(bot, Items.BIRCH_LOG) + InventoryAction.countItem(bot, Items.OAK_LOG) >= 4);
    }

    // Death recovery (R1): the bot is killed in one hit while carrying easily-identifiable supplies; assert the respawn reflex auto-runs to the corpse and retrieves the iron ingot back into the inventory before it despawns.
    // A blind-spot sanity check: the drop is genuinely spawned (this mod's respawn does not restore the inventory; vanilla dropEquipment drops it at the death point).
    private static Result assignGeoRecover(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_INGOT, 5));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        // One-hit kill: goes through the vanilla death flow (drops are spawned); setHealth(0) doesn't trigger die drops, damage must be applied instead.
        bot.hurtServer(world, world.damageSources().generic(), 1000.0F);
        return Result.running("geo_recover", 2400,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.IRON_INGOT) >= 5);
    }

    // Runtime recipe index end to end: OAK_TRAPDOOR is not in the hand-written table (confirmed by grep), so it can only be crafted by backward-planning
    // through a recipe (6 planks) learned by RuntimeRecipeIndex from RecipeAccess — mod items go through the same path, and a vanilla item outside the table is used here as a stand-in proof.
    private static Result assignCraftRuntime(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_PLANKS, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE, 1));
        final int deathBase = deathCount(bot);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.OAK_TRAPDOOR, 1));
        if (!started) {
            return Result.fail("craft_runtime", "goal_submit_failed(the runtime index never taught the planner about the trapdoor)");
        }
        return Result.runningGoal("craft_runtime", 2400,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.OAK_TRAPDOOR) >= 1
                        && deathCount(bot) == deathBase);
    }

    // Memory/knowledge subsystem API smoke test (synchronous, judged once): feeding in 2 deaths at the same spot → distills out a danger zone (a single one doesn't flag it);
    // 2 resource discoveries at the same spot → deduplicates to just one record; the on-disk file exists. Pure API behavior, no task is run.
    private static Result assignKnowledgeSmoke(AIPlayerEntity bot) {
        io.github.zoyluo.minecraftai.memory.KnowledgeBase kb = io.github.zoyluo.minecraftai.memory.KnowledgeBase.INSTANCE;
        io.github.zoyluo.minecraftai.memory.EpisodeLog log = io.github.zoyluo.minecraftai.memory.EpisodeLog.INSTANCE;
        BlockPos spot = bot.blockPosition().offset(1000, 0, 1000); // far from the actual activity area, so it doesn't contaminate later scenarios
        int dangersBefore = kb.dangerCount(bot.getUUID());
        log.record(bot, io.github.zoyluo.minecraftai.memory.EpisodeLog.Type.DEATH, spot, "smoke_test");
        if (kb.dangerCount(bot.getUUID()) != dangersBefore) {
            return Result.fail("knowledge_smoke", "single_death_created_zone(should require two before flagging)");
        }
        log.record(bot, io.github.zoyluo.minecraftai.memory.EpisodeLog.Type.DEATH, spot.offset(3, 0, 3), "smoke_test");
        if (!kb.isDanger(bot.getUUID(), spot)) {
            return Result.fail("knowledge_smoke", "two_deaths_no_zone(cluster distillation did not take effect)");
        }
        int resBefore = kb.resourceCount(bot.getUUID());
        log.record(bot, io.github.zoyluo.minecraftai.memory.EpisodeLog.Type.RESOURCE_FOUND, spot.offset(50, 0, 0), "minecraft:iron_ore");
        log.record(bot, io.github.zoyluo.minecraftai.memory.EpisodeLog.Type.RESOURCE_FOUND, spot.offset(52, 0, 2), "minecraft:iron_ore");
        if (kb.resourceCount(bot.getUUID()) != resBefore + 1) {
            return Result.fail("knowledge_smoke", "resource_dedup_failed(the same ore within 8 blocks should dedupe)");
        }
        if (kb.nearestResource(bot.getUUID(), "minecraft:iron_ore", spot.offset(40, 0, 0), 96).isEmpty()) {
            return Result.fail("knowledge_smoke", "nearest_resource_miss");
        }
        return Result.pass("knowledge_smoke", "distill+dedup+query ok, dangers=" + kb.dangerCount(bot.getUUID())
                + " resources=" + kb.resourceCount(bot.getUUID()));
    }

    // L1 wiring regression (no key burned): feeds tool calls directly to each high-level tool handler, asserting "the right tool + the right parameters → maps to the right Goal and submits successfully
    // (goal_assigned)". Only tests wiring/parameters/mapping, not actual execution (each submission is cleared right after). Locks this chain down as a deterministic regression to prevent future breakage.
    // Does not replace llm_* (that verifies whether the real LLM picks correctly, burning a key); this test verifies [once the right choice is made, is the wiring correct].
    private static Result assignToolDispatch(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        GoalExecutor.INSTANCE.clear(bot);
        TaskManager.INSTANCE.abort(bot);
        io.github.zoyluo.minecraftai.brain.ToolRegistry reg = new io.github.zoyluo.minecraftai.brain.ToolRegistry();
        record Case(String tool, String args) {
        }
        java.util.List<Case> cases = java.util.List.of(
                new Case("mine_ore", "{\"ore\":\"minecraft:iron_ore\",\"count\":3}"),
                new Case("achieve_goal", "{\"item\":\"minecraft:iron_pickaxe\"}"),
                new Case("harvest_crop", "{\"crop\":\"wheat\"}"),
                new Case("provision_food", "{\"count\":4}"),
                new Case("forage", "{}"),
                new Case("achieve_armor", "{}"),
                new Case("achieve_workstation", "{}"),
                new Case("stockpile", "{\"item\":\"minecraft:cobblestone\",\"count\":10}"),
                new Case("build_house", "{\"width\":7,\"material\":\"stone_like\"}"),
                new Case("build_house", "{}"));
        StringBuilder fails = new StringBuilder();
        int ok = 0;
        for (Case c : cases) {
            io.github.zoyluo.minecraftai.brain.ToolDefinition def = reg.get(c.tool()).orElse(null);
            if (def == null) {
                fails.append(c.tool()).append(":unregistered; ");
                continue;
            }
            io.github.zoyluo.minecraftai.brain.ToolDefinition.ToolResult r;
            try {
                com.google.gson.JsonObject args = com.google.gson.JsonParser.parseString(c.args()).getAsJsonObject();
                r = def.handler().invoke(bot, args);
            } catch (RuntimeException e) {
                fails.append(c.tool()).append(":threw(").append(e.getClass().getSimpleName()).append("); ");
                GoalExecutor.INSTANCE.clear(bot);
                TaskManager.INSTANCE.abort(bot);
                continue;
            }
            if (r != null && r.ok() && r.message() != null && r.message().contains("goal_assigned")) {
                ok++;
            } else {
                fails.append(c.tool()).append("=").append(r == null ? "null" : r.message()).append("; ");
            }
            GoalExecutor.INSTANCE.clear(bot); // wiring test only: clear the just-submitted goal without actually executing it
            TaskManager.INSTANCE.abort(bot);
        }
        return fails.length() == 0
                ? Result.pass("tool_dispatch", ok + "/" + cases.size() + " high-level tool→Goal wiring/parameter mapping all passed")
                : Result.fail("tool_dispatch", ok + "/" + cases.size() + " ok; FAIL: " + fails.toString().trim());
    }

    private static Result assignMsgKeepGoal(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        final int deathBase = deathCount(bot);
        if (!GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.COBBLESTONE, 6))) {
            return Result.fail("msg_keep_goal", "goal_submit_failed");
        }
        BrainCoordinator.INSTANCE.handleMessage(bot, "Tester", "What are you up to?");
        if (!GoalExecutor.INSTANCE.hasActivePlan(bot)) {
            return Result.fail("msg_keep_goal", "goal_cleared_by_message"); // P2 regression: a player message cleared the in-progress goal
        }
        return Result.runningGoal("msg_keep_goal", 2400,
                ignored -> bot.isAlive()
                        && InventoryAction.countItem(bot, Items.COBBLESTONE) >= 6
                        && deathCount(bot) == deathBase);
    }

    // Counts the number of cells within center's horizontal ±r, vertical [center.y, center.y+8] range that belong to any block in targets (used for the building assertion).
    // Deliberately counts only center.y and above (the "above" baseline): prepareArea's lab-platform floor/foundation (y-1 cobblestone, 16 layers of solid
    // stone below) shares the same family as stone_like building material, and counting it would misjudge "no house built" as meeting the bar; the house's floor layer lands exactly at the anchor foothold y
    // (=origin.y, since SiteFinder's site selection picks a standable cell), so the "above" baseline costs the building itself nothing.
    private static int countNearbyBlocksAbove(ServerLevel world, BlockPos center, int r, java.util.Set<Block> targets) {
        int count = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = 0; dy <= 8; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (targets.contains(world.getBlockState(center.offset(dx, dy, dz)).getBlock())) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    // Symmetric vertical scan (center's yLo..yHi above and below): dedicated to real_build — the blueprint's SiteFinder auto-selects a site, and the house may land
    // far from / below the spawn point (observed built at 265,63,587 while spawn was elsewhere); an origin-anchored "only above" scan can't find the finished house → a house finished
    // 116/116 with zero deaths gets misjudged as assertion_failed. Under real terrain, the ground below is dirt/stone, not in the same family as planks, so scanning symmetrically around the "actual build spot"
    // (where the bot ends up once the build is done) counts only genuine planks with no fake-platform problem (cannot be used for the lab build scenario: there, the platform floor shares a family with the building material, see
    // the countNearbyBlocksAbove comment). Still requires ≥80 genuine planks + zero deaths + survival, just with the count anchor aimed at the actual build spot.
    private static int countNearbyBlocks(ServerLevel world, BlockPos center, int r, int yLo, int yHi,
                                         java.util.Set<Block> targets) {
        int count = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = yLo; dy <= yHi; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (targets.contains(world.getBlockState(center.offset(dx, dy, dz)).getBlock())) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    // Counts the number of water-source blocks among the four cells of the 2×2 area at center.
    private static int countWaterSources(ServerLevel world, BlockPos center) {
        BlockPos[] cells = {center, center.east(), center.south(), center.east().south()};
        int n = 0;
        for (BlockPos p : cells) {
            if (io.github.zoyluo.minecraftai.action.FarmAction.isWaterSource(world, p)) {
                n++;
            }
        }
        return n;
    }

    // Phase1: gear goal. Given ample iron ingots + wood (focusing on "craft armor and equip it", skipping the time cost of mining 24 iron), achieve Goal.Armor should craft 4 armor pieces + a sword and equip them automatically.
    private static Result assignAchieveArmor(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_INGOT, 30));
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        for (int dy = 0; dy < 6; dy++) {
            world.setBlock(origin.relative(Direction.WEST, 2).above(dy), Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        }
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Armor());
        if (!started) {
            return Result.fail("achieve_armor", "goal_submit_failed");
        }
        // The assertion matches the typed Armor predicate's baseline: none of the five — head/chest/legs/feet/sword — may be missing.
        return Result.runningGoal("achieve_armor", 16000,
                ignored -> bot.isAlive()
                        && hasGear(bot, Items.IRON_HELMET)
                        && hasGear(bot, Items.IRON_CHESTPLATE)
                        && hasGear(bot, Items.IRON_LEGGINGS)
                        && hasGear(bot, Items.IRON_BOOTS)
                        && hasGear(bot, Items.IRON_SWORD));
    }

    // Phase2: infrastructure goal. Given ample planks + cobblestone (focusing on "craft the three-piece set + place them"), achieve Goal.Workstation should place a crafting table/furnace/chest nearby.
    private static Result assignAchieveWorkstation(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_PLANKS, 20));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 8));
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Workstation());
        if (!started) {
            return Result.fail("achieve_workstation", "goal_submit_failed");
        }
        return Result.runningGoal("achieve_workstation", 8000,
                ignored -> bot.isAlive()
                        && hasBlockNearby(bot, Blocks.CRAFTING_TABLE)
                        && hasBlockNearby(bot, Blocks.FURNACE)
                        && hasBlockNearby(bot, Blocks.CHEST));
    }

    private static boolean hasBlockNearby(AIPlayerEntity bot, Block block) {
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-5, -3, -5), origin.offset(5, 3, 5))) {
            if (world.getBlockState(p).is(block)) {
                return true;
            }
        }
        return false;
    }

    // Phase3: stockpiling goal. Given a stone pickaxe + a stone pillar + a target chest, passes only once the chest actually receives 6 cobblestone.
    private static Result assignStockpile(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        BlockPos chestPos = origin.relative(Direction.EAST, 2);
        world.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        io.github.zoyluo.minecraftai.memory.BotMemoryStore.INSTANCE.of(bot.getUUID())
                .markPlace("base", world, origin);
        for (int dy = 1; dy <= 12; dy++) {
            world.setBlock(origin.below(dy), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Stockpile(Items.COBBLESTONE, 6));
        if (!started) {
            return Result.fail("stockpile", "goal_submit_failed");
        }
        return Result.runningGoal("stockpile", 12000,
                ignored -> bot.isAlive() && io.github.zoyluo.minecraftai.action.ContainerAction.resolve(bot, chestPos)
                        .map(inventory -> {
                            int count = 0;
                            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                                if (inventory.getItem(slot).is(Items.COBBLESTONE)) {
                                    count += inventory.getItem(slot).getCount();
                                }
                            }
                            return count >= 6;
                        })
                        .orElse(false));
    }

    // Deep-mining refactor P1: DescendToYTask should dig a continuous vertical shaft down to the target Y (this is the direct countermeasure for the Y=48 stall — reach the ore layer first).
    private static Result assignDescendToOre(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 1));
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        int targetY = origin.getY() - 20;
        for (int dy = 1; dy <= 25; dy++) {
            world.setBlock(origin.below(dy), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        Task task = new DescendToYTask(targetY);
        return assignTask(bot, "descend_to_ore", task,
                MiningMissionBudget.descendTaskWindowTicks(origin.getY(), targetY),
                ignored -> bot.isAlive() && bot.blockPosition().getY() <= targetY);
    }

    // Digging-based movement: the bot is enclosed by a horizontal stone wall (headroom left open so it doesn't suffocate), with the target outside the wall. Pure pathfinding can't get through → MoveTask should fall back to digging through the wall to reach it.
    private static Result assignMoveDigThrough(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 1));
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int dy = 0; dy <= 1; dy++) {
                world.setBlock(origin.relative(direction).above(dy), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        BlockPos goal = origin.relative(Direction.EAST, 4);
        Task task = new MoveTask(bot, goal);
        return assignTask(bot, "move_dig_through", task, 4000,
                ignored -> bot.isAlive() && bot.blockPosition().distSqr(goal) <= 9.0D);
    }

    /**
     * REGRESSION(P2): achieve_goal diamond — given an iron pickaxe (isolating the tool chain), diamond ore buried in the stone layer underfoot, assert diamond is mined.
     * Tests the new mapping "gold/redstone/diamond/emerald need an iron pickaxe" + OreDig mining higher-tier ore.
     */
    // Nighttime mob-sea survival (front A, a deterministic reproduction of real_diamond's death spiral): night + low health (8) + 3-zombie siege + given cobblestone with no weapon
    // (forcing shelter, not combat). Assertion: the bot seals itself inside a wall (all four sides of its head are non-empty) and survives — only met once the life-saving wall-off succeeds;
    // if it's killed partway through, deathCount changes and the assertion can never be satisfied → timeout FAIL. Verifies whether "ignore the cooldown and wall off immediately when near death" genuinely saves the bot's life.
    private static Result assignGeoNightSwarm(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin);
        world.setDayTime(13000L); // night: spawned zombies won't burn in sunlight, so the siege is sustained
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 32)); // wall-building material (no weapon forces shelter)
        bot.setHealth(8.0F); // ≤ EMERGENCY_SHELTER_HP, triggers the life-saving wall-off
        for (int i = 0; i < 3; i++) {
            net.minecraft.world.entity.monster.zombie.Zombie z = EntityType.ZOMBIE.create(world, EntitySpawnReason.COMMAND);
            if (z != null) {
                z.setPersistenceRequired();
                double ang = i * 2.094D;
                z.snapTo(bot.getX() + 2.0D * Math.cos(ang), bot.getY(),
                        bot.getZ() + 2.0D * Math.sin(ang), 0.0F, 0.0F);
                world.addFreshEntity(z);
            }
        }
        TaskManager.INSTANCE.assign(bot, new io.github.zoyluo.minecraftai.task.HoldTask(),
                io.github.zoyluo.minecraftai.runtime.TaskOrigin.of(io.github.zoyluo.minecraftai.runtime.TaskOrigin.Kind.VERIFY, "hold"));
        final int deathBase = deathCount(bot);
        return Result.running("geo_night_swarm", 600, ignored -> {
            if (!bot.isAlive() || deathCount(bot) != deathBase) {
                return false; // killed = failed to save itself
            }
            BlockPos h = bot.blockPosition().above();
            int walls = 0;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (!world.getBlockState(h.relative(d)).isAir()) {
                    walls++;
                }
            }
            return walls >= 4; // all four sides around the head sealed = the life-saving wall-off succeeded
        });
    }

    // Cliffside wood-gathering (the #1 obstacle behind diamond's 67% failure rate, deterministic reproduction):    // Cliffside wood-gathering (the #1 obstacle behind diamond's 67% failure rate, deterministic reproduction): the bot is on the canvas platform, with a tree growing at the bottom of a **steep pit** to the east
    // (separated from the platform by a 6-block vertical drop, so pure walking hits GOAL_UNREACHABLE). Assert the bot upgrades to digging-based approach, descends far enough,
    // gathers a full 3 logs, with zero deaths. This is the first gate of "wood can be gathered on any terrain" → "diamond can be mined on any terrain".
    private static Result assignGeoCliffTree(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin);
        // Dig a steep pit to the east at dx 4..10: from the rim at y0, clear 6 blocks straight down into a vertical wall, with a stone floor at the bottom, y-7.
        for (int dx = 4; dx <= 10; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = 0; dy >= -6; dy--) {
                    world.setBlock(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
                world.setBlock(origin.offset(dx, -7, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        // Plant 2 oak trees 4 blocks tall (8 log segments total) at the pit bottom; the bot must descend to the bottom to reach them.
        for (int dy = -6; dy <= -3; dy++) {
            world.setBlock(origin.offset(7, dy, -1), Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(origin.offset(8, dy, 1), Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1)); // give a pickaxe (digging-based approach needs to break stone)
        final int target = 3;
        final int deathBase = deathCount(bot);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.OAK_LOG, target));
        if (!started) {
            return Result.fail("geo_cliff_tree", "goal_submit_failed");
        }
        return Result.runningGoal("geo_cliff_tree", 6000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.OAK_LOG) >= target
                        && deathCount(bot) == deathBase);
    }

    // Make obsidian (new capability L1): a sunken lava source pool to the east of the canvas + a diamond pickaxe + 4 water buckets, **with no obsidian pre-placed** (unlike
    // achieve_obsidian's cheat of pre-placing it). Assert the bot autonomously "pours water on lava to make it on the spot" + mines ≥4 blocks, zero deaths. Once this passes, dial it up to 15 for a stress test.
    private static Result assignGeoObsidianMake(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin);
        // The lava sits one block below ground level with a stone rim kept around it, close to a natural cave floor/surface lava pool. The old canvas placed the source
        // at ground-level footing, where vanilla lava would spread endlessly and swallow the safe standing spot, testing an artificial hazard rather than the pour-water-and-mine capability.
        for (int dx = 4; dx <= 5; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                world.setBlock(origin.offset(dx, -1, dz), Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        // Starts working from the stone rim one block up: strict survival can only use information within line of sight, and isn't allowed to scan underground fluids through the floor.
        world.setBlock(origin.offset(3, 0, 0), Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
        bot.teleportTo(world, origin.getX() + 3.5D, origin.getY() + 1.0D, origin.getZ() + 0.5D,
                java.util.Collections.emptySet(), bot.getYRot(), bot.getXRot(), true);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_PICKAXE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.WATER_BUCKET, 4));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 16));
        final int target = 3;
        final int deathBase = deathCount(bot);
        // Isolates and verifies the new capability itself: dispatches CreateObsidianTask directly (bypassing the planner's bucket-provisioning chain — which would trigger
        // wood→iron collection on the canvas and lead the bot to step right into the lava pool; the planner's wiring is verified separately by a Goal-level scenario).
        return assignTask(bot, "geo_obsidian_make",
                new io.github.zoyluo.minecraftai.task.CreateObsidianTask(target), 9600,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.OBSIDIAN) >= target
                        && deathCount(bot) == deathBase);
    }

    // Deep-approach jittering · deterministic reproduction (replays the exact failure geometry captured from seed777, restored from an ore_dig_region snapshot):
    // the bot is in a self-dug air tunnel (with a cavity around B), the diamond vein is embedded 5-6 blocks into solid stone along +X/+Z, with the target T at the far end —
    // "an open tunnel to the side + solid ore ahead" induces A* to jitter between the tunnel and the dig face (breaking blocks without ever closing the distance → no_progress).
    // 7 layers of ASCII laid out by X segment (|) and Z character; # solid, . air, O ore, T target. The bot stands at B, given an iron pickaxe + deep-mining kit.
    private static final String[] REPLAY_ROWS_Y = { // index 0 = y+4 .. 6 = y-2 (relative to the bot)
        "#############|#############|#############|#############|#############|#############|#############|#############|#############|#############|#############|#############", // +4
        "#############|#############|#############|#############|#############|#############|#############|#############|#############|#############|#############|#############", // +3
        "#############|#############|#############|#############|####.########|###...#######|###OO########|#############|#############|#############|#############|#############", // +2
        "#.###########|#...#########|###.#########|###.#########|####..#######|##....#######|##OOO########|##OO#########|#########T###|#############|#############|#############", // +1
        "#.###########|#...#########|###.#########|###B#########|#############|###..########|##OOO########|##O##########|#############|#############|#############|#############", // 0 (bot)
        "#############|#############|#############|#############|#############|#############|#############|#############|#############|#############|#############|#############", // -1
        "#############|#############|#############|#############|#############|#############|#############|#############|#############|#############|#############|#############", // -2
    };

    private static Result assignGeoReplayOre(AIPlayerEntity bot) {
        clearInventory(bot);
        BlockPos origin = prepareDeepArea(bot, -59); // deep environment (the Y-59 band), the bot lands at origin
        ServerLevel world = bot.level();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 1));
        giveDeepMineKit(bot);
        giveDeepMineSupplies(bot);
        // Restores the grid relative to the bot (B). dxMin=-3 (seg0 is X-3), dz starting at -3 (char0); dy: row 0 = +4 .. row 6 = -2.
        for (int yi = 0; yi < REPLAY_ROWS_Y.length; yi++) {
            int dy = 4 - yi;
            String[] segs = REPLAY_ROWS_Y[yi].split("\\|");
            for (int xi = 0; xi < segs.length; xi++) {
                int dx = xi - 3; // seg3 = the bot's X
                String seg = segs[xi];
                for (int zi = 0; zi < seg.length(); zi++) {
                    int dz = zi - 3; // char3 = the bot's Z
                    char c = seg.charAt(zi);
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (c == '#') {
                        world.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    } else if (c == '.' || c == 'B') {
                        world.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    } else if (c == 'O' || c == 'T') {
                        world.setBlock(pos, Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        final int deathBase = deathCount(bot);
        boolean started = GoalExecutor.INSTANCE.submit(bot,
                new Goal.MineOre(java.util.Set.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE), 1));
        if (!started) {
            return Result.fail("geo_replay_ore", "goal_submit_failed");
        }
        return Result.runningGoal("geo_replay_ore", 4800,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.DIAMOND) >= 1
                        && deathCount(bot) == deathBase);
    }

    // Diamond ≥3 · deep lava (real-play application L1): the diamond band (Y-59) is already dense with lava.    // Diamond ≥3 · deep lava (real-play application L1): the diamond band (Y-59) is already dense with lava. 3 diamond ore blocks each adjoin a lava source,
    // forcing out "deep-lava survival + chaining multiple targets" — the prime suspect behind diamond's real-world failures. Given an iron pickaxe + deep-mining kit + supplies (the same
    // standard as achieve_diamond: no diamond given, the pickaxe is iron, and it genuinely has to mine). Assert ≥3 diamonds and zero deaths (dying once at depth = a real incident).
    private static Result assignGeoDiamondLava(AIPlayerEntity bot) {
        clearInventory(bot);
        BlockPos origin = prepareDeepArea(bot, -59);
        ServerLevel world = bot.level();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 1));
        giveDeepMineKit(bot);
        giveDeepMineSupplies(bot);
        // 3 diamond ore blocks scattered (spaced apart, forcing a genuine "finish one, move to the next"), each with a lava source on its east side
        int[][] spots = {{3, -1, 0}, {-3, -1, 2}, {0, -2, -3}};
        for (int[] s : spots) {
            BlockPos ore = origin.offset(s[0], s[1], s[2]);
            world.setBlock(ore, Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(ore.east(), Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
        }
        final int deathBase = deathCount(bot);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.DIAMOND, 3));
        if (!started) {
            return Result.fail("geo_diamond_lava", "goal_submit_failed");
        }
        return Result.runningGoal("geo_diamond_lava", 9600,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.DIAMOND) >= 3
                        && deathCount(bot) == deathBase);
    }

    // Diamond (deep ore, needs an iron pickaxe): teleport to the diamond-ore layer (-59), bury diamond ore underfoot, given an iron pickaxe + deep-mining safety gear + supplies → mine diamond ore to get diamond.
    private static Result assignAchieveDiamond(AIPlayerEntity bot) {
        clearInventory(bot);
        BlockPos origin = prepareDeepArea(bot, -59);
        ServerLevel world = bot.level();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 1));
        giveDeepMineKit(bot);
        giveDeepMineSupplies(bot);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.setBlock(origin.offset(dx, -2, dz), Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.DIAMOND, 1));
        if (!started) {
            return Result.fail("achieve_diamond", "goal_submit_failed");
        }
        return Result.runningGoal("achieve_diamond", 8000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.DIAMOND) >= 1);
    }

    /**
     * REGRESSION(P3): harvest_crop wheat. Give a wooden hoe, lay out a patch of **ripe** wheat nearby (age=7, avoiding the uncertainty of waiting for it to grow),
     * take the GoalExecutor HarvestCrop goal, and assert ≥3 wheat is received. Tests the farming chain: has a hoe → FARM step → harvest count completes.
     */
    private static Result assignFarmWheatFromScratch(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_HOE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.WHEAT_SEEDS, 8));
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin); // clear skeletons/cows: isolates the harvest logic, avoiding a y6 darkness skeleton preempting and aborting the goal (observed aborted)
        net.minecraft.world.level.block.state.BlockState matureWheat =
                Blocks.WHEAT.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_7, 7);
        // Lay a 3×3 patch of ripe wheat (farmland + ripe crop) on the floor layer (y-1) to the bot's north.
        // It must be laid on the floor layer: the original code laid it at origin.y (the bot's body level) → the farmland block blocked body height with wheat overhead at y+1,
        // so the bot couldn't walk over or reach it and only collected 1~2 → timeout (observed done=14 deposit_skipped). The 3×3 patch is entirely within radius 4,
        // far more than the target of 3, for tolerance.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = 1; dz <= 3; dz++) {
                BlockPos farmland = origin.offset(dx, -1, -dz);
                world.setBlock(farmland, Blocks.FARMLAND.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(farmland.above(), matureWheat, Block.UPDATE_ALL);
            }
        }
        boolean started = GoalExecutor.INSTANCE.submit(bot,
                new Goal.HarvestCrop(Blocks.WHEAT, Items.WHEAT_SEEDS, Items.WHEAT, 3));
        if (!started) {
            return Result.fail("farm_wheat_from_scratch", "goal_submit_failed");
        }
        return Result.runningGoal("farm_wheat_from_scratch", 4800,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.WHEAT) >= 3);
    }

    private static Result assignNavDescend(AIPlayerEntity bot) {
        prepareArea(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        BlockPos goal = origin.relative(Direction.NORTH, 3).below(3);
        for (int i = 1; i <= 3; i++) {
            BlockPos step = origin.relative(Direction.NORTH, i).below(i);
            world.setBlock(step, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(step.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(step.below(), Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        return assignTask(bot, "nav_descend", new MoveTask(bot, goal), 600,
                ignored -> bot.blockPosition().distSqr(goal) <= 4.0D);
    }

    /**
     * Pathfinding fault tolerance ①: trapped escape. A 4-high ring of stone walls seals the bot in completely (no horizontal detour exists), with only 32 dirt in hand —
     * MoveTask should either pillar up and over the wall, or dig through it by hand; either escape route is fine, and the assertion checks the bot ends up standing at the target point outside the wall.
     * This is the minimal reproduction of real play's "fell into a pit / boxed in by terrain": pathfinding must treat "placing blocks/breaking blocks" as valid movement, or a pure-plane A* will judge it a dead end and spin.
     */
    private static Result assignNavPillarOut(AIPlayerEntity bot) {
        surfaceTeleport(bot); // must be surfaced first: building a wall in the y6 underground darkness would trigger dark_trap_escape's life-saving teleport and override the escape being tested (observed aborted)
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin); // an unequipped bot enclosed by the wall is a death sentence if the y6 mob sea gets in; clear it to isolate the escape logic being tested
        // First clear the activity space wide: 2 more blocks of headroom are needed above the wall top (y+3) to climb over, and there must also be footing from the wall to the target —
        // the dev world's y6 surroundings are native stone, and without clearing this would test "getting toyed with by terrain" rather than "can it save itself".
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-6, 0, -6), origin.offset(10, 6, 6))) {
            world.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-6, -1, -6), origin.offset(10, -1, 6))) {
            world.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        // A 5×5 ring of stone (4 high) seals the bot in: the inner 3×3 is left as air, and STONE is built along the ring where |dx|==2 or |dz|==2.
        for (int dy = 0; dy <= 3; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
                        world.setBlock(origin.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 32)); // ample pillaring material; no pickaxe given — digging through the wall by hand is also a valid escape
        BlockPos goal = origin.relative(Direction.EAST, 8);
        return assignTask(bot, "nav_pillar_out", new MoveTask(bot, goal), 2400,
                ignored -> bot.isAlive() && bot.blockPosition().distSqr(goal) <= 9.0D);
    }

    /**
     * Pathfinding fault tolerance ②: buried-alive escape. The bot's feet and head positions are directly filled with STONE (simulating a cave-in / being forced into a wall), and the bot is suffocating and losing health.
     * Submit an ordinary MoveTask; NavSafetyNet's suffocation-escape should preempt it, break the body-position blocks first, dig the bot out, and only then walk.
     * The assertion on completion requires the bot to be alive with no collision shape at either the feet or head position — it must genuinely be dug out, not just have its task status glossed over
     * (the task could otherwise be judged complete while the body is still stuck in a block grinding down health).
     */
    private static Result assignNavBuriedEscape(AIPlayerEntity bot) {
        surfaceTeleport(bot); // surface first, to prevent y6 darkness from triggering dark_trap_escape's life-saving teleport and interfering with the escape being tested
        prepareArea(bot);
        clearInventory(bot);
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin); // after escaping the bot is at low health, and a single arrow from the y6 mob sea would derail it; clearing them ensures the escape itself is what's tested
        world.setBlock(origin, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(origin.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        return assignTask(bot, "nav_buried_escape", new MoveTask(bot, origin.north(5)), 1200,
                ignored -> bot.isAlive() && bodyFree(bot));
    }

    // Whether the bot's feet and head positions both have no collision shape (= not stuck in a block). The core check condition for buried-alive escape: only being dug out counts as a genuine escape.
    private static boolean bodyFree(AIPlayerEntity bot) {
        ServerLevel world = bot.level();
        BlockPos feet = bot.blockPosition();
        BlockPos head = feet.above();
        return world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                && world.getBlockState(head).getCollisionShape(world, head).isEmpty();
    }

    /**
     * Pathfinding fault tolerance ③ (inverted scenario): an unreachable target must trigger a "quick admission of defeat". The target is 80 blocks straight up, and the inventory is completely empty (not even a block to pillar with) —
     * physically impossible to reach — MoveTask is expected to **cleanly FAIL** within 2400t (that counts as a PASS); COMPLETED, or still RUNNING at timeout, are both judged FAIL.
     * Spinning without progress is the most insidious failure mode in real play: the bot looks like it's working while actually just spinning in place, wasting the whole run — much harder to spot than an outright error.
     */
    private static Result assignNavUnreachable(AIPlayerEntity bot) {
        surfaceTeleport(bot); // surface first, to prevent the darkness reflex from interfering with the "clean admission of defeat" judgment
        prepareArea(bot);
        clearInventory(bot);
        clearNearbyMobs(bot.level(), bot.blockPosition()); // prevent a mob from killing the bot and producing a "false clean failure" (death-abort ≠ voluntarily admitting defeat)
        // Places the target beyond the world's height limit: resolveEndpoint would otherwise downgrade an "unreachable target" to a nearby standable spot (this is a navigation
        // fault-tolerance feature) — up(80) on open surface terrain would get downgraded to right underfoot, a fake 1-tick completion (observed as should_have_failed).
        // A point beyond the build limit has no standable spot anywhere around it, so the downgrade has no fallback either, which is what forces out the "clean admission of defeat" path.
        ServerLevel unreachableWorld = bot.level();
        int topLimit = unreachableWorld.getMinY() + unreachableWorld.getHeight();
        BlockPos goal = new BlockPos(bot.blockPosition().getX(), topLimit + 10, bot.blockPosition().getZ());
        // Doesn't go through assignTask (it only ever produces ordinary running semantics): assign directly + an inverted Result, whose meaning is "should fail".
        TaskManager.INSTANCE.assign(bot, new MoveTask(bot, goal),
                io.github.zoyluo.minecraftai.runtime.TaskOrigin.of(io.github.zoyluo.minecraftai.runtime.TaskOrigin.Kind.VERIFY, "move"));
        return Result.runningExpectCleanFail("nav_unreachable", 2400);
    }

    private static Result assignTask(AIPlayerEntity bot, String feature, Task task, int timeoutTicks, Predicate<TaskStatus> assertion) {
        TaskManager.INSTANCE.assign(bot, task,
                io.github.zoyluo.minecraftai.runtime.TaskOrigin.of(io.github.zoyluo.minecraftai.runtime.TaskOrigin.Kind.VERIFY, "scenario_task"));
        return Result.running(feature, timeoutTicks, assertion);
    }

    private static void prepareArea(AIPlayerEntity bot) {
        ServerLevel world = bot.level();
        world.setDayTime(1000L); // set to daytime: later in a suite it turns to night, and the night lighting reflex would preempt the scenario task (observed farm_irrigate sporadically aborted)
        // When multiple scenarios run in sequence within a suite, the bot's position carries over from the previous scenario (wandered off hunting, etc.) → a scenario that assumes a "clean spawn point" would be thrown off
        // (observed in food_suite: during farm_wheat the bot had drifted to 9,-2, the pre-placed ripe wheat wasn't surveyed, and was treated as empty ground to plant on → FAIL).
        // Resetting to a fixed origin at the start guarantees determinism; y is taken from the world origin's natural surface — it used to be hardcoded to y=6 (the old test world's spawn point),
        // but after switching to a natural world, y6 is underground in darkness, teleporting every scenario underground: the darkness triggers DangerWatcher's trapped-in-a-death-pit life-saving teleport
        // (dark_trap_escape), aborting the task being tested (observed as the true root cause of nav_pillar_out aborting two rounds in a row).
        bot.getActionPack().stopAll();
        // Scenario spatial isolation: each scenario moves to a fresh plot (stepping 64 blocks along the x axis each rotation). Running 13 scenarios back to back at the same anchor, earlier mining/blasting
        // chews the foundation into a ruined mess, and a later scenario's mining shaft walking out of fillStoneCube's range falls into the wreckage → an ore_dig_no_progress
        // cluster erupts (observed as 6 scenarios FAILing in the mining suite, while the same scenario run alone in material_suite is all green — proof of cross-contamination).
        scenarioSlot++;
        int baseX = (scenarioSlot % 32) * 64;
        // V3 unified high-altitude canvas: scenarios are always built in a fixed column in the void layer at y=232 — anchor search / heightmap / reject-on-wreckage-anchor
        // are all retired. Phase-1 observations: natural ore hijacking the test (geo_rich, three rounds), a natural lake luring the bot to drown (geo_lake), falling through wreckage columns
        // (geo_resume), void columns (the obsidian case) — this whole class of "terrain lottery" false positives was eating ~60% of debugging time —
        // with 16 blocks of artificial stone under the canvas and void all around, natural interference drops to physically zero. Genuine terrain challenges remain the dedicated job of real_suite.
        // y=232: 88 blocks of headroom remain above (232 + 8 cleared + scenario structure), never touching the 320 world height limit.
        BlockPos origin = new BlockPos(baseX, 232, 0);
        // Turned into a lab: a rotating plot's natural terrain (lake/slope/cave/sand) turns a deterministic regression into a lottery — the same scenario passing or failing shuffles every round
        // (observed: the stone-mining family drowning by a lake, a mining scenario alternating between need_planks/no_progress). The scenario area is wholesale replaced with an artificial platform:
        // 16 blocks of solid stone below the floor (mining/digging-down always eats artificial stone, never breaking into a natural aquifer), with 8 blocks cleared above.
        // Idealized scenarios run in "the lab"; genuine terrain challenges are the responsibility of real_suite (SEED, multiple terrains) — the layering of responsibilities is explicit.
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-16, -16, -16), origin.offset(16, -1, 16))) {
            world.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-16, 0, -16), origin.offset(16, 8, 16))) {
            world.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-4, -1, -4), origin.offset(4, -1, 4))) {
            world.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        // Guard rail: the canvas is surrounded by void, and a movement-heavy scenario (hunting/foraging/exploring) could carry the bot off the edge and drop it into the natural layer
        // (observed in food_full: y211→65, with a cascade of no_place while trying to place a furnace mid-fall). A 2-block stone wall rings it in; corridor-type scenarios'
        // own clearing setBlockState calls carve doorways in the wall without interfering with each other.
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-16, 0, -16), origin.offset(16, 1, 16))) {
            if (Math.abs(pos.getX() - origin.getX()) == 16 || Math.abs(pos.getZ() - origin.getZ()) == 16) {
                world.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        // Lay the platform before teleporting: doing it the other way around would give the bot a 1+ tick falling window in the unlaid area (a straight free-fall down the void column).
        bot.teleportTo(world, origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D,
                java.util.Collections.emptySet(), bot.getYRot(), bot.getXRot(), true);
        bot.fallDistance = 0.0F;
        // Memory-layer isolation: episodic stream + semantic knowledge cross-contaminating between scenarios (an earlier mining scenario's resource point could steer geo_rich's rich-area guidance off into a dead zone,
        // or a leftover episode could let the distillation/dedup logic intercept a pre-warmed point). Deterministic tests clear this per scenario; real usage never goes through this path, and knowledge persists as normal.
        io.github.zoyluo.minecraftai.memory.EpisodeLog.INSTANCE.clearFor(bot.getUUID());
        io.github.zoyluo.minecraftai.memory.KnowledgeBase.INSTANCE.resetFor(bot.getUUID());
        bot.getActionPack().stopAll();
    }

    private static void clearInventory(AIPlayerEntity bot) {
        bot.getInventory().clearContent();
        bot.getInventory().setChanged();
    }

    // Clears animals and hostile mobs within 70 blocks of origin. Dual purpose:
    // (1) animals — the food source-selection test needs a "no animals" environment (otherwise Goal.Food misjudges prey as present → goes hunting instead, and the planting chain never gets tested;
    //     also the dev world is contaminated by cows spawned by earlier food scenarios, accumulating more over time);
    // (2) hostiles — the dev test world has skeletons in the y6 darkness, and being attacked during a long farming/mining run would trigger the survival reflex to preempt and abort the goal,
    //     making the deterministic regression test flaky (observed farm_wheat aborted for this reason). Clearing them isolates the logic being tested.
    private static void clearNearbyMobs(ServerLevel world, BlockPos origin) {
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(origin).inflate(70.0D);
        world.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, box, e -> true)
                .forEach(net.minecraft.world.entity.Entity::discard);
        world.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, box, e -> true)
                .forEach(net.minecraft.world.entity.Entity::discard);
    }

    // Fills a solid stone cube below origin (horizontally ±hr, vertically down 1..depth). Gives mining tasks a deterministic solid environment:
    // covers over pits/leftover blocks dug out by the previous scenario in a suite, and also avoids "the mining task's diagonal shaft walking off a single stone pillar into unlaid terrain". Ore is embedded afterward.
    private static void fillStoneCube(ServerLevel world, BlockPos origin, int hr, int depth) {
        for (int dx = -hr; dx <= hr; dx++) {
            for (int dz = -hr; dz <= hr; dz++) {
                for (int dy = 1; dy <= depth; dy++) {
                    world.setBlock(origin.offset(dx, -dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    // Gives the bot a set of deep-mining safety gear (helmet+chestplate+iron sword+shield), satisfying ensureMineOre's armor prerequisite for tier≥IRON ore (gold/diamond),
    // so the material tests focus on "mine → smelt → ingot" itself, without getting dragged into the "assemble armor first" chain (assembling armor is tested separately by achieve_armor).
    private static void giveDeepMineKit(AIPlayerEntity bot) {
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_HELMET, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_CHESTPLATE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_SWORD, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.SHIELD, 1));
    }

    // Deep-mine test prerequisite: the gold/diamond planner must issue a "dig down to Y=the deep ore layer" step (gold -16 / diamond -59). Rather than have the bot genuinely dig 60+ blocks down from y6
    // (slow, and the terrain/lava are uncontrollable), it's simpler to teleport the bot straight to the ore layer and clear + wall off a solid stone cube there: the descend step is skipped since the bot has already reached depth,
    // and the test focuses on "find ore at the ore layer → mine → (smelt)". Also fully provisions rations/torches/armor to skip the deep-mining food/lighting prerequisites. Returns the deep-layer origin.
    private static BlockPos prepareDeepArea(AIPlayerEntity bot, int depthY) {
        ServerLevel world = bot.level();
        bot.getActionPack().stopAll();
        bot.teleportTo(world, 0.5D, depthY, 0.5D, java.util.Collections.emptySet(), bot.getYRot(), bot.getXRot(), true);
        BlockPos origin = bot.blockPosition();
        clearNearbyMobs(world, origin);
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-4, 0, -4), origin.offset(4, 3, 4))) {
            world.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        fillStoneCube(world, origin, 6, 8); // solid stone below (including the floor at y-1)
        for (int dy = 0; dy <= 4; dy++) {   // surrounding vertical walls block deep lava/void/unknown terrain
            for (int d = -6; d <= 6; d++) {
                world.setBlock(origin.offset(d, dy, -6), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(origin.offset(d, dy, 6), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(origin.offset(-6, dy, d), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(origin.offset(6, dy, d), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        return origin;
    }

    // Deep-mine test's rations/lighting/crafting-table prerequisites (skips the hunting/cooking/torch-making/coal-mining chain in deep-mine planning, focusing on mining itself).
    private static void giveDeepMineSupplies(AIPlayerEntity bot) {
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.TORCH, 16));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE, 1));
    }

    private static void giveItemToAtLeast(AIPlayerEntity bot, Item item, int target) {
        int remaining = Math.max(0, target - InventoryAction.countItem(bot, item));
        int stackLimit = Math.max(1, new ItemStack(item).getMaxStackSize());
        while (remaining > 0) {
            int batch = Math.min(stackLimit, remaining);
            if (InventoryAction.giveItem(bot, new ItemStack(item, batch)).isFailed()) {
                throw new IllegalStateException("verify_fixture_inventory_full:item=" + item
                        + ":remaining=" + remaining);
            }
            remaining -= batch;
        }
    }

    // Extreme environment: spawns `count` zombies around the bot (the combat threshold maxEnemiesToFight=2, hence the default of 2 — the bot will fight rather than flee).
    // Tests "fighting while working": the survival reflex pauseFor's the combat, resumes the original task after the fight, and the task must still complete.
    private static void spawnHostiles(ServerLevel world, BlockPos origin, int count) {
        for (int i = 0; i < count; i++) {
            Zombie zombie = EntityType.ZOMBIE.create(world, EntitySpawnReason.COMMAND);
            if (zombie != null) {
                zombie.setPersistenceRequired(); // prevent natural despawning
                double side = (i % 2 == 0) ? 2.5D : -2.5D;
                zombie.snapTo(origin.getX() + side, origin.getY(), origin.getZ() + (i - count / 2), 0.0F, 0.0F);
                world.addFreshEntity(zombie);
            }
        }
    }

    private static int countContainer(AIPlayerEntity bot, BlockPos pos, Item item) {
        Optional<Container> inventory = io.github.zoyluo.minecraftai.action.ContainerAction.resolve(bot, pos);
        if (inventory.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int slot = 0; slot < inventory.get().getContainerSize(); slot++) {
            ItemStack stack = inventory.get().getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static final class VerifyRun {
        private final CommandSourceStack source;
        private final UUID botId;
        private final ArrayDeque<String> queue;
        private final List<Result> results;
        private ActiveScenario active;

        VerifyRun(CommandSourceStack source, UUID botId, List<String> features) {
            this.source = source;
            this.botId = botId;
            this.queue = new ArrayDeque<>(features);
            this.results = new ArrayList<>();
        }

        private UUID botId() {
            return botId;
        }

        private boolean tick(MinecraftServer server) {
            Optional<AIPlayerEntity> bot = AIPlayerManager.INSTANCE.getByUuid(botId);
            if (bot.isEmpty()) {
                record(Result.fail(active == null ? "run" : active.result().feature(), "bot_removed"));
                finish();
                return true;
            }
            if (active != null) {
                pollActive(server, bot.get());
                return false;
            }
            if (queue.isEmpty()) {
                finish();
                return true;
            }

            String feature = queue.removeFirst();
            Result result;
            long goalResultSequenceBefore = GoalExecutor.INSTANCE.lastResult(bot.get())
                    .map(io.github.zoyluo.minecraftai.goal.GoalResult::sequence)
                    .orElse(0L);
            try {
                result = startScenario(source, bot.get(), feature);
            } catch (RuntimeException | IOException exception) {
                result = Result.fail(feature, exception.getClass().getSimpleName() + ": " + exception.getMessage());
            }
            if (result.running()) {
                active = new ActiveScenario(result, server.getTickCount(), goalResultSequenceBefore);
                String message = "[MinecraftAi Verify] " + result.feature() + " RUNNING timeout=" + result.timeoutTicks();
                source.sendSuccess(() -> Component.literal(message), false);
                return false;
            }
            record(result);
            return false;
        }

        private void pollActive(MinecraftServer server, AIPlayerEntity bot) {
            Result running = active.result();
            Optional<String> fastFailure = running.failFast().apply(bot);
            if (fastFailure.isPresent()) {
                String reason = fastFailure.get();
                IntentController.INSTANCE.cancelAll(
                        bot, IntentController.ControlOrigin.SYSTEM, "verify_fail_fast:" + reason);
                record(Result.fail(running.feature(), reason));
                active = null;
                return;
            }
            running.perTick().accept(bot); // runs the scenario's per-tick world side effect (e.g. forcibly ripening crops), ahead of the state judgment below
            int elapsedTicks = server.getTickCount() - active.startedTick();
            TaskStatus status = TaskManager.INSTANCE.status(bot);
            // patient (LLM conversation-style) judging: under brain-driven control the bot dispatches multiple tasks in a row, retries with a different approach on failure, and idle-thinks between tasks,
            // so a single task's COMPLETED (the assertion isn't satisfied yet) / FAILED (the brain will still try to recover) is never the scenario's final word — the ordinary final-judgment logic below
            // would misjudge every LLM flow, so this must take over the whole block ahead of it. It does exactly two things: test the world-state assertion every tick
            // (regardless of task status, including idle/RUNNING), PASS as soon as it's satisfied; abort the task and FAIL on timeout (detail carries the last task status).
            if (running.patient()) {
                if (running.assertion().test(status)) {
                    record(Result.pass(running.feature(), "completed in " + elapsedTicks + " ticks"));
                    active = null;
                    return;
                }
                if (elapsedTicks >= running.timeoutTicks()) {
                    IntentController.INSTANCE.cancelAll(
                            bot, IntentController.ControlOrigin.SYSTEM, "verify_patient_timeout");
                    record(Result.fail(running.feature(), "verify_timeout status=" + status.name() + " " + status.description()));
                    active = null;
                }
                return;
            }
            if (running.allowGoalContinuation()) {
                var terminal = GoalExecutor.INSTANCE.resultAfter(bot, active.goalResultSequenceBefore());
                if (terminal.isPresent()) {
                    boolean moreGoalWork = GoalExecutor.INSTANCE.hasActivePlan(bot)
                            || GoalExecutor.INSTANCE.queuedGoalCount(bot) > 0;
                    if (!moreGoalWork) {
                        var goalResult = terminal.get();
                        if (goalResult.status() == io.github.zoyluo.minecraftai.goal.GoalResult.Status.COMPLETED
                                && running.assertion().test(status)) {
                            record(Result.pass(running.feature(), "goal accepted in " + elapsedTicks + " ticks evidence="
                                    + goalResult.evaluation().matched() + "/" + goalResult.evaluation().required()));
                        } else {
                            record(Result.fail(running.feature(), "goal_result=" + goalResult.status()
                                    + " evidence=" + goalResult.evaluation().matched() + "/" + goalResult.evaluation().required()
                                    + " unmet=" + goalResult.evaluation().unmet()));
                        }
                        active = null;
                        return;
                    }
                }
                if (elapsedTicks >= running.timeoutTicks()) {
                    IntentController.INSTANCE.cancelAll(
                            bot, IntentController.ControlOrigin.SYSTEM, "verify_goal_timeout");
                    record(Result.fail(running.feature(), "verify_timeout waiting_for_goal_result status="
                            + status.name() + " " + status.description()));
                    active = null;
                }
                return;
            }
            if (status.state() == TaskState.COMPLETED) {
                if (running.expectFail()) {
                    // Inverted scenario: the task "completing" is actually wrong — it means the scenario's premise never held up (the target was in fact reachable); record a FAIL prompting manual review of the setup.
                    record(Result.fail(running.feature(), "should_have_failed: completed in " + elapsedTicks + " ticks"));
                    active = null;
                    return;
                }
                if (running.assertion().test(status)) {
                    record(Result.pass(running.feature(), "completed in " + elapsedTicks + " ticks"));
                } else if (running.allowGoalContinuation() && GoalExecutor.INSTANCE.hasActivePlan(bot)) {
                    return;
                } else {
                    record(Result.fail(running.feature(), "assertion_failed status=" + status.name() + " " + status.description()));
                }
                active = null;
                return;
            }
            if (status.state() == TaskState.FAILED) {
                if (running.expectFail()) {
                    String failureReason = status.failureReason().isBlank()
                            ? "task_failed"
                            : status.failureReason();
                    if (running.assertion().test(status)) {
                        // An inverted scenario's PASS: it cleanly reported the expected failure before the timeout (rather than spinning forever).
                        record(Result.pass(running.feature(), "clean fail in " + elapsedTicks
                                + " ticks: " + failureReason));
                    } else {
                        record(Result.fail(running.feature(),
                                "unexpected_clean_failure reason=" + failureReason));
                    }
                    active = null;
                    return;
                }
                if (running.allowGoalContinuation() && GoalExecutor.INSTANCE.hasActivePlan(bot)) {
                    return;
                }
                record(Result.fail(running.feature(), status.failureReason().isBlank() ? "task_failed" : status.failureReason()));
                active = null;
                return;
            }
            if (elapsedTicks >= running.timeoutTicks()) {
                IntentController.INSTANCE.cancelAll(
                        bot, IntentController.ControlOrigin.SYSTEM, "verify_timeout");
                // An expectFail scenario timing out = the task neither completed nor admitted defeat, and just kept spinning — exactly the failure mode an inverted scenario is meant to pin down; a dedicated prefix makes it easy to recognize.
                record(Result.fail(running.feature(), (running.expectFail() ? "no_clean_fail_before_timeout" : "verify_timeout")
                        + " status=" + status.name() + " " + status.description()));
                active = null;
            }
        }

        private void record(Result result) {
            Result effective = finalizeMiningProvenance(result);
            results.add(effective);
            String message = "[MinecraftAi Verify] "
                    + effective.feature()
                    + " "
                    + (effective.pass() ? "PASS" : "FAIL")
                    + " - "
                    + effective.detail();
            source.sendSuccess(() -> Component.literal(message), false);
        }

        private Result finalizeMiningProvenance(Result result) {
            if (!MINING_ACCEPTANCE_FROM_ZERO_SUITE.contains(result.feature())) {
                return result;
            }
            Optional<AIPlayerEntity> liveBot = AIPlayerManager.INSTANCE.getByUuid(botId);
            Optional<MiningEvidenceAudit.Snapshot> snapshot = liveBot
                    .flatMap(MiningEvidenceAudit::snapshot)
                    .or(() -> MiningEvidenceAudit.snapshot(botId));
            int finalInventory = liveBot.map(bot -> InventoryAction.countItem(
                            bot,
                            result.feature().startsWith("diamond_") ? Items.DIAMOND : Items.OBSIDIAN))
                    .orElse(0);
            Result effective = result;
            if (snapshot.isEmpty()) {
                if (result.pass()) {
                    effective = Result.fail(result.feature(), "mining_provenance_session_missing");
                }
                io.github.zoyluo.minecraftai.log.BotLog.task(liveBot.orElse(null),
                        "mining_provenance_result",
                        "schema", MiningEvidenceAudit.SCHEMA_VERSION,
                        "scenario", result.feature(),
                        "target", result.feature().startsWith("diamond_") ? "diamond" : "obsidian",
                        "verdict", "FAIL",
                        "reason", "session_missing",
                        "observed_ticks", 0,
                        "game_mode_violations", 0,
                        "privileged_allowed", 0,
                        "death_delta", 0,
                        "diamond_natural_ore_breaks", 0,
                        "diamond_native_drops", 0,
                        "diamond_physical_pickups", 0,
                        "water_placements", 0,
                        "lava_conversions", 0,
                        "obsidian_breaks", 0,
                        "obsidian_physical_pickups", 0,
                        "vanilla_obsidian_breaks", 0,
                        "final_inventory", finalInventory);
                MiningEvidenceAudit.clear(botId);
                return effective;
            }

            MiningEvidenceAudit.Snapshot facts = snapshot.orElseThrow();
            // The audit session carries the scenario's own quota (diamond 64, obsidian 32/64);
            // a per-scenario hardcode here would accept the wrong inventory floor for the
            // 64-obsidian tier.
            boolean finalInventoryPass = finalInventory >= facts.requiredCount();
            boolean provenancePass = facts.passes() && finalInventoryPass;
            if (result.pass() && !provenancePass) {
                effective = Result.fail(result.feature(), "mining_provenance_postcondition_failed");
            }
            io.github.zoyluo.minecraftai.log.BotLog.task(liveBot.orElse(null),
                    "mining_provenance_result",
                    "schema", MiningEvidenceAudit.SCHEMA_VERSION,
                    "scenario", result.feature(),
                    "target", facts.target().name().toLowerCase(java.util.Locale.ROOT),
                    "verdict", effective.pass() && provenancePass ? "PASS" : "FAIL",
                    "observed_ticks", facts.observedTicks(),
                    "game_mode_violations", facts.gameModeViolations(),
                    "privileged_allowed", facts.privilegedAllowed(),
                    "death_delta", facts.deathDelta(),
                    "diamond_natural_ore_breaks", facts.diamondNaturalOreBreaks(),
                    "diamond_native_drops", facts.diamondNativeDrops(),
                    "diamond_physical_pickups", facts.diamondPhysicalPickups(),
                    "water_placements", facts.waterPlacements(),
                    "lava_conversions", facts.lavaConversions(),
                    "obsidian_breaks", facts.obsidianBreaks(),
                    "obsidian_physical_pickups", facts.obsidianPhysicalPickups(),
                    "vanilla_obsidian_breaks", facts.vanillaObsidianBreaks(),
                    "final_inventory", finalInventory);
            MiningEvidenceAudit.clear(botId);
            return effective;
        }

        private void finish() {
            long passed = results.stream().filter(Result::pass).count();
            String summary = "[MinecraftAi Verify] summary " + passed + "/" + results.size() + " PASS: " + summarize(results);
            if (passed == results.size()) {
                source.sendSuccess(() -> Component.literal(summary), false);
            } else {
                source.sendFailure(Component.literal(summary));
            }
        }

        private static String summarize(List<Result> results) {
            Map<String, String> parts = new LinkedHashMap<>();
            for (Result result : results) {
                parts.put(result.feature(), result.pass() ? "PASS" : "FAIL:" + result.detail());
            }
            return parts.toString();
        }

        private record ActiveScenario(Result result, int startedTick, long goalResultSequenceBefore) {
        }
    }

    private record Result(String feature,
                          boolean pass,
                          String detail,
                          boolean running,
                          int timeoutTicks,
                          boolean allowGoalContinuation,
                          boolean expectFail,
                          boolean patient,
                          Predicate<TaskStatus> assertion,
                          Function<AIPlayerEntity, Optional<String>> failFast,
                          Consumer<AIPlayerEntity> perTick) {
        private static final Consumer<AIPlayerEntity> NO_TICK = bot -> {
        };
        private static final Function<AIPlayerEntity, Optional<String>> NO_FAIL_FAST = bot -> Optional.empty();

        private static Result pass(String feature, String detail) {
            return new Result(feature, true, detail, false, 0, false, false, false,
                    ignored -> true, NO_FAIL_FAST, NO_TICK);
        }

        private static Result fail(String feature, String detail) {
            return new Result(feature, false, detail, false, 0, false, false, false,
                    ignored -> false, NO_FAIL_FAST, NO_TICK);
        }

        private static Result running(String feature, int timeoutTicks, Predicate<TaskStatus> assertion) {
            return new Result(feature, false, "running", true, timeoutTicks, false, false, false,
                    assertion, NO_FAIL_FAST, NO_TICK);
        }

        private static Result runningGoal(String feature, int timeoutTicks, Predicate<TaskStatus> assertion) {
            return new Result(feature, false, "running", true, timeoutTicks, true, false, false,
                    assertion, NO_FAIL_FAST, NO_TICK);
        }

        private static Result runningGoalFailFast(String feature, int timeoutTicks,
                                                  Function<AIPlayerEntity, Optional<String>> failFast,
                                                  Predicate<TaskStatus> assertion) {
            return new Result(feature, false, "running", true, timeoutTicks, true, false, false,
                    assertion, failFast, NO_TICK);
        }

        // A runningGoal with a per-tick side-effect hook: perTick is invoked on every server tick inside pollActive (regardless of whether a task has completed),
        // used to continuously manipulate the world during the test (e.g. forcibly ripening crops, skipping the long wait for natural random-tick growth). The assertion is still what determines success.
        private static Result runningGoal(String feature, int timeoutTicks,
                                          Consumer<AIPlayerEntity> perTick, Predicate<TaskStatus> assertion) {
            return new Result(feature, false, "running", true, timeoutTicks, true, false, false,
                    assertion, NO_FAIL_FAST, perTick);
        }

        // Inverted-scenario factory: expects the task to **cleanly FAIL** within timeoutTicks — that's what counts as a PASS (detail carries the failure reason + elapsed time);
        // COMPLETED, or still RUNNING at timeout, are both recorded as FAIL. Used to pin down the fault-tolerance contract that "an unreachable target must be admitted quickly",
        // preventing pathfinding from degenerating into endless retries and spinning (in real play, spinning is far more costly than an outright error: it looks like it's working while the whole run quietly dies).
        // The generic clean-fail only requires any non-spinning failure; use runningExpectTypedFail when the exact reason needs to be checked.
        private static Result runningExpectCleanFail(String feature, int timeoutTicks) {
            return new Result(feature, false, "running", true, timeoutTicks, false, true, false,
                    ignored -> true, NO_FAIL_FAST, NO_TICK);
        }

        private static Result runningExpectTypedFail(String feature,
                                                     int timeoutTicks,
                                                     String expectedFailureReason) {
            return new Result(feature, false, "running", true, timeoutTicks, false, true, false,
                    status -> expectedFailureReason.equals(status.failureReason()),
                    NO_FAIL_FAST,
                    NO_TICK);
        }

        // patient factory: dedicated to the R2 LLM full-chain layer. Under brain-driven conversational control, a single task's COMPLETED/FAILED is never the final word
        // (it will dispatch tasks in a row / retry on failure / idle-think), so pollActive skips every final-judgment check for patient,
        // recognizing only "the world-state assertion is satisfied" (PASS, completed in X ticks) or a timeout (abort+FAIL, detail carries the last task status).
        private static Result runningPatient(String feature, int timeoutTicks, Predicate<TaskStatus> assertion) {
            return new Result(feature, false, "running", true, timeoutTicks, false, false, true,
                    assertion, NO_FAIL_FAST, NO_TICK);
        }
    }
}

package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.TeleportAudit;
import io.github.zoyluo.minecraftai.gametest.BotFixtureMoves;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.MiningBudget;
import io.github.zoyluo.minecraftai.navigation.NavEngine;
import io.github.zoyluo.minecraftai.navigation.NavEngineSelector;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * No micro-teleports in DescendToYTask (R5): the stair steps down, the flat landings, the climb-overs, the lateral detours and the
 * sneak-bridge over the edge of a lone pillar are walked steps ({@link WalkedStep}) whose landing is published when it is verified,
 * never in the tick that starts them. Every test runs in the default strict-survival profile and asserts on every tick that
 * {@code TeleportAudit.corrections(bot) == 0} and that the bot took no damage. Each test has its own world layer and its own test
 * environment.
 */
public final class DescendNaturalMovementGameTests {
    private static final int BASE_Y = 130;
    private static final int LAYER_STEP = 48;

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
            throw new IllegalStateException(message);
        }
    }

    /**
     * A solid stone volume with open air above it (a stair cut north, -z, from {@code feet} runs into the stone) and a light grid: a
     * staircase of {@code depth} levels fits below and north of the entry cell. Own layer per test.
     */
    private static BlockPos buildStoneArena(GameTestHelper context, int layer, int depth) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(8, BASE_Y + LAYER_STEP * layer, depth + 4));
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -(depth + 3); dz <= 2; dz++) {
                for (int dy = -(depth + 3); dy <= 6; dy++) {
                    Block block = dy < 0 ? Blocks.STONE : Blocks.AIR;
                    world.setBlock(feet.offset(dx, dy, dz), block.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        return feet;
    }

    /** Air everywhere around {@code feet} (a cube of radius 4) with one stone block under it: an isolated pillar. */
    private static BlockPos buildPillarArena(GameTestHelper context, int layer) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(8, BASE_Y + LAYER_STEP * layer, 12));
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    world.setBlock(feet.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        return feet;
    }

    private static AIPlayerEntity spawn(GameTestHelper context, String name, BlockPos feet) {
        ServerLevel world = context.getLevel();
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet), 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        NavEngineSelector.setBotEngine(bot.getUUID(), NavEngine.LEGACY);
        BotFixtureMoves.place(bot, feet);
        bot.setOnGround(true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.getFoodData().setSaturation(20.0F);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        Standability.clearCache();
        TeleportAudit.reset(bot);
        return bot;
    }

    private static void finish(GameTestHelper context, AIPlayerEntity bot, DescendToYTask task) {
        task.cancel(bot, "gametest_complete");
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
        bot.getActionPack().stopAll();
        bot.getActionPack().clearPace();
        NavEngineSelector.clearBotEngine(bot.getUUID());
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), bot.getGameProfile().name());
        context.succeed();
    }

    private static String audit(AIPlayerEntity bot) {
        return "corrections=" + TeleportAudit.corrections(bot) + " last=" + TeleportAudit.lastCaller(bot);
    }

    private static void requireUntouched(GameTestHelper context, AIPlayerEntity bot, String what) {
        require(context, TeleportAudit.corrections(bot) == 0, what + ": the bot was teleported (" + audit(bot) + ")");
        require(context, bot.getHealth() >= bot.getMaxHealth() - 0.01F,
                what + ": the bot took damage (health " + bot.getHealth() + ")");
    }

    private static String encode(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** A checkpoint that forces the detour path from {@code origin}: all four stair directions were rejected there. */
    private static Map<String, String> allDirectionsRejected(AIPlayerEntity bot, BlockPos origin, int targetY) {
        DescendToYTask fresh = new DescendToYTask(targetY);
        fresh.start(bot);
        Map<String, String> checkpoint = new LinkedHashMap<>(fresh.checkpoint());
        fresh.cancel(bot, "gametest_fixture");
        checkpoint.put("budget_used", "1");
        checkpoint.put("last_progress_budget", "1");
        checkpoint.put("stair_direction", "0");
        checkpoint.put("rejected_landing_origin", encode(origin));
        checkpoint.put("rejected_landing_directions", "15");
        return checkpoint;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The staircase
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A DescendToYTask cuts a stair twenty levels deep into solid stone. Every level is a walked step off the edge of the tread onto
     * the next one: the bot is never teleported, takes no damage (a one block drop hurts nothing) and ends on the target layer.
     */
    @GameTest(environment = "minecraftai-gametest:descend_natural_movement_game_tests_descend_twenty_without_teleport", maxTicks = 4200)
    public void descendTwentyWithoutTeleport(GameTestHelper context) {
        BlockPos start = buildStoneArena(context, 0, 20);
        AIPlayerEntity bot = spawn(context, "DescendWalkGT", start);
        int targetY = start.getY() - 20;
        DescendToYTask task = new DescendToYTask(targetY);
        task.start(bot);
        int[] steps = {0};
        boolean[] wasIdle = {true};
        DescendTickStages.run(context,
                DescendTickStages.tickUntil(context, task, bot, 4000,
                        "the twenty level descent did not finish",
                        () -> {
                            requireUntouched(context, bot, "descend twenty at " + bot.blockPosition().toShortString());
                            boolean idle = bot.getActionPack().stepIdle();
                            if (idle && !wasIdle[0]) {
                                steps[0]++;
                            }
                            wasIdle[0] = idle;
                            return task.state() == TaskState.COMPLETED;
                        }),
                () -> {
                    require(context, bot.blockPosition().getY() == targetY,
                            "the descent completed off its target layer: " + bot.blockPosition().toShortString());
                    require(context, steps[0] >= 20, "the descent was not walked step by step: " + steps[0] + " steps");
                    finish(context, bot, task);
                    return true;
                });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The climb-over
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A solid block stands in the way of the stair at the bot's feet level, its top face is open and the cell below it is hidden:
     * Descend climbs over it with a hop (forward and jump) instead of mining it, without a teleport. The block stays.
     */
    @GameTest(environment = "minecraftai-gametest:descend_natural_movement_game_tests_climb_over_lip_by_jump", maxTicks = 200)
    public void climbOverLipByJump(GameTestHelper context) {
        BlockPos start = buildPillarArena(context, 1);
        BlockPos lip = start.north();
        BlockPos top = lip.above();
        context.getLevel().setBlock(lip, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();
        AIPlayerEntity bot = spawn(context, "DescendClimbGT", start);
        DescendToYTask task = new DescendToYTask(start.getY() - 5);
        task.start(bot);
        double[] peak = {start.getY()};
        DescendTickStages.run(context,
                DescendTickStages.tickUntil(context, task, bot, 120,
                        "Descend did not climb over the lip in its way",
                        () -> {
                            requireUntouched(context, bot, "climb over at " + bot.blockPosition().toShortString());
                            peak[0] = Math.max(peak[0], bot.getY());
                            return bot.blockPosition().equals(top) && bot.getActionPack().stepIdle()
                                    && "1".equals(task.checkpoint().get("lateral_detours"));
                        }),
                () -> {
                    require(context, context.getLevel().getBlockState(lip).is(Blocks.STONE),
                            "Descend mined the block it was to climb over");
                    require(context, peak[0] >= top.getY() + 0.05D,
                            "the climb was not a hop: the bot never rose above the top of the lip (peak " + peak[0] + ")");
                    require(context, task.state() == TaskState.RUNNING,
                            "Descend ended after the climb-over: " + task.state() + ":" + task.failureReason());
                    finish(context, bot, task);
                    return true;
                });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The edge shift: the sneak-bridge over a lone pillar
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The bot stands on a lone stone pillar with every direction rejected and one block to spare: it leans out over the edge while
     * sneaking (its body past the edge of the pillar, never falling), places one floor block against the side of the pillar, walks
     * back to the middle of the cell and then steps onto the new floor. No teleport, no fall, no damage, one block used.
     */
    @GameTest(environment = "minecraftai-gametest:descend_natural_movement_game_tests_edge_shift_by_sneak", maxTicks = 500)
    public void edgeShiftBySneak(GameTestHelper context) {
        BlockPos origin = buildPillarArena(context, 2);
        AIPlayerEntity bot = spawn(context, "DescendEdgeGT", origin);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, MiningBudget.EMERGENCY_STONE_LIKE + 1));
        DescendToYTask task = new DescendToYTask(origin.getY() - 5, allDirectionsRejected(bot, origin, origin.getY() - 5));
        task.start(bot);
        BlockPos landing = origin.north();
        BlockPos support = landing.below();
        double[] lean = {0.0D};
        boolean[] sneakedOverTheEdge = {false};
        DescendTickStages.run(context,
                DescendTickStages.tickUntil(context, task, bot, 400,
                        "Descend did not bridge onto the new floor",
                        () -> {
                            requireUntouched(context, bot, "edge shift at " + bot.blockPosition().toShortString());
                            require(context, bot.getY() >= origin.getY() - 0.01D,
                                    "the bot fell off the edge: " + bot.position());
                            double over = origin.getZ() + 0.5D - bot.getZ();
                            lean[0] = Math.max(lean[0], over);
                            if (over >= 0.5D && bot.isShiftKeyDown()) {
                                sneakedOverTheEdge[0] = true;
                            }
                            return bot.blockPosition().equals(landing) && bot.getActionPack().stepIdle()
                                    && "1".equals(task.checkpoint().get("lateral_detours"));
                        }),
                () -> {
                    require(context, lean[0] >= 0.5D, "the bot never leaned over the edge of its pillar (lean " + lean[0] + ")");
                    require(context, sneakedOverTheEdge[0], "the bot was over the edge without sneaking");
                    require(context, context.getLevel().getBlockState(support).is(Blocks.COBBLESTONE),
                            "no floor block was placed under the landing");
                    require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == MiningBudget.EMERGENCY_STONE_LIKE,
                            "the bridge did not use exactly one block");
                    require(context, task.state() == TaskState.RUNNING,
                            "Descend ended after the bridge: " + task.state() + ":" + task.failureReason());
                    finish(context, bot, task);
                    return true;
                });
    }

    /**
     * The task is paused (a safety task takes over) while the bot leans over the edge of its pillar. The step is cancelled, the bot
     * keeps standing on the edge of its support, and when the task resumes it walks back onto the pillar (never teleported, never
     * falling) and builds the bridge from there.
     */
    @GameTest(environment = "minecraftai-gametest:descend_natural_movement_game_tests_pause_mid_lean_walks_back", maxTicks = 700)
    public void pauseMidLeanWalksBack(GameTestHelper context) {
        BlockPos origin = buildPillarArena(context, 3);
        AIPlayerEntity bot = spawn(context, "DescendLeanPauseGT", origin);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, MiningBudget.EMERGENCY_STONE_LIKE + 1));
        DescendToYTask task = new DescendToYTask(origin.getY() - 5, allDirectionsRejected(bot, origin, origin.getY() - 5));
        task.start(bot);
        BlockPos landing = origin.north();
        int[] settle = {0};
        DescendTickStages.run(context,
                DescendTickStages.tickUntil(context, task, bot, 200,
                        "the bot never leaned over the edge",
                        () -> {
                            requireUntouched(context, bot, "pause mid lean at " + bot.blockPosition().toShortString());
                            return !bot.getActionPack().stepIdle() && origin.getZ() + 0.5D - bot.getZ() >= 0.3D;
                        }),
                () -> {
                    task.pause(bot);
                    require(context, bot.getActionPack().stepIdle(), "pausing left a step in flight");
                    return true;
                },
                () -> {
                    requireUntouched(context, bot, "paused on the edge at " + bot.blockPosition().toShortString());
                    require(context, bot.getY() >= origin.getY() - 0.01D, "the paused bot fell: " + bot.position());
                    return ++settle[0] >= 10;
                },
                () -> {
                    task.resume(bot);
                    return true;
                },
                DescendTickStages.tickUntil(context, task, bot, 500,
                        "the resumed task did not walk back and bridge onto the new floor",
                        () -> {
                            requireUntouched(context, bot, "resumed at " + bot.blockPosition().toShortString());
                            require(context, bot.getY() >= origin.getY() - 0.01D, "the bot fell: " + bot.position());
                            return bot.blockPosition().equals(landing) && bot.getActionPack().stepIdle()
                                    && "1".equals(task.checkpoint().get("lateral_detours"));
                        }),
                () -> {
                    require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == MiningBudget.EMERGENCY_STONE_LIKE,
                            "the bridge did not use exactly one block");
                    finish(context, bot, task);
                    return true;
                });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // A restart in the middle of a step re-derives the stair from where the bot really is
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The process restarts (the task is cancelled and rebuilt from its checkpoint) while a stair step is in flight. The checkpoint
     * holds no landing that has not been verified; the restored task re-derives the stair from the bot's block position and finishes
     * the descent by walking.
     */
    @GameTest(environment = "minecraftai-gametest:descend_natural_movement_game_tests_restart_mid_step_re_derives", maxTicks = 1600)
    public void restartMidStepReDerives(GameTestHelper context) {
        BlockPos start = buildStoneArena(context, 4, 4);
        AIPlayerEntity bot = spawn(context, "DescendRestartGT", start);
        int targetY = start.getY() - 3;
        DescendToYTask first = new DescendToYTask(targetY);
        first.start(bot);
        Map<String, String>[] snapshot = new Map[1];
        DescendToYTask[] restored = new DescendToYTask[1];
        int[] settle = {0};
        DescendTickStages.run(context,
                DescendTickStages.tickUntil(context, first, bot, 900,
                        "no stair step was ever in flight",
                        () -> {
                            requireUntouched(context, bot, "restart mid step at " + bot.blockPosition().toShortString());
                            // Mid step: a walked step is in flight and the bot has really left its cell (walked off, or in the air).
                            return !bot.getActionPack().stepIdle() && bot.getY() < start.getY() - 0.05D
                                    && !bot.onGround();
                        }),
                () -> {
                    snapshot[0] = first.checkpoint();
                    require(context, "none".equals(snapshot[0].get("pending_landing_target")),
                            "the checkpoint recorded a landing that was not verified: " + snapshot[0]);
                    first.cancel(bot, "simulate_process_restart");
                    require(context, bot.getActionPack().stepIdle(), "cancelling left a step in flight");
                    return true;
                },
                () -> {
                    requireUntouched(context, bot, "settling after the restart at " + bot.blockPosition().toShortString());
                    return ++settle[0] >= 30 && WalkedStep.supported(bot);
                },
                () -> {
                    DescendToYTask task = new DescendToYTask(targetY, snapshot[0]);
                    task.start(bot);
                    require(context, task.state() == TaskState.RUNNING,
                            "the restored task did not start: " + task.state() + " " + task.failureReason());
                    restored[0] = task;
                    return true;
                },
                DescendTickStages.tickUntil(context, () -> restored[0], bot, 1200,
                        "the restored descent did not finish",
                        () -> {
                            requireUntouched(context, bot, "restored descent at " + bot.blockPosition().toShortString());
                            return restored[0].state() == TaskState.COMPLETED;
                        }),
                () -> {
                    require(context, bot.blockPosition().getY() == targetY,
                            "the restored descent completed off its target layer: " + bot.blockPosition().toShortString());
                    finish(context, bot, restored[0]);
                    return true;
                });
    }
}

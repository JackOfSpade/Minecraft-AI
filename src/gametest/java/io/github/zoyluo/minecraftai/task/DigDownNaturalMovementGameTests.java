package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.baritone.BaritoneEdits;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.TeleportAudit;
import io.github.zoyluo.minecraftai.gametest.BotFixtureMoves;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.navigation.NavEngine;
import io.github.zoyluo.minecraftai.navigation.NavEngineSelector;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * No micro-teleports in DigDownTask (R5): the staircase descent, the horizontal advance and the return up the recorded trail are all
 * walked steps ({@link WalkedStep}) whose landing is published when it is verified, never in the tick that starts them. Every test
 * runs in the default strict-survival profile, resets {@link TeleportAudit} for its bot after the fixture is built and asserts
 * {@code TeleportAudit.corrections(bot) == 0} on every tick. Each test has its own world layer and its own test environment.
 */
public final class DigDownNaturalMovementGameTests {
    private static final int BASE_Y = 130;
    private static final int LAYER_STEP = 24;

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
            throw new IllegalStateException(message);
        }
    }

    /**
     * A solid stone volume twelve blocks deep with open air above it, a light grid (no natural spawns) and the digging direction
     * (north, -z) free for fourteen blocks: the stair a DigDownTask cuts starts here and runs into the stone. Own layer per test.
     */
    private static BlockPos buildShaftArena(GameTestHelper context, int layer) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(8, BASE_Y + LAYER_STEP * layer, 14));
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -14; dz <= 1; dz++) {
                for (int dy = -14; dy <= 6; dy++) {
                    Block block = dy < 0 ? Blocks.STONE : Blocks.AIR;
                    world.setBlock(feet.offset(dx, dy, dz), block.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        for (int dx = -4; dx <= 4; dx += 4) {
            for (int dz = -12; dz <= 0; dz += 4) {
                world.setBlock(feet.offset(dx, 5, dz), Blocks.LIGHT.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        return feet;
    }

    private static AIPlayerEntity spawn(GameTestHelper context, String name, BlockPos feet) {
        ServerLevel world = context.getLevel();
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet), 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        NavEngineSelector.setBotEngine(bot.getUUID(), NavEngine.BARITONE);
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

    private static void finish(GameTestHelper context, AIPlayerEntity bot, DigDownTask task) {
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

    private static DigDownTask.DigDownCheckpoint decode(DigDownTask task) {
        return DigDownTask.DigDownCheckpoint.decode(task.checkpoint()).orElse(null);
    }

    /** Where the bot last stood on a floor: the cell a step it now walks began in. */
    private static final class Stand {
        BlockPos cell;

        void note(AIPlayerEntity bot) {
            if (bot.getActionPack().stepIdle() && WalkedStep.supported(bot)) {
                cell = bot.blockPosition();
            }
        }

        /** True while a walked step is in flight and the bot is really away from where it began (walking off, or in the air). */
        boolean midStep(AIPlayerEntity bot) {
            if (bot.getActionPack().stepIdle() || cell == null) {
                return false;
            }
            Vec3 from = Vec3.atBottomCenterOf(cell);
            return Math.hypot(bot.getX() - from.x, bot.getZ() - from.z) > 0.4D || !bot.onGround();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The whole trip: dig down, come back up the trail
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A DigDownTask cuts a stair eight blocks deep into solid stone and climbs back to its entry cell. Every descent step and every
     * step of the return is a walked step: the bot is never teleported, takes no damage and ends on its exact entry cell.
     */
    @GameTest(environment = "minecraftai-gametest:dig_down_natural_movement_game_tests_dig_down_and_return_without_teleport", maxTicks = 2200)
    public void digDownAndReturnWithoutTeleport(GameTestHelper context) {
        BlockPos start = buildShaftArena(context, 0);
        AIPlayerEntity bot = spawn(context, "DigDownWalkGT", start);
        DigDownTask task = new DigDownTask(Blocks.STONE, 16);
        task.start(bot);
        int[] lowest = {start.getY()};
        int[] ticks = {0};
        context.onEachTick(() -> {
            ticks[0]++;
            lowest[0] = Math.min(lowest[0], bot.blockPosition().getY());
            requireUntouched(context, bot, "dig down and return, tick " + ticks[0] + " at " + bot.blockPosition().toShortString());
            require(context, task.state() == TaskState.RUNNING || task.state() == TaskState.COMPLETED,
                    "the task ended as " + task.state() + ": " + task.failureReason());
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
                return;
            }
            require(context, start.getY() - lowest[0] >= 6,
                    "the descent never went deep: lowest " + lowest[0] + " from " + start.getY());
            require(context, bot.blockPosition().equals(start),
                    "the task completed away from its entry: " + bot.blockPosition().toShortString());
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) >= 16,
                    "the task completed without the requested stone");
            finish(context, bot, task);
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Historical DigNav compatibility boundary
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The old DigNav fallback could turn a target embedded in stone into a blind descent. Its
     * compatibility adapter now submits only an observed Baritone route, so this unseen target
     * is refused without a break, a route, or a teleport.
     */
    @GameTest(environment = "minecraftai-gametest:dig_down_natural_movement_game_tests_dig_nav_drops_into_the_hole_it_dug", maxTicks = 1200)
    public void hiddenDigNavTargetIsRefusedWithoutExcavation(GameTestHelper context) {
        BlockPos start = buildShaftArena(context, 3);
        AIPlayerEntity bot = spawn(context, "DigNavDropGT", start);
        BlockPos target = start.offset(0, -3, -1);
        io.github.zoyluo.minecraftai.action.BlockMiner miner = new io.github.zoyluo.minecraftai.action.BlockMiner();
        require(context, !io.github.zoyluo.minecraftai.action.DigNav.digStep(bot, miner, target),
                "the hidden DigNav target was admitted for movement");
        require(context, !bot.getActionPack().hasBaritoneRoute(),
                "the hidden DigNav target left a Baritone route active");
        require(context, BaritoneEdits.of(bot.getUUID()).isEmpty(),
                "the hidden DigNav target excavated terrain: " + BaritoneEdits.of(bot.getUUID()));
        context.runAfterDelay(20, () -> {
            requireUntouched(context, bot, "hidden DigNav target");
            require(context, !bot.getActionPack().hasBaritoneRoute(),
                    "the hidden DigNav target began a route later");
            require(context, BaritoneEdits.of(bot.getUUID()).isEmpty(),
                    "the hidden DigNav target later excavated terrain: " + BaritoneEdits.of(bot.getUUID()));
            bot.getActionPack().stopAll();
            NavEngineSelector.clearBotEngine(bot.getUUID());
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), bot.getGameProfile().name());
            context.succeed();
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // A pause or a restart in the middle of a step re-derives the trail from where the bot really is
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The task is paused (a safety task takes over) while a descent step is in flight. The step is cancelled, nothing in-flight is
     * recorded (every trail cell stays a standable cell next to the one before it), the bot settles where gravity puts it and the
     * resumed task climbs back to its exact entry by walking.
     */
    @GameTest(environment = "minecraftai-gametest:dig_down_natural_movement_game_tests_pause_mid_descent_re_derives_trail", maxTicks = 2200)
    public void pauseMidDescentReDerivesTrail(GameTestHelper context) {
        BlockPos start = buildShaftArena(context, 1);
        AIPlayerEntity bot = spawn(context, "DigDownPauseGT", start);
        DigDownTask task = new DigDownTask(Blocks.STONE, 16);
        task.start(bot);
        Stand stand = new Stand();
        int[] phase = {0};
        int[] ticks = {0};
        int[] settle = {0};
        context.onEachTick(() -> {
            ticks[0]++;
            requireUntouched(context, bot, "pause mid descent, phase " + phase[0] + " at " + bot.blockPosition().toShortString());
            require(context, ticks[0] < 2150, "timed out in phase " + phase[0] + " at " + bot.position());
            switch (phase[0]) {
                case 0 -> {
                    require(context, task.state() == TaskState.RUNNING, "the descent ended early: " + task.state() + " " + task.failureReason());
                    DigDownTask.DigDownCheckpoint live = decode(task);
                    stand.note(bot);
                    if (live != null && live.phase() == DigDownTask.Phase.DESCEND && bot.getY() < start.getY() - 1.5D
                            && stand.midStep(bot)) {
                        task.pause(bot);
                        require(context, bot.getActionPack().stepIdle(), "pausing left a step in flight");
                        phase[0] = 1;
                        return;
                    }
                    task.tick(bot);
                }
                case 1 -> {
                    if (++settle[0] < 30) {
                        return;
                    }
                    require(context, WalkedStep.supported(bot), "the paused bot did not settle on a floor: " + bot.position());
                    DigDownTask.DigDownCheckpoint paused = decode(task);
                    require(context, paused != null && paused.phase() == DigDownTask.Phase.RETURN
                                    && paused.returnOutcome() == DigDownTask.ReturnOutcome.SAFETY_INTERRUPTED,
                            "the pause did not publish safety RETURN debt: " + task.checkpoint());
                    List<BlockPos> trail = paused.trail();
                    for (int i = 1; i < trail.size(); i++) {
                        BlockPos from = trail.get(i - 1);
                        BlockPos to = trail.get(i);
                        require(context, Math.abs(from.getX() - to.getX()) <= 1 && Math.abs(from.getY() - to.getY()) <= 1
                                        && Math.abs(from.getZ() - to.getZ()) <= 1,
                                "the trail has a gap between " + from.toShortString() + " and " + to.toShortString());
                        require(context, Standability.isStandable(context.getLevel(), to),
                                "an in-flight cell was recorded in the trail: " + to.toShortString());
                    }
                    task.resume(bot);
                    phase[0] = 2;
                }
                default -> {
                    if (task.state() == TaskState.RUNNING) {
                        task.tick(bot);
                        return;
                    }
                    require(context, bot.blockPosition().equals(start),
                            "the resumed task ended away from its entry: " + bot.blockPosition().toShortString()
                                    + " state=" + task.state() + " " + task.failureReason());
                    require(context, task.state() == TaskState.COMPLETED
                                    || task.failureReason().startsWith("dig_down_safety_interrupted"),
                            "the resumed task ended as " + task.state() + ": " + task.failureReason());
                    finish(context, bot, task);
                }
            }
        });
    }

    /**
     * The process restarts (the task is cancelled and rebuilt from its checkpoint) while a step of the return is in flight. The
     * checkpoint holds no step; the restored task re-derives its position on the recorded trail from the bot's block position and
     * walks the rest of the way back to the exact entry, never by a teleport.
     */
    @GameTest(environment = "minecraftai-gametest:dig_down_natural_movement_game_tests_restart_mid_return_re_derives_trail", maxTicks = 2200)
    public void restartMidReturnReDerivesTrail(GameTestHelper context) {
        BlockPos start = buildShaftArena(context, 2);
        AIPlayerEntity bot = spawn(context, "DigDownRestartGT", start);
        DigDownTask first = new DigDownTask(Blocks.STONE, 4);
        first.start(bot);
        Stand stand = new Stand();
        DigDownTask[] current = {first};
        AtomicReference<Map<String, String>> snapshot = new AtomicReference<>();
        int[] phase = {0};
        int[] ticks = {0};
        int[] settle = {0};
        context.onEachTick(() -> {
            ticks[0]++;
            requireUntouched(context, bot, "restart mid return, phase " + phase[0] + " at " + bot.blockPosition().toShortString());
            require(context, ticks[0] < 2150, "timed out in phase " + phase[0] + " at " + bot.position());
            switch (phase[0]) {
                case 0 -> {
                    require(context, first.state() == TaskState.RUNNING, "the trip ended early: " + first.state() + " " + first.failureReason());
                    DigDownTask.DigDownCheckpoint live = decode(first);
                    stand.note(bot);
                    if (live != null && live.phase() == DigDownTask.Phase.RETURN && stand.midStep(bot)) {
                        snapshot.set(first.checkpoint());
                        first.cancel(bot, "simulate_process_restart");
                        require(context, bot.getActionPack().stepIdle(), "cancelling left a step in flight");
                        phase[0] = 1;
                        return;
                    }
                    first.tick(bot);
                }
                case 1 -> {
                    if (++settle[0] < 30) {
                        return;
                    }
                    require(context, WalkedStep.supported(bot), "the restarted bot did not settle on a floor: " + bot.position());
                    DigDownTask restored = new DigDownTask(Blocks.STONE, 4, snapshot.get());
                    restored.start(bot);
                    require(context, restored.state() == TaskState.RUNNING,
                            "the restored task did not start: " + restored.state() + " " + restored.failureReason());
                    DigDownTask.DigDownCheckpoint back = decode(restored);
                    require(context, back != null && back.phase() == DigDownTask.Phase.RETURN,
                            "the restored task lost its return debt: " + restored.checkpoint());
                    current[0] = restored;
                    phase[0] = 2;
                }
                default -> {
                    DigDownTask task = current[0];
                    if (task.state() == TaskState.RUNNING) {
                        task.tick(bot);
                        return;
                    }
                    require(context, bot.blockPosition().equals(start),
                            "the restored task ended away from its entry: " + bot.blockPosition().toShortString()
                                    + " state=" + task.state() + " " + task.failureReason());
                    require(context, task.state() == TaskState.COMPLETED,
                            "the restored task ended as " + task.state() + ": " + task.failureReason());
                    finish(context, bot, task);
                }
            }
        });
    }
}

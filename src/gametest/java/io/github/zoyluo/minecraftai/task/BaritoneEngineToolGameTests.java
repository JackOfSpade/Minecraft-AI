package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.ToolSelector;
import io.github.zoyluo.minecraftai.baritone.BaritoneEdits;
import io.github.zoyluo.minecraftai.baritone.BaritoneRegistry;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.navigation.NavOutcome;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The tool of a Baritone-driven break is the mod's own choice ({@code ToolSelector}, made once when the break starts), not
 * Baritone's: a bot that carries a stone and an iron pickaxe digs stone with the stone one (the iron one is kept for the ores
 * that need it), and a sword is never a mining tool (it would be worn down on leaves or cobweb for nothing). Baritone's own
 * auto-tool, the fastest tool of the hotbar, is switched off ({@code BaritoneSettings}); its cost model prices the break with the
 * tool the policy picks ({@code BaritoneToolPolicy}, patch 0016), so the course below still finishes inside the movement time
 * budgets although the stone pickaxe is slower than the iron one.
 */
public final class BaritoneEngineToolGameTests {
    private static final double ARRIVED = 3.6D;

    /**
     * A follower with an iron pickaxe in hand and a stone pickaxe in the hotbar has to dig through a three-thick stone wall to reach
     * the player behind it: every block goes with the stone pickaxe (worn), the iron one keeps all its durability, and the follow
     * still arrives (the cost model's break time matches the tool that is really used).
     */
    @GameTest(environment = "minecraftai-gametest:baritone_engine_tool_game_tests_follow_through_astone_wall_breaks_with_the_stone_pickaxe_not_the_iron_one", maxTicks = 1400)
    public void followThroughAStoneWallBreaksWithTheStonePickaxeNotTheIronOne(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 6, 14, 5);
        for (int dx = 0; dx <= 2; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                arena.fill(dx, dz, Blocks.STONE, 0, BaritoneEngineArena.CEILING - 1);
            }
        }
        AIPlayerEntity target = arena.spawnHolder("BeToolWallTgt", arena.cell(7, 0, 0));
        AIPlayerEntity bot = arena.spawnOnBaritone("BeToolWall", arena.cell(-7, 0, 0));
        bot.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        bot.getInventory().setItem(1, new ItemStack(Items.STONE_PICKAXE));
        bot.getInventory().setItem(2, new ItemStack(Items.DIAMOND_SWORD));
        bot.getInventory().setSelectedSlot(0);
        FollowTask follow = new FollowTask("BeToolWallTgt");
        TaskManager.INSTANCE.assign(bot, follow, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_baritone_follow_tool_wall"));
        int[] tick = {0};
        context.failIfEver(() -> {
            int now = ++tick[0];
            arena.require(follow.state() == TaskState.RUNNING, "follow ended early: " + follow.state() + " " + follow.failureReason());
            arena.require(now < 1350, "the follower never got through the wall: " + describe(bot, arena)
                    + " breaks=" + BaritoneEdits.of(bot.getUUID(), BaritoneEdits.Kind.BREAK).size());
            if (follow.isWaiting() && bot.getX() > arena.origin.getX() + 2.5D && bot.distanceTo(target) <= ARRIVED) {
                List<BaritoneEdits.Edit> breaks = BaritoneEdits.of(bot.getUUID(), BaritoneEdits.Kind.BREAK);
                arena.require(breaks.size() >= 4, "too few blocks were dug through a three-thick wall: " + breaks.size());
                for (BaritoneEdits.Edit edit : breaks) {
                    arena.require(edit.block().equals("minecraft:stone"), "dug " + edit.block());
                    arena.require(edit.tool().equals("minecraft:stone_pickaxe"),
                            "broke " + edit.block() + " with " + edit.tool() + " instead of the stone pickaxe: " + breaks);
                }
                ItemStack iron = bot.getInventory().getItem(0);
                ItemStack stone = bot.getInventory().getItem(1);
                arena.require(iron.is(Items.IRON_PICKAXE) && iron.getDamageValue() == 0, "the iron pickaxe was worn: " + iron.getDamageValue());
                arena.require(stone.is(Items.STONE_PICKAXE) && stone.getDamageValue() >= breaks.size(),
                        "the stone pickaxe was not the one that dug: damage " + stone.getDamageValue() + " for " + breaks.size() + " blocks");
                arena.require(follow.baritoneStarts() >= 1 && BaritoneRegistry.INSTANCE.find(bot.getUUID()) != null, "the route was not Baritone's");
                arena.finish(bot, target);
            }
        });
    }

    private static String describe(AIPlayerEntity bot, BaritoneEngineArena arena) {
        var baritone = BaritoneRegistry.INSTANCE.find(bot.getUUID());
        String path = "none";
        if (baritone != null) {
            path = "current=" + baritone.getPathingBehavior().getCurrent() + " inProgress=" + baritone.getPathingBehavior().getInProgress().isPresent()
                    + " goal=" + baritone.getPathingBehavior().getGoal();
        }
        if (baritone != null && baritone.getPathingBehavior().getCurrent() != null) {
            var current = baritone.getPathingBehavior().getCurrent();
            int at = current.getPosition();
            var moves = current.getPath().movements();
            path += " step=" + at + "/" + moves.size();
            if (at < moves.size()) {
                var move = moves.get(at);
                path += " move=" + move.getClass().getSimpleName() + " " + move.getSrc() + "->" + move.getDest();
                BlockPos dest = new BlockPos(move.getDest().x, move.getDest().y, move.getDest().z);
                path += " destState=" + arena.world.getBlockState(dest).getBlock() + "/" + arena.world.getBlockState(dest.above()).getBlock();
                try {
                    var field = baritone.pathing.movement.Movement.class.getDeclaredField("currentState");
                    field.setAccessible(true);
                    var state = (baritone.pathing.movement.MovementState) field.get(move);
                    path += " status=" + state.getStatus() + " targetRot=" + state.getTarget().getRotation() + " force=" + state.getTarget().hasToForceRotations()
                            + " inputs=" + state.getInputStates();
                } catch (ReflectiveOperationException e) {
                    path += " (no state: " + e + ")";
                }
            }
        }
        return "pos=" + bot.position() + " selected=" + bot.getInventory().getSelectedSlot() + " " + path
                + " refusals=" + io.github.zoyluo.minecraftai.baritone.BaritoneRefusals.of(bot.getUUID());
    }

    /**
     * The host's tool policy is what prices a break in the cost model (patch 0016), and the inventory snapshot it works on is taken
     * lazily: a cost model for the game thread (the per-tick sprint and water-bucket checks of a driven bot) copies no inventory
     * unless a break is priced, one for another thread (a path search) takes it at once. Differential: with a stone and an iron pickaxe
     * in the hotbar the policy prices stone with the stone pickaxe; with the hook unset upstream's fastest-tool pricing gives the
     * iron one, and the two prices differ.
     */
    @GameTest(environment = "minecraftai-gametest:baritone_engine_tool_game_tests_the_cost_model_snapshots_lazily_and_the_host_hook_prices_breaks", maxTicks = 100)
    public void theCostModelSnapshotsLazilyAndTheHostHookPricesBreaks(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 6, 14, 5);
        AIPlayerEntity bot = arena.spawnOnBaritone("BeToolLazy", arena.cell(-7, 0, 0));
        bot.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        bot.getInventory().setItem(1, new ItemStack(Items.STONE_PICKAXE));
        bot.getInventory().setSelectedSlot(0);
        baritone.api.IBaritone bt = BaritoneRegistry.INSTANCE.get(bot);
        BlockState stone = Blocks.STONE.defaultBlockState();
        baritone.api.utils.HostEnvironment.ToolPolicy installed = baritone.api.utils.HostEnvironment.toolPolicy();
        arena.require(installed != null, "the mod's tool policy is not installed");
        int[] snapshots = {0};
        try {
            baritone.api.utils.HostEnvironment.setToolPolicy(new baritone.api.utils.HostEnvironment.ToolPolicy() {
                @Override
                public Object snapshot(net.minecraft.world.entity.player.Player player) {
                    snapshots[0]++;
                    return installed.snapshot(player);
                }

                @Override
                public ItemStack toolFor(Object snapshot, BlockState state) {
                    return installed.toolFor(snapshot, state);
                }
            });
            var forThisThread = new baritone.pathing.movement.CalculationContext(bt, false);
            arena.require(snapshots[0] == 0, "a cost model for the game thread copied the inventory before any break was priced: " + snapshots[0]);
            double hooked = forThisThread.toolSet.getStrVsBlock(stone);
            arena.require(snapshots[0] == 1, "pricing a break took " + snapshots[0] + " snapshots instead of one");
            forThisThread.toolSet.getStrVsBlock(Blocks.DIRT.defaultBlockState());
            arena.require(snapshots[0] == 1, "a second price took another snapshot: " + snapshots[0]);
            new baritone.pathing.movement.CalculationContext(bt, true);
            arena.require(snapshots[0] == 2, "a cost model for another thread must snapshot when it is created: " + snapshots[0]);
            baritone.api.utils.HostEnvironment.setToolPolicy(null);
            double upstream = new baritone.pathing.movement.CalculationContext(bt, false).toolSet.getStrVsBlock(stone);
            arena.require(hooked != upstream, "the host hook did not change the price of a break: " + hooked + " both ways");
            arena.require(hooked < upstream, "getStrVsBlock is a speed: the cheaper stone pickaxe must be priced slower than the fastest (iron) tool: hooked " + hooked + ", upstream " + upstream);
        } finally {
            baritone.api.utils.HostEnvironment.setToolPolicy(installed);
        }
        arena.finish(bot);
    }

    /**
     * A sword is not a mining tool. The tool policy never picks it for leaves or cobweb even when it is the stack in hand and the
     * only other things are a pickaxe and empty slots, and a Baritone-driven route through a leaf wall breaks every leaf with
     * something else while the sword keeps all its durability.
     */
    @GameTest(environment = "minecraftai-gametest:baritone_engine_tool_game_tests_a_sword_in_the_hotbar_is_never_used_on_leaves_or_cobweb", maxTicks = 900)
    public void aSwordInTheHotbarIsNeverUsedOnLeavesOrCobweb(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 6, 14, 5);
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = 0; dy < BaritoneEngineArena.CEILING; dy++) {
                    arena.world.setBlock(arena.cell(dx, dy, dz), leaves, Block.UPDATE_ALL);
                }
            }
        }
        AIPlayerEntity bot = arena.spawnOnBaritone("BeToolSword", arena.cell(-7, 0, 0));
        bot.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        bot.getInventory().setItem(1, new ItemStack(Items.IRON_PICKAXE));
        bot.getInventory().setSelectedSlot(0);

        // The policy itself, with the sword as the stack in hand.
        for (BlockState state : new BlockState[] {leaves, Blocks.COBWEB.defaultBlockState()}) {
            bot.getInventory().setSelectedSlot(0);
            ToolSelector.equipBestTool(bot, state, false);
            ItemStack held = bot.getMainHandItem();
            arena.require(!held.is(Items.DIAMOND_SWORD), "the sword was left in hand for " + state.getBlock() + ", held " + held);
        }
        bot.getInventory().setSelectedSlot(0);

        BlockPos goal = arena.cell(6, 0, 0);
        ActionPack pack = bot.getActionPack();
        ActionResult started = pack.startPathTo(goal);
        arena.require(started.isInProgress(), "the route was not accepted: " + started.status() + " " + started.reason());
        int[] tick = {0};
        context.failIfEver(() -> {
            int now = ++tick[0];
            arena.require(now < 850, "the route never ended: " + bot.position());
            if (pack.hasBaritoneRoute()) {
                return;
            }
            NavOutcome outcome = pack.lastRouteOutcome();
            arena.require(outcome != null && outcome.status() == NavOutcome.Status.SUCCESS, "the route did not succeed: " + outcome);
            List<BaritoneEdits.Edit> breaks = BaritoneEdits.of(bot.getUUID(), BaritoneEdits.Kind.BREAK);
            arena.require(breaks.size() >= 4, "too few leaves were broken: " + breaks.size());
            for (BaritoneEdits.Edit edit : breaks) {
                arena.require(edit.block().equals("minecraft:oak_leaves"), "broke " + edit.block());
                arena.require(!edit.tool().contains("sword"), "leaves were broken with " + edit.tool());
            }
            ItemStack sword = bot.getInventory().getItem(0);
            arena.require(sword.is(Items.DIAMOND_SWORD) && sword.getDamageValue() == 0, "the sword lost durability: " + sword.getDamageValue());
            arena.finish(bot);
        });
    }
}

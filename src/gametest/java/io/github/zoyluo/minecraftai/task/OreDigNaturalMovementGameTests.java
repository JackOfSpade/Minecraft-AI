package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.TeleportAudit;
import io.github.zoyluo.minecraftai.gametest.BotFixtureMoves;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.MiningBudget;
import io.github.zoyluo.minecraftai.mining.MiningCursor;
import io.github.zoyluo.minecraftai.navigation.NavEngine;
import io.github.zoyluo.minecraftai.navigation.NavEngineSelector;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * No micro-teleports in OreDigTask, HarvestCore and MiningBarricadeTask (R5): the stair descent, the one-block lower step, the hop up a
 * ledge, the retreat out of a reoccupied cell, the drop into a pickup cell and the barricade retreat are walked steps
 * ({@link WalkedStep}) whose landing is published when it is verified, never in the tick that starts them. Every test runs in the
 * default strict-survival profile, resets {@link TeleportAudit} for its bot after the fixture is built and asserts
 * {@code TeleportAudit.corrections(bot) == 0} on every tick. Each test has its own world layer and its own test environment.
 */
public final class OreDigNaturalMovementGameTests {
    private static final int BASE_Y = 130;
    private static final int LAYER_STEP = 24;

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
            throw new IllegalStateException(message);
        }
    }

    /**
     * A solid stone volume fourteen blocks deep with open air above it and a light grid (no natural spawns), the room a miner starts
     * in. Own layer per test.
     */
    private static BlockPos buildArena(GameTestHelper context, int layer) {
        ServerLevel world = context.getLevel();
        BlockPos raw = context.absolutePos(new BlockPos(8, BASE_Y + LAYER_STEP * layer, 14));
        // A scene stays inside one chunk column: the test structures sit at arbitrary positions, and a drop that pops into the
        // neighbouring chunk of a bot placed far from every player can lie in a chunk that does not tick its entities (the item
        // then hangs in the air at its spawn velocity and is never picked up). Chunk-local x and z of the start: 4 and 8.
        BlockPos feet = raw.offset(4 - Math.floorMod(raw.getX(), 16), 0, 8 - Math.floorMod(raw.getZ(), 16));
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -10; dz <= 3; dz++) {
                for (int dy = -14; dy <= 6; dy++) {
                    Block block = dy < 0 ? Blocks.STONE : Blocks.AIR;
                    world.setBlock(feet.offset(dx, dy, dz), block.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        for (int dx = -6; dx <= 6; dx += 4) {
            for (int dz = -8; dz <= 2; dz += 4) {
                world.setBlock(feet.offset(dx, 5, dz), Blocks.LIGHT.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        return feet;
    }

    private static AIPlayerEntity spawn(GameTestHelper context, String name, BlockPos feet, float yaw) {
        ServerLevel world = context.getLevel();
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet), yaw, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        NavEngineSelector.setBotEngine(bot.getUUID(), NavEngine.BARITONE);
        BotFixtureMoves.place(bot, feet);
        bot.setOnGround(true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.getFoodData().setSaturation(20.0F);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 3));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, MiningBudget.EMERGENCY_STONE_LIKE + 1));
        Standability.clearCache();
        TeleportAudit.reset(bot);
        return bot;
    }

    private static void despawn(GameTestHelper context, AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
        bot.getActionPack().clearPace();
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
        NavEngineSelector.clearBotEngine(bot.getUUID());
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), bot.getGameProfile().name());
    }

    private static String audit(AIPlayerEntity bot) {
        return "corrections=" + TeleportAudit.corrections(bot) + " last=" + TeleportAudit.lastCaller(bot);
    }

    private static void requireUntouched(GameTestHelper context, AIPlayerEntity bot, String what) {
        require(context, TeleportAudit.corrections(bot) == 0, what + ": the bot was teleported (" + audit(bot) + ")");
        require(context, bot.getHealth() >= bot.getMaxHealth() - 0.01F,
                what + ": the bot took damage (health " + bot.getHealth() + ")");
    }

    /** The item entities around the bot (for a failure message). */
    private static String items(AIPlayerEntity bot) {
        StringBuilder text = new StringBuilder("[");
        for (ItemEntity item : bot.level().getEntitiesOfClass(ItemEntity.class, bot.getBoundingBox().inflate(6.0D))) {
            text.append(item.getItem().getItem()).append('@').append(item.position()).append(' ');
        }
        return text.append(']').toString();
    }

    private static String encode(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** An open cursor at {@code face}, heading north with a bounded leg: the checkpoint every ore approach here restarts from. */
    private static Map<String, String> openCheckpoint(BlockPos face, int targetCount, Set<Block> ores) {
        Map<String, String> encoded = new LinkedHashMap<>(new OreDigCheckpoint(
                4, targetCount, true, 0, 0, false, 40, 0, 0,
                MiningCursor.initial(face, 48),
                OreDigTask.oreFingerprint(ores),
                0, 0, null, null, null, null, -1, -1, -1, null, -1).encode());
        encoded.put("direction", "0");
        encoded.put("steps_left", "17");
        return encoded;
    }

    /**
     * The ledge scene of the first two tests: the miner stands at {@code start} on the stone floor of a room, one lower cell
     * ({@code landing}) lies east of it, and two adjacent diamond ores sit in the floor beyond it, one and two cells further east
     * of the landing's neighbour. The four cells around the cell under the first ore are bedrock, so the only way to the vein is
     * over the lower step and then east through the floor.
     */
    private static BlockPos buildLedgeScene(GameTestHelper context, BlockPos start) {
        ServerLevel world = context.getLevel();
        BlockPos landing = start.east().below();
        world.setBlock(landing, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos ore = start.east(3).below();
        world.setBlock(ore, Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(ore.east(), Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
        // The one-lower upper-lateral work pose west of this ore is a legal route in general,
        // but this fixture specifically exercises the controlled physical descent. Remove only
        // that pose's support; the bedrock one block below remains the lower corridor's floor.
        world.setBlock(ore.west(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // A broken ore's drop pops up about half a block: walls around the first ore's open top and a roof over the second keep it
        // in its cell or the miner's tunnel, where a walking bot picks it up (a drop that lands on top of a neighbouring block is
        // a pickup scene of its own, covered by the pickup tests).
        for (BlockPos wall : new BlockPos[]{ore.north().above(), ore.south().above(), ore.east().above()}) {
            world.setBlock(wall, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        for (net.minecraft.core.Direction direction : new net.minecraft.core.Direction[]{
                net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.EAST,
                net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.WEST}) {
            world.setBlock(ore.below().relative(direction), Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
        }
        Standability.clearCache();
        return landing;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // A short branch: one lower step by walking, then the vein
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * An OreDigTask reaches a two-ore vein over a one-block lower step (a cave ledge) and collects both diamonds. The descent is a
     * walked step off the ledge whose landing is published when verified: the bot is never teleported and takes no damage.
     */


    // ---------------------------------------------------------------------------------------------------------------
    // The retreat before a barricade is a walk
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A MiningBarricadeTask retreats one block back down a two-high tunnel and seals the cell the miner stood in: the retreat is a
     * walked step, the barricade is placed only after arrival and the bot is never teleported.
     */
    @GameTest(environment = "minecraftai-gametest:ore_dig_natural_movement_game_tests_barricade_retreat_walks", maxTicks = 600)
    public void barricadeRetreatWalks(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos room = buildArena(context, 5);
        BlockPos face = room.below(1);
        // A tunnel two high, north-south, cut into the stone: face at the north end, the retreat cell one block south of it.
        for (int dz = -6; dz <= 3; dz++) {
            world.setBlock(face.offset(0, 0, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(face.offset(0, 1, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        BlockPos retreat = face.south();
        AIPlayerEntity bot = spawn(context, "OreDigBarricadeGT", face, 180.0F);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 8));
        MiningBarricadeTask task = new MiningBarricadeTask(retreat, face);
        task.start(bot);
        int[] ticks = {0};
        boolean[] walked = {false};
        context.onEachTick(() -> {
            ticks[0]++;
            requireUntouched(context, bot, "barricade retreat, tick " + ticks[0] + " at " + bot.blockPosition().toShortString());
            if (!bot.getActionPack().stepIdle()) {
                walked[0] = true;
            }
            require(context, task.state() == TaskState.RUNNING || task.state() == TaskState.COMPLETED,
                    "the barricade ended as " + task.state() + ": " + task.failureReason());
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
                return;
            }
            require(context, walked[0], "the retreat was not a walked step");
            require(context, bot.blockPosition().equals(retreat), "the bot ended away from its retreat: " + bot.blockPosition().toShortString());
            require(context, !world.getBlockState(face).isAir() && !world.getBlockState(face.above()).isAir(),
                    "the tunnel was not sealed");
            despawn(context, bot);
            context.succeed();
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Harvest: the drop into the cell of a pickup
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * HarvestCore drops the bot through the open shaft a broken ore left using an observed, exact surface route: the bot is
     * never teleported, physically enters the lower cell, and collects the item lying there.
     */
    @GameTest(environment = "minecraftai-gametest:ore_dig_natural_movement_game_tests_harvest_shaft_descent_walks", maxTicks = 400)
    public void harvestShaftDescentWalks(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos start = buildArena(context, 6);
        AIPlayerEntity bot = spawn(context, "HarvestDropGT", start, 0.0F);
        // A one-cell shaft leaves an item at y + 0.1 inside vanilla's pickup overlap
        // from the starting cell. Keep an open intermediate cell and put the supported
        // drop two cells below, so ordinary collision requires a real descent first.
        BlockPos shaft = start.below();
        BlockPos hole = shaft.below();
        world.setBlock(shaft, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(hole, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();
        ItemEntity drop = new ItemEntity(world, hole.getX() + 0.5D, hole.getY() + 0.1D, hole.getZ() + 0.5D, new ItemStack(Items.DIAMOND));
        drop.setDeltaMovement(Vec3.ZERO);
        require(context, world.addFreshEntity(drop), "failed to spawn the diamond in the hole");
        int[] ticks = {0};
        boolean[] descended = {false};
        context.onEachTick(() -> {
            ticks[0]++;
            requireUntouched(context, bot, "harvest descent, tick " + ticks[0] + " at " + bot.blockPosition().toShortString());
            if (bot.blockPosition().getY() < start.getY()) {
                descended[0] = true;
            }
            if (InventoryAction.countItem(bot, Items.DIAMOND) >= 1) {
                require(context, descended[0], "the item was collected before the bot physically descended");
                require(context, bot.blockPosition().equals(hole), "the bot collected the item away from its cell: " + bot.blockPosition().toShortString());
                despawn(context, bot);
                context.succeed();
                return;
            }
            require(context, ticks[0] < 350, "the bot never collected the item: at " + bot.position());
            if (bot.getActionPack().stepIdle() && bot.getActionPack().isPathExecutorIdle()) {
                // The item and the body settle on the server's physics tick after this fixture
                // removes the floor. Admit the observed entity only from that factual pose; the
                // old tick-zero assertion raced both observations and falsely treated a refusal
                // to inspect an unsettled cell as a movement failure.
                HarvestCore.approachDropPhysically(bot, drop);
            }
        });
    }
}

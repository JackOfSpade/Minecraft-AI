package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Live proofs for "follow me while I am on a boat" (live session 20260928-232208): the bot must
 * walk to the water, get a boat (nearby empty boat, then inventory boat, then craft one), board it,
 * actually drive it (an AIPlayerEntity boat only moves because the two AIPlayerControlledBoat
 * mixins make the server simulate it), recover when the boat is wedged, and never take the boat its
 * target is riding.  The target is another bot riding a boat on a lake; the follower starts on land
 * roughly ten blocks from the shore.
 *
 * <p>Fixture geometry (relative to feet level F): land for x in [0,14] whose top block is F-1,
 * a 3-deep lake for x in [15,44] whose surface block is also F-1 (the ordinary vanilla flush bank:
 * the bot stands one cell above the water block that touches its floor block).</p>
 */
public final class BoatFollowGameTests {
    private static final int LAND_MAX_X = 14;
    private static final int LAKE_MAX_X = 44;
    private static final int MAX_Z = 24;
    private static final int FEET_Z = 12;

    @GameTest(environment = "minecraftai-gametest:boat_follow_game_tests_ai_player_boat_moves_when_steered_on_open_water", maxTicks = 200)
    public void aiPlayerBoatMovesWhenSteeredOnOpenWater(TestContext context) {
        BlockPos feet = buildLake(context);
        ServerWorld world = context.getWorld();
        AIPlayerEntity bot = spawnBot(world, "BoatSteerGT", feet.add(20, 0, FEET_Z));
        AbstractBoatEntity boat = placeBoat(world, feet.add(20, 0, FEET_Z));
        boarded(bot, boat);
        Vec3d start = boat.getEntityPos();
        AtomicInteger ticks = new AtomicInteger();
        context.runAtEveryTick(() -> {
            require(context, bot.getVehicle() == boat, "bot left its boat while being steered");
            BoatSupport.steerToward(boat, start.x + 25.0D, start.z, 1.0D, 82.0D);
            if (ticks.incrementAndGet() < 80) {
                return;
            }
            double moved = Math.hypot(boat.getX() - start.x, boat.getZ() - start.z);
            require(context, moved >= 4.0D,
                    "boat driven by an AIPlayerEntity did not move: moved=" + moved
                            + " velocity=" + boat.getVelocity()
                            + " logicalSide=" + boat.isLogicalSideForUpdatingMovement()
                            + " controller=" + boat.getControllingPassenger()
                            + " inWater=" + boat.isTouchingWater()
                            + " alive=" + boat.isAlive() + " age=" + boat.age
                            + " tickEntityAt=" + world.shouldTickEntityAt(boat.getBlockPos())
                            + " tickTestAt=" + world.shouldTickTestAt(boat.getChunkPos())
                            + " tickChunkAt=" + world.shouldTickChunkAt(boat.getChunkPos())
                            + " blockAt=" + world.getBlockState(boat.getBlockPos()).getBlock()
                            + " blockBelow=" + world.getBlockState(boat.getBlockPos().down()).getBlock()
                            + " blockBelow2=" + world.getBlockState(boat.getBlockPos().down(2)).getBlock()
                            + " feetY=" + feet.getY()
                            + " botAge=" + bot.age + " inputs=" + boat.isPaddleMoving(0)
                            + " pos=" + boat.getEntityPos() + " yaw=" + boat.getYaw());
            despawnAndComplete(context, bot);
        });
    }

    @GameTest(environment = "minecraftai-gametest:boat_follow_game_tests_walks_to_shore_launches_inventory_boat_and_follows_boating_target", maxTicks = 1500)
    public void walksToShoreLaunchesInventoryBoatAndFollowsBoatingTarget(TestContext context) {
        Scenario scenario = scenario(context, "InvBoat");
        InventoryAction.giveItem(scenario.bot(), new ItemStack(Items.OAK_BOAT, 1));
        runFollow(context, scenario, null, null, () -> { });
    }

    @GameTest(environment = "minecraftai-gametest:boat_follow_game_tests_crafts_boat_from_planks_then_launches_and_follows_boating_target", maxTicks = 2400)
    public void craftsBoatFromPlanksThenLaunchesAndFollowsBoatingTarget(TestContext context) {
        Scenario scenario = scenario(context, "CraftBoat");
        InventoryAction.giveItem(scenario.bot(), new ItemStack(Items.OAK_PLANKS, 12));
        runFollow(context, scenario, null, null, () -> require(context,
                InventoryAction.countItem(scenario.bot(), Items.OAK_PLANKS) < 12,
                "the bot followed by boat without spending any planks on crafting one"));
    }

    @GameTest(environment = "minecraftai-gametest:boat_follow_game_tests_boards_nearby_placed_empty_boat_instead_of_launching_and_follows", maxTicks = 1500)
    public void boardsNearbyPlacedEmptyBoatInsteadOfLaunchingAndFollows(TestContext context) {
        Scenario scenario = scenario(context, "PlacedBoat");
        // Empty boat on the lake close to the shore; the bot has no boat item and no planks, so the
        // only way to follow is to board this one.
        AbstractBoatEntity placed = placeBoat(context.getWorld(),
                scenario.feet().add(LAND_MAX_X + 3, 0, FEET_Z + 2));
        runFollow(context, scenario, placed, null, () -> { });
    }

    @GameTest(environment = "minecraftai-gametest:boat_follow_game_tests_never_takes_the_boat_its_target_is_riding", maxTicks = 600)
    public void neverTakesTheBoatItsTargetIsRiding(TestContext context) {
        Scenario scenario = scenario(context, "OwnBoat");
        AIPlayerEntity bot = scenario.bot();
        // Bot on the shore next to the target's boat; nothing else to board and nothing to build.
        bot.teleport(context.getWorld(), scenario.feet().getX() + LAND_MAX_X + 0.5D,
                scenario.feet().getY(), scenario.feet().getZ() + 20.5D, Set.of(), 0.0F, 0.0F, true);
        scenario.targetBoat().setPosition(
                scenario.feet().getX() + LAND_MAX_X + 3.5D, scenario.targetBoat().getY(),
                scenario.feet().getZ() + 20.5D);
        require(context, BoatSupport.nearbyEmptyBoat(bot).isEmpty(),
                "an occupied boat must never be offered as an empty boat");
        FollowTask follow = new FollowTask(scenario.targetName());
        TaskManager.INSTANCE.assign(bot, follow,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_boat_follow_own_boat"));
        AtomicInteger ticks = new AtomicInteger();
        context.runAtEveryTick(() -> {
            require(context, bot.getVehicle() != scenario.targetBoat(),
                    "the follower took the boat its target is riding");
            require(context, scenario.targetBoat().getPassengerList().size() == 1,
                    "the target's boat gained a passenger: "
                            + scenario.targetBoat().getPassengerList().size());
            if (ticks.incrementAndGet() >= 400) {
                despawnAndComplete(context, bot, scenario.target());
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:boat_follow_game_tests_beached_boat_watchdog_abandons_it_and_relaunches_own_boat", maxTicks = 2400)
    public void beachedBoatWatchdogAbandonsItAndRelaunchesOwnBoat(TestContext context) {
        Scenario scenario = scenario(context, "Beached");
        BlockPos feet = scenario.feet();
        ServerWorld world = context.getWorld();
        InventoryAction.giveItem(scenario.bot(), new ItemStack(Items.OAK_BOAT, 1));
        // A boat sitting on dry land in front of the bot, with a one-high stone wall right in the
        // line to the lake: boarding works, steering east never gets anywhere (a hull cannot climb
        // a block), while a walking bot simply steps over it once it has abandoned the boat.
        BlockState stone = Blocks.STONE.getDefaultState();
        for (int z = 4; z <= 20; z++) {
            world.setBlockState(feet.add(10, 0, z), stone, Block.NOTIFY_ALL);
        }
        AbstractBoatEntity beached = placeBoat(world, feet.add(8, 0, FEET_Z));
        beached.setPosition(feet.getX() + 8.5D, feet.getY(), feet.getZ() + FEET_Z + 0.5D);
        runFollow(context, scenario, beached, beached, () -> require(context,
                beached.getPassengerList().isEmpty(), "the wedged boat still carries the bot"));
    }

    @GameTest(environment = "minecraftai-gametest:boat_follow_game_tests_target_leaving_boat_onto_land_makes_bot_exit_at_dry_shore_and_follow_on_foot", maxTicks = 1800)
    public void targetLeavingBoatOntoLandMakesBotExitAtDryShoreAndFollowOnFoot(TestContext context) {
        Scenario scenario = scenario(context, "LeaveBoat");
        ServerWorld world = context.getWorld();
        AIPlayerEntity bot = scenario.bot();
        BlockPos feet = scenario.feet();
        AbstractBoatEntity botBoat = placeBoat(world, feet.add(22, 0, FEET_Z));
        boarded(bot, botBoat);
        FollowTask follow = new FollowTask(scenario.targetName());
        TaskManager.INSTANCE.assign(bot, follow,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_boat_follow_leave_boat"));
        AtomicInteger stage = new AtomicInteger();
        context.runAtEveryTick(() -> {
            if (follow.state() == TaskState.FAILED || follow.state() == TaskState.CANCELLED) {
                context.throwGameTestException(Text.of("follow ended early: " + follow.state()
                        + " reason=" + follow.failureReason() + " botPos=" + bot.getEntityPos()));
                return;
            }
            double toTargetBoat = Math.hypot(botBoat.getX() - scenario.targetBoat().getX(),
                    botBoat.getZ() - scenario.targetBoat().getZ());
            if (stage.get() == 0) {
                if (bot.getVehicle() == botBoat && toTargetBoat <= 9.0D) {
                    // Bot has sailed up to the boating target; now the target steps out onto the bank.
                    scenario.target().stopRiding();
                    scenario.target().teleport(world, feet.getX() + 6.5D, feet.getY(),
                            feet.getZ() + FEET_Z + 0.5D, Set.of(), 0.0F, 0.0F, true);
                    stage.set(1);
                }
                return;
            }
            double toTarget = Math.hypot(bot.getX() - scenario.target().getX(),
                    bot.getZ() - scenario.target().getZ());
            if (bot.getVehicle() == null && bot.getX() <= feet.getX() + LAND_MAX_X + 1.0D
                    && toTarget <= 6.0D) {
                TaskManager.INSTANCE.abort(bot);
                despawnAndComplete(context, bot, scenario.target());
            }
        });
    }

    // ---- scenario plumbing -------------------------------------------------------------------

    private record Scenario(BlockPos feet, AIPlayerEntity bot, AIPlayerEntity target,
            AbstractBoatEntity targetBoat, String botName, String targetName) {
    }

    private static Scenario scenario(TestContext context, String suffix) {
        BlockPos feet = buildLake(context);
        ServerWorld world = context.getWorld();
        String botName = "Fol" + suffix;
        String targetName = "Tgt" + suffix;
        AIPlayerEntity bot = spawnBot(world, botName, feet.add(4, 0, FEET_Z));
        BlockPos targetPos = feet.add(32, 0, FEET_Z);
        AIPlayerEntity target = spawnBot(world, targetName, targetPos);
        AbstractBoatEntity targetBoat = placeBoat(world, targetPos);
        boarded(target, targetBoat);
        return new Scenario(feet, bot, target, targetBoat, botName, targetName);
    }

    /**
     * Assigns an ordinary FollowTask and waits for the bot to end up in a boat, on the water, close
     * to the boating target -- never in the target's own boat.
     *
     * @param mustBoardFirst when non-null, the first boat the bot boards must be this one
     * @param mustNotEndIn when non-null, a boat the bot must have left/abandoned by the end
     * @param onSuccess extra final assertions, run just before the test completes
     */
    private static void runFollow(TestContext context, Scenario scenario,
            AbstractBoatEntity mustBoardFirst, AbstractBoatEntity mustNotEndIn, Runnable onSuccess) {
        AIPlayerEntity bot = scenario.bot();
        FollowTask follow = new FollowTask(scenario.targetName());
        TaskManager.INSTANCE.assign(bot, follow,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_boat_follow"));
        AtomicReference<Vec3d> firstBoatPos = new AtomicReference<>();
        AtomicReference<AbstractBoatEntity> firstBoat = new AtomicReference<>();
        context.runAtEveryTick(() -> {
            if (follow.state() == TaskState.FAILED || follow.state() == TaskState.CANCELLED) {
                context.throwGameTestException(Text.of("follow ended early: " + follow.state()
                        + " reason=" + follow.failureReason() + " botPos=" + bot.getEntityPos()
                        + " vehicle=" + bot.getVehicle()
                        + " expectedBoat=" + (mustBoardFirst == null ? "-" : mustBoardFirst.getEntityPos()
                                + " passengers=" + mustBoardFirst.getPassengerList().size()
                                + " alive=" + mustBoardFirst.isAlive())));
                return;
            }
            require(context, bot.getVehicle() != scenario.targetBoat(),
                    "the follower took the boat its target is riding");
            if (!(bot.getVehicle() instanceof AbstractBoatEntity boat)) {
                return;
            }
            if (firstBoat.get() == null) {
                firstBoat.set(boat);
                firstBoatPos.set(boat.getEntityPos());
                require(context, mustBoardFirst == null || boat == mustBoardFirst,
                        "bot boarded some other boat instead of the expected one first");
            }
            boolean onWater = boat.getX() >= scenario.feet().getX() + LAND_MAX_X + 1;
            double toTarget = Math.hypot(boat.getX() - scenario.targetBoat().getX(),
                    boat.getZ() - scenario.targetBoat().getZ());
            if (onWater && boat != mustNotEndIn && toTarget <= 9.0D) {
                double travelled = Math.hypot(boat.getX() - firstBoatPos.get().x,
                        boat.getZ() - firstBoatPos.get().z);
                require(context, travelled >= 1.0D || firstBoat.get() != boat,
                        "the bot's boat never moved: travelled=" + travelled);
                require(context, !boat.getPassengerList().contains(scenario.target()),
                        "bot and target share a boat");
                onSuccess.run();
                TaskManager.INSTANCE.abort(bot);
                despawnAndComplete(context, bot, scenario.target());
            }
        });
    }

    // ---- world / entity helpers --------------------------------------------------------------

    /** Builds the lake fixture and returns the feet-level origin cell (x=0, z=0). */
    private static BlockPos buildLake(TestContext context) {
        ServerWorld world = context.getWorld();
        world.setTimeOfDay(1000L);
        BlockPos feet = context.getAbsolutePos(new BlockPos(0, 4, 0));
        BlockState stone = Blocks.STONE.getDefaultState();
        BlockState air = Blocks.AIR.getDefaultState();
        BlockState water = Blocks.WATER.getDefaultState();
        // The gametest box is one chunk-sized structure; the lake is wider than that and boats
        // only tick (and only float/drive) in entity-ticking chunks, so force-load the whole area.
        forceLakeChunks(world, feet, true);
        // Leftover boats from earlier tests in the same server would be offered as "empty boats".
        world.getEntitiesByClass(AbstractBoatEntity.class,
                net.minecraft.util.math.Box.enclosing(feet.add(-30, -10, -30), feet.add(LAKE_MAX_X + 30, 10, MAX_Z + 30)),
                boat -> true).forEach(net.minecraft.entity.Entity::discard);
        for (int x = -1; x <= LAKE_MAX_X + 1; x++) {
            for (int z = -1; z <= MAX_Z + 1; z++) {
                boolean frame = x == -1 || z == -1 || x == LAKE_MAX_X + 1 || z == MAX_Z + 1;
                for (int y = -5; y <= 7; y++) {
                    BlockState state;
                    if (frame) {
                        state = y <= 2 ? stone : air;
                    } else if (y <= -4) {
                        state = stone;
                    } else if (y <= -1) {
                        state = x <= LAND_MAX_X ? stone : water;
                    } else {
                        state = air;
                    }
                    world.setBlockState(feet.add(x, y, z), state, Block.NOTIFY_ALL);
                }
            }
        }
        return feet;
    }

    private static void forceLakeChunks(ServerWorld world, BlockPos feet, boolean forced) {
        for (int cx = (feet.getX() - 1) >> 4; cx <= (feet.getX() + LAKE_MAX_X + 1) >> 4; cx++) {
            for (int cz = (feet.getZ() - 1) >> 4; cz <= (feet.getZ() + MAX_Z + 1) >> 4; cz++) {
                world.setChunkForced(cx, cz, forced);
            }
        }
    }

    private static AbstractBoatEntity placeBoat(ServerWorld world, BlockPos cell) {
        BoatEntity boat = EntityType.OAK_BOAT.create(world, SpawnReason.COMMAND);
        if (boat == null) {
            throw new IllegalStateException("failed to create boat");
        }
        boat.refreshPositionAndAngles(cell.getX() + 0.5D, cell.getY() - 0.1D, cell.getZ() + 0.5D, 0.0F, 0.0F);
        world.spawnEntity(boat);
        return boat;
    }

    private static void boarded(AIPlayerEntity rider, AbstractBoatEntity boat) {
        rider.teleport((ServerWorld) boat.getEntityWorld(), boat.getX(), boat.getY(), boat.getZ(),
                Set.of(), 0.0F, 0.0F, true);
        if (!rider.startRiding(boat, true, false)) {
            throw new IllegalStateException(rider.getGameProfile().name() + " could not board the fixture boat");
        }
    }

    private static AIPlayerEntity spawnBot(ServerWorld world, String name, BlockPos feet) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(feet),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        return bot;
    }

    private static void despawnAndComplete(TestContext context, AIPlayerEntity... bots) {
        var server = bots[0].getEntityWorld().getServer();
        for (AIPlayerEntity bot : bots) {
            DangerWatcher.INSTANCE.clear(bot);
            AIPlayerManager.INSTANCE.despawn(server, bot.getGameProfile().name());
        }
        context.complete();
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(Text.of(message));
        }
    }
}

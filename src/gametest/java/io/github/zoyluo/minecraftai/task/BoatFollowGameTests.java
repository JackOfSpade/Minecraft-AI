package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.gametest.GameTestCleanup;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.network.PlayerKind;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
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
    public void aiPlayerBoatMovesWhenSteeredOnOpenWater(GameTestHelper context) {
        BlockPos feet = buildLake(context);
        ServerLevel world = context.getLevel();
        AIPlayerEntity bot = spawnBot(world, "BoatSteerGT", feet.offset(20, 0, FEET_Z));
        AbstractBoat boat = placeBoat(world, feet.offset(20, 0, FEET_Z));
        boarded(bot, boat);
        Vec3 start = boat.position();
        AtomicInteger ticks = new AtomicInteger();
        context.failIfEver(() -> {
            require(context, bot.getVehicle() == boat, "bot left its boat while being steered");
            BoatSupport.steerToward(boat, start.x + 25.0D, start.z, 1.0D, 82.0D);
            if (ticks.incrementAndGet() < 80) {
                return;
            }
            double moved = Math.hypot(boat.getX() - start.x, boat.getZ() - start.z);
            require(context, moved >= 4.0D,
                    "boat driven by an AIPlayerEntity did not move: moved=" + moved
                            + " velocity=" + boat.getDeltaMovement()
                            + " logicalSide=" + boat.isLocalInstanceAuthoritative()
                            + " controller=" + boat.getControllingPassenger());
            despawnAndComplete(context, bot);
        });
    }

    @GameTest(environment = "minecraftai-gametest:boat_follow_game_tests_walks_to_shore_launches_inventory_boat_and_follows_boating_target", maxTicks = 1500)
    public void walksToShoreLaunchesInventoryBoatAndFollowsBoatingTarget(GameTestHelper context) {
        Scenario scenario = scenario(context, "InvBoat");
        InventoryAction.giveItem(scenario.bot(), new ItemStack(Items.OAK_BOAT, 1));
        runFollow(context, scenario, null, null, () -> { });
    }

    @GameTest(environment = "minecraftai-gametest:boat_follow_game_tests_crafts_boat_from_planks_then_launches_and_follows_boating_target", maxTicks = 2400)
    public void craftsBoatFromPlanksThenLaunchesAndFollowsBoatingTarget(GameTestHelper context) {
        Scenario scenario = scenario(context, "CraftBoat");
        InventoryAction.giveItem(scenario.bot(), new ItemStack(Items.OAK_PLANKS, 12));
        runFollow(context, scenario, null, null, () -> require(context,
                InventoryAction.countItem(scenario.bot(), Items.OAK_PLANKS) < 12,
                "the bot followed by boat without spending any planks on crafting one"));
    }

    @GameTest(environment = "minecraftai-gametest:boat_follow_game_tests_boards_nearby_placed_empty_boat_instead_of_launching_and_follows", maxTicks = 1500)
    public void boardsNearbyPlacedEmptyBoatInsteadOfLaunchingAndFollows(GameTestHelper context) {
        Scenario scenario = scenario(context, "PlacedBoat");
        // Empty boat on the lake close to the shore; the bot has no boat item and no planks, so the
        // only way to follow is to board this one.
        AbstractBoat placed = placeBoat(context.getLevel(),
                scenario.feet().offset(LAND_MAX_X + 3, 0, FEET_Z + 2));
        runFollow(context, scenario, placed, null, () -> { });
    }

    @GameTest(environment = "minecraftai-gametest:boat_follow_game_tests_never_takes_the_boat_its_target_is_riding", maxTicks = 600)
    public void neverTakesTheBoatItsTargetIsRiding(GameTestHelper context) {
        Scenario scenario = scenario(context, "OwnBoat");
        AIPlayerEntity bot = scenario.bot();
        // Bot on the shore next to the target's boat; nothing else to board and nothing to build.
        bot.teleportTo(context.getLevel(), scenario.feet().getX() + LAND_MAX_X + 0.5D,
                scenario.feet().getY(), scenario.feet().getZ() + 20.5D, Set.of(), 0.0F, 0.0F, true);
        scenario.targetBoat().setPos(
                scenario.feet().getX() + LAND_MAX_X + 3.5D, scenario.targetBoat().getY(),
                scenario.feet().getZ() + 20.5D);
        require(context, BoatSupport.nearbyEmptyBoat(bot).isEmpty(),
                "an occupied boat must never be offered as an empty boat");
        FollowTask follow = new FollowTask(scenario.targetName());
        TaskManager.INSTANCE.assign(bot, follow,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_boat_follow_own_boat"));
        AtomicInteger ticks = new AtomicInteger();
        context.failIfEver(() -> {
            require(context, bot.getVehicle() != scenario.targetBoat(),
                    "the follower took the boat its target is riding");
            require(context, scenario.targetBoat().getPassengers().size() == 1,
                    "the target's boat gained a passenger: "
                            + scenario.targetBoat().getPassengers().size());
            if (ticks.incrementAndGet() >= 400) {
                despawnAndComplete(context, bot, scenario.target());
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:boat_follow_game_tests_beached_boat_watchdog_abandons_it_and_relaunches_own_boat", maxTicks = 2400)
    public void beachedBoatWatchdogAbandonsItAndRelaunchesOwnBoat(GameTestHelper context) {
        Scenario scenario = scenario(context, "Beached");
        BlockPos feet = scenario.feet();
        ServerLevel world = context.getLevel();
        InventoryAction.giveItem(scenario.bot(), new ItemStack(Items.OAK_BOAT, 1));
        // A boat sitting on dry land in front of the bot, with a one-high stone wall right in the
        // line to the lake: boarding works, steering east never gets anywhere (a hull cannot climb
        // a block), while a walking bot simply steps over it once it has abandoned the boat.
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int z = 4; z <= 20; z++) {
            world.setBlock(feet.offset(10, 0, z), stone, Block.UPDATE_ALL);
        }
        AbstractBoat beached = placeBoat(world, feet.offset(8, 0, FEET_Z));
        beached.setPos(feet.getX() + 8.5D, feet.getY(), feet.getZ() + FEET_Z + 0.5D);
        runFollow(context, scenario, beached, beached, () -> require(context,
                beached.getPassengers().isEmpty(), "the wedged boat still carries the bot"));
    }

    @GameTest(environment = "minecraftai-gametest:boat_follow_game_tests_target_leaving_boat_onto_land_makes_bot_exit_at_dry_shore_and_follow_on_foot", maxTicks = 1800)
    public void targetLeavingBoatOntoLandMakesBotExitAtDryShoreAndFollowOnFoot(GameTestHelper context) {
        Scenario scenario = scenario(context, "LeaveBoat");
        ServerLevel world = context.getLevel();
        AIPlayerEntity bot = scenario.bot();
        BlockPos feet = scenario.feet();
        AbstractBoat botBoat = placeBoat(world, feet.offset(22, 0, FEET_Z));
        boarded(bot, botBoat);
        FollowTask follow = new FollowTask(scenario.targetName());
        TaskManager.INSTANCE.assign(bot, follow,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_boat_follow_leave_boat"));
        AtomicInteger stage = new AtomicInteger();
        context.failIfEver(() -> {
            if (follow.state() == TaskState.FAILED || follow.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("follow ended early: " + follow.state()
                        + " reason=" + follow.failureReason() + " botPos=" + bot.position()));
                return;
            }
            double toTargetBoat = Math.hypot(botBoat.getX() - scenario.targetBoat().getX(),
                    botBoat.getZ() - scenario.targetBoat().getZ());
            if (stage.get() == 0) {
                if (bot.getVehicle() == botBoat && toTargetBoat <= 9.0D) {
                    // Bot has sailed up to the boating target; now the target steps out onto the bank.
                    scenario.target().stopRiding();
                    scenario.target().teleportTo(world, feet.getX() + 6.5D, feet.getY(),
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

    /**
     * Guard for AIPlayerControlledBoatLogicalSideMixin: it may only make the server authoritative
     * for a boat controlled by our own AIPlayerEntity.  Vanilla answers false on the server for a
     * boat whose controlling passenger is any Player (the real client simulates it and reports
     * back) and true for an empty boat or one carrying a non-player entity, so only a PLAYER
     * passenger can show whether the mixin leaks: a human (a real non-AI ServerPlayer over a
     * LocalChannel, the stand-in the sleep-vote test uses) must keep its boat client-authoritative
     * (false) while an AIPlayerEntity in an identical boat makes it server-authoritative (true).
     */
    @GameTest(environment = "minecraftai-gametest:boat_follow_game_tests_human_driven_boat_stays_client_authoritative_while_ai_driven_is_server_authoritative", maxTicks = 100)
    public void humanDrivenBoatStaysClientAuthoritativeWhileAiDrivenIsServerAuthoritative(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(3, 4, 3));
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                world.setBlock(feet.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 3; dy++) {
                    world.setBlock(feet.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        AbstractBoat emptyBoat = placeBoat(world, feet.offset(-3, 0, -3));
        AbstractBoat humanBoat = placeBoat(world, feet.offset(3, 0, -3));
        AbstractBoat aiBoat = placeBoat(world, feet.offset(0, 0, 3));
        AIPlayerEntity bot = spawnBot(world, "BoatAuthorityBotGT", feet.offset(-3, 0, 3));
        boarded(bot, aiBoat);
        ServerPlayer human = SleepVoteGameTests.connectHuman(world.getServer(), world, humanBoat.blockPosition());
        GameTestCleanup.whenFinished(context, () -> {
            if (human.connection != null) {
                human.connection.onDisconnect(new DisconnectionDetails(Component.literal("boat authority test over")));
            }
            DangerWatcher.INSTANCE.clear(bot);
            AIPlayerManager.INSTANCE.despawn(world.getServer(), "BoatAuthorityBotGT");
            emptyBoat.discard();
            humanBoat.discard();
            aiBoat.discard();
        });
        require(context, !PlayerKind.isBot(human) && !(human instanceof AIPlayerEntity),
                "fixture: the stand-in must be a human, not one of our bots");
        human.teleportTo(world, humanBoat.getX(), humanBoat.getY(), humanBoat.getZ(), Set.of(), 0.0F, 0.0F, true);
        require(context, human.startRiding(humanBoat, true, false),
                "the human could not board its boat: removed=" + humanBoat.isRemoved()
                        + " passengers=" + humanBoat.getPassengers());
        AtomicInteger ticks = new AtomicInteger();
        context.failIfEver(() -> {
            require(context, humanBoat.getControllingPassenger() == human,
                    "fixture: the human is not the controlling passenger: " + humanBoat.getControllingPassenger());
            require(context, aiBoat.getControllingPassenger() == bot,
                    "fixture: the bot is not the controlling passenger: " + aiBoat.getControllingPassenger());
            require(context, !humanBoat.isLocalInstanceAuthoritative(),
                    "the mixin leaked: a boat driven by a human player must stay client-authoritative on the server");
            require(context, aiBoat.isLocalInstanceAuthoritative(),
                    "an AIPlayerEntity-driven boat must be server-authoritative");
            require(context, emptyBoat.isLocalInstanceAuthoritative(),
                    "an empty boat keeps vanilla's server authority");
            if (ticks.incrementAndGet() >= 20) {
                context.succeed();
            }
        });
    }

    // ---- scenario plumbing -------------------------------------------------------------------

    private record Scenario(BlockPos feet, AIPlayerEntity bot, AIPlayerEntity target,
            AbstractBoat targetBoat, String botName, String targetName) {
    }

    private static Scenario scenario(GameTestHelper context, String suffix) {
        BlockPos feet = buildLake(context);
        ServerLevel world = context.getLevel();
        String botName = "Fol" + suffix;
        String targetName = "Tgt" + suffix;
        AIPlayerEntity bot = spawnBot(world, botName, feet.offset(4, 0, FEET_Z));
        BlockPos targetPos = feet.offset(32, 0, FEET_Z);
        AIPlayerEntity target = spawnBot(world, targetName, targetPos);
        AbstractBoat targetBoat = placeBoat(world, targetPos);
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
    private static void runFollow(GameTestHelper context, Scenario scenario,
            AbstractBoat mustBoardFirst, AbstractBoat mustNotEndIn, Runnable onSuccess) {
        AIPlayerEntity bot = scenario.bot();
        FollowTask follow = new FollowTask(scenario.targetName());
        TaskManager.INSTANCE.assign(bot, follow,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_boat_follow"));
        AtomicReference<Vec3> firstBoatPos = new AtomicReference<>();
        AtomicReference<AbstractBoat> firstBoat = new AtomicReference<>();
        context.failIfEver(() -> {
            if (follow.state() == TaskState.FAILED || follow.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("follow ended early: " + follow.state()
                        + " reason=" + follow.failureReason() + " botPos=" + bot.position()
                        + " vehicle=" + bot.getVehicle()
                        + " expectedBoat=" + (mustBoardFirst == null ? "-" : mustBoardFirst.position()
                                + " passengers=" + mustBoardFirst.getPassengers().size()
                                + " alive=" + mustBoardFirst.isAlive())));
                return;
            }
            require(context, bot.getVehicle() != scenario.targetBoat(),
                    "the follower took the boat its target is riding");
            if (!(bot.getVehicle() instanceof AbstractBoat boat)) {
                return;
            }
            if (firstBoat.get() == null) {
                firstBoat.set(boat);
                firstBoatPos.set(boat.position());
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
                require(context, !boat.getPassengers().contains(scenario.target()),
                        "bot and target share a boat");
                onSuccess.run();
                TaskManager.INSTANCE.abort(bot);
                despawnAndComplete(context, bot, scenario.target());
            }
        });
    }

    // ---- world / entity helpers --------------------------------------------------------------

    /** Builds the lake fixture and returns the feet-level origin cell (x=0, z=0). */
    private static BlockPos buildLake(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        world.setDayTime(1000L);
        BlockPos feet = context.absolutePos(new BlockPos(0, 4, 0));
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        // The gametest box is one chunk-sized structure; the lake is wider than that and boats
        // only tick (and only float/drive) in entity-ticking chunks, so force-load the whole area.
        forceLakeChunks(world, feet);
        // Leftover boats from earlier tests in the same server would be offered as "empty boats".
        world.getEntitiesOfClass(AbstractBoat.class,
                net.minecraft.world.phys.AABB.encapsulatingFullBlocks(feet.offset(-30, -10, -30), feet.offset(LAKE_MAX_X + 30, 10, MAX_Z + 30)),
                boat -> true).forEach(net.minecraft.world.entity.Entity::discard);
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
                    world.setBlock(feet.offset(x, y, z), state, Block.UPDATE_ALL);
                }
            }
        }
        // The gametest world is a flat void-ish world below y=40: every slime chunk spawns slimes
        // regardless of light, and one slain bot fails an otherwise correct test (seen live).  Keep
        // the fixture monster-free instead of touching the global mob-spawning game rule.
        net.minecraft.world.phys.AABB area = net.minecraft.world.phys.AABB.encapsulatingFullBlocks(
                feet.offset(-40, -10, -40), feet.offset(LAKE_MAX_X + 40, 12, MAX_Z + 40));
        context.failIfEver(() -> world.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, area,
                mob -> mob instanceof net.minecraft.world.entity.monster.Enemy)
                .forEach(net.minecraft.world.entity.Entity::discard));
        return feet;
    }

    /**
     * Force-loads every chunk under the lake so the boats keep ticking (boats only tick and float in
     * entity-ticking chunks). The chunks are deliberately never released: ChunkForcing is a plain flag shared with
     * the GameTest runner, which force-loads each test structure's chunks too and starts the NEXT batch from its own
     * completion listener, before ours runs. Unforcing a lake chunk in our completion cleanup therefore removed the
     * flag the next test's structure had just been given (the runner's setChunkForced(true) saw it already set), the
     * chunk unloaded, and that test then waited forever for its structure chunks to be entity-loaded: its tick
     * counter never starts, so it does not even time out and the server ticks on at full speed.
     */
    private static void forceLakeChunks(ServerLevel world, BlockPos feet) {
        for (int cx = (feet.getX() - 1) >> 4; cx <= (feet.getX() + LAKE_MAX_X + 1) >> 4; cx++) {
            for (int cz = (feet.getZ() - 1) >> 4; cz <= (feet.getZ() + MAX_Z + 1) >> 4; cz++) {
                world.setChunkForced(cx, cz, true);
            }
        }
    }

    private static AbstractBoat placeBoat(ServerLevel world, BlockPos cell) {
        Boat boat = EntityType.OAK_BOAT.create(world, EntitySpawnReason.COMMAND);
        if (boat == null) {
            throw new IllegalStateException("failed to create boat");
        }
        boat.snapTo(cell.getX() + 0.5D, cell.getY() - 0.1D, cell.getZ() + 0.5D, 0.0F, 0.0F);
        world.addFreshEntity(boat);
        return boat;
    }

    private static void boarded(AIPlayerEntity rider, AbstractBoat boat) {
        rider.teleportTo((ServerLevel) boat.level(), boat.getX(), boat.getY(), boat.getZ(),
                Set.of(), 0.0F, 0.0F, true);
        if (!rider.startRiding(boat, true, false)) {
            throw new IllegalStateException(rider.getGameProfile().name() + " could not board the fixture boat");
        }
    }

    private static AIPlayerEntity spawnBot(ServerLevel world, String name, BlockPos feet) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        return bot;
    }

    private static void despawnAndComplete(GameTestHelper context, AIPlayerEntity... bots) {
        var server = bots[0].level().getServer();
        for (AIPlayerEntity bot : bots) {
            DangerWatcher.INSTANCE.clear(bot);
            AIPlayerManager.INSTANCE.despawn(server, bot.getGameProfile().name());
        }
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}

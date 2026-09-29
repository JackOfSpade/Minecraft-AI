package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.network.PlayerKind;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.require;

/**
 * Real-server proof that phantoms are never spawned around bots: bots never sleep, so their "time since
 * last rest" statistic grows forever and vanilla's {@code PhantomSpawner} would otherwise pick them as
 * targets and the phantoms would attack the humans nearby. A bot and a human stand-in (a real
 * {@link ServerPlayer} over a non-embedded channel, exactly what {@link PlayerKind} tells apart from a
 * bot) get the same huge rest statistic, stand under open sky at night 50 blocks apart, and the vanilla
 * spawner is driven directly many times. The human side is the control: it must get phantoms under the very
 * same conditions, the bot side must get none.
 *
 * <p>The world's difficulty is deliberately not forced to HARD: difficulty is global server state, and
 * changing it would perturb every GameTest running concurrently in the same server (mob spawning, damage,
 * hunger). Instead the test runs on whatever difficulty the server has (asserted to be non-peaceful) and
 * compensates with {@code ATTEMPTS} = 80 spawner runs, which is plenty even at the lowest odds.
 */
public final class PhantomSpawnerGameTests {
    private static final long MIDNIGHT = 18000L;
    private static final int HUGE_REST = 1_000_000_000;
    /** One attempt targets a given player with roughly 1-in-4 odds even on easy; 80 attempts make a miss astronomically unlikely. */
    private static final int ATTEMPTS = 80;
    private static final int SETTLE_TICKS = 10;

    @GameTest(environment = "minecraftai-gametest:phantom_spawner_game_tests_bots_are_skipped_while_humans_with_the_same_stat_still_get_phantoms", maxTicks = 3000)
    public void botsAreSkippedWhileHumansWithTheSameStatStillGetPhantoms(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        MinecraftServer server = world.getServer();
        BlockPos base = context.absolutePos(new BlockPos(20, 5, 180));
        // The default gametest area may lie below sea level (the spawner ignores players there) or under terrain:
        // stand on a platform at least at sea level with clear sky above and clear air where phantoms appear.
        int floorY = Math.max(base.getY(), world.getSeaLevel()) - 1;
        BlockPos botFloor = new BlockPos(base.getX(), floorY, base.getZ());
        BlockPos humanFloor = botFloor.east(50);
        prepareArena(world, botFloor);
        prepareArena(world, humanFloor);

        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(server, "PhantomBotGT", world,
                        Vec3.atBottomCenterOf(botFloor.above()), 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn the bot"));
        ServerPlayer human = SleepVoteGameTests.connectHuman(server, world, humanFloor.above(), "PhantomHumanGT");
        boolean originalSpawnPhantoms = world.getGameRules().get(GameRules.SPAWN_PHANTOMS);
        boolean originalAdvanceTime = world.getGameRules().get(GameRules.ADVANCE_TIME);
        long originalDayTime = world.getDayTime();
        int[] ticks = {0};

        Runnable cleanup = () -> {
            discardPhantoms(world, box(botFloor));
            discardPhantoms(world, box(humanFloor));
            if (human.connection != null) {
                human.connection.onDisconnect(new DisconnectionDetails(Component.literal("phantom test over")));
            }
            AIPlayerManager.INSTANCE.despawn(server, "PhantomBotGT");
            world.getGameRules().set(GameRules.SPAWN_PHANTOMS, originalSpawnPhantoms, server);
            world.getGameRules().set(GameRules.ADVANCE_TIME, originalAdvanceTime, server);
            world.setDayTime(originalDayTime);
        };

        TimeLockedRun.run(context, 400, () -> {
            ticks[0]++;
            // no network handler ticks the stand-in: keep its chunk loaded
            world.getChunkSource().move(human);
            world.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
            world.getGameRules().set(GameRules.SPAWN_PHANTOMS, true, server);
            world.setDayTime(MIDNIGHT);
            bot.getStats().setValue(bot, Stats.CUSTOM.get(Stats.TIME_SINCE_REST), HUGE_REST);
            human.getStats().setValue(human, Stats.CUSTOM.get(Stats.TIME_SINCE_REST), HUGE_REST);
            if (ticks[0] == 1) {
                require(context, PlayerKind.isBot(bot) && !PlayerKind.isBot(human),
                        "the bot must classify as a bot and the stand-in as a human");
                require(context, world.players().contains(bot) && world.players().contains(human),
                        "both must be players of the world, or vanilla would not see them");
            }
            if (ticks[0] < SETTLE_TICKS) {
                return false; // give the ambient darkness and the sky light a few ticks to follow the clock
            }
            require(context, world.getSkyDarken() >= 5,
                    "it is not dark enough for phantoms: sky darken " + world.getSkyDarken());
            require(context, world.canSeeSky(bot.blockPosition()) && world.canSeeSky(human.blockPosition()),
                    "both players must see the sky: bot " + world.canSeeSky(bot.blockPosition())
                            + " human " + world.canSeeSky(human.blockPosition()));
            require(context, world.getDifficulty() != Difficulty.PEACEFUL,
                    "the world is peaceful: the control cannot spawn phantoms");

            for (int i = 0; i < ATTEMPTS; i++) {
                // a fresh spawner each time: vanilla's own cooldown (its nextTick counter) would swallow all but the first
                new PhantomSpawner().tick(world, true);
            }
            List<Phantom> nearBot = world.getEntitiesOfClass(Phantom.class, box(botFloor));
            List<Phantom> nearHuman = world.getEntitiesOfClass(Phantom.class, box(humanFloor));
            require(context, !nearHuman.isEmpty(),
                    "control failed: the human with the same rest statistic got no phantom in " + ATTEMPTS
                            + " spawner ticks, so the scenario cannot prove anything about the bot");
            require(context, nearBot.isEmpty(),
                    nearBot.size() + " phantom(s) spawned around the bot (" + nearHuman.size() + " around the human)");
            return true;
        }, cleanup);
    }

    private static AABB box(BlockPos floor) {
        return new AABB(floor).inflate(25.0D, 0.0D, 25.0D).expandTowards(0.0D, 60.0D, 0.0D);
    }

    private static void discardPhantoms(ServerLevel world, AABB area) {
        for (Phantom phantom : world.getEntitiesOfClass(Phantom.class, area)) {
            phantom.discard();
        }
    }

    /**
     * A stone platform with open air above it: the whole column up to the world top over the 7x7 stand (so
     * the sky light reaches it) and the region a phantom may appear in (x/z +-10, 20..36 above the player).
     */
    private static void prepareArena(ServerLevel world, BlockPos floor) {
        int top = world.getMinY() + world.getHeight() - 1;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                world.setBlock(floor.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        for (int dx = -10; dx <= 10; dx++) {
            for (int dz = -10; dz <= 10; dz++) {
                boolean stand = Math.abs(dx) <= 3 && Math.abs(dz) <= 3;
                int from = stand ? 1 : 20;
                int to = stand ? top - floor.getY() : 36;
                for (int dy = from; dy <= to && floor.getY() + dy <= top; dy++) {
                    BlockPos cell = floor.offset(dx, dy, dz);
                    if (!world.getBlockState(cell).isAir()) {
                        world.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }
    }
}

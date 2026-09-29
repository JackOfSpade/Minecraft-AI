package io.github.zoyluo.minecraftai.task;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Either;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.network.ClientConnectionAccessor;
import io.github.zoyluo.minecraftai.network.FakeClientConnection;
import io.github.zoyluo.minecraftai.network.PlayerKind;
import io.netty.channel.local.LocalChannel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BedPart;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.NetworkSide;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.Unit;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.rule.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.require;

/**
 * Real-server proof that only human players take part in the vanilla sleep vote: a human in a bed at night
 * must be able to skip it while an awake bot stands in the same world (vanilla counts every non-spectator
 * player, so 1 sleeping of 2 would never skip). The human is a real {@link ServerPlayerEntity} whose
 * connection runs over a non-embedded channel, exactly what {@link PlayerKind} tells apart from a bot.
 */
public final class SleepVoteGameTests {
    private static final Logger LOG = LoggerFactory.getLogger("minecraftai-sleep-vote-gametest");

    @GameTest(environment = "minecraftai-gametest:sleep_vote_game_tests_awake_bots_do_not_block_human_from_skipping_the_night", maxTicks = 3000)
    public void awakeBotsDoNotBlockHumanFromSkippingTheNight(TestContext context) {
        ServerWorld world = context.getWorld();
        MinecraftServer server = world.getServer();
        BlockPos foot = context.getAbsolutePos(new BlockPos(20, 5, 180));
        BlockPos head = foot.east();
        for (int dx = -3; dx <= 4; dx++) {
            for (int dz = -3; dz <= 4; dz++) {
                BlockPos cell = foot.add(dx, 0, dz);
                world.setBlockState(cell.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                for (int dy = 0; dy <= 3; dy++) {
                    world.setBlockState(cell.up(dy), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        world.setBlockState(foot, Blocks.RED_BED.getDefaultState()
                .with(BedBlock.FACING, Direction.EAST).with(BedBlock.PART, BedPart.FOOT), Block.NOTIFY_ALL);
        world.setBlockState(head, Blocks.RED_BED.getDefaultState()
                .with(BedBlock.FACING, Direction.EAST).with(BedBlock.PART, BedPart.HEAD), Block.NOTIFY_ALL);

        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(server, "SleepVoteBotGT", world,
                        Vec3d.ofBottomCenter(foot.south(3)), 0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn the awake bot"));
        ServerPlayerEntity[] human = {null};
        boolean originalAdvanceTime = world.getGameRules().getValue(GameRules.ADVANCE_TIME);
        int originalPercentage = world.getGameRules().getValue(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
        long[] startDay = {0L};
        int[] ticks = {0};
        int[] sleepStart = {0};
        boolean[] slept = {false};

        Runnable cleanup = () -> {
            if (human[0] != null && human[0].networkHandler != null) {
                human[0].networkHandler.onDisconnected(new DisconnectionInfo(Text.literal("sleep vote test over")));
            }
            AIPlayerManager.INSTANCE.despawn(server, "SleepVoteBotGT");
            world.getGameRules().setValue(GameRules.ADVANCE_TIME, originalAdvanceTime, server);
            world.getGameRules().setValue(GameRules.PLAYERS_SLEEPING_PERCENTAGE, originalPercentage, server);
        };

        TimeLockedRun.run(context, 700, () -> {
            ticks[0]++;
            if (human[0] != null) {
                // no network handler ticks this stand-in (as for a bot, see AIPlayerEntity.tick): tick it and keep its chunk loaded
                world.getChunkManager().updatePosition(human[0]);
                human[0].tick();
                human[0].playerTick();
            }
            if (human[0] == null) {
                world.getGameRules().setValue(GameRules.ADVANCE_TIME, true, server);
                // the vanilla default: every counted player must be asleep, so an awake bot in the count blocks the skip
                world.getGameRules().setValue(GameRules.PLAYERS_SLEEPING_PERCENTAGE, 100, server);
                startDay[0] = world.getTimeOfDay() / 24000L;
                human[0] = connectHuman(server, world, foot);
                require(context, !PlayerKind.isBot(human[0]), "the stand-in was classified as a bot");
                require(context, PlayerKind.isBot(bot), "our bot was not classified as a bot");
                List<ServerPlayerEntity> counted = PlayerKind.humansOnly(world.getPlayers());
                require(context, counted.contains(human[0]) && !counted.contains(bot),
                        "the vote list must hold the human and not the bot: " + counted);
                require(context, world.getPlayers().contains(bot) && !bot.isSpectator() && !bot.isSleeping(),
                        "the awake bot is not a non-spectator player of the world: vanilla would not be blocked");
            }
            if (!human[0].isSleeping() && !slept[0]) {
                // Pin the night and give the ambient darkness (recomputed each tick) a few ticks to follow.
                world.setTimeOfDay(startDay[0] * 24000L + 18000L);
                if (ticks[0] < 8) {
                    return false;
                }
                Either<PlayerEntity.SleepFailureReason, Unit> result = human[0].trySleep(head);
                require(context, result.right().isPresent(), "the human could not lie down: " + result.left().orElse(null));
                slept[0] = true;
                sleepStart[0] = ticks[0];
                return false;
            }
            if (human[0].isSleeping()) {
                require(context, ticks[0] - sleepStart[0] < 400,
                        "the night was never skipped: the awake bot still counts in the vote");
                return false;
            }
            long now = world.getTimeOfDay();
            require(context, ticks[0] - sleepStart[0] >= 100,
                    "the human woke before the 100 ticks a vote-driven wake-up needs: " + (ticks[0] - sleepStart[0]));
            require(context, now / 24000L > startDay[0] && now % 24000L < 2000L,
                    "the human woke but the night was not skipped, time of day is " + now);
            return true;
        }, cleanup);
    }

    /**
     * A real (non-{@link AIPlayerEntity}) player whose connection is a fake that never touches a socket but
     * runs over a {@link LocalChannel}, the channel type of a singleplayer client, so it counts as a human.
     */
    static ServerPlayerEntity connectHuman(MinecraftServer server, ServerWorld world, BlockPos foot) {
        GameProfile profile = new GameProfile(UUID.randomUUID(), "SleepVoteHuman");
        ConnectedClientData data = ConnectedClientData.createDefault(profile, false);
        ServerPlayerEntity player = new ServerPlayerEntity(server, world, profile, data.syncedOptions()) {
            @Override
            public boolean isSpectator() {
                return false;
            }
        };
        FakeClientConnection connection = new FakeClientConnection(NetworkSide.SERVERBOUND) {
            @Override
            public void flush() {
                // the local channel is never registered to an event loop
            }
        };
        ((ClientConnectionAccessor) connection).minecraftai$setChannel(new LocalChannel());
        server.getPlayerManager().onPlayerConnect(connection, player, data);
        player.teleport(world, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                java.util.Set.of(), 0.0F, 0.0F, true);
        player.setHealth(player.getMaxHealth());
        return player;
    }
}

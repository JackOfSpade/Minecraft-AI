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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.require;

/**
 * Real-server proof that only human players take part in the vanilla sleep vote: a human in a bed at night
 * must be able to skip it while an awake bot stands in the same world (vanilla counts every non-spectator
 * player, so 1 sleeping of 2 would never skip). The human is a real {@link ServerPlayer} whose
 * connection runs over a non-embedded channel, exactly what {@link PlayerKind} tells apart from a bot.
 */
public final class SleepVoteGameTests {
    private static final Logger LOG = LoggerFactory.getLogger("minecraftai-sleep-vote-gametest");

    @GameTest(environment = "minecraftai-gametest:sleep_vote_game_tests_awake_bots_do_not_block_human_from_skipping_the_night", maxTicks = 3000)
    public void awakeBotsDoNotBlockHumanFromSkippingTheNight(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        MinecraftServer server = world.getServer();
        BlockPos foot = context.absolutePos(new BlockPos(20, 5, 180));
        BlockPos head = foot.east();
        for (int dx = -3; dx <= 4; dx++) {
            for (int dz = -3; dz <= 4; dz++) {
                BlockPos cell = foot.offset(dx, 0, dz);
                world.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 3; dy++) {
                    world.setBlock(cell.above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        world.setBlock(foot, Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.FOOT), Block.UPDATE_ALL);
        world.setBlock(head, Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.HEAD), Block.UPDATE_ALL);

        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(server, "SleepVoteBotGT", world,
                        Vec3.atBottomCenterOf(foot.south(3)), 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn the awake bot"));
        ServerPlayer[] human = {null};
        boolean originalAdvanceTime = world.getGameRules().get(GameRules.ADVANCE_TIME);
        int originalPercentage = world.getGameRules().get(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
        long[] startDay = {0L};
        int[] ticks = {0};
        int[] sleepStart = {0};
        boolean[] slept = {false};

        Runnable cleanup = () -> {
            if (human[0] != null && human[0].connection != null) {
                human[0].connection.onDisconnect(new DisconnectionDetails(Component.literal("sleep vote test over")));
            }
            AIPlayerManager.INSTANCE.despawn(server, "SleepVoteBotGT");
            world.getGameRules().set(GameRules.ADVANCE_TIME, originalAdvanceTime, server);
            world.getGameRules().set(GameRules.PLAYERS_SLEEPING_PERCENTAGE, originalPercentage, server);
        };

        TimeLockedRun.run(context, 700, () -> {
            ticks[0]++;
            if (human[0] != null) {
                // no network handler ticks this stand-in (as for a bot, see AIPlayerEntity.tick): tick it and keep its chunk loaded
                world.getChunkSource().move(human[0]);
                human[0].tick();
                human[0].doTick();
            }
            if (human[0] == null) {
                world.getGameRules().set(GameRules.ADVANCE_TIME, true, server);
                // the vanilla default: every counted player must be asleep, so an awake bot in the count blocks the skip
                world.getGameRules().set(GameRules.PLAYERS_SLEEPING_PERCENTAGE, 100, server);
                startDay[0] = world.getDayTime() / 24000L;
                human[0] = connectHuman(server, world, foot);
                require(context, !PlayerKind.isBot(human[0]), "the stand-in was classified as a bot");
                require(context, PlayerKind.isBot(bot), "our bot was not classified as a bot");
                List<ServerPlayer> counted = PlayerKind.humansOnly(world.players());
                require(context, counted.contains(human[0]) && !counted.contains(bot),
                        "the vote list must hold the human and not the bot: " + counted);
                require(context, world.players().contains(bot) && !bot.isSpectator() && !bot.isSleeping(),
                        "the awake bot is not a non-spectator player of the world: vanilla would not be blocked");
            }
            if (!human[0].isSleeping() && !slept[0]) {
                // Pin the night and give the ambient darkness (recomputed each tick) a few ticks to follow.
                world.setDayTime(startDay[0] * 24000L + 18000L);
                if (ticks[0] < 8) {
                    return false;
                }
                Either<Player.BedSleepingProblem, Unit> result = human[0].startSleepInBed(head);
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
            long now = world.getDayTime();
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
    static ServerPlayer connectHuman(MinecraftServer server, ServerLevel world, BlockPos foot) {
        GameProfile profile = new GameProfile(UUID.randomUUID(), "SleepVoteHuman");
        CommonListenerCookie data = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(server, world, profile, data.clientInformation()) {
            @Override
            public boolean isSpectator() {
                return false;
            }
        };
        FakeClientConnection connection = new FakeClientConnection(PacketFlow.SERVERBOUND) {
            @Override
            public void flushChannel() {
                // the local channel is never registered to an event loop
            }
        };
        ((ClientConnectionAccessor) connection).minecraftai$setChannel(new LocalChannel());
        server.getPlayerList().placeNewPlayer(connection, player, data);
        player.teleportTo(world, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                java.util.Set.of(), 0.0F, 0.0F, true);
        player.setHealth(player.getMaxHealth());
        return player;
    }
}

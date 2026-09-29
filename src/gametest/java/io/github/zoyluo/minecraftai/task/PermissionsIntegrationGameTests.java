package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.network.PlayerKind;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.require;

/**
 * Real-server proof of the fabric-permissions-api integration that keeps bots from vein-mining. VeinMiner
 * itself is not on the GameTest runtime, but the permissions API is: with VeinMiner's
 * {@code permissionRestricted} setting on it asks {@code Permissions.check(player, "veinminer.use")}, so
 * this exercises exactly that call. A bot must be denied, a human stand-in (a real {@link ServerPlayer}
 * over a non-embedded channel) allowed, and no other node may be touched.
 */
public final class PermissionsIntegrationGameTests {
    @GameTest(environment = "minecraftai-gametest:permissions_integration_game_tests_bots_are_denied_vein_miner_while_humans_are_allowed_and_other_nodes_are_untouched", maxTicks = 200)
    public void botsAreDeniedVeinMinerWhileHumansAreAllowedAndOtherNodesAreUntouched(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        MinecraftServer server = world.getServer();
        BlockPos spot = context.absolutePos(new BlockPos(20, 5, 220));
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(server, "PermissionsBotGT", world,
                        Vec3.atBottomCenterOf(spot), 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn the bot"));
        ServerPlayer human = null;
        try {
            human = SleepVoteGameTests.connectHuman(server, world, spot.east(6), "PermissionsHumanGT");
            require(context, PlayerKind.isBot(bot) && !PlayerKind.isBot(human),
                    "the bot must classify as a bot and the stand-in as a human");

            require(context, !Permissions.check(bot, "veinminer.use"), "a bot must not be allowed to vein-mine");
            require(context, !Permissions.check(bot, "veinminer.use", true),
                    "the bot's veinminer.use must be a hard no, not the default");
            require(context, Permissions.check(human, "veinminer.use"), "a human must be allowed to vein-mine");
            require(context, Permissions.check(human, "veinminer.use", false),
                    "the human's veinminer.use must be a hard yes, not the default");

            // no other node is answered: the API's own default (given by the caller) decides
            for (String other : new String[]{"veinminer.other", "minecraftai.unrelated", "veinminer.use.extra"}) {
                require(context, Permissions.check(bot, other, true) && !Permissions.check(bot, other, false),
                        "bot: " + other + " must be left to the default");
                require(context, Permissions.check(human, other, true) && !Permissions.check(human, other, false),
                        "human: " + other + " must be left to the default");
            }
        } finally {
            if (human != null && human.connection != null) {
                human.connection.onDisconnect(new DisconnectionDetails(Component.literal("permissions test over")));
            }
            AIPlayerManager.INSTANCE.despawn(server, "PermissionsBotGT");
        }
        context.succeed();
    }
}

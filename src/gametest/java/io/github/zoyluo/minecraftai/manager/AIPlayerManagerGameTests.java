package io.github.zoyluo.minecraftai.manager;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.gametest.GameTestCleanup;
import io.github.zoyluo.minecraftai.gametest.MockPlayers;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.task.SharedVision;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Lifecycle regressions for the owner index and per-tick owner-vision cache. */
public final class AIPlayerManagerGameTests {
    private static final String ENV = "minecraftai-gametest:ai_player_manager_game_tests_";

    @GameTest(environment = ENV + "sibling_matches_counts_only_other_live_bots", maxTicks = 20)
    public void siblingMatchesCountsOnlyOtherLiveBots(GameTestHelper context) {
        var world = context.getLevel();
        var server = world.getServer();
        ServerPlayer owner = MockPlayers.survivalMock(context);
        UUID ownerId = owner.getUUID();
        BlockPos start = context.absolutePos(new BlockPos(3, 2, 3));
        prepareLanding(context, start);
        List<String> botNames = new ArrayList<>();
        GameTestCleanup.whenFinished(context, () -> {
            for (String name : botNames) {
                AIPlayerManager.INSTANCE.despawn(server, name);
            }
        });

        int[] predicateCalls = {0};
        require(context, !AIPlayerManager.INSTANCE.anySiblingMatches(ownerId, null, bot -> {
                    predicateCalls[0]++;
                    return true;
                }),
                "zero owned bots unexpectedly had a sibling");
        require(context, predicateCalls[0] == 0,
                "zero owned bots evaluated the sibling predicate " + predicateCalls[0] + " times");

        AIPlayerEntity first = spawn(context, name("SiblingA", ownerId), start, ownerId, botNames);
        predicateCalls[0] = 0;
        require(context, !AIPlayerManager.INSTANCE.anySiblingMatches(ownerId, first, bot -> {
                    predicateCalls[0]++;
                    return true;
                }),
                "one owned bot unexpectedly had a sibling");
        require(context, predicateCalls[0] == 0,
                "one owned bot evaluated the sibling predicate " + predicateCalls[0] + " times");

        AIPlayerEntity second = spawn(context, name("SiblingB", ownerId), start.east(3), ownerId, botNames);
        require(context, AIPlayerManager.INSTANCE.anySiblingMatches(ownerId, first, sibling -> sibling == second),
                "the second live owner sibling was not found from the first bot");
        require(context, !AIPlayerManager.INSTANCE.anySiblingMatches(ownerId, second, sibling -> sibling == second),
                "except bot was allowed to match itself");
        require(context, AIPlayerManager.INSTANCE.anySiblingMatches(ownerId, second, sibling -> sibling == first),
                "the first live owner sibling was not found when the second was excluded");
        context.succeed();
    }

    @GameTest(environment = ENV + "forget_drops_a_populated_same_tick_owner_lookup", maxTicks = 20)
    public void forgetDropsAPopulatedSameTickOwnerLookup(GameTestHelper context) {
        var world = context.getLevel();
        var server = world.getServer();
        ServerPlayer owner = MockPlayers.survivalMock(context);
        UUID ownerId = owner.getUUID();
        BlockPos start = context.absolutePos(new BlockPos(3, 2, 3));
        prepareLanding(context, start);
        List<String> botNames = new ArrayList<>();
        List<UUID> botIds = new ArrayList<>();
        GameTestCleanup.whenFinished(context, () -> {
            for (UUID botId : botIds) {
                SharedVision.forget(botId);
            }
            for (String name : botNames) {
                AIPlayerManager.INSTANCE.despawn(server, name);
            }
        });

        AIPlayerEntity bot = spawn(context, name("Vision", ownerId), start, ownerId, botNames);
        botIds.add(bot.getUUID());
        require(context, AIPlayerManager.INSTANCE.ownerOf(bot).filter(ownerId::equals).isPresent(),
                "fixture bot was not registered to its owner");
        require(context, SharedVision.ownerOnline(bot) == owner,
                "fixture did not populate the same-tick owner cache");
        MockPlayers.disconnect(server, owner);
        require(context, server.getPlayerList().getPlayer(ownerId) == null,
                "fixture owner remained online after disconnect");
        require(context, AIPlayerManager.INSTANCE.ownerOf(bot).filter(ownerId::equals).isPresent(),
                "disconnect incorrectly removed the bot's persistent owner relationship");
        require(context, SharedVision.ownerOnline(bot) == owner,
                "fixture did not retain the populated owner lookup in the same server tick");

        SharedVision.forget(bot.getUUID());
        require(context, SharedVision.ownerOnline(bot) == null,
                "forget did not evict the populated same-tick owner lookup");
        context.succeed();
    }

    /**
     * A bot is placed at the world spawn and then teleported to where it is wanted, and it has no client whose movement packets would
     * re-centre its chunk tracking: until its own first tick, a bot spawned into a loaded chunk used to see nothing in it, so anything
     * started in the spawn tick (a restored mission) refused its first route as unobserved.
     */
    @GameTest(environment = ENV + "spawned_bot_observes_its_chunk_in_the_tick_it_spawns", maxTicks = 20)
    public void spawnedBotObservesItsChunkInTheTickItSpawns(GameTestHelper context) {
        var world = context.getLevel();
        var server = world.getServer();
        BlockPos start = context.absolutePos(new BlockPos(3, 2, 3));
        prepareLanding(context, start);
        BlockPos log = start.east(3);
        world.setBlock(log, Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        String name = "TrackSpawnGT";
        GameTestCleanup.whenFinished(context, () -> AIPlayerManager.INSTANCE.despawn(server, name));

        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        server, name, world, Vec3.atBottomCenterOf(start), 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        require(context, bot.getChunkTrackingView().contains(log.getX() >> 4, log.getZ() >> 4),
                "the bot does not track the chunk it was spawned into");
        require(context, ObservableWorldQuery.canObserveBlock(bot, log),
                "the bot cannot see a log in plain view in the tick it was spawned");
        context.succeed();
    }

    /** A retained player must return as an owner-visible entity, and remain recallable afterwards. */
    @GameTest(environment = ENV + "dead_owned_companion_respawns_visibly_and_recall_repairs_it", maxTicks = 40)
    public void deadOwnedCompanionRespawnsVisiblyAndRecallRepairsIt(GameTestHelper context) {
        var world = context.getLevel();
        var server = world.getServer();
        ServerPlayer owner = MockPlayers.survivalMock(context);
        UUID ownerId = owner.getUUID();
        BlockPos ownerFeet = context.absolutePos(new BlockPos(3, 2, 3));
        BlockPos botFeet = ownerFeet.east(8);
        prepareLanding(context, ownerFeet);
        prepareLanding(context, botFeet);
        owner.teleportTo(world, ownerFeet.getX() + 0.5D, ownerFeet.getY(), ownerFeet.getZ() + 0.5D,
                java.util.Set.of(), 0.0F, 0.0F, true);
        // A real client immediately acknowledges its teleport. The harness mock has no client
        // tick, so move its chunk view explicitly before asking vanilla's tracker to pair Moss.
        world.getChunkSource().move(owner);
        String name = name("RespawnRecall", ownerId);
        GameTestCleanup.whenFinished(context, () -> AIPlayerManager.INSTANCE.despawn(server, name));
        AIPlayerEntity bot = spawn(context, name, botFeet, ownerId, new ArrayList<>());

        bot.setHealth(0.0F);
        bot.die(world.damageSources().genericKill());
        require(context, bot.isDeadOrDying(), "fixture bot did not enter vanilla death state");
        require(context, AIPlayerManager.INSTANCE.respawnDeadBot(bot, owner), "dead owned companion did not respawn");
        require(context, bot.isAlive(), "respawned companion is not alive");
        require(context, isVisibleBesideOwner(bot, owner), "respawned companion is not visibly beside its owner");

        bot.teleportTo(world, botFeet.getX() + 0.5D, botFeet.getY(), botFeet.getZ() + 0.5D,
                java.util.Set.of(), 0.0F, 0.0F, true);
        require(context, AIPlayerManager.INSTANCE.recallOwnedCompanion(owner, name),
                "owner recall rejected a live owned companion");
        require(context, isVisibleBesideOwner(bot, owner), "recalled companion is not visibly beside its owner");
        context.succeed();
    }

    private static AIPlayerEntity spawn(GameTestHelper context,
                                        String name,
                                        BlockPos feet,
                                        UUID ownerId,
                                        List<String> botNames) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getLevel().getServer(), name, context.getLevel(), Vec3.atBottomCenterOf(feet),
                        0.0F, 0.0F, GameType.SURVIVAL, ownerId)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        botNames.add(name);
        return bot;
    }

    private static String name(String prefix, UUID ownerId) {
        return prefix + ownerId.toString().replace("-", "").substring(0, 7);
    }

    private static boolean isVisibleBesideOwner(AIPlayerEntity bot, ServerPlayer owner) {
        return bot.isAlive() && bot.level() == owner.level()
                && bot.distanceToSqr(owner) <= 3.5D * 3.5D && owner.hasLineOfSight(bot);
    }

    private static void prepareLanding(GameTestHelper context, BlockPos center) {
        var world = context.getLevel();
        for (int dx = -2; dx <= 5; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = center.offset(dx, 0, dz);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}

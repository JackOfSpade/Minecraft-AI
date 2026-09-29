package io.github.zoyluo.minecraftai.task;

import com.mojang.logging.LogUtils;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.MiningController;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.require;

/**
 * Pack-compat proof that VeinMiner really respects the bot exclusion. It is meaningful only when VeinMiner is loaded
 * (run with the profile's mods, see docs/TESTING_AND_EVIDENCE.md, "Pack-compat check"); without it the test logs
 * {@code VEINMINER_PACK result=skipped} and passes, so the normal suite is unaffected. The harness turns VeinMiner's
 * {@code permissionRestricted} on in the run directory's config, so VeinMiner asks {@code veinminer.use} before it vein-mines:
 * <ul>
 *   <li>a human stand-in breaking one ore of a five-ore vein with an iron pickaxe must take the whole vein (positive control:
 *       VeinMiner is active and reacts to a server-side break), and</li>
 *   <li>a bot breaking the first ore of an identical vein through its real {@link MiningController} must remove that one ore
 *       only, every other ore of the vein staying put.</li>
 * </ul>
 */
public final class VeinMinerPackGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** The vein relative to its first ore: a row of three plus two more on top, all edge/face connected. */
    private static final List<BlockPos> VEIN = List.of(
            new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(2, 0, 0), new BlockPos(0, 1, 0), new BlockPos(1, 1, 0));

    @GameTest(environment = "minecraftai-gametest:vein_miner_pack_game_tests_bot_breaks_are_not_vein_mined_while_ahuman_break_is", maxTicks = 400)
    public void botBreaksAreNotVeinMinedWhileAHumanBreakIs(GameTestHelper context) {
        if (!FabricLoader.getInstance().isModLoaded("veinminer")) {
            LOGGER.info("VEINMINER_PACK result=skipped reason=veinminer_not_loaded");
            context.succeed();
            return;
        }
        ServerLevel world = context.getLevel();
        MinecraftServer server = world.getServer();
        BlockPos botSpot = context.absolutePos(new BlockPos(18, 5, 220));
        BlockPos humanSpot = context.absolutePos(new BlockPos(18, 5, 230));
        BlockPos botFirstOre = buildVeinFixture(world, botSpot);
        BlockPos humanFirstOre = buildVeinFixture(world, humanSpot);

        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(server, "VeinBotGT", world,
                        Vec3.atBottomCenterOf(botSpot), -90.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn the bot"));
        ServerPlayer human = null;
        try {
            InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
            human = SleepVoteGameTests.connectHuman(server, world, humanSpot, "VeinHumanGT");
            // the GameTest server defaults new players to creative, which VeinMiner never vein-mines for
            human.setGameMode(GameType.SURVIVAL);
            human.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        } catch (RuntimeException e) {
            cleanup(server, human);
            throw e;
        }
        ServerPlayer standIn = human;
        // the bot's break goes through the same START/STOP_DESTROY_BLOCK path as its mining tasks, one tick per server tick
        MiningController mining = new MiningController(botFirstOre, Direction.WEST);
        int[] ticks = {0};
        int[] botDoneTick = {-1};
        boolean[] humanBroke = {false};
        context.failIfEver(() -> {
            ticks[0]++;
            if (botDoneTick[0] < 0) {
                ActionResult r = mining.tick(bot.getActionPack());
                require(context, !r.isFailed(), "the bot's own mining failed: " + r.reason());
                if (r.isSuccess()) {
                    botDoneTick[0] = ticks[0];
                }
            }
            if (!humanBroke[0]) {
                humanBroke[0] = true;
                require(context, standIn.gameMode.destroyBlock(humanFirstOre),
                        "the human stand-in could not break the first ore");
            }
            if (botDoneTick[0] < 0 || ticks[0] < botDoneTick[0] + 10) {
                return;
            }
            int humanLeft = remaining(world, humanFirstOre);
            int botLeft = remaining(world, botFirstOre);
            LOGGER.info("VEINMINER_PACK result=checked human_ores_left={} bot_ores_left={} of={}", humanLeft, botLeft, VEIN.size());
            cleanup(server, standIn);
            require(context, humanLeft == 0,
                    "positive control failed: VeinMiner did not vein-mine the human's break (" + humanLeft + " of "
                            + VEIN.size() + " ores left), so the bot result would prove nothing");
            require(context, world.getBlockState(botFirstOre).isAir(), "the bot's own ore was not broken");
            require(context, botLeft == VEIN.size() - 1,
                    "VeinMiner vein-mined a BOT's break: " + botLeft + " of the other " + (VEIN.size() - 1)
                            + " ores are left (expected all)");
            context.succeed();
        });
        // safety net for a test that times out or fails before the check above
        context.runAfterDelay(390, () -> cleanup(server, standIn));
    }

    private static void cleanup(MinecraftServer server, ServerPlayer human) {
        if (human != null && human.connection != null) {
            human.connection.onDisconnect(new DisconnectionDetails(Component.literal("vein miner pack test over")));
        }
        AIPlayerManager.INSTANCE.despawn(server, "VeinBotGT");
    }

    private static int remaining(ServerLevel world, BlockPos firstOre) {
        int left = 0;
        for (BlockPos offset : VEIN) {
            if (world.getBlockState(firstOre.offset(offset)).is(Blocks.IRON_ORE)) {
                left++;
            }
        }
        return left;
    }

    /**
     * A stone floor with cleared air above it, the player cell at {@code spot} and the vein starting one cell east of it
     * (returned: the first ore, directly in front of the standing player).
     */
    private static BlockPos buildVeinFixture(ServerLevel world, BlockPos spot) {
        for (int dx = -2; dx <= 6; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.setBlock(spot.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 4; dy++) {
                    world.setBlock(spot.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        BlockPos first = spot.east();
        for (BlockPos offset : VEIN) {
            world.setBlock(first.offset(offset), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        }
        return first;
    }
}

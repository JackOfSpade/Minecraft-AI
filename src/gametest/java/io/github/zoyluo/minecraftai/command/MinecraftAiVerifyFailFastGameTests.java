package io.github.zoyluo.minecraftai.command;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.goal.Goal;
import io.github.zoyluo.minecraftai.goal.GoalExecutor;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.MiningEvidenceAudit;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.runtime.IntentController;
import io.github.zoyluo.minecraftai.runtime.RuntimeLifecycleCoordinator;
import io.github.zoyluo.minecraftai.task.StripMineTask;
import io.github.zoyluo.minecraftai.task.TaskManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.List;

/** World-backed contract for strict from-zero acceptance fail-fast boundaries. */
public final class MinecraftAiVerifyFailFastGameTests {
    private static final String ZERO_DEATH_VIOLATION = "zero_death_violation";

    @GameTest(maxTicks = 5)
    public void verifyAllIncludesTheRetiredLegacyMiningBoundary(GameTestHelper context) {
        List<String> strictAll = MinecraftAiVerifySubcommand.expandFeaturesForGameTest(
                List.of("all"), OperatingProfile.STRICT_SURVIVAL);
        List<String> operatorAll = MinecraftAiVerifySubcommand.expandFeaturesForGameTest(
                List.of("all"), OperatingProfile.OPERATOR);

        require(context, !strictAll.contains("strip_mine") && !operatorAll.contains("strip_mine"),
                "verify all retained a public legacy strip_mine scenario");
        require(context, count(strictAll, MinecraftAiVerifySubcommand.RETIRED_LEGACY_MINING_FEATURE) == 1
                        && count(operatorAll, MinecraftAiVerifySubcommand.RETIRED_LEGACY_MINING_FEATURE) == 1,
                "verify all must include exactly one profile-independent retirement boundary");
        require(context, strictAll.size() == operatorAll.size(),
                "profile expansion silently changed verify-all coverage cardinality");

        require(context, MinecraftAiVerifySubcommand.expandFeaturesForGameTest(
                        List.of(MinecraftAiVerifySubcommand.RETIRED_LEGACY_MINING_FEATURE), OperatingProfile.STRICT_SURVIVAL)
                        .equals(List.of(MinecraftAiVerifySubcommand.RETIRED_LEGACY_MINING_FEATURE)),
                "explicit retired legacy-mining verification did not remain available");
        require(context, MinecraftAiVerifySubcommand.expandFeaturesForGameTest(
                        List.of("strip_mine"), OperatingProfile.OPERATOR)
                        .isEmpty(),
                "retired strip_mine unexpectedly remained a verification feature");
        require(context, count(MinecraftAiVerifySubcommand.expandFeaturesForGameTest(
                        List.of("all+" + MinecraftAiVerifySubcommand.RETIRED_LEGACY_MINING_FEATURE), OperatingProfile.STRICT_SURVIVAL),
                        MinecraftAiVerifySubcommand.RETIRED_LEGACY_MINING_FEATURE) == 1,
                "composed suites duplicated the retirement boundary");
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void retiredLegacyMiningScenarioRequiresExactTypedRejection(GameTestHelper context) {
        AIPlayerEntity bot = spawnBot(context, "VerifyStripGateGT");
        String feature = MinecraftAiVerifySubcommand.RETIRED_LEGACY_MINING_FEATURE;
        try {
            require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                    "typed retirement fixture is not running under strict_survival");
            require(context, MinecraftAiVerifySubcommand.startForGameTest(
                            bot.level().getServer().createCommandSourceStack(), bot, feature),
                    "retired legacy-mining verifier did not start");

            MinecraftAiVerifySubcommand.tick(bot.level().getServer());
            require(context, StripMineTask.RETIRED_REJECTION.equals(
                            TaskManager.INSTANCE.status(bot).failureReason()),
                    "retained StripMineTask did not emit the exact retirement rejection");

            MinecraftAiVerifySubcommand.tick(bot.level().getServer());
            require(context, MinecraftAiVerifySubcommand.resultDetailForGameTest(bot.getUUID(), feature)
                            .filter(detail -> detail.endsWith(StripMineTask.RETIRED_REJECTION))
                            .isPresent(),
                    "verifier did not accept the exact typed rejection as coverage PASS");

            MinecraftAiVerifySubcommand.tick(bot.level().getServer());
            require(context, !MinecraftAiVerifySubcommand.hasRunForGameTest(bot.getUUID()),
                    "typed rejection verifier remained registered after summary");
        } finally {
            cleanupBot(context, bot, "VerifyStripGateGT");
        }
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void fromZeroMiningDeathFailsInOneVerifierPollAndCancelsAllIntent(GameTestHelper context) {
        var world = context.getLevel();
        var server = world.getServer();
        String botName = "VerifyDeathFastGT";
        // EMPTY_STRUCTURE is placed near the world's bottom. The from-zero planner correctly
        // treats that as an underground resume and refuses to invent surface supplies, so build a
        // small sky-visible platform at ordinary overworld height for this acceptance-entry test.
        BlockPos spawn = context.absolutePos(new BlockPos(1, 126, 1));
        prepareCell(world, spawn);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        server, botName, world, Vec3.atBottomCenterOf(spawn),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + botName));

        try {
            verifyFeature(context, bot, "diamond_stack_64_from_zero");
            verifyFeature(context, bot, "obsidian_half_stack_32_from_zero");
        } finally {
            MinecraftAiVerifySubcommand.discardRunForGameTest(bot.getUUID());
            IntentController.INSTANCE.cancelAll(
                    bot, IntentController.ControlOrigin.SYSTEM, "verify_fail_fast_gametest_cleanup");
            AIPlayerManager.INSTANCE.despawn(server, botName);
        }
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void fromZeroMiningNonSurvivalModeFailsInOneVerifierPollAndClearsAudit(GameTestHelper context) {
        AIPlayerEntity bot = spawnBot(context, "VerifyModeFastGT");
        try {
            String feature = "diamond_stack_64_from_zero";
            require(context, MinecraftAiVerifySubcommand.startForGameTest(
                    bot.level().getServer().createCommandSourceStack(), bot, feature), "verifier run did not start");
            MinecraftAiVerifySubcommand.tick(bot.level().getServer());
            require(context, MiningEvidenceAudit.hasSession(bot.getUUID()),
                    "from-zero verifier did not open provenance audit");

            bot.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
            MinecraftAiVerifySubcommand.tick(bot.level().getServer());
            require(context, MinecraftAiVerifySubcommand.resultDetailForGameTest(bot.getUUID(), feature)
                            .filter("mining_provenance_non_survival_mode"::equals).isPresent(),
                    "non-survival tick did not fail with typed provenance reason");
            require(context, !MiningEvidenceAudit.hasSession(bot.getUUID()),
                    "terminal mode violation leaked provenance audit state");
        } finally {
            bot.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
            cleanupBot(context, bot, "VerifyModeFastGT");
        }
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void fromZeroMiningAllowedPrivilegeFailsInOneVerifierPollAndClearsAudit(GameTestHelper context) {
        AIPlayerEntity bot = spawnBot(context, "VerifyPrivilegeFastGT");
        try {
            String feature = "obsidian_half_stack_32_from_zero";
            require(context, MinecraftAiVerifySubcommand.startForGameTest(
                    bot.level().getServer().createCommandSourceStack(), bot, feature), "verifier run did not start");
            MinecraftAiVerifySubcommand.tick(bot.level().getServer());
            MiningEvidenceAudit.recordCapabilityDecision(bot, true);
            MinecraftAiVerifySubcommand.tick(bot.level().getServer());
            require(context, MinecraftAiVerifySubcommand.resultDetailForGameTest(bot.getUUID(), feature)
                            .filter("mining_provenance_privileged_allowed"::equals).isPresent(),
                    "allowed privilege did not fail with typed provenance reason");
            require(context, !MiningEvidenceAudit.hasSession(bot.getUUID()),
                    "terminal privilege violation leaked provenance audit state");
        } finally {
            cleanupBot(context, bot, "VerifyPrivilegeFastGT");
        }
        context.succeed();
    }

    private static void verifyFeature(GameTestHelper context, AIPlayerEntity bot, String feature) {
        var server = bot.level().getServer();
        require(context, MinecraftAiVerifySubcommand.startForGameTest(
                server.createCommandSourceStack(), bot, feature), feature + " verifier run did not start");

        // First poll starts the real from-zero scenario and installs its Result fail-fast hook.
        MinecraftAiVerifySubcommand.tick(server);
        require(context, GoalExecutor.INSTANCE.hasActivePlan(bot), feature + " goal was not active");
        require(context, GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.DIRT, 1)),
                feature + " queued intent setup failed");
        require(context, GoalExecutor.INSTANCE.queuedGoalCount(bot) == 1,
                feature + " did not establish queued intent");

        // Mirror the runtime ordering: the death counter advances and the ordinary Mission is
        // suspended for recovery before the verifier sees the next server tick.
        bot.awardStat(Stats.CUSTOM.get(Stats.DEATHS), 1);
        RuntimeLifecycleCoordinator.INSTANCE.onBotDeath(bot);
        require(context, GoalExecutor.INSTANCE.hasActivePlan(bot),
                feature + " ordinary death suspension disappeared before verifier polling");

        // One poll must terminate acceptance immediately; it must not wait for recovery/replanning
        // or for either scenario's long outer timeout (diamond is derived from its live plan).
        MinecraftAiVerifySubcommand.tick(server);
        require(context, MinecraftAiVerifySubcommand.resultDetailForGameTest(bot.getUUID(), feature)
                        .filter(ZERO_DEATH_VIOLATION::equals).isPresent(),
                feature + " did not record typed zero_death_violation");
        require(context, !GoalExecutor.INSTANCE.hasActivePlan(bot),
                feature + " left the acceptance goal suspended/active");
        require(context, GoalExecutor.INSTANCE.queuedGoalCount(bot) == 0,
                feature + " left queued intent available for replanning");
        require(context, TaskManager.INSTANCE.getActive(bot).isEmpty(),
                feature + " left an active task after fail-fast cancellation");
        require(context, !bot.getActionPack().hasActiveActions(),
                feature + " left physical actions running after fail-fast cancellation");

        // The following poll only emits the summary and removes the completed verifier run.
        MinecraftAiVerifySubcommand.tick(server);
        require(context, !MinecraftAiVerifySubcommand.hasRunForGameTest(bot.getUUID()),
                feature + " verifier run remained registered after failure");
    }

    private static AIPlayerEntity spawnBot(GameTestHelper context, String botName) {
        var world = context.getLevel();
        BlockPos spawn = context.absolutePos(new BlockPos(1, 126, 1));
        prepareCell(world, spawn);
        return AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), botName, world, Vec3.atBottomCenterOf(spawn),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + botName));
    }

    private static void cleanupBot(GameTestHelper context, AIPlayerEntity bot, String botName) {
        MinecraftAiVerifySubcommand.discardRunForGameTest(bot.getUUID());
        IntentController.INSTANCE.cancelAll(
                bot, IntentController.ControlOrigin.SYSTEM, "verify_fail_fast_gametest_cleanup");
        AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), botName);
    }

    private static void prepareCell(net.minecraft.server.level.ServerLevel world, BlockPos center) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                world.setBlock(center.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                world.setBlock(center.offset(dx, 0, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                world.setBlock(center.offset(dx, 1, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }

    private static int count(List<String> values, String expected) {
        int count = 0;
        for (String value : values) {
            if (expected.equals(value)) {
                count++;
            }
        }
        return count;
    }
}

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
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.stat.Stats;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.List;
import net.minecraft.text.Text;

/** World-backed contract for strict from-zero acceptance fail-fast boundaries. */
public final class MinecraftAiVerifyFailFastGameTests {
    private static final String ZERO_DEATH_VIOLATION = "zero_death_violation";

    @GameTest(maxTicks = 5)
    public void verifyAllExpandsLegacyStripMineByOperatingProfile(TestContext context) {
        List<String> strictAll = MinecraftAiVerifySubcommand.expandFeaturesForGameTest(
                List.of("all"), OperatingProfile.STRICT_SURVIVAL);
        List<String> operatorAll = MinecraftAiVerifySubcommand.expandFeaturesForGameTest(
                List.of("all"), OperatingProfile.OPERATOR);

        require(context, !strictAll.contains("strip_mine"),
                "strict all retained the legacy strip_mine success scenario");
        require(context, count(strictAll, MinecraftAiVerifySubcommand.STRICT_STRIP_MINE_REJECTION_FEATURE) == 1,
                "strict all did not include exactly one typed strip_mine rejection scenario");
        require(context, operatorAll.contains("strip_mine"),
                "operator all lost the legacy strip_mine success scenario");
        require(context, !operatorAll.contains(MinecraftAiVerifySubcommand.STRICT_STRIP_MINE_REJECTION_FEATURE),
                "operator all incorrectly selected the strict rejection scenario");
        require(context, strictAll.size() == operatorAll.size(),
                "profile expansion silently changed verify-all coverage cardinality");

        require(context, MinecraftAiVerifySubcommand.expandFeaturesForGameTest(
                        List.of("strip_mine"), OperatingProfile.STRICT_SURVIVAL)
                        .equals(List.of(MinecraftAiVerifySubcommand.STRICT_STRIP_MINE_REJECTION_FEATURE)),
                "explicit strict strip_mine did not map to the typed rejection check");
        require(context, MinecraftAiVerifySubcommand.expandFeaturesForGameTest(
                        List.of("strip_mine"), OperatingProfile.OPERATOR)
                        .equals(List.of("strip_mine")),
                "explicit operator strip_mine no longer maps to the legacy success check");
        require(context, count(MinecraftAiVerifySubcommand.expandFeaturesForGameTest(
                        List.of("all+strip_mine"), OperatingProfile.STRICT_SURVIVAL),
                        MinecraftAiVerifySubcommand.STRICT_STRIP_MINE_REJECTION_FEATURE) == 1,
                "composed strict suites duplicated the typed rejection scenario");
        context.complete();
    }

    @GameTest(maxTicks = 20)
    public void strictStripMineScenarioRequiresExactTypedRejection(TestContext context) {
        AIPlayerEntity bot = spawnBot(context, "VerifyStripGateGT");
        String feature = MinecraftAiVerifySubcommand.STRICT_STRIP_MINE_REJECTION_FEATURE;
        try {
            require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                    "typed rejection fixture is not running under strict_survival");
            require(context, MinecraftAiVerifySubcommand.startForGameTest(
                            bot.getEntityWorld().getServer().getCommandSource(), bot, feature),
                    "strict strip_mine rejection verifier did not start");

            MinecraftAiVerifySubcommand.tick(bot.getEntityWorld().getServer());
            require(context, StripMineTask.STRICT_SURVIVAL_REJECTION.equals(
                            TaskManager.INSTANCE.status(bot).failureReason()),
                    "real StripMineTask did not emit the exact typed rejection");

            MinecraftAiVerifySubcommand.tick(bot.getEntityWorld().getServer());
            require(context, MinecraftAiVerifySubcommand.resultDetailForGameTest(bot.getUuid(), feature)
                            .filter(detail -> detail.endsWith(StripMineTask.STRICT_SURVIVAL_REJECTION))
                            .isPresent(),
                    "verifier did not accept the exact typed rejection as coverage PASS");

            MinecraftAiVerifySubcommand.tick(bot.getEntityWorld().getServer());
            require(context, !MinecraftAiVerifySubcommand.hasRunForGameTest(bot.getUuid()),
                    "typed rejection verifier remained registered after summary");
        } finally {
            cleanupBot(context, bot, "VerifyStripGateGT");
        }
        context.complete();
    }

    @GameTest(maxTicks = 20)
    public void fromZeroMiningDeathFailsInOneVerifierPollAndCancelsAllIntent(TestContext context) {
        var world = context.getWorld();
        var server = world.getServer();
        String botName = "VerifyDeathFastGT";
        // EMPTY_STRUCTURE is placed near the world's bottom. The from-zero planner correctly
        // treats that as an underground resume and refuses to invent surface supplies, so build a
        // small sky-visible platform at ordinary overworld height for this acceptance-entry test.
        BlockPos spawn = context.getAbsolutePos(new BlockPos(1, 126, 1));
        prepareCell(world, spawn);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        server, botName, world, Vec3d.ofBottomCenter(spawn),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + botName));

        try {
            verifyFeature(context, bot, "diamond_stack_64_from_zero");
            verifyFeature(context, bot, "obsidian_half_stack_32_from_zero");
        } finally {
            MinecraftAiVerifySubcommand.discardRunForGameTest(bot.getUuid());
            IntentController.INSTANCE.cancelAll(
                    bot, IntentController.ControlOrigin.SYSTEM, "verify_fail_fast_gametest_cleanup");
            AIPlayerManager.INSTANCE.despawn(server, botName);
        }
        context.complete();
    }

    @GameTest(maxTicks = 20)
    public void fromZeroMiningNonSurvivalModeFailsInOneVerifierPollAndClearsAudit(TestContext context) {
        AIPlayerEntity bot = spawnBot(context, "VerifyModeFastGT");
        try {
            String feature = "diamond_stack_64_from_zero";
            require(context, MinecraftAiVerifySubcommand.startForGameTest(
                    bot.getEntityWorld().getServer().getCommandSource(), bot, feature), "verifier run did not start");
            MinecraftAiVerifySubcommand.tick(bot.getEntityWorld().getServer());
            require(context, MiningEvidenceAudit.hasSession(bot.getUuid()),
                    "from-zero verifier did not open provenance audit");

            bot.interactionManager.changeGameMode(GameMode.CREATIVE);
            MinecraftAiVerifySubcommand.tick(bot.getEntityWorld().getServer());
            require(context, MinecraftAiVerifySubcommand.resultDetailForGameTest(bot.getUuid(), feature)
                            .filter("mining_provenance_non_survival_mode"::equals).isPresent(),
                    "non-survival tick did not fail with typed provenance reason");
            require(context, !MiningEvidenceAudit.hasSession(bot.getUuid()),
                    "terminal mode violation leaked provenance audit state");
        } finally {
            bot.interactionManager.changeGameMode(GameMode.SURVIVAL);
            cleanupBot(context, bot, "VerifyModeFastGT");
        }
        context.complete();
    }

    @GameTest(maxTicks = 20)
    public void fromZeroMiningAllowedPrivilegeFailsInOneVerifierPollAndClearsAudit(TestContext context) {
        AIPlayerEntity bot = spawnBot(context, "VerifyPrivilegeFastGT");
        try {
            String feature = "obsidian_half_stack_32_from_zero";
            require(context, MinecraftAiVerifySubcommand.startForGameTest(
                    bot.getEntityWorld().getServer().getCommandSource(), bot, feature), "verifier run did not start");
            MinecraftAiVerifySubcommand.tick(bot.getEntityWorld().getServer());
            MiningEvidenceAudit.recordCapabilityDecision(bot, true);
            MinecraftAiVerifySubcommand.tick(bot.getEntityWorld().getServer());
            require(context, MinecraftAiVerifySubcommand.resultDetailForGameTest(bot.getUuid(), feature)
                            .filter("mining_provenance_privileged_allowed"::equals).isPresent(),
                    "allowed privilege did not fail with typed provenance reason");
            require(context, !MiningEvidenceAudit.hasSession(bot.getUuid()),
                    "terminal privilege violation leaked provenance audit state");
        } finally {
            cleanupBot(context, bot, "VerifyPrivilegeFastGT");
        }
        context.complete();
    }

    private static void verifyFeature(TestContext context, AIPlayerEntity bot, String feature) {
        var server = bot.getEntityWorld().getServer();
        require(context, MinecraftAiVerifySubcommand.startForGameTest(
                server.getCommandSource(), bot, feature), feature + " verifier run did not start");

        // First poll starts the real from-zero scenario and installs its Result fail-fast hook.
        MinecraftAiVerifySubcommand.tick(server);
        require(context, GoalExecutor.INSTANCE.hasActivePlan(bot), feature + " goal was not active");
        require(context, GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.DIRT, 1)),
                feature + " queued intent setup failed");
        require(context, GoalExecutor.INSTANCE.queuedGoalCount(bot) == 1,
                feature + " did not establish queued intent");

        // Mirror the runtime ordering: the death counter advances and the ordinary Mission is
        // suspended for recovery before the verifier sees the next server tick.
        bot.increaseStat(Stats.CUSTOM.getOrCreateStat(Stats.DEATHS), 1);
        RuntimeLifecycleCoordinator.INSTANCE.onBotDeath(bot);
        require(context, GoalExecutor.INSTANCE.hasActivePlan(bot),
                feature + " ordinary death suspension disappeared before verifier polling");

        // One poll must terminate acceptance immediately; it must not wait for recovery/replanning
        // or for either scenario's long outer timeout (diamond is derived from its live plan).
        MinecraftAiVerifySubcommand.tick(server);
        require(context, MinecraftAiVerifySubcommand.resultDetailForGameTest(bot.getUuid(), feature)
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
        require(context, !MinecraftAiVerifySubcommand.hasRunForGameTest(bot.getUuid()),
                feature + " verifier run remained registered after failure");
    }

    private static AIPlayerEntity spawnBot(TestContext context, String botName) {
        var world = context.getWorld();
        BlockPos spawn = context.getAbsolutePos(new BlockPos(1, 126, 1));
        prepareCell(world, spawn);
        return AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), botName, world, Vec3d.ofBottomCenter(spawn),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + botName));
    }

    private static void cleanupBot(TestContext context, AIPlayerEntity bot, String botName) {
        MinecraftAiVerifySubcommand.discardRunForGameTest(bot.getUuid());
        IntentController.INSTANCE.cancelAll(
                bot, IntentController.ControlOrigin.SYSTEM, "verify_fail_fast_gametest_cleanup");
        AIPlayerManager.INSTANCE.despawn(context.getWorld().getServer(), botName);
    }

    private static void prepareCell(net.minecraft.server.world.ServerWorld world, BlockPos center) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                world.setBlockState(center.add(dx, -1, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS);
                world.setBlockState(center.add(dx, 0, dz), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                world.setBlockState(center.add(dx, 1, dz), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
            }
        }
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(Text.of(message));
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

package io.github.zoyluo.minecraftai.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.gametest.GameTestCleanup;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.memory.BotMemoryStore;
import io.github.zoyluo.minecraftai.perception.PerceptionSnapshot;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import io.github.zoyluo.minecraftai.task.AbstractTask;
import io.github.zoyluo.minecraftai.task.TaskManager;
import io.github.zoyluo.minecraftai.task.TaskState;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The brain against a model service that fails, end to end through the real {@link BrainCoordinator},
 * the real HTTP client and the real retry runner, with a loopback server standing in for the provider.
 *
 * <p>What a five-minute outage ends in cannot be waited for in a test, so the final-failure tests
 * deliver that failure to the coordinator directly ({@link BrainCoordinator#deliverFinalFailureForTest})
 * and assert what the player is told and what the bot does next against the live runtime state.</p>
 *
 * <p>The test server ticks much faster than real time while a retry waits in wall-clock time, so the
 * tick budgets of the end-to-end tests are far larger than the work needs.</p>
 */
public final class BrainApiFailureGameTests {
    private static final LlmApiException OUTAGE = new LlmApiException(
            "server_error: status=503 body=high demand", LlmApiException.Kind.TRANSIENT, 503, null, null);
    private static final String OUTAGE_TEXT = "overloaded or unavailable";

    @GameTest(maxTicks = 2000)
    public void unusableReplyIsRepairedWithOneMoreMeteredCall(GameTestHelper context) {
        // The 200 with an empty choice list is the service's, not an outage: one repair call, paid for
        // from the instruction's budget, tells the model what was wrong and gets the answer.
        Harness harness = Harness.start(context, "ApiRepairGT", Reply.unusable(), Reply.answer("Four."));
        harness.ask("What is two plus two?");

        context.onEachTick(() -> harness.whenAnswered("Four.", () -> {
            require(context, harness.service.requests() == 2,
                    "expected the call and exactly one repair, saw " + harness.service.requests() + " requests");
            require(context, harness.service.bodies().get(1).contains("could not be used"),
                    "the repair call must tell the model its reply was unusable: " + harness.service.bodies().get(1));
            require(context, BrainCoordinator.INSTANCE.modelCallsUsedForTest(harness.bot) == 2,
                    "a repair is a planner call and must spend one of the instruction's calls, spent "
                            + BrainCoordinator.INSTANCE.modelCallsUsedForTest(harness.bot));
            require(context, !harness.said("usable answer"),
                    "a repaired reply must not be reported to the player as a failure");
            context.succeed();
        }));
    }

    @GameTest(maxTicks = 2000)
    public void secondUnusableReplyInARowIsReportedInsteadOfAskedForAgain(GameTestHelper context) {
        // A standing fault (a proxy's error page, a broken schema) must not burn the budget in a burst.
        Harness harness = Harness.start(context, "ApiRepairTwiceGT",
                Reply.unusable(), Reply.unusable(), Reply.answer("Four."));
        harness.ask("What is two plus two?");

        context.onEachTick(() -> harness.whenSettled(() -> harness.said("usable answer"), () -> {
            require(context, harness.service.requests() == 2,
                    "one call and one repair, then the report: saw " + harness.service.requests() + " requests");
            require(context, BrainCoordinator.INSTANCE.modelCallsUsedForTest(harness.bot) == 2,
                    "the repair is the only extra call");
            require(context, !harness.said("Four."), "the third scripted reply must never be asked for");
            context.succeed();
        }));
    }

    @GameTest(maxTicks = 18000)
    public void transientErrorsReplayTheSameRequestAndSpendOneModelCall(GameTestHelper context) {
        // The 2026-10-05 burst, shortened: overload twice, then the answer. The client's own retry is
        // configured to sleep a minute, so passing in seconds also proves it does not stack under the brain's.
        Harness harness = Harness.start(context, "ApiBurstGT",
                Reply.status(503), Reply.status(503), Reply.answer("Four."));
        harness.ask("What is two plus two?");

        context.onEachTick(() -> harness.whenAnswered("Four.", () -> {
            List<String> bodies = harness.service.bodies();
            require(context, bodies.size() == 3, "expected 3 identical requests, saw " + bodies.size());
            require(context, bodies.get(0).equals(bodies.get(1)) && bodies.get(1).equals(bodies.get(2)),
                    "a retry must replay the identical request context, not a rebuilt or extended one");
            require(context, BrainCoordinator.INSTANCE.modelCallsUsedForTest(harness.bot) == 1,
                    "transient retries must not spend the model-call budget, spent "
                            + BrainCoordinator.INSTANCE.modelCallsUsedForTest(harness.bot));
            require(context, !harness.said(OUTAGE_TEXT), "an outage that ended must not be reported to the player");
            context.succeed();
        }));
    }

    @GameTest(maxTicks = 2000)
    public void rejectedRequestFailsFastAndTellsThePlayerTheServiceFailed(GameTestHelper context) {
        Harness harness = Harness.start(context, "ApiRejectGT", Reply.status(400));
        harness.ask("What is two plus two?");

        context.onEachTick(() -> harness.whenSettled(() -> harness.said("usable answer"), () -> {
            require(context, harness.service.requests() == 1,
                    "a rejected request must not be sent again, saw " + harness.service.requests() + " requests");
            require(context, BrainCoordinator.INSTANCE.modelCallsUsedForTest(harness.bot) == 1,
                    "one request, one call");
            require(context, !harness.said("work out") && !harness.said("another way"),
                    "the player must not be told the request was unclear");
            context.succeed();
        }));
    }

    @GameTest(maxTicks = 18000)
    public void newMessageEndsTheWaitForTheOldRequest(GameTestHelper context) {
        // The first request is refused with a 503 and its retry is waiting out a backoff when the player
        // says something else: the old request must never be sent again, only the new one.
        Harness harness = Harness.start(context, "ApiCancelGT", Reply.status(503), Reply.answer("Six."));
        harness.ask("What is two plus two?");
        AtomicBoolean replaced = new AtomicBoolean();
        AtomicLong answeredAtNanos = new AtomicLong();

        context.onEachTick(() -> {
            if (!replaced.get()) {
                if (harness.service.requests() >= 1) {
                    harness.ask("Never mind, what is three plus three?");
                    replaced.set(true);
                }
                return;
            }
            harness.whenAnswered("Six.", () -> {
                // The old request's wait is at most its first backoff, which is measured in wall-clock time.
                answeredAtNanos.compareAndSet(0L, System.nanoTime());
                long outlast = Duration.ofMillis(LlmRetryPolicy.INITIAL_BACKOFF_MS + 500L).toNanos();
                if (System.nanoTime() - answeredAtNanos.get() < outlast) {
                    return;
                }
                require(context, harness.service.requests() == 2,
                        "the superseded request was sent again: " + harness.service.requests() + " requests");
                require(context, BrainCoordinator.INSTANCE.modelCallsUsedForTest(harness.bot) == 1,
                        "only the new instruction's call may be counted");
                context.succeed();
            });
        });
    }

    @GameTest(maxTicks = 100)
    public void finalOutageIsSilentOnlyWhenTheRequestDemonstrablyFinished(GameTestHelper context) {
        Harness harness = Harness.start(context, "ApiSilenceGT");
        GameTestCleanup.whenFinished(context, () -> BotMemoryStore.INSTANCE.of(harness.bot.getUUID()).clearGoal());
        AtomicInteger phase = new AtomicInteger();
        AtomicBoolean finished = new AtomicBoolean();

        context.onEachTick(() -> {
            AIPlayerEntity bot = harness.bot;
            switch (phase.get()) {
                case 0 -> {
                    // The task finished successfully and only the closing words were lost.
                    TaskManager.INSTANCE.assign(bot, new OneShotTask(true), origin());
                    phase.set(1);
                }
                case 1 -> {
                    if (TaskManager.INSTANCE.getActive(bot).isEmpty()
                            && TaskManager.INSTANCE.status(bot).state() == TaskState.COMPLETED) {
                        harness.freshInstruction();
                        BrainCoordinator.INSTANCE.deliverFinalFailureForTest(bot, OUTAGE, true);
                        require(context, !harness.said(OUTAGE_TEXT),
                                "a finished request must not be followed by a failure text");
                        // The same outage, when the request never started: the player must hear it.
                        harness.freshInstruction();
                        BrainCoordinator.INSTANCE.deliverFinalFailureForTest(bot, OUTAGE, false);
                        require(context, harness.said(OUTAGE_TEXT),
                                "an unstarted request must be told the service is unavailable");
                        // A task failure still waiting to be reported is not a finished request.
                        harness.freshInstruction();
                        TaskManager.INSTANCE.recordFailure(bot, "gather", "no_logs_found", 1);
                        BrainCoordinator.INSTANCE.deliverFinalFailureForTest(bot, OUTAGE, true);
                        require(context, harness.said(OUTAGE_TEXT), "a failed request must hear the truth");
                        TaskManager.INSTANCE.consumeFailure(bot);
                        // An unfinished long-term goal means an idle bot is stalled, not done.
                        harness.freshInstruction();
                        BotMemoryStore.INSTANCE.of(bot.getUUID()).setGoal("Build a hut", List.of("gather logs"));
                        BrainCoordinator.INSTANCE.deliverFinalFailureForTest(bot, OUTAGE, true);
                        require(context, harness.said(OUTAGE_TEXT), "an unfinished goal must hear the truth");
                        BotMemoryStore.INSTANCE.of(bot.getUUID()).clearGoal();
                        // Work still running is not a finished request either.
                        harness.freshInstruction();
                        TaskManager.INSTANCE.assign(bot, new OneShotTask(false), origin());
                        BrainCoordinator.INSTANCE.deliverFinalFailureForTest(bot, OUTAGE, true);
                        require(context, harness.said(OUTAGE_TEXT), "running work must hear the truth");
                        finished.set(true);
                    }
                }
                default -> {
                }
            }
            if (finished.get()) {
                context.succeed();
            }
        });
    }

    @GameTest(maxTicks = 1000)
    public void finalOutageHoldsOffTheNextGoalWakeInsteadOfStartingAnotherRetryCycle(GameTestHelper context) {
        // Without the hold, the idle watcher wakes the brain for the unfinished goal ten seconds later and
        // starts another five-minute retry cycle against the same outage.
        Harness harness = Harness.start(context, "ApiWakeGT", Reply.answer("Working on it."));
        AIPlayerEntity bot = harness.bot;
        BotMemoryStore.INSTANCE.of(bot.getUUID()).setGoal("Build a hut", List.of("gather logs"));
        GameTestCleanup.whenFinished(context, () -> BotMemoryStore.INSTANCE.of(bot.getUUID()).clearGoal());

        harness.freshInstruction();
        BrainCoordinator.INSTANCE.deliverFinalFailureForTest(bot, OUTAGE, false);
        boolean wokeDuringHold = BrainCoordinator.INSTANCE.maybeWakeForFailureOrGoal(bot);
        require(context, !wokeDuringHold, "a goal wake right after a final failure must wait");
        require(context, !BrainCoordinator.INSTANCE.status(bot).busy(), "no request may be in flight during the hold");
        require(context, harness.service.requests() == 0, "the held-off wake must not reach the service");

        // The player's next message clears every wake source; the same bot then wakes for its goal.
        BrainCoordinator.INSTANCE.clearIntentWakeSources(bot);
        require(context, BrainCoordinator.INSTANCE.maybeWakeForFailureOrGoal(bot),
                "control: without the hold this bot does wake for its unfinished goal");
        context.onEachTick(() -> {
            if (harness.service.requests() >= 1) {
                context.succeed();
            }
        });
    }

    @GameTest(maxTicks = 4000)
    public void aServiceThatKeepsFailingTellsTheWaitingPlayerOnceThatTheBotIsStillTrying(GameTestHelper context) {
        // A refusal that asks for a ten second wait: the wait that makes the player's request unanswered for ten
        // seconds is the moment to say so, at once, not when the retry finally lands.
        Harness harness = Harness.start(context, "ApiNoticeGT", Reply.status(503).retryAfter(10), Reply.answer("Four."));
        harness.ask("What is two plus two?");

        context.onEachTick(() -> {
            if (!harness.said(ApiFailureReport.stillTryingMessage())) {
                return;
            }
            require(context, harness.service.requests() == 1, "the retry is still waiting: " + harness.service.requests());
            // A later failed attempt of the same request would announce it again; the player hears it once.
            BrainCoordinator.INSTANCE.deliverRetryNoticeForTest(harness.bot, OUTAGE, false);
            BrainCoordinator.INSTANCE.deliverRetryNoticeForTest(harness.bot, OUTAGE, false);
            require(context, harness.count(ApiFailureReport.stillTryingMessage()) == 1,
                    "the player must hear that the bot is still trying exactly once, heard it "
                            + harness.count(ApiFailureReport.stillTryingMessage()) + " times");
            require(context, !harness.said(OUTAGE_TEXT), "a request that is still being retried is not a failure");
            context.succeed();
        });
    }

    @GameTest(maxTicks = 400)
    public void aPlayerHearsOfARetryOnlyWhileHeWaitsForTheRequest(GameTestHelper context) {
        Harness harness = Harness.start(context, "ApiNoNoticeGT");
        TaskManager.INSTANCE.assign(harness.bot, new OneShotTask(true), origin());
        AtomicBoolean checked = new AtomicBoolean();

        context.onEachTick(() -> {
            if (checked.get() || TaskManager.INSTANCE.getActive(harness.bot).isPresent()
                    || TaskManager.INSTANCE.status(harness.bot).state() != TaskState.COMPLETED) {
                return;
            }
            checked.set(true);
            // The task finished and the call that would have worded it keeps failing: nothing to tell the player.
            harness.freshInstruction();
            BrainCoordinator.INSTANCE.deliverRetryNoticeForTest(harness.bot, OUTAGE, true);
            require(context, !harness.said(ApiFailureReport.stillTryingMessage()),
                    "a finished request must not be followed by a notice about its closing call");
            // Control: the same wait for a request that never started is one the player is waiting on.
            harness.freshInstruction();
            BrainCoordinator.INSTANCE.deliverRetryNoticeForTest(harness.bot, OUTAGE, false);
            require(context, harness.said(ApiFailureReport.stillTryingMessage()),
                    "a request that never started has a player waiting for it");
            context.succeed();
        });
    }

    @GameTest(maxTicks = 2000)
    public void aKeyGoogleRejectsWithABadRequestFailsFastAsACredentialsProblem(GameTestHelper context) {
        Harness harness = Harness.start(context, "ApiKeyGT", Reply.apiKeyRejected());
        harness.ask("What is two plus two?");

        context.onEachTick(() -> harness.whenSettled(() -> harness.said("credentials"), () -> {
            require(context, harness.service.requests() == 1,
                    "a rejected key must not be sent again, saw " + harness.service.requests() + " requests");
            require(context, harness.said("API key"), "the player is told who has to fix it: " + harness.transcript());
            require(context, !harness.said("usable answer"), "a rejected key is not a garbled reply");
            context.succeed();
        }));
    }

    @GameTest(maxTicks = 8000)
    public void expiredGeminiInteractionContinuesInAFreshOneAndSpendsOneMoreCall(GameTestHelper context) {
        // The first reply asks for a read-only tool, so the brain continues the stored interaction with its
        // result. The service no longer has that interaction (it keeps them for a limited time): the request
        // can never succeed, so the conversation carries on in a fresh interaction.
        Harness harness = Harness.startGemini(context, "ApiStaleGT",
                Reply.geminiTool("interaction-1", "inventory", "{}"),
                Reply.geminiNotFound(),
                Reply.geminiAnswer("interaction-2", "Four."));
        harness.ask("What is two plus two?");

        context.onEachTick(() -> harness.whenAnswered("Four.", () -> {
            List<String> bodies = harness.service.bodies();
            require(context, bodies.size() == 3, "expected the call, the rejected continuation and the fresh call, saw " + bodies.size());
            require(context, !bodies.get(0).contains("previous_interaction_id"), "the first call starts an interaction");
            require(context, bodies.get(1).contains("\"previous_interaction_id\":\"interaction-1\""),
                    "the second call continues the first interaction: " + bodies.get(1));
            require(context, !bodies.get(2).contains("previous_interaction_id"),
                    "the recovery must not name the interaction the service lost: " + bodies.get(2));
            require(context, bodies.get(2).contains("fresh start") && bodies.get(2).contains("inventory"),
                    "the fresh interaction must say it starts over and carry the last tool results: " + bodies.get(2));
            require(context, BrainCoordinator.INSTANCE.modelCallsUsedForTest(harness.bot) == 3,
                    "the recovery is one more planner call, spent " + BrainCoordinator.INSTANCE.modelCallsUsedForTest(harness.bot));
            require(context, !harness.said("usable answer") && !harness.said(OUTAGE_TEXT),
                    "a recovered conversation must not be reported as a failure: " + harness.transcript());
            context.succeed();
        }));
    }

    @GameTest(maxTicks = 8000)
    public void aFreshGeminiInteractionThatIsRejectedTooIsReportedNotRetried(GameTestHelper context) {
        Harness harness = Harness.startGemini(context, "ApiStaleTwiceGT",
                Reply.geminiTool("interaction-1", "inventory", "{}"),
                Reply.geminiNotFound(), Reply.geminiNotFound(), Reply.geminiAnswer("interaction-3", "Four."));
        harness.ask("What is two plus two?");

        context.onEachTick(() -> harness.whenSettled(() -> harness.said("usable answer"), () -> {
            require(context, harness.service.requests() == 3,
                    "the call, the rejected continuation and one fresh call, then the report: " + harness.service.requests());
            require(context, !harness.said("Four."), "the fourth scripted reply must never be asked for");
            context.succeed();
        }));
    }

    @GameTest(maxTicks = 400)
    public void finalOutageAfterAPlanWithFurtherStepsIsNotSilent(GameTestHelper context) {
        planThenFinishedTask(context, "ApiMoreStepsGT", true,
                harness -> require(context, harness.said(OUTAGE_TEXT),
                        "the first task of a plan with further steps finished, and nothing will start the next one: "
                                + "the player must be told"));
    }

    @GameTest(maxTicks = 400)
    public void finalOutageAfterASingleTaskPlanStaysSilent(GameTestHelper context) {
        planThenFinishedTask(context, "ApiSingleStepGT", false,
                harness -> require(context, !harness.said(OUTAGE_TEXT),
                        "the plan had no further step, so only the closing words were lost: " + harness.transcript()));
    }

    /**
     * Announces a plan through the real coordinator, lets its one task finish, then fails the model for good
     * the way a five-minute outage does, and hands the harness to {@code check}.
     */
    private static void planThenFinishedTask(GameTestHelper context, String botName, boolean moreSteps,
                                             Consumer<Harness> check) {
        Harness harness = Harness.start(context, botName, Reply.plan("I will gather logs for you.", moreSteps));
        harness.ask("Gather some logs");
        AtomicInteger phase = new AtomicInteger();

        context.onEachTick(() -> {
            if (phase.get() == 0 && harness.said("I will gather logs for you.")) {
                TaskManager.INSTANCE.assign(harness.bot, new OneShotTask(true), origin());
                phase.set(1);
            } else if (phase.get() == 1 && TaskManager.INSTANCE.getActive(harness.bot).isEmpty()
                    && TaskManager.INSTANCE.status(harness.bot).state() == TaskState.COMPLETED) {
                BrainCoordinator.INSTANCE.deliverFinalFailureForTest(harness.bot, OUTAGE, true);
                check.accept(harness);
                phase.set(2);
                context.succeed();
            }
        });
    }

    @GameTest(maxTicks = 18000)
    public void chatRouterRetriesWithoutHoldingAWorkerThreadAndAnswersOnTheServerThread(GameTestHelper context) {
        // Three chat lines at once, each refused once. The router has two worker threads: a retry that slept on
        // its thread (the client's own sleep-and-retry, configured here to a minute) would leave the third
        // line's first attempt waiting for a thread, so all three first attempts must reach the service at once.
        FakeModelService service = FakeModelService.start(
                Reply.status(503), Reply.status(503), Reply.status(503),
                Reply.routeTo("Alpha"), Reply.routeTo("Alpha"), Reply.routeTo("Alpha"));
        MinecraftAiConfig previous = MinecraftAiConfig.get();
        installConfig(previous.withLlm(new MinecraftAiConfig.Llm("test-key", service.baseUrl(), "fake-model",
                256, 0.0D, 10, 3, 60_000, Boolean.FALSE, "low")));
        ChatRecipientRouter.INSTANCE.configure(MinecraftAiConfig.get());
        GameTestCleanup.whenFinished(context, () -> {
            service.stop();
            installConfig(previous);
            ChatRecipientRouter.INSTANCE.configure(previous);
        });
        var sender = context.makeMockServerPlayerInLevel();
        List<ChatRecipientRouter.Candidate> candidates = List.of(
                routerCandidate("Alpha", UUID.fromString("00000000-0000-0000-0000-00000000000a")),
                routerCandidate("Bravo", UUID.fromString("00000000-0000-0000-0000-00000000000b")));
        AtomicInteger decisions = new AtomicInteger();
        AtomicBoolean offThread = new AtomicBoolean();
        long started = System.nanoTime();
        for (int line = 0; line < 3; line++) {
            ChatRecipientRouter.INSTANCE.select(sender, "gather wood please", candidates, decision -> {
                offThread.compareAndSet(false, !context.getLevel().getServer().isSameThread());
                require(context, decision.target() == ChatRecipientRouter.Target.BOT, "routed to " + decision);
                decisions.incrementAndGet();
            }, failure -> context.fail(Component.nullToEmpty("routing failed: " + failure)));
        }

        context.onEachTick(() -> {
            if (service.requests() >= 3 && service.requests() < 6) {
                require(context, System.nanoTime() - started < Duration.ofSeconds(10).toNanos(),
                        "the first attempts of all three lines must not wait for a sleeping worker");
            }
            if (decisions.get() == 3) {
                require(context, !offThread.get(), "a routing decision must be applied on the server thread");
                require(context, service.requests() == 6, "expected 3 refusals and 3 answers, saw " + service.requests());
                context.succeed();
            }
        });
    }

    private static ChatRecipientRouter.Candidate routerCandidate(String name, UUID id) {
        PerceptionSnapshot.Equipment equipment = new PerceptionSnapshot.Equipment(null, null, null, null, null, null);
        return new ChatRecipientRouter.Candidate(id, name, true, 4.0D, "Alpha".equals(name),
                new ChatRecipientRouter.CapabilitySummary(Map.of(), equipment, 20.0F, 20, 30, "none",
                        "on_foot", new ChatRecipientRouter.TaskSummary("idle", "IDLE", 0.0D)));
    }

    private static TaskOrigin origin() {

        return TaskOrigin.of(TaskOrigin.Kind.VERIFY, "api_failure_gametest");
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }

    /** A bot, a loopback model service and the swapped-in config, all undone when the test ends. */
    private static final class Harness {
        private final GameTestHelper context;
        private final AIPlayerEntity bot;
        private final FakeModelService service;

        private Harness(GameTestHelper context, AIPlayerEntity bot, FakeModelService service) {
            this.context = context;
            this.bot = bot;
            this.service = service;
        }

        static Harness start(GameTestHelper context, String botName, Reply... script) {
            return start(context, botName, "", script);
        }

        private static Harness start(GameTestHelper context, String botName, String basePath, Reply... script) {
            var world = context.getLevel();
            BlockPos spawn = context.absolutePos(new BlockPos(1, 126, 1));
            prepareCell(world, spawn);
            AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                            world.getServer(), botName, world, Vec3.atBottomCenterOf(spawn),
                            0.0F, 0.0F, GameType.SURVIVAL)
                    .orElseThrow(() -> new IllegalStateException("failed to spawn " + botName));
            FakeModelService service = FakeModelService.start(script);
            MinecraftAiConfig previous = MinecraftAiConfig.get();
            // retryCount 2 with a minute of backoff: if the planner client retried on its own under the
            // brain's retry, a single 503 would stall the test for a minute.
            MinecraftAiConfig.Llm llm = new MinecraftAiConfig.Llm("test-key", service.baseUrl() + basePath, "fake-model",
                    256, 0.0D, 10, 2, 60_000, Boolean.FALSE, "low");
            installConfig(previous.withLlm(llm));
            BrainCoordinator.INSTANCE.configure(MinecraftAiConfig.get());
            GameTestCleanup.whenFinished(context, () -> {
                service.stop();
                installConfig(previous);
                BrainCoordinator.INSTANCE.configure(previous);
                BrainCoordinator.INSTANCE.reset(bot);
                TaskManager.INSTANCE.cancelIntentTasks(bot, "api_failure_gametest_cleanup");
                AIPlayerManager.INSTANCE.despawn(world.getServer(), botName);
            });
            return new Harness(context, bot, service);
        }

        /** The same harness against the Gemini Interactions endpoint, which a loopback server can pose as. */
        static Harness startGemini(GameTestHelper context, String botName, Reply... script) {
            return start(context, botName, "/generativelanguage.googleapis.com/v1beta", script);
        }

        int count(String fragment) {
            String transcript = transcript();
            int found = 0;
            for (int at = transcript.indexOf(fragment); at >= 0; at = transcript.indexOf(fragment, at + 1)) {
                found++;
            }
            return found;
        }

        String transcript() {
            return ChatTranscript.renderRecentChat(bot.getUUID());
        }

        void ask(String text) {

            require(context, BrainCoordinator.INSTANCE.handleMessage(bot, "Tester", text),
                    "the coordinator did not take the instruction");
        }

        /** A new player instruction in the coordinator's own terms: a fresh conversation, no wake pending. */
        void freshInstruction() {
            BrainCoordinator.INSTANCE.reset(bot);
        }

        boolean said(String fragment) {
            return transcript().contains(fragment);
        }

        void whenAnswered(String answer, Runnable then) {
            whenSettled(() -> said(answer), then);
        }

        /** Runs {@code then} once the awaited reply is out and the coordinator has nothing in flight. */
        void whenSettled(BooleanSupplier replied, Runnable then) {
            if (replied.getAsBoolean() && !BrainCoordinator.INSTANCE.status(bot).busy()) {
                then.run();
            }
        }
    }

    private record Reply(int status, String body, int retryAfterSeconds) {
        Reply(int status, String body) {
            this(status, body, 0);
        }

        static Reply status(int status) {
            return new Reply(status, "{\"error\":{\"message\":\"scripted " + status + "\"}}");
        }

        /** The same reply with a Retry-After header: the service's own request for how long to wait. */
        Reply retryAfter(int seconds) {
            return new Reply(status, body, seconds);
        }

        /** Google's answer to a wrong API key: HTTP 400, not 401. */
        static Reply apiKeyRejected() {
            return new Reply(400, "{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\","
                    + "\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}");
        }

        /** An interaction the Gemini service answers with one function call. */
        static Reply geminiTool(String interactionId, String tool, String arguments) {
            return new Reply(200, "{\"id\":\"" + interactionId + "\",\"status\":\"completed\",\"steps\":[{\"type\":\"function_call\","
                    + "\"id\":\"call_" + interactionId + "\",\"name\":\"" + tool + "\",\"arguments\":" + arguments + "}]}");
        }

        static Reply geminiAnswer(String interactionId, String text) {
            JsonObject arguments = new JsonObject();
            arguments.addProperty("message", text);
            arguments.addProperty("purpose", "answer");
            return geminiTool(interactionId, "say", arguments.toString());
        }

        /** What the Gemini service answers for a stored interaction it no longer has. */
        static Reply geminiNotFound() {
            return new Reply(404, "{\"error\":{\"code\":404,\"message\":\"Interaction not found.\",\"status\":\"NOT_FOUND\"}}");
        }

        /** A plan announcement, optionally declaring that more steps follow its first task. */
        static Reply plan(String text, boolean moreSteps) {
            JsonObject arguments = new JsonObject();
            arguments.addProperty("message", text);
            arguments.addProperty("purpose", "plan");
            if (moreSteps) {
                arguments.addProperty("more_steps", true);
            }
            return toolCall("say", arguments);
        }

        /** The recipient router's required choice. */
        static Reply routeTo(String target) {
            JsonObject arguments = new JsonObject();
            arguments.addProperty("target", target);
            return toolCall("select_chat_recipient", arguments);
        }

        /** HTTP 200 whose choice list is empty: nothing the brain can use. */
        static Reply unusable() {
            return new Reply(200, "{\"choices\":[]}");
        }

        /** A well-formed completion in which the model answers with the say tool. */
        static Reply answer(String text) {
            JsonObject arguments = new JsonObject();
            arguments.addProperty("message", text);
            arguments.addProperty("purpose", "answer");
            return toolCall("say", arguments);
        }

        /** A well-formed chat completion whose one choice is a single call of {@code tool}. */
        private static Reply toolCall(String tool, JsonObject arguments) {
            JsonObject function = new JsonObject();
            function.addProperty("name", tool);
            function.addProperty("arguments", arguments.toString());
            JsonObject call = new JsonObject();
            call.addProperty("id", "call_1");
            call.addProperty("type", "function");
            call.add("function", function);
            JsonArray calls = new JsonArray();
            calls.add(call);
            JsonObject message = new JsonObject();
            message.addProperty("role", "assistant");
            message.add("tool_calls", calls);
            JsonObject choice = new JsonObject();
            choice.add("message", message);
            choice.addProperty("finish_reason", "tool_calls");
            JsonArray choices = new JsonArray();
            choices.add(choice);
            JsonObject root = new JsonObject();
            root.add("choices", choices);
            return new Reply(200, root.toString());
        }
    }

    /** A loopback stand-in for the provider: replies from a script, in order, and records every request body. */
    private static final class FakeModelService {
        private final HttpServer server;
        private final List<Reply> script;
        private final List<String> bodies = new CopyOnWriteArrayList<>();
        private final AtomicInteger arrivals = new AtomicInteger();

        private FakeModelService(HttpServer server, List<Reply> script) {
            this.server = server;
            this.script = script;
        }

        static FakeModelService start(Reply... replies) {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                FakeModelService service = new FakeModelService(server, List.of(replies));
                server.createContext("/", exchange -> {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    int index = service.arrivals.getAndIncrement();
                    service.bodies.add(body);
                    // An unscripted request is a bug in the code under test: answer it so it fails loudly there.
                    Reply reply = index < service.script.size() ? service.script.get(index) : Reply.status(500);
                    byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                    if (reply.retryAfterSeconds() > 0) {
                        exchange.getResponseHeaders().add("Retry-After", Integer.toString(reply.retryAfterSeconds()));
                    }
                    exchange.sendResponseHeaders(reply.status(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
                server.start();
                return service;
            } catch (IOException failure) {
                throw new IllegalStateException("cannot start the fake model service", failure);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        int requests() {
            return bodies.size();
        }

        List<String> bodies() {
            return bodies;
        }

        void stop() {
            server.stop(0);
        }
    }

    /** Finishes on its first tick, or runs forever, so the bot has real work to be (or not be) busy with. */
    private static final class OneShotTask extends AbstractTask {
        private final boolean finishes;

        OneShotTask(boolean finishes) {
            this.finishes = finishes;
        }

        @Override
        public String name() {
            return "api_failure_probe";
        }

        @Override
        public String describe() {
            return "api failure probe";
        }

        @Override
        public double progress() {
            return finishes ? 1.0D : 0.25D;
        }

        @Override
        protected void onStart(AIPlayerEntity bot) {
        }

        @Override
        protected void onTick(AIPlayerEntity bot) {
            if (finishes) {
                complete();
            }
        }
    }

    private static void installConfig(MinecraftAiConfig config) {
        try {
            Field instance = MinecraftAiConfig.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, config);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot swap the test config", failure);
        }
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
}

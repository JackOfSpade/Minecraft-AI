package io.github.zoyluo.minecraftai.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.gametest.GameTestCleanup;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.memory.BotMemoryStore;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

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

    @GameTest(maxTicks = 6000)
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

    @GameTest(maxTicks = 6000)
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
                long outlast = Duration.ofMillis(2L * LlmRetryPolicy.INITIAL_BACKOFF_MS).toNanos();
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
            MinecraftAiConfig.Llm llm = new MinecraftAiConfig.Llm("test-key", service.baseUrl(), "fake-model",
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

        void ask(String text) {
            require(context, BrainCoordinator.INSTANCE.handleMessage(bot, "Tester", text),
                    "the coordinator did not take the instruction");
        }

        /** A new player instruction in the coordinator's own terms: a fresh conversation, no wake pending. */
        void freshInstruction() {
            BrainCoordinator.INSTANCE.reset(bot);
        }

        boolean said(String fragment) {
            return ChatTranscript.renderRecentChat(bot.getUUID()).contains(fragment);
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

    private record Reply(int status, String body) {
        static Reply status(int status) {
            return new Reply(status, "{\"error\":{\"message\":\"scripted " + status + "\"}}");
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
            JsonObject function = new JsonObject();
            function.addProperty("name", "say");
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
                    int index = service.bodies.size();
                    service.bodies.add(body);
                    // An unscripted request is a bug in the code under test: answer it so it fails loudly there.
                    Reply reply = index < service.script.size() ? service.script.get(index) : Reply.status(500);
                    byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
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

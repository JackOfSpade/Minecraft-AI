package io.github.zoyluo.minecraftai.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.gametest.GameTestCleanup;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.memory.BotMemoryStore;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import io.github.zoyluo.minecraftai.task.AbstractTask;
import io.github.zoyluo.minecraftai.task.TaskManager;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Which tools the model is offered, end to end through the real {@link BrainCoordinator} and the real HTTP
 * client, with a loopback server standing in for the provider. A request to collect new resources hides
 * give_item and achieve_goal from the model's tool list; an autonomous goal-continuation wake is not part of
 * that request and must be offered them again, whichever of the two places wakes the brain for the goal.
 */
public final class BrainRoutingGameTests {
    private static final String COLLECTION_REQUEST = "get 32 logs";

    @GameTest(maxTicks = 1200)
    public void aNewInstructionIsOfferedTheToolsItsOwnWordingAllows(GameTestHelper context) {
        Harness harness = Harness.start(context, "RouteNewGT", Reply.answer("One."), Reply.answer("Two."));
        harness.ask(COLLECTION_REQUEST);
        AtomicInteger phase = new AtomicInteger();

        context.onEachTick(() -> {
            if (phase.get() == 0 && harness.service.requests() == 1 && harness.settled()) {
                require(context, harness.toolsOfRequest(0).contains("gather"), "the collection tools are offered");
                require(context, !harness.toolsOfRequest(0).contains("give_item")
                                && !harness.toolsOfRequest(0).contains("achieve_goal"),
                        "a carried stack could satisfy these: " + harness.toolsOfRequest(0));
                harness.ask("give me 32 logs");
                phase.set(1);
            } else if (phase.get() == 1 && harness.service.requests() == 2 && harness.settled()) {
                require(context, harness.toolsOfRequest(1).contains("give_item"),
                        "a handoff of carried stock is offered give_item again: the next message starts afresh");
                context.succeed();
            }
        });
    }

    @GameTest(maxTicks = 1200)
    public void anIdleGoalWakeIsNotPartOfTheLastCollectionRequest(GameTestHelper context) {
        Harness harness = Harness.start(context, "RouteIdleGT", Reply.answer("Looking."), Reply.answer("Going on."));
        harness.ask(COLLECTION_REQUEST);
        harness.setGoal(); // after the request: a new instruction cancels the bot's long-term goal

        context.onEachTick(() -> {
            if (harness.service.requests() >= 2) {
                Set<String> first = harness.toolsOfRequest(0);
                require(context, !first.contains("give_item") && !first.contains("achieve_goal"),
                        "setup: the collection request must withhold the carried-stock tools: " + first);
                Set<String> woken = harness.toolsOfRequest(1);
                require(context, woken.contains("give_item") && woken.contains("achieve_goal"),
                        "the goal wake kept the last instruction's restriction: " + woken);
                context.succeed();
            } else if (harness.service.requests() == 1 && harness.settled()) {
                // The idle watcher may wake the brain for the unfinished goal by itself; waking it here as well
                // is harmless (a busy brain refuses) and makes the test independent of the watcher's timing.
                BrainCoordinator.INSTANCE.maybeWakeForFailureOrGoal(harness.bot);
            }
        });
    }

    // The continuation waits a few seconds of wall-clock time, which the test server ticks through many times over.
    @GameTest(maxTicks = 20000)
    public void aGoalWakeAfterAFinishedTaskIsNotPartOfTheLastCollectionRequest(GameTestHelper context) {
        // The first call answers with a tool that starts nothing, so the instruction goes on; when its
        // continuation finds the bot idle after a finished task, the long-term goal is what wakes the model.
        Harness harness = Harness.start(context, "RouteTaskGT", Reply.toolCall("inventory"), Reply.answer("Going on."));
        harness.ask(COLLECTION_REQUEST);
        harness.setGoal(); // after the request: a new instruction cancels the bot's long-term goal
        TaskManager.INSTANCE.assign(harness.bot, new OneShotTask(), TaskOrigin.of(TaskOrigin.Kind.VERIFY, "routing_gametest"));

        context.onEachTick(() -> {
            if (harness.service.requests() >= 2) {
                Set<String> first = harness.toolsOfRequest(0);
                require(context, !first.contains("give_item") && !first.contains("achieve_goal"),
                        "setup: the collection request must withhold the carried-stock tools: " + first);
                Set<String> woken = harness.toolsOfRequest(1);
                require(context, harness.bodyOfRequest(1).contains("The previous task completed"),
                        "setup: the second call must be the goal continuation after the finished task");
                require(context, woken.contains("give_item") && woken.contains("achieve_goal"),
                        "the continuation after a finished task kept the last instruction's restriction: " + woken);
                context.succeed();
            }
        });
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }

    /** A bot, a loopback model service and the swapped-in config, all undone when the test ends. */
    private static final class Harness {
        private final AIPlayerEntity bot;
        private final FakeModelService service;

        private Harness(AIPlayerEntity bot, FakeModelService service) {
            this.bot = bot;
            this.service = service;
        }

        static Harness start(GameTestHelper context, String botName, Reply... script) {
            var world = context.getLevel();
            BlockPos spawn = context.absolutePos(new BlockPos(1, 126, 1));
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    world.setBlock(spawn.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    world.setBlock(spawn.offset(dx, 0, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    world.setBlock(spawn.offset(dx, 1, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
            AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                            world.getServer(), botName, world, Vec3.atBottomCenterOf(spawn),
                            0.0F, 0.0F, GameType.SURVIVAL)
                    .orElseThrow(() -> new IllegalStateException("failed to spawn " + botName));
            FakeModelService service = FakeModelService.start(script);
            MinecraftAiConfig previous = MinecraftAiConfig.get();
            MinecraftAiConfig.Llm llm = new MinecraftAiConfig.Llm("test-key", service.baseUrl(), "fake-model",
                    256, 0.0D, 10, 2, 60_000, Boolean.FALSE, "low");
            installConfig(previous.withLlm(llm));
            BrainCoordinator.INSTANCE.configure(MinecraftAiConfig.get());
            GameTestCleanup.whenFinished(context, () -> {
                service.stop();
                installConfig(previous);
                BrainCoordinator.INSTANCE.configure(previous);
                BrainCoordinator.INSTANCE.reset(bot);
                BotMemoryStore.INSTANCE.of(bot.getUUID()).clearGoal();
                TaskManager.INSTANCE.cancelIntentTasks(bot, "routing_gametest_cleanup");
                AIPlayerManager.INSTANCE.despawn(world.getServer(), botName);
            });
            return new Harness(bot, service);
        }

        void ask(String text) {
            if (!BrainCoordinator.INSTANCE.handleMessage(bot, "Tester", text)) {
                throw new IllegalStateException("the coordinator did not take the instruction");
            }
        }

        void setGoal() {
            BotMemoryStore.INSTANCE.of(bot.getUUID()).setGoal("Build a hut", List.of("gather logs"));
        }

        boolean settled() {
            return !BrainCoordinator.INSTANCE.status(bot).busy();
        }

        String bodyOfRequest(int index) {
            return service.bodies().get(index);
        }

        /** The names of the tools the model was offered in the {@code index}-th request. */
        Set<String> toolsOfRequest(int index) {
            Set<String> names = new HashSet<>();
            JsonArray tools = JsonParser.parseString(bodyOfRequest(index)).getAsJsonObject().getAsJsonArray("tools");
            for (JsonElement tool : tools) {
                names.add(tool.getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
            }
            return names;
        }
    }

    private record Reply(int status, String body) {
        /** A well-formed completion in which the model answers with the say tool. */
        static Reply answer(String text) {
            JsonObject arguments = new JsonObject();
            arguments.addProperty("message", text);
            arguments.addProperty("purpose", "answer");
            return call("say", arguments);
        }

        /** A completion that calls one tool without arguments. */
        static Reply toolCall(String name) {
            return call(name, new JsonObject());
        }

        private static Reply call(String name, JsonObject arguments) {
            JsonObject function = new JsonObject();
            function.addProperty("name", name);
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
                    byte[] bytes = (index < service.script.size() ? service.script.get(index).body()
                            : "{\"error\":{\"message\":\"unscripted\"}}").getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(index < service.script.size() ? service.script.get(index).status() : 500,
                            bytes.length);
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

    /** Finishes on its first tick, leaving a completed task behind as the bot's last one. */
    private static final class OneShotTask extends AbstractTask {
        @Override
        public String name() {
            return "routing_probe";
        }

        @Override
        public String describe() {
            return "routing probe";
        }

        @Override
        public double progress() {
            return 1.0D;
        }

        @Override
        protected void onStart(AIPlayerEntity bot) {
        }

        @Override
        protected void onTick(AIPlayerEntity bot) {
            complete();
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
}

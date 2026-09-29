package io.github.zoyluo.minecraftai.brain;

import com.google.gson.JsonObject;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.task.TaskManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class ActionDispatcher {
    // Optimization 2: after a goal fails, the brain often switches to these tools/subtasks to manually
    // mine block-by-block or move blindly, instantly burning through turns (and can dig the bot into
    // water/lava/mob piles, killing it) -> block these for a short time after a failure, forcing it to
    // retry with a high-level goal (mine_ore/gather) or stop.
    private static final java.util.Set<String> MANUAL_MINING_TOOLS =
            java.util.Set.of("strip_mine", "mine_block", "move_to");
    // Gap fix (a death chain confirmed in testing): the brain can also use
    // assign_task{task_type=move/mine/strip_mine} to bypass the tool-name block above (going through
    // assign_task still creates a MoveTask/mining task) -> block these dangerous subtypes as well;
    // high-level goals like mine_ore/gather are allowed through.
    private static final java.util.Set<String> MANUAL_MINING_TASK_TYPES =
            java.util.Set.of("move", "mine", "strip_mine");
    private static final int GOAL_FAIL_GUARD_TICKS = 600; // 30s
    private static final java.util.Set<String> USER_PAUSED_ALLOWED_TOOLS = java.util.Set.of(
            "say", "get_task_status", "goal_status", "recall", "list_jobs", "pause", "resume", "stop", "cancel_all");

    private final ToolRegistry registry;

    public ActionDispatcher(ToolRegistry registry) {
        this.registry = registry;
    }

    public List<ChatMessage> dispatch(AIPlayerEntity bot, List<ChatToolCall> calls) {
        return dispatchBatch(bot, calls, () -> true).messages();
    }

    public List<ChatMessage> dispatch(AIPlayerEntity bot,
                                      List<ChatToolCall> calls,
                                      BooleanSupplier leaseGuard) {
        return dispatchBatch(bot, calls, leaseGuard).messages();
    }

    public DispatchBatch dispatchBatch(AIPlayerEntity bot,
                                       List<ChatToolCall> calls,
                                       BooleanSupplier leaseGuard) {
        int maxCalls = MinecraftAiConfig.get().brain().maxToolCallsPerTurn();
        List<ChatMessage> results = new ArrayList<>();
        List<ExecutedToolCall> executedCalls = new ArrayList<>();
        ControlEffect controlEffect = ControlEffect.NONE;
        for (int index = 0; index < calls.size(); index++) {
            if (!leaseGuard.getAsBoolean()) {
                break;
            }
            ChatToolCall call = calls.get(index);
            ToolDefinition.ToolResult result;
            if (index >= maxCalls) {
                result = new ToolDefinition.ToolResult(false, "throttled");
            } else {
                result = invoke(bot, call);
            }
            // A tool may synchronously start a newer decision (for example tell_bot targeting self).
            // Do not execute or publish any remaining work from the superseded response.
            if (!leaseGuard.getAsBoolean()) {
                break;
            }
            if (result.ok()) {
                controlEffect = controlEffect.merge(effectOf(call.name()));
            }
            BotLog.action(bot, "tool_result", "tool", call.name(), "ok", result.ok(), "message", result.message());
            String toolContent = result.toToolContent();
            results.add(ChatMessage.toolResult(call.id(), call.name(), toolContent));
            executedCalls.add(new ExecutedToolCall(call.id(), call.name(), result.ok(), toolContent));
        }
        return new DispatchBatch(List.copyOf(results), List.copyOf(executedCalls), controlEffect);
    }

    private ToolDefinition.ToolResult invoke(AIPlayerEntity bot, ChatToolCall call) {
        try {
            if (TaskManager.INSTANCE.isUserPaused(bot) && !USER_PAUSED_ALLOWED_TOOLS.contains(call.name())) {
                return new ToolDefinition.ToolResult(false, "blocked: mission_user_paused");
            }
            // Optimization 2: right after a goal fails, the brain often switches to
            // strip_mine/mine_block/move_to (or assign_task{move/mine/strip_mine}) to manually mine
            // block-by-block or move blindly, instantly burning through turns, and can dig the bot into
            // water/lava/mob piles, killing it (confirmed two deaths in testing).
            // Block it here to force it to retry with a high-level goal (mine_ore auto-locates ore /
            // gather auto-locates resources) or stop.
            if (isManualMiningOrMove(call)
                    && io.github.zoyluo.minecraftai.goal.GoalExecutor.INSTANCE.recentlyFailed(bot, GOAL_FAIL_GUARD_TICKS)) {
                BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.COMM, bot, "manual_mining_blocked", "tool", call.name());
                return new ToolDefinition.ToolResult(false,
                        "blocked: goal just failed, do not manually mine block-by-block or move blindly "
                                + "(this easily burns through turns or digs you into water/lava/mob piles). "
                                + "Retry with a high-level goal instead (mine_ore auto-switches layers/positions to "
                                + "find ore / gather auto-finds resources), or say a brief explanation and stand by.");
            }
            ToolDefinition definition = registry.get(call.name())
                    .orElseThrow(() -> new IllegalArgumentException("unknown_tool: " + call.name()));
            JsonObject args = call.parsedArguments();
            BotLog.action(bot, "tool_dispatch", "tool", call.name(), "args", sanitizedArguments(call.name(), args));
            return definition.handler().invoke(bot, args);
        } catch (SafetyTaskActiveException exception) {
            boolean deferred = BrainCoordinator.INSTANCE.deferRequestUntilSafetyEnds(bot);
            BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.COMM, bot, "tool_blocked_by_safety_task", "tool", call.name(), "deferred", Boolean.toString(deferred));
            return new ToolDefinition.ToolResult(false, "blocked: " + exception.resultText(deferred));
        } catch (IllegalArgumentException exception) {
            // D: parameter/input validation failures (usually the brain using the wrong tool or passing
            // incomplete arguments, e.g. assign_task mine given only coordinates but missing block) are an
            // **expected** error -- log a concise warn instead of a full stack trace polluting the logs;
            // the reason is still passed back clearly to the brain so it can self-correct.
            String reason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.COMM, bot, "tool_bad_arg", "tool", call.name(), "reason", reason);
            return new ToolDefinition.ToolResult(false, "bad_arg: " + reason);
        } catch (RuntimeException exception) {
            BotLog.error(bot, "tool_exception", exception, "tool", call.name());
            String reason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            return new ToolDefinition.ToolResult(false, "exception: " + reason);
        }
    }

    // Tools whose "message"/"text"/"value" argument IS the bot's own chat output -- redacting it makes
    // tool_dispatch log lines useless for debugging what the bot actually said, while the player's own
    // chat_in is already logged verbatim elsewhere. Log the chat text itself (truncated/escaped) instead
    // of "<redacted>" for these; every other tool keeps full redaction.
    private static final java.util.Set<String> CHAT_TEXT_LOGGING_TOOLS = java.util.Set.of("say", "tell_bot");
    private static final int CHAT_LOG_TEXT_MAX_CHARS = 300;

    static JsonObject sanitizedArguments(String toolName, JsonObject args) {
        JsonObject sanitized = args == null ? new JsonObject() : args.deepCopy();
        boolean logChatText = CHAT_TEXT_LOGGING_TOOLS.contains(toolName);
        for (String sensitive : java.util.List.of("message", "text", "value")) {
            if (!sanitized.has(sensitive)) {
                continue;
            }
            if (logChatText && sanitized.get(sensitive).isJsonPrimitive()
                    && sanitized.get(sensitive).getAsJsonPrimitive().isString()) {
                sanitized.addProperty(sensitive, chatLogText(sanitized.get(sensitive).getAsString()));
            } else {
                sanitized.addProperty(sensitive, "<redacted>");
            }
        }
        return sanitized;
    }

    /** Collapses to a single line (escaping real newlines) and truncates to {@link #CHAT_LOG_TEXT_MAX_CHARS}. */
    static String chatLogText(String text) {
        String singleLine = text.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
        if (singleLine.length() > CHAT_LOG_TEXT_MAX_CHARS) {
            singleLine = singleLine.substring(0, CHAT_LOG_TEXT_MAX_CHARS) + "...";
        }
        return singleLine;
    }

    // Whether this is a "manual mining/blind movement" type call -- includes direct low-level tools
    // (strip_mine/mine_block/move_to) and assign_task{task_type=move/mine/strip_mine} (closing the gap
    // where the latter bypasses the tool-name block; high-level goals like mine_ore/gather are allowed
    // through).
    private static boolean isManualMiningOrMove(ChatToolCall call) {
        if (MANUAL_MINING_TOOLS.contains(call.name())) {
            return true;
        }
        if ("assign_task".equals(call.name())) {
            try {
                JsonObject args = call.parsedArguments();
                if (args != null && args.has("task_type") && args.get("task_type").isJsonPrimitive()) {
                    return MANUAL_MINING_TASK_TYPES.contains(args.get("task_type").getAsString());
                }
            } catch (RuntimeException ignored) {
                // Argument parsing exception -> do not block here; let the normal downstream flow report bad_arg
            }
        }
        return false;
    }

    private static ControlEffect effectOf(String toolName) {
        return switch (toolName) {
            case "stop", "abort_task" -> ControlEffect.CANCEL_CURRENT;
            case "cancel_all" -> ControlEffect.CANCEL_ALL;
            default -> ControlEffect.NONE;
        };
    }

    public enum ControlEffect {
        NONE,
        CANCEL_CURRENT,
        CANCEL_ALL;

        private ControlEffect merge(ControlEffect other) {
            return ordinal() >= other.ordinal() ? this : other;
        }
    }

    /** One locally executed result, kept alongside the serialized chat message for native Gemini continuations. */
    public record ExecutedToolCall(String callId, String name, boolean ok, String content) {
    }

    public record DispatchBatch(List<ChatMessage> messages,
                                List<ExecutedToolCall> executedCalls,
                                ControlEffect controlEffect) {
        public int failedCallCount() {
            return (int) executedCalls.stream().filter(call -> !call.ok()).count();
        }
    }
}

package io.github.zoyluo.minecraftai.brain;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.memory.BotMemoryStore;
import io.github.zoyluo.minecraftai.observe.ReplayRecorder;
import io.github.zoyluo.minecraftai.observe.TpsGuard;
import io.github.zoyluo.minecraftai.network.MinecraftAiServerNetworking;
import io.github.zoyluo.minecraftai.perception.PerceptionCollector;
import io.github.zoyluo.minecraftai.perception.PerceptionSnapshot;
import io.github.zoyluo.minecraftai.perception.SpeakerViewCollector;
import io.github.zoyluo.minecraftai.task.MemoryStore;
import io.github.zoyluo.minecraftai.task.TaskManager;
import io.github.zoyluo.minecraftai.task.TaskStatus;
import io.github.zoyluo.minecraftai.runtime.IntentController;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class BrainCoordinator {
    public static final BrainCoordinator INSTANCE = new BrainCoordinator();
    private static final int MAX_CONTINUATION_TASK_POLLS = 80;
    // Only this explicit inspection set can accompany a question answer. Do not infer that an
    // unknown tool is harmless: a newly added tool must opt in here before it can complete a
    // question-only turn.
    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "inventory", "plan_craft", "find_container", "list_jobs",
            "recall", "goal_status", "get_task_status", "lookup_recipe");
    // These are the known ways an LLM response can begin or perform requested work. This is a
    // positive list deliberately paired with runtime-work checks below: non-action tools cannot
    // accidentally satisfy the initial-action gate simply because they are not on a blacklist.
    private static final Set<String> GENUINE_ACTION_TOOLS = Set.of(
            "look_at", "move_to", "mine_block", "place_block", "select_hotbar", "equip_best_tool",
            "craft", "eat", "smelt", "gather", "clear_grass", "break_blocks", "fish", "trade", "set_base",
            "deposit_all", "strip_mine", "mine_vein", "mine_ore", "mine_valuables_in_radius", "achieve_goal", "harvest_crop",
            "provision_food", "forage", "achieve_armor", "achieve_workstation", "build_house",
            "stockpile", "deposit", "withdraw", "equip_armor", "attack", "light_area",
            "follow", "hold", "guard", "farm", "harvest", "breed", "attack_entity", "post_job",
            "tell_bot", "remember", "forget", "mark_place", "goto_place", "resume_mining",
            "mine_and_stockpile", "recover_drops", "set_goal", "advance_goal", "assign_task",
            "launch_boat", "board_boat", "boat_follow", "exit_boat", "give_item");
    // A successful setup, memory, or coordination call is not evidence that the requested work
    // has begun. These are the concrete task/goal/direct-work entry points that may start the
    // initial request even when their operation finishes in the same tick.
    private static final Set<String> WORK_START_TOOLS = Set.of(
            "move_to", "mine_block", "place_block", "craft", "eat", "smelt", "gather",
            "clear_grass", "break_blocks", "fish", "trade", "deposit_all", "strip_mine", "mine_vein",
            "mine_ore", "mine_valuables_in_radius", "achieve_goal", "harvest_crop", "provision_food", "forage",
            "achieve_armor", "achieve_workstation", "build_house", "stockpile", "deposit",
            "withdraw", "attack", "light_area", "follow", "hold", "guard", "farm",
            "harvest", "breed", "attack_entity", "goto_place", "resume_mining",
            "mine_and_stockpile", "recover_drops", "assign_task", "launch_boat", "board_boat",
            "boat_follow", "exit_boat", "give_item");
    // These terminal/mission-control commands are intentionally not "start work" actions. They
    // must remain usable as concise commands without making a fictional plan first.
    private static final Set<String> CONTROL_ONLY_TOOLS = Set.of(
            "stop", "abort_task", "pause", "resume", "cancel_all");
    private static final String PLAN_REQUIRED_TOOL_RESULT =
            "blocked: call say with purpose=plan and a non-empty English plan before the first action or goal tool";
    private static final String THROTTLED_TOOL_RESULT = "throttled: per-response function-call cap reached";
    // A plan is announced to the player once. Re-saying it (the model does this when it offers only
    // say and never starts the work) must not be repeated in chat: it is dropped as a fault and the
    // next call is forced to use an action tool instead (see shouldWithholdSay).
    private static final String REPEATED_PLAN_TOOL_RESULT =
            "blocked: your plan was already announced; do not say it again, call the action or goal tool now";
    static final String SAY_TOOL_NAME = "say";
    private static final int MAX_INSTRUCTION_ECHO_CHARS = 60;

    private final Map<UUID, BotConversation> conversations = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> manualModes = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> nextGoalWakeTick = new ConcurrentHashMap<>();
    // FLOW-2: set true once the brain assigns a long-running task; after the task ends the
    // idle-watcher uses this to auto-wake the brain to decide the next step (no human nudge needed).
    private final Map<UUID, Boolean> awaitingTask = new ConcurrentHashMap<>();
    // The player's request was blocked because a SAFETY task (a fight, an evade) was running
    // (see ToolRegistry.assignLlm). It is not lost: when the threat is handled and the bot is idle,
    // the brain is woken to start it (maybeWakeForFailureOrGoal). Cleared by any newer intent.
    private final Map<UUID, Boolean> deferredRequests = new ConcurrentHashMap<>();
    private ToolRegistry toolRegistry = new ToolRegistry();
    private ActionDispatcher dispatcher = new ActionDispatcher(toolRegistry);
    private AsyncDecisionExecutor executor;

    private BrainCoordinator() {
    }

    public void configure(MinecraftAiConfig config) {
        conversations.values().forEach(conversation -> conversation.decision.invalidate());
        if (executor != null) {
            executor.shutdown();
        }
        toolRegistry = new ToolRegistry();
        dispatcher = new ActionDispatcher(toolRegistry);
        GeminiInteractionsApiClient geminiInteractions = GeminiInteractionsApiClient
                .isGoogleInteractionsEndpoint(config.llm())
                ? new GeminiInteractionsApiClient(config.llm())
                : null;
        executor = new AsyncDecisionExecutor(new OpenAiCompatibleApiClient(config.llm()), geminiInteractions);
    }

    /** Preserves the speaker's real current view for ordinary/player-panel chat. */
    public boolean handleMessage(AIPlayerEntity bot, ServerPlayer sender, String text) {
        if (sender == null) {
            return handleMessage(bot, "player", text);
        }
        return handleMessage(bot,
                sender.getGameProfile().name(),
                text,
                SpeakerViewCollector.collect(sender, bot).toJson(),
                0);
    }

    /** Receives normal chat after its successful Gemini recipient-routing call is accounted for. */
    boolean handleRoutedMessage(AIPlayerEntity bot,
                                ServerPlayer sender,
                                String text,
                                int routingModelCallCost) {
        if (sender == null) {
            return handleMessage(bot, "player", text);
        }
        return handleMessage(bot,
                sender.getGameProfile().name(),
                text,
                SpeakerViewCollector.collect(sender, bot).toJson(),
                routingModelCallCost);
    }

    public boolean handleMessage(AIPlayerEntity bot, String senderName, String text) {
        return handleMessage(bot, senderName, text, "", 0);
    }

    private boolean handleMessage(AIPlayerEntity bot,
                                  String senderName,
                                  String text,
                                  String speakerViewJson,
                                  int callsAlreadyUsed) {
        ensureConfigured();
        BotConversation conversation = conversations.computeIfAbsent(bot.getUUID(), BotConversation::new);
        boolean supersededDecision = conversation.decision.busy();
        // Companion-mode semantics: the newest player request is authoritative.  Cancel all
        // current and queued work before making a new plan, so a bot never quietly finishes an
        // older request after the player has changed their mind. The one exception is a running SAFETY task
        // (a fight against a real threat): it is not cancelled mid-fight, the new request waits until the
        // threat is handled (see ToolRegistry.assignLlm); an explicit stop/hold still preempts it.
        IntentController.INSTANCE.cancelAllKeepingActiveSafety(
                bot, IntentController.ControlOrigin.SYSTEM, "new_player_request");
        awaitingTask.remove(bot.getUUID());
        nextGoalWakeTick.remove(bot.getUUID());
        deferredRequests.remove(bot.getUUID());
        DecisionLease lease = conversation.decision.beginEpoch();
        // A fresh instruction also gets a fresh LLM context. This avoids old tool calls and
        // goals biasing the planner toward a request the player has already replaced.
        conversation.history.clear();
        conversation.history.add(ChatMessage.system(systemPrompt(bot.getGameProfile().name(), senderName)));
        if (supersededDecision) {
            BotLog.comm(bot, "decision_superseded",
                    "epoch", lease.epoch(),
                    "request_sequence", lease.requestSequence());
        }

        // The recent-chat block must reflect only what was said BEFORE this instruction; render
        // it first, then record this instruction so later calls see it as history.
        String recentChat = ChatTranscript.renderRecentChat(bot.getUUID());
        ChatTranscript.recordPlayerLine(bot.getUUID(), senderName, text);
        String recentChatBlock = recentChat.isEmpty() ? "" : recentChat + "\n\n";

        PerceptionSnapshot snapshot = PerceptionCollector.collect(bot);
        conversation.lastPerceptionDigest = perceptionDigest(snapshot);
        String speakerView = speakerViewJson == null || speakerViewJson.isBlank()
                ? ""
                : "\n\nSpeaker visual context:\n" + speakerViewJson;
        conversation.history.add(ChatMessage.user(recentChatBlock
                + "New instruction -- [" + senderName + "] says: " + text
                + "\n\nCurrent state:\n" + snapshot.toJson() + speakerView));
        trimHistory(conversation);
        conversation.callBudget.beginPlayerInstruction(callsAlreadyUsed);
        conversation.continuationTaskPolls = 0;
        conversation.budgetExhaustionReported = false;
        conversation.lastToolRoundFailureCount = 0;
        conversation.requestStarted = false;
        conversation.failureReportCall = false;
        conversation.initialPlanSpoken = false;
        conversation.lastToolRoundMissingRequiredAction = false;
        conversation.lastToolRoundPlanBlockedAction = false;
        conversation.withholdSayNextCall = false;
        conversation.lastInstruction = text;
        conversation.instructionChain.beginPlayerInstruction();
        conversation.geminiInteractionId = null;
        conversation.pendingGeminiFunctionResults = List.of();
        io.github.zoyluo.minecraftai.goal.GoalExecutor.INSTANCE.clearUserGoal(bot); // B: a new message from the user clears the stored original goal; the first goal triggered by this message becomes the new "user's original goal"
        submit(bot, conversation, lease);
        return true;
    }

    private void onResponse(AIPlayerEntity bot, DecisionLease lease, ChatResponse response) {
        BotConversation conversation = conversations.get(lease.botId());
        if (conversation == null || !conversation.decision.tryAcceptResponse(lease)) {
            logStaleDecision(lease, "response");
            return;
        }

        // A failure-report call is a per-call marker (set by submit), never an instruction-level flag:
        // it must neither start the player's request nor be held against it.
        boolean failureReportCall = conversation.failureReportCall;
        // The withheld-say flag describes the call that was just answered; the round evaluation below
        // decides whether the NEXT call needs it. A failure-report call is a side job that leaves it
        // untouched (see InstructionRoundEvaluator.nextWithholdSay), so a plan-only loop interrupted by
        // a failure wake still withholds say afterwards.
        if (!failureReportCall) {
            conversation.withholdSayNextCall = false;
        }
        boolean planAlreadyAnnounced = !failureReportCall
                && conversation.initialPlanSpoken && !conversation.requestStarted;
        recordResponseAndDeliverReply(bot, conversation, response);
        InitialActionGate initialActionGate = prepareToolCallsForThisRound(conversation, response, failureReportCall);
        List<ChatToolCall> toolCalls = initialActionGate.orderedCalls();

        if (response.wantsToolCalls()) {
            dispatchToolCallRound(bot, lease, conversation, response, toolCalls, initialActionGate,
                    planAlreadyAnnounced, failureReportCall);
            return;
        }
        handleTextOnlyResponse(bot, lease, conversation, response, failureReportCall);
    }

    /** Logs the API response, delivers any chat reply, and records token/Gemini-interaction bookkeeping. */
    private void recordResponseAndDeliverReply(AIPlayerEntity bot, BotConversation conversation, ChatResponse response) {
        BotLog.api(bot, "api_response",
                "tokens_in", response.promptTokens(),
                "tokens_out", response.completionTokens(),
                "cache_hit", response.promptCacheHitTokens(),
                "finish_reason", response.finishReason());

        if (response.content() != null && !response.content().isBlank()) {
            // Plain-text replies bypass the say tool, so log them here the way say is logged.
            BotLog.comm(bot, "bot_text_reply", "message", ActionDispatcher.chatLogText(response.content()));
            sendBotReply(bot, response.content());
        }
        conversation.lastPromptTokens = response.promptTokens();
        conversation.lastCompletionTokens = response.completionTokens();
        conversation.lastCacheHitTokens = response.promptCacheHitTokens();
        if (response.geminiInteractionId() != null && !response.geminiInteractionId().isBlank()) {
            // A response proves that the prior function-result batch was accepted. Keep only
            // the new interaction id; if this HTTP call had failed, onError would instead retain
            // the previous id and result batch for a safe retry.
            conversation.geminiInteractionId = response.geminiInteractionId();
            conversation.pendingGeminiFunctionResults = List.of();
        }
    }

    /** Applies the initial-action-plan gate to this round's tool calls and appends the assistant message to history. */
    private InitialActionGate prepareToolCallsForThisRound(BotConversation conversation,
                                                           ChatResponse response,
                                                           boolean failureReportCall) {
        InitialActionGate initialActionGate = initialActionGate(
                response.toolCalls(),
                conversation.requestStarted || conversation.initialPlanSpoken || failureReportCall);
        List<ChatToolCall> toolCalls = initialActionGate.orderedCalls();
        if (!failureReportCall && !conversation.initialPlanSpoken && containsValidPlan(response.toolCalls())) {
            conversation.initialPlanSpoken = true;
        }
        conversation.history.add(ChatMessage.assistant(response.content(), toolCalls));
        return initialActionGate;
    }

    /**
     * Dispatches this round's tool calls, resolves any control effect (stop/cancel), and then
     * evaluates whether the round satisfies the initial-action requirement, scheduling a
     * continuation or completing the decision as appropriate.
     */
    private void dispatchToolCallRound(AIPlayerEntity bot,
                                       DecisionLease lease,
                                       BotConversation conversation,
                                       ChatResponse response,
                                       List<ChatToolCall> toolCalls,
                                       InitialActionGate initialActionGate,
                                       boolean planAlreadyAnnounced,
                                       boolean failureReportCall) {
        ActionDispatcher.DispatchBatch dispatchBatch = dispatchWithInitialPlanGate(
                bot,
                toolCalls,
                initialActionGate,
                planAlreadyAnnounced,
                () -> conversation.decision.isApplying(lease));
        if (!conversation.decision.isApplying(lease)) {
            logStaleDecision(lease, "tool_batch");
            return;
        }
        List<ChatMessage> toolResults = dispatchBatch.messages();
        ReplayRecorder.INSTANCE.onDecision(bot, conversation.lastPerceptionDigest, toolCalls, replayResult(toolResults));
        conversation.history.addAll(toolResults);
        if (executor.usesGeminiInteractions()) {
            List<GeminiInteractionsApiClient.FunctionResult> nativeResults = new ArrayList<>();
            for (ActionDispatcher.ExecutedToolCall call : dispatchBatch.executedCalls()) {
                nativeResults.add(new GeminiInteractionsApiClient.FunctionResult(
                        call.callId(), call.name(), call.content()));
            }
            nativeResults.addAll(response.geminiCappedFunctionResults());
            conversation.pendingGeminiFunctionResults = List.copyOf(nativeResults);
        }
        if (dispatchBatch.controlEffect() != ActionDispatcher.ControlEffect.NONE) {
            boolean replacementWorkActive = shouldContinueAfterControl(
                    dispatchBatch.controlEffect(),
                    TaskManager.INSTANCE.getActive(bot).isPresent(),
                    io.github.zoyluo.minecraftai.goal.GoalExecutor.INSTANCE.hasActivePlan(bot),
                    io.github.zoyluo.minecraftai.goal.GoalExecutor.INSTANCE.queuedGoalCount(bot),
                    bot.getActionPack().hasActiveActions());
            if (!replacementWorkActive) {
                if (!conversation.decision.complete(lease)) {
                    logStaleDecision(lease, "control_completion");
                    return;
                }
                trimHistory(conversation);
                BotLog.comm(bot, "conversation_controlled", "effect", dispatchBatch.controlEffect());
                return;
            }
            BotLog.comm(bot, "conversation_control_replaced", "effect", dispatchBatch.controlEffect());
        }
        boolean workActive = hasRuntimeWork(
                TaskManager.INSTANCE.getActive(bot).isPresent(),
                io.github.zoyluo.minecraftai.goal.GoalExecutor.INSTANCE.hasActivePlan(bot),
                io.github.zoyluo.minecraftai.goal.GoalExecutor.INSTANCE.queuedGoalCount(bot),
                bot.getActionPack().hasActiveActions());
        // A dropped repeated say(plan) is a fault of its own (handled below), not a tool failure the
        // model should be asked to repair: counting it would hide the missing action.
        int repeatedPlanFaults = (int) dispatchBatch.executedCalls().stream()
                .filter(call -> !call.ok() && call.content().contains(REPEATED_PLAN_TOOL_RESULT))
                .count();
        int failedToolCalls = dispatchBatch.failedCallCount() - repeatedPlanFaults
                + response.geminiCappedFunctionResults().size();
        // "This instruction's request started" is deliberately NOT "some work is active": unrelated
        // autonomous work (a flee, auto-eat, a leftover task) must not count as the player's command
        // having begun, or the command is silently dropped when the planner never starts it. Only a
        // work-start tool of this instruction that succeeded moves the flag (it then stays set).
        boolean workStartToolSucceeded = dispatchBatch.executedCalls().stream()
                .anyMatch(call -> call.ok() && isWorkStartTool(call.name()));
        InstructionRoundEvaluator.RoundOutcome outcome = InstructionRoundEvaluator.evaluateToolRound(
                new InstructionRoundEvaluator.ToolRound(
                        conversation.requestStarted,
                        // initialPlanSpoken already includes a plan announced in this very round.
                        conversation.initialPlanSpoken,
                        failureReportCall,
                        workActive,
                        workStartToolSucceeded,
                        isAnswerOnlyReply(toolCalls),
                        isControlOnlyReply(toolCalls),
                        failedToolCalls,
                        initialActionGate.blockedActionCalls()));
        conversation.requestStarted = outcome.requestStarted();
        boolean missingRequiredAction = outcome.missingRequiredAction();
        conversation.lastToolRoundFailureCount = failedToolCalls;
        conversation.lastToolRoundMissingRequiredAction = outcome.lastRoundMissingRequiredAction();
        conversation.lastToolRoundPlanBlockedAction = initialActionGate.blockedActionCalls();
        conversation.withholdSayNextCall = InstructionRoundEvaluator.nextWithholdSay(
                conversation.withholdSayNextCall, failureReportCall, outcome.withholdSayNextCall());
        BotLog.comm(bot, "tool_round_evaluated",
                "model_call", conversation.callBudget.callsUsed(),
                "model_calls_remaining", conversation.callBudget.callsRemaining(),
                "tool_calls", toolCalls.size(),
                "failed_tool_calls", failedToolCalls,
                "missing_required_action", conversation.lastToolRoundMissingRequiredAction,
                "initial_plan_blocked_action", initialActionGate.blockedActionCalls(),
                "initial_plan_reordered", initialActionGate.reorderedPlan(),
                "repeated_plan_faults", repeatedPlanFaults,
                "say_withheld_next_call", conversation.withholdSayNextCall,
                "work_active", workActive,
                "request_started", conversation.requestStarted,
                "failure_report_call", failureReportCall,
                "provider", executor.usesGeminiInteractions() ? "gemini_interactions" : "chat_completions");
        if (missingRequiredAction || initialActionGate.blockedActionCalls()) {
            BotLog.warn(LogCategory.COMM, bot, "action_request_not_started",
                    "model_call", conversation.callBudget.callsUsed(),
                    "tool_calls", toolCalls.stream().map(ChatToolCall::name).toList(),
                    "initial_plan_blocked_action", initialActionGate.blockedActionCalls());
            if (conversation.callBudget.exhausted()) {
                if (!conversation.decision.complete(lease)) {
                    logStaleDecision(lease, "missing_action_budget_completion");
                    return;
                }
                trimHistory(conversation);
                finishCallBudget(bot, conversation, initialActionGate.blockedActionCalls()
                        ? "initial_plan_required"
                        : "missing_required_action");
                return;
            }
            trimHistory(conversation);
            if (!conversation.decision.awaitContinuation(lease)) {
                logStaleDecision(lease, "missing_action_continuation_wait");
                return;
            }
            scheduleContinuation(bot, conversation, lease);
            return;
        }
        // A valid one-off answer (for example say for a pure question) does not need a
        // second model call. Retries are reserved for an actual tool failure or for work
        // whose deterministic runtime is still progressing.
        if (!outcome.keepGoing()) {
            if (!conversation.decision.complete(lease)) {
                logStaleDecision(lease, "tool_round_completion");
                return;
            }
            trimHistory(conversation);
            BotLog.comm(bot, "tool_round_completed", "model_call", conversation.callBudget.callsUsed());
            return;
        }
        if (conversation.callBudget.exhausted()) {
            if (!conversation.decision.complete(lease)) {
                logStaleDecision(lease, "model_call_budget_completion");
                return;
            }
            trimHistory(conversation);
            finishCallBudget(bot, conversation, "tool_round");
            return;
        }
        trimHistory(conversation);
        if (!conversation.decision.awaitContinuation(lease)) {
            logStaleDecision(lease, "continuation_wait");
            return;
        }
        scheduleContinuation(bot, conversation, lease);
        return;
    }

    /**
     * Handles a response with no tool calls: rejects it as an incomplete turn unless an
     * initial action already started this instruction, otherwise completes the decision.
     */
    private void handleTextOnlyResponse(AIPlayerEntity bot,
                                        DecisionLease lease,
                                        BotConversation conversation,
                                        ChatResponse response,
                                        boolean failureReportCall) {
        // Tool choice is required for every fresh player turn. A bare text response cannot say
        // whether it was an answer or a plan, so do not let it silently complete an action
        // request. Once an initial action has genuinely started, a later text-only completion
        // report remains valid, and so is a text-only failure report (not an unfinished request).
        InstructionRoundEvaluator.TextOnlyOutcome textOnly = InstructionRoundEvaluator.evaluateTextOnlyRound(
                conversation.requestStarted, conversation.initialPlanSpoken, failureReportCall);
        if (textOnly.requestOutstanding()) {
            conversation.lastToolRoundFailureCount = 0;
            conversation.lastToolRoundMissingRequiredAction = true;
            conversation.lastToolRoundPlanBlockedAction = false;
            // onResponse cleared the flag; an announced-but-unstarted plan keeps say withheld.
            conversation.withholdSayNextCall = textOnly.withholdSayNextCall();
            BotLog.warn(LogCategory.COMM, bot, "structured_tool_call_missing",
                    "model_call", conversation.callBudget.callsUsed(),
                    "finish_reason", response.finishReason());
            if (conversation.callBudget.exhausted()) {
                if (!conversation.decision.complete(lease)) {
                    logStaleDecision(lease, "unstructured_response_budget_completion");
                    return;
                }
                trimHistory(conversation);
                finishCallBudget(bot, conversation, "structured_tool_call_missing");
                return;
            }
            trimHistory(conversation);
            if (!conversation.decision.awaitContinuation(lease)) {
                logStaleDecision(lease, "unstructured_response_continuation_wait");
                return;
            }
            scheduleContinuation(bot, conversation, lease);
            return;
        }

        if (!conversation.decision.complete(lease)) {
            logStaleDecision(lease, "response_completion");
            return;
        }
        ReplayRecorder.INSTANCE.onDecision(bot, conversation.lastPerceptionDigest, List.of(), response.content());
        // FLOW-2: if the brain is wrapping up and a task is still active (this round is "assigned
        // a long task then stopped"), mark that it is waiting for the task to finish; after the
        // task ends the idle-watcher auto-wakes the brain to decide the next step, no human nudge needed.
        if (TaskManager.INSTANCE.getActive(bot).isPresent()) {
            awaitingTask.put(bot.getUUID(), true);
        }
        trimHistory(conversation);
        BotLog.comm(bot, "conversation_done", "finish_reason", response.finishReason());
    }

    /**
     * Enforces the one user-visible plan before the first effectful tool call without changing
     * later status/reporting rounds. When Gemini supplied a valid plan later in the same tool
     * batch, move only that call ahead of the first action. If it supplied no valid plan, return
     * synthetic failed results for every action call instead of letting an action happen first.
     * A say(purpose=plan) repeated while the announced plan has still not started is dropped too
     * ({@link #REPEATED_PLAN_TOOL_RESULT}): the player must not see the same announcement again.
     */
    private ActionDispatcher.DispatchBatch dispatchWithInitialPlanGate(
            AIPlayerEntity bot,
            List<ChatToolCall> calls,
            InitialActionGate gate,
            boolean planAlreadyAnnounced,
            BooleanSupplier leaseGuard) {
        boolean anyBlocked = false;
        for (ChatToolCall call : calls) {
            if (blockedResult(call, gate, planAlreadyAnnounced) != null) {
                anyBlocked = true;
                break;
            }
        }
        if (!anyBlocked) {
            return dispatcher.dispatchBatch(bot, calls, leaseGuard);
        }

        int maxCalls = MinecraftAiConfig.get().brain().maxToolCallsPerTurn();
        List<ChatToolCall> safeCalls = new ArrayList<>();
        for (int index = 0; index < calls.size() && index < maxCalls; index++) {
            ChatToolCall call = calls.get(index);
            if (blockedResult(call, gate, planAlreadyAnnounced) == null) {
                safeCalls.add(call);
            }
        }
        ActionDispatcher.DispatchBatch safeBatch = dispatcher.dispatchBatch(bot, safeCalls, leaseGuard);
        if (!leaseGuard.getAsBoolean()) {
            // The regular dispatch path may have begun a newer decision (for example, tell_bot
            // targeting self). Its caller will discard this stale batch in the normal way.
            return safeBatch;
        }

        List<ChatMessage> results = new ArrayList<>(calls.size());
        List<ActionDispatcher.ExecutedToolCall> executedCalls = new ArrayList<>(calls.size());
        int safeIndex = 0;
        for (int index = 0; index < calls.size(); index++) {
            ChatToolCall call = calls.get(index);
            String blocked = blockedResult(call, gate, planAlreadyAnnounced);
            if (index >= maxCalls) {
                appendSyntheticToolFailure(bot, call, THROTTLED_TOOL_RESULT, results, executedCalls);
            } else if (blocked != null) {
                if (REPEATED_PLAN_TOOL_RESULT.equals(blocked)) {
                    BotLog.warn(LogCategory.COMM, bot, "repeated_plan_say_dropped");
                }
                appendSyntheticToolFailure(bot, call, blocked, results, executedCalls);
            } else if (safeIndex < safeBatch.messages().size()
                    && safeIndex < safeBatch.executedCalls().size()) {
                results.add(safeBatch.messages().get(safeIndex));
                executedCalls.add(safeBatch.executedCalls().get(safeIndex));
                safeIndex++;
            } else {
                // This can only happen when a lease becomes stale while the regular dispatcher
                // is iterating. Preserve a function result for protocol completeness; the outer
                // stale-lease check immediately drops the response.
                appendSyntheticToolFailure(bot, call, "blocked: decision superseded", results, executedCalls);
            }
        }
        return new ActionDispatcher.DispatchBatch(
                List.copyOf(results), List.copyOf(executedCalls), safeBatch.controlEffect());
    }

    /** The synthetic failure text for a call that must not run in this round, or null when it may run. */
    private static String blockedResult(ChatToolCall call, InitialActionGate gate, boolean planAlreadyAnnounced) {
        if (gate.blockedActionCalls() && isGenuineActionTool(call.name())) {
            return PLAN_REQUIRED_TOOL_RESULT;
        }
        if (isRepeatedPlanSay(call, planAlreadyAnnounced)) {
            return REPEATED_PLAN_TOOL_RESULT;
        }
        return null;
    }

    /** True for a valid say(purpose=plan) after a plan was already announced and no action has begun. */
    static boolean isRepeatedPlanSay(ChatToolCall call, boolean planAlreadyAnnounced) {
        return planAlreadyAnnounced && isValidSayWithPurpose(call, "plan");
    }

    /**
     * Whether the NEXT model call must be forced into using an action tool: the model announced a
     * plan (say purpose=plan) but started nothing (the say(plan)-only loop that re-offered ~60 tools
     * and burned the whole call budget). The say tool is then removed from that call's tool set, so
     * the only way to answer is to call something that does work. A round whose action calls were
     * blocked for lacking a plan still needs say (to announce it), and a round with no plan may be a
     * question, so neither withholds it.
     */
    static boolean shouldWithholdSay(boolean missingRequiredAction,
                                     boolean planBlockedAction,
                                     boolean requestStarted,
                                     boolean roundAnnouncedPlan) {
        return InstructionRoundEvaluator.shouldWithholdSay(
                missingRequiredAction, planBlockedAction, requestStarted, roundAnnouncedPlan);
    }

    /** The tools offered to a model call: everything, or everything except say when it is withheld. */
    static List<ToolDefinition> toolsForCall(List<ToolDefinition> tools, boolean withholdSay) {
        if (!withholdSay || tools == null) {
            return tools;
        }
        List<ToolDefinition> withoutSay = new ArrayList<>(tools.size());
        for (ToolDefinition tool : tools) {
            if (tool != null && !SAY_TOOL_NAME.equals(tool.name())) {
                withoutSay.add(tool);
            }
        }
        return List.copyOf(withoutSay);
    }

    /** What the player hears when the planner gave up without ever starting the request. */
    static String couldNotStartMessage(String instruction) {
        String echo = instruction == null ? "" : instruction.replace('\n', ' ').replace('\r', ' ').trim();
        if (echo.length() > MAX_INSTRUCTION_ECHO_CHARS) {
            echo = echo.substring(0, MAX_INSTRUCTION_ECHO_CHARS - 3) + "...";
        }
        return echo.isEmpty()
                ? "Sorry, I could not start that. Could you say it another way?"
                : "Sorry, I could not start \"" + echo + "\". Could you say it another way?";
    }

    /** The one-line fallback when a failed task cannot get a model call to report it. */
    static String failureFallbackMessage(String taskName, String reason) {
        return "Sorry, my " + ReasonText.taskName(taskName == null ? "task" : taskName)
                + " stopped: " + ReasonText.itemText(reason) + ".";
    }

    private static void appendSyntheticToolFailure(AIPlayerEntity bot,
                                                   ChatToolCall call,
                                                   String message,
                                                   List<ChatMessage> results,
                                                   List<ActionDispatcher.ExecutedToolCall> executedCalls) {
        String content = new ToolDefinition.ToolResult(false, message).toToolContent();
        BotLog.action(bot, "tool_result", "tool", call.name(), "ok", false, "message", message);
        results.add(ChatMessage.toolResult(call.id(), call.name(), content));
        executedCalls.add(new ActionDispatcher.ExecutedToolCall(call.id(), call.name(), false, content));
    }

    static InitialActionGate initialActionGate(List<ChatToolCall> calls, boolean initialActionStarted) {
        List<ChatToolCall> safeCalls = calls == null ? List.of() : List.copyOf(calls);
        if (initialActionStarted) {
            return new InitialActionGate(safeCalls, false, false);
        }

        int firstActionIndex = -1;
        int planIndex = -1;
        for (int index = 0; index < safeCalls.size(); index++) {
            ChatToolCall call = safeCalls.get(index);
            if (firstActionIndex < 0 && isGenuineActionTool(call.name())) {
                firstActionIndex = index;
            }
            if (planIndex < 0 && isValidSayWithPurpose(call, "plan")) {
                planIndex = index;
            }
        }
        if (firstActionIndex < 0) {
            return new InitialActionGate(safeCalls, false, false);
        }
        if (planIndex < 0) {
            return new InitialActionGate(safeCalls, true, false);
        }
        if (planIndex < firstActionIndex) {
            return new InitialActionGate(safeCalls, false, false);
        }

        List<ChatToolCall> reordered = new ArrayList<>(safeCalls);
        ChatToolCall plan = reordered.remove(planIndex);
        reordered.add(firstActionIndex, plan);
        return new InitialActionGate(List.copyOf(reordered), false, true);
    }

    /** Whether any call in this round is a valid say(purpose=plan), regardless of order or outcome. */
    static boolean containsValidPlan(List<ChatToolCall> calls) {
        return calls != null && calls.stream().anyMatch(call -> isValidSayWithPurpose(call, "plan"));
    }

    static boolean isAnswerOnlyReply(List<ChatToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return false;
        }
        boolean hasAnswer = false;
        for (ChatToolCall call : calls) {
            if (isValidSayWithPurpose(call, "answer")) {
                hasAnswer = true;
            } else if (!READ_ONLY_TOOLS.contains(call.name())) {
                return false;
            }
        }
        return hasAnswer;
    }

    private static boolean isControlOnlyReply(List<ChatToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return false;
        }
        boolean hasControl = false;
        for (ChatToolCall call : calls) {
            if (CONTROL_ONLY_TOOLS.contains(call.name())) {
                hasControl = true;
            } else if (!"say".equals(call.name())) {
                return false;
            }
        }
        return hasControl;
    }

    private static boolean isGenuineActionTool(String toolName) {
        return GENUINE_ACTION_TOOLS.contains(toolName);
    }

    static boolean isWorkStartTool(String toolName) {
        return WORK_START_TOOLS.contains(toolName);
    }

    private static boolean isValidSayWithPurpose(ChatToolCall call, String expectedPurpose) {
        if (call == null || !"say".equals(call.name())) {
            return false;
        }
        try {
            var arguments = call.parsedArguments();
            return arguments.has("message")
                    && arguments.get("message").isJsonPrimitive()
                    && !arguments.get("message").getAsString().isBlank()
                    && arguments.has("purpose")
                    && arguments.get("purpose").isJsonPrimitive()
                    && expectedPurpose.equals(arguments.get("purpose").getAsString());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static record InitialActionGate(List<ChatToolCall> orderedCalls,
                                    boolean blockedActionCalls,
                                    boolean reorderedPlan) {
    }

    static boolean shouldContinueAfterControl(ActionDispatcher.ControlEffect effect,
                                              boolean activeTask,
                                              boolean activeGoal,
                                              int queuedGoals,
                                              boolean activeAction) {
        return effect != ActionDispatcher.ControlEffect.NONE
                && hasRuntimeWork(activeTask, activeGoal, queuedGoals, activeAction);
    }

    static boolean hasRuntimeWork(boolean activeTask,
                                  boolean activeGoal,
                                  int queuedGoals,
                                  boolean activeAction) {
        return activeTask || activeGoal || queuedGoals > 0 || activeAction;
    }

    private void onError(AIPlayerEntity bot, DecisionLease lease, Throwable throwable) {
        BotConversation conversation = conversations.get(lease.botId());
        if (conversation == null || !conversation.decision.tryAcceptError(lease)) {
            logStaleDecision(lease, "error");
            return;
        }
        String message = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
        BotLog.error(bot, "brain_hiccup", throwable, "message", message);
        if (conversation.callBudget.exhausted()) {
            finishCallBudget(bot, conversation, "api_error");
            return;
        }
        // A failed HTTP/model turn did not apply any game action. Keep the original instruction,
        // native interaction id, and function-result batch intact, then spend one of the two
        // permitted repair calls on a fresh attempt.
        conversation.history.add(ChatMessage.user(
                "The previous AI request failed before it could finish. Retry the same player request now. "
                        + "If a prior tool result is present, correct its error instead of repeating it unchanged. "
                        + "Failure summary: " + conciseFailureMessage(message)));
        trimHistory(conversation);
        DecisionLease retryLease = conversation.decision.beginEpoch();
        BotLog.warn(LogCategory.COMM, bot, "model_call_retry_scheduled",
                "model_call", conversation.callBudget.callsUsed() + 1,
                "model_calls_remaining_before_retry", conversation.callBudget.callsRemaining(),
                "reason", conciseFailureMessage(message));
        submit(bot, conversation, retryLease);
    }

    public void reset(AIPlayerEntity bot) {
        BotConversation conversation = conversations.remove(bot.getUUID());
        if (conversation != null) {
            conversation.decision.invalidate();
        }
        manualModes.remove(bot.getUUID());
        awaitingTask.remove(bot.getUUID());
        nextGoalWakeTick.remove(bot.getUUID());
        deferredRequests.remove(bot.getUUID());
        BotRuntimeOptions.INSTANCE.clear(bot);
        ChatTranscript.clear(bot.getUUID());
        BotLog.comm(bot, "conversation_reset");
    }

    /** Invalidates only the asynchronous decision; P0-02 owns full Mission/Task cancellation. */
    public boolean invalidateDecision(AIPlayerEntity bot, String reason) {
        BotConversation conversation = conversations.get(bot.getUUID());
        if (conversation == null || !conversation.decision.invalidateIfBusy()) {
            return false;
        }
        BotLog.comm(bot, "decision_invalidated", "reason", reason);
        return true;
    }

    public boolean clearIntentWakeSources(AIPlayerEntity bot) {
        boolean awaitingCleared = awaitingTask.remove(bot.getUUID()) != null;
        boolean wakeTickCleared = nextGoalWakeTick.remove(bot.getUUID()) != null;
        boolean deferredCleared = deferredRequests.remove(bot.getUUID()) != null;
        return awaitingCleared || wakeTickCleared || deferredCleared;
    }

    /**
     * A model tool was blocked because a SAFETY task is running. The request is remembered instead of
     * being lost: once the bot is idle again the brain is woken to start it
     * ({@link #maybeWakeForFailureOrGoal}) with a fresh call budget, and the budget end of the blocked
     * round stays silent (the request is deferred, not dropped).
     */
    void deferRequestUntilSafetyEnds(AIPlayerEntity bot) {
        deferredRequests.put(bot.getUUID(), true);
    }

    /** Test seam: whether a blocked request is waiting for the SAFETY task to end. */
    public boolean isRequestDeferredForTest(AIPlayerEntity bot) {
        return Boolean.TRUE.equals(deferredRequests.get(bot.getUUID()));
    }

    /** Test-only seam (mirrors {@code PoiAdvisor.setTestTransport}/{@code MiningAssistRuntime.
     * setTestTpsDegraded}): seeds the {@code awaitingTask} wake source directly, exactly as a real
     * conversation turn would leave it (see the {@code TaskManager.INSTANCE.getActive(bot).isPresent()}
     * branch above), without driving a full LLM conversation turn. Lets a GameTest prove that {@code
     * TaskManager.pauseUserIntent}/{@code resumeUserIntent} -- unlike {@code IntentController.pause}/{@code
     * resume}, which call {@link #clearIntentWakeSources} -- leave this wake source untouched (mining-assist
     * design 6.5's stated reason for the P3 POI hold bypassing {@code IntentController}). */
    public void setAwaitingTaskForTest(AIPlayerEntity bot, boolean awaiting) {
        if (awaiting) {
            awaitingTask.put(bot.getUUID(), true);
        } else {
            awaitingTask.remove(bot.getUUID());
        }
    }

    /** Test-only seam pairing {@link #setAwaitingTaskForTest}: reads the {@code awaitingTask} wake source
     * back without going through the heavier {@link #status}/conversation machinery. */
    public boolean isAwaitingTaskForTest(AIPlayerEntity bot) {
        return Boolean.TRUE.equals(awaitingTask.get(bot.getUUID()));
    }

    public void setManualMode(AIPlayerEntity bot, boolean enabled) {
        if (enabled) {
            manualModes.put(bot.getUUID(), true);
        } else {
            manualModes.remove(bot.getUUID());
        }
        BotLog.comm(bot, "manual_mode_set", "enabled", enabled);
    }

    public boolean manualMode(AIPlayerEntity bot) {
        return manualModes.getOrDefault(bot.getUUID(), false);
    }

    public boolean maybeWakeForFailureOrGoal(AIPlayerEntity bot) {
        // GOALFIX-GF1 P0-A: whenever the bot has an active deterministic goal plan, auto-wake
        // (FLOW-2 / failure injection) always defers to GoalExecutor, so the two orchestrators do
        // not race to assign between steps. awaitingTask is NOT cleared here: only after the goal
        // plan itself completes and is removed from activePlans will this method next wake the
        // brain, using awaitingTask, to judge whether the overall intent has been achieved.
        if (io.github.zoyluo.minecraftai.goal.GoalExecutor.INSTANCE.hasActivePlan(bot)) {
            return false;
        }
        boolean hasFailure = TaskManager.INSTANCE.peekFailure(bot).isPresent();
        boolean hasGoal = BotMemoryStore.INSTANCE.of(bot.getUUID()).hasActiveGoal();
        // FLOW-2: the idle-watcher only calls this method when there is no active task, so
        // awaiting=true means "the task the brain assigned has already finished".
        boolean taskJustFinished = Boolean.TRUE.equals(awaitingTask.get(bot.getUUID()));
        boolean requestDeferred = Boolean.TRUE.equals(deferredRequests.get(bot.getUUID()));
        if (!hasFailure && !shouldWakeForGoal(bot, hasGoal) && !taskJustFinished && !requestDeferred) {
            return false;
        }
        ensureConfigured();
        BotConversation conversation = conversations.computeIfAbsent(bot.getUUID(), BotConversation::new);
        if (conversation.decision.busy()) {
            return false;
        }
        if (conversation.history.isEmpty()) {
            conversation.history.add(ChatMessage.system(systemPrompt(bot.getGameProfile().name(), "")));
        }
        conversation.withholdSayNextCall = false;
        // An autonomous wake starts a chain of its own: the last player instruction (never cleared) must
        // not be mistaken for the request of this chain if its budget ends.
        conversation.instructionChain.beginAutonomousWake();
        conversation.continuationTaskPolls = 0;
        // A task failure is reported with its own guaranteed model call, so it is checked before the
        // per-instruction planner budget: an exhausted planner budget must never swallow the report.
        if (hasFailure) {
            InstructionRoundEvaluator.FailureWake failureWake = maybeInjectFailure(bot, conversation);
            if (failureWake == InstructionRoundEvaluator.FailureWake.INJECT) {
                awaitingTask.remove(bot.getUUID());
                trimHistory(conversation);
                submit(bot, conversation, conversation.decision.beginEpoch(), true);
                return true;
            }
            if (failureWake == InstructionRoundEvaluator.FailureWake.REPORT_DIRECTLY) {
                // The player was already told, deterministically. The spent budget must not also
                // trigger finishCallBudget's generic apology: one failure, one report.
                awaitingTask.remove(bot.getUUID());
                return false;
            }
        }
        if (requestDeferred) {
            // The threat that blocked the player's request is handled and the bot is idle: start it now.
            // The blocked round usually spent the instruction's call budget, so the retry gets a fresh
            // one. The player was already told about the delay (or, if the model stayed silent, hears
            // the plan when it starts): the model must not announce the delay a second time.
            deferredRequests.remove(bot.getUUID());
            awaitingTask.remove(bot.getUUID());
            conversation.callBudget.beginPlayerInstruction(0);
            conversation.budgetExhaustionReported = false;
            conversation.lastToolRoundFailureCount = 0;
            conversation.requestStarted = false;
            conversation.failureReportCall = false;
            conversation.lastToolRoundMissingRequiredAction = false;
            conversation.lastToolRoundPlanBlockedAction = false;
            PerceptionSnapshot snapshot = PerceptionCollector.collect(bot);
            conversation.lastPerceptionDigest = perceptionDigest(snapshot);
            String request = conversation.lastInstruction.isBlank()
                    ? "the player's last request"
                    : "the player's request: " + conversation.lastInstruction;
            conversation.history.add(ChatMessage.user(
                    "The threat that blocked your request is handled. Start " + request
                    + " now with the appropriate task tool. Do not tell the player about the delay again."
                    + "\n\nCurrent state:\n" + snapshot.toJson()));
            BotLog.comm(bot, "deferred_request_wake", "instruction", ActionDispatcher.chatLogText(conversation.lastInstruction));
            trimHistory(conversation);
            submit(bot, conversation, conversation.decision.beginEpoch());
            return true;
        }
        if (conversation.callBudget.exhausted()) {
            finishCallBudget(bot, conversation, "automatic_wake");
            return false;
        }
        if (hasGoal && maybeInjectGoalContinuation(bot, conversation, "There is no active task, but the long-term goal is unfinished. Continue the current step and assign a high-level task when needed.")) {
            awaitingTask.remove(bot.getUUID());
            nextGoalWakeTick.put(bot.getUUID(), bot.level().getServer().getTickCount() + 200);
            trimHistory(conversation);
            submit(bot, conversation, conversation.decision.beginEpoch());
            return true;
        }
        // FLOW-2: the task the brain assigned has finished, with no failure and no long-term
        // goal -> auto-wake the brain to decide the next step, no human nudge needed.
        if (taskJustFinished) {
            awaitingTask.remove(bot.getUUID());
            TaskStatus status = TaskManager.INSTANCE.status(bot);
            PerceptionSnapshot snapshot = PerceptionCollector.collect(bot);
            conversation.lastPerceptionDigest = perceptionDigest(snapshot);
            conversation.history.add(ChatMessage.user(
                    "The previous task ended: " + status.name() + " (state " + status.state() + ": " + status.description()
                    + "). If the player's overall request is complete, use say to report that in English and stop; "
                    + "otherwise continue with the next high-level task.\n\nCurrent state:\n" + snapshot.toJson()));
            BotLog.comm(bot, "task_done_wake", "name", status.name(), "state", String.valueOf(status.state()));
            trimHistory(conversation);
            submit(bot, conversation, conversation.decision.beginEpoch());
            return true;
        }
        return false;
    }

    public void shutdown() {
        conversations.values().forEach(conversation -> conversation.decision.invalidate());
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
        conversations.clear();
        manualModes.clear();
        nextGoalWakeTick.clear();
        awaitingTask.clear();
        deferredRequests.clear();
        ChatTranscript.clearAll();
    }

    public BrainStatus status(AIPlayerEntity bot) {
        BotConversation conversation = conversations.get(bot.getUUID());
        if (conversation == null) {
            return new BrainStatus(false, 0, 0, 0, 0);
        }
        return new BrainStatus(
                conversation.decision.busy(),
                conversation.history.size(),
                conversation.lastPromptTokens,
                conversation.lastCompletionTokens,
                conversation.lastCacheHitTokens);
    }

    public void sendPanelChat(AIPlayerEntity bot, String role, String text) {
        MinecraftAiServerNetworking.INSTANCE.sendBotChat(bot, role, text);
    }

    /** Sends the bot's actual reply both to the optional panel and to ordinary Minecraft chat. */
    public void sendBotReply(AIPlayerEntity bot, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String concise = text.length() > 240 ? text.substring(0, 240) : text;
        ChatTranscript.recordBotReply(bot.getUUID(), bot.getGameProfile().name(), concise);
        sendPanelChat(bot, "bot", concise);
        bot.level().getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("<" + bot.getGameProfile().name() + "> ").append(Component.literal(concise)), false);
    }

    private void submit(AIPlayerEntity bot, BotConversation conversation, DecisionLease lease) {
        submit(bot, conversation, lease, false);
    }

    /**
     * {@code failureReport} draws the call from the dedicated failure-report allowance (see
     * {@link PlayerInstructionCallBudget#tryAcquireFailureReportCall}) so a task failure is always
     * reported, whatever the planner already spent on the player's instruction.
     */
    private void submit(AIPlayerEntity bot, BotConversation conversation, DecisionLease lease, boolean failureReport) {
        PlayerInstructionCallBudget.Reservation reservation = PlayerInstructionCallBudget.Reservation.NONE;
        try {
            List<ChatMessage> historySnapshot = MemoryStore.INSTANCE.prepareHistory(bot, List.copyOf(conversation.history));
            MinecraftAiConfig.Brain brainConfig = MinecraftAiConfig.get().brain();
            // A failure report is a say-only job: it never inherits a withheld say from an earlier
            // plan-only round of the instruction.
            boolean withholdSay = !failureReport && conversation.withholdSayNextCall;
            List<ToolDefinition> toolsSnapshot = toolsForCall(toolRegistry.tools(
                    brainConfig,
                    brainConfig.exposesLowLevelTools() || manualMode(bot),
                    BotRuntimeOptions.INSTANCE.memoryToolsEnabled(bot),
                    brainConfig.coordinationToolsEnabled()), withholdSay);
            AsyncDecisionExecutor.GeminiInteractionRequest geminiRequest = executor.usesGeminiInteractions()
                    ? geminiRequestFor(conversation, historySnapshot)
                    : null;
            if (failureReport) {
                reservation = conversation.callBudget.tryAcquireFailureReportCall();
                if (reservation == PlayerInstructionCallBudget.Reservation.NONE) {
                    if (!conversation.decision.failSubmission(lease)) {
                        logStaleDecision(lease, "failure_report_budget_submission");
                        return;
                    }
                    reportPendingFailureWithoutModel(bot, conversation);
                    return;
                }
            } else if (conversation.callBudget.tryAcquireModelCall()) {
                reservation = PlayerInstructionCallBudget.Reservation.REGULAR;
            } else {
                if (!conversation.decision.failSubmission(lease)) {
                    logStaleDecision(lease, "model_call_budget_submission");
                    return;
                }
                finishCallBudget(bot, conversation, "submission");
                return;
            }
            // Reporting a failure is not an unfinished player request: a say alone is the right
            // answer. That is remembered per call (read back by onResponse) instead of by touching
            // the instruction-level request flags, so an errored report call leaves them intact.
            conversation.failureReportCall = failureReport;
            BotLog.comm(bot, "model_call_submitted",
                    "model_call", conversation.callBudget.callsUsed(),
                    "model_calls_remaining", conversation.callBudget.callsRemaining(),
                    "instruction", conversation.callBudget.instructionSequence(),
                    "provider", executor.usesGeminiInteractions() ? "gemini_interactions" : "chat_completions",
                    "continuation", geminiRequest != null && !geminiRequest.initial(),
                    "failure_report", failureReport,
                    "say_withheld", withholdSay,
                    "tools", toolsSnapshot.size());
            executor.submit(
                    bot,
                    lease,
                    historySnapshot,
                    toolsSnapshot,
                    geminiRequest,
                    withholdSay,
                    (responseLease, response) -> onResponse(bot, responseLease, response),
                    (errorLease, throwable) -> onError(bot, errorLease, throwable));
        } catch (RuntimeException exception) {
            conversation.callBudget.releaseFailureReportReservation(reservation);
            if (!conversation.decision.failSubmission(lease)) {
                logStaleDecision(lease, "submission_error");
                return;
            }
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            BotLog.error(bot, "decision_submit_failed", exception, "message", message);
            sendPanelChat(bot, "system", "AI request could not be submitted: " + message);
        }
    }

    private static AsyncDecisionExecutor.GeminiInteractionRequest geminiRequestFor(
            BotConversation conversation,
            List<ChatMessage> history) {
        if (conversation.geminiInteractionId == null || conversation.geminiInteractionId.isBlank()) {
            return AsyncDecisionExecutor.GeminiInteractionRequest.initial(initialGeminiPrompt(history));
        }
        return AsyncDecisionExecutor.GeminiInteractionRequest.continuation(
                conversation.geminiInteractionId,
                conversation.pendingGeminiFunctionResults,
                latestUserMessage(history));
    }

    private static String initialGeminiPrompt(List<ChatMessage> history) {
        StringBuilder prompt = new StringBuilder();
        for (ChatMessage message : history) {
            if (message.content() == null || message.content().isBlank()) {
                continue;
            }
            if ("system".equals(message.role())) {
                appendPromptSection(prompt, "System instructions", message.content());
            } else if ("user".equals(message.role())) {
                appendPromptSection(prompt, "Player request and current world state", message.content());
            }
        }
        return prompt.toString();
    }

    private static String latestUserMessage(List<ChatMessage> history) {
        for (int index = history.size() - 1; index >= 0; index--) {
            ChatMessage message = history.get(index);
            if ("user".equals(message.role()) && message.content() != null && !message.content().isBlank()) {
                return message.content();
            }
        }
        return "Continue the player request using the current tool results.";
    }

    private static void appendPromptSection(StringBuilder prompt, String title, String content) {
        if (!prompt.isEmpty()) {
            prompt.append("\n\n");
        }
        prompt.append(title).append(":\n").append(content);
    }

    private void scheduleContinuation(AIPlayerEntity bot, BotConversation conversation, DecisionLease waitingLease) {
        var server = bot.level().getServer();
        CompletableFuture.delayedExecutor(TpsGuard.INSTANCE.continuationDelaySeconds(), TimeUnit.SECONDS).execute(() ->
                server.execute(() -> {
                    if (conversations.get(waitingLease.botId()) != conversation
                            || !conversation.decision.isWaiting(waitingLease)) {
                        logStaleDecision(waitingLease, "continuation_timer");
                        return;
                    }
                    // GOALFIX-CONT: while a deterministic goal plan is running, never re-wake the
                    // brain -- not even during the single tick where getActive() is briefly empty
                    // between two steps (otherwise the brain would wake and call assign_task,
                    // aborting the goal's current step, which was exactly the real culprit behind
                    // field test #6). Just keep polling and waiting: it does not count toward the
                    // limit and does not force a wake; once the goal ends, hasActivePlan flips to
                    // false and the next continuation round naturally hands the result back to the brain to report.
                    if (io.github.zoyluo.minecraftai.goal.GoalExecutor.INSTANCE.hasActivePlan(bot)) {
                        scheduleContinuation(bot, conversation, waitingLease);
                        return;
                    }
                    if (TaskManager.INSTANCE.getActive(bot).isPresent()
                            || bot.getActionPack().hasActiveActions()) {
                        conversation.continuationTaskPolls++;
                        if (conversation.continuationTaskPolls >= MAX_CONTINUATION_TASK_POLLS) {
                            DecisionLease nextLease = conversation.decision.advanceContinuation(waitingLease).orElse(null);
                            if (nextLease == null) {
                                logStaleDecision(waitingLease, "continuation_advance");
                                return;
                            }
                            try {
                                PerceptionSnapshot snapshot = PerceptionCollector.collect(bot);
                                conversation.history.add(ChatMessage.user("Task or action is still active after waiting for continuation. Current state:\n" + snapshot.toJson()));
                                trimHistory(conversation);
                                BotLog.warn(LogCategory.COMM, bot, "continuation_wait_limit_reached", "polls", conversation.continuationTaskPolls);
                                submit(bot, conversation, nextLease);
                            } catch (RuntimeException exception) {
                                failContinuationPreparation(bot, conversation, nextLease, exception);
                            }
                            return;
                        }
                        scheduleContinuation(bot, conversation, waitingLease);
                        return;
                    }
                    DecisionLease nextLease = conversation.decision.advanceContinuation(waitingLease).orElse(null);
                    if (nextLease == null) {
                        logStaleDecision(waitingLease, "continuation_advance");
                        return;
                    }
                    try {
                        conversation.continuationTaskPolls = 0;
                        if (maybeInjectGoalResult(bot, conversation)) {
                            trimHistory(conversation);
                            submit(bot, conversation, nextLease);
                            return;
                        }
                        InstructionRoundEvaluator.FailureWake failureWake = maybeInjectFailure(bot, conversation);
                        if (failureWake == InstructionRoundEvaluator.FailureWake.INJECT) {
                            trimHistory(conversation);
                            submit(bot, conversation, nextLease, true);
                            return;
                        }
                        if (failureWake == InstructionRoundEvaluator.FailureWake.REPORT_DIRECTLY) {
                            // Every call is spent and the player was just told directly. A further
                            // continuation call could only end in a second, generic apology.
                            if (!conversation.decision.failSubmission(nextLease)) {
                                logStaleDecision(nextLease, "failure_reported_directly");
                            }
                            return;
                        }
                        TaskStatus status = TaskManager.INSTANCE.status(bot);
                        if (status.state() == io.github.zoyluo.minecraftai.task.TaskState.COMPLETED
                                && maybeInjectGoalContinuation(bot, conversation, "The previous task completed: " + status.description() + ". Advance the next step for the long-term goal; call advance_goal first if this step is complete.")) {
                            trimHistory(conversation);
                            submit(bot, conversation, nextLease);
                            return;
                        }
                        PerceptionSnapshot snapshot = PerceptionCollector.collect(bot);
                        conversation.lastPerceptionDigest = perceptionDigest(snapshot);
                        String correction = conversation.withholdSayNextCall
                                ? "You already announced your plan but did not start any work, so the say tool is not "
                                + "available for this call. Call the action or goal tool that carries out the player's "
                                + "request now (for example follow to come to or stay with the player, hold to stay put, "
                                + "eat, gather, break_blocks, mine_ore, achieve_goal). Do not answer with text.\n\n"
                                : conversation.lastToolRoundPlanBlockedAction
                                ? "Your action tool calls were not run because you did not announce a plan first. "
                                + "If the player asked only a question, call say with purpose=answer and use only "
                                + "read-only tools. Otherwise call say with purpose=plan and a non-empty English plan "
                                + "before the first action or goal tool in this response.\n\n"
                                : conversation.lastToolRoundMissingRequiredAction
                                ? "You did not start an in-world action. If the player asked only a question, call say "
                                + "with purpose=answer. Otherwise call an appropriate action or goal tool now; a plan or "
                                + "status say alone is not enough. For grass, call clear_grass with the requested count. "
                                + "If part of the request cannot be done with your available tools, start the part(s) "
                                + "that can be done now with an action or goal tool, and call say with purpose=answer "
                                + "to plainly tell the player which part cannot be done and why.\n\n"
                                : conversation.lastToolRoundFailureCount > 0
                                        ? "One or more tool calls just failed. Read every function result, correct the bad or "
                                        + "missing argument (or choose a valid alternative), and do not repeat the same invalid call. "
                                        + "No task is running yet.\n\n"
                                        : "";
                        conversation.history.add(ChatMessage.user(correction
                                + "Updated state after tool calls:\n" + snapshot.toJson()));
                        trimHistory(conversation);
                        submit(bot, conversation, nextLease);
                    } catch (RuntimeException exception) {
                        failContinuationPreparation(bot, conversation, nextLease, exception);
                    }
                }));
    }

    private void failContinuationPreparation(AIPlayerEntity bot,
                                             BotConversation conversation,
                                             DecisionLease lease,
                                             RuntimeException exception) {
        if (!conversation.decision.failSubmission(lease)) {
            logStaleDecision(lease, "continuation_preparation_error");
            return;
        }
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        BotLog.error(bot, "continuation_preparation_failed", exception, "message", message);
        try {
            sendPanelChat(bot, "system", "AI continuation setup failed; this thinking round stopped safely: " + message);
        } catch (RuntimeException notificationException) {
            BotLog.error(bot, "continuation_failure_notification_failed", notificationException);
        }
    }

    /**
     * Ends only the planner loop. A task that was successfully started is allowed to finish;
     * repeated no-work failures are reset to a clean idle state and reported in normal chat. A mission
     * that a safety task paused counts as work: resetToIdle would destroy its cursor, and
     * DangerWatcher resumes exactly that paused work once the threat is gone.
     */
    private void finishCallBudget(AIPlayerEntity bot, BotConversation conversation, String trigger) {
        boolean pausedMission = TaskManager.INSTANCE.hasPaused(bot);
        boolean runtimeWork = hasRuntimeWork(
                TaskManager.INSTANCE.getActive(bot).isPresent(),
                io.github.zoyluo.minecraftai.goal.GoalExecutor.INSTANCE.hasActivePlan(bot),
                io.github.zoyluo.minecraftai.goal.GoalExecutor.INSTANCE.queuedGoalCount(bot),
                bot.getActionPack().hasActiveActions());
        // A paused mission protects the mission (no idle reset below), never the silence: the report
        // still depends only on whether THIS instruction started.
        boolean workActive = runtimeWork || pausedMission;
        BotLog.warn(LogCategory.COMM, bot, "model_call_budget_exhausted",
                "calls_used", conversation.callBudget.callsUsed(),
                "call_limit", conversation.callBudget.callsUsed() + conversation.callBudget.callsRemaining(),
                "instruction", conversation.callBudget.instructionSequence(),
                "trigger", trigger,
                "work_active", workActive,
                "last_failed_tool_calls", conversation.lastToolRoundFailureCount,
                "last_missing_required_action", conversation.lastToolRoundMissingRequiredAction,
                "last_initial_plan_blocked_action", conversation.lastToolRoundPlanBlockedAction);
        // Silence is only acceptable while the player's request is genuinely under way. Anything else
        // (the planner never started it, even if some unrelated work happens to be running) must be
        // told to the player -- a dropped command with no word is the worst outcome.
        // The planner loop is over: a withheld say must not leak into a later wake-up call.
        conversation.withholdSayNextCall = false;
        if (deferredRequests.containsKey(bot.getUUID())) {
            // The request was blocked by a running SAFETY task and is kept for when the threat ends
            // (maybeWakeForFailureOrGoal): it is deferred, not dropped, so no "could not start" apology.
            BotLog.comm(bot, "budget_end_silent_request_deferred", "trigger", trigger);
            return;
        }
        InstructionRoundEvaluator.BudgetReport report = InstructionRoundEvaluator.budgetReport(
                conversation.budgetExhaustionReported,
                workActive,
                conversation.requestStarted,
                conversation.instructionChain.playerInstruction() && !conversation.lastInstruction.isBlank(),
                "automatic_wake".equals(trigger));
        if (report == InstructionRoundEvaluator.BudgetReport.SILENT) {
            return;
        }
        boolean requestNeverStarted = report == InstructionRoundEvaluator.BudgetReport.COULD_NOT_START;
        conversation.budgetExhaustionReported = true;
        if (InstructionRoundEvaluator.shouldResetToIdleAtBudgetEnd(runtimeWork, pausedMission)) {
            io.github.zoyluo.minecraftai.goal.GoalExecutor.INSTANCE.clear(bot);
            TaskManager.INSTANCE.resetToIdle(bot);
            bot.getActionPack().stopAll();
            awaitingTask.remove(bot.getUUID());
        }
        sendBotReply(bot, requestNeverStarted
                ? couldNotStartMessage(conversation.lastInstruction)
                : "Sorry, I could not work out how to do that. Could you say it another way?");
    }

    private void reportFailureWithoutModel(AIPlayerEntity bot, String name, String reason) {
        BotLog.warn(LogCategory.COMM, bot, "failure_reported_without_model_call",
                "name", name, "reason", reason);
        sendBotReply(bot, failureFallbackMessage(name, reason));
    }

    private void reportPendingFailureWithoutModel(AIPlayerEntity bot, BotConversation conversation) {
        reportFailureWithoutModel(bot,
                conversation.lastFailureName == null ? "task" : conversation.lastFailureName,
                conversation.lastFailureReason == null ? "unknown" : conversation.lastFailureReason);
    }

    private static String conciseFailureMessage(String message) {
        if (message == null || message.isBlank()) {
            return "unknown API error";
        }
        String singleLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        return singleLine.length() > 180 ? singleLine.substring(0, 180) : singleLine;
    }

    private void ensureConfigured() {
        if (executor == null) {
            configure(MinecraftAiConfig.get());
        }
    }

    private void trimHistory(BotConversation conversation) {
        int max = MinecraftAiConfig.get().brain().maxHistoryMessages();
        if (conversation.history.size() <= max) {
            return;
        }
        ChatMessage system = conversation.history.peekFirst();
        List<ChatMessage> rest = new ArrayList<>(conversation.history);
        conversation.history.clear();
        if (system != null && "system".equals(system.role())) {
            conversation.history.add(system);
            rest = rest.subList(1, rest.size());
        }
        int keep = Math.max(0, max - conversation.history.size());
        conversation.history.addAll(trimmedTail(rest, keep));
    }

    /**
     * Returns the last {@code keep} messages of {@code rest}, skipping forward past any
     * leading {@code role="tool"} messages. A "tool" message only ever follows its owning
     * assistant(tool_calls) message within the same round (onResponse always appends that
     * assistant message before its tool-result messages), so a tool message still at the
     * front of the count-based cut necessarily lost its assistant owner to the cut; keeping
     * it anyway would open the trimmed history with an orphaned tool result.
     */
    static List<ChatMessage> trimmedTail(List<ChatMessage> rest, int keep) {
        int start = Math.max(0, rest.size() - keep);
        while (start < rest.size() && "tool".equals(rest.get(start).role())) {
            start++;
        }
        return rest.subList(start, rest.size());
    }

    private InstructionRoundEvaluator.FailureWake maybeInjectFailure(AIPlayerEntity bot, BotConversation conversation) {
        InstructionRoundEvaluator.FailureWake wake = InstructionRoundEvaluator.failureWake(
                TaskManager.INSTANCE.peekFailure(bot).isPresent(),
                conversation.callBudget.canAcquireFailureReportCall());
        if (wake == InstructionRoundEvaluator.FailureWake.REPORT_DIRECTLY) {
            // Even the dedicated failure-report allowance is spent (a task that keeps failing): tell
            // the player directly instead of leaving the failure unreported or looping the model.
            // The caller must treat this as the one and only report of that failure.
            return TaskManager.INSTANCE.consumeFailure(bot)
                    .map(failure -> {
                        reportFailureWithoutModel(bot, failure.name(), failure.reason());
                        return InstructionRoundEvaluator.FailureWake.REPORT_DIRECTLY;
                    })
                    .orElse(InstructionRoundEvaluator.FailureWake.NONE);
        }
        return TaskManager.INSTANCE.consumeFailure(bot)
                .map(failure -> {
                    conversation.lastFailureName = failure.name();
                    conversation.lastFailureReason = failure.reason();
                    int maxRetries = MinecraftAiConfig.get().brain().maxTaskRetries();
                    String retryHint = failure.count() >= maxRetries
                            ? " The same failure has happened repeatedly; prefer a different approach or explain the limitation with say."
                            : "";
                    String strategyHint = failure.count() >= 2
                            ? " The same task and reason failed repeatedly. Do not retry unchanged; switch tools or strategy, or obtain the prerequisites first."
                            : "";
                    String executableHint = executableFailureHint(failure);
                    PerceptionSnapshot snapshot = PerceptionCollector.collect(bot);
                    conversation.lastPerceptionDigest = perceptionDigest(snapshot);
                    conversation.history.add(ChatMessage.user("The previous task failed: "
                            + failure.name()
                            + ", reason: "
                            + failure.reason()
                            + " (attempt "
                            + failure.count()
                            + "). Decide whether to obtain prerequisites and retry, use another method, or explain why it cannot be completed with say."
                            + retryHint
                            + strategyHint
                            + executableHint
                            + "\n\nCurrent state:\n"
                            + snapshot.toJson()));
                    BotLog.comm(bot, "failure_injected",
                            "name", failure.name(),
                            "reason", failure.reason(),
                            "count", failure.count(),
                            "tick", failure.tick());
                    return InstructionRoundEvaluator.FailureWake.INJECT;
                })
                .orElse(InstructionRoundEvaluator.FailureWake.NONE);
    }

    private boolean maybeInjectGoalResult(AIPlayerEntity bot, BotConversation conversation) {
        return io.github.zoyluo.minecraftai.goal.GoalExecutor.INSTANCE
                .resultAfter(bot, conversation.lastGoalResultSequence)
                .map(result -> {
                    conversation.lastGoalResultSequence = result.sequence();
                    conversation.history.add(ChatMessage.user(
                            "Goal terminal result (authoritative): status=" + result.status()
                                    + ", matched=" + result.evaluation().matched()
                                    + "/" + result.evaluation().required()
                                    + ", reason=" + result.reason()
                                    + ", unmet=" + result.evaluation().unmet()
                                    + ". Only COMPLETED may be described as complete; PARTIAL, FAILED, or CANCELLED must be reported truthfully."));
                    BotLog.comm(bot, "goal_result_injected",
                            "sequence", result.sequence(),
                            "status", result.status(),
                            "matched", result.evaluation().matched(),
                            "required", result.evaluation().required());
                    return true;
                })
                .orElse(false);
    }

    private static String executableFailureHint(TaskManager.FailureRecord failure) {
        String reason = failure.reason() == null ? "" : failure.reason();
        if (reason.startsWith("no_exposed_ore:use_strip_mine")) {
            return " Actionable guidance: the target is ore but there are no exposed ore blocks nearby. Do not use mine again; use strip_mine or assign_task strip_mine with target_ores set to the target ore.";
        }
        return "";
    }

    private boolean maybeInjectGoalContinuation(AIPlayerEntity bot, BotConversation conversation, String reason) {
        String goal = BotMemoryStore.INSTANCE.of(bot.getUUID()).goalDriveStatus("");
        if (goal.isBlank()) {
            return false;
        }
        PerceptionSnapshot snapshot = PerceptionCollector.collect(bot);
        conversation.lastPerceptionDigest = perceptionDigest(snapshot);
        conversation.history.add(ChatMessage.user(reason
                + "\n\nLong-term goal status:\n"
                + goal
                + "\n\nCurrent state:\n"
                + snapshot.toJson()));
        BotLog.comm(bot, "goal_continuation_injected", "reason", reason);
        return true;
    }

    private boolean shouldWakeForGoal(AIPlayerEntity bot, boolean hasGoal) {
        if (!hasGoal) {
            return false;
        }
        return bot.level().getServer().getTickCount() >= nextGoalWakeTick.getOrDefault(bot.getUUID(), 0);
    }

    private static String perceptionDigest(PerceptionSnapshot snapshot) {
        String json = snapshot.toJson();
        return json.length() <= 1400 ? json : json.substring(0, 1397) + "...";
    }

    private static String replayResult(List<ChatMessage> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (ChatMessage message : toolResults) {
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append(message.toolCallId()).append(":").append(message.content());
            if (builder.length() > 1600) {
                return builder.substring(0, 1597) + "...";
            }
        }
        return builder.toString();
    }

    private static void logStaleDecision(DecisionLease lease, String callback) {
        BotLog.commSystem("stale_decision_dropped",
                "bot_uuid", lease.botId(),
                "session_id", lease.sessionId(),
                "epoch", lease.epoch(),
                "request_sequence", lease.requestSequence(),
                "callback", callback);
    }

    /**
     * Package-private (not private) so the prompt assembly is directly unit-testable, mirroring
     * {@link #trimmedTail}. {@code speakingParty} is the name of the player or bot whose message
     * is starting this fresh instruction; pass "" or null for the automatic-wake path, which has
     * no single speaking party.
     */
    static String systemPrompt(String botName, String speakingParty) {
        String speakerLine = speakingParty == null || speakingParty.isBlank()
                ? ""
                : " The player or bot currently speaking to you is " + speakingParty
                        + "; you are " + botName + " and you speak and act only as " + botName + ", never on their behalf.";
        return """
                You are a player in Minecraft named %s. You exist as a real player in the world and can interact with it using the tools provided.%s

                Game version: this is Minecraft Java Edition 1.21.11 exactly. Your built-in memory of items, recipes, enchantments and mechanics is from older versions and is often out of date (for example copper tools and armor, spears and the Lunge enchantment exist in this version). Never tell the player that an item, recipe or enchantment does not exist, cannot be crafted, or works a certain way from memory: call lookup_recipe (read-only, cheap) to verify first, and trust its result over your memory even when the player has not corrected you.

                Rules:
                1. Understand the human's intent first, then break it into tool calls.
                2. Coordinates are integers (block positions).
                3. Prefer high-level deterministic tasks for survival work. For ores or raw ore materials, use mine_ore; it automatically prepares the required pickaxe before mining. For an item/tool goal such as iron_pickaxe or iron_ingot, use achieve_goal. Do not manually decompose these into gather/craft/mine steps unless the goal tool fails.
                4. Low-level tools such as move_to, mine_block, select_hotbar, and place_block are for one-off manual actions only. Do not use them for gathering materials or placing a crafting table for recipes unless the human explicitly asks for manual control.
                5. A new player message always supersedes prior work. The runtime cancels old tasks, goals, queued goals, and actions before this request is planned, so treat each new message as self-contained. For a compound request in one message, goal tools (achieve_goal, mine_ore, harvest_crop, provision_food, set_goal) may be queued in that same response. High-level tasks run over multiple ticks; start only one non-goal task at a time and wait for its status before assigning another.
                6. Before beginning requested work, call say with purpose=plan and a short one-sentence, player-facing plan in English. State the important first steps and why (for example, "I will gather cobblestone for tools and a foundation, then collect wood for the house."). Then call the action or goal tool that starts the work in the SAME response. A plan/status say alone is invalid: it starts nothing, the runtime will strip say from your next call and force an action tool, and if you still start nothing the player is told you could not do it. Never answer an action request with say alone. For "come here" / "come to me" call follow (it walks to the player); for "stay here" call hold; for "follow me" call follow; for "eat until full" call eat. For a pure question, opinion, or advice request, call say with purpose=answer only; do not start work merely because the question mentions an action (for example, "Do you think this is the best spot to mine?"). If the player asks about "this", "there", a building, terrain, or a route, use Speaker visual context when it is present. It is a real sample of the speaker's current line of sight, not a screenshot or a complete map: mention only evidence it contains. If it has no visible target or lacks enough evidence, say you cannot see enough to judge instead of inventing details. Use purpose=status only after work has started. The say tool appears in ordinary Minecraft chat as well as the MinecraftAi panel.
                7. For an item or tool the player wants obtained from whatever materials are available, use achieve_goal directly even when materials may be missing. It is the deterministic dependency planner and will gather, craft, mine, smelt, and use an existing or newly made crafting table as needed. Use plan_craft only when the player explicitly asks for a feasibility or material breakdown; it is read-only. Use craft only when the player explicitly wants a one-step craft and the required materials are already carried. Do not decompose an item goal into assign_task, mine, smelt, planks, or sticks yourself.
                8. For 3x3 recipes, do not manually select or place a crafting table. If a crafting table is nearby or in inventory, the craft task can use or place it.
                9. For "mine iron ore", call mine_ore with ore=minecraft:iron_ore. For "mine the whole/entire vein", "this vein" or "until the vein is gone", call mine_ore with mode=vein (optionally x/y/z of an ore in that vein): it mines only that connected vein, stops, and reports the count; never approximate a vein with a count. For "make an iron pickaxe" or "get iron ingots", call achieve_goal with item=minecraft:iron_pickaxe or minecraft:iron_ingot. The deterministic goal executor will plan gathering, crafting, mining, and smelting. A single mine_ore/achieve_goal call runs the entire multi-step plan autonomously. The only allowed companion call in that same response is the initial say plan required by rule 6; after that, STOP. Do not call inventory, assign_task, mine, or strip_mine. For wheat, carrots, or potatoes, call harvest_crop with crop=wheat/carrot/potato; it auto-prepares a hoe, tills, plants, waits, and harvests. To clear or hit grass/tall grass, call clear_grass with the requested count; it counts actual plants broken, not seed drops. For "break N leaves" / "clear the leaves", call break_blocks with block=leaves (any leaf type) and the exact count; drops are irrelevant and it uses shears or a hoe if carried, otherwise bare hands (never craft shears for it). Use gather only when the player wants leaf blocks in the inventory (that needs shears). For an explicit request to break, remove, or clear a precise number of another nearby block where drops do not matter, call break_blocks with its exact block id and count; it counts physical blocks broken and will not roam or tunnel. Use gather only when the player wants new inventory items: count always means the additional amount to collect, so "gather 3 logs" means collect 3 more even if logs are already carried. For water travel: launch_boat puts a boat into nearby safe water (crafting one first if needed), board_boat enters a nearby boat, boat_follow handles launch, boarding, and steering after a named player or the owner, and exit_boat dismounts safely. For "build a house", call build_house (blueprint optional); it auto-gathers all materials then builds.
                10. After each action, look at the next world state (passed in user messages) and decide the next step.
                11. When the task is complete or impossible, say so and stop calling tools.
                12. You are fully autonomous and self-reliant. NEVER ask the human for help, for resources, or to move/carry you — the human will not help. NEVER mine ore with bare hands and NEVER use strip_mine or assign_task mine to dig without a proper pickaxe (that wastes blocks and drops nothing). To get ore always use mine_ore, and to get an item/tool use achieve_goal — these automatically walk to find wood, craft the needed pickaxe, then mine. If mine_ore/achieve_goal reports it cannot proceed, just retry the SAME mine_ore once (do NOT switch to an easier or different goal such as achieve_goal a pickaxe — mine_ore already auto-prepares the pickaxe, so switching only loses the real goal); if it still cannot, state the situation in one short sentence and stop — do not flail with move/strip_mine and do not beg.

                Available tools are declared in the tools field. You MUST use them; do not invent tools. All player-facing replies and plans must be concise English.
                """.formatted(botName, speakerLine);
    }

    private static final class BotConversation {
        private final DecisionSession decision;
        private final Deque<ChatMessage> history = new ArrayDeque<>();
        private final PlayerInstructionCallBudget callBudget;
        private int continuationTaskPolls;
        private boolean budgetExhaustionReported;
        private int lastToolRoundFailureCount;
        // This instruction's request has started: a work-start tool of THIS instruction succeeded.
        // Deliberately not "some runtime work is active" (unrelated autonomous work says nothing about
        // the player's command). Reset for every new instruction, never touched by failure reports.
        private boolean requestStarted;
        // The call in flight is a failure report (set per call by submit, read by onResponse).
        private boolean failureReportCall;
        // Distinct from requestStarted (which requires the paired action to have actually
        // succeeded/be active): a plan already spoken this turn should not be demanded again just
        // because the FOLLOWING action call failed on unrelated grounds (e.g. a bad tool argument).
        private boolean initialPlanSpoken;
        private boolean lastToolRoundMissingRequiredAction;
        private boolean lastToolRoundPlanBlockedAction;
        private String geminiInteractionId;
        private List<GeminiInteractionsApiClient.FunctionResult> pendingGeminiFunctionResults = List.of();
        private int lastPromptTokens;
        private int lastCompletionTokens;
        private int lastCacheHitTokens;
        private long lastGoalResultSequence;
        private String lastPerceptionDigest = "";
        // The say tool is removed from the next call after a say(plan)-only round (see shouldWithholdSay).
        private boolean withholdSayNextCall;
        private String lastInstruction = "";
        private final InstructionRoundEvaluator.InstructionChain instructionChain =
                new InstructionRoundEvaluator.InstructionChain();
        private String lastFailureName;
        private String lastFailureReason;

        private BotConversation(UUID botId) {
            decision = new DecisionSession(botId);
            callBudget = new PlayerInstructionCallBudget(MinecraftAiConfig.get().brain().maxTurnsPerRequest());
        }
    }

    public record BrainStatus(boolean busy, int historySize, int promptTokens, int completionTokens, int cacheHitTokens) {
    }
}

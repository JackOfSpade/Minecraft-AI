package io.github.zoyluo.minecraftai.brain;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.perception.PerceptionCollector;
import io.github.zoyluo.minecraftai.perception.PerceptionSnapshot;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import io.github.zoyluo.minecraftai.task.TaskManager;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Occasional, unprompted bot-to-bot chat: at most once per {@code conversation.cooldownTicks},
 * 2+ currently-eligible companions, the first of whom has noticed another, have a short in-character
 * exchange, each line its own independent LLM call. Modeled on {@link ChatRecipientRouter}: its own client, its own thread
 * pool and lifecycle, entirely outside {@link BrainCoordinator}'s per-bot planner/tool-loop, so it
 * never disrupts, consumes the call budget of, or is throttled by whatever a bot is actually doing.
 * <p>
 * Server-wide, not per-bot: {@link #tick(MinecraftServer)} is called once per server tick from the
 * mod's own {@code END_SERVER_TICK} hook, and drives at most one conversation at a time.
 * <p>
 * The "reading + thinking" pause between lines and the next line's LLM call happen concurrently:
 * the instant a line is posted, {@link #fireNextTurn} both starts the timer AND submits the next
 * speaker's request, so the reply is already sitting in {@link ActiveConversation#pendingLine}
 * once the pause elapses (see {@link #driveActive}).
 */
public final class AmbientConversationCoordinator {
    public static final AmbientConversationCoordinator INSTANCE = new AmbientConversationCoordinator();

    private final Object lifecycleLock = new Object();
    private OpenAiCompatibleApiClient client;
    private ExecutorService executor;
    private long generation;

    private long nextEligibleTick;
    private ActiveConversation active;

    private AmbientConversationCoordinator() {
    }

    public void configure(MinecraftAiConfig config) {
        Objects.requireNonNull(config, "config");
        synchronized (lifecycleLock) {
            generation++;
            if (executor != null) {
                executor.shutdownNow();
            }
            client = new OpenAiCompatibleApiClient(config.llm());
            executor = Executors.newFixedThreadPool(2);
        }
    }

    public void shutdown() {
        synchronized (lifecycleLock) {
            generation++;
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            client = null;
        }
        active = null;
    }

    /** Clears in-progress/cooldown state at a world boundary; the client/executor survive this. */
    public void reset() {
        active = null;
        nextEligibleTick = 0L;
    }

    public void tick(MinecraftServer server) {
        if (active != null) {
            driveActive(server);
        } else {
            maybeStart(server);
        }
    }

    private void maybeStart(MinecraftServer server) {
        MinecraftAiConfig.Conversation cfg = MinecraftAiConfig.get().conversation();
        if (!Boolean.TRUE.equals(cfg.enabled())) {
            return;
        }
        long now = server.getTickCount();
        if (now < nextEligibleTick || !shouldCheckThisTick(now, cfg.checkIntervalTicks())) {
            return;
        }
        SplittableRandom random = new SplittableRandom(System.nanoTime());
        if (random.nextDouble() >= cfg.startChancePerCheck()) {
            return;
        }

        List<AIPlayerEntity> eligible = eligibleBots();
        if (!hasEnoughParticipants(eligible.size(), cfg)) {
            return;
        }
        int count = pickParticipantCount(eligible.size(), requiredParticipants(cfg), cfg.maxParticipants(), random);
        List<AIPlayerEntity> chosen = shuffleAndTake(eligible, count, random);
        // The opening line is said to somebody the speaker has noticed (its view cone and hearing, docs/PERCEPTION.md): companions
        // who have not set eyes on each other start nothing, however many of them are online.
        int opener = indexOfOpener(chosen, ObservableWorldQuery::canNoticeCreature);
        if (opener < 0) {
            return;
        }
        Collections.rotate(chosen, -opener);

        ActiveConversation conversation = new ActiveConversation(chosen.stream().map(AIPlayerEntity::getUUID).toList());
        active = conversation;
        BotLog.lifecycle("ambient_conversation_started",
                "participants", chosen.stream().map(bot -> bot.getGameProfile().name()).toList());
        fireNextTurn(server, conversation);
    }

    private static List<AIPlayerEntity> eligibleBots() {
        Collection<AIPlayerEntity> all = AIPlayerManager.INSTANCE.all();
        List<AIPlayerEntity> eligible = new ArrayList<>(all.size());
        for (AIPlayerEntity bot : all) {
            boolean inSafetyTask = TaskManager.INSTANCE.activeOrigin(bot).map(TaskOrigin::safety).orElse(false);
            boolean brainBusy = BrainCoordinator.INSTANCE.status(bot).busy();
            if (isEligible(inSafetyTask, brainBusy)) {
                eligible.add(bot);
            }
        }
        return eligible;
    }

    private static boolean addresseePresent(ActiveConversation conversation, UUID speakerId) {
        return hasAddressee(conversation.order, speakerId, id -> AIPlayerManager.INSTANCE.getByUuid(id).isPresent());
    }

    private void driveActive(MinecraftServer server) {
        ActiveConversation conversation = active;
        if (conversation == null) {
            return;
        }
        if (!conversation.pendingReady || server.getTickCount() < conversation.revealAtTick) {
            return;
        }
        revealAndAdvance(server, conversation);
    }

    private void revealAndAdvance(MinecraftServer server, ActiveConversation conversation) {
        UUID speakerId = conversation.order.get(conversation.index);
        AIPlayerEntity speaker = AIPlayerManager.INSTANCE.getByUuid(speakerId).orElse(null);
        if (speaker == null) {
            endConversation(server, "speaker_gone");
            return;
        }
        if (!addresseePresent(conversation, speakerId)) {
            endConversation(server, "no_addressee");
            return;
        }
        String line = conversation.pendingLine;
        // Unprompted chat never passes through the say tool, so log its text here the same way a
        // say call is logged (otherwise only token counts of these lines ever reach the logs).
        BotLog.comm(speaker, "ambient_say",
                "message", ActionDispatcher.chatLogText(line),
                "turn", conversation.index + 1,
                "of", conversation.order.size());
        BrainCoordinator.INSTANCE.sendBotReply(speaker, line);
        conversation.transcript.add(new TranscriptEntry(speaker.getGameProfile().name(), line));
        conversation.index++;
        conversation.pendingLine = null;
        conversation.pendingReady = false;

        if (conversation.index >= conversation.order.size()) {
            endConversation(server, "complete");
            return;
        }
        fireNextTurn(server, conversation);
    }

    private void endConversation(MinecraftServer server, String reason) {
        MinecraftAiConfig.Conversation cfg = MinecraftAiConfig.get().conversation();
        BotLog.lifecycle("ambient_conversation_ended", "reason", reason);
        active = null;
        nextEligibleTick = server.getTickCount() + Math.max(0, cfg.cooldownTicks());
    }

    private void fireNextTurn(MinecraftServer server, ActiveConversation conversation) {
        UUID speakerId = conversation.order.get(conversation.index);
        AIPlayerEntity speaker = AIPlayerManager.INSTANCE.getByUuid(speakerId).orElse(null);
        if (speaker == null) {
            endConversation(server, "speaker_gone");
            return;
        }
        // Every line, canned or model-written, is spoken to the other participants: with none of them left there is nobody to hear it.
        if (!addresseePresent(conversation, speakerId)) {
            endConversation(server, "no_addressee");
            return;
        }
        MinecraftAiConfig.Conversation cfg = MinecraftAiConfig.get().conversation();
        String previousLine = conversation.transcript.isEmpty()
                ? null : conversation.transcript.get(conversation.transcript.size() - 1).text();
        conversation.revealAtTick = server.getTickCount() + computeDelayTicks(previousLine, cfg);
        conversation.pendingReady = false;

        boolean mustBeStatement = conversation.index == conversation.order.size() - 1;
        List<String> others = conversation.order.stream()
                .filter(id -> !id.equals(speakerId))
                .map(id -> AIPlayerManager.INSTANCE.getByUuid(id).map(bot -> bot.getGameProfile().name()).orElse("someone"))
                .toList();
        PerceptionSnapshot snapshot = PerceptionCollector.collect(speaker);
        // With literally no line-of-sight scene fact, a free-form model has nothing from which it
        // may honestly describe terrain or travel.  Keep this ambient turn social by construction
        // instead of relying only on a prompt to resist inventing a plausible scene after a task
        // failure.  This is deliberately based on evidence availability, not a list of words such
        // as "cliff" or "stone".
        if (!hasSceneObservation(snapshot.highlights())) {
            // The canned line claims no company (it used to say "keeping each other company") and is spoken only once the speaker has
            // noticed a participant, so a bot that is alone never addresses anyone.
            if (!hasAddressee(conversation.order, speakerId, id -> AIPlayerManager.INSTANCE.getByUuid(id)
                    .filter(other -> ObservableWorldQuery.canNoticeCreature(speaker, other)).isPresent())) {
                endConversation(server, "no_addressee_in_view");
                return;
            }
            conversation.pendingLine = ambientSocialFallback(mustBeStatement);
            conversation.pendingReady = true;
            BotLog.comm(speaker, "ambient_social_fallback_no_scene_evidence",
                    "turn", conversation.index + 1,
                    "of", conversation.order.size());
            return;
        }
        List<ChatMessage> history = List.of(
                ChatMessage.system(systemPrompt(speaker.getGameProfile().name(), others, mustBeStatement)),
                ChatMessage.user(userPayload(conversation.transcript, snapshot)));

        OpenAiCompatibleApiClient requestClient;
        ExecutorService worker;
        long requestGeneration;
        synchronized (lifecycleLock) {
            requestClient = client;
            worker = executor;
            requestGeneration = generation;
        }
        if (requestClient == null || worker == null || worker.isShutdown()) {
            endConversation(server, "client_unavailable");
            return;
        }

        int maxTokens = cfg.maxTokens();
        try {
            worker.submit(() -> {
                try {
                    ChatResponse response = requestClient.chat(history, List.of());
                    String line = response == null ? null : response.content();
                    invokeOnServer(server, requestGeneration, worker, conversation,
                            () -> onLineReady(server, conversation, line, mustBeStatement));
                } catch (Exception exception) {
                    invokeOnServer(server, requestGeneration, worker, conversation,
                            () -> onLineFailed(server, conversation, exception));
                }
            });
        } catch (RuntimeException exception) {
            onLineFailed(server, conversation, exception);
        }
    }

    private void onLineReady(MinecraftServer server, ActiveConversation conversation, String line, boolean mustBeStatement) {
        if (line == null || line.isBlank()) {
            endConversation(server, "empty_reply");
            return;
        }
        String trimmed = line.trim();
        if (mustBeStatement && endsWithQuestion(trimmed)) {
            BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.COMM, null,
                    "ambient_conversation_last_line_was_a_question", "line", trimmed);
        }
        conversation.pendingLine = trimmed;
        conversation.pendingReady = true;
    }

    private void onLineFailed(MinecraftServer server, ActiveConversation conversation, Throwable cause) {
        BotLog.error("ambient_conversation_call_failed", cause);
        endConversation(server, "call_failed");
    }

    /** Mirrors {@link ChatRecipientRouter}'s stale-callback guard, plus a same-conversation check. */
    private void invokeOnServer(MinecraftServer server,
                                long requestGeneration,
                                ExecutorService requestExecutor,
                                ActiveConversation requestConversation,
                                Runnable callback) {
        server.execute(() -> {
            synchronized (lifecycleLock) {
                if (generation != requestGeneration || executor != requestExecutor) {
                    return;
                }
            }
            if (active != requestConversation) {
                return;
            }
            callback.run();
        });
    }

    private static String systemPrompt(String botName, List<String> others, boolean mustBeStatement) {
        String companions = others.isEmpty() ? "your fellow companion" : String.join(" and ", others);
        String turnRule = mustBeStatement
                ? "You are the LAST one speaking in this conversation -- nobody else will reply after you. "
                        + "End with a statement or remark, NOT a question, since there is no one left to answer it."
                : "You may end with a question or a statement, whichever feels natural.";
        return """
                You are %s, an AI companion in Minecraft. You are having a short, casual,
                in-character conversation with %s while going about your day. This is NOT a task request from a
                player -- nobody is asking you to do anything right now. Just chat naturally like a real person
                would, reacting to what has been said so far (if anything) and to your own current surroundings
                and situation.

                Rules:
                1. Reply with exactly one short, natural sentence or two. No more.
                2. Stay in character: casual, a little personality is good, not robotic or overly formal.
                3. Treat only the `confirmed current observations` section as evidence for a physical-world claim.
                   An omitted material, place, route, or resource is UNKNOWN -- it is never evidence that it is absent.
                   The conversation transcript is dialogue, not world evidence.
                4. This turn has NO travel or action history. Never claim that you tried, searched, found, failed to
                   find, walked to, visited, or inspected a place. Never invent terrain, a landmark, a route, or a
                   location. A task's outcome, an inventory, and missing observations cannot prove any of those things.
                5. React to the conversation so far if there is any; otherwise start a casual social topic. When no
                   confirmed observation is relevant, speak socially rather than filling in scene details.
                6. %s
                7. Do not use any tools. Do not narrate actions. Reply with only the spoken line, nothing else.
                """.formatted(botName, companions, turnRule);
    }

    /**
     * Builds a deliberately narrow ambient-chat context.  A full {@link PerceptionSnapshot} contains
     * task bookkeeping (including failures) and historical memory that are useful to the planner,
     * but neither proves that the bot travelled somewhere or saw terrain there.  Passing those
     * fields to a free-form social model caused failed gather tasks to turn into invented reports
     * about cliffs and materials.  Ambient chat receives only current, observation-fenced highlights
     * and self facts; absence from this list is explicitly unknown.
     */
    static String userPayload(List<TranscriptEntry> transcript, PerceptionSnapshot snapshot) {
        Objects.requireNonNull(transcript, "transcript");
        Objects.requireNonNull(snapshot, "snapshot");
        StringBuilder builder = new StringBuilder();
        builder.append("Conversation so far:\n");
        if (transcript.isEmpty()) {
            builder.append("(nothing yet -- you are starting it)\n");
        } else {
            for (TranscriptEntry entry : transcript) {
                builder.append(entry.speakerName()).append(": ").append(entry.text()).append('\n');
            }
        }
        builder.append("\nGrounding contract:\n")
                .append("- The observations below are confirmed only for this exact moment.\n")
                .append("- Their absence means unknown, not absent.\n")
                .append("- No task state, task failure, travel history, route history, or remembered location is provided.\n")
                .append("- Do not treat dialogue above as physical-world evidence.\n")
                .append("\nSelf facts:\n");
        appendSelfFacts(builder, snapshot.self());
        builder.append("\nConfirmed current observations:\n");
        int observationCount = appendObservationFacts(builder, snapshot.highlights());
        if (observationCount == 0) {
            builder.append("(No direct scene observations were supplied. Keep the topic social and non-spatial.)\n");
        }
        return builder.toString();
    }

    private static void appendSelfFacts(StringBuilder builder, PerceptionSnapshot.SelfState self) {
        if (self == null) {
            builder.append("(No self facts supplied.)\n");
            return;
        }
        builder.append("- holding: ").append(self.holdingItem()).append('\n')
                .append("- health: ").append(self.hp()).append('\n')
                .append("- hunger: ").append(self.hunger()).append('\n');
    }

    private static boolean hasSceneObservation(PerceptionSnapshot.Highlights highlights) {
        return highlights != null && (hasObservations(highlights.nearest_tree())
                || hasObservations(highlights.nearest_stone())
                || hasObservations(highlights.nearest_ore())
                || hasObservations(highlights.nearest_water())
                || hasObservations(highlights.nearest_furnace())
                || hasObservations(highlights.nearest_chest())
                || hasObservations(highlights.nearest_bed())
                || hasObservations(highlights.nearest_crafting_table())
                || hasObservations(highlights.nearest_hostile()));
    }

    private static boolean hasObservations(List<?> observations) {
        return observations != null && !observations.isEmpty();
    }

    private static String ambientSocialFallback(boolean mustBeStatement) {
        return mustBeStatement
                ? "I'm glad we got to chat."
                : "It's nice to stop and chat for a bit.";
    }

    private static int appendObservationFacts(StringBuilder builder, PerceptionSnapshot.Highlights highlights) {
        if (highlights == null) {
            return 0;
        }
        int count = 0;
        count += appendBlocks(builder, "tree", highlights.nearest_tree());
        count += appendBlocks(builder, "stone", highlights.nearest_stone());
        count += appendBlocks(builder, "ore", highlights.nearest_ore());
        count += appendBlocks(builder, "water", highlights.nearest_water());
        count += appendBlocks(builder, "furnace", highlights.nearest_furnace());
        count += appendBlocks(builder, "chest", highlights.nearest_chest());
        count += appendBlocks(builder, "bed", highlights.nearest_bed());
        count += appendBlocks(builder, "crafting table", highlights.nearest_crafting_table());
        count += appendEntities(builder, "hostile creature", highlights.nearest_hostile());
        return count;
    }

    private static int appendBlocks(StringBuilder builder,
                                    String category,
                                    List<PerceptionSnapshot.NearbyBlock> observations) {
        if (observations == null || observations.isEmpty()) {
            return 0;
        }
        int appended = 0;
        for (PerceptionSnapshot.NearbyBlock observation : observations) {
            if (observation == null || observation.type() == null || observation.type().isBlank()) {
                continue;
            }
            builder.append("- observed ").append(category).append(": ")
                    .append(observation.type()).append(" at ")
                    .append(observation.distance()).append(" blocks\n");
            appended++;
        }
        return appended;
    }

    private static int appendEntities(StringBuilder builder,
                                      String category,
                                      List<PerceptionSnapshot.NearbyEntity> observations) {
        if (observations == null || observations.isEmpty()) {
            return 0;
        }
        int appended = 0;
        for (PerceptionSnapshot.NearbyEntity observation : observations) {
            if (observation == null || observation.type() == null || observation.type().isBlank()) {
                continue;
            }
            builder.append("- observed ").append(category).append(": ")
                    .append(observation.type()).append(" at ")
                    .append(observation.distance()).append(" blocks\n");
            appended++;
        }
        return appended;
    }

    // ---- Pure, unit-testable decision logic ----

    static boolean shouldCheckThisTick(long tick, int checkIntervalTicks) {
        return checkIntervalTicks > 0 && tick % checkIntervalTicks == 0;
    }

    static boolean isEligible(boolean inSafetyTask, boolean brainBusy) {
        return !inSafetyTask && !brainBusy;
    }

    /** A conversation is an exchange between companions: a lone bot has nobody to address, whatever the configured minimum. */
    static final int MIN_CONVERSATION_PARTICIPANTS = 2;

    static int requiredParticipants(MinecraftAiConfig.Conversation cfg) {
        return Math.max(MIN_CONVERSATION_PARTICIPANTS, cfg.minParticipants());
    }

    static boolean hasEnoughParticipants(int eligibleCount, MinecraftAiConfig.Conversation cfg) {
        return eligibleCount >= requiredParticipants(cfg);
    }

    /** Whether a participant other than the speaker is still around to hear the line. */
    static boolean hasAddressee(List<UUID> order, UUID speakerId, Predicate<UUID> present) {
        for (UUID id : order) {
            if (!id.equals(speakerId) && present.test(id)) {
                return true;
            }
        }
        return false;
    }

    /** Index of the first participant who has noticed another one (so can open the conversation to somebody it perceives), or -1. */
    static <T> int indexOfOpener(List<T> participants, BiPredicate<T, T> hasNoticed) {
        for (int i = 0; i < participants.size(); i++) {
            for (int j = 0; j < participants.size(); j++) {
                if (i != j && hasNoticed.test(participants.get(i), participants.get(j))) {
                    return i;
                }
            }
        }
        return -1;
    }

    static int pickParticipantCount(int eligibleCount, int minParticipants, int maxParticipants, SplittableRandom random) {
        int lo = Math.max(1, Math.min(minParticipants, eligibleCount));
        int hi = Math.max(lo, Math.min(maxParticipants, eligibleCount));
        return lo == hi ? lo : lo + random.nextInt(hi - lo + 1);
    }

    static <T> List<T> shuffleAndTake(List<T> items, int count, SplittableRandom random) {
        List<T> copy = new ArrayList<>(items);
        int take = Math.min(count, copy.size());
        List<T> result = new ArrayList<>(take);
        for (int i = 0; i < take; i++) {
            int remaining = copy.size() - i;
            int pick = i + random.nextInt(remaining);
            T chosen = copy.get(pick);
            copy.set(pick, copy.get(i));
            copy.set(i, chosen);
            result.add(chosen);
        }
        return result;
    }

    static boolean endsWithQuestion(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.stripTrailing();
        return trimmed.endsWith("?");
    }

    /** Reading time (words / reading speed) plus a thinking buffer proportional to length, clamped. */
    static int computeDelayTicks(String previousLine, MinecraftAiConfig.Conversation cfg) {
        if (previousLine == null || previousLine.isBlank()) {
            return 0;
        }
        int wordCount = previousLine.trim().split("\\s+").length;
        double readingSeconds = wordCount / (cfg.readingWordsPerMinute() / 60.0D);
        double thinkingSeconds = wordCount * cfg.thinkingSecondsPerWord();
        double totalSeconds = Math.max(cfg.minReplyDelaySeconds(),
                Math.min(cfg.maxReplyDelaySeconds(), readingSeconds + thinkingSeconds));
        return (int) Math.round(totalSeconds * 20.0D);
    }

    private record TranscriptEntry(String speakerName, String text) {
    }

    private static final class ActiveConversation {
        private final List<UUID> order;
        private final List<TranscriptEntry> transcript = new ArrayList<>();
        private int index;
        private String pendingLine;
        private boolean pendingReady;
        private long revealAtTick;

        private ActiveConversation(List<UUID> order) {
            this.order = order;
        }
    }
}

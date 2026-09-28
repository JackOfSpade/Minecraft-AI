package io.github.zoyluo.minecraftai.brain;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.goal.GoalExecutor;
import io.github.zoyluo.minecraftai.goal.GoalResult;
import io.github.zoyluo.minecraftai.task.TaskState;
import io.github.zoyluo.minecraftai.task.TaskStatus;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BotReporter {
    public static final BotReporter INSTANCE = new BotReporter();

    private static final int MIN_REPORT_INTERVAL_TICKS = 80;
    private static final Pattern COUNT_PATTERN = Pattern.compile("(\\d+)/(\\d+)");
    private final Map<UUID, ReportState> states = new ConcurrentHashMap<>();
    // Per-bot logical task-chat sequence: useful for runtime diagnostics and deterministic restore
    // assertions without depending on whether a panel subscriber happens to be connected.
    private final Map<UUID, Long> taskReportSequences = new ConcurrentHashMap<>();

    private BotReporter() {
    }

    public void onAssigned(AIPlayerEntity bot, TaskStatus status) {
        if (!enabled(bot)) {
            return;
        }
        ReportState state = new ReportState(status.name(), status.description(), 25);
        states.put(bot.getUuid(), state);
        report(bot, state, "Starting " + summary(status) + ".", bot.getEntityWorld().getServer().getTicks(), true);
    }

    public void onStatus(MinecraftServer server, AIPlayerEntity bot, TaskStatus status) {
        if (!enabled(bot)) {
            return;
        }
        if (status.name().equals("idle")) {
            states.remove(bot.getUuid());
            return;
        }
        ReportState state = states.computeIfAbsent(bot.getUuid(),
                ignored -> new ReportState(status.name(), status.description(), 25));
        if (!state.taskName.equals(status.name())) {
            state.taskName = status.name();
            state.taskDescription = status.description();
            state.nextMilestone = 25;
            report(bot, state, "Starting " + summary(status) + ".", server.getTicks(), true);
        }
        switch (status.state()) {
            case RUNNING -> reportProgress(server, bot, status, state);
            case PAUSED -> report(bot, state, "Pausing " + summary(status) + " for now.", server.getTicks(), false);
            case COMPLETED -> {
                String prefix = GoalExecutor.INSTANCE.hasActivePlan(bot) ? "Step complete: " : "Completed: ";
                report(bot, state, prefix + summary(status) + ".", server.getTicks(), true);
                states.remove(bot.getUuid());
            }
            case FAILED -> {
                report(bot, state, "Could not complete " + summary(status) + ". "
                        + ReasonText.friendly(status.failureReason()), server.getTicks(), true);
                states.remove(bot.getUuid());
            }
            case CANCELLED -> {
                report(bot, state, "Cancelled: " + summary(status) + ".", server.getTicks(), true);
                states.remove(bot.getUuid());
            }
            default -> {
            }
        }
    }

    public void onCleared(AIPlayerEntity bot) {
        states.remove(bot.getUuid());
        taskReportSequences.remove(bot.getUuid());
    }

    public void clearAll() {
        states.clear();
        taskReportSequences.clear();
    }

    public long taskReportSequence(AIPlayerEntity bot) {
        return taskReportSequences.getOrDefault(bot.getUuid(), 0L);
    }

    public void onGoalMessage(AIPlayerEntity bot, String text) {
        if (!enabled(bot)) {
            return;
        }
        // Goal transitions are meaningful companion updates (new phase/replan), not hidden
        // diagnostics.  Put them in ordinary game chat as well as the optional panel.
        BrainCoordinator.INSTANCE.sendBotReply(bot, text);
    }

    /** Terminal Goal facts are never hidden by verbose progress settings. */
    public void onGoalResult(AIPlayerEntity bot, GoalResult.Status status, String text) {
        BrainCoordinator.INSTANCE.sendBotReply(bot, "[" + status.name() + "] " + text);
    }

    private void reportProgress(MinecraftServer server, AIPlayerEntity bot, TaskStatus status, ReportState state) {
        int percent = (int) Math.floor(status.progress() * 100.0D);
        if (percent < state.nextMilestone || state.nextMilestone >= 100) {
            return;
        }
        int milestone = state.nextMilestone;
        state.nextMilestone += 25;
        report(bot, state, progressText(status, milestone), server.getTicks(), false);
    }

    private void report(AIPlayerEntity bot, ReportState state, String text, int tick, boolean force) {
        if (text.equals(state.lastText)) {
            return;
        }
        if (!force && tick - state.lastTick < MIN_REPORT_INTERVAL_TICKS) {
            return;
        }
        state.lastText = text;
        state.lastTick = tick;
        taskReportSequences.merge(bot.getUuid(), 1L, Long::sum);
        BrainCoordinator.INSTANCE.sendBotReply(bot, text);
    }

    private static boolean enabled(AIPlayerEntity bot) {
        return BotRuntimeOptions.INSTANCE.verboseReportsEnabled(bot);
    }

    private static String progressText(TaskStatus status, int milestone) {
        Matcher matcher = COUNT_PATTERN.matcher(status.description());
        if (matcher.find()) {
            return "Progress on " + summary(status) + ": " + matcher.group(1) + "/" + matcher.group(2) + ".";
        }
        return "About " + milestone + "% complete: " + summary(status) + ".";
    }

    private static String summary(TaskStatus status) {
        String description = status.description() == null ? "" : status.description();
        return switch (status.name()) {
            case "mine" -> "mining " + objectAfter(description, "Mining ");
            case "craft" -> "crafting " + objectAfter(description, "Crafting ");
            case "smelt" -> "smelting " + objectAfter(description, "Smelting ");
            case "move" -> "moving to " + description.replace("Walking to ", "");
            case "eat" -> "eating";
            case "sleep" -> "sleeping";
            case "combat" -> "fighting";
            case "evade" -> "avoiding danger";
            case "light_area" -> "lighting this area";
            case "build" -> "building " + objectAfter(description, "Building ");
            case "forage" -> "foraging " + objectAfter(description, "Foraging ");
            case "gather" -> "gathering " + objectAfter(description, "Gathering ");
            case "mine_valuables" -> "mining nearby valuables";
            case "hunt" -> "hunting for food";
            case "dig_down" -> "digging downward";
            default -> ReasonText.taskName(status.name());
        };
    }

    private static String objectAfter(String description, String prefix) {
        if (!description.startsWith(prefix)) {
            return "";
        }
        String value = description.substring(prefix.length());
        int phase = value.indexOf(" phase=");
        if (phase >= 0) {
            value = value.substring(0, phase);
        }
        return ReasonText.itemText(value);
    }

    private static final class ReportState {
        private String taskName;
        private String taskDescription;
        private int nextMilestone;
        private String lastText = "";
        private int lastTick = Integer.MIN_VALUE / 2;

        private ReportState(String taskName, String taskDescription, int nextMilestone) {
            this.taskName = taskName;
            this.taskDescription = taskDescription;
            this.nextMilestone = nextMilestone;
        }
    }
}

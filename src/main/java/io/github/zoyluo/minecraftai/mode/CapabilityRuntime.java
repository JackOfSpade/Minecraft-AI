package io.github.zoyluo.minecraftai.mode;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mining.MiningEvidenceAudit;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Minecraft adapter for the pure capability policy. Every privileged execution is decided first. */
public final class CapabilityRuntime {
    /** How often {@link #flushDue} actually sweeps (every 100 ticks = 5 s). */
    static final int SWEEP_PERIOD_TICKS = 100;
    private static final CapabilityAuditThrottle AUDIT = new CapabilityAuditThrottle();
    /** Bot names, so pending counts can still be reported after the entity itself is gone. */
    private static final ConcurrentHashMap<UUID, String> BOT_NAMES = new ConcurrentHashMap<>();
    private static volatile int lastTick;

    private CapabilityRuntime() {
    }

    public static CapabilityDecision decide(AIPlayerEntity bot,
                                            PrivilegedCapability capability,
                                            String context) {
        MinecraftAiConfig config = MinecraftAiConfig.get();
        CapabilityDecision decision = CapabilityPolicy.decide(
                config.profile(), config.operatorCapabilities(), capability);
        MiningEvidenceAudit.recordCapabilityDecision(bot, decision.allowed());
        String normalizedContext = context == null ? "" : context;
        int now = bot.getEntityWorld().getServer().getTicks();
        lastTick = now;
        BOT_NAMES.put(bot.getUuid(), bot.getGameProfile().name());
        boolean alwaysAudit = capability == PrivilegedCapability.MANUAL_TELEPORT
                || (capability == PrivilegedCapability.EMERGENCY_TELEPORT && decision.allowed());
        CapabilityAuditThrottle.Outcome outcome = AUDIT.observe(bot.getUuid(), capability, decision.allowed(),
                decision.reason(), normalizedContext, now, alwaysAudit);
        if (outcome.logDecision()) {
            BotLog.action(bot, "capability_decision",
                    "profile", decision.profile().configValue(),
                    "capability", decision.capability(),
                    "allowed", decision.allowed(),
                    "reason", decision.reason(),
                    "context", normalizedContext);
        }
        if (outcome.summary() != null) {
            logSummary(bot.getGameProfile().name(), outcome.summary(), decision.profile().configValue());
        }
        return decision;
    }

    public static boolean run(AIPlayerEntity bot,
                              PrivilegedCapability capability,
                              String context,
                              Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        if (!decide(bot, capability, context).allowed()) {
            return false;
        }
        operation.run();
        return true;
    }

    public static <T> Result<T> call(AIPlayerEntity bot,
                                     PrivilegedCapability capability,
                                     String context,
                                     Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        CapabilityDecision decision = decide(bot, capability, context);
        return decision.allowed()
                ? new Result<>(decision, true, operation.get())
                : new Result<>(decision, false, null);
    }

    public static void clear(AIPlayerEntity bot) {
        // Flush counted-but-unreported repeats first so the last window of a bot's life is not lost.
        int now = bot.getEntityWorld().getServer() == null ? 0 : bot.getEntityWorld().getServer().getTicks();
        for (CapabilityAuditThrottle.Summary summary : AUDIT.drain(bot.getUuid(), now)) {
            logSummary(botName(summary), summary, MinecraftAiConfig.get().profile().configValue());
        }
        AUDIT.clear(bot.getUuid());
        BOT_NAMES.remove(bot.getUuid());
    }

    /**
     * Periodic sweep (a few times per minute from the server tick): reports every pending repeat count whose
     * window has run its full length, so a lone repeat after the first occurrence is never left unreported
     * until the bot goes away. Bounded delay: {@link CapabilityAuditThrottle#SUMMARY_INTERVAL_TICKS} plus
     * {@link #SWEEP_PERIOD_TICKS}.
     */
    public static void flushDue(int serverTick) {
        if (serverTick % SWEEP_PERIOD_TICKS != 0) {
            return;
        }
        lastTick = serverTick;
        for (CapabilityAuditThrottle.Summary summary : AUDIT.drainDue(serverTick)) {
            logSummary(botName(summary), summary, MinecraftAiConfig.get().profile().configValue());
        }
    }

    /** World boundary, server stop and reload: flush every pending repeat count first, then forget everything. */
    public static void clearAll() {
        for (CapabilityAuditThrottle.Summary summary : AUDIT.drainAll(lastTick)) {
            logSummary(botName(summary), summary, MinecraftAiConfig.get().profile().configValue());
        }
        AUDIT.clearAll();
        BOT_NAMES.clear();
        lastTick = 0;
    }

    private static String botName(CapabilityAuditThrottle.Summary summary) {
        return BOT_NAMES.getOrDefault(summary.key().botId(), "-");
    }

    private static void logSummary(String botName, CapabilityAuditThrottle.Summary summary, String profile) {
        BotLog.actionNamed(botName, "capability_decision_summary",
                "profile", profile,
                "capability", summary.key().capability(),
                "allowed", summary.key().allowed(),
                "reason", summary.key().reason(),
                "repeats", summary.count(),
                "window_ticks", summary.windowTicks(),
                "contexts", String.join(",", summary.contexts()) + (summary.otherContexts() > 0 ? ",+" + summary.otherContexts() + "_more" : ""));
    }

    public record Result<T>(CapabilityDecision decision, boolean executed, T value) {
    }
}

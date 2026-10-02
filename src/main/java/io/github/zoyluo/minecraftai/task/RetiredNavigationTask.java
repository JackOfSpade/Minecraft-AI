package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;

/**
 * Compatibility boundary for automatic excavation routes retired in favor of observed Baritone
 * navigation. It deliberately performs no physical action.
 */
public final class RetiredNavigationTask extends AbstractTask {
    public static final String OBSERVED_TARGET_REQUIRED = "navigation_observed_target_required";

    private final String operation;

    public RetiredNavigationTask(String operation) {
        this.operation = operation == null || operation.isBlank() ? "legacy_navigation" : operation;
    }

    @Override
    public String name() {
        return operation;
    }

    @Override
    public String describe() {
        return "Awaiting an observed target for " + operation;
    }

    @Override
    public double progress() {
        return 0.0D;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        refuse(bot, operation);
        fail(OBSERVED_TARGET_REQUIRED);
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        // onStart always fails closed.
    }

    /** Logs a single durable audit event for an old automatic excavation entry point. */
    public static void refuse(AIPlayerEntity bot, String operation) {
        BotLog.action(bot, "legacy_navigation_retired",
                "operation", operation == null || operation.isBlank() ? "legacy_navigation" : operation,
                "reason", OBSERVED_TARGET_REQUIRED);
    }

    /** Kept as a method so retained checkpoint decoders remain source-compatible but cannot act. */
    public static boolean legacyExcavationDisabled() {
        return true;
    }
}

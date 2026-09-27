package io.github.zoyluo.aibot.mining.assist;

import java.util.function.BooleanSupplier;

/**
 * What the coordinator does for one bot in one tick (mining-assist design 2.3, steps 3d and 3e), as a
 * pure decision so the order of the checks can be unit-tested without a server.
 *
 * <p>The checks run cheapest first and every supplier is evaluated only if all earlier checks passed:
 * a bot that is not in a mining task never pays for the gate, and a bot whose gate is closed never pays
 * for the sky read. In particular the tick a watcher already {@code handled} does no sensing at all
 * (design 2.3 step 3d: "if handled, stop here").</p>
 */
public final class SensePlan {
    /** The outcome of one tick's plan. Only {@link #SENSE} runs the sensor. */
    public enum Verdict {
        /** A watcher (danger scan) took over this tick. Says nothing about whether the bot still mines. */
        HANDLED("handled"),
        /** The active task is not one of the sensed mining classes. */
        NOT_MINING("not_mining_task"),
        /** The assist gate is closed for this bot: mode, harness, origin, audit session or TPS. */
        GATE_CLOSED("gate_closed"),
        /** The sky is visible at the bot's feet: the sensor only runs underground. */
        SURFACE("surface"),
        /** Run the sensor. */
        SENSE("sensing");

        private final String reason;

        Verdict(String reason) {
            this.reason = reason;
        }

        /** Stable snake_case token for the {@code assist_sense_disabled} log line. */
        public String reason() {
            return reason;
        }

        public boolean senses() {
            return this == SENSE;
        }

        /** A neutral tick must not change the sensing status (it is neither sensing nor a real stop). */
        public boolean neutral() {
            return this == HANDLED;
        }
    }

    private SensePlan() {
    }

    /**
     * @param handled     the danger scan handled this bot this tick
     * @param miningTask  the active task is one of the five sensed classes
     * @param gateOpen    {@code MiningAssistRuntime.enabledFor(bot, tick)}
     * @param underground {@code !world.isSkyVisible(feet)}
     */
    public static Verdict decide(boolean handled, BooleanSupplier miningTask, BooleanSupplier gateOpen,
                                 BooleanSupplier underground) {
        if (handled) {
            return Verdict.HANDLED;
        }
        if (!miningTask.getAsBoolean()) {
            return Verdict.NOT_MINING;
        }
        if (!gateOpen.getAsBoolean()) {
            return Verdict.GATE_CLOSED;
        }
        if (!underground.getAsBoolean()) {
            return Verdict.SURFACE;
        }
        return Verdict.SENSE;
    }
}

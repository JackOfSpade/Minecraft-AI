package io.github.zoyluo.minecraftai.task;

/**
 * Pure bounds for the visible part of gather's surveys.  Keeping this separate from the task
 * avoids initializing Minecraft registries merely to test the observation boundary.
 */
final class ObservableSearchBounds {
    static final int MAX_SURVEY_RADIUS = 48;
    static final int MAX_PROSPECT_RADIUS = 96;

    private ObservableSearchBounds() {
    }

    static int surveyRadius(int configuredPerceptionRadius) {
        return Math.max(1, Math.min(MAX_SURVEY_RADIUS, configuredPerceptionRadius));
    }

    static int prospectRadius(int configuredPerceptionRadius) {
        return Math.max(1, Math.min(MAX_PROSPECT_RADIUS, configuredPerceptionRadius));
    }
}

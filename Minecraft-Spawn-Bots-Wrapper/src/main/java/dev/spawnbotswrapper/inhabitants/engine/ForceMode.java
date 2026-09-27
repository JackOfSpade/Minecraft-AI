package dev.spawnbotswrapper.inhabitants.engine;

/** How an admin-forced processing of a not-yet-processed structure decides occupied vs abandoned. */
public enum ForceMode {
    /** Run the normal roll (chance from the config). */
    ROLL,
    /** Skip the roll: occupied. Still uses the configured bot-count range. */
    OCCUPIED,
    /** Skip the roll: abandoned. */
    ABANDONED
}

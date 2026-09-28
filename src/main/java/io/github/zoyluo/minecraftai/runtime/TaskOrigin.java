package io.github.zoyluo.aibot.runtime;

import java.util.UUID;

public record TaskOrigin(Kind kind, UUID missionId, UUID jobId, String reason) {
    public enum Kind {
        MISSION,
        PLAYER_COMMAND,
        PLAYER_PANEL,
        LLM_TOOL,
        JOB,
        SAFETY,
        SYSTEM_BACKGROUND,
        VERIFY
    }

    public TaskOrigin {
        kind = kind == null ? Kind.SYSTEM_BACKGROUND : kind;
        reason = reason == null ? "" : reason;
    }

    public boolean safety() {
        return kind == Kind.SAFETY;
    }

    /**
     * A stable, human-greppable tag identifying the request this origin belongs to, for smart
     * log scoping (see {@link io.github.zoyluo.aibot.log.BotLog}). {@code missionId}/{@code jobId}
     * already stay constant across every sub-task of one player-issued instruction (GoalExecutor
     * threads the same missionId through every step's TaskOrigin), so reusing it here means every
     * log line emitted anywhere during that instruction's execution can be filtered to exactly that
     * one request with a single grep -- no per-call-site changes needed anywhere else. Ambient/
     * background work (safety interrupts, idle-watcher scans, verification) is not a player request,
     * so it shares one coarse tag per kind rather than a unique id per instance.
     */
    public String scopeId() {
        UUID id = missionId != null ? missionId : jobId;
        return switch (kind) {
            case MISSION, JOB, PLAYER_COMMAND, PLAYER_PANEL, LLM_TOOL ->
                    kind.name().toLowerCase(java.util.Locale.ROOT) + ':' + (id != null ? shortId(id) : "adhoc");
            case SAFETY -> "safety";
            case VERIFY -> "verify";
            case SYSTEM_BACKGROUND -> "background";
        };
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    public static TaskOrigin of(Kind kind, String reason) {
        return new TaskOrigin(kind, null, null, reason);
    }

    public static TaskOrigin mission(UUID missionId, String reason) {
        return new TaskOrigin(Kind.MISSION, missionId, null, reason);
    }

    public static TaskOrigin job(UUID jobId, String reason) {
        return new TaskOrigin(Kind.JOB, null, jobId, reason);
    }

    public static TaskOrigin safety(String reason) {
        return of(Kind.SAFETY, reason);
    }
}

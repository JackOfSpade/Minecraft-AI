package io.github.zoyluo.minecraftai.persist;

public record PersistedBot(BotRecord bot, MissionRuntimeRecord missions) {
    public PersistedBot {
        missions = missions == null ? MissionRuntimeRecord.empty() : missions;
    }
}

package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;

/** Compatibility adapter retained while ancient-city quiet-zone behavior is disabled. */
public final class QuietZone {
    public enum Level {
        NONE,
        CAUTION,
        SILENT
    }

    /** Environment-specific zones no longer alter pace. */
    public void refresh(AIPlayerEntity bot) {
    }

    public void invalidate() {
    }

    public Level level() {
        return Level.NONE;
    }

}

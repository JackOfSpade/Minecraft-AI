package dev.spawnbotswrapper.inhabitants.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The console commands run once at every server start: the addon-managed PvP BOT settings first, then the
 * operator's own {@code startupCommands}. Pure (no Minecraft), so the plan is testable.
 * <p>
 * The addon never writes PvP BOT's settings files. It only issues PvP BOT's own {@code /pvpbot settings ...}
 * command, exactly like an operator would, so PvP BOT validates and persists the value itself.
 */
public final class StartupCommands {
    /** The PvP BOT settings key (as accepted by {@code /pvpbot settings}) of the crit fall phase, PvP BOT 0.0.15. */
    public static final String CRIT_FALL_TICKS_KEY = "crit-fall-ticks";

    private StartupCommands() {
    }

    /** The command that sets the managed critical fall ticks, e.g. {@code pvpbot settings crit-fall-ticks 3}. */
    public static String critFallTicksCommand(int ticks) {
        return "pvpbot settings " + CRIT_FALL_TICKS_KEY + " " + ticks;
    }

    /**
     * Managed commands followed by the operator's own, blank entries dropped. The managed crit setting is
     * left out when it is switched off ({@code criticalFallTicks <= 0}) or when the operator already sets it
     * explicitly in {@code startupCommands} (an explicit choice wins).
     */
    public static List<String> plan(InhabitantsConfig config) {
        List<String> out = new ArrayList<>();
        List<String> own = config.startupCommands == null ? List.of() : config.startupCommands;
        if (config.criticalFallTicks > 0 && !anySets(own, CRIT_FALL_TICKS_KEY)) {
            out.add(critFallTicksCommand(config.criticalFallTicks));
        }
        for (String command : own) {
            if (command != null && !command.isBlank()) {
                out.add(command);
            }
        }
        return out;
    }

    private static boolean anySets(List<String> commands, String settingKey) {
        for (String command : commands) {
            if (command == null) {
                continue;
            }
            String[] words = command.trim().toLowerCase(Locale.ROOT).replaceFirst("^/", "").split("\\s+");
            if (words.length >= 3 && words[0].equals("pvpbot") && words[1].equals("settings")
                    && words[2].equals(settingKey)) {
                return true;
            }
        }
        return false;
    }
}

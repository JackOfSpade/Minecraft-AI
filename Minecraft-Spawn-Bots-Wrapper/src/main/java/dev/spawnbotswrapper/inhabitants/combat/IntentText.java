package dev.spawnbotswrapper.inhabitants.combat;

/**
 * The text form of what PvP BOT currently intends for a bot (its target, weapon mode and bow-draw state), appended to
 * the state snapshot of the diagnostics as {@code pvpbot=global(...) target=Steve mode=RANGED draw=1/12}. Pure text
 * formatting; the Minecraft side reads the values through the adapter.
 */
public final class IntentText {
    private IntentText() {
    }

    /**
     * @param targetName the target's name, or null for none
     * @param mode       PvP BOT's weapon mode ("RANGED", "MELEE", ...); null when unreadable (then omitted)
     * @param drawing    whether PvP BOT believes it is drawing a bow or crossbow; null when unreadable (then omitted)
     * @param drawTicks  ticks of that draw; null when unreadable
     */
    public static String of(String targetName, String mode, Boolean drawing, Integer drawTicks) {
        StringBuilder sb = new StringBuilder("target=").append(targetName == null ? "none" : targetName);
        if (mode != null) {
            sb.append(" mode=").append(mode);
        }
        if (drawing != null) {
            sb.append(" draw=").append(drawing ? "1" : "0");
            if (drawing && drawTicks != null) {
                sb.append('/').append(drawTicks);
            }
        }
        return sb.toString();
    }

    /** The intent when PvP BOT's per-bot state cannot be read at all (bot not listed, a name missing upstream). */
    public static String unreadable() {
        return "target=unreadable";
    }

    /** The global switches text followed by the per-bot intent text; either part may be null. */
    public static String join(String global, String intent) {
        if (global == null) {
            return intent;
        }
        return intent == null ? global : global + " " + intent;
    }
}

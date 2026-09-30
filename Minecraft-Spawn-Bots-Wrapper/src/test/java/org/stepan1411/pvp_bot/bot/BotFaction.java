package org.stepan1411.pvp_bot.bot;

import org.stepan1411.testdouble.Recorder;

import java.util.HashMap;
import java.util.Map;

/** Test-only fake of the upstream faction registry: just the ally question. */
public class BotFaction {

    private static final Map<String, String> FACTION = new HashMap<>();

    public static boolean areAllies(String a, String b) {
        Recorder.guard("areAllies", a + "," + b);
        String fa = FACTION.get(a);
        String fb = FACTION.get(b);
        return fa != null && fb != null && fa.equals(fb);
    }

    /** Test control (not part of upstream). */
    public static void put(String player, String faction) {
        FACTION.put(player, faction);
    }

    public static void resetAll() {
        FACTION.clear();
    }
}

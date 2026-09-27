package org.stepan1411.pvp_bot.bot;

import org.stepan1411.testdouble.Recorder;

/** Test-only fake of the upstream navigation helper: only the per-bot state removal the adapter calls. */
public class BotNavigation {

    public static void removeState(String botName) {
        Recorder.guard("removeState", botName);
    }
}

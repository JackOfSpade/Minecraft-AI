package dev.spawnbotswrapper.inhabitants.adapter;

import org.stepan1411.pvp_bot.bot.BotCombat;
import org.stepan1411.pvp_bot.bot.BotFaction;
import org.stepan1411.pvp_bot.bot.BotNavigation;
import org.stepan1411.pvp_bot.bot.BotPath;
import org.stepan1411.pvp_bot.bot.BotSettings;
import org.stepan1411.testdouble.Recorder;
import java.util.List;

/** Shared setup of the adapter tests: a fresh fake upstream, a probed adapter, the captured log. */
final class AdapterFixture {

    static final CommandTree FULL_TREE = new CommandTree(true, true, true, true, true, true, true);

    final TestEnvironment env;
    final RecordingSink sink = new RecordingSink();
    final PvpBotAdapter adapter;

    private AdapterFixture(TestEnvironment env, ClassLocator locator, SpawnBackend backend) {
        this.env = env;
        this.adapter = new PvpBotAdapter("0.1.0-test", env, locator, sink, backend);
    }

    /** Resets the shared fake upstream state; every test that touches the fakes starts here. */
    static void resetUpstream() {
        Recorder.reset();
        BotPath.resetAll();
        BotSettings.resetInstance();
        BotCombat.resetAll();
        BotFaction.resetAll();
        BotNavigation.CALLS.clear();
    }

    static AdapterFixture healthy() {
        resetUpstream();
        return new AdapterFixture(TestEnvironment.standard(), TestLocators.canonical(), SpawnBackend.AUTO);
    }

    static AdapterFixture with(TestEnvironment env, ClassLocator locator, SpawnBackend backend) {
        resetUpstream();
        return new AdapterFixture(env, locator, backend);
    }

    static AdapterFixture with(ClassLocator locator) {
        return with(TestEnvironment.standard(), locator, SpawnBackend.AUTO);
    }

    /** A healthy adapter that has already probed successfully against the full command tree. */
    static AdapterFixture probed() {
        AdapterFixture f = healthy();
        f.adapter.probeWith(FULL_TREE);
        return f;
    }

    /** True as soon as some line of a {@code Status.warnings()}/{@code .details()}-style list contains {@code text}. */
    static boolean anyContains(List<String> lines, String text) {
        return lines.stream().anyMatch(l -> l.contains(text));
    }
}

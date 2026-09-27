package dev.spawnbotswrapper.inhabitants.adapter;

import java.util.ArrayList;
import java.util.List;

/** Captures what the adapter logs, by level. */
final class RecordingSink implements Diagnostics.Sink {

    final List<String> debug = new ArrayList<>();
    final List<String> info = new ArrayList<>();
    final List<String> warn = new ArrayList<>();
    final List<String> error = new ArrayList<>();

    @Override
    public void debug(String message) {
        debug.add(message);
    }

    @Override
    public void info(String message) {
        info.add(message);
    }

    @Override
    public void warn(String message) {
        warn.add(message);
    }

    @Override
    public void error(String message) {
        error.add(message);
    }

    /** Everything at info level or above. */
    List<String> loud() {
        List<String> all = new ArrayList<>(info);
        all.addAll(warn);
        all.addAll(error);
        return all;
    }

    boolean anyContains(String text) {
        for (List<String> level : List.of(debug, info, warn, error)) {
            for (String m : level) {
                if (m.contains(text)) {
                    return true;
                }
            }
        }
        return false;
    }
}

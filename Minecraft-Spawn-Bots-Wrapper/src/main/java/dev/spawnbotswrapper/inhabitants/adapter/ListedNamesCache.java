package dev.spawnbotswrapper.inhabitants.adapter;

import java.util.Map;

/**
 * One-tick cache of PvP BOT's bot list. Upstream builds a brand-new copy of its whole set on every
 * {@code getAllBots()} call, and several adapter methods need the list many times per tick (name checks,
 * poll after poll, per-bot management questions), which would turn a population of N bots into O(N^2)
 * allocation work per tick. Within one server tick the list is read once.
 * <p>
 * A negative tick means "unknown": nothing is cached then, every call reads through.
 */
final class ListedNamesCache {

    @FunctionalInterface
    interface Loader {
        Map<String, String> load() throws Throwable;
    }

    private long tick = Long.MIN_VALUE;
    private Map<String, String> value;

    Map<String, String> get(long currentTick, Loader loader) throws Throwable {
        if (currentTick >= 0 && value != null && tick == currentTick) {
            return value;
        }
        value = null;
        Map<String, String> fresh = loader.load();
        if (currentTick >= 0) {
            value = fresh;
            tick = currentTick;
        }
        return fresh;
    }

    /** Call after anything that changes the list (spawn, remove, re-list) or when the server changes. */
    void invalidate() {
        value = null;
        tick = Long.MIN_VALUE;
    }
}

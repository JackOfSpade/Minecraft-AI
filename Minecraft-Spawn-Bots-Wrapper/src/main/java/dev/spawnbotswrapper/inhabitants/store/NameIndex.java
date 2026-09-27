package dev.spawnbotswrapper.inhabitants.store;

import dev.spawnbotswrapper.inhabitants.engine.PopulationView.BotLocation;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lowercase bot name to (structure, bot record). PvP BOT keys everything by name and Minecraft looks
 * players up case-insensitively, so "does an inhabitant called X exist" must ignore case, and it must not
 * need a scan of every record: it is asked about arbitrary player names.
 * <p>
 * The records are mutable and edited in place by the engine, so the index cannot observe changes to
 * names. It is kept exact by {@link #index}/{@link #unindex} on store, replace and remove, and by an
 * explicit {@code reindex} when the engine renames or adds a bot in a stored record. As a safety net a
 * lookup that finds a stale entry (bot gone, renamed, or its structure removed) discards the index and
 * rebuilds it once rather than answering wrongly.
 * <p>
 * Names are unique by construction. If a duplicate appears anyway (damaged or hand-edited data) the first
 * one indexed wins and the clash is counted so the load report can mention it.
 */
final class NameIndex {
    private final Map<String, BotLocation> byName = new HashMap<>();
    private final Map<StructureKey, List<String>> ownedNames = new HashMap<>();
    private final List<String> clashExamples = new ArrayList<>();
    private int clashes;
    private boolean rebuildOnMiss;

    void clear() {
        byName.clear();
        ownedNames.clear();
        clashExamples.clear();
        clashes = 0;
        rebuildOnMiss = false;
    }

    void index(StructureKey key, StructureRecord record) {
        List<String> owned = null;
        for (BotRecord bot : record.bots) {
            if (bot == null || bot.name == null) {
                continue;
            }
            String lower = lower(bot.name);
            if (byName.putIfAbsent(lower, new BotLocation(key, bot)) == null) {
                if (owned == null) {
                    owned = new ArrayList<>(record.bots.size());
                }
                owned.add(lower);
            } else {
                clashes++;
                if (clashExamples.size() < 5) {
                    clashExamples.add(bot.name);
                }
            }
        }
        if (owned != null) {
            ownedNames.put(key, owned);
        }
    }

    void unindex(StructureKey key) {
        List<String> owned = ownedNames.remove(key);
        if (owned == null) {
            return;
        }
        for (String name : owned) {
            BotLocation location = byName.get(name);
            if (location != null && location.structure().equals(key)) {
                byName.remove(name);
            }
        }
        if (clashes > 0) {
            rebuildOnMiss = true; // a shadowed duplicate elsewhere may have to take over the name
        }
    }

    void reindex(StructureKey key, StructureRecord record) {
        unindex(key);
        index(key, record);
    }

    void rebuild(Map<StructureKey, StructureRecord> records) {
        clear();
        for (Map.Entry<StructureKey, StructureRecord> e : records.entrySet()) {
            index(e.getKey(), e.getValue());
        }
    }

    int clashes() {
        return clashes;
    }

    List<String> clashExamples() {
        return List.copyOf(clashExamples);
    }

    BotLocation find(String name, Map<StructureKey, StructureRecord> records) {
        if (name == null) {
            return null;
        }
        String lower = lower(name);
        BotLocation hit = byName.get(lower);
        if (hit != null) {
            if (confirms(hit, lower, records)) {
                return hit;
            }
            rebuildOnMiss = true;
        }
        if (rebuildOnMiss) {
            rebuild(records);
            hit = byName.get(lower);
            if (hit != null && confirms(hit, lower, records)) {
                return hit;
            }
        }
        return null;
    }

    private static boolean confirms(BotLocation hit, String lower, Map<StructureKey, StructureRecord> records) {
        BotRecord bot = hit.bot();
        if (bot.name == null || !lower(bot.name).equals(lower)) {
            return false;
        }
        StructureRecord record = records.get(hit.structure());
        if (record == null) {
            return false;
        }
        for (BotRecord candidate : record.bots) {
            if (candidate == bot) {
                return true;
            }
        }
        return false;
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}

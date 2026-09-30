package dev.spawnbotswrapper.inhabitants.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.google.gson.stream.MalformedJsonException;
import dev.spawnbotswrapper.inhabitants.sample.PersistentDeckStore;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Codec for {@code populations.json}: {@code { dataVersion, structures: {key: record}, decks: {name: deck} }}.
 * <p>
 * Reading is STRICT on purpose. A file that is truncated, contains a record of the wrong shape, an
 * unparseable key, a duplicate key or an unknown status is reported {@link Outcome#INVALID} as a whole
 * and the store falls back to the backup; it never quietly loads "whatever could be salvaged", because a
 * store that silently forgets a structure re-rolls it and duplicates its population. Unknown extra
 * fields are ignored (forward compatibility) and missing fields keep the defaults of the record classes
 * (older layouts). Only a NEWER {@code dataVersion} is refused outright ({@link Outcome#TOO_NEW}).
 * <p>
 * Non-finite numbers cannot be represented in JSON. They are written as 0 instead of failing, because a
 * single NaN in one record would otherwise make every future save fail and freeze persistence.
 */
final class PopulationFile {

    enum Outcome {OK, MISSING, INVALID, TOO_NEW}

    /**
     * @param structures records exactly as stored, keyed by structure; an ABANDONED-status record is
     *                   possible here only if someone wrote one by hand, and the store moves it to the key log
     * @param warnings   anomalies that were tolerated (kept in the report so they are not silent)
     */
    record Parsed(int dataVersion, Map<StructureKey, StructureRecord> structures,
                  Map<String, PersistentDeckStore.Snapshot> decks, List<String> warnings) {
    }

    /**
     * @param parsed non-null only for {@link Outcome#OK}
     * @param detail why the file is not usable, for the load report
     */
    record ReadResult(Outcome outcome, Parsed parsed, String detail) {
        static ReadResult of(Outcome outcome, String detail) {
            return new ReadResult(outcome, null, detail);
        }
    }

    private static final TypeAdapter<Double> FINITE_DOUBLE = new TypeAdapter<>() {
        @Override
        public void write(JsonWriter out, Double value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(Double.isFinite(value) ? value : 0.0);
            }
        }

        @Override
        public Double read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return in.nextDouble();
        }
    };

    private static final TypeAdapter<Float> FINITE_FLOAT = new TypeAdapter<>() {
        @Override
        public void write(JsonWriter out, Float value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(Float.isFinite(value) ? value : 0.0f);
            }
        }

        @Override
        public Float read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return (float) in.nextDouble();
        }
    };

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(double.class, FINITE_DOUBLE)
            .registerTypeAdapter(Double.class, FINITE_DOUBLE)
            .registerTypeAdapter(float.class, FINITE_FLOAT)
            .registerTypeAdapter(Float.class, FINITE_FLOAT)
            .disableHtmlEscaping()
            .create();

    private static final TypeAdapter<StructureRecord> RECORD = GSON.getAdapter(StructureRecord.class);
    private static final TypeAdapter<PersistentDeckStore.Snapshot> DECK = GSON.getAdapter(PersistentDeckStore.Snapshot.class);

    /** Decks are indexed by bucket or option count (a few dozen at most); anything beyond this is damage. */
    static final int MAX_DECK_SIZE = 1 << 16;

    private PopulationFile() {
    }

    /** Thrown internally when the file is from a newer build. */
    private static final class TooNewException extends IOException {
        TooNewException(String message) {
            super(message);
        }
    }

    static ReadResult read(Path file) {
        try (InputStream raw = Files.newInputStream(file);
             Reader reader = new InputStreamReader(new BufferedInputStream(raw, 1 << 16),
                     StandardCharsets.UTF_8.newDecoder()
                             .onMalformedInput(CodingErrorAction.REPORT)
                             .onUnmappableCharacter(CodingErrorAction.REPORT))) {
            return classify(reader);
        } catch (NoSuchFileException e) {
            return ReadResult.of(Outcome.MISSING, "missing");
        } catch (IOException | RuntimeException e) {
            return ReadResult.of(Outcome.INVALID, describe(e)); // could not be opened at all
        }
    }

    /** Package-private for tests that feed hand-made documents. */
    static ReadResult parseText(String text) {
        return classify(new StringReader(text));
    }

    /** Never throws: whatever goes wrong while reading a document means it is not a document we can trust. */
    private static ReadResult classify(Reader reader) {
        try {
            return parse(reader);
        } catch (TooNewException e) {
            return ReadResult.of(Outcome.TOO_NEW, e.getMessage());
        } catch (Exception e) {
            return ReadResult.of(Outcome.INVALID, describe(e));
        }
    }

    @SuppressWarnings("deprecation") // setLenient(false) is the one call that means "strict" on every Gson version MC has shipped
    private static ReadResult parse(Reader reader) throws IOException {
        JsonReader in = new JsonReader(reader);
        in.setLenient(false);
        List<String> warnings = new ArrayList<>();
        Integer version = null;
        Map<StructureKey, StructureRecord> structures = null;
        Map<String, PersistentDeckStore.Snapshot> decks = null;
        try {
            in.beginObject();
            while (in.hasNext()) {
                String name = in.nextName();
                switch (name) {
                    case "dataVersion" -> {
                        requireAbsent(version, name);
                        version = in.nextInt();
                        if (version > StructureRecord.CURRENT_DATA_VERSION) {
                            throw tooNew(version);
                        }
                        if (version < 1) {
                            throw new MalformedJsonException("dataVersion must be at least 1: " + version);
                        }
                    }
                    case "structures" -> {
                        requireAbsent(structures, name);
                        structures = readStructures(in, warnings, version == null ? StructureRecord.CURRENT_DATA_VERSION : version);
                    }
                    case "decks" -> {
                        requireAbsent(decks, name);
                        decks = readDecks(in, warnings);
                    }
                    default -> in.skipValue();
                }
            }
            in.endObject();
            if (in.peek() != JsonToken.END_DOCUMENT) {
                throw new MalformedJsonException("unexpected data after the end of the document");
            }
        } catch (TooNewException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // A newer layout may well be unparseable by this build; say "newer", not "corrupt".
            if (version != null && version > StructureRecord.CURRENT_DATA_VERSION) {
                throw tooNew(version);
            }
            throw e;
        }
        if (structures == null) {
            throw new MalformedJsonException("no \"structures\" object");
        }
        if (version == null) {
            warnings.add("populations.json has no dataVersion; assuming " + StructureRecord.CURRENT_DATA_VERSION);
        }
        return new ReadResult(Outcome.OK, new Parsed(
                version == null ? StructureRecord.CURRENT_DATA_VERSION : version,
                structures, decks == null ? Map.of() : decks, warnings), "ok");
    }

    private static void requireAbsent(Object seen, String name) throws MalformedJsonException {
        if (seen != null) {
            throw new MalformedJsonException("duplicate member \"" + name + "\"");
        }
    }

    private static TooNewException tooNew(int version) {
        return new TooNewException("dataVersion " + version + " is newer than the " + StructureRecord.CURRENT_DATA_VERSION
                + " this build understands");
    }

    /**
     * Read by hand rather than through Gson's map adapter, which also accepts an array of key/value pairs
     * and would therefore let a wrongly shaped document through.
     */
    private static Map<String, PersistentDeckStore.Snapshot> readDecks(JsonReader in, List<String> warnings) throws IOException {
        Map<String, PersistentDeckStore.Snapshot> out = new LinkedHashMap<>();
        in.beginObject();
        while (in.hasNext()) {
            String name = in.nextName();
            PersistentDeckStore.Snapshot snapshot = DECK.read(in);
            if (out.containsKey(name)) {
                throw new MalformedJsonException("duplicate deck \"" + abbreviate(name) + "\"");
            }
            if (snapshot != null && (snapshot.size < 1 || snapshot.size > MAX_DECK_SIZE)) {
                // Restoring a deck allocates {@code size} ints; a damaged number must not become an OutOfMemoryError.
                // A deck is only coverage bookkeeping and is recreated on demand, so this is not worth failing the file.
                warnings.add("deck \"" + abbreviate(name) + "\" has an unusable size (" + snapshot.size
                        + ") and was dropped; it will start a new cycle");
                continue;
            }
            out.put(name, snapshot);
        }
        in.endObject();
        return out;
    }

    private static Map<StructureKey, StructureRecord> readStructures(JsonReader in, List<String> warnings,
                                                                     int headerVersion) throws IOException {
        Map<StructureKey, StructureRecord> out = new LinkedHashMap<>();
        int[] migration = new int[2]; // [0] pacifists made fighters, [1] unseen stored bots released
        in.beginObject();
        while (in.hasNext()) {
            String keyText = in.nextName();
            StructureRecord record = RECORD.read(in);
            StructureKey key = StructureKey.parse(keyText);
            if (key == null) {
                throw new MalformedJsonException("unparseable structure key \"" + abbreviate(keyText) + "\"");
            }
            normalise(key, record, warnings, headerVersion, migration);
            if (out.putIfAbsent(key, record) != null) {
                throw new MalformedJsonException("duplicate structure key \"" + abbreviate(keyText) + "\"");
            }
        }
        in.endObject();
        if (migration[0] > 0) {
            warnings.add("data migration to version 3: " + migration[0]
                    + " inhabitant profile(s) that were pacifists are now fighters (every inhabitant fights)");
        }
        if (migration[1] > 0) {
            warnings.add("data migration to version " + StructureRecord.CURRENT_DATA_VERSION + ": " + migration[1]
                    + " unseen stored bots released (a bot no player ever saw is not kept while asleep; its slot is free again)");
        }
        return out;
    }

    /**
     * Repairs what Gson leaves null (explicit JSON nulls) and rejects what cannot be repaired without
     * guessing. Fields that are simply absent were already defaulted by the record classes' constructors.
     */
    private static void normalise(StructureKey key, StructureRecord record, List<String> warnings,
                                  int headerVersion, int[] migration) throws IOException {
        if (record == null) {
            throw new MalformedJsonException("structure " + key + " has no record");
        }
        if (record.dataVersion > StructureRecord.CURRENT_DATA_VERSION) {
            throw tooNew(record.dataVersion);
        }
        if (record.dataVersion < 2) {
            // wrapperB-1: rollDetailsKept did not exist before dataVersion 2, so every such record deserialises
            // with it false regardless of whether a real roll happened. Backfill with the same numeric
            // heuristic the code used to rely on, so old records keep displaying exactly as before; only a
            // FRESH roll of exactly 0.0 against a genuinely 0% chance can now tell itself apart from "no data".
            record.rollDetailsKept = record.roll > 0 || record.occupiedChance > 0;
        }
        // A record that does not state its own version is judged by the file's header (written before the records).
        boolean migrateFighters = record.dataVersion < 3 || headerVersion < 3;
        boolean migrateSeen = record.dataVersion < 4 || headerVersion < 4;
        record.dataVersion = StructureRecord.CURRENT_DATA_VERSION;
        if (record.status == null) {
            throw new MalformedJsonException("structure " + key + " has an unknown status");
        }
        if (record.bots == null) {
            record.bots = new ArrayList<>();
        }
        record.bots.removeIf(Objects::isNull);
        int maxIndex = -1;
        for (BotRecord bot : record.bots) {
            maxIndex = Math.max(maxIndex, bot.index);
        }
        record.nextBotIndex = Math.max(record.nextBotIndex, maxIndex + 1);
        for (java.util.Iterator<BotRecord> it = record.bots.iterator(); it.hasNext(); ) {
            BotRecord bot = it.next();
            if (bot.state == null) {
                throw new MalformedJsonException("a bot of structure " + key + " has an unknown state");
            }
            if (bot.name == null) {
                warnings.add("a bot of structure " + key + " has no name; it cannot be found by name");
            }
            if (migrateSeen && bot.state == BotState.DORMANT && !bot.seen) {
                // dataVersion 4: only a bot a player has seen is kept while it is away. Every older sleeper is treated as
                // unseen: its stored state and profile go, its slot is free again (deaths already recorded stay recorded).
                it.remove();
                migration[1]++;
                continue;
            }
            if (bot.snapshot != null) {
                bot.snapshot.normalised();
            }
            // dataVersion 3 migration: profiles are authoritative and never regenerated, so a profile stored
            // as a pacifist is corrected in place. Its path (attack=false upstream) is rebuilt with attack=true
            // the next time the bot's patrol is (re)assigned, which happens on every restore.
            if (migrateFighters && bot.profile != null && bot.profile.isLegacyPacifist()) {
                bot.profile = bot.profile.asFighter();
                migration[0]++;
            }
        }
    }

    /** Streams the document to {@code file} and fsyncs it. The caller moves it into place. */
    static void write(Path file, int dataVersion, Map<StructureKey, StructureRecord> structures,
                      Map<String, PersistentDeckStore.Snapshot> decks) throws IOException {
        DurableFiles.writeSynced(file, out -> {
            Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            JsonWriter json = new JsonWriter(writer);
            json.setSerializeNulls(false);
            json.beginObject();
            json.name("dataVersion").value(dataVersion);
            json.name("structures").beginObject();
            for (Map.Entry<StructureKey, StructureRecord> e : structures.entrySet()) {
                json.name(e.getKey().asString());
                RECORD.write(json, e.getValue());
            }
            json.endObject();
            json.name("decks").beginObject();
            for (Map.Entry<String, PersistentDeckStore.Snapshot> e : decks.entrySet()) {
                json.name(e.getKey());
                DECK.write(json, e.getValue());
            }
            json.endObject();
            json.endObject();
            json.flush();
            writer.flush();
        });
    }

    private static String describe(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage().replaceAll("\\s+", " ");
        return abbreviate(e.getClass().getSimpleName() + (message.isEmpty() ? "" : ": " + message));
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}

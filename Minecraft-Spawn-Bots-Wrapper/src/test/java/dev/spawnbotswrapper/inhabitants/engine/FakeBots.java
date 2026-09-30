package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Scriptable stand-in for the bot side. By default a requested bot is reported ready on the first poll and
 * is then "online"; everything else (slow, never, refused, exceptions, name clashes, deaths) is a knob.
 */
final class FakeBots implements BotGateway {

    record Request(SpawnRequest request, long tick) {
    }

    record Applied(String name, BotProfile profile) {
    }

    boolean available = true;
    String reason = "test says unavailable";
    GlobalCapabilities capabilities = GlobalCapabilities.upstreamDefaults();
    ApplyResult applyResult = new ApplyResult(true, true, true, List.of());

    /** A poll number (1-based, per request) from which a request reports Ready; Integer.MAX_VALUE = never. */
    int readyAfterPolls = 1;
    /** Per-name override of {@link #readyAfterPolls}, by lower-case name. */
    final Map<String, Integer> readyAfterByName = new HashMap<>();
    /** The next this-many requests are refused with Failed(reason). */
    int refuseFirst;
    boolean refuseAll;
    private final Set<Long> refusedHandles = new HashSet<>();
    /** Names whose requests are always refused with Failed(reason). */
    final Set<String> refuse = new HashSet<>();
    String refuseReason = "refused by test";

    final Set<String> online = new HashSet<>();
    final Set<String> unmanaged = new HashSet<>();
    final Set<String> taken = new HashSet<>();
    /** Per-name override for {@link #distanceToNearestPlayer}, by lower-case name; unset means -1 (unknown). */
    final Map<String, Double> distanceToPlayer = new HashMap<>();
    boolean throwDistance;

    final List<Request> requests = new ArrayList<>();
    final List<Applied> applied = new ArrayList<>();
    final List<String> forgets = new ArrayList<>();
    final List<String> removes = new ArrayList<>();
    final List<Applied> restores = new ArrayList<>();
    final List<String> pearlStripCalls = new ArrayList<>();
    /** Pearls "found" per strip call, by lower-case name; the fake keeps answering the same number. */
    final Map<String, Integer> pearlsToStrip = new HashMap<>();
    int availableCalls;
    int restoreCalls;
    /** When non-null, available() throws this (any Throwable, including Errors). */
    Throwable availableFailure;
    int pollCalls;

    boolean throwAvailable;
    boolean throwNameAvailable;
    boolean throwRequest;
    boolean throwPoll;
    boolean throwApply;
    boolean throwIsOnline;
    boolean throwForget;
    boolean throwRemove;
    boolean throwRestore;
    boolean throwStrip;
    boolean throwCapabilities;
    boolean nullHandle;

    /** Called at the very start of every requestSpawn, before anything is recorded. */
    Consumer<SpawnRequest> onRequest = r -> {
    };

    private final FakeClock clock;
    private long nextHandle = 1;
    private final Map<Long, int[]> polls = new HashMap<>();
    private final Map<Long, SpawnRequest> handles = new HashMap<>();

    FakeBots(FakeClock clock) {
        this.clock = clock;
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    static UUID uuidOf(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ test helpers

    /** The bot dies / leaves: it is not online any more. */
    void kill(String name) {
        online.remove(key(name));
    }

    void bringOnline(String name) {
        online.add(key(name));
    }

    List<String> requestedNames() {
        List<String> names = new ArrayList<>();
        for (Request r : requests) {
            names.add(r.request().name());
        }
        return names;
    }

    long requestsFor(String name) {
        return requests.stream().filter(r -> r.request().name().equalsIgnoreCase(name)).count();
    }

    // ------------------------------------------------------------------ BotGateway

    @Override
    public boolean available() {
        availableCalls++;
        if (availableFailure != null) {
            sneakyThrow(availableFailure);
        }
        if (throwAvailable) {
            throw new IllegalStateException("injected available failure");
        }
        return available;
    }

    @Override
    public String unavailableReason() {
        return reason;
    }

    @Override
    public GlobalCapabilities capabilities() {
        if (throwCapabilities) {
            throw new IllegalStateException("injected capabilities failure");
        }
        return capabilities;
    }

    @Override
    public boolean nameAvailable(String name) {
        if (throwNameAvailable) {
            throw new IllegalStateException("injected nameAvailable failure");
        }
        return !online.contains(key(name)) && !taken.contains(key(name));
    }

    @Override
    public SpawnHandle requestSpawn(SpawnRequest request) {
        onRequest.accept(request);
        if (throwRequest) {
            throw new IllegalStateException("injected requestSpawn failure");
        }
        requests.add(new Request(request, clock.tick()));
        if (nullHandle) {
            return null;
        }
        long id = nextHandle++;
        polls.put(id, new int[]{0});
        if (refuseAll || refuseFirst > 0) {
            refuseFirst = Math.max(0, refuseFirst - 1);
            refusedHandles.add(id);
        }
        handles.put(id, request);
        return new SpawnHandle(id, request.name());
    }

    @Override
    public SpawnPoll poll(SpawnHandle handle) {
        pollCalls++;
        if (throwPoll) {
            throw new IllegalStateException("injected poll failure");
        }
        SpawnRequest req = handles.get(handle.id());
        int n = ++polls.get(handle.id())[0];
        if (refusedHandles.contains(handle.id()) || refuse.contains(key(req.name()))) {
            return new SpawnPoll.Failed(refuseReason);
        }
        if (n >= readyAfterByName.getOrDefault(key(req.name()), readyAfterPolls)) {
            online.add(key(req.name()));
            return new SpawnPoll.Ready(uuidOf(req.name()));
        }
        return new SpawnPoll.Pending();
    }

    @Override
    public ApplyResult applyProfile(String botName, BotProfile profile) {
        if (throwApply) {
            throw new IllegalStateException("injected applyProfile failure");
        }
        applied.add(new Applied(botName, profile));
        return applyResult;
    }

    @Override
    public boolean isOnline(String botName) {
        if (throwIsOnline) {
            throw new IllegalStateException("injected isOnline failure");
        }
        return online.contains(key(botName));
    }

    @Override
    public boolean isManaged(String botName) {
        return online.contains(key(botName)) && !unmanaged.contains(key(botName));
    }

    @Override
    public double distanceToNearestPlayer(String botName) {
        if (throwDistance) {
            throw new IllegalStateException("injected distanceToNearestPlayer failure");
        }
        return distanceToPlayer.getOrDefault(key(botName), -1.0);
    }

    @Override
    public void forget(String botName) {
        if (throwForget) {
            throw new IllegalStateException("injected forget failure");
        }
        forgets.add(botName);
    }

    @Override
    public boolean remove(String botName) {
        if (throwRemove) {
            throw new IllegalStateException("injected remove failure");
        }
        removes.add(botName);
        online.remove(key(botName));
        return true;
    }

    @Override
    public boolean restore(String botName, BotProfile profile) {
        restoreCalls++;
        if (throwRestore) {
            throw new IllegalStateException("injected restore failure");
        }
        restores.add(new Applied(botName, profile));
        return true;
    }

    @Override
    public int stripEnderPearls(String botName) {
        pearlStripCalls.add(botName);
        if (throwStrip) {
            throw new IllegalStateException("injected stripEnderPearls failure");
        }
        return pearlsToStrip.getOrDefault(key(botName), 0);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }
}

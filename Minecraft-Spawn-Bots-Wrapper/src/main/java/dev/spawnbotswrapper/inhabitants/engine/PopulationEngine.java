package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.EffectiveRule;
import dev.spawnbotswrapper.inhabitants.config.IdMatcher;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.config.RuleResolver;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.PopulationStorage;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.store.StructureStatus;
import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import dev.spawnbotswrapper.inhabitants.util.SplitMix64;
import dev.spawnbotswrapper.inhabitants.util.StableHash;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The pure-logic heart of the addon: it decides, exactly once per structure instance, whether the structure is
 * occupied or abandoned, plans and persists the bots, finds positions, asks the {@link BotGateway} to spawn them,
 * applies profiles, retries safely, and never repopulates. It has no dependency on Minecraft or PvP BOT classes,
 * only on the ports in this package.
 * <p>
 * Guarantees, in the order they matter:
 * <ol>
 *   <li><b>One roll per structure, ever.</b> The verdict (and for occupied structures every bot's name and seed) is
 *       persisted before anything is requested; an existing record of any status makes {@link #submit} a no-op.
 *       An unavailable integration or unreadable store never causes a roll, so nothing is wrongly marked done.</li>
 *   <li><b>Crash-safe spawning.</b> A bot's REQUESTED state is durable before its request is sent, so a restart
 *       adopts a bot that did appear and retries one that did not, without re-rolling, renaming or duplicating.</li>
 *   <li><b>Death is permanent.</b> Nothing ever replaces a killed inhabitant or revisits a settled structure.</li>
 *   <li><b>No custom AI.</b> The engine only decides who exists and where; PvP BOT does all fighting and walking.</li>
 *   <li><b>Nothing escapes the tick.</b> Every port call is contained; a failing port degrades to "try later".</li>
 * </ol>
 * The configuration is re-read from the supplier on every use, so a reload needs no notification. All methods
 * are for the server thread only.
 */
public final class PopulationEngine implements EngineControl {
    /**
     * Upper bound for structures waiting for their (cheap) roll. It only matters if rolling stalls (config
     * disabled, integration down) while chunks keep loading; a structure dropped here is simply detected again
     * the next time its chunk loads.
     */
    static final int MAX_ROLL_QUEUE = 4096;

    private final EngineContext ctx;
    private final BotRoster roster;
    private final PopulationDriver driver;
    private final LongSupplier seedSource;
    private final PopulationView view;
    private final long createdAtTick;
    /** Detected, not yet rolled; drained a few per tick so a burst of chunk loads cannot spike one tick. */
    private final Map<StructureKey, StructureSnapshot> rollQueue = new LinkedHashMap<>();
    private boolean settledSeen;
    private boolean shutDown;

    public PopulationEngine(Supplier<InhabitantsConfig> config, PopulationStorage store, BotGateway bots,
                            WorldGateway world, Clock clock, ProfileFactory profiles, SpawnPlanner planner) {
        this(config, store, bots, world, clock, profiles, planner, () -> SplitMix64.fromEntropy().nextLong());
    }

    /** @param seedSource where non-deterministic structure seeds come from; tests inject a seeded source */
    PopulationEngine(Supplier<InhabitantsConfig> config, PopulationStorage store, BotGateway bots,
                     WorldGateway world, Clock clock, ProfileFactory profiles, SpawnPlanner planner,
                     LongSupplier seedSource) {
        this.ctx = new EngineContext(Objects.requireNonNull(config, "config"), Objects.requireNonNull(store, "store"),
                Objects.requireNonNull(bots, "bots"), Objects.requireNonNull(world, "world"),
                Objects.requireNonNull(clock, "clock"), Objects.requireNonNull(profiles, "profiles"),
                Objects.requireNonNull(planner, "planner"));
        this.seedSource = Objects.requireNonNull(seedSource, "seedSource");
        this.roster = new BotRoster(ctx);
        this.driver = new PopulationDriver(ctx, roster);
        this.view = new ReadOnlyView(store);
        this.createdAtTick = clock.tick();
    }

    // ------------------------------------------------------------------ detection

    /**
     * A structure was detected (start chunk loaded). Idempotent and cheap: a structure that already has a
     * record, is queued, or is excluded/ineligible is ignored. May be called many times for the same
     * structure (chunk unload/reload) and must never cause a second roll. Server thread.
     */
    public void submit(StructureSnapshot snapshot) {
        if (snapshot == null || shutDown) {
            return;
        }
        ctx.guard("submit", () -> accept(snapshot));
    }

    private void accept(StructureSnapshot snapshot) {
        InhabitantsConfig cfg = ctx.config();
        if (cfg == null || !cfg.enabled || !ctx.storeUsable() || !ctx.available()) {
            return;
        }
        StructureKey key = snapshot.key();
        if (rollQueue.containsKey(key) || driver.isQueued(key)) {
            return;
        }
        StructureRecord existing = ctx.store.find(key).orElse(null);
        if (existing != null) {
            // A structure rolled occupied whose population was interrupted (restart, live-bot cap) resumes when
            // its snapshot is supplied again. The "only newly generated" filter must not apply here: after a
            // restart the chunk is merely loaded, not generated. Anything already settled stays settled.
            if (existing.status == StructureStatus.OCCUPIED_PENDING && isAllowedHere(cfg, snapshot)) {
                driver.enqueue(snapshot, false, ctx.now());
            }
            return;
        }
        if (!isAllowedHere(cfg, snapshot)) {
            return;
        }
        if (EngineContext.processing(cfg).onlyNewlyGenerated && !snapshot.newlyGenerated()) {
            return;
        }
        if (rollQueue.size() >= MAX_ROLL_QUEUE) {
            return;
        }
        rollQueue.put(key, snapshot);
        ctx.counters.seen++;
    }

    /** Dimension and include/exclude filters: what makes a structure eligible for population at all. */
    private static boolean isAllowedHere(InhabitantsConfig cfg, StructureSnapshot snapshot) {
        StructureKey key = snapshot.key();
        return RuleResolver.isDimensionEligible(cfg, key.dimension())
                && RuleResolver.isEligible(cfg, key.structureId(), snapshot.tagIds());
    }

    // ------------------------------------------------------------------ tick

    /** Advances the state machine; call once per server tick. Server thread. */
    public void tick() {
        if (shutDown) {
            return;
        }
        ctx.guard("tick", this::step);
    }

    private void step() {
        InhabitantsConfig cfg = ctx.config();
        if (cfg == null || !ctx.storeUsable()) {
            return;
        }
        long now = ctx.now();
        ctx.guard("roster", roster::build);

        // Bots already requested are always seen through, even if the addon was disabled meanwhile.
        driver.poll(now, cfg);

        if (cfg.enabled && ctx.available()) {
            ctx.guard("roll", () -> rollQueued(now, cfg));
            if (now - createdAtTick >= Math.max(0, EngineContext.processing(cfg).restoreSettleTicks)) {
                if (!settledSeen) {
                    settledSeen = true;
                    ctx.guard("roster", () -> roster.refreshAll(now));
                }
                ctx.guard("drive", () -> driver.drive(now, cfg));
                ctx.guard("reconcile", () -> roster.tick(now, cfg));
            }
        }
        ctx.saveIfDue(now, cfg);
    }

    private void rollQueued(long now, InhabitantsConfig cfg) {
        if (rollQueue.isEmpty()) {
            return;
        }
        int budget = Math.max(1, EngineContext.processing(cfg).maxStructuresPerTick);
        boolean rolled = false;
        Iterator<StructureSnapshot> it = rollQueue.values().iterator();
        while (budget > 0 && it.hasNext()) {
            StructureSnapshot snapshot = it.next();
            it.remove();
            if (rollQueuedOne(snapshot, cfg)) {
                budget--;
                rolled = true;
            }
        }
        if (rolled) {
            ctx.saveNow(); // the verdict (and the planned names) must be durable before any spawn is requested
        }
    }

    /** @return whether a roll was attempted (structures dropped by a config change cost nothing) */
    private boolean rollQueuedOne(StructureSnapshot snapshot, InhabitantsConfig cfg) {
        try {
            if (!isAllowedHere(cfg, snapshot)
                    || (EngineContext.processing(cfg).onlyNewlyGenerated && !snapshot.newlyGenerated())
                    || ctx.store.find(snapshot.key()).isPresent()) {
                return false;
            }
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            ctx.log.error("roll-check", snapshot.key().toString(), t);
            return false;
        }
        try {
            roll(snapshot, cfg, ForceMode.ROLL, false);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            ctx.log.error("roll", snapshot.key().toString(), t);
        }
        return true;
    }

    // ------------------------------------------------------------------ the roll

    /**
     * Decides and records the structure's fate, once. Occupied structures get their whole population planned
     * (count, names, seeds) here so that everything later is a restartable continuation of persisted state.
     */
    private StructureRecord roll(StructureSnapshot snapshot, InhabitantsConfig cfg, ForceMode mode, boolean immediate) {
        StructureKey key = snapshot.key();
        EffectiveRule rule = RuleResolver.resolve(cfg, key.structureId(), snapshot.tagIds());
        boolean deterministic = EngineContext.isDeterministic(cfg);
        long structureSeed = deterministic
                ? StableHash.of(ctx.world.worldSeed(), StableHash.ofString(EngineContext.salt(cfg)), key.stableHash())
                : seedSource.getAsLong();
        StructureRoll roll = StructureRoll.of(rule, structureSeed, mode, deterministic ? "DETERMINISTIC" : "RANDOM");

        StructureRecord rec = roll.occupied() ? new StructureRecord() : StructureRecord.abandoned();
        rec.source = roll.source();
        rec.occupiedChance = roll.chance();
        rec.roll = roll.roll();
        rec.structureSeed = roll.structureSeed();
        rec.rolledAtMillis = ctx.clock.nowMillis();
        rec.bounds = boundsOf(snapshot.bounds());
        if (roll.occupied()) {
            rec.status = StructureStatus.OCCUPIED_PENDING;
            rec.plannedBots = roll.botCount();
            rec.bots = planBots(cfg, roll);
        }

        ctx.store.put(key, rec);
        ctx.counters.rolled++;
        if (roll.occupied()) {
            driver.enqueue(snapshot, immediate, ctx.now());
        }
        ctx.debug(cfg, "Rolled {}: {} (roll {} vs chance {} [{}], {} bot(s), source {})", key,
                roll.occupied() ? "occupied" : "abandoned", roll.roll(), roll.chance(), rule.occupiedChanceFrom(),
                roll.occupied() ? roll.botCount() : 0, roll.source());
        return rec;
    }

    private List<BotRecord> planBots(InhabitantsConfig cfg, StructureRoll roll) {
        NameGenerator names = new NameGenerator(EngineContext.namePrefix(cfg));
        Set<String> inThisBatch = new HashSet<>();
        List<BotRecord> planned = new ArrayList<>(roll.botCount());
        for (int i = 0; i < roll.botCount(); i++) {
            long seed = StructureRoll.botSeed(roll.structureSeed(), i);
            String name = names.generate(seed,
                    n -> inThisBatch.contains(EngineContext.lower(n)) || ctx.store.findBot(n).isPresent());
            inThisBatch.add(EngineContext.lower(name));
            planned.add(new BotRecord(i, name, seed));
        }
        return planned;
    }

    private static int[] boundsOf(IntBox b) {
        return new int[]{b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()};
    }

    // ------------------------------------------------------------------ lifecycle

    /** The configuration object was replaced (reload command). Limits/rules are re-read from the supplier. */
    public void onConfigReloaded() {
        // Nothing derived from the config is cached between ticks, so there is nothing to invalidate; a fixed
        // config should however get to report a still-failing port again instead of staying throttled.
        ctx.log.clear();
    }

    /** Flushes persistence; call on server stop. */
    public void shutdown() {
        shutDown = true;
        ctx.guard("shutdown", ctx.store::flush);
    }

    /** Read-only queries (backed by the store). */
    public PopulationView view() {
        return view;
    }

    // ------------------------------------------------------------------ admin / testing

    @Override
    public ProcessOutcome process(StructureSnapshot snapshot, ForceMode mode) {
        if (snapshot == null) {
            return rejected("no structure given");
        }
        try {
            return processChecked(snapshot, mode == null ? ForceMode.ROLL : mode);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            ctx.log.error("process", snapshot.key().toString(), t);
            return rejected("internal error: " + t);
        }
    }

    private ProcessOutcome processChecked(StructureSnapshot snapshot, ForceMode mode) {
        InhabitantsConfig cfg = ctx.config();
        if (shutDown) {
            return rejected("the engine has been shut down");
        }
        if (cfg == null || !cfg.enabled) {
            return rejected("the addon is disabled in the config");
        }
        if (!ctx.storeUsable()) {
            return rejected("the persistent store is unusable, so nothing may be processed");
        }
        if (!ctx.available()) {
            String reason;
            try {
                reason = ctx.bots.unavailableReason();
            } catch (Throwable t) {
                reason = String.valueOf(t);
            }
            return rejected("the PvP BOT integration is unavailable: " + reason);
        }
        StructureKey key = snapshot.key();
        Optional<StructureRecord> existing = ctx.store.find(key);
        if (existing.isPresent()) {
            return new ProcessOutcome(ProcessOutcome.Kind.ALREADY_PROCESSED,
                    "already processed (" + existing.get().status + "); use reset first to roll it again");
        }
        // An admin bypasses the dimension/include filters and the "newly generated" rule. The exclude list is the
        // one thing that still protects a structure from a plain roll; explicitly forcing an outcome overrides it.
        if (mode == ForceMode.ROLL && IdMatcher.matchesAny(cfg.exclude, key.structureId(), snapshot.tagIds())) {
            return rejected(key.structureId() + " is on the exclude list (force it occupied or abandoned to override)");
        }

        if (rollQueue.remove(key) == null) {
            ctx.counters.seen++; // a structure that was already waiting for its roll has been counted when queued
        }
        StructureRecord rec = roll(snapshot, cfg, mode, true);
        ctx.saveNow();
        if (rec.status == StructureStatus.ABANDONED) {
            return new ProcessOutcome(ProcessOutcome.Kind.ABANDONED,
                    "rolled abandoned (" + describeRoll(rec) + ")");
        }
        return new ProcessOutcome(ProcessOutcome.Kind.OCCUPIED_QUEUED,
                "rolled occupied with " + rec.plannedBots + " bot(s) (" + describeRoll(rec) + "); population queued");
    }

    private static String describeRoll(StructureRecord rec) {
        return "source " + rec.source + ", roll " + String.format(java.util.Locale.ROOT, "%.4f", rec.roll)
                + " vs chance " + String.format(java.util.Locale.ROOT, "%.4f", rec.occupiedChance);
    }

    private static ProcessOutcome rejected(String message) {
        return new ProcessOutcome(ProcessOutcome.Kind.REJECTED, message);
    }

    @Override
    public boolean reset(StructureKey key, boolean removeBots) {
        try {
            Optional<StructureRecord> found = ctx.store.find(key);
            if (found.isEmpty()) {
                return false;
            }
            StructureRecord rec = found.get();
            rollQueue.remove(key);
            driver.abandon(key);
            roster.untrack(key);
            if (removeBots) {
                for (BotRecord b : rec.bots) {
                    if (b.name == null) {
                        continue;
                    }
                    // A REQUESTED bot's entity may already exist (PvP BOT resolved the spawn but the engine
                    // had not yet polled it) even though this record still says REQUESTED, so it must be
                    // removed too, not just a bot the engine had already promoted to SPAWNED. bots.remove is
                    // documented as a no-op for a name with no online bot entity, so this is safe either way.
                    if (b.state == BotState.SPAWNED || b.state == BotState.REQUESTED) {
                        ctx.guard("reset-remove", () -> ctx.bots.remove(b.name));
                    }
                    ctx.guard("reset-forget", () -> ctx.bots.forget(b.name));
                }
            }
            ctx.store.remove(key);
            ctx.saveNow();
            return true;
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            ctx.log.error("reset", key.toString(), t);
            return false;
        }
    }

    @Override
    public EngineStats stats() {
        ctx.guard("roster", roster::build);
        int live = 0;
        try {
            live = roster.countOnlineNow();
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            ctx.log.error("stats", String.valueOf(t), t);
        }
        EngineCounters c = ctx.counters;
        return new EngineStats(c.seen, c.rolled, c.requested, c.spawned, c.failed,
                rollQueue.size() + driver.pendingCount(), driver.inFlightCount(), live);
    }

    // ------------------------------------------------------------------ diagnostics (package-private, for tests)

    /** Number of failures the engine has contained (caught instead of letting them reach the server tick). */
    long containedErrors() {
        return ctx.log.errorCount();
    }

    int rollQueueSize() {
        return rollQueue.size();
    }

    int pendingStructures() {
        return driver.pendingCount();
    }

    /** {@link PopulationView} that exposes only the read side of the store. */
    private record ReadOnlyView(PopulationStorage store) implements PopulationView {
        @Override
        public Optional<StructureRecord> find(StructureKey key) {
            return store.find(key);
        }

        @Override
        public List<Map.Entry<StructureKey, StructureRecord>> nearby(String dimensionId, int chunkX, int chunkZ,
                                                                     int radiusChunks) {
            return store.nearby(dimensionId, chunkX, chunkZ, radiusChunks);
        }

        @Override
        public Optional<BotLocation> findBot(String botName) {
            return store.findBot(botName);
        }

        @Override
        public PopulationCounts counts() {
            return store.counts();
        }
    }
}

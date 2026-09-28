package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities;
import dev.spawnbotswrapper.inhabitants.sample.TransientDeckStore;
import dev.spawnbotswrapper.inhabitants.spawn.BlockProbe;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.store.StructureStatus;
import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import dev.spawnbotswrapper.inhabitants.util.SplitMix64;
import dev.spawnbotswrapper.inhabitants.util.StableHash;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns rolled-occupied structures into living inhabitants: finds positions, asks the bot side for the bots at a
 * controlled pace, waits for them to appear, dresses them, and settles the structure once every bot is resolved.
 * <p>
 * The durable state is the record in the store; this class only schedules. That is what makes every step
 * restartable: the roll (names, seeds) is persisted before anything is requested, and each request is preceded by
 * a persisted REQUESTED state (write-ahead), so after a crash a bot is either not requested at all or is found
 * REQUESTED and reconciled by {@link #audit}. Nothing ever re-rolls, renames a live bot, or requests one twice.
 * <p>
 * Pacing knobs are read from the configuration on every use. All times are server ticks.
 */
final class PopulationDriver {
    /** Spawn requests per bot before it is given up on. */
    static final int MAX_SPAWN_ATTEMPTS = 3;
    /**
     * Found-but-unused positions are dropped after this long and searched again: when the live-bot cap holds
     * a structure back for minutes, players may have changed the blocks in the meantime.
     */
    static final long ASSIGNMENT_TTL_TICKS = 400;

    private static final Comparator<BotRecord> BY_INDEX = Comparator.comparingInt(b -> b.index);
    private static final int MAX_NOTE_LENGTH = 240;
    /** Sub-stream of a bot's seed used to plan its patrol. */
    private static final long BEHAVIOR_STREAM = 0xBE4A710AL;

    /** What may still be done in the current tick. */
    private static final class Budget {
        /** Spawn requests left (0 when the spawn interval has not elapsed). */
        int bots;
        /** Position searches left; they are the expensive part (block probing). */
        int searches;
    }

    private final EngineContext ctx;
    private final BotRoster roster;
    private final TpsGovernor tpsGovernor;
    private final Map<StructureKey, PendingStructure> pending = new LinkedHashMap<>();
    /** By lower-case bot name: also what guarantees one bot is never requested twice concurrently. */
    private final Map<String, SpawnJob> inFlight = new LinkedHashMap<>();
    /** Dormancy restore is a self-contained cluster (its own in-flight map, never touches {@link #pending}); see
     * {@link DormancyRestorer}'s own doc for why it is deliberately outside the write-ahead machinery below. */
    private final DormancyRestorer dormancyRestorer;
    private final List<StructureKey> finished = new ArrayList<>();
    private long lastSpawnTick = PendingStructure.NEVER;

    PopulationDriver(EngineContext ctx, BotRoster roster, TpsGovernor tpsGovernor) {
        this.ctx = ctx;
        this.roster = roster;
        this.tpsGovernor = tpsGovernor;
        this.dormancyRestorer = new DormancyRestorer(ctx, roster, inFlight);
    }

    // ------------------------------------------------------------------ queue

    boolean isQueued(StructureKey key) {
        return pending.containsKey(key);
    }

    int pendingCount() {
        return pending.size();
    }

    int inFlightCount() {
        return inFlight.size();
    }

    /** Starts driving a rolled-occupied (or resumed) structure. Idempotent per key. */
    void enqueue(StructureSnapshot snapshot, boolean skipInitialDelay, long now) {
        pending.putIfAbsent(snapshot.key(), new PendingStructure(snapshot, now, skipInitialDelay));
    }

    /** Forgets everything in memory about a structure (admin reset). A bot still in flight is left alone if it appears. */
    void abandon(StructureKey key) {
        pending.remove(key);
        finished.remove(key);
        inFlight.values().removeIf(j -> j.structure().equals(key));
        dormancyRestorer.forget(key);
    }

    // ------------------------------------------------------------------ in-flight bots

    /** Polls every requested bot once; completes, fails or times out each. */
    void poll(long now, InhabitantsConfig cfg) {
        if (inFlight.isEmpty()) {
            return;
        }
        for (SpawnJob job : new ArrayList<>(inFlight.values())) {
            ctx.guard("poll", () -> pollOne(job, now, cfg));
        }
        sweep();
    }

    private void pollOne(SpawnJob job, long now, InhabitantsConfig cfg) {
        int appearTimeoutTicks = EngineContext.processing(cfg).appearTimeoutTicks;
        SpawnPollClassifier.Outcome outcome =
                SpawnPollClassifier.classify(ctx, job, now, appearTimeoutTicks, "poll", true);
        if (outcome instanceof SpawnPollClassifier.Ready ready) {
            inFlight.remove(EngineContext.lower(job.name()));
            finishSpawn(job, ready.uuid(), cfg, now);
        } else if (outcome instanceof SpawnPollClassifier.Failed failed) {
            inFlight.remove(EngineContext.lower(job.name()));
            failJob(job, failed.reason(), now);
        } else if (outcome instanceof SpawnPollClassifier.TimedOut) {
            inFlight.remove(EngineContext.lower(job.name()));
            failJob(job, "did not appear within " + appearTimeoutTicks + " ticks", now);
        }
        // StillPending: no bookkeeping change, retried next tick.
    }

    private void finishSpawn(SpawnJob job, UUID uuid, InhabitantsConfig cfg, long now) {
        Located l = locate(job);
        if (l == null) {
            return;
        }
        completeSpawn(l.pending, l.record, l.bot, uuid, cfg, now);
    }

    private void failJob(SpawnJob job, String reason, long now) {
        Located l = locate(job);
        if (l == null) {
            return;
        }
        spawnFailed(l.pending, l.record, l.bot, reason, now);
    }

    private record Located(PendingStructure pending, StructureRecord record, BotRecord bot) {
    }

    /** The structure and bot a job belongs to, or null when they were reset meanwhile (the bot is then left alone). */
    private Located locate(SpawnJob job) {
        PendingStructure p = pending.get(job.structure());
        if (p == null || p.done) {
            return null;
        }
        StructureRecord rec = ctx.store.find(job.structure()).orElse(null);
        if (rec == null || rec.status != StructureStatus.OCCUPIED_PENDING) {
            return null;
        }
        p.record = rec;
        for (BotRecord b : rec.bots) {
            if (b.index == job.botIndex() && b.state == BotState.REQUESTED && job.name().equalsIgnoreCase(b.name)) {
                return new Located(p, rec, b);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ restoring a dormant bot

    /** See {@link DormancyRestorer#restoreDormant}: the whole cluster now lives there (wrapperA-r4). */
    void restoreDormant(StructureKey key, StructureRecord rec, long now) {
        dormancyRestorer.restoreDormant(key, rec, now);
    }

    /** See {@link DormancyRestorer#pollDormant}. */
    void pollDormant(long now, InhabitantsConfig cfg) {
        dormancyRestorer.pollDormant(now, cfg);
    }

    // ------------------------------------------------------------------ pending structures

    /**
     * One pass over the queued structures: reconcile leftovers of a previous session, find positions, and send
     * as many spawn requests as this tick's budget and the live-bot cap allow. Only called once the settle period
     * is over.
     */
    void drive(long now, InhabitantsConfig cfg) {
        if (pending.isEmpty()) {
            return;
        }
        InhabitantsConfig.Processing pr = EngineContext.processing(cfg);
        Budget budget = new Budget();
        budget.bots = now - lastSpawnTick >= Math.max(0, pr.spawnIntervalTicks) ? Math.max(1, pr.maxBotsPerTick) : 0;
        budget.searches = Math.max(1, pr.maxStructuresPerTick);

        for (PendingStructure p : pending.values()) {
            if (p.done || !p.awake(now, pr.initialDelayTicks)) {
                continue;
            }
            try {
                driveOne(p, pr, cfg, now, budget);
            } catch (OutOfMemoryError e) {
                throw e;
            } catch (Throwable t) {
                onDriveError(p, t, pr, now);
            }
        }
        sweep();
    }

    private void driveOne(PendingStructure p, InhabitantsConfig.Processing pr, InhabitantsConfig cfg, long now, Budget budget) {
        long retry = Math.max(0, pr.retryIntervalTicks);
        if (p.needsAudit) {
            if (now - p.lastPlanTick < retry) {
                return;
            }
            audit(p, cfg, now);
            if (p.done || p.needsAudit) {
                return; // finished, or the server could not be asked yet: never act on a half-reconciled record
            }
        }
        StructureRecord rec = p.record;
        List<BotRecord> planned = plannedBots(rec);
        if (planned.isEmpty()) {
            return; // everything is in flight; the poll settles it
        }
        // Adoption never creates a new entity -- it must run before the capacity/pacing gate below, or a bot
        // whose spawn request timed out and then appeared late (slow profile lookup) can get stuck PLANNED
        // forever whenever capacity is exhausted, since nothing else can ever adopt it (see adoptLateArrivals).
        planned = adoptLateArrivals(p, rec, planned, cfg, now);
        if (planned.isEmpty()) {
            return;
        }
        if (budget.bots <= 0 || capacityLeft(pr) <= 0) {
            return; // paced or capped: the structure just stays pending, it is never re-rolled or dropped
        }

        if (!p.assigned.isEmpty() && now - p.assignedAtTick > ASSIGNMENT_TTL_TICKS) {
            p.assigned.clear();
        }
        List<BotRecord> needPosition = new ArrayList<>();
        for (BotRecord b : planned) {
            if (!p.assigned.containsKey(b.index)) {
                needPosition.add(b);
            }
        }
        if (!needPosition.isEmpty() && budget.searches > 0 && now - p.lastPlanTick >= retry) {
            budget.searches--;
            findPositions(p, rec, needPosition, pr, cfg, now);
        }

        if (now - p.saveFailedAtTick < retry) {
            return; // the last write-ahead could not be saved: probe the disk again after the retry interval
        }
        for (BotRecord b : planned) {
            if (b.state != BotState.PLANNED || !p.assigned.containsKey(b.index)) {
                continue;
            }
            if (budget.bots <= 0 || capacityLeft(pr) <= 0) {
                break;
            }
            requestBot(p, rec, b, cfg, now, budget);
        }
    }

    /**
     * How many more bots may be started without exceeding {@code maxLiveBots}: live ones plus those in flight
     * -- or 0 unconditionally whenever {@link TpsGovernor} says the server is currently degraded, overriding
     * the configured number entirely (see that class's doc for why this must be a hard flag, not a number).
     */
    private int capacityLeft(InhabitantsConfig.Processing pr) {
        if (tpsGovernor.blocksNewSpawns()) {
            return 0; // hard gate: see TpsGovernor's class doc for why this must be a flag, not a number
        }
        if (pr.maxLiveBots <= 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, pr.maxLiveBots - roster.online() - inFlight.size());
    }

    /** Same as above, for callers (the roll itself) that only have the whole config in hand. */
    int capacityLeft(InhabitantsConfig cfg) {
        return capacityLeft(EngineContext.processing(cfg));
    }

    private static List<BotRecord> plannedBots(StructureRecord rec) {
        List<BotRecord> planned = new ArrayList<>();
        for (BotRecord b : rec.bots) {
            if (b.state == BotState.PLANNED) {
                planned.add(b);
            }
        }
        planned.sort(BY_INDEX);
        return planned;
    }

    // ------------------------------------------------------------------ reconciling a previous session

    /**
     * First look at a resumed structure: a bot found REQUESTED that is online was created just before the
     * restart (adopt it: dress it now), one that is not online never appeared (retry it, the attempt is already
     * counted). Runs only after the settle period so that upstream's own restoring cannot be mistaken for absence.
     */
    private void audit(PendingStructure p, InhabitantsConfig cfg, long now) {
        StructureRecord rec = ctx.store.find(p.key).orElse(null);
        if (rec == null || rec.status != StructureStatus.OCCUPIED_PENDING) {
            finish(p);
            return;
        }
        p.record = rec;
        for (BotRecord b : new ArrayList<>(rec.bots)) {
            if (b.name == null) {
                failBot(p, b, "the stored record has no name for this bot");
                continue;
            }
            if (b.state != BotState.REQUESTED || inFlight.containsKey(EngineContext.lower(b.name))) {
                continue;
            }
            Boolean online = ctx.online(b.name);
            if (online == null) {
                p.lastPlanTick = now; // could not tell: look again after the retry interval
                return;
            }
            if (online) {
                ctx.debug(cfg, "Adopting inhabitant {} that was requested before the restart", b.name);
                completeSpawn(p, rec, b, null, cfg, now);
            } else {
                b.failure = "interrupted before the bot appeared";
                if (b.spawnAttempts >= MAX_SPAWN_ATTEMPTS) {
                    b.state = BotState.FAILED;
                    ctx.counters.failed++;
                } else {
                    b.state = BotState.PLANNED;
                }
                ctx.store.markDirty();
            }
        }
        p.needsAudit = false;
        checkComplete(p, rec);
    }

    /**
     * A bot whose earlier request timed out may have appeared afterwards (slow profile lookup upstream). Renaming
     * and respawning it would leave the late arrival behind as a second, undressed bot, so it is adopted instead.
     */
    private List<BotRecord> adoptLateArrivals(PendingStructure p, StructureRecord rec, List<BotRecord> planned,
                                              InhabitantsConfig cfg, long now) {
        List<BotRecord> remaining = new ArrayList<>(planned.size());
        for (BotRecord b : planned) {
            if (b.spawnAttempts > 0 && b.name != null) {
                Boolean online = ctx.online(b.name);
                if (online != null && online) {
                    ctx.debug(cfg, "Inhabitant {} appeared after its request had timed out; adopting it", b.name);
                    completeSpawn(p, rec, b, null, cfg, now);
                    continue;
                }
            }
            remaining.add(b);
        }
        return remaining;
    }

    // ------------------------------------------------------------------ positions

    private void findPositions(PendingStructure p, StructureRecord rec, List<BotRecord> needPosition,
                               InhabitantsConfig.Processing pr, InhabitantsConfig cfg, long now) {
        p.lastPlanTick = now;
        BlockProbe probe = ctx.world.probe(p.key.dimension());
        if (probe == null) {
            return; // dimension not loaded: wait, no attempt consumed
        }
        List<SpawnPlanner.Position> taken = new ArrayList<>(p.assigned.values());
        for (BotRecord b : rec.bots) {
            if (b.state == BotState.REQUESTED || b.state == BotState.SPAWNED) {
                taken.add(new SpawnPlanner.Position(b.x, b.y, b.z, b.yaw));
            }
        }
        SplitMix64 rng = StructureRoll.positionRng(rec.structureSeed, rec.attempts);
        SpawnPlanner.PositionResult result = ctx.planner.findPositions(p.snapshot, probe, needPosition.size(), taken, rng);

        List<SpawnPlanner.Position> found = result.positions();
        int usable = Math.min(found.size(), needPosition.size());
        for (int i = 0; i < usable; i++) {
            p.assigned.put(needPosition.get(i).index, found.get(i));
        }
        if (usable > 0) {
            p.assignedAtTick = now;
        }
        if (usable == needPosition.size()) {
            return;
        }
        if (result.incompleteBecauseUnloaded()) {
            ctx.debug(cfg, "Structure {}: {} of {} positions found, some chunks not loaded yet; retrying later",
                    p.key, usable, needPosition.size());
            return; // not the structure's fault: no attempt consumed
        }
        rec.attempts++;
        ctx.store.markDirty();
        ctx.debug(cfg, "Structure {}: only {} of {} positions valid (attempt {}/{})",
                p.key, usable, needPosition.size(), rec.attempts, pr.maxAttemptsPerStructure);
        if (rec.attempts >= pr.maxAttemptsPerStructure) {
            for (int i = usable; i < needPosition.size(); i++) {
                failBot(p, needPosition.get(i), "no valid position found after " + rec.attempts + " attempts");
            }
            checkComplete(p, rec);
        }
    }

    // ------------------------------------------------------------------ requesting a bot

    /**
     * Write-ahead spawn: the REQUESTED state, position and attempt count are made durable BEFORE the request is
     * sent, so a crash right after can only ever produce a REQUESTED bot that {@link #audit} reconciles. If the
     * state cannot be made durable nothing is requested (an unrecorded bot would be lost track of).
     */
    private void requestBot(PendingStructure p, StructureRecord rec, BotRecord bot, InhabitantsConfig cfg, long now,
                            Budget budget) {
        SpawnPlanner.Position pos = p.assigned.get(bot.index);
        if (bot.name != null && inFlight.containsKey(EngineContext.lower(bot.name))) {
            return;
        }
        if (!ensureUsableName(p.key, bot, cfg)) {
            return;
        }

        int attemptsBefore = bot.spawnAttempts;
        bot.x = pos.x();
        bot.y = pos.y();
        bot.z = pos.z();
        bot.yaw = pos.yaw();
        bot.state = BotState.REQUESTED;
        bot.spawnAttempts = attemptsBefore + 1;
        bot.failure = null;
        ctx.store.markDirty();
        if (!ctx.saveNow()) {
            bot.state = BotState.PLANNED;
            bot.spawnAttempts = attemptsBefore;
            p.saveFailedAtTick = now;
            budget.bots = 0;
            return;
        }

        p.assigned.remove(bot.index);
        lastSpawnTick = now;
        budget.bots--;
        BotGateway.SpawnHandle handle;
        try {
            handle = ctx.bots.requestSpawn(new BotGateway.SpawnRequest(
                    p.key.dimension(), bot.name, pos.x(), pos.y(), pos.z(), pos.yaw()));
            if (handle == null) {
                throw new IllegalStateException("the bot gateway returned no spawn handle");
            }
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            ctx.log.error("requestSpawn", bot.name, t);
            ctx.counters.requested++;
            spawnFailed(p, rec, bot, "spawn request failed: " + t, now);
            return;
        }
        ctx.counters.requested++;
        inFlight.put(EngineContext.lower(bot.name), new SpawnJob(p.key, bot.index, bot.name, handle, now));
        ctx.debug(cfg, "Requested inhabitant {} at {} {} {} in {}", bot.name,
                Math.round(pos.x()), Math.round(pos.y()), Math.round(pos.z()), p.key.dimension());
    }

    /**
     * Re-checks the planned name against the live server right before the request and picks the next
     * deterministic candidate if it has been taken since the roll (an online player, a bot upstream still lists).
     * Reindexes the record so {@link PopulationView#findBot} and this class's own {@link #nameTaken} check
     * see the new name immediately, not just the old one.
     *
     * @return false when the server could not be asked; the bot is then simply retried later
     */
    private boolean ensureUsableName(StructureKey key, BotRecord bot, InhabitantsConfig cfg) {
        if (NameGenerator.isValid(bot.name)) {
            Boolean free = ctx.nameFree(bot.name);
            if (free == null) {
                return false;
            }
            if (free) {
                return true;
            }
        }
        String old = bot.name;
        NameGenerator names = new NameGenerator(EngineContext.namePrefix(cfg));
        bot.name = names.generate(bot.seed, candidate -> nameTaken(candidate));
        ctx.store.markDirty();
        ctx.store.reindex(key);
        ctx.debug(cfg, "Inhabitant name {} is not available any more; using {}", old, bot.name);
        return true;
    }

    /** Taken anywhere the addon can know: the store (any bot, dead or alive), bots in flight, the live server. */
    private boolean nameTaken(String candidate) {
        if (ctx.store.findBot(candidate).isPresent() || inFlight.containsKey(EngineContext.lower(candidate))) {
            return true;
        }
        Boolean free = ctx.nameFree(candidate);
        return free == null || !free;
    }

    // ------------------------------------------------------------------ a bot exists: dress it

    /**
     * The entity exists: generate its profile, plan its behaviour around where it stands, apply everything, and
     * record it. The stored profile is authoritative from here on and is never regenerated.
     */
    private void completeSpawn(PendingStructure p, StructureRecord rec, BotRecord bot, UUID uuid,
                               InhabitantsConfig cfg, long now) {
        BotProfile profile;
        try {
            profile = createProfile(cfg, rec, bot);
            profile = profile.withBehavior(planBehavior(p, bot, profile.behavior(), cfg));
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            ctx.log.error("profile", bot.name, t);
            ctx.discard(bot.name); // an undressed bot must not be left standing around
            spawnFailed(p, rec, bot, "profile generation failed: " + t, now);
            return;
        }

        BotGateway.ApplyResult applied;
        try {
            applied = ctx.bots.applyProfile(bot.name, profile);
            if (applied == null) {
                applied = new BotGateway.ApplyResult(false, false, false, List.of("the gateway reported nothing"));
            }
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            ctx.log.error("applyProfile", bot.name, t);
            applied = new BotGateway.ApplyResult(false, false, false, List.of(String.valueOf(t)));
        }
        if (!applied.allApplied()) {
            ctx.log.warn("apply-partial", "Inhabitant " + bot.name + " was only partly configured (loadout "
                    + applied.loadoutApplied() + ", vitals " + applied.vitalsApplied() + ", behaviour "
                    + applied.behaviorApplied() + "): " + applied.warnings());
        }

        bot.profile = profile;
        bot.profileVersion = profile.version();
        bot.profileApplied = applied.allApplied();
        if (uuid != null) {
            bot.uuid = uuid.toString();
        }
        bot.spawnedAtMillis = ctx.clock.nowMillis();
        bot.failure = null;
        bot.state = BotState.SPAWNED;
        p.assigned.remove(bot.index);
        ctx.counters.spawned++;
        ctx.store.markDirty();
        roster.trackSpawned(p.key, bot);
        ctx.debug(cfg, "Inhabitant {} spawned in {} ({}, {})", bot.name, p.key, profile.archetype(), profile.behavior().stance());
        checkComplete(p, rec);
    }

    /**
     * Normal mode draws from the world-wide persistent decks. Deterministic mode uses a fresh transient deck store
     * per structure and first replays the profiles of the lower-numbered bots (generated and discarded), so the
     * profile of bot #i is a function of the structure alone - independent of which sibling spawned first, of
     * failed siblings, and of restarts in between.
     */
    private BotProfile createProfile(InhabitantsConfig cfg, StructureRecord rec, BotRecord bot) {
        GlobalCapabilities caps = ctx.capabilities();
        BotProfile profile;
        if (!EngineContext.isDeterministic(cfg)) {
            profile = ctx.profiles.create(bot.seed, caps, ctx.store.decks());
        } else {
            TransientDeckStore decks = new TransientDeckStore();
            List<BotRecord> earlier = new ArrayList<>();
            for (BotRecord other : rec.bots) {
                if (other.index < bot.index) {
                    earlier.add(other);
                }
            }
            earlier.sort(BY_INDEX);
            for (BotRecord other : earlier) {
                ctx.profiles.create(other.seed, caps, decks);
            }
            profile = ctx.profiles.create(bot.seed, caps, decks);
        }
        if (profile == null) {
            throw new IllegalStateException("the profile factory returned no profile");
        }
        return profile;
    }

    /**
     * Concrete waypoints around where the bot actually stands. Without a probe (dimension gone) or when planning
     * fails the requested behaviour is kept without waypoints, which the bot side applies as standing still.
     */
    private BotProfile.Behavior planBehavior(PendingStructure p, BotRecord bot, BotProfile.Behavior requested,
                                             InhabitantsConfig cfg) {
        BotProfile.Behavior fallback = requested.withWaypoints(List.of());
        BlockProbe probe = ctx.world.probe(p.key.dimension());
        if (probe == null) {
            return fallback;
        }
        try {
            SpawnPlanner.Position home = new SpawnPlanner.Position(bot.x, bot.y, bot.z, bot.yaw);
            SplitMix64 rng = new SplitMix64(StableHash.combine(bot.seed, BEHAVIOR_STREAM));
            BotProfile.Behavior planned = ctx.planner.planBehavior(probe, home, requested, p.snapshot, rng);
            return planned != null ? planned : fallback;
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            ctx.log.error("planBehavior", bot.name, t);
            return fallback;
        }
    }

    // ------------------------------------------------------------------ outcomes

    /**
     * A request did not work out. The bot goes back to PLANNED (its position is discarded and a new one is found)
     * until it has used {@link #MAX_SPAWN_ATTEMPTS} attempts, then it is FAILED for good.
     */
    private void spawnFailed(PendingStructure p, StructureRecord rec, BotRecord bot, String reason, long now) {
        bot.failure = reason;
        p.assigned.remove(bot.index);
        p.lastPlanTick = now;
        if (bot.spawnAttempts >= MAX_SPAWN_ATTEMPTS) {
            bot.state = BotState.FAILED;
            ctx.counters.failed++;
        } else {
            bot.state = BotState.PLANNED;
        }
        ctx.store.markDirty();
        checkComplete(p, rec);
    }

    private void failBot(PendingStructure p, BotRecord bot, String reason) {
        bot.state = BotState.FAILED;
        bot.failure = reason;
        p.assigned.remove(bot.index);
        ctx.counters.failed++;
        ctx.store.markDirty();
    }

    /**
     * Settles the structure once every bot is SPAWNED or FAILED: POPULATED if at least one lives, otherwise
     * GAVE_UP. Both are permanent - killed inhabitants are never replaced and the structure is never revisited.
     */
    private void checkComplete(PendingStructure p, StructureRecord rec) {
        if (p.done) {
            return;
        }
        if (rec.status != StructureStatus.OCCUPIED_PENDING) {
            finish(p);
            return;
        }
        if (!rec.allBotsResolved()) {
            return;
        }
        int spawned = rec.spawnedCount();
        String reasons = failureReasons(rec);
        if (spawned > 0) {
            rec.status = StructureStatus.POPULATED;
            int failed = rec.bots.size() - spawned;
            rec.note = failed > 0 ? truncate(failed + " of " + rec.bots.size() + " planned inhabitants could not be placed: " + reasons) : null;
        } else {
            rec.status = StructureStatus.GAVE_UP;
            rec.note = truncate(reasons.isEmpty() ? "no inhabitant could be placed" : "no inhabitant could be placed: " + reasons);
        }
        ctx.store.markDirty();
        InhabitantsConfig cfg = ctx.config();
        if (cfg != null) {
            String spread = spawned > 0 ? spreadSummary(p.snapshot, rec.bots) : "no pieces occupied";
            ctx.debug(cfg, "Structure {} settled: {} ({}; {})", p.key, rec.status,
                    rec.note != null ? rec.note : "no issues", spread);
        }
        finish(p);
    }

    /** How many of the structure's own pieces ended up with at least one living or requested inhabitant. */
    private static String spreadSummary(StructureSnapshot snapshot, List<BotRecord> bots) {
        List<IntBox> boxes = snapshot.sampleBoxes();
        int total = boxes.size();
        Set<IntBox> occupiedBoxes = new HashSet<>();
        for (BotRecord b : bots) {
            if (b.state != BotState.SPAWNED && b.state != BotState.REQUESTED) {
                continue;
            }
            int x = (int) Math.floor(b.x);
            int y = (int) Math.floor(b.y);
            int z = (int) Math.floor(b.z);
            for (IntBox box : boxes) {
                if (box.contains(x, y, z)) {
                    occupiedBoxes.add(box);
                }
            }
        }
        return occupiedBoxes.size() + " of " + total + " piece(s) occupied";
    }

    private static String failureReasons(StructureRecord rec) {
        Set<String> reasons = new LinkedHashSet<>();
        for (BotRecord b : rec.bots) {
            if (b.state == BotState.FAILED && b.failure != null) {
                reasons.add(b.failure);
            }
        }
        return String.join("; ", reasons);
    }

    private static String truncate(String s) {
        return s.length() <= MAX_NOTE_LENGTH ? s : s.substring(0, MAX_NOTE_LENGTH - 3) + "...";
    }

    /**
     * Something unexpected threw while handling this structure (a port or a bug). It counts as a failed attempt so
     * that a structure that can never work is eventually given up on instead of failing forever.
     */
    private void onDriveError(PendingStructure p, Throwable t, InhabitantsConfig.Processing pr, long now) {
        ctx.log.error("populate", p.key.toString(), t);
        p.lastPlanTick = now;
        StructureRecord rec = p.record;
        if (rec == null || rec.status != StructureStatus.OCCUPIED_PENDING) {
            return;
        }
        rec.attempts++;
        ctx.store.markDirty();
        if (rec.attempts >= pr.maxAttemptsPerStructure) {
            for (BotRecord b : plannedBots(rec)) {
                failBot(p, b, "gave up after repeated errors: " + t);
            }
            checkComplete(p, rec);
        }
    }

    private void finish(PendingStructure p) {
        if (!p.done) {
            p.done = true;
            p.assigned.clear();
            finished.add(p.key);
        }
    }

    private void sweep() {
        if (finished.isEmpty()) {
            return;
        }
        for (StructureKey key : finished) {
            PendingStructure p = pending.get(key);
            if (p != null && p.done) {
                pending.remove(key);
            }
        }
        finished.clear();
    }
}

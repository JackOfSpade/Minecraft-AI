package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.store.StructureStatus;
import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nearest-first dynamic population (see {@link InhabitantsConfig.Allocation} for the rule in the user's words).
 * <p>
 * Every pass (at most once per {@code allocation.intervalTicks}) it
 * <ol>
 *   <li>looks only at the structures whose bounding box reaches into a real player's relevance area (via the {@link
 *       StructureIndex}, never the whole store),</li>
 *   <li>recomputes the desired live-bot count per structure with {@link Allocation#allocate} -- but only when a player
 *       moved, changed level, or something relevant happened (a structure appeared or went, a bot was seen, died, was
 *       removed); otherwise the last answer stands,</li>
 *   <li>reconciles: the surplus bots of structures that dropped out are removed (a bot a player has seen sleeps, an unseen
 *       one is deleted -- see {@link Retirer}), paced by the TPS governor's batch size and never a bot that is engaged
 *       with a player or SEEN inside the relevance area; and structures that came in get their sleepers woken first,
 *       then fresh bots for their vacant slots ({@code N - dead - failed}), handed to the {@link PopulationDriver}
 *       nearest first.</li>
 * </ol>
 * There is no separate work queue: each pass diffs the desired state against the live state, so a structure whose desired
 * count changes again before its work ran simply gets a new diff (no duplicate work), and the driver's own pacing
 * (bots per tick, the live cap, the TPS block) spreads the spawns.
 * <p>
 * Anti-churn: hysteresis (a structure must beat an allocated one by {@code hysteresisBlocks}), dwell (a bot is not removed
 * within {@code dwellTicks} of coming up, unless the room is needed by a structure {@code dwellOverrideBlocks} nearer) and
 * grace (a structure that dropped out keeps its bots {@code graceTicks} before they go).
 * <p>
 * While no real player is known (or {@code allocation.enabled} is false) it is idle and {@link #active()} is false:
 * population is first come, first served and the legacy distance rule sleeps far bots.
 */
final class AllocationGovernor implements AllocationView {
    /** The order is recomputed at least this often (ticks) even when nothing moved, to pick up state changes nobody announced. */
    static final int FORCED_RECOMPUTE_TICKS = 200;

    private final EngineContext ctx;
    private final BotRoster roster;
    private final PopulationDriver driver;
    private final Retirer retirer;
    private final TpsGovernor tpsGovernor;
    private final Map<StructureKey, StructureSnapshot> known;
    private final StructureIndex index = new StructureIndex();

    private boolean indexBuilt;
    private boolean active;
    private boolean dirty = true;
    private long nextPassTick = PendingStructure.NEVER;
    private long lastComputeTick = PendingStructure.NEVER;
    private List<BotGateway.PlayerPos> lastPlayers = List.of();
    private Allocation.Result result = new Allocation.Result(Map.of(), List.of());
    private List<StructureKey> allowedOrder = List.of();
    /** 3D distance of each candidate structure of the last computation; used to order removals and to judge new structures. */
    private Map<StructureKey, Double> distances = Map.of();
    /** Structures inside a player's relevance area at the last computation. */
    private Set<StructureKey> relevant = Set.of();
    /** Since when a structure has had more live bots than it was allocated (for the grace period). */
    private final Map<StructureKey, Long> surplusSince = new HashMap<>();
    /** Lower-case bot name -> tick before which it is not tried again (its removal just failed: the store could not be written). */
    private final Map<String, Long> retryAfter = new HashMap<>();
    private static final int RETRY_BACKOFF_TICKS = 200;
    /** Counters of the last pass, for the debug line and the tests. */
    long passes;
    long computations;

    AllocationGovernor(EngineContext ctx, BotRoster roster, PopulationDriver driver, Retirer retirer,
                       TpsGovernor tpsGovernor, Map<StructureKey, StructureSnapshot> known) {
        this.ctx = ctx;
        this.roster = roster;
        this.driver = driver;
        this.retirer = retirer;
        this.tpsGovernor = tpsGovernor;
        this.known = known;
    }

    // ------------------------------------------------------------------ AllocationView

    @Override
    public boolean active() {
        return active;
    }

    @Override
    public List<StructureKey> allowedInOrder() {
        return allowedOrder;
    }

    @Override
    public int allowed(StructureKey key) {
        return result.of(key);
    }

    // ------------------------------------------------------------------ events

    /** A structure that can host bots exists (rolled occupied, or found in the store). */
    void structureAdded(StructureKey key, IntBox box) {
        if (box != null) {
            index.put(key, box);
        }
        dirty = true;
    }

    void structureRemoved(StructureKey key) {
        index.remove(key);
        surplusSince.remove(key);
        dirty = true;
    }

    /** Something that changes the answer happened (a bot was seen, died, was removed, a snapshot became known). */
    void markDirty() {
        dirty = true;
    }

    int indexedStructures() {
        ensureIndex();
        return index.size();
    }


    // ------------------------------------------------------------------ the pass

    /** Server-thread nanoseconds spent in {@link #tick} so far (diagnostics and the cost test). */
    long nanos;

    void tick(long now, InhabitantsConfig cfg) {
        long started = System.nanoTime();
        try {
            pass(now, cfg);
        } finally {
            nanos += System.nanoTime() - started;
        }
    }

    private void pass(long now, InhabitantsConfig cfg) {
        InhabitantsConfig.Allocation a = EngineContext.allocation(cfg);
        if (!a.enabled) {
            deactivate();
            return;
        }
        if (nextPassTick != PendingStructure.NEVER && now < nextPassTick) {
            return;
        }
        nextPassTick = now + Math.max(1, a.intervalTicks);
        List<BotGateway.PlayerPos> humans = ctx.realPlayers();
        if (humans.isEmpty()) {
            deactivate();
            return;
        }
        ensureIndex();
        passes++;
        boolean recompute = dirty || !active || playersMoved(humans, a.moveThresholdBlocks)
                || lastComputeTick == PendingStructure.NEVER || now - lastComputeTick >= FORCED_RECOMPUTE_TICKS;
        active = true;
        if (recompute) {
            compute(humans, cfg, a);
            lastPlayers = humans;
            lastComputeTick = now;
            dirty = false;
            computations++;
        }
        reconcile(now, cfg, a);
    }

    private void deactivate() {
        if (active) {
            active = false;
            result = new Allocation.Result(Map.of(), List.of());
            allowedOrder = List.of();
            distances = Map.of();
            relevant = Set.of();
            surplusSince.clear();
            dirty = true;
        }
    }

    private void ensureIndex() {
        if (indexBuilt) {
            return;
        }
        indexBuilt = true;
        for (Map.Entry<StructureKey, StructureRecord> e : ctx.store.nonAbandoned()) {
            StructureRecord rec = e.getValue();
            IntBox box = Allocation.boxOf(rec.bounds);
            if (box != null && (rec.status == StructureStatus.OCCUPIED_PENDING || rec.status == StructureStatus.POPULATED)) {
                index.put(e.getKey(), box);
            }
        }
    }

    private boolean playersMoved(List<BotGateway.PlayerPos> now, double threshold) {
        if (now.size() != lastPlayers.size()) {
            return true;
        }
        double limitSq = threshold * threshold;
        for (int i = 0; i < now.size(); i++) {
            BotGateway.PlayerPos a = now.get(i);
            BotGateway.PlayerPos b = lastPlayers.get(i);
            if (!a.dimension().equals(b.dimension())) {
                return true;
            }
            double dx = a.x() - b.x();
            double dy = a.y() - b.y();
            double dz = a.z() - b.z();
            if (dx * dx + dy * dy + dz * dz >= limitSq) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ recompute

    private record Near(StructureKey key, StructureRecord rec, IntBox box) {
    }

    /** The relevance radius in blocks: the players' simulation distance plus the extra chunks, at most the legacy dormancy distance. */
    double relevanceRadius(InhabitantsConfig cfg, InhabitantsConfig.Allocation a) {
        double r = ctx.relevanceRadiusBlocks() + 16.0 * Math.max(0, a.relevanceExtraChunks);
        InhabitantsConfig.Dormancy d = EngineContext.dormancy(cfg);
        if (d.enabled && d.distanceBlocks > 0) {
            r = Math.min(r, d.distanceBlocks);
        }
        return r;
    }

    private void compute(List<BotGateway.PlayerPos> humans, InhabitantsConfig cfg, InhabitantsConfig.Allocation a) {
        double radius = relevanceRadius(cfg, a);
        // 1. the structures near a player (index lookup only)
        List<Near> near = new ArrayList<>();
        Set<StructureKey> seenKeys = new HashSet<>();
        for (BotGateway.PlayerPos h : humans) {
            index.forEachNear(h.dimension(), h.x(), h.z(), radius, e -> {
                if (!e.key.dimension().equals(h.dimension()) || !seenKeys.add(e.key)) {
                    return;
                }
                if (Allocation.horizontalDistance(e.box, e.key.dimension(), humans) > radius) {
                    return;
                }
                StructureRecord rec = ctx.store.find(e.key).orElse(null);
                if (rec == null || (rec.status != StructureStatus.OCCUPIED_PENDING && rec.status != StructureStatus.POPULATED)) {
                    return;
                }
                near.add(new Near(e.key, rec, e.box));
            });
        }
        Set<StructureKey> nearKeys = new HashSet<>();
        for (Near n : near) {
            nearKeys.add(n.key());
        }
        // 2. which live bots may not be removed: engaged with a player anywhere, or seen inside the relevance area
        Map<StructureKey, Integer> protectedPerStructure = new HashMap<>();
        int protectedTotal = 0;
        for (Map.Entry<StructureKey, BotRecord> e : roster.onlineEntries()) {
            if (ctx.engaged(e.getValue().name) || (e.getValue().seen && nearKeys.contains(e.getKey()))) {
                protectedPerStructure.merge(e.getKey(), 1, Integer::sum);
                protectedTotal++;
            }
        }
        // 3. candidates and the nearest-first walk
        List<Allocation.Candidate> candidates = new ArrayList<>(near.size());
        Map<StructureKey, Double> dist = new HashMap<>();
        for (Near n : near) {
            int target = n.rec().fillTarget();
            boolean hasBots = n.rec().spawnedCount() > 0;
            if (target <= 0 || (!known.containsKey(n.key()) && !hasBots)) {
                continue; // nothing to fill, or nothing can be done for it yet (its start chunk was not seen this session)
            }
            double d = Allocation.distance(n.box(), n.key().dimension(), humans);
            dist.put(n.key(), d);
            candidates.add(new Allocation.Candidate(n.key(), d, target, protectedPerStructure.getOrDefault(n.key(), 0),
                    result.of(n.key()) > 0));
        }
        int budget = EngineContext.processing(cfg).maxLiveBots;
        Allocation.Result next = Allocation.allocate(candidates, budget, protectedTotal, a.hysteresisBlocks);
        // Structures with protected bots that are not candidates keep those bots: they are their own allocation.
        Map<StructureKey, Integer> desired = new HashMap<>(next.desired());
        for (Map.Entry<StructureKey, Integer> p : protectedPerStructure.entrySet()) {
            desired.merge(p.getKey(), p.getValue(), Math::max);
        }
        result = new Allocation.Result(desired, next.order());
        List<StructureKey> allowedKeys = new ArrayList<>();
        for (StructureKey k : next.order()) {
            if (desired.getOrDefault(k, 0) > 0) {
                allowedKeys.add(k);
            }
        }
        allowedOrder = allowedKeys;
        distances = dist;
        relevant = nearKeys;
    }

    // ------------------------------------------------------------------ reconcile

    private boolean isProtected(StructureKey key, BotRecord bot) {
        return ctx.engaged(bot.name) || (bot.seen && relevant.contains(key));
    }

    private record Victim(StructureKey key, BotRecord bot, double distance) {
    }

    private void reconcile(long now, InhabitantsConfig cfg, InhabitantsConfig.Allocation a) {
        removeSurplus(now, cfg, a);
        fill(now, cfg);
    }

    private void removeSurplus(long now, InhabitantsConfig cfg, InhabitantsConfig.Allocation a) {
        InhabitantsConfig.Dormancy dormancy = EngineContext.dormancy(cfg);
        if (!dormancy.enabled) {
            surplusSince.clear();
            return; // the operator switched removing far bots off: nothing is ever taken out for being out of the allocation
        }
        retryAfter.values().removeIf(t -> t <= now);
        Map<StructureKey, List<BotRecord>> live = new HashMap<>();
        for (Map.Entry<StructureKey, BotRecord> e : roster.onlineEntries()) {
            live.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
        }
        surplusSince.keySet().retainAll(live.keySet());
        double nearestWaiting = nearestWaitingDistance(cfg);
        boolean capacityShort = driver.capacityLeft(cfg) <= 0;
        List<Victim> victims = new ArrayList<>();
        for (Map.Entry<StructureKey, List<BotRecord>> e : live.entrySet()) {
            StructureKey key = e.getKey();
            List<BotRecord> bots = e.getValue();
            int desired = result.of(key);
            if (bots.size() <= desired) {
                surplusSince.remove(key);
                continue;
            }
            long since = surplusSince.computeIfAbsent(key, k -> now);
            if (now - since < Math.max(0, a.graceTicks)) {
                continue;
            }
            double structureDistance = distances.getOrDefault(key, Double.POSITIVE_INFINITY);
            boolean needed = capacityShort && structureDistance - nearestWaiting >= a.dwellOverrideBlocks;
            List<BotRecord> order = new ArrayList<>(bots);
            // The ones a player never saw go first; among equals the youngest slot (highest index) first.
            order.sort(Comparator.comparing((BotRecord b) -> b.seen).thenComparing(Comparator.comparingInt((BotRecord b) -> b.index).reversed()));
            int surplus = bots.size() - desired;
            for (BotRecord b : order) {
                if (surplus <= 0) {
                    break;
                }
                if (isProtected(key, b)) {
                    continue;
                }
                Long later = retryAfter.get(EngineContext.lower(b.name));
                if (later != null && later > now) {
                    continue;
                }
                long upSince = roster.onlineSince(b);
                if (upSince >= 0 && now - upSince < Math.max(0, a.dwellTicks) && !needed) {
                    continue; // just came up: it is left alone for a while
                }
                victims.add(new Victim(key, b, structureDistance));
                surplus--;
            }
        }
        if (victims.isEmpty()) {
            return;
        }
        victims.sort(Comparator.comparingDouble(Victim::distance).reversed().thenComparing(v -> v.bot().name));
        int batch = Math.max(1, EngineContext.tpsThrottle(cfg).despawnBatchSize);
        List<Victim> chosen = victims.subList(0, Math.min(batch, victims.size()));
        List<Retirer.Item> items = new ArrayList<>(chosen.size());
        for (Victim v : chosen) {
            items.add(new Retirer.Item(v.key(), v.bot()));
        }
        // One batch: the sleeps among them share ONE write of the store (see Retirer#retireAll).
        List<Retirer.Result> results = retirer.retireAll(items, Retirer.Reason.ALLOCATION, cfg);
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i) == Retirer.Result.KEPT) {
                retryAfter.put(EngineContext.lower(chosen.get(i).bot().name), now + RETRY_BACKOFF_TICKS); // do not hammer a broken disk
            } else {
                dirty = true;
            }
        }
    }

    /** Distance of the nearest structure that has fewer live bots than allocated (and so is waiting for a slot); infinity if none. */
    private double nearestWaitingDistance(InhabitantsConfig cfg) {
        for (StructureKey key : result.order()) {
            int desired = result.of(key);
            if (desired > roster.liveCount(key) + driver.inFlightFor(key)) {
                return distances.getOrDefault(key, Double.POSITIVE_INFINITY);
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    private void fill(long now, InhabitantsConfig cfg) {
        if (result.order().isEmpty()) {
            return;
        }
        int capacity = driver.capacityLeft(cfg);
        for (StructureKey key : result.order()) {
            if (capacity <= 0) {
                return;
            }
            int desired = result.of(key);
            if (desired <= 0) {
                continue;
            }
            StructureRecord rec = ctx.store.find(key).orElse(null);
            if (rec == null || (rec.status != StructureStatus.OCCUPIED_PENDING && rec.status != StructureStatus.POPULATED)) {
                continue;
            }
            int need = desired - roster.liveCount(key) - driver.inFlightFor(key);
            if (need <= 0) {
                continue;
            }
            StructureSnapshot snapshot = known.get(key);
            // 1. the sleepers of this structure first: they are the bots a player already met
            if (DormancyRestorer.hasSleepers(rec)) {
                int woken = driver.restoreDormant(key, rec, now, Math.min(need, capacity), snapshot);
                need -= woken;
                capacity -= woken;
            }
            if (need <= 0 || capacity <= 0) {
                continue;
            }
            // 2. then fresh bots for the vacant slots (a bot that died is never in them)
            if (snapshot == null) {
                continue;
            }
            int fresh = Math.min(need, capacity);
            int planned = 0;
            for (BotRecord b : rec.bots) {
                if (b.state == BotState.PLANNED) {
                    planned++;
                }
            }
            int create = Math.min(Math.max(0, fresh - planned), rec.vacantSlots());
            if (create > 0) {
                planFresh(key, rec, create, cfg);
                planned += create;
            }
            if (planned > 0) {
                if (rec.status == StructureStatus.POPULATED) {
                    rec.status = StructureStatus.OCCUPIED_PENDING;
                    ctx.store.markDirty();
                }
                if (!driver.isQueued(key)) {
                    driver.enqueue(snapshot, true, now);
                }
            }
            capacity -= Math.min(fresh, planned);
        }
    }

    /**
     * Rolls {@code count} FRESH bots for vacant slots of a structure: new indices (never reused), so a new name and a new
     * loadout, drawn by the normal roller when they spawn. They are persisted by the driver's write-ahead before anything
     * is requested.
     */
    private void planFresh(StructureKey key, StructureRecord rec, int count, InhabitantsConfig cfg) {
        NameGenerator names = new NameGenerator(EngineContext.namePrefix(cfg));
        Set<String> inThisBatch = new HashSet<>();
        for (int i = 0; i < count; i++) {
            int index = rec.allocateBotIndex();
            long seed = StructureRoll.botSeed(rec.structureSeed, index);
            String name = names.generate(seed,
                    n -> inThisBatch.contains(EngineContext.lower(n)) || ctx.store.findBot(n).isPresent());
            inThisBatch.add(EngineContext.lower(name));
            rec.bots.add(new BotRecord(index, name, seed));
        }
        ctx.store.reindex(key);
        ctx.store.markDirty();
        ctx.debug(cfg, "Structure {}: {} fresh bot(s) rolled for vacant slots ({} of {} planned are dead)", key, count,
                rec.deadCount(), rec.plannedBots);
    }
}

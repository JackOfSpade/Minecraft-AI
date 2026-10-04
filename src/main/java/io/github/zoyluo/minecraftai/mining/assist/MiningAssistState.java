package io.github.zoyluo.minecraftai.mining.assist;

import java.util.Objects;
import java.util.UUID;

/**
 * Per-bot runtime state of the mining assist sensor (mining-assist design 2.4). Runtime only: it is
 * never persisted and is dropped by {@code MiningAssistRegistry.clear(bot)}. Everything in here is
 * built from pure kernels, so the whole object is unit-testable without a Minecraft server; the
 * Minecraft adapters ({@code ViewSweeper}, {@code BreakPeek}, {@code PoiDetector}) fill it in.
 * Server thread only, never shared with an async worker.
 *
 * <p>The 69 KB {@link ObservedOccupancy} window is allocated lazily by the first sweep, so a bot the
 * gate never opens for costs only the small ring and empty collections.</p>
 */
public final class MiningAssistState {
    public static final int PENDING_BREAK_CAP = PendingBreakRing.DEFAULT_CAPACITY;
    /**
     * Sentinel for "never swept". It is {@code Integer.MIN_VALUE}, so a plain {@code tick - stored} subtraction
     * overflows: never write {@code tick - x > n} for a field that may hold NEVER. Use {@link #staleOrNever}
     * (or an explicit {@code x != NEVER} test and a {@code long} subtraction).
     */
    public static final int NEVER = Integer.MIN_VALUE;

    /**
     * True when {@code thenTick} is {@link #NEVER}, lies in the future of {@code nowTick} (a clock that moved
     * backwards), or is more than {@code maxAgeTicks} old. The subtraction is done as {@code long}, so it cannot
     * overflow for the sentinel. This is the one predicate every "is this fact fresh" check of the detour uses
     * (biome read, POI score, ledger timestamps): a fact that was never recorded is stale, never fresh.
     */
    public static boolean staleOrNever(int nowTick, int thenTick, int maxAgeTicks) {
        if (thenTick == NEVER || nowTick < thenTick) {
            return true;
        }
        return (long) nowTick - (long) thenTick > maxAgeTicks;
    }

    private final UUID botId;
    private final FreeRunStats ring = new FreeRunStats();
    private ObservedOccupancy occupancy;
    private final HazardField hazards = new HazardField();
    private final SightingLedger sightings = new SightingLedger();
    private final PoiEvidenceWindow poiWindow = new PoiEvidenceWindow();
    private final PendingBreakRing pendingBreaks = new PendingBreakRing(PENDING_BREAK_CAP);
    private final PoiCandidates poiCandidates = new PoiCandidates();

    private int sweepIndex;
    private int sweepCursor;
    private SphereSchedule.Sweep sweep;
    private boolean breakthrough;
    private int lastSweepTick = NEVER;
    private int lastBreakthroughTick = NEVER;

    private String dimensionKey;
    private String biomeId = "";
    private boolean lush;

    private int nextPoiEvalTick = NEVER;
    private PoiScorer.Band lastPoiBand = PoiScorer.Band.NONE;
    private final PoiBandLogGate poiBandGate = new PoiBandLogGate();
    private boolean cavernDisabledLogged;
    private int lastMaintenanceTick = NEVER;
    private int lastSummaryTick = NEVER;

    private SenseCounters counters = new SenseCounters();
    private long lifetimeRays;
    private long lifetimeSweeps;
    private final SenseStatus status = new SenseStatus();

    // ---- P1 (detour) additions, design 2.4 ---------------------------------------------------------------
    private final DetourExclusions exclusions = new DetourExclusions();
    private Object detourOwner;
    private DetourPhase detourPhase = DetourPhase.IDLE;
    private int detourPublishedTick = NEVER;
    private DetourControl detourControl;
    private int hurtTimeSeen;
    private double poiStructureScore;
    private int poiScoreTick = NEVER;
    private int biomeTick = NEVER;

    public MiningAssistState(UUID botId) {
        this.botId = Objects.requireNonNull(botId, "botId");
    }

    // ---------------------------------------------------------------------------------------
    // Kernels
    // ---------------------------------------------------------------------------------------

    public UUID botId() {
        return botId;
    }

    public FreeRunStats ring() {
        return ring;
    }

    public HazardField hazards() {
        return hazards;
    }

    public SightingLedger sightings() {
        return sightings;
    }

    public PoiEvidenceWindow poiWindow() {
        return poiWindow;
    }

    public PendingBreakRing pendingBreaks() {
        return pendingBreaks;
    }

    public PoiCandidates poiCandidates() {
        return poiCandidates;
    }

    /** The occupancy window if a sweep has allocated it yet, else null. */
    public ObservedOccupancy occupancyIfPresent() {
        return occupancy;
    }

    /**
     * The occupancy window, created centred on {@code feet} on first use and recentred (once the bot
     * is more than {@link ObservedOccupancy#RECENTRE_MARGIN} cells from the centre) on every later call.
     */
    public ObservedOccupancy occupancy(int feetX, int feetY, int feetZ) {
        if (occupancy == null) {
            occupancy = new ObservedOccupancy(feetX, feetY, feetZ);
        } else {
            occupancy.recentreIfNeeded(feetX, feetY, feetZ);
        }
        return occupancy;
    }

    // ---------------------------------------------------------------------------------------
    // Sweep cursor
    // ---------------------------------------------------------------------------------------

    public int sweepIndex() {
        return sweepIndex;
    }

    /** Next visit index within the current sweep, in {@code [0, 2048]} (2048 means the sweep is complete). */
    public int sweepCursor() {
        return sweepCursor;
    }

    /** Takes the next visit index of the current sweep and advances the cursor. */
    public int takeVisit() {
        return sweepCursor++;
    }

    public boolean sweepComplete() {
        return sweepCursor >= SphereSchedule.LATTICE_SIZE;
    }

    /** The rotation of the current sweep, built once per sweep index for this bot. */
    public SphereSchedule.Sweep sweep() {
        if (sweep == null) {
            sweep = SphereSchedule.sweep(botId.getMostSignificantBits(), botId.getLeastSignificantBits(), sweepIndex);
        }
        return sweep;
    }

    /** Ends the current sweep: next rotation, cursor back to 0, breakthrough rate ends. */
    public void completeSweep() {
        sweepIndex++;
        sweepCursor = 0;
        sweep = null;
        breakthrough = false;
        counters.sweepsCompleted++;
        lifetimeSweeps++;
    }

    /**
     * Breakthrough (design 3.3): the bot opened a wall into unobserved open space, so start a fresh
     * rotation from cursor 0 and run {@link SenseBudget#BREAKTHROUGH_RAYS_PER_TICK} for this one sweep.
     */
    public void requestBreakthrough() {
        sweepIndex++;
        sweepCursor = 0;
        sweep = null;
        breakthrough = true;
        counters.breakthroughs++;
    }

    /**
     * Fewest ticks between two breakthrough restarts of one bot. A breakthrough sweep runs at
     * {@value SenseBudget#BREAKTHROUGH_RAYS_PER_TICK} rays per tick, so a bot that keeps opening new
     * pockets must not hold that rate for good: a restart inside the gap is counted as deferred and the
     * running sweep goes on (it refreshes the ring and the occupancy anyway).
     */
    public static final int MIN_BREAKTHROUGH_GAP_TICKS = 40;

    /**
     * {@link #requestBreakthrough()} unless the previous restart was less than
     * {@value #MIN_BREAKTHROUGH_GAP_TICKS} ticks ago (a tick that moved backwards never blocks).
     *
     * @return true when the sweep restarted
     */
    public boolean requestBreakthrough(int nowTick) {
        if (lastBreakthroughTick != NEVER && nowTick >= lastBreakthroughTick
                && nowTick - lastBreakthroughTick < MIN_BREAKTHROUGH_GAP_TICKS) {
            counters.breakthroughsDeferred++;
            return false;
        }
        lastBreakthroughTick = nowTick;
        requestBreakthrough();
        return true;
    }

    public boolean breakthroughActive() {
        return breakthrough;
    }

    /** Tick of the last sweep step, or {@link #NEVER}. */
    public int lastSweepTick() {
        return lastSweepTick;
    }

    public void markSweepTick(int tick) {
        this.lastSweepTick = tick;
    }

    // ---------------------------------------------------------------------------------------
    // Dimension, biome
    // ---------------------------------------------------------------------------------------

    public String dimensionKey() {
        return dimensionKey;
    }

    /**
     * Notes the dimension the bot is sensing in. When it changes, every observation belongs to another
     * world and is dropped ({@link #resetObservations()}). Returns true when that happened.
     */
    public boolean enterDimension(String key) {
        if (Objects.equals(dimensionKey, key)) {
            return false;
        }
        boolean hadPrevious = dimensionKey != null;
        dimensionKey = key;
        if (hadPrevious) {
            resetObservations();
        }
        return hadPrevious;
    }

    /** The biome id at the bot's own feet, refreshed by the POI pass; empty until first read. */
    public String biomeId() {
        return biomeId;
    }

    /** True while the feet biome is lush caves (the lexicon's naturalTag input for logs and leaves). */
    public boolean lush() {
        return lush;
    }

    public void setBiome(String id) {
        this.biomeId = id == null ? "" : id;
        this.lush = AssistRules.isLushBiome(this.biomeId);
    }

    // ---------------------------------------------------------------------------------------
    // POI bookkeeping
    // ---------------------------------------------------------------------------------------

    public int nextPoiEvalTick() {
        return nextPoiEvalTick;
    }

    public void setNextPoiEvalTick(int tick) {
        this.nextPoiEvalTick = tick;
    }

    public PoiScorer.Band lastPoiBand() {
        return lastPoiBand;
    }

    public void setLastPoiBand(PoiScorer.Band band) {
        this.lastPoiBand = band == null ? PoiScorer.Band.NONE : band;
    }

    /** The rate limit of this bot's {@code assist_poi_band} lines. */
    public PoiBandLogGate poiBandGate() {
        return poiBandGate;
    }

    /** Whether the one-time "cavern channel disabled" note was already logged for this bot. */
    public boolean cavernDisabledLogged() {
        return cavernDisabledLogged;
    }

    public void setCavernDisabledLogged(boolean logged) {
        this.cavernDisabledLogged = logged;
    }

    // ---------------------------------------------------------------------------------------
    // Maintenance and reporting
    // ---------------------------------------------------------------------------------------

    /** Ticks between expiry passes over the hazard field, the sighting ledger and the POI window. */
    public static final int MAINTENANCE_INTERVAL_TICKS = 100;
    /** Sighting time-to-live, chosen here because the design leaves it to the caller (5 minutes). */
    public static final int SIGHTING_MAX_AGE_TICKS = 6000;

    /** Expires old hazard, sighting and POI entries at most once per {@link #MAINTENANCE_INTERVAL_TICKS}. */
    public boolean maintain(int nowTick) {
        if (lastMaintenanceTick != NEVER && nowTick >= lastMaintenanceTick
                && nowTick - lastMaintenanceTick < MAINTENANCE_INTERVAL_TICKS) {
            return false;
        }
        lastMaintenanceTick = nowTick;
        hazards.expire(nowTick);
        sightings.expire(nowTick, SIGHTING_MAX_AGE_TICKS);
        poiWindow.expire(nowTick);
        return true;
    }

    public SenseCounters counters() {
        return counters;
    }

    /** Whether the bot is being sensed right now, and since when it last was (coordinator bookkeeping). */
    public SenseStatus status() {
        return status;
    }

    /** Rays ever cast by this state, across reporting windows. */
    public long lifetimeRays() {
        return lifetimeRays;
    }

    public long lifetimeSweeps() {
        return lifetimeSweeps;
    }

    /** Adds to the lifetime ray total; the sweep engine calls it once per step. */
    public void addLifetimeRays(int rays) {
        lifetimeRays += rays;
    }

    /**
     * Ends the current reporting window if at least {@code intervalTicks} have passed since the last
     * one (the first call only starts the clock) and returns its counters; otherwise null.
     */
    public SenseCounters drainWindow(int nowTick, int intervalTicks) {
        if (lastSummaryTick == NEVER || nowTick < lastSummaryTick) {
            lastSummaryTick = nowTick;
            return null;
        }
        if (nowTick - lastSummaryTick < Math.max(1, intervalTicks)) {
            return null;
        }
        lastSummaryTick = nowTick;
        SenseCounters window = counters;
        counters = new SenseCounters();
        return window;
    }

    /** Tick at which the current reporting window started, or {@link #NEVER} before the first {@link #drainWindow}. */
    public int windowStartTick() {
        return lastSummaryTick;
    }

    /**
     * Ends the current reporting window unconditionally and returns its counters (used for the final
     * summary when the bot's state is released). The next window starts at {@code nowTick}.
     */
    public SenseCounters takeWindow(int nowTick) {
        lastSummaryTick = nowTick;
        SenseCounters window = counters;
        counters = new SenseCounters();
        return window;
    }

    // ---------------------------------------------------------------------------------------
    // P1: detour memory and the published detour tuple (design 2.3 and 2.4)
    // ---------------------------------------------------------------------------------------

    /**
     * The detour's private anti-thrash exclusions (cap 256, TTL). Not {@code EpisodeMemory}, so a busy detour
     * cannot evict OreDig's own exclusions. Cleared by {@link #resetObservations()} once P1 wires it (the
     * P1 contract assigns that one-line change to the coordinator integrator).
     */
    public DetourExclusions exclusions() {
        return exclusions;
    }

    /**
     * Publishes the live detour: the owning task instance (compared by identity, so it is typed Object and the
     * assist package does not depend on the task package), its phase, the server tick of this publish, and the
     * lever the coordinator's net may pull. Called by {@code OreDig.tickOpportunistic} on every tick in which the
     * engine is not IDLE.
     */
    public void publishDetour(Object owner, DetourPhase phase, int serverTick, DetourControl control) {
        this.detourOwner = owner;
        this.detourPhase = phase == null ? DetourPhase.IDLE : phase;
        this.detourPublishedTick = serverTick;
        this.detourControl = control;
    }

    /** The engine went idle, or the coordinator's orphan cleanup ran: no detour is published. Keeps {@link #hurtTimeSeen()}. */
    public void clearDetour() {
        this.detourOwner = null;
        this.detourPhase = DetourPhase.IDLE;
        this.detourPublishedTick = NEVER;
        this.detourControl = null;
    }

    /** The task instance that published the detour, or null when none is published. */
    public Object detourOwner() {
        return detourOwner;
    }

    public DetourPhase detourPhase() {
        return detourPhase;
    }

    /** Server tick of the last {@link #publishDetour}, or {@link #NEVER}. */
    public int detourPublishedTick() {
        return detourPublishedTick;
    }

    /** The lever of the published detour, or null. */
    public DetourControl detourControl() {
        return detourControl;
    }

    /** The bot's {@code hurtTime} the coordinator saw on its previous run (edge detection of the net); 0 initially. */
    public int hurtTimeSeen() {
        return hurtTimeSeen;
    }

    public void noteHurtTime(int hurtTime) {
        this.hurtTimeSeen = hurtTime;
    }

    // ---- POI facts the SAFE gate reads (design 4.4 item 9), filled by the shadow POI pass ------------------

    /** The structure score S of the last shadow POI evaluation, 0 when none ran since the last reset. */
    public double poiStructureScore() {
        return poiStructureScore;
    }

    /** Server tick of the last POI evaluation that stored a score, or {@link #NEVER}. */
    public int poiScoreTick() {
        return poiScoreTick;
    }

    /** Stores the structure score of a POI evaluation done at {@code serverTick}. */
    public void notePoiScore(int serverTick, double structureScore) {
        this.poiStructureScore = structureScore;
        this.poiScoreTick = serverTick;
    }

    /** Server tick at which the own-cell biome was last read into {@link #biomeId()}, or {@link #NEVER}. */
    public int biomeTick() {
        return biomeTick;
    }

    public void noteBiomeRead(int serverTick) {
        this.biomeTick = serverTick;
    }

    /** Drops every observation (dimension change, mission end): ring, occupancy, hazards, sightings, POI window, candidates, pending breaks. */
    public void resetObservations() {
        ring.clear();
        if (occupancy != null) {
            occupancy.clear();
        }
        hazards.clear();
        sightings.clear();
        poiWindow.clear();
        poiCandidates.clear();
        pendingBreaks.clear();
        sweepCursor = 0;
        sweep = null;
        breakthrough = false;
        lastBreakthroughTick = NEVER;
        nextPoiEvalTick = NEVER;
        lastPoiBand = PoiScorer.Band.NONE;
        poiBandGate.reset();
        // P1: a fact about the old world must never read as fresh in the new one (design 4.4 items 8 and 9).
        biomeTick = NEVER;
        poiStructureScore = 0.0D;
        poiScoreTick = NEVER;
        // P1 (F.3): an exclusion keyed by BlockPos is a cell of the old world; never carry it into a new one.
        exclusions.clear();
    }
}

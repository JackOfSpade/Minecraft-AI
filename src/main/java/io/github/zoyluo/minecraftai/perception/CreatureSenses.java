package io.github.zoyluo.minecraftai.perception;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.HumanAim;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.RecentDamage;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.observe.BotProfiler;
import io.github.zoyluo.minecraftai.perception.CreaturePerception.Params;
import io.github.zoyluo.minecraftai.perception.CreaturePerception.Reading;
import io.github.zoyluo.minecraftai.perception.CreaturePerception.Sense;
import io.github.zoyluo.minecraftai.perception.CreaturePerception.Subject;
import io.github.zoyluo.minecraftai.task.TaskManager;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The level adapter of the realistic perception: what each companion bot has NOTICED, the same model as the PvP BOT wrapper
 * ({@link CreaturePerception}; see {@code docs/PERCEPTION.md}). One instance per server, one {@link #tickBot} per bot per server tick
 * ({@code BotTickCoordinator}); everything else is a lookup of that state, so the many call sites that ask "does the bot notice this
 * creature" cost a hash lookup, never a ray.
 *
 * <ul>
 *   <li><b>Sight</b> of a creature: same level, within the profile observation radius ({@code perception.radius}; a warden within
 *       {@link #WARDEN_SIGHT_RANGE}, the quiet-zone watch), inside the view cone of the bot's REAL look vector (full attention up to 30
 *       degrees, peripheral to 100, behind never), a clear line (an eye ray, then a body-centre ray, collider shapes) and not fully
 *       invisible. Rays are cast last.</li>
 *   <li><b>Noticing</b> takes the continuous reaction time of {@link CreaturePerception}, counted per (bot, creature) by an
 *       {@link ExposureTracker}: continuous exposure with one missed tick tolerated, so EVERY re-sighting after a gap starts again
 *       from zero.</li>
 *   <li><b>Hearing</b> is vanilla's vibration system called per bot ({@link BotEars}, radius 16 by default). A sound whose source is a
 *       creature in clear view within {@link #HEARD_MATCH} blocks counts as sight without the cone for {@link #ATTENTION_TICKS} ticks
 *       (angle factor 1: the bot turned to it; the reaction time still applies). A sound with nobody in clear view is only an
 *       INVESTIGATE hint ({@link #hint}); it never counts as a notice. NO MAGIC: only the position of the sound is used.</li>
 *   <li><b>Awareness</b>: once noticed, or once struck by it, a creature is tracked by plain occlusion (no cone, no reaction time)
 *       until the line is lost for more than one tick; the next sighting starts a new reaction. A blow tells the victim where its
 *       striker is (an adjacent attacker is known at once); a projectile from an unseen shooter gives only the incoming direction (a
 *       hint), never the shooter.</li>
 *   <li>Listeners never leak: removed on despawn, death, level change, when the switch is turned off and when the server stops.</li>
 * </ul>
 * {@code behaviour.perception.enabled=false} is today's omnidirectional line of sight exactly: {@link #noticed} answers the plain
 * distance-plus-line-of-sight test and no listener is registered.
 */
public final class CreatureSenses {
    public static final CreatureSenses INSTANCE = new CreatureSenses();

    /** Ticks a heard sound keeps the bot's attention on its source: while the source stays in clear view it is noticed without the cone. */
    public static final int ATTENTION_TICKS = 30;
    /** A creature in clear view within this many blocks of a heard sound is where the sound came from. */
    public static final double HEARD_MATCH = 4.0D;
    /** How far a warden is watched (the quiet-zone scan range), beyond the observation radius of everything else. */
    public static final double WARDEN_SIGHT_RANGE = 24.0D;
    /** Ticks an idle bot keeps its head turned toward a sound or a blow it could not place. */
    static final int LOOK_TICKS = 25;
    /** Ticks a hint stays worth investigating. */
    static final int HINT_TICKS = 100;
    /** Ticks a heard shot can still explain an arrow in flight. */
    static final int SHOT_MEMORY_TICKS = 60;
    /** How far a blow from a non-projectile source can be felt as coming from its attacker: melee reach plus slack. */
    static final double FELT_MELEE_RANGE = 8.0D;

    /** A place to look at or search: a sound with nobody in view, or the direction a projectile came from. */
    public record Hint(Vec3 pos, long tick, boolean fromHit) {
    }

    /** How a creature came to be noticed (diagnostics). */
    public enum How {
        SIGHT, HEARING, HIT
    }

    private static final class Track {
        How how;
        /** The last tick the creature was in plain clear view (or struck the bot). */
        long lastClear;
        long since;
    }

    private record Shot(Vec3 pos, long tick) {
    }

    private static final class BotState {
        final AIPlayerEntity bot;
        final BotEars ears;
        /** The creatures this bot has noticed, by UUID. */
        final Map<UUID, Track> noticed = new HashMap<>();
        final ExposureTracker<UUID> exposure = new ExposureTracker<>();
        /** Creature UUID to the last tick its heard sound holds the bot's attention. */
        final Map<UUID, Long> attention = new HashMap<>();
        final ArrayDeque<Shot> shots = new ArrayDeque<>();
        Hint hint;
        long lastScan = Long.MIN_VALUE;

        BotState(AIPlayerEntity bot) {
            this.bot = bot;
            this.ears = new BotEars(bot);
        }
    }

    private final Map<UUID, BotState> bots = new HashMap<>();
    private boolean listening;

    private final Set<UUID> failed = new HashSet<>();
    /** Bots whose scan threw, and the game tick it did: for that tick and the next the legacy omnidirectional test answers instead. */
    private final Map<UUID, Long> failedAt = new HashMap<>();
    /** Rays cast by the perception (a measuring seam: the cost of the scan is dominated by them). */
    private static long rays;
    private static volatile boolean throttle = true;
    private static volatile Runnable scanFault;

    private CreatureSenses() {
    }

    /** Hooks the damage records (a blow makes its striker known); called once from {@code MinecraftAiMod}. Repeated calls do nothing. */
    public synchronized void install() {
        if (listening) {
            return;
        }
        listening = true;
        RecentDamage.addListener(this::onHit);
    }

    // ------------------------------------------------------------------ configuration

    static MinecraftAiConfig.PerceptionBehaviour config() {
        MinecraftAiConfig config = MinecraftAiConfig.get();
        return config == null || config.behaviour() == null
                ? MinecraftAiConfig.PerceptionBehaviour.defaults()
                : config.behaviour().perceptionOrDefaults();
    }

    /** The harness (GameTest and verify lanes) runs with perception ON, like production; {@link #setHarnessDefaultOff} is a diagnostic escape. */
    private static volatile boolean harnessOff;
    private static volatile boolean forcedOn;

    /**
     * The harness asks for the old omnidirectional line of sight (perception OFF) only for diagnosis
     * ({@code MINECRAFTAI_HARNESS_PERCEPTION=off}: to tell a fixture that assumes an omniscient bot from a product problem); the whole
     * GameTest suite otherwise runs with realistic perception, and its fixtures face a bot toward a threat and wait the reaction time
     * of the shared formula ({@code PerceptionFixtures}). An explicit {@code behaviour.perception.enabled} in the config does not
     * matter to a harness lane that asked for OFF. Production never calls this.
     */
    public static void setHarnessDefaultOff(boolean off) {
        harnessOff = off;
    }

    /** A test opts realistic perception in (true) for its batch, or back out (false); only meaningful under the harness default. */
    public static void forceEnabledForTests(boolean on) {
        forcedOn = on;
    }

    /**
     * A measuring seam: false runs the scan with none of the cost limits (every creature every tick, passive animals included), the
     * behaviour before the throttle, so a test can measure the same scene both ways. Production never calls this.
     */
    public static void setThrottleForTests(boolean on) {
        throttle = on;
    }

    /** A test makes every scan throw (a non-null hook runs at the start of each scan) to prove the fail-safe; null removes it. */
    public static void setScanFaultForTests(Runnable fault) {
        scanFault = fault;
    }

    /** Rays cast by the perception so far (a measuring seam). */
    public static long raysCast() {
        return rays;
    }

    /** True when realistic perception is on (the default). Off = today's omnidirectional line of sight. */
    public static boolean enabled() {
        return config().enabledOn() && (!harnessOff || forcedOn);
    }

    /**
     * How long, in ticks, a bot has to look at something {@code distance} blocks straight ahead before it has noticed what stands
     * there: the shared reaction-time formula plus the scan cadence. For a bot that peeks (an observation port, a look round a corner)
     * and must not act on what it has not yet registered. Zero with perception off (plain sight answers at once).
     */
    public static int noticeDwellTicks(double distance) {
        if (!enabled()) {
            return 0;
        }
        double seconds = CreaturePerception.requiredSeconds(config().params(), 0.0D, distance, CreaturePerception.Subject.of(false), true);
        return (int) CreaturePerception.noticeTick(seconds) + SCAN_CADENCE_TICKS;
    }

    /** The scan reads a creature that is not yet being watched every this-many ticks (see {@link #tickBot}). */
    public static final int SCAN_CADENCE_TICKS = 2;

    private static int observationRadius() {
        MinecraftAiConfig config = MinecraftAiConfig.get();
        return Math.max(1, config == null ? 16 : config.perception().radius());
    }

    // ------------------------------------------------------------------ the tick

    /**
     * Once per bot per server tick: registers and ticks the bot's ears, then reads every creature around it. Never throws into the
     * tick (a failing bot is dropped and registered again the next tick; a persistent failure is logged once).
     */
    public void tickBot(MinecraftServer server, AIPlayerEntity bot) {
        MinecraftAiConfig.PerceptionBehaviour cfg = config();
        if (!enabled()) {
            forget(bot.getUUID());
            return;
        }
        if (!bot.isAlive() || bot.isRemoved() || !(bot.level() instanceof ServerLevel level)) {
            forget(bot.getUUID());
            return;
        }
        BotState state = bots.get(bot.getUUID());
        if (state == null || state.bot != bot) {
            if (state != null) {
                state.ears.detach();
            }
            state = new BotState(bot);
            bots.put(bot.getUUID(), state);
        }
        long started = System.nanoTime();
        try {
            scan(level, state, cfg);
        } catch (RuntimeException exception) {
            // FAIL-SAFE, never blindness: for this tick (and the next, until the scan is rebuilt) every creature question is
            // answered by the legacy omnidirectional line of sight, exactly what the bot did before realistic perception. A
            // persistent failure keeps the bot on that legacy answer every tick; it is logged once per bot.
            state.ears.detach();
            bots.remove(bot.getUUID());
            failedAt.put(bot.getUUID(), level.getGameTime());
            if (failed.add(bot.getUUID())) {
                BotLog.error(bot, "perception_failed", exception);
            }
        } finally {
            BotProfiler.INSTANCE.record(bot, "perception_scan", System.nanoTime() - started);
        }
    }

    /** True when the scan of {@code bot} threw this tick or the last: the legacy omnidirectional test stands in (see {@link #tickBot}). */
    private boolean scanFailedRecently(AIPlayerEntity bot) {
        Long at = failedAt.get(bot.getUUID());
        if (at == null) {
            return false;
        }
        if (bot.level().getGameTime() - at <= 1L) {
            return true;
        }
        failedAt.remove(bot.getUUID());
        return false;
    }

    /** The old omnidirectional test: within the observation radius and a vanilla line of sight. */
    private static boolean legacyNoticed(AIPlayerEntity bot, Entity creature) {
        int radius = observationRadius();
        return bot.distanceToSqr(creature) <= (double) radius * radius && bot.hasLineOfSight(creature);
    }

    /**
     * A creature that is no threat by nature: an animal, a villager, a fish or another mob that is neither an {@link Enemy} nor a
     * {@link NeutralMob} and is not hunting anything. Realistic noticing of it would only cost a scan: it is answered on demand by
     * today's omnidirectional line of sight (animals and villagers keep omnidirectional observation: a bot glances around while it
     * hunts, breeds or shears; docs/PERCEPTION.md). A mob that has a target is never passive.
     */
    static boolean isPassive(LivingEntity e) {
        return e instanceof Mob mob && !(mob instanceof Enemy) && !(mob instanceof NeutralMob) && mob.getTarget() == null;
    }

    /** After the per-bot loop: forgets the ears and memory of every bot that is no longer among {@code live} (despawned, unloaded). */
    public void endTick(Collection<AIPlayerEntity> live) {
        if (bots.isEmpty()) {
            return;
        }
        Set<UUID> keep = new HashSet<>();
        for (AIPlayerEntity bot : live) {
            keep.add(bot.getUUID());
        }
        for (Iterator<Map.Entry<UUID, BotState>> it = bots.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, BotState> entry = it.next();
            if (!keep.contains(entry.getKey())) {
                entry.getValue().ears.detach();
                it.remove();
            }
        }
    }

    private void scan(ServerLevel level, BotState s, MinecraftAiConfig.PerceptionBehaviour cfg) {
        AIPlayerEntity bot = s.bot;
        long now = level.getGameTime();
        Params params = cfg.params();
        int radius = observationRadius();

        Runnable fault = scanFault;
        if (fault != null) {
            fault.run();
        }
        s.ears.tick(level, cfg.hearingRadius());
        List<BotEars.Sound> sounds = s.ears.drain();
        s.lastScan = now;

        Vec3 eye = bot.getEyePosition();
        Vec3 look = bot.getViewVector(1.0F);
        // One clear-view answer per creature per scan: the sounds, the awareness check and the reading share it.
        Map<UUID, Boolean> clear = new HashMap<>();
        List<LivingEntity> around = new ArrayList<>(level.getEntitiesOfClass(LivingEntity.class, bot.getBoundingBox().inflate(radius),
                e -> e != bot && e.isAlive() && !e.isSpectator() && (e instanceof Mob || e instanceof Player)
                        && bot.distanceToSqr(e) <= (double) radius * radius));
        if (WARDEN_SIGHT_RANGE > radius) {
            // A warden is watched farther (the quiet-zone scan): a class-specific query, far cheaper than widening the general one.
            for (Warden warden : level.getEntitiesOfClass(Warden.class, bot.getBoundingBox().inflate(WARDEN_SIGHT_RANGE),
                    w -> w.isAlive() && !w.isSpectator()
                            && bot.distanceToSqr(w) <= WARDEN_SIGHT_RANGE * WARDEN_SIGHT_RANGE)) {
                if (!around.contains(warden)) {
                    around.add(warden);
                }
            }
        }

        // Sounds first: a creature in clear view near the sound is where it came from (the bot turns to it); a sound with nobody in
        // view is a place to investigate.
        for (BotEars.Sound sound : sounds) {
            if (sound.shot()) {
                s.shots.addLast(new Shot(sound.pos(), now));
            }
            LivingEntity match = null;
            double best = HEARD_MATCH * HEARD_MATCH;
            for (LivingEntity c : around) {
                double d = c.position().distanceToSqr(sound.pos());
                if (d <= best && seenClear(bot, c, clear)) {
                    match = c;
                    best = d;
                }
            }
            if (match != null) {
                s.attention.put(match.getUUID(), now + ATTENTION_TICKS);
            } else {
                s.hint = new Hint(sound.pos(), now, false);
            }
        }
        while (!s.shots.isEmpty() && now - s.shots.peekFirst().tick() > SHOT_MEMORY_TICKS) {
            s.shots.pollFirst();
        }

        Set<UUID> seen = new HashSet<>();
        for (LivingEntity c : around) {
            if (throttle && isPassive(c)) {
                // A creature that is no threat by nature is not read (it answers the plain omnidirectional test on demand); it stays
                // in {@code around} above only so that its own sounds (a cow's steps) are explained by it and never become a hint.
                continue;
            }
            UUID id = c.getUUID();
            seen.add(id);
            Track track = s.noticed.get(id);
            if (track != null) {
                // Awareness: tracked by plain occlusion. The line lost for more than a tick ends it; the next sighting is a new reaction.
                // Throttled, the line is verified every second tick while it is clear (a creature seen clear this tick or the last is taken
                // as still in view) and every tick once a check has failed; the tolerance for a lost line grows by that unverified tick. So a
                // line lost on the unverified tick ends the awareness one tick (50 ms, one scan cadence) later than unthrottled, never more:
                // the same cadence bound as noticing itself (PerceptionFixtures.SCAN_SLACK_TICKS), and no knowledge the bot never had.
                if (throttle && now - track.lastClear <= 1) {
                    continue;
                }
                if (seenClear(bot, c, clear)) {
                    track.lastClear = now;
                    continue;
                }
                if (now - track.lastClear <= (throttle ? SCAN_CADENCE_TICKS : 1)) {
                    continue;
                }
                s.noticed.remove(id);
                s.exposure.forget(id);
            }
            Long until = s.attention.get(id);
            boolean heardNear = until != null && until >= now;
            if (throttle && !heardNear && !s.exposure.inProgress(id, now) && (now + c.getId()) % SCAN_CADENCE_TICKS != 0L) {
                // Cadence: a creature that is not being watched right now (no run of exposure, no sound at it) is read every second tick,
                // alternating by entity; once it has a run (or a sound), every tick, so the reaction time keeps its tick granularity.
                // Whoever enters the view is therefore seen at most one tick later.
                continue;
            }
            Vec3 toward = c.getEyePosition().subtract(eye);
            double distance = toward.length();
            double theta = CreaturePerception.angleDeg(look.x, look.y, look.z, toward.x, toward.y, toward.z);
            Reading reading = CreaturePerception.read(params, theta, distance, subjectOf(c, bot), heardNear,
                    () -> seenClear(bot, c, clear));
            if (!reading.exposed()) {
                s.exposure.missed(id, now);
                continue;
            }
            long ticks = s.exposure.sighted(id, now);
            if (CreaturePerception.noticed(CreaturePerception.exposureSeconds(ticks), reading.requiredSeconds())) {
                Track fresh = new Track();
                fresh.how = reading.sense() == Sense.HEARING ? How.HEARING : How.SIGHT;
                fresh.lastClear = now;
                fresh.since = now;
                s.noticed.put(id, fresh);
                BotLog.perception(bot, "creature_noticed", "type", c.getType(), "id", c.getId(), "how", fresh.how,
                        "distance", Math.round(distance * 10.0D) / 10.0D, "angle", Math.round(theta),
                        "seconds", Math.round(reading.requiredSeconds() * 100.0D) / 100.0D);
            }
        }
        // Whoever left (died, despawned, went out of range) is forgotten at once: coming back is a new sighting.
        s.noticed.keySet().retainAll(seen);
        s.exposure.retain(seen);
        s.attention.keySet().retainAll(seen);
        if (s.hint != null && now - s.hint.tick() > HINT_TICKS) {
            s.hint = null;
        }
        lookAtHint(s, now);
    }

    /**
     * An idle bot keeps its head turned toward a place it could not explain (a sound out of sight, the direction of a blow). Only an
     * idle one: a bot in the middle of an action (a walked step, a dig, a route, an item in use) keeps its head where the action
     * needs it, exactly as without perception; turning it would steer the step or spend the turn its own aim needs.
     */
    private static void lookAtHint(BotState s, long now) {
        Hint hint = s.hint;
        if (hint == null || now - hint.tick() > LOOK_TICKS || !s.noticed.isEmpty()
                || TaskManager.INSTANCE.getActive(s.bot).isPresent() || s.bot.getActionPack().hasActiveActions()) {
            return;
        }
        HumanAim.lookToward(s.bot, hint.pos());
    }

    /**
     * What a creature looks like to {@code observer}: crouching, and the vanilla visibility (invisibility with armour cover, worn mob
     * heads) with its own sneak factor divided out (sneaking is the model's factor, not counted twice).
     */
    static Subject subjectOf(LivingEntity e, Entity observer) {
        double visibility = e.getVisibilityPercent(observer);
        if (e.isDiscrete()) {
            visibility /= 0.8D;
        }
        return new Subject(e.isDiscrete() || e.isCrouching(), visibility);
    }

    /**
     * A clear view from {@code from}'s eye to {@code to}: a ray to its eye and, when that is blocked, a second to its body centre.
     * Collider shapes, fluids ignored: the blocks a vanilla mob cannot see through.
     */
    static boolean clearView(Entity from, Entity to) {
        if (to.level() != from.level()) {
            return false;
        }
        Vec3 start = from.getEyePosition();
        return clear(from, start, to.getEyePosition())
                || clear(from, start, to.position().add(0.0D, to.getBbHeight() * 0.5D, 0.0D));
    }

    /** {@link #clearView} answered once per creature per scan. */
    private static boolean seenClear(Entity from, Entity to, Map<UUID, Boolean> cache) {
        return cache.computeIfAbsent(to.getUUID(), id -> clearView(from, to));
    }

    private static boolean clear(Entity from, Vec3 start, Vec3 end) {
        rays++;
        return from.level().clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, from))
                .getType() == HitResult.Type.MISS;
    }

    // ------------------------------------------------------------------ hits

    private void onHit(RecentDamage.Hit hit) {
        if (!enabled()) {
            return;
        }
        BotState s = bots.get(hit.victim());
        if (s == null) {
            // A bot struck in the very tick it was spawned (before the coordinator has read it once) is still struck: its memory is
            // opened now, and the first scan adopts it.
            AIPlayerEntity victim = io.github.zoyluo.minecraftai.manager.AIPlayerManager.INSTANCE.getByUuid(hit.victim())
                    .filter(AIPlayerEntity::isAlive).orElse(null);
            if (victim == null) {
                return;
            }
            s = new BotState(victim);
            bots.put(hit.victim(), s);
        }
        try {
            AIPlayerEntity bot = s.bot;
            if (!(bot.level() instanceof ServerLevel level)) {
                return;
            }
            long now = level.getGameTime();
            if (hit.attackerId() >= 0 && hit.attackerId() == hit.directId()) {
                // A melee blow: the striker is adjacent, and pain tells the victim where it is.
                Entity attacker = level.getEntity(hit.attackerId());
                if (attacker instanceof LivingEntity living && living != bot && living.isAlive()
                        && living.distanceTo(bot) <= FELT_MELEE_RANGE) {
                    markNoticed(s, living, How.HIT, now);
                }
            } else if (hit.directId() >= 0 && hit.directId() != hit.attackerId()) {
                // A projectile (or another remote source): only the direction it came from, never the shooter.
                Entity projectile = level.getEntity(hit.directId());
                if (projectile != null && projectile.getDeltaMovement().lengthSqr() > 1.0E-6D) {
                    Vec3 back = projectile.getDeltaMovement().scale(-1.0D).normalize();
                    s.hint = new Hint(traceBack(bot, back, observationRadius()), now, true);
                }
            }
        } catch (RuntimeException exception) {
            BotLog.error(s.bot, "perception_hit_failed", exception);
        }
    }

    private static void markNoticed(BotState s, LivingEntity creature, How how, long now) {
        Track track = s.noticed.get(creature.getUUID());
        if (track == null) {
            track = new Track();
            track.how = how;
            track.since = now;
            s.noticed.put(creature.getUUID(), track);
            BotLog.perception(s.bot, "creature_noticed", "type", creature.getType(), "id", creature.getId(), "how", how,
                    "distance", Math.round(creature.distanceTo(s.bot) * 10.0D) / 10.0D);
        }
        track.lastClear = now;
    }

    /** The last free point on the line from the bot's eye along {@code toward} (a unit vector), up to {@code limit} blocks. */
    static Vec3 traceBack(AIPlayerEntity bot, Vec3 toward, double limit) {
        Vec3 start = bot.getEyePosition();
        Vec3 end = start.add(toward.scale(limit));
        HitResult hit = bot.level().clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
        return hit.getType() == HitResult.Type.MISS ? end : hit.getLocation().subtract(toward.scale(0.4D));
    }

    // ------------------------------------------------------------------ queries

    /**
     * True when {@code bot} has NOTICED {@code creature}: it saw it long enough (the reaction time), heard it and saw it, or was
     * struck by it, and still has a clear line to it. With perception off: today's omnidirectional test (within the observation radius
     * and a vanilla line of sight).
     */
    public boolean noticed(AIPlayerEntity bot, LivingEntity creature) {
        if (!enabled() || scanFailedRecently(bot) || (throttle && isPassive(creature))) {
            // Off, a failed scan (fail-safe: never blind) or a creature that is no threat by nature: today's omnidirectional test.
            return legacyNoticed(bot, creature);
        }
        BotState s = bots.get(bot.getUUID());
        return s != null && s.bot == bot && s.noticed.containsKey(creature.getUUID());
    }

    /** How {@code bot} came to notice {@code creature}, or empty when it has not. */
    public Optional<How> howNoticed(AIPlayerEntity bot, LivingEntity creature) {
        BotState s = bots.get(bot.getUUID());
        Track track = s == null ? null : s.noticed.get(creature.getUUID());
        return Optional.ofNullable(track == null ? null : track.how);
    }

    /** The creatures {@code bot} has noticed right now (empty with perception off: ask {@link #noticed} per creature then). */
    public Set<UUID> noticedIds(AIPlayerEntity bot) {
        BotState s = bots.get(bot.getUUID());
        return s == null ? Set.of() : Set.copyOf(s.noticed.keySet());
    }

    /**
     * True when {@code bot} is aware of {@code projectile} (an arrow or trident in flight): the shot was HEARD (the vanilla
     * {@code PROJECTILE_SHOOT} vibration, its source near where the projectile has come from), or the projectile itself is in view
     * (inside the peripheral view field with a clear line). There is no reaction time for an object flying at the bot: it is a flinch,
     * not a recognition. With perception off: today's omnidirectional test.
     */
    public boolean noticedProjectile(AIPlayerEntity bot, Entity projectile) {
        if (!enabled()) {
            // Perception off is EXACTLY the old test, the strict capability bypass included.
            return io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveEntity(bot, projectile);
        }
        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "observable_entity_query").allowed()) {
            return true;
        }
        int radius = observationRadius();
        if (bot.distanceToSqr(projectile) > (double) radius * radius) {
            return false;
        }
        if (scanFailedRecently(bot)) {
            return bot.hasLineOfSight(projectile); // fail-safe: the legacy answer
        }
        Params params = config().params();
        BotState s = bots.get(bot.getUUID());
        if (s != null && s.bot == bot) {
            double reach = 4.0D + 3.2D * Math.max(0, projectile.tickCount);
            for (Shot shot : s.shots) {
                if (shot.pos().distanceTo(projectile.position()) <= reach) {
                    return true;
                }
            }
        }
        Vec3 toward = projectile.position().subtract(bot.getEyePosition());
        Vec3 look = bot.getViewVector(1.0F);
        double theta = CreaturePerception.angleDeg(look.x, look.y, look.z, toward.x, toward.y, toward.z);
        return theta <= params.peripheralHalfAngleDeg()
                && bot.level().clip(new ClipContext(bot.getEyePosition(), projectile.position(), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, bot)).getType() == HitResult.Type.MISS;
    }

    /** The place {@code bot} should turn to look at or search: a sound with nobody in view, or where a projectile that hit it came from. */
    public Optional<Hint> hint(AIPlayerEntity bot) {
        BotState s = bots.get(bot.getUUID());
        return s == null ? Optional.empty() : Optional.ofNullable(s.hint);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Forgets one bot: its listener is removed and its memory dropped (it died, despawned, changed level, or the switch is off). */
    public void forget(UUID botId) {
        failedAt.remove(botId);
        BotState s = bots.remove(botId);
        if (s != null) {
            s.ears.detach();
        }
    }

    /** Removes every listener and forgets everything (the server is stopping). */
    public void clearAll() {
        for (BotState s : new ArrayList<>(bots.values())) {
            s.ears.detach();
        }
        bots.clear();
        failed.clear();
        failedAt.clear();
    }

    /** How many vibration listeners are registered right now (a seam for the leak tests). */
    public int listenerCount() {
        int n = 0;
        for (BotState s : bots.values()) {
            if (s.ears.registered()) {
                n++;
            }
        }
        return n;
    }

    /** The level {@code bot}'s vibration listener is registered in, or empty when it has none (a seam for the leak tests). */
    public Optional<ServerLevel> listenerLevel(AIPlayerEntity bot) {
        BotState s = bots.get(bot.getUUID());
        return s == null ? Optional.empty() : Optional.ofNullable(s.ears.registeredLevel());
    }

    /** How many bots have perception state (a seam for tests). */
    public int botCount() {
        return bots.size();
    }
}

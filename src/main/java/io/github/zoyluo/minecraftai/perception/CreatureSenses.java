package io.github.zoyluo.minecraftai.perception;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.HumanAim;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.RecentDamage;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
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

    /** The harness (GameTest and verify lanes) runs with perception OFF unless a test opts in: see {@link #setHarnessDefaultOff}. */
    private static volatile boolean harnessOff;
    private static volatile boolean forcedOn;

    /**
     * The harness (GameTests, verify scenarios) asks for realistic perception to default OFF: their fixtures spawn a hostile a few
     * blocks away at any angle and expect the very next scan to react, which is today's omnidirectional line of sight; the tests of
     * the perception itself switch it on with {@link #forceEnabledForTests}. An explicit {@code behaviour.perception.enabled} in the
     * config does not matter to the harness lanes. Production never calls this.
     */
    public static void setHarnessDefaultOff(boolean off) {
        harnessOff = off;
    }

    /** A test opts realistic perception in (true) for its batch, or back out (false); only meaningful under the harness default. */
    public static void forceEnabledForTests(boolean on) {
        forcedOn = on;
    }

    /** True when realistic perception is on (the default). Off = today's omnidirectional line of sight. */
    public static boolean enabled() {
        return config().enabledOn() && (!harnessOff || forcedOn);
    }

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
        try {
            scan(level, state, cfg);
        } catch (RuntimeException exception) {
            state.ears.detach();
            bots.remove(bot.getUUID());
            if (failed.add(bot.getUUID())) {
                BotLog.error(bot, "perception_failed", exception);
            }
        }
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

        s.ears.tick(level, cfg.hearingRadius());
        List<BotEars.Sound> sounds = s.ears.drain();
        s.lastScan = now;

        Vec3 eye = bot.getEyePosition();
        Vec3 look = bot.getViewVector(1.0F);
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
                if (d <= best && clearView(bot, c)) {
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
            UUID id = c.getUUID();
            seen.add(id);
            Track track = s.noticed.get(id);
            if (track != null) {
                // Awareness: tracked by plain occlusion. The line lost for more than a tick ends it; the next sighting is a new reaction.
                if (clearView(bot, c)) {
                    track.lastClear = now;
                    continue;
                }
                if (now - track.lastClear <= 1) {
                    continue;
                }
                s.noticed.remove(id);
                s.exposure.forget(id);
            }
            Long until = s.attention.get(id);
            boolean heardNear = until != null && until >= now;
            Vec3 toward = c.getEyePosition().subtract(eye);
            double distance = toward.length();
            double theta = CreaturePerception.angleDeg(look.x, look.y, look.z, toward.x, toward.y, toward.z);
            Reading reading = CreaturePerception.read(params, theta, distance, subjectOf(c, bot), heardNear,
                    () -> clearView(bot, c));
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

    /** An idle bot keeps its head turned toward a place it could not explain (a sound out of sight, the direction of a blow). */
    private static void lookAtHint(BotState s, long now) {
        Hint hint = s.hint;
        if (hint == null || now - hint.tick() > LOOK_TICKS || !s.noticed.isEmpty()
                || TaskManager.INSTANCE.getActive(s.bot).isPresent()) {
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

    private static boolean clear(Entity from, Vec3 start, Vec3 end) {
        return from.level().clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, from))
                .getType() == HitResult.Type.MISS;
    }

    // ------------------------------------------------------------------ hits

    private void onHit(RecentDamage.Hit hit) {
        BotState s = bots.get(hit.victim());
        if (s == null || !enabled()) {
            return;
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
        if (!enabled()) {
            int radius = observationRadius();
            return bot.distanceToSqr(creature) <= (double) radius * radius && bot.hasLineOfSight(creature);
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
        int radius = observationRadius();
        if (bot.distanceToSqr(projectile) > (double) radius * radius) {
            return false;
        }
        Params params = config().params();
        if (!enabled()) {
            return bot.hasLineOfSight(projectile);
        }
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

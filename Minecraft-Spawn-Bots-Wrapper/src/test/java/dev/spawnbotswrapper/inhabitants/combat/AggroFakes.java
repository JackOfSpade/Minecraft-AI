package dev.spawnbotswrapper.inhabitants.combat;

import dev.spawnbotswrapper.inhabitants.combat.AggroWorld.Body;
import dev.spawnbotswrapper.inhabitants.combat.AggroWorld.Pos;
import dev.spawnbotswrapper.inhabitants.combat.AggroWorld.SearchSpot;
import dev.spawnbotswrapper.inhabitants.combat.AggroWorld.Watcher;
import dev.spawnbotswrapper.inhabitants.combat.TargetControl.Settings;
import dev.spawnbotswrapper.inhabitants.combat.TargetControl.Target;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Fakes for the aggro controller tests: a world of people standing somewhere, a stand-in for PvP BOT's own target
 * resolution and steering, a planner that plans straight or as scripted, and a log that keeps every line. No
 * Minecraft, no PvP BOT.
 */
final class AggroFakes {
    private AggroFakes() {
    }

    /** A player, a bot or a mob standing somewhere; an inhabitant when registered as one. */
    static final class Person implements Watcher {
        final String name;
        double x;
        double y = 64;
        double z;
        String dimension = "overworld";
        boolean alive = true;
        boolean spectator;
        boolean creative;
        boolean invulnerable;
        boolean player = true;
        /** Names this person cannot see (line of sight blocked). */
        final Set<String> blind = new HashSet<>();
        /** Look direction (x, z); null = the fake cannot tell (the controller then uses plain line of sight). */
        double[] look = {1, 0};
        /** What this person is doing, for those who notice it. */
        Perception.Subject subject = Perception.Subject.player(false, false, false);
        int canSeeCalls;
        /** Attackers of hits taken since the controller last asked (the hit feed). */
        final List<Person> hitQueue = new ArrayList<>();
        /** Where this person looked to during the last tick (recorded by the control). */
        Pos lookedAt;

        Person(String name, double x) {
            this.name = name;
            this.x = x;
        }

        Person at(double x, double z) {
            this.x = x;
            this.z = z;
            return this;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean alive() {
            return alive;
        }

        @Override
        public boolean spectator() {
            return spectator;
        }

        @Override
        public boolean creative() {
            return creative;
        }

        @Override
        public boolean invulnerable() {
            return invulnerable;
        }

        @Override
        public boolean isPlayer() {
            return player;
        }

        @Override
        public Object dimension() {
            return dimension;
        }

        @Override
        public Pos position() {
            return new Pos(x, y, z);
        }

        @Override
        public double distanceTo(Body other) {
            Person o = (Person) other;
            double dx = o.x - x;
            double dy = o.y - y;
            double dz = o.z - z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        @Override
        public Object handle() {
            return this;
        }

        @Override
        public boolean canSee(Body other) {
            canSeeCalls++;
            return !blind.contains(other.name());
        }

        @Override
        public Body newHitAttacker() {
            return hitQueue.isEmpty() ? null : hitQueue.remove(0);
        }

        @Override
        public AggroWorld.Senses senses() {
            return look == null ? null : new AggroWorld.Senses(new Pos(x, y + 1.62, z), new Pos(look[0], 0, look[1]),
                    subject);
        }
    }

    static final class World implements AggroWorld {
        final Map<String, Person> online = new LinkedHashMap<>();
        final Set<String> inhabitants = new HashSet<>();
        int playersWithinCalls;
        int searchSpotCalls;
        /** Candidate search cells the world offers, by focus (a function so a test can script them). */
        BiFunction<Watcher, Pos, List<SearchSpot>> spots = (b, focus) -> List.of();

        Person add(String name, double x) {
            Person p = new Person(name, x);
            online.put(name.toLowerCase(), p);
            return p;
        }

        Person addInhabitant(String name, double x) {
            inhabitants.add(name.toLowerCase());
            return add(name, x);
        }

        @Override
        public List<? extends Watcher> inhabitants() {
            List<Person> out = new ArrayList<>();
            for (Person p : online.values()) {
                if (inhabitants.contains(p.name.toLowerCase())) {
                    out.add(p);
                }
            }
            return out;
        }

        @Override
        public Watcher inhabitant(String name) {
            return inhabitants.contains(name.toLowerCase()) ? online.get(name.toLowerCase()) : null;
        }

        @Override
        public Body bodyOf(Object entity) {
            Person p = (Person) entity;
            // A logged-out or removed entity reads as not alive, like a removed ServerPlayer.
            if (!online.containsValue(p)) {
                Person gone = new Person(p.name, p.x);
                gone.alive = false;
                return gone;
            }
            return p;
        }

        @Override
        public List<? extends Body> playersWithin(Watcher bot, double range) {
            playersWithinCalls++;
            Person self = (Person) bot;
            List<Person> out = new ArrayList<>();
            for (Person p : online.values()) {
                if (p != self && p.player && p.dimension.equals(self.dimension) && self.distanceTo(p) <= range) {
                    out.add(p);
                }
            }
            return out;
        }

        @Override
        public List<SearchSpot> searchSpots(Watcher bot, Pos focus, double radius) {
            searchSpotCalls++;
            return spots.apply(bot, focus);
        }
    }

    /**
     * A fake of PvP BOT's target routine: {@link #pvpTick} resolves each bot's target like BotCombat.findTarget does
     * (forced name first, then the last attacker, both only within {@code maxTargetDistance}); the controller reads and
     * writes through the {@link TargetControl} interface. Steering moves the bot when {@code walkSpeed} is set.
     */
    static class Control implements TargetControl {
        Settings settings = new Settings(true, false, true, false, false, false, false, 128.0);
        boolean available = true;
        boolean steering = true;
        boolean settingsFail;
        boolean failEverything;
        boolean steerFail;
        final Set<String> listed = new HashSet<>();
        final Set<String> allies = new HashSet<>();
        final Map<String, String> forced = new HashMap<>();
        final Map<String, Person> lastAttacker = new HashMap<>();
        /** Targets PvP BOT picks for another reason (a mob it decided to fight): never revenge. */
        final Map<String, Person> other = new HashMap<>();
        final Map<String, Target> current = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        final List<Pos> steered = new ArrayList<>();
        /** When set, a steer call moves the bot this many blocks toward the point (a walking bot). */
        double walkSpeed;
        /** Largest distance one tick moved a bot, over the whole run (a teleport would show here). */
        double maxStep;
        World world;
        int areAlliesCalls;
        int steerCalls;
        int lookCalls;
        int haltCalls;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public String unavailableReason() {
            return available ? null : "BotCombat.setTarget(String, String) - not found";
        }

        @Override
        public Settings settings() {
            if (settingsFail) {
                throw new UpstreamFailure("settings blew up");
            }
            return settings;
        }

        @Override
        public boolean isPvpBotBot(String name) {
            return listed.contains(name);
        }

        @Override
        public boolean areAllies(String a, String b) {
            areAlliesCalls++;
            if (failEverything) {
                throw new UpstreamFailure("faction registry broke");
            }
            return allies.contains(a + "," + b) || allies.contains(b + "," + a);
        }

        @Override
        public String forcedTarget(String bot) {
            if (failEverything) {
                throw new UpstreamFailure("combat state broke");
            }
            return forced.get(bot);
        }

        @Override
        public Target currentTarget(String bot) {
            if (failEverything) {
                throw new UpstreamFailure("combat state broke");
            }
            return current.get(bot);
        }

        @Override
        public void setTarget(String bot, String target) {
            calls.add("set " + bot + "->" + target);
            forced.put(bot, target);
        }

        @Override
        public void clearTarget(String bot) {
            calls.add("clear " + bot);
            forced.remove(bot);
            current.remove(bot);
            lastAttacker.remove(bot);
            other.remove(bot);
        }

        @Override
        public boolean steeringAvailable() {
            return steering;
        }

        @Override
        public String steeringProblem() {
            return steering ? null : "BotNavigation.moveTowardPosition - not found";
        }

        @Override
        public void steer(Object bot, Pos to, double speed) {
            if (steerFail) {
                throw new UpstreamFailure("steer blew up");
            }
            Person p = (Person) bot;
            steerCalls++;
            calls.add("steer " + p.name);
            steered.add(to);
            if (walkSpeed > 0) {
                double dx = to.x() - p.x;
                double dz = to.z() - p.z;
                double len = Math.sqrt(dx * dx + dz * dz);
                double step = Math.min(walkSpeed, len);
                if (len > 0) {
                    p.x += dx / len * step;
                    p.z += dz / len * step;
                    maxStep = Math.max(maxStep, step);
                }
                p.look = len > 0 ? new double[]{dx / len, dz / len} : p.look;
            }
        }

        @Override
        public void look(Object bot, Pos at) {
            Person p = (Person) bot;
            lookCalls++;
            calls.add("look " + p.name);
            p.lookedAt = at;
            double dx = at.x() - p.x;
            double dz = at.z() - p.z;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 1e-6) {
                p.look = new double[]{dx / len, dz / len};
            }
        }

        @Override
        public void halt(Object bot) {
            haltCalls++;
            calls.add("halt " + ((Person) bot).name);
        }

        /** PvP BOT's own tick: resolve every bot's target the way BotCombat.findTarget orders its sources. */
        void pvpTick() {
            for (Person bot : new ArrayList<>(world.online.values())) {
                if (!world.inhabitants.contains(bot.name.toLowerCase())) {
                    continue;
                }
                Person picked = null;
                boolean revenge = false;
                String name = forced.get(bot.name);
                if (name != null) {
                    Person p = world.online.get(name.toLowerCase());
                    if (p != null && p.alive && bot.distanceTo(p) <= settings.maxTargetDistance()) {
                        picked = p;
                    }
                }
                if (picked == null) {
                    Person p = lastAttacker.get(bot.name);
                    if (p != null && p.alive && bot.distanceTo(p) <= settings.maxTargetDistance()) {
                        picked = p;
                        revenge = true;
                    }
                }
                if (picked == null) {
                    Person p = other.get(bot.name);
                    if (p != null && p.alive && bot.distanceTo(p) <= settings.maxTargetDistance()) {
                        picked = p;
                    }
                }
                if (picked == null) {
                    current.remove(bot.name);
                } else {
                    current.put(bot.name, new Target(picked, picked.name, revenge));
                }
            }
        }

        List<String> callsOf(String verb) {
            return calls.stream().filter(c -> c.startsWith(verb + " ")).toList();
        }
    }

    /** A planner that answers as scripted and counts its calls. */
    static final class Planner implements PathPlanner {
        /** Straight: one waypoint, the goal; false = no route. */
        PathPlanner.Outcome outcome = PathPlanner.Outcome.REACHES;
        /** Optional: waypoints for a plan (null = the goal itself). */
        BiFunction<Object, Pos, List<Pos>> route;
        int plans;
        final List<Pos> goals = new ArrayList<>();

        @Override
        public Plan plan(Object bot, Pos goal, double maxRange) {
            plans++;
            goals.add(goal);
            if (outcome == PathPlanner.Outcome.NONE || outcome == PathPlanner.Outcome.UNAVAILABLE) {
                return Plan.none(outcome);
            }
            List<Pos> wps = route != null ? route.apply(bot, goal) : List.of(goal);
            return new Plan(wps, outcome);
        }
    }

    static final class Lines implements AggroController.Log {
        final List<String> debug = new ArrayList<>();
        final List<String> info = new ArrayList<>();
        final List<String> warn = new ArrayList<>();

        @Override
        public void debug(String message) {
            debug.add(message);
        }

        @Override
        public void info(String message) {
            info.add(message);
        }

        @Override
        public void warn(String message) {
            warn.add(message);
        }

        boolean any(String part) {
            return debug.stream().anyMatch(s -> s.contains(part)) || info.stream().anyMatch(s -> s.contains(part))
                    || warn.stream().anyMatch(s -> s.contains(part));
        }
    }

    /** One scene: a world with one inhabitant "Warden7" at the origin facing east, a far away player "Steve", and the controller. */
    static class Sim {
        final World world = new World();
        final Control up = new Control();
        final Planner planner = new Planner();
        final Lines log = new Lines();
        AggroController.Config config = AggroController.Config.defaults();
        final AggroController controller;
        long now = 1000;
        final Person bot;
        final Person steve;

        Sim() {
            up.world = world;
            controller = new AggroController(() -> config, up, planner, log);
            bot = world.addInhabitant("Warden7", 0);
            steve = world.add("Steve", 30);
        }

        /** Runs {@code n} ticks, each with PvP BOT's tick first and the controller's after it. */
        void run(int n) {
            for (int i = 0; i < n; i++) {
                up.pvpTick();
                controller.tick(now++, world);
            }
        }

        /** Runs ticks until {@code cond} holds or {@code max} ticks passed; returns the ticks run (max + 1 when it never held). */
        int runUntil(java.util.function.BooleanSupplier cond, int max) {
            for (int i = 0; i < max; i++) {
                run(1);
                if (cond.getAsBoolean()) {
                    return i + 1;
                }
            }
            return max + 1;
        }

        AggroController.Phase phase() {
            return controller.phaseOf(bot.name);
        }

        /** Somebody hits the bot: PvP BOT remembers the attacker as its revenge target, and the hit feed reports it. */
        void hit(Person attacker) {
            up.lastAttacker.put(bot.name, attacker);
            bot.hitQueue.add(attacker);
        }
    }
}

package dev.spawnbotswrapper.inhabitants.combat;

import dev.spawnbotswrapper.inhabitants.combat.AggroController.Cause;
import dev.spawnbotswrapper.inhabitants.combat.AggroController.Config;
import dev.spawnbotswrapper.inhabitants.combat.AggroController.Mode;
import dev.spawnbotswrapper.inhabitants.combat.AggroWorld.Body;
import dev.spawnbotswrapper.inhabitants.combat.AggroWorld.Pos;
import dev.spawnbotswrapper.inhabitants.combat.AggroWorld.Watcher;
import dev.spawnbotswrapper.inhabitants.combat.TargetControl.Settings;
import dev.spawnbotswrapper.inhabitants.combat.TargetControl.Target;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The aggro range and leash: who is noticed, when a chase is given up, that PvP BOT's target is cleared, that the
 * bot walks back (never teleports) and that nothing somebody else forced is ever touched. Everything runs against
 * fakes, including a small stand-in for PvP BOT's own target resolution; no Minecraft, no PvP BOT.
 */
class AggroControllerTest {

    // ---------------------------------------------------------------- fakes

    /** A player, a bot or a mob standing somewhere; an inhabitant when registered as one. */
    static final class Person implements Watcher {
        final String name;
        double x;
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
        double[] look;
        /** What this person is doing, for those who notice it. */
        Perception.Subject subject = Perception.Subject.player(false, false, false);
        int canSeeCalls;

        Person(String name, double x) {
            this.name = name;
            this.x = x;
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
            return new Pos(x, 64, z);
        }

        @Override
        public double distanceTo(Body other) {
            Person o = (Person) other;
            return Math.sqrt((o.x - x) * (o.x - x) + (o.z - z) * (o.z - z));
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
        public AggroWorld.Senses senses() {
            return look == null ? null : new AggroWorld.Senses(new Pos(x, 65.62, z), new Pos(look[0], 0, look[1]),
                    subject);
        }
    }

    static final class World implements AggroWorld {
        final Map<String, Person> online = new LinkedHashMap<>();
        final Set<String> inhabitants = new HashSet<>();
        int playersWithinCalls;

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
    }

    /**
     * A fake of PvP BOT's target routine: {@link #pvpTick} resolves each bot's target like BotCombat.findTarget
     * does (forced name first, then the last attacker, both only within {@code maxTargetDistance}); the controller
     * reads and writes through the {@link TargetControl} interface.
     */
    static class Control implements TargetControl {
        Settings settings = new Settings(true, false, true, false, false, false, false, 64.0);
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
        World world;
        int areAlliesCalls;

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
                }
            }
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

    // ---------------------------------------------------------------- harness

    World world;
    Control up;
    Lines log;
    Config config;
    AggroController controller;
    long now;
    Person bot;
    Person steve;

    @BeforeEach
    void setUp() {
        world = new World();
        up = new Control();
        up.world = world;
        log = new Lines();
        config = Config.defaults();
        controller = new AggroController(() -> config, up, log);
        now = 1000;
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

    /** Runs one full scan interval, so one scan certainly happens. */
    void scan() {
        run(config.scanIntervalTicks());
    }

    /** Somebody hits the bot: PvP BOT remembers the attacker as its revenge target. */
    void hit(Person attacker) {
        up.lastAttacker.put(bot.name, attacker);
    }

    // ---------------------------------------------------------------- noticing (rule 1, first clause)

    @Test
    void noticesAPlayerAt9Point5BlocksInSightButNotAt10Point5() {
        steve.x = 10.5;
        scan();
        assertEquals(List.of(), up.callsOf("set"), "10.5 blocks is outside the 10 block range");
        steve.x = 9.5;
        scan();
        assertEquals(List.of("set Warden7->Steve"), up.callsOf("set"));
        assertEquals("Steve", controller.engagedWith("Warden7"));
        assertEquals(Cause.ACQUIRED, controller.causeOf("Warden7"));
    }

    @Test
    void noticesExactlyAtTheRangeEdge() {
        steve.x = 10.0;
        scan();
        assertEquals(List.of("set Warden7->Steve"), up.callsOf("set"));
    }

    @Test
    void doesNotNoticeAPlayerWithoutLineOfSight() {
        steve.x = 5;
        bot.blind.add("Steve");
        run(100);
        assertEquals(List.of(), up.callsOf("set"));
        bot.blind.clear();
        scan();
        assertEquals(List.of("set Warden7->Steve"), up.callsOf("set"));
    }

    @Test
    void lineOfSightCanBeSwitchedOff() {
        steve.x = 5;
        bot.blind.add("Steve");
        config = new Config(true, 10.0, false, 5, 32.0, 200, true, 1.5, 200, 1200);
        scan();
        assertEquals(List.of("set Warden7->Steve"), up.callsOf("set"));
    }

    @Test
    void aFarPlayerIsNeverNoticed() {
        steve.x = 40;
        run(400);
        assertEquals(List.of(), up.callsOf("set"));
    }

    @Test
    void theRangeComesFromTheConfigurationAndIsRereadEveryScan() {
        steve.x = 15;
        scan();
        assertEquals(List.of(), up.callsOf("set"));
        config = new Config(true, 20.0, true, 5, 32.0, 200, true, 1.5, 200, 1200);
        scan();
        assertEquals(List.of("set Warden7->Steve"), up.callsOf("set"));
    }

    @Test
    void noticesTheNearestOfSeveralPlayers() {
        steve.x = 8;
        world.add("Alex", 4);
        world.add("Zed", 6);
        scan();
        assertEquals(List.of("set Warden7->Alex"), up.callsOf("set"));
    }

    @Test
    void skipsAnInvalidNearestPlayerForTheNextValidOne() {
        world.add("Alex", 4).creative = true;
        steve.x = 8;
        scan();
        assertEquals(List.of("set Warden7->Steve"), up.callsOf("set"));
    }

    @Test
    void scansOnlyEveryScanIntervalTicks() {
        steve.x = 5;
        up.pvpTick();
        controller.tick(now++, world);
        assertEquals(1, up.callsOf("set").size(), "the very first tick scans");
        world.addInhabitant("Ranger3", 2);
        run(config.scanIntervalTicks() - 1);
        assertEquals(1, up.callsOf("set").size(), "no scan inside the interval");
        run(1);
        assertEquals(2, up.callsOf("set").size(), "the next scan finds the second bot's player");
    }

    @Test
    void doesNotQueryPlayersOnTicksBetweenScans() {
        steve.x = 40;
        run(100);
        assertTrue(world.playersWithinCalls <= 100 / config.scanIntervalTicks() + 1, "at most one query per scan");
    }

    // ---------------------------------------------------------------- PvP BOT's validity rules

    @Test
    void doesNotNoticeCreativeSpectatorOrInvulnerablePlayers() {
        steve.x = 5;
        steve.creative = true;
        scan();
        steve.creative = false;
        steve.spectator = true;
        scan();
        steve.spectator = false;
        steve.invulnerable = true;
        scan();
        assertEquals(List.of(), up.callsOf("set"));
    }

    @Test
    void noticesThemWhenPvpBotAttacksInvincibleTargets() {
        steve.x = 5;
        steve.creative = true;
        up.settings = new Settings(true, false, true, false, true, false, false, 64.0);
        scan();
        assertEquals(List.of("set Warden7->Steve"), up.callsOf("set"));
    }

    @Test
    void doesNotNoticeOtherPvpBotBotsUnlessTargetOtherBotsIsOn() {
        steve.x = 5;
        up.listed.add("Steve");
        scan();
        assertEquals(List.of(), up.callsOf("set"));
        up.settings = new Settings(true, false, true, true, false, false, false, 64.0);
        scan();
        assertEquals(List.of("set Warden7->Steve"), up.callsOf("set"));
    }

    @Test
    void doesNotNoticePlayersWhenTargetPlayersIsOff() {
        steve.x = 5;
        up.settings = new Settings(true, false, false, false, false, false, false, 64.0);
        scan();
        assertEquals(List.of(), up.callsOf("set"));
    }

    @Test
    void doesNotNoticeFactionAlliesUnlessFriendlyFire() {
        steve.x = 5;
        up.allies.add("Warden7,Steve");
        up.settings = new Settings(true, false, true, false, false, true, false, 64.0);
        scan();
        assertEquals(List.of(), up.callsOf("set"));
        up.settings = new Settings(true, false, true, false, false, true, true, 64.0);
        scan();
        assertEquals(List.of("set Warden7->Steve"), up.callsOf("set"));
    }

    @Test
    void neverTouchesTheFactionRegistryWhileFactionsAreOff() {
        steve.x = 5;
        scan();
        assertEquals(0, up.areAlliesCalls);
    }

    @Test
    void aFailingFactionCheckMeansNoTargetNotAnAttack() {
        steve.x = 5;
        up.settings = new Settings(true, false, true, false, false, true, false, 64.0);
        // Only the faction registry is broken, not the combat state.
        up.allies.add("boom");
        Control broken = new Control() {
            @Override
            public boolean areAllies(String a, String b) {
                throw new UpstreamFailure("faction registry broke");
            }
        };
        broken.world = world;
        broken.settings = up.settings;
        controller = new AggroController(() -> config, broken, log);
        for (int i = 0; i < 20; i++) {
            broken.pvpTick();
            controller.tick(now++, world);
        }
        assertEquals(List.of(), broken.callsOf("set"));
        assertTrue(log.warn.stream().anyMatch(s -> s.contains("faction check failed")));
    }

    @Test
    void doesNotNoticeAPlayerInAnotherDimension() {
        steve.x = 5;
        steve.dimension = "nether";
        scan();
        assertEquals(List.of(), up.callsOf("set"));
    }

    @Test
    void aBotThatAlreadyHasATargetIsNotGivenAnother() {
        Person alex = world.add("Alex", 40);
        hit(alex);
        run(1);
        steve.x = 5;
        scan();
        assertEquals(List.of(), up.callsOf("set"));
    }

    // ---------------------------------------------------------------- hits (rule 1, second clause)

    @Test
    void aHitFrom30BlocksStartsAHitEngagementOriginatingAtTheBotsPosition() {
        bot.x = 100;
        bot.z = 50;
        steve.x = 130;
        steve.z = 50;
        hit(steve);
        run(1);
        assertEquals(Cause.HIT, controller.causeOf("Warden7"));
        assertEquals("Steve", controller.engagedWith("Warden7"));
        assertEquals(List.of(), up.callsOf("set"), "PvP BOT's own revenge does the chasing; nothing is forced");
        // The origin is where the bot stood when the hit landed: prove it by what the walk back steers to.
        bot.x = 100 + 32.5;
        run(1);
        assertTrue(controller.isReturning("Warden7"));
        assertEquals(new Pos(100, 64, 50), up.steered.get(0));
    }

    @Test
    void aFarHitIsChasedEvenThoughNothingWasNoticed() {
        steve.x = 30;
        hit(steve);
        run(20);
        assertEquals("Steve", controller.engagedWith("Warden7"));
        assertEquals(List.of(), up.callsOf("clear"));
    }

    @Test
    void aMobFightStartsAnOtherEngagementThatIsLeashedToo() {
        Person zombie = world.add("Zombie", 6);
        zombie.player = false;
        up.other.put("Warden7", zombie);
        run(1);
        assertEquals(Cause.OTHER, controller.causeOf("Warden7"));
        assertEquals("Zombie", controller.engagedWith("Warden7"));
        bot.x = 33;
        zombie.x = 36;
        run(2);
        assertTrue(controller.isReturning("Warden7"), "a bot chasing a zombie is leashed like any other");
        assertEquals(List.of("clear Warden7"), up.callsOf("clear"));
    }

    // ---------------------------------------------------------------- giving up: leash

    @Test
    void givesUpAt32Point1BlocksFromWhereItStartedButNotAt31Point9() {
        steve.x = 5;
        scan();
        assertEquals("Steve", controller.engagedWith("Warden7"));
        bot.x = 31.9;
        steve.x = 36;
        run(3);
        assertEquals("Steve", controller.engagedWith("Warden7"), "31.9 blocks from the origin is inside the leash");
        assertEquals(List.of(), up.callsOf("clear"));
        bot.x = 32.1;
        run(1);
        assertNull(controller.engagedWith("Warden7"));
        assertEquals(List.of("clear Warden7"), up.callsOf("clear"));
    }

    @Test
    void theLeashIsMeasuredFromTheEngagementOriginNotFromTheTarget() {
        steve.x = 5;
        scan();
        bot.x = 20;
        steve.x = 60;
        run(5);
        assertEquals("Steve", controller.engagedWith("Warden7"), "the target being far away is PvP BOT's business");
    }

    @Test
    void theLeashIgnoresHeight() {
        steve.x = 5;
        scan();
        bot.z = 31.9;
        run(3);
        assertEquals("Steve", controller.engagedWith("Warden7"));
        bot.z = 32.1;
        run(1);
        assertNull(controller.engagedWith("Warden7"));
    }

    @Test
    void theLeashRangeComesFromTheConfiguration() {
        steve.x = 5;
        scan();
        config = new Config(true, 10.0, true, 5, 20.0, 200, true, 1.5, 200, 1200);
        bot.x = 20.5;
        run(1);
        assertNull(controller.engagedWith("Warden7"));
    }

    // ---------------------------------------------------------------- giving up: sight

    @Test
    void givesUpAfter200ConsecutiveUnseenTicks() {
        steve.x = 5;
        scan();
        bot.blind.add("Steve");
        run(199);
        assertEquals("Steve", controller.engagedWith("Warden7"), "199 unseen ticks are not enough");
        run(1);
        assertNull(controller.engagedWith("Warden7"));
        assertEquals(List.of("clear Warden7"), up.callsOf("clear"));
    }

    @Test
    void theUnseenCounterResetsWhenSightReturns() {
        steve.x = 5;
        scan();
        bot.blind.add("Steve");
        run(150);
        bot.blind.clear();
        run(1);
        bot.blind.add("Steve");
        run(199);
        assertEquals("Steve", controller.engagedWith("Warden7"), "the count started again from 0");
        run(1);
        assertNull(controller.engagedWith("Warden7"));
    }

    @Test
    void theSightLimitComesFromTheConfiguration() {
        steve.x = 5;
        scan();
        config = new Config(true, 10.0, true, 5, 32.0, 20, true, 1.5, 200, 1200);
        bot.blind.add("Steve");
        run(19);
        assertNotNull(controller.engagedWith("Warden7"));
        run(1);
        assertNull(controller.engagedWith("Warden7"));
    }

    @Test
    void aHitEngagementAlsoEndsAfterTenSecondsUnseen() {
        hit(steve);
        run(1);
        bot.blind.add("Steve");
        run(200);
        assertNull(controller.engagedWith("Warden7"));
        assertEquals(List.of("clear Warden7"), up.callsOf("clear"));
    }

    // ---------------------------------------------------------------- giving up: target and bot state

    @Test
    void givesUpWhenTheTargetLogsOutAndClearsPromptly() {
        steve.x = 5;
        scan();
        world.online.remove("steve");
        run(1);
        assertNull(controller.engagedWith("Warden7"));
        assertEquals(List.of("clear Warden7"), up.callsOf("clear"));
        assertNull(up.forced.get("Warden7"), "no forced name for a logged-out player stays behind");
    }

    @Test
    void givesUpWhenTheTargetDies() {
        steve.x = 5;
        scan();
        steve.alive = false;
        run(1);
        assertNull(controller.engagedWith("Warden7"));
        assertEquals(List.of("clear Warden7"), up.callsOf("clear"));
    }

    @Test
    void givesUpWhenTheTargetChangesDimension() {
        steve.x = 5;
        scan();
        steve.dimension = "nether";
        run(1);
        assertNull(controller.engagedWith("Warden7"));
    }

    @Test
    void givesUpWhenTheTargetGoesCreative() {
        steve.x = 5;
        scan();
        steve.creative = true;
        run(1);
        assertNull(controller.engagedWith("Warden7"));
    }

    @Test
    void givesUpWhenPvpBotItselfDropsTheTarget() {
        steve.x = 5;
        scan();
        up.settings = new Settings(true, false, true, false, false, false, false, 3.0);
        run(1);
        // (Steve may be noticed again at once, being in range: what matters is that the old engagement ended.)
        assertEquals(List.of("clear Warden7"), up.callsOf("clear"), "PvP BOT no longer resolves a target, so the engagement is over");
        assertTrue(log.any("(target lost"));
    }

    @Test
    void aBotThatChangesLevelGivesUpWithoutAWalkBack() {
        steve.x = 5;
        scan();
        bot.dimension = "nether";
        run(1);
        assertNull(controller.engagedWith("Warden7"));
        assertFalse(controller.isReturning("Warden7"), "the origin is in another level: nothing to walk back to");
    }

    @Test
    void aBotThatDiesDropsTheEngagementAndTheForcedNameWithoutReturning() {
        steve.x = 5;
        scan();
        bot.alive = false;
        run(1);
        assertNull(controller.engagedWith("Warden7"));
        assertFalse(controller.isReturning("Warden7"));
        assertNull(up.forced.get("Warden7"), "the force this controller set is released");
    }

    @Test
    void aBotThatLeavesDropsEverythingIncludingAPendingReturn() {
        steve.x = 5;
        scan();
        bot.x = 40;
        run(2);
        assertTrue(controller.isReturning("Warden7"));
        world.online.remove("warden7");
        run(1);
        assertFalse(controller.isReturning("Warden7"));
        assertEquals(0, controller.stats().returning());
    }

    @Test
    void aDeadBotWithAPendingReturnForgetsIt() {
        steve.x = 5;
        scan();
        bot.x = 40;
        run(2);
        assertTrue(controller.isReturning("Warden7"));
        bot.alive = false;
        run(1);
        assertFalse(controller.isReturning("Warden7"));
    }

    // ---------------------------------------------------------------- external forced targets

    @Test
    void aForcedTargetSomeoneElseSetIsTrackedButNeverLeashedOrCleared() {
        up.forced.put("Warden7", "Steve");
        steve.x = 5;
        run(1);
        assertEquals("Steve", controller.engagedWith("Warden7"));
        assertTrue(controller.describe("Warden7").contains("external"), controller.describe("Warden7"));
        bot.x = 200;
        steve.x = 210;
        bot.blind.add("Steve");
        run(600);
        assertEquals("Steve", controller.engagedWith("Warden7"), "never leashed, never timed out");
        assertEquals(List.of(), up.callsOf("clear"));
        assertEquals("Steve", up.forced.get("Warden7"));
        assertFalse(controller.isReturning("Warden7"));
    }

    @Test
    void anExternalForceThatReplacesOursIsLeftAlone() {
        steve.x = 5;
        scan();
        world.add("Alex", 6);
        up.forced.put("Warden7", "Alex");
        bot.x = 200;
        run(300);
        assertEquals(List.of(), up.callsOf("clear"));
        assertEquals("Alex", up.forced.get("Warden7"));
    }

    @Test
    void whenTheExternalForceEndsTheFightIsJudgedLikeAnyOther() {
        up.forced.put("Warden7", "Steve");
        steve.x = 5;
        run(1);
        up.forced.remove("Warden7");
        up.lastAttacker.put("Warden7", steve);
        run(2);
        assertEquals(Cause.HIT, controller.causeOf("Warden7"));
    }

    // ---------------------------------------------------------------- the walk back

    @Test
    void afterGivingUpItWalksBackTickByTickAndArrivesAtOneAndAHalfBlocks() {
        steve.x = 5;
        scan();
        up.walkSpeed = 0.5;
        bot.x = 33;
        steve.x = 300; // out of range: nothing new is noticed on the way home
        run(1);
        assertTrue(controller.isReturning("Warden7"));
        assertTrue(controller.describe("Warden7").startsWith("returning home, "), controller.describe("Warden7"));
        int steps = 0;
        while (controller.isReturning("Warden7") && steps < 200) {
            run(1);
            steps++;
        }
        assertFalse(controller.isReturning("Warden7"));
        assertTrue(steps > 50, "a 33 block walk at half a block per tick takes a while: " + steps);
        assertTrue(Math.abs(bot.x) <= 1.5, "the bot stands within 1.5 blocks of the origin, x=" + bot.x);
        assertTrue(up.callsOf("steer").size() >= steps, "one steer call per tick while walking");
        assertEquals(1, controller.stats().returned());
    }

    @Test
    void steersTowardTheOriginEveryTickAndNeverAnythingElse() {
        bot.x = 10;
        bot.z = 4;
        steve.x = 12;
        steve.z = 4;
        hit(steve);
        run(1);
        bot.x = 10 + 32.5;
        run(6);
        assertEquals(6, up.steered.size(), "the give-up tick and each tick after it");
        for (Pos p : up.steered) {
            assertEquals(new Pos(10, 64, 4), p);
        }
    }

    @Test
    void arrivingWithinOneAndAHalfBlocksEndsTheReturn() {
        steve.x = 5;
        scan();
        bot.x = 33;
        run(1);
        assertTrue(controller.isReturning("Warden7"));
        bot.x = 1.6;
        run(1);
        assertTrue(controller.isReturning("Warden7"), "1.6 blocks is not yet arrived");
        bot.x = 1.4;
        run(1);
        assertFalse(controller.isReturning("Warden7"));
    }

    /** Steve is noticed at 0, the bot follows to 33 (past the leash) and gives up: it now walks home from x=33. */
    private void engageThenGiveUpAt33() {
        steve.x = 5;
        scan();
        bot.x = 33;
        run(2);
        assertTrue(controller.isReturning("Warden7"));
        assertTrue(controller.hasHome("Warden7"));
        steve.x = 300; // out of everybody's range: nothing new is noticed on the way home
    }

    @Test
    void aPlayerNoticedWhileReturningIsAcquiredWithATemporaryOrigin() {
        engageThenGiveUpAt33();
        world.add("Alex", 41);
        run(config.scanIntervalTicks());
        assertEquals(List.of("set Warden7->Steve", "set Warden7->Alex"), up.callsOf("set"));
        assertEquals("Alex", controller.engagedWith("Warden7"));
        assertEquals(Cause.ACQUIRED, controller.causeOf("Warden7"));
        assertTrue(controller.hasTemporaryOrigin("Warden7"));
        assertFalse(controller.isReturning("Warden7"), "the walk ends while the new chase runs");
        assertTrue(controller.hasHome("Warden7"), "the home anchor is kept");
        assertTrue(controller.describe("Warden7").contains("from temp origin, home 33.0 away"),
                controller.describe("Warden7"));
    }

    @Test
    void noticingWhileReturningKeepsTheRegularRules() {
        engageThenGiveUpAt33();
        Person alex = world.add("Alex", 43.5);
        run(20);
        assertEquals(1, up.callsOf("set").size(), "10.5 blocks is outside the range");
        alex.x = 41;
        bot.blind.add("Alex");
        run(20);
        assertEquals(1, up.callsOf("set").size(), "no line of sight, no noticing");
        assertTrue(controller.isReturning("Warden7"));
        alex.creative = true;
        bot.blind.clear();
        run(20);
        assertEquals(1, up.callsOf("set").size(), "an invalid target is not noticed");
        alex.creative = false;
        run(config.scanIntervalTicks());
        assertEquals(2, up.callsOf("set").size());
    }

    @Test
    void aTemporaryAcquisitionIsLeashedFromItsOwnOriginAndThenWalksHome() {
        engageThenGiveUpAt33();
        world.add("Alex", 41);
        run(config.scanIntervalTicks());
        assertTrue(controller.hasTemporaryOrigin("Warden7"));
        // 31.9 blocks from the temporary origin (33, 0) but 45 from home: still inside its own leash.
        bot.z = 31.9;
        run(3);
        assertEquals("Alex", controller.engagedWith("Warden7"));
        bot.z = 32.1;
        run(1);
        assertNull(controller.engagedWith("Warden7"));
        assertTrue(controller.isReturning("Warden7"));
        assertEquals(new Pos(0, 64, 0), up.steered.get(up.steered.size() - 1), "home, never the temporary origin");
    }

    @Test
    void anAcquisitionIsNotStartedOnTheTickAChaseWasGivenUpSoTheWalkGetsItsSteeringTick() {
        config = new Config(true, 10.0, true, 1, 32.0, 200, true, 1.5, 200, 1200);
        steve.x = 5;
        run(1);
        assertEquals("Steve", controller.engagedWith("Warden7"));
        bot.x = 33;
        steve.x = 34;
        int steersBefore = up.steered.size();
        run(1);
        assertTrue(controller.isReturning("Warden7"));
        assertEquals(steersBefore + 1, up.steered.size(), "the give-up tick already steers home");
        run(1);
        assertEquals("Steve", controller.engagedWith("Warden7"), "next tick the regular noticing applies again");
        assertTrue(controller.hasTemporaryOrigin("Warden7"));
    }

    @Test
    void theWalkHomeIsSteeredOnEveryTickIncludingScanTicks() {
        engageThenGiveUpAt33();
        up.walkSpeed = 0.5;
        int before = up.steered.size();
        for (int i = 1; i <= 40; i++) {
            run(1);
            assertEquals(before + i, up.steered.size(), "one steer per tick, tick " + i);
        }
    }

    @Test
    void afterTheReturnTheBotNoticesPlayersAgain() {
        steve.x = 5;
        scan();
        bot.x = 33;
        run(2);
        bot.x = 0;
        steve.x = 6;
        run(1);
        assertFalse(controller.isReturning("Warden7"));
        scan();
        assertEquals(2, up.callsOf("set").size());
    }

    @Test
    void aHitWhileReturningStartsATemporaryOriginAndTheLeashIsMeasuredFromIt() {
        engageThenGiveUpAt33();
        // Walking home, the bot is at x=25 when Steve (far outside the noticing range) hits it.
        bot.x = 25;
        steve.x = 55;
        hit(steve);
        run(1);
        assertEquals(Cause.HIT, controller.causeOf("Warden7"));
        assertTrue(controller.hasTemporaryOrigin("Warden7"));
        assertFalse(controller.isReturning("Warden7"));
        assertTrue(controller.hasHome("Warden7"));
        assertTrue(controller.describe("Warden7").contains("(hit) 0.0 from temp origin, home 25.0 away"),
                controller.describe("Warden7"));
        // 31.9 blocks from the temporary origin (25) while only 6.9 from home: inside its leash.
        bot.x = 25 - 31.9;
        run(3);
        assertEquals("Steve", controller.engagedWith("Warden7"));
        // 32.1 blocks from the temporary origin though only 7.1 from home: over its leash.
        bot.x = 25 - 32.1;
        run(1);
        assertNull(controller.engagedWith("Warden7"));
        assertEquals(2, up.callsOf("clear").size());
        assertTrue(controller.isReturning("Warden7"));
        assertEquals(new Pos(0, 64, 0), up.steered.get(up.steered.size() - 1), "home, not the temporary origin");
    }

    @Test
    void aHitDuringARunningEngagementChangesNothing() {
        steve.x = 5;
        scan();
        Person alex = world.add("Alex", 8);
        bot.x = 20;
        run(1);
        hit(alex);
        run(3);
        assertFalse(controller.hasTemporaryOrigin("Warden7"));
        assertEquals(Cause.ACQUIRED, controller.causeOf("Warden7"));
        // Still measured from the original origin (0): 20 is inside, 32.1 is out.
        bot.x = 32.1;
        run(1);
        assertNull(controller.engagedWith("Warden7"));
        assertEquals(new Pos(0, 64, 0), up.steered.get(up.steered.size() - 1));
    }

    @Test
    void aTemporaryEngagementThatGivesUpAlwaysWalksHomeNeverToItsOwnOrigin() {
        engageThenGiveUpAt33();
        bot.x = 25;
        steve.x = 55;
        hit(steve);
        run(1);
        bot.blind.add("Steve");
        run(config.loseSightTicks());
        assertNull(controller.engagedWith("Warden7"));
        assertTrue(controller.isReturning("Warden7"));
        assertEquals(new Pos(0, 64, 0), up.steered.get(up.steered.size() - 1));
        assertTrue(controller.describe("Warden7").startsWith("returning home, 25.0 to go"),
                controller.describe("Warden7"));
    }

    @Test
    void theHomeIsClearedOnArrival() {
        engageThenGiveUpAt33();
        bot.x = 1.4;
        run(1);
        assertFalse(controller.isReturning("Warden7"));
        assertFalse(controller.hasHome("Warden7"));
    }

    @Test
    void theHomeIsClearedWhenTheWalkIsAbandoned() {
        engageThenGiveUpAt33();
        run(config.returnStuckTicks() + 1);
        assertFalse(controller.isReturning("Warden7"));
        assertFalse(controller.hasHome("Warden7"));
        assertEquals(1, up.callsOf("set").size(), "nothing new was noticed");
    }

    @Test
    void theHomeIsClearedWhenTheBotDiesOrLeaves() {
        engageThenGiveUpAt33();
        bot.alive = false;
        run(1);
        assertFalse(controller.hasHome("Warden7"));
        bot.alive = true;
        // Another life: a fresh engagement sets a fresh home right where the bot stands now.
        bot.x = 50;
        steve.x = 55;
        hit(steve);
        run(1);
        assertTrue(controller.hasHome("Warden7"));
        assertFalse(controller.hasTemporaryOrigin("Warden7"));
        world.online.remove("warden7");
        run(1);
        assertFalse(controller.hasHome("Warden7"));
    }

    @Test
    void aNewEngagementAfterArrivalSetsAFreshHomeAndOrigin() {
        engageThenGiveUpAt33();
        bot.x = 1.4;
        run(1);
        assertFalse(controller.hasHome("Warden7"));
        bot.x = 20;
        steve.x = 45;
        hit(steve);
        run(1);
        assertEquals(Cause.HIT, controller.causeOf("Warden7"));
        assertFalse(controller.hasTemporaryOrigin("Warden7"), "not walking home: this is a first engagement again");
        assertTrue(controller.hasHome("Warden7"));
        bot.x = 20 + 32.5;
        run(1);
        assertEquals(new Pos(20, 64, 0), up.steered.get(up.steered.size() - 1), "the new home is where it stood");
    }

    @Test
    void theStuckGuardCountsPerLegAndRestartsWithANewLeg() {
        engageThenGiveUpAt33();
        // 150 ticks of a stuck first leg...
        run(150);
        assertTrue(controller.isReturning("Warden7"));
        // ...then a hit starts a temporary chase that ends unseen; the new leg starts its counters from zero.
        steve.x = 60;
        hit(steve);
        run(1);
        assertFalse(controller.isReturning("Warden7"));
        bot.blind.add("Steve");
        run(config.loseSightTicks());
        assertTrue(controller.isReturning("Warden7"));
        run(config.returnStuckTicks() - 3);
        assertTrue(controller.isReturning("Warden7"), "199 ticks into the new leg is not yet stuck");
        run(4);
        assertFalse(controller.isReturning("Warden7"));
        assertTrue(log.any("stops walking back: stuck"));
    }

    @Test
    void theMaximumTimeGuardCountsPerLegToo() {
        config = new Config(true, 10.0, true, 5, 32.0, 200, true, 1.5, 100000, 1200);
        engageThenGiveUpAt33();
        up.walkSpeed = 0.01;
        run(1000);
        steve.x = 60;
        hit(steve);
        run(1);
        bot.blind.add("Steve");
        run(config.loseSightTicks());
        assertTrue(controller.isReturning("Warden7"));
        run(1100);
        assertTrue(controller.isReturning("Warden7"), "1100 ticks into the new leg: the old 1200 tick budget is gone");
        run(200);
        assertFalse(controller.isReturning("Warden7"));
        assertTrue(log.any("stops walking back: took too long"));
    }

    // ---------------------------------------------------------------- forced names PvP BOT writes itself

    @Test
    void aForcedNamePvpBotWritesForTheAttackerDoesNotEscapeTheLeash() {
        steve.x = 10;
        hit(steve);
        run(1);
        assertEquals(Cause.HIT, controller.causeOf("Warden7"));
        // PvP BOT's wind-burst / elytra flow forces the attacker's name itself.
        up.forced.put("Warden7", "Steve");
        run(3);
        assertEquals("Steve", controller.engagedWith("Warden7"));
        assertFalse(controller.describe("Warden7").contains("external"), controller.describe("Warden7"));
        bot.x = 32.1;
        run(1);
        assertNull(controller.engagedWith("Warden7"), "still leashed");
        assertEquals(List.of("clear Warden7"), up.callsOf("clear"));
        assertTrue(controller.isReturning("Warden7"));
    }

    @Test
    void aForcedNamePvpBotWritesForTheAttackerDoesNotEscapeTheSightRule() {
        steve.x = 10;
        hit(steve);
        run(1);
        up.forced.put("Warden7", "Steve");
        bot.blind.add("Steve");
        run(config.loseSightTicks());
        assertNull(controller.engagedWith("Warden7"));
        assertEquals(List.of("clear Warden7"), up.callsOf("clear"));
    }

    @Test
    void aForcedNameForSomeoneElseThanTheAttackerStillCountsAsExternal() {
        steve.x = 10;
        hit(steve);
        run(1);
        world.add("Alex", 6);
        up.forced.put("Warden7", "Alex");
        run(1);
        assertTrue(controller.describe("Warden7").contains("external"), controller.describe("Warden7"));
        assertFalse(controller.hasHome("Warden7"), "an external force has no home to walk back to");
        bot.x = 100;
        run(50);
        assertEquals(List.of(), up.callsOf("clear"));
    }

    @Test
    void theAnchorResetsOnceTheBotHasReturned() {
        steve.x = 5;
        scan();
        bot.x = 33;
        run(2);
        bot.x = 0;
        run(1);
        assertFalse(controller.isReturning("Warden7"));
        bot.x = 20;
        steve.x = 22;
        hit(steve);
        run(1);
        bot.x = 20 + 32.5;
        run(3);
        assertEquals(new Pos(20, 64, 0), up.steered.get(up.steered.size() - 1), "the new fight has a new origin");
    }

    @Test
    void abandonsAReturnThatMakesNoProgress() {
        steve.x = 5;
        scan();
        bot.x = 40;
        run(2);
        assertTrue(controller.isReturning("Warden7"));
        // The bot never moves (stuck against a wall): no progress over the stuck window.
        run(197);
        assertTrue(controller.isReturning("Warden7"));
        run(3);
        assertFalse(controller.isReturning("Warden7"));
        assertTrue(log.any("stops walking back: stuck"));
        assertEquals(1, controller.stats().abandoned().values().stream().mapToLong(Long::longValue).sum());
    }

    @Test
    void progressOfAtLeastOneBlockPerStuckWindowKeepsTheReturnAlive() {
        steve.x = 5;
        scan();
        bot.x = 40;
        run(1);
        up.walkSpeed = 0.02;
        for (int i = 0; i < 500; i++) {
            run(1);
            assertTrue(controller.isReturning("Warden7"), "still making progress at tick " + i);
        }
    }

    @Test
    void abandonsAReturnAfterTheMaximumTime() {
        steve.x = 5;
        scan();
        config = new Config(true, 10.0, true, 5, 32.0, 200, true, 1.5, 100000, 1200);
        bot.x = 40;
        run(2);
        up.walkSpeed = 0.01;
        run(1190);
        assertTrue(controller.isReturning("Warden7"));
        run(20);
        assertFalse(controller.isReturning("Warden7"));
        assertTrue(log.any("stops walking back: took too long"));
    }

    @Test
    void doesNotWalkBackWhenTurnedOffInTheConfiguration() {
        steve.x = 5;
        scan();
        config = new Config(true, 10.0, true, 5, 32.0, 200, false, 1.5, 200, 1200);
        bot.x = 40;
        run(3);
        assertFalse(controller.isReturning("Warden7"));
        assertEquals(List.of(), up.callsOf("steer"));
    }

    @Test
    void doesNotWalkBackWhenTheNavigationCallsAreMissingAndSaysWhyOnce() {
        up.steering = false;
        steve.x = 5;
        scan();
        bot.x = 40;
        run(2);
        assertFalse(controller.isReturning("Warden7"));
        assertEquals(1, log.warn.stream().filter(s -> s.contains("cannot walk back")).count());
        assertNull(controller.engagedWith("Warden7"), "the leash itself still works");
        assertEquals(List.of("clear Warden7"), up.callsOf("clear"));
    }

    // ---------------------------------------------------------------- inert mode

    @Test
    void whilePvpBotAutoTargetIsOnNothingIsNoticedAndItIsSaidOnce() {
        up.settings = new Settings(true, true, true, false, false, false, false, 64.0);
        steve.x = 5;
        run(100);
        assertEquals(List.of(), up.callsOf("set"));
        assertEquals(Mode.INERT_AUTO_TARGET, controller.mode());
        assertEquals(1, log.info.stream().filter(s -> s.contains("auto-target is on")).count());
    }

    @Test
    void inertModeStillLeashesAndReturnsAnEngagementPvpBotStartedByItself() {
        up.settings = new Settings(true, true, true, false, false, false, false, 64.0);
        hit(steve);
        run(1);
        assertEquals(Cause.HIT, controller.causeOf("Warden7"));
        bot.x = 33;
        run(2);
        assertEquals(List.of("clear Warden7"), up.callsOf("clear"));
        assertTrue(controller.isReturning("Warden7"));
    }

    @Test
    void combatOffMeansNothingIsNoticedAndNoEngagementStarts() {
        up.settings = new Settings(false, false, true, false, false, false, false, 64.0);
        steve.x = 5;
        hit(steve);
        run(50);
        assertEquals(List.of(), up.callsOf("set"));
        assertNull(controller.engagedWith("Warden7"));
        assertEquals(Mode.INERT_COMBAT_OFF, controller.mode());
    }

    @Test
    void unreadableSettingsMeanNothingIsNoticed() {
        up.settingsFail = true;
        steve.x = 5;
        run(20);
        assertEquals(List.of(), up.callsOf("set"));
        assertEquals(Mode.SETTINGS_UNREADABLE, controller.mode());
    }

    // ---------------------------------------------------------------- robustness and diagnostics

    @Test
    void disabledInTheConfigurationDoesNothingAndDropsWhatItHolds() {
        steve.x = 5;
        scan();
        config = new Config(false, 10.0, true, 5, 32.0, 200, true, 1.5, 200, 1200);
        run(1);
        assertEquals(Mode.OFF, controller.mode());
        assertNull(controller.engagedWith("Warden7"));
        assertNull(up.forced.get("Warden7"), "the force this controller set is released when it is switched off");
        steve.x = 4;
        run(20);
        assertEquals(1, up.callsOf("set").size());
    }

    @Test
    void missingTargetCallsDisableTheControllerAndSayWhy() {
        up.available = false;
        steve.x = 5;
        run(20);
        assertEquals(List.of(), up.calls);
        assertEquals(Mode.UPSTREAM_UNAVAILABLE, controller.mode());
        assertEquals(1, log.warn.stream().filter(s -> s.contains("not found")).count());
    }

    @Test
    void repeatedUpstreamFailuresSwitchTheControllerOffAfterTwentyTicks() {
        steve.x = 5;
        up.failEverything = true;
        run(AggroController.MAX_CONSECUTIVE_FAILURES + 5);
        assertEquals(Mode.FAILED, controller.mode());
        int warnings = log.warn.size();
        run(50);
        assertEquals(warnings, log.warn.size(), "a failed controller is silent");
    }

    @Test
    void aFailedSteerCallIsReportedOnceAndDoesNotKillTheWalk() {
        steve.x = 5;
        scan();
        bot.x = 40;
        up.steerFail = true;
        run(10);
        assertTrue(controller.isReturning("Warden7"));
        assertEquals(1, log.warn.stream().filter(s -> s.contains("steer blew up")).count());
    }

    @Test
    void warnsWhenTheLeashCanOutrunPvpBotsChaseLimit() {
        up.settings = new Settings(true, false, true, false, false, false, false, 40.0);
        run(1);
        assertTrue(log.warn.stream().anyMatch(s -> s.contains("maxTargetDistance 40.0")), log.warn.toString());
        run(50);
        assertEquals(1, log.warn.stream().filter(s -> s.contains("maxTargetDistance")).count(), "warned once");
    }

    @Test
    void doesNotWarnWhenTheRangesFitPvpBotsLimit() {
        run(10);
        assertFalse(log.warn.stream().anyMatch(s -> s.contains("maxTargetDistance")));
    }

    @Test
    void statusTextShowsTheEngagementAndTheReturn() {
        run(1);
        assertEquals("idle", controller.describe("Warden7"));
        steve.x = 5;
        scan();
        String engaged = controller.describe("Warden7");
        assertTrue(engaged.startsWith("engaged Steve (acquired by sight) 0.0 from origin, unseen 0t"), engaged);
        bot.blind.add("Steve");
        run(40);
        assertTrue(controller.describe("Warden7").endsWith("unseen 40t"), controller.describe("Warden7"));
        bot.x = 18;
        bot.blind.clear();
        run(1);
        assertTrue(controller.describe("Warden7").contains("18.0 from origin"), controller.describe("Warden7"));
        bot.x = 33;
        run(1);
        assertEquals("returning home, 33.0 to go", controller.describe("Warden7"));
    }

    @Test
    void infoLinesAreRateLimitedPerBotAndTheRestGoToDebug() {
        steve.x = 5;
        for (int i = 0; i < 5; i++) {
            scan();
            bot.x = 40;
            run(3);
            bot.x = 0;
            run(3);
        }
        long infos = log.info.stream().filter(s -> s.contains("Warden7")).count();
        assertTrue(infos <= 2, "a bot that keeps starting and giving up must not flood the log: " + infos);
        assertTrue(log.debug.stream().anyMatch(s -> s.contains("gives up")));
    }

    @Test
    void aNewServerWithRestartedTicksForgetsEverything() {
        steve.x = 5;
        scan();
        bot.x = 40;
        run(2);
        assertTrue(controller.isReturning("Warden7"));
        now = 3;
        run(1);
        assertFalse(controller.isReturning("Warden7"));
        assertNull(controller.engagedWith("Warden7"));
    }

    @Test
    void statsCountTheWholeStory() {
        steve.x = 5;
        scan();
        bot.x = 33;
        run(2);
        bot.x = 0;
        run(1);
        AggroController.Stats stats = controller.stats();
        assertEquals(1, stats.acquisitions());
        assertEquals(1, stats.returned());
        assertEquals(Map.of("leash", 1L), stats.giveUps());
        assertEquals(0, stats.engaged());
    }

    // ---------------------------------------------------------------- realistic perception (see Perception)

    /** The bot at x=0 looks along +x; Steve is a plain standing player. Positive x is in front, negative behind. */
    void perceive() {
        bot.look = new double[]{1, 0};
        steve.look = new double[]{-1, 0};
    }

    private static Perception.Subject sneakingWalk() {
        return Perception.Subject.player(true, true, false);
    }

    private static Perception.Subject walking() {
        return Perception.Subject.player(false, true, false);
    }

    private static Perception.Subject sprinting() {
        return Perception.Subject.player(false, true, true);
    }

    /** A player standing at {@code degrees} off the bot's look direction, {@code distance} blocks away. */
    void placeAt(double degrees, double distance) {
        steve.x = Math.cos(Math.toRadians(degrees)) * distance;
        steve.z = Math.sin(Math.toRadians(degrees)) * distance;
    }

    @Test
    void aPlayerSneakingUpFromBehindIsNeverNoticedUntilTheyHit() {
        perceive();
        steve.x = -2;
        steve.subject = sneakingWalk();
        run(200);
        assertEquals(List.of(), up.callsOf("set"), "nobody sees a sneaking player behind them, at 2 blocks");
        assertNull(controller.engagedWith("Warden7"));
        hit(steve);
        run(1);
        assertEquals("Steve", controller.engagedWith("Warden7"), "the hit makes the bot aware of the attacker");
        assertEquals(Cause.HIT, controller.causeOf("Warden7"));
        assertTrue(controller.describe("Warden7").startsWith("engaged Steve (hit) "), controller.describe("Warden7"));
    }

    @Test
    void aStandingPlayerBehindIsNotNoticedEitherBecauseSightHasNoBack() {
        perceive();
        steve.x = -2;
        run(100);
        assertEquals(List.of(), up.callsOf("set"));
    }

    @Test
    void aPlayerWalkingBehindAtThreeBlocksIsHeard() {
        perceive();
        steve.x = -3;
        steve.subject = walking();
        scan();
        assertEquals(List.of("set Warden7->Steve"), up.callsOf("set"));
        assertEquals(Cause.ACQUIRED, controller.causeOf("Warden7"));
        assertTrue(controller.describe("Warden7").startsWith("engaged Steve (acquired by hearing) "),
                controller.describe("Warden7"));
        assertTrue(log.info.stream().anyMatch(s -> s.contains("noticed Steve") && s.contains("by hearing")),
                log.info.toString());
    }

    @Test
    void aPlayerWalkingBehindAtFiveBlocksIsNot() {
        perceive();
        steve.x = -5;
        steve.subject = walking();
        run(100);
        assertEquals(List.of(), up.callsOf("set"));
    }

    @Test
    void aSprintingPlayerBehindIsHeardAtSevenButNotAtNine() {
        perceive();
        steve.subject = sprinting();
        steve.x = -9;
        scan();
        assertEquals(List.of(), up.callsOf("set"));
        steve.x = -7;
        scan();
        assertEquals(List.of("set Warden7->Steve"), up.callsOf("set"));
    }

    @Test
    void aPlayerInFrontAtNinePointFiveIsSeenStanding() {
        perceive();
        steve.x = 9.5;
        scan();
        assertEquals(List.of("set Warden7->Steve"), up.callsOf("set"));
        assertTrue(controller.describe("Warden7").startsWith("engaged Steve (acquired by sight) "),
                controller.describe("Warden7"));
    }

    @Test
    void aSneakingPlayerInFrontIsSeenFromHalfTheDistance() {
        perceive();
        steve.subject = sneakingWalk();
        steve.x = 6;
        run(100);
        assertEquals(List.of(), up.callsOf("set"), "6 blocks is beyond half of the 10 block range");
        steve.x = 4.5;
        scan();
        assertEquals(List.of("set Warden7->Steve"), up.callsOf("set"));
    }

    @Test
    void thePeripheralFieldReachesHalfAsFar() {
        perceive();
        placeAt(70, 5.1);
        run(100);
        assertEquals(List.of(), up.callsOf("set"));
        placeAt(70, 4.9);
        scan();
        assertEquals(List.of("set Warden7->Steve"), up.callsOf("set"));
    }

    @Test
    void hearingNeverPassesAWall() {
        perceive();
        steve.x = -2;
        steve.subject = walking();
        bot.blind.add("Steve");
        run(100);
        assertEquals(List.of(), up.callsOf("set"), "a walking player behind a wall is not heard");
        bot.blind.clear();
        scan();
        assertEquals(List.of("set Warden7->Steve"), up.callsOf("set"));
    }

    @Test
    void aPlayerWhoJustFoughtIsHeardFromBehindEvenWhenSneaking() {
        perceive();
        steve.x = -9;
        steve.subject = new Perception.Subject(true, false, false, 2, Perception.MobKind.NONE, 1.0);
        scan();
        assertEquals(List.of("set Warden7->Steve"), up.callsOf("set"));
        assertTrue(controller.describe("Warden7").contains("by hearing"), controller.describe("Warden7"));
    }

    @Test
    void theOcclusionRayIsOnlyCastWhenRangeAndAngleAlreadyAllowNoticing() {
        perceive();
        steve.x = -2;
        steve.subject = sneakingWalk();
        run(50);
        steve.x = 12;
        steve.subject = Perception.Subject.player(false, false, false);
        run(50);
        assertEquals(0, bot.canSeeCalls, "behind and silent, or out of range: no ray is cast");
        steve.x = 5;
        scan();
        assertTrue(bot.canSeeCalls > 0, "in front and in range: the ray decides");
    }

    @Test
    void anEngagedBotKeepsFollowingWhoeverItHasNoticedEvenFromBehindOrSneaking() {
        perceive();
        steve.x = 5;
        scan();
        assertEquals("Steve", controller.engagedWith("Warden7"));
        steve.x = -3;
        steve.subject = sneakingWalk();
        run(400);
        assertEquals("Steve", controller.engagedWith("Warden7"), "awareness uses plain occlusion, not the view cone");
        assertTrue(controller.describe("Warden7").endsWith("unseen 0t"), controller.describe("Warden7"));
        bot.blind.add("Steve");
        run(config.loseSightTicks());
        assertNull(controller.engagedWith("Warden7"), "behind a wall for 10 seconds the chase is given up");
    }

    @Test
    void withPerceptionOffSightIsOmnidirectionalAgainAndSneakingDoesNotMatter() {
        config = config.withPerception(Perception.Params.defaults().disabled());
        perceive();
        steve.x = -9;
        steve.subject = sneakingWalk();
        scan();
        assertEquals(List.of("set Warden7->Steve"), up.callsOf("set"));
        assertTrue(controller.describe("Warden7").startsWith("engaged Steve (acquired by sight) "),
                controller.describe("Warden7"));
    }

    @Test
    void withoutLineOfSightRequiredHearingAndSightAreStillDirectional() {
        config = new Config(true, 10.0, false, 5, 32.0, 200, true, 1.5, 200, 1200);
        perceive();
        steve.x = -2;
        steve.subject = sneakingWalk();
        bot.blind.add("Steve");
        run(100);
        assertEquals(List.of(), up.callsOf("set"), "requireLineOfSight only switches occlusion off, not the cone");
        steve.x = 5;
        scan();
        assertEquals(List.of("set Warden7->Steve"), up.callsOf("set"), "no occlusion check: seen through a wall");
    }

    @Test
    void aViewThatCannotTellEyesAndStanceFallsBackToPlainLineOfSight() {
        // No look direction on either fake: exactly the pre-perception behaviour (omnidirectional).
        steve.x = -9;
        steve.subject = sneakingWalk();
        scan();
        assertEquals(List.of("set Warden7->Steve"), up.callsOf("set"));
    }
}

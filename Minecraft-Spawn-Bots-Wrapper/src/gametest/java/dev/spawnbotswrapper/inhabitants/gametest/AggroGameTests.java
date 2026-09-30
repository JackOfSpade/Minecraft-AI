package dev.spawnbotswrapper.inhabitants.gametest;

import dev.spawnbotswrapper.inhabitants.InhabitantsMod;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.level.block.Blocks;

import java.util.Locale;

/**
 * Real-server tests of the line-of-sight hunter (see {@code AggroController}): a real PvP BOT inhabitant on a real
 * HeroBot fake player, the wrapper loaded, a survival mock player as the "human". The scenes are stone platforms; every
 * time and distance below is a number of the rules: reaction time (5 ticks + a soft distance term), the view cone,
 * hearing, no distance limit, lost -> pursue -> search 10 s -> walk home, noticing again on the way home, a hit from cover.
 * The bot faces east (+x) unless a test turns it.
 */
public final class AggroGameTests {
    private static final String ENV = "pvpbot-inhabitants-gametest:";

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** A sealed 1x1x2 stone cell centred {@code dx, dz} from the bot's cell: nobody can see, or hear, into it. */
    private static void sealedCell(Rig rig, int dx, int dz) {
        rig.fill(dx - 1, 0, dz - 1, dx + 1, 3, dz + 1, Blocks.STONE);
        rig.fill(dx, 0, dz, dx, 1, dz, Blocks.AIR);
    }

    /**
     * The route planner ran (the bot walked AROUND the wall, which a straight walk cannot do) and its detached helper mob
     * never reached the world: no entity with a helper's id exists in the level (natural zombies may, the helper may not),
     * and there is at most one helper per level.
     */
    private static void requireCleanPlanner(Rig rig) {
        long[] stats = InhabitantsMod.aggroPlannerStats();
        int inWorld = 0;
        for (java.util.UUID id : InhabitantsMod.aggroPlannerHelperIds()) {
            if (rig.level.getEntity(id) != null) {
                inWorld++;
            }
        }
        Rig.LOG.info("[planner] {} plans, {} reached their goal, {} helper mob(s) held, {} of them found in the level", stats[0],
                stats[1], stats[2], inWorld);
        if (stats[0] < 1) {
            rig.fail("no route was planned: the walk around the wall cannot have been planned");
        }
        if (inWorld != 0) {
            rig.fail("the planner's helper mob is in the world (" + inWorld + " found by its id)");
        }
        if (stats[2] > 3) {
            rig.fail("the planner keeps " + stats[2] + " helper mobs (one per level is expected)");
        }
    }

    // ------------------------------------------------------------------ (a) reaction time

    /**
     * (a) A player appears in front of the bot (a wall between them is removed): the bot must not have targeted the player
     * before 5 ticks (0.25 s of reaction time), and has by about 7 to 10 (5 + a soft distance term of 1.25 ticks at 8 blocks
     * + the scan interval).
     */
    @GameTest(environment = ENV + "aggro_reaction", maxTicks = 500)
    public void aggroAPlayerAppearingInFrontIsTargetedNotBeforeFiveTicksAndByAboutSeven(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.fill(4, 0, -6, 4, 4, 6, Blocks.STONE); // a wall, one thick
        rig.createTarget(8.0);
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        long[] removedAt = {-1};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.MELEE_ONLY, "reaction")) {
                return;
            }
            long since = context.getTick() - dressedAt[0];
            if (removedAt[0] < 0) {
                if (rig.hasTarget() || !rig.phase().equals("IDLE")) {
                    rig.fail("the bot noticed a player behind a wall: phase " + rig.phase() + " " + rig.trace());
                }
                if (since >= 20) {
                    rig.fill(4, 0, -6, 4, 4, 6, Blocks.AIR); // the player steps into view
                    removedAt[0] = context.getTick();
                    Rig.LOG.info("[reaction] wall removed at test tick {}", removedAt[0]);
                }
                return;
            }
            long n = context.getTick() - removedAt[0];
            if (rig.hasTarget()) {
                Rig.LOG.info("[reaction] targeted {} ticks after the player came into view (phase {})", n, rig.phase());
                if (n < 5) {
                    rig.fail("the bot targeted the player only " + n + " ticks after it came into view: no reaction time");
                }
                if (n > 12) {
                    rig.fail("the bot needed " + n + " ticks to target a player in plain view (expected about 7)");
                }
                rig.succeed();
            } else if (n > 30) {
                rig.fail("the bot never targeted a player in plain view; phase " + rig.phase() + " " + rig.trace());
            }
        });
    }

    // ------------------------------------------------------------------ (b) behind, hearing, sneaking

    /** (b1) A player standing still 3 blocks behind the bot is never noticed: nobody sees behind them, and standing is silent. */
    @GameTest(environment = ENV + "aggro_behind_still", maxTicks = 400)
    public void aggroAPlayerStandingStillThreeBlocksBehindIsNeverNoticed(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(-3.0);
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.MELEE_ONLY, "behind-still")) {
                return;
            }
            long since = context.getTick() - dressedAt[0];
            if (since <= 1) {
                rig.faceDirection(1.0, 0.0); // east: the player at -x is directly behind
            }
            rig.placeTargetAt(rig.homeX() - 3.0, rig.bot.getY(), rig.homeZ());
            if (rig.hasTarget() || !rig.phase().equals("IDLE")) {
                rig.fail("the bot noticed a player standing still behind it after " + since + " ticks: phase " + rig.phase());
            }
            if (since >= 100) {
                rig.succeed();
            }
        });
    }

    /** (b2) A player WALKING 3 blocks behind the bot is heard (footsteps carry 4 blocks): noticed, from behind. */
    @GameTest(environment = ENV + "aggro_behind_walk", maxTicks = 400)
    public void aggroAPlayerWalkingThreeBlocksBehindIsHeard(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(-3.0);
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.MELEE_ONLY, "behind-walk")) {
                return;
            }
            long since = context.getTick() - dressedAt[0];
            if (since <= 1) {
                rig.faceDirection(1.0, 0.0);
            }
            if (rig.hasTarget() || rig.phase().equals("CHASE")) {
                Rig.LOG.info("[behind-walk] noticed after {} ticks of walking behind the bot", since);
                rig.succeed();
                return;
            }
            // pacing between 2.6 and 3.4 blocks behind at 0.1 blocks per tick: a walk, in the bot's back
            double offset = 3.0 + 0.4 * Math.sin(since * 0.25);
            rig.walkTargetTo(rig.homeX() - offset, rig.bot.getY(), rig.homeZ());
            if (since > 120) {
                rig.fail("a player walking 3 blocks behind the bot was never heard; phase " + rig.phase() + " " + rig.trace());
            }
        });
    }

    /**
     * (b3) A player SNEAKING 1.5 blocks behind the bot is never noticed (silent, and behind), until it hits: then the bot is
     * aware of it at once and chases.
     */
    @GameTest(environment = ENV + "aggro_behind_sneak", maxTicks = 600)
    public void aggroAPlayerSneakingBehindIsNeverNoticedUntilItHits(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(-1.5);
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        long[] hitAt = {-1};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.MELEE_ONLY, "behind-sneak")) {
                return;
            }
            long since = context.getTick() - dressedAt[0];
            if (since <= 1) {
                rig.faceDirection(1.0, 0.0);
                rig.setSneaking(true);
            }
            if (hitAt[0] < 0) {
                double offset = 1.5 + 0.2 * Math.sin(since * 0.3);
                rig.walkTargetTo(rig.homeX() - offset, rig.bot.getY(), rig.homeZ());
                if (rig.hasTarget() || !rig.phase().equals("IDLE")) {
                    rig.fail("the bot noticed a player sneaking behind it after " + since + " ticks: phase " + rig.phase());
                }
                if (since >= 120) {
                    if (rig.tryHit(2.0F)) {
                        hitAt[0] = context.getTick();
                        Rig.LOG.info("[behind-sneak] the sneaking player hit the bot at test tick {}", hitAt[0]);
                    } else if (since > 300) {
                        rig.fail("the hit was refused for 300 ticks");
                    }
                }
                return;
            }
            long n = context.getTick() - hitAt[0];
            if (rig.phase().equals("CHASE") && rig.hasTarget()) {
                Rig.LOG.info("[behind-sneak] chasing {} ticks after the hit", n);
                rig.succeed();
            } else if (n > 40) {
                rig.fail("the bot did not react to being hit from behind; phase " + rig.phase() + " " + rig.trace());
            }
        });
    }

    // ------------------------------------------------------------------ (c) long sight

    /**
     * (c) A player 40 blocks away in front with a clear line of sight is noticed (about 12 ticks: 5 + 6.25) and chased: there
     * is no distance limit.
     */
    @GameTest(environment = ENV + "aggro_long_sight", maxTicks = 600)
    public void aggroAPlayerFortyBlocksAwayWithClearSightIsNoticedAndChased(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildStrip(46);
        rig.createTarget(40.0);
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        long[] noticedAt = {-1};
        double[] startDist = {0};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.MELEE_ONLY, "long-sight")) {
                return;
            }
            long since = context.getTick() - dressedAt[0];
            rig.keepTargetAlive();
            if (since == 1) {
                startDist[0] = rig.bot.distanceTo(rig.target);
            }
            if (noticedAt[0] < 0) {
                if (rig.hasTarget()) {
                    noticedAt[0] = since;
                    Rig.LOG.info("[long-sight] noticed the player {} blocks away after {} ticks", fmt(startDist[0]), since);
                    if (since < 9) {
                        rig.fail("noticed a player 40 blocks away after only " + since + " ticks: no reaction time");
                    }
                } else if (since > 80) {
                    rig.fail("a player 40 blocks away in plain sight was never noticed; phase " + rig.phase() + " " + rig.trace());
                }
                return;
            }
            double d = rig.bot.distanceTo(rig.target);
            if (rig.phase().equals("CHASE") && d <= startDist[0] - 8.0) {
                Rig.LOG.info("[long-sight] closed in from {} to {} blocks", fmt(startDist[0]), fmt(d));
                rig.succeed();
            } else if (since - noticedAt[0] > 300) {
                rig.fail("the bot noticed the player but did not chase it; distance " + fmt(d) + " phase " + rig.phase());
            }
        });
    }

    // ------------------------------------------------------------------ (d) lost -> pursue -> search -> return

    /**
     * (d) The player is noticed and then hides in a sealed cell: the bot walks to the last known position (within 2
     * blocks), searches for about 10 s (a window of 190 to 215 ticks), gives up, and walks back to within 1.5 blocks of where
     * it started, without ever moving more than 1.5 blocks in one tick (no teleport).
     */
    @GameTest(environment = ENV + "aggro_search_return", maxTicks = 1800)
    public void aggroALostPlayerIsPursuedSearchedForTenSecondsAndTheBotWalksHome(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform(HUNT_ARENA);
        sealedCell(rig, HUNT_DISTANCE, 8);
        rig.createTarget(HUNT_DISTANCE);
        Hunt hunt = new Hunt(rig, context);
        context.onEachTick(() -> hunt.tick(false));
    }

    /**
     * Where the hunt scenes happen: the player is noticed 20 blocks away, hides in a sealed cell there, and the search (which
     * looks up to 12 blocks around the last known position) therefore always ends at least 8 blocks from where the bot
     * started: the walk home is long enough to be interrupted.
     */
    private static final int HUNT_DISTANCE = 20;
    private static final int HUNT_ARENA = 34;

    /**
     * (e) While the bot walks home the player steps into view again: it chases again; the player hides once more, and the bot
     * still walks back to its FIRST start point (not to where it stood when it noticed the player the second time).
     */
    @GameTest(environment = ENV + "aggro_resight_return", maxTicks = 3000)
    public void aggroSeeingThePlayerAgainOnTheWayHomeRestartsTheHuntButHomeStaysTheFirstStartPoint(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform(HUNT_ARENA);
        sealedCell(rig, HUNT_DISTANCE, 8);
        rig.createTarget(HUNT_DISTANCE);
        Hunt hunt = new Hunt(rig, context);
        context.onEachTick(() -> hunt.tick(true));
    }

    /** The shared script of (d) and (e): notice, hide, watch the phases, optionally show up again while returning. */
    private static final class Hunt {
        final Rig rig;
        final GameTestHelper context;
        boolean dressed;
        long[] dressedAt = {0};
        boolean[] dressedFlag = {false};
        int stage;
        long chaseSince = -1;
        long hiddenAt = -1;
        double lkpX;
        double lkpZ;
        double minLkpDistance = Double.MAX_VALUE;
        String lastPhase = "";
        long searchStart = -1;
        long searchEnd = -1;
        long returnStart = -1;
        int searches;
        boolean sawPursue;
        boolean sawReturn;
        boolean reappeared;
        boolean rechased;
        double[] home;
        double lastX;
        double lastZ;
        boolean haveLast;
        double maxStep;
        long reappearedAt = -1;

        Hunt(Rig rig, GameTestHelper context) {
            this.rig = rig;
            this.context = context;
        }

        void tick(boolean resight) {
            if (!rig.awaitDressed(dressedFlag, dressedAt, Rig.Loadout.MELEE_ONLY, "hunt")) {
                return;
            }
            long since = context.getTick() - dressedAt[0];
            String phase = rig.phase();
            // every tick: how far did the bot move? (a teleport would show as a jump)
            if (haveLast) {
                maxStep = Math.max(maxStep, Math.hypot(rig.bot.getX() - lastX, rig.bot.getZ() - lastZ));
            }
            lastX = rig.bot.getX();
            lastZ = rig.bot.getZ();
            haveLast = true;
            if (!phase.equals(lastPhase)) {
                Rig.LOG.info("[hunt] test tick {}: {} -> {} at ({}, {}) {} from the start, {} from the last known position | {}",
                        context.getTick(), lastPhase.isEmpty() ? "-" : lastPhase, phase, fmt(rig.bot.getX() - rig.homeX()),
                        fmt(rig.bot.getZ() - rig.homeZ()), fmt(rig.horizontalTo(rig.homeX(), rig.homeZ())),
                        hiddenAt < 0 ? "?" : fmt(rig.horizontalTo(lkpX, lkpZ)), InhabitantsMod.aggroDescribe(rig.botName));
                if (phase.equals("SEARCH")) {
                    searchStart = context.getTick();
                    searches++;
                }
                if (lastPhase.equals("SEARCH")) {
                    searchEnd = context.getTick();
                    long length = searchEnd - searchStart;
                    if (length < 190 || length > 215) {
                        rig.fail("the search lasted " + length + " ticks, not about 200 (10 s)");
                    }
                }
                if (phase.equals("PURSUE")) {
                    sawPursue = true;
                }
                if (phase.equals("RETURN")) {
                    sawReturn = true;
                    returnStart = context.getTick();
                }
                lastPhase = phase;
            }
            switch (stage) {
                case 0 -> { // wait until the bot has noticed the player and chases it
                    if (home == null && rig.bot != null) {
                        home = new double[]{rig.homeX(), rig.homeZ()};
                    }
                    if (phase.equals("CHASE")) {
                        if (chaseSince < 0) {
                            chaseSince = context.getTick();
                        }
                        if (context.getTick() - chaseSince >= 3) {
                            lkpX = rig.target.getX();
                            lkpZ = rig.target.getZ();
                            hide();
                            stage = 1;
                        }
                    } else if (since > 100) {
                        rig.fail("the bot never noticed the player in plain view; phase " + phase + " " + rig.trace());
                    }
                }
                case 1 -> watch(phase, since, resight);
                default -> {
                }
            }
        }

        /** The player vanishes into the sealed cell: nobody sees or hears anything in there. */
        void hide() {
            rig.placeTargetAt(rig.homeX() + HUNT_DISTANCE, rig.bot.getY(), rig.homeZ() + 8.0);
            hiddenAt = context.getTick();
            Rig.LOG.info("[hunt] the player hid at test tick {}; last seen at ({}, {}) relative to the start", hiddenAt,
                    fmt(lkpX - rig.homeX()), fmt(lkpZ - rig.homeZ()));
        }

        void watch(String phase, long since, boolean resight) {
            if ((phase.equals("PURSUE") || phase.equals("SEARCH")) && rechased == reappeared) {
                minLkpDistance = Math.min(minLkpDistance, rig.horizontalTo(lkpX, lkpZ));
            }
            if (hiddenAt >= 0 && context.getTick() - hiddenAt > 1500) {
                rig.fail("the hunt did not end: phase " + phase + " " + rig.trace());
            }
            double toHome = rig.horizontalTo(rig.homeX(), rig.homeZ());
            if (resight && !reappeared && phase.equals("RETURN") && toHome > 6.0) {
                // the bot walks toward home: the player steps into view 8 blocks ahead of it, on its way
                double ux = (rig.homeX() - rig.bot.getX()) / toHome;
                double uz = (rig.homeZ() - rig.bot.getZ()) / toHome;
                rig.placeTargetAt(rig.bot.getX() + ux * 8.0, rig.bot.getY(), rig.bot.getZ() + uz * 8.0);
                reappeared = true;
                reappearedAt = context.getTick();
                lastPhase = "?";
                Rig.LOG.info("[hunt] the player stepped into view at test tick {}, 8 blocks ahead of the bot, {} from home", reappearedAt,
                        fmt(toHome));
                return;
            }
            if (reappeared && !rechased) {
                if (phase.equals("CHASE")) {
                    rechased = true;
                    double[] h = InhabitantsMod.aggroHomeOf(rig.botName);
                    if (h == null || Math.hypot(h[0] - home[0], h[2] - home[1]) > 0.05) {
                        rig.fail("the home anchor changed when the bot noticed the player again: "
                                + (h == null ? "none" : fmt(h[0] - home[0]) + ", " + fmt(h[2] - home[1])));
                    }
                    Rig.LOG.info("[hunt] chasing again {} ticks after the player showed up", context.getTick() - reappearedAt);
                    lkpX = rig.target.getX();
                    lkpZ = rig.target.getZ();
                    minLkpDistance = Double.MAX_VALUE;
                    chaseSince = context.getTick();
                } else if (context.getTick() - reappearedAt > 60) {
                    rig.fail("the bot did not notice the player stepping into view while walking home; phase " + phase);
                }
                return;
            }
            if (rechased && chaseSince > 0 && phase.equals("CHASE") && context.getTick() - chaseSince >= 3 && hiddenAt < reappearedAt) {
                lkpX = rig.target.getX();
                lkpZ = rig.target.getZ();
                hide();
                chaseSince = -1;
                return;
            }
            if (phase.equals("IDLE") && sawReturn && (!resight || (rechased && hiddenAt > reappearedAt))) {
                finish(resight);
            }
        }

        void finish(boolean resight) {
            double toHome = rig.horizontalTo(rig.homeX(), rig.homeZ());
            Rig.LOG.info("[hunt] over at test tick {}: {} from the first start point, closest approach to the last known position {}, "
                            + "searches {}, largest step {} blocks in one tick", context.getTick(), fmt(toHome), fmt(minLkpDistance),
                    searches, fmt(maxStep));
            if (!sawPursue) {
                rig.fail("the bot never pursued the last known position");
            }
            if (minLkpDistance > 2.0) {
                rig.fail("the bot got no closer than " + fmt(minLkpDistance) + " blocks to the last known position (needs 2)");
            }
            if (searches < (resight ? 2 : 1)) {
                rig.fail("the bot searched " + searches + " time(s)");
            }
            requireCleanPlanner(rig);
            if (toHome > 1.55) {
                rig.fail("the bot stopped " + fmt(toHome) + " blocks from its first start point (needs 1.5)");
            }
            if (maxStep >= 1.5) {
                rig.fail("the bot moved " + fmt(maxStep) + " blocks in one tick: a teleport");
            }
            if (InhabitantsMod.aggroHomeOf(rig.botName) != null) {
                rig.fail("the home anchor was not cleared on arrival");
            }
            rig.succeed();
        }
    }

    // ------------------------------------------------------------------ (f) hit from cover

    /**
     * (f) An arrow from a player the bot cannot see (a wall between them): the bot is aware of the shooter, does not chase it
     * through the wall, walks a planned route round the wall to where the shot came from, and gets within melee range (3.6
     * blocks) of it, or is already fighting the shooter it found there.
     */
    @GameTest(environment = ENV + "aggro_hit_from_cover", maxTicks = 900)
    public void aggroAnArrowFromAPlayerOutOfSightSendsTheBotToTheShootersPosition(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.fill(3, 0, -4, 3, 4, 4, Blocks.STONE); // a wall 9 wide
        rig.createTarget(7.0);
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        long[] hitAt = {-1};
        double[] shooter = new double[2];
        double[] closest = {Double.MAX_VALUE};
        double[] detour = {0.0};
        String[] phaseBefore = {""};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.MELEE_ONLY, "cover")) {
                return;
            }
            long since = context.getTick() - dressedAt[0];
            rig.keepTargetAlive();
            if (hitAt[0] < 0) {
                if (rig.hasTarget() || !rig.phase().equals("IDLE")) {
                    rig.fail("the bot noticed a player behind a wall: phase " + rig.phase() + " " + rig.trace());
                }
                if (since >= 20) {
                    Arrow arrow = EntityType.ARROW.create(rig.level, EntitySpawnReason.COMMAND);
                    arrow.setOwner(rig.target);
                    arrow.setPos(rig.target.getX(), rig.target.getEyeY(), rig.target.getZ());
                    DamageSource source = rig.level.damageSources().arrow(arrow, rig.target);
                    if (!rig.bot.connection.hasClientLoaded()) {
                        rig.bot.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
                    }
                    if (rig.bot.hurtServer(rig.level, source, 3.0F)) {
                        hitAt[0] = context.getTick();
                        shooter[0] = rig.target.getX();
                        shooter[1] = rig.target.getZ();
                        Rig.LOG.info("[cover] the bot was shot at test tick {} from ({}, {}) relative to its cell", hitAt[0],
                                fmt(shooter[0] - rig.homeX()), fmt(shooter[1] - rig.homeZ()));
                    } else if (since > 200) {
                        rig.fail("the arrow hit was refused for 200 ticks");
                    }
                }
                return;
            }
            long n = context.getTick() - hitAt[0];
            String phase = rig.phase();
            if (!phase.equals(phaseBefore[0])) {
                Rig.LOG.info("[cover] {} ticks after the hit: phase {} at ({}, {})", n, phase, fmt(rig.bot.getX() - rig.homeX()),
                        fmt(rig.bot.getZ() - rig.homeZ()));
                phaseBefore[0] = phase;
            }
            if (n >= 1 && n <= 8 && rig.hasTarget()) {
                rig.fail("the bot targeted a shooter it cannot see, " + n + " ticks after the hit (PvP BOT's revenge must be held back)");
            }
            if (n >= 3 && n <= 8 && !phase.equals("PURSUE")) {
                rig.fail("the bot is in phase " + phase + " " + n + " ticks after a hit from cover; it must pursue the shooter's position");
            }
            closest[0] = Math.min(closest[0], rig.horizontalTo(shooter[0], shooter[1]));
            detour[0] = Math.max(detour[0], Math.abs(rig.bot.getZ() - rig.homeZ()));
            // Arrived: within melee range of where the shot came from (PvP BOT's melee range is 3.5 and it stops there to fight),
            // or already fighting the shooter after walking round the wall to find it.
            if (closest[0] <= 3.6 || (phase.equals("CHASE") && detour[0] >= 4.0)) {
                Rig.LOG.info("[cover] reached within {} blocks of the shooter's position {} ticks after the hit (phase {}); the detour "
                        + "reached {} blocks to the side (the wall ends at 4.5)", fmt(closest[0]), n, phase, fmt(detour[0]));
                if (detour[0] < 4.0) {
                    rig.fail("the bot got to the shooter without walking around the wall (largest sideways distance "
                            + fmt(detour[0]) + "): it did not walk a planned route");
                }
                requireCleanPlanner(rig);
                rig.succeed();
            } else if (n > 400) {
                rig.fail("the bot got no closer than " + fmt(closest[0]) + " blocks to the shooter's position; phase " + phase);
            }
        });
    }
}

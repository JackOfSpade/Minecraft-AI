package dev.spawnbotswrapper.inhabitants.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Smoke tests of the wrapper's aggro controller on a real server: WHO an inhabitant attacks now that PvP BOT's own
 * auto-target is managed off and the {@code AggroController} decides by the {@code Perception} model (view cone, hearing,
 * sneaking), and how it gives up and walks home. The assertions read PvP BOT's own current target of the bot
 * ({@link Upstream#target}) and positions; the bot's facing is pinned (east, yaw -90) until it has a target.
 */
public final class AggroSmokeGameTests {
    private static final String ENV = "pvpbot-inhabitants-gametest:";
    /** Yaw of a body looking toward +x (east). */
    private static final float EAST = -90.0F;

    /** The shared per-test state and steps. */
    private static final class Scene {
        final Rig rig;
        final GameTestHelper ctx;
        final LogCapture capture = new LogCapture();
        final boolean[] dressed = {false};
        final long[] dressedAt = {0};
        boolean placed;
        long placedAt;
        /** Where the bot stood when the scene began (its home). */
        Vec3 home;

        Scene(GameTestHelper ctx) {
            this.ctx = ctx;
            this.rig = new Rig(ctx);
        }

        /** Platform and a survival player parked out of every noticing range (beyond the 10 block sight range). */
        void build(int radius) {
            rig.buildPlatform(radius);
            rig.createTarget(11.5);
        }

        /** True once the bot is dressed (melee only, so nothing shoots) and the scene is placed; places it on the first call. */
        boolean ready(double dx, boolean sneaking) {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.MELEE_ONLY, "aggro")) {
                return false;
            }
            if (!placed) {
                placed = true;
                placedAt = ctx.getTick();
                home = rig.bot.position();
                faceEast();
                Vec3 spot = home.add(dx, 0, 0);
                rig.target.teleportTo(rig.level, spot.x, spot.y, spot.z, Set.of(), dx > 0 ? 90.0F : -90.0F, 0.0F, true);
                rig.target.setShiftKeyDown(sneaking);
                Rig.LOG.info("[aggro] scene placed at tick {}: bot at {} facing east, player at {} sneaking={}", placedAt,
                        fmt(home), fmt(spot), sneaking);
            }
            return true;
        }

        void faceEast() {
            rig.bot.setYRot(EAST);
            rig.bot.setYHeadRot(EAST);
            rig.bot.setXRot(0.0F);
            rig.bot.yRotO = EAST;
            rig.bot.xRotO = 0.0F;
        }

        boolean hasTarget() {
            String t = Upstream.target(rig.botName);
            if (t.startsWith("?")) {
                rig.fail("PvP BOT's target of the bot cannot be read: " + t);
            }
            return !t.equals("none");
        }

        String status() {
            return "t=" + rig.level.getGameTime() + " botPos=" + fmt(rig.bot.position()) + " yaw=" + rig.bot.getYRot()
                    + " player=" + fmt(rig.target.position()) + " dist="
                    + String.format(Locale.ROOT, "%.2f", rig.bot.distanceTo(rig.target)) + " target="
                    + Upstream.target(rig.botName);
        }

        void end() {
            capture.close();
        }
    }

    private static String fmt(Vec3 v) {
        return String.format(Locale.ROOT, "(%.2f,%.2f,%.2f)", v.x, v.y, v.z);
    }

    /** A survival player standing still 6 blocks in front of the inhabitant, in clear view: it is noticed by sight. */
    @GameTest(environment = ENV + "aggro_front_acquire", maxTicks = 700)
    public void playerStandingSixBlocksInFrontIsTargeted(GameTestHelper context) {
        Scene s = new Scene(context);
        s.build(12);
        context.onEachTick(() -> {
            if (!s.ready(6.0, false)) {
                return;
            }
            long since = context.getTick() - s.placedAt;
            if (!s.hasTarget()) {
                s.faceEast();
            }
            if (since % 10 == 0) {
                Rig.LOG.info("[front] {}", s.status());
            }
            if (s.hasTarget()) {
                List<String> lines = s.capture.containing("noticed");
                Rig.LOG.info("[front] targeted after {} ticks; log: {}", since, lines);
                s.end();
                if (lines.stream().noneMatch(l -> l.contains(s.rig.botName) && l.contains("by sight"))) {
                    s.rig.fail("targeted, but the aggro log has no 'noticed ... by sight' line for the bot: " + lines);
                }
                s.rig.succeed();
            } else if (since > 60) {
                s.end();
                s.rig.fail("a player 6 blocks in front in clear view was not targeted within 60 ticks; " + s.status());
            }
        });
    }

    /**
     * A sneaking, motionless player 3 blocks behind is not noticed (sneaking is silent, and nothing behind is seen). As
     * a control the same player then stands up and walks: if that is not noticed either, the "no target" above proved
     * nothing.
     */
    @GameTest(environment = ENV + "aggro_sneak_behind", maxTicks = 900)
    public void sneakingPlayerThreeBlocksBehindIsNotNoticed(GameTestHelper context) {
        Scene s = new Scene(context);
        s.build(12);
        double[] z = {0.0};
        double[] step = {0.15};
        context.onEachTick(() -> {
            if (!s.ready(-3.0, true)) {
                return;
            }
            long since = context.getTick() - s.placedAt;
            if (since < 100) {
                s.faceEast();
                s.rig.target.setShiftKeyDown(true);
                if (since % 20 == 0) {
                    Rig.LOG.info("[sneak] {}", s.status());
                }
                if (s.hasTarget()) {
                    s.end();
                    s.rig.fail("a sneaking, motionless player 3 blocks behind was targeted after " + since + " ticks; " + s.status());
                }
                return;
            }
            if (since == 100) {
                Rig.LOG.info("[sneak] no target for 100 ticks; control: the player stands up and walks");
                s.rig.target.setShiftKeyDown(false);
            }
            if (!s.hasTarget()) {
                s.faceEast();
                walkBack(s, z, step, -3.0);
            }
            if (s.hasTarget()) {
                Rig.LOG.info("[sneak] control: targeted {} ticks after the player started walking", since - 100);
                s.end();
                s.rig.succeed();
            } else if (since > 100 + 80) {
                s.end();
                s.rig.fail("control failed: the same player walking 3 blocks behind was not noticed within 80 ticks either; "
                        + s.status());
            }
        });
    }

    /** Moves the player one step (0.15 blocks) sideways every tick, bouncing between -0.75 and +0.75, at {@code dx} behind the bot. */
    private static void walkBack(Scene s, double[] z, double[] step, double dx) {
        z[0] += step[0];
        if (Math.abs(z[0]) >= 0.75) {
            step[0] = -step[0];
        }
        Vec3 at = s.home.add(dx, 0, z[0]);
        s.rig.target.setOldPosAndRot();
        s.rig.target.setPos(at.x, at.y, at.z);
    }

    /** A player walking (not sneaking) 3 blocks behind is heard. */
    @GameTest(environment = ENV + "aggro_walk_behind", maxTicks = 700)
    public void walkingPlayerThreeBlocksBehindIsHeard(GameTestHelper context) {
        Scene s = new Scene(context);
        s.build(12);
        double[] z = {0.0};
        double[] step = {0.15};
        context.onEachTick(() -> {
            if (!s.ready(-3.0, false)) {
                return;
            }
            long since = context.getTick() - s.placedAt;
            if (!s.hasTarget()) {
                s.faceEast();
                walkBack(s, z, step, -3.0);
            }
            if (since % 10 == 0) {
                Rig.LOG.info("[walk] {} xo={} x={}", s.status(), s.rig.target.xo, s.rig.target.getX());
            }
            if (s.hasTarget()) {
                List<String> lines = s.capture.containing("noticed");
                Rig.LOG.info("[walk] targeted after {} ticks; log: {}", since, lines);
                s.end();
                if (lines.stream().noneMatch(l -> l.contains(s.rig.botName) && l.contains("by hearing"))) {
                    s.rig.fail("targeted, but not 'by hearing' (the model says a walker behind is heard): " + lines);
                }
                s.rig.succeed();
            } else if (since > 60) {
                s.end();
                s.rig.fail("a walking player 3 blocks behind was not noticed within 60 ticks; " + s.status());
            }
        });
    }

    /**
     * After it noticed a player, the player is walled in (fully enclosed, out of sight): within about 260 ticks the
     * inhabitant gives up (target cleared) and walks back to where it stood, without a single jump of position.
     */
    @GameTest(environment = ENV + "aggro_give_up_home", maxTicks = 1400)
    public void inhabitantGivesUpOnAWalledInPlayerAndWalksHome(GameTestHelper context) {
        Scene s = new Scene(context);
        s.build(14);
        long[] acquiredAt = {-1};
        long[] clearedAt = {-1};
        Vec3[] last = {null};
        double[] maxStep = {0.0};
        double[] maxAway = {0.0};
        context.onEachTick(() -> {
            if (!s.ready(6.0, false)) {
                return;
            }
            long now = context.getTick();
            long since = now - s.placedAt;
            Vec3 pos = s.rig.bot.position();
            if (acquiredAt[0] < 0) {
                s.faceEast();
                if (s.hasTarget()) {
                    acquiredAt[0] = now;
                    last[0] = pos;
                    Rig.LOG.info("[home] acquired after {} ticks; walling the player in. {}", since, s.status());
                    enclose(s);
                } else if (since > 60) {
                    s.end();
                    s.rig.fail("precondition: the player 6 blocks in front was not targeted within 60 ticks; " + s.status());
                }
                return;
            }
            double step = pos.distanceTo(last[0]);
            maxStep[0] = Math.max(maxStep[0], step);
            maxAway[0] = Math.max(maxAway[0], horizontal(pos, s.home));
            if (step > 1.5) {
                s.end();
                s.rig.fail("the inhabitant moved " + step + " blocks in one tick (a teleport?): " + fmt(last[0]) + " -> " + fmt(pos));
            }
            last[0] = pos;
            long sinceAcq = now - acquiredAt[0];
            if (sinceAcq % 20 == 0) {
                Rig.LOG.info("[home] +{} {} homeDist={}", sinceAcq, s.status(),
                        String.format(Locale.ROOT, "%.2f", horizontal(pos, s.home)));
            }
            if (clearedAt[0] < 0) {
                if (!s.hasTarget()) {
                    clearedAt[0] = now;
                    Rig.LOG.info("[home] target cleared {} ticks after the wall went up", sinceAcq);
                    if (sinceAcq > 280) {
                        s.end();
                        s.rig.fail("gave up only after " + sinceAcq + " ticks (expected about 200 to 260)");
                    }
                } else if (sinceAcq > 300) {
                    s.end();
                    s.rig.fail("the inhabitant still had its target " + sinceAcq + " ticks after the player was walled in; " + s.status());
                }
                return;
            }
            double home = horizontal(pos, s.home);
            if (home <= 1.5) {
                List<String> lines = s.capture.containing("gives up");
                Rig.LOG.info("[home] back within {} of home {} ticks after clearing; max per-tick step {}; farthest {}; log {}",
                        String.format(Locale.ROOT, "%.2f", home), now - clearedAt[0], maxStep[0], maxAway[0], lines);
                s.end();
                if (lines.stream().noneMatch(l -> l.contains(s.rig.botName) && l.contains("out of sight"))) {
                    s.rig.fail("no 'gives up ... out of sight' line for the bot in the log: " + lines);
                }
                s.rig.succeed();
            } else if (now - clearedAt[0] > 400) {
                s.end();
                s.rig.fail("target cleared but the inhabitant did not walk back within 400 ticks: home distance " + home
                        + " (farthest " + maxAway[0] + "); " + s.status());
            }
        });
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    /** A solid stone block three cells thick around the player's cell (only its feet and head cell stay air): no line of sight reaches it, and a sword cannot reach it from outside (PvP BOT melee has no line-of-sight check: a thin wall lets the bot kill the player through it, which ends the chase for another reason than the one under test). */
    private static void enclose(Scene s) {
        BlockPos feet = BlockPos.containing(s.rig.target.position());
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 5; dy++) {
                    boolean inside = dx == 0 && dz == 0 && (dy == 0 || dy == 1);
                    s.rig.level.setBlock(feet.offset(dx, dy, dz),
                            inside ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }
}

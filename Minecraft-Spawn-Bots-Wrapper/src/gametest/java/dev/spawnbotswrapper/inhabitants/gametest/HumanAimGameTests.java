package dev.spawnbotswrapper.inhabitants.gametest;

import dev.spawnbotswrapper.inhabitants.InhabitantsMod;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Real-server tests of HUMAN AIM (see {@code HumanAim}): PvP BOT snaps an inhabitant's rotation onto its target every tick,
 * which is instant and perfect. The wrapper turns a tracked aim toward what PvP BOT wants at a human speed (27 degrees per
 * tick at most, the shorter way round) and uses the tracked aim for the view cone, for crossbow shots, for the arrows PvP BOT
 * releases and for melee blows:
 * <ul>
 *   <li>a player shoots a crossbow bot in the back from 10 blocks: the bot's look changes by at most 27 degrees per tick,
 *       the first return shot comes no earlier than the turn plus the reaction time, and the bolt leaves along the aim;</li>
 *   <li>the same turn, seen as yaw samples: a smooth ramp of full steps, not a jump;</li>
 *   <li>a sword bot facing away from a player standing behind it cannot hit until it has turned (and one facing the player
 *       hits at once, the control);</li>
 *   <li>arrows leave along the tracked aim: a deterministic release (the rotation PvP BOT just snapped versus the aim) and a
 *       real PvP BOT release with the target teleported to the other side a tick before the release.</li>
 * </ul>
 */
public final class HumanAimGameTests {
    private static final String ENV = "pvpbot-inhabitants-gametest:";
    /** The most the aim may change in one tick: 540 degrees per second. */
    private static final double MAX_STEP = 27.0;
    private static final double EPS = 0.05;

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static double angleDeg(Vec3 a, Vec3 b) {
        double dot = a.dot(b) / Math.max(1.0e-12, a.length() * b.length());
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
    }

    private static double wrap(double d) {
        double x = d % 360.0;
        return x >= 180.0 ? x - 360.0 : x < -180.0 ? x + 360.0 : x;
    }

    /** The direction of a tracked aim {yaw, pitch, ...}. */
    private static Vec3 dirOf(double[] aim) {
        double pitch = Math.toRadians(aim[1]);
        double yaw = Math.toRadians(-aim[0]);
        return new Vec3(Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
    }

    /** New arrows owned by {@code bot} near it that this test has not seen yet, oldest first. */
    private static List<AbstractArrow> freshArrows(Rig rig, Set<UUID> seen) {
        List<AbstractArrow> found = new ArrayList<>();
        AABB area = rig.bot.getBoundingBox().inflate(12.0);
        for (AbstractArrow a : rig.level.getEntitiesOfClass(AbstractArrow.class, area, x -> x.getOwner() == rig.bot)) {
            if (seen.add(a.getUUID())) {
                found.add(a);
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ (a) shot in the back

    /**
     * (a) A player shoots a crossbow bot in the back from 10 blocks. The bot's look never changes by more than 27 degrees in a
     * tick (a fast human flick, 540 degrees per second), it needs about a third of a second to turn round, and its first shot
     * at the player comes no earlier than the tick the player came into its view cone plus the reaction time (0.5 + 1.5 * 10 /
     * 64 = 0.73 s, 15 ticks). The bolt leaves along where the bot really looks (vanilla's shot vector is the view vector).
     */
    @GameTest(environment = ENV + "human_aim_back_shot", maxTicks = 700)
    public void humanAimABotShotInTheBackTurnsAtHumanSpeedAndFiresOnlyAfterTheReactionTime(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(10.0);
        rig.hybridCrossbow = true;
        rig.hybridLoaded = true;
        rig.hybridArrows = 32;
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        long[] hitAt = {-1};
        long[] hitGame = {0};
        Vec3[] last = {null};
        double[] maxStep = {0.0};
        double[] turned = {0.0};
        long[] coneAt = {-1};
        long[] confirmedAt = {-1};
        Set<UUID> seen = new HashSet<>();
        double[] boltError = {-1.0};
        List<String> yaws = new ArrayList<>();
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.HYBRID, "back-shot")) {
                return;
            }
            long since = context.getTick() - dressedAt[0];
            rig.keepTargetAlive();
            if (hitAt[0] < 0) {
                rig.faceDirection(-1.0, 0.0); // facing west, away from the player 10 blocks east: its back is turned
                if (rig.hasTarget() || !rig.phase().equals("IDLE") || !HarnessMod.shotsBy(rig.bot.getUUID()).isEmpty()) {
                    rig.fail("the bot noticed or shot a player standing behind it: phase " + rig.phase() + " " + rig.trace());
                }
                if (since >= 25) {
                    Arrow arrow = EntityType.ARROW.create(rig.level, EntitySpawnReason.COMMAND);
                    arrow.setOwner(rig.target);
                    arrow.setPos(rig.target.getX(), rig.target.getEyeY(), rig.target.getZ());
                    arrow.setDeltaMovement(-1.5, 0.0, 0.0); // flying west, into the bot's back
                    DamageSource source = rig.level.damageSources().arrow(arrow, rig.target);
                    if (!rig.bot.connection.hasClientLoaded()) {
                        rig.bot.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
                    }
                    if (rig.bot.hurtServer(rig.level, source, 1.0F)) {
                        hitAt[0] = context.getTick();
                        hitGame[0] = rig.level.getGameTime();
                        last[0] = rig.bot.getViewVector(1.0F);
                        Rig.LOG.info("[back-shot] the bot was shot in the back at game tick {}; it looks west (yaw {})", hitGame[0],
                                fmt(rig.bot.getYRot()));
                    } else if (since > 200) {
                        rig.fail("the arrow hit was refused for 200 ticks");
                    }
                }
                return;
            }
            long n = context.getTick() - hitAt[0];
            Vec3 look = rig.bot.getViewVector(1.0F);
            double step = angleDeg(last[0], look);
            maxStep[0] = Math.max(maxStep[0], step);
            turned[0] += step;
            last[0] = look;
            if (n <= 14) {
                yaws.add(fmt(rig.bot.getYRot()));
            }
            Vec3 toPlayer = rig.target.getEyePosition().subtract(rig.bot.getEyePosition());
            if (coneAt[0] < 0 && angleDeg(look, toPlayer) <= 100.0) {
                coneAt[0] = n;
            }
            if (confirmedAt[0] < 0 && InhabitantsMod.aggroConfirmedTarget(rig.botName) != null) {
                confirmedAt[0] = n;
            }
            if (step > MAX_STEP + EPS) {
                rig.fail("the bot's look changed by " + fmt(step) + " degrees in one tick (the human limit is " + MAX_STEP
                        + "); yaw samples " + yaws);
            }
            for (AbstractArrow bolt : freshArrows(rig, seen)) {
                double[] aim = InhabitantsMod.aimOf(rig.botName);
                if (boltError[0] < 0 && aim != null) {
                    boltError[0] = angleDeg(bolt.getDeltaMovement(), dirOf(aim));
                }
            }
            List<HarnessMod.Shot> shots = HarnessMod.shotsBy(rig.bot.getUUID());
            if (shots.isEmpty()) {
                if (n > 200) {
                    rig.fail("the bot never fired at the player after being shot; phase " + rig.phase() + " " + rig.trace());
                }
                return;
            }
            long first = shots.get(0).tick() - hitGame[0];
            Rig.LOG.info("[back-shot] yaw samples after the hit {}; turned {} degrees in total, max step {}; player in the cone {} ticks "
                            + "after the hit, engagement confirmed {} ticks after, FIRST SHOT {} ticks after the hit ({} s); bolt {} degrees off the aim",
                    yaws, fmt(turned[0]), fmt(maxStep[0]), coneAt[0], confirmedAt[0], first, fmt(first * 0.05), fmt(boltError[0]));
            if (turned[0] < 150.0) {
                rig.fail("the bot turned only " + fmt(turned[0]) + " degrees toward the shot: the scene is wrong");
            }
            if (coneAt[0] < 3) {
                rig.fail("the player was in the bot's view cone " + coneAt[0] + " ticks after the shot: the bot did not turn at human speed");
            }
            if (first < coneAt[0] + 14) {
                rig.fail("the first shot came " + first + " ticks after the hit, only " + (first - coneAt[0]) + " after the player came "
                        + "into view: the turn plus the 15 tick reaction time was not served");
            }
            if (boltError[0] >= 0 && boltError[0] > 12.0) {
                rig.fail("the bolt left " + fmt(boltError[0]) + " degrees off the bot's aim: it must follow the aim plus a few degrees of jitter");
            }
            rig.succeed();
        });
    }

    // ------------------------------------------------------------------ (b) the turn is a ramp

    /**
     * (b) The bot visibly turns: PvP BOT is given a target on the other side (it snaps its rotation onto it at once), and the
     * yaw samples of the following ticks are a ramp of full 27 degree steps in one direction, not a jump.
     */
    @GameTest(environment = ENV + "human_aim_ramp", maxTicks = 500)
    public void humanAimAHalfTurnIsASmoothRampOfFullStepsNotAJump(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(10.0);
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        long[] startAt = {-1};
        List<Double> samples = new ArrayList<>();
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.MELEE_ONLY, "ramp")) {
                return;
            }
            long since = context.getTick() - dressedAt[0];
            rig.keepTargetAlive();
            if (startAt[0] < 0) {
                rig.faceDirection(-1.0, 0.0); // facing west; the player is 10 blocks east
                if (since >= 10) {
                    startAt[0] = context.getTick();
                    samples.add((double) rig.bot.getYRot());
                    rig.forceTarget(); // PvP BOT now wants to look east and snaps there at once
                }
                return;
            }
            if (!rig.hasTarget()) {
                rig.forceTarget();
            }
            samples.add((double) rig.bot.getYRot());
            long n = context.getTick() - startAt[0];
            if (n < 12) {
                return;
            }
            List<String> text = new ArrayList<>();
            int full = 0;
            double sign = 0.0;
            for (int i = 1; i < samples.size(); i++) {
                double d = wrap(samples.get(i) - samples.get(i - 1));
                text.add(fmt(d));
                if (Math.abs(d) > MAX_STEP + EPS) {
                    rig.fail("the yaw jumped " + fmt(d) + " degrees in one tick: samples of the change per tick " + text);
                }
                if (Math.abs(d) >= 20.0) {
                    full++;
                }
                if (Math.abs(d) > 0.5) {
                    if (sign != 0.0 && Math.signum(d) != sign && i <= 8) {
                        rig.fail("the turn changed direction: " + text);
                    }
                    if (sign == 0.0) {
                        sign = Math.signum(d);
                    }
                }
            }
            double finalYaw = samples.get(samples.size() - 1);
            Rig.LOG.info("[ramp] yaw change per tick after the order to look the other way: {}; final yaw {}", text, fmt(finalYaw));
            if (full < 5) {
                rig.fail("only " + full + " ticks turned by 20 degrees or more: not a ramp of full steps: " + text);
            }
            if (Math.abs(wrap(finalYaw + 90.0)) > 25.0) {
                rig.fail("the bot did not end up looking at the player (east, yaw -90): " + fmt(finalYaw));
            }
            rig.succeed();
        });
    }

    // ------------------------------------------------------------------ (c) melee

    /**
     * One swing the way PvP BOT makes it: the rotation snapped onto the victim (its {@code lookAtTarget}), then a vanilla
     * {@code attack}. What the wrapper adds is the tracked aim: the blow lands only when the victim is under the crosshair of
     * where the bot really looks, so the rotation set here is only what PvP BOT WANTS.
     *
     * @return true when the victim lost health to it
     */
    private static boolean swing(Rig rig) {
        Vec3 to = rig.target.getBoundingBox().getCenter().subtract(rig.bot.getEyePosition());
        double horizontal = Math.hypot(to.x, to.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(to.y, horizontal));
        rig.bot.setYRot(yaw);
        rig.bot.setYHeadRot(yaw);
        rig.bot.setXRot(pitch);
        float before = rig.target.getHealth();
        rig.bot.attack(rig.target);
        return rig.target.getHealth() < before;
    }

    /**
     * (c) A sword bot faces away from a player standing 1.5 blocks behind it and swings at it every tick, each time with the
     * rotation snapped onto the player like PvP BOT's own melee does. The blow lands only once the tracked aim has turned round
     * (27 degrees a tick, so about 6 ticks): no damage before that, every swing before it vetoed and counted.
     */
    @GameTest(environment = ENV + "human_aim_melee_behind", maxTicks = 500)
    public void humanAimASwordBotFacingAwayCannotHitAPlayerBehindItUntilItHasTurned(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(-1.5);
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        long[] startAt = {-1};
        long[] vetoesAtStart = {0};
        List<String> offAim = new ArrayList<>();
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.MELEE_ONLY, "melee-behind")) {
                return;
            }
            long since = context.getTick() - dressedAt[0];
            if (startAt[0] < 0) {
                rig.faceDirection(1.0, 0.0); // facing east; the player stands 1.5 blocks west, behind it
                rig.target.setHealth(rig.target.getMaxHealth());
                if (since >= 10) {
                    startAt[0] = context.getTick();
                    vetoesAtStart[0] = InhabitantsMod.aimCounters()[2];
                    rig.forceTarget(); // an engagement the aggro controller has confirmed: only the aim is in question
                    Rig.LOG.info("[melee-behind] the bot faces east (yaw {}), the player stands behind it; swinging from tick {}",
                            fmt(rig.bot.getYRot()), startAt[0]);
                }
                return;
            }
            long n = context.getTick() - startAt[0];
            if (!rig.hasTarget()) {
                rig.forceTarget();
            }
            double[] aim = InhabitantsMod.aimOf(rig.botName);
            Vec3 toPlayer = rig.target.getBoundingBox().getCenter().subtract(rig.bot.getEyePosition());
            offAim.add(aim == null ? "?" : fmt(angleDeg(dirOf(aim), toPlayer)));
            boolean landed = swing(rig);
            long vetoes = InhabitantsMod.aimCounters()[2] - vetoesAtStart[0];
            if (landed) {
                Rig.LOG.info("[melee-behind] the first blow landed at swing {} (degrees off the player before each swing {}); {} blows vetoed for aim",
                        n, offAim, vetoes);
                if (n < 4) {
                    rig.fail("a blow landed on a player behind the bot at swing " + n + ": the bot cannot have turned round yet");
                }
                if (vetoes < n - 1) {
                    rig.fail("only " + vetoes + " of the " + (n - 1) + " early swings were vetoed for want of aim");
                }
                rig.succeed();
            } else if (n > 40) {
                rig.fail("no blow ever landed on the player after the bot turned; degrees off " + offAim + " vetoes " + vetoes);
            }
        });
    }

    /** Control of (c): a sword bot that already faces the player lands its very first swing, and no swing is vetoed for aim. */
    @GameTest(environment = ENV + "human_aim_melee_front", maxTicks = 500)
    public void humanAimASwordBotFacingItsVictimHitsAtOnce(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(-1.5);
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        long[] startAt = {-1};
        long[] vetoesAtStart = {0};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.MELEE_ONLY, "melee-front")) {
                return;
            }
            long since = context.getTick() - dressedAt[0];
            if (startAt[0] < 0) {
                rig.faceDirection(-1.0, 0.0); // facing west, at the player 1.5 blocks away
                rig.target.setHealth(rig.target.getMaxHealth());
                if (since >= 10) {
                    startAt[0] = context.getTick();
                    vetoesAtStart[0] = InhabitantsMod.aimCounters()[2];
                    rig.forceTarget();
                }
                return;
            }
            long n = context.getTick() - startAt[0];
            if (!rig.hasTarget()) {
                rig.forceTarget();
            }
            boolean landed = swing(rig);
            long vetoes = InhabitantsMod.aimCounters()[2] - vetoesAtStart[0];
            if (landed) {
                Rig.LOG.info("[melee-front] the blow landed at swing {}; {} blows vetoed for aim", n, vetoes);
                if (n > 1) {
                    rig.fail("a bot facing its victim needed " + n + " swings to land a blow");
                }
                if (vetoes != 0) {
                    rig.fail("a blow was vetoed for want of aim although the bot faces the player: " + vetoes);
                }
                rig.succeed();
            } else if (n > 40) {
                rig.fail("the bot facing the player never landed a blow; vetoes " + vetoes + " " + rig.trace());
            }
        });
    }

    // ------------------------------------------------------------------ (d) arrows

    /**
     * (d) The arrows of a bow bot leave along the TRACKED aim. Deterministic: the bot's rotation is set the way PvP BOT sets it
     * inside its own tick (snapped onto a target on the other side, here east) while the tracked aim still looks west, and vanilla's
     * own bow launch (arrow created by the arrow item, {@code shootFromRotation} from the rotation, speed 3.0, inaccuracy 1.0)
     * is called. The arrow that enters the world is re-aimed along the tracked aim (plus a fraction of a degree of jitter), keeps
     * its launch speed, and is not deleted.
     */
    @GameTest(environment = ENV + "human_aim_arrow_reaim", maxTicks = 400)
    public void humanAimAnArrowLeavesAlongTheTrackedAimNotAlongTheRotationPvpBotJustSnapped(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(10.0);
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.BOW_ONLY, "reaim")) {
                return;
            }
            long since = context.getTick() - dressedAt[0];
            if (since == 3) {
                rig.faceDirection(-1.0, 0.0); // pinned once: the tracked aim looks west and is settled after a while
            }
            double[] aim = InhabitantsMod.aimOf(rig.botName);
            if (since < 4 || aim == null || aim[5] < 1.0) {
                if (since > 300) {
                    rig.fail("the tracked aim never settled: " + (aim == null ? "none" : "settled " + fmt(aim[5]) + " s"));
                }
                return;
            }
            double sigma = 0.3 + 2.5 * Math.exp(-aim[5] / 0.25);
            Vec3 tracked = dirOf(aim);
            Vec3 snapped = new Vec3(1.0, 0.0, 0.0);
            long before = InhabitantsMod.aimCounters()[0];
            double worstAim = 0.0;
            double worstSnapped = 180.0;
            double slowest = 99.0;
            double fastest = 0.0;
            int shot = 0;
            for (int i = 0; i < 30; i++) {
                // PvP BOT snapped the rotation onto its target, east, inside its own tick; the tracked aim still looks west
                float east = -90.0F;
                float yaw = rig.bot.getYRot();
                rig.bot.setYRot(east);
                rig.bot.setYHeadRot(east);
                rig.bot.setXRot(0.0F);
                ItemStack ammo = new ItemStack(Items.ARROW);
                AbstractArrow arrow = ((ArrowItem) Items.ARROW).createArrow(rig.level, ammo, rig.bot, new ItemStack(Items.BOW));
                Projectile.spawnProjectile(arrow, rig.level, ammo,
                        p -> p.shootFromRotation(rig.bot, rig.bot.getXRot(), rig.bot.getYRot(), 0.0F, 3.0F, 1.0F));
                Vec3 v = arrow.getDeltaMovement();
                worstAim = Math.max(worstAim, angleDeg(v, tracked));
                worstSnapped = Math.min(worstSnapped, angleDeg(v, snapped));
                slowest = Math.min(slowest, v.length());
                fastest = Math.max(fastest, v.length());
                if (arrow.isRemoved()) {
                    rig.fail("an arrow was removed by the human aim hook");
                }
                arrow.discard();
                rig.bot.setYRot(yaw);
                rig.bot.setYHeadRot(yaw);
                shot++;
            }
            long reaimed = InhabitantsMod.aimCounters()[0] - before;
            Rig.LOG.info("[reaim] {} arrows: worst angle to the tracked aim {} degrees, closest to the snapped rotation {} degrees; speed {} to {}; re-aimed {}",
                    shot, fmt(worstAim), fmt(worstSnapped), fmt(slowest), fmt(fastest), reaimed);
            if (reaimed != shot) {
                rig.fail("only " + reaimed + " of " + shot + " arrows were re-aimed by the human aim hook");
            }
            double allowed = 4.5 * sigma + 1.5; // the jitter of a settled hand, and the vanilla inaccuracy of the launch
            if (worstAim > allowed) {
                rig.fail("an arrow left " + fmt(worstAim) + " degrees off the tracked aim (jitter sigma " + fmt(sigma) + ", allowed " + fmt(allowed) + ")");
            }
            if (worstSnapped < 150.0) {
                rig.fail("an arrow flew within " + fmt(worstSnapped) + " degrees of the rotation PvP BOT had just snapped: not re-aimed");
            }
            if (slowest < 2.85 || fastest > 3.2) {
                rig.fail("the launch speed of a full-power bow arrow (3.0) was not kept: " + fmt(slowest) + " to " + fmt(fastest));
            }
            rig.succeed();
        });
    }

    /**
     * (d) The same with PvP BOT's own release: a bow bot draws at a player 10 blocks east; when its draw has one tick to go the
     * player is moved to the other side (west), so at the release PvP BOT snaps its rotation west, but the tracked aim still looks
     * east: the arrow leaves along the tracked aim (east), not toward where the player now stands. The next arrow, after the
     * head has turned, goes toward the player.
     */
    @GameTest(environment = ENV + "human_aim_bow_release", maxTicks = 700)
    public void humanAimABowBotsReleasedArrowLeavesAlongTheTrackedAim(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(10.0);
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        long[] flippedAt = {-1};
        Vec3[] trackedAtFlip = {null};
        Vec3[] towardAtFlip = {null};
        double[] firstError = {-1.0};
        double[] firstToward = {-1.0};
        Set<UUID> seen = new HashSet<>();
        int[] arrows = {0};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.BOW_ONLY, "bow-release")) {
                return;
            }
            long since = context.getTick() - dressedAt[0];
            rig.keepTargetAlive();
            if (since == 5) {
                rig.forceTarget();
            }
            if (flippedAt[0] < 0) {
                if (since < 5) {
                    return;
                }
                if (!rig.hasTarget()) {
                    rig.forceTarget();
                }
                freshArrows(rig, seen); // arrows of the first volley (if any) are not what is measured
                int draw = Upstream.bowDrawTicks(rig.botName);
                if (draw == 19) {
                    double[] aim = InhabitantsMod.aimOf(rig.botName);
                    if (aim == null) {
                        rig.fail("the bot has no tracked aim");
                        return;
                    }
                    trackedAtFlip[0] = dirOf(aim);
                    rig.placeTargetAt(rig.bot.getX() - 10.0, rig.bot.getY(), rig.bot.getZ());
                    towardAtFlip[0] = rig.target.getEyePosition().subtract(rig.bot.getEyePosition());
                    flippedAt[0] = context.getTick();
                    Rig.LOG.info("[bow-release] the draw is at 19 ticks at test tick {}: tracked aim yaw {} pitch {}; the player moves to the other side",
                            flippedAt[0], fmt(aim[0]), fmt(aim[1]));
                } else if (since > 300) {
                    rig.fail("the bow bot never drew to 19 ticks; " + rig.trace());
                }
                return;
            }
            long n = context.getTick() - flippedAt[0];
            for (AbstractArrow arrow : freshArrows(rig, seen)) {
                arrows[0]++;
                Vec3 v = arrow.getDeltaMovement();
                if (arrows[0] == 1) {
                    firstError[0] = angleDeg(v, trackedAtFlip[0]);
                    firstToward[0] = angleDeg(v, towardAtFlip[0]);
                    Rig.LOG.info("[bow-release] first arrow {} ticks after the flip: {} degrees off the tracked aim, {} degrees off the direction to "
                            + "the player's new place; re-aimed so far {}", n, fmt(firstError[0]), fmt(firstToward[0]),
                            InhabitantsMod.aimCounters()[0]);
                    if (firstError[0] > 10.0) {
                        rig.fail("the released arrow left " + fmt(firstError[0]) + " degrees off the tracked aim");
                    }
                    if (firstToward[0] < 90.0) {
                        rig.fail("the released arrow flew toward the player's new place (" + fmt(firstToward[0]) + " degrees off): it followed "
                                + "the rotation PvP BOT just snapped instead of the tracked aim");
                    }
                } else {
                    double toward = angleDeg(v, rig.target.getEyePosition().subtract(rig.bot.getEyePosition()));
                    Rig.LOG.info("[bow-release] arrow {} {} ticks after the flip: {} degrees off the direction to the player", arrows[0], n, fmt(toward));
                    if (toward > 30.0) {
                        rig.fail("the second arrow, after the head had turned, was still " + fmt(toward) + " degrees off the player");
                    }
                    if (InhabitantsMod.aimCounters()[0] < 2) {
                        rig.fail("the arrows were not re-aimed by the human aim hook");
                    }
                    rig.succeed();
                    return;
                }
            }
            if (n > 250) {
                rig.fail("arrows released so far: " + arrows[0] + "; " + rig.trace());
            }
        });
    }
}

package dev.spawnbotswrapper.inhabitants.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "No cheating": an inhabitant's melee hit has to be one a human client could make (see {@code MeleeLegality}). PvP BOT's
 * melee has no line-of-sight check; the real smoke test had a chasing inhabitant kill a player through a one-block wall.
 * These run the REAL PvP BOT melee routine (the bot has a forced target and an iron sword) against a survival player
 * standing still:
 * <ul>
 *   <li>behind a one-block wall: no damage for 100 ticks, and the veto log proves the bot did try;</li>
 *   <li>in the open (the control): damage, and no veto;</li>
 *   <li>behind a wall that is removed: no damage first, damage after;</li>
 *   <li>3.8 blocks away (beyond the 3.0 reach), the bot held in place: no damage, and a direct swing is vetoed as
 *       "beyond reach".</li>
 * </ul>
 * The bot is pinned to face the player (yaw and head yaw) so that no test depends on where the spawn happened to look.
 */
public final class MeleeLegalityGameTests {
    private static final String ENV = "pvpbot-inhabitants-gametest:";
    /** Yaw of a body looking toward +x (east). */
    private static final float EAST = -90.0F;
    private static final Pattern VETO = Pattern.compile("vetoed (\\d+) hit\\(s\\) by (\\S+) since the last line "
            + "\\((\\d+) without a clear line, (\\d+) beyond reach\\)");

    private static final class Scene {
        final Rig rig;
        final GameTestHelper ctx;
        final LogCapture capture = new LogCapture();
        final boolean[] dressed = {false};
        final long[] dressedAt = {0};
        boolean placed;
        long placedAt;
        Vec3 home;

        Scene(GameTestHelper ctx) {
            this.ctx = ctx;
            this.rig = new Rig(ctx);
            rig.onCleanup(capture::close);
        }

        /** True from the tick after the bot is dressed on; on the first such tick the player is put {@code dx} east of the bot. */
        boolean ready(double dx) {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.MELEE_ONLY, "melee")) {
                return false;
            }
            if (!placed) {
                placed = true;
                placedAt = ctx.getTick();
                home = rig.bot.position();
                face();
                rig.placeTarget(dx);
                rig.target.setHealth(rig.target.getMaxHealth());
                Rig.LOG.info("[melee] scene placed at tick {}: bot at {}, player {} blocks east", placedAt, fmt(home), dx);
            }
            return true;
        }

        void face() {
            rig.bot.setYRot(EAST);
            rig.bot.setYHeadRot(EAST);
            rig.bot.setXRot(0.0F);
            rig.bot.yRotO = EAST;
            rig.bot.xRotO = 0.0F;
        }

        /** Every tick: face the player and make sure PvP BOT has him as its target (it never acquires a walled-in player). */
        void engage() {
            face();
            if (Upstream.target(rig.botName).equals("none")) {
                rig.forceTarget();
            }
        }

        boolean damaged() {
            return rig.target.getHealth() < rig.target.getMaxHealth();
        }

        /** The largest veto counts the log has reported so far: {no-line, reach}, or null when there is no veto line for the bot. */
        int[] vetoCounts() {
            int blocked = -1;
            int reach = -1;
            for (String line : capture.containing("melee legality: vetoed")) {
                Matcher m = VETO.matcher(line);
                if (m.find() && m.group(2).equals(rig.botName)) {
                    blocked = Math.max(blocked, Integer.parseInt(m.group(3)));
                    reach = Math.max(reach, Integer.parseInt(m.group(4)));
                }
            }
            return blocked < 0 ? null : new int[] {blocked, reach};
        }

        List<String> vetoLines() {
            return capture.containing("melee legality: vetoed");
        }

        String status() {
            return "t=" + rig.level.getGameTime() + " bot=" + fmt(rig.bot.position()) + " player=" + fmt(rig.target.position())
                    + " dist=" + String.format(Locale.ROOT, "%.2f", rig.bot.distanceTo(rig.target)) + " playerHp="
                    + rig.target.getHealth() + " target=" + Upstream.target(rig.botName) + " " + Upstream.combatState(rig.botName);
        }
    }

    private static String fmt(Vec3 v) {
        return String.format(Locale.ROOT, "(%.2f,%.2f,%.2f)", v.x, v.y, v.z);
    }

    /** A one-block-thick wall, 5 high and 25 wide, {@code dx} blocks east of the bot's cell (the bot cannot walk or jump around it in time). */
    private static void buildWall(Rig rig, int dx, boolean solid) {
        for (int dz = -12; dz <= 12; dz++) {
            for (int dy = 0; dy <= 4; dy++) {
                rig.level.setBlock(rig.botFeet.offset(dx, dy, dz),
                        (solid ? Blocks.STONE : Blocks.AIR).defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    /**
     * The smoke-test finding: a player behind a one-block stone wall, 2.2 blocks from the attacking inhabitant, took no
     * melee damage in 100 ticks. The veto line proves the bot really swung (otherwise "no damage" would prove nothing).
     */
    @GameTest(environment = ENV + "melee_wall", maxTicks = 500)
    public void playerBehindAOneBlockWallTakesNoMeleeDamage(GameTestHelper context) {
        Scene s = new Scene(context);
        s.rig.buildPlatform();
        buildWall(s.rig, 1, true);
        s.rig.createTarget(2.0);
        context.onEachTick(() -> {
            if (!s.ready(2.0)) {
                return;
            }
            long since = context.getTick() - s.placedAt;
            s.engage();
            if (since % 20 == 0) {
                Rig.LOG.info("[wall] {}", s.status());
            }
            if (s.damaged()) {
                s.rig.fail("the player behind the wall took melee damage after " + since + " ticks; " + s.status()
                        + " vetoes=" + s.vetoLines());
            }
            if (since >= 100) {
                int[] counts = s.vetoCounts();
                Rig.LOG.info("[wall] after {} ticks: {} veto lines {}", since, s.vetoLines().size(), s.vetoLines());
                if (counts == null || counts[0] < 1) {
                    s.rig.fail("no swing through the wall was vetoed, so the bot never tried and the missing damage proves nothing; "
                            + s.status());
                }
                s.rig.succeed();
            }
        });
    }

    /** The control: the same scene without the wall, the player at the same spot takes damage (and nothing is vetoed as blocked). */
    @GameTest(environment = ENV + "melee_open", maxTicks = 500)
    public void playerInTheOpenTakesMeleeDamage(GameTestHelper context) {
        Scene s = new Scene(context);
        s.rig.buildPlatform();
        s.rig.createTarget(2.0);
        context.onEachTick(() -> {
            if (!s.ready(2.0)) {
                return;
            }
            long since = context.getTick() - s.placedAt;
            s.engage();
            if (since % 20 == 0) {
                Rig.LOG.info("[open] {}", s.status());
            }
            if (s.damaged()) {
                Rig.LOG.info("[open] damaged after {} ticks; {}; vetoes={}", since, s.status(), s.vetoLines());
                s.rig.succeed();
            } else if (since > 200) {
                s.rig.fail("the player in the open took no melee damage in " + since + " ticks; " + s.status() + " vetoes="
                        + s.vetoLines());
            }
        });
    }

    /** Wall first (no damage for 100 ticks), then the wall is gone: the same player is hit. */
    @GameTest(environment = ENV + "melee_wall_removed", maxTicks = 800)
    public void playerTakesDamageOnceTheWallIsGone(GameTestHelper context) {
        Scene s = new Scene(context);
        s.rig.buildPlatform();
        buildWall(s.rig, 1, true);
        s.rig.createTarget(2.0);
        long[] removedAt = {0};
        context.onEachTick(() -> {
            if (!s.ready(2.0)) {
                return;
            }
            long since = context.getTick() - s.placedAt;
            s.engage();
            if (since % 20 == 0) {
                Rig.LOG.info("[removed] {}", s.status());
            }
            if (removedAt[0] == 0) {
                if (s.damaged()) {
                    s.rig.fail("the player behind the wall took melee damage after " + since + " ticks; " + s.status());
                }
                if (since >= 100) {
                    int[] counts = s.vetoCounts();
                    if (counts == null || counts[0] < 1) {
                        s.rig.fail("no swing through the wall was vetoed, so the bot never tried; " + s.status());
                    }
                    buildWall(s.rig, 1, false);
                    removedAt[0] = context.getTick();
                    Rig.LOG.info("[removed] wall removed at tick {}", removedAt[0]);
                }
                return;
            }
            if (s.damaged()) {
                Rig.LOG.info("[removed] damaged {} ticks after the wall was removed", context.getTick() - removedAt[0]);
                s.rig.succeed();
            } else if (context.getTick() - removedAt[0] > 200) {
                s.rig.fail("the player took no damage 200 ticks after the wall was removed; " + s.status());
            }
        });
    }

    /**
     * Beyond vanilla reach: the player stands 3.8 blocks from the bot (3.5 to its box, reach 3.0), the bot is held in
     * place (else it would simply walk up). No damage for 100 ticks although the bot has him as its target, and a swing
     * made directly by the bot is vetoed as "beyond reach".
     */
    @GameTest(environment = ENV + "melee_reach", maxTicks = 500)
    public void playerBeyondVanillaReachTakesNoMeleeDamage(GameTestHelper context) {
        Scene s = new Scene(context);
        s.rig.buildPlatform();
        s.rig.createTarget(3.8);
        boolean[] swung = {false};
        context.onEachTick(() -> {
            if (!s.ready(3.8)) {
                return;
            }
            long since = context.getTick() - s.placedAt;
            s.engage();
            // Held in place: PvP BOT walks toward its target, which would close the distance the test is about.
            s.rig.bot.teleportTo(s.rig.level, s.home.x, s.home.y, s.home.z, java.util.Set.of(), EAST, 0.0F, true);
            s.rig.bot.setDeltaMovement(Vec3.ZERO);
            if (since % 20 == 0) {
                Rig.LOG.info("[reach] {}", s.status());
            }
            if (since == 30 && !swung[0]) {
                swung[0] = true;
                // A human standing there could not have hit; the bot's own attack goes through the same damage path.
                s.rig.bot.attack(s.rig.target);
            }
            if (s.damaged()) {
                s.rig.fail("the player 3.8 blocks away took melee damage after " + since + " ticks; " + s.status()
                        + " vetoes=" + s.vetoLines());
            }
            if (since >= 100) {
                int[] counts = s.vetoCounts();
                Rig.LOG.info("[reach] after {} ticks: vetoes {}", since, s.vetoLines());
                if (counts == null || counts[1] < 1) {
                    s.rig.fail("the swing at 3.8 blocks was not vetoed as beyond reach: " + s.vetoLines());
                }
                s.rig.succeed();
            }
        });
    }
}

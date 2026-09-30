package dev.spawnbotswrapper.inhabitants.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;

/**
 * Real-server tests of an inhabitant that carries a sword and a crossbow: the archer that ran out of arrows has to use
 * its sword (PvP BOT alone leaves it standing there holding the empty crossbow), a bot with ammunition keeps shooting
 * beyond the melee switch distance (twice the managed melee range of 2.5 blocks = 5 blocks) and takes the sword out
 * within it. See {@link Rig} for the scene.
 */
public final class OutOfAmmoGameTests {
    private static final String ENV = "pvpbot-inhabitants-gametest:";

    /** The shipped switch distance: twice the managed meleeRange 2.5. */
    private static final double SWITCH_DISTANCE = 5.0;

    /**
     * Proves a melee hit: once armed (after the projectiles in flight had time to land) the target is at full health, and
     * it must then lose health while the bot holds the sword and has launched nothing new.
     */
    private static final class MeleeProof {
        private final Rig rig;
        private long armedAt = -1;
        private int shotsAtArming;

        MeleeProof(Rig rig) {
            this.rig = rig;
        }

        boolean armed() {
            return armedAt >= 0;
        }

        void arm(long tick) {
            armedAt = tick;
            shotsAtArming = HarnessMod.shotsBy(rig.bot.getUUID()).size();
            rig.target.setHealth(rig.target.getMaxHealth());
        }

        long ticksSinceArming(long tick) {
            return tick - armedAt;
        }

        /** True when the target was hurt by a sword strike. */
        boolean hit(long tick) {
            if (rig.target.getHealth() >= rig.target.getMaxHealth()) {
                return false;
            }
            int shots = HarnessMod.shotsBy(rig.bot.getUUID()).size();
            if (shots != shotsAtArming) {
                // a late projectile, not a strike: start over
                arm(armedAt);
                return false;
            }
            return rig.bot.getMainHandItem().is(Items.IRON_SWORD);
        }
    }

    private static boolean holdsSword(Rig rig) {
        return rig.bot.getMainHandItem().is(Items.IRON_SWORD);
    }

    private static void keepTargeting(Rig rig) {
        if (Upstream.target(rig.botName).equals("none")) {
            rig.forceTarget();
        }
    }

    private void noArrowsSword(GameTestHelper context, double distance, String tag, int budget) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(distance);
        rig.hybridArrows = 0;
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        boolean[] sawSword = {false};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.HYBRID, tag)) {
                return;
            }
            keepTargeting(rig);
            long since = context.getTick() - dressedAt[0];
            rig.traceEvery(10, since, tag);
            sawSword[0] |= holdsSword(rig);
            int shots = HarnessMod.shotsBy(rig.bot.getUUID()).size();
            if (shots > 0) {
                rig.fail("a bot with no arrow launched " + shots + " projectile(s); " + rig.trace());
            }
            if (rig.target.getHealth() < rig.target.getMaxHealth()) {
                if (!holdsSword(rig)) {
                    rig.fail("the target was hurt but the bot does not hold the sword; " + rig.trace());
                }
                Rig.LOG.info("[{}] first melee hit {} ticks after dressing; {}", tag, since, rig.trace());
                rig.succeed();
            } else if (since > budget) {
                rig.fail("the bot with a sword and an empty crossbow did not hit the player at " + distance + " blocks in "
                        + since + " ticks (held the sword at some point: " + sawSword[0] + "); " + rig.trace());
            }
        });
    }

    /** The user's bug: an archer without arrows, a sword and a player 4 blocks away holds the sword and strikes. */
    @GameTest(environment = ENV + "oa_no_arrows_4", maxTicks = 500)
    public void botWithoutArrowsUsesItsSwordAtFourBlocks(GameTestHelper context) {
        noArrowsSword(context, 4.0, "oa4", 200);
    }

    /** Farther away it walks toward the player with the sword out and strikes. */
    @GameTest(environment = ENV + "oa_no_arrows_12", maxTicks = 700)
    public void botWithoutArrowsWalksUpAndUsesItsSwordAtTwelveBlocks(GameTestHelper context) {
        noArrowsSword(context, 12.0, "oa12", 400);
    }

    /** The arrows run out during the fight: the bot shoots first, and once nothing is left to shoot it switches to the sword. */
    @GameTest(environment = ENV + "oa_runs_out", maxTicks = 1500)
    public void botThatRunsOutOfArrowsMidFightSwitchesToItsSword(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(12.0);
        rig.hybridArrows = 3;
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        long[] ranOutAt = {-1};
        MeleeProof proof = new MeleeProof(rig);
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.HYBRID, "runsout")) {
                return;
            }
            keepTargeting(rig);
            long since = context.getTick() - dressedAt[0];
            rig.traceEvery(20, since, "runsout");
            int shots = HarnessMod.shotsBy(rig.bot.getUUID()).size();
            if (ranOutAt[0] < 0) {
                // shooting phase: keep the player alive and where he was
                rig.target.setHealth(rig.target.getMaxHealth());
                rig.placeTarget(12.0);
                if (shots > 0 && rig.ammunitionLeft() == 0) {
                    ranOutAt[0] = since;
                    Rig.LOG.info("[runsout] out of ammunition after {} ticks and {} shot(s); {}", since, shots, rig.trace());
                } else if (since > 900) {
                    rig.fail("the bot never used up its arrows (shots=" + shots + " ammo=" + rig.ammunitionLeft() + "); " + rig.trace());
                }
                return;
            }
            if (!proof.armed()) {
                if (since - ranOutAt[0] >= 40) {
                    if (!holdsSword(rig)) {
                        rig.fail("40 ticks after the last arrow the bot still does not hold the sword; " + rig.trace());
                    }
                    proof.arm(since);
                } else {
                    rig.target.setHealth(rig.target.getMaxHealth());
                }
                return;
            }
            if (proof.hit(since)) {
                Rig.LOG.info("[runsout] melee hit {} ticks after the last arrow; {}", since - ranOutAt[0], rig.trace());
                rig.succeed();
            } else if (proof.ticksSinceArming(since) > 400) {
                rig.fail("out of arrows the bot did not strike the player in 400 ticks; " + rig.trace());
            }
        });
    }

    /** A crossbow that is still loaded is fired (once) although the bot has no arrow in its inventory; then the sword comes out. */
    @GameTest(environment = ENV + "oa_loaded_no_arrows", maxTicks = 1200)
    public void loadedCrossbowWithoutArrowsFiresOnceThenTheBotUsesItsSword(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(12.0);
        rig.hybridArrows = 0;
        rig.hybridLoaded = true;
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        long[] firedAt = {-1};
        MeleeProof proof = new MeleeProof(rig);
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.HYBRID, "loaded")) {
                return;
            }
            keepTargeting(rig);
            long since = context.getTick() - dressedAt[0];
            rig.traceEvery(20, since, "loaded");
            int shots = HarnessMod.shotsBy(rig.bot.getUUID()).size();
            if (firedAt[0] < 0) {
                rig.target.setHealth(rig.target.getMaxHealth());
                rig.placeTarget(12.0);
                if (shots > 0) {
                    firedAt[0] = since;
                    Rig.LOG.info("[loaded] the loaded bolt was fired after {} ticks; {}", since, rig.trace());
                } else if (since > 400) {
                    rig.fail("the loaded crossbow was never fired; " + rig.trace());
                }
                return;
            }
            if (!proof.armed()) {
                if (since - firedAt[0] >= 40) {
                    if (shots != 1) {
                        rig.fail("expected exactly one projectile from the single loaded bolt, saw " + shots + "; " + rig.trace());
                    }
                    if (!holdsSword(rig)) {
                        rig.fail("after the only bolt was fired the bot does not hold the sword; " + rig.trace());
                    }
                    proof.arm(since);
                } else {
                    rig.target.setHealth(rig.target.getMaxHealth());
                }
                return;
            }
            if (proof.hit(since)) {
                rig.succeed();
            } else if (proof.ticksSinceArming(since) > 400) {
                rig.fail("after firing its only bolt the bot did not strike the player in 400 ticks; " + rig.trace());
            }
        });
    }

    /**
     * With ammunition the bot keeps shooting at 6 blocks (beyond the 5-block melee switch), and once the player stands at
     * 4.5 blocks (inside it) the bot holds the sword and strikes.
     */
    @GameTest(environment = ENV + "oa_switch_distance", maxTicks = 1200)
    public void botWithArrowsShootsAtSixBlocksAndUsesItsSwordWithinFive(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform(20);
        rig.createTarget(SWITCH_DISTANCE + 1.0);
        rig.hybridArrows = 40;
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        long[] movedAt = {-1};
        boolean[] heldCrossbowFar = {false};
        MeleeProof proof = new MeleeProof(rig);
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.HYBRID, "switch")) {
                return;
            }
            keepTargeting(rig);
            long since = context.getTick() - dressedAt[0];
            rig.traceEvery(20, since, "switch");
            int shots = HarnessMod.shotsBy(rig.bot.getUUID()).size();
            if (movedAt[0] < 0) {
                double before = rig.bot.distanceTo(rig.target);
                rig.target.setHealth(rig.target.getMaxHealth());
                // an archer backs away from a player closer than rangedMinRange (8), so the player follows the bot
                rig.placeTargetBesideBot(SWITCH_DISTANCE + 1.0);
                heldCrossbowFar[0] |= rig.bot.getMainHandItem().is(Items.CROSSBOW);
                if (shots > 0) {
                    if (before <= SWITCH_DISTANCE) {
                        rig.fail("the bot shot while the player was only " + before + " blocks away; " + rig.trace());
                    }
                    if (!heldCrossbowFar[0]) {
                        rig.fail("the bot shot at 6 blocks without ever holding the crossbow; " + rig.trace());
                    }
                    movedAt[0] = since;
                    Rig.LOG.info("[switch] shooting at 6 blocks after {} ticks; moving the player to 4.5 blocks; {}", since, rig.trace());
                    rig.placeTargetBesideBot(SWITCH_DISTANCE - 0.5);
                } else if (since > 500) {
                    rig.fail("the bot with arrows did not shoot at 6 blocks in " + since + " ticks; " + rig.trace());
                }
                return;
            }
            long moved = since - movedAt[0];
            if (!proof.armed()) {
                if (moved >= 30) {
                    if (!holdsSword(rig)) {
                        rig.fail("30 ticks after the player came within 5 blocks the bot does not hold the sword; " + rig.trace());
                    }
                    proof.arm(since);
                } else {
                    rig.target.setHealth(rig.target.getMaxHealth());
                }
                return;
            }
            if (proof.hit(since)) {
                Rig.LOG.info("[switch] melee hit {} ticks after the player came within 5 blocks; {}", moved, rig.trace());
                rig.succeed();
            } else if (proof.ticksSinceArming(since) > 300) {
                rig.fail("within 5 blocks the bot did not strike the player in 300 ticks; " + rig.trace());
            }
        });
    }
}

package dev.spawnbotswrapper.inhabitants.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Real-server tests of how an inhabitant (a real PvP BOT bot on a real HeroBot fake player) uses its bow and crossbow
 * against a nearby survival player, with the wrapper loaded. See {@link Rig} for the scene.
 */
public final class RangedCombatGameTests {
    /** Every test is its own environment: environments run one after another, so no bot ever sees another test's player. */
    private static final String ENV = "pvpbot-inhabitants-gametest:";

    /** Ticks a test waits for the inhabitant to exist before it gives up. */
    private static final int SPAWN_TICKS = 200;
    /** Shots (volleys) a rate test collects before it judges the cycle. */
    private static final int RATE_SHOTS = 8;

    /** The ticks at which separate shots left the bot: projectiles launched in one tick (piercing, multishot) are one shot. */
    private static List<Long> shotTicks(List<HarnessMod.Shot> shots) {
        List<Long> ticks = new ArrayList<>();
        for (HarnessMod.Shot s : shots) {
            if (ticks.isEmpty() || ticks.get(ticks.size() - 1) != s.tick()) {
                ticks.add(s.tick());
            }
        }
        return ticks;
    }

    /**
     * What the user reported: an inhabitant with a crossbow next to a player who does not attack it never shoots
     * until the player hits it first. Nobody touches the bot here; it has to open fire on its own.
     */
    @GameTest(environment = ENV + "crossbow_unhit", maxTicks = 800)
    public void crossbowInhabitantFiresAtNearbyPlayerWithoutBeingHitFirst(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(6.0);
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.SKIRMISHER, "unhit")) {
                return;
            }
            long since = context.getTick() - dressedAt[0];
            rig.traceEvery(10, since, "unhit");
            List<HarnessMod.Shot> shots = HarnessMod.shotsBy(rig.bot.getUUID());
            if (!shots.isEmpty()) {
                Rig.LOG.info("[unhit] first shot {} ticks after dressing: {}", since, shots.get(0));
                rig.succeed();
            } else if (since > 400) {
                rig.fail("the crossbow inhabitant did not fire once in " + since + " ticks; " + rig.trace());
            }
        });
    }

    /**
     * NATURAL crossbow speed: a Quick Charge III crossbow charges in 10 ticks, so at a visible player the inhabitant must
     * cycle (charge, load, fire) about every 12 ticks: what a person spam-clicking gets, never faster than vanilla allows
     * (the charge time) and not held back by PvP BOT's fixed 25-tick draw, nor by a rate limit of the addon's own.
     */
    @GameTest(environment = ENV + "crossbow_rate", maxTicks = 900)
    public void quickChargeThreeCrossbowFiresAboutEveryTwelveTicksAtAVisiblePlayer(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(6.0);
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.SKIRMISHER, "qc3")) {
                return;
            }
            rig.keepTargetAlive();
            long since = context.getTick() - dressedAt[0];
            rig.traceEvery(20, since, "qc3");
            List<Long> ticks = shotTicks(HarnessMod.shotsBy(rig.bot.getUUID()));
            if (ticks.size() > RATE_SHOTS) {
                List<Long> gaps = new ArrayList<>();
                for (int i = 1; i < ticks.size(); i++) {
                    gaps.add(ticks.get(i) - ticks.get(i - 1));
                }
                List<Long> sorted = new ArrayList<>(gaps);
                java.util.Collections.sort(sorted);
                long min = sorted.get(0);
                long median = sorted.get(sorted.size() / 2);
                Rig.LOG.info("[qc3] {} shots, gaps {} (min {}, median {}), first shot {} ticks after dressing", ticks.size(),
                        gaps, min, median, ticks.get(0) - (dressedAt[0]));
                if (min < 10) {
                    rig.fail("two shots only " + min + " ticks apart: faster than a Quick Charge III crossbow can charge; gaps " + gaps);
                }
                if (median < 11 || median > 14) {
                    rig.fail("the typical shot gap is " + median + " ticks, not about 12; gaps " + gaps);
                }
                rig.succeed();
            } else if (since > 700) {
                rig.fail("only " + ticks.size() + " shot(s) in " + since + " ticks; " + rig.trace());
            }
        });
    }

    /**
     * The "machine gun" after the player swings at the inhabitant: within four blocks of a swinging player PvP BOT raises
     * its shield through HeroBot's continuous "use" action, and that action right-clicks the crossbow every tick. The bot
     * has no sword here: a bot that carries one takes it out within five blocks (see OutOfAmmoGameTests) and no longer holds
     * the crossbow at three blocks. There is no pacing of the addon's own any more, so the guard is vanilla itself: whatever
     * fires the crossbow, two shots can never be closer than the Quick Charge III charge time (10 ticks), and the
     * inhabitant keeps shooting (it is not frozen by the shield).
     */
    @GameTest(environment = ENV + "crossbow_swing", maxTicks = 900)
    public void crossbowShotsNeverBeatVanillaChargeTimeWhileThePlayerSwingsAtThreeBlocks(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(3.0);
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.CROSSBOW_SHIELD, "swing")) {
                return;
            }
            rig.keepTargetAlive();
            // The target stays three blocks from the bot and keeps swinging (a mock player is never ticked, so the swing lasts).
            Vec3 at = rig.bot.position().add(3.0, 0.0, 0.0);
            rig.target.teleportTo(rig.level, at.x, at.y, at.z, java.util.Set.of(), 90.0F, 0.0F, true);
            rig.target.swinging = true;
            rig.target.swingTime = 0;
            rig.target.swingingArm = InteractionHand.MAIN_HAND;
            long since = context.getTick() - dressedAt[0];
            rig.traceEvery(10, since, "swing");
            if (since < 500) {
                return;
            }
            List<Long> ticks = shotTicks(HarnessMod.shotsBy(rig.bot.getUUID()));
            StringBuilder deltas = new StringBuilder();
            long closest = Long.MAX_VALUE;
            for (int i = 1; i < ticks.size(); i++) {
                long gap = ticks.get(i) - ticks.get(i - 1);
                closest = Math.min(closest, gap);
                deltas.append(gap).append(' ');
            }
            Rig.LOG.info("[swing] {} shots, gaps between shots: {}", ticks.size(), deltas);
            if (closest == Long.MAX_VALUE) {
                rig.fail("fewer than two separate shots in " + since + " ticks, so the rate cannot be judged; " + rig.trace());
            }
            if (closest < 10) {
                rig.fail("two shots only " + closest + " ticks apart: faster than a Quick Charge III crossbow can charge; gaps: " + deltas);
            }
            rig.succeed();
        });
    }

    /**
     * NATURAL bow speed: PvP BOT's own 2 second draw is an artificial wait; managed at 20 ticks (vanilla full power) an
     * inhabitant's bow releases a full-power arrow about every 20 to 22 ticks at a visible player.
     */
    @GameTest(environment = ENV + "bow_rate", maxTicks = 900)
    public void bowReleasesAFullPowerArrowAboutEveryTwentyOneTicksAtAVisiblePlayer(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(9.0);
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.BOW_ONLY, "bowrate")) {
                return;
            }
            rig.keepTargetAlive();
            long since = context.getTick() - dressedAt[0];
            rig.traceEvery(20, since, "bowrate");
            List<HarnessMod.Shot> shots = HarnessMod.shotsBy(rig.bot.getUUID());
            List<Long> ticks = shotTicks(shots);
            if (ticks.size() > RATE_SHOTS) {
                List<Long> gaps = new ArrayList<>();
                for (int i = 1; i < ticks.size(); i++) {
                    gaps.add(ticks.get(i) - ticks.get(i - 1));
                }
                List<Long> sorted = new ArrayList<>(gaps);
                java.util.Collections.sort(sorted);
                long min = sorted.get(0);
                long median = sorted.get(sorted.size() / 2);
                List<Double> speeds = new ArrayList<>();
                for (HarnessMod.Shot s : shots) {
                    speeds.add(s.speed());
                }
                java.util.Collections.sort(speeds);
                double medianSpeed = speeds.get(speeds.size() / 2);
                Rig.LOG.info("[bowrate] {} shots, gaps {} (min {}, median {}), median launch speed {}", ticks.size(), gaps,
                        min, median, String.format(java.util.Locale.ROOT, "%.2f", medianSpeed));
                if (min < 19) {
                    rig.fail("two arrows only " + min + " ticks apart: a bow needs 20 ticks for full power; gaps " + gaps);
                }
                if (median < 20 || median > 23) {
                    rig.fail("the typical arrow gap is " + median + " ticks, not 20 to 22; gaps " + gaps);
                }
                if (medianSpeed < 2.8) {
                    rig.fail("the arrows are not full power (median launch speed " + medianSpeed + ", full power is 3.0)");
                }
                rig.succeed();
            } else if (since > 700) {
                rig.fail("only " + ticks.size() + " arrow(s) in " + since + " ticks; " + rig.trace());
            }
        });
    }

    /** The same with a sword in the hotbar: this is the case weapon auto-equip broke (it keeps selecting the sword, which ends every draw). */
    @GameTest(environment = ENV + "bow_sword", maxTicks = 800)
    public void bowAndSwordInhabitantReleasesArrows(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(8.0);
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.BOW_AND_SWORD, "bow+sword")) {
                return;
            }
            long since = context.getTick() - dressedAt[0];
            rig.traceEvery(10, since, "bow+sword");
            List<HarnessMod.Shot> shots = HarnessMod.shotsBy(rig.bot.getUUID());
            if (!shots.isEmpty()) {
                Rig.LOG.info("[bow+sword] first arrow {} ticks after dressing: {}", since, shots.get(0));
                rig.succeed();
            } else if (since > 400) {
                rig.fail("the bow-and-sword inhabitant did not release one arrow in " + since + " ticks; " + rig.trace());
            }
        });
    }

    /**
     * The trigger asks for line of sight: with a wall between the crossbow inhabitant and the player, PvP BOT still draws
     * and loads the crossbow, but the addon does not fire it (nothing would be hit and the bolt would only hit the wall).
     */
    @GameTest(environment = ENV + "crossbow_no_los", maxTicks = 500)
    public void loadedCrossbowIsNotFiredThroughAWall(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        for (int dz = -12; dz <= 12; dz++) {
            for (int dy = 0; dy <= 5; dy++) {
                rig.level.setBlock(rig.botFeet.offset(3, dy, dz), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),
                        net.minecraft.world.level.block.Block.UPDATE_ALL);
            }
        }
        rig.createTarget(6.0);
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        boolean[] wasLoaded = {false};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.SKIRMISHER, "wall")) {
                return;
            }
            long since = context.getTick() - dressedAt[0];
            if (Upstream.target(rig.botName).equals("none")) {
                rig.forceTarget();
            }
            rig.traceEvery(20, since, "wall");
            wasLoaded[0] |= net.minecraft.world.item.CrossbowItem.isCharged(rig.bot.getMainHandItem());
            int shots = HarnessMod.shotsBy(rig.bot.getUUID()).size();
            if (shots > 0) {
                rig.fail("the inhabitant fired " + shots + " projectile(s) although a wall stands between it and the player; " + rig.trace());
            }
            if (since >= 250) {
                if (!wasLoaded[0]) {
                    rig.fail("the crossbow was never loaded, so the absence of shots proves nothing; " + rig.trace());
                }
                rig.succeed();
            }
        });
    }

    /** A bow-only inhabitant has to release arrows: every bow draw used to be cancelled by PvP BOT's own weapon auto-equip. */
    @GameTest(environment = ENV + "bow_only", maxTicks = 800)
    public void bowOnlyInhabitantReleasesArrows(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(8.0);
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.BOW_ONLY, "bow")) {
                return;
            }
            long since = context.getTick() - dressedAt[0];
            rig.traceEvery(10, since, "bow");
            List<HarnessMod.Shot> shots = HarnessMod.shotsBy(rig.bot.getUUID());
            if (!shots.isEmpty()) {
                Rig.LOG.info("[bow] first arrow {} ticks after dressing: {}", since, shots.get(0));
                rig.succeed();
            } else if (since > 400) {
                rig.fail("the bow inhabitant did not release one arrow in " + since + " ticks; " + rig.trace());
            }
        });
    }
}

package dev.spawnbotswrapper.inhabitants.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Real-server tests of how an inhabitant (a real PvP BOT bot on a real HeroBot fake player) uses its bow and crossbow
 * against a nearby survival player, with the wrapper loaded. See {@link Rig} for the scene.
 */
public final class RangedCombatGameTests {
    /** Ticks a test waits for the inhabitant to exist before it gives up. */
    private static final int SPAWN_TICKS = 200;
    /** The wrapper's default {@code rangedPacing.crossbowMinShotIntervalTicks}: PvP BOT's own 25-tick draw plus one. */
    private static final int PACING_TICKS = 26;

    /**
     * What the user reported: an inhabitant with a crossbow next to a player who does not attack it never shoots
     * until the player hits it first. Nobody touches the bot here; it has to open fire on its own.
     */
    @GameTest(maxTicks = 800)
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
                context.succeed();
            } else if (since > 400) {
                rig.fail("the crossbow inhabitant did not fire once in " + since + " ticks; " + rig.trace());
            }
        });
    }

    /**
     * The "machine gun" after the player swings at the inhabitant: within four blocks of a swinging player PvP BOT raises
     * its shield through HeroBot's continuous "use" action, and that action right-clicks the crossbow every tick. Whatever
     * fires the crossbow, shots must never be closer together than the pacing interval.
     */
    @GameTest(maxTicks = 900)
    public void crossbowShotsKeepThePacingIntervalWhileThePlayerSwingsAtThreeBlocks(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(3.0);
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.SKIRMISHER, "swing")) {
                return;
            }
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
            List<HarnessMod.Shot> shots = HarnessMod.shotsBy(rig.bot.getUUID());
            StringBuilder deltas = new StringBuilder();
            long closest = Long.MAX_VALUE;
            for (int i = 1; i < shots.size(); i++) {
                long gap = shots.get(i).tick() - shots.get(i - 1).tick();
                // piercing/multishot volleys put several projectiles into one tick; those are one shot
                if (gap > 0) {
                    closest = Math.min(closest, gap);
                    deltas.append(gap).append(' ');
                }
            }
            Rig.LOG.info("[swing] {} projectiles, gaps between shots: {}", shots.size(), deltas);
            if (closest == Long.MAX_VALUE) {
                rig.fail("fewer than two separate shots in " + since + " ticks, so the pacing cannot be judged; " + rig.trace());
            }
            if (closest < PACING_TICKS) {
                rig.fail("two shots only " + closest + " ticks apart (pacing interval " + PACING_TICKS + "); gaps: " + deltas);
            }
            context.succeed();
        });
    }

    /** The same with a sword in the hotbar: this is the case weapon auto-equip broke (it keeps selecting the sword, which ends every draw). */
    @GameTest(maxTicks = 800)
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
                context.succeed();
            } else if (since > 400) {
                rig.fail("the bow-and-sword inhabitant did not release one arrow in " + since + " ticks; " + rig.trace());
            }
        });
    }

    /** A bow-only inhabitant has to release arrows: every bow draw used to be cancelled by PvP BOT's own weapon auto-equip. */
    @GameTest(maxTicks = 800)
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
                context.succeed();
            } else if (since > 400) {
                rig.fail("the bow inhabitant did not release one arrow in " + since + " ticks; " + rig.trace());
            }
        });
    }
}

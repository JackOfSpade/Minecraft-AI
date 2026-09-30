package dev.spawnbotswrapper.inhabitants.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;

/**
 * The user's question about the 10 block targeting radius: "will they come to me if I hit them outside that range?" The
 * answer is read off PvP BOT's behaviour with the real settings of the run (auto target on, radius 10).
 */
public final class TargetingRadiusGameTests {
    private static final String ENV = "pvpbot-inhabitants-gametest:";

    /**
     * A player hits an inhabitant from 15 blocks (a bow shot, say): PvP BOT's revenge only accepts an attacker within
     * maxTargetDistance, so the bot neither targets nor walks toward the player. The moment the player comes within the
     * radius the bot picks it as a target (auto target on).
     */
    @GameTest(environment = ENV + "radius_beyond", maxTicks = 500)
    public void playerHitFromBeyondTheTargetingRadiusIsNotPursuedUntilTheyComeWithinIt(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform(20);
        rig.createTarget(15.0);
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        Vec3[] home = {null};
        long[] hitAt = {-1};
        long[] closeAt = {-1};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.MELEE_ONLY, "radius")) {
                return;
            }
            if (home[0] == null) {
                home[0] = rig.bot.position();
            }
            long now = context.getTick();
            rig.traceEvery(20, now - dressedAt[0], "radius");
            if (hitAt[0] < 0) {
                // a fresh bot is protected like a client that has not finished loading
                if (rig.tryHit(2.0F)) {
                    Rig.LOG.info("[radius] hit from {} blocks applied, health now {}", rig.bot.distanceTo(rig.target),
                            rig.bot.getHealth());
                    hitAt[0] = now;
                } else if (now - dressedAt[0] > 200) {
                    rig.fail("the inhabitant refused every hit for 200 ticks; " + rig.trace());
                }
                return;
            }
            if (closeAt[0] < 0) {
                if (!Upstream.target(rig.botName).equals("none")) {
                    rig.fail("the bot targets a player 15 blocks away that hit it: " + Upstream.target(rig.botName));
                }
                if (rig.bot.position().distanceTo(home[0]) > 3.0) { // the hit itself knocks it back about a block and a half
                    rig.fail("the bot walked " + rig.bot.position().distanceTo(home[0]) + " blocks toward a player beyond its radius");
                }
                if (now - hitAt[0] >= 100) {
                    Rig.LOG.info("[radius] 100 ticks after the hit: no target, bot did not move. Player steps to 8 blocks.");
                    rig.placeTarget(8.0);
                    closeAt[0] = now;
                }
                return;
            }
            if (!Upstream.target(rig.botName).equals("none")) {
                Rig.LOG.info("[radius] the bot targets the player {} ticks after it came within the radius: {}",
                        now - closeAt[0], Upstream.target(rig.botName));
                rig.succeed();
            } else if (now - closeAt[0] > 60) {
                rig.fail("the player stands 8 blocks away and the bot still has no target; " + rig.trace());
            }
        });
    }
}

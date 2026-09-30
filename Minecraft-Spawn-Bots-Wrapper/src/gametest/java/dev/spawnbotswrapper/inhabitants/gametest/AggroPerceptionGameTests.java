package dev.spawnbotswrapper.inhabitants.gametest;

import dev.spawnbotswrapper.inhabitants.InhabitantsMod;
import dev.spawnbotswrapper.inhabitants.command.CommandServices;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;
import java.util.Locale;

/**
 * Real-server tests of the perception rules of the line-of-sight hunter that need a real server: the 64 block engage
 * limit, hearing through vanilla's own vibration system (walking is heard, sneaking is silent, a stone wall gives a clue but
 * no notice, wool blocks the vibration, no listener leaks), and the reaction time on EVERY sighting (a loaded crossbow does
 * not fire the instant the player comes into view, a blink behind a pillar restarts the reaction, and a hit does not let
 * PvP BOT counter-hit inside the reaction delay). The mock player does not walk by itself: a step is vanilla's STEP game
 * event emitted as the player ({@link Rig#emitStep}), and everything after that is vanilla and the wrapper.
 */
public final class AggroPerceptionGameTests {
    private static final String ENV = "pvpbot-inhabitants-gametest:";

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    // ------------------------------------------------------------------ the engage limit

    /**
     * A player in plain view in the cone at 70 blocks is never engaged (the engage limit is 64), however long it stands
     * there. Control: the same player at 60 blocks is engaged after its reaction time (0.5 + 1.5 * 60 / 64 = 1.9 s, 39 ticks),
     * so the distance was the only reason.
     */
    @GameTest(environment = ENV + "aggro_engage_limit", maxTicks = 500)
    public void aggroAPlayerInPlainViewAtSeventyBlocksIsNeverEngagedButAtSixtyIs(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildStrip(76);
        rig.createTarget(70.0);
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        long[] movedAt = {-1};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.MELEE_ONLY, "engage-limit")) {
                return;
            }
            long since = context.getTick() - dressedAt[0];
            rig.keepTargetAlive();
            if (movedAt[0] < 0) {
                if (since == 2 && !rig.bot.hasLineOfSight(rig.target)) {
                    rig.fail("scene broken: the player 70 blocks away is not in the bot's line of sight");
                }
                if (rig.hasTarget() || !rig.phase().equals("IDLE")) {
                    rig.fail("the bot engaged a player 70 blocks away (the limit is 64): phase " + rig.phase() + " " + rig.trace());
                }
                if (since >= 100) {
                    rig.placeTargetAt(rig.homeX() + 60.0, rig.bot.getY(), rig.homeZ());
                    movedAt[0] = context.getTick();
                    Rig.LOG.info("[engage-limit] never engaged at 70 blocks for {} ticks; the player steps to 60 blocks", since);
                }
                return;
            }
            long n = context.getTick() - movedAt[0];
            if (rig.hasTarget()) {
                Rig.LOG.info("[engage-limit] engaged {} ticks after the player came within 64 blocks (0.5 + 1.5 * 60 / 64 = 1.9 s = 39 ticks)", n);
                if (n < 34) {
                    rig.fail("the bot engaged a player 60 blocks away after only " + n + " ticks: the reaction time is 39 ticks");
                }
                rig.succeed();
            } else if (n > 120) {
                rig.fail("the bot never engaged a player 60 blocks away in plain view; phase " + rig.phase() + " " + rig.trace());
            }
        });
    }

    // ------------------------------------------------------------------ hearing (vanilla vibrations)

    /**
     * A player walking (vanilla step vibrations) 6 blocks BEHIND the bot, in clear view of it: the vibration reaches the
     * bot (radius 16, travel time 6 ticks), it turns to the sound, and after the heard reaction time (0.5 + 1.5 * 6 / 64 =
     * 0.64 s, 13 ticks) it has the player as its target: noticed by hearing.
     */
    @GameTest(environment = ENV + "aggro_hear_walk", maxTicks = 700)
    public void aggroAPlayerWalkingSixBlocksBehindIsHeardAndNoticedAfterTheReactionTime(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(-6.0);
        LogCapture capture = new LogCapture();
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        long[] startedAt = {-1};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.MELEE_ONLY, "hear-walk")) {
                return;
            }
            rig.keepTargetAlive();
            if (startedAt[0] < 0) {
                startedAt[0] = context.getTick();
                rig.faceDirection(1.0, 0.0); // east: the player at -x is directly behind
            }
            long n = context.getTick() - startedAt[0];
            if (rig.hasTarget()) {
                List<String> lines = capture.containing("noticed");
                capture.close();
                Rig.LOG.info("[hear-walk] noticed {} ticks after the first step; listeners registered {}; log {}", n,
                        InhabitantsMod.hearingListeners(), lines);
                if (n < 13) {
                    rig.fail("noticed only " + n + " ticks after the first step: the sound needs 6 ticks to travel and the reaction 13");
                }
                if (lines.stream().noneMatch(l -> l.contains(rig.botName) && l.contains("by hearing"))) {
                    rig.fail("noticed, but not 'by hearing': " + lines);
                }
                rig.succeed();
                return;
            }
            if (n % 5 == 0) {
                rig.emitStep();
            }
            if (n > 200) {
                capture.close();
                rig.fail("a player walking 6 blocks behind was never heard and noticed; listeners registered "
                        + InhabitantsMod.hearingListeners() + " " + rig.aggroSubjectTrace());
            }
        });
    }

    /**
     * A SNEAKING player 3 blocks behind the bot takes steps: vanilla emits no vibration for them (the bot stays as it was,
     * looking east, and never has a target). Control: the same player then stands up and takes the same steps and is
     * noticed, so the silence was vanilla's sneaking rule and not a broken listener.
     */
    @GameTest(environment = ENV + "aggro_hear_sneak", maxTicks = 700)
    public void aggroASneakingPlayerThreeBlocksBehindIsNotHeardUntilItStandsUp(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(-3.0);
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        long[] startedAt = {-1};
        long[] stoodAt = {-1};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.MELEE_ONLY, "hear-sneak")) {
                return;
            }
            rig.keepTargetAlive();
            if (startedAt[0] < 0) {
                startedAt[0] = context.getTick();
                rig.faceDirection(1.0, 0.0);
            }
            long n = context.getTick() - startedAt[0];
            if (stoodAt[0] < 0) {
                rig.setSneaking(true);
                if (n % 5 == 0) {
                    rig.emitStep();
                }
                if (rig.hasTarget() || !rig.phase().equals("IDLE") || rig.lookX() < 0.9) {
                    rig.fail("the bot noticed or turned to a SNEAKING player's steps: phase " + rig.phase() + " look.x="
                            + fmt(rig.lookX()) + " " + rig.aggroSubjectTrace());
                }
                if (n >= 80) {
                    if (InhabitantsMod.hearingListeners() < 1) {
                        rig.fail("no vibration listener is registered for the inhabitant: the silence above proves nothing");
                    }
                    rig.setSneaking(false);
                    stoodAt[0] = context.getTick();
                    Rig.LOG.info("[hear-sneak] silent for {} ticks; the player stands up and keeps walking", n);
                }
                return;
            }
            long m = context.getTick() - stoodAt[0];
            if (rig.hasTarget()) {
                Rig.LOG.info("[hear-sneak] control: noticed {} ticks after standing up", m);
                rig.succeed();
                return;
            }
            if (m % 5 == 0) {
                rig.emitStep();
            }
            if (m > 100) {
                rig.fail("control failed: the walking player 3 blocks behind was not heard within 100 ticks; " + rig.aggroSubjectTrace());
            }
        });
    }

    /**
     * A player walking on the far side of a two-block stone wall, 4 blocks behind the bot: the vibration passes through the
     * stone (only wool stops it), so the bot HEARS it and turns to look at where it came from, but it sees nobody, so it never
     * engages: an occluded sound is a clue, not a notice.
     */
    @GameTest(environment = ENV + "aggro_hear_wall", maxTicks = 700)
    public void aggroAPlayerWalkingBehindAStoneWallMakesTheBotTurnButIsNeverEngaged(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.fill(-3, 0, -8, -2, 5, 8, Blocks.STONE);
        rig.createTarget(-4.0);
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        long[] startedAt = {-1};
        boolean[] turned = {false};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.MELEE_ONLY, "hear-wall")) {
                return;
            }
            rig.keepTargetAlive();
            if (startedAt[0] < 0) {
                startedAt[0] = context.getTick();
                rig.faceDirection(1.0, 0.0);
            }
            long n = context.getTick() - startedAt[0];
            if (rig.hasTarget() || !rig.phase().equals("IDLE")) {
                rig.fail("the bot engaged a player it cannot see, behind a wall: phase " + rig.phase() + " " + rig.trace());
            }
            if (rig.lookX() < -0.5) {
                if (!turned[0]) {
                    Rig.LOG.info("[hear-wall] the bot turned to the sound {} ticks after the first step (look.x={})", n, fmt(rig.lookX()));
                }
                turned[0] = true;
            }
            if (n % 5 == 0) {
                rig.emitStep();
            }
            if (n >= 100) {
                if (!turned[0]) {
                    rig.fail("the bot never turned toward the sound behind the wall (look.x=" + fmt(rig.lookX()) + "); listeners "
                            + InhabitantsMod.hearingListeners());
                }
                rig.succeed();
            }
        });
    }

    /**
     * The same walk behind a WOOL wall: wool blocks vibrations (vanilla), so the bot does not hear it at all and never turns.
     * Control: the wool is then replaced by stone and the bot turns to the sound, so the silence was the wool.
     */
    @GameTest(environment = ENV + "aggro_hear_wool", maxTicks = 700)
    public void aggroAPlayerWalkingBehindWoolIsNotHeardButBehindStoneIs(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.fill(-2, 0, -8, -2, 5, 8, Blocks.WHITE_WOOL);
        rig.createTarget(-5.0);
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        long[] startedAt = {-1};
        long[] swappedAt = {-1};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.MELEE_ONLY, "hear-wool")) {
                return;
            }
            rig.keepTargetAlive();
            if (startedAt[0] < 0) {
                startedAt[0] = context.getTick();
                rig.faceDirection(1.0, 0.0);
            }
            long n = context.getTick() - startedAt[0];
            if (rig.hasTarget() || !rig.phase().equals("IDLE")) {
                rig.fail("the bot engaged a player it cannot see: phase " + rig.phase() + " " + rig.trace());
            }
            if (swappedAt[0] < 0) {
                if (rig.lookX() < 0.9) {
                    rig.fail("the bot turned to steps behind a wool wall (look.x=" + fmt(rig.lookX()) + "): wool blocks vibrations");
                }
                if (n % 5 == 0) {
                    rig.emitStep();
                }
                if (n >= 60) {
                    if (InhabitantsMod.hearingListeners() < 1) {
                        rig.fail("no vibration listener is registered for the inhabitant: the silence above proves nothing");
                    }
                    rig.fill(-2, 0, -8, -2, 5, 8, Blocks.STONE);
                    swappedAt[0] = context.getTick();
                    Rig.LOG.info("[hear-wool] no reaction to steps behind wool for {} ticks; the wool becomes stone", n);
                }
                return;
            }
            long m = context.getTick() - swappedAt[0];
            if (m % 5 == 0) {
                rig.emitStep();
            }
            if (rig.lookX() < -0.5) {
                Rig.LOG.info("[hear-wool] control: the bot turned to the sound {} ticks after the wool became stone", m);
                rig.succeed();
            } else if (m > 60) {
                rig.fail("control failed: steps behind a stone wall did not turn the bot either (look.x=" + fmt(rig.lookX()) + ")");
            }
        });
    }

    /**
     * No listener leaks: while the inhabitant lives exactly one vibration listener is registered, in the game event registry
     * of the chunk section it stands in (vanilla's own registry: it is not empty); when the inhabitant is removed the
     * listener is gone from both the wrapper's count and that registry.
     */
    @GameTest(environment = ENV + "aggro_listeners", maxTicks = 500)
    public void aggroTheVibrationListenerIsRegisteredWhileTheInhabitantLivesAndGoneWhenItIsRemoved(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        long[] removedAt = {-1};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.MELEE_ONLY, "listeners")) {
                return;
            }
            long since = context.getTick() - dressedAt[0];
            LevelChunk chunk = rig.level.getChunkAt(rig.botFeet);
            int sectionY = SectionPos.blockToSectionCoord(rig.botFeet.getY());
            boolean registryEmpty = chunk.getListenerRegistry(sectionY).isEmpty();
            int count = InhabitantsMod.hearingListeners();
            if (removedAt[0] < 0) {
                if (since >= 5) {
                    if (count != 1) {
                        rig.fail("expected exactly one vibration listener for the one inhabitant, found " + count);
                    }
                    if (registryEmpty) {
                        rig.fail("the inhabitant's listener is not in the game event registry of its chunk section");
                    }
                    CommandServices services = InhabitantsMod.servicesOf(rig.server);
                    services.adapter().removeBot(rig.server, rig.botName);
                    rig.server.getPlayerList().remove(rig.bot);
                    rig.bot.discard();
                    removedAt[0] = context.getTick();
                    Rig.LOG.info("[listeners] one listener registered, in a non-empty registry; the inhabitant is removed");
                }
                return;
            }
            long n = context.getTick() - removedAt[0];
            if (count == 0 && registryEmpty) {
                Rig.LOG.info("[listeners] {} ticks after the removal: the listener is gone (count 0, registry empty)", n);
                rig.succeed();
            } else if (n > 20) {
                rig.fail("the listener leaked: wrapper count " + count + ", registry empty " + registryEmpty);
            }
        });
    }

    // ------------------------------------------------------------------ the reaction time on every sighting

    /**
     * A player steps out from behind a wall into view of a bot with a LOADED crossbow, 10 blocks away: no shot before the
     * reaction time (0.5 + 1.5 * 10 / 64 = 0.73 s, 15 ticks), a shot after it.
     */
    @GameTest(environment = ENV + "aggro_confirm_wait", maxTicks = 700)
    public void aggroALoadedCrossbowDoesNotFireBeforeTheReactionTimeWhenThePlayerAppears(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.fill(2, 0, -6, 2, 4, 6, Blocks.STONE); // a wall between the bot and the player
        rig.createTarget(10.0);
        rig.hybridCrossbow = true;
        rig.hybridLoaded = true;
        rig.hybridArrows = 16;
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        long[] appearedAt = {-1};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.HYBRID, "confirm-wait")) {
                return;
            }
            long since = context.getTick() - dressedAt[0];
            rig.keepTargetAlive();
            List<HarnessMod.Shot> shots = HarnessMod.shotsBy(rig.bot.getUUID());
            if (appearedAt[0] < 0) {
                if (!shots.isEmpty() || rig.hasTarget()) {
                    rig.fail("the bot shot at or targeted a player behind a wall: " + rig.trace());
                }
                if (since >= 20) {
                    rig.fill(2, 0, -6, 2, 4, 6, Blocks.AIR); // the player steps into view
                    appearedAt[0] = rig.level.getGameTime();
                    Rig.LOG.info("[confirm-wait] the player came into view at game tick {}; crossbow loaded: {}", appearedAt[0],
                            net.minecraft.world.item.CrossbowItem.isCharged(rig.bot.getInventory().getItem(1)));
                }
                return;
            }
            long n = rig.level.getGameTime() - appearedAt[0];
            if (!shots.isEmpty()) {
                long first = shots.get(0).tick() - appearedAt[0];
                Rig.LOG.info("[confirm-wait] first shot {} ticks after the player came into view (reaction time 15 ticks)", first);
                if (first < 14) {
                    rig.fail("the bot fired " + first + " ticks after the player came into view: the reaction time (15 ticks) was skipped");
                }
                rig.succeed();
            } else if (n > 120) {
                rig.fail("the loaded crossbow never fired at the player in plain view; " + rig.trace());
            }
        });
    }

    /**
     * A bot that is fighting a player at 10 blocks loses sight of it for 5 ticks behind a pillar. The FIRST unseen tick ends
     * the confirmation (PvP BOT holds no target), and when the player reappears the reaction starts again from zero: no target
     * and no shot for another 14 ticks, the target again after about 15.
     */
    @GameTest(environment = ENV + "aggro_confirm_blink", maxTicks = 900)
    public void aggroABlinkBehindAPillarOfFiveTicksNeedsAFullReactionTimeAgain(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(10.0);
        rig.hybridCrossbow = true;
        rig.hybridLoaded = true;
        rig.hybridArrows = 32;
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        long[] engagedAt = {-1};
        long[] hiddenAt = {-1};
        long[] shownAt = {-1};
        int[] shotsAtShown = {0};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.HYBRID, "confirm-blink")) {
                return;
            }
            rig.keepTargetAlive();
            long now = rig.level.getGameTime();
            int shots = HarnessMod.shotsBy(rig.bot.getUUID()).size();
            if (engagedAt[0] < 0) {
                if (rig.hasTarget()) {
                    engagedAt[0] = now;
                    Rig.LOG.info("[confirm-blink] engaged at game tick {}", now);
                } else if (context.getTick() - dressedAt[0] > 150) {
                    rig.fail("the bot never engaged a player 10 blocks away in plain view; " + rig.trace());
                }
                return;
            }
            if (hiddenAt[0] < 0) {
                // ten ticks of fighting, then the player steps behind a one block pillar on the line between them
                if (now - engagedAt[0] >= 10) {
                    rig.fill(5, 0, 0, 5, 3, 0, Blocks.STONE);
                    hiddenAt[0] = now;
                    Rig.LOG.info("[confirm-blink] the player is hidden behind a pillar at game tick {}", now);
                }
                return;
            }
            if (shownAt[0] < 0) {
                if (now - hiddenAt[0] >= 5) {
                    rig.fill(5, 0, 0, 5, 3, 0, Blocks.AIR);
                    shownAt[0] = now;
                    shotsAtShown[0] = shots;
                    Rig.LOG.info("[confirm-blink] the player reappeared at game tick {} after 5 ticks; PvP BOT target {}", now,
                            Upstream.target(rig.botName));
                }
                return;
            }
            long n = now - shownAt[0];
            if (n <= 14) {
                if (rig.hasTarget()) {
                    rig.fail("PvP BOT held the target " + n + " ticks after the player reappeared: no new reaction time");
                }
                if (shots != shotsAtShown[0]) {
                    rig.fail("the bot fired " + n + " ticks after the player reappeared: no new reaction time");
                }
                return;
            }
            if (rig.hasTarget()) {
                Rig.LOG.info("[confirm-blink] the target is back {} ticks after the player reappeared (reaction 15 ticks)", n);
                rig.succeed();
            } else if (n > 60) {
                rig.fail("the bot never engaged the player again after it reappeared; phase " + rig.phase() + " " + rig.trace());
            }
        });
    }

    /**
     * A player hits the bot from the front at 2 blocks. PvP BOT's revenge would counter-hit at once; the reaction delay
     * (0.5 + 1.5 * 2 / 64 = 0.55 s, 11 ticks) holds it back at damage level: the player takes no damage in the first 10
     * ticks after the hit, and the bot does fight back afterwards.
     */
    @GameTest(environment = ENV + "aggro_confirm_melee", maxTicks = 700)
    public void aggroAMeleeHitFromTheFrontLetsNoCounterHitLandInsideTheReactionDelay(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(2.0);
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        long[] hitAt = {-1};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.MELEE_ONLY, "confirm-melee")) {
                return;
            }
            long since = context.getTick() - dressedAt[0];
            long now = rig.level.getGameTime();
            if (hitAt[0] < 0) {
                rig.target.setHealth(rig.target.getMaxHealth());
                if (since >= 2 && rig.tryHit(1.0F)) {
                    hitAt[0] = now;
                    rig.target.setHealth(rig.target.getMaxHealth());
                    Rig.LOG.info("[confirm-melee] the player hit the bot at game tick {}", now);
                } else if (since > 200) {
                    rig.fail("the hit on the bot was refused for 200 ticks");
                }
                return;
            }
            long n = now - hitAt[0];
            if (n == 3) {
                // The damage-level veto itself: whatever PvP BOT does, a melee blow the inhabitant lands on the player inside
                // the reaction delay is refused (no damage, no knockback) and counted.
                float before = rig.target.getHealth();
                long vetoesBefore = InhabitantsMod.reactionHolds()[0];
                rig.bot.attack(rig.target);
                long vetoed = InhabitantsMod.reactionHolds()[0] - vetoesBefore;
                Rig.LOG.info("[confirm-melee] a melee blow forced on the player 3 ticks after the hit: vetoed {}, health {} -> {}",
                        vetoed, before, rig.target.getHealth());
                if (vetoed < 1 || rig.target.getHealth() < before) {
                    rig.fail("a melee blow inside the reaction delay was not vetoed at damage level (vetoed " + vetoed + ", health "
                            + before + " -> " + rig.target.getHealth() + ")");
                }
            }
            boolean damaged = rig.target.getHealth() < rig.target.getMaxHealth();
            long[] holds = InhabitantsMod.reactionHolds();
            if (damaged && n < 10) {
                rig.fail("the bot counter-hit the player " + n + " ticks after being hit, inside the reaction delay (melee blows vetoed so far: "
                        + holds[0] + ")");
            }
            if (damaged) {
                Rig.LOG.info("[confirm-melee] the bot hit back {} ticks after the hit (reaction delay 11 ticks); melee blows vetoed at damage level: {}",
                        n, holds[0]);
                rig.succeed();
            } else if (n > 150) {
                rig.fail("the bot never fought back after the reaction delay; phase " + rig.phase() + " " + rig.trace()
                        + " vetoed=" + holds[0]);
            }
        });
    }
}

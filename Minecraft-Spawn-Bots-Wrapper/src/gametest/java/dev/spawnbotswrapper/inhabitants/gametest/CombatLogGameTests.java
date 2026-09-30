package dev.spawnbotswrapper.inhabitants.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** The combat log lines of hits an inhabitant takes, with a real HeroBot fake player as the victim. */
public final class CombatLogGameTests {
    private static final String ENV = "pvpbot-inhabitants-gametest:";

    /**
     * The user hit several inhabitants and the log had no "Combat taken:" line. The fake player class of the bot mod
     * re-implements the vanilla hurt routine, so the Fabric damage event never fires for it; the wrapper has to notice
     * the hit another way. A survival mock player hits the inhabitant like a player would (a real melee damage source).
     */
    @GameTest(environment = ENV + "combat_taken", maxTicks = 300)
    public void hitAnInhabitantTakesShowsUpAsACombatTakenLine(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(2.0);
        LogCapture capture = new LogCapture();
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        boolean[] hit = {false};
        long[] hitAt = {0};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.SKIRMISHER, "taken")) {
                return;
            }
            long sinceDress = context.getTick() - dressedAt[0];
            rig.traceEvery(5, sinceDress, "taken");
            long since = context.getTick() - dressedAt[0];
            // A fresh bot is protected like a client that has not finished loading (up to 60 ticks), as for any joining player.
            if (!hit[0] && rig.bot.connection.hasClientLoaded()) {
                float before = rig.bot.getHealth();
                ServerPlayer attacker = rig.target;
                boolean applied = rig.bot.hurtServer(rig.level, rig.level.damageSources().playerAttack(attacker), 4.0F);
                Rig.LOG.info("[taken] hit applied={} health {} -> {} (fake player class {})", applied, before,
                        rig.bot.getHealth(), rig.bot.getClass().getName());
                hit[0] = true;
                hitAt[0] = context.getTick();
            }
            if (hit[0] && context.getTick() >= hitAt[0] + 10) {
                List<String> lines = capture.containing("Combat taken:");
                capture.close();
                if (lines.isEmpty()) {
                    rig.fail("the hit left no 'Combat taken:' line in the log");
                }
                String line = lines.get(0);
                if (!line.contains(rig.botName) || !line.contains("player") || !line.contains("via player")
                        || !line.contains("4.0 damage")) {
                    rig.fail("the 'Combat taken:' line does not describe the hit: " + line);
                }
                if (line.contains("not-readable") || !line.contains(" target=") || !line.contains(" mode=")) {
                    rig.fail("the state snapshot does not carry PvP BOT's target and mode: " + line);
                }
                Rig.LOG.info("[taken] line: {}", line);
                rig.succeed();
            }
        });
    }

    /**
     * The "ranged loop" diagnostic names its cause: with PvP BOT's weapon auto-equip switched back ON (the wrapper turns it
     * off), a bot with a sword and a bow starts a draw, PvP BOT moves the selection to the sword at the end of the tick, and
     * the draw dies every 20 ticks. Three such draws raise the warning, and it has to say the selection left the bow inside
     * the tick (seen by sampling the slot at the start and at the end of the server tick).
     */
    @GameTest(environment = ENV + "ranged_loop_cause", maxTicks = 500)
    public void rangedLoopWarningNamesTheAutoEquipCause(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(8.0);
        LogCapture capture = new LogCapture();
        rig.onCleanup(capture::close);
        rig.onCleanup(() -> Upstream.setAutoEquipWeapon(false));
        boolean[] dressed = {false};
        long[] dressedAt = {0};
        context.onEachTick(() -> {
            if (!rig.awaitDressed(dressed, dressedAt, Rig.Loadout.BOW_AND_SWORD, "loop")) {
                return;
            }
            long since = context.getTick() - dressedAt[0];
            if (since == 1) {
                Upstream.setAutoEquipWeapon(true);
            }
            rig.traceEvery(20, since, "loop");
            List<String> lines = capture.containing("ranged loop:");
            if (!lines.isEmpty()) {
                String line = lines.get(0);
                if (!line.contains("selected slot left the ranged weapon inside the tick")) {
                    rig.fail("the ranged loop warning does not name the cause: " + line);
                }
                Rig.LOG.info("[loop] warning: {}", line);
                rig.succeed();
            } else if (since > 350) {
                rig.fail("no ranged loop warning in " + since + " ticks; " + rig.trace());
            }
        });
    }
}

package dev.spawnbotswrapper.inhabitants.gametest;

import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;

/**
 * Real-server checks of nearest-first population and of the rule that makes a loot or XP farm impossible: taking a bot
 * out of the world is NOT a death (nothing drops, nothing dies, nothing is counted), a real death drops vanilla loot and is
 * never refilled, a bot a player has seen persists (it sleeps with its whole state and wakes with exactly one copy of each
 * item), a bot nobody saw is ephemeral (deleted, and a fresh one fills the slot on return).
 */
public final class PopulationGameTests {
    private static final String ENV = "pvpbot-inhabitants-gametest:";

    /**
     * PvP BOT's own removal runs {@code clear} and then kills the bot, a real vanilla death: its items and XP orbs (level x 7,
     * at most 100) drop and a death message goes out. Removal by the addon must be silent: emptied first, then a plain
     * disconnect. Nothing on the ground within 5 ticks, no death event, the entity gone at once, PvP BOT forgot it.
     */
    @GameTest(environment = ENV + "removal_no_death", maxTicks = 300)
    public void removingABotDropsNothingAndIsNotADeath(GameTestHelper context) {
        PopulationScene scene = new PopulationScene(context);
        scene.buildPlatform();
        scene.createHuman(6.0, false);
        boolean[] done = {false};
        long[] removedAt = {-1};
        UUID[] botId = new UUID[1];
        Vec3[] where = new Vec3[1];
        Entity[] entity = new Entity[1];
        Map<String, Integer>[] carried = new Map[1];
        context.onEachTick(() -> {
            if (done[0] || !scene.requestInhabitant()) {
                return;
            }
            if (removedAt[0] < 0) {
                if (!scene.inhabitantReady()) {
                    if (context.getTick() > 200) {
                        scene.fail("the inhabitant never appeared");
                    }
                    return;
                }
                carried[0] = scene.dress();
                botId[0] = scene.bot.getUUID();
                where[0] = scene.bot.position();
                entity[0] = scene.bot;
                PopulationScene.LOG.info("[removal] {} carries {} and {} experience levels", scene.botName, carried[0], scene.bot.experienceLevel);
                boolean issued = scene.services().adapter().removeBot(scene.server, scene.botName);
                removedAt[0] = context.getTick();
                if (!issued) {
                    scene.fail("the removal was not issued");
                }
                // Synchronous: the entity is gone the moment the call returns.
                if (scene.server.getPlayerList().getPlayerByName(scene.botName) != null) {
                    scene.fail("the bot is still online right after its removal");
                }
                if (scene.services().adapter().isManaged(scene.botName)) {
                    scene.fail("PvP BOT still lists the removed bot");
                }
                return;
            }
            long since = context.getTick() - removedAt[0];
            if (since < 6) {
                return;
            }
            Map<String, Integer> items = scene.itemsOnTheGround(12);
            int orbs = scene.orbsOnTheGround(12);
            if (!items.isEmpty() || orbs != 0) {
                scene.fail("removal dropped " + items + " and " + orbs + " experience: it is a farm, not a removal");
            }
            if (PopulationScene.deathsOf(botId[0]) != 0) {
                scene.fail("the removal was a death (AFTER_DEATH fired " + PopulationScene.deathsOf(botId[0]) + " time(s))");
            }
            if (entity[0].getRemovalReason() == Entity.RemovalReason.KILLED || ((ServerPlayer) entity[0]).isDeadOrDying()) {
                scene.fail("the bot was killed: removal reason " + entity[0].getRemovalReason());
            }
            PopulationScene.LOG.info("[removal] ok: nothing dropped ({} items, {} xp), no death, reason {}", items.size(), orbs,
                    entity[0].getRemovalReason());
            done[0] = true;
            scene.succeed();
        });
    }

    /**
     * A real kill (a player hits the bot) is vanilla: the bot drops what it carried and its experience, the death is
     * counted for its structure, and the slot is NEVER refilled: the structure has planned one bot, and one bot's worth of
     * kills is all it can ever yield.
     */
    @GameTest(environment = ENV + "real_kill", maxTicks = 700)
    public void aRealKillDropsVanillaLootAndIsNeverRefilled(GameTestHelper context) {
        PopulationScene scene = new PopulationScene(context);
        scene.buildPlatform();
        scene.vanillaDeathRules();
        scene.allocationQuick(64.0);
        scene.createHuman(6.0, false);
        long[] killedAt = {-1};
        UUID[] botId = new UUID[1];
        Map<String, Integer>[] carried = new Map[1];
        context.onEachTick(() -> {
            if (!scene.requestInhabitant()) {
                return;
            }
            if (killedAt[0] < 0) {
                if (!scene.inhabitantReady()) {
                    if (context.getTick() > 200) {
                        scene.fail("the inhabitant never appeared");
                    }
                    return;
                }
                if (carried[0] == null) {
                    carried[0] = scene.dress();
                    botId[0] = scene.bot.getUUID();
                    PopulationScene.LOG.info("[kill] {} carries {}", scene.botName, carried[0]);
                }
                if (!scene.bot.connection.hasClientLoaded()) {
                    scene.bot.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
                }
                var source = scene.level.damageSources().playerAttack(scene.human);
                if (scene.bot.hurtServer(scene.level, source, 100.0F) || scene.bot.isDeadOrDying()) {
                    killedAt[0] = context.getTick();
                } else if (context.getTick() > 300) {
                    scene.fail("the bot refused every hit");
                }
                return;
            }
            long since = context.getTick() - killedAt[0];
            if (since == 10) {
                Map<String, Integer> items = scene.itemsOnTheGround(12);
                int orbs = scene.orbsOnTheGround(12);
                if (!items.equals(carried[0])) {
                    scene.fail("a death must drop exactly what the bot carried; carried " + carried[0] + " but dropped " + items);
                }
                if (orbs <= 0) {
                    scene.fail("a death of a level-30 player must drop experience orbs, found " + orbs);
                }
                if (PopulationScene.deathsOf(botId[0]) != 1) {
                    scene.fail("expected one death event, saw " + PopulationScene.deathsOf(botId[0]));
                }
                StructureRecord record = scene.record().orElseThrow();
                if (record.deadCount() != 1) {
                    scene.fail("the death was not counted for the structure: dead=" + record.deadCount() + " bots=" + record.bots.size());
                }
                PopulationScene.LOG.info("[kill] ok: dropped {} and {} xp, dead={}", items, orbs, record.deadCount());
            }
            if (since >= 250) {
                StructureRecord record = scene.record().orElseThrow();
                for (BotRecord b : record.bots) {
                    if (b.state != BotState.DEAD) {
                        scene.fail("a killed bot's slot was refilled: " + b.name + " is " + b.state);
                    }
                }
                if (record.bots.size() != 1 || record.vacantSlots() != 0) {
                    scene.fail("the structure must hold exactly its one dead bot and no vacant slot: bots=" + record.bots.size()
                            + " vacant=" + record.vacantSlots());
                }
                scene.succeed();
            }
        });
    }

    /**
     * A bot a player has SEEN persists. It is seen for real (the human stands in front of it, looking at it: view cone and
     * line of sight), then the human leaves so that its structure drops out of the allocation: the bot sleeps with its
     * whole state and is taken out WITHOUT dropping anything. When the human returns, the same bot (same name) is back with
     * exactly one copy of each item: what it carried is neither lost nor duplicated (inventory plus the ground).
     */
    @GameTest(environment = ENV + "seen_sleep_wake", maxTicks = 1200)
    public void aSeenBotSleepsAndWakesWithExactlyOneCopyOfEachItem(GameTestHelper context) {
        PopulationScene scene = new PopulationScene(context);
        scene.buildPlatform();
        scene.allocationQuick(20.0);
        scene.createHuman(3.0, true);
        int[] phase = {0};
        long[] at = {0};
        Map<String, Integer>[] carried = new Map[1];
        String[] name = new String[1];
        Vec3[] slept = new Vec3[1];
        context.onEachTick(() -> {
            if (!scene.requestInhabitant()) {
                return;
            }
            long now = context.getTick();
            switch (phase[0]) {
                case 0 -> {
                    if (!scene.inhabitantReady()) {
                        if (now > 200) {
                            scene.fail("the inhabitant never appeared");
                        }
                        return;
                    }
                    carried[0] = scene.dress();
                    name[0] = scene.botName;
                    // A snapshot of the dressed state is what the sleep will save: give the roster time to see it too.
                    PopulationScene.LOG.info("[seen] {} carries {}", name[0], carried[0]);
                    phase[0] = 1;
                    at[0] = now;
                }
                case 1 -> {
                    BotRecord b = scene.botRecord(0);
                    if (b != null && b.seen) {
                        PopulationScene.LOG.info("[seen] {} was seen after {} ticks", name[0], now - at[0]);
                        slept[0] = scene.bot.position();
                        scene.moveHuman(60.0); // out of the (20 block) relevance area
                        phase[0] = 2;
                        at[0] = now;
                    } else if (now - at[0] > 200) {
                        scene.fail("the bot in front of the player was never marked seen");
                    }
                }
                case 2 -> {
                    BotRecord b = scene.botRecord(0);
                    boolean offline = scene.server.getPlayerList().getPlayerByName(name[0]) == null;
                    if (b != null && b.state == BotState.DORMANT && offline && !b.removing) {
                        if (b.snapshot == null || b.snapshot.stacks.isEmpty()) {
                            scene.fail("the sleeping bot has no saved state");
                        }
                        Map<String, Integer> ground = scene.itemsOnTheGround(12);
                        if (!ground.isEmpty() || scene.orbsOnTheGround(12) != 0) {
                            scene.fail("going to sleep dropped " + ground + " and " + scene.orbsOnTheGround(12) + " xp");
                        }
                        if (scene.record().orElseThrow().deadCount() != 0) {
                            scene.fail("going to sleep was counted as a death");
                        }
                        PopulationScene.LOG.info("[seen] asleep after {} ticks with {} saved stack(s) at {} {} {}", now - at[0],
                                b.snapshot.stacks.size(), b.x, b.y, b.z);
                        scene.placeHuman(3.0, true); // back
                        phase[0] = 3;
                        at[0] = now;
                    } else if (now - at[0] > 400) {
                        scene.fail("the seen bot did not go to sleep: " + scene.describeRecord() + " offline=" + offline);
                    }
                }
                case 3 -> {
                    BotRecord b = scene.botRecord(0);
                    ServerPlayer again = scene.server.getPlayerList().getPlayerByName(name[0]);
                    if (b != null && b.state == BotState.SPAWNED && again != null) {
                        if (now - at[0] < 10) {
                            return; // let the wake finish (the inventory is written slot by slot right after it appears)
                        }
                        Map<String, Integer> now2 = PopulationScene.counts(again);
                        Map<String, Integer> ground = scene.itemsOnTheGround(12);
                        if (!now2.equals(carried[0])) {
                            scene.fail("the woken bot must carry exactly what it carried before: had " + carried[0] + " now " + now2);
                        }
                        if (!ground.isEmpty()) {
                            scene.fail("a second copy lies on the ground: " + ground);
                        }
                        if (again.experienceLevel != 30) {
                            scene.fail("its experience was not kept: level " + again.experienceLevel);
                        }
                        if (again.position().distanceTo(slept[0]) > 4.0) {
                            scene.fail("it woke " + again.position().distanceTo(slept[0]) + " blocks from where it went to sleep");
                        }
                        if (scene.record().orElseThrow().bots.size() != 1) {
                            scene.fail("the same bot must be back, not a second one: " + scene.record().orElseThrow().bots.size() + " bots");
                        }
                        PopulationScene.LOG.info("[seen] ok: awake again as {} with {}", name[0], now2);
                        scene.succeed();
                    } else if (now - at[0] > 500) {
                        scene.fail("the sleeping bot did not wake: " + scene.describeRecord() + " online=" + (again != null));
                    }
                }
                default -> {
                }
            }
        });
    }

    /**
     * A bot nobody saw is ephemeral: when its structure leaves the allocation it is deleted (no record, PvP BOT forgets
     * it, nothing dropped, not a death) and its slot is vacant; when the player returns a FRESH bot (a different name) fills
     * the slot.
     */
    @GameTest(environment = ENV + "unseen_fresh_on_return", maxTicks = 1200)
    public void anUnseenBotIsDeletedAndAFreshOneFillsTheSlotOnReturn(GameTestHelper context) {
        PopulationScene scene = new PopulationScene(context);
        scene.buildPlatform();
        scene.allocationQuick(20.0);
        scene.createHuman(3.0, false); // looking away: the bot is never in view
        int[] phase = {0};
        long[] at = {0};
        String[] first = new String[1];
        context.onEachTick(() -> {
            if (!scene.requestInhabitant()) {
                return;
            }
            long now = context.getTick();
            switch (phase[0]) {
                case 0 -> {
                    if (!scene.inhabitantReady()) {
                        if (now > 200) {
                            scene.fail("the inhabitant never appeared");
                        }
                        return;
                    }
                    first[0] = scene.botName;
                    scene.dress();
                    scene.moveHuman(60.0);
                    phase[0] = 1;
                    at[0] = now;
                }
                case 1 -> {
                    StructureRecord r = scene.record().orElseThrow();
                    boolean offline = scene.server.getPlayerList().getPlayerByName(first[0]) == null;
                    if (offline && r.bots.isEmpty()) {
                        if (r.deadCount() != 0) {
                            scene.fail("the deletion was counted as a death");
                        }
                        if (r.vacantSlots() != 1) {
                            scene.fail("the slot must be vacant: " + r.vacantSlots());
                        }
                        if (scene.services().population().findBot(first[0]).isPresent()) {
                            scene.fail("the store still knows the deleted bot");
                        }
                        if (scene.services().adapter().isManaged(first[0])) {
                            scene.fail("PvP BOT still lists the deleted bot");
                        }
                        Map<String, Integer> ground = scene.itemsOnTheGround(12);
                        if (!ground.isEmpty() || scene.orbsOnTheGround(12) != 0) {
                            scene.fail("the deletion dropped " + ground + " and " + scene.orbsOnTheGround(12) + " xp");
                        }
                        PopulationScene.LOG.info("[unseen] deleted after {} ticks; slot vacant", now - at[0]);
                        scene.placeHuman(3.0, false);
                        phase[0] = 2;
                        at[0] = now;
                    } else if (now - at[0] > 400) {
                        scene.fail("the unseen bot was not deleted: bots=" + r.bots.size() + " offline=" + offline
                                + (r.bots.isEmpty() ? "" : " state=" + r.bots.get(0).state + " seen=" + r.bots.get(0).seen));
                    }
                }
                case 2 -> {
                    StructureRecord r = scene.record().orElseThrow();
                    BotRecord fresh = r.bots.stream().filter(b -> b.state == BotState.SPAWNED).findFirst().orElse(null);
                    if (fresh != null && scene.server.getPlayerList().getPlayerByName(fresh.name) != null) {
                        if (fresh.name.equalsIgnoreCase(first[0])) {
                            scene.fail("the slot was refilled with the deleted bot instead of a fresh roll");
                        }
                        if (r.deadCount() != 0 || r.bots.size() != 1) {
                            scene.fail("unexpected record: dead=" + r.deadCount() + " bots=" + r.bots.size());
                        }
                        PopulationScene.LOG.info("[unseen] ok: {} replaced {} after {} ticks", fresh.name, first[0], now - at[0]);
                        scene.succeed();
                    } else if (now - at[0] > 500) {
                        scene.fail("no fresh bot filled the vacant slot: " + r.bots.size() + " bot record(s)");
                    }
                }
                default -> {
                }
            }
        });
    }
}

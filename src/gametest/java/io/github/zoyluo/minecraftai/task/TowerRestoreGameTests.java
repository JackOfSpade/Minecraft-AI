package io.github.zoyluo.minecraftai.task;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.persist.BotPersistence;
import io.github.zoyluo.minecraftai.persist.BotRecord;
import io.github.zoyluo.minecraftai.persist.MissionRuntimeRecord;
import io.github.zoyluo.minecraftai.persist.PersistedBot;
import io.github.zoyluo.minecraftai.persist.RuntimeSnapshot;
import io.github.zoyluo.minecraftai.persist.RuntimeSnapshotCodec;
import io.github.zoyluo.minecraftai.persist.TowerBaseCodec;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import io.github.zoyluo.minecraftai.task.GatherOverheadLogGameTests.Case;
import java.io.StringReader;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * A server restart while the bot stands on a tower it built must not leave it stranded there: the tower it stood on is saved with the
 * bot ({@code BotRecord.towerBase}) and the restored bot takes it down first, whether or not the task that built it comes back. Every
 * case is a real round trip: a real {@link MineTask} climbs a six-block tower to a dirt block eleven up in a sealed room, the bot is
 * captured by the save path ({@link BotPersistence#capture}), pushed through the runtime.json codec and unloaded (a restart clears every
 * runtime registry, the tower in the world stays), and {@link AIPlayerManager#respawnFromRecord} restores it. Nothing is staged: the
 * tower under the restored bot is the one the task built.
 */
public final class TowerRestoreGameTests {
    private static final String ENV = "minecraftai-gametest:tower_restore_game_tests_";
    /** A dirt block eleven up is a tower of six, one block farther than the arm reaches from the next lower tower. */
    private static final int TARGET_UP = 11;
    private static final int SUPPORTS = 6;
    private static final int RESTART_GAP_TICKS = 3;
    private static final int TICK_BUDGET = 2800;

    @GameTest(environment = ENV + "bot_restored_halfway_up_its_tower_comes_down_without_its_task", maxTicks = 3000)
    public void botRestoredHalfwayUpItsTowerComesDownWithoutItsTask(GameTestHelper context) {
        Restart r = new Restart(context, "TowerRestoreClimbGT");
        r.watch(r::halfwayUp, null, () -> {
            if (!r.isDown()) {
                return false;
            }
            List<String> lines = r.afterRestore();
            r.requireBackOnTheFloor(lines);
            r.c.require(r.c.world().getBlockState(r.target).is(Blocks.DIRT),
                    "a restored bot with no task went on mining: " + r.c.tail(lines, ""));
            return true;
        });
    }

    @GameTest(environment = ENV + "bot_restored_halfway_down_its_tower_finishes_coming_down", maxTicks = 3000)
    public void botRestoredHalfwayDownItsTowerFinishesComingDown(GameTestHelper context) {
        Restart r = new Restart(context, "TowerRestoreDescentGT");
        r.watch(r::halfwayDown, null, () -> {
            if (!r.isDown()) {
                return false;
            }
            List<String> lines = r.afterRestore();
            r.requireBackOnTheFloor(lines);
            r.c.require(r.c.world().getBlockState(r.target).isAir(),
                    "the dirt the task had mined is back in the world: " + r.c.tail(lines, ""));
            return true;
        });
    }

    @GameTest(environment = ENV + "restored_task_waits_while_the_bot_takes_its_tower_down_first", maxTicks = 3000)
    public void restoredTaskWaitsWhileTheBotTakesItsTowerDownFirst(GameTestHelper context) {
        // The mission restores a new task for the interrupted work; it knows nothing of the old tower.
        MineTask again = new MineTask(Blocks.DIRT, 1);
        Restart r = new Restart(context, "TowerRestoreTaskGT");
        r.watch(r::halfwayUp, bot -> TaskManager.INSTANCE.assign(bot, again,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_tower_restore")), () -> {
            r.noteHealth();
            if (again.state() != TaskState.COMPLETED) {
                return false;
            }
            List<String> lines = r.c.log();
            int restored = r.c.indexOf(lines, "tower_restored");
            int down = r.c.indexOf(lines.subList(Math.max(restored, 0), lines.size()), "tower_orphan_descended", "owner='restart'");
            int climb = r.c.indexOf(lines.subList(Math.max(restored, 0), lines.size()), "mine_pillar_start");
            r.c.require(restored >= 0 && down >= 0 && climb >= 0 && down < climb,
                    "the restored task started before the saved tower was down: " + r.c.tail(lines, ""));
            r.c.require(r.c.world().getBlockState(r.target).isAir() && InventoryAction.countItem(r.restored(), Items.DIRT) >= 1,
                    "the restored task did not finish the mine: " + r.c.tail(lines, ""));
            r.c.require(r.restored().blockPosition().equals(r.c.feet) && r.towerBlocksStanding() == 0,
                    "the bot did not end on the floor with no tower left: " + r.c.tail(lines, ""));
            r.requireNoFall();
            return true;
        });
    }

    @GameTest(environment = ENV + "save_from_before_the_tower_field_restores_as_it_always_did", maxTicks = 3000)
    public void saveFromBeforeTheTowerFieldRestoresAsItAlwaysDid(GameTestHelper context) {
        Restart r = new Restart(context, "TowerRestoreOldSaveGT");
        r.removeFromSave = json -> require(r.c, json.remove("towerBase") != null, "the new format did not write towerBase");
        int[] since = {0};
        r.watch(r::halfwayUp, null, () -> {
            if (!r.isRestored() || ++since[0] < 100) {
                return false;
            }
            List<String> lines = r.c.log();
            r.c.require(r.record.towerBase() == null, "the old record decoded a tower: " + r.record.towerBase());
            r.c.require(r.c.count(lines, "tower_restored") == 0 && r.c.count(lines, "tower_restore_rejected") == 0
                            && r.c.count(lines, "tower_orphan_descended") == 0,
                    "an old save must restore with no tower business at all: " + r.c.tail(lines, "tower_"));
            r.c.require(r.restored().blockPosition().getY() == r.restoredAt.getY()
                            && r.restored().blockPosition().getX() == r.c.feet.getX()
                            && r.restored().blockPosition().getZ() == r.c.feet.getZ(),
                    "the restored bot moved off the spot it was saved on: " + r.restored().blockPosition());
            r.c.require(r.towerBlocksStanding() == r.towerAtRestore,
                    "the tower changed with nobody to take it down: " + r.towerBlocksStanding() + " of " + r.towerAtRestore);
            r.c.require(InventoryAction.countItem(r.restored(), Items.COBBLESTONE) == r.cobbleAtSave,
                    "the old record restored a different inventory: " + InventoryAction.countItem(r.restored(), Items.COBBLESTONE));
            return true;
        });
    }

    @GameTest(environment = ENV + "restored_bot_that_is_not_on_its_tower_does_nothing_special", maxTicks = 3000)
    public void restoredBotThatIsNotOnItsTowerDoesNothingSpecial(GameTestHelper context) {
        // The world no longer has the tower the save names (an edited or rolled-back world): the safe-spawn snap puts the bot
        // on the floor, at the foot of the column the tower stood in.
        Restart r = new Restart(context, "TowerRestoreGoneGT");
        r.whileUnloaded = () -> {
            for (int up = 0; up < SUPPORTS; up++) {
                r.c.set(0, up, 0, Blocks.AIR);
            }
        };
        int[] since = {0};
        r.watch(r::halfwayUp, null, () -> {
            if (!r.isRestored() || ++since[0] < 60) {
                return false;
            }
            List<String> lines = r.c.log();
            r.c.require(r.record.towerBase() != null, "fixture: the bot was saved with no tower, so nothing is under test");
            r.c.require(r.c.count(lines, "tower_restore_rejected", "reason='not_on_tower'") == 1
                            && r.c.count(lines, "tower_restored") == 0 && r.c.count(lines, "tower_orphan_descended") == 0
                            && r.c.count(lines, "tower_orphan_descent_failed") == 0,
                    "a bot off its tower must restore no tower and say so: " + r.c.tail(lines, "tower_"));
            r.c.require(r.restored().blockPosition().equals(r.c.feet) && r.restored().onGround(),
                    "the bot should stand on the floor where the safe spawn put it: " + r.restored().blockPosition());
            r.c.require(r.c.world().getBlockState(r.c.at(0, -1, 0)).is(Blocks.STONE) && r.towerBlocksStanding() == 0,
                    "the floor under the column was touched");
            r.requireNoFall();
            return true;
        });
    }

    @GameTest(environment = ENV + "death_respawn_does_not_resurrect_the_tower", maxTicks = 3000)
    public void deathRespawnDoesNotResurrectTheTower(GameTestHelper context) {
        Restart r = new Restart(context, "TowerRestoreDeathGT");
        r.savedWithTower = false;
        r.beforeSave = () -> {
            AIPlayerEntity bot = r.c.bot;
            BotRecord onTheTower = BotPersistence.capture(bot);
            r.c.require(TowerBaseCodec.encode(r.c.feet).equals(onTheTower.towerBase()),
                    "fixture: the bot on its tower was not saved with it, so the death proves nothing: " + onTheTower.towerBase());
            bot.setHealth(0.0F);
            bot.die(r.c.world().damageSources().genericKill());
            r.c.require(bot.isDeadOrDying(), "fixture: the bot did not die");
            r.c.require(AIPlayerManager.INSTANCE.respawnDeadBot(bot), "respawnDeadBot did not revive the bot");
            r.c.require(!sameColumn(bot, r.c.feet),
                    "fixture: the revived bot is back in the tower's column, so it is on the tower again, not at the spawn");
        };
        int[] since = {0};
        r.watch(r::halfwayUp, null, () -> {
            if (++since[0] < 40) {
                return false;
            }
            List<String> lines = r.c.log();
            r.c.require(r.c.count(lines, "tower_restored") == 0 && r.c.count(lines, "tower_restore_rejected") == 0
                            && r.c.count(lines, "tower_orphan_descended") == 0,
                    "a tower came back with the respawned bot: " + r.c.tail(lines, "tower_"));
            r.c.require(BotPersistence.capture(r.restored()).towerBase() == null, "the respawned bot is saved with a tower");
            return true;
        });
    }

    @GameTest(environment = ENV + "bot_that_dies_before_its_restored_tower_is_down_does_not_carry_it_on", maxTicks = 3000)
    public void botThatDiesBeforeItsRestoredTowerIsDownDoesNotCarryItOn(GameTestHelper context) {
        Restart r = new Restart(context, "TowerRestoreDiesGT");
        boolean[] done = {false};
        int[] since = {0};
        r.watch(r::halfwayUp, bot -> {
            List<String> lines = r.c.log();
            r.c.require(r.c.count(lines, "tower_restored") == 1,
                    "fixture: the tower was not restored, so there is none to die on: " + r.c.tail(lines, "tower_"));
            bot.setHealth(0.0F);
            bot.die(r.c.world().damageSources().genericKill());
            r.c.require(AIPlayerManager.INSTANCE.respawnDeadBot(bot), "respawnDeadBot did not revive the bot");
            r.c.require(!sameColumn(bot, r.c.feet), "fixture: the revived bot is back in the tower's column");
            r.c.require(BotPersistence.capture(bot).towerBase() == null,
                    "a bot revived at the spawn was saved with the restored tower it died on");
            done[0] = true;
        }, () -> {
            if (!done[0] || ++since[0] < 60) {
                return false;
            }
            List<String> lines = r.c.log();
            r.c.require(BotPersistence.capture(r.restored()).towerBase() == null,
                    "the tower came back to the revived bot: " + r.c.tail(lines, "tower_"));
            r.c.require(r.c.count(lines, "tower_orphan_descended") == 0 && r.c.count(lines, "tower_orphan_descent_failed") == 0,
                    "the revived bot went on taking down a tower it is no longer on: " + r.c.tail(lines, "tower_"));
            return true;
        });
    }

    private static boolean sameColumn(AIPlayerEntity bot, BlockPos column) {
        return bot.blockPosition().getX() == column.getX() && bot.blockPosition().getZ() == column.getZ();
    }

    private static void require(Case c, boolean condition, String message) {
        c.require(condition, message);
    }

    /**
     * One bot, one real task, one restart. {@code watch} drives all of it from the GameTest tick: the first life runs until the
     * moment the restart should catch it, which is saved and unloaded, and the restored bot is checked by the case's own verdict.
     */
    private static final class Restart {
        final Case c;
        final BlockPos target;
        BotRecord record;
        /** What the test does to the saved JSON before it is read back (an old save lacks a field). */
        Consumer<JsonObject> removeFromSave = json -> { };
        /** What the first life goes through between the moment it is caught and the save (a death). */
        Runnable beforeSave = () -> { };
        /** Whether the bot is on its tower when it is saved: a death in between leaves it at the spawn. */
        boolean savedWithTower = true;
        /** What happens to the world while the server is down. */
        Runnable whileUnloaded = () -> { };
        BlockPos restoredAt;
        int cobbleAtSave;
        int towerAtRestore;
        private float healthAtRestore;
        private float lowestHealth;
        private int gap = -1;
        private boolean restored;

        Restart(GameTestHelper context, String name) {
            this.c = new Case(context, name, 0, 9);
            this.target = c.at(0, TARGET_UP, 0);
            c.set(0, TARGET_UP, 0, Blocks.DIRT);
            c.give(new ItemStack(Items.WOODEN_SHOVEL), new ItemStack(Items.WOODEN_PICKAXE), new ItemStack(Items.COBBLESTONE, 8));
            c.budget = TICK_BUDGET;
            TaskManager.INSTANCE.assign(c.bot, new MineTask(Blocks.DIRT, 1),
                    TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_tower_restore"));
        }

        /**
         * {@code stopNow} says when the first life is caught; {@code onRestored} (may be null) is what the restart does for the
         * restored bot the tick it is back (the mission restoring a task); {@code verdict} judges the rest.
         */
        void watch(BooleanSupplier stopNow, Consumer<AIPlayerEntity> onRestored, BooleanSupplier verdict) {
            c.watch(() -> {
                if (record == null) {
                    if (stopNow.getAsBoolean()) {
                        save();
                    }
                    return false;
                }
                if (!restored) {
                    if (--gap <= 0) {
                        restore(onRestored);
                    }
                    return false;
                }
                noteHealth();
                return verdict.getAsBoolean();
            });
        }

        /** Mid-climb: the bot stands on the third block of its tower, with three more to place. */
        boolean halfwayUp() {
            return c.bot.onGround() && c.bot.blockPosition().getY() == c.feet.getY() + SUPPORTS / 2
                    && sameColumn(c.bot, c.feet);
        }

        /** Mid-descent: the bot has been to the top and is coming down, with some of the tower already taken down. */
        boolean halfwayDown() {
            int y = c.bot.blockPosition().getY();
            return c.bot.onGround() && c.maxFeetY >= c.feet.getY() + SUPPORTS && y <= c.maxFeetY - 2
                    && y >= c.feet.getY() + 2 && sameColumn(c.bot, c.feet);
        }

        private void save() {
            beforeSave.run();
            AIPlayerEntity bot = c.bot;
            cobbleAtSave = InventoryAction.countItem(bot, Items.COBBLESTONE);
            BotRecord captured = BotPersistence.capture(bot);
            c.require(savedWithTower
                            ? TowerBaseCodec.encode(c.feet).equals(captured.towerBase()) : captured.towerBase() == null,
                    (savedWithTower ? "the bot standing on its tower was saved without it: "
                            : "a bot that is not on its tower was saved with one: ") + captured.towerBase());
            record = roundTrip(captured);
            unload();
        }

        /** What a restart does to a bot: the runtime is gone, the world keeps what was built in it. */
        private void unload() {
            c.require(AIPlayerManager.INSTANCE.despawn(c.context.getLevel().getServer(), c.name), "despawn failed");
            whileUnloaded.run();
            gap = RESTART_GAP_TICKS;
        }

        private void restore(Consumer<AIPlayerEntity> onRestored) {
            restored = true;
            AIPlayerEntity bot = AIPlayerManager.INSTANCE.respawnFromRecord(c.context.getLevel().getServer(), record)
                    .orElseThrow(() -> new IllegalStateException("restore failed"));
            restoredAt = bot.blockPosition().immutable();
            towerAtRestore = towerBlocksStanding();
            healthAtRestore = bot.getHealth();
            lowestHealth = healthAtRestore;
            if (onRestored != null) {
                onRestored.accept(bot);
            }
        }

        BotRecord roundTrip(BotRecord saved) {
            RuntimeSnapshot snapshot = new RuntimeSnapshot(RuntimeSnapshot.CURRENT_SCHEMA, "t", "test", "s",
                    List.of(new PersistedBot(saved, MissionRuntimeRecord.empty())), List.of());
            JsonObject root = JsonParser.parseString(RuntimeSnapshotCodec.encode(snapshot)).getAsJsonObject();
            removeFromSave.accept(root.getAsJsonArray("bots").get(0).getAsJsonObject().getAsJsonObject("bot"));
            RuntimeSnapshotCodec.DecodeResult decoded = RuntimeSnapshotCodec.decode(new StringReader(root.toString()));
            c.require(decoded.status() == RuntimeSnapshotCodec.Status.OK, "the save did not read back: " + decoded.status());
            return decoded.snapshot().bots().getFirst().bot();
        }

        AIPlayerEntity restored() {
            return AIPlayerManager.INSTANCE.getByName(c.name).orElse(null);
        }

        boolean isRestored() {
            return restored;
        }

        void noteHealth() {
            AIPlayerEntity bot = restored();
            if (bot != null) {
                lowestHealth = Math.min(lowestHealth, bot.getHealth());
            }
        }

        /** The bot is on the floor of its tower with nothing left of it standing, and the descent has closed (its last item is picked up). */
        boolean isDown() {
            AIPlayerEntity bot = restored();
            return bot != null && bot.onGround() && bot.blockPosition().equals(c.feet) && towerBlocksStanding() == 0
                    && c.count(c.log(), "tower_orphan_descended") > 0;
        }

        int towerBlocksStanding() {
            int standing = 0;
            for (int up = 0; up < SUPPORTS; up++) {
                if (!c.world().getBlockState(c.at(0, up, 0)).isAir()) {
                    standing++;
                }
            }
            return standing;
        }

        /** The log lines of the restored bot's own life. */
        List<String> afterRestore() {
            List<String> lines = c.log();
            int from = c.indexOf(lines, "tower_restored");
            c.require(from >= 0, "the tower was not restored: " + c.tail(lines, "tower_"));
            return lines.subList(from, lines.size());
        }

        void requireBackOnTheFloor(List<String> lines) {
            c.require(c.count(lines, "tower_orphan_descended", "owner='restart'", "blocks='" + towerAtRestore + "'") == 1,
                    "the restored tower was not taken down block by block (" + towerAtRestore + " standing at the restore): "
                            + c.tail(lines, "tower_"));
            c.require(c.count(lines, "tower_orphan_descent_failed") == 0 && c.count(lines, "tower_restore_rejected") == 0,
                    "the restored descent was refused: " + c.tail(lines, "tower_"));
            c.require(InventoryAction.countItem(restored(), Items.COBBLESTONE) == 8,
                    "the tower's blocks were not picked up again: " + InventoryAction.countItem(restored(), Items.COBBLESTONE));
            requireNoFall();
        }

        void requireNoFall() {
            c.require(lowestHealth >= healthAtRestore,
                    "the bot was hurt coming down: health " + healthAtRestore + " -> " + lowestHealth);
        }
    }
}

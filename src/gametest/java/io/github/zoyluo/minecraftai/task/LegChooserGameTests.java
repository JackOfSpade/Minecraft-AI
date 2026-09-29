package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.assist.AssistMode;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistConfig;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistRuntime;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import io.github.zoyluo.minecraftai.task.SensingArena.Room;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Real-Minecraft GameTest for mining-assist phase P4 R2a (design 5.3, {@code MINING_ASSIST_DESIGN.md} section
 * 8.2 hook 14): {@code OreDigTask.publishStripSuccessor}'s {@code legChooserActive} branch, running the real
 * {@code OreDigTask} against a live server, never a JUnit fake. Design 5.3's own "Done when" bar for this phase
 * is "an empty model reproduces the spiral bit for bit... Existing cursor GameTests unchanged" -- {@link
 * LegChooserTest} and {@link CoverageGridTest} already prove the pure {@code LegChooser}/{@code CoverageGrid}
 * kernels reproduce that spiral in isolation; this file is the "does hook 14's wiring actually stay inert with
 * the shipped default" proof, in the style of the sibling file {@link OreDigOpportunisticGameTests} (the sealed
 * stone-room/forceEnable fixture idiom this file copies).
 *
 * <p>{@code explore.legChooser} defaults to {@code false} ({@link MiningAssistConfig.Explore#DEFAULTS}), and
 * every other OreDig GameTest in this package already runs with that default -- so their continued passing is
 * itself part of "existing cursor GameTests unchanged". What none of them assert directly is the exact
 * checkpoint value hook 14 publishes for the very first leg; this test reads {@code OreDigTask.checkpoint()}
 * straight from a live mission and requires the fixed pre-L1 answer (design 5.3: {@code STRIP_DIRS} index 0 /
 * NORTH, leg length {@code STRIP_SEGMENT} = 48), so a future change to hook 14's {@code legChooserActive} gate
 * that silently flips the shipped default is caught here even though L1 itself stays off.</p>
 */
public final class LegChooserGameTests {

    private static final String ENV_PREFIX = "minecraftai-gametest:leg_chooser_game_tests_";

    // ---------------------------------------------------------------------------------------------
    // Hook 14 stays inert with the shipped default: the very first leg is NORTH / STRIP_SEGMENT (design 5.3)
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = ENV_PREFIX + "off_by_default_reproduces_the_fixed_clockwise_spiral_start", maxTicks = 500)
    public void offByDefaultReproducesTheFixedClockwiseSpiralStart(GameTestHelper context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(10, -6, 6, -6, 6, 4);

        AIPlayerEntity bot = h.spawn("LegChooserOffGT", room, 0, 0);
        // Harness default explore config (explore.legChooser == Explore.DEFAULTS.legChooser() == false);
        // only DETOUR/harness plumbing is installed, exactly like OreDigOpportunisticGameTests's own
        // enableDetour -- forcing never bypasses the origin/audit/TPS gates, only the harness default.
        h.enableDetour(bot, 256);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        Progress p = new Progress();
        // No ore anywhere in the sealed room: nearestOre/prospect/richZone all fall through immediately
        // (design 5.3's own trigger note, ~:1439), so stripMine -- and hook 14's publishStripSuccessor --
        // runs on the mission's very first scan, well inside this test's budget.
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 40);

        context.failIfEver(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                h.assertStrict(bot, "leg_chooser_off_start");
                TaskManager.INSTANCE.assign(bot, task,
                        TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_leg_chooser_off"));
                p.assignedAt = p.tick;
                return;
            }
            requireNotFailed(h, task);
            Map<String, String> live = task.checkpoint();
            String direction = live.get("direction");
            if (direction == null || "-1".equals(direction)) {
                // publishStripSuccessor has not run yet for this mission (stripDirIndex still the
                // pre-first-tick sentinel); keep waiting inside the budget.
                h.require(p.tick - p.assignedAt < 450,
                        "hook 14's publishStripSuccessor never ran within the test budget");
                return;
            }
            h.require("0".equals(direction),
                    "hook 14 must reproduce today's fixed NORTH (STRIP_DIRS index 0) start when"
                            + " explore.legChooser is off (the shipped default); got direction=" + direction);
            h.require("48".equals(live.get("leg_length")),
                    "hook 14 must reproduce today's fixed STRIP_SEGMENT (48) first-leg length when"
                            + " explore.legChooser is off (the shipped default); got leg_length="
                            + live.get("leg_length"));
            h.assertStrict(bot, "leg_chooser_off_end");
            h.pass();
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // Shared fixture plumbing (OreDigOpportunisticGameTests's Harness/Room pattern)
    // ---------------------------------------------------------------------------------------------

    private static void requireNotFailed(Harness h, OreDigTask task) {
        h.require(task.state() != TaskState.FAILED && task.state() != TaskState.CANCELLED,
                "mission ended as " + task.state() + ":" + task.failureReason());
    }

    private static MiningAssistConfig detourConfig(int raysPerTick) {
        JsonObject sense = new JsonObject();
        sense.addProperty("raysPerTick", raysPerTick);
        JsonObject section = new JsonObject();
        section.add("sense", sense);
        JsonObject root = new JsonObject();
        root.add(MiningAssistConfig.FILE_SECTION, section);
        // DETOUR mode, harnessOff=true (the shipped harness default, design M4/M38): the test opts its
        // one bot past it with forceEnable, which never bypasses the origin/audit/TPS gates. The
        // "explore" section is left unset, so explore.legChooser stays at Explore.DEFAULTS (off).
        return MiningAssistConfig.parse(root, key -> null, AssistMode.DETOUR, true);
    }

    /** Per-test tick bookkeeping (mirrors {@code OreDigOpportunisticGameTests.Progress}). */
    private static final class Progress {
        int tick;
        int assignedAt = -1;
    }

    /** Cleanup-on-failure, strict-capability and DETOUR-mode config plumbing shared by every test. */
    private static final class Harness {
        final GameTestHelper context;
        final List<String> bots = new ArrayList<>();
        final List<Room> rooms = new ArrayList<>();
        final List<UUID> forced = new ArrayList<>();
        MiningAssistConfig restoreConfig;
        boolean tpsOverridden;
        boolean done;

        Harness(GameTestHelper context) {
            this.context = context;
        }

        Room newRoom(int relY, int minDx, int maxDx, int minDz, int maxDz, int height) {
            Room room = new Room(context, relY, minDx, maxDx, minDz, maxDz, height);
            rooms.add(room);
            return room;
        }

        AIPlayerEntity spawn(String name, Room room, int dx, int dz) {
            ServerLevel world = room.world;
            BlockPos feet = room.at(dx, 0, dz);
            bots.add(name);
            var spawned = AIPlayerManager.INSTANCE.spawn(
                    world.getServer(), name, world, Vec3.atBottomCenterOf(feet), 0.0F, 0.0F, GameType.SURVIVAL);
            if (spawned.isEmpty()) {
                fail("failed to spawn " + name + " (a bot of that name is still alive)");
            }
            AIPlayerEntity bot = spawned.get();
            bot.teleportTo(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
            bot.setHealth(bot.getMaxHealth());
            bot.getFoodData().setFoodLevel(20);
            bot.getFoodData().setSaturation(5.0F);
            return bot;
        }

        /** Installs a DETOUR-mode config (design G.6, harnessOff=true) and force-enables one bot past it. */
        void enableDetour(AIPlayerEntity bot, int raysPerTick) {
            if (restoreConfig == null) {
                restoreConfig = MiningAssistRuntime.config();
            }
            MiningAssistRuntime.install(detourConfig(raysPerTick));
            MiningAssistRuntime.setTestTpsDegraded(Boolean.FALSE);
            tpsOverridden = true;
            MiningAssistRuntime.forceEnable(bot.getUUID());
            forced.add(bot.getUUID());
        }

        void require(boolean condition, String message) {
            if (!condition) {
                fail(message);
            }
        }

        void fail(String message) {
            cleanup();
            context.fail(Component.nullToEmpty(message));
        }

        /** Runs one tick of a test body; anything thrown cleans up first so a failure never leaves a bot behind. */
        void guard(Runnable body) {
            try {
                body.run();
            } catch (RuntimeException | Error failure) {
                cleanup();
                throw failure;
            }
        }

        void cleanup() {
            done = true;
            for (String name : new ArrayList<>(bots)) {
                AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), name);
            }
            bots.clear();
            for (Room room : rooms) {
                try {
                    room.clear();
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
            rooms.clear();
            for (UUID id : forced) {
                MiningAssistRuntime.clearForced(id);
            }
            forced.clear();
            if (restoreConfig != null) {
                MiningAssistRuntime.install(restoreConfig);
                restoreConfig = null;
            }
            if (tpsOverridden) {
                MiningAssistRuntime.setTestTpsDegraded(null);
                tpsOverridden = false;
            }
        }

        void pass() {
            cleanup();
            context.succeed();
        }

        void assertStrict(AIPlayerEntity bot, String label) {
            require(io.github.zoyluo.minecraftai.MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                    "GameTest must run under strict_survival, got " + io.github.zoyluo.minecraftai.MinecraftAiConfig.get().profile());
            for (PrivilegedCapability capability : PrivilegedCapability.values()) {
                require(!CapabilityRuntime.decide(bot, capability, label).allowed(),
                        "strict_survival unexpectedly allowed " + capability);
            }
        }
    }
}

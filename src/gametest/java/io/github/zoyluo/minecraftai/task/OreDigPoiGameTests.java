package io.github.zoyluo.minecraftai.task;

import com.google.gson.JsonObject;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationGate;
import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.brain.PoiAdvisor;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLogWriter;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.assist.AssistMode;
import io.github.zoyluo.minecraftai.mining.assist.BotEdits;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistConfig;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistRegistry;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistRuntime;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistState;
import io.github.zoyluo.minecraftai.mining.assist.PoiCache;
import io.github.zoyluo.minecraftai.mining.assist.PoiConsultBudget;
import io.github.zoyluo.minecraftai.mining.assist.PoiDetector;
import io.github.zoyluo.minecraftai.mining.assist.PoiLabeler;
import io.github.zoyluo.minecraftai.mining.assist.PoiPrompt;
import io.github.zoyluo.minecraftai.mining.assist.PoiRegistry;
import io.github.zoyluo.minecraftai.mining.assist.PoiScorer;
import io.github.zoyluo.minecraftai.coordination.PoiCoordinator;
import io.github.zoyluo.minecraftai.coordination.MiningAssistCoordinator;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.runtime.IntentController;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import io.github.zoyluo.minecraftai.task.SensingArena.Room;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.zoyluo.minecraftai.task.SensingArena.botLog;

/**
 * Real-Minecraft GameTests for the P2 (R3 deterministic POI) stop/notify flow (mining-assist design 6.1-6.5,
 * P2 R3 contract section 4): the actual {@code PoiDetector}/{@code PoiRegistry}/{@code MandatoryLatch}/
 * {@code PoiDecisionPolicy}/{@code PoiNotice}/{@code coordination.PoiCoordinator} running a real server, real
 * blocks and entities and real task/pause plumbing -- never a scripted fake of the classes under test. In the
 * style of the sibling files in this package ({@code OreDigOpportunisticGameTests},
 * {@code OreDigOpportunisticLifecycleGameTests}) and of {@code mining.assist.MiningAssistSenseGameTests} (P0),
 * whose sealed-room/forceEnable/environment-JSON fixture idiom this file copies.
 *
 * <h2>Two harness idioms in this file, both driving real production code</h2>
 * <ul>
 *   <li><b>Organic sensing</b> ({@link #mineshaftPaletteTriggersCertainStopAndNotify},
 *       {@link #wardenRiskAlwaysStops}, {@link #playerBaseDowngradesToConsultNotStop}): real blocks/entities are
 *       placed, a real bot is given a real (frozen, matching {@code MiningAssistSenseGameTests.freeze}) mission
 *       task, and the test waits for {@code MiningAssistCoordinator.sense()} to run the real
 *       {@code PoiDetector.evaluate} and dispatch to the real {@code PoiCoordinator.onCandidate} on its own
 *       schedule -- exactly the path a live bot uses.</li>
 *   <li><b>Direct dispatch</b> (the remaining nine tests): a real bot with a real active task (an actual
 *       {@code OreDigTask}/{@code DigDownTask}/frozen {@code MineTask} assigned through {@code TaskManager}) is
 *       handed a hand-built {@code PoiDetector.Result} via one direct call to the real, production
 *       {@code PoiCoordinator.INSTANCE.onCandidate(...)} -- the exact call {@code sense()} itself makes after a
 *       real {@code PoiDetector.evaluate()}. This substitutes only the sensor's evidence-gathering stage (whose
 *       classification is already proven end-to-end by the three organic tests above and exhaustively by
 *       {@code PoiScorerTest}/{@code PoiDetectorTest}); every other class in the chain -- {@code PoiCoordinator},
 *       {@code PoiRegistry}, {@code MandatoryLatch}, {@code MissionAssistLedger}, {@code IntentController},
 *       {@code TaskManager}, {@code DigDownTask} -- runs unmodified, real, and observably. This trade-off is
 *       deliberate: these nine tests are about coordinator/registry/latch/pause-stack/task-notice-variant
 *       <i>mechanics</i>, not about proving the detector can classify a mineshaft from raycast evidence (that is
 *       what the organic tests and {@code PoiScorerTest} already prove), and pinning a bot's exact geometry
 *       relative to hand-placed evidence while it is simultaneously digging (a real {@code DigDownTask} moves
 *       downward every few ticks) would make the very mechanics these tests exist to pin down flaky on the
 *       sensor's own timing instead.</li>
 * </ul>
 *
 * <h2>A real, documented harness limitation: no player-message capture</h2>
 * <p>{@code PoiCoordinator.sendNotice} sends through {@code BrainCoordinator.sendPanelChat} (a no-op unless a
 * real client has subscribed over the mod's own networking channel) and {@code ServerPlayer.sendSystemMessage}
 * (routed through each bot's {@code FakeClientConnection}, whose {@code send(Packet)} is an intentional no-op --
 * verified by reading {@code network/FakeClientConnection.java} and {@code network/DeliveredPackets.java} before
 * writing this file). Neither path leaves anything this harness can read back, and grepping the whole
 * {@code src/gametest} tree turned up no existing fixture that captures delivered chat either. So instead of
 * inventing new capture plumbing (out of scope for writing test bodies against the existing harness), every
 * test below verifies the literal notice <em>text</em> is {@code PoiNoticeTest}'s job (it already covers all
 * four templates byte-for-byte) and verifies here only what a real server actually leaves observable: the real
 * pause ({@code TaskManager.isUserPaused}/{@code peekPaused}), the real dedupe/latch state
 * ({@code PoiRegistry}/{@code MandatoryLatch}), and the real structured log line
 * ({@code BotLog.task(bot, "poi_stop"/"poi_fyi"/"poi_case_closed"/"poi_restart_rehydrated", ...)}, read back the
 * same way the sibling files' own {@code botLog}/event-scan helpers already do). Where the contract's test list
 * names an authorized-vs-broadcast recipient split, this file instead exercises the real
 * {@code BotAuthorizationGate.canCommand} decision {@code sendNotice} depends on, against a real second bot
 * spawned as the subject's owner (an ordinary {@code AIPlayerManager.spawn(..., ownerUuid)} call, not a fake) --
 * see {@link #noticeReachesOnlyAuthorizedPlayers}.
 */
public final class OreDigPoiGameTests {

    /** See {@link #wardenRiskAlwaysStops}'s javadoc: outside the combat layer's 17-block warden pressure range
     * ({@code CombatCore.WARDEN_SONIC_BOOM_RANGE} + 2), so it needs a perception radius above the default 16. */
    private static final int WARDEN_STANDOFF_BLOCKS = 19;
    /** The perception radius the far-warden fixture runs with: the warden must be inside the POI sensor's radius. */
    private static final int WARDEN_TEST_PERCEPTION_RADIUS = 22;
    /** A warden inside {@code CombatCore}'s warden pressure range: real hostile pressure, so the bot evades. */
    private static final int WARDEN_PRESSURE_STANDOFF_BLOCKS = 9;

    // ---------------------------------------------------------------------------------------------
    // Deterministic stop / notify (organic sensing)
    // ---------------------------------------------------------------------------------------------

    /**
     * A real mineshaft-like palette (rails, fence/plank support frames, cobwebs, a chest and a chest minecart --
     * the exact evidence combination {@code MiningAssistSenseGameTests.minePoiPaletteScoresAsStructureInShadow}
     * already proves reaches {@code PoiScorer.Band.POSSIBLE} or {@code STRUCTURE_CERTAIN} in shadow) reaches
     * {@code STRUCTURE_CERTAIN} for real (rails and cobwebs both being present keeps {@code habitationLike}
     * false regardless of any habitation item, so the certain band is never downgraded here -- that is
     * {@link #playerBaseDowngradesToConsultNotStop}'s job), and P2's coordinator turns that into a real stop:
     * {@code IntentController} pauses the bot, {@code TaskManager.isUserPaused} becomes true, and a
     * {@code poi_stop} log line names the mineshaft label.
     */
    @GameTest(environment = "minecraftai-gametest:ore_dig_poi_game_tests_mineshaft_palette_triggers_certain_stop_and_notify",
            maxTicks = 900)
    public void mineshaftPaletteTriggersCertainStopAndNotify(GameTestHelper context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(10, -9, 9, -1, 1, 3);
        for (int x : new int[] {-8, -6, -4, -2, 2, 4, 6, 8}) {
            room.set(x, 0, 0, Blocks.RAIL);
        }
        for (int x : new int[] {-7, -3, 3, 7}) {
            room.set(x, 0, -1, Blocks.OAK_FENCE);
            room.set(x, 1, -1, Blocks.OAK_FENCE);
            room.set(x, 0, 1, Blocks.OAK_FENCE);
            room.set(x, 1, 1, Blocks.OAK_FENCE);
            room.set(x, 2, -1, Blocks.OAK_PLANKS);
            room.set(x, 2, 0, Blocks.OAK_PLANKS);
            room.set(x, 2, 1, Blocks.OAK_PLANKS);
        }
        room.set(-5, 2, 0, Blocks.COBWEB);
        room.set(5, 2, 0, Blocks.COBWEB);
        room.set(-8, 1, 1, Blocks.COBWEB);
        room.set(8, 1, -1, Blocks.COBWEB);
        room.set(-4, 0, 1, Blocks.CHEST);
        var minecart = EntityType.CHEST_MINECART.create(room.world, EntitySpawnReason.COMMAND);
        h.require(minecart != null, "could not create a chest minecart");
        BlockPos cart = room.at(4, 0, 0);
        minecart.snapTo(cart.getX() + 0.5D, cart.getY() + 0.0625D, cart.getZ() + 0.5D, 0.0F, 0.0F);
        room.world.addFreshEntity(minecart);
        h.onCleanup(minecart::discard);

        AIPlayerEntity bot = h.spawn("PoiMineshaftGT", room, 0, 0);
        h.enablePoi(bot, null);
        UUID id = bot.getUUID();
        Progress p = new Progress();

        context.failIfEver(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    h.assertStrict(bot, "poi_mineshaft");
                    freeze(bot, TaskOrigin.Kind.MISSION, "gametest_poi_mineshaft");
                    p.assignedAt = p.tick;
                }
                return;
            }
            if (TaskManager.INSTANCE.isUserPaused(bot)) {
                h.require(PoiRegistry.openCase(id) != null, "the bot paused but PoiRegistry has no open case");
                PoiRegistry.OpenCase open = PoiRegistry.openCase(id);
                h.require(open.label().equals(PoiLabeler.MINESHAFT),
                        "the stop's label was " + open.label() + ", expected " + PoiLabeler.MINESHAFT);
                h.require("CERTAIN".equals(open.source()),
                        "the stop's source was " + open.source() + ", expected CERTAIN");
                List<String> lines = botLog(bot.getGameProfile().name());
                h.require(lines != null && hasEvent(lines, "poi_stop"),
                        "no poi_stop log line was written for the mineshaft stop");
                h.assertStrict(bot, "poi_mineshaft_end");
                h.pass();
                return;
            }
            h.require(p.tick - p.assignedAt < 800,
                    "the real mineshaft palette never produced a STRUCTURE_CERTAIN stop within the budget");
        }));
    }

    /**
     * A real, visible {@code Warden} (AI disabled so it cannot itself hurt the bot mid-test, matching this
     * package's own {@code setNoAi(true)} convention for fixture hostiles) reaches
     * {@code PoiScorer.Band.MANDATORY} and stops the bot regardless of two things placed to try to prevent it:
     * a full mineshaft-certain palette in the very same evidence window (proving MANDATORY is decided before,
     * and independent of, the certain/habitation path in {@code PoiScorer.evaluate}), and a pre-seeded
     * {@code PoiRegistry} STOPPED entry at the warden's own centroid (proving {@code PoiCoordinator.onCandidate}
     * checks {@code band == MANDATORY} before it ever calls {@code PoiRegistry.suppressed}, exactly as design
     * 6.4's "a DECLINED or STOPPED registry entry never suppresses a mandatory candidate" requires).
     *
     * <p>The warden sits {@value #WARDEN_STANDOFF_BLOCKS} blocks from the bot: beyond the combat layer's warden
     * pressure range ({@code CombatCore.WARDEN_SONIC_BOOM_RANGE} 15 + 2 = 17 blocks) but inside the POI sensor's
     * radius, which the fixture raises to {@value #WARDEN_TEST_PERCEPTION_RADIUS} for that (the default perception
     * radius, 16, is smaller than the pressure range, so the two only overlap with a larger radius). A warden
     * inside the pressure range is real hostile pressure to the bot's separate danger-response system regardless
     * of AI being disabled: {@code DangerWatcher} evades and pauses the mission before a MANDATORY evaluation
     * runs. That is the intended behaviour there and is pinned by
     * {@link #wardenInsidePressureRangeEvadesInsteadOfPoiStop}; this test pins the other side: a warden the bot
     * can see but is not under pressure from is still a mandatory stop.</p>
     */
    @GameTest(environment = "minecraftai-gametest:ore_dig_poi_game_tests_warden_risk_always_stops", maxTicks = 900)
    public void wardenRiskAlwaysStops(GameTestHelper context) {
        Harness h = new Harness(context);
        h.setPerceptionRadius(WARDEN_TEST_PERCEPTION_RADIUS);
        Room room = h.newRoom(20, -9, WARDEN_STANDOFF_BLOCKS + 1, -1, 1, 3);
        for (int x : new int[] {-8, -6, -4, -2, 2, 4, 6, 8}) {
            room.set(x, 0, 0, Blocks.RAIL);
        }
        for (int x : new int[] {-7, -3, 3, 7}) {
            room.set(x, 0, -1, Blocks.OAK_FENCE);
            room.set(x, 1, -1, Blocks.OAK_FENCE);
            room.set(x, 0, 1, Blocks.OAK_FENCE);
            room.set(x, 1, 1, Blocks.OAK_FENCE);
            room.set(x, 2, -1, Blocks.OAK_PLANKS);
            // No z=0 (dead-centre) plank at x=7: round-1 real-server debugging (BotLog diagnostics: a raw
            // ObservableWorldQuery/Entity#hasLineOfSight raycast between the bot's and warden's eyes) found this exact
            // cell was the actual cause of a real failure here, not a scorer/coordinator bug. The Warden's eye
            // height (~2.47) is much higher than the bot's (~1.62), so the straight sightline from the bot's
            // eye to the warden WARDEN_STANDOFF_BLOCKS away slopes upward and, at x=7 (58% of the way across),
            // sits inside this cell's y=[2,3) box -- it blocks line of sight to the warden even though the
            // room is fully open at the bot's own eye height. Every other cell of this palette (including the
            // z=-1/z=1 planks at x=7 and the z=0 planks at the other three x's) stays, and WOOD_BUILD's
            // strongMinCells cap (8) means dropping one of 12 plank cells changes nothing about the
            // STRUCTURE_CERTAIN score this fixture is built to produce.
            room.set(x, 2, 1, Blocks.OAK_PLANKS);
            if (x != 7) {
                room.set(x, 2, 0, Blocks.OAK_PLANKS);
            }
        }
        room.set(-5, 2, 0, Blocks.COBWEB);
        room.set(5, 2, 0, Blocks.COBWEB);

        AIPlayerEntity bot = h.spawn("PoiWardenGT", room, 0, 0);
        h.enablePoi(bot, null);
        UUID id = bot.getUUID();

        BlockPos wardenFeet = room.at(WARDEN_STANDOFF_BLOCKS, 0, 0);
        Warden warden = EntityType.WARDEN.create(room.world, EntitySpawnReason.COMMAND);
        h.require(warden != null, "could not create a warden fixture");
        warden.setPersistenceRequired();
        warden.setNoAi(true);
        warden.snapTo(wardenFeet.getX() + 0.5D, wardenFeet.getY(), wardenFeet.getZ() + 0.5D,
                180.0F, 0.0F);
        room.world.addFreshEntity(warden);
        h.onCleanup(warden::discard);

        // A STOPPED registry entry at the warden's own centroid: this must never suppress the mandatory stop
        // (mandatory never consults PoiRegistry at all -- see PoiCoordinator.onCandidate's branch order).
        PoiRegistry.record(id, BotEdits.dimensionKey(room.world), wardenFeet, "warden_risk",
                PoiRegistry.State.STOPPED, 0.9D, 0);

        Progress p = new Progress();
        context.failIfEver(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    h.assertStrict(bot, "poi_warden");
                    freeze(bot, TaskOrigin.Kind.MISSION, "gametest_poi_warden");
                    p.assignedAt = p.tick;
                }
                return;
            }
            if (TaskManager.INSTANCE.isUserPaused(bot)) {
                PoiRegistry.OpenCase open = PoiRegistry.openCase(id);
                h.require(open != null, "the bot paused but PoiRegistry has no open case");
                h.require("MANDATORY".equals(open.source()),
                        "the stop's source was " + open.source() + ", expected MANDATORY (the warden must win)");
                h.require("warden_risk".equals(open.label()), "the stop's label was " + open.label());
                List<String> lines = botLog(bot.getGameProfile().name());
                h.require(lines != null && hasEvent(lines, "poi_stop"), "no poi_stop log line was written");
                h.assertStrict(bot, "poi_warden_end");
                h.pass();
                return;
            }
            h.require(p.tick - p.assignedAt < 800,
                    "the visible warden never produced a MANDATORY stop within the budget");
        }));
    }

    /**
     * The other half of {@link #wardenRiskAlwaysStops}: a live warden inside the combat layer's warden pressure
     * range ({@value #WARDEN_PRESSURE_STANDOFF_BLOCKS} blocks, well inside the 17-block range) is hostile pressure
     * and the intended answer is evasion, not a POI stop. {@code DangerWatcher} pauses the frozen mission under a
     * SAFETY-origin {@code EvadeTask} for a HOSTILE threat; the mission is kept paused beneath it (evasion has
     * priority over the notify-and-stop path, exactly as a fight or a fire would).
     */
    @GameTest(environment = "minecraftai-gametest:ore_dig_poi_game_tests_warden_in_pressure_range_evades",
            maxTicks = 900)
    public void wardenInsidePressureRangeEvadesInsteadOfPoiStop(GameTestHelper context) {
        Harness h = new Harness(context);
        h.setPerceptionRadius(WARDEN_TEST_PERCEPTION_RADIUS);
        Room room = h.newRoom(20, -9, WARDEN_PRESSURE_STANDOFF_BLOCKS + 1, -1, 1, 3);
        AIPlayerEntity bot = h.spawn("PoiWardenNearGT", room, 0, 0);
        h.enablePoi(bot, null);

        BlockPos wardenFeet = room.at(WARDEN_PRESSURE_STANDOFF_BLOCKS, 0, 0);
        Warden warden = EntityType.WARDEN.create(room.world, EntitySpawnReason.COMMAND);
        h.require(warden != null, "could not create a warden fixture");
        warden.setPersistenceRequired();
        warden.setNoAi(true);
        warden.snapTo(wardenFeet.getX() + 0.5D, wardenFeet.getY(), wardenFeet.getZ() + 0.5D, 180.0F, 0.0F);
        room.world.addFreshEntity(warden);
        h.onCleanup(warden::discard);

        Progress p = new Progress();
        Task[] mission = {null};
        context.failIfEver(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    h.assertStrict(bot, "poi_warden_near");
                    mission[0] = freeze(bot, TaskOrigin.Kind.MISSION, "gametest_poi_warden_near");
                    p.assignedAt = p.tick;
                }
                return;
            }
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (active instanceof EvadeTask evade) {
                h.require(evade.describe().startsWith("Evading HOSTILE"),
                        "the evasion was for the wrong threat: " + evade.describe());
                h.require(TaskManager.INSTANCE.activeOrigin(bot).map(TaskOrigin::safety).orElse(false),
                        "the warden evasion was not a SAFETY-origin task");
                h.require(TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission[0],
                        "the mission was not kept paused beneath the warden evasion");
                h.pass();
                return;
            }
            h.require(p.tick - p.assignedAt < 400,
                    "a warden inside the pressure range never made the bot evade; active="
                            + (active == null ? "none" : active.name()));
        }));
    }

    /**
     * A structure-certain-worthy palette that is deliberately habitation-like (furniture and a bed, no
     * SPAWNER/SCULK_STRUCT/RAIL/WEB cell anywhere) never hard-stops the bot: {@code PoiScorer.evaluate} downgrades
     * it from {@code STRUCTURE_CERTAIN} to {@code POSSIBLE}, and the default {@code unavailablePolicy} (
     * {@code STOP_IF_STRUCTURE}) still notify-only's it once the fallback's {@code habitationLike} override
     * fires (design 6.7: "not habitation-like" gates the STOP rows). The bot must keep working, never pause.
     */
    @GameTest(environment = "minecraftai-gametest:ore_dig_poi_game_tests_player_base_downgrades_to_consult_not_stop",
            maxTicks = 900)
    public void playerBaseDowngradesToConsultNotStop(GameTestHelper context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(30, -9, 9, -1, 1, 3);
        // Wood floor/walls (WOOD_BUILD) and a stone-brick wall (STONE_BUILD): neither is RAIL/WEB/SPAWNER/
        // SCULK_STRUCT, so nothing here blocks the habitation-like downgrade once a habitation item is present.
        for (int x = -8; x <= 8; x++) {
            room.set(x, 0, -1, Blocks.OAK_PLANKS);
            room.set(x, 0, 1, Blocks.STONE_BRICKS);
        }
        room.set(-6, 0, 0, Blocks.CRAFTING_TABLE);
        room.set(-2, 0, 0, Blocks.FURNACE);
        room.set(2, 0, 0, Blocks.BOOKSHELF);
        room.set(6, 0, 0, Blocks.RED_BED); // a coloured bed: habitationKey handles "*_bed" by suffix
        room.set(0, 0, 0, Blocks.CHEST);

        AIPlayerEntity bot = h.spawn("PoiPlayerBaseGT", room, 0, 0);
        h.enablePoi(bot, null);
        UUID id = bot.getUUID();
        Progress p = new Progress();
        int[] settledSince = {-1};

        context.failIfEver(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            if (p.assignedAt < 0) {
                if (h.settle(bot, p)) {
                    h.assertStrict(bot, "poi_player_base");
                    freeze(bot, TaskOrigin.Kind.MISSION, "gametest_poi_player_base");
                    p.assignedAt = p.tick;
                }
                return;
            }
            h.require(!TaskManager.INSTANCE.isUserPaused(bot),
                    "a habitation-like candidate paused the bot: it must only ever notify-and-continue");
            if (settledSince[0] < 0 && p.tick - p.assignedAt >= 700) {
                settledSince[0] = p.tick;
            }
            if (settledSince[0] < 0) {
                return;
            }
            // Held for a further stretch to give the confirmation hysteresis (2 of the last 3 evaluations,
            // design 6.3) every chance to fire and still never pause the bot.
            if (p.tick - settledSince[0] < 100) {
                return;
            }
            h.require(!TaskManager.INSTANCE.isUserPaused(bot), "the bot paused late in the run");
            List<String> lines = botLog(bot.getGameProfile().name());
            h.require(lines == null || !hasEvent(lines, "poi_stop"),
                    "a poi_stop log line was written for a habitation-like candidate");
            h.assertStrict(bot, "poi_player_base_end");
            h.pass();
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // Mandatory suppression / registry bypass (direct dispatch)
    // ---------------------------------------------------------------------------------------------

    /** A DECLINED cavern-only site recorded at the exact anchor a mandatory candidate later appears at never
     * suppresses it: mandatory is decided before {@code PoiRegistry.suppressed} is ever consulted. */
    @GameTest(environment = "minecraftai-gametest:ore_dig_poi_game_tests_warden_stop_not_suppressed_after_declined_cavern",
            maxTicks = 200)
    public void wardenStopNotSuppressedAfterDeclinedCavern(GameTestHelper context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(40, -3, 3, -3, 3, 3);
        AIPlayerEntity bot = h.spawn("PoiDeclinedCavernGT", room, 0, 0);
        h.enablePoi(bot, null);
        UUID id = bot.getUUID();
        String dim = BotEdits.dimensionKey(room.world);
        BlockPos anchor = room.at(2, 0, 0);

        context.failIfEver(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            if (!h.settle(bot, new Progress())) {
                return;
            }
            h.assertStrict(bot, "poi_declined_cavern");
            freeze(bot, TaskOrigin.Kind.MISSION, "gametest_poi_declined_cavern");
            int tick = MiningAssistRuntime.serverTick(bot);
            PoiRegistry.record(id, dim, anchor, "cavern", PoiRegistry.State.DECLINED, 0.20D, tick);
            h.require(PoiRegistry.suppressed(id, dim, anchor, "cavern", 0.20D, tick),
                    "fixture error: the seeded DECLINED entry does not even suppress a matching non-mandatory candidate");

            MiningAssistState state = MiningAssistRegistry.getOrCreate(bot);
            state.enterDimension(dim);
            PoiDetector.Result mandatory = syntheticResult(
                    PoiScorer.Band.MANDATORY, "warden_risk", anchor, 0.95D, false, "reinforced_deepslate");
            PoiCoordinator.INSTANCE.onCandidate(bot, state, room.world, mandatory, tick);

            h.require(TaskManager.INSTANCE.isUserPaused(bot),
                    "the mandatory candidate was suppressed by an unrelated DECLINED cavern entry");
            PoiRegistry.OpenCase open = PoiRegistry.openCase(id);
            h.require(open != null && "MANDATORY".equals(open.source()) && "warden_risk".equals(open.label()),
                    "the open case after the mandatory candidate was " + open);
            h.assertStrict(bot, "poi_declined_cavern_end");
            h.pass();
        }));
    }

    /** After the player resumes an earlier, unrelated, non-mandatory stop, a fresh mandatory candidate
     * elsewhere still stops the bot: resuming one case never leaves any latch or registry state that could
     * blunt an unrelated later mandatory stop. */
    @GameTest(environment = "minecraftai-gametest:ore_dig_poi_game_tests_warden_stop_not_suppressed_after_player_continue",
            maxTicks = 200)
    public void wardenStopNotSuppressedAfterPlayerContinue(GameTestHelper context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(50, -6, 6, -3, 3, 3);
        AIPlayerEntity bot = h.spawn("PoiResumeThenWardenGT", room, 0, 0);
        h.enablePoi(bot, null);
        UUID id = bot.getUUID();
        String dim = BotEdits.dimensionKey(room.world);
        BlockPos earlierAnchor = room.at(-4, 0, 0);
        BlockPos mandatoryAnchor = room.at(4, 0, 0);

        context.failIfEver(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            if (!h.settle(bot, new Progress())) {
                return;
            }
            h.assertStrict(bot, "poi_resume_then_warden");
            freeze(bot, TaskOrigin.Kind.MISSION, "gametest_poi_resume_then_warden");
            MiningAssistState state = MiningAssistRegistry.getOrCreate(bot);
            state.enterDimension(dim);
            int tick = MiningAssistRuntime.serverTick(bot);

            // An unrelated earlier structure-certain stop, then the player resumes it.
            PoiDetector.Result earlier = syntheticResult(
                    PoiScorer.Band.STRUCTURE_CERTAIN, PoiLabeler.MINESHAFT, earlierAnchor, 0.90D, false, "");
            PoiCoordinator.INSTANCE.onCandidate(bot, state, room.world, earlier, tick);
            h.require(TaskManager.INSTANCE.isUserPaused(bot), "fixture error: the earlier stop never paused the bot");
            h.require(PoiRegistry.openCase(id) != null, "fixture error: the earlier stop never opened a case");

            IntentController.INSTANCE.resume(bot, IntentController.ControlOrigin.PLAYER_COMMAND,
                    "gametest_resume_unrelated");
            PoiCoordinator.INSTANCE.tick(bot, tick + 1);
            h.require(!TaskManager.INSTANCE.isUserPaused(bot), "the resume never actually unpaused the bot");
            h.require(PoiRegistry.openCase(id) == null,
                    "the earlier case is still open after the resume was tended by PoiCoordinator.tick");

            // A fresh mandatory candidate, at a different site, must still stop the bot.
            PoiDetector.Result mandatory = syntheticResult(
                    PoiScorer.Band.MANDATORY, "warden_risk", mandatoryAnchor, 0.95D, false, "reinforced_deepslate");
            PoiCoordinator.INSTANCE.onCandidate(bot, state, room.world, mandatory, tick + 2);
            h.require(TaskManager.INSTANCE.isUserPaused(bot),
                    "a fresh mandatory candidate was suppressed after an earlier, unrelated resume");
            PoiRegistry.OpenCase open = PoiRegistry.openCase(id);
            h.require(open != null && "MANDATORY".equals(open.source()), "the open case after the mandatory candidate was " + open);
            h.assertStrict(bot, "poi_resume_then_warden_end");
            h.pass();
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // Restart / resume (direct dispatch)
    // ---------------------------------------------------------------------------------------------

    /**
     * A STOPPED case survives a genuine unload/restart: {@code MiningAssistRuntime.clearBotUnload} (the actual
     * production method, called for real) drops {@code MiningAssistState}, {@code PoiRegistry} and
     * {@code MandatoryLatch} but never touches {@code BotMemory} or the pause flag; the very next real
     * {@code PoiCoordinator.tick} rebuilds the open case from {@code BotMemory} and resends the notice exactly
     * once (a "poi_restart_rehydrated" line, never a second one on a later tick).
     *
     * <p>Also directly exercises the fix this method's own production code exists to guard (contract §0
     * correction #8/§3.5): first, the soft idle-release variant {@code MiningAssistRuntime.clearBot} (a real
     * call, real bot) must leave the open case intact -- proving the idle-release path can no longer wipe a
     * live hold's dedupe state -- before the genuine-unload variant {@code clearBotUnload} is used for the
     * restart itself.</p>
     */
    @GameTest(environment = "minecraftai-gametest:ore_dig_poi_game_tests_restart_during_stop_rebuilds_case", maxTicks = 300)
    public void restartDuringStopRebuildsCase(GameTestHelper context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(60, -3, 3, -3, 3, 3);
        AIPlayerEntity bot = h.spawn("PoiRestartGT", room, 0, 0);
        h.enablePoi(bot, null);
        UUID id = bot.getUUID();
        String dim = BotEdits.dimensionKey(room.world);
        BlockPos anchor = room.at(2, 0, 0);
        Progress p = new Progress();
        int[] stage = {0};
        int[] stageStart = {0};

        context.failIfEver(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            switch (stage[0]) {
                case 0 -> {
                    if (!h.settle(bot, p)) {
                        return;
                    }
                    h.assertStrict(bot, "poi_restart");
                    freeze(bot, TaskOrigin.Kind.MISSION, "gametest_poi_restart");
                    MiningAssistState state = MiningAssistRegistry.getOrCreate(bot);
                    state.enterDimension(dim);
                    int tick = MiningAssistRuntime.serverTick(bot);
                    PoiDetector.Result certain = syntheticResult(
                            PoiScorer.Band.STRUCTURE_CERTAIN, PoiLabeler.MINESHAFT, anchor, 0.90D, false, "");
                    PoiCoordinator.INSTANCE.onCandidate(bot, state, room.world, certain, tick);
                    h.require(TaskManager.INSTANCE.isUserPaused(bot), "fixture error: the stop never paused the bot");
                    PoiRegistry.OpenCase before = PoiRegistry.openCase(id);
                    h.require(before != null, "fixture error: the stop never opened a case");

                    // Regression guard for the idle-release fix: the SOFT clear must never touch the open case.
                    MiningAssistRuntime.clearBot(bot);
                    PoiRegistry.OpenCase afterSoftClear = PoiRegistry.openCase(id);
                    h.require(before.equals(afterSoftClear),
                            "MiningAssistRuntime.clearBot (idle-release) altered the open POI case: " + before
                                    + " -> " + afterSoftClear);

                    // The genuine-unload variant: simulates a real restart. BotMemory (the poi_hold_<label>
                    // marker) and the TaskManager pause flag are NOT cleared by this call -- only the
                    // in-memory MiningAssistState/PoiRegistry/MandatoryLatch are.
                    MiningAssistRuntime.clearBotUnload(bot);
                    h.require(PoiRegistry.openCase(id) == null, "clearBotUnload did not actually drop the in-memory case");
                    h.require(TaskManager.INSTANCE.isUserPaused(bot), "clearBotUnload touched the pause flag");
                    stage[0] = 1;
                    stageStart[0] = p.tick;
                }
                case 1 -> {
                    // The next real coordinator tick (MiningAssistCoordinator.tickBot -> PoiCoordinator.tick,
                    // unconditional of sense/mode state) must rebuild the case from BotMemory.
                    PoiRegistry.OpenCase rebuilt = PoiRegistry.openCase(id);
                    if (rebuilt == null) {
                        h.require(p.tick - stageStart[0] < 40, "the open case was never rebuilt from BotMemory after a restart");
                        return;
                    }
                    // No BotMemory fact records the original Source (design 2.4/6.4: "No BotMemory facts are
                    // written"), so a rehydrated non-mandatory case is always reconstructed as FALLBACK, never
                    // the original CERTAIN -- both render the identical (non-mandatory) notice template and
                    // never touch MandatoryLatch, so this is a label on an equivalence class, not a real
                    // behavior change. WARDEN_RISK_LABEL is the one label that must still come back MANDATORY.
                    h.require(rebuilt.label().equals(PoiLabeler.MINESHAFT) && "FALLBACK".equals(rebuilt.source()),
                            "the rebuilt case did not match the original: " + rebuilt);
                    List<String> lines = botLog(bot.getGameProfile().name());
                    h.require(lines != null && countEvent(lines, "poi_restart_rehydrated") == 1,
                            "expected exactly one poi_restart_rehydrated line, saw "
                                    + (lines == null ? -1 : countEvent(lines, "poi_restart_rehydrated")));
                    stage[0] = 2;
                    stageStart[0] = p.tick;
                }
                case 2 -> {
                    // Further ticks must not resend the restart notice a second time.
                    if (p.tick - stageStart[0] < 20) {
                        return;
                    }
                    List<String> lines = botLog(bot.getGameProfile().name());
                    h.require(lines != null && countEvent(lines, "poi_restart_rehydrated") == 1,
                            "the restart notice was resent on a later tick");
                    h.assertStrict(bot, "poi_restart_end");
                    h.pass();
                }
                default -> {
                }
            }
        }));
    }

    /** The vanilla "continue" chat phrase closes an open POI case purely through
     * {@code IntentController.routePlayerControlPhrase -> resume}; no {@code PoiCoordinator} code is involved in
     * the resume decision itself, only in tending the bookkeeping afterward. */
    @GameTest(environment = "minecraftai-gametest:ore_dig_poi_game_tests_continue_phrase_resumes_to_anchor", maxTicks = 200)
    public void continuePhraseResumesToAnchor(GameTestHelper context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(70, -3, 3, -3, 3, 3);
        AIPlayerEntity bot = h.spawn("PoiContinuePhraseGT", room, 0, 0);
        h.enablePoi(bot, null);
        UUID id = bot.getUUID();
        String dim = BotEdits.dimensionKey(room.world);
        BlockPos anchor = room.at(2, 0, 0);
        Progress p = new Progress();
        int[] stage = {0};
        int[] stageStart = {0};
        Task[] frozen = new Task[1];

        context.failIfEver(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            switch (stage[0]) {
                case 0 -> {
                    if (!h.settle(bot, p)) {
                        return;
                    }
                    h.assertStrict(bot, "poi_continue_phrase");
                    frozen[0] = freeze(bot, TaskOrigin.Kind.MISSION, "gametest_poi_continue_phrase");
                    MiningAssistState state = MiningAssistRegistry.getOrCreate(bot);
                    state.enterDimension(dim);
                    int tick = MiningAssistRuntime.serverTick(bot);
                    PoiDetector.Result certain = syntheticResult(
                            PoiScorer.Band.STRUCTURE_CERTAIN, PoiLabeler.MINESHAFT, anchor, 0.90D, false, "");
                    PoiCoordinator.INSTANCE.onCandidate(bot, state, room.world, certain, tick);
                    h.require(TaskManager.INSTANCE.isUserPaused(bot), "fixture error: the stop never paused the bot");
                    h.require(PoiRegistry.openCase(id) != null, "fixture error: the stop never opened a case");
                    h.require(MiningAssistCoordinator.awaitingContinue(bot), "awaitingContinue was false while a case is open");

                    boolean handled = IntentController.INSTANCE.routePlayerControlPhrase(
                            bot, IntentController.ControlOrigin.PLAYER_COMMAND, "continue");
                    h.require(handled, "the vanilla \"continue\" phrase was not recognised as a control phrase");
                    h.require(!TaskManager.INSTANCE.isUserPaused(bot), "\"continue\" did not actually resume the bot");
                    h.require(TaskManager.INSTANCE.getActive(bot).orElse(null) == frozen[0],
                            "the frozen mission task was not restored by the resume");
                    stage[0] = 1;
                    stageStart[0] = p.tick;
                }
                case 1 -> {
                    // The next real PoiCoordinator.tick (run by the live coordinator every tick) must close the
                    // case; no PoiCoordinator code was involved in the resume decision itself.
                    if (PoiRegistry.openCase(id) != null) {
                        h.require(p.tick - stageStart[0] < 40, "the open case was never closed after the resume");
                        return;
                    }
                    h.require(!MiningAssistCoordinator.awaitingContinue(bot), "awaitingContinue stayed true after the case closed");
                    List<String> lines = botLog(bot.getGameProfile().name());
                    h.require(lines != null && hasEvent(lines, "poi_case_closed"), "no poi_case_closed line was written");
                    h.assertStrict(bot, "poi_continue_phrase_end");
                    h.pass();
                }
                default -> {
                }
            }
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // DigDown notice variant (design 6.1) -- direct dispatch
    // ---------------------------------------------------------------------------------------------

    /** A POSSIBLE candidate below the fallback's structure-score threshold never pauses a descending
     * {@code DigDownTask}: the design 6.7 fallback matrix resolves to NOTIFY_ONLY, and DigDown keeps descending
     * untouched. */
    @GameTest(environment = "minecraftai-gametest:ore_dig_poi_game_tests_dig_down_possible_fallback_never_pauses_below_threshold",
            maxTicks = 200)
    public void digDownPossibleFallbackNeverPausesBelowThreshold(GameTestHelper context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(80, -3, 3, -3, 3, 4);
        AIPlayerEntity bot = h.spawn("PoiDigDownFallbackGT", room, 0, 0);
        h.enablePoi(bot, null);
        String dim = BotEdits.dimensionKey(room.world);
        BlockPos anchor = room.at(2, 0, 0);

        context.failIfEver(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            if (!h.settle(bot, new Progress())) {
                return;
            }
            h.assertStrict(bot, "poi_digdown_fallback");
            DigDownTask task = new DigDownTask(Blocks.STONE, 999);
            TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_poi_digdown_fallback"));
            h.require(task.isDescending(), "fixture error: a freshly assigned DigDownTask is not in DESCEND");
            MiningAssistState state = MiningAssistRegistry.getOrCreate(bot);
            state.enterDimension(dim);
            int tick = MiningAssistRuntime.serverTick(bot);
            // S = 0.30, well below PoiDecisionPolicy.FALLBACK_STOP_SCORE (0.75): under the default
            // unavailablePolicy (STOP_IF_STRUCTURE) this must resolve to NOTIFY_ONLY, never a stop.
            PoiDetector.Result possible = syntheticResult(PoiScorer.Band.POSSIBLE, "cavern", anchor, 0.30D, false, "");
            PoiCoordinator.INSTANCE.onCandidate(bot, state, room.world, possible, tick);

            h.require(!TaskManager.INSTANCE.isUserPaused(bot), "a below-threshold POSSIBLE candidate paused the bot");
            h.require(TaskManager.INSTANCE.getActive(bot).orElse(null) == task,
                    "the DigDownTask is no longer the active task after a NOTIFY_ONLY candidate");
            h.require(task.isDescending(), "the DigDownTask's phase moved although nothing should have touched it");
            List<String> lines = botLog(bot.getGameProfile().name());
            h.require(lines != null && hasEvent(lines, "poi_fyi"), "no poi_fyi log line was written");
            h.require(lines == null || !hasEvent(lines, "poi_stop"), "a poi_stop line was written for a NOTIFY_ONLY candidate");
            h.assertStrict(bot, "poi_digdown_fallback_end");
            h.pass();
        }));
    }

    /**
     * A stop (structure-certain or fallback) while a real {@code DigDownTask} is descending must read
     * {@code TaskManager.peekPaused}, not {@code getActive}: {@code IntentController.pause} routes through
     * {@code pauseUserIntent -> pauseFor}, which removes the task from {@code active} <em>before</em> pushing it
     * onto the pause stack, so by the time {@code PoiCoordinator} looks for the paused task, {@code getActive} is
     * unconditionally empty. This is the real-server proof of contract §0 correction #10: with the original
     * {@code getActive} bug this assertion would fail outright (the paused task would appear to be
     * {@code Optional.empty()}), which is exactly how that bug should have been caught before it shipped.
     *
     * <p>Real-server proof of a second, related timing bug this same GameTest surfaced: {@code
     * PoiCoordinator.stopNow} must read whether the task being paused is a descending {@code DigDownTask}
     * from the still-<em>active</em> task, <em>before</em> the {@code IntentController.pause} call below runs
     * -- not after, even via the correct {@code peekPaused}. {@code DigDownTask.onPause} (design 6.1's table,
     * independently verified) converts phase DESCEND to RETURN as its own unconditional side effect of being
     * paused, so by the time this test's own assertions run (after {@code onCandidate} has already paused the
     * task), the live task correctly reports RETURN: that is what proves {@code stopNow} captured "descending"
     * before pausing rather than reading the now-mutated phase back out, which is exactly how the pre-fix bug
     * (always selecting the standard/mandatory template, never the climb-out one, for every DigDown-descend
     * stop) would have gone undetected without a real paused task to observe.</p>
     */
    @GameTest(environment = "minecraftai-gametest:ore_dig_poi_game_tests_dig_down_stop_uses_descent_climb_notice",
            maxTicks = 200)
    public void digDownStopUsesDescentClimbNotice(GameTestHelper context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(90, -3, 3, -3, 3, 4);
        AIPlayerEntity bot = h.spawn("PoiDigDownStopGT", room, 0, 0);
        h.enablePoi(bot, null);
        String dim = BotEdits.dimensionKey(room.world);
        BlockPos anchor = room.at(2, 0, 0);

        context.failIfEver(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            if (!h.settle(bot, new Progress())) {
                return;
            }
            h.assertStrict(bot, "poi_digdown_stop");
            DigDownTask task = new DigDownTask(Blocks.STONE, 999);
            TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_poi_digdown_stop"));
            h.require(task.isDescending(), "fixture error: a freshly assigned DigDownTask is not in DESCEND");
            MiningAssistState state = MiningAssistRegistry.getOrCreate(bot);
            state.enterDimension(dim);
            int tick = MiningAssistRuntime.serverTick(bot);
            PoiDetector.Result certain = syntheticResult(
                    PoiScorer.Band.STRUCTURE_CERTAIN, PoiLabeler.MINESHAFT, anchor, 0.90D, false, "");
            PoiCoordinator.INSTANCE.onCandidate(bot, state, room.world, certain, tick);

            h.require(TaskManager.INSTANCE.isUserPaused(bot), "the stop never paused the bot");
            // The regression this test guards: getActive must be empty (pauseFor already removed the task)...
            h.require(TaskManager.INSTANCE.getActive(bot).isEmpty(),
                    "fixture assumption broken: getActive still returns the task after IntentController.pause");
            // ...while peekPaused correctly returns the same DigDownTask. PoiCoordinator.stopNow must have
            // already captured "descending" from the task while it was still active, before this pause ran
            // (see this test's own javadoc): DigDownTask.onPause (design 6.1, independently verified)
            // unconditionally converts DESCEND to RETURN as a side effect of being paused, so the live task
            // now correctly reports RETURN, not DESCEND.
            Task paused = TaskManager.INSTANCE.peekPaused(bot).orElse(null);
            h.require(paused == task, "peekPaused did not return the paused DigDownTask: " + paused);
            h.require(!task.isDescending(),
                    "onPause should have converted the paused DigDownTask's phase from DESCEND to RETURN by now");
            PoiRegistry.OpenCase open = PoiRegistry.openCase(bot.getUUID());
            h.require(open != null && "CERTAIN".equals(open.source()), "the open case after the stop was " + open);
            List<String> lines = botLog(bot.getGameProfile().name());
            h.require(lines != null && hasEvent(lines, "poi_stop"), "no poi_stop log line was written");
            h.assertStrict(bot, "poi_digdown_stop_end");
            h.pass();
        }));
    }

    /** Same descent-notice-variant proof as {@link #digDownStopUsesDescentClimbNotice} (including the same
     * pre-pause-capture timing point), off a MANDATORY trigger instead of a structure-certain one: every
     * source uses the climb-out template while DigDown is descending. */
    @GameTest(environment = "minecraftai-gametest:ore_dig_poi_game_tests_dig_down_mandatory_uses_descent_variant_too",
            maxTicks = 200)
    public void digDownMandatoryUsesDescentVariantToo(GameTestHelper context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(100, -3, 3, -3, 3, 4);
        AIPlayerEntity bot = h.spawn("PoiDigDownMandatoryGT", room, 0, 0);
        h.enablePoi(bot, null);
        String dim = BotEdits.dimensionKey(room.world);
        BlockPos anchor = room.at(2, 0, 0);
        Progress p = new Progress();
        int[] stage = {0};
        int[] stageStart = {0};

        context.failIfEver(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            switch (stage[0]) {
                case 0 -> {
                    if (!h.settle(bot, p)) {
                        return;
                    }
                    h.assertStrict(bot, "poi_digdown_mandatory");
                    DigDownTask task = new DigDownTask(Blocks.STONE, 999);
                    TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.MISSION, "gametest_poi_digdown_mandatory"));
                    h.require(task.isDescending(), "fixture error: a freshly assigned DigDownTask is not in DESCEND");
                    MiningAssistState state = MiningAssistRegistry.getOrCreate(bot);
                    state.enterDimension(dim);
                    int tick = MiningAssistRuntime.serverTick(bot);
                    PoiDetector.Result mandatory = syntheticResult(
                            PoiScorer.Band.MANDATORY, "warden_risk", anchor, 0.95D, false, "reinforced_deepslate");
                    PoiCoordinator.INSTANCE.onCandidate(bot, state, room.world, mandatory, tick);

                    h.require(TaskManager.INSTANCE.isUserPaused(bot), "the mandatory stop never paused the bot");
                    h.require(TaskManager.INSTANCE.getActive(bot).isEmpty(), "fixture assumption broken: getActive still returns the task");
                    Task paused = TaskManager.INSTANCE.peekPaused(bot).orElse(null);
                    h.require(paused == task, "peekPaused did not return the paused DigDownTask: " + paused);
                    h.require(!task.isDescending(),
                            "onPause should have converted the paused DigDownTask's phase from DESCEND to RETURN by now");
                    PoiRegistry.OpenCase open = PoiRegistry.openCase(bot.getUUID());
                    h.require(open != null && "MANDATORY".equals(open.source()), "the open case after the mandatory stop was " + open);
                    stage[0] = 1;
                    stageStart[0] = p.tick;
                }
                case 1 -> {
                    // BotLogWriter drains its queue on a separate background thread (see BotLogWriter.workerLoop):
                    // the poi_stop line submitted above is not guaranteed to have reached disk on this very same
                    // tick, especially with many GameTests logging concurrently. Poll a few ticks for it instead
                    // of requiring it same-tick, exactly like this file's own restart-rehydration polling
                    // (restartDuringStopRebuildsCase) already does for the same class of async-log race. Every
                    // behavioural assertion (pause, getActive, peekPaused, phase, registry source) already ran
                    // strictly, same-tick, above; only the log-visibility check gets this tolerance.
                    List<String> lines = botLog(bot.getGameProfile().name());
                    if (lines == null || !hasEvent(lines, "poi_stop")) {
                        h.require(p.tick - stageStart[0] < 40, "no poi_stop log line was written");
                        return;
                    }
                    h.assertStrict(bot, "poi_digdown_mandatory_end");
                    h.pass();
                }
                default -> {
                }
            }
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // Routing / bookkeeping
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code PoiCoordinator.sendNotice}'s recipient loop is gated on
     * {@code recipients == BROADCAST || BotAuthorizationGate.canCommand(player, bot)}. This harness cannot
     * capture a delivered chat packet (see the class javadoc), so this test instead exercises the real
     * authorization decision the loop depends on, against a genuinely owned second bot (spawned with
     * {@code ownerUuid} set to another bot's UUID -- an ordinary, real ownership relationship, not a fake): the
     * owner reads as authorized, an unrelated third bot does not, and the real stop/notify pipeline completes
     * identically (same {@code poi_stop} log line, same pause) under both {@code noticeRecipients} policies,
     * proving recipient selection is orthogonal to the coordinator's own state changes.
     */
    @GameTest(environment = "minecraftai-gametest:ore_dig_poi_game_tests_notice_reaches_only_authorized_players",
            maxTicks = 250)
    public void noticeReachesOnlyAuthorizedPlayers(GameTestHelper context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(110, -6, 6, -3, 3, 3);
        String dim = BotEdits.dimensionKey(room.world);
        Progress p = new Progress();
        int[] stage = {0};
        AIPlayerEntity[] owner = new AIPlayerEntity[1];
        AIPlayerEntity[] stranger = new AIPlayerEntity[1];
        AIPlayerEntity[] subject = new AIPlayerEntity[1];

        context.failIfEver(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            switch (stage[0]) {
                case 0 -> {
                    owner[0] = h.spawn("PoiNoticeOwnerGT", room, -4, 0);
                    stranger[0] = h.spawn("PoiNoticeStrangerGT", room, 4, 0);
                    subject[0] = h.spawnOwned("PoiNoticeSubjectGT", room, 0, 0, owner[0].getUUID());
                    h.enablePoi(subject[0], null);
                    stage[0] = 1;
                }
                case 1 -> {
                    if (!h.settle(subject[0], p)) {
                        return;
                    }
                    h.assertStrict(subject[0], "poi_notice_recipients");
                    h.require(BotAuthorizationGate.INSTANCE.canCommand(owner[0], subject[0]),
                            "the real owner is not authorized to command the subject bot");
                    h.require(!BotAuthorizationGate.INSTANCE.canCommand(stranger[0], subject[0]),
                            "an unrelated bot is unexpectedly authorized to command the subject bot");

                    // Stage A: the default AUTHORIZED policy.
                    MiningAssistState state = MiningAssistRegistry.getOrCreate(subject[0]);
                    state.enterDimension(dim);
                    int tick = MiningAssistRuntime.serverTick(subject[0]);
                    freeze(subject[0], TaskOrigin.Kind.MISSION, "gametest_poi_notice_recipients_a");
                    BlockPos anchorA = room.at(-2, 0, 0);
                    PoiDetector.Result certainA = syntheticResult(
                            PoiScorer.Band.STRUCTURE_CERTAIN, PoiLabeler.MINESHAFT, anchorA, 0.90D, false, "");
                    PoiCoordinator.INSTANCE.onCandidate(subject[0], state, room.world, certainA, tick);
                    h.require(TaskManager.INSTANCE.isUserPaused(subject[0]), "the AUTHORIZED-policy stop never paused the bot");
                    List<String> linesA = botLog(subject[0].getGameProfile().name());
                    h.require(linesA != null && countEvent(linesA, "poi_stop") == 1,
                            "expected exactly one poi_stop under the AUTHORIZED policy");

                    IntentController.INSTANCE.resume(subject[0], IntentController.ControlOrigin.PLAYER_COMMAND, "gametest_reset_a");
                    PoiCoordinator.INSTANCE.tick(subject[0], tick + 1);
                    h.require(!TaskManager.INSTANCE.isUserPaused(subject[0]), "fixture error: could not reset between stages");
                    // Stage A's STOPPED entry (same "mineshaft" label, anchored 4 blocks from anchorB below)
                    // would otherwise legitimately suppress Stage B's candidate under PoiRegistry's own dedupe
                    // radius and same-label-radius rules (design 6.4) -- correct production behaviour, but
                    // orthogonal to what this stage actually tests (recipient routing under BROADCAST vs
                    // AUTHORIZED, not dedupe, which PoiRegistryTest already covers). Clear it so Stage B is a
                    // clean, independent candidate.
                    PoiRegistry.clear(subject[0].getUUID());

                    // Stage B: the BROADCAST policy -- must reach the same internal outcome regardless of who is
                    // "authorized" (BROADCAST never consults BotAuthorizationGate at all).
                    h.enablePoi(subject[0], broadcastOverrides());
                    BlockPos anchorB = room.at(2, 0, 0);
                    PoiDetector.Result certainB = syntheticResult(
                            PoiScorer.Band.STRUCTURE_CERTAIN, PoiLabeler.MINESHAFT, anchorB, 0.90D, false, "");
                    PoiCoordinator.INSTANCE.onCandidate(subject[0], state, room.world, certainB, tick + 2);
                    h.require(TaskManager.INSTANCE.isUserPaused(subject[0]), "the BROADCAST-policy stop never paused the bot");
                    List<String> linesB = botLog(subject[0].getGameProfile().name());
                    h.require(linesB != null && countEvent(linesB, "poi_stop") == 2,
                            "expected a second poi_stop under the BROADCAST policy, saw "
                                    + (linesB == null ? -1 : countEvent(linesB, "poi_stop")));
                    h.assertStrict(subject[0], "poi_notice_recipients_end");
                    h.pass();
                }
                default -> {
                }
            }
        }));
    }

    /** {@code TaskManager.userPauseEpoch} strictly increases across pauseUserIntent -> resumeUserIntent ->
     * cancelIntentTasks, and a despawn during an active pause bumps it by exactly 1 (via
     * {@code onBotDespawn -> cancelIntentTasks(bot, "bot_unload")} as its own first statement), never 2 --
     * the real-server regression guard for the double-bump fix (contract §0 correction #4/§3.1). */
    @GameTest(environment = "minecraftai-gametest:ore_dig_poi_game_tests_user_pause_epoch_bumps_on_every_transition",
            maxTicks = 100)
    public void userPauseEpochBumpsOnEveryTransition(GameTestHelper context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(120, -3, 3, -3, 3, 3);
        AIPlayerEntity bot = h.spawn("PoiEpochGT", room, 0, 0);
        String name = bot.getGameProfile().name();

        context.failIfEver(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            if (!h.settle(bot, new Progress())) {
                return;
            }
            h.assertStrict(bot, "poi_epoch");
            freeze(bot, TaskOrigin.Kind.MISSION, "gametest_poi_epoch");
            int e0 = TaskManager.INSTANCE.userPauseEpoch(bot);

            TaskManager.INSTANCE.pauseUserIntent(bot, "gametest_epoch_pause");
            h.require(TaskManager.INSTANCE.userPauseEpoch(bot) == e0 + 1,
                    "pauseUserIntent did not bump the epoch by exactly 1");

            TaskManager.INSTANCE.resumeUserIntent(bot, "gametest_epoch_resume");
            h.require(TaskManager.INSTANCE.userPauseEpoch(bot) == e0 + 2,
                    "resumeUserIntent did not bump the epoch by exactly 1");

            TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_epoch_cancel");
            h.require(TaskManager.INSTANCE.userPauseEpoch(bot) == e0 + 3,
                    "cancelIntentTasks did not bump the epoch by exactly 1");

            // Re-establish an active pause, then despawn while paused: onBotDespawn's own first statement is
            // cancelIntentTasks(bot, "bot_unload"), which bumps the epoch once; onBotDespawn itself must not
            // also bump it a second time.
            freeze(bot, TaskOrigin.Kind.MISSION, "gametest_poi_epoch_2");
            TaskManager.INSTANCE.pauseUserIntent(bot, "gametest_epoch_pause_2");
            int beforeDespawn = TaskManager.INSTANCE.userPauseEpoch(bot);

            h.bots.remove(name); // despawn ourselves below; do not let Harness.cleanup double-despawn
            boolean despawned = AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), name);
            h.require(despawned, "fixture error: could not despawn the epoch-test bot");
            h.require(TaskManager.INSTANCE.userPauseEpoch(bot) == beforeDespawn + 1,
                    "a despawn during an active pause bumped the epoch by "
                            + (TaskManager.INSTANCE.userPauseEpoch(bot) - beforeDespawn) + ", expected exactly 1");
            h.pass();
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // P3 R4 LLM confirm: hold, consult, timeout, stale/late verdict, degraded-TPS skip (stub transport)
    // ---------------------------------------------------------------------------------------------

    /**
     * The core happy path of design 6.5's {@code consult()}: a POSSIBLE candidate with the advisor available
     * (a stub {@link PoiAdvisor} transport stands in for the real HTTP client) holds the mission through
     * {@code TaskManager.pauseUserIntent} <em>before</em> the transport ever answers (proven by asserting the
     * pause the same tick {@code onCandidate} returns, well before the stub's own artificial delay elapses),
     * then a STOP verdict promotes that hold in place into a real stop -- never a second
     * {@code IntentController.pause} -- with the dedupe registry recording it STOPPED.
     */
    @GameTest(environment = "minecraftai-gametest:ore_dig_poi_game_tests_possible_candidate_holds_then_stop", maxTicks = 300)
    public void possibleCandidateHoldsThenStop(GameTestHelper context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(130, -3, 3, -3, 3, 3);
        AIPlayerEntity bot = h.spawn("PoiHoldStopGT", room, 0, 0);
        String botName = bot.getGameProfile().name();
        h.enablePoi(bot, null);
        h.onCleanup(() -> {
            PoiAdvisor.setTestTransport(null);
            PoiConsultBudget.clearAll();
            PoiCache.clearAll();
        });
        PoiConsultBudget.clearAll();
        PoiCache.clearAll();
        GatedStub gate = new GatedStub(new PoiPrompt.Verdict(PoiPrompt.Decision.STOP, "mineshaft", "high", "gametest stub stop"));
        h.onCleanup(gate::free);
        PoiAdvisor.setTestTransport(gate.transport());
        String dim = BotEdits.dimensionKey(room.world);
        BlockPos anchor = room.at(2, 0, 0);
        Progress p = new Progress();
        int[] phase = {0};

        context.failIfEver(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            if (!h.settle(bot, p)) {
                return;
            }
            if (phase[0] == 0) {
                h.assertStrict(bot, "poi_hold_stop");
                freeze(bot, TaskOrigin.Kind.MISSION, "gametest_poi_hold_stop");
                MiningAssistState state = MiningAssistRegistry.getOrCreate(bot);
                state.enterDimension(dim);
                int tick = MiningAssistRuntime.serverTick(bot);
                PoiDetector.Result possible = syntheticResult(PoiScorer.Band.POSSIBLE, "mineshaft", anchor, 0.55D, false, "");
                PoiCoordinator.INSTANCE.onCandidate(bot, state, room.world, possible, tick);
                h.require(TaskManager.INSTANCE.isUserPaused(bot),
                        "the hold must pause the bot synchronously, before the (stubbed) advisor ever answers");
                List<String> started = botLog(botName);
                h.require(started != null && hasEvent(started, "poi_consult_started"), "no poi_consult_started log line");
                phase[0] = 1;
                return;
            }
            if (phase[0] == 1) {
                List<String> lines = botLog(botName);
                boolean resolved = lines != null && hasEvent(lines, "poi_stop");
                if (!resolved) {
                    h.require(TaskManager.INSTANCE.isUserPaused(bot),
                            "the bot must stay paused for the whole hold, until the verdict resolves it");
                    gate.advance(h, p.tick, GatedStub.HOLD_TICKS, "the stub verdict never resolved within the test budget");
                    return;
                }
                h.require(TaskManager.INSTANCE.isUserPaused(bot), "a STOP verdict must leave the bot paused");
                PoiRegistry.OpenCase open = PoiRegistry.openCase(bot.getUUID());
                h.require(open != null && "FALLBACK".equals(open.source()) && "mineshaft".equals(open.label()),
                        "the open case after a verdict-driven stop was " + open);
                h.assertStrict(bot, "poi_hold_stop_end");
                h.pass();
            }
        }));
    }

    /**
     * Same as {@link #possibleCandidateHoldsThenStop}, but the stub verdict is CONTINUE: design 6.5's
     * {@code TaskManager.resumeUserIntent(bot, "poi_cleared")} un-pauses the mission and the dedupe registry
     * records the candidate DECLINED, never STOPPED.
     */
    @GameTest(environment = "minecraftai-gametest:ore_dig_poi_game_tests_possible_candidate_holds_then_continue", maxTicks = 300)
    public void possibleCandidateHoldsThenContinue(GameTestHelper context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(140, -3, 3, -3, 3, 3);
        AIPlayerEntity bot = h.spawn("PoiHoldContinueGT", room, 0, 0);
        String botName = bot.getGameProfile().name();
        h.enablePoi(bot, null);
        h.onCleanup(() -> {
            PoiAdvisor.setTestTransport(null);
            PoiConsultBudget.clearAll();
            PoiCache.clearAll();
        });
        PoiConsultBudget.clearAll();
        PoiCache.clearAll();
        GatedStub gate = new GatedStub(new PoiPrompt.Verdict(PoiPrompt.Decision.CONTINUE, "natural_cave", "medium", "gametest stub continue"));
        h.onCleanup(gate::free);
        PoiAdvisor.setTestTransport(gate.transport());
        String dim = BotEdits.dimensionKey(room.world);
        BlockPos anchor = room.at(2, 0, 0);
        Progress p = new Progress();
        int[] phase = {0};
        Task[] taskHolder = new Task[1];

        context.failIfEver(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            if (!h.settle(bot, p)) {
                return;
            }
            if (phase[0] == 0) {
                h.assertStrict(bot, "poi_hold_continue");
                taskHolder[0] = freeze(bot, TaskOrigin.Kind.MISSION, "gametest_poi_hold_continue");
                MiningAssistState state = MiningAssistRegistry.getOrCreate(bot);
                state.enterDimension(dim);
                int tick = MiningAssistRuntime.serverTick(bot);
                PoiDetector.Result possible = syntheticResult(PoiScorer.Band.POSSIBLE, "cavern", anchor, 0.50D, false, "");
                PoiCoordinator.INSTANCE.onCandidate(bot, state, room.world, possible, tick);
                h.require(TaskManager.INSTANCE.isUserPaused(bot), "the hold must pause the bot synchronously");
                phase[0] = 1;
                return;
            }
            if (phase[0] == 1) {
                List<String> lines = botLog(botName);
                boolean resolved = lines != null && hasEvent(lines, "poi_continue");
                if (!resolved) {
                    gate.advance(h, p.tick, GatedStub.HOLD_TICKS, "the stub verdict never resolved within the test budget");
                    return;
                }
                h.require(!TaskManager.INSTANCE.isUserPaused(bot), "a CONTINUE verdict must resume the bot");
                h.require(TaskManager.INSTANCE.getActive(bot).orElse(null) == taskHolder[0],
                        "the frozen task must be active again after resumeUserIntent");
                h.require(PoiRegistry.openCase(bot.getUUID()) == null, "a CONTINUE must never open a case");
                List<String> stopLines = botLog(botName);
                h.require(stopLines == null || !hasEvent(stopLines, "poi_stop"), "a poi_stop line was written for a CONTINUE verdict");
                h.assertStrict(bot, "poi_hold_continue_end");
                h.pass();
            }
        }));
    }

    /**
     * Design 6.5's stale-result rule: a player action during the hold (here, a direct
     * {@code TaskManager.resumeUserIntent}, standing in for the player's own "continue") bumps the pause
     * epoch, so the eventual verdict -- even a STOP -- must never re-pause the bot or claim "Stopped." It
     * becomes a notify-only "Late check" line instead (design 6.6: "Late replies never pause"), and the
     * registry still records the outcome for future dedupe even though the bot's pause state is untouched.
     */
    @GameTest(environment = "minecraftai-gametest:ore_dig_poi_game_tests_player_pause_during_hold_is_never_overridden",
            maxTicks = 300)
    public void playerPauseDuringHoldIsNeverOverridden(GameTestHelper context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(150, -3, 3, -3, 3, 3);
        AIPlayerEntity bot = h.spawn("PoiHoldStaleGT", room, 0, 0);
        String botName = bot.getGameProfile().name();
        h.enablePoi(bot, null);
        h.onCleanup(() -> {
            PoiAdvisor.setTestTransport(null);
            PoiConsultBudget.clearAll();
            PoiCache.clearAll();
        });
        PoiConsultBudget.clearAll();
        PoiCache.clearAll();
        // Released only after the player's resume (phase 2 first tick), so the verdict deterministically
        // arrives after the player's action, which is the whole point of this test.
        GatedStub gate = new GatedStub(new PoiPrompt.Verdict(PoiPrompt.Decision.STOP, "dungeon", "high", "gametest stub late stop"));
        h.onCleanup(gate::free);
        PoiAdvisor.setTestTransport(gate.transport());
        String dim = BotEdits.dimensionKey(room.world);
        BlockPos anchor = room.at(2, 0, 0);
        Progress p = new Progress();
        int[] phase = {0};

        context.failIfEver(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            if (!h.settle(bot, p)) {
                return;
            }
            if (phase[0] == 0) {
                h.assertStrict(bot, "poi_hold_stale");
                freeze(bot, TaskOrigin.Kind.MISSION, "gametest_poi_hold_stale");
                MiningAssistState state = MiningAssistRegistry.getOrCreate(bot);
                state.enterDimension(dim);
                int tick = MiningAssistRuntime.serverTick(bot);
                PoiDetector.Result possible = syntheticResult(PoiScorer.Band.POSSIBLE, "dungeon", anchor, 0.55D, false, "");
                PoiCoordinator.INSTANCE.onCandidate(bot, state, room.world, possible, tick);
                h.require(TaskManager.INSTANCE.isUserPaused(bot), "the hold must pause the bot synchronously");
                phase[0] = 1;
                return;
            }
            if (phase[0] == 1) {
                // The player "acts" during the hold, independent of PoiCoordinator entirely -- exactly what a
                // real "continue" phrase does through IntentController.routePlayerControlPhrase.
                TaskManager.INSTANCE.resumeUserIntent(bot, "gametest_player_continue_during_hold");
                h.require(!TaskManager.INSTANCE.isUserPaused(bot), "fixture error: resumeUserIntent did not resume");
                phase[0] = 2;
                return;
            }
            if (phase[0] == 2) {
                List<String> lines = botLog(botName);
                boolean resolved = lines != null && hasEvent(lines, "poi_late_check");
                if (!resolved) {
                    h.require(!TaskManager.INSTANCE.isUserPaused(bot),
                            "a late verdict must never re-pause a bot the player already resumed");
                    gate.advance(h, p.tick, 0, "the stub verdict never resolved within the test budget");
                    return;
                }
                h.require(!TaskManager.INSTANCE.isUserPaused(bot), "a late STOP must never claim the pause back");
                h.require(!hasEvent(lines, "poi_stop"), "a late STOP must never log a real poi_stop");
                h.require(PoiRegistry.openCase(bot.getUUID()) == null, "a late verdict must never open a case");
                boolean recordedStopped = PoiRegistry.snapshot(bot.getUUID()).stream()
                        .anyMatch(e -> e.label().equals("dungeon") && e.state() == PoiRegistry.State.STOPPED);
                h.require(recordedStopped, "the registry must still record the late STOP for future dedupe");
                h.assertStrict(bot, "poi_hold_stale_end");
                h.pass();
            }
        }));
    }

    /**
     * Design 6.5's "deadline reached (any state) -> applyFallback()": {@code poi.holdDeadlineTicks} is
     * shrunk far below the stub transport's own delay, so {@code PoiCoordinator.tick}'s own deadline check
     * resolves the case through the design 6.7 fallback matrix (S=0.85 &gt;= 0.75, so {@code
     * unavailablePolicy=STOP_IF_STRUCTURE} stops it) before the advisor ever answers. The hold's existing
     * pause is promoted in place, exactly like a real verdict's STOP branch. When the stub eventually does
     * answer, its (now-stale) verdict must be a silent no-op: no second stop, no duplicate log line.
     */
    @GameTest(environment = "minecraftai-gametest:ore_dig_poi_game_tests_consult_deadline_applies_fallback_when_advisor_is_slow",
            maxTicks = 700)
    public void consultDeadlineAppliesFallbackWhenAdvisorIsSlow(GameTestHelper context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(160, -3, 3, -3, 3, 3);
        AIPlayerEntity bot = h.spawn("PoiDeadlineGT", room, 0, 0);
        String botName = bot.getGameProfile().name();
        JsonObject poi = new JsonObject();
        poi.addProperty("holdDeadlineTicks", 20); // 1s: comfortably shorter than the stub's 3s delay
        h.enablePoi(bot, poi);
        h.onCleanup(() -> {
            PoiAdvisor.setTestTransport(null);
            PoiConsultBudget.clearAll();
            PoiCache.clearAll();
        });
        PoiConsultBudget.clearAll();
        PoiCache.clearAll();
        // Held back until the coordinator's own deadline fallback has fired (phase 2), then released.
        GatedStub gate = new GatedStub(new PoiPrompt.Verdict(PoiPrompt.Decision.STOP, "stronghold", "low", "gametest stub too-slow stop"));
        h.onCleanup(gate::free);
        PoiAdvisor.setTestTransport(gate.transport());
        String dim = BotEdits.dimensionKey(room.world);
        BlockPos anchor = room.at(2, 0, 0);
        Progress p = new Progress();
        int[] phase = {0};

        context.failIfEver(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            if (!h.settle(bot, p)) {
                return;
            }
            if (phase[0] == 0) {
                h.assertStrict(bot, "poi_deadline");
                freeze(bot, TaskOrigin.Kind.MISSION, "gametest_poi_deadline");
                MiningAssistState state = MiningAssistRegistry.getOrCreate(bot);
                state.enterDimension(dim);
                int tick = MiningAssistRuntime.serverTick(bot);
                PoiDetector.Result possible = syntheticResult(PoiScorer.Band.POSSIBLE, "stronghold", anchor, 0.85D, false, "");
                PoiCoordinator.INSTANCE.onCandidate(bot, state, room.world, possible, tick);
                h.require(TaskManager.INSTANCE.isUserPaused(bot), "the hold must pause the bot synchronously");
                phase[0] = 1;
                return;
            }
            if (phase[0] == 1) {
                List<String> lines = botLog(botName);
                boolean deadlineHit = lines != null && hasEvent(lines, "poi_consult_deadline");
                if (!deadlineHit) {
                    h.require(p.tick < 120, "the 1s hold deadline never fired within the test budget");
                    return;
                }
                h.require(hasEvent(lines, "poi_stop"),
                        "the deadline's own fallback (S=0.85 >= 0.75, STOP_IF_STRUCTURE) must stop the bot");
                h.require(TaskManager.INSTANCE.isUserPaused(bot), "the deadline-triggered stop must leave the bot paused");
                phase[0] = 2;
                return;
            }
            if (phase[0] == 2) {
                // Release the (now-stale) stub and wait until the coordinator has actually processed its verdict
                // (the "poi_advisor_verdict already_resolved=true" line), then confirm it changed nothing:
                // still exactly one poi_stop line, still paused, no second consult attempt logged.
                List<String> lines = botLog(botName);
                if (lines == null || !hasEvent(lines, "poi_advisor_verdict")) {
                    gate.advance(h, p.tick, 0, "the released stub's late verdict never reached the coordinator");
                    return;
                }
                h.require(lines.stream().anyMatch(l -> l.contains("event=poi_advisor_verdict") && l.contains("already_resolved='true'")),
                        "the late verdict must be recognised as arriving after the deadline resolved the case");
                int stopCount = lines == null ? 0 : (int) lines.stream().filter(l -> l.contains("event=poi_stop ") || l.endsWith("event=poi_stop")).count();
                h.require(stopCount == 1, "a late, already-superseded verdict must never add a second poi_stop, saw " + stopCount);
                h.require(TaskManager.INSTANCE.isUserPaused(bot), "the bot must still be paused from the deadline's own stop");
                h.assertStrict(bot, "poi_deadline_end");
                h.pass();
            }
        }));
    }

    /**
     * Design 6.6: "Skipped while TPS is degraded." With the TPS test override forced degraded, {@code
     * advisorAvailable} must return false before ever reaching {@link PoiAdvisor#consult}, so the stub
     * transport (present, but must never be invoked) is bypassed entirely and the design 6.7 fallback matrix
     * runs synchronously, exactly like P2 -- no hold, no pause-then-resolve delay.
     */
    @GameTest(environment = "minecraftai-gametest:ore_dig_poi_game_tests_degraded_tps_skips_consult_and_uses_fallback",
            maxTicks = 200)
    public void degradedTpsSkipsConsultAndUsesFallback(GameTestHelper context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(170, -3, 3, -3, 3, 3);
        AIPlayerEntity bot = h.spawn("PoiDegradedTpsGT", room, 0, 0);
        h.enablePoi(bot, null);
        MiningAssistRuntime.setTestTpsDegraded(Boolean.TRUE);
        AtomicBoolean transportCalled = new AtomicBoolean(false);
        h.onCleanup(() -> {
            PoiAdvisor.setTestTransport(null);
            PoiConsultBudget.clearAll();
            PoiCache.clearAll();
        });
        PoiConsultBudget.clearAll();
        PoiCache.clearAll();
        PoiAdvisor.setTestTransport(payload -> {
            transportCalled.set(true);
            return new PoiPrompt.Verdict(PoiPrompt.Decision.CONTINUE, "natural_cave", "high", "must never be called");
        });
        String dim = BotEdits.dimensionKey(room.world);
        BlockPos anchor = room.at(2, 0, 0);

        context.failIfEver(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            if (!h.settle(bot, new Progress())) {
                return;
            }
            h.assertStrict(bot, "poi_degraded_tps");
            freeze(bot, TaskOrigin.Kind.MISSION, "gametest_poi_degraded_tps");
            MiningAssistState state = MiningAssistRegistry.getOrCreate(bot);
            state.enterDimension(dim);
            int tick = MiningAssistRuntime.serverTick(bot);
            // S = 0.85 >= 0.75: the default STOP_IF_STRUCTURE keyless fallback stops it, synchronously,
            // exactly the P2 code path -- proving degraded TPS skipped the consult/hold machinery entirely.
            PoiDetector.Result possible = syntheticResult(PoiScorer.Band.POSSIBLE, "stronghold", anchor, 0.85D, false, "");
            PoiCoordinator.INSTANCE.onCandidate(bot, state, room.world, possible, tick);

            h.require(!transportCalled.get(), "degraded TPS must never reach the advisor transport at all");
            h.require(TaskManager.INSTANCE.isUserPaused(bot), "the keyless fallback must still stop a strong candidate");
            List<String> lines = botLog(bot.getGameProfile().name());
            h.require(lines != null && hasEvent(lines, "poi_stop"), "no poi_stop log line was written");
            h.require(!hasEvent(lines, "poi_consult_started"), "degraded TPS must never start a real consult");
            h.assertStrict(bot, "poi_degraded_tps_end");
            h.pass();
        }));
    }

    /**
     * Design 6.5's stated reason the hold pauses through {@code TaskManager.pauseUserIntent} directly instead
     * of {@code IntentController.pause}: {@code IntentController.pause/resume} call {@code BrainCoordinator.
     * clearIntentWakeSources}, which would silently drop the post-task wake ({@code awaitingTask}) after every
     * false-positive hold. A {@code LLM_TOOL}-origin mission (the kind a real planner decision assigns) is
     * seeded with that wake source ({@code BrainCoordinator.setAwaitingTaskForTest}, standing in for the real
     * end-of-turn bookkeeping at the "if (TaskManager.INSTANCE.getActive(bot).isPresent())" branch in {@code
     * BrainCoordinator} -- driving a full LLM conversation turn is out of scope for this harness, see the
     * class javadoc's "no player-message capture" section for the same trade-off applied elsewhere in this
     * file), then must still be set after the hold's own {@code pauseUserIntent} call, after the eventual
     * STOP verdict promotes that hold into a real stop, and after {@code TaskManager.INSTANCE.
     * resumeUserIntent} would restore it (proven directly, not by waiting for a verdict, exactly like the
     * regression proven by {@code IntentController.pause}/{@code resume} never being called at all here).
     */
    @GameTest(environment = "minecraftai-gametest:ore_dig_poi_game_tests_hold_does_not_clear_planner_wake_state",
            maxTicks = 300)
    public void holdDoesNotClearPlannerWakeState(GameTestHelper context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(180, -3, 3, -3, 3, 3);
        AIPlayerEntity bot = h.spawn("PoiWakeStateGT", room, 0, 0);
        String botName = bot.getGameProfile().name();
        h.enablePoi(bot, null);
        h.onCleanup(() -> {
            PoiAdvisor.setTestTransport(null);
            PoiConsultBudget.clearAll();
            PoiCache.clearAll();
            BrainCoordinator.INSTANCE.setAwaitingTaskForTest(bot, false);
        });
        PoiConsultBudget.clearAll();
        PoiCache.clearAll();
        GatedStub gate = new GatedStub(new PoiPrompt.Verdict(PoiPrompt.Decision.STOP, "mineshaft", "high", "gametest stub stop"));
        h.onCleanup(gate::free);
        PoiAdvisor.setTestTransport(gate.transport());
        String dim = BotEdits.dimensionKey(room.world);
        BlockPos anchor = room.at(2, 0, 0);
        Progress p = new Progress();
        int[] phase = {0};

        context.failIfEver(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            if (!h.settle(bot, p)) {
                return;
            }
            if (phase[0] == 0) {
                h.assertStrict(bot, "poi_wake_state");
                // LLM_TOOL: the origin kind a real planner decision assigns (design's own example), with the
                // wake source a real end-of-turn "task still active" bookkeeping would have left set.
                freeze(bot, TaskOrigin.Kind.LLM_TOOL, "gametest_poi_wake_state");
                BrainCoordinator.INSTANCE.setAwaitingTaskForTest(bot, true);
                MiningAssistState state = MiningAssistRegistry.getOrCreate(bot);
                state.enterDimension(dim);
                int tick = MiningAssistRuntime.serverTick(bot);
                PoiDetector.Result possible = syntheticResult(PoiScorer.Band.POSSIBLE, "mineshaft", anchor, 0.55D, false, "");
                PoiCoordinator.INSTANCE.onCandidate(bot, state, room.world, possible, tick);
                h.require(TaskManager.INSTANCE.isUserPaused(bot),
                        "the hold must pause the bot synchronously, before the (stubbed) advisor ever answers");
                h.require(BrainCoordinator.INSTANCE.isAwaitingTaskForTest(bot),
                        "TaskManager.pauseUserIntent (design 6.5's direct hold path) must never clear "
                                + "awaitingTask, unlike IntentController.pause would");
                phase[0] = 1;
                return;
            }
            if (phase[0] == 1) {
                List<String> lines = botLog(botName);
                boolean resolved = lines != null && hasEvent(lines, "poi_stop");
                if (!resolved) {
                    h.require(BrainCoordinator.INSTANCE.isAwaitingTaskForTest(bot),
                            "the wake source must survive the whole hold, not just its first tick");
                    gate.advance(h, p.tick, GatedStub.HOLD_TICKS, "the stub verdict never resolved within the test budget");
                    return;
                }
                h.require(TaskManager.INSTANCE.isUserPaused(bot), "a STOP verdict must leave the bot paused");
                h.require(BrainCoordinator.INSTANCE.isAwaitingTaskForTest(bot),
                        "promoting the hold into a real stop (never a second IntentController.pause, design "
                                + "6.5) must not clear the wake source either");
                // The reverse direction: TaskManager.resumeUserIntent (the hold's own CONTINUE path, and what
                // a real player "continue" eventually calls) must not clear it either.
                TaskManager.INSTANCE.resumeUserIntent(bot, "gametest_poi_wake_state_resume");
                h.require(!TaskManager.INSTANCE.isUserPaused(bot), "fixture error: resumeUserIntent did not resume");
                h.require(BrainCoordinator.INSTANCE.isAwaitingTaskForTest(bot),
                        "TaskManager.resumeUserIntent must never clear awaitingTask, unlike IntentController.resume would");
                h.assertStrict(bot, "poi_wake_state_end");
                h.pass();
            }
        }));
    }

    /**
     * Design 6.6's "late replies never pause," the half of the rule {@link
     * #consultDeadlineAppliesFallbackWhenAdvisorIsSlow} does not cover: that test's late reply is itself a
     * STOP (redundant with the fallback's own STOP, so a silent drop and a correctly-handled late reply look
     * identical from the outside -- {@code stopCount == 1} either way). Here the fallback's decision (STOP,
     * S=0.85 &gt;= 0.75) and the eventual real verdict (CONTINUE) disagree, so the two behaviours are only
     * distinguishable if the late reply is actually processed: "a late stop after the fallback... becomes a
     * notify-only line[;] a late continue only fills the cache" (design 6.6). The bot must stay exactly as
     * the fallback left it (paused, one {@code poi_stop} line, never resumed), while the cache still gets the
     * real verdict so a later candidate in the same coarse cell benefits from it.
     */
    @GameTest(environment = "minecraftai-gametest:ore_dig_poi_game_tests_late_verdict_is_notify_only", maxTicks = 700)
    public void lateVerdictIsNotifyOnly(GameTestHelper context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(190, -3, 3, -3, 3, 3);
        AIPlayerEntity bot = h.spawn("PoiLateVerdictGT", room, 0, 0);
        String botName = bot.getGameProfile().name();
        JsonObject poi = new JsonObject();
        poi.addProperty("holdDeadlineTicks", 20); // 1s: comfortably shorter than the stub's 3s delay
        h.enablePoi(bot, poi);
        h.onCleanup(() -> {
            PoiAdvisor.setTestTransport(null);
            PoiConsultBudget.clearAll();
            PoiCache.clearAll();
        });
        PoiConsultBudget.clearAll();
        PoiCache.clearAll();
        // Held back until the coordinator's own deadline fallback has fired (phase 2), then released.
        GatedStub gate = new GatedStub(new PoiPrompt.Verdict(PoiPrompt.Decision.CONTINUE, "natural_cave", "medium", "gametest stub late continue"));
        h.onCleanup(gate::free);
        PoiAdvisor.setTestTransport(gate.transport());
        String dim = BotEdits.dimensionKey(room.world);
        BlockPos anchor = room.at(2, 0, 0);
        String cacheKey = PoiCache.keyFor(dim, anchor.getX(), anchor.getY(), anchor.getZ(), List.of());
        Progress p = new Progress();
        int[] phase = {0};

        context.failIfEver(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            if (!h.settle(bot, p)) {
                return;
            }
            if (phase[0] == 0) {
                h.assertStrict(bot, "poi_late_verdict");
                freeze(bot, TaskOrigin.Kind.MISSION, "gametest_poi_late_verdict");
                MiningAssistState state = MiningAssistRegistry.getOrCreate(bot);
                state.enterDimension(dim);
                int tick = MiningAssistRuntime.serverTick(bot);
                PoiDetector.Result possible = syntheticResult(PoiScorer.Band.POSSIBLE, "stronghold", anchor, 0.85D, false, "");
                PoiCoordinator.INSTANCE.onCandidate(bot, state, room.world, possible, tick);
                h.require(TaskManager.INSTANCE.isUserPaused(bot), "the hold must pause the bot synchronously");
                phase[0] = 1;
                return;
            }
            if (phase[0] == 1) {
                List<String> lines = botLog(botName);
                boolean deadlineHit = lines != null && hasEvent(lines, "poi_consult_deadline");
                if (!deadlineHit) {
                    h.require(p.tick < 120, "the 1s hold deadline never fired within the test budget");
                    return;
                }
                h.require(hasEvent(lines, "poi_stop"),
                        "the deadline's own fallback (S=0.85 >= 0.75, STOP_IF_STRUCTURE) must stop the bot");
                h.require(TaskManager.INSTANCE.isUserPaused(bot), "the deadline-triggered stop must leave the bot paused");
                h.require(PoiCache.get(cacheKey, MiningAssistRuntime.serverTick(bot)) == null,
                        "the fallback itself (no real verdict yet) must never write the advisor cache");
                phase[0] = 2;
                return;
            }
            if (phase[0] == 2) {
                // Release the stub (a genuine CONTINUE, disagreeing with the fallback's STOP) and wait until
                // the coordinator has processed it, then confirm design 6.6's late-reply rule was actually
                // applied, not just silently dropped (which would look identical on the stopCount alone). The
                // reply is gated by the test instead of a wall-clock sleep: the headless GameTest server ticks
                // at roughly 1000/s, so any tick-count margin raced the stub's sleep (see GatedStub).
                List<String> lines = botLog(botName);
                if (lines == null || !hasEvent(lines, "poi_late_check")) {
                    gate.advance(h, p.tick, 0, "the released stub's late verdict never reached the coordinator");
                    return;
                }
                h.require(hasEvent(lines, "poi_late_check"),
                        "a late verdict after the coordinator's own deadline fallback must log poi_late_check, "
                                + "design 6.6: \"a late stop after the fallback or after the player acted...\"");
                boolean loggedAsContinueViaDeadline = lines.stream().anyMatch(
                        l -> l.contains("event=poi_late_check") && l.contains("decision='continue'")
                                && l.contains("trigger='coordinator_deadline'"));
                h.require(loggedAsContinueViaDeadline,
                        "expected one poi_late_check line with decision=continue, trigger=coordinator_deadline");
                int stopCount = (int) lines.stream().filter(l -> l.contains("event=poi_stop ") || l.endsWith("event=poi_stop")).count();
                h.require(stopCount == 1, "a late CONTINUE must never add or remove a poi_stop, saw " + stopCount);
                h.require(TaskManager.INSTANCE.isUserPaused(bot),
                        "a late continue must never resume a bot the fallback already stopped (\"late replies never pause\")");
                PoiCache.Entry cached = PoiCache.get(cacheKey, MiningAssistRuntime.serverTick(bot));
                h.require(cached != null && !cached.stop(),
                        "design 6.6: \"a late continue only fills the cache\" -- the real verdict must still reach PoiCache");
                h.assertStrict(bot, "poi_late_verdict_end");
                h.pass();
            }
        }));
    }

    /**
     * Design 6.5's "Restart during a hold": a genuine unload/restart mid-consult ({@code MiningAssistRuntime.
     * clearBotUnload}, the actual production method) must leave {@code PoiCoordinator} with no in-memory case
     * for this bot at all, so the very next {@code tick} takes the "no in-memory case, user-paused, a {@code
     * poi_hold_*} place present" branch and rebuilds a STOPPED case (fail closed), exactly as it does for a
     * restart during an already-resolved stop ({@link #restartDuringStopRebuildsCase}). This is the regression
     * {@link PoiCoordinator#clearBot} exists to guard: without dropping the P3 {@code caseIdByBot}/{@code
     * pendingByCaseId} bookkeeping on unload, this bot's stale in-flight-consult entry would instead route the
     * next tick into {@code checkConsultDeadline} (a case that no longer has a real advisor callback coming,
     * since the restart-simulated bot object is the very one the callback closures captured), never reaching
     * the fail-closed rehydration path at all.
     */
    @GameTest(environment = "minecraftai-gametest:ore_dig_poi_game_tests_restart_during_consulting_hold_fails_closed",
            maxTicks = 300)
    public void restartDuringConsultingHoldFailsClosed(GameTestHelper context) {
        Harness h = new Harness(context);
        Room room = h.newRoom(200, -3, 3, -3, 3, 3);
        AIPlayerEntity bot = h.spawn("PoiRestartHoldGT", room, 0, 0);
        UUID id = bot.getUUID();
        h.enablePoi(bot, null);
        h.onCleanup(() -> {
            PoiAdvisor.setTestTransport(null);
            PoiConsultBudget.clearAll();
            PoiCache.clearAll();
        });
        PoiConsultBudget.clearAll();
        PoiCache.clearAll();
        // Never answers within this test's own real-time budget (maxTicks=300, 15s): the whole point is to
        // restart while still "in flight." Bounded at 10s -- PoiAdvisor.DEFAULT_CONSULT_DEADLINE_MS's own
        // wall-clock guard, comfortably longer than this test needs -- rather than an unbounded sleep, so a
        // stray callback (pending == null by then, a safe no-op) does not tie up the shared 2-thread advisor
        // executor for longer than the sibling slow-stub tests in this file already do.
        PoiAdvisor.setTestTransport(payload -> {
            Thread.sleep(10_000L);
            return new PoiPrompt.Verdict(PoiPrompt.Decision.CONTINUE, "natural_cave", "low", "must never be observed");
        });
        String dim = BotEdits.dimensionKey(room.world);
        BlockPos anchor = room.at(2, 0, 0);
        Progress p = new Progress();
        int[] stage = {0};
        int[] stageStart = {0};

        context.failIfEver(() -> h.guard(() -> {
            if (h.done) {
                return;
            }
            p.tick++;
            switch (stage[0]) {
                case 0 -> {
                    if (!h.settle(bot, p)) {
                        return;
                    }
                    h.assertStrict(bot, "poi_restart_hold");
                    freeze(bot, TaskOrigin.Kind.MISSION, "gametest_poi_restart_hold");
                    MiningAssistState state = MiningAssistRegistry.getOrCreate(bot);
                    state.enterDimension(dim);
                    int tick = MiningAssistRuntime.serverTick(bot);
                    PoiDetector.Result possible = syntheticResult(PoiScorer.Band.POSSIBLE, "dungeon", anchor, 0.55D, false, "");
                    PoiCoordinator.INSTANCE.onCandidate(bot, state, room.world, possible, tick);
                    h.require(TaskManager.INSTANCE.isUserPaused(bot),
                            "fixture error: the hold never paused the bot before the restart");
                    // A hold that has not yet resolved to a stop has no PoiRegistry.openCase (only stopNow
                    // ever sets that); the fixture check is the dedupe entry startConsult itself records.
                    boolean consulting = PoiRegistry.snapshot(id).stream()
                            .anyMatch(e -> e.label().equals("dungeon") && e.state() == PoiRegistry.State.CONSULTING);
                    h.require(consulting, "fixture error: the hold never opened a CONSULTING dedupe entry");
                    h.require(PoiRegistry.openCase(id) == null,
                            "fixture error: an in-flight (unresolved) hold must not already have an open case");

                    // The genuine-unload variant, called for real, still mid-consult (the stub transport has
                    // not answered and will not for the rest of this test). BotMemory (poi_hold_dungeon) and
                    // the TaskManager pause flag are untouched -- only the in-memory PoiRegistry/PoiCoordinator
                    // bookkeeping is, exactly like restartDuringStopRebuildsCase's own comment documents.
                    // Both real production calls: RuntimeLifecycleCoordinator.clearTransient (private, so a
                    // GameTest cannot reach it directly) makes exactly this pair of calls on a genuine
                    // unload/death/reset.
                    MiningAssistRuntime.clearBotUnload(bot);
                    PoiCoordinator.INSTANCE.clearBot(id);
                    h.require(PoiRegistry.openCase(id) == null, "clearBotUnload did not drop the in-memory case");
                    h.require(TaskManager.INSTANCE.isUserPaused(bot), "clearBotUnload touched the pause flag");
                    stage[0] = 1;
                    stageStart[0] = p.tick;
                }
                case 1 -> {
                    // The next real PoiCoordinator.tick must take the rehydration branch (no in-memory case,
                    // still user-paused, poi_hold_dungeon present), never checkConsultDeadline: if
                    // PoiCoordinator.clearBot had not dropped caseIdByBot for this bot, tick() would instead
                    // route here via checkConsultDeadline (whose own deadline was never shortened in this
                    // test, so it would just silently wait out this test's whole tick budget with no case ever
                    // rebuilt -- the fail-closed rehydration this test exists to prove would never run).
                    PoiRegistry.OpenCase rebuilt = PoiRegistry.openCase(id);
                    if (rebuilt == null) {
                        h.require(p.tick - stageStart[0] < 40,
                                "the open case was never rebuilt from BotMemory after a restart mid-consult "
                                        + "(PoiCoordinator's stale in-flight-consult bookkeeping was not cleared)");
                        return;
                    }
                    h.require(rebuilt.label().equals("dungeon") && "FALLBACK".equals(rebuilt.source()),
                            "the rebuilt case did not match the original candidate: " + rebuilt);
                    List<String> lines = botLog(bot.getGameProfile().name());
                    h.require(lines != null && countEvent(lines, "poi_restart_rehydrated") == 1,
                            "expected exactly one poi_restart_rehydrated line, saw "
                                    + (lines == null ? -1 : countEvent(lines, "poi_restart_rehydrated")));
                    h.require(!hasEvent(lines, "poi_consult_deadline"),
                            "a restart mid-consult must take the rehydration branch, never checkConsultDeadline");
                    h.assertStrict(bot, "poi_restart_hold_end");
                    h.pass();
                }
                default -> {
                }
            }
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // Shared fixture plumbing
    // ---------------------------------------------------------------------------------------------

    /** Assigns a real, harmless {@code MineTask} (never findable, so it never completes) and pauses it in
     * place through the task's own lifecycle hook (not through {@code TaskManager}): it stays the bot's active
     * task (one of the five sensed classes, keeping {@code sense()}/the coordinator engaged) without ever
     * walking anywhere. Exactly {@code MiningAssistSenseGameTests.freeze}'s idiom, reused here so the bot has a
     * real task to pause into (and, for the direct-dispatch tests, a real target for
     * {@code TaskManager.activeOrigin}/{@code ledgerKeyFor}). */
    private static Task freeze(AIPlayerEntity bot, TaskOrigin.Kind kind, String reason) {
        MineTask task = new MineTask(Blocks.OBSIDIAN, 1);
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(kind, reason));
        task.pause(bot);
        return task;
    }

    /** Builds a {@code PoiDetector.Result} exactly as {@code PoiDetector.evaluate} would hand one to
     * {@code MiningAssistCoordinator.sense()} -> {@code PoiCoordinator.onCandidate}, but with the caller
     * choosing the band/label/score/habitation-like/mandatory-trigger directly instead of deriving them from a
     * real raycast sweep. See the class javadoc's "direct dispatch" section for why and where this is used. */
    private static PoiDetector.Result syntheticResult(PoiScorer.Band band, String label, BlockPos anchor,
            double structureScore, boolean habitationLike, String mandatoryTrigger) {
        PoiScorer.PoiScore score = new PoiScorer.PoiScore(
                8.0D, structureScore, 0.0D, 0.0D, Math.max(structureScore, 0.5D),
                8, 3, true, band, false, mandatoryTrigger == null ? "" : mandatoryTrigger,
                Vec3.atCenterOf(anchor), false, true, false, true, 3, habitationLike);
        return new PoiDetector.Result(true, band, score, label, true, true, anchor, 8, 0, 8,
                "minecraft:the_overworld", false);
    }

    /** {@code poi.noticeRecipients = broadcast}, everything else default. */
    private static JsonObject broadcastOverrides() {
        JsonObject poi = new JsonObject();
        poi.addProperty("noticeRecipients", "broadcast");
        return poi;
    }

    /** Parses a fixed-form {@code MiningAssistConfig} in {@code AssistMode.POI} (harnessOff=true, matching the
     * shipped default: every test opts a single bot past it with {@code forceEnable}, which never bypasses the
     * origin/audit/TPS gates, only the harness default). {@code poiOverrides}, when non-null, becomes the
     * {@code "poi"} child object (e.g. {@code noticeRecipients}); every other {@code poi.*} key keeps
     * {@code MiningAssistConfig.Poi.DEFAULTS} (maxHoldsPerMission=3, unavailablePolicy=STOP_IF_STRUCTURE,
     * cavernKeylessPolicy=NOTIFY_ONLY, noticeRecipients=AUTHORIZED, dedupeRadius=40). */
    private static MiningAssistConfig poiConfig(JsonObject poiOverrides) {
        JsonObject section = new JsonObject();
        if (poiOverrides != null) {
            section.add("poi", poiOverrides);
        }
        JsonObject root = new JsonObject();
        root.add(MiningAssistConfig.FILE_SECTION, section);
        return MiningAssistConfig.parse(root, key -> null, AssistMode.POI, true);
    }

    /** True when any line contains {@code event=<name>}. */
    private static boolean hasEvent(List<String> lines, String name) {
        String needle = "event=" + name + " ";
        String needleEol = "event=" + name;
        for (String line : lines) {
            if (line.contains(needle) || line.endsWith(needleEol)) {
                return true;
            }
        }
        return false;
    }

    /** Count of lines containing {@code event=<name>}. */
    private static int countEvent(List<String> lines, String name) {
        int count = 0;
        for (String line : lines) {
            if (hasEvent(List.of(line), name)) {
                count++;
            }
        }
        return count;
    }

    /** Per-test tick bookkeeping (mirrors {@code MiningAssistSenseGameTests.Progress}). */
    private static final class Progress {
        int tick;
        int assignedAt = -1;
        int chunkWaitStart = -1;
    }

    /**
     * A stub advisor transport whose reply is released by the test, never by wall-clock time. The headless
     * GameTest server ticks at close to 1000 ticks per second (and that rate varies with machine load and
     * with how heavy each tick is), so a {@code Thread.sleep(N)} in the stub races every tick-count budget
     * in the test: a 3s stub could not answer inside a 3000-tick margin, and a 400ms stub not inside 300
     * ticks. Gating the reply on a latch keeps the ordering the sleeps were standing in for ("the reply
     * arrives after the hold/deadline/player action, and before the test gives up") while removing the
     * dependence on the tick rate. The test releases the stub, blocks the server thread (harmless: the stub
     * never needs it) until the worker has returned the verdict, and only then counts ticks for the
     * server-thread callback the advisor queues.
     */
    private static final class GatedStub {
        /** Ticks the server thread gets, after the worker returned, to run the queued advisor callback. */
        private static final int CALLBACK_TICK_BUDGET = 100;
        /** Ticks a reply is held back once the consult started, so the hold is visibly in force first. */
        static final int HOLD_TICKS = 50;
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch returned = new CountDownLatch(1);
        private final PoiPrompt.Verdict verdict;
        private int firstTick = -1;
        private int releasedAt = -1;

        GatedStub(PoiPrompt.Verdict verdict) {
            this.verdict = verdict;
        }

        PoiAdvisor.Transport transport() {
            return payload -> {
                try {
                    // Bounded so a test that dies before releasing cannot pin a shared advisor worker.
                    release.await(30L, TimeUnit.SECONDS);
                } finally {
                    returned.countDown();
                }
                return verdict;
            };
        }

        /** Cleanup: never leave the worker blocked (the bounded await would otherwise pin a shared advisor worker). */
        void free() {
            release.countDown();
        }

        /**
         * One call per test tick while the verdict has not been observed yet. Holds the reply back for
         * {@code holdTicks} ticks, then releases it and waits for the worker; afterwards each call spends
         * one tick of {@link #CALLBACK_TICK_BUDGET} (with a tiny real sleep so the budget also covers
         * scheduling jitter) and fails with {@code neverArrived} when the callback still did not run.
         */
        void advance(Harness h, int tick, int holdTicks, String neverArrived) {
            try {
                if (releasedAt < 0) {
                    if (firstTick < 0) {
                        firstTick = tick;
                    }
                    if (tick - firstTick < holdTicks) {
                        return;
                    }
                    release.countDown();
                    if (!returned.await(10L, TimeUnit.SECONDS)) {
                        h.fail("the stub advisor transport was never invoked (or never returned) after release: " + neverArrived);
                    }
                    releasedAt = tick;
                    return;
                }
                h.require(tick < releasedAt + CALLBACK_TICK_BUDGET, neverArrived);
                Thread.sleep(2L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                h.fail("interrupted while waiting for the stub advisor: " + neverArrived);
            }
        }
    }

    /** Cleanup-on-failure, strict-capability and POI-mode config plumbing shared by every test. */
    private static final class Harness {
        final GameTestHelper context;
        final List<String> bots = new ArrayList<>();
        final List<Room> rooms = new ArrayList<>();
        final List<UUID> forced = new ArrayList<>();
        final List<Runnable> cleanups = new ArrayList<>();
        MiningAssistConfig restoreConfig;
        MinecraftAiConfig restorePerception;
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
            return spawnOwned(name, room, dx, dz, null);
        }

        AIPlayerEntity spawnOwned(String name, Room room, int dx, int dz, UUID ownerUuid) {
            ServerLevel world = room.world;
            BlockPos feet = room.at(dx, 0, dz);
            bots.add(name);
            var spawned = AIPlayerManager.INSTANCE.spawn(
                    world.getServer(), name, world, Vec3.atBottomCenterOf(feet), 0.0F, 0.0F, GameType.SURVIVAL, ownerUuid);
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

        /** Runs this test with a larger perception radius (restored by {@link #cleanup()}): the config has no
         * setter, so this swaps the immutable singleton the way the sibling GameTests do. */
        void setPerceptionRadius(int radius) {
            MinecraftAiConfig config = MinecraftAiConfig.get();
            if (restorePerception == null) {
                restorePerception = config;
            }
            MinecraftAiConfig.Perception perception = config.perception();
            installConfig(new MinecraftAiConfig(config.profile(), config.operatorCapabilities(), config.llm(),
                    new MinecraftAiConfig.Perception(radius, perception.maxBlocks(), perception.maxEntities(),
                            perception.maxItems(), perception.includeRawLists()),
                    config.brain(), config.watchdog(), config.logging(), config.survival(), config.combat(),
                    config.night(), config.mining(), config.goal(), config.nav(), config.pickup(),
                    config.conversation(), config.storage()));
        }

        private static void installConfig(MinecraftAiConfig config) {
            try {
                java.lang.reflect.Field instance = MinecraftAiConfig.class.getDeclaredField("instance");
                instance.setAccessible(true);
                instance.set(null, config);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("failed to install GameTest config", exception);
            }
        }

        /** Installs a POI-mode config (design G.6-style, harnessOff=true) and force-enables one bot past it. */
        void enablePoi(AIPlayerEntity bot, JsonObject poiOverrides) {
            if (restoreConfig == null) {
                restoreConfig = MiningAssistRuntime.config();
            }
            MiningAssistRuntime.install(poiConfig(poiOverrides));
            MiningAssistRuntime.setTestTpsDegraded(Boolean.FALSE);
            tpsOverridden = true;
            MiningAssistRuntime.forceEnable(bot.getUUID());
            if (!forced.contains(bot.getUUID())) {
                forced.add(bot.getUUID());
            }
        }

        void onCleanup(Runnable cleanup) {
            cleanups.add(cleanup);
        }

        /** True once the bot is underground by the world's own sky test and the chunk ring around it is loaded
         * (waived after 60 ticks, matching the sibling files). */
        boolean settle(AIPlayerEntity bot, Progress p) {
            ServerLevel world = bot.level();
            BlockPos feet = bot.blockPosition();
            p.tick++;
            if (world.canSeeSky(feet)) {
                require(p.tick < 200, "the sealed fixture never became underground by the world's sky test");
                return false;
            }
            boolean loaded = world.getChunkSource().hasChunk(feet.getX() >> 4, feet.getZ() >> 4);
            if (loaded) {
                return true;
            }
            if (p.chunkWaitStart < 0) {
                p.chunkWaitStart = p.tick;
            }
            return p.tick - p.chunkWaitStart >= 60;
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
            for (Runnable cleanup : cleanups) {
                try {
                    cleanup.run();
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
            cleanups.clear();
            for (String name : new ArrayList<>(bots)) {
                try {
                    AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), name);
                } catch (RuntimeException ignored) {
                    // best effort: a test that already despawned this bot itself must not fail cleanup
                }
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
            if (restorePerception != null) {
                installConfig(restorePerception);
                restorePerception = null;
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
            require(MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                    "GameTest must run under strict_survival, got " + MinecraftAiConfig.get().profile());
            for (PrivilegedCapability capability : PrivilegedCapability.values()) {
                require(!CapabilityRuntime.decide(bot, capability, label).allowed(),
                        "strict_survival unexpectedly allowed " + capability);
            }
        }
    }
}

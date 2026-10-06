package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ContainerAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.memory.BotMemoryStore;
import io.github.zoyluo.minecraftai.mining.MiningBudget;
import io.github.zoyluo.minecraftai.mining.MiningFoodReserve;
import io.github.zoyluo.minecraftai.mining.MiningCursor;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Live fail-closed coverage for underground tool and safe-food service. */
public final class MiningServiceResourceGameTests {
    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_disposal_admission_centers_residual_ore_walk_before_publishing_open_debt", maxTicks = 120)
    public void disposalAdmissionCentersResidualOreWalkBeforePublishingOpenDebt(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceAdmissionVelocityGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.blockPosition().immutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                ServicePolicy.defaultOre(false),
                0, "admission-velocity", 0, cursor);

        // Reproduce the evidence pose: OreDig has reached the correct BlockPos but its physical
        // walk ended on the forward edge of the cell with residual walking velocity (a real walk
        // stops within the cell). The service walks back to the middle with its movement keys (no
        // teleport), so admission takes a few ticks; OPEN is published only once the bot stands
        // centred and stopped, and nothing before that becomes durable geometry debt.
        bot.teleportTo(bot.level(), face.getX() + 0.5D, face.getY(),
                face.getZ() + 0.8D, Set.of(), 0.0F, 0.0F, true);
        bot.setOnGround(true);
        bot.setDeltaMovement(0.0D, 0.0D, 0.1D);
        io.github.zoyluo.minecraftai.entity.TeleportAudit.reset(bot);
        task.start(bot);
        task.tick(bot);
        require(context, !"OPEN_DISPOSAL_POCKET".equals(task.checkpoint().get("phase")),
                "service published OPEN while the bot was still off centre and sliding: " + task.checkpoint());

        AtomicReference<Integer> observedTicks = new AtomicReference<>(0);
        AtomicReference<Boolean> opened = new AtomicReference<>(false);
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if ("OPEN_DISPOSAL_POCKET".equals(live.get("phase")) && !opened.get()) {
                opened.set(true);
                double offset = Math.hypot(bot.getX() - (face.getX() + 0.5D), bot.getZ() - (face.getZ() + 0.5D));
                require(context, offset <= 0.2D && bot.getDeltaMovement().horizontalDistanceSqr() <= 1.0E-8D,
                        "OPEN transaction started off centre or still moving: offset=" + offset
                                + " velocity=" + bot.getDeltaMovement());
                require(context, io.github.zoyluo.minecraftai.entity.TeleportAudit.corrections(bot) == 0,
                        "the centring teleported the bot");
            }
            require(context, task.state() != TaskState.FAILED,
                    "admitted disposal failed after residual walk: " + task.failureReason());
            require(context, bot.blockPosition().equals(face),
                    "admitted disposal drifted off work face: "
                            + bot.blockPosition().toShortString());
            require(context, !live.getOrDefault("pocket_failure", "")
                            .contains("geometry_anchor_changed")
                            && !"RETURN_TO_DISPOSAL_FACE".equals(live.get("phase"))
                            && !"SEAL_DISPOSAL_POCKET".equals(live.get("phase")),
                    "pre-OPEN motion became durable geometry debt: " + live);
            int ticks = observedTicks.get() + 1;
            observedTicks.set(ticks);
            if (ticks < 8 || !opened.get()) {
                return;
            }
            task.abort(bot);
            cleanup(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_natural_open_pocket_without_head_support_seals_floor_first", maxTicks = 320)
    public void naturalOpenPocketWithoutHeadSupportSealsFloorFirst(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceNaturalPocketSealGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        // Control the two-block spoil stack directly. Vanilla opening drops may reach the player
        // zero, one, or two at a time depending on entity motion, which obscures the selector this
        // regression is intended to lock.
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLED_DEEPSLATE, 2));
        for (int index = 0; index < 28; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        require(context, freeMainSlots(bot) == 3,
                "natural-pocket fixture did not require a capacity handoff");

        BlockPos face = bot.blockPosition().immutable();
        Direction direction = Direction.EAST;
        BlockPos entry = face.relative(direction);
        BlockPos sink = face.relative(direction, 2);
        var world = bot.level();
        // Reproduce the seed-3000 coal-vein cavity: the mouth and sink are already open, their
        // floors and far wall are sound, but the head mouth has no persistent adjacent support.
        // A legal seal must therefore place the feet block against the floor before placing the
        // head block against the new feet block.
        for (BlockPos cell : new BlockPos[]{entry, entry.above(), sink, sink.above(),
                entry.above(2), entry.north(), entry.north().above(),
                entry.south(), entry.south().above()}) {
            world.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        world.setBlock(entry.below(),
                Blocks.DEEPSLATE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(sink.below(),
                Blocks.DEEPSLATE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(sink.relative(direction),
                Blocks.DEEPSLATE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(sink.relative(direction).above(),
                Blocks.DEEPSLATE.defaultBlockState(), Block.UPDATE_ALL);
        for (Direction side : new Direction[]{Direction.NORTH, Direction.SOUTH}) {
            world.setBlock(sink.relative(side),
                    Blocks.DEEPSLATE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(sink.relative(side).above(),
                    Blocks.DEEPSLATE.defaultBlockState(), Block.UPDATE_ALL);
        }
        for (Direction neighbor : Direction.values()) {
            require(context, world.getBlockState(entry.above().relative(neighbor)).isAir(),
                    "natural-pocket head unexpectedly began with placement support at "
                            + neighbor.getSerializedName());
        }

        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                ServicePolicy.capacityHandoff(64),
                0, "natural-pocket-seal", 0, miningCursor(face, 0, 1));
        task.start(bot);
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("natural open disposal pocket ended as "
                        + task.state() + ":" + task.failureReason()
                        + " checkpoint=" + task.checkpoint()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, bot.blockPosition().equals(face),
                    "natural-pocket service lost its exact work face");
            require(context, isSolid(bot, entry) && isSolid(bot, entry.above()),
                    "floor-first transaction did not double-seal the natural mouth");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 0
                            && freeMainSlots(bot) >= 4,
                    "natural-pocket capacity handoff did not spend two seals and free a slot");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 64,
                    "capacity handoff discarded its protected mining reserve");
            require(context, sinkCount(bot, sink, Items.DIRT) >= 62,
                    "natural-pocket ledger did not remain physically contained in the sink");
            cleanup(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_unsafe_open_cave_seals_then_uses_opposite_disposal_pocket", maxTicks = 500)
    public void unsafeOpenCaveSealsThenUsesOppositeDisposalPocket(GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceGeometryRerouteGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        // Control the two-block spoil stack directly. Vanilla opening drops may reach the player
        // zero, one, or two at a time depending on entity motion, which obscures the selector this
        // regression is intended to lock.
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLED_DEEPSLATE, 2));
        for (int index = 0; index < 28; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        require(context, freeMainSlots(bot) == 3,
                "geometry-reroute fixture did not require a capacity handoff");

        BlockPos face = bot.blockPosition().immutable();
        BlockPos unsafeEntry = face.east();
        BlockPos unsafeSink = face.east(2);
        BlockPos safeEntry = face.west();
        BlockPos safeSink = face.west(2);
        var world = bot.level();
        // Reproduce the live seed-3000 geometry: the preferred mouth is mineable, but opening it
        // reveals an existing cave instead of a supported two-cell sink.  The opposite side is a
        // normal bounded pocket and must not become active until the unsafe mouth is double-sealed.
        world.setBlock(unsafeEntry,
                Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(unsafeEntry.above(),
                Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(unsafeSink,
                Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(unsafeSink.above(),
                Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(face.east(3),
                Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(face.east(3).above(),
                Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        prepareDisposalPocket(fixture, Direction.WEST);
        // Keep this regression about geometry rerouting and seal-source selection, not random
        // opening-drop motion. Glass still requires four physical breaks but produces no survival
        // drop, so the controlled spoil stack and disposable inventory independently prove the
        // promised four free slots.
        for (BlockPos cell : new BlockPos[]{safeEntry, safeEntry.above(), safeSink, safeSink.above()}) {
            world.setBlock(cell, Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
        }

        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                ServicePolicy.capacityHandoff(64),
                0, "unsafe-geometry-reroute", 0, miningCursor(face, 0, 1));
        task.start(bot);
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("unsafe geometry reroute ended as "
                        + task.state() + ":" + task.failureReason()
                        + " checkpoint=" + task.checkpoint()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, bot.blockPosition().equals(face),
                    "geometry reroute lost its exact work face");
            require(context, isSolid(bot, unsafeEntry) && isSolid(bot, unsafeEntry.above()),
                    "opposite pocket started before the unsafe mouth was double-sealed");
            require(context, isSolid(bot, safeEntry) && isSolid(bot, safeEntry.above()),
                    "alternate disposal pocket did not finish with a double seal");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 0
                            && freeMainSlots(bot) >= 4,
                    "alternate pocket did not complete the promised capacity handoff");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 64,
                    "geometry reroute discarded its protected mining reserve");
            require(context, InventoryAction.countItem(bot, Items.COBBLED_DEEPSLATE) == 0
                            && world.getBlockState(safeEntry).is(Blocks.COBBLED_DEEPSLATE)
                            && world.getBlockState(safeEntry.above()).is(Blocks.COBBLED_DEEPSLATE),
                    "alternate seal did not consume the slot-releasing surplus stone stack");
            require(context, sinkCount(bot, safeSink, Items.DIRT) >= 62,
                    "alternate sink did not retain the post-reroute disposal ledger");
            cleanup(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_two_unsafe_open_caves_seal_once_each_and_fail_without_ping_pong", maxTicks = 300)
    public void twoUnsafeOpenCavesSealOnceEachAndFailWithoutPingPong(GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceDoubleGeometryGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        require(context, freeMainSlots(bot) == 3,
                "double-geometry fixture did not require a capacity handoff");

        BlockPos face = bot.blockPosition().immutable();
        var world = bot.level();
        for (Direction direction : new Direction[]{Direction.EAST, Direction.WEST}) {
            BlockPos entry = face.relative(direction);
            BlockPos sink = face.relative(direction, 2);
            // GLASS, not DIRT: fast/no-tool to mine, and (unlike DIRT) breaking it produces no
            // drop, so it cannot pollute the DIRT==60 seal-consumption assertion below.
            world.setBlock(entry,
                    Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(entry.above(),
                    Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(sink,
                    Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(sink.above(),
                    Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(face.relative(direction, 3),
                    Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(face.relative(direction, 3).above(),
                    Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }

        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                ServicePolicy.capacityHandoff(64),
                0, "double-unsafe-geometry", 0, miningCursor(face, 0, 1));
        task.start(bot);
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.FAILED
                            && "mining_service_disposal_geometry_unsafe"
                            .equals(task.failureReason()),
                    "two unsafe pockets did not terminate with the exact geometry failure: "
                            + task.state() + ":" + task.failureReason());
            require(context, isSolid(bot, face.east()) && isSolid(bot, face.east().above())
                            && isSolid(bot, face.west()) && isSolid(bot, face.west().above()),
                    "bounded geometry failure left a mouth open or retried it again");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 60,
                    "double geometry seal did not consume exactly four physical blocks");
            Map<String, String> terminal = task.checkpoint();
            require(context, "SEAL_DISPOSAL_POCKET".equals(terminal.get("phase"))
                            && "true".equals(terminal.get("pocket_drop_committed"))
                            && "mining_service_disposal_geometry_unsafe"
                            .equals(terminal.get("pocket_failure"))
                            && MiningServiceTask.inspectCheckpoint(terminal).isPresent(),
                    "double geometry terminal debt was not restartable: " + terminal);
            cleanup(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_lower_only_seal_restart_closes_head_then_fails_ledger_visibility", maxTicks = 500)
    public void lowerOnlySealRestartClosesHeadThenFailsLedgerVisibility(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceLowerOnlyRestartGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.blockPosition().immutable();
        BlockPos entry = face.east();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        ServicePolicy policy =
                ServicePolicy.defaultOre(false);
        MiningServiceTask[] active = {new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "lower-only-restart", 0, cursor)};
        active[0].start(bot);
        AtomicBoolean restarted = new AtomicBoolean();

        context.failIfEver(() -> {
            MiningServiceTask task = active[0];
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (!restarted.get()
                    && "SEAL_DISPOSAL_POCKET".equals(live.get("phase"))
                    && !live.getOrDefault("pocket_ledger", "").isBlank()) {
                require(context, MiningServiceTask.inspectCheckpoint(live).isPresent()
                                && InventoryAction.countItem(bot, Items.DIRT) == 2,
                        "lower-only restart fixture did not reach a valid pre-seal debt: " + live);
                task.abort(bot);
                bot.level().setBlock(
                        entry, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
                require(context, InventoryAction.removeItems(bot, Items.DIRT, 1)
                                && isSolid(bot, entry)
                                && bot.level().getBlockState(entry.above()).isAir(),
                        "fixture could not reproduce the lower-only physical crash boundary");
                active[0] = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), live, policy,
                        0, "lower-only-restart", 0, cursor);
                active[0].start(bot);
                require(context, active[0].state() == TaskState.RUNNING
                                && "SEAL_DISPOSAL_POCKET".equals(
                                active[0].checkpoint().get("phase")),
                        "lower-only debt did not restart in SEAL");
                restarted.set(true);
                return;
            }
            task = active[0];
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty(
                        "lower-only restart incorrectly ended as " + task.state()));
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, restarted.get()
                            && "mining_service_disposal_ledger_lost_before_seal"
                            .equals(task.failureReason()),
                    "lower-only restart lost its fail-closed ledger outcome: "
                            + task.failureReason());
            require(context, isSolid(bot, entry) && isSolid(bot, entry.above())
                            && InventoryAction.countItem(bot, Items.DIRT) == 0,
                    "lower-only restart failed before completing the physical head seal");
            require(context, MiningServiceTask.inspectCheckpoint(task.checkpoint()).isPresent()
                            && !task.checkpoint().getOrDefault(
                            "pocket_ledger", "").isBlank(),
                    "lower-only terminal debt lost its restartable ledger checkpoint");
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 20)
    public void restoredHardBudgetCannotBeResetByRestart(GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceBudgetGT", false);
        Map<String, String> checkpoint = validCheckpoint(fixture.bot(), "4800", "4800");
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), checkpoint);
        task.start(fixture.bot());
        task.tick(fixture.bot());

        require(context, task.state() == TaskState.FAILED
                        && task.failureReason().startsWith("mining_service_timeout:"),
                "restart reset the service hard budget: " + task.state()
                        + ":" + task.failureReason());
        Map<String, String> terminal = task.checkpoint();
        require(context, "4800".equals(terminal.get("budget_used"))
                        && "8".equals(terminal.get("schema"))
                        && "ORE_BATCH".equals(terminal.get("service_profile"))
                        && MiningServiceTask.inspectCheckpoint(terminal).isPresent(),
                "legacy service did not migrate into a valid schema-8 checkpoint: " + terminal);
        cleanup(context, fixture);
    }

    @GameTest(maxTicks = 20)
    public void malformedCheckpointFailsClosed(GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceInvalidGT", false);
        Map<String, String> checkpoint = new LinkedHashMap<>(
                validCheckpoint(fixture.bot(), "10", "5"));
        checkpoint.put("unknown", "must_fail_closed");
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), checkpoint);
        task.start(fixture.bot());

        require(context, task.state() == TaskState.FAILED
                        && "mining_service_invalid_checkpoint".equals(task.failureReason()),
                "malformed checkpoint silently became a fresh service: "
                        + task.state() + ":" + task.failureReason());
        cleanup(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_pocket_checkpoint_counts_and_phase_authority_are_strictly_bounded", maxTicks = 40)
    public void pocketCheckpointCountsAndPhaseAuthorityAreStrictlyBounded(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServicePocketCountGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.blockPosition().immutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        ServicePolicy policy =
                ServicePolicy.defaultOre(false);
        MiningServiceTask original = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "pocket-count-bound", 0, cursor);
        original.start(bot);
        require(context, MiningServiceTask.checkedPocketLedgerCount(
                        MiningServiceTask.POCKET_CHECKPOINT_MAX_ITEM_COUNT - 1, 1)
                        == MiningServiceTask.POCKET_CHECKPOINT_MAX_ITEM_COUNT
                        && MiningServiceTask.checkedPocketLedgerCount(
                        MiningServiceTask.POCKET_CHECKPOINT_MAX_ITEM_COUNT, 1) == -1
                        && MiningServiceTask.checkedPocketLedgerCount(Integer.MAX_VALUE, 1) == -1,
                "runtime pocket ledger count did not enforce its exact MAX boundary");
        boolean invalidEncodeRejected = false;
        try {
            MiningServiceTask.encodeItemLedger(Map.of(Items.DIRT, 0));
        } catch (IllegalStateException expected) {
            invalidEncodeRejected = expected.getMessage().startsWith(
                    "invalid_pocket_ledger:");
        }
        require(context, invalidEncodeRejected,
                "checkpoint encoder silently filtered an invalid internal ledger entry");
        Map<String, String> open = tickUntilServicePhase(
                original, bot, "OPEN_DISPOSAL_POCKET", 10);
        require(context, MiningServiceTask.inspectCheckpoint(open).isPresent(),
                "live OPEN pocket checkpoint did not decode: " + open);

        Map<String, String> baselineBoundary = new LinkedHashMap<>(open);
        baselineBoundary.put("phase", "CAPTURE_DISPOSAL_BASELINE");
        baselineBoundary.put("pocket_clear_index", "4");
        baselineBoundary.put("pocket_baseline", "minecraft:dirt="
                + MiningServiceTask.POCKET_CHECKPOINT_MAX_ITEM_COUNT);
        require(context, MiningServiceTask.inspectCheckpoint(baselineBoundary).isPresent(),
                "exact pocket baseline boundary was rejected: " + baselineBoundary);

        Map<String, String> baselinePastBoundary = new LinkedHashMap<>(baselineBoundary);
        baselinePastBoundary.put("pocket_baseline", "minecraft:dirt="
                + (MiningServiceTask.POCKET_CHECKPOINT_MAX_ITEM_COUNT + 1));
        require(context, MiningServiceTask.inspectCheckpoint(baselinePastBoundary).isEmpty(),
                "past-boundary pocket baseline retained restore authority");

        String tracked = "00000000-0000-0000-0000-000000000064";
        Map<String, String> prebaselineCapture = new LinkedHashMap<>(open);
        prebaselineCapture.put("phase", "CAPTURE_DISPOSAL_BASELINE");
        prebaselineCapture.put("pocket_clear_index", "4");
        prebaselineCapture.put("pocket_entities", tracked);
        prebaselineCapture.put("pocket_drop_committed", "true");
        require(context, MiningServiceTask.inspectCheckpoint(prebaselineCapture).isPresent(),
                "CAPTURE prebaseline identity lost its distinct checkpoint authority: "
                        + prebaselineCapture);

        Map<String, String> oneStackLedger = new LinkedHashMap<>(open);
        oneStackLedger.put("phase", "SETTLE_DISPOSABLE");
        oneStackLedger.put("pocket_clear_index", "4");
        oneStackLedger.put("pocket_entities", tracked);
        oneStackLedger.put("pocket_ledger", "minecraft:dirt=64");
        require(context, MiningServiceTask.inspectCheckpoint(oneStackLedger).isPresent(),
                "one tracked full stack stopped decoding: " + oneStackLedger);

        Map<String, String> staleVerifiedSettle = new LinkedHashMap<>(oneStackLedger);
        staleVerifiedSettle.put("pocket_drop_committed", "true");
        staleVerifiedSettle.put("pocket_ledger_verified", "true");
        require(context, MiningServiceTask.inspectCheckpoint(staleVerifiedSettle).isEmpty(),
                "SETTLE checkpoint retained stale SEAL verification authority");

        Map<String, String> ledgerPastIdentity = new LinkedHashMap<>(oneStackLedger);
        ledgerPastIdentity.put("pocket_ledger", "minecraft:dirt=65");
        require(context, MiningServiceTask.inspectCheckpoint(ledgerPastIdentity).isEmpty(),
                "one tracked UUID authorized more than one survival stack");

        String trackedSecond = "00000000-0000-0000-0000-000000000065";
        Map<String, String> twoStackLedger = new LinkedHashMap<>(ledgerPastIdentity);
        twoStackLedger.put("pocket_entities", tracked + "," + trackedSecond);
        require(context, MiningServiceTask.inspectCheckpoint(twoStackLedger).isPresent(),
                "two tracked UUIDs did not authorize a legal 65-item ledger: "
                        + twoStackLedger);

        Map<String, String> twoTypesOneIdentity = new LinkedHashMap<>(oneStackLedger);
        twoTypesOneIdentity.put(
                "pocket_ledger", "minecraft:dirt=1;minecraft:gravel=1");
        require(context, MiningServiceTask.inspectCheckpoint(twoTypesOneIdentity).isEmpty(),
                "one tracked UUID authorized two distinct item identities");

        Map<String, String> paddedIdentity = new LinkedHashMap<>(oneStackLedger);
        paddedIdentity.put("pocket_entities", tracked + "," + trackedSecond);
        paddedIdentity.put("pocket_ledger", "minecraft:dirt=1");
        require(context, MiningServiceTask.inspectCheckpoint(paddedIdentity).isEmpty(),
                "more UUIDs than recorded items retained checkpoint authority");

        Map<String, String> emptyLedgerIdentity = new LinkedHashMap<>(oneStackLedger);
        emptyLedgerIdentity.put("pocket_ledger", "");
        emptyLedgerIdentity.put("pocket_drop_committed", "true");
        require(context, MiningServiceTask.inspectCheckpoint(emptyLedgerIdentity).isEmpty(),
                "non-CAPTURE UUID retained authority without any ledger item");

        Map<String, String> integerMaxLedger = new LinkedHashMap<>(oneStackLedger);
        integerMaxLedger.put("pocket_ledger", "minecraft:dirt=" + Integer.MAX_VALUE);
        require(context, MiningServiceTask.inspectCheckpoint(integerMaxLedger).isEmpty(),
                "Integer.MAX_VALUE pocket ledger retained restore authority");

        Map<String, String> committedReturn = new LinkedHashMap<>(oneStackLedger);
        committedReturn.put("phase", "RETURN_TO_DISPOSAL_FACE");
        committedReturn.put("pocket_drop_committed", "true");
        require(context, MiningServiceTask.inspectCheckpoint(committedReturn).isPresent(),
                "committed return checkpoint stopped decoding: " + committedReturn);
        Map<String, String> baselineBackedReturn = new LinkedHashMap<>(committedReturn);
        baselineBackedReturn.put("pocket_baseline", "minecraft:dirt=2");
        require(context, MiningServiceTask.inspectCheckpoint(baselineBackedReturn).isEmpty(),
                "baseline=2 plus ledger=64 incorrectly fit into one survival lineage root: "
                        + baselineBackedReturn);
        baselineBackedReturn.put("pocket_entities", tracked + "," + trackedSecond);
        require(context, MiningServiceTask.inspectCheckpoint(baselineBackedReturn).isPresent(),
                "two lineage roots did not authorize physical baseline=2 plus ledger=64: "
                        + baselineBackedReturn);
        Map<String, String> uncommittedReturn = new LinkedHashMap<>(committedReturn);
        uncommittedReturn.put("pocket_drop_committed", "false");
        require(context, MiningServiceTask.inspectCheckpoint(uncommittedReturn).isPresent(),
                "ledger-backed return checkpoint lost its physical-debt authority");

        Map<String, String> debtFreeReturn = new LinkedHashMap<>(open);
        debtFreeReturn.put("phase", "RETURN_TO_DISPOSAL_FACE");
        require(context, MiningServiceTask.inspectCheckpoint(debtFreeReturn).isEmpty(),
                "return checkpoint without committed or ledger debt retained authority");

        Map<String, String> nonPocketPhase = new LinkedHashMap<>(open);
        nonPocketPhase.put("phase", "PREPARE");
        require(context, MiningServiceTask.inspectCheckpoint(nonPocketPhase).isEmpty(),
                "pocket payload was accepted outside a pocket phase");

        original.abort(bot);
        MiningServiceTask maliciousRestore = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), integerMaxLedger, policy,
                0, "pocket-count-bound", 0, cursor);
        maliciousRestore.start(bot);
        require(context, maliciousRestore.state() == TaskState.FAILED
                        && "mining_service_invalid_checkpoint"
                        .equals(maliciousRestore.failureReason()),
                "malicious ledger did not fail with the typed checkpoint error: "
                        + maliciousRestore.state() + ":" + maliciousRestore.failureReason());
        cleanup(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_open_retry_marker_at_hard_budget_becomes_terminal_and_cannot_reroute", maxTicks = 80)
    public void openRetryMarkerAtHardBudgetBecomesTerminalAndCannotReroute(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceOpenRetryBudgetGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.blockPosition().immutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        prepareDisposalPocket(fixture, Direction.WEST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        ServicePolicy policy =
                ServicePolicy.defaultOre(false);
        MiningServiceTask original = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "open-retry-budget", 0, cursor);
        original.start(bot);
        Map<String, String> open = tickUntilServicePhase(
                original, bot, "OPEN_DISPOSAL_POCKET", 10);
        original.abort(bot);
        Direction selected = Direction.valueOf(open.get("pocket_direction"));
        Map<String, String> hard = new LinkedHashMap<>(open);
        hard.put("budget_used", "4800");
        hard.put("last_progress_budget", "4800");
        hard.put("pocket_failure", "retry_disposal_ore:"
                + selected.getOpposite().name() + ":fixture");
        require(context, MiningServiceTask.inspectCheckpoint(hard).isPresent(),
                "OPEN retry hard-window checkpoint was invalid: " + hard);
        MiningServiceTask restored = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), hard, policy,
                0, "open-retry-budget", 0, cursor);
        restored.start(bot);
        for (int tick = 0; tick < 10 && restored.state() == TaskState.RUNNING; tick++) {
            restored.tick(bot);
        }
        require(context, restored.state() == TaskState.FAILED
                        && "mining_service_timeout:OPEN_DISPOSAL_POCKET"
                        .equals(restored.failureReason()),
                "OPEN retry marker survived hard terminal recovery: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, isSolid(bot, face.relative(selected))
                        && isSolid(bot, face.relative(selected).above()),
                "OPEN retry hard timeout failed before double seal");
        Map<String, String> terminal = restored.checkpoint();
        require(context, "4800".equals(terminal.get("budget_used"))
                        && !terminal.getOrDefault("pocket_failure", "")
                        .startsWith("retry_disposal_ore:")
                        && MiningServiceTask.inspectCheckpoint(terminal).isPresent(),
                "OPEN retry hard timeout reset budget or retained routing authority: "
                        + terminal);
        cleanup(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_seal_retry_marker_at_hard_budget_becomes_terminal_and_cannot_ping_pong", maxTicks = 80)
    public void sealRetryMarkerAtHardBudgetBecomesTerminalAndCannotPingPong(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceSealRetryBudgetGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.blockPosition().immutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        prepareDisposalPocket(fixture, Direction.WEST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        ServicePolicy policy =
                ServicePolicy.defaultOre(false);
        MiningServiceTask original = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "seal-retry-budget", 0, cursor);
        original.start(bot);
        Map<String, String> open = tickUntilServicePhase(
                original, bot, "OPEN_DISPOSAL_POCKET", 10);
        original.abort(bot);
        Direction selected = Direction.valueOf(open.get("pocket_direction"));
        Map<String, String> hard = new LinkedHashMap<>(open);
        hard.put("phase", "SEAL_DISPOSAL_POCKET");
        hard.put("pocket_drop_committed", "true");
        hard.put("pocket_failure", "retry_disposal_ore:"
                + selected.name() + ":fixture");
        hard.put("budget_used", "4800");
        hard.put("last_progress_budget", "4800");
        require(context, MiningServiceTask.inspectCheckpoint(hard).isPresent(),
                "SEAL retry hard-window checkpoint was invalid: " + hard);
        MiningServiceTask restored = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), hard, policy,
                0, "seal-retry-budget", 0, cursor);
        restored.start(bot);
        for (int tick = 0; tick < 10 && restored.state() == TaskState.RUNNING; tick++) {
            restored.tick(bot);
        }
        require(context, restored.state() == TaskState.FAILED
                        && "mining_service_timeout:SEAL_DISPOSAL_POCKET"
                        .equals(restored.failureReason()),
                "SEAL retry marker ping-ponged past the hard window: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, isSolid(bot, face.relative(selected))
                        && isSolid(bot, face.relative(selected).above()),
                "SEAL retry hard timeout failed before double seal");
        Map<String, String> terminal = restored.checkpoint();
        require(context, "4800".equals(terminal.get("budget_used"))
                        && MiningServiceTask.inspectCheckpoint(terminal).isPresent(),
                "SEAL retry terminal checkpoint reset budget or became invalid: " + terminal);
        cleanup(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_sealed_old_pocket_alternate_start_failure_leaves_valid_non_pocket_checkpoint", maxTicks = 100)
    public void sealedOldPocketAlternateStartFailureLeavesValidNonPocketCheckpoint(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceRerouteAtomicGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.blockPosition().immutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        prepareDisposalPocket(fixture, Direction.WEST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        ServicePolicy policy =
                ServicePolicy.defaultOre(false);
        MiningServiceTask original = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "reroute-atomic", 0, cursor);
        original.start(bot);
        Map<String, String> open = tickUntilServicePhase(
                original, bot, "OPEN_DISPOSAL_POCKET", 10);
        original.abort(bot);
        Direction rejected = Direction.valueOf(open.get("pocket_direction"));
        Direction alternate = rejected.getOpposite();
        BlockPos oldEntry = face.relative(rejected);
        BlockPos alternateEntry = face.relative(alternate);
        bot.level().setBlock(oldEntry,
                Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        bot.level().setBlock(oldEntry.above(),
                Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        bot.level().setBlock(alternateEntry,
                Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        BotMemoryStore.INSTANCE.of(bot.getUUID()).markPlace(
                "depot", bot.level(), alternateEntry);
        Map<String, String> retry = new LinkedHashMap<>(open);
        retry.put("phase", "SEAL_DISPOSAL_POCKET");
        retry.put("pocket_drop_committed", "true");
        retry.put("pocket_failure", "retry_disposal_ore:"
                + rejected.name() + ":fixture");
        require(context, MiningServiceTask.inspectCheckpoint(retry).isPresent(),
                "reroute atomic fixture checkpoint was invalid: " + retry);
        int budgetBefore = Integer.parseInt(retry.get("budget_used"));
        MiningServiceTask restored = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), retry, policy,
                0, "reroute-atomic", 0, cursor);
        restored.start(bot);
        for (int tick = 0; tick < 20 && restored.state() == TaskState.RUNNING; tick++) {
            restored.tick(bot);
        }
        require(context, restored.state() == TaskState.FAILED
                        && restored.failureReason().startsWith(
                        "mining_service_disposal_no_alternate_after_ore:"),
                "alternate-start failure did not remain bounded: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, isSolid(bot, oldEntry) && isSolid(bot, oldEntry.above()),
                "alternate selection ran before old pocket was double-sealed");
        Map<String, String> terminal = restored.checkpoint();
        require(context, "PREPARE".equals(terminal.get("phase"))
                        && terminal.keySet().stream()
                        .noneMatch(key -> key.startsWith("pocket_"))
                        && Integer.parseInt(terminal.get("budget_used")) >= budgetBefore
                        && MiningServiceTask.inspectCheckpoint(terminal).isPresent(),
                "alternate-start failure published a half-pocket checkpoint: " + terminal);
        cleanup(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_restored_open_clear_zero_with_factually_broken_entry_seals_at_hard_window", maxTicks = 80)
    public void restoredOpenClearZeroWithFactuallyBrokenEntrySealsAtHardWindow(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceOpenMutationBudgetGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.blockPosition().immutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        ServicePolicy policy =
                ServicePolicy.defaultOre(false);
        MiningServiceTask original = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "open-mutation-budget", 0, cursor);
        original.start(bot);
        Map<String, String> open = tickUntilServicePhase(
                original, bot, "OPEN_DISPOSAL_POCKET", 10);
        original.abort(bot);
        Direction direction = Direction.valueOf(open.get("pocket_direction"));
        BlockPos entry = face.relative(direction);
        bot.level().setBlock(entry,
                Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Map<String, String> hard = new LinkedHashMap<>(open);
        hard.put("budget_used", "4800");
        hard.put("last_progress_budget", "4800");
        require(context, "0".equals(hard.get("pocket_clear_index"))
                        && MiningServiceTask.inspectCheckpoint(hard).isPresent(),
                "OPEN clear-zero factual-mutation checkpoint was invalid: " + hard);
        MiningServiceTask restored = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), hard, policy,
                0, "open-mutation-budget", 0, cursor);
        restored.start(bot);
        for (int tick = 0; tick < 10 && restored.state() == TaskState.RUNNING; tick++) {
            restored.tick(bot);
        }
        require(context, restored.state() == TaskState.FAILED
                        && "mining_service_timeout:OPEN_DISPOSAL_POCKET"
                        .equals(restored.failureReason()),
                "OPEN clear-zero mutation bypassed terminal recovery: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, isSolid(bot, entry) && isSolid(bot, entry.above()),
                "OPEN factual first-break was left unsealed at hard timeout");
        Map<String, String> terminal = restored.checkpoint();
        require(context, "4800".equals(terminal.get("budget_used"))
                        && MiningServiceTask.inspectCheckpoint(terminal).isPresent(),
                "OPEN geometry terminal checkpoint reset budget or became invalid: "
                        + terminal);
        cleanup(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_restored_capture_empty_ledger_seals_at_hard_window", maxTicks = 80)
    public void restoredCaptureEmptyLedgerSealsAtHardWindow(GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceCaptureMutationBudgetGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.blockPosition().immutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        ServicePolicy policy =
                ServicePolicy.defaultOre(false);
        MiningServiceTask original = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "capture-mutation-budget", 0, cursor);
        original.start(bot);
        Map<String, String> open = tickUntilServicePhase(
                original, bot, "OPEN_DISPOSAL_POCKET", 10);
        original.abort(bot);
        Direction direction = Direction.valueOf(open.get("pocket_direction"));
        BlockPos entry = face.relative(direction);
        BlockPos sink = face.relative(direction, 2);
        for (BlockPos cell : new BlockPos[]{entry, entry.above(), sink, sink.above()}) {
            bot.level().setBlock(
                    cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        Map<String, String> hard = new LinkedHashMap<>(open);
        hard.put("phase", "CAPTURE_DISPOSAL_BASELINE");
        hard.put("pocket_clear_index", "4");
        hard.put("budget_used", "4800");
        hard.put("last_progress_budget", "4800");
        require(context, hard.getOrDefault("pocket_ledger", "").isBlank()
                        && hard.getOrDefault("pocket_entities", "").isBlank()
                        && MiningServiceTask.inspectCheckpoint(hard).isPresent(),
                "CAPTURE empty-ledger hard checkpoint was invalid: " + hard);
        MiningServiceTask restored = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), hard, policy,
                0, "capture-mutation-budget", 0, cursor);
        restored.start(bot);
        for (int tick = 0; tick < 10 && restored.state() == TaskState.RUNNING; tick++) {
            restored.tick(bot);
        }
        require(context, restored.state() == TaskState.FAILED
                        && "mining_service_timeout:CAPTURE_DISPOSAL_BASELINE"
                        .equals(restored.failureReason()),
                "CAPTURE empty-ledger geometry debt bypassed terminal recovery: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, isSolid(bot, entry) && isSolid(bot, entry.above()),
                "CAPTURE geometry debt failed before double seal");
        Map<String, String> terminal = restored.checkpoint();
        require(context, "4800".equals(terminal.get("budget_used"))
                        && MiningServiceTask.inspectCheckpoint(terminal).isPresent(),
                "CAPTURE hard terminal checkpoint reset budget or became invalid: "
                        + terminal);
        cleanup(context, fixture);
    }

    @GameTest(maxTicks = 40)
    public void fullHungerAndRawMeatDoNotBypassSafeReserve(GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceRawFoodGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BEEF, 64));
        bot.getFoodData().setFoodLevel(20);

        MiningServiceTask task = new MiningServiceTask(Set.of(Blocks.DIAMOND_ORE));
        task.start(bot);
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED) {
                require(context, task.failureReason().startsWith(
                                "mining_service_food_reserve_depleted:have=0:required=2"),
                        "wrong fail-closed reason: " + task.failureReason());
                require(context, InventoryAction.countItem(bot, Items.BEEF) == 64,
                        "service consumed raw meat instead of rejecting it");
                cleanup(context, fixture);
            } else if (task.state() == TaskState.COMPLETED
                    || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("unsafe food reserve ended as " + task.state()));
            }
        });
    }

    @GameTest(maxTicks = 500)
    public void depotWithdrawsSafeFoodAndLeavesDangerousFoodUntouched(GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceDepotFoodGT", true);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        Container depot = ContainerAction.resolve(bot, fixture.depot()).orElseThrow();
        depot.setItem(0, new ItemStack(Items.ROTTEN_FLESH, 8));
        depot.setItem(1, new ItemStack(Items.BREAD, 2));
        depot.setChanged();

        MiningServiceTask task = new MiningServiceTask(Set.of(Blocks.DIAMOND_ORE));
        task.start(bot);
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("safe depot service ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.BREAD) == 2,
                    "service did not withdraw the two-unit safe reserve");
            require(context, depot.getItem(0).is(Items.ROTTEN_FLESH)
                            && depot.getItem(0).getCount() == 8,
                    "service withdrew dangerous food before safe food");
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 200)
    public void localCraftsTunnelingToolsWithoutDepot(GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceLocalToolsGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 28));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));

        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), true);
        task.start(bot);
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("local channel-tool service ended as "
                        + task.state() + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.STONE_PICKAXE) == 4,
                    "service did not locally craft four tunneling picks");
            require(context, InventoryAction.countItem(bot, Items.IRON_PICKAXE) == 1,
                    "service consumed the target-grade iron pickaxe");
            require(context, InventoryAction.countItem(bot, Items.BREAD) == 2,
                    "service consumed the safe reserve instead of checking it");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 16,
                    "local craft consumed the emergency stone reserve");
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 700)
    public void noDepotThreeFreeSlotsReclaimsDeadPicksAndJunkBeforeService(
            GameTestHelper context) {
        require(context, MiningServiceTask.reconciledPocketBaselineCount(
                        2, 64, 64, 64) == 0
                        && MiningServiceTask.reconciledPocketBaselineCount(
                        2, 65, 64, 64) == 1
                        && MiningServiceTask.reconciledPocketBaselineCount(
                        2, 64, 64, 63) == 2
                        && MiningServiceTask.reconciledPocketBaselineCount(
                        2, 63, 64, 64) == 2
                        && MiningServiceTask.reconciledPocketBaselineCount(
                        2, 66, 64, 64) == 2,
                "baseline reconciliation policy no longer requires an intact tracked ledger");
        Fixture fixture = spawn(context, "MiningServiceNoDepotSlotsGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 30));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        giveExhaustedPick(bot, Items.WOODEN_PICKAXE);
        giveExhaustedPick(bot, Items.STONE_PICKAXE);
        giveExhaustedPick(bot, Items.IRON_PICKAXE);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.GRAVEL, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.SAND, 64));
        for (int index = 0; index < 22; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }

        require(context, freeMainSlots(bot) == 3,
                "test precondition did not leave exactly three free slots: "
                        + freeMainSlots(bot));
        require(context, unusableCheapPickaxes(bot) == 3,
                "test precondition did not carry three unusable cheap pickaxes");

        BlockPos face = bot.blockPosition().immutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        BlockPos entry = face.east();
        BlockPos sink = face.east(2);
        for (BlockPos cell : new BlockPos[]{entry, entry.above(), sink, sink.above()}) {
            bot.level().setBlock(
                    cell, Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
        }
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                ServicePolicy.defaultOre(true),
                0, "sealed-pocket-test", 0, cursor);
        task.start(bot);
        int[] serviceTicks = {0};
        int[] serviceCompletedAt = {-1};
        OreDigTask[] nextBatch = {null};
        BlockPos nextOre = bot.blockPosition().north(2).immutable();
        AtomicReference<ItemEntity> controlledBaseline = new AtomicReference<>();
        AtomicBoolean baselineRemoved = new AtomicBoolean();
        AtomicBoolean baselineRebased = new AtomicBoolean();
        context.failIfEver(() -> {
            serviceTicks[0]++;
            Map<String, String> before = task.checkpoint();
            if (task.state() == TaskState.RUNNING
                    && controlledBaseline.get() == null
                    && "CAPTURE_DISPOSAL_BASELINE".equals(before.get("phase"))) {
                ItemEntity baseline = new ItemEntity(
                        bot.level(), sink.getX() + 0.5D, sink.getY() + 0.25D,
                        sink.getZ() + 0.5D, new ItemStack(Items.DIRT, 2));
                baseline.setDeltaMovement(Vec3.ZERO);
                baseline.setNeverPickUp();
                require(context, bot.level().addFreshEntity(baseline),
                        "three-slot fixture failed to spawn its controlled sink baseline");
                controlledBaseline.set(baseline);
            }
            if (task.state() == TaskState.RUNNING
                    && !baselineRemoved.get()
                    && controlledBaseline.get() != null
                    && "DROP_DISPOSABLE".equals(before.get("phase"))) {
                require(context, "minecraft:dirt=2".equals(before.get("pocket_baseline")),
                        "controlled baseline was not durably frozen: " + before);
                require(context, MiningServiceTask.inspectCheckpoint(before).isPresent(),
                        "baseline identity reset checkpoint failed strict decoding: " + before);
                controlledBaseline.get().discard();
                baselineRemoved.set(true);
            }
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (baselineRemoved.get()
                    && live.getOrDefault("pocket_ledger", "").contains("minecraft:dirt=")
                    && !live.getOrDefault("pocket_baseline", "")
                    .contains("minecraft:dirt=")) {
                baselineRebased.set(true);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("three-slot no-depot service ended as "
                        + task.state() + ":" + task.failureReason()
                        + " checkpoint=" + task.checkpoint()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            if (serviceCompletedAt[0] < 0) {
                serviceCompletedAt[0] = serviceTicks[0];
                require(context, bot.blockPosition().equals(face),
                        "service did not complete at the exact cursor face anchor");
                require(context, unusableCheapPickaxes(bot) == 1,
                        "service dropped the exhausted iron pickaxe instead of preserving it");
                require(context, InventoryAction.countItem(bot, Items.IRON_PICKAXE) == 2,
                        "service did not preserve both healthy and exhausted iron pickaxes");
                require(context, InventoryAction.countItem(bot, Items.STONE_PICKAXE) == 4,
                        "service did not craft four replacement tunneling pickaxes");
                int channelDurability = bot.getInventory().getNonEquipmentItems().stream()
                        .filter(stack -> stack.is(Items.STONE_PICKAXE))
                        .mapToInt(MiningServiceTask::usableDurability)
                        .sum();
                require(context, channelDurability >= 520,
                        "replacement tunneling durability is below four fresh stone picks: "
                                + channelDurability);
                require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 17,
                        "cleanup did not retain the post-service target-support allowance");
                require(context, InventoryAction.countItem(bot, Items.DIRT) == 0
                                && InventoryAction.countItem(bot, Items.GRAVEL) == 0
                                && InventoryAction.countItem(bot, Items.SAND) == 0,
                        "service did not drop enough low-value stacks to fund its craft outputs");
                require(context, freeMainSlots(bot) >= 4,
                        "service completed without the four-slot postcondition: free="
                                + freeMainSlots(bot));
                require(context, baselineRemoved.get() && baselineRebased.get(),
                        "three-slot service skipped the controlled baseline rebase race");
                bot.level().setBlock(nextOre,
                        Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
                nextBatch[0] = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1);
                nextBatch[0].start(bot);
            }
            if (nextBatch[0].state() == TaskState.RUNNING) {
                nextBatch[0].tick(bot);
            }
            if (nextBatch[0].state() == TaskState.FAILED
                    || nextBatch[0].state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("post-service ore batch ended as "
                        + nextBatch[0].state() + ":" + nextBatch[0].failureReason()));
            }
            if (nextBatch[0].state() != TaskState.COMPLETED
                    || serviceTicks[0] - serviceCompletedAt[0] <= 100) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.DIAMOND) == 1,
                    "next OreDig batch did not physically collect its target");
            // The service preserved the exhausted iron pickaxe (asserted above, at its completion). Tools are used until they break,
            // worst-first with the more worn of two equal ones first: the diamond is mined with the exhausted pickaxe, whose single
            // use left breaks on it (nothing was lost by the service), and the healthy iron pickaxe is the one that remains.
            require(context, unusableCheapPickaxes(bot) == 0 && InventoryAction.countItem(bot, Items.IRON_PICKAXE) == 1,
                    "the next ore batch did not use up exactly the exhausted iron pickaxe: exhausted left="
                            + unusableCheapPickaxes(bot) + " iron pickaxes=" + InventoryAction.countItem(bot, Items.IRON_PICKAXE));
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 0
                            && InventoryAction.countItem(bot, Items.GRAVEL) == 0
                            && InventoryAction.countItem(bot, Items.SAND) == 0,
                    "junk returned after the next mining batch started");
            require(context, freeMainSlots(bot) > 0,
                    "post-service pickup consumed every reclaimed inventory slot");
            java.util.List<ItemEntity> sealed = bot.level().getEntitiesOfClass(
                    ItemEntity.class, sinkBox(sink), ItemEntity::isAlive);
            require(context, !sealed.isEmpty(),
                    "disposal ledger had no surviving vanilla ItemEntity in the sealed sink");
            require(context, isSolid(bot, face.east()) && isSolid(bot, face.east().above()),
                    "disposal pocket mouth was not sealed at both player cells");
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 500)
    public void committedDisposalRestoreWaitsPastPickupDelayAndSealsWithoutRedrop(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServicePocketRestoreGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        require(context, freeMainSlots(bot) == 3,
                "restore fixture did not begin with exactly three free slots");

        BlockPos face = bot.blockPosition().immutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        ServicePolicy policy =
                ServicePolicy.defaultOre(false);
        MiningServiceTask original = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "pocket-restore", 0, cursor);
        original.start(bot);

        Map<String, String>[] committed = new Map[]{null};
        MiningServiceTask[] restored = {null};
        int[] committedAt = {-1};
        int[] elapsed = {0};
        boolean[] suppliesRestarted = {false};
        context.failIfEver(() -> {
            elapsed[0]++;
            if (committed[0] == null && original.state() == TaskState.RUNNING) {
                original.tick(bot);
                Map<String, String> live = original.checkpoint();
                if ("SEAL_DISPOSAL_POCKET".equals(live.get("phase"))
                        && "true".equals(live.get("pocket_drop_committed"))) {
                    committed[0] = live;
                    committedAt[0] = elapsed[0];
                    original.abort(bot);
                    require(context, InventoryAction.countItem(bot, Items.DIRT) == 2,
                            "committed checkpoint did not retain exactly two physical seal blocks");
                    require(context, bot.blockPosition().equals(face),
                            "drop-committed checkpoint left the bot at the pocket mouth");
                }
            }
            if (committed[0] == null && original.state() == TaskState.FAILED) {
                context.fail(Component.nullToEmpty("original disposal failed: "
                        + original.failureReason()));
            }
            if (committed[0] == null || restored[0] != null
                    || elapsed[0] - committedAt[0] <= 60) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 2,
                    "vanilla drop returned after its default pickup delay during interruption");
            require(context, sinkCount(bot, face.east(2), Items.DIRT) >= 62,
                    "committed dirt ledger was not physically present in the sink before restore");
            restored[0] = new MiningServiceTask(
                    Set.of(Blocks.DIAMOND_ORE), committed[0], policy,
                    0, "pocket-restore", 0, cursor);
            restored[0].start(bot);
        });
        context.failIfEver(() -> {
            if (restored[0] == null) {
                return;
            }
            if (restored[0].state() == TaskState.RUNNING) {
                restored[0].tick(bot);
            }
            Map<String, String> live = restored[0].checkpoint();
            if (!suppliesRestarted[0] && "SUPPLIES".equals(live.get("phase"))) {
                require(context, live.keySet().stream()
                                .noneMatch(key -> key.startsWith("pocket_")),
                        "settled SUPPLIES checkpoint retained pocket transaction payload: "
                                + live);
                require(context, MiningServiceTask.inspectCheckpoint(live).isPresent(),
                        "settled SUPPLIES checkpoint could not decode itself: " + live);
                String budget = live.get("budget_used");
                restored[0].abort(bot);
                restored[0] = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), live, policy,
                        0, "pocket-restore", 0, cursor);
                restored[0].start(bot);
                require(context, budget.equals(restored[0].checkpoint().get("budget_used")),
                        "SUPPLIES restore refreshed the service hard budget");
                suppliesRestarted[0] = true;
                return;
            }
            if (restored[0].state() == TaskState.FAILED
                    || restored[0].state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("restored disposal ended as "
                        + restored[0].state() + ":" + restored[0].failureReason()));
            }
            if (restored[0].state() != TaskState.COMPLETED) {
                return;
            }
            require(context, suppliesRestarted[0],
                    "fixture never restored the post-seal SUPPLIES checkpoint");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 0,
                    "restore repeated disposal or skipped spending the two seal blocks");
            require(context, InventoryAction.countItem(bot, Items.GLASS) == 30 * 64,
                    "restore discarded a protected non-junk inventory stack");
            require(context, bot.blockPosition().equals(face),
                    "restored disposal did not finish at the exact cursor face");
            require(context, isSolid(bot, face.east()) && isSolid(bot, face.east().above()),
                    "restored disposal skipped one of the two physical mouth seals");
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 700)
    public void committedSettlePauseMoveResumeReturnsAndSealsBothMouthCells(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceDebtReturnGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.blockPosition().immutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                ServicePolicy.defaultOre(false),
                0, "debt-return", 0, cursor);
        task.start(bot);
        MiningServiceTask[] active = {task};
        boolean[] moved = {false};
        boolean[] checkpointedReturn = {false};

        context.failIfEver(() -> {
            if (active[0].state() == TaskState.RUNNING) {
                active[0].tick(bot);
            }
            Map<String, String> live = active[0].checkpoint();
            if (!moved[0]
                    && "SETTLE_DISPOSABLE".equals(live.get("phase"))
                    && !live.getOrDefault("pocket_ledger", "").isBlank()) {
                active[0].pause(bot);
                BlockPos away = face.west();
                bot.teleportTo(bot.level(), away.getX() + 0.5D,
                        away.getY(), away.getZ() + 0.5D,
                        Set.of(), 0.0F, 0.0F, true);
                active[0].resume(bot);
                moved[0] = true;
                return;
            }
            if (moved[0] && !checkpointedReturn[0]
                    && "RETURN_TO_DISPOSAL_FACE".equals(live.get("phase"))) {
                require(context, MiningServiceTask.inspectCheckpoint(live).isPresent(),
                        "return-to-pocket debt checkpoint was not restartable: " + live);
                require(context, Integer.parseInt(live.get("budget_used")) > 0,
                        "return-to-pocket checkpoint reset its hard budget");
                active[0].abort(bot);
                active[0] = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), live,
                        ServicePolicy.defaultOre(false),
                        0, "debt-return", 0, cursor);
                active[0].start(bot);
                require(context, live.get("budget_used").equals(
                                active[0].checkpoint().get("budget_used")),
                        "RETURN restore refreshed the committed hard budget");
                checkpointedReturn[0] = true;
            }
            if (active[0].state() == TaskState.FAILED
                    || active[0].state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("moved disposal debt ended as "
                        + active[0].state() + ":" + active[0].failureReason()));
            }
            if (active[0].state() != TaskState.COMPLETED) {
                return;
            }
            require(context, moved[0] && checkpointedReturn[0],
                    "fixture never exercised the durable return phase");
            require(context, bot.blockPosition().equals(face),
                    "debt recovery did not return to the exact work face");
            require(context, isSolid(bot, face.east()) && isSolid(bot, face.east().above()),
                    "debt recovery completed without both observable mouth seals");
            cleanup(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_unreachable_unsealed_return_fails_boundedly_and_keeps_restartable_debt", maxTicks = 500)
    public void unreachableUnsealedReturnFailsBoundedlyAndKeepsRestartableDebt(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceUnsealedReturnGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.blockPosition().immutable();
        BlockPos entry = face.east();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        ServicePolicy policy =
                ServicePolicy.defaultOre(false);
        MiningServiceTask[] active = {new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "unsealed-return", 0, cursor)};
        active[0].start(bot);
        AtomicBoolean trapped = new AtomicBoolean();
        AtomicReference<String> ledger = new AtomicReference<>();
        AtomicReference<String> identities = new AtomicReference<>();

        context.failIfEver(() -> {
            MiningServiceTask task = active[0];
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (!trapped.get()
                    && "SETTLE_DISPOSABLE".equals(live.get("phase"))
                    && !live.getOrDefault("pocket_ledger", "").isBlank()) {
                require(context, MiningServiceTask.inspectCheckpoint(live).isPresent(),
                        "unsealed-return fixture produced an invalid SETTLE checkpoint: " + live);
                ledger.set(live.get("pocket_ledger"));
                identities.set(live.get("pocket_entities"));
                require(context, !identities.get().isBlank(),
                        "unsealed-return fixture committed a ledger without UUID authority");
                task.abort(bot);
                BlockPos away = face.west(2);
                bot.teleportTo(bot.level(), away.getX() + 0.5D,
                        away.getY(), away.getZ() + 0.5D,
                        Set.of(), 0.0F, 0.0F, true);
                buildBedrockCage(bot, away);
                active[0] = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), live, policy,
                        0, "unsealed-return", 0, cursor);
                active[0].start(bot);
                trapped.set(true);
                return;
            }
            task = active[0];
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("unreachable unsealed debt ended as "
                        + task.state()));
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, trapped.get()
                            && task.failureReason().startsWith(
                            "mining_service_disposal_unsealed_return_failed:"),
                    "unreachable unsealed return lost its typed bounded failure: "
                            + task.failureReason());
            Map<String, String> terminal = task.checkpoint();
            require(context, "RETURN_TO_DISPOSAL_FACE".equals(terminal.get("phase"))
                            && task.failureReason().equals(terminal.get("pocket_failure"))
                            && ledger.get().equals(terminal.get("pocket_ledger"))
                            && identities.get().equals(terminal.get("pocket_entities"))
                            && MiningServiceTask.inspectCheckpoint(terminal).isPresent(),
                    "unreachable return cleared or invalidated unresolved pocket debt: "
                            + terminal);
            require(context, !isSolid(bot, entry) && !isSolid(bot, entry.above()),
                    "unreachable return claimed a physical seal it could not reach");

            MiningServiceTask restored = new MiningServiceTask(
                    Set.of(Blocks.DIAMOND_ORE), terminal, policy,
                    0, "unsealed-return", 0, cursor);
            restored.start(bot);
            Map<String, String> restarted = restored.checkpoint();
            require(context, restored.state() == TaskState.RUNNING
                            && "RETURN_TO_DISPOSAL_FACE".equals(restarted.get("phase"))
                            && ledger.get().equals(restarted.get("pocket_ledger"))
                            && identities.get().equals(restarted.get("pocket_entities"))
                            && MiningServiceTask.inspectCheckpoint(restarted).isPresent(),
                    "terminal unsealed debt could not be restored without false settlement: "
                            + restarted);
            restored.abort(bot);
            cleanup(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_prebaseline_capture_pause_move_restart_returns_in_capture_and_completes", maxTicks = 1200)
    public void prebaselineCapturePauseMoveRestartReturnsInCaptureAndCompletes(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServicePrebaselineReturnGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.ANDESITE, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.GRAVEL, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.SAND, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        require(context, freeMainSlots(bot) == 0,
                "prebaseline-return fixture was not inventory-full");
        BlockPos face = bot.blockPosition().immutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        ServicePolicy policy =
                ServicePolicy.defaultOre(false);
        MiningServiceTask[] active = {new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "prebaseline-return", 0, cursor)};
        active[0].start(bot);
        AtomicBoolean restartedAway = new AtomicBoolean();
        AtomicBoolean returnedInCapture = new AtomicBoolean();

        context.failIfEver(() -> {
            MiningServiceTask task = active[0];
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (!restartedAway.get()
                    && "CAPTURE_DISPOSAL_BASELINE".equals(live.get("phase"))
                    && "true".equals(live.get("pocket_drop_committed"))
                    && live.getOrDefault("pocket_ledger", "").isBlank()
                    && !live.getOrDefault("pocket_entities", "").isBlank()) {
                task.pause(bot);
                BlockPos away = face.west();
                bot.teleportTo(bot.level(), away.getX() + 0.5D,
                        away.getY(), away.getZ() + 0.5D,
                        Set.of(), 0.0F, 0.0F, true);
                task.resume(bot);
                Map<String, String> moved = task.checkpoint();
                require(context, "CAPTURE_DISPOSAL_BASELINE".equals(moved.get("phase"))
                                && MiningServiceTask.inspectCheckpoint(moved).isPresent(),
                        "prebaseline move lost CAPTURE checkpoint authority: " + moved);
                String budget = moved.get("budget_used");
                task.abort(bot);
                active[0] = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), moved, policy,
                        0, "prebaseline-return", 0, cursor);
                active[0].start(bot);
                require(context, budget.equals(active[0].checkpoint().get("budget_used")),
                        "prebaseline CAPTURE restart reset the hard budget");
                restartedAway.set(true);
                return;
            }
            task = active[0];
            live = task.checkpoint();
            if (restartedAway.get() && !returnedInCapture.get()
                    && bot.blockPosition().equals(face)) {
                require(context, "CAPTURE_DISPOSAL_BASELINE".equals(live.get("phase"))
                                && MiningServiceTask.inspectCheckpoint(live).isPresent(),
                        "physical return skipped prebaseline containment/baseline authority: "
                                + live);
                returnedInCapture.set(true);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("prebaseline CAPTURE return ended as "
                        + task.state() + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, restartedAway.get() && returnedInCapture.get(),
                    "fixture skipped durable CAPTURE return/restart");
            require(context, isSolid(bot, face.east()) && isSolid(bot, face.east().above()),
                    "prebaseline return completed without double seal");
            cleanup(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_terminal_prebaseline_return_checkpoint_restores_seals_and_fails_original_reason", maxTicks = 700)
    public void terminalPrebaselineReturnCheckpointRestoresSealsAndFailsOriginalReason(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServicePrebaselineTerminalGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.ANDESITE, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.GRAVEL, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.SAND, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.blockPosition().immutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        ServicePolicy policy =
                ServicePolicy.defaultOre(false);
        MiningServiceTask[] active = {new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "prebaseline-terminal", 0, cursor)};
        active[0].start(bot);
        AtomicBoolean restoredTerminal = new AtomicBoolean();
        AtomicBoolean sawTerminalSeal = new AtomicBoolean();
        String terminalReason =
                "mining_service_disposal_prebaseline_return_failed:fixture";

        context.failIfEver(() -> {
            MiningServiceTask task = active[0];
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (!restoredTerminal.get()
                    && "CAPTURE_DISPOSAL_BASELINE".equals(live.get("phase"))
                    && "true".equals(live.get("pocket_drop_committed"))
                    && live.getOrDefault("pocket_ledger", "").isBlank()
                    && !live.getOrDefault("pocket_entities", "").isBlank()) {
                Map<String, String> terminal = new LinkedHashMap<>(live);
                terminal.put("phase", "RETURN_TO_DISPOSAL_FACE");
                terminal.put("pocket_failure", terminalReason);
                require(context, MiningServiceTask.inspectCheckpoint(terminal).isPresent(),
                        "typed terminal prebaseline RETURN checkpoint was rejected: " + terminal);
                task.abort(bot);
                BlockPos away = face.west();
                bot.teleportTo(bot.level(), away.getX() + 0.5D,
                        away.getY(), away.getZ() + 0.5D,
                        Set.of(), 0.0F, 0.0F, true);
                active[0] = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), terminal, policy,
                        0, "prebaseline-terminal", 0, cursor);
                active[0].start(bot);
                restoredTerminal.set(true);
                return;
            }
            task = active[0];
            live = task.checkpoint();
            if (restoredTerminal.get()
                    && "SEAL_DISPOSAL_POCKET".equals(live.get("phase"))) {
                require(context, MiningServiceTask.inspectCheckpoint(live).isPresent(),
                        "terminal prebaseline SEAL checkpoint rejected itself: " + live);
                sawTerminalSeal.set(true);
            }
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("terminal prebaseline debt ended as "
                        + task.state()));
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, restoredTerminal.get() && sawTerminalSeal.get()
                            && terminalReason.equals(task.failureReason()),
                    "terminal prebaseline lost its original typed outcome: "
                            + task.failureReason());
            require(context, isSolid(bot, face.east()) && isSolid(bot, face.east().above()),
                    "terminal prebaseline failed before double seal");
            require(context, MiningServiceTask.inspectCheckpoint(task.checkpoint()).isPresent(),
                    "terminal prebaseline failure checkpoint lost restore authority");
            cleanup(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_open_geometry_debt_move_restarts_return_and_fails_only_after_double_seal", maxTicks = 700)
    public void openGeometryDebtMoveRestartsReturnAndFailsOnlyAfterDoubleSeal(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceOpenMoveDebtGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.blockPosition().immutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        ServicePolicy policy =
                ServicePolicy.defaultOre(false);
        MiningServiceTask original = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "open-move-debt", 0, cursor);
        original.start(bot);
        Map<String, String> open = tickUntilServicePhase(
                original, bot, "OPEN_DISPOSAL_POCKET", 10);
        original.abort(bot);
        Direction direction = Direction.valueOf(open.get("pocket_direction"));
        BlockPos entry = face.relative(direction);
        bot.level().setBlock(entry,
                Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos away = face.west();
        bot.teleportTo(bot.level(), away.getX() + 0.5D,
                away.getY(), away.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        MiningServiceTask[] active = {new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), open, policy,
                0, "open-move-debt", 0, cursor)};
        active[0].start(bot);
        AtomicBoolean restartedReturn = new AtomicBoolean();
        AtomicBoolean sawSeal = new AtomicBoolean();
        String expected = "mining_service_disposal_geometry_anchor_changed:phase="
                + "OPEN_DISPOSAL_POCKET:at=" + away.toShortString();

        context.failIfEver(() -> {
            MiningServiceTask task = active[0];
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (!restartedReturn.get()
                    && "RETURN_TO_DISPOSAL_FACE".equals(live.get("phase"))) {
                require(context, expected.equals(live.get("pocket_failure"))
                                && MiningServiceTask.inspectCheckpoint(live).isPresent(),
                        "OPEN move did not publish restartable unresolved geometry debt: "
                                + live);
                String budget = live.get("budget_used");
                task.abort(bot);
                active[0] = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), live, policy,
                        0, "open-move-debt", 0, cursor);
                active[0].start(bot);
                require(context, budget.equals(active[0].checkpoint().get("budget_used")),
                        "OPEN geometry RETURN restart reset budget");
                restartedReturn.set(true);
                return;
            }
            task = active[0];
            live = task.checkpoint();
            if (restartedReturn.get()
                    && "SEAL_DISPOSAL_POCKET".equals(live.get("phase"))) {
                require(context, MiningServiceTask.inspectCheckpoint(live).isPresent(),
                        "OPEN geometry SEAL checkpoint rejected itself: " + live);
                sawSeal.set(true);
            }
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("OPEN geometry debt ended as " + task.state()));
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, restartedReturn.get() && sawSeal.get()
                            && expected.equals(task.failureReason()),
                    "OPEN geometry debt lost exact terminal reason: "
                            + task.failureReason());
            require(context, isSolid(bot, entry) && isSolid(bot, entry.above()),
                    "OPEN move debt failed before double seal");
            require(context, MiningServiceTask.inspectCheckpoint(task.checkpoint()).isPresent(),
                    "OPEN move terminal checkpoint lost restore authority");
            cleanup(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_capture_empty_ledger_move_restarts_return_and_fails_only_after_double_seal", maxTicks = 700)
    public void captureEmptyLedgerMoveRestartsReturnAndFailsOnlyAfterDoubleSeal(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceCaptureMoveDebtGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.blockPosition().immutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        ServicePolicy policy =
                ServicePolicy.defaultOre(false);
        MiningServiceTask original = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "capture-move-debt", 0, cursor);
        original.start(bot);
        Map<String, String> open = tickUntilServicePhase(
                original, bot, "OPEN_DISPOSAL_POCKET", 10);
        original.abort(bot);
        Direction direction = Direction.valueOf(open.get("pocket_direction"));
        BlockPos entry = face.relative(direction);
        BlockPos sink = face.relative(direction, 2);
        for (BlockPos cell : new BlockPos[]{entry, entry.above(), sink, sink.above()}) {
            bot.level().setBlock(
                    cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        Map<String, String> capture = new LinkedHashMap<>(open);
        capture.put("phase", "CAPTURE_DISPOSAL_BASELINE");
        capture.put("pocket_clear_index", "4");
        require(context, MiningServiceTask.inspectCheckpoint(capture).isPresent(),
                "CAPTURE empty-ledger move fixture was invalid: " + capture);
        BlockPos away = face.west();
        bot.teleportTo(bot.level(), away.getX() + 0.5D,
                away.getY(), away.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        MiningServiceTask[] active = {new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), capture, policy,
                0, "capture-move-debt", 0, cursor)};
        active[0].start(bot);
        AtomicBoolean restartedReturn = new AtomicBoolean();
        AtomicBoolean sawSeal = new AtomicBoolean();
        String expected = "mining_service_disposal_geometry_anchor_changed:phase="
                + "CAPTURE_DISPOSAL_BASELINE:at=" + away.toShortString();

        context.failIfEver(() -> {
            MiningServiceTask task = active[0];
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (!restartedReturn.get()
                    && "RETURN_TO_DISPOSAL_FACE".equals(live.get("phase"))) {
                require(context, expected.equals(live.get("pocket_failure"))
                                && MiningServiceTask.inspectCheckpoint(live).isPresent(),
                        "CAPTURE move did not publish restartable geometry debt: " + live);
                String budget = live.get("budget_used");
                task.abort(bot);
                active[0] = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), live, policy,
                        0, "capture-move-debt", 0, cursor);
                active[0].start(bot);
                require(context, budget.equals(active[0].checkpoint().get("budget_used")),
                        "CAPTURE geometry RETURN restart reset budget");
                restartedReturn.set(true);
                return;
            }
            task = active[0];
            live = task.checkpoint();
            if (restartedReturn.get()
                    && "SEAL_DISPOSAL_POCKET".equals(live.get("phase"))) {
                require(context, MiningServiceTask.inspectCheckpoint(live).isPresent(),
                        "CAPTURE geometry SEAL checkpoint rejected itself: " + live);
                sawSeal.set(true);
            }
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("CAPTURE geometry debt ended as " + task.state()));
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, restartedReturn.get() && sawSeal.get()
                            && expected.equals(task.failureReason()),
                    "CAPTURE geometry debt lost exact terminal reason: "
                            + task.failureReason());
            require(context, isSolid(bot, entry) && isSolid(bot, entry.above()),
                    "CAPTURE move debt failed before double seal");
            require(context, MiningServiceTask.inspectCheckpoint(task.checkpoint()).isPresent(),
                    "CAPTURE move terminal checkpoint lost restore authority");
            cleanup(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_straddling_tracked_item_must_enter_raw_sink_before_preseal_stability", maxTicks = 180)
    public void straddlingTrackedItemMustEnterRawSinkBeforePresealStability(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceRawSinkGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.NETHERRACK, 2));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        require(context, freeMainSlots(bot) == 3,
                "raw-sink fixture did not trigger the four-slot disposal boundary");
        BlockPos face = bot.blockPosition().immutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        ServicePolicy policy =
                ServicePolicy.defaultOre(false);
        MiningServiceTask bootstrap = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "raw-sink-containment", 0, cursor);
        bootstrap.start(bot);
        Map<String, String> open = tickUntilServicePhase(
                bootstrap, bot, "OPEN_DISPOSAL_POCKET", 10);
        bootstrap.abort(bot);
        require(context, InventoryAction.removeItems(bot, Items.GLASS, 30 * 64),
                "raw-sink fixture could not release its bootstrap-only filler slots");

        Direction direction = Direction.valueOf(open.get("pocket_direction"));
        require(context, direction == Direction.EAST,
                "raw-sink fixture selected its unprepared side: " + direction);
        BlockPos entry = face.relative(direction);
        BlockPos sink = face.relative(direction, 2);
        for (BlockPos cell : new BlockPos[]{entry, entry.above(), sink, sink.above()}) {
            bot.level().setBlock(
                    cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }

        ItemEntity tracked = new ItemEntity(
                bot.level(), sink.getX() + 0.5D, sink.getY() + 0.25D,
                sink.getZ() + 0.5D, new ItemStack(Items.DIRT, 64));
        tracked.setDeltaMovement(Vec3.ZERO);
        tracked.setNoGravity(true);
        tracked.setNeverPickUp();
        double halfWidth = (tracked.getBoundingBox().maxX
                - tracked.getBoundingBox().minX) / 2.0D;
        double centerOffset = 0.5D - halfWidth + 0.005D;
        Vec3 sinkCenter = new Vec3(
                sink.getX() + 0.5D, sink.getY() + 0.25D, sink.getZ() + 0.5D);
        Vec3 straddling = sinkCenter.subtract(
                direction.getStepX() * centerOffset,
                0.0D,
                direction.getStepZ() * centerOffset);
        tracked.snapTo(
                straddling.x, straddling.y, straddling.z, 0.0F, 0.0F);
        AABB rawSink = sinkBox(sink);
        require(context, !fullyContains(rawSink, tracked.getBoundingBox())
                        && fullyContains(rawSink.inflate(0.01D), tracked.getBoundingBox()),
                "controlled item did not isolate raw containment from query tolerance: "
                        + tracked.getBoundingBox());
        require(context, bot.level().addFreshEntity(tracked),
                "failed to spawn controlled raw-sink ledger entity");

        Map<String, String> settle = new LinkedHashMap<>(open);
        settle.put("phase", "SETTLE_DISPOSABLE");
        settle.put("pocket_clear_index", "4");
        settle.put("pocket_entities", tracked.getStringUUID());
        settle.put("pocket_lineage", tracked.getStringUUID()
                + "@minecraft:dirt@64@L");
        settle.put("pocket_baseline", "");
        settle.put("pocket_ledger", "minecraft:dirt=64");
        settle.put("pocket_drop_committed", "true");
        settle.put("pocket_ledger_verified", "false");
        require(context, MiningServiceTask.inspectCheckpoint(settle).isPresent(),
                "raw-sink SETTLE fixture was not restartable: " + settle);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), settle, policy,
                0, "raw-sink-containment", 0, cursor);
        task.start(bot);
        require(context, task.state() == TaskState.RUNNING
                        && "SETTLE_DISPOSABLE".equals(task.checkpoint().get("phase")),
                "raw-sink checkpoint did not restart in SETTLE: "
                        + task.state() + ":" + task.failureReason());

        int[] straddlingTicks = {0};
        boolean[] movedInside = {false};
        context.failIfEver(() -> {
            if (!movedInside[0]) {
                tracked.snapTo(
                        straddling.x, straddling.y, straddling.z, 0.0F, 0.0F);
                tracked.setDeltaMovement(Vec3.ZERO);
            }
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("raw-sink containment ended as "
                        + task.state() + ":" + task.failureReason()));
            }
            if (!movedInside[0]) {
                straddlingTicks[0]++;
                require(context, "SETTLE_DISPOSABLE".equals(
                                task.checkpoint().get("phase"))
                                && !isSolid(bot, entry) && !isSolid(bot, entry.above()),
                        "query tolerance granted physical custody at straddling tick "
                                + straddlingTicks[0] + ":" + task.checkpoint());
                if (straddlingTicks[0] >= 25) {
                    tracked.snapTo(
                            sinkCenter.x, sinkCenter.y, sinkCenter.z, 0.0F, 0.0F);
                    tracked.setDeltaMovement(Vec3.ZERO);
                    movedInside[0] = true;
                }
                return;
            }
            if (!isSolid(bot, entry) || !isSolid(bot, entry.above())) {
                return;
            }
            require(context, tracked.isAlive()
                            && fullyContains(rawSink, tracked.getBoundingBox()),
                    "service sealed without factual raw-sink custody");
            cleanup(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_settle_phase_in_flight_tracked_entity_cannot_be_impersonated_and_times_out", maxTicks = 500)
    public void settlePhaseInFlightTrackedEntityCannotBeImpersonatedAndTimesOut(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceSettleEscapeGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.blockPosition().immutable();
        BlockPos entry = face.east();
        BlockPos sink = face.east(2);
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        ServicePolicy policy =
                ServicePolicy.defaultOre(false);
        MiningServiceTask[] active = {new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "settle-tracked-escape", 0, cursor)};
        active[0].start(bot);
        AtomicBoolean injected = new AtomicBoolean();
        AtomicReference<ItemEntity> escapedRef = new AtomicReference<>();
        AtomicReference<ItemEntity> impostorRef = new AtomicReference<>();
        AtomicReference<ItemEntity> nearerSpoilRef = new AtomicReference<>();

        context.failIfEver(() -> {
            MiningServiceTask task = active[0];
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (!injected.get()) {
                if (task.state() != TaskState.RUNNING) {
                    context.fail(Component.nullToEmpty(
                            "SETTLE tracked-escape fixture ended before injection: "
                                    + task.state() + ":" + task.failureReason()));
                }
                if (!"SETTLE_DISPOSABLE".equals(live.get("phase"))
                        || live.getOrDefault("pocket_ledger", "").isBlank()) {
                    return;
                }
                String[] identities = live.getOrDefault("pocket_entities", "")
                        .split(",", -1);
                // A baseline-zero transaction legitimately has one lineage root: the ordinary
                // player drop recorded by the ledger.  Requiring a separate baseline UUID makes
                // this identity test depend on incidental opening-spoil timing instead of the
                // persisted lineage contract that the restart actually consumes.
                require(context, identities.length >= 1
                                && java.util.Arrays.stream(identities)
                                .noneMatch(String::isBlank)
                                && !live.getOrDefault("pocket_lineage", "").isBlank(),
                        "SETTLE fixture did not expose committed ledger lineage: " + live);
                ItemEntity escaped = java.util.Arrays.stream(identities)
                        .map(java.util.UUID::fromString)
                        .map(id -> bot.level().getEntity(id))
                        .filter(ItemEntity.class::isInstance)
                        .map(ItemEntity.class::cast)
                        .filter(ItemEntity::isAlive)
                        .max(java.util.Comparator.comparingInt(
                                entity -> entity.getItem().getCount()))
                        .orElse(null);
                require(context, escaped != null && escaped.getItem().is(Items.DIRT),
                        "SETTLE lineage had no live dirt survivor before escape injection");
                ItemEntity impostor = new ItemEntity(
                        bot.level(), sink.getX() + 0.5D, sink.getY() + 0.25D,
                        sink.getZ() + 0.5D, escaped.getItem().copy());
                impostor.setDeltaMovement(Vec3.ZERO);
                impostor.setNeverPickUp();
                require(context, bot.level().addFreshEntity(impostor),
                        "failed to spawn SETTLE aggregate impostor");
                ItemEntity nearerSpoil = new ItemEntity(
                        bot.level(), face.getX() + 0.5D, face.getY() + 0.25D,
                        face.getZ() + 0.5D, new ItemStack(Items.CLAY_BALL));
                nearerSpoil.setDeltaMovement(Vec3.ZERO);
                nearerSpoil.setNeverPickUp();
                require(context, bot.level().addFreshEntity(nearerSpoil),
                        "failed to spawn nearer SETTLE untracked spoil");
                escaped.snapTo(
                        entry.getX() + 0.5D, entry.getY() + 0.25D,
                        entry.getZ() + 0.5D, 0.0F, 0.0F);
                escaped.setDeltaMovement(Vec3.ZERO);
                escaped.setNeverPickUp();
                Map<String, String> interrupted = task.checkpoint();
                require(context, "false".equals(
                                interrupted.get("pocket_ledger_verified"))
                                && MiningServiceTask.inspectCheckpoint(interrupted).isPresent(),
                        "SETTLE in-flight checkpoint retained stale verification: "
                                + interrupted);
                task.abort(bot);
                active[0] = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), interrupted, policy,
                        0, "settle-tracked-escape", 0, cursor);
                active[0].start(bot);
                require(context, active[0].state() == TaskState.RUNNING
                                && "SETTLE_DISPOSABLE".equals(
                                active[0].checkpoint().get("phase")),
                        "SETTLE in-flight checkpoint did not restart in place: "
                                + active[0].state() + ":" + active[0].failureReason());
                escapedRef.set(escaped);
                impostorRef.set(impostor);
                nearerSpoilRef.set(nearerSpoil);
                injected.set(true);
                return;
            }
            task = active[0];
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("SETTLE tracked escape ended as " + task.state()));
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, "mining_service_disposal_settle_timeout"
                            .equals(task.failureReason()),
                    "SETTLE in-flight identity propagated the wrong failure: "
                            + task.failureReason());
            require(context, isSolid(bot, entry) && isSolid(bot, entry.above()),
                    "SETTLE in-flight timeout failed before double seal");
            require(context, MiningServiceTask.inspectCheckpoint(task.checkpoint()).isPresent(),
                    "SETTLE timeout checkpoint lost restore authority");
            escapedRef.get().discard();
            impostorRef.get().discard();
            nearerSpoilRef.get().discard();
            cleanup(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_seal_phase_tracked_escape_fails_typed_and_cannot_hide_behind_nearer_spoil", maxTicks = 500)
    public void sealPhaseTrackedEscapeFailsTypedAndCannotHideBehindNearerSpoil(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceTrackedEscapeGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.blockPosition().immutable();
        BlockPos entry = face.east();
        BlockPos sink = face.east(2);
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        ServicePolicy policy =
                ServicePolicy.defaultOre(false);
        MiningServiceTask original = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "tracked-escape", 0, cursor);
        original.start(bot);
        MiningServiceTask[] active = {original};
        AtomicBoolean injected = new AtomicBoolean();
        AtomicReference<ItemEntity> escapedRef = new AtomicReference<>();
        AtomicReference<ItemEntity> impostorRef = new AtomicReference<>();
        AtomicReference<ItemEntity> nearerSpoilRef = new AtomicReference<>();

        context.failIfEver(() -> {
            MiningServiceTask task = active[0];
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (!injected.get()) {
                if (task.state() != TaskState.RUNNING) {
                    context.fail(Component.nullToEmpty(
                            "tracked-escape fixture ended before injection: "
                                    + task.state() + ":" + task.failureReason()));
                }
                if (!"SEAL_DISPOSAL_POCKET".equals(live.get("phase"))
                        || live.getOrDefault("pocket_ledger", "").isBlank()) {
                    return;
                }
                String[] identities = live.getOrDefault("pocket_entities", "")
                        .split(",", -1);
                require(context, identities.length >= 1
                                && java.util.Arrays.stream(identities)
                                .noneMatch(String::isBlank),
                        "tracked-escape fixture did not commit valid identities: " + live);
                ItemEntity escaped = java.util.Arrays.stream(identities)
                        .map(java.util.UUID::fromString)
                        .map(id -> bot.level().getEntity(id))
                        .filter(ItemEntity.class::isInstance)
                        .map(ItemEntity.class::cast)
                        .filter(ItemEntity::isAlive)
                        .findFirst()
                        .orElse(null);
                require(context, escaped != null,
                        "all committed tracked entities vanished before escape injection");
                ItemEntity impostor = new ItemEntity(
                        bot.level(), sink.getX() + 0.5D, sink.getY() + 0.25D,
                        sink.getZ() + 0.5D, escaped.getItem().copy());
                impostor.setDeltaMovement(Vec3.ZERO);
                impostor.setNeverPickUp();
                require(context, bot.level().addFreshEntity(impostor),
                        "failed to spawn aggregate-only sink impostor");
                ItemEntity nearerSpoil = new ItemEntity(
                        bot.level(), face.getX() + 0.5D, face.getY() + 0.25D,
                        face.getZ() + 0.5D, new ItemStack(Items.CLAY_BALL));
                nearerSpoil.setDeltaMovement(Vec3.ZERO);
                nearerSpoil.setNeverPickUp();
                require(context, bot.level().addFreshEntity(nearerSpoil),
                        "failed to spawn nearer untracked opening spoil");
                escaped.snapTo(
                        entry.getX() + 0.5D, entry.getY() + 0.25D,
                        entry.getZ() + 0.5D, 0.0F, 0.0F);
                escaped.setDeltaMovement(Vec3.ZERO);
                escaped.setNeverPickUp();
                task.abort(bot);
                active[0] = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), live, policy,
                        0, "tracked-escape", 0, cursor);
                active[0].start(bot);
                escapedRef.set(escaped);
                impostorRef.set(impostor);
                nearerSpoilRef.set(nearerSpoil);
                injected.set(true);
                return;
            }
            task = active[0];
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("tracked escape ended as " + task.state()));
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, injected.get()
                            && "mining_service_disposal_tracked_entity_escaped"
                            .equals(task.failureReason()),
                    "SEAL tracked escape did not preserve its exact typed failure: "
                            + task.failureReason());
            require(context, isSolid(bot, entry) && isSolid(bot, entry.above()),
                    "SEAL tracked escape failed before factual double seal");
            Map<String, String> terminal = task.checkpoint();
            require(context, MiningServiceTask.inspectCheckpoint(terminal).isPresent()
                            && !terminal.getOrDefault("pocket_ledger", "").isBlank()
                            && !terminal.getOrDefault("pocket_entities", "").isBlank(),
                    "tracked escape lost its durable ledger/UUID debt: " + terminal);
            escapedRef.get().discard();
            impostorRef.get().discard();
            nearerSpoilRef.get().discard();
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 500)
    public void settleTimeoutSealsBothMouthCellsBeforeTypedFailure(GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceDebtTimeoutGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        // Keep the two seals in a different, non-disposable item so the complete dirt stack can
        // become a baseline-zero ledger=64 transaction.
        InventoryAction.giveItem(bot, new ItemStack(Items.NETHERRACK, 2));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        require(context, freeMainSlots(bot) == 2,
                "missing-identity fixture did not begin with exactly two free slots");
        BlockPos face = bot.blockPosition().immutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        BlockPos entry = face.east();
        BlockPos sink = face.east(2);
        for (BlockPos cell : new BlockPos[]{entry, entry.above(), sink, sink.above()}) {
            bot.level().setBlock(
                    cell, Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
        }
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask[] active = {new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                ServicePolicy.defaultOre(false),
                0, "debt-timeout", 0, cursor)};
        active[0].start(bot);
        AtomicBoolean restoredWithMissingIdentity = new AtomicBoolean();
        context.failIfEver(() -> {
            MiningServiceTask task = active[0];
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (!restoredWithMissingIdentity.get()
                    && "SETTLE_DISPOSABLE".equals(live.get("phase"))
                    && "minecraft:dirt=64".equals(live.get("pocket_ledger"))) {
                require(context, live.getOrDefault("pocket_baseline", "").isBlank(),
                        "missing-identity fixture did not preserve baseline=0: " + live);
                String realEntities = live.getOrDefault("pocket_entities", "");
                require(context, !realEntities.isBlank()
                                && !live.getOrDefault("pocket_lineage", "").isBlank(),
                        "committed dirt ledger had no persisted lineage root");
                java.util.UUID real = java.util.UUID.fromString(
                        realEntities.split(",", -1)[0]);
                require(context, bot.level().getEntity(real) instanceof ItemEntity,
                        "committed dirt lineage root vanished before interruption");
                bot.level().getEntity(real).discard();
                require(context, MiningServiceTask.inspectCheckpoint(live).isPresent(),
                        "factual pre-loss checkpoint stopped decoding");
                task.abort(bot);
                active[0] = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), live,
                        ServicePolicy.defaultOre(false),
                        0, "debt-timeout", 0, cursor);
                active[0].start(bot);
                restoredWithMissingIdentity.set(true);
                return;
            }
            task = active[0];
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("settle-timeout debt ended as " + task.state()));
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, restoredWithMissingIdentity.get(),
                    "fixture never restored a ledger with one missing UUID");
            require(context, "mining_service_disposal_settle_timeout"
                            .equals(task.failureReason()),
                    "settle timeout propagated the wrong typed failure: "
                            + task.failureReason());
            require(context, isSolid(bot, face.east()) && isSolid(bot, face.east().above()),
                    "settle timeout failed before both mouth seals were factual");
            Map<String, String> terminal = task.checkpoint();
            require(context, MiningServiceTask.inspectCheckpoint(terminal).isPresent()
                            && Integer.parseInt(terminal.get("budget_used")) > 0,
                    "failed sealed debt lost its restartable hard-budget checkpoint: " + terminal);
            require(context, terminal.getOrDefault("pocket_baseline", "").isBlank(),
                    "baseline-zero missing-UUID debt mutated its baseline: " + terminal);
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 700)
    public void consecutiveSameFaceDisposalsUseIncrementalSinkBaseline(GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServicePocketReuseGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.blockPosition().immutable();
        BlockPos sink = face.east(2);
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        ServicePolicy policy =
                ServicePolicy.defaultOre(false);
        MiningServiceTask[] active = {new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "pocket-reuse", 0, cursor)};
        active[0].start(bot);
        int[] completed = {0};
        int[] secondCompletedAt = {-1};
        int[] ticks = {0};

        context.failIfEver(() -> {
            ticks[0]++;
            if (active[0].state() == TaskState.RUNNING) {
                active[0].tick(bot);
            }
            if (active[0].state() == TaskState.FAILED
                    || active[0].state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("same-face disposal " + completed[0]
                        + " ended as " + active[0].state() + ":"
                        + active[0].failureReason()));
            }
            if (active[0].state() != TaskState.COMPLETED) {
                return;
            }
            if (completed[0] == 0) {
                require(context, sinkCount(bot, sink, Items.DIRT) >= 62,
                        "first same-face disposal never reached the sink");
                InventoryAction.giveItem(bot, new ItemStack(Items.ANDESITE, 64));
                require(context, freeMainSlots(bot) == 3,
                        "second same-face fixture did not refill exactly one slot");
                completed[0] = 1;
                active[0] = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                        0, "pocket-reuse", 0, cursor);
                active[0].start(bot);
                return;
            }
            if (secondCompletedAt[0] < 0) {
                secondCompletedAt[0] = ticks[0];
            }
            if (ticks[0] - secondCompletedAt[0] <= 100) {
                return;
            }
            require(context, sinkCount(bot, sink, Items.DIRT) >= 62,
                    "second service reopened and re-collected the first sink ledger");
            require(context, sinkCount(bot, sink, Items.ANDESITE) >= 62,
                    "second service accepted the old baseline without its new ledger increment");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 0
                            && InventoryAction.countItem(bot, Items.ANDESITE) == 0,
                    "same-face sink contents returned after vanilla pickup delay expired");
            require(context, isSolid(bot, face.east()) && isSolid(bot, face.east().above()),
                    "second same-face service failed to reseal the reused mouth");
            cleanup(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_full_inventory_reused_pocket_frees_a_stack_before_collecting_opening_spoil", maxTicks = 900)
    public void fullInventoryReusedPocketFreesAStackBeforeCollectingOpeningSpoil(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceFullPocketGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.ANDESITE, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.GRAVEL, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.SAND, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        require(context, freeMainSlots(bot) == 0,
                "full-pocket fixture did not begin with zero free slots");

        BlockPos face = bot.blockPosition().immutable();
        BlockPos sink = face.east(2);
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                ServicePolicy.defaultOre(false),
                0, "full-pocket-reuse", 0, cursor);
        task.start(bot);
        int[] completedAt = {-1};
        int[] dirtAtCompletion = {-1};
        int[] andesiteAtCompletion = {-1};
        int[] gravelAtCompletion = {-1};
        int[] sandAtCompletion = {-1};
        int[] ticks = {0};
        boolean[] prebaselineIdentityReset = {false};

        context.failIfEver(() -> {
            ticks[0]++;
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (!prebaselineIdentityReset[0]
                    && "DROP_DISPOSABLE".equals(live.get("phase"))
                    && "true".equals(live.get("pocket_drop_committed"))
                    && live.getOrDefault("pocket_ledger", "").isBlank()) {
                require(context, !live.getOrDefault("pocket_baseline", "").isBlank(),
                        "full-pocket fixture never froze its tracked prebaseline drop");
                require(context, !live.getOrDefault("pocket_entities", "").isBlank(),
                        "frozen baseline UUID lineage was not persisted for merge attestation");
                prebaselineIdentityReset[0] = true;
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("full-pocket disposal ended as "
                        + task.state() + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            if (completedAt[0] < 0) {
                completedAt[0] = ticks[0];
                dirtAtCompletion[0] = InventoryAction.countItem(bot, Items.DIRT);
                andesiteAtCompletion[0] = InventoryAction.countItem(bot, Items.ANDESITE);
                gravelAtCompletion[0] = InventoryAction.countItem(bot, Items.GRAVEL);
                sandAtCompletion[0] = InventoryAction.countItem(bot, Items.SAND);
                // Every disposable stack must be absent at completion. A one- or two-item dirt
                // remainder still occupies the fourth promised working slot even if it originated
                // from ordinary opening spoil and could otherwise be spent on the double seal.
                require(context, dirtAtCompletion[0] == 0
                                && andesiteAtCompletion[0] == 0
                                && gravelAtCompletion[0] == 0
                                && sandAtCompletion[0] == 0,
                        "full-pocket service retained a disposable stack at completion");
            }
            if (ticks[0] - completedAt[0] <= 100) {
                return;
            }
            require(context, freeMainSlots(bot) >= 4,
                    "full-pocket service completed below its four-slot postcondition");
            require(context, prebaselineIdentityReset[0],
                    "full-pocket service skipped the prebaseline identity reset boundary");
            require(context, InventoryAction.countItem(bot, Items.DIRT)
                            == dirtAtCompletion[0]
                            && InventoryAction.countItem(bot, Items.ANDESITE)
                            == andesiteAtCompletion[0]
                            && InventoryAction.countItem(bot, Items.GRAVEL)
                            == gravelAtCompletion[0]
                            && InventoryAction.countItem(bot, Items.SAND)
                            == sandAtCompletion[0],
                    "opening spoil or disposable stacks returned after completion");
            require(context, sinkCount(bot, sink, Items.DIRT) >= 64
                            && sinkCount(bot, sink, Items.GRAVEL) >= 64
                            && sinkCount(bot, sink, Items.SAND) >= 64
                            && sinkCount(bot, sink, Items.ANDESITE) >= 62,
                    "zero-slot service did not physically settle every disposal ledger increment");
            require(context, isSolid(bot, face.east()) && isSolid(bot, face.east().above()),
                    "zero-slot service did not double-seal the reused mouth");
            cleanup(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_non_whitelisted_natural_work_face_spoil_cannot_consume_promised_slot", maxTicks = 900)
    public void nonWhitelistedNaturalWorkFaceSpoilCannotConsumePromisedSlot(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceClaySpoilGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.ANDESITE, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.GRAVEL, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.SAND, 64));
        // Keep one additional non-seal junk stack available after the initial four-slot repair.
        // The late clay stack then has a physically legal capacity-recovery path instead of
        // correctly forcing the service to seal and fail for lack of anything disposable.
        InventoryAction.giveItem(bot, new ItemStack(Items.GRAVEL, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.GRANITE, 64));
        for (int index = 0; index < 28; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        require(context, freeMainSlots(bot) == 0,
                "clay-spoil fixture did not begin with a full main inventory");

        BlockPos face = bot.blockPosition().immutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        BlockPos entry = face.east();
        BlockPos sink = face.east(2);
        // Use a non-disposable opening material so its delayed baseline entity cannot merge with
        // a later tracked junk UUID and turn this spoil-capacity test into an identity-merge race.
        // GLASS, not CLAY: fast/no-tool to mine and produces no drop of its own, so opening the
        // pocket cannot add extra clay_ball to the synthetic spoil this test tracks below.
        for (BlockPos cell : new BlockPos[]{entry, entry.above(), sink, sink.above()}) {
            bot.level().setBlock(
                    cell, Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
        }
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                ServicePolicy.defaultOre(false),
                0, "non-junk-opening-spoil", 0, cursor);
        task.start(bot);
        AtomicReference<ItemEntity> claySpoil = new AtomicReference<>();

        context.failIfEver(() -> {
            Map<String, String> before = task.checkpoint();
            if (task.state() == TaskState.RUNNING && claySpoil.get() == null
                    && "SEAL_DISPOSAL_POCKET".equals(before.get("phase"))) {
                // Four clay balls are the ordinary non-whitelisted drop of one clay block. Hold
                // them in the factual work-face corridor long enough to prove that sealing waits
                // for collection instead of publishing a free-slot promise first.
                ItemEntity spoil = new ItemEntity(
                        bot.level(), face.getX() + 0.5D, face.getY() + 0.25D,
                        face.getZ() + 0.5D, new ItemStack(Items.CLAY_BALL, 4));
                spoil.setDeltaMovement(Vec3.ZERO);
                spoil.setPickUpDelay(20);
                require(context, bot.level().addFreshEntity(spoil),
                        "failed to spawn controlled natural clay opening spoil");
                claySpoil.set(spoil);
            }
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("non-junk opening spoil ended as "
                        + task.state() + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, claySpoil.get() != null,
                    "fixture never reached the pre-seal spoil injection boundary");
            require(context, !claySpoil.get().isAlive()
                            && InventoryAction.countItem(bot, Items.CLAY_BALL) == 4,
                    "service sealed before collecting non-whitelisted work-face spoil");
            require(context, freeMainSlots(bot) >= 4,
                    "collected clay spoil consumed a promised post-service free slot");
            require(context, isSolid(bot, face.east()) && isSolid(bot, face.east().above()),
                    "clay-spoil service did not leave a factual double seal");
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 900)
    public void disposalPocketPreservesObservedOreAndRestartsThroughOppositeSide(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServicePocketOreGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.blockPosition().immutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        prepareDisposalPocket(fixture, Direction.WEST);
        bot.level().setBlock(
                face.east(2), Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
        MiningCursor cursor = miningCursor(face, 0, 1);
        ServicePolicy policy =
                ServicePolicy.defaultOre(false);
        AtomicReference<MiningServiceTask> active = new AtomicReference<>(new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                policy, 0, "pocket-ore", 0, cursor));
        active.get().start(bot);
        AtomicBoolean restarted = new AtomicBoolean();

        context.failIfEver(() -> {
            MiningServiceTask task = active.get();
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (!restarted.get()
                    && "OPEN_DISPOSAL_POCKET".equals(live.get("phase"))
                    && "WEST".equals(live.get("pocket_direction"))
                    && live.getOrDefault("pocket_failure", "")
                    .startsWith("retry_disposal_ore:EAST:")) {
                require(context, MiningServiceTask.inspectCheckpoint(live).isPresent(),
                        "ore-reroute checkpoint did not decode: " + live);
                Map<String, String> staleDirection = new LinkedHashMap<>(live);
                staleDirection.put("pocket_failure",
                        "retry_disposal_ore:WEST:mining_service_disposal_ore_preserved:"
                                + "minecraft:diamond_ore");
                require(context, MiningServiceTask.inspectCheckpoint(staleDirection).isEmpty(),
                        "ore-reroute checkpoint accepted its active side as the rejected side");
                Map<String, String> malformedDirection = new LinkedHashMap<>(live);
                malformedDirection.put("pocket_failure",
                        "retry_disposal_ore:UP:mining_service_disposal_ore_preserved:"
                                + "minecraft:diamond_ore");
                require(context, MiningServiceTask.inspectCheckpoint(malformedDirection).isEmpty(),
                        "ore-reroute checkpoint accepted a non-lateral rejected direction");
                require(context, isSolid(bot, face.east())
                                && isSolid(bot, face.east().above()),
                        "preferred ore pocket was not physically sealed before reroute");
                require(context, bot.level().getBlockState(face.east(2))
                                .is(Blocks.DIAMOND_ORE),
                        "preferred pocket mined or replaced its finite ore");
                int budgetBefore = Integer.parseInt(live.get("budget_used"));
                task.abort(bot);
                MiningServiceTask restored = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), live,
                        policy, 0, "pocket-ore", 0, cursor);
                restored.start(bot);
                require(context, Integer.parseInt(restored.checkpoint().get("budget_used"))
                                >= budgetBefore,
                        "ore-reroute restart reset the hard budget");
                active.set(restored);
                restarted.set(true);
                return;
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("ore-reroute disposal ended as "
                        + task.state() + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, restarted.get(),
                    "ore pocket completed without exercising restartable reroute state");
            require(context, bot.blockPosition().equals(face),
                    "ore-reroute disposal did not finish at the exact work face");
            require(context, bot.level().getBlockState(face.east(2))
                            .is(Blocks.DIAMOND_ORE),
                    "ore-reroute disposal changed the finite ore block");
            require(context, isSolid(bot, face.east()) && isSolid(bot, face.east().above())
                            && isSolid(bot, face.west()) && isSolid(bot, face.west().above()),
                    "ore-reroute disposal did not seal both attempted mouths");
            require(context, sinkCount(bot, face.west(2), Items.DIRT) > 0,
                    "opposite pocket did not retain the physical disposal ledger");
            require(context, InventoryAction.countItem(bot, Items.GLASS) == 30 * 64,
                    "ore-reroute disposal changed protected inventory");
            require(context, freeMainSlots(bot) >= 4,
                    "ore-reroute disposal completed below its free-slot contract");
            cleanup(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_disposal_pocket_preserves_both_ore_sides_and_fails_after_double_seal", maxTicks = 700)
    public void disposalPocketPreservesBothOreSidesAndFailsAfterDoubleSeal(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceBothPocketOreGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.blockPosition().immutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        prepareDisposalPocket(fixture, Direction.WEST);
        for (BlockPos mouth : new BlockPos[]{face.east(), face.west()}) {
            bot.level().setBlock(
                    mouth, Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
            bot.level().setBlock(
                    mouth.above(), Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
        }
        bot.level().setBlock(
                face.east(2), Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
        bot.level().setBlock(
                face.west(2), Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                ServicePolicy.defaultOre(false),
                0, "both-pocket-ore", 0, cursor);
        task.start(bot);

        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("dual-ore disposal ended as " + task.state()));
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, "mining_service_disposal_ore_preserved:minecraft:diamond_ore"
                            .equals(task.failureReason()),
                    "dual-ore disposal failed with the wrong typed reason: "
                            + task.failureReason());
            require(context, bot.level().getBlockState(face.east(2))
                            .is(Blocks.DIAMOND_ORE)
                            && bot.level().getBlockState(face.west(2))
                            .is(Blocks.DIAMOND_ORE),
                    "dual-ore disposal changed one of the finite ore blocks");
            require(context, isSolid(bot, face.east()) && isSolid(bot, face.east().above())
                            && isSolid(bot, face.west()) && isSolid(bot, face.west().above()),
                    "dual-ore disposal published failure before sealing both attempted mouths");
            Map<String, String> terminal = task.checkpoint();
            require(context, "PREPARE".equals(terminal.get("phase"))
                            && task.failureReason().equals(
                            terminal.get("terminal_failure"))
                            && terminal.keySet().stream().noneMatch(
                            key -> key.startsWith("pocket_"))
                            && Integer.parseInt(terminal.get("budget_used")) > 0
                            && MiningServiceTask.inspectCheckpoint(terminal)
                            .filter(metadata -> task.failureReason().equals(
                            metadata.terminalFailure())).isPresent(),
                    "settled dual-ore failure retained physical pocket authority: " + terminal);

            Map<String, String> legacy = new LinkedHashMap<>(terminal);
            legacy.remove("terminal_failure");
            require(context, MiningServiceTask.inspectCheckpoint(legacy).isPresent(),
                    "optional terminal receipt broke current schema-8 checkpoints");
            Map<String, String> emptyReceipt = new LinkedHashMap<>(terminal);
            emptyReceipt.put("terminal_failure", "");
            require(context, MiningServiceTask.inspectCheckpoint(emptyReceipt).isEmpty(),
                    "decoder accepted an empty terminal failure receipt");
            Map<String, String> committedReceipt = new LinkedHashMap<>(terminal);
            committedReceipt.put("phase", "DONE");
            committedReceipt.put("budget_used", "0");
            committedReceipt.put("last_progress_budget", "0");
            require(context, MiningServiceTask.inspectCheckpoint(committedReceipt).isEmpty(),
                    "decoder accepted DONE plus a terminal failure receipt");
            for (String pocketKey : Set.of(
                    "pocket_entry", "pocket_sink", "pocket_direction",
                    "pocket_entities", "pocket_lineage", "pocket_baseline",
                    "pocket_ledger", "pocket_drop_committed",
                    "pocket_ledger_verified", "pocket_phase_started",
                    "pocket_failure", "pocket_clear_index")) {
                Map<String, String> conflictingReceipt = new LinkedHashMap<>(terminal);
                conflictingReceipt.put(pocketKey, "residual-pocket-authority");
                require(context,
                        MiningServiceTask.inspectCheckpoint(conflictingReceipt).isEmpty(),
                        "decoder accepted terminal receipt plus lone " + pocketKey);
            }

            int dirtBeforeRestore = InventoryAction.countItem(bot, Items.DIRT);
            BlockPos rememberedFaceBeforeRestore = face.north(4);
            BotMemoryStore.INSTANCE.of(bot.getUUID()).markPlace(
                    "mine_face", bot.level(), rememberedFaceBeforeRestore);
            MiningServiceTask restored = new MiningServiceTask(
                    Set.of(Blocks.DIAMOND_ORE), terminal,
                    ServicePolicy.defaultOre(false),
                    0, "both-pocket-ore", 0, cursor);
            restored.start(bot);
            Map<String, String> replayed = restored.checkpoint();
            require(context, restored.state() == TaskState.FAILED
                            && task.failureReason().equals(restored.failureReason())
                            && task.failureReason().equals(
                            replayed.get("terminal_failure"))
                            && replayed.keySet().stream().noneMatch(
                            key -> key.startsWith("pocket_"))
                            && terminal.get("budget_used").equals(
                            replayed.get("budget_used"))
                            && InventoryAction.countItem(bot, Items.DIRT)
                            == dirtBeforeRestore
                            && BotMemoryStore.INSTANCE.of(bot.getUUID())
                            .placeIn(bot.level(), "mine_face")
                            .filter(rememberedFaceBeforeRestore::equals).isPresent(),
                    "restart replayed settled disposal work instead of the terminal result: "
                            + restored.state() + ":" + restored.failureReason() + " " + replayed);
            cleanup(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_second_pocket_entry_ore_retires_only_after_visible_double_closure", maxTicks = 700)
    public void secondPocketEntryOreRetiresOnlyAfterVisibleDoubleClosure(
            GameTestHelper context) {
        runSecondPocketMouthOreRetirement(
                context, "MiningServicePocketEntryOreGT", false);
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_second_pocket_upper_ore_retires_only_after_visible_double_closure", maxTicks = 700)
    public void secondPocketUpperOreRetiresOnlyAfterVisibleDoubleClosure(
            GameTestHelper context) {
        runSecondPocketMouthOreRetirement(
                context, "MiningServicePocketUpperOreGT", true);
    }

    private static void runSecondPocketMouthOreRetirement(GameTestHelper context,
                                                           String name,
                                                           boolean upperOre) {
        Fixture fixture = spawn(context, name, false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.blockPosition().immutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        prepareDisposalPocket(fixture, Direction.WEST);
        // EAST is the deterministic first pocket and forces the one allowed reroute at its sink.
        // Use no-drop glass at that mouth so this fixture proves only the second pocket's
        // mouth-ore retirement. A dirt drop still inside vanilla's pickup-delay window is real
        // unresolved opening spoil and must continue to block terminal receipt publication.
        bot.level().setBlock(
                face.east(), Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
        bot.level().setBlock(
                face.east().above(), Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
        bot.level().setBlock(
                face.east(2), Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos preserved = upperOre ? face.west().above() : face.west();
        BlockPos complementaryMouth = upperOre ? face.west() : face.west().above();
        bot.level().setBlock(
                preserved, Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
        bot.level().setBlock(
                complementaryMouth,
                upperOre ? Blocks.GLASS.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);

        MiningCursor cursor = miningCursor(face, 0, 1);
        ServicePolicy policy =
                ServicePolicy.defaultOre(false);
        String mission = upperOre ? "upper-mouth-ore" : "entry-mouth-ore";
        AtomicReference<MiningServiceTask> active = new AtomicReference<>(
                new MiningServiceTask(Set.of(Blocks.DIAMOND_ORE), Map.of(),
                        policy, 0, mission, 0, cursor));
        active.get().start(bot);
        AtomicBoolean restarted = new AtomicBoolean();

        context.failIfEver(() -> {
            MiningServiceTask task = active.get();
            Map<String, String> before = task.checkpoint();
            if (!restarted.get() && task.state() == TaskState.RUNNING
                    && "SEAL_DISPOSAL_POCKET".equals(before.get("phase"))
                    && "WEST".equals(before.get("pocket_direction"))
                    && "mining_service_disposal_ore_preserved:minecraft:diamond_ore"
                    .equals(before.get("pocket_failure"))) {
                require(context, String.valueOf(upperOre ? 1 : 0)
                                .equals(before.get("pocket_clear_index"))
                                && MiningServiceTask.inspectCheckpoint(before).isPresent(),
                        "mouth-ore frontier checkpoint lost its exact clear index: " + before);
                int budget = Integer.parseInt(before.get("budget_used"));
                task.abort(bot);
                MiningServiceTask restored = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), before,
                        policy, 0, mission, 0, cursor);
                restored.start(bot);
                require(context, restored.state() == TaskState.RUNNING
                                && Integer.parseInt(
                                restored.checkpoint().get("budget_used")) >= budget,
                        "mouth-ore restart reset its physical seal authority");
                active.set(restored);
                restarted.set(true);
                return;
            }
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty(
                        "mouth-ore disposal ended as " + task.state()));
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            Map<String, String> terminal = task.checkpoint();
            require(context, restarted.get()
                            && "mining_service_disposal_ore_preserved:minecraft:diamond_ore"
                            .equals(task.failureReason())
                            && task.failureReason().equals(
                            terminal.get("terminal_failure"))
                            && terminal.keySet().stream().noneMatch(
                            key -> key.startsWith("pocket_"))
                            && MiningServiceTask.inspectCheckpoint(terminal)
                            .filter(metadata -> task.failureReason().equals(
                            metadata.terminalFailure())).isPresent(),
                    "closed mouth-ore failure retained or lost terminal authority: "
                            + terminal);
            require(context, bot.level().getBlockState(preserved)
                            .is(Blocks.DIAMOND_ORE)
                            && isSolid(bot, face.west())
                            && isSolid(bot, face.west().above())
                            && isSolid(bot, face.east())
                            && isSolid(bot, face.east().above()),
                    "mouth-ore retirement changed its finite ore or skipped double closure");
            cleanup(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_preserved_ore_failure_keeps_pocket_identity_when_sink_entity_remains", maxTicks = 500)
    public void preservedOreFailureKeepsPocketIdentityWhenSinkEntityRemains(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceClosedOreEntityDebtGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.blockPosition().immutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        prepareDisposalPocket(fixture, Direction.WEST);
        for (BlockPos mouth : new BlockPos[]{face.east(), face.west()}) {
            bot.level().setBlock(
                    mouth, Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
            bot.level().setBlock(
                    mouth.above(), Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
        }
        bot.level().setBlock(
                face.east(2), Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
        bot.level().setBlock(
                face.west(2), Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
        bot.level().setBlock(
                face.west(2).above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                ServicePolicy.defaultOre(false),
                0, "closed-ore-entity-debt", 0, cursor);
        task.start(bot);
        AtomicReference<ItemEntity> residual = new AtomicReference<>();

        context.failIfEver(() -> {
            Map<String, String> before = task.checkpoint();
            if (task.state() == TaskState.RUNNING && residual.get() == null
                    && "SEAL_DISPOSAL_POCKET".equals(before.get("phase"))
                    && "WEST".equals(before.get("pocket_direction"))
                    && "mining_service_disposal_ore_preserved:minecraft:diamond_ore"
                    .equals(before.get("pocket_failure"))) {
                BlockPos sink = face.west(2);
                ItemEntity item = new ItemEntity(
                        bot.level(), sink.getX() + 0.5D, sink.getY() + 1.25D,
                        sink.getZ() + 0.5D, new ItemStack(Items.CLAY_BALL, 4));
                item.setDeltaMovement(Vec3.ZERO);
                item.setNoGravity(true);
                item.setNeverPickUp();
                require(context, bot.level().addFreshEntity(item),
                        "failed to spawn controlled terminal sink entity");
                require(context, ObservableWorldQuery.canObserveEntity(bot, item),
                        "controlled terminal sink entity was not strictly observable");
                residual.set(item);
            }
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("entity-debt ore disposal ended as "
                        + task.state()));
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, residual.get() != null && residual.get().isAlive(),
                    "entity-debt fixture failed before retaining its sink entity");
            require(context, "mining_service_disposal_ore_preserved:minecraft:diamond_ore"
                            .equals(task.failureReason()),
                    "entity-debt disposal changed the typed ore failure: "
                            + task.failureReason());
            Map<String, String> terminal = task.checkpoint();
            require(context, "SEAL_DISPOSAL_POCKET".equals(terminal.get("phase"))
                            && terminal.containsKey("pocket_entry")
                            && terminal.containsKey("pocket_sink")
                            && MiningServiceTask.inspectCheckpoint(terminal).isPresent(),
                    "entity-debt disposal retired an observable unowned sink entity: "
                            + terminal);
            require(context, isSolid(bot, face.west()) && isSolid(bot, face.west().above()),
                    "entity-debt disposal failed before double-sealing its terminal mouth");
            residual.get().discard();
            cleanup(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_disposal_ore_seal_loss_terminates_within_pocket_recovery_window", maxTicks = 500)
    public void disposalOreSealLossTerminatesWithinPocketRecoveryWindow(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceSealLossGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.blockPosition().immutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        prepareDisposalPocket(fixture, Direction.WEST);
        // Glass opens without producing a delayed disposable block drop that could replenish the
        // deliberately removed seal inventory after the retry marker is checkpointed.
        bot.level().setBlock(
                face.east(), Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
        bot.level().setBlock(
                face.east().above(), Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
        bot.level().setBlock(
                face.east(2), Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                ServicePolicy.defaultOre(false),
                0, "pocket-seal-loss", 0, cursor);
        task.start(bot);
        AtomicBoolean sealInventoryRemoved = new AtomicBoolean();
        int[] removedAt = {-1};
        int[] ticks = {0};

        context.failIfEver(() -> {
            ticks[0]++;
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (!sealInventoryRemoved.get()
                    && "SEAL_DISPOSAL_POCKET".equals(live.get("phase"))
                    && live.getOrDefault("pocket_failure", "")
                    .startsWith("retry_disposal_ore:EAST:")) {
                int dirt = InventoryAction.countItem(bot, Items.DIRT);
                require(context, dirt > 0 && InventoryAction.removeItems(bot, Items.DIRT, dirt),
                        "seal-loss fixture could not remove its live seal inventory");
                sealInventoryRemoved.set(true);
                removedAt[0] = ticks[0];
            }
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("seal-loss disposal ended as " + task.state()));
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, sealInventoryRemoved.get(),
                    "seal-loss disposal failed before exercising retry-marker recovery");
            require(context, "mining_service_disposal_seal_block_missing"
                            .equals(task.failureReason()),
                    "seal-loss disposal failed with the wrong typed reason: "
                            + task.failureReason());
            require(context, ticks[0] - removedAt[0] <= 110,
                    "seal-loss disposal exceeded its bounded pocket recovery window: elapsed="
                            + (ticks[0] - removedAt[0]));
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 240)
    public void nearlyBrokenTunnelingToolsDoNotBypassDurabilityService(GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceDamagedToolsGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 28));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        for (int i = 0; i < 4; i++) {
            ItemStack damaged = new ItemStack(Items.STONE_PICKAXE);
            damaged.setDamageValue(damaged.getMaxDamage() - 1);
            InventoryAction.giveItem(bot, damaged);
        }

        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), true);
        task.start(bot);
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("damaged channel-tool service ended as "
                        + task.state() + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            long fresh = bot.getInventory().getNonEquipmentItems().stream()
                    .filter(stack -> stack.is(Items.STONE_PICKAXE) && stack.getDamageValue() == 0)
                    .count();
            require(context, fresh == 4,
                    "four nearly-broken picks incorrectly satisfied the durability target: fresh="
                            + fresh);
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 16,
                    "durability repair consumed the emergency stone reserve");
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 80)
    public void obsidianPreflightUsesItsOwnProfileAndValidatesTheExactKit(GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceObsidianPreflightGT", false);
        AIPlayerEntity bot = fixture.bot();
        giveObsidianServiceKit(bot, 33, 4, 52);
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 24));
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                ServicePolicy.obsidianPreflight(32));
        task.start(bot);

        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("exact obsidian preflight kit was rejected: "
                        + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, "OBSIDIAN_PREFLIGHT".equals(
                            task.checkpoint().get("service_profile")),
                    "preflight checkpoint lost its distinct profile: " + task.checkpoint());
            require(context, InventoryAction.countItem(bot, Items.WATER_BUCKET) == 1
                            && InventoryAction.countItem(bot, Items.COBBLESTONE) == 52
                            && InventoryAction.countItem(bot, Items.STICK) == 24
                            && InventoryAction.countItem(bot, Items.CRAFTING_TABLE) == 1,
                    "preflight consumed its water or emergency-block reserve");
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 80)
    public void obsidianPreflightFailsTypedWithoutItsWaterBucket(GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceObsidianPreflightWaterGT", false);
        AIPlayerEntity bot = fixture.bot();
        giveObsidianServiceKit(bot, 33, 4, 52);
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 24));
        InventoryAction.removeItems(bot, Items.WATER_BUCKET, 1);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                ServicePolicy.obsidianPreflight(32));
        task.start(bot);

        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("missing water bucket bypassed preflight"));
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, "mining_service_water_bucket_missing".equals(task.failureReason()),
                    "missing preflight water failed with the wrong reason: "
                            + task.failureReason());
            cleanup(context, fixture);
        });
    }

    // Four real service stages, each with its own real crafting/mining/tool-exhaustion cycle; under
    // heavy full-suite concurrent load (hundreds of bots' real per-tick decisions/interactions
    // sharing the same server) this legitimately needs materially more real ticks than an isolated
    // single-test run to reach the same logical state -- 900 was tight enough to occasionally run
    // out before the fourth stage's own terminal failure was ever reached, misreporting a
    // crafting-table placement as broken when the run simply needed more time.
    @GameTest(maxTicks = 1800)
    public void thirtyTwoObsidianServiceHorizonFundsAllFourWorstCaseRepairs(GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceObsidianHorizonGT", false);
        AIPlayerEntity bot = fixture.bot();
        giveObsidianServiceKit(bot, 33, 0, 64);
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 32));
        giveExhaustedStonePicks(bot, 4);

        record Stage(java.util.function.Supplier<MiningServiceTask> newTask,
                     int stoneLike, int sticks, String name) {
        }
        java.util.List<Stage> stages = java.util.List.of(
                new Stage(() -> new MiningServiceTask(
                        Set.of(Blocks.OBSIDIAN), Map.of(),
                        ServicePolicy.obsidianPreflight(32)),
                        52, 24, "preflight"),
                new Stage(() -> new MiningServiceTask(
                        Set.of(Blocks.OBSIDIAN), Map.of(),
                        ServicePolicy.obsidian8(32, 8), 8),
                        40, 16, "boundary_8"),
                new Stage(() -> new MiningServiceTask(
                        Set.of(Blocks.OBSIDIAN), Map.of(),
                        ServicePolicy.obsidian8(32, 16), 16),
                        28, 8, "boundary_16"),
                new Stage(() -> new MiningServiceTask(
                        Set.of(Blocks.OBSIDIAN), Map.of(),
                        ServicePolicy.obsidian8(32, 24), 24),
                        16, 0, "boundary_24"));
        int[] stageIndex = {0};
        MiningServiceTask[] current = {null};
        // Only the first (preflight) stage ever places/reclaims a crafting table; that reclaim's
        // real BlockMiner/ActionPack mining only advances on genuine per-tick AIPlayerEntity.tick()
        // calls, so this must poll across real GameTest ticks instead of the single synchronous
        // task.tick(bot) burst runServiceToTerminal used to run each stage in.
        context.failIfEver(() -> {
            if (current[0] == null) {
                if (stageIndex[0] > 0) {
                    exhaustAllStonePicks(bot);
                }
                current[0] = stages.get(stageIndex[0]).newTask().get();
                current[0].start(bot);
                return;
            }
            current[0].tick(bot);
            if (current[0].state() == TaskState.RUNNING) {
                return;
            }
            if (current[0].state() != TaskState.COMPLETED) {
                throw new IllegalStateException("service horizon stage ended as "
                        + current[0].state() + ":" + current[0].failureReason());
            }
            Stage stage = stages.get(stageIndex[0]);
            assertServiceResources(context, bot, stage.stoneLike(), stage.sticks(), stage.name());
            current[0] = null;
            stageIndex[0]++;
            if (stageIndex[0] >= stages.size()) {
                cleanup(context, fixture);
            }
        });
    }

    @GameTest(maxTicks = 80)
    public void obsidianPreflightFailsTypedWithoutCarriedCraftingTable(GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceObsidianTableGT", false);
        AIPlayerEntity bot = fixture.bot();
        giveObsidianServiceKit(bot, 33, 4, 52);
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 24));
        InventoryAction.removeItems(bot, Items.CRAFTING_TABLE, 1);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                ServicePolicy.obsidianPreflight(32));
        task.start(bot);

        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("missing crafting table bypassed preflight"));
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, "mining_service_crafting_table_missing"
                            .equals(task.failureReason()),
                    "missing crafting table failed with the wrong reason: "
                            + task.failureReason());
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 80)
    public void obsidianPolicyRejectsEightRawDurability(GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceObsidianRaw8GT", false);
        AIPlayerEntity bot = fixture.bot();
        giveObsidianServiceKit(bot, 8, 4, 16);
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 2));
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                ServicePolicy.obsidian8(32, 24), 24);
        task.start(bot);
        for (int tick = 0; tick < 400 && task.state() == TaskState.RUNNING; tick++) {
            task.tick(bot);
        }
        require(context, task.state() == TaskState.FAILED
                        && task.failureReason().startsWith(
                        "mining_service_target_tool_durability_depleted:"),
                "raw remaining=8 failed for the wrong reason: "
                        + task.state() + ":" + task.failureReason());
        require(context, diamondRawDurability(bot) == 8,
                "failed durability service damaged or replaced the guarded pick");
        cleanup(context, fixture);
    }

    @GameTest(maxTicks = 80)
    public void obsidianPolicyAcceptsNineRawDurability(GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceObsidianRaw9GT", false);
        AIPlayerEntity bot = fixture.bot();
        giveObsidianServiceKit(bot, 9, 4, 16);
        MiningServiceTask task = new MiningServiceTask(
                    Set.of(Blocks.OBSIDIAN), Map.of(),
                    ServicePolicy.obsidian8(32, 24), 24);
        task.start(bot);
        for (int tick = 0; tick < 400 && task.state() == TaskState.RUNNING; tick++) {
            task.tick(bot);
        }
        require(context, task.state() == TaskState.COMPLETED,
                "raw remaining=9 was rejected: "
                        + task.state() + ":" + task.failureReason());
        require(context, diamondRawDurability(bot) == 9,
                "accepted service consumed target-tool durability");
        cleanup(context, fixture);
    }

    @GameTest(maxTicks = 80)
    public void obsidianPolicyWillNotSpendTheLastSixteenStoneLikeBlocks(GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceObsidianReserveGT", false);
        AIPlayerEntity bot = fixture.bot();
        giveObsidianServiceKit(bot, 33, 0, 27);
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 8));

        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                ServicePolicy.obsidian8(32, 24), 24);
        task.start(bot);
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.COMPLETED
                    || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("insufficient stone surplus bypassed reserve gate"));
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, task.failureReason().startsWith(
                            "mining_service_channel_material_reserve_depleted:"),
                    "stone reserve failed for the wrong reason: " + task.failureReason());
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 27,
                    "failed service consumed protected stone-like blocks");
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 80)
    public void obsidianPolicyRejectsMissingRepairSticksBeforeConsumingStone(GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceObsidianStickGT", false);
        AIPlayerEntity bot = fixture.bot();
        giveObsidianServiceKit(bot, 33, 0, 28);
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 7));

        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                ServicePolicy.obsidian8(32, 24), 24);
        task.start(bot);
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("missing repair sticks bypassed reserve gate"));
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, task.failureReason().startsWith(
                            "mining_service_tool_stick_reserve_depleted:"),
                    "stick reserve failed for the wrong reason: " + task.failureReason());
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 28,
                    "failed stick gate consumed stone before proving the recipe");
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 80)
    public void obsidianPolicyRoundTripsAndCannotRestoreAsDefaultOrePolicy(GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceObsidianPolicyGT", false);
        AIPlayerEntity bot = fixture.bot();
        giveObsidianServiceKit(bot, 33, 4, 16);

        MiningServiceTask original = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                ServicePolicy.obsidian8(32, 24), 24);
        original.start(bot);
        Map<String, String> checkpoint = original.checkpoint();
        MiningServiceTask.RestoreMetadata metadata =
                MiningServiceTask.inspectCheckpoint(checkpoint).orElseThrow();
        require(context, "8".equals(checkpoint.get("schema"))
                        && "OBSIDIAN_8".equals(checkpoint.get("service_profile"))
                        && "standalone".equals(checkpoint.get("service_mission_id"))
                        && "32".equals(checkpoint.get("service_target_count"))
                        && "24".equals(checkpoint.get("service_boundary"))
                        && "8".equals(checkpoint.get("target_tool_usable"))
                        && "520".equals(checkpoint.get("channel_tool_usable"))
                        && "0".equals(checkpoint.get("torch_min_count"))
                        && "0".equals(checkpoint.get("future_stick_reserve"))
                        && "true".equals(checkpoint.get("crafting_table_required"))
                        && metadata.policy().equals(
                        ServicePolicy.obsidian8(32, 24))
                        && metadata.serviceBoundary() == 24,
                "schema-8 checkpoint lost its identity or policy: " + checkpoint);

        Map<String, String> schema4 = new LinkedHashMap<>(checkpoint);
        schema4.put("schema", "4");
        schema4.remove("torch_min_count");
        schema4.remove("service_dimension");
        require(context, MiningServiceTask.inspectCheckpoint(Map.copyOf(schema4)).isPresent(),
                "schema-4 obsidian identity stopped decoding after schema-8 upgrade: " + schema4);

        MiningServiceTask wrongPolicy = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), checkpoint, false);
        wrongPolicy.start(bot);
        require(context, wrongPolicy.state() == TaskState.FAILED
                        && "mining_service_invalid_checkpoint".equals(wrongPolicy.failureReason()),
                "obsidian checkpoint silently downgraded to default ore policy");

        Map<String, String> unidentified = new LinkedHashMap<>(checkpoint);
        unidentified.put("schema", "3");
        unidentified.remove("service_mission_id");
        unidentified.remove("service_target_count");
        unidentified.remove("service_boundary");
        unidentified.remove("future_stick_reserve");
        unidentified.remove("crafting_table_required");
        unidentified.remove("torch_min_count");
        MiningServiceTask legacyObsidian = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.copyOf(unidentified),
                ServicePolicy.obsidian8(32, 24), 24);
        legacyObsidian.start(bot);
        require(context, legacyObsidian.state() == TaskState.FAILED
                        && "mining_service_invalid_checkpoint"
                        .equals(legacyObsidian.failureReason()),
                "schema-3 obsidian service retained unproven boundary authority");

        MiningServiceTask restored = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), checkpoint,
                ServicePolicy.obsidian8(32, 24), 24);
        restored.start(bot);
        context.failIfEver(() -> {
            if (restored.state() == TaskState.RUNNING) {
                restored.tick(bot);
            }
            if (restored.state() == TaskState.FAILED
                    || restored.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("matching policy restart failed: "
                        + restored.failureReason()));
            }
            if (restored.state() == TaskState.COMPLETED) {
                cleanup(context, fixture);
            }
        });
    }

    @GameTest(maxTicks = 120)
    public void obsidianDepotPreservesDiamondBlackstoneAndSafetySupplies(GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceObsidianDepositGT", true);
        AIPlayerEntity bot = fixture.bot();
        giveObsidianServiceKit(bot, 33, 4, 0);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND, 3));
        InventoryAction.giveItem(bot, new ItemStack(Items.BLACKSTONE, 16));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.TORCH, 8));
        for (int i = 0; i < 22; i++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        }

        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                ServicePolicy.obsidian8(32, 24), 24);
        task.start(bot);
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED
                    || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("obsidian depot service ended as "
                        + task.state() + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.DIAMOND) == 3,
                    "depot deposited replacement-pick diamonds");
            require(context, InventoryAction.countItem(bot, Items.BLACKSTONE) == 16,
                    "depot deposited the emergency blackstone reserve");
            require(context, InventoryAction.countItem(bot, Items.WATER_BUCKET) == 1
                            && InventoryAction.countItem(bot, Items.STICK) == 8
                            && InventoryAction.countItem(bot, Items.TORCH) == 8
                            && InventoryAction.countItem(bot, Items.BREAD) == 2,
                    "depot deposited obsidian expedition safety supplies");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 0,
                    "depot did not unload expendable by-products");
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 120)
    public void obsidianDepotReplenishesMixedEmergencyBlocksToSixteen(GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceObsidianBlocksGT", true);
        AIPlayerEntity bot = fixture.bot();
        giveObsidianServiceKit(bot, 33, 4, 5);
        InventoryAction.giveItem(bot, new ItemStack(Items.OBSIDIAN, 3));
        Container depot = ContainerAction.resolve(bot, fixture.depot()).orElseThrow();
        depot.setItem(0, new ItemStack(Items.COBBLED_DEEPSLATE, 5));
        depot.setItem(1, new ItemStack(Items.BLACKSTONE, 6));
        depot.setChanged();

        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                ServicePolicy.obsidian8(32, 24), 24);
        task.start(bot);
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("mixed emergency-block service ended as "
                        + task.state() + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE)
                            + InventoryAction.countItem(bot, Items.COBBLED_DEEPSLATE)
                            + InventoryAction.countItem(bot, Items.BLACKSTONE) == 16,
                    "mixed depot blocks did not replenish the exact sixteen-block reserve");
            require(context, InventoryAction.countItem(bot, Items.OBSIDIAN) == 3,
                    "obsidian output left inventory during emergency-block service");
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 60)
    public void obsidianServiceFailsTypedWhenEmergencyBlocksCannotReachSixteen(GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceObsidianBlocksFailGT", false);
        AIPlayerEntity bot = fixture.bot();
        giveObsidianServiceKit(bot, 33, 4, 15);

        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                ServicePolicy.obsidian8(32, 24), 24);
        task.start(bot);
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("insufficient emergency blocks ended as "
                        + task.state()));
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, task.failureReason().equals(
                            "mining_service_emergency_blocks_reserve_depleted:have=15:required=16"),
                    "insufficient emergency blocks failed with the wrong typed reason: "
                            + task.failureReason());
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 300)
    public void rareBoundary8AcceptsExactResourceHorizonAndRepairsChannel(GameTestHelper context) {
        Fixture fixture = spawn(context, "RareHorizonExactGT", false);
        AIPlayerEntity bot = fixture.bot();
        ServicePolicy policy =
                ServicePolicy.rareOreBatch(64, 8);
        int preRepairSticks = rarePreRepairSticks(policy, false);
        giveRareBoundaryKit(bot, policy, policy.torchMinCount(),
                policy.foodMinUnits(), preRepairSticks, false);
        MiningServiceTask task = rareBoundaryTask(bot, 64, 8);
        task.start(bot);

        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("exact rare horizon failed: " + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.TORCH)
                            == policy.torchMinCount()
                            && MiningFoodReserve.units(bot.getInventory())
                            == policy.foodMinUnits(),
                    "service consumed the protected torch/food horizon");
            require(context, InventoryAction.countItem(bot, Items.STICK)
                            == rareProtectedSticks(policy),
                    "boundary8 did not spend exactly one current epoch's repair sticks");
            require(context, InventoryAction.countItem(bot, Items.STONE_PICKAXE)
                            == MiningBudget.RARE_TUNNELING_SERVICE_TARGET,
                    "boundary8 did not rebuild the seven-pick channel epoch");
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 30)
    public void rareBoundary8RejectsOneTorchBelowHorizonBeforeRepair(GameTestHelper context) {
        Fixture fixture = spawn(context, "RareHorizonTorchFailGT", false);
        AIPlayerEntity bot = fixture.bot();
        ServicePolicy policy =
                ServicePolicy.rareOreBatch(64, 8);
        int preRepairSticks = rarePreRepairSticks(policy, false);
        int haveTorches = policy.torchMinCount() - 1;
        giveRareBoundaryKit(bot, policy, haveTorches,
                policy.foodMinUnits(), preRepairSticks, false);
        MiningServiceTask task = rareBoundaryTask(bot, 64, 8);
        tickToTerminal(task, bot, 10);

        require(context, task.state() == TaskState.FAILED
                        && ("mining_service_torch_reserve_depleted:have=" + haveTorches
                        + ":required=" + policy.torchMinCount())
                        .equals(task.failureReason()),
                "one-below torch horizon failed with the wrong typed reason: "
                        + task.failureReason());
        require(context, InventoryAction.countItem(bot, Items.STONE_PICKAXE) == 0
                        && InventoryAction.countItem(bot, Items.STICK) == preRepairSticks,
                "torch gate spent repair material before proving the horizon");
        cleanup(context, fixture);
    }

    @GameTest(maxTicks = 30)
    public void rareBoundary8RejectsOneFoodBelowHorizonBeforeRepair(GameTestHelper context) {
        Fixture fixture = spawn(context, "RareHorizonFoodFailGT", false);
        AIPlayerEntity bot = fixture.bot();
        ServicePolicy policy =
                ServicePolicy.rareOreBatch(64, 8);
        int preRepairSticks = rarePreRepairSticks(policy, false);
        int haveFood = policy.foodMinUnits() - 1;
        giveRareBoundaryKit(bot, policy, policy.torchMinCount(),
                haveFood, preRepairSticks, false);
        MiningServiceTask task = rareBoundaryTask(bot, 64, 8);
        tickToTerminal(task, bot, 10);

        require(context, task.state() == TaskState.FAILED
                        && ("mining_service_food_reserve_depleted:have=" + haveFood
                        + ":required=" + policy.foodMinUnits())
                        .equals(task.failureReason()),
                "one-below food horizon failed with the wrong typed reason: "
                        + task.failureReason());
        require(context, InventoryAction.countItem(bot, Items.STONE_PICKAXE) == 0
                        && InventoryAction.countItem(bot, Items.STICK) == preRepairSticks,
                "food gate spent repair material before proving the horizon");
        cleanup(context, fixture);
    }

    @GameTest(maxTicks = 30)
    public void rareBoundary8RejectsOneStickBelowRepairHorizon(GameTestHelper context) {
        Fixture fixture = spawn(context, "RareHorizonStickFailGT", false);
        AIPlayerEntity bot = fixture.bot();
        ServicePolicy policy =
                ServicePolicy.rareOreBatch(64, 8);
        int requiredSticks = rarePreRepairSticks(policy, false);
        int haveSticks = requiredSticks - 1;
        giveRareBoundaryKit(bot, policy, policy.torchMinCount(),
                policy.foodMinUnits(), haveSticks, false);
        MiningServiceTask task = rareBoundaryTask(bot, 64, 8);
        tickToTerminal(task, bot, 10);

        require(context, task.state() == TaskState.FAILED
                        && task.failureReason().startsWith(
                        "mining_service_tool_stick_reserve_depleted:have=" + haveSticks + ":")
                        && task.failureReason().endsWith(":required=" + requiredSticks),
                "one-below stick horizon failed with the wrong typed reason: "
                        + task.failureReason());
        require(context, InventoryAction.countItem(bot, Items.STONE_PICKAXE) == 0
                        && InventoryAction.countItem(bot, Items.STICK) == haveSticks,
                "stick gate partially repaired before proving the complete horizon");
        cleanup(context, fixture);
    }

    @GameTest(maxTicks = 300)
    public void rareBoundary8PhysicallyWithdrawsMissionHorizonFromDepot(GameTestHelper context) {
        Fixture fixture = spawn(context, "RareHorizonDepotGT", true);
        AIPlayerEntity bot = fixture.bot();
        ServicePolicy policy =
                ServicePolicy.rareOreBatch(64, 8);
        giveRareBoundaryKit(bot, policy, 0, 0, 0, true);
        Container depot = ContainerAction.resolve(bot, fixture.depot()).orElseThrow();
        int depotSlot = putStackedInventory(
                depot, 0, Items.TORCH, policy.torchMinCount());
        depotSlot = putStackedInventory(
                depot, depotSlot, Items.BREAD, policy.foodMinUnits());
        putStackedInventory(depot, depotSlot, Items.STICK, rareProtectedSticks(policy));
        depot.setChanged();

        MiningServiceTask task = rareBoundaryTask(bot, 64, 8);
        task.start(bot);
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("depot horizon service failed: "
                        + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.TORCH)
                            == policy.torchMinCount()
                            && MiningFoodReserve.units(bot.getInventory())
                            == policy.foodMinUnits()
                            && InventoryAction.countItem(bot, Items.STICK)
                            == rareProtectedSticks(policy),
                    "depot did not physically fund the exact boundary8 horizon");
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 1400)
    public void rareBoundary8CrowdedDepotReservesTheWholeRefillPeak(GameTestHelper context) {
        Fixture fixture = spawn(context, "RareCrowdedDepotGT", true);
        AIPlayerEntity bot = fixture.bot();
        ServicePolicy policy =
                ServicePolicy.rareOreBatch(64, 8);
        giveRareBoundaryKit(bot, policy, 0, 0, 0, false);
        Container depot = ContainerAction.resolve(bot, fixture.depot()).orElseThrow();
        int depotSlot = putStackedInventory(
                depot, 0, Items.TORCH, policy.torchMinCount());
        depotSlot = putStackedInventory(
                depot, depotSlot, Items.BREAD, policy.foodMinUnits());
        putStackedInventory(depot, depotSlot, Items.STICK,
                rarePreRepairSticks(policy, false));
        depot.setChanged();
        for (int slot = 0; slot < bot.getInventory().getNonEquipmentItems().size(); slot++) {
            if (bot.getInventory().getNonEquipmentItems().get(slot).isEmpty()) {
                bot.getInventory().getNonEquipmentItems().set(slot, new ItemStack(Items.DIRT, 64));
            }
        }
        bot.getInventory().setChanged();
        require(context, freeMainSlots(bot) == 0,
                "crowded-depot fixture did not start full");
        prepareDisposalPocket(fixture, Direction.WEST);

        MiningServiceTask task = rareBoundaryTask(bot, 64, 8);
        task.start(bot);
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("crowded depot service failed: "
                        + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.TORCH)
                            == policy.torchMinCount()
                            && MiningFoodReserve.units(bot.getInventory())
                            == policy.foodMinUnits()
                            && InventoryAction.countItem(bot, Items.STICK)
                            == rareProtectedSticks(policy)
                            && InventoryAction.countItem(bot, Items.COBBLESTONE)
                            == policy.emergencyBlocksReserved()
                            && InventoryAction.countItem(bot, Items.STONE_PICKAXE)
                            == MiningBudget.RARE_TUNNELING_SERVICE_TARGET
                            && freeMainSlots(bot) >= 4,
                    "dynamic refill peak did not preserve the final four slots");
            cleanup(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_rare_service_uses_local_pocket_without_touching_remote_owned_depot", maxTicks = 700)
    public void rareServiceUsesLocalPocketWithoutTouchingRemoteOwnedDepot(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "RareLocalFirstGT", false);
        AIPlayerEntity bot = fixture.bot();
        BlockPos face = bot.blockPosition().immutable();
        BlockPos remoteDepot = face.east(8);
        String mission = "rare-local-first";
        bot.level().setBlock(remoteDepot.below(),
                Blocks.DEEPSLATE.defaultBlockState(), Block.UPDATE_ALL);
        bot.level().setBlock(remoteDepot,
                Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        Container depot = ContainerAction.resolve(bot, remoteDepot).orElseThrow();
        depot.setItem(0, new ItemStack(Items.EMERALD, 13));
        depot.setItem(1, new ItemStack(Items.GOLD_INGOT, 7));
        depot.setChanged();
        var memory = BotMemoryStore.INSTANCE.of(bot.getUUID());
        memory.markPlace("mining_depot", bot.level(), remoteDepot);
        memory.remember("mining_depot_owner", mission);

        ServicePolicy policy =
                ServicePolicy.rareOreBatch(64, 63);
        giveRareBoundaryKit(bot, policy, policy.torchMinCount(),
                policy.foodMinUnits(), rareProtectedSticks(policy), true);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND, 5));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.TUFF, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.GRANITE, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIORITE, 64));
        require(context, InventoryAction.countItem(bot, Items.COBBLESTONE)
                        == policy.emergencyBlocksReserved(),
                "offhand projection fixture retained disposable stone excess");
        require(context, bot.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty(),
                "offhand projection fixture did not start with an empty offhand");
        bot.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.DIRT, 2));
        while (freeMainSlots(bot) > 3) {
            int empty = firstEmptyMainSlot(bot);
            require(context, empty >= 0, "local-first fixture lost an expected empty slot");
            bot.getInventory().getNonEquipmentItems().set(empty, new ItemStack(Items.GLASS, 64));
        }
        bot.getInventory().setChanged();
        prepareDisposalPocket(fixture, Direction.EAST);
        // Glass produces no fixture-only block drops. Dirt exists only in offhand, so accepting
        // all three full junk stacks proves the projection models promoteOffhandSlot, hotbar swap,
        // both physical seal consumptions, and continued cleanup after the fourth slot is safe.
        BlockPos sink = face.east(2);
        for (BlockPos cell : new BlockPos[]{face.east(), face.east().above(), sink, sink.above()}) {
            bot.level().setBlock(
                    cell, Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
        }
        MiningCursor cursor = miningCursor(face, 0, 7);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                63, mission, 64, cursor);
        task.start(bot);

        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            require(context, bot.blockPosition().equals(face),
                    "rare local service left its exact work face: " + bot.blockPosition());
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("rare local-first service ended as "
                        + task.state() + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, bot.level().getBlockState(remoteDepot).is(Blocks.CHEST)
                            && inventoryCount(depot, Items.EMERALD) == 13
                            && inventoryCount(depot, Items.GOLD_INGOT) == 7,
                    "rare local service accessed or mutated the remote owned depot");
            require(context, InventoryAction.countItem(bot, Items.DIAMOND) == 5
                            && InventoryAction.countItem(bot, Items.DIAMOND_PICKAXE) == 1,
                    "local disposal discarded a target drop or usable damageable tool");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE)
                            == policy.emergencyBlocksReserved()
                            && InventoryAction.countItem(bot, Items.STICK)
                            == rareProtectedSticks(policy),
                    "local disposal consumed protected stone or sticks");
            require(context, InventoryAction.countItem(bot, Items.TUFF) == 0
                            && InventoryAction.countItem(bot, Items.GRANITE) == 0
                            && InventoryAction.countItem(bot, Items.DIORITE) == 0
                            && sinkCount(bot, sink, Items.TUFF) == 64
                            && sinkCount(bot, sink, Items.GRANITE) == 64
                            && sinkCount(bot, sink, Items.DIORITE) == 64
                            && sinkCount(bot, sink, Items.COBBLESTONE) == 0,
                    "local disposal stopped at minimum capacity or lost a junk transaction");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 0,
                    "offhand-only seal blocks were not physically consumed");
            require(context, freeMainSlots(bot) >= policy.freeSlotsMin(),
                    "local disposal did not restore the delivery-slot contract");
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 300)
    public void boundaryZeroWorstCaseRepairLeavesRetryCushionUsable(GameTestHelper context) {
        Fixture fixture = spawn(context, "RareBoundaryZeroRetryGT", false);
        AIPlayerEntity bot = fixture.bot();
        ServicePolicy retryPolicy =
                ServicePolicy.rareOreBatch(64, 0, 1);
        // Epoch zero already spent one seven-pick pool. Epoch one receives the sealed retry heads,
        // while the sixteen emergency blocks remain untouchable.
        int retryPreRepairSticks = rarePreRepairSticks(retryPolicy, false);
        giveRareBoundaryKit(bot, retryPolicy, retryPolicy.torchMinCount(),
                retryPolicy.foodMinUnits(), retryPreRepairSticks, false);
        BlockPos face = bot.blockPosition().immutable();
        MiningCursor cursor = miningCursor(face, 0, 0);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                retryPolicy,
                0, "rare-boundary-zero-retry", 64, cursor);
        task.start(bot);

        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("boundary-zero retry service failed: "
                        + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.STICK)
                            == rareProtectedSticks(retryPolicy)
                            && InventoryAction.countItem(bot, Items.COBBLESTONE)
                            == MiningBudget.EMERGENCY_STONE_LIKE
                            && InventoryAction.countItem(bot, Items.STONE_PICKAXE)
                            == MiningBudget.RARE_TUNNELING_SERVICE_TARGET,
                    "boundary-zero retry did not consume exactly its released head/stick pool");
            Map<String, String> terminal = task.checkpoint();
            require(context, terminal.containsKey("cursor_schema")
                            && terminal.get("cursor_face").equals(terminal.get("work_face"))
                            && String.valueOf(MiningBudget.EMERGENCY_STONE_LIKE)
                            .equals(terminal.get("emergency_blocks_reserved"))
                            && MiningServiceTask.inspectCheckpoint(terminal).isPresent(),
                    "completed no-pocket service did not persist its schema-8 cursor");
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 80)
    public void boundary63AcceptsExactlyOneUsableTargetBreak(GameTestHelper context) {
        Fixture fixture = spawn(context, "RareBoundary63ToolGT", false);
        AIPlayerEntity bot = fixture.bot();
        ServicePolicy policy =
                ServicePolicy.rareOreBatch(64, 63);
        giveRareBoundaryKit(bot, policy, policy.torchMinCount(),
                policy.foodMinUnits(), rareProtectedSticks(policy), true);
        int ironSlot = InventoryAction.findItem(bot, Items.IRON_PICKAXE).orElseThrow();
        ItemStack ironPick = bot.getInventory().getNonEquipmentItems().get(ironSlot);
        ironPick.setDamageValue(ironPick.getMaxDamage() - 2);
        MiningServiceTask task = rareBoundaryTask(bot, 64, 63);
        task.start(bot);
        tickToTerminal(task, bot, 40);

        require(context, task.state() == TaskState.COMPLETED,
                "one usable target break did not satisfy boundary63: " + task.failureReason());
        require(context, InventoryAction.countItem(bot, Items.IRON_PICKAXE) == 1
                        && InventoryAction.countItem(bot, Items.IRON_INGOT) == 6,
                "boundary63 unnecessarily replaced its exactly-usable target pick");
        cleanup(context, fixture);
    }

    @GameTest(maxTicks = 200)
    public void serviceReturnsToExactSavedWorkFace(GameTestHelper context) {
        Fixture fixture = spawn(context, "MiningServiceExactFaceGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        BlockPos face = bot.blockPosition().immutable();
        Map<String, String> checkpoint = validCheckpoint(bot, "0", "0");
        bot.teleportTo(bot.level(), face.getX() + 1.5D, face.getY(), face.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);

        MiningServiceTask task = new MiningServiceTask(Set.of(Blocks.DIAMOND_ORE), checkpoint);
        task.start(bot);
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("exact-face return failed: " + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, bot.blockPosition().equals(face),
                    "service completed near, but not at, its saved face: " + bot.blockPosition());
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 30)
    public void rareServiceSchema6PinsMissionTargetAndBoundary(GameTestHelper context) {
        Fixture fixture = spawn(context, "RareIdentityGT", false);
        AIPlayerEntity bot = fixture.bot();
        ServicePolicy policy =
                ServicePolicy.rareOreBatch(64, 55);
        giveRareBoundaryKit(bot, policy, policy.torchMinCount(),
                policy.foodMinUnits(), rarePreRepairSticks(policy, false), false);
        MiningCursor cursor = miningCursor(bot.blockPosition().immutable(), 0, 7);
        MiningServiceTask original = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                policy,
                55, "rare-mission", 64, cursor);
        original.start(bot);
        Map<String, String> checkpoint = original.checkpoint();
        MiningServiceTask.RestoreMetadata metadata =
                MiningServiceTask.inspectCheckpoint(checkpoint).orElseThrow();

        require(context, "8".equals(checkpoint.get("schema"))
                        && "RARE_ORE_BATCH".equals(checkpoint.get("service_profile"))
                        && "rare-mission".equals(checkpoint.get("service_mission_id"))
                        && "64".equals(checkpoint.get("service_target_count"))
                        && "55".equals(checkpoint.get("service_boundary"))
                        && String.valueOf(policy.torchMinCount())
                        .equals(checkpoint.get("torch_min_count"))
                        && String.valueOf(policy.foodMinUnits())
                        .equals(checkpoint.get("food_min_units"))
                        && String.valueOf(policy.futureStickReserve())
                        .equals(checkpoint.get("future_stick_reserve"))
                        && String.valueOf(cursor.schema()).equals(checkpoint.get("cursor_schema"))
                        && checkpoint.get("cursor_face").equals(checkpoint.get("work_face"))
                        && metadata.policy().equals(
                        policy),
                "rare schema-8 checkpoint lost exact identity: " + checkpoint);

        MiningServiceTask wrongMission = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), checkpoint,
                ServicePolicy.rareOreBatch(64, 55),
                55, "forged-mission", 64, cursor);
        wrongMission.start(bot);
        MiningServiceTask wrongTarget = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), checkpoint,
                ServicePolicy.rareOreBatch(72, 55),
                55, "rare-mission", 72, cursor);
        wrongTarget.start(bot);
        MiningServiceTask wrongOres = new MiningServiceTask(
                Set.of(Blocks.EMERALD_ORE), checkpoint,
                ServicePolicy.rareOreBatch(64, 55),
                55, "rare-mission", 64, cursor);
        wrongOres.start(bot);
        require(context, wrongMission.state() == TaskState.FAILED
                        && wrongTarget.state() == TaskState.FAILED
                        && wrongOres.state() == TaskState.FAILED,
                "rare checkpoint restored under mismatched mission/target/ores identity");
        Map<String, String> cursorlessSchema6 = new LinkedHashMap<>(checkpoint);
        cursorlessSchema6.keySet().removeIf(key -> key.startsWith("cursor_"));
        Map<String, String> cursorless = Map.copyOf(cursorlessSchema6);
        MiningServiceTask cursorlessRestore = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), cursorless,
                ServicePolicy.rareOreBatch(64, 55),
                55, "rare-mission", 64);
        cursorlessRestore.start(bot);
        require(context, MiningServiceTask.inspectCheckpoint(cursorless).isEmpty()
                        && cursorlessRestore.state() == TaskState.FAILED
                        && "mining_service_invalid_checkpoint".equals(
                        cursorlessRestore.failureReason()),
                "schema-8 rare checkpoint retained authority without its embedded cursor");
        Map<String, String> schema5 = new LinkedHashMap<>(checkpoint);
        schema5.put("schema", "5");
        schema5.keySet().removeIf(key -> key.startsWith("cursor_"));
        require(context, MiningServiceTask.inspectCheckpoint(Map.copyOf(schema5)).isEmpty(),
                "schema-5 rare checkpoint migrated across the retry/cursor authority boundary");
        cleanup(context, fixture);
    }

    @GameTest(maxTicks = 40)
    public void rareDescentKitRestoresSchema7OpenAlcoveCheckpoint(GameTestHelper context) {
        Fixture fixture = spawn(context, "RareDescentKitOpenRestoreGT", false);
        AIPlayerEntity bot = fixture.bot();
        String mission = "rare-descent-open-restore";
        MiningCursor cursor = miningCursor(bot.blockPosition().immutable(), 0, 0);
        giveReadyRareDescentKit(bot, true);

        MiningServiceTask original = rareDescentKitTask(mission, cursor, Map.of());
        original.start(bot);
        Map<String, String> open = tickUntilServicePhase(
                original, bot, "OPEN_MISSION_DEPOT_ALCOVE", 20);
        require(context, "9".equals(open.get("schema"))
                        && "false".equals(open.get("mission_depot_place_committed"))
                        && "0".equals(open.get("mission_depot_clear_index"))
                        && decodeCheckpointPos(open.get("depot"))
                        .equals(bot.blockPosition().east()),
                "OPEN checkpoint lost its schema-9 mission-depot identity: " + open);
        require(context, MiningServiceTask.inspectCheckpoint(open).isPresent(),
                "OPEN checkpoint is not independently decodable: " + open);
        original.abort(bot);

        MiningServiceTask restored = rareDescentKitTask(mission, cursor, open);
        runServiceToTerminal(restored, bot);
        BlockPos depot = decodeCheckpointPos(restored.checkpoint().get("depot"));
        require(context, bot.level().getBlockState(depot).is(Blocks.CHEST)
                        && MiningServiceTask.ownedMissionDepot(bot, mission),
                "OPEN restore did not place and own the exact mission chest");
        require(context, InventoryAction.countItem(bot, Items.CHEST) == 0,
                "OPEN restore failed to consume exactly one carried chest");
        cleanup(context, fixture);
    }

    @GameTest(maxTicks = 40)
    public void rareDescentKitWorldChestBeforeCommitRestoresWithoutDoubleSpend(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "RareDescentKitPlaceRestoreGT", false);
        AIPlayerEntity bot = fixture.bot();
        String mission = "rare-descent-place-restore";
        MiningCursor cursor = miningCursor(bot.blockPosition().immutable(), 0, 0);
        giveReadyRareDescentKit(bot, true);

        MiningServiceTask original = rareDescentKitTask(mission, cursor, Map.of());
        original.start(bot);
        Map<String, String> beforePlace = tickUntilServicePhase(
                original, bot, "PLACE_MISSION_DEPOT", 20);
        BlockPos depot = decodeCheckpointPos(beforePlace.get("depot"));
        require(context, "false".equals(
                        beforePlace.get("mission_depot_place_committed"))
                        && InventoryAction.countItem(bot, Items.CHEST) == 1
                        && bot.level().getBlockState(depot).isAir(),
                "PLACE fixture was not immediately before the physical placement: "
                        + beforePlace);
        original.abort(bot);

        // Simulate a process death after vanilla accepted the placement but before the task wrote
        // its committed bit or mission memory. The restored PLACE phase must observe the world
        // chest and advance; it may not demand or consume a second chest.
        require(context, InventoryAction.removeItems(bot, Items.CHEST, 1),
                "fixture could not spend the physically placed chest");
        bot.level().setBlock(
                depot, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        MiningServiceTask afterWorldMutation = rareDescentKitTask(
                mission, cursor, beforePlace);
        afterWorldMutation.start(bot);
        Map<String, String> verify = tickUntilServicePhase(
                afterWorldMutation, bot, "VERIFY_MISSION_DEPOT", 10);
        require(context, "true".equals(verify.get("mission_depot_place_committed"))
                        && InventoryAction.countItem(bot, Items.CHEST) == 0
                        && BotMemoryStore.INSTANCE.of(bot.getUUID())
                        .recall("mining_depot_owner").isEmpty(),
                "world-first restore duplicated the chest or prematurely forged ownership: "
                        + verify);
        afterWorldMutation.abort(bot);

        MiningServiceTask afterVerifyCrash = rareDescentKitTask(mission, cursor, verify);
        runServiceToTerminal(afterVerifyCrash, bot);
        require(context, bot.level().getBlockState(depot).is(Blocks.CHEST)
                        && InventoryAction.countItem(bot, Items.CHEST) == 0
                        && MiningServiceTask.ownedMissionDepot(bot, mission),
                "VERIFY restore did not commit the one physical mission chest exactly once");
        cleanup(context, fixture);
    }

    @GameTest(maxTicks = 30)
    public void rareDescentKitRejectsOwnerPositionDifferentFromCheckpointDepot(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "RareDescentKitOwnerMismatchGT", false);
        AIPlayerEntity bot = fixture.bot();
        String mission = "rare-descent-owner-mismatch";
        MiningCursor cursor = miningCursor(bot.blockPosition().immutable(), 0, 0);
        giveReadyRareDescentKit(bot, true);

        MiningServiceTask original = rareDescentKitTask(mission, cursor, Map.of());
        original.start(bot);
        Map<String, String> beforePlace = tickUntilServicePhase(
                original, bot, "PLACE_MISSION_DEPOT", 20);
        BlockPos checkpointDepot = decodeCheckpointPos(beforePlace.get("depot"));
        original.abort(bot);
        require(context, InventoryAction.removeItems(bot, Items.CHEST, 1),
                "owner-mismatch fixture could not spend the checkpoint chest");
        bot.level().setBlock(
                checkpointDepot, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        MiningServiceTask placed = rareDescentKitTask(mission, cursor, beforePlace);
        placed.start(bot);
        Map<String, String> verify = tickUntilServicePhase(
                placed, bot, "VERIFY_MISSION_DEPOT", 10);
        placed.abort(bot);

        BlockPos rememberedDepot = bot.blockPosition().west();
        bot.level().setBlock(
                rememberedDepot, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        var memory = BotMemoryStore.INSTANCE.of(bot.getUUID());
        memory.markPlace("mining_depot", bot.level(), rememberedDepot);
        memory.remember("mining_depot_owner", mission);
        MiningServiceTask restored = rareDescentKitTask(mission, cursor, verify);
        restored.start(bot);
        restored.tick(bot);

        require(context, restored.state() == TaskState.FAILED
                        && "mining_service_mission_depot_owner_mismatch"
                        .equals(restored.failureReason()),
                "checkpoint depot B silently replaced the same-owner memory position A: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, memory.placeIn(bot.level(), "mining_depot")
                        .filter(rememberedDepot::equals).isPresent(),
                "failed owner-position check rewrote durable mission-depot memory");
        cleanup(context, fixture);
    }

    @GameTest(maxTicks = 30)
    public void rareDescentKitRejectsForgedSchemaAndIncompleteDoneCheckpoints(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "RareDescentKitSchemaRejectGT", false);
        AIPlayerEntity bot = fixture.bot();
        String mission = "rare-descent-schema-reject";
        MiningCursor cursor = miningCursor(bot.blockPosition().immutable(), 0, 0);
        giveReadyRareDescentKit(bot, true);
        MiningServiceTask original = rareDescentKitTask(mission, cursor, Map.of());
        original.start(bot);
        Map<String, String> schema7 = tickUntilServicePhase(
                original, bot, "OPEN_MISSION_DEPOT_ALCOVE", 20);
        original.abort(bot);

        Map<String, String> schema6Kit = new LinkedHashMap<>(schema7);
        schema6Kit.put("schema", "6");
        assertRejectedRareDescentCheckpoint(
                context, bot, mission, cursor, Map.copyOf(schema6Kit), "schema6_kit");

        Map<String, String> cursorless = new LinkedHashMap<>(schema7);
        cursorless.keySet().removeIf(key -> key.startsWith("cursor_"));
        assertRejectedRareDescentCheckpoint(
                context, bot, mission, cursor, Map.copyOf(cursorless), "schema7_cursorless");

        Map<String, String> incompleteDone = new LinkedHashMap<>(schema7);
        incompleteDone.put("phase", "DONE");
        incompleteDone.put("budget_used", "0");
        incompleteDone.put("last_progress_budget", "0");
        incompleteDone.put("mission_depot_clear_index", "2");
        incompleteDone.put("mission_depot_place_committed", "true");
        incompleteDone.put("mission_depot_retirement_completed", "false");
        assertRejectedRareDescentCheckpoint(
                context, bot, mission, cursor, Map.copyOf(incompleteDone),
                "done_without_retirement");
        cleanup(context, fixture);
    }

    @GameTest(environment = "minecraftai-gametest:mining_service_resource_game_tests_rare_descent_kit_full_inventory_retires_only_cheap_picks_then_mines_diamond", maxTicks = 700)
    public void rareDescentKitFullInventoryRetiresOnlyCheapPicksThenMinesDiamond(
            GameTestHelper context) {
        Fixture fixture = spawn(context, "RareDescentKitPressureGT", false);
        AIPlayerEntity bot = fixture.bot();
        String mission = "rare-descent-pressure";
        BlockPos face = bot.blockPosition().immutable();
        MiningCursor cursor = miningCursor(face, 0, 0);
        giveFullRareDescentPressureInventory(bot);
        require(context, freeMainSlots(bot) == 0,
                "pressure fixture did not fill all 36 main slots: free="
                        + freeMainSlots(bot));

        MiningServiceTask service = rareDescentKitTask(mission, cursor, Map.of());
        // Drive the service through the real runtime owner. A directly ticked task is invisible to
        // DangerWatcher, which can then classify the bot as idle and spend this fixture's exact
        // 720-torch reserve on a concurrent LightAreaTask.
        TaskManager.INSTANCE.assign(bot, service,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_rare_descent_pressure"));
        BlockPos ore = face.north(2);
        BlockPos[] depotPos = {null};
        OreDigTask[] dig = {null};
        context.failIfEver(() -> {
            if (service.state() == TaskState.FAILED
                    || service.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("descent-kit service ended as "
                        + service.state() + ":" + service.failureReason()));
            }
            if (service.state() != TaskState.COMPLETED) {
                return;
            }
            if (dig[0] == null) {
                Map<String, String> terminal = service.checkpoint();
                depotPos[0] = decodeCheckpointPos(terminal.get("depot"));
                Container depot = ContainerAction.resolve(bot, depotPos[0]).orElseThrow();

                require(context, "9".equals(terminal.get("schema"))
                                && "DONE".equals(terminal.get("phase"))
                                && "true".equals(terminal.get(
                                "mission_depot_retirement_completed")),
                        "pressure service did not durably commit the schema-9 kit: "
                                + terminal);
                require(context, inventoryCount(depot, Items.WOODEN_PICKAXE) == 1
                                && inventoryCount(depot, Items.STONE_PICKAXE) == 5,
                        "mission chest did not preserve every retired old wood/stone pick");
                require(context, inventoryCount(depot, Items.LEATHER) == 7
                                && inventoryCount(depot, Items.FEATHER) == 9,
                        "mission chest did not preserve the carried by-product ledger");
                require(context, InventoryAction.countItem(bot, Items.WOODEN_PICKAXE) == 0
                                && InventoryAction.countItem(bot, Items.STONE_PICKAXE) == 5
                                && bot.getInventory().getNonEquipmentItems().stream()
                                .filter(stack -> stack.is(Items.STONE_PICKAXE))
                                .allMatch(stack -> stack.getDamageValue() == 0),
                        "retirement left old cheap picks on the player or removed a fresh replacement");
                require(context, InventoryAction.countItem(bot, Items.IRON_PICKAXE) == 3
                                && InventoryAction.countItem(bot, Items.DIAMOND_PICKAXE) == 1
                                && inventoryCount(depot, Items.IRON_PICKAXE) == 0
                                && inventoryCount(depot, Items.DIAMOND_PICKAXE) == 0,
                        "mission retirement moved an iron/diamond pick out of player custody");
                require(context, usableMainDurability(bot, Items.STONE_PICKAXE) >= 650
                                && targetGradeUsableMainDurability(bot) >= 8
                                && InventoryAction.countItem(bot, Items.TORCH)
                                >= MiningBudget.DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES
                                && MiningFoodReserve.units(bot.getInventory())
                                >= MiningBudget.RARE_BOOTSTRAP_FOOD
                                && InventoryAction.countItem(bot, Items.COBBLESTONE)
                                + InventoryAction.countItem(bot, Items.COBBLED_DEEPSLATE)
                                + InventoryAction.countItem(bot, Items.BLACKSTONE)
                                >= MiningBudget.RARE_BOOTSTRAP_STONE_LIKE
                                && InventoryAction.countItem(bot, Items.STICK)
                                >= MiningBudget.DIAMOND_STACK_BOOTSTRAP_STICKS
                                && freeMainSlots(bot) >= 4
                                && MiningServiceTask.rareDescentKitReady(bot)
                                && MiningServiceTask.ownedMissionDepot(bot, mission),
                        "pressure service missed the 650/8/720/80/60/256/free4 descent contract");
                require(context, bot.blockPosition().equals(face),
                        "descent kit did not complete at its exact mining workface");

                bot.level().setBlock(
                        ore, Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
                dig[0] = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1);
                // Abort a background task that may have been admitted in the END_SERVER_TICK where
                // service became terminal, before it gets a tick to mutate the proven kit.
                TaskManager.INSTANCE.abort(bot);
                TaskManager.INSTANCE.assign(bot, dig[0],
                        TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                                "gametest_post_descent_kit_diamond"));
            }
            if (dig[0].state() == TaskState.FAILED
                    || dig[0].state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("post-kit diamond dig ended as "
                        + dig[0].state() + ":" + dig[0].failureReason()));
            }
            if (dig[0].state() != TaskState.COMPLETED) {
                return;
            }
            require(context, bot.level().getBlockState(ore).isAir()
                            && InventoryAction.countItem(bot, Items.DIAMOND) == 1,
                    "post-kit OreDig did not physically break and collect one visible diamond");
            require(context, bot.level().getBlockState(depotPos[0])
                            .is(Blocks.CHEST)
                            && ContainerAction.resolve(bot, depotPos[0]).isPresent()
                            && MiningServiceTask.ownedMissionDepot(bot, mission),
                    "post-kit OreDig destroyed or forgot the mission chest");
            require(context, bot.isAlive() && bot.getHealth() > 0.0F,
                    "post-kit OreDig killed the bot");
            cleanup(context, fixture);
        });
    }

    private static MiningServiceTask rareDescentKitTask(
            String mission,
            MiningCursor cursor,
            Map<String, String> checkpoint) {
        return new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), checkpoint,
                ServicePolicy.rareDescentKit(64),
                0, mission, 64, cursor);
    }

    private static Map<String, String> tickUntilServicePhase(
            MiningServiceTask task,
            AIPlayerEntity bot,
            String expectedPhase,
            int maxTicks) {
        for (int tick = 0; tick < maxTicks && task.state() == TaskState.RUNNING; tick++) {
            Map<String, String> checkpoint = task.checkpoint();
            if (expectedPhase.equals(checkpoint.get("phase"))) {
                return checkpoint;
            }
            task.tick(bot);
        }
        Map<String, String> terminal = task.checkpoint();
        if (expectedPhase.equals(terminal.get("phase"))) {
            return terminal;
        }
        throw new IllegalStateException("service never reached " + expectedPhase
                + ":state=" + task.state() + ":reason=" + task.failureReason()
                + ":checkpoint=" + terminal);
    }

    private static void assertRejectedRareDescentCheckpoint(
            GameTestHelper context,
            AIPlayerEntity bot,
            String mission,
            MiningCursor cursor,
            Map<String, String> checkpoint,
            String label) {
        require(context, MiningServiceTask.inspectCheckpoint(checkpoint).isEmpty(),
                label + " checkpoint retained standalone restore authority: " + checkpoint);
        MiningServiceTask rejected = rareDescentKitTask(mission, cursor, checkpoint);
        rejected.start(bot);
        require(context, rejected.state() == TaskState.FAILED
                        && "mining_service_invalid_checkpoint"
                        .equals(rejected.failureReason()),
                label + " checkpoint did not fail closed: " + rejected.state()
                        + ":" + rejected.failureReason());
    }

    private static void giveReadyRareDescentKit(AIPlayerEntity bot, boolean chest) {
        if (chest) {
            InventoryAction.giveItem(bot, new ItemStack(Items.CHEST));
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        for (int index = 0; index < 5; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        }
        giveStackedItem(bot, Items.TORCH,
                MiningBudget.DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES);
        giveStackedItem(bot, Items.BREAD, MiningBudget.RARE_BOOTSTRAP_FOOD);
        giveStackedItem(bot, Items.COBBLESTONE,
                MiningBudget.RARE_BOOTSTRAP_STONE_LIKE);
        giveStackedItem(bot, Items.STICK,
                MiningBudget.DIAMOND_STACK_BOOTSTRAP_STICKS);
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
    }

    private static void giveFullRareDescentPressureInventory(AIPlayerEntity bot) {
        InventoryAction.giveItem(bot, new ItemStack(Items.CHEST));
        // The margin-funded torch/stick pools occupy three more slots than the pre-margin carry,
        // so only one of the old four exhausted wooden picks still fits the 36-slot boundary.
        // Wood and stone cheap-pick retirement both stay exercised.
        giveExhaustedPick(bot, Items.WOODEN_PICKAXE);
        for (int index = 0; index < 5; index++) {
            giveExhaustedPick(bot, Items.STONE_PICKAXE);
        }
        for (int index = 0; index < 3; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_INGOT, 6));
        giveStackedItem(bot, Items.TORCH,
                MiningBudget.DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES);
        giveStackedItem(bot, Items.BREAD, MiningBudget.RARE_BOOTSTRAP_FOOD);
        giveStackedItem(bot, Items.STICK,
                MiningBudget.DIAMOND_STACK_BOOTSTRAP_STICKS + 5 * 2);
        giveStackedItem(bot, Items.COBBLESTONE,
                MiningBudget.RARE_BOOTSTRAP_STONE_LIKE + 5 * 3 + 2);
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.LEATHER, 7));
        InventoryAction.giveItem(bot, new ItemStack(Items.FEATHER, 9));
    }

    private static int inventoryCount(Container inventory, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int usableMainDurability(
            AIPlayerEntity bot, net.minecraft.world.item.Item item) {
        return bot.getInventory().getNonEquipmentItems().stream()
                .filter(stack -> stack.is(item))
                .mapToInt(MiningServiceTask::usableDurability)
                .sum();
    }

    private static int targetGradeUsableMainDurability(AIPlayerEntity bot) {
        return usableMainDurability(bot, Items.IRON_PICKAXE)
                + usableMainDurability(bot, Items.DIAMOND_PICKAXE);
    }

    private static BlockPos decodeCheckpointPos(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("missing_checkpoint_pos");
        }
        String[] parts = encoded.split(",", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("invalid_checkpoint_pos:" + encoded);
        }
        return new BlockPos(Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    private static MiningServiceTask rareBoundaryTask(
            AIPlayerEntity bot, int target, int boundary) {
        BlockPos face = bot.blockPosition().immutable();
        return new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                ServicePolicy.rareOreBatch(target, boundary),
                boundary, "rare-horizon-" + target, target,
                miningCursor(face, 0, boundary / 8));
    }

    private static void giveRareBoundaryKit(AIPlayerEntity bot,
                                            ServicePolicy policy,
                                            int torches,
                                            int food,
                                            int sticks,
                                            boolean freshChannelPicks) {
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_INGOT, 6));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        int channelStone = freshChannelPicks ? 0
                : MiningBudget.RARE_TUNNELING_SERVICE_TARGET
                * MiningBudget.STONE_PICKAXE_HEAD_COST;
        giveStackedItem(bot, Items.COBBLESTONE,
                policy.emergencyBlocksReserved() + channelStone);
        if (freshChannelPicks) {
            for (int index = 0;
                 index < MiningBudget.RARE_TUNNELING_SERVICE_TARGET; index++) {
                InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
            }
        }
        giveStackedItem(bot, Items.TORCH, torches);
        giveStackedItem(bot, Items.BREAD, food);
        giveStackedItem(bot, Items.STICK, sticks);
    }

    private static int rareProtectedSticks(ServicePolicy policy) {
        return policy.futureStickReserve() + MiningBudget.DIAMOND_STACK_TARGET_TOOL_STICKS;
    }

    private static int rarePreRepairSticks(
            ServicePolicy policy, boolean freshChannelPicks) {
        int currentEpoch = freshChannelPicks ? 0
                : MiningBudget.RARE_TUNNELING_SERVICE_TARGET
                * MiningBudget.STONE_PICKAXE_STICK_COST;
        return rareProtectedSticks(policy) + currentEpoch;
    }

    private static int putStackedInventory(
            Container inventory, int startSlot, net.minecraft.world.item.Item item, int count) {
        int slot = startSlot;
        int remaining = Math.max(0, count);
        while (remaining > 0) {
            if (slot >= inventory.getContainerSize()) {
                throw new IllegalArgumentException("inventory_fixture_capacity_depleted");
            }
            int batch = Math.min(item.getDefaultMaxStackSize(), remaining);
            inventory.setItem(slot++, new ItemStack(item, batch));
            remaining -= batch;
        }
        return slot;
    }

    private static MiningCursor miningCursor(BlockPos face, int direction, int batches) {
        return new MiningCursor(
                MiningCursor.CURRENT_SCHEMA,
                face,
                face,
                direction,
                0,
                24,
                48,
                batches);
    }

    private static void prepareDisposalPocket(Fixture fixture, Direction direction) {
        AIPlayerEntity bot = fixture.bot();
        BlockPos face = bot.blockPosition();
        var world = bot.level();
        BlockPos entry = face.relative(direction);
        BlockPos sink = face.relative(direction, 2);
        BlockPos back = face.relative(direction, 3);
        world.setBlock(entry, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(entry.above(), Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(sink, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(sink.above(), Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(back, Blocks.DEEPSLATE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(back.above(), Blocks.DEEPSLATE.defaultBlockState(), Block.UPDATE_ALL);
        Direction left = direction.getCounterClockWise();
        Direction right = direction.getClockWise();
        for (BlockPos cell : new BlockPos[]{entry, sink}) {
            world.setBlock(cell.relative(left),
                    Blocks.DEEPSLATE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.relative(left).above(),
                    Blocks.DEEPSLATE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.relative(right),
                    Blocks.DEEPSLATE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.relative(right).above(),
                    Blocks.DEEPSLATE.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static void buildBedrockCage(AIPlayerEntity bot, BlockPos center) {
        var world = bot.level();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.setBlock(center.offset(dx, -1, dz),
                        Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(center.offset(dx, 2, dz),
                        Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
                if (dx != 0 || dz != 0) {
                    world.setBlock(center.offset(dx, 0, dz),
                            Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
                    world.setBlock(center.offset(dx, 1, dz),
                            Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        world.setBlock(center, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(center.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static AABB sinkBox(BlockPos sink) {
        return new AABB(sink.getX(), sink.getY(), sink.getZ(),
                sink.getX() + 1.0D, sink.getY() + 2.0D, sink.getZ() + 1.0D);
    }

    private static int sinkCount(AIPlayerEntity bot, BlockPos sink, net.minecraft.world.item.Item item) {
        AABB raw = sinkBox(sink);
        return bot.level().getEntitiesOfClass(
                        ItemEntity.class, raw.inflate(0.01D),
                        entity -> entity.isAlive()
                                && fullyContains(raw, entity.getBoundingBox())).stream()
                .filter(entity -> entity.getItem().is(item))
                .mapToInt(entity -> entity.getItem().getCount())
                .sum();
    }

    private static boolean fullyContains(AABB outer, AABB inner) {
        return inner.minX >= outer.minX && inner.maxX <= outer.maxX
                && inner.minY >= outer.minY && inner.maxY <= outer.maxY
                && inner.minZ >= outer.minZ && inner.maxZ <= outer.maxZ;
    }

    private static boolean isSolid(AIPlayerEntity bot, BlockPos pos) {
        var state = bot.level().getBlockState(pos);
        return !state.canBeReplaced()
                && !state.getCollisionShape(bot.level(), pos).isEmpty();
    }

    private static void giveStackedItem(AIPlayerEntity bot, net.minecraft.world.item.Item item, int count) {
        int remaining = Math.max(0, count);
        while (remaining > 0) {
            int batch = Math.min(item.getDefaultMaxStackSize(), remaining);
            InventoryAction.giveItem(bot, new ItemStack(item, batch));
            remaining -= batch;
        }
    }

    private static void tickToTerminal(MiningServiceTask task, AIPlayerEntity bot, int maxTicks) {
        task.start(bot);
        for (int tick = 0; tick < maxTicks && task.state() == TaskState.RUNNING; tick++) {
            task.tick(bot);
        }
    }

    private static Fixture spawn(GameTestHelper context, String name, boolean withDepot) {
        var world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                world.setBlock(feet.offset(dx, -1, dz),
                        Blocks.DEEPSLATE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.offset(dx, 0, dz),
                        Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.offset(dx, 1, dz),
                        Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        BlockPos depot = feet.east(2);
        if (withDepot) {
            world.setBlock(depot, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        world.getChunkSource().move(bot); // the tracking view follows a teleport on the next tick; sight questions at tick 0 need it now
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        BotMemoryStore.INSTANCE.remove(bot.getUUID());
        if (withDepot) {
            BotMemoryStore.INSTANCE.of(bot.getUUID()).markPlace("depot", world, depot);
        }
        return new Fixture(name, bot, depot);
    }

    private static Map<String, String> validCheckpoint(
            AIPlayerEntity bot, String budget, String lastProgress) {
        ServicePolicy policy =
                ServicePolicy.defaultOre(false);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("schema", "8");
        values.put("work_face", bot.blockPosition().getX() + ","
                + bot.blockPosition().getY() + "," + bot.blockPosition().getZ());
        values.put("phase", "PREPARE");
        values.put("channel_tools", "false");
        values.put("service_profile", policy.profile().name());
        values.put("service_mission_id", "standalone");
        values.put("service_dimension", bot.level().dimension()
                .identifier().toString());
        values.put("service_target_count", "0");
        values.put("service_boundary", "0");
        values.put("target_tool_usable", String.valueOf(
                policy.targetToolUsableDurability()));
        values.put("channel_tool_usable", String.valueOf(
                policy.channelToolUsableDurability()));
        values.put("food_min_units", String.valueOf(policy.foodMinUnits()));
        values.put("torch_min_count", String.valueOf(policy.torchMinCount()));
        values.put("free_slots_min", String.valueOf(policy.freeSlotsMin()));
        values.put("emergency_blocks_reserved", String.valueOf(
                policy.emergencyBlocksReserved()));
        values.put("future_stick_reserve", String.valueOf(
                policy.futureStickReserve()));
        values.put("crafting_table_required", String.valueOf(
                policy.craftingTableRequired()));
        values.put("ores", OreDigTask.oreFingerprint(Set.of(Blocks.DIAMOND_ORE)));
        values.put("budget_used", budget);
        values.put("last_progress_budget", lastProgress);
        return Map.copyOf(values);
    }

    private static void giveObsidianServiceKit(AIPlayerEntity bot,
                                               int diamondRawDurability,
                                               int stonePicks,
                                               int cobblestone) {
        // Keep directly ticked service tests isolated from held-tool background resupply.
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.WATER_BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        ItemStack diamond = new ItemStack(Items.DIAMOND_PICKAXE);
        diamond.setDamageValue(diamond.getMaxDamage() - diamondRawDurability);
        InventoryAction.giveItem(bot, diamond);
        for (int i = 0; i < stonePicks; i++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        }
        if (cobblestone > 0) {
            InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, cobblestone));
        }
    }

    private static int diamondRawDurability(AIPlayerEntity bot) {
        return bot.getInventory().getNonEquipmentItems().stream()
                .filter(stack -> stack.is(Items.DIAMOND_PICKAXE))
                .mapToInt(stack -> stack.getMaxDamage() - stack.getDamageValue())
                .sum();
    }

    private static void runServiceToTerminal(MiningServiceTask task, AIPlayerEntity bot) {
        task.start(bot);
        for (int tick = 0; tick < 400 && task.state() == TaskState.RUNNING; tick++) {
            task.tick(bot);
        }
        if (task.state() != TaskState.COMPLETED) {
            throw new IllegalStateException("service horizon stage ended as " + task.state()
                    + ":" + task.failureReason());
        }
    }

    private static void giveExhaustedStonePicks(AIPlayerEntity bot, int count) {
        for (int index = 0; index < count; index++) {
            ItemStack pick = new ItemStack(Items.STONE_PICKAXE);
            pick.setDamageValue(pick.getMaxDamage() - 1);
            InventoryAction.giveItem(bot, pick);
        }
    }

    private static void giveExhaustedPick(AIPlayerEntity bot, net.minecraft.world.item.Item item) {
        ItemStack pick = new ItemStack(item);
        pick.setDamageValue(pick.getMaxDamage() - 1);
        InventoryAction.giveItem(bot, pick);
    }

    private static long unusableCheapPickaxes(AIPlayerEntity bot) {
        return bot.getInventory().getNonEquipmentItems().stream()
                .filter(stack -> stack.is(Items.WOODEN_PICKAXE)
                        || stack.is(Items.STONE_PICKAXE)
                        || stack.is(Items.IRON_PICKAXE))
                .filter(stack -> MiningServiceTask.usableDurability(stack) == 0)
                .count();
    }

    private static int freeMainSlots(AIPlayerEntity bot) {
        return (int) bot.getInventory().getNonEquipmentItems().stream().filter(ItemStack::isEmpty).count();
    }

    private static int firstEmptyMainSlot(AIPlayerEntity bot) {
        for (int slot = 0; slot < bot.getInventory().getNonEquipmentItems().size(); slot++) {
            if (bot.getInventory().getNonEquipmentItems().get(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private static void exhaustAllStonePicks(AIPlayerEntity bot) {
        bot.getInventory().getNonEquipmentItems().stream()
                .filter(stack -> stack.is(Items.STONE_PICKAXE))
                .forEach(stack -> stack.setDamageValue(stack.getMaxDamage() - 1));
    }

    private static void assertServiceResources(GameTestHelper context,
                                               AIPlayerEntity bot,
                                               int stoneLike,
                                               int sticks,
                                               String stage) {
        require(context, InventoryAction.countItem(bot, Items.COBBLESTONE)
                        + InventoryAction.countItem(bot, Items.COBBLED_DEEPSLATE)
                        + InventoryAction.countItem(bot, Items.BLACKSTONE) == stoneLike,
                stage + " did not retain the exact future stone reserve");
        require(context, InventoryAction.countItem(bot, Items.STICK) == sticks,
                stage + " did not retain the exact future stick reserve");
        require(context, InventoryAction.countItem(bot, Items.CRAFTING_TABLE) == 1,
                stage + " lost the carried crafting table");
        int channelDurability = bot.getInventory().getNonEquipmentItems().stream()
                .filter(stack -> stack.is(Items.STONE_PICKAXE))
                .mapToInt(MiningServiceTask::usableDurability)
                .sum();
        require(context, channelDurability >= 520,
                stage + " did not restore four fresh channel picks");
    }

    private static void cleanup(GameTestHelper context, Fixture fixture) {
        BotMemoryStore.INSTANCE.remove(fixture.bot().getUUID());
        AIPlayerManager.INSTANCE.despawn(fixture.bot().level().getServer(), fixture.name());
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }

    private record Fixture(String name, AIPlayerEntity bot, BlockPos depot) {
    }
}

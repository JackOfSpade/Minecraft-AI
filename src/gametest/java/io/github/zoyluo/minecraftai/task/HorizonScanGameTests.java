package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.perception.SharedWorldSight;
import io.github.zoyluo.minecraftai.task.SensingArena.Room;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The look-around scans answer with the first thing they see, so a sighting the caller cannot use would answer
 * every step again and the rest of the sweep would never run (the real session: 326 refusals of one leaf in 37 s).
 * These pin, with real rays in a real room, that a declined sighting is passed over and that a shared-sight lead
 * is revisited on its cadence rather than at every step.
 */
public final class HorizonScanGameTests {
    private static final int ROOM_HEIGHT = 12;
    private static final int SLAB_BASE = 140;
    /** More than the whole lattice of either scan needs: 2 phases x 480 x 64 samples at 62 a step is under 1000. */
    private static final int STEPS = 1200;

    @GameTest(environment = "minecraftai-gametest:horizon_scan_game_tests_declined_overhead_log_does_not_stall_the_tree_sweep", maxTicks = 200)
    public void declinedOverheadLogDoesNotStallTheTreeSweep(GameTestHelper context) {
        Fixture f = new Fixture(context, "HorizonTreeDeclineGT", 0);
        BlockPos overhead = f.room.at(0, 5, 0);
        BlockPos beside = f.room.at(-5, 2, 4);
        f.room.set(0, 5, 0, Blocks.OAK_LOG);
        f.room.set(-5, 2, 4, Blocks.OAK_LOG);
        TreeHorizonScan scan = new TreeHorizonScan(Set.of(Blocks.OAK_LOG));

        context.runAfterDelay(2L, () -> {
            TreeHorizonScan.Sighting first = scan.step(f.bot);
            TreeHorizonScan.Sighting again = scan.step(f.bot);
            f.require(first != null && overhead.equals(first.pos()), "the vertical ray should see the overhead log first: " + first);
            f.require(again != null && overhead.equals(again.pos()),
                    "without a decline the same overhead log answers the very next step again: " + again);

            scan.decline(f.bot, overhead);
            boolean foundBeside = false;
            for (int step = 0; step < STEPS && !scan.complete(); step++) {
                TreeHorizonScan.Sighting seen = scan.step(f.bot);
                if (seen == null) {
                    continue;
                }
                f.require(!overhead.equals(seen.pos()), "the declined log was offered again at step " + step);
                foundBeside |= beside.equals(seen.pos());
            }
            f.require(foundBeside, "the sweep never reached the second log");
            f.require(scan.complete(), "the sweep did not run to its end");
            f.finish();
        });
    }

    @GameTest(environment = "minecraftai-gametest:horizon_scan_game_tests_declined_overhead_block_does_not_stall_the_target_sweep", maxTicks = 200)
    public void declinedOverheadBlockDoesNotStallTheTargetSweep(GameTestHelper context) {
        // The scan every ore, mine and gather request shares: OreDig and MineTask decline an excluded or refused ore.
        Fixture f = new Fixture(context, "HorizonTargetDeclineGT", 1);
        BlockPos overhead = f.room.at(0, 5, 0);
        BlockPos beside = f.room.at(4, 2, -4);
        f.room.set(0, 5, 0, Blocks.IRON_ORE);
        f.room.set(4, 2, -4, Blocks.IRON_ORE);
        VisibleTargetHorizonScan scan = new VisibleTargetHorizonScan(Set.of(Blocks.IRON_ORE));

        context.runAfterDelay(2L, () -> {
            VisibleTargetHorizonScan.Sighting first = scan.step(f.bot);
            VisibleTargetHorizonScan.Sighting again = scan.step(f.bot);
            f.require(first != null && overhead.equals(first.pos()), "the vertical ray should see the overhead ore first: " + first);
            f.require(again != null && overhead.equals(again.pos()),
                    "without a decline the same overhead ore answers the very next step again: " + again);

            scan.decline(f.bot, overhead);
            boolean foundBeside = false;
            for (int step = 0; step < STEPS && !scan.complete(); step++) {
                VisibleTargetHorizonScan.Sighting seen = scan.step(f.bot);
                if (seen == null) {
                    continue;
                }
                f.require(!overhead.equals(seen.pos()), "the declined ore was offered again at step " + step);
                foundBeside |= beside.equals(seen.pos());
            }
            f.require(foundBeside, "the sweep never reached the second ore");
            f.require(scan.complete(), "the sweep did not run to its end");
            f.finish();
        });
    }

    @GameTest(environment = "minecraftai-gametest:horizon_scan_game_tests_shared_sight_lead_is_revisited_on_its_cadence_not_every_step", maxTicks = 200)
    public void sharedSightLeadIsRevisitedOnItsCadenceNotEveryStep(GameTestHelper context) {
        // A lead from shared sight is a shortcut to be re-proved, not a reason to pin the sweep: the step that
        // returns it must still advance, or a lead the caller cannot use would be handed back (without a single ray
        // cast) on every call and the sweep would never run. A shared lead costs no ray, so it reports no rays; the
        // next step is a sweep step and has cast some.
        Fixture f = new Fixture(context, "HorizonSharedCadenceGT", 2);
        BlockPos log = f.room.at(4, 1, 0);
        f.room.set(4, 1, 0, Blocks.OAK_LOG);
        TreeHorizonScan trees = new TreeHorizonScan(Set.of(Blocks.OAK_LOG));
        VisibleTargetHorizonScan targets = new VisibleTargetHorizonScan(Set.of(Blocks.OAK_LOG));

        context.runAfterDelay(2L, () -> {
            SharedWorldSight.rememberConfirmed(f.bot, log);
            TreeHorizonScan.Sighting lead = trees.step(f.bot);
            f.require(lead != null && log.equals(lead.pos()) && lead.raysCast() == 0,
                    "expected the remembered log from shared sight, before any ray: " + lead);
            TreeHorizonScan.Sighting next = trees.step(f.bot);
            f.require(next == null || next.raysCast() > 0,
                    "the tree sweep handed back the shared lead again without advancing: " + next);

            VisibleTargetHorizonScan.Sighting targetLead = targets.step(f.bot);
            f.require(targetLead != null && log.equals(targetLead.pos()) && targetLead.raysCast() == 0,
                    "expected the remembered block from shared sight, before any ray: " + targetLead);
            VisibleTargetHorizonScan.Sighting targetNext = targets.step(f.bot);
            f.require(targetNext == null || targetNext.raysCast() > 0,
                    "the target sweep handed back the shared lead again without advancing: " + targetNext);
            f.finish();
        });
    }

    /** One sealed room with a bot in it, cleaned up whichever way the test ends. */
    private static final class Fixture {
        final GameTestHelper context;
        final Room room;
        final AIPlayerEntity bot;
        final String name;
        private boolean done;

        Fixture(GameTestHelper context, String name, int slab) {
            this.context = context;
            this.name = name;
            this.room = new Room(context, SLAB_BASE + 30 * slab, -6, 6, -6, 6, ROOM_HEIGHT);
            for (int dx = -4; dx <= 4; dx += 4) {
                for (int dz = -4; dz <= 4; dz += 4) {
                    room.set(dx, ROOM_HEIGHT - 1, dz, Blocks.LIGHT);
                }
            }
            this.bot = AIPlayerManager.INSTANCE.spawn(context.getLevel().getServer(), name, room.world,
                            Vec3.atBottomCenterOf(room.feet), 0.0F, 0.0F, GameType.SURVIVAL)
                    .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
            bot.teleportTo(room.world, room.feet.getX() + 0.5D, room.feet.getY(), room.feet.getZ() + 0.5D,
                    Set.of(), 0.0F, 0.0F, true);
        }

        void require(boolean condition, String message) {
            if (!condition) {
                cleanup();
                context.fail(Component.nullToEmpty(message));
                throw new IllegalStateException(message);
            }
        }

        void finish() {
            cleanup();
            context.succeed();
        }

        private void cleanup() {
            if (done) {
                return;
            }
            done = true;
            AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), name);
            room.clear();
        }
    }
}

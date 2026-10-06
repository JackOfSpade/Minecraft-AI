package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.task.GatherOverheadLogGameTests.Case;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The generic mine request meets the same overhead target as gather: a block straight above has no heading for a
 * landmark leg and the sweep must not stall on it, and a block no route reaches is climbed to before it is set aside.
 * (Dirt, because the sealed room is stone and a stone mine would simply break the floor.)
 */
public final class MineOverheadGameTests {
    @GameTest(environment = "minecraftai-gametest:mine_overhead_game_tests_overhead_block_is_climbed_and_the_tower_taken_down", maxTicks = 1500)
    public void overheadBlockIsClimbedAndTheTowerTakenDown(GameTestHelper context) {
        Case c = new Case(context, "MineOverheadOwnGT", 0, 4);
        BlockPos dirt = c.at(0, 7, 0);
        c.set(0, 7, 0, Blocks.DIRT);
        c.give(new ItemStack(Items.WOODEN_SHOVEL), new ItemStack(Items.COBBLESTONE, 8));
        MineTask task = new MineTask(Blocks.DIRT, 1);
        task.start(c.bot);

        c.run(task, () -> {
            if (task.state() != TaskState.COMPLETED) {
                return false;
            }
            List<String> lines = c.log();
            c.require(c.count(lines, "mine_pillar_start", "target='" + dirt.toShortString() + "'") == 1,
                    "the overhead block was not climbed: " + c.tail(lines, "mine_"));
            c.require(c.count(lines, "mine_target_sighting_pursuit_refused") == 0,
                    "an overhead block must not go through a refused landmark pursuit: " + c.tail(lines, "mine_"));
            c.require(c.world().getBlockState(dirt).isAir() && InventoryAction.countItem(c.bot, Items.DIRT) >= 1,
                    "the block was not mined and picked up: " + c.tail(lines, "mine_"));
            c.require(c.count(lines, "mine_tower_descended") == 1 && c.bot.blockPosition().getY() == c.feet.getY(),
                    "the task ended with the bot still up on its tower: " + c.tail(lines, "mine_"));
            return true;
        });
    }

    @GameTest(environment = "minecraftai-gametest:mine_overhead_game_tests_excluded_overhead_block_does_not_stop_the_sweep_finding_another", maxTicks = 1500)
    public void excludedOverheadBlockDoesNotStopTheSweepFindingAnother(GameTestHelper context) {
        // The excluded block straight above answers the sweep's vertical ray on every step. It must be passed over
        // (neither climbed nor offered again) so the 360-degree raster reaches the second block off to the side.
        Case c = new Case(context, "MineOverheadExcludedGT", 1, 5);
        BlockPos excluded = c.at(0, 7, 0);
        BlockPos other = c.at(-3, 7, 2);
        c.set(0, 7, 0, Blocks.DIRT);
        c.set(-3, 7, 2, Blocks.DIRT);
        c.give(new ItemStack(Items.WOODEN_SHOVEL), new ItemStack(Items.COBBLESTONE, 8));
        EpisodeMemory.INSTANCE.exclude(c.bot.getUUID(), excluded, context.getLevel().getServer().getTickCount(),
                EpisodeMemory.TTL_UNREACHABLE);
        MineTask task = new MineTask(Blocks.DIRT, 1);
        task.start(c.bot);

        c.run(task, () -> {
            if (task.state() != TaskState.COMPLETED) {
                return false;
            }
            List<String> lines = c.log();
            c.require(c.world().getBlockState(excluded).is(Blocks.DIRT) && c.world().getBlockState(other).isAir(),
                    "the sweep took the excluded block instead of the other one: " + c.tail(lines, "mine_"));
            c.require(c.count(lines, "mine_pillar_start", "target='" + excluded.toShortString() + "'") == 0,
                    "an excluded block was climbed anyway: " + c.tail(lines, "mine_"));
            int pillar = c.indexOf(lines, "mine_pillar_start", "target='" + other.toShortString() + "'");
            c.require(pillar >= 0, "the second block was never climbed to: " + c.tail(lines, "mine_"));
            long sighted = lines.subList(0, pillar).stream().filter(line -> line.contains("event=mine_target_sighted")
                    && line.contains("pos='" + excluded.toShortString() + "'")).count();
            c.require(sighted <= 1, "the excluded block answered the sweep again and again (" + sighted + " times): "
                    + c.tail(lines, "mine_"));
            return true;
        });
    }

    @GameTest(environment = "minecraftai-gametest:mine_overhead_game_tests_block_a_route_refuses_is_climbed_to_before_it_is_set_aside", maxTicks = 1500)
    public void blockARouteRefusesIsClimbedToBeforeItIsSetAside(GameTestHelper context) {
        // A floating stone ledge is an observed stance beside the block, but no route reaches it (the bot holds
        // nothing a route could place). The search used to ask for the same refused route on every tick; now the
        // block is climbed to, or set aside, once. (Coarse dirt, because the dirt on the floor is the bot's pillar supply.)
        // The test ends with the block mined: where its drop comes to rest (the ledge, out of the bot's sight, is one
        // place) is a matter of the drop's random pop, and a bot with only the supports it needed has none to climb
        // for it with; the recovery has its own test.
        Case c = new Case(context, "MineOverheadLedgeGT", 2, 9);
        BlockPos coarse = c.at(0, 6, 7);
        c.set(0, 6, 7, Blocks.COARSE_DIRT);
        c.set(1, 5, 7, Blocks.STONE);
        for (int[] cell : new int[][] {{-3, 0, 0}, {-3, 0, 1}, {-3, 0, -1}, {-4, 0, 0}}) {
            c.set(cell[0], cell[1], cell[2], Blocks.DIRT);
        }
        c.give(new ItemStack(Items.WOODEN_SHOVEL));
        MineTask task = new MineTask(Blocks.COARSE_DIRT, 1);
        task.start(c.bot);

        c.run(task, () -> {
            if (!c.world().getBlockState(coarse).isAir()) {
                return false;
            }
            List<String> lines = c.log();
            c.require(c.count(lines, "mine_route_refused") <= 1,
                    "the same refused route was asked for again and again: " + c.tail(lines, "mine_"));
            c.require(c.count(lines, "mine_pillar_start", "target='" + coarse.toShortString() + "'") >= 1,
                    "the block no route reaches was not climbed to: " + c.tail(lines, "mine_"));
            return true;
        });
    }

    @GameTest(environment = "minecraftai-gametest:mine_overhead_game_tests_item_that_rests_on_a_ledge_out_of_sight_is_looked_for_and_collected", maxTicks = 1800)
    public void itemThatRestsOnALedgeOutOfSightIsLookedForAndCollected(GameTestHelper context) {
        // The drop of a block broken from a pillar can come to rest on a ledge beside the block: above the pillar's
        // head and hidden from it by the ledge itself, as the real session's canopy log lay on its leaf. Here the
        // ledge is a pocket beside the block, a stone shelf under it and a stone cap over it (so no one can stand
        // there and no route is asked for), and the fixture puts the drop in it (where a drop comes to rest is a matter
        // of its random pop). The bot waits the time it takes an item to fall and be collected, builds the pillar up
        // to the level of the block it broke, sees the item and collects it, and only then takes the tower down.
        Case c = new Case(context, "MineOverheadDropGT", 5, 9);
        BlockPos coarse = c.at(0, 6, 7);
        BlockPos shelf = c.at(1, 5, 7);
        c.set(0, 6, 7, Blocks.COARSE_DIRT);
        c.set(1, 5, 7, Blocks.STONE);
        c.set(1, 7, 7, Blocks.STONE);
        c.give(new ItemStack(Items.WOODEN_SHOVEL), new ItemStack(Items.WOODEN_PICKAXE), new ItemStack(Items.COBBLESTONE, 8));
        MineTask task = new MineTask(Blocks.COARSE_DIRT, 1);
        task.start(c.bot);

        boolean[] placed = {false};
        c.run(task, () -> {
            if (!placed[0]) {
                for (ItemEntity drop : c.world().getEntitiesOfClass(ItemEntity.class, new AABB(coarse).inflate(4.0D),
                        item -> item.getItem().is(Items.COARSE_DIRT))) {
                    drop.setPos(shelf.getX() + 0.5D, shelf.getY() + 1.0D, shelf.getZ() + 0.5D);
                    drop.setDeltaMovement(Vec3.ZERO);
                    placed[0] = true;
                }
            }
            if (task.state() != TaskState.COMPLETED) {
                return false;
            }
            List<String> lines = c.log();
            c.require(placed[0], "the block's drop never appeared: " + c.tail(lines, "mine_"));
            c.require(c.count(lines, "mine_drop_climb") == 1 && c.count(lines, "mine_drop_climb_refused") == 0,
                    "the bot never built up to look for the item: " + c.tail(lines, "mine_"));
            c.require(InventoryAction.countItem(c.bot, Items.COARSE_DIRT) == 1,
                    "the item on the shelf was not collected: " + c.tail(lines, "mine_"));
            c.require(c.count(lines, "mine_tower_descended") == 1 && c.bot.blockPosition().getY() == c.feet.getY()
                            && InventoryAction.countItem(c.bot, Items.COBBLESTONE) == 8,
                    "the tower was not taken down and its blocks picked up again: " + c.tail(lines, "mine_"));
            return true;
        });
    }

    @GameTest(environment = "minecraftai-gametest:mine_overhead_game_tests_block_it_cannot_build_up_to_is_not_offered_every_tick", maxTicks = 300)
    public void blockItCannotBuildUpToIsNotOfferedEveryTick(GameTestHelper context) {
        // The real session's leaf, in a generic mine request: a block the bot can see and can do nothing with answers
        // the look-around's very next ray again if the request does not tell the sweep, and the rest of the raster
        // never runs (326 refusals of one leaf in 37 seconds). The vertical ray makes it every step. The bot carries
        // blocks to build with, the dirt hangs seven up in its own column, and a roof of light blocks (no shape, so
        // the ray goes through them; not air, so no column under them is a pillar column) shuts every column.
        Case c = new Case(context, "MineOverheadRoofGT", 7, 9);
        BlockPos dirt = c.at(0, 7, 0);
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                c.set(x, 3, z, Blocks.LIGHT);
            }
        }
        c.set(0, 7, 0, Blocks.DIRT);
        c.give(new ItemStack(Items.WOODEN_SHOVEL), new ItemStack(Items.COBBLESTONE, 8));
        MineTask task = new MineTask(Blocks.DIRT, 1);
        task.start(c.bot);

        c.run(task, () -> {
            if (c.ticks < 40) {
                return false;
            }
            List<String> lines = c.log();
            long sighted = c.count(lines, "mine_target_sighted", "pos='" + dirt.toShortString() + "'");
            c.require(sighted >= 1, "the bot never saw the block overhead, so this proves nothing: " + c.tail(lines, "mine_"));
            c.require(sighted <= 2, "the same block it cannot build up to was sighted " + sighted + " times in 40 ticks: "
                    + c.tail(lines, "mine_"));
            c.require(c.count(lines, "mine_pillar_start") == 0, "a pillar was built through the roof: " + c.tail(lines, "mine_"));
            return true;
        });
    }

    @GameTest(environment = "minecraftai-gametest:mine_overhead_game_tests_blocks_of_the_towers_own_kind_are_not_counted_as_mined", maxTicks = 3000)
    public void blocksOfTheTowersOwnKindAreNotCountedAsMined(GameTestHelper context) {
        // The tower is built of dirt and the request is for dirt: its blocks come back as dirt when it is taken down.
        // Two blocks are asked for, so the first one's pickup must be worth one, not one plus the tower.
        Case c = new Case(context, "MineOverheadSameKindGT", 6, 9);
        BlockPos first = c.at(0, 7, 0);
        BlockPos second = c.at(6, 7, 0);
        c.set(0, 7, 0, Blocks.DIRT);
        c.set(6, 7, 0, Blocks.DIRT);
        c.give(new ItemStack(Items.WOODEN_SHOVEL), new ItemStack(Items.DIRT, 8));
        MineTask task = new MineTask(Blocks.DIRT, 2);
        task.start(c.bot);

        c.budget = 2800;
        c.run(task, () -> {
            if (task.state() != TaskState.COMPLETED) {
                return false;
            }
            List<String> lines = c.log();
            c.require(c.world().getBlockState(first).isAir() && c.world().getBlockState(second).isAir(),
                    "the quota of two closed with a block still standing: " + c.tail(lines, "mine_"));
            c.require(c.count(lines, "pickup_collected", "count='1'") == 2,
                    "a pickup was counted for more than the one block mined: " + c.tail(lines, ""));
            // One of the tower's blocks may have popped out of reach of the spot the bot finished on.
            c.require(InventoryAction.countItem(c.bot, Items.DIRT) >= 9,
                    "expected the supports back and the two blocks mined: " + InventoryAction.countItem(c.bot, Items.DIRT));
            return true;
        });
    }

    @GameTest(environment = "minecraftai-gametest:mine_overhead_game_tests_block_over_a_hill_is_climbed_from_the_hills_top", maxTicks = 1800)
    public void blockOverAHillIsClimbedFromTheHillsTop(GameTestHelper context) {
        // Two broad steps of stone rise toward the far wall and the block hangs nine up above the top one: nothing
        // at the bot's own level is a column worth building in, so it walks up the hill and builds two blocks.
        Case c = new Case(context, "MineOverheadHillGT", 3, 9);
        BlockPos dirt = c.at(0, 9, 6);
        c.set(0, 9, 6, Blocks.DIRT);
        for (int row = 0; row < 2; row++) {
            for (int dz = 2 + 4 * row; dz <= 5 + 4 * row; dz++) {
                for (int dx = -4; dx <= 4; dx++) {
                    for (int y = 0; y <= row; y++) {
                        c.set(dx, y, dz, Blocks.STONE);
                    }
                }
            }
        }
        c.give(new ItemStack(Items.WOODEN_SHOVEL), new ItemStack(Items.COBBLESTONE, 8));
        MineTask task = new MineTask(Blocks.DIRT, 1);
        task.start(c.bot);

        c.run(task, () -> {
            if (task.state() != TaskState.COMPLETED) {
                return false;
            }
            List<String> lines = c.log();
            int walk = c.indexOf(lines, "mine_pillar_base_walk", "target='" + dirt.toShortString() + "'");
            int pillar = c.indexOf(lines, "mine_pillar_start", "target='" + dirt.toShortString() + "'", "supports='2'");
            c.require(walk >= 0 && pillar > walk,
                    "expected a walk up the hill, then the two-block pillar (" + walk + "," + pillar + "): " + c.tail(lines, "mine_"));
            c.require(c.world().getBlockState(dirt).isAir() && InventoryAction.countItem(c.bot, Items.DIRT) >= 1,
                    "the block was not mined and picked up: " + c.tail(lines, "mine_"));
            return true;
        });
    }
}

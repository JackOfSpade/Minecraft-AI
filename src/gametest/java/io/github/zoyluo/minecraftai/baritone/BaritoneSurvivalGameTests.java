package io.github.zoyluo.minecraftai.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.BlockOptionalMetaLookup;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLogWriter;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.navigation.NavRoute;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The strict-survival rules of the Baritone integration, tested negatively on a real server with real bots: what Baritone must
 * <em>not</em> do even though it could ({@link BaritoneBreakPlacePolicy}). The positive courses (breaking through a natural stone
 * wall, bridging, pillaring, a wooden door) live in {@link BaritoneNavigationGameTests} and must keep passing next to these.
 *
 * <ul>
 *   <li>A coordinate approach whose target is hidden behind a bed, chest, crafting table, player-built planks, or even natural
 *       stone is refused before Baritone may use loaded-world knowledge to plan through the wall; no terrain is touched.</li>
 *   <li>The controller, asked directly, refuses to break any of them (and bedrock, a spawner, glass) and a natural stone that the
 *       bot cannot see, and logs it; a visible natural stone is broken.</li>
 *   <li>A placement whose support face is hidden, out of reach, interactive (a chest), of a wrong item or forbidden by the bot's
 *       permission is refused; the same click on a visible floor face places.</li>
 *   <li>Using an item without a block (a water bucket, an ender pearl, food) and inventory moves are refused; a hotbar swap is
 *       allowed only when the setting is on.</li>
 *   <li>Baritone's scanning processes (mine, get-to-block, farm, explore, build) do not start, and goals other than coordinate goals
 *       are refused.</li>
 *   <li>A mining goal on an ore the bot cannot observe is refused; on an ore it can see it is accepted and carried out.</li>
 * </ul>
 *
 * <p>Vertical layout: every test runs at the same time as the others of its batch, in structures 13 blocks apart, so a course wider
 * than that gets a slab of its own. The slabs sit in the free rows between the planning tests' platforms ({@code 40 + 12 * k}):
 * the small tests share slab 0, the big courses use slabs 1 to 5.</p>
 */
public final class BaritoneSurvivalGameTests {
    private static final int SLAB_BASE_Y = 46;
    private static final int SLAB_STEP = 12;
    private static final int HALF_Z = 7;

    // ---------------------------------------------------------------------------------------------------------------
    // Routes: around the protected block, or no route
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = 900)
    public void routesAroundABedOnTheShortestRoute(GameTestHelper context) {
        detourCourse(context, "SurvBedGT", 1, (world, at) -> placeBed(world, at, Direction.EAST));
    }

    @GameTest(maxTicks = 900)
    public void routesAroundAChestOnTheShortestRoute(GameTestHelper context) {
        detourCourse(context, "SurvChestGT", 2, (world, at) -> world.setBlock(at, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL));
    }

    @GameTest(maxTicks = 900)
    public void routesAroundACraftingTableOnTheShortestRoute(GameTestHelper context) {
        detourCourse(context, "SurvTableGT", 3, (world, at) -> world.setBlock(at, Blocks.CRAFTING_TABLE.defaultBlockState(), Block.UPDATE_ALL));
    }

    @GameTest(maxTicks = 900)
    public void routesAroundAPlayerBuiltPlankWallOnTheShortestRoute(GameTestHelper context) {
        detourCourse(context, "SurvPlanksGT", 4, (world, at) -> world.setBlock(at, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL));
    }

    /** The natural-stone control: a break-capable request still cannot use a goal hidden behind the wall as a map oracle. */
    @GameTest(maxTicks = 900)
    public void takesTheShortcutThroughNaturalStoneOnTheSameGeometry(GameTestHelper context) {
        detourCourse(context, "SurvStoneGT", 6, (world, at) -> world.setBlock(at, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL));
    }

    /**
     * A wall with one blocked column (two cells deep and two high) at the bot's own row and one open gap far away. The target is
     * deliberately hidden behind it, so strict observed navigation must reject the request before searching the loaded course.
     */
    private static void detourCourse(GameTestHelper context, String name, int slab, Filler obstacle) {
        Course c = Course.begin(context, name, slab, -2, 14, HALF_Z);
        for (int dz = -HALF_Z; dz <= HALF_Z; dz++) {
            for (int dx = 5; dx <= 6; dx++) {
                c.fill(dx, dz, Blocks.BEDROCK, 0, 3);
            }
        }
        for (int dx = 5; dx <= 6; dx++) {
            c.fill(dx, HALF_Z, Blocks.AIR, 0, 3); // the open gap, far from the start
        }
        // The blocked column in the bot's own row: two cells deep, two high (bedrock stays above, nothing can be stepped over).
        int row = -HALF_Z + 1;
        for (int dx = 5; dx <= 6; dx++) {
            c.fill(dx, row, Blocks.AIR, 0, 1);
        }
        List<BlockPos> cells = new ArrayList<>();
        for (int dy = 0; dy <= 1; dy++) {
            obstacle.place(c.world, c.feet.offset(5, dy, row));
            if (c.world.getBlockState(c.feet.offset(6, dy, row)).isAir()) { // a bed already covers both cells
                obstacle.place(c.world, c.feet.offset(6, dy, row));
            }
        }
        for (int dx = 5; dx <= 6; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos cell = c.feet.offset(dx, dy, row);
                require(context, !c.world.getBlockState(cell).isAir(), "the obstacle did not fill " + cell);
                cells.add(cell);
            }
        }
        Map<BlockPos, BlockState> intact = c.remember(cells);
        c.giveTools();
        c.moveBot(0, -HALF_Z + 1);
        c.snapshot();
        BlockPos goal = c.feet.offset(10, 0, -HALF_Z + 1);
        BaritoneNavigator.Admission admission = admitHiddenWallGoal(c, goal);
        require(context, !admission.accepted()
                        && ("navigation_goal_unobserved".equals(admission.failure())
                        || "navigation_observed_corridor_unavailable".equals(admission.failure())),
                "the hidden wall goal was not refused by observation admission: " + admission);
        c.await(20, true, run -> {
            require(context, run.trail.stream().noneMatch(p -> p.x > c.feet.getX() + 4.6), "the rejected route moved through the wall");
            requireIntact(context, c, intact);
            require(context, BaritoneEdits.of(c.bot.getUUID()).isEmpty(), "the rejected route edited terrain: " + BaritoneEdits.of(c.bot.getUUID()));
            run.requireEditsExplainTheWorldDiff();
            require(context, !BaritoneRegistry.INSTANCE.isBusy(c.bot) && !c.baritone.getCustomGoalProcess().isActive(),
                    "a refused hidden goal left Baritone active: " + c.describe());
        });
    }

    @GameTest(maxTicks = 900)
    public void endsWithoutABreakWhenTheOnlyRouteIsThroughProtectedBlocks(GameTestHelper context) {
        Course c = Course.begin(context, "SurvSealedGT", 5, -2, 14, HALF_Z);
        Filler[] fillers = {
                (world, at) -> placeBed(world, at, Direction.EAST),
                (world, at) -> world.setBlock(at, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL),
                (world, at) -> world.setBlock(at, Blocks.CRAFTING_TABLE.defaultBlockState(), Block.UPDATE_ALL),
                (world, at) -> world.setBlock(at, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL),
                (world, at) -> world.setBlock(at, Blocks.FURNACE.defaultBlockState(), Block.UPDATE_ALL),
                (world, at) -> world.setBlock(at, Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL),
                (world, at) -> world.setBlock(at, Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL),
        };
        List<BlockPos> cells = new ArrayList<>();
        for (int dz = -HALF_Z; dz <= HALF_Z; dz++) {
            for (int dx = 5; dx <= 6; dx++) {
                c.fill(dx, dz, Blocks.BEDROCK, 0, 3);
            }
        }
        // One protected doorway per row, z = -6, -4, ..., 6: two cells deep, two high, sealed above.
        for (int i = 0; i < fillers.length; i++) {
            int dz = -HALF_Z + 1 + 2 * i;
            for (int dx = 5; dx <= 6; dx++) {
                c.fill(dx, dz, Blocks.AIR, 0, 1);
            }
            for (int dy = 0; dy <= 1; dy++) {
                fillers[i].place(c.world, c.feet.offset(5, dy, dz));
                if (!(c.world.getBlockState(c.feet.offset(5, dy, dz)).getBlock() instanceof BedBlock)) {
                    fillers[i].place(c.world, c.feet.offset(6, dy, dz));
                }
            }
            for (int dx = 5; dx <= 6; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos cell = c.feet.offset(dx, dy, dz);
                    if (c.world.getBlockState(cell).isAir()) {
                        c.world.setBlock(cell, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
                    }
                    cells.add(cell);
                }
            }
        }
        Map<BlockPos, BlockState> intact = c.remember(cells);
        c.giveTools();
        c.snapshot();
        BlockPos goal = c.feet.offset(10, 0, 0);
        BaritoneNavigator.Admission admission = admitHiddenWallGoal(c, goal);
        require(context, !admission.accepted() && "navigation_goal_unobserved".equals(admission.failure()),
                "the sealed hidden goal was not refused by observation admission: " + admission);
        c.await(20, true, run -> {
            require(context, run.trail.stream().noneMatch(p -> p.x > c.feet.getX() + 4.6), "the bot got through the wall");
            requireIntact(context, c, intact);
            require(context, BaritoneEdits.of(c.bot.getUUID()).isEmpty(), "the terrain was edited: " + BaritoneEdits.of(c.bot.getUUID()));
            require(context, !BaritoneRegistry.INSTANCE.isBusy(c.bot) && !c.baritone.getCustomGoalProcess().isActive(),
                    "a refused hidden goal left Baritone active: " + c.describe());
        });
    }

    /** Uses the production admission seam, retaining break permission to prove target visibility is the refusal cause. */
    private static BaritoneNavigator.Admission admitHiddenWallGoal(Course c, BlockPos goal) {
        NavRoute route = new NavRoute(NavRoute.Shape.NEAR, goal, 1,
                new NavRoute.Options(true, false, false), "survival_hidden_wall", c.bot.getServer().getTickCount());
        return BaritoneNavigator.start(c.bot, route, true);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The break rule, asked directly
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = 200)
    public void controllerRefusesToBreakProtectedBlocksAndUnseenStone(GameTestHelper context) {
        Small s = Small.begin(context, "SurvBreakGT", 4);
        // A row of things a bot must not break, all within reach, and a solid mass of stone with one cell deep inside.
        BlockPos bed = s.at(-3, 0, 1);
        placeBed(s.world, bed, Direction.EAST);
        BlockPos chest = s.at(-3, 0, 3);
        s.world.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos table = s.at(0, 0, 3);
        s.world.setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos planks = s.at(1, 0, 3);
        s.world.setBlock(planks, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos glass = s.at(2, 0, 3);
        s.world.setBlock(glass, Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos spawner = s.at(3, 0, 3);
        s.world.setBlock(spawner, Blocks.SPAWNER.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos bedrock = s.at(3, 0, 1);
        s.world.setBlock(bedrock, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
        for (int dx = 1; dx <= 3; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    s.world.setBlock(s.at(dx, dy, -2 + dz - 1), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        BlockPos hidden = s.at(3, 0, -3);
        // Keep a positive natural-stone control outside the wall. The old front-wall cell is
        // geometrically occluded by the structure at the bot's eye height, so it cannot prove
        // the observed-action path this test is meant to cover.
        BlockPos observedStone = s.at(1, 0, 0);
        s.world.setBlock(observedStone, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos[] protectedCells = {bed, chest, table, planks, glass, spawner, bedrock};
        String[] expected = {"block_entity", "block_entity", "protected_block", "structure_block", "structure_block", "not_observable", "unbreakable"};
        Map<BlockPos, BlockState> before = s.remember(List.of(protectedCells));
        before.putAll(s.remember(List.of(hidden, observedStone)));
        ServerPlayerController controller = s.controller();
        for (int i = 0; i < protectedCells.length; i++) {
            // The spawner is hidden behind the other protected cells. Its strict refusal is
            // still required, but it cannot earn downstream block-entity classification.
            if (!protectedCells[i].equals(spawner)) {
                s.observeActionTarget(protectedCells[i]);
            }
            int refusalsBefore = BaritoneRefusals.of(s.bot.getUUID(), BaritoneRefusals.Op.BREAK).size();
            require(context, !controller.clickBlock(protectedCells[i], Direction.UP), "the controller started to break " + s.world.getBlockState(protectedCells[i]));
            List<BaritoneRefusals.Refusal> now = BaritoneRefusals.of(s.bot.getUUID(), BaritoneRefusals.Op.BREAK);
            require(context, now.size() == refusalsBefore + 1, "no refusal was recorded for " + protectedCells[i]);
            // Depending on the immediately preceding route fence, the shielded spawner can be
            // rejected at admission or at the later block-entity rule. Both outcomes fail closed;
            // the separate hidden-stone assertion below keeps the strict unseen-cell contract.
            boolean validRefusal = expected[i].equals(now.get(now.size() - 1).reason())
                    || (protectedCells[i].equals(spawner)
                    && "block_entity".equals(now.get(now.size() - 1).reason()));
            require(context, validRefusal,
                    "refusal for " + protectedCells[i] + " was " + now.get(now.size() - 1).reason() + ", expected " + expected[i]);
        }
        require(context, !controller.clickBlock(hidden, Direction.WEST), "the controller started to break a stone the bot cannot see");
        List<BaritoneRefusals.Refusal> breaks = BaritoneRefusals.of(s.bot.getUUID(), BaritoneRefusals.Op.BREAK);
        require(context, "not_observable".equals(breaks.get(breaks.size() - 1).reason()), "the unseen stone was refused for " + breaks.get(breaks.size() - 1).reason());
        s.observeActionTarget(observedStone);
        BaritoneRegistry.INSTANCE.setPolicy(s.bot, BaritonePolicy.NO_BREAKING);
        require(context, !controller.clickBlock(observedStone, Direction.SOUTH), "a bot that may not break started to break");
        breaks = BaritoneRefusals.of(s.bot.getUUID(), BaritoneRefusals.Op.BREAK);
        require(context, "policy_no_break".equals(breaks.get(breaks.size() - 1).reason()), "refused for " + breaks.get(breaks.size() - 1).reason());
        BaritoneRegistry.INSTANCE.setPolicy(s.bot, BaritonePolicy.UNRESTRICTED);
        require(context, controller.clickBlock(observedStone, Direction.SOUTH), "a natural stone the bot can see was refused: " + BaritoneRefusals.of(s.bot.getUUID()));
        controller.resetBlockRemoving();
        for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
            require(context, s.world.getBlockState(entry.getKey()).equals(entry.getValue()), "the block at " + entry.getKey() + " changed");
        }
        require(context, BaritoneEdits.of(s.bot.getUUID(), BaritoneEdits.Kind.BREAK).isEmpty(), "a break was recorded");
        s.requireLogged("baritone_refused", protectedCells.length + 2);
        s.finish();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Placement, item use, inventory
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = 200)
    public void placementWithoutSightOrSupportIsRefused(GameTestHelper context) {
        Small s = Small.begin(context, "SurvPlaceGT", 4);
        s.giveHand(new ItemStack(Items.COBBLESTONE, 16));
        ServerPlayerController controller = s.controller();
        List<BlockPos> watched = new ArrayList<>();
        // 1. Hidden face: the top of a floor block that has stone on it (the ray from the eye meets the stone first).
        BlockPos coveredFloor = s.at(3, -1, 0);
        BlockPos coverStone = s.at(3, 0, 0);
        s.world.setBlock(coverStone, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        watched.add(s.at(3, 1, 0));
        // 2. A chest as the support.
        BlockPos chest = s.at(2, 0, 2);
        s.world.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        watched.add(s.at(2, 1, 2));
        // 3. Out of reach: a floor block eight cells away.
        BlockPos far = s.at(8, -1, 0);
        watched.add(s.at(8, 0, 0));
        // 4. The legal one: the floor block right in front, its top face in plain view.
        BlockPos floor = s.at(1, -1, 0);
        BlockPos placedAt = s.at(1, 0, 0);

        // The covered support itself cannot be observed, so the strict admission gate is the
        // whole refusal contract for this case.  Every downstream-policy case below first earns
        // a real ray-based route fence for its support cell.
        expectRefused(context, s, controller, top(coveredFloor), "not_observable");
        s.observeActionTarget(chest);
        expectRefused(context, s, controller, top(chest), "support_is_interactive");
        // This support is beyond the available sight proof as well as interaction reach.  Keep
        // the stricter admission result rather than manufacturing an observed far-away cell.
        expectRefused(context, s, controller, top(far), "not_observable");
        s.observeActionTarget(floor);
        s.giveHand(new ItemStack(Items.OAK_PLANKS, 16));
        expectRefused(context, s, controller, top(floor), "item_not_allowed");
        s.giveHand(new ItemStack(Items.COBBLESTONE, 16));
        BaritoneRegistry.INSTANCE.setPolicy(s.bot, BaritonePolicy.NO_PLACING);
        expectRefused(context, s, controller, top(floor), "policy_no_place");
        BaritoneRegistry.INSTANCE.setPolicy(s.bot, BaritonePolicy.UNRESTRICTED);
        for (BlockPos cell : watched) {
            require(context, s.world.getBlockState(cell).isAir(), "a block appeared at " + cell + " although every placement was refused");
        }
        require(context, BaritoneEdits.of(s.bot.getUUID(), BaritoneEdits.Kind.PLACE).isEmpty(), "a refused placement was recorded as an edit");
        require(context, s.count(Items.COBBLESTONE) == 16, "a refused placement used up cobblestone");

        InteractionResult result = controller.processRightClickBlock(s.bot, s.world, InteractionHand.MAIN_HAND, top(floor));
        require(context, result.consumesAction(), "the legal placement was refused: " + result + " " + BaritoneRefusals.of(s.bot.getUUID()));
        require(context, s.world.getBlockState(placedAt).is(Blocks.COBBLESTONE), "the legal placement did not put a block down: " + s.world.getBlockState(placedAt));
        List<BaritoneEdits.Edit> placed = BaritoneEdits.of(s.bot.getUUID(), BaritoneEdits.Kind.PLACE);
        require(context, placed.size() == 1 && placed.get(0).pos().equals(placedAt), "placement ledger: " + placed);
        s.requireLogged("baritone_refused", 5);
        s.finish();
    }

    /** The top face of a closed bottom-half trapdoor (3/16 high). */
    private static BlockHitResult trapdoorTop(BlockPos trapdoor) {
        return new BlockHitResult(new Vec3(trapdoor.getX() + 0.5D, trapdoor.getY() + 0.1875D, trapdoor.getZ() + 0.5D), Direction.UP, trapdoor, false);
    }

    /**
     * A click that opens a trapdoor is allowed for whatever the hand holds, but only from a bot that is not sneaking (a sneaking
     * player with an item in hand uses the item, not the block) and only for a trapdoor a hand can open: the wooden and the
     * copper one open, the iron one is a refused use of the pickaxe.
     */
    @GameTest(maxTicks = 200)
    public void clickOpensHandTrapdoorsOnlyFromANonSneakingBotAndNeverIron(GameTestHelper context) {
        Small s = Small.begin(context, "SurvTrapGT", 4);
        s.giveHand(new ItemStack(Items.STONE_PICKAXE, 1));
        ServerPlayerController controller = s.controller();
        BlockPos wooden = s.at(1, 0, 0);
        BlockPos iron = s.at(2, 0, 0);
        BlockPos copper = s.at(3, 0, 0);
        s.world.setBlock(wooden, Blocks.OAK_TRAPDOOR.defaultBlockState(), Block.UPDATE_ALL);
        s.world.setBlock(iron, Blocks.IRON_TRAPDOOR.defaultBlockState(), Block.UPDATE_ALL);
        s.world.setBlock(copper, Blocks.COPPER_TRAPDOOR.defaultBlockState(), Block.UPDATE_ALL);
        var open = net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN;

        s.bot.setShiftKeyDown(true);
        require(context, s.bot.isSecondaryUseActive(), "the test bot is not sneaking");
        s.observeActionTarget(wooden);
        expectRefused(context, s, controller, trapdoorTop(wooden), "not_a_block_item");
        require(context, !s.world.getBlockState(wooden).getValue(open), "a sneaking click opened the trapdoor");
        s.bot.setShiftKeyDown(false);

        s.observeActionTarget(iron);
        expectRefused(context, s, controller, trapdoorTop(iron), "not_a_block_item");
        require(context, !s.world.getBlockState(iron).getValue(open), "an iron trapdoor opened by hand");

        s.observeActionTarget(wooden);
        InteractionResult result = controller.processRightClickBlock(s.bot, s.world, InteractionHand.MAIN_HAND, trapdoorTop(wooden));
        require(context, result.consumesAction() && s.world.getBlockState(wooden).getValue(open),
                "the click did not open the wooden trapdoor: " + result + " " + BaritoneRefusals.of(s.bot.getUUID()));
        s.observeActionTarget(copper);
        result = controller.processRightClickBlock(s.bot, s.world, InteractionHand.MAIN_HAND, trapdoorTop(copper));
        require(context, result.consumesAction() && s.world.getBlockState(copper).getValue(open),
                "the click did not open the copper trapdoor: " + result + " " + BaritoneRefusals.of(s.bot.getUUID()));
        require(context, BaritoneEdits.of(s.bot.getUUID(), BaritoneEdits.Kind.PLACE).isEmpty(), "opening a trapdoor was recorded as a placement");
        s.finish();
    }

    /**
     * Vanilla ({@code ServerPlayerGameMode#useItemOn}) lets the held item win over the block only for a player who is sneaking AND
     * holds something in either hand. A sneaking bot with both hands empty still opens a hand trapdoor and a door; the same bot with
     * something in its off hand only uses that item, which for the policy is a refused click.
     */
    @GameTest(maxTicks = 200)
    public void aSneakingBotWithBothHandsEmptyStillOpensAHandTrapdoorAndDoor(GameTestHelper context) {
        Small s = Small.begin(context, "SurvSneakEmptyGT", 4);
        s.giveHand(ItemStack.EMPTY);
        ServerPlayerController controller = s.controller();
        BlockPos trapdoor = s.at(1, 0, 0);
        BlockPos door = s.at(3, 0, 0);
        s.world.setBlock(trapdoor, Blocks.OAK_TRAPDOOR.defaultBlockState(), Block.UPDATE_ALL);
        var open = net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN;
        var half = net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF;
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, Direction.EAST)
                .setValue(half, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
        s.world.setBlock(door, lower, Block.UPDATE_ALL);
        s.world.setBlock(door.above(), lower.setValue(half, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), Block.UPDATE_ALL);

        s.bot.setShiftKeyDown(true);
        require(context, s.bot.isSecondaryUseActive(), "the test bot is not sneaking");
        require(context, s.bot.getMainHandItem().isEmpty() && s.bot.getOffhandItem().isEmpty(), "the hands are not empty");
        // An item in the OFF hand makes the item win, so the click is a refused use of an empty main hand.
        s.bot.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.STONE_PICKAXE));
        s.observeActionTarget(trapdoor);
        expectRefused(context, s, controller, trapdoorTop(trapdoor), "not_a_block_item");
        require(context, !s.world.getBlockState(trapdoor).getValue(open), "a sneaking click with an item in the off hand opened the trapdoor");
        s.bot.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);

        s.observeActionTarget(trapdoor);
        InteractionResult result = controller.processRightClickBlock(s.bot, s.world, InteractionHand.MAIN_HAND, trapdoorTop(trapdoor));
        require(context, result.consumesAction() && s.world.getBlockState(trapdoor).getValue(open),
                "a sneaking bot with both hands empty did not open the trapdoor: " + result + " " + BaritoneRefusals.of(s.bot.getUUID()));
        // a closed door facing east is a 3/16 plate on the west edge of its cell: the bot (west of it) looks at its west face
        BlockHitResult doorHit = new BlockHitResult(new Vec3(door.getX(), door.getY() + 0.5D, door.getZ() + 0.5D), Direction.WEST, door, false);
        s.observeActionTarget(door);
        result = controller.processRightClickBlock(s.bot, s.world, InteractionHand.MAIN_HAND, doorHit);
        require(context, result.consumesAction() && s.world.getBlockState(door).getValue(net.minecraft.world.level.block.DoorBlock.OPEN),
                "a sneaking bot with both hands empty did not open the door: " + result + " " + BaritoneRefusals.of(s.bot.getUUID()));
        require(context, BaritoneEdits.of(s.bot.getUUID(), BaritoneEdits.Kind.PLACE).isEmpty(), "opening was recorded as a placement");
        s.finish();
    }

    @GameTest(maxTicks = 200)
    public void itemUseWithoutABlockAndInventoryMovesAreRefused(GameTestHelper context) {
        Small s = Small.begin(context, "SurvUseGT", 4);
        ServerPlayerController controller = s.controller();
        for (var item : List.of(Items.WATER_BUCKET, Items.ENDER_PEARL, Items.GOLDEN_APPLE, Items.SPLASH_POTION)) {
            s.giveHand(new ItemStack(item, 1));
            InteractionResult result = controller.processRightClick(s.bot, s.world, InteractionHand.MAIN_HAND);
            require(context, !result.consumesAction(), item + " was used: " + result);
            require(context, s.bot.getMainHandItem().is(item) && s.bot.getMainHandItem().getCount() == 1, item + " was consumed");
        }
        List<BaritoneRefusals.Refusal> uses = BaritoneRefusals.of(s.bot.getUUID(), BaritoneRefusals.Op.USE_ITEM);
        // The water bucket is refused too: outside a fall movement (BaritoneWaterFall) it is an item like any other.
        require(context, uses.size() == 4 && uses.stream().allMatch(r -> r.reason().equals(r.detail().equals("minecraft:water_bucket") ? "not_in_fall_movement" : "item_not_allowed")),
                "item use refusals: " + uses);
        for (int dx = -1; dx <= 1; dx++) {
            require(context, s.world.getBlockState(s.at(dx, 0, 0)).isAir(), "water or something else appeared on the floor: " + s.world.getBlockState(s.at(dx, 0, 0)));
        }

        // Inventory moves: refused while Baritone's allowInventory is off (the fixed setting) ...
        s.bot.getInventory().setItem(11, new ItemStack(Items.STONE, 5));
        s.bot.getInventory().setItem(0, new ItemStack(Items.DIRT, 3));
        int menu = s.bot.inventoryMenu.containerId;
        require(context, !BaritoneAPI.getSettings().allowInventory.value, "the fixed settings left allowInventory on");
        controller.windowClick(menu, 11, 0, ClickType.SWAP, s.bot);
        require(context, s.bot.getInventory().getItem(11).is(Items.STONE) && s.bot.getInventory().getItem(0).is(Items.DIRT), "the inventory was moved although moves are off");
        // ... and with it on (switched back at once, the setting is global) only the hotbar swap goes through.
        boolean before = BaritoneAPI.getSettings().allowInventory.value;
        try {
            BaritoneAPI.getSettings().allowInventory.value = true;
            controller.windowClick(menu, 11, 0, ClickType.THROW, s.bot);
            controller.windowClick(menu, 11, 0, ClickType.QUICK_MOVE, s.bot);
            controller.windowClick(menu, 11, 0, ClickType.PICKUP, s.bot);
            controller.windowClick(menu, 5, 0, ClickType.SWAP, s.bot); // an armour slot
            require(context, s.bot.getInventory().getItem(11).is(Items.STONE) && s.bot.getInventory().getItem(0).is(Items.DIRT)
                    && s.bot.getInventory().getItem(11).getCount() == 5, "a click that is not a hotbar swap changed the inventory");
            controller.windowClick(menu, 11, 0, ClickType.SWAP, s.bot);
            require(context, s.bot.getInventory().getItem(0).is(Items.STONE) && s.bot.getInventory().getItem(11).is(Items.DIRT), "the hotbar swap was refused");
        } finally {
            BaritoneAPI.getSettings().allowInventory.value = before;
        }
        List<BaritoneRefusals.Refusal> moves = BaritoneRefusals.of(s.bot.getUUID(), BaritoneRefusals.Op.INVENTORY);
        require(context, moves.size() == 5, "inventory refusals: " + moves);
        s.requireLogged("baritone_refused", 9);
        s.finish();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Targets: no scanning processes, no unobserved goals
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = 200)
    public void scanningProcessesRefuseToStartAndOnlyCoordinateGoalsAreAccepted(GameTestHelper context) {
        Small s = Small.begin(context, "SurvScanGT", 4);
        // Ore that exists, is loaded, and that the bot has never seen: what a scanning process would walk straight to.
        s.world.setBlock(s.at(3, -6, 0), Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
        IBaritone baritone = s.baritone;
        baritone.getMineProcess().mine(0, new BlockOptionalMetaLookup(Blocks.DIAMOND_ORE));
        baritone.getMineProcess().mineByName(0, "diamond_ore");
        baritone.getGetToBlockProcess().getToBlock(new BlockOptionalMeta(Blocks.DIAMOND_ORE));
        baritone.getFarmProcess().farm(20, null);
        baritone.getExploreProcess().explore(0, 0);
        baritone.getBuilderProcess().clearArea(s.at(0, 0, 0), s.at(2, 2, 2));
        require(context, !baritone.getMineProcess().isActive(), "the mine process started");
        require(context, !baritone.getGetToBlockProcess().isActive(), "the get-to-block process started");
        require(context, !baritone.getFarmProcess().isActive(), "the farm process started");
        require(context, !baritone.getExploreProcess().isActive(), "the explore process started");
        require(context, !baritone.getBuilderProcess().isActive(), "the builder process started");
        require(context, !BaritoneRegistry.INSTANCE.isBusy(s.bot), "Baritone claims the bot after refusing every process");
        List<BaritoneRefusals.Refusal> scans = BaritoneRefusals.of(s.bot.getUUID(), BaritoneRefusals.Op.SCAN_PROCESS);
        require(context, scans.size() >= 6, "expected a refusal for every start, got " + scans);
        require(context, scans.stream().map(BaritoneRefusals.Refusal::detail).distinct().count() == 5, "not every process was named: " + scans);

        require(context, !BaritoneGoals.setGoal(s.bot, new GoalRunAway(5.0D, s.at(0, 0, 0))).accepted(), "a non-coordinate goal was accepted");
        require(context, !baritone.getCustomGoalProcess().isActive(), "the refused goal is active");
        require(context, BaritoneGoals.walkNear(s.bot, s.at(2, 0, 0), 1).accepted(), "a coordinate goal was refused");
        require(context, baritone.getCustomGoalProcess().isActive(), "the coordinate goal is not active");
        baritone.getPathingBehavior().cancelEverything();
        s.requireLogged("baritone_refused", 6);
        s.finish();
    }

    @GameTest(maxTicks = 600)
    public void miningGoalOnAnUnobservedOreIsRefusedAndOnAVisibleOreIsCarriedOut(GameTestHelper context) {
        Small s = Small.begin(context, "SurvOreGT", 4);
        for (int dx = 1; dx <= 6; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    s.world.setBlock(s.at(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        BlockPos visibleOre = s.at(1, 0, 0);
        BlockPos hiddenOre = s.at(5, 0, 0);
        s.world.setBlock(visibleOre, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
        s.world.setBlock(hiddenOre, Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
        s.bot.getInventory().setItem(1, new ItemStack(Items.IRON_PICKAXE));
        require(context, !Sight.sees(s.bot, hiddenOre), "the test setup is wrong: the bot can see the buried ore");
        require(context, Sight.sees(s.bot, visibleOre), "the test setup is wrong: the bot cannot see the exposed ore");

        BaritoneGoals.Outcome refused = BaritoneGoals.mineAt(s.bot, hiddenOre);
        require(context, !refused.accepted() && "target_not_observed".equals(refused.reason()), "unobserved ore: " + refused);
        require(context, !s.baritone.getCustomGoalProcess().isActive() && !BaritoneRegistry.INSTANCE.isBusy(s.bot), "Baritone started towards the unobserved ore");
        BaritoneGoals.Outcome nothing = BaritoneGoals.mineAt(s.bot, s.at(0, 3, 0));
        require(context, !nothing.accepted(), "an empty cell was accepted as a mining target");

        BaritoneGoals.Outcome accepted = BaritoneGoals.mineAt(s.bot, visibleOre);
        require(context, accepted.accepted(), "the visible ore was refused: " + accepted);
        require(context, !s.baritone.getCustomGoalProcess().isActive() && !BaritoneRegistry.INSTANCE.isBusy(s.bot),
                "a direct observed interaction unexpectedly started a Baritone navigation route");
        int[] ticks = {0};
        context.onEachTick(() -> {
            if (s.done) {
                return;
            }
            ticks[0]++;
            // Direct mining is a physical player action admitted by BaritoneGoals; it is not a
            // long-running Baritone route, so registry "busy" is intentionally false here.
            // Complete only after the observed target has actually changed in the world.
            if (s.world.getBlockState(visibleOre).isAir()) {
                s.done = true;
                try {
                    List<BaritoneEdits.Edit> breaks = BaritoneEdits.of(s.bot.getUUID(), BaritoneEdits.Kind.BREAK);
                    require(context, breaks.stream().anyMatch(edit -> edit.pos().equals(visibleOre) && edit.block().equals("minecraft:coal_ore")),
                            "the visible ore was not broken: " + breaks + " " + BaritoneRefusals.of(s.bot.getUUID()));
                    require(context, s.world.getBlockState(hiddenOre).is(Blocks.DIAMOND_ORE), "the buried ore was touched");
                    require(context, breaks.stream().noneMatch(edit -> edit.pos().equals(hiddenOre)), "the buried ore was broken");
                    s.requireLogged("mine_start", 1);
                    s.requireLogged("mine_complete", 1);
                } finally {
                    s.finish();
                }
            } else if (ticks[0] > 500) {
                s.done = true;
                String where = s.bot.position().toString();
                s.finish();
                require(context, false, "mining the visible ore did not finish: bot at " + where);
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------------

    private interface Filler {
        void place(ServerLevel world, BlockPos at);
    }

    private static void placeBed(ServerLevel world, BlockPos foot, Direction facing) {
        BlockState base = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, facing);
        world.setBlock(foot, base.setValue(BedBlock.PART, BedPart.FOOT), Block.UPDATE_ALL);
        world.setBlock(foot.relative(facing), base.setValue(BedBlock.PART, BedPart.HEAD), Block.UPDATE_ALL);
    }

    private static BlockHitResult top(BlockPos support) {
        return new BlockHitResult(Vec3.atCenterOf(support).add(0.0D, 0.5D, 0.0D), Direction.UP, support, false);
    }

    private static void expectRefused(GameTestHelper context, Small s, ServerPlayerController controller, BlockHitResult hit, String reason) {
        InteractionResult result = controller.processRightClickBlock(s.bot, s.world, InteractionHand.MAIN_HAND, hit);
        require(context, !result.consumesAction(), "a click on " + hit.getBlockPos() + " went through: " + result);
        List<BaritoneRefusals.Refusal> all = BaritoneRefusals.of(s.bot.getUUID(), BaritoneRefusals.Op.PLACE);
        require(context, !all.isEmpty() && reason.equals(all.get(all.size() - 1).reason()),
                "the click on " + hit.getBlockPos() + " was refused for " + (all.isEmpty() ? "nothing" : all.get(all.size() - 1).reason()) + ", expected " + reason);
    }

    private static void requireIntact(GameTestHelper context, Course c, Map<BlockPos, BlockState> intact) {
        for (Map.Entry<BlockPos, BlockState> entry : intact.entrySet()) {
            require(context, c.world.getBlockState(entry.getKey()).equals(entry.getValue()),
                    "the protected block at " + entry.getKey() + " changed from " + entry.getValue() + " to " + c.world.getBlockState(entry.getKey()));
        }
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        BaritoneServerGameTests.require(context, condition, message);
    }

    /** Observation as the rules see it, for the test setup's own sanity checks. */
    private static final class Sight {
        static boolean sees(AIPlayerEntity bot, BlockPos pos) {
            return io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlockCellFace(bot, pos)
                    && (io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlock(bot, pos)
                    || io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, pos));
        }
    }

    /** A 15x15 open platform with one bot, its floor at feet-1; the small tests run side by side in their own structure cells. */
    private static final class Small {
        final GameTestHelper context;
        final ServerLevel world;
        final BlockPos feet;
        final String name;
        final AIPlayerEntity bot;
        final IBaritone baritone;
        boolean done;

        private Small(GameTestHelper context, String name, BlockPos feet, AIPlayerEntity bot, IBaritone baritone) {
            this.context = context;
            this.world = context.getLevel();
            this.name = name;
            this.feet = feet;
            this.bot = bot;
            this.baritone = baritone;
        }

        static Small begin(GameTestHelper context, String name, int radius) {
            ServerLevel world = context.getLevel();
            BlockPos feet = context.absolutePos(new BlockPos(6, SLAB_BASE_Y, 6));
            int reach = Math.max(radius, 4);
            for (int dx = -reach; dx <= reach + 4; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    world.setBlock(feet.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    for (int dy = 0; dy <= 4; dy++) {
                        world.setBlock(feet.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
            AIPlayerEntity bot = BaritoneServerGameTests.spawn(context, name, feet);
            return new Small(context, name, feet, bot, BaritoneRegistry.INSTANCE.get(bot));
        }

        BlockPos at(int dx, int dy, int dz) {
            return feet.offset(dx, dy, dz);
        }

        ServerPlayerController controller() {
            return (ServerPlayerController) baritone.getPlayerContext().playerController();
        }

        /**
         * Gives a direct-controller assertion the same narrow, ray-derived action authority as
         * a real admitted route.  It deliberately starts no Baritone movement: these tests are
         * exercising the controller's policy ordering, not path execution.
         */
        void observeActionTarget(BlockPos target) {
            NavRoute route = new NavRoute(NavRoute.Shape.NEAR, target, 0, NavRoute.Options.WALK_ONLY,
                    "gametest_" + name + "_action", bot.getServer().getTickCount());
            ObservedNavigationFence prior = BaritoneRegistry.INSTANCE.observationMemory(bot);
            long generation = BaritoneRegistry.INSTANCE.observationFence(bot).generation() + 1L;
            ObservedNavigationFence.Capture capture = ObservedNavigationFence.admit(bot, route, prior, generation);
            if (!capture.accepted()) {
                throw new IllegalStateException("fixture action target was not visibly admissible: "
                        + target + " reason=" + capture.failure());
            }
            BaritoneRegistry.INSTANCE.setObservationFence(bot, capture.fence(), route);
        }

        void giveHand(ItemStack stack) {
            bot.getInventory().setItem(0, stack);
            bot.getInventory().setSelectedSlot(0);
        }

        int count(net.minecraft.world.item.Item item) {
            int total = 0;
            for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
                if (stack.is(item)) {
                    total += stack.getCount();
                }
            }
            return total;
        }

        Map<BlockPos, BlockState> remember(List<BlockPos> cells) {
            Map<BlockPos, BlockState> states = new java.util.HashMap<>();
            for (BlockPos cell : cells) {
                states.put(cell.immutable(), world.getBlockState(cell));
            }
            return states;
        }

        void finish() {
            AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
            if (!done) {
                done = true;
            }
            context.succeed();
        }

        /** The bot's log has at least {@code count} lines of the event (only where the log writer runs; asynchronous, so polled). */
        void requireLogged(String event, int count) {
            logLines(context, name, event, count);
        }
    }

    private static void logLines(GameTestHelper context, String name, String event, int count) {
        if (!BotLogWriter.INSTANCE.isStarted() || BotLogWriter.INSTANCE.baseDir() == null) {
            System.out.println("BARITONE_LOG skipped: the log writer is not running in this test server");
            return;
        }
        Path file = BotLogWriter.INSTANCE.baseDir().resolve("by-bot").resolve(name + ".log");
        long found = 0;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                if (Files.exists(file)) {
                    found = Files.readAllLines(file).stream().filter(line -> line.contains(event)).count();
                }
            } catch (IOException ignored) {
                found = 0;
            }
            if (found >= count) {
                break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.out.println("BARITONE_LOG " + name + " event=" + event + " lines=" + found + " expected>=" + count + " file=" + file);
        require(context, found >= count, "the bot log has " + found + " of " + count + " expected '" + event + "' lines (" + file + ")");
    }

    /** A sealed course (bedrock ring, 4 high) at its own height, with one bot and its Baritone instance. */
    private static final class Course {
        final GameTestHelper context;
        final ServerLevel world;
        final BlockPos feet;
        final String name;
        final int fromX;
        final int toX;
        final int halfZ;
        final AIPlayerEntity bot;
        final IBaritone baritone;
        private final java.util.Map<BlockPos, BlockState> before = new java.util.HashMap<>();
        final List<PathEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        private Course(GameTestHelper context, String name, BlockPos feet, int fromX, int toX, int halfZ, AIPlayerEntity bot, IBaritone baritone) {
            this.context = context;
            this.world = context.getLevel();
            this.name = name;
            this.feet = feet;
            this.fromX = fromX;
            this.toX = toX;
            this.halfZ = halfZ;
            this.bot = bot;
            this.baritone = baritone;
            baritone.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
                @Override
                public void onPathEvent(PathEvent event) {
                    events.add(event);
                }
            });
        }

        static Course begin(GameTestHelper context, String name, int slab, int fromX, int toX, int halfZ) {
            ServerLevel world = context.getLevel();
            BlockPos feet = context.absolutePos(new BlockPos(8, SLAB_BASE_Y + SLAB_STEP * slab, 8));
            for (int dx = fromX - 1; dx <= toX + 1; dx++) {
                for (int dz = -halfZ - 1; dz <= halfZ + 1; dz++) {
                    boolean ring = dx == fromX - 1 || dx == toX + 1 || dz == -halfZ - 1 || dz == halfZ + 1;
                    for (int dy = -1; dy <= 4; dy++) {
                        BlockState state;
                        if (ring && dy <= 3) {
                            state = Blocks.BEDROCK.defaultBlockState();
                        } else if (!ring && dy == -1) {
                            state = Blocks.STONE.defaultBlockState();
                        } else {
                            state = Blocks.AIR.defaultBlockState();
                        }
                        world.setBlock(feet.offset(dx, dy, dz), state, Block.UPDATE_CLIENTS);
                    }
                }
            }
            AIPlayerEntity bot = BaritoneServerGameTests.spawn(context, name, feet);
            return new Course(context, name, feet, fromX, toX, halfZ, bot, BaritoneRegistry.INSTANCE.get(bot));
        }

        void set(int dx, int dy, int dz, Block block) {
            world.setBlock(feet.offset(dx, dy, dz), block.defaultBlockState(), Block.UPDATE_ALL);
        }

        void fill(int dx, int dz, Block block, int fromDy, int toDy) {
            for (int dy = fromDy; dy <= toDy; dy++) {
                set(dx, dy, dz, block);
            }
        }

        Map<BlockPos, BlockState> remember(List<BlockPos> cells) {
            Map<BlockPos, BlockState> states = new java.util.HashMap<>();
            for (BlockPos cell : cells) {
                states.put(cell.immutable(), world.getBlockState(cell));
            }
            return states;
        }

        void giveTools() {
            bot.getInventory().setItem(1, new ItemStack(Items.IRON_AXE));
            bot.getInventory().setItem(2, new ItemStack(Items.IRON_PICKAXE));
            bot.getInventory().setSelectedSlot(1);
        }

        void moveBot(int dx, int dz) {
            bot.teleportTo(world, feet.getX() + dx + 0.5D, feet.getY(), feet.getZ() + dz + 0.5D, java.util.Set.of(), 0.0F, 0.0F, true);
            bot.setOnGround(true);
        }

        void snapshot() {
            before.clear();
            forEachCell(pos -> before.put(pos, world.getBlockState(pos)));
        }

        void forEachCell(java.util.function.Consumer<BlockPos> action) {
            for (int dx = fromX - 1; dx <= toX + 1; dx++) {
                for (int dz = -halfZ - 1; dz <= halfZ + 1; dz++) {
                    for (int dy = -1; dy <= 4; dy++) {
                        action.accept(feet.offset(dx, dy, dz));
                    }
                }
            }
        }

        /**
         * Waits until Baritone lets go of the bot, then runs {@code check} and removes the bot. With {@code mustFinish} false a
         * Baritone that is still busy after {@code limit} ticks is cancelled and checked anyway (an unreachable goal may keep
         * a search or a partial path going).
         */
        void await(int limit, boolean mustFinish, java.util.function.Consumer<Run> check) {
            Run run = new Run(this);
            context.onEachTick(() -> {
                if (run.done) {
                    return;
                }
                run.sample();
                boolean busy = BaritoneRegistry.INSTANCE.isBusy(bot);
                boolean timedOut = run.ticks > limit;
                if ((run.ticks > 2 && !busy) || (timedOut && !mustFinish)) {
                    run.done = true;
                    run.endedByItself = !busy;
                    if (busy) {
                        baritone.getPathingBehavior().cancelEverything();
                    }
                    try {
                        System.out.println("BARITONE_SURVIVAL " + name + " finished after " + run.ticks + " ticks at " + describe());
                        check.accept(run);
                    } finally {
                        AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
                    }
                    context.succeed();
                } else if (timedOut) {
                    run.done = true;
                    String where = describe();
                    AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
                    require(context, false, name + ": still busy after " + limit + " ticks, " + where + " events=" + events);
                }
            });
        }

        String describe() {
            return "offset=(" + String.format("%.2f,%.2f,%.2f", bot.getX() - feet.getX(), bot.getY() - feet.getY(), bot.getZ() - feet.getZ())
                    + ") goal=" + baritone.getPathingBehavior().getGoal() + " events=" + events;
        }
    }

    private static final class Run {
        final Course c;
        int ticks;
        double maxZ = Double.NEGATIVE_INFINITY;
        final List<Vec3> trail = new ArrayList<>();
        final List<PathEvent> events;
        boolean done;
        boolean endedByItself;

        Run(Course c) {
            this.c = c;
            this.events = c.events;
        }

        void sample() {
            ticks++;
            Vec3 pos = c.bot.position();
            trail.add(pos);
            maxZ = Math.max(maxZ, pos.z);
            if (ticks % 20 == 0) {
                System.out.println("BARITONE_SURVIVAL_TRACE " + c.name + " t=" + ticks + " " + c.describe());
            }
        }

        void requireNear(BlockPos goal, double horizontal, double vertical, String what) {
            Vec3 target = Vec3.atBottomCenterOf(goal);
            double dx = c.bot.getX() - target.x;
            double dz = c.bot.getZ() - target.z;
            double dy = c.bot.getY() - target.y;
            require(c.context, Math.sqrt(dx * dx + dz * dz) <= horizontal && Math.abs(dy) <= vertical,
                    what + ": the bot is not at the goal " + goal + ": " + c.describe());
        }

        void requireEditsExplainTheWorldDiff() {
            java.util.Set<BlockPos> recorded = new java.util.HashSet<>();
            BaritoneEdits.of(c.bot.getUUID()).forEach(edit -> recorded.add(edit.pos().immutable()));
            List<String> unexplained = new ArrayList<>();
            c.forEachCell(pos -> {
                BlockState was = c.before.get(pos);
                if (was != null && !was.is(c.world.getBlockState(pos).getBlock()) && !recorded.contains(pos)) {
                    unexplained.add(pos.toShortString() + " " + was.getBlock() + " -> " + c.world.getBlockState(pos).getBlock());
                }
            });
            require(c.context, unexplained.isEmpty(), "blocks changed without a ledger entry: " + unexplained);
        }
    }
}

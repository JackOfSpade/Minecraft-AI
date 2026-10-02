package io.github.zoyluo.minecraftai.task;

import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * The Baritone navigation obstacle courses (docs/NAVIGATION_COURSES.md): each one is a geometry (built into a
 * {@link BaritoneEngineArena}), a start cell for the bot(s), and one or more target cells (legs). Cells are relative to the
 * arena origin (x east, y up, z south). Every course runs on a fresh strict-survival Baritone arena through
 * {@link NavigationCourseRun}.
 */
final class NavigationCourses {
    enum Mode {
        /** A {@code FollowTask} on a stand-in player that stands at the target cell (or walks a loop). */
        FOLLOW,
        /** A {@code MoveTask} to an exact goal cell. */
        MOVE
    }

    enum Expect {
        /** Baritone is expected to get there. */
        REACH,
        /** There is no dry/safe way: the bot must hold on the near side (and say so, when {@code notice} is set). */
        HOLD,
        /** Reaching or holding are both fine; only the safety invariants count. */
        EITHER
    }

    static final class Course {
        final String id;
        final String title;
        final Mode mode;
        final int layer;
        final int halfX;
        final int halfZ;
        final int floorDepth;
        final int budget;
        final Consumer<BaritoneEngineArena> build;
        int[][] starts = {{-8, 0, 0}};
        int[][] legs = {{8, 0, 0}};
        Expect expect = Expect.REACH;
        boolean pickaxe;
        /** The geometry has a walkable way, so no block may be broken (navigate first, break as a last resort). */
        boolean noBreak;
        /** HOLD courses: the follower must tell the player there is no route. */
        boolean notice;
        /** The followed player walks a loop (see {@link NavigationCourseRun}). */
        boolean moving;

        private Course(String id, String title, Mode mode, int layer, int halfX, int halfZ, int floorDepth, int budget,
                Consumer<BaritoneEngineArena> build) {
            this.id = id;
            this.title = title;
            this.mode = mode;
            this.layer = layer;
            this.halfX = halfX;
            this.halfZ = halfZ;
            this.floorDepth = floorDepth;
            this.budget = budget;
            this.build = build;
        }

        Course starts(int[]... starts) {
            this.starts = starts;
            return this;
        }

        Course legs(int[]... legs) {
            this.legs = legs;
            return this;
        }

        Course expect(Expect expect) {
            this.expect = expect;
            return this;
        }

        Course pickaxe() {
            this.pickaxe = true;
            return this;
        }

        Course noBreak() {
            this.noBreak = true;
            return this;
        }

        Course notice() {
            this.notice = true;
            return this;
        }

        Course moving() {
            this.moving = true;
            return this;
        }
    }

    private NavigationCourses() {
    }

    private static int[] c(int x, int y, int z) {
        return new int[] {x, y, z};
    }

    private static Course of(String id, String title, Mode mode, int layer, int halfX, int halfZ, int floorDepth, int budget,
            Consumer<BaritoneEngineArena> build) {
        return new Course(id, title, mode, layer, halfX, halfZ, floorDepth, budget, build);
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** (1) A two-high stone wall across the course with a gap at the +z end: the walkable detour is about 12 blocks longer. */
    static final Course WALL_DETOUR = of("wall", "wall needing a detour (no tool)", Mode.FOLLOW, 0, 14, 9, 4, 600,
            NavigationCourses::wallDetour).starts(c(-7, 0, 0)).legs(c(7, 0, 0)).noBreak();

    /** (1b) The same wall, but the bot carries a stone pickaxe: breaking is cheap, walking around is still the rule. */
    static final Course WALL_DETOUR_PICKAXE = of("wallpick", "wall needing a detour (stone pickaxe in the hotbar)", Mode.FOLLOW, 1, 14, 9, 4, 600,
            NavigationCourses::wallDetour).starts(c(-7, 0, 0)).legs(c(7, 0, 0)).pickaxe().noBreak();

    /** (2) A two-thick stone wall over the whole width and up to the ceiling: only digging gets through (stone pickaxe). */
    static final Course SEALED_WALL = of("sealed", "sealed stone wall, breaking is the last resort", Mode.FOLLOW, 2, 14, 9, 4, 1500, arena -> {
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = -9; dz <= 9; dz++) {
                arena.fill(dx, dz, Blocks.STONE, 0, BaritoneEngineArena.CEILING);
            }
        }
    }).starts(c(-7, 0, 0)).legs(c(7, 0, 0)).pickaxe();

    /** (3) A one-block step up, then a second one (the target stands two blocks above the start). */
    static final Course STEPS = of("steps", "1-high step then 2-high step", Mode.FOLLOW, 3, 14, 6, 4, 500, arena -> {
        for (int dx = -2; dx <= 1; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                arena.set(dx, 0, dz, Blocks.STONE);
            }
        }
        for (int dx = 2; dx <= 12; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                arena.set(dx, 0, dz, Blocks.STONE);
                arena.set(dx, 1, dz, Blocks.STONE);
            }
        }
    }).starts(c(-8, 0, 0)).legs(c(8, 2, 0)).noBreak();

    /** (4) A three-wide, five-deep crevasse across the course with a walkable end at +z (a fall in it is 5 blocks and cannot be climbed). */
    static final Course PIT_CREVASSE = of("pit", "crevasse to walk around", Mode.FOLLOW, 4, 14, 9, 6, 600, arena -> {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -9; dz <= 5; dz++) {
                arena.fill(dx, dz, Blocks.AIR, -5, -1);
            }
        }
    }).starts(c(-7, 0, 0)).legs(c(7, 0, 0)).noBreak();

    /** (5) A four-step stair-block staircase up to a plateau, and another one down: MoveTask to the plateau, then to the far floor. */
    static final Course STAIRCASE = of("stairs", "staircase ascent then descent", Mode.MOVE, 5, 14, 6, 4, 800, arena -> {
        for (int i = 0; i < 4; i++) {
            column(arena, -8 + i, i, Direction.EAST);
        }
        for (int dx = -4; dx <= 0; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                arena.fill(dx, dz, Blocks.STONE, 0, 3);
            }
        }
        for (int i = 0; i < 4; i++) {
            column(arena, 1 + i, 3 - i, Direction.WEST);
        }
    }).starts(c(-12, 0, 0)).legs(c(-2, 4, 0), c(9, 0, 0)).noBreak();

    /** (6) A lake (one deep) across most of the width with a dry way round at +z: the follower must not enter the water. */
    static final Course LAKE_DRY_PATH = of("lakedry", "lake with a dry path around", Mode.FOLLOW, 6, 14, 9, 4, 800, arena -> {
        for (int dx = 0; dx <= 4; dx++) {
            for (int dz = -9; dz <= 3; dz++) {
                arena.set(dx, -1, dz, Blocks.WATER);
            }
        }
    }).starts(c(-9, 0, 0)).legs(c(9, 0, 0)).noBreak();

    /** (7) A lake over the whole width: no dry way, the follower holds at the bank and says so once (follow_no_dry_route). */
    static final Course LAKE_NO_DRY_PATH = of("lakenone", "lake with NO dry path", Mode.FOLLOW, 7, 14, 9, 4, 800, arena -> {
        for (int dx = 0; dx <= 3; dx++) {
            for (int dz = -9; dz <= 9; dz++) {
                arena.set(dx, -1, dz, Blocks.WATER);
            }
        }
    }).starts(c(-10, 0, 0)).legs(c(9, 0, 0)).expect(Expect.HOLD).notice().noBreak();

    /** (8) A lava moat over the whole width with a one-wide stone bridge, four cells off the straight line. */
    static final Course LAVA_MOAT_BRIDGE = of("lava", "lava moat with a bridge", Mode.FOLLOW, 8, 14, 7, 4, 700, arena -> {
        for (int dx = 0; dx <= 3; dx++) {
            for (int dz = -7; dz <= 7; dz++) {
                if (dz != 4) {
                    arena.set(dx, -1, dz, Blocks.LAVA);
                }
            }
        }
    }).starts(c(-9, 0, 0)).legs(c(9, 0, 0)).noBreak();

    /** (9) A field of two-high cacti on sand (checkerboard, not against the bedrock ring: a cactus next to a solid block pops off) across the line, with a four-wide clear lane at +z. */
    static final Course CACTUS_FIELD = of("cactus", "cactus field", Mode.FOLLOW, 9, 14, 9, 4, 600, arena -> {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -9; dz <= 5; dz++) {
                arena.set(dx, -1, dz, Blocks.SAND);
            }
        }
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -8; dz <= 5; dz++) {
                if (((dx + dz) & 1) == 0) {
                    arena.set(dx, 0, dz, Blocks.CACTUS);
                    arena.set(dx, 1, dz, Blocks.CACTUS);
                }
            }
        }
    }).starts(c(-8, 0, 0)).legs(c(8, 0, 0)).noBreak();

    /** (10a) The target stands at the bottom of a basin whose rim is a vertical 3-block drop: vanilla-safe, no damage. */
    static final Course CLIFF_SAFE_DROP = of("cliff3", "cliff, safe 3-block drop", Mode.FOLLOW, 10, 14, 9, 8, 500, arena -> basin(arena, 3))
            .starts(c(-6, 0, 0)).legs(c(8, -3, 0)).noBreak();

    /** (10b) Same with a 6-block drop: falling is not acceptable, the bot must hold, find another way down, or dig safely. */
    static final Course CLIFF_UNSAFE_DROP = of("cliff6", "cliff, unsafe 6-block drop", Mode.FOLLOW, 11, 14, 9, 8, 500, arena -> basin(arena, 6))
            .starts(c(-6, 0, 0)).legs(c(8, -6, 0)).expect(Expect.EITHER);

    /** (11) A closed house (oak planks, roof, one wooden door in the west wall): the target is inside, then it is outside again. */
    static final Course HOUSE_DOOR = of("house", "house with a wooden door, in and out", Mode.FOLLOW, 12, 14, 9, 4, 900, arena -> {
        for (int dx = 0; dx <= 6; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                boolean wall = dx == 0 || dx == 6 || dz == -3 || dz == 3;
                for (int dy = 0; dy <= 2; dy++) {
                    arena.set(dx, dy, dz, wall ? Blocks.OAK_PLANKS : Blocks.AIR);
                }
                arena.set(dx, 3, dz, Blocks.OAK_PLANKS);
            }
        }
        arena.set(0, 0, 0, Blocks.AIR);
        arena.set(0, 1, 0, Blocks.AIR);
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
        arena.world.setBlock(arena.cell(0, 0, 0), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
        arena.world.setBlock(arena.cell(0, 1, 0), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        arena.set(3, 2, 2, Blocks.LIGHT);
        arena.set(3, 2, -2, Blocks.LIGHT);
    }).starts(c(-8, 0, 0)).legs(c(4, 0, 0), c(-8, 0, 6)).noBreak();

    /** (12) A fence (not jumpable) across the whole width with one closed fence gate off the straight line. */
    static final Course FENCE_GATE = of("gate", "fence with a fence gate", Mode.FOLLOW, 13, 14, 7, 4, 600, arena -> {
        for (int dz = -7; dz <= 7; dz++) {
            arena.set(0, 0, dz, Blocks.OAK_FENCE);
        }
        arena.world.setBlock(arena.cell(0, 0, 3), Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.EAST),
                Block.UPDATE_ALL);
    }).starts(c(-7, 0, -3)).legs(c(7, 0, -3)).noBreak();

    /** (13) A four-high stone tower whose only way up is a ladder on its west face; MoveTask to the top. */
    static final Course LADDER_SHAFT = of("ladder", "ladder shaft up", Mode.MOVE, 14, 14, 6, 4, 500, arena -> {
        for (int dx = 3; dx <= 7; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                arena.fill(dx, dz, Blocks.STONE, 0, 3);
            }
        }
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST);
        for (int dy = 0; dy <= 3; dy++) {
            arena.world.setBlock(arena.cell(2, dy, 0), ladder, Block.UPDATE_ALL);
        }
    }).starts(c(-6, 0, 0)).legs(c(5, 4, 0)).noBreak();

    /** (14) Oak trunks every four blocks with a persistent canopy (low leaves next to some trunks): the target is on the far side. */
    static final Course FOREST = of("forest", "tree canopy / forest", Mode.FOLLOW, 15, 16, 10, 4, 900, arena -> {
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int tx = -12; tx <= 12; tx += 4) {
            for (int tz = -8; tz <= 8; tz += 4) {
                for (int dy = 0; dy <= 3; dy++) {
                    arena.set(tx, dy, tz, Blocks.OAK_LOG);
                }
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                        boolean trunk = dx == 0 && dz == 0;
                        if (!trunk) {
                            leaves(arena, tx + dx, 3, tz + dz, leaves);
                        }
                        if (!corner) {
                            leaves(arena, tx + dx, 4, tz + dz, leaves);
                        }
                        if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                            leaves(arena, tx + dx, 5, tz + dz, leaves);
                            if (!trunk) {
                                leaves(arena, tx + dx, 2, tz + dz, leaves);
                            }
                        }
                    }
                }
                if (tx == -4 || tx == 4) {
                    leaves(arena, tx + 1, 1, tz, leaves);
                }
            }
        }
        for (int lx = -14; lx <= 14; lx += 4) {
            for (int lz = -6; lz <= 6; lz += 4) {
                arena.set(lx, 2, lz, Blocks.LIGHT);
            }
        }
    }).starts(c(-14, 0, 0)).legs(c(14, 0, 2)).noBreak();

    /** (15) The followed player walks a rectangular loop (0.2 blocks per tick) around a stone pillar; the follower must keep up. */
    static final Course MOVING_TARGET = of("moving", "moving target on a loop", Mode.FOLLOW, 16, 14, 8, 4, 800, arena -> {
        for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                arena.fill(dx, dz, Blocks.STONE, 0, 2);
            }
        }
    }).starts(c(-12, 0, 0)).legs(c(-8, 0, -4)).moving();

    /** (16) Two bots follow the same player across a wall with a one-wide gap: both must get there. */
    static final Course TWO_BOTS = of("twobots", "two bots following the same player", Mode.FOLLOW, 17, 14, 9, 4, 800, arena -> {
        for (int dz = -9; dz <= 9; dz++) {
            if (dz != 0) {
                arena.fill(0, dz, Blocks.STONE, 0, 1);
            }
        }
    }).starts(c(-8, 0, -1), c(-8, 0, 1)).legs(c(8, 0, 0)).noBreak();

    /** (17) 76 blocks along x (five chunk borders) with a wall, a pillar and a pit on the line. */
    static final Course LONG_PATH = of("long", "long path across chunk boundaries", Mode.MOVE, 18, 40, 5, 4, 2000, arena -> {
        for (int dz = -5; dz <= 2; dz++) {
            arena.fill(-10, dz, Blocks.STONE, 0, 1);
        }
        for (int dx = 10; dx <= 11; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                arena.fill(dx, dz, Blocks.STONE, 0, 2);
            }
        }
        for (int dz = -5; dz <= 3; dz++) {
            arena.fill(25, dz, Blocks.STONE, 0, 1);
        }
        for (int dx = 32; dx <= 33; dx++) {
            for (int dz = -5; dz <= 2; dz++) {
                arena.fill(dx, dz, Blocks.AIR, -3, -1);
            }
        }
    }).starts(c(-38, 0, 0)).legs(c(38, 0, 0)).noBreak();

    // ---------------------------------------------------------------------------------------------------------------

    private static void wallDetour(BaritoneEngineArena arena) {
        for (int dz = -9; dz <= 5; dz++) {
            arena.fill(0, dz, Blocks.STONE, 0, 1);
        }
    }

    private static void leaves(BaritoneEngineArena arena, int dx, int dy, int dz, BlockState leaves) {
        arena.world.setBlock(arena.cell(dx, dy, dz), leaves, Block.UPDATE_CLIENTS);
    }

    /** One stair column (three wide) at x with its stair block at height dy, solid stone below it. */
    private static void column(BaritoneEngineArena arena, int dx, int dy, Direction ascends) {
        BlockState stairs = Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, ascends);
        for (int dz = -1; dz <= 1; dz++) {
            if (dy > 0) {
                arena.fill(dx, dz, Blocks.STONE, 0, dy - 1);
            }
            arena.world.setBlock(arena.cell(dx, dy, dz), stairs, Block.UPDATE_ALL);
        }
    }

    /** The whole east part of the course (x >= 3) lowered by {@code drop} blocks: a vertical cliff at x = 3. */
    private static void basin(BaritoneEngineArena arena, int drop) {
        for (int dx = 3; dx <= 14; dx++) {
            for (int dz = -9; dz <= 9; dz++) {
                arena.fill(dx, dz, Blocks.AIR, -drop, -1);
            }
        }
    }

    static BlockPos cell(BaritoneEngineArena arena, int[] c) {
        return arena.cell(c[0], c[1], c[2]);
    }
}

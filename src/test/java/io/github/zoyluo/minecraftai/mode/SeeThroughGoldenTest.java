package io.github.zoyluo.minecraftai.mode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.zoyluo.minecraftai.testsupport.VanillaRegistries;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins the see-through verdict of every vanilla block. {@code golden_see_through_ids.txt} is the census of the 1166 blocks of
 * 1.21.11 ({@code all_block_ids.txt}): a block a later vanilla adds, or one whose class, tag or collision changes, fails here
 * until somebody decides on purpose which side of the line it belongs, instead of bots quietly seeing through (or being blinded
 * by) it.
 */
class SeeThroughGoldenTest {
    @BeforeAll
    static void bootstrapRegistries() {
        VanillaRegistries.ensureReady();
    }

    @Test
    void theTagsTheRulesRelyOnAreBound() {
        assertTrue(Blocks.OAK_LEAVES.defaultBlockState().is(BlockTags.LEAVES), "tags were not bound: every tag rule would be silently false");
        assertTrue(Blocks.OAK_FENCE.defaultBlockState().is(BlockTags.FENCES));
        assertTrue(Blocks.NETHER_BRICK_FENCE.defaultBlockState().is(BlockTags.FENCES));
    }

    @Test
    void predicateMatchesTheGoldenListOverTheWholeRegistry() throws IOException {
        Set<String> golden = ids("golden_see_through_ids.txt");
        Set<String> known = ids("all_block_ids.txt");
        assertEquals(1166, known.size(), "the census this list was made from");
        assertTrue(known.containsAll(golden), "a golden id that is no block of the census");
        Set<String> actual = new TreeSet<>();
        Set<String> unseen = new TreeSet<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            if (!"minecraft".equals(id.getNamespace()) || !known.contains(id.getPath())) {
                unseen.add(id.toString());
            }
            if (SeeThrough.block(block)) {
                actual.add(id.getPath());
            }
        }
        assertEquals(Set.of(), unseen, "a block the census never saw needs a conscious see-through decision (add it to both files)");
        assertEquals(golden, actual, "the predicate drifted from the golden list");
        assertEquals(248, actual.size());
    }

    @Test
    void theFamiliesTheUserNamedAreSeeThroughAndTheSolidBuildingBlocksAreNot() {
        for (Block block : BuiltInRegistries.BLOCK) {
            BlockState state = block.defaultBlockState();
            if (state.is(BlockTags.LEAVES) || state.is(BlockTags.FENCES) || state.is(BlockTags.FENCE_GATES)) {
                assertTrue(SeeThrough.block(block), block + " is leaves, a fence or a gate");
            }
            if (state.is(BlockTags.WALLS) || state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS)
                    || state.is(BlockTags.SLABS) || state.is(BlockTags.STAIRS)) {
                assertFalse(SeeThrough.block(block), block + " is a wall, door, trapdoor, slab or stairs");
            }
        }
        for (Block opaque : List.of(Blocks.STONE, Blocks.CHEST, Blocks.WHITE_BED, Blocks.BLUE_ICE, Blocks.PACKED_ICE, Blocks.BEACON,
                Blocks.VAULT, Blocks.AZALEA, Blocks.LILY_PAD, Blocks.SEA_PICKLE, Blocks.OAK_SIGN, Blocks.STONE_BUTTON,
                Blocks.STONE_PRESSURE_PLATE, Blocks.LEVER, Blocks.POWDER_SNOW, Blocks.END_PORTAL)) {
            assertFalse(SeeThrough.block(opaque), opaque + " is visible although nothing, or only a part, collides with it");
        }
        for (Block seeThrough : List.of(Blocks.GLASS, Blocks.WHITE_STAINED_GLASS, Blocks.TINTED_GLASS, Blocks.GLASS_PANE, Blocks.IRON_BARS,
                Blocks.ICE, Blocks.SLIME_BLOCK, Blocks.HONEY_BLOCK, Blocks.IRON_CHAIN, Blocks.SCAFFOLDING, Blocks.LADDER,
                Blocks.WHEAT, Blocks.SHORT_GRASS, Blocks.TORCH, Blocks.COBWEB, Blocks.VINE, Blocks.RAIL, Blocks.AIR)) {
            assertTrue(SeeThrough.block(seeThrough), seeThrough + " is a block a player looks through");
        }
    }

    @Test
    void waterIsSeeThroughAndLavaIsNot() {
        assertTrue(SeeThrough.block(Blocks.WATER));
        assertFalse(SeeThrough.block(Blocks.LAVA));
        assertTrue(SeeThrough.cell(Blocks.WATER.defaultBlockState()));
        assertFalse(SeeThrough.cell(Blocks.LAVA.defaultBlockState()));
        assertFalse(SeeThrough.cell(Blocks.LAVA_CAULDRON.defaultBlockState()));
    }

    @Test
    void waterloggingNeverChangesTheVerdictOfTheHostBlock() {
        assertTrue(SeeThrough.cell(Blocks.OAK_FENCE.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)));
        assertFalse(SeeThrough.cell(Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)));
        assertFalse(SeeThrough.cell(Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)));
    }

    @Test
    void aTagReloadIsFollowedOnceTheCacheIsInvalidated() {
        Block leaves = Blocks.OAK_LEAVES;
        try {
            assertTrue(SeeThrough.block(leaves));
            VanillaRegistries.reloadBlockTags("leaves");
            assertTrue(SeeThrough.block(leaves), "cached: the verdict is kept until the invalidation the tag-load hook triggers");
            SeeThrough.invalidate();
            assertFalse(SeeThrough.block(leaves), "leaves are only leaves by tag");
        } finally {
            VanillaRegistries.reloadBlockTags();
            SeeThrough.invalidate();
        }
        assertTrue(SeeThrough.block(leaves));
    }

    private static Set<String> ids(String resource) throws IOException {
        try (InputStream in = SeeThroughGoldenTest.class.getResourceAsStream(resource)) {
            assertTrue(in != null, resource + " is missing from the test resources");
            Set<String> out = new TreeSet<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (!line.isBlank()) {
                    out.add(line.trim());
                }
            }
            return out;
        }
    }
}

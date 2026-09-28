package io.github.zoyluo.minecraftai.mining;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for {@code pickaxeItem(int)}'s NETHERITE mapping (miningcore-bug-002): the method must
 * check {@code tier >= NETHERITE} before {@code tier >= DIAMOND}, otherwise a NETHERITE tier falls into the
 * DIAMOND branch and {@code requiredPickaxeItem}/{@code requiredPickaxeItemId} would recommend a diamond
 * pickaxe for a block that actually needs a netherite one. No caller reaches this branch with tier=NETHERITE
 * today (this is a latent/defensive fix), and {@code Items.NETHERITE_PICKAXE} needs a Minecraft bootstrap
 * to compare against at runtime, so this reads the production source as text instead, like the other
 * source-contract tests (see {@code goal/MiningPlanningSourceContractTest} for the analogous check on
 * {@code GoalPlanner.pickaxeForTier}, which already had this ordering right).
 */
class ToolTierSourceContractTest {
    private static final Path FILE = Path.of("src/main/java/io/github/zoyluo/minecraftai/mining/ToolTier.java");

    private static String source() throws IOException {
        return Files.readString(FILE);
    }

    @Test
    void pickaxeItemChecksNetheriteBeforeDiamondBeforeIronBeforeWood() throws IOException {
        String source = source();
        int pickaxeItem = source.indexOf("private static Item pickaxeItem(int tier)");
        assertTrue(pickaxeItem >= 0, "pickaxeItem(int) must exist");

        int netherite = source.indexOf("if (tier >= NETHERITE)", pickaxeItem);
        int diamond = source.indexOf("if (tier >= DIAMOND)", pickaxeItem);
        int iron = source.indexOf("if (tier >= IRON)", pickaxeItem);
        int wood = source.indexOf("if (tier >= WOOD)", pickaxeItem);
        assertTrue(netherite > pickaxeItem && diamond > netherite && iron > diamond && wood > iron,
                "pickaxeItem must check NETHERITE > DIAMOND > IRON > WOOD, in that order, so a NETHERITE "
                        + "tier is never caught by the DIAMOND branch first");

        int netheritePick = source.indexOf("return Items.NETHERITE_PICKAXE;", netherite);
        int diamondPick = source.indexOf("return Items.DIAMOND_PICKAXE;", diamond);
        int ironPick = source.indexOf("return Items.IRON_PICKAXE;", iron);
        assertTrue(netheritePick > netherite && netheritePick < diamond,
                "the NETHERITE branch must return Items.NETHERITE_PICKAXE before the DIAMOND check");
        assertTrue(diamondPick > diamond && diamondPick < iron,
                "the DIAMOND branch must return Items.DIAMOND_PICKAXE before the IRON check");
        assertTrue(ironPick > iron, "the IRON branch must return Items.IRON_PICKAXE");
    }
}

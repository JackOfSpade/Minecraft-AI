package dev.spawnbotswrapper.inhabitants.command;

import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.Category;
import dev.spawnbotswrapper.inhabitants.engine.ForceMode;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandArgsTest {

    @Test
    void radiusIsClampedToTheSupportedRange() {
        assertEquals(1, CommandArgs.radius(0));
        assertEquals(1, CommandArgs.radius(-5));
        assertEquals(1, CommandArgs.radius(Integer.MIN_VALUE));
        assertEquals(1, CommandArgs.radius(1));
        assertEquals(8, CommandArgs.radius(8));
        assertEquals(64, CommandArgs.radius(64));
        assertEquals(64, CommandArgs.radius(65));
        assertEquals(64, CommandArgs.radius(Integer.MAX_VALUE));
        assertTrue(CommandArgs.DEFAULT_NEARBY_RADIUS >= 1 && CommandArgs.DEFAULT_NEARBY_RADIUS <= CommandArgs.MAX_RADIUS);
    }

    @Test
    void chunkOfFloorsCorrectlyForNegativeCoordinates() {
        assertEquals(0, CommandArgs.chunkOf(0));
        assertEquals(0, CommandArgs.chunkOf(15.99));
        assertEquals(1, CommandArgs.chunkOf(16));
        assertEquals(6, CommandArgs.chunkOf(100.5));
        assertEquals(-1, CommandArgs.chunkOf(-0.1));
        assertEquals(-1, CommandArgs.chunkOf(-1));
        assertEquals(-1, CommandArgs.chunkOf(-16));
        assertEquals(-2, CommandArgs.chunkOf(-16.01));
        assertEquals(-2, CommandArgs.chunkOf(-17));
        assertEquals(1_874_999, CommandArgs.chunkOf(29_999_984.0));
    }

    @Test
    void theChunkLimitCoversTheWholeWorldBorder() {
        assertTrue(CommandArgs.chunkOf(29_999_984.0) <= CommandArgs.MAX_CHUNK);
        assertTrue(CommandArgs.chunkOf(-29_999_984.0) >= -CommandArgs.MAX_CHUNK);
    }

    @Test
    void categoriesParseLeniently() {
        assertEquals(Optional.of(Category.PER_BOT_RANDOMIZABLE), CommandArgs.parseCategory("per_bot_randomizable"));
        assertEquals(Optional.of(Category.PER_BOT_RANDOMIZABLE), CommandArgs.parseCategory("PER_BOT_RANDOMIZABLE"));
        assertEquals(Optional.of(Category.PER_BOT_RANDOMIZABLE), CommandArgs.parseCategory("per-bot-randomizable"));
        assertEquals(Optional.of(Category.PER_BOT_RANDOMIZABLE), CommandArgs.parseCategory("per bot randomizable"));
        assertEquals(Optional.of(Category.PER_BOT_RANDOMIZABLE), CommandArgs.parseCategory("per"));
        assertEquals(Optional.of(Category.GLOBAL_ONLY), CommandArgs.parseCategory("  Global_Only  "));
        assertEquals(Optional.of(Category.GLOBAL_ONLY), CommandArgs.parseCategory("global"));
        assertEquals(Optional.of(Category.ADMIN_OPERATIONAL), CommandArgs.parseCategory("admin"));
        assertEquals(Optional.of(Category.UNSUPPORTED), CommandArgs.parseCategory("unsup"));
    }

    @Test
    void everyCategoryIsReachableByItsOwnLowerCaseName() {
        for (Category c : Category.values()) {
            assertEquals(Optional.of(c), CommandArgs.parseCategory(c.name().toLowerCase()), c.name());
        }
    }

    @Test
    void unknownBlankAndNullCategoriesAreRejected() {
        assertEquals(Optional.empty(), CommandArgs.parseCategory(null));
        assertEquals(Optional.empty(), CommandArgs.parseCategory(""));
        assertEquals(Optional.empty(), CommandArgs.parseCategory("   "));
        assertEquals(Optional.empty(), CommandArgs.parseCategory("nonsense"));
        assertEquals(Optional.empty(), CommandArgs.parseCategory("per_bot_randomizable_extra"));
    }

    @Test
    void modeLiteralsAreTheLowerCaseEnumNamesAndDistinct() {
        assertEquals("roll", CommandArgs.modeLiteral(ForceMode.ROLL));
        assertEquals("occupied", CommandArgs.modeLiteral(ForceMode.OCCUPIED));
        assertEquals("abandoned", CommandArgs.modeLiteral(ForceMode.ABANDONED));
        assertEquals(ForceMode.values().length,
                java.util.Arrays.stream(ForceMode.values()).map(CommandArgs::modeLiteral).distinct().count());
    }

    @Test
    void rootAndAliasAreTheDocumentedNames() {
        assertEquals("inhabitants", CommandArgs.ROOT);
        assertEquals("pvpbot_inhabitants", CommandArgs.ALIAS);
    }
}

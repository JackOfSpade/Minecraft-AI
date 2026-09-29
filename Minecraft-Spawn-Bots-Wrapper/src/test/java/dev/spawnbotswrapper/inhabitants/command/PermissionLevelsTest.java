package dev.spawnbotswrapper.inhabitants.command;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.Permissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The 0-4 config level onto Minecraft 1.21.11's permission constants (levels 0..4 = ALL..OWNERS). */
class PermissionLevelsTest {

    private static Supplier<CommandServices> servicesOf(FakeServices fake) {
        CommandServices s = fake.services();
        return () -> s;
    }

    @Test
    void eachLevelMapsToTheMatchingDefaultPermission() {
        assertNull(PermissionLevels.permissionFor(0), "level 0 means everybody: no permission is asked for");
        assertSame(Permissions.COMMANDS_MODERATOR, PermissionLevels.permissionFor(1));
        assertSame(Permissions.COMMANDS_GAMEMASTER, PermissionLevels.permissionFor(2));
        assertSame(Permissions.COMMANDS_ADMIN, PermissionLevels.permissionFor(3));
        assertSame(Permissions.COMMANDS_OWNER, PermissionLevels.permissionFor(4));
    }

    @Test
    void outOfRangeLevelsClampInsteadOfFailing() {
        assertNull(PermissionLevels.permissionFor(-1));
        assertNull(PermissionLevels.permissionFor(Integer.MIN_VALUE));
        assertSame(Permissions.COMMANDS_OWNER, PermissionLevels.permissionFor(5));
        assertSame(Permissions.COMMANDS_OWNER, PermissionLevels.permissionFor(Integer.MAX_VALUE));
        assertEquals(0, PermissionLevels.clamp(-7));
        assertEquals(4, PermissionLevels.clamp(40));
        assertEquals(3, PermissionLevels.clamp(3));
    }

    @Test
    void aSourceMayUseACommandExactlyWhenItsLevelIsAtLeastTheRequiredOne() {
        for (int have = 0; have <= 4; have++) {
            for (int need = 0; need <= 4; need++) {
                assertEquals(have >= need, PermissionLevels.allows(TestSources.at(have), need),
                        "source level " + have + " against required " + need);
            }
        }
    }

    @Test
    void theVanillaConstantsBehaveAsTheMappingAssumes() {
        // guards the assumption that GAMEMASTERS (2) is what vanilla /give and /tp need
        assertTrue(LevelBasedPermissionSet.GAMEMASTER.hasPermission(Permissions.COMMANDS_GAMEMASTER));
        assertFalse(LevelBasedPermissionSet.GAMEMASTER.hasPermission(Permissions.COMMANDS_ADMIN));
        assertTrue(LevelBasedPermissionSet.OWNER.hasPermission(Permissions.COMMANDS_MODERATOR));
        assertFalse(LevelBasedPermissionSet.MODERATOR.hasPermission(Permissions.COMMANDS_GAMEMASTER));
    }

    @Test
    void aSourceWithNoPermissionsPassesOnlyLevelZero() {
        assertTrue(PermissionLevels.allows(PermissionSet.NO_PERMISSIONS, 0));
        for (int need = 1; need <= 4; need++) {
            assertFalse(PermissionLevels.allows(PermissionSet.NO_PERMISSIONS, need), "level " + need);
        }
        assertTrue(PermissionLevels.allows(null, 0));
        assertFalse(PermissionLevels.allows(null, 1));
        assertTrue(PermissionLevels.allows(PermissionSet.ALL_PERMISSIONS, 4));
    }

    // ---------------------------------------------------------------- the level that applies right now

    @Test
    void theFallbackIsTheConfigDefault() {
        assertEquals(new InhabitantsConfig().commandPermissionLevel, PermissionLevels.FALLBACK);
        assertEquals(2, PermissionLevels.FALLBACK);
    }

    @Test
    void currentLevelReadsTheLiveConfigEveryTime() {
        FakeServices fake = new FakeServices();
        Supplier<CommandServices> services = servicesOf(fake);

        fake.config.commandPermissionLevel = 3;
        assertEquals(3, PermissionLevels.currentLevel(services));
        fake.config.commandPermissionLevel = 0;
        assertEquals(0, PermissionLevels.currentLevel(services));
        fake.config.commandPermissionLevel = 9;
        assertEquals(4, PermissionLevels.currentLevel(services), "an unvalidated config value is clamped");
        fake.config.commandPermissionLevel = -2;
        assertEquals(0, PermissionLevels.currentLevel(services));
    }

    @Test
    void notReadyServicesFallBackToTheDefaultLevel() {
        assertEquals(PermissionLevels.FALLBACK, PermissionLevels.currentLevel(() -> null));
    }

    @Test
    void aBrokenConfigNeverEscapesTheRequirement() {
        FakeServices fake = new FakeServices();
        CommandServices broken = new CommandServices(() -> {
            throw new IllegalStateException("config exploded");
        }, fake.population, fake.engine, fake.adapter, fake.locator, () -> java.util.List.of(), "1");
        assertEquals(PermissionLevels.FALLBACK, PermissionLevels.currentLevel(() -> broken));

        CommandServices nullConfig = new CommandServices(() -> null, fake.population, fake.engine, fake.adapter,
                fake.locator, () -> java.util.List.of(), "1");
        assertEquals(PermissionLevels.FALLBACK, PermissionLevels.currentLevel(() -> nullConfig));

        assertEquals(PermissionLevels.FALLBACK, PermissionLevels.currentLevel(() -> {
            throw new IllegalStateException("services exploded");
        }));

        Predicate<CommandSourceStack> requirement = PermissionLevels.requirement(() -> {
            throw new IllegalStateException("services exploded");
        });
        assertTrue(requirement.test(TestSources.level(2)));
        assertFalse(requirement.test(TestSources.level(1)));
    }

    @Test
    void disablingTheCommandsRefusesEverybodyIncludingOwners() {
        FakeServices fake = new FakeServices();
        fake.config.debugCommands = false;
        assertEquals(PermissionLevels.NOBODY, PermissionLevels.currentLevel(servicesOf(fake)));
        Predicate<CommandSourceStack> requirement = PermissionLevels.requirement(servicesOf(fake));
        for (int level = 0; level <= 4; level++) {
            assertFalse(requirement.test(TestSources.level(level)), "level " + level);
        }
    }

    // ---------------------------------------------------------------- the requirement

    @Test
    void theRequirementTracksTheConfigLevelAcrossReloads() {
        FakeServices fake = new FakeServices();
        Predicate<CommandSourceStack> requirement = PermissionLevels.requirement(servicesOf(fake));
        CommandSourceStack moderator = TestSources.level(1);
        CommandSourceStack gamemaster = TestSources.level(2);
        CommandSourceStack admin = TestSources.level(3);
        CommandSourceStack everyone = TestSources.level(0);

        fake.config.commandPermissionLevel = 2;
        assertFalse(requirement.test(everyone));
        assertFalse(requirement.test(moderator));
        assertTrue(requirement.test(gamemaster));
        assertTrue(requirement.test(admin));

        fake.config.commandPermissionLevel = 3;
        assertFalse(requirement.test(gamemaster));
        assertTrue(requirement.test(admin));

        fake.config.commandPermissionLevel = 0;
        assertTrue(requirement.test(everyone));

        fake.config.commandPermissionLevel = 4;
        assertFalse(requirement.test(admin));
        assertTrue(requirement.test(TestSources.level(4)));
    }

    @Test
    void whileTheServicesAreNotReadyTheDefaultLevelApplies() {
        Predicate<CommandSourceStack> requirement = PermissionLevels.requirement(() -> null);
        assertFalse(requirement.test(TestSources.level(0)));
        assertFalse(requirement.test(TestSources.level(1)));
        assertTrue(requirement.test(TestSources.level(2)));
    }
}

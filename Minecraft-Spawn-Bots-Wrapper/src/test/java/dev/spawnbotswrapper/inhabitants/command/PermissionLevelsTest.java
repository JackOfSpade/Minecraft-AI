package dev.spawnbotswrapper.inhabitants.command;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import net.minecraft.command.DefaultPermissions;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.command.permission.PermissionPredicate;
import net.minecraft.server.command.ServerCommandSource;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;
import java.util.function.Supplier;

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
        assertSame(DefaultPermissions.MODERATORS, PermissionLevels.permissionFor(1));
        assertSame(DefaultPermissions.GAMEMASTERS, PermissionLevels.permissionFor(2));
        assertSame(DefaultPermissions.ADMINS, PermissionLevels.permissionFor(3));
        assertSame(DefaultPermissions.OWNERS, PermissionLevels.permissionFor(4));
    }

    @Test
    void outOfRangeLevelsClampInsteadOfFailing() {
        assertNull(PermissionLevels.permissionFor(-1));
        assertNull(PermissionLevels.permissionFor(Integer.MIN_VALUE));
        assertSame(DefaultPermissions.OWNERS, PermissionLevels.permissionFor(5));
        assertSame(DefaultPermissions.OWNERS, PermissionLevels.permissionFor(Integer.MAX_VALUE));
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
        assertTrue(LeveledPermissionPredicate.GAMEMASTERS.hasPermission(DefaultPermissions.GAMEMASTERS));
        assertFalse(LeveledPermissionPredicate.GAMEMASTERS.hasPermission(DefaultPermissions.ADMINS));
        assertTrue(LeveledPermissionPredicate.OWNERS.hasPermission(DefaultPermissions.MODERATORS));
        assertFalse(LeveledPermissionPredicate.MODERATORS.hasPermission(DefaultPermissions.GAMEMASTERS));
    }

    @Test
    void aSourceWithNoPermissionsPassesOnlyLevelZero() {
        assertTrue(PermissionLevels.allows(PermissionPredicate.NONE, 0));
        for (int need = 1; need <= 4; need++) {
            assertFalse(PermissionLevels.allows(PermissionPredicate.NONE, need), "level " + need);
        }
        assertTrue(PermissionLevels.allows(null, 0));
        assertFalse(PermissionLevels.allows(null, 1));
        assertTrue(PermissionLevels.allows(PermissionPredicate.ALL, 4));
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

        Predicate<ServerCommandSource> requirement = PermissionLevels.requirement(() -> {
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
        Predicate<ServerCommandSource> requirement = PermissionLevels.requirement(servicesOf(fake));
        for (int level = 0; level <= 4; level++) {
            assertFalse(requirement.test(TestSources.level(level)), "level " + level);
        }
    }

    // ---------------------------------------------------------------- the requirement

    @Test
    void theRequirementTracksTheConfigLevelAcrossReloads() {
        FakeServices fake = new FakeServices();
        Predicate<ServerCommandSource> requirement = PermissionLevels.requirement(servicesOf(fake));
        ServerCommandSource moderator = TestSources.level(1);
        ServerCommandSource gamemaster = TestSources.level(2);
        ServerCommandSource admin = TestSources.level(3);
        ServerCommandSource everyone = TestSources.level(0);

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
        Predicate<ServerCommandSource> requirement = PermissionLevels.requirement(() -> null);
        assertFalse(requirement.test(TestSources.level(0)));
        assertFalse(requirement.test(TestSources.level(1)));
        assertTrue(requirement.test(TestSources.level(2)));
    }
}

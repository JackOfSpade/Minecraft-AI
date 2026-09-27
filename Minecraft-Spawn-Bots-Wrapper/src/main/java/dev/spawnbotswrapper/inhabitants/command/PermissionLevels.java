package dev.spawnbotswrapper.inhabitants.command;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import net.minecraft.command.DefaultPermissions;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionPredicate;
import net.minecraft.server.command.ServerCommandSource;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Maps the configured op level ({@code commandPermissionLevel}, 0-4) onto Minecraft 1.21.11's permission
 * system, which no longer has {@code hasPermissionLevel(int)}: a node's requirement now asks the source's
 * {@link PermissionPredicate} whether it holds a {@link Permission}, and the four op levels are the
 * constants of {@link DefaultPermissions}.
 * <pre>
 *   0 ALL         everybody (the vanilla "always pass" check: no permission is asked for)
 *   1 MODERATORS  DefaultPermissions.MODERATORS
 *   2 GAMEMASTERS DefaultPermissions.GAMEMASTERS   (what /give, /summon, /tp need)
 *   3 ADMINS      DefaultPermissions.ADMINS
 *   4 OWNERS      DefaultPermissions.OWNERS
 * </pre>
 * The level is read from the CURRENT config on every evaluation, so a reload takes effect at once. The
 * requirement is evaluated by Minecraft for every node whenever the command tree is sent to a player or
 * a command is parsed, so it must never throw: any failure reading the config falls back to the default level.
 */
final class PermissionLevels {
    static final int MIN = 0;
    static final int MAX = 4;
    /** Returned by {@link #currentLevel} when the commands are switched off: no source passes. */
    static final int NOBODY = -1;

    /** The level used while the services are not ready and when reading the config fails. */
    static final int FALLBACK = new InhabitantsConfig().commandPermissionLevel;

    /** Index = op level; slot 0 has no permission because level 0 lets everyone in. */
    private static final Permission[] BY_LEVEL = {
            null,
            DefaultPermissions.MODERATORS,
            DefaultPermissions.GAMEMASTERS,
            DefaultPermissions.ADMINS,
            DefaultPermissions.OWNERS
    };

    private PermissionLevels() {
    }

    static int clamp(int level) {
        return Math.max(MIN, Math.min(MAX, level));
    }

    /** The permission a source needs for {@code level} (clamped), or null when everybody may pass. */
    static Permission permissionFor(int level) {
        return BY_LEVEL[clamp(level)];
    }

    static boolean allows(PermissionPredicate permissions, int level) {
        Permission needed = permissionFor(level);
        return needed == null || (permissions != null && permissions.hasPermission(needed));
    }

    /**
     * The op level that applies right now: the current config's, or {@link #FALLBACK} when the services
     * are not ready or anything goes wrong reading them. A config with {@code debugCommands=false} yields
     * {@link #NOBODY} (the tree stays registered but every node refuses everyone, exactly as
     * if it had not been registered) - except {@code reload} itself, see {@link #reloadLevel}: without that
     * exception, turning {@code debugCommands} off would be a one-way trap with no in-game way back.
     */
    static int currentLevel(Supplier<CommandServices> services) {
        try {
            CommandServices s = services.get();
            if (s == null) {
                return FALLBACK;
            }
            InhabitantsConfig c = s.config().get();
            if (c == null) {
                return FALLBACK;
            }
            return c.debugCommands ? clamp(c.commandPermissionLevel) : NOBODY;
        } catch (RuntimeException e) {
            return FALLBACK;
        }
    }

    /**
     * The op level for {@code /inhabitants reload} specifically: the same {@code commandPermissionLevel} as
     * every other node, but never {@link #NOBODY} - {@code debugCommands=false} does not apply to it. An
     * operator at the required level can always reload the config (and so flip {@code debugCommands} back
     * on) without a server restart; a non-operator still cannot reach it.
     */
    static int reloadLevel(Supplier<CommandServices> services) {
        try {
            CommandServices s = services.get();
            if (s == null) {
                return FALLBACK;
            }
            InhabitantsConfig c = s.config().get();
            return c == null ? FALLBACK : clamp(c.commandPermissionLevel);
        } catch (RuntimeException e) {
            return FALLBACK;
        }
    }

    /** The requirement placed on every ordinary command node; evaluated per use against the current config. */
    static Predicate<ServerCommandSource> requirement(Supplier<CommandServices> services) {
        return source -> {
            int level = currentLevel(services);
            return level != NOBODY && allows(source.getPermissions(), level);
        };
    }

    /** The requirement placed on {@code reload} alone: {@link #reloadLevel}, immune to {@code debugCommands}. */
    static Predicate<ServerCommandSource> reloadRequirement(Supplier<CommandServices> services) {
        return source -> allows(source.getPermissions(), reloadLevel(services));
    }
}

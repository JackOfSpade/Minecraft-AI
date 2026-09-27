package dev.spawnbotswrapper.inhabitants.adapter;

/**
 * Every upstream name the addon knows, in one place: mod ids, class names, command literals. These are
 * plain strings on purpose. The addon has no compile-time dependency on PvP BOT or HeroBot; it resolves
 * classes by name at run time and reports what it could not find.
 * <p>
 * Classes that must NEVER appear here: PvP BOT's name generator (its static initialiser performs blocking
 * HTTP on the calling thread), its faction registry and HeroBot's movement helper (both bind to world state
 * in static initialisers before the server is ready). The addon has no use for any of them.
 */
final class UpstreamNames {

    private UpstreamNames() {
    }

    // ---------------------------------------------------------------- mods
    static final String MOD_PVP_BOT = "pvp_bot";
    static final String MOD_HEROBOT = "herobot";
    /** PvP BOT declares a hard conflict with Carpet; if both are present something is badly wrong. */
    static final String MOD_CARPET = "carpet";

    // ---------------------------------------------------------------- classes
    static final String PACKAGE = "org.stepan1411.pvp_bot";
    static final String CLASS_BOT_MANAGER = PACKAGE + ".bot.BotManager";
    static final String CLASS_BOT_SETTINGS = PACKAGE + ".bot.BotSettings";
    static final String CLASS_BOT_PATH = PACKAGE + ".bot.BotPath";
    static final String CLASS_BOT_NAVIGATION = PACKAGE + ".bot.BotNavigation";
    static final String CLASS_MAIN = PACKAGE + ".Pvp_bot";
    static final String FIELD_MOD_ID = "MOD_ID";

    /**
     * Simple name of HeroBot's fake-player class. Its package differs between HeroBot generations, its
     * simple name does not, and it is never subclassed.
     */
    static final String BOT_ENTITY_SIMPLE_NAME = "BotPlayer";

    /**
     * Every package HeroBot's fake-player class has shipped under, checked alongside the simple name so an
     * unrelated mod's own {@code BotPlayer} class is never mistaken for it (real bot-entity checks gate
     * destructive operations such as removal).
     */
    static final String[] BOT_ENTITY_PACKAGES = {
            "hero.bane.herobot.bot.",              // HeroBot <= 2.0.1
            "hero.bane.herobot.mod.common.bot.",   // HeroBot >= the 2026-07-30 rework
    };

    // ---------------------------------------------------------------- commands
    static final String COMMAND_PVPBOT = "pvpbot";
    static final String SUBCOMMAND_SPAWN = "spawn";
    static final String SUBCOMMAND_REMOVE = "remove";
    static final String COMMAND_PLAYERSPAWN = "playerspawn";
    static final String COMMAND_PLAYER = "player";
    static final String COMMAND_HEROBOT = "herobot";

    /** The literal the tier-3 fallback dispatches, for a name already validated by {@link NameRules}. */
    static String spawnCommand(String name) {
        return COMMAND_PVPBOT + " " + SUBCOMMAND_SPAWN + " " + name;
    }

    static String removeCommand(String name) {
        return COMMAND_PVPBOT + " " + SUBCOMMAND_REMOVE + " " + name;
    }

    /**
     * {@code true} when a player entity's class is HeroBot's fake player: the simple name must match AND the
     * class must live in one of the packages HeroBot has actually shipped it under ({@link
     * #BOT_ENTITY_PACKAGES}). The simple-name check alone would also match an unrelated mod's own class
     * that happens to be named {@code BotPlayer}, which matters here because a false positive would make
     * the addon treat a real player as an addon-owned bot for removal.
     */
    static boolean isBotClassName(String className) {
        if (className == null) {
            return false;
        }
        int end = className.length();
        int start = Math.max(className.lastIndexOf('.'), className.lastIndexOf('$')) + 1;
        boolean simpleNameMatches = end - start == BOT_ENTITY_SIMPLE_NAME.length()
                && className.startsWith(BOT_ENTITY_SIMPLE_NAME, start);
        if (!simpleNameMatches) {
            return false;
        }
        for (String pkg : BOT_ENTITY_PACKAGES) {
            // startsWith alone would also accept an extra nested package under HeroBot's own namespace
            // (e.g. "hero.bane.herobot.bot.evil.BotPlayer"); requiring the simple name to start exactly
            // where the known package ends closes that gap too.
            if (pkg.length() == start && className.startsWith(pkg)) {
                return true;
            }
        }
        return false;
    }

    /** Simple (unqualified) name of a fully qualified class name. */
    static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }
}

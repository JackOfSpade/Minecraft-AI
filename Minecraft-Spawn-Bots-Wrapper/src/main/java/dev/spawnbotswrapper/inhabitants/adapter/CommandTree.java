package dev.spawnbotswrapper.inhabitants.adapter;

import com.mojang.brigadier.tree.CommandNode;

/**
 * Which of the command literals the integration depends on are registered. PvP BOT drives HeroBot by
 * dispatching commands, so the command tree, not any Java class, is the real contract between the two.
 * A registered literal is found even when its {@code requires} predicate would refuse some sources, which
 * is what we want: we run every command as the console.
 *
 * @param known         false when the dispatcher could not be inspected (then no other flag means anything)
 * @param pvpbot        root literal {@code pvpbot}
 * @param pvpbotSpawn   {@code pvpbot spawn}
 * @param pvpbotRemove  {@code pvpbot remove}
 * @param playerspawn   root literal {@code playerspawn}: without it no bot can ever appear
 * @param player        root literal {@code player}: how PvP BOT kills and steers bots
 * @param herobot       root literal {@code herobot}: how PvP BOT pushes its leave-on-death rule
 */
record CommandTree(boolean known, boolean pvpbot, boolean pvpbotSpawn, boolean pvpbotRemove,
                   boolean playerspawn, boolean player, boolean herobot) {

    static final CommandTree UNKNOWN = new CommandTree(false, false, false, false, false, false, false);

    /** Reads the literals from a dispatcher root; a null root or any failure yields {@link #UNKNOWN}. */
    static CommandTree scan(CommandNode<?> root) {
        if (root == null) {
            return UNKNOWN;
        }
        try {
            CommandNode<?> pvpbotNode = root.getChild(UpstreamNames.COMMAND_PVPBOT);
            boolean spawn = pvpbotNode != null && pvpbotNode.getChild(UpstreamNames.SUBCOMMAND_SPAWN) != null;
            boolean remove = pvpbotNode != null && pvpbotNode.getChild(UpstreamNames.SUBCOMMAND_REMOVE) != null;
            return new CommandTree(true, pvpbotNode != null, spawn, remove,
                    root.getChild(UpstreamNames.COMMAND_PLAYERSPAWN) != null,
                    root.getChild(UpstreamNames.COMMAND_PLAYER) != null,
                    root.getChild(UpstreamNames.COMMAND_HEROBOT) != null);
        } catch (RuntimeException e) {
            return UNKNOWN;
        }
    }
}

package dev.spawnbotswrapper.inhabitants.adapter;

/** The spawn path in use; {@link #label()} is what {@code Status.spawnTier()} reports. */
enum SpawnTier {
    /** T1: {@code BotManager.spawnBot(server, name, source, Vec3)}. */
    CLASS_POS("CLASS(pos)"),
    /** T2: {@code BotManager.spawnBot(server, name, source)} with the position carried by the source. */
    CLASS("CLASS"),
    /** T3: {@code /pvpbot spawn <name>} dispatched with the prepared console-derived source. */
    COMMAND("COMMAND"),
    NONE("NONE");

    private final String label;

    SpawnTier(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}

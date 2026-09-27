package dev.spawnbotswrapper.inhabitants.adapter;

/**
 * How the adapter turns an upstream class NAME into a {@link Class}. A seam so the probe logic can be
 * tested against fake upstream classes (and against absent or broken ones) without a running game.
 */
@FunctionalInterface
interface ClassLocator {

    /** Never initialises the class: PvP BOT classes have static initialisers with side effects (config I/O). */
    Class<?> load(String className) throws ClassNotFoundException;

    static ClassLocator forLoader(ClassLoader loader) {
        return name -> Class.forName(name, false, loader);
    }
}

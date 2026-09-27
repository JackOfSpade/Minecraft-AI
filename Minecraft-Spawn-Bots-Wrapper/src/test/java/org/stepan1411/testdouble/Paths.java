package org.stepan1411.testdouble;

import net.minecraft.util.math.Vec3d;

/** Test-only variants of the upstream path registry. */
public final class Paths {

    private Paths() {
    }

    /** The walk-type setter was removed, so a complete path can no longer be built. */
    public static class NoWalkType {
        public static boolean createPath(String name) {
            return true;
        }

        public static boolean deletePath(String name) {
            return true;
        }

        public static boolean addPoint(String pathName, Vec3d point) {
            return true;
        }

        public static boolean setLoop(String pathName, boolean loop) {
            return true;
        }

        public static boolean setAttack(String pathName, boolean attack) {
            return true;
        }

        public static boolean startFollowing(String botName, String pathName) {
            return true;
        }

        public static boolean stopFollowing(String botName) {
            return true;
        }

        public static Object getPath(String name) {
            return null;
        }
    }

    /** A path registry without the optional follower check. */
    public static class NoIsFollowing {
        public static boolean createPath(String name) {
            return org.stepan1411.pvp_bot.bot.BotPath.createPath(name);
        }

        public static boolean deletePath(String name) {
            return org.stepan1411.pvp_bot.bot.BotPath.deletePath(name);
        }

        public static boolean addPoint(String pathName, Vec3d point) {
            return org.stepan1411.pvp_bot.bot.BotPath.addPoint(pathName, point);
        }

        public static boolean setLoop(String pathName, boolean loop) {
            return org.stepan1411.pvp_bot.bot.BotPath.setLoop(pathName, loop);
        }

        public static boolean setAttack(String pathName, boolean attack) {
            return org.stepan1411.pvp_bot.bot.BotPath.setAttack(pathName, attack);
        }

        public static boolean setWalkType(String pathName, String walkType) {
            return org.stepan1411.pvp_bot.bot.BotPath.setWalkType(pathName, walkType);
        }

        public static boolean startFollowing(String botName, String pathName) {
            return org.stepan1411.pvp_bot.bot.BotPath.startFollowing(botName, pathName);
        }

        public static boolean stopFollowing(String botName) {
            return org.stepan1411.pvp_bot.bot.BotPath.stopFollowing(botName);
        }

        public static Object getPath(String name) {
            return org.stepan1411.pvp_bot.bot.BotPath.getPath(name);
        }
    }
}

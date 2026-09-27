package org.stepan1411.testdouble;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Test-only variants of the upstream bot manager, each deviating from the contract in exactly one way, so
 * the probe can be shown to name the precise member that is wrong. A test maps the upstream class name to
 * one of these through its class locator; every variant is otherwise complete and healthy.
 */
public final class Managers {

    private Managers() {
    }

    /** Without the position overload of spawn. */
    public static class NoPos {
        public static boolean spawnBot(MinecraftServer server, String name, ServerCommandSource source) {
            boolean ok = Recorder.guard("spawn3", name);
            if (ok) {
                Recorder.LISTED.add(name);
            }
            return ok;
        }

        public static Set<String> getAllBots() {
            Recorder.getAllBotsCalls++;
            return new HashSet<>(Recorder.LISTED);
        }

        public static int getBotCount() {
            return Recorder.LISTED.size();
        }

        public static boolean removeBot(MinecraftServer server, String name, ServerCommandSource source) {
            Recorder.guard("removeBot", name);
            return Recorder.LISTED.remove(name);
        }
    }

    /** With ONLY the position overload of spawn. */
    public static class OnlyPos {
        public static boolean spawnBot(MinecraftServer server, String name, ServerCommandSource source, Vec3d pos) {
            boolean ok = Recorder.guard("spawn4", name);
            if (ok) {
                Recorder.LISTED.add(name);
            }
            return ok;
        }

        public static Set<String> getAllBots() {
            Recorder.getAllBotsCalls++;
            return new HashSet<>(Recorder.LISTED);
        }

        public static int getBotCount() {
            return Recorder.LISTED.size();
        }

        public static boolean removeBot(MinecraftServer server, String name, ServerCommandSource source) {
            Recorder.guard("removeBot", name);
            return Recorder.LISTED.remove(name);
        }
    }

    /** Lists and removes, but has no spawn method of either shape. */
    public static class NoSpawn {
        public static Set<String> getAllBots() {
            Recorder.getAllBotsCalls++;
            return new HashSet<>(Recorder.LISTED);
        }

        public static int getBotCount() {
            return Recorder.LISTED.size();
        }

        public static boolean removeBot(MinecraftServer server, String name, ServerCommandSource source) {
            Recorder.guard("removeBot", name);
            return Recorder.LISTED.remove(name);
        }
    }

    /** Without removeBot. */
    public static class NoRemove {
        public static boolean spawnBot(MinecraftServer server, String name, ServerCommandSource source) {
            boolean ok = Recorder.guard("spawn3", name);
            if (ok) {
                Recorder.LISTED.add(name);
            }
            return ok;
        }

        public static boolean spawnBot(MinecraftServer server, String name, ServerCommandSource source, Vec3d pos) {
            boolean ok = Recorder.guard("spawn4", name);
            if (ok) {
                Recorder.LISTED.add(name);
            }
            return ok;
        }

        public static Set<String> getAllBots() {
            Recorder.getAllBotsCalls++;
            return new HashSet<>(Recorder.LISTED);
        }

        public static int getBotCount() {
            return Recorder.LISTED.size();
        }
    }

    /** Without the bot list accessor. */
    public static class NoGetAllBots {
        public static boolean spawnBot(MinecraftServer server, String name, ServerCommandSource source) {
            return true;
        }

        public static boolean spawnBot(MinecraftServer server, String name, ServerCommandSource source, Vec3d pos) {
            return true;
        }

        public static int getBotCount() {
            return 0;
        }

        public static boolean removeBot(MinecraftServer server, String name, ServerCommandSource source) {
            return true;
        }
    }

    /** Without the bot counter. */
    public static class NoGetBotCount {
        public static boolean spawnBot(MinecraftServer server, String name, ServerCommandSource source) {
            return true;
        }

        public static boolean spawnBot(MinecraftServer server, String name, ServerCommandSource source, Vec3d pos) {
            return true;
        }

        public static Set<String> getAllBots() {
            return new HashSet<>();
        }

        public static boolean removeBot(MinecraftServer server, String name, ServerCommandSource source) {
            return true;
        }
    }

    /** The bot list accessor returns a List instead of a Set. */
    public static class WrongReturn {
        public static boolean spawnBot(MinecraftServer server, String name, ServerCommandSource source) {
            return true;
        }

        public static boolean spawnBot(MinecraftServer server, String name, ServerCommandSource source, Vec3d pos) {
            return true;
        }

        public static List<String> getAllBots() {
            return List.of();
        }

        public static int getBotCount() {
            return 0;
        }

        public static boolean removeBot(MinecraftServer server, String name, ServerCommandSource source) {
            return true;
        }
    }

    /** The bot counter returns long instead of int. */
    public static class WrongPrimitiveReturn {
        public static boolean spawnBot(MinecraftServer server, String name, ServerCommandSource source) {
            return true;
        }

        public static boolean spawnBot(MinecraftServer server, String name, ServerCommandSource source, Vec3d pos) {
            return true;
        }

        public static Set<String> getAllBots() {
            return new HashSet<>();
        }

        public static long getBotCount() {
            return 0;
        }

        public static boolean removeBot(MinecraftServer server, String name, ServerCommandSource source) {
            return true;
        }
    }

    /** removeBot became an instance method. */
    public static class NonStaticRemove {
        public static boolean spawnBot(MinecraftServer server, String name, ServerCommandSource source) {
            return true;
        }

        public static boolean spawnBot(MinecraftServer server, String name, ServerCommandSource source, Vec3d pos) {
            return true;
        }

        public static Set<String> getAllBots() {
            return new HashSet<>();
        }

        public static int getBotCount() {
            return 0;
        }

        public boolean removeBot(MinecraftServer server, String name, ServerCommandSource source) {
            return true;
        }
    }

    /** removeBot is no longer public. */
    public static class PrivateRemove {
        public static boolean spawnBot(MinecraftServer server, String name, ServerCommandSource source) {
            return true;
        }

        public static boolean spawnBot(MinecraftServer server, String name, ServerCommandSource source, Vec3d pos) {
            return true;
        }

        public static Set<String> getAllBots() {
            return new HashSet<>();
        }

        public static int getBotCount() {
            return 0;
        }

        static boolean removeBot(MinecraftServer server, String name, ServerCommandSource source) {
            return true;
        }
    }

    /** The class itself is no longer public, so its public methods cannot be invoked from another package. */
    static class HiddenClass {
        public static boolean spawnBot(MinecraftServer server, String name, ServerCommandSource source) {
            return true;
        }

        public static boolean spawnBot(MinecraftServer server, String name, ServerCommandSource source, Vec3d pos) {
            return true;
        }

        public static Set<String> getAllBots() {
            return new HashSet<>();
        }

        public static int getBotCount() {
            return 0;
        }

        public static boolean removeBot(MinecraftServer server, String name, ServerCommandSource source) {
            return true;
        }
    }

    /** Signature drift: the command source parameter of spawn is now a plain Object. */
    public static class WrongParam {
        public static boolean spawnBot(MinecraftServer server, String name, Object source) {
            return true;
        }

        public static boolean spawnBot(MinecraftServer server, String name, Object source, Vec3d pos) {
            return true;
        }

        public static Set<String> getAllBots() {
            return new HashSet<>();
        }

        public static int getBotCount() {
            return 0;
        }

        public static boolean removeBot(MinecraftServer server, String name, ServerCommandSource source) {
            return true;
        }
    }

    /** A complete manager whose class initialiser records that it ran: the probe must never trigger it. */
    public static class CountsInit {
        static {
            Recorder.CALLS.add("clinit:Managers.CountsInit");
        }

        public static boolean spawnBot(MinecraftServer server, String name, ServerCommandSource source) {
            return true;
        }

        public static boolean spawnBot(MinecraftServer server, String name, ServerCommandSource source, Vec3d pos) {
            return true;
        }

        public static Set<String> getAllBots() {
            return new HashSet<>();
        }

        public static int getBotCount() {
            return 0;
        }

        public static boolean removeBot(MinecraftServer server, String name, ServerCommandSource source) {
            return true;
        }
    }

    /** The class initialiser fails, as upstream's could if its config were unreadable. */
    public static class BrokenInit {
        static {
            if (Boolean.parseBoolean("true")) {
                throw new IllegalStateException("static initialiser failed");
            }
        }

        public static Set<String> getAllBots() {
            return new HashSet<>();
        }
    }
}

package org.stepan1411.pvp_bot.bot;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.stepan1411.testdouble.Recorder;

import java.util.HashSet;
import java.util.Set;

/**
 * Test-only fake of the upstream bot manager: the signatures of the reflection contract (R1..R9) and just
 * enough behaviour to be observed (like upstream, a spawn lists the name at once and answers true).
 */
public class BotManager {

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
        Recorder.guard("getAllBots", "");
        return new HashSet<>(Recorder.LISTED);
    }

    public static int getBotCount() {
        return Recorder.LISTED.size();
    }

    public static boolean removeBot(MinecraftServer server, String name, ServerCommandSource source) {
        Recorder.guard("removeBot", name);
        return Recorder.LISTED.remove(name);
    }

    public static ServerPlayerEntity getBot(MinecraftServer server, String name) {
        return null;
    }

    public static void removeAllBots(MinecraftServer server, ServerCommandSource source) {
        Recorder.FORBIDDEN.add("removeAllBots");
    }

    public static void saveBots() {
    }

    public static void updateBotData(MinecraftServer server) {
    }
}

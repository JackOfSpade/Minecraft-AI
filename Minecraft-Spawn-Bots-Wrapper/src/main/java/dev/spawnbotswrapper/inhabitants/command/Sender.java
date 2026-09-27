package dev.spawnbotswrapper.inhabitants.command;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Where a command was run from: the world, its dimension id, and the position. This is the position of
 * the command SOURCE, so it follows {@code /execute in ... positioned ... run} and works from the console
 * (which sits at the world spawn) as well as for players.
 *
 * @param dimensionId {@code namespace:path} of the world's dimension, the same form {@code StructureKey} uses
 */
record Sender(ServerWorld world, String dimensionId, double x, double y, double z) {

    static Sender of(ServerCommandSource source) {
        ServerWorld world = source.getWorld();
        if (world == null) {
            throw new IllegalStateException("the command source has no world");
        }
        Vec3d p = source.getPosition();
        return new Sender(world, world.getRegistryKey().getValue().toString(), p.x, p.y, p.z);
    }

    BlockPos blockPos() {
        return BlockPos.ofFloored(x, y, z);
    }

    int chunkX() {
        return CommandArgs.chunkOf(x);
    }

    int chunkZ() {
        return CommandArgs.chunkOf(z);
    }
}

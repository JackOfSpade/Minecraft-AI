package dev.spawnbotswrapper.inhabitants.command;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Where a command was run from: the world, its dimension id, and the position. This is the position of
 * the command SOURCE, so it follows {@code /execute in ... positioned ... run} and works from the console
 * (which sits at the world spawn) as well as for players.
 *
 * @param dimensionId {@code namespace:path} of the world's dimension, the same form {@code StructureKey} uses
 */
record Sender(ServerLevel world, String dimensionId, double x, double y, double z) {

    static Sender of(CommandSourceStack source) {
        ServerLevel world = source.getLevel();
        if (world == null) {
            throw new IllegalStateException("the command source has no world");
        }
        Vec3 p = source.getPosition();
        return new Sender(world, world.dimension().identifier().toString(), p.x, p.y, p.z);
    }

    BlockPos blockPos() {
        return BlockPos.containing(x, y, z);
    }

    int chunkX() {
        return CommandArgs.chunkOf(x);
    }

    int chunkZ() {
        return CommandArgs.chunkOf(z);
    }
}

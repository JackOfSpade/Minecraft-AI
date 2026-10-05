package dev.spawnbotswrapper.inhabitants.mc;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.gameevent.vibrations.VibrationInfo;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;

/**
 * The server-side behaviour of vanilla's {@link VibrationSystem.Ticker}, without its client-only vibration-particle
 * packets.
 *
 * <p>The vanilla ticker has no {@code User} switch for particles: it unconditionally sends a
 * {@code VibrationParticleOption} when it schedules a vibration. The two bot hearing systems need every other part
 * of that ticker unchanged (the listener's vanilla event selection, physical travel delay, delayed delivery, and
 * {@link VibrationSystem.User#onDataChanged()} calls), but those packets advertise an otherwise private sound path
 * to players. This mirrors the 1.21.11 ticker exactly apart from omitting those packets and clearing a loaded
 * particle-reload request.
 */
final class SilentVibrationTicker {
    private SilentVibrationTicker() {
    }

    static void tick(ServerLevel level, VibrationSystem.Data data, VibrationSystem.User user) {
        if (data.getCurrentVibration() == null) {
            data.getSelectionStrategy().chosenCandidate(level.getGameTime())
                    .ifPresent(vibration -> schedule(data, user, vibration));
        }

        VibrationInfo vibration = data.getCurrentVibration();
        if (vibration == null) {
            return;
        }

        // Vanilla's only work here is to (re)send a VibrationParticleOption. It has no hearing-side effect, so clear
        // a flag restored from data rather than sending the packet now or retrying it on every later tick.
        boolean dataChanged = data.getTravelTimeInTicks() > 0;
        data.setReloadVibrationParticle(false);
        data.decrementTravelTime();
        if (data.getTravelTimeInTicks() <= 0) {
            dataChanged = receive(level, data, user, vibration);
        }
        if (dataChanged) {
            user.onDataChanged();
        }
    }

    private static void schedule(VibrationSystem.Data data, VibrationSystem.User user, VibrationInfo vibration) {
        data.setCurrentVibration(vibration);
        data.setTravelTimeInTicks(user.calculateTravelTimeInTicks(vibration.distance()));
        user.onDataChanged();
        data.getSelectionStrategy().startOver();
    }

    private static boolean receive(ServerLevel level, VibrationSystem.Data data, VibrationSystem.User user,
                                   VibrationInfo vibration) {
        BlockPos source = BlockPos.containing(vibration.pos());
        BlockPos listener = user.getPositionSource().getPosition(level).map(BlockPos::containing).orElse(source);
        if (user.requiresAdjacentChunksToBeTicking() && !areAdjacentChunksTicking(level, listener)) {
            return false;
        }

        user.onReceiveVibration(level, source, vibration.gameEvent(), vibration.getEntity(level).orElse(null),
                vibration.getProjectileOwner(level).orElse(null), VibrationSystem.Listener.distanceBetweenInBlocks(source, listener));
        data.setCurrentVibration(null);
        return true;
    }

    /** Mirrors vanilla's private ticker guard for users that request adjacent chunk ticking. */
    private static boolean areAdjacentChunksTicking(ServerLevel level, BlockPos pos) {
        ChunkPos chunk = new ChunkPos(pos);
        for (int x = chunk.x - 1; x <= chunk.x + 1; x++) {
            for (int z = chunk.z - 1; z <= chunk.z + 1; z++) {
                if (!level.shouldTickBlocksAt(ChunkPos.asLong(x, z)) || level.getChunkSource().getChunkNow(x, z) == null) {
                    return false;
                }
            }
        }
        return true;
    }
}

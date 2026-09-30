package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.engine.WorldGateway;
import dev.spawnbotswrapper.inhabitants.spawn.BlockProbe;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;

/**
 * {@link WorldGateway} over the running server: the overworld seed and a non-loading block view per loaded
 * dimension. A fresh probe is returned on every call because probes memoise block classifications that are
 * only trustworthy for the moment they were made.
 */
public final class McWorldGateway implements WorldGateway {
    private final ServerAccess access;
    private final Function<ServerLevel, BlockProbe> probes;

    public McWorldGateway(ServerAccess access) {
        this(access, McBlockProbe::new);
    }

    McWorldGateway(ServerAccess access, Function<ServerLevel, BlockProbe> probes) {
        this.access = access;
        this.probes = probes;
    }

    @Override
    public long worldSeed() {
        return access.worldSeed();
    }

    @Override
    public BlockProbe probe(String dimensionId) {
        ServerLevel world = access.world(dimensionId);
        return world == null ? null : probes.apply(world);
    }

    @Override
    public dev.spawnbotswrapper.inhabitants.spawn.SpawnSafety.Verdict standing(String dimensionId, double x, double y, double z) {
        BlockProbe probe = probe(dimensionId);
        return dev.spawnbotswrapper.inhabitants.spawn.SpawnSafety.check(probe, x, y, z, false);
    }
}

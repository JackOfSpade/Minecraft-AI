package baritone.utils.schematic.schematica;

import baritone.api.schematic.IStaticSchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Tuple;

import java.util.Optional;

/** Stub for the excluded Schematica client-mod bridge: Schematica is never present on a server. */
public final class SchematicaHelper {

    private SchematicaHelper() {}

    public static boolean isSchematicaPresent() {
        return false;
    }

    public static Optional<Tuple<IStaticSchematic, BlockPos>> getOpenSchematic() {
        return Optional.empty();
    }
}

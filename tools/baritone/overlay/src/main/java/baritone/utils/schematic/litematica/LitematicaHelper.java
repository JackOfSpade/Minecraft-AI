package baritone.utils.schematic.litematica;

import baritone.api.schematic.IStaticSchematic;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Tuple;

/** Stub for the excluded Litematica client-mod bridge: Litematica is never present on a server. */
public final class LitematicaHelper {

    private LitematicaHelper() {}

    public static boolean isLitematicaPresent() {
        return false;
    }

    public static boolean hasLoadedSchematic(int i) {
        return false;
    }

    public static Tuple<IStaticSchematic, Vec3i> getSchematic(int i) {
        return null;
    }
}

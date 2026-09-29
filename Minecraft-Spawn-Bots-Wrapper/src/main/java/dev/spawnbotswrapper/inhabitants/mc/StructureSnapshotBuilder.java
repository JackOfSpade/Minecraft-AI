package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Turns Minecraft's {@link StructureStart} into the plain, immutable {@link StructureSnapshot} the rest of
 * the addon works with.
 * <p>
 * Everything registry-derived (a structure's registry id and the tags it belongs to) comes from the
 * world's RUNTIME registry, never from a hard-coded list, so structures added by mods and data packs are
 * handled exactly like vanilla ones. That data is cached per structure and must be dropped with
 * {@link #invalidate()} whenever data packs reload, because tag membership can change on {@code /reload}.
 * <p>
 * One start exists per structure INSTANCE however many chunks it spans, so one snapshot is produced per
 * instance. Server thread only.
 */
public final class StructureSnapshotBuilder implements SnapshotSource {

    /** What the registry says about one structure: its id and every structure tag it is in (without '#'). */
    public record Info(String id, Set<String> tagIds) {
        public Info {
            tagIds = Set.copyOf(tagIds);
        }
    }

    private final Map<Structure, Info> cache = new IdentityHashMap<>();

    /** Forgets all cached registry data. Call when data packs (and therefore tags) reload. */
    @Override
    public void invalidate() {
        cache.clear();
    }

    /** True for a start that describes a real structure: not the legacy "invalid" sentinel, and it has pieces. */
    public static boolean usable(StructureStart start) {
        return start != null && start.getStructure() != null && start.isValid();
    }

    /** The dimension id used in {@link StructureKey}s, e.g. {@code minecraft:overworld}. */
    public static String dimensionId(ServerLevel world) {
        return world.dimension().identifier().toString();
    }

    /** Identity of a start, or null when the start is unusable or its structure is not in the registry. */
    @Override
    public StructureKey keyOf(ServerLevel world, StructureStart start) {
        if (!usable(start)) {
            return null;
        }
        Info info = infoOf(world, start.getStructure());
        if (info == null) {
            return null;
        }
        ChunkPos pos = start.getChunkPos();
        return new StructureKey(dimensionId(world), info.id(), pos.x, pos.z);
    }

    /** Full snapshot of a start, or null when it is unusable or its structure is not in the registry. */
    @Override
    public StructureSnapshot build(ServerLevel world, StructureStart start, boolean newlyGenerated) {
        if (!usable(start)) {
            return null;
        }
        Info info = infoOf(world, start.getStructure());
        return info == null ? null : snapshot(dimensionId(world), info, start, newlyGenerated);
    }

    /** Registry facts about a structure of this world, cached; null when the structure is not registered. */
    public Info infoOf(ServerLevel world, Structure structure) {
        Info cached = cache.get(structure);
        if (cached != null) {
            return cached;
        }
        RegistryAccess manager = world.registryAccess();
        Optional<Registry<Structure>> registry = manager.lookup(Registries.STRUCTURE);
        if (registry.isEmpty()) {
            return null;
        }
        Info info = describe(registry.get().wrapAsHolder(structure));
        if (info != null) {
            cache.put(structure, info);
        }
        return info;
    }

    /**
     * Id and tags of a registry entry; null for a direct (unregistered) entry, which has no id. Tags that are
     * not bound yet (no data pack loaded) read as "in no tag".
     */
    static Info describe(Holder<Structure> entry) {
        Optional<String> id = entry.unwrapKey().map(key -> key.identifier().toString());
        if (id.isEmpty()) {
            return null;
        }
        Set<String> tags = new TreeSet<>();
        try {
            entry.tags().map(StructureSnapshotBuilder::tagId).forEach(tags::add);
        } catch (IllegalStateException tagsNotBound) {
            tags.clear();
        }
        return new Info(id.get(), tags);
    }

    /** {@code TagKey} to the string form used in configs and snapshots: the id, without a leading '#'. */
    static String tagId(TagKey<?> tag) {
        return tag.location().toString();
    }

    /** The snapshot of one start; separated from the registry lookups so it can be tested with a stand-in. */
    static StructureSnapshot snapshot(String dimensionId, Info info, StructureStart start, boolean newlyGenerated) {
        ChunkPos pos = start.getChunkPos();
        List<IntBox> pieces = new ArrayList<>(start.getPieces().size());
        for (StructurePiece piece : start.getPieces()) {
            pieces.add(box(piece.getBoundingBox()));
        }
        return new StructureSnapshot(
                new StructureKey(dimensionId, info.id(), pos.x, pos.z),
                info.tagIds(),
                box(start.getBoundingBox()),
                pieces,
                newlyGenerated);
    }

    /** Minecraft's inclusive block box as the addon's own box type. */
    static IntBox box(BoundingBox b) {
        return new IntBox(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }
}

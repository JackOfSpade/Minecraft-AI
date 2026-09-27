package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.gen.structure.Structure;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

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
        return start != null && start.getStructure() != null && start.hasChildren();
    }

    /** The dimension id used in {@link StructureKey}s, e.g. {@code minecraft:overworld}. */
    public static String dimensionId(ServerWorld world) {
        return world.getRegistryKey().getValue().toString();
    }

    /** Identity of a start, or null when the start is unusable or its structure is not in the registry. */
    @Override
    public StructureKey keyOf(ServerWorld world, StructureStart start) {
        if (!usable(start)) {
            return null;
        }
        Info info = infoOf(world, start.getStructure());
        if (info == null) {
            return null;
        }
        ChunkPos pos = start.getPos();
        return new StructureKey(dimensionId(world), info.id(), pos.x, pos.z);
    }

    /** Full snapshot of a start, or null when it is unusable or its structure is not in the registry. */
    @Override
    public StructureSnapshot build(ServerWorld world, StructureStart start, boolean newlyGenerated) {
        if (!usable(start)) {
            return null;
        }
        Info info = infoOf(world, start.getStructure());
        return info == null ? null : snapshot(dimensionId(world), info, start, newlyGenerated);
    }

    /** Registry facts about a structure of this world, cached; null when the structure is not registered. */
    public Info infoOf(ServerWorld world, Structure structure) {
        Info cached = cache.get(structure);
        if (cached != null) {
            return cached;
        }
        DynamicRegistryManager manager = world.getRegistryManager();
        Optional<Registry<Structure>> registry = manager.getOptional(RegistryKeys.STRUCTURE);
        if (registry.isEmpty()) {
            return null;
        }
        Info info = describe(registry.get().getEntry(structure));
        if (info != null) {
            cache.put(structure, info);
        }
        return info;
    }

    /**
     * Id and tags of a registry entry; null for a direct (unregistered) entry, which has no id. Tags that are
     * not bound yet (no data pack loaded) read as "in no tag".
     */
    static Info describe(RegistryEntry<Structure> entry) {
        Optional<String> id = entry.getKey().map(key -> key.getValue().toString());
        if (id.isEmpty()) {
            return null;
        }
        Set<String> tags = new TreeSet<>();
        try {
            entry.streamTags().map(StructureSnapshotBuilder::tagId).forEach(tags::add);
        } catch (IllegalStateException tagsNotBound) {
            tags.clear();
        }
        return new Info(id.get(), tags);
    }

    /** {@code TagKey} to the string form used in configs and snapshots: the id, without a leading '#'. */
    static String tagId(TagKey<?> tag) {
        return tag.id().toString();
    }

    /** The snapshot of one start; separated from the registry lookups so it can be tested with a stand-in. */
    static StructureSnapshot snapshot(String dimensionId, Info info, StructureStart start, boolean newlyGenerated) {
        ChunkPos pos = start.getPos();
        List<IntBox> pieces = new ArrayList<>(start.getChildren().size());
        for (StructurePiece piece : start.getChildren()) {
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
    static IntBox box(BlockBox b) {
        return new IntBox(b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ());
    }
}

package io.github.zoyluo.minecraftai.perception;

import com.google.gson.Gson;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A small, honest visual-context sample for conversational questions such as "do you see this?"
 *
 * <p>It deliberately traces the speaker's actual view cone rather than scanning arbitrary world
 * cells.  Each reported block is the first thing a ray from the speaker can hit, so the model can
 * discuss a facade, a cliff, or a visible route without being given hidden terrain or a fabricated
 * screenshot.</p>
 */
public final class SpeakerViewCollector {
    private static final Gson GSON = new Gson();
    private static final double VIEW_RANGE = 32.0D;
    private static final double[] HORIZONTAL_SAMPLES = {-0.52D, -0.26D, 0.0D, 0.26D, 0.52D};
    private static final double[] VERTICAL_SAMPLES = {-0.34D, 0.0D, 0.34D};

    private SpeakerViewCollector() {
    }

    /**
     * Captures a compact description of what the chat sender is presently looking at.  The
     * context is intentionally unavailable across dimensions: a remote companion must not claim
     * that it can see a scene beside the player.
     */
    public static SpeakerView collect(ServerPlayer speaker, AIPlayerEntity companion) {
        if (speaker == null || companion == null || speaker.level() != companion.level()) {
            return SpeakerView.unavailable();
        }

        Vec3 eye = speaker.getEyePosition();
        Vec3 forward = speaker.getViewVector(1.0F).normalize();
        Vec3 right = forward.cross(new Vec3(0.0D, 1.0D, 0.0D));
        if (right.lengthSqr() < 0.0001D) {
            right = new Vec3(1.0D, 0.0D, 0.0D);
        } else {
            right = right.normalize();
        }
        Vec3 up = right.cross(forward).normalize();

        Map<BlockPos, SampledBlock> visible = new LinkedHashMap<>();
        SampledBlock center = null;
        for (double vertical : VERTICAL_SAMPLES) {
            for (double horizontal : HORIZONTAL_SAMPLES) {
                Vec3 ray = forward.add(right.scale(horizontal)).add(up.scale(vertical)).normalize();
                BlockHitResult hit = speaker.level().clip(new ClipContext(
                        eye,
                        eye.add(ray.scale(VIEW_RANGE)),
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.ANY,
                        speaker));
                if (hit.getType() != HitResult.Type.BLOCK) {
                    continue;
                }
                BlockPos pos = hit.getBlockPos().immutable();
                BlockState state = speaker.level().getBlockState(pos);
                SampledBlock sampled = new SampledBlock(
                        BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                        pos,
                        round(eye.distanceTo(hit.getLocation())));
                visible.putIfAbsent(pos, sampled);
                if (horizontal == 0.0D && vertical == 0.0D) {
                    center = sampled;
                }
            }
        }

        List<SampledBlock> samples = List.copyOf(visible.values());
        if (samples.isEmpty()) {
            return new SpeakerView(true, false, "", 0, 0, 0, 0.0D,
                    speaker.blockPosition().getY(), 0, 0, List.of(), List.of(), 0, 0, 0,
                    "No solid or fluid surface is visible in the speaker's current view cone.");
        }

        SampledBlock focus = center != null ? center : samples.getFirst();
        List<MaterialCount> materials = materialCounts(samples);
        List<String> features = visibleFeatures(samples);
        int minX = samples.stream().mapToInt(sample -> sample.pos().getX()).min().orElse(focus.pos().getX());
        int maxX = samples.stream().mapToInt(sample -> sample.pos().getX()).max().orElse(focus.pos().getX());
        int minY = samples.stream().mapToInt(sample -> sample.pos().getY()).min().orElse(focus.pos().getY());
        int maxY = samples.stream().mapToInt(sample -> sample.pos().getY()).max().orElse(focus.pos().getY());
        int minZ = samples.stream().mapToInt(sample -> sample.pos().getZ()).min().orElse(focus.pos().getZ());
        int maxZ = samples.stream().mapToInt(sample -> sample.pos().getZ()).max().orElse(focus.pos().getZ());
        int lowestDrop = Math.max(0, speaker.blockPosition().getY() - minY);

        return new SpeakerView(true, true, focus.blockId(), focus.pos().getX(), focus.pos().getY(), focus.pos().getZ(),
                focus.distance(), speaker.blockPosition().getY(), minY, lowestDrop, materials, features,
                maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1,
                "This is a sampled line-of-sight view, not a full map or screenshot. Describe only the visible evidence.");
    }

    private static List<MaterialCount> materialCounts(List<SampledBlock> samples) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SampledBlock sample : samples) {
            counts.merge(sample.blockId(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .map(entry -> new MaterialCount(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(MaterialCount::visibleHits).reversed()
                        .thenComparing(MaterialCount::blockId))
                .toList();
    }

    private static List<String> visibleFeatures(List<SampledBlock> samples) {
        Set<String> features = new LinkedHashSet<>();
        for (SampledBlock sample : samples) {
            String id = sample.blockId().toLowerCase(Locale.ROOT);
            if (id.contains("door")) {
                features.add("door");
            }
            if (id.contains("glass")) {
                features.add("glass");
            }
            if (id.contains("stairs")) {
                features.add("stairs");
            }
            if (id.contains("slab")) {
                features.add("slabs");
            }
            if (id.contains("fence")) {
                features.add("fences");
            }
            if (id.contains("torch") || id.contains("lantern")) {
                features.add("lighting");
            }
            if (id.contains("trapdoor")) {
                features.add("trapdoor");
            }
            if (id.contains("water")) {
                features.add("water");
            }
            if (id.contains("lava")) {
                features.add("lava");
            }
        }
        return new ArrayList<>(features);
    }

    static List<String> featuresForTest(List<String> blockIds) {
        List<SampledBlock> samples = new ArrayList<>();
        int x = 0;
        for (String blockId : blockIds) {
            samples.add(new SampledBlock(blockId, new BlockPos(x++, 0, 0), 1.0D));
        }
        return visibleFeatures(samples);
    }

    private static double round(double value) {
        return Math.round(value * 10.0D) / 10.0D;
    }

    private record SampledBlock(String blockId, BlockPos pos, double distance) {
    }

    public record MaterialCount(String blockId, int visibleHits) {
    }

    public record SpeakerView(boolean sameDimension,
                              boolean hasVisibleTarget,
                              String focusBlock,
                              int focusX,
                              int focusY,
                              int focusZ,
                              double focusDistanceBlocks,
                              int speakerFeetY,
                              int lowestVisibleBlockY,
                              int visibleDropBlocks,
                              List<MaterialCount> visibleMaterials,
                              List<String> visibleFeatures,
                              int sampledSpanX,
                              int sampledSpanY,
                              int sampledSpanZ,
                              String limitations) {
        public String toJson() {
            return GSON.toJson(this);
        }

        private static SpeakerView unavailable() {
            return new SpeakerView(false, false, "", 0, 0, 0, 0.0D,
                    0, 0, 0, List.of(), List.of(), 0, 0, 0,
                    "The speaker is not in this companion's dimension, so no shared visual context is available.");
        }
    }
}

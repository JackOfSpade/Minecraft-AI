package io.github.zoyluo.minecraftai.task;

import java.util.ArrayList;
import java.util.List;

public record BlueprintSchema(
        String name,
        int width,
        int height,
        int depth,
        List<BlockPlacement> placements,
        List<Op> ops
) {
    public record BlockPlacement(int dx, int dy, int dz, String blockId, String palette) {
        public BlockPlacement(int dx, int dy, int dz, String blockId) {
            this(dx, dy, dz, blockId, null);
        }
    }

    public record Op(String type, int[] from, int[] to, String block, String palette) {
    }

    public static BlueprintSchema hut5x5() {
        List<BlockPlacement> blocks = new ArrayList<>();
        String plank = "minecraft:oak_planks";
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                blocks.add(new BlockPlacement(x, 0, z, plank));
            }
        }
        for (int y = 1; y <= 3; y++) {
            for (int x = 0; x < 5; x++) {
                blocks.add(new BlockPlacement(x, y, 0, plank));
                blocks.add(new BlockPlacement(x, y, 4, plank));
            }
            for (int z = 1; z < 4; z++) {
                blocks.add(new BlockPlacement(0, y, z, plank));
                blocks.add(new BlockPlacement(4, y, z, plank));
            }
        }
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                blocks.add(new BlockPlacement(x, 4, z, plank));
            }
        }
        blocks.removeIf(block -> block.dx() == 2 && block.dz() == 0 && (block.dy() == 1 || block.dy() == 2));
        return new BlueprintSchema("hut_5x5", 5, 5, 5, List.copyOf(blocks), List.of());
    }

    public static BlueprintSchema smallHutOps() {
        return new BlueprintSchema("small_hut", 5, 5, 5, List.of(
                new BlockPlacement(2, 1, 0, "minecraft:air"),
                new BlockPlacement(2, 2, 0, "minecraft:air")
        ), List.of(
                new Op("layer", new int[]{0, 0, 0}, new int[]{4, 0, 4}, null, "planks"),
                new Op("hollow_box", new int[]{0, 1, 0}, new int[]{4, 3, 4}, null, "planks"),
                new Op("layer", new int[]{0, 4, 0}, new int[]{4, 4, 4}, null, "planks")
        ));
    }

    /**
     * P3 parametric house: generated from a "custom:widthxdepthxheight:material" spec (e.g. custom:7x5x4:stone).
     * Structure matches small_hut: floor + hollow walls + flat roof + a 2-block-high door opening centered on the front face.
     * width/depth = outer dimension including walls (clamped to 3..16 to keep oversized structures from exhausting materials); height = net wall height (clamped to 2..8; total height = height + 1 floor + 1 roof).
     * material = palette name (planks/stone_like/glass... see MaterialPalette; unknown defaults to planks); gathering/building accepts any member of the family.
     * Returns null for an invalid spec (the caller reports an IOException).
     */
    public static BlueprintSchema parametricHouse(String spec) {
        try {
            String[] parts = spec.split(":");
            if (parts.length < 2) {
                return null;
            }
            String[] dims = parts[1].split("x");
            if (dims.length != 3) {
                return null;
            }
            int w = Math.max(3, Math.min(16, Integer.parseInt(dims[0].trim())));
            int d = Math.max(3, Math.min(16, Integer.parseInt(dims[1].trim())));
            int h = Math.max(2, Math.min(8, Integer.parseInt(dims[2].trim())));
            String palette = parts.length >= 3 && !parts[2].isBlank() ? parts[2].trim() : "planks";
            int doorX = w / 2; // door opening centered on the front face (z=0), 2 blocks high
            List<BlockPlacement> door = List.of(
                    new BlockPlacement(doorX, 1, 0, "minecraft:air"),
                    new BlockPlacement(doorX, 2, 0, "minecraft:air"));
            List<Op> ops = List.of(
                    new Op("layer", new int[]{0, 0, 0}, new int[]{w - 1, 0, d - 1}, null, palette),
                    new Op("hollow_box", new int[]{0, 1, 0}, new int[]{w - 1, h, d - 1}, null, palette),
                    new Op("layer", new int[]{0, h + 1, 0}, new int[]{w - 1, h + 1, d - 1}, null, palette));
            return new BlueprintSchema(spec, w, h + 2, d, door, ops);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

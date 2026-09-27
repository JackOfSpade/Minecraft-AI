package dev.spawnbotswrapper.inhabitants.structure;

/**
 * Axis-aligned box of block coordinates, both corners INCLUSIVE (matches Minecraft's BlockBox).
 * Pure data so structure geometry can be passed around and unit-tested without Minecraft.
 */
public record IntBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public IntBox {
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            throw new IllegalArgumentException("inverted box " + minX + "," + minY + "," + minZ
                    + " .. " + maxX + "," + maxY + "," + maxZ);
        }
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    /** Number of blocks; long because big structures overflow int products. */
    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    public double centerX() {
        return (minX + maxX + 1) / 2.0;
    }

    public double centerY() {
        return (minY + maxY + 1) / 2.0;
    }

    public double centerZ() {
        return (minZ + maxZ + 1) / 2.0;
    }

    /** Squared distance in the XZ plane from a point to the closest point of this box (0 if inside). */
    public double horizontalDistanceSq(double x, double z) {
        double dx = Math.max(Math.max(minX - x, 0), x - (maxX + 1));
        double dz = Math.max(Math.max(minZ - z, 0), z - (maxZ + 1));
        return dx * dx + dz * dz;
    }

    /** Smallest box containing both. */
    public IntBox union(IntBox o) {
        return new IntBox(Math.min(minX, o.minX), Math.min(minY, o.minY), Math.min(minZ, o.minZ),
                Math.max(maxX, o.maxX), Math.max(maxY, o.maxY), Math.max(maxZ, o.maxZ));
    }
}

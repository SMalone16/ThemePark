package org.pawling.themepark;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/** The park occupies exactly 70 x 70 blocks; the X/Z offsets are -35 through +34. */
public record ParkArea(World world, int centerX, int centerZ, int floorY) {
    public static final int SIZE = 70;

    public int minX() { return centerX - 35; }
    public int maxX() { return centerX + 34; }
    public int minZ() { return centerZ - 35; }
    public int maxZ() { return centerZ + 34; }

    public boolean contains(int x, int z) {
        return x >= minX() && x <= maxX() && z >= minZ() && z <= maxZ();
    }

    public boolean contains(Block block) {
        return block.getWorld().equals(world) && contains(block.getX(), block.getZ());
    }

    public boolean contains(Location location) {
        return world.equals(location.getWorld()) && contains(location.getBlockX(), location.getBlockZ());
    }

    /** Half-block shift keeps circular features symmetrical in an even-width plot. */
    public double radiusSquared(int x, int z) {
        double dx = x - centerX + 0.5;
        double dz = z - centerZ + 0.5;
        return dx * dx + dz * dz;
    }

    public boolean isRiver(int x, int z) {
        double r2 = radiusSquared(x, z);
        return r2 >= 25.0 * 25.0 && r2 <= 31.0 * 31.0;
    }

    public boolean isNightBarrier(int x, int z) {
        if (!contains(x, z)) return false;
        double r2 = radiusSquared(x, z);
        return r2 >= 32.0 * 32.0 && r2 <= 34.0 * 34.0;
    }

    public Location spawn() {
        return new Location(world, centerX + 0.5, floorY + 2.0, centerZ + 0.5);
    }

    public Location boardingPoint() {
        return new Location(world, centerX + 0.5, floorY + 11.6, centerZ - 33 + 0.5);
    }
}

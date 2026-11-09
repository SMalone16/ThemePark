package org.pawling.themepark;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/** All world modifications run synchronously; large terraforming is spread across ticks. */
public final class ParkBuilder {
    private static final int COLUMNS_PER_TICK = 70;
    private final JavaPlugin plugin;

    public ParkBuilder(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void generate(ParkArea area, Runnable onFinished) {
        new BukkitRunnable() {
            private int column;

            @Override
            public void run() {
                try {
                    int processed = 0;
                    while (column < ParkArea.SIZE * ParkArea.SIZE && processed++ < COLUMNS_PER_TICK) {
                        int dx = column / ParkArea.SIZE;
                        int dz = column % ParkArea.SIZE;
                        flattenColumn(area, area.minX() + dx, area.minZ() + dz);
                        column++;
                    }
                    if (column >= ParkArea.SIZE * ParkArea.SIZE) {
                        buildLandscape(area);
                        buildRollerCoaster(area);
                        buildExtras(area);
                        buildArcade(area);
                        area.world().setSpawnLocation(area.centerX(), area.floorY() + 2, area.centerZ());
                        plugin.getLogger().info("ThemePark generated 70x70 park in "
                                + area.world().getName() + " at " + area.centerX()
                                + "," + area.floorY() + "," + area.centerZ());
                        onFinished.run();
                        cancel();
                    }
                } catch (RuntimeException failure) {
                    cancel();
                    plugin.getLogger().severe("ThemePark generation interrupted; the next startup can retry: " + failure);
                    failure.printStackTrace();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void flattenColumn(ParkArea area, int x, int z) {
        World world = area.world();
        int floor = area.floorY();
        int top = Math.min(world.getMaxHeight() - 1,
                Math.max(floor + 20, world.getHighestBlockYAt(x, z) + 3));

        // Refill old caves and hollows so every park tile has a solid base.
        for (int y = Math.max(world.getMinHeight(), floor - 4); y < floor; y++) {
            place(world, x, y, z, Material.STONE);
        }
        place(world, x, floor, z, Material.STONE);
        for (int y = floor + 1; y <= top; y++) {
            place(world, x, y, z, Material.AIR);
        }
    }

    private void buildLandscape(ParkArea area) {
        World world = area.world();
        int floor = area.floorY();
        for (int x = area.minX(); x <= area.maxX(); x++) {
            for (int z = area.minZ(); z <= area.maxZ(); z++) {
                double r2 = area.radiusSquared(x, z);
                int dx = x - area.centerX();
                int dz = z - area.centerZ();

                if (area.isRiver(x, z)) {
                    // A continuous, two-block-deep circular canal with a stone bed.
                    place(world, x, floor - 2, z, Material.STONE_BRICKS);
                    place(world, x, floor - 1, z, Material.WATER);
                    place(world, x, floor, z, Material.WATER);
                } else if ((r2 >= 24.0 * 24.0 && r2 < 25.0 * 25.0)
                        || (r2 > 31.0 * 31.0 && r2 <= 32.0 * 32.0)) {
                    place(world, x, floor, z, Material.STONE_BRICKS);
                } else if ((r2 >= 20.0 * 20.0 && r2 <= 23.0 * 23.0)
                        || (r2 < 24.0 * 24.0 && (Math.abs(dx) <= 2 || Math.abs(dz) <= 2))) {
                    place(world, x, floor, z, Material.DIRT_PATH);
                }
            }
        }

        // Clear central spawn plaza with illuminated edge markers.
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                place(world, area.centerX() + dx, floor, area.centerZ() + dz, Material.STONE_BRICKS);
            }
        }
        for (int dx : new int[] {-4, 4}) {
            for (int dz : new int[] {-4, 4}) {
                place(world, area.centerX() + dx, floor, area.centerZ() + dz, Material.SEA_LANTERN);
            }
        }
    }

    /**
     * Non-destructive route migration from the former perimeter-only loop.
     * It removes only known legacy rail positions; it never re-flattens terrain.
     */
    public void upgradeCoasterAndArcade(ParkArea area) {
        World world = area.world();
        int floor = area.floorY();
        for (int x = area.minX(); x <= area.maxX(); x++) {
            for (int z = area.minZ(); z <= area.maxZ(); z++) {
                int dx = x - area.centerX();
                int dz = z - area.centerZ();
                boolean oldRailLine = ((dz == -33 || dz == 32) && dx >= -33 && dx <= 32)
                        || ((dx == -33 || dx == 32) && dz >= -33 && dz <= 32);
                if (!oldRailLine) continue;
                for (int y = floor + 9; y <= floor + 18; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.RAIL || block.getType() == Material.POWERED_RAIL) {
                        block.setType(Material.AIR, false);
                        Block below = world.getBlockAt(x, y - 1, z);
                        if (below.getType() == Material.REDSTONE_BLOCK
                                || below.getType() == Material.POLISHED_ANDESITE) {
                            below.setType(Material.AIR, false);
                        }
                    }
                }
            }
        }
        buildRollerCoaster(area);
        buildArcade(area);
    }

    private void buildRollerCoaster(ParkArea area) {
        List<CoasterCourse.Node> route = new CoasterCourse(area).nodes();
        World world = area.world();
        for (int i = 0; i < route.size(); i++) {
            CoasterCourse.Node current = route.get(i);
            CoasterCourse.Node previous = route.get(Math.max(0, i - 1));
            CoasterCourse.Node next = route.get(Math.min(route.size() - 1, i + 1));

            BlockFace towardPrevious = (i == 0)
                    ? direction(current, next).getOppositeFace()
                    : direction(current, previous);
            BlockFace towardNext = (i == route.size() - 1)
                    ? direction(current, previous).getOppositeFace()
                    : direction(current, next);
            boolean corner = towardPrevious != towardNext.getOppositeFace();
            boolean ascending = previous.y() > current.y() || next.y() > current.y();
            boolean powered = !corner && (ascending || i % 7 == 0);

            int x = area.centerX() + current.x();
            int y = current.y();
            int z = area.centerZ() + current.z();
            Material support = powered ? Material.REDSTONE_BLOCK : Material.POLISHED_ANDESITE;
            place(world, x, y - 1, z, support);
            place(world, x, y, z, powered ? Material.POWERED_RAIL : Material.RAIL);
            for (int air = y + 1; air <= y + 3; air++) {
                place(world, x, air, z, Material.AIR);
            }
            if (i % 9 == 0 && !area.nearHideout(x, z, 3)
                    && !(Math.abs(current.x() - 11) <= 6 && Math.abs(current.z()) <= 6)) {
                for (int pillarY = area.floorY() + 1; pillarY < y - 1; pillarY++) {
                    place(world, x, pillarY, z, Material.STONE_BRICKS);
                }
            }

            Block rail = world.getBlockAt(x, y, z);
            Rail data = (Rail) rail.getBlockData();
            data.setShape(shape(towardPrevious, towardNext,
                    previous.y() > current.y() ? towardPrevious
                            : next.y() > current.y() ? towardNext : null));
            rail.setBlockData(data, false);
            if (powered && rail.getBlockData() instanceof org.bukkit.block.data.Powerable powerable) {
                powerable.setPowered(true);
                rail.setBlockData(powerable, false);
            }
        }
    }

    private Rail.Shape shape(BlockFace a, BlockFace b, BlockFace uphill) {
        if (a != b.getOppositeFace()) {
            EnumSet<BlockFace> sides = EnumSet.of(a, b);
            if (sides.contains(BlockFace.NORTH) && sides.contains(BlockFace.EAST)) return Rail.Shape.NORTH_EAST;
            if (sides.contains(BlockFace.NORTH) && sides.contains(BlockFace.WEST)) return Rail.Shape.NORTH_WEST;
            if (sides.contains(BlockFace.SOUTH) && sides.contains(BlockFace.EAST)) return Rail.Shape.SOUTH_EAST;
            return Rail.Shape.SOUTH_WEST;
        }
        if (uphill != null) {
            return switch (uphill) {
                case NORTH -> Rail.Shape.ASCENDING_NORTH;
                case SOUTH -> Rail.Shape.ASCENDING_SOUTH;
                case EAST -> Rail.Shape.ASCENDING_EAST;
                case WEST -> Rail.Shape.ASCENDING_WEST;
                default -> throw new IllegalStateException("Invalid rail incline");
            };
        }
        return a == BlockFace.EAST || a == BlockFace.WEST
                ? Rail.Shape.EAST_WEST : Rail.Shape.NORTH_SOUTH;
    }

    private BlockFace direction(CoasterCourse.Node from, CoasterCourse.Node to) {
        if (to.x() > from.x()) return BlockFace.EAST;
        if (to.x() < from.x()) return BlockFace.WEST;
        if (to.z() > from.z()) return BlockFace.SOUTH;
        if (to.z() < from.z()) return BlockFace.NORTH;
        throw new IllegalStateException("Coaster contains duplicate adjacent nodes");
    }

    public void buildArcade(ParkArea area) {
        platform(area, -3, -17, Material.GOLD_BLOCK);
        platform(area, -19, 16, Material.EMERALD_BLOCK);
        platform(area, -9, 12, Material.IRON_BLOCK);
        platform(area, -22, -8, Material.LAPIS_BLOCK);
        World world = area.world();
        int x = area.centerX() + 21;
        int z = area.centerZ() - 8;
        for (int dz = -3; dz <= 3; dz++) {
            for (int dy = 2; dy <= 8; dy++) {
                int radius = Math.max(Math.abs(dz), Math.abs(dy - 5));
                Material color = radius == 0 ? Material.GOLD_BLOCK
                        : radius == 1 ? Material.RED_CONCRETE
                        : radius == 2 ? Material.WHITE_CONCRETE : Material.BLACK_CONCRETE;
                place(world, x, area.floorY() + dy, z + dz, color);
                place(world, x + 1, area.floorY() + dy, z + dz, Material.STONE_BRICKS);
            }
        }
    }

    private void platform(ParkArea area, int dx, int dz, Material tile) {
        for (int x = dx - 1; x <= dx + 1; x++) {
            for (int z = dz - 1; z <= dz + 1; z++) {
                place(area.world(), area.centerX() + x,
                        area.floorY(), area.centerZ() + z, tile);
            }
        }
    }

    /** New attractions can be installed into a saved park without re-flattening it. */
    public void buildExtras(ParkArea area) {
        buildCarousel(area);
        buildHideouts(area);
    }

    private void buildCarousel(ParkArea area) {
        World world = area.world();
        int cx = area.centerX() + 11;
        int cz = area.centerZ();
        int floor = area.floorY();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                int r2 = dx * dx + dz * dz;
                if (r2 > 36) continue;
                Material base = r2 >= 25 ? Material.GOLD_BLOCK
                        : ((dx + dz) % 2 == 0 ? Material.RED_CONCRETE : Material.WHITE_CONCRETE);
                place(world, cx + dx, floor, cz + dz, base);
                if (r2 <= 25) {
                    // A striped canopy stays fixed while visible seat blocks orbit beneath it.
                    place(world, cx + dx, floor + 5, cz + dz,
                            (dx + dz) % 2 == 0 ? Material.RED_CONCRETE : Material.WHITE_CONCRETE);
                }
            }
        }
        for (int y = floor + 1; y <= floor + 4; y++) {
            place(world, cx, y, cz, Material.QUARTZ_PILLAR);
        }
        place(world, cx, floor + 6, cz, Material.GLOWSTONE);
    }

    /** Restores only temporary shelter blocks at dawn, leaving the rest of the park intact. */
    public void repairHideouts(ParkArea area) {
        buildHideouts(area);
    }

    private void buildHideouts(ParkArea area) {
        World world = area.world();
        int floor = area.floorY();
        for (int[] offset : ParkArea.HIDEOUTS) {
            int cx = area.centerX() + offset[0];
            int cz = area.centerZ() + offset[1];
            // Doors always face the plaza so players can duck in from the paths.
            int doorwayZ = offset[1] > 0 ? -2 : 2;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    place(world, cx + dx, floor, cz + dz, Material.STONE_BRICKS);
                    boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                    boolean door = dx == 0 && dz == doorwayZ;
                    for (int dy = 1; dy <= 2; dy++) {
                        place(world, cx + dx, floor + dy, cz + dz,
                                wall && !door ? Material.OAK_PLANKS : Material.AIR);
                    }
                    place(world, cx + dx, floor + 3, cz + dz, Material.DARK_OAK_SLAB);
                }
            }
            // Closeable gate buys players time; monsters eventually break the walls.
            place(world, cx, floor + 1, cz + doorwayZ, Material.OAK_FENCE_GATE);
        }
    }

    private void place(World world, int x, int y, int z, Material material) {
        world.getBlockAt(x, y, z).setType(material, false);
    }

    private record TrackPoint(int dx, int dz, int y) {
        int x(ParkArea area) { return area.centerX() + dx; }
        int z(ParkArea area) { return area.centerZ() + dz; }
    }
}

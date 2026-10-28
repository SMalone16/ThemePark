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
                        buildStaircaseAndStation(area);
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

    private void buildRollerCoaster(ParkArea area) {
        List<TrackPoint> route = makeRoute(area);
        World world = area.world();
        int floor = area.floorY();

        // First construct all the track supports. Vertical pillars are spaced
        // around the whole 70x70 park, with beams under every rail segment.
        for (int i = 0; i < route.size(); i++) {
            TrackPoint current = route.get(i);
            TrackPoint previous = route.get((i + route.size() - 1) % route.size());
            TrackPoint next = route.get((i + 1) % route.size());
            boolean corner = isCorner(previous, current, next);
            boolean slope = previous.y() > current.y() || next.y() > current.y();
            boolean booster = !corner && (slope || i % 6 == 0
                    || (current.dz() == -33 && Math.abs(current.dx()) <= 5));

            if (i % 7 == 0) {
                for (int y = floor + 1; y < current.y() - 1; y++) {
                    place(world, current.x(area), y, current.z(area), Material.STONE_BRICKS);
                }
            }
            place(world, current.x(area), current.y() - 1, current.z(area),
                    booster ? Material.REDSTONE_BLOCK : Material.POLISHED_ANDESITE);
            place(world, current.x(area), current.y(), current.z(area),
                    booster ? Material.POWERED_RAIL : Material.RAIL);

            for (int y = current.y() + 1; y <= current.y() + 3; y++) {
                place(world, current.x(area), y, current.z(area), Material.AIR);
            }
        }

        // Explicit rail shapes make the loop closed and handle inclined tracks.
        // Curves use normal rails; powered rails are always straight or sloped.
        for (int i = 0; i < route.size(); i++) {
            TrackPoint current = route.get(i);
            TrackPoint previous = route.get((i + route.size() - 1) % route.size());
            TrackPoint next = route.get((i + 1) % route.size());
            Block block = world.getBlockAt(current.x(area), current.y(), current.z(area));
            Rail data = (Rail) block.getBlockData();
            data.setShape(shape(previous, current, next));
            block.setBlockData(data, false);
            // We intentionally place tracks without neighbor physics to protect
            // our custom curves. Explicitly energize boosters as well.
            if (block.getType() == Material.POWERED_RAIL
                    && block.getBlockData() instanceof org.bukkit.block.data.Powerable powered) {
                powered.setPowered(true);
                block.setBlockData(powered, false);
            }
        }
    }

    private List<TrackPoint> makeRoute(ParkArea area) {
        List<TrackPoint> points = new ArrayList<>();
        int y = area.floorY() + 11;
        // Rail loop one block inside the park border, following a rounded
        // perimeter effect from the four vanilla rail corners.
        for (int x = -33; x <= 32; x++) {
            points.add(new TrackPoint(x, -33, y + hill(x, 8, 3, 6)));
        }
        for (int z = -32; z <= 32; z++) {
            points.add(new TrackPoint(32, z, y + hill(z, -14, 3, 6)));
        }
        for (int x = 31; x >= -33; x--) {
            points.add(new TrackPoint(x, 32, y + hill(x, -20, 3, 6)));
        }
        for (int z = 31; z >= -32; z--) {
            points.add(new TrackPoint(-33, z, y + hill(z, 7, 3, 6)));
        }
        return points;
    }

    private int hill(int coordinate, int start, int rise, int plateau) {
        int offset = coordinate - start;
        if (offset <= 0 || offset >= rise * 2 + plateau) return 0;
        if (offset < rise) return offset;
        if (offset <= rise + plateau) return rise;
        return rise * 2 + plateau - offset;
    }

    private boolean isCorner(TrackPoint prev, TrackPoint current, TrackPoint next) {
        return prev.dx() != next.dx() && prev.dz() != next.dz();
    }

    private Rail.Shape shape(TrackPoint prev, TrackPoint current, TrackPoint next) {
        BlockFace a = direction(current, prev);
        BlockFace b = direction(current, next);
        if (a != b.getOppositeFace()) {
            EnumSet<BlockFace> sides = EnumSet.of(a, b);
            if (sides.contains(BlockFace.NORTH) && sides.contains(BlockFace.EAST)) return Rail.Shape.NORTH_EAST;
            if (sides.contains(BlockFace.NORTH) && sides.contains(BlockFace.WEST)) return Rail.Shape.NORTH_WEST;
            if (sides.contains(BlockFace.SOUTH) && sides.contains(BlockFace.EAST)) return Rail.Shape.SOUTH_EAST;
            return Rail.Shape.SOUTH_WEST;
        }
        if (prev.y() > current.y()) return ascending(a);
        if (next.y() > current.y()) return ascending(b);
        return a == BlockFace.EAST || a == BlockFace.WEST
                ? Rail.Shape.EAST_WEST : Rail.Shape.NORTH_SOUTH;
    }

    private Rail.Shape ascending(BlockFace towardHigher) {
        return switch (towardHigher) {
            case NORTH -> Rail.Shape.ASCENDING_NORTH;
            case SOUTH -> Rail.Shape.ASCENDING_SOUTH;
            case EAST -> Rail.Shape.ASCENDING_EAST;
            case WEST -> Rail.Shape.ASCENDING_WEST;
            default -> throw new IllegalArgumentException("Unexpected coaster direction: " + towardHigher);
        };
    }

    private BlockFace direction(TrackPoint from, TrackPoint to) {
        if (to.dx() > from.dx()) return BlockFace.EAST;
        if (to.dx() < from.dx()) return BlockFace.WEST;
        if (to.dz() > from.dz()) return BlockFace.SOUTH;
        return BlockFace.NORTH;
    }

    private void buildStaircaseAndStation(ParkArea area) {
        World world = area.world();
        int floor = area.floorY();

        // Station deck is adjacent to the northern rail at Z=-33. It is
        // reachable by a 3-wide staircase from the north interior pathway.
        for (int dz = -32; dz <= -29; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                place(world, area.centerX() + dx, floor + 10, area.centerZ() + dz,
                        Material.STONE_BRICKS);
                for (int y = floor + 11; y <= floor + 14; y++) {
                    place(world, area.centerX() + dx, y, area.centerZ() + dz, Material.AIR);
                }
            }
        }

        Stairs stairs = (Stairs) Material.STONE_BRICK_STAIRS.createBlockData();
        stairs.setFacing(BlockFace.NORTH);
        for (int step = 0; step < 10; step++) {
            int dz = -19 - step;
            int y = floor + 1 + step;
            for (int dx = -1; dx <= 1; dx++) {
                Block block = world.getBlockAt(area.centerX() + dx, y, area.centerZ() + dz);
                block.setBlockData(stairs.clone(), false);
                for (int air = y + 1; air <= y + 3; air++) {
                    place(world, area.centerX() + dx, air, area.centerZ() + dz, Material.AIR);
                }
                if (dx == 0 && step % 3 == 0) {
                    for (int support = floor + 1; support < y; support++) {
                        // Sparse pillars leave open water beneath the bridge.
                        place(world, area.centerX(), support, area.centerZ() + dz,
                                Material.STONE_BRICKS);
                    }
                }
            }
        }

        for (int dz = -32; dz <= -29; dz++) {
            for (int dx : new int[] {-3, 3}) {
                place(world, area.centerX() + dx, floor + 11, area.centerZ() + dz,
                        Material.OAK_FENCE);
            }
        }
        // Visible lighting at the boarding area.
        for (int dx : new int[] {-2, 2}) {
            place(world, area.centerX() + dx, floor + 10, area.centerZ() - 30,
                    Material.SEA_LANTERN);
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

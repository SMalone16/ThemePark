package org.pawling.themepark;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Location;

/**
 * Single-use elevated course. Every node is an adjacent rail block; turns are
 * orthogonal and all changes in height are one block per horizontal step.
 * A genuine closed loop: passengers complete all 245 sampled rail positions,
 * then dismount at the separate platform across the track from the entrance.
 */
public final class CoasterCourse {
    private static final int[][] WAYPOINTS = {
        {-3, -22}, {20, -22}, {20, -15}, {-17, -15},
        {-17, -2}, {-6, -2}, {-6, 5}, {19, 5},
        {19, 18}, {-25, 18}, {-25, -23},
        {-4, -23}, {-4, -22}, {-3, -22}
    };
    private static final int[] HILLS = {3, 0, 4, 2, 0, 0, 4, 2, 4, 4, 3, 0, 0};

    public record Node(int x, int y, int z) {
        public Location position(ParkArea area) {
            return new Location(area.world(), area.centerX() + x + 0.5,
                    y + 0.15, area.centerZ() + z + 0.5);
        }
    }

    private final List<Node> nodes;

    public CoasterCourse(ParkArea area) {
        List<Node> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (int seg = 0; seg < WAYPOINTS.length - 1; seg++) {
            int[] from = WAYPOINTS[seg];
            int[] to = WAYPOINTS[seg + 1];
            if (from[0] != to[0] && from[1] != to[1]) {
                throw new IllegalStateException("Coaster diagonal at segment " + seg);
            }
            int length = Math.abs(to[0] - from[0]) + Math.abs(to[1] - from[1]);
            int dx = Integer.compare(to[0], from[0]);
            int dz = Integer.compare(to[1], from[1]);
            for (int step = 0; step < length; step++) {
                int x = from[0] + dx * step;
                int z = from[1] + dz * step;
                int ramp = Math.max(0, Math.min(HILLS[seg],
                        Math.min(step - 2, length - step - 3)));
                Node node = new Node(x, area.floorY() + 12 + ramp, z);
                if (!visited.add(x + "," + z)) {
                    throw new IllegalStateException("Coaster self-intersects at " + x + "," + z);
                }
                if (!area.contains(area.centerX() + x, area.centerZ() + z)) {
                    throw new IllegalStateException("Coaster outside park: " + node);
                }
                if (!result.isEmpty()) {
                    Node last = result.get(result.size() - 1);
                    if (Math.abs(last.x - node.x) + Math.abs(last.z - node.z) != 1
                            || Math.abs(last.y - node.y) > 1) {
                        throw new IllegalStateException("Broken coaster join: " + last + " to " + node);
                    }
                }
                result.add(node);
            }
        }
        int[] last = WAYPOINTS[WAYPOINTS.length - 1];
        Node finish = new Node(last[0], area.floorY() + 12, last[1]);
        Node previous = result.get(result.size() - 1);
        if (Math.abs(previous.x - finish.x) + Math.abs(previous.z - finish.z) != 1
                || previous.y != finish.y) {
            throw new IllegalStateException("Invalid final coaster rail");
        }
        if (!finish.equals(result.get(0))) {
            throw new IllegalStateException("Coaster must close at the first rail");
        }
        result.add(finish); // Deliberate final duplicate for one complete lap.
        nodes = List.copyOf(result);
    }

    public List<Node> nodes() {
        return nodes;
    }

    public Location sample(ParkArea area, double progress) {
        double pos = Math.max(0.0, Math.min(nodes.size() - 1, progress));
        int index = Math.min(nodes.size() - 2, (int) Math.floor(pos));
        double fraction = pos - index;
        Location a = nodes.get(index).position(area);
        Location b = nodes.get(index + 1).position(area);
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        return new Location(area.world(),
                a.getX() + dx * fraction,
                a.getY() + (b.getY() - a.getY()) * fraction,
                a.getZ() + dz * fraction,
                yaw, 0.0f);
    }

    public int length() { return nodes.size(); }
}

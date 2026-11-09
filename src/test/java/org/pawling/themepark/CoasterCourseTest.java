package org.pawling.themepark;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Track routing properties are checked without starting a Bukkit server. */
final class CoasterCourseTest {
    @Test
    void contiguousSinglePassWithMultipleTurnsAndHills() {
        ParkArea area = new ParkArea(null, 0, 0, 80);
        List<CoasterCourse.Node> nodes = new CoasterCourse(area).nodes();

        assertEquals(245, nodes.size());
        assertEquals(-3, nodes.get(0).x());
        assertEquals(-22, nodes.get(0).z());
        assertEquals(-3, nodes.get(nodes.size() - 1).x());
        assertEquals(-22, nodes.get(nodes.size() - 1).z());
        assertEquals(92, nodes.get(0).y());

        Set<String> unique = new HashSet<>();
        int changesInElevation = 0;
        int turns = 0;
        int maxHeight = Integer.MIN_VALUE;
        for (int i = 0; i < nodes.size(); i++) {
            CoasterCourse.Node current = nodes.get(i);
            if (i == nodes.size() - 1) {
                assertEquals(nodes.get(0), current, "Final rail must close the loop");
            } else {
                assertTrue(unique.add(current.x() + "," + current.z()), "No duplicate nonterminal rail");
            }
            assertTrue(area.contains(current.x(), current.z()), "All rail blocks remain in park");
            maxHeight = Math.max(maxHeight, current.y());
            if (i > 0) {
                CoasterCourse.Node previous = nodes.get(i - 1);
                assertEquals(1, Math.abs(previous.x() - current.x())
                        + Math.abs(previous.z() - current.z()), "No gap or diagonal");
                assertTrue(Math.abs(previous.y() - current.y()) <= 1, "Safe track incline");
                if (previous.y() != current.y()) changesInElevation++;
            }
            if (i > 0 && i < nodes.size() - 1) {
                CoasterCourse.Node previous = nodes.get(i - 1);
                CoasterCourse.Node next = nodes.get(i + 1);
                if ((current.x() - previous.x()) != (next.x() - current.x())
                        || (current.z() - previous.z()) != (next.z() - current.z())) turns++;
            }
        }
        assertEquals(12, turns, "Multiple 90-degree turns through and around the park");
        assertTrue(maxHeight >= 96, "Course climbs at least 4 blocks");
        assertTrue(changesInElevation >= 12, "Course repeatedly climbs and drops");
    }
}

package org.pawling.themepark;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class ParkSurvivalCapsTest {
    @Test
    void perTypeCapsNeverExceedFive() {
        assertEquals(0, ParkSurvival.clampCap(-1));
        assertEquals(0, ParkSurvival.clampCap(0));
        assertEquals(2, ParkSurvival.clampCap(2));
        assertEquals(5, ParkSurvival.clampCap(5));
        assertEquals(5, ParkSurvival.clampCap(16));
        assertEquals(5, ParkSurvival.clampCap(Integer.MAX_VALUE));
    }
}

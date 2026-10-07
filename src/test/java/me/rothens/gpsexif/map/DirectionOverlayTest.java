package me.rothens.gpsexif.map;

import org.junit.jupiter.api.Test;

import java.awt.geom.Point2D;

import static org.junit.jupiter.api.Assertions.*;

class DirectionOverlayTest {

    private static final Point2D O = new Point2D.Double(100, 100);

    @Test
    void screenBearingIsCompassLike() {
        assertEquals(0, DirectionOverlay.bearing(O, new Point2D.Double(100, 50)), 1e-9, "up = north");
        assertEquals(90, DirectionOverlay.bearing(O, new Point2D.Double(150, 100)), 1e-9, "right = east");
        assertEquals(180, DirectionOverlay.bearing(O, new Point2D.Double(100, 150)), 1e-9);
        assertEquals(270, DirectionOverlay.bearing(O, new Point2D.Double(50, 100)), 1e-9);
        assertEquals(315, DirectionOverlay.bearing(O, new Point2D.Double(50, 50)), 1e-9);
    }

    @Test
    void towardsIsTheInverseOfBearing() {
        for (double d = 0; d < 360; d += 22.5) {
            Point2D p = DirectionOverlay.towards(O, d, 70);
            assertEquals(70, O.distance(p), 1e-9);
            assertEquals(d, DirectionOverlay.bearing(O, p), 1e-9);
        }
    }
}

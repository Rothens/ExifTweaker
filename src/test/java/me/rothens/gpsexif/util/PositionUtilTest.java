package me.rothens.gpsexif.util;

import org.junit.jupiter.api.Test;
import org.jxmapviewer.viewer.GeoPosition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PositionUtilTest {

    private static final double EPS = 1e-6;

    private static void assertPosition(double lat, double lon, GeoPosition gp) {
        assertEquals(lat, gp.getLatitude(), EPS);
        assertEquals(lon, gp.getLongitude(), EPS);
    }

    @Test
    void parsesDecimalWithSeparators() {
        assertPosition(47.4979, 19.0402, PositionUtil.parse("47.4979;19.0402"));
        assertPosition(47.4979, 19.0402, PositionUtil.parse(" 47.4979, 19.0402 "));
        assertPosition(-33.86, 151.21, PositionUtil.parse("-33.86 151.21"));
    }

    @Test
    void roundTripsFormattedString() {
        GeoPosition gp = new GeoPosition(35.681236, 139.767125);
        assertPosition(35.681236, 139.767125, PositionUtil.parse(PositionUtil.getPositionString(gp)));
    }

    @Test
    void parsesDegreesMinutesSeconds() {
        assertPosition(47 + 29 / 60.0 + 52.4 / 3600, 19 + 2 / 60.0 + 24 / 3600.0,
                PositionUtil.parse("47°29'52.4\"N 19°2'24\"E"));
        assertPosition(-(33 + 51 / 60.0), 151 + 12 / 60.0,
                PositionUtil.parse("33°51'S, 151°12'E"));
    }

    @Test
    void acceptsLongitudeFirstInDms() {
        assertPosition(40.5, -74.25, PositionUtil.parse("74°15'W 40°30'N"));
    }

    @Test
    void rejectsGarbageAndOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> PositionUtil.parse("hello"));
        assertThrows(IllegalArgumentException.class, () -> PositionUtil.parse("91;10"));
        assertThrows(IllegalArgumentException.class, () -> PositionUtil.parse("10;181"));
        assertThrows(IllegalArgumentException.class, () -> PositionUtil.parse("10°N 20°S"));
        assertThrows(IllegalArgumentException.class, () -> PositionUtil.parse(null));
    }
}

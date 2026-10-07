package me.rothens.gpsexif.gpx;

import org.junit.jupiter.api.Test;
import org.jxmapviewer.viewer.GeoPosition;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrackMatcherTest {

    private static final Instant T0 = Instant.parse("2026-09-30T08:00:00Z");
    private static final Duration GAP = Duration.ofMinutes(10);

    private static TrackPoint pt(int minutes, double lat, double lon, Double ele) {
        return new TrackPoint(T0.plus(Duration.ofMinutes(minutes)), new GeoPosition(lat, lon), ele);
    }

    private static Instant at(int minutes, int seconds) {
        return T0.plus(Duration.ofMinutes(minutes)).plusSeconds(seconds);
    }

    // Segment 1: 08:00-08:02 moving north, 08:30 after a 28-minute pause (no interpolation over it)
    // Segment 2: 09:00-09:01
    private final TrackMatcher matcher = new TrackMatcher(List.of(new Track("t", List.of(
            List.of(pt(0, 47.0, 19.0, 100.0), pt(2, 47.2, 19.0, 120.0), pt(30, 48.0, 19.0, null)),
            List.of(pt(60, 50.0, 20.0, null), pt(61, 50.0, 21.0, null))))));

    @Test
    void interpolatesBetweenPoints() {
        TrackMatcher.Match m = matcher.match(at(1, 0), GAP);
        assertTrue(m.isMatched());
        assertEquals(47.1, m.position().getLatitude(), 1e-9);
        assertEquals(19.0, m.position().getLongitude(), 1e-9);
        assertEquals(110.0, m.elevation(), 1e-9);
        assertEquals(Duration.ofMinutes(1), m.gap());
    }

    @Test
    void exactPointMatches() {
        TrackMatcher.Match m = matcher.match(at(2, 0), GAP);
        assertEquals(47.2, m.position().getLatitude(), 1e-9);
        assertEquals(Duration.ZERO, m.gap());
    }

    @Test
    void doesNotInterpolateOverLongPauseButUsesNearbyPoint() {
        TrackMatcher.Match nearStart = matcher.match(at(5, 0), GAP);
        assertEquals(47.2, nearStart.position().getLatitude(), 1e-9, "3 min after 08:02 -> that point");
        assertEquals(Duration.ofMinutes(3), nearStart.gap());

        TrackMatcher.Match middle = matcher.match(at(16, 0), GAP);
        assertFalse(middle.isMatched(), "14 min from both ends of the pause");
        assertEquals(Duration.ofMinutes(14), middle.gap());
    }

    @Test
    void neverInterpolatesAcrossSegments() {
        TrackMatcher.Match m = matcher.match(at(35, 0), GAP);
        assertEquals(48.0, m.position().getLatitude(), 1e-9, "5 min after the end of segment 1");
        assertFalse(matcher.match(at(45, 0), GAP).isMatched());
    }

    @Test
    void beforeAndAfterTheTrack() {
        assertEquals(47.0, matcher.match(at(-5, 0), GAP).position().getLatitude(), 1e-9);
        TrackMatcher.Match late = matcher.match(at(61 + 180, 0), GAP);
        assertFalse(late.isMatched());
        assertEquals(Duration.ofHours(3), late.gap());
    }

    @Test
    void interpolatesAcrossTheAntimeridian() {
        TrackMatcher m = new TrackMatcher(List.of(new Track("t", List.of(List.of(
                pt(0, 0, 179.0, null), pt(2, 0, -179.0, null))))));
        assertEquals(180.0, Math.abs(m.match(at(1, 0), GAP).position().getLongitude()), 1e-9);
        assertEquals(179.5, m.match(at(0, 30), GAP).position().getLongitude(), 1e-9);
        assertEquals(-179.5, m.match(at(1, 30), GAP).position().getLongitude(), 1e-9);
    }

    @Test
    void pointsWithoutTimeAreIgnored() {
        TrackMatcher m = new TrackMatcher(List.of(new Track("t", List.of(List.of(
                new TrackPoint(null, new GeoPosition(1, 1), null))))));
        assertFalse(m.hasTimedPoints());
        TrackMatcher.Match result = m.match(T0, GAP);
        assertFalse(result.isMatched());
        assertNull(result.gap());
    }

    @Test
    void nearestPointByDistance() {
        assertEquals(47.2, matcher.nearestPoint(new GeoPosition(47.25, 19.01)).position().getLatitude(), 1e-9);
        // Budapest -> Vienna is about 214 km
        double d = TrackMatcher.distanceMetres(new GeoPosition(47.4979, 19.0402), new GeoPosition(48.2082, 16.3738));
        assertEquals(214_000, d, 2_000);
    }
}

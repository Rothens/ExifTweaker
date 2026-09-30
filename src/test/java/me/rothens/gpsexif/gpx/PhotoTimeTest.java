package me.rothens.gpsexif.gpx;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class PhotoTimeTest {

    private static final LocalDateTime TAKEN = LocalDateTime.of(2026, 7, 1, 14, 0, 0);

    @Test
    void usesCameraZoneIncludingDaylightSaving() {
        assertEquals(Instant.parse("2026-07-01T12:00:00Z"),
                PhotoTime.toInstant(TAKEN, null, ZoneId.of("Europe/Budapest"), Duration.ZERO));
        assertEquals(Instant.parse("2026-01-01T13:00:00Z"), PhotoTime.toInstant(
                LocalDateTime.of(2026, 1, 1, 14, 0), null, ZoneId.of("Europe/Budapest"), Duration.ZERO));
    }

    @Test
    void recordedOffsetWinsOverCameraZone() {
        assertEquals(Instant.parse("2026-07-01T09:00:00Z"),
                PhotoTime.toInstant(TAKEN, ZoneOffset.ofHours(5), ZoneId.of("Europe/Budapest"), Duration.ZERO));
    }

    @Test
    void cameraAheadMeansRealTimeWasEarlier() {
        assertEquals(Instant.parse("2026-07-01T13:56:48Z"),
                PhotoTime.toInstant(TAKEN, ZoneOffset.UTC, ZoneId.of("UTC"), Duration.ofSeconds(192)));
    }

    @Test
    void offsetFromClockPhoto() {
        assertEquals(Duration.ofSeconds(192),
                PhotoTime.offsetFromClockPhoto(TAKEN, LocalTime.of(13, 56, 48)), "camera 3:12 ahead");
        assertEquals(Duration.ofMinutes(-5),
                PhotoTime.offsetFromClockPhoto(TAKEN, LocalTime.of(14, 5)), "camera 5 min behind");
        assertEquals(Duration.ofMinutes(2), PhotoTime.offsetFromClockPhoto(
                LocalDateTime.of(2026, 7, 2, 0, 1), LocalTime.of(23, 59)), "across midnight");
    }

    @Test
    void parsesAndFormatsOffsets() {
        assertEquals(Duration.ofSeconds(192), PhotoTime.parseOffset("+3:12"));
        assertEquals(Duration.ofSeconds(192), PhotoTime.parseOffset("3:12"));
        assertEquals(Duration.ofHours(-1), PhotoTime.parseOffset("-1:00:00"));
        assertEquals(Duration.ofMinutes(125), PhotoTime.parseOffset("2h 5m"));
        assertEquals(Duration.ofSeconds(-90), PhotoTime.parseOffset("-90s"));
        assertEquals(Duration.ZERO, PhotoTime.parseOffset(" 0 "));
        assertEquals(Duration.ZERO, PhotoTime.parseOffset(""));
        assertThrows(IllegalArgumentException.class, () -> PhotoTime.parseOffset("3:75"));
        assertThrows(IllegalArgumentException.class, () -> PhotoTime.parseOffset("1:60:00"));
        assertThrows(IllegalArgumentException.class, () -> PhotoTime.parseOffset("soon"));

        assertEquals("+0:03:12", PhotoTime.formatOffset(Duration.ofSeconds(192)));
        assertEquals("-1:00:00", PhotoTime.formatOffset(Duration.ofHours(-1)));
        for (String s : new String[]{"+0:03:12", "-1:00:00", "+26:00:05"}) {
            assertEquals(s, PhotoTime.formatOffset(PhotoTime.parseOffset(s)));
        }
    }

    @Test
    void describesGaps() {
        assertEquals("45 s", PhotoTime.describe(Duration.ofSeconds(45)));
        assertEquals("12 min", PhotoTime.describe(Duration.ofMinutes(12)));
        assertEquals("3 h 5 min", PhotoTime.describe(Duration.ofMinutes(185)));
        assertEquals("3 days", PhotoTime.describe(Duration.ofDays(3)));
    }
}

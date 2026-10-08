package me.rothens.gpsexif.gpx;

import me.rothens.gpsexif.metadata.CommonsImagingBackend;
import me.rothens.gpsexif.metadata.MetadataChanges;
import me.rothens.gpsexif.model.ImageFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jxmapviewer.viewer.GeoPosition;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PhotoTrackTest {

    private static final ZoneId BUDAPEST = ZoneId.of("Europe/Budapest");

    @TempDir
    Path dir;

    private ImageFile photo(String name, int hour, int minute, GeoPosition position, Double altitude)
            throws Exception {
        Path f = dir.resolve(name);
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", f.toFile());
        ImageFile image = new ImageFile(f.toFile(), new CommonsImagingBackend());
        MetadataChanges changes = new MetadataChanges().taken(LocalDateTime.of(2026, 7, 11, hour, minute));
        if (null != position) {
            changes.position(position);
        }
        if (null != altitude) {
            changes.altitude(altitude);
        }
        image.apply(changes);
        return image;
    }

    @Test
    void phonePhotosPlaceTheCameraPhotosBetweenThem() throws Exception {
        GeoPosition abbey = new GeoPosition(46.9137, 17.8893);
        GeoPosition port = new GeoPosition(46.9089, 17.8960);
        // The phone's photos (with a location), in any order; one without a location is ignored
        List<ImageFile> phone = List.of(
                photo("phone2.jpg", 15, 0, port, 106.0),
                photo("phone1.jpg", 14, 0, abbey, 130.0),
                photo("phone-noloc.jpg", 14, 30, null, null));
        Track track = PhotoTrack.of("phone", phone, BUDAPEST);
        assertNotNull(track);
        assertEquals(2, track.pointCount());
        assertEquals(Instant.parse("2026-07-11T12:00:00Z"), track.start(), "14:00 in Budapest (summer time)");
        assertEquals(2, PhotoTrack.usable(phone));

        // A camera photo at 14:30 lands halfway, with the altitude in between
        ImageFile camera = photo("camera.jpg", 14, 30, null, null);
        TrackMatcher matcher = new TrackMatcher(List.of(track));
        Instant time = PhotoTime.toInstant(camera.getTaken(), camera.getTakenOffset(), BUDAPEST, Duration.ZERO);
        TrackMatcher.Match match = matcher.match(time, Duration.ofHours(1));
        assertTrue(match.isMatched());
        assertEquals((abbey.getLatitude() + port.getLatitude()) / 2, match.position().getLatitude(), 1e-6);
        assertEquals(118, match.elevation(), 0.5);
        // Too far apart for a 10-minute limit: unmatched (the dialog then suggests a larger Max. time)
        assertFalse(matcher.match(time, Duration.ofMinutes(10)).isMatched());
    }

    @Test
    void noUsablePhotosNoTrack() throws Exception {
        assertNull(PhotoTrack.of("phone", List.of(photo("a.jpg", 9, 0, null, null)), BUDAPEST));
    }
}

package me.rothens.gpsexif.playback;

import me.rothens.gpsexif.gpx.Track;
import me.rothens.gpsexif.gpx.TrackPoint;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TravelTimelineTest {

    @TempDir
    Path dir;

    private static final GeoPosition HOME = new GeoPosition(47.50, 19.05);
    private static final GeoPosition PARK = new GeoPosition(47.545, 19.05);    // 5 km north
    private static final GeoPosition HOTEL = new GeoPosition(47.59, 19.05);    // another 5 km
    private static final GeoPosition BREAKFAST = new GeoPosition(47.599, 19.05); // 1 km from the hotel
    private static final GeoPosition LAKE = new GeoPosition(46.90, 17.90);     // ~110 km away

    private static final TravelTimeline.Settings SETTINGS = new TravelTimeline.Settings(Duration.ofSeconds(60),
            Duration.ofSeconds(2), Duration.ofMillis(500), true, Duration.ofHours(1), 2000, Duration.ofSeconds(2));

    private ImageFile photo(String name, LocalDateTime taken, GeoPosition position) throws Exception {
        Path f = dir.resolve(name);
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", f.toFile());
        ImageFile image = new ImageFile(f.toFile(), new CommonsImagingBackend());
        MetadataChanges changes = new MetadataChanges().taken(taken);
        if (null != position) {
            changes.position(position);
        }
        image.apply(changes);
        return image;
    }

    private static Instant utc(ImageFile p) {
        return p.getTaken().toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime day(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 10, day, hour, minute);
    }

    /** Day 1: home 09:00, park 09:30, hotel 10:00. Night. Day 2: breakfast 09:00 near the hotel, lake 12:00. */
    private List<ImageFile> trip() throws Exception {
        return List.of(photo("a.jpg", day(1, 9, 0), HOME), photo("b.jpg", day(1, 9, 30), PARK),
                photo("c.jpg", day(1, 10, 0), HOTEL), photo("d.jpg", day(2, 9, 0), BREAKFAST),
                photo("e.jpg", day(2, 12, 0), LAKE));
    }

    @Test
    void nightsAreSqueezedAndTravelGetsTheVideoTime() throws Exception {
        TravelTimeline t = new TravelTimeline(trip(), TravelTimelineTest::utc, List.of(), SETTINGS);
        assertEquals(1, t.getSqueezedStays(), "hotel -> breakfast: 23 h within 1 km");
        // 58 s available (the last photo keeps 2 s); the stay takes 2 s, 56 s for 4 h of travel
        List<Double> v = t.getStops().stream().map(TravelTimeline.Stop::videoTime).toList();
        assertEquals(0, v.get(0), 1e-9);
        assertEquals(7, v.get(1), 1e-9);
        assertEquals(14, v.get(2), 1e-9);
        assertEquals(16, v.get(3), 1e-9);
        assertEquals(58, v.get(4), 1e-9);
        assertEquals(14400 / 56.0, t.getRealSecondsPerVideoSecond(), 1e-6);
        assertEquals(5, t.getSlots().size());

        TravelTimeline.Slot hotel = t.getSlots().get(2);
        assertEquals(Duration.ofHours(23), hotel.pause());
        assertFalse(hotel.showMap());
        assertEquals("+23 h", t.frameAt(15.9).caption(), "the stay shows a caption, not the map");

        TravelTimeline.Slot breakfast = t.getSlots().get(3);
        assertTrue(breakfast.showMap(), "110 km to the lake: travel on the map");
        TravelTimeline.Frame onTheRoad = t.frameAt(37);
        assertNull(onTheRoad.visible(), "the full-screen map is showing");
        assertNull(onTheRoad.caption());
        // Halfway between breakfast (16 s) and the lake (58 s) the marker is halfway there
        assertEquals((BREAKFAST.getLatitude() + LAKE.getLatitude()) / 2, onTheRoad.marker().getLatitude(), 1e-6);
        assertEquals(BREAKFAST.getLatitude(), onTheRoad.photoAt().getLatitude(), 1e-6, "dot at the last photo");
        assertEquals(Instant.parse("2026-10-02T10:30:00Z"), onTheRoad.time());
    }

    @Test
    void crossFadesBetweenPhotosAndToTheMap() throws Exception {
        TravelTimeline t = new TravelTimeline(trip(), TravelTimelineTest::utc, List.of(), SETTINGS);
        TravelTimeline.Frame start = t.frameAt(0);
        assertEquals("a.jpg", start.visible().getFile().getName());
        assertEquals(1, start.alpha(), 1e-9);

        // Slot A (0-7 s) goes to the map after its 2 s; slot B starts at 7 s fading in from the map
        TravelTimeline.Frame toMap = t.frameAt(1.75);
        assertEquals("a.jpg", toMap.from().getFile().getName());
        assertNull(toMap.to());
        assertEquals(0.5, toMap.alpha(), 1e-9);
        TravelTimeline.Frame fromMap = t.frameAt(7.25);
        assertNull(fromMap.from());
        assertEquals("b.jpg", fromMap.to().getFile().getName());
        assertEquals(0.5, fromMap.alpha(), 1e-9);

        // Slot C (hotel, 14-16 s) fades straight into D's photo: no map in between
        TravelTimeline.Frame photoToPhoto = t.frameAt(16.25);
        assertEquals("c.jpg", photoToPhoto.from().getFile().getName());
        assertEquals("d.jpg", photoToPhoto.to().getFile().getName());
        assertEquals("e.jpg", t.frameAt(60).visible().getFile().getName(), "ends on the last photo");
    }

    @Test
    void burstShowsTheMiddlePhoto() throws Exception {
        List<ImageFile> photos = new ArrayList<>();
        photos.add(photo("start.jpg", day(1, 9, 0), HOME));
        for (int i = 0; i < 5; i++) {
            photos.add(photo("burst" + i + ".jpg", day(1, 10, i), PARK)); // 5 photos within 4 minutes
        }
        photos.add(photo("end.jpg", day(1, 11, 0), HOTEL));
        TravelTimeline t = new TravelTimeline(photos, TravelTimelineTest::utc, List.of(), SETTINGS);
        List<String> shown = t.getSlots().stream().map(s -> s.stop().photo().getFile().getName()).toList();
        assertEquals(List.of("start.jpg", "burst2.jpg", "end.jpg"), shown);
        assertTrue(t.summary().startsWith("Shows 3 of 7 photos"), t.summary());
        for (int i = 1; i < t.getSlots().size(); i++) {
            assertTrue(t.getSlots().get(i).start() - t.getSlots().get(i - 1).start() >= 2 - 1e-9,
                    "every shown photo gets its minimum time");
        }
    }

    @Test
    void markerFollowsGpxTrack() throws Exception {
        // The track makes a detour east between the park and the hotel
        GeoPosition detour = new GeoPosition(47.5675, 19.20);
        Track track = new Track("t", List.of(List.of(
                new TrackPoint(day(1, 9, 30).toInstant(ZoneOffset.UTC), PARK, null),
                new TrackPoint(day(1, 9, 45).toInstant(ZoneOffset.UTC), detour, null),
                new TrackPoint(day(1, 10, 0).toInstant(ZoneOffset.UTC), HOTEL, null))));
        List<ImageFile> photos = trip();
        TravelTimeline gpx = new TravelTimeline(photos, TravelTimelineTest::utc, List.of(track), SETTINGS);
        TravelTimeline straight = new TravelTimeline(photos, TravelTimelineTest::utc, List.of(), SETTINGS);
        assertTrue(gpx.usesTrack());
        GeoPosition viaTrack = gpx.markerAt(day(1, 9, 45).toInstant(ZoneOffset.UTC));
        GeoPosition direct = straight.markerAt(day(1, 9, 45).toInstant(ZoneOffset.UTC));
        assertEquals(19.20, viaTrack.getLongitude(), 1e-9);
        assertEquals(19.05, direct.getLongitude(), 1e-9);
    }

    @Test
    void withoutSqueezingNightsTakeTheirRealShare() throws Exception {
        TravelTimeline.Settings noSqueeze = new TravelTimeline.Settings(Duration.ofSeconds(60), Duration.ofSeconds(2),
                Duration.ofMillis(500), false, Duration.ofHours(1), 2000, Duration.ofSeconds(2));
        TravelTimeline t = new TravelTimeline(trip(), TravelTimelineTest::utc, List.of(), noSqueeze);
        assertEquals(0, t.getSqueezedStays());
        // 1 h day 1 + 23 h night + 3 h day 2 = 27 h over 58 s: the night takes 23/27 of the video
        double night = t.getStops().get(3).videoTime() - t.getStops().get(2).videoTime();
        assertEquals(58 * 23 / 27.0, night, 1e-6);
    }

    @Test
    void emptyAndSinglePhoto() throws Exception {
        assertTrue(new TravelTimeline(List.of(), TravelTimelineTest::utc, List.of(), SETTINGS).isEmpty());
        TravelTimeline one = new TravelTimeline(List.of(photo("x.jpg", day(1, 9, 0), HOME)),
                TravelTimelineTest::utc, List.of(), SETTINGS);
        assertEquals(1, one.getSlots().size());
        assertEquals("x.jpg", one.frameAt(30).visible().getFile().getName());
    }

    @Test
    void photoAfterANightIsNeverMergedIntoTheOneBefore() throws Exception {
        // Minimum photo time (2.5 s) longer than the squeezed stop (2 s): the stop is stretched to 2.5 s
        TravelTimeline.Settings s = new TravelTimeline.Settings(Duration.ofSeconds(20), Duration.ofMillis(2500),
                Duration.ofMillis(500), true, Duration.ofHours(1), 2000, Duration.ofSeconds(2));
        TravelTimeline t = new TravelTimeline(trip(), TravelTimelineTest::utc, List.of(), s);
        List<String> shown = t.getSlots().stream().map(x -> x.stop().photo().getFile().getName()).toList();
        assertTrue(shown.contains("c.jpg") && shown.contains("d.jpg"), shown.toString());
        TravelTimeline.Slot hotel = t.getSlots().get(shown.indexOf("c.jpg"));
        assertEquals(Duration.ofHours(23), hotel.pause());
        assertEquals(2.5, hotel.end() - hotel.start(), 1e-9);
    }
}

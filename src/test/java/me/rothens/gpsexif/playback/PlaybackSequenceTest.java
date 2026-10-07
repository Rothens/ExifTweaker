package me.rothens.gpsexif.playback;

import me.rothens.gpsexif.metadata.CommonsImagingBackend;
import me.rothens.gpsexif.metadata.MetadataChanges;
import me.rothens.gpsexif.model.ImageFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jxmapviewer.viewer.GeoPosition;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackSequenceTest {

    @TempDir
    Path dir;

    private ImageFile photo(String name, Integer hour, GeoPosition position) throws Exception {
        Path f = dir.resolve(name);
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", f.toFile());
        ImageFile image = new ImageFile(f.toFile(), new CommonsImagingBackend());
        MetadataChanges changes = new MetadataChanges();
        if (null != hour) {
            changes.taken(LocalDateTime.of(2026, 9, 30, hour, 0));
        }
        if (null != position) {
            changes.position(position);
        }
        image.apply(changes);
        return image;
    }

    /** EXIF stores coordinates as fractions, so they come back very slightly different. */
    private static void assertNear(GeoPosition expected, GeoPosition actual) {
        assertEquals(expected.getLatitude(), actual.getLatitude(), 1e-6);
        assertEquals(expected.getLongitude(), actual.getLongitude(), 1e-6);
    }

    @Test
    void ordersByTimeSkipsUndatedAndCarriesLocationForward() throws Exception {
        GeoPosition home = new GeoPosition(47.5, 19.05);
        GeoPosition lake = new GeoPosition(46.9, 17.9);
        ImageFile late = photo("a.jpg", 15, lake);
        ImageFile early = photo("b.jpg", 9, home);
        ImageFile noLocation = photo("c.jpg", 12, null);
        ImageFile noDate = photo("d.jpg", null, home);

        PlaybackSequence seq = new PlaybackSequence(List.of(late, early, noLocation, noDate));
        assertEquals(3, seq.size());
        assertEquals(1, seq.getWithoutDate());
        assertEquals(2, seq.getRoute().size());
        assertNear(home, seq.getRoute().get(0));
        assertNear(lake, seq.getRoute().get(1));

        assertSame(early, seq.current());
        assertNear(home, seq.mapPosition());
        assertTrue(seq.next(false));
        assertSame(noLocation, seq.current());
        assertNear(home, seq.mapPosition()); // no location: the map stays where it was
        assertFalse(seq.hasOwnLocation());
        assertTrue(seq.next(false));
        assertSame(late, seq.current());
        assertNear(lake, seq.mapPosition());

        assertFalse(seq.next(false), "stops at the end");
        assertSame(late, seq.current());
        assertTrue(seq.next(true), "loops");
        assertSame(early, seq.current());
        seq.previous();
        assertSame(early, seq.current(), "can't go before the first");
        seq.seek(99);
        assertSame(late, seq.current());
    }

    @Test
    void firstPhotosWithoutLocationHaveNoMapPosition() throws Exception {
        PlaybackSequence seq = new PlaybackSequence(List.of(photo("a.jpg", 9, null), photo("b.jpg", 10, new GeoPosition(1, 2))));
        assertNull(seq.mapPosition());
        seq.next(false);
        assertNotNull(seq.mapPosition());
    }
}

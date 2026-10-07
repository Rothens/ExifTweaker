package me.rothens.gpsexif.playback;

import me.rothens.gpsexif.metadata.CommonsImagingBackend;
import me.rothens.gpsexif.metadata.MetadataChanges;
import me.rothens.gpsexif.model.ImageFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class ClockZoneTest {

    private static final ZoneId BUDAPEST = ZoneId.of("Europe/Budapest");
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    @TempDir
    Path dir;

    private ImageFile photo(LocalDateTime taken) throws Exception {
        Path f = dir.resolve("p.jpg");
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", f.toFile());
        ImageFile image = new ImageFile(f.toFile(), new CommonsImagingBackend());
        image.apply(new MetadataChanges().taken(taken));
        return image;
    }

    @Test
    void cameraClockShowsTheTimeAsRecorded() throws Exception {
        ImageFile p = photo(LocalDateTime.of(2026, 10, 1, 9, 0));
        ClockZone clock = new ClockZone(BUDAPEST, null);
        assertEquals(LocalDateTime.of(2026, 10, 1, 9, 0), clock.local(p));
        assertNull(clock.offsetLabel(p));
        assertEquals(Instant.parse("2026-10-01T07:00:00Z"), clock.instant(p));
    }

    @Test
    void aChosenZoneConvertsFromTheCameraZone() throws Exception {
        // Camera left on Budapest time (UTC+2 in summer) on a trip to Tokyo (UTC+9)
        ImageFile p = photo(LocalDateTime.of(2026, 10, 1, 9, 0));
        ClockZone clock = new ClockZone(BUDAPEST, TOKYO);
        assertEquals(LocalDateTime.of(2026, 10, 1, 16, 0), clock.local(p));
        assertEquals("UTC+9", clock.offsetLabel(p));
        assertEquals(LocalDateTime.of(2026, 10, 1, 16, 0), clock.local(Instant.parse("2026-10-01T07:00:00Z")));
    }

    @Test
    void offsetsAreFormattedShortly() {
        assertEquals("UTC", ClockZone.formatOffset(ZoneOffset.UTC));
        assertEquals("UTC-5", ClockZone.formatOffset(ZoneOffset.ofHours(-5)));
        assertEquals("UTC+5:30", ClockZone.formatOffset(ZoneOffset.ofHoursMinutes(5, 30)));
    }

    @Test
    void chooserStartsWithCameraClockAndRoundTrips() {
        JComboBox<String> chooser = ClockZone.createChooser(BUDAPEST, null);
        assertEquals(ClockZone.CAMERA_CLOCK, chooser.getItemAt(0));
        assertNull(ClockZone.selected(chooser));
        chooser.setSelectedItem("Asia/Tokyo");
        assertEquals(TOKYO, ClockZone.selected(chooser));
        assertEquals(TOKYO, ClockZone.selected(ClockZone.createChooser(BUDAPEST, TOKYO)));
        assertEquals(ZoneId.of("UTC"), ClockZone.selected(ClockZone.createChooser(BUDAPEST, ZoneId.of("UTC"))));
    }
}

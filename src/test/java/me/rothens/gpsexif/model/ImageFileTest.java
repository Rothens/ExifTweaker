package me.rothens.gpsexif.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jxmapviewer.viewer.GeoPosition;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ImageFileTest {

    @TempDir
    Path dir;

    private File createJpeg(String name) throws IOException {
        File f = dir.resolve(name).toFile();
        ImageIO.write(new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB), "jpg", f);
        return f;
    }

    @Test
    void imageWithoutExifHasNoPosition() throws IOException {
        ImageFile image = new ImageFile(createJpeg("plain.jpg"));
        assertFalse(image.hasExifGPS());
        assertTrue(image.getExifData().isEmpty());
    }

    @Test
    void savedPositionCanBeReadBack() throws IOException {
        File f = createJpeg("photo.jpg");
        ImageFile image = new ImageFile(f);
        image.setGp(new GeoPosition(-33.8568, 151.2153));
        image.save();

        ImageFile reloaded = new ImageFile(f);
        assertTrue(reloaded.hasExifGPS());
        assertEquals(-33.8568, reloaded.getGp().getLatitude(), 1e-4);
        assertEquals(151.2153, reloaded.getGp().getLongitude(), 1e-4);

        // Saving again over existing EXIF data must work too
        reloaded.setGp(new GeoPosition(47.4979, 19.0402));
        reloaded.save();
        assertEquals(47.4979, new ImageFile(f).getGp().getLatitude(), 1e-4);
    }

    @Test
    void saveLeavesNoTemporaryFilesBehind() throws IOException {
        ImageFile image = new ImageFile(createJpeg("photo.jpg"));
        image.setGp(new GeoPosition(1, 2));
        image.save();
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
    }
}

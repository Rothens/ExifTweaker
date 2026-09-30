package me.rothens.gpsexif.model;

import me.rothens.gpsexif.metadata.CommonsImagingBackend;
import me.rothens.gpsexif.metadata.MetadataBackend;
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

    private final MetadataBackend backend = new CommonsImagingBackend();

    @TempDir
    Path dir;

    private File createJpeg(String name) throws IOException {
        File f = dir.resolve(name).toFile();
        ImageIO.write(new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB), "jpg", f);
        return f;
    }

    @Test
    void imageWithoutExifHasNoPosition() throws IOException {
        ImageFile image = new ImageFile(createJpeg("plain.jpg"), backend);
        assertFalse(image.hasExifGPS());
        assertTrue(image.getExifData().isEmpty());
    }

    @Test
    void savedPositionCanBeReadBack() throws IOException {
        File f = createJpeg("photo.jpg");
        ImageFile image = new ImageFile(f, backend);
        image.savePosition(new GeoPosition(-33.8568, 151.2153));
        assertEquals(-33.8568, image.getGp().getLatitude(), 1e-4, "model is refreshed after saving");

        ImageFile reloaded = new ImageFile(f, backend);
        assertTrue(reloaded.hasExifGPS());
        assertEquals(-33.8568, reloaded.getGp().getLatitude(), 1e-4);
        assertEquals(151.2153, reloaded.getGp().getLongitude(), 1e-4);

        // Saving again over existing EXIF data must work too
        reloaded.savePosition(new GeoPosition(47.4979, 19.0402));
        assertEquals(47.4979, new ImageFile(f, backend).getGp().getLatitude(), 1e-4);
    }

    @Test
    void saveLeavesNoTemporaryFilesBehind() throws IOException {
        ImageFile image = new ImageFile(createJpeg("photo.jpg"), backend);
        image.savePosition(new GeoPosition(1, 2));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
    }
}

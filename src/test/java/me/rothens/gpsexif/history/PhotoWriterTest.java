package me.rothens.gpsexif.history;

import me.rothens.gpsexif.metadata.CommonsImagingBackend;
import me.rothens.gpsexif.model.ImageFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jxmapviewer.viewer.GeoPosition;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PhotoWriterTest {

    @TempDir
    Path dir;

    private final EditHistory history = new EditHistory();

    @AfterEach
    void tearDown() {
        history.close();
    }

    private ImageFile createImage() throws IOException {
        Path f = dir.resolve("photo.jpg");
        ImageIO.write(new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB), "jpg", f.toFile());
        return new ImageFile(f.toFile(), new CommonsImagingBackend());
    }

    @Test
    void backupKeepsTheTrueOriginal() throws IOException {
        ImageFile image = createImage();
        byte[] original = Files.readAllBytes(image.getPath());
        PhotoWriter writer = new PhotoWriter(history, () -> true);

        writer.savePosition(image, new GeoPosition(1, 2));
        writer.savePosition(image, new GeoPosition(3, 4));

        Path backup = PhotoWriter.backupPath(image.getPath());
        assertEquals("photo.jpg.bak", backup.getFileName().toString());
        assertArrayEquals(original, Files.readAllBytes(backup));
    }

    @Test
    void noBackupWhenDisabled() throws IOException {
        ImageFile image = createImage();
        new PhotoWriter(history, () -> false).savePosition(image, new GeoPosition(1, 2));
        assertFalse(Files.exists(PhotoWriter.backupPath(image.getPath())));
    }

    @Test
    void saveCanBeUndone() throws IOException {
        ImageFile image = createImage();
        byte[] original = Files.readAllBytes(image.getPath());
        new PhotoWriter(history, () -> false).savePosition(image, new GeoPosition(1, 2));
        assertTrue(image.hasExifGPS());

        history.undo();
        image.reload();

        assertArrayEquals(original, Files.readAllBytes(image.getPath()));
        assertFalse(image.hasExifGPS());
    }
}

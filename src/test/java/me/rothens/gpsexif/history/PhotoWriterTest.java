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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
        return createImage("photo.jpg");
    }

    private ImageFile createImage(String name) throws IOException {
        Path f = dir.resolve(name);
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

    @Test
    void batchContinuesPastFailuresAndUndoesAsOneStep() throws IOException {
        ImageFile a = createImage("a.jpg");
        ImageFile b = createImage("b.jpg");
        Path brokenPath = dir.resolve("broken.jpg");
        Files.writeString(brokenPath, "not a jpeg");
        ImageFile broken = new ImageFile(brokenPath.toFile(), new CommonsImagingBackend());
        ImageFile c = createImage("c.jpg");
        AtomicInteger lastProgress = new AtomicInteger();

        PhotoWriter.Result result = new PhotoWriter(history, () -> false).apply("batch", List.of(a, b, broken, c),
                image -> image.savePosition(new GeoPosition(1, 2)), true, (done, total) -> lastProgress.set(done));

        assertEquals(List.of(a, b, c), result.changed());
        assertEquals(List.of(broken), List.copyOf(result.failures().keySet()));
        assertFalse(result.isComplete());
        assertEquals(4, lastProgress.get());
        assertTrue(a.hasExifGPS() && b.hasExifGPS() && c.hasExifGPS());

        assertEquals("batch", history.getUndoDescription());
        history.undo();
        for (ImageFile image : List.of(a, b, c)) {
            image.reload();
            assertFalse(image.hasExifGPS(), image.toString());
        }
        assertFalse(history.canUndo(), "the whole batch is one undo step");
    }

    @Test
    void cancelledBatchKeepsWhatWasDone() throws IOException {
        ImageFile a = createImage("a.jpg");
        ImageFile b = createImage("b.jpg");
        ImageFile c = createImage("c.jpg");
        AtomicInteger done = new AtomicInteger();
        PhotoWriter.Progress cancelAfterOne = new PhotoWriter.Progress() {
            @Override
            public void update(int d, int total) {
                done.set(d);
            }

            @Override
            public boolean isCancelled() {
                return done.get() >= 1;
            }
        };

        PhotoWriter.Result result = new PhotoWriter(history, () -> false).apply("batch", List.of(a, b, c),
                image -> image.savePosition(new GeoPosition(1, 2)), true, cancelAfterOne);

        assertEquals(List.of(a), result.changed());
        assertEquals(2, result.skipped());
        assertTrue(history.canUndo());
    }

    @Test
    void batchWithoutUndoClearsHistory() throws IOException {
        ImageFile a = createImage("a.jpg");
        ImageFile b = createImage("b.jpg");
        PhotoWriter writer = new PhotoWriter(history, () -> false);
        writer.savePosition(a, new GeoPosition(1, 2));
        assertTrue(history.canUndo());

        writer.apply("big batch", List.of(a, b), ImageFile::removePosition, false, (d, t) -> { });

        assertFalse(history.canUndo());
        assertFalse(a.hasExifGPS());
    }

    @Test
    void undoBudgetIsCheckedAgainstFileSizes() throws IOException {
        ImageFile a = createImage("a.jpg");
        long size = Files.size(a.getPath());
        EditHistory small = new EditHistory(20, size * 2);
        PhotoWriter writer = new PhotoWriter(small, () -> false);
        assertTrue(writer.canUndo(List.of(a)));
        assertFalse(writer.canUndo(List.of(a, createImage("b.jpg"), createImage("c.jpg"))));
        small.close();
    }
}

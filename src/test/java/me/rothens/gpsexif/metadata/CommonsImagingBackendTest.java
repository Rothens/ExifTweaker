package me.rothens.gpsexif.metadata;

import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jxmapviewer.viewer.GeoPosition;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CommonsImagingBackendTest {

    private final CommonsImagingBackend backend = new CommonsImagingBackend();

    @TempDir
    Path dir;

    private Path createJpeg(String name) throws Exception {
        Path f = dir.resolve(name);
        ImageIO.write(new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB), "jpg", f.toFile());
        return f;
    }

    @Test
    void supportsJpegOnly() {
        assertTrue(backend.canRead(Path.of("a.jpg")));
        assertTrue(backend.canWrite(Path.of("B.JPEG")));
        assertFalse(backend.canRead(Path.of("c.png")));
        assertFalse(backend.canWrite(Path.of("d.heic")));
    }

    @Test
    void readsEmptyMetadataFromPlainJpeg() throws Exception {
        PhotoMetadata metadata = backend.read(createJpeg("plain.jpg"));
        assertNull(metadata.position());
        assertEquals(1, metadata.orientation());
        assertTrue(metadata.fields().isEmpty());
    }

    @Test
    void writesToTargetWithoutTouchingSource() throws Exception {
        Path source = createJpeg("src.jpg");
        byte[] before = Files.readAllBytes(source);
        Path target = dir.resolve("out.jpg");

        backend.writePosition(source, target, new GeoPosition(10.5, -20.25));

        assertArrayEquals(before, Files.readAllBytes(source));
        GeoPosition written = backend.read(target).position();
        assertEquals(10.5, written.getLatitude(), 1e-4);
        assertEquals(-20.25, written.getLongitude(), 1e-4);
    }

    @Test
    void readsOrientationAndKeepsItWhenWritingPosition() throws Exception {
        Path plain = createJpeg("plain.jpg");
        Path rotated = dir.resolve("rotated.jpg");
        TiffOutputSet outputSet = new TiffOutputSet();
        outputSet.getOrCreateRootDirectory().add(TiffTagConstants.TIFF_TAG_ORIENTATION, (short) 6);
        try (OutputStream os = Files.newOutputStream(rotated)) {
            new ExifRewriter().updateExifMetadataLossless(plain.toFile(), os, outputSet);
        }
        assertEquals(6, backend.read(rotated).orientation());

        Path tagged = dir.resolve("tagged.jpg");
        backend.writePosition(rotated, tagged, new GeoPosition(1, 2));
        PhotoMetadata metadata = backend.read(tagged);
        assertEquals(6, metadata.orientation());
        assertNotNull(metadata.position());
    }

    @Test
    void removesPositionButKeepsOtherMetadata() throws Exception {
        Path plain = createJpeg("plain.jpg");
        Path rotated = dir.resolve("rotated.jpg");
        TiffOutputSet outputSet = new TiffOutputSet();
        outputSet.getOrCreateRootDirectory().add(TiffTagConstants.TIFF_TAG_ORIENTATION, (short) 3);
        try (OutputStream os = Files.newOutputStream(rotated)) {
            new ExifRewriter().updateExifMetadataLossless(plain.toFile(), os, outputSet);
        }
        Path tagged = dir.resolve("tagged.jpg");
        backend.writePosition(rotated, tagged, new GeoPosition(1, 2));
        assertNotNull(backend.read(tagged).position());

        Path cleaned = dir.resolve("cleaned.jpg");
        backend.removePosition(tagged, cleaned);

        PhotoMetadata metadata = backend.read(cleaned);
        assertNull(metadata.position());
        assertEquals(3, metadata.orientation());
        // The position can be written again afterwards
        Path retagged = dir.resolve("retagged.jpg");
        backend.writePosition(cleaned, retagged, new GeoPosition(5, 6));
        assertEquals(5, backend.read(retagged).position().getLatitude(), 1e-4);
    }

    @Test
    void removingFromPhotoWithoutExifIsHarmless() throws Exception {
        Path plain = createJpeg("plain.jpg");
        Path out = dir.resolve("out.jpg");
        backend.removePosition(plain, out);
        assertNull(backend.read(out).position());
    }
}

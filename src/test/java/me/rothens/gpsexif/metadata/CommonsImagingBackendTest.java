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

    private Path withExif(String name, java.util.function.Consumer<TiffOutputSet> tags) throws Exception {
        Path plain = createJpeg("plain-" + name);
        Path out = dir.resolve(name);
        TiffOutputSet outputSet = new TiffOutputSet();
        tags.accept(outputSet);
        try (OutputStream os = Files.newOutputStream(out)) {
            new ExifRewriter().updateExifMetadataLossless(plain.toFile(), os, outputSet);
        }
        return out;
    }

    private static void add(TiffOutputSet set, org.apache.commons.imaging.formats.tiff.taginfos.TagInfoAscii tag,
                            String value) {
        try {
            set.getOrCreateExifDirectory().add(tag, value);
        } catch (org.apache.commons.imaging.ImagingException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void readsCaptureTimeWithSubSecondsAndOffset() throws Exception {
        var offsetTag = new org.apache.commons.imaging.formats.tiff.taginfos.TagInfoAscii("OffsetTimeOriginal",
                0x9011, 7, org.apache.commons.imaging.formats.tiff.constants.TiffDirectoryType.EXIF_DIRECTORY_EXIF_IFD);
        Path photo = withExif("timed.jpg", set -> {
            add(set, org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL,
                    "2026:09:30 10:15:30");
            add(set, org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants.EXIF_TAG_SUB_SEC_TIME_ORIGINAL,
                    "25");
            add(set, offsetTag, "+02:00");
        });
        PhotoMetadata metadata = backend.read(photo);
        assertEquals(java.time.LocalDateTime.of(2026, 9, 30, 10, 15, 30, 250_000_000), metadata.taken());
        assertEquals(java.time.ZoneOffset.ofHours(2), metadata.takenOffset());
    }

    @Test
    void fallsBackToDigitizedTimeAndIgnoresUnsetClocks() throws Exception {
        Path digitized = withExif("digitized.jpg", set -> add(set,
                org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants.EXIF_TAG_DATE_TIME_DIGITIZED,
                "2025:01:02 03:04:05"));
        assertEquals(java.time.LocalDateTime.of(2025, 1, 2, 3, 4, 5), backend.read(digitized).taken());
        assertNull(backend.read(digitized).takenOffset());

        Path unset = withExif("unset.jpg", set -> add(set,
                org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL,
                "0000:00:00 00:00:00"));
        assertNull(backend.read(unset).taken());
    }

    @Test
    void writesAltitudeAboveAndBelowSeaLevel() throws Exception {
        Path plain = createJpeg("plain.jpg");
        Path high = dir.resolve("high.jpg");
        backend.writePosition(plain, high, new GeoPosition(46.5, 7.9), 3454.5);
        assertEquals(3454.5, backend.read(high).altitude(), 1e-6);

        Path low = dir.resolve("low.jpg");
        backend.writePosition(high, low, new GeoPosition(31.5, 35.5), -430.0);
        assertEquals(-430.0, backend.read(low).altitude(), 1e-6);

        Path moved = dir.resolve("moved.jpg");
        backend.writePosition(low, moved, new GeoPosition(31.6, 35.6));
        assertEquals(-430.0, backend.read(moved).altitude(), 1e-6, "no altitude given: existing one is kept");
        assertNull(backend.read(plain).altitude());
    }
}

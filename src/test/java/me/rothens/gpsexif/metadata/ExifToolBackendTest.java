package me.rothens.gpsexif.metadata;

import me.rothens.gpsexif.history.EditHistory;
import me.rothens.gpsexif.history.PhotoWriter;
import me.rothens.gpsexif.model.ImageFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jxmapviewer.viewer.GeoPosition;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs against a real ExifTool; skipped when it isn't installed. */
class ExifToolBackendTest {

    private static ExifTool exifTool;
    private static ExifToolBackend backend;

    @TempDir
    Path dir;

    @BeforeAll
    static void start() {
        String executable = ExifTool.locate(null);
        if (null != executable) {
            exifTool = new ExifTool(executable);
            backend = new ExifToolBackend(exifTool);
        }
    }

    @AfterAll
    static void stop() {
        if (null != exifTool) {
            exifTool.close();
        }
    }

    @BeforeEach
    void requireExifTool() {
        assumeTrue(null != backend, "ExifTool not installed");
    }

    private Path image(String name, String format) throws Exception {
        Path f = dir.resolve(name);
        ImageIO.write(new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB), format, f.toFile());
        return f;
    }

    private Path write(Path photo, String out, MetadataChanges changes) throws Exception {
        Path target = dir.resolve(out);
        Files.createFile(target); // like the caller's temp file
        backend.write(photo, target, changes);
        return target;
    }

    @Test
    void writesEverythingInPlaceForPngAndTiff() throws Exception {
        for (String format : new String[]{"png", "tiff"}) {
            Path photo = image("a." + format, format);
            Path out = write(photo, "out." + format, new MetadataChanges()
                    .position(new GeoPosition(-33.8568, 151.2153)).altitude(-12.5).direction(270.0)
                    .text(TextTag.ARTIST, "Máté Dávid").text(TextTag.DESCRIPTION, "line one\nline\ttwo \\ \"q\"")
                    .taken(LocalDateTime.of(2026, 9, 30, 23, 30)));
            PhotoMetadata m = backend.read(out);
            assertEquals(-33.8568, m.position().getLatitude(), 1e-6, format);
            assertEquals(151.2153, m.position().getLongitude(), 1e-6, format);
            assertEquals(-12.5, m.altitude(), 1e-6, format);
            assertEquals(270.0, m.direction(), 1e-6, format);
            assertEquals("Máté Dávid", m.text().get(TextTag.ARTIST), format);
            assertEquals("line one\nline\ttwo \\ \"q\"", m.text().get(TextTag.DESCRIPTION), format);
            assertEquals(LocalDateTime.of(2026, 9, 30, 23, 30), m.taken(), format);

            Path shifted = write(out, "shifted." + format, new MetadataChanges().shiftTime(Duration.ofHours(1)));
            assertEquals(LocalDateTime.of(2026, 10, 1, 0, 30), backend.read(shifted).taken(), format);

            Path cleaned = write(shifted, "cleaned." + format, new MetadataChanges().removePosition()
                    .text(TextTag.ARTIST, ""));
            PhotoMetadata c = backend.read(cleaned);
            assertNull(c.position(), format);
            assertNull(c.altitude(), format);
            assertNull(c.direction(), format);
            assertNull(c.text().get(TextTag.ARTIST), format);
            assertEquals("line one\nline\ttwo \\ \"q\"", c.text().get(TextTag.DESCRIPTION), format + ": kept");
        }
    }

    @Test
    void rawFilesGetAnXmpSidecarAndAreNeverModified() throws Exception {
        // DNG is TIFF-based, good enough for ExifTool: a TIFF with a capture date, renamed
        Path tiff = image("base.tif", "tiff");
        Path dated = write(tiff, "dated.tif", new MetadataChanges().taken(LocalDateTime.of(2026, 1, 2, 3, 4, 5)));
        Path raw = Files.move(dated, dir.resolve("IMG_1.dng"));
        byte[] rawBytes = Files.readAllBytes(raw);
        Path sidecar = backend.writeTarget(raw);
        assertEquals(dir.resolve("IMG_1.xmp"), sidecar);
        assertFalse(Files.exists(sidecar));

        Path tmp = write(raw, ".tmp-1.xmp", new MetadataChanges().position(new GeoPosition(47.5, -19.05))
                .text(TextTag.ARTIST, "Máté").altitude(100.0));
        Files.move(tmp, sidecar);
        assertArrayEquals(rawBytes, Files.readAllBytes(raw), "the RAW file is untouched");

        PhotoMetadata m = backend.read(raw);
        assertEquals(47.5, m.position().getLatitude(), 1e-6);
        assertEquals(-19.05, m.position().getLongitude(), 1e-6);
        assertEquals(100.0, m.altitude(), 1e-6);
        assertEquals("Máté", m.text().get(TextTag.ARTIST));
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4, 5), m.taken(), "date comes from the RAW file");

        // Second write updates the sidecar; replacing the (list-type) creator doesn't append
        Path tmp2 = write(raw, ".tmp-2.xmp", new MetadataChanges().text(TextTag.ARTIST, "Other")
                .shiftTime(Duration.ofDays(1)));
        Files.move(tmp2, sidecar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        PhotoMetadata m2 = backend.read(raw);
        assertEquals("Other", m2.text().get(TextTag.ARTIST));
        assertEquals(47.5, m2.position().getLatitude(), 1e-6, "position kept");
        assertEquals(LocalDateTime.of(2026, 1, 3, 3, 4, 5), m2.taken(), "shifted date is stored in the sidecar");
        assertArrayEquals(rawBytes, Files.readAllBytes(raw));
    }

    @Test
    void newSidecarIsRemovedByUndo() throws Exception {
        Path raw = image("IMG_2.nef", "tiff");
        RoutingBackend routing = new RoutingBackend();
        routing.setExifTool(backend);
        ImageFile image = new ImageFile(raw.toFile(), routing);
        assertTrue(image.isWritable());
        EditHistory history = new EditHistory();
        try {
            new PhotoWriter(history, () -> true).savePosition(image, new GeoPosition(1, 2));
            assertTrue(Files.exists(dir.resolve("IMG_2.xmp")));
            assertTrue(image.hasExifGPS());
            assertFalse(Files.exists(dir.resolve("IMG_2.xmp.bak")), "nothing to back up for a new sidecar");
            try (var files = Files.list(dir)) {
                assertEquals(List.of("IMG_2.nef", "IMG_2.xmp"), files.map(p -> p.getFileName().toString()).sorted().toList(),
                        "no temporary files left");
            }

            history.undo();
            image.reload();
            assertFalse(Files.exists(dir.resolve("IMG_2.xmp")));
            assertFalse(image.hasExifGPS());
        } finally {
            history.close();
        }
    }

    @Test
    void withoutExifToolOtherFormatsAreListedButReadOnly() throws Exception {
        Path png = image("b.png", "png");
        RoutingBackend routing = new RoutingBackend();
        assertTrue(routing.canRead(png));
        assertFalse(routing.canWrite(png));
        assertTrue(routing.needsExifTool(png));
        assertSame(PhotoMetadata.EMPTY, routing.read(png));
        assertTrue(routing.canWrite(dir.resolve("c.jpg")), "JPEG never needs ExifTool");
        assertThrows(java.io.IOException.class, () -> new ImageFile(png.toFile(), routing).savePosition(new GeoPosition(1, 1)));
    }

    @Test
    void previewOfAFileWithoutOneIsNull() throws Exception {
        assertNull(backend.preview(image("p.png", "png")));
    }

    @Test
    void shiftFormat() {
        assertEquals("0:0:1 2:3:4", ExifToolBackend.shiftValue(Duration.ofDays(1).plusHours(2).plusMinutes(3).plusSeconds(4)));
    }

    @Test
    void writesPlaceToXmpAndClearsStaleIptc() throws Exception {
        Place tihany = new Place(null, "Tihany", "Veszprém", "Magyarország", "HU", "Óvár");
        Path png = image("p.png", "png");
        Path out = write(png, "p-out.png", new MetadataChanges().place(tihany));
        assertEquals(tihany, backend.read(out).place());
        Path cleared = write(out, "p-cleared.png", new MetadataChanges().removePosition());
        assertNull(backend.read(cleared).place());

        // A JPEG with an old IPTC city: the new place replaces it, no stale IPTC is left behind
        Path jpeg = image("old.jpg", "jpg");
        exifTool.execute(java.util.List.of("-overwrite_original", "-IPTC:City=Budapest", jpeg.toString()));
        assertEquals("Budapest", backend.read(jpeg).place().city());
        Path updated = write(jpeg, "new.jpg", new MetadataChanges().place(tihany));
        assertEquals(tihany, backend.read(updated).place());
        assertFalse(exifTool.execute(java.util.List.of("-IPTC:City", updated.toString())).contains("Budapest"));
    }

    @Test
    void rawSidecarGetsThePlace() throws Exception {
        Path raw = Files.move(image("base2.tif", "tiff"), dir.resolve("IMG_9.dng"));
        Place place = new Place("Abbey", "Tihany", null, "Hungary", "HU", "Óvár");
        Path tmp = write(raw, ".tmp-9.xmp", new MetadataChanges().position(new GeoPosition(46.9, 17.9)).place(place));
        Files.move(tmp, backend.writeTarget(raw));
        assertEquals(place, backend.read(raw).place());
        // The district is in ExifTweaker's own XMP namespace, which ExifTool reads back with the config
        assertTrue(exifTool.execute(java.util.List.of("-XMP-exiftweaker:District", backend.writeTarget(raw).toString()))
                .contains("Óvár"));
    }
}

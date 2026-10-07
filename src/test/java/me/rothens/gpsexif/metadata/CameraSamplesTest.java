package me.rothens.gpsexif.metadata;

import me.rothens.gpsexif.util.MiniJson;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jxmapviewer.viewer.GeoPosition;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Writes a location into real photos from many cameras and phones (src/test/resources/samples, from ExifTool's
 * test set) and checks that it reads back and that nothing else changed: with ExifTool installed, every tag it
 * decodes - maker notes included - must be the same before and after.
 */
class CameraSamplesTest {

    private static final GeoPosition POSITION = new GeoPosition(47.497912, 19.040235);
    private static final double ALTITUDE = 104.5;

    private static ExifTool exifTool;
    private static final RoutingBackend backend = new RoutingBackend();

    @TempDir
    Path dir;

    @BeforeAll
    static void start() {
        String executable = ExifTool.locate(null);
        if (null != executable) {
            exifTool = new ExifTool(executable);
            backend.setExifTool(new ExifToolBackend(exifTool));
        }
    }

    @AfterAll
    static void stop() {
        if (null != exifTool) {
            exifTool.close();
        }
    }

    private Path sample(String name) throws IOException {
        Path copy = dir.resolve(name);
        try (InputStream in = CameraSamplesTest.class.getResourceAsStream("/samples/" + name)) {
            assertNotNull(in, "missing sample " + name);
            Files.copy(in, copy);
        }
        return copy;
    }

    /** Writes like the app does: into a temporary file that then replaces the write target. */
    private Path writeLocation(Path photo) throws IOException {
        Path target = backend.writeTarget(photo);
        Path temp = dir.resolve("tmp-" + target.getFileName());
        Files.createFile(temp);
        backend.write(photo, temp, new MetadataChanges().position(POSITION).altitude(ALTITUDE));
        Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private static void assertLocation(PhotoMetadata m, String name) {
        assertNotNull(m.position(), name + ": no position read back");
        assertEquals(POSITION.getLatitude(), m.position().getLatitude(), 1e-6, name);
        assertEquals(POSITION.getLongitude(), m.position().getLongitude(), 1e-6, name);
        assertEquals(ALTITUDE, m.altitude(), 1e-3, name);
    }

    /**
     * Every tag ExifTool reads, as "group:tag" to value, without the GPS tags (which we change) and without the
     * file system, layout and computed values that a rewrite legitimately changes.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> allTags(Path file) throws IOException {
        String json = exifTool.execute(List.of("-j", "-a", "-G1", "-n", "-u", file.toString()));
        Map<String, Object> tags = new TreeMap<>((Map<String, Object>) ((List<Object>) MiniJson.parse(json)).get(0));
        tags.keySet().removeIf(k -> k.startsWith("GPS:") || k.startsWith("System:") || k.startsWith("File:")
                || k.startsWith("Composite:") || k.startsWith("ExifTool:") || k.equals("SourceFile")
                || k.startsWith("XMP-exif:GPS") || k.equals("XMP-x:XMPToolkit")
                // where blocks sit in the file, which moves when the EXIF block grows
                || k.endsWith(":ThumbnailOffset") || k.endsWith(":PreviewImageStart")
                || k.endsWith(":ExifOffset") || k.endsWith(":GPSInfo") || k.endsWith(":InteropOffset")
                || k.endsWith(":StripOffsets") || k.endsWith(":OtherImageStart") || k.endsWith(":JpgFromRawStart")
                || k.endsWith(":MakerNoteOffset") || k.endsWith(":MediaDataOffset"));
        return tags;
    }

    private static void assertSameTags(Map<String, Object> before, Map<String, Object> after, String name) {
        List<String> differences = new ArrayList<>();
        for (Map.Entry<String, Object> e : before.entrySet()) {
            Object now = after.get(e.getKey());
            if (!e.getValue().equals(now)) {
                differences.add(e.getKey() + ": " + e.getValue() + " -> " + now);
            }
        }
        assertTrue(differences.isEmpty(), name + " lost or changed tags: " + differences);
    }

    /** JPEGs from cameras and phones, written by the built-in backend; the maker notes must survive. */
    @ParameterizedTest
    @ValueSource(strings = {"Canon.jpg", "Nikon.jpg", "Sony.jpg", "FujiFilm.jpg", "Olympus.jpg", "Pentax.jpg",
            "Panasonic.jpg", "Apple.jpg", "Google.jpg", "Motorola.jpg", "GoPro.jpg", "Kodak.jpg", "Casio.jpg",
            "Minolta.jpg", "Ricoh.jpg", "Sigma.jpg"})
    void jpegKeepsEverythingElse(String name) throws Exception {
        Path photo = sample(name);
        PhotoMetadata original = backend.read(photo);
        Map<String, Object> before = null == exifTool ? null : allTags(photo);

        writeLocation(photo);

        PhotoMetadata written = backend.read(photo);
        assertLocation(written, name);
        assertEquals(original.taken(), written.taken(), name);
        assertEquals(original.orientation(), written.orientation(), name);
        if (null != before) {
            assertSameTags(before, allTags(photo), name);
        }
    }

    /** RAW files are never modified: the location goes into an XMP sidecar, which the app reads back. */
    @ParameterizedTest
    @ValueSource(strings = {"CanonRaw.cr2", "CanonRaw.cr3", "Nikon.nef", "DNG.dng", "Panasonic.rw2",
            "FujiFilm.raf"})
    void rawGetsASidecar(String name) throws Exception {
        assumeTrue(null != exifTool, "ExifTool not installed");
        Path photo = sample(name);
        byte[] raw = Files.readAllBytes(photo);
        LocalDateTime taken = backend.read(photo).taken();

        Path sidecar = writeLocation(photo);

        assertNotEquals(photo, sidecar, name);
        assertTrue(sidecar.getFileName().toString().endsWith(".xmp"), name + " -> " + sidecar);
        assertArrayEquals(raw, Files.readAllBytes(photo), name + " was modified");
        PhotoMetadata written = backend.read(photo);
        assertLocation(written, name);
        assertEquals(taken, written.taken(), name);
    }

    /** HEIC (iPhone), PNG and TIFF are written in place through ExifTool. */
    @ParameterizedTest
    @ValueSource(strings = {"QuickTime.heic", "PNG.png", "ExifTool.tif"})
    void otherFormatsAreWrittenInPlace(String name) throws Exception {
        assumeTrue(null != exifTool, "ExifTool not installed");
        Path photo = sample(name);
        Map<String, Object> before = allTags(photo);

        assertEquals(photo, writeLocation(photo), name);

        assertLocation(backend.read(photo), name);
        assertSameTags(before, allTags(photo), name);
    }
}

package me.rothens.gpsexif.share;

import me.rothens.gpsexif.metadata.ExifTool;
import me.rothens.gpsexif.metadata.ExifToolBackend;
import me.rothens.gpsexif.metadata.MetadataChanges;
import me.rothens.gpsexif.metadata.Place;
import me.rothens.gpsexif.metadata.RoutingBackend;
import me.rothens.gpsexif.model.ImageFile;
import me.rothens.gpsexif.util.MiniJson;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jxmapviewer.viewer.GeoPosition;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ShareExportTest {

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

    private ImageFile sample(String name) throws IOException {
        Path copy = dir.resolve("in").resolve(name);
        Files.createDirectories(copy.getParent());
        try (InputStream in = ShareExportTest.class.getResourceAsStream("/samples/" + name)) {
            assertNotNull(in, name);
            Files.copy(in, copy);
        }
        return new ImageFile(copy.toFile(), backend);
    }

    /** A sample with a location and place written into it. */
    private ImageFile located(String name) throws IOException {
        ImageFile photo = sample(name);
        photo.apply(new MetadataChanges().position(new GeoPosition(46.9137, 17.8893))
                .place(new Place(null, "Tihany", null, "Hungary", "HU")));
        assertNotNull(photo.getGp());
        return photo;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tags(Path file) throws IOException {
        String json = exifTool.execute(List.of("-j", "-a", "-G1", "-n", file.toString()));
        Map<String, Object> tags = new TreeMap<>((Map<String, Object>) ((List<Object>) MiniJson.parse(json)).get(0));
        tags.keySet().removeIf(k -> k.startsWith("System:") || k.startsWith("File:") || k.startsWith("ExifTool:")
                || k.equals("SourceFile") || k.startsWith("Composite:"));
        return tags;
    }

    /** The compressed picture: everything from the first start-of-scan marker. */
    private static byte[] scan(Path jpeg) throws IOException {
        byte[] b = Files.readAllBytes(jpeg);
        for (int i = 2; i + 1 < b.length; i++) {
            if ((b[i] & 0xFF) == 0xFF && (b[i + 1] & 0xFF) == 0xDA) {
                return Arrays.copyOfRange(b, i, b.length);
            }
        }
        throw new AssertionError("no scan in " + jpeg);
    }

    private Path export(ImageFile photo, ShareExport.Privacy privacy, int size) throws IOException {
        return new ShareExport(backend.getExifTool()).export(photo, dir.resolve("out"),
                new ShareExport.Options(privacy, size, 0.85f));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Canon.jpg", "Apple.jpg", "Nikon.jpg", "Google.jpg"})
    void withoutLocationKeepsEverythingElse(String name) throws Exception {
        assumeTrue(null != exifTool, "ExifTool not installed");
        ImageFile photo = located(name);
        Path copy = export(photo, ShareExport.Privacy.NO_LOCATION, 0);
        Map<String, Object> tags = tags(copy);
        assertTrue(tags.keySet().stream().noneMatch(k -> k.contains("GPS") || k.endsWith(":City")
                || k.endsWith(":Country")), name + ": " + tags.keySet());
        assertEquals(tags(photo.getPath()).get("IFD0:Model"), tags.get("IFD0:Model"));
        assertArrayEquals(scan(photo.getPath()), scan(copy), "the picture isn't re-encoded");
        assertNotNull(photo.getGp(), "the original keeps its location");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Canon.jpg", "Apple.jpg", "Sony.jpg", "Olympus.jpg"})
    void withoutAnyMetadataOnlyKeepsOrientationAndColors(String name) throws Exception {
        assumeTrue(null != exifTool, "ExifTool not installed");
        ImageFile photo = located(name);
        Path copy = export(photo, ShareExport.Privacy.NONE, 0);
        Map<String, Object> tags = tags(copy);
        tags.keySet().removeIf(k -> k.startsWith("JFIF:") || k.startsWith("ICC") || k.startsWith("Adobe:"));
        if (photo.getOrientation() == 1) {
            assertEquals(Map.of(), tags, name);
        } else {
            assertEquals(photo.getOrientation(), ((Double) tags.remove("IFD0:Orientation")).intValue(), name);
            tags.keySet().removeIf(k -> k.startsWith("IFD0:") && !k.equals("IFD0:Make") && !k.equals("IFD0:Model"));
            assertEquals(Map.of(), tags, name + " keeps only the orientation");
        }
        assertArrayEquals(scan(photo.getPath()), scan(copy), "the picture isn't re-encoded");
    }

    @Test
    void allMetadataIsAPlainCopyAndNamesDontCollide() throws Exception {
        ImageFile photo = sample("Canon.jpg");
        Path first = export(photo, ShareExport.Privacy.ALL, 0);
        Path second = export(photo, ShareExport.Privacy.ALL, 0);
        assertEquals("Canon.jpg", first.getFileName().toString());
        assertEquals("Canon (2).jpg", second.getFileName().toString());
        assertArrayEquals(Files.readAllBytes(photo.getPath()), Files.readAllBytes(first));
    }

    @Test
    void smallerCopiesAreUprightAndKeepTheirMetadata() throws Exception {
        assumeTrue(null != exifTool, "ExifTool not installed");
        // The camera samples are tiny: a larger photo, stored sideways so the copy has to be turned upright
        Path big = dir.resolve("in").resolve("big.jpg");
        Files.createDirectories(big.getParent());
        ImageIO.write(new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB), "jpg", big.toFile());
        exifTool.execute(List.of("-overwrite_original", "-n", "-IFD0:Orientation=6", "-IFD0:Model=X100V",
                "-ThumbnailImage<=" + dir.resolve("in").resolve("big.jpg"), big.toString()));
        ImageFile photo = new ImageFile(big.toFile(), backend);
        photo.apply(new MetadataChanges().position(new GeoPosition(46.9137, 17.8893))
                .place(new Place(null, "Tihany", null, "Hungary", "HU")));
        BufferedImage original = ImageIO.read(photo.getFile());
        Path copy = export(photo, ShareExport.Privacy.NO_LOCATION, 200);
        BufferedImage small = ImageIO.read(copy.toFile());
        assertEquals(200, Math.max(small.getWidth(), small.getHeight()));
        assertEquals(original.getWidth() > original.getHeight(), small.getHeight() > small.getWidth(),
                "upright: width and height swapped");
        Map<String, Object> tags = tags(copy);
        assertEquals(1, ((Double) tags.get("IFD0:Orientation")).intValue());
        assertEquals(tags(photo.getPath()).get("IFD0:Model"), tags.get("IFD0:Model"));
        assertTrue(tags.keySet().stream().noneMatch(k -> k.contains("GPS") || k.endsWith(":City")), tags.keySet()
                .toString());
        assertTrue(tags.keySet().stream().noneMatch(k -> k.startsWith("IFD1:")), "no stale thumbnail");
    }

    @Test
    void rawAndOtherFormats() throws Exception {
        assumeTrue(null != exifTool, "ExifTool not installed");
        ImageFile raw = sample("DNG.dng");
        Path jpeg = export(raw, ShareExport.Privacy.NONE, 0);
        assertEquals("DNG.jpg", jpeg.getFileName().toString());
        assertNotNull(ImageIO.read(jpeg.toFile()));

        Path png = dir.resolve("in").resolve("a.png");
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", png.toFile());
        ImageFile photo = new ImageFile(png.toFile(), backend);
        photo.apply(new MetadataChanges().position(new GeoPosition(1, 2)).place(new Place(null, "X", null, null, null)));
        Path copy = export(photo, ShareExport.Privacy.NO_LOCATION, 0);
        assertEquals("a.png", copy.getFileName().toString());
        assertTrue(tags(copy).keySet().stream().noneMatch(k -> k.contains("GPS") || k.endsWith(":City")));
    }

    @Test
    void stripsWithoutExifTool() throws Exception {
        ImageFile photo = sample("Nikon.jpg");
        Path copy = new ShareExport(null).export(photo, dir.resolve("out"),
                new ShareExport.Options(ShareExport.Privacy.NONE, 0, 0.85f));
        assertNull(new me.rothens.gpsexif.metadata.CommonsImagingBackend().read(copy).text()
                .get(me.rothens.gpsexif.metadata.TextTag.MODEL));
        assertNotNull(ImageIO.read(copy.toFile()));
    }
}

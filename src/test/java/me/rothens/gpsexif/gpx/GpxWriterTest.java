package me.rothens.gpsexif.gpx;

import me.rothens.gpsexif.util.SafeXml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jxmapviewer.viewer.GeoPosition;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GpxWriterTest {

    @TempDir
    Path dir;

    @Test
    void writesSortedWaypointsThatParseAsGpx() throws Exception {
        Path file = dir.resolve("photos.gpx");
        GpxWriter.write(file, List.of(
                new GpxWriter.Waypoint("b.jpg", new GeoPosition(47.5, 19.05), Instant.parse("2026-09-30T10:00:00Z"),
                        null, "Sunset <over> the \"Danube\" & Máté"),
                new GpxWriter.Waypoint("a.jpg", new GeoPosition(-33.8568, 151.2153),
                        Instant.parse("2026-09-30T08:00:00.500Z"), 12.34, null)), "ExifTweaker test");

        Document doc = SafeXml.newDocumentBuilder(true).parse(file.toFile());
        Element root = doc.getDocumentElement();
        assertEquals("http://www.topografix.com/GPX/1/1", root.getNamespaceURI());
        assertEquals("1.1", root.getAttribute("version"));
        NodeList wpts = doc.getElementsByTagNameNS("*", "wpt");
        assertEquals(2, wpts.getLength());
        Element first = (Element) wpts.item(0);
        assertEquals("-33.8568000", first.getAttribute("lat"), "sorted by time");
        assertEquals("151.2153000", first.getAttribute("lon"));
        assertEquals("12.3", text(first, "ele"));
        assertEquals("2026-09-30T08:00:00.500Z", text(first, "time"));
        assertEquals("a.jpg", text(first, "name"));
        assertEquals(0, first.getElementsByTagNameNS("*", "desc").getLength());
        Element second = (Element) wpts.item(1);
        assertEquals("Sunset <over> the \"Danube\" & Máté", text(second, "desc"), "special characters are escaped");
        assertEquals(0, second.getElementsByTagNameNS("*", "ele").getLength());
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "no temp file left");
        }
    }

    @Test
    void emptyListGivesValidEmptyGpx() throws Exception {
        Path file = dir.resolve("empty.gpx");
        GpxWriter.write(file, List.of(), "test");
        assertEquals(0, SafeXml.newDocumentBuilder(true).parse(file.toFile()).getElementsByTagNameNS("*", "wpt").getLength());
    }

    private static String text(Element parent, String name) {
        return parent.getElementsByTagNameNS("*", name).item(0).getTextContent();
    }
}

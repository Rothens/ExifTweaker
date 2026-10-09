package me.rothens.gpsexif.gpx;

import static me.rothens.gpsexif.i18n.I18n.tr;
import me.rothens.gpsexif.util.SafeXml;
import org.jxmapviewer.viewer.GeoPosition;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads GPX 1.0 / 1.1 files as exported by phones, watches and GPS loggers. Track segments ({@code trkseg}) are
 * read as segments; a route ({@code rte}) or loose waypoints are not used for geotagging as they carry no reliable
 * times.
 */
public final class GpxParser {

    private GpxParser() {
    }

    public static Track parse(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(in, file.getFileName().toString());
        }
    }

    public static Track parse(InputStream in, String name) throws IOException {
        Document doc;
        try {
            doc = SafeXml.newDocumentBuilder(true).parse(in);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException(tr("{0} isn't a valid GPX file: {1}", name, e.getMessage()), e);
        }
        Element root = doc.getDocumentElement();
        if (!"gpx".equals(localName(root))) {
            throw new IOException(tr("{0} isn't a GPX file (root element <{1}>)", name, localName(root)));
        }
        List<List<TrackPoint>> segments = new ArrayList<>();
        NodeList segmentNodes = doc.getElementsByTagNameNS("*", "trkseg");
        for (int i = 0; i < segmentNodes.getLength(); i++) {
            List<TrackPoint> points = new ArrayList<>();
            for (Element pt : children((Element) segmentNodes.item(i), "trkpt")) {
                TrackPoint point = parsePoint(pt);
                if (null != point) {
                    points.add(point);
                }
            }
            if (!points.isEmpty()) {
                segments.add(points);
            }
        }
        return new Track(name, segments);
    }

    private static TrackPoint parsePoint(Element pt) {
        double lat;
        double lon;
        try {
            lat = Double.parseDouble(pt.getAttribute("lat"));
            lon = Double.parseDouble(pt.getAttribute("lon"));
        } catch (NumberFormatException e) {
            return null;
        }
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            return null;
        }
        Double elevation = null;
        Instant time = null;
        for (Element child : children(pt, null)) {
            String text = child.getTextContent().trim();
            switch (localName(child)) {
                case "ele" -> {
                    try {
                        elevation = Double.parseDouble(text);
                    } catch (NumberFormatException ignored) {
                        // no elevation
                    }
                }
                case "time" -> time = parseTime(text);
                default -> {
                    // extensions (heart rate, speed, ...) aren't needed
                }
            }
        }
        return new TrackPoint(time, new GeoPosition(lat, lon), elevation);
    }

    /** ISO 8601 as used by GPX: {@code 2026-09-30T10:15:30Z}, optionally with fractions or an offset. */
    static Instant parseTime(String text) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            try {
                // Some loggers omit the zone; GPX times are UTC by definition
                return java.time.LocalDateTime.parse(text).toInstant(java.time.ZoneOffset.UTC);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && (null == localName || localName.equals(localName(e)))) {
                result.add(e);
            }
        }
        return result;
    }

    private static String localName(Element e) {
        return null != e.getLocalName() ? e.getLocalName() : e.getTagName();
    }
}

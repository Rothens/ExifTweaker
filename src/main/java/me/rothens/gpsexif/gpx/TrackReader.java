package me.rothens.gpsexif.gpx;

import me.rothens.gpsexif.util.SafeXml;
import org.jxmapviewer.viewer.GeoPosition;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads a recorded track from any of the formats ExifTweaker knows, recognized by the content rather than the
 * name: GPX, KML/KMZ ({@code gx:Track}, or placemarks with a time), TCX and FIT (Garmin, Strava, Wahoo), and
 * Google Maps location history (Takeout's Records.json and the on-device Timeline exports).
 */
public final class TrackReader {

    /** File name extensions to offer in file choosers. */
    public static final List<String> EXTENSIONS = List.of("gpx", "kml", "kmz", "tcx", "fit", "json");
    private static final long MAX_IN_MEMORY = 512L * 1024 * 1024;

    private TrackReader() {
    }

    public static Track read(Path file) throws IOException {
        return read(file, t -> true);
    }

    /**
     * Keeps the times within {@code margin} of the photos' dates (taken as UTC, so any camera time zone fits in a
     * few days' margin); everything if none has a date.
     */
    public static Predicate<Instant> around(List<java.time.LocalDateTime> taken, java.time.Duration margin) {
        List<java.time.LocalDateTime> dated = taken.stream().filter(java.util.Objects::nonNull).toList();
        if (dated.isEmpty()) {
            return t -> true;
        }
        Instant from = dated.stream().min(java.time.LocalDateTime::compareTo).orElseThrow()
                .toInstant(java.time.ZoneOffset.UTC).minus(margin);
        Instant to = dated.stream().max(java.time.LocalDateTime::compareTo).orElseThrow()
                .toInstant(java.time.ZoneOffset.UTC).plus(margin);
        return t -> !t.isBefore(from) && !t.isAfter(to);
    }

    /**
     * @param keep which point times to keep; a location history spanning years can be cut down to the days around
     *             the photos this way
     * @throws IOException with a readable message if the file can't be read or has no points with a time
     */
    public static Track read(Path file, Predicate<Instant> keep) throws IOException {
        String name = file.getFileName().toString();
        byte[] head = new byte[16];
        int n;
        try (InputStream in = Files.newInputStream(file)) {
            n = in.readNBytes(head, 0, head.length);
        }
        Track track;
        if (FitParser.isFit(head)) {
            track = single(name, filter(FitParser.parse(readAll(file)), keep));
        } else if (n >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) {
            track = kmz(file, name, keep);
        } else {
            int first = firstChar(head, n);
            if (first == '{' || first == '[') {
                try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    track = single(name, GoogleTimeline.read(reader, keep));
                } catch (IOException e) {
                    throw new IOException(name + " isn't a location history this app knows: " + e.getMessage(), e);
                }
            } else if (first == '<') {
                track = xml(readAll(file), name, keep);
            } else {
                throw new IOException(name + ": not a track file (GPX, KML, KMZ, TCX, FIT or Google location history)");
            }
        }
        if (track.pointCount() == 0) {
            throw new IOException(name + ": no points with a time" + (keep.test(Instant.EPOCH) ? ""
                    : " around the photos' dates"));
        }
        return track;
    }

    private static byte[] readAll(Path file) throws IOException {
        if (Files.size(file) > MAX_IN_MEMORY) {
            throw new IOException(file.getFileName() + " is too large");
        }
        return Files.readAllBytes(file);
    }

    private static int firstChar(byte[] head, int n) {
        int i = 0;
        if (n >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            i = 3; // UTF-8 BOM
        }
        while (i < n && Character.isWhitespace(head[i])) {
            i++;
        }
        return i < n ? head[i] : -1;
    }

    private static Track kmz(Path file, String name, Predicate<Instant> keep) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file))) {
            for (ZipEntry e = zip.getNextEntry(); null != e; e = zip.getNextEntry()) {
                if (e.getName().toLowerCase(Locale.ROOT).endsWith(".kml")) {
                    return xml(zip.readNBytes((int) MAX_IN_MEMORY), name, keep);
                }
            }
        }
        throw new IOException(name + ": no KML file inside");
    }

    private static Track xml(byte[] bytes, String name, Predicate<Instant> keep) throws IOException {
        Document doc;
        try {
            doc = SafeXml.newDocumentBuilder(true).parse(new ByteArrayInputStream(bytes));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException(name + " isn't valid XML: " + e.getMessage(), e);
        }
        String root = localName(doc.getDocumentElement());
        return switch (root) {
            case "gpx" -> GpxParser.parse(new ByteArrayInputStream(bytes), name);
            case "kml" -> new Track(name, kml(doc, keep));
            case "TrainingCenterDatabase" -> new Track(name, tcx(doc, keep));
            default -> throw new IOException(name + ": not a track file (root element <" + root + ">)");
        };
    }

    /** gx:Track (when + gx:coord pairs) per segment; placemarks with a time stamp and a point as one more. */
    private static List<List<TrackPoint>> kml(Document doc, Predicate<Instant> keep) {
        List<List<TrackPoint>> segments = new ArrayList<>();
        NodeList tracks = doc.getElementsByTagNameNS("*", "Track");
        for (int i = 0; i < tracks.getLength(); i++) {
            Element track = (Element) tracks.item(i);
            List<Instant> times = new ArrayList<>();
            List<String> coords = new ArrayList<>();
            for (Node c = track.getFirstChild(); null != c; c = c.getNextSibling()) {
                if (c instanceof Element e) {
                    if ("when".equals(localName(e))) {
                        times.add(instant(e.getTextContent()));
                    } else if ("coord".equals(localName(e))) {
                        coords.add(e.getTextContent());
                    }
                }
            }
            List<TrackPoint> points = new ArrayList<>();
            for (int k = 0; k < Math.min(times.size(), coords.size()); k++) {
                // "lon lat alt", separated by spaces
                String[] parts = coords.get(k).strip().split("\\s+");
                add(points, keep, times.get(k), parts, 1, 0, 2);
            }
            if (!points.isEmpty()) {
                segments.add(points);
            }
        }
        List<TrackPoint> stamped = new ArrayList<>();
        NodeList placemarks = doc.getElementsByTagNameNS("*", "Placemark");
        for (int i = 0; i < placemarks.getLength(); i++) {
            Element placemark = (Element) placemarks.item(i);
            Element when = first(first(placemark, "TimeStamp"), "when");
            Element coordinates = first(first(placemark, "Point"), "coordinates");
            if (null != when && null != coordinates) {
                // "lon,lat,alt"
                add(stamped, keep, instant(when.getTextContent()), coordinates.getTextContent().strip().split(","),
                        1, 0, 2);
            }
        }
        if (!stamped.isEmpty()) {
            stamped.sort(java.util.Comparator.comparing(TrackPoint::time));
            segments.add(stamped);
        }
        return segments;
    }

    /** Each Track of each lap (or course) is a segment of Trackpoints. */
    private static List<List<TrackPoint>> tcx(Document doc, Predicate<Instant> keep) {
        List<List<TrackPoint>> segments = new ArrayList<>();
        NodeList tracks = doc.getElementsByTagNameNS("*", "Track");
        for (int i = 0; i < tracks.getLength(); i++) {
            List<TrackPoint> points = new ArrayList<>();
            for (Node c = tracks.item(i).getFirstChild(); null != c; c = c.getNextSibling()) {
                if (c instanceof Element pt && "Trackpoint".equals(localName(pt))) {
                    Element position = first(pt, "Position");
                    Element lat = first(position, "LatitudeDegrees");
                    Element lon = first(position, "LongitudeDegrees");
                    Element time = first(pt, "Time");
                    Element altitude = first(pt, "AltitudeMeters");
                    if (null != lat && null != lon && null != time) {
                        add(points, keep, instant(time.getTextContent()), new String[]{lat.getTextContent(),
                                lon.getTextContent(), null == altitude ? "" : altitude.getTextContent()}, 0, 1, 2);
                    }
                }
            }
            if (!points.isEmpty()) {
                segments.add(points);
            }
        }
        return segments;
    }

    private static void add(List<TrackPoint> points, Predicate<Instant> keep, Instant time, String[] parts,
                            int latIndex, int lonIndex, int altIndex) {
        if (null == time || !keep.test(time) || parts.length <= Math.max(latIndex, lonIndex)) {
            return;
        }
        try {
            double lat = Double.parseDouble(parts[latIndex].strip());
            double lon = Double.parseDouble(parts[lonIndex].strip());
            if (Math.abs(lat) > 90 || Math.abs(lon) > 180) {
                return;
            }
            Double altitude = null;
            if (parts.length > altIndex && !parts[altIndex].isBlank()) {
                altitude = Double.parseDouble(parts[altIndex].strip());
            }
            points.add(new TrackPoint(time, new GeoPosition(lat, lon), altitude));
        } catch (NumberFormatException ignored) {
            // skip a broken point
        }
    }

    private static Track single(String name, List<TrackPoint> points) {
        return new Track(name, points.isEmpty() ? List.of() : List.of(points));
    }

    private static List<TrackPoint> filter(List<TrackPoint> points, Predicate<Instant> keep) {
        return points.stream().filter(p -> keep.test(p.time())).toList();
    }

    private static Element first(Element parent, String name) {
        if (null == parent) {
            return null;
        }
        for (Node c = parent.getFirstChild(); null != c; c = c.getNextSibling()) {
            if (c instanceof Element e && name.equals(localName(e))) {
                return e;
            }
        }
        return null;
    }

    private static String localName(Element e) {
        return null != e.getLocalName() ? e.getLocalName() : e.getTagName();
    }

    private static Instant instant(String text) {
        try {
            return OffsetDateTime.parse(text.strip()).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(text.strip());
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}

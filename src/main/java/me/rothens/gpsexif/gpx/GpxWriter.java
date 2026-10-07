package me.rothens.gpsexif.gpx;

import org.jxmapviewer.viewer.GeoPosition;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Writes photo locations as GPX 1.1 waypoints. */
public final class GpxWriter {

    private static final String NS = "http://www.topografix.com/GPX/1/1";

    /**
     * One waypoint.
     *
     * @param time        UTC time
     * @param elevation   metres, or {@code null}
     * @param description optional text, or {@code null}
     */
    public record Waypoint(String name, GeoPosition position, Instant time, Double elevation, String description) {
    }

    private GpxWriter() {
    }

    /** Writes the waypoints sorted by time (atomically: a temp file is moved into place). */
    public static void write(Path file, List<Waypoint> waypoints, String creator) throws IOException {
        Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), ".exiftweaker-", ".gpx");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                write(out, waypoints, creator);
            }
            me.rothens.gpsexif.util.FileUtil.replace(tmp, file);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public static void write(OutputStream out, List<Waypoint> waypoints, String creator) throws IOException {
        List<Waypoint> sorted = waypoints.stream().sorted(Comparator.comparing(Waypoint::time)).toList();
        try {
            XMLStreamWriter xml = XMLOutputFactory.newInstance().createXMLStreamWriter(out, "UTF-8");
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeCharacters("\n");
            xml.writeStartElement("gpx");
            xml.writeDefaultNamespace(NS);
            xml.writeAttribute("version", "1.1");
            xml.writeAttribute("creator", creator);
            xml.writeCharacters("\n");
            if (!sorted.isEmpty()) {
                xml.writeCharacters("  ");
                xml.writeStartElement("metadata");
                element(xml, "time", DateTimeFormatter.ISO_INSTANT.format(sorted.get(0).time()));
                xml.writeEndElement();
                xml.writeCharacters("\n");
            }
            for (Waypoint w : sorted) {
                xml.writeCharacters("  ");
                xml.writeStartElement("wpt");
                xml.writeAttribute("lat", coordinate(w.position().getLatitude()));
                xml.writeAttribute("lon", coordinate(w.position().getLongitude()));
                if (null != w.elevation()) {
                    element(xml, "ele", String.format(Locale.ROOT, "%.1f", w.elevation()));
                }
                element(xml, "time", DateTimeFormatter.ISO_INSTANT.format(w.time()));
                element(xml, "name", w.name());
                if (null != w.description() && !w.description().isBlank()) {
                    element(xml, "desc", w.description());
                }
                xml.writeEndElement();
                xml.writeCharacters("\n");
            }
            xml.writeEndElement();
            xml.writeCharacters("\n");
            xml.writeEndDocument();
            xml.close();
        } catch (XMLStreamException e) {
            throw new IOException("Couldn't write GPX: " + e.getMessage(), e);
        }
    }

    private static void element(XMLStreamWriter xml, String name, String text) throws XMLStreamException {
        xml.writeStartElement(name);
        xml.writeCharacters(text);
        xml.writeEndElement();
    }

    private static String coordinate(double value) {
        return String.format(Locale.ROOT, "%.7f", value);
    }
}

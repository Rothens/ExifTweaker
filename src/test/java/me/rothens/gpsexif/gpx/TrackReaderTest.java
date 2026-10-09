package me.rothens.gpsexif.gpx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class TrackReaderTest {

    @TempDir
    Path dir;

    private static final Instant T0 = Instant.parse("2024-04-01T08:00:00Z");

    private Path file(String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content);
    }

    private static List<TrackPoint> points(Track t) {
        return t.segments().stream().flatMap(List::stream).toList();
    }

    @Test
    void gpxStillWorksWhateverTheName() throws Exception {
        Path f = file("track.txt", """
                <?xml version="1.0"?><gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1"><trk><trkseg>
                <trkpt lat="47.5" lon="19.05"><ele>120</ele><time>2024-04-01T08:00:00Z</time></trkpt>
                </trkseg></trk></gpx>""");
        Track t = TrackReader.read(f);
        assertEquals(1, t.pointCount());
        assertEquals(120, points(t).get(0).elevation(), 1e-9);
    }

    @Test
    void kmlTracksAndTimedPlacemarks() throws Exception {
        String kml = """
                <kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2"><Document>
                 <Placemark><gx:Track>
                  <when>2024-04-01T08:00:00Z</when><when>2024-04-01T08:01:00+02:00</when>
                  <gx:coord>19.05 47.5 120</gx:coord><gx:coord>19.06 47.51 125</gx:coord>
                 </gx:Track></Placemark>
                 <Placemark><TimeStamp><when>2024-04-01T09:00:00Z</when></TimeStamp>
                  <Point><coordinates>17.89,46.91,110</coordinates></Point></Placemark>
                 <Placemark><Point><coordinates>1,2</coordinates></Point></Placemark>
                </Document></kml>""";
        Track t = TrackReader.read(file("a.kml", kml));
        assertEquals(2, t.segments().size());
        TrackPoint second = t.segments().get(0).get(1);
        assertEquals(47.51, second.position().getLatitude(), 1e-9);
        assertEquals(19.06, second.position().getLongitude(), 1e-9);
        assertEquals(Instant.parse("2024-04-01T06:01:00Z"), second.time());
        assertEquals(46.91, t.segments().get(1).get(0).position().getLatitude(), 1e-9);

        // KMZ: the same, zipped
        Path kmz = dir.resolve("a.kmz");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(kmz))) {
            zip.putNextEntry(new ZipEntry("doc.kml"));
            zip.write(kml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(3, TrackReader.read(kmz).pointCount());
    }

    @Test
    void tcx() throws Exception {
        Track t = TrackReader.read(file("run.tcx", """
                <TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">
                 <Activities><Activity Sport="Running"><Lap StartTime="2024-04-01T08:00:00Z"><Track>
                  <Trackpoint><Time>2024-04-01T08:00:00Z</Time><Position><LatitudeDegrees>47.5</LatitudeDegrees>
                   <LongitudeDegrees>19.05</LongitudeDegrees></Position><AltitudeMeters>101.5</AltitudeMeters></Trackpoint>
                  <Trackpoint><Time>2024-04-01T08:00:05Z</Time><HeartRateBpm><Value>120</Value></HeartRateBpm></Trackpoint>
                  <Trackpoint><Time>2024-04-01T08:00:10Z</Time><Position><LatitudeDegrees>47.501</LatitudeDegrees>
                   <LongitudeDegrees>19.051</LongitudeDegrees></Position></Trackpoint>
                 </Track></Lap></Activity></Activities></TrainingCenterDatabase>"""));
        assertEquals(2, t.pointCount(), "the point without a position is left out");
        assertEquals(101.5, points(t).get(0).elevation(), 1e-9);
        assertNull(points(t).get(1).elevation());
    }

    @Test
    void googleTakeoutRecords() throws Exception {
        Track t = TrackReader.read(file("Records.json", """
                {"locations": [
                  {"latitudeE7": 475000000, "longitudeE7": 190500000, "accuracy": 12, "altitude": 130,
                   "timestamp": "2024-04-01T08:00:00.123Z", "activity": [{"type": "STILL"}]},
                  {"latitudeE7": 475100000, "longitudeE7": 190600000, "accuracy": 5000, "timestamp": "2024-04-01T08:05:00Z"},
                  {"latitudeE7": 474900000, "longitudeE7": 190400000, "timestampMs": "1711958700000"}
                ]}"""));
        assertEquals(2, t.pointCount(), "the cell-tower point (5 km accuracy) is left out");
        assertEquals(47.5, points(t).get(0).position().getLatitude(), 1e-9);
        assertEquals(130, points(t).get(0).elevation(), 1e-9);
        assertEquals(Instant.ofEpochMilli(1711958700000L), points(t).get(1).time());
    }

    @Test
    void androidTimelineExport() throws Exception {
        Track t = TrackReader.read(file("Timeline.json", """
                {"semanticSegments": [
                  {"startTime": "2024-04-01T10:00:00.000+02:00", "endTime": "2024-04-01T11:00:00.000+02:00",
                   "visit": {"hierarchyLevel": 0, "topCandidate": {"placeId": "x",
                     "placeLocation": {"latLng": "47.4979414°, 19.0402350°"}}}},
                  {"startTime": "2024-04-01T11:00:00.000+02:00", "endTime": "2024-04-01T12:30:00.000+02:00",
                   "timelinePath": [{"point": "47.3000000°, 18.5000000°", "time": "2024-04-01T11:30:00.000+02:00"}],
                   "activity": {"start": {"latLng": "47.4979°, 19.0402°"}, "end": {"latLng": "46.9137°, 17.8893°"}}}
                 ],
                 "rawSignals": [{"position": {"LatLng": "47.1°, 18.2°", "accuracyMeters": 10, "altitudeMeters": 140.0,
                   "timestamp": "2024-04-01T12:00:00.000+02:00"}}],
                 "userLocationProfile": {"frequentPlaces": [{"placeLocation": "47.0°, 19.0°"}]}}"""));
        List<TrackPoint> p = points(t);
        assertEquals(List.of(Instant.parse("2024-04-01T08:00:00Z"), Instant.parse("2024-04-01T09:00:00Z"),
                Instant.parse("2024-04-01T09:30:00Z"), Instant.parse("2024-04-01T10:00:00Z"),
                Instant.parse("2024-04-01T10:30:00Z")), p.stream().map(TrackPoint::time).toList());
        assertEquals(47.4979414, p.get(0).position().getLatitude(), 1e-9);
        assertEquals(140, p.get(3).elevation(), 1e-9);
        assertEquals(17.8893, p.get(4).position().getLongitude(), 1e-9);
    }

    @Test
    void iphoneTimelineExport() throws Exception {
        Track t = TrackReader.read(file("location-history.json", """
                [{"endTime": "2024-04-01T11:00:00.000+02:00", "startTime": "2024-04-01T10:00:00.000+02:00",
                  "visit": {"topCandidate": {"placeLocation": "geo:47.497941,19.040235", "semanticType": "Unknown"}}},
                 {"endTime": "2024-04-01T12:00:00.000+02:00", "startTime": "2024-04-01T11:00:00.000+02:00",
                  "timelinePath": [{"point": "geo:47.300000,18.500000", "durationMinutesOffsetFromStartTime": "30"}]}]"""));
        List<TrackPoint> p = points(t);
        assertEquals(3, p.size());
        assertEquals(Instant.parse("2024-04-01T09:30:00Z"), p.get(2).time());
        assertEquals(18.5, p.get(2).position().getLongitude(), 1e-9);
    }

    @Test
    void historyIsCutToTheDaysAround() throws Exception {
        Path f = file("Records.json", """
                {"locations": [{"latitudeE7": 475000000, "longitudeE7": 190500000, "timestamp": "2020-01-01T08:00:00Z"},
                  {"latitudeE7": 475000000, "longitudeE7": 190500000, "timestamp": "2024-04-01T08:00:00Z"}]}""");
        Instant from = Instant.parse("2024-03-30T00:00:00Z");
        Instant to = Instant.parse("2024-04-03T00:00:00Z");
        Track t = TrackReader.read(f, time -> !time.isBefore(from) && !time.isAfter(to));
        assertEquals(1, t.pointCount());
        IOException none = assertThrows(IOException.class, () -> TrackReader.read(f, time -> false));
        assertTrue(none.getMessage().contains("around the photos"), none.getMessage());
    }

    @Test
    void notATrack() throws Exception {
        assertThrows(IOException.class, () -> TrackReader.read(file("x.json", "{\"hello\": 1}")));
        assertThrows(IOException.class, () -> TrackReader.read(file("x.html", "<html><body/></html>")));
        assertThrows(IOException.class, () -> TrackReader.read(file("x.bin", "hello world, not a track")));
    }

    // ---- FIT ----

    /** A small FIT file: one little-endian and one big-endian record definition, and a compressed-time record. */
    static byte[] fit() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        long t0 = T0.getEpochSecond() - 631_065_600L;
        // file_id message (global 0), definition and data, to be skipped
        body.writeBytes(new byte[]{0x40, 0, 0, 0, 0, 1, 4, 1, 0x00}); // local 0: field 4 (type), 1 byte
        body.writeBytes(new byte[]{0x00, 4});
        // record definition, local 1, little-endian: timestamp, lat, lon, altitude
        body.writeBytes(new byte[]{0x41, 0, 0, 20, 0, 4, (byte) 253, 4, (byte) 0x86, 0, 4, (byte) 0x85, 1, 4,
                (byte) 0x85, 2, 2, (byte) 0x84});
        body.writeBytes(record(ByteOrder.LITTLE_ENDIAN, 0x01, t0, 47.5, 19.05, 120));
        // the same layout big-endian, with developer data (2 bytes) behind each record
        body.writeBytes(new byte[]{0x62, 0, 1, 0, 20, 4, (byte) 253, 4, (byte) 0x86, 0, 4, (byte) 0x85, 1, 4,
                (byte) 0x85, 2, 2, (byte) 0x84, 1, 0, 2, 0});
        byte[] big = record(ByteOrder.BIG_ENDIAN, 0x02, t0 + 10, 47.51, 19.06, 125);
        body.writeBytes(big);
        body.writeBytes(new byte[]{7, 7});
        // record without its own timestamp (compressed header: local 3, offset), lat/lon only
        body.writeBytes(new byte[]{0x43, 0, 0, 20, 0, 2, 0, 4, (byte) 0x85, 1, 4, (byte) 0x85});
        int offset = (int) ((t0 + 15) & 0x1F);
        ByteBuffer compressed = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN);
        compressed.put((byte) (0x80 | (3 << 5) | offset));
        compressed.putInt(semicircles(47.52)).putInt(semicircles(19.07));
        body.writeBytes(compressed.array());
        byte[] data = body.toByteArray();
        ByteBuffer file = ByteBuffer.allocate(14 + data.length + 2).order(ByteOrder.LITTLE_ENDIAN);
        file.put((byte) 14).put((byte) 0x20).putShort((short) 2132).putInt(data.length)
                .put(".FIT".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putShort((short) 0);
        file.put(data).putShort((short) 0);
        return file.array();
    }

    private static byte[] record(ByteOrder order, int header, long time, double lat, double lon, double altitude) {
        ByteBuffer b = ByteBuffer.allocate(15).order(order);
        b.put((byte) header).putInt((int) time).putInt(semicircles(lat)).putInt(semicircles(lon))
                .putShort((short) Math.round((altitude + 500) * 5));
        return b.array();
    }

    private static int semicircles(double degrees) {
        return (int) Math.round(degrees * 2147483648.0 / 180);
    }

    @Test
    void fitActivities() throws Exception {
        Path f = Files.write(dir.resolve("ride.fit"), fit());
        List<TrackPoint> p = points(TrackReader.read(f));
        assertEquals(3, p.size());
        assertEquals(T0, p.get(0).time());
        assertEquals(47.5, p.get(0).position().getLatitude(), 1e-6);
        assertEquals(120, p.get(0).elevation(), 1e-9);
        assertEquals(T0.plusSeconds(10), p.get(1).time(), "big-endian record");
        assertEquals(19.06, p.get(1).position().getLongitude(), 1e-6);
        assertEquals(125, p.get(1).elevation(), 1e-9);
        assertEquals(T0.plusSeconds(15), p.get(2).time(), "compressed timestamp");
        assertEquals(47.52, p.get(2).position().getLatitude(), 1e-6);
    }
}

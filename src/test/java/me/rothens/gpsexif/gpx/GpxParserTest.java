package me.rothens.gpsexif.gpx;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class GpxParserTest {

    static Track parse(String xml) throws IOException {
        return GpxParser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "test.gpx");
    }

    @Test
    void readsGpx11TrackWithSegmentsElevationAndTime() throws IOException {
        Track track = parse("""
                <?xml version="1.0" encoding="UTF-8"?>
                <gpx version="1.1" creator="Phone" xmlns="http://www.topografix.com/GPX/1/1"
                     xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
                  <metadata><time>2026-09-30T08:00:00Z</time></metadata>
                  <wpt lat="1" lon="1"><time>2026-09-30T08:00:00Z</time></wpt>
                  <trk><name>Morning walk</name>
                    <trkseg>
                      <trkpt lat="47.4979" lon="19.0402"><ele>105.2</ele><time>2026-09-30T08:00:00Z</time>
                        <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>90</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>
                      </trkpt>
                      <trkpt lat="47.4990" lon="19.0410"><time>2026-09-30T10:00:05.250+02:00</time></trkpt>
                      <trkpt lat="bogus" lon="19"><time>2026-09-30T08:00:10Z</time></trkpt>
                    </trkseg>
                    <trkseg>
                      <trkpt lat="47.5" lon="19.05"><ele>110</ele></trkpt>
                    </trkseg>
                    <trkseg/>
                  </trk>
                </gpx>
                """);
        assertEquals("test.gpx", track.name());
        assertEquals(2, track.segments().size(), "empty segments are dropped");
        assertEquals(3, track.pointCount(), "malformed points are skipped, waypoints ignored");
        TrackPoint first = track.segments().get(0).get(0);
        assertEquals(47.4979, first.position().getLatitude(), 1e-9);
        assertEquals(105.2, first.elevation(), 1e-9);
        assertEquals(Instant.parse("2026-09-30T08:00:00Z"), first.time());
        TrackPoint second = track.segments().get(0).get(1);
        assertEquals(Instant.parse("2026-09-30T08:00:05.250Z"), second.time(), "offsets are converted to UTC");
        assertNull(second.elevation());
        assertNull(track.segments().get(1).get(0).time());
        assertEquals(Instant.parse("2026-09-30T08:00:00Z"), track.start());
        assertEquals(Instant.parse("2026-09-30T08:00:05.250Z"), track.end());
    }

    @Test
    void readsGpx10WithoutNamespace() throws IOException {
        Track track = parse("""
                <gpx version="1.0"><trk><trkseg>
                  <trkpt lat="1.5" lon="2.5"><time>2026-01-01T12:00:00</time></trkpt>
                </trkseg></trk></gpx>
                """);
        assertEquals(1, track.pointCount());
        assertEquals(Instant.parse("2026-01-01T12:00:00Z"), track.start(), "zone-less times are UTC");
    }

    @Test
    void rejectsOtherXmlAndDoctypes() {
        assertThrows(IOException.class, () -> parse("<kml><Document/></kml>"));
        assertThrows(IOException.class, () -> parse("not xml at all"));
        assertThrows(IOException.class, () -> parse("""
                <?xml version="1.0"?>
                <!DOCTYPE gpx [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <gpx><trk><trkseg><trkpt lat="1" lon="2"><name>&x;</name></trkpt></trkseg></trk></gpx>
                """));
    }
}

package me.rothens.gpsexif.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MiniJsonTest {

    @Test
    void parsesExifToolOutput() {
        Object parsed = MiniJson.parse("""
                [{
                  "SourceFile": "out.png",
                  "IFD0:ImageDescription": "first\\nsecond\\ttab \\\\ \\"q\\" Máté \\u00e9",
                  "Composite:GPSLatitude": -33.86,
                  "IFD0:Orientation": 6,
                  "XMP-dc:Creator": ["Máté","A"],
                  "Empty": [],
                  "Obj": {},
                  "Flag": true, "Nothing": null, "Exp": 1.5e3
                }]
                """);
        Map<?, ?> tags = (Map<?, ?>) ((List<?>) parsed).get(0);
        assertEquals("first\nsecond\ttab \\ \"q\" Máté é", tags.get("IFD0:ImageDescription"));
        assertEquals(-33.86, tags.get("Composite:GPSLatitude"));
        assertEquals(6.0, tags.get("IFD0:Orientation"));
        assertEquals(List.of("Máté", "A"), tags.get("XMP-dc:Creator"));
        assertEquals(List.of(), tags.get("Empty"));
        assertEquals(Map.of(), tags.get("Obj"));
        assertEquals(Boolean.TRUE, tags.get("Flag"));
        assertTrue(tags.containsKey("Nothing"));
        assertNull(tags.get("Nothing"));
        assertEquals(1500.0, tags.get("Exp"));
        assertEquals(List.of("SourceFile", "IFD0:ImageDescription"), List.copyOf(tags.keySet()).subList(0, 2),
                "document order is kept");
    }

    @Test
    void rejectsMalformedJson() {
        for (String bad : new String[]{"", "[", "{\"a\" 1}", "[1,]", "\"open", "[1] x", "tru", "{1:2}", "\"\\x\""}) {
            assertThrows(IllegalArgumentException.class, () -> MiniJson.parse(bad), bad);
        }
    }
}

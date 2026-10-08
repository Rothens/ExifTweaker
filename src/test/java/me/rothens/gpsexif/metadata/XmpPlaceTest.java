package me.rothens.gpsexif.metadata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class XmpPlaceTest {

    private static final Place TIHANY = new Place("Tihany Abbey", "Tihany", "Veszprém County", "Hungary", "hu");

    @Test
    void newPacketRoundTrips() throws Exception {
        String xmp = XmpPlace.apply(null, TIHANY);
        assertTrue(xmp.contains("<photoshop:City>Tihany</photoshop:City>"), xmp);
        assertTrue(xmp.contains("xpacket"), xmp);
        assertEquals(TIHANY, XmpPlace.read(xmp));
        assertEquals("HU", XmpPlace.read(xmp).countryCode());
    }

    @Test
    void replacesAttributeAndElementFormsAndKeepsEverythingElse() throws Exception {
        String lightroom = """
                <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                 <rdf:Description rdf:about="" xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/"
                   xmlns:xmp="http://ns.adobe.com/xap/1.0/" photoshop:City="Budapest" xmp:Rating="4"/>
                 <rdf:Description rdf:about="" xmlns:Iptc4xmpCore="http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/"
                   xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <Iptc4xmpCore:Location>Parliament</Iptc4xmpCore:Location>
                  <dc:subject><rdf:Bag><rdf:li>holiday</rdf:li></rdf:Bag></dc:subject>
                 </rdf:Description>
                </rdf:RDF></x:xmpmeta>""";
        assertEquals(new Place("Parliament", "Budapest", null, null, null), XmpPlace.read(lightroom));

        String updated = XmpPlace.apply(lightroom, new Place(null, "Tihany", null, "Hungary", "HU"));
        assertEquals(new Place(null, "Tihany", null, "Hungary", "HU"), XmpPlace.read(updated));
        assertFalse(updated.contains("Budapest"), updated);
        assertFalse(updated.contains("Parliament"), updated);
        assertTrue(updated.contains("xmp:Rating=\"4\""), updated);
        assertTrue(updated.contains("<rdf:li>holiday</rdf:li>"), updated);
    }

    @Test
    void noneRemovesThePlace() throws Exception {
        String xmp = XmpPlace.apply(XmpPlace.apply(null, TIHANY), Place.NONE);
        assertNull(XmpPlace.read(xmp));
        assertFalse(xmp.contains("Tihany"));
    }

    @Test
    void unreadableXmpHasNoPlace() {
        assertNull(XmpPlace.read("not xml"));
        assertNull(XmpPlace.read(null));
        assertThrows(java.io.IOException.class, () -> XmpPlace.apply("<broken", TIHANY));
    }

    @Test
    void labels() {
        assertEquals("Tihany Abbey, Tihany, Veszprém County, Hungary", TIHANY.label());
        assertEquals("Tihany, Hungary", TIHANY.shortLabel());
        assertEquals("HUN", TIHANY.countryCode3());
        assertEquals("HU", Place.alpha2("HUN"));
        assertEquals("", Place.NONE.label());
        assertEquals("Budapest", new Place(null, "Budapest", null, "Budapest", null).label());
    }
}

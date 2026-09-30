package me.rothens.gpsexif.map;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PlaceSearchTest {

    private static final String RESPONSE = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <searchresults timestamp='Wed, 30 Sep 26 12:00:00 +0000' querystring='eiffel tower' more_url='x'>
              <place place_id='1' osm_type='way' osm_id='5013364' lat='48.8582599' lon='2.2945006'
                     boundingbox="48.8574753,48.8590453,2.2933119,2.2956897"
                     display_name='Eiffel Tower, Avenue Gustave Eiffel, Paris, France' class='man_made' type='tower'/>
              <place place_id='2' lat='not a number' lon='1' display_name='broken'/>
              <place place_id='3' lat='33.1' lon='-96.6' display_name='Eiffel Tower, Texas'/>
            </searchresults>
            """;

    private final List<URI> requests = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(100_000);
    private final List<Long> sleeps = new ArrayList<>();
    private final PlaceSearch search = new PlaceSearch(uri -> {
        requests.add(uri);
        return new ByteArrayInputStream(RESPONSE.getBytes(StandardCharsets.UTF_8));
    }, now::get, millis -> {
        sleeps.add(millis);
        now.addAndGet(millis);
    });

    @Test
    void parsesPlacesAndSkipsMalformedOnes() throws Exception {
        List<PlaceSearch.Place> places = search.search("Eiffel Tower");
        assertEquals(2, places.size());
        PlaceSearch.Place paris = places.get(0);
        assertEquals("Eiffel Tower, Avenue Gustave Eiffel, Paris, France", paris.name());
        assertEquals(48.8582599, paris.position().getLatitude(), 1e-9);
        assertEquals(2.2945006, paris.position().getLongitude(), 1e-9);
        assertTrue(paris.hasBounds());
        assertEquals(48.8574753, paris.south(), 1e-9);
        assertEquals(2.2956897, paris.east(), 1e-9);
        assertFalse(places.get(1).hasBounds());
    }

    @Test
    void encodesQuery() throws Exception {
        search.search("  Straße & Co  ");
        String uri = requests.get(0).toString();
        assertTrue(uri.startsWith("https://nominatim.openstreetmap.org/search?format=xml&limit="), uri);
        assertTrue(uri.endsWith("&q=Stra%C3%9Fe+%26+Co"), uri);
    }

    @Test
    void cachesResultsIgnoringCaseAndSpacing() throws Exception {
        search.search("Eiffel Tower");
        search.search("  eiffel   TOWER ");
        assertEquals(1, requests.size());
    }

    @Test
    void waitsAtLeastOneSecondBetweenRequests() throws Exception {
        search.search("a");
        now.addAndGet(300);
        search.search("b");
        assertEquals(List.of(700L), sleeps);
        now.addAndGet(5000);
        search.search("c");
        assertEquals(List.of(700L), sleeps, "no wait needed after 5 s");
        assertEquals(3, requests.size());
    }

    @Test
    void blankQueryDoesNothing() throws Exception {
        assertTrue(search.search("   ").isEmpty());
        assertTrue(requests.isEmpty());
    }

    @Test
    void rejectsDoctypeToPreventXxe() {
        String evil = """
                <?xml version="1.0"?>
                <!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <searchresults><place lat='1' lon='2' display_name='&x;'/></searchresults>
                """;
        InputStream in = new ByteArrayInputStream(evil.getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> PlaceSearch.parse(in));
    }
}

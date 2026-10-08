package me.rothens.gpsexif.map;

import me.rothens.gpsexif.metadata.Place;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jxmapviewer.viewer.GeoPosition;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PlaceNamesTest {

    /** Abridged from a real answer for Tihany Abbey. */
    static final String TIHANY = """
            {"place_id":123,"lat":"46.9139","lon":"17.8893","category":"historic","type":"monastery",
             "name":"Tihanyi bencés apátság","display_name":"Tihanyi bencés apátság, Tihany, Veszprém vármegye, Magyarország",
             "address":{"historic":"Tihanyi bencés apátság","road":"I. András tér","village":"Tihany",
               "county":"Veszprém vármegye","state":"Dunántúl","ISO3166-2-lvl6":"HU-VE","postcode":"8237",
               "country":"Magyarország","country_code":"hu"}}""";
    static final String BERKELEY = """
            {"category":"highway","type":"residential","name":"Bancroft Way",
             "address":{"road":"Bancroft Way","suburb":"Southside","city":"Berkeley","county":"Alameda County",
               "state":"California","country":"United States","country_code":"us"}}""";

    @TempDir
    Path dir;

    private final List<URI> requests = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<Long> sleeps = new ArrayList<>();

    private PlaceNames names(String answer, Path cache) {
        PlaceSearch.Throttle throttle = new PlaceSearch.Throttle(now::get, ms -> {
            sleeps.add(ms);
            now.addAndGet(ms);
        });
        return new PlaceNames(uri -> {
            requests.add(uri);
            if (null == answer) {
                throw new IOException("offline");
            }
            return new ByteArrayInputStream(answer.getBytes(StandardCharsets.UTF_8));
        }, throttle, now::get, cache, "hu");
    }

    @Test
    void parsesAnswers() throws Exception {
        assertEquals(new Place("Tihanyi bencés apátság", "Tihany", "Veszprém", "Magyarország", "HU"),
                PlaceNames.parse(TIHANY));
        assertEquals(new Place("Southside", "Berkeley", "California", "United States", "US"),
                PlaceNames.parse(BERKELEY));
        assertEquals(Place.NONE, PlaceNames.parse("{\"error\":\"Unable to geocode\"}"));
        assertThrows(IOException.class, () -> PlaceNames.parse("<html>"));
    }

    @Test
    void nearbyPositionsReuseTheCacheAlsoAfterARestart() throws Exception {
        Path cache = dir.resolve("sub/places.tsv");
        PlaceNames names = names(TIHANY, cache);
        Place place = names.lookup(new GeoPosition(46.9139, 17.8893));
        assertEquals("Tihany", place.city());
        assertEquals(1, requests.size());
        assertTrue(requests.get(0).toString().contains("lat=46.913900&lon=17.889300"), requests.get(0).toString());
        assertTrue(requests.get(0).toString().contains("accept-language=hu"));

        // 50 m away: no request
        assertEquals(place, names.lookup(new GeoPosition(46.9143, 17.8893)));
        assertEquals(1, requests.size());
        // 2 km away: asks again, but not faster than once a second
        names.lookup(new GeoPosition(46.93, 17.8893));
        assertEquals(2, requests.size());
        assertEquals(List.of(1000L), sleeps);

        PlaceNames restarted = names(null, cache);
        assertEquals(place, restarted.cached(new GeoPosition(46.9140, 17.8894)));
        assertEquals(place, restarted.lookup(new GeoPosition(46.9140, 17.8894)));
        assertNull(restarted.cached(new GeoPosition(47.5, 19.04)));
    }

    @Test
    void failsFastForAMinuteAfterAFailure() {
        PlaceNames names = names(null, dir.resolve("places.tsv"));
        assertThrows(IOException.class, () -> names.lookup(new GeoPosition(1, 1)));
        assertThrows(IOException.class, () -> names.lookup(new GeoPosition(2, 2)));
        assertEquals(1, requests.size(), "the second one didn't try");
        now.addAndGet(61_000);
        assertThrows(IOException.class, () -> names.lookup(new GeoPosition(2, 2)));
        assertEquals(2, requests.size());
    }

    @Test
    void otherLanguagesAreLookedUpAgain() throws Exception {
        Path cache = dir.resolve("places.tsv");
        names(TIHANY, cache).lookup(new GeoPosition(46.9139, 17.8893));
        PlaceNames english = new PlaceNames(uri -> new ByteArrayInputStream(BERKELEY.getBytes(StandardCharsets.UTF_8)),
                new PlaceSearch.Throttle(now::get, ms -> { }), now::get, cache, "en");
        assertNull(english.cached(new GeoPosition(46.9139, 17.8893)));
    }
}

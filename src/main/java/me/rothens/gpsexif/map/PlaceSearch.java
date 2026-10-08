package me.rothens.gpsexif.map;

import me.rothens.gpsexif.util.SafeXml;
import org.jxmapviewer.viewer.GeoPosition;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Looks up places by name with OpenStreetMap's Nominatim service, following its usage policy
 * (https://operations.osmfoundation.org/policies/nominatim/): at most one request per second, an identifying
 * User-Agent, and results are cached so repeated searches don't hit the service again.
 */
public class PlaceSearch {

    private static final String ENDPOINT = "https://nominatim.openstreetmap.org/search";
    private static final long MIN_INTERVAL_MS = 1000;
    private static final int MAX_RESULTS = 8;
    private static final int CACHE_SIZE = 100;

    /** A search result. {@code south/north/west/east} is the place's bounding box, if Nominatim returned one. */
    public record Place(String name, GeoPosition position, double south, double north, double west, double east) {
        public boolean hasBounds() {
            return !Double.isNaN(south);
        }
    }

    /** Fetches a URL; replaceable for tests. */
    public interface Fetcher {
        InputStream get(URI uri) throws IOException;
    }

    /** Waits; replaceable for tests. */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * Keeps requests to Nominatim at least a second apart. One instance is shared by the place search and the place
     * name lookup, so together they stay within the limit.
     */
    public static final class Throttle {
        /** The one used by the app. */
        public static final Throttle SHARED = new Throttle(System::currentTimeMillis, Thread::sleep);

        private final LongSupplier clock;
        private final Sleeper sleeper;
        private long lastRequest = Long.MIN_VALUE / 2;

        Throttle(LongSupplier clock, Sleeper sleeper) {
            this.clock = clock;
            this.sleeper = sleeper;
        }

        /** Waits until the next request may be sent. */
        synchronized void await() throws InterruptedException {
            long wait = lastRequest + MIN_INTERVAL_MS - clock.getAsLong();
            if (wait > 0) {
                sleeper.sleep(wait);
            }
            lastRequest = clock.getAsLong();
        }
    }

    private final Fetcher fetcher;
    private final Throttle throttle;
    private final Map<String, List<Place>> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Place>> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    public PlaceSearch(String userAgent) {
        this(httpFetcher(userAgent), Throttle.SHARED);
    }

    PlaceSearch(Fetcher fetcher, LongSupplier clock, Sleeper sleeper) {
        this(fetcher, new Throttle(clock, sleeper));
    }

    PlaceSearch(Fetcher fetcher, Throttle throttle) {
        this.fetcher = fetcher;
        this.throttle = throttle;
    }

    /** Searches for places; blocks, so call it off the event thread. Returns an empty list if nothing matched. */
    public synchronized List<Place> search(String query) throws IOException, InterruptedException {
        String key = query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return List.of();
        }
        List<Place> cached = cache.get(key);
        if (null != cached) {
            return cached;
        }
        throttle.await();
        URI uri = URI.create(ENDPOINT + "?format=xml&limit=" + MAX_RESULTS + "&q="
                + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8));
        List<Place> places;
        try (InputStream in = fetcher.get(uri)) {
            places = parse(in);
        }
        cache.put(key, places);
        return places;
    }

    /** Parses Nominatim's {@code format=xml} search response. */
    static List<Place> parse(InputStream xml) throws IOException {
        try {
            // The response comes from the network
            DocumentBuilder builder = SafeXml.newDocumentBuilder(false);
            NodeList nodes = builder.parse(xml).getElementsByTagName("place");
            List<Place> places = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                Element e = (Element) nodes.item(i);
                try {
                    double lat = Double.parseDouble(e.getAttribute("lat"));
                    double lon = Double.parseDouble(e.getAttribute("lon"));
                    double[] box = parseBox(e.getAttribute("boundingbox"));
                    places.add(new Place(e.getAttribute("display_name"), new GeoPosition(lat, lon),
                            box[0], box[1], box[2], box[3]));
                } catch (NumberFormatException ignored) {
                    // Skip malformed entries
                }
            }
            return List.copyOf(places);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Unexpected response from the place search: " + e.getMessage(), e);
        }
    }

    /** "south,north,west,east" or NaNs. */
    private static double[] parseBox(String text) {
        String[] parts = text.split(",");
        if (parts.length == 4) {
            try {
                return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2]), Double.parseDouble(parts[3])};
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    }

    static Fetcher httpFetcher(String userAgent) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return uri -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", userAgent)
                    .header("Accept-Language", Locale.getDefault().toLanguageTag())
                    .GET()
                    .build();
            try {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    response.body().close();
                    throw new IOException("OpenStreetMap Nominatim answered HTTP " + response.statusCode());
                }
                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
        };
    }
}

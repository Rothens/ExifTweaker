package me.rothens.gpsexif.map;

import static me.rothens.gpsexif.i18n.I18n.tr;
import me.rothens.gpsexif.gpx.TrackMatcher;
import me.rothens.gpsexif.metadata.Place;
import me.rothens.gpsexif.util.MiniJson;
import org.jxmapviewer.viewer.GeoPosition;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Finds the place name of a position ("Tihany, Veszprém, Hungary") with OpenStreetMap Nominatim's reverse
 * geocoding, within its usage policy: at most one request per second (shared with {@link PlaceSearch}), and every
 * answer is kept on disk, so a position near one looked up before (by default within 150 m) is never asked again.
 * After a failure (e.g. offline), lookups fail at once for a minute instead of each waiting for a timeout.
 */
public class PlaceNames {

    private static final String ENDPOINT = "https://nominatim.openstreetmap.org/reverse";
    /** Positions this close to a cached one get its place. */
    public static final double REUSE_METRES = 150;
    private static final long RETRY_AFTER_FAILURE_MS = 60_000;
    /** Countries whose top level below the country is a statistical region; the county is what people use. */
    private static final Set<String> PREFER_COUNTY = Set.of("hu");

    private record Entry(String language, double lat, double lon, Place place) {
    }

    private final PlaceSearch.Fetcher fetcher;
    private final PlaceSearch.Throttle throttle;
    private final LongSupplier clock;
    private final Path cacheFile;
    private final String language;
    private List<Entry> cache;
    private long failedAt = Long.MIN_VALUE / 2;
    private IOException lastFailure;

    /** @param cacheFile where answers are kept, e.g. {@code ~/.exiftweaker/places.tsv} */
    public PlaceNames(String userAgent, Path cacheFile) {
        this(PlaceSearch.httpFetcher(userAgent), PlaceSearch.Throttle.SHARED, System::currentTimeMillis, cacheFile,
                Locale.getDefault().toLanguageTag());
    }

    PlaceNames(PlaceSearch.Fetcher fetcher, PlaceSearch.Throttle throttle, LongSupplier clock, Path cacheFile,
               String language) {
        this.fetcher = fetcher;
        this.throttle = throttle;
        this.clock = clock;
        this.cacheFile = cacheFile;
        this.language = language;
    }

    /** The default cache file: {@code ~/.exiftweaker/places.tsv}. */
    public static Path defaultCacheFile() {
        return Path.of(System.getProperty("user.home"), ".exiftweaker", "places.tsv");
    }

    /**
     * The place at {@code position}; {@link Place#NONE} if there's none (e.g. out at sea). Blocks for up to a second
     * per uncached position (and the network), so call it off the event thread.
     *
     * @throws IOException if Nominatim couldn't be asked (offline, refused)
     */
    public synchronized Place lookup(GeoPosition position) throws IOException, InterruptedException {
        Place cached = cached(position);
        if (null != cached) {
            return cached;
        }
        if (clock.getAsLong() - failedAt < RETRY_AFTER_FAILURE_MS) {
            throw lastFailure;
        }
        throttle.await();
        URI uri = URI.create(String.format(Locale.ROOT,
                "%s?format=jsonv2&zoom=18&addressdetails=1&lat=%.6f&lon=%.6f&accept-language=%s",
                ENDPOINT, position.getLatitude(), position.getLongitude(), language));
        Place place;
        try (InputStream in = fetcher.get(uri)) {
            place = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            failedAt = clock.getAsLong();
            lastFailure = new IOException(tr("Couldn't look up the place name: {0}", e.getMessage()), e);
            throw lastFailure;
        }
        remember(position, place);
        return place;
    }

    /** The cached place near {@code position}, or {@code null} if it would have to be looked up. */
    public synchronized Place cached(GeoPosition position) {
        Entry nearest = null;
        double best = REUSE_METRES;
        for (Entry e : entries()) {
            if (!e.language().equals(language) || Math.abs(e.lat() - position.getLatitude()) > 0.01) {
                continue;
            }
            double d = TrackMatcher.distanceMetres(position, new GeoPosition(e.lat(), e.lon()));
            if (d <= best) {
                best = d;
                nearest = e;
            }
        }
        return null == nearest ? null : nearest.place();
    }

    /** Turns Nominatim's {@code format=jsonv2} reverse answer into a place. */
    static Place parse(String json) throws IOException {
        Object parsed;
        try {
            parsed = MiniJson.parse(json);
        } catch (IllegalArgumentException e) {
            throw new IOException("Unexpected answer from Nominatim: " + e.getMessage(), e);
        }
        if (!(parsed instanceof Map<?, ?> result)) {
            throw new IOException("Unexpected answer from Nominatim");
        }
        if (null != result.get("error") || !(result.get("address") instanceof Map<?, ?> address)) {
            return Place.NONE; // "Unable to geocode": nothing there
        }
        String code = text(address, "country_code");
        String city = first(address, "city", "town", "village", "municipality", "hamlet");
        String state = null != code && PREFER_COUNTY.contains(code.toLowerCase(Locale.ROOT))
                ? first(address, "county", "state", "state_district", "region", "province")
                : first(address, "state", "province", "region", "state_district", "county");
        if (null != state && null != code && code.equalsIgnoreCase("hu")) {
            state = state.replaceFirst("\\s+(vármegye|megye|County)$", "");
        }
        String name = text(result, "name");
        String category = text(result, "category");
        // The part of the city: Namba in Osaka, Chiyoda in Tokyo, Lipótváros in Budapest
        String district = first(address, "suburb", "quarter", "city_district", "borough", "neighbourhood");
        if (null != district && district.equals(city)) {
            district = null;
        }
        // A landmark if the spot is one, else the neighbourhood when it's finer than the district
        String sublocation = null != name && null != category && Set.of("tourism", "historic", "leisure", "natural",
                "amenity", "man_made", "waterway", "building").contains(category) && !name.equals(city)
                ? name : first(address, "neighbourhood", "quarter");
        if (null != sublocation && (sublocation.equals(city) || sublocation.equals(district))) {
            sublocation = null;
        }
        return new Place(sublocation, city, state, text(address, "country"), code, district);
    }

    private static String first(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            String value = text(map, key);
            if (null != value) {
                return value;
            }
        }
        return null;
    }

    private static String text(Map<?, ?> map, String key) {
        return map.get(key) instanceof String s && !s.isBlank() ? s.strip() : null;
    }

    private List<Entry> entries() {
        if (null == cache) {
            cache = new ArrayList<>();
            try {
                if (Files.exists(cacheFile)) {
                    for (String line : Files.readAllLines(cacheFile, StandardCharsets.UTF_8)) {
                        String[] f = line.split("\t", -1);
                        // Older lines without the district (8 fields) are looked up again
                        if (f.length == 9) {
                            try {
                                cache.add(new Entry(f[0], Double.parseDouble(f[1]), Double.parseDouble(f[2]),
                                        new Place(f[3], f[4], f[5], f[6], f[7], f[8])));
                            } catch (NumberFormatException ignored) {
                                // skip a broken line
                            }
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("Couldn't read the place name cache: " + e.getMessage());
            }
        }
        return cache;
    }

    private void remember(GeoPosition position, Place place) {
        Entry entry = new Entry(language, position.getLatitude(), position.getLongitude(), place);
        entries().add(entry);
        try {
            Files.createDirectories(cacheFile.toAbsolutePath().getParent());
            try (BufferedWriter out = Files.newBufferedWriter(cacheFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                out.write(String.join("\t", language, String.format(Locale.ROOT, "%.6f", entry.lat()),
                        String.format(Locale.ROOT, "%.6f", entry.lon()), field(place.sublocation()),
                        field(place.city()), field(place.state()), field(place.country()), field(place.countryCode()),
                        field(place.district())));
                out.newLine();
            }
        } catch (IOException e) {
            System.err.println("Couldn't save the place name cache: " + e.getMessage());
        }
    }

    private static String field(String value) {
        return null == value ? "" : value.replaceAll("[\t\r\n]+", " ");
    }
}

package me.rothens.gpsexif.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Asks GitHub for the latest ExifTweaker release, to tell the user when a newer version is out. Nothing is
 * downloaded or installed; only the release's version and page are read.
 */
public final class UpdateCheck {

    public static final String LATEST = "https://api.github.com/repos/rothens/ExifTweaker/releases/latest";
    public static final String RELEASES_PAGE = "https://github.com/rothens/ExifTweaker/releases/latest";
    /** How often the background check asks GitHub. */
    public static final Duration INTERVAL = Duration.ofDays(1);

    /** A published release: its version (e.g. "1.4.0") and web page. */
    public record Release(String version, String url) {
    }

    /** Fetches a URL; replaceable for tests. */
    public interface Fetcher {
        String get(URI uri) throws IOException;
    }

    private final Fetcher fetcher;

    public UpdateCheck(String userAgent) {
        this(httpFetcher(userAgent));
    }

    UpdateCheck(Fetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The latest published release; blocks, so call it off the event thread. */
    public Release latest() throws IOException {
        return parse(fetcher.get(URI.create(LATEST)));
    }

    static Release parse(String json) throws IOException {
        Object parsed;
        try {
            parsed = MiniJson.parse(json);
        } catch (IllegalArgumentException e) {
            throw new IOException("Unexpected answer from GitHub: " + e.getMessage(), e);
        }
        if (!(parsed instanceof Map<?, ?> release) || !(release.get("tag_name") instanceof String tag)) {
            throw new IOException("Unexpected answer from GitHub");
        }
        String url = release.get("html_url") instanceof String s && s.startsWith("https://github.com/")
                ? s : RELEASES_PAGE;
        return new Release(tag.replaceFirst("^[vV]", "").strip(), url);
    }

    /**
     * Whether {@code candidate} is a newer version than {@code current}, comparing the numbers ("1.10.0" is newer
     * than "1.9.2"). A release is newer than a development build of the same number ("1.4.0" vs "1.4.0-SNAPSHOT").
     * Versions that can't be read are never newer.
     */
    public static boolean isNewer(String candidate, String current) {
        List<Integer> a = numbers(candidate);
        List<Integer> b = numbers(current);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            int x = i < a.size() ? a.get(i) : 0;
            int y = i < b.size() ? b.get(i) : 0;
            if (x != y) {
                return x > y;
            }
        }
        // Same numbers: only a release beats a pre-release (1.4.0 > 1.4.0-SNAPSHOT)
        return !candidate.contains("-") && current.contains("-");
    }

    private static List<Integer> numbers(String version) {
        List<Integer> numbers = new ArrayList<>();
        if (null == version) {
            return numbers;
        }
        String core = version.strip().replaceFirst("^[vV]", "").split("-", 2)[0];
        for (String part : core.split("\\.")) {
            if (!part.matches("\\d{1,9}")) {
                return List.of();
            }
            numbers.add(Integer.parseInt(part));
        }
        return numbers;
    }

    private static Fetcher httpFetcher(String userAgent) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        return uri -> {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("User-Agent", userAgent).header("Accept", "application/vnd.github+json").GET().build();
            try {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = response.body()) {
                    if (response.statusCode() != 200) {
                        throw new IOException("GitHub answered HTTP " + response.statusCode());
                    }
                    return new String(body.readNBytes(1 << 20), StandardCharsets.UTF_8);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
        };
    }
}
